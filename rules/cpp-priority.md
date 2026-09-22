# 规则：C++ 优先（引擎核心语言方向）

**生效日期**：2026-08-22（用户指令"项目整体使用 C++"）。本文件是 AGENTS.md 用户公约第 16 条的落地细则。
**总方案**：`docs/adr/cpp-engine-migration.md`（已批准）；**迁移进度**：`docs/cpp-engine.md`（批次 0-3 已完成，批次 4-10 待做）。

---

## 1. 规则定义

项目整体技术方向为 **C++**：

- **新增/修改/删除的引擎核心逻辑代码，一律优先 C++ 实现**（经 JNI 与 Kotlin 对接）
- **涉及引擎逻辑的设计方案必须包含 C++ 实现方案**，禁止只出 Kotlin 方案
- **UI 层（Compose）只能用 Kotlin，保持不变**——这是 Compose 框架的客观技术限制，不属于规则例外
- 现有 Kotlin 引擎逻辑按 `docs/cpp-engine.md` 迁移批次持续下沉 C++，Kotlin 侧保留签名降级为转发/镜像

## 2. 语言选择判定表

| 代码类型 | 语言 | 说明 |
|---|---|---|
| 引擎核心逻辑（时间/结算/战斗/生产/探索/内政/弟子属性生成/经济/RNG 等） | **C++** | 落在 `android/app/src/main/cpp/gamecore/`，纯 C++20、零 Android 依赖 |
| JNI 桥（`GameCoreBridge`/`GameCoreJni`） | C++（Android/桌面薄层） | 只做参数/结果转换，不承载业务逻辑 |
| ViewModel / UseCase / Facade 转发层 | Kotlin | 保留签名，方法体转发 JNI |
| UI / Compose / 导航 / 对话框 | Kotlin | Compose 框架限制 |
| 存档链路（Room / kotlinx-proto / .sav / 云存档） | Kotlin | 镜像方案保证存档格式零变更 |
| SDK 集成（广告/统计/防沉迷/登录等） | Kotlin | Android 平台外围 |
| 静态数据/注册表 | C++ 表（codegen 生成） | 走 `scripts/gen-templates.mjs`，禁止手写 C++ 表与 Kotlin Registry 双份漂移 |

**边界规则**：

1. **新逻辑先落 C++**：新增算法/系统逻辑直接在 game-core 对应子系统实现（含 GTest + 跨语言对拍），**禁止先写纯 Kotlin 实现再等迁移**（避免双重工作与双份行为漂移）
2. **确因紧急无法立即 C++ 化的**：必须在 `docs/cpp-engine.md` 登记待下沉项并说明原因，后续批次补齐，禁止"就此遗忘"
3. **纯 UI/外围改动**（不影响引擎逻辑）不受本规则约束，但方案中须说明不涉及引擎逻辑
4. 修改既有 Kotlin 引擎逻辑时：若该逻辑已被 C++ 覆盖，改 C++ 侧并跑对拍；若未覆盖，按边界规则 1 优先下沉到 C++ 而非在 Kotlin 侧打补丁

## 3. 技术约束（迁移全程有效，来自 ADR 与 docs/cpp-engine.md 第 4 节）

1. **C++20**；game-core 静态库**零 Android 依赖**（桌面可编译、CI 独立跑、iOS 直接复用）；JNI 桥是唯一依赖 Android 的薄层
2. **确定性保真**：
   - RNG 用 PCG-XSH-RR 复刻，8 分区 id 与 Kotlin `RngPartition` 对齐
   - `shuffled(rng)` 必须用 `std::stable_sort`（Kotlin sortedBy 为稳定排序）
   - 禁止 `unordered_map` 参与业务迭代（用 `std::map`/vector + 显式排序）
   - IEEE754 double、Int/Long 溢出回绕、截断除法、coerceIn/coerceAtMost 语义与 Kotlin 对齐
3. **现实时间一律 `Clock` 注入**，引擎内禁止直接取系统时间（对拍用 FixedClock）
4. **编解码**：参数/结果用 JSON（kotlinx ↔ nlohmann/json），字段名与 kotlinx 一致；`ActionId` 走 `scripts/gen-action-ids.mjs` codegen 双产物（提交 git 防漂移）
5. **测试双守护**：C++ GTest（黄金序列）+ JUnit 跨语言对拍（`DiffRngTest`/`DiffStateTest`/`DiffTimeTest` 模式），任何 C++ 业务实现必须两者齐备

## 4. 方案设计要求

涉及引擎逻辑的设计方案（按 rules/design-plan-review.md编写）必须额外包含：

- **C++ 侧实现方案**：落在 gamecore/ 哪个子系统目录、关键类/接口、与 Kotlin 侧如何对拍
- **影响范围清单标注语言**：每项变更标注 `C++`/`Kotlin` 及所属模块，遗漏标注视为方案不完整
- **兼容性**：存档格式不变（镜像方案）；JSON 快照字段名与 kotlinx 一致；新增状态字段需同步 `state/models.h` 与 Kotlin 快照 DTO

## 5. 审查清单（🔴 新增/修改引擎相关代码时逐项核对）

- [ ] 新增引擎核心逻辑是否为 C++（gamecore/），而非纯 Kotlin 新写或 Kotlin 侧打补丁
- [ ] JNI 桥是否只做转换、不承载业务逻辑
- [ ] game-core 是否零 Android 依赖（不含 `android/log.h` 等 Android 头）
- [ ] 是否有 GTest + 跨语言对拍双守护（或说明为何无需对拍）
- [ ] 是否违反确定性禁止项（`unordered_map` 业务迭代 / 引擎内直接系统时间 / 非稳定排序）
- [ ] 静态数据是否走 codegen（`gen-templates.mjs`）而非手写 C++ 表
- [ ] 新增 `ActionId` 是否已更新 `scripts/gen-action-ids.mjs` 并重新生成双产物

## 6. 行业依据

跨平台 C++ 引擎是手游行业成熟做法（引擎核心 C++、UI 层各平台原生），主要依据见 `docs/adr/cpp-engine-migration.md` 参考清单：

- [Android JNI tips](https://developer.android.com/training/articles/perf-jni)（Google 官方）
- [Cocos2d-x 跨平台架构](https://developer.baidu.com/article/detail.html?id=6340038)
- [Outfit7 Starlite 自研引擎](https://outfit7.com/blog/tech/building-the-ultimate-mobile-game-engine-starlite)
- [Gaffer On Games: Floating Point Determinism](https://www.gafferongames.com/post/floating_point_determinism/)
- [Riot: Determinism in League](https://www.riotgames.com/en/news/determinism-league-legends-implementation)
- 项目既有确定性调研：`docs/adr/exploration-system-refactoring.md`（Photon Quantum / Knockout City / Factorio / bevy_rand）
