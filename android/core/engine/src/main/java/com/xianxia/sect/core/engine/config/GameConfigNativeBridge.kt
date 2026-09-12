package com.xianxia.sect.core.engine.config

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.DomainLog

/**
 * GameConfigNativeBridge — Kotlin 运行时配置 → C++ 全局 GameConfig 注入桥。
 *
 * 读取 [GameConfigProvider]（assets/config/game_config.json，支持远程热更新）的
 * 仓库容量 / 执法堂配置，注入 C++ `gamecore::setGameConfig`（经
 * [GameCoreBridge.nativeSetGameConfig]）——消除 C++ 硬编码默认值与 Kotlin 配置
 * 读取的双端漂移（inventory.h 仓库容量常量、month_settlement.h 执法堂
 * 常量）。
 *
 * 注入时机（双点幂等，任一点先到先注）：
 *   1. [CultivationEventProcessor]（@Singleton 月变/任务编排入口）构造 init——
 *      native 库未加载时跳过（库加载由 GameEngineCore.ensureAuthoritativeNative 触发）
 *   2. [GameEngineCoreAuthoritativeOps.ensureAuthoritativeNative] 的 nativeInit
 *      成功后补注（见 GameEngineCoreAuthoritativeOps.kt）——保证 native 引擎
 *      初始化完成时配置必然已注入
 *
 * 线程契约：nativeSetGameConfig 必须在引擎线程调用（GameCoreBridge 全局契约）；
 * 两处调用点均在引擎线程（构造期 Dagger 单例 + 引擎线程初始化）。
 */
object GameConfigNativeBridge {

    private const val TAG = "GameConfigNativeBridge"

    /** 已注入标志（幂等——双点调用只注一次） */
    @Volatile
    private var injected = false

    /** Provider 引用（由 CultivationEventProcessor 构造注册；ensureAuthoritativeNative 补注用） */
    @Volatile
    private var providerRef: GameConfigProvider? = null

    /** 注册 Provider（CultivationEventProcessor init 调用）并尝试注入 */
    fun register(provider: GameConfigProvider) {
        providerRef = provider
        injectFrom(provider)
    }

    /** 幂等注入：native 未加载或已注入时跳过 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")  // 注入失败按降级契约处理（C++ 默认值兜底）
    fun injectFrom(provider: GameConfigProvider) {
        if (injected || !GameCoreBridge.isLoaded) return
        try {
            val w = provider.warehouse
            val le = provider.lawEnforcement
            GameCoreBridge.nativeSetGameConfig(
                warehouseBaseCapacity = w.baseCapacity,
                warehouseCapacityPerBuilding = w.capacityPerBuilding,
                lawLoyaltyThreshold = le.loyaltyThreshold,
                lawMoralityThreshold = le.moralityThreshold,
                lawHerdLoyaltyThreshold = le.herdLoyaltyThreshold,
                lawProbPerPoint = le.probPerPoint,
                lawMaxProb = le.maxProb,
                lawBaseCaptureRate = le.baseCaptureRate,
                lawIntelligenceBase = le.intelligenceBase,
                lawElderBonusPerPoint = le.elderBonusPerPoint,
                lawDiscipleIntelligenceStep = le.discipleIntelligenceStep,
                lawDiscipleBonusPerStep = le.discipleBonusPerStep,
                lawReflectionYears = le.reflectionYears,
                lawNewDiscipleProtectionMonths = le.newDiscipleProtectionMonths,
                lawMaxTheftPerYear = 3,
                lawMaxTheftJudgementsPerMonth = 3
            )
            injected = true
            DomainLog.i(
                TAG,
                "GameConfig 注入 C++（warehouse=${w.baseCapacity}+${w.capacityPerBuilding}, " +
                    "law=${le.loyaltyThreshold}）"
            )
        } catch (e: Exception) {
            // 注入失败不阻断引擎启动——C++ 侧默认值兜底（与 game_config.json 一致）
            DomainLog.w(TAG, "GameConfig 注入 C++ 失败，C++ 使用默认值兜底", e)
        }
    }

    /** ensureAuthoritativeNative 补注入口（native 初始化完成后调用；幂等） */
    fun ensureInjected() {
        val provider = providerRef ?: return
        injectFrom(provider)
    }
}
