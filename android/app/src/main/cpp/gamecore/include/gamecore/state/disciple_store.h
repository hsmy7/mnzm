#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

// 注意：本头文件**不包含** models.h——Disciple 及嵌套类型（StorageBagItem 等）
// 由 models.h 在本文件之前完整定义（models.h 文件尾包含本头文件，循环依赖
// 通过 include guard 消解）。独立包含本头文件会因 Disciple 不完整而编译失败，
// 必须经 `#include "gamecore/state/models.h"` 引入。

// ============================================================
// DiscipleStore — 弟子 SoA 列式存储（计划 v2 阶段 3：实体存储数据导向化）
//
// 替换 `GameState::disciples: std::vector<Disciple>`（AoS）。每列一个并行
// 数组（行序 == 弟子顺序），列访问零对象分配、缓存友好；id → 行索引
// std::map 有序（确定性）。materialize(row) 按需物化完整 Disciple 供
// 按值入参的公式函数（disciple_stats.h 等）使用。
//
// ## 确定性红线（RNG 对拍命门）
// 1. **行序 == JSON 数组序 == Kotlin ids 序**——突破（BREAKTHROUGH 分区）与
//    伴侣配对（SYSTEM 分区）的 RNG 抽取序列依赖该顺序；禁止任何重排/压缩。
// 2. id → 行索引使用 std::map（有序）；禁止 unordered_map 参与业务迭代。
// 3. 同 id 保留最后一个（Kotlin SparseArray 写入语义，indexById 注释明示）。
//
// ## JSON 协议零变更
// to_json/from_json 仍输出"每弟子平铺对象数组"（与 kotlinx 双向兼容），
// 编解码经 materialize/appendDisciple 落盘到列存储。
// ============================================================
namespace gamecore::state {

class DiscipleStore {
public:
    // ── 标识列 ──
    std::vector<std::string> ids;                // 行序 == 弟子顺序（RNG 红线）
    // ── 基础信息列 ──
    std::vector<std::string> names;
    std::vector<std::string> surnames;
    std::vector<std::string> genders;
    std::vector<std::string> portraitRes;
    std::vector<std::string> discipleTypes;
    std::vector<std::string> spiritRootTypes;
    // ── 境界与修为 ──
    std::vector<int32_t> realms;
    std::vector<int32_t> realmLayers;
    std::vector<double> cultivations;
    std::vector<double> cultivationCheckpoints;
    std::vector<int32_t> cultivationCheckpointGameMonths;
    std::vector<int32_t> ages;
    std::vector<int32_t> lifespans;
    std::vector<int8_t> isAlive;                 // 0/1（Int 语义避免 bool 填充）
    std::vector<int32_t> deathYears;             // 0 = 无条目（Kotlin 稀疏组件表语义：仅已故弟子有值；不进 JSON 协议）
    // 0 = 从未判定（Kotlin DiscipleTables.lastTheftJudgementYears 稀疏表语义；
    // 不进 JSON 协议，读档即归零——与 Kotlin 会话级组件表一致）
    std::vector<int32_t> lastTheftJudgementYears;
    std::vector<int32_t> soulPowers;
    // ── 修炼加速 ──
    std::vector<double> cultivationSpeedBonuses;
    std::vector<int32_t> cultivationSpeedDurations;
    // ── 列表/映射列 ──
    std::vector<std::vector<std::string>> manualIds;
    std::vector<std::vector<std::string>> talentIds;
    std::vector<std::vector<std::string>> physiqueIds;
    std::vector<std::vector<std::string>> affixIds;
    std::vector<std::map<std::string, int32_t>> manualMasteries;
    std::vector<std::string> statuses;           // DiscipleStatus.name
    std::vector<std::map<std::string, std::string>> statusData;
    // ── 完成时间列 ──
    std::vector<int32_t> cultivationCompletionMonths;
    std::vector<int32_t> cultivationCompletionPhases;
    std::vector<int32_t> manualCompletionMonths;
    std::vector<int32_t> manualCompletionPhases;
    std::vector<int32_t> equipmentNurturingCompletionMonths;
    std::vector<int32_t> equipmentNurturingCompletionPhases;

    // ── CombatAttributes 列 ──
    std::vector<int32_t> baseHps;
    std::vector<int32_t> baseMps;
    std::vector<int32_t> basePhysicalAttacks;
    std::vector<int32_t> baseMagicAttacks;
    std::vector<int32_t> basePhysicalDefenses;
    std::vector<int32_t> baseMagicDefenses;
    std::vector<int32_t> baseSpeeds;
    std::vector<int32_t> hpVariances;
    std::vector<int32_t> mpVariances;
    std::vector<int32_t> physicalAttackVariances;
    std::vector<int32_t> magicAttackVariances;
    std::vector<int32_t> physicalDefenseVariances;
    std::vector<int32_t> magicDefenseVariances;
    std::vector<int32_t> speedVariances;
    std::vector<int64_t> totalCultivations;
    std::vector<int32_t> breakthroughCounts;
    std::vector<int32_t> breakthroughFailCounts;
    std::vector<int32_t> currentHps;
    std::vector<int32_t> currentMps;

