#include "gamecore/state/gameview_encode.h"

#include <cstdint>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>
#include <gtest/gtest.h>

// ============================================================
// gameview_encode_test — GameView protobuf 编码器 wire format 锁定
//
// 策略：内置最小 protobuf wire 解码器，把 encodeGameView 的字节产出解析回
// (field_number, wire_type, value) 序列，逐字段断言——既锁定字节级正确性，
// 又证明产出是**合法 protobuf wire**（能被任意标准解析器接受）。跨语言一致性
// 由 Kotlin 侧 javalite 真解码（DiffDirtyEnvelopeEquivalenceTest）二次锁定。
//
// schema 唯一权威 = core/engine/src/main/proto/game_view.proto；本测试与
// gameview_encode.cpp 的 kDiscipleRowFields 编码表共同构成契约的双向校验。
// ============================================================
namespace {

using nlohmann::json;
using gamecore::state::encodeGameView;

struct DecodedField {
    uint32_t wire = 0;             ///< 0=varint, 1=fixed64, 2=length-delimited
    uint64_t varint = 0;           ///< wire==0
    uint64_t fixed64 = 0;          ///< wire==1
    std::string bytes;             ///< wire==2（string/bytes/子消息）
};

/// 解析一段字节为 (字段号, 值) 序列（保序，重复字段按出现序返回）。
/// 解析失败（截断/非法 wire）返回 false。
bool decodeFields(const std::string& in,
                  std::vector<std::pair<uint32_t, DecodedField>>& out) {
    std::size_t pos = 0;
    const auto readVarint = [&](uint64_t& v) -> bool {
        v = 0;
        int shift = 0;
        while (pos < in.size()) {
            const uint8_t b = static_cast<uint8_t>(in[pos++]);
            v |= static_cast<uint64_t>(b & 0x7Fu) << shift;
            if ((b & 0x80u) == 0) return true;
            shift += 7;
            if (shift > 63) return false;
        }
        return false;
    };
    while (pos < in.size()) {
        uint64_t key = 0;
        if (!readVarint(key)) return false;
        DecodedField f;
        f.wire = static_cast<uint32_t>(key & 0x7u);
        const uint32_t field = static_cast<uint32_t>(key >> 3);
        switch (f.wire) {
            case 0:  // varint
                if (!readVarint(f.varint)) return false;
                break;
            case 1: {  // 64-bit
                if (pos + 8 > in.size()) return false;
                uint64_t bits = 0;
                for (int i = 0; i < 8; ++i) {
                    bits |= static_cast<uint64_t>(static_cast<uint8_t>(in[pos + i])) << (8 * i);
                }
                pos += 8;
                f.fixed64 = bits;
                break;
            }
            case 2: {  // length-delimited
                uint64_t len = 0;
                if (!readVarint(len)) return false;
                if (pos + len > in.size()) return false;
                f.bytes = in.substr(pos, static_cast<std::size_t>(len));
                pos += static_cast<std::size_t>(len);
                break;
            }
            default:
                return false;
        }
        out.emplace_back(field, std::move(f));
    }
    return true;
}

/// 取指定字段号的全部出现（保序）。
std::vector<DecodedField> fieldsWith(
    const std::vector<std::pair<uint32_t, DecodedField>>& fs, uint32_t field) {
    std::vector<DecodedField> r;
    for (const auto& kv : fs) {
        if (kv.first == field) r.push_back(kv.second);
    }
    return r;
}

int64_t asInt64(uint64_t v) { return static_cast<int64_t>(v); }
double asDouble(uint64_t bits) {
    double d = 0;
    static_assert(sizeof(d) == sizeof(bits), "double 必须为 64 位");
    std::memcpy(&d, &bits, sizeof(d));
    return d;
}

class GameViewEncodeTest : public ::testing::Test {};

// ── 空信封：仅 version=0 ───────────────────────────────────────────
TEST_F(GameViewEncodeTest, EmptyDiffEncodesVersionZeroOnly) {
    const std::string bytes = encodeGameView(json::object(), "");
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(bytes, fs));
    ASSERT_EQ(1u, fs.size());
    EXPECT_EQ(1u, fs[0].first);          // field 1 = version
    EXPECT_EQ(0u, fs[0].second.wire);    // varint
    EXPECT_EQ(0u, fs[0].second.varint);  // version 0
}

