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
 * GameEngineNativeOps — C++ 引擎转发辅助。
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
 * 全量转发依赖 C++ 引擎完整性（见 docs/cpp-engine.md）。
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
     *        可空——原非空参数的内在
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
        // 状态已变 → 字段级回读镜像：经 exportDirty 变更集只镜像 C++ 报告变化的字段/实体——查询类
        // 动作（checkAttackConditions 等）变更集为空 → 零镜像开销；脏通道不可用
        // （native 异常/解析失败）→ 全量同步兜底。
        // 镜像服务缺失或镜像失败不阻断转发结果（调用方仍以
        // C++ 计算值为准）
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            if (stateSyncService?.applyDirtyFromNative() == null) {
                stateSyncService?.syncFromNative()
            }
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

    /**
     * C++ 业务拒绝信封（failure 语义的三态载体）。
     *
     * `tryExecuteNative` 对失败信封统一降级为 null（"回退 Kotlin 原路径"契约）；
     * 但存在一类入口需要**用户可见文案**由 native 侧产出才能与 C++ 语义逐字一致
     * 且避免 Kotlin 侧重复判定链（洗炼 confirm 的 弟子不存在/已死亡/该特质已不存在 三态）。
     * 本方法保留失败信封的 `code`（errorType）与 `message`（C++ 诊断/文案）。
     *
     * @property code C++ failure 信封的 errorType（如 `NOT_FOUND` / `DEAD` / `INVALID`）
     * @property message C++ failure 信封的 message（玩家可读文案，与 Kotlin 回退臂同源）
     */
    data class NativeRefusal(val code: String, val message: String)

    /** 执行结果：成功携带 data；业务拒绝携带 [NativeRefusal]；降级（flag/桥/异常）为 null envelope */
    data class RawResult(val data: JsonElement?, val refusal: NativeRefusal?)

    /**
     * 执行动作并保留失败信封（[tryExecuteNative] 的失败信封保留变体）。
     *
     * 降级条件与 [tryExecuteNative] 一致（flag 关闭 / 桥未加载 / 异常 / 信封不可解析
     * → `RawResult(null, null)`，调用方走 Kotlin 回退臂）；native 成功同样执行镜像
     * 增量回读（与 [tryExecuteNative] 共用同一镜像契约）。
     */
    @Suppress("ReturnCount")  // 多 return 为降级契约（flag 关/未加载/异常/解析失败逐级返回空）
    fun executeRaw(
        stateSyncService: StateSyncService?,
        actionId: Int,
        paramsJson: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): RawResult {
        if (!NativeEngineFlag.enabled) return RawResult(null, null)
        if (!GameCoreBridge.isLoaded) return RawResult(null, null)
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val executed = try {
            GameCoreBridge.nativeExecute(actionId, paramsJson, nowMs)
        } catch (e: Throwable) {
            return RawResult(null, null)
        }
        if (executed.isEmpty()) return RawResult(null, null)
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val parsed = try {
            json.parseToJsonElement(executed.decodeToString())
        } catch (e: Exception) {
            return RawResult(null, null)
        }
        val obj = parsed as? JsonObject ?: return RawResult(null, null)
        if (obj["status"]?.toString() != "\"success\"") {
            val code = obj["code"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val message = obj["message"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            return RawResult(null, NativeRefusal(code, message))
        }
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            if (stateSyncService?.applyDirtyFromNative() == null) {
                stateSyncService?.syncFromNative()
            }
        } catch (e: Throwable) {
            // 镜像失败不阻断转发结果（调用方仍以 C++ 计算值为准）
        }
        return RawResult(obj["data"], null)
    }
}