    // ── PillEffects 列 ──
    std::vector<int32_t> pillPhysicalAttackBonuses;
    std::vector<int32_t> pillMagicAttackBonuses;
    std::vector<int32_t> pillPhysicalDefenseBonuses;
    std::vector<int32_t> pillMagicDefenseBonuses;
    std::vector<int32_t> pillHpBonuses;
    std::vector<int32_t> pillMpBonuses;
    std::vector<int32_t> pillSpeedBonuses;
    std::vector<double> pillCritRateBonuses;
    std::vector<double> pillCritEffectBonuses;
    std::vector<double> pillCultivationSpeedBonuses;
    std::vector<double> pillSkillExpSpeedBonuses;
    std::vector<double> pillNurtureSpeedBonuses;
    std::vector<int32_t> pillEffectDurations;
    std::vector<std::vector<std::string>> activePillTypes;
    std::vector<std::string> activePillCategories;

    // ── EquipmentSet 列 ──
    std::vector<std::string> weaponIds;
    std::vector<std::string> armorIds;
    std::vector<std::string> bootsIds;
    std::vector<std::string> accessoryIds;
    std::vector<EquipmentNurtureData> weaponNurtures;
    std::vector<EquipmentNurtureData> armorNurtures;
    std::vector<EquipmentNurtureData> bootsNurtures;
    std::vector<EquipmentNurtureData> accessoryNurtures;
    std::vector<std::vector<StorageBagItem>> storageBagItems;
    std::vector<int64_t> storageBagSpiritStones;
    std::vector<int32_t> spiritStones;

    // ── SocialData 列（""=null 哨兵，与序列化协议一致） ──
    std::vector<std::string> partnerIds;
    std::vector<std::string> partnerSectIds;
    std::vector<std::string> parentId1s;
    std::vector<std::string> parentId2s;
    std::vector<int32_t> lastChildYears;
    std::vector<int32_t> childBirthMonths;       // 0 = null 哨兵
    std::vector<int32_t> griefEndYears;          // -1 = null 哨兵
    std::vector<std::string> masterIds;

    // ── SkillStats 列 ──
    std::vector<int32_t> intelligences;
    std::vector<int32_t> charms;
    std::vector<int32_t> loyalties;
    std::vector<int32_t> comprehensions;
    std::vector<int32_t> artifactRefinings;
    std::vector<int32_t> pillRefinings;
    std::vector<int32_t> spiritPlantings;
    std::vector<int32_t> minings;
    std::vector<int32_t> teachings;
    std::vector<int32_t> moralities;
    std::vector<int32_t> aptitudes;
    std::vector<int32_t> salaryPaidCounts;
    std::vector<int32_t> salaryMissedCounts;
    std::vector<int32_t> alchemyLevels;
    std::vector<int32_t> alchemyPromotionCounts;
    std::vector<int32_t> forgeLevels;
    std::vector<int32_t> forgePromotionCounts;

    // ── UsageTracking 列 ──
    std::vector<std::vector<std::string>> usedPermanentPillKeys;
    std::vector<std::vector<std::string>> usedExtendLifePillTypes;
    std::vector<std::vector<std::string>> usedFunctionalPillTypes;
    std::vector<std::vector<std::string>> usedExtendLifePillIds;
    std::vector<int32_t> recruitedMonths;
    std::vector<int8_t> hasReviveEffects;        // 0/1
    std::vector<int8_t> hasClearAllEffects;      // 0/1

    // ── 索引 ──
    /// id → 行索引（std::map 有序；同 id 保留最后——SparseArray 写入语义）
    std::map<std::string, std::size_t> idToRow;

    // ============================================================
    // 容量/索引
    // ============================================================

    /// 弟子数（所有列等长；idToRow.size() 同值）
    std::size_t size() const { return idToRow.size(); }

    /// 行索引 → 弟子 id（行序即插入序，RNG 对拍红线）
    const std::string& idAt(std::size_t row) const { return ids[row]; }

    /// id → 行索引；不存在返回 npos
    std::optional<std::size_t> rowOf(const std::string& id) const {
        const auto it = idToRow.find(id);
        if (it == idToRow.end()) return std::nullopt;
        return it->second;
    }

    bool contains(const std::string& id) const { return idToRow.count(id) != 0; }

    // ============================================================
    // 物化/装载
    // ============================================================

    /// 物化完整 Disciple（公式函数按值入参用；热路径应直读列避免本调用）
    Disciple materialize(std::size_t row) const;

    /// 追加一个弟子（所有列 push_back + idToRow 登记；行序 = 追加序）
    void appendDisciple(const Disciple& d);

    /// 全量装载（清空后按序追加；JSON 导入/全量回导用）
    void loadFromVector(const std::vector<Disciple>& disciples);

    // ============================================================
    // 行级变更（保持行序：删除为原位 erase，新增为末尾追加）
    // ============================================================

    /// 按 id 原位覆盖或追加（upsert；applyReverseDirty 弟子通道用）
    void upsertDisciple(const Disciple& d);

    /// 按 id 删除（其余行序保留）
    void removeById(const std::string& id);

    /// 清空全部列与索引
    void clear();

private:
    /// 按行索引删除（removeById 内部；不做 idToRow 之外的校验）
    void eraseAt(std::size_t row);

    /// 交换两行完整数据（upsert 保序旋转用；各列逐元素 swap）
    void swapRows(std::size_t a, std::size_t b);
};

}  // namespace gamecore::state
