package com.xianxia.sect.data.integrity

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
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
 * 在加载存档后进行业务层的数据完整性检查，自动修复可修复的问题。
 * 关注游戏业务语义层面的数据一致性（不同于 [com.xianxia.sect.data.validation.StorageValidator]
 * 的加密/文件/存储层验证）。
 *
 * 八项检查：
 * 1. sectName 非空 → 设为默认值
 * 2. gameYear / gameMonth 合法范围 → 截断修正
 * 3. 弟子修为不超过境界上限 → 截断
 * 4. 弟子装备引用指向存在的物品 → 清除孤立引用
 * 5. residenceSlots 引用的建筑实例存在于 placedBuildings → 清除孤立槽位
 * 6. 存活弟子年龄不超过寿命上限 → 截断
 * 7. 幽灵弟子（name.isBlank()）→ 从存档中清理
 * 8. residenceSlots.discipleId 引用已清理的幽灵弟子 → 清除引用
 */
object SaveValidator {

    /** 默认宗门名称 */
    private const val DEFAULT_SECT_NAME = "青云宗"

    /**
     * 执行全部八项完整性检查并自动修复。
     *
     * @param saveData 待校验的存档数据
     * @return [Passed] / [Repaired]（含修复后数据）/ [Corrupted]（含问题清单）
     */
    fun validate(saveData: SaveData): IntegrityResult {
        val repairs = mutableListOf<String>()
        val corruption = mutableListOf<String>()

        // ── 第 1 项：sectName ──────────────────────────────────
        var gameData = saveData.gameData
        if (gameData.sectName.isBlank()) {
            repairs.add("sectName 为空，已设为默认值\"$DEFAULT_SECT_NAME\"")
            gameData = gameData.copy(sectName = DEFAULT_SECT_NAME)
        }

        // ── 第 2 项：gameYear / gameMonth ──────────────────────
        val clampedYear = gameData.gameYear.coerceAtLeast(1)
        val clampedMonth = gameData.gameMonth.coerceIn(1, 12)
        if (clampedYear != gameData.gameYear || clampedMonth != gameData.gameMonth) {
            repairs.add(
                "游戏时间越界：year=${gameData.gameYear} month=${gameData.gameMonth}，" +
                    "已修正为 year=$clampedYear month=$clampedMonth"
            )
            gameData = gameData.copy(gameYear = clampedYear, gameMonth = clampedMonth)
        }

        // ── 预计算装备 ID 集合（stack + instance）────────────
        val allEquipmentIds = mutableSetOf<String>().apply {
            saveData.equipmentStacks.forEach { add(it.id) }
            saveData.equipmentInstances.forEach { add(it.id) }
        }

        // ── 预计算建筑 instanceId 集合 ────────────────────────
        val buildingInstanceIds = gameData.placedBuildings
            .map { it.instanceId }
            .filter { it.isNotEmpty() }
            .toHashSet()

        // ── 第 3、4、6 项：遍历弟子 ────────────────────────────
        var disciples = saveData.disciples
        val discipleRepairDetails = mutableListOf<String>()

        if (disciples.isNotEmpty()) {
            disciples = disciples.map { disciple ->
                processDisciple(disciple, allEquipmentIds, discipleRepairDetails)
            }
        }
        if (discipleRepairDetails.isNotEmpty()) {
            repairs.addAll(discipleRepairDetails)
        }

        // ── 第 5 项：building consistency ─────────────────────
        var residenceSlots = gameData.residenceSlots
        val buildingRepairDetails = mutableListOf<String>()

        if (residenceSlots.isNotEmpty()) {
            val validSlots = residenceSlots.filter { slot ->
                // 空 buildingInstanceId 视为尚未分配，始终有效
                if (slot.buildingInstanceId.isEmpty()) return@filter true
                // 有 buildingInstanceId 但 placedBuildings 为空 → 孤立
                if (buildingInstanceIds.isEmpty()) {
                    buildingRepairDetails.add(
                        "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                            "discipleId=${slot.discipleId}) 引用的建筑不存在，已移除"
                    )
                    return@filter false
                }
                // buildingInstanceId 不存在于 placedBuildings → 孤立
                if (slot.buildingInstanceId !in buildingInstanceIds) {
                    buildingRepairDetails.add(
                        "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                            "discipleId=${slot.discipleId}) 引用的建筑不存在，已移除"
                    )
                    return@filter false
                }
                true
            }
            if (validSlots.size != residenceSlots.size) {
                residenceSlots = validSlots
            }
        }
        if (buildingRepairDetails.isNotEmpty()) {
            repairs.addAll(buildingRepairDetails)
        }

        // ── 第 7 项：幽灵弟子检测（name.isBlank() → 清理）──────
        val ghostRemovals = disciples.filter { it.name.isBlank() }
        var hadGhostRemovals = false
        if (ghostRemovals.isNotEmpty()) {
            hadGhostRemovals = true
            ghostRemovals.forEach { ghost ->
                repairs.add("幽灵弟子 id=${ghost.id}（name=空, age=${ghost.age}, realm=${ghost.realm}）已从存档中清理")
            }
            disciples = disciples.filter { it.name.isNotBlank() }
        }

        // ── 第 8 项：residenceSlots.discipleId 引用已清理的幽灵弟子 ──
        if (hadGhostRemovals && residenceSlots.isNotEmpty()) {
            val validDiscipleIds = disciples.map { it.id }.toHashSet()
            val slotDiscipleRepairDetails = mutableListOf<String>()
            var changed = false
            val fixedSlots = residenceSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() && slot.discipleId !in validDiscipleIds) {
                    changed = true
                    slotDiscipleRepairDetails.add(
                        "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                            "slotIndex=${slot.slotIndex}) 引用的弟子(id=${slot.discipleId})不存在，已清除"
                    )
                    slot.copy(discipleId = "", discipleName = "")
                } else {
                    slot
                }
            }
            if (changed) {
                residenceSlots = fixedSlots
                repairs.addAll(slotDiscipleRepairDetails)
            }
        }

        // ── 汇总判决 ───────────────────────────────────────────
        return when {
            repairs.isNotEmpty() && corruption.isEmpty() -> {
                // 重建需改动的字段
                var fixedGameData = gameData
                if (residenceSlots !== gameData.residenceSlots) {
                    fixedGameData = fixedGameData.copy(residenceSlots = residenceSlots)
                }
                val fixedSaveData = if (fixedGameData !== saveData.gameData || disciples !== saveData.disciples) {
                    saveData.copy(gameData = fixedGameData, disciples = disciples)
                } else {
                    saveData
                }
                Log.w(TAG, "存档完整性修复: ${repairs.size} 项\n${repairs.joinToString("\n")}")
                IntegrityResult.Repaired(fixedSaveData, repairs.toList())
            }
            corruption.isNotEmpty() -> {
                Log.e(TAG, "存档不可修复: ${corruption.size} 项\n${corruption.joinToString("\n")}")
                IntegrityResult.Corrupted(corruption.toList())
            }
            else -> IntegrityResult.Passed
        }
    }

    // ── 私有方法 ──────────────────────────────────────────────

    /**
     * 对单个弟子执行第 3（修为上限）、第 4（装备引用）、第 6（年龄/寿命）项检查与修复。
     */
    private fun processDisciple(
        disciple: Disciple,
        allEquipmentIds: Set<String>,
        repairs: MutableList<String>
    ): Disciple {
        var d = disciple
        val name = d.name.ifBlank { "ID=${d.id}" }
        val localRepairs = mutableListOf<String>()

        // 第 3 项：cultivation cap
        val maxCult = computeMaxCultivation(d.realm, d.realmLayer)
        if (d.cultivation > maxCult) {
            localRepairs.add("弟子[$name] cultivation=${d.cultivation} 超过境界上限=$maxCult，已截断")
            d = d.copy(cultivation = maxCult)
        }

        // 第 4 项：equipment orphan refs
        val oldEquip = d.equipment
        var weaponId = oldEquip.weaponId
        var armorId = oldEquip.armorId
        var bootsId = oldEquip.bootsId
        var accessoryId = oldEquip.accessoryId

        val equipFixes = mutableListOf<String>()
        if (weaponId.isNotEmpty() && weaponId !in allEquipmentIds) {
            equipFixes.add("weaponId=$weaponId")
            weaponId = ""
        }
        if (armorId.isNotEmpty() && armorId !in allEquipmentIds) {
            equipFixes.add("armorId=$armorId")
            armorId = ""
        }
        if (bootsId.isNotEmpty() && bootsId !in allEquipmentIds) {
            equipFixes.add("bootsId=$bootsId")
            bootsId = ""
        }
        if (accessoryId.isNotEmpty() && accessoryId !in allEquipmentIds) {
            equipFixes.add("accessoryId=$accessoryId")
            accessoryId = ""
        }
        if (equipFixes.isNotEmpty()) {
            localRepairs.add("弟子[$name] 装备引用不存在: ${equipFixes.joinToString(", ")}，已清除")
            d = d.copy(
                equipment = oldEquip.copy(
                    weaponId = weaponId,
                    armorId = armorId,
                    bootsId = bootsId,
                    accessoryId = accessoryId
                )
            )
        }

        // 第 6 项：age vs lifespan
        if (d.isAlive && d.age > d.lifespan) {
            localRepairs.add("弟子[$name] age=${d.age} 超过 lifespan=${d.lifespan}，已截断")
            d = d.copy(age = d.lifespan)
        }

        repairs.addAll(localRepairs)
        return d
    }

    /**
     * 计算给定境界和层数的修为上限。
     *
     * 逻辑与 [com.xianxia.sect.core.model.Disciple.maxCultivation] 保持一致。
     */
    internal fun computeMaxCultivation(realm: Int, realmLayer: Int): Double {
        if (realm <= 0) return Double.MAX_VALUE // 仙人境界不限制
        val cfg = GameConfig.Realm.get(realm)
        val nextCfg = GameConfig.Realm.get(realm - 1)
        val base = cfg.cultivationBase.toDouble()
        val nextBase = nextCfg.cultivationBase.toDouble()
        val maxLayers = cfg.maxLayers
        return base + (realmLayer - 1).toDouble() * (nextBase - base) / maxLayers
    }
}
