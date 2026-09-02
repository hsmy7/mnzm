# C++ 游戏引擎（game-core）架构文档

> 更新日期：2026-09-01。Kotlin→C++ 迁移——已完成批次归档，本文档仅保留**未完成项**详细规划（迁移主线批次的；**引擎整体现状 + 后续工作计划见 §0**）。
> 总方案见 `docs/adr/cpp-engine-migration.md`。
>
> **📌 快速恢复点（2026-09-02，上下文压缩/新会话从此继续）**：
> - **基线**：GTest **722/722** · engine JUnit 全量（桌面 JNI 对拍 0 skip）· NDK externalNativeBuildRelease · engine detekt 全绿 · app compileReleaseKotlin 通过——版本 4.01.12 已发布（commit 11961daa，2026-09-02，CHANGELOG 与游戏内 changelog 均含批 Y 全量条目）
> - **已完成批次**（迁移主线批次全部达成——**注意：非"引擎整体目标"完成**，引擎级现况与后续计划见 **§0**）：批 M-1 月变真相源切换 → 批 Y-1/Y-2/Y-switch/Y-3（T1 11/11）→ 批 Y-4a/4b/4c（T2 11/11，年变残留执行器扇出清零）→ **收尾批（2026-09-02）**：S-07 清偿（DomainLog.setLogger 返回旧值 + 基准测试保存-恢复 + DomainLogTest）；S-21 清偿（孤儿入口 GameEngineCore.loadSnapshot/createSnapshot + GameEngine.loadFromSave→SaveFacade/SaveService 链删除；getEffectiveCultivation 勘误保留——源码调用点在活代码 CultivationService，批 9-2 后仅 Kotlin 运行时无执行驱动，为 C++ 修炼对拍基准）；年变对拍外交簇换装真实（DiplomacyService/DiplomacyEventProcessor/FavorEventProcessor/VassalService）+ 新增外交对拍场景（T2-④ 交易刷新首次整链对拍 + T2-⑥⑦⑨ 联盟/好感）——途中实锤并修复 C++ 两处与 Kotlin 不等价：① manual 交易商品 RNG 消费序缺口（ManualDatabase.generateRandom 的 generateRarity 阶梯无条件消耗 1×nextDouble（min==max 无短路），C++ pickTradeTemplate 无此消耗致 RNG 流错位选中不同功法）；② 交易/收购 pill 池序（Kotlin allPills.values 模板序（grade 外层×丹名内层）vs C++ pillRecipes 配方序（丹名外层×grade 内层）——新增 `pillRecipesInTemplateOrder()` 模板序视图，交易/收购池消费之）
> - **剩余工作**：~~无~~ （**2026-09-09 审计修正**：迁移主线（确定性逻辑核心）+ S 系列登记项确已收口（S-07/S-21/S-23/S-24 已清偿，见 §8）；但"自研跨平台游戏引擎"仍有明确缺口与后续工作——**ECS 基础、渲染路径修正、iOS 立项**为后续主线，权威现况与计划见 **§0**。本条"无剩余工作"仅就迁移主线而言，不适用于引擎整体目标）
> - **恢复指引**：无未下沉年变编排扇出（残留执行器仅剩 T1-③ 死亡链平台效应，设计边界）；S-23（SaveLoadCoordinator 孤儿类）/ S-24（realtimeCultivation 投影链，UI 修为显示已确认走 discipleAggregates 镜像）已随收尾批二清偿；每批 = Kotlin 源码审计 → C++ 移植（year_settlement.h detail）→ GTest 黄金序列 → 桌面对拍桥（scripts/build-desktop-jni.ps1）→ engine JUnit 强制重跑（--rerun-tasks）→ NDK/detekt；工作区另有 feature/game 4 项**预存非本任务改动**（建造栏石板路置灰）未提交，勿混入迁移提交

> 当前基线：**桌面 GTest 659/659（本机桌面工具链实跑；批 10-0 起 GTest 纳入本地验证门，CMake gtest_discover 需 llvm-mingw bin 在 PATH；批 12-1/12-2 新增 6 用例、批 13-1 新增 3 用例、批 13-2a 新增 3 用例、批 13-2b 新增 3 用例、批 13-3 新增 3 用例、批 13-4b 新增 5 用例、批 13-4c 新增 4 用例、战斗批次 A 新增 7 用例、战斗批次 B 新增 15 用例、战斗批次 C 新增 7 用例） · engine JUnit 2979/2979（testReleaseUnitTest 全量 + 桌面 JNI 对拍全执行 0 skip——本机已具备桌面工具链，`-Dgamecore.jni.path` 注入后原 194 个 Assume 跳过用例全部实跑） · app compileReleaseKotlin 通过 · detekt 全模块全绿（含首次纳入验证门的 `:feature:game:detekt`） · NDK externalNativeBuildRelease 通过**。
> **计划 v2 阶段 0~7 已完成**（阶段 2：批量结算下沉 + tick 真相源切换 AUTHORITATIVE
> 过渡管线；阶段 3：反向增量通道 + DiscipleStore SoA 实体存储 + 静态数据单一源；阶段 4：
> 未迁移系统逐批 C++ 化——LevelGenerator/死亡物化/SecretRealm 状态机核心/外交决策/
> 11 槽分配/兑换码+邮件附件；阶段 5：游戏循环入 C++——平台能力接口化（Clock/Telemetry/
> 热控/电量端口）+ 引擎循环（PhaseClock/EngineLoop）+ 看门狗判据（ProgressMonitor）；
> 阶段 6：渲染 RHI + 合成器统一——道路合成器单一权威物理下沉（批次 R 剩余清零）+
> Renderer2D → Rhi.h 形式化（Metal/iOS 预留）；阶段 7：AUTHORITATIVE 生产默认切换
> （C++ 真相源验收）+ 存档编码决策（T-CPP-1 保持 Kotlin）+ engine 平台能力接口化
> 收尾（Android import 36→11），详见 §7 阶段 7 行；**Kotlin 引擎逻辑全量退役随 C-06
> 续作（阶段 7 批 7-4 登记）**）。**计划 v2 批 8（C-06 续作）**：批 8-1 完成
> ThermalMonitor/FrameMetricsMonitor 平台能力接口化（engine `import android.*` 11→0）；
> 批 8-2 完成首个生产接线家族（库存 add/remove 7 动作 AUTHORITATIVE 路由 + 溢出邮件
> 草稿回传通道 + 行为审计登记）；批 8-3 完成库存家族收尾（consolidate/sort/toggleLock
> 新增 3 ActionId C++ 化 + 接线，该家族 10 动作全量接线）；**批 8-4 完成接线面收口判定**
> （87 动作全量清点六类裁决 + 钱包族行为审计——可接线面已穷尽，见 §7.1 批 8-4 行）；
> **退役专项批 9-1/9-2 完成**（SHADOW 对拍态 + 纯 Kotlin 旬结算路径删除——tick 结算
> 恒走 native 单引擎终态；对拍框架转长期回归基线，见 §7.2）；**月变残留执行器增量
> C++ 化批 10-1 完成**（S8 侦察过期清理下沉 + 宗门详情域协议扩容，见 §7.3）；**批 10-2 完成**（S8 月度叛逃检测下沉 + 执法堂配置/职务加成辅助入 C++）；**批 10-3 完成**（S8 月度偷盗兜底全链下沉 + lastTheftJudgementYears 纯内存列 + stats::baseStats，S-14 登记，见 §7.3）；**批 10-4 完成**（S8 附庸脱离检查下沉 + aiSectDisciples 协议扩容——GameState 顶层承载 @Transient 重型数据，S-15 登记，见 §7.3）；**批 10-5 完成**（S-15 清偿——aiSectDisciples 反向回导 + 镜像 @Transient 保留修复，见 §7.3）；**批 11-1~11-3 完成**（S8 子事件 2 自动招募 / 15·16 秘境到期关闭+AI 队伍派遣 / 10 十二月自动购买下沉——GTest 603/603，S-16~S-19 登记，见 §7.3 批 11 行）；**批 12-1~12-2 完成**（S8 子事件 12 弟子智能购买 / 14 任务刷新下沉——GTest 609/609，C-11 shuffled 算法修复、S-19 特判移除、S-20 登记，见 §7.4）；**批 12-3/12-4 审计判定**（任务完成/AI 兽战/洞天 AI 操作三件战斗边界保持 Kotlin，见 §7.4）；**批 12-5 S 系列清偿**（S-10/S-11/S-12/S-13——配置注入 C++ 通道 + 空白名校验 + 转发辅助 NPE 守卫，见 §7.4）；**批 12-6 对拍框架长期化**（CI `cpp-diff-jni-test` job + build-desktop-jni-linux.sh，见 §7.4）；**批 13-1 完成**（月变步骤 3 AI 兽袭目标预计算下沉——aiSectBeastDirectTargets/aiSectBeastSkipCooldowns/lockedBeastIds 协议扩容 + detail::precomputeTargets 等价移植 + DiffPrecomputeTargetsTest 新建对拍，GTest 612/612，见 §7.5）。**2026-08-31（B 批，残留执行器增量下沉 + 双端修正）**：① 自动装备/学习下沉 C++（新建 `system/auto_gear.h`——仓库 + 弟子储物袋候选统一（equipment/manual 实例保真装配、堆叠按名模板重建）+ 更高品阶自动替换，接入 runPhaseSettlement 步骤 0，对齐 Kotlin execute 首步）；② 丹药/突破双端修正（突破丹自动服用逐颗扣减、修复整叠删除 P0——C++ `attemptAutoPill` 原为忠实复刻该 bug 的 diff 基准，同批修正；治疗/回蓝丹满状态门槛 `healGatingBlocked`；战斗临时丹不自动服用；满修为/全功法满级跳过；孕养度丹 nurtureAdd 效果落地——均分至已装备实例）——GTest 659→672（+13），engine JUnit 3028/3028（桌面对拍桥 0 skip），详见 CHANGELOG.md。

> **2026-09-01（批 M-1：月变真相源切换）**：生产月变路径从 Kotlin `MonthSettlementExecutor` 八步编排切换为 **C++ `runMonthSettlement` + Kotlin 残留执行器互插**（旬结算同构模式）——`GameCore::settleMonth()`（`nativeSettleMonth` JNI 信封：policyCosts.disabledPolicies + S-17 秘境关闭草稿 + S-20 购买日志草稿）+ Kotlin `settleMonthNative` 管线（nativeSettleMonth → 增量镜像 → `MonthSettlementResidualExecutor` 单事务（生产结算 4a/4b + 战斗三件子事件 5/6/9 + 邮件 4g + S-17/S-20 草稿应用）→ 反向回导）+ `GameEngineCore.processMonthYearChange` 月变分支切换（native 未就绪回退 Kotlin 完整编排）；**S-14/S-16/S-17/S-20 清偿**（S-14：执法域随月变编排整体入 C++，Kotlin committed 读消灭；S-16：`RecruitService.resetAutoRecruitIdle` 经 `nativeResetAutoRecruitIdle` 同步 C++ 惰性门——重置点收敛；S-17：秘境关闭草稿回传（背包快照 + memberIds）→ `SecretRealmService.applyExpiryCloseDraft` 重建关闭邮件 + gate release；S-20：购买日志草稿 → lifeEvents 瞬态列写入）；**行为基线登记**：残留执行器（生产结算 SYSTEM/任务完成 MISSION）在 C++ 全部消耗之后执行——SYSTEM/MISSION 序列与切换前 Kotlin 编排不同（月变编排整体入 C++ 的必然），C++ 侧 GTest 黄金序列锁定、残留侧委托式 NativeBackedRng 保证确定性；验证：engine JUnit 全量（桌面 JNI 0 skip）+ NDK externalNativeBuildRelease + detekt 全绿 + app compileReleaseKotlin 通过，详见 CHANGELOG.md 与 §7.6。

## 0. 当前项目现状与后续工作（2026-09 审计更新，权威）

> 2026-09 独立技术审计结论。**覆盖 / 修正本文件头部"剩余工作：无（迁移主线全部收口）"的表述**——迁移主线（确定性逻辑核心）确已收口，但"自研跨平台游戏引擎"这一更大目标仍有明确缺口与后续工作。本节为现况与新计划，历史批次归档见 §4/§7 不变。

### 0.1 真实状态（已完成 vs 缺口，基于实际调用链）

**✅ 已完成（扎实、真实）**
- **C++ 逻辑核心**：`gamecore` 纯 C++20、零 Android 依赖、桌面可编译；GTest 722/722；覆盖 RNG/时间推进/旬-月-年结算/弟子 SoA/战斗(计算+AI+回合+三引擎)/经济/库存/灵田/内政/探索/秘境/外交/兑换码/道路。
- **事实真相源**：`NativeEngineFlag.mode = AUTHORITATIVE` 为生产默认；`EngineLoop`(PhaseClock) 帧判据 + `ProgressMonitor` 看门狗判据已入 C++;每旬经 `nativeSettlePhase` 标量通道 + Kotlin 残留执行器互插 + 反向增量。
- **渲染双路径**：Vulkan 完整实现（Device/Swapchain/CommandBuffer/RenderPass/Pipeline/Draw/离屏 renderScale/ASTC 图集）+ Canvas 软件渲染兜底，经 `Rhi.h` 抽象 + `RenderFrame` 契约。
- **平台解耦**：engine 层 `import android.*` 清零；`core/platform.h` 端口注入；gamecore 无任何 Android 泄漏。
- **确定性/对拍体系**：跨语言 Diff 对拍 + GTest 黄金序列 + RNG 分区 + kotlinx-proto 存档链路零改动。

**⚠️ 缺口（审计确凿，后续工作对象）**
| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| G1 | **无 ECS**：只有 DiscipleStore(单一实体 SoA，硬编码 ~124 列) + Kotlin EntityStore/ComponentTable；无 Entity/Component/System、无 SparseSet/Archetype/查询 | `disciple_store.h`（单一弟子型）、`month_settlement.h`（过程式 system 操作大状态） | **✅ ECS 基础①-④ 已建（2026-09 续作，见 §0.3）**；但结算/内政 system 仍为过程式，尚未经 ECS System 调度——后续迁移项 |
| G2 | **完全单线程、无 JobSystem**：gamecore 无 std::thread/async/ThreadPool；游戏逻辑在单线程 executor | `GameEngineCore.kt:396` 单线程 | **✅ JobSystem（§0.3 ③）已建**；但现有 system 未接入并行（5000 弟子每旬 O(D) 单线程热点仍存在，待 ECS 调度迁移后释放） |
| G3 | **地图/建筑/地形数据不在引擎**：瓦片/建筑/道路数据由 Kotlin 生成并喂 RenderFrame；引擎仅道路合成器几何 | `SectMapTileGenerator`/`MainGameScreen` | 世界级 entity(建筑/NPC/空间)无 ECS 基础 |
| G4 | **渲染路径结构性缺陷**：行业降级链均为 `Vulkan→GPU GLES→软件渲染(仅兜底)`，而本项目为 `Vulkan→CPU Canvas` 且**额外关闭系统硬件加速**(→真·CPU 逐像素)，**缺失行业标配的 GPU GLES 中间层**——这是性能/电量风险的最主要根因，也是与行业最大结构性差异 | `VulkanPolicy.detectTier`/`shouldDisableHardwareAcceleration` | **✅ 修复完成（2026-09-09 主修复 + 2026-09 量化阈值 + 2026-09 CPU Canvas 辅线收尾）**：① GPU GLES 中间层（降级链 `Vulkan→GPU GLES→CPU Canvas`）；② 量化阈值决策引擎（default Vulkan + 窄 Deny，`evaluateVulkanTier` + C++ 上报 `setVulkanDeviceInfo`）；③ CPU Canvas 分配微优化（地面子位图跨 chunk 共享 + crop 矩形复用——chunk 缓存/LOD/renderScale 已存在）。**剩余**：真机 Bugly 阈值校准、GLES 真机验证（坐标/UV、混合、ASTC/renderScale/REPEAT） |
| G5 | **iOS/Metal 未开始**：无 Xcode 工程/无 .metal/无 Swift；只有 game-core 纯 C++ 可复用 | 全仓库 glob=0 | iOS 目标未达成 |
| G6 | **主循环线程/帧率策略/存档编码( kotlinx-proto)/UI 全 Kotlin** | `GameEngineCore`/`T-CPP-1` | 双端需各自实现 |
| G7 | **战斗/宗门口战副引擎部分留 Kotlin**（部分系统、AI 兽战后处理、部分平台效应） | 批 12-3/13-8~10 边界 | **✅ 副引擎战斗执行全部闭合（2026-09 收尾）**：批 A-D 已下沉 executeBattle + 三生产接线；**✅ G7-2 AI 攻击决策下沉（2026-09）**：`checkAttackConditions`/`decidePlayerAttack` 下沉 C++（`gamecore/system/sect_attack_decision.h` dict + GameState 模型字段 aiSectPersonalities/activeAttackWarnings/isPlayerProtected + 生产路由 nativeDecidePlayerAttack/nativeCheckAttackConditions + 6 GTest），AISectAttackManager 加 native 优先路由（降级回退 Kotlin）；**保留 Kotlin** 设计边界（战斗组装 BattleDescriptionGenerator（JVM Random）/伤亡/占领落库 = 状态/平台效应；HeavenlyTrial 试炼敌人派生种子）。**剩余**：DiffSectAttackDecisionTest（BATTLE 序列锁序——跨语言全状态对拍需搭完整景，见 §0.1 G7 审计报告） |

### 0.2 后续工作（主线，按优先级）

| 优先级 | 工作 | 对应缺口 |
|---|---|---|
| **P0** | **ECS 基础 + 并行化（见 §0.3）**：对当前单线程/多实体迭代提升最大。**✅ ECS 基础①-④ 已实施（2026-09 续作，GTest 757/757）**——通用 ECS 骨架/System 调度/JobSystem/弟子组件化；现有 system 未接 ECS 调度、world 实体层为后续依赖项 | G1/G2 |
| P0 | **渲染路径结构性修正**：**补 GPU OpenGL ES 中间层**（`Vulkan→GPU GLES→CPU Canvas`）+ `VulkanPolicy` 量化阈值（默认 Vulkan + 窄 Deny）+ 驱动版本黑名单 + 崩溃自愈 + 预渲染地面层优化 Canvas 兜底。**✅ 主体已完成（2026-09-09 GLES 中间层 + 2026-09 量化阈值决策引擎 + 探测上报 + 单测 14 用例）**；**剩余**：CPU Canvas 优化（预渲染/LOD）、真机 Bugly 阈值校准、GLES 真机验证 | G4 |
| P1 | **iOS 立项**：Xcode 工程 + MetalBackend(Metal-cpp) + Swift/ObjC++ 桥 + UI(Compose Multiplatform 1.8.0)/存档(SQLDelight)/图集/输入/音频 + 合规 | G5/G6 |
| P1 | **世界实体层**：若有探索/大地图需求，把建筑/地形/NPC 纳入 ECS World + 空间索引 | G3 |
| P1 | **战斗残余下沉**：宗门口战副引擎/部分平台效应入 C++ | G7 |

> 渲染路径修正见 `docs/adr/render-strategy-decision.md`；iOS 见 `docs/adr/ios-migration-plan.md`；ECS 见 `docs/adr/ecs-foundation-design.md`。

### 0.3 【优先完成】ECS 基础 —— 对当前项目提升最大的改动（排序）

> 用户拍板"打好 ECS 基础"，且要求把**对当前项目提升最大的改动**列为最先完成。下列按"影响 × 依存"排序。首个改动独立、收益即到；随后的并行化依赖它。
>
> **✅ 进度（2026-09 续作）**：①②③④ 已实施——通用 ECS 骨架（entity/component/storage/registry/world）、System 调度框架（view/system + 优先级串行确定性）、JobSystem（线程池 parallelFor 索引确定性）、弟子组件化（DiscipleRef 层，不改 DiscipleStore/JSON）。落地为 `gamecore/include/gamecore/ecs/*` 纯头文件 + GTest 35 用例，桌面全量 **757/757**（722 基线零回归）。设计/落地边界见 `docs/adr/ecs-foundation-design.md`。**⑤（世界实体）默认不做**——建筑/地形/道路数据保持渲染链路（RenderFrame），仅在需要探索/大地图/世界交互时再升级并升级 Archetype。

| 顺序 | 改动 | 影响（对当前项目） | 依赖 |
|---|---|---|---|
| **①** | **通用 ECS 骨架**：`EntityId(index+generation)` 句柄 + 每组件一个 **SoA/SparseSet 列存储** + 实体注册表 | 把单一硬编码 DiscipleStore 泛化为可复用组件存储；entity 复用/防悬垂；为多组件/多实体打基础。**收益：数据导向复用、可扩展。** | 无 |
| **②** | **ECS System 调度框架**：注册式 System + 优先级调度 + `View` 组件查询迭代（确定性/串行，预留 job 接口） | 把"过程式 system 操作大状态对象"改为显式依赖、可测、可组合的 System 图；确定性保住 RNG 行序红线。**收益：系统边界清晰、可并行、可测试。** | ① |
| **③** | **JobSystem + 独立 system 并行化**（战斗/探索/内政/结算等无共享写的 system） | **对当前单线程短板提升最大**：5000 弟子每旬 O(D) 热点、战斗/探索等可并行 system 分摊到多核。**收益：性能直接提升（当前 G2 是主要热点）。** | ② |
| **④** | **弟子组件化**：把 DiscipleStore 泛化为"弟子组件集"（每列一个独立组件 SoA），弟子经实体映射接入 | 弟子迭代 cache 更友好；为与其他实体组合做准备。**收益：弟子量大、迭代频繁，缓存局部性提升。**（注意：不触碰 RNG 行序红线/JSON 协议零变更）| ① |
| ⑤(后置) | **世界实体 + 空间索引**（建筑/地形/NPC 位置 + 栅格/空间哈希） | 仅当需要探索/大地图/世界级交互；当前 128×128 小地图非必需。**设为可选，避免大范围重构。** | ①/② |

**落地边界（首期）**：只实体化**弟子**（项目逻辑主体、数量大）；**建筑/地形/道路数据保持在渲染链路**（RenderFrame）与现有 GridSystem，**不急于迁入 ECS**——避免大范围重构与存档触碰。ECS/弟子组件化均**不改 `state/models.h`、`json_codec`、`rng/*` 的 JSON 协议**（存档零变更、RNG 行序红线不动）。

**确定性红线（全局）**：System 迭代序 == 实体行序（RNG 对拍命门）；禁止 `unordered_map` 参与业务迭代；系统调度先串行，并行化仅限无共享写的独立 system。

> 详细设计 + "避免假 ECS"检查清单见 `docs/adr/ecs-foundation-design.md`。



### 0.4 【已实施】GPU GLES 中间层（2026-09-09）

> 补齐行业标配的 GPU GLES 中间层，使 Android 降级链变为 **`Vulkan→GPU GLES→CPU Canvas`**。
> 决策/对标见 `docs/adr/render-strategy-decision.md`；完整调研见 `docs/research-android-graphics-api-vulkan-gles-software.md`。

