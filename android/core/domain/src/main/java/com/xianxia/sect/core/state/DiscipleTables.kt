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
    val cultivationCheckpoints = DoubleComponentTable()  // id → checkpoint cultivation
    val cultivationCheckpointGameMonths = IntComponentTable()  // id → checkpoint gameMonth

    // === 自动行为 ===
    val autoLearnFromWarehouse = IntComponentTable()   // id → 0/1
    val autoEquipFromWarehouse = IntComponentTable()   // id → 0/1

    // === 列表类型（ComponentTable<List<T>>） ===
    val manualIds = ComponentTable<List<String>>()        // id → [manualId1, ...]
    val talentIds = ComponentTable<List<String>>()        // id → [talentId1, ...]
    val lifeEvents = ComponentTable<List<String>>()       // id → ["11岁：加入宗门", ...]
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
    val activePillTypes = ComponentTable<Set<String>>()

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
    val masterIds = ComponentTable<String?>()        // 师父弟子ID（师徒关系）

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
    val usedPermanentPillKeys = ComponentTable<Set<String>>()
    val usedExtendLifePillTypes = ComponentTable<Set<String>>()
    val recruitedMonths = IntComponentTable()
    val hasReviveEffects = IntComponentTable()    // 0/1
    val hasClearAllEffects = IntComponentTable()  // 0/1
    val lastTheftMonths = IntComponentTable()

    // === 弟子总数 ===
    val count: Int get() = ids.size

    /**
     * 从另一个 [DiscipleTables] 中复制一个弟子的全部组件到当前表。
     * 如果 id 已存在则更新，否则插入。
     * 用于 [GameStateStoreImpl.mergeDiscipleTables] 的简化合并。
     */
    fun copyRowFrom(source: DiscipleTables, id: Int) {
        val disciple = source.assemble(id)
        if (id in ids) update(disciple) else insert(disciple)
    }

    // ================================================================
    // 迭代式 CRUD 支持（所有组件表的统一引用列表）
    // ================================================================

    /** 所有组件表的统一引用列表，用于 [remove]/[clear]/[bindAllOnWrite]/[deepCopy] 的迭代操作 */
    private val _allCopyableRefs: List<CopyableTableRef> = buildCopyableRefs()

    @Suppress("LongMethod")
    private fun buildCopyableRefs(): List<CopyableTableRef> = listOf(
        // ── Int 表（值拷贝） ──
        IntTableRef(slotIds, DiscipleTables::slotIds, "slotIds"),
        IntTableRef(realms, DiscipleTables::realms, "realms"),
        IntTableRef(realmLayers, DiscipleTables::realmLayers, "realmLayers"),
        IntTableRef(ages, DiscipleTables::ages, "ages"),
        IntTableRef(lifespans, DiscipleTables::lifespans, "lifespans"),
        IntTableRef(isAlive, DiscipleTables::isAlive, "isAlive"),
        IntTableRef(soulPowers, DiscipleTables::soulPowers, "soulPowers"),
        IntTableRef(cultivationSpeedDurations, DiscipleTables::cultivationSpeedDurations, "cultivationSpeedDurations"),
        IntTableRef(autoLearnFromWarehouse, DiscipleTables::autoLearnFromWarehouse, "autoLearnFromWarehouse"),
        IntTableRef(autoEquipFromWarehouse, DiscipleTables::autoEquipFromWarehouse, "autoEquipFromWarehouse"),
        IntTableRef(baseHps, DiscipleTables::baseHps, "baseHps"),
        IntTableRef(baseMps, DiscipleTables::baseMps, "baseMps"),
        IntTableRef(basePhysicalAttacks, DiscipleTables::basePhysicalAttacks, "basePhysicalAttacks"),
        IntTableRef(baseMagicAttacks, DiscipleTables::baseMagicAttacks, "baseMagicAttacks"),
        IntTableRef(basePhysicalDefenses, DiscipleTables::basePhysicalDefenses, "basePhysicalDefenses"),
        IntTableRef(baseMagicDefenses, DiscipleTables::baseMagicDefenses, "baseMagicDefenses"),
        IntTableRef(baseSpeeds, DiscipleTables::baseSpeeds, "baseSpeeds"),
        IntTableRef(hpVariances, DiscipleTables::hpVariances, "hpVariances"),
        IntTableRef(mpVariances, DiscipleTables::mpVariances, "mpVariances"),
        IntTableRef(physicalAttackVariances, DiscipleTables::physicalAttackVariances, "physicalAttackVariances"),
        IntTableRef(magicAttackVariances, DiscipleTables::magicAttackVariances, "magicAttackVariances"),
        IntTableRef(physicalDefenseVariances, DiscipleTables::physicalDefenseVariances, "physicalDefenseVariances"),
        IntTableRef(magicDefenseVariances, DiscipleTables::magicDefenseVariances, "magicDefenseVariances"),
        IntTableRef(speedVariances, DiscipleTables::speedVariances, "speedVariances"),
        IntTableRef(breakthroughCounts, DiscipleTables::breakthroughCounts, "breakthroughCounts"),
        IntTableRef(breakthroughFailCounts, DiscipleTables::breakthroughFailCounts, "breakthroughFailCounts"),
        IntTableRef(currentHps, DiscipleTables::currentHps, "currentHps"),
        IntTableRef(currentMps, DiscipleTables::currentMps, "currentMps"),
        IntTableRef(pillPhysicalAttackBonuses, DiscipleTables::pillPhysicalAttackBonuses, "pillPhysicalAttackBonuses"),
        IntTableRef(pillMagicAttackBonuses, DiscipleTables::pillMagicAttackBonuses, "pillMagicAttackBonuses"),
        IntTableRef(pillPhysicalDefenseBonuses, DiscipleTables::pillPhysicalDefenseBonuses, "pillPhysicalDefenseBonuses"),
        IntTableRef(pillMagicDefenseBonuses, DiscipleTables::pillMagicDefenseBonuses, "pillMagicDefenseBonuses"),
        IntTableRef(pillHpBonuses, DiscipleTables::pillHpBonuses, "pillHpBonuses"),
        IntTableRef(pillMpBonuses, DiscipleTables::pillMpBonuses, "pillMpBonuses"),
        IntTableRef(pillSpeedBonuses, DiscipleTables::pillSpeedBonuses, "pillSpeedBonuses"),
        IntTableRef(pillEffectDurations, DiscipleTables::pillEffectDurations, "pillEffectDurations"),
        IntTableRef(discipleSpiritStones, DiscipleTables::discipleSpiritStones, "discipleSpiritStones"),
        IntTableRef(cultivationCompletionMonths, DiscipleTables::cultivationCompletionMonths, "cultivationCompletionMonths"),
        IntTableRef(cultivationCompletionPhases, DiscipleTables::cultivationCompletionPhases, "cultivationCompletionPhases"),
        IntTableRef(manualCompletionMonths, DiscipleTables::manualCompletionMonths, "manualCompletionMonths"),
        IntTableRef(manualCompletionPhases, DiscipleTables::manualCompletionPhases, "manualCompletionPhases"),
        IntTableRef(equipmentNurturingCompletionMonths, DiscipleTables::equipmentNurturingCompletionMonths, "equipmentNurturingCompletionMonths"),
        IntTableRef(equipmentNurturingCompletionPhases, DiscipleTables::equipmentNurturingCompletionPhases, "equipmentNurturingCompletionPhases"),
        IntTableRef(lastChildYears, DiscipleTables::lastChildYears, "lastChildYears"),
        IntTableRef(intelligences, DiscipleTables::intelligences, "intelligences"),
        IntTableRef(charms, DiscipleTables::charms, "charms"),
        IntTableRef(loyalties, DiscipleTables::loyalties, "loyalties"),
        IntTableRef(comprehensions, DiscipleTables::comprehensions, "comprehensions"),
        IntTableRef(artifactRefinings, DiscipleTables::artifactRefinings, "artifactRefinings"),
        IntTableRef(pillRefinings, DiscipleTables::pillRefinings, "pillRefinings"),
        IntTableRef(spiritPlantings, DiscipleTables::spiritPlantings, "spiritPlantings"),
        IntTableRef(minings, DiscipleTables::minings, "minings"),
        IntTableRef(teachings, DiscipleTables::teachings, "teachings"),
        IntTableRef(moralities, DiscipleTables::moralities, "moralities"),
        IntTableRef(salaryPaidCounts, DiscipleTables::salaryPaidCounts, "salaryPaidCounts"),
        IntTableRef(salaryMissedCounts, DiscipleTables::salaryMissedCounts, "salaryMissedCounts"),
        IntTableRef(recruitedMonths, DiscipleTables::recruitedMonths, "recruitedMonths"),
        IntTableRef(hasReviveEffects, DiscipleTables::hasReviveEffects, "hasReviveEffects"),
        IntTableRef(hasClearAllEffects, DiscipleTables::hasClearAllEffects, "hasClearAllEffects"),
        IntTableRef(lastTheftMonths, DiscipleTables::lastTheftMonths, "lastTheftMonths"),

        // ── Double 表（值拷贝） ──
        DoubleTableRef(cultivations, DiscipleTables::cultivations, "cultivations"),
        DoubleTableRef(cultivationCheckpoints, DiscipleTables::cultivationCheckpoints, "cultivationCheckpoints"),
        IntTableRef(cultivationCheckpointGameMonths, DiscipleTables::cultivationCheckpointGameMonths, "cultivationCheckpointGameMonths"),
        DoubleTableRef(cultivationSpeedBonuses, DiscipleTables::cultivationSpeedBonuses, "cultivationSpeedBonuses"),
        DoubleTableRef(pillCritRateBonuses, DiscipleTables::pillCritRateBonuses, "pillCritRateBonuses"),
        DoubleTableRef(pillCritEffectBonuses, DiscipleTables::pillCritEffectBonuses, "pillCritEffectBonuses"),
        DoubleTableRef(pillCultivationSpeedBonuses, DiscipleTables::pillCultivationSpeedBonuses, "pillCultivationSpeedBonuses"),
        DoubleTableRef(pillSkillExpSpeedBonuses, DiscipleTables::pillSkillExpSpeedBonuses, "pillSkillExpSpeedBonuses"),
        DoubleTableRef(pillNurtureSpeedBonuses, DiscipleTables::pillNurtureSpeedBonuses, "pillNurtureSpeedBonuses"),

        // ── Long 表（值不可变，浅拷贝安全） ──
        RefTableRef(totalCultivations, DiscipleTables::totalCultivations, "totalCultivations"),
        RefTableRef(storageBagSpiritStones, DiscipleTables::storageBagSpiritStones, "storageBagSpiritStones"),

        // ── String 表（引用不可变，浅拷贝安全） ──
        RefTableRef(names, DiscipleTables::names, "names"),
        RefTableRef(surnames, DiscipleTables::surnames, "surnames"),
        RefTableRef(genders, DiscipleTables::genders, "genders"),
        RefTableRef(portraitRes, DiscipleTables::portraitRes, "portraitRes"),
        RefTableRef(discipleTypes, DiscipleTables::discipleTypes, "discipleTypes"),
        RefTableRef(spiritRootTypes, DiscipleTables::spiritRootTypes, "spiritRootTypes"),
        RefTableRef(activePillCategories, DiscipleTables::activePillCategories, "activePillCategories"),
        RefTableRef(weaponIds, DiscipleTables::weaponIds, "weaponIds"),
        RefTableRef(armorIds, DiscipleTables::armorIds, "armorIds"),
        RefTableRef(bootsIds, DiscipleTables::bootsIds, "bootsIds"),
        RefTableRef(accessoryIds, DiscipleTables::accessoryIds, "accessoryIds"),

        // ── Set 表（需深拷贝 toSet） ──
        MutableTableRef(activePillTypes, DiscipleTables::activePillTypes, "activePillTypes") { it.toSet() },
        MutableTableRef(usedPermanentPillKeys, DiscipleTables::usedPermanentPillKeys, "usedPermanentPillKeys") { it.toSet() },
        MutableTableRef(usedExtendLifePillTypes, DiscipleTables::usedExtendLifePillTypes, "usedExtendLifePillTypes") { it.toSet() },

        // ── List 表（需深拷贝 toList） ──
        MutableTableRef(manualIds, DiscipleTables::manualIds, "manualIds") { it.toList() },
        MutableTableRef(talentIds, DiscipleTables::talentIds, "talentIds") { it.toList() },
        MutableTableRef(lifeEvents, DiscipleTables::lifeEvents, "lifeEvents") { it.toList() },
        MutableTableRef(storageBagItems, DiscipleTables::storageBagItems, "storageBagItems") { it.toList() },
        MutableTableRef(usedFunctionalPillTypes, DiscipleTables::usedFunctionalPillTypes, "usedFunctionalPillTypes") { it.toList() },
        MutableTableRef(usedExtendLifePillIds, DiscipleTables::usedExtendLifePillIds, "usedExtendLifePillIds") { it.toList() },

        // ── Map 表（需深拷贝 toMap） ──
        MutableTableRef(manualMasteries, DiscipleTables::manualMasteries, "manualMasteries") { it.toMap() },
        MutableTableRef(statusData, DiscipleTables::statusData, "statusData") { it.toMap() },

        // ── 枚举/数据类单值表（值不可变，浅拷贝安全） ──
        RefTableRef(statuses, DiscipleTables::statuses, "statuses"),
        RefTableRef(weaponNurtures, DiscipleTables::weaponNurtures, "weaponNurtures"),
        RefTableRef(armorNurtures, DiscipleTables::armorNurtures, "armorNurtures"),
        RefTableRef(bootsNurtures, DiscipleTables::bootsNurtures, "bootsNurtures"),
        RefTableRef(accessoryNurtures, DiscipleTables::accessoryNurtures, "accessoryNurtures"),

        // ── Nullable 值表（值不可变，浅拷贝安全） ──
        RefTableRef(partnerIds, DiscipleTables::partnerIds, "partnerIds"),
        RefTableRef(partnerSectIds, DiscipleTables::partnerSectIds, "partnerSectIds"),
        RefTableRef(parentId1s, DiscipleTables::parentId1s, "parentId1s"),
        RefTableRef(parentId2s, DiscipleTables::parentId2s, "parentId2s"),
        RefTableRef(childBirthMonths, DiscipleTables::childBirthMonths, "childBirthMonths"),
        RefTableRef(griefEndYears, DiscipleTables::griefEndYears, "griefEndYears"),
        RefTableRef(masterIds, DiscipleTables::masterIds, "masterIds")
    )

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
        lifeEvents[id] = disciple.lifeEvents
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
        activePillTypes[id] = p.activePillTypes

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
        s.masterId?.let { masterIds[id] = it }

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
        usedPermanentPillKeys[id] = u.usedPermanentPillKeys
        usedExtendLifePillTypes[id] = u.usedExtendLifePillTypes
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
        lifeEvents[id] = disciple.lifeEvents
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
        activePillTypes[id] = p.activePillTypes

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
        masterIds[id] = s.masterId

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
        usedPermanentPillKeys[id] = u.usedPermanentPillKeys
        usedExtendLifePillTypes[id] = u.usedExtendLifePillTypes
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
                activePillCategory = activePillCategories.getOrNull(id) ?: "",
                activePillTypes = activePillTypes.getOrNull(id) ?: emptySet()
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
                griefEndYear = griefEndYears.getOrNull(id),
                masterId = masterIds.getOrNull(id)
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
                usedPermanentPillKeys = usedPermanentPillKeys.getOrNull(id) ?: emptySet(),
                usedExtendLifePillTypes = usedExtendLifePillTypes.getOrNull(id) ?: emptySet(),
                recruitedMonth = recruitedMonths.getOrDefault(id, 0),
                hasReviveEffect = hasReviveEffects.getOrDefault(id, 0) == 1,
                hasClearAllEffect = hasClearAllEffects.getOrDefault(id, 0) == 1,
                lastTheftMonth = lastTheftMonths.getOrDefault(id, 0)
            )
        ).also { it.lifeEvents = lifeEvents.getOrNull(id) ?: emptyList() }
    }

    /** 组装全部弟子的 List<Disciple>（用于序列化、旧 API 兼容）。 */
    fun assembleAll(): List<Disciple> = ids.distinct().map { assemble(it) }

    /**
     * 删除一个弟子。所有组件表同时删除对应行。
     */
    fun remove(id: Int) {
        synchronized(ids) { ids.remove(id) }
        _allCopyableRefs.forEach { it.remove(id) }
    }

    /** 清空所有组件表 */
    fun clear() {
        synchronized(ids) { ids.clear() }
        _allCopyableRefs.forEach { it.clear() }
    }

    /**
     * 绑定所有子表的 onWrite → markMutated，确保字段级写自动 bump 版本号。
     * deepCopy() 创建的副本不调用此方法——副本写不应影响原表版本号。
     */
    private fun bindAllOnWrite() {
        val cb: () -> Unit = ::markMutated
        _allCopyableRefs.forEach { ref ->
            when (ref) {
                is IntTableRef -> ref.table.onWrite = cb
                is DoubleTableRef -> ref.table.onWrite = cb
                is RefTableRef<*> -> ref.table.onWrite = cb
                is MutableTableRef<*> -> ref.table.onWrite = cb
            }
        }
    }

    /**
     * 深拷贝组件表（用于 Shadow 结算）。
     * 使用 [CopyableTableRef] 迭代完成所有表的复制。
     */
    fun deepCopy(): DiscipleTables {
        val copy = DiscipleTables()
        copy.ids.addAll(this.ids)
        _allCopyableRefs.forEach { it.copyTo(copy) }
        return copy
    }

    /** 获取有效修炼值：检查点值 + 速率 × 经过月份 × 3。
     *  无检查点时回退到实际修炼值（兼容旧数据/新弟子）。 */
    fun getEffectiveCultivation(id: Int, currentMonth: Int, rate: Double): Double {
        if (!cultivationCheckpoints.contains(id)) return cultivations.getOrDefault(id, 0.0)
        val checkpoint = cultivationCheckpoints[id]
        val cpMonth = cultivationCheckpointGameMonths.getOrDefault(id, currentMonth)
        if (rate <= 0.0) return checkpoint
        val monthsElapsed = (currentMonth - cpMonth).coerceAtLeast(0)
        if (monthsElapsed <= 0) return checkpoint
        return checkpoint + rate * monthsElapsed * 3.0
    }
}
