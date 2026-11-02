package ai.firstwheel.core.parking

import ai.firstwheel.core.model.CarProfile
import ai.firstwheel.core.model.Gear
import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.VehicleSample
import kotlin.math.abs
import kotlin.math.tan

/** 후진 직각 주차 단계 */
enum class ParkingStep { DRIVE_PAST, STOP_AND_REVERSE, FULL_LOCK, STRAIGHTEN, STOP, DONE }

data class ParkingCue(val step: ParkingStep, val message: String)

/**
 * 운전자의 주차 습관.
 * reactionLagS: 안내를 들은 뒤 실제로 핸들을 꺾기까지 걸린 평균 시간(초).
 * 이 값만큼 안내를 일찍 하면 늦게 꺾는 습관을 보정할 수 있다.
 */
data class ParkingHabit(val reactionLagS: Double = 0.0, val sessions: Int = 0) {
    fun update(measuredLagS: Double, alpha: Double = 0.3): ParkingHabit {
        val lag = measuredLagS.coerceIn(0.0, 2.0)
        val next = if (sessions == 0) lag else (1 - alpha) * reactionLagS + alpha * lag
        return ParkingHabit(next, sessions + 1)
    }
}

data class ParkingParams(
    /** 주차면 중심을 이만큼 지나서 멈추면 한 번에 들어가기 좋다 (기본: 최소 회전 반경 - 0.5m) */
    val stopPastSpotCenterM: Double? = null,
    /** 차가 이 각도만큼 돌기 전에 '핸들 풀기'를 안내한다 */
    val baseStraightenLeadDeg: Double = 10.0,
    /** 뒤쪽 여유가 이 거리 이하가 되면 정지 안내 */
    val stopClearanceM: Double = 0.4,
    /** 핸들을 최대 조향의 몇 % 이상 돌리면 '끝까지 돌렸다'고 볼지 */
    val fullLockRatio: Double = 0.8,
)

/**
 * 후진 직각 주차 코치.
 *
 * 차량 신호로 이동 거리와 회전 각도를 적분해(자전거 모델) 현재 단계를 판단하고,
 * 단계가 바뀔 때마다 음성·화면 안내 문구를 돌려준다.
 * 안내 시점은 [ParkingHabit.reactionLagS]만큼 앞당겨 운전자 습관에 맞춘다.
 */
class ParkingCoach(
    private val car: CarProfile = CarProfile(),
    private val side: Side = Side.RIGHT,
    habit: ParkingHabit = ParkingHabit(),
    private val params: ParkingParams = ParkingParams(),
) {
    var step: ParkingStep = ParkingStep.DRIVE_PAST
        private set
    var habit: ParkingHabit = habit
        private set
    var retries: Int = 0
        private set
    /** FULL_LOCK 단계에서 차가 돈 각도(도) */
    var headingChangeDeg: Double = 0.0
        private set

    private var distancePastCenterM = 0.0
    private var lastMs: Long? = null
    private var fullLockCueMs: Long? = null
    private val dirWord = if (side == Side.RIGHT) "오른쪽" else "왼쪽"
    private val dirSign = if (side == Side.RIGHT) 1.0 else -1.0

    /**
     * 주차 코치를 시작한다.
     * @param distancePastSpotCenterM 추천 주차면 중심을 지난 거리(아직 지나지 않았으면 음수).
     *        여유 자리 추천 단계에서 ADAS 객체 정보로 계산해 넘겨준다.
     */
    fun start(distancePastSpotCenterM: Double): ParkingCue {
        distancePastCenterM = distancePastSpotCenterM
        step = ParkingStep.DRIVE_PAST
        return ParkingCue(step, "천천히 앞으로 가세요. 멈출 위치를 알려 드릴게요.")
    }

    fun onSample(s: VehicleSample, rearClearanceM: Double? = null): ParkingCue? {
        val dt = lastMs?.let { (s.timestampMs - it) / 1000.0 } ?: 0.0
        lastMs = s.timestampMs
        val speed = abs(s.speedMps)
        val signedSpeed = if (s.gear == Gear.REVERSE) -speed else speed

        return when (step) {
            ParkingStep.DRIVE_PAST -> {
                distancePastCenterM += signedSpeed * dt
                val target = params.stopPastSpotCenterM ?: (car.minTurningRadiusM - 0.5)
                val lead = speed * habit.reactionLagS
                if (distancePastCenterM >= target - lead) move(ParkingStep.STOP_AND_REVERSE, "멈추고 후진 기어를 넣으세요.")
                else null
            }
            ParkingStep.STOP_AND_REVERSE ->
                if (s.gear == Gear.REVERSE) {
                    fullLockCueMs = s.timestampMs
                    headingChangeDeg = 0.0
                    move(ParkingStep.FULL_LOCK, "지금 핸들을 ${dirWord} 끝까지 돌리고 천천히 후진하세요.")
                } else null
            ParkingStep.FULL_LOCK -> {
                if (s.gear == Gear.DRIVE) return retry()
                learnReaction(s)
                val yawRateDeg = yawRateDeg(s, speed)
                headingChangeDeg += yawRateDeg * dt
                val lead = params.baseStraightenLeadDeg + yawRateDeg * habit.reactionLagS
                if (headingChangeDeg >= 90.0 - lead) move(ParkingStep.STRAIGHTEN, "핸들을 풀고 똑바로 후진하세요.")
                else null
            }
            ParkingStep.STRAIGHTEN -> {
                if (s.gear == Gear.DRIVE) return retry()
                val limit = params.stopClearanceM + speed * habit.reactionLagS
                if (rearClearanceM != null && rearClearanceM <= limit) move(ParkingStep.STOP, "정지하세요.")
                else null
            }
            ParkingStep.STOP ->
                if (speed < 0.05 && (s.brakePressed || s.gear == Gear.PARK)) {
                    val msg = if (retries == 0) "주차 완료! 한 번에 성공했어요." else "주차 완료! 잘하셨어요."
                    move(ParkingStep.DONE, msg)
                } else null
            ParkingStep.DONE -> null
        }
    }

    /** 핸들 각도로 계산한 차의 회전 속도(도/초). 주차 방향으로 도는 경우만 양수 */
    private fun yawRateDeg(s: VehicleSample, speed: Double): Double {
        val wheelAngleRad = Math.toRadians(s.steeringWheelAngleDeg * dirSign / car.steeringRatio)
        return Math.toDegrees(speed * tan(wheelAngleRad) / car.wheelbaseM).coerceAtLeast(0.0)
    }

    /** '핸들 끝까지' 안내 후 실제로 핸들을 끝까지 돌리기까지 걸린 시간을 학습한다 */
    private fun learnReaction(s: VehicleSample) {
        val cue = fullLockCueMs ?: return
        if (s.steeringWheelAngleDeg * dirSign >= car.maxSteeringWheelDeg * params.fullLockRatio) {
            habit = habit.update((s.timestampMs - cue) / 1000.0)
            fullLockCueMs = null
        }
    }

    private fun retry(): ParkingCue {
        retries++
        return move(ParkingStep.STOP_AND_REVERSE, "괜찮아요. 앞으로 조금 나간 뒤 멈추고 다시 후진 기어를 넣으세요.")
    }

    private fun move(next: ParkingStep, message: String): ParkingCue {
        step = next
        return ParkingCue(next, message)
    }
}

