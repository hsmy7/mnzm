package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*

/**
 * 弟子组件表集合。
 *
 * 替代旧的 `MutableGameState.disciples: List<Disciple>`。
 * 每张组件表存储所有弟子的某一种属性。
 *
 * 全部操作 O(log n)，无对象分配（int/double 基本类型零装箱）。
 *
 * 使用方式：
 *   val name = tables.names[id]
 *   tables.loyalties[id] = 90
 *   tables.cultivations.update(id) { it + rate * delta }
 *   for (id in tables.ids) { ... }
 */
class DiscipleTables {

    /** 写操作计数器——GameStateStore 用于脏检测，跳过无变化的 assembleAll */
    @Volatile var mutationVersion: Long = 0
        private set

    /** 在每次写操作后调用，递增版本号 */
    fun markMutated() { mutationVersion++ }

    // === 标识 ===
    // CopyOnWriteArrayList 保证并发安全：读操作（maxOrNull/for-in/filter）
    // 无需额外同步，迭代器为快照不会抛 ConcurrentModificationException。
    // 写操作仍使用 synchronized(ids) 保护多表原子性（DiscipleTables 不是
    // 唯一受影响的表 — insert/remove 操作约 90 张组件表）。
    val ids: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList<Int>()

    // === 基础信息（ComponentTable<String>） ===
    val names = ComponentTable<String>()          // id → name
    val surnames = ComponentTable<String>()       // id → surname
    val genders = ComponentTable<String>()        // id → "male"/"female"
    val portraitRes = ComponentTable<String>()    // id → 头像资源
    val discipleTypes = ComponentTable<String>()  // id → "outer"/"inner"/"elder"
    val spiritRootTypes = ComponentTable<String>()// id → "metal"/"fire"/...
    val slotIds = IntComponentTable()             // id → slot_id (持久化用)

    // === 境界与修为（Int/Double 基本类型表） ===
    val realms = IntComponentTable()              // id → realm (9=练气 ... 0=仙人)
    val realmLayers = IntComponentTable()         // id → layer (1-9)
    val cultivations = DoubleComponentTable()     // id → cultivation progress
    val ages = IntComponentTable()                // id → age
    val lifespans = IntComponentTable()           // id → lifespan
    val isAlive = IntComponentTable()             // id → 0/1 (用 Int 避免 Boolean 装箱)
    val soulPowers = IntComponentTable()          // id → soulPower

    // === 修炼加速 ===
    val cultivationSpeedBonuses = DoubleComponentTable()
    val cultivationSpeedDurations = IntComponentTable()

    // === 自动行为 ===
    val autoLearnFromWarehouse = IntComponentTable()   // id → 0/1
    val autoEquipFromWarehouse = IntComponentTable()   // id → 0/1

    // === 列表类型（ComponentTable<List<T>>） ===
    val manualIds = ComponentTable<List<String>>()        // id → [manualId1, ...]
    val talentIds = ComponentTable<List<String>>()        // id → [talentId1, ...]
    val manualMasteries = ComponentTable<Map<String, Int>>()

    // === 状态 ===
    val statuses = ComponentTable<DiscipleStatus>()
    val statusData = ComponentTable<Map<String, String>>()

    // === 战斗属性（窄表） ===
    val baseHps = IntComponentTable()
    val baseMps = IntComponentTable()
    val basePhysicalAttacks = IntComponentTable()
    val baseMagicAttacks = IntComponentTable()
    val basePhysicalDefenses = IntComponentTable()
    val baseMagicDefenses = IntComponentTable()
    val baseSpeeds = IntComponentTable()
    val hpVariances = IntComponentTable()
    val mpVariances = IntComponentTable()
    val physicalAttackVariances = IntComponentTable()
    val magicAttackVariances = IntComponentTable()
    val physicalDefenseVariances = IntComponentTable()
    val magicDefenseVariances = IntComponentTable()
    val speedVariances = IntComponentTable()
    val totalCultivations = ComponentTable<Long>()
    val breakthroughCounts = IntComponentTable()
    val breakthroughFailCounts = IntComponentTable()
    val currentHps = IntComponentTable()
    val currentMps = IntComponentTable()

    // === 丹药效果 ===
    val pillPhysicalAttackBonuses = IntComponentTable()
    val pillMagicAttackBonuses = IntComponentTable()
    val pillPhysicalDefenseBonuses = IntComponentTable()
    val pillMagicDefenseBonuses = IntComponentTable()
    val pillHpBonuses = IntComponentTable()
    val pillMpBonuses = IntComponentTable()
    val pillSpeedBonuses = IntComponentTable()
    val pillEffectDurations = IntComponentTable()
    val pillCritRateBonuses = DoubleComponentTable()
    val pillCritEffectBonuses = DoubleComponentTable()
    val pillCultivationSpeedBonuses = DoubleComponentTable()
    val pillSkillExpSpeedBonuses = DoubleComponentTable()
    val pillNurtureSpeedBonuses = DoubleComponentTable()
    val activePillCategories = ComponentTable<String>()

    // === 装备 ===
    val weaponIds = ComponentTable<String>()
    val armorIds = ComponentTable<String>()
    val bootsIds = ComponentTable<String>()
    val accessoryIds = ComponentTable<String>()
    val weaponNurtures = ComponentTable<EquipmentNurtureData>()
    val armorNurtures = ComponentTable<EquipmentNurtureData>()
    val bootsNurtures = ComponentTable<EquipmentNurtureData>()
    val accessoryNurtures = ComponentTable<EquipmentNurtureData>()
    val storageBagItems = ComponentTable<List<StorageBagItem>>()
    val storageBagSpiritStones = ComponentTable<Long>()
    val discipleSpiritStones = IntComponentTable()
    val cultivationCompletionMonths = IntComponentTable()
    val cultivationCompletionPhases = IntComponentTable()
    val manualCompletionMonths = IntComponentTable()
    val manualCompletionPhases = IntComponentTable()
    val equipmentNurturingCompletionMonths = IntComponentTable()
    val equipmentNurturingCompletionPhases = IntComponentTable()

