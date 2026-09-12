// ============================================================
// child_birth.h — 月变步骤 4d 生育
//
// 等价复刻 Kotlin `ChildBirthSystem.processMonthlyBirth`（core/engine/
// system/ChildBirthSystem.kt）——月变扇出中 @SystemPriority(235) 的生育
// 子事件：到期母亲（childBirthMonth == 当前月）逐人生育，新生儿入
// recruitList 并触发自动招募惰性重置 + processAutoRecruit。
//
// 确定性要点（与 Kotlin 逐位对齐，供 GTest 黄金序列 + Diff 对拍验证）：
//   - SYSTEM 分区 RNG 消费序（每正常分支母亲，与 Kotlin createChild 同序）：
//     ① 性别 nextInt(2)
//     ② inheritName（name_service.h：nextDouble 定双字/单字 + nextInt(bound)
//        选名，最多 50 次冲突尝试 + 数字后缀兜底）
//     ③ 灵根继承 nextInt(100)：0..29 父 / 30..59 母 / ≥60 SpiritRootGenerator
//        （1 次 nextDouble 定根数 + 4 次 nextInt(i+1) Fisher-Yates 洗牌）
//     ④ createDisciple（disciple_factory.h 固定序：14 次方差 + 2 次阶梯 +
//        三分类生成 + 1 次肖像 + 18 次技能）
//   - 父亲死亡分支零 RNG（清 childBirthMonth + partnerId 增量 update）
//   - 快照语义与 Kotlin 一致：母亲列表与 discipleMap 取 processMonthlyBirth
//     入口快照；createChild 内 existingNames 取**当前态**（recruitList 已含
//     前序新生儿——Kotlin 每轮重新组装）
//   - 新生儿 id 为镜像生成字段（Kotlin UUID.randomUUID 非确定性）——C++
//     侧空串占位，对拍 diff 面排除（同 worldLevels id 契约）；其余字段
//     （名字/性别/灵根/属性/双亲）逐位一致参与对拍
//   - autoRejectIdle（Kotlin RecruitLazyState 瞬态）C++ 侧无对应字段——
//     C++ processAutoRecruit 不消费，对拍 diff 面不承载
// ============================================================
#pragma once

#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/ecs/disciple_component.h"  // syncDiscipleEntities 行序桥接
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_factory.h"
#include "gamecore/system/name_service.h"
#include "gamecore/system/recruit_settlement.h"