**改动清单（本轮落实，未改逻辑核心）**：
- **C++**：新增 `android/app/src/main/cpp/GlesBackend.h/.cpp`（EGL + GLES2 渲染，实现 Rhi `Renderer2D` 接口；单管线/单图集/按纹理批处理/动态 VBO；Y 投影翻转适配 GLES NDC）。`NativeBridge.cpp` 加 `g_backendType` + `nativeSetRenderBackend`，`initRenderer` 按类型创建 `GlesBackend`；5 个 Vulkan 专属方法（prewarm/initSurface/setRenderScale/uploadRepeatTexture/uploadCompressedAtlas）经 dynamic_cast 对 GLES 优雅回退。`CMakeLists.txt` 加入 `GlesBackend.cpp` 并链接 `EGL`/`GLESv2`。
- **Kotlin**：`NativeBridge.setRenderBackend` + `BACKEND_VULKAN/BACKEND_GLES`；`RenderMode.GLES`；`GlesRenderBackend`（复用 `VulkanRenderBackend` 渲染逻辑）；`NativeSurfaceView` 降级链 `Vulkan→GLES→Canvas`（Vulkan 失败先试 GLES 再软件）+ ASTC 仅 Vulkan；`VulkanPolicy` 新增 `RenderStrategy.GLES_PREFERRED`，把"Vulkan 不可靠但 GPU 可用"（MediaTek/Mali/非高通国产/旧 API 非白名单/PROBLEMATIC/崩溃自愈）路由到 GPU GLES；`GameActivity`→`MainGameScreen`→`SectMapViewport` Thread `glesRendering` 标志。

**⚠️ 需真机验证**（本实现静态代码通过，但无法在本环境跑 Android 构建/真机，以下为剩余验证点）：
1. GLES 后端在 NDK/Android 构建编译通过、真机初始化成功。
2. **坐标/UV 翻转方向**：GlesBackend 采用"投影 Y 翻转、UV 不翻转"（注释已说明），若真机发现地图上下/纹理颠倒，改 GlesBackend.cpp 顶点着色器 `vUV.y = 1.0 - aUV.y`（或去投影翻转）即反转——一行修正。
3. **混合模式**：GlesBackend 用普通 alpha（SRC_ALPHA/ONE_MINUS_SRC_ALPHA），若高亮/阴影色差，改预乘（GL_ONE/ONE_MINUS_SRC_ALPHA）。
4. `shouldDisableHardwareAcceleration` 未联动（本次只改游戏地图渲染走 GLES；Compose UI 系统级 HWUI 的 SkiaVK 处置仍保持原策略——见 render-strategy-decision.md 诚实声明，Android<15 回传 SkiaVK 无权威来源，需行业口径确认后再联动）。

**范围外（后续待办）**：GPU GLES 暂不支持 ASTC 压缩图集、离屏 renderScale（直渲全分辨率）、REPEAT 地面无缝纹理（回退逐格地面）——均为可接受的降级（性能差异小，真机验证后可按需补）。

## 1. 目标架构
```
Compose UI (feature/game + app) —— 保留，零改动
  ↓
ViewModel / UseCase —— 保留，零改动
  ↓
GameEngine 族 (~275 方法) + 10 Facade (117 方法) —— Kotlin 保留签名；低频业务操作方法体转发
  ↓  参数/结果 JSON 编解码 (kotlinx.serialization ↔ nlohmann/json)
JNI 桥 GameCoreBridge (通用入口：init/advance/execute/export/import/poll + Clock 注入)
  ↓
★ C++ GameCore（纯 C++20、零 Android 依赖、桌面可编译、iOS 可复用）—— 确定性计算真相源
  ↓  版本号 + 变更集（增量）或全量快照（存档/读档）
Kotlin StateSyncService → GameStateStore（镜像写入，接口/StateFlow 不变）
  ↓
Room 34 表 + .sav/云存档 —— 保留，链路零改动
```

> **终态决策（2026-08-25 二次修订，选项 A：彻底单引擎）**：C++ 为**唯一**引擎核心（游戏循环 + 结算 + 实体存储数据导向 + 渲染），
> Kotlin 最终降级为**纯平台层**（Activity/生命周期/权限/输入桥/平台 SDK/Compose UI 消费只读镜像）+ 存档编码（低频，可选迁移）。
> 推翻 2026-08-25 上午的"职责边界固化（双端并行=最终态）"——依据阶段 0 实测：批量通道往返 0.1µs、传输占比 <0.1%，
> 双实现并行的镜像/对拍开销是纯浪费。详见第 6 节（阶段 0 数据）与第 7 节（计划 v2）。

## 2. 目录结构

```
android/app/src/main/cpp/
├── CMakeLists.txt              # 主构建：native-renderer（渲染）+ native-game-core（引擎 JNI）
├── GameCoreBridge.cpp/.h       # 引擎 JNI 桥（Android 专用薄层，仅转换不承载逻辑）
├── gamecore/                   # 纯 C++ 引擎（零 Android 依赖）
│   ├── CMakeLists.txt          # 静态库 game-core（GAMECORE_BUILD_TESTS=ON 构建桌面测试）
│   ├── include/gamecore/
│   │   ├── core/    types.h / result.h / clock.h / logger.h / platform.h（阶段 5：Clock/Telemetry/热控/电量端口）
│   │   ├── rng/     pcg_xsh_rr.h（DeterministicRng 复刻）/ rng_manager.h（8 分区）
│   │   ├── state/   models.h / json_codec.h（状态模型 + 快照编解码）
│   │   ├── data/    equipment_db / herb_db / trait_db / recipe_db / beast_material_db / manual_db
│   │   ├── map/     road_system.h（道路求解器单一权威）
│   │   ├── system/  economy / inventory / spirit_field / disciple / cultivation /
│   │   │            breakthrough / lifecycle / battle / government / exploration /
│   │   │            time_system / settlement / engine_loop.h（阶段 5：PhaseClock+EngineLoop）/
│   │   │            watchdog.h（阶段 5：ProgressMonitor 判据）
│   │   ├── game_core.h         # 引擎门面（execute/advance/export/import/poll）
│   │   └── action_ids.h        # 生成产物（scripts/gen-action-ids.mjs）
│   ├── src/  rng.cpp / game_core.cpp / json_codec.cpp / execute_dispatch.cpp
│   ├── jni/  GameCoreJni.cpp   # 桌面 JNI 对拍桥（无 Android 依赖，测试用）
│   ├── test/  （GTest，见各批次一览）
│   └── third_party/nlohmann/json.hpp   # vendored 单头 JSON
├── VulkanBackend.cpp ...       # 既有渲染层（不动）
```

## 3. 确定性保真要求（迁移全程）

1. **RNG**：PCG-XSH-RR 复刻；`shuffled(rng)` 用 `std::stable_sort`（Kotlin sortedBy 稳定）——登记 C-11
2. **数学语义**：IEEE754 double、Int/Long 溢出回绕、截断除法、coerceIn/coerceAtMost——登记 C-12（nextGaussian 精度风险）
3. **顺序稳定性**：禁止 `unordered_map` 参与业务迭代（用 map/vector + 显式排序）
4. **现实时间**：一律 `Clock` 注入（对拍用 FixedClock）；引擎内禁直接系统时间
5. **对拍守护**：C++ GTest 黄金序列 + JUnit 跨语言对拍——登记 C-14/C-15

## 4. 已完成批次（归档一览）

> 各批次详细验收记录（产物/对拍清单/验证数字）已随推进写入 git 历史（commit 2bb319d9 / 7269ab6c 及更早）。
> 当前基线：**桌面 GTest 289/289 · engine JUnit 2818/2818 · NDK externalNativeBuildRelease 通过 · engine detekt 全绿**。

| 批次 | 内容 | 关键产物 | 验证 |
|---|---|---|---|
| 0 基础设施 | game-core 静态库（C++20 零依赖）、Result/Clock/Logger、RNG 8 分区复刻、JNI 桥、ActionId 协议、桌面对拍桥 | `core/*`、`rng/`、`GameCoreBridge`、`gen-action-ids.mjs` | GTest + DiffRngTest 双守护 |
| 1 状态模型+快照 | GameData 全字段 + 18 嵌套类型 + Disciple/物品；JSON 编解码（宽松 from_json、optional、浮点规范化） | `state/models.h`、`json_codec`、`NativeGameState.kt` | DiffStateTest + json_codec_test 38 |
| 1 剩余·低频嵌套 | 血炼三件套/功法精通/矿脉/巡视槽位 + **远古秘境状态机 10 类型**（含 optional currentEvent） | models.h 扩展 + 编解码 | DiffNestedTypesTest |
| 2 静态数据 | 装备 72 / 灵草种子 108 / 天赋体质词条 204 / 配方 804 / **妖兽材料 192 / 功法 540** | `data/*_db.h` + 生成器 6 个 | 双端守卫测试（Kotlin + C++） |
| 3 时间+结算引擎 | TimeSystem 复刻 + SettlementEngine（对齐 GameTimeClock 语义、读档复位） | `system/time_system.h`、`settlement.h` | DiffTimeTest + time_system_test 15 |
| 4 经济/库存/灵田 | SpiritStoneWallet 逐行等价 / StackableItemStore / 灵田收获 | `system/economy.h`、`inventory.h`、`spirit_field.h` | DiffEconomy 8 + DiffInventory 5 + DiffSpiritField 4 |
| 5 弟子系统 | 属性乘区法/修炼 Checkpoint/突破/生命周期 | `system/disciple.h`、`cultivation.h`、`breakthrough.h`、`lifecycle.h` | Diff* 30 + GTest 192 |
| 6 战斗系统 | 乘区法伤害/境界压制/斩杀/闪避/护盾/DoT/冷却 | `system/battle.h` | DiffBattleTest 7 + GTest 225 |
| 7 内政系统 | ZoneCalculator 等价/政策成本/灵矿产出/年俸 | `system/government.h` | DiffGovernmentTest 6 + GTest 245 |
| 8 探索/关卡 | 关卡过期/刷新/妖兽移动 | `system/exploration.h` | DiffExplorationTest 2 + GTest 254 |
| 9 核心 | ActionId **46 动作** + execute 分发表（7 handler 统一信封） | `action_ids.h`、`execute_dispatch.cpp` | DiffExecuteTest 6 + GTest 268 |
| 9 剩余·基础设施 | feature flag / StateSyncService（字段级宽松合并）/ tick 桥（shadow 对拍）/ 转发辅助 / 性能基准 | `NativeEngineFlag`、`StateSyncService`、`GameEngineNativeOps` | DiffStateSyncTest 7 + DiffNativeForwardTest 4 + NativeBenchmarkTest 2 |
| R 求解器权威 | 位掩码→形态/描边/邻接计算单一权威（Kotlin RoadTiling ↔ C++ road_system.h 双端对拍）；Vulkan 端位掩码判定收敛 | `map/road_system.h`、`NativeBridge.cpp` 收敛 | DiffRoadTest 3 + road_system_test 16 |

## 5. 未完成项（活跃待办）

### 5.1 批次 9 剩余：转发层收尾（C-06）

| 项 | 说明 |
|---|---|
| 已完成 | feature flag / StateSyncService（宽松合并防丢字段）/ tick 桥（shadow 对拍）/ 转发辅助 / 性能基准（见 4.9 剩余·基础设施）；**阶段 1 新增**：增量变更集通道（C++ `state::DirtyTracker` + Kotlin `StateSyncService.applyDirty/applyDirtyFromNative`，DiffDirtyTest / DiffDirtyDisciplesTest / GTest dirty_tracker_test 三层守护）、RNG 读档恢复接线（C-13，含导出前活动状态回写） |
| 剩余·GameEngine 方法转发 | ~~Kotlin GameEngine 275 方法逐一转发~~ **转发接线面已收口（✅ 批 8-4 判定）**：87 个 ActionId 全量清点六类裁决（已接线 10 / 月结年结旬结内部路径 16 / 纯函数·影子对拍基准 46 / 查询留守 4 / 事务内变更原语留守 3 / 无独立生产调用点·嵌套调用面留守 8）——可接线面已穷尽，判定与证据见 §7.1 批 8-4 行；GameEngine 族其余 ~200 非 ActionId 操作（UI 编排/协调逻辑）终态属 Kotlin 输入桥，不迁移 |
| 剩余·全量切换 | ~~增量变更集~~（✅ 阶段 1 完成）；~~逐系统切换~~（✅ 阶段 2-6 完成）；~~AUTHORITATIVE 生产默认~~（✅ 阶段 7 批 7-1，OFF 保留为回退契约）；~~GameEngine 方法全量转发接线~~（✅ 批 8-4 判定收口——可接线面已穷尽，见 §7.1）；~~SHADOW 对拍模式 + 纯 Kotlin 旬结算路径~~（✅ 退役专项批 9-1/9-2 删除，见 §7.2）；**退役后常态**：逐动作转发降级契约保留（native 不可用回退 Kotlin 原实现）、Diff 对拍框架转长期回归基线（桌面对拍桥全量实跑）、Wallet/Inventory-未接线动作按批 8-4 判定留守（双实现为其降级契约本体） |
| 阻塞依赖 | 未迁移系统（SecretRealm 状态机/外交/邮件/兑换码/11 槽分配/死亡物化/LevelGenerator 等）——**纳入计划 v2 阶段 4 逐批 C++ 化**（不再"保持 Kotlin 实现"） |

### 5.2 批次 10：彻底单引擎（C-07，2026-08-25 二次重定义）

> **2026-08-25 二次重新审视**：上午"职责边界固化"结论基于"批量通道未验证 + 全量退役无收益"的保守假设；
> 阶段 0 实测（第 6 节）推翻该假设——批量通道（标量参数）往返仅 0.1µs、传输占比 <0.1%，
> 且双实现并行 = 每 tick 双倍计算 + 全量快照同步 = 纯浪费。用户决策采纳**选项 A：彻底单引擎**。
> 批次 10 重定义为**阶段化退役 Kotlin 引擎**（计划 v2 见第 7 节），不再保留"Kotlin 永久保留"清单。

| 项 | 说明 |
|---|---|
| C++ 接管（终态） | 游戏循环 + 时间推进/结算引擎 + 实体存储（数据导向/ECS）+ 未迁移系统逐批 + 静态数据单一源 + 渲染 RHI |
| Kotlin 保留（终态） | UI/Compose + 平台能力（SDK/广告/合规/看门狗接口化）+ 存档编码（可选 T-CPP-1 迁移）+ 输入桥 |
| 落地动作 | ① 增量变更集（exportDirty）实现——C++ 真相源 → Kotlin 镜像核心通道；② 未迁移系统按第 7 节逐批 C++ 化（不再"永久保留"）；③ ~~游戏循环平台能力接口化后迁 C++~~（✅ **阶段 5 已完成**：Clock/Telemetry/热控/电量端口 + PhaseClock/EngineLoop + 看门狗判据全部入 C++，Kotlin 驱动侧仅剩协程线程本体/delay/帧率策略/ADPF 上报等平台机制）；④ 静态数据单一源（T-CPP-2 提前触发，✅ 阶段 3 已完成）；⑤ 对拍框架全程守护 |
| 验收 | 每阶段 C++ 真相源切换 + 对拍全绿 + 性能对比（对阶段 0 基线） |

### 5.3 ~~批次 R 剩余：渲染合成器物理下沉~~ ✅（计划 v2 阶段 6 完成，见 §7 阶段 6 行）

| 项 | 说明 |
|---|---|
| ~~已完成~~ ✅ | 求解器权威性（掩码→形态/描边/邻接双端对拍）+ Vulkan 端位掩码判定收敛 road_system.h 单一权威 |
| ~~剩余~~ ✅ **渲染合成器物理下沉**（2026-08-28 阶段 6 完成）：逐格合成（主体/描边条/转角件/十字中心的摆放顺序）统一为单一 C++ 合成器 `gamecore/map/road_compositor.h`（RoadSprite 语义枚举 + 格内整型几何操作序列），Kotlin Canvas `drawRoadsToCanvas` 改为纯数据装配（枚举序→精灵名→源矩形），Vulkan `drawAllTiles` 道路段删除 UV 硬编码改消费操作序列；与生成式图集解耦（合成器零 UV/精灵名依赖，图集映射由各端按枚举序号查表）；降级契约：native 通道不可用（库加载失败，生产不触达）时跳过道路层，chunk 烘焙其余层不受影响 |

### 5.4 审查登记项（C-10 ~ C-15）

| # | 项 | 触发/计划 |
|---|---|---|
| C-10 | **批次 3 剩余：月变/年变结算钩子系统实现**（政策成本/生产/年俸/年度报告等 onMonthChange/onYearChange 钩子接线） | 政策成本/灵矿/年俸已 C++ 化；钩子接线随**计划 v2 阶段 2**（批量结算下沉）推进 |
| ~~C-11~~ ✅ | **审查登记：C++ `shuffled(rng)` 未实现**——实现时必须用 `std::stable_sort`（Kotlin sortedBy 稳定），且确定性对拍 | 批次 5+（涉及随机打乱时） |
| ~~C-12~~ ✅ | **审查登记：nextGaussian 跨语言精度风险**——JVM Math.cos/log/sqrt 与 C++ std::cos/log/sqrt 可能最后一位差异；对拍验证，发现差异则内嵌 fdlibm | ✅ **已清偿（批 13-2c）**：对拍实测 JVM `Math.cos`（intrinsic）与 C++ std::cos 差 1 ULP、JVM `Math.log` 与 C++ std::log 在部分输入差 1 ULP（glibc 与 fdlibm 版本差异）——**双管修复**：① Kotlin `DeterministicRng.nextGaussian` 改用 `StrictMath`（纯 Java fdlibm，无平台 intrinsic——跨桌面 JVM/Android 位级一致，修正权威确定性）；② C++ 内嵌 fdlibm（`gamecore/rng/fdlibm.h`：e_log.c 的 log + JDK FdLibm 的 cos 链），sqrt 沿用 std::（对拍验证一致）；DiffRngTest 新增 `nextGaussian sequence matches Kotlin bitwise`（3 种子 × 3 mean/stddev 组合 × 30 次全位级一致） |
| ~~C-13~~ ✅ | **RNG 读档恢复已接线**：`importStateJson` 从 `GameData.rngStates` 恢复分区状态，`exportStateJson/exportDirtyJson` 导出前回写活动状态（守护：dirty_tracker_test.ImportRestoresRngPartitionStates + DiffStateTest 契约更新） | 完成（计划 v2 阶段 1） |
| ~~C-14~~ ✅ | **审查登记：float 字段对拍覆盖**（WorldSect.x/y、WorldLevel.x/y）——已覆盖抽样，全量 float 语义随批次扩展 | ✅ **已关闭（批 13-2b）**：float 运算核心路径已在对拍中逐位验证——批 13-1 DiffPrecomputeTargetsTest（AI 候选距离 `sqrt((dx*dx+dy*dy).toDouble()).toFloat()` float 运算双端一致）+ 批 13-2b 场景⑭（关卡生成坐标 float + moveBeasts 边界钳制 float clamp 双端一致）+ 批 4 系列 DiffSectDiplomacyTest（WorldSect.x/y 距离参与）+ DiffStateTest/NestedTypesTest（float 字段 JSON 往返） |
| ~~C-15~~ ✅ | **Diff 对拍基准已切换真实引擎**：DiffTimeTest Kotlin 侧改为真实 `TimeSystem` 实例驱动（内联复刻删除）；DiffExecute 等其余对拍本就走真实通道 | 完成（计划 v2 阶段 1） |

## 6. 阶段 0 测量基线（2026-08-25，彻底单引擎决策依据）

> 测试：`Phase0SettlementBenchmarkTest`（纯 JVM，真实 CultivationCore + 桌面对拍桥，预热+多次采样取最小）。

| 测量 | 结果 | 结论 |
|---|---|---|
| C++ 批量通道 `advancePhases(1)`（标量参数，含 JNI 往返） | **0.1µs/次** | 标量/二进制协议下 JNI 往返可忽略；JSON 编解码才是 12µs 往返的成本大头——**高频批量必须走标量/二进制协议，禁用 JSON 逐操作** |
| Kotlin 每旬核心路径（HP/MP 恢复+修炼累积，真实 CultivationCore） | 100 弟子 167µs · 1000 弟子 **327µs** · 5000 弟子 1189µs（O(D)，每弟子 ~0.3µs 收敛） | 每旬检查是真实 CPU 热点（月 30 旬 ≈ 10ms+，叠加月变/年变更高）；未含熟练度/孕养/丹药/突破（同量级 O(D)） |
| 批量下沉传输占比 | **<0.1%** | 每旬整批下沉 C++ 的传输成本可忽略——阶段 2 收益最高且最可行 |

**收益排序（据此安排阶段）**：阶段 2 批量结算下沉（传输无碍，C++ 结算替代 Kotlin 每旬热点）> 阶段 1 增量变更集（消除全量快照镜像）> 阶段 3 数据导向存储（降每弟子成本）> 阶段 4-5 系统迁移/引擎循环（确定性/平台化）。

## 7. 彻底单引擎计划 v2（选项 A 执行路径，合并架构待办）

> 每阶段验收：C++ 真相源切换 + 对拍守护全绿（GTest 289 + JUnit 对拍）+ 性能对比对阶段 0 基线 + 可运行可回退。
> 待办合并规则：C 系列 = 迁移主线（调整触发）；R 系列 = Kotlin 侧质量债务（保留，随 Kotlin 面收窄部分自然消除；R-14 = 2026-08-28 阶段 6 途中发现的 feature:game detekt 存量 10 项——`feature:game:detekt` 从未进入批次验证门，见 architecture.md R 系列登记表）；T 系列 = 触发条件调整（T-CPP-2 提前、T-CPP-1 保持）。

| 阶段 | 内容 | 合并的待办 |
|---|---|---|
| 0 ✅ | 测量基线（已完成：热点 + 批量原型） | — |
| 1 ✅ | **增量变更集 + RNG 恢复**（已完成：`state::DirtyTracker` changed/removed/version 协议 + `StateSyncService.applyDirty` 单事务增量镜像 + import 恢复 rngStates（C-13）+ 对拍基准切换真实引擎（C-15）+ S-01~S-04 清理） | C-13、C-15 |
| 2 ✅ | **批量结算下沉**（已完成 2026-08-26）：每旬核心批次（步骤 1-5 零 RNG）C++ 化（T2.1）、月变钩子（T2.2）、年变钩子（T2.3，含钩子序年先于月对齐）、tick 真相源切换 AUTHORITATIVE 过渡管线（T2.4：settleOnePhase 标量通道 + NativeEngineFlag 三态 + 残留执行器 + NativeBackedRng 委托式 RNG 单一真相源 + 每旬双向同步）；D7 三注册表效果聚合填表 + comprehension 分叉修复（T2.4a）。**AUTHORITATIVE 默认 OFF（灰度开关）**；100 旬逐旬互锁对拍验收 PASS | C-10、C-06 增量部分、T-CPP-2（部分触发：注册表消费侧已统一） |
| 3 ✅ | **反向增量通道 + SoA 实体存储 + 静态数据单一源**（2026-08-26 完成）：
  - **反向增量通道**（T3.1）：AUTHORITATIVE tick 步骤 ⑤ 由全量 importToNative 改为 `applyDirtyToNative` 增量回导——GameStateStoreImpl 事务级反向脏捕获（弟子脏 id peek + gameData 引用 + 集合引用全量/消失 id）+ StateSyncService 信封 {version,changed,removed}（gameData 全量剔除 rngStates + 弟子变化 id 全实体/removed + 集合变化全量/removed）+ C++ `GameCore::applyReverseDirty`（版本严格递增 + 基线同步）；失败降级全量。100 旬互锁对拍 PASS
  - **DiscipleStore SoA**（T3.2）：`GameState.disciples` 由 `std::vector<Disciple>` 改为 SoA 列式存储（~124 列 + idToRow + materialize/append/loadFrom/upsert 保序/removeById/swapRows 旋转同步索引）；JSON 协议零变更；每旬核心批次/月变/年变/突破/丹药路径全列化；快照语义保留；**性能：runPhaseCoreBatch 1000 弟子 180µs vs 阶段 0 Kotlin 基线 327µs（1.8x）**
  - **静态数据单一源**（T3.3 / T-CPP-2）：`scripts/data/*.json`（6 类中性源，唯一权威）→ 6 个 gen-*.mjs 只读中性源 → C++ 表 + 测试快照（重跑零漂移）；Kotlin Registry 由各 RegistryGuardTest + 新增 `StaticDataSingleSourceGuardTest`（中性源 ↔ 快照逐字节）兜底；补齐灵草/种子双端守卫（HerbRegistryGuardTest + herb_db_test.cpp，预存缺口）；修复 beast_material_db.h 中文妖兽名映射漂移（收敛进生成器） | T-CPP-2（Kotlin Registry 文件级生成余项登记，偿还触发：阶段 7/iOS 立项） |
