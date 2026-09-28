package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData

/**
 * 双轨时间权威轴一致性（结算改造 2026-09-27 B3，方案 §4.3）：
 *
 * 1. [SaveData.gameData.elapsedGameMs] 负值钳 0（单调时钟不回拨，防手改存档）；
 * 2. 权威轴为 0 且日历非初值 ⇒ 旧档归一化回填（calendarToGameMs，与 C++
 *    `ensureBaselineTimeAxis` 同公式同值——B1 双端守卫锁定）；
 * 3. 权威轴 > 0 且日历投影与其不一致 ⇒ **以权威轴为准**重算日历投影
 *    （INV-1：日历是派生投影，不是第二真相源——防"手改日历"造成双真相源漂移）；
 * 4. [SaveData.gameData.lastSettleGameMs] 越界（负值 / 超过权威轴）钳回合法区间。
 *
 * order=25：排在日历范围修复（GameDateRule=2 / GamePhaseRangeRule=4）之后——
 * 回填与投影重算消费的是已修复范围合法的日历值；链内无后续规则消费时间轴。
 */
object TimeAxisRule : SaveValidationRule {
    override val id = "time_axis_consistency"
    override val order = 25

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val notes = mutableListOf<String>()

        var elapsed = gd.elapsedGameMs
        var lastSettle = gd.lastSettleGameMs

        if (elapsed < 0) {
            notes += "权威时间轴负值 elapsedGameMs=$elapsed，已钳 0"
            elapsed = 0
        }
        if (lastSettle < 0) {
            notes += "积分基准负值 lastSettleGameMs=$lastSettle，已钳 0"
            lastSettle = 0
        }
        if (lastSettle > elapsed) {
            notes += "积分基准超过权威轴（$lastSettle > $elapsed），已钳到权威轴"
            lastSettle = elapsed
        }

        val calendarAtOrigin = gd.gameYear <= 1 && gd.gameMonth <= 1 && gd.gamePhase <= 0
        var calendar = GameConfig.Time.GameCalendar(gd.gameYear, gd.gameMonth, gd.gamePhase)

        if (elapsed == 0L && !calendarAtOrigin) {
            // 旧档归一化：权威轴缺省 ⇒ 按日历换算回填
            elapsed = GameConfig.Time.calendarToGameMs(gd.gameYear, gd.gameMonth, gd.gamePhase)
            lastSettle = elapsed
            notes += "旧档归一化：elapsedGameMs 按日历回填为 $elapsed"
        } else if (elapsed > 0L) {
            // 一致性：日历与权威轴投影不一致时以权威轴为准重算（INV-1）
            val projected = GameConfig.Time.projectCalendar(elapsed)
            if (projected != calendar) {
                notes += "日历与权威轴不一致（日历=${calendar.year}年${calendar.month}月" +
                    "${calendar.phase}旬，投影=${projected.year}年${projected.month}月" +
                    "${projected.phase}旬），已按权威轴重算"
                calendar = projected
            }
        }

        if (notes.isEmpty()) return RuleOutcome.Passed
        return RuleOutcome.Repaired(
            data.copy(
                gameData = gd.copy(
                    elapsedGameMs = elapsed,
                    lastSettleGameMs = lastSettle,
                    gameYear = calendar.year,
                    gameMonth = calendar.month,
                    gamePhase = calendar.phase
                )
            ),
            notes
        )
    }
}
