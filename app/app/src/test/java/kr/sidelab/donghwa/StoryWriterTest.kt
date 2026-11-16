package kr.sidelab.donghwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * **생성층 검사 — 에뮬레이터 없이.**
 *
 * ## 왜 이 파일이 있나
 * 접수 서류 5항에 "2차 PT 전까지 LLM SDK 생성을 연결한다" 라고 적었다. 그런데 이 기능은
 * **에뮬레이터에서 한 줄도 돌려 볼 수 없다** — 온디바이스 추론이 플랫폼 쪽에서 꺼져 있고
 * `onError` 조차 오지 않는다(SDK_FACTS 7.10). 돌려 볼 수 없는 코드는 **안 되는 코드**다.
 *
 * 그래서 [LlmWriter] 는 SDK 를 [LlmWriter.Backend] 뒤에 숨기고 스레드를 [Executor] 로 받는다.
 * 여기서는 그 둘을 가짜로 끼워 **생성이 되는 차 / 안 되는 차 / 이상한 문장을 주는 차**를
 * 전부 만들어 본다. 양산기에서 처음 켜질 때 처음 도는 코드가 되지 않게 하려는 것이다.
 *
 * ## 이 검사가 지키는 약속
 * 1. 생성이 붙어도 **책의 순서와 쪽 수가 안 바뀐다**(연출은 규칙이 쥔다).
 * 2. 생성이 안 되는 차에서도 **앱은 똑같이 돌아간다**(내장 이야기).
 * 3. 이상한 문장은 **아이에게 가기 전에 막힌다**.
 * 4. 멀미 선행 안내는 **생성에 맡기지 않는다**(안전 기능).
 */
class StoryWriterTest {

    /** 그 자리에서 바로 돌리는 실행기. 시험이 비동기를 기다리지 않게 한다. */
    private val 즉시 = Executor { it.run() }

    /**
     * 가짜 SDK. [answer] 가 null 을 주면 "조용히 무응답" — 에뮬레이터가 딱 그렇다.
     * @param answer 생성 지시문을 받아 문장을 돌려준다.
     */
    private class FakeBackend(private val answer: (String) -> String?) : LlmWriter.Backend {
        val calls = AtomicInteger(0)
        var closed = false
            private set

        override fun generate(
            system: String,
            user: String,
            timeoutMs: Long,
            onDone: (String?) -> Unit,
        ) {
            calls.incrementAndGet()
            onDone(answer(user))
        }

        override fun close() {
            closed = true
        }
    }

    private fun 생성층(backend: LlmWriter.Backend, level: AgeLevel = AgeLevel.KID) = LlmWriter(
        childName = "현차",
        guestName = "구름",
        vehicle = "배",
        level = level,
        destination = { "에버랜드" },
        minutesLeft = { 65 },
        backend = backend,
        worker = 즉시,
    )

    private fun 요청(text: String, core: Boolean = false) =
        StoryWriter.Request(text, SceneKind.ROAD, core)

    // ──────────────────────────── 어린이 필터 ────────────────────────────

    @Test
    fun 무서운_말은_아이에게_안_간다() {
        val 원문 = "구름이 폴짝 뛰었어요."
        for (나쁜말 in listOf(
            "구름이 죽었어요.",
            "앞에서 사고가 났어요.",
            "칼을 든 괴물이 나왔어요.",
            "위험해요!",
            "브레이크를 밟아요.",
        )) {
            assertNull("막혔어야 한다: $나쁜말", ChildFilter.check(나쁜말, 원문, AgeLevel.KID))
        }
    }

    @Test
    fun 운전하는_어른을_부르지_않는다() {
        // 뒷좌석에서 운전자를 부르면 그게 바로 주의 분산이다.
        val 원문 = "구름이 창밖을 봤어요."
        assertNull(ChildFilter.check("아빠가 봐요, 저거!", 원문, AgeLevel.KID))
        assertNull(ChildFilter.check("핸들을 돌려요.", 원문, AgeLevel.KID))
    }