// ── version + resourcesHeader.spiritStones ────────────────────────
TEST_F(GameViewEncodeTest, VersionAndSpiritStonesHeader) {
    const json tree = {
        {"version", 3},
        {"changed", {{"gameData.spiritStones", 12345}}},
        {"removed", json::object()},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));

    auto ver = fieldsWith(fs, 1);
    ASSERT_EQ(1u, ver.size());
    EXPECT_EQ(3u, ver[0].varint);

    auto hdr = fieldsWith(fs, 2);  // resourcesHeader
    ASSERT_EQ(1u, hdr.size());
    std::vector<std::pair<uint32_t, DecodedField>> sub;
    ASSERT_TRUE(decodeFields(hdr[0].bytes, sub));
    auto stones = fieldsWith(sub, 1);  // ResourcesHeader.spiritStones int64
    ASSERT_EQ(1u, stones.size());
    EXPECT_EQ(12345, asInt64(stones[0].varint));
}

// ── discipleListDelta：typed 行 + removedIds ──────────────────────
TEST_F(GameViewEncodeTest, DiscipleRowTypedFields) {
    const json row = {
        {"id", "7"},
        {"name", "玄真"},
        {"realm", 5},
        {"cultivation", 1.5},
        {"isAlive", true},
        {"manualIds", {"a", "b"}},
        {"manualMasteries", {{"m1", 3}, {"m2", 7}}},
        {"statusData", {{"k", "v"}}},
        {"weaponNurture", {{"equipmentId", "w1"}, {"rarity", 2}, {"nurtureLevel", 4}, {"nurtureProgress", 0.25}}},
        {"storageBagItems", {{{"id", "s1"}, {"count", 9}}}},
        {"spiritStones", 88},
    };
    const json tree = {
        {"version", 9},
        {"changed", {{"disciples", json::array({row})}}},
        {"removed", {{"disciples", {"7", "8"}}}},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));

    auto delta = fieldsWith(fs, 3);  // DiscipleListDelta
    ASSERT_EQ(1u, delta.size());
    std::vector<std::pair<uint32_t, DecodedField>> d;
    ASSERT_TRUE(decodeFields(delta[0].bytes, d));

    auto upserts = fieldsWith(d, 1);  // upserts (DiscipleRow)
    ASSERT_EQ(1u, upserts.size());
    std::vector<std::pair<uint32_t, DecodedField>> r;
    ASSERT_TRUE(decodeFields(upserts[0].bytes, r));

    EXPECT_EQ("7", fieldsWith(r, 1)[0].bytes);      // id
    EXPECT_EQ("玄真", fieldsWith(r, 2)[0].bytes);   // name
    ASSERT_EQ(1u, fieldsWith(r, 4).size());
    EXPECT_EQ(5, asInt64(fieldsWith(r, 4)[0].varint));  // realm int32
    ASSERT_EQ(1u, fieldsWith(r, 6).size());
    EXPECT_DOUBLE_EQ(1.5, asDouble(fieldsWith(r, 6)[0].fixed64));  // cultivation double
    ASSERT_EQ(1u, fieldsWith(r, 12).size());
    EXPECT_EQ(1u, fieldsWith(r, 12)[0].varint);  // isAlive true
    auto manuals = fieldsWith(r, 16);            // manualIds repeated string
    ASSERT_EQ(2u, manuals.size());
    EXPECT_EQ("a", manuals[0].bytes);
    EXPECT_EQ("b", manuals[1].bytes);
    auto mastery = fieldsWith(r, 20);            // manualMasteries StringIntEntry
    ASSERT_EQ(2u, mastery.size());
    std::vector<std::pair<uint32_t, DecodedField>> e0;
    ASSERT_TRUE(decodeFields(mastery[0].bytes, e0));
    EXPECT_EQ("m1", fieldsWith(e0, 1)[0].bytes);
    EXPECT_EQ(3, asInt64(fieldsWith(e0, 2)[0].varint));
    auto status = fieldsWith(r, 22);             // statusData StringStringEntry
    ASSERT_EQ(1u, status.size());
    std::vector<std::pair<uint32_t, DecodedField>> se;
    ASSERT_TRUE(decodeFields(status[0].bytes, se));
    EXPECT_EQ("k", fieldsWith(se, 1)[0].bytes);
    EXPECT_EQ("v", fieldsWith(se, 2)[0].bytes);
    auto nurture = fieldsWith(r, 71);            // weaponNurture
    ASSERT_EQ(1u, nurture.size());
    std::vector<std::pair<uint32_t, DecodedField>> ne;
    ASSERT_TRUE(decodeFields(nurture[0].bytes, ne));
    EXPECT_EQ("w1", fieldsWith(ne, 1)[0].bytes);
    EXPECT_DOUBLE_EQ(0.25, asDouble(fieldsWith(ne, 4)[0].fixed64));
    auto bag = fieldsWith(r, 75);                // storageBagItemsJson bytes
    ASSERT_EQ(1u, bag.size());
    EXPECT_NO_THROW({
        const json parsed = json::parse(bag[0].bytes);
        ASSERT_TRUE(parsed.is_array());
        EXPECT_EQ("s1", parsed[0].at("id").get<std::string>());
    });
    ASSERT_EQ(1u, fieldsWith(r, 77).size());
    EXPECT_EQ(88, asInt64(fieldsWith(r, 77)[0].varint));  // spiritStones int32

    auto removedIds = fieldsWith(d, 2);  // removedIds
    ASSERT_EQ(2u, removedIds.size());
    EXPECT_EQ("7", removedIds[0].bytes);
    EXPECT_EQ("8", removedIds[1].bytes);
}

