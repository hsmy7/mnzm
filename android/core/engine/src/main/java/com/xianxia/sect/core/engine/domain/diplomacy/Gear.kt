package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentNurtureData
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.accessoryNurture
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.armorNurture
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.bootsNurture
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.model.weaponNurture
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.exploration.generateManuals

// ── AISectDiscipleManager 拆分域（行为零变更） ──
fun AISectDiscipleManager.isGearCompleteForLevel(disciple: Disciple, sectLevel: Int): Boolean =
    disciple.equipment.equippedItemIds.size >= (EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1) &&
        disciple.manualIds.size >= (MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)

/**
 * 为缺失的体质/词条/天赋分类生成随机标签（0-3 个），并写入已尝试标记。
 *
 * 标记保证后续读档不再对空分类重复 roll——空是合法状态（0-3 随机可能为 0），
 * 若不标记，每次读档都会重新 roll 并消耗 AI 分区 RNG，导致同档演化序列漂移。
 */

fun AISectDiscipleManager.applyGearToDisciple(disciple: Disciple, sectLevel: Int): Disciple {
    if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized) return disciple
    val maxRarity = GameConfig.Realm.getMaxRarity(disciple.realm)
    val equipmentIds = generateEquipmentIds(maxRarity, EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)
    val manuals = generateManuals(maxRarity, MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1)
    return disciple.copy(
        manualIds = manuals.map { it.first },
        manualMasteries = manuals.toMap(),
        equipment = disciple.equipment.copy(
            weaponId = equipmentIds[EquipmentSlot.WEAPON].orEmpty(),
            armorId = equipmentIds[EquipmentSlot.ARMOR].orEmpty(),
            bootsId = equipmentIds[EquipmentSlot.BOOTS].orEmpty(),
            accessoryId = equipmentIds[EquipmentSlot.ACCESSORY].orEmpty(),
            weaponNurture = generateInitialNurture(equipmentIds[EquipmentSlot.WEAPON].orEmpty()),
            armorNurture = generateInitialNurture(equipmentIds[EquipmentSlot.ARMOR].orEmpty()),
            bootsNurture = generateInitialNurture(equipmentIds[EquipmentSlot.BOOTS].orEmpty()),
            accessoryNurture = generateInitialNurture(equipmentIds[EquipmentSlot.ACCESSORY].orEmpty())
        )
    )
}

/**
 * 只补缺不覆盖：体质/词条/天赋为空则生成，装备/功法不足则补至宗门等级数量。
 *
 * 用于旧档补全与宗门等级升级后的数量补齐，绝不重生成或删除已有项。
 * 体质/词条/天赋为 0-3 随机生成（可能 roll 出 0 个），补全后写入
 * [GEAR_ROLL_MARKER] 标记——防止下次读档对空分类重复 roll 造成
 * AI 分区 RNG 序列漂移（同档两次读档演化结果不一致）。
 *
 * @param disciple 目标弟子
 * @param sectLevel 宗门等级（0-3）
 * @return 补齐后的弟子副本
 */

fun AISectDiscipleManager.ensureDiscipleGear(disciple: Disciple, sectLevel: Int): Disciple {
    var working = disciple
    if (working.statusData?.get(GEAR_ROLL_MARKER) != "1") {
        working = rollMissingCategories(working)
    }
    if (!ManualDatabase.isInitialized || !EquipmentDatabase.isInitialized) return working

    val maxRarity = GameConfig.Realm.getMaxRarity(working.realm)
    val expectedEquip = EQUIPMENT_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1
    val expectedManuals = MANUAL_COUNT_BY_SECT_LEVEL[sectLevel] ?: 1

    var equipment = working.equipment
    val currentEquip = equipment.equippedItemIds.size
    if (currentEquip < expectedEquip) {
        val emptySlots = EquipmentSlot.values()
            .filter { slot -> equipment.idFor(slot).isEmpty() }
            .shuffled(java.util.Random(rng.nextInt().toLong()))
        val toAdd = (expectedEquip - currentEquip).coerceAtMost(emptySlots.size)
        repeat(toAdd) { i ->
            val slot = emptySlots[i]
            val template = pickEquipmentTemplate(slot, maxRarity) ?: return@repeat
            equipment = equipment.withEquipped(slot, template.id, generateInitialNurture(template.id))
        }
    }
    working = working.copy(equipment = equipment)

    val currentManuals = working.manualIds.size
    if (currentManuals < expectedManuals) {
        val existing = working.manualIds.toSet()
        // 已有心法时补全不再生成心法（避免同弟子多本心法堆叠）；无则补 1 本
        val hasMindManual = existing.any { ManualDatabase.getById(it)?.type == ManualType.MIND }
        val newManuals = generateManuals(maxRarity, expectedManuals, includeMind = !hasMindManual)
            .filter { it.first !in existing }
            .take(expectedManuals - currentManuals)
        working = working.copy(
            manualIds = working.manualIds + newManuals.map { it.first },
            manualMasteries = working.manualMasteries + newManuals.toMap()
        )
    }
    return working
}

