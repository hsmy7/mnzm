package com.byazt.td

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Robolectric 兼容桩（2026-08-14）。
 *
 * 背景：TapTap SDK（TapADN）的 [Collector] 字节码无 StackMapTable（dx 老产物，
 * ART 不要求 stackmap），Robolectric 应用安装时实例化 manifest 注册的 receiver
 * （`AndroidTestEnvironment.registerBroadcastReceivers`），JVM 类验证拒绝该字节码
 * 抛出 `VerifyError: Expecting a stackmap frame`——app 模块 254 个测试全部失败
 * （首个 receiver 实例化失败阻塞整个应用安装）。
 *
 * 触发条件：**Kover 关闭（插桩任务不执行）时必然触发**；Kover 开启时（transform
 * 插桩测试类）该问题被掩盖（具体机制未完全解释，见 docs/build-perf/robolectric-4.16-evaluation.md）。
 * 2.1 Kover 按需开关落地后本地默认 kover 关 → 本桩为必要基建，不可删除。
 *
 * 修复原理：测试源（build/tmp/kotlin-classes 产物）位于 Robolectric 沙箱 classpath
 * 首位，同名同包测试桩优先于 SDK 原类被加载。测试不初始化 TapTap SDK（需用户
 * 同意隐私政策后 UI 层才初始化），空实现即可满足 receiver 注册语义。
 *
 * 注意：若未来测试引入 TapTap SDK 初始化并依赖本类成员，需同步补齐桩的方法签名。
 */
class Collector : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
