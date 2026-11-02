package ai.firstwheel.core

import ai.firstwheel.core.lanechange.LaneChangeAdvice
import ai.firstwheel.core.lanechange.LaneChangeAdvisor
import ai.firstwheel.core.lanechange.LaneChangePlanner
import ai.firstwheel.core.model.Gear
import ai.firstwheel.core.model.Side
import ai.firstwheel.core.model.TrackedVehicle
import ai.firstwheel.core.model.TurnSignal
import ai.firstwheel.core.model.VehicleSample
import ai.firstwheel.core.model.WarningType
import ai.firstwheel.core.parking.ParkingCoach
import ai.firstwheel.core.parking.ParkingHabit
import ai.firstwheel.core.parking.ParkingSpot
import ai.firstwheel.core.parking.ParkingSpotRanker
import ai.firstwheel.core.parking.ParkingStep
import ai.firstwheel.core.scoring.DrivingEventDetector
import ai.firstwheel.core.scoring.DrivingEventType
import ai.firstwheel.core.scoring.DrivingScoreCalculator
import ai.firstwheel.core.scoring.FeedbackWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sample(
    ms: Long, kmh: Double, steer: Double = 0.0, brake: Boolean = false,
    gear: Gear = Gear.DRIVE, signal: TurnSignal = TurnSignal.NONE,
) = VehicleSample(ms, kmh, steer, brake, gear, signal)

class LaneChangeAdvisorTest {
    private val advisor = LaneChangeAdvisor()

    @Test fun `목표 차선이 비어 있으면 바로 이동 가능`() {
        assertIs<LaneChangeAdvice.SafeNow>(advisor.advise(Side.RIGHT, emptyList()))
    }

    @Test fun `다른 차선의 차는 판단에 영향을 주지 않는다`() {
        val leftCar = TrackedVehicle(1, laneOffset = -1, longitudinalM = 0.0, relativeSpeedMps = 0.0)
        assertIs<LaneChangeAdvice.SafeNow>(advisor.advise(Side.RIGHT, listOf(leftCar)))
    }

    @Test fun `뒤차가 멀리 있으면 다가오더라도 이동 가능`() {
        val rear = TrackedVehicle(1, laneOffset = 1, longitudinalM = -30.0, relativeSpeedMps = 5.0)
        assertIs<LaneChangeAdvice.SafeNow>(advisor.advise(Side.RIGHT, listOf(rear)))
    }

    @Test fun `빠른 뒤차가 바짝 붙어 있으면 지나간 뒤 이동하라고 카운트다운`() {
        // 10m 뒤에서 초속 5m 빠르게 접근 → 앞으로 지나가 충분히 멀어지는 약 4.2초 뒤 이동 가능
        val rear = TrackedVehicle(1, laneOffset = 1, longitudinalM = -10.0, relativeSpeedMps = 5.0)
        val advice = advisor.advise(Side.RIGHT, listOf(rear))
        assertIs<LaneChangeAdvice.Wait>(advice)
        assertTrue(advice.secondsUntilSafe in 4.1..4.3, "예상 4.2초, 실제 ${advice.secondsUntilSafe}")
        assertEquals(5, advice.countdown)
    }

    @Test fun `바로 옆에 같은 속도로 달리는 차가 있으면 이동 불가`() {
        val side = TrackedVehicle(1, laneOffset = -1, longitudinalM = 0.0, relativeSpeedMps = 0.0)
        assertIs<LaneChangeAdvice.NotAvailable>(advisor.advise(Side.LEFT, listOf(side)))
    }

    @Test fun `목표 차선 앞차가 더 느리고 가까우면 이동 불가`() {
        val slowFront = TrackedVehicle(1, laneOffset = 1, longitudinalM = 8.0, relativeSpeedMps = -2.0)
        assertIs<LaneChangeAdvice.NotAvailable>(advisor.advise(Side.RIGHT, listOf(slowFront)))
    }

