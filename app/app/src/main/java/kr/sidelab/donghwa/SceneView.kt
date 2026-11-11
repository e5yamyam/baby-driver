package kr.sidelab.donghwa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 장면을 그린다. 그림 파일을 쓰지 않고 코드로 그린다 —
 * 통신 없이도 끝까지 가야 하고, 어떤 화면 크기에도 또렷해야 하기 때문이다.
 *
 * 주행 중에는 **멈춘 그림**만 보여 준다(움직이는 그림은 시선을 끈다).
 * 정차 중에만 [animated] 를 켜서 아주 느리게 흔들리게 한다.
 *
 * ## 그림의 방향 (2026-10-29 다시 잡음)
 * 처음 판은 **채도 높은 기본색 + 납작한 도형**이라 그림책이 아니라 도표처럼 보였다. 네 가지를 바꿨다.
 *
 * 1. **구아슈(물감) 팔레트** — 순색을 쓰지 않는다. 시간대마다 채도를 낮춘 한 벌로 묶는다.
 * 2. **공기원근** — 먼 언덕일수록 밝고 옅다. 디테일이 아니라 **명도 차이**로 거리를 만든다.
 * 3. **종이 질감** — 전체에 아주 옅은 노이즈를 덮는다. 벡터가 인쇄물처럼 보이는 가장 싼 방법이다.
 * 4. **주인공** — 길만 있고 아무도 없었다. **뒤에서 본 작은 차**를 올려서 "우리가 간다"가 되게 했다.
 *    이야기가 차를 "배"라고 부르므로(`배가 오른쪽으로 기울어요`) 둥근 장난감처럼 그린다.
 *
 * 글 영역의 어둡게 깔기(vignette)도 여기서 그린다 — 레이아웃의 검은 띠를 쓰면
 * 팔레트와 따로 놀아서 그림 위에 검은 막대를 올린 것처럼 보였다.
 */
