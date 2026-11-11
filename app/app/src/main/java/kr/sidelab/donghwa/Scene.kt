package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.CautionPointType

/**
 * 장면의 그림. 글 한 줄만 있으면 심심하다 —
 * 주행 중에도 **정지 그림 한 장**은 쓸 수 있다(Music/Audio 카테고리의 "앨범아트 등 단순 시각 정보").
 * 움직이는 그림은 정차 중에만 쓴다.
 */
enum class SceneKind {
    ROAD,        // 그냥 길 — 기본
    START,       // 출발
    TURN,        // 곧 기울어요(방향 모를 때 — U턴 등)
    TURN_LEFT,   // 왼쪽으로
    TURN_RIGHT,  // 오른쪽으로
    BUMP,        // 통통 튀는 언덕
    TUNNEL,      // 빛이 꺼진 동굴
    FOREST,      // 숲의 친구
    SCHOOL,      // 친구들이 다니는 길
    RAIL,        // 기차가 지나가는 길
    FOG,         // 구름이 내려온 곳
    // ⭐ 아래 둘은 **내비가 아니라 차 신호**에서 온다(2026-11-02 실측으로 값이 오는 걸 확인했다).
    //    길이 주는 장면은 한 주행에 한 번뿐인데, 이 둘은 **그날 날씨·그날 창문**이라
    //    같은 화가 비 오는 날과 맑은 날에 다른 쪽을 읽는다 — 다양성이 여기서 생긴다.
    RAIN,        // 와이퍼가 움직인다 = 비
    WIND,        // 창문이 열렸다 = 바람
    REST,        // 쉼터 마을
    STOP,        // 잠깐 멈춤
    CHARGE,      // 충전 요정
    ARRIVE,      // 도착
}

/**
 * 그림의 세계. **시즌이 정한다.**
 *
 * 이야기는 차를 "배"라고 부르고 구름이 따라온다는데 화면엔 아스팔트 위 자동차가 있었다 —
 * 두 세계가 따로 놀았다(2026-10-29 지적). 이야기가 바다면 화면도 바다여야 한다.
 */
enum class SceneTheme { ROAD, SEA }

/** 하루 중 언제인가 — 하늘 색이 바뀐다. */
enum class TimeOfDay { MORNING, DAY, DUSK, NIGHT }

fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
    in 5..8 -> TimeOfDay.MORNING
    in 9..16 -> TimeOfDay.DAY
    in 17..19 -> TimeOfDay.DUSK
    else -> TimeOfDay.NIGHT
}

fun CautionPointType.toScene(): SceneKind? = when (this) {
    CautionPointType.SPEED_BUMP -> SceneKind.BUMP
    CautionPointType.SCHOOL_ZONE -> SceneKind.SCHOOL
    CautionPointType.WILDLIFE_CROSSING -> SceneKind.FOREST
    CautionPointType.RAILROAD_CROSSING -> SceneKind.RAIL
    CautionPointType.FOG_AREA -> SceneKind.FOG
    CautionPointType.FALLING_ROCK_AREA,
    CautionPointType.DOWNHILL_CAUTION_AREA,
    CautionPointType.NARROW_ROAD,
    CautionPointType.SLIPPERY_ROAD_ALERT,
    -> SceneKind.ROAD

    else -> null
}