    @Test fun `출구까지 남은 시간이 충분하면 아직 준비 안내를 하지 않는다`() {
        // 초속 25m, 두 차선 이동 → 기준 45초(1,125m)
        assertEquals(false, LaneChangePlanner.shouldStartPreparing(1500.0, 2, 25.0))
        assertEquals(true, LaneChangePlanner.shouldStartPreparing(1000.0, 2, 25.0))
        assertEquals(false, LaneChangePlanner.shouldStartPreparing(100.0, 0, 25.0))
    }
}

class DrivingScoreTest {
    @Test fun `속도가 1초에 15km_h 이상 오르면 급가속`() {
        val d = DrivingEventDetector()
        d.onSample(sample(0, 20.0))
        val e = d.onSample(sample(1000, 35.0)) // 4.2 m/s²
        assertEquals(listOf(DrivingEventType.HARSH_ACCEL), e.map { it.type })
    }

    @Test fun `완만한 가속은 이벤트가 아니다`() {
        val d = DrivingEventDetector()
        d.onSample(sample(0, 20.0))
        assertTrue(d.onSample(sample(1000, 28.0)).isEmpty()) // 2.2 m/s²
    }

    @Test fun `급브레이크는 쿨다운 동안 한 번만 센다`() {
        val d = DrivingEventDetector()
        d.onSample(sample(0, 60.0))
        d.onSample(sample(500, 52.0, brake = true))  // -4.4 m/s²
        d.onSample(sample(1000, 44.0, brake = true)) // 쿨다운 중
        assertEquals(1, d.events.count { it.type == DrivingEventType.HARSH_BRAKE })
    }

    @Test fun `빠른 속도에서 핸들을 크게 꺾으면 급커브`() {
        val d = DrivingEventDetector()
        val e = d.onSample(sample(0, 50.0, steer = 90.0)) // 약 7.5 m/s²
        assertEquals(listOf(DrivingEventType.SHARP_TURN), e.map { it.type })
    }

    @Test fun `방향지시등 없이 차선을 바꾸면 감점, 켜고 바꾸면 정상`() {
        val d = DrivingEventDetector()
        d.onSample(sample(0, 80.0, signal = TurnSignal.RIGHT))
        assertNull(d.onLaneChanged(2000))
        assertNotNull(d.onLaneChanged(20_000))
    }

    @Test fun `리포트 점수와 가장 큰 감점 항목`() {
        val d = DrivingEventDetector()
        d.onSample(sample(0, 20.0)); d.onSample(sample(1000, 35.0))     // 급가속
        d.onSample(sample(4000, 20.0)); d.onSample(sample(5000, 35.0))  // 급가속
        d.onSample(sample(9000, 35.0, steer = 120.0))                   // 급커브
        d.onWarning(WarningType.PROXIMITY, 12_000)                      // 근접 경고
        val r = DrivingScoreCalculator().report(d.events, previousScore = 82)
        assertEquals(100 - 3 * 2 - 3 - 2, r.score)
        assertEquals(DrivingEventType.HARSH_ACCEL, r.topIssue)
        assertEquals(7, r.delta)
        assertTrue(FeedbackWriter.fallback(r).contains("가속 페달"))
        assertTrue(FeedbackWriter.llmPrompt(r).contains("급가속: 2회"))
    }

    @Test fun `한 항목의 감점은 상한을 넘지 않는다`() {
        val d = DrivingEventDetector()
        repeat(30) { d.onWarning(WarningType.COLLISION, it * 10_000L) }
        assertEquals(80, DrivingScoreCalculator().report(d.events).score)
    }
}

