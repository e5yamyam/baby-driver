package kr.sidelab.donghwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **시연용 미리 만든 생성문** — 읽기와 **이름 가드**.
 *
 * 왜 이 검사가 중요한가: 이 파일에는 시연 프로필 이름(`현차`)이 박힌 문장이 들어 있다.
 * 가드가 새면 **다른 이름을 쓰는 아이에게 남의 이름을 읽어 주는 동화**가 나간다.
 * 그런 건 발표장에서 들키는 게 아니라 **아이 방에서** 들킨다.
 */
class CannedBackendTest {

    // ⛔ 경로를 여기 또 적지 않는다 — 앱이 여는 그 경로로 본다.
    private val 파일 = File("src/main/assets/${CannedBackend.PATH}")

    private val 본문 = """
        # 머리말은 건너뛴다
        #이름	현차
        # 열쇠	생성문장
        sea:1:7:KID	구름은 그 이름을 몇 번 불러 보았어요.
        sea:1:9:KID	배가 기울어지자 구름이 미끄러졌어요.
        빈칸은	
        	열쇠없음
    """.trimIndent().lineSequence()

    @Test
    fun 머리말과_빈칸을_걸러_읽는다() {
        val c = CannedBackend.parse(본문)
        assertEquals("현차", c.forName)
        assertEquals(2, c.answers.size)
        assertEquals("구름은 그 이름을 몇 번 불러 보았어요.", c.answers["sea:1:7:KID"])
    }

    @Test
    fun 이름이_같으면_답한다() {
        val c = CannedBackend.parse(본문)
        assertNotNull(CannedBackend.answer(c, "현차", "sea:1:7:KID"))
    }

    @Test
    fun 이름이_다르면_한_줄도_안_준다() {
        val c = CannedBackend.parse(본문)
        assertNull("남의 이름이 박힌 문장이 나가면 안 된다", CannedBackend.answer(c, "지우", "sea:1:7:KID"))
        assertNull(CannedBackend.answer(c, "", "sea:1:7:KID"))
    }

    @Test
    fun 이름이_안_적힌_파일은_통째로_안_쓴다() {
        val 이름없음 = CannedBackend.parse(sequenceOf("sea:1:7:KID\t문장"))
        assertNull(이름없음.forName)
        assertNull("누구 것인지 모르는 문장은 쓰지 않는다", CannedBackend.answer(이름없음, "현차", "sea:1:7:KID"))
    }

    @Test
    fun asset_경로가_ASCII_다() {
        // ⛔ 2026-11-17: 이름을 `demo/생성문장.tsv` 로 뒀더니 **앱이 못 찾았다.**
        //    맥은 파일 이름을 NFD 로 적고 코틀린 문자열 리터럴은 NFC 라 바이트가 다르다.
        //    그런데 `assets.open` 이 실패하면 `CannedBackend` 는 조용히 빈 손이 되고,
        //    로그에는 "생성 0줄" 만 남아 **원인이 안 보인다**(첫 녹화를 그렇게 날렸다).
        assertTrue(
            "asset 경로에 ASCII 아닌 글자가 있다: ${CannedBackend.PATH}",
            CannedBackend.PATH.all { it.code in 0x20..0x7E },
        )
        assertTrue("앱이 여는 경로에 파일이 없다: ${파일.path}", 파일.exists())
    }

    @Test
    fun 실제_asset_의_열쇠가_앱이_만드는_열쇠_꼴이다() {
        val c = CannedBackend.parse(파일.readLines().asSequence())
        assertEquals("시연 프로필 이름이 안 적혀 있다", "현차", c.forName)
        assertTrue("문장이 너무 적다(${c.answers.size})", c.answers.size > 200)

        val 꼴 = Regex("""^(sea|sky|wood):(\d{1,2}):(\d{1,3}):(TODDLER|KID|BIG)$""")
        val 틀린열쇠 = c.answers.keys.filterNot { 꼴.matches(it) }
        assertTrue("열쇠 꼴이 StoryDirector.genKey 와 다르다: ${틀린열쇠.take(3)}", 틀린열쇠.isEmpty())

        // 열쇠가 **실제로 있는 쪽**을 가리키는지. 쪽 번호가 범위를 넘으면 영원히 안 쓰인다.
        val 없는쪽 = c.answers.keys.filterNot { k ->
            val f = k.split(":")
            val ep = Episode.SEA.firstOrNull { it.no == f[1].toInt() }
            ep != null && f[2].toInt() in ep.pages.indices
        }
        assertTrue("없는 쪽을 가리키는 열쇠 ${없는쪽.size}개: ${없는쪽.take(3)}", 없는쪽.isEmpty())

        // 줄바꿈·탭이 든 문장은 앱이 막는다 — asset 에 들어 있으면 그 줄은 죽은 줄이다.
        val 더러운줄 = c.answers.values.filter { it.contains('\n') || it.contains('\t') }
        assertTrue("줄바꿈·탭이 든 문장 ${더러운줄.size}줄", 더러운줄.isEmpty())
    }
}
