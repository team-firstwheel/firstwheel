package ai.firstwheel.core.scoring

/** 이벤트 1회당 감점 */
val DEFAULT_PENALTIES: Map<DrivingEventType, Int> = mapOf(
    DrivingEventType.HARSH_ACCEL to 3,
    DrivingEventType.HARSH_BRAKE to 4,
    DrivingEventType.SHARP_TURN to 3,
    DrivingEventType.LANE_CHANGE_NO_SIGNAL to 5,
    DrivingEventType.LANE_DEPARTURE to 3,
    DrivingEventType.COLLISION_WARNING to 6,
    DrivingEventType.PROXIMITY_WARNING to 2,
)

data class DailyReport(
    val score: Int,
    val counts: Map<DrivingEventType, Int>,
    /** 감점이 가장 컸던 항목. 이벤트가 없으면 null */
    val topIssue: DrivingEventType?,
    val previousScore: Int?,
) {
    val delta: Int? get() = previousScore?.let { score - it }
}

/**
 * 하루 운전 점수 계산.
 * 100점에서 감점하되, 한 항목이 점수를 지나치게 깎지 않도록 항목별 감점 상한을 둔다.
 */
class DrivingScoreCalculator(
    private val penalties: Map<DrivingEventType, Int> = DEFAULT_PENALTIES,
    private val maxPenaltyPerType: Int = 20,
) {
    fun report(events: List<DrivingEvent>, previousScore: Int? = null): DailyReport {
        val counts = DrivingEventType.values().associateWith { t -> events.count { it.type == t } }
        val perType = counts.mapValues { (t, n) -> minOf(n * (penalties[t] ?: 0), maxPenaltyPerType) }
        val score = (100 - perType.values.sum()).coerceIn(0, 100)
        val top = perType.filter { it.value > 0 }.maxByOrNull { it.value }?.key
        return DailyReport(score, counts, top, previousScore)
    }
}

/**
 * 리포트를 바탕으로 LLM(Gleo AI LLM SDK)에 보낼 프롬프트를 만들고,
 * LLM을 쓸 수 없을 때는 규칙 기반 피드백 문장을 만든다.
 */
object FeedbackWriter {
    fun llmPrompt(r: DailyReport): String = buildString {
        appendLine("너는 초보 운전자를 돕는 친절한 운전 코치야.")
        appendLine("아래 기록을 보고 칭찬 한 가지와 구체적인 개선 방법 한 가지를 세 문장 이내의 존댓말로 알려 줘.")
        appendLine("오늘 점수: ${r.score}점" + (r.delta?.let { " (어제 대비 ${signed(it)}점)" } ?: ""))
        val issues = r.counts.filter { it.value > 0 }
        if (issues.isEmpty()) appendLine("- 위험 운전 기록 없음")
        issues.forEach { (t, n) -> appendLine("- ${t.label}: ${n}회") }
    }

    fun fallback(r: DailyReport): String {
        val d = r.delta
        val head = "오늘 점수는 ${r.score}점이에요" + when {
            d == null -> "."
            d == 0 -> ", 어제와 같아요."
            d > 0 -> ", 어제보다 ${d}점 올랐어요."
            else -> ", 어제보다 ${-d}점 내려갔어요."
        }
        val tip = when (r.topIssue) {
            null -> "위험 운전 기록이 하나도 없어요. 지금처럼만 운전하세요!"
            DrivingEventType.HARSH_ACCEL -> "출발하고 2초 동안은 가속 페달을 절반만 밟아 보세요."
            DrivingEventType.HARSH_BRAKE -> "앞차와 거리를 조금 더 두면 급하게 브레이크를 밟는 일이 줄어요."
            DrivingEventType.SHARP_TURN -> "회전하기 전에 충분히 속도를 줄인 뒤 핸들을 돌려 보세요."
            DrivingEventType.LANE_CHANGE_NO_SIGNAL -> "차선을 바꾸기 3초 전에 방향지시등을 먼저 켜 주세요."
            DrivingEventType.LANE_DEPARTURE -> "시선을 멀리 두면 차로 가운데를 유지하기 쉬워요."
            DrivingEventType.COLLISION_WARNING -> "앞차와의 거리를 2초 이상 유지해 보세요."
            DrivingEventType.PROXIMITY_WARNING -> "주차나 서행할 때 주변 거리를 한 번 더 확인해 보세요."
        }
        return "$head $tip"
    }

    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
}
