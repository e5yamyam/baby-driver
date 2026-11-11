package kr.sidelab.donghwa

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 로 말한다.
 *
 * 왜 이게 필요한가: Pleos Connect Emulator v2.1.0 에는 **Android 표준 TTS 엔진이 아예 없다**
 * (`tts_default_synth = null`, TTS 패키지 0개). 2026-10-29 실측.
 * 그래서 차 안에서는 Pleos TTS 가 사실상 유일한 음성 출력 경로다.
 *
 * `Mode.ON_DEVICE` 는 서버 연결이 없으니 `registerApp(클라이언트 ID/시크릿)` 이 필요 없다 —
 * Playground 콘솔에서 TTS 가 "준비 중"이어도 온디바이스는 될 수 있다는 뜻이다.
 * 되는지 안 되는지는 [onReady] 가 불리는지로 판정한다.
 */
class PleosNarrator(
    context: Context,
    private val onUnavailable: (String) -> Unit,
) : Narrator {

    companion object {
        const val TAG = "DonghwaTts"
    }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var ready = false
    private val tts = PleosTts(context, Mode.ON_DEVICE)

    private val events = object : EventListener {
        override fun onReady() {
            ready = true
            Log.i(TAG, "pleos tts ready")
        }

        override fun onStart() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "pleos tts error: $errorType")
            if (errorType == ErrorType.NOT_SUPPORTED_API ||
                errorType == ErrorType.PERMISSION_DENIED ||
                errorType == ErrorType.ON_DEVICE_ENGINE_ERROR ||
                errorType == ErrorType.UNAVAILABLE_LANGUAGE
            ) {
                ready = false
                onUnavailable(errorType.name)
            }
        }
    }

    init {
        // ⚠️ 순서가 중요하다. initialize() 보다 먼저 addEventListener() 를 부르면
        //    SDK 내부의 lateinit 이 터진다(2026-10-29 실측, UninitializedPropertyAccessException).
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.i(TAG, "pleos tts initialized")
                runCatching {
                    tts.addEventListener(events)
                    tts.setAudioAttributes(attrs)
                    tts.setLocale(Locale.KOREAN)
                }.onFailure { Log.w(TAG, "설정 실패: ${it.message}") }
                ready = true
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "pleos tts init 실패: $errorType")
                onUnavailable(errorType.name)
            }
        })
    }

    /**
     * 선행 묵음은 문장 앞 공백으로는 안 된다(묵음이 아니라 쉼표처럼 읽힌다).
     * Pleos TTS 는 스트림을 직접 주지 않으므로, 급한 말이 아니면 앞의 말이 끝난 뒤에 넣는다.
     * ⚠️ 150~200ms 선행 묵음은 아직 적용 못 했다 — 오디오 데이터 레벨 접근이 필요하다(TODO).
     */
    override fun say(text: String, urgent: Boolean) {
        if (!ready) {
            Log.w(TAG, "pleos tts 준비 안 됨, 버린다: $text")
            return
        }
        runCatching { tts.speak(text, urgent) }
            .onFailure { Log.w(TAG, "speak 실패: ${it.message}") }
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun release() {
        runCatching {
            tts.removeEventListener(events)
            tts.release()
        }
    }
}
