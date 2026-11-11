package kr.sidelab.donghwa

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType

/**
 * 차에서 들어온 신호를 "이야기가 알아듣는 말"로 바꿔 놓은 것.
 *
 * SDK 타입을 이 벽 밖으로 내보내지 않는다. 그래서
 *  - 어떤 SDK 가 아직 안 열려도(TTS·STT·FusedLocation) 나머지로 이야기가 돌아가고
 *  - 테스트에서 가짜 이벤트를 넣어 StoryDirector 만 따로 돌릴 수 있다.
 */
sealed interface CarEvent {

    /** 목적지가 정해졌다 — 이야기 길이가 여기서 정해진다. */
    data class RouteStarted(
        val destinationName: String?,
        val remainSeconds: Int,
        val remainMeters: Int,
    ) : CarEvent

    /** 남은 시간이 바뀠다(정체·우회). 이야기를 늘리거나 줄인다. */
    data class EtaChanged(
        val remainSeconds: Int,
        val remainMeters: Int,
    ) : CarEvent

    /** 곧 몸이 기운다. 멀미 케어의 입력. */
    data class TurnAhead(
        val side: Side,
        val sharp: Boolean,
        val meters: Int,
        val description: String?,
    ) : CarEvent {
        enum class Side { LEFT, RIGHT, STRAIGHT, UTURN, UNKNOWN }
    }

    /** 길 위의 소재 — 과속방지턱·야생동물·안개·철길 같은 것들. */
    data class CautionAhead(
        val kind: CautionPointType,
        val meters: Int,
    ) : CarEvent

    /**
     * 길 위의 무대 장치. TurnType 안에 섞여 들어오는 것들을 따로 뽑았다
     * (TURN_TUNNEL·TURN_TOLL·TURN_RESTAREA·TURN_ENTER_SERVICEAREA).
     */
    data class Landmark(val kind: Kind, val meters: Int) : CarEvent {
        enum class Kind { TUNNEL, TOLLGATE, REST_AREA, MERGE }
    }

    /** 지금 어디를 지나는가 — 실제 지명이 무대가 된다. */
    data class Place(val address: String?) : CarEvent

    /** 멈췄다 / 다시 움직인다. */
    data class MotionChanged(val moving: Boolean, val speedKph: Float) : CarEvent

    /** 브레이크를 밟았다. */
    data class Braking(val pressed: Boolean) : CarEvent

    /** 경유지에 닿았다 — 장(章)이 끝난다. */
    data object WaypointArrived : CarEvent

    /** 목적지에 닿았다 — 결말. */
    data object DestinationArrived : CarEvent

    /** 충전 중 배터리가 차고 있다. */
    data class ChargeLevel(val percent: Float) : CarEvent

    /**
     * **비가 온다**(와이퍼가 움직인다). 앞유리 와이퍼 상태를 그대로 쓴다.
     *
     * 왜 와이퍼인가: 비 센서 값은 Vehicle SDK 에 없고, 있어도 "비가 오는지"는
     * **사람이 와이퍼를 켠 것**으로 가장 확실히 안다. 2026-11-02 실측에서
     * `WiperState` 는 값이 오는 신호였다(OFF/ON/SERVICE · 앞·뒤 2곳).
     */
    data class Rain(val falling: Boolean) : CarEvent

    /**
     * **창문이 열렸다** = 바람이 들어온다.
     *
     * ⚠️ 창문 위치는 **0~10** 이다(0~100 이 아니다 — 2026-11-02 실측).
     *    백분율로 알고 임계값을 30 쯤 잡으면 **영원히 안 열린 것**이 된다.
     */
    data class Breeze(val open: Boolean) : CarEvent

    /**
     * **뒷좌석에 누가 앉아 있나.** 혼자 가는 길과 둘이 가는 길은 다른 이야기다.
     *
     * ⚠️ 에뮬레이터 기본값이 `true` 다(2026-11-02 실측). "앉아야 켜진다"를 조건으로 걸면
     *    에뮬레이터에선 늘 참이니, **없을 때 빼는 쪽으로만** 쓴다.
     */
    data class Riders(val rear: Int) : CarEvent

