package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **진짜 모델에게 물어 볼 지시문을 꺼내 쓴다.**
 *
 * 왜 필요한가: 에뮬레이터는 온디바이스 추론을 꺼 두므로 **생성된 문장을 한 줄도 볼 수 없다**
 * (SDK_FACTS 7.10). 그러면 지시문이 쓸모 있는지, [ChildFilter] 가 너무 많이 막는지 알 길이 없다.
 * 그래서 **실제 [StoryPrompt] 가 만든 지시문**을 파일로 꺼내고, 맥에서 다른 모델에게 물어 본 뒤
 * 그 답을 **실제 [ChildFilter]** 에 통과시켜 본다([GenFilterReportTest]).
 *
 * ⛔ **아이 이름을 넣지 않는다.** 지시문이 기계 밖으로 나가므로 `별이`·`바닷가` 같은 가짜만 쓴다.
 *
 * 쓰는 법:
 *   1. `./gradlew :app:testDebugUnitTest --tests '*GenProbeExportTest*'`
 *   2. `python3 tools/gen_probe.py`            ← 모델에게 묻고 후보를 적는다
 *   3. `./gradlew :app:testDebugUnitTest --tests '*GenFilterReportTest*'`
 */
class GenProbeExportTest {

    @Test
    fun 지시문을_파일로_내보낸다() {
        val level = AgeLevel.KID
        val system = StoryPrompt.system("별이", Guest.CLOUD.name, Season.SEA.vehicle, level)
        val out = File("build/생성지시문.tsv")
        out.parentFile?.mkdirs()
        var n = 0
        out.bufferedWriter().use { w ->
            w.write("# 원문\t장면\tsystem\tuser  (줄바꿈은 \\n 으로 적는다)\n")
            for (ep in Episode.SEA) {
                for (pg in ep.pages) {
                    // 생성이 손대는 범위와 **같은 조건**이다 — 뼈대·`{여기}` 쪽은 제외한다.
                    if (pg.core || pg.text.contains("{여기}")) continue
                    // 자리표시자는 채워서 보낸다(날글을 보내면 모델이 베껴 와 필터에 막힌다).
                    // ⛔ 조사는 **앱과 같은 코드**로 붙인다. 처음엔 `{가}` 를 그냥 "가" 로 바꿨더니
                    //    모델에게 `구름가`·`별이이` 같은 틀린 한국어를 보냈다(2026-11-16).
                    val original = particles(
                        pg.text
                            .replace("{아이}", "별이").replace("{손님}", Guest.CLOUD.name)
                            .replace("{때}", "아침").replace("{곳}", "바닷가")
                            .replace("{탈것}", Season.SEA.vehicle),
                    )
                    if (original.contains('{')) continue   // 못 채운 자리표시자가 남으면 안 보낸다
                    val req = StoryWriter.Request(original, pg.scene, pg.core, key = "${ep.no}:$n")
                    val user = StoryPrompt.rewrite(req, "바닷가", 30)
                    w.write(
                        listOf(original, pg.scene.name, esc(system), esc(user))
                            .joinToString("\t") + "\n",
                    )
                    n += 1
                }
            }
        }
        println("[지시문 내보냄] ${n}줄 → ${out.absolutePath}")
        assertTrue("생성이 손댈 쪽이 있어야 한다", n > 50)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")
}
