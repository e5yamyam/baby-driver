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
        /**
         * @param key    그 쪽의 좌표(`시즌:화:쪽:나이`). 진짜 모델은 안 쓰지만
         *               [CannedBackend] 는 **이걸로 미리 만들어 둔 답을 찾는다.**
         * @param onDone 성공하면 문장, 실패·타임아웃이면 null. 아무 스레드에서나 불릴 수 있다.
         */
        fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit)
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

    override fun ready(key: String): String? {
        if (closed.get()) return null
        return cache.remove(key)
    }

    override fun prepare(requests: List<StoryWriter.Request>) {
        if (closed.get() || givenUp.get()) return
        for (r in requests) {
            // 뼈대는 건드리지 않는다 — 인과가 거기 걸려 있다.
            if (r.core) continue
            if (cache.containsKey(r.key) || !inFlight.add(r.key)) continue
            if (cache.size >= CACHE_MAX) { inFlight.remove(r.key); continue }
            // 🔴 **여기서 새는 예외는 앱을 통째로 죽인다.** 전용 스레드에서 올라온 예외는
            //    잡아 주는 사람이 없어 그대로 프로세스 종료로 간다 — 이야기가 한 줄 읽히고 끝났다
            //    (2026-11-17, ChildFilter 의 정규식이 안드로이드에서만 터졌다).
            //    이 층의 약속은 "생성이 안 돼도 앱은 똑같이 돈다" 이다. 그 약속을 코드로 못박는다.
            worker.execute {
                try {
                    run(r)
                } catch (t: Throwable) {
                    inFlight.remove(r.key)
                    if (givenUp.compareAndSet(false, true)) {
                        Log.w(TAG, "생성층이 터졌다 — 이 주행은 내장 이야기로만 간다", t)
                    }
                }
            }
        }
    }

    private fun run(r: StoryWriter.Request) {
        if (closed.get() || givenUp.get()) { inFlight.remove(r.key); return }
        val user = StoryPrompt.rewrite(r, destination(), minutesLeft())
        backend.generate(r.key, system, user, TIMEOUT_MS) { out -> runCatching {
            // ⚠️ 이 콜백은 **아무 스레드에서나** 불린다([Backend.generate] 계약).
            //    [PleosBackend] 는 SDK 스레드나 타임아웃 시계에서 부르는데, 거기서 샌 예외도
            //    앱을 죽인다. 위 `worker.execute` 의 try 로는 거기까지 못 막는다.
            inFlight.remove(r.key)
            if (closed.get()) return@runCatching
            if (out == null) {
                // ⛔ 여기가 에뮬레이터가 떨어지는 자리다. 조용히 세고, 세 번이면 그만둔다.
                val n = fails.incrementAndGet()
                if (n >= GIVE_UP_AFTER && givenUp.compareAndSet(false, true)) {
                    Log.i(TAG, "생성 ${n}회 연속 실패 — 이 주행에서는 내장 이야기로만 간다")
                    backend.close()
                }
                return@runCatching
            }
            fails.set(0)
            val safe = ChildFilter.check(out, r.original, level)
            if (safe == null) {
                filtered.incrementAndGet()
                Log.i(TAG, "필터에 걸림, 내장 문장으로: $out")
                return@runCatching
            }
            cache[r.key] = safe
            used.incrementAndGet()
            Log.i(TAG, "생성[${r.key}]: ${r.original} → $safe")
        }.onFailure { t ->
            inFlight.remove(r.key)
            if (givenUp.compareAndSet(false, true)) {
                Log.w(TAG, "생성 콜백이 터졌다 — 이 주행은 내장 이야기로만 간다", t)
            }
        } }
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

    override fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
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

/**
 * **미리 만들어 둔 생성문을 읽어 주는 [LlmWriter.Backend]** — 시연용.
 *
 * ## 왜 필요한가
 * 2차 발표는 **차 없이** 한다. 그런데 온디바이스 LLM 은 에뮬레이터·노트북에서 꺼져 있어서
 * 거기서 돌리면 **생성이 한 줄도 안 나온다**(SDK_FACTS 7.10). 그러면 접수 서류가 내세운
 * "AI 가 그날 주행에 맞는 문장을 쓴다" 를 **보여 줄 수가 없다.**
 *
 * 그래서 앱의 지시문([StoryPrompt])과 어린이 필터([ChildFilter])를 **코드 그대로** 돌려
 * 받아 둔 답을 `assets/demo/생성문장.tsv` 에 넣고, 시연에서는 그것을 읽는다.
 * 만드는 길은 `tools/gen_probe.py --canned` 이고, 받은 문장은 `docs/생성_실측.md` 에 그대로 있다.
 *
 * ## 거짓말이 되지 않게 지키는 것
 * - **설정에서 사람이 켜야 한다**([Settings.demoGenerateOn], 기본 꺼짐). 몰래 켜지지 않는다.
 * - **아이 이름이 시연 프로필과 다르면 아무 답도 안 한다.** 미리 만든 문장에는 시연 이름이
 *   박혀 있으므로, 다른 이름을 쓰는 아이에게 **남의 이름을 읽어 주면 안 된다.**
 * - 양산기에서는 이 백엔드를 쓰지 않는다 — 거기선 [PleosBackend] 가 그 자리에서 만든다.
 */
class CannedBackend(
    context: Context,
    /** 지금 설정된 아이 이름. 시연 프로필과 같을 때만 답한다. */
    private val childName: () -> String,
) : LlmWriter.Backend {

    /** 읽어 들인 시연 파일. [forName] 이 그 파일을 만들 때 쓴 아이 이름이다. */
    data class Canned(val forName: String?, val answers: Map<String, String>) {
        companion object {
            val EMPTY = Canned(null, emptyMap())
        }
    }

    companion object {
        const val TAG = "DonghwaLlm"
        // ⛔ **ASCII 이름만 쓴다.** 맥은 파일 이름을 NFD 로 적고 코틀린 문자열은 NFC 라
        //    한글 이름을 주면 `assets.open` 이 **못 찾는다** — 그러면 시연 모드를 켜도
        //    아무 답이 없어 차단기가 돌고, 로그만 보면 "생성 0줄" 이라 원인이 안 보인다
        //    (2026-11-17 첫 녹화가 그래서 생성 0줄로 나왔다).
        const val PATH = "demo/canned.tsv"

        /** 첫 줄에 적어 둔 시연 프로필 이름을 읽는 머리말. */
        const val HEAD = "#이름\t"

        /**
         * 시연 파일을 읽는다. **순수 함수로 둔 이유는 검사 때문이다** —
         * `Context` 가 필요하면 이 로직이 에뮬레이터 밖에서 한 줄도 안 돌아간다.
         */
        fun parse(lines: Sequence<String>): Canned {
            var who: String? = null
            val m = HashMap<String, String>()
            for (ln in lines) {
                if (ln.startsWith(HEAD)) { who = ln.removePrefix(HEAD).trim(); continue }
                if (ln.isBlank() || ln.startsWith("#")) continue
                val f = ln.split("\t")
                if (f.size >= 2 && f[0].isNotBlank() && f[1].isNotBlank()) m[f[0]] = f[1].trim()
            }
            return Canned(who, m)
        }

        /**
         * 그 열쇠의 답. **이름이 다르면 아무 답도 안 한다.**
         *
         * ⛔ 미리 만든 문장에는 시연 프로필 이름이 박혀 있다. 다른 이름을 쓰는 아이에게
         *    그걸 읽어 주면 **남의 이름을 부르는 동화**가 된다 — 그럴 땐 내장 이야기가 나간다.
         */
        fun answer(canned: Canned, who: String, key: String): String? {
            val made = canned.forName ?: return null
            if (made != who) return null
            return canned.answers[key]
        }
    }

    private val canned: Canned = runCatching {
        context.assets.open(PATH).bufferedReader().useLines { parse(it) }
    }.getOrElse {
        Log.i(TAG, "시연용 생성문 없음(${it.javaClass.simpleName})")
        Canned.EMPTY
    }.also { Log.i(TAG, "시연용 생성문 ${it.answers.size}줄 · 프로필 이름 ${it.forName}") }

    /** 못 찾은 열쇠를 처음 몇 번만 적는다. 0 줄로 끝났을 때 **왜** 인지 알려면 이게 있어야 한다. */
    private val 빗나간횟수 = java.util.concurrent.atomic.AtomicInteger(0)

    override fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
        val who = childName()
        val got = answer(canned, who, key)
        if (got == null) {
            if (canned.forName != who) {
                Log.i(TAG, "시연 프로필(${canned.forName})과 아이 이름($who)이 달라 생성을 쓰지 않는다")
            } else if (빗나간횟수.incrementAndGet() <= 3) {
                // 이름은 맞는데 열쇠가 없다 — 미리 만들 때와 꺼낼 때의 좌표가 어긋난 것이다.
                Log.i(TAG, "시연용 열쇠 없음: $key (가진 열쇠 ${canned.answers.size}개, 예: ${canned.answers.keys.take(3)})")
            }
        }
        onDone(got)
    }

    override fun close() = Unit
}
