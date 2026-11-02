package ai.firstwheel.core.scoring

import ai.firstwheel.core.model.CarProfile
import ai.firstwheel.core.model.TurnSignal
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.model.WarningType
import kotlin.math.abs
import kotlin.math.tan

/** 운전 점수에 반영되는 위험 운전 이벤트 */
enum class DrivingEventType(val label: String) {
    HARSH_ACCEL("급가속"),
    HARSH_BRAKE("급브레이크"),
    SHARP_TURN("급커브"),
    LANE_CHANGE_NO_SIGNAL("방향지시등 미사용"),
    LANE_DEPARTURE("차선 이탈 경고"),
    COLLISION_WARNING("충돌 경고"),
    PROXIMITY_WARNING("근접 경고"),
}

data class DrivingEvent(val type: DrivingEventType, val timestampMs: Long)

/**
 * 이벤트 판단 기준값.
 * 실제 서비스에서는 한국교통안전공단 위험운전행동 기준 등 공신력 있는 기준과
 * 주행 데이터를 바탕으로 조정한다.
 */
data class DetectionThresholds(
    val harshAccelMps2: Double = 3.0,
    val harshBrakeMps2: Double = -3.5,
    val sharpTurnLateralMps2: Double = 3.5,
    val minSpeedForTurnKmh: Double = 20.0,
    /** 같은 종류의 이벤트를 중복으로 세지 않는 시간 */
    val cooldownMs: Long = 2_000,
    /** 차선 변경 전 이 시간 안에 방향지시등이 켜졌으면 정상으로 본다 */
    val signalLookbackMs: Long = 3_000,
)

/**
 * 연속된 차량 신호에서 위험 운전 이벤트를 찾는다.
 * - 급가속·급브레이크: 속도 변화율(m/s²)
 * - 급커브: 핸들 각도로 추정한 횡가속도 (자전거 모델 a = v² · tan(δ) / L)
 * - 방향지시등 미사용: 차선 변경 직전 일정 시간 안에 방향지시등이 없었는지
 */
class DrivingEventDetector(
    private val car: CarProfile = CarProfile(),
    private val th: DetectionThresholds = DetectionThresholds(),
) {
    private var prev: VehicleSample? = null
    private var lastSignalOnMs: Long? = null
    private val lastEventMs = mutableMapOf<DrivingEventType, Long>()
    private val _events = mutableListOf<DrivingEvent>()
    val events: List<DrivingEvent> get() = _events

    fun onSample(s: VehicleSample): List<DrivingEvent> {
        val found = mutableListOf<DrivingEvent>()
        if (s.turnSignal != TurnSignal.NONE) lastSignalOnMs = s.timestampMs
        prev?.let { p ->
            val dt = (s.timestampMs - p.timestampMs) / 1000.0
            if (dt > 0) {
                val accel = (s.speedMps - p.speedMps) / dt
                if (accel >= th.harshAccelMps2) emit(DrivingEventType.HARSH_ACCEL, s.timestampMs, found)
                if (accel <= th.harshBrakeMps2) emit(DrivingEventType.HARSH_BRAKE, s.timestampMs, found)
            }
        }
        if (s.speedKmh >= th.minSpeedForTurnKmh && lateralAccel(s) >= th.sharpTurnLateralMps2) {
            emit(DrivingEventType.SHARP_TURN, s.timestampMs, found)
        }
        prev = s
        return found
    }

    /** 차선 정보(ADAS Local Map)로 차선 변경이 감지되었을 때 호출 */
    fun onLaneChanged(timestampMs: Long): DrivingEvent? {
        val signal = lastSignalOnMs
        if (signal != null && timestampMs - signal <= th.signalLookbackMs) return null
        return mutableListOf<DrivingEvent>()
            .also { emit(DrivingEventType.LANE_CHANGE_NO_SIGNAL, timestampMs, it) }
            .firstOrNull()
    }

    /** 차량 경고(ADAS Warning API)가 올라왔을 때 호출 */
    fun onWarning(type: WarningType, timestampMs: Long): DrivingEvent? {
        val eventType = when (type) {
            WarningType.LANE_DEPARTURE -> DrivingEventType.LANE_DEPARTURE
            WarningType.COLLISION -> DrivingEventType.COLLISION_WARNING
            WarningType.PROXIMITY -> DrivingEventType.PROXIMITY_WARNING
        }
        return mutableListOf<DrivingEvent>()
            .also { emit(eventType, timestampMs, it) }
            .firstOrNull()
    }

    /** 핸들 각도와 속도로 추정한 횡가속도(m/s²) */
    fun lateralAccel(s: VehicleSample): Double {
        val wheelAngleRad = Math.toRadians(s.steeringWheelAngleDeg / car.steeringRatio)
        val v = s.speedMps
        return abs(v * v * tan(wheelAngleRad) / car.wheelbaseM)
    }

    private fun emit(type: DrivingEventType, ts: Long, out: MutableList<DrivingEvent>) {
        val last = lastEventMs[type]
        if (last != null && ts - last < th.cooldownMs) return
        lastEventMs[type] = ts
        val e = DrivingEvent(type, ts)
        _events += e
        out += e
    }
}
