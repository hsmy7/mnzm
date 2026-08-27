#pragma once

#include <algorithm>
#include <cstdint>
#include <cctype>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"

// ============================================================
// 兑换码与灵根生成（Kotlin→C++ 迁移计划 v2 阶段 4 / 批 4-6）
//
// 等价移植 Kotlin RedeemCodeManager / SpiritRootGenerator 的**确定性纯逻辑**：
//   - validateRedeemInput：兑换码格式校验（trim/空/长度 3..20/字符集
//     ^[\u4e00-\u9fa5A-Za-z0-9]+$）
//   - rollBySpiritRootCount：灵根阶梯属性掷点（1 灵根 80+ 逐级降 20）
//   - generateVariance / avoidSentinel50：属性方差（-50..50）/ 资质避开哨兵 50
//   - resolveAgeAndLifespan：年龄区间 + 境界寿元 ±10% 波动（coerceAtLeast）
//   - resolveSpiritRoot：灵根类型解析（配置指定 / 数量随机 / 权重随机生成）
//   - spiritRootGenerate：SpiritRootGenerator.generate（COUNT_WEIGHTS 权重表）
//   - JavaRandomCompat：java.util.Random 48 位 LCG 复现
//     （Kotlin `types.shuffled(java.util.Random(random.nextInt().toLong()))`）
//
// 与 Kotlin 语义对齐要点：
//   - 全部随机走 MAIL 分区（RngPartition::kMail——本地兑换走 MAIL 分区 RNG）
//   - shuffled(Random)：Kotlin 标准 Fisher-Yates（从后往前 nextInt(i+1)）
//   - java.util.Random 种子 = DeterministicRng.nextInt()（32 位 signed → toLong）
//   - 灵根权重表 COUNT_WEIGHTS：1→0.01, 2→0.03, 3→0.26, 4→0.30, 5→0.40
//   - 已知边界（保留 Kotlin）：名字生成（NameService）、体质/词条/天赋数据库
//     （PhysiqueDatabase/AffixDatabase/TalentDatabase）、头像池（PortraitPool）
//     与服务器验证/签名校验/频率限制（平台 IO）
// ============================================================
namespace gamecore::system {

// ── java.util.Random 兼容（48 位 LCG；Kotlin shuffled(java.util.Random(seed)) 用）──
class JavaRandomCompat {
public:
    explicit JavaRandomCompat(int64_t seed) {
        seed_ = (static_cast<uint64_t>(seed) ^ kMultiplier) & kMask;
    }

    /// next(31)：高位 31 位
    int32_t next31() { return next(31); }

    /// Java nextInt(bound)（拒绝采样；2 的幂快速路径）
    int32_t nextInt(int32_t bound) {
        if (bound <= 0) return 0;
        if ((bound & -bound) == bound) {  // 2 的幂
            return static_cast<int32_t>(
                (static_cast<uint64_t>(bound) * static_cast<uint64_t>(next(31))) >> 31);
        }
        int32_t bits = 0;
        int32_t val = 0;
        do {
            bits = next(31);
            val = bits % bound;
        } while (static_cast<int64_t>(bits) - val + (bound - 1) < 0);
        return val;
    }

    /// Kotlin shuffled(Random) Fisher-Yates（从后往前 nextInt(i+1)）
    template <typename T>
    static std::vector<T> shuffle(const std::vector<T>& input, int64_t seed) {
        std::vector<T> out = input;
        JavaRandomCompat rng(seed);
        for (std::size_t i = out.size(); i > 1; --i) {
            const std::size_t j = static_cast<std::size_t>(rng.nextInt(static_cast<int32_t>(i)));
            std::swap(out[i - 1], out[j]);
        }
        return out;
    }

private:
    static constexpr uint64_t kMultiplier = 0x5DEECE66DULL;
    static constexpr uint64_t kAddend = 0xBULL;
    static constexpr uint64_t kMask = (1ULL << 48) - 1;

    int32_t next(int32_t bits) {
        seed_ = (seed_ * kMultiplier + kAddend) & kMask;
        return static_cast<int32_t>(seed_ >> (48 - bits));
    }

