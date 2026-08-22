#include <gtest/gtest.h>

#include <nlohmann/json.hpp>

#include "gamecore/state/json_codec.h"
#include "gamecore/state/models.h"

namespace gamecore::state {
namespace {

TEST(JsonCodecTest, GameDataRoundTrip) {
    GameData d;
    d.id = "sect-1";
    d.sectName = "青云宗";
    d.gameYear = 12;
    d.gameMonth = 7;
    d.gamePhase = 1;
    d.spiritStones = 99999;
    d.midGradeSpiritStones = 123;
    d.highGradeSpiritStones = 45;
    d.sectCultivation = 1234.5678;
    d.rngStates = {{0, 123456789LL}, {3, -987654321LL}};
    d.yearlySalary = {{9, 240}, {8, 720}};
    d.unlockedRecipes = {"pill:聚气丹"};
    d.autoRecruitSpiritRootFilter = {1, 2, 3};
    d.guideCounters = {{"build_first", 1LL}};
    d.mapSeed = 42;
    d.soundEnabled = false;
    d.musicEnabled = true;

    const nlohmann::json j = d;
    GameData decoded = j.get<GameData>();
    EXPECT_EQ(d.id, decoded.id);
    EXPECT_EQ(d.sectName, decoded.sectName);
    EXPECT_EQ(d.gameYear, decoded.gameYear);
    EXPECT_EQ(d.spiritStones, decoded.spiritStones);
    EXPECT_DOUBLE_EQ(d.sectCultivation, decoded.sectCultivation);
    EXPECT_EQ(d.rngStates, decoded.rngStates);
    EXPECT_EQ(d.yearlySalary, decoded.yearlySalary);
    EXPECT_EQ(d.unlockedRecipes, decoded.unlockedRecipes);
    EXPECT_EQ(d.autoRecruitSpiritRootFilter, decoded.autoRecruitSpiritRootFilter);
    EXPECT_EQ(d.guideCounters, decoded.guideCounters);
    EXPECT_EQ(d.mapSeed, decoded.mapSeed);
    EXPECT_FALSE(decoded.soundEnabled);
    EXPECT_TRUE(decoded.musicEnabled);
    // 默认字段不因往返改变
    EXPECT_EQ(d.gamePhase, decoded.gamePhase);
    EXPECT_EQ(d.saveVersion, decoded.saveVersion);
}

TEST(JsonCodecTest, DiscipleRoundTrip) {
    Disciple d;
    d.id = "d-1";
    d.name = "张三";
    d.realm = 7;
    d.cultivation = 12345.6;
    d.isAlive = true;
    d.manualMasteries = {{"m-1", 50}};
    d.status = "IN_TEAM";

    const nlohmann::json j = d;
    Disciple decoded = j.get<Disciple>();
    EXPECT_EQ(d.id, decoded.id);
    EXPECT_EQ(d.name, decoded.name);
    EXPECT_EQ(d.realm, decoded.realm);
    EXPECT_DOUBLE_EQ(d.cultivation, decoded.cultivation);
    EXPECT_EQ(d.isAlive, decoded.isAlive);
    EXPECT_EQ(d.manualMasteries, decoded.manualMasteries);
    EXPECT_EQ(d.status, decoded.status);
}

TEST(JsonCodecTest, ItemsRoundTrip) {
    EquipmentStack es;
    es.id = "eq-s1";
    es.name = "青锋剑";
    es.rarity = 3;
    es.quantity = 2;
    es.slot = "WEAPON";
    es.physicalAttack = 12;
    es.magicAttack = 3;
    es.isLocked = true;
    EXPECT_EQ(es.id, nlohmann::json(es).get<EquipmentStack>().id);
    EXPECT_EQ(es.isLocked, nlohmann::json(es).get<EquipmentStack>().isLocked);

    EquipmentInstance ei;
    ei.id = "eq-i1";
    ei.ownerId = "d-1";
    ei.isEquipped = true;
    const auto eiDecoded = nlohmann::json(ei).get<EquipmentInstance>();
    EXPECT_EQ(ei.ownerId, eiDecoded.ownerId);
    EXPECT_TRUE(eiDecoded.isEquipped);

    // 可空字段：null 语义保留
    EquipmentInstance eiNull;
    const auto eiNullDecoded = nlohmann::json(eiNull).get<EquipmentInstance>();
    EXPECT_FALSE(eiNullDecoded.ownerId.has_value());

    Pill p;
    p.id = "pill-1";
    p.name = "聚气丹";
    p.category = "CULTIVATION";
    p.grade = "MEDIUM";
    p.pillType = "qi";
    EXPECT_EQ(p.pillType, nlohmann::json(p).get<Pill>().pillType);

    ManualInstance mi;
    mi.id = "m-i1";
    mi.type = "ATTACK";
    mi.stats = {{"attack", 15}};
    mi.skillName = "御剑";
    mi.skillDamageMultiplier = 1.5;
    mi.skillIsAoe = true;
    mi.skillShieldPercent = 0.05;
    mi.ownerId = "d-1";
    mi.isLearned = true;
    const auto miDecoded = nlohmann::json(mi).get<ManualInstance>();
    EXPECT_EQ(mi.skillName, miDecoded.skillName);
    EXPECT_EQ(mi.skillDamageMultiplier, miDecoded.skillDamageMultiplier);
    EXPECT_TRUE(miDecoded.skillIsAoe);
    EXPECT_DOUBLE_EQ(mi.skillShieldPercent, miDecoded.skillShieldPercent);
    EXPECT_EQ(mi.ownerId, miDecoded.ownerId);
    EXPECT_TRUE(miDecoded.isLearned);
    // 未设置的 skillBuffType 保持 null
    EXPECT_FALSE(miDecoded.skillBuffType.has_value());

    // ManualStack 的 quantity 与 ManualInstance 区分
    ManualStack ms;
    ms.id = "m-s1";
    ms.quantity = 3;
    const auto msDecoded = nlohmann::json(ms).get<ManualStack>();
    EXPECT_EQ(ms.quantity, msDecoded.quantity);
    EXPECT_TRUE(msDecoded.skillDescription == std::nullopt);

    Seed s;
    s.id = "seed-1";
    s.growTime = 3;
    s.yield = 2;
    EXPECT_EQ(s.yield, nlohmann::json(s).get<Seed>().yield);
}

TEST(JsonCodecTest, GameStateRoundTrip) {
    GameState st;
    st.gameData.gameYear = 5;
    st.gameData.spiritStones = 777;
    st.disciples.push_back(Disciple{});
    st.disciples[0].id = "d-1";
    st.disciples[0].name = "张三";
    st.pills.push_back(Pill{});
    st.pills[0].id = "p-1";
    st.seeds.push_back(Seed{});
    st.seeds[0].id = "s-1";

    const nlohmann::json j = st;
    const GameState decoded = j.get<GameState>();
    EXPECT_EQ(st.gameData.gameYear, decoded.gameData.gameYear);
    EXPECT_EQ(st.gameData.spiritStones, decoded.gameData.spiritStones);
    ASSERT_EQ(decoded.disciples.size(), 1u);
    EXPECT_EQ(decoded.disciples[0].id, "d-1");
    EXPECT_EQ(decoded.disciples[0].name, "张三");
    ASSERT_EQ(decoded.pills.size(), 1u);
    EXPECT_EQ(decoded.pills[0].id, "p-1");
    ASSERT_EQ(decoded.seeds.size(), 1u);
    EXPECT_EQ(decoded.seeds[0].id, "s-1");
    EXPECT_TRUE(decoded.materials.empty());
}

TEST(JsonCodecTest, LenientFromJsonIgnoresUnknownFields) {
    // 宽松解析：未知字段（后续批次未覆盖的嵌套对象）不得破坏导入
    const nlohmann::json j = nlohmann::json::parse(R"({
        "gameYear": 8,
        "spiritStones": 123,
        "worldMapSects": [{"id":"s-1","unknownNested":true}],
        "futureField": {"nested": [1,2,3]}
    })");
    GameData d = j.get<GameData>();
    EXPECT_EQ(d.gameYear, 8);
    EXPECT_EQ(d.spiritStones, 123);
    // 未知字段被忽略，其余保持默认
    EXPECT_EQ(d.gameMonth, 1);
}

TEST(JsonCodecTest, NestedTypesRoundTrip) {
    GameData d;
    // 政策
    d.sectPolicies.spiritMineBoost = true;
    d.sectPolicies.openRecruitment = true;
    d.sectPolicies.autoMineRootCounts = {1, 2};
    d.sectPolicies.autoMineThreshold = 3;
    // 长老
    d.elderSlots.viceSectMaster = "d-1";
    d.elderSlots.herbGardenDisciples.push_back(DirectDiscipleSlot{});
    d.elderSlots.herbGardenDisciples[0].discipleId = "d-3";
    d.elderSlots.herbGardenDisciples[0].discipleName = "王五";
    // 生产槽
    d.productionSlots.push_back(ProductionSlot{});
    d.productionSlots[0].id = "ps-1";
    d.productionSlots[0].buildingType = "FORGE";
    d.productionSlots[0].status = "WORKING";
    d.productionSlots[0].recipeId = std::nullopt;   // null 语义
    d.productionSlots[0].completionMonth = 7;
    // 建筑
    d.placedBuildings.push_back(GridBuildingData{});
    d.placedBuildings[0].buildingId = "forge";
    d.placedBuildings[0].gridX = 3;
    // 联盟
    d.alliances.push_back(Alliance{});
    d.alliances[0].id = "a-1";
    d.alliances[0].sectIds = {"s-1", "s-2"};
    // 世界宗门
    d.worldMapSects.push_back(WorldSect{});
    d.worldMapSects[0].name = "青云宗";
    d.worldMapSects[0].disciples = {{1, 5}, {2, 3}};
    // 世界关卡
    d.worldLevels.push_back(WorldLevel{});
    d.worldLevels[0].id = "wl-1";
    d.worldLevels[0].type = "BEAST";
    d.worldLevels[0].beastType = 1;
    d.worldLevels[0].x = 10.5f;
    // 巡视配置
    d.patrolConfig.targetRealms = {5, 6, 7};
    d.patrolConfig.maxBeastCount = 3;
    // 商人
    d.travelingMerchantItems.push_back(MerchantItem{});
    d.travelingMerchantItems[0].id = "m-1";
    d.travelingMerchantItems[0].price = 100;
    // 轻量记录
    d.mailRecords.push_back(MailClaimRecord{});
    d.mailRecords[0].mailId = "mail-1";
    d.pendingTraitAdds.push_back(PendingTraitAdd{});
    d.pendingTraitAdds[0].traitId = "t-1";

    const nlohmann::json j = d;
    const GameData decoded = j.get<GameData>();
    EXPECT_EQ(d.sectPolicies.spiritMineBoost, decoded.sectPolicies.spiritMineBoost);
    EXPECT_EQ(d.sectPolicies.autoMineRootCounts, decoded.sectPolicies.autoMineRootCounts);
    EXPECT_EQ(d.elderSlots.viceSectMaster, decoded.elderSlots.viceSectMaster);
    ASSERT_EQ(decoded.elderSlots.herbGardenDisciples.size(), 1u);
    EXPECT_EQ(decoded.elderSlots.herbGardenDisciples[0].discipleName, "王五");
    ASSERT_EQ(decoded.productionSlots.size(), 1u);
    EXPECT_EQ(decoded.productionSlots[0].buildingType, "FORGE");
    EXPECT_FALSE(decoded.productionSlots[0].recipeId.has_value());
    EXPECT_EQ(decoded.placedBuildings[0].gridX, 3);
    EXPECT_EQ(decoded.alliances[0].sectIds, std::vector<std::string>({"s-1", "s-2"}));
    EXPECT_EQ(decoded.worldMapSects[0].disciples.at(2), 3);
    EXPECT_EQ(decoded.worldLevels[0].beastType.value(), 1);
    EXPECT_EQ(decoded.patrolConfig.targetRealms, std::vector<int32_t>({5, 6, 7}));
    EXPECT_EQ(decoded.travelingMerchantItems[0].price, 100);
    EXPECT_EQ(decoded.mailRecords[0].mailId, "mail-1");
    EXPECT_EQ(decoded.pendingTraitAdds[0].traitId, "t-1");
}

TEST(JsonCodecTest, LenientFromJsonMissingFieldsUseDefaults) {
    const nlohmann::json j = nlohmann::json::parse(R"({"gameYear": 42})");
    GameData d = j.get<GameData>();
    EXPECT_EQ(d.gameYear, 42);
    EXPECT_EQ(d.sectName, "青云宗");   // 默认值保留
    EXPECT_EQ(d.spiritStones, 1000);   // 默认值保留
    EXPECT_TRUE(d.unlockedRecipes.empty());
}

TEST(JsonCodecTest, DumpStateNormalizesIntegralDoubles) {
    // kotlinx 流式解码器拒绝 "N.0" 格式 → 整数值 double 导出为整数形式
    GameState st;
    st.gameData.gameYear = 5;
    st.gameData.sectCultivation = 0.0;       // 整值 → "0"
    st.disciples.push_back(Disciple{});
    st.disciples[0].cultivationCheckpoint = 12000.0;  // 整值 → "12000"
    st.disciples[0].cultivation = 12345.6;             // 非整值保留

    const std::string dumped = dumpStateJson(st);
    EXPECT_NE(std::string::npos, dumped.find("\"cultivationCheckpoint\":12000"));
    EXPECT_EQ(std::string::npos, dumped.find("\"cultivationCheckpoint\":12000.0"));
    EXPECT_NE(std::string::npos, dumped.find("\"cultivation\":12345.6"));
    EXPECT_NE(std::string::npos, dumped.find("\"sectCultivation\":0"));
    EXPECT_EQ(std::string::npos, dumped.find("\"sectCultivation\":0.0"));

    // 规范化后仍是合法 JSON 且往返值不变
    const auto parsed = nlohmann::json::parse(dumped).get<GameState>();
    EXPECT_DOUBLE_EQ(parsed.disciples[0].cultivationCheckpoint, 12000.0);
    EXPECT_DOUBLE_EQ(parsed.disciples[0].cultivation, 12345.6);
}

}  // namespace
}  // namespace gamecore::state