/** 按境界上限品阶从槽位模板池选取装备（无该品阶时取槽位最高品阶兜底）。 */

fun AISectDiscipleManager.buildEquipmentMapForDisciple(disciple: Disciple): Map<String, EquipmentInstance> {
    val equipmentMap = mutableMapOf<String, EquipmentInstance>()
    buildEquipmentEntry(equipmentMap, disciple.equipment.weaponId, disciple.equipment.weaponNurture)
    buildEquipmentEntry(equipmentMap, disciple.equipment.armorId, disciple.equipment.armorNurture)
    buildEquipmentEntry(equipmentMap, disciple.equipment.bootsId, disciple.equipment.bootsNurture)
    buildEquipmentEntry(equipmentMap, disciple.equipment.accessoryId, disciple.equipment.accessoryNurture)
    return equipmentMap
}

/**
 * 从持久化字段构建单个弟子的功法实例映射 + 熟练度数据。
 *
 * 功法实例以模板 id 为实例 id（AI 侧不落玩家实例表），
 * 熟练度从 [Disciple.manualMasteries] 转换（[ManualProficiencyData] 语义）。
 */

fun AISectDiscipleManager.buildManualDataForDisciple(
    disciple: Disciple
): Pair<Map<String, ManualInstance>, Map<String, ManualProficiencyData>> {
    val manualMap = mutableMapOf<String, ManualInstance>()
    for (mId in disciple.manualIds) {
        if (mId !in manualMap) {
            val template = ManualDatabase.getById(mId) ?: continue
            manualMap[mId] = ManualDatabase.createFromTemplate(template)
                .toInstance(id = mId)
        }
    }
    return Pair(manualMap, buildProficiencyDataFromMasteries(disciple))
}

/**
 * 将 [Disciple.manualMasteries]（模板 id → 熟练度）转换为
 * 修炼/战斗可用的 [ManualProficiencyData] 映射（manualId → 数据）。
 */

fun AISectDiscipleManager.buildProficiencyDataFromMasteries(
    disciple: Disciple
): Map<String, ManualProficiencyData> {
    return disciple.manualIds.associateWith { mId ->
        val mastery = disciple.manualMasteries[mId] ?: 0
        val manual = ManualDatabase.getById(mId)
        val masteryLevel = if (manual != null) {
            ManualProficiencySystem.MasteryLevel.fromProficiency(mastery.toDouble()).level
        } else 0
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        ManualProficiencyData(
            manualId = mId,
            // 下界防护：损坏存档负熟练度归零（负值会放大 NOVICE 加成语义）
            proficiency = mastery.toDouble().coerceIn(0.0, maxProf.toDouble()),
            maxProficiency = maxProf,
            masteryLevel = masteryLevel
        )
    }
}

/** 向装备映射中添加单件装备条目（如已存在则跳过）。 */

internal fun AISectDiscipleManager.buildEquipmentEntry(
    equipmentMap: MutableMap<String, EquipmentInstance>,
    eqId: String,
    nurture: EquipmentNurtureData
) {
    if (eqId.isEmpty() || eqId in equipmentMap) return
    val template = EquipmentDatabase.getById(eqId) ?: return
    var instance = EquipmentDatabase.createFromTemplate(template).toInstance(id = eqId)
    if (nurture.equipmentId == eqId) {
        instance = instance.copy(
            nurtureLevel = nurture.nurtureLevel,
            nurtureProgress = nurture.nurtureProgress
        )
    }
    equipmentMap[eqId] = instance
}

/** 初始装备孕养数据（AI 装备从 0 级 0 进度起步，由月度增长温养）。 */
