package kr.sidelab.donghwa

/**
 * **문장을 어디서 가져오는가 — 두 겹 중 위층.**
 *
 * 접수 서류 3항이 약속한 모습은 "AI 가 그날 주행에 맞는 동화를 만든다" 이다.
 * 다만 **연출 규칙(어느 장면을 언제 읽을지)은 [StoryDirector] 가 그대로 쥔다.**
 * 생성이 바꾸는 것은 **그 자리에 나갈 문장뿐**이다. 그래서:
 *
 * - 생성이 늦거나 실패하거나 어린이 필터에 걸리면 → **내장 이야기가 그대로 나간다.**
 * - 생성이 붙어도 책의 순서·박자·멀미 안내 시점은 하나도 안 바뀐다.
 *
 * ⛔ **[ready] 는 기다리지 않는다.** 이야기를 내보내는 길([StoryDirector.gate])은
 *    이벤트 스레드에서 도는 동기 경로다. 거기서 LLM 을 기다리면 **차 신호가 밀린다** —
 *    멀미 선행 안내가 커브보다 늦게 나가면 그 기능은 없는 것과 같다.
 *    그래서 **미리 만들어 둔 것만 꺼내 쓴다**([prepare] 가 한 구간 앞서 채운다).
 */
interface StoryWriter {

    /**
     * [key] 자리에 내보낼 생성 문장. **준비된 게 없으면 null** — 내장 문장이 그대로 나간다.
     * 반드시 즉시 돌려줘야 한다(블로킹 금지).
     *
     * ⛔ **문장이 아니라 열쇠로 찾는다.** 처음엔 원문 문장을 그대로 열쇠로 썼는데,
     *    미리 만들어 둘 때는 원고의 **자리표시자가 든 날글**(`{아이}{호격}, 저기 봐`)이고
     *    꺼내 쓸 때는 **이름이 채워진 글**(`별이야, 저기 봐`)이라 **영원히 안 맞았다** —
     *    65분 주행에서 생성 12줄을 만들어 놓고 아이에게 나간 건 **0줄**이었다(2026-11-16 실측).
     *    열쇠는 `시즌:화:쪽:나이` 라 채워 넣기와 무관하다.
     */
    fun ready(key: String): String?

    /** 곧 나올 문장들을 미리 만들어 둔다. 비동기로 돌고, 실패는 조용히 버린다. */
    fun prepare(requests: List<Request>)

    /** 이 주행이 끝났다. 캐시를 비우고 자원을 놓는다. */
    fun close()

    /**
     * 한 장면을 생성해 달라는 요청.
     *
     * @param original 그 자리에 나갈 문장. **자리표시자가 채워진 뒤의 글**이어야 한다 —
     *                 `{아이}` 가 그대로 들어가면 모델이 그걸 베껴 오고 [ChildFilter] 가 통째로 막는다.
     * @param scene    장면 종류. 생성 지시문이 "지금 무슨 일이 일어나는가" 를 아는 유일한 근거다.
     * @param core     이야기의 뼈대인가. 뼈대는 **건드리지 않는다**(인과가 깨진다).
     * @param key      꺼내 쓸 때 쓰는 열쇠. 기본값은 [original] 이지만, 책 본문은
     *                 `시즌:화:쪽:나이` 를 쓴다([ready] 의 주석 참고).
     */
    data class Request(
        val original: String,
        val scene: SceneKind,
        val core: Boolean,
        val key: String = original,
    )

    /** 아무것도 하지 않는 층. 생성이 꺼져 있을 때 쓴다 — 내장 이야기만 나간다. */
    object None : StoryWriter {
        override fun ready(key: String): String? = null
        override fun prepare(requests: List<Request>) = Unit
        override fun close() = Unit
    }
}

/**
 * **읽기 전에 거치는 어린이용 필터.** 접수 서류 3항 안전 설계가 약속한 그것이다.
 *
 * 생성 모델은 대체로 멀쩡한 문장을 주지만, **가끔 주는 이상한 한 줄이 아이에게 간다.**
 * 그 한 줄을 막는 것이 이 필터의 전부다. 통과 못 하면 [StoryWriter.ready] 가 null 을
 * 돌려주고 내장 문장이 나간다 — **막는 쪽이 늘 안전한 쪽이다.**
 *
 * 순수 함수로 둔 이유: 에뮬레이터 없이 [StoryHarnessTest] 가 전수로 돌려 본다.
 */
object ChildFilter {

