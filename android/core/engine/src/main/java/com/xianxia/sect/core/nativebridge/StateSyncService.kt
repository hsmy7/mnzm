package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class StateSyncService @Inject constructor(
    private val stateStore: GameStateStore,
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
     * @return true 导入成功
     */
    fun importToNative(): Boolean {
        val state = buildNativeState()
        val encoded = json.encodeToString(NativeGameState.serializer(), state)
        // 双实现并行契约：native 不可用降级 false（不崩溃，Kotlin 引擎照常）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            GameCoreBridge.nativeImportState(encoded.encodeToByteArray())
        } catch (e: Throwable) {
            false
        }
    }
}