    // === 社交 ===
    val partnerIds = ComponentTable<String?>()       // nullable
    val partnerSectIds = ComponentTable<String?>()
    val parentId1s = ComponentTable<String?>()
    val parentId2s = ComponentTable<String?>()
    val lastChildYears = IntComponentTable()
    val childBirthMonths = ComponentTable<Int?>()    // nullable
    val griefEndYears = ComponentTable<Int?>()

    // === 技能属性 ===
    val intelligences = IntComponentTable()
    val charms = IntComponentTable()
    val loyalties = IntComponentTable()
    val comprehensions = IntComponentTable()
    val artifactRefinings = IntComponentTable()
    val pillRefinings = IntComponentTable()
    val spiritPlantings = IntComponentTable()
    val minings = IntComponentTable()
    val teachings = IntComponentTable()
    val moralities = IntComponentTable()
    val salaryPaidCounts = IntComponentTable()
    val salaryMissedCounts = IntComponentTable()

    // === 使用追踪 ===
    val usedFunctionalPillTypes = ComponentTable<List<String>>()
    val usedExtendLifePillIds = ComponentTable<List<String>>()
    val recruitedMonths = IntComponentTable()
    val hasReviveEffects = IntComponentTable()    // 0/1
    val hasClearAllEffects = IntComponentTable()  // 0/1
    val lastTheftMonths = IntComponentTable()

    // === 弟子总数 ===
    val count: Int get() = ids.size

    init { bindAllOnWrite() }

    /* ================================================================
     * 核心 API
     * ================================================================ */

    /**
     * 添加一个新弟子。所有组件表同时插入一行。
     */
    fun insert(disciple: Disciple) {
        val id = disciple.id.toInt()
        // synchronized 确保 check-and-add 原子性，防止多协程交错产生重复 ID。
        synchronized(ids) {
            if (id in ids) {
                update(disciple)
                return
            }
            ids.add(id)
        }

        names[id] = disciple.name
        surnames[id] = disciple.surname
        genders[id] = disciple.gender
        portraitRes[id] = disciple.portraitRes
        discipleTypes[id] = disciple.discipleType
        spiritRootTypes[id] = disciple.spiritRootType
        slotIds[id] = disciple.slotId

        realms[id] = disciple.realm
        realmLayers[id] = disciple.realmLayer
        cultivations[id] = disciple.cultivation
        ages[id] = disciple.age
        lifespans[id] = disciple.lifespan
        isAlive[id] = if (disciple.isAlive) 1 else 0
        soulPowers[id] = disciple.soulPower

        cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
        cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

        autoLearnFromWarehouse[id] = if (disciple.autoLearnFromWarehouse) 1 else 0
        autoEquipFromWarehouse[id] = if (disciple.equipment.autoEquipFromWarehouse) 1 else 0

        manualIds[id] = disciple.manualIds
        talentIds[id] = disciple.talentIds
        manualMasteries[id] = disciple.manualMasteries

        statuses[id] = disciple.status
        statusData[id] = disciple.statusData

        // 战斗属性
        val c = disciple.combat
        baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
        basePhysicalAttacks[id] = c.basePhysicalAttack
        baseMagicAttacks[id] = c.baseMagicAttack
        basePhysicalDefenses[id] = c.basePhysicalDefense
        baseMagicDefenses[id] = c.baseMagicDefense
        baseSpeeds[id] = c.baseSpeed
        hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
        physicalAttackVariances[id] = c.physicalAttackVariance
        magicAttackVariances[id] = c.magicAttackVariance
        physicalDefenseVariances[id] = c.physicalDefenseVariance
        magicDefenseVariances[id] = c.magicDefenseVariance
        speedVariances[id] = c.speedVariance
        totalCultivations[id] = c.totalCultivation
        breakthroughCounts[id] = c.breakthroughCount
        breakthroughFailCounts[id] = c.breakthroughFailCount
        currentHps[id] = c.currentHp; currentMps[id] = c.currentMp

        // 丹药效果
        val p = disciple.pillEffects
        pillPhysicalAttackBonuses[id] = p.pillPhysicalAttackBonus
        pillMagicAttackBonuses[id] = p.pillMagicAttackBonus
        pillPhysicalDefenseBonuses[id] = p.pillPhysicalDefenseBonus
        pillMagicDefenseBonuses[id] = p.pillMagicDefenseBonus
        pillHpBonuses[id] = p.pillHpBonus; pillMpBonuses[id] = p.pillMpBonus
        pillSpeedBonuses[id] = p.pillSpeedBonus
        pillEffectDurations[id] = p.pillEffectDuration
        pillCritRateBonuses[id] = p.pillCritRateBonus
        pillCritEffectBonuses[id] = p.pillCritEffectBonus
        pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
        pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
        pillNurtureSpeedBonuses[id] = p.pillNurtureSpeedBonus
        activePillCategories[id] = p.activePillCategory

        // 装备
        val e = disciple.equipment
        weaponIds[id] = e.weaponId; armorIds[id] = e.armorId
        bootsIds[id] = e.bootsId; accessoryIds[id] = e.accessoryId
        weaponNurtures[id] = e.weaponNurture
        armorNurtures[id] = e.armorNurture
        bootsNurtures[id] = e.bootsNurture
        accessoryNurtures[id] = e.accessoryNurture
        storageBagItems[id] = e.storageBagItems
        storageBagSpiritStones[id] = e.storageBagSpiritStones
        discipleSpiritStones[id] = e.spiritStones
        cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
        cultivationCompletionPhases[id] = disciple.cultivationCompletionPhase
        manualCompletionMonths[id] = disciple.manualCompletionMonth
        manualCompletionPhases[id] = disciple.manualCompletionPhase
        equipmentNurturingCompletionMonths[id] = disciple.equipmentNurturingCompletionMonth
        equipmentNurturingCompletionPhases[id] = disciple.equipmentNurturingCompletionPhase

        // 社交
        val s = disciple.social
        s.partnerId?.let { partnerIds[id] = it }
        s.partnerSectId?.let { partnerSectIds[id] = it }
        s.parentId1?.let { parentId1s[id] = it }
        s.parentId2?.let { parentId2s[id] = it }
        lastChildYears[id] = s.lastChildYear
        s.childBirthMonth?.let { childBirthMonths[id] = it }
        s.griefEndYear?.let { griefEndYears[id] = it }

        // 技能
        val sk = disciple.skills
        intelligences[id] = sk.intelligence; charms[id] = sk.charm
        loyalties[id] = sk.loyalty; comprehensions[id] = sk.comprehension
        artifactRefinings[id] = sk.artifactRefining; pillRefinings[id] = sk.pillRefining
        spiritPlantings[id] = sk.spiritPlanting; minings[id] = sk.mining
        teachings[id] = sk.teaching; moralities[id] = sk.morality
        salaryPaidCounts[id] = sk.salaryPaidCount; salaryMissedCounts[id] = sk.salaryMissedCount

        // 使用追踪
        val u = disciple.usage
        usedFunctionalPillTypes[id] = u.usedFunctionalPillTypes
        usedExtendLifePillIds[id] = u.usedExtendLifePillIds
        recruitedMonths[id] = u.recruitedMonth
        hasReviveEffects[id] = if (u.hasReviveEffect) 1 else 0
        hasClearAllEffects[id] = if (u.hasClearAllEffect) 1 else 0
        lastTheftMonths[id] = u.lastTheftMonth
    }