    uint64_t seed_ = 0;
};

// ── 兑换码格式校验（Kotlin RedeemCodeManager.validateInput）──
// 校验结果：空串表示通过；非空表示错误消息（Kotlin RedeemResult.message 语义）。

namespace redeem_cfg {
inline constexpr int32_t kMinCodeLength = 3;
inline constexpr int32_t kMaxCodeLength = 20;
}  // namespace redeem_cfg

/// Kotlin String.trim() 的常用空白子集（ASCII 空白 + 全角空格 U+3000）
inline std::string kotlinTrim(const std::string& s) {
    auto isWs = [](unsigned char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v' ||
               c == 0xC2 || c == 0xA0 || c == 0xE3 || c == 0x80 || c == 0x80;  // U+00A0 / U+3000 字节
    };
    std::size_t begin = 0;
    std::size_t end = s.size();
    // 按 UTF-8 码点跳过前导/尾随空白（简化：ASCII 空白 + U+00A0(2字节) + U+3000(3字节)）
    while (begin < end) {
        const unsigned char c0 = static_cast<unsigned char>(s[begin]);
        if (c0 == ' ' || c0 == '\t' || c0 == '\n' || c0 == '\r' || c0 == '\f' || c0 == '\v') {
            ++begin;
        } else if (c0 == 0xC2 && begin + 1 < end && static_cast<unsigned char>(s[begin + 1]) == 0xA0) {
            begin += 2;  // U+00A0
        } else if (c0 == 0xE3 && begin + 2 < end && static_cast<unsigned char>(s[begin + 1]) == 0x80 &&
                   static_cast<unsigned char>(s[begin + 2]) == 0x80) {
            begin += 3;  // U+3000 全角空格
        } else {
            break;
        }
    }
    while (end > begin) {
        const unsigned char c0 = static_cast<unsigned char>(s[end - 1]);
        if (c0 == ' ' || c0 == '\t' || c0 == '\n' || c0 == '\r' || c0 == '\f' || c0 == '\v') {
            --end;
        } else if (c0 == 0xA0 && end >= 2 && static_cast<unsigned char>(s[end - 2]) == 0xC2) {
            end -= 2;  // U+00A0
        } else if (c0 == 0x80 && end >= 3 && static_cast<unsigned char>(s[end - 2]) == 0x80 &&
                   static_cast<unsigned char>(s[end - 3]) == 0xE3) {
            end -= 3;  // U+3000 全角空格
        } else {
            break;
        }
    }
    return s.substr(begin, end - begin);
}

/// 校验兑换码（Kotlin validateInput）：通过返回空串，失败返回错误消息
inline std::string validateRedeemInput(const std::string& rawCode) {
    const std::string code = kotlinTrim(rawCode);
    if (code.empty()) return "请输入兑换码";
    if (static_cast<int32_t>(code.size()) < redeem_cfg::kMinCodeLength) {
        return "兑换码长度不能少于" + std::to_string(redeem_cfg::kMinCodeLength) + "个字符";
    }
    if (static_cast<int32_t>(code.size()) > redeem_cfg::kMaxCodeLength) {
        return "兑换码长度不能超过" + std::to_string(redeem_cfg::kMaxCodeLength) + "个字符";
    }
    // ^[\u4e00-\u9fa5A-Za-z0-9]+$ —— 中文/ASCII 字母数字（UTF-8 码点级判定）
    std::size_t i = 0;
    const std::size_t n = code.size();
    while (i < n) {
        const unsigned char c0 = static_cast<unsigned char>(code[i]);
        if (c0 < 0x80) {
            const bool alnum = (c0 >= '0' && c0 <= '9') || (c0 >= 'A' && c0 <= 'Z') ||
                               (c0 >= 'a' && c0 <= 'z');
            if (!alnum) return "兑换码只能包含字母和数字";
            ++i;
        } else if ((c0 & 0xF0) == 0xE0 && i + 2 < n) {
            // 3 字节 UTF-8：CJK 统一表意文字 U+4E00..U+9FA5
            const uint32_t cp = (static_cast<uint32_t>(c0 & 0x0F) << 12) |
                                (static_cast<uint32_t>(static_cast<unsigned char>(code[i + 1]) & 0x3F) << 6) |
                                (static_cast<uint32_t>(static_cast<unsigned char>(code[i + 2]) & 0x3F));
            if (cp < 0x4E00 || cp > 0x9FA5) return "兑换码只能包含字母和数字";
            i += 3;
        } else {
            return "兑换码只能包含字母和数字";
        }
    }
    return "";
}

// ── 灵根阶梯属性掷点（Kotlin rollBySpiritRootCount）──
// 单灵根 80+ 起逐级降 20；5+ 灵根 1+nextInt(20)。RNG 消费 1×nextInt。
inline int32_t rollBySpiritRootCount(rng::DeterministicRng& rng, int32_t spiritRootCount) {
    switch (spiritRootCount) {
        case 1:
            return 80 + rng.nextInt(21);
        case 2:
            return 60 + rng.nextInt(21);
        case 3:
            return 40 + rng.nextInt(21);
        case 4:
            return 20 + rng.nextInt(21);
        default:
            return 1 + rng.nextInt(20);
    }
}

/// 属性方差（Kotlin generateVariance）：-50..50
inline int32_t generateVariance(rng::DeterministicRng& rng) { return -50 + rng.nextInt(101); }

/// 资质避开哨兵值 50（Kotlin avoidSentinel50；DEFAULT_APTITUDE=50）
inline int32_t avoidSentinel50(int32_t roll) { return roll == 50 ? 51 : roll; }

// ── 年龄与基础寿命解析（Kotlin resolveAgeAndLifespan）──
// RNG 消费：年龄 1×nextInt + 寿命 1×nextDouble。
inline std::pair<int32_t, int32_t> resolveAgeAndLifespan(rng::DeterministicRng& rng,
                                                         int32_t minAge, int32_t maxAge,
                                                         int32_t realm) {
    // S13 修复：minAge >= maxAge 时直接取 minAge（防 nextInt 负数崩溃）
    const int32_t age = (minAge >= maxAge) ? minAge : minAge + rng.nextInt(maxAge - minAge + 1);
    const int32_t realmMaxAge = disciple::realmConfig(realm).maxAge;
    // ±10% 波动（下限 coerceAtLeast(realmMaxAge)——出生即不低于境界基准寿元）
    const int32_t lifespan = std::max(
        static_cast<int32_t>(static_cast<double>(realmMaxAge) * (1.0 + (-0.1 + rng.nextDouble() * 0.2))),
        realmMaxAge);
    return {age, lifespan};
}

// ── 灵根类型解析（Kotlin resolveSpiritRoot / SpiritRootGenerator）──

/// 灵根元素（Kotlin ELEMENTS，顺序一致）
inline const std::vector<std::string>& spiritRootElements() {
    static const std::vector<std::string> kElements = {"metal", "wood", "water", "fire", "earth"};
    return kElements;
}

/// 灵根数量权重表（Kotlin GameConfig.SpiritRoot.COUNT_WEIGHTS，增量值）
inline const std::vector<std::pair<int32_t, double>>& spiritRootCountWeights() {
    static const std::vector<std::pair<int32_t, double>> kWeights = {
        {1, 0.01}, {2, 0.03}, {3, 0.26}, {4, 0.30}, {5, 0.40},
    };
    return kWeights;
}

/// SpiritRootGenerator.generate（Kotlin）：权重掷数量（1×nextDouble）→ 元素洗牌 → take → join(",")
inline std::string spiritRootGenerate(rng::DeterministicRng& rng) {
    const double rand = rng.nextDouble();
    double cumulative = 0.0;
    int32_t rootCount = 5;
    for (const auto& [count, weight] : spiritRootCountWeights()) {
        cumulative += weight;
        if (rand < cumulative) {
            rootCount = count;
            break;
        }
    }
    // ELEMENTS.shuffled(random)：kotlin Fisher-Yates（从后往前 nextInt(i+1)）
    std::vector<std::string> elements = spiritRootElements();
    for (std::size_t i = elements.size(); i > 1; --i) {
        const std::size_t j = static_cast<std::size_t>(rng.nextInt(static_cast<int32_t>(i)));
        std::swap(elements[i - 1], elements[j]);
    }
    std::string out;
    for (int32_t k = 0; k < rootCount; ++k) {
        if (k > 0) out += ",";
        out += elements[static_cast<std::size_t>(k)];
    }
    return out;
}

/// resolveSpiritRoot（Kotlin）：配置指定 / 数量随机 / 默认权重生成。
/// 返回 (success, 灵根串)；success=false 表示参数非法（调用方 fallback）。
/// RNG 消费：前两分支各 1×nextInt（java.util.Random 种子）→ java Random 内部洗牌；
/// 默认分支走 spiritRootGenerate（1×nextDouble + 洗牌）。
inline std::string resolveSpiritRoot(rng::DeterministicRng& rng, const std::string* cfgSpiritRootType,
                                     int32_t* cfgSpiritRootCount) {
    const std::string& baseType = (cfgSpiritRootType != nullptr) ? *cfgSpiritRootType : "";
    const int32_t count =
        (cfgSpiritRootCount != nullptr) ? std::max(*cfgSpiritRootCount, 1) : -1;  // coerceAtLeast(1)

    if (cfgSpiritRootType != nullptr && cfgSpiritRootCount != nullptr) {
        // 配置指定类型 + 数量
        if (count == 1) return baseType;
        std::vector<std::string> others;
        for (const auto& e : spiritRootElements()) {
            if (e != baseType) others.push_back(e);
        }
        const int64_t javaSeed = static_cast<int64_t>(rng.nextInt());  // random.nextInt().toLong()
        others = JavaRandomCompat::shuffle(others, javaSeed);
        std::string out = baseType;
        for (int32_t k = 1; k < count && static_cast<std::size_t>(k) <= others.size(); ++k) {
            out += "," + others[static_cast<std::size_t>(k - 1)];
        }
        return out;
    }
    if (cfgSpiritRootCount != nullptr) {
        // 仅数量：全部元素洗牌后取前 count
        const int64_t javaSeed = static_cast<int64_t>(rng.nextInt());
        auto shuffled = JavaRandomCompat::shuffle(spiritRootElements(), javaSeed);
        std::string out;
        for (int32_t k = 0; k < count && static_cast<std::size_t>(k) < shuffled.size(); ++k) {
            if (k > 0) out += ",";
            out += shuffled[static_cast<std::size_t>(k)];
        }
        return out;
    }
    // 无配置：SpiritRootGenerator.generate
    return spiritRootGenerate(rng);
}

}  // namespace gamecore::system
