#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// 结算编排共享工具（T2.1/T2.2 共用）
//
// phase_settlement.h（每旬）与 month_settlement.h（月变）的 detail 命名空间
// 共用的小工具单一定义源——避免同签名 inline 函数跨头文件重复定义。
// 全部为纯函数；语义注释见各函数体。
// ============================================================
namespace gamecore::system {
namespace settle_util {

/// Kotlin String.toIntOrNull 等价：全串严格校验的整数解析
inline std::optional<int32_t> toIntOrNull(const std::string& s) {
    if (s.empty()) return std::nullopt;
    try {
        std::size_t pos = 0;
        const long v = std::stol(s, &pos);
        if (pos != s.size()) return std::nullopt;   // Kotlin 全串校验
        return static_cast<int32_t>(v);
    } catch (...) { return std::nullopt; }
}

inline bool containsString(const std::vector<std::string>& v, const std::string& s) {
    return std::find(v.begin(), v.end(), s) != v.end();
}

/// 弟子 Int id → 向量下标（等价 Kotlin tables.names.contains(id) 列查；
/// 同 id 保留最后一个 == SparseArray 写入语义）
inline std::map<int32_t, std::size_t> indexById(const std::vector<state::Disciple>& ds) {
    std::map<int32_t, std::size_t> m;
    for (std::size_t i = 0; i < ds.size(); ++i) {
        const auto id = toIntOrNull(ds[i].id);
        if (id.has_value()) m[*id] = i;
    }
    return m;
}

/// 灵根数量（split(",")；空串 → [""] → 1）
inline int32_t spiritRootCount(const state::Disciple& d) {
    return static_cast<int32_t>(std::count(d.spiritRootType.begin(),
                                           d.spiritRootType.end(), ',')) + 1;
}

/// UTF-8 串的 Kotlin String.length 口径长度（BMP 内码点数 == UTF-16 code unit 数；
/// 非 BMP 字符 Kotlin 计 2——守卫阈值场景不含 emoji，注释存疑边界）
inline std::size_t kotlinCharLength(const std::string& s) {
    std::size_t count = 0;
    for (std::size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        i += (c < 0x80) ? 1 : (c < 0xE0) ? 2 : (c < 0xF0) ? 3 : 4;
        ++count;
    }
    return count;
}

/// Kotlin String.isBlank：空串或全空白字符
inline bool isBlankString(const std::string& s) {
    if (s.empty()) return true;
    return std::all_of(s.begin(), s.end(), [](unsigned char c) {
        return c == ' ' || c == '\t' || c == '\n' ||
               c == '\r' || c == '\f' || c == '\v';
    });
}

}  // namespace settle_util
}  // namespace gamecore::system
