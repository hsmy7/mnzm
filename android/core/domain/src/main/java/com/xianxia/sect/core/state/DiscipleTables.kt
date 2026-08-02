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
    private val _deathRecords = mutableListOf<DeathRecord>()
    val deathRecords: List<DeathRecord> get() = _deathRecords

    /**
     * 写操作计数器——由列级写入回调（bindAllOnWrite 的 dirtyCb）自动递增。
     * 2026-08-01 对抗性审查：显式 markMutated 双计已移除（无生产消费者）。
     */
    @Volatile var mutationVersion: Long = 0
        private set

    // ── ID 列表守卫方法 ──

    /** 添加一个弟子 ID（含守卫检查） */
    fun addId(id: Int) { requireWriteAccess(); _ids.add(id) }

    /** 移除一个弟子 ID（含守卫检查） */
    fun removeId(id: Int) { requireWriteAccess(); _ids.remove(id) }

    /** 添加一个死亡记录（含守卫检查） */
    fun addDeathRecord(record: DeathRecord) { requireWriteAccess(); _deathRecords.add(record) }

    /**
     * 记录指定弟子 ID 的组件数据被修改。
     * 写入组件表的值方法应调用此方法以支持增量组装。
     * 注：通过 [IntComponentTable.set] / [ComponentTable.set] 等列级写入时，
     *     由 onWrite → dirtyTracker.markDirty 负责列级脏标记，
     *     本方法处理弟子级脏标记以支持增量 assemble。
     */
    fun recordChangedId(id: Int) { changedIdTracker.record(id) }

    /**
     * 批量记录多个弟子 ID 被修改。
     * 用于 [replaceAll] / [clear] 等批量操作。
     */
    fun recordChangedIds(ids: Collection<Int>) { changedIdTracker.recordAll(ids) }

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
    // _ids 是普通 mutableListOf（非 CopyOnWriteArrayList）：读侧暴露可变列表的
    // 只读引用，调用方不得持有后跨线程使用；所有写点均以 synchronized(_ids)
    // 互斥（DiscipleTables 不是唯一受影响的表 — insert/remove 操作约 90 张
    // 组件表），多表原子性由同一把锁保证。
    /** 弟子 ID 列表 — 由 synchronized(_ids) 写互斥保护 */
    private val _ids = mutableListOf<Int>()
    val ids: List<Int> get() = _ids

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
    val physiqueIds = ComponentTable<List<String>>()      // id → [physiqueId1, ...]
    val affixIds = ComponentTable<List<String>>()         // id → [affixId1, ...]
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
    val lastTheftJudgementYears = IntComponentTable()  // id → 上次偷盗判定年份（0=从未判定）
    val hasReviveEffects = IntComponentTable()    // 0/1
    val hasClearAllEffects = IntComponentTable()  // 0/1

    // === 弟子总数 ===
    val count: Int get() = ids.size

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
        private val _writeGuardEnabled = ThreadLocal.withInitial { true }
        var writeGuardEnabled: Boolean
            get() = _writeGuardEnabled.get()
            set(value) = _writeGuardEnabled.set(value)

        /**
         * 跨表一致性校验开关。Release 构建建议关闭。
         * 在 GameStateStoreImpl 的 Release 构造函数中设为 false。
         */
        @Volatile var consistencyCheckEnabled: Boolean = true

        /**
         * COW 兜底开关：为 true 时 [deepCopy] 走旧的逐元素全量复制路径。
         * 仅用于重构回归调试，生产环境保持 false。
         */
        @Volatile var forceFullCopy: Boolean = false

        /**
         * Mutable 列值对象防御开关（2026-08-01 浅共享修复配套）。
         *
         * 13 张 List/Map/Set 列改为 O(1) 浅共享后，值对象在源快照与事务缓冲间共享
         * 引用——若未来代码对列返回值做原地修改（绕过 set → 不触发 ensureOwned），
         * 会污染源存储破坏快照隔离。Debug/CI 开启时 [deepCopy] 对 Mutable 列每值
         * 包装 unmodifiable（任何原地修改立即抛 UnsupportedOperationException）；
         * Release 关闭（纯共享零成本）。
         */
        @Volatile var mutableValueGuardEnabled: Boolean = true
    }

    private val _allCopyableRefs: List<CopyableTableRef> = buildCopyableRefs().also { refs ->
        // 为每张组件表分配 DirtyTracker 索引，用于增量 deepCopy
        refs.forEachIndexed { index, ref -> ref.columnIndex = index }
    }

    // ════════════════════════════════════════════════════════════
    // DirtyTracker — 脏标记跟踪系统
    //
    // 每张组件表的 onWrite 回调会自动标记对应列为脏，
    // deepCopy() 时只复制被修改过的列，大幅减少数据复制量。
    //
    // 参考实现：
    // - Unreal Engine TTripleBuffer: 脏标记跳过无数据交换
    // - GDExtensionECS: mark_components_dirty() 触发过滤器重建
    // ════════════════════════════════════════════════════════════
    class DirtyTracker {
        private val dirtyColumns = mutableSetOf<Int>()
        private val lock = Any()

        /** 标记指定列索引为脏 */
        fun markDirty(columnIndex: Int) {
            synchronized(lock) { dirtyColumns.add(columnIndex) }
        }

        /**
         * 消费并清除脏列集合。
         * @return 当前所有脏列的索引集合（空集合表示无变化）
         */
        fun consumeDirtyColumns(): Set<Int> {
            synchronized(lock) {
                val copy = dirtyColumns.toSet()
                dirtyColumns.clear()
                return copy
            }
        }

        /** 当前是否有脏列 */
        val isDirty: Boolean get() = synchronized(lock) { dirtyColumns.isNotEmpty() }
    }

    /** DirtyTracker 实例 — 在 stateStore.update 事务内追踪哪些列被修改 */
    val dirtyTracker = DirtyTracker()

    // ════════════════════════════════════════════════════════════
    // ChangedIdTracker — 增量 assemble 支持
    //
    // 追踪哪些弟子 ID 的组件数据被修改过，用于增量组装。
    // 对标 Bevy ECS change tick 跳过未修改组件的表迭代。
    // ════════════════════════════════════════════════════════════
    class ChangedIdTracker {
        // 2026-08-01：mutableSetOf → java.util.BitSet（每旬 D 次列写热路径零装箱，
        // 单字更新；consume 用 nextSetBit 构造，天然升序供增量归并使用）
        private val changedBits = java.util.BitSet()
        private val lock = Any()

        /**
         * 记录某弟子 ID 被修改。
         * 2026-08-01 对抗性审查修复：BitSet 内存与最大 id 成正比——crafted 存档
         * id=2^30 时 set() 分配 ~128MB 可 OOM。超出安全上限的 id 拒绝记录
         * （增量组装退化为全量兜底，正确性不受影响）。
         */
        fun record(id: Int) {
            synchronized(lock) {
                if (id < 0 || id >= MAX_SAFE_CAPACITY) return
                changedBits.set(id)
            }
        }

        /** 记录多个弟子 ID 被修改（如批量写入场景） */
        fun recordAll(ids: Collection<Int>) { synchronized(lock) { ids.forEach { if (it >= 0 && it < MAX_SAFE_CAPACITY) changedBits.set(it) } } }

        /**
         * 消费并清除已修改的 ID 集合。
         * @return 自上次消费以来被修改过的所有弟子 ID（升序）
         */
        fun consumeChangedIds(): Set<Int> {
            synchronized(lock) {
                if (changedBits.isEmpty) return emptySet()
                val result = LinkedHashSet<Int>()
                var bit = changedBits.nextSetBit(0)
                while (bit >= 0) {
                    result.add(bit)
                    bit = changedBits.nextSetBit(bit + 1)
                }
                changedBits.clear()
                return result
            }
        }
    }

    /** ChangedIdTracker 实例 — 追踪本次事务中哪些弟子被修改 */
    val changedIdTracker = ChangedIdTracker()

    /**
     * P-3 子对象组装组——[assembleAllPatched] 的复用粒度。
     * 每组对应一个 assembleXxx 子对象（lifeEvents 单独一组）。
     */
    internal enum class AssembleGroup { COMBAT, PILL, EQUIPMENT, SOCIAL, SKILLS, USAGE, LIFEEVENTS }

    /**
     * 列索引 → 子对象组（P-3 列级 patch 组装）。
     *
     * 列名从 [buildCopyableRefs] 注册表按名解析为索引；未知列（新列未注册映射）
     * 值为 -1 → [assembleAllPatched] 整体退化全量（正确性优先，绝不复用旧数据）。
     * 映射表从 assembleCombat/assemblePillEffects/assembleEquipment/assembleSocial/
     * assembleSkills/assembleUsage 的读取点逐行推导，新增列必须同步更新。
     */
    private val columnGroupByIndex: IntArray = run {
        val byName: Map<String, AssembleGroup> = mapOf(
            // assembleCombat 读取列
            "baseHps" to AssembleGroup.COMBAT,
            "baseMps" to AssembleGroup.COMBAT,
            "basePhysicalAttacks" to AssembleGroup.COMBAT,
            "baseMagicAttacks" to AssembleGroup.COMBAT,
            "basePhysicalDefenses" to AssembleGroup.COMBAT,
            "baseMagicDefenses" to AssembleGroup.COMBAT,
            "baseSpeeds" to AssembleGroup.COMBAT,
            "hpVariances" to AssembleGroup.COMBAT,
            "mpVariances" to AssembleGroup.COMBAT,
            "physicalAttackVariances" to AssembleGroup.COMBAT,
            "magicAttackVariances" to AssembleGroup.COMBAT,
            "physicalDefenseVariances" to AssembleGroup.COMBAT,
            "magicDefenseVariances" to AssembleGroup.COMBAT,
            "speedVariances" to AssembleGroup.COMBAT,
            "totalCultivations" to AssembleGroup.COMBAT,
            "breakthroughCounts" to AssembleGroup.COMBAT,
            "breakthroughFailCounts" to AssembleGroup.COMBAT,
            "currentHps" to AssembleGroup.COMBAT,
            "currentMps" to AssembleGroup.COMBAT,
            // assemblePillEffects 读取列
            "pillPhysicalAttackBonuses" to AssembleGroup.PILL,
            "pillMagicAttackBonuses" to AssembleGroup.PILL,
            "pillPhysicalDefenseBonuses" to AssembleGroup.PILL,
            "pillMagicDefenseBonuses" to AssembleGroup.PILL,
            "pillHpBonuses" to AssembleGroup.PILL,
            "pillMpBonuses" to AssembleGroup.PILL,
            "pillSpeedBonuses" to AssembleGroup.PILL,
            "pillEffectDurations" to AssembleGroup.PILL,
            "pillCritRateBonuses" to AssembleGroup.PILL,
            "pillCritEffectBonuses" to AssembleGroup.PILL,
            "pillCultivationSpeedBonuses" to AssembleGroup.PILL,
            "pillSkillExpSpeedBonuses" to AssembleGroup.PILL,
            "pillNurtureSpeedBonuses" to AssembleGroup.PILL,
            "activePillCategories" to AssembleGroup.PILL,
            "activePillTypes" to AssembleGroup.PILL,
            // assembleEquipment 读取列
            "weaponIds" to AssembleGroup.EQUIPMENT,
            "armorIds" to AssembleGroup.EQUIPMENT,
            "bootsIds" to AssembleGroup.EQUIPMENT,
            "accessoryIds" to AssembleGroup.EQUIPMENT,
            "weaponNurtures" to AssembleGroup.EQUIPMENT,
            "armorNurtures" to AssembleGroup.EQUIPMENT,
            "bootsNurtures" to AssembleGroup.EQUIPMENT,
            "accessoryNurtures" to AssembleGroup.EQUIPMENT,
            "autoEquipFromWarehouse" to AssembleGroup.EQUIPMENT,
            "storageBagItems" to AssembleGroup.EQUIPMENT,
            "storageBagSpiritStones" to AssembleGroup.EQUIPMENT,
            "discipleSpiritStones" to AssembleGroup.EQUIPMENT,
            // assembleSocial 读取列
            "partnerIds" to AssembleGroup.SOCIAL,
            "partnerSectIds" to AssembleGroup.SOCIAL,
            "parentId1s" to AssembleGroup.SOCIAL,
            "parentId2s" to AssembleGroup.SOCIAL,
            "lastChildYears" to AssembleGroup.SOCIAL,
            "childBirthMonths" to AssembleGroup.SOCIAL,
            "griefEndYears" to AssembleGroup.SOCIAL,
            "masterIds" to AssembleGroup.SOCIAL,
            // assembleSkills 读取列
            "intelligences" to AssembleGroup.SKILLS,
            "charms" to AssembleGroup.SKILLS,
            "loyalties" to AssembleGroup.SKILLS,
            "comprehensions" to AssembleGroup.SKILLS,
            "artifactRefinings" to AssembleGroup.SKILLS,
            "pillRefinings" to AssembleGroup.SKILLS,
            "spiritPlantings" to AssembleGroup.SKILLS,
            "minings" to AssembleGroup.SKILLS,
            "teachings" to AssembleGroup.SKILLS,
            "moralities" to AssembleGroup.SKILLS,
            "salaryPaidCounts" to AssembleGroup.SKILLS,
            "salaryMissedCounts" to AssembleGroup.SKILLS,
            // assembleUsage 读取列
            "usedFunctionalPillTypes" to AssembleGroup.USAGE,
            "usedExtendLifePillIds" to AssembleGroup.USAGE,
            "usedPermanentPillKeys" to AssembleGroup.USAGE,
            "usedExtendLifePillTypes" to AssembleGroup.USAGE,
            "recruitedMonths" to AssembleGroup.USAGE,
            "hasReviveEffects" to AssembleGroup.USAGE,
            "hasClearAllEffects" to AssembleGroup.USAGE,
            // lifeEvents（assemble .also 读取列）
            "lifeEvents" to AssembleGroup.LIFEEVENTS
        )
        IntArray(_allCopyableRefs.size) { index ->
            byName[_allCopyableRefs[index].debugName]?.ordinal ?: -1
        }
    }

    /** P-3 辅助：dirtyGroups 位图是否包含指定组。 */
    private fun Int.hasGroup(group: AssembleGroup): Boolean = (this and (1 shl group.ordinal)) != 0

    /** P-3 测试辅助：列名 → 注册索引（-1 表示列未注册）。 */
    internal fun columnIndexOf(name: String): Int =
        _allCopyableRefs.indexOfFirst { it.debugName == name }

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
        IntTableRef(lastTheftJudgementYears, DiscipleTables::lastTheftJudgementYears, "lastTheftJudgementYears"),
        IntTableRef(hasReviveEffects, DiscipleTables::hasReviveEffects, "hasReviveEffects"),
        IntTableRef(hasClearAllEffects, DiscipleTables::hasClearAllEffects, "hasClearAllEffects"),

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
        MutableTableRef(physiqueIds, DiscipleTables::physiqueIds, "physiqueIds") { it.toList() },
        MutableTableRef(affixIds, DiscipleTables::affixIds, "affixIds") { it.toList() },
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
    fun allocateAndInsert(disciple: Disciple): String = synchronized(_ids) {
        requireWriteAccess()
        val id = (_ids.maxOrNull() ?: 0) + 1
        val idStr = id.toString()
        _ids.add(id)
        // copy() 不复制 class body 属性（如 lifeEvents），手动保留
        val d = disciple.copy(id = idStr)
        d.lifeEvents = disciple.lifeEvents
        writeAllFields(d)
        recordChangedId(id)
        idStr
    }

    /**
     * 添加一个新弟子。所有组件表同时插入一行。
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun insert(disciple: Disciple) {
        val id = disciple.id.toInt()
        synchronized(_ids) {
            requireWriteAccess()
            if (id in _ids) {
                update(disciple)
                return
            }
            _ids.add(id)
            try {
                writeAllFields(disciple)
            } catch (e: Exception) {
                // writeAllFields 中途异常 → 回滚 ids，防止幽灵 ID 残留
                _ids.remove(id)
                throw e
            }
            recordChangedId(id)
        }
        assertAllTablesConsistent()
        if (!consistencyCheckEnabled) {
            val ghostCount = ids.count { !isAlive.contains(it) }
            if (ghostCount > 0) {
                Log.w(TAG, "insert 后检测到 $ghostCount 个幽灵弟子（isAlive 表无记录）")
            }
        }
    }

    /**
     * 更新一个已有弟子的所有组件字段（不修改 ids 列表）。
     * 用于从组装后的 Disciple 对象写回修改。
     */
    fun update(disciple: Disciple) {
        val id = disciple.id.toIntOrNull() ?: return
        synchronized(_ids) {
            requireWriteAccess()
            if (!_ids.contains(id)) return@synchronized
            writeAllFields(disciple)
            recordChangedId(id)
        }
    }

    /**
     * 原子全量替换所有弟子数据。
     *
     * 在单个 [synchronized(ids)] 锁内完成四步操作：
     *   1) ids.clear()       — 清空 ID 索引列表
     *   2) 全表 clear()      — 清空所有组件表（通过 _allCopyableRefs 迭代）
     *   3) 全量写入           — 对每个弟子调用 writeAllFields()
     *   4) ids.addAll(...)   — 重建 ID 索引列表 + recordChangedIds
     *   （mutationVersion 由列写回调自动递增，2026-08-01 移除显式 markMutated）
     *
     * 替代 [clear] + 多次 [insert] 的 N+1 锁裸模式，提供更清晰的批量替换语义。
     * 调用方传入的列表必须已是完整替换集——[replaceAll] 不负责过滤/保留。
     * [deathRecords] 不受此操作影响。
     *
     * @param disciples 替换后的弟子完整列表，所有元素的 ID 必须已分配且唯一
     */
    fun replaceAll(disciples: List<Disciple>) {
        requireWriteAccess()
        // 在清除前保存 deathYears，writeAllFields 不写入此表
        val savedDeathYears = mutableMapOf<Int, Int>()
        synchronized(_ids) {
            for (id in _ids) {
                if (deathYears.contains(id)) {
                    savedDeathYears[id] = deathYears[id]
                }
            }
        }
        synchronized(_ids) {
            _ids.clear()
            _allCopyableRefs.forEach { it.clear() }
            disciples.forEach { writeAllFields(it) }
            val newIds = disciples.map { it.id.toInt() }
            check(newIds.size == newIds.distinct().size) {
                "replaceAll: 弟子列表包含重复 ID（编程错误），列表大小=${newIds.size}"
            }
            _ids.addAll(newIds)
            // 恢复死亡年份（仅对仍在列表中的弟子）
            savedDeathYears.forEach { (id, year) ->
                if (id in ids) deathYears[id] = year
            }
            recordChangedIds(newIds)
        }
        assertAllTablesConsistent()
        if (!consistencyCheckEnabled) {
            // Release 构建：轻量校验，仅日志不抛异常
            val ghostCount = ids.count { !isAlive.contains(it) }
            if (ghostCount > 0) {
                Log.w(TAG, "replaceAll 后检测到 $ghostCount 个幽灵弟子（isAlive 表无记录）")
            }
        }
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
        physiqueIds[id] = disciple.physiqueIds; affixIds[id] = disciple.affixIds
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
    }

    /**
     * 从组件表组装一个完整的 Disciple 对象。
     * 仅在需要"完整弟子视图"时调用：
     *   - UI 渲染（Screen 层）
     *   - 序列化/持久化
     *   - 网络同步
     * 不应在 tick 热路径中调用。
     */
    fun assemble(id: Int): Disciple = assembleCoreFields(id, prev = null, dirtyGroups = 0)

    /**
     * P-3 子对象级 patch 组装：仅重装脏列所属子对象组，未脏组复用 [prev] 引用。
     *
     * 每旬 changedIds ≈ 全量（cultivation 列几乎全部弟子写入）时，原全量
     * [assembleAll] 每弟子 ~100 列读 + 10 个嵌套对象分配。patch 后本体字段
     * （~33 列，含 cultivation）始终重读，6 个子对象 + lifeEvents 仅在对应组
     * 脏时重装——每旬典型（仅 cultivation + HP/MP 变化）可复用全部子对象引用，
     * 消除 ~67 列读与 6 个对象分配/弟子。
     *
     * @param id 弟子 ID
     * @param prev 上一快照中的同 ID 弟子（未脏组复用的引用来源）
     * @param dirtyGroups 脏列所属组位图（[AssembleGroup.ordinal] 位），0=全部未脏
     * @return 组装后的 Disciple
     */
    private fun assembleCoreFields(id: Int, prev: Disciple?, dirtyGroups: Int): Disciple {
        val combat = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.COMBAT))
            assembleCombat(id) else prev.combat
        val pillEffects = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.PILL))
            assemblePillEffects(id) else prev.pillEffects
        val equipment = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.EQUIPMENT))
            assembleEquipment(id) else prev.equipment
        val social = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.SOCIAL))
            assembleSocial(id) else prev.social
        val skills = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.SKILLS))
            assembleSkills(id) else prev.skills
        val usage = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.USAGE))
            assembleUsage(id) else prev.usage
        return Disciple(
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
            physiqueIds = physiqueIds.getOrNull(id) ?: emptyList(),
            affixIds = affixIds.getOrNull(id) ?: emptyList(),
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
            combat = combat,
            pillEffects = pillEffects,
            equipment = equipment,
            social = social,
            skills = skills,
            usage = usage
        ).also {
            it.lifeEvents = if (prev == null || dirtyGroups.hasGroup(AssembleGroup.LIFEEVENTS))
                lifeEvents.getOrNull(id) ?: emptyList()
            else prev.lifeEvents
        }
    }

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
    )

    /** 三表齐全判据：isAlive + names + realms 任一缺失 → 幽灵（ID 未完整写入）。
     *  assembleAll / assembleAllIncremental / deepCopy 三处共用，保证快照 ids、
     *  UI 列表、序列化三条路径的幽灵防御粒度一致。 */
    private fun isCompleteId(id: Int): Boolean =
        isAlive.contains(id) && names.contains(id) && realms.contains(id)

    /** 组装全部弟子的 List<Disciple>（用于序列化、旧 API 兼容）。
     *  含幽灵弟子防御性跳过：ID 在 ids 中但组件表数据缺失 → 跳过并打 Log。
     *  isAlive.contains(id) 校验确保 ID 经过了 writeAllFields 全表写入，
     * 防止仅 names 表有条目的半幽灵逃逸到 UI/存档。 */
    fun assembleAll(): List<Disciple> {
        val result = ids.distinct().mapNotNull { id ->
            try {
                // 全幽灵防御：isAlive + names + realms 任一缺失说明 ID 未完整写入
                if (!isCompleteId(id)) {
                    val reason = when {
                        !isAlive.contains(id) -> "isAlive table missing"
                        !names.contains(id) -> "names table missing"
                        else -> "realms table missing"
                    }
                    Log.w(TAG, "GHOST DISCIPLE (skipped): id=$id, $reason")
                    return@mapNotNull null
                }
                val d = assemble(id)
                // 有意差异：deepCopy 的三表过滤保留空名字弟子（三表齐全，非半幽灵），
                // 空名防御仅在本处 assembleAll 执行——两处组合保证 UI 永不见空名/半幽灵。
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
     * 增量组装：只重新组装 [changedIds] 中的弟子，与 [prevSnapshot] 合并。
     * 对标 Bevy ECS change tick 跳过未修改组件的表迭代。
     *
     * 2026-08-01：`(unchanged + changed).sortedBy`（O(D log D)）改为双指针归并
     * （O(D + C)）——prevSnapshot 按 id 升序（既有不变量）、changedIds 按 BitSet
     * 升序，线性归并且未变弟子复用旧对象引用（UI 侧 data class 相等跳过重组）。
     *
     * @param prevSnapshot 上一次的完整弟子列表（id 升序）
     * @param changedIds 本次事务中修改过的弟子 ID（升序）
     * @return 合并后的完整弟子列表（id 升序）
     */
    fun assembleAllIncremental(prevSnapshot: List<Disciple>, changedIds: Set<Int>): List<Disciple> {
        if (changedIds.isEmpty()) return prevSnapshot

        // 2026-08-01 对抗性审查修复：双指针归并依赖 prevSnapshot 按 id 升序——
        // 读档路径（DiscipleDataDao.getAllSync = ORDER BY realm, cultivation）产出
        // 非升序列表直接赋给 _disciplesFlow，失序归并会产生重复弟子。
        // 入口 O(D) 校验升序，失序时退化为全量组装（正确性优先）。
        var prevSorted = true
        var lastId = -1
        for (d in prevSnapshot) {
            val id = d.id.toIntOrNull()
            if (id == null || id < lastId) { prevSorted = false; break }
            lastId = id
        }
        if (!prevSorted) {
            Log.w(TAG, "assembleAllIncremental: prevSnapshot 非升序（读档路径），退化为全量组装")
            return assembleAll()
        }
        // changedIds 升序迭代（BitSet nextSetBit 天然升序）——组装为 id→Disciple 映射
        val changedMap = HashMap<Int, Disciple>(changedIds.size * 2)
        for (id in changedIds) {
            if (!isCompleteId(id)) {
                Log.w(TAG, "assembleAllIncremental: ghost skipped id=$id")
                continue
            }
            try { changedMap[id] = assemble(id) } catch (e: NoSuchElementException) {
                Log.w(TAG, "assembleAllIncremental: assemble 失败 id=$id（列缺失）", e)
            }
        }
        // 注意：changedMap 为空时不能提前返回——remove 场景 changedIds 含被删弟子
        //（组装必然失败），此时归并仍须从 prevSnapshot 剔除这些 id（防陈尸残留）

        // 已移除/幽灵弟子 id：changedIds 中存在但组装失败的——归并时必须从
        // prevSnapshot 中剔除（否则陈尸残留）
        val removedIds = changedIds.filter { it !in changedMap }.toHashSet()

        // 双指针归并：prevSnapshot（id 升序）∪ changedMap（id 升序）
        val result = ArrayList<Disciple>(prevSnapshot.size + changedMap.size)
        var i = 0
        val prevSize = prevSnapshot.size
        for ((id, disciple) in changedMap.entries.sortedBy { it.key }) {
            // 复制 prevSnapshot 中 id < 当前变更 id 的未变弟子（剔除已移除 id）
            while (i < prevSize) {
                val prevId = prevSnapshot[i].id.toIntOrNull() ?: break
                if (prevId >= id) break
                if (prevId !in removedIds) result.add(prevSnapshot[i])
                i++
            }
            // 跳过 prevSnapshot 中与变更 id 重合的旧条目（id 唯一，最多一个）
            while (i < prevSize) {
                val prevId = prevSnapshot[i].id.toIntOrNull() ?: break
                if (prevId != id) break
                i++
            }
            result.add(disciple)
        }
        // 追加尾部未变弟子（剔除已移除 id）
        while (i < prevSize) {
            val prevId = prevSnapshot[i].id.toIntOrNull()
            if (prevId == null || prevId !in removedIds) result.add(prevSnapshot[i])
            i++
        }
        return result
    }

    /**
     * P-3 子对象级 patch 增量组装：changedIds ≈ 全量时替代 [assembleAll]。
     *
     * 每旬 cultivation 列写几乎所有弟子 → 原全量路径每弟子 ~100 列读 + 10 个
     * 嵌套对象分配。本方法按脏列所属子对象组只重装对应组（未脏组复用
     * [prevSnapshot] 中同 ID 弟子的子对象引用），本体字段（~33 列）始终重读。
     *
     * 安全网：脏列含未注册映射的列（-1 组）时整体退化为全量 [assembleAll]——
     * 新增列未同步映射时绝不复用旧子对象数据（正确性优先）。
     * 失序/幽灵防御与 [assembleAllIncremental] 一致。
     *
     * @param prevSnapshot 上一次的完整弟子列表（id 升序）
     * @param changedIds 本次事务中修改过的弟子 ID（升序）
     * @param dirtyColumnIndices 本次事务脏列索引集合（DirtyTracker 消费结果）
     * @return 合并后的完整弟子列表（id 升序）
     */
    fun assembleAllPatched(
        prevSnapshot: List<Disciple>,
        changedIds: Set<Int>,
        dirtyColumnIndices: Set<Int>
    ): List<Disciple> {
        if (changedIds.isEmpty()) return prevSnapshot

        // 脏列 → 组位图。注意：-1 组 = 本体列（如 cultivations，始终重读），
        // 属正常情况不退化；仅"列索引越界"（新增列未注册）才整体退化全量。
        var dirtyGroups = 0
        var hasUnknownColumn = false
        for (ci in dirtyColumnIndices) {
            if (ci < 0 || ci >= columnGroupByIndex.size) { hasUnknownColumn = true; break }
            val group = columnGroupByIndex[ci]
            if (group >= 0) dirtyGroups = dirtyGroups or (1 shl group)
        }
        if (hasUnknownColumn) {
            Log.w(
                TAG,
                "assembleAllPatched: 脏列含未注册组映射（新增列未同步 columnGroupByIndex），" +
                    "退化为全量组装——请检查 DiscipleTables 的列→组映射表"
            )
            return assembleAll()
        }

        // 升序校验（与 assembleAllIncremental 相同：读档路径可能非升序）
        var prevSorted = true
        var lastId = -1
        for (d in prevSnapshot) {
            val id = d.id.toIntOrNull()
            if (id == null || id < lastId) { prevSorted = false; break }
            lastId = id
        }
        if (!prevSorted) {
            Log.w(TAG, "assembleAllPatched: prevSnapshot 非升序（读档路径），退化为全量组装")
            return assembleAll()
        }

        // prevSnapshot → id 映射（O(D)，patch 复用 prev 子对象引用）
        val prevById = HashMap<Int, Disciple>(prevSnapshot.size * 2)
        for (d in prevSnapshot) {
            d.id.toIntOrNull()?.let { prevById[it] = d }
        }

        val changedMap = HashMap<Int, Disciple>(changedIds.size * 2)
        for (id in changedIds) {
            if (!isCompleteId(id)) {
                Log.w(TAG, "assembleAllPatched: ghost skipped id=$id")
                continue
            }
            try {
                changedMap[id] = assembleCoreFields(id, prevById[id], dirtyGroups)
            } catch (e: NoSuchElementException) {
                Log.w(TAG, "assembleAllPatched: assemble 失败 id=$id（列缺失）", e)
            }
        }
        // 注意：changedMap 为空时不能提前返回——remove 场景 changedIds 含被删弟子
        //（组装必然失败），此时归并仍须从 prevSnapshot 剔除这些 id（防陈尸残留）

        // 已移除/幽灵弟子 id：changedIds 中存在但组装失败的——归并时必须从
        // prevSnapshot 中剔除（否则陈尸残留）
        val removedIds = changedIds.filter { it !in changedMap }.toHashSet()

        // 双指针归并：prevSnapshot（id 升序）∪ changedMap（id 升序）
        val result = ArrayList<Disciple>(prevSnapshot.size + changedMap.size)
        var i = 0
        val prevSize = prevSnapshot.size
        for ((id, disciple) in changedMap.entries.sortedBy { it.key }) {
            // 复制 prevSnapshot 中 id < 当前变更 id 的未变弟子（剔除已移除 id）
            while (i < prevSize) {
                val prevId = prevSnapshot[i].id.toIntOrNull() ?: break
                if (prevId >= id) break
                if (prevId !in removedIds) result.add(prevSnapshot[i])
                i++
            }
            // 跳过 prevSnapshot 中与变更 id 重合的旧条目（id 唯一，最多一个）
            while (i < prevSize) {
                val prevId = prevSnapshot[i].id.toIntOrNull() ?: break
                if (prevId != id) break
                i++
            }
            result.add(disciple)
        }
        // 追加尾部未变弟子（剔除已移除 id）
        while (i < prevSize) {
            val prevId = prevSnapshot[i].id.toIntOrNull()
            if (prevId == null || prevId !in removedIds) result.add(prevSnapshot[i])
            i++
        }
        return result
    }

    /**
     * 删除一个弟子。所有组件表同时删除对应行。
     * 锁层次：synchronized(ids) → ComponentTable.synchronized(lock)
     */
    fun remove(id: Int) {
        synchronized(_ids) {
            requireWriteAccess()
            _ids.remove(id)
            _allCopyableRefs.forEach { it.remove(id) }
            recordChangedId(id)
            assertAllTablesConsistent()
            if (!consistencyCheckEnabled) {
                val ghosts = ids.filter { !isAlive.contains(it) }
                if (ghosts.isNotEmpty()) {
                    Log.w(TAG, "remove 后存在幽灵弟子: ids=$ghosts")
                }
            }
        }
    }

    /** 清空所有组件表与死亡记录 */
    fun clear() {
        requireWriteAccess()
        synchronized(_ids) {
            _ids.clear()
            _deathRecords.clear()
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
        synchronized(_ids) {
            if (!_ids.contains(id)) return@synchronized
            _deathRecords.add(DeathRecord(
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
            // ★ 记录 changedId：markDead 修改了弟子数据，若本事务还包含其他
            // update/insert（产生 changedIds），增量组装必须重排本弟子，
            // 否则快照会保留其"存活"旧数据（陈尸）。
            recordChangedId(id)
        }
    }

    /**
     * 绑定所有子表的 onWrite → markMutated，以及 requireWrite → requireWriteAccess。
     * deepCopy 构造函数同样调用此方法——副本的 requireWrite 指向副本的 requireWriteAccess，
     * 与原始表互不干扰，确保 deepCopy 在 writeAllowed=true 时可写。
     */
    private fun bindAllOnWrite() {
        val guard: () -> Unit = { requireWriteAccess() }
        _allCopyableRefs.forEach { ref ->
            // 脏标记回调：每次写入时同时递增 mutationVersion 并标记对应列为脏
            val dirtyCb: () -> Unit = {
                mutationVersion++
                dirtyTracker.markDirty(ref.columnIndex)
            }
            // 按 id 写入回调（2026-08-01 增量组装基建）：
            // 列级 setter 记录被修改的弟子 ID → changedIdTracker → 增量 assemble 不再全量兜底
            val idCb: (Int) -> Unit = { id -> changedIdTracker.record(id) }
            when (ref) {
                is IntTableRef -> {
                    ref.table.setMutationCallback(dirtyCb)
                    ref.table.setWriteGuard(guard)
                    ref.table.setIdWriteCallback(idCb)
                }
                is DoubleTableRef -> {
                    ref.table.setMutationCallback(dirtyCb)
                    ref.table.setWriteGuard(guard)
                    ref.table.setIdWriteCallback(idCb)
                }
                is RefTableRef<*> -> {
                    ref.table.setMutationCallback(dirtyCb)
                    ref.table.setWriteGuard(guard)
                    ref.table.setIdWriteCallback(idCb)
                }
                is MutableTableRef<*> -> {
                    ref.table.setMutationCallback(dirtyCb)
                    ref.table.setWriteGuard(guard)
                    ref.table.setIdWriteCallback(idCb)
                }
            }
        }
    }

    /**
     * 深拷贝组件表（列级 Copy-on-Write 快照隔离）。
     *
     * 默认路径（[forceFullCopy] = false）：每张组件表 [shareStoreTo] 共享源表存储
     * （O(1) 引用赋值，零数据复制），事务缓冲首次写入某列时自动私有化。
     * 非脏列共享的是引用而非空数组——assembleAll() 在任意快照上都能读到全列数据。
     * 旧快照（UI 持有）引用旧存储，事务永不原地修改源存储，天然隔离。
     *
     * 兜底路径（[forceFullCopy] = true）：逐元素全量复制，与重构前语义逐字一致。
     *
     * @param dirtyColumns 兼容参数（已弃用——COW 每列 O(1) adopt，无需增量复制）。
     *   由 [dirtyTracker.consumeDirtyColumns] 收集，仅用于 DirtyTracker 维护。
     */
    fun deepCopy(dirtyColumns: Set<Int>? = null): DiscipleTables {
        val copy = DiscipleTables()
        copy.writeAllowed = true
        synchronized(_ids) {
            val idsSnapshot = this._ids.toList()
            if (forceFullCopy) {
                // 兜底路径：逐元素全量复制
                _allCopyableRefs.forEach { it.copyTo(copy) }
            } else {
                // COW 路径：共享存储引用，首次写入时自动私有化
                // （Mutable 列的 unmodifiable 包装已在 MutableTableRef.shareStoreTo 内完成）
                _allCopyableRefs.forEach { it.shareStoreTo(copy) }
            }
            // 只保留组件表中有完整数据的 ID，过滤掉幽灵 ID（Bug 产生的残留）
            // 三表判据与 assembleAll/assembleAllIncremental 一致（isCompleteId）
            copy._ids.addAll(idsSnapshot.filter { copy.isCompleteId(it) })
        }
        // 显式复制死亡记录，防止跨 update 边界丢失
        copy._deathRecords.addAll(this._deathRecords)
        copy.writeAllowed = false  // ★ 复制完成后重置守卫，由调用方（update{}）的 .apply { writeAllowed = true } 再次开启
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
        val toRemove = synchronized(_ids) {
            _ids.filter { id ->
                deathYears.contains(id) && deathYears[id] <= thresholdYear
            }.also { filtered ->
                filtered.forEach { id ->
                    _deathRecords.add(DeathRecord(
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
        synchronized(_ids) {
            for (id in _ids) {
                _allCopyableRefs.forEach { ref ->
                    // deathYears 是稀疏表——仅已故弟子有条目，存活弟子无写入。
                    // 与 markDead() 的生命周期合约一致，不在此检查范围内。
                    if (ref.debugName == "deathYears" || ref.debugName == "lastTheftJudgementYears") return@forEach
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
