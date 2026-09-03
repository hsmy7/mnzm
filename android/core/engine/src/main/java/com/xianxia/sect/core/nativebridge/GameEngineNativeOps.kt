package com.xianxia.sect.core.nativebridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * GameEngineNativeOps — C++ 引擎转发辅助（Kotlin→C++ 迁移批次 9 剩余）。
 *
 * 对已 C++ 化的动作经 [GameCoreBridge.nativeExecute] 提供 Kotlin 侧统一转发入口：
 * flag 开启时调用 C++ 计算，结果经 [StateSyncService] 镜像回 GameStateStore；
 * flag 关闭或 native 不可用时静默降级（返回 null，调用方走 Kotlin 原实现——双实现并行契约）。
 *
 * 使用方式（GameEngine 扩展方法内）：
 *   val data = tryExecuteNative(
 *       stateSyncService = gameEngine.stateSyncService,
 *       actionId = ActionIds.WALLET_ADD,
 *       params = params { put("amount", 100); put("grade", "LOW") }
 *   ) ?: run { /* flag 关闭/native 不可用 → Kotlin 原实现 */ }
 *
 * 注意：当前仅覆盖 C++ 已实现的纯计算/查询类动作；变更类动作的
 * 全量转发依赖 C++ 引擎完整性（批次 10 前置，见 docs/cpp-engine.md）。
 */
object GameEngineNativeOps {

    private val json = Json { encodeDefaults = true }

    /** 参数 JSON 构造器（Kotlin 侧序列化 → ByteArray）。 */
    fun params(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray {
        return json.encodeToString(JsonObject.serializer(), buildJsonObject(build)).encodeToByteArray()
    }

    /**
     * 尝试经 C++ 执行动作并镜像结果。
     *
     * @param stateSyncService 镜像服务（成功执行后把 C++ 状态宽松合并回 Kotlin）；
     *        可空——S-12 清偿（登记见 docs/cpp-engine.md §8）：原非空参数的内在
     *        null 检查在函数入口（早于 flag/isLoaded 早退）即触发，测试 mock 未
     *        stub `stateSyncServiceRef` 时返回 null 必触 NPE；改可空 + 内部守卫，
     *        镜像服务缺失时正常降级（不阻塞 flag 关闭/未加载的既有降级路径）
     * @return 成功时返回 C++ 结果 JSON 的 data 字段；flag 关闭/native 不可用/
     *         执行失败返回 null（调用方回退 Kotlin 实现）
     */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/未加载/异常/失败信封逐级返回 null）
    fun tryExecuteNative(
        stateSyncService: StateSyncService?,
        actionId: Int,
        paramsJson: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): JsonElement? {
        if (!NativeEngineFlag.enabled) return null
        if (!GameCoreBridge.isLoaded) return null
        // 双实现并行契约：native 任何异常/解析失败均降级 null，调用方回退 Kotlin 原实现
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val executed = try {
            GameCoreBridge.nativeExecute(actionId, paramsJson, nowMs)
        } catch (e: Throwable) {
            return null
        }
        if (executed.isEmpty()) return null
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val parsed = try {
            json.parseToJsonElement(executed.decodeToString())
        } catch (e: Exception) {
            return null
        }
        // 失败信封（status=failure）→ 返回 null 走 Kotlin 兜底
        val obj = parsed as? JsonObject ?: return null
        if (obj["status"]?.toString() != "\"success\"") return null
        // 状态已变 → 镜像回 Kotlin（宽松合并，未迁移字段保留）；
        // 镜像服务缺失（S-12 守卫）或镜像失败不阻断转发结果（调用方仍以
        // C++ 计算值为准）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            stateSyncService?.syncFromNative()
        } catch (e: Throwable) {
            // 镜像失败不阻断转发结果（调用方仍以 C++ 计算值为准）
        }
        return obj["data"]
    }

    /** 从 C++ 结果 JSON 提取字段（data 对象内）。 */
    fun JsonElement?.field(name: String): JsonElement? =
        (this as? JsonObject)?.get(name)

    /** 从 C++ 结果 JSON 提取字符串字段（data 对象内）。 */
    fun JsonElement?.str(name: String): String? =
        (this as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull }

    /** 从 C++ 结果 JSON 提取数值字段（data 对象内）。 */
    fun JsonElement?.long(name: String): Long? =
        (this as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.longOrNull }

    /** 从 C++ 结果 JSON 提取布尔字段（data 对象内）。 */
    fun JsonElement?.bool(name: String): Boolean? =
        (this as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.booleanOrNull }
}
