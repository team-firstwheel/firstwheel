package ai.firstwheel.core.lanechange

import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.TrackedVehicle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** 차선 변경 안내 결과 */
sealed interface LaneChangeAdvice {
    val side: Side

    /** 지금 바로 이동 가능 */
    data class SafeNow(override val side: Side) : LaneChangeAdvice

    /** secondsUntilSafe 초 뒤 이동 가능 (화면 카운트다운은 올림한 정수) */
    data class Wait(override val side: Side, val secondsUntilSafe: Double) : LaneChangeAdvice {
        val countdown: Int get() = ceil(secondsUntilSafe - 1e-9).toInt()
    }

    /** 예측 범위 안에서 안전한 틈이 없음 */
    data class NotAvailable(override val side: Side) : LaneChangeAdvice
}

data class LaneChangeParams(
    val egoLengthM: Double = 4.7,
    /** 앞뒤 차와 최소한 확보할 간격(범퍼 사이 거리) */
    val minGapM: Double = 6.0,
    /** 뒤차가 다가오는 속도 1m/s당 추가로 필요한 간격(초) */
    val rearTimeGapS: Double = 2.5,
    /** 앞차에 다가가는 속도 1m/s당 추가로 필요한 간격(초) */
    val frontTimeGapS: Double = 1.5,
    /** 몇 초 앞까지 예측할지 */
    val horizonS: Double = 8.0,
    val stepS: Double = 0.1,
    /** 안전 상태가 최소 이 시간 동안 유지되어야 이동 가능으로 본다 */
    val stableWindowS: Double = 1.0,
)

/**
 * 목표 차선의 앞뒤 차량 위치·상대 속도로 안전한 틈(Gap)이 생기는 시점을 예측한다.
 *
 * 각 차량이 현재 상대 속도를 유지한다고 보고 0.1초 단위로 미래 위치를 계산한 뒤,
 * 모든 차량과의 간격이 필요 간격 이상인 상태가 [LaneChangeParams.stableWindowS] 동안 유지되는
 * 가장 이른 시점을 찾는다. 입력은 ADAS SDK의 Tracked Object / Local Map 정보를 변환해 만든다.
 */
class LaneChangeAdvisor(private val p: LaneChangeParams = LaneChangeParams()) {

    fun advise(side: Side, vehicles: List<TrackedVehicle>): LaneChangeAdvice {
        val targetLane = if (side == Side.LEFT) -1 else 1
        val target = vehicles.filter { it.laneOffset == targetLane }
        val steps = (p.horizonS / p.stepS).roundToInt()
        val window = (p.stableWindowS / p.stepS).roundToInt()
        val safe = BooleanArray(steps + window + 1) { i -> isSafeAt(i * p.stepS, target) }
        for (i in 0..steps) {
            if ((i..i + window).all { safe[it] }) {
                return if (i == 0) LaneChangeAdvice.SafeNow(side)
                else LaneChangeAdvice.Wait(side, i * p.stepS)
            }
        }
        return LaneChangeAdvice.NotAvailable(side)
    }

    /** t초 뒤 목표 차선의 모든 차량과 충분한 간격이 있는지 */
    fun isSafeAt(t: Double, target: List<TrackedVehicle>): Boolean = target.all { v ->
        val long = v.longitudinalM + v.relativeSpeedMps * t
        val halfLengths = (p.egoLengthM + v.lengthM) / 2
        if (long >= 0) {
            val gap = long - halfLengths
            val closing = max(0.0, -v.relativeSpeedMps) // 내가 더 빨라 앞차에 다가가는 속도
            gap >= p.minGapM + closing * p.frontTimeGapS
        } else {
            val gap = -long - halfLengths
            val closing = max(0.0, v.relativeSpeedMps) // 뒤차가 나에게 다가오는 속도
            gap >= p.minGapM + closing * p.rearTimeGapS
        }
    }
}

/**
 * 내비게이션(NaviHelper SDK) 경로의 회전·출구 지점까지 남은 거리로
 * 차선 변경 준비 안내를 시작할 시점인지 판단한다.
 */
object LaneChangePlanner {
    fun shouldStartPreparing(
        distanceToManeuverM: Double,
        lanesToMove: Int,
        speedMps: Double,
        secondsPerLane: Double = 15.0,
        bufferS: Double = 15.0,
    ): Boolean {
        if (lanesToMove <= 0) return false
        if (speedMps < 1.0) return distanceToManeuverM <= lanesToMove * 200.0
        val secondsLeft = distanceToManeuverM / speedMps
        return secondsLeft <= lanesToMove * secondsPerLane + bufferS
    }
}