class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    var scene: SceneKind = SceneKind.ROAD
        set(value) {
            if (field != value) {
                field = value; invalidate()
            }
        }

    /** 하늘 색. 평소엔 실제 시각을 따르고, 시연에서는 장면에 맞게 정해 준다. */
    var time: TimeOfDay = TimeOfDay.DAY
        set(value) {
            if (field != value) {
                field = value; invalidate()
            }
        }

    /** 그림의 세계. 바다 시즌이면 길이 물길이 되고 차가 배가 된다. */
    var theme: SceneTheme = SceneTheme.SEA
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 동행이 얼마나 자랐나(0부터). **화면에서 구름이 실제로 커진다.** */
    var growth: Int = 0
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /**
     * 배가 기울어 있는 정도. -1(왼쪽) ~ +1(오른쪽). 깜빡이가 켜진 동안만 0 이 아니다.
     * 길이 휜 것([bend], 내비 예고)과 **더해진다** — 예고한 커브가 실제로 올 때 더 기운다.
     */
    var lean: Float = 0f
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** 정차 중에만 켠다. */
    var animated: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) anim.start() else { anim.cancel(); phase = 0f; invalidate() }
        }

    /**
     * 아래쪽 몇 %를 글 자리로 비워 둘지. 그 구간은 어둡게 깔아 글이 읽히게 한다.
     * 표지처럼 글이 안 올라가는 곳에서는 0 으로 둔다.
     */
    var textZone: Float = 0.26f
        set(value) {
            if (field != value) {
                field = value; invalidate()
            }
        }

    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 7_000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    // ──────────────────────────────── 팔레트 ────────────────────────────────

    /** 한 시간대의 색 한 벌. 낱개로 고르지 않고 벌로 묶어야 그림이 따로 놀지 않는다. */
    private class Palette(
        val skyTop: Int, val skyBot: Int,
        val hillFar: Int, val hillMid: Int,
        val ground: Int, val groundNear: Int,
        val road: Int, val shoulder: Int, val line: Int,
        val ink: Int, val accent: Int, val glow: Int,
        val dark: Int,
        /** 바다 테마 — 먼 물 / 가까운 물 / 배가 지나는 물길 */
        val sea: Int, val seaNear: Int, val lane: Int,
    )

    private val morning = Palette(
        skyTop = 0xFF9FC3DC.toInt(), skyBot = 0xFFFBE3C0.toInt(),
        hillFar = 0xFFB9C9A6.toInt(), hillMid = 0xFF9FB389.toInt(),
        ground = 0xFFB3C396.toInt(), groundNear = 0xFFA2B384.toInt(),
        road = 0xFF8B8178.toInt(), shoulder = 0xFFB4A48D.toInt(), line = 0xFFF6EFE0.toInt(),
        ink = 0xFF4A4438.toInt(), accent = 0xFFD2825A.toInt(), glow = 0xFFF8D59A.toInt(),
        dark = 0xFF2A2822.toInt(),
        sea = 0xFF6FA3BE.toInt(), seaNear = 0xFF54879F.toInt(), lane = 0xFF8EBDD2.toInt(),
    )
    private val day = Palette(
        skyTop = 0xFF8FB9D6.toInt(), skyBot = 0xFFDCEAE8.toInt(),
        hillFar = 0xFFB2C49E.toInt(), hillMid = 0xFF95AC7F.toInt(),
        ground = 0xFFAEBE8E.toInt(), groundNear = 0xFF9DAE7D.toInt(),
        road = 0xFF857C73.toInt(), shoulder = 0xFFAD9D87.toInt(), line = 0xFFF6EFE0.toInt(),
        ink = 0xFF45402F.toInt(), accent = 0xFFC9794F.toInt(), glow = 0xFFF5D497.toInt(),
        dark = 0xFF262419.toInt(),
        sea = 0xFF68A6C4.toInt(), seaNear = 0xFF4F88A4.toInt(), lane = 0xFF93C4D8.toInt(),
    )
    private val dusk = Palette(
        skyTop = 0xFF5D5680.toInt(), skyBot = 0xFFF0B183.toInt(),
        hillFar = 0xFFA08A94.toInt(), hillMid = 0xFF7F6F7D.toInt(),
        ground = 0xFF706272.toInt(), groundNear = 0xFF615467.toInt(),
        road = 0xFF5B5362.toInt(), shoulder = 0xFF7C6E7B.toInt(), line = 0xFFEADCC8.toInt(),
        ink = 0xFF2E2836.toInt(), accent = 0xFFE0906A.toInt(), glow = 0xFFFFD9A0.toInt(),
        dark = 0xFF201C28.toInt(),
        sea = 0xFF53577E.toInt(), seaNear = 0xFF3E4365.toInt(), lane = 0xFF6F6E98.toInt(),
    )
    private val night = Palette(
        skyTop = 0xFF141D33.toInt(), skyBot = 0xFF31416A.toInt(),
        hillFar = 0xFF2C3859.toInt(), hillMid = 0xFF1F2A46.toInt(),
        ground = 0xFF1B2439.toInt(), groundNear = 0xFF161E31.toInt(),
        road = 0xFF272E42.toInt(), shoulder = 0xFF2E3348.toInt(), line = 0xFFBFC4D4.toInt(),
        ink = 0xFF0D1220.toInt(), accent = 0xFF8FA3C9.toInt(), glow = 0xFFFFE2A8.toInt(),
        dark = 0xFF080C16.toInt(),
        sea = 0xFF16213A.toInt(), seaNear = 0xFF101828.toInt(), lane = 0xFF24324F.toInt(),
    )

    private val p: Palette
        get() = when (time) {
            TimeOfDay.MORNING -> morning
            TimeOfDay.DAY -> day
            TimeOfDay.DUSK -> dusk
            TimeOfDay.NIGHT -> night
        }

    private val isNight get() = time == TimeOfDay.NIGHT

    // ──────────────────────────────── 종이 질감 ────────────────────────────────

    /**
     * 아주 옅은 노이즈를 한 번 만들어 두고 타일로 덮는다.
     * 이거 하나로 "납작한 벡터"가 "인쇄된 그림"처럼 보인다.
     */
    private val grainPaint: Paint by lazy {
        val n = 96
        val px = IntArray(n * n)
        var seed = 20261029
        for (i in px.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            px[i] = when (seed % 100) {
                in 0..5 -> 0x10000000
                in 6..11 -> 0x0EFFFFFF
                else -> 0
            }
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    // ──────────────────────────────── 그리기 ────────────────────────────────

    /** 바다 테마인가. 자주 쓰므로 한 줄로. */
    private val sea get() = theme == SceneTheme.SEA

    // ── 그려 둔 그림 ──
    // 세계(바다·섬·굴)는 배경 한 장, **배와 손님은 오려 둔 그림 한 장씩**이다.
    //
    // 처음엔 배와 손님을 코드로 그렸다. 손님은 화가 갈수록 커지고 배는 기울어야 하는데
    // 구운 그림은 자라지도 기울지도 않는다고 봤기 때문이다. 틀렸다 —
    // **바뀌는 건 모양이 아니라 크기·각도·자리뿐**이라 행렬 하나로 다 된다(`drawBitmap(bmp, Matrix, …)`).
    // 그동안은 수채화 배경 위에 납작한 벡터 배가 올라가 **붓이 두 개**였다(2026-11-03 화면 시안).
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgSrc = Rect()
    private var bgRes = 0
    private var bgBitmap: Bitmap? = null

    /** 오려 둔 그림을 한 번만 풀어 둔다. 장면마다 다시 풀면 넘길 때 걸린다. */
    private val sprites = HashMap<Int, Bitmap?>()
    private val spriteM = Matrix()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * 밤에 쓰는 색조. 낮에 그린 그림을 그대로 올리면 **한밤에 혼자 환한 배**가 된다.
     * 빨강·초록을 파랑보다 더 깎아 어둡고 푸르게 민다(달빛).
     */
    private val nightTint = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.60f, 0f, 0f, 0f, 8f,
                0f, 0.64f, 0f, 0f, 12f,
                0f, 0f, 0.78f, 0f, 30f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    private fun sprite(res: Int): Bitmap? {
        // ⚠️ `getOrPut` 을 쓰면 안 된다 — 값이 null 이면 **넣어 두고도 매번 다시 시도**한다.
        //    그림 풀기가 실패하는 상황(메모리 부족)에서 프레임마다 다시 풀려 들다가 더 느려진다.
        if (!sprites.containsKey(res)) {
            sprites[res] = runCatching { BitmapFactory.decodeResource(resources, res) }.getOrNull()
        }
        return sprites[res]
    }

    /**
     * 오려 둔 그림을 **밑동 가운데를 축으로** 올린다.
     *
     * @param cx 가로 가운데, [baseY] 밑동이 닿을 높이, [targetW] 그려질 가로폭
     * @param deg 기울기(도). 밑동이 축이라 배가 바닥에서 미끄러지지 않고 기운다.
     */
    private fun drawSprite(canvas: Canvas, bmp: Bitmap, cx: Float, baseY: Float,
                           targetW: Float, deg: Float = 0f) {
        val sh = targetW * bmp.height / bmp.width
        spriteM.reset()
        spriteM.postScale(targetW / bmp.width, sh / bmp.height)
        spriteM.postTranslate(-targetW / 2f, -sh)      // 밑동 가운데가 원점
        if (deg != 0f) spriteM.postRotate(deg)
        spriteM.postTranslate(cx, baseY)
        spritePaint.colorFilter = if (isNight) nightTint else null
        canvas.drawBitmap(bmp, spriteM, spritePaint)
    }

    /**
     * 배경을 깔고 **그 그림의 수평선이 화면 몇 px 인지** 돌려준다.
     *
     * 비율이 안 맞으면 가운데를 잘라 채우는데(세로 화면), 그러면 수평선도 같이 움직인다.
     * 그래서 비율에서 **다시 계산한다** — 그림 속 비율을 그대로 쓰면 배가 물에서 뜬다.
     */
    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float, art: SceneArt.Backdrop): Float {
        if (bgRes != art.res || bgBitmap == null) {
            bgBitmap = BitmapFactory.decodeResource(resources, art.res)
            bgRes = art.res
        }
        val bmp = bgBitmap ?: return h * 0.56f
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = max(w / bw, h / bh)
        val srcLeft = (bw - w / scale) / 2f
        val srcTop = (bh - h / scale) / 2f
        bgSrc.set(
            srcLeft.toInt(), srcTop.toInt(),
            (bw - srcLeft).toInt(), (bh - srcTop).toInt(),
        )
        canvas.drawBitmap(bmp, bgSrc, RectF(0f, 0f, w, h), bgPaint)
        return ((art.horizon * bh - srcTop) * scale).coerceIn(h * 0.30f, h * 0.85f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sway = if (animated) sin(phase) * w * 0.010f else 0f
        bend = when (scene) {
            SceneKind.TURN_LEFT -> -1f
            SceneKind.TURN_RIGHT -> 1f
            else -> 0f
        }

        val art = SceneArt.of(scene, isNight, sea)
        val horizon = if (art != null) drawBackdrop(canvas, w, h, art) else h * 0.56f

        if (art == null) {
            drawSky(canvas, w, horizon)
            if (isNight) drawStars(canvas, w, horizon)
            drawSunOrMoon(canvas, w, horizon)
            if (!isNight) drawClouds(canvas, w, horizon, sway)

            drawHills(canvas, w, horizon, sway)
            drawGround(canvas, w, h, horizon)
            drawRoad(canvas, w, h, horizon)

            if (scene == SceneKind.FOG) drawFog(canvas, w, horizon)

            // 무대 장치도 그림 안에 이미 있다 — 그림이 있을 땐 올리지 않는다.
            when (scene) {
                SceneKind.TUNNEL -> drawTunnel(canvas, w, h, horizon)
                SceneKind.FOREST -> drawForest(canvas, w, h, horizon, sway)
                SceneKind.SCHOOL -> drawSchool(canvas, w, h, horizon)
                SceneKind.RAIL -> drawRail(canvas, w, h, horizon)
                SceneKind.REST -> drawRest(canvas, w, h, horizon)
                SceneKind.BUMP -> drawBump(canvas, w, h, horizon)
                SceneKind.ARRIVE -> drawGate(canvas, w, h, horizon)
                SceneKind.CHARGE -> drawCharge(canvas, w, h, horizon, sway)
                SceneKind.RAIN -> drawRain(canvas, w, h)
                SceneKind.WIND -> drawWind(canvas, w, horizon, sway)
                SceneKind.TURN, SceneKind.TURN_LEFT, SceneKind.TURN_RIGHT -> Unit  // 길이 휜다(bend)
                SceneKind.START, SceneKind.STOP, SceneKind.ROAD, SceneKind.FOG -> Unit
            }
        }

        // 한때 여기서 배를 **반투명하게** 깔았다 — 납작한 벡터가 물감 그림 위에 오려 붙인 듯 떠서
        // 종이를 비쳐 보이게 눌러 둔 것이다. 지금은 배도 손님도 같은 붓으로 그린 그림이라
        // 비칠 이유가 없다. 비치게 두면 배 너머로 파도가 지나간다.
        if (sea) drawBoat(canvas, w, h, horizon, sway) else drawCar(canvas, w, h, horizon, sway)
        drawCompanion(canvas, w, h, horizon, sway)

        // 종이 질감 → 그 다음 글 자리 어둡게. 순서가 바뀌면 글 위에 노이즈가 낀다.
        // ⛔ **그려 둔 그림 위에는 깔지 않는다.** 그 그림에는 이미 종이결이 칠해져 있어서
        //    두 번 덮이고, 2560px 짜리 차 화면에서는 모래를 뿌린 것처럼 보인다(2026-11-03).
        //    노이즈는 **코드로 그린 납작한 세계**를 인쇄물처럼 보이게 하려던 장치였다.
        if (art == null) canvas.drawRect(0f, 0f, w, h, grainPaint)
        drawTextScrim(canvas, w, h)
    }

    private fun drawSky(canvas: Canvas, w: Float, horizon: Float) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, horizon * 1.05f, p.skyTop, p.skyBot, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, horizon + 1f, paint)
        paint.shader = null
    }

    /** 해(달)는 지평선 가까이 낮게. 하늘이 비어 보이지 않게 하는 가장 싼 장치다. */
    private fun drawSunOrMoon(canvas: Canvas, w: Float, horizon: Float) {
        val cx = w * 0.72f
        val cy = horizon * when (time) {
            TimeOfDay.MORNING -> 0.46f
            TimeOfDay.DAY -> 0.26f
            TimeOfDay.DUSK -> 0.62f
            TimeOfDay.NIGHT -> 0.30f
        }
        val r = horizon * if (isNight) 0.072f else 0.085f
        paint.shader = android.graphics.RadialGradient(
            cx, cy, r * 2.6f,
            p.glow and 0x4DFFFFFF, p.glow and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 2.6f, paint)
        paint.shader = null
        paint.color = p.glow
        canvas.drawCircle(cx, cy, r, paint)
        if (isNight) {                       // 초승달 — 같은 색으로 한 입 베어 문다
            paint.color = p.skyTop
            canvas.drawCircle(cx + r * 0.42f, cy - r * 0.22f, r * 0.92f, paint)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, horizon: Float, sway: Float) {
        paint.color = 0x59FFFFFF
        for ((i, spec) in listOf(0.18f to 0.30f, 0.46f to 0.17f, 0.86f to 0.38f).withIndex()) {
            val (fx, fy) = spec
            val cx = w * fx + if (i % 2 == 0) sway * 1.6f else -sway * 1.2f
            val cy = horizon * fy
            val r = horizon * (0.055f + 0.018f * (i % 2))
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx + r * 1.05f, cy + r * 0.14f, r * 0.78f, paint)
            canvas.drawCircle(cx - r * 0.95f, cy + r * 0.18f, r * 0.66f, paint)
            canvas.drawRect(cx - r * 0.95f, cy + r * 0.12f, cx + r * 1.05f, cy + r * 0.8f, paint)
        }
    }

    private fun drawStars(canvas: Canvas, w: Float, horizon: Float) {
        var seed = 7
        repeat(46) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val x = (seed % 1000) / 1000f * w
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val y = (seed % 1000) / 1000f * horizon * 0.82f
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val big = seed % 7 == 0
            paint.color = if (big) 0xE6FFFFFF.toInt() else 0x8CFFFFFF.toInt()
            canvas.drawCircle(x, y, w * if (big) 0.0022f else 0.0012f, paint)
        }
    }

    /** 언덕 세 겹. 먼 것일수록 밝다 — 공기원근. 디테일 없이 깊이를 만드는 방법이다. */
    private fun drawHills(canvas: Canvas, w: Float, horizon: Float, sway: Float) {
        if (sea) { drawIslands(canvas, w, horizon, sway); return }
        paint.color = p.hillFar
        path.reset()
        path.moveTo(-w * 0.1f, horizon)
        path.quadTo(w * 0.30f + sway * 0.6f, horizon - horizon * 0.34f, w * 0.72f, horizon)
        path.close()
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(w * 0.55f, horizon)
        path.quadTo(w * 0.86f - sway * 0.6f, horizon - horizon * 0.26f, w * 1.15f, horizon)
        path.close()
        canvas.drawPath(path, paint)

        paint.color = p.hillMid
        path.reset()
        path.moveTo(-w * 0.15f, horizon)
        path.quadTo(w * 0.14f + sway, horizon - horizon * 0.19f, w * 0.46f, horizon)
        path.close()
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(w * 0.62f, horizon)
        path.quadTo(w * 0.86f - sway, horizon - horizon * 0.14f, w * 1.1f, horizon)
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * 섬. 바다에서 **이어진 언덕**을 그리면 그건 바다가 아니라 강가다(2026-10-29 첫 판이 그랬다).
     * 떨어뜨려 놓고 물가를 한 줄 밝게 깔아야 "물 위에 떠 있는 섬"으로 읽힌다.
     */
    private fun drawIslands(canvas: Canvas, w: Float, horizon: Float, sway: Float) {
        // (중심, 폭, 높이, 먼 섬인가)
        val isles = listOf(
            Triple(0.06f, 0.20f, 0.17f) to true,
            Triple(0.26f, 0.15f, 0.11f) to false,
            Triple(0.80f, 0.22f, 0.20f) to true,
            Triple(0.97f, 0.16f, 0.13f) to false,
        )
        for ((spec, far) in isles) {
            val (fx, fw, fh) = spec
            val cx = w * fx + if (far) sway * 0.6f else sway
            val halfW = w * fw
            val tall = horizon * fh

            paint.color = if (far) p.hillFar else p.hillMid
            path.reset()
            path.moveTo(cx - halfW, horizon)
            path.quadTo(cx - halfW * 0.45f, horizon - tall, cx, horizon - tall * 0.92f)
            path.quadTo(cx + halfW * 0.5f, horizon - tall * 0.82f, cx + halfW, horizon)
            path.close()
            canvas.drawPath(path, paint)

            // 물가 한 줄 — 이게 없으면 섬이 물에 꽂혀 보인다
            paint.color = 0x66FFFFFF
            canvas.drawOval(
                RectF(cx - halfW * 1.02f, horizon - horizon * 0.008f,
                    cx + halfW * 1.02f, horizon + horizon * 0.016f),
                paint,
            )
        }
    }

    /** 땅도 두 겹 — 먼 쪽이 밝고 발치가 어둡다. */
    private fun drawGround(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        paint.shader = LinearGradient(
            0f, horizon, 0f, h,
            if (sea) p.sea else p.ground,
            if (sea) p.seaNear else p.groundNear,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horizon, w, h, paint)
        paint.shader = null
    }

    private fun drawRoad(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        // 바다에서는 '갓길'이 물거품 가장자리, '길'이 배가 지나는 밝은 물길이 된다
        paint.color = if (sea) 0x40FFFFFF else p.shoulder
        roadPath(w, h, horizon, 0.085f, 0.47f)
        canvas.drawPath(path, paint)

        paint.color = if (sea) p.lane else p.road
        roadPath(w, h, horizon, 0.058f, 0.41f)
        canvas.drawPath(path, paint)

        drawRoadside(canvas, w, h, horizon)

        // 가운데 점선. 글 자리에는 그리지 않는다 — 글자를 덮으면 못 읽는다.
        paint.color = p.line and 0xCCFFFFFF.toInt()
        val textTop = h * (1f - textZone) - h * 0.02f
        var y = horizon + (h - horizon) * 0.10f
        var i = 0
        while (y < textTop) {
            val t = (y - horizon) / (h - horizon)
            val halfW = w * (0.0022f + 0.0055f * t)
            val len = (h - horizon) * (0.035f + 0.05f * t)
            if (y + len > textTop) break
            val cx = w * centerAt(t)
            if (sea) canvas.drawOval(
                RectF(cx - halfW * 2.4f, y, cx + halfW * 2.4f, y + len * 0.40f), paint,
            ) else canvas.drawRect(cx - halfW, y, cx + halfW, y + len, paint)
            y += len * 2.0f
            i++
            if (i > 14) break
        }
    }

    /**
     * 길가 기둥. 멀수록 작고 촘촘해진다 —
     * 들판만 있으면 그림이 멈춰 보인다. 리듬이 있어야 "가고 있다"가 된다.
     */
    private fun drawRoadside(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val stop = h * (1f - textZone) + h * 0.02f
        for (i in 0..6) {
            val t = 0.04f + i * i * 0.028f          // 멀리는 촘촘, 가까이는 성기게
            val y = horizon + (h - horizon) * t
            if (y > stop) break
            // ⚠️ 길 가장자리는 곡선이라 선형으로 어림하면 안쪽으로 들어간다(첫 판은 기둥이 아스팔트에 섰다).
            // 넉넉히 바깥으로 — 가까운 기둥이 화면 밖으로 나가는 건 자연스럽다.
            val half = 0.09f + 0.58f * t
            val tall = (h - horizon) * (0.035f + 0.085f * t)
            val thick = w * (0.0025f + 0.006f * t)
            for (sgn in listOf(-1f, 1f)) {
                val x = w * (centerAt(t) + sgn * (half + 0.03f))
                if (sea) {                       // 부표 — 물 위에 뜬 공
                    paint.color = p.accent
                    canvas.drawCircle(x, y - tall * 0.35f, thick * 2.4f, paint)
                    paint.color = 0x59FFFFFF
                    canvas.drawOval(
                        RectF(x - thick * 3.4f, y - tall * 0.12f, x + thick * 3.4f, y + tall * 0.10f),
                        paint,
                    )
                } else {
                    paint.color = p.ink and 0x99FFFFFF.toInt()
                    canvas.drawRect(x - thick, y - tall, x + thick, y, paint)
                }
            }
        }
    }

    /**
     * 깊이 t(0=지평선, 1=화면 아래)에서 길의 반폭.
     * [roadPath] 와 **같은 곡선**을 써야 한다 — 눈대중으로 쓰면 횡단보도가 길 밖 풀밭까지 나간다
     * (2026-10-29 첫 판이 그랬다).
     */
    private fun roadHalfAt(t: Float, topHalf: Float = 0.058f, botHalf: Float = 0.41f): Float {
        val c = botHalf * 0.62f
        val u = t.coerceIn(0f, 1f)
        return (1 - u) * (1 - u) * topHalf + 2 * (1 - u) * u * c + u * u * botHalf
    }

    /**
     * 길은 **중심선 + 깊이별 반폭**으로 그린다.
     * 그래야 [bend] 하나로 길 전체가 휘고, 점선·기둥·횡단보도·차가 다 같이 따라간다.
     * 꺾임을 노면 화살표로 알리던 첫 판은 긁힌 자국처럼 보였다(2026-10-29).
     */
    private fun roadPath(w: Float, h: Float, horizon: Float, topHalf: Float, botHalf: Float) {
        val n = 18
        path.reset()
        for (k in 0..n) {                                  // 왼쪽 가장자리: 아래에서 위로
            val t = 1f - k.toFloat() / n
            val y = horizon + (h - horizon) * t
            val x = w * (centerAt(t) - roadHalfAt(t, topHalf, botHalf))
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in 0..n) {                                  // 오른쪽 가장자리: 위에서 아래로
            val t = k.toFloat() / n
            val y = horizon + (h - horizon) * t
            path.lineTo(w * (centerAt(t) + roadHalfAt(t, topHalf, botHalf)), y)
        }
        path.close()
    }

    /** -1 왼쪽 / +1 오른쪽으로 길이 휜다. 0 이면 곧은 길. */
    private var bend = 0f

    /** 깊이 t 에서 길의 중심. 발치는 가운데 그대로, 멀수록 꺾이는 쪽으로 밀린다. */
    private fun centerAt(t: Float): Float {
        val far = (1f - t).coerceIn(0f, 1f)
        return 0.5f + bend * 0.17f * far * far
    }

    /**
     * 주인공. 뒤에서 본 작은 차 — "우리가 저기 간다"가 되는 한 조각이다.
     * 뒷유리에 작은 머리 둘(아이와 어른)을 앉힌다.
     */
    private fun drawCar(canvas: Canvas, w: Float, h: Float, horizon: Float, sway: Float) {
        val cw = w * 0.150f
        val ch = cw * 0.72f
        // 가운데에 두면 차가 중앙선을 깔고 앉아 길이 텅 빈 면처럼 보인다 → 오른쪽 차선으로.
        val carT = 0.36f
        val cx = w * (centerAt(carT) + 0.098f) + sway * 0.8f
        val base = horizon + (h - horizon) * carT + (if (animated) sin(phase * 2f) * h * 0.004f else 0f)

        shadow(canvas, cx, base + ch * 0.06f, cw * 0.56f)

        // 바퀴
        paint.color = p.ink
        canvas.drawRoundRect(
            RectF(cx - cw * 0.46f, base - ch * 0.22f, cx - cw * 0.22f, base), cw * 0.06f, cw * 0.06f, paint,
        )
        canvas.drawRoundRect(
            RectF(cx + cw * 0.22f, base - ch * 0.22f, cx + cw * 0.46f, base), cw * 0.06f, cw * 0.06f, paint,
        )

        // 몸통
        paint.color = p.accent
        canvas.drawRoundRect(
            RectF(cx - cw * 0.5f, base - ch * 0.62f, cx + cw * 0.5f, base - ch * 0.06f),
            cw * 0.14f, cw * 0.14f, paint,
        )
        // 지붕
        canvas.drawRoundRect(
            RectF(cx - cw * 0.36f, base - ch * 1.0f, cx + cw * 0.36f, base - ch * 0.5f),
            cw * 0.13f, cw * 0.13f, paint,
        )
        // 뒷유리 — 넓고 낮게. 정사각에 가까우면 얼굴처럼 보인다
        val glassTop = base - ch * 0.90f
        val glassBot = base - ch * 0.60f
        paint.color = if (isNight) 0xFF3A4766.toInt() else 0xFFCFE0E4.toInt()
        canvas.drawRoundRect(
            RectF(cx - cw * 0.30f, glassTop, cx + cw * 0.30f, glassBot), cw * 0.05f, cw * 0.05f, paint,
        )
        // 머리 둘 — 유리 **아랫변에 반쯤 걸치게** 잘라 낸다.
        // 유리 한가운데 떠 있는 동그라미 둘은 크기와 무관하게 '눈'으로 읽힌다
        // (2026-10-29 첫 판이 그랬다: 뒤에서 본 차가 아니라 얼굴 달린 차가 됐다).
        canvas.save()
        canvas.clipRect(cx - cw * 0.30f, glassTop, cx + cw * 0.30f, glassBot)
        paint.color = p.ink
        canvas.drawCircle(cx - cw * 0.145f, glassBot + cw * 0.012f, cw * 0.068f, paint)
        canvas.drawCircle(cx + cw * 0.125f, glassBot + cw * 0.004f, cw * 0.078f, paint)
        canvas.restore()
        // 미등 — **가로로 이어진 띠**로 둔다.
        // 동그란 등 두 개는 헤드램프처럼 보여서 차가 이쪽을 보고 있는 앞모습이 됐다(2026-10-29).
        // 뒷모습임을 한 번에 알리는 건 가로줄 하나다.
        paint.color = p.accent and 0x66FFFFFF.toInt()
        canvas.drawRoundRect(
            RectF(cx - cw * 0.44f, base - ch * 0.44f, cx + cw * 0.44f, base - ch * 0.33f),
            ch * 0.055f, ch * 0.055f, paint,
        )
        paint.color = 0xFFE8604A.toInt()
        canvas.drawRoundRect(
            RectF(cx - cw * 0.43f, base - ch * 0.425f, cx - cw * 0.17f, base - ch * 0.345f),
            ch * 0.04f, ch * 0.04f, paint,
        )
        canvas.drawRoundRect(
            RectF(cx + cw * 0.17f, base - ch * 0.425f, cx + cw * 0.43f, base - ch * 0.345f),
            ch * 0.04f, ch * 0.04f, paint,
        )
        // 번호판 자리 — 뒷모습을 한 번 더 못박는다
        paint.color = if (isNight) 0xFF6E6A62.toInt() else 0xFFF2EADA.toInt()
        canvas.drawRoundRect(
            RectF(cx - cw * 0.12f, base - ch * 0.30f, cx + cw * 0.12f, base - ch * 0.19f),
            ch * 0.03f, ch * 0.03f, paint,
        )
    }

    /**
     * 배 — **그려 둔 그림 한 장**을 올린다([SceneArt] 의 배경과 같은 붓).
     *
     * 물살·물거품만 코드로 그린다. 그림마다 수평선 높이가 다르므로 물은 그림에 못 굽는다.
     *
     * ⚠️ **밑동을 물에 담근다.** 통째로 올리면 배가 물 위에 **얹힌** 것처럼 보인다 —
     *    물가에서 잘라 내야 떠 있는 것으로 읽힌다.
     */
    private fun drawBoat(canvas: Canvas, w: Float, h: Float, horizon: Float, sway: Float) {
        val carT = 0.36f
        val cx = w * (centerAt(carT) + 0.098f) + sway * 0.8f
        val base = horizon + (h - horizon) * carT +
            (if (animated) sin(phase * 2f) * h * 0.005f else 0f)
        val bw = w * 0.150f

        // 물살 — 배가 물 위에 있다는 걸 알려 주는 건 이것 하나다.
        // ⚠️ 짙게 그리면 **흰 접시 위에 배를 얹어 둔 것**처럼 보인다(2026-11-03 실측).
        //    물감 그림 위에서는 넓고 아주 옅은 띠 두 줄이면 충분하다.
        paint.color = 0x3DFFFFFF
        canvas.drawOval(
            RectF(cx - bw * 0.80f, base - bw * 0.05f, cx + bw * 0.80f, base + bw * 0.09f), paint,
        )
        paint.color = 0x22FFFFFF
        canvas.drawOval(
            RectF(cx - bw * 1.25f, base + bw * 0.01f, cx + bw * 1.25f, base + bw * 0.16f), paint,
        )

        val bmp = sprite(R.drawable.sp_boat) ?: return
        val sw = w * 0.30f                               // 돛까지 들어 있는 그림이다
        val sh = sw * bmp.height / bmp.width

        // 배가 **기운다**. 이야기가 "배가 오른쪽으로 기울어요" 라고 말하는데
        // 화면이 가만히 있으면 그건 다른 이야기다. 길이 휜 쪽([bend])으로 기울고,
        // 멈춰 있을 땐 물결만큼만 흔들린다.
        val tilt = bend * 5.5f + lean * 7.5f +
            (if (animated) sin(phase * 2f + 0.6f) * 2.0f else 0f)

        canvas.save()
        canvas.clipRect(0f, 0f, w, base + sh * 0.02f)    // 물가에서 자른다
        drawSprite(canvas, bmp, cx, base + sh * 0.10f, sw, tilt)
        canvas.restore()

        // 물거품 — 자른 자리를 덮어 **물에 잠긴 선**으로 만든다. 얇게.
        paint.color = 0x59FFFFFF
        canvas.drawOval(
            RectF(cx - bw * 0.55f, base - bw * 0.035f, cx + bw * 0.55f, base + bw * 0.035f), paint,
        )
    }

    /**
     * 동행 — 시즌 1의 구름. **늘 화면에 있다.**
     * 이야기가 "구름이 따라오고 있어"라고 하는데 화면에 구름이 없으면 그건 다른 이야기다.
     * 그리고 [growth] 만큼 **실제로 커진다** — 진행이 눈에 보이는 유일한 장치다.
     *
     * 크기만 바뀌므로 그림 한 장이면 된다. 한때는 원 네 개와 네모로 그렸는데,
     * 그 네모가 원 밖으로 **삐져나와 구름 밑에 하늘색 막대**가 보였다(2026-11-03 화면 시안).
     */
    private fun drawCompanion(canvas: Canvas, w: Float, h: Float, horizon: Float, sway: Float) {
        if (!sea) return
        val bmp = sprite(R.drawable.sp_cloud) ?: return
        val g = growth.coerceIn(0, 6)
        val cw = w * (0.088f + 0.022f * g)               // 1화의 작은 점에서 12화의 큰 구름까지
        val ch = cw * bmp.height / bmp.width
        val cx = w * 0.30f - sway * 2.2f
        val cy = horizon * 0.52f + (if (animated) sin(phase) * h * 0.010f else 0f)

        // ⛔ **물감 그림 위에는 그림자를 두지 않는다.** 옅게 깔아 봐도 물 한가운데 **회색 얼룩**으로
        //    보일 뿐이다(2026-11-03 실측). 애초에 손님은 하늘에 있어서 바로 아래 물에 그림자가
        //    질 이유도 없다. 코드로 그리는 세계에서만 바닥에 앉혀 둔다.
        if (SceneArt.of(scene, isNight, sea) == null) {
            paint.color = 0x26000000
            canvas.drawOval(
                RectF(cx - cw * 0.5f, horizon + h * 0.06f, cx + cw * 0.5f, horizon + h * 0.10f),
                paint,
            )
        }

        drawSprite(canvas, bmp, cx, cy + ch / 2f, cw)
    }

    /** 글 자리를 팔레트 색으로 깐다. 검정 띠를 쓰면 그림 위에 막대를 올린 것처럼 보인다. */
    private fun drawTextScrim(canvas: Canvas, w: Float, h: Float) {
        if (textZone <= 0f) return
        // ⛔ **앞에서 쓰던 알파가 그라디언트에 그대로 곱해진다.** 안드로이드는 shader 색에
        //    paint 의 알파를 한 번 더 곱한다 — 바로 앞에서 그린 옅은 그림자(알파 7%)가 남아 있으면
        //    글 밑 어둡게 깔기도 7% 로 줄어 **흰 글씨가 밝은 물 위에 그냥 얹힌다**(2026-11-03).
        //    여기서 되돌린다. 앞 함수가 뒷정리를 하리라 믿지 않는다.
        paint.alpha = 255
        // ⚠️ 시작점이 지평선(h*0.56)보다 위로 올라가면 하늘과 언덕까지 어두워진다(첫 판이 그랬다).
        // ⛔ **세로 화면에서는 높이 비율로 잡으면 안 된다.** 1440×2560 에서 36% 면 920px —
        //    글은 두 줄인데 그림의 아래 3분의 1이 어두워진다. 글자 크기는 화면 **폭**을 따라가므로
        //    띠도 폭으로 묶는다([textZone] 자체는 안 건드린다 — 길이 사라지는 높이도 그 값을 쓴다).
        val band = min(h * (textZone + 0.10f), w * 0.34f)
        val top = h - band
        paint.shader = LinearGradient(
            0f, top, 0f, h,
            p.dark and 0x00FFFFFF, p.dark or 0xF0000000.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top, w, h, paint)
        paint.shader = null
    }

    // ────────────────────────── 장면 조각들

    /**
     * 비 — 비스듬한 빗줄기. **화면 전체에** 깐다(하늘에만 그리면 창밖이 아니라 그림이 된다).
     *
     * ⚠️ 움직이지 않는다. 주행 중에 쓸 수 있는 건 **정지 그림 한 장**뿐이다(SDK_FACTS 6절).
     *    그래서 빗줄기는 `phase` 를 쓰지 않고 자리만 흩어 둔다.
     */
    private fun drawRain(canvas: Canvas, w: Float, h: Float) {
        paint.color = 0x66FFFFFF
        paint.strokeWidth = h * 0.003f
        val slant = w * 0.02f
        var i = 0
        while (i < 90) {
            // 고정 난수 — 같은 쪽을 두 번 그려도 같은 비가 내려야 한다(앨범아트로도 굽힌다).
            val x = (i * 7919 % 1000) / 1000f * (w + slant * 2) - slant
            val y = (i * 104729 % 1000) / 1000f * h
            val len = h * (0.03f + (i % 3) * 0.012f)
            canvas.drawLine(x, y, x - slant, y + len, paint)
            i++
        }
        paint.strokeWidth = 0f
    }

    /**
     * 바람 — 수평으로 길게 누운 흐름선. 비와 달리 **지평선 쪽에만** 둔다(바람은 멀리서 온다).
     */
    private fun drawWind(canvas: Canvas, w: Float, horizon: Float, sway: Float) {
        paint.color = 0x4DFFFFFF
        paint.strokeWidth = horizon * 0.009f
        for (i in 0..5) {
            val y = horizon * (0.30f + i * 0.09f)
            val x0 = w * (0.04f + (i % 3) * 0.14f) + sway
            canvas.drawLine(x0, y, x0 + w * (0.22f + (i % 2) * 0.12f), y, paint)
        }
        paint.strokeWidth = 0f
    }

    private fun drawFog(canvas: Canvas, w: Float, horizon: Float) {
        paint.color = 0x7AFFFFFF
        for (i in 0..3) {
            val y = horizon * (0.52f + i * 0.13f)
            canvas.drawRoundRect(
                RectF(-w * 0.1f + i * w * 0.07f, y, w * 0.8f + i * w * 0.1f, y + horizon * 0.08f),
                horizon * 0.045f, horizon * 0.045f, paint,
            )
        }
    }

    private fun drawTunnel(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val cx = w * 0.5f
        // 산은 **낮고 넓게**. 둥근 사각형을 세우면 초록 알약처럼 보인다(첫 판).
        // 바다에선 바위섬. 넓게 그리면 화면을 다 덮어 바다가 사라진다(2026-10-29 첫 판).
        val mw = if (sea) 0.27f else 0.46f
        val mh = if (sea) 0.24f else 0.30f
        paint.color = if (sea) 0xFF8C8275.toInt() else p.hillMid
        path.reset()
        path.moveTo(cx - w * mw, horizon + h * 0.02f)
        path.quadTo(cx - w * mw * 0.65f, horizon - h * mh, cx, horizon - h * mh)
        path.quadTo(cx + w * mw * 0.65f, horizon - h * mh, cx + w * mw, horizon + h * 0.02f)
        path.close()
        canvas.drawPath(path, paint)
        if (sea) {                                   // 물가 한 줄
            paint.color = 0x66FFFFFF
            canvas.drawOval(
                RectF(cx - w * mw * 1.04f, horizon - h * 0.006f,
                    cx + w * mw * 1.04f, horizon + h * 0.016f),
                paint,
            )
        }

        // 입구는 길 폭에 맞춘다 — 길보다 훨씬 크면 산에 뚫린 구멍이 아니라 터널 그림이 된다
        val rx = w * (roadHalfAt(0.04f) + if (sea) 0.030f else 0.045f)
        val ry = h * if (sea) 0.125f else 0.17f
        val baseY = horizon + h * 0.02f
        paint.color = if (sea) 0xFFA79A88.toInt() else p.shoulder
        canvas.drawRoundRect(RectF(cx - rx, baseY - ry * 2.0f, cx + rx, baseY), rx, rx, paint)
        paint.color = p.ink
        canvas.drawRoundRect(
            RectF(cx - rx * 0.80f, baseY - ry * 1.78f, cx + rx * 0.80f, baseY),
            rx * 0.80f, rx * 0.80f, paint,
        )
        paint.color = p.glow                           // 저 끝의 빛
        canvas.drawCircle(cx, baseY - ry * 0.80f, rx * 0.13f, paint)
    }

    private fun drawForest(canvas: Canvas, w: Float, h: Float, horizon: Float, sway: Float) {
        val base = horizon + h * 0.012f
        for ((i, x) in listOf(0.10f, 0.20f, 0.80f, 0.90f).withIndex()) {
            val cx = w * x + if (i % 2 == 0) sway else -sway
            val tall = h * (0.17f + 0.05f * (i % 2))
            paint.color = p.ink
            canvas.drawRect(cx - w * 0.007f, base - tall * 0.34f, cx + w * 0.007f, base, paint)
            // 잎을 두 겹으로 — 한 겹이면 삼각형 표지판처럼 보인다
            paint.color = if (isNight) 0xFF1D3F31.toInt() else 0xFF5E8A58.toInt()
            leaf(cx, base - tall * 0.28f, w * 0.062f, tall * 0.48f)
            canvas.drawPath(path, paint)
            paint.color = if (isNight) 0xFF24523E.toInt() else 0xFF73A268.toInt()
            leaf(cx, base - tall * 0.52f, w * 0.050f, tall * 0.44f)
            canvas.drawPath(path, paint)
        }
        // 길 가의 사슴
        val dx = w * 0.70f
        val dy = horizon + h * 0.085f
        shadow(canvas, dx, dy + h * 0.034f, w * 0.034f)
        paint.color = p.ink
        canvas.drawOval(RectF(dx - w * 0.034f, dy - h * 0.044f, dx + w * 0.034f, dy), paint)
        canvas.drawCircle(dx + w * 0.037f, dy - h * 0.064f, h * 0.021f, paint)
        canvas.drawRect(dx - w * 0.023f, dy - h * 0.004f, dx - w * 0.015f, dy + h * 0.034f, paint)
        canvas.drawRect(dx + w * 0.015f, dy - h * 0.004f, dx + w * 0.023f, dy + h * 0.034f, paint)
        paint.strokeWidth = w * 0.0035f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(dx + w * 0.031f, dy - h * 0.083f, dx + w * 0.021f, dy - h * 0.112f, paint)
        canvas.drawLine(dx + w * 0.044f, dy - h * 0.083f, dx + w * 0.054f, dy - h * 0.112f, paint)
        paint.style = Paint.Style.FILL
    }

    /** 뾰족한 삼각형 대신 밑이 둥근 잎. */
    private fun leaf(cx: Float, topY: Float, halfW: Float, tall: Float) {
        path.reset()
        path.moveTo(cx, topY)
        path.quadTo(cx + halfW, topY + tall * 0.66f, cx + halfW * 0.82f, topY + tall)
        path.lineTo(cx - halfW * 0.82f, topY + tall)
        path.quadTo(cx - halfW, topY + tall * 0.66f, cx, topY)
        path.close()
    }

    private fun drawSchool(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val x = w * 0.78f
        val base = horizon + h * 0.055f
        if (sea) {                                   // 집이 물에 떠 있으면 이상하다 — 섬을 깔아 준다
            paint.color = p.hillMid
            path.reset()
            path.moveTo(x - w * 0.17f, base + h * 0.012f)
            path.quadTo(x - w * 0.09f, base - h * 0.035f, x, base - h * 0.038f)
            path.quadTo(x + w * 0.10f, base - h * 0.035f, x + w * 0.17f, base + h * 0.012f)
            path.close()
            canvas.drawPath(path, paint)
            paint.color = 0x66FFFFFF
            canvas.drawOval(
                RectF(x - w * 0.18f, base + h * 0.004f, x + w * 0.18f, base + h * 0.026f), paint,
            )
        }
        shadow(canvas, x, base, w * 0.072f)
        paint.color = if (isNight) 0xFF4A4330.toInt() else 0xFFE8C98A.toInt()
        canvas.drawRoundRect(
            RectF(x - w * 0.082f, base - h * 0.155f, x + w * 0.082f, base), w * 0.006f, w * 0.006f, paint,
        )
        paint.color = p.accent
        path.reset()
        path.moveTo(x - w * 0.098f, base - h * 0.155f)
        path.lineTo(x, base - h * 0.225f)
        path.lineTo(x + w * 0.098f, base - h * 0.155f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = p.ink
        canvas.drawRoundRect(
            RectF(x - w * 0.022f, base - h * 0.078f, x + w * 0.022f, base), w * 0.004f, w * 0.004f, paint,
        )
        // 횡단보도 — 길 폭에 맞춰서(roadHalfAt) 그린다. 글 자리 위에서 멈춘다.
        paint.color = p.line and 0xE6FFFFFF.toInt()
        val stop = h * (1f - textZone) - h * 0.03f
        for (i in 0..5) {
            val t = 0.10f + i * 0.055f
            val yy = horizon + (h - horizon) * t
            val thick = (h - horizon) * (0.012f + 0.022f * t)
            if (yy + thick > stop) break
            val half = w * roadHalfAt(t) * 0.94f
            val ccx = w * centerAt(t)
            canvas.drawRect(ccx - half, yy, ccx + half, yy + thick, paint)
        }
    }

    private fun drawRail(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val y = horizon + (h - horizon) * 0.40f
        paint.color = if (isNight) 0xFF2A2436.toInt() else 0xFF7A6552.toInt()
        canvas.drawRect(0f, y, w, y + (h - horizon) * 0.07f, paint)
        paint.color = p.shoulder
        canvas.drawRect(0f, y + (h - horizon) * 0.014f, w, y + (h - horizon) * 0.026f, paint)
        canvas.drawRect(0f, y + (h - horizon) * 0.048f, w, y + (h - horizon) * 0.060f, paint)
        paint.color = p.accent
        canvas.drawRect(w * 0.12f, y - h * 0.125f, w * 0.134f, y, paint)
        paint.color = p.glow
        canvas.drawCircle(w * 0.127f, y - h * 0.135f, h * 0.016f, paint)
    }

    private fun drawRest(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val x = w * 0.22f
        val base = horizon + h * 0.05f
        shadow(canvas, x, base, w * 0.08f)
        paint.color = if (isNight) 0xFF3B3A42.toInt() else 0xFFF6EBD6.toInt()
        canvas.drawRoundRect(
            RectF(x - w * 0.058f, base - h * 0.082f, x + w * 0.058f, base), w * 0.005f, w * 0.005f, paint,
        )
        paint.color = if (isNight) 0xFF2C4652.toInt() else 0xFF5B8C96.toInt()
        canvas.drawRoundRect(
            RectF(x - w * 0.072f, base - h * 0.102f, x + w * 0.072f, base - h * 0.080f),
            w * 0.005f, w * 0.005f, paint,
        )
        paint.color = p.glow
        canvas.drawRoundRect(
            RectF(x - w * 0.015f, base - h * 0.054f, x + w * 0.015f, base - h * 0.028f),
            w * 0.003f, w * 0.003f, paint,
        )
    }

    private fun drawBump(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        val y = horizon + (h - horizon) * 0.46f
        paint.color = if (sea) 0xCCFFFFFF.toInt() else p.glow      // 바다에선 파도
        path.reset()
        path.moveTo(w * 0.22f, y + (h - horizon) * 0.06f)
        path.quadTo(w * 0.5f, y - (h - horizon) * 0.095f, w * 0.78f, y + (h - horizon) * 0.06f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = if (sea) 0x80FFFFFF.toInt() else p.accent   // 물마루 / 줄무늬
        path.reset()
        path.moveTo(w * 0.36f, y + (h - horizon) * 0.028f)
        path.quadTo(w * 0.5f, y - (h - horizon) * 0.040f, w * 0.64f, y + (h - horizon) * 0.028f)
        path.quadTo(w * 0.5f, y - (h - horizon) * 0.012f, w * 0.36f, y + (h - horizon) * 0.028f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawGate(canvas: Canvas, w: Float, h: Float, horizon: Float) {
        // 길 폭에 맞춘다. 전엔 길보다 네 배 넓고 다리가 가늘어 축구 골대처럼 보였다(2026-10-29).
        val t = 0.10f
        val legX = w * (roadHalfAt(t) + 0.060f)
        val baseY = horizon + (h - horizon) * t
        val topY = baseY - h * 0.145f
        val legW = w * 0.030f
        val cx = w * 0.5f

        paint.color = p.accent
        for (sgn in listOf(-1f, 1f)) {
            val x = cx + sgn * legX
            canvas.drawRoundRect(
                RectF(x - legW, topY, x + legW, baseY), legW * 0.35f, legW * 0.35f, paint,
            )
        }
        canvas.drawRoundRect(
            RectF(cx - legX - legW * 1.5f, topY - h * 0.052f, cx + legX + legW * 1.5f, topY),
            h * 0.022f, h * 0.022f, paint,
        )
        paint.color = p.glow
        val lights = 4
        for (i in 0 until lights) {
            val f = (i + 0.5f) / lights
            canvas.drawCircle(
                cx - legX + (legX * 2f) * f, topY - h * 0.026f, h * 0.011f, paint,
            )
        }
        // 깃발 — 도착이 잔치처럼 보이게
        for (sgn in listOf(-1f, 1f)) {
            val x = cx + sgn * (legX + legW * 1.5f)
            path.reset()
            path.moveTo(x, topY - h * 0.052f)
            path.lineTo(x + sgn * w * 0.030f, topY - h * 0.078f)
            path.lineTo(x, topY - h * 0.100f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawCharge(canvas: Canvas, w: Float, h: Float, horizon: Float, sway: Float) {
        val cx = w * 0.5f + sway * 2f
        val cy = horizon - h * 0.14f
        paint.color = p.glow and 0x44FFFFFF
        canvas.drawCircle(cx, cy, h * 0.14f, paint)
        paint.color = p.glow
        path.reset()
        path.moveTo(cx + h * 0.02f, cy - h * 0.09f)
        path.lineTo(cx - h * 0.04f, cy + h * 0.005f)
        path.lineTo(cx + h * 0.005f, cy + h * 0.005f)
        path.lineTo(cx - h * 0.015f, cy + h * 0.09f)
        path.lineTo(cx + h * 0.05f, cy - h * 0.015f)
        path.lineTo(cx + h * 0.0f, cy - h * 0.015f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 땅에 붙어 보이게 하는 타원 그림자. 없으면 물건이 공중에 뜬다. */
    private fun shadow(canvas: Canvas, cx: Float, baseY: Float, rx: Float) {
        paint.color = if (isNight) 0x4D000000 else 0x2E000000
        canvas.drawOval(RectF(cx - rx, baseY - rx * 0.16f, cx + rx, baseY + rx * 0.16f), paint)
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}
