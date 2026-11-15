package kr.sidelab.donghwa

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Pleos LLM SDK 로 장면 문장을 미리 만들어 두는 층.**
 *
 * ⛔⛔ 이 클래스의 설계를 정한 것은 실측 한 줄이다(SDK_FACTS 7.10, 2026-10-30):
 *
 * ```
 * [GAAS]OnDeviceLLMManagerImpl: [onDeviceLLM] initialize(...) ignored: on-device LLM is disabled
 * ```
 *
 * 권한도 받고 서비스 바인드도 되고 콜백 등록까지 되는데, **추론만 조용히 안 된다.**
 * `onError` 조차 오지 않는다 — 부르면 그냥 **아무 일도 안 일어난다.**
 * 그래서 이 층은 다음 세 가지를 반드시 지킨다.
 *
 * 1. **우리 타임아웃을 건다.** SDK 가 안 알려 주므로 우리가 시간을 재는 수밖에 없다.
 * 2. **차단기를 둔다.** 연달아 [GIVE_UP_AFTER] 번 실패하면 **이 주행 내내 다시 부르지 않는다.**
 *    에뮬레이터처럼 아예 꺼진 기계에서 매 장면 타임아웃을 기다리면 그게 더 해롭다.
 * 3. **이야기 경로를 막지 않는다.** 전부 제 스레드에서 돌고, 결과는 캐시에만 넣는다.
 *    [ready] 는 캐시를 들여다볼 뿐이라 즉시 돌아온다.
 *
 * 그래서 **생성이 안 되는 기계에서도 앱은 똑같이 돌아간다** — 내장 이야기가 그대로 나간다.
 * 에뮬레이터에서 이 클래스는 조용히 꺼지고, 양산기에서는 켜진다.
 */
