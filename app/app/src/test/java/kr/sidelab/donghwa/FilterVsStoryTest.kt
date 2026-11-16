package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **필터가 우리 원고를 막으면 그건 필터 버그다.**
 *
 * 생성 문장은 원고와 같은 결로 쓰이게 지시해 둔다([StoryPrompt]). 그러니 **원고 자체가
 * [ChildFilter] 를 못 지나면, 그 결로 쓴 생성 문장도 못 지난다** — 생성 기능이 아무도
 * 모르게 죽는다. 전부 내장 문장으로 돌기 때문에 겉으로는 멀쩡해 보인다.
 *
 * 2026-11-17 에 실제로 그랬다. 금지어 `"죽"` 한 글자가 **"입을 삐죽 내밀었어요"** 를 막고,
 * 선행어 규칙이 **대사로 시작하는 쪽**(`"내가 먼저야."`)을 막았다. 둘 다 원고에 있는 문장이다.
 */
class FilterVsStoryTest {

    /** 원고의 자리표시자를 채운 꼴. 생성 지시문에 들어가는 것과 같은 모양이다. */
    private fun 채운다(t: String) = particles(
        t.replace("{아이}", "현차")
            .replace("{손님}", Guest.CLOUD.name)
            .replace("{때}", "아침")
            .replace("{곳}", "에버랜드")
            .replace("{여기}", "분당")
            .replace("{탈것}", Season.SEA.vehicle),
    )

    @Test
    fun 원고_자체는_필터에_안_걸린다() {
        val 걸린것 = mutableListOf<String>()
        var n = 0
        for (ep in Episode.SEA) {
            for (pg in ep.pages) {
                val t = 채운다(pg.text)
                if (t.contains('{')) continue
                n += 1
                // 자기 자신을 원문으로 두고 본다 — 길이·문장수는 당연히 통과해야 한다.
                // 나이는 가장 넉넉한 층으로 본다(세 살 35자 상한은 원고에 일부러 긴 쪽이 있다).
                if (ChildFilter.check(t, t, AgeLevel.BIG) == null) 걸린것 += "${ep.no}화: $t"
            }
        }
        println("[원고 대 필터] ${n}쪽 중 걸린 쪽 ${걸린것.size}")
        걸린것.take(8).forEach { println("      ⛔ $it") }
        assertTrue(
            "원고 ${걸린것.size}쪽이 어린이 필터에 걸린다 — 같은 결로 쓴 생성 문장도 다 막힌다",
            걸린것.isEmpty(),
        )
    }

    @Test
    fun 조각_대사도_필터에_안_걸린다() {
        // 책이 없는 화에서 나가는 줄들(손님 대사·과제·물음)도 같은 결이어야 한다.
        val 조각 = Episode.SEA.flatMap { it.tells + it.asks + it.looks + listOf(it.task, it.gain) }
        val 걸린것 = 조각.map(::채운다)
            .filterNot { it.contains('{') }
            .filter { ChildFilter.check(it, it, AgeLevel.BIG) == null }
        println("[조각 대사 대 필터] ${조각.size}줄 중 걸린 줄 ${걸린것.size}")
        걸린것.take(8).forEach { println("      ⛔ $it") }
        assertTrue("조각 대사 ${걸린것.size}줄이 필터에 걸린다", 걸린것.isEmpty())
    }
}
