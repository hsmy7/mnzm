#include <gtest/gtest.h>

#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/data/beast_config.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/data_inject.h"
#include "gamecore/data/data_store.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/gacha_pool_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"

#include "game_data_json.h"

namespace gamecore::data {
namespace {

// ============================================================
// B16 / R6.2 数值外置守卫（验收门 3 + 门 4）
//
// 三层口径：
//   层 1「数值逐位等价」（硬门）：数据文件加载后的 C++ DB 与改前头文件 DB
//     **逐行逐字段相等**——这是对拍不变的前提。
//   层 2「兜底语义」（硬门）：注入前 / 失败时表**非空**且等于内联默认值，
//     禁止静默空表；`stateName()` 可观测。
//   层 3「注入纪律」（硬门）：注入仅初始化期一次，重复注入被拒且计数达成
//     稳态；即"稳态零跨线"的行为级证据。
//
// 数据文件定位：**默认从仓库路径读**（相对 GTest 工作目录，沿既有多候选
// 兜底惯例），可用 `--data_json=<path>` 覆盖（桌面注入工具用）。
// ============================================================

/// 定位 `assets/data/game-data.json` —— 收敛到 `test/game_data_json.h`
/// （多候选兜底与"定位不到即 fail-fast"的口径与抽卡守护共用一份）

/// 测试夹具：进程内全局 store 只能注入一次 ⇒ 每个用例前复位（仅测试可用）
class DataStoreGuardTest : public ::testing::Test {
protected:
    void SetUp() override { resetGameDataStoreForTest(); }
    void TearDown() override { resetGameDataStoreForTest(); }

    /// 加载数据文件全文（缺失即 fail-fast——不允许静默跳过守卫）
    static std::string loadPayload() {
        const std::string payload = testsupport::readGameDataJson();
        if (payload.empty()) {
            ADD_FAILURE() << "game-data.json 不存在——请先运行 node scripts/gen-game-data.mjs";
            return {};
        }
        return payload;
    }

    static nlohmann::json parsePayload(const std::string& payload) {
        auto j = nlohmann::json::parse(payload, nullptr, false);
        EXPECT_FALSE(j.is_discarded());
        return j;
    }
};

// ── 层 1：数值逐位等价（硬门）────────────────────────────────

TEST_F(DataStoreGuardTest, 注入后与数据文件逐行逐字段相等) {
    const std::string payload = loadPayload();
    if (payload.empty()) return;  // ADD_FAILURE 已登记
    const auto doc = parsePayload(payload);
    const auto& db = doc["db"];

    ASSERT_TRUE(inject::injectFromJson(payload));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kLoadedFromFile);

    // 逐行逐字段：容器 vs JSON 段（用同一 nlohmann 序列化再比对，
    // 覆盖**全部**字段而不只是抽样——避免"抽样漏字段"的假绿）
    EXPECT_EQ(db["equipment"].get<std::vector<EquipmentTemplate>>(),
              equipmentTemplates());
    EXPECT_EQ(db["herbs"].get<std::vector<HerbTemplate>>(), herbTemplates());
    EXPECT_EQ(db["seeds"].get<std::vector<SeedTemplate>>(), seedTemplates());
    EXPECT_EQ(db["manuals"].get<std::vector<ManualTemplate>>(), manualTemplates());
    EXPECT_EQ(db["beastMaterials"].get<std::vector<BeastMaterialTemplate>>(),
              beastMaterialTemplates());
    EXPECT_EQ(db["forgeRecipes"].get<std::vector<ForgeRecipeTemplate>>(),
              forgeRecipes());
    // pillRecipes 的 price 为派生字段（JSON 段无该键）——按 data_inject 生产
    // 路径同款方式回填后再比对：其余 38 字段证明"JSON 段 ↔ 容器逐位相等"，
    // price 字段证明"回填 == C++ 构建器派生值"（下方另有逐行抽样断言）
    auto pillExpected = db["pillRecipes"].get<std::vector<PillRecipeTemplate>>();
    {
        std::map<std::string, int32_t> derivedPrice;
        for (const auto& r : detail::buildPillRecipes()) {
            derivedPrice.emplace(r.id, r.price);
        }
        for (auto& r : pillExpected) {
            const auto it = derivedPrice.find(r.id);
            ASSERT_NE(it, derivedPrice.end());
            r.price = it->second;
        }
    }
    EXPECT_EQ(pillExpected, pillRecipes());