    /**
     * 更新一个已有弟子的所有组件字段（不修改 ids 列表）。
     * 用于从组装后的 Disciple 对象写回修改。
     */
    fun update(disciple: Disciple) {
        val id = disciple.id.toInt()

        names[id] = disciple.name
        surnames[id] = disciple.surname
        genders[id] = disciple.gender
        portraitRes[id] = disciple.portraitRes
        discipleTypes[id] = disciple.discipleType
        spiritRootTypes[id] = disciple.spiritRootType
        slotIds[id] = disciple.slotId

        realms[id] = disciple.realm
        realmLayers[id] = disciple.realmLayer
        cultivations[id] = disciple.cultivation
        ages[id] = disciple.age
        lifespans[id] = disciple.lifespan
        isAlive[id] = if (disciple.isAlive) 1 else 0
        soulPowers[id] = disciple.soulPower

        cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
        cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

        autoLearnFromWarehouse[id] = if (disciple.autoLearnFromWarehouse) 1 else 0
        autoEquipFromWarehouse[id] = if (disciple.equipment.autoEquipFromWarehouse) 1 else 0

        manualIds[id] = disciple.manualIds
        talentIds[id] = disciple.talentIds
        manualMasteries[id] = disciple.manualMasteries

        statuses[id] = disciple.status
        statusData[id] = disciple.statusData

        val c = disciple.combat
        baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
        basePhysicalAttacks[id] = c.basePhysicalAttack
        baseMagicAttacks[id] = c.baseMagicAttack
        basePhysicalDefenses[id] = c.basePhysicalDefense
        baseMagicDefenses[id] = c.baseMagicDefense
        baseSpeeds[id] = c.baseSpeed
        hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
        physicalAttackVariances[id] = c.physicalAttackVariance
        magicAttackVariances[id] = c.magicAttackVariance
        physicalDefenseVariances[id] = c.physicalDefenseVariance
        magicDefenseVariances[id] = c.magicDefenseVariance
        speedVariances[id] = c.speedVariance
        totalCultivations[id] = c.totalCultivation
        breakthroughCounts[id] = c.breakthroughCount
        breakthroughFailCounts[id] = c.breakthroughFailCount
        currentHps[id] = c.currentHp; currentMps[id] = c.currentMp

        val p = disciple.pillEffects
        pillPhysicalAttackBonuses[id] = p.pillPhysicalAttackBonus
        pillMagicAttackBonuses[id] = p.pillMagicAttackBonus
        pillPhysicalDefenseBonuses[id] = p.pillPhysicalDefenseBonus
        pillMagicDefenseBonuses[id] = p.pillMagicDefenseBonus
        pillHpBonuses[id] = p.pillHpBonus; pillMpBonuses[id] = p.pillMpBonus
        pillSpeedBonuses[id] = p.pillSpeedBonus
        pillEffectDurations[id] = p.pillEffectDuration
        pillCritRateBonuses[id] = p.pillCritRateBonus
        pillCritEffectBonuses[id] = p.pillCritEffectBonus
        pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
        pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
        pillNurtureSpeedBonuses[id] = p.pillNurtureSpeedBonus
        activePillCategories[id] = p.activePillCategory

        val e = disciple.equipment
        weaponIds[id] = e.weaponId; armorIds[id] = e.armorId
        bootsIds[id] = e.bootsId; accessoryIds[id] = e.accessoryId
        weaponNurtures[id] = e.weaponNurture
        armorNurtures[id] = e.armorNurture
        bootsNurtures[id] = e.bootsNurture
        accessoryNurtures[id] = e.accessoryNurture
        storageBagItems[id] = e.storageBagItems
        storageBagSpiritStones[id] = e.storageBagSpiritStones
        discipleSpiritStones[id] = e.spiritStones
        cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
        cultivationCompletionPhases[id] = disciple.cultivationCompletionPhase
        manualCompletionMonths[id] = disciple.manualCompletionMonth
        manualCompletionPhases[id] = disciple.manualCompletionPhase
        equipmentNurturingCompletionMonths[id] = disciple.equipmentNurturingCompletionMonth
        equipmentNurturingCompletionPhases[id] = disciple.equipmentNurturingCompletionPhase

        val s = disciple.social
        partnerIds[id] = s.partnerId; partnerSectIds[id] = s.partnerSectId
        parentId1s[id] = s.parentId1; parentId2s[id] = s.parentId2
        lastChildYears[id] = s.lastChildYear
        s.childBirthMonth?.let { childBirthMonths[id] = it }
        s.griefEndYear?.let { griefEndYears[id] = it }

        val sk = disciple.skills
        intelligences[id] = sk.intelligence; charms[id] = sk.charm
        loyalties[id] = sk.loyalty; comprehensions[id] = sk.comprehension
        artifactRefinings[id] = sk.artifactRefining; pillRefinings[id] = sk.pillRefining
        spiritPlantings[id] = sk.spiritPlanting; minings[id] = sk.mining
        teachings[id] = sk.teaching; moralities[id] = sk.morality
        salaryPaidCounts[id] = sk.salaryPaidCount; salaryMissedCounts[id] = sk.salaryMissedCount

        val u = disciple.usage
        usedFunctionalPillTypes[id] = u.usedFunctionalPillTypes
        usedExtendLifePillIds[id] = u.usedExtendLifePillIds
        recruitedMonths[id] = u.recruitedMonth
        hasReviveEffects[id] = if (u.hasReviveEffect) 1 else 0
        hasClearAllEffects[id] = if (u.hasClearAllEffect) 1 else 0
        lastTheftMonths[id] = u.lastTheftMonth
    }