// ── collectionChange：非弟子集合（B18-P1 typed 行 + removedIds；
//    旧 upsertsJson bytes 已停写）─────────────────────────────────────
TEST_F(GameViewEncodeTest, CollectionChangeCarriesTypedRows) {
    const json tree = {
        {"version", 4},
        {"changed", {{"pills", json::array({{{"id", "p1"}, {"quantity", 3}}})}}},
        {"removed", {{"pills", {"p0"}}}},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));
    auto cc = fieldsWith(fs, 6);  // CollectionChange
    ASSERT_EQ(1u, cc.size());
    std::vector<std::pair<uint32_t, DecodedField>> c;
    ASSERT_TRUE(decodeFields(cc[0].bytes, c));
    EXPECT_EQ("pills", fieldsWith(c, 1)[0].bytes);          // name
    EXPECT_TRUE(fieldsWith(c, 2).empty());                   // upsertsJson 停写保留（号冻结，不再产出）
    auto rm = fieldsWith(c, 3);                              // removedIds
    ASSERT_EQ(1u, rm.size());
    EXPECT_EQ("p0", rm[0].bytes);
    auto rows = fieldsWith(c, 4);                            // upsertsTyped（TypedRow）
    ASSERT_EQ(1u, rows.size());
    std::vector<std::pair<uint32_t, DecodedField>> row;
    ASSERT_TRUE(decodeFields(rows[0].bytes, row));
    ASSERT_EQ(2u, row.size());                                // TypedField ×2（键字典序：id < quantity）
    EXPECT_EQ(1u, row[0].first);                              // TypedField.fields（field 1）
    EXPECT_EQ(1u, row[1].first);
    std::vector<std::pair<uint32_t, DecodedField>> f0;        // TypedField{id}
    ASSERT_TRUE(decodeFields(row[0].second.bytes, f0));
    EXPECT_EQ("id", fieldsWith(f0, 1)[0].bytes);              // 键（TypedField.key）
    std::vector<std::pair<uint32_t, DecodedField>> v0;        // TypedValue(vString="p1")
    ASSERT_TRUE(decodeFields(fieldsWith(f0, 2)[0].bytes, v0));
    EXPECT_EQ("p1", fieldsWith(v0, 1)[0].bytes);
    std::vector<std::pair<uint32_t, DecodedField>> f1;        // TypedField{quantity}
    ASSERT_TRUE(decodeFields(row[1].second.bytes, f1));
    EXPECT_EQ("quantity", fieldsWith(f1, 1)[0].bytes);        // 键字典序：id < quantity
    std::vector<std::pair<uint32_t, DecodedField>> v1;        // TypedValue(vInt=3)
    ASSERT_TRUE(decodeFields(fieldsWith(f1, 2)[0].bytes, v1));
    EXPECT_EQ(3, asInt64(fieldsWith(v1, 2)[0].varint));
    EXPECT_TRUE(fieldsWith(v1, 8).empty());                   // 非空容器无 vEmptyArray 判别位
}