| 4 ✅ | **未迁移系统逐批 C++ 化**（2026-08-27 完成，5 批 6 模块，GTest 478/478 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 4-1 LevelGenerator**：`system/level_generator.h`（妖兽类型/境界属性表 + selectBeastRealm 年份加权 + generateBeastLevel/CaveLevel/WorldLevels 位置去重与距离校验 + 属性预生成 + 洞府奖励）；ActionId 1402/1403；`WorldLevel.beastSpeed` 协议补齐；GTest 15 + JUnit DiffLevelGeneratorTest 3
  - **批 4-2 死亡物化**：`system/death_handler.h`（markDead 三字段 + 年死亡计数 + 装备断言 + backfillDeathYears）；`DiscipleStore.deathYears` 列（纯内存，不进 JSON 协议，upsert 保留语义对齐 Kotlin replaceAll）；ActionId 1404/1405；GTest 11 + JUnit DiffDeathHandlerTest 4
  - **批 4-3 SecretRealm 状态机核心**：`system/secret_realm.h`（playerAvgRealm/rollBeastRealm/事件生成 beast·rest·ruins·direction·AI 遭遇/rollNextEvent 一次 nextDouble 分段/buildBeastPreGenStats/rollBeastLoot/遗迹结算/applyLootLoss 洗牌/AI 队伍派遣/位置寻找 Float 精度/体力 clamp/年变现世判定）；模型复用批次 1；边界：战斗执行（BattleSystem）与秘宝模板实例化保留 Kotlin；ActionId 1406~1419；GTest 29 + JUnit DiffSecretRealmTest 8
  - **批 4-4 外交**：`system/sect_decision.h`（四因素概率模型 + 脱离 + 战力分档，SectDecisionConfig 同源）+ `system/sect_power.h`（弟子/妖兽战力 + fingerprint，Java hashCode 语义见 `system/java_hash.h` UTF-16 解码）+ `system/rarity_progression.h`（品阶时间曲线，3000 年后爬升轨道）+ `system/sect_trade.h`（交易确定性种子/库存曲线/价格波动/灵石映射）；ActionId 1420~1432；GTest 20 + JUnit DiffSectDiplomacyTest 7
  - **批 4-5 11 槽分配**：`system/slot_cleanup.h`（clearAllSlotsDataOnly 11 类槽位纯数据变换）；补齐缺失模型（GarrisonSlot/BattleTeam/BattleTeamSlot/WarehouseGarrisonSlot/CaveExplorationTeam/ActiveMissionLite + GameData/WorldSect 字段 + JSON 协议）；边界：Gate 注册表与完整 ActiveMission 保留 Kotlin；ActionId 1433；GTest 9 + JUnit DiffSlotCleanupTest 2
  - **批 4-6 兑换码+邮件附件**：`system/redeem_code.h`（格式校验/灵根生成含 java.util.Random 48 位 LCG 复现/灵根阶梯/年龄寿元/方差）+ `MailAttachment` 模型与 kotlinx 对齐的附件 JSON 编码；边界：名字/体质/词条/天赋注册表与服务器验证保留 Kotlin；ActionId 1434~1439；GTest 13 + JUnit DiffRedeemCodeTest 3 | 原 C-06 阻塞依赖清单（原"永久保留"清单全部纳入，不再保留） |
| 5 ✅ | **游戏循环入 C++**（2026-08-27 完成，GTest 538/538 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 5-1 平台能力端口**：`core/platform.h`（MonotonicClock + Steady/Fixed 实现、TelemetrySink + Null、ThermalState 枚举 + Settable 热/电 Provider、BatteryStatus 结构）——ADR Clock/Logger 注入先例扩展；GameCoreBridge.cpp 注入 `AndroidMonotonicClock`（CLOCK_BOOTTIME，与 elapsedRealtime 一致含深度睡眠）+ `AndroidTelemetrySink`（logcat）+ Settable 热/电全局实例；`PlatformProviders` + `setPlatformProviders()` 门面
  - **批 5-2 引擎循环**：`system/engine_loop.h`——**PhaseClock**（GameTimeClock 逐位移植：墙钟消费/速度切换旧速度结算/追补上限 3×speed 余量丢弃/consumeDeadTime/forceConsumeOnePhase/refundPhases/msPerPhase/phaseProgress，accumulatedGameMs/speed atomic 镜像 Kotlin @Volatile）+ **EngineLoop**（gameLoopIteration 判据移植：iterate(pausedOrLoading, isSaving) 返回 LoopFramePlan；kLogicDtNs=100ms、kMaxAccumulatorNs=5 步、kMaxStepsPerFrame=5；心跳 lastLoopActivityMs；notifyUserActivity；onLoopRestart）；**17 槽 LongArray 帧计划协议**（每帧一次 JNI 标量通道，禁 JSON——阶段 0 基准 JSON 往返 12µs 为成本大头）；GTest 31（PhaseClock 19 + EngineLoop 10 + 平台端口 2）+ JUnit DiffEngineLoopTest 15 双端对拍（时钟状态机 + 帧计划语义）
  - **批 5-3 看门狗判据**：`system/watchdog.h`——**ProgressMonitor**（GameTimeProgressMonitor 逐位移植：ProgressSnapshot 12 字段/StallVerdict 数值码 0-4/evaluate/classify/classifyFlags/三阈值 45s·90s·20s/std::mutex 线程安全；S1/S4/S5/F2/V1/V6 修复分支随行移植）；GameCore 组合通道 `watchdogVerdict(flags)`（引擎侧状态 C++ 组合 + 平台侧 6 flags）；-1 未初始化回退 Kotlin；GTest 25 + JUnit DiffWatchdogTest 24 全矩阵对拍
  - **批 5-4 AUTHORITATIVE 接线**：`GameEngineCoreLoopOps.kt`（authoritativeLoopIteration 帧迭代 AUTHORITATIVE 化 + tickAuthoritativeStep + nativeVerdictToStall + thermalSeverityCode）+ `GameEngineCore.kt`（gameLoopIteration 顶部 AUTHORITATIVE 分流、onSpeedChanged→nativeLoopSetSpeed 钩子、nativeLoopPipelineActive refund 分流、prepareLoopStart→nativeLoopStart、performEmergencyRestart→nativeLoopOnRestart、onUserActivity→nativeLoopNotifyUserActivity、progressVerdict native 判据分支 + 6 内部辅助/共享辅助提取）；`GameEngineCoreAuthoritativeOps.kt` refund 按真相源分流；回退契约：帧计划不可用→纯 Kotlin 累积器路径；**AUTHORITATIVE 默认 OFF（灰度开关）不变**
  - **批 5-5 R-02 循环路径清除**：`GameTimeClock.kt` 删 `SystemClock/Log` import（SystemTimeSource/TimeSourceModule 移 app 层 `di/PlatformTimeModule.kt`）；`GameEngineCore.kt` 删 `Build` import（doBusyWait SDK_INT≥33 改 supportsOnSpinWait 反射探测）；engine 模块 Android import 的接口化收尾由阶段 7 批 7-2 完成（36→11 处，见 §7 阶段 7 行） | R-02（core/engine Android 依赖随引擎退役自然消除——循环路径 3 处已清除；阶段 7 接口化 36→11，剩余 11 处随 C-06 退役批次移出） |
| 6 ✅ | **渲染 RHI + 合成器统一**（2026-08-28 完成，GTest 550/550 + JUnit 对拍全绿 + NDK 构建通过）：
  - **批 6-1 合成器单一权威**：`gamecore/map/road_compositor.h`（零依赖纯函数 `emitRoadDrawOps`：掩码 → RoadSprite 语义枚举 + 格内整型几何操作序列，顺序契约 主体→描边条→转角件→十字中心 与双端烘焙顺序一致；与生成式图集解耦——合成器零 UV/精灵名依赖，枚举序 = ROAD_RECTS 声明序 = roadUVMap 索引 = SPRITE_KEYS 下标三端映射锚点）；GTest 12（全 16 掩码操作数守恒/几何有界/顺序契约/枚举序锚点/tileSize=36 整型↔浮点一致性）
  - **批 6-2 Vulkan 路段接入**：`NativeBridge.drawAllTiles` 道路段删除 roadTypeForMask/UV 硬编码，改消费合成器操作序列（仅做 操作→SpriteBatcher 数据装配）；新增 roadUVMap 长度防御；删本地 roadTypeForMask 包装（road_system.h 直引）
  - **批 6-3 Canvas 接入**：`SoftwareCanvasBackend.drawRoadsToCanvas` 改纯数据装配（逐格 `RoadCompositorBridge.compose` JNI 通道 → RoadSprite 枚举序→精灵名→图集源矩形，RoadTiling 合成逻辑移除）；生产 JNI `GameCoreBridge.nativeRoadCompose`（无状态纯函数，不依赖引擎实例）；`RoadCompositorBridge`（core/render）+ 守护测试（SpriteAtlasDefGeneratedTest：SPRITE_KEYS ↔ ROAD_RECTS 声明序全等）+ JUnit DiffRoadComposeTest 5（桌面对拍桥 compose op，手算规格 + 全 16 掩码结构不变量）；降级契约：compose 首次调用幂等 ensureLoaded 自加载，仅加载失败（JVM 测试环境/极端损坏）返回 null 跳过道路层（生产不触达，不影响 chunk 其余层）；同批产品回退：恢复建造栏石板路建造入口（回退 8c9b7734，独立提交）
  - **批 6-4 RHI 形式化**：`Renderer2D.h` → `Rhi.h`（RHI 契约：上层 NativeBridge/SpriteBatcher 不得 include 图形 API 头，下层实现 VulkanBackend 现有 / MetalBackend iOS 预留；类名 Renderer2D 保留）；Metal 接入指南（CAMetalLayer/NDC 差异/uploadTexture/submitFrame 语义映射，见 Rhi.h 头注释）——iOS 立项时零上层改动接入 | 批次 R 剩余（✅ 全部完成）、iOS 预留 |
| 7 ✅ | **Kotlin 降级纯平台层 + 存档决策**（2026-08-28 完成，分批 7-1~7-3；**剩余项批 7-4 依赖 C-06 转发收尾，见下注**）：
  - **批 7-1 AUTHORITATIVE 生产默认切换**：`NativeEngineFlag` 默认 OFF→**AUTHORITATIVE**（C++ 真相源切换验收——每旬时间推进+核心结算标量通道、委托式 RNG、Kotlin 残留执行器互插；SHADOW 对拍与 OFF 回退契约保留，任一时刻可切回纯 Kotlin）；随批修复降级契约缺口：`ensureAuthoritativeNative` 原只捕 `Exception`，而 `System.loadLibrary` 失败抛 `UnsatisfiedLinkError`（Error）——OFF 灰度期从未触达该路径，生产同理存在（split APK 损坏/16KB 对齐失败），改捕 `Throwable`（CancellationException 穿透）后 native 不可用严格回退纯 Kotlin；JUnit 4 失败复验全绿
  - **批 7-2 R-02 收尾（engine 平台能力接口化，`import android.*` 36→11 处）**：① `AndroidThermalReader` 物理移 app 层 `platform/`（ThermalReader 接口既有，零 engine 消费者）；② `BatteryAwareController` 拆分——接口 `BatteryStatusProvider`+`BatteryPolicy` 常量+`evaluatePowerPolicy` 纯函数+`NoopBatteryStatus` 留 engine，Android 广播/binder 读取移 app `platform/BatteryAwareController`，测试同步拆分（engine 纯策略 12 用例 + app Robolectric 平台回退 2 用例）；③ `GpuTierDetector` 移 feature/game `ui/game/perf/`（唯一消费域；`GpuTier`/`GpuRenderConfig` 留 engine 供 RenderScalePolicy）；④ `OemPowerProfileProvider` 厂商识别改平台串注入 `injectPlatformManufacturer`（app Application.onCreate 注入 Build.MANUFACTURER/BRAND；未注入按 OTHER 安全回退）；⑤ `android.util.Log`→`DomainLog`（HttpRemoteConfigProvider/SaveLoadCoordinator/DisciplePillManager/BuildingConfigService）；⑥ `android.util.Base64`→`kotlin.io.encoding.Base64`（ManualDatabase，minSdk 24 兼容）；⑦ **资产链端口** `core/platform/AssetSource`（BuildingConfigService/ManualDatabase/ManualRegistry/GameDataManager/ResourcePreloader 全链改造，app `AndroidAssetSource` + CoreModule 绑定；缺失返回 null 由调用方回退，日志语义保留）；⑧ **签名校验端口** `core/platform/ApkSigningCertificateSource`（RedeemCodeService 防篡改校验，证书提取移 app `AndroidApkSigningCertificateSource`，SHA-256 摘要比对留 engine 跨平台一致）
  - **批 7-3 存档决策（T-CPP-1 正式定案）**：**保持 Kotlin kotlinx-proto 存档编码**（Room 34 表 + .sav + 云存档链路零改动；kotlinx-proto 2174 字段号 schema 的 C++ 直出无当前消费者）——偿还触发不变：iOS 立项且需无 Kotlin 纯 C++ 存档时（ADR 技术债表登记）
  - **批 7-4 R-14 全量清偿 + 剩余项登记**：验证门补 `:feature:game:detekt`，10 项存量 + 接口化迁移暴露项全部真修（提取拆函数/折行/RenderFrame 收参/手势簇 467 行拆出 `MainGameScreenGestures.kt`——R-13 拆分专项首阶段；GpuTierDetector 分档规则链 + EglGpuProbe 拆分）；**剩余项（依赖 C-06，登记为阶段 7 续作）**：① `ThermalMonitor`（ADPF，4 处 import）+ `FrameMetricsMonitor`（7 处）接口化——深度耦合引擎循环/看门狗测试面（Bugly #3114 并发敏感守卫），随 C-06 退役批次专项重构；② **Kotlin 引擎逻辑全量退役**：GameEngine 族 ~289 方法中 84 动作协议已建未接线、其余 ~200 操作待 C++ 化（C-06 转发收尾），完成后 Kotlin 双实现删除 + shadow 对拍转回归基线 + 剩余 11 处 import 随迁 | T-CPP-1（定案保持）、C-07 验收（C++ 真相源切换完成；全量退役随 C-06） |

### 7.1 计划 v2 批 8（C-06 转发收尾续作，2026-08-29 启动）

> 范围：批 7-4 登记的剩余项——GameEngine 方法全量转发接线（84 已建未接线 + ~200 待 C++ 化）→
> Kotlin 双实现删除 + shadow 对拍转回归基线。按家族逐批接线，每批 = 逐动作行为审计（C++ handler ↔
> Kotlin 生产路径全语义比对）+ 接线 + 守护测试；**无 C++ 对应动作或行为不等价的一律不接线**（保持 Kotlin 回退）。

| 批 | 内容 | 验证 |
|---|---|---|
| 8-1 ✅ | **监控器平台能力接口化**（engine `import android.*` 11→0 处，R-02 收尾完成）：`core/perf/ThermalPorts.kt`（ThermalStatusReader + PerformanceHintPort 不透明句柄端口）；ThermalMonitor 重写——轮询/映射/线程绑定守卫（Bugly #3114）全留引擎，Android API 移 app `platform/AndroidThermalPorts.kt`（hintManager internal 接缝随端口化消失）；`core/perf/FrameMetricsSession.kt` + FrameMetricsMonitor 重写（卡顿判定/统计留引擎，Window/FrameMetrics 采集移 app `WindowFrameMetricsSession.kt`）；CoreModule 绑定 + GameActivity 调用点改造；ThermalMonitorTest 重写为 fake port 纯 JVM（守卫语义断言逐条对应，脱离 Robolectric） | compileReleaseKotlin + lintRelease + engine/app detekt + ThermalMonitorTest 全绿 |
| 8-2 ✅ | **库存 add/remove 家族生产接线**（首批 7 动作：INV_ADD_{EQUIPMENT_STACK,MANUAL_STACK,PILL,MATERIAL,HERB,SEED} + INV_REMOVE_EQUIPMENT）：逐动作行为审计（C++ `gamecore::system::inventory.h` ↔ Kotlin `InventorySystem` 全语义比对：合并/分块/槽位跨类型容量/溢出邮件/annual 追踪/锁语义逐项等价，登记缺口见下）；GameEngineInventoryOps 7 方法 AUTHORITATIVE 路由（`InventoryNativeForward.tryForward`：flag 三态守卫——SHADOW 保持 Kotlin 执行真相源；顶层失败/native 不可用回退 Kotlin；**data.status=partial 不回退**——C++ 状态已变更，回退会二次入仓复制物品）；**溢出邮件草稿回传通道**（批 8-2 前置缺口修复：C++ handleInventory 信封新增 overflowDrafts 数组 + OverflowDraft 扩展 grade/category/slot/type/growTime/yield 反查区分字段，Kotlin 侧重建最小模型走 `InventorySystem.resolveOverflowItemId` 同一解析路径 + sendOverflowMail 投递，精度与 Kotlin 原路径一致）；GTest 3 用例（partial/full/remover 无草稿）+ JUnit GameEngineInventoryForwardTest 5 用例（OFF/AUTHORITATIVE 回退契约） | NDK externalNativeBuildRelease 通过 + engine 全量 JUnit + detekt + ThermalMonitorTest 回归；GTest 3 新用例已随批 10-0 本地桌面构建实跑（✅） |
| 8-3 ✅ | **库存家族收尾 C++ 化 + 接线**（consolidateStacks/sortWarehouse/toggleItemLock，批 8-2 审计登记的"无 C++ 对应动作"三项）：gen-action-ids 新增 INV_CONSOLIDATE(1027)/INV_SORT(1028)/INV_TOGGLE_LOCK(1029)（87 动作）+ 双端产物重生成；`inventory.h` 新增 `consolidateItems`（Kotlin 2026-08-01 对抗性审查语义逐条移植：单遍合并/满堆叠跳过防振荡/锁定可作目标禁作来源/组间独立序无关）+ `sortStacks`（rarity desc name asc，stable_sort 对齐 sortedWith）+ `sortWarehouse`（含装备/功法实例轨道）+ `toggleItemLock`（6 类堆叠轨道，未知类型 no-op 对齐 when 无 else）；handleInventory 3 case + execute 路由范围扩至 1029；GameEngineInventoryOps 3 方法 AUTHORITATIVE 路由（同批 8-2 三态守卫契约）；GTest 3 用例（三同键堆叠合并锁语义/排序双键序/翻转+未知 id/类型）+ JUnit 回退守卫补 1 用例；**途中发现并修复转发层缺陷：`tryExecuteNative` 的 Kotlin 非空参数内在检查在函数入口（早于 isLoaded 早退）即抛 NPE——测试 mock 未 stub `stateSyncServiceRef` 时必触**（tryForward 先行空过滤，登记 S-12：转发辅助的 Kotlin 非空参数在 mock 场景的入口 NPE 语义） | NDK externalNativeBuildRelease + engine 全量 JUnit（BootSequence 12 用例回归确认）+ detekt 全绿；GTest 6 新用例已随批 10-0 本地桌面构建实跑（✅） |
| 8-4 ✅ | **接线面收口判定**（2026-08-29 完成，审计/判定批——无生产代码变更，判定依据为全仓库调用点核查）：87 个 ActionId 全量清点**六类裁决**（详表见下"批 8-4 接线面收口判定"节）：① 已接线生产 10（批 8-2/8-3）；② 月结/年结/旬结内部路径 16——AUTHORITATIVE tick 已在 C++ 侧执行，无独立 Kotlin 生产调用点（不接线=已接线）；③ 纯函数/影子对拍基准 46——Kotlin 消费方为系统内部计算（战斗执行/秘宝模板/注册表/服务器校验按批 4-3/4-6 边界保留 Kotlin），随双实现退役自然消失；④ 查询动作留守 4（WALLET_BALANCE/TOTAL_SELL_VALUE、INV_CAN_ADD_ITEM/CAPACITY_INFO——事务内消费 + C++ 只读通道在 tick 间落后 Kotlin，直读精确且零成本）；⑤ **事务内变更原语留守 3（钱包族 WALLET_ADD/DEDUCT/BATCH，行为审计完成）**：C++ `economy.h` 为 Kotlin `SpiritStoneWallet` 纯逻辑忠实移植（add 饱和回绕/deduct 自动售卖补差价/batch 预检查原子回滚/年度报告累积逐项等价），判定留守的依据：a) 钱包是 `stateStore.update` 事务内被组合调用的原语（~数十调用点），`tryExecuteNative` 的 `syncFromNative` store 级镜像在闭包内调用会被外层事务提交覆盖（镜像丢失→反向同步把变更冲回）；b) 可观察契约含 Kotlin 独有平台效应（SpiritStoneLedger 流水 + pendingEvents 事件暂存/flush + DomainLog），转发需在转发层第三次复刻该逻辑，劣于现状双实现 + 逐旬对拍；c) JSON execute 往返 1.1× 慢于 Kotlin 真实实现，无性能收益；C++ 侧钱包收敛由反向增量通道保证（gameData 全量同步，每旬 tick 步骤 ⑤）；⑥ 无独立生产调用点/嵌套调用面留守 8（INV_REMOVE_{MANUAL,PILL,MATERIAL,HERB,SEED} 仅被 InventorySystem 内部 sell*/consume 组合操作消费；INV_ADD_{EQUIPMENT,MANUAL}_INSTANCE 的 ItemAdder 接口无外部生产调用点；INV_ADD_STORAGE_BAG 调用面嵌套形态混杂——引导奖励外层 update 闭包/宗门升级批量发放循环/邮件附件分发，无单一自有事务编排入口）。**结论：可接线面已穷尽，C-06 转发接线阶段终结**；剩余终态收尾 = **Kotlin 引擎退役专项**（删除 tick/结算双实现 + shadow 对拍转回归基线，另行批次规划） | 审计批无测试面变更；判定证据：全仓库 ActionIds 引用核查（生产接线仅库存 10 动作）+ execute_dispatch.cpp 87 case 全覆盖核对 + 钱包/库存调用点形态核查 |