    @Test
    fun 자리표시자가_남으면_막는다() {
        assertNull(ChildFilter.check("{아이}야, 봐.", "현차야, 봐.", AgeLevel.KID))
    }

    @Test
    fun 모델이_제_설명을_붙이면_막는다() {
        val 원문 = "구름이 웃었어요."
        assertNull(ChildFilter.check("물론이죠! 구름이 웃었어요.", 원문, AgeLevel.KID))
        assertNull(ChildFilter.check("다음은 다시 쓴 문장입니다.", 원문, AgeLevel.KID))
    }

    @Test
    fun 세_살에게_긴_문장은_막는다() {
        // ⚠️ 원문을 짧게 두면 **나이가 아니라 길이 천장**([StoryPrompt.budget])에 먼저 걸려서
        //    이 검사가 나이 규칙을 보는 게 아니게 된다(2026-11-16에 실제로 그랬다).
        //    그래서 원문을 넉넉히 두고 **나이만 다르게** 본다.
        val 원문 = "구름이 통통 튀어 오르더니 깔깔 웃었어요. 창밖이 환해졌어요."        // 34자
        val 긴줄 = "구름이 통통 튀어 오르더니 깔깔 웃었어요. 창밖이 아주아주 환해졌어요."   // 39자
        assertNull("세 살 상한 35자를 넘었다", ChildFilter.check(긴줄, 원문, AgeLevel.TODDLER))
        assertNotNull("여덟 살에게는 39자가 길지 않다", ChildFilter.check(긴줄, 원문, AgeLevel.BIG))
    }

    @Test
    fun 원문보다_너무_길면_막는다() {
        // 모델이 혼자 이야기를 지어내기 시작한 것이다.
        val 원문 = "구름이 웃었어요."
        val 폭주 = "구름이 웃었어요. 그리고 하늘로 올라가서 엄마를 만나고 다시 내려와 인사했어요."
        assertNull(ChildFilter.check(폭주, 원문, AgeLevel.BIG))
    }

    @Test
    fun 원문에_없던_장소_지시어를_만들면_막는다() {
        // 창밖에 없는 걸 "저기 있어" 라고 하면 아이가 창밖을 보고 속는다.
        assertNull(ChildFilter.check("저기 구름이 보여요.", "구름이 따라왔어요.", AgeLevel.KID))
        // 원문에 이미 있으면 통과한다.
        assertNotNull(ChildFilter.check("저기 구름이 보여요.", "저기 구름이 있어요.", AgeLevel.KID))
    }

    @Test
    fun 한글이_없으면_막는다() {
        assertNull(ChildFilter.check("OK 1234", "구름이 웃었어요.", AgeLevel.KID))
    }

    @Test
    fun 따옴표는_벗기고_통과시킨다() {
        val 결과 = ChildFilter.check("\"구름이 폴짝 뛰었어요.\"", "구름이 뛰었어요.", AgeLevel.KID)
        assertEquals("구름이 폴짝 뛰었어요.", 결과)
    }

    // ──────────────────────────── 생성 지시문 ────────────────────────────

    @Test
    fun 지시문이_주인공과_목표를_먼저_고정한다() {
        val s = StoryPrompt.system("현차", "구름", "배", AgeLevel.KID)
        assertTrue("주인공", "현차" in s)
        assertTrue("동행", "구름" in s)
        assertTrue("탈것", "배" in s)
        assertTrue("목표가 있어야 길안내로 안 떨어진다", "목표" in s)
        assertTrue("길을 설명하지 말라고 못 박아야 한다", "길 자체를 설명하지 않는다" in s)
    }

    @Test
    fun 지시문이_원문을_주고_결을_유지시킨다() {
        val u = StoryPrompt.rewrite(요청("구름이 웃었어요.").copy(scene = SceneKind.TUNNEL), "에버랜드", 12)
        assertTrue("원문을 줘야 혼자 이야기를 끌고 가지 않는다", "구름이 웃었어요." in u)
        assertTrue("장면", "굴" in u)
        assertTrue("가는 곳", "에버랜드" in u)
        assertTrue("남은 시간", "12분" in u)
    }

