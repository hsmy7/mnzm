#pragma once

#include <cstdint>

// ============================================================
// 运行时游戏配置（配置单源缺口）
//
// Kotlin GameConfigProvider（读 assets/config/game_config.json，支持远程
// 热更新）→ JNI 注入 → C++ 全局实例。消除 C++ 硬编码默认值与 Kotlin
// 配置读取的双端漂移（inventory.h 仓库容量常量、month_settlement.h
// 执法堂配置常量）。
//
// 设计（参照 core/platform.h PlatformProviders 注入先例）：
//   - 引擎初始化后由桥层注入（GameCoreBridge.cpp::nativeSetGameConfig）；
//     注入前/失败时 getter 返回 Kotlin 默认值（与 game_config.json 默认值
//     一致的静态兜底——见各 getter 注释）
//   - 单线程引擎契约：注入发生在引擎线程初始化期，月结/旬结消费与注入
//     无并发竞争（与 GameCore 单线程模型对齐，无需原子）
// ============================================================
namespace gamecore {

/// 运行时游戏配置（镜像 Kotlin GameConfigData 的相关段）
class GameConfig {
public:
    // ── 仓库（Kotlin GameConfigData.WarehouseSection） ──
    int32_t warehouseBaseCapacity = 50;
    int32_t warehouseCapacityPerBuilding = 75;

    // ── 执法堂（Kotlin GameConfigData.LawEnforcementSection） ──
    int32_t lawLoyaltyThreshold = 30;
    int32_t lawMoralityThreshold = 30;
    int32_t lawHerdLoyaltyThreshold = 50;
    double lawProbPerPoint = 0.01;
    double lawMaxProb = 0.90;
    double lawBaseCaptureRate = 0.0;
    int32_t lawIntelligenceBase = 50;
    double lawElderBonusPerPoint = 0.01;
    int32_t lawDiscipleIntelligenceStep = 5;
    double lawDiscipleBonusPerStep = 0.01;
    int32_t lawReflectionYears = 5;
    int32_t lawNewDiscipleProtectionMonths = 12;
    int32_t lawMaxTheftPerYear = 3;
    int32_t lawMaxTheftJudgementsPerMonth = 3;
};

/// 全局配置实例（注入前为默认值——与 game_config.json 默认值一致）
inline GameConfig& gameConfig() {
    static GameConfig instance;
    return instance;
}

/// 注入配置（桥层调用；Kotlin GameConfigProvider → JNI）
inline void setGameConfig(const GameConfig& config) {
    gameConfig() = config;
}

}  // namespace gamecore