    /**
     * 从组件表组装一个完整的 Disciple 对象。
     * 仅在需要"完整弟子视图"时调用：
     *   - UI 渲染（Screen 层）
     *   - 序列化/持久化
     *   - 网络同步
     * 不应在 tick 热路径中调用。
     */
    fun assemble(id: Int): Disciple {
        return Disciple(
            id = id.toString(),
            slotId = slotIds.getOrDefault(id, 0),
            name = names.getOrNull(id) ?: "",
            surname = surnames.getOrNull(id) ?: "",
            realm = realms.getOrDefault(id, 9),
            realmLayer = realmLayers.getOrDefault(id, 1),
            cultivation = cultivations.getOrDefault(id, 0.0),
            spiritRootType = spiritRootTypes.getOrNull(id) ?: "metal",
            age = ages.getOrDefault(id, 16),
            lifespan = lifespans.getOrDefault(id, 80),
            isAlive = isAlive.getOrDefault(id, 1) == 1,
            gender = genders.getOrNull(id) ?: "male",
            portraitRes = portraitRes.getOrNull(id) ?: "",
            manualIds = manualIds.getOrNull(id) ?: emptyList(),
            talentIds = talentIds.getOrNull(id) ?: emptyList(),
            manualMasteries = manualMasteries.getOrNull(id) ?: emptyMap(),
            status = statuses.getOrNull(id) ?: DiscipleStatus.IDLE,
            statusData = statusData.getOrNull(id) ?: emptyMap(),
            cultivationSpeedBonus = cultivationSpeedBonuses.getOrDefault(id, 0.0),
            cultivationSpeedDuration = cultivationSpeedDurations.getOrDefault(id, 0),
            discipleType = discipleTypes.getOrNull(id) ?: "outer",
            autoLearnFromWarehouse = autoLearnFromWarehouse.getOrDefault(id, 0) == 1,
            soulPower = soulPowers.getOrDefault(id, 0),
            cultivationCompletionMonth = cultivationCompletionMonths.getOrDefault(id, 0),
            cultivationCompletionPhase = cultivationCompletionPhases.getOrDefault(id, 1),
            manualCompletionMonth = manualCompletionMonths.getOrDefault(id, 0),
            manualCompletionPhase = manualCompletionPhases.getOrDefault(id, 1),
            equipmentNurturingCompletionMonth = equipmentNurturingCompletionMonths.getOrDefault(id, 0),
            equipmentNurturingCompletionPhase = equipmentNurturingCompletionPhases.getOrDefault(id, 1),
            combat = CombatAttributes(
                baseHp = baseHps.getOrDefault(id, 0), baseMp = baseMps.getOrDefault(id, 0),
                basePhysicalAttack = basePhysicalAttacks.getOrDefault(id, 0),
                baseMagicAttack = baseMagicAttacks.getOrDefault(id, 0),
                basePhysicalDefense = basePhysicalDefenses.getOrDefault(id, 0),
                baseMagicDefense = baseMagicDefenses.getOrDefault(id, 0),
                baseSpeed = baseSpeeds.getOrDefault(id, 0),
                hpVariance = hpVariances.getOrDefault(id, 0), mpVariance = mpVariances.getOrDefault(id, 0),
                physicalAttackVariance = physicalAttackVariances.getOrDefault(id, 0),
                magicAttackVariance = magicAttackVariances.getOrDefault(id, 0),
                physicalDefenseVariance = physicalDefenseVariances.getOrDefault(id, 0),
                magicDefenseVariance = magicDefenseVariances.getOrDefault(id, 0),
                speedVariance = speedVariances.getOrDefault(id, 0),
                totalCultivation = totalCultivations.getOrNull(id) ?: 0L,
                breakthroughCount = breakthroughCounts.getOrDefault(id, 0),
                breakthroughFailCount = breakthroughFailCounts.getOrDefault(id, 0),
                currentHp = currentHps.getOrDefault(id, 0), currentMp = currentMps.getOrDefault(id, 0)
            ),
            pillEffects = PillEffects(
                pillPhysicalAttackBonus = pillPhysicalAttackBonuses.getOrDefault(id, 0),
                pillMagicAttackBonus = pillMagicAttackBonuses.getOrDefault(id, 0),
                pillPhysicalDefenseBonus = pillPhysicalDefenseBonuses.getOrDefault(id, 0),
                pillMagicDefenseBonus = pillMagicDefenseBonuses.getOrDefault(id, 0),
                pillHpBonus = pillHpBonuses.getOrDefault(id, 0), pillMpBonus = pillMpBonuses.getOrDefault(id, 0),
                pillSpeedBonus = pillSpeedBonuses.getOrDefault(id, 0),
                pillEffectDuration = pillEffectDurations.getOrDefault(id, 0),
                pillCritRateBonus = pillCritRateBonuses.getOrDefault(id, 0.0),
                pillCritEffectBonus = pillCritEffectBonuses.getOrDefault(id, 0.0),
                pillCultivationSpeedBonus = pillCultivationSpeedBonuses.getOrDefault(id, 0.0),
                pillSkillExpSpeedBonus = pillSkillExpSpeedBonuses.getOrDefault(id, 0.0),
                pillNurtureSpeedBonus = pillNurtureSpeedBonuses.getOrDefault(id, 0.0),
                activePillCategory = activePillCategories.getOrNull(id) ?: ""
            ),
            equipment = EquipmentSet(
                weaponId = weaponIds.getOrNull(id) ?: "",
                armorId = armorIds.getOrNull(id) ?: "",
                bootsId = bootsIds.getOrNull(id) ?: "",
                accessoryId = accessoryIds.getOrNull(id) ?: "",
                weaponNurture = weaponNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
                armorNurture = armorNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
                bootsNurture = bootsNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
                accessoryNurture = accessoryNurtures.getOrNull(id) ?: EquipmentNurtureData(equipmentId = "", rarity = 0),
                autoEquipFromWarehouse = autoEquipFromWarehouse.getOrDefault(id, 0) == 1,
                storageBagItems = storageBagItems.getOrNull(id) ?: emptyList(),
                storageBagSpiritStones = storageBagSpiritStones.getOrNull(id) ?: 0L,
                spiritStones = discipleSpiritStones.getOrDefault(id, 0)
            ),
            social = SocialData(
                partnerId = partnerIds.getOrNull(id),
                partnerSectId = partnerSectIds.getOrNull(id),
                parentId1 = parentId1s.getOrNull(id),
                parentId2 = parentId2s.getOrNull(id),
                lastChildYear = lastChildYears.getOrDefault(id, 0),
                childBirthMonth = childBirthMonths.getOrNull(id),
                griefEndYear = griefEndYears.getOrNull(id)
            ),
            skills = SkillStats(
                intelligence = intelligences.getOrDefault(id, 0), charm = charms.getOrDefault(id, 0),
                loyalty = loyalties.getOrDefault(id, 0), comprehension = comprehensions.getOrDefault(id, 0),
                artifactRefining = artifactRefinings.getOrDefault(id, 0),
                pillRefining = pillRefinings.getOrDefault(id, 0),
                spiritPlanting = spiritPlantings.getOrDefault(id, 0),
                mining = minings.getOrDefault(id, 0), teaching = teachings.getOrDefault(id, 0),
                morality = moralities.getOrDefault(id, 0),
                salaryPaidCount = salaryPaidCounts.getOrDefault(id, 0),
                salaryMissedCount = salaryMissedCounts.getOrDefault(id, 0)
            ),
            usage = UsageTracking(
                usedFunctionalPillTypes = usedFunctionalPillTypes.getOrNull(id) ?: emptyList(),
                usedExtendLifePillIds = usedExtendLifePillIds.getOrNull(id) ?: emptyList(),
                recruitedMonth = recruitedMonths.getOrDefault(id, 0),
                hasReviveEffect = hasReviveEffects.getOrDefault(id, 0) == 1,
                hasClearAllEffect = hasClearAllEffects.getOrDefault(id, 0) == 1,
                lastTheftMonth = lastTheftMonths.getOrDefault(id, 0)
            )
        )
    }

