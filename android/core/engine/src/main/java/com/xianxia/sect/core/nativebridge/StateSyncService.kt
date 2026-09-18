package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.gameview.GameDataFieldPatch
import com.xianxia.sect.core.gameview.GameViewStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /**
     * 弟子行 typed 投影（R2.3 第二波，[NativeEngineFlag.gameViewProjection] 开时
     * 非空且 `changed["disciples"]` 恒缺）；JSON 回滚臂 / 第一波形态为空表，
     * 弟子行走 `changed["disciples"]` 的 JSON 数组重建。
     */
    val discipleProjections: List<Disciple> = emptyList(),
) {
    /** 是否为空变更集（零写入快速路径）。 */
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
}

/**
 * StateSyncService — C++ 真相源 → Kotlin 镜像同步。
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
 *
 * 反向增量通道已随 w3-13 删除（handover §2.82；ADR reverse-channel-elimination
 * 终局）：AUTHORITATIVE 稳态下 Kotlin 对 C++ 状态**只读**，唯一合法的
 * C++ 状态写入路径是 [importToNative] 全量导入（读档/新档基线与
 * `rebaselineNativeMirror` 的事件后基线重建）。Kotlin 侧确需变更状态的玩法
 * 一律走 native 事务（变更经前向镜像回流）。
 */