**批 8-4 接线面收口判定（2026-08-29 完成，87 动作全量清点六类裁决）**："84 动作已建未接线"的实际生产接线面小于字面量——
- SPIRIT_FIELD_HARVEST / WORLD_LEVEL_* / LEVEL_* / DISCIPLE_MARK_DEAD+BACKFILL 等是**月结/年结内部路径**，阶段 2 AUTHORITATIVE tick 已在 C++ 侧执行，无独立 Kotlin 生产调用点（不接线 = 已接线）；
- SECRET_REALM_*（14）/ SECT_*（13）/ BATTLE_*（7）/ DISCIPLE_BASE_STATS 等纯函数/查询动作的 Kotlin 消费方是系统内部计算（阶段 4 已 C++ 化系统逻辑，Kotlin 侧为影子对拍基准），随双实现退役自然消失，无需单独接线；战斗执行（BattleSystem）/秘宝模板实例化/注册表与服务器验证按批 4-3/4-6 边界保留 Kotlin；
- 真正需要接线的表面 = **有玩家/系统发起的独立生产调用点的编排操作**（如批 8-2 库存 add/remove）+ ~200 个未 C++ 化操作——后者多数是 UI 编排/协调逻辑（终态属 Kotlin 输入桥），逐批判定"转发 vs 留守"而非全量 C++ 化。

六类裁决汇总（合计 87）：

| 类别 | 数量 | 动作 | 判定 |
|---|---|---|---|
| ① 已接线生产 | 10 | INV_ADD_{EQUIPMENT_STACK,MANUAL_STACK,PILL,MATERIAL,HERB,SEED} + INV_REMOVE_EQUIPMENT + INV_{CONSOLIDATE,SORT,TOGGLE_LOCK} | ✅ 批 8-2/8-3 AUTHORITATIVE 路由 |
| ② 月结/年结/旬结内部路径 | 16 | SPIRIT_FIELD_HARVEST、WORLD_LEVEL_MONTHLY/CHECK_EXPIRED、LEVEL_SELECT_BEAST_REALM/GENERATE_LEVELS、DISCIPLE_MARK_DEAD/BACKFILL_DEATH_YEARS、DISCIPLE_{CULTIVATION_PER_PHASE,CHECKPOINT,ACCUMULATE_CULTIVATION,AGE,BREAKTHROUGH}、GOV_{POLICY_COSTS,POLICY_MONTHLY_EFFECTS,SPIRIT_MINE_MONTHLY,ANNUAL_SALARY} | 不接线 = 已接线（AUTHORITATIVE tick C++ 侧执行，ActionId 仅为协议占位/对拍入口） |
| ③ 纯函数/影子对拍基准 | 46 | DISCIPLE_{BASE_STATS,BREAKTHROUGH_CHANCE,MAX_AGE,ESTIMATE_BREAKTHROUGH_MONTH}、BATTLE_FINAL_DAMAGE~COOLDOWN_UPDATE（7）、GOV_ZONE_CALCULATE、SECRET_REALM_*（14）、SECT_*（13）、SLOT_CLEAR_ALL、REDEEM_*（5）、MAIL_ATTACHMENT_ENCODE | 无需接线（Kotlin 消费方为系统内部计算/对拍基准，随双实现退役自然消失） |
| ④ 查询动作留守 | 4 | WALLET_{BALANCE,TOTAL_SELL_VALUE}、INV_{CAN_ADD_ITEM,CAPACITY_INFO} | 留守：事务内消费（DiplomacyService/AutoBuyService 在 update 闭包内 canAddItemInTransaction）+ C++ 只读通道在 tick 间落后 Kotlin（反向同步每旬一次），直读精确且零成本 |
| ⑤ 事务内变更原语留守（钱包族） | 3 | WALLET_{ADD,DEDUCT,BATCH} | 留守（行为审计完成，等价性确认；依据见批 8-4 行 a/b/c 三条——镜像机制不兼容事务内调用/平台效应不可产出/无性能收益；C++ 收敛由反向增量通道保证） |
| ⑥ 无独立生产调用点/嵌套调用面留守 | 8 | INV_REMOVE_{MANUAL,PILL,MATERIAL,HERB,SEED}、INV_ADD_{EQUIPMENT,MANUAL}_INSTANCE、INV_ADD_STORAGE_BAG | 留守：remove 族仅被 InventorySystem 内部 sell*/consume 组合操作消费；instance 族 ItemAdder 接口无外部调用点；storageBag 调用面嵌套形态混杂，无单一自有事务编排入口 |

**批 8-2 行为审计登记缺口**（不阻塞接线，登记偿还）：
- **S-10**：C++ 库存容量常量硬编码（`kWarehouseBaseCapacity=50`/`kWarehouseCapacityPerBuilding=75`，inventory.h），Kotlin 读 `gameConfigProvider.warehouse.*`——config 改动时双端漂移（偿还：配置对象注入 C++ 或 codegen 常量单源）
- **S-11**：C++ `validateStackableItem` 用 `name.empty()`，Kotlin `isBlank()` 拒绝纯空白名——空白名行为差异（低风险）
- ~~未接线（无 C++ 对应动作）：sortWarehouse/consolidateStacks/toggleItemLock~~（✅ 批 8-3 已 C++ 化接线）；consumeMaterialByName（多堆叠跨栈消耗）/sell*/merchant 交易族——保持 Kotlin（批 8-4 判定归入类别 ⑥：组合操作内部路径）
- INV_ADD_EQUIPMENT_INSTANCE(1011)/INV_ADD_MANUAL_INSTANCE(1013) 声明无 handler（顶层 UNKNOWN_ACTION → 天然回退 Kotlin，正确性无损）——批 8-4 判定确认无生产调用点，无需补 handler

**保持不动（与迁移方向无关）**：R-01/03~14（detekt/lint/测试质量债务；R-14 = feature:game detekt 存量 10 项 + 验证门缺口，随阶段 7 Kotlin 面收窄与 MainGameScreen/Canvas 拆分专项处置）、T-D46~D49/T-D40/T-A2/T-RB/T-CONV/T-PRO（平台/发行技术债）、P 系列真机验证、扩展性预留（RemoteConfig/商业化/离线收益——离线收益结算接入点在阶段 4 后自动走 C++）。

### 7.2 退役专项（Kotlin 引擎退役，2026-08-29 启动；批 8-4 接线面收口后启动）

> 终态依据：选项 A 彻底单引擎（ADR 二次修订）——C++ 单一真相源，双实现并行的镜像/对拍开销为纯浪费。
> 退役边界 = **tick/结算层的 Kotlin 并行实现**；逐动作转发降级契约（native 不可用回退 Kotlin 原实现）
> 是产品降级能力的本体，随单引擎长期保留。

| 批 | 内容 | 验证 |
|---|---|---|
| 9-1 ✅ | **SHADOW 对拍模式退役**：`NativeEngineFlag` 三态→双态（OFF/AUTHORITATIVE，SHADOW 枚举删除）；`tickNativeShadow` 影子推进桥删除（GameEngineCoreNativeOps 重写为读档基线对齐单职责）；tickInternal 影子推进调用点删除；DiffNativeForwardTest SHADOW 用例改 AUTHORITATIVE（语义等价：非 OFF 态 + 生产桥未加载降级）；跨语言语义守护由 Diff 对拍测试以回归基线形态继续承担 | compileReleaseKotlin + detekt |
| 10-0 ✅ | **GTest 本地桌面验证门建立**（2026-08-29）：本机桌面工具链（llvm-mingw + SDK cmake/ninja）实跑 GTest 全量 556/556——批 8-2/8-3 登记的 6 个新用例验证缺口关闭；**途中修复 1 个测试场景错误**：`InvOverflowPartialEmitsDrafts` 原"先填 999 满堆叠再入 5"预期 partial——实际双端 StackableItemStore 契约一致（同键堆叠已满且有空槽 → 分块创建新堆叠返回 Success 非溢出；Partial = 发生合并 + 槽位全满），场景改为 49 填充 + 木剑×998 占满 50 槽再入 5 → 合并 1 溢出 4；实现零变更（C++/Kotlin 逐位等价复核） | cmake --build + game-core-tests.exe（llvm-mingw bin 入 PATH） |
| 9-2 ✅ | **纯 Kotlin 旬结算路径删除（tick 层双实现退役）**：`processTickPhases`（OFF 路径多旬合并事务：TimeSystem.onPhaseTick 时间推进 + checkBreakthroughsAndPills 六步结算）删除；`checkBreakthroughsAndPills` 生产入口删除（PhaseSettlementExecutor 完整版 execute 保留为对拍基准）；tickInternal 分支改造——**tick 结算恒走 native**（不再检查 flag：单引擎终态 OFF 不影响 tick），native 未就绪（.so 加载/初始化失败）时本旬跳过结算 + refundPhases 归还未落地旬数（时间不丢），持续不可用由看门狗停滞判据 → 紧急重启自愈（重启重建 native 链路）；私有 FLAG_MONTH/YEAR_CHANGED 常量删除；KDoc 定位更新（NativeEngineFlag OFF 语义收窄为逐动作/循环集成降级；TimeSystem.onPhaseTick 保留为对拍时间驱动器；GameTimeClock.refundPhases 注释随行）；**语义决策**：引擎级"切回纯 Kotlin"回退契约随退役消灭（选项 A 的必然结果），逐动作降级与循环/看门狗 Kotlin 集成路径保留 | **engine JUnit 全量 2925/2925（桌面 JNI 对拍全执行 0 skip）+ engine detekt + app compileReleaseKotlin 全绿，零回归** |

**退役专项剩余（登记）**：
- 对拍框架长期化：Diff *Test 全套以桌面对拍桥（`-Dgamecore.jni.path`）作为 C++ 回归基线持续运行（CI 桌面 job + 本地 build-desktop-jni.ps1）；Kotlin 臂（残留执行器 + TimeSystem.onPhaseTick 时间驱动）即回归基准，不再承担"迁移验收"职责
- Wallet/库存未接线动作双实现按批 8-4 判定留守——该双实现即逐动作降级契约本体，不退役
- 月/年残留执行器（MonthSettlementExecutor/YearSettlementExecutor/PhaseSettlementExecutor.executeResidual）为 AUTHORITATIVE 生产实现的 Kotlin 侧组成（未 C++ 化扇出见 month_settlement.h 范围边界），其 C++ 化属后续增量迁移批次（非退役范畴）。**2026-08-31（B 批）进展**：`executeResidual` 首步"自动装备/学习"已下沉 C++（`auto_gear.h`，含储物袋候选 + 高品阶替换，接入 `runPhaseSettlement` 步骤 0）；丹药/突破路径双端修正（突破丹逐颗扣减、治疗丹门槛、战斗丹排除、满修为跳过、孕养度丹落地）——Kotlin 残留执行器（`executeResidual` 的自动丹药/突破 + 月/年编排）仍为生产组成，详见基线行 B 批登记

### 7.3 月变/年变残留执行器增量 C++ 化（2026-08-29 启动；非退役范畴）

> 退役专项（§7.2）收口后的主线：把 AUTHORITATIVE 生产管线中仍由 Kotlin 残留执行器
> 承担的月变/年变编排逐批下沉 C++（月变八步中未下沉扇出 + S8 十六子事件余量，
> 见 month_settlement.h 文件头范围边界）。每批 = Kotlin 源码逐条审计 → C++ 等价
> 移植（含协议扩容）→ GTest 黄金序列 + JUnit Diff 对拍守护。真相源切换（月变编排
> 整体走 C++）待下沉面收敛后单独立批。

| 批 | 内容 | 验证 |
|---|---|---|
| 10-1 ✅ | **S8 子事件 8：侦察信息过期清理**（Kotlin `CultivationEventDiplomacyOps.applyScoutInfoExpiry` 等价移植；零 RNG 纯数据变换）：**协议扩容**——宗门详情域 6 模型入 C++ 快照（SectDetail/SectScoutInfo/MineSlot/SectWarehouse/WarehouseItem + GiftPreferenceType 枚举按 name-string 约定，GameData 新增 `sectDetails`/`scoutInfo` 两 map 字段，json_codec 双向编解码；dirty_tracker 对 gameData 顶层字段为通用 diff，新字段自动覆盖）；C++ `detail::applyScoutInfoExpiry`（过期判定/无过期纯早退/三段更新逐条对齐 Kotlin 读取顺序——剩余条目明细刷新+新建、被移除明细 scoutInfo 清空保留其余字段、worldMapSects.isKnown 翻转读原始明细）；接线进 runMonthSettlement 子事件 8 位（gameOverCheck 与 spiritMine 之间，相对序对齐 Kotlin） | GTest +2（过期移除+isKnown 翻转+明细保留/新建刷新；无过期零写入）558/558 · DiffMonthSettlementTest 场景扩展（AI 宗门×2：过期/未过期+明细保留/新建+嵌套 map 资源/弟子键）1/1 · engine JUnit 2925/2925（0 skip）· NDK externalNativeBuildRelease 通过 · detekt 绿。**途中修复协议默认值缺陷：giftPreference C++ 默认空串→"NONE"**（Kotlin 枚举默认名，空串不可解码） |

| 10-2 ✅ | **S8 子事件 4：月度叛逃检测**（Kotlin `LawEnforcementProcessor.processLawEnforcementMonthly` 等价移植）：**辅助函数入 C++**——`stats::baseIntelligence`（Disciple/行版双载，同 baseComprehension 口径）+ `stats::positionEffectBonus`（天赋非负面 + 词条 PositionBonus 按 slotType 求和，消费 trait_db 单一数据源）；C++ `detail::processLawEnforcementMonthly`（从众门控整数除法均值/捕获率三段算式 clamp [0,1]（长老智力阶梯×(1+职务加成)+执法弟子阶梯+双政策）/at-risk 行序扫描（存活+非免疫状态+忠诚<阈值+保护期）/SYSTEM 抽取序（每候选 1 次概率判定 + 通过后 1 次捕获判定））/捕获思过（remove+末尾重插 REFLECTING + statusData 思过年限 + guideCounters.discipleImprisoned + DESERTION_CAUGHT 事件）/逃脱清理（clearAllSlotsDataOnly 含住所 → 装备/功法实例移除 → 熟练度移除 → 弟子移除 + 年度计数 + DESERTION 事件）；接线进 processMonthlyEvents 子事件 4 位（招募归零后、gameOverCheck 前）；**S-13 登记**：执法堂配置常量 C++ 取默认值（Kotlin 读远程配置可空覆盖） | GTest +3（从众门控零抽取/逃脱黄金序列含装备功法清理+分区快照锁/捕获黄金序列含长老智力+政策+重插行序+引导计数+事件锁）561/561 · **DiffMonthSettlementTest 场景扩展**：Kotlin 臂换装真实 LawEnforcementProcessor（原 mock 不消耗 RNG 无法对拍）+ 叛逃候选弟子（忠诚 0/未成年避配对扰动/IDLE/保护期外）1/1 跨语言逐位一致（含 rngStates 结构对拍）· engine JUnit 2925/2925（0 skip）· NDK 通过 · detekt 绿 |

| 10-3 ✅ | **S8 子事件 3：月度偷盗兜底**（Kotlin `LawEnforcementProcessor.processTheftIfNeeded → processTheftMonthly → processSingleDiscipleTheft` 非事务版全链等价移植）：C++ `detail::processTheftMonthlyFallback`——① `theftJudgementsThisMonth` 无条件归零（Kotlin 首行）；② 前置链（灵石>0 / 年度成功上限 3 / 从众门控 / hasCandidate 门）；③ 候选收集（道德<30 + IDLE + 保护期 12 月 + 年判定去重，`take(3)` 消耗名额制）+ `canDiscipleAttemptTheft` 复检（判定标记先于概率抽取——未遂同样计数+年标记）；④ 四步偷盗链：偷盗概率（道德差×0.01 clamp [0,0.90]×宵禁减免）→ 执法堂捕获（复用批 10-2 捕获率；**原位** REFLECTING + statusData 思过年限，无 remove/重插与引导计数——异于叛逃捕获）→ 仓库驻守智力比拼（nextInt 选仓 + isActive=discipleId 非空）→ 成功偷窃；⑤ 成功偷窃：金额新公式（境界基准×(1+身法/智力加成)×随机波动(±20%)，clamp [100, 灵石×10%]；`Long.coerceIn` max<min 抛异常→safelyRunInState 吞掉中止——以异常等价模拟）+ 六类堆叠轨道加权物品选取（数量展开等概率池、抽取即移除、首现序 groupBy 分组）+ `LootCalculator.applyLoot` stolenItems 段（扣减下限 0 + 末尾统一过滤 0 数量）+ 储物袋灵石/条目入袋（obtainedYear/Month 现值）+ `warehouse_theft` 事件 + `annualTheftCount` 递增；⑥ 偷盗后叛逃（`desertDiscipleCleanup` 参数化 eventType/summary 复用批 10-2 清理体：11 槽清理→装备/功法实例移除→熟练度移除→弟子移除→theft_desertion 事件+年度计数）。**配套**：DiscipleStore 新增 `lastTheftJudgementYears` 纯内存列（Kotlin 稀疏组件表语义：读档归零、upsert 保序旋转存/复、removeById 随行清除、不进 JSON 协议——deathYears 同款）+ `stats::baseStats` 完整基础属性（Kotlin getBaseStats 等价，血炼参数缺省 null）；**S-14 登记**（见 §8） | GTest +20（成功/执法堂捕获/守卫抓捕/物品选取与仓库扣减/偷后叛逃/从众门控/年上限/无候选/保护期/未遂标记/未遂常量/金额下溢中止——种子扫描黄金序列 + SYSTEM 分区快照锁）581/581 · **DiffMonthSettlementTest 场景扩展**：偷盗保护期候选（道德 10 + 入伍月 13，双端保护期口径均 <12）候选排除零抽取——虚假 SYSTEM 抽取即移位叛逃序列对拍失败，门控（平均忠诚 42<50）与 hasCandidate 路径真实覆盖 1/1 · engine JUnit 2925/2925（0 skip，DiffMonthSettlement 实跑）· NDK externalNativeBuildRelease 通过 · detekt 全模块全绿（`assertExplicitAssertions` ⑥ 断言块提取 `assertTheftProtectedCandidateZeroEffect` 清偿 LongMethod 64/60） |

| 10-4 ✅ | **S8 子事件 13：附庸脱离检查**（Kotlin `VassalService.processMonthlyBreakawayCheck` 等价移植；全链读事务内 state——MutableGameState 重载无 S-14 口径差）：**协议扩容**——① `VassalContract` 修正为 Kotlin 真实形状（`vassalSectId`/`establishedYear`/`lastTributeYear`，原占位结构系批 4-5 误植 GarrisonSlot 形状，因对拍场景从不填充而休眠未暴露）；② `SectRelation.acquainted` 补齐；③ `sectBattleRecords`（宗门战报，SectBattleType 存 name-string）入 GameData；④ **`aiSectDisciples` 入 GameState 顶层**——Kotlin `GameData.aiSectDisciples` 为 `@Transient` 重型数据（Room GameHeavyData 表单独存，不进 kotlinx 序列化），快照协议改经 `NativeGameState.aiSectDisciples` 顶层可空字段承载（null=旧 .so 未导出 → 镜像不回写保持 Kotlin 权威；非 null → applySnapshot 写回），DirtyTracker 仅跟踪 gameData 字段与固定集合清单 → 本字段不进脏导出、镜像/脏通道零污染；C++ `detail::processVassalBreakaway`（契约空/无玩家宗门纯早退零抽取/战报近 3 年窗口 `year >= gameYear-3` 四类计数/玩家战力 `calculateSectPower`（存活弟子 `stats::baseStats` 血炼 null 口径 × `discipleCombatPower` 六参公式）/AI 战力同公式作用于 aiSectDisciples/好感 `FavorDomain.findRelation` 双向匹配默认 50/等级映射 `fromFavor`（越界 HOSTILE）/概率委托批 4-4 `sectBreakawayChance`（战力反向分档 + 丢失比例×0.30 + 失败比例×0.15 + 好感分值×0.15，clamp [0,0.40]）/每契约恰抽 1 次 SYSTEM nextDouble（宗门已不存在→无抽取直接移除；AI 战力 0→无抽取不脱离）/契约过滤移除 + WORLD `vassal_breakaway` 事件（worldMapSects 查名））；接线进 processMonthlyEvents 子事件 13 位（灵矿后、missionRefresh 前，相对序对齐 Kotlin） | GTest +10（协议往返含 aiSectDisciples 顶层/契约空与无玩家宗门零抽取/宗门缺失静默移除/零 AI 战力不脱离/至交+战力比≥5x 概率 0.0 黄金序列/战力比 0 敌对好感 0.40 种子扫描脱离+留守双分支/战报窗口边界四类计数/JSON 导出导入后 SYSTEM 抽取数连续）591/591 · **DiffMonthSettlementTest 场景扩展**：玩家宗门 p1 在场（gameOverCheck 走"本宗未被占领"）+ 附属 ai-3 至交 100 + AI 弟子同规格（战力比=存活数≥5 精确成立）恰抽 1 次必不脱离 + Kotlin 臂换装真实 VassalService 1/1 跨语言逐位一致（SYSTEM 终态 6 抽=4 配对+1 叛逃+1 附庸）· engine JUnit 2925/2925（0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全模块全绿。**途中发现并根治协议缺口：`GameData.aiSectDisciples` @Transient 不入快照 → C++ 侧 AI 战力恒 0 致脱离永不触发（对拍 SYSTEM 5 抽 vs Kotlin 6 抽实锤）**——协议改顶层承载 + 镜像/脏通道零污染设计（见上）；**S-15 登记**（见 §8） |

| 10-5 ✅ | **S-15 清偿：aiSectDisciples 反向回导 + 镜像 @Transient 保留修复**（批 10-4 收尾三件套）：
  - **镜像清空根因修复（预存问题，非本批引入）**：`mergeGameData`/`mergeGameDataChanges`/`applySnapshot` 全量替换分支经 `GameData.serializer` 解码时 `@Transient aiSectDisciples` **解码必然丢失** → AUTHORITATIVE 每 tick forward 镜像清空 Kotlin 侧 AI 弟子池，被 `checkAndRepairAiSectDisciples` 自愈重新随机生成掩盖（AI 宗门弟子进度/装备反复重置丢失）——三处解码后显式回填事务内既有值（"未迁移字段保留 Kotlin 既有值"语义完整性）；`applySnapshot` 顶层字段携带（非 null）才覆盖，镜像永不主动清空该域
  - **反向回导接入**：反向信封 `changed.gameData`（@Transient 不入 gameData JSON）新增独立 `changed["aiSectDisciples"]` 全量段；`StateSyncService.lastAiSectDisciplesSent` 变化检测缓存（importToNative 全量导入后对齐、sendReverseEnvelope 发送成功后对齐——AI 招募/战斗才重发，避免每 tick 重发重型数据）；C++ `applyReverseDirty` 新增 `aiSectDisciples` 段应用（整体替换语义；gameData 覆盖不受影响——字段已移 GameState 顶层） | GTest +1（段应用+整体替换+与 gameData 覆盖互不干扰）592/592 · DiffStateSyncTest +4（mergeGameData 保留/全量替换保留/C++ 携带覆盖/反向信封变化检测——首次携带、未变不携带、变化携带新值）· engine JUnit 2925/2925（0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全模块全绿 · lintRelease 通过。**S-15 关闭** |

