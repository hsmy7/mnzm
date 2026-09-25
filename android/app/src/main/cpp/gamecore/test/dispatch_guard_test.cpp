/**
 * dispatch_guard_test.cpp — ActionId 分派覆盖守卫（W4-00 并行前置批新增）。
 *
 * ## 这个守卫防的是什么
 * `GameCore::execute` 的分发表是一段**连续的 `else if` 区间链**。这种写法有一个
 * 已经真实发生过的缺陷类：
 *
 * > 1730（`BEAST_VIEW_LOCK_TX`）被写成了"1520–1531 区间"的一部分，于是它被吞进
 * > 库存 handler，返回 `"inventory tx action 1730"` + `UNKNOWN_ACTION`
 * > （事故注释至今留在 `execute_dispatch.cpp` 的 `STORAGE_BAG_OPEN_TX` 分支上方）。
 *
 * 症状隐蔽：动作号**存在**、`action_ids.h` **有**常量、Kotlin 侧**能**发出去，
 * 只是永远走不到自己的 handler。单点用例发现不了，只有"对**每一个**已注册动作号
 * 断言分派可达"才能拦住。
 *
 * ## 守卫怎么判定
 * 对 `action::kAllActionIds`（生成器产出的全量已注册动作号）逐个调用
 * `GameCore::execute(id, "{}")`，断言结果信封的 `code` **既不是**
 * `NOT_IMPLEMENTED`（分发表无任何分支认领）**也不是** `UNKNOWN_ACTION`
 * （落到了别的域 handler 的 `default:` 兜底）。二者都表示"该动作号没被正确注册"。
 *
 * 业务失败（参数缺失 / 前置不满足 / 资源不足）**不算失败**——本守卫只判定
 * "分派可达性"，不判定业务语义。
 *
 * ## 为什么由生成器提供动作号清单
 * 手写清单会与实际注册漂移（历史教训：文档与代码的 ActionId 计数长期不一致）。
 * `kAllActionIds` 由 `scripts/gen-action-ids.mjs` 从同一份 catalog 产出 ⇒
 * 新增动作忘接分派时本测试**自动变红**，且报错信息直接给出动作号。
 */

#include <gtest/gtest.h>

#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"

