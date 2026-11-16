package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * **시연 모드가 실제 주행에서 문장을 바꾸나.**
 *
 * `CannedBackendTest` 는 파일을 읽고 이름 가드를 보는 것까지다. 그런데 2026-11-17 녹화에서
 * 파일은 323줄 다 읽혔는데도 **생성 0줄**로 나왔다 — 주행이 **묻는 열쇠**와 파일에 **있는 열쇠**가
 * 달랐기 때문이다. 그건 파일 검사로는 안 보인다. 그래서 여기서는 **한 주행을 흘려 보내고**
 * 실제로 묻는 열쇠를 모아 파일과 맞춰 본다.
 */
class CannedReachTest {

    private val 파일 = File("src/main/assets/${CannedBackend.PATH}")

    /** 실제 asset 을 읽어 답하는 백엔드. 앱이 쓰는 그 함수를 그대로 쓴다. */
    private class 파일백엔드(lines: Sequence<String>, private val who: String) : LlmWriter.Backend {
        val canned = CannedBackend.parse(lines)
        val 물은열쇠 = mutableListOf<String>()
        val 못찾은열쇠 = mutableListOf<String>()
        override fun generate(key: String, system: String, user: String, timeoutMs: Long, onDone: (String?) -> Unit) {
            물은열쇠 += key
            val got = CannedBackend.answer(canned, who, key)
            if (got == null) 못찾은열쇠 += key
            onDone(got)
        }
        override fun close() = Unit
    }

    private fun 달린다(level: AgeLevel, writer: StoryWriter): List<String> {
        var clock = 1_000L
        val d = StoryDirector(
            childName = "현차", journal = null, season = Season.SEA, level = level,
            now = { clock }, sky = { TimeOfDay.MORNING }, writer = writer,
        )
        val 대본 = listOf<Pair<Long, CarEvent>>(
            0L to CarEvent.RouteStarted("에버랜드", 65 * 60, 54_000),
            60_000L to CarEvent.Landmark(CarEvent.Landmark.Kind.TUNNEL, meters = 150),
            300_000L to CarEvent.TurnAhead(CarEvent.TurnAhead.Side.RIGHT, sharp = true, meters = 180, description = null),
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
    fun 시연_모드가_실제_주행에서_문장을_바꾼다() {
        assertTrue("시연용 asset 이 없다 — tools/gen_probe.py --all --canned", 파일.exists())
        val 차 = 파일백엔드(파일.readLines().asSequence(), "현차")
        val w = LlmWriter(
            childName = "현차", guestName = Guest.CLOUD.name, vehicle = Season.SEA.vehicle,
            level = AgeLevel.KID, destination = { "에버랜드" }, minutesLeft = { 30 },
            backend = 차, worker = Executor { it.run() },
        )
        val 나온줄 = 달린다(AgeLevel.KID, w)
        val 미리만든문장 = 차.canned.answers.values.toSet()
        val 바뀐줄 = 나온줄.count { it in 미리만든문장 }
        println(
            "[시연 닿음] 나간 줄 ${나온줄.size} · 물은 열쇠 ${차.물은열쇠.size} · " +
                "못 찾은 열쇠 ${차.못찾은열쇠.size} · 필터통과 ${w.used.get()} · 실제로 바뀐 줄 ${바뀐줄}",
        )
        차.못찾은열쇠.take(6).forEach { println("      ⛔ 파일에 없는 열쇠: $it") }
        차.물은열쇠.take(3).forEach { println("      · 물은 열쇠: $it") }

        assertTrue(
            "주행이 묻는 열쇠가 파일에 없다 — 시연 모드를 켜도 생성이 한 줄도 안 나온다",
            차.못찾은열쇠.isEmpty(),
        )
        assertTrue("시연 모드인데 바뀐 줄이 0 이다", 바뀐줄 > 0)
    }
}
