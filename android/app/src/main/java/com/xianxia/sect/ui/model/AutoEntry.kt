package com.xianxia.sect.ui.model

import com.xianxia.sect.data.model.SaveSlot

/**
 * 自动进入游戏的决策结果（登录/防沉迷验证通过后，经加载界面直达游戏）。
 *
 * 玩家点击「进入游戏」后全程只看到加载界面；本 sealed 的每个分支最终都落在
 * `GameActivity` 的加载界面上（新档自动建档 / 老档自动读档 / 云档自动下载）。
 */
sealed interface AutoEntry {
    /** 本地有可读档 */
    data object LoadLocal : AutoEntry

    data object LoadCloud : AutoEntry

    /** 本地云端均无档：自动新建（宗门名走引擎默认「青云宗」） */
    data object CreateNew : AutoEntry
}

/**
 * 自动进入决策（纯函数，守卫测试 [com.xianxia.sect.ui.model.AutoEntryResolverTest] 锚定）。
 *
 * 判定优先级：
 * 1. 本地可读档（非空、非损坏）取最新——绝大多数玩家路径，零云端请求；
 * 2. 本地全无可读档且云端有档 → 读云；
 * 3. 本地仍有损坏档（isLoadError）→ 把损坏档交给读档链：`SaveValidator` 能修复则修复，
 *    不能修复时 `GameActivity` boot 失败弹窗的「删除存档并重新开始」是显式处置口；
 *    **任何情况下都不允许在本地数据状态可疑时静默新建覆盖**；
 * 4. 本地确无任何档 → 自动新建。
 */
object AutoEntryResolver {

    /**
     * 是否存在可读的本地档——调用方据此决定是否发起云端存在性检查
     *（有本地档的玩家跳过云端查询，零网络等待）。
     */
    fun hasLoadableLocal(slots: List<SaveSlot>): Boolean =
        loadableSlots(slots).isNotEmpty()

    /**
     * 决策入口。
     *
     * @param slots 本地槽位快照（含 slot 0 云伪槽与损坏档）
     * @param cloudHasSave 云端是否有档；null = 未判定（无本地档时才会发起查询，查询
     *   失败/超时也落在此值）。仅当本地无可读档时才会消费该值。
     */
    fun resolve(slots: List<SaveSlot>, cloudHasSave: Boolean?): AutoEntry {
        loadableSlots(slots).maxWithOrNull(
            compareBy { it.timestamp }
        )?.let { return AutoEntry.LoadLocal }
        if (cloudHasSave == true) return AutoEntry.LoadCloud
        slots.filter { it.isLoadError }
            .maxWithOrNull(compareBy { it.timestamp })
            ?.let { return AutoEntry.LoadLocal }
        return AutoEntry.CreateNew
    }

    private fun loadableSlots(slots: List<SaveSlot>): List<SaveSlot> =
        slots.filter { !it.isEmpty && !it.isLoadError }
}
