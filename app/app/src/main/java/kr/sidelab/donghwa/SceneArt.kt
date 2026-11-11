package kr.sidelab.donghwa

/**
 * 장면 배경 그림과 그 **수평선 높이**.
 *
 * ⚠️ 이 파일은 `tools/paint_scenes.py --kotlin` 이 만든다. 손으로 고치지 않는다 —
 *    그림을 다시 그리면 수평선이 바뀌고, 숫자만 남으면 배가 하늘에 뜬다.
 *
 * 배경만 그림이고 **배와 손님은 [SceneView] 가 코드로 그린다**(손님은 화가 갈수록 커진다).
 * 그래서 배를 어디에 띄울지가 그림마다 필요하고, 그 값이 [Backdrop.horizon] 이다.
 *
 * 바다 시즌에만 있다. 다른 세계([SceneTheme.ROAD])는 null 이라 예전처럼 코드로 그린다.
 */
object SceneArt {

    class Backdrop(val res: Int, val horizon: Float)

    private val DAY = mapOf(
        SceneKind.ROAD to Backdrop(R.drawable.bg_sea_day, 0.461f),
        SceneKind.STOP to Backdrop(R.drawable.bg_sea_day, 0.461f),
        SceneKind.TURN to Backdrop(R.drawable.bg_sea_day, 0.461f),
        SceneKind.TURN_LEFT to Backdrop(R.drawable.bg_sea_day, 0.461f),
        SceneKind.TURN_RIGHT to Backdrop(R.drawable.bg_sea_day, 0.461f),
        SceneKind.START to Backdrop(R.drawable.bg_start_day, 0.469f),
        SceneKind.BUMP to Backdrop(R.drawable.bg_wave_day, 0.492f),
        SceneKind.TUNNEL to Backdrop(R.drawable.bg_cave_day, 0.742f),
        SceneKind.FOREST to Backdrop(R.drawable.bg_forest_day, 0.484f),
        SceneKind.SCHOOL to Backdrop(R.drawable.bg_school_day, 0.461f),
        SceneKind.RAIL to Backdrop(R.drawable.bg_rail_day, 0.492f),
        SceneKind.FOG to Backdrop(R.drawable.bg_fog_day, 0.484f),
        SceneKind.RAIN to Backdrop(R.drawable.bg_rain_day, 0.5f),
        SceneKind.WIND to Backdrop(R.drawable.bg_wind_day, 0.508f),
        SceneKind.REST to Backdrop(R.drawable.bg_rest_day, 0.508f),
        SceneKind.CHARGE to Backdrop(R.drawable.bg_charge_day, 0.477f),
        SceneKind.ARRIVE to Backdrop(R.drawable.bg_arrive_day, 0.445f),
    )

    private val NIGHT = mapOf(
        SceneKind.ROAD to Backdrop(R.drawable.bg_sea_night, 0.523f),
        SceneKind.STOP to Backdrop(R.drawable.bg_sea_night, 0.523f),
        SceneKind.TURN to Backdrop(R.drawable.bg_sea_night, 0.523f),
        SceneKind.TURN_LEFT to Backdrop(R.drawable.bg_sea_night, 0.523f),
        SceneKind.TURN_RIGHT to Backdrop(R.drawable.bg_sea_night, 0.523f),
        SceneKind.START to Backdrop(R.drawable.bg_start_night, 0.508f),
        SceneKind.BUMP to Backdrop(R.drawable.bg_wave_night, 0.5f),
        SceneKind.TUNNEL to Backdrop(R.drawable.bg_cave_night, 0.664f),
        SceneKind.FOREST to Backdrop(R.drawable.bg_forest_night, 0.594f),
        SceneKind.SCHOOL to Backdrop(R.drawable.bg_school_night, 0.508f),
        SceneKind.RAIL to Backdrop(R.drawable.bg_rail_night, 0.492f),
        SceneKind.FOG to Backdrop(R.drawable.bg_fog_night, 0.531f),
        SceneKind.RAIN to Backdrop(R.drawable.bg_rain_night, 0.453f),
        SceneKind.WIND to Backdrop(R.drawable.bg_wind_night, 0.492f),
        SceneKind.REST to Backdrop(R.drawable.bg_rest_night, 0.5f),
        SceneKind.CHARGE to Backdrop(R.drawable.bg_charge_night, 0.516f),
        SceneKind.ARRIVE to Backdrop(R.drawable.bg_arrive_night, 0.547f),
    )

    fun of(scene: SceneKind, night: Boolean, sea: Boolean): Backdrop? =
        if (!sea) null else (if (night) NIGHT else DAY)[scene]
}