    // 行数断言（消费面枚举清单的权威口径）
    EXPECT_EQ(72u, equipmentTemplates().size());
    EXPECT_EQ(54u, herbTemplates().size());
    EXPECT_EQ(54u, seedTemplates().size());
    EXPECT_EQ(540u, manualTemplates().size());
    EXPECT_EQ(192u, beastMaterialTemplates().size());
    EXPECT_EQ(72u, forgeRecipes().size());
    // R11 孕养丹退役：660 − 36 = 624
    EXPECT_EQ(624u, pillRecipes().size());

    // price 回填抽样：注入后 pillRecipes 的 price 必须等于 C++ 同一构建器的
    // 派生值（派生逻辑保持 C++ 侧的红线实证；全量等价由上面的逐字段比对锁定）
    const auto built = detail::buildPillRecipes();
    ASSERT_EQ(built.size(), pillRecipes().size());
    for (size_t i = 0; i < built.size(); ++i) {
        ASSERT_EQ(built[i].id, pillRecipes()[i].id);
        EXPECT_EQ(built[i].price, pillRecipes()[i].price);
    }

    // 卡池 / 角色模板（G09）：同样逐行逐字段比对，且容器长度必须等于段长
    // （"注入器读到几条就是几条"——漏读/半注入在这里显形）
    EXPECT_EQ(db["gachaPools"].get<std::vector<GachaPoolTemplate>>(), gachaPools());
    EXPECT_EQ(db["characterTemplates"].get<std::vector<CharacterTemplate>>(),
              characterTemplates());
    EXPECT_EQ(db["gachaPools"].size(), gachaPools().size());
    EXPECT_EQ(db["characterTemplates"].size(), characterTemplates().size());
}

// ── 卡池两表专项（G09 数据面：C++ 抽卡 roll 的唯一配置来源）──────────

TEST_F(DataStoreGuardTest, 卡池两表注入计数与段长一致且关键字段可读) {
    const std::string payload = loadPayload();
    if (payload.empty()) return;
    const auto doc = parsePayload(payload);
    ASSERT_TRUE(doc["db"]["gachaPools"].is_array());
    ASSERT_TRUE(doc["db"]["characterTemplates"].is_array());

    const auto counts = inject::applyAndCount(doc);
    // AppliedCounts 与段长逐一对齐（多算/漏算都在这里显形）
    EXPECT_EQ(static_cast<int32_t>(doc["db"]["gachaPools"].size()), counts.gachaPools);
    EXPECT_EQ(static_cast<int32_t>(doc["db"]["characterTemplates"].size()),
              counts.characterTemplates);
    // 现况锚点：一张常驻池 + 六个具名角色（增减条目须同步本断言与产品口径）
    EXPECT_EQ(1, counts.gachaPools);
    EXPECT_EQ(6, counts.characterTemplates);
    // 其余八段计数非零（一次注入喂满九张表，零段被静默跳过）
    EXPECT_GT(counts.equipment, 0);
    EXPECT_GT(counts.gachaPools, 0);

    // 按 id 查询入口命中注入值；池关键字段可读（抽卡前置校验的输入面）。
    // 数值一律取自数据文件，本用例**不抄字面量**——手工复刻的期望表是
    // 编译与生成器都抓不到的孤儿面（HANDOVER-3 §2.3 坑 9）。
    const auto& poolJson = doc["db"]["gachaPools"][0];
    const auto* pool = gachaPoolById(poolJson["poolId"].get<std::string>());
    ASSERT_NE(nullptr, pool);
    EXPECT_TRUE(pool->enabled);
    EXPECT_GT(pool->pricePerPull, 0);
    EXPECT_GT(pool->pity.pullThreshold, 0);
    EXPECT_GT(pool->pity.fragmentCount, 0);
    EXPECT_EQ(poolJson["pity"]["pickMode"].get<std::string>(), pool->pity.pickMode);
    EXPECT_GT(pool->fragmentsPerStar, 0);
    EXPECT_GT(pool->maxStar, 0);
    EXPECT_FALSE(pool->categories.empty());
    EXPECT_EQ(poolJson["categories"].size(), pool->categories.size());
    EXPECT_EQ(poolJson["itemRarityWeights"].size(), pool->itemRarityWeights.size());
    EXPECT_EQ(nullptr, gachaPoolById("no_such_pool"));

    const auto& tplJson = doc["db"]["characterTemplates"][0];
    const auto* tpl = characterTemplateById(tplJson["id"].get<std::string>());
    ASSERT_NE(nullptr, tpl);
    EXPECT_EQ(tplJson["name"].get<std::string>(), tpl->name);
    EXPECT_EQ(tplJson["avatarKey"].get<std::string>(), tpl->avatarKey);
    EXPECT_EQ(tplJson["portraitKey"].get<std::string>(), tpl->portraitKey);
    EXPECT_EQ(nullptr, characterTemplateById("no_such_template"));

    // 保底候选（全部角色类别的 templateIds 并集，按声明序）必须覆盖六名：
    // 抽卡侧按此序取随机下标，序与集都由本表钉死，双臂不可能各抽各的
    std::vector<std::string> characterIds;
    for (const auto& cat : pool->categories) {
        if (!cat.isCharacter()) continue;
        ASSERT_FALSE(cat.templateIds.empty()) << "角色类别候选为空: " << cat.kind;
        for (const auto& id : cat.templateIds) characterIds.push_back(id);
    }
    EXPECT_EQ(6u, characterIds.size());
    for (const auto& id : characterIds) {
        EXPECT_NE(nullptr, characterTemplateById(id)) << "池引用表外角色: " << id;
    }
}

TEST_F(DataStoreGuardTest, 卡池段存在但为空即整体注入失败) {
    // 段在而非数组 / 段在为空数组 ⇒ return false ⇒ 落 kFallbackDefault，
    // 禁止"半注入"（其余段成功、卡池段静默为空）
    EXPECT_FALSE(inject::injectFromJson(
        R"({"schemaVersion":1,"db":{"gachaPools":[]}})"));
    EXPECT_EQ(GameDataState::kFallbackDefault, gameDataStoreState().state);
    EXPECT_EQ(1, gameDataStoreState().stats.failedParse);

    resetGameDataStoreForTest();
    EXPECT_FALSE(inject::injectFromJson(
        R"({"schemaVersion":1,"db":{"characterTemplates":"oops"}})"));
    EXPECT_EQ(GameDataState::kFallbackDefault, gameDataStoreState().state);
}

TEST_F(DataStoreGuardTest, 注入后内联兜底与数据文件默认值一致) {
    // 「兜底值与数据文件默认值一致」——红线要求。
    // 口径：注入前快照 == 注入后快照（证明数据文件产出的值与内联默认**逐位相同**），
    // 即数据文件不是"另一套数值"，而是内联默认的等价外置形式。
    const auto equipBefore = equipmentTemplates();
    const auto herbBefore = herbTemplates();
    const auto manualBefore = manualTemplates();
    const auto forgeBefore = forgeRecipes();
    const auto pillBefore = pillRecipes();

    const std::string payload = loadPayload();
    if (payload.empty()) return;
    ASSERT_TRUE(inject::injectFromJson(payload));

    EXPECT_EQ(equipBefore, equipmentTemplates());
    EXPECT_EQ(herbBefore, herbTemplates());
    EXPECT_EQ(manualBefore, manualTemplates());
    EXPECT_EQ(forgeBefore, forgeRecipes());
    EXPECT_EQ(pillBefore, pillRecipes());
}

TEST_F(DataStoreGuardTest, 全部九表均可注入且消费入口非空) {
    const std::string payload = loadPayload();
    if (payload.empty()) return;
    ASSERT_TRUE(inject::injectFromJson(payload));

    // 九张注入表（5 简单表 + forge/pill 配方 + G09 卡池/角色模板）的消费入口
    // 均非空；beast_config 的结构性表（C++ 侧真相源，残余登记）同验
    EXPECT_FALSE(equipmentTemplates().empty());
    EXPECT_FALSE(herbTemplates().empty());
    EXPECT_FALSE(seedTemplates().empty());
    EXPECT_FALSE(manualTemplates().empty());
    EXPECT_FALSE(beastMaterialTemplates().empty());
    EXPECT_FALSE(forgeRecipes().empty());
    EXPECT_FALSE(pillRecipes().empty());
    EXPECT_FALSE(gachaPools().empty());
    EXPECT_FALSE(characterTemplates().empty());
    EXPECT_FALSE(beastTypes().empty());
    EXPECT_GT(detail::beastRealmStats(0).hp, 0);

    // 卡池类别的品阶口径：物品类别必须给出 itemSource 与 maxRarity，
    // 角色类别必须给出候选（抽卡前置校验的输入完整性，缺即配置漂移）
    for (const auto& pool : gachaPools()) {
        ASSERT_FALSE(pool.categories.empty()) << "池无类别: " << pool.poolId;
        for (const auto& cat : pool.categories) {
            if (cat.isCharacter()) {
                ASSERT_FALSE(cat.templateIds.empty()) << "角色类别无候选: " << cat.kind;
                EXPECT_TRUE(cat.itemSource.empty()) << "角色类别带 itemSource: " << cat.kind;
            } else {
                ASSERT_FALSE(cat.itemSource.empty()) << "物品类别无 itemSource: " << cat.kind;
                EXPECT_GT(cat.maxRarity, 0) << "物品类别缺 maxRarity: " << cat.kind;
                EXPECT_TRUE(cat.templateIds.empty()) << "物品类别带 templateIds: " << cat.kind;
            }
        }
    }

    // 注入后派生回填的 price 必须为正（Kotlin PillTemplate.price ≥ 4000 档）
    EXPECT_GT(pillRecipes().front().price, 0);
}

// ── 层 2：兜底语义（硬门）────────────────────────────────────

TEST_F(DataStoreGuardTest, 注入前为未注入态且表等于内联默认) {
    // 注入前：状态 = kUninitialized，表**非空**（禁止空表）
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kUninitialized);
    EXPECT_EQ(std::string("uninitialized"),
              std::string(stateName(gameDataStoreState().state)));
    EXPECT_FALSE(equipmentTemplates().empty());
    EXPECT_FALSE(manualTemplates().empty());
    EXPECT_EQ(72u, equipmentTemplates().size());
}

