package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * **생성이 실제로 아이에게 닿는가.**
 *
 * `StoryWriterTest` 는 생성층의 조각들(캐시·필터·차단기)과 "생성이 붙어도 연출이 안 흔들린다"를
 * 본다. 그런데 거기 쓰는 가짜 생성층은 `ready()` 가 **무엇을 묻든 문장을 돌려준다** —
 * 그래서 **"미리 만들어 둔 자리와 꺼내 쓰는 자리가 같은 열쇠를 쓰는가"** 를 못 본다.
 *
 * 이 검사는 **진짜 [LlmWriter]** 를 끼우고 한 주행을 흘려 보낸 뒤, 아이에게 나간 줄 중
 * 생성 문장이 **몇 줄인지** 센다. 0 이면 생성 기능은 코드로만 있고 실제로는 죽어 있다는 뜻이다.
 */
class GenReachTest {

    /** 무엇을 물어도 아이에게 보내도 되는 한 문장을 준다. 필터를 전부 지나는 길이·내용이다. */
    private class 되는차 : LlmWriter.Backend {
        val 받은지시 = mutableListOf<String>()
        override fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
            받은지시 += user
            onDone(만든문장)
        }
        override fun close() = Unit
    }

    companion object {
        const val 만든문장 = "구름이 방긋 웃었어요."
    }

    private val 즉시 = Executor { it.run() }

    private fun 생성층(backend: LlmWriter.Backend) = LlmWriter(
        childName = "별이",
        guestName = "구름이",
        vehicle = "배",
        level = AgeLevel.KID,
        destination = { "바닷가" },
        minutesLeft = { 30 },
        backend = backend,
        worker = 즉시,
    )

    private fun 달린다(level: AgeLevel, writer: StoryWriter): List<String> {
        var clock = 1_000L
        val d = StoryDirector(
            childName = "별이",
            journal = null,
            season = Season.SEA,
            level = level,
            now = { clock },
            sky = { TimeOfDay.MORNING },
            writer = writer,
        )
        val 대본 = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("바닷가", 65 * 60, 40_000),
            5 * 60_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 180, description = null),
            65 * 60_000L to CarEvent.DestinationArrived,
        )
        val out = mutableListOf<String>()
        val end = 대본.last().first + 180_000L
        var next = 0
        while (clock <= end) {
            while (next < 대본.size && 대본[next].first <= clock) {
                d.onEvent(대본[next].second)?.let { out += it.line }
                next += 1
            }
            d.tick()?.let { out += it.line }
            clock += 1_000L
        }
        return out
    }

    @Test
    fun 생성_문장이_실제로_아이에게_나간다() {
        val 차 = 되는차()
        val w = 생성층(차)
        val 나온줄 = 달린다(AgeLevel.KID, w)
        val 생성줄 = 나온줄.count { it == 만든문장 }
        println("[생성 닿음] 나간 줄 ${나온줄.size} · 생성 요청 ${차.받은지시.size} · 필터통과 ${w.used.get()} · 막힘 ${w.filtered.get()} · 실제로 나간 생성줄 ${생성줄}")
        assertTrue(
            "생성층이 붙었는데 아이에게 나간 생성 문장이 0 줄이다 — " +
                "미리 만들어 둔 열쇠와 꺼내 쓰는 열쇠가 다르다는 뜻이다",
            생성줄 > 0,
        )
    }

    @Test
    fun 세_살에게도_생성이_닿는다() {
        val 차 = 되는차()
        val w = LlmWriter(
            childName = "별이", guestName = "구름이", vehicle = "배",
            level = AgeLevel.TODDLER,
            destination = { "바닷가" }, minutesLeft = { 30 },
            backend = 차, worker = 즉시,
        )
        val 나온줄 = 달린다(AgeLevel.TODDLER, w)
        val 생성줄 = 나온줄.count { it == 만든문장 }
        println("[세 살] 나간 줄 ${나온줄.size} · 생성 요청 ${차.받은지시.size} · 실제로 나간 생성줄 ${생성줄}")
        assertTrue("세 살에게는 짧은 판(short)이 나가는데 생성은 본문(text)으로 걸려 있다", 생성줄 > 0)
    }

    /** 부르면 그 자리에서 터지는 생성기. 진짜로 그런 일이 있었다. */
    private class 터지는차 : LlmWriter.Backend {
        var 불린횟수 = 0
        override fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
            불린횟수++
            throw IllegalStateException("기기에서만 터지는 종류의 사고")
        }
        override fun close() = Unit
    }

    /**
     * 🔴 **생성층이 터져도 이야기는 끝까지 간다.**
     *
     * 2026-11-17, `ChildFilter` 의 정규식이 안드로이드에서 `PatternSyntaxException` 으로
     * 터졌다. 그게 생성 전용 스레드에서 올라와 **앱 프로세스를 통째로 죽였고**, 아이는
     * 첫 줄 하나만 듣고 끝났다. 이 층이 약속한 것은 그 반대다 —
     * "생성이 안 되는 기계에서도 앱은 똑같이 돌아간다".
     *
     * 그래서 **터지는 생성기를 끼우고도 내장 이야기가 그대로 나오는지**를 센다.
     */
    @Test
    fun 생성층이_터져도_이야기는_끝까지_간다() {
        val 멀쩡 = 달린다(AgeLevel.KID, StoryWriter.None)
        val 차 = 터지는차()
        val 터진채 = 달린다(AgeLevel.KID, 생성층(차))
        println("[터져도] 생성 호출 ${차.불린횟수}회 · 내장만 ${멀쩡.size}줄 · 터진 채 ${터진채.size}줄")
        assertTrue("생성기를 부르지도 않았다면 이 검사가 아무것도 안 본 것이다", 차.불린횟수 > 0)
        assertTrue(
            "생성층이 터지자 이야기가 끊겼다 — 내장 ${멀쩡.size}줄 vs 터진 채 ${터진채.size}줄",
            터진채 == 멀쩡,
        )
    }

    @Test
    fun 자리표시자가_든_쪽도_생성에_걸린다() {
        val 차 = 되는차()
        val w = 생성층(차)
        달린다(AgeLevel.KID, w)
        val 자리표시자달린요청 = 차.받은지시.count { Regex("""\{[^}]{1,12}\}""").containsMatchIn(it) }
        println("[자리표시자] 생성 요청 ${차.받은지시.size} 건 중 자리표시자 남은 지시 ${자리표시자달린요청} 건")
        assertTrue(
            "생성 지시문에 `{아이}` 가 그대로 들어가면 모델이 그걸 지어내거나 " +
                "그대로 베껴 와서 ChildFilter 가 통째로 막는다 — 채워서 보내야 한다",
            자리표시자달린요청 == 0,
        )
    }
}
