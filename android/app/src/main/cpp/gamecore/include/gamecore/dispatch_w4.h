#pragma once

/**
 * dispatch_w4.h — W4 三批次并行的 ActionId 分派端口（W4-00 并行前置批建立）。
 *
 * ## 存在理由
 * `GameCore::execute` 的分发表原本是一段**连续的 `else if` 区间链**——三个并行批次
 * 若各自往链尾追加分支，会全部锚定同一插入点，产生**必然的文本冲突**。
 * 因此 W4-00 把分派面切成三个独立端口：三个并行批次各写各的 `dispatch_w4X.cpp`，
 * `execute_dispatch.cpp` 只被本前置批触碰一次，此后冻结。
 *
 * 详见 `docs/parallel-batches-w4/README.md` §3.1 项 3 与 §5.3 冻结清单。
 *
 * ## 契约
 * - 入参：`actionId` 与已解析的 `params`（`GameCore::execute` 已完成 JSON 解析与
 *   null→空对象归一化，与既有 `handleXxxTx` 一致）
 * - 返回：
 *   - `std::nullopt` — **本批不认领该 actionId**，交由下一个端口或最终
 *     `NOT_IMPLEMENTED` 兜底；
 *   - 有值 — 本批认领并已产出**完整结果信封**（`{"status":...}` JSON），
 *     `GameCore::execute` 直接原样返回给调用方。
 * - 🔴 端口函数**不得**抛异常（与既有 handler 同契约：内部以失败信封表达业务失败）；
 *   `GameCore::execute` 仍有 `std::exception` 终局兜底，但端口应自洽。
 * - 🔴 端口只处理**自己批次预分配段内**的 actionId（段号见
 *   `docs/parallel-batches-w4/README.md` §6）；越段认领会被
 *   `test/dispatch_guard_test.cpp` 与 `test/w4X_tests.cmake` 内的批次用例发现。
 */

#include <cstdint>
#include <optional>

#include <nlohmann/json.hpp>

namespace gamecore {

class GameCore;

/**
 * W4-A 分派端口（弟子与建设轴）。
 *
 * @param core 引擎实例（端口内可读写权威状态，与既有 handler 同权限）
 * @param actionId 业务操作码
 * @param params 已解析的参数对象（非 null）
 * @return 认领并产出的结果信封；不认领返回 `std::nullopt`
 */
std::optional<nlohmann::json> dispatchW4A(GameCore& core, int32_t actionId,
                                         const nlohmann::json& params);

/**
 * W4-B 分派端口（内政与经济运营轴）。
 *
 * @param core 引擎实例
 * @param actionId 业务操作码
 * @param params 已解析的参数对象（非 null）
 * @return 认领并产出的结果信封；不认领返回 `std::nullopt`
 */
std::optional<nlohmann::json> dispatchW4B(GameCore& core, int32_t actionId,
                                         const nlohmann::json& params);

/**
 * W4-C 分派端口（战斗与世界协议轴）。
 *
 * @param core 引擎实例
 * @param actionId 业务操作码
 * @param params 已解析的参数对象（非 null）
 * @return 认领并产出的结果信封；不认领返回 `std::nullopt`
 */
std::optional<nlohmann::json> dispatchW4C(GameCore& core, int32_t actionId,
                                         const nlohmann::json& params);

}  // namespace gamecore