    /** 아이에게 보내지 않는 말. 무서움·폭력·죽음·협박, 그리고 운전자를 부르는 말. */
    val BANNED = listOf(
        "죽", "피가", "피를", "시체", "귀신", "괴물", "악마", "지옥", "칼", "총", "불타",
        "때려", "때리", "맞아 죽", "무서워 죽", "싫어 죽",
        "사고", "충돌", "추돌", "다쳐", "다쳤", "부딪", "위험해",
        "아빠가 봐", "엄마가 봐", "운전", "핸들", "브레이크", "앞을 봐",
    )

    /**
     * 생성 문장이 **길을 가리키는 말**을 새로 지어내면 거짓말이 된다.
     * 창밖에 없는 것을 "저기 있어" 라고 하면 아이가 창밖을 보고 속는다.
     * 그래서 원문에 없던 **장소 지시어**가 생기면 버린다.
     */
    val POINTERS = listOf("저기 ", "저쪽", "지금 보이는", "창밖에 보이는", "바로 앞에")

    /** 자리표시자가 안 채워진 채로 나가면 "{아이}야" 가 그대로 읽힌다. */
    private val PLACEHOLDER = Regex("""\{[^}]{1,12}}""")

    /** 모델이 제 설명을 붙이는 경우("물론이죠!", 따옴표 통째). */
    private val PREAMBLE = Regex("""^\s*(물론|네[,.!]|알겠|다음은|여기 |^")""")

    /** 나이별 길이 상한. 세 살에게 긴 문장은 안 들린다. */
    fun maxChars(level: AgeLevel): Int = when (level) {
        AgeLevel.TODDLER -> StoryDirector.TODDLER_MAX_CHARS
        AgeLevel.KID -> 90
        AgeLevel.BIG -> 140
    }

    /**
     * 통과하면 다듬은 문장, 막히면 null.
     *
     * @param original 내장 원고의 문장. 길이를 여기에 맞춰 재는 기준이기도 하다 —
     *                 원문보다 **두 배 넘게 길면** 모델이 혼자 이야기를 지어낸 것이다.
     */
    fun check(generated: String, original: String, level: AgeLevel): String? {
        val s = generated.trim().removeSurrounding("\"").trim()
        if (s.isEmpty()) return null
        if (PLACEHOLDER.containsMatchIn(s)) return null
        if (PREAMBLE.containsMatchIn(s)) return null
        if (s.length > maxChars(level)) return null
        // ⛔ **2배까지 봐 주면 안 됐다.** 36줄 실측에서 모델은 두 문장을 쉼표로 이어 붙여
        //    원문의 1.5~1.8배로 늘렸고, 그게 전부 통과했다 — 그림책 결이 그렇게 무너진다.
        //    지시문이 약속한 천장([StoryPrompt.budget])과 같은 줄에 둔다.
        if (s.length > StoryPrompt.budget(original) + 10) return null
        // ⛔ **"문장이 늘면 막는다" 는 규칙은 뺐다**(2026-11-16 실측 36줄). 막힌 4줄이 전부
        //    그거였는데, 넷 다 모델이 **긴 문장을 짧게 쪼갠 것**이었다 —
        //    "바람이 들어오자 … 펄럭펄럭 — 간지러워! 하고 웃었어요" → 두 문장으로.
        //    그림책에서는 그게 **더 나은 글**이다. 막아야 할 것은 사건이 붙는 것이고,
        //    그건 위의 길이 천장이 잡는다. 짧게 끊는 결은 **지시문**이 끌고 간다
        //    (`StoryPrompt.rewrite` 가 "원문처럼 짧게 끊어라" 라고 말한다).
        if (s.count { it == '\n' } > 0) return null
        if (BANNED.any { it in s }) return null
        // 원문에 없던 장소 지시어를 새로 만들면 버린다.
        if (POINTERS.any { it in s && it !in original }) return null
        // 한글이 하나도 없으면(영어·숫자 덩어리) 버린다.
        if (s.none { it in '가'..'힣' }) return null
        return s
    }
}

/**
 * **생성 지시문.** 접수 서류 3항이 말한 "주인공과 목표를 먼저 고정하고, 주행 신호를
 * 그 목표를 돕거나 방해하는 사건으로 넣는다" 를 글로 옮긴 것이다.
 *
 * 지시문을 코드에 두는 이유: 이 문장들이 **이 앱이 길안내로 떨어지지 않는 유일한 장치**다.
 * 프롬프트가 바뀌면 결과가 바뀌므로, 원고와 같은 자리에서 버전 관리한다.
 */
object StoryPrompt {

