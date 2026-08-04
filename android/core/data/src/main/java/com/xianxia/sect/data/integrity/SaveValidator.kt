package com.xianxia.sect.data.integrity

import android.util.Log
import com.xianxia.sect.data.integrity.rules.RuleContext
import com.xianxia.sect.data.integrity.rules.RuleOutcome
import com.xianxia.sect.data.integrity.rules.SaveValidationRuleRegistry
import com.xianxia.sect.data.integrity.rules.registerDefaults
import com.xianxia.sect.data.model.SaveData

private const val TAG = "SaveValidator"

/**
 * 存档完整性校验结果。
 *
 * - [Passed]: 所有检查通过，数据完好。
 * - [Repaired]: 发现可修复的问题，已一并修复，附加修复记录。
 * - [Corrupted]: 存在不可修复的严重问题，附加问题清单，调用方应尝试恢复备份。
 */
sealed interface IntegrityResult {
    /** 数据完好，无任何问题。 */
    data object Passed : IntegrityResult

    /**
     * 数据存在可修复的问题，已自动修复。
     * @param data 修复后的完整 [SaveData]
     * @param details 修复记录列表
     */
    data class Repaired(val data: SaveData, val details: List<String>) : IntegrityResult

    /**
     * 数据存在无法自动修复的严重问题。
     * @param details 问题清单
     */
    data class Corrupted(val details: List<String>) : IntegrityResult
}

/**
 * 存档完整性校验器。
 *
 * 使用规则引擎模式，所有检查逻辑由 [SaveValidationRuleRegistry] 中注册的规则执行。
 * 新检查只需在 [SaveValidationRuleRegistry] 中注册一条新规则即可。
 *
 * 参见 [com.xianxia.sect.data.integrity.rules] 包下的所有规则实现。
 */
object SaveValidator {

    private val lock = Any()

    /**
     * 执行全部注册规则的完整性检查并自动修复。
     *
     * @param saveData 待校验的存档数据
     * @return [Passed] / [Repaired]（含修复后数据）/ [Corrupted]（含问题清单）
     */
    fun validate(saveData: SaveData): IntegrityResult {
        ensureRegistered()
        val context = RuleContext(saveData)
        var currentData = saveData
        val allRepairs = mutableListOf<String>()
        val allCorruptions = mutableListOf<String>()

        for (rule in SaveValidationRuleRegistry.all) {
            val outcome = try {
                rule.execute(currentData, context)
            } catch (e: Exception) {
                Log.e(TAG, "规则[${rule.id}]执行异常: ${e.message}", e)
                allCorruptions.add("规则[${rule.id}]执行异常: ${e.message}")
                continue
            }

            when (outcome) {
                is RuleOutcome.Passed -> { /* 无操作 */ }
                is RuleOutcome.Skipped -> {
                    Log.d(TAG, "规则[${rule.id}]跳过: ${outcome.reason}")
                }
                is RuleOutcome.Repaired -> {
                    currentData = outcome.data
                    allRepairs.addAll(outcome.details)
                }
                is RuleOutcome.Corrupted -> {
                    allCorruptions.addAll(outcome.details)
                }
            }
        }

        return when {
            allCorruptions.isNotEmpty() -> {
                Log.e(TAG, "存档不可修复: ${allCorruptions.size} 项\n${allCorruptions.joinToString("\n")}")
                IntegrityResult.Corrupted(allCorruptions.toList())
            }
            allRepairs.isNotEmpty() -> {
                Log.w(TAG, "存档完整性修复: ${allRepairs.size} 项\n${allRepairs.joinToString("\n")}")
                IntegrityResult.Repaired(currentData, allRepairs.toList())
            }
            else -> IntegrityResult.Passed
        }
    }

    /**
     * 确保默认规则已注册。
     * C8 修复：以注册表实际规模为唯一依据（原 `registered` 标志首次置位后恒 true，
     * 测试 `SaveValidationRuleRegistry.clear()` 后 validate 会以空规则运行全部 Passed）。
     * 测试可以通过 [SaveValidationRuleRegistry.clear] + [SaveValidationRuleRegistry.register] 覆盖。
     */
    private fun ensureRegistered() {
        if (SaveValidationRuleRegistry.size == 0) {
            synchronized(lock) {
                if (SaveValidationRuleRegistry.size == 0) {
                    SaveValidationRuleRegistry.registerDefaults()
                    Log.d(TAG, "规则引擎已初始化: ${SaveValidationRuleRegistry.size} 条规则")
                }
            }
        }
    }

    // ==================== 旧 API 桥接（保持向后兼容） ====================

    /**
     * 计算给定境界和层数的修为上限。
     *
     * 委托给 [com.xianxia.sect.data.integrity.rules.computeMaxCultivation]。
     * 旧代码（测试 / 其他模块）可以通过此桥接方法继续使用。
     */
    fun computeMaxCultivation(realm: Int, realmLayer: Int): Double {
        return com.xianxia.sect.data.integrity.rules.computeMaxCultivation(realm, realmLayer)
    }
}
