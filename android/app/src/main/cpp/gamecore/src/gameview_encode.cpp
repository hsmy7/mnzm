#include "gamecore/state/gameview_encode.h"

#include <cstdint>
#include <cstring>
#include <string>

#include <nlohmann/json.hpp>

namespace gamecore::state {

namespace {

using nlohmann::json;

// ============================================================
// ProtoWriter — protobuf wire format 最小编码器
//
// 仅覆盖 GameView schema 所需的 wire type：
//   0 = varint（int32/int64/bool/uint64）、1 = 64-bit（double fixed64）、
//   2 = 长度前缀（string/bytes/子消息）。proto3 scalar 默认 packed 编码
//   仅作用于数值 repeated——本 schema 的 repeated 全为字符串/消息条目
//   （逐条 tag，无 packed），故无需 packed 支持。
// ============================================================
class ProtoWriter {
public:
    std::string take() { return std::move(out_); }

    void uint64Field(uint32_t field, uint64_t value) {
        tag(field, kWireVarint);
        varint(value);
    }

    void int64Field(uint32_t field, int64_t value) {
        tag(field, kWireVarint);
        varint(static_cast<uint64_t>(value));
    }

    void int32Field(uint32_t field, int32_t value) {
        tag(field, kWireVarint);
        // proto3 负数按 64 位补码 varint（10 字节）编码
        varint(static_cast<uint64_t>(static_cast<int64_t>(value)));
    }

    void boolField(uint32_t field, bool value) {
        tag(field, kWireVarint);
        varint(value ? 1u : 0u);
    }

    void doubleField(uint32_t field, double value) {
        tag(field, kWireFixed64);
        uint64_t bits = 0;
        static_assert(sizeof(bits) == sizeof(value), "double 必须为 64 位");
        std::memcpy(&bits, &value, sizeof(bits));
        for (int i = 0; i < 8; ++i) {
            out_.push_back(static_cast<char>((bits >> (8 * i)) & 0xFFu));
        }
    }

    void stringField(uint32_t field, const std::string& value) {
        lengthDelimited(field, value.data(), value.size());
    }

    void bytesField(uint32_t field, const std::string& payload) {
        lengthDelimited(field, payload.data(), payload.size());
    }

    /// 子消息：先编码进局部 writer，再作为长度前缀字段写入
    void messageField(uint32_t field, const ProtoWriter& sub) {
        const std::string& payload = sub.out_;
        lengthDelimited(field, payload.data(), payload.size());
    }

private:
    static constexpr uint32_t kWireVarint = 0;
    static constexpr uint32_t kWireFixed64 = 1;
    static constexpr uint32_t kWireLen = 2;

    void tag(uint32_t field, uint32_t wire) { varint((static_cast<uint64_t>(field) << 3) | wire); }

    void lengthDelimited(uint32_t field, const char* data, std::size_t size) {
        tag(field, kWireLen);
        varint(size);
        out_.append(data, size);
    }

    void varint(uint64_t v) {
        while (v >= 0x80u) {
            out_.push_back(static_cast<char>((v & 0x7Fu) | 0x80u));
            v >>= 7;
        }
        out_.push_back(static_cast<char>(v));
    }