TEST_F(DataStoreGuardTest, 解析失败落兜底且表非空) {
    // 畸形 JSON ⇒ 显式失败 + 兜底（不是空表）
    EXPECT_FALSE(inject::injectFromJson("{ not json !!"));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kFallbackDefault);
    EXPECT_EQ(std::string("fallbackDefault"),
              std::string(stateName(gameDataStoreState().state)));
    EXPECT_EQ(1, gameDataStoreState().stats.failedParse);
    EXPECT_FALSE(equipmentTemplates().empty());  // 兜底：仍是 72 条内联默认
    EXPECT_EQ(72u, equipmentTemplates().size());
}

TEST_F(DataStoreGuardTest, schema版本不符落兜底) {
    EXPECT_FALSE(inject::injectFromJson(R"({"schemaVersion":999,"db":{}})"));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kFallbackDefault);
    EXPECT_FALSE(equipmentTemplates().empty());
}

TEST_F(DataStoreGuardTest, 段类型不符落兜底) {
    // equipment 段存在但非数组 ⇒ 显式失败（不允许静默只注入一半）
    EXPECT_FALSE(inject::injectFromJson(
        R"({"schemaVersion":1,"db":{"equipment":"oops"}})"));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kFallbackDefault);
    EXPECT_EQ(72u, equipmentTemplates().size());
}

