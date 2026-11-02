package ai.firstwheel.app.data

import ai.firstwheel.core.model.Gear
import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.TrackedVehicle
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.model.WarningType
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** 내비게이션(NaviHelper SDK)에서 받은 다음 회전·출구 정보 */
data class NavGuidance(
    val maneuverName: String,
    val distanceToManeuverM: Double,
    val lanesToMove: Int,
    val side: Side,
)

/**
 * 코칭 엔진이 사용하는 차량 데이터 입력.
 * 실제 차량에서는 [PleosVehicleDataSource], 에뮬레이터 시연에서는 [SimulatedVehicleDataSource]를 쓴다.
 */
interface VehicleDataSource {
    val samples: Flow<VehicleSample>
    val surroundings: Flow<List<TrackedVehicle>>
    val warnings: Flow<WarningType>
    /** 차선 변경이 감지된 시각(ms) */
    val laneChanges: Flow<Long>
    val navigation: Flow<NavGuidance?>
    /** 후방 장애물까지 거리(m). 알 수 없으면 null */
    val rearClearanceM: Flow<Double?>

    fun start()
    fun stop()
}

/**
 * AAOS 표준 Car API로 속도와 기어를 읽는다.
 * Pleos Connect SDK 정책상 AAOS 표준에 있는 데이터는 표준 API를 사용한다.
 */
class CarPropertyReader(context: Context) {
    private val car: Car = Car.createCar(context)
    private val propertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    private val _speedKmh = MutableStateFlow(0.0)
    val speedKmh: StateFlow<Double> = _speedKmh
    private val _gear = MutableStateFlow(Gear.PARK)
    val gear: StateFlow<Gear> = _gear

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    _speedKmh.value = abs((value.value as Float).toDouble()) * 3.6
                VehiclePropertyIds.GEAR_SELECTION ->
                    _gear.value = when (value.value as Int) {
                        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                        VehicleGear.GEAR_PARK -> Gear.PARK
                        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                        else -> Gear.DRIVE
                    }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    fun start() {
        propertyManager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
        propertyManager.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }

    fun stop() {
        propertyManager.unregisterCallback(callback)
        car.disconnect()
    }
}
