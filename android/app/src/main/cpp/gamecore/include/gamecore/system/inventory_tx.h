#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"
// batch-11：商人模板转换器与按型容量谓词（merchant_settle 同源复用，禁止
// 复制漂移）+ 袋条目物化原语（sr_session 与 Kotlin BagItemReconstructor
// 对拍锁定）。二者传递包含均无 month_settlement using 声明类包含序风险
//（diplomacy_tx.h 先例），可置于 diplomacy_tx.h 之前的包含位。
#include "gamecore/system/merchant_settlement.h"
#include "gamecore/system/secret_realm_session.h"
#include "gamecore/system/settlement_detail.h"

// ============================================================
// 库存出售/上架事务（InventoryFacade AUTHORITATIVE 转发——C++ 唯一真相）
//
// 承接 Kotlin InventoryFacadeImpl 的出售族写者语义（W2-a 下沉；判定序、
// 取价公式、扣减序与 Kotlin 原实现逐字对齐）：
//   sellItemTx            单类出售：物品存在 → 未锁定且 1<=quantity<=持有量
//                         → 售价入账（来源 Sell(<type>)）→ 扣减/移除堆叠
//   bulkSellTx            批量出售：逐条 deductStack（零入账）→ 末尾一次
//                         入账（来源 Sell("bulk")）
//   sellToMerchantTx      商人收购：收购项存在 → 非法参数拒绝 → 仓库实际
//                         可售量 → 扣仓 → 入账（MerchantTrade）→ 收购项数量回写
//   listItemsToMerchantTx 玩家上架：装备/功法/丹药三段首命中（不扣仓库，
//                         仅登记 playerListedItems）
//   removePlayerListedItemTx  上架项移除
//   consumeMaterialByNameTx   按名称+品阶消耗材料（未锁定、按列表序）
//
// 承接 Kotlin InventoryFacadeImpl 的库存收官写者语义（batch-11 下沉）：
//   buyMerchantItemTx         商人购买：商品存在 → D-21 篡改档防御 →
//                             灵石/库存早退 → 模板存在性 → 按型容量预测 →
//                             先加物品后扣灵石（Purchase/MerchantTrade）→
//                             商家库存扣减；模板缺失 failure 信封回退
//   confiscateStorageBagItemTx 充公：幂等探测（以袋内当前条目为准）→
//                             数量篡改防御 → 三态物化（实例裸回仓/堆叠
//                             模板重建 quantity=1/模板缺失保留）→ 溢出
//                             抑制 → 仅 Success 移除袋条目
//
// 校验失败零写入（单类/批量按 Kotlin 语义"未成交即零写入"；商人收购
// 非法参数直接拒绝），信封 failure → Kotlin 回退原路径重执行校验链。
//
// 零 RNG 论证：全链纯确定性状态变换——取价为品阶基准价/模板价的纯函数，
// 校验链只读，变更段仅堆叠数量 + 灵石余额 + 商人条目。Kotlin 原路径同样
// 零 Random 消费（Signature 级证据：本头 API 不接受 RngManager/种子参数），
// 抽取集为空集，RNG 红线平凡满足。
//
// batch-11 RNG 边界（开袋不下沉）：商人购买的模板缺失回退臂在 Kotlin 走
// JVM Random.Default（generateRandom 族，非分区、不可复刻）——C++ 以
// TemplateMiss failure 信封回退 Kotlin 原路径（含其随机回退语义），禁止
// 近似复刻；开袋子域分支内 Random.Default 抽取同理不可复刻，本批不下沉
//（路线 B，登记 batch-23 待拍板）。
//
// 上架条目 id 为确定性自增占位（Kotlin UUID.randomUUID 属非协议随机域；
// recruit_settlement.h / diplomacy_tx.h 同先例）——id 不参与任何业务判定，
// 对拍面忽略新增条目 id。
//
// 取价口径（Kotlin GameConfig.Rarity / ItemDatabase）：
//   装备 EquipmentDatabase.getTemplateByName(name)?.price ?: 品阶 basePrice
//   功法 品阶 basePrice（**不查模板**——Kotlin ManualStack.basePrice 自带语义）
//   丹药 roundToInt(品阶 pillBasePrice × PillGrade.priceMultiplier)
//   材料 materialBasePrice / 草药 herbPrice / 种子 seedPrice
//   出售价 = (basePrice × quantity × 0.8) 向零截断
// ============================================================
namespace gamecore::system::inventory_tx {

/// 品阶基准价（Kotlin GameConfig.Rarity.CONFIGS.*.basePrice / pillBasePrice）
inline constexpr int32_t kRarityBasePrice[7] = {0, 4000, 16000, 80000, 480000,
                                                3360000, 26880000};

/// 材料基准价（Kotlin GameConfig.Rarity.*.materialBasePrice）
inline constexpr int32_t kRarityMaterialBasePrice[7] = {0, 400, 1600, 8000, 48000,
                                                        336000, 2688000};

/// 草药基准价（Kotlin GameConfig.Rarity.*.herbPrice）
inline constexpr int32_t kRarityHerbPrice[7] = {0, 400, 1600, 8000, 48000, 336000,
                                                2688000};

/// 种子基准价（Kotlin GameConfig.Rarity.*.seedPrice）
inline constexpr int32_t kRaritySeedPrice[7] = {0, 80, 320, 1600, 9600, 67200, 537600};

/// GameConfig.Rarity.get(rarity)：越界/缺失回退品阶 1（CONFIGS[rarity] ?: CONFIGS[1]）
inline int rarityIndex(int32_t rarity) {
    return (rarity >= 1 && rarity <= 6) ? rarity : 1;
}

/// GameConfig.Rarity.calculateSellPrice = (basePrice.toLong() * quantity * 0.8).toLong()
inline int64_t calculateSellPrice(int32_t basePrice, int32_t quantity) {
    return static_cast<int64_t>(static_cast<double>(basePrice) *
                                static_cast<double>(quantity) * 0.8);
}

/// Kotlin Double.roundToInt()（Math.round：floor(x + 0.5)）
inline int32_t roundToInt(double value) {
    return static_cast<int32_t>(std::floor(value + 0.5));
}

/// PillGrade.priceMultiplier（LOW 0.5 / MEDIUM 1.0 / HIGH 2.0；未知按 MEDIUM）
inline double pillGradeMultiplier(const std::string& grade) {
    if (grade == "LOW") return 0.5;
    if (grade == "HIGH") return 2.0;
    return 1.0;
}

/// PillGrade.name → displayName（Kotlin PillGrade.displayName）
inline std::string pillGradeDisplayName(const std::string& grade) {
    if (grade == "LOW") return "下品";
    if (grade == "HIGH") return "上品";
    return "中品";
}

/// ASCII 小写（Kotlin String.lowercase(Locale.getDefault()) 的类型名归一）
inline std::string toLowerAscii(const std::string& s) {
    std::string out = s;
    for (char& c : out) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return out;
}

namespace detail {

/// EquipmentDatabase.getTemplateByName：首名匹配（allTemplates.values.find）
inline const gamecore::data::EquipmentTemplate* equipmentTemplateByName(
    const std::string& name) {
    for (const auto& t : gamecore::data::equipmentTemplates()) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

/// ManualDatabase.getByName：首名匹配（allManuals.values.find——上架取价用；
/// 注意出售取价走 ManualStack.basePrice 品阶基准价，**不查模板**）
inline const gamecore::data::ManualTemplate* manualTemplateByName(
    const std::string& name) {
    for (const auto& t : gamecore::data::manualTemplates()) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

/// EquipmentStack.basePrice（模板价优先，缺失回退品阶基准价）
inline int32_t equipmentBasePrice(const gamecore::state::EquipmentStack& s) {
    const auto* tpl = equipmentTemplateByName(s.name);
    return tpl != nullptr ? tpl->price : kRarityBasePrice[rarityIndex(s.rarity)];
}

/// ManualStack.basePrice（品阶基准价——不查模板）
inline int32_t manualBasePrice(const gamecore::state::ManualStack& s) {
    return kRarityBasePrice[rarityIndex(s.rarity)];
}

/// Pill.basePrice = roundToInt(pillBasePrice × grade.priceMultiplier)
inline int32_t pillBasePrice(const gamecore::state::Pill& p) {
    return roundToInt(static_cast<double>(kRarityBasePrice[rarityIndex(p.rarity)]) *
                      pillGradeMultiplier(p.grade));
}

/// Material.basePrice
inline int32_t materialBasePrice(const gamecore::state::Material& m) {
    return kRarityMaterialBasePrice[rarityIndex(m.rarity)];
}

/// Herb.basePrice
inline int32_t herbBasePrice(const gamecore::state::Herb& h) {
    return kRarityHerbPrice[rarityIndex(h.rarity)];
}

/// Seed.basePrice
inline int32_t seedBasePrice(const gamecore::state::Seed& s) {
    return kRaritySeedPrice[rarityIndex(s.rarity)];
}

/// 单类出售扣减（Kotlin sellStack/deductStack）：未找到/已锁定/数量非法 → 0。
/// @param basePriceOf 该类型的 basePrice 取值函数
/// @return 售价（0 = 未成交，零写入）
template <typename T>
int64_t deductStack(std::vector<T>& store, const std::string& itemId, int32_t quantity,
                    int32_t (*basePriceOf)(const T&)) {
    auto it = std::find_if(store.begin(), store.end(),
                           [&itemId](const T& item) { return item.id == itemId; });
    if (it == store.end()) return 0;
    if (it->isLocked || quantity < 1 || quantity > it->quantity) return 0;
    const int64_t amount = calculateSellPrice(basePriceOf(*it), quantity);
    const int32_t newQuantity = it->quantity - quantity;
    if (newQuantity <= 0) {
        store.erase(it);
    } else {
        it->quantity = newQuantity;
    }
    return amount;
}

/// 按 itemType 分发扣减（未知类型返回 0——Kotlin when 的 else 分支语义）
inline int64_t deductByType(gamecore::state::GameState& st, const std::string& itemType,
                            const std::string& itemId, int32_t quantity) {
    if (itemType == "equipment") {
        return deductStack(st.equipmentStacks, itemId, quantity, equipmentBasePrice);
    }
    if (itemType == "manual") {
        return deductStack(st.manualStacks, itemId, quantity, manualBasePrice);
    }
    if (itemType == "pill") {
        return deductStack(st.pills, itemId, quantity, pillBasePrice);
    }
    if (itemType == "material") {
        return deductStack(st.materials, itemId, quantity, materialBasePrice);
    }
    if (itemType == "herb") {
        return deductStack(st.herbs, itemId, quantity, herbBasePrice);
    }
    if (itemType == "seed") {
        return deductStack(st.seeds, itemId, quantity, seedBasePrice);
    }
    return 0;
}

/// 仓库堆叠计数（Kotlin countWarehouseStacks：名称+品阶求和，**不看锁定**）
template <typename T>
int32_t countWarehouseStacks(const std::vector<T>& stacks,
                             const gamecore::state::MerchantItem& item) {
    int32_t total = 0;
    for (const auto& s : stacks) {
        if (s.name == item.name && s.rarity == item.rarity) total += s.quantity;
    }
    return total;
}

/// 丹药仓库计数（Kotlin countWarehousePills：名称+品阶+品级显示名）
inline int32_t countWarehousePills(const std::vector<gamecore::state::Pill>& pills,
                                   const gamecore::state::MerchantItem& item) {
    const std::string grade = item.grade.value_or("");
    int32_t total = 0;
    for (const auto& p : pills) {
        if (p.name == item.name && p.rarity == item.rarity &&
            pillGradeDisplayName(p.grade) == grade) {
            total += p.quantity;
        }
    }
    return total;
}

/// 玩家灵石余额按收购项显示名解析（Kotlin warehouseSpiritStoneCount；
/// 未知名称 0，负值钳制 0）
inline int32_t warehouseSpiritStoneCount(const gamecore::state::GameData& gd,
                                         const gamecore::state::MerchantItem& item) {
    int64_t amount = 0;
    if (item.name == "下品灵石") {
        amount = gd.spiritStones;
    } else if (item.name == "中品灵石") {
        amount = gd.midGradeSpiritStones;
    } else if (item.name == "上品灵石") {
        amount = gd.highGradeSpiritStones;
    } else {
        return 0;
    }
    if (amount <= 0) return 0;
    return amount > INT32_MAX ? INT32_MAX : static_cast<int32_t>(amount);
}

/// 从堆叠序列按名称+品阶(+丹药品级)过滤未锁定项顺序扣减（Kotlin removeMatching）
template <typename T, typename Predicate>
void removeMatching(std::vector<T>& store, int32_t amount, Predicate match) {
    int32_t remaining = amount;
    std::vector<T> kept;
    kept.reserve(store.size());
    for (auto& item : store) {
        if (remaining > 0 && match(item)) {
            const int32_t deduct = std::min(remaining, item.quantity);
            const int32_t newQuantity = item.quantity - deduct;
            remaining -= deduct;
            if (newQuantity > 0) {
                item.quantity = newQuantity;
                kept.push_back(std::move(item));
            }
        } else {
            kept.push_back(std::move(item));
        }
    }
    store = std::move(kept);
}

/// 上架条目自增占位 id（确定性；扫描已有集合规避碰撞）
inline std::string nextListedItemId(const gamecore::state::GameData& gd) {
    int32_t n = static_cast<int32_t>(gd.playerListedItems.size()) + 1;
    while (true) {
        const std::string candidate = "gc-listed-" + std::to_string(n);
        bool taken = false;
        for (const auto& it : gd.playerListedItems) {
            if (it.id == candidate) {
                taken = true;
                break;
            }
        }
        if (!taken) return candidate;
        ++n;
    }
}

/// 已上架同条目的数量合计（Kotlin alreadyListed 求和）
inline int32_t alreadyListedQuantity(const gamecore::state::GameData& gd,
                                     const std::string& itemId,
                                     const std::string& type) {
    int32_t total = 0;
    for (const auto& it : gd.playerListedItems) {
        if (it.itemId == itemId && it.type == type) total += it.quantity;
    }
    return total;
}

}  // namespace detail

/// 事务结果信封（失败零写入）
struct TxOutcome {
    bool ok = false;
    const char* errorType = "";
    std::string message;
    int64_t earned = 0;
};

/// 单类出售事务（Kotlin sellEquipment/sellManual/sellPill/sellMaterial/
/// sellHerb/sellSeed 的 sellStack 等价）。未成交不是错误——Kotlin 原实现
/// 返回 false，故信封 SUCCESS + sold=false（调用方按 Boolean 语义消费）。
inline TxOutcome sellItemTx(gamecore::state::GameState& st, const std::string& itemType,
                            const std::string& itemId, int32_t quantity) {
    TxOutcome out;
    const int64_t amount =
        detail::deductByType(st, itemType, itemId, quantity);
    if (amount <= 0) {
        out.ok = true;  // 未成交（存在性/锁定/数量守卫未过）——零写入
        out.earned = 0;
        return out;
    }
    SpiritStoneWallet::add(st.gameData, amount, SpiritStoneGrade::LOW,
                           "Sell(" + itemType + ")");
    out.ok = true;
    out.earned = amount;
    return out;
}

/// 批量出售请求条目（Kotlin InventoryFacade.BulkSellOperation + name）
struct BulkSellRequest {
    std::string id;
    std::string name;
    std::string itemType;
    int32_t quantity = 0;
};

/// 批量出售结果（Kotlin InventoryFacade.BulkSellResult）
struct BulkSellOutcome {
    bool ok = false;
    int32_t soldCount = 0;
    int64_t totalEarned = 0;
    std::vector<std::string> soldItemNames;
    std::vector<std::string> failedItemNames;
};

/// 批量出售事务（Kotlin bulkSellItems 等价：逐条扣减零入账，末尾一次入账）
inline BulkSellOutcome bulkSellTx(gamecore::state::GameState& st,
                                  const std::vector<BulkSellRequest>& operations) {
    BulkSellOutcome out;
    for (const auto& op : operations) {
        const int64_t earned =
            detail::deductByType(st, op.itemType, op.id, op.quantity);
        if (earned > 0) {
            out.totalEarned += earned;
            out.soldCount += 1;
            out.soldItemNames.push_back(op.name + " " + std::to_string(op.quantity));
        } else {
            out.failedItemNames.push_back(op.name);
        }
    }
    if (out.totalEarned > 0) {
        SpiritStoneWallet::add(st.gameData, out.totalEarned, SpiritStoneGrade::LOW,
                               "Sell(bulk)");
    }
    out.ok = true;
    return out;
}

/// 商人收购结果信封
struct MerchantSellOutcome {
    bool ok = false;
    const char* errorType = "";
    std::string message;
    int32_t soldQuantity = 0;
    int64_t totalPrice = 0;
};

/// 仓库可售数量（Kotlin warehouseCount）
inline int32_t warehouseCount(const gamecore::state::GameState& st,
                              const gamecore::state::MerchantItem& item) {
    const std::string type = toLowerAscii(item.type);
    if (type == "equipment") return detail::countWarehouseStacks(st.equipmentStacks, item);
    if (type == "manual") return detail::countWarehouseStacks(st.manualStacks, item);
    if (type == "pill") return detail::countWarehousePills(st.pills, item);
    if (type == "material") return detail::countWarehouseStacks(st.materials, item);
    if (type == "herb") return detail::countWarehouseStacks(st.herbs, item);
    if (type == "seed") return detail::countWarehouseStacks(st.seeds, item);
    if (type == "spiritstone") return detail::warehouseSpiritStoneCount(st.gameData, item);
    return 0;
}

/// 出售扣减仓库库存（Kotlin deductSoldStock：堆叠类扣仓库堆叠，灵石类扣玩家余额）
inline void deductSoldStock(gamecore::state::GameState& st,
                            const gamecore::state::MerchantItem& item,
                            int32_t amount) {
    const std::string type = toLowerAscii(item.type);
    if (type == "equipment" || type == "manual" || type == "material" ||
        type == "herb" || type == "seed") {
        auto match = [&item](const auto& s) {
            return s.name == item.name && s.rarity == item.rarity && !s.isLocked;
        };
        if (type == "equipment") {
            detail::removeMatching(st.equipmentStacks, amount, match);
        } else if (type == "manual") {
            detail::removeMatching(st.manualStacks, amount, match);
        } else if (type == "material") {
            detail::removeMatching(st.materials, amount, match);
        } else if (type == "herb") {
            detail::removeMatching(st.herbs, amount, match);
        } else {
            detail::removeMatching(st.seeds, amount, match);
        }
        return;
    }
    if (type == "pill") {
        const std::string grade = item.grade.value_or("");
        detail::removeMatching(st.pills, amount,
                               [&item, &grade](const gamecore::state::Pill& p) {
                                   return p.name == item.name && p.rarity == item.rarity &&
                                          pillGradeDisplayName(p.grade) == grade &&
                                          !p.isLocked;
                               });
        return;
    }
    if (type == "spiritstone") {
        // Kotlin deductSoldSpiritStones：中品/上品直接扣余额（下限 0），下品无分支
        if (item.name == "中品灵石") {
            const int64_t next = st.gameData.midGradeSpiritStones - amount;
            st.gameData.midGradeSpiritStones = next > 0 ? next : 0;
        } else if (item.name == "上品灵石") {
            const int64_t next = st.gameData.highGradeSpiritStones - amount;
            st.gameData.highGradeSpiritStones = next > 0 ? next : 0;
        }
    }
}

/// 商人收购事务（Kotlin sellToMerchant 等价）。非法参数/收购项缺失 →
/// failure 信封（Kotlin 回退臂重执行校验链并静默返回）；actualQuantity<=0
/// 为正常"无可售"——SUCCESS + soldQuantity=0（零写入）。
inline MerchantSellOutcome sellToMerchantTx(gamecore::state::GameState& st,
                                            const std::string& acquisitionItemId,
                                            int32_t quantity) {
    MerchantSellOutcome out;
    const gamecore::state::MerchantItem* found = nullptr;
    for (const auto& it : st.gameData.merchantAcquisitionItems) {
        if (it.id == acquisitionItemId) {
            found = &it;
            break;
        }
    }
    if (found == nullptr) {
        out.errorType = "NotFound";
        out.message = "收购项不存在";
        return out;
    }
    // D-21 存档完整性防御：数量越界或价格非正（篡改档）拒绝收购
    if (quantity <= 0 || quantity > found->quantity || found->price <= 0) {
        out.errorType = "InvalidTradeRequest";
        out.message = "收购被拒:非法参数";
        return out;
    }
    const int32_t warehouseQuantity = warehouseCount(st, *found);
    int32_t actualQuantity = quantity;
    if (actualQuantity > warehouseQuantity) actualQuantity = warehouseQuantity;
    if (actualQuantity > found->quantity) actualQuantity = found->quantity;
    if (actualQuantity <= 0) {
        out.ok = true;
        return out;
    }
    deductSoldStock(st, *found, actualQuantity);
    const int64_t totalPrice = found->price * actualQuantity;
    SpiritStoneWallet::add(st.gameData, totalPrice, SpiritStoneGrade::LOW, "MerchantTrade");
    for (auto& it : st.gameData.merchantAcquisitionItems) {
        if (it.id == acquisitionItemId) it.quantity -= actualQuantity;
    }
    out.ok = true;
    out.soldQuantity = actualQuantity;
    out.totalPrice = totalPrice;
    return out;
}

/// 上架请求条目（Kotlin Pair<itemId, quantity>）
struct ListItemRequest {
    std::string itemId;
    int32_t quantity = 0;
};

/// 上架结果信封
struct ListItemsOutcome {
    bool ok = false;
    int32_t listedCount = 0;
};

/// 装备上架段（Kotlin listEquipmentForSale 语义：返回 false 表示非可上架装备，
/// 调用方继续尝试下一类型；true = 已处理完，含"已上架量超限静默跳过"）
inline bool listEquipmentForSale(gamecore::state::GameData& gd,
                                 const std::vector<gamecore::state::EquipmentStack>& stacks,
                                 const std::string& itemId, int32_t quantity,
                                 std::vector<gamecore::state::MerchantItem>& newItems) {
    const auto it = std::find_if(stacks.begin(), stacks.end(),
                                 [&itemId](const gamecore::state::EquipmentStack& s) {
                                     return s.id == itemId;
                                 });
    if (it == stacks.end()) return false;
    if (it->isLocked || quantity < 1 || quantity > it->quantity) return false;
    const int32_t alreadyListed =
        detail::alreadyListedQuantity(gd, itemId, "equipment");
    if (alreadyListed + quantity > it->quantity) return true;
    const auto* tpl = detail::equipmentTemplateByName(it->name);
    const int32_t original =
        tpl != nullptr ? tpl->price : kRarityBasePrice[rarityIndex(it->rarity)];
    gamecore::state::MerchantItem listed;
    listed.name = it->name;
    listed.type = "equipment";
    listed.itemId = itemId;
    listed.rarity = it->rarity;
    listed.price = roundToInt(static_cast<double>(original) * 0.8);
    listed.quantity = quantity;
    newItems.push_back(std::move(listed));
    return true;
}

/// 功法上架段（返回值语义同 listEquipmentForSale）
inline bool listManualForSale(gamecore::state::GameData& gd,
                              const std::vector<gamecore::state::ManualStack>& stacks,
                              const std::string& itemId, int32_t quantity,
                              std::vector<gamecore::state::MerchantItem>& newItems) {
    const auto it = std::find_if(stacks.begin(), stacks.end(),
                                 [&itemId](const gamecore::state::ManualStack& s) {
                                     return s.id == itemId;
                                 });
    if (it == stacks.end()) return false;
    if (it->isLocked || quantity < 1 || quantity > it->quantity) return false;
    const int32_t alreadyListed = detail::alreadyListedQuantity(gd, itemId, "manual");
    if (alreadyListed + quantity > it->quantity) return true;
    const auto* tpl = detail::manualTemplateByName(it->name);
    const int32_t original =
        tpl != nullptr ? tpl->price : kRarityBasePrice[rarityIndex(it->rarity)];
    gamecore::state::MerchantItem listed;
    listed.name = it->name;
    listed.type = "manual";
    listed.itemId = itemId;
    listed.rarity = it->rarity;
    listed.price = roundToInt(static_cast<double>(original) * 0.8);
    listed.quantity = quantity;
    newItems.push_back(std::move(listed));
    return true;
}

/// 丹药上架段（基准价取丹药基准×品阶倍率；返回值语义同上）
inline bool listPillForSale(gamecore::state::GameData& gd,
                            const std::vector<gamecore::state::Pill>& pills,
                            const std::string& itemId, int32_t quantity,
                            std::vector<gamecore::state::MerchantItem>& newItems) {
    const auto it = std::find_if(pills.begin(), pills.end(),
                                 [&itemId](const gamecore::state::Pill& p) {
                                     return p.id == itemId;
                                 });
    if (it == pills.end()) return false;
    if (it->isLocked || quantity < 1 || quantity > it->quantity) return false;
    const int32_t alreadyListed = detail::alreadyListedQuantity(gd, itemId, "pill");
    if (alreadyListed + quantity > it->quantity) return true;
    const double original = static_cast<double>(kRarityBasePrice[rarityIndex(it->rarity)]) *
                            pillGradeMultiplier(it->grade);
    gamecore::state::MerchantItem listed;
    listed.name = it->name;
    listed.type = "pill";
    listed.itemId = itemId;
    listed.rarity = it->rarity;
    listed.price = roundToInt(original * 0.8);
    listed.quantity = quantity;
    listed.grade = pillGradeDisplayName(it->grade);
    newItems.push_back(std::move(listed));
    return true;
}

/// 玩家上架事务（Kotlin listItemsToMerchant 等价：不扣仓库，仅登记）
inline ListItemsOutcome listItemsToMerchantTx(gamecore::state::GameState& st,
                                              const std::vector<ListItemRequest>& items) {
    ListItemsOutcome out;
    std::vector<gamecore::state::MerchantItem> newItems;
    for (const auto& req : items) {
        if (listEquipmentForSale(st.gameData, st.equipmentStacks, req.itemId,
                                 req.quantity, newItems)) {
            continue;
        }
        if (listManualForSale(st.gameData, st.manualStacks, req.itemId, req.quantity,
                              newItems)) {
            continue;
        }
        listPillForSale(st.gameData, st.pills, req.itemId, req.quantity, newItems);
    }
    if (!newItems.empty()) {
        for (auto& item : newItems) {
            // 占位 id 在入列处统一分配——扫描含本批已入列条目，批内不碰撞
            item.id = detail::nextListedItemId(st.gameData);
            st.gameData.playerListedItems.push_back(std::move(item));
        }
    }
    out.ok = true;
    out.listedCount = static_cast<int32_t>(newItems.size());
    return out;
}

/// 撤下上架项（Kotlin removePlayerListedItem 等价）
inline void removePlayerListedItemTx(gamecore::state::GameState& st,
                                     const std::string& itemId) {
    std::vector<gamecore::state::MerchantItem> kept;
    kept.reserve(st.gameData.playerListedItems.size());
    for (auto& it : st.gameData.playerListedItems) {
        if (it.id != itemId) kept.push_back(std::move(it));
    }
    st.gameData.playerListedItems = std::move(kept);
}

/// 按名称+品阶消耗材料（Kotlin consumeMaterialByName 等价）：
/// 过滤未锁定的同名同阶堆叠，按列表序逐摞扣减（扣至 0 移除）。
/// **入口快照语义**——Kotlin 的 `materials.all().filter{}` 先在事务内取
/// 快照再逐个 remove/update，故扣减量以**快照时的持有量**为准；C++ 同样
/// 先收集快照再应用（边迭代边 erase 是 UB，且会漏扣）。
/// @return 是否恰好扣满（remaining == 0；quantity<=0 时恒 false 且零写入，
///         quantity==0 时恒 true 且零写入——与 Kotlin 逐字一致）
inline bool consumeMaterialByNameTx(gamecore::state::GameState& st,
                                    const std::string& name, int32_t rarity,
                                    int32_t quantity) {
    int32_t remaining = quantity;
    // 入口快照：id + 快照持有量（Kotlin filter 产出的不可变列表）
    std::vector<std::pair<std::string, int32_t>> snapshot;
    for (const auto& mat : st.materials) {
        if (mat.name == name && mat.rarity == rarity && !mat.isLocked) {
            snapshot.emplace_back(mat.id, mat.quantity);
        }
    }
    for (const auto& [id, heldQuantity] : snapshot) {
        if (remaining <= 0) break;
        const int32_t take = std::min(remaining, heldQuantity);
        const int32_t newQuantity = heldQuantity - take;
        if (newQuantity <= 0) {
            st.materials.erase(
                std::remove_if(st.materials.begin(), st.materials.end(),
                               [&id](const gamecore::state::Material& m) {
                                   return m.id == id;
                               }),
                st.materials.end());
        } else {
            for (auto& mat : st.materials) {
                if (mat.id == id) {
                    mat.quantity = newQuantity;
                    break;
                }
            }
        }
        remaining -= take;
    }
    return remaining == 0;
}

// ══ 库存域收官事务（batch-11：商人购买 / 充公）══════════════════

namespace detail {

/// 模板存在性探测（Kotlin MerchantItemConverter 模板分支可达性——模板缺失
/// 回退走 JVM Random.Default（generateRandom 族）不可复刻 → C++ 必须以
/// TemplateMiss failure 信封回退 Kotlin 原路径）。spiritstone/未知类型无
/// 模板参与（路径确定性），恒 true。丹药查找镜像 toPill 的
/// getRecipeByNameAndGrade → getRecipeByName 两段回退。
inline bool merchantTemplateExists(const gamecore::state::MerchantItem& item) {
    const std::string type = toLowerAscii(item.type);
    if (type == "equipment") {
        return equipmentTemplateByName(item.name) != nullptr;
    }
    if (type == "manual") {
        return manualTemplateByName(item.name) != nullptr;
    }
    if (type == "pill") {
        const std::string gradeName =
            merchant_settle::gradeNameFromDisplay(item.grade);
        std::string gradeLower = gradeName;
        std::transform(gradeLower.begin(), gradeLower.end(), gradeLower.begin(),
                       [](unsigned char c) {
                           return static_cast<char>(std::tolower(c));
                       });
        const auto& recipes = gamecore::data::pillRecipes();
        for (const auto& r : recipes) {
            if (r.name == item.name && r.grade == gradeLower) return true;
        }
        for (const auto& r : recipes) {
            if (r.name == item.name) return true;
        }
        return false;
    }
    if (type == "material") {
        for (const auto& t : gamecore::data::beastMaterialTemplates()) {
            if (t.name == item.name) return true;
        }
        return false;
    }
    if (type == "herb") {
        for (const auto& t : gamecore::data::herbTemplates()) {
            if (t.name == item.name) return true;
        }
        return false;
    }
    if (type == "seed") {
        for (const auto& t : gamecore::data::seedTemplates()) {
            if (t.name == item.name) return true;
        }
        return false;
    }
    return true;
}

/// Kotlin InventorySystem.returnEquipmentToStack 等价——**裸 store.add**：
/// 不校验（实例 quantity 恒 1）、不记年度报表（绕过 addEquipmentStack 的
/// annual 段——充公实例路径 Kotlin 无年报写入，禁用 addXxx 错记）。
/// 溢出抑制由调用方实参表达（充公上下文 withOverflowMailSuppressed）。
inline InventoryResult<gamecore::state::EquipmentStack> confiscateReturnEquipmentInstance(
    gamecore::state::GameState& st, const gamecore::state::EquipmentInstance& inst,
    OverflowMailCollector& mail) {
    const int32_t otherTypes = static_cast<int32_t>(
        st.manualStacks.size() + st.pills.size() + st.materials.size() +
        st.herbs.size() + st.seeds.size());
    StackableItemStore<gamecore::state::EquipmentStack> store(
        st.equipmentStacks, equipmentKey, getMaxStackSize("equipment_stack"),
        [&]() { return computeMaxSlots(st) - otherTypes; });
    const auto item = sr_session::detail::equipmentInstanceToStack(inst);
    auto result = store.add(item);
    st.equipmentStacks = store.all();
    handleOverflow(result, "equipment", item, mail, "confiscate",
                   /*overflowMailSuppressed=*/true);
    return result;
}

/// Kotlin InventorySystem.returnManualToStack 等价（裸 store.add 同上）
inline InventoryResult<gamecore::state::ManualStack> confiscateReturnManualInstance(
    gamecore::state::GameState& st, const gamecore::state::ManualInstance& inst,
    OverflowMailCollector& mail) {
    const int32_t otherTypes = static_cast<int32_t>(
        st.equipmentStacks.size() + st.pills.size() + st.materials.size() +
        st.herbs.size() + st.seeds.size());
    StackableItemStore<gamecore::state::ManualStack> store(
        st.manualStacks, manualKey, getMaxStackSize("manual_stack"),
        [&]() { return computeMaxSlots(st) - otherTypes; });
    const auto item = sr_session::detail::manualInstanceToStack(inst);
    auto result = store.add(item);
    st.manualStacks = store.all();
    handleOverflow(result, "manual", item, mail, "confiscate",
                   /*overflowMailSuppressed=*/true);
    return result;
}

}  // namespace detail

/// 商人购买结果信封（奖励卡片所需字段随信封回传——卡片构造留 Kotlin，
/// S6 战报同口径；溢出草稿经信封 overflowDrafts → InventoryNativeForward
/// 同一投递通道）
struct MerchantBuyOutcome {
    bool ok = false;
    const char* errorType = "";
    std::string message;
    bool bought = false;
    std::string itemName;
    std::string itemType;
    int32_t rarity = 0;
    int32_t quantity = 0;
    std::vector<OverflowDraft> overflowDrafts;
};

/// 商人购买事务（Kotlin buyMerchantItem 等价——判定序/容量预测/先加后扣/
/// 商家库存扣减逐字对齐）。商人模板转换器与按型容量谓词复用
/// merchant_settle 同源实现（与 Kotlin MerchantItemConverter 对拍锁定）。
/// 所有校验先行，任一失败零写入；失败信封 → Kotlin 回退臂重执行校验链
///（静默 no-op 或模板缺失随机回退，用户可见文案由 Kotlin 臂产出）。
inline MerchantBuyOutcome buyMerchantItemTx(gamecore::state::GameState& st,
                                            const std::string& itemId,
                                            int32_t quantity) {
    MerchantBuyOutcome out;
    // ① 商品存在（Kotlin find ?: log + return）
    const gamecore::state::MerchantItem* found = nullptr;
    for (const auto& it : st.gameData.travelingMerchantItems) {
        if (it.id == itemId) {
            found = &it;
            break;
        }
    }
    if (found == nullptr) {
        out.errorType = "NotFound";
        out.message = "购买失败:商品不存在 itemId=" + itemId;
        return out;
    }
    // 快照（后续 gameData 变更防御性取值——商人列表条目在末尾才擦除）
    const gamecore::state::MerchantItem item = *found;
    // ② D-21 存档完整性防御：商人商品价格本应恒正，篡改档负价/0 价拒绝
    if (item.price <= 0 || quantity <= 0) {
        out.errorType = "InvalidParam";
        out.message = "购买被拒:非法价格或数量";
        return out;
    }
    // ③ 灵石/库存早退（Kotlin gameData.spiritStones < cost——仅下品余额，
    //    不含自动换算；实际扣 deduct(autoConvert=true) 但本臂已保证
    //    straight-line Success）
    const int64_t cost = static_cast<int64_t>(
        static_cast<uint64_t>(item.price) * static_cast<uint64_t>(quantity));
    if (st.gameData.spiritStones < cost || quantity > item.quantity) {
        out.errorType = "Insufficient";
        out.message = "购买被拒:灵石不足或库存不足";
        return out;
    }
    const std::string type = toLowerAscii(item.type);
    // ④ 模板存在性：模板缺失回退走 Kotlin JVM Random.Default——不可复刻，
    //    failure 信封回退（禁止近似复刻，RNG 红线）
    if (!detail::merchantTemplateExists(item)) {
        out.errorType = "TemplateMiss";
        out.message = "商品模板缺失:" + item.name;
        return out;
    }

    // ⑤⑥ 按型转换 + 容量预测 + 入库（单分支执行——预测先于写入，预测
    //    失败零写入；转换一次，Kotlin 转换两次系确定性重复，单次等价）
    OverflowMailCollector mail;
    bool addOk = true;
    if (type == "equipment") {
        auto stack = merchant_settle::toEquipment(item);
        stack.quantity = quantity;
        if (!merchant_settle::canAddEquipment(st, stack.name, stack.rarity,
                                              stack.slot)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addEquipmentStack(st, stack, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "manual") {
        auto stack = merchant_settle::toManual(item);
        stack.quantity = quantity;
        if (!merchant_settle::canAddManual(st, stack.name, stack.rarity,
                                           stack.type)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addManualStack(st, stack, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "pill") {
        auto pill = merchant_settle::toPill(item);
        pill.quantity = quantity;
        if (!merchant_settle::canAddPill(st, pill.name, pill.rarity,
                                         pill.category, pill.grade)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addPill(st, pill, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "material") {
        auto material = merchant_settle::toMaterial(item);
        material.quantity = quantity;
        if (!merchant_settle::canAddMaterial(st, material.name, material.rarity,
                                             material.category)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addMaterial(st, material, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "herb") {
        auto herb = merchant_settle::toHerb(item);
        herb.quantity = quantity;
        if (!merchant_settle::canAddHerb(st, herb.name, herb.rarity,
                                         herb.category)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addHerb(st, herb, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "seed") {
        auto seed = merchant_settle::toSeed(item);
        seed.quantity = quantity;
        if (!merchant_settle::canAddSeed(st, seed.name, seed.rarity,
                                         seed.growTime)) {
            out.errorType = "CapacityFull";
            out.message = "购买被拒:仓库容量不足";
            return out;
        }
        const auto r = addSeed(st, seed, mail, "merchant", false);
        addOk = r.status != InventoryStatus::kFailure;
    } else if (type == "spiritstone") {
        // Kotlin grantMerchantSpiritStones：中品/上品直加余额（Long+Int 回绕
        // 语义；下品/未知名称 no-op 但 addOk 保持 true，照常扣费）
        const int64_t addend = static_cast<int64_t>(quantity);
        if (item.name == "中品灵石") {
            st.gameData.midGradeSpiritStones = static_cast<int64_t>(
                static_cast<uint64_t>(st.gameData.midGradeSpiritStones) +
                static_cast<uint64_t>(addend));
        } else if (item.name == "上品灵石") {
            st.gameData.highGradeSpiritStones = static_cast<int64_t>(
                static_cast<uint64_t>(st.gameData.highGradeSpiritStones) +
                static_cast<uint64_t>(addend));
        }
        addOk = true;
    } else {
        // 未知类型：Kotlin else 分支 no-op（addOk 保持 true，照常扣费）
        addOk = true;
    }
    // 物品添加完全失败（零合并且仓库满，溢出已转邮件）→ 不扣灵石，不更新
    // 商家库存（Kotlin addOk=false → return@update；C++ addXxx Failure 段
    // 无状态变更，零写入成立）
    if (!addOk) {
        out.errorType = "AddFailed";
        out.message = "购买失败:物品入库失败 " + item.name;
        return out;
    }

    // ⑦ 扣灵石（先加物品后扣——Kotlin 语义序；Purchase/MerchantTrade，
    //    autoConvert 默认 true）。③ 保证 straight-line Success，预演为
    //    零写入防御臂：预演失败即 failure 信封（Kotlin 回退臂重执行时同
    //    臂拒绝，收敛零写入；Kotlin 对应分支为不可达死码）
    {
        gamecore::state::GameData probe = st.gameData;
        if (SpiritStoneWallet::deduct(probe, cost, SpiritStoneGrade::LOW,
                                      "Purchase",
                                      "MerchantTrade").status !=
            DeductStatus::kSuccess) {
            out.errorType = "DeductFailed";
            out.message = "购买失败:扣费失败";
            return out;
        }
    }
    SpiritStoneWallet::deduct(st.gameData, cost, SpiritStoneGrade::LOW,
                              "Purchase", "MerchantTrade");

    // ⑧ 商家库存扣减（Kotlin reduceMerchantStock：quantity >= stock 移除
    //    条目，否则减量）
    for (auto it = st.gameData.travelingMerchantItems.begin();
         it != st.gameData.travelingMerchantItems.end();) {
        if (it->id == itemId) {
            if (quantity >= it->quantity) {
                it = st.gameData.travelingMerchantItems.erase(it);
            } else {
                it->quantity -= quantity;
                ++it;
            }
        } else {
            ++it;
        }
    }

    out.ok = true;
    out.bought = true;
    out.itemName = item.name;
    out.itemType = item.type;
    out.rarity = item.rarity;
    out.quantity = quantity;
    out.overflowDrafts = mail.takeAll();
    return out;
}

/// 充公结果信封（总以 SUCCESS 返回——Kotlin 全臂静默，confiscated 区分
/// Success 落地与静默 no-op；溢出抑制上下文恒无草稿）
struct ConfiscateOutcome {
    bool ok = true;
    bool confiscated = false;
};

/// 充公事务（Kotlin confiscateStorageBagItem 等价——幂等探测/三态物化/
/// 溢出抑制/仅 Success 移除袋条目逐字对齐）。
/// - 幂等防线：以袋内当前条目为准——入参 itemId 无匹配即已没收（陈旧
///   UI 快照防复制），静默 no-op
/// - 实例条目：裸 store.add 回仓（不校验/不记年报——Kotlin
///   returnEquipmentToStack/returnManualToStack 语义，勿用 addXxx 错记）
/// - 堆叠条目：sr_session::detail::reconstructStackedItem 模板重建 + quantity=1
///   （Kotlin BagItemReconstructor.reconstruct().copy(quantity=1)），走
///   addXxx（含年报 trackingSource="confiscate" 与校验链）
/// - 溢出抑制（凭据类语义）：Partial/Failure 保留袋条目待重试（已入仓
///   部分合并不重复），仅 Success 才移除
inline ConfiscateOutcome confiscateStorageBagItemTx(gamecore::state::GameState& st,
                                                    const std::string& discipleId,
                                                    const std::string& itemId) {
    ConfiscateOutcome out;
    gamecore::state::DiscipleStore& ds = st.disciples;
    // Kotlin: discipleId.toIntOrNull() ?: return（静默 no-op）
    if (!settle_util::toIntOrNull(discipleId).has_value()) return out;
    // Kotlin: !discipleTables.ids.contains(id) return
    const auto rowOpt = ds.rowOf(discipleId);
    if (!rowOpt.has_value()) return out;
    auto& bag = ds.storageBagItems[*rowOpt];
    // 幂等探测：袋内已无匹配条目 = 已没收（防双击/重复调用物品复制）
    gamecore::state::StorageBagItem* current = nullptr;
    for (auto& e : bag) {
        if (e.itemId == itemId) {
            current = &e;
            break;
        }
    }
    if (current == nullptr) return out;
    // 快照（移除段写 bag，防指针失效）
    const gamecore::state::StorageBagItem currentItem = *current;
    const bool hasInstance = currentItem.equipmentInstance.has_value() ||
                             currentItem.manualInstance.has_value();
    // 堆叠条目篡改防御：数量 <=0 且无实例 → 拒绝物化（防 0 数量白得物品）
    if (!hasInstance && currentItem.quantity <= 0) return out;

    OverflowMailCollector mail;  // 溢出抑制上下文——恒空（凭据类路径不发邮件）
    InventoryStatus status = InventoryStatus::kFailure;
    if (currentItem.equipmentInstance.has_value()) {
        status = detail::confiscateReturnEquipmentInstance(
                     st, *currentItem.equipmentInstance, mail)
                     .status;
    } else if (currentItem.manualInstance.has_value()) {
        status = detail::confiscateReturnManualInstance(
                     st, *currentItem.manualInstance, mail)
                     .status;
    } else {
        gamecore::state::EquipmentStack eqs;
        gamecore::state::ManualStack mns;
        gamecore::state::Pill ps;
        gamecore::state::Herb hs;
        gamecore::state::Seed ss;
        gamecore::state::Material ms;
        if (!sr_session::detail::reconstructStackedItem(currentItem, &eqs, &mns, &ps,
                                                &hs, &ss, &ms)) {
            // 模板缺失（堆叠类条目无法重建，丢弃处理）：保留袋条目
            status = InventoryStatus::kFailure;
        } else {
            const std::string t = toLowerAscii(currentItem.itemType);
            if (t == "equipment" || t == "equipment_stack") {
                eqs.quantity = 1;
                status = addEquipmentStack(st, eqs, mail, "confiscate", true).status;
            } else if (t == "manual" || t == "manual_stack") {
                mns.quantity = 1;
                status = addManualStack(st, mns, mail, "confiscate", true).status;
            } else if (t == "pill") {
                ps.quantity = 1;
                status = addPill(st, ps, mail, "confiscate", true).status;
            } else if (t == "herb") {
                hs.quantity = 1;
                status = addHerb(st, hs, mail, "confiscate", true).status;
            } else if (t == "seed") {
                ss.quantity = 1;
                status = addSeed(st, ss, mail, "confiscate", true).status;
            } else if (t == "material") {
                ms.quantity = 1;
                status = addMaterial(st, ms, mail, "confiscate", true).status;
            } else {
                status = InventoryStatus::kFailure;  // 未知类型保守保留（Kotlin 不可达）
            }
        }
    }

    // 仅 Success 才移除袋条目（Kotlin applyConfiscationResult；Partial/
    // Failure/模板缺失保留待重试——C1 防复制）
    if (status == InventoryStatus::kSuccess) {
        if (hasInstance) {
            // 实例条目：整条移除（实例不可分，防 quantity>1 实例重复没收复制）
            bag.erase(std::remove_if(bag.begin(), bag.end(),
                                     [&itemId](const gamecore::state::StorageBagItem& e) {
                                         return e.itemId == itemId;
                                     }),
                      bag.end());
        } else {
            // 堆叠条目：每次没收 1 个（Kotlin StorageBagUtils.decreaseItemQuantity）
            for (auto it = bag.begin(); it != bag.end(); ++it) {
                if (it->itemId == itemId) {
                    if (it->quantity - 1 > 0) {
                        it->quantity -= 1;
                    } else {
                        bag.erase(it);
                    }
                    break;
                }
            }
        }
        out.confiscated = true;
    }
    return out;
}

}  // namespace gamecore::system::inventory_tx
