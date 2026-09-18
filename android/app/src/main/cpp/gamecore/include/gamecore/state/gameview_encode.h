#pragma once

#include <string>

#include <nlohmann/json.hpp>

// ============================================================
// gameview_encode — GameView protobuf 信封编码器（重构方案 R2.1/R2.2）
//
// 把 DirtyTracker 变更集树（diffToTree 产物：{version, changed, removed}，
// 已过 normalizeIntegralFloats）编码为 GameView proto（wire format）字节串。
// schema 唯一权威 = core/engine/src/main/proto/game_view.proto；本文件是
// 其 C++ 手写编码面（gamecore 零三方依赖 ⇒ 不链 libprotobuf，proto wire
// format 仅为 varint/tag/长度前缀，自写编码器 ~百行；字节级正确性由
// gameview_encode_test.cpp golden 断言 + Kotlin 侧 javalite 真解码
// （DiffDirtyEnvelopeEquivalenceTest）双端锁定）。
//
// ## 语义契约（与旧 JSON 变更集协议逐值等价，等价性守卫锁定）
//   - version → GameView.version（同源同语义：单调 +1、导出即消费）；
//   - changed["gameData.spiritStones"] → resourcesHeader.spiritStones
//     （presence 即"本封已变化"）；
//   - changed["disciples"] → discipleListDelta.upserts（全行 typed 编码，
//     行字段表 kDiscipleRowFields 与 proto 字段号一一对应，emit-always）；
//   - removed["disciples"] → discipleListDelta.removedIds；
//   - 其余 changed/removed 键（非 "gameData.*"/"disciples"）→
//     collectionChange（upsertsJson = 实体数组 JSON 原文，与旧协议逐字节
//     同值）；"gameData.*" 其余键 → gameDataChange（valueJson 同上）；
//   - configEcho.snapshotSchemaVersion = GameCoreConfig.snapshotSchemaVersion；
//   - eventFeed 本批不产出（schema 预留，R2.4 接线）。
//
// ## 确定性约束
//   编码顺序 = 字段号升序 + nlohmann 对象键有序遍历（std::map 序）——
//   同一变更集树恒产出逐字节相同的信封（RNG/对拍红线对齐）。
// ============================================================
namespace gamecore::state {

/// 变更集树 → GameView protobuf 信封字节串。
/// [diff] 形如 {"version":N,"changed":{...},"removed":{...}}（缺失键按空
/// 处理，不抛异常；schemaVersion 空串时省略 configEcho 子消息）。
std::string encodeGameView(const nlohmann::json& diff, const std::string& schemaVersion);

}  // namespace gamecore::state
