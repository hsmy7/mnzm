# Task Plan: C++ 迁移整改执行（M0-M3）

<!--
  依据：docs/cpp-migration-audit-report.md（独立审计）+ docs/cpp-migration-implementation-plan.md（总方案，2026-09-04）
  性质：长期整改任务，跨多周。本文件是磁盘工作记忆，随阶段更新。
-->

## Goal

按总方案四里程碑（M0止血清残 → M1减税+试点 → M2主轴成型 → M3收敛）完成 Kotlin→C++ 引擎迁移整改：C++ 收敛为唯一模拟真相源、游戏全面使用 ECS（NPC 移动为首个真实负载）、逐批下沉残留执行器、清空空存档残留、清偿 detekt 债务、删双 ABI/无效分包。

## Current Phase

M0 止血清残（开始执行）

## 里程碑地图

| 里程碑 | 时间 | 内容 | 验收标准 |
|---|---|---|---|
| M0 止血清残 | 第1-2周 | WS-0 全部 + WS-6 | 死导出清零；自动存档残留全项清理；对拍CI强制；图集不碰主线程；arm64单架构；文案与实现一致；baseline守卫上线 |
| **M0 现状** | —— | WS-0.a全 + WS-0.b死导出/注释 + WS-0.c + WS-0.d(P1-7/CI③) | 死导出清零✅；自动存档残留全项✅；arm64单架构✅；文案✅；baseline守卫✅；**待：图集出主线程(P0-3) + P1-4 + CI①②** |
| M1 减税+试点 | 第3-6周 | WS-1 + WS-2(S1-S3) + WS-3(E1) | 库存全量导出消失；反向通道gameData段-90%；突破/丹药/自动装备下沉且对拍全绿；E1保序验证通过；detekt≥1个baseline文件清零 |
| M2 主轴成型 | 第7-14周 | WS-2(S4-S8) + WS-3(E2-E3) + WS-4 + WS-5 | 月结残留扇出≤3项；NPC在地图行走+可交互；占位真相源在C++；月结O(N²)修复 |
| M3 收敛 | 第15周起 | WS-2收尾 + WS-7收尾 | 反向同步通道按域全关；detekt baseline清零；全仓无双实现并行活路径；死代码清单清零 |

## Phases

### Phase 0: 资料理解与规划文件建立
- [x] 阅读审计报告 + 总方案（2026-09-04）
- [x] 建立 task_plan/findings/progress 三文件
- **Status:** complete

### Phase 1: 执行路线规划交付
- [ ] 将 M0-M3 + WS-0~7 + S1-S8 拆解为可执行、可中断、可回滚的批次
- [ ] 明确每批验收标准与"先对拍再删Kotlin"红线
- **Status:** in_progress

### Phase 2: M0 止血清残（WS-0 全部）
- [x] WS-0.a 安全项：退出文案 / 删 autoSave 配置键 / 注释改"保留"（已落地+编译过）
- [x] WS-0.a 高危项：autoSaveIntervalMonths 两表 Room 删除（V50 迁移，PRAGMA 动态重建）+ proto 旧档兼容（@Ignore+@Transient；已生成 50.json）
- [x] WS-0.a 死代码链：GameStateStoreImpl markDirty/consumeDirty + _stateDirty/_discipleDirty 整链删除（已核实零读者；保留每个 _updateVersion.value++ 活逻辑；编译+状态/仓库测试通过）
- [x] WS-0.a 防复发护栏：docs/architecture.md + CODE_WIKI.md 加"纯手动存档"权威记录
- [x] WS-0.c 包体两项：arm64-v8a 单架构 + 移除纹理分包开关（已落地+编译过）
- [x] WS-0.b 死导出：isRendererReady / nativeAdvance / nativePollEvents+pollEventsJson（已删 Kotlin+C++双侧+测试断言，externalNativeBuildRelease+compileReleaseKotlin 通过）
- [x] WS-0.b 注释纠偏：GameEngineNativeOps / NativeSurfaceView / gamecore-CMakeLists / game_core.h / SaveLoadSaveDelegate / GameEngineAdminOps（已改）
- [ ] WS-0.b TimeSystem.onPhaseTick（保留——被 6 个 Diff 测试用作 Kotlin 跨语言对拍基准；删它破坏时间验证基线，需用户拍板）
- [x] WS-0.d P1-7：release `debugSymbolLevel "SYMBOL_TABLE"`（build.gradle）
- [x] WS-0.d CI ③：detekt baseline 只缩不增守卫（detekt-baseline-count.guard + ci.yml 对比步骤，本地逻辑已验证 PASS）
- [ ] WS-0.d P0-3：图集拼装移出主线程 + RGBA 2048 封顶 + toRgbaByteArray DirectByteBuffer（复杂渲染线程重构，涉及 renderer 三线程契约/生命周期，需专项隔离验证）
- [ ] WS-0.d P0-3(VulkanBackend 清屏纯黑) / P1-4(JNI debug 线程断言) / CI ①②
- **Status:** M0 主体完成，WS-0.d 剩余大项待专项