| 11-1 ✅ | **S8 子事件 2：自动招募 autoRecruit**（Kotlin `RecruitService.processAutoRecruit` 等价移植，零 RNG）：`system/recruit_settlement.h`——RecruitIntegrity 移植（isValidRecruit/同人签名/isSamePerson/三级去重含 id/内容/同人，内容去重按协议字段全等）、`allocateAndInsert` 等价（id=max+1、资质哨兵 50 散列补算 floorMod(id×527+31,span)）、`materializeCaptiveGear` 全链（四槽位装备实例 + 功法实例去重截断 + HP/MP 增量 + 熟练度注册，消费 equipment_db/manual_db 单一数据源）、惰性门 `autoRecruitIdle`（GameState 瞬态字段不进 JSON 协议，重置点仍在 Kotlin——**S-16 登记**）；接线进 processMonthlyEvents 子事件 2 位（零 RNG 不扰动 SYSTEM 抽取序） | GTest +5（月度黄金含资质/recruitedMonth/年计数/SYSTEM 快照锁/惰性门/月度上限/俘虏落库含实例 id 自增与熟练度/损坏净化先于去重）597/597 · **DiffMonthSettlementTest 场景⑧**：招募候选 1 根匹配 + 2 根不匹配，跨语言逐位一致（资质 82/recruitedMonth 14/列表保留/年计数）· engine JUnit 全量（桌面 JNI 0 skip）· NDK 通过 |
| 11-2 ✅ | **S8 子事件 15/16：秘境到期关闭 + AI 队伍派遣**（Kotlin `SecretRealmService.processMonthlyExpiryCheck` 状态段 + `SecretRealmAIProcessor.processMonthlyAiTeams` 等价移植，零 RNG）：`system/secret_realm_settlement.h`——① 派遣（存在性门控/已派去重/存活筛选/境界升序稳定取 4/宗门名与等级回退；队伍 id 确定性自增——**S-16 同族：aiSectDisciples 迭代序 std::map 键升序 vs Kotlin 插入序，对拍以键升序规避**）；② 到期关闭**可移植状态段**（到期判定 spawnYear+50/背包灵石入钱包 LOW-SecretRealm/背包清空/会话·秘境·AI 队伍清场/冷却年/SECT secret_realm 事件）——邮件（buildExpiryCloseMail+sendDirectMail 异步落库）与 assignmentGate.release（纯内存注册表）保留 Kotlin（**S-17 登记**，邮件草稿通道随月变真相源切换接线）；`recordGameEvent` 移入 settlement_detail.h 共享（P-9 序号/裁剪守卫单一定义） | GTest +3（派遣黄金含幂等去重/境界排序/到期关闭黄金含钱包·背包·清场·事件·幂等/未到期保留）600/600 · **DiffMonthSettlementTest 场景⑨**：秘境存在 + ai-3 存活弟子 → 恰 1 队跨语言逐位一致（id 字段镜像生成排除）· engine JUnit 全量 · NDK 通过 |
| 11-3 ✅ | **S8 子事件 10：12 月自动购买 autoBuy**（Kotlin `AutoBuyService.executeAutoBuy` 等价移植，零 RNG 主路径）：`system/merchant_settlement.h`——**协议扩容**：MerchantItem 补齐 `type`/`grade`（Kotlin @ProtoNumber(3)/(11)，旧 .so 宽松兼容）+ `AutoBuyEntry` 模型 + `autoBuyList` 入 GameData 双向编解码；MerchantItemConverter 模板路径（equipment/manual/pill/material/herb/seed 按名查表，consumes equipment_db/manual_db/recipe_db/beast_material_db/herb_db 单一数据源）+ 未知物品回退分支（Kotlin 用 JVM 全局 Random 非确定性 → C++ 用 SYSTEM 分区，**S-18 登记**）；容量检查（computeSlotCount<computeMaxSlots + 六类堆叠合并余量）；钱包扣除 LOW-Purchase-MerchantTrade；addXxx 入库（overflow 草稿本地收集丢弃——自动类路径溢出不抑制，Kotlin 真相源发邮件，**S-18 同族**）；接线进 processMonthlyEvents 子事件 10 位（仅 month==12） | GTest +3（已知模板黄金含钱包/商人库存/仓库入库/年度来源追踪/SYSTEM 快照锁/灵石不足部分购买/中品灵石与无匹配跳过）603/603 · **DiffMonthSettlementTest 场景⑩**：12 月跨月界 autoBuy 对拍（(1,11)→(1,12)）——钱包/商人库存/年度支出跨语言逐位一致；库存年度 by-source 因 FakeGameStateStore 嵌套事务不回写外层 buffer（S-14 committed 读口径差家族）diff 面排除，C++ 侧 GTest 守护；availableMissions 为 Kotlin 非托管 RNG 内容（任务批次边界 **S-19**）diff 面排除 · engine JUnit 全量 · NDK 通过 |
| 11-4 ✅ | **途中发现问题清偿批**（2026-08-30，用户指示"解决途中发现的问题"）：① **FakeGameStateStore 嵌套事务修复（S-14 家族测试基建）**——对齐生产 GameStateStoreImpl ReentrantLock 重入语义：嵌套 update 复用最外层事务 buffer（不 persist/不 captureReverse/不递增 updateCallCount），最外层统一提交——12 月 autoBuy 对拍**库存集合与年度 by-source 纳入 diff 面逐位一致**（删除排除项；库存 id 仍为镜像生成字段排除）；Kotlin 臂 `advanceKotlinSide` 补填集合字段；**途中发现并修正 C++ 转换器两处 Kotlin 行为对齐缺口**（MerchantItemConverter.toEquipment 模板分支遗漏 critChance、toManual 模板分支遗漏 skillHealFixed——Kotlin 预存小缺口，C++ 逐位对齐）；② **S-19 清偿（任务 RNG 确定性化）**：`RngPartition` 新增 `MISSION(8)`（Kotlin + C++ `kMission` 双端，initSystemSeed seed+8，旧档 rngStates 缺键向前兼容）；`MissionSystem` 收敛于 GameRngManager.MISSION 分区（生产经 CultivationEventProcessor @Singleton 构造幂等注入；测试须显式注入固定种子实例，MissionSystemTest @Before/@After 注入+复位）；12 月对拍 rngStates 8 号键特判（C++ 任务逻辑未下沉不消费，任务批次下沉后移除）；③ **S-18 清偿（回退分支确定性化）**：C++ 商人转换未知名回退改物品名稳定散列选池（FNV-1a）——零分区 RNG 消耗（损坏数据触达不污染确定性流）；④ **GTest rng_test 分区数断言 8→9 更新** | GTest 603/603 全绿 · DiffMonthSettlementTest 三场景全绿（12 月含库存集合+年度 by-source diff）· MissionSystemTest/GameRngManagerTest/DiffRngTest 全绿 · engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 · 存档兼容：rngStates 新增 8 号键（动态 map 无 schema 变更，旧档缺失按种子播种） |

### 7.4 批 12（2026-08-30 续作：S8 余量 + S 系列清偿 + 对拍框架长期化）

> 范围：用户确认"全部包含"恢复批 11 收窄的五件子事件 + S 系列清偿 + 对拍框架长期化。
> 审计判定（批 12-3/12-4）：任务完成(5)/AI 兽战(9)/洞天 AI 操作(6) 三件因**战斗边界**保持 Kotlin——
> COMBAT 任务全路径经 `BattleSystem.executeBattle`（批 4-3 边界"战斗执行保留 Kotlin"，C++ battle.h 仅为
> 乘区法公式层无 executeBattle 等价物），AI 兽战/洞天同源；按批 8-4 判定方法论"无 C++ 对应动作或行为
> 不等价的一律不接线"，审计登记保持 Kotlin（详见 month_settlement.h 文件头范围边界）。

| 批 | 内容 | 验证 |
|---|---|---|
| 12-1 ✅ | **S8 子事件 12：弟子智能购买 executePurchase**（Kotlin `DisciplePurchaseService.executePurchase` 等价移植）：`system/disciple_purchase.h`——collectDisciples（存活 + 资金 >0 上下文）/buildPurchaseDecisions（功法→装备→丹药优先级、A 组空槽优先 B 组升级、每弟子类别上限 功法2/装备2/丹药10、`shuffled` 洗牌）/applyPurchaseDecisions（hasWarehouseStock 未锁定+name/rarity+丹药 grade.displayName 匹配、deductWarehouseStack exact id→name+rarity 回退、入袋 StorageBagItem+stackedData/effect、灵石先储物袋后随身、宗门灵石入账）；**途中修复 C-11 登记项 `shuffled` 算法**——Kotlin `Iterable.shuffled(rng: DeterministicRng)` 为**每元素 1 次无参 nextInt() 随机键 + 稳定排序**（非 Fisher-Yates！原实现 Fisher-Yates 致 SYSTEM 分区消费次数双端失配）；接线进 processMonthlyEvents 子事件 12 位（灵矿后、附庸前） | GTest +3（单弟子三类别购买黄金含入袋/仓库扣减/宗门入账/SYSTEM 快照锁、无资金零写入、无仓库货跳过保留 listing）606/606 · **DiffMonthSettlementTest 场景⑪**：2 弟子+上架装备/丹药+仓库有货，跨语言逐位一致（SYSTEM 分区消费对齐——Kotlin shuffled 4 次无参 nextInt vs C++ 同序）· engine JUnit 全量 · NDK 通过 · **S-20 登记**：购买日志 lifeEvents 为 Kotlin 类体属性非协议字段（C++ 无该列，diff 面天然排除） |
| 12-2 ✅ | **S8 子事件 14：任务刷新 processMissionRefresh**（Kotlin `MissionSystem.processMonthlyRefresh` 等价移植）：**协议扩容**——Mission/MissionRewardConfig 模型 + 24 个 MissionTemplate 模板静态属性表（displayName/description/difficulty/missionType/enemyType/duration/triggerChance/权重池）+ `availableMissions` 入 GameData 双向编解码（JSON 键 `template` 与 Kotlin @Serializable 字段名对齐——`template_` 为 C++ 内部字段）；`system/mission_settlement.h`——month%3==0 门、nextInt(7) 刷新数、加权池抽取（nextDouble×总权重，首个累积权重 > roll）、createMission（name=难度displayName+模板displayName，rewards 四级回退）、cleanedMissions（刷新月清空旧列表 Kotlin emptyList 语义）；接线进 processMonthlyEvents 子事件 14 位（附庸后、秘境前）；**S-19 特判移除**（rngStates 8 号键 + availableMissions diff 排除——双端消费对齐，仅 Mission.id 镜像生成排除） | GTest +3（刷新月黄金含模板抽取序/MISSION 快照锁/清空语义、非刷新月零写入、24 模板奖励配置全覆盖）609/609 · **DiffMonthSettlementTest 12 月场景**：任务刷新（month=12）双端逐位一致（availableMissions 内容一致，id 排除）· engine JUnit 全量 · NDK 通过 |
| 12-3/12-4 ✅ | **审计判定批**（任务完成 5 / AI 兽战 9 / 洞天 AI 操作 6）：依赖面核查——任务完成 COMBAT_REQUIRED/COMBAT_RANDOM（12 模板）经 `BattleSystem.executeBattle`；AI 兽战全路径 executeBattle；洞天依赖 AISectDiscipleManager 修炼域（境界/突破/装备生成）+ AI vs AI 战斗 + 占领结算。三件均依赖**未下沉战斗执行**（批 4-3 边界保留 Kotlin），按批 8-4 方法论登记**保持 Kotlin**——战斗系统 executeBattle 全流程 C++ 化（回合循环/技能/日志/RNG 消费序）为独立工程，随战斗批次推进 | 审计批无测试面变更；判定证据：battle.h 无 executeBattle 等价物 + 三件源码调用链核查（登记于 month_settlement.h 文件头） |
| 12-5 ✅ | **S 系列清偿**：① **S-11**（空白名校验）：`inventory.h validateStackableItem` 改 `name.empty() → isBlankString` 语义（Kotlin isBlank 拒纯空白名）；② **S-12**（转发辅助入口 NPE）：`GameEngineNativeOps.tryExecuteNative` 的 `stateSyncService` 参数改可空 + 内部守卫（mock 未 stub 场景不再函数入口 NPE）；③ **S-10 + S-13**（配置单源缺口）：新增 `core/game_config.h`（GameConfig 全局实例 + setGameConfig 注入）+ JNI 通道 `nativeSetGameConfig`（GameCoreBridge.cpp）+ Kotlin `GameConfigNativeBridge`（CultivationEventProcessor 构造注册 + ensureAuthoritativeNative 补注，双点幂等）；inventory.h `computeMaxSlots` 与 month_settlement.h 执法堂常量改消费注入配置（默认值兜底 = game_config.json 值） | GTest 609/609 全绿（默认值兜底零行为回归）· engine JUnit 全量 · NDK 通过（新 JNI 符号编译）· engine detekt 全绿 |
| 12-6 ✅ | **对拍框架长期化**：`scripts/build-desktop-jni-linux.sh`（Linux g++ 构建桌面对拍桥，镜像 Windows 版源列表）+ CI 新增 `cpp-diff-jni-test` job（构建 JNI 桥 → `-Dgamecore.jni.path` 实跑 engine 全量含 Diff*Test 0 skip）——对拍框架从"本地手动"升级为 CI 长期回归基线 | **CI 首跑（push 05ce3c5f，2026-08-30）**：`cpp-engine-test` ✅（Linux GTest 全过）· `cpp-diff-jni-test` 的 **Build desktop JNI bridge ✅**（Linux g++ 构建成功——脚本链路验证通过）但测试步骤 ❌（原因待日志，无 GH token 无法下载；本地 --rerun-tasks 等价命令全绿 → 疑 Linux 环境特有）· `build` job compileReleaseKotlin ❌（**预存问题**：08-25 历史 run cf99f56/7269ab6 同样在 Compile check 失败——R-07"CI 全绿未经真实 push 验证"实锤，非本批引入）；本地等价命令已实测通过 |

### 7.5 批 13（2026-08-30 续作：月变八步非战斗扇出下沉——S8 步骤 3 先行）

> 范围：月变真相源切换批的前置——未下沉扇出逐件下沉（战斗三件依赖独立战斗批次，见批 12-3/12-4）。
> 批 13-1 完成步骤 3（AI 兽袭目标预计算）；后续批 13-2+ 覆盖步骤 2 教化之道偷盗判定钩子 / 步骤 4d 生育 / 步骤 6 自动排班 / 步骤 4e 关卡刷新生成接线等。