    /** 组装全部弟子的 List<Disciple>（用于序列化、旧 API 兼容）。 */
    fun assembleAll(): List<Disciple> = ids.distinct().map { assemble(it) }

    /**
     * 删除一个弟子。所有组件表同时删除对应行。
     */
    fun remove(id: Int) {
        synchronized(ids) { ids.remove(id) }
        names.remove(id); surnames.remove(id); genders.remove(id)
        portraitRes.remove(id); discipleTypes.remove(id); spiritRootTypes.remove(id)
        slotIds.remove(id)
        realms.remove(id); realmLayers.remove(id); cultivations.remove(id)
        ages.remove(id); lifespans.remove(id); isAlive.remove(id); soulPowers.remove(id)
        cultivationSpeedBonuses.remove(id); cultivationSpeedDurations.remove(id)
        autoLearnFromWarehouse.remove(id); autoEquipFromWarehouse.remove(id)
        manualIds.remove(id); talentIds.remove(id); manualMasteries.remove(id)
        statuses.remove(id); statusData.remove(id)
        baseHps.remove(id); baseMps.remove(id)
        basePhysicalAttacks.remove(id); baseMagicAttacks.remove(id)
        basePhysicalDefenses.remove(id); baseMagicDefenses.remove(id); baseSpeeds.remove(id)
        hpVariances.remove(id); mpVariances.remove(id)
        physicalAttackVariances.remove(id); magicAttackVariances.remove(id)
        physicalDefenseVariances.remove(id); magicDefenseVariances.remove(id)
        speedVariances.remove(id)
        totalCultivations.remove(id); breakthroughCounts.remove(id); breakthroughFailCounts.remove(id)
        currentHps.remove(id); currentMps.remove(id)
        pillPhysicalAttackBonuses.remove(id); pillMagicAttackBonuses.remove(id)
        pillPhysicalDefenseBonuses.remove(id); pillMagicDefenseBonuses.remove(id)
        pillHpBonuses.remove(id); pillMpBonuses.remove(id); pillSpeedBonuses.remove(id)
        pillEffectDurations.remove(id)
        pillCritRateBonuses.remove(id); pillCritEffectBonuses.remove(id)
        pillCultivationSpeedBonuses.remove(id); pillSkillExpSpeedBonuses.remove(id)
        pillNurtureSpeedBonuses.remove(id); activePillCategories.remove(id)
        weaponIds.remove(id); armorIds.remove(id); bootsIds.remove(id); accessoryIds.remove(id)
        weaponNurtures.remove(id); armorNurtures.remove(id)
        bootsNurtures.remove(id); accessoryNurtures.remove(id)
        storageBagItems.remove(id); storageBagSpiritStones.remove(id)
        discipleSpiritStones.remove(id)
        cultivationCompletionMonths.remove(id); cultivationCompletionPhases.remove(id)
        manualCompletionMonths.remove(id); manualCompletionPhases.remove(id)
        equipmentNurturingCompletionMonths.remove(id); equipmentNurturingCompletionPhases.remove(id)
        partnerIds.remove(id); partnerSectIds.remove(id)
        parentId1s.remove(id); parentId2s.remove(id)
        lastChildYears.remove(id); childBirthMonths.remove(id); griefEndYears.remove(id)
        intelligences.remove(id); charms.remove(id); loyalties.remove(id)
        comprehensions.remove(id); artifactRefinings.remove(id); pillRefinings.remove(id)
        spiritPlantings.remove(id); minings.remove(id); teachings.remove(id)
        moralities.remove(id); salaryPaidCounts.remove(id); salaryMissedCounts.remove(id)
        usedFunctionalPillTypes.remove(id); usedExtendLifePillIds.remove(id)
        recruitedMonths.remove(id); hasReviveEffects.remove(id); hasClearAllEffects.remove(id)
        lastTheftMonths.remove(id)
    }

