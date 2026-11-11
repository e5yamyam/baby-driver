package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.CautionPointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **실주행 박자를 가짜 시계로 흘려 보내는 하네스.**
 *
 * ## 왜 이게 필요한가 (2026-10-31)
 * 그전까지 검증은 에뮬레이터 시연([Demo])으로만 했다. 시연은 65분을 2분에 재생하므로
 * `timeScale` 이 32쯤 된다. 그런데 **`AgeLevel.idleMs` 는 배율을 안 받는다** —
 * 그래서 압축 재생에서는 조용할 때 끼어드는 경로가 **한 번도 안 돌았다.**
 * 실주행에서만 나는 버그가 통째로 숨어 있었다는 뜻이다.
 *
 * 여기서는 시계를 직접 돌린다. 65분 주행을 밀리초에 돌리므로
 * **주행 수십 번을 한 번에** 볼 수 있고, 사람이 전사를 읽어 판단할 수도 있다.
 *
 * 전사는 `build/전사.txt` 에 떨어진다 — 숫자만 보지 말고 **읽어야** 안다.
 */
/**
 * **내비 말투를 찾아내는 체.**
 *
 * ## 왜 기계가 보나 (2026-11-04 사용자)
 * "동화스럽게 손봐 줘 — 아직 살짝 네비게이션 안내 같아."
 *
 * 세 번째로 같은 지적을 받았다. 2026-10-29 에 "길을 말랑한 단어로 바꿔 말하는 것"을 버렸고,
 * 10-31 에 "지금 ○○를 지나고 있어"를 버렸는데도 또 났다. **고칠 때마다 눈으로만 봤기 때문이다.**
 * 눈은 열두 화 중 한 화만 읽는다 — 그래서 열두 화 전부가 똑같이
 * `{곳}까지 {분}분이야` 로 시작하는 걸 여태 못 봤다. 아이가 **맨 처음 듣는 한 줄이
 * 도착 예정 시각**이었다.
 *
 * 그래서 말투를 규칙으로 적는다. 아래 여섯 줄이 "내비 말투"의 정의다.
 *
 * | 축 | 안 되는 까닭 |
 * |---|---|
 * | 숫자 | 동화에 계기판이 없다. 남은 시간은 내비가 이미 말하고 화면에도 떠 있다 |
 * | 길 상태 | "길이 막혔어 / 좁아져" 는 교통정보다. 주어가 **길**이면 그건 안내다 |
 * | 교통어 | 목적지·경유·우회전·정체 — 어른의 낱말이고, 다섯 살은 모른다 |
 * | 보고체 | "지나고 있어" 는 중계다. 동화는 **사건**으로 말한다 |
 * | 운전 지시 | "천천히 가자" 는 운전자에게 하는 말이다. 뒷좌석 아이가 할 수 있는 일이 아니다 |
 * | 기울기 원인 | "오른쪽으로 많이 기울어" 는 방향·세기 **보고**다. 동화에는 **바람이 밀어서** 기운다 |
 *
 * 하나 더 — **조사**. 자리표시자를 쓰면 "크게 한 바퀴으로" 처럼 말이 깨지는데,
 * 사람은 U턴이 실제로 올 때까지 못 본다(시연 대본엔 U턴이 없다).
 */
object Tone {

    private val RULES: List<Pair<String, Regex>> = listOf(
        "숫자" to Regex("""\{분}|[0-9]"""),
        "길상태" to Regex("""길이 ?(막히|막혔|뚫|좁아|미끄|갈라|볼록|넓어)|[는을] 길이(야|지|네|에요)|도로[가를에]|구간"""),
        "교통어" to Regex("""목적지|경유지|우회전|좌회전|정체|서행|감속|전방|통행|톨게이트|차선|신호등"""),
        "보고체" to Regex("""지나고 있어|입니다|합니다|하세요|보세요|주세요"""),
        "운전지시" to Regex("""천천히 가자|천천히 가야|조심조심 가|조심해서 가"""),
        // ⛔ **소리에 '요'를 붙이면 말이 아니다.** 해요체로 옮길 때 기계가 의성어까지 바꿨다 —
        //    "바람이 휘잉요.", "{탈것} 소리만 웅웅요." 열두 화에 서른세 곳이었다(2026-11-05).
        //    앞 판에서 스물한 곳을 **글자로 적어** 고쳤는데, 적은 목록은 다음 것을 못 잡는다.
        //    그래서 **되풀이 꼴**(토독토독요)과 소리 낱말을 체로 본다. 조사만 남은 끝도 같이 본다.
        "소리에 요" to Regex("""([가-힣]{1,2})\1요|(휘잉|와르르|납작|활짝|깜빡|콕|꺼억|후욱|부웅|폴짝|바스락|통통|덜컹|출렁|쏙|훅|휙)요"""),
        "조사로 끝남" to Regex("""(처럼|으로|한테로|고서)요"""),
    )

    /** 기울기를 **일으킨 것**. 이 중 하나가 주어로 서야 사건이 된다. */
    private val CAUSE = Regex("""파도|물결|바람|너울|물살""")

    /** 방향을 가리키는 꼴. 자리표시자 판과 읽힌 판을 **둘 다** 본다. */
    private val SIDEWAYS = Regex("""\{쪽}|(왼|오른|반대)쪽으로""")

    private val EURO = Regex("""([가-힣])으로""")

    /**
     * **반말 서술 종결.** 그림책의 서술은 "~요" 로 닫는다(2026-11-05 사용자: "실제 동화처럼").
     *
     * ⚠️ 셋은 반말이 맞다 — 여기서 뺀다.
     *   ⑴ **아이를 부르는 줄**(`{호격}` 이 든 줄): "현차야, 저기 봐."
     *   ⑵ **따옴표 안 등장인물 대사**: 구름은 아이에게 반말로 말한다.
     *   ⑶ **흔들리기 전 안내**([Guest.tiltEarly] 등): 지금 일어나는 일이라 현재형이어야 하고,
     *      그래야 이야기 사이에서 **그 한 줄만 도드라진다**(안전 기능이다).
     */
    private val BANMAL = Regex("""(구나|거야|나 봐|[어아해야지네대래나까니군봐데걸])$""")
    private val QUOTED = Regex(""""[^"]*"|'[^']*'""")

    fun narration(line: String): List<String> {
        if (line.contains("{호격}")) return emptyList()
        // ⛔ `…` 로 자르면 안 된다 — "크는데… 괜찮을까요?" 의 앞 토막만 보고 거짓 지적이 난다.
        return QUOTED.replace(line, " ").split(Regex("[.!?]+")).mapNotNull { frag ->
            val f = frag.trim().trimEnd('"', '\'', ')')
            if (f.count { it in '가'..'힣' } < 3) null
            else if (BANMAL.containsMatchIn(f)) "반말 서술('${f.takeLast(10)}')"
            else null
        }
    }

    /**
     * 이 한 줄이 어긋난 축들. 빈 목록이면 통과다.
     *
     * ⚠️ 자리표시자가 **그대로 든 원고**와 **읽힌 문장**을 같은 체로 봐야 한다.
     *    원고만 보면 `fill` 이 만든 말("크게 한 바퀴으로")을 못 보고,
     *    읽힌 것만 보면 그 주행에 안 나온 줄을 통째로 놓친다.
     */
    fun check(line: String): List<String> = buildList {
        RULES.forEach { (name, rx) -> rx.find(line)?.let { add("$name('${it.value}')") } }
        if (SIDEWAYS.containsMatchIn(line) && !CAUSE.containsMatchIn(line)) {
            add("기울기에 원인이 없다(바람·물결이 주어여야 한다)")
        }
        // 받침이 없거나 ㄹ 받침이면 '으로'가 아니라 '로'다
        EURO.findAll(line).forEach { m ->
            val jong = (m.groupValues[1][0] - '가') % 28
            if (jong == 0 || jong == 8) add("조사('${m.value}' → '${m.groupValues[1]}로')")
        }
    }
}

/** 하네스·시연이 쓰는 아이 이름. **한 곳에서만 바꾼다** — 전사·§4·시연 영상이 다 이걸 쓴다. */
internal const val 시연_아이 = "현차"

class StoryHarnessTest {

    // ──────────────────────────── 깔개 ────────────────────────────

