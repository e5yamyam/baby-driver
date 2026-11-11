package kr.sidelab.donghwa

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 아이 설정 — 이름과 나이.
 *
 * ## 왜 이 화면이 필요한가 (2026-11-01)
 * 이 앱의 전제는 "아이 이름을 불러 줘서 **내** 이야기가 된다" 인데, 그 입구가 없었다.
 * 이름·나이가 `adb ... --es name` 으로만 들어가서 **실제 사용자는 이름을 넣을 방법이 없었고**
 * 늘 "아이"·다섯 살로 시작했다(사용설명서를 쓰다 드러났다).
 *
 * ⚠️ [BookActivity] 와 같은 이유로 **`distractionOptimized` 를 일부러 선언하지 않는다.**
 * 글자 입력은 주행 중에 허용되지 않으므로, 막는 일을 우리 코드가 아니라 플랫폼에 맡긴다.
 *
 * ⛔ **시즌은 고르게 하지 않는다.** [Episode.of] 가 바다 시즌만 돌려주므로
 * 시즌 2·3 을 고르면 그 주행은 본문도 과제도 없이 조각만 돈다. 고를 수 있게 해 두면
 * "고장" 으로 보인다 — 그래서 지금 보고 있는 이야기를 **알려 주기만** 한다.
 *
 * 열기:
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.SettingsActivity
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var nameField: EditText

    /** 고른 나이. 쓰이는 건 층([AgeLevel])뿐이라 층을 대표하는 나이 하나를 저장한다. */
    private var age = 5

    private lateinit var choices: List<Pair<Button, Int>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        nameField = findViewById(R.id.nameField)
        nameField.setText(Settings.childName(this))
        nameField.setSelection(nameField.text.length)

        age = Settings.childAge(this)
        choices = listOf(
            findViewById<Button>(R.id.ageToddler) to 4,
            findViewById<Button>(R.id.ageKid) to 5,
            findViewById<Button>(R.id.ageBig) to 8,
        )
        choices.forEach { (button, a) ->
            button.setOnClickListener { age = a; markAge() }
        }
        markAge()

        // 고르는 칸이 아니라 알려 주는 칸이다.
        val journal = Journal(this)
        val season = Season.of(journal.seasonId)
        val ep = journal.episode.coerceAtMost(Season.EPISODES)
        findViewById<TextView>(R.id.seasonText).text =
            "${season.title}\n지금 ${ep}화 · ${Season.EPISODES}화 중"
        // 손님 이름에 조사를 붙이려면 StoryDirector 의 조사 처리를 꺼내 와야 한다.
        // 한 줄 때문에 그걸 복사하지 않고 **조사가 필요 없게** 쓴다.
        findViewById<TextView>(R.id.seasonHint).text =
            "주행 한 번이 한 화예요. ${Season.EPISODES}화에서 작별하고, 다음 시즌은 준비 중이에요."

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }

    /** 고른 층만 밝게. 나이를 숫자로 저장해도 **층이 같으면 같은 칸**이 켜져야 한다. */
    private fun markAge() {
        val picked = AgeLevel.of(age)
        choices.forEach { (button, a) ->
            val on = AgeLevel.of(a) == picked
            // ⛔ 안 고른 쪽을 **흐리게 하지 않는다.** alpha 0.45 로 깔았더니
            //    "안 고른 것"이 아니라 **"못 누르는 것"** 으로 보였다(2026-11-03).
            //    고른 것은 배경(@drawable/btn_pick)이 드러낸다.
            button.isSelected = on
        }
    }

    private fun save() {
        nameField.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?.let { Settings.setChildName(this, it) }
        Settings.setChildAge(this, age)

        // 주행 중이면 이야기꾼을 갈아끼우지 않는다 — 읽던 쪽이 처음으로 돌아간다.
        val applied = Engine.applySettings()
        val name = Settings.childName(this)
        Toast.makeText(
            this,
            if (applied) "$name${vocative(name)}, 이 이름으로 불러 줄게요."
            else "저장했어요. 다음 출발부터 이 이름으로 불러요.",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }
}
