package ai.firstwheel.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 음성 안내. 주행 중에는 화면보다 음성이 우선이다. */
interface VoiceCoach {
    fun speak(text: String)
    fun shutdown() = Unit
}

/**
 * 안드로이드 기본 TTS 구현.
 * TODO(Gleo AI SDK): TextToSpeech SDK로 교체하면 차량 음성 비서와 같은 목소리로 안내할 수 있다.
 */
class AndroidTtsVoiceCoach(context: Context) : VoiceCoach {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply { language = Locale.KOREAN }

    override fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    override fun shutdown() = tts.shutdown()
}

/** 하루 리포트 피드백 문장 생성기 */
fun interface FeedbackGenerator {
    /** 문장을 만들지 못하면 null → 규칙 기반 문장(FeedbackWriter.fallback)을 사용 */
    suspend fun generate(prompt: String): String?
}

/**
 * TODO(Gleo AI SDK): LLM SDK로 프롬프트를 보내고 응답 문장을 돌려준다.
 * 네트워크나 SDK를 쓸 수 없을 때를 대비해 지금은 null을 돌려 규칙 기반 피드백을 쓰게 한다.
 */
class GleoLlmFeedbackGenerator : FeedbackGenerator {
    override suspend fun generate(prompt: String): String? = null
}
