package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.model.BagStackedData

/** 持续/临时战斗属性丹效果：面板加成 + 持续时间 */
// ── DiscipleFacadeImpl 拆分域 2/2（行为零变更） ──
internal fun MutableGameState.applyBattleAttrEffects(id: Int, effect: PillEffect, pill: Pill, rule: PillRule) {
    discipleTables.pillPhysicalAttackBonuses[id] = effect.physicalAttackAdd
    discipleTables.pillMagicAttackBonuses[id] = effect.magicAttackAdd
    discipleTables.pillPhysicalDefenseBonuses[id] = effect.physicalDefenseAdd
    discipleTables.pillMagicDefenseBonuses[id] = effect.magicDefenseAdd
    discipleTables.pillHpBonuses[id] = effect.hpAdd
    discipleTables.pillMpBonuses[id] = effect.mpAdd
    discipleTables.pillSpeedBonuses[id] = effect.speedAdd
    discipleTables.pillCritRateBonuses[id] = effect.critRateAdd
    discipleTables.pillCritEffectBonuses[id] = effect.critEffectAdd
    discipleTables.pillCultivationSpeedBonuses[id] = effect.cultivationSpeedPercent
    discipleTables.pillSkillExpSpeedBonuses[id] = effect.skillExpSpeedPercent
    discipleTables.pillNurtureSpeedBonuses[id] = effect.nurtureSpeedPercent
    // 以旬为单位，不再 *30
    val currentDuration = discipleTables.pillEffectDurations[id]
    discipleTables.pillEffectDurations[id] = if (effect.duration > 0)
        maxOf(currentDuration, effect.duration)
    else currentDuration

    // 丹药修炼速度加成统一收敛于 pillEffects 体系——
    // 清零旧 cultivationSpeedBonus 组件列（旧档残留数据自愈），
    // 防止旧档残留加成在乘区之外继续影响速率
    discipleTables.cultivationSpeedBonuses[id] = 0.0
    discipleTables.cultivationSpeedDurations[id] = 0

    // 持续/临时效果记录 pillType
    if (rule == PillRule.SUSTAINED_SPEED || rule == PillRule.TEMPORARY_BATTLE) {
        val activeTypes = discipleTables.activePillTypes[id]
        if (pill.pillType.isNotEmpty()) {
            discipleTables.activePillTypes[id] = activeTypes + pill.pillType
        }
    }

    // 速率变化点必须同步 checkpoint：
    // 修炼速度丹修改 pillCultivationSpeedBonuses 影响速率，
    // 缺失会导致 getEffectiveCultivation 投影用旧速率推导（checkpoint 死代码埋雷）
    if (effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 ||
        effect.nurtureSpeedPercent > 0
    ) {
        discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)
    }
}

/** 百分比回血丹药效果 */

internal fun MutableGameState.applyHealEffect(id: Int, effect: PillEffect) {
    val rawHp = discipleTables.currentHps[id]
    val maxHp = discipleTables.baseHps[id]
    val currentHp = if (rawHp < 0) maxHp else rawHp
    val healAmount = (maxHp * effect.healMaxHpPercent).toInt().coerceAtLeast(1)
    discipleTables.currentHps[id] = (currentHp + healAmount).coerceAtMost(maxHp)
}

/** 清除丹药效果 */

internal fun MutableGameState.applyClearAllEffect(id: Int) {
    discipleTables.pillPhysicalAttackBonuses[id] = 0
    discipleTables.pillMagicAttackBonuses[id] = 0
    discipleTables.pillPhysicalDefenseBonuses[id] = 0
    discipleTables.pillMagicDefenseBonuses[id] = 0
    discipleTables.pillHpBonuses[id] = 0
    discipleTables.pillMpBonuses[id] = 0
    discipleTables.pillSpeedBonuses[id] = 0
    discipleTables.pillEffectDurations[id] = 0
    discipleTables.pillCritRateBonuses[id] = 0.0
    discipleTables.pillCritEffectBonuses[id] = 0.0
    discipleTables.pillCultivationSpeedBonuses[id] = 0.0
    discipleTables.pillSkillExpSpeedBonuses[id] = 0.0
    discipleTables.pillNurtureSpeedBonuses[id] = 0.0
    discipleTables.activePillCategories[id] = ""
    discipleTables.activePillTypes[id] = emptySet()
}