    /** 清空所有组件表 */
    fun clear() {
        synchronized(ids) { ids.clear() }
        names.clear(); surnames.clear(); genders.clear()
        portraitRes.clear(); discipleTypes.clear(); spiritRootTypes.clear()
        slotIds.clear()
        realms.clear(); realmLayers.clear(); cultivations.clear()
        ages.clear(); lifespans.clear(); isAlive.clear(); soulPowers.clear()
        cultivationSpeedBonuses.clear(); cultivationSpeedDurations.clear()
        autoLearnFromWarehouse.clear(); autoEquipFromWarehouse.clear()
        manualIds.clear(); talentIds.clear(); manualMasteries.clear()
        statuses.clear(); statusData.clear()
        baseHps.clear(); baseMps.clear()
        basePhysicalAttacks.clear(); baseMagicAttacks.clear()
        basePhysicalDefenses.clear(); baseMagicDefenses.clear(); baseSpeeds.clear()
        hpVariances.clear(); mpVariances.clear()
        physicalAttackVariances.clear(); magicAttackVariances.clear()
        physicalDefenseVariances.clear(); magicDefenseVariances.clear()
        speedVariances.clear()
        totalCultivations.clear(); breakthroughCounts.clear(); breakthroughFailCounts.clear()
        currentHps.clear(); currentMps.clear()
        pillPhysicalAttackBonuses.clear(); pillMagicAttackBonuses.clear()
        pillPhysicalDefenseBonuses.clear(); pillMagicDefenseBonuses.clear()
        pillHpBonuses.clear(); pillMpBonuses.clear(); pillSpeedBonuses.clear()
        pillEffectDurations.clear()
        pillCritRateBonuses.clear(); pillCritEffectBonuses.clear()
        pillCultivationSpeedBonuses.clear(); pillSkillExpSpeedBonuses.clear()
        pillNurtureSpeedBonuses.clear(); activePillCategories.clear()
        weaponIds.clear(); armorIds.clear(); bootsIds.clear(); accessoryIds.clear()
        weaponNurtures.clear(); armorNurtures.clear()
        bootsNurtures.clear(); accessoryNurtures.clear()
        storageBagItems.clear(); storageBagSpiritStones.clear()
        discipleSpiritStones.clear()
        cultivationCompletionMonths.clear(); cultivationCompletionPhases.clear()
        manualCompletionMonths.clear(); manualCompletionPhases.clear()
        equipmentNurturingCompletionMonths.clear(); equipmentNurturingCompletionPhases.clear()
        partnerIds.clear(); partnerSectIds.clear()
        parentId1s.clear(); parentId2s.clear()
        lastChildYears.clear(); childBirthMonths.clear(); griefEndYears.clear()
        intelligences.clear(); charms.clear(); loyalties.clear()
        comprehensions.clear(); artifactRefinings.clear(); pillRefinings.clear()
        spiritPlantings.clear(); minings.clear(); teachings.clear()
        moralities.clear(); salaryPaidCounts.clear(); salaryMissedCounts.clear()
        usedFunctionalPillTypes.clear(); usedExtendLifePillIds.clear()
        recruitedMonths.clear(); hasReviveEffects.clear(); hasClearAllEffects.clear()
        lastTheftMonths.clear()
    }

