# Findings & Decisions：C++ 迁移整改

<!-- 审计与总方案的要点沉淀。外部/网络内容只写这里，不写 task_plan.md。 -->

## Requirements
- 按 docs/cpp-migration-implementation-plan.md 执行迁移整改
- C++ 收敛为唯一模拟真相源（决策1选A）
- 游戏全面使用 ECS（决策3，方案X）
- iOS 暂缓，仅文案收敛 + 可移植性护栏（决策4）
- arm64-v8a 单架构 + 移除纹理分包开关（决策7）
- detekt baseline 只许缩小，逐批清偿（决策8）
- 每批下沉必须"先对拍再删Kotlin"

## 审计核心结论（来自报告）
- 定性：**真实但未完成的迁移**（总评 5.9/10）。时间/旬结算/RNG/战斗/宗门地图渲染已真迁 C++ 且 C++ 为真相源；但 Kotlin 保留每旬残留执行器、全部旧实现作回退、UI/持久化/地图数据模型层，且同步通道昂贵、ECS 为展品、iOS 为零。
- 架构评分：C++ Core 7.5 / Renderer 7.5 / Vulkan 8 / Android 7.5 / ECS 3（列式存储计 7）/ Map 6 / 跨平台 3 / iOS 0.5。

## 决定不放过的关键事实（证据链）
1. **ECS 伪完成**：`ecsWorld_` 全 src/ 仅出现一次；`PhaseCoreBatchSystem::run(ecs::World&)` 形参无名被忽略。生产真相是 DiscipleStore 列式存储（数据导向设计，非 ECS）。
2. **行序=RNG 确定性红线**（disciple_store.h:22-27）：稀疏集 dense 迭代序 ≠ DiscipleStore 行序。E1 保序验证绝对前置。
3. **同步通道 O(全状态)**：StateSyncService.kt:376-377 整段重发；dirty_tracker.cpp:63-64 两次全量序列化+深拷贝；GameEngineNativeOps.kt:81 每次库存操作全状态导出+全表替换。
4. **自动存档残留**（原P0-1勘误）：弹窗文案/autoSave 配置键/autoSaveIntervalMonths Room列（两张表）/flushDirtyState+consumeDirty 死代码/注释。旧档兼容 proto 字段139 保留解析-忽略。
5. **死导出群**：NativeBridge.isRendererReady（Kotlin声明C++无导出，调用即崩）/ nativePollEvents+nativeAdvance 桩。
6. **双 ABI**：armeabi-v7a + arm64-v8a；纹理 split 开关形同虚设（仅一种 KTX）。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 方案X（推荐）：DiscipleStore留存储后端，ECS做系统调度与实体关系层 | 保确定性对拍/脏追踪/存档基建；方案Y风险大一个量级 |
| 决策1选A：C++唯一模拟真相源 | 双真相源是过渡态不是终态 |
| 反向通道只能分域关 | 写入者未全部下沉前该域通道必须存活 |
| WS-4 需先补 NPC 玩法设计文档 | 数量/生成规则/与弟子系统关系未定 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| zcode 免费活动端点（zai-coding-plan/zai-start-plan）实测 429/401 | 当前 zcode 实际选中的是 `builtin:zai`（Z.ai - API Key），该通道真实可用（GLM-5.3-flash 返回 200，支持工具调用） |

## Resources
- docs/cpp-migration-audit-report.md — 独立审计（2026-09-04，275行）
- docs/cpp-migration-implementation-plan.md — 总方案（2026-09-04，183行）
- 关键源码：disciple_store.h:22-27、phase_settlement.h:1371-1383、StateSyncService.kt:376-377、dirty_tracker.cpp:63-64、GameEngineNativeOps.kt:81、SettingsTab.kt:356、game_config.json:6-7、NativeBridge.kt:215、GameCoreBridge.kt:237/65、build.gradle:60-62/163-167

## Visual/Browser Findings
- 无图像/浏览器取证。