TEST_F(DataStoreGuardTest, 空段落兜底) {
    EXPECT_FALSE(inject::injectFromJson(
        R"({"schemaVersion":1,"db":{"equipment":[]}})"));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kFallbackDefault);
    EXPECT_EQ(72u, equipmentTemplates().size());
}

// ── 层 3：注入纪律（硬门）────────────────────────────────────

TEST_F(DataStoreGuardTest, 注入仅初始化期一次_重复注入被拒) {
    const std::string payload = loadPayload();
    if (payload.empty()) return;

    ASSERT_TRUE(inject::injectFromJson(payload));
    const auto& st = gameDataStoreState();
    EXPECT_EQ(1, st.stats.accepted);
    EXPECT_EQ(1, st.stats.attempts);

    // 稳态：再注入 3 次全被拒，表**地址与内容均不变**
    const auto* addr = &equipmentTemplates();
    const auto snapshot = equipmentTemplates();
    for (int i = 0; i < 3; ++i) {
        EXPECT_FALSE(inject::injectFromJson(payload));
    }
    EXPECT_EQ(1, st.stats.accepted);
    EXPECT_EQ(4, st.stats.attempts);
    EXPECT_EQ(3, st.stats.rejectedAlreadyLoaded);
    // 指针稳定性（指针型消费点的硬约束）
    EXPECT_EQ(addr, &equipmentTemplates());
    EXPECT_EQ(snapshot, equipmentTemplates());
    EXPECT_TRUE(isGameDataSealed());
}