// ── gameDataChange：resourcesHeader 未覆盖字段（去前缀 + valueTyped；
//    旧 valueJson bytes 已停写）───────────────────────────────────────
TEST_F(GameViewEncodeTest, GameDataChangeStripsPrefixAndCarriesTypedValue) {
    const json tree = {
        {"version", 5},
        {"changed", {{"gameData.gameYear", 12}, {"gameData.someList", {1, 2, 3}}}},
        {"removed", json::object()},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));
    auto gc = fieldsWith(fs, 7);  // JsonFieldChange
    ASSERT_EQ(2u, gc.size());      // gameYear + someList（有序遍历按字母序 gameYear<someList）
    std::vector<std::pair<uint32_t, DecodedField>> g0;
    ASSERT_TRUE(decodeFields(gc[0].bytes, g0));
    EXPECT_EQ("gameYear", fieldsWith(g0, 1)[0].bytes);   // name（无 gameData. 前缀）
    EXPECT_TRUE(fieldsWith(g0, 2).empty());               // valueJson 停写保留（号冻结，不再产出）
    std::vector<std::pair<uint32_t, DecodedField>> v0;    // TypedValue(vInt=12)
    ASSERT_TRUE(decodeFields(fieldsWith(g0, 3)[0].bytes, v0));
    EXPECT_EQ(12, asInt64(fieldsWith(v0, 2)[0].varint));
    std::vector<std::pair<uint32_t, DecodedField>> g1;    // someList → vArray 递归
    ASSERT_TRUE(decodeFields(gc[1].bytes, g1));
    std::vector<std::pair<uint32_t, DecodedField>> v1;
    ASSERT_TRUE(decodeFields(fieldsWith(g1, 3)[0].bytes, v1));
    ASSERT_EQ(3u, v1.size());                             // repeated vArray = TypedValue ×3
    EXPECT_EQ(5u, v1[0].first);
    for (int i = 0; i < 3; ++i) {
        std::vector<std::pair<uint32_t, DecodedField>> e;
        ASSERT_TRUE(decodeFields(v1[static_cast<std::size_t>(i)].second.bytes, e));
        EXPECT_EQ(i + 1, asInt64(fieldsWith(e, 2)[0].varint));  // vInt 1/2/3
    }
}

