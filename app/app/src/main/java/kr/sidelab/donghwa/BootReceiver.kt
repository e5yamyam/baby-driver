package kr.sidelab.donghwa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 차 전원을 켜면 이야기 서비스를 올린다.
 *
 * ## 왜 이게 필요한가 (2026-11-01)
 * [StoryService] 는 [MainActivity.onCreate] 에서만 켜졌다. 즉 **매 주행마다 아이콘을 한 번
 * 눌러야** 했고, 안 누르면 목적지를 넣어도 아무 일도 일어나지 않았다.
 * 등원길처럼 매일 타는 길에서 그 한 번이 빠지는 건 시간문제다.
 *
 * ## 자동으로 켜져도 조용한 이유
 * 올라오는 건 **듣는 쪽**뿐이다. 이야기는 `RouteStarted`(목적지 설정)에서만 시작하므로,
 * 목적지 없이 그냥 달리면 한 마디도 하지 않는다 —
 * 그걸 [StoryHarnessTest.길이_없으면_말하지_않는다] 가 증명한다.
 * 미디어 상태도 [Engine.storyActive] 가 거짓이면 `STATE_NONE` 으로 둬서
 * 차의 음악 자리를 가져오지 않는다.
 *
 * ⚠️ Android 12 부터 백그라운드에서 포그라운드 서비스를 띄우는 건 막혀 있고
 * `BOOT_COMPLETED` 는 그 예외에 들어간다. 다만 **문서 대신 실측으로 확인한다** —
 * 안 되면 `ForegroundServiceStartNotAllowedException` 이 여기 로그에 남는다.
 *
 *   adb logcat -s DonghwaBoot
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { StoryService.start(context) }
            .onSuccess { Log.i(TAG, "전원 켜짐 — 이야기 서비스 올림(길이 시작될 때까지 조용하다)") }
            .onFailure { Log.w(TAG, "전원 켜짐 — 서비스 못 올림: ${it.javaClass.simpleName} ${it.message}") }
    }

    private companion object {
        const val TAG = "DonghwaBoot"
    }
}