namespace gamecore::system::child_birth {

// ============================================================
// SpiritRootGenerator（Kotlin core/util/SpiritRootGenerator.kt 等价）
// ============================================================

/// 灵根数权重（GameConfig.SpiritRoot.COUNT_WEIGHTS 升序：1% / 3% / 26% / 30% / 40%）
inline const std::vector<std::pair<int32_t, double>>& kRootCountWeights() {
    static const std::vector<std::pair<int32_t, double>> kWeights = {
        {1, 0.01}, {2, 0.03}, {3, 0.26}, {4, 0.30}, {5, 0.40}};
    return kWeights;
}

/// 灵根元素 key 序（Kotlin SpiritRootGenerator.ELEMENTS）
inline const std::vector<std::string>& rootElements() {
    static const std::vector<std::string> kElements = {
        "metal", "wood", "water", "fire", "earth"};
    return kElements;
}

/// 随机灵根（Kotlin SpiritRootGenerator.generate）：1 次 nextDouble 定根数 +
/// Fisher-Yates 洗牌（Kotlin shuffled：lastIndex→1 递减，j=nextInt(i+1) 交换）
/// 后取前 rootCount 个逗号连接
inline std::string generateSpiritRoot(rng::DeterministicRng& rng) {
    const double rand = rng.nextDouble();
    int32_t rootCount = 5;  // 权重和 <1.0 兜底（Kotlin 回退 5 灵根）
    double cumulative = 0.0;
    for (const auto& [count, weight] : kRootCountWeights()) {
        cumulative += weight;
        if (rand < cumulative) {
            rootCount = count;
            break;
        }
    }
    std::vector<std::string> shuffled = rootElements();
    for (std::size_t i = shuffled.size() - 1; i >= 1; --i) {
        const std::size_t j = static_cast<std::size_t>(
            rng.nextInt(static_cast<int32_t>(i + 1)));
        std::swap(shuffled[i], shuffled[j]);
    }
    std::string out;
    for (int32_t i = 0; i < rootCount; ++i) {
        if (i > 0) out += ",";
        out += shuffled[static_cast<std::size_t>(i)];
    }
    return out;
}

// ============================================================
// createChild（Kotlin ChildBirthSystem.createChild 等价）
// ============================================================

/// 生育单个新生儿（Kotlin createChild——SYSTEM 分区消费序见文件头注释）。
/// [mother]/[father] 为 processMonthlyBirth 入口快照；[existingNames] 取
/// 当前态（含前序新生儿）——调用方（本文件 processMonthlyBirth）负责
/// 每轮重建。
inline state::Disciple createChild(const state::Disciple& mother,
                                   const state::Disciple& father,
                                   int32_t /*currentYear*/,
                                   const std::set<std::string>& existingNames,
                                   rng::DeterministicRng& rng) {
    // ① 性别（1 次 nextInt）
    const std::string gender = (rng.nextInt(2) == 0) ? "male" : "female";

    // ② 继承姓氏 + 名字（inheritName 内部消费；函数位于 gamecore::system 直接命名空间）
    const std::string fatherSurname = father.surname.empty()
        ? ::gamecore::system::extractSurname(father.name)
        : father.surname;
    const auto nameResult =
        ::gamecore::system::inheritName(fatherSurname, gender, existingNames, rng);

    // ③ 灵根继承（1 次 nextInt；≥60 走 SpiritRootGenerator）
    std::string spiritRootType;
    const int32_t roll = rng.nextInt(100);
    if (roll < 30) {
        spiritRootType = father.spiritRootType;
    } else if (roll < 60) {
        spiritRootType = mother.spiritRootType;
    } else {
        spiritRootType = generateSpiritRoot(rng);
    }

    // ④ 弟子生成（createDisciple 固定消费序）；id 为镜像生成字段（空串占位）
    DiscipleCreationSeed seed;
    seed.id = "";
    seed.gender = gender;
    seed.fullName = nameResult.fullName;
    seed.surname = nameResult.surname;
    seed.spiritRootType = spiritRootType;
    seed.age = 1;
    seed.realmLayer = 0;
    state::Disciple child = createDisciple(seed, rng);
    child.parentId1 = mother.id;
    child.parentId2 = father.id;
    return child;
}

// ============================================================
// processMonthlyBirth（Kotlin ChildBirthSystem.processMonthlyBirth 等价）
// ============================================================

/// 月度生育（Kotlin onMonthlyEvent → processMonthlyBirth 等价）：
/// 到期母亲（isAlive && childBirthMonth == 当前月）逐人生育——父亲死亡清
/// 孕期状态；正常生育新生儿入 recruitList + autoRecruitIdle 重置 +
/// processAutoRecruit + 母亲 lastChildYear/childBirthMonth 增量更新。
/// 母亲列表与 discipleMap 取入口快照（Kotlin assembleAll 一次）。
/// 入口快照/existingNames 扫描经 sync + View
/// 行序（快照序 == 行序 == Kotlin assembleAll 序，母亲 RNG 消费序不变）。
inline void processMonthlyBirth(state::GameState& state,
                                rng::DeterministicRng& rng,
                                ecs::World& world) {
    const int32_t currentYear = state.gameData.gameYear;
    const int32_t currentMonth = state.gameData.gameMonth;

    // 快照：全部弟子 + id → Disciple map（Kotlin assembleAll + associateBy；
    // 重复 id 保留最后——associateBy 语义）
    std::vector<state::Disciple> allDisciples;
    allDisciples.reserve(state.disciples.size());
    std::map<std::string, state::Disciple> discipleMap;
    {
        state::DiscipleStore& ds = state.disciples;
        ecs::syncDiscipleEntities(world, ds.size());
        ecs::View<ecs::DiscipleRef> view(world.registry());
        view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
            state::Disciple d = ds.materialize(ref.row);   // 行地址取自组件（桥接规范 3）
            allDisciples.push_back(d);
            discipleMap[d.id] = d;
        });
    }

    // 到期母亲（快照列表；行序 = 弟子序，RNG 消费序红线）
    std::vector<state::Disciple> mothersDue;
    for (const auto& mother : allDisciples) {
        if (mother.isAlive && mother.childBirthMonth == currentMonth) {
            mothersDue.push_back(mother);
        }
    }
    if (mothersDue.empty()) return;

    for (const auto& mother : mothersDue) {
        const std::string& fatherId = mother.partnerId;
        if (fatherId.empty()) continue;
        const auto fatherIt = discipleMap.find(fatherId);
        const bool fatherDead =
            fatherIt == discipleMap.end() || !fatherIt->second.isAlive;

        if (fatherDead) {
            // 父亲死亡：清 childBirthMonth + partnerId（增量 update 保序）
            state::Disciple m = mother;
            m.childBirthMonth = 0;
            m.partnerId.clear();
            state.disciples.upsertDisciple(m);
            continue;
        }

        // 当前态 existingNames：全部弟子（当前列）+ recruitList（含前序
        // 新生儿——Kotlin createChild 内每轮重新组装，父死分支不计算）。
        // processAutoRecruit 可能已追加行（实体集漂移）——sync 检测即重建。
        std::set<std::string> existingNames;
        {
            state::DiscipleStore& ds = state.disciples;
            ecs::syncDiscipleEntities(world, ds.size());
            ecs::View<ecs::DiscipleRef> view(world.registry());
            view.forEach([&](ecs::EntityId, ecs::DiscipleRef& ref) {
                existingNames.insert(ds.names[ref.row]);   // 行地址取自组件（桥接规范 3）
            });
        }
        for (const auto& d : state.gameData.recruitList) {
            existingNames.insert(d.name);
        }

        const auto child = createChild(mother, fatherIt->second, currentYear,
                                       existingNames, rng);
        state.gameData.recruitList.push_back(child);
        // 新生儿产生后立即执行自动招募检查 + 重置惰性（Kotlin 同语义；
        // autoRejectIdle 为 Kotlin 瞬态，C++ 无对应字段）
        state.autoRecruitIdle = false;
        recruit_settle::processAutoRecruit(state);

        // 母亲增量更新（保序，避免覆盖 processAutoRecruit 已插入的弟子）
        state::Disciple m = mother;
        m.lastChildYear = currentYear;
        m.childBirthMonth = 0;
        state.disciples.upsertDisciple(m);
    }
}

}  // namespace gamecore::system::child_birth
