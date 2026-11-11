package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.CautionPointType
import kotlin.math.roundToInt

/**
 * 이야기를 지휘한다. 차 이벤트를 받아 "지금 무슨 말을 할지"를 정한다.
 *
 * ## 2026-10-29 설계를 뒤집었다
 * 첫 판은 **길을 말랑한 단어로 바꿔 말했다** — "터널 = 빛이 꺼진 동굴".
 * 그건 내비 안내의 다른 표현이지 동화가 아니었다. 그래서 순서를 바꿨다:
 * **이야기([Guest])가 먼저 서고, 차 신호는 그 이야기의 사건으로 들어간다.**
 *
 * 이걸 동화로 만드는 건 규칙 둘이다.
 * 1. **연속성** — 손님의 상태([grown])가 이어진다. 굴을 지나면 구름이 커지고, 뒤 대사가 그걸 안다.
 * 2. **스스로 간다** — 차 이벤트가 없어도 [tick] 이 뼈대 대사를 내보낸다.
 *    한산한 길에서 이야기가 멈추면 안 된다.
 *
 * 왜 상태기계인가: SDK 중 일부가 안 열려도(음성 입력·위치·제어) 남은 신호로 이야기가 끝까지 가야 한다.
 * 그래서 모든 판단은 [CarEvent] 만 보고 하고, 들어오지 않는 이벤트는 연출이 하나 빠지는 것으로 끝난다.
 */
