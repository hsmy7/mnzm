package com.xianxia.sect.core.state

import android.util.Log
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

    /** 已故弟子的简要死亡记录（用于剔除后保留信息） */
    val deathRecords = mutableListOf<DeathRecord>()

    /** 写操作计数器——GameStateStore 用于脏检测，跳过无变化的 assembleAll */
    @Volatile var mutationVersion: Long = 0
        private set

    /** 在每次写操作后调用，递增版本号 */
    fun markMutated() { mutationVersion++ }

    /**
     * 运行时写保护标志。仅在 stateStore.update{} 事务内为 true。
     * 对标 Android StrictMode：所有写方法在入口检查此标志，
     * 绕过 update{} 的直接写立即抛 IllegalStateException。
     *
     * 警告：不应在 update{} 事务外手动设为 true。所有写方法均检查此标志，
     * 但刻意绕过仍会解除守卫。这不是安全机制，而是开发期契约强制手段。
     */
    @Volatile var writeAllowed: Boolean = false

    /** 写方法入口守卫 */
    private fun requireWriteAccess() {
        if (!writeGuardEnabled) return
        require(writeAllowed) {
            "Direct write to DiscipleTables outside stateStore.update{} " +
            "is forbidden. Use stateStore.update { ... } instead."
        }
    }

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
    val deathYears = IntComponentTable()          // id → deathYear（存活弟子该值为 0 或无条目）
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
    val griefEndYears = IntComponentTable()
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
    companion object {
        private const val TAG = "DiscipleTables"

        /** 用于 [IntComponentTable] griefEndYears 列表示"无哀悼期"的哨兵值 */
        const val GRIEF_YEAR_NULL_SENTINEL = -1

        /** 合法的死亡原因集合 */
        private val VALID_DEATH_CAUSES = setOf("age", "battle", "scout", "exploration", "cave", "unknown")

        /**
         * WriteGuard 开关。
         * - 生产环境始终为 true
         * - 单元测试中设为 false（测试直接操作组件表绕过 stateStore.update{}）
         */
        @Volatile var writeGuardEnabled: Boolean = true

        /**
         * 跨表一致性校验开关。Release 构建建议关闭。
         * 在 GameStateStoreImpl 的 Release 构造函数中设为 false。
         */
        @Volatile var consistencyCheckEnabled: Boolean = true
    }

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
        IntTableRef(deathYears, DiscipleTables::deathYears, "deathYears"),
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
        IntTableRef(griefEndYears, DiscipleTables::griefEndYears, "griefEndYears"),
        RefTableRef(childBirthMonths, DiscipleTables::childBirthMonths, "childBirthMonths"),
        RefTableRef(masterIds, DiscipleTables::masterIds, "masterIds")
    )

    init { bindAllOnWrite() }

    /* ================================================================
     * 核心 API
     * ================================================================ */

    /**
     * 在 synchronized 保护下分配下一个可用弟子 ID。
     * 与 [insert]/[remove] 的 synchronized 使用同一锁对象 [ids]，防止多协程 ID 竞态。
     *
     * 调用方获得 ID 后应立即构建 Disciple 并调用 [insert]，避免窗口期。
     * [insert] 检测到 ID 已存在时会调用内部 [update] 写入全部字段，不会丢失数据。
     *
     * @return 分配的新 ID（int），调用方须自行转换为 String
     */
    @Deprecated(
        message = "使用 allocateAndInsert() 替代，可消除 ID 分配与数据写入之间的悬空窗口。",
        replaceWith = ReplaceWith("allocateAndInsert(disciple)")
    )
    fun allocateNextId(): Int = synchronized(ids) {
        requireWriteAccess()
        val id = (ids.maxOrNull() ?: 0) + 1
        ids.add(id)
        id
    }

    /**
     * 原子分配 ID 并写入全部组件表。
     *
     * 在单个 [synchronized(ids)] 锁内完成 ID 分配 + 组件数据写入，
     * 消灭 [allocateNextId] 与 [insert] 之间的悬空窗口。
     * 无论 [disciple.id] 是什么值，都会被新分配的 ID 覆盖。
     *
     * 对标 Unity DOTS [EntityManager.CreateEntity] / Flecs [world.entity]
     * 的原子实体创建模式，杜绝"分配 ID 后未写入数据"的窗口期。
     *
     * @param disciple 待插入的弟子对象（其 ID 将被覆盖）
     * @return 新分配的 ID（String 格式）
     */
    fun allocateAndInsert(disciple: Disciple): String = synchronized(ids) {
        requireWriteAccess()
        val id = (ids.maxOrNull() ?: 0) + 1
        val idStr = id.toString()
        ids.add(id)
        // copy() 不复制 class body 属性（如 lifeEvents），手动保留
        val d = disciple.copy(id = idStr)
        d.lifeEvents = disciple.lifeEvents
        writeAllFields(d)
        markMutated()
        idStr
    }

    /**
     * 回滚指定 ID 的分配——从 [ids] 和所有组件表中移除该 ID。
     * 在 [allocateNextId] 分配后、[insert] 前放弃时调用，防止悬空 ID。
     *
     * @param id 要回滚的 ID
     * @return true 表示回滚成功，false 表示 ID 不存在
     */
    @Deprecated(
        message = "不再需要——allocateAndInsert() 内部原子完成分配+写入，无需回滚。",
        level = DeprecationLevel.WARNING
    )
    fun rollbackAllocation(id: Int): Boolean = synchronized(ids) {
        requireWriteAccess()
        if (id !in ids) return@synchronized false
        ids.remove(id)
        _allCopyableRefs.forEach { it.remove(id) }
        true
    }

    /**
     * 添加一个新弟子。所有组件表同时插入一行。
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun insert(disciple: Disciple) {
        val id = disciple.id.toInt()
        synchronized(ids) {
            requireWriteAccess()
            if (id in ids) {
                update(disciple)
                return
            }
            ids.add(id)
            writeAllFields(disciple)
        }
        assertAllTablesConsistent()
    }

    /**
     * 更新一个已有弟子的所有组件字段（不修改 ids 列表）。
     * 用于从组装后的 Disciple 对象写回修改。
     */
    fun update(disciple: Disciple) {
        val id = disciple.id.toIntOrNull() ?: return
        synchronized(ids) {
            requireWriteAccess()
            if (!ids.contains(id)) return@synchronized
            writeAllFields(disciple)
        }
    }

    /**
     * 原子全量替换所有弟子数据。
     *
     * 在单个 [synchronized(ids)] 锁内完成五步操作：
     *   1) ids.clear()       — 清空 ID 索引列表
     *   2) 全表 clear()      — 清空所有组件表（通过 _allCopyableRefs 迭代）
     *   3) 全量写入           — 对每个弟子调用 writeAllFields()
     *   4) ids.addAll(...)   — 重建 ID 索引列表
     *   5) markMutated()     — 递增版本号（仅一次）
     *
     * 替代 [clear] + 多次 [insert] 的 N+1 锁裸模式，提供更清晰的批量替换语义。
     * 调用方传入的列表必须已是完整替换集——[replaceAll] 不负责过滤/保留。
     * [deathRecords] 不受此操作影响。
     *
     * @param disciples 替换后的弟子完整列表，所有元素的 ID 必须已分配且唯一
     */
    fun replaceAll(disciples: List<Disciple>) {
        requireWriteAccess()
        synchronized(ids) {
            ids.clear()
            _allCopyableRefs.forEach { it.clear() }
            disciples.forEach { writeAllFields(it) }
            val newIds = disciples.map { it.id.toInt() }
            check(newIds.size == newIds.distinct().size) {
                "replaceAll: 弟子列表包含重复 ID（编程错误），列表大小=${newIds.size}"
            }
            ids.addAll(newIds)
            markMutated()
        }
        assertAllTablesConsistent()
    }

    /** insert/update 共用：将 Disciple 所有字段写入组件表 */
    private fun writeAllFields(disciple: Disciple) {
        val id = disciple.id.toInt()

        // 基础信息
        names[id] = disciple.name; surnames[id] = disciple.surname
        genders[id] = disciple.gender; portraitRes[id] = disciple.portraitRes
        discipleTypes[id] = disciple.discipleType
        spiritRootTypes[id] = disciple.spiritRootType; slotIds[id] = disciple.slotId

        // 境界与修为
        realms[id] = disciple.realm; realmLayers[id] = disciple.realmLayer
        cultivations[id] = disciple.cultivation
        cultivationCheckpoints[id] = disciple.cultivationCheckpoint
        cultivationCheckpointGameMonths[id] = disciple.cultivationCheckpointGameMonth
        ages[id] = disciple.age; lifespans[id] = disciple.lifespan
        isAlive[id] = if (disciple.isAlive) 1 else 0; soulPowers[id] = disciple.soulPower

        // 修炼加速
        cultivationSpeedBonuses[id] = disciple.cultivationSpeedBonus
        cultivationSpeedDurations[id] = disciple.cultivationSpeedDuration

        // 自动行为
        autoLearnFromWarehouse[id] = if (disciple.autoLearnFromWarehouse) 1 else 0
        autoEquipFromWarehouse[id] = if (disciple.equipment.autoEquipFromWarehouse) 1 else 0

        // 列表/映射
        manualIds[id] = disciple.manualIds; talentIds[id] = disciple.talentIds
        lifeEvents[id] = disciple.lifeEvents; manualMasteries[id] = disciple.manualMasteries

        // 状态
        statuses[id] = disciple.status; statusData[id] = disciple.statusData

        // 战斗属性
        val c = disciple.combat
        baseHps[id] = c.baseHp; baseMps[id] = c.baseMp
        basePhysicalAttacks[id] = c.basePhysicalAttack
        baseMagicAttacks[id] = c.baseMagicAttack
        basePhysicalDefenses[id] = c.basePhysicalDefense
        baseMagicDefenses[id] = c.baseMagicDefense; baseSpeeds[id] = c.baseSpeed
        hpVariances[id] = c.hpVariance; mpVariances[id] = c.mpVariance
        physicalAttackVariances[id] = c.physicalAttackVariance
        magicAttackVariances[id] = c.magicAttackVariance
        physicalDefenseVariances[id] = c.physicalDefenseVariance
        magicDefenseVariances[id] = c.magicDefenseVariance
        speedVariances[id] = c.speedVariance; totalCultivations[id] = c.totalCultivation
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
        pillSpeedBonuses[id] = p.pillSpeedBonus; pillEffectDurations[id] = p.pillEffectDuration
        pillCritRateBonuses[id] = p.pillCritRateBonus
        pillCritEffectBonuses[id] = p.pillCritEffectBonus
        pillCultivationSpeedBonuses[id] = p.pillCultivationSpeedBonus
        pillSkillExpSpeedBonuses[id] = p.pillSkillExpSpeedBonus
        pillNurtureSpeedBonuses[id] = p.pillNurtureSpeedBonus
        activePillCategories[id] = p.activePillCategory; activePillTypes[id] = p.activePillTypes

        // 装备
        val e = disciple.equipment
        weaponIds[id] = e.weaponId; armorIds[id] = e.armorId
        bootsIds[id] = e.bootsId; accessoryIds[id] = e.accessoryId
        weaponNurtures[id] = e.weaponNurture; armorNurtures[id] = e.armorNurture
        bootsNurtures[id] = e.bootsNurture; accessoryNurtures[id] = e.accessoryNurture
        storageBagItems[id] = e.storageBagItems; storageBagSpiritStones[id] = e.storageBagSpiritStones
        discipleSpiritStones[id] = e.spiritStones
        cultivationCompletionMonths[id] = disciple.cultivationCompletionMonth
        cultivationCompletionPhases[id] = disciple.cultivationCompletionPhase
        manualCompletionMonths[id] = disciple.manualCompletionMonth
        manualCompletionPhases[id] = disciple.manualCompletionPhase
        equipmentNurturingCompletionMonths[id] = disciple.equipmentNurturingCompletionMonth
        equipmentNurturingCompletionPhases[id] = disciple.equipmentNurturingCompletionPhase

        // 社交
        val s = disciple.social
        partnerIds[id] = s.partnerId; partnerSectIds[id] = s.partnerSectId
        parentId1s[id] = s.parentId1; parentId2s[id] = s.parentId2
        lastChildYears[id] = s.lastChildYear
        childBirthMonths[id] = s.childBirthMonth
        griefEndYears[id] = s.griefEndYear ?: GRIEF_YEAR_NULL_SENTINEL
        masterIds[id] = s.masterId

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
     * 从组件表组装一个完整的 Disciple 对象。
     * 仅在需要"完整弟子视图"时调用：
     *   - UI 渲染（Screen 层）
     *   - 序列化/持久化
     *   - 网络同步
     * 不应在 tick 热路径中调用。
     */
    fun assemble(id: Int): Disciple = Disciple(
        id = id.toString(),
        slotId = slotIds.getOrDefault(id, 0),
        name = names.getOrNull(id) ?: "",
        surname = surnames.getOrNull(id) ?: "",
        realm = realms.getOrDefault(id, 9),
        realmLayer = realmLayers.getOrDefault(id, 1),
        cultivation = cultivations.getOrDefault(id, 0.0),
        cultivationCheckpoint = cultivationCheckpoints.getOrDefault(id, 0.0),
        cultivationCheckpointGameMonth = cultivationCheckpointGameMonths.getOrDefault(id, 0),
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
        combat = assembleCombat(id),
        pillEffects = assemblePillEffects(id),
        equipment = assembleEquipment(id),
        social = assembleSocial(id),
        skills = assembleSkills(id),
        usage = assembleUsage(id)
    ).also { it.lifeEvents = lifeEvents.getOrNull(id) ?: emptyList() }

    private fun assembleCombat(id: Int) = CombatAttributes(
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
    )

    private fun assemblePillEffects(id: Int) = PillEffects(
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
    )

    private fun assembleEquipment(id: Int) = EquipmentSet(
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
    )

    private fun assembleSocial(id: Int) = SocialData(
        partnerId = partnerIds.getOrNull(id),
        partnerSectId = partnerSectIds.getOrNull(id),
        parentId1 = parentId1s.getOrNull(id),
        parentId2 = parentId2s.getOrNull(id),
        lastChildYear = lastChildYears.getOrDefault(id, 0),
        childBirthMonth = childBirthMonths.getOrNull(id),
        griefEndYear = griefEndYears.getOrDefault(id, GRIEF_YEAR_NULL_SENTINEL)
            .takeIf { it != GRIEF_YEAR_NULL_SENTINEL },
        masterId = masterIds.getOrNull(id)
    )

    private fun assembleSkills(id: Int) = SkillStats(
        intelligence = intelligences.getOrDefault(id, 0), charm = charms.getOrDefault(id, 0),
        loyalty = loyalties.getOrDefault(id, 0), comprehension = comprehensions.getOrDefault(id, 0),
        artifactRefining = artifactRefinings.getOrDefault(id, 0),
        pillRefining = pillRefinings.getOrDefault(id, 0),
        spiritPlanting = spiritPlantings.getOrDefault(id, 0),
        mining = minings.getOrDefault(id, 0), teaching = teachings.getOrDefault(id, 0),
        morality = moralities.getOrDefault(id, 0),
        salaryPaidCount = salaryPaidCounts.getOrDefault(id, 0),
        salaryMissedCount = salaryMissedCounts.getOrDefault(id, 0)
    )

    private fun assembleUsage(id: Int) = UsageTracking(
        usedFunctionalPillTypes = usedFunctionalPillTypes.getOrNull(id) ?: emptyList(),
        usedExtendLifePillIds = usedExtendLifePillIds.getOrNull(id) ?: emptyList(),
        usedPermanentPillKeys = usedPermanentPillKeys.getOrNull(id) ?: emptySet(),
        usedExtendLifePillTypes = usedExtendLifePillTypes.getOrNull(id) ?: emptySet(),
        recruitedMonth = recruitedMonths.getOrDefault(id, 0),
        hasReviveEffect = hasReviveEffects.getOrDefault(id, 0) == 1,
        hasClearAllEffect = hasClearAllEffects.getOrDefault(id, 0) == 1,
        lastTheftMonth = lastTheftMonths.getOrDefault(id, 0)
    )

    /** 组装全部弟子的 List<Disciple>（用于序列化、旧 API 兼容）。
     *  含幽灵弟子防御性跳过：ID 在 ids 中但组件表数据缺失 → 跳过并打 Log。
     *  isAlive.contains(id) 校验确保 ID 经过了 writeAllFields 全表写入，
     * 防止仅 names 表有条目的半幽灵逃逸到 UI/存档。 */
    fun assembleAll(): List<Disciple> {
        val result = ids.distinct().mapNotNull { id ->
            try {
                // 全幽灵防御：isAlive 表无条目说明该 ID 未经过 writeAllFields
                if (!isAlive.contains(id)) {
                    Log.w(TAG, "GHOST DISCIPLE (skipped): id=$id, isAlive table missing")
                    return@mapNotNull null
                }
                val d = assemble(id)
                if (d.name.isBlank()) {
                    Log.w(TAG, "GHOST DISCIPLE (skipped): id=${d.id}, " +
                        "age=${d.age}, realm=${d.realm}/${d.realmLayer}, " +
                        "cultivation=${d.cultivation}")
                    null
                } else d
            } catch (e: NoSuchElementException) {
                Log.w(TAG, "GHOST DISCIPLE (skipped): id=$id, error=${e.message}")
                null
            }
        }
        return result
    }

    /**
     * 删除一个弟子。所有组件表同时删除对应行。
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun remove(id: Int) {
        synchronized(ids) {
            requireWriteAccess()
            ids.remove(id)
            _allCopyableRefs.forEach { it.remove(id) }
            assertAllTablesConsistent()
        }
    }

    /** 清空所有组件表 */
    fun clear() {
        requireWriteAccess()
        synchronized(ids) {
            ids.clear()
            _allCopyableRefs.forEach { it.clear() }
        }
    }

    /**
     * 集中标记弟子死亡 —— 设置 isAlive/status/deathYears + 创建 DeathRecord。
     * 所有死亡路径必须调用此方法（或通过 handleDiscipleDeath），禁止手动写三个字段。
     * [cause] 取值："age" / "battle" / "scout" / "exploration" / "cave" / "unknown"
     * 使用方式：
     *   discipleTables.markDead(id, currentYear, "battle")
     *
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun markDead(id: Int, currentYear: Int, cause: String = "unknown") {
        require(cause in VALID_DEATH_CAUSES) { "Invalid death cause: $cause. Valid: $VALID_DEATH_CAUSES" }
        requireWriteAccess()
        synchronized(ids) {
            if (!ids.contains(id)) return@synchronized
            deathRecords.add(DeathRecord(
                id = id,
                name = names.getOrNull(id) ?: "",
                surname = surnames.getOrNull(id) ?: "",
                realm = realms.getOrDefault(id, 9),
                realmLayer = realmLayers.getOrDefault(id, 1),
                deathAge = ages.getOrDefault(id, 0),
                deathYear = currentYear,
                cause = cause
            ))
            isAlive[id] = 0
            statuses[id] = DiscipleStatus.DEAD
            deathYears[id] = currentYear
        }
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
     * 深拷贝组件表（用于 Shadow 结算 / update{} 事务隔离）。
     * 使用 [CopyableTableRef] 迭代完成所有表的复制。
     * 通过 [synchronized(ids)] 保护 ids 快照一致性。
     */
    fun deepCopy(): DiscipleTables {
        val copy = DiscipleTables()
        synchronized(ids) {
            val idsSnapshot = this.ids.toList()
            copy.ids.addAll(idsSnapshot)
            _allCopyableRefs.forEach { it.copyTo(copy) }
        }
        return copy
    }

    /**
     * 修炼检查点 — 将当前修炼值同步到检查点。
     *
     * 在任意影响修炼速率的操作后调用（政策、长老、丹药、突破等），
     * 使后续 [getEffectiveCultivation] 在新速率下正确投影。
     *
     * @param id 弟子 ID
     * @param currentMonth 当前绝对月份（gameYear * 12 + gameMonth）
     */
    fun checkpointDisciple(id: Int, currentMonth: Int) {
        requireWriteAccess()
        if (isAlive[id] != 1) return
        cultivationCheckpoints[id] = cultivations.getOrDefault(id, 0.0)
        cultivationCheckpointGameMonths[id] = currentMonth
    }

    /**
     * 全量弟子检查点 — 对所有存活弟子同步检查点。
     *
     * 在影响全体弟子的速率变化后调用（政策切换、全局丹药等）。
     *
     * @param currentMonth 当前绝对月份（gameYear * 12 + gameMonth）
     */
    fun checkpointAllDisciples(currentMonth: Int) {
        for (id in ids) {
            checkpointDisciple(id, currentMonth)
        }
    }

    /**
     * 修炼投影值：检查点值 + 速率 × 经过月份 × 3。
     * 无检查点时回退到实际修炼值（兼容旧数据/新弟子）。
     *
     * [checkpointDisciple] 已全量接入所有速率变化点，
     * 投影计算在新速率下正确反映从检查点以来的增量。
     */
    fun getEffectiveCultivation(id: Int, currentMonth: Int, rate: Double): Double {
        if (!cultivationCheckpoints.contains(id)) return cultivations.getOrDefault(id, 0.0)
        val checkpoint = cultivationCheckpoints[id]
        val cpMonth = cultivationCheckpointGameMonths.getOrDefault(id, currentMonth)
        if (rate <= 0.0) return checkpoint
        val monthsElapsed = (currentMonth - cpMonth).coerceAtLeast(0)
        if (monthsElapsed <= 0) return checkpoint
        return checkpoint + rate * monthsElapsed * 3.0
    }

    /**
     * 剔除死亡超过 [thresholdYear] 年的弟子，将基本信息保留到 [deathRecords]。
     * 使用 [deathYears] 组件判断死亡时长，无 deathYear 记录的不会剔除。
     * 用于 [DiscipleLifecycleProcessor.processYearlyAging] 年变事件。
     *
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun cullDeadDisciples(thresholdYear: Int) {
        requireWriteAccess()
        val toRemove = synchronized(ids) {
            ids.filter { id ->
                deathYears.contains(id) && deathYears[id] <= thresholdYear
            }.also { filtered ->
                filtered.forEach { id ->
                    deathRecords.add(DeathRecord(
                        id = id,
                        name = names.getOrNull(id) ?: "",
                        surname = surnames.getOrNull(id) ?: "",
                        realm = realms.getOrDefault(id, 9),
                        realmLayer = realmLayers.getOrDefault(id, 1),
                        deathAge = ages.getOrDefault(id, 0),
                        deathYear = deathYears.getOrDefault(id, 0),
                        cause = "unknown"
                    ))
                }
            }
        }
        toRemove.forEach { remove(it) }
    }

    /**
     * Debug 模式: 断言 ids 中所有 id 在每张组件表中都存在。
     * 对标 Bevy UnsafeWorldCell Debug 运行时检查模式。
     * 违反时立即 `check()` 失败，杜绝幽灵弟子逃逸到生产环境。
     */
    private fun assertAllTablesConsistent() {
        if (!consistencyCheckEnabled) return
        synchronized(ids) {
            for (id in ids) {
                _allCopyableRefs.forEach { ref ->
                    // deathYears 是稀疏表——仅已故弟子有条目，存活弟子无写入。
                    // 与 markDead() 的生命周期合约一致，不在此检查范围内。
                    if (ref.debugName == "deathYears") return@forEach
                    check(ref.contains(id)) {
                        "GHOST DISCIPLE: id=$id missing in ${ref.debugName}. " +
                        "Insert/remove/replaceAll did not write to all component tables."
                    }
                }
            }
        }
    }
}

/**
 * 已故弟子的简要死亡记录。
 * 弟子从 [DiscipleTables] 中剔除时创建，保留基本信息供墓碑/统计使用。
 */
data class DeathRecord(
    val id: Int,
    val name: String,
    val surname: String,
    val realm: Int,
    val realmLayer: Int,
    val deathAge: Int,
    val deathYear: Int,
    val cause: String
)
