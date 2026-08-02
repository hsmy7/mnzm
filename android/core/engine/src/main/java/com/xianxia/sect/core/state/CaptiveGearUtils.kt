package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import java.util.UUID

/**
 * 俘虏/旧档 AI 弟子入玩家池时的装备/功法落库工具。
 *
 * 必须在 stateStore.update {} 事务内调用（接收 [MutableGameState]）。
 * 将 AI 侧持久化的模板 id 装备/功法重建为玩家侧 UUID 实例并写入：
 * 1. 4 槽位 → [MutableGameState.equipmentInstances] + 回写 DiscipleTables 槽位列（孕养继承）
 * 2. 功法 → [MutableGameState.manualInstances] + 回写 manualIds/manualMasteries 列（实例 id 键）+ HP/MP 增量
 * 3. 熟练度 → [MutableGameState.gameData].manualProficiencies（按新弟子 id 注册）
 *
 * 幂等：槽位已装备玩家实例 id（非俘虏模板 id）时跳过，重复调用不产生重复实例。
 */
fun MutableGameState.materializeCaptiveGear(captive: Disciple, newId: String) {
    val intId = newId.toIntOrNull() ?: return
    if (!shouldMaterializeCaptiveGear(captive, newId, intId)) return
    materializeEquipments(captive, intId)
    materializeManuals(captive, newId, intId)
}

/**
 * 幂等/合法性守卫：弟子 id 存在且未落库。
 *
 * 落库哨兵双通道：① `gameData.manualProficiencies` 已注册该弟子（纯 Kotlin map，
 * 任何环境可靠）；② 任一装备槽位列已写入玩家实例 id（区别于俘虏模板 id）。
 * 仅依赖武器槽单列会漏掉"无武器俘虏"（小型宗门约 3/4 俘虏无武器），导致重复落库。
 */
private fun MutableGameState.shouldMaterializeCaptiveGear(
    captive: Disciple,
    newId: String,
    intId: Int
): Boolean {
    if (!discipleTables.ids.contains(intId) || gameData.manualProficiencies.containsKey(newId)) {
        return false
    }
    val anySlotInstance = listOf(
        discipleTables.weaponIds[intId],
        discipleTables.armorIds[intId],
        discipleTables.bootsIds[intId],
        discipleTables.accessoryIds[intId]
    ).any { slotId ->
        !slotId.isNullOrEmpty() &&
            slotId != captive.equipment.weaponId &&
            slotId != captive.equipment.armorId &&
            slotId != captive.equipment.bootsId &&
            slotId != captive.equipment.accessoryId
    }
    return !anySlotInstance
}

/** 按模板重建 4 槽位装备实例（新 UUID、ownerId、isEquipped），孕养数据从俘虏继承。 */
private fun MutableGameState.materializeEquipments(captive: Disciple, intId: Int) {
    val slots = listOf(
        Triple(EquipmentSlot.WEAPON, captive.equipment.weaponId, captive.equipment.weaponNurture),
        Triple(EquipmentSlot.ARMOR, captive.equipment.armorId, captive.equipment.armorNurture),
        Triple(EquipmentSlot.BOOTS, captive.equipment.bootsId, captive.equipment.bootsNurture),
        Triple(EquipmentSlot.ACCESSORY, captive.equipment.accessoryId, captive.equipment.accessoryNurture)
    )
    for ((slot, templateId, nurture) in slots) {
        val instance = buildEquipmentInstanceForCaptive(templateId, intId, nurture) ?: continue
        equipmentInstances.add(instance)
        when (slot) {
            EquipmentSlot.WEAPON -> discipleTables.weaponIds[intId] = instance.id
            EquipmentSlot.ARMOR -> discipleTables.armorIds[intId] = instance.id
            EquipmentSlot.BOOTS -> discipleTables.bootsIds[intId] = instance.id
            EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[intId] = instance.id
        }
    }
}

