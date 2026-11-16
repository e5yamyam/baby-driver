package kr.sidelab.donghwa

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **데스크톱에서 되는 정규식이 안드로이드에서는 안 될 수 있다.**
 *
 * 2026-11-17, 앱이 기기에서 통째로 죽었다. 원인은 한 글자였다:
 *
 * ```
 * Regex("""\{[^}]{1,12}}""")        // 닫는 중괄호가 안 escape 돼 있다
 * → PatternSyntaxException: Syntax error in regexp pattern near index 13
 * ```
 *
 * 데스크톱 JVM 의 `java.util.regex` 는 홀로 선 `}` 를 **그냥 글자로 받아 준다.**
 * 안드로이드는 ICU 엔진을 쓰는데 거기서는 **문법 오류**다. 그래서
 * **단위검사 49개가 전부 통과하는데 기기에서는 정적 초기화가 터졌다.**
 * 게다가 그 정규식은 생성 문장이 올 때만 닿는 자리라, 생성이 꺼진 기계에서는
 * 영영 안 터진다 — 제품 차에 올라가서야 처음 터졌을 수도 있다.
 *
 * 그래서 이 검사는 **소스에 적힌 정규식 리터럴을 직접 읽어** 중괄호 규칙을 본다.
 * 같은 엔진이 없으니 결과를 비교할 수는 없고, **쓰는 법을 제한하는 수밖에 없다.**
 */
class RegexPortabilityTest {

    /** `{2}` `{1,12}` `{3,}` 처럼 수량자로 쓰인 중괄호. 이건 두 엔진 다 받는다. */
    private val 수량자 = Regex("""\{\d+(,\d*)?\}""")

    /**
     * 그 패턴에 **escape 안 된 중괄호**가 있으면 그 자리를 돌려준다.
     *
     * 봐 주는 자리가 둘이다. **수량자**(`{1,12}`)와 **글자류 안쪽**(`[^}]`) —
     * 둘 다 두 엔진이 똑같이 받는다. 그 밖에서 홀로 선 중괄호만 집어낸다.
     */
    private fun 맨중괄호(pattern: String): List<Int> {
        val 지운것 = 수량자.replace(pattern) { "\u0000".repeat(it.value.length) }
        val 자리 = mutableListOf<Int>()
        var i = 0
        var 글자류안 = false
        while (i < 지운것.length) {
            val c = 지운것[i]
            if (c == '\\') { i += 2; continue }
            when {
                글자류안 && c == ']' -> 글자류안 = false
                !글자류안 && c == '[' -> 글자류안 = true
                !글자류안 && (c == '{' || c == '}') -> 자리 += i
            }
            i++
        }
        return 자리
    }

    private fun 코틀린소스(): List<File> =
        File("src/main/java/kr/sidelab/donghwa").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()

    /** `Regex("""…""")` 안쪽. 세겹따옴표만 본다 — 이 앱은 전부 그 꼴로 적는다. */
    private val 리터럴 = Regex("""Regex\("{3}(.*?)"{3}\)""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun 정규식에_escape_안된_중괄호가_없다() {
        val 소스 = 코틀린소스()
        assertTrue("소스를 못 찾았다 — 작업 폴더가 app/app 이 아닌가", 소스.isNotEmpty())

        val 걸린것 = mutableListOf<String>()
        var 본것 = 0
        for (f in 소스) {
            for (m in 리터럴.findAll(f.readText())) {
                본것++
                val p = m.groupValues[1]
                val 자리 = 맨중괄호(p)
                if (자리.isNotEmpty()) 걸린것 += "${f.name}: $p  ← ${자리.joinToString()}번째 글자"
            }
        }
        assertTrue("정규식 리터럴을 하나도 못 찾았다 — 찾는 꼴이 바뀌었나", 본것 > 0)
        assertTrue(
            "안드로이드 ICU 는 홀로 선 중괄호를 문법 오류로 거절한다. `\\{` `\\}` 로 적을 것:\n" +
                걸린것.joinToString("\n"),
            걸린것.isEmpty(),
        )
    }

    /**
     * **검사기 자신을 검사한다.** 실제로 앱을 죽인 그 패턴을 집어내지 못하면
     * 이 검사는 영원히 초록불만 켜는 장식이 된다.
     */
    @Test
    fun 실제로_앱을_죽인_패턴을_집어낸다() {
        assertTrue("터졌던 그 패턴을 못 집어낸다", 맨중괄호("""\{[^}]{1,12}}""").isNotEmpty())
        assertTrue("고친 패턴을 틀렸다고 한다", 맨중괄호("""\{[^}]{1,12}\}""").isEmpty())
        assertTrue("수량자를 중괄호로 센다", 맨중괄호("""[가-힣]{2,4}""").isEmpty())
    }

    /** 고쳐 둔 그 패턴이 하던 일은 그대로 하는지. */
    @Test
    fun 자리표시자는_여전히_막힌다() {
        assertTrue(ChildFilter.check("{아이}야, 저기 봐.", "현차야, 저기 봐.", AgeLevel.KID) == null)
        assertTrue(ChildFilter.check("현차야, 구름이 웃었어요.", "현차야, 구름이 웃었어요.", AgeLevel.KID) != null)
    }
}