class StoryDirector(
    private val childName: String = "아이",
    /** 같은 길을 몇 번째 지나는지 기억하는 곳. 없으면 늘 "처음"이 된다. */
    private val journal: Journal? = null,
    /** 시즌이 탈것과 동행을 정한다. */
    private val season: Season = Season.DEFAULT,
    /** 이야기의 수준. 뼈대는 그대로 두고 말하는 층만 바꾼다. */
    private val level: AgeLevel = AgeLevel.KID,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 지금 하늘. **여는 줄의 `{때}`가 여기서 나온다.**
     *
     * ⚠️ 함수로 받는다 — 시연은 `--es sky night` 으로 하늘을 바꾸므로([Demo.timeOfDay])
     *    출발 때 한 번 읽어 두면 그림은 밤인데 책은 "아침이야" 로 연다.
     *    [Engine.timeOfDayNow] 를 그대로 받아서 **화면과 같은 값**을 쓴다.
     */
    private val sky: () -> TimeOfDay = { TimeOfDay.DAY },
) {
    /**
     * 밖으로 내보내는 결과. 화면과 음성이 같은 걸 쓴다.
     * @param urgent 앞의 말을 끊고 지금 나가야 한다(멀미 선행 안내).
     * @param keep   말 간격에 걸려도 버리지 않는다(이야기의 뼈대).
     */
    data class Beat(
        val line: String,
        val urgent: Boolean = false,
        val keep: Boolean = urgent,
        val scene: SceneKind = SceneKind.ROAD,
        /** 3~4세용 짧은 판. 없으면 어린 층에서는 **말하지 않는다**(뼈대 대사만 남는다). */
        val short: String? = null,
        /** 7~9세에만 붙는 꼬리 질문. 어린 층에는 압박이 된다. */
        val tail: String? = null,
        /**
         * **이미 나이에 맞춰 고른 문장인가.** 책의 쪽이 그렇다 — [read] 가 거기서 골라 놓는다.
         *
         * 두 번 고르면 안 된다: [voice] 는 "도착 뒤 떡밥은 큰 아이용" 이라는 **조각 대사용 규칙**을
         * 들고 있어서, 책의 마지막 두 쪽이 세 살에게 통째로 삼켜졌다(2026-10-31 실측: 10줄로 끝났다).
         */
        val voiced: Boolean = false,
    )

    private var plan: Plan? = null
    private var chapter = 0
    private var lastPlace: String? = null
    private var lastSpokeAt = 0L
    private var stopped = false

    /**
     * 뒷좌석에 앉은 사람 수. **읽을 쪽을 고를 때만** 쓴다.
     *
     * ⚠️ 에뮬레이터 기본값이 `true` 라 "앉으면"을 조건으로 걸면 늘 참이다(2026-11-02 실측).
     *    그래서 **둘 이상일 때 더하는 쪽으로만** 쓰고, 혼자인 걸 전제로 깐 말은 쓰지 않는다.
     */
    private var riders = 0

    /** 직전 충전율. 첫 보고(-1 → 값)는 **충전이 아니다** — [charging] 참고. */
    private var lastSoc = -1f
    private var arrived = false

    /** 오늘 탄 손님. 출발 때 정해진다. */
    private var guest: Guest? = null
    private var destination: String? = null

    /** 손님이 얼마나 자랐나. 굴을 지날 때마다 는다 — **연속성의 전부**다. */
    var grown = 0
        private set

    /** 이번 화에 실제로 나온 장면들. 떡밥이 **없던 일**을 가리키지 않게 하는 데 쓴다. */
    private val seenScenes = mutableSetOf<SceneKind>()

    /** 오늘 몇 화이고 **과제가 뭔지.** 꼬리말이 전부 여기서 나온다. */
    private var episode: Episode? = null

    /** 종류별로 이번 주행에서 몇 번째 말했나 — **같은 문장을 두 번 쓰지 않으려고** 센다. */
    private val said = mutableMapOf<String, Int>()

    /** 과제 꼬리를 몇 번 붙였나. **두 번이면 그만** — 그 뒤는 잔소리가 된다. */
    private var looked = 0
    private var gained = false

    /** 본문을 몇 줄 들려줬나. 질문이 아니라 **이게 기본값**이다. */
    private var told = 0

    /** 묻는 건 한 주행에 한 번. 계속 물으면 동화가 아니라 퀴즈가 된다. */
    private var asked = false

    /** **오늘 할 일을 줬나.** 주기 전에는 묻지 않는다([BookPage.quest]). */
    private var questGiven = false

    /** 지금 몇 쪽까지 읽었나. 책 모드의 전부다. */
    private var page = 0
    private var startedAt = 0L

    /**
     * 시연은 65분을 2분에 재생한다. **이야기 박자도 같이 줄여야** 책이 끝까지 간다.
     * 실제 주행에서는 1이다.
     */
    var timeScale: Float = 1f

    /**
     * 아직 안 한 **뼈대 대사**. 차 이벤트와 무관하게 제 박자로 나간다.
     * 이게 없으면 한산한 길에서 앱이 입을 다문다.
     */
    private val pending = ArrayDeque<Pending>()

    /** 줄 선 뼈대 하나. [dueAt] 이 되면 나간다. */
    private class Pending(val dueAt: Long, val beat: Beat)

    /** 장(章) 구성. 남은 시간이 길이를 정한다. */
    data class Plan(val totalSeconds: Int, val chapters: Int) {
        companion object {
            fun of(remainSeconds: Int): Plan {
                val chapters = when {
                    remainSeconds < 10 * 60 -> 1      // 등하원
                    remainSeconds < 25 * 60 -> 2
                    remainSeconds < 50 * 60 -> 3
                    else -> 4
                }
                return Plan(remainSeconds, chapters)
            }
        }
    }

    /**
     * **책이 먼저다. 길은 박자다.**
     *
     * 본문([Episode.pages])이 있는 화에서는 **1쪽부터 순서대로 읽는 것이 본체**고,
     * 차 신호는 두 가지만 한다 — ⑴ 어울리는 쪽을 **지금** 읽게 하고(동기화),
     * ⑵ 그 밖의 신호에는 **아무 말도 하지 않는다.**
     *
     * 예외는 멀미 안내뿐이다. 그건 이야기가 아니라 **안전**이라 책을 끊고 들어간다.
     */
    fun onEvent(e: CarEvent): Beat? {
        // ⛔ **이야기가 끝난 뒤에는 길이 말을 걸어도 받지 않는다.**
        //    도착 인사·떡밥 뒤에 정차 이벤트가 들어와 "오늘 하늘은 무슨 색이야?" 가
        //    하나 더 따라 나갔다(2026-10-31 실측). 책을 덮었는데 질문이 남으면 끝이 안 난다.
        if (arrived && e !is CarEvent.RouteStarted) return null

        val ep = book()
        if (ep != null) {
            when (e) {
                is CarEvent.TurnAhead -> return onTurnInBook(e)
                is CarEvent.CautionAhead -> return fitPage(e.kind.toScene())
                is CarEvent.Landmark -> return fitPage(
                    when (e.kind) {
                        CarEvent.Landmark.Kind.TUNNEL -> SceneKind.TUNNEL
                        CarEvent.Landmark.Kind.REST_AREA -> SceneKind.REST
                        else -> null
                    },
                )
                // ⭐ 지명은 **말하지 않고 기억만 한다.** 책이 지명을 부를 쪽에서 꺼내 쓴다.
                //    전에는 지명이 올 때마다 "지금 ○○를 지나고 있어"라고 했는데,
                //    그건 동화가 아니라 **내비 안내를 동화 말투로 읽는 것**이었다(2026-10-31 사용자).
                is CarEvent.Place -> {
                    // ⛔ 아이가 모르는 이름은 **기억하지도 않는다.** 읽을 일이 없어야 한다.
                    e.address?.takeIf { it.isNotBlank() }
                        ?.let { addr -> childPlace(addr)?.let { lastPlace = it } }
                    return null
                }
                // ⚠️ 남은 시간·장 경계는 **박자에만** 쓴다.
                //    여기서 [onEventLegacy] 를 부르면 그 끝의 [gate] 가 `lastSpokeAt` 을 갱신해
                //    **말은 안 나가고 책만 뒤로 밀린다**(2026-10-31 실측: 경유지 뒤 9분 침묵).
                is CarEvent.EtaChanged -> { retime(e.remainSeconds); return null }
                CarEvent.WaypointArrived -> { chapter += 1; return null }
                // 책을 덮는다 — 남은 **본줄기 쪽**을 이어 읽고 끝낸다.
                CarEvent.DestinationArrived -> {
                    arrived = true
                    pending.clear()
                    return closeBookPages(ep)
                }
                // ⭐ 충전은 **장면**이다. 차는 서 있지만 책은 멈추지 않는다 —
                //    충전 대기는 아이가 차에 묶여 있는 25분이고, 동화에 가장 좋은 시간이다.
                //    (`tick()` 은 정차와 무관하게 돌므로 책은 이미 계속 읽힌다.
                //     여기서 더하는 건 **충전에 어울리는 쪽을 그 자리에서 읽는 것**뿐이다.)
                is CarEvent.ChargeLevel -> return if (charging(e)) onChargeInBook() else null
                // 정차 — 책은 멈추지 않지만, **멈춘 자리에서 한 번 묻는다.**
                is CarEvent.MotionChanged -> {
                    val wasMoving = !stopped
                    stopped = !e.moving
                    // ⛔ 2026-11-04: 이 자리가 비어 있었다. 열두 화 전부에 본문이 생기면서
                    //    `MotionChanged` 가 전부 책 모드로 들어왔고, 책 모드는 `stopped` 만 적고
                    //    돌려보냈다 — **기획서가 내세우는 "멈추면 묻는다"가 통째로 죽어 있었다**
                    //    (65분 나들이 전사에서 46분 정차에 한 줄도 없다). 묻는 말([Episode.asks])은
                    //    원고에 이미 있는데 **부르는 곳이 옛 분기뿐**이어서 아무도 못 봤다.
                    //    달릴 때는 창밖을 보고, **서면 이야기가 묻는다** — 주행 중에 물으면
                    //    아이가 대답할 틈이 없고 운전자가 대신 받는다.
                    // ⛔ **할 일을 주기 전에는 묻지 않는다**([BookPage.quest]).
                    if (stopped && wasMoving && !asked && questGiven) {
                        asked = true
                        val g = guest
                        if (g != null) ep.ask(0)?.let { q ->
                            queue(Beat(fill(q, g), keep = true, scene = SceneKind.STOP), ASK_GAP_MS)
                        }
                    }
                    return null
                }
                // ⭐ **비와 바람은 그날 다른 쪽을 읽게 한다.**
                //    길이 주는 장면(터널·방지턱)은 한 주행에 한 번뿐이라 어느 날이나 같은데,
                //    와이퍼와 창문은 **오늘 날씨·오늘 창문**이다. 비 오는 날 2화는 물을 바로 찾고,
                //    창문을 열면 3화가 그 자리에서 바람을 먹는다 — 같은 책이 다른 이야기가 된다.
                //    ⚠️ 꺼짐(비 멎음·창 닫힘)은 **말하지 않는다.** 사라진 것을 굳이 알릴 일이 없다.
                is CarEvent.Rain -> return if (e.falling) fitPage(SceneKind.RAIN) else null
                is CarEvent.Breeze -> return if (e.open) fitPage(SceneKind.WIND) else null
                is CarEvent.Riders -> { riders = e.rear; return null }
                // 책 모드에서는 조각 대사를 쓰지 않는다. 책이 읽히는 중이다.
                // ⛔ [CarEvent.Turning] 은 **말을 만들지 않는다.** 깜빡이는 한 번 가는 길에
                //    수십 번 켜지고, 그때마다 한 줄씩 끼면 책이 아니라 주행 안내가 된다.
                //    쓰는 쪽은 화면 하나다 — 그 순간 **배가 기운다**([Engine.lean]).
                is CarEvent.Braking, is CarEvent.RestrictionChanged, is CarEvent.Failed,
                is CarEvent.Turning,
                -> return null

                else -> {}   // RouteStarted 는 아래로
            }
        }
        return onEventLegacy(e)
    }

    /**
     * 책 모드의 충전. **처음 한 번만** 충전 쪽을 읽는다.
     *
     * 왜 한 번인가: `EvBatteryStateOfChargeListener` 는 SOC 가 1%씩 오를 때마다 온다.
     * 25분 충전이면 수십 번이고, 그때마다 쪽을 읽으면 **책이 충전소에서 다 끝난다.**
     * 충전 중 나머지 시간은 평소대로 [readNext] 의 박자가 채운다.
     */
    private fun onChargeInBook(): Beat? {
        if (nth("CHARGE") > 1) return null
        return fitPage(SceneKind.CHARGE)
    }

    /**
     * **지금 충전 중인가.** SOC 한 번으로는 알 수 없다.
     *
     * ⛔ 2026-10-31 에뮬레이터 실측: 충전과 아무 상관없는 **나들이 시연 두 번째 줄**에
     * "배가 잠깐 서서 기운을 채워" 가 나왔다. 차는 **켜기만 해도 SOC 를 한 번 올려 보내고**
     * (에뮬레이터는 100), `EvBatteryStateOfChargeListener` 는 그 첫 보고도 그대로 넘긴다.
     * 하네스는 못 잡는다 — 대본에 넣은 충전 이벤트만 오니까. **실기기에서만 보이는 종류다.**
     *
     * 충전은 **서 있는데 SOC 가 오르는 것**이다. 달리면서 오르는 건 회생제동이다.
     */
    private fun charging(e: CarEvent.ChargeLevel): Boolean {
        val prev = lastSoc
        lastSoc = e.percent
        return prev >= 0f && stopped && e.percent > prev
    }

    /**
     * 책 모드의 커브. 두 가지를 **같이** 한다 —
     * ⑴ 급커브·첫 커브는 멀미 안내를 먼저 내보내고(안전),
     * ⑵ **그 커브에 어울리는 쪽을 한 박자 뒤에 읽는다.**
     *
     * 전에는 ⑵를 안 했다. 그래서 커브가 한 번뿐인 조용한 길에서 커브 쪽이
     * **영영 그 커브를 못 만나** [BookPage.alt] 로 떨어졌다(2026-10-31 실측: 40분 주행).
     */
    private fun onTurnInBook(e: CarEvent.TurnAhead): Beat? {
        val sc = when (e.side) {
            CarEvent.TurnAhead.Side.LEFT -> SceneKind.TURN_LEFT
            CarEvent.TurnAhead.Side.RIGHT -> SceneKind.TURN_RIGHT
            else -> SceneKind.TURN
        }
        val side = when (e.side) {
            CarEvent.TurnAhead.Side.LEFT -> "왼쪽"
            CarEvent.TurnAhead.Side.RIGHT -> "오른쪽"
            CarEvent.TurnAhead.Side.UTURN -> "반대쪽"
            else -> null
        }
        val g = guest
        val n = nth("TILT")
        if ((e.sharp || n <= 1) && g != null && side != null) {
            // ⚠️ 문장은 **커브 횟수**가 아니라 **안내한 횟수**로 골라야 한다.
            //    커브 열둘 중 급커브 넷에만 안내하면 n=1·4·8·15 가 되고,
            //    `(n-2) % 4` 가 겹쳐 **같은 말이 두 번** 나갔다(2026-10-31 실측: 시내 20분).
            val w = nth("WARN")
            val warned = gate(
                Beat(
                    tiltHold(g, side, if (e.sharp) "큰" else "작은", w),
                    short = tiltShort(g, side, w),
                    // ⛔ **큰 아이 꼬리가 여기만 빠져 있었다.** 본문 쪽은 [read] 가 `voiced` 로 내보내
                    //    꼬리를 안 붙이므로, 책이 있는 화(1·12화)에서는 **7~9세가 5세와 글자까지 같았다**
                    //    (2026-11-04 실측: 등원 12분 열두 줄이 한 자도 안 달랐다).
                    //    나이 층은 기획서가 내세우는 축인데 **심사위원이 열어 볼 1화에서** 죽어 있었다.
                    tail = BIG_TAIL,
                    urgent = true, scene = sc,
                ),
            )?.also { seenScenes += it.scene }
            if (warned != null) {
                (fitPage(SceneKind.TURN) ?: fitPage(sc))?.let { queue(it, TILT_PAGE_GAP_MS) }
                return warned
            }
        }
        return fitPage(SceneKind.TURN) ?: fitPage(sc)
    }

    /**
     * 옛 방식 — 본문이 없는 화에서 조각을 이어 붙인다. 2~12화가 아직 여기로 돈다.
     *
     * ⛔ **길이 시작되기 전에는 한 마디도 하지 않는다.** 2026-11-01 부터 차 전원을 켜면
     *    이야기 서비스가 자동으로 뜬다([BootReceiver]). 여기서 한 줄이라도 새면
     *    **아이가 타지도 않은 차가 혼자 말한다.**
     *    실제로 `WaypointArrived` 가 `plan` 도 없이 "여기서 쉬어 가자" 를 냈다 —
     *    하네스 [StoryHarnessTest.길이_없으면_말하지_않는다] 가 잡았다.
     *    분기마다 막으면 새 분기를 더할 때 또 빠지므로 **입구 한 곳**에서 막는다.
     */
    private fun onEventLegacy(e: CarEvent): Beat? =
        if (plan == null && e !is CarEvent.RouteStarted) null else onRoute(e)

    private fun onRoute(e: CarEvent): Beat? = when (e) {

        is CarEvent.RouteStarted -> {
            val p = Plan.of(e.remainSeconds)
            plan = p
            chapter = 1
            // ⭐ 손님은 **시즌 내내** 자란다. 주행마다 0으로 돌리면 12화째 구름도 1화만큼 작아서
            //    "이제 다 컸다"는 줄거리와 화면이 어긋난다([SceneView.growth] 가 실제 크기다).
            // ⛔ 전에는 `화 번호 - 1` 로 깔았다. 그런데 **8화는 구름이 비가 되어 쏟아지는 화**라
            //    줄거리가 거꾸로 간다 — 글은 "손톱만 한 구름" 인데 화면은 거의 다 큰 구름이었다
            //    (2026-11-05 전사). 그래서 **크기도 원고가 정한다**([Episode.startGrown]).
            //    원고가 없는 시즌(2·3)은 옛 방식으로 깐다.
            grown = Episode.of(season, journal?.episode ?: 1)
                ?.takeIf { it.hasBook }?.startGrown
                ?: ((journal?.episode ?: 1) - 1).coerceIn(0, MAX_GROWN - 1)
            arrived = false
            pending.clear()
            seenScenes.clear()
            destination = e.destinationName?.takeIf { it.isNotBlank() }
            val g = season.guest
            guest = g
            // 왜 가야 하는지는 **바로 뒤에** 따로 나간다. 한 번에 다 말하면 설명이 된다.
            // 2화부터는 사정을 다시 설명하지 않는다 — 아이는 이미 안다.
            val first = (journal?.episode ?: 1) <= 1
            // 오늘의 과제. 이게 있어야 커브가 "기울어"로 끝나지 않는다.
            episode = Episode.of(season, journal?.episode ?: 1)
            said.clear(); looked = 0; gained = false; told = 0; asked = false
            questGiven = false
            page = 0; startedAt = now(); readIdx.clear()
            // ⚠️ 주행을 연달아 하면 지난 주행 상태가 샌다 — 멈춘 적 있다고 기억하면
            //    새 주행 첫 정차에서 입을 다물고, 지난 길 이름이 새 책의 `{여기}` 를 채운다.
            stopped = false; lastPlace = null
            // ⭐ 본문이 있으면 **1쪽부터 읽는 것이 시작**이다.
            //    손님 소개·사정·과제를 따로 내보내면 책 앞에 같은 말이 한 번 더 붙는다(실측).
            val bk = book()
            if (bk != null) {
                peek(bk)?.let { read(bk, it) }
            } else {
                if (first) queue(Beat(fill(g.why, g), keep = true, scene = SceneKind.START), OPENING_GAP_MS)
                // 사정 설명 다음에 **오늘 뭘 할지**를 준다. 1화는 손님 소개가 먼저라 한 박자 뒤.
                episode?.let { ep ->
                    queue(
                        Beat(
                            fill(ep.task, g),
                            short = ep.taskShort?.let { fill(it, g) },
                            keep = true, scene = SceneKind.START,
                        ),
                        if (first) OPENING_GAP_MS + TASK_GAP_MS else TASK_GAP_MS,
                    )
                }
                Beat(
                    fill(if (first) g.meet else g.meetAgain, g),
                    // 3~4세에게 여는 대사가 길면 그 자리에서 놓친다. 한 호흡으로 줄인다.
                    short = fill(
                        if (first) "{아이}{호격}, {곳}에 가는 {때}{이}야. 오늘은 {손님}이 같이 가."
                        else "{아이}{호격}, {손님}이 또 왔어! {곳}까지 같이 가.",
                        g,
                    ),
                    keep = true, scene = SceneKind.START,
                )
            }
        }

        is CarEvent.EtaChanged -> onEta(e)

        is CarEvent.TurnAhead -> {
            // 멀미 케어의 핵심. 흔들리기 전에 방향과 **할 일**을 준다.
            // "창밖을 보세요"가 아니라 "손님이 안 떨어지게 붙잡아 줘" — 시키는 말은 안 듣지만 역할은 한다.
            val side = when (e.side) {
                CarEvent.TurnAhead.Side.LEFT -> "왼쪽"
                CarEvent.TurnAhead.Side.RIGHT -> "오른쪽"
                CarEvent.TurnAhead.Side.UTURN -> "반대쪽"
                else -> null
            }
            guest?.let { g ->
                side?.let {
                    val n = nth("TILT")
                    val sc = when (e.side) {
                        CarEvent.TurnAhead.Side.LEFT -> SceneKind.TURN_LEFT
                        CarEvent.TurnAhead.Side.RIGHT -> SceneKind.TURN_RIGHT
                        else -> SceneKind.TURN
                    }
                    // ⚠️ 2026-10-31: 커브마다 흔들림을 보고하니 **20줄 중 5줄이 흔들림 보고**였다.
                    //    그건 동화가 아니라 알림이다. 멀미 안내는 **급커브와 첫 커브**만 하고,
                    //    완만한 커브에서는 **화면만 기울고 말은 이야기가 한 칸 나아간다.**
                    //    이게 "차에 맞춘다"의 진짜 뜻이다 — 차가 말할 때마다 받아 적는 게 아니라.
                    if (e.sharp || n <= 1) {
                        val w = nth("WARN")
                        val line = tiltLine(g, it, if (e.sharp) "큰" else "작은", w)
                        Beat(
                            line,
                            short = tiltShort(g, it, w),
                            // 과제가 붙었으면 꼬리 질문은 뗀다 — 질문 둘이 연달아 가면 둘 다 못 받는다.
                            tail = if (line.endsWith("?")) null else BIG_TAIL,
                            urgent = true,
                            scene = sc,
                        )
                    } else {
                        tellNext(g)?.let { line -> Beat(line, scene = sc) }
                    }
                }
            }
        }

        is CarEvent.CautionAhead -> guest?.let { g ->
            val n = nth(e.kind.name)
            cautionLine(g, e.kind, n)?.let { line ->
                // ⚠️ 여기엔 과제를 붙이지 않는다. 주의구간 문장은 그 자체로 한 사건이라,
                //    뒤에 과제까지 붙이면 한 호흡에 세 문장이 된다(2026-10-31 전사에서 그랬다).
                //    과제는 **기울기**에만 붙는다 — 커브가 제일 자주 오니 거기가 제자리다.
                Beat(
                    line,
                    scene = e.kind.toScene() ?: SceneKind.ROAD,
                    short = shortCaution(g, e.kind),
                    tail = tailCaution(e.kind),
                )
            }
        }

        is CarEvent.Landmark -> guest?.let { g ->
            when (e.kind) {
                CarEvent.Landmark.Kind.TUNNEL -> {
                    // 굴을 나오는 이벤트는 안 온다. 그래서 **나오는 장면을 예약**해 둔다.
                    // 손님이 변하는 자리이므로 빠지면 안 된다.
                    grown = (grown + 1).coerceAtMost(MAX_GROWN)
                    val nth = journal?.visit("TUNNEL", lastPlace) ?: 1
                    queue(
                        Beat(fill(darkOutLine(g, nth), g), keep = true, scene = SceneKind.ROAD),
                        DARK_GAP_MS,
                    )
                    Beat(fill(darkInLine(g, nth), g), scene = SceneKind.TUNNEL)
                }

                CarEvent.Landmark.Kind.REST_AREA -> Beat(fill(g.rest, g), scene = SceneKind.REST)
                CarEvent.Landmark.Kind.TOLLGATE ->
                    Beat("성문이야. 문지기한테 인사하고 지나갈까?")
                CarEvent.Landmark.Kind.MERGE ->
                    Beat("앞이 둘로 갈라져. 우리는 이쪽이야.")
            }
        }

        is CarEvent.Place -> guest?.let { g ->
            // ⛔ 2026-10-31: 여기도 체를 통과해야 한다. 옛 방식만 고치지 않고 두는 바람에
            //    2화에서 "지금 용인시 기흥구를 지나고 있어"가 그대로 나갔다(실측).
            //    아이가 모르는 이름이면 **아무 말도 하지 않는다.**
            e.address?.takeIf { it.isNotBlank() }?.let { addr ->
                val word = childPlace(addr) ?: return@let null
                if (word == lastPlace) return@let null
                lastPlace = word
                val nth = journal?.visit("PLACE", word) ?: 1
                // 세 번째부터는 "처음 와 보는 데"가 아니다. 아이가 먼저 안다.
                val line = if (nth <= 2) g.place
                else "또 {여기}{을} 지나. ${g.name}이 이제 길을 외웠대."
                Beat(fill(line, g, here = word))
            }
        }

        is CarEvent.MotionChanged -> guest?.let { g ->
            when {
                !e.moving && !stopped -> {
                    stopped = true
                    // 달릴 때는 창밖을 보고, **멈추면 이야기가 묻는다.**
                    // 주행 중에 물으면 아이가 대답할 틈이 없고, 운전자가 대신 받게 된다.
                    if (!asked) {
                        asked = true
                        episode?.ask(0)?.let {
                            queue(Beat(fill(it, g), keep = true, scene = SceneKind.STOP), ASK_GAP_MS)
                        }
                    }
                    Beat(fill(g.stopped, g), scene = SceneKind.STOP)
                }

                e.moving && stopped -> {
                    stopped = false
                    Beat(fill(g.moving, g))
                }

                else -> null
            }
        }

        is CarEvent.Braking -> null   // 기록만 한다. 말이 너무 많아지면 안 된다.

        CarEvent.WaypointArrived -> {
            val p = plan
            chapter += 1
            val which = chapter - 1
            if (p != null && which in 1..p.chapters) {
                Beat("${ORDINALS.getOrElse(which - 1) { "또 한" }} 번째 이야기가 끝났어. 여기서 잠깐 숨을 돌리자.",
                    keep = true, scene = SceneKind.REST)
            } else {
                Beat("여기서 잠깐 숨을 돌리자.", keep = true, scene = SceneKind.REST)
            }
        }

        CarEvent.DestinationArrived -> {
            // 본문이 있는 화는 [onEvent] 가 [closeBookPages] 로 먼저 처리한다. 여기는 옛 방식뿐이다.
            if (arrived) null else {
                arrived = true
                pending.clear()          // 작별 뒤에 뼈대 대사가 따라 나오면 안 된다
                guest?.let { g ->
                    val ep = journal?.episode ?: 1
                    // ⚠️ 작별은 **시즌 마지막 화에서만.** 매 화 "잘 가"로 끝내면
                    //    다음 날 또 만나는 게 이상해진다(2026-10-29 실측에서 그랬다).
                    if (ep >= Season.EPISODES) {
                        Beat(fill(g.farewell, g), keep = true, scene = SceneKind.ARRIVE)
                    } else {
                        // 보통 날은 **다음 화 떡밥**으로 닫는다. 다음에 탈 이유가 여기서 나온다.
                        // 이번 화에 실제로 나온 장면을 가리키는 떡밥만 후보로 둔다
                        val usable = g.hooks.filter { it.needs == null || it.needs in seenScenes }
                        val hook = usable[(ep - 1).mod(usable.size)]
                        queue(
                            Beat(
                                fill(hook.line, g),
                                short = null,           // 떡밥은 큰 아이용. 어린 층은 도착 인사로 끝낸다
                                keep = true, scene = SceneKind.ARRIVE,
                            ),
                            HOOK_GAP_MS,
                        )
                        Beat(
                            fill(g.arriveDaily, g),
                            short = fill("다 왔다! {손님}아, 내일 또 보자.", g),
                            keep = true, scene = SceneKind.ARRIVE,
                        )
                    }
                }
            }
        }

        // 본문 없는 화의 충전. 손님 이름으로 말한다 — 전에는 "요정"이라는
        // **어디에도 없는 등장인물**이 여기서만 나왔다(2026-10-31).
        is CarEvent.ChargeLevel -> {
            // ⚠️ [charging] 은 직전 SOC 를 갱신하므로 **이벤트마다 꼭 한 번** 불러야 한다.
            //    `guest?.let { }` 안에 두면 손님이 없는 동안 SOC 가 안 쌓인다.
            val isCharging = charging(e)
            guest?.takeIf { isCharging }?.let { g ->
                // ⛔ 충전율은 1%씩 수십 번 온다. 전에는 `pct % 10 == 0` 으로 걸렀는데
                //    25분 충전에서 **같은 말이 다섯 번** 나갔다(2026-10-31 하네스).
                //    "10의 배수"는 횟수를 줄이는 장치가 아니다 — **한 번씩만** 말한다.
                val pct = e.percent.roundToInt()
                when {
                    pct >= 80 && nth("CHARGE_FULL") == 1 -> Beat(
                        fill("{탈것}{가} 기운을 거의 다 채웠어. {손님}{가} 기다리다 졸았대.", g),
                        scene = SceneKind.CHARGE,
                    )
                    pct < 80 && nth("CHARGE_MID") == 1 -> Beat(
                        fill("{탈것}{가} 아직 기운을 채우는 중이야. {손님}{과} 조금만 더 기다리자.", g),
                        scene = SceneKind.CHARGE,
                    )
                    else -> null
                }
            }
        }

        // 본문 없는 화에서는 **조각을 새로 만들지 않는다.** 날씨 대사를 여기 또 깔면
        // 조각 모드가 더 수다스러워질 뿐이다 — 비·바람은 **본문이 있는 화에서만** 쓴다.
        is CarEvent.Rain, is CarEvent.Breeze -> null
        is CarEvent.Riders -> { riders = (e as CarEvent.Riders).rear; null }

        is CarEvent.RestrictionChanged -> null   // 화면 쪽에서만 쓴다
        is CarEvent.Turning -> null              // 〃 (배가 기운다)
        is CarEvent.Failed -> null
    }?.let(::gate)?.also { seenScenes += it.scene }

    /**
     * 차에서 아무 일도 안 일어날 때 이야기를 밀어 준다.
     * 한산한 길에서 앱이 입을 다물면 그건 이야기가 아니라 안내 방송이다.
     */
    fun tick(): Beat? {
        val t = now()
        // ⛔ **책이 있는 화에서는 [idleTell] 을 쓰지 않는다.** 그건 조각 대사용이라
        //    책 사이사이에 옛 조각이 끼어들었고, 그중 하나는 본문과 글자까지 같아
        //    같은 말이 두 번 나갔다(2026-10-31 실측: 출발 첫 3분에 조각 3줄).
        //    시연은 65분을 2분에 재생하는데 `idleMs` 는 배율을 안 받으므로
        //    **압축 재생에서는 이 경로가 한 번도 안 돌아** 여태 숨어 있었다.
        val head = pending.firstOrNull()
            ?: return readNext(t) ?: if (bookOpen()) null else idleTell(t)
        // ⚠️ 예전엔 '조용해지면' 내보냈는데, 사건이 촘촘하면 **영영 안 나가거나 순서가 뒤집혔다**
        //    (2026-10-29 시연: 여는 대사가 사라지고, 굴을 나오는 장면이 사슴 뒤로 갔다).
        //    이어지는 장면이므로 **정해진 시각**에 나가야 한다.
        if (t < head.dueAt) return null
        pending.removeFirst()
        lastSpokeAt = t
        return voice(head.beat)
    }

    // ──────────────────────────── 책 읽기 ────────────────────────────

    /** 본문이 있는 화인가. 없으면 옛 방식으로 돈다(2~12화). */
    private fun book(): Episode? = episode?.takeIf { it.hasBook }

    /**
     * **아직 읽을 쪽이 남은 책이 펼쳐져 있나.**
     *
     * 펼쳐져 있으면 조각 대사를 섞지 않는다(책 사이에 옛 조각이 끼어든다).
     * 다 읽었으면 — 결말은 도착에서 읽으므로 그 전까지 — **손님과 노는 시간**이다.
     * 그러지 않으면 책이 일찍 끝난 긴 주행에서 도착까지 입을 다문다(실측 17분).
     */
    private fun bookOpen(): Boolean {
        val ep = book() ?: return false
        val at = peekIdx(ep) ?: return false
        return ep.pages[at].scene != SceneKind.ARRIVE
    }

    /** 12분 등원길이면 **core 쪽만** 읽는다. 책을 다 읽고 도착해야 하니까. */
    private val shortRide: Boolean get() = (plan?.totalSeconds ?: 0) < 15 * 60

    /** 이미 읽은 쪽. 길을 만나 **당겨 읽은** 쪽이 다시 나오지 않게 한다. */
    private val readIdx = mutableSetOf<Int>()

    /** 다음에 읽을 쪽의 번호. 짧은 주행에서는 곁가지를 건너뛴다. */
    private fun peekIdx(ep: Episode): Int? {
        while (page < ep.pages.size) {
            val pg = ep.pages[page]
            if (page in readIdx || (shortRide && !pg.core)) { page += 1; continue }
            return page
        }
        return null
    }

    private fun peek(ep: Episode): BookPage? = peekIdx(ep)?.let { ep.pages[it] }

    /**
     * **쪽 간격을 도착 시각이 정한다.** 남은 시간을 남은 쪽 수로 나눈다.
     * 이게 "도착할 때까지 딱 맞게 끝나는 이야기"의 실제 구현이다 —
     * 정체로 늦어지면 천천히 읽고, 빨라지면 당겨 읽는다.
     */
    private fun pageGapMs(ep: Episode): Long {
        val p = plan ?: return level.idleMs
        // 당겨 읽은 쪽([readIdx])은 **남은 쪽이 아니다.** 빼지 않으면 간격을 과소평가해 너무 빨리 읽는다.
        val left = ep.pages.indices
            .count { it >= page && it !in readIdx && (!shortRide || ep.pages[it].core) }
            .coerceAtLeast(1)
        val elapsed = ((now() - startedAt) * timeScale).toLong()
        val remain = (p.totalSeconds * 1000L - elapsed).coerceAtLeast(0L)
        val story = (remain / left).coerceIn(level.idleMs, level.maxGapMs)
        return (story / timeScale).toLong().coerceAtLeast(1_500L)
    }

    /**
     * 한 쪽을 읽는다. 본문에 `{여기}` 가 있으면 **지금 지나는 지명**이 들어간다.
     *
     * 지명이 아직 안 들어왔으면 "이 길"로 떨어진다 — 책이 멈추지 않게.
     * 그래서 **지명은 책을 바꾸지 않고, 책 안의 한 칸만 채운다.**
     */
    private fun read(ep: Episode, pg: BookPage, matched: Boolean = true, idx: Int = page): Beat? {
        val g = guest ?: return null
        readIdx += idx
        if (idx == page) page += 1
        // ⛔ **본 적 없는 걸 봤다고 말하지 않는다.**
        //    굴 쪽을 터널이 아닌 데서 읽으면 "깜깜한 굴이야"가 거짓말이 된다 —
        //    아이가 창밖을 보면 굴이 없다(2026-10-31 조용한 길 실측에서 그랬다).
        //    그래서 그 길을 못 만나고 읽을 때는 **길을 가리키지 않는 문장**으로 바꾼다.
        // 지명을 부르는 쪽도 같다 — 아이가 아는 이름이 없으면 다른 문장으로 읽는다.
        val needsAlt = (!matched && pg.fit != null) ||
            (pg.text.contains("{여기}") && lastPlace == null)
        val text = if (needsAlt) pg.alt ?: return null else pg.text
        // ⚠️ 여기서 [voice] 를 쓰면 안 된다. 그건 **조각 대사용 규칙**이라
        //    "도착 뒤 떡밥은 큰 아이용"에 걸려 **세 살이 책의 마지막 두 쪽을 통째로 못 듣는다**(실측).
        //    책은 끝까지 읽혀야 한다. 나이는 **문장의 길이**로만 맞춘다.
        val line = when {
            level != AgeLevel.TODDLER -> text
            // ⛔ **길을 못 만나 대신 읽는 중이면 짧은 판을 쓰지 않는다.**
            //    짧은 판은 그 길을 가리키게 쓰여 있다("깜깜한 굴이에요") — 그런데
            //    [needsAlt] 일 때도 짧은 판이 [BookPage.alt] 를 **덮어썼다.** 그래서 세 살만
            //    굴이 없는 길에서 "깜깜한 굴이에요"를 들었다. 하네스의 '없던 길을 봤다고 함'
            //    검사는 본문([BookPage.text])만 훑어서 이걸 못 봤다(2026-11-05 발견).
            //    대신 읽는 문장이 길면 그 쪽은 건너뛴다 — 거짓말보다 침묵이 낫다.
            needsAlt -> if (pg.core || text.length <= TODDLER_MAX_CHARS) text else return null
            pg.short != null -> pg.short
            pg.core -> text                    // 뼈대는 빼지 않는다
            else -> return null                // 세 살에겐 곁가지를 읽지 않는다
        }
        // 말이 그 길을 가리키지 않으면 **그림도 가리키면 안 된다.**
        // 터널이 없는데 화면에 굴을 띄우면 그림이 거짓말을 한다.
        val sc = if (needsAlt && pg.fit != null) SceneKind.ROAD else pg.scene
        // ⭐ **말이 "커졌다"고 하면 그림도 커져야 한다.**
        //    [grown] 을 늘리는 곳이 옛 터널 분기에만 있어서, 책은 "아주 조금 커졌어!" 를 읽고도
        //    화면의 구름이 그대로였다(2026-10-31 실측: 모든 주행 `grown=0`).
        //    대신 읽는 문장([BookPage.alt])에서는 자라지 않는다 — 그 장면이 없었으니까.
        if (pg.grow && !needsAlt) grown = (grown + 1).coerceAtMost(MAX_GROWN)
        // ⭐ **할 일을 주는 쪽을 서 있는 동안 읽었으면, 그 자리에서 묻는다.**
        //    정차가 과제보다 먼저 오면 [onEvent] 의 물음이 막히는데(순서가 맞아야 하니까),
        //    멈춤이 한 번뿐인 주행에서는 **묻는 말이 통째로 사라졌다**(2026-11-05 세 살 65분).
        //    막는 것으로 끝내면 기획서가 내세우는 "멈추면 묻는다"가 조용히 죽는다.
        if (pg.quest) {
            questGiven = true
            if (stopped && !asked) {
                asked = true
                episode?.ask(0)?.let { q ->
                    queue(Beat(fill(q, g), keep = true, scene = SceneKind.STOP), ASK_GAP_MS)
                }
            }
        }
        lastSpokeAt = now()
        seenScenes += sc
        return Beat(fill(line, g, here = lastPlace), keep = true, scene = sc, voiced = true)
    }

    /**
     * **그 길이 왔다 — 어울리는 쪽이면 지금 읽는다.** 이게 "동기화"다.
     * 다음 쪽이 그 길을 기다리고 있지 않으면 **아무 말도 하지 않는다.**
     */
    private fun fitPage(sc: SceneKind?): Beat? {
        if (sc == null) return null
        val ep = book() ?: return null
        val at = peekIdx(ep) ?: return null
        if (ep.pages[at].fit == sc) return read(ep, ep.pages[at], idx = at)
        // 바로 다음 차례가 아니어도 **그 길을 가리키는 쪽이면 당겨 읽는다.**
        //
        // 65분 주행에서 터널은 한 번뿐인데, 그때 책이 3쪽쯤 읽고 있으면
        // 굴 쪽은 영영 터널을 못 만난다(2026-10-31 실측: 다섯 쪽까지만 당겼더니 못 맞췄다).
        //
        // 그래서 책을 두 겹으로 본다 — **본줄기([BookPage.core])는 순서가 고정**이고,
        // **길을 가리키는 곁가지는 그 길에서 읽는다.** 당겨 읽은 쪽만 빠지고 나머지는 제자리에 남는다.
        // ⛔ **자라는 쪽([BookPage.grow])은 당겨 읽지 않는다.** 손님이 커지는 건 그 화의 진행이라
        //    순서를 건너뛰면 앞뒤가 어긋난다 — 2026-11-02 실측: 비 오는 날 2분에 "빗방울을 받아
        //    마시더니 통통해졌어" 가 먼저 읽히고, 그 뒤에 "물은 한 방울도 없나 봐" 가 따라 나왔다.
        //
        // ⛔ 그러나 **아직 안 읽은 본줄기 쪽을 넘어가지는 못한다.** 전에는 책 끝까지 뒤져
        //    곁가지를 당겼다. 그래서 65분 주행 8분에 방지턱이 오면
        //    `파도가 왔어요! 통통. 구름이 튀어 오르더니 그제야 깔깔 웃었어요`(13쪽)가
        //    `나… 집을 잃어버렸어`(4쪽)보다 **먼저** 읽혔다(2026-11-05 전사: 8분/10분).
        //    아이는 구름이 왜 시무룩했는지도 모르는데 "그제야 웃었다"를 듣는다 —
        //    **인과가 뒤집히면 그것만으로 동화가 아니라 사건 보고가 된다.**
        //    그래서 **본줄기가 물길막**이 된다. 곁가지는 그 사이에서만 자리를 바꾼다.
        //    (이 교칙이 여는 쪽도 지킨다 — 여는 세 쪽은 본줄기니까.)
        val wall = ep.pages.indices
            .firstOrNull { it >= at && it !in readIdx && ep.pages[it].core }
            ?: ep.pages.size
        val idx = ep.pages.indices
            .firstOrNull {
                it !in readIdx && it >= at && it < wall && !ep.pages[it].core &&
                    !ep.pages[it].grow && ep.pages[it].fit == sc
            }
            ?: return null
        // **같은 길을 가리키는 쪽이 둘 이어지면 짝으로 읽는다.**
        // 굴은 들어가고 나오는 게 한 장면이다 — 나오는 쪽이 한참 뒤에 혼자 나오면
        // "구름이 다시 눈을 떴어"가 어디서 나온 말인지 알 수 없다(2026-10-31 실측).
        // ⚠️ 짝 쪽도 **[read] 를 거쳐야** 한다. 전에는 여기서 Beat 를 손으로 만들어
        //    나이·지명 규칙을 건너뛰었다(세 살에게 긴 문장이 그대로 갔다).
        ep.pages.getOrNull(idx + 1)
            ?.takeIf { it.fit == sc && !it.grow && !it.core && (idx + 1) !in readIdx && idx + 1 < wall }
            ?.let { next -> read(ep, next, idx = idx + 1)?.let { queue(it, DARK_GAP_MS) } }
        return read(ep, ep.pages[idx], idx = idx)
    }

    /**
     * 제 박자로 다음 쪽을 읽는다.
     * 어울리는 길을 기다리는 쪽이면 [WAIT_TURNS] 만큼 더 기다려 준다 —
     * 그래도 안 오면 **그냥 읽는다. 책이 멈추면 안 된다.**
     */
    private fun readNext(t: Long): Beat? {
        if (arrived) return null
        val ep = book() ?: return null
        val pg = peek(ep) ?: return null
        // ⛔ **결말은 도착해서 읽는다.** 제 박자로 읽어 버리면 도착 14분 전에 "다 왔어" 가 나가고,
        //    그 뒤에 커브 안내가 따라붙는다(2026-10-31 실측: 65분 주행에서 51분에 책이 끝났다).
        //    남은 쪽은 [closeBookPages] 가 도착에서 읽는다.
        if (pg.scene == SceneKind.ARRIVE) return null
        val gap = pageGapMs(ep)
        val waited = t - lastSpokeAt
        // ⚠️ 기다림에 **절대 상한**을 둔다. "3분을 넘기면 책을 놓는다"고 정해 놓고
        //    [WAIT_TURNS] 를 곱해 버리면 그 뜻이 날아간다(2026-10-31 실측: 6~9분 침묵).
        val due = if (pg.fit != null) minOf(gap * WAIT_TURNS, level.maxGapMs) else gap
        if (waited < due) return null
        // **같은 길을 가리키는 쪽이 둘 이어지면 제 박자로 읽을 때도 짝으로 읽는다.**
        // [fitPage] 에만 이 규칙이 있어서, 길을 못 만나 제 박자로 읽히면 짝이 떨어졌다 —
        // 2026-11-05 전사: `구름이 갑자기 조용해졌어요`(43분) 와 `구름이 다시 눈을 떴어요`(49분)
        // 사이에 정차 질문이 끼어들었다. 굴에 들어가고 나오는 건 **한 장면**이다.
        val idx = peekIdx(ep) ?: return null
        ep.pages.getOrNull(idx + 1)
            ?.takeIf { pg.fit != null && it.fit == pg.fit && !it.grow && (idx + 1) !in readIdx }
            ?.let { next ->
                val first = read(ep, pg, matched = pg.fit == null, idx = idx)
                read(ep, next, matched = next.fit == null, idx = idx + 1)
                    ?.let { queue(it, DARK_GAP_MS) }
                return first
            }
        // 기다렸는데 그 길이 안 왔다 — 길을 가리키지 않는 문장으로 읽는다.
        return read(ep, pg, matched = pg.fit == null)
    }

    /**
     * **차가 조용할 때 이야기를 한 칸 민다.**
     *
     * 전에는 예약된 장면이 없으면 앱이 그냥 입을 다물었다. 그래서 사건이 날 때만 말하게 되고,
     * **말하는 게 전부 사건 보고**가 됐다(2026-10-31 전사: 흔들림 보고 25% · 질문 35%).
     * 동화는 반대다 — 본문이 제 박자로 흐르고, 길에서 온 일이 **거기 끼어든다.**
     */
    private fun idleTell(t: Long): Beat? {
        if (arrived) return null
        val g = guest ?: return null
        if (t - lastSpokeAt < idleGapMs()) return null
        // ⛔ 전에는 [voice] 에 맡겼는데, 조각 대사엔 "짧은 판"이 없어서
        //    **세 살이 조각을 하나도 못 들었다**(2026-10-31 실측: 책이 끝난 뒤 26분 침묵).
        //    조각은 이미 짧으니, 긴 것만 건너뛰고 다음 걸 본다. 입을 다무는 건 답이 아니다.
        var line = tellNext(g)
        while (line != null && level == AgeLevel.TODDLER && line.length > TODDLER_MAX_CHARS) {
            line = tellNext(g)
        }
        lastSpokeAt = t
        return Beat(line ?: return null, keep = true, voiced = true)
    }

    /**
     * **책을 덮는다** — 남은 본줄기 쪽을 이어 읽는다.
     *
     * ⚠️ 반드시 [read] 를 거친다. 전에는 여기서 Beat 를 손으로 만들어 [gate]·[voice] 를 지났고,
     * 그 규칙이 "도착 뒤는 큰 아이용" 이라 **세 살이 책의 결말을 못 들었다**(2026-10-31 실측).
     */
    private fun closeBookPages(ep: Episode): Beat? {
        var first: Beat? = null
        var slot = 0
        ep.pages.indices
            .filter { it >= page && it !in readIdx && ep.pages[it].core }
            .forEach { i ->
                val b = read(ep, ep.pages[i], idx = i) ?: return@forEach
                if (first == null) {
                    first = b
                } else {
                    slot += 1
                    queue(b, slot * HOOK_GAP_MS)
                }
            }
        page = ep.pages.size
        return first
    }

    /**
     * **말은 하지 않고 남은 시간만 고쳐 잡는다.** 쪽 간격이 알아서 늘고 준다.
     *
     * [Plan.totalSeconds] 는 **주행 전체 길이**다. ETA 가 주는 건 **남은 시간**이므로
     * 지난 시간을 더해야 맞는다 — 전에는 그대로 대입해서 간격 계산이 지난 시간만큼 틀렸다.
     */
    private fun retime(remainSeconds: Int) {
        val p = plan ?: return
        plan = p.copy(totalSeconds = elapsedSec() + remainSeconds)
    }

    /** 출발한 뒤 흐른 **이야기 속 시간**(초). 시연은 압축 재생이라 배율을 곱한다. */
    private fun elapsedSec(): Int = (((now() - startedAt) * timeScale) / 1000f).toInt()

    /**
     * **조각 대사도 남은 시간에 고르게 퍼뜨린다.**
     *
     * 전에는 [AgeLevel.idleMs](45초) 마다 쏟아냈다. 한 화에 조각이 셋뿐이라
     * **출발 2분에 다 써 버리고 그 뒤 15분을 입 다물었다**(2026-10-31 실측: 40분 조용한 길).
     * 본문이 있는 화는 [pageGapMs] 가 같은 일을 한다 — 여기는 본문 없는 화의 몫이다.
     */
    private fun idleGapMs(): Long {
        val p = plan ?: return level.idleMs
        val left = (tellPool().size - told).coerceAtLeast(1)
        val remain = ((p.totalSeconds - elapsedSec()) * 1000L).coerceAtLeast(0L)
        // 본문이 있는 화라면 책을 다 읽고 남은 시간을 메우는 자리다 — **책과 같은 박자**로 간다.
        // 본문이 없는 화는 조각이 여섯 줄뿐이라 더 띄워야 고르게 퍼진다.
        val cap = if (book() != null) level.maxGapMs else MAX_IDLE_GAP_MS
        val story = (remain / left).coerceIn(level.idleMs, cap)
        return (story / timeScale).toLong().coerceAtLeast(1_500L)
    }

    /** @param delayMs 앞 대사에 바로 이어 붙을 시간. 이어지는 장면이라 길게 끌면 안 된다. */
    private fun queue(beat: Beat, delayMs: Long) {
        pending += Pending(now() + delayMs, beat)
    }

    /**
     * **책을 덮어도 되는가.** 도착했고 예약된 쪽도 다 나갔을 때만 참이다.
     * 도착 이벤트에서 바로 닫으면 **마지막 두 쪽이 책에 안 들어간다**(2026-10-31 실측: 18장).
     */
    val done: Boolean get() = arrived && pending.isEmpty()

    // ──────────────────────────────── 속 ────────────────────────────────

    /** 말이 겹치지 않게 최소 간격을 둔다. 단 뼈대와 급한 안내는 삼키지 않는다. */
    private fun gate(beat: Beat): Beat? {
        val t = now()
        if (!beat.keep && t - lastSpokeAt < level.gapMs) return null
        val voiced = voice(beat) ?: return null
        lastSpokeAt = t
        return voiced
    }

    /**
     * 같은 사건을 **나이에 맞는 말**로 바꾼다.
     * - 3~4세: 짧은 판이 있으면 그걸로, 없으면 **뼈대가 아닌 한 말하지 않는다**(말수가 적어야 한다)
     * - 5~6세: 그대로
     * - 7~9세: 꼬리 질문이 있으면 붙인다
     */
    private fun voice(beat: Beat): Beat? = if (beat.voiced) beat else when (level) {
        AgeLevel.TODDLER -> when {
            beat.short != null -> beat.copy(line = beat.short)
            beat.urgent -> beat
            beat.keep && beat.scene != SceneKind.ARRIVE -> beat
            else -> null          // 도착 뒤 떡밥은 큰 아이용이다
        }

        AgeLevel.KID -> beat
        AgeLevel.BIG -> beat.tail?.let { beat.copy(line = "${beat.line} $it") } ?: beat
    }

    /** 자리표시자를 채운다. 아이 이름과 목적지가 들어가야 "우리 이야기"가 된다. */
    private fun fill(template: String, g: Guest, here: String? = null): String = particles(
        template
            .replace("{아이}", childName)
            .replace("{손님}", g.name)
            .replace("{때}", WHEN[sky()] ?: "낮")
            .replace("{곳}", destination ?: "먼 곳")
            .replace("{여기}", here ?: "이 길")
            .replace("{탈것}", season.vehicle),
    )

    /**
     * 조사를 **앞 글자를 보고** 정한다. 이름이나 지명이 들어간 **뒤에** 풀어야 맞는다.
     * 먼저 풀면 "에버랜드을", "서준 머리"가 된다(2026-10-29 시연에서 둘 다 나왔다).
     *
     * `{을}`→을/를 · `{는}`→은/는 · `{가}`→이/가 · `{과}`→과/와 ·
     * `{이}`→이름 뒤 "이"(서준이 / 지우) · `{호격}`→아/야
     */
    private fun particles(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) { out.append(text, i, text.length); break }
            out.append(text, i, open)
            val close = text.indexOf('}', open)
            if (close < 0) { out.append(text, open, text.length); break }
            val tok = text.substring(open + 1, close)
            val prev = out.lastOrNull()
            val final = prev != null && prev in '가'..'힣' && (prev - '가') % 28 != 0
            out.append(
                when (tok) {
                    "을" -> if (final) "을" else "를"
                    "는" -> if (final) "은" else "는"
                    "가" -> if (final) "이" else "가"
                    "과" -> if (final) "과" else "와"
                    "이" -> if (final) "이" else ""
                    "호격" -> if (final) "아" else "야"
                    else -> "{$tok}"
                },
            )
            i = close + 1
        }
        return out.toString()
    }

    /**
     * 남은 시간이 바뀠다. **얼마나 어긋났는지**는 "원래 남아 있어야 할 시간"과 비교해서 안다 —
     * 전에는 `remainSeconds` 를 주행 **전체 길이**와 비교해서, 출발 직후엔 얼추 맞고
     * 뒤로 갈수록 틀렸다(30분 지난 뒤엔 30분어치가 어긋난다).
     */
    private fun onEta(e: CarEvent.EtaChanged): Beat? {
        val p = plan ?: return null
        val g = guest ?: return null
        val expected = (p.totalSeconds - elapsedSec()).coerceAtLeast(0)
        val grew = e.remainSeconds - expected
        retime(e.remainSeconds)
        return when {
            grew > GROW_THRESHOLD_SEC -> Beat(fill(g.delayed, g), keep = true)
            grew < -GROW_THRESHOLD_SEC -> Beat(fill(g.hurried, g), keep = true)
            else -> null
        }
    }

    /** 그 종류를 이번 주행에서 몇 번째 말하는가(1부터). 반복을 피하는 유일한 근거다. */
    private fun nth(kind: String): Int {
        val n = (said[kind] ?: 0) + 1
        said[kind] = n
        return n
    }

    /**
     * 사건 뒤에 **오늘의 과제**를 한 조각 붙인다. 이것이 "기울어"를 동화로 만드는 자리다.
     *
     * 세 번까지만 붙인다 — 그 뒤엔 아이가 이미 과제를 알고 있고, 더 말하면 잔소리가 된다.
     * 세 번째를 붙이고 나면 **찾았다고 치고 돌려준다**([Episode.gain]).
     * 아이가 실제로 뭘 찾았는지 우리는 알 수 없지만, **헛되게 두면 다음부터 안 찾는다.**
     */
    private fun questTail(g: Guest): String? {
        val ep = episode ?: return null
        val t = if (looked < MAX_LOOKS) ep.look(looked) else null
        if (t == null) {
            // 더 줄 게 없으면 **찾았다고 치고 돌려준다.** 아이가 뭘 찾았는지 우리는 모르지만,
            // 헛되게 두면 다음부터 안 찾는다.
            if (!gained) {
                gained = true
                // 과제를 해냈으면 **손님이 자란다.** 본문 없는 화에서 화면이 변하는 유일한 자리다.
                grown = (grown + 1).coerceAtMost(MAX_GROWN)
                queue(Beat(fill(ep.gain, g), keep = true), GAIN_GAP_MS)
            }
            return null
        }
        looked += 1
        return fill(t, g)
    }

    /**
     * **그냥 들려주는 본문 한 줄.** 질문도 요청도 아니다.
     * 차가 움직일 때마다 받아 적는 대신, **이야기가 한 칸 나아간다.**
     */
    private fun tellNext(g: Guest): String? {
        val t = tellPool().getOrNull(told) ?: return null
        told += 1
        return fill(t, g)
    }

    /**
     * 지금 들려줄 수 있는 조각 전체.
     * 화 원고가 있으면 그걸 쓰고, **없으면 손님의 평상 대사**([Guest.idle])로 간다 —
     * 시즌 2·3 은 12화 줄거리가 아직 없어서 여기가 비면 입을 다문다.
     */
    private fun tellPool(): List<String> =
        episode?.tells?.takeIf { it.isNotEmpty() } ?: guest?.idle ?: emptyList()

    /**
     * 기울어질 때. **첫 번째만 제대로 말하고, 그 뒤로는 과제로 넘긴다.**
     *
     * 전에는 문장이 [Guest.tiltEarly]/[tiltLate] 둘뿐이라 65분 주행에서 같은 말이 스무 번 나왔다.
     * 커브는 길에서 제일 자주 오는 사건이라, 여기가 제일 빨리 들통난다(2026-10-31).
     */
    private fun tiltHold(g: Guest, side: String, shake: String, n: Int): String {
        val t = if (n <= 1) {
            // ⚠️ 손님이 다 컸는지는 **지금 크기**([grown])로 안다. 화 번호로 가르면
            //    8화에서 "구름이 거의 다 컸어" 가 나온다 — 비가 되어 손톱만 해진 화다
            //    (2026-11-05 전사 5분). 크기는 이제 원고가 깔아 주므로([Episode.startGrown])
            //    1화 첫 커브에서 "다 컸다"가 나오던 옛 함정도 같이 막힌다.
            if (grown >= TALL_GROWN) g.tiltLate else g.tiltEarly
        } else {
            TILT_HOLD[(n - 2).mod(TILT_HOLD.size)]
        }
        return fill(t, g).replace("{쪽}", side).replace("{세기}", shake)
    }

    /**
     * 3~4세용 한 호흡 안내. **이것도 돌려 써야** 한다 —
     * 고정 문장이라 한 주행에 세 번 똑같이 나갔다(2026-10-31 실측: 시내 20분, 3세).
     */
    private fun tiltShort(g: Guest, side: String, n: Int): String =
        TILT_SHORT[(n - 1).mod(TILT_SHORT.size)]
            .replace("{쪽}", side).replace("{손님}", g.name)

    private fun tiltLine(g: Guest, side: String, shake: String, n: Int): String {
        val hold = tiltHold(g, side, shake, n)
        return if (n <= 1) hold else questTail(g)?.let { "$hold $it" } ?: hold
    }

    /** 방지턱도 하나로는 못 버틴다 — **세면서 말이 달라져야** 그게 파도가 된다. */
    private fun bumpLine(g: Guest, n: Int): String = when (n) {
        1 -> g.bump
        2 -> "또 파도야! {손님}{가} 이번엔 준비하고 있었어."
        3 -> "파도 셋째. {손님}{가} 하나하나 세고 있어."
        else -> "통통! {손님}{가} 이제 파도를 즐기네."
    }

    /**
     * **같은 굴을 몇 번째 지나느냐**에 따라 말이 달라진다.
     * 매일 같은 길을 가는 아이에게 같은 대사를 틀면 사흘이면 끝이다.
     * 반복이 쌓여 그 굴이 **캐릭터**가 되는 것이 이 앱의 핵심이다.
     */
    private fun darkInLine(g: Guest, nth: Int): String = when {
        nth <= 1 -> g.darkIn
        nth <= 4 -> "또 그 굴이네. 어제보단 덜 무섭지? 오늘은 안에서 소리를 들어 볼까?"
        nth <= 9 -> "그 굴이야. 저번에 누가 숨 쉬는 소리가 났잖아. 오늘은 더 귀 기울여 보자."
        else -> "굴 주인이 우리를 기다리고 있을지도 몰라. ${g.name}도 이제 안 무서워해."
    }

    private fun darkOutLine(g: Guest, nth: Int): String = when {
        nth <= 1 -> g.darkOut
        nth <= 4 -> "나왔다! ${g.name}이 오늘은 하나도 안 울었어."
        nth <= 9 -> "들었어? 아까 그 소리. ${g.name}이 '친구인 것 같다'고 하네."
        else -> "${g.name}이 굴 쪽을 한 번 더 돌아봤어. 곧 만나게 될 것 같아."
    }

    /** 주의 구간을 **손님의 사건**으로 바꾼다. 경고도, 바꿔 말하기도 아니다. */
    private fun cautionLine(g: Guest, kind: CautionPointType, n: Int): String? = when (kind) {
        CautionPointType.SPEED_BUMP -> fill(bumpLine(g, n), g)
        CautionPointType.SCHOOL_ZONE -> fill(g.school, g)
        CautionPointType.WILDLIFE_CROSSING -> fill(g.wild, g)
        CautionPointType.RAILROAD_CROSSING -> fill(g.rail, g)
        CautionPointType.FOG_AREA -> fill(g.fog, g)
        CautionPointType.FALLING_ROCK_AREA -> "커다란 바위산이 바로 옆이야. ${g.name}이 위를 한참 올려다봐."
        CautionPointType.DOWNHILL_CAUTION_AREA -> "앗, 아래로 쭈욱! ${g.name}이 미끄럼틀 같대."
        CautionPointType.NARROW_ROAD -> "양쪽이 바짝 좁아졌어. ${g.name}이 몸을 쏙 집어넣었어."
        CautionPointType.SLIPPERY_ROAD_ALERT -> "바닥이 반짝반짝해. ${g.name}이 미끄러질까 봐 꽉 매달렸어."
        else -> null   // 단속 카메라 같은 건 아이 이야기에 넣지 않는다
    }

    /** 3~4세용 한 호흡 판. 없는 종류는 어린 층에서 말하지 않는다 — 말수가 적어야 한다. */
    private fun shortCaution(g: Guest, kind: CautionPointType): String? = when (kind) {
        CautionPointType.SPEED_BUMP -> "통통! ${g.name}이 깡충."
        CautionPointType.SCHOOL_ZONE -> "친구들이야. 손 흔들어 줄까?"
        CautionPointType.WILDLIFE_CROSSING -> "숲이야. 누가 있나 찾아봐."
        CautionPointType.RAILROAD_CROSSING -> "칙칙폭폭! 기차 소리 들려?"
        CautionPointType.FOG_AREA -> "하얘! 구름이 내려왔어."
        else -> null
    }

    /** 7~9세에만 붙는 꼬리 질문. 어린 층에는 압박이 된다. */
    private fun tailCaution(kind: CautionPointType): String? = when (kind) {
        CautionPointType.SPEED_BUMP -> "왜 여기만 바닥이 볼록할까?"
        CautionPointType.SCHOOL_ZONE -> "여기엔 왜 친구들이 많을까?"
        CautionPointType.WILDLIFE_CROSSING -> "동물들은 왜 길을 건널까?"
        CautionPointType.RAILROAD_CROSSING -> "기차는 왜 길을 비켜 주지 않을까?"
        else -> null
    }

    /**
     * **아이가 알아들을 지명만 남긴다. 나머지는 버린다.**
     *
     * 다섯 살에게 "성남시 분당구"는 아무 뜻이 없다. 행정구역 이름은 어른의 말이고,
     * 그걸 읽으면 동화 세계가 그 자리에서 깨진다 — **동화에 주소가 나오지 않는다.**
     *
     * 아이가 아는 건 **눈에 보이는 것**뿐이다 — 다리·강·산·바다·공원·숲·시장·역.
     * 그래서 그런 말이 든 이름만 통과시키고, 그마저 **동화 말로 바꿔서** 준다.
     * 통과하는 게 없으면 null 이고, 그 쪽은 [BookPage.alt] 로 넘어간다.
     *
     * (목적지는 다르다 — "할머니 집"·"어린이집"은 부모가 찍은 이름이고 **아이도 안다.**
     *  그건 `{곳}` 으로 이미 쓰고 있다.)
     */
    private fun childPlace(address: String): String? {
        val tail = address.trim().split(' ').lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // ⛔ **보통명사만 돌려준다.** 전에는 통과한 이름을 그대로 읽어서
        //    "남한산성이래", "구름이 북한강을 둘러보더니" 가 나갔다(2026-10-31 실측).
        //    다섯 살은 '남한산성'을 모른다 — 아는 건 **산**이다.
        // ⛔ 그리고 **그 글자로 끝날 때만** 통과시킨다. 포함만 보면
        //    "역삼동"이 기차역이 되고 "남한산성"이 산이 된다.
        for ((mark, word) in CHILD_PLACES) {
            if (tail.endsWith(mark)) return word
        }
        return null
    }

    companion object {

        /**
         * **여는 줄의 때.** 동화는 "옛날 어느 아침에" 로 열고, 내비는 "42분 남았습니다" 로 연다.
         *
         * 2026-11-04 까지 열두 화 전부가 **"{곳}까지 {분}분이야"** 로 시작했다 —
         * 아이가 타서 **맨 처음 듣는 한 줄이 도착 예정 시각**이었다는 뜻이다.
         * 남은 시간은 내비가 이미 말하고 화면에도 떠 있다. 그걸 동화 말투로 한 번 더
         * 읽으면 동화가 아니라 **안내의 더빙**이 된다. 그래서 숫자를 빼고 때로 연다.
         */
        val WHEN: Map<TimeOfDay, String> = mapOf(
            TimeOfDay.MORNING to "아침",
            TimeOfDay.DAY to "낮",
            TimeOfDay.DUSK to "저녁",
            TimeOfDay.NIGHT to "밤",
        )

        /**
         * 7~9세에게만 붙는 커브 꼬리. **한 가지로 통일한다** — 커브는 한 주행에 열 번도 오는데
         * 꼬리가 매번 다르면 그게 질문 공세가 된다. 3화(바람)로 이어지는 물음이라 헛되지도 않다.
         */
        const val BIG_TAIL = "그런데 바람은 어디서 오는 걸까?"

        /**
         * 여는 쪽 수 — 때·자리·누가 나오는지까지. **이만큼은 본줄기여야 한다.**
         *
         * 전에는 [fitPage] 가 이 수를 들고 "세 쪽 전엔 당기지 말기"를 따로 막았는데,
         * 그러면 한 가지 일에 규칙이 둘이 된다. 지금은 **본줄기가 물길막**이라
         * 여는 쪽을 `core` 로 두는 것만으로 같은 일이 된다 — 사람이 지킬 일이므로
         * 하네스가 원고를 읽어 붙들어 둔다(`여는_세_쪽은_본줄기다`).
         */
        const val OPENING_PAGES = 3

        /** 장 번호를 한글로. 동화에 아라비아 숫자가 나오지 않는다. */
        val ORDINALS = listOf("첫", "두", "세", "네", "다섯")

        /** 손님을 소개하고 "왜 가야 하는지"까지. 한 번에 다 말하면 설명이 된다. */
        const val OPENING_GAP_MS = 4_500L

        /** 굴에 들어갔다 나오는 시간. 손님이 변하는 자리라 사건 사이에 끼면 안 된다. */
        const val DARK_GAP_MS = 5_500L

        /** 도착 인사 뒤에 다음 화 떡밥을 붙이는 간격. */
        const val HOOK_GAP_MS = 5_000L

        /** 손님 소개 뒤에 **오늘의 과제**를 주는 간격. */
        const val TASK_GAP_MS = 4_500L

        /** 멈춘 뒤 아이에게 묻기까지. 멈추자마자 물으면 아직 창밖을 보고 있다. */
        const val ASK_GAP_MS = 3_500L

        /**
         * 멀미 안내를 내보낸 뒤 **그 커브 쪽**을 읽기까지.
         * 흔들리는 동안은 아이가 몸을 잡고 있으므로 한 호흡 쉬어야 들린다.
         */
        const val TILT_PAGE_GAP_MS = 6_000L

        /**
         * 과제를 몇 번까지 안내하나. **둘.**
         * 셋이면 출발 과제까지 더해 한 주행에 요청이 넷이 되고, 그러면 동화가 아니라 숙제다.
         */
        const val MAX_LOOKS = 2

        /**
         * **아이가 아는 지명만 통과시키는 체.** 눈에 보이는 것이 이름에 들어 있어야 한다.
         * 통과한 뒤에도 그대로 읽지 않고 **동화 말로 바꿔** 준다.
         */
        val CHILD_PLACES: List<Pair<String, String>> = listOf(
            "대교" to "아주 큰 다리",
            "다리" to "다리",
            "교" to "큰 다리",
            "해수욕장" to "바닷가",
            "바다" to "바다",
            "호수" to "호수",
            "저수지" to "큰 물웅덩이",
            "강" to "강",
            "산" to "산",
            "고개" to "고갯길",
            "숲" to "숲",
            "공원" to "공원",
            "동물원" to "동물원",
            "시장" to "시장",
            "역" to "기차역",
            "항" to "배가 쉬는 곳",
            "터널" to "굴",
            "분기점" to "갈림목",
            "나들목" to "드나드는 문",
            "휴게소" to "쉼터",
        )

        /** 어울리는 길을 몇 박자까지 기다려 주나. 그래도 안 오면 그냥 읽는다. */
        const val WAIT_TURNS = 2

        /** 손님이 자랄 수 있는 끝. [SceneView.growth] 가 그리는 한계와 같아야 한다. */
        const val MAX_GROWN = 6

        /** 이만큼 자라면 "거의 다 컸다"고 말해도 거짓이 아니다. */
        const val TALL_GROWN = 4

        /** 3~4세가 한 호흡에 받는 길이. 넘으면 그 자리에서 놓친다. */
        const val TODDLER_MAX_CHARS = 35


        /**
         * 조각 대사의 상한. **본문보다 느슨하다** —
         * 본문 없는 화는 조각이 여섯 줄뿐이라, 3분 상한을 쓰면 앞에 몰아 쏟고
         * 뒤를 통째로 비운다(2026-10-31 실측: 40분 주행에 15분 침묵).
         * 고르게 띄우는 쪽이 낫다. **진짜 해결은 그 화의 본문을 쓰는 것이다.**
         */
        const val MAX_IDLE_GAP_MS = 7 * 60_000L

        /** 과제를 다 안내한 뒤 "찾았다"를 돌려주기까지. */
        const val GAIN_GAP_MS = 7_000L

        /**
         * 두 번째부터 쓰는 **짧은 기울기.** 뒤에 그 화의 과제가 붙는다.
         * 첫 번째처럼 길게 말하면 과제를 붙일 자리가 없다.
         */
        /** 3~4세용 한 호흡 안내. 한 주행에 셋까지는 안 겹친다. */
        val TILT_SHORT = listOf(
            "바람이 {쪽}으로 밀어! {손님} 꼭 잡아.",
            "또 바람이야! {손님} 안 떨어지게 잡아 줘.",
            "바람에 {쪽}으로 쏠려! {손님} 꽉.",
            "바람이 휘청! {손님} 떨어질라.",
        )

        val TILT_HOLD = listOf(
            "{세기} 바람이 {탈것}{을} {쪽}으로 밀어 — {손님} 꼭 잡아!",
            "또 바람이 왔어! {손님}{가} {쪽}으로 쏠렸어.",
            "바람이 {탈것}{을} {쪽}으로 빙 돌려. {손님}{가} {아이}{이} 손을 잡았어.",
            "{세기} 바람에 흔들려! {손님}{는} 괜찮대.",
        )
        const val GROW_THRESHOLD_SEC = 5 * 60
    }
}