| 批 | 内容 | 验证 |
|---|---|---|
| 13-1 ✅ | **月变步骤 3：AI 兽袭目标预计算下沉**（Kotlin `AISectBeastAttackProcessor.precomputeTargets` 等价移植）：**协议扩容**——`aiSectBeastDirectTargets`（Map<String,List<String>>）/`aiSectBeastSkipCooldowns`（Map<String,Int>）/`lockedBeastIds`（Set<String>）入 GameState 顶层（Kotlin 同名 GameData 字段 @Transient 纯运行态——快照协议经 `NativeGameState` 顶层可空字段承载，非空才导出/宽松导入/镜像永不主动清空，语义同批 10-4 aiSectDisciples；DirtyTracker 零污染）；C++ `detail::precomputeTargets`（活跃妖兽筛选 type==BEAST/未击败/未过期/未锁定 + id 升序 → AI 候选距离排序 Float 精度 + `std::stable_sort` 对齐 sortedBy 稳定序 → 门控序 冷却≥绝对月/弟子池存在/存活数≥10 → 战力比较：beastPower≤0 必攻零抽取、aiPower≤beastPower 记冷却跳过、否则恰抽 1 次 EXPLORATION nextDouble（prob=min((ratio-1)×0.3+0.3, 0.9)）→ 命中 ≤2 宗门写 targets → 冷却 12 月窗口清理）；**快照语义守护**（Kotlin `val gd = state.gameData` 值快照——冷却写入不影响后续妖兽读取，值拷贝 cooldownSnapshot 对齐）；接线进 runMonthSettlement 步骤 3 位（消费方巡视楼/子事件 9 保留 Kotlin）；镜像层 @Transient 回填扩展至三字段（applySnapshot/mergeGameData/mergeGameDataChanges）；DiffMonthSettlementTest 换装真实 AISectBeastAttackProcessor（worldLevels 空场景纯早退零效果） | GTest 612/612（+3：门控+抽不中+冷却清理黄金序列/命中+必攻+qualified 上限/同宗门双妖兽快照语义快照锁）· **DiffPrecomputeTargetsTest 新建 1/1**（桌面 JNI 直调 `detail::precomputeTargets` vs Kotlin 真实处理器逐位一致——命中/战力不足记冷却/锁定排除/过期清理 + EXPLORATION 分区终态逐位一致；直调设计规避步骤 4e moveBeasts 干扰——Kotlin 对拍臂 SystemManager 未装 ExplorationSystem）· engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全模块全绿 · app compileReleaseKotlin 通过 |
| 13-2a ✅ | **月变步骤 2：教化之道偷盗判定钩子下沉**（Kotlin `CultivationSettlement.processPolicyMonthlyEffects` 事务内钩子——道德提升后仍 < 偷盗阈值 → `processSingleDiscipleTheft(id, state)` 事务内版等价移植）：**单弟子判定入口提取** `detail::judgeSingleTheftCandidate`（Kotlin canDiscipleAttemptTheft 复检当前态 + 标记判定先于概率抽取 + executeFullTheftCheck 完整链——偷盗概率/捕获/仓库守卫/成功偷窃/偷后叛逃，SYSTEM 抽取序与 Kotlin 逐位一致）；`processTheftMonthlyFallback` 单候选段重构复用（语义零变更，GTest 回归守护）；`processPolicyMonthlyEffects` 加 rng 参数 + 道德分支接入（SYSTEM 抽取内嵌弟子循环序——拆出会破坏抽取序）；接线 runMonthSettlement 步骤 2 位；**途中根因修复 FakeGameStateStore.discipleTables 共享语义**（S-14 家族测试基建：原实现每次访问从 disciplesValue 重建副本，事务内读取丢失前序写入——钩子 lastTheftJudgementYears 标记不可见 → 子事件 3 兜底误判 hasCandidate 重复判定，与 C++ 当前态行为漂移；修复：事务内返回 activeTransaction 共享表，事务外仍返回 committed 副本，对齐生产 GameStateStoreImpl 共享语义） | GTest 615/615（+3：道德 28→29 触发判定黄金序列（标记+1 抽）/道德 29→30 达阈值不触发零抽取/月上限拦截零抽取）· **DiffMonthSettlementTest 场景⑬**：moralEducation + 道德 28 弟子，跨语言逐位一致（道德 29/兜底归零/SYSTEM 恰 1 抽——钩子标记后兜底 hasCandidate 排除不重复判定；Fake 修复前 Kotlin 侧重复判定 SYSTEM 漂移实锤）· engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-2b ✅ | **月变步骤 4e：世界关卡刷新生成接线下沉**（Kotlin `WorldLevelManager.processMonthly` 等价移植——LevelGenerator 批 4-1 接线）：runMonthSettlement 步骤 4e 完整语义——shouldRefresh 判定（lastRefreshMonth==0 || 差值≥3）→ 玩家宗门门控（无 → 只清理不生成不推进，Kotlin 提前 return 分支）→ `generateWorldLevels`（maxNewLevels=6 → nextInt(6)+1 个，playerAvgRealm 存活弟子平均境界 toInt 安全兜底）→ lastRefreshMonth 推进绝对月 → 妖兽移动（刷新后统一执行，对齐 Kotlin 步骤序）；**对拍臂换装真实 ExplorationTickSystem**（WorldLevelManager/LevelGenerator 真实——EXPLORATION 分区消费与 C++ 逐位对齐；巡视/攻击检测 mock 纯早退）；**测试基建**：DiffMonthSettlementTest 拆分（LargeClass）——Kotlin 臂装配提取 `DiffMonthSettlementFixture.kt`（internal 顶层函数，语义零变更）；现有 4 场景预置 worldLevelLastRefreshMonth 当前月（不刷新零生成，专注政策/执法域）；diff 面排除 worldLevels id（Kotlin UUID vs C++ 空串，镜像生成字段）；GTest 既有 2 附庸用例预置刷新月（零抽取断言不受生成干扰） | GTest 618/618（+3：玩家宗门+lastRefreshMonth=0 生成 1~6 关卡+推进/无玩家宗门不生成不推进零消费/最近刷新不刷新零消费）· **DiffMonthSettlementTest 场景⑭**：p1 + 空 worldLevels 跨语言逐位一致（生成关卡非空 + lastRefreshMonth=14 + EXPLORATION 分区逐位一致）· engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-3 ✅ | **月变步骤 6：月度自动排班下沉**（Kotlin `ProductionProcessor.processAutoAssign` 等价移植；零 RNG 纯数据变换）：**协议修正**——`ResidenceSlot` 对齐 Kotlin 真实形状（`slotIndex` 补齐、`sectId` 误植删除——原 C++ 形状含 Kotlin 无的 sectId，对拍空槽未暴露；json_codec 同步）；**静态数据**——住所建筑表（BuildingFeature 注册表 Residence 分类子集：初级/中级单人住所 slots=1、初级/中级多人住所 slots=4，双端守卫防漂移）；C++ `detail::processAutoAssign`（11 槽占用扫描 buildOccupiedSlotDiscipleIds——长老 10 单槽+7 列表/灵矿/藏经阁/仓库驻守/巡视/宗门驻守/战斗队伍/活跃任务/秘境/洞穴活跃队伍/血炼/生产槽 → idle 池 → 住所分配 computeResidenceAssignments（单人/多人政策、候选排序 followed desc→rootCount asc→attr desc、逐空槽 key=buildingInstanceId:slotIndex）→ 四类生产候选 takeAutoAssignCandidates（灵植/灵矿/炼丹/锻造，超出空槽数回流池）→ 原子写入 applyAutoAssignments（住所/生产/灵矿槽位字段，**不写 DiscipleStatus**——Kotlin 事务内同语义，状态由 UI 派生））；接线 runMonthSettlement 步骤 6 位；**对拍策略**——场景⑮ 灵矿分配路径（不依赖 BuildingFeature 注册表（feature/game 测试环境未注册，住所分配空）与 repo 回滚面（生产槽 batchAssign 异步回写 mock 会回滚镜像））；住所/生产分配由 GTest 黄金序列守护 | GTest 621/621（+3：住所分配黄金序列（comprehension 降序+零 RNG 锁）/生产分配+池回流黄金（灵植 50 优先+灵矿 40 回流）/政策全关纯早退零写入）· **DiffMonthSettlementTest 场景⑮**：autoMineRootCounts + 2 弟子 + 1 灵矿空槽，跨语言逐位一致（mining 最高入矿 + 全结构 diff）· engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-4a ✅ | **生育前置：名字生成分区化 + C++ 等价**（Kotlin `NameService.inheritName` 原用 JVM 全局 Random——非确定性、不入 rngStates、跨语言不可对拍（S-19 同族确定性缺口）：**分区化**——`inheritName`/`generateName` 加 `rng: kotlin.random.Random` 参数（默认 `Random.Default` 保持招募/兑换码/AI/弟子服务调用点行为不变），`ChildBirthSystem.createChild` 传 SYSTEM 分区 PRNG 适配器（`rng.asKotlinRandom()`——与 DiscipleSeed.random 同源，SYSTEM 消费序对齐）；**C++ 移植**——`gamecore/system/name_service.h`（中文名数据表 compoundSurnames/male+female 双字/单字名逐项对齐 Kotlin + `inheritName`/`extractSurname` 等价，RNG 语义 nextDouble 定双字/单字 + nextInt(bound) 选名逐位一致） | **DiffNameServiceTest 新建 1/1**（3 种子 × 3 姓氏 × 2 性别 × 20 次名字逐字符一致 + existingNames 冲突规避路径（50 次尝试→兜底后缀）逐字符一致）· engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿（NameService ReturnCount 既有冻结条目转 @Suppress，baseline 移除失效条目） |
| 13-4b ✅ | **弟子创建工厂下沉**（Kotlin `DiscipleFactory.create` 等价移植——recruitDisciple/refreshRecruitList/createChild 三站点六段逻辑的 C++ 复刻）：`gamecore/system/disciple_factory.h`——WeightedRoll 分布常量（数量 0-5：35/35/20/6/3/1；品阶四档：负面30%/下品50%/中品18%/上品2%）+ **三分类同构生成**（Talent/Physique/Affix 共用模板：数量 1 次 nextDouble + 每轮品阶 1 次 nextDouble + 选池 1 次 nextInt(size)，空轮次零消费；template 去重后整组移除；Talent 池排 DEPRECATED_TALENT_TYPES（CULT_SPEED/BREAK_CHANCE/LIFESPAN/MANUAL_SLOT/WIN_GROWTH），Physique/Affix 不过滤——负面经品阶概率抽取）+ **gaussianInt**（C-12 同族：u1=(1+nextInt(10000))/10000、u2=nextInt(10001)/10000，fdlibm log/cos + std::sqrt + floor(v+0.5) 复刻 Math.round 半值向正无穷）+ 灵根数阶梯（悟性/资质 1根80-100…5根1-20；资质 avoidSentinel50 哨兵规避——与读档自愈判定一致）+ 肖像池（male 1-20/female 1-17）+ 技能（9×gaussianInt(50.5,16.5)，忠诚上限 100）+ 基础属性（Kotlin `calculateBaseStatsWithVariance`：120/60/12/12/10/8/15 × (1+方差/100) 截断——**非** realm 乘区 computeBaseStats（getBaseStats 路径语义不同））+ 寿命（talent+affix effects["lifespan"] 聚合 × realmMaxAge（境界基准寿命表，realm 9→80…0→9999））；**RNG 消费序逐位对齐**（单个 DeterministicRng 串行：14 次方差 + 2 次阶梯 + 三分类 + 1 次肖像 + 18 次技能）；**JNI 对拍通道**——`nativeCreateDisciple`（seed JSON 入 → 弟子生成结果 JSON 出，g_rng 随机源） | GTest 626/626（+5：两种子黄金序列（seed42 负面体质抽取 + seed987654321 五灵根女弟子三分类多特质/template 去重）/确定性跨实例重放/统计不变式（数量全档 0-5、技能 1-200、忠诚 ≤100、方差 ±50、资质哨兵 50 规避、悟性资质阶梯区间）/realm 寿命基准）· **DiffDiscipleFactoryTest 新建 1/1**（3 种子 × 2 性别 × 灵根 1/2/5 阶梯全分支 + realm5/realm7 lifespan 基准（realm7 同种子同参数锚定 GTest 黄金值）+ 12 种子负面池耗尽/template 去重路径；逐字段对拍：肖像/7 方差/悟性/资质/10 技能/7 基础属性/寿命/三分类 id 顺序）· engine JUnit 2941/2941（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-4c ✅ | **月变步骤 4d：生育下沉**（Kotlin `ChildBirthSystem.processMonthlyBirth` 等价移植）：`gamecore/system/child_birth.h`——**SpiritRootGenerator 等价**（灵根数权重 1/3/26/30/40% 累积分档 `rand < cumulative` + Fisher-Yates 洗牌（Kotlin shuffled：lastIndex→1 递减 j=nextInt(i+1) 交换）取前 rootCount 个逗号连接）；**createChild 等价**（SYSTEM 分区消费序：① 性别 nextInt(2) ② inheritName（name_service.h 复用，最多 50 次冲突尝试+数字后缀兜底）③ 灵根继承 nextInt(100)：0..29 父 / 30..59 母 / ≥60 SpiritRootGenerator ④ createDisciple（disciple_factory.h 复用固定序））；**processMonthlyBirth 等价**（入口快照母亲列表+discipleMap（重复 id 保留最后）→ 到期母亲（isAlive && childBirthMonth==当前月）逐人：父死分支清 childBirthMonth+partnerId 增量 update 保序 / 正常分支 existingNames **当前态**重建（含前序新生儿——Kotlin 每轮重新组装）+ 新生儿入 recruitList + autoRecruitIdle 重置 + recruit_settle::processAutoRecruit 复用 + 母亲 lastChildYear/childBirthMonth 增量 update）；**新生儿 id 为镜像生成字段**（Kotlin UUID.randomUUID 非确定性——C++ 空串占位，对拍 diff 排除 recruitList[].id，同 worldLevels 契约；autoRejectIdle 为 Kotlin 瞬态 C++ 无对应字段）；接线 runMonthSettlement 步骤 4d 位；**对拍臂装配真实 ChildBirthSystem**（fixture systemManager 注入——SYSTEM 分区消费序与 C++ kSystem 对齐；现有场景 childBirthMonth 全空早退零扰动） | GTest 630/630（+4：黄金序列（种子对齐对拍场景⑯ SYSTEM 分区 fromSeed(seed+3)+3 预热，新生儿全字段+母亲更新）/父死清孕期/无到期早退/双母按序生育（existingNames 规避跨轮）+ 名字规避）· **DiffMonthSettlementTest 场景⑯ 新建**：母亲到期（childBirthMonth=月变当前月 2）+ partner 互指 + 无自动招募 filter——新生儿入 recruitList 逐位一致（diff 排除 recruitList[].id；GTest 黄金值三方闭环锚定：Kotlin 值 == C++ 输出 == GTest 常量——父丹青/male/metal/aptitude 80/体质双件）· engine JUnit 2942/2942（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-5 ✅（战斗批次 A） | **战斗计算管线全量下沉**（Kotlin `BattleCalculator` 计算管线等价移植——`gamecore/system/battle_calculator.h`，**AI 决策层 selectSkill/selectTarget 归批次 B**）：**战斗域模型**——BuffType（25 枚举含 isDebuff）/CombatBuff/CombatSkill（含 buffs 三元组列表）/Combatant（effectivePhysicalAttack/MagicAttack/PhysicalDefense/MagicDefense/CritRate/Speed/MaxHp/MaxMp 8 个 buff 分桶计算属性 + isDead/hpPercent/mpPercent/hasControlEffect）/PhysiqueCombatFactors/AffixCombatEffects；**计算管线**——buildDamageZones（物理/魔法/增伤分桶单遍历 + 防守方减伤求和 + 体质/词条/境界三因子）/calculateCombatantDamage 全链（tryInstantKill 斩杀前置 → tryDodge 闪避 → computeDamagePipeline 暴击→波动→分桶注入→多段 Long 钳制；**RNG 消费序**：闪避 1 + 暴击 1 + 波动 1 次 nextDouble）/calculateDamage（CombatantStats 接口版）/estimateDamage（确定性估算：期望暴击 avgCritMult，无 RNG——AI 决策用）/processDotEffects+dotRealmFactor/executeSupportSkill+computeHealAmounts+buildSkillBuffs（护盾/分摊/单/多 BUFF）/updateCombatantCooldowns+updateCombatantBuffsOnly/calculateDamageShare+calculateLinkedDamage；**JNI 对拍通道**——nativeCoreBattleOp 新增 combatantDamage/estimateDamage op（Combatant/Skill JSON 解析 + DamageResult JSON 输出，g_rng 随机源） | GTest 637/637（+7：普攻/技能/斩杀/Buff+体质+词条 4 黄金序列 + estimateDamage + RNG 审计（3 抽快照锁定）+ 确定性重放）· **DiffBattleCalculatorTest 新建 1/1**（3 种子普攻 + 技能攻击 + 斩杀两分支 + Buff/体质/词条因子 + estimateDamage——同种子同消费序逐字段一致（damage/isCrit/isPhysical/isDodged/isInstantKill/hits））· engine JUnit 2947/2947（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| **评估** | **executeBattle 全流程 C++ 化评估完成**（子代理勘察报告，2026-08-30）：① executeBattle 为纯 Kotlin 全流程（BattleSystem.kt:297/301 主循环、BattleAI 双路径决策、BattleDamageApplier、BattleDescriptionGenerator），C++ battle.h 原仅 8 公式原语；② AI 兽战（S8 子事件 9）/任务完成（5）/洞天与玩家战斗 **100% 共用 executeBattle**（AISectBeastAttackProcessor.kt:257/309/339），差异仅战后后处理（aiSectDisciples 死亡/任务 MISSION 分区奖励/濒死语义）；③ **不建议现在推进全流程**——月变真相源切换批进行中 + S8 三件战斗边界场景规避 + 批 12-3/12-4 审计登记；**批次切分**：A 战斗计算管线（本批完成）→ B AI 决策层（selectSkill/selectTarget/BattleAI）→ C executeBattle 回合编排（玩家战斗为主臂对拍，diff 排除 message——BattleDescriptionGenerator 用 JVM 全局 Random 不值得 C++ 化）→ D 三件边界接线（子事件 5/6/9 + aiSectDisciples 死亡/任务奖励）；④ 风险：Buff 生命周期（C++ 无 Buff 模型——本批已建 CombatBuff）、主路径"到达即抽" vs 拉条 legacy"先判再抽+命中后 nextInt"双 RNG 模式、奖励发放留 Kotlin 调用方层（对齐批 8-4 钱包留守裁决）、宗门战第三引擎 AISectAttackManager.executeUnifiedAIBattle 共享公式防回归 |
| 13-6 ✅（战斗批次 B） | **AI 决策层全量下沉**（Kotlin `BattleAI` 600 行等价移植——`gamecore/system/battle_ai.h`，承接批次 A 计算管线）：**AIActionType（10 枚举）/AIAction（skill 副本 + targetId 承载）** + **decideAction 主决策**（8 层级联优先级 + 概率衰减：Tier1 被控检查（零消费）→ Tier2 保命（hpPercent<0.25 短路消费 1）→ Tier3 斩杀（无条件消费 1 + estimateDamage 确定性斩杀判定）→ Tier4 支援盟友（无条件消费 1）→ Tier5 团队 Buff（无条件消费 1）→ Tier6 控制（无条件消费 1）→ Tier7 AOE（3+ 敌人消费 1）→ Tier8-10 攻击决策（省蓝选最低蓝耗/最优单体伤害蓝耗比/普攻兜底））+ **selectAttackTarget**（低血量/高威胁/低防御三概率分支 + 兜底第一个存活；alive==1 零消费）+ **selectSupportTarget**（治疗/护盾选血量最低，否则双防最低）+ 私有辅助（findSelfPreservation 护盾>治愈>减伤加速 / findExecuteTarget 威胁降序稳定排序 / findAllySupport 治愈>防御 Buff / findBuffOpportunity 团队>自身>单体 / findControlAction 最高威胁未受控 / decideAttackAction）；**RNG 消费序逐位一致**（C++ `&&` 与 Kotlin `&&` 同从左到右短路——Tier2 血量阈值内才消费、Tier3-6 无条件消费、Tier7 仅 3+ 敌人消费；minByOrNull/maxByOrNull 相等取第一个 → std::min/max_element；sortedByDescending 稳定 → std::stable_sort；指针 vector 承载集合引用语义零拷贝）；**JNI 对拍通道**——nativeCoreBattleOp 新增 decideAction op（unit/allies/enemies Combatant JSON → actionType/skillName/targetId JSON，g_rng 随机源） | GTest 652/652（+15：保命/斩杀/支援/团队Buff/控制/AOE/省蓝/最优单体/普攻 9 黄金序列——含 **RNG 消费次数快照锁定**（保命 1 抽/斩杀 1 抽/支援 2 抽/团队 Buff 3 抽/控制 5 抽（Tier6 未命中走目标选择）/AOE 5 抽）+ 被控/无敌人/沉默零消费与普攻兜底 + 确定性重放 + 15 种子目标选择分支全覆盖（双敌两分支均命中））· **DiffBattleAITest 新建 15 场景**（全部决策层级分支 + playerDamageModifier 斩杀判定——actionType/skillName/targetId + **RNG 决策终态快照逐位一致**（消费序验证））· engine JUnit 2962/2962（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-7 ✅（战斗批次 C） | **executeBattle 回合编排全量下沉**（Kotlin `BattleSystem` 回合编排核心 + `BattleDamageApplier` 等价移植——`gamecore/system/battle_execution.h`，承接批次 A/B）：**回合主循环** executeBattle（超时检查（MonotonicClock 注入，对拍 -1 不检查）/速度序稳定降序/胜负判定 resolveBattleWinner/奖励 generateRewards 100×**初始** beasts 数）；**单回合** executeTurn（战斗开始时存活快照行动序（回合内击杀不影响本轮序）+ 逐参战者行动 + DoT 结算写回）；**单参战者** executeCombatantTurn 全链（判死/全灭早退 → 控制效果（眩晕/冰冻跳行动 + Buff 结算）→ 沉默禁用技能 → selectSkill（BattleAI.decideAction 批次 B 复用 + 玩家伤害倍率透传）→ executeSkillAction 四分支（普攻/友方单体支援（rng.nextInt 随机选友方）/团队支援（全体存活）/AOE（逐存活敌人）/单体技能）→ applyDamageEffects（斩杀无视护盾 hp=0——普攻透传 isInstantKill 而**技能斩杀恒 false**（Kotlin 未传参默认，走护盾吸收路径）→ 护盾吸收余量按 **value 匹配**写回 → 伤害链接/分摊（**受击方阵营判定**——分摊从受击方同阵营找）→ 单体/AOE/链接 debuff）→ 冷却写回 → 支援效果（治疗 clamp max/团队 BUFF 合并存活/拉条立即行动（resolveAdvancedAlly → BattleCalculator.selectSkill+selectTarget **旧版决策**（批次 B 补项 calcSelectSkill/calcSelectTarget——nextInt 随机选中语义）→ 行动/伤害/冷却））；**BattleDamageApplier**（applyDamageToTarget 护盾吸收+余量写回 / applySharedDamage / applyLinkedDamage 应用编排——先查 team 再查 beasts）；**RNG 消费序逐位一致**（决策层短路 + 计算管线 3 抽 + 随机选友方/拉条目标 nextInt）；**JNI 对拍通道**——nativeCoreBattleOp 新增 executeBattle op（team/beasts Combatant JSON 入 → 终态 JSON 出，新增 combatantToJson/skillToJson/buffToJson 全字段序列化 + buffTypeName 反向映射，g_rng 随机源） | GTest 659/659（+7：基础战斗黄金序列（seed42 玩家 4 回合全灭妖兽 TEAM 胜利 + rewards 200 + 终态 hp d1=145/d2=840 逐位固化）/压制胜利黄金/打满 25 回合 DRAW 边界/确定性重放（含 RNG 终态）/RNG 消费审计/支援路径状态合法）· **DiffBattleExecutionTest 新建 8 场景**（基础战斗（3 种子）/治疗+团队 Buff/控制+沉默/AOE+护盾/拉条/链接+分摊/全灭+奖励/伤害倍率——**turn/winner/rewards + 逐 Combatant 的 hp/mp/buffs/skills 冷却逐位一致**；途中根因修复：applySharedDamage 受击方阵营判定方向（分摊者从受击方阵营找））· engine JUnit 2970/2970（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 |
| 13-8 ✅（战斗批次 D-1：AI 兽战生产接线） | **子事件 9 AI 兽战接入 C++ 战斗引擎**（批 12-3/12-4 审计"三件战斗边界保持 Kotlin（随战斗批次推进）"的批次 D 第一件——BattleSystem.executeBattle 生产调用点路由）：**生产 JNI 通道**——GameCoreBridge.cpp 新增 `nativeBattleExecute`（Combatant JSON 入 → 终态 JSON 出，消费 `g_gameCore->rng().getRng(kBattle)`——AUTHORITATIVE 下委托式 RNG 单一真相源，与 Kotlin NativeBackedRng 委托同一分区序列天然一致；超时用 Android 单调时钟，生产传 -1 不检查）+ Kotlin GameCoreBridge.nativeBattleExecute external；**JSON 编解码提取共享**——`gamecore/system/battle_json.h`（combatantFromJson/skillFromJson/buffFromJson/combatantToJson/skillToJson/buffToJson 从 GameCoreJni.cpp 匿名命名空间提取，桌面对拍桥 + 生产桥双桥复用防漂移）；**Kotlin 路由层**——`BattleExecutionRouter`（engine/domain/battle）：AUTHORITATIVE + bridge 已加载 → nativeBattleExecute → 重建 BattleSystemResult（battle 终态/winner/rewards + **log 终态重建**（teamMembers/enemies isAlive/hp/mp，rounds 空——AI 兽战路径不消费回放日志；message 保持 Kotlin 调用方层）；降级契约：flag 非 AUTHORITATIVE / native 未加载 / 返回 error → null 回退 Kotlin 原实现）；**调用点改造**——AISectBeastAttackProcessor 三处（AI vs AI PvP / 胜者 vs 妖兽 / 单 AI vs 妖兽）`tryExecuteNative ?: executeBattle` 路由 | engine JUnit 2974/2974（+4：BattleExecutionRouterTest——OFF 回退 null/AUTHORITATIVE+未加载回退 null/Combatant JSON 往返无损/协议键对齐 C++ battle_json.h）· 既有 AISectBeastAttackProcessor 调用点测试环境（bridge 未加载）恒走 Kotlin 回退零回归 · NDK externalNativeBuildRelease 通过（生产桥新通道编译）· detekt 全绿 · app compileReleaseKotlin 通过；等价性由 DiffBattleExecutionTest（桌面对拍桥同源 battle_execution.h）逐位守护 |
| 13-9 ✅（战斗批次 D-2：任务完成接线 + 动作序列输出） | **子事件 5 任务完成接入 C++ 战斗引擎 + C++ 战斗动作序列（rounds）输出**：**C++ 动作层**——battle_execution.h 补记录链：TurnContext.actions（BattleActionRecord 确定性字段：type/attacker/attackerType/target/damage/damageType/isCrit/isKill/isInstantKill/skillName）+ recordTurnAction（斩杀/支援/技能 AOE 总伤/普攻分支，isKill 用攻击时目标 hp 快照——AttackResult.targetHp 新增）+ 拉条立即行动记录（buildAdvancedActionLog 等价）+ 控制记录（眩晕/冰冻）+ DoT 记录（持续伤害）+ executeTurn 逐回合打包 BattleRound（EndBattle 早退回合不推进对齐 Kotlin）+ BattleResult.rounds；Combatant 补 isBeast（attackerType 判定）+ battle_json.h 编解码；**JNI 双桥输出 rounds**（roundsToJson/actionRecordToJson 共享）；**Kotlin 重建**——BattleExecutionRouter 从 C++ rounds 重建 BattleRoundData/BattleActionData（message 为确定性摘要——原 BattleDescriptionGenerator 用 JVM 全局 Random 随机措辞，评估报告"diff 排除 message"同源决策）；**调用点改造**——MissionSystem.executeMissionBattle 两处（BEAST/HUMAN 敌人）`tryExecuteNative ?: executeBattle`；**编解码拆分**——BattleJsonCodec.kt（生产协议编解码从 Router 拆出，TooManyFunctions 真修） | engine JUnit 2974/2974（DiffBattleExecutionTest rounds 对拍扩展：**rounds 数量 + 每回合 roundNumber + 逐动作 type/attacker/attackerType/target/damage/damageType/isCrit/isKill/isInstantKill/skillName 逐位一致**——途中根因修复：拉条立即行动未记录（processTurnAdvance 补 buildAdvancedActionLog）；BattleExecutionRouterTest 协议键对齐扩展 isBeast）· NDK externalNativeBuildRelease 通过 · detekt 全绿 · app compileReleaseKotlin 通过 |
| 13-10 ✅（战斗批次 D-3：洞天 AI 操作生产接线） | **子事件 6 洞天 AI 操作接入 C++ 第三战斗引擎**（批次 D 最后一件——AISectBattleProcessor → executeSectBattle → `AISectAttackManager.executeUnifiedAIBattle` 第三引擎）：**C++ 等价移植**——`gamecore/system/sect_battle.h`（AISectAttackManager.kt 895-1474 行）：executeUnifiedAIBattle 主循环（MAX 200 回合/超时 Clock 注入/ended 判定）+ executeAiRound（超时检查 + 回合开始存活快照速度稳定序 + 逐行动 **每步后 filter 死亡列表压缩** + DoT 按 side 写回）+ executeAiCombatantTurn（控制跳过/沉默/决策复用 BattleAI + 普攻/单体技能/AOE/支援四分支）+ 行动执行（斩杀 hp=0/dodged/正常护盾吸收+debuff+链接/分摊——aiApplyLinkDebuff/aiApplyShareAndLink 先 team 后 beasts）+ 支援（ally nextInt 随机选友方/治疗 clamp/团队 BUFF/冷却）+ resolveAiWinner（防御空 ATTACKER/攻击空 DEFENDER/超时存活数）+ rounds 复用 BattleRound；**两处 Kotlin 语义深坑对拍实锤并修复**：① **支援后施放者自身新 buff 丢失**——Kotlin `updateSupportCooldown` 用 applySupportTeamBuffs **之前**的 caster 值覆盖（疾风阵给施放者加的 SPEED_BOOST 被旧值覆盖丢失——round[2] 速度序分叉根因），C++ 引用语义需显式 casterSnapshot 复刻；② **支援日志 target 用全体 allies 名连接**（Kotlin buildSupportActionLog 用 allies 参数非实际目标）——C++ 原用 supportAllies 致 ally 单目标日志漂移；**生产接线**——GameCoreBridge.cpp nativeAiBattleExecute（BATTLE 分区 RNG）+ Kotlin external + AISectAttackManager.tryExecuteUnifiedNative（AUTHORITATIVE 守卫/结果重建含 rounds/降级回退）+ executeSectBattleCore 路由；executeUnifiedAIBattle/UnifiedAIBattleResult 改 internal（对拍入口） | engine JUnit 2979/2979（+5：**DiffSectBattleTest 新建 5 场景**——基础战斗（3 种子）/支援+控制（3 种子）/AOE+护盾/链接+分摊/全灭胜负——turns/winner + 逐 Combatant hp/mp/buffs/skills 冷却 + rounds 动作序列逐位一致；RNG 终态逐位一致）· GTest 659/659（C++ 引擎零回归）· NDK externalNativeBuildRelease 通过（nativeAiBattleExecute 编译）· detekt 全绿（tryExecuteUnifiedNative rounds 重建拆分）· app compileReleaseKotlin 通过；生产路由测试环境（bridge 未加载）恒走 Kotlin 回退零回归 |

