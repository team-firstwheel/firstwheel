package ai.firstwheel.app.data

import ai.firstwheel.core.model.TrackedVehicle
import ai.firstwheel.core.model.TurnSignal
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.model.WarningType
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 실제 차량(Pleos Connect)용 데이터 소스.
 *
 * - 속도·기어: AAOS Car API ([CarPropertyReader])
 * - 핸들 각도·브레이크·방향지시등: Pleos Connect Vehicle SDK (Steeringwheel / Brake / TurnSignal API)
 * - 주변 차량·차선·경고: Pleos Connect ADAS SDK (Tracked Object / Local Map / Warning API)
 * - 회전·출구 안내: NaviHelper SDK
 *
 * 아래 TODO의 SDK 호출 형태는 Pleos Playground 공개 문서와 튜토리얼을 참고해 적은 것이다.
 * 패키지 경로와 데이터 클래스 필드는 사용하는 SDK 버전의 API Reference로 확정해야 한다.
 * ADAS SDK는 차종·버전에 따라 지원 여부가 다르므로, 미지원 차량에서는 surroundings가 빈 목록으로 남고
 * 운전 점수·주차 코치(Vehicle SDK 기반)만 동작하도록 설계했다.
 */
class PleosVehicleDataSource(context: Context) : VehicleDataSource {

    private val carProps = CarPropertyReader(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sampler: Job? = null

    private val steeringDeg = MutableStateFlow(0.0)
    private val brakePressed = MutableStateFlow(false)
    private val turnSignal = MutableStateFlow(TurnSignal.NONE)

    private val _samples = MutableSharedFlow<VehicleSample>(extraBufferCapacity = 64)
    override val samples: Flow<VehicleSample> = _samples
    private val _surroundings = MutableStateFlow<List<TrackedVehicle>>(emptyList())
    override val surroundings: Flow<List<TrackedVehicle>> = _surroundings
    private val _warnings = MutableSharedFlow<WarningType>(extraBufferCapacity = 16)
    override val warnings: Flow<WarningType> = _warnings
    private val _laneChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val laneChanges: Flow<Long> = _laneChanges
    private val _navigation = MutableStateFlow<NavGuidance?>(null)
    override val navigation: Flow<NavGuidance?> = _navigation
    private val _rearClearance = MutableStateFlow<Double?>(null)
    override val rearClearanceM: Flow<Double?> = _rearClearance

    override fun start() {
        carProps.start()

        // TODO(Vehicle SDK) 핸들 각도 콜백 등록 — 튜토리얼 'Callback Steering Wheel Angle API' 참고
        // vehicle.registerSteeringWheelAngle { angle -> steeringDeg.value = angle.toDouble() }
        // TODO(Vehicle SDK) Brake API로 브레이크 페달 상태 구독 → brakePressed
        // TODO(Vehicle SDK) TurnSignal API로 방향지시등 상태 구독 → turnSignal

        // TODO(ADAS SDK) 'Make the first API call' 문서 참고
        // val adas = Adas(context); adas.initialize()
        // adas.subscribeTrackedObjects { data -> _surroundings.value = data.toTrackedVehicles() }
        // adas.subscribeLocalMap(object : SubscribeLocalMap {
        //     override fun onLaneDataChange(data: Lanes) { /* 내 차선 번호가 바뀌면 _laneChanges.tryEmit(now) */ }
        //     override fun onTrafficLightsChange(data: TrafficLights) = Unit
        // })
        // adas.subscribeWarning(object : ObserveWarning {
        //     override fun onChangedCollision(data: CollisionWarning) { _warnings.tryEmit(WarningType.COLLISION) }
        //     override fun onChangedLaneDeparture(data: LaneDepartureWarning) { _warnings.tryEmit(WarningType.LANE_DEPARTURE) }
        //     override fun onChangedProximity(data: ProximityWarning) { /* 후방 거리 → _rearClearance */ }
        // })

        // TODO(NaviHelper SDK) 경로 안내 정보 구독 → _navigation (다음 회전·출구까지 거리, 필요한 차선 이동 수)

        // 여러 SDK에서 따로 들어오는 값을 10Hz 샘플 하나로 묶는다.
        sampler = scope.launch {
            while (isActive) {
                _samples.tryEmit(
                    VehicleSample(
                        timestampMs = System.currentTimeMillis(),
                        speedKmh = carProps.speedKmh.value,
                        steeringWheelAngleDeg = steeringDeg.value,
                        brakePressed = brakePressed.value,
                        gear = carProps.gear.value,
                        turnSignal = turnSignal.value,
                    ),
                )
                delay(100)
            }
        }
    }

    override fun stop() {
        sampler?.cancel()
        carProps.stop()
        // TODO: adas.stop(); adas.release(); vehicle.release()
    }
}