    /**
     * **지금 꺾는다** — 깜빡이. [side] 가 null 이면 다 꺾었다는 뜻.
     *
     * 내비의 [TurnAhead] 는 "곧 꺾어요" 라는 **예고**(멀미 선행 안내)다.
     * 이건 그 커브가 **실제로 오는 순간**이고, 그래서 화면의 배가 그때 기운다.
     * 예고와 실제를 둘 다 가진 건 차 안에서만 되는 일이다.
     *
     * @param degrees 그 순간의 핸들 각도(절댓값). 얼마나 기울지 정한다.
     *   ⚠️ 핸들 각도 자체는 **이벤트로 내보내지 않는다** — 주행 중 쉴 새 없이 바뀌어서
     *   그대로 흘리면 이야기가 그것만 받는다. [CarEventHub] 가 적어 두었다가 이때 읽는다.
     */
    data class Turning(val side: Side?, val degrees: Float) : CarEvent {
        enum class Side { LEFT, RIGHT }

        /**
         * 화면의 배가 얼마나 기우나. **-1(왼쪽) ~ +1(오른쪽)**, 다 꺾었으면 0.
         *
         * 세기는 그 순간의 핸들 각도가 정한다 — 크게 꺾은 커브에서 더 기운다.
         * 차선 변경처럼 살짝 돌린 것과 같을 수는 없다.
         *
         * ⚠️ 바닥([FLOOR])을 두는 이유: 각도가 0 으로 오는 차에서도 **깜빡이만으로 기울긴 해야**
         *    한다. 바닥이 없으면 그런 차에서는 이 기능이 통째로 조용히 사라진다.
         */
        val lean: Float
            get() {
                val dir = when (side) {
                    Side.LEFT -> -1f
                    Side.RIGHT -> 1f
                    null -> return 0f
                }
                return dir * (degrees / STEER_FULL).coerceIn(FLOOR, 1f)
            }

        companion object {
            /** 핸들을 이만큼 돌리면 '끝까지 꺾었다'로 본다(도). 실측 범위는 -470~500. */
            const val STEER_FULL = 360f

            /** 각도가 안 와도 이만큼은 기운다. */
            const val FLOOR = 0.35f
        }
    }

    /** 주행 규제 상태가 바뀠다. 글자·그림을 줄여야 하는지. */
    data class RestrictionChanged(val restricted: Boolean) : CarEvent

    data class Failed(val where: String, val reason: String) : CarEvent
}

/** Pleos 의 TurnType(40여 개)을 이야기에 필요한 만큼으로 줄인다. */
fun TurnType.toSide(): CarEvent.TurnAhead.Side = when (this) {
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_KEEP_L,
    TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L,
    TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_AVOID_UNDER_L,
    TurnType.TURN_ENTER_OVER_L, TurnType.TURN_AVOID_OVER_L,
    -> CarEvent.TurnAhead.Side.LEFT

    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R,
    TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R,
    TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_AVOID_UNDER_R,
    TurnType.TURN_ENTER_OVER_R, TurnType.TURN_AVOID_OVER_R,
    -> CarEvent.TurnAhead.Side.RIGHT

    TurnType.TURN_STRAIGHT -> CarEvent.TurnAhead.Side.STRAIGHT
    TurnType.TURN_UTURN -> CarEvent.TurnAhead.Side.UTURN
    else -> CarEvent.TurnAhead.Side.UNKNOWN
}

fun TurnType.isSharp(): Boolean =
    this == TurnType.TURN_SHARP_L || this == TurnType.TURN_SHARP_R || this == TurnType.TURN_UTURN

/** 턴이 아니라 무대 장치인 TurnType 들. */
fun TurnType.toLandmark(): CarEvent.Landmark.Kind? = when (this) {
    TurnType.TURN_TUNNEL -> CarEvent.Landmark.Kind.TUNNEL
    TurnType.TURN_TOLL -> CarEvent.Landmark.Kind.TOLLGATE
    TurnType.TURN_RESTAREA, TurnType.TURN_ENTER_SERVICEAREA -> CarEvent.Landmark.Kind.REST_AREA
    TurnType.TURN_DEVIDE_MERGE -> CarEvent.Landmark.Kind.MERGE
    else -> null
}
