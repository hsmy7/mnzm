# AGENTS.md — C++ 引擎核心（game-core）

> 本文件是 `android/app/src/main/cpp/gamecore/` 的追加规范。
> **通用规范见仓库根 `AGENTS.md`，引擎方向规则见 `rules/cpp-priority.md`。**

## 硬约束

- **C++20**，**零 Android 依赖**（桌面可直接编译，核心保持可移植）—— 这是 iOS 迁移与桌面对拍的前提
- 新增引擎逻辑**先落 C++**，禁止先写纯 Kotlin 等「以后再迁移」；确因紧急未 C++ 化的，必须在 `docs/cpp-engine.md` 登记待下沉
- JNI 桥只做薄层转换（类型/编码），**不承载业务逻辑**

## 确定性保真（迁移全程的硬要求）

跨语言逐位一致是验收标准：

- RNG 用 **PCG-XSH-RR 复刻**（与 Kotlin 侧同源）
- `shuffled` 语义必须用 `std::stable_sort` 复刻
- **禁止 `unordered_map` 参与业务迭代** —— 迭代顺序不确定会破坏确定性
- IEEE754 / 溢出 / 截断 / coerce 语义必须与 Kotlin 对齐
- 现实时间必须经 `Clock` 注入，禁止直接读挂钟
- 编解码 JSON 字段名与 kotlinx.serialization 一致

## ECS 与调度

数据导向：ECS 骨架 + System 调度 + JobSystem 并行化。
新增 System 前先读 `docs/adr/ecs-foundation-design.md`（含「避免假 ECS」检查清单）；
组件化接入方式与目录落点见 `docs/cpp-engine.md` §1/§2。

## 测试双守护

C++ 与 Kotlin 的逻辑对拍必须**两侧齐备**，缺一即任务未完成：

- **GTest**（黄金序列）—— CI 的 `cpp-engine-test` job
- **JUnit 跨语言对拍**（`Diff*Test` 模式）—— CI 的 `cpp-diff-jni-test` job

新增 / 修改 JNI 端口、`ActionId`、静态数据表时两套都要覆盖。
`ActionId` 走 `scripts/gen-action-ids.mjs` codegen 双产物，**禁止手改生成物**。
`external fun` 只允许出现在在册的两个桥文件（`GameCoreBridge.kt` / `NativeBridge.kt`），
计数门禁见 `scripts/check-jni-count.mjs`。

## 审查清单

引擎相关变更逐项过 `rules/cpp-priority.md` 第 5 节（语言归属 / 零 Android 依赖 / 确定性 / 对拍双守护）。
