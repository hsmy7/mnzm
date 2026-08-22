# ADR: 游戏逻辑核心 Kotlin→C++ 迁移（cpp-engine-migration）

> 日期：2026-08-16。状态：已批准实施（批次 0 进行中）。

## Context

游戏为 Android 修仙放置游戏，代码 28 万行 Kotlin（1436 文件），仅地图渲染为 C++（Vulkan）。用户需求：**游戏逻辑核心转 C++，界面保持现状**，目的为**性能提升 + 为 iOS/多平台做准备**。

关键约束与事实：
- 引擎逻辑（core/engine 261 文件 / 6.9 万行）依赖 Kotlin 生态：协程（442 个 suspend）、StateFlow、sealed class、ReentrantLock 事务、Room 存档（kotlinx ProtoBuf、2174 个 @ProtoNumber）
- 确定性 RNG（PCG-XSH-RR 64→32）+ 存档确定性（rngStates）是读档一致性的根基
- 存档三轨：Room 34 表 + .sav 备份 + 云存档（SaveData kotlinx-proto + LZ4 + 自定义头）；saveVersion=2
- 既有 C++ 渲染层 JNI 桥模式（NativeBridge：静态全局 + extern C）可复用
- 项目已有 codegen 先例（build-atlas.mjs → Kotlin + C++ 双产物）

## Decision

1. **C++ GameCore 为状态真相源**；Kotlin GameStateStore 降级为镜像（接口/StateFlow 不变）
2. **Kotlin 转发层**：GameEngine 族（~275 方法）+ 10 Facade（117 方法）保留签名，方法体改转发；GameEngineCore（帧循环/生命周期/看门狗）保留 Kotlin
3. **通用 JNI 入口**（init/advance/execute/export/import/poll 5-8 个）+ ActionId 协议（codegen 生成双产物）
4. **参数/结果编码 = JSON**（kotlinx ↔ nlohmann/json）：方法调用低频，零代码生成
5. **镜像 JSON 快照**：C++ 全量导出 → Kotlin 镜像 → 现有存档链路**零改动**（存档兼容 100% 由 Kotlin 层保证；不做 C++ 直出 kotlinx-proto——2174 字段号工作量无当前消费者，登记技术债）
6. **RNG 精确复刻**（PCG-XSH-RR、8 分区 id 对齐、stable_sort shuffle）；现实时间 **Clock 注入**
7. **双实现并行 + 差分对拍 + feature flag 切换 + Kotlin 引擎退役**：任何时刻可回退
8. **game-core 纯 C++ 静态库**（零 Android 依赖，桌面可编译 GTest，iOS 可复用）；JNI 桥独立薄层

## Consequences

正面：
- 界面/存档/外围零改动；存档格式零变更无 Migration
- 性能：计算下沉原生；确定性由差分对拍守护
- iOS：game-core 直接复用；平台能力（Clock/Logger）已注入抽象

负面/成本：
- C++ 引擎 8-10 万行 + 测试 3-4 万行，10 批交付
- 迁移期 Kotlin/C++ 双实现共存（静态数据双份，对拍守卫防漂移）
- 静态数据/注册表需要 C++ 侧等价物（codegen 优先）
- JNI/JSON 传输有少量开销（方法低频可接受）

## 技术债（偿还触发）

| 债项 | 偿还触发 |
|---|---|
| C++ 未实现 kotlinx-proto 编解码（存档经 Kotlin 镜像） | iOS 立项需纯 C++ 存档时 |
| 静态数据双份 | 批次 10 退役后统一单一源 |
| 桌面 JNI .so 仅测试用途 | 对拍框架退役时删除 |

## 参考

- [Android JNI tips](https://developer.android.com/training/articles/perf-jni)
- [Cocos2d-x 跨平台架构](https://developer.baidu.com/article/detail.html?id=6340038)
- [Outfit7 Starlite 自研引擎](https://outfit7.com/blog/tech/building-the-ultimate-mobile-game-engine-starlite)
- [Gaffer On Games: Floating Point Determinism](https://www.gafferongames.com/post/floating_point_determinism/)
- [Riot: League Determinism](https://www.riotgames.com/en/news/determinism-league-legends-implementation)
- 项目既有确定性调研：docs/adr/exploration-system-refactoring.md（Photon Quantum / Knockout City / Factorio / bevy_rand）