    // ──────────────────────────── 생성층 ────────────────────────────

    @Test
    fun 미리_만들어_둔_문장을_꺼내_쓴다() {
        val w = 생성층(FakeBackend { "구름이 방긋 웃었어요." })
        w.prepare(listOf(요청("구름이 웃었어요.")))
        assertEquals("구름이 방긋 웃었어요.", w.ready("구름이 웃었어요."))
        w.close()
    }

    @Test
    fun 한_번_쓴_문장은_다시_안_나온다() {
        // 같은 생성 문장이 두 번 나가면 아이가 같은 말을 두 번 듣는다.
        val w = 생성층(FakeBackend { "구름이 방긋 웃었어요." })
        w.prepare(listOf(요청("구름이 웃었어요.")))
        assertNotNull(w.ready("구름이 웃었어요."))
        assertNull(w.ready("구름이 웃었어요."))
        w.close()
    }

    @Test
    fun 준비_안_된_문장은_즉시_null_이다() {
        // ready() 가 기다리면 멀미 안내가 커브보다 늦게 나간다.
        val w = 생성층(FakeBackend { "안 쓰임" })
        assertNull(w.ready("아무도 요청하지 않은 문장"))
        w.close()
    }

    @Test
    fun 뼈대는_건드리지_않는다() {
        // 인과가 뼈대에 걸려 있다. 다시 쓰면 "그제야 웃었다" 가 왜인지 사라진다.
        val b = FakeBackend { "다시 쓴 문장이에요." }
        val w = 생성층(b)
        w.prepare(listOf(요청("구름이 집을 잃어버렸어요.", core = true)))
        assertEquals("뼈대는 생성기에 보내지도 않는다", 0, b.calls.get())
        assertNull(w.ready("구름이 집을 잃어버렸어요."))
        w.close()
    }

    @Test
    fun 필터에_걸리면_내장_문장이_나간다() {
        val w = 생성층(FakeBackend { "구름이 죽었어요." })
        w.prepare(listOf(요청("구름이 잠들었어요.")))
        assertNull("막혔어야 한다", w.ready("구름이 잠들었어요."))
        assertEquals(1, w.filtered.get())
        assertEquals(0, w.used.get())
        w.close()
    }

    @Test
    fun 무응답이_이어지면_스스로_꺼진다() {
        // 에뮬레이터가 이 경우다. 매 장면 타임아웃을 기다리면 그게 더 해롭다.
        val b = FakeBackend { null }
        val w = 생성층(b)
        repeat(LlmWriter.GIVE_UP_AFTER) { i -> w.prepare(listOf(요청("문장 $i"))) }
        assertEquals(LlmWriter.GIVE_UP_AFTER, b.calls.get())
        assertTrue("차단기가 내려가면 SDK 를 놓는다", b.closed)

        // 그 뒤로는 아무리 불러도 생성기를 안 부른다.
        repeat(10) { i -> w.prepare(listOf(요청("더 $i"))) }
        assertEquals("더 부르면 안 된다", LlmWriter.GIVE_UP_AFTER, b.calls.get())
        w.close()
    }

    @Test
    fun 중간에_한_번_성공하면_차단기가_풀린다() {
        var n = 0
        val b = FakeBackend { if (++n == 2) "구름이 방긋 웃었어요." else null }
        val w = 생성층(b)
        repeat(5) { i -> w.prepare(listOf(요청("문장 $i"))) }
        // 실패–성공–실패–실패–실패 → 연속 실패가 3에 닿는 건 마지막이다.
        assertEquals(5, b.calls.get())
        w.close()
    }

    @Test
    fun 닫은_뒤에는_아무것도_안_준다() {
        val w = 생성층(FakeBackend { "구름이 방긋 웃었어요." })
        w.prepare(listOf(요청("구름이 웃었어요.")))
        w.close()
        assertNull(w.ready("구름이 웃었어요."))
    }

