package com.xianxia.sect.core.state

/**
 * 保存脏集——自上次成功落盘以来累积的变更快照（增量落盘 SS5 的输入形状）。
 *
 * @property upsertIds 表名 → 需要重写（upsert）的实体 id 集；id 均为字符串形态
 *   （弟子表的 Int id 在馈送侧转字符串，与 `Disciple.id` 同形）
 * @property rewriteTables 需要整表重写的派生表名集合（当前仅 recipes：
 *   由 `gameData.unlockedRecipes` 派生，无稳定行级脏信号，引用变化即整表重写）。
 *   行级跟踪表的删除不在此列——增量路径每保存对全部行级表做
 *   「已落盘 id ↔ 快照 id」双向对账，删除集与漏插行由对账兜住
 * @property heavyKeys 变动的 heavy key（`GameHeavyData.ALL_KEYS` 子集）——
 *   heavy 数据无行级稳定键，按 key 粒度整 key 重编码
 *
 * 不持久化（进程内存活）：进程重启后首保走全量基线建立，正确性由全量兜底保证。
 * 不进入任何序列化面（`.sav`/`.bak` 与云载荷保持单 blob 全量）。
 */
data class SaveDirtySet(
    val upsertIds: Map<String, Set<String>> = emptyMap(),
    val rewriteTables: Set<String> = emptySet(),
    val heavyKeys: Set<String> = emptySet()
) {
    /** 零变更快速判据：无行级 upsert、无派生表重写、无 heavy 重编码。 */
    val isEmpty: Boolean
        get() = upsertIds.isEmpty() && rewriteTables.isEmpty() && heavyKeys.isEmpty()

    companion object {
        /** 空脏集单例（有效但零变更——增量路径只落轻量行与域表）。 */
        val EMPTY = SaveDirtySet()
    }
}

/** 行级跟踪的表名（与 applyDirty 变更集的集合名一一对应）。 */
object SaveDirtyTables {
    const val DISCIPLES = "disciples"
    const val EQUIPMENT_INSTANCES = "equipmentInstances"
    const val MANUAL_STACKS = "manualStacks"
    const val MANUAL_INSTANCES = "manualInstances"
    const val PILLS = "pills"
    const val MATERIALS = "materials"
    const val HERBS = "herbs"
    const val SEEDS = "seeds"
    const val STORAGE_BAGS = "storageBags"
    const val BATTLE_LOGS = "battleLogs"
    const val RECIPES = "recipes"

    /** 全部行级跟踪表（不含 recipes——配方由 gameData 派生，走整表重写通道）。 */
    val ENTITY_TABLES: Set<String> = setOf(
        DISCIPLES,
        EQUIPMENT_INSTANCES,
        MANUAL_STACKS,
        MANUAL_INSTANCES,
        PILLS,
        MATERIALS,
        HERBS,
        SEEDS,
        STORAGE_BAGS,
        BATTLE_LOGS
    )
}

/**
 * 保存脏集馈送口——[GameStateStore] 事务提交段在引擎线程上回传本次事务
 * 触达的实体/heavy key。镜像写入（StateSyncService.applyDirty 系）与
 * 本地 Kotlin 写（stateStore.update）都经同一提交段，单一捕获点双源覆盖。
 * 行删除无需馈送：增量路径每保存按「已落盘 id ↔ 快照 id」对账删除集。
 */
interface SaveDirtyRecorder {
    /** 记录行级 upsert 脏 id（表名 + 本事务内容变化的实体 id）。 */
    fun recordEntityUpserts(table: String, ids: Collection<String>)

    /** 记录需要整表重写的派生表（引用变化即整写，当前仅 recipes）。 */
    fun recordTableRewrite(table: String)

    /** 记录变动的 heavy key（整 key 重编码）。 */
    fun recordHeavyKeys(keys: Collection<String>)

    /** 标记下一次保存必须走全量路径（结构变更/读档恢复/溢出等）。 */
    fun recordRequiresFullWrite()

    /** 状态整体替换（读档/重置/新档）后调用：清空累积脏集并要求全量基线重建。 */
    fun resetForStateReplacement()
}

/** 空馈送实现——非注入构造（测试/预注入环境）的安全默认，行为等于不跟踪。 */
object NoopSaveDirtyRecorder : SaveDirtyRecorder {
    override fun recordEntityUpserts(table: String, ids: Collection<String>) = Unit
    override fun recordTableRewrite(table: String) = Unit
    override fun recordHeavyKeys(keys: Collection<String>) = Unit
    override fun recordRequiresFullWrite() = Unit
    override fun resetForStateReplacement() = Unit
}

/**
 * 保存脏集出口——存档快照构建点（引擎线程、状态读取之后）调用，
 * 把累积脏集原子移出为本次保存的 [SaveDirtySet]（move 语义：
 * 移出后再次发生的变更重新累积，与本快照无关，归下一次保存）。
 *
 * 移出与快照读取同在引擎线程串行执行，保证脏集与快照内容严格对应；
 * 保存失败由存储层把脏集并回累积区，不丢失。
 */
interface SaveDirtyDeltaSource {
    /** 取出当前累积脏集（move 语义）；无可取内容时返回 [SaveDirtySet.EMPTY]。 */
    fun captureDeltaForSave(): SaveDirtySet?
}

/** 空出口实现——安全默认：恒返回 null ⇒ 保存链路走全量兜底。 */
object NoopSaveDirtyDeltaSource : SaveDirtyDeltaSource {
    override fun captureDeltaForSave(): SaveDirtySet? = null
}