// ── gameDataChange：空容器判别（[] 与 {} wire 层同为零字节——b02 发现 7）─
TEST_F(GameViewEncodeTest, GameDataChangeEmptyContainerDiscriminator) {
    const json tree = {
        {"version", 6},
        {"changed", {{"gameData.disabledPolicies", json::array()},
                     {"gameData.someMap", json::object()}}},
        {"removed", json::object()},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));
    auto gc = fieldsWith(fs, 7);
    ASSERT_EQ(2u, gc.size());  // disabledPolicies < someMap（字典序）
    // [] → vEmptyArray=true（field 8），vArray 零条目
    std::vector<std::pair<uint32_t, DecodedField>> a;
    ASSERT_TRUE(decodeFields(gc[0].bytes, a));
    EXPECT_EQ("disabledPolicies", fieldsWith(a, 1)[0].bytes);
    std::vector<std::pair<uint32_t, DecodedField>> av;
    ASSERT_TRUE(decodeFields(fieldsWith(a, 3)[0].bytes, av));
    ASSERT_EQ(1u, av.size());
    EXPECT_EQ(8u, av[0].first);          // vEmptyArray（field 8）
    EXPECT_EQ(0u, av[0].second.wire);    // boolField = varint wire
    EXPECT_EQ(1u, av[0].second.varint);  // vEmptyArray=true
    // {} → 零字节 TypedValue（presence 在，形状 = 默认对象）
    std::vector<std::pair<uint32_t, DecodedField>> m;
    ASSERT_TRUE(decodeFields(gc[1].bytes, m));
    EXPECT_EQ("someMap", fieldsWith(m, 1)[0].bytes);
    auto mv = fieldsWith(m, 3);
    ASSERT_EQ(1u, mv.size());
    EXPECT_TRUE(mv[0].bytes.empty());                         // 零字节子消息
}

// ── configEcho：schemaVersion 非空才携带 ──────────────────────────
TEST_F(GameViewEncodeTest, ConfigEchoPresentWhenVersionNonEmpty) {
    const std::string withEcho = encodeGameView(json{{"version", 1}}, "4.01.15");
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(withEcho, fs));
    auto echo = fieldsWith(fs, 5);  // ConfigEcho
    ASSERT_EQ(1u, echo.size());
    std::vector<std::pair<uint32_t, DecodedField>> e;
    ASSERT_TRUE(decodeFields(echo[0].bytes, e));
    EXPECT_EQ("4.01.15", fieldsWith(e, 1)[0].bytes);

    std::vector<std::pair<uint32_t, DecodedField>> fs2;
    ASSERT_TRUE(decodeFields(encodeGameView(json{{"version", 1}}, ""), fs2));
    EXPECT_TRUE(fieldsWith(fs2, 5).empty());  // 空 schemaVersion → 省略
}

// ── eventFeed：缺省不产出（R2.1/R2.2 历史字节不变）────────────────
TEST_F(GameViewEncodeTest, EventFeedNotEmitted) {
    const json tree = {
        {"version", 1},
        {"changed", {{"gameData.spiritStones", 1}, {"disciples", json::array()}}},
        {"removed", json::object()},
    };
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, ""), fs));
    EXPECT_TRUE(fieldsWith(fs, 4).empty());  // 无 field 4
}