### 7.6 批 M-1：月变真相源切换（2026-09-01）

> 范围：文档 §7.3 "真相源切换（月变编排整体走 C++）待下沉面收敛后单独立批" 的正式批次。
> 下沉面判定（审计 2026-09-01）：月变八步 + 十六子事件已下沉 13 件（战斗三件 5/6/9 的战斗执行
> 已随批 13-8/13-9/13-10 生产接线 C++），未下沉扇出（生产结算 4a/4b——Room 仓储域、邮件 4g——
> 异步网络、战斗三件后处理 5/6/9）按旬结算同构模式作为 **Kotlin 残留执行器** 保留（非退役范畴）。
> 架构模式与旬结算 `PhaseSettlementExecutor.executeResidual` 完全同构。

| 项 | 内容 | 验证 |
|---|---|---|
| M-1 ✅ | **月变真相源切换**（生产月变路径 Kotlin `MonthSettlementExecutor` 八步编排 → C++ `runMonthSettlement` + Kotlin 残留执行器互插）：
  - **C++ 侧**：`MonthSettlementResult` 扩展（policyCosts + **S-17 秘境关闭草稿** SecretRealmCloseDraft（closed/memberIds/backpack/slotId——closeSecretRealmByExpiry 清空前快照）+ **S-20 购买日志草稿** PurchaseLogDraft（discipleId/itemName/age——applyPurchaseDecisions 购买点记录）；`GameCore::settleMonth()`（runMonthSettlement → JSON 信封，SecretRealmBackpack 复用协议 to_json）+ `GameCore::resetAutoRecruitIdle()`（S-16 瞬态门复位）；JNI：`nativeSettleMonth`/`nativeResetAutoRecruitIdle`
  - **Kotlin 侧**：`settleMonthNative` 管线（nativeSettleMonth → applyDirtyFromNative 增量镜像（失败全量兜底）→ `MonthSettlementResidualExecutor` 单事务（AlchemySystem/ForgeSystem/MailSystem 扇出 + 任务完成 5 + 洞天 6 + AI 兽战 9 + S-20 lifeEvents 写入 + S-17 `SecretRealmService.applyExpiryCloseDraft`）→ 返回信封）；`processMonthYearChange` 月变分支切换（native 未就绪回退 Kotlin 完整编排——C++ 状态未变更回退安全；nativeSettleMonth 后任何失败传播给外层 refund + 看门狗自愈，**不回退**（防双份结算））；事务外三件（checkpointAllProduction 按信封 disabledPolicies/missionCheck/flushPendingEvents）语义保留
  - **S-16 清偿**：`RecruitService.resetAutoRecruitIdle` 统一收口 5 个重置点（年度刷新 refreshRecruitList/sanitizeRecruitList/玩家改筛选/洞天招募/生育）→ 重置 Kotlin 惰性 + `nativeResetAutoRecruitIdle` 同步 C++（isLoaded 守卫，纯 Kotlin 回退路径静默跳过）
  - **S-14 清偿**：执法域（偷盗/叛逃）随月变编排整体入 C++ 执行，Kotlin committed 读口径差自然消灭（对拍 FakeGameStateStore 重入语义修复随批 11-4 已就位）
  - **行为基线登记**：残留执行器（生产结算 SYSTEM/任务完成 MISSION）在 C++ 全部消耗之后执行——SYSTEM/MISSION 抽取序与切换前 Kotlin 编排（生产结算在步骤 4a、任务完成在子事件 5）不同，属"月变编排整体入 C++"的必然行为基线；C++ 侧确定性由 GTest 黄金序列锁定，残留侧由委托式 NativeBackedRng 单一真相源保证；BATTLE（洞天 decideAttacks/战斗）与 AI 独立分区（AISectDiscipleManager 派生种子）不污染主序列 | engine JUnit 3028/3028 全量（桌面 JNI 0 skip，含 DiffMonthSettlementTest 全场景对拍零回归——对拍场景本就规避残留扇出）+ **GameEngineCoreMonthOpsTest 新建 9 用例**（信封解析全分支：空/非法 JSON 宽松默认/disabledPolicies/S-17 closed=true 草稿含背包快照/closed=false 与缺失为 null/purchaseLogs 逐条/损坏背包回退空背包/全信封组合）· NDK externalNativeBuildRelease 通过（新 JNI 符号编译）· detekt 全绿 · app compileReleaseKotlin 通过 · 回退契约：native 未加载恒走 Kotlin 完整编排（测试环境/降级路径零行为变化） |

**途中清理**：
- `MonthSettlementResidualExecutor` 与 `CultivationService.eventProcessor` 构造签名保持 private（detekt baseline 按构造签名匹配 LongParameterList 豁免——改可见性会失配新增违规），新增 `eventProcessorForMonthSettlement` 访问器承载批 M-1 依赖
- 信封解析宽松契约（旧 .so 无草稿段/损坏 JSON → 空默认），`GameEngineCoreMonthOpsTest` 守卫

**遗留（不属本批）**：年变编排（`YearSettlementExecutor`）仍为 Kotlin——年变 22 项下沉审计已完成（T1 11 项 + T2 11 项规模/RNG 分区/依赖域全表见 2026-09-01 子代理审计报告），批次切分：零 RNG 小件 10 项（T1-①/②/⑤/⑥/⑦/⑧ + T2-①/⑥/⑦/⑨/⑩）/中件 5 项（T1-⑨ 条件 SYSTEM、T1-⑩ 驻军轮换、T2-③ SYSTEM 稀有度、T2-④ 局部种子编排、T2-⑪ SECRET_REALM 编排）/大件 3 项（T1-③ 死亡链、T1-④ 招募生成、T2-② AI 招募）+ no-op 2 项（T2-⑤/⑧）；**executeResidual 自动丹药/突破**接线与 **S-21 孤儿入口** 评估随批 Y 收尾。

### 7.7 批 Y-1：年变零 RNG 小件下沉（2026-09-01）

> 范围：年变 22 项审计的**零 RNG 小件 10 项 + no-op 2 项**（T2-⑤/⑧ 空实现无下沉必要）。
> 每件 = Kotlin 源码逐条审计 → C++ 等价移植（year_settlement.h detail 命名空间）→
> GTest 黄金序列（手算期望）→ 现有 DiffYearSettlementTest 规避适配（场景置
> merchantLastRefreshChanceGrantYear=1 防 T1-⑥ 首次授予与 Kotlin mock 臂失配）。

| 批 | 内容 | 验证 |
|---|---|---|
| Y-1 ✅ | **年变零 RNG 小件下沉 11 件**（Kotlin 等价移植，逐件语义对齐源码）：
  - **T1-① 附庸年贡**（`VassalService.processYearlyTribute`）：suzerainSectId 非空 + income×0.5（>0 保底 1）→ 钱包扣 LOW/VassalTribute/Internal
  - **T1-② 附属宗门年贡**（`processYearlyVassalTribute`）：establishedYear/lastTributeYear 双门槛跳过、宗门缺失移除契约、按等级查表（0:20万/1:80万/2:300万/3:1000万/默认5万）+ lastTributeYear 更新 + 钱包 add 总额
  - **T1-⑤ 自动拒绝**（`RecruitService.processAutoReject`）：autoRejectIdle 惰性门（GameState 新增 autoRejectIdle 瞬态字段——autoRecruitIdle 同族不进协议）+ 灵根数 filter + 损坏条目保留 + 无匹配置惰性
  - **T1-⑥ 商人刷新机会**（`giveMerchantRefreshChanceIfDue`）：lastGrant==0 首次/差值≥30 → +1（cap 999）+ 更新授予年
  - **T1-⑦ 年度老化清理**（`processYearlyAging`）：deathYears ≤ currentYear-1 的弟子整行移除（cullDeadDisciples 列语义）
  - **T1-⑧ 招募老化+净化**（`ageRecruitList`）：age+1 + 超寿元移除 + sanitizeRecruitList 等价（isValidRecruit 损坏过滤/三级去重（id/内容/同人签名）/跨表 isSamePerson 已入宗门残留移除）
  - **T2-① AI 弟子老化**（`processSectDisciplesAging`）：非玩家宗门 age+1 + 超寿元过滤；玩家宗门不动（aiSectDisciples 键升序边界同批 10-4）
  - **T2-⑥ 联盟到期**（`checkAllianceExpiry`）：年差 ≥5 解散 + 成员宗门清 alliance 字段
  - **T2-⑦ 联盟好感过低**（`checkAllianceFavorDrop`）：player 哨兵 + 好感到期 <80 → 解散
  - **T2-⑨ 好感衰减**（`processFavorDecay`）：玩家相关 + favor>80 + 距上次交互 ≥1 年 → 减 1 保底 80 + noGiftYears+1
  - **T2-⑩ 哀悼期到期**（`processGriefExpiry`）：griefEndYears 到期 → 置 -1（哨兵列直写）
  - runYearSettlement 按 Kotlin T1/T2 相对序接线（T2 无分帧同步执行——行为基线登记：C++ 无 yearlyOpsQueue 分帧概念，T2 由"延迟 drain"变"同步执行"，语义等价（T2 全部最终执行），时序变化随年变真相源切换批统一登记） | **GTest 691/691**（+16：T1-① 三用例（扣减/无主宗与零收入早退/保底 1）、T1-②（等级查表+双门槛+缺失移除）、T1-⑤（过滤+损坏保留/惰性置位）、T1-⑥（30 年授予+cap）、T1-⑦（deathYears 阈值清理）、T1-⑧（老化+净化）、T2-①（AI 老化+玩家不动）、T2-⑥（到期解散+未到期保留）、T2-⑦（低好感解散/高好感保留）、T2-⑨（衰减+阈值+非玩家不动）、T2-⑩（哨兵到期））· **DiffYearSettlementTest 规避适配**（场景置 merchantLastRefreshChanceGrantYear=1——T1-⑥ 首次授予与 Kotlin mock 臂（merchantAndRecruitService mockSmart）失配）零回归 · engine JUnit 全量（桌面 JNI 0 skip）· NDK externalNativeBuildRelease 通过 · detekt 全绿 · app compileReleaseKotlin 通过 |

**遗留（批 Y 后续）**：
- **批 Y-1 对拍扩展**：年变 Kotlin 臂换装真实服务（VassalService/RecruitService/MerchantAndRecruitService/DiplomacyEventProcessor/FavorEventProcessor/CaveExplorationProcessor——现为 mockSmart）+ 批 Y-1 场景（有附庸/招募/联盟/好感/AI 弟子）双端逐字段对拍——与批 Y-2/Y-3 的 Kotlin 臂完整装配合并执行（一次性换装全部年变服务更高效）
- **批 Y-2 剩余**：T2-③（refreshAcquisition SYSTEM 稀有度曲线 + 商人池）、T2-④（sectTradeRefresh 局部种子编排 + 交易模板池）——商人/交易域中件，随批 Y-2 续作
- **批 Y-3**：T1-③（discipleAging 死亡链）、T1-④（refreshRecruitList SYSTEM 生成链）、T2-②（sectYearlyRecruitment AI 独立 RNG + 占领路由）

### 7.8 批 Y-2：年变中件下沉（2026-09-01，3/5 完成）

> 范围：年变 22 项审计的**中件 5 项**——T1-⑨（reflectionRelease 条件性 SYSTEM 偷盗钩子）、
> T1-⑩（驻军轮换）、T2-⑪（秘境年变刷新 SECRET_REALM 编排）已完成；T2-③（商人收购
> SYSTEM 稀有度曲线）、T2-④（宗门交易局部种子编排）续作中。

| 批 | 内容 | 验证 |
|---|---|---|
| Y-2 ✅（3/5） | **年变中件下沉**（逐件语义对齐 Kotlin 源码）：
  - **T1-⑨ 思过到期释放**（`DiscipleLifecycleProcessor.processReflectionRelease`）：statusData.reflectionEndYear <= year → IDLE + 清思过字段 + 道德/忠诚 +5（cap 200/100）；释放后道德 < 阈值（30）→ **单弟子偷盗判定**（复用月变 `judgeSingleTheftCandidate`——year_settlement.h include month_settlement.h，detail 同名命名空间共享；SYSTEM 条件性抽取序逐位一致）
  - **T1-⑩ 占领宗门驻军轮换**（`AISectGarrisonManager.rotateGarrisonSlots`）：玩家在场 + AI 占领宗门（occupierSectId 非空非玩家）→ 每占领者存活弟子 realm 升序稳定排序，前 10 留守、第 11 名起填 GARRISON_SLOT_COUNT(10) 槽；realmName（level_generator.h）+ 灵根数颜色 countColor（新移植 1..5 固定色）；分组顺序 std::map 键升序与 Kotlin groupBy 插入序结果等价（各占领者独立候选池）
  - **T2-⑪ 远古秘境年变刷新**（`SecretRealmService.processYearlySpawn`）：未现世 + 冷却满（coerceAtLeast 0 → year-diff >= 50）→ SECRET_REALM 分区：findSecretRealmPosition（≤100×2 nextInt + 兜底扫描零 RNG）+ 1×nextInt(变体 3)；SecretRealmState.id 为镜像生成字段（Kotlin UUID，C++ 空串占位） | **GTest 700/700**（+9：T1-⑨ 三用例（释放+加成/道德忠诚 cap/低道德触发偷盗——SYSTEM 快照变化断言）、T1-⑩ 三用例（12 弟子轮换黄金序列——realm 升序第 11/12 名 d8/d12 逐位固化/无玩家宗门恒等/无占领宗门恒等）、T2-⑪ 三用例（生成/冷却未满零抽取/已现世零抽取））· **engine JUnit 3057/3057 全量（新桥强制重跑，DiffYearSettlementTest 场景规避批 Y-2 路径（无 REFLECTING/无占领/冷却未满）零回归）** · NDK externalNativeBuildRelease 通过 · 桌面对拍桥编译通过 · detekt 无 Kotlin 改动 |

**遗留**：T2-③（refreshMerchantAcquisition——buildMerchantItemPools 池构建 + selectRarity 稀有度曲线（rarity_progression.h）+ createMerchantItem（价格波动 sect_trade.h）+ mergeMerchantItems 合并）、T2-④（refreshAllSectTrades——局部种子 sectId.hashCode()+year + generateSectTradeItems 模板池实例化）——商人/交易域续作；**Kotlin 臂完整换装对拍**（批 Y-1/Y-2/Y-3 全部下沉后）随批 Y-switch 合并。

### 7.9 批 Y-switch：年变真相源切换（2026-09-01）

> 范围：目标③主线——生产年变路径从 Kotlin `YearSettlementExecutor` 编排切换为
> **C++ `runYearSettlement` + Kotlin 残留执行器互插**（与月变切换批 M-1 完全同构）。
> 切换不依赖 T2-③/④ 下沉（残留模式）——未下沉扇出（T1-③ 死亡链 / T1-④ 招募生成 /
> T2-② AI 招募 / T2-③ 商人收购 / T2-④ 交易刷新）作为 Kotlin 残留执行器保留。

| 批 | 内容 | 验证 |
|---|---|---|
| Y-switch ✅ | **年变真相源切换**（生产年变路径 C++ `runYearSettlement` + Kotlin 残留执行器互插）：
  - **C++ 侧**：`GameCore::settleYear()`（runYearSettlement——T1 已下沉面（①/②/⑤/⑥/⑦/⑧/⑨/⑩）+ 年报快照 + annual* 清零 + 年俸 + T2 已下沉面（①/⑥/⑦/⑨/⑩/⑪）→ 空信封（年变残留无 C++ 草稿——死亡链 DAO 清理/招募生成/AI 招募/商人收购/交易刷新均为 Kotlin 纯状态 + 平台效应））；JNI `nativeSettleYear`
  - **Kotlin 侧**：`settleYearNative` 管线（nativeSettleYear → applyDirtyFromNative 增量镜像（失败全量兜底）→ `YearSettlementResidualExecutor` 单事务（T1-③ 死亡链 + T1-④ 招募生成 + T2-② AI 招募（差值判据）+ T2-③ 商人收购 + T2-④ 交易刷新））；`processMonthYearChange` 年变分支切换（native 未就绪回退 Kotlin 完整编排——C++ 状态未变更回退安全；nativeSettleYear 后失败传播看门狗自愈不回退）
  - **RNG 行为基线登记**：C++ 已下沉年变面零 SYSTEM 消耗（T1 各件零 RNG、T2-⑪ 为 SECRET_REALM 分区）——残留执行器（T1-④ SYSTEM / T2-③ SYSTEM）消耗序与 Kotlin 原编排基本一致；唯一差异：T1-⑨（C++ 条件性 SYSTEM 偷盗钩子）先于 T1-④（残留）执行——SYSTEM 序 ⑨→④→③ vs 原序 ④→⑨→③，属年变编排整体入 C++ 的必然行为基线（同月变切换登记） | engine JUnit 3057/3057 全量（新桥强制重跑——生产切换在测试环境恒回退 Kotlin（GameCoreBridge 库未加载），零回归）· NDK externalNativeBuildRelease 通过（nativeSettleYear 编译）· detekt 全绿 · 桌面对拍桥编译通过 · app compileReleaseKotlin 通过 |

**目标③完成状态**：年变真相源切换达成（C++ runYearSettlement 为生产真相源 + Kotlin 残留执行器互插）；**批 Y-3（2026-09-01）**：T1-⑪ autoBuy 接线 + T1-④ 招募刷新下沉（SYSTEM 生成链 + 名字生成分区化 S-19 同族清偿）+ **T1-③ 弟子老化死亡链下沉**（`detail::processDiscipleAgingStep`——老化判定（age+1/5 岁回正/computeMaxAge）/逐死者状态面（11 槽清理（SlotCleanupInput 适配）/哀悼期传播+丧亲草稿/道侣师徒解绑/血炼清理/袋物品草稿/装备功法清除/死亡记录/事件/年死亡计数）/统一移除/活弟子老化；**平台效应草稿**（YearSettlementDraft：agedDeaths——袋物品物化回仓库含溢出邮件/DAO 清理/DeathEvent/死亡记录档案 + bereavements——lifeEvents 丧亲事件）经 nativeSettleYear 信封回传 Kotlin 残留执行器（事务内物化/丧亲/死亡档案 + 事务外 DAO/DeathEvent，与 Kotlin processDiscipleAging 事务外段一致）；残留执行器同步移除 T1-④ Kotlin 调用（防双份招募生成）；DiffYearSettlementTest 换装真实 DiscipleLifecycleProcessor（T1-③ 老化 age+1 双端一致））；**T1 11/11 全部下沉**；GTest 708/708（+4 T1-③ 黄金序列）+ engine JUnit 全量（桌面 JNI 0 skip）+ NDK + detekt + app compileReleaseKotlin。

**批 Y-4a/Y-4b（T2-④/③ 下沉，2026-09 续作）**：
- **T2-④ 交易刷新下沉**（`detail::generateSectTradeItems`/`refreshAllSectTrades`/`shouldRefreshSectTrade`——Kotlin DiplomacyService 等价移植）：7 类型模板池生成（equipment/manual/pill/material/herb/seed/spiritStone——按品阶选池 1×nextInt + 价格波动 1×nextDouble + 库存 1×nextInt）、**局部种子 RNG**（sectId.hashCode()+year，零分区消耗）、20 条目/名去重/60 次尝试/品阶降序稳定排序；差值判据（≥3 年或列表空兜底）+ 玩家宗门/无详情跳过；**丹药 price 数据补全（批 Y-4 前置）**：recipe_db.h `PillRecipeTemplate.price`（pillBasePrice × gradeMultiplier ×（双属性 1.2）——Kotlin ItemDatabase.PillTemplate.price 逐值）；接线 runYearSettlement T2 #13；残留执行器移除 refreshAllSectTrades；GTest +5
- **T2-③ 商人收购下沉**（`detail::buildMerchantItemPools`/`createMerchantItem`/`mergeMerchantItems`/`refreshMerchantAcquisition`——Kotlin MerchantAndRecruitService 等价移植）：六大类池 + 中品/上品灵石（RATIO）；SYSTEM 分区（数量 1×nextInt(9) + 每 item 品阶 1×nextDouble + 选池 1×nextInt + 库存 1×nextInt + 丹药 grade 1×nextDouble + 价格 1×nextDouble——消费序逐位一致）；**S-22 部分清偿**（收购价格收敛 SYSTEM 分区：createMerchantItem 加 random 参数，收购传 rng.asKotlinRandom()；旅行商人保持默认 Random.Default——剩余面登记）；merge 加权平均价保持首次出现序（LinkedHashMap 语义，禁 std::map 字典序）；协议补 merchantAcquisitionLastRefreshYear（models.h + json_codec）；接线 runYearSettlement T2 #12（#13 前）；残留执行器移除 refreshMerchantAcquisition；GTest +4
- **验证**：GTest 717/717 · **DiffYearSettlementTest 换装真实 MerchantAndRecruitService**（收购流对拍主体——C++ 每年执行收购 vs Kotlin 臂 flushYearlyOpsQueue forceDrain T2 组；ManualDatabase 从 /templates/manual_db_sample.json 快照注入真实功法表（与 C++ manual_db.h 同源同序）；收购 items 全字段（含 price/grade/quantity）逐位一致 + SYSTEM RNG 终态逐位一致；diff 面排除 MerchantItem.id/itemId 镜像生成字段）· engine JUnit 全量 · NDK · detekt · app compileReleaseKotlin
- **行为基线登记**：年变 SYSTEM 消耗从"零"变为"T2-③ 收购每年消耗"（C++ GTest YearChangeConsumesOnlySystemPartition 重命名守护；年变对拍 RNG 断言同步更新）
- **~~剩余~~ ✅（收尾批 2026-09-02 完成）**：~~T2-② AI 宗门招募~~（✅ 批 Y-4c）；**年变对拍 Kotlin 臂换装真实 DiplomacyService**（✅ 收尾批——外交簇全真实 + 新增 T2-④ 交易刷新首次整链对拍场景，途中实锤修复 C++ manual RNG 消费序 + pill 池序两处不等价，见头部快速恢复点）；~~CHANGELOG 条目 + 最终验证提交~~（✅ 随版本 4.01.12，commit 11961daa）

