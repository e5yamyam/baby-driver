package kr.sidelab.donghwa

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 책장 — **주행 하나가 동화책 한 권이 된다.**
 *
 * 왜 이게 필요한가: 도착 대사가 이미 "오늘 지나온 길로 동화책 한 편을 만들어 둘게요" 라고 약속한다.
 * 그 약속을 지키는 곳이다. 그리고 주차 중에는 제한이 풀리므로([SDK_FACTS] 3절 실측: `parked` = `0x0`),
 * **펼쳐 보는 책이 정차 중에 보여 줄 수 있는 가장 자연스러운 화면**이다.
 *
 * 저장은 앱 내부(`filesDir/books`)에만 한다. 서버도, 계정도 쓰지 않는다 —
 * 아이 이름과 다녀온 장소가 들어가므로 기기 밖으로 내보낼 이유가 없다.
 */
object Library {

    private const val TAG = "DonghwaBook"
    private const val DIR = "books"

    /**
     * 책의 한 장. 그림 한 컷 + 글 한 줄 — 주행 중 화면과 같은 재료다.
     *
     * @param grown 그때 손님이 얼마나 커져 있었나. **이게 없으면 책을 펼쳤을 때
     *   "구름이 커졌어" 라고 적힌 장에서도 구름이 작다**(2026-10-31 실측).
     *   주행 중 화면은 고쳤는데 책장만 안 고쳐져 있었다.
     */
    data class Page(val line: String, val scene: SceneKind, val grown: Int = 0)

    data class Book(
        /** 만든 시각. 그대로 파일 이름이 된다. */
        val id: Long,
        val destination: String?,
        val minutes: Int,
        /** 지나온 곳. 책 뒤에 "오늘 지나온 길" 로 적는다. */
        val places: List<String>,
        val pages: List<Page>,
        val timeOfDay: TimeOfDay,
        /** 마지막 장의 사진. 아직 비어 있다 — 카메라 경로가 열리면 채운다. */
        val photoPath: String? = null,
    ) {
        val title: String
            get() = destination?.takeIf { it.isNotBlank() }?.let { "$it 가는 길" } ?: "어느 날의 길"

        val dateText: String
            get() = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(id))

        /**
         * 표지 그림 — 그 날 제일 특별했던 장면.
         * 처음엔 "ROAD·START 가 아닌 첫 장면"을 썼더니 표지가 죄다 화살표·과속방지턱이었다
         * (2026-10-29, 책장 스크린샷에서 확인). 그래서 **눈에 띄는 순서로 고른다.**
         */
        val cover: SceneKind
            get() {
                val had = pages.map { it.scene }.toSet()
                return COVER_PREFERENCE.firstOrNull { it in had } ?: SceneKind.START
            }
    }

    // ──────────────────────────────── 모으는 중 ────────────────────────────────

    /**
     * 주행 한 번을 모으는 중인 상태. 출발에서 시작해 도착에서 책이 된다.
     * 도착 전에 앱이 죽으면 그 주행은 책이 되지 않는다 — 지금은 그걸 받아들인다
     * (중간 저장을 넣으면 "안 끝난 책"을 책장에 보여 줄지부터 정해야 한다).
     */
    class Recording(val startedAt: Long) {
        var destination: String? = null
        var minutes: Int = 0
        var timeOfDay: TimeOfDay = TimeOfDay.DAY
        val places = mutableListOf<String>()
        val pages = mutableListOf<Page>()

        fun add(beat: StoryDirector.Beat, grown: Int) {
            // 같은 그림·같은 글이 연달아 들어가면 책이 지루해진다
            if (pages.lastOrNull()?.line == beat.line) return
            pages += Page(beat.line, beat.scene, grown)
        }

        fun place(name: String) {
            if (places.lastOrNull() != name) places += name
        }

        fun toBook() = Book(
            id = startedAt,
            destination = destination,
            minutes = minutes,
            places = places.toList(),
            pages = pages.toList(),
            timeOfDay = timeOfDay,
        )
    }

    // ──────────────────────────────── 읽고 쓰기 ────────────────────────────────

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun save(ctx: Context, book: Book): Boolean = runCatching {
        if (book.pages.size < MIN_PAGES) {
            Log.i(TAG, "장이 ${book.pages.size}개뿐이라 책으로 묶지 않는다")
            return false
        }
        File(dir(ctx), "${book.id}.json").writeText(encode(book).toString())
        Log.i(TAG, "책 저장: ${book.title} (${book.pages.size}장)")
        true
    }.getOrElse { Log.w(TAG, "책 저장 실패: ${it.message}"); false }

    /** 새 책이 위로. */
    fun list(ctx: Context): List<Book> = runCatching {
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { decode(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedByDescending { it.id }
            ?: emptyList()
    }.getOrElse { emptyList() }

    fun count(ctx: Context): Int = runCatching {
        dir(ctx).listFiles { f -> f.extension == "json" }?.size ?: 0
    }.getOrElse { 0 }

    private fun encode(b: Book) = JSONObject().apply {
        put("id", b.id)
        put("destination", b.destination ?: JSONObject.NULL)
        put("minutes", b.minutes)
        put("timeOfDay", b.timeOfDay.name)
        put("places", JSONArray(b.places))
        put("photoPath", b.photoPath ?: JSONObject.NULL)
        put("pages", JSONArray().apply {
            b.pages.forEach { p ->
                put(JSONObject().put("line", p.line).put("scene", p.scene.name).put("grown", p.grown))
            }
        })
    }

    private fun decode(o: JSONObject): Book {
        val pages = o.optJSONArray("pages") ?: JSONArray()
        return Book(
            id = o.getLong("id"),
            destination = o.optString("destination").takeIf { it.isNotBlank() && it != "null" },
            minutes = o.optInt("minutes"),
            places = (o.optJSONArray("places") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getString(it) }
            },
            pages = (0 until pages.length()).map { i ->
                val p = pages.getJSONObject(i)
                Page(p.getString("line"), sceneOf(p.optString("scene")), p.optInt("grown"))
            },
            timeOfDay = runCatching { TimeOfDay.valueOf(o.optString("timeOfDay")) }
                .getOrDefault(TimeOfDay.DAY),
            photoPath = o.optString("photoPath").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** 나중에 [SceneKind] 가 늘어나도 옛 책이 안 깨지게 한다. */
    private fun sceneOf(name: String): SceneKind =
        runCatching { SceneKind.valueOf(name) }.getOrDefault(SceneKind.ROAD)

    /** 이야기라고 부를 만한 최소 장수. 잠깐 켰다 끈 주행은 책장에 쌓지 않는다. */
    const val MIN_PAGES = 3

    /** 표지로 쓰기 좋은 순서. 앞쪽일수록 그림이 특별하다. */
    private val COVER_PREFERENCE = listOf(
        SceneKind.TUNNEL, SceneKind.FOREST, SceneKind.RAIL, SceneKind.SCHOOL,
        SceneKind.FOG, SceneKind.REST, SceneKind.CHARGE, SceneKind.BUMP,
        SceneKind.ARRIVE, SceneKind.TURN_LEFT, SceneKind.TURN_RIGHT, SceneKind.TURN,
        SceneKind.STOP, SceneKind.START,
    )
}
