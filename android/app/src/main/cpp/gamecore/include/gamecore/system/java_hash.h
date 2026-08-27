#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "gamecore/state/models.h"

// ============================================================
// Java 兼容哈希（Kotlin→C++ 迁移确定性红线）
//
// 复现 Java/Kotlin 标准库哈希语义（signed int32 溢出回绕）：
//   - javaStringHashCode：String.hashCode()（h = 31*h + char）
//   - javaListHashCode：List.hashCode()（AbstractList：h 初始 1，31*h + item）
//   - javaDoubleHashCode：Double.hashCode()（doubleToLongBits 高低 32 位异或）
//
// 用于 SectCombatPowerCalculator.computeFingerprint 缓存指纹与
// 宗门交易确定性种子（sectId.hashCode() + year）。
// ============================================================
namespace gamecore::system {

/// Java String.hashCode()（signed int32 回绕）。
/// 注意：Java/Kotlin 字符串是 UTF-16，hashCode 按 **UTF-16 code unit** 计算
/// （中文/BMP 字符 1 个 code unit，增补平面字符 surrogate pair 2 个 code unit）。
/// C++ 输入为 UTF-8 字节串 → 先解码为 UTF-16 code unit 序列再计算。
inline int32_t javaStringHashCode(const std::string& s) {
    uint32_t h = 0;
    std::size_t i = 0;
    const std::size_t n = s.size();
    while (i < n) {
        const unsigned char c0 = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0;
        std::size_t len = 0;
        if (c0 < 0x80) {
            cp = c0;
            len = 1;
        } else if ((c0 & 0xE0) == 0xC0) {
            cp = static_cast<uint32_t>(c0 & 0x1F);
            len = 2;
        } else if ((c0 & 0xF0) == 0xE0) {
            cp = static_cast<uint32_t>(c0 & 0x0F);
            len = 3;
        } else {
            cp = static_cast<uint32_t>(c0 & 0x07);
            len = 4;
        }
        for (std::size_t k = 1; k < len; ++k) {
            if (i + k >= n) break;
            cp = (cp << 6) | static_cast<uint32_t>(static_cast<unsigned char>(s[i + k]) & 0x3F);
        }
        i += len;
        if (cp < 0x10000) {
            // BMP 字符 → 1 个 code unit
            h = h * 31u + cp;
        } else {
            // 增补平面 → surrogate pair（Java 高位代理在前）
            const uint32_t v = cp - 0x10000;
            const uint32_t high = 0xD800 + (v >> 10);
            const uint32_t low = 0xDC00 + (v & 0x3FF);
            h = h * 31u + high;
            h = h * 31u + low;
        }
    }
    return static_cast<int32_t>(h);
}

/// Java List.hashCode()（AbstractList：h 初始 1，逐元素 31*h + elem.hashCode()）
inline int32_t javaListHashCode(const std::vector<std::string>& list) {
    uint32_t h = 1;
    for (const auto& s : list) {
        h = h * 31u + static_cast<uint32_t>(javaStringHashCode(s));
    }
    return static_cast<int32_t>(h);
}

/// Java Double.hashCode()（doubleToLongBits 的 bits ^ (bits >>> 32) 截断 int）
inline int32_t javaDoubleHashCode(double d) {
    uint64_t bits = 0;
    std::memcpy(&bits, &d, sizeof(double));
    const uint32_t high = static_cast<uint32_t>(bits >> 32);
    const uint32_t low = static_cast<uint32_t>(bits);
    return static_cast<int32_t>(high ^ low);
}

}  // namespace gamecore::system
