package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * 碎片入账 native 回执（`GACHA_FRAGMENT_GRANT_TX` 成功信封的 data 段）。
 *
 * @property starBefore 入账前星级（C++ 侧无记录按 0）
 * @property starAfter 入账后星级（本次可连升多星）
 * @property fragmentsAfter 入账后当前星级内进度（满星后继续累加）
 */
internal data class GachaNativeGrant(
    val starBefore: Int,
    val starAfter: Int,
    val fragmentsAfter: Int,
)

/**
 * 角色碎片入账 native 事务转发臂（G08 写者下沉；InventoryNativeTx /
 * DiscipleLifecycleNativeTx 同构）。
 *
 * AUTHORITATIVE 门控下把入账事务（C++ `gacha_fragment.h::addFragment`——
 * 零校验、零 RNG、无效入参落失败信封且账本零改动）经 `tryExecuteNative`
 * 转发；成功内含 `applyDirtyFromNative` 脏段回读（两张碎片账本随之镜像）。
 * 门控关闭/桥未加载/信封失败一律返回 null，调用方回退逐字同式的 Kotlin
 * 账本 `GachaFragmentLedger`（双实现并行契约，玩家可见文案由 Kotlin 臂产出）。
 *
 * 走既有动作执行通道，本域**零新增 `external fun`**（JNI 计数门禁恒 86/86）。
 */
internal class GachaNativeTx(
    private val gameEngineCore: GameEngineCore,
) {

    /**
     * 碎片入账事务（ActionId `GACHA_FRAGMENT_GRANT_TX`）。
     *
     * @param templateId 角色模板 id（C++ 侧空串 ⇒ 失败信封）
     * @param count 本次入账碎片数（C++ 侧非正 ⇒ 失败信封）
     * @return native 已入账时返回回执；未转发/降级返回 null
     */
    fun tryGrantFragments(templateId: String, count: Int): GachaNativeGrant? {
        val data = tx(ActionIds.GACHA_FRAGMENT_GRANT_TX) {
            put("templateId", templateId)
            put("count", count)
        } ?: return null
        return GachaNativeGrant(
            starBefore = (data.long("starBefore") ?: 0L).toInt(),
            starAfter = (data.long("starAfter") ?: 0L).toInt(),
            fragmentsAfter = (data.long("fragmentsAfter") ?: 0L).toInt(),
        )
    }

    /** native 事务转发：AUTHORITATIVE 门控 + tryExecuteNative；失败信封/降级返回 null。 */
    private fun tx(actionId: Int, build: JsonObjectBuilder.() -> Unit): JsonElement? =
        if (!NativeEngineFlag.authoritative) {
            null
        } else {
            // 防御性空安全：生产恒非空，但测试 mock（未 stub stateSyncServiceRef）
            // 返回 null——非空声明的内在检查会在调用点即抛 NPE，须先经可空局部过滤
            val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
            if (sync == null) {
                null
            } else {
                GameEngineNativeOps.tryExecuteNative(
                    stateSyncService = sync,
                    actionId = actionId,
                    paramsJson = params(build)
                )
            }
        }
}
