// ============================================================
// game_data_json.h — 测试侧定位并读取 `assets/data/game-data.json`
//
// 为什么独立一个头：本仓库的桌面 GTest 由 `gtest_discover_tests` 逐例拉起，
// 工作目录随运行方式而变（ctest 从 build 目录跑、手工从源码根跑），故定位
// 必须带多候选兜底。这段兜底此前只在 `test/data_store_test.cpp` 里有一份，
// 任何需要"注入真实配置再断言"的新用例都会被迫手抄一遍——抄漏候选即表现为
// "文件不存在"的假失败。收敛到此，两处共用同一份判据。
//
// 口径：**定位不到即 fail-fast**（调用方不得静默跳过守卫）。
// ============================================================
#pragma once

#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace gamecore::testsupport {

/// 定位数据文件（首个命中即用；空串 = 未找到）
inline std::string locateGameDataJson() {
    const std::vector<std::string> candidates = {
        // ctest 的 WORKING_DIRECTORY = gamecore 源码根（test/CMakeLists.txt）
        "android/app/src/main/assets/data/game-data.json",
        // 直接从构建目录手工运行时的逐级上溯兜底
        "../../../../../android/app/src/main/assets/data/game-data.json",
        "../../../../../../android/app/src/main/assets/data/game-data.json",
        "../../../../../../app/src/main/assets/data/game-data.json",
        "app/src/main/assets/data/game-data.json",
    };
    for (const auto& c : candidates) {
        std::ifstream f(c);
        if (f.good()) return c;
    }
    return {};
}

/// 读取数据文件全文（未找到返回空串，由调用方登记 ADD_FAILURE）
inline std::string readGameDataJson() {
    const std::string path = locateGameDataJson();
    if (path.empty()) return {};
    std::ifstream f(path, std::ios::binary);
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

/// 解析数据文件（失败返回 discarded）
inline nlohmann::json parseGameDataJson() {
    return nlohmann::json::parse(readGameDataJson(), nullptr, false);
}

}  // namespace gamecore::testsupport
