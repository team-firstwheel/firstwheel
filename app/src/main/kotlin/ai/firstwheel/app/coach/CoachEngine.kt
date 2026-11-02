package ai.firstwheel.app.coach

import ai.firstwheel.app.data.NavGuidance
import ai.firstwheel.app.data.VehicleDataSource
import ai.firstwheel.app.voice.FeedbackGenerator
import ai.firstwheel.app.voice.VoiceCoach
import ai.firstwheel.core.lanechange.LaneChangeAdvice
import ai.firstwheel.core.lanechange.LaneChangeAdvisor
import ai.firstwheel.core.lanechange.LaneChangePlanner
import ai.firstwheel.core.model.CarProfile
import ai.firstwheel.core.model.Gear
import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.TrackedVehicle
import ai.firstwheel.core.model.TurnSignal
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.parking.ParkingCoach
import ai.firstwheel.core.parking.ParkingHabit
import ai.firstwheel.core.parking.ParkingStep
import ai.firstwheel.core.scoring.DailyReport
import ai.firstwheel.core.scoring.DrivingEventDetector
import ai.firstwheel.core.scoring.DrivingScoreCalculator
import ai.firstwheel.core.scoring.FeedbackWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class CoachMode { READY, DRIVING, LANE_CHANGE, PARKING, REPORT }
enum class Tone { NORMAL, GO, WAIT }

/** 화면에 보여 줄 코칭 상태 (시안의 오른쪽 안내 패널과 같은 구성) */
data class CoachUiState(
    val mode: CoachMode = CoachMode.READY,
    val tag: String = "오늘의 코칭",
    val title: String = "안전 운전을 시작해요",
    val subtitle: String = "",
    val tone: Tone = Tone.NORMAL,
    val countdown: Int? = null,
    val speedKmh: Int = 0,
)

/**
 * 차량 데이터를 받아 운전 점수·차선 변경·주차 코칭을 실행하고 화면 상태와 음성 안내를 만든다.
 * 판단 로직은 모두 :core 모듈에 있고, 이 클래스는 입력을 연결하고 결과를 보여 주는 역할만 한다.
 */
class CoachEngine(
    private val source: VehicleDataSource,
    private val voice: VoiceCoach,
    private val feedback: FeedbackGenerator,
    private val scope: CoroutineScope,
    private val car: CarProfile = CarProfile(),
    parkingHabit: ParkingHabit = ParkingHabit(),
) {
    private val detector = DrivingEventDetector(car)
    private val advisor = LaneChangeAdvisor()
    private var parking: ParkingCoach? = null
    private var habit = parkingHabit
    private var lastSpoken: String? = null
    private var latestClearance: Double? = null

    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui

    fun start() {
        source.start()
        scope.launch { source.rearClearanceM.collect { latestClearance = it } }
        scope.launch { source.samples.collect(::onSample) }
        scope.launch { source.warnings.collect { detector.onWarning(it, System.currentTimeMillis()) } }
        scope.launch { source.laneChanges.collect { detector.onLaneChanged(it) } }
        scope.launch {
            combine(source.samples, source.surroundings, source.navigation) { s, cars, nav -> Triple(s, cars, nav) }
                .collect { (s, cars, nav) -> onLaneContext(s, cars, nav) }
        }
    }

    fun stop() = source.stop()

    private fun onSample(s: VehicleSample) {
        detector.onSample(s)
        if (s.gear == Gear.REVERSE && parking == null) {
            // 여유 자리 추천에서 계산한 위치가 없으면 '이미 기준점을 지났다'고 보고 바로 후진 단계부터 안내
            parking = ParkingCoach(car, Side.RIGHT, habit).also { it.start(distancePastSpotCenterM = 99.0) }
        }
        val coach = parking ?: return
        coach.onSample(s, latestClearance)?.let { cue ->
            val tone = when (cue.step) {
                ParkingStep.STOP_AND_REVERSE, ParkingStep.STOP -> Tone.WAIT
                else -> Tone.GO
            }
            show(CoachUiState(CoachMode.PARKING, "AI 주차 코치", cue.message, tone = tone, speedKmh = s.speedKmh.toInt()))
            if (cue.step == ParkingStep.DONE) {
                habit = coach.habit // 다음 주차 때 반영할 습관 저장
                parking = null
            }
        }
    }

    private fun onLaneContext(s: VehicleSample, cars: List<TrackedVehicle>, nav: NavGuidance?) {
        if (parking != null) return
        val signalSide = when (s.turnSignal) {
            TurnSignal.LEFT -> Side.LEFT
            TurnSignal.RIGHT -> Side.RIGHT
            TurnSignal.NONE -> null
        }
        val navSide = nav?.takeIf {
            LaneChangePlanner.shouldStartPreparing(it.distanceToManeuverM, it.lanesToMove, s.speedMps)
        }?.side
        val side = signalSide ?: navSide
        if (side == null) {
            if (_ui.value.mode == CoachMode.LANE_CHANGE) show(CoachUiState(CoachMode.DRIVING, "주행 중", "안전 운전 중이에요", speedKmh = s.speedKmh.toInt()))
            return
        }
        val dir = if (side == Side.RIGHT) "오른쪽" else "왼쪽"
        val sub = nav?.let { "${it.maneuverName}까지 ${"%.1f".format(it.distanceToManeuverM / 1000)}km, ${dir}으로 ${it.lanesToMove}차선 이동" } ?: ""
        val state = when (val a = advisor.advise(side, cars)) {
            is LaneChangeAdvice.SafeNow ->
                CoachUiState(CoachMode.LANE_CHANGE, "차선 변경 안내", "지금 방향지시등을 켜고 이동하세요", sub, Tone.GO)
            is LaneChangeAdvice.Wait ->
                CoachUiState(CoachMode.LANE_CHANGE, "차선 변경 안내", "잠시 기다리세요", "${a.countdown}초 후 ${dir} 차선으로 이동할 수 있어요", Tone.WAIT, a.countdown)
            is LaneChangeAdvice.NotAvailable ->
                CoachUiState(CoachMode.LANE_CHANGE, "차선 변경 안내", "지금은 이동하기 어려워요", "${dir} 차선이 막혀 있어요. 속도를 유지하세요", Tone.WAIT)
        }
        show(state.copy(speedKmh = s.speedKmh.toInt()), speakText = state.title)
    }

    /** 주행이 끝났을 때 하루 리포트를 만들고 AI 피드백을 읽어 준다 */
    suspend fun finishTrip(previousScore: Int?): Pair<DailyReport, String> {
        val report = DrivingScoreCalculator().report(detector.events, previousScore)
        val message = feedback.generate(FeedbackWriter.llmPrompt(report)) ?: FeedbackWriter.fallback(report)
        show(CoachUiState(CoachMode.REPORT, "오늘의 운전 리포트", "${report.score}점", message), speakText = message)
        return report to message
    }

    /** 같은 문장을 반복해서 읽지 않도록, 안내 문구가 바뀔 때만 음성으로 읽는다 */
    private fun show(state: CoachUiState, speakText: String = state.title) {
        _ui.value = state
        if (speakText != lastSpoken) {
            lastSpoken = speakText
            voice.speak(speakText)
        }
    }
}