/**
 * 빈 주차면 정보. ADAS Tracked Object / AutoParking 정보로 만든다.
 * @param leftIntrusionM 왼쪽 옆 차가 이 주차면 안으로 넘어온 거리(없으면 0)
 * @param leftNeighborEmpty 왼쪽 옆 칸이 비어 있는지
 */
data class ParkingSpot(
    val id: String,
    val widthM: Double,
    val lengthM: Double,
    val leftIntrusionM: Double = 0.0,
    val rightIntrusionM: Double = 0.0,
    val leftNeighborEmpty: Boolean = false,
    val rightNeighborEmpty: Boolean = false,
    val nextToPillar: Boolean = false,
    val distanceM: Double = 0.0,
)

/** @param usableMarginM 한쪽으로 붙여 세울 수 있다고 보고 계산한 양옆 평균 여유(m) */
data class RankedSpot(val spot: ParkingSpot, val usableMarginM: Double, val score: Double)

/**
 * 내 차 크기 대비 양옆 여유가 넉넉하고 가까운 자리를 먼저 추천한다.
 * 옆 칸이 비어 있으면 그쪽으로 붙여 세울 수 있으므로 여유를 더해 준다.
 * 기둥 옆 자리와 먼 자리는 감점한다.
 */
class ParkingSpotRanker(
    private val car: CarProfile = CarProfile(),
    private val minMarginM: Double = 0.2,
    private val emptyNeighborBonusM: Double = 1.0,
    private val pillarPenalty: Double = 30.0,
    private val distancePenaltyPerM: Double = 0.5,
) {
    fun sideMargins(s: ParkingSpot): Pair<Double, Double> {
        val base = (s.widthM - car.widthM) / 2
        val left = base - s.leftIntrusionM + if (s.leftNeighborEmpty) emptyNeighborBonusM else 0.0
        val right = base - s.rightIntrusionM + if (s.rightNeighborEmpty) emptyNeighborBonusM else 0.0
        return left to right
    }

    fun rank(spots: List<ParkingSpot>): List<RankedSpot> = spots
        .filter { it.lengthM >= car.lengthM + 0.3 }
        .map { s ->
            val (l, r) = sideMargins(s)
            val m = (l + r) / 2
            val score = m * 100 - (if (s.nextToPillar) pillarPenalty else 0.0) - s.distanceM * distancePenaltyPerM
            RankedSpot(s, m, score)
        }
        .filter { it.usableMarginM >= minMarginM }
        .sortedByDescending { it.score }
}
