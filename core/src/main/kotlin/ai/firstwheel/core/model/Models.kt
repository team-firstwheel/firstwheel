package ai.firstwheel.core.model

/** 기어 상태 (AAOS GEAR_SELECTION 값을 앱 내부 표현으로 단순화) */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE }

/** 방향지시등 상태 */
enum class TurnSignal { NONE, LEFT, RIGHT }

/** 차선 변경·주차 방향 */
enum class Side { LEFT, RIGHT }

/**
 * 한 시점의 차량 신호 묶음.
 * Vehicle SDK(핸들, 브레이크, 방향지시등)와 AAOS Car API(속도, 기어)에서 받은 값을 합쳐 만든다.
 */
data class VehicleSample(
    val timestampMs: Long,
    val speedKmh: Double,
    /** 핸들 각도. 오른쪽(시계 방향)이 양수 */
    val steeringWheelAngleDeg: Double,
    val brakePressed: Boolean,
    val gear: Gear,
    val turnSignal: TurnSignal,
) {
    val speedMps: Double get() = speedKmh / 3.6
}

/**
 * ADAS Tracked Object를 내 차 기준 좌표로 바꾼 주변 차량 정보.
 * @param laneOffset 0 = 내 차선, -1 = 왼쪽 차선, +1 = 오른쪽 차선
 * @param longitudinalM 내 차 중심에서 상대 차 중심까지 거리. 앞 = 양수, 뒤 = 음수
 * @param relativeSpeedMps 상대 차 속도 - 내 차 속도. 양수면 상대 차가 더 빠르다
 */
data class TrackedVehicle(
    val id: Int,
    val laneOffset: Int,
    val longitudinalM: Double,
    val relativeSpeedMps: Double,
    val lengthM: Double = 4.7,
)

/** 차량 제원. CarInfo API의 외부 크기 정보와 차종별 설정값으로 채운다. */
data class CarProfile(
    val lengthM: Double = 4.7,
    val widthM: Double = 1.88,
    val wheelbaseM: Double = 2.8,
    val steeringRatio: Double = 14.5,
    val maxSteeringWheelDeg: Double = 480.0,
    val minTurningRadiusM: Double = 5.5,
)

/** 차량에서 올라오는 경고 (ADAS Warning API / Safety API) */
enum class WarningType { LANE_DEPARTURE, COLLISION, PROXIMITY }
