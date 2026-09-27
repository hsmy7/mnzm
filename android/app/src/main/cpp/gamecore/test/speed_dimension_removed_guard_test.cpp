#include <gtest/gtest.h>

#include <fstream>
#include <string>
#include <vector>

// ============================================================
// speed_dimension_removed_guard_test — 速度维度删除 C++ 侧结构守卫
//
// 守护目标（docs/design/remove-2x-speed-implementation-plan.md §6.3；
// 与 Kotlin 侧 SpeedDimensionRemovedGuardTest 对称）：
//   1. PhaseClock（engine_loop.h）/ SettlementEngine（settlement.h）/
//      ProgressMonitor（watchdog.h）符号面无速度状态机残留
//      （setSpeed/speed_/int speed/kMsPerPhase1x）。
//   2. JNI 桥无速度端口（nativeLoopSetSpeed / nativeCoreLoopSetSpeed /
//      jint speed 形参）。
//   3. 双端追补上限/旬时常量钉死（kMsPerPhase=2000 / kMaxPhasesPerTick=3）。
//
// 故意排除（同名不同义，不扫描）：角色属性 speed（身法/速度加成）——
// disciple_stats.h / battle_calculator.h / level_generator.h 等战力面的
// speed 字段与「时间倍速」无关。
// ============================================================

namespace gamecore {
namespace {

/// 读仓库 cpp 根相对路径的源文件（MR1_CPP_ROOT 由 test/CMakeLists.txt 定义）
std::string readSource(const std::string& relativePath) {
    const std::string root = MR1_CPP_ROOT;
    std::ifstream in(root + "/" + relativePath, std::ios::binary);
    if (!in) return {};
    return std::string(std::istreambuf_iterator<char>(in),
                       std::istreambuf_iterator<char>());
}

constexpr const char* kGuardMessage =
    "「游戏倍速」维度已删除（见 docs/design/remove-2x-speed-implementation-plan.md）。"
    "若确需重新引入时间倍率，须先扩 C++ PhaseClock 协议并双端对拍，"
    "禁止在 UI/ViewModel 层加回 timescale 开关";

/// 白名单头文件 → 禁现符号（子串匹配；注释一并禁现）
TEST(SpeedDimensionRemovedGuardTest, EngineHeadersHaveNoSpeedStateMachine) {
    const std::vector<std::pair<std::string, std::vector<std::string>>> files = {
        {"gamecore/include/gamecore/system/engine_loop.h",
         {"setSpeed", "speed_", "int speed", "kMsPerPhase1x"}},
        {"gamecore/include/gamecore/system/settlement.h",
         {"setSpeed", "speed_", "int speed", "kMsPerPhase1x", "maxPhasesPerTick("}},
        {"gamecore/include/gamecore/system/watchdog.h",
         {"setSpeed", "speed_", "int speed"}},
    };
    for (const auto& [path, tokens] : files) {
        const std::string src = readSource(path);
        ASSERT_FALSE(src.empty()) << "守卫定位失败：" << path << " 不可读";
        for (const auto& token : tokens) {
            EXPECT_EQ(src.find(token), std::string::npos)
                << path << " 含被禁符号「" << token << "」——" << kGuardMessage;
        }
    }
}

TEST(SpeedDimensionRemovedGuardTest, JniBridgesHaveNoSpeedPorts) {
    const std::string jni = readSource("gamecore/jni/GameCoreJni.cpp");
    ASSERT_FALSE(jni.empty()) << "gamecore/jni/GameCoreJni.cpp 不可读";
    EXPECT_EQ(jni.find("LoopSetSpeed"), std::string::npos)
        << "对拍桥残留速度端口——" << kGuardMessage;

    const std::string bridge = readSource("GameCoreBridge.cpp");
    ASSERT_FALSE(bridge.empty()) << "GameCoreBridge.cpp 不可读";
    EXPECT_EQ(bridge.find("LoopSetSpeed"), std::string::npos)
        << "生产桥残留速度端口——" << kGuardMessage;
}

TEST(SpeedDimensionRemovedGuardTest, SingleSpeedConstantsPinned) {
    const std::string settlement = readSource("gamecore/include/gamecore/system/settlement.h");
    ASSERT_FALSE(settlement.empty());
    EXPECT_NE(settlement.find("constexpr int64_t kMsPerPhase = 2000;"), std::string::npos)
        << "kMsPerPhase 应为 2000ms（1 旬 = 2s 墙钟，与 Kotlin MS_PER_PHASE 双端同值）";
    EXPECT_NE(settlement.find("constexpr int kMaxPhasesPerTick = 3;"), std::string::npos)
        << "kMaxPhasesPerTick 应为 3（与速度解耦的常量，与 Kotlin MAX_PHASES_PER_TICK 双端同值）";
}

}  // namespace
}  // namespace gamecore
