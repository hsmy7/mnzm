package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 变更集应用结果（[StateSyncService.applyDirty] 返回）。
 *
 * @property version C++ 变更集版本号（每次导出单调递增）
 * @property changedFieldCount 应用的 gameData 字段数
 * @property upsertCount 应用的新增/更新实体数
 * @property removedCount 应用的删除实体数
 */
data class DirtyApplyResult(
    val version: Long,
    val changedFieldCount: Int,
    val upsertCount: Int,
    val removedCount: Int,
)

/**
 * 变更集信封（[StateSyncService.applyDirty] 内部解析产物）。
 *
 * @property version 协议版本号（C++ 侧每次导出单调递增）
 * @property changed 变更路径 → 新值（"gameData.x" 字段 / 集合名 → 实体数组）
 * @property removed 集合名 → 待删 id 数组
 */
private data class DirtyEnvelope(
    val version: Long,
    val changed: JsonObject,
    val removed: JsonObject,
) {
    /** 是否为空变更集（零写入快速路径）。 */
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
}

/**
 * StateSyncService — C++ 真相源 → Kotlin 镜像同步（Kotlin→C++ 迁移批次 9）。
 *
 * 职责：把 C++ GameCore 的全量状态快照（JSON）解码为 [NativeGameState] 并
 * 镜像写入 [GameStateStore]（单次 update 事务原子完成），使 UI/存档链路
 * （StateFlow + Room）零改动消费 C++ 引擎产出。
 *
 * 架构定位（docs/adr/cpp-engine-migration.md Decision 5）：
 *   C++ GameCore 为真相源 → exportStateJson → 本服务镜像 → GameStateStore
 *   → Room 存档链路（存档兼容 100% 由 Kotlin 层保证）。
 *
 * 宽松合并语义（与 C++ json_codec 宽松 from_json 对齐）：
 *   C++ 快照只含**已迁移字段**；镜像时未出现在导出 JSON 中的 gameData
 *   字段保留 Kotlin 侧既有值（[mergeGameData] 字段级合并），空实体列表
 *   不覆盖 Kotlin 既有列表——双实现并行期未迁移字段永不丢失。
 *
 * 线程契约：在引擎线程（GameEngineCore 单线程调度器）调用；镜像写入
 * 经 stateStore.update 事务（与 Kotlin 引擎写路径同一锁语义）。
 *
 * 使用时机：
 *   - 读档后：Kotlin 从 Room 读状态 → nativeImportState → C++（真相源就绪）
 *   - tick 后/execute 后：C++ 状态已变 → 本服务 export+镜像 → UI 可见
 *   - 存档前：C++ export → 现有存档链路编码（格式不变）
 */
