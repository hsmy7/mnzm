#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

// ============================================================
// 数值外置数据存储（B16 / R6.2）
//
// 背景：`gamecore/data/*.h` 七个头文件 DB（beast_config / beast_material_db /
// equipment_db / herb_db / manual_db / recipe_db / trait_db）历史上把**条目数值**
// 硬编码为 `static const std::vector<...>` 编译期常量表 ⇒ 改一个数值（如某丹药
// 的 `cultivationAdd`）要重编译整个 C++ 逻辑。
//
// 本批把这批数值搬到**数据文件**（`assets/data/game-data.json`），沿
// `game_config.json` / `nativeSetGameConfig` 既有先例：
//   - **数值真相源在 Kotlin/资产侧**（`scripts/data/*_sample.json`，Kotlin
//     Registry 的单一源快照），
//   - C++ 侧只在**引擎初始化期**接收一次注入并解析，
//   - 注入前/失败时用**头文件内联默认值兜底**（与数据文件默认值一致——
//     由 `scripts/gen-game-data.mjs` 同源产出，禁止双真相源漂移）。
//
// ── 设计要点 ────────────────────────────────────────────────
//  1. **注入只发生一次**。`loadFromJson` 仅在 `kUninitialized` 状态接受写入，
//     稳态（`kLoadedFromFile` / `kFallbackDefault`）再次调用返回 false，
//     表容器**地址不再变化** ⇒ 满足"注入仅初始化期一次 + 指针稳定性"两条硬约束
//     （`beastMaterialById` / `manualById` / `talentById` 等返回 `const T*`，
//     消费点会持有该指针）。
//  2. **显式失败，禁止静默空表**。解析失败时保留内联默认并置
//     `kFallbackDefault`；`stateName()` 可观测；`loadedCount()` 用于守卫断言。
//  3. **本头文件不包含任何具体 DB struct**（避免循环依赖）：注入由
//     `gamecore/data/data_inject.h` 完成——它把解析出的 JSON 段映射到各
//     DB 的容器。本文件只持有通用状态 + 通道。
// ============================================================
namespace gamecore::data {

/// 数据文件 schema 版本（与 scripts/gen-game-data.mjs 的 SCHEMA_VERSION 同字面量）
inline constexpr int32_t kGameDataSchemaVersion = 1;

/// 注入状态（可观测——守卫测试与运行期诊断用）
enum class GameDataState : int32_t {
    /// 尚未注入：表 = 头文件内联默认（与数据文件默认值一致）
    kUninitialized = 0,
    /// 已从数据文件成功注入
    kLoadedFromFile = 1,
    /// 注入失败：保留内联默认兜底（**不是**空表）
    kFallbackDefault = 2,
};

inline const char* stateName(GameDataState s) {
    switch (s) {
        case GameDataState::kUninitialized: return "uninitialized";
        case GameDataState::kLoadedFromFile: return "loadedFromFile";
        case GameDataState::kFallbackDefault: return "fallbackDefault";
    }
    return "unknown";
}

/// 注入计数器（**注入纪律守卫锚点**：结束后必须恰为 1，稳态零增长）
struct GameDataInjectionStats {
    /// `loadFromJson` 收到调用的次数（无论成败）
    int32_t attempts = 0;
    /// 真正被接受的注入次数（≤1，稳态不再增长）
    int32_t accepted = 0;
    /// 因"已初始化"而被拒绝的重复注入次数
    int32_t rejectedAlreadyLoaded = 0;
    /// 因解析/schema 校验失败而落到兜底的次数
    int32_t failedParse = 0;
};

/// 全局数据存储状态（单线程引擎契约：注入发生在初始化期，与结算无并发）
struct GameDataStoreState {
    GameDataState state = GameDataState::kUninitialized;
    GameDataInjectionStats stats{};
    /// 各表注入后的实际条目数（0 = 该表未注入，仍用内联默认）
    int32_t loadedCount = 0;
    /// 注入时读到的 schemaVersion（0 = 未读）
    int32_t schemaVersion = 0;
};

/// 全局唯一实例
inline GameDataStoreState& gameDataStoreState() {
    static GameDataStoreState instance;
    return instance;
}

/// 是否已处于稳态（已注入 或 已显式兜底）——稳态后表容器地址不得再变
inline bool isGameDataSealed() {
    return gameDataStoreState().state != GameDataState::kUninitialized;
}

/// 注入入口（**仅初始化期调用一次**）。
///
/// `json`：数据文件全文（`assets/data/game-data.json`）。
/// `apply`：把解析后的 JSON 写进各 DB 容器的回调（由 `data_inject.h` 提供）；
///          返回 false 表示该段校验失败 ⇒ 整体视为注入失败并落兜底。
///
/// 返回 true = 注入被接受（表已是数据文件值）；
/// 返回 false = 被拒绝（重复注入 / 解析失败 / schema 不符）——此时表保持
///             兜底默认，调用方**不得**假设表为空。
bool loadFromJson(const std::string& json,
                  const std::function<bool(const void*)>& apply);

/// 便捷重载：不带 apply（仅测状态机；生产用上面那个）
bool loadFromJson(const std::string& json);

/// 复位到未注入态（**仅测试用**；生产代码禁止调用——复位会让消费点持有的
/// 指针悬空，破坏指针稳定性约束）
void resetGameDataStoreForTest();

}  // namespace gamecore::data
