package com.xianxia.sect.ui.model

import com.xianxia.sect.data.unified.SaveInfo

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
 * 1. 本地可读档（非空、非损坏）——绝大多数玩家路径，零云端请求；
 * 2. 本地无可读档且云端有档 → 读云；
 * 3. 本地档损坏（isLoadError）→ 把损坏档交给读档链：`SaveValidator` 能修复则修复，
 *    不能修复时 `GameActivity` boot 失败弹窗的「删除存档并重新开始」是显式处置口；
 *    **任何情况下都不允许在本地数据状态可疑时静默新建覆盖**；
 * 4. 本地确无档 → 自动新建。
 */
object AutoEntryResolver {

    /**
     * 本地是否存在可读档——调用方据此决定是否发起云端存在性检查
     *（有本地档的玩家跳过云端查询，零网络等待）。
     */
    fun hasLoadableLocal(save: SaveInfo?): Boolean =
        loadable(save) != null

    /**
     * 决策入口。
     *
     * @param save 本地单档摘要（null = 查询面未就绪，视同无档，但损坏档语义仍由
     *   [SaveInfo.isLoadError] 承载）；@param cloudHasSave 云端是否有档；null = 未判定
     *   （无本地可读档时才会发起查询，查询失败/超时也落在此值）。仅当本地无可读档时
     *   才会消费该值。
     */
    fun resolve(save: SaveInfo?, cloudHasSave: Boolean?): AutoEntry {
        if (loadable(save) != null) return AutoEntry.LoadLocal
        if (cloudHasSave == true) return AutoEntry.LoadCloud
        if (save != null && save.isLoadError) return AutoEntry.LoadLocal
        return AutoEntry.CreateNew
    }

    private fun loadable(save: SaveInfo?): SaveInfo? =
        save?.takeIf { !it.isEmpty && !it.isLoadError }
}
