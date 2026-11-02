package ai.firstwheel.app.data

import ai.firstwheel.core.model.Gear
import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.TrackedVehicle
import ai.firstwheel.core.model.TurnSignal
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.model.WarningType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 에뮬레이터·시연용 가상 주행 데이터.
 * 시연 영상의 '고속도로 출구 앞 차선 변경' 장면을 그대로 재현한다.
 *
 *  0초  : 시속 92km 주행, 출구까지 1.2km, 오른쪽으로 두 차선 이동 필요
 *  0~6초: 오른쪽 차선 20m 뒤에서 차가 초속 5m 빠르게 접근 → 지나감
 *          (약 2초부터 차선 변경 준비 안내, 약 6초에 이동 가능)
 *  9초  : 방향지시등 켜고 첫 번째 차선 변경
 *  13초 : 두 번째 차선 변경, 출구 차선 진입
 *  22초 : 처음부터 다시 반복 (심사·시연 중 계속 볼 수 있도록)
 */
class SimulatedVehicleDataSource : VehicleDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

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
        job = scope.launch {
            while (isActive) {
                runScenarioOnce()
                delay(2_000)
            }
        }
    }

    private suspend fun runScenarioOnce() {
        coroutineScope {
            val startMs = System.currentTimeMillis()
            var distanceToExit = 1200.0
            var lanesToMove = 2
            var rearCarLong = -20.0
            while (isActive) {
                val now = System.currentTimeMillis()
                val t = (now - startMs) / 1000.0
                val speedKmh = 92.0
                distanceToExit -= speedKmh / 3.6 * 0.1
                rearCarLong += 5.0 * 0.1

                if (lanesToMove == 2 && t >= 9.0) { lanesToMove = 1; _laneChanges.tryEmit(now) }
                if (lanesToMove == 1 && t >= 13.0) { lanesToMove = 0; _laneChanges.tryEmit(now) }

                // 첫 차선 변경 전까지만 오른쪽 차선 접근 차량을 보여 준다
                _surroundings.value =
                    if (lanesToMove == 2) listOf(TrackedVehicle(1, laneOffset = 1, longitudinalM = rearCarLong, relativeSpeedMps = 5.0))
                    else emptyList()
                _navigation.value =
                    if (lanesToMove > 0) NavGuidance("판교 출구", distanceToExit, lanesToMove, Side.RIGHT) else null

                val signal = if ((t in 8.5..9.5) || (t in 12.0..13.5)) TurnSignal.RIGHT else TurnSignal.NONE
                _samples.tryEmit(VehicleSample(now, speedKmh, 0.0, false, Gear.DRIVE, signal))
                if (t > 20) break
                delay(100)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }
}
