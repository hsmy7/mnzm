// 数值外置数据存储实现（B16 / R6.2）——纯 C++，零平台依赖，桌面可直测。

#include "gamecore/data/data_store.h"

#include <nlohmann/json.hpp>

namespace gamecore::data {

namespace {
/// 解析后的顶层文档句柄——注入时由 apply 回调消费，注入后立即释放
/// （不长期持有，避免与各 DB 容器形成第二份真相源）。
}  // namespace

bool loadFromJson(const std::string& json,
                  const std::function<bool(const void*)>& apply) {
    auto& st = gameDataStoreState();

    // ── 注入纪律：仅初始化期一次 ───────────────────────────────
    // 稳态（已注入 / 已兜底）后再来一律拒绝，表容器地址保持不变。
    if (st.state != GameDataState::kUninitialized) {
        st.stats.attempts += 1;
        st.stats.rejectedAlreadyLoaded += 1;
        return false;
    }
    st.stats.attempts += 1;

    // ── 解析（显式失败，禁止静默空表）────────────────────────
    // nlohmann::json::parse(json, nullptr, false) 在失败时返回 discarded
    // 值而不抛异常——与仓库「无异常依赖」约束（CMakeLists 注释）一致。
    nlohmann::json doc = nlohmann::json::parse(json, nullptr, false);
    if (doc.is_discarded() || !doc.is_object()) {
        st.stats.failedParse += 1;
        st.state = GameDataState::kFallbackDefault;
        return false;
    }

    // schemaVersion 校验：不符即拒绝（数据文件与水印漂移时宁可兜底）
    if (!doc.contains("schemaVersion") || !doc["schemaVersion"].is_number_integer() ||
        doc["schemaVersion"].get<int32_t>() != kGameDataSchemaVersion) {
        st.stats.failedParse += 1;
        st.state = GameDataState::kFallbackDefault;
        return false;
    }
    st.schemaVersion = doc["schemaVersion"].get<int32_t>();

    if (!doc.contains("db") || !doc["db"].is_object()) {
        st.stats.failedParse += 1;
        st.state = GameDataState::kFallbackDefault;
        return false;
    }

    // ── 应用到各 DB 容器（由 data_inject.h 提供）──────────────
    if (!apply(static_cast<const void*>(&doc))) {
        st.stats.failedParse += 1;
        st.state = GameDataState::kFallbackDefault;
        return false;
    }

    st.state = GameDataState::kLoadedFromFile;
    st.stats.accepted += 1;
    return true;
}

bool loadFromJson(const std::string& json) {
    // 无 apply 的重载：仅推进状态机（守卫测试用），不触碰任何 DB 容器。
    return loadFromJson(json, [](const void*) { return true; });
}

void resetGameDataStoreForTest() {
    gameDataStoreState() = GameDataStoreState{};
}

}  // namespace gamecore::data
