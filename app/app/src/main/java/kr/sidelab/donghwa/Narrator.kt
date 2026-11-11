package kr.sidelab.donghwa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/**
 * 이야기를 소리로 낸다.
 *
 * Pleos TextToSpeech SDK 는 2026-10-29 기준 Playground 콘솔에서 "준비 중"이라 쓸 수 없다.
 * 그래서 지금은 Android 표준 TTS 로 내고, 이 인터페이스만 지키면 나중에 Pleos TTS 로 갈아끼운다.
 */
interface Narrator {
    /** @param urgent 멀미 선행 안내처럼 지금 당장 나가야 하는 말. 앞의 말을 끊는다. */
    fun say(text: String, urgent: Boolean = false)
    fun stop()
    fun release()
}

class AndroidNarrator(context: Context) : Narrator {

    companion object {
        const val TAG = "DonghwaTts"

        /**
         * 선행 묵음. Pleos "TTS / 음성 안내 선행 묵음 적용 가이드" 권장값 150~200ms.
         * 없으면 첫 음절이 잘린다("배가" → "가").
         */
        const val PREROLL_MS = 180L
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * USAGE_ASSISTANT 로 낸다. 내비게이션 안내(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)가
     * 우선순위를 가지므로, 안내가 나오면 우리가 비켜 주게 된다.
     */
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var ready = false
    private var ducked = false
    private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context, ::onTtsInit)

    private fun onTtsInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(TAG, "tts init failed: $status")
            return
        }
        tts.language = Locale.KOREAN
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(0.95f)   // 아이가 따라올 속도
        Log.i(TAG, "tts ready")
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                // 내비 안내가 나온다 — 말을 멈추고 그 문장을 기억해 둔다.
                // 음원도 같이 멈춘다. 안 그러면 길 안내 위에 동화가 겹쳐 흐른다.
                ducked = true
                tts.stop()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                ducked = false
                pending?.let { pending = null; say(it) }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    override fun say(text: String, urgent: Boolean) {
        if (!ready) {
            Log.w(TAG, "아직 준비 안 됨, 버린다: $text")
            return
        }
        if (ducked) {
            // 안내 중이면 끝나고 이어서 말한다. 급한 말만 기억한다(멀미 안내는 늦으면 쓸모없다).
            if (urgent) pending = text
            return
        }
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "오디오 포커스 거절: $text")
            return
        }

        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // 묵음 → 말. 묵음을 먼저 큐에 넣는 것이 핵심이다.
        tts.playSilentUtterance(PREROLL_MS, mode, "preroll")
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "line-${text.hashCode()}")
        Log.i(TAG, "say(urgent=$urgent): $text")
    }

    override fun stop() {
        tts.stop()
        audio.abandonAudioFocusRequest(focusRequest)
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

/**
 * **미리 구운 나레이션을 먼저 튼다.** 없는 문장만 안쪽 목소리([inner])에게 넘긴다.
 *
 * 왜 필요한가: Pleos TextToSpeech SDK 는 아직 "준비 중"이고 **에뮬레이터에는 안드로이드 TTS
 * 엔진이 아예 없다.** 둘 다 `ready` 라고 대답은 하는데 소리가 안 난다 — 시연에서 한마디도
 * 안 들린다. 대사는 저작물이라 유한하므로(`tools/make_voice.py`) 미리 만들어 둔다.
 *
 * ⛔ 아이 이름·지명·남은 시간이 든 문장은 **굽지 않는다**(사람마다 다르다). 그런 줄은 그대로
 *    [inner] 로 간다. 그래서 이 감싸개는 **대체가 아니라 앞단**이다.
 *
 * 파일 이름은 문장의 SHA-1 앞 16자다. 목록을 따로 들고 다니지 않아도 맞는지 알 수 있고,
 * 문장을 고치면 소리가 저절로 안 맞게 되어(파일이 없어) TTS 로 내려간다 — 틀린 소리가 나느니 낫다.
 */
class VoicePack(context: Context, private val inner: Narrator) : Narrator {

    companion object {
        const val TAG = "DonghwaVoice"
        const val DIR = "voice"

        /**
         * 음원 파일 이름 = 문장의 SHA-1 앞 16자.
         *
         * `tools/make_voice.py` 가 같은 규칙으로 파일을 만든다. 둘이 어긋나면 **소리가 통째로
         * 안 난다** — 그런데 앱은 조용히 TTS 로 내려가므로 아무도 모른다.
         * 그래서 하네스가 이 함수와 실제 파일 이름을 대조한다(`음원_이름이_양쪽에서_같다`).
         */
        fun keyOf(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
    }

    private val assets = context.assets
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    // ⛔ `assets.list(DIR)` 로 세지 않는다. 서비스가 뜨는 그 순간에 도는 호출이라
    //    기계가 바쁘면 거기서 멈추고 **ANR 로 앱이 죽는다**(2026-11-03). 목록은 파일 하나다.
    private val have: Set<String> = runCatching {
        assets.open("$DIR/index.txt").bufferedReader().useLines { ls ->
            ls.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }.getOrNull().orEmpty().also { Log.i(TAG, "구운 음원 ${it.size}개") }

    private var player: MediaPlayer? = null
    private val queued = ArrayDeque<String>()

    /** 다음 음원을 건다. 겹쳐 틀면 두 문장이 **동시에** 들린다 — 줄을 세운다. */
    private fun playNext(): Boolean {
        val k = queued.removeFirstOrNull() ?: run {
            audio.abandonAudioFocusRequest(focus)
            return false
        }
        return runCatching {
            val p = MediaPlayer()
            assets.openFd("$DIR/$k.m4a").use {
                p.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            p.setAudioAttributes(attrs)
            p.setOnCompletionListener { done ->
                done.release()
                if (player === done) player = null
                playNext()
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "재생 오류 $k ($what/$extra)")
                player = null
                playNext()
                true
            }
            // ⚠️ `prepare()` 는 **메인 스레드를 잡는다**(코덱이 소프트웨어면 더). 비동기로 건다.
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
            player = p
            true
        }.getOrElse {
            Log.w(TAG, "음원 재생 실패 $k: ${it.message}")
            player = null
            false
        }
    }

    private fun stopVoice() {
        queued.clear()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    override fun say(text: String, urgent: Boolean) {
        val k = keyOf(text)
        if (k !in have) {
            inner.say(text, urgent)
            return
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "오디오 포커스 거절: $text")
            return
        }
        if (urgent) {
            // 급한 말(멀미 선행 안내)은 앞의 말을 끊는다. 늦게 나오면 쓸모가 없다.
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            queued.clear()
        }
        queued.addLast(k)
        if (player == null) playNext()
        Log.i(TAG, "음원: $text")
    }

    override fun stop() {
        stopVoice()
        inner.stop()
    }

    override fun release() {
        stopVoice()
        inner.release()
    }
}
