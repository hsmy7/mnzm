#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/system/java_hash.h"
#include "gamecore/system/rarity_progression.h"

// ============================================================
// 宗门交易确定性核心
//
// 等价移植 Kotlin DiplomacyService 宗门交易的**确定性纯逻辑**：
//   - 确定性种子：DeterministicRng.fromSeed(sectId.hashCode() + year)
//     ——同 (sectId, year) 生成结果完全可复现（Java hashCode 语义见 java_hash.h）
//   - calcMerchantStock：消耗品（草药/种子/材料）与耐用品两档库存曲线
//   - applyPriceFluctuation：价格波动 ±20%（1×nextDouble，toInt 截断一位小数）
//   - spiritStone 商品映射：上品(4)/中品(3) + 年份品阶上限判定
//
// 已知边界（保留 Kotlin）：商品模板选取（EquipmentDatabase.generateRandom /
// ItemDatabase.createPillFromTemplate 等注册表实例化）与 UUID（java.util.UUID）
// 属平台数据边界——本文件只提供 RNG 消费与判定核心。
// ============================================================
namespace gamecore::system {

/// 交易确定性种子（Kotlin：DeterministicRng.fromSeed(sectId.hashCode().toLong() + year)）
inline int64_t sectTradeSeed(const std::string& sectId, int32_t year) {
    return static_cast<int64_t>(javaStringHashCode(sectId)) + static_cast<int64_t>(year);
}

/// 商品库存量抽样（Kotlin calcMerchantStock：消耗品与耐用品两档库存曲线）
/// RNG 消费：恰好 1×nextInt（档内区间）。
inline int32_t sectTradeStock(rng::DeterministicRng& rng, const std::string& type,
                              int32_t rarity) {
    const bool isConsumable = type == "herb" || type == "seed" || type == "material";
    if (isConsumable) {
        switch (rarity) {
            case 6:
            case 5:
                return 3 + rng.nextInt(5);
            case 4:
                return 5 + rng.nextInt(6);
            case 3:
                return 5 + rng.nextInt(8);
            case 2:
                return 5 + rng.nextInt(11);
            default:
                return 7 + rng.nextInt(9);
        }
    }
    switch (rarity) {
        case 6:
        case 5:
            return 1 + rng.nextInt(3);
        default:
            return 1 + rng.nextInt(5);
    }
}

/// 价格波动（Kotlin GameUtils.applyPriceFluctuation(Long)）
/// RNG 消费：1×nextDouble（fluctuation = -20 + 40*d；一位小数截断；结果 ≥ 1）。
inline int64_t sectTradePriceFluctuation(int64_t basePrice, rng::DeterministicRng& rng) {
    const double fluctuationPercent = -20.0 + 40.0 * rng.nextDouble();
    const double roundedPercent =
        static_cast<int32_t>(fluctuationPercent * 10) / 10.0;  // toInt() 截断
    const double result =
        static_cast<double>(basePrice) * (1 + roundedPercent / 100.0);
    return std::max(static_cast<int64_t>(result), static_cast<int64_t>(1));
}

/// 灵石商品映射（Kotlin generateSpiritStoneItem 判定核心）：
/// 上品灵石(品阶 4) / 中品灵石(品阶 3)；超过当年可出上限 → 空（跳过）。
/// 返回 (itemRarity, basePrice)；basePrice = RATIO(=100) 或 RATIO²(=10000)。
inline std::optional<std::pair<int32_t, int64_t>> sectTradeSpiritStone(int32_t rarity,
                                                                       int32_t year) {
    constexpr int64_t kRatio = 10'000L;  // SpiritStoneExchange.RATIO（1 中品 = 10,000 下品）
    const int32_t spiritStoneRarity = rarity >= 4 ? 4 : 3;
    if (spiritStoneRarity > maxRarityForYear(year)) return std::nullopt;
    const bool isHigh = rarity >= 4;
    const int32_t itemRarity = isHigh ? 4 : 3;
    const int64_t basePrice = isHigh ? kRatio * kRatio : kRatio;
    return std::make_pair(itemRarity, basePrice);
}

}  // namespace gamecore::system
