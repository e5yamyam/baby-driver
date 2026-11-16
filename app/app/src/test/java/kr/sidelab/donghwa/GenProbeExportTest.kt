package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **진짜 모델에게 물어 볼 지시문을 꺼내 쓴다.**
 *
 * 왜 필요한가: 에뮬레이터는 온디바이스 추론을 꺼 두므로 **생성된 문장을 한 줄도 볼 수 없다**
 * (SDK_FACTS 7.10). 그러면 지시문이 쓸모 있는지, [ChildFilter] 가 멀쩡한 문장까지 막는지
 * 알 길이 없고, **차 없는 자리(2차 발표)에서 생성을 보여 줄 수도 없다.**
 *
 * 그래서 **실제 [StoryPrompt] 가 만든 지시문**을 열쇠와 함께 파일로 꺼낸다. 다음은 둘이다.
 *  ⑴ `tools/gen_probe.py` — 차 밖 모델에게 묻고 답을 받는다
 *  ⑵ `tools/gen_probe.py --canned` — 그 답을 `assets/demo/생성문장.tsv` 로 굽는다([CannedBackend])
 * 받은 답은 **실제 [ChildFilter]** 를 지나야 쓴다([GenFilterReportTest]).
 *
 * ⛔ **아이 이름은 시연 프로필 하나로 고정한다**([NAME]). 미리 만든 문장에는 이름이 박히므로
 *    [CannedBackend] 는 **이름이 다르면 아무 답도 안 한다** — 남의 이름을 읽어 주면 안 된다.
 */
class GenProbeExportTest {

    companion object {
        /** 시연 프로필. `tools/record_demo.py --name` 의 기본값과 **같아야 한다.** */
        const val NAME = "현차"

        /** 시연 대본 a 의 목적지(`Demo.kt`). */
        const val DEST = "에버랜드"
    }

    @Test
    fun 지시문을_파일로_내보낸다() {
        val out = File("build/생성지시문.tsv")
        out.parentFile?.mkdirs()
        var n = 0
        out.bufferedWriter().use { w ->
            w.write("# 열쇠\t원문\t장면\tsystem\tuser  (줄바꿈은 \\n 으로 적는다)\n")
            // 세 살과 다섯 살은 **다른 문장**이 나간다(short 판). 열쇠에 나이가 들어 있다.
            for (level in listOf(AgeLevel.KID, AgeLevel.TODDLER)) {
                val system = StoryPrompt.system(NAME, Guest.CLOUD.name, Season.SEA.vehicle, level)
                for (ep in Episode.SEA) {
                    ep.pages.forEachIndexed { idx, pg ->
                        // 생성이 손대는 범위와 **같은 조건**이다(`StoryDirector.prefetch`).
                        if (pg.core) return@forEachIndexed
                        val raw = when {
                            level != AgeLevel.TODDLER -> pg.text
                            pg.short != null -> pg.short
                            else -> null
                        } ?: return@forEachIndexed
                        // ⛔ 주행마다 달라지는 자리(`{여기}` 지명 · `{때}` 시각)는 미리 만들 수 없다.
                        //    그 쪽은 내장 문장이 그대로 나간다.
                        if (raw.contains("{여기}") || raw.contains("{때}")) return@forEachIndexed
                        // 조사는 **앱과 같은 코드**로 붙인다(`particles`) — 두 벌 쓰면 어긋난다.
                        val original = particles(
                            raw.replace("{아이}", NAME)
                                .replace("{손님}", Guest.CLOUD.name)
                                .replace("{곳}", DEST)
                                .replace("{탈것}", Season.SEA.vehicle),
                        )
                        if (original.contains('{')) return@forEachIndexed
                        val key = "${Season.SEA.id}:${ep.no}:$idx:$level"
                        val req = StoryWriter.Request(original, pg.scene, pg.core, key = key)
                        val user = StoryPrompt.rewrite(req, DEST, 30)
                        w.write(
                            listOf(key, original, pg.scene.name, esc(system), esc(user))
                                .joinToString("\t") + "\n",
                        )
                        n += 1
                    }
                }
            }
        }
        println("[지시문 내보냄] ${n}줄 → ${out.absolutePath}")
        assertTrue("생성이 손댈 쪽이 있어야 한다", n > 100)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")
}
