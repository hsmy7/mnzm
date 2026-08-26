# ADR: 游戏逻辑核心 Kotlin→C++ 迁移（cpp-engine-migration）

> 日期：2026-08-16。状态：已批准实施（批次 0-9 核心完成；计划 v2 阶段 0-3 已完成——阶段 3：反向增量通道 + DiscipleStore SoA + 静态数据单一源）。
> 修订：2026-08-25——性能基准（NativeBenchmarkTest）复核：旧"408×"为单次采样不可复现（421×~768× 波动）已弃用；
> 正确基准（真实实现对比）显示单次 JNI+JSON 往返约 7-12µs，wallet 类操作 native 转发 vs Kotlin 真实实现仅 1.1×（批量打平）——
> **转发成本可忽略，性能不再是高频留 Kotlin 的论据**；决策依据修正为工程性：
> 大量系统（SecretRealm/外交/邮件/兑换码等）依赖 Kotlin 状态链路无 C++ 对应动作，
> **Decision 7 "Kotlin 引擎退役"重定义为"职责边界固化"**（详见下）。

> 二次修订：2026-08-25（用户决策）——采纳**彻底单引擎（选项 A）**：C++ 单一真相源为最终态，
> Decision 7 由"职责边界固化（双端并行=最终态）"改回"**Kotlin 引擎退役（C++ 引擎=最终态）**"。
> 依据（阶段 0 实测，见 docs/cpp-engine.md 第 6 节）：C++ 批量通道（标量参数 advancePhases）单次往返仅
> **0.1µs**（12µs 的 JSON 协议往返成本大头是 JSON 编解码，高频批量必须走标量/二进制协议）；
> Kotlin 每旬核心路径 1000 弟子 ≈**327µs**、5000 弟子 ≈1189µs；批量下沉传输占比 **<0.1%**——
> 整批下沉无传输障碍，双实现并行的镜像/对拍开销是纯浪费，与性能最大化目标冲突。
> 执行路径见 docs/cpp-engine.md 第 7 节（阶段 1-7）。

## Context

游戏为 Android 修仙放置游戏，代码 28 万行 Kotlin（1436 文件），仅地图渲染为 C++（Vulkan）。用户需求：**游戏逻辑核心转 C++，界面保持现状**，目的为**性能提升 + 为 iOS/多平台做准备**。

关键约束与事实：
- 引擎逻辑（core/engine 261 文件 / 6.9 万行）依赖 Kotlin 生态：协程（442 个 suspend）、StateFlow、sealed class、ReentrantLock 事务、Room 存档（kotlinx ProtoBuf、2174 个 @ProtoNumber）
- 确定性 RNG（PCG-XSH-RR 64→32）+ 存档确定性（rngStates）是读档一致性的根基
- 存档三轨：Room 34 表 + .sav 备份 + 云存档（SaveData kotlinx-proto + LZ4 + 自定义头）；saveVersion=2
- 既有 C++ 渲染层 JNI 桥模式（NativeBridge：静态全局 + extern C）可复用
- 项目已有 codegen 先例（build-atlas.mjs → Kotlin + C++ 双产物）

## Decision

1. **C++ GameCore 为确定性计算真相源**；Kotlin GameStateStore 保持单一真相源接口（StateFlow 不变，镜像/派生两态并存）
2. **Kotlin 转发层**：GameEngine 族（~275 方法）+ 10 Facade（117 方法）保留签名，**低频业务操作方法体改转发**；GameEngineCore（帧循环/生命周期/看门狗）保留 Kotlin
3. **通用 JNI 入口**（init/advance/execute/export/import/poll 5-8 个）+ ActionId 协议（codegen 生成双产物）
4. **参数/结果编码 = JSON**（kotlinx ↔ nlohmann/json）：方法调用低频，零代码生成
5. **镜像 JSON 快照**：C++ 全量导出 → Kotlin 镜像 → 现有存档链路**零改动**（存档兼容 100% 由 Kotlin 层保证；不做 C++ 直出 kotlinx-proto——2174 字段号工作量无当前消费者，登记技术债）
6. **RNG 精确复刻**（PCG-XSH-RR、8 分区 id 对齐、stable_sort shuffle）；现实时间 **Clock 注入**
7. **双实现并行 + 差分对拍 + feature flag 切换**：任何时刻可回退。~~Kotlin 引擎退役~~ **修订为职责边界固化**（2026-08-25）：
   - C++ 接管：时间推进/结算引擎（确定性核心）、低频业务操作转发（玩家行为入口）、静态数据单一源
   - Kotlin 保留：高频纯计算（复核后论据修正为工程性——正确基准显示 native 转发 vs Kotlin 真实实现仅 1.1×/批量打平，性能不构成障碍；保留理由是 C++ 侧未实现对应系统逻辑（SecretRealm/外交/邮件等）+ 批量通道未验证 + 迁移工程量）、UI 链路、未迁移系统（SecretRealm/外交/邮件/兑换码等）、存档编码（kotlinx-proto 链路零改动）
   - 不做 Kotlin 引擎全量删除——职责边界冻结后双端并行成为**最终架构**而非过渡态
8. **game-core 纯 C++ 静态库**（零 Android 依赖，桌面可编译 GTest，iOS 可复用）；JNI 桥独立薄层

## Consequences

正面：
- 界面/存档/外围零改动；存档格式零变更无 Migration
- 性能：**确定性结算下沉原生**（时间推进/低频业务）；正确基准（真实实现对比）显示 JNI 转发成本可忽略（wallet 类 1.1×，批量打平），低频转发与批量下沉无性能障碍（旧"408×"已弃用）
- iOS：game-core 直接复用；平台能力（Clock/Logger）已注入抽象
- 双实现并行成为最终架构，无一次性迁移风险

负面/成本：
- C++ 引擎已交付 ~7 万行 + 测试（GTest 289 + JUnit 对拍）；双端共存为长期态
- 静态数据双份（Kotlin Registry + C++ 表，双端守卫防漂移；T-CPP-2 决定单一源时机）
- 未迁移系统保持 Kotlin 实现（职责边界显式登记）
- JNI/JSON 传输有少量开销（低频方法调用可接受，高频已排除）

## 技术债（偿还触发）

| 债项 | 偿还触发 |
|---|---|
| C++ 未实现 kotlinx-proto 编解码（存档经 Kotlin 镜像） | iOS 立项需纯 C++ 存档时 |
| 静态数据双份 | 职责边界固化后或 iOS 立项需单一数据源时（T-CPP-2） |
| 桌面 JNI .so 仅测试用途 | 对拍框架退役时删除 |

## 参考

- [Android JNI tips](https://developer.android.com/training/articles/perf-jni)
- [Cocos2d-x 跨平台架构](https://developer.baidu.com/article/detail.html?id=6340038)
- [Outfit7 Starlite 自研引擎](https://outfit7.com/blog/tech/building-the-ultimate-mobile-game-engine-starlite)
- [Gaffer On Games: Floating Point Determinism](https://www.gafferongames.com/post/floating_point_determinism/)
- [Riot: League Determinism](https://www.riotgames.com/en/news/determinism-league-legends-implementation)
- 项目既有确定性调研：docs/adr/exploration-system-refactoring.md（Photon Quantum / Knockout City / Factorio / bevy_rand）
