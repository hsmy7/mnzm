package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.registry.EquipmentSetDatabase

/**
 * 装备加成汇合点（装备重构 B3，方案 §3.10——**唯一结算入口**）。
 *
 * 输入：弟子六槽位实例的 `totalBonus()` + 套装 2/4/6 档效果；输出 [EquipBonus]。
 * Kotlin 消费点：`DiscipleStatCalculator属性Ops3/4`；C++ 对偶 `disciple_stats.h`
 * 逐位一致（`DiffEquipmentSetBonusTest`/`DiffEquipmentStatTest`）。
 *
 * 乘区口径（方案 §3.4.1）：`atk = (baseAtk + Σ装备flatAtk) × (1 + Σ装备atkPct) +
 * Σ功法flat + Σ丹药flat`——装备乘区只放大装备自身贡献，功法/丹药加法序逐位不变。
 */
data class EquipBonus(
    val flatAttack: Double = 0.0,
    val flatDefense: Double = 0.0,
    val flatHp: Double = 0.0,
    val pctAttack: Double = 0.0,
    val critRate: Double = 0.0,
    val critDamage: Double = 0.0,
    /** 物理伤害加成（不受灵根 gate） */
    val physicalDamageBonus: Double = 0.0,
    /** 金伤害加成（gate 前原始值；弟子侧汇总时按灵根折算） */
    val metalDamageBonus: Double = 0.0,
    /** 木伤害加成（gate 前原始值） */
    val woodDamageBonus: Double = 0.0,
    /** 水伤害加成（gate 前原始值） */
    val waterDamageBonus: Double = 0.0,
    /** 火伤害加成（gate 前原始值） */
    val fireDamageBonus: Double = 0.0,
    /** 土伤害加成（gate 前原始值） */
    val earthDamageBonus: Double = 0.0
) {
    operator fun plus(other: EquipBonus): EquipBonus = EquipBonus(
        flatAttack = flatAttack + other.flatAttack,
        flatDefense = flatDefense + other.flatDefense,
        flatHp = flatHp + other.flatHp,
        pctAttack = pctAttack + other.pctAttack,
        critRate = critRate + other.critRate,
        critDamage = critDamage + other.critDamage,
        physicalDamageBonus = physicalDamageBonus + other.physicalDamageBonus,
        metalDamageBonus = metalDamageBonus + other.metalDamageBonus,
        woodDamageBonus = woodDamageBonus + other.woodDamageBonus,
        waterDamageBonus = waterDamageBonus + other.waterDamageBonus,
        fireDamageBonus = fireDamageBonus + other.fireDamageBonus,
        earthDamageBonus = earthDamageBonus + other.earthDamageBonus
    )
}

object EquipStatResolver {

    /**
     * 解析六件已装备实例（含词条）+ 命中套装档位 → [EquipBonus]。
     *
     * 走**恒等键整解析缓存**（方案 §13-6「按实例内容做值语义缓存」的落地变体，
     * EQ-B4；变体理由见 [resolveCache] KDoc）——每旬热点（`getMaxHpMpColumn`）
     * 逐弟子逐旬调用，缓存把词条重建与套装档位解析摊薄为一次哈希查找
     * （`EquipmentStatHotPathBenchmark` 门：不劣化于改造前含缓存基线 10%）。
     *
     * @param equippedInstances 弟子六槽位的实例（按槽位 id 从实例表取到的非空件）
     */
    fun resolve(equippedInstances: List<EquipmentInstance>): EquipBonus {
        if (equippedInstances.isEmpty()) return EquipBonus()
        val key = InstanceListKey(equippedInstances)
        resolveCache[key]?.let { return it }
        var bonus = EquipBonus()
        for (instance in equippedInstances) {
            for (statValue in instance.totalBonus()) {
                // 🔴 禁写 `bonus += plusStat(bonus, sv)`：plusStat 已折入 current，
                // 复合赋值 = 「旧值×2 + sv」（EQ-B2 同款 Kotlin 坑复发，测试实证）
                bonus = plusStat(bonus, statValue)
            }
        }
        bonus += resolveSetBonus(equippedInstances)
        if (resolveCache.size >= CACHE_LIMIT) {
            resolveCache.clear()
        }
        resolveCache[key] = bonus
        return bonus
    }