    /** 메모리 저장소. 화 번호와 "그 터널 몇 번째"를 테스트가 직접 정한다. */
    class MemStore : Store {
        private val ints = mutableMapOf<String, Int>()
        private val strs = mutableMapOf<String, String>()
        override fun getInt(key: String, def: Int) = ints[key] ?: def
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, def: String) = strs[key] ?: def
        override fun putString(key: String, value: String) { strs[key] = value }
    }

    class Said(val atMs: Long, val line: String, val scene: SceneKind, val grown: Int)

    class Drive(
        val name: String,
        val script: List<Pair<Long, CarEvent>>,
        val age: Int = 5,
        val season: Season = Season.SEA,
        val episode: Int = 1,
        /** 하늘. 여는 줄의 `{때}` 가 여기서 나온다 — 네 가지를 다 돌려야 한다. */
        val sky: TimeOfDay = TimeOfDay.MORNING,
    )

    /** 화면이 당겨 오는 주기. [MainActivity.POLL_MS] 와 같아야 의미가 있다. */
    private val pollMs = 5_000L

    private fun play(d: Drive, store: MemStore = MemStore()): List<Said> {
        var clock = 1_000L
        val journal = Journal(store)
        repeat(d.episode - 1) { journal.nextEpisode() }
        val director = StoryDirector(
            childName = 시연_아이,
            journal = journal,
            season = d.season,
            level = AgeLevel.of(d.age),
            now = { clock },
            sky = { d.sky },
        )
        val out = mutableListOf<Said>()
        val end = d.script.last().first + 180_000L
        var next = 0
        while (clock <= end) {
            while (next < d.script.size && d.script[next].first <= clock) {
                director.onEvent(d.script[next].second)
                    ?.let { out += Said(clock, it.line, it.scene, director.grown) }
                next += 1
            }
            director.tick()?.let { out += Said(clock, it.line, it.scene, director.grown) }
            clock += pollMs
        }
        return out
    }

    // ──────────────────────────── 대본 ────────────────────────────

    private fun m(min: Int) = min * 60_000L
    private fun bump(min: Int) = m(min) to CarEvent.CautionAhead(CautionPointType.SPEED_BUMP, 80)
    private fun school(min: Int) = m(min) to CarEvent.CautionAhead(CautionPointType.SCHOOL_ZONE, 70)
    private fun wild(min: Int) = m(min) to CarEvent.CautionAhead(CautionPointType.WILDLIFE_CROSSING, 120)
    private fun tunnel(min: Int) = m(min) to CarEvent.Landmark(CarEvent.Landmark.Kind.TUNNEL, 150)
    private fun turn(min: Int, right: Boolean = true, sharp: Boolean = false) =
        m(min) to CarEvent.TurnAhead(
            if (right) CarEvent.TurnAhead.Side.RIGHT else CarEvent.TurnAhead.Side.LEFT,
            sharp, 140, "길",
        )

    /** A — 토요일 나들이 65분. 사건이 골고루 온다. */
    private val outing = listOf(
        0L to CarEvent.RouteStarted("에버랜드", 65 * 60, 54_000),
        m(3) to CarEvent.Place("경기도 성남시 분당구"),
        turn(5, right = true, sharp = true),
        bump(8),
        tunnel(12),
        m(18) to CarEvent.EtaChanged(70 * 60, 48_000),
        turn(22, right = false),
        wild(26),
        turn(30),
        m(34) to CarEvent.Landmark(CarEvent.Landmark.Kind.REST_AREA, 400),
        bump(38),
        m(42) to CarEvent.WaypointArrived,
        m(46) to CarEvent.MotionChanged(false, 0f),
        m(48) to CarEvent.MotionChanged(true, 40f),
        turn(52, right = true, sharp = true),
        school(55),
        bump(58),
        turn(61, right = false),
        m(63) to CarEvent.Place("경기도 용인시 처인구 포곡읍"),
        m(65) to CarEvent.DestinationArrived,
        m(65) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /** B — 평일 등원 12분. 짧아서 곁가지를 버려야 한다. */
    private val school12 = listOf(
        0L to CarEvent.RouteStarted("어린이집", 12 * 60, 4_200),
        m(2) to CarEvent.Place("경기도 용인시 기흥구"),
        bump(3),
        turn(5),
        school(8),
        turn(11, right = false),
        m(12) to CarEvent.DestinationArrived,
        m(12) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /** C — 조용한 고속도로 40분. 사건이 셋뿐이다. */
    private val quiet40 = listOf(
        0L to CarEvent.RouteStarted("할머니 집", 40 * 60, 32_000),
        turn(10, right = false),
        bump(25),
        m(40) to CarEvent.DestinationArrived,
        m(40) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /** D — 사건이 **하나도** 없는 30분. 여기서 입을 다물면 그건 이야기가 아니다. */
    private val silent30 = listOf(
        0L to CarEvent.RouteStarted("바다", 30 * 60, 40_000),
        m(30) to CarEvent.DestinationArrived,
        m(30) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /** E — 시내 20분, 커브가 쏟아진다. 반복이 제일 빨리 들통나는 길. */
    private val city20 = listOf(
        0L to CarEvent.RouteStarted("박물관", 20 * 60, 9_000),
        turn(1), turn(2, right = false), bump(3), turn(4, sharp = true),
        turn(5, right = false), turn(6), bump(7), turn(8, right = false, sharp = true),
        turn(9), turn(10, right = false), bump(11), turn(12),
        school(13), turn(14, right = false), turn(15, sharp = true), bump(16),
        turn(17), turn(18, right = false),
        m(20) to CarEvent.DestinationArrived,
        m(20) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /**
     * H — **비 오는 날 등원 20분.** 2026-11-02 에 실측으로 확인한 차 신호가 오는 길이다
     * (와이퍼·창문·뒷좌석 — SDK_FACTS 7.5.1).
     *
     * 왜 따로 돌리나: 비와 바람은 **길이 주는 사건이 아니라 그날 날씨**다. 그래서 같은 책이
     * 비 오는 날엔 다른 쪽을 읽어야 한다. 이 길이 없으면 RAIN·WIND 쪽은 **한 번도 안 읽히고**
     * 그래도 하네스가 통과한다 — 안 읽히는 쪽은 있으나 없으나 같다.
     */
    private val rainy20 = listOf(
        0L to CarEvent.RouteStarted("어린이집", 20 * 60, 7_000),
        m(1) to CarEvent.Riders(1),
        m(2) to CarEvent.Rain(true),
        bump(4),
        turn(6),
        m(9) to CarEvent.Breeze(true),
        tunnel(13),
        m(16) to CarEvent.Breeze(false),
        m(17) to CarEvent.Rain(false),
        turn(18, right = false),
        m(20) to CarEvent.DestinationArrived,
        m(20) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /** F — 아이가 아는 지명이 섞인 길. 체가 통과시켜야 하는 쪽. */
    private val named = listOf(
        0L to CarEvent.RouteStarted("할머니 집", 35 * 60, 28_000),
        m(4) to CarEvent.Place("경기도 성남시 분당구 탄천"),
        m(9) to CarEvent.Place("경기도 광주시 남한산성"),
        turn(12),
        m(16) to CarEvent.Place("경기도 양평군 두물머리다리"),
        tunnel(20),
        bump(26),
        m(30) to CarEvent.Place("경기도 가평군 북한강"),
        m(35) to CarEvent.DestinationArrived,
        m(35) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    /**
     * G — **전기차 충전 대기 25분.** 기획서 시나리오 C 다.
     *
     * 차는 1분에 멈춰서 끝까지 안 움직인다. 주행 신호가 **하나도 안 온다** —
     * 그래도 책이 끝까지 읽혀야 한다. `MotionChanged(false)` 를 받고 입을 다무는
     * 구현이면 여기서 들통난다. SOC 는 1%씩 올라오므로 쪽을 태우지 않는지도 같이 본다.
     */
    private val charge25 = listOf(
        0L to CarEvent.RouteStarted("충전소", 25 * 60, 1_200),
        m(1) to CarEvent.MotionChanged(false, 0f),
    ) + (2..24).map { min -> m(min) to CarEvent.ChargeLevel(40f + min * 2f) as CarEvent } + listOf(
        m(25) to CarEvent.DestinationArrived,
        m(25) + 20_000L to CarEvent.MotionChanged(false, 0f),
    )

    // ──────────────────────────── 검사 ────────────────────────────

    /** 자리표시자를 뺀 가장 긴 토막. **사람이 읽을 지적 문구**에만 쓴다(판정은 [spoken] 이 한다). */
    private fun frag(text: String): String? = text
        .split(Regex("\\{[^}]*\\}"))
        .maxByOrNull { it.trim().length }
        ?.trim()
        ?.takeIf { it.length >= 8 }

    /**
     * 원고 한 줄이 **읽힌 말 안에 있나.** `null` = 너무 짧아 판정하지 않는다.
     *
     * ⚠️ 전에는 [frag] 로 치환자 사이의 **가장 긴 토막** 하나만 뽑아 비교했다. 그런데 본문이
     *    `{손님}`·`{아이}` 를 제대로 쓰면 토막이 여덟 자가 안 돼 `null` 이 되고, 그러면
     *    "핵심 쪽 안 읽힘" 이 **읽혔는데도** 뜬다(2026-11-02 실측 7건). 여태 안 드러난 건
     *    1화만 손님 이름을 "구름" 으로 **글자로 박아** 둬서 토막이 길었기 때문이다.
     *    그래서 치환자 자리를 **아무 글자 몇 개**로 두고 통째로 맞춰 본다.
     *
     * ⚠️ 줄 끝을 넘어가면 안 된다 — 전사는 줄바꿈으로 이어 붙인 한 덩이라
     *    `.` 로 두면 **서로 다른 두 줄에 걸쳐** 맞는 척한다.
     */
    private fun spoken(all: String, template: String): Boolean? {
        val parts = template.split(Regex("\\{[^}]*\\}")).filter { it.isNotEmpty() }
        // 다섯 자. 여덟 자로 뒀더니 세 살 판("{아이}{호격}, {손님}{가} 안 보여.")이
        // 통째로 판정 불가가 되어 **읽혔는데도 "안 읽힘"** 이 떴다(2026-11-02 8화).
        if (parts.sumOf { it.trim().length } < 5) return null
        return Regex(parts.joinToString("[^\n]{0,8}") { Regex.escape(it) }).containsMatchIn(all)
    }

    /** 그 문장이 **그 길을 봤다고** 말한다 — 길이 없었으면 거짓말이다. */
    private fun observations(d: Drive): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val g = d.season.guest
        listOf(
            g.darkIn to "TUNNEL", g.darkOut to "TUNNEL", g.bump to "BUMP",
            g.school to "SCHOOL", g.wild to "FOREST", g.rail to "RAIL",
            g.fog to "FOG", g.rest to "REST",
        ).forEach { (t, kind) -> out += t to kind }
        Episode.of(d.season, d.episode)?.pages?.forEach { pg ->
            val kind = when (pg.fit) {
                SceneKind.TUNNEL -> "TUNNEL"
                SceneKind.BUMP -> "BUMP"
                SceneKind.SCHOOL -> "SCHOOL"
                SceneKind.FOREST -> "FOREST"
                SceneKind.TURN, SceneKind.TURN_LEFT, SceneKind.TURN_RIGHT -> "TURN"
                else -> null
            } ?: return@forEach
            // ⚠️ **짧은 판도 훑는다.** 전에는 본문만 봐서, 길을 가리키는 짧은 판이
            //    길 없는 주행에서 읽혀도 검사기가 조용했다(2026-11-05).
            // ⛔ 단, 짧은 판이 **대신 읽는 판([BookPage.alt]) 안에 그대로 들어 있으면** 같은 말이다 —
            //    그걸 따로 세면 alt 가 읽힐 때마다 "없던 길을 봤다"가 떠서 거짓 지적이 된다
            //    (11화 굴 쪽이 그랬다: 짧은 판이 alt 의 앞 토막과 글자까지 같다).
            out += pg.text to kind
            pg.short?.takeIf { pg.alt?.contains(it) != true }?.let { out += it to kind }
        }
        return out
    }

    private fun eventKinds(script: List<Pair<Long, CarEvent>>): Set<String> = script.mapNotNull { (_, e) ->
        when (e) {
            is CarEvent.Landmark -> when (e.kind) {
                CarEvent.Landmark.Kind.TUNNEL -> "TUNNEL"
                CarEvent.Landmark.Kind.REST_AREA -> "REST"
                else -> null
            }
            is CarEvent.CautionAhead -> when (e.kind) {
                CautionPointType.SPEED_BUMP -> "BUMP"
                CautionPointType.SCHOOL_ZONE -> "SCHOOL"
                CautionPointType.WILDLIFE_CROSSING -> "FOREST"
                CautionPointType.RAILROAD_CROSSING -> "RAIL"
                CautionPointType.FOG_AREA -> "FOG"
                else -> null
            }
            is CarEvent.TurnAhead -> "TURN"
            else -> null
        }
    }.toSet()

    /** 대본에 넣은 주소의 토막들. 하나라도 전사에 나오면 **동화에 주소가 나온 것**이다. */
    private fun addressWords(script: List<Pair<Long, CarEvent>>): Set<String> = script
        .mapNotNull { (_, e) -> (e as? CarEvent.Place)?.address }
        .flatMap { it.split(' ') }
        .filter { it.isNotBlank() }
        .toSet()

    /**
     * 미리 구울 나레이션 후보 — 주행에서 실제로 나온 **완성된 문장**을 모은다.
     *
     * 원고를 따로 파싱하면 조사 규칙([StoryDirector] 의 `fill`)을 한 번 더 구현하게 되고,
     * 둘이 어긋나면 **글자와 소리가 다른 말을 한다.** 그래서 실제로 읽힌 문장만 쓴다.
     * 아이 이름·지명·남은 시간이 든 줄은 `tools/make_voice.py` 가 걸러 낸다(사람마다 다르다).
     */
    private val voiceLines = linkedSetOf<String>()

    // ──────────────────────── 이야기의 모양 ────────────────────────

    /**
     * **읽힌 한 줄이 원고의 몇 쪽인가.** 못 찾으면 `null` — 안내·질문·메움 줄이다.
     *
     * 한 쪽은 판이 셋이다(본문·세 살 판·길 없을 때 판). 어느 판으로 읽혔든 같은 쪽이다.
     */
    private fun pageAt(ep: Episode, line: String): Int? = ep.pages.indices.firstOrNull { i ->
        val pg = ep.pages[i]
        listOfNotNull(pg.text, pg.short, pg.alt).any { spoken(line, it) == true }
    }

    /** 읽힌 줄의 종류. 전사 머리에 세어 붙인다 — **숫자가 있어야 "동화 같다"를 따질 수 있다.** */
    enum class Kind { 본줄기, 곁가지, 여운, 안내, 질문, 겉돌기, 기타 }

    /**
     * 안내 줄인가 — 흔들림 안내는 **현재형 그대로 두기로 한 안전 기능**이라
     * 이야기 줄과 섞어 세면 안 된다. 원고가 아니라 [StoryDirector] 의 문장 꾸러미로 가린다.
     */
    private fun isWarn(d: Drive, line: String): Boolean {
        val g = d.season.guest
        return (StoryDirector.TILT_SHORT + StoryDirector.TILT_HOLD +
            listOf(g.tiltEarly, g.tiltLate))
            .any { spoken(line, it) == true }
    }

    /**
     * **여운 줄** — 책이 끝난 뒤 채우는 조각인데 [Episode.tells] 라 **오늘의 문제를 말한다**
     * ("구름이 무거워서 창틀에 축 처져 있어요"). 이야기 안이므로 지적하지 않는다.
     */
    private fun isEcho(d: Drive, line: String): Boolean =
        Episode.of(d.season, d.episode)?.tells.orEmpty().any { spoken(line, it) == true }

    /**
     * **겉도는 줄** — 손님의 평상 대사([Guest.idle])다. 오늘 뭘 하러 가는지와 아무 상관이 없다
     * ("구름이 조그맣게 하품을 했어요"). 이게 "옆에서 말 거는 사람" 느낌의 정체다.
     *
     * 시즌 2·3 은 아직 원고가 없어 여기로 떨어지는 게 맞지만,
     * **원고가 있는 화에서 이게 나오면 그 화의 여운 줄이 비어 있다는 뜻이다.**
     */
    private fun isIdle(d: Drive, line: String): Boolean =
        d.season.guest.idle.any { spoken(line, it) == true }

    private fun kindOf(d: Drive, line: String): Kind {
        val ep = Episode.of(d.season, d.episode)
        if (ep != null) pageAt(ep, line)?.let {
            return if (ep.pages[it].core) Kind.본줄기 else Kind.곁가지
        }
        if (isWarn(d, line)) return Kind.안내
        if (isEcho(d, line)) return Kind.여운
        if (isIdle(d, line)) return Kind.겉돌기
        if (line.trimEnd().endsWith("?")) return Kind.질문
        return Kind.기타
    }

    /** 전사 머리에 붙이는 한 줄. `본줄기 12 · 곁가지 5 · 안내 2 · 질문 1 · 메움 0` */
    private fun tally(d: Drive, said: List<Said>): String =
        said.groupingBy { kindOf(d, it.line) }.eachCount()
            .let { c -> Kind.entries.filter { c[it] != null } .joinToString(" · ") { "$it ${c[it]}" } }

    private fun check(d: Drive, said: List<Said>): List<String> {
        val bad = mutableListOf<String>()
        val lines = said.map { it.line }
        voiceLines += lines
        val all = lines.joinToString("\n")
        val had = eventKinds(d.script)

        // ⑴ 자리표시자가 남으면 그대로 읽힌다
        lines.filter { it.contains('{') || it.contains('}') }
            .forEach { bad += "자리표시자 남음: $it" }

        // ⑵ 같은 문장을 두 번 — 아이는 바로 알아챈다
        lines.groupingBy { it }.eachCount().filter { it.value > 1 }
            .forEach { (l, n) -> bad += "중복 ${n}번: $l" }

        // ⑶ 동화에 주소가 나오지 않는다
        addressWords(d.script).forEach { w ->
            if (w.length >= 2 && all.contains(w)) bad += "주소 읽음: '$w'"
        }

        // ⑷ 본 적 없는 걸 봤다고 말하지 않는다
        observations(d).forEach { (t, kind) ->
            if (kind !in had && spoken(all, t) == true) {
                bad += "없던 길을 봤다고 함($kind): ${frag(t) ?: t}"
            }
        }

        // ⑸ 너무 오래 입을 다물면 아이가 책을 놓는다
        val arrive = said.indexOfFirst { it.scene == SceneKind.ARRIVE }.takeIf { it >= 0 } ?: said.size
        var prev = 0L
        var worst = 0L
        var worstAt = ""
        said.take(arrive).forEach {
            if (it.atMs - prev > worst) { worst = it.atMs - prev; worstAt = it.line }
            prev = it.atMs
        }
        // 기준은 그 나이에 허용한 최대 간격([AgeLevel.maxGapMs])에 한 박자를 더한 값이다.
        // 본문 없는 화는 조각 여섯 줄로 버텨야 하므로 더 느슨하다 —
        // **남은 격차는 코드가 아니라 원고(그 화의 본문)로 메운다.**
        val lvl = AgeLevel.of(d.age)
        val hush = (if (Episode.of(d.season, d.episode)?.hasBook == true)
            lvl.maxGapMs else StoryDirector.MAX_IDLE_GAP_MS) + 60_000L
        if (worst > hush) bad += "침묵 ${worst / 1000}초 뒤에야: $worstAt"

        // ⑹ 책은 끝까지 읽혀야 한다 — core 쪽이 빠지면 줄거리가 끊긴다
        Episode.of(d.season, d.episode)?.pages?.filter { it.core }?.forEach { pg ->
            val a = spoken(all, pg.text)
            val b = pg.short?.let { spoken(all, it) }
            // ⚠️ **한쪽이라도 판정 불가면 지적하지 않는다.** 세 살은 짧은 판을 읽으므로
            //    긴 본문이 안 보이는 게 정상이다 — 짧은 판을 못 판정하는 상태에서 긴 본문만
            //    보고 "안 읽힘" 을 외치면 거짓 지적이 된다(2026-11-02 8화에서 그랬다).
            //    모르는 것은 틀렸다고 하지 않는다.
            val judged = a != null && (pg.short == null || b != null)
            if (judged && a != true && b != true) {
                bad += "핵심 쪽 안 읽힘: ${frag(pg.text) ?: pg.text}"
            }
        }

        // ⑺ 말이 "커졌다"고 하면 그림도 커져야 한다
        val grewInText = all.contains("커졌")
        if (grewInText && said.last().grown == 0) bad += "말은 커졌다는데 grown=0 (그림이 안 자란다)"

        // ⑻ 동화는 퀴즈가 아니다
        val q = lines.count { it.trimEnd().endsWith("?") }
        if (lines.isNotEmpty() && q * 100 / lines.size > 40) bad += "질문 ${q}/${lines.size}"

        // ⑼ 흔들림 보고가 많으면 알림이지 동화가 아니다
        val tilt = lines.count { it.contains("기울") || it.contains("흔들려") }
        if (lines.isNotEmpty() && tilt * 100 / lines.size > 25) bad += "흔들림 ${tilt}/${lines.size}"

        // ⑾ 내비 말투 — 세 번 지적받은 축이라 사람 눈 대신 체가 본다([Tone])
        lines.forEach { l -> Tone.check(l).forEach { bad += "내비 말투: $it — $l" } }

        // ⑿ **인과가 뒤집히면 동화가 아니다.** 곁가지를 당겨 읽을 때 아직 안 읽은 본줄기를
        //    넘어가면, 아이는 구름이 왜 시무룩했는지도 모르는 채 "그제야 웃었다"를 듣는다.
        //    2026-11-05 전사에서 실제로 그랬다 — 방지턱 쪽(13쪽)이 문제 쪽(4쪽)보다 먼저.
        //    **이게 "아직 길안내 같다"는 지적의 정체였다.** 눈으로는 안 잡힌다. 쪽 번호로 잡는다.
        Episode.of(d.season, d.episode)?.takeIf { it.hasBook }?.let { ep ->
            val order = said.mapNotNull { pageAt(ep, it.line) }
            val appears = order.toSet()
            val seen = mutableSetOf<Int>()
            order.forEach { i ->
                (0 until i).filter { ep.pages[it].core && it in appears && it !in seen }
                    .forEach { bad += "순서 뒤집힘: ${i + 1}쪽이 ${it + 1}쪽보다 먼저" }
                seen += i
            }
            // 본줄기끼리는 더 엄하다 — 줄거리는 한 번도 거꾸로 가지 않는다
            val cores = order.filter { ep.pages[it].core }
            cores.zipWithNext().filter { (a, b) -> b < a }
                .forEach { (a, b) -> bad += "본줄기 역순: ${a + 1}쪽 뒤에 ${b + 1}쪽" }
        }

        // ⒀ **메움 줄은 이야기가 아니다.** 책이 일찍 끝나면 손님의 평상 대사로 때우는데,
        //    그건 오늘의 문제와 아무 상관이 없다("구름이 하품을 했어요"). 옆에서 말을 거는 사람이지
        //    동화가 아니다 — 세 살 전사에서 스무 줄 중 일곱 줄이 그랬다(2026-11-05).
        //    ⚠️ **메움 자체가 나쁜 게 아니다.** 2~12화의 여운 줄([Episode.tells])은 오늘의 문제를
        //    말하므로 이야기 안이다. 나쁜 건 **손님의 평상 대사**([Guest.idle]) — 어느 화에서도
        //    같은 말이라 오늘 뭘 하러 가는지와 끊겨 있다. 1화가 여운 줄이 비어 거기로 떨어졌다.
        if (Episode.of(d.season, d.episode)?.hasBook == true) {
            lines.filter { isIdle(d, it) }
                .forEach { bad += "오늘 이야기와 무관한 줄: $it" }
        }

        // ⒁ **묻는 말은 할 일을 준 뒤에만.** 전에는 정차 질문이 과제 쪽보다 먼저 나갔다 —
        //    아이는 왜 세어야 하는지 모르는 채 "반짝이는 게 몇 개나 보여?" 를 들었다
        //    (2026-11-05 전사, 세 살 65분: 질문 46분 / 과제 49분).
        Episode.of(d.season, d.episode)?.takeIf { it.hasBook }?.let { ep ->
            val quest = ep.pages.indexOfFirst { it.quest }
            if (quest >= 0) {
                val askedAt = said.indexOfFirst { l -> ep.asks.any { spoken(l.line, it) == true } }
                val questAt = said.indexOfFirst { l ->
                    listOfNotNull(ep.pages[quest].text, ep.pages[quest].short)
                        .any { spoken(l.line, it) == true }
                }
                if (askedAt >= 0 && (questAt < 0 || askedAt < questAt)) {
                    bad += "할 일보다 먼저 물었다: ${said[askedAt].line}"
                }
            }
        }

        // ⑽ 세 살에게 긴 문장은 그 자리에서 놓친다
        if (AgeLevel.of(d.age) == AgeLevel.TODDLER) {
            lines.filter { it.length > 35 }.forEach { bad += "세 살에게 긴 문장(${it.length}자): $it" }
        }
        return bad
    }

    /**
     * **판정기부터 시험한다.** [spoken] 이 조용히 틀리면 위의 검사가 전부 헛돈다 —
     * 2026-11-02 에 실제로 그랬다(치환자 때문에 늘 `null` 이 되어 거짓 지적 7건).
     * 검사기를 고쳤으면 **그 검사기를 검사하는 줄**이 있어야 한다.
     */
    @Test
    fun 판정기가_치환자를_넘어_찾는다() {
        val all = "현차야, 구름이 벌써 와 있어.\n꿀꺽꿀꺽! 구름이 통통해졌어."
        val bad = mutableListOf<String>()
        fun want(template: String, expect: Boolean?, what: String) {
            val got = spoken(all, template)
            if (got != expect) bad += "$what — $expect 를 기대했는데 $got ($template)"
        }
        want("{아이}{호격}, {손님}{가} 벌써 와 있어.", true, "치환자 여러 개")
        want("꿀꺽꿀꺽! {손님}{가} 통통해졌어.", true, "짧은 토막에 치환자")
        want("{손님}{가} 하늘로 폴짝 올라갔어.", false, "안 읽힌 줄")
        want("{손님}{가} 왔어.", null, "너무 짧아 판정 불가")
        want("벌써 와 있어. 꿀꺽꿀꺽!", false, "줄을 넘어가면 안 된다")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }


    // ──────────────────────────── 말투 ────────────────────────────

    /** 손님 원고 전부. **반사(reflection)로 훑는다** — 손으로 적으면 새 칸이 생길 때 조용히 빠진다. */
    private fun guestLines(g: Guest, narrationOnly: Boolean = false): List<String> = buildList {
        Guest::class.java.declaredFields.forEach { f ->
            f.isAccessible = true
            if (narrationOnly && f.name in PRESENT_FIELDS) return@forEach
            when (val v = runCatching { f.get(g) }.getOrNull()) {
                is String -> add(v)
                is List<*> -> v.forEach { x ->
                    when (x) {
                        is String -> add(x)
                        is Hook -> add(x.line)
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    /** 아이에게 거는 칸. 반말이 맞으므로 **서술체 축에서 뺀다.** */
    private fun episodeAsks(ep: Episode): List<String> = buildList {
        add(ep.title); add(ep.task); ep.taskShort?.let { add(it) }
        addAll(ep.looks); addAll(ep.asks)
    }

    /** 들려주는 칸. 여기는 **그림책 서술**이어야 한다. */
    private fun episodeNarration(ep: Episode): List<String> = buildList {
        add(ep.gain); addAll(ep.tells)
        ep.pages.forEach { pg -> add(pg.text); pg.short?.let { add(it) }; pg.alt?.let { add(it) } }
    }

    /** 손님 원고 중 **흔들림 안내만** 현재형이다. 나머지는 들려주는 말이다. */
    private val PRESENT_FIELDS = setOf("tiltEarly", "tiltLate", "name")

    /**
     * **동화에 내비 말투가 없다.** 규칙과 까닭은 [Tone] 에 적어 뒀다.
     *
     * 원고와 **읽힌 말**을 같은 체로 본다. 원고만 보면 `fill` 이 만든 말을 못 보고,
     * 읽힌 것만 보면 그 주행에 안 나온 줄을 통째로 놓친다 — 2026-11-04 에
     * `크게 한 바퀴으로 많이 기울어` 가 그래서 숨어 있었다(시연 대본에 U턴이 없다).
     */
    @Test
    fun 원고에_내비_말투가_없다() {
        val bad = mutableListOf<String>()
        // ⛔ **몇 줄을 봤는지 센다.** 0건은 "성하다"일 수도 있고 "아무것도 안 봤다"일 수도 있다 —
        //    세어 두지 않으면 둘을 가를 수 없고, 그러면 통과가 증거가 아니다.
        var seen = 0
        fun scan(where: String, lines: List<String>) {
            seen += lines.size
            lines.forEach { l -> Tone.check(l).forEach { bad += "[$where] $it — $l" } }
        }

        // 서술체까지 보는 칸과, 내비 말투만 보는 칸을 가른다
        fun scanNarr(where: String, lines: List<String>) {
            seen += lines.size
            lines.forEach { l ->
                (Tone.check(l) + Tone.narration(l)).forEach { bad += "[$where] $it — $l" }
            }
        }
        listOf(Guest.CLOUD, Guest.FIREFLY, Guest.SNAIL).forEach {
            scanNarr("${it.name} 들려주는 말", guestLines(it, narrationOnly = true))
            scan("${it.name} 흔들림 안내", listOf(it.tiltEarly, it.tiltLate))
        }
        Episode.SEA.forEach {
            scanNarr("${it.no}화 본문", episodeNarration(it))
            scan("${it.no}화 묻는 말", episodeAsks(it))
        }
        scan("기울기 돌려쓰기", StoryDirector.TILT_SHORT + StoryDirector.TILT_HOLD)

        // U턴이 섞인 길. 자리표시자가 조사와 어긋나는 건 **읽혀 봐야** 보인다.
        val uturn = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("할머니 집", 30 * 60, 24_000),
            m(4) to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.UTURN, true, 120, "길"),
            m(9) to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.UTURN, false, 120, "길"),
            turn(14, sharp = true),
            turn(19, right = false, sharp = true),
            m(24) to CarEvent.CautionAhead(CautionPointType.SLIPPERY_ROAD_ALERT, 90),
            m(26) to CarEvent.CautionAhead(CautionPointType.NARROW_ROAD, 60),
            m(27) to CarEvent.CautionAhead(CautionPointType.DOWNHILL_CAUTION_AREA, 60),
            m(28) to CarEvent.CautionAhead(CautionPointType.FALLING_ROCK_AREA, 60),
            m(29) to CarEvent.Landmark(CarEvent.Landmark.Kind.MERGE, 200),
            m(30) to CarEvent.DestinationArrived,
        )
        listOf(
            Drive("U턴·1화", uturn),
            Drive("U턴·5화", uturn, episode = 5),
            Drive("U턴·5화·3세", uturn, age = 3, episode = 5),
            Drive("시내20분", city20),
            Drive("나들이65분·12화", outing, episode = 12),
            Drive("시즌2(비행선)", uturn, season = Season.SKY),
            Drive("시즌3(수레)", uturn, season = Season.WOOD),
        ).forEach { d -> scan("${d.name} 읽힌 말", play(d).map { it.line }) }

        File("build").mkdirs()
        File("build/말투.txt").writeText("${seen}줄 훑음 · 내비 말투 ${bad.size}건\n" +
            bad.joinToString("\n").ifBlank { "" })
        println("말투 — ${seen}줄 훑음 · 내비 말투 ${bad.size}건")
        // 원고 1,000줄 남짓 + 읽힌 말. 이 밑으로 떨어지면 훑는 길이 끊긴 것이다.
        assertTrue("${seen}줄만 훑었다 — 훑는 길이 끊겼다", seen > 800)
        assertTrue("내비 말투 ${bad.size}건\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /**
     * **묻는 말이 세 살에게 길지 않다.**
     *
     * 멈추면 묻는 말([Episode.asks])은 `keep` 이라 [StoryDirector.voice] 가 세 살에게도
     * 그대로 보낸다 — 짧은 판이 없다. 그래서 **원고 쪽에서 짧아야** 한다.
     * 하네스로는 안 잡힌다: 세 살 대본 중 **멈추는 길**이 있는 것만 걸리므로
     * 2026-11-04 에 1화 하나만 뜨고 2화는 똑같이 긴데도 통과였다. 그래서 원고를 직접 센다.
     */
    /**
     * **여는 쪽은 본줄기다.** 길이 책을 당겨 읽을 때 넘지 못하는 벽이 `core` 라서,
     * 여는 쪽이 곁가지면 커브 하나에 소개보다 사건이 먼저 와 이야기가 거꾸로 선다.
     * 전에는 [StoryDirector.fitPage] 가 "세 쪽 전엔 당기지 말기"로 따로 막았는데,
     * 한 가지 일에 규칙이 둘이면 새 화를 쓸 때 한쪽만 지킨다. **원고 쪽에서 지킨다.**
     */
    @Test
    fun 여는_세_쪽은_본줄기다() {
        val bad = mutableListOf<String>()
        Season.ALL.forEach { s ->
            (1..Season.EPISODES).forEach { no ->
                val ep = Episode.of(s, no)?.takeIf { it.hasBook } ?: return@forEach
                ep.pages.take(StoryDirector.OPENING_PAGES).forEachIndexed { i, pg ->
                    if (!pg.core) bad += "${s.id} ${no}화 ${i + 1}쪽이 곁가지다: ${frag(pg.text)}"
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * **할 일 쪽은 화마다 하나다.** 둘이면 정차 질문이 어느 쪽을 기다릴지 모르고,
     * 없으면 그 화는 정차에서 **영영 묻지 않는다**(묻는 말이 조용히 사라진다).
     */
    @Test
    fun 할_일_쪽이_화마다_하나다() {
        val bad = mutableListOf<String>()
        Season.ALL.forEach { season ->
            (1..Season.EPISODES).forEach { no ->
                val ep = Episode.of(season, no)?.takeIf { it.hasBook } ?: return@forEach
                val q = ep.pages.count { it.quest }
                if (q != 1) bad += "${season.id} ${no}화 할 일 쪽 ${q}개"
                // 할 일 쪽이 곁가지면 짧은 주행에서 빠지고, 그러면 그 주행은 묻지 않는다
                ep.pages.filter { it.quest && !it.core }
                    .forEach { bad += "${season.id} ${no}화 할 일 쪽이 곁가지다: ${frag(it.text)}" }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * **손님 크기는 줄거리와 같이 간다.** 화면의 구름이 실제로 그 크기다([SceneView.growth]).
     *
     * 시즌 1 은 8화에서 구름이 비가 되어 쏟아진다 — 그래서 **한 번은 거꾸로 가도 된다.**
     * 두 번 거꾸로 가면 그건 줄거리가 아니라 실수고, 그 화의 원고가 "작아졌다"고 말하지 않으면
     * 글과 그림이 다른 말을 하는 것이다(2026-11-05: 8화 글은 "손톱만 한 구름", 화면은 거의 다 큰 구름).
     */
    @Test
    fun 손님_크기가_줄거리를_따른다() {
        val bad = mutableListOf<String>()
        val shrink = listOf("작아", "손톱만", "비가 되어", "쏟아져", "쏟아졌")
        Season.ALL.forEach { season ->
            val eps = (1..Season.EPISODES).mapNotNull { Episode.of(season, it)?.takeIf { e -> e.hasBook } }
            if (eps.isEmpty()) return@forEach
            eps.forEach { ep ->
                if (ep.startGrown !in 0..StoryDirector.MAX_GROWN) {
                    bad += "${season.id} ${ep.no}화 크기 ${ep.startGrown} (0~${StoryDirector.MAX_GROWN})"
                }
            }
            val drops = eps.zipWithNext().filter { (a, b) -> b.startGrown < a.startGrown }
            if (drops.size > 1) bad += "${season.id} 크기가 ${drops.size}번 거꾸로 간다"
            drops.forEach { (_, b) ->
                val said = (b.pages.map { it.text } + b.pages.mapNotNull { it.short }).joinToString(" ")
                if (shrink.none { said.contains(it) }) {
                    bad += "${season.id} ${b.no}화 크기가 줄었는데 원고는 작아졌다고 안 한다"
                }
            }
            // 마지막 화는 다 커야 떠오른다
            val last = eps.last()
            val end = last.startGrown + last.pages.count { it.grow }
            if (end < StoryDirector.MAX_GROWN) bad += "${season.id} ${last.no}화 끝 크기 $end (다 커야 떠오른다)"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun 묻는_말이_세_살에게_길지_않다() {
        val bad = mutableListOf<String>()
        Episode.SEA.forEach { ep ->
            // 실제로 쓰이는 건 **첫 번째**뿐이다([StoryDirector] 가 `ask(0)` 만 부른다).
            val q = ep.ask(0) ?: return@forEach
            val said = q.replace("{아이}", 시연_아이)
                // ⛔ 호격을 손으로 박지 않는다 — 이름이 모음으로 끝나면 "야" 다(2026-11-07 이름을 바꾸며 드러났다)
                .replace("{호격}", vocative(시연_아이))
                .replace("{탈것}", "배").replace(Regex("\\{[^}]*}"), "")
            if (said.length > StoryDirector.TODDLER_MAX_CHARS) {
                bad += "${ep.no}화 묻는 말 ${said.length}자: $said"
            }
        }
        assertTrue("세 살에게 긴 질문 ${bad.size}건\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /**
     * **검사기부터 시험한다.** 말투 체가 조용히 아무것도 안 잡으면 위의 검사는 통과만 찍는다.
     *
     * 그래서 ⑴ 실제로 있었던 내비 말투를 넣어 **잡는지** 보고,
     * ⑵ 지금 원고의 성한 문장을 넣어 **거짓 지적이 없는지** 본다. 둘 다 없으면 증거가 아니다.
     */
    @Test
    fun 말투_검사기가_내비_말투를_잡는다() {
        val bad = mutableListOf<String>()

        // ⑴ 실제로 앱에 있었던 줄들. 축 이름까지 맞아야 한다.
        listOf(
            "{아이}{호격}, {곳}까지 {분}분이야. 창밖 위를 봐." to "숫자",
            "300미터 앞에서 꺾어." to "숫자",
            "길이 막혔어. 달팽이가 걱정해." to "길상태",
            "길이 좁아져. 구름이 몸을 쏙 집어넣었어." to "길상태",
            "기차가 지나가는 길이야." to "길상태",
            "목적지를 설정하면 이야기가 시작돼요." to "교통어",
            "지금 다리를 지나고 있어." to "보고체",
            "길이 미끄러워. 천천히 가자." to "운전지시",
            "{탈것}{가} {쪽}으로 {세기} 기울어 — 꼭 잡아!" to "기울기",
            "배가 오른쪽으로 많이 기울어." to "기울기",
            "크게 한 바퀴으로 많이 기울어." to "조사",
            "구름이 하늘으로 올라갔어." to "조사",
        ).forEach { (line, axis) ->
            val hits = Tone.check(line)
            if (hits.none { it.startsWith(axis) }) bad += "안 잡힌다[$axis]: $line → $hits"
        }

        // ⑵ 반례 — 성한 문장을 걸고 넘어지면 원고를 망친다
        listOf(
            "작은 물결이 배를 왼쪽으로 밀어 — 구름이 안 떨어지게, 창밖 멀리 있는 걸 붙잡아 줘!",
            "{아이}{호격}, {곳}에 가는 {때}{이}야. 창밖 위를 봐 — 조그만 구름 한 조각이 따라와.",
            "달팽이가 지나간 자리에 반짝이는 길이 하나 생겼어.",
            "{손님}{가} 창밖을 둘러보며 말했어. \"오늘 길도 기억할래. 물을 준 길이야.\"",
            "물을 못 먹으면 더 작아진대. 어제 커진 만큼이 도로 줄어들지도 모른대.",
            "바람이 멎었어. 구름이 천천히 가재. 그럼 가는 길에 하나 더 들렀다 갈까?",
            "스으, 스으. 아주 조그만 숨소리야. 조심조심, 우리처럼.",
            "나무가 줄줄이 선 길이야. 그늘이 창문을 지나갈 때마다 그림자가 세어.",
            "반대쪽으로 바람이 밀어! 구름 꼭 잡아.",
        ).forEach { l -> Tone.check(l).forEach { bad += "거짓 지적: $it — $l" } }

        assertTrue("말투 검사기 ${bad.size}건\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    // ──────────────────────────── 돌린다 ────────────────────────────

    @Test
    fun 전수_주행() {
        val drives = buildList {
            listOf(3, 5, 8).forEach { age ->
                add(Drive("A·나들이65분·${age}세", outing, age))
                add(Drive("B·등원12분·${age}세", school12, age))
                add(Drive("C·조용40분·${age}세", quiet40, age))
                add(Drive("D·무사건30분·${age}세", silent30, age))
                add(Drive("E·시내20분·${age}세", city20, age))
                add(Drive("F·지명길35분·${age}세", named, age))
                add(Drive("H·비오는날20분·${age}세", rainy20, age))
            }
            // 본문 없는 화(2~11) — 조각 열세 줄로 돈다. **11화를 꼭 넣는다**(마지막 조각 화)
            listOf(2, 3, 4, 5, 8, 11).forEach { ep ->
                add(Drive("A·${ep}화", outing, episode = ep))
                add(Drive("C·${ep}화·조용", quiet40, episode = ep))
                // ⚠️ 세 살은 긴 조각을 **건너뛴다**. 짧은 줄이 모자라면 세 살만 조용해지는데,
                //    전에는 나이 훑기가 1화(책)에만 걸려 있어 이 구멍이 안 보였다.
                add(Drive("C·${ep}화·조용·3세", quiet40, age = 3, episode = ep))
                add(Drive("D·${ep}화·무사건·3세", silent30, age = 3, episode = ep))
                // ⭐ 비·바람 쪽은 **이 주행에서만** 읽힌다. 없으면 그 쪽은 있으나 없으나 같다.
                add(Drive("H·비·${ep}화", rainy20, episode = ep))
            }
            // 12화 — 두 번째 본문. 작별이 도착에서 제대로 읽히는지.
            listOf(3, 5, 8).forEach { age ->
                add(Drive("A·12화·작별·${age}세", outing, age, episode = 12))
            }
            add(Drive("C·12화·조용", quiet40, episode = 12))
            // 하늘 네 가지 — 여는 줄이 "{곳}에 가는 {때}이야" 로 읽힌다
            listOf(TimeOfDay.DAY, TimeOfDay.DUSK, TimeOfDay.NIGHT).forEach { sky ->
                add(Drive("B·등원·${sky}", school12, sky = sky))
            }
            // G — 충전 대기. 1화·12화(책)와 5화(조각) 셋 다 본다.
            add(Drive("G·충전25분·1화", charge25))
            add(Drive("G·충전25분·12화", charge25, episode = 12))
            add(Drive("G·충전25분·5화", charge25, episode = 5))
            add(Drive("G·충전25분·3세", charge25, age = 3))
            // 본문도 과제도 없는 시즌
            add(Drive("A·시즌2(밤·반딧불이)", outing, season = Season.SKY))
            add(Drive("C·시즌2·조용", quiet40, season = Season.SKY))
            add(Drive("A·시즌3(숲·달팽이)", outing, season = Season.WOOD))
            add(Drive("C·시즌3·조용", quiet40, season = Season.WOOD))
        }

        val sb = StringBuilder()
        val problems = linkedMapOf<String, List<String>>()
        drives.forEach { d ->
            val said = play(d)
            problems[d.name] = check(d, said)
            sb.appendLine("━".repeat(78))
            sb.appendLine("${d.name}  (${said.size}줄 · ${d.season.id} · ${d.episode}화)")
            sb.appendLine("  ${tally(d, said)}")
            sb.appendLine("━".repeat(78))
            said.forEach {
                sb.appendLine("%5d분 [%-10s g%d] %s".format(it.atMs / 60_000, it.scene, it.grown, it.line))
            }
            problems[d.name]!!.forEach { sb.appendLine("   ⛔ $it") }
            sb.appendLine()
        }

        // 같은 director 로 두 번 달린다 — 지난 주행 상태가 새 주행에 새는지
        sb.appendLine("━".repeat(78))
        sb.appendLine("연속 두 주행(상태 누수)")
        sb.appendLine("━".repeat(78))
        run {
            var clock = 1_000L
            val director = StoryDirector(시연_아이, Journal(MemStore()), Season.SEA, AgeLevel.KID, now = { clock })
            val leak = mutableListOf<String>()
            listOf(school12, school12).forEachIndexed { i, script ->
                sb.appendLine("-- ${i + 1}번째 --")
                val base = clock
                var next = 0
                while (clock <= base + script.last().first + 120_000L) {
                    while (next < script.size && base + script[next].first <= clock) {
                        director.onEvent(script[next].second)?.let {
                            sb.appendLine("%5d분 [%-10s] %s".format((clock - base) / 60_000, it.scene, it.line))
                            if (i == 1) leak += it.line
                        }
                        next += 1
                    }
                    director.tick()?.let {
                        sb.appendLine("%5d분 [%-10s] %s".format((clock - base) / 60_000, it.scene, it.line))
                        if (i == 1) leak += it.line
                    }
                    clock += pollMs
                }
            }
            val p = mutableListOf<String>()
            if (leak.isEmpty()) p += "두 번째 주행에서 아무 말도 안 한다"
            val leakAll = leak.joinToString("\n")
            Episode.SEA[0].pages.filter { it.core }.forEach { pg ->
                val a = spoken(leakAll, pg.text)
                val b = pg.short?.let { spoken(leakAll, it) }
                if (a != null && (pg.short == null || b != null) && a != true && b != true) {
                    p += "2번째 주행에 핵심 쪽 없음: ${frag(pg.text) ?: pg.text}"
                }
            }
            problems["연속 두 주행"] = p
            p.forEach { sb.appendLine("   ⛔ $it") }
        }

        File("build").mkdirs()
        File("build/전사.txt").writeText(sb.toString())
        File("build/대사.txt").writeText(voiceLines.joinToString("\n"))

        val total = problems.values.sumOf { it.size }
        val summary = StringBuilder("문제 ${total}건\n")
        problems.filter { it.value.isNotEmpty() }.forEach { (k, v) ->
            summary.appendLine("[$k]")
            v.forEach { summary.appendLine("  - $it") }
        }
        File("build/문제.txt").writeText(summary.toString())
        println(summary)
    }

    /**
     * **조각 대사는 손님만 가리킨다.**
     *
     * 차 신호와 묶여 있지 않은 대사가 창밖을 단정하면, 그 길에 그게 없을 때 그대로 거짓이 된다 —
     * 아이가 창밖을 보고 "없는데?" 하는 순간 이야기가 끝난다.
     * (전사에 안 잡힌다. 그 길이 **우연히 있었던** 주행만 돌려 보면 통과하므로 **원고를 본다.**)
     */
    @Test
    fun 조각은_창밖을_단정하지_않는다() {
        val outward = listOf(
            "저 아래", "저 멀리", "저 위에서", "창밖에서", "지나갔", "지나가던",
            "햇빛이 들", "개울", "나무 끝", "강이", "산이", "비가 와",
        )
        val bad = mutableListOf<String>()
        Episode.SEA.forEach { ep ->
            ep.tells.forEach { t ->
                outward.filter { t.contains(it) }.forEach { bad += "${ep.no}화 조각: '$it' — $t" }
            }
        }
        listOf(Guest.CLOUD, Guest.FIREFLY, Guest.SNAIL).forEach { g ->
            g.idle.forEach { t ->
                outward.filter { t.contains(it) }.forEach { bad += "${g.name} 평상: '$it' — $t" }
            }
            // 떡밥 중 [Hook.needs] 가 없는 것은 **아무 길에서나** 나간다 — 그것도 단정하면 안 된다.
            g.hooks.filter { it.needs == null }.forEach { h ->
                outward.filter { h.line.contains(it) }.forEach { bad += "${g.name} 떡밥: '$it' — ${h.line}" }
            }
        }
        // 여운 줄이 모자라면 긴 주행의 뒤가 **다른 화의 말**로 메워진다
        // ⛔ 전에는 "본문이 있는 화는 조각을 안 쓴다 — 책이 1쪽부터 읽힌다"고 적고 건너뛰었다.
        //    틀렸다. 세 살은 짧은 판 없는 쪽을 건너뛰므로 책이 절반에서 끝나고,
        //    어느 화든 도착 전 몇 분은 조각이 메운다(2026-11-05 전사: 1화 세 살 7/20줄).
        //    그러니 **본문이 있는 화도 여운 줄이 있어야** 한다.
        Episode.SEA.forEach { ep ->
            if (ep.tells.size < 6) bad += "${ep.no}화 여운 줄 ${ep.tells.size}줄 (여섯 줄 필요)"
            // 세 살은 긴 줄을 건너뛴다 — 짧은 줄이 모자라면 세 살만 조용해진다
            val short = ep.tells.count { it.length <= StoryDirector.TODDLER_MAX_CHARS }
            if (ep.tells.isNotEmpty() && short < 4) bad += "${ep.no}화 짧은 여운 ${short}줄 (넷 필요)"
        }
        File("build/원고.txt").writeText(if (bad.isEmpty()) "원고 이상 없음\n" else bad.joinToString("\n"))
        println(bad.joinToString("\n").ifBlank { "원고 이상 없음" })
    }

    /**
     * **길이 없으면 한 마디도 하지 않는다.**
     *
     * 왜 이걸 못 박아 두나: 2026-11-01 부터 차 전원을 켜면 이야기 서비스가 자동으로 뜬다
     * ([BootReceiver]). 목적지를 넣지 않은 주행에서 조각 대사가 한 줄이라도 새면
     * **아이가 타지도 않은 차가 혼자 말한다.** 그건 기능이 아니라 사고다.
     *
     * 뒤에 목적지를 넣고 다시 흘려서 **엔진이 죽은 게 아니라는 것**도 같이 본다 —
     * 그걸 안 보면 "아무 말도 안 한다" 를 통과로 착각한다(공허검사).
     */
    @Test
    fun 길이_없으면_말하지_않는다() {
        var clock = 1_000L
        val director = StoryDirector(시연_아이, Journal(MemStore()), Season.SEA, AgeLevel.KID, now = { clock })

        // ① 목적지 없이 20분. 차가 알려 줄 수 있는 것을 다 흘려 보낸다.
        val noRoute = listOf<Pair<Long, CarEvent>>(
            m(1) to CarEvent.MotionChanged(true, 42f),
            m(2) to CarEvent.Place("경기도 성남시 분당구"),
            turn(3, sharp = true),
            bump(5),
            tunnel(7),
            school(9),
            m(10) to CarEvent.Braking(true),
            m(11) to CarEvent.ChargeLevel(61f),
            m(12) to CarEvent.MotionChanged(false, 0f),
            m(13) to CarEvent.ChargeLevel(63f),
            m(14) to CarEvent.MotionChanged(true, 30f),
            turn(16, right = false),
            m(18) to CarEvent.EtaChanged(10 * 60, 4_000),
            m(19) to CarEvent.WaypointArrived,
            m(20) to CarEvent.RestrictionChanged(true),
        )
        val spoke = mutableListOf<String>()
        var next = 0
        while (clock <= m(22)) {
            while (next < noRoute.size && noRoute[next].first <= clock) {
                director.onEvent(noRoute[next].second)?.let { spoke += "${clock / 60_000}분 ${it.line}" }
                next += 1
            }
            director.tick()?.let { spoke += "${clock / 60_000}분 ${it.line}" }
            clock += pollMs
        }

        // ② 목적지를 넣는다. 여기서는 말해야 한다.
        val after = mutableListOf<String>()
        val base = clock
        val routed = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("어린이집", 12 * 60, 4_200),
        ) + school12.drop(1)
        next = 0
        while (clock <= base + m(13)) {
            while (next < routed.size && base + routed[next].first <= clock) {
                director.onEvent(routed[next].second)?.let { after += it.line }
                next += 1
            }
            director.tick()?.let { after += it.line }
            clock += pollMs
        }

        val bad = mutableListOf<String>()
        spoke.forEach { bad += "길도 없는데 말함: $it" }
        if (after.isEmpty()) bad += "목적지를 넣어도 말하지 않는다 — 검사가 헛돌고 있다"

        File("build").mkdirs()
        File("build/길없음.txt").writeText(
            buildString {
                appendLine("① 목적지 없이 20분 — ${spoke.size}줄 (0줄이어야 한다)")
                spoke.forEach { appendLine("   $it") }
                appendLine("② 목적지를 넣은 뒤 12분 — ${after.size}줄 (한 줄 이상이어야 한다)")
                after.forEach { appendLine("   $it") }
                bad.forEach { appendLine("   ⛔ $it") }
            },
        )
        println(
            if (bad.isEmpty()) "길없음 검사 통과 — 조용 ${spoke.size}줄 / 넣은 뒤 ${after.size}줄"
            else bad.joinToString("\n"),
        )
        // ⛔ 이건 판단이 아니라 불변식이다. 전사를 읽어 보는 검사와 달리 **여기서 터져야** 한다.
        if (bad.isNotEmpty()) throw AssertionError(bad.joinToString("\n"))
    }
}

/**
 * **음원 이름이 양쪽에서 같은가.**
 *
 * 소리는 `tools/make_voice.py` 가 굽고 앱은 [VoicePack.keyOf] 로 찾는다. 규칙이 어긋나면
 * 앱은 **조용히 TTS 로 내려가고**(또는 아무 소리도 안 내고) 아무도 눈치채지 못한다 —
 * 에뮬레이터엔 TTS 엔진이 없어 그냥 조용해질 뿐이다. 그래서 기계가 대조한다.
 *
 * 비교 대상은 `assets/voice/index.txt`(구운 목록)와 `build/대사.txt`(실제로 읽힌 문장)다.
 * 둘 중 하나라도 없으면 **검사를 건너뛴 척하지 않고** 그 사실을 적는다.
 */
class VoiceNameTest {

    private val index = File("src/main/assets/voice/index.txt")
    private val lines = File("build/대사.txt")

    @Test
    fun 음원_이름이_양쪽에서_같다() {
        if (!index.exists() || !lines.exists()) {
            println("⚠️ 음원 대조 건너뜀 — ${if (!index.exists()) index else lines} 없음 " +
                "(tools/make_voice.py 를 아직 안 돌렸다)")
            return
        }
        val baked = index.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertTrue("구운 음원이 없다", baked.isNotEmpty())

        // 굽힌 적 없는 이름이 섞여 있으면 **앱이 못 찾는다**(파이썬 쪽 규칙이 다른 것이다).
        val said = lines.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val keys = said.associateBy { VoicePack.keyOf(it) }
        val orphan = baked - keys.keys
        val n = baked.count { it in keys }
        println("음원 ${baked.size}개 중 ${n}개가 실제 대사와 이름이 맞는다")
        assertTrue("대사와 이름이 하나도 안 맞는다 — 해시 규칙이 어긋났다", n > 0)
        assertTrue("대사에 없는 음원 ${orphan.size}개 — 원고가 바뀌었는데 소리는 그대로다 " +
            "(tools/make_voice.py 를 다시 돌릴 것)", orphan.isEmpty())

        // ⛔ **반대쪽도 본다 — 소리가 없는 줄.** 묵은 음원만 보고 있었더니, 원고를 고친 뒤
        //    새 줄 몇 개가 **소리 없이** 지나갔다(2026-11-05). 에뮬레이터엔 TTS 가 없어
        //    그 줄은 그냥 조용하다 — 시연 영상에서야 드러난다.
        //    굽지 않기로 한 줄(이름·지명이 든 줄)은 파이썬이 적어 준 목록으로 가른다.
        val skipped = File("build/음원제외.txt")
        if (!skipped.exists()) {
            println("⚠️ 음원제외.txt 없음 — tools/make_voice.py 를 다시 돌릴 것")
            return
        }
        val skip = skipped.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val silent = said.filter { it !in skip && VoicePack.keyOf(it) !in baked }
        assertTrue("소리가 없는 줄 ${silent.size}개 — ${silent.take(3)}", silent.isEmpty())
    }
}

/**
 * **깜빡이가 기울기가 되는가.**
 *
 * 내비는 180m 전에 "곧 꺾어요" 라고 예고하고([CarEventHub.TURN_NOTICE_METERS]),
 * 깜빡이는 그 커브가 **실제로 오는 순간**을 알려 준다. 그때 화면의 배가 기운다 —
 * 예고와 실제를 둘 다 가진 건 차 안에서만 되는 일이다.
 *
 * 왜 기계가 보나: 기울기는 **말을 만들지 않는다.** 숫자가 틀려도 대사가 어긋나지 않으니
 * 하네스의 다른 축에는 안 걸리고, 화면을 줄여 보면 사람 눈에도 안 보인다
 * (2026-11-04 에 그래서 배 자리를 확대해서야 확인했다).
 */
class TurningLeanTest {

    private fun lean(side: CarEvent.Turning.Side?, deg: Float) =
        CarEvent.Turning(side, deg).lean

    @Test
    fun 깜빡이가_기울기가_된다() {
        val L = CarEvent.Turning.Side.LEFT
        val R = CarEvent.Turning.Side.RIGHT

        // 방향 — 왼쪽은 음수, 오른쪽은 양수. 뒤집히면 배가 **반대로** 기운다.
        assertTrue("오른쪽인데 양수가 아니다", lean(R, 330f) > 0f)
        assertTrue("왼쪽인데 음수가 아니다", lean(L, 330f) < 0f)
        assertEquals("좌우가 대칭이 아니다", lean(R, 330f), -lean(L, 330f), 1e-4f)

        // 다 꺾었으면 바로 선다
        assertEquals("끝났는데 기울어 있다", 0f, lean(null, 0f), 1e-4f)
        assertEquals("끝났는데 각도가 남아 기울어 있다", 0f, lean(null, 330f), 1e-4f)

        // 세기 — 크게 꺾은 커브가 더 기운다
        assertTrue("330도가 120도보다 안 기운다", lean(R, 330f) > lean(R, 120f))

        // ⚠️ 바닥 — 각도가 **안 오는 차**에서도 깜빡이만으로 기울어야 한다.
        //    이게 없으면 그런 차에서 기능이 통째로 조용히 사라진다.
        assertEquals("각도 0 인데 안 기운다", CarEvent.Turning.FLOOR, lean(R, 0f), 1e-4f)

        // ⚠️ 천장 — 실측 범위는 -470~500 이라 STEER_FULL(360)을 넘는 값이 실제로 온다.
        //    막아 두지 않으면 배가 한 바퀴 돈다.
        assertEquals("끝까지 꺾었는데 1 을 넘는다", 1f, lean(R, 500f), 1e-4f)
        assertEquals("범위 밖에서도 1 을 넘지 않는다", 1f, lean(R, 9_999f), 1e-4f)
    }
}
