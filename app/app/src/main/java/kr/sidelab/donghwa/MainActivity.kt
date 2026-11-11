package kr.sidelab.donghwa

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 0.1 단계의 목표는 하나다: **어떤 차 이벤트가 실제로 오는지 눈으로 확인하는 것.**
 * 문서가 "실제로 전달되는 이벤트는 Navigation 앱 구현과 현재 상태에 따라 달라질 수 있다"고 했으므로
 * 받은 것을 전부 화면과 logcat 에 남긴다.
 *
 *   adb logcat -s DonghwaCar DonghwaTts
 *
 * 화면은 엔진([Engine])에 붙었다 떼지기만 한다 — 액티비티가 다시 만들어져도 이야기는 한 벌이다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sceneText: TextView
    private lateinit var statusText: TextView
    private lateinit var sceneView: SceneView
    private lateinit var shelfButton: Button
    private lateinit var parkedBar: View

    private val main = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            Engine.poll()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sceneText = findViewById(R.id.sceneText)
        statusText = findViewById(R.id.statusText)
        // 들어온 이벤트 개수는 **개발용 계기판**이다. 시연에서도 끈다 —
        // 영상에 그림 밑으로 영문 약어 한 줄이 그대로 찍힌다(2026-11-03 스크린샷).
        //   보려면: adb shell am start ... --ez debug true
        statusText.visibility =
            if (intent.getBooleanExtra("debug", false)) View.VISIBLE else View.GONE
        sceneView = findViewById(R.id.scene)
        shelfButton = findViewById(R.id.shelfButton)
        parkedBar = findViewById(R.id.parkedBar)
        shelfButton.setOnClickListener { startActivity(Intent(this, BookActivity::class.java)) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 아이 이름 — 주행 중엔 글자 입력이 막히므로 정차 중에만 바꾼다
        intent.getStringExtra("name")?.takeIf { it.isNotBlank() }
            ?.let { Settings.setChildName(this, it) }
        // 나이는 이야기의 수준을 가른다. 개발 중에는 --ei age 3 처럼 넣는다
        intent.getIntExtra("age", 0).takeIf { it > 0 }?.let { Settings.setChildAge(this, it) }
        // 시연에서 화를 건너뛴다 — 작별(12화)을 보려고 열한 번 달릴 수는 없다
        intent.getIntExtra("episode", 0).takeIf { it > 0 }?.let { Journal(this).setEpisode(it) }

        // ⚠️ CAR_SPEED 는 런타임 권한이다. 받지 않으면 CarPropertyManager 가 속도를 목록에조차
        //    넣어 주지 않는다(2026-10-29 실측: 권한 없이 6개 / 받으면 9개 — PERF_VEHICLE_SPEED 포함).
        //    헤드리스 에뮬레이터에선 대화상자를 누를 수 없으니 개발 중에는 adb 로 준다:
        //      adb shell pm grant --user 10 kr.sidelab.donghwa android.car.permission.CAR_SPEED
        if (checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(SPEED_PERMISSION), REQ_SPEED)
        }
        // ⚠️ 엔진을 액티비티에서 켜지 않는다. **서비스가 켠다.**
        //    Pleos 에서는 내비와 우리 앱이 같은 창을 쓰므로(실측 [853,90]~[2535,1300]),
        //    주행 중에 화면을 가져오면 지도가 사라진다. 이야기는 화면 없이도 돌아야 한다.
        StoryService.start(this) {
            intent.getStringExtra("name")?.let { putExtra("name", it) }
            putExtra("route", intent.getBooleanExtra("route", false))
            putExtra("demo", intent.getBooleanExtra("demo", false))
            intent.getStringExtra("script")?.let { putExtra("script", it) }
            // 시연 하늘 고르기 — `--es sky night`. 밤 그림을 눈으로 확인하고 영상도 찍는다.
            intent.getStringExtra("sky")?.let { putExtra("sky", it) }
            // 시연 대본 배율 — 영상 녹화에서만 쓴다(`--ef stretch 1.7`)
            intent.getFloatExtra("stretch", 1f).takeIf { it != 1f }?.let { putExtra("stretch", it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SPEED) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "CAR_SPEED granted=$ok")
        }
    }

    private var observer: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        observer = Engine.observe(::render)
        render()
        main.postDelayed(poll, POLL_MS)
    }

    override fun onStop() {
        main.removeCallbacks(poll)
        Engine.unobserve(observer)
        observer = null
        super.onStop()
    }

    private fun render() {
        Engine.lastLine?.let { sceneText.text = it }
        statusText.text = Engine.statusLine()
        sceneView.scene = Engine.scene
        sceneView.time = Engine.timeOfDayNow()
        sceneView.theme = Engine.theme
        sceneView.growth = Engine.companionGrowth
        sceneView.lean = Engine.lean
        sceneView.animated = Engine.parked

        // 누를 것은 세워 둔 동안에만 내놓는다. 주행 중엔 화면에 아무 버튼도 없어야 한다.
        parkedBar.visibility = if (Engine.parked) View.VISIBLE else View.GONE
        // 책장은 책이 한 권이라도 있어야 뜻이 있다. 설정은 처음부터 필요하다.
        val books = Engine.bookCount > 0
        shelfButton.visibility = if (books) View.VISIBLE else View.GONE
        if (books) shelfButton.text = "책장 ${Engine.bookCount}권"
    }

    companion object {
        const val TAG = "DonghwaCar"
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        const val REQ_SPEED = 1001

        /** 푸시로만 오지 않는 값(TBT·남은시간·현재위치)을 당겨 오는 주기. */
        const val POLL_MS = 5_000L
    }
}
