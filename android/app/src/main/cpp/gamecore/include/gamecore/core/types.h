#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// game-core 基础类型
// 对应 Kotlin core/domain 的通用类型映射。
// ============================================================
namespace gamecore {

/// 游戏实体 ID（对应 Kotlin String id；用 string_view 出入参避免拷贝热点）
using GameId = std::string;

/// 弟子 ID 等数值主键（对应 Kotlin Int id 字段）
using EntityIndex = int32_t;

/// 游戏时间：年份/月份/旬（1-based 月；旬 0=上旬 1=中旬 2=下旬）
struct GameTime {
    int32_t year = 1;
    int32_t month = 1;   // 1..12
    int32_t phase = 0;   // 0..2

    bool operator==(const GameTime&) const = default;
};

/// 金钱/数值（灵石 Long）
using Long = int64_t;
using Int = int32_t;
using Double = double;

}  // namespace gamecore
