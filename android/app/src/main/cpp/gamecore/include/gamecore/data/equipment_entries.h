// 装备 216 条展开条目（五行属性伤害系统）——派生逻辑头（非生成件：由
// equipment_db.h 的 36 部件 × 品阶 1..6 展开，展开式与 Kotlin
// EquipmentDatabase.expand 逐字段一致）。
// AI 轻量实例 / 按部位选件 / 商店与外交贸易查询共用此面。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"

namespace gamecore::data {

/// 部件 × 品阶展开后的可生成条目（216 条；Kotlin EquipPieceEntry 镜像）
struct EquipPieceEntry {
    std::string id;        // "{pieceId}_r{rarity}"
    std::string pieceId;   // 所属部件 id
    std::string setId;
    std::string part;      // 四部位 EquipmentSlot.name
    int32_t rarity = 1;
    std::string name;
    std::string description;
    int32_t price = 0;
    int32_t minRealm = 9;
};

inline bool operator==(const EquipPieceEntry& a, const EquipPieceEntry& b) {
    return a.id == b.id && a.pieceId == b.pieceId && a.setId == b.setId &&
           a.part == b.part && a.rarity == b.rarity && a.name == b.name &&
           a.description == b.description && a.price == b.price &&
           a.minRealm == b.minRealm;
}

/// 全部展开条目（顺序 = 部件声明序 × 品阶 1..6——nextInt 抽取序基准，禁重排）
inline const std::vector<EquipPieceEntry>& equipmentEntries() {
    static const std::vector<EquipPieceEntry> kEntries = [] {
        std::vector<EquipPieceEntry> out;
        out.reserve(setPieceTemplates().size() * 6);
        for (const SetPieceTemplate& piece : setPieceTemplates()) {
            for (int32_t r = 1; r <= 6; ++r) {
                EquipPieceEntry e;
                e.id = piece.id + "_r" + std::to_string(r);
                e.pieceId = piece.id;
                e.setId = piece.setId;
                e.part = piece.part;
                e.rarity = r;
                e.name = piece.name;
                e.description = piece.description;
                e.price = piece.priceByRarity[r - 1];
                e.minRealm = piece.minRealmByRarity[r - 1];
                out.push_back(std::move(e));
            }
        }
        return out;
    }();
    return kEntries;
}

/// 条目 id → 条目（未命中返回 nullptr；线性扫，216 条规模）
inline const EquipPieceEntry* equipmentEntryById(const std::string& id) {
    if (id.empty()) return nullptr;
    for (const EquipPieceEntry& e : equipmentEntries()) {
        if (e.id == id) return &e;
    }
    return nullptr;
}

/// 按部位取条目（顺序 = equipmentEntries() 声明序过滤——抽取序与 Kotlin
/// EquipmentDatabase.getBySlot 一致）
inline std::vector<const EquipPieceEntry*> equipmentEntriesByPart(
        const std::string& part) {
    std::vector<const EquipPieceEntry*> out;
    for (const EquipPieceEntry& e : equipmentEntries()) {
        if (e.part == part) out.push_back(&e);
    }
    return out;
}

}  // namespace gamecore::data
