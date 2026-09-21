#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

// ============================================================
// gameview_encode — GameView protobuf 信封编码器（重构方案 R2.1/R2.2/R2.4）
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
//   - changed["disciples"] → discipleListDelta.upserts（行 typed 编码，
//     行字段表 kDiscipleRowFields 与 proto 字段号一一对应；全量树 = 全行
//     emit-always，列级树（R2.4/B09）= 仅脏列——行内逐键 presence 编码，
//     两形状 wire 层同构）；
//   - removed["disciples"] → discipleListDelta.removedIds；
//   - 其余 changed/removed 键（非 "gameData.*"/"disciples"）→
//     collectionChange（B18-P1 起 upsert 载荷 = upsertsTyped 通用 typed 行
//     ——TypedValue/TypedField/TypedRow 递归承载，与旧 JSON 协议逐值等价，
//     等价性守卫锁定；旧 upsertsJson bytes 已停写，号冻结永不复用）；
//     "gameData.*" 其余键 → gameDataChange（valueTyped 同法；旧 valueJson
//     bytes 已停写）；
//   - configEcho.snapshotSchemaVersion = GameCoreConfig.snapshotSchemaVersion；
//   - eventFeed（R2.4 转正）：[ViewEventDraft] 列表逐条编码为 ViewEvent
//     （type/gameYear/gameMonth/detailJson）——月/年结算信封 + 突破/死亡/
//     购买/秘境关闭事件入流；导出即消费（GameCore 队列在编码成功后清空）。
//
// ## 确定性约束
//   编码顺序 = 字段号升序 + nlohmann 对象键有序遍历（std::map 序）——
//   同一变更集树恒产出逐字节相同的信封（RNG/对拍红线对齐）。
// ============================================================
namespace gamecore::state {

/// proto `ViewEventType` 枚举值镜像（game_view.proto 只增不改纪律：
/// 字段号/枚举号冻结，C++ 编码面按号直写）
enum class ViewEventType : int32_t {
    kUnknown = 0,
    kMonthSettled = 1,
    kYearSettled = 2,
    kBreakthrough = 3,
    kDeath = 4,
    kPurchase = 5,
    kSecretRealmClosed = 6,
};

/// 待发事件草稿（GameCore eventFeed 队列元素；detailJson = 事件载荷 JSON
/// 原文——v1 过渡编码，typed 化时 proto 只增不改追加新字段）
struct ViewEventDraft {
    ViewEventType type = ViewEventType::kUnknown;
    int32_t gameYear = 0;
    int32_t gameMonth = 0;
    std::string detailJson;
};

/// 变更集树 → GameView protobuf 信封字节串。
/// [diff] 形如 {"version":N,"changed":{...},"removed":{...}}（缺失键按空
/// 处理，不抛异常；schemaVersion 空串时省略 configEcho 子消息）。
/// [eventFeed] 非空时逐条产出块 3 `repeated ViewEvent eventFeed`（缺省
/// nullptr = 不产出——与 R2.1/R2.2 历史字节保持一致，对拍桥/旧用例零影响）。
std::string encodeGameView(const nlohmann::json& diff, const std::string& schemaVersion,
                           const std::vector<ViewEventDraft>* eventFeed = nullptr);

}  // namespace gamecore::state
