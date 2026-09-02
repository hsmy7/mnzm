#include <gtest/gtest.h>

#include <memory>
#include <string>
#include <vector>

#include "gamecore/ecs/system.h"
#include "gamecore/ecs/view.h"
#include "gamecore/ecs/world.h"

namespace gamecore::ecs {
namespace {

struct Pos {
    int x = 0;
    int y = 0;
};

// 记录执行序的简单系统
class TestSystem : public ISystem {
public:
    TestSystem(std::string name, int priority, std::vector<std::string>& log)
        : name_(std::move(name)), priority_(priority), log_(log) {}

    const char* name() const override { return name_.c_str(); }
    int priority() const override { return priority_; }
    void run(World&) override { log_.push_back(name_); }

private:
    std::string name_;
    int priority_;
    std::vector<std::string>& log_;
};

TEST(SystemSchedulerTest, RunsInPriorityThenAttachOrder) {
    SystemScheduler scheduler;
    std::vector<std::string> log;
    scheduler.attach(std::make_unique<TestSystem>("c", 3, log));
    scheduler.attach(std::make_unique<TestSystem>("a", 1, log));
    scheduler.attach(std::make_unique<TestSystem>("b", 1, log));
    scheduler.attach(std::make_unique<TestSystem>("d", 2, log));

    World world;
    scheduler.runAll(world);
    EXPECT_EQ(log, (std::vector<std::string>{"a", "b", "d", "c"}));
}

TEST(SystemSchedulerTest, DeterministicAcrossRuns) {
    SystemScheduler scheduler;
    std::vector<std::string> log;
    scheduler.attach(std::make_unique<TestSystem>("x", 5, log));
    scheduler.attach(std::make_unique<TestSystem>("y", 1, log));
    scheduler.attach(std::make_unique<TestSystem>("z", 3, log));

    World world;
    std::vector<std::string> run1, run2, run3;
    // 换 log 引用重跑 3 次
    log.clear(); scheduler.runAll(world); run1 = log;
    log.clear(); scheduler.runAll(world); run2 = log;
    log.clear(); scheduler.runAll(world); run3 = log;
    EXPECT_EQ(run1, run2);
    EXPECT_EQ(run2, run3);
    EXPECT_EQ(run1, (std::vector<std::string>{"y", "z", "x"}));
}

TEST(SystemSchedulerTest, DefaultNotParallelizable) {
    std::vector<std::string> log;
    TestSystem sys("s", 1, log);
    EXPECT_FALSE(sys.isParallelizable());
}

TEST(SystemSchedulerTest, SystemConsumesViewComponents) {
    // 系统通过 View 迭代组件并批量处理
    World world;
    auto& pos = world.registry().storage<Pos>();
    for (int i = 0; i < 4; ++i) {
        const EntityId e = world.createEntity();
        pos.addOrAssign(e, Pos{i, 0});
    }

    struct SumSystem : public ISystem {
        int total = 0;
        const char* name() const override { return "sum"; }
        int priority() const override { return 0; }
        void run(World& w) override {
            View<Pos> view(w.registry());
            view.forEach([&](EntityId, Pos& p) { total += p.x; });
        }
    };
    SumSystem sys;
    sys.run(world);
    EXPECT_EQ(sys.total, 0 + 1 + 2 + 3);
}

TEST(SystemSchedulerTest, ClearResets) {
    SystemScheduler scheduler;
    std::vector<std::string> log;
    scheduler.attach(std::make_unique<TestSystem>("a", 1, log));
    EXPECT_EQ(scheduler.size(), 1u);
    scheduler.clear();
    EXPECT_EQ(scheduler.size(), 0u);
}

}  // namespace
}  // namespace gamecore::ecs
