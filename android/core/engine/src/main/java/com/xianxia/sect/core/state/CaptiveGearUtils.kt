package com.xianxia.sect.core.state

import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import java.util.UUID
import com.xianxia.sect.core.engine.domain.disciple.getMaxManualSlots

/**
 * 俘虏/旧档 AI 弟子入玩家池时的装备/功法落库工具。
 *
 * 必须在 stateStore.update {} 事务内调用（接收 [MutableGameState]）。
 * 将 AI 侧持久化的部件 id 装备/功法重建为玩家侧 UUID 实例并写入：
 * 1. 六槽位 → [MutableGameState.equipmentInstances] + 回写 DiscipleTables 槽位列
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
 * 仅依赖单列会漏掉"空槽位俘虏"，导致重复落库。
 */
private fun MutableGameState.shouldMaterializeCaptiveGear(
    captive: Disciple,
    newId: String,
    intId: Int
): Boolean {
    if (!discipleTables.ids.contains(intId) || gameData.manualProficiencies.containsKey(newId)) {
        return false
    }
    val anySlotInstance = EquipmentSlot.displayOrder.any { slot ->
        val slotId = discipleTables.slotIdOf(intId, slot)
        !slotId.isNullOrEmpty() && slotId != captive.equipment.slotId(slot)
    }
    return !anySlotInstance
}

/** 按部件展开条目重建四部位装备实例（新 UUID、ownerId、isEquipped；词条占位空面——AI 载荷不存词条）。 */
private fun MutableGameState.materializeEquipments(captive: Disciple, intId: Int) {
    for (slot in EquipmentSlot.displayOrder) {
        val pieceEntryId = captive.equipment.slotId(slot)
        val instance = buildEquipmentInstanceForCaptive(pieceEntryId, intId) ?: continue
        equipmentInstances.add(instance)
        discipleTables.setSlotId(intId, slot, instance.id)
    }
}

/** 从部件展开条目构建单个装备实例（条目缺失/空 id 返回 null）。 */
private fun buildEquipmentInstanceForCaptive(
    pieceEntryId: String,
    intId: Int
): EquipmentInstance? {
    val entry = pieceEntryId.takeIf { it.isNotEmpty() }
        ?.let { EquipmentDatabase.getById(it) } ?: return null
    return EquipmentInstance(
        id = UUID.randomUUID().toString(),
        name = entry.name,
        setId = entry.setId,
        part = entry.part,
        growth = EquipGrowth(
            affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))
        ),
        meta = EquipInstanceMeta(
            rarity = entry.rarity,
            minRealm = entry.minRealm,
            description = entry.description
        ),
        ownerId = intId.toString(),
        isEquipped = true
    )
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

/** DiscipleTables 四部位槽位读写扩展（与 DiscipleEquipmentService 同族） */
private fun DiscipleTables.slotIdOf(id: Int, slot: EquipmentSlot): String = when (slot) {
    EquipmentSlot.HEAD -> headIds[id]
    EquipmentSlot.BODY -> bodyIds[id]
    EquipmentSlot.HANDS -> handsIds[id]
    EquipmentSlot.FEET -> feetIds[id]
}

private fun DiscipleTables.setSlotId(id: Int, slot: EquipmentSlot, value: String) {
    when (slot) {
        EquipmentSlot.HEAD -> headIds[id] = value
        EquipmentSlot.BODY -> bodyIds[id] = value
        EquipmentSlot.HANDS -> handsIds[id] = value
        EquipmentSlot.FEET -> feetIds[id] = value
    }
}
