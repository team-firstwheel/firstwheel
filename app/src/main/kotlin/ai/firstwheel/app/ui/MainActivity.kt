package ai.firstwheel.app.ui

import ai.firstwheel.app.coach.CoachEngine
import ai.firstwheel.app.coach.CoachUiState
import ai.firstwheel.app.coach.Tone
import ai.firstwheel.app.data.PleosVehicleDataSource
import ai.firstwheel.app.data.SimulatedVehicleDataSource
import ai.firstwheel.app.voice.AndroidTtsVoiceCoach
import ai.firstwheel.app.voice.GleoLlmFeedbackGenerator
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel

/** 시연할 때는 true: 가상 주행 데이터로 차선 변경 장면을 재현한다 */
private const val USE_SIMULATION = true

class CoachViewModel(app: Application) : AndroidViewModel(app) {
    private val voice = AndroidTtsVoiceCoach(app)
    val engine = CoachEngine(
        source = if (USE_SIMULATION) SimulatedVehicleDataSource() else PleosVehicleDataSource(app),
        voice = voice,
        feedback = GleoLlmFeedbackGenerator(),
        scope = viewModelScope,
    ).also { it.start() }

    override fun onCleared() {
        engine.stop()
        voice.shutdown()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: CoachViewModel = viewModel()
            val state by vm.engine.ui.collectAsStateWithLifecycle()
            CoachScreen(state)
        }
    }
}

private val Asphalt = Color(0xFF1B222C)
private val Panel = Color(0xFF232C38)
private val LaneYellow = Color(0xFFF4C542)
private val Go = Color(0xFF3DD68C)
private val Wait = Color(0xFFFF6A55)
private val Paint = Color(0xFFE8ECF1)
private val Muted = Color(0xFF8C98AA)

/** 주행 중 시선을 덜 빼앗도록 큰 글씨 하나와 짧은 보조 문장만 보여 준다 */
@Composable
fun CoachScreen(s: CoachUiState) {
    val accent = when (s.tone) { Tone.GO -> Go; Tone.WAIT -> Wait; Tone.NORMAL -> Paint }
    Row(Modifier.fillMaxSize().background(Asphalt)) {
        Box(Modifier.weight(0.57f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            // TODO: 시안처럼 도로·주차장 탑뷰를 Canvas로 그린다 (주변 차량, 추천 자리, 예상 궤적)
            Text("${s.speedKmh} km/h", color = Muted, fontSize = 40.sp)
        }
        Column(Modifier.weight(0.43f).fillMaxHeight().background(Panel).padding(48.dp)) {
            Text(s.tag, color = Muted, fontSize = 24.sp)
            Spacer(Modifier.height(12.dp))
            Text(s.title, color = accent, fontSize = 52.sp, fontWeight = FontWeight.Black, lineHeight = 62.sp)
            Spacer(Modifier.height(16.dp))
            Text(s.subtitle, color = Paint.copy(alpha = 0.8f), fontSize = 28.sp, lineHeight = 40.sp)
            Spacer(Modifier.weight(1f))
            s.countdown?.let {
                Box(Modifier.size(200.dp).background(Asphalt, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$it", color = accent, fontSize = 110.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
