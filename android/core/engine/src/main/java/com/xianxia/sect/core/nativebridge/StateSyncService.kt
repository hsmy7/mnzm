package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.BuildConfig
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
import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.util.DomainLog
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
 */
@Suppress("TooManyFunctions")  // 镜像同步服务：正/反两向各通道各一函数（+反向 4 个），属同步协议职责边界
@Singleton
class StateSyncService @Inject constructor(
    private val stateStore: GameStateStore,
    /**
     * 反向增量发送器：默认走 [GameCoreBridge.nativeApplyReverseDirty]；
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
        // 反向锚点推进——全量镜像后 C++ 已知导出 gameData 全部键值。
        // 锚点从解码后快照**重新经 kotlinx 编码**（而非直接缓存 C++ 原始树），
        // 保证与反向 diff 侧（kotlinx 编码的当前值）数值格式同源——C++ 导出对
        // 整数值 double 输出整数形式（12000），kotlinx 输出 12000.0，混入锚点
        // 会造成格式性假差异
        anchorReverseGameData(snapshot.gameData)
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
        // 镜像写入走 updateMirror（不参与反向捕获）——C++ 产生的
        // 变更无需回导，不污染玩家操作捕获窗口（镜像若参与反向捕获，
        // 玩家放置/消耗捕获会被误清 → Kotlin 侧灵石扣除等变更永不同步 C++ 真相源）
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
        }
        // 全量镜像后 C++ 已知全部 gameData 键值——刷新关闭字段基线
        // （快照值与 C++ 侧一致；用镜像后的状态编码，与检测侧同源格式）
        refreshClosedFieldBaseline(gameDataJsonWithoutRng(stateStore.gameData.value))
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
            val ok = if (restoreRng) {
                GameCoreBridge.nativeImportState(encoded.encodeToByteArray())
            } else {
                GameCoreBridge.nativeImportStateNoRng(encoded.encodeToByteArray())
            }
            if (ok) {
                // 全量导入后 C++ 的 AI 弟子池与 Kotlin 一致——缓存对齐，
                // 反向通道仅在后续变化时重发（含降级全量回导兜底路径）
                lastAiSectDisciplesSent = state.gameData.aiSectDisciples
                lastLockedBeastIdsSent = state.gameData.lockedBeastIds
                // 导入成功 → C++ 已知该 gameData 全量同值 → 反向锚点推进
                //（锚点本就不含 rngStates，与 restoreRng 取值无关——native RNG 真相源）
                anchorReverseGameData(state.gameData)
                // 全量导入 = C++ 已收到全部字段 → 关闭字段基线随之刷新
                refreshClosedFieldBaseline(gameDataJsonWithoutRng(state.gameData))
                // 全量导入 = 重同步基线——反向增量版本归零（配对
                // C++ importStateInternal 的 reverseVersion_ = 0；否则
                // 跨会话/热重载后版本错位，所有增量回导会被 C++ 版本检查永久拒绝）
                reverseVersion = 0
            }
            ok
        } catch (e: Throwable) {
            false
        }
    }

    // ============================================================
    // 反向增量回导（取代 AUTHORITATIVE 每旬全量 importToNative）
    // ============================================================

    /** Kotlin → C++ 反向增量回导版本号（单调递增；C++ 侧校验严格递增防乱序） */
    private var reverseVersion = 0L

    /**
     * 上次回导给 C++ 的 AI 宗门弟子池（[GameData.aiSectDisciples]）。
     *
     * 变化检测缓存：该字段 @Transient 不入 gameData JSON，反向信封单独携带全量段
     * （AI 招募/战斗才更新）——避免每 tick 重发重型数据。初始 null 表示"未同步"
     * （首次 gameDataChanged 必发一次）；[importToNative] 全量导入成功后与 C++
     * 对齐置为导入值；[sendReverseEnvelope] 发送成功后置为已发送值。引擎线程
     * 串行访问（tick 调度器），无需同步。
     */
    private var lastAiSectDisciplesSent: Map<String, List<Disciple>>? = null

    /**
     * 上次回导给 C++ 的妖兽视图锁定集（[GameData.lockedBeastIds]）。
     *
     * 变化检测缓存：该字段 @Transient 不入 gameData JSON，反向信封
     * 单独携带全量 id 段（整体替换语义——lock/unlock 均为集合重写）。初始 null
     * 表示"未同步"（首次 gameDataChanged 必发一次）；[importToNative] 全量导入
     * 成功后与 C++ 对齐置为导入值；[sendReverseEnvelope] 发送成功后置为已发送值。
     * 引擎线程串行访问（tick 调度器），无需同步。
     */
    private var lastLockedBeastIdsSent: Set<String>? = null

    /**
     * 已关闭 gameData 字段的"C++ 已知值"影子表（batch-21 关闭域写入检测）。
     *
     * 反向通道逐域关闭后，被关闭字段的 Kotlin 写入不再回导 C++——若该字段在
     * AUTHORITATIVE 稳态下仍有 Kotlin 写者，即为**数据丢失缺陷**（下一次前向
     * 镜像会以 C++ 侧值覆盖）。本表记录 C++ 侧已确认的值，信封构建时与 Kotlin
     * 当前值比较，差异即命中（见 [detectClosedFieldWrites]）。
     *
     * 表内容仅覆盖[ReverseChannelPolicy.closedGameDataFields]（通常为个位数），
     * 更新时机：全量导入成功 / 全量镜像成功 / 前向增量镜像携带的 gameData 字段
     * ——三者都是"C++ 值被确认"的时刻。null = 尚未建立基线（启动早期不做检测，
     * 避免把"未同步"误报为"越权写入"）。
     */
    private var closedFieldKnown: MutableMap<String, JsonElement>? = null

    /** 已上报过 ERROR 的关闭字段（日志去重；计数仍逐次累计，见策略诊断面）。 */
    private val reportedClosedFieldWrites = HashSet<String>()

    /**
     * 反向 gameData 锚点：上次确认 C++ 已知同值的 gameData JSON
     * （kotlinx 编码格式，剔除 rngStates——native RNG 真相源）。
     *
     * 不变量：锚点 ⊆ C++ 已知值。推进点（三处，全部 kotlinx 编码同源格式）：
     *   - [importToNative] 全量导入成功 → 锚点 = 导入值；
     *   - [syncFromNative] 全量镜像成功 → 锚点 = 导出快照（重新编码）；
     *   - [sendReverseEnvelope] 发送成功 → 锚点 = 信封构建时快照
     *     （[pendingReverseGameDataAnchor]，发送与构建间无并发写——引擎线程串行）。
     * 前向增量镜像（applyDirty）**不**推进锚点——保守过期方向：反向 diff 会把
     * C++ 镜像写回的字段重复携带（值等于 C++ 自身，应用为无操作），绝不漏发。
     * null = 未锚定（下一窗 gameData 段全量发送）。引擎线程串行访问，无需同步。
     */
    private var lastGameDataSentJson: JsonObject? = null

    /**
     * 反向信封构建时的 gameData 快照（[sendReverseEnvelope] 发送成功后推进
     * [lastGameDataSentJson]；发送失败/未携带 gameData 段时消费丢弃——锚点
     * 只允许推进到 C++ 确认收到的快照）。构建与消费在 sendReverseEnvelope
     * 内同步完成，无跨调用残留。
     */
    private var pendingReverseGameDataAnchor: JsonObject? = null

    /**
     * 反向增量回导：把 AUTHORITATIVE 残留窗口内 Kotlin 侧的状态变化增量发给 C++。
     *
     * 数据来源 [GameStateStore.consumeReverseDirty]（事务级捕获累积，见
     * GameStateStoreImpl.captureReverseDirty）。协议与 forward 对称
     * （gameData 段为字段级 dirty 集，未锚定首窗全量）：
     * ```
     * { "version": N,
     *   "changed": {
     *     "gameData": { ...仅变更字段，不含 rngStates；C++ 按补丁应用... },
     *     "disciples": [ {全实体}... ],
     *     "equipmentStacks": [ {全实体}... ],  // 窗口内引用变化的集合
     *     "aiSectDisciples": {...},            // @Transient 顶层段（变化时）
     *     "lockedBeastIds": [...]              // @Transient 顶层段（变化时）
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
        val gameData = stateStore.gameData.value
        val buildStartNanos = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val envelope = buildReverseEnvelope(
            snapshot = snapshot,
            tables = stateStore.discipleTables,
            gameData = gameData
        )
        // 构建时暂存的锚点候选（消费即清——发送失败/未携带 gameData 段时不推进，
        // 防止锚点越过 C++ 实际收到的快照造成后续漏发）
        val anchorCandidate = pendingReverseGameDataAnchor
        pendingReverseGameDataAnchor = null
        val encoded = envelope.toString().encodeToByteArray()
        val sendStartNanos = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        // w3-13 阶段 A 观察窗（先禁用后删除）：信封已构建（关闭域写入检测保持
        // 存活），但**不发送** C++——传输体积 = 0 可观测；版本号/AI 池与锁定集
        // 缓存/锚点均不推进（与"C++ 未收到任何字节"语义一致）。仪器随删除批移除。
        if (!ReverseChannelPolicy.reverseTransportEnabled) {
            if (BuildConfig.DEBUG) {
                DomainLog.d(
                    TAG,
                    "反向通道已禁用（w3-13 观察窗）：本窗口信封 ${encoded.size}B 构建未发送 " +
                        "removed=${(envelope["removed"] as? JsonObject)?.values?.size ?: 0} 段"
                )
            }
            return true
        }
        // 双实现并行契约：native 失败降级 false（调用方回退全量）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            val ok = reverseSender(encoded)
            if (BuildConfig.DEBUG) {
                logReverseEnvelopeProfile(
                    envelope = envelope,
                    totalBytes = encoded.size,
                    buildMicros = (sendStartNanos - buildStartNanos) / 1_000L,
                    sendMicros = (System.nanoTime() - sendStartNanos) / 1_000L,
                    ok = ok,
                )
            }
            if (ok) {
                reverseVersion++
                // 发送成功后缓存与 C++ 一致（下次仅在变化时重发）
                lastAiSectDisciplesSent = gameData.aiSectDisciples
                lastLockedBeastIdsSent = gameData.lockedBeastIds
                // 发送成功 → C++ 已知构建时快照全量同值 → 锚点推进
                lastGameDataSentJson = anchorCandidate
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 反向信封体积/耗时埋点（batch-22a，debug 构建专属——`BuildConfig.DEBUG`
     * 为编译期常量，release 变体恒 false，经 R8 常量折叠整段剔除，零字节进 APK）。
     *
     * 采集项：① 信封总体积（UTF-8 字节 = JNI 实际传输量）与**分段体积**
     * （gameData / 弟子通道 / AI 池 / 锁定集 / 各集合段——与
     * ReverseChannelVolumeProfileTest 的单元 harness 口径同源）；② 信封构建
     * 耗时；③ native 发送耗时。三者构成 w3-13 通道删除（W4-D/D4）前的
     * "关闭前基线"真机采样与 WS-1 再评估阈值（E3/N5）的观测输入。
     */
    private fun logReverseEnvelopeProfile(
        envelope: JsonObject,
        totalBytes: Int,
        buildMicros: Long,
        sendMicros: Long,
        ok: Boolean,
    ) {
        val changed = envelope["changed"] as? JsonObject ?: JsonObject(emptyMap())
        val sectionBytes = changed.entries.joinToString(" ") { (name, el) ->
            "$name=${el.toString().toByteArray().size}B"
        }
        val removedBytes = (envelope["removed"] as? JsonObject)
            ?.values?.sumOf { it.toString().toByteArray().size } ?: 0
        DomainLog.d(
            TAG,
            "反向信封体积/耗时：total=${totalBytes}B 构建=${buildMicros}µs 发送=${sendMicros}µs " +
                "ok=$ok removed=${removedBytes}B 分段[$sectionBytes]"
        )
    }

    /**
     * 反向 gameData 段：当前值与锚点按键比较，仅保留变化字段（dirty 集）。
     * 未锚定返回全量（首窗建立基线）；键序随 kotlinx 编码（同源格式，数值
     * 比较逐位一致）。
     */
    private fun reverseGameDataPatch(current: JsonObject): JsonObject {
        val anchor = lastGameDataSentJson ?: return current
        return buildJsonObject {
            current.forEach { (key, value) ->
                if (anchor[key] != value) put(key, value)
            }
        }
    }

    /** 锚点推进（gameData → kotlinx 编码，剔除 rngStates）。 */
    private fun anchorReverseGameData(gameData: GameData) {
        lastGameDataSentJson = gameDataJsonWithoutRng(gameData) as? JsonObject
    }

    /**
     * 构建反向信封（version 单调递增；无任何通道内容时返回空 changed/removed）。
     *
     * gameData 段为**变更字段 dirty 集**（非对象引用变化即全量重发）——与
     * [lastGameDataSentJson] 锚点按键
     * 比较，仅携带变化字段（C++ 侧按字段级补丁应用，见 GameCore.applyReverseDirty）；
     * 未锚定（首窗）全量发送建立基线。
     */
    internal fun buildReverseEnvelope(
        snapshot: GameStateStore.ReverseDirtySnapshot,
        tables: DiscipleTables,
        gameData: GameData,
    ): JsonObject {
        val changed = mutableMapOf<String, JsonElement>()
        val removed = mutableMapOf<String, JsonElement>()
        if (snapshot.gameDataChanged) {
            appendGameDataSections(changed, gameData)
        }
        appendDiscipleSection(changed, removed, snapshot, tables)
        appendCollectionSections(changed, removed, snapshot)
        return buildJsonObject {
            put("version", reverseVersion + 1)
            put("changed", buildJsonObject { changed.forEach { (k, v) -> put(k, v) } })
            put("removed", buildJsonObject { removed.forEach { (k, v) -> put(k, v) } })
        }
    }

    /**
     * gameData 段 + 两个 `@Transient` 顶层段（逐域关闭过滤 + 关闭字段写入检测）。
     *
     * gameData 为变更字段 dirty 集（与锚点按键比较）；已关闭域字段剔除后
     * 仍做一次"C++ 已知值"比较——关闭后仍有 Kotlin 写者即为回导缺口。
     */
    private fun appendGameDataSections(
        changed: MutableMap<String, JsonElement>,
        gameData: GameData,
    ) {
        val currentGd = gameDataJsonWithoutRng(gameData)
        val patch = reverseGameDataPatch(currentGd)
        val transported = filterTransportedGameDataFields(patch)
        if (transported.isNotEmpty()) {
            changed["gameData"] = transported
        }
        detectClosedFieldWrites(currentGd)
        pendingReverseGameDataAnchor = currentGd
        appendAiSectDisciplesSection(changed, gameData)
        if (ReverseChannelPolicy.isSectionTransported(SECTION_LOCKED_BEAST_IDS) &&
            gameData.lockedBeastIds != lastLockedBeastIdsSent
        ) {
            // 妖兽视图锁定顶层段：变化检测 + 整体替换（@Transient 不入 gameData JSON）
            changed[SECTION_LOCKED_BEAST_IDS] = JsonArray(
                gameData.lockedBeastIds.map { JsonPrimitive(it) }
            )
        }
    }

    /**
     * AI 宗门弟子池顶层段（`@Transient`，单独全量段回导）。
     *
     * O(1) 快路径（审计 P2-3 / 方案 D3 改动 8）：GameData 为不可变数据类，
     * `update{}` 的 copy 保持未修改字段引用不变——引用相同即池未变，直接跳过
     * O(ΣD×字段数) 的数据类深比较（原实现稳态每旬全量比较 28k 池）。引用不同再
     * 深比较：内容相同（C++ 前向更新所致——镜像与 native 同值）仅推进缓存，
     * 免掉冗余全量回导（28k 池 JSON 两次过 JNI）与后续每旬重复深比较；
     * 内容不同（Kotlin 本地写——战斗死亡标记/攻占清池）照旧回导。
     */
    private fun appendAiSectDisciplesSection(
        changed: MutableMap<String, JsonElement>,
        gameData: GameData,
    ) {
        if (!ReverseChannelPolicy.isSectionTransported(SECTION_AI_SECT_DISCIPLES)) return
        if (gameData.aiSectDisciples === lastAiSectDisciplesSent) return
        if (gameData.aiSectDisciples != lastAiSectDisciplesSent) {
            changed[SECTION_AI_SECT_DISCIPLES] = json.encodeToJsonElement(
                serializer<Map<String, List<Disciple>>>(), gameData.aiSectDisciples
            )
        } else {
            lastAiSectDisciplesSent = gameData.aiSectDisciples
        }
    }

    /** 弟子段：窗口内变化 id → 当前表组装全实体（upsert，id 升序）；组装失败 → removed。 */
    private fun appendDiscipleSection(
        changed: MutableMap<String, JsonElement>,
        removed: MutableMap<String, JsonElement>,
        snapshot: GameStateStore.ReverseDirtySnapshot,
        tables: DiscipleTables,
    ) {
        if (!ReverseChannelPolicy.isDiscipleChannelTransported()) {
            if (snapshot.discipleIds.isNotEmpty()) {
                // 关闭后仍有弟子表写者 = 数据丢失缺陷（捕获侧已上报，此处兜底计数：
                // 信封可被直接构造，测试/兜底路径同样受检测约束）
                ReverseChannelPolicy.noteClosedWrite(
                    ReverseChannelPolicy.Kind.DISCIPLE_CHANNEL,
                    ReverseChannelPolicy.DISCIPLE_CHANNEL_NAME,
                    "buildReverseEnvelope"
                )
            }
            return
        }
        val upserted = tables.assembleAllIncremental(emptyList(), snapshot.discipleIds)
        val upsertedIds = upserted.mapTo(HashSet()) { it.id.toIntOrNull() ?: Int.MIN_VALUE }
        val removedDiscipleIds = snapshot.discipleIds - upsertedIds
        if (upserted.isNotEmpty()) {
            changed[ReverseChannelPolicy.DISCIPLE_CHANNEL_NAME] = JsonArray(
                upserted.map { json.encodeToJsonElement(Disciple.serializer(), it) }
            )
        }
        if (removedDiscipleIds.isNotEmpty()) {
            removed[ReverseChannelPolicy.DISCIPLE_CHANNEL_NAME] =
                JsonArray(removedDiscipleIds.map { JsonPrimitive(it.toString()) })
        }
    }

    /** 实体集合段：引用变化的集合 → 全量实体 upsert + 消失 id（关闭集合剔除并登记检测）。 */
    private fun appendCollectionSections(
        changed: MutableMap<String, JsonElement>,
        removed: MutableMap<String, JsonElement>,
        snapshot: GameStateStore.ReverseDirtySnapshot,
    ) {
        for ((name, capture) in snapshot.collections) {
            if (ReverseChannelPolicy.isCollectionTransported(name)) {
                if (capture.upserts.isNotEmpty()) {
                    changed[name] = encodeCollectionEntities(name, capture.upserts)
                }
                if (capture.removedIds.isNotEmpty()) {
                    removed[name] = JsonArray(capture.removedIds.map { JsonPrimitive(it) })
                }
            } else {
                ReverseChannelPolicy.noteClosedWrite(
                    ReverseChannelPolicy.Kind.COLLECTION, name, "buildReverseEnvelope"
                )
            }
        }
    }

    /** gameData 全量 JSON（剔除 rngStates 键——委托模式下 native RNG 即真相源）。 */
    private fun gameDataJsonWithoutRng(gameData: GameData): JsonObject {
        val full = json.encodeToJsonElement(GameData.serializer(), gameData).jsonObject
        return buildJsonObject {
            full.forEach { (k, v) -> if (k != RNG_STATES_FIELD) put(k, v) }
        }
    }

    /**
     * 逐域关闭过滤：剔除已关闭域的 gameData 字段（batch-21）。
     *
     * 关闭依据 = `ReverseChannelPolicy` 的逐域审计结论（该域稳态写者已归 C++）。
     * 未在关闭清单中的字段一律保留（默认开放）。
     */
    private fun filterTransportedGameDataFields(patch: JsonObject): JsonObject {
        if (patch.isEmpty()) return patch
        val closed = ReverseChannelPolicy.closedGameDataFields()
        if (closed.isEmpty()) return patch
        return buildJsonObject {
            patch.forEach { (key, value) ->
                if (key !in closed) put(key, value)
            }
        }
    }

    /**
     * 关闭域写入检测（batch-21）：已关闭字段的 Kotlin 当前值与 C++ 已知值不一致
     * ⇒ 该字段在 AUTHORITATIVE 稳态下仍有 Kotlin 写者 = **回导缺口（数据丢失风险）**。
     *
     * 语义边界：仅在基线已建立（[closedFieldKnown] 非 null，即发生过全量导入/全量镜像）
     * 时检测——启动早期"未同步"不是"越权写入"。基线在前向镜像携带该字段时同步
     * （C++ 值被确认），故 C++ 自身改动不会误报。
     */
    private fun detectClosedFieldWrites(current: JsonObject) {
        val known = closedFieldKnown ?: return
        if (known.isEmpty()) return
        for ((field, knownValue) in known) {
            val currentValue = current[field]
            if (currentValue != null && !jsonValuesEquivalent(currentValue, knownValue)) {
                ReverseChannelPolicy.noteClosedWrite(
                    ReverseChannelPolicy.Kind.GAME_DATA_FIELD, field, "buildReverseEnvelope"
                )
                if (reportedClosedFieldWrites.add(field)) {
                    DomainLog.e(
                        TAG,
                        "反向通道关闭域仍被 Kotlin 写入：字段 $field" +
                            "（Kotlin=$currentValue，C++已知=$knownValue）" +
                            "——该写入无法到达 C++，属数据丢失风险；需复核逐域审计结论或回滚该域"
                    )
                }
            }
        }
    }

    /**
     * 刷新关闭字段基线（C++ 值被确认的三个时刻：全量导入 / 全量镜像 / 前向增量镜像）。
     *
     * @param gameDataJson 以 kotlinx 编码的 gameData JSON（与检测侧同源格式）
     */
    private fun refreshClosedFieldBaseline(gameDataJson: JsonObject) {
        val closed = ReverseChannelPolicy.closedGameDataFields()
        if (closed.isEmpty()) {
            closedFieldKnown = null
            return
        }
        val known = HashMap<String, JsonElement>(closed.size)
        for (field in closed) {
            gameDataJson[field]?.let { known[field] = it }
        }
        closedFieldKnown = known
    }

    /**
     * 前向增量镜像携带的 gameData 字段 = C++ 侧当前值——同步基线，
     * 避免把"C++ 自己改的值"误报为越权写入。
     */
    private fun updateClosedFieldBaseline(overrides: Map<String, JsonElement>) {
        val known = closedFieldKnown ?: return
        if (known.isEmpty()) return
        for ((field, value) in overrides) {
            if (field in known) known[field] = value
        }
    }

    /**
     * JSON 值语义相等（容忍 C++ 整数 double 输出形式：`12000` vs `12000.0`）。
     * 结构不同（对象/数组）退化为字面比较——本检测只用于诊断，宁可漏报不误报
     * 亦可接受，但不得因格式差异产生噪声。
     */
    private fun jsonValuesEquivalent(a: JsonElement, b: JsonElement): Boolean {
        if (a == b) return true
        val pa = a as? JsonPrimitive ?: return false
        val pb = b as? JsonPrimitive ?: return false
        if (pa.isString != pb.isString) return false
        val na = pa.content.toDoubleOrNull()
        val nb = pb.content.toDoubleOrNull()
        return na != null && nb != null && na == nb
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

    /** 应用非空变更集（单次 updateMirror 事务原子完成——镜像写入不参与反向捕获）。 */
    private fun applyEnvelope(envelope: DirtyEnvelope): DirtyApplyResult {
        var changedFieldCount = 0
        var upsertCount = 0
        var removedCount = 0
        stateStore.updateMirror {
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
        // 前向镜像携带的键 = C++ 侧当前值——刷新关闭字段基线（防误报）
        updateClosedFieldBaseline(overrides)
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
        var ups = 0
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

    companion object {
        private const val TAG = "StateSyncService"
        private const val GAMEDATA_PATH_PREFIX = "gameData."
        /** gameData 中 RNG 分区状态字段——反向回导必须剔除（native RNG 真相源） */
        private const val RNG_STATES_FIELD = "rngStates"
        /** 顶层段名：AI 宗门弟子池（@Transient，单独全量段承载） */
        internal const val SECTION_AI_SECT_DISCIPLES = ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES
        /** 顶层段名：妖兽视图锁定集（@Transient，单独全量段承载） */
        internal const val SECTION_LOCKED_BEAST_IDS = ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS
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
