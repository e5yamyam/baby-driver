package kr.sidelab.donghwa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View

/**
 * 이야기를 **화면 없이** 끌고 가는 곳.
 *
 * ## 왜 이게 필요한가 (2026-10-29 실측)
 * Pleos Connect 화면을 재 보니 앱 창은 `[853,90]~[2535,1300]`(1682×1210) 하나뿐이고,
 * **내비게이션 앱(`ai.umos.maps.android.navigation.app`)이 우리와 똑같은 창을 쓴다.**
 * 즉 우리 액티비티를 띄우면 **지도가 사라진다.**
 *
 * 그런데 이 앱은 오디오북이다. 지도를 밀어낼 이유가 없다.
 * 그래서 주행 중에는 액티비티가 아니라 **이 서비스가 돈다** —
 * 소리는 나가고, 화면은 내비가 그대로 쓰고, 우리는 시스템 미디어 영역에
 * **지금 장면 글 + 장면 그림**을 올린다.
 *
 * 장면 그림이 그대로 **앨범아트**가 된다. 정책상으로도 이게 맞다 —
 * Music/Audio 카테고리가 주행 중 허용하는 것이 "오디오북 재생"과 "앨범아트 등 단순 시각 정보"다.
 *
 * 전체 화면(액티비티)은 **보고 싶을 때·주차 중에만** 연다.
 */
class StoryService : Service() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** 이야기를 민다. 예약된 장면이 제시간에 나가려면 촘촘해야 한다. */
    private val tick = object : Runnable {
        override fun run() {
            Engine.tickStory()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** 내비·차량 값을 당겨 온다. 이건 무거우니 드물게. */
    private val pollJob = object : Runnable {
        override fun run() {
            Engine.poll()
            main.postDelayed(this, POLL_MS)
        }
    }

    private var session: MediaSession? = null
    private var observer: (() -> Unit)? = null
    private var lastArt: Pair<SceneKind, TimeOfDay>? = null
    private var artCache: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.start(this)

        session = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Engine.resumeStory()
                override fun onPause() = Engine.pauseStory()
                override fun onStop() = Engine.pauseStory()
            })
            isActive = true
        }

        startForeground(NOTI_ID, notification())
        observer = Engine.observe(::push)
        push()
        main.postDelayed(tick, TICK_MS)
        main.postDelayed(pollJob, POLL_MS)
        Log.i(TAG, "이야기 서비스 시작 — 화면은 내비가 쓰고 우리는 소리와 미디어 정보만 낸다")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("name")?.takeIf { it.isNotBlank() }
            ?.let { Settings.setChildName(this, it) }
        intent?.getIntExtra("age", 0)?.takeIf { it > 0 }?.let { Settings.setChildAge(this, it) }
        if (intent?.getBooleanExtra("route", false) == true) Engine.requestDemoRoute()
        if (intent?.getBooleanExtra("demo", false) == true) {
            Engine.startDemo(
                intent.getStringExtra("script"),
                Demo.skyOf(intent.getStringExtra("sky")),
                // 시연 대본을 늘리는 배율 — 영상용. `--ef stretch 1.7`
                intent.getFloatExtra("stretch", 1f),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        main.removeCallbacks(pollJob)
        Engine.unobserve(observer)
        session?.release()
        session = null
        super.onDestroy()
    }

    /** 지금 장면을 미디어 정보로 올린다. 화면이 없어도 운전자는 여기서 본다. */
    private fun push() {
        val s = session ?: return
        val line = Engine.lastLine ?: "창틀에서 손님을 기다려요"

        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, line)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, Engine.bookTitle ?: "오늘의 길")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(
                    when {
                        // ⛔ 길이 없으면 **재생 중이 아니다.** 차 전원을 켜면 이 서비스가 자동으로
                        //    뜨는데(BootReceiver), 그때 PLAYING 을 올리면 목적지도 없이
                        //    차의 음악 자리를 가져간다.
                        !Engine.storyActive -> PlaybackState.STATE_NONE
                        Engine.storyPaused -> PlaybackState.STATE_PAUSED
                        else -> PlaybackState.STATE_PLAYING
                    },
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
        notificationManager().notify(NOTI_ID, notification())
    }

    /**
     * 장면 그림을 앨범아트로 굽는다.
     * 화면에 붙이지 않은 [SceneView] 를 직접 measure/layout 해서 비트맵에 그린다 —
     * 그림이 코드로 그려지기 때문에 가능한 일이다(그림 파일이었다면 장면마다 따로 만들어야 했다).
     */
    private fun art(): Bitmap {
        val key = Engine.scene to Engine.timeOfDayNow()
        artCache?.let { if (lastArt == key) return it }

        val v = SceneView(this).apply {
            scene = key.first
            time = key.second
            textZone = 0f          // 앨범아트엔 글이 안 올라간다
            animated = false
        }
        val spec = View.MeasureSpec.makeMeasureSpec(ART, View.MeasureSpec.EXACTLY)
        v.measure(spec, spec)
        v.layout(0, 0, ART, ART)
        val bmp = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))

        lastArt = key
        artCache = bmp
        return bmp
    }

    private fun notificationManager() =
        getSystemService(NotificationManager::class.java)

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, "이야기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "주행 중 이야기를 이어서 들려 줍니다"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(Engine.lastLine ?: "창틀에서 손님을 기다려요")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken))
            .build()
    }

    companion object {
        const val TAG = "DonghwaSvc"
        private const val CHANNEL = "story"
        private const val NOTI_ID = 42
        private const val ART = 512

        /** 이야기를 밀어 주는 주기. 화면이 없을 때는 여기만 돈다. */
        private const val TICK_MS = 1_500L

        /** 내비·차량 값을 당겨 오는 주기. */
        private const val POLL_MS = 5_000L

        fun start(ctx: android.content.Context, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, StoryService::class.java).apply(extras)
            ctx.startForegroundService(i)
        }
    }
}