class LlmWriter(
    private val childName: String,
    private val guestName: String,
    private val vehicle: String,
    private val level: AgeLevel,
    /** 가는 곳. 생성 지시문에 들어간다. */
    private val destination: () -> String?,
    /** 남은 분. 모르면 null. */
    private val minutesLeft: () -> Int?,
    /** 실제 호출부. [Engine] 은 [PleosBackend] 를 넣고, 시험은 가짜를 넣는다 —
     *  에뮬레이터 없이 생성 경로를 전수로 돌리려고 이렇게 갈라 두었다. */
    private val backend: Backend,
    /**
     * 생성 요청이 도는 곳. 기본은 전용 스레드 하나다.
     *
     * ⚠️ `HandlerThread` 가 아니라 평범한 [Executor] 를 쓴다 — **그래야 에뮬레이터 없이
     *    이 클래스를 시험할 수 있다.** 안드로이드 타입에 묶어 두면 생성 경로가 영영
     *    검사 밖에 남는다(그게 지금까지 이 기능이 없던 이유이기도 하다).
     */
    private val worker: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "donghwa-llm").apply { isDaemon = true }
    },
) : StoryWriter {

    companion object {
        const val TAG = "DonghwaLlm"

        /**
         * 한 장면을 기다리는 시간. 넘으면 버린다.
         *
         * 짧게 잡은 이유: 이건 **미리 만들어 두는 일**이라 늦게 와도 쓸 데가 없다.
         * 쪽 간격이 제일 짧을 때가 1.5초이므로(압축 재생), 그보다 넉넉하되 길지 않게 둔다.
         */
        const val TIMEOUT_MS = 4_000L

        /** 연달아 이만큼 실패하면 이 주행에서는 생성을 포기한다. */
        const val GIVE_UP_AFTER = 3

        /** 캐시 상한. 한 구간 앞만 쓰므로 많이 들고 있을 이유가 없다. */
        const val CACHE_MAX = 24
    }

    /**
     * 생성기를 부르는 길. Pleos SDK 를 직접 쓰지 않는 이유는 **시험 때문**이다 —
     * 에뮬레이터에서 LLM 이 꺼져 있어서, 이 인터페이스가 없으면 생성 경로를
     * **한 줄도 검사할 수 없다**(그게 지금까지 이 기능이 없던 이유이기도 하다).
     */
    interface Backend {
        /** @param onDone 성공하면 문장, 실패·타임아웃이면 null. 아무 스레드에서나 불릴 수 있다. */
        fun generate(system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit)
        fun close()
    }

    private val cache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fails = AtomicInteger(0)
    private val givenUp = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val system = StoryPrompt.system(childName, guestName, vehicle, level)

    /** 생성이 실제로 쓰인 횟수 / 막힌 횟수. 시연과 하네스가 이걸 보고 "정말 돌았나" 를 센다. */
    val used = AtomicInteger(0)
    val filtered = AtomicInteger(0)

    override fun ready(original: String): String? {
        if (closed.get()) return null
        return cache.remove(original)
    }

    override fun prepare(requests: List<StoryWriter.Request>) {
        if (closed.get() || givenUp.get()) return
        for (r in requests) {
            // 뼈대는 건드리지 않는다 — 인과가 거기 걸려 있다.
            if (r.core) continue
            if (cache.containsKey(r.original) || !inFlight.add(r.original)) continue
            if (cache.size >= CACHE_MAX) { inFlight.remove(r.original); continue }
            worker.execute { run(r) }
        }
    }

    private fun run(r: StoryWriter.Request) {
        if (closed.get() || givenUp.get()) { inFlight.remove(r.original); return }
        val user = StoryPrompt.rewrite(r, destination(), minutesLeft())
        backend.generate(system, user, TIMEOUT_MS) { out ->
            inFlight.remove(r.original)
            if (closed.get()) return@generate
            if (out == null) {
                // ⛔ 여기가 에뮬레이터가 떨어지는 자리다. 조용히 세고, 세 번이면 그만둔다.
                val n = fails.incrementAndGet()
                if (n >= GIVE_UP_AFTER && givenUp.compareAndSet(false, true)) {
                    Log.i(TAG, "생성 ${n}회 연속 실패 — 이 주행에서는 내장 이야기로만 간다")
                    backend.close()
                }
                return@generate
            }
            fails.set(0)
            val safe = ChildFilter.check(out, r.original, level)
            if (safe == null) {
                filtered.incrementAndGet()
                Log.i(TAG, "필터에 걸림, 내장 문장으로: $out")
                return@generate
            }
            cache[r.original] = safe
            used.incrementAndGet()
            Log.i(TAG, "생성: ${r.original} → $safe")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cache.clear()
        inFlight.clear()
        runCatching { backend.close() }
        (worker as? java.util.concurrent.ExecutorService)?.shutdownNow()
        Log.i(TAG, "닫음 — 생성 ${used.get()}줄 · 필터 ${filtered.get()}줄 · 포기 ${givenUp.get()}")
    }
}

/**
 * 진짜 Pleos LLM SDK 를 부르는 [LlmWriter.Backend].
 *
 * ⚠️ SDK 타입을 **리플렉션으로** 잡는다. 이유가 둘이다.
 *  ⑴ `ai.pleos.playground:LLM` 은 공모전 공식 SDK 목록(FAQ)에 **없다.** 아티팩트는 있지만
 *     언제 사라져도 이상하지 않다. 직접 참조하면 그날 **앱이 통째로 안 뜬다.**
 *  ⑵ 양산기와 에뮬레이터의 SDK 판이 다를 수 있다. 메서드가 없으면 그냥 생성이 안 되는 것으로
 *     떨어져야지, `NoSuchMethodError` 로 죽으면 안 된다.
 *
 * 그래서 **클래스가 없으면 조용히 '생성 불가'** 가 된다 — 내장 이야기로 돈다.
 */
class PleosBackend(private val context: Context) : LlmWriter.Backend {

    companion object {
        const val TAG = "DonghwaLlm"
        const val LLM_CLASS = "ai.pleos.playground.llm.LLM"
    }

    private var llm: Any? = null
    private val dead = AtomicBoolean(false)

    /** 타임아웃 시계. SDK 가 무응답일 때 우리를 깨우는 유일한 장치다. */
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "donghwa-llm-timeout").apply { isDaemon = true }
    }

    private fun instance(): Any? {
        if (dead.get()) return null
        llm?.let { return it }
        return runCatching {
            val cls = Class.forName(LLM_CLASS)
            val modeCls = Class.forName("ai.pleos.playground.llm.constant.Mode")
            @Suppress("UNCHECKED_CAST")
            val onDevice = java.lang.Enum.valueOf(modeCls as Class<out Enum<*>>, "ON_DEVICE")
            val obj = cls.getConstructor(Context::class.java, modeCls).newInstance(context, onDevice)
            runCatching { cls.getMethod("initialize").invoke(obj) }
            llm = obj
            obj
        }.getOrElse {
            Log.i(TAG, "Pleos LLM SDK 없음(${it.javaClass.simpleName}) — 내장 이야기로 간다")
            dead.set(true)
            null
        }
    }

    override fun generate(system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
        val obj = instance() ?: return onDone(null)
        // ⛔ **한 번만 부른다.** SDK 가 콜백을 안 주는 경우가 있어(on-device LLM is disabled)
        //    타임아웃과 콜백이 **둘 다** 올 수 있다. 먼저 온 쪽만 받는다.
        val answered = AtomicBoolean(false)
        var alarm: java.util.concurrent.ScheduledFuture<*>? = null
        fun finish(s: String?) {
            if (!answered.compareAndSet(false, true)) return
            alarm?.cancel(false)   // 안 걷으면 성공한 호출마다 타이머가 하나씩 남는다
            onDone(s)
        }
        alarm = timer.schedule({
            Log.i(TAG, "생성 타임아웃 ${timeoutMs}ms — 내장 문장으로")
            finish(null)
        }, timeoutMs, TimeUnit.MILLISECONDS)

        runCatching {
            val cls = obj.javaClass
            // generateContent(prompt, callback) 꼴을 찾는다. 판마다 이름이 달라 후보를 훑는다.
            val m = cls.methods.firstOrNull {
                it.name in setOf("generateContent", "generateContentStream", "startChat") &&
                    it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
            } ?: error("generateContent 없음")
            val cbType = m.parameterTypes[1]
            val cb = java.lang.reflect.Proxy.newProxyInstance(
                cbType.classLoader, arrayOf(cbType)
            ) { _, method, args ->
                when {
                    method.name.contains("Error", true) || method.name.contains("Fail", true) -> {
                        Log.i(TAG, "생성 오류: ${args?.firstOrNull()}")
                        finish(null)
                    }
                    args?.firstOrNull() is String -> finish(args[0] as String)
                }
                null
            }
            m.invoke(obj, "$system\n\n$user", cb)
        }.onFailure {
            Log.i(TAG, "생성 호출 실패: ${it.message}")
            finish(null)
        }
    }

    override fun close() {
        runCatching { llm?.javaClass?.getMethod("release")?.invoke(llm) }
        llm = null
        dead.set(true)
        timer.shutdownNow()
    }
}