@Suppress("TooManyFunctions")  // 镜像同步服务：正/反两向各通道各一函数（+反向 4 个），属同步协议职责边界
@Singleton
class StateSyncService @Inject constructor(
    private val stateStore: GameStateStore,
    /**
     * 反向增量发送器（计划 v2 阶段 3）：默认走 [GameCoreBridge.nativeApplyReverseDirty]；
     * 对拍测试注入桌面通道（DiffRngBridge.nativeCoreApplyReverseDirty）。
     */
    private val reverseSender: (ByteArray) -> Boolean = { GameCoreBridge.nativeApplyReverseDirty(it) },
) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 从 C++ 导出全量快照并镜像写入 GameStateStore（字段级宽松合并）。
     *
     * @return true 同步成功；false 引擎未初始化/快照解析失败（调用方保留
     *         Kotlin 侧状态继续运行——镜像失败不应崩溃，符合双实现并行契约）
     */
    @Suppress("ReturnCount")  // 多 return 为降级契约（native 异常/空快照/解析失败逐级返回 false）
    fun syncFromNative(): Boolean {
        // 双实现并行契约：native 任何异常/解析失败均降级 false，Kotlin 侧状态照常
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val exported = try {
            GameCoreBridge.nativeExportState()
        } catch (e: Throwable) {
            // 引擎未初始化/库未加载——双实现并行期正常降级
            return false
        }
        if (exported.isEmpty()) return false
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val snapshot = try {
            json.decodeFromString(NativeGameState.serializer(), exported.decodeToString())
        } catch (e: Exception) {
            // 快照 schema 不匹配——解析失败不覆盖 Kotlin 状态
            return false
        }
        applySnapshot(snapshot, exportedGameDataKeys(exported))
        return true
    }

    /** 导出 JSON 中 gameData 对象的字段键集合（C++ 已迁移字段白名单）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun exportedGameDataKeys(exported: ByteArray): Set<String> {
        return try {
            val root = json.parseToJsonElement(exported.decodeToString()) as? JsonObject ?: return emptySet()
            (root["gameData"] as? JsonObject)?.keys ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * 将 [NativeGameState] 快照镜像写入 GameStateStore（单事务原子）。
     *
     * @param exportedGameDataKeys C++ 导出 JSON 中 gameData 的字段键集合；
     *       空集合表示"全量替换"（测试/读档全量恢复场景）。
     * 未覆盖的嵌套字段（C++ 未迁移部分）保留 Kotlin 侧既有值——宽松合并，
     * 与 json_codec 宽松 from_json 语义对齐。
     */
    fun applySnapshot(snapshot: NativeGameState, exportedGameDataKeys: Set<String> = emptySet()) {
        stateStore.update {
            gameData = if (exportedGameDataKeys.isEmpty()) {
                snapshot.gameData
            } else {
                mergeGameData(gameData, snapshot.gameData, exportedGameDataKeys)
            }
            if (snapshot.disciples.isNotEmpty()) {
                discipleTables.replaceAll(snapshot.disciples)
            }
            equipmentStacks.replaceAll(snapshot.equipmentStacks)
            equipmentInstances.replaceAll(snapshot.equipmentInstances)
            manualStacks.replaceAll(snapshot.manualStacks)
            manualInstances.replaceAll(snapshot.manualInstances)
            pills.replaceAll(snapshot.pills)
            materials.replaceAll(snapshot.materials)
            herbs.replaceAll(snapshot.herbs)
            seeds.replaceAll(snapshot.seeds)
            storageBags.replaceAll(snapshot.storageBags)
        }
    }

    /**
     * 字段级宽松合并：C++ 已迁移字段（[exportedKeys] 白名单）覆盖 Kotlin 值，
     * 其余字段保留 Kotlin 既有值——镜像同步永不丢未迁移字段。
     */
    internal fun mergeGameData(
        current: GameData,
        snapshot: GameData,
        exportedKeys: Set<String>
    ): GameData {
        if (exportedKeys.isEmpty()) return snapshot
        val currentJson = json.encodeToJsonElement(GameData.serializer(), current) as JsonObject
        val snapshotJson = json.encodeToJsonElement(GameData.serializer(), snapshot) as JsonObject
        val merged = buildJsonObject {
            // 先写 Kotlin 既有值（全字段），再按白名单覆盖 C++ 导出值
            currentJson.forEach { (k, v) -> put(k, v) }
            snapshotJson.forEach { (k, v) ->
                if (k in exportedKeys) put(k, v)
            }
        }
        return json.decodeFromJsonElement(GameData.serializer(), merged)
    }

    /**
     * 从 GameStateStore 构建 [NativeGameState]（供 nativeImportState 编码）。
     * 调用方在引擎线程、事务外读取原子快照。
     */
    fun buildNativeState(): NativeGameState {
        val snapshot = stateStore.takeAtomicSnapshot()
        return NativeGameState(
            gameData = snapshot.gameData,
            disciples = snapshot.disciples,
            equipmentStacks = snapshot.equipmentStacks,
            equipmentInstances = snapshot.equipmentInstances,
            manualStacks = snapshot.manualStacks,
            manualInstances = snapshot.manualInstances,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            storageBags = snapshot.storageBags
        )
    }

    /**
     * 全量导入：Kotlin 当前状态 → C++（读档/切换真相源时调用）。
     *
     * @param restoreRng 是否在导入时恢复 RNG 分区（读档/初始基线=true；
     *        AUTHORITATIVE 每旬回导=false——委托模式下 native RNG 即真相源，
     *        镜像 rngStates 可能滞后于残留执行器抽取，恢复会造成回卷漂移）
     * @return true 导入成功
     */
    fun importToNative(restoreRng: Boolean = true): Boolean {
        val state = buildNativeState()
        val encoded = json.encodeToString(NativeGameState.serializer(), state)
        // 双实现并行契约：native 不可用降级 false（不崩溃，Kotlin 引擎照常）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            if (restoreRng) {
                GameCoreBridge.nativeImportState(encoded.encodeToByteArray())
            } else {
                GameCoreBridge.nativeImportStateNoRng(encoded.encodeToByteArray())
            }
        } catch (e: Throwable) {
            false
        }
    }

    // ============================================================
    // 反向增量回导（计划 v2 阶段 3：取代 AUTHORITATIVE 每旬全量 importToNative）
    // ============================================================

    /** Kotlin → C++ 反向增量回导版本号（单调递增；C++ 侧校验严格递增防乱序） */
    private var reverseVersion = 0L

    /**
     * 反向增量回导：把 AUTHORITATIVE 残留窗口内 Kotlin 侧的状态变化增量发给 C++。
     *
     * 数据来源 [GameStateStore.consumeReverseDirty]（事务级捕获累积，见
     * GameStateStoreImpl.captureReverseDirty）。协议与 forward 对称：
     * ```
     * { "version": N,
     *   "changed": {
     *     "gameData": { ...全量 gameData，不含 rngStates... },
     *     "disciples": [ {全实体}... ],
     *     "equipmentStacks": [ {全实体}... ]   // 窗口内引用变化的集合
     *   },
     *   "removed": { "disciples": ["id"...], "equipmentStacks": ["id"...] } }
     * ```
     * 空窗口（无捕获）零发送返回 true；容量拒绝（超大 id 未记录）时弟子侧无法
     * 精确增量 → 整体降级全量回导。native 不可用/失败返回 false（调用方降级
     * [importToNative] 全量兜底，与 forward 的 applyDirty→syncFromNative 对称）。
     */
    fun applyDirtyToNative(): Boolean {
        val snapshot = stateStore.consumeReverseDirty()
        return when {
            // 空窗口零发送
            snapshot == null -> true
            // 容量拒绝（超大 id 未记录）→ 弟子侧无法精确增量，整体全量回导兜底
            snapshot.rejectedRecord -> importToNative(restoreRng = false)
            else -> sendReverseEnvelope(snapshot)
        }
    }

    /** 构建反向信封并发送（版本单调递增；native 失败返回 false 由调用方降级全量）。 */
    private fun sendReverseEnvelope(snapshot: GameStateStore.ReverseDirtySnapshot): Boolean {
        val envelope = buildReverseEnvelope(
            snapshot = snapshot,
            tables = stateStore.discipleTables,
            gameData = stateStore.gameData.value
        )
        val encoded = envelope.toString().encodeToByteArray()
        // 双实现并行契约：native 失败降级 false（调用方回退全量）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            val ok = reverseSender(encoded)
            if (ok) {
                reverseVersion++
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 构建反向信封（version 单调递增；无任何通道内容时返回空 changed/removed）。 */
    internal fun buildReverseEnvelope(
        snapshot: GameStateStore.ReverseDirtySnapshot,
        tables: DiscipleTables,
        gameData: GameData,
    ): JsonObject {
        val changed = mutableMapOf<String, JsonElement>()
        val removed = mutableMapOf<String, JsonElement>()

        // gameData：整对象引用变化 → 全量发送（排除 rngStates——native RNG 真相源）
        if (snapshot.gameDataChanged) {
            changed["gameData"] = gameDataJsonWithoutRng(gameData)
        }

        // 弟子：窗口内变化 id → 当前表组装全实体（upsert，id 升序）；
        // 组装失败（已移除/幽灵）→ removed
        val upserted = tables.assembleAllIncremental(emptyList(), snapshot.discipleIds)
        val upsertedIds = upserted.mapTo(HashSet()) { it.id.toIntOrNull() ?: Int.MIN_VALUE }
        val removedDiscipleIds = snapshot.discipleIds - upsertedIds
        if (upserted.isNotEmpty()) {
            changed["disciples"] = JsonArray(
                upserted.map { json.encodeToJsonElement(Disciple.serializer(), it) }
            )
        }
        if (removedDiscipleIds.isNotEmpty()) {
            removed["disciples"] = JsonArray(removedDiscipleIds.map { JsonPrimitive(it.toString()) })
        }

        // 实体集合：引用变化的集合 → 全量实体 upsert + 消失 id
        for ((name, capture) in snapshot.collections) {
            if (capture.upserts.isNotEmpty()) {
                changed[name] = encodeCollectionEntities(name, capture.upserts)
            }
            if (capture.removedIds.isNotEmpty()) {
                removed[name] = JsonArray(capture.removedIds.map { JsonPrimitive(it) })
            }
        }

        return buildJsonObject {
            put("version", reverseVersion + 1)
            put("changed", buildJsonObject { changed.forEach { (k, v) -> put(k, v) } })
            put("removed", buildJsonObject { removed.forEach { (k, v) -> put(k, v) } })
        }
    }

    /** gameData 全量 JSON（剔除 rngStates 键——委托模式下 native RNG 即真相源）。 */
    private fun gameDataJsonWithoutRng(gameData: GameData): JsonElement {
        val full = json.encodeToJsonElement(GameData.serializer(), gameData).jsonObject
        return buildJsonObject {
            full.forEach { (k, v) -> if (k != RNG_STATES_FIELD) put(k, v) }
        }
    }

    /** 集合实体按具体类型序列化（与 applyCollection 同名分发）。 */
    private fun encodeCollectionEntities(name: String, upserts: List<HasId>): JsonArray = buildJsonArray {
        for (entity in upserts) {
            val element: JsonElement? = when (name) {
                COLLECTION_EQUIPMENT_STACKS ->
                    json.encodeToJsonElement(EquipmentStack.serializer(), entity as EquipmentStack)
                COLLECTION_EQUIPMENT_INSTANCES ->
                    json.encodeToJsonElement(EquipmentInstance.serializer(), entity as EquipmentInstance)
                COLLECTION_MANUAL_STACKS ->
                    json.encodeToJsonElement(ManualStack.serializer(), entity as ManualStack)
                COLLECTION_MANUAL_INSTANCES ->
                    json.encodeToJsonElement(ManualInstance.serializer(), entity as ManualInstance)
                COLLECTION_PILLS -> json.encodeToJsonElement(Pill.serializer(), entity as Pill)
                COLLECTION_MATERIALS -> json.encodeToJsonElement(Material.serializer(), entity as Material)
                COLLECTION_HERBS -> json.encodeToJsonElement(Herb.serializer(), entity as Herb)
                COLLECTION_SEEDS -> json.encodeToJsonElement(Seed.serializer(), entity as Seed)
                COLLECTION_STORAGE_BAGS -> json.encodeToJsonElement(StorageBag.serializer(), entity as StorageBag)
                else -> null
            }
            if (element != null) add(element)
        }
    }

    // ============================================================
    // 增量镜像（计划 v2 阶段 1：exportDirty 变更集应用）
    // ============================================================

    /**
     * 从 C++ 拉取变更集并增量应用到 GameStateStore。
     *
     * 与 [syncFromNative] 的全量快照+宽松合并不同，本方法只写入 C++ 报告
     * 变化的字段/实体——消除每 tick 全量快照镜像的浪费（阶段 2 起为
     * "C++ 真相源 → Kotlin 只读镜像"的主通道）。
     *
     * @return 应用结果；native 不可用/变更集解析失败返回 null（调用方降级，
     *         可回退 [syncFromNative] 全量同步兜底）
     */
    fun applyDirtyFromNative(): DirtyApplyResult? {
        val raw = fetchNativeDirty() ?: return null
        // 双实现并行契约：变更集解析/应用异常均降级 null（调用方可回退全量同步）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return runCatching { applyDirty(raw.decodeToString()) }.getOrNull()
    }

    /**
     * 解析并应用变更集 JSON（协议见 C++ `state::DirtyTracker`）：
     * ```
     * { "version": N,
     *   "changed": { "gameData.<field>": <值>, "<集合名>": [<实体>...] },
     *   "removed": { "<集合名>": ["<id>"...] } }
     * ```
     * 单次 update 事务原子完成；未知路径宽松忽略（前向兼容）。
     *
     * @return 应用结果；JSON 非法/结构不符返回 null（不触碰 Kotlin 状态）
     */
    fun applyDirty(dirtyJson: String): DirtyApplyResult? {
        val envelope = parseDirtyEnvelope(dirtyJson) ?: return null
        return when {
            // 空变更集：零写入（不触发事务与 StateFlow 发射——镜像空闲期零开销）
            envelope.isEmpty -> DirtyApplyResult(envelope.version, 0, 0, 0)
            else -> applyEnvelope(envelope)
        }
    }

    /** 应用非空变更集（单次 update 事务原子完成）。 */
    private fun applyEnvelope(envelope: DirtyEnvelope): DirtyApplyResult {
        var changedFieldCount = 0
        var upsertCount = 0
        var removedCount = 0
        stateStore.update {
            changedFieldCount = mergeGameDataChanges(envelope.changed)
            val applied = applyEntityCollections(envelope.changed, envelope.removed)
            upsertCount = applied.first
            removedCount = applied.second
        }
        return DirtyApplyResult(envelope.version, changedFieldCount, upsertCount, removedCount)
    }

    /** 拉取 native 变更集字节（native 不可用/空响应 → null）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fetchNativeDirty(): ByteArray? = try {
        val raw = GameCoreBridge.nativeExportDirty()
        if (raw.isEmpty()) null else raw
    } catch (e: Throwable) {
        null
    }

    /**
     * 解析变更集信封（宽松读取 + 协议校验）：
     * changed/removed 若存在必须为对象（结构违例 → null，不触碰状态）；
     * 缺失视为旧协议/空变更集。
     */
    private fun parseDirtyEnvelope(dirtyJson: String): DirtyEnvelope? {
        val root = parseJsonObjectOrNull(dirtyJson)
            ?.takeIf(::hasValidEnvelopeObjects) ?: return null
        return DirtyEnvelope(
            version = (root["version"] as? JsonPrimitive)?.longOrNull ?: 0L,
            changed = root["changed"] as? JsonObject ?: JsonObject(emptyMap()),
            removed = root["removed"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** JSON 文本 → JsonObject（非法输入 → null）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseJsonObjectOrNull(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }

    /** 协议校验：changed/removed 字段若存在必须为对象。 */
    private fun hasValidEnvelopeObjects(root: JsonObject): Boolean =
        isObjectOrNil(root["changed"]) && isObjectOrNil(root["removed"])

    private fun isObjectOrNil(element: kotlinx.serialization.json.JsonElement?): Boolean =
        element == null || element is JsonObject

    /**
     * gameData 字段级覆盖（未变化字段零改动，单事务内合并）。
     * @return 应用的字段数
     */
    private fun MutableGameState.mergeGameDataChanges(changed: JsonObject): Int {
        val gameDataChanges = changed.filterKeys { it.startsWith(GAMEDATA_PATH_PREFIX) }
        if (gameDataChanges.isEmpty()) return 0
        val currentJson =
            json.encodeToJsonElement(GameData.serializer(), gameData).jsonObject
        val overrides =
            gameDataChanges.mapKeys { it.key.removePrefix(GAMEDATA_PATH_PREFIX) }
        val merged = buildJsonObject {
            currentJson.forEach { (k, v) -> put(k, v) }
            overrides.forEach { (k, v) -> put(k, v) }
        }
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        gameData = try {
            json.decodeFromJsonElement(GameData.serializer(), merged)
        } catch (e: Exception) {
            // 变更值与 schema 不符（版本漂移防御）——保留 Kotlin 现状
            gameData
        }
        return gameDataChanges.size
    }

    /** 实体集合按 id upsert/remove（返回 upsert/removed 计数对）。 */
    private fun MutableGameState.applyEntityCollections(
        changed: JsonObject,
        removed: JsonObject,
    ): Pair<Int, Int> {
        var ups = 0
        var rms = 0
        for ((name, upsertsEl) in changed) {
            if (name.startsWith(GAMEDATA_PATH_PREFIX)) continue
            val upserts = runCatching { upsertsEl.jsonArray }.getOrNull()
            val removals = removed[name]?.let { runCatching { it.jsonArray }.getOrNull() }
            val applied = applyCollection(name, upserts, removals)
            ups += applied.first
            rms += applied.second
        }
        // 仅删除、无新增的集合
        for ((name, removalsEl) in removed) {
            if (!name.startsWith(GAMEDATA_PATH_PREFIX) && name !in changed) {
                val removals = runCatching { removalsEl.jsonArray }.getOrNull()
                rms += applyCollection(name, null, removals).second
            }
        }
        return ups to rms
    }

    /** 变更集实体集合分发（集合名 → 对应存储；返回 upsert/removed 计数对）。 */
    private fun MutableGameState.applyCollection(
        name: String,
        upserts: JsonArray?,
        removals: JsonArray?,
    ): Pair<Int, Int> = when (name) {
        COLLECTION_DISCIPLES -> applyDisciples(discipleTables, upserts, removals)
        COLLECTION_EQUIPMENT_STACKS -> applyToStore(equipmentStacks, upserts, removals)
        COLLECTION_EQUIPMENT_INSTANCES -> applyToStore(equipmentInstances, upserts, removals)
        COLLECTION_MANUAL_STACKS -> applyToStore(manualStacks, upserts, removals)
        COLLECTION_MANUAL_INSTANCES -> applyToStore(manualInstances, upserts, removals)
        COLLECTION_PILLS -> applyToStore(pills, upserts, removals)
        COLLECTION_MATERIALS -> applyToStore(materials, upserts, removals)
        COLLECTION_HERBS -> applyToStore(herbs, upserts, removals)
        COLLECTION_SEEDS -> applyToStore(seeds, upserts, removals)
        COLLECTION_STORAGE_BAGS -> applyToStore(storageBags, upserts, removals)
        else -> Pair(0, 0)  // 未知集合：宽松忽略（前向兼容）
    }

    /**
     * 实体存储增量应用：先删后插（id 相同即更新语义）。
     * 返回 upsert/removed 计数对。
     */
    private inline fun <reified T : HasId> applyToStore(
        store: EntityStore<T>,
        upserts: JsonArray?,
        removals: JsonArray?,
    ): Pair<Int, Int> {
        var ups = 0
        var rms = 0
        if (removals != null) {
            for (el in removals) {
                val id = (el as? JsonPrimitive)?.contentOrNull ?: continue
                store.remove(id)
                rms++
            }
        }
        if (upserts != null) {
            for (el in upserts) {
                val entity = json.decodeFromJsonElement(serializer<T>(), el)
                if (store.contains(entity.id)) {
                    store.update(entity.id) { entity }
                } else {
                    store.add(entity)
                }
                ups++
            }
        }
        return ups to rms
    }

    /**
     * 弟子表增量应用：DiscipleTables 为组件表存储，经 assembleAll 合并后
     * replaceAll 回写（仅 disciples 集合实际变化时触发；列级增量随阶段 3
     * 数据导向存储落地，见 docs/cpp-engine.md 计划 v2）。
     */
    private fun applyDisciples(
        tables: DiscipleTables,
        upserts: JsonArray?,
        removals: JsonArray?,
    ): Pair<Int, Int> {
        var ups = 0
        var rms = 0
        val merged = tables.assembleAll().associateBy { it.id }.toMutableMap()
        if (removals != null) {
            for (el in removals) {
                val id = (el as? JsonPrimitive)?.contentOrNull ?: continue
                if (merged.remove(id) != null) rms++
            }
        }
        if (upserts != null) {
            for (el in upserts) {
                val disciple = json.decodeFromJsonElement(Disciple.serializer(), el)
                merged[disciple.id] = disciple
                ups++
            }
        }
        tables.replaceAll(merged.values.toList())
        return ups to rms
    }

    companion object {
        private const val GAMEDATA_PATH_PREFIX = "gameData."
        /** gameData 中 RNG 分区状态字段——反向回导必须剔除（native RNG 真相源） */
        private const val RNG_STATES_FIELD = "rngStates"
        private const val COLLECTION_DISCIPLES = "disciples"
        private const val COLLECTION_EQUIPMENT_STACKS = "equipmentStacks"
        private const val COLLECTION_EQUIPMENT_INSTANCES = "equipmentInstances"
        private const val COLLECTION_MANUAL_STACKS = "manualStacks"
        private const val COLLECTION_MANUAL_INSTANCES = "manualInstances"
        private const val COLLECTION_PILLS = "pills"
        private const val COLLECTION_MATERIALS = "materials"
        private const val COLLECTION_HERBS = "herbs"
        private const val COLLECTION_SEEDS = "seeds"
        private const val COLLECTION_STORAGE_BAGS = "storageBags"
    }
}