    /**
     * 绑定所有子表的 onWrite → markMutated，确保字段级写自动 bump 版本号。
     * deepCopy() 创建的副本不调用此方法——副本写不应影响原表版本号。
     */
    private fun bindAllOnWrite() {
        val cb: () -> Unit = ::markMutated
        // 基础信息
        names.onWrite = cb; surnames.onWrite = cb; genders.onWrite = cb
        portraitRes.onWrite = cb; discipleTypes.onWrite = cb
        spiritRootTypes.onWrite = cb; slotIds.onWrite = cb
        // 境界与修为
        realms.onWrite = cb; realmLayers.onWrite = cb; cultivations.onWrite = cb
        ages.onWrite = cb; lifespans.onWrite = cb; isAlive.onWrite = cb
        soulPowers.onWrite = cb
        // 修炼加速
        cultivationSpeedBonuses.onWrite = cb
        cultivationSpeedDurations.onWrite = cb
        // 自动行为
        autoLearnFromWarehouse.onWrite = cb; autoEquipFromWarehouse.onWrite = cb
        // 列表类型
        manualIds.onWrite = cb; talentIds.onWrite = cb; manualMasteries.onWrite = cb
        // 状态
        statuses.onWrite = cb; statusData.onWrite = cb
        // 战斗属性
        baseHps.onWrite = cb; baseMps.onWrite = cb
        basePhysicalAttacks.onWrite = cb; baseMagicAttacks.onWrite = cb
        basePhysicalDefenses.onWrite = cb; baseMagicDefenses.onWrite = cb
        baseSpeeds.onWrite = cb
        hpVariances.onWrite = cb; mpVariances.onWrite = cb
        physicalAttackVariances.onWrite = cb; magicAttackVariances.onWrite = cb
        physicalDefenseVariances.onWrite = cb; magicDefenseVariances.onWrite = cb
        speedVariances.onWrite = cb
        totalCultivations.onWrite = cb; breakthroughCounts.onWrite = cb
        breakthroughFailCounts.onWrite = cb
        currentHps.onWrite = cb; currentMps.onWrite = cb
        // 丹药效果
        pillPhysicalAttackBonuses.onWrite = cb; pillMagicAttackBonuses.onWrite = cb
        pillPhysicalDefenseBonuses.onWrite = cb; pillMagicDefenseBonuses.onWrite = cb
        pillHpBonuses.onWrite = cb; pillMpBonuses.onWrite = cb
        pillSpeedBonuses.onWrite = cb; pillEffectDurations.onWrite = cb
        pillCritRateBonuses.onWrite = cb; pillCritEffectBonuses.onWrite = cb
        pillCultivationSpeedBonuses.onWrite = cb
        pillSkillExpSpeedBonuses.onWrite = cb
        pillNurtureSpeedBonuses.onWrite = cb; activePillCategories.onWrite = cb
        // 装备
        weaponIds.onWrite = cb; armorIds.onWrite = cb
        bootsIds.onWrite = cb; accessoryIds.onWrite = cb
        weaponNurtures.onWrite = cb; armorNurtures.onWrite = cb
        bootsNurtures.onWrite = cb; accessoryNurtures.onWrite = cb
        storageBagItems.onWrite = cb; storageBagSpiritStones.onWrite = cb
        discipleSpiritStones.onWrite = cb
        cultivationCompletionMonths.onWrite = cb
        cultivationCompletionPhases.onWrite = cb
        manualCompletionMonths.onWrite = cb
        manualCompletionPhases.onWrite = cb
        equipmentNurturingCompletionMonths.onWrite = cb
        equipmentNurturingCompletionPhases.onWrite = cb
        // 社交
        partnerIds.onWrite = cb; partnerSectIds.onWrite = cb
        parentId1s.onWrite = cb; parentId2s.onWrite = cb
        lastChildYears.onWrite = cb; childBirthMonths.onWrite = cb
        griefEndYears.onWrite = cb
        // 技能
        intelligences.onWrite = cb; charms.onWrite = cb; loyalties.onWrite = cb
        comprehensions.onWrite = cb; artifactRefinings.onWrite = cb
        pillRefinings.onWrite = cb; spiritPlantings.onWrite = cb
        minings.onWrite = cb; teachings.onWrite = cb; moralities.onWrite = cb
        salaryPaidCounts.onWrite = cb; salaryMissedCounts.onWrite = cb
        // 使用记录
        usedFunctionalPillTypes.onWrite = cb
        usedExtendLifePillIds.onWrite = cb; recruitedMonths.onWrite = cb
        hasReviveEffects.onWrite = cb; hasClearAllEffects.onWrite = cb
        lastTheftMonths.onWrite = cb
    }

