package kr.sidelab.donghwa

import android.content.Context
import android.util.Log

/**
 * 이야기 엔진을 화면 밖에 둔다.
 *
 * 왜: 액티비티가 다시 만들어지면 수집부·음성이 두 벌이 된다. 실제로 Pleos Connect Emulator 에서
 * `onCreate` 가 두 번 돌아 **같은 이벤트가 두 번 들어오고 말이 두 번 나갔다**(2026-10-29 실측).
 * 그래서 엔진은 한 번만 만들고 화면만 붙였다 뗀다.
 *
 * 나중에 주행 중 백그라운드로 내려가도 이야기가 이어지게 하려면 여기를 포그라운드 서비스로 올린다.
 */
object Engine {

    private const val TAG = "DonghwaCar"

    private var app: Context? = null
    private var hub: CarEventHub? = null
    private var narrator: Narrator? = null
    private var director = StoryDirector()
    private var journal: Journal? = null

    /** 그림의 세계. 시즌이 정한다 — 바다 시즌이면 길이 물길이 되고 차가 배가 된다. */
    var theme: SceneTheme = SceneTheme.SEA
        private set

    /** 동행이 얼마나 자랐나. 화면에서 **구름이 실제로 커진다.** */
    val companionGrowth: Int get() = director.grown

    /** 받은 이벤트 종류별 횟수 — 지금 단계에서 가장 중요한 산출물. */
    val seen = linkedMapOf<String, Int>()

    /** 마지막 장면 글. 화면이 다시 붙을 때 그대로 보여 준다. */
    var lastLine: String? = null
        private set

    /** 지금 화면에 그릴 장면. */
    var scene: SceneKind = SceneKind.ROAD
        private set

    /** 멈춰 있나 — 멈췄을 때만 그림이 움직인다(주행 중엔 시선을 끌면 안 된다). */
    var parked: Boolean = true
        private set

    /**
     * 배가 지금 얼마나 기울어 있나. -1(왼쪽) ~ +1(오른쪽).
     *
     * **깜빡이가 켜진 동안만** 0 이 아니다. 내비가 180m 전에 예고한 그 커브가
     * 실제로 오는 순간을 화면이 받는 유일한 길이다.
     *
     * ⚠️ 핸들 각도를 그대로 따라가게 하지 않는다 — 그러면 주행 중에 **쉬지 않고 움직이는 그림**이
     *    된다. 주행 규제에서 막는 축은 영상 하나뿐이지만(SDK_FACTS 3절), 이 앱은
     *    **장면이 바뀔 때만 그림을 바꾸는** 보수적인 쪽을 지켜 왔다. 기울기도 커브마다
     *    **두 번**(들어갈 때·나올 때) 바뀔 뿐이다.
     */
    var lean: Float = 0f
        private set

    /**
     * 갱신을 받는 쪽. **여럿이다** — 화면(MainActivity)과 미디어 세션(StoryService)이 같이 본다.
     * 하나만 두면 서비스가 붙는 순간 화면이 갱신을 못 받는다.
     */
    private val listeners = mutableListOf<() -> Unit>()

    /** 지금 모으는 중인 책. 출발에서 생기고 도착에서 책장으로 넘어간다. */
    private var recording: Library.Recording? = null

    /** 책장에 쌓인 권수. 화면이 "책장(3권)" 을 보여 주는 데 쓴다. */
    var bookCount: Int = 0
        private set

    /** 지금 가는 길의 이름. 미디어 정보의 앨범 자리에 쓴다. */
    val bookTitle: String?
        get() = recording?.destination?.takeIf { it.isNotBlank() }?.let { "$it 가는 길" }

    /**
     * 길이 시작됐나 — **미디어 상태를 이걸로 정한다.**
     *
     * ⛔ 차 전원을 켜면 서비스가 자동으로 뜬다([BootReceiver]). 그때도 `STATE_PLAYING` 을 올리면
     *    목적지도 없는데 차의 음악 자리가 "동화 재생 중"이 된다 — 라디오를 듣던 사람에게는 사고다.
     *    그래서 **책을 모으는 중일 때만** 재생 중이라고 말한다.
     */
    val storyActive: Boolean get() = recording != null

    /** 소리를 잠시 멈춘 상태인가. 미디어 버튼(재생/일시정지)이 여기를 바꾼다. */
    var storyPaused: Boolean = false
        private set