**已验证（2026-09-04）**：
- `:core:data:testReleaseUnitTest --tests RoomMigrationTest --max-workers=1` → 62/62 全绿
- `assembleRelease` → BUILD SUCCESSFUL（含 native 编译/R8/lint lintVitalReportRelease 无错误/打包）

### Phase 3: M1 减税+试点（WS-1 + WS-2 S1-S3 + WS-3 E1）
- [ ] WS-1 同步通道降本（字段级回读/dirty集/写屏障/UI读取面清单）
- [ ] WS-2 S1 突破下沉 + S2 丹药下沉 + S3 自动装备下沉（对拍全绿后删Kotlin）
- [ ] WS-3 E1 实体模型与保序验证（PhaseCoreBatchSystem 真用 World/View）
- **Status:** pending

### Phase 4: M2 主轴成型（WS-2 S4-S8 + WS-3 E2-E3 + WS-4 + WS-5）
- [ ] WS-2 S4-S8 月结残留+秘境+生产+AI宗门逐批下沉
- [ ] WS-3 E2/E3 逐系统View迁移 + NPC实体组件族
- [ ] WS-4 NPC移动系统
- [ ] WS-5 地图数据模型改造
- **Status:** pending

### Phase 5: M3 收敛（WS-2 收尾 + WS-7 收尾）
- [ ] 反向同步通道按域全关
- [ ] detekt baseline 清零
- [ ] 死代码滚动清单清零
- **Status:** pending

## Key Questions
1. ECS 迭代序保序验证（dense迭代序 ≠ DiscipleStore 行序）——E1 绝对前置
2. NPC 玩法设计文档（数量上限/生成规则/与弟子系统关系）——WS-4 实现前需用户补充
3. Room 迁移旧档升级路径测试覆盖
4. 自动存档 markDirty 链是否存在隐藏读者（决定整链删除 or 保留）

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 决策1 选A：C++收敛为唯一模拟真相源 | 终态每旬只走 nativeSettlePhase + 前向diff；反向同步通道终态删除（先通道降本再逐批下沉） |
| 决策3：游戏全面使用ECS（方案X：DiscipleStore留存储后端，ECS做系统调度与实体关系层） | 更纯粹的方案Y破坏确定性对拍/脏追踪/存档三套基建，风险大一个量级 |
| 决策4：iOS暂缓 | 仅做"Android为主，核心可移植"文案收敛 + gamecore零Android依赖CI护栏 |
| 决策7：arm64-v8a 单架构 + 移除纹理分包开关 | APK native 约-40%；纹理分包从未生效 |
| 决策8：detekt baseline 只许缩小 3075 行逐批清偿 | 债务必须实际解决，不得压制 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       | 1       |            |

## Notes
- 每批下沉固定流水线：C++实现/启用 → ActionId接线 → 桌面对拍验证 → 删Kotlin路径与回退 → CI绿。
- 禁止在 Kotlin 路径删除前对拍未全绿时下手。
- 无测试 / 无 Migrateion 的改动视为未完成（见 CLAUDE.md 13.3）。
- 所有 commit 引用中文信息（CLAUDE.md 约定），任务完成后一次性提交。
