package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder

/** w3-01 弟子操作面事务转发（AUTHORITATIVE 门控；失败信封/降级返回 null）。 */
internal fun GameEngine.tryDiscipleOpNative(
    actionId: Int,
    paramsBuilder: JsonObjectBuilder.() -> Unit
): JsonElement? {
    if (!NativeEngineFlag.authoritative) return null
    // 防御性空安全：生产恒非空，测试 mock（未 stub）返回 null 时降级（红线 8 同款）
    val sync: StateSyncService? = stateSyncService
    if (sync == null) return null
    return GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = actionId,
        paramsJson = GameEngineNativeOps.params(paramsBuilder)
    )
}