internal fun DiscipleFacadeImpl.rewardPill(discipleId: String, item: RewardSelectedItem, quantity: Int) {
    stateStore.update {
        val pill = pills.get(item.id)
        if (pill == null || pill.quantity < quantity) return@update
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@update
        val pillItem = StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_PILL,
            name = pill.name, rarity = pill.rarity, quantity = quantity,
            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
            effect = pillManager.pillToItemEffect(pill),
            grade = pill.grade.displayName,
            // 已物化标记（数据全在顶层字段，无需堆叠查找）
            stackedData = BagStackedData())
        val disciple = discipleTables.assemble(id)
        val canUse = pillManager.canUsePill(disciple, pillItem).canUse
        if (pill.quantity == quantity) pills.remove(item.id)
        else pills.update(item.id) { it.copy(quantity = pill.quantity - quantity) }
        if (canUse) {
            applyPillEffectsToDisciple(id, pill)
        } else {
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id], pillItem)
        }
    }
}

internal fun DiscipleFacadeImpl.rewardMaterial(discipleId: String, item: RewardSelectedItem, quantity: Int) {
    stateStore.update {
        // 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
        val id = discipleId.toIntOrNull() ?: return@update
        if (!discipleTables.ids.contains(id)) return@update
        val material = materials.get(item.id)
        if (material == null || material.isLocked || quantity !in 1..material.quantity) return@update
        if (material.quantity == quantity) materials.remove(item.id)
        else materials.update(item.id) { it.copy(quantity = material.quantity - quantity) }
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            discipleTables.storageBagItems[id],
            StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_MATERIAL, name = item.name,
                rarity = item.rarity, quantity = quantity,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                stackedData = BagStackedData())
        )
    }
}

internal fun DiscipleFacadeImpl.rewardHerb(discipleId: String, item: RewardSelectedItem, quantity: Int) {
    stateStore.update {
        // 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
        val id = discipleId.toIntOrNull() ?: return@update
        if (!discipleTables.ids.contains(id)) return@update
        val herb = herbs.get(item.id)
        if (herb == null || herb.isLocked || quantity !in 1..herb.quantity) return@update
        if (herb.quantity == quantity) herbs.remove(item.id)
        else herbs.update(item.id) { it.copy(quantity = herb.quantity - quantity) }
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            discipleTables.storageBagItems[id],
            StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_HERB, name = item.name,
                rarity = item.rarity, quantity = quantity,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                stackedData = BagStackedData())
        )
    }
}

internal fun DiscipleFacadeImpl.rewardSeed(discipleId: String, item: RewardSelectedItem, quantity: Int) {
    stateStore.update {
        // 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
        val id = discipleId.toIntOrNull() ?: return@update
        if (!discipleTables.ids.contains(id)) return@update
        val seed = seeds.get(item.id)
        if (seed == null || seed.isLocked || quantity !in 1..seed.quantity) return@update
        if (seed.quantity == quantity) seeds.remove(item.id)
        else seeds.update(item.id) { it.copy(quantity = seed.quantity - quantity) }
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            discipleTables.storageBagItems[id],
            StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_SEED, name = item.name,
                rarity = item.rarity, quantity = quantity,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                stackedData = BagStackedData())
        )
    }
}

/** 目标亲传槽旧 occupant 读取：槽位扩容前的原始列表 */
internal fun DiscipleFacadeImpl.getElderSlotOccupant(
    slots: ElderSlots,
    elderSlotType: String,
    slotIndex: Int
): String = when (elderSlotType) {
    SLOT_TYPE_HERB_GARDEN ->
        slots.herbGardenDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_ALCHEMY ->
        slots.alchemyDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_FORGE ->
        slots.forgeDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_PREACHING ->
        slots.preachingMasters.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_LAW_ENFORCEMENT ->
        slots.lawEnforcementDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_QINGYUN ->
        slots.qingyunPreachingMasters.getOrNull(slotIndex)?.discipleId.orEmpty()
    SLOT_TYPE_SPIRIT_MINE_DEACON ->
        slots.spiritMineDeaconDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
    else -> ""
}

/** 亲传槽位写入：扩容到 slotIndex 后覆写目标槽 */

