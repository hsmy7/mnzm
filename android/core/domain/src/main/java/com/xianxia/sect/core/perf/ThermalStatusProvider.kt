package com.xianxia.sect.core.perf

/**
 * 热状态提供者接口
 *
 * 在 :core:domain 中定义，由 :app 中的 ThermalMonitor 实现。
 * 解除 data 模块对 app 模块 Android 特定 API 的直接依赖。
 */
interface ThermalStatusProvider {
    /** 是否应降低非关键计算负载 */
    fun shouldReduceWorkload(): Boolean

    /** 是否应紧急保存并暂停 */
    fun shouldEmergencySave(): Boolean
}