/** 从模板构建单个装备实例（模板缺失/空 id 返回 null）。 */
private fun buildEquipmentInstanceForCaptive(
    templateId: String,
    intId: Int,
    nurture: EquipmentNurtureData
): EquipmentInstance? {
    val template = templateId.takeIf { it.isNotEmpty() }
        ?.let { EquipmentDatabase.getById(it) } ?: return null
    var instance = EquipmentDatabase.createFromTemplate(template)
        .toInstance(id = UUID.randomUUID().toString(), ownerId = intId.toString(), isEquipped = true)
    if (nurture.equipmentId == templateId) {
        instance = instance.copy(
            nurtureLevel = nurture.nurtureLevel,
            nurtureProgress = nurture.nurtureProgress
        )
    }
    return instance
}

/**
 * 按模板重建功法实例（新 UUID、ownerId、isLearned），
 * 回写 manualIds/manualMasteries 列（实例 id 键），
 * 并将熟练度注册进 gameData.manualProficiencies（按新弟子 id）。
 */
private fun MutableGameState.materializeManuals(captive: Disciple, newId: String, intId: Int) {
    if (captive.manualIds.isEmpty()) return

    // 去重 + 对齐玩家功法槽位上限（getMaxManualSlots），防损坏存档重复/超量功法入池
    val maxManualSlots = DiscipleStatCalculator.getMaxManualSlots(captive)
    val manualTemplateIds = captive.manualIds.distinct().take(maxManualSlots)
    val templateToInstanceId = mutableMapOf<String, String>()
    val newManualIds = mutableListOf<String>()
    val proficiencyList = mutableListOf<ManualProficiencyData>()
    val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
    var hp = discipleTables.currentHps[intId]
    var mp = discipleTables.currentMps[intId]

    for (templateId in manualTemplateIds) {
        val created = createManualForCaptive(captive, templateId, newId, maxProf, hp, mp) ?: continue
        manualInstances.add(created.instance)
        templateToInstanceId[templateId] = created.instance.id
        newManualIds.add(created.instance.id)
        if (created.hp != hp) {
            hp = created.hp
            discipleTables.currentHps[intId] = hp
        }
        if (created.mp != mp) {
            mp = created.mp
            discipleTables.currentMps[intId] = mp
        }
        proficiencyList.add(created.proficiency)
    }
    if (newManualIds.isEmpty()) return

    discipleTables.manualIds[intId] = newManualIds
    discipleTables.manualMasteries[intId] = captive.manualMasteries
        .mapNotNull { (tId, mastery) ->
            templateToInstanceId[tId]?.let { Pair(it, mastery) }
        }
        .toMap()
    if (proficiencyList.isNotEmpty()) {
        gameData = gameData.copy(
            manualProficiencies = gameData.manualProficiencies + (newId to proficiencyList)
        )
    }
}

/** 单本功法实例构建结果。 */
private data class CreatedManual(
    val instance: ManualInstance,
    val hp: Int,
    val mp: Int,
    val proficiency: ManualProficiencyData
)

/** 从模板构建单本功法实例（含 HP/MP 增量与熟练度条目）；模板缺失返回 null。 */
private fun createManualForCaptive(
    captive: Disciple,
    templateId: String,
    newId: String,
    maxProf: Int,
    hp: Int,
    mp: Int
): CreatedManual? {
    val template = ManualDatabase.getById(templateId) ?: return null
    val stack = ManualDatabase.createFromTemplate(template)
    val instance = stack.toInstance(
        id = UUID.randomUUID().toString(), ownerId = newId, isLearned = true
    )
    // HP/MP 增量对齐 learnManual：rawHp >= 0 且增益为正才累加
    val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
    val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
    val newHp = if (hp >= 0 && hpDelta > 0) hp + hpDelta else hp
    val newMp = if (mp >= 0 && mpDelta > 0) mp + mpDelta else mp

    val mastery = captive.manualMasteries[templateId] ?: 0
    return CreatedManual(
        instance = instance,
        hp = newHp,
        mp = newMp,
        proficiency = ManualProficiencyData(
            manualId = instance.id,
            manualName = template.name,
            // 上下界防护：损坏存档负熟练度归零
            proficiency = mastery.toDouble().coerceIn(0.0, maxProf.toDouble()),
            maxProficiency = maxProf,
            masteryLevel = ManualProficiencySystem.MasteryLevel
                .fromProficiency(mastery.toDouble()).level
        )
    )
}