internal fun DiscipleFacadeImpl.replaceElderSlot(
    slots: ElderSlots,
    elderSlotType: String,
    slotIndex: Int,
    newSlot: DirectDiscipleSlot
): ElderSlots = when (elderSlotType) {
    SLOT_TYPE_HERB_GARDEN -> {
        val list = growElderSlotList(list = slots.herbGardenDisciples.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(herbGardenDisciples = list)
    }
    SLOT_TYPE_ALCHEMY -> {
        val list = growElderSlotList(list = slots.alchemyDisciples.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(alchemyDisciples = list)
    }
    SLOT_TYPE_FORGE -> {
        val list = growElderSlotList(list = slots.forgeDisciples.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(forgeDisciples = list)
    }
    SLOT_TYPE_PREACHING -> {
        val list = growElderSlotList(list = slots.preachingMasters.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(preachingMasters = list)
    }
    SLOT_TYPE_LAW_ENFORCEMENT -> {
        val list = growElderSlotList(list = slots.lawEnforcementDisciples.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(lawEnforcementDisciples = list)
    }
    SLOT_TYPE_QINGYUN -> {
        val list = growElderSlotList(list = slots.qingyunPreachingMasters.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(qingyunPreachingMasters = list)
    }
    SLOT_TYPE_SPIRIT_MINE_DEACON -> {
        val list = growElderSlotList(list = slots.spiritMineDeaconDisciples.toMutableList(), index = slotIndex)
        list[slotIndex] = newSlot
        slots.copy(spiritMineDeaconDisciples = list)
    }
    else -> slots
}

/** 亲传槽列表扩容：用空槽补齐至 index 位置 */

internal fun DiscipleFacadeImpl.growElderSlotList(
    list: MutableList<DirectDiscipleSlot>,
    index: Int
): MutableList<DirectDiscipleSlot> {
    while (list.size <= index) list.add(DirectDiscipleSlot())
    return list
}

internal fun DiscipleFacadeImpl.getDirectDiscipleId(elderSlotType: String, slotIndex: Int): String {
    val slots = stateStore.gameDataSnapshot.elderSlots
    val list = when (elderSlotType) {
        SLOT_TYPE_HERB_GARDEN -> slots.herbGardenDisciples
        SLOT_TYPE_ALCHEMY -> slots.alchemyDisciples
        SLOT_TYPE_FORGE -> slots.forgeDisciples
        SLOT_TYPE_PREACHING -> slots.preachingMasters
        SLOT_TYPE_LAW_ENFORCEMENT -> slots.lawEnforcementDisciples
        SLOT_TYPE_QINGYUN -> slots.qingyunPreachingMasters
        SLOT_TYPE_SPIRIT_MINE_DEACON -> slots.spiritMineDeaconDisciples
        else -> emptyList()
    }
    return list.getOrNull(slotIndex)?.discipleId.orEmpty()
}

internal fun DiscipleFacadeImpl.usePill(discipleId: String, pillId: String) {
    gameEngineCore.launchInScope {
        stateStore.update {
            val pill = pills.get(pillId) ?: return@update
            if (pill.quantity <= 0) return@update
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update

            // 委托 pillManager 统一检查资格
            val disciple = discipleTables.assemble(id)
            val itemEffect = pillManager.pillToItemEffect(pill)
            val bagItem = StorageBagItem(
                itemId = pillId, itemType = ITEM_TYPE_PILL,
                name = pill.name, rarity = pill.rarity, quantity = 1,
                effect = itemEffect
            )
            if (!pillManager.canUsePill(disciple, bagItem).canUse) return@update

            if (pill.quantity > 1) {
                pills.update(pillId) { it.copy(quantity = it.quantity - 1) }
            } else {
                pills.remove(pillId)
            }

            applyPillEffectsToDisciple(id, pill)

            // 记录服药日志
            val pillAge = discipleTables.ages[id]
            val currentLifeEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            discipleTables.lifeEvents[id] = currentLifeEvents +
                "${pillAge}岁：服用了${pill.name}"
        }
    }
}

internal fun DiscipleFacadeImpl.learnManual(discipleId: String, stackId: String) {
    stateStore.update {
        val stack = manualStacks.get(stackId) ?: return@update
        val id = discipleId.toIntOrNull() ?: return@update
        if (!discipleTables.ids.contains(id)) return@update
        if (!canLearnManualFromStack(stack, id)) return@update

        consumeManualStackForLearn(stackId, stack)

        val instanceId = java.util.UUID.randomUUID().toString()
        val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
        manualInstances.add(instance)

        applyLearnedManualToTables(id, stack, instanceId)
    }
}

internal fun DiscipleFacadeImpl.forgetManual(discipleId: String, instanceId: String) {
    stateStore.update {
        val instance = manualInstances.get(instanceId) ?: return@update
        val id = discipleId.toIntOrNull() ?: return@update
        if (!discipleTables.ids.contains(id)) return@update
        val currentDisciple = discipleTables.assemble(id)

        // 遗忘的功法实例直接铸造入袋（容量无上限，永不失败），
        // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            currentDisciple.equipment.storageBagItems,
            StorageBagItem(
                itemId = instanceId, itemType = ITEM_TYPE_MANUAL_INSTANCE,
                name = instance.name, rarity = instance.rarity, quantity = 1,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                manualInstance = instance
            )
        )
        discipleTables.storageBagSpiritStones[id] = currentDisciple.equipment.storageBagSpiritStones
        discipleTables.discipleSpiritStones[id] = currentDisciple.equipment.spiritStones
        discipleTables.manualIds[id] = currentDisciple.manualIds
        manualInstances.remove(instanceId)
    }
}
