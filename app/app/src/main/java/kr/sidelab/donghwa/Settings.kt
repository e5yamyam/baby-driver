package kr.sidelab.donghwa

import android.content.Context

/**
 * 아이 설정. 이야기가 아이 이름을 부르지 않으면 "내 이야기"가 되지 않는다.
 *
 * 주행 중에는 글자 입력을 할 수 없으므로(규제), 이름은 **정차 중에만** 바꾼다.
 * 개발 중에는 이렇게 넣는다:
 *   adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity --es name 현차
 */
object Settings {
    private const val FILE = "donghwa"
    private const val KEY_NAME = "child_name"
    private const val DEFAULT_NAME = "아이"
    private const val KEY_AGE = "child_age"
    private const val DEFAULT_AGE = 5

    fun childName(context: Context): String =
        prefs(context).getString(KEY_NAME, DEFAULT_NAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME

    fun setChildName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /** 아이 나이. 이야기의 수준이 여기서 갈린다. */
    fun childAge(context: Context): Int = prefs(context).getInt(KEY_AGE, DEFAULT_AGE)

    fun setChildAge(context: Context, age: Int) {
        prefs(context).edit().putInt(KEY_AGE, age.coerceIn(2, 12)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 이야기의 수준. **세 살과 일곱 살은 같은 동화를 못 듣는다** —
 * 세 살에게 복선은 지루하고, 일곱 살에게 "통통!"은 유치하다.
 *
 * 뼈대(무슨 일이 일어나는가)는 셋이 공유하고 **말하는 층만** 바뀐다.
 * 기계적으로 달라지는 건 셋이다: 대사 / 말의 양·간격 / 꼬리 질문.
 */
enum class AgeLevel {
    /** 3~4세 — 한 호흡, 의성어. 말수가 적고 사이가 길다. */
    TODDLER,

    /** 5~6세 — 두 마디, 인과, 역할 수행. 기준이 되는 층. */
    KID,

    /** 7~9세 — 복문, 추론과 선택. 꼬리 질문이 붙는다. */
    BIG;

    /** 말 사이 최소 간격. 어릴수록 길다 — 세 살에게 12분에 열 마디는 소음이다. */
    val gapMs: Long
        get() = when (this) {
            TODDLER -> 9_000L
            KID -> 5_000L
            BIG -> 4_000L
        }

    /**
     * **차가 조용할 때 본문을 한 줄 들려주는 간격.**
     * 사건이 없다고 입을 다물면, 말하는 게 전부 사건 보고가 된다 — 그건 동화가 아니다.
     * 어릴수록 길게: 세 살은 한참 창밖을 보다가 한 마디면 된다.
     */
    val idleMs: Long
        get() = when (this) {
            TODDLER -> 75_000L
            KID -> 45_000L
            BIG -> 35_000L
        }

    /**
     * **말 사이 최대 간격.** 이걸 넘기면 아이가 이야기를 놓는다.
     *
     * 어릴수록 **길다.** 세 살은 들려줄 쪽 자체가 적으므로(긴 쪽을 안 읽는다)
     * 간격을 짧게 잡으면 책이 금방 끝나고 **도착까지 입을 다문다**
     * (2026-10-31 실측: 65분 주행에서 26분 침묵). 5분에 한 마디면 충분하다.
     */
    val maxGapMs: Long
        get() = when (this) {
            TODDLER -> 300_000L
            KID -> 180_000L
            BIG -> 150_000L
        }

    companion object {
        fun of(age: Int): AgeLevel = when {
            age <= 4 -> TODDLER
            age <= 6 -> KID
            else -> BIG
        }
    }
}

/** 이름 뒤에 붙는 호격 조사. 자음으로 끝나면 "서준아", 모음으로 끝나면 "현차야" / "지우야". */
fun vocative(name: String): String {
    val last = name.trimEnd().lastOrNull() ?: return "야"
    if (last !in '가'..'힣') return "야"
    return if ((last - '가') % 28 != 0) "아" else "야"
}
