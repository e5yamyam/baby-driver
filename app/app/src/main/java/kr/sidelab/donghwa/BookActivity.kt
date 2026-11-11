package kr.sidelab.donghwa

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 책장과 펼친 책.
 *
 * ⚠️ 이 액티비티는 **일부러 `distractionOptimized` 를 선언하지 않는다.**
 * 그러면 주행·신호대기 중에는 시스템이 알아서 막아 준다(실측: `moving`·`idling` 둘 다 `Requires DO? true`).
 * 즉 "주차 중에만 펼쳐진다" 는 규칙을 우리 코드가 아니라 플랫폼이 보증한다 —
 * 우리가 속도를 재서 직접 판단하면 놓치는 경우가 생긴다.
 *
 * 열기:
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.BookActivity
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.BookActivity --el book <id>
 */
class BookActivity : AppCompatActivity() {

    private lateinit var shelfLayer: View
    private lateinit var pageLayer: View
    private lateinit var shelf: LinearLayout
    private lateinit var shelfHint: TextView
    private lateinit var bookScene: SceneView
    private lateinit var bookText: TextView
    private lateinit var bookMeta: TextView

    private var books: List<Library.Book> = emptyList()
    private var open: Library.Book? = null
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book)
        shelfLayer = findViewById(R.id.shelfLayer)
        pageLayer = findViewById(R.id.pageLayer)
        shelf = findViewById(R.id.shelf)
        shelfHint = findViewById(R.id.shelfHint)
        bookScene = findViewById(R.id.bookScene)
        bookText = findViewById(R.id.bookText)
        bookMeta = findViewById(R.id.bookMeta)

        findViewById<Button>(R.id.prev).setOnClickListener { turn(-1) }
        findViewById<Button>(R.id.next).setOnClickListener { turn(+1) }
        findViewById<Button>(R.id.closeBook).setOnClickListener { showShelf() }

        books = Library.list(this)
        buildShelf()

        // 특정 책을 바로 열어 달라는 요청(시연·스크린샷용)
        intent.getLongExtra("book", -1L).takeIf { it > 0 }
            ?.let { id -> books.firstOrNull { it.id == id } }
            ?.let { openBook(it) }
            ?: showShelf()
    }

    // ──────────────────────────────── 책장 ────────────────────────────────

    private fun buildShelf() {
        shelf.removeAllViews()
        shelfHint.text = when {
            books.isEmpty() -> "아직 책이 없어요. 한 번 다녀오면 한 권이 생겨요."
            else -> "${books.size}권 — 다녀온 길이 책이 되었어요"
        }
        books.forEach { shelf.addView(cover(it)) }
    }

    /** 표지 하나. 그 날 제일 특별했던 장면을 작게 그려 둔다. */
    private fun cover(book: Library.Book): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(36) }
            setOnClickListener { openBook(book) }
        }

        col.addView(SceneView(this).apply {
            scene = book.cover
            time = book.timeOfDay
            // 표지는 **다 자란 손님**으로. 그 날의 끝이 표지가 되는 게 맞다.
            growth = book.pages.lastOrNull()?.grown ?: 0
            animated = false          // 표지는 멈춰 있는 게 책다워 보인다
            textZone = 0f             // 표지엔 글이 안 올라가므로 아래를 깔지 않는다
            layoutParams = LinearLayout.LayoutParams(dp(440), dp(280))
            // 모서리를 둥글게 — 각진 이미지는 '그림'이지 '책'으로 안 읽힌다
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat())
                }
            }
        })

        col.addView(label(book.title, 27f, 0xFFFFF7EC.toInt(), bold = true, topDp = 22))
        col.addView(label(book.dateText, 17f, 0x8CFFF7EC.toInt(), topDp = 8))
        col.addView(label("${book.pages.size}장 · ${book.minutes}분", 15f, 0x59FFF7EC.toInt(), topDp = 5))
        return col
    }

    private fun label(
        text: String, sizeSp: Float, color: Int,
        bold: Boolean = false, topDp: Int = 0,
    ) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topDp) }
    }

    // ──────────────────────────────── 펼친 책 ────────────────────────────────

    private fun openBook(book: Library.Book) {
        open = book
        page = 0
        shelfLayer.visibility = View.GONE
        pageLayer.visibility = View.VISIBLE
        bookScene.time = book.timeOfDay
        bookScene.animated = true     // 주차 중에만 열리는 화면이므로 움직여도 된다
        render()
    }

    private fun showShelf() {
        open = null
        pageLayer.visibility = View.GONE
        shelfLayer.visibility = View.VISIBLE
        books = Library.list(this)
        buildShelf()
    }

    private fun turn(delta: Int) {
        val book = open ?: return
        val last = book.pages.size            // 마지막 한 장은 "오늘 지나온 길"
        page = (page + delta).coerceIn(0, last)
        render()
    }

    private fun render() {
        val book = open ?: return
        val last = book.pages.size
        if (page < last) {
            val p = book.pages[page]
            bookScene.scene = p.scene
            // ⭐ 그 장에서 손님이 얼마나 컸는지까지 그린다 — 글이 "커졌어" 라고 하는데
            //    그림이 그대로면 책이 저 혼자 모순된다(2026-10-31 실측).
            bookScene.growth = p.grown
            bookText.text = p.line
            bookMeta.text = "${book.title} · ${page + 1} / ${last + 1}"
        } else {
            // 맺는 장. 다녀온 곳을 적어 둔다 — 아이가 아니라 어른이 보는 장이다.
            bookScene.scene = SceneKind.ARRIVE
            bookScene.growth = book.pages.lastOrNull()?.grown ?: 0
            bookText.text = if (book.places.isEmpty()) {
                "${book.dateText}, ${book.minutes}분을 함께 달렸어요."
            } else {
                "${book.dateText}, ${book.minutes}분.\n${book.places.joinToString(" → ")}"
            }
            bookMeta.text = "오늘 지나온 길 · ${last + 1} / ${last + 1}"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
