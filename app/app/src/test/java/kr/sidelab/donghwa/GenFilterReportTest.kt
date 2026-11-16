package kr.sidelab.donghwa

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **진짜 모델이 쓴 문장을 진짜 필터에 통과시켜 본다.**
 *
 * `StoryWriterTest` 는 **내가 지어낸 나쁜 문장**이 막히는지 본다(무서운 말·자리표시자·긴 문장).
 * 그건 필터가 막아야 할 것을 막는지를 보는 검사다. 여기서 보는 것은 반대쪽이다 —
 * **멀쩡한 문장까지 막고 있지 않은가.** 통과율이 너무 낮으면 생성 기능은 켜도 안 켠 것과 같다.
 *
 * 후보는 `tools/gen_probe.py` 가 적어 둔다. 파일이 없으면 이 검사는 **건너뛴다**
 * (새로 받은 저장소에서 빨간 줄이 뜨지 않게).
 */
class GenFilterReportTest {

    private val 후보 = File("src/test/resources/생성후보.tsv")

    @Test
    fun 멀쩡한_생성문장은_필터를_지난다() {
        assumeTrue("후보 파일이 없다 — tools/gen_probe.py 를 먼저 돌린다", 후보.exists())
        val rows = 후보.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .filter { it.size >= 3 }
        assumeTrue("후보가 비었다", rows.isNotEmpty())

        val level = AgeLevel.KID
        var pass = 0
        val 막힌것 = mutableListOf<String>()
        for ((original, _, got) in rows.map { Triple(it[0], it[1], it[2]) }) {
            val ok = ChildFilter.check(got, original, level)
            if (ok != null) {
                pass += 1
            } else {
                막힌것 += "      · ${이유(got, original, level)}\n        원문(${original.length}) $original\n        생성(${got.length}) $got"
            }
        }
        val 율 = pass * 100 / rows.size
        println("[필터 통과] ${pass}/${rows.size} (${율}%)")
        막힌것.take(12).forEach(::println)

        assertTrue(
            "멀쩡해 보이는 생성 문장이 ${율}% 만 통과한다 — 필터나 지시문이 서로 안 맞는다",
            율 >= 60,
        )
    }

    /** 왜 막혔는지 짚어 준다. **공개 상수만** 써서 필터와 어긋나지 않게 한다. */
    private fun 이유(s0: String, original: String, level: AgeLevel): String {
        val s = s0.trim().removeSurrounding("\"").trim()
        val 걸린말 = ChildFilter.BANNED.filter { it in s }
        val 걸린지시어 = ChildFilter.POINTERS.filter { it in s && it !in original }
        return when {
            s.isEmpty() -> "빈 문장"
            Regex("""\{[^}]{1,12}}""").containsMatchIn(s) -> "자리표시자가 남았다"
            Regex("""^\s*(물론|네[,.!]|알겠|다음은|여기 |^")""").containsMatchIn(s) -> "모델이 제 설명을 붙였다"
            s.length > ChildFilter.maxChars(level) -> "나이 상한 ${ChildFilter.maxChars(level)}자 넘음(${s.length}자)"
            s.length > StoryPrompt.budget(original) + 10 ->
                "길이 천장 ${StoryPrompt.budget(original) + 10}자 넘음(${s.length}자)"
            s.contains('\n') -> "줄바꿈이 들었다"
            걸린말.isNotEmpty() -> "쓰지 않는 말: ${걸린말.joinToString()}"
            걸린지시어.isNotEmpty() -> "없던 장소 지시어: ${걸린지시어.joinToString()}"
            s.none { it in '가'..'힣' } -> "한글이 없다"
            else -> "이유 불명"
        }
    }
}