**批 Y-4c（T2-② AI 宗门招募下沉，2026-09 续作）**：
- **AI 宗门周期性招募下沉**（新建 `system/ai_sect_recruit.h`——Kotlin AISectDiscipleManager + CaveExplorationProcessor.processSectDisciplesYearlyRecruitment + runSectRecruitmentIfDue 等价移植）：
  - **AI 独立分区 RNG 接入 GameCore**（`aiRng_` 成员——种子 systemSeed + AI_SECT.id(6)×31337，initialize/importStateJson 双点播种对齐 Kotlin initForSlot；不入 rngStates 分区）
  - **generateRandomAiDisciple**（消费序逐位对齐：gender 1×nextInt → 名字 XIANXIA（姓氏 1×nextInt + 双字判定 1×nextDouble + 选名 1×nextInt 冲突循环）→ 灵根（1×nextDouble + Fisher-Yates 4×nextInt）→ 悟性/资质 各 1×nextInt → 7×nextGaussian（14×nextDouble——AI 版 nextDouble 流非玩家 gaussianInt nextInt 流）→ 三分类（复用 disciple_factory WeightedRoll）→ 肖像 1×nextInt → 年龄 1×nextInt → 技能 9×nextGaussian（18×nextDouble）→ baseStats/lifespan 纯计算）
  - **applyGearToAiDisciple**（装备/功法按宗门等级数量 + 境界上限品阶——槽位/攻防池洗牌用 JavaRandomCompat 复刻 java.util.Random 48 位 LCG（种子 1×nextInt），心法恒带 1 本 1×nextInt；孕养 0 级 0 进度）
  - **占领路由**（玩家占领 → recruitList 拼接；其他宗门占领 → 占领者池 truncateToLimit(1000)；否则自身池——战力降序稳定截断）+ 尾部重置惰性门 + recruit_settle::processAutoRecruit（零 RNG）
  - 接线 runYearSettlement T2 #10（#4 老化后、#12 收购前）；残留执行器移除 processSectDisciplesYearlyRecruitment——**年变残留执行器扇出清零**（仅剩 T1-③ 死亡链平台效应）
- **S-19 同族清偿（AI 名字非托管 RNG）**：AISectDiscipleManager.generateRandomDisciple 的 NameService.generateName 原未传 rng（JVM 全局 Random）——传 `rng.asKotlinRandom()`（AI 独立分区，名字序列存档可重放、跨语言可对拍）
- **S-22 完全清偿**：旅行商人路径（refreshTravelingMerchant 常规 + addGuaranteedTopRarityItem 保底）价格波动同步收敛 SYSTEM 分区——createMerchantItem 无生产调用方使用默认 Random.Default
- **途中修复**：redeem_code.h 与 disciple_factory.h 独立复刻的 avoidSentinel50 同签重名（ai_sect_recruit.h 首次同时引入两文件触发重定义）——redeem_code.h 删除重复定义并 include disciple_factory.h（权威实现单源）
- **验证**：GTest 722/722（+5：差值触发/跳过/玩家占领路由/占领者路由/确定性重放）· **DiffYearSettlementTest 新增 AI 招募对拍场景**（gameYear 3→4 差值触发——Kotlin 臂换装真实 CaveExplorationProcessor（Provider 断环装配）+ AISectDiscipleManager.initForSlot(0) + GameData.aiSectDisciples @Transient 顶层回填；aiSectDisciples 弟子全字段逐位一致（名字/性别/灵根/属性/技能/装备/功法），diff 面排除 AI 弟子 id 镜像生成字段）· engine JUnit 全量 · NDK · detekt · app compileReleaseKotlin

## 8. 存量问题清理清单（S 系列，迁移全程途中发现）

> 来源：迁移架构报告与子代理深潜途中发现（死代码/过时文档/设计缺口），统一登记并分配清理时机，防止遗漏。
> 原则：死代码清理必须是低风险最小修改（删除前确认无引用 + 全量测试守护）；文档勘误不阻塞阶段，可随时执行。

| # | 问题 | 位置 | 类型 | 清理时机 |
|---|---|---|---|---|
| ~~S-01~~ ✅ | **死代码 `GameEngineCore.tick()` 已删除**（连同仅其使用的 `TICK_WARNING_THRESHOLD_MS` 常量；删除前确认全仓库无调用点） | `GameEngineCore.kt` | 死代码 | 完成（阶段 1 前置） |
| ~~S-02~~ ✅ | **过时文档 `UnifiedGameState` 已修正**：KDoc 与 docs/architecture.md 改为"逐字段流 + 三层派生流"现状描述 | `GameEngineCore.kt` + `docs/architecture.md` | 文档过时 | 完成 |
| ~~S-03~~ ✅ | **地图渲染文档勘误完成**：`docs/map-rendering-architecture.md` 两处"每帧更新"改为 RenderFrame 帧率门控推送（与 SectMapViewport 实现一致） | `docs/map-rendering-architecture.md` | 文档滞后 | 完成 |
| ~~S-04~~ ✅ | **SavePipeline 旧名注释已清理**：SaveStorage.kt KDoc + StorageSystemBenchmark.kt 三处输出文案改为现行组件名 | `core/domain/.../repository/SaveStorage.kt` 等 | 注释过时 | 完成 |
| ~~S-05~~ ✅ | **RNG 读档恢复已接线**（并入 C-13，见 §5.4） | `game_core.cpp` | 功能缺口 | 完成（计划 v2 阶段 1） |
| ~~S-06~~ ✅ | **exportDirty 变更集已实现**（C++ DirtyTracker + Kotlin applyDirty，见 §5.1/§7 阶段 1） | `GameCoreBridge` / `game_core.h` | 功能缺口 | 完成（计划 v2 阶段 1） |
| ~~S-07~~ ✅ | **设计限制 `DomainLog` 无 logger getter**：`setLogger` 后无法恢复旧 logger（基准测试需行为等价替代） | `core/domain/.../util/DomainLog.kt` | 设计改进（低优先） | ✅ **已清偿（收尾批，2026-09-02）**：`setLogger` 改为**返回旧实现**（保存-恢复能力，KDoc 更新）——NativeBenchmarkTest/Phase0SettlementBenchmarkTest 改 `val original = setLogger(SilentLogger)` + finally `setLogger(original)`（删除孤儿 PrintLogger 定义，原硬编码恢复语义修正为恢复真原值）；新建 `DomainLogTest` 3 用例（setLogger 返回旧实现/日志路由/恢复后不再路由——恢复能力守护） |
| ~~S-08~~ ✅ | **NDK 编译验证已通过**：2026-08-27 `externalNativeBuildRelease` 成功（阶段 2 新增 JNI 入口 + 阶段 4 新增 6 系统头文件/模型在 NDK 工具链下编译通过） | `GameCoreBridge.cpp` + `GameCoreBridge.kt` | 验证缺口 | 完成（计划 v2 阶段 4） |
| ~~S-09~~ ✅ | **对拍测试隔离缺口已修复**：JUnit 对拍测试中 C++ `nativeCoreInit` 幂等复用引擎单例（阶段 1 既有设计），`EngineLoop.tickCount/speed/累积` 跨用例残留，与 Kotlin 侧每用例 `new GameTimeClock` 的干净基准不对称——首轮 DiffEngineLoopTest 8/15 失败（tickTotal 残留 65、speed 残留致 catch-up cap 3→6 等）。根因修复：`EngineLoop::resetForTest()`（tick 计数/速度/累积/帧状态/活跃基准全清，生产路径不调用——与 Kotlin 单例语义一致）+ 桌面对拍桥 `nativeCoreLoopReset` + GTest 2 用例守护；另修测试自身 2 处（死区消费缺暂停帧刷新帧基准、2x 断言算术错） | `engine_loop.h` + `GameCoreJni.cpp` + `DiffEngineLoopTest.kt` | 测试基建缺口 | 完成（计划 v2 阶段 5） |
| ~~S-10~~ ✅ | **C++ 库存容量常量硬编码**：`kWarehouseBaseCapacity=50`/`kWarehouseCapacityPerBuilding=75`（`gamecore/include/gamecore/system/inventory.h`），Kotlin 读 `gameConfigProvider.warehouse.*`——config 改动时双端漂移 | `inventory.h` | 配置单源缺口 | ✅ **已清偿（批 12-5）**：新增 `core/game_config.h` 全局 GameConfig + JNI `nativeSetGameConfig` 注入通道（Kotlin GameConfigNativeBridge 双点幂等），`computeMaxSlots` 改消费注入配置（默认值兜底 = game_config.json 值） |
| ~~S-11~~ ✅ | **空白名校验差异**：C++ `validateStackableItem` 用 `name.empty()`，Kotlin `isBlank()` 拒绝纯空白名 | `inventory.h` | 语义差异（低风险） | ✅ **已清偿（批 12-5）**：`validateStackableItem` 改 isBlankString 语义（空串或全空白拒绝，与 Kotlin isBlank 一致） |
| ~~S-13~~ ✅ | **执法堂配置常量 C++ 硬编码默认值**：批 10-2 的 kLaw* 常量取 GameConfig.LawEnforcementConfig 默认值，Kotlin 读远程配置可空覆盖——远程配置改动时双端漂移 | `month_settlement.h` | 配置单源缺口（S-10 同族） | ✅ **已清偿（批 12-5）**：kLaw* 常量改 getter 消费注入配置（gameConfig()，默认值兜底 = game_config.json 值）；const val 类（THEFT_REALM_BASE_AMOUNTS 等）保持编译期 |
| ~~S-12~~ ✅ | **转发辅助入口 NPE 语义**：`GameEngineNativeOps.tryExecuteNative` 的 Kotlin 非空参数 `stateSyncService` 的内在 null 检查在函数入口即触发（早于 flag/isLoaded 早退）——生产恒非空无影响，测试 mock（未 stub `stateSyncServiceRef`）返回 null 必触；InventoryNativeForward.tryForward 已先行空过滤 | `GameEngineNativeOps.kt` | 降级契约缺口（测试场景） | ✅ **已清偿（批 12-5）**：`tryExecuteNative` 参数改可空 + 内部守卫（`stateSyncService?.syncFromNative()`），镜像服务缺失时正常降级不 NPE |
| S-14 | **月变执法域 committed 读口径差**：生产月变（真实 `GameStateStore`）中执法域经嵌套 `stateStore.update` 读 `gameData.value`/`discipleTables`/`disciples.value` 为**事务前已提交快照**（偷盗域：spiritStones/theftJudgementsThisMonth/annualTheftCount/sectPolicies/elderSlots/placedBuildings/warehouseGarrisons/组装弟子；叛逃域同），写经重入进外层事务 buffer；C++ 移植与对拍基线（`FakeGameStateStore` 每次嵌套 update 独立 fork 且**立即持久化**、外层提交覆盖写）均为当前态口径——对拍场景以保护期/未触发规避分歧面；生产真实 store 与 C++ 在"政策月费改变灵石后再判定"等场景存在读数口径差（对拍 Fake 不可达） | `month_settlement.h` / `LawEnforcementProcessor.kt` | 语义口径差（对拍不可达面，S-10/S-13 同族） | ✅ **已清偿（批 M-1，2026-09-01）**：月变真相源切换后执法域（偷盗/叛逃）随月变编排整体由 C++ `runMonthSettlement` 执行（C++ 当前读口径成为唯一口径），Kotlin committed 读随编排退役自然消灭；批 11-4 的 FakeGameStateStore 重入语义修复（对拍基建）同步就位。**批 11-4 部分清偿**：`FakeGameStateStore` 对齐生产 ReentrantLock 重入语义（嵌套 update 复用最外层事务 buffer，内层写入进入外层事务，最外层统一提交）——12 月 autoBuy 对拍库存集合与年度 by-source 纳入 diff 面逐位一致；执法域 committed 读口径差（生产 store 特有）已随批 M-1 切换消灭 |
| S-15 | **C++ `aiSectDisciples` 反向增量回导缺口**（批 10-4 协议扩容引入）：`GameData.aiSectDisciples` @Transient → 反向脏信封 `changed.gameData`（经 `GameData.serializer` 全量）不携带其变更，生产 AUTHORITATIVE 下 C++ 侧 AI 弟子池随 Kotlin 招募/战斗更新而陈旧。当前无功能影响：生产 `coreMode` 不触发 `runMonthSettlement`（C++ 不消费该字段）+ 镜像经 `NativeGameState.aiSectDisciples` 可空语义回写（非 null 才覆盖，Kotlin 权威保持）。 | `StateSyncService.kt` / `game_core.cpp` | 同步通道缺口（协议层） | ✅ **已清偿（批 10-5）**：反向信封新增独立 `changed["aiSectDisciples"]` 全量段（`lastAiSectDisciplesSent` 变化检测缓存——importToNative 全量导入后对齐、发送成功后对齐，AI 招募/战斗才重发）+ C++ `applyReverseDirty` 段应用（整体替换语义）+ GTest/JUnit 守护 |
| S-16 | **招募惰性门跨层同步缺口**（批 11-1 引入）：`autoRecruitIdle` 为 GameState 瞬态字段（不进 JSON 协议，读档即 false），重置点（年度招募列表刷新/玩家改筛选/生育/净化）仍在 Kotlin 侧——月变真相源切换前 C++ 侧惰性只进不出（Kotlin 重置不回传）；对拍场景双侧显式复位规避 | `recruit_settlement.h` / `models.h` | 同步通道缺口（纯内存态） | ✅ **已清偿（批 M-1，2026-09-01）**：`RecruitService.resetAutoRecruitIdle` 统一收口 5 个重置点（refreshRecruitList/sanitizeRecruitList/玩家改筛选/洞天招募/生育）→ Kotlin 惰性复位 + `GameCoreBridge.nativeResetAutoRecruitIdle` 同步 C++ `GameState.autoRecruitIdle`（isLoaded 守卫——纯 Kotlin 回退路径静默跳过；C++ 生育已内置惰性重置，月变切换后 ChildBirthSystem 不再执行不产生缺口） |
| S-17 | **秘境到期关闭邮件/gate 边界**（批 11-2 登记）：`closeSecretRealmByExpiry` 的关闭邮件（buildExpiryCloseMail + sendDirectMail 异步落库）与 `assignmentGate.release`（纯内存注册表）保留 Kotlin——背包物品走邮件不回宗门仓库，C++ 侧只做状态段；对拍场景背包物品为空或仅灵石规避 | `secret_realm_settlement.h` / `SecretRealmService.kt` | 平台效应边界 | ✅ **已清偿（批 M-1，2026-09-01）**：C++ `closeSecretRealmByExpiry` 关闭时记录 **S-17 草稿**（SecretRealmCloseDraft：memberIds + 背包清空前快照）→ `nativeSettleMonth` 信封回传 → Kotlin `SecretRealmService.applyExpiryCloseDraft`（复用 buildExpiryCloseMail 重建关闭邮件 + sendDirectMail + gate release）——月变真相源切换后邮件/gate 行为与切换前一致（防背包物品丢失） |
| S-18 | **商人转换回退分支随机源差异**（批 11-3 登记）：MerchantItemConverter 未知物品回退 Kotlin 用 JVM 全局 Random（非确定性、非分区），C++ 用 SYSTEM 分区（确定性）——回退分支仅在物品名无对应模板（损坏数据）时触达；另溢出邮件草稿 C++ 月结上下文丢弃（Kotlin 真相源发送），对拍场景仓库容量充足规避 | `merchant_settlement.h` | 语义差异（低风险，仅损坏数据触达） | ✅ **已清偿（批 11-4）**：C++ 回退改物品名稳定散列选池（FNV-1a）——零分区 RNG 消耗（损坏数据触达不污染确定性流），跨语言内容本就无法对齐（Kotlin 每次进程不同），C++ 侧确定性自洽；剩余面 = 溢出邮件草稿通道（随月变真相源切换接线，S-17 同族） |
| S-19 | **任务域非托管 RNG**（批 11-3 对拍途中登记）：`MissionSystem.rng` 为 nanoTime 播种的单例（非 GameRngManager 分区、不入 rngStates）——任务刷新（S8#14）与任务完成奖励（S8#5）内容跨语言不可对拍；C++ 侧无 availableMissions 协议字段（diff 面天然排除），12 月 autoBuy 对拍排除该字段 | `MissionSystem.kt` | 确定性缺口（预存设计债，任务批次边界） | ✅ **已清偿（批 11-4）**：`RngPartition` 新增 `MISSION(8)`（Kotlin + C++ `kMission` 双端，initSystemSeed seed+8；旧档 rngStates 缺 8 号键 → restore 跳过按种子播种，向前兼容）；`MissionSystem` 收敛于 `GameRngManager.getRng(MISSION)`——生产经 `CultivationEventProcessor`（@Singleton 月变/任务编排中枢）构造幂等注入，测试须显式注入固定种子实例（拒绝静默非确定性降级）；任务随机序列存档可重放。对拍侧：12 月 rngStates 8 号键特判（C++ 任务逻辑未下沉不消费，任务批次下沉后双端消费对齐移除特判——**批 12-2 已移除**，availableMissions 内容双端一致参与对拍，仅 Mission.id 镜像生成排除） |
| S-20 | **购买日志 lifeEvents 跨层边界**（批 12-1 登记）：弟子购买日志（"X岁：购买了Y"）写入 Kotlin `DiscipleTables.lifeEvents`——该字段为 Kotlin 类体属性（@Ignore 非序列化、不进快照协议，models.h 注释同源），C++ 侧无对应列，购买子事件下沉后该日志不更新（玩家弟子履历缺购买记录）；对拍 diff 面天然排除（协议外字段） | `disciple_purchase.h` / `Disciple.kt` | 平台效应边界（纯内存态） | ✅ **已清偿（批 M-1，2026-09-01）**：C++ `applyPurchaseDecisions` 购买点记录 **S-20 草稿**（PurchaseLogDraft：discipleId/itemName/age）→ `nativeSettleMonth` 信封回传 → Kotlin `MonthSettlementResidualExecutor` 写 lifeEvents 瞬态列（"${age}岁：购买了${itemName}"，与原 executePurchase 日志逐条一致） |
| ~~S-21~~ ✅ | **孤儿读档/恢复入口 `GameEngineCore.loadSnapshot` 与 `GameEngine.loadFromSave` 生产无调用方**（2026-09 云读档被本地档覆盖根因排查途中确认）：两条链均会整体替换 Kotlin 状态（`stateStore.loadFromSnapshot`），其中 `loadSnapshot` 自带 `loadNativeBaseline`（安全但无人用），`loadFromSave`（→ `saveFacade.loadFromSave` → `saveService.loadFromSave`）缺 native 基线导入——若未来接入调用方会复现"读档后 native 残留旧档反向覆盖"（与云读档 bug 同根因）。另知识库 `getEffectiveCultivation` 亦为无生产调用方入口（仅测试引用），可一并评估 | `GameEngineCore.kt:1919` / `GameEngineSaveOps.kt:33` / `SaveService.kt:121` | 死代码（孤儿 API） | ✅ **已清偿（收尾批，2026-09-02）**：删除入口——`GameEngineCore.loadSnapshot`（1978-1995）+ 同批审计发现的兄弟孤儿 `createSnapshot`（1960-1976，全仓库零调用方含测试）+ `GameEngine.loadFromSave` 扩展（GameEngineSaveOps.kt）→ `SaveFacade`/`SaveFacadeImpl`/`SaveService.loadFromSave` 全链 + `GameEngineCoordinationTest` 守卫用例（loadFromSave 链唯一测试依赖）；删除前全仓库引用确认（含 C++/脚本/文档——仅历史 CHANGELOG 与本文档引用）。**getEffectiveCultivation 勘误（保留）**：S-21 原描述"无生产调用方（仅测试引用）"**错误**——源码级调用点存在（CultivationService.accumulateCultivationPerPhase 投影块，CultivationService 为活代码 @Singleton 注入点 20+）；准确表述 = 批 9-2 后 Kotlin 运行时无执行驱动（唯一驱动 PhaseSettlementExecutor.execute 完整版已退役至对拍/回归基准），生产修炼累积/投影由 C++ cultivation.h 承担——该入口作为 checkpoint 投影契约 + C++ 修炼对拍 Kotlin 臂保留（只删读侧会复现"checkpoint 只写不读"埋雷）。同步勘误 docs/knowledge-base.md:234 |
| ~~S-22~~ ✅ | **商人 `createMerchantItem` 价格波动非托管 RNG**（批 Y-4b 对拍途中登记）：`GameUtils.applyPriceFluctuation(adjustedPrice)` 未传 rng → JVM 全局 Random（非分区、不入 rngStates）——收购/旅行商人价格生成期随机（结果落库后固定，对存档确定性无影响），但跨语言 SYSTEM 流分叉（C++ 若消费分区价格则每 item 多 1 次 nextDouble，RNG 终态永不对齐） | `MerchantAndRecruitService.kt` | 确定性缺口（S-19 同族） | ✅ **已清偿（批 Y-4b + Y-4c 收尾）**：`createMerchantItem` 加 `random: kotlin.random.Random = Random.Default` 参数——**收购路径**（refreshMerchantAcquisition，年变 T2-③ 已下沉 C++）传 `rng.asKotlinRandom()`（SYSTEM 分区确定性，跨语言逐位可对拍）；**旅行商人路径**（refreshTravelingMerchant 常规条目 + addGuaranteedTopRarityItem 保底条目）批 Y-4c 同步传 `rng.asKotlinRandom()`——价格波动全量收敛 SYSTEM 分区（存档可重放），无生产调用方使用默认 Random.Default |
| ~~S-23~~ ✅ | **孤儿类 `SaveLoadCoordinator`**（收尾批 S-21 全仓库审计途中发现）：`domain/save/SaveLoadCoordinator.kt` 全类（194 行）在 main 无任何注入/调用点（仅 StorageSystemBenchmark 文案提及）；读档真实入口收敛于 loadData 三入口（GameEngineCoordination.kt） | `domain/save/SaveLoadCoordinator.kt` | 死代码（孤儿类） | ✅ **已清偿（收尾批 2026-09-02）**：删除全类文件（构造依赖仅 IoDispatcher——活；删除前全仓库引用确认：main 零注入/调用点、仅 detekt-baseline 5 条豁免 + StorageSystemBenchmark 打印文案）；detekt-baseline.xml 移除 5 条对应豁免（InvalidPackageDeclaration/NestedBlockDepth/TooGenericExceptionCaught/UnusedPrivateProperty×2）；StorageSystemBenchmark 设计阈值文案去孤儿类名引用（数值保留为历史设计参考）；无测试依赖 |
| ~~S-24~~ ✅ | **`realtimeCultivation` 投影链疑似停摆**（收尾批 S-21 审计途中发现）：`CultivationService.accumulate → GameEngine.realtimeCultivation → DiscipleFacadeImpl → GameViewModel` 全仓库**无 UI collect 调用点**；叠加批 9-2 后 Kotlin 写侧停摆（修炼累积由 C++ 承担）+ StateSyncService 同步协议不含该字段 → 该 StateFlow 生产值恒为空/陈旧 | `CultivationService.kt` / `GameEngine.kt:305` / `DiscipleFacadeImpl.kt` | 疑似死链（需 UI 数据源复核） | ✅ **已清偿（收尾批 2026-09-02）**：UI 数据源复核确认——弟子修为显示走 `GameViewModel.discipleAggregates`（native 镜像派生，DiscipleAggregate.cultivation），realtimeCultivation 全仓库零 collect（含 feature/game 仅转发定义）→ 死链成立删除：写侧（HighFrequencyData.realtimeCultivation 字段 + accumulateCultivationPerPhase 投影块 + pendingRealtime 参数 + flushRealtimeCultivation）+ 驱动侧（PhaseSettlementExecutor.executeCultivationBatch P-6 段）+ 读侧（GameEngine/DiscipleFacade/DiscipleFacadeImpl/GameViewModel 转发）+ RealtimeCultivationBatchTest（P-6 专门测试删除）+ 注释/KDoc 引用更新（CheckpointCallSiteGuardTest、TraitWashOps/TraitAddOps/SpiritRootOps 措辞改 getEffectiveCultivation 投影语义）；修为累积主体（accumulate 列写）保留（对拍 Kotlin 臂 + 基准仍需） |