    /** 单条词条并入（求和口径，双端逐位一致） */
    private fun plusStat(current: EquipBonus, statValue: EquipStatValue): EquipBonus {
        val v = statValue.value
        return when (statValue.stat) {
            EquipStat.ATTACK -> current.copy(flatAttack = current.flatAttack + v)
            EquipStat.DEFENSE -> current.copy(flatDefense = current.flatDefense + v)
            EquipStat.HP -> current.copy(flatHp = current.flatHp + v)
            EquipStat.CRIT_RATE -> current.copy(critRate = current.critRate + v)
            EquipStat.CRIT_DAMAGE -> current.copy(critDamage = current.critDamage + v)
            EquipStat.ATTACK_PCT -> current.copy(pctAttack = current.pctAttack + v)
            EquipStat.PHYSICAL_DAMAGE_PCT -> current.copy(physicalDamageBonus = current.physicalDamageBonus + v)
            EquipStat.METAL_DAMAGE_PCT -> current.copy(metalDamageBonus = current.metalDamageBonus + v)
            EquipStat.WOOD_DAMAGE_PCT -> current.copy(woodDamageBonus = current.woodDamageBonus + v)
            EquipStat.WATER_DAMAGE_PCT -> current.copy(waterDamageBonus = current.waterDamageBonus + v)
            EquipStat.FIRE_DAMAGE_PCT -> current.copy(fireDamageBonus = current.fireDamageBonus + v)
            EquipStat.EARTH_DAMAGE_PCT -> current.copy(earthDamageBonus = current.earthDamageBonus + v)
            // 退役段（MAGIC_DAMAGE_PCT）：禁新产出；旧档残留词条不再并入任何通道
            EquipStat.MAGIC_DAMAGE_PCT -> current
        }
    }

    /**
     * 套装档位（按 setId 统计件数，2/4/6 达档即生效、可越级不叠加——
     * 穿满 6 件时 2/4/6 三档同时生效；0.2-7：同类百分比相加）。
     * 同一 setId 只统计六槽位内件数（去重由 EquipmentDedupeRule 保证独占）。
     */
    fun resolveSetBonus(equippedInstances: List<EquipmentInstance>): EquipBonus {
        var bonus = EquipBonus()
        val countBySet = equippedInstances
            .filter { it.setId.isNotEmpty() }
            .groupingBy { it.setId }
            .eachCount()
        for ((setId, count) in countBySet) {
            val def = EquipmentSetDatabase.getById(setId) ?: continue
            for (tier in def.activeBonuses(count)) {
                for (statValue in tier.entries) {
                    bonus = plusStat(bonus, statValue)
                }
            }
        }
        return bonus
    }

    // ── 整解析缓存（恒等键，对标改造前 cachedFinalStats 的护栏口径） ──

    /**
     * 缓存键：实例列表的**恒等有序**集（顺序参与相等性——两调用方都以稳定的
     * 槽位序构建列表，序变只多算一次，无正确性风险）。
     *
     * **为何恒等键而非值语义键**（对方案 §13-6「按实例内容」的实测修正）：
     * 新模型实例是深层嵌套 data class（growth→affix→3 副词条列表），值语义
     * hashCode 每件遍历整棵词条树——六件逐旬 × 全体弟子使深哈希成为热点主导项
     * （B4 实测：值语义键 1,286 ns/调用 vs 改造前基线复刻 203 ns，超门 6 倍；
     * 恒等键后见报告实测）。恒等键的版本安全性由不可变纪律保证：
     * `EquipmentInstance` 全字段 val（内容不可变）、唯一 `var slotId` 不参与
     * 加成且变更走 `copy`（新恒等 = 新键）——**内容变 ⇒ 必然新实例 ⇒ 必然新键**，
     * 永不需要失效逻辑。
     */
    private class InstanceListKey(private val refs: List<EquipmentInstance>) {
        private val hash: Int = refs.fold(1) { acc, r -> 31 * acc + System.identityHashCode(r) }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is InstanceListKey) return false
            if (other.refs.size != refs.size) return false
            for (i in refs.indices) {
                if (other.refs[i] !== refs[i]) return false
            }
            return true
        }
    }

    /**
     * resolve 结果缓存（键见 [InstanceListKey]；值 = 纯函数结果，幂等，
     * ConcurrentHashMap 跨引擎线程/测试安全）。
     * 容量护栏：实例数无上限（S18），键数无界增长时清空重建（摊还 O(1)）。
     */
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<InstanceListKey, EquipBonus>()

    /** 缓存容量护栏（与改造前 finalStatsCache 同值） */
    private const val CACHE_LIMIT = 4096
}