@Suppress("TooManyFunctions")  // 镜像同步服务：全量/增量镜像各通道一函数，属同步协议职责边界
@Singleton
class StateSyncService @Inject constructor(
    private val stateStore: GameStateStore,
    /**
     * R2.3 第二波投影态（GameViewStore）。默认值 = 手工构造（测试/非 Hilt 环境）
     * 时的独立实例；生产经 Hilt 注入全局单例，与 UI 消费面读的是同一份投影。
     */
    internal val gameViewStore: GameViewStore = GameViewStore(),
) {

    init {
        // 投影态只被镜像馈送；绑定 store 仅用于注册"非镜像事务"对账钩子
        gameViewStore.attach(stateStore)
    }

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
        val root = try {
            val exported = GameCoreBridge.nativeExportState()
            if (exported.isEmpty()) return false
            // 解析一次复用同一棵树——键集提取与解码共用同一 JSON 树，
            // 不做第二次解析
            json.parseToJsonElement(exported.decodeToString()).jsonObject
        } catch (e: Throwable) {
            // 引擎未初始化/库未加载——双实现并行期正常降级
            return false
        }
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val snapshot = try {
            json.decodeFromJsonElement(NativeGameState.serializer(), root)
        } catch (e: Exception) {
            // 快照 schema 不匹配——解析失败不覆盖 Kotlin 状态
            return false
        }
        val exportedKeys = (root["gameData"] as? JsonObject)?.keys ?: emptySet()
        applySnapshot(snapshot, exportedKeys)
        return true
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
        // 镜像写入走 updateMirror（镜像投影语义）——C++ 产生的变更属真相源
        // 自身推进，非 Kotlin 游戏写入（稳态写纪律由镜像只读契约守卫约束）
        stateStore.updateMirror {
            val carriedAi = snapshot.aiSectDisciples
            val carriedBeastTargets = snapshot.aiSectBeastDirectTargets
            val carriedBeastCooldowns = snapshot.aiSectBeastSkipCooldowns
            val carriedLockedBeasts = snapshot.lockedBeastIds
            gameData = (if (exportedGameDataKeys.isEmpty()) {
                // 全量替换：@Transient 字段不在快照 gameData（不入 kotlinx
                // 序列化）——以事务内当前值回填（顶层字段携带时下方覆盖）
                snapshot.gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples,
                    aiSectBeastDirectTargets = gameData.aiSectBeastDirectTargets,
                    aiSectBeastSkipCooldowns = gameData.aiSectBeastSkipCooldowns,
                    lockedBeastIds = gameData.lockedBeastIds
                )
            } else {
                mergeGameData(gameData, snapshot.gameData, exportedGameDataKeys)
            }).let { base ->
                // @Transient 字段经顶层字段承载——C++ 导出
                // 携带（非 null）才覆盖；未携带保留事务内当前值，镜像永不主动
                // 清空该域
                var merged = base
                if (carriedAi != null) merged = merged.copy(aiSectDisciples = carriedAi)
                if (carriedBeastTargets != null) {
                    merged = merged.copy(aiSectBeastDirectTargets = carriedBeastTargets)
                }
                if (carriedBeastCooldowns != null) {
                    merged = merged.copy(aiSectBeastSkipCooldowns = carriedBeastCooldowns)
                }
                if (carriedLockedBeasts != null) {
                    merged = merged.copy(lockedBeastIds = carriedLockedBeasts)
                }
                merged
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
            reprojectFromSnapshot()
        }
    }

    /**
     * 全量快照臂后重投投影（R2.3 第二波）：低频兜底臂（F3）一次覆盖全部消费块，
     * 与增量臂共用同一 gameData 实例 ⇒ 两臂馈送后投影不可能分叉。
     */
    private fun MutableGameState.reprojectFromSnapshot() {
        if (!NativeEngineFlag.gameViewProjection) return
        gameViewStore.recordMirrorCommit(stateStore.currentTransactionGeneration)
        gameViewStore.reprojectAll(gameData)
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
        val decoded = json.decodeFromJsonElement(GameData.serializer(), merged)
        // @Transient 字段（aiSectDisciples /
        // aiSectBeastDirectTargets / aiSectBeastSkipCooldowns / lockedBeastIds）
        // 永不进 gameData JSON（kotlinx 序列化排除）——解码必然丢失，按"未迁移
        // 字段保留 Kotlin 既有值"语义显式回填，镜像永不因解码丢失清空该域
        return decoded.copy(
            aiSectDisciples = current.aiSectDisciples,
            aiSectBeastDirectTargets = current.aiSectBeastDirectTargets,
            aiSectBeastSkipCooldowns = current.aiSectBeastSkipCooldowns,
            lockedBeastIds = current.lockedBeastIds
        )
    }

    /**
     * 从 GameStateStore 构建 [NativeGameState]（供 nativeImportState 编码）。
     * 调用方在引擎线程、事务外读取原子快照。
     */
    fun buildNativeState(): NativeGameState {
        val snapshot = stateStore.takeAtomicSnapshot()
        return NativeGameState(
            gameData = snapshot.gameData,
            // @Transient 字段经顶层字段显式承载
            //（GameData 侧不入序列化——见 NativeGameState KDoc）
            aiSectDisciples = snapshot.gameData.aiSectDisciples,
            aiSectBeastDirectTargets = snapshot.gameData.aiSectBeastDirectTargets,
            aiSectBeastSkipCooldowns = snapshot.gameData.aiSectBeastSkipCooldowns,
            lockedBeastIds = snapshot.gameData.lockedBeastIds,
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
     * 全量导入：Kotlin 当前状态 → C++（读档/新档基线与事件后基线重建时调用；
     * w3-13 后这是 Kotlin 侧唯一合法的 C++ 状态写入路径——反向增量通道已删除）。
     *
     * @param restoreRng 是否在导入时恢复 RNG 分区（读档/初始基线=true；
     *        基线重建=false——委托模式下 native RNG 即真相源，
     *        镜像 rngStates 可能滞后于残留执行器抽取，恢复会造成回卷漂移）
     * @return true 导入成功
     */
    fun importToNative(restoreRng: Boolean = true): Boolean {
        val state = buildNativeState()
        // 基线建立点 = 投影重投点（读档 / 新档 / 事件后 rebaseline 三处共用）：
        // 换档走 loadFromSnapshot 而非 update 事务，对账钩子不覆盖，必须在此收敛，
        // 否则暂停态读档后 HUD 会停在上一档的头部值。
        if (NativeEngineFlag.gameViewProjection) gameViewStore.reprojectAll(state.gameData)
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
    // 增量镜像（exportDirty 变更集应用）
    // ============================================================

    /**
     * 从 C++ 拉取变更集并增量应用到 GameStateStore。
     *
     * 与 [syncFromNative] 的全量快照+宽松合并不同，本方法只写入 C++ 报告
     * 变化的字段/实体——消除每 tick 全量快照镜像的浪费（现为
     * "C++ 真相源 → Kotlin 只读镜像"的主通道）。
     *
     * @return 应用结果；native 不可用/变更集解析失败返回 null（调用方降级，
     *         可回退 [syncFromNative] 全量同步兜底）
     */
    fun applyDirtyFromNative(): DirtyApplyResult? {
        val raw = fetchNativeDirty() ?: return null
        // 双实现并行契约：变更集解析/应用异常均降级 null（调用方可回退全量同步）。
        // R2.2 传输编码换轨：灰度开 = protobuf 信封解码，关 = 旧 JSON 文本
        //（两分支产出同一棵变更集树，复用同一 applyEnvelope，逐值等价）。
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return if (NativeEngineFlag.mirrorProtobufTransport) {
            runCatching { applyDirtyProto(raw) }.getOrNull()
        } else {
            runCatching { applyDirty(raw.decodeToString()) }.getOrNull()
        }
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

    /**
     * 解码并应用 GameView protobuf 变更集信封（R2.2 换轨后的镜像通道格式）。
     *
     * 经 [GameViewMirrorCodec] 还原为与旧 JSON 协议同形的变更集树，复用
     * [applyEnvelope]——与 [applyDirty]（JSON）共享同一应用逻辑，故两传输格式
     * 在相同 state 输入下逐值等价（DiffDirtyEnvelopeEquivalenceTest 守卫）。
     *
     * @return 应用结果；字节非法（parseFrom 抛错，调用方 runCatching 捕获）
     */
    fun applyDirtyProto(protoBytes: ByteArray): DirtyApplyResult? {
        val view = GameViewMirrorCodec.parse(protoBytes)
        // R2.3 第二波灰度：投影臂下弟子行以 typed 载荷交付（每行 109 节点的 JSON
        // 造树整段退场）；关旗标即回第一波形态（同一棵树、同一 applier）。
        val projection = NativeEngineFlag.gameViewProjection
        val decoded = GameViewMirrorCodec.decodeView(
            view, includeDiscipleJson = !projection, discipleJson = json
        )
        val envelope = DirtyEnvelope(
            decoded.version, decoded.changed, decoded.removed, decoded.discipleProjections
        )
        return when {
            envelope.isEmpty -> DirtyApplyResult(envelope.version, 0, 0, 0)
            else -> applyEnvelope(envelope)
        }
    }

    /** 应用非空变更集（单次 updateMirror 事务原子完成——镜像投影写入）。 */
    private fun applyEnvelope(envelope: DirtyEnvelope): DirtyApplyResult {
        var changedFieldCount = 0
        var upsertCount = 0
        var removedCount = 0
        val carriedGameDataFields = envelope.changed.keys
            .filter { it.startsWith(GAMEDATA_PATH_PREFIX) }
            .mapTo(linkedSetOf()) { it.removePrefix(GAMEDATA_PATH_PREFIX) }
        stateStore.updateMirror {
            changedFieldCount = mergeGameDataChanges(envelope.changed)
            val applied = applyEntityCollections(
                envelope.changed, envelope.removed, envelope.discipleProjections
            )
            upsertCount = applied.first
            removedCount = applied.second
            feedProjection(carriedGameDataFields)
        }
        return DirtyApplyResult(envelope.version, changedFieldCount, upsertCount, removedCount)
    }

    /**
     * 镜像事务内馈送投影（R2.3 第二波，[NativeEngineFlag.gameViewProjection] 灰度）：
     * 与 store 写回同一事务、同一 gameData 实例 ⇒ 投影与全量镜像不可能读到彼此
     * 不同步的中间态；关旗标即不馈送，UI 消费块由 [GameEngine] 转发回退到
     * GameStateStore 全量流（第一波形态）。
     */
    private fun MutableGameState.feedProjection(carriedGameDataFields: Set<String>) {
        if (!NativeEngineFlag.gameViewProjection) return
        gameViewStore.recordMirrorCommit(stateStore.currentTransactionGeneration)
        gameViewStore.project(carriedGameDataFields, gameData)
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
     *
     * 两臂（R2.3 第二波灰度，[NativeEngineFlag.gameViewProjection]）：
     * - 开（生产默认）：[GameDataFieldPatch] 一次浅拷贝 + 变更字段逐个解码；
     * - 关（回滚臂）：整份 GameData JSON 往返（第一波形态，每旬级全量重建）。
     * 两臂逐值等价由 `GameDataFieldPatchEquivalenceTest` 锁定。
     *
     * @return 本封携带的 gameData 变更字段数（与旧路径同：解码失败丢弃仍计数）
     */
    private fun MutableGameState.mergeGameDataChanges(changed: JsonObject): Int {
        val gameDataChanges = changed.filterKeys { it.startsWith(GAMEDATA_PATH_PREFIX) }
        if (gameDataChanges.isEmpty()) return 0
        if (NativeEngineFlag.gameViewProjection) {
            GameDataFieldPatch.apply(
                current = gameData,
                changes = gameDataChanges.map { (path, value) ->
                    path.removePrefix(GAMEDATA_PATH_PREFIX) to value
                },
                json = json
            )?.let { patched -> gameData = patched }
            return gameDataChanges.size
        }
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
                // @Transient 字段（aiSectDisciples /
                // aiSectBeastDirectTargets / aiSectBeastSkipCooldowns / lockedBeastIds）
                // 解码必然丢失——显式回填事务内当前值（dirty 路径同样永不清空）
                .copy(
                    aiSectDisciples = gameData.aiSectDisciples,
                    aiSectBeastDirectTargets = gameData.aiSectBeastDirectTargets,
                    aiSectBeastSkipCooldowns = gameData.aiSectBeastSkipCooldowns,
                    lockedBeastIds = gameData.lockedBeastIds
                )
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
        discipleProjections: List<Disciple> = emptyList(),
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
        // 第二波投影臂：弟子行以 typed 载荷交付（changed 内无 "disciples" 键），
        // 删除通道已在上方按 removed["disciples"] 应用——此处只做行级 upsert，
        // 与旧臂"先删后插、同 id upsert 胜"的结果逐值一致。
        if (discipleProjections.isNotEmpty()) {
            ups += applyDiscipleUpserts(discipleTables, discipleProjections).first
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
     * 弟子表增量应用：信封内 upsert/remove 的 id **行级
     * 精确应用**，未出现弟子零触碰——避免"assembleAll 全量组装 + replaceAll
     * 全表替换"O(N) 路径（弟子任一变化即全表重写）。
     *
     * 语义：先删后插（id 同入两通道时 upsert 胜——remove 后
     * upsertMirrorRow 走 insert 恢复）；既有弟子原位覆盖、新弟子追加末尾
     * （行序 = _ids 序，与 C++ DiscipleStore 保序语义对称）。
     */
    private fun applyDisciples(
        tables: DiscipleTables,
        upserts: JsonArray?,
        removals: JsonArray?,
    ): Pair<Int, Int> {
        var rms = 0
        if (removals != null) {
            for (el in removals) {
                val id = (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: continue
                // isAlive 键存在 = 行存在（SparseArray 索引 O(log n)）；计数与
                // 旧 merged-map 语义对齐（缺失 id 不计）
                if (tables.isAlive.contains(id)) {
                    tables.remove(id)
                    rms++
                }
            }
        }
        var ups = 0
        if (upserts != null) {
            for (el in upserts) {
                val disciple = json.decodeFromJsonElement(Disciple.serializer(), el)
                // 非数字 id = 编程/协议错误——显式失败（事务边界回滚，状态不污染；
                // 与旧 replaceAll 的 toInt 抛出语义一致，DiffDirtyDisciplesTest 守卫）
                require(disciple.id.toIntOrNull() != null) {
                    "镜像弟子 id 非数字: ${disciple.id}"
                }
                tables.upsertMirrorRow(disciple)
                ups++
            }
        }
        return ups to rms
    }

    /**
     * 弟子行 typed 直读 upsert（R2.3 第二波投影臂，
     * [com.xianxia.sect.core.gameview.GameViewDiscipleRows]）：与 JSON 臂同一
     * `upsertMirrorRow` 落表、同一 id 数字性显式失败语义；分歧即
     * `DiscipleRowTypedProjectionTest` 红。
     */
    private fun applyDiscipleUpserts(tables: DiscipleTables, rows: List<Disciple>): Pair<Int, Int> {
        var ups = 0
        for (disciple in rows) {
            require(disciple.id.toIntOrNull() != null) {
                "镜像弟子 id 非数字: ${disciple.id}"
            }
            tables.upsertMirrorRow(disciple)
            ups++
        }
        return ups to 0
    }

    companion object {
        private const val GAMEDATA_PATH_PREFIX = "gameData."
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