// ── eventFeed：R2.4 转正——事件列表逐条编码为 ViewEvent ────────────
TEST_F(GameViewEncodeTest, EventFeedEmittedWhenProvided) {
    using gamecore::state::ViewEventDraft;
    using gamecore::state::ViewEventType;
    const json tree = {{"version", 1}, {"changed", json::object()},
                       {"removed", json::object()}};
    std::vector<ViewEventDraft> events;
    ViewEventDraft month;
    month.type = ViewEventType::kMonthSettled;
    month.gameYear = 37;
    month.gameMonth = 12;
    month.detailJson = R"({"disabledPolicies":[],"seizedSectBuildings":[]})";
    events.push_back(month);
    ViewEventDraft purchase;
    purchase.type = ViewEventType::kPurchase;
    purchase.gameYear = 37;
    purchase.gameMonth = 12;
    purchase.detailJson = R"({"discipleId":"3","itemName":"聚气丹","age":21})";
    events.push_back(purchase);
    ViewEventDraft bare;
    bare.type = ViewEventType::kBreakthrough;
    bare.gameYear = 38;
    bare.gameMonth = 1;
    // detailJson 空 → detail 字段省略（proto3 显式 presence）
    events.push_back(bare);

    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(tree, "", &events), fs));
    const auto evs = fieldsWith(fs, 4);
    ASSERT_EQ(3u, evs.size());                      // 三条 ViewEvent 子消息
    for (const auto& ev : evs) EXPECT_EQ(2u, ev.wire);

    // 第一条 = MONTH_SETTLED（type=1），载荷字段 1/2/3/4 逐一断言
    std::vector<std::pair<uint32_t, DecodedField>> m;
    ASSERT_TRUE(decodeFields(evs[0].bytes, m));
    ASSERT_EQ(4u, m.size());
    EXPECT_EQ(1u, m[0].first); EXPECT_EQ(1u, m[0].second.varint);   // type
    EXPECT_EQ(2u, m[1].first); EXPECT_EQ(37u, m[1].second.varint);  // gameYear
    EXPECT_EQ(3u, m[2].first); EXPECT_EQ(12u, m[2].second.varint);  // gameMonth
    EXPECT_EQ(4u, m[3].first);                                      // detailJson
    EXPECT_EQ(std::string(R"({"disabledPolicies":[],"seizedSectBuildings":[]})"),
              m[3].second.bytes);

    // 第二条 = PURCHASE（type=5）
    std::vector<std::pair<uint32_t, DecodedField>> p;
    ASSERT_TRUE(decodeFields(evs[1].bytes, p));
    ASSERT_FALSE(p.empty());
    EXPECT_EQ(5u, p[0].second.varint);

    // 第三条 = BREAKTHROUGH（type=3），无 detail（仅 3 个字段）
    std::vector<std::pair<uint32_t, DecodedField>> b;
    ASSERT_TRUE(decodeFields(evs[2].bytes, b));
    ASSERT_EQ(3u, b.size());
    EXPECT_EQ(3u, b[0].second.varint);
    EXPECT_EQ(38u, b[1].second.varint);
    EXPECT_EQ(1u, b[2].second.varint);

    // 确定性：同输入两次编码逐字节相同
    const std::string a = encodeGameView(tree, "", &events);
    const std::string c = encodeGameView(tree, "", &events);
    EXPECT_EQ(a, c);
}

// ── 确定性：同一变更集树 → 逐字节相同信封 ─────────────────────────
TEST_F(GameViewEncodeTest, DeterministicByteIdentical) {
    const json tree = {
        {"version", 42},
        {"changed", {
            {"gameData.spiritStones", 999},
            {"disciples", json::array({{{"id", "1"}, {"name", "甲"}, {"realm", 3}}})},
            {"pills", json::array({{{"id", "p1"}, {"quantity", 2}}})},
        }},
        {"removed", {{"disciples", {"9"}}}},
    };
    const std::string a = encodeGameView(tree, "4.01.15");
    const std::string b = encodeGameView(tree, "4.01.15");
    EXPECT_EQ(a, b);
}

// ── 缺失/畸形键的鲁棒性（不抛、不产出多余字段）───────────────────
TEST_F(GameViewEncodeTest, MalformedDiffIsLenient) {
    // changed 非对象、removed 缺失、version 缺失 → 仅 version=0
    std::vector<std::pair<uint32_t, DecodedField>> fs;
    ASSERT_TRUE(decodeFields(encodeGameView(json{{"changed", 123}}, ""), fs));
    ASSERT_EQ(1u, fs.size());
    EXPECT_EQ(1u, fs[0].first);
    EXPECT_EQ(0u, fs[0].second.varint);
}

}  // namespace