    std::string out_;
};

// ── DiscipleRow 字段编码表 ──────────────────────────────────────────

/// 行字段 wire 类别（与 game_view.proto DiscipleRow 字段号一一对应）
enum class RowKind : uint8_t {
    kString,
    kInt32,
    kInt64,
    kBool,
    kDouble,
    kStringList,
    kStringIntMap,
    kStringStringMap,
    kNurture,     ///< EquipmentNurtureDataView 子消息
    kBytesJson,   ///< 字段值 JSON 原文（storageBagItems 过渡编码）
};

struct RowField {
    const char* key;    ///< Disciple to_json 协议键（= JSON 变更集行内键）
    uint32_t no;        ///< proto 字段号（game_view.proto 契约，发布后冻结）
    RowKind kind;
};

/// 表序 = proto 字段号升序（编码顺序确定性）；键集与 Disciple to_json
/// 全量协议字段一致（column_dirty.h DiscipleColumn 双射族 + deathYear）。
constexpr RowField kDiscipleRowFields[] = {
    {"id", 1, RowKind::kString},
    {"name", 2, RowKind::kString},
    {"surname", 3, RowKind::kString},
    {"realm", 4, RowKind::kInt32},
    {"realmLayer", 5, RowKind::kInt32},
    {"cultivation", 6, RowKind::kDouble},
    {"cultivationCheckpoint", 7, RowKind::kInt64},
    {"cultivationCheckpointGameMonth", 8, RowKind::kInt32},
    {"spiritRootType", 9, RowKind::kString},
    {"age", 10, RowKind::kInt32},
    {"lifespan", 11, RowKind::kInt32},
    {"isAlive", 12, RowKind::kBool},
    {"deathYear", 13, RowKind::kInt32},
    {"gender", 14, RowKind::kString},
    {"portraitRes", 15, RowKind::kString},
    {"manualIds", 16, RowKind::kStringList},
    {"talentIds", 17, RowKind::kStringList},
    {"physiqueIds", 18, RowKind::kStringList},
    {"affixIds", 19, RowKind::kStringList},
    {"manualMasteries", 20, RowKind::kStringIntMap},
    {"status", 21, RowKind::kString},
    {"statusData", 22, RowKind::kStringStringMap},
    {"cultivationSpeedBonus", 23, RowKind::kDouble},
    {"cultivationSpeedDuration", 24, RowKind::kInt32},
    {"discipleType", 25, RowKind::kString},
    {"soulPower", 26, RowKind::kInt32},
    {"cultivationCompletionMonth", 27, RowKind::kInt32},
    {"cultivationCompletionPhase", 28, RowKind::kInt32},
    {"manualCompletionMonth", 29, RowKind::kInt32},
    {"manualCompletionPhase", 30, RowKind::kInt32},
    {"equipmentNurturingCompletionMonth", 31, RowKind::kInt32},
    {"equipmentNurturingCompletionPhase", 32, RowKind::kInt32},
    {"baseHp", 33, RowKind::kInt32},
    {"baseMp", 34, RowKind::kInt32},
    {"basePhysicalAttack", 35, RowKind::kInt32},
    {"baseMagicAttack", 36, RowKind::kInt32},
    {"basePhysicalDefense", 37, RowKind::kInt32},
    {"baseMagicDefense", 38, RowKind::kInt32},
    {"baseSpeed", 39, RowKind::kInt32},
    {"hpVariance", 40, RowKind::kInt32},
    {"mpVariance", 41, RowKind::kInt32},
    {"physicalAttackVariance", 42, RowKind::kInt32},
    {"magicAttackVariance", 43, RowKind::kInt32},
    {"physicalDefenseVariance", 44, RowKind::kInt32},
    {"magicDefenseVariance", 45, RowKind::kInt32},
    {"speedVariance", 46, RowKind::kInt32},
    {"totalCultivation", 47, RowKind::kInt64},
    {"breakthroughCount", 48, RowKind::kInt32},
    {"breakthroughFailCount", 49, RowKind::kInt32},
    {"currentHp", 50, RowKind::kInt32},
    {"currentMp", 51, RowKind::kInt32},
    {"pillPhysicalAttackBonus", 52, RowKind::kInt32},
    {"pillMagicAttackBonus", 53, RowKind::kInt32},
    {"pillPhysicalDefenseBonus", 54, RowKind::kInt32},
    {"pillMagicDefenseBonus", 55, RowKind::kInt32},
    {"pillHpBonus", 56, RowKind::kInt32},
    {"pillMpBonus", 57, RowKind::kInt32},
    {"pillSpeedBonus", 58, RowKind::kInt32},
    {"pillCritRateBonus", 59, RowKind::kDouble},
    {"pillCritEffectBonus", 60, RowKind::kDouble},
    {"pillCultivationSpeedBonus", 61, RowKind::kDouble},
    {"pillSkillExpSpeedBonus", 62, RowKind::kDouble},
    {"pillNurtureSpeedBonus", 63, RowKind::kDouble},
    {"pillEffectDuration", 64, RowKind::kInt32},
    {"activePillTypes", 65, RowKind::kStringList},
    {"activePillCategory", 66, RowKind::kString},
    {"weaponId", 67, RowKind::kString},
    {"armorId", 68, RowKind::kString},
    {"bootsId", 69, RowKind::kString},
    {"accessoryId", 70, RowKind::kString},
    {"weaponNurture", 71, RowKind::kNurture},
    {"armorNurture", 72, RowKind::kNurture},
    {"bootsNurture", 73, RowKind::kNurture},
    {"accessoryNurture", 74, RowKind::kNurture},
    {"storageBagItems", 75, RowKind::kBytesJson},
    {"storageBagSpiritStones", 76, RowKind::kInt64},
    {"spiritStones", 77, RowKind::kInt32},
    {"partnerId", 78, RowKind::kString},
    {"partnerSectId", 79, RowKind::kString},
    {"parentId1", 80, RowKind::kString},
    {"parentId2", 81, RowKind::kString},
    {"lastChildYear", 82, RowKind::kInt32},
    {"childBirthMonth", 83, RowKind::kInt32},
    {"griefEndYear", 84, RowKind::kInt32},
    {"masterId", 85, RowKind::kString},
    {"intelligence", 86, RowKind::kInt32},
    {"charm", 87, RowKind::kInt32},
    {"loyalty", 88, RowKind::kInt32},
    {"comprehension", 89, RowKind::kInt32},
    {"artifactRefining", 90, RowKind::kInt32},
    {"pillRefining", 91, RowKind::kInt32},
    {"spiritPlanting", 92, RowKind::kInt32},
    {"mining", 93, RowKind::kInt32},
    {"teaching", 94, RowKind::kInt32},
    {"morality", 95, RowKind::kInt32},
    {"aptitude", 96, RowKind::kInt32},
    {"salaryPaidCount", 97, RowKind::kInt32},
    {"salaryMissedCount", 98, RowKind::kInt32},
    {"alchemyLevel", 99, RowKind::kInt32},
    {"alchemyPromotionCount", 100, RowKind::kInt32},
    {"forgeLevel", 101, RowKind::kInt32},
    {"forgePromotionCount", 102, RowKind::kInt32},
    {"usedPermanentPillKeys", 103, RowKind::kStringList},
    {"usedExtendLifePillTypes", 104, RowKind::kStringList},
    {"usedFunctionalPillTypes", 105, RowKind::kStringList},
    {"usedExtendLifePillIds", 106, RowKind::kStringList},
    {"recruitedMonth", 107, RowKind::kInt32},
    {"hasReviveEffect", 108, RowKind::kBool},
    {"hasClearAllEffect", 109, RowKind::kBool},
};

// ── 值级编码辅助 ────────────────────────────────────────────────────

void encodeNurture(uint32_t field, const json& value, ProtoWriter& out) {
    if (!value.is_object()) return;
    ProtoWriter sub;
    const auto idIt = value.find("equipmentId");
    if (idIt != value.end() && idIt->is_string()) {
        sub.stringField(1, idIt->get<std::string>());
    }
    const auto rarityIt = value.find("rarity");
    if (rarityIt != value.end() && rarityIt->is_number()) {
        sub.int32Field(2, rarityIt->get<int64_t>());
    }
    const auto levelIt = value.find("nurtureLevel");
    if (levelIt != value.end() && levelIt->is_number()) {
        sub.int32Field(3, levelIt->get<int64_t>());
    }
    const auto progressIt = value.find("nurtureProgress");
    if (progressIt != value.end() && progressIt->is_number()) {
        sub.doubleField(4, progressIt->get<double>());
    }
    out.messageField(field, sub);
}

void encodeRowField(const RowField& f, const json& row, ProtoWriter& out) {
    const auto it = row.find(f.key);
    if (it == row.end() || it->is_null()) return;  // 缺键 = 不携带（presence 语义）
    const json& v = *it;
    switch (f.kind) {
        case RowKind::kString:
            if (v.is_string()) out.stringField(f.no, v.get<std::string>());
            return;
        case RowKind::kInt32:
            if (v.is_number()) out.int32Field(f.no, v.get<int64_t>());
            return;
        case RowKind::kInt64:
            if (v.is_number()) out.int64Field(f.no, v.get<int64_t>());
            return;
        case RowKind::kBool:
            if (v.is_boolean()) out.boolField(f.no, v.get<bool>());
            return;
        case RowKind::kDouble:
            if (v.is_number()) out.doubleField(f.no, v.get<double>());
            return;
        case RowKind::kStringList:
            if (!v.is_array()) return;
            for (const json& e : v) {
                if (e.is_string()) out.stringField(f.no, e.get<std::string>());
            }
            return;
        case RowKind::kStringIntMap:
            if (!v.is_object()) return;
            for (auto entry = v.begin(); entry != v.end(); ++entry) {
                if (!entry.value().is_number()) continue;
                ProtoWriter sub;
                sub.stringField(1, entry.key());
                sub.int32Field(2, entry.value().get<int64_t>());
                out.messageField(f.no, sub);
            }
            return;
        case RowKind::kStringStringMap:
            if (!v.is_object()) return;
            for (auto entry = v.begin(); entry != v.end(); ++entry) {
                if (!entry.value().is_string()) continue;
                ProtoWriter sub;
                sub.stringField(1, entry.key());
                sub.stringField(2, entry.value().get<std::string>());
                out.messageField(f.no, sub);
            }
            return;
        case RowKind::kNurture:
            encodeNurture(f.no, v, out);
            return;
        case RowKind::kBytesJson:
            // storageBagItems：数组 JSON 原文（与旧 JSON 协议逐字节同值）
            if (v.is_array()) out.bytesField(f.no, v.dump());
            return;
    }
}

void encodeDiscipleRow(const json& row, ProtoWriter& out) {
    if (!row.is_object()) return;
    for (const RowField& f : kDiscipleRowFields) {
        encodeRowField(f, row, out);
    }
}

bool isGameDataPath(const std::string& key) {
    return key.rfind("gameData.", 0) == 0;
}

}  // namespace

std::string encodeGameView(const json& diff, const std::string& schemaVersion) {
    ProtoWriter out;

    // field 1: version
    uint64_t version = 0;
    if (diff.is_object()) {
        const auto vIt = diff.find("version");
        if (vIt != diff.end() && vIt->is_number()) version = vIt->get<uint64_t>();
    }
    out.uint64Field(1, version);

    const json empty = json::object();
    const json changed = diff.is_object() && diff.contains("changed") && diff.at("changed").is_object()
        ? diff.at("changed") : empty;
    const json removed = diff.is_object() && diff.contains("removed") && diff.at("removed").is_object()
        ? diff.at("removed") : empty;

    // field 2: resourcesHeader（v1 = spiritStones；presence 即"本封已变化"）
    const auto stonesIt = changed.find("gameData.spiritStones");
    if (stonesIt != changed.end() && stonesIt->is_number()) {
        ProtoWriter header;
        header.int64Field(1, stonesIt->get<int64_t>());
        out.messageField(2, header);
    }

    // field 3: discipleListDelta（upsert typed 全行 + removed id 列表）
    const auto disciplesIt = changed.find("disciples");
    const auto disciplesRemovedIt = removed.find("disciples");
    const bool hasUpserts = disciplesIt != changed.end() && disciplesIt->is_array();
    const bool hasRemoved = disciplesRemovedIt != removed.end() && disciplesRemovedIt->is_array();
    if (hasUpserts || hasRemoved) {
        ProtoWriter delta;
        if (hasUpserts) {
            for (const json& row : *disciplesIt) {
                ProtoWriter rowWriter;
                encodeDiscipleRow(row, rowWriter);
                delta.messageField(1, rowWriter);
            }
        }
        if (hasRemoved) {
            for (const json& id : *disciplesRemovedIt) {
                if (id.is_string()) delta.stringField(2, id.get<std::string>());
            }
        }
        out.messageField(3, delta);
    }

    // field 4: eventFeed —— 本批 schema 预留不产出（R2.4 接线）

    // field 5: configEcho（schemaVersion 非空才携带）
    if (!schemaVersion.empty()) {
        ProtoWriter echo;
        echo.stringField(1, schemaVersion);
        out.messageField(5, echo);
    }

    // field 6: collectionChange —— disciples 之外的实体集合（通用承载：
    // 现有 9 集合 + 未来新增集合自动覆盖；changed/removed 键均有序遍历）
    for (auto it = changed.begin(); it != changed.end(); ++it) {
        if (isGameDataPath(it.key()) || it.key() == "disciples") continue;
        if (!it.value().is_array()) continue;
        ProtoWriter entry;
        entry.stringField(1, it.key());
        entry.bytesField(2, it.value().dump());
        const auto rmIt = removed.find(it.key());
        if (rmIt != removed.end() && rmIt->is_array()) {
            for (const json& id : *rmIt) {
                if (id.is_string()) entry.stringField(3, id.get<std::string>());
            }
        }
        out.messageField(6, entry);
    }
    for (auto it = removed.begin(); it != removed.end(); ++it) {
        if (isGameDataPath(it.key()) || it.key() == "disciples") continue;
        if (!it.value().is_array()) continue;
        if (changed.contains(it.key()) && changed.at(it.key()).is_array()) continue;  // 已随 upsert 条目携带
        ProtoWriter entry;
        entry.stringField(1, it.key());
        for (const json& id : *it) {
            if (id.is_string()) entry.stringField(3, id.get<std::string>());
        }
        out.messageField(6, entry);
    }

    // field 7: gameDataChange —— resourcesHeader 未覆盖的 gameData 字段
    //（嵌套容器整体替换语义；载荷 = 字段值 JSON 原文）
    for (auto it = changed.begin(); it != changed.end(); ++it) {
        if (!isGameDataPath(it.key())) continue;
        if (it.key() == "gameData.spiritStones") continue;
        ProtoWriter entry;
        entry.stringField(1, it.key().substr(9));  // 去掉 "gameData." 前缀
        entry.bytesField(2, it.value().dump());
        out.messageField(7, entry);
    }

    return out.take();
}

}  // namespace gamecore::state
