package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 单个规则执行结果。
 *
 * - [Passed]: 无问题
 * - [Skipped]: 规则因前置条件不满足而跳过（例如无幽灵弟子时跳过清理）
 * - [Repaired]: 发现问题并修复，附带修复描述和修复后的数据副本
 * - [Corrupted]: 不可修复的致命问题
 */
sealed interface RuleOutcome {

    /** 数据通过检查，无任何问题 */
    data object Passed : RuleOutcome

    /**
     * 规则跳过执行。
     * @param reason 跳过原因
     */
    data class Skipped(val reason: String) : RuleOutcome

    /**
     * 发现问题并自动修复。
     * @param data 修复后的完整 [SaveData]
     * @param details 修复记录列表
     */
    data class Repaired(
        val data: SaveData,
        val details: List<String>
    ) : RuleOutcome

    /**
     * 存在无法自动修复的严重问题。
     * @param details 问题描述列表
     */
    data class Corrupted(val details: List<String>) : RuleOutcome
}