    /**
     * 深拷贝组件表（用于 Shadow 结算）。
     * 直接按表复制，不经过 assemble→insert 的 Disciple 中转。
     * 基本类型表是值拷贝。引用类型表（List/Map/String）浅拷贝。
     */
    fun deepCopy(): DiscipleTables {
        val copy = DiscipleTables()
        copy.ids.addAll(this.ids)

        // Int 表：直接值拷贝
        copyIntTable(this.slotIds, copy.slotIds)
        copyIntTable(this.realms, copy.realms)
        copyIntTable(this.realmLayers, copy.realmLayers)
        copyIntTable(this.ages, copy.ages)
        copyIntTable(this.lifespans, copy.lifespans)
        copyIntTable(this.isAlive, copy.isAlive)
        copyIntTable(this.soulPowers, copy.soulPowers)
        copyIntTable(this.cultivationSpeedDurations, copy.cultivationSpeedDurations)
        copyIntTable(this.autoLearnFromWarehouse, copy.autoLearnFromWarehouse)
        copyIntTable(this.autoEquipFromWarehouse, copy.autoEquipFromWarehouse)
        copyIntTable(this.baseHps, copy.baseHps)
        copyIntTable(this.baseMps, copy.baseMps)
        copyIntTable(this.basePhysicalAttacks, copy.basePhysicalAttacks)
        copyIntTable(this.baseMagicAttacks, copy.baseMagicAttacks)
        copyIntTable(this.basePhysicalDefenses, copy.basePhysicalDefenses)
        copyIntTable(this.baseMagicDefenses, copy.baseMagicDefenses)
        copyIntTable(this.baseSpeeds, copy.baseSpeeds)
        copyIntTable(this.hpVariances, copy.hpVariances)
        copyIntTable(this.mpVariances, copy.mpVariances)
        copyIntTable(this.physicalAttackVariances, copy.physicalAttackVariances)
        copyIntTable(this.magicAttackVariances, copy.magicAttackVariances)
        copyIntTable(this.physicalDefenseVariances, copy.physicalDefenseVariances)
        copyIntTable(this.magicDefenseVariances, copy.magicDefenseVariances)
        copyIntTable(this.speedVariances, copy.speedVariances)
        copyIntTable(this.breakthroughCounts, copy.breakthroughCounts)
        copyIntTable(this.breakthroughFailCounts, copy.breakthroughFailCounts)
        copyIntTable(this.currentHps, copy.currentHps)
        copyIntTable(this.currentMps, copy.currentMps)
        copyIntTable(this.pillPhysicalAttackBonuses, copy.pillPhysicalAttackBonuses)
        copyIntTable(this.pillMagicAttackBonuses, copy.pillMagicAttackBonuses)
        copyIntTable(this.pillPhysicalDefenseBonuses, copy.pillPhysicalDefenseBonuses)
        copyIntTable(this.pillMagicDefenseBonuses, copy.pillMagicDefenseBonuses)
        copyIntTable(this.pillHpBonuses, copy.pillHpBonuses)
        copyIntTable(this.pillMpBonuses, copy.pillMpBonuses)
        copyIntTable(this.pillSpeedBonuses, copy.pillSpeedBonuses)
        copyIntTable(this.pillEffectDurations, copy.pillEffectDurations)
        copyIntTable(this.discipleSpiritStones, copy.discipleSpiritStones)
        copyIntTable(this.cultivationCompletionMonths, copy.cultivationCompletionMonths)
        copyIntTable(this.cultivationCompletionPhases, copy.cultivationCompletionPhases)
        copyIntTable(this.manualCompletionMonths, copy.manualCompletionMonths)
        copyIntTable(this.manualCompletionPhases, copy.manualCompletionPhases)
        copyIntTable(this.equipmentNurturingCompletionMonths, copy.equipmentNurturingCompletionMonths)
        copyIntTable(this.equipmentNurturingCompletionPhases, copy.equipmentNurturingCompletionPhases)
        copyIntTable(this.lastChildYears, copy.lastChildYears)
        copyIntTable(this.intelligences, copy.intelligences)
        copyIntTable(this.charms, copy.charms)
        copyIntTable(this.loyalties, copy.loyalties)
        copyIntTable(this.comprehensions, copy.comprehensions)
        copyIntTable(this.artifactRefinings, copy.artifactRefinings)
        copyIntTable(this.pillRefinings, copy.pillRefinings)
        copyIntTable(this.spiritPlantings, copy.spiritPlantings)
        copyIntTable(this.minings, copy.minings)
        copyIntTable(this.teachings, copy.teachings)
        copyIntTable(this.moralities, copy.moralities)
        copyIntTable(this.salaryPaidCounts, copy.salaryPaidCounts)
        copyIntTable(this.salaryMissedCounts, copy.salaryMissedCounts)
        copyIntTable(this.recruitedMonths, copy.recruitedMonths)
        copyIntTable(this.hasReviveEffects, copy.hasReviveEffects)
        copyIntTable(this.hasClearAllEffects, copy.hasClearAllEffects)
        copyIntTable(this.lastTheftMonths, copy.lastTheftMonths)

        // Double 表
        copyDoubleTable(this.cultivations, copy.cultivations)
        copyDoubleTable(this.cultivationSpeedBonuses, copy.cultivationSpeedBonuses)
        copyDoubleTable(this.pillCritRateBonuses, copy.pillCritRateBonuses)
        copyDoubleTable(this.pillCritEffectBonuses, copy.pillCritEffectBonuses)
        copyDoubleTable(this.pillCultivationSpeedBonuses, copy.pillCultivationSpeedBonuses)
        copyDoubleTable(this.pillSkillExpSpeedBonuses, copy.pillSkillExpSpeedBonuses)
        copyDoubleTable(this.pillNurtureSpeedBonuses, copy.pillNurtureSpeedBonuses)

        // Long 表
        copyRefTable(this.totalCultivations, copy.totalCultivations)
        copyRefTable(this.storageBagSpiritStones, copy.storageBagSpiritStones)

        // String 表（同引用类型）
        copyRefTable(this.names, copy.names)
        copyRefTable(this.surnames, copy.surnames)
        copyRefTable(this.genders, copy.genders)
        copyRefTable(this.portraitRes, copy.portraitRes)
        copyRefTable(this.discipleTypes, copy.discipleTypes)
        copyRefTable(this.spiritRootTypes, copy.spiritRootTypes)
        copyRefTable(this.activePillCategories, copy.activePillCategories)
        copyRefTable(this.weaponIds, copy.weaponIds)
        copyRefTable(this.armorIds, copy.armorIds)
        copyRefTable(this.bootsIds, copy.bootsIds)
        copyRefTable(this.accessoryIds, copy.accessoryIds)

        // 枚举/数据类单值表
        copyRefTable(this.statuses, copy.statuses)
        copyRefTable(this.weaponNurtures, copy.weaponNurtures)
        copyRefTable(this.armorNurtures, copy.armorNurtures)
        copyRefTable(this.bootsNurtures, copy.bootsNurtures)
        copyRefTable(this.accessoryNurtures, copy.accessoryNurtures)

        // List/Map 表：深拷贝防止 Shadow 内突变影响原表
        copyMutableTable(this.manualIds, copy.manualIds) { it.toList() }
        copyMutableTable(this.talentIds, copy.talentIds) { it.toList() }
        copyMutableTable(this.manualMasteries, copy.manualMasteries) { it.toMap() }
        copyMutableTable(this.statusData, copy.statusData) { it.toMap() }
        copyMutableTable(this.storageBagItems, copy.storageBagItems) { it.toList() }
        copyMutableTable(this.usedFunctionalPillTypes, copy.usedFunctionalPillTypes) { it.toList() }
        copyMutableTable(this.usedExtendLifePillIds, copy.usedExtendLifePillIds) { it.toList() }

        // Nullable 表
        copyRefTable(this.partnerIds, copy.partnerIds)
        copyRefTable(this.partnerSectIds, copy.partnerSectIds)
        copyRefTable(this.parentId1s, copy.parentId1s)
        copyRefTable(this.parentId2s, copy.parentId2s)
        copyRefTable(this.childBirthMonths, copy.childBirthMonths)
        copyRefTable(this.griefEndYears, copy.griefEndYears)

        return copy
    }

    private companion object {
        fun copyIntTable(src: IntComponentTable, dst: IntComponentTable) {
            for (i in 0 until src.store.size()) {
                dst.store.put(src.store.keyAt(i), src.store.valueAt(i))
            }
        }

        fun copyDoubleTable(src: DoubleComponentTable, dst: DoubleComponentTable) {
            for (i in 0 until src.store.size()) {
                dst.store.put(src.store.keyAt(i), src.store.valueAt(i))
            }
        }

        fun <T> copyRefTable(src: ComponentTable<T>, dst: ComponentTable<T>) {
            for (i in 0 until src.store.size()) {
                dst.store.put(src.store.keyAt(i), src.store.valueAt(i))
            }
        }

        fun <T> copyMutableTable(
            src: ComponentTable<T>,
            dst: ComponentTable<T>,
            deepCopy: (T) -> T
        ) {
            for (i in 0 until src.store.size()) {
                dst.store.put(src.store.keyAt(i), deepCopy(src.store.valueAt(i)))
            }
        }
    }
}
