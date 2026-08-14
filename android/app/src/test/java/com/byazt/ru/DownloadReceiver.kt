package com.byazt.ru

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Robolectric 兼容桩（2026-08-14）。
 *
 * 与 [com.byazt.td.Collector] 相同背景：TapTap SDK 下载模块的 manifest receiver
 * 字节码无 StackMapTable，Robolectric 应用安装时实例化导致 VerifyError。
 * 测试桩优先于 SDK 原类被加载，空实现满足注册语义。
 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