    fun pauseStory() {
        storyPaused = true
        runCatching { narrator?.stop() }
        fireUpdate()
    }

    fun resumeStory() {
        storyPaused = false
        fireUpdate()
    }

    /** 지금 하늘 색. 시연 중이면 시연 시나리오를 따른다. */
    fun timeOfDayNow(): TimeOfDay = if (demoMode) Demo.timeOfDay else timeOfDay(
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    )

    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        val j = journal ?: Journal(ctx).also { journal = it }
        val season = Season.of(j.seasonId)
        theme = season.theme
        director = StoryDirector(
            childName = Settings.childName(ctx),
            journal = j,
            season = season,
            level = AgeLevel.of(Settings.childAge(ctx)),
            // 여는 줄의 `{때}`. 그림의 하늘과 **같은 함수**를 쓴다 — 밤 그림에 "아침이야" 가 붙으면
            // 그 자리에서 동화가 깨진다(시연은 `--es sky night` 으로 하늘을 바꾼다).
            sky = ::timeOfDayNow,
        )

        if (narrator == null) {
            // ⚠️ **미리 구운 음원이 제일 바깥**이다. 안쪽 목소리(Pleos·Android TTS)는 둘 다
            //    `ready` 라고 대답하면서 소리를 안 낼 수 있다 — 에뮬레이터가 그렇다.
            //    감싸개가 안이 아니라 밖에 있어야, 어느 쪽이 잡히든 들린다(2026-11-03).
            narrator = VoicePack(ctx, runCatching {
                PleosNarrator(ctx, ::fallbackToAndroidTts) as Narrator
            }.getOrElse {
                Log.w(TAG, "Pleos TTS 생성 실패(${it.message}) → Android TTS")
                AndroidNarrator(ctx)
            })
        }
        if (hub == null) {
            hub = CarEventHub(ctx, ::handle).also { it.start() }
        }
        bookCount = Library.count(ctx)
    }

    /**
     * 설정이 바뀌었다 — 이야기꾼을 새 이름·나이로 다시 만든다([SettingsActivity] 가 부른다).
     *
     * ⛔ **주행 중에는 갈아끼우지 않는다.** 읽던 쪽 번호가 0 으로 돌아가서,
     *    모으던 책과 화면이 어긋난다. 그 경우는 다음 출발에서 반영된다.
     *
     * @return 지금 반영됐는가. 화면이 "다음 출발부터" 라고 말해 줄지 정하는 데 쓴다.
     */
    fun applySettings(): Boolean {
        val ctx = app ?: return false
        if (recording != null) return false
        start(ctx)
        return true
    }

    /** 갱신 알림을 받기 시작한다. 돌려받은 것을 [unobserve] 에 다시 넣어 뗀다. */
    fun observe(block: () -> Unit): () -> Unit {
        listeners += block
        return block
    }

    fun unobserve(block: (() -> Unit)?) {
        block?.let { listeners -= it }
    }

    private fun fireUpdate() {
        // 듣는 쪽에서 예외가 나도 나머지는 받아야 한다
        listeners.toList().forEach { runCatching { it() } }
    }

    /**
     * 당겨 오는 값(TBT·남은시간·위치)을 긁고, **이야기도 한 칸 민다.**
     * 차에서 아무 일도 안 일어나는 구간에서 입을 다물면 안 된다.
     */
    fun poll() {
        hub?.poll()
        tickStory()
    }

    /** 예약된 장면이 있으면 내보낸다. 무거운 조회 없이 자주 불러도 된다. */
    fun tickStory() {
        director.tick()?.let(::deliver)
        // 마지막 쪽까지 나간 뒤에 책을 덮는다.
        if (director.done) closeBook()
    }

    /** 시연 중인가 — 하늘 색을 시연 시나리오(아침)에 맞춘다. */
    var demoMode = false
        private set

    /** 시연 재생. 들어가는 길은 실제 이벤트와 똑같다(같은 handle 을 탄다). */
    fun startDemo(which: String? = null, sky: TimeOfDay? = null, stretch: Float = 1f) {
        demoMode = true
        Demo.start(::handle, which, sky, stretch)
        // 압축 재생이므로 책 넘기는 박자도 같은 배율로 줄인다.
        director.timeScale = Demo.timeScale
    }

    /** 내비에 목적지를 넣어 본다. 되면 진짜 주행 이벤트가 흐른다. */
    fun requestDemoRoute() = hub?.requestRoute(
        latitude = 37.2940, longitude = 127.2020, name = "에버랜드",
    )

    fun statusLine(): String = seen.entries.joinToString("  ") { "${it.key}×${it.value}" }

    private fun fallbackToAndroidTts(why: String) {
        val ctx = app ?: return
        Log.w(TAG, "Pleos TTS 못 씀($why) → Android TTS 로 내려간다")
        runCatching { narrator?.release() }
        // 음원 감싸개는 여기서도 **다시 씌운다.** 안 씌우면 Pleos 가 늦게 실패한 뒤부터
        // 구운 음원이 통째로 안 나간다(감싸개가 안쪽 목소리와 함께 버려진다).
        narrator = VoicePack(ctx, AndroidNarrator(ctx))
    }

    private fun handle(event: CarEvent) {
        val name = event::class.simpleName ?: "?"
        seen[name] = (seen[name] ?: 0) + 1

        if (event is CarEvent.MotionChanged) parked = !event.moving
        if (event is CarEvent.Turning) lean = event.lean
        // ⚠️ 여기서 손님을 하늘(밤/낮)로 고르지 않는다. **동행은 시즌이 정한다** —
        //    날마다 바뀌면 정이 들지 않는다([Journal] 의 Season 주석). 밤은 **하늘 색**으로만 드러난다.
        //    전에는 `director.timeOfDay` 를 넣어 줬는데 **읽는 곳이 없어** 죽은 값이었다(2026-10-31).
        record(event)

        director.onEvent(event)?.let(::deliver)

        // ⚠️ 도착 이벤트에서 바로 닫으면 **예약된 마지막 쪽들이 책에 안 들어간다**(실측 18장).
        //    닫는 건 [tickStory] 가 "다 읽었다"를 보고 한다.

        fireUpdate()
    }

    /** 한 마디를 내보낸다 — 화면·음성·책에 동시에. */
    private fun deliver(beat: StoryDirector.Beat) {
        lastLine = beat.line
        scene = beat.scene
        Log.i(TAG, "장면[${beat.scene}]: ${beat.line}")
        if (!storyPaused) narrator?.say(beat.line, urgent = beat.urgent)
        recording?.add(beat, director.grown)
        fireUpdate()
    }

    /** 책의 겉면(목적지·길이·지나온 곳)은 대사가 아니라 이벤트에서 가져온다. */
    private fun record(event: CarEvent) {
        when (event) {
            is CarEvent.RouteStarted -> {
                recording = Library.Recording(System.currentTimeMillis()).apply {
                    destination = event.destinationName
                    minutes = Math.max(1, Math.round(event.remainSeconds / 60.0).toInt())
                    timeOfDay = timeOfDayNow()
                }
            }

            // ETA 가 늘어도 책의 길이는 **출발 때 약속한 길이**로 둔다.
            // 1장이 "에버랜드까지 65분" 이라고 말해 놓고 맺는 장이 77분이면 책이 저 혼자 모순된다
            // (2026-10-29 시연에서 실제로 그랬다). 늘어난 사실은 이미 한 장("시간이 좀 늘었어요")으로 들어간다.

            // 맺는 장은 **어른이 보는 장**이라 실제 지명을 적는다(아이에게 읽어 주는 건 아니다).
            // 다만 "경기도 용인시 처인구 포곡읍" 은 길어서 한 줄을 다 먹는다 — 뒤 두 토막만 남긴다.
            is CarEvent.Place -> event.address
                ?.takeIf { it.isNotBlank() }
                ?.let { recording?.place(shortPlace(it)) }

            else -> Unit
        }
    }

    /** "경기도 용인시 처인구 포곡읍" 처럼 긴 주소를 뒤 두 토막으로 줄인다. */
    private fun shortPlace(address: String): String =
        address.trim().split(' ').filter { it.isNotBlank() }
            .takeLast(2).joinToString(" ").ifBlank { address }

    /** 도착했다. 모은 걸 책장에 꽂는다. */
    fun closeBook() {
        val ctx = app ?: return
        val r = recording ?: return
        recording = null
        journal?.nextEpisode()
        val book = r.toBook()
        if (Library.save(ctx, book)) {
            bookCount = Library.count(ctx)
            Log.i(TAG, "책 저장: \"${book.title}\" ${book.pages.size}장 · 책장 ${bookCount}권")
            fireUpdate()
        } else {
            Log.w(TAG, "책 저장 실패")
        }
    }
}