    @Test
    fun 캐시가_무한히_자라지_않는다() {
        val w = 생성층(FakeBackend { "구름이 방긋 웃었어요." })
        w.prepare((1..200).map { 요청("문장 $it") })
        // 상한을 넘겨 쌓이지 않는다 — 안 쓸 문장을 들고 있을 이유가 없다.
        assertTrue("상한 안에 있어야 한다", w.used.get() <= LlmWriter.CACHE_MAX)
        w.close()
    }

    // ──────────────────────────── 연출과의 관계 ────────────────────────────

    /** 들어온 문장을 통째로 바꿔 주는 가짜 생성층. 연출이 안 흔들리는지 보는 용도. */
    private class 전부바꿈 : StoryWriter {
        val asked = mutableListOf<String>()
        override fun ready(key: String): String? {
            asked += key
            return "생성된 문장입니다."
        }
        override fun prepare(requests: List<StoryWriter.Request>) = Unit
        override fun close() = Unit
    }

    @Test
    fun 생성이_붙어도_책의_순서와_쪽수가_안_바뀐다() {
        // 접수 서류가 약속한 것: "문장을 어디서 가져오는지만 바뀌고 연출 규칙은 그대로".
        val 대본 = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("에버랜드", 65 * 60, 40_000),
            5 * 60_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 180, description = null),
            65 * 60_000L to CarEvent.DestinationArrived,
        )
        val 민판 = 달린다(대본, StoryWriter.None)
        val 생성판 = 달린다(대본, 전부바꿈())
        assertEquals("줄 수가 같아야 한다", 민판.size, 생성판.size)
        assertEquals(
            "장면 순서가 같아야 한다",
            민판.map { it.second },
            생성판.map { it.second },
        )
    }

    @Test
    fun 멀미_안내는_생성에_맡기지_않는다() {
        // 방향과 세기가 정확해야 하는 안전 기능이다. 다시 쓴 문장이 들어갈 자리가 아니다.
        val 대본 = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("에버랜드", 65 * 60, 40_000),
            5 * 60_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 180, description = null),
            65 * 60_000L to CarEvent.DestinationArrived,
        )
        val 나온줄 = 달린다(대본, 전부바꿈())
        val 기울임 = 나온줄.filter { it.second == SceneKind.TURN_RIGHT }
        assertTrue("우커브 안내가 나왔어야 한다", 기울임.isNotEmpty())
        assertTrue(
            "흔들림 안내가 생성 문장으로 바뀌면 안 된다",
            기울임.none { it.first == "생성된 문장입니다." },
        )
    }

    @Test
    fun 생성이_꺼지면_내장_문장_그대로다() {
        val 대본 = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("에버랜드", 20 * 60, 12_000),
            20 * 60_000L to CarEvent.DestinationArrived,
        )
        val 나온줄 = 달린다(대본, StoryWriter.None)
        assertTrue("이야기가 나와야 한다", 나온줄.size > 3)
        assertFalse("생성 문장이 섞이면 안 된다", 나온줄.any { it.first == "생성된 문장입니다." })
    }

    /** 가짜 시계로 한 주행을 흘려 보내고, 나간 (문장, 장면) 을 모은다. */
    private fun 달린다(
        script: List<Pair<Long, CarEvent>>,
        writer: StoryWriter,
    ): List<Pair<String, SceneKind>> {
        var clock = 1_000L
        val d = StoryDirector(
            childName = "현차",
            journal = null,
            season = Season.SEA,
            level = AgeLevel.KID,
            now = { clock },
            sky = { TimeOfDay.MORNING },
            writer = writer,
        )
        val out = mutableListOf<Pair<String, SceneKind>>()
        val end = script.last().first + 180_000L
        var next = 0
        while (clock <= end) {
            while (next < script.size && script[next].first <= clock) {
                d.onEvent(script[next].second)?.let { out += it.line to it.scene }
                next += 1
            }
            d.tick()?.let { out += it.line to it.scene }
            clock += 5_000L
        }
        return out
    }
}