class ParkingCoachTest {
    /** 앞으로 지나가기 → 후진 → 핸들 끝까지 → 핸들 풀기 → 정지 순서로 주행을 흉내 낸다 */
    private fun simulate(coach: ParkingCoach, steerDelayS: Double = 0.0): Pair<List<ParkingStep>, Double> {
        val steps = mutableListOf<ParkingStep>()
        var headingAtStraighten = -1.0
        var ms = 0L
        var clearance = 2.0
        var cueMs = 0L
        coach.start(distancePastSpotCenterM = -3.0)
        repeat(1000) {
            val s = when (coach.step) {
                ParkingStep.DRIVE_PAST -> sample(ms, 5.0)
                ParkingStep.STOP_AND_REVERSE -> sample(ms, 0.0, brake = true, gear = Gear.REVERSE)
                ParkingStep.FULL_LOCK -> {
                    val steer = if ((ms - cueMs) / 1000.0 >= steerDelayS) 480.0 else 0.0
                    sample(ms, 3.0, steer = steer, gear = Gear.REVERSE)
                }
                ParkingStep.STRAIGHTEN -> { clearance -= 3.0 / 3.6 * 0.1; sample(ms, 3.0, gear = Gear.REVERSE) }
                ParkingStep.STOP -> sample(ms, 0.0, brake = true, gear = Gear.REVERSE)
                ParkingStep.DONE -> return steps to headingAtStraighten
            }
            val cue = coach.onSample(s, rearClearanceM = clearance)
            if (cue != null) {
                steps += cue.step
                if (cue.step == ParkingStep.FULL_LOCK) cueMs = ms
                if (cue.step == ParkingStep.STRAIGHTEN) headingAtStraighten = coach.headingChangeDeg
            }
            ms += 100
        }
        return steps to headingAtStraighten
    }

    @Test fun `단계가 순서대로 진행되고 한 번에 성공한다`() {
        val coach = ParkingCoach(side = Side.RIGHT)
        val (steps, heading) = simulate(coach)
        assertEquals(
            listOf(ParkingStep.STOP_AND_REVERSE, ParkingStep.FULL_LOCK, ParkingStep.STRAIGHTEN, ParkingStep.STOP, ParkingStep.DONE),
            steps,
        )
        assertTrue(heading in 75.0..82.0, "핸들 풀기 안내 시 회전 각도: $heading")
        assertEquals(0, coach.retries)
    }

    @Test fun `핸들을 늦게 꺾으면 반응 시간을 학습한다`() {
        val coach = ParkingCoach(side = Side.RIGHT)
        simulate(coach, steerDelayS = 1.0)
        assertTrue(coach.habit.reactionLagS in 0.9..1.2, "학습된 반응 시간: ${coach.habit.reactionLagS}")
    }

    @Test fun `반응이 느린 운전자에게는 핸들 풀기 안내를 더 일찍 한다`() {
        val (_, normal) = simulate(ParkingCoach(side = Side.RIGHT))
        val (_, slow) = simulate(ParkingCoach(side = Side.RIGHT, habit = ParkingHabit(reactionLagS = 1.0, sessions = 3)))
        assertTrue(slow < normal - 3.0, "느린 운전자 $slow° / 기본 $normal°")
    }

    @Test fun `왼쪽 주차는 왼쪽으로 안내한다`() {
        val coach = ParkingCoach(side = Side.LEFT)
        coach.start(10.0) // 이미 충분히 지나온 상태
        assertEquals(ParkingStep.STOP_AND_REVERSE, coach.onSample(sample(0, 5.0))?.step)
        val cue = coach.onSample(sample(100, 0.0, gear = Gear.REVERSE))
        assertTrue(cue!!.message.contains("왼쪽"))
    }
}

class ParkingSpotRankerTest {
    @Test fun `양옆이 넉넉한 자리를 먼저 추천하고 너무 좁은 자리는 뺀다`() {
        val spots = listOf(
            ParkingSpot("tight", widthM = 2.5, lengthM = 5.2, leftIntrusionM = 0.15, rightIntrusionM = 0.1),
            ParkingSpot("pillar", widthM = 2.5, lengthM = 5.2, leftNeighborEmpty = true, nextToPillar = true),
            ParkingSpot("best", widthM = 2.5, lengthM = 5.2, rightNeighborEmpty = true, distanceM = 8.0),
            ParkingSpot("normal", widthM = 2.5, lengthM = 5.2, distanceM = 2.0),
        )
        val ranked = ParkingSpotRanker().rank(spots)
        assertEquals(listOf("best", "pillar", "normal"), ranked.map { it.spot.id })
        assertTrue(ranked.none { it.spot.id == "tight" })
    }
}
