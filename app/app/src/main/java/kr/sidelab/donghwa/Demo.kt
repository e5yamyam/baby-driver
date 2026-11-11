package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.CautionPointType
import android.os.Handler
import android.os.Looper

/**
 * 시연용 주행 재생.
 *
 * 왜 필요한가: 에뮬레이터에서 실제로 길을 따라 달리게 하려면 내비 앱이 경로를 타야 한다.
 * 그게 되기 전까지, **진짜 [CarEvent] 를 진짜 [StoryDirector] 에 넣어** 이야기가 어떻게 흐르는지 보여 준다.
 * 꾸며 낸 화면이 아니라 실제 엔진이 도는 것이고, 들어오는 신호만 사람이 만든 것이다.
 *
 * 켜는 법:
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity --ez demo true
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity --ez demo true --es script b
 */
object Demo {

    private val main = Handler(Looper.getMainLooper())

    /** 집 → 에버랜드 65분. 기획서 시나리오 A 를 그대로 재생한다. */
    private val script: List<Pair<Long, CarEvent>> = listOf(
        0L to CarEvent.RouteStarted("에버랜드", remainSeconds = 65 * 60, remainMeters = 54_000),
        11_000L to CarEvent.Place("경기도 성남시 분당구"),
        15_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 140, description = "분당수서로"),
        // ⭐ **예고한 커브가 실제로 오는 순간** — 깜빡이. 180m 전에 말해 두고,
        //    여기서 배가 기운다. 예고와 실제를 둘 다 가진 건 차 안에서만 되는 일이다.
        //    ⚠️ 에뮬레이터에서는 깜빡이를 밖에서 켤 길이 없다(scriptE 의 와이퍼와 같은 사정) —
        //       실차에서 올 신호를 그대로 넣어 **경로가 화면까지 이어지는지** 보여 준다.
        //    핸들 각도(도)가 세기를 정한다: 크게 꺾은 커브일수록 더 기운다.
        18_000L to CarEvent.Turning(CarEvent.Turning.Side.RIGHT, degrees = 330f),
        21_000L to CarEvent.Turning(null, degrees = 0f),
        23_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 90),
        31_000L to CarEvent.Landmark(CarEvent.Landmark.Kind.TUNNEL, meters = 150),
        39_000L to CarEvent.EtaChanged(remainSeconds = 77 * 60, remainMeters = 48_000),
        // ⚠️ 2026-10-31: 65분 주행에 커브가 둘뿐이면 **반복이 안 드러난다.**
        //    실제 길은 커브가 제일 자주 오는 사건이라, 여기서 안 보이면 차에서 들통난다.
        43_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.LEFT, sharp = false, meters = 180, description = "용인서울고속도로"),
        45_000L to CarEvent.Turning(CarEvent.Turning.Side.LEFT, degrees = 120f),   // 살짝 돌린 커브
        48_000L to CarEvent.Turning(null, degrees = 0f),
        49_000L to CarEvent.CautionAhead(CautionPointType.WILDLIFE_CROSSING, meters = 120),
        51_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = false, meters = 200, description = "광교상현"),
        55_000L to CarEvent.Landmark(CarEvent.Landmark.Kind.REST_AREA, meters = 400),
        59_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 60),
        63_000L to CarEvent.WaypointArrived,
        71_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
        79_000L to CarEvent.MotionChanged(moving = true, speedKph = 42f),
        83_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 110, description = "포곡읍"),
        87_000L to CarEvent.CautionAhead(CautionPointType.SCHOOL_ZONE, meters = 80),
        91_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 50),
        95_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.LEFT, sharp = false, meters = 160, description = "에버랜드 진입"),
        97_000L to CarEvent.Turning(CarEvent.Turning.Side.LEFT, degrees = 210f),
        100_000L to CarEvent.Turning(null, degrees = 0f),
        99_000L to CarEvent.Place("경기도 용인시 처인구 포곡읍"),
        103_000L to CarEvent.DestinationArrived,
        // ⚠️ 도착하면 차는 멈춘다. 이 줄이 없으면 `parked` 가 false 로 남아
        //    **책장 버튼이 끝까지 안 뜬다**(2026-10-31: 시연에서 책을 못 보여 줬다).
        107_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
    )

    /**
     * 시나리오 B — 평일 등원 12분. 기획서의 "짧은 주행은 이야기 한 개" 분기를 보여 준다
     * ([StoryDirector.Plan.of] 가 10분 미만이면 1장, 25분 미만이면 2장을 고른다).
     */
    private val scriptB: List<Pair<Long, CarEvent>> = listOf(
        0L to CarEvent.RouteStarted("어린이집", remainSeconds = 12 * 60, remainMeters = 4_200),
        11_000L to CarEvent.Place("경기도 용인시 기흥구"),
        12_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 70),
        18_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = false, meters = 120, description = "구성로"),
        24_000L to CarEvent.CautionAhead(CautionPointType.SCHOOL_ZONE, meters = 60),
        30_000L to CarEvent.DestinationArrived,
        // ⚠️ 도착하면 차는 멈춘다. 이 줄이 없으면 `parked` 가 false 로 남아
        //    **책장 버튼이 끝까지 안 뜬다**(2026-10-31: 시연에서 책을 못 보여 줬다).
        34_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
    )

    /**
     * 시나리오 C — **조용한 길 2분.** 고속도로처럼 사건이 뜸할 때
     * 이야기가 제 박자로 흐르는지 본다(2026-10-31). 사건만 기다리면 앱이 입을 다문다.
     */
    private val scriptC: List<Pair<Long, CarEvent>> = listOf(
        0L to CarEvent.RouteStarted("할머니 집", remainSeconds = 40 * 60, remainMeters = 32_000),
        60_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.LEFT, sharp = false, meters = 300, description = "영동고속도로"),
        150_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 60),
        210_000L to CarEvent.DestinationArrived,
        // ⚠️ 도착하면 차는 멈춘다. 이 줄이 없으면 `parked` 가 false 로 남아
        //    **책장 버튼이 끝까지 안 뜬다**(2026-10-31: 시연에서 책을 못 보여 줬다).
        214_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
    )

    /**
     * 시나리오 D — **전기차 충전 대기 25분.** 기획서 시나리오 C 다.
     *
     * 차가 서 있고 **주행 신호가 하나도 안 온다.** 그래도 책이 끝까지 읽혀야 한다 —
     * 충전 대기는 아이가 차에 묶여 있는 25분이고, 동화에 제일 좋은 시간이다.
     * SOC 는 1%씩 올라오므로 쪽을 태우지 않는지도 같이 보인다([StoryDirector] 가 첫 번만 읽는다).
     */
    private val scriptD: List<Pair<Long, CarEvent>> =
        listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("충전소", remainSeconds = 25 * 60, remainMeters = 1_200),
            4_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
        ) + (1..22).map { i -> (8_000L + i * 4_000L) to CarEvent.ChargeLevel(40f + i * 2.5f) } + listOf(
            100_000L to CarEvent.DestinationArrived,
            104_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
        )

    /**
     * 시나리오 E — **비 오는 날 등원 20분.** 2026-11-02 에 측정으로 확인한 차 신호를 쓰는 판이다
     * (와이퍼 = 비 · 창문 = 바람 · 뒷좌석 = 동승 — SDK_FACTS 7.5.1).
     *
     * 왜 대본으로 넣나: 에뮬레이터에서는 **와이퍼를 밖에서 켤 길이 없다.**
     * Virtual Console 쓰기는 `503 emulator_inactive` 고, `cmd car_service inject-vhal-event` 는
     * user 빌드라 막혀 있다(둘 다 2026-11-02 실측). 그래서 실차에서 올 신호를 그대로 넣어
     * **경로가 끝까지 이어지는지**(와이퍼 → CarEvent.Rain → RAIN 쪽) 보여 준다.
     *
     * ⚠️ 이건 **신호를 흉내 내는 것**이고, 바꿀 길이 열리면 이 대본은 지운다.
     */
    private val scriptE: List<Pair<Long, CarEvent>> = listOf(
        0L to CarEvent.RouteStarted("어린이집", remainSeconds = 20 * 60, remainMeters = 7_000),
        6_000L to CarEvent.Riders(1),
        9_000L to CarEvent.Rain(true),
        14_000L to CarEvent.Place("경기도 용인시 기흥구"),
        19_000L to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, meters = 70),
        26_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = false, meters = 130, description = "구성로"),
        34_000L to CarEvent.Breeze(open = true),
        43_000L to CarEvent.Landmark(CarEvent.Landmark.Kind.TUNNEL, meters = 150),
        51_000L to CarEvent.Breeze(open = false),
        56_000L to CarEvent.Rain(false),
        61_000L to CarEvent.CautionAhead(CautionPointType.SCHOOL_ZONE, meters = 60),
        68_000L to CarEvent.DestinationArrived,
        72_000L to CarEvent.MotionChanged(moving = false, speedKph = 0f),
    )

    private var running = false

    /** 시연 시나리오는 토요일 아침 나들이다. 에뮬레이터 시계와 무관하게 아침 하늘로 본다. */
    var timeOfDay = TimeOfDay.MORNING
        private set

    /**
     * **시연은 65분을 2분에 재생한다.** 이야기 박자(쪽 넘기는 간격)도 같이 줄이지 않으면
     * 책이 두 쪽만 읽히고 도착해 버린다. 재생할 때 실제 배율을 계산해 둔다.
     */
    var timeScale = 1f
        private set

    /**
     * 시연에서 하늘을 지정한다 — `--es sky night`.
     *
     * 왜 필요한가: 밤 그림 13장을 그려 두고도 **한 번도 못 봤다.** 시연은 늘 아침으로 고정돼 있고
     * (아래 [timeOfDay]), 진짜 밤을 기다리면 확인에 하루가 걸린다. 시연 영상에서
     * "밤에 가는 길" 을 보여 주려 해도 같은 문제가 생긴다.
     */
    fun skyOf(name: String?): TimeOfDay? = when (name?.lowercase()) {
        "night", "밤" -> TimeOfDay.NIGHT
        "dusk", "저녁" -> TimeOfDay.DUSK
        "day", "낮" -> TimeOfDay.DAY
        "morning", "아침" -> TimeOfDay.MORNING
        else -> null
    }

    /**
     * @param stretch 대본을 늘리는 배율. 1f 면 적힌 대로.
     *
     * ⛔ **왜 늘리나.** 65분 주행을 107초로 접으면 사건이 2~4초 간격으로 쏟아지는데,
     *    한 줄 읽는 데는 평균 8초가 걸린다. 차에서는 사건 사이가 몇 분이라 문제가 없지만,
     *    **시연 영상에서는 소리가 서로 겹친다**(2026-11-07 지적). 앱은 읽는 중이면 다음 줄을
     *    버리므로([Narrator] 의 ducked) 차에서 겹쳐 들릴 일은 없다 — 겹침은 **압축이 만든 것**이다.
     *    영상에서는 이야기를 끝까지 들려줘야 하니, 버리는 대신 **대본을 늘려** 숨 쉴 틈을 준다.
     *    이야기 속 시계([timeScale])는 늘린 길이로 다시 계산하므로 "65분 주행"은 그대로다.
     */
    fun start(feed: (CarEvent) -> Unit, which: String? = null, sky: TimeOfDay? = null,
              stretch: Float = 1f) {
        if (running) return
        running = true
        val short = which.equals("b", ignoreCase = true)
        val chosen = when {
            which.equals("c", ignoreCase = true) -> scriptC
            which.equals("d", ignoreCase = true) -> scriptD
            which.equals("e", ignoreCase = true) -> scriptE
            short -> scriptB
            else -> script
        }
        timeOfDay = sky ?: if (short) TimeOfDay.DAY else TimeOfDay.MORNING
        // 대본의 RouteStarted 가 말하는 "이야기 속 시간" ÷ 실제 재생 시간
        val storySec = (chosen.first().second as CarEvent.RouteStarted).remainSeconds.toFloat()
        val k = stretch.coerceIn(0.2f, 10f)
        val playSec = chosen.last().first * k / 1000f
        timeScale = (storySec / playSec).coerceAtLeast(1f)
        for ((delay, event) in chosen) {
            main.postDelayed({
                android.util.Log.i("DonghwaDemo", "feed=$event")
                feed(event)
            }, (delay * k).toLong())
        }
        val label = when (chosen) {
            scriptB -> "B·등원"
            scriptC -> "C·조용한 길"
            scriptD -> "D·충전 대기"
            scriptE -> "E·비 오는 날"
            else -> "A·나들이"
        }
        android.util.Log.i("DonghwaDemo", "시연 시작($label) — ${chosen.size}개 장면 · 배율 $k")
        main.postDelayed({ running = false }, (chosen.last().first * k).toLong() + 1_000)
    }
}
