#pragma once

#include <nlohmann/json.hpp>

#include "gamecore/state/models.h"

// ============================================================
// JSON 快照编解码（Kotlin→C++ 迁移批次 1）
//
// 协议：nlohmann/json ↔ kotlinx.serialization JSON（字段名一致）。
//   - to_json：输出**全部已覆盖字段**（与 kotlinx encodeDefaults=true 对齐）
//   - from_json：**宽松**（未知/缺失字段忽略，用默认值）——未覆盖字段的
//     嵌套对象（后续批次补齐）不会破坏导入
//
// 顶层快照结构（GameState）：
//   { "gameData": {...}, "disciples": [...], "equipmentStacks": [...], ... }
// ============================================================
namespace gamecore::state {

/// 宽松读取辅助：字段存在则读入（否则保持目标默认值）
template <typename T>
inline void readField(const nlohmann::json& j, const char* key, T& out) {
    if (j.contains(key) && !j.at(key).is_null()) {
        j.at(key).get_to(out);
    }
}

void to_json(nlohmann::json& j, const EquipmentStack& v);
void from_json(const nlohmann::json& j, EquipmentStack& v);
void to_json(nlohmann::json& j, const EquipmentInstance& v);
void from_json(const nlohmann::json& j, EquipmentInstance& v);
void to_json(nlohmann::json& j, const ManualStack& v);
void from_json(const nlohmann::json& j, ManualStack& v);
void to_json(nlohmann::json& j, const ManualInstance& v);
void from_json(const nlohmann::json& j, ManualInstance& v);
void to_json(nlohmann::json& j, const Pill& v);
void from_json(const nlohmann::json& j, Pill& v);
void to_json(nlohmann::json& j, const Material& v);
void from_json(const nlohmann::json& j, Material& v);
void to_json(nlohmann::json& j, const Herb& v);
void from_json(const nlohmann::json& j, Herb& v);
void to_json(nlohmann::json& j, const Seed& v);
void from_json(const nlohmann::json& j, Seed& v);
void to_json(nlohmann::json& j, const StorageBag& v);
void from_json(const nlohmann::json& j, StorageBag& v);

void to_json(nlohmann::json& j, const Disciple& v);
void from_json(const nlohmann::json& j, Disciple& v);

void to_json(nlohmann::json& j, const DirectDiscipleSlot& v);
void from_json(const nlohmann::json& j, DirectDiscipleSlot& v);
void to_json(nlohmann::json& j, const ElderSlots& v);
void from_json(const nlohmann::json& j, ElderSlots& v);
void to_json(nlohmann::json& j, const SectPolicies& v);
void from_json(const nlohmann::json& j, SectPolicies& v);
void to_json(nlohmann::json& j, const ProductionSlot& v);
void from_json(const nlohmann::json& j, ProductionSlot& v);
void to_json(nlohmann::json& j, const GridBuildingData& v);
void from_json(const nlohmann::json& j, GridBuildingData& v);
void to_json(nlohmann::json& j, const MerchantItem& v);
void from_json(const nlohmann::json& j, MerchantItem& v);
void to_json(nlohmann::json& j, const Alliance& v);
void from_json(const nlohmann::json& j, Alliance& v);
void to_json(nlohmann::json& j, const VassalContract& v);
void from_json(const nlohmann::json& j, VassalContract& v);
void to_json(nlohmann::json& j, const SectRelation& v);
void from_json(const nlohmann::json& j, SectRelation& v);
void to_json(nlohmann::json& j, const WorldSect& v);
void from_json(const nlohmann::json& j, WorldSect& v);
void to_json(nlohmann::json& j, const ResidenceSlot& v);
void from_json(const nlohmann::json& j, ResidenceSlot& v);
void to_json(nlohmann::json& j, const SpiritFieldPlant& v);
void from_json(const nlohmann::json& j, SpiritFieldPlant& v);
void to_json(nlohmann::json& j, const PatrolConfig& v);
void from_json(const nlohmann::json& j, PatrolConfig& v);
void to_json(nlohmann::json& j, const WorldLevel& v);
void from_json(const nlohmann::json& j, WorldLevel& v);
void to_json(nlohmann::json& j, const MailClaimRecord& v);
void from_json(const nlohmann::json& j, MailClaimRecord& v);
void to_json(nlohmann::json& j, const SectLevelClaimRecord& v);
void from_json(const nlohmann::json& j, SectLevelClaimRecord& v);
void to_json(nlohmann::json& j, const YearlyReport& v);
void from_json(const nlohmann::json& j, YearlyReport& v);
void to_json(nlohmann::json& j, const PendingTraitAdd& v);
void from_json(const nlohmann::json& j, PendingTraitAdd& v);

void to_json(nlohmann::json& j, const BloodRefinementProgress& v);
void from_json(const nlohmann::json& j, BloodRefinementProgress& v);
void to_json(nlohmann::json& j, const BloodRefinementBonusTotal& v);
void from_json(const nlohmann::json& j, BloodRefinementBonusTotal& v);
void to_json(nlohmann::json& j, const BloodRefinementPctTotal& v);
void from_json(const nlohmann::json& j, BloodRefinementPctTotal& v);
void to_json(nlohmann::json& j, const ManualProficiencyData& v);
void from_json(const nlohmann::json& j, ManualProficiencyData& v);
void to_json(nlohmann::json& j, const SpiritMineSlot& v);
void from_json(const nlohmann::json& j, SpiritMineSlot& v);
void to_json(nlohmann::json& j, const PatrolSlot& v);
void from_json(const nlohmann::json& j, PatrolSlot& v);

// 批次 1 剩余：远古秘境状态机
void to_json(nlohmann::json& j, const SecretRealmState& v);
void from_json(const nlohmann::json& j, SecretRealmState& v);
void to_json(nlohmann::json& j, const SecretRealmMemberState& v);
void from_json(const nlohmann::json& j, SecretRealmMemberState& v);
void to_json(nlohmann::json& j, const SecretRealmOption& v);
void from_json(const nlohmann::json& j, SecretRealmOption& v);
void to_json(nlohmann::json& j, const SecretRealmRewardItem& v);
void from_json(const nlohmann::json& j, SecretRealmRewardItem& v);
void to_json(nlohmann::json& j, const SecretRealmAIMember& v);
void from_json(const nlohmann::json& j, SecretRealmAIMember& v);
void to_json(nlohmann::json& j, const SecretRealmEventParams& v);
void from_json(const nlohmann::json& j, SecretRealmEventParams& v);
void to_json(nlohmann::json& j, const SecretRealmEventRecord& v);
void from_json(const nlohmann::json& j, SecretRealmEventRecord& v);
void to_json(nlohmann::json& j, const SecretRealmBackpack& v);
void from_json(const nlohmann::json& j, SecretRealmBackpack& v);
void to_json(nlohmann::json& j, const SecretRealmExplorationSession& v);
void from_json(const nlohmann::json& j, SecretRealmExplorationSession& v);
void to_json(nlohmann::json& j, const SecretRealmAITeam& v);
void from_json(const nlohmann::json& j, SecretRealmAITeam& v);

void to_json(nlohmann::json& j, const GameData& v);
void from_json(const nlohmann::json& j, GameData& v);

void to_json(nlohmann::json& j, const GameState& v);
void from_json(const nlohmann::json& j, GameState& v);

/// 导出 GameState 为 JSON 文本（含浮点规范化）。
///
/// 规范化：整数值的 double（如 12000.0）转为整数形式（12000）——
/// kotlinx.serialization 的流式解码器（1.7.x）对 "N.0" 格式数字有缺陷
/// （Unexpected symbol '.' in numeric literal），非整数值（12345.6）保留。
std::string dumpStateJson(const GameState& v);

}  // namespace gamecore::state
