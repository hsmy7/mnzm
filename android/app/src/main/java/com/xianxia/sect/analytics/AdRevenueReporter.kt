package com.xianxia.sect.analytics

import android.util.Log
import com.xianxia.sect.taptap.TapDBManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 广告变现收入上报门面。
 *
 * 激励视频广告展示（onAdShow）时调用 [onAdShown]；
 * 模式开关 [TapDBConfig.adRevenueMode] 防止客户端/服务端重复统计（TapDB 官方要求二选一）。
 */
@Singleton
class AdRevenueReporter @Inject constructor() {

    private val tag = "AdRevenueReporter"

    /** 上报执行体（测试可替换以观察调用；生产默认走 TapDBManager） */
    internal var reportAction: (AdRevenueConfig) -> Unit = { TapDBManager.reportAdShow(it) }

    /** 广告展示回调：按配置上报 TapDB `#ad_show`（客户端模式） */
    fun onAdShown(spaceId: Long) {
        if (!TapDBConfig.analyticsEnabled || TapDBConfig.adRevenueMode != AdRevenueMode.CLIENT_ONLY) {
            Log.d(
                tag,
                "skip client ad revenue report: enabled=${TapDBConfig.analyticsEnabled}, " +
                    "mode=${TapDBConfig.adRevenueMode}"
            )
            return
        }
        val config = AdRevenueConfig.forSpaceId(spaceId)
        if (config == null) {
            Log.w(tag, "no AdRevenueConfig for spaceId=$spaceId, skip")
            return
        }
        reportAction(config)
    }
}
