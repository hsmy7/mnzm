// ============================================================
// gacha_pool_db.h — 寻访卡池与角色模板的 C++ 只读表（G09 数据面）
//
// ## 为什么需要本头
// C++ 是 AUTHORITATIVE 真相源，抽卡 roll（类别 / 品阶 / 保底归属）必须在 C++
// 完成，否则「概率由谁决定」会分裂成两处。而这两张表此前只存在于
// `assets/data/game-data.json` 的 `db.gachaPools` / `db.characterTemplates` 段
// 与 Kotlin 侧 `CharacterTemplateDb`，C++ 无读取器 ⇒ roll 无法下沉。
// 本头提供结构 + 容器，由 `data_inject.h` 在引擎初始化期一次性注入。
//
// ## 单一真源（为什么这里**没有**内联兜底条目）
// `data/*.h` 的其余六张表把条目数值同时内联为编译期兜底，前提是那些数值
// 历史上就写在 C++ 头里（B16 外置）。卡池表不同：概率表由
// `scripts/data/gacha_config_sample.json` 经 `scripts/gen-game-data.mjs` 单源
// 产出，C++ 再抄一份字面量即构成第二真源（改配置不重编译就静默按旧概率出货，
// 且 `gen-game-data.mjs --check` 管不住 C++ 侧）。故本头**只定义结构与空容器**：
//   - 注入成功 ⇒ 表 = 数据文件值；
//   - 未注入 / 注入失败 ⇒ 表为空 ⇒ 抽卡以 `PoolNotFound` 失败信封**显式**拒绝，
//     不存在"按另一套数值静默出货"的中间态。
//   `test/data_store_test.cpp` 断言「有段即非空」+「AppliedCounts 与段长一致」
//   把这条兜底口径固化成守卫。
//
// ## 字段命名
// JSON 键 = C++ 字段名（逐字段同名，与 `data_json.h` 其余表同一约定）；
// 缺字段按宽松口径保留结构体默认值，段级硬校验在 `data_inject.h`。
//
// ## 确定性
// 消费点**禁止**依赖 `categories` / `templateIds` 的迭代序做随机以外的业务判定
// 之外的分支；抽取用的候选序一律由 `gacha_tx.h` 显式排序（物品按 id 升序）。
// 表本身的顺序是数据文件数组序，两侧同源（`CharacterTemplateGuardTest` 钉死
// 「六条同序同值」），故保底按表序取下标在 Kotlin/C++ 双臂一致。
// ============================================================
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace gamecore::data {

/// 保底配置（`gachaPools[].pity`）——第 pullThreshold 抽本身发 fragmentCount 片
struct GachaPityConfig {
    int32_t pullThreshold = 0;
    int32_t fragmentCount = 0;
    /// 保底归属挑选方式："random"（池内全部角色随机）/"singleSpiritRoot"
    /// （池内单灵根角色随机）；其余取值按池不自洽拒绝
    std::string pickMode;
};

inline bool operator==(const GachaPityConfig& a, const GachaPityConfig& b) {
    return a.pullThreshold == b.pullThreshold && a.fragmentCount == b.fragmentCount &&
        a.pickMode == b.pickMode;
}

/// 物品品阶权重（`gachaPools[].itemRarityWeights[]`，weightPct 全池和为 100）
struct GachaRarityWeight {
    int32_t rarity = 0;
    int32_t weightPct = 0;
};

inline bool operator==(const GachaRarityWeight& a, const GachaRarityWeight& b) {
    return a.rarity == b.rarity && a.weightPct == b.weightPct;
}

/// 池内一个类别（角色类填 templateIds；物品类填 itemSource + maxRarity）
struct GachaCategory {
    /// 类别标识：`character_single` / `character_double` / `beast_material` /
    /// `herb` / `seed`——角色类的判据是 [isCharacter]，不在此枚举外新增语义
    std::string kind;
    int32_t weightPct = 0;
    /// 角色类候选模板 id（声明序即抽取序）；物品类为空
    std::vector<std::string> templateIds;
    /// 物品类取哪张模板表：`herbs` / `seeds` / `beastMaterials`；角色类为空
    std::string itemSource;
    /// 该类别投放的最高品阶（池内品阶截断口径）
    int32_t maxRarity = 0;

    /// 是否角色类（命中即入碎片账本，不入仓库）
    bool isCharacter() const { return kind.rfind("character", 0) == 0; }
};

inline bool operator==(const GachaCategory& a, const GachaCategory& b) {
    return a.kind == b.kind && a.weightPct == b.weightPct &&
        a.templateIds == b.templateIds && a.itemSource == b.itemSource &&
        a.maxRarity == b.maxRarity;
}

/// 卡池模板（`db.gachaPools[]`）——当前只有一张常驻池，多池结构按配置预留
struct GachaPoolTemplate {
    std::string poolId;
    /// 缺键即 false：拿不准的配置不允许被玩家抽到（显式失败优于静默可抽）
    bool enabled = false;
    int32_t pricePerPull = 0;
    std::vector<GachaCategory> categories;
    std::vector<GachaRarityWeight> itemRarityWeights;
    /// 角色碎片数量权重（下标 i = i+1 片；weightPct 全池和为 100）——普通角色抽掷点用
    std::vector<int32_t> fragmentCountWeights;
    /// 物品数量权重（下标 i = i+1 件；weightPct 全池和为 100，钟形近似正态）——物品抽掷点用
    std::vector<int32_t> itemCountWeights;
    GachaPityConfig pity;
    /// 池内升星门槛/上限：与 `gacha_fragment.h` 的三向常量为**同一口径**，
    /// 由 `CharacterTemplateGuardTest` 看护（抽卡侧只读不写死）
    int32_t fragmentsPerStar = 0;
    int32_t maxStar = 0;
};

inline bool operator==(const GachaPoolTemplate& a, const GachaPoolTemplate& b) {
    return a.poolId == b.poolId && a.enabled == b.enabled &&
        a.pricePerPull == b.pricePerPull && a.categories == b.categories &&
        a.itemRarityWeights == b.itemRarityWeights &&
        a.fragmentCountWeights == b.fragmentCountWeights &&
        a.itemCountWeights == b.itemCountWeights && a.pity == b.pity &&
        a.fragmentsPerStar == b.fragmentsPerStar && a.maxStar == b.maxStar;
}

/// 角色模板（`db.characterTemplates[]`）——抽卡侧只取 id 做归属与解锁校验，
/// 头像/立绘键由 UI 层（G11）经 Kotlin `CharacterTemplateDb` 取，不经本表
struct CharacterTemplate {
    std::string id;
    std::string name;
    std::string gender;
    std::string avatarKey;
    std::string portraitKey;
    std::vector<std::string> spiritRoots;
    // 固有伤害属性（"PHYSICAL"/"MAGIC"；B1 §15.3 / InnateDamageType.derive
    // 模板优先臂——Kotlin CharacterTemplate.innateDamageType 镜像面）
    std::string innateDamageType;
};

inline bool operator==(const CharacterTemplate& a, const CharacterTemplate& b) {
    return a.id == b.id && a.name == b.name && a.gender == b.gender &&
        a.avatarKey == b.avatarKey && a.portraitKey == b.portraitKey &&
        a.spiritRoots == b.spiritRoots &&
        a.innateDamageType == b.innateDamageType;
}

/// 卡池表容器（注入期写入；`const&` 消费口在 [gachaPools]）
inline std::vector<GachaPoolTemplate>& gachaPoolsMutable() {
    static std::vector<GachaPoolTemplate> kPools;
    return kPools;
}

/// 全部卡池（未注入时为空——见头注释「无内联兜底」口径）
inline const std::vector<GachaPoolTemplate>& gachaPools() {
    return gachaPoolsMutable();
}

/// 角色模板表容器
inline std::vector<CharacterTemplate>& characterTemplatesMutable() {
    static std::vector<CharacterTemplate> kTemplates;
    return kTemplates;
}

/// 全部角色模板
inline const std::vector<CharacterTemplate>& characterTemplates() {
    return characterTemplatesMutable();
}

/// 按 poolId 查池（线性扫——池规模 O(1)，低频调用）
inline const GachaPoolTemplate* gachaPoolById(const std::string& poolId) {
    for (const auto& p : gachaPools()) {
        if (p.poolId == poolId) return &p;
    }
    return nullptr;
}

/// 按角色模板 id 查模板（线性扫——模板规模 O(10)）
inline const CharacterTemplate* characterTemplateById(const std::string& id) {
    for (const auto& t : characterTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

}  // namespace gamecore::data