    /** 모델에게 한 번만 말해 두는 것 — 세계와 지켜야 할 선. */
    fun system(childName: String, guestName: String, vehicle: String, level: AgeLevel): String =
        buildString {
            append("너는 차 뒷좌석 아이에게 들려줄 그림책을 쓰는 작가다.\n")
            append("주인공은 $childName 이고, 함께 다니는 동행은 '$guestName', 타고 가는 것은 '$vehicle' 이다.\n")
            append("동행의 목표는 하나다: $childName 이 창밖에서 찾아 주는 것을 먹고 자라서 집에 돌아가는 것.\n")
            append("길에서 일어나는 일(커브·터널·방지턱·정차)은 그 목표를 돕거나 방해하는 사건으로 쓴다.\n")
            append("길 자체를 설명하지 않는다. '터널이 있어요' 가 아니라 '깜깜한 굴이에요' 처럼 세계 안의 말로 쓴다.\n")
            append("서술은 '~했어요 / ~였지요', 아이를 부르는 말과 동행의 대사는 반말이다.\n")
            append(
                when (level) {
                    AgeLevel.TODDLER -> "세 살에게 읽어 준다. 한 호흡으로 끝나는 짧은 문장, 의성어를 쓴다.\n"
                    AgeLevel.KID -> "다섯 살에게 읽어 준다. 두 마디 정도, 원인과 결과가 보이게 쓴다.\n"
                    AgeLevel.BIG -> "여덟 살에게 읽어 준다. 복문과 조건문을 써도 된다.\n"
                }
            )
            append("무섭거나 아프거나 위험한 말은 쓰지 않는다. 운전하는 어른을 부르지 않는다.\n")
            append("설명 없이 문장 하나만 답한다. 따옴표로 감싸지 않는다.")
        }

    /**
     * 한 장면을 다시 쓰라는 요청. **원문을 주고 결을 유지시킨다** — 혼자 이야기를 끌고 가지 못하게.
     *
     * ⛔ **"한 문장만" 이라고 하면 안 된다**(2026-11-16 실측, `tools/gen_probe.py` 36줄).
     *    원고의 한 쪽은 대개 **두 문장**이다("깜깜한 굴이에요. 그림자가 벌떡 일어났어요.").
     *    "한 문장" 을 요구하니 모델이 둘을 **쉼표로 이어 붙여** 길게 늘였다 —
     *    36줄 중 상당수가 그랬고, 그림책의 짧게 끊는 결이 통째로 사라졌다.
     *    그래서 **문장 수를 원문에 맞추고 글자 수에 천장을 준다.**
     */
    fun rewrite(req: StoryWriter.Request, destination: String?, minutesLeft: Int?): String =
        buildString {
            append("지금 장면: ${sceneWord(req.scene)}\n")
            destination?.takeIf { it.isNotBlank() }?.let { append("가는 곳: $it\n") }
            minutesLeft?.takeIf { it > 0 }?.let { append("남은 시간: 약 ${it}분\n") }
            append("아래 원문을 같은 뜻으로 다시 써라. 사건을 새로 만들지 마라.\n")
            append("문장 수는 원문과 같게 ${sentences(req.original)}문장, 전체 ${budget(req.original)}자 안으로 써라.\n")
            append("쉼표로 이어 붙이지 말고 원문처럼 짧게 끊어라.\n")
            append("원문: ${req.original}")
        }

    /** 원문의 문장 수. 마침표·물음표·느낌표로 센다(최소 1). */
    fun sentences(original: String): Int =
        original.count { it in ".?!" }.coerceAtLeast(1)

    /** 글자 수 천장. 원문보다 길어지는 건 조금만 허용한다 — 결을 유지하려면 길이가 결이다. */
    fun budget(original: String): Int = original.length + 10

    private fun sceneWord(s: SceneKind): String = when (s) {
        SceneKind.START -> "출발"
        SceneKind.TUNNEL -> "깜깜한 굴(터널)을 지난다"
        SceneKind.BUMP -> "파도가 왔다(과속방지턱)"
        SceneKind.TURN_LEFT -> "왼쪽으로 기운다"
        SceneKind.TURN_RIGHT -> "오른쪽으로 기운다"
        SceneKind.TURN -> "곧 기운다"
        SceneKind.SCHOOL -> "아이들이 다니는 길"
        SceneKind.FOREST -> "숲의 친구들"
        SceneKind.RAIL -> "기차가 지나간다"
        SceneKind.FOG -> "구름이 내려앉았다"
        SceneKind.RAIN -> "비가 온다"
        SceneKind.WIND -> "바람이 들어온다"
        SceneKind.REST -> "쉬어 가는 곳"
        SceneKind.CHARGE -> "충전하며 서 있다"
        SceneKind.STOP -> "잠깐 멈춰 섰다"
        SceneKind.ARRIVE -> "도착"
        SceneKind.ROAD -> "달리는 중"
    }
}
