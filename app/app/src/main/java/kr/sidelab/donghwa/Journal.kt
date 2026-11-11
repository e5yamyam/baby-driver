package kr.sidelab.donghwa

import android.content.Context
import android.content.SharedPreferences

/**
 * 작은 저장소 한 겹.
 *
 * 왜 한 겹을 두나: 이야기 박자(쪽을 언제 넘기나)는 **실주행 한 시간을 그대로 흘려 봐야** 검증된다.
 * 그걸 에뮬레이터로 하면 한 번에 한 시간이고, 시연처럼 압축 재생하면
 * **실주행에서만 나는 버그가 숨는다**(2026-10-31: 압축 재생에선 `idleMs` 가 한 번도 안 걸렸다).
 * 그래서 저장소를 갈아 끼울 수 있게 해 두고, 가짜 시계로 주행 수십 번을 돌린다.
 */
interface Store {
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)
}

private class PrefStore(private val sp: SharedPreferences) : Store {
    override fun getInt(key: String, def: Int) = sp.getInt(key, def)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun getString(key: String, def: String) = sp.getString(key, def) ?: def
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
}

/**
 * **같은 길을 몇 번째 지나는지 기억하는 곳.**
 *
 * ## 왜 이게 핵심인가
 * 등원길은 매일 같은 터널, 같은 학교 앞, 같은 커브다. 신호가 똑같으니 그냥 두면 같은 말이 반복되고,
 * 아이는 사흘이면 패턴을 안다. 그래서 뒤집었다 —
 * **매일 같은 곳을 지나는 걸 약점이 아니라 재료로 쓴다.**
 *
 * 그 터널을 처음 지나는지 열 번째 지나는지 아는 건 **차 안의 앱뿐**이다.
 * TV 동화는 "우리 아이가 매일 지나는 그 터널"을 모른다.
 *
 * 화(話) 번호도 여기 있다. 한 번의 주행이 한 화이고, 도착하면 하나 올라간다.
 */
class Journal(private val store: Store) {

    constructor(ctx: Context) : this(
        PrefStore(ctx.getSharedPreferences("journal", Context.MODE_PRIVATE)),
    )

    /** 지금 몇 화인가. 1부터. */
    val episode: Int get() = store.getInt(KEY_EPISODE, 1)

    fun nextEpisode() = store.putInt(KEY_EPISODE, episode + 1)

    /**
     * **시연·시험용으로 화를 직접 놓는다.** 평소에는 [nextEpisode] 로만 오른다.
     *
     * 2차 발표에서 "작별을 보여 주세요" 를 들으면 열한 번 주행할 수가 없다:
     *   adb shell am start --user 10 -n kr.sidelab.donghwa/.MainActivity --ei episode 12
     */
    fun setEpisode(n: Int) = store.putInt(KEY_EPISODE, n.coerceAtLeast(1))

    /**
     * 그 자리를 몇 번째 지나는가. **세면서 돌려준다**(처음이면 1).
     *
     * @param kind  무엇인지 — "TUNNEL" "SCHOOL" 같은 것
     * @param where 어디쯤인지. 지명이 있으면 **그 터널**이 되고, 없으면 "터널 일반"이 된다.
     *              매일 같은 길이면 지명이 안정적이라 사실상 그 터널 하나를 센다.
     */
    fun visit(kind: String, where: String?): Int {
        val key = "$PLACE$kind@${where ?: "-"}"
        val n = store.getInt(key, 0) + 1
        store.putInt(key, n)
        return n
    }

    /** 세지 않고 보기만 한다. */
    fun visits(kind: String, where: String?): Int =
        store.getInt("$PLACE$kind@${where ?: "-"}", 0)

    /** 지금 보고 있는 시즌. 시즌이 탈것과 동행을 정한다. */
    var seasonId: String
        get() = store.getString(KEY_SEASON, Season.DEFAULT.id)
        set(v) = store.putString(KEY_SEASON, v)

    private companion object {
        const val KEY_EPISODE = "episode"
        const val KEY_SEASON = "season"
        const val PLACE = "p."
    }
}

/**
 * 시즌 — 하나의 큰 이야기. **탈것과 동행을 시즌이 정한다.**
 *
 * 차를 늘 "배"라고 부를 이유가 없었다(첫 판이 그랬다).
 * 바다 시즌이면 배, 하늘 시즌이면 비행선, 숲 시즌이면 수레다.
 * 동행도 날마다 뽑지 않는다 — **시즌 내내 같은 친구**여야 정이 든다.
 */
class Season(
    val id: String,
    val title: String,
    /** 아이가 이름을 지어 주기 전까지 부르는 말. */
    val vehicle: String,
    val guest: Guest,
    /** 화면의 세계. 이야기와 그림이 같은 세계여야 한다. */
    val theme: SceneTheme = SceneTheme.SEA,
) {
    companion object {
        val SEA = Season("sea", "구름을 집에 보내 주는 이야기", "배", Guest.CLOUD)
        val SKY = Season("sky", "불빛을 찾아서", "비행선", Guest.FIREFLY, SceneTheme.ROAD)
        val WOOD = Season("wood", "편지 배달", "수레", Guest.SNAIL, SceneTheme.ROAD)

        /** 한 시즌의 길이. 이 화에 닿으면 작별하고 시즌이 끝난다. */
        const val EPISODES = 12

        val ALL = listOf(SEA, SKY, WOOD)
        val DEFAULT = SEA

        fun of(id: String): Season = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