TEST_F(DataStoreGuardTest, 稳态零跨线_失败后再成功亦被拒) {
    // 先失败落兜底 ⇒ 已 seal ⇒ 后续「正确」注入也不得改变表
    // （防止"失败后可被第二次调用静默修正"，破坏指针稳定性）
    const std::string payload = loadPayload();
    if (payload.empty()) return;
    ASSERT_FALSE(inject::injectFromJson("{ broken"));
    EXPECT_EQ(gameDataStoreState().state, GameDataState::kFallbackDefault);
    EXPECT_EQ(0, gameDataStoreState().stats.accepted);  // 首次即失败，从未被接受

    const auto* addr = &equipmentTemplates();
    EXPECT_FALSE(inject::injectFromJson(payload));  // 已 seal ⇒ 拒
    EXPECT_EQ(addr, &equipmentTemplates());
    // 兜底后仍不得被"修正"：accepted 恒为 0，且计入"已初始化被拒"
    EXPECT_EQ(0, gameDataStoreState().stats.accepted);
    EXPECT_EQ(1, gameDataStoreState().stats.rejectedAlreadyLoaded);
    EXPECT_EQ(GameDataState::kFallbackDefault, gameDataStoreState().state);
}

TEST_F(DataStoreGuardTest, 指针型查询入口在注入后稳定) {
    const std::string payload = loadPayload();
    if (payload.empty()) return;
    ASSERT_TRUE(inject::injectFromJson(payload));

    // 注入后查询入口返回的指针必须指向容器内稳定地址
    const auto* m = manualById(manualTemplates().front().id);
    ASSERT_NE(nullptr, m);
    EXPECT_EQ(&manualTemplates().front(), m);

    const auto* bm = beastMaterialById(beastMaterialTemplates().front().id);
    ASSERT_NE(nullptr, bm);
    EXPECT_EQ(&beastMaterialTemplates().front(), bm);

    const auto* bt = beastTypeByName(beastTypes().front().name);
    ASSERT_NE(nullptr, bt);
    EXPECT_EQ(&beastTypes().front(), bt);

    // 配方/天赋族查询入口返回 optional<T>（值拷贝）——注入后按 id 查询必须命中
    // 注入值（与数据文件一致），且价格派生回填已生效
    const auto forge = forgeRecipeById(forgeRecipes().front().id);
    ASSERT_TRUE(forge.has_value());
    EXPECT_EQ(forgeRecipes().front(), *forge);

    const auto pill = pillRecipeById(pillRecipes().front().id);
    ASSERT_TRUE(pill.has_value());
    EXPECT_EQ(pillRecipes().front(), *pill);
    EXPECT_GT(pill->price, 0);
}

}  // namespace
}  // namespace gamecore::data