namespace gamecore {
namespace {

/// 分派缺失的两种表现（见文件头说明）。
bool isDispatchGap(const std::string& code) {
    return code == "NOT_IMPLEMENTED" || code == "UNKNOWN_ACTION";
}

class DispatchGuardFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    /// 执行一次动作并返回结果信封（解析失败时返回含 `_parseError` 的对象）。
    nlohmann::json exec(int32_t actionId) {
        const std::string raw = core_->execute(actionId, "{}", 1000);
        nlohmann::json parsed = nlohmann::json::parse(raw, nullptr, false);
        if (parsed.is_discarded() || !parsed.is_object()) {
            return nlohmann::json{{"_parseError", raw}};
        }
        return parsed;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 生成物自身的完整性 ──────────────────────────────────────────────

TEST(DispatchGuardCatalog, ActionIdListIsNotEmpty) {
    EXPECT_GT(action::kAllActionIdsCount, 0)
        << "kAllActionIds 为空——生成器产出异常（scripts/gen-action-ids.mjs）";
}

TEST(DispatchGuardCatalog, ActionIdListIsStrictlyAscendingAndUnique) {
    std::set<int32_t> seen;
    for (int i = 0; i < action::kAllActionIdsCount; ++i) {
        const int32_t id = action::kAllActionIds[i];
        EXPECT_TRUE(seen.insert(id).second)
            << "kAllActionIds 出现重复动作号 " << id
            << "——同一动作号被两处代码驱动（生成器已内置 id 唯一断言，此处为二道闸门）";
        if (i > 0) {
            EXPECT_LT(action::kAllActionIds[i - 1], id)
                << "kAllActionIds 未按升序排列（下标 " << i << "）";
        }
    }
}

// ── 核心守卫：每个已注册动作号都必须分派可达 ────────────────────────

TEST_F(DispatchGuardFixture, EveryRegisteredActionIdReachesItsOwnDomainHandler) {
    // 退役动作号（catalog 保留 + desc 标注废弃 + 删 dispatch case——
    // ActionId 只增不复用）：分派不可达是退役的预期形态，单列断言。
    const std::set<int32_t> retired = {
        action::DISCIPLE_MAX_AGE,          //1103 老死链删除
        action::DISCIPLE_AGE,              //1106 老化链删除
        action::REDEEM_ROLL_SKILLS,        //1437 资质哨兵生成下线（G04）
        action::DISCIPLE_LIFECYCLE_EXPEL,  //1590 逐出事务下线（G06）
        action::DISCIPLE_LIFECYCLE_APPRENTICE,  //1591 师徒玩法下线（G15）
        action::DISCIPLE_LIFECYCLE_MARRY_APPROVE,  //1592 婚姻批准下线（G03）
        action::DISCIPLE_LIFECYCLE_RELEASE_REFLECTION,  //1593 思过释放无实现
        action::WAREHOUSE_GARRISON_TX,     //1612 仓库驻守无实现
        action::SPIRIT_ROOT_WASH_TX,       //1613 灵根洗炼下线（G04）
        action::TRAIT_ADD_ROLL_TX,         //1614 特质新增下线（G04）
        action::TRAIT_ADD_CONFIRM_TX,      //1615 特质新增下线（G04）
        action::TRAIT_WASH_SLOT_TX,        //1616 特质洗炼下线（G04）
        action::RECRUIT_REMOVE_TX,         //1630 招募链下线（G05）
        action::RECRUIT_REFRESH_TX,        //1631 招募链下线（G05）
        action::RECRUIT_AGE_TX,            //1632 招募链下线（G05）
        action::GOV_OPEN_RECRUITMENT_TOGGLE_TX,  //1681 广纳门徒下线（G05）
        action::SECT_ATTACK_GRANT_SOUL_POWERS_TX,       //1712 魂力授予无实现
        action::SPIRIT_ROOT_WASH_CONFIRM_TX,  //1732 灵根洗炼下线（G04）
        action::TRAIT_WASH_CONFIRM_TX,     //1733 特质洗炼下线（G04）
        action::DISCIPLE_OP_RENAME,        //1740 弟子改名下线（G06）
        action::DISCIPLE_OP_START_BLOOD_REFINEMENT,  //1746 血炼下线（G04）
        action::DISCIPLE_LIFECYCLE_MARRY_REJECT,  //1750 婚姻拒绝下线（G03）
    };
    std::vector<std::string> gaps;
    for (int i = 0; i < action::kAllActionIdsCount; ++i) {
        const int32_t id = action::kAllActionIds[i];
        const auto r = exec(id);
        if (r.contains("_parseError")) {
            gaps.push_back("id=" + std::to_string(id) + " 结果非 JSON 对象: " +
                           r.at("_parseError").get<std::string>());
            continue;
        }
        const std::string code = r.value("code", std::string{});
        if (retired.count(id) != 0) {
            EXPECT_TRUE(isDispatchGap(code))
                << "退役动作号 " << id << " 仍被分派认领——退役口径要求删 dispatch case";
            continue;
        }
        if (isDispatchGap(code)) {
            gaps.push_back("id=" + std::to_string(id) + " code=" + code +
                           " message=" + r.value("message", std::string{}));
        }
    }
    std::string joined;
    for (const auto& g : gaps) {
        joined += "\n  - " + g;
    }
    EXPECT_TRUE(gaps.empty())
        << "以下已注册动作号**分派不可达**（分发表区间写法吞掉了它们，或忘记接线）"
        << "——这是真实发生过的缺陷类（1730 被 1520–1531 区间吞进库存 handler）："
        << joined
        << "\n修法：在 GameCore::execute 的分派链里为该动作号补独立分支；"
        << "W4 三批次的动作号应落在各自 dispatch_w4{a,b,c}.cpp 端口内。";
}

// ── W4 端口骨架的负向契约（W4-00 建立时恒成立；批次接线后由各批自查）──

TEST_F(DispatchGuardFixture, W4UnaffiliatedActionIdStillReportsNotImplemented) {
    // 1735（W4 预留段之外、未注册）应落入最终兜底。
    const auto r = exec(1735);
    EXPECT_EQ(r.value("status", std::string{}), "failure");
    EXPECT_EQ(r.value("code", std::string{}), "NOT_IMPLEMENTED");
}

}  // namespace
}  // namespace gamecore
