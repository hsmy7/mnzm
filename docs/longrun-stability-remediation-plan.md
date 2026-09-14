# 长期运行稳定性审计根治方案

> **对应审计**：[longrun-stability-audit-report.md](longrun-stability-audit-report.md)（2026-09-09，49 项问题：1×P0、7×P1、18×P2、23×P3，五大结构性缺陷带）
> **执行方式**：任务带 checkbox，按 Phase 分批交给执行会话逐任务实施；每任务自带验证门槛，每 Phase 结束跑全量检查。
> **同日关联方案**：[performance-remediation-plan-2026-09-09.md](performance-remediation-plan-2026-09-09.md)（渲染性能审计的根治方案，已实施大部分——与本方案在渲染器生命周期上有交叉，见「§0 基线核对」）。

**目标**：对审计 49 项问题全部给出**结构性根治**设计。根治的定义沿性能方案的判据精神：修复后该类问题在新代码路径中**不可能再发生**（幂等不变量 / 生命周期对称 / 上限即语义 / 双端一致），而非在症状处加守卫、打补丁。

**架构总览**：七项全局决策（D1–D7）承载全部修复——渲染器 Surface 纪元「先析构后重建」不变量 + 渲染线程 vk 调用全面有界化（D1，承载 P0）；物品 id 计数器与存档生命周期对齐（D2）；「死亡不删除」账本族统一生命周期治理（D3）；数据库与文件侧「承诺清理接线」（D4）；进程级自续期机制的退出条件与收尾对称（D5）；时间追补公式双端单一来源（D6）；规模×时间热点的行级指纹预算（D7）。

**技术栈**：Kotlin 2.2.20 / C++20（NDK r27，arm64-v8a）、Vulkan 1.1 / GLES2、Room（v50）、JNI、Compose UI。

---

## §0 基线核对（方案编写时二次实码核对）

审计与性能修复会话**同日并行**，工作区已漂移。本方案编写时（同日晚些时候）对渲染器关键项做了二次实读核对，结论：

| 审计项 | 审计时状态 | 当前工作区实况（本方案基线） |
|---|---|---|
| P1-2 destroyTexture 空实现 | 空函数体 | **已被性能方案 Task 3.4 修复**：`VulkanBackend::destroyTexture`（VulkanBackend.cpp:2492-2498）入队 `m_retiredTextures`，帧计数超过 MAX_FRAMES_IN_FLIGHT 后在帧边界销毁（:2575-2583）；shutdown 一并清空（:366-367）。**本方案仅复核确认，不再重复修复** |
| P1-1 UAF 竞态 | fence 等待无界 | **部分收窄**：fence 已有界（`waitForFenceBounded` 2s，:180/:1856 + 上传熔断计数 P3-9）；但 `vkAcquireNextImageKHR` 仍 `UINT64_MAX` 无界（:2591-2593）——**卡 >2s 的物理条件仍存在**，本方案 D1 补齐 |
| P0-1 复用路径泄漏 | initSurface 覆盖式重建 | **原样存在**：`initSurface`（VulkanBackend.cpp:257-299）无任何「先销毁旧代」防御；复用入口 NativeBridge.cpp:329-340 `isDeviceReady()==true` 直接 `initSurface`；skip-release 入口 NativeSurfaceView.kt:843-848 仍在（日志原文 "skipping backend release … Resources are rebuilt by next initRenderer" ——注释自证的正是泄漏路径） |
| P2-1 resize 泄漏 VkSurfaceKHR | createSwapchain 无条件建 surface | **原样存在**（:621-630 建 surface；destroySwapchain :748-758 不销毁） |
| P2-2 GLES 不 release ANativeWindow | shutdown 只置空 | **原样存在**（GlesBackend.cpp:316 `m_window = nullptr`，全文件无 `ANativeWindow_release`） |
| P3-2 描述符池 maxSets=16 | 硬上限 | **原样存在**（:1304） |
| P2-16 追补 cap 双端不对称 | Kotlin 不随速度缩放 | **原样存在**（GameEngineCoreAuthoritativeOps.kt:53 `coerceAtMost(GameTimeClock.MAX_PHASES_PER_TICK)`） |
| P2-10 换档不清瞬态队列 | reset 清六容器、load 不清 | **复核有补充发现**：`reset()`（GameStateStoreImpl.kt:1739-1775）清了审计所列六容器中的五项（pendingBattleResult / notificationQueue / rewardCards / rewardCardQueue / beastAttacks），但 **`_pendingMarriageProposalsFlow`（:307）在 reset 路径同样漏清**（全文件仅 :836 手动清空一处）；loadFromSnapshot（:1459 起）对全部七个容器均不清。**本方案按七个容器全覆盖设计** |

其余 40 项属审计独立完成、无同日会话交叉的子系统，以审计结论为准（行号可能有 ±10 行漂移，执行时以函数名+注释锚点二次定位）。

---

## 全局约束（每个任务隐含遵守）

- 降级链顺序 `Vulkan → GPU GLES → CPU Canvas` 不可改变；每帧绘制路径不持 `g_rendererLifecycleMutex`（NativeBridge.cpp 纪律）保持。
- C++ 是 AUTHORITATIVE 真相源：涉及结算/账本/AI 状态的清理逻辑一律先落 C++，Kotlin 侧做镜像同步（CLAUDE.md 规则 16 / rules/cpp-priority.md）。
- 存档兼容：任何「导入后裁剪/压缩」必须幂等、可重入（旧版本存档多次导入结果一致）；不引入 DB schema 变更（本方案全部为 DAO 逻辑与调度接线，无 Migration）。
- 所有异常捕获点带日志与上下文（项目「防御兜底」体例）；所有新上限用命名常量（禁魔法数字）。
- 既有测试不回退；测试串行 `--max-workers=1`；detekt baseline 只缩不增。
- 死亡标记路径必须走 `markDead` 统一入口（CLAUDE.md 13.3 既有条目）——本方案 D3 的统一收口建立在其上。

## 根治判据（什么算「根治」而非「打补丁」）

| # | 判据 | 反例（补丁） | 正例（根治） |
|---|---|---|---|
| R1 | 修复后**同类问题在新代码路径中无法复现**（不变量层面阻断） | 给当前泄漏点补一次销毁 | 「任何 create* 必须先销毁旧句柄」编码为 initSurface 入口幂等不变量，后续新增 Surface 级资源自动被 destroySurfaceGeneration 覆盖 |
| R2 | 修复**完整生命周期**而非单次读写 | 死亡路径补一个 map 清理 | 死亡/叛逃/逐出三路径统一收口到 `eraseDiscipleDerivedMaps`，新增强化派生 map 有唯一登记点 |
| R3 | 修复**数据源头的错位**而非在错误输入上修补 | 撞号后特判合并 | id 计数器经 reseed 与存档对齐，撞号在构造上不可能发生 |
| R4 | 消灭**随时间/次数单调支付的成本结构** | 调大容器上限 | 账本按语义窗口裁剪（消费窗口=保留窗口），成本有上界 |
| R5 | **双端一致**（C++/Kotlin 公式、顺序、上限对齐） | 只改 C++ 侧 cap | 追补公式/账本裁剪/尸体压缩全部双端同步改，守卫测试锁死 |

---

## 第一部分：全局架构决策（D1–D7）

### D1. 渲染器 Surface 纪元「先析构后重建」不变量 + vk 调用有界化（承载 P0-1、P1-1、P2-1、P2-2、P3-1、P3-2、P3-3）

**现状结构缺陷**：Surface 纪元的资源析构只有一个入口——`shutdown()`（整实例销毁）。当 Kotlin 侧因渲染线程 2s 内未停而跳过 release（NativeSurfaceView.kt:843-848），下一纪元 `initRenderer` 复用同一实例调 `initSurface`（NativeBridge.cpp:329-340），后者对全部成员句柄**覆盖式赋值**（surface/swapchain/views/framebuffers/renderPass/pipelines/VBO/commandPool/同步对象/白纹/纹理注册表），旧代句柄无人持有直到进程结束。同时 `vkAcquireNextImageKHR` 无界等待（UINT64_MAX）使「渲染线程卡 >2s」具备物理可能性，这正是 skip-release 分支的触发条件。

**根治设计**（三层防御，任一层独立成立即可阻断泄漏/崩溃）：

1. **析构函数化**：把 `shutdown()` 中「Surface 纪元资源」的分段清理拆为可复用的 `destroySurfaceGeneration()`——销毁 pipelines/offscreen/swapchain/framebuffers/renderPass/VBO/commandPool/同步对象/白纹/m_textures/m_retiredTextures/descriptorPool/VkSurfaceKHR/ANativeWindow 引用，**保留** device 级资源（instance/device/shaderModules/pipelineCache/staging）。`shutdown()` 重写为调用它 + device 级收尾。
2. **入口幂等不变量**：`initSurface()` 入口检测旧代残留（`m_surface != VK_NULL_HANDLE || m_swapchain != VK_NULL_HANDLE || m_commandPool != VK_NULL_HANDLE`）→ 先 `vkDeviceWaitIdle` + `destroySurfaceGeneration()`，再走正常创建链。**从此「跳过 release」「迟到 init 成功」任何路径走到 initSurface 都不泄漏**。
3. **有界化 + 世代收尾**（消灭触发条件本身）：
   - `vkAcquireNextImageKHR` 从 `UINT64_MAX` 改 250ms 有界循环（spin 上限 + 每轮检查 `m_ready`），与已有 fence 有界等待对齐——渲染线程在 Surface 销毁后**必然**在有限时间（≈2s fence + 250ms×spin 上限）内退出，2s join 截止从此覆盖真实情况；
   - `m_ready`/`m_deviceReady` 改 `std::atomic<bool>`（当前跨线程读写无同步，submitFrame 的 `if (!m_ready)` 守卫依赖它可见）；
   - Kotlin 侧 skip-release 分支不再只打日志：保留 `deferredBackend` 引用，新纪元 init 派发前 `resolveDeferredRelease()`——轮询 join 旧渲染线程（5s 截止），join 成功即补 `release()`；5s 仍未退出（有界化后理论上不可达）则**本纪元降级 GLES** 并上报结构化事件，绝不并发触碰该 Vulkan 实例——把「UAF 或泄漏」的二选一变成「安全降级」的第三选项。

配套两个小修：`createFramebuffers()` 入口先销毁旧 `m_framebuffers`（P3-1，清晰度切换路径）；`createSwapchain` 的 surface 创建拆出 `ensureSurface()`（m_surface 已存在则复用，P3-1 同根的 P2-1——resize 不再新建 surface）；`shutdownRenderer` 补 `g_scale = 1.0f`（P3-3）。

**GLES 对称收尾**（P2-2）：`GlesBackend::shutdown` 在置空 `m_window` 前补 `ANativeWindow_release(m_window)`（与 VulkanBackend.cpp:401-403 同构；引用来源是 NativeBridge `ANativeWindow_fromSurface` 的 acquire）。

### D2. 物品 id 计数器与存档生命周期对齐（承载 P1-3）

**现状结构缺陷**：7 处函数级 `static uint64_t counter`（inventory.h:464-465/544-547、merchant_settlement.h:54-57、recruit_settlement.h:63-67、year_settlement.h:589-593、ai_sect_recruit.h:82-86、secret_realm_settlement.h:65-68）生命周期=进程，而生成的 `gc-*` id 生命周期=存档。`importStateJson`（game_core.cpp:388-420）不复位任何计数器 → 重启读档后必然撞号 → 按 id 去重/删除/upsert 的状态腐化。

**根治设计**：

1. **单一 id 分配器**：收敛全部 static 计数器到 inventory.h 的注册表（`prefix → counter`，`nextItemId` 上移共享已有先例，production.h:284 注释）。新增物品类别必须登记 prefix——守卫测试锁定（见第五部分）。
2. **导入即 reseed**：`importStateInternal` 成功尾部调用 `reseedItemIdAllocators(state)`——扫描全部持有 `gc-*` id 的集合（pills/manualStacks/equipmentInstances/materials/herbs/seeds/storageBags），按 prefix 提取已见最大后缀 N，计数器推到 `max+1`。未识别格式（Kotlin 侧 UUID）跳过——UUID 空间与计数器空间不相交（审计运行时验证点 3 的静态推演，执行时复核）。
3. **存量撞号自愈**（读档一次性）：可堆叠类（丹药/材料/草药/种子/功法）重复 id 交给 `StackableItemStore` 合并语义（同 id 合并本就是正确行为）；不可堆叠的 `gc-eq` 装备实例重复 id 在导入时对第二及以后的条目重新编号，并同步替换 state 内引用面（弟子装备槽/manual 绑定）。自愈规则放入读档净化规则族（data/integrity/rules/ 先例），执行时先 grep 引用面再定替换清单。

### D3. 「死亡不删除」账本族统一生命周期治理（承载 P1-7、P2-3、P2-4、P2-5、P2-6、P2-7、P2-8、P3-4、P3-5）

**现状结构缺陷**：一族容器「只进不出」——AI 弟子尸体、死亡记录、邮件幂等账本、宗门战史、血炼三 map、储物袋、lifeEvents。共同根因：**容器生命周期与语义生命周期脱钩**（防重复账本按「永久」保留、战史按「全史」保留、尸体按「永不删除」保留），且清理点分散在死亡/叛逃/逐出三条路径上各自漏项。

**根治设计**（按语义定生命周期，统一收口）：

1. **AI 尸体新陈代谢**（P1-7）：年结统一压缩——`isAlive=false && gameYear - deathYear >= AI_CORPSE_RETENTION_YEARS`（建议 3，产品决策项①）的条目整行移除（C++ year_settlement 年度 cull 同一生命周期点 + Kotlin YearSettlementResidualExecutor 镜像）；`truncateToLimit`（AISectDiscipleManager.kt:603-609 / ai_sect_recruit.h:396-400）排序键改为 `isAlive 优先，再按 base stats`——尸体不再挤占 1000/宗名额、不再冻结宗门战力。导入侧幂等：读档后跑一次同规则压缩（老档自然回缩）。
2. **幂等账本按防重复窗口截断**（P2-5）：`mailRecords` 保留近 `MAIL_RECORD_RETENTION`（建议 500）条——防重复发放只需覆盖「删除已读后重领」的交互窗口，500 条远超实际需要；追加时裁剪，导入时同规则（三处持久化 .sav/.bak/云档同步回缩）。
3. **战史按消费窗口裁剪**（P2-6）：`sectBattleRecords` 追加时按 `year >= gameYear - 3` 裁剪——消费方（countRecentBattleRecords / AISectAttackManager）窗口就是 3 年，双端同步（C++ month_settlement.h:1942-1949 读侧 + Kotlin 写侧 GameEngineBattleOps.kt:302-312）。
4. **强化派生 map 统一收口**（P2-7 + P3-4）：新函数 `eraseDiscipleDerivedMaps(state, id)`——清 `bloodRefinementBonusTotals`/`bloodRefinementPctTotals`/`bloodRefinements`/`manualProficiencies` 四表键。调用点：C++ 死亡链（year_settlement.h:1690 补齐）、叛逃链（month_settlement.h:1227-1310 desertDiscipleCleanup 补齐）、逐出链；Kotlin 侧 DiscipleLifecycleProcessor.kt:194-196/307-309 与 DiscipleService.kt:166-221 同步补齐。`bloodRefinements[id]` 追加处加 `takeLast` 上限。守卫测试：构造「招募→血炼→逐出→再血炼→死亡」全路径档，断言四表零残留。
5. **零消费者字段直接删除**（P2-4）：`_deathRecords` 全库 feature/data 层零消费者、不持久化——删除字段与 `loadFromSnapshot` 的 `deathRecords` 参数（API 收窄为内部模块变更）；若执行时编译暴露隐藏消费者，降级方案为 deepCopy 改共享不可变引用 O(1) + cap 1000。
6. **储物袋合并+容量**（P2-8）：入袋三路径（auto_gear.h:334/607、disciple_purchase.h:595/645/665、month_settlement.h:1674）统一走 `StackableItemStore` 合并（Kotlin 侧 GameEngineSectLevelOps.kt:272 已有同模式）+ 袋容量上限 `STORAGE_BAG_CAPACITY`（建议 50，按仓库 capacityPerBuilding 先例定值）；超限转化为「不再拾取」（偷盗/购买路径跳过），不销毁已有物品。
7. **lifeEvents 软上限**（P3-5）：每弟子保留最近 `LIFE_EVENTS_RETENTION`（建议 100）条，追加时裁剪——突破/金丹等里程碑事件天然在近期窗口内，裁最旧即可。

**性能联动**（P2-3）：aiSectDisciples 引入世代计数 `aiSectDisciplesRev`（C++ MutableGameState 新字段，不持久化；该表全部写入点 bump，读档 bump 一次）。exportDirty 导出包附带 rev；`StateSyncService.buildReverseEnvelope`（StateSyncService.kt:463-467）先比 rev——相同则跳过深比较与全量 JSON 两次过 JNI。深比较从「每旬 O(ΣD×字段数)」降为「每旬一次整数比较」，把 P1-7 即便在压缩前的成本也按住。

### D4. 数据库与文件侧「承诺清理接线」（承载 P1-4、P1-5、P2-14、P2-15、P3-6、P3-9、P3-10、P3-11）

**现状结构缺陷**：三处「注释承诺的清理不存在」+ 两处「清理函数无调用方」——`.pre_migrate_backup.v{N}`、change_log 死清理代码、mails 空 limit、archives 清理未接线、snapshots 目录无清理。共同根因：**写入点与治理任务没有接线契约**。

**根治设计**：

1. **备份保留策略**（P1-5）：`verifyAndRecoverDatabase` 版本达标分支（GameDatabase.kt:607 附近）调用新函数 `pruneMigrationBackups(keep = MIGRATION_BACKUP_RETENTION)`（建议 2）——按版本号降序保留最近 2 份 `.pre_migrate_backup.v*`，删除更旧；同时接入 StorageMaintenanceFacade 定期任务（双保险，GameDatabase.kt:604-605 注释承诺的「维护任务」就此兑现）。
2. **mails 真实容量上限**（P1-4）：`insertWithEnforceLimit` 用 `@Transaction` 实现——INSERT 后，若该 slot 行数超 `maxLimit`，淘汰**已读+附件已领+（有 expireAt 的）已过期**三元组满足的最旧行（产品决策项②：永不自动删除含未领附件的邮件；若无可淘汰行则超限保留并计数上报，不静默）。邮件 UI 加 `LIMIT` 分页加载（`getActiveMails` 先取最近 `MAIL_PAGE_SIZE`=200，滚动加载更多）。
3. **change_log 接线**（P2-14）：`DataPruningScheduler` 新增周期任务调用既有 `deleteOlderThan(7d)`（ChangeLogDao.kt:36-37 已有实现，只缺调用方）——保留 7 天排障窗口；`markSynced` 死代码删除（保存成功即消费，无需 synced 标记位）。
4. **archives 清理接线**（P2-15）：`cleanupExpiredArchives(retentionMonths=12)`（DataArchiver.kt:267-289）接入 StorageMaintenanceFacade 定时任务（StorageMaintenanceFacade.kt:22-28 框架已存在）；`maxBattleLogs` 运行时可调至 5000 的配置面同步登记本依赖。
5. **一次性与口径修复**（P3-9/P3-10/P3-11/P3-6）：启动时一次性删除 legacy `snapshots/` 目录（StorageConstants.kt:68 常量已无引用）；`DataPruningScheduler`/`DataArchiveScheduler` 的 slotIds 从 StorageConstants 派生（覆盖 0..6 全合法槽）；`totalLogsDeleted` 改累加 `deleteOld` 返回行数；`importStateInternal` 尾部对 `gameEventRecords` 统一裁剪至 200（与生成侧 cap 对齐，防旧档/篡改档一次性放大）。

### D5. 进程级自续期机制的退出条件与收尾对称（承载 P1-6、P2-11、P2-12、P2-13、P3-8、P3-12、P3-13、P3-14、P3-15、P3-17、P3-18、P3-19、P3-23）

**现状结构缺陷**：看门狗闹钟链无「会话不存在」退出条件；紧急重启路径的清理集合是 shutdown 的真子集（漏 cancel engineJob、漏 join 线程池）；EventBus.emitTyped 背压转无上限挂起协程。共同根因：**自续期/长生命周期机制没有显式的退出条件，异常路径的清理集合没有与正常路径对称**。

**根治设计**：

1. **闹钟链会话门**（P1-6）：`AlarmWatchdogReceiver.onReceive` 三条路径（正常判定 :199、两条异常早退 :166/:177）统一先过会话门——`GameForegroundService.isRunning`（companion 内存标志）与引擎会话态（GameEngineCore phase）双信号：无会话 → `cancelAlarm(context)` + return（**不续链**）。`XianxiaApplication.onCreate` 早期自愈：无会话且 AlarmManager 中存在本包闹钟 → cancel（覆盖「进程被 OEM 杀死后闹钟拉起空转进程」的存量状态）。看门狗本体语义（LoopStalled → emergencyRestart）不变。
2. **紧急重启收尾对称**（P2-11/P2-12）：`performEmergencyRestart`（GameEngineCore.kt:1420-1421）步骤 3 前补 `engineJob.cancel()`（与 shutdown:1070 对称）；旧 GameEngine-Thread `shutdown()` 后补 `awaitTermination(2, SECONDS)`（:1325-1328），超时计数上报（OEM 挂起病理观测，对应审计验证点 11）。
3. **emitTyped 背压统一**（P2-13）：`emitTyped`（GameEvents.kt:334-338）改 `trySend` + 丢弃计数 + 节流上报，与 emit/emitSync 同一契约——满通道丢弃事件（月度灵石流水可容忍）优于无上限挂起协程。`notifySubscribers`（:355-365）同改（P3-12，防御性——当前 0 订阅者）。
4. **杂项清偿**（P3 族）：FunctionalWAL recover 注册加时间戳驱逐 + commit 异常补 abort（P3-8）；BackgroundTaskScheduler.cleanup 清 tasks（P3-13）；UnifiedPerformanceMonitor.recordMetric 复用上限检查（P3-14）；ImeAnimationTracker detach 时全表清扫弱引用（P3-15）；CrashHandler 复用 SecureHttpClient（P3-17）；SecureHttpClient Debug 降级路径复用 client（P3-18）；BaseViewModel 三事件通道改 `Channel(64)`（P3-19）；YearlyOpsQueue.forceDrain 加软预算（P3-23）；EventBusAudit.kt 文档修订对齐实况（P3-16）。

### D6. 时间追补公式双端单一来源（承载 P2-16、P3-22）

**现状结构缺陷**：同一「单 tick 追补上限」公式，C++ 侧 `phaseCap = 3×max(speed,1)`（engine_loop.h:145），Kotlin 侧硬编码 `coerceAtMost(3)` 不随速度缩放（GameEngineCoreAuthoritativeOps.kt:53）——2x 速度下挂起 ≥6s 时计划 6 旬只执行 3 旬且不 refund，游戏时间相对墙钟持续变慢。

**根治设计**：公式收敛单一来源——C++ 暴露 `maxPhasesPerTick(speed)`（constexpr，engine_loop.h 现公式上移），JNI 侧 Kotlin `GameTimeClock.MAX_PHASES_PER_TICK` 改为按当前速度计算的属性（`const × max(speed, 1)`，常量同一处注释锚定 C++ 公式）；守卫测试锁定两端公式一致（R5）。`GameTimeClock` rawDelta 补 `coerceAtLeast(0)`（P3-22，理论边界一行防御）。

### D7. 规模×时间热点的行级指纹预算（承载 P2-17、P3-7、P3-20、P3-21）

**现状结构缺陷**：「增量导出」实为常驻基线 JSON 全量树 + 每次导出再全量序列化 + 深比较（dirty_tracker.cpp:53-59/66-67/126）；applyReverseDirty 每条增量对集合线性扫描（game_core.cpp:24-48）；DiscipleTables 每事务重建静态映射（P3-20）。成本随状态规模线性上涨，旬节拍（游戏最频繁节拍）周期性支付。

**根治设计**：

1. **表级 epoch + 行级指纹两步走**（P2-17）：第一步表级——每张 SoA 表维护 `dirtyRowCount`（既有列写屏障递增），exportDirty 时整表无脏行则直接复用基线 JSON 子树（跳过编码+比较）；第二步行级——行指纹（64-bit FNV over 行序列化字节）+ 行脏位，仅重编码指纹变化行。dirty_tracker.h:33-35 登记的「列级写屏障」既定待办即本项。基线内存不增长（move 替换语义保持）。
2. **反向增量索引**（P3-7）：applyReverseDirty 构建临时 `unordered_map<id, index>`（一次 O(N) 建索引，M 条增量 O(1) 查找），替代 O(N×M) find_if。
3. **静态映射 companion 化**（P3-20）：`columnGroupByIndex` 等 90 项纯静态映射移入 companion object（DiscipleTables.kt:539-630），事务路径零重分配——与 P2-4 删除 deathRecords 同一事务瘦身批次。
4. **garrisonJson 复用**（P3-21）：随 P2-18 编排复活实施——`tryNativeCheckAttackConditions` 的全量序列化提升到 decideAttacks 层每宗一次。

---

## 第二部分：逐项方案

> 每项格式：根因 → 根治方案 → 具体改动（文件 + 代码要点）→ 验证 → 风险与回滚。
> 行号以 2026-09-09 方案编写时工作区为准（§0 已核对项）；执行时如已漂移，以函数名+注释锚点二次定位。

### P0-1【P0·确定】渲染器实例复用路径整体泄漏旧一代全部 GPU 资源 → D1

**根因**：见 D1。三条链：① skip-release（2s join 截止后跳过 backend release，NativeSurfaceView.kt:843-848）→ 下一纪元复用入口（NativeBridge.cpp:329-340）→ `initSurface` 覆盖式重建（VulkanBackend.cpp:257-299，全成员无「先销毁旧」）→ 旧 swapchain/VBO(3×≈11.25MB)/纹理(17~22MB)/命令池/同步对象/VkSurfaceKHR/ANativeWindow 引用全量泄漏；② VulkanInit 迟到成功被纪元守卫丢弃（NativeSurfaceView.kt:1035 `currentGen != generation` 早退）→ activeBackend 恒 null → release 永不执行；③ acquire 无界（:2591）使触发条件物理存在。

**具体改动**：

1. `VulkanBackend.h`——新增私有方法声明 + 契约注释：

```cpp
// Surface 纪元资源析构（幂等，可重复调用）：vkDeviceWaitIdle 后销毁
// pipeline/offscreen/swapchain/framebuffer/renderPass/VBO/commandPool/
// 同步对象/白纹/m_textures/m_retiredTextures/descriptorPool/VkSurfaceKHR/
// ANativeWindow 引用。保留 device 级资源（instance/device/shaderModules/
// pipelineCache/staging）。initSurface 入口与 shutdown 共用——任何
// 「上一代未释放」路径走到这里都不泄漏（审计 P0-1 幂等不变量）。
void destroySurfaceGeneration();
```

2. `VulkanBackend.cpp`——`destroySurfaceGeneration()` 实现 = 现 shutdown() :348-408 段搬移（含 m_ready/m_deviceReady 置 false 与 m_pendingDraws.clear() 中属于纪元的部分；m_deviceReady 保持 true——device 未销毁）。`shutdown()` 重写：`destroySurfaceGeneration()` + savePipelineCache/销毁 cache + staging 销毁 + device/instance 销毁。
3. `initSurface()` 入口（:260 `if (!m_deviceReady)` 之后）：

```cpp
// 幂等防御：上一代表面资源未释放（skip-release 纪元 / 迟到的 init 成功）
// 时先析构旧代——覆盖式重建从此不可能泄漏（审计 P0-1）
if (m_surface != VK_NULL_HANDLE || m_swapchain != VK_NULL_HANDLE ||
    m_commandPool != VK_NULL_HANDLE) {
    LOGW("initSurface: stale surface generation detected — destroying first");
    destroySurfaceGeneration();
}
```

4. `createSwapchain()`（:621-630）surface 创建拆出 `ensureSurface()`：`m_surface != VK_NULL_HANDLE` 直接复用（P2-1 一并根治——resize/清晰度切换不再新建 surface）。
5. `createFramebuffers()`（:760）入口先销毁旧 `m_framebuffers`（P3-1）。
6. `submitFrame` 的 acquire 段（:2591-2593）：

```cpp
VkResult result = VK_TIMEOUT;
for (int spin = 0; spin < ACQUIRE_SPIN_LIMIT && m_ready.load(); ++spin) {
    result = vkAcquireNextImageKHR(m_device, m_swapchain, 250'000'000ULL /*250ms*/,
                                   m_imageAvailable[m_currentFrame], VK_NULL_HANDLE, &imageIndex);
    if (result != VK_TIMEOUT) break;
}
if (result == VK_TIMEOUT || !m_ready.load()) return;  // 有界放弃，线程必然退出
```

`m_ready`/`m_deviceReady` 改 `std::atomic<bool>`，全部读写点走 load/store（头文件 :229 契约注释同步更新）。
7. `NativeSurfaceView.kt`——skip-release 分支记录待回收引用，新纪元收尾：

```kotlin
private var deferredBackend: RenderBackend? = null  // skip-release 纪元残留

// stopRenderThread() 超时分支：
if (!joined) {
    deferredBackend = activeBackend
    Log.w(TAG, "RenderThread did not stop after 2s deadline — release deferred to next epoch")
}

// handleSurfaceCreated 派发 init 前（主线程）：
private fun resolveDeferredRelease() {
    val backend = deferredBackend ?: return
    val thread = stuckRenderThread ?: return
    val deadlineNs = System.nanoTime() + DEFERRED_RELEASE_DEADLINE_NS  // 5s
    while (thread.isAlive && System.nanoTime() < deadlineNs) { /* 200ms 轮询 join */ }
    deferredBackend = null
    stuckRenderThread = null
    if (!thread.isAlive) {
        backend.release()          // 安全补释放
    } else {
        reportRenderFallback(this, "STUCK_RENDER_THREAD")  // 本纪元降级 GLES（initCoordinator 选择链注入）
        forceGlesForEpoch()
    }
}
```

（skip-release 时同步保留 `stuckRenderThread = renderThread` 引用。）

**验证**：
- 单元/桌面：gamecore 桌面编译回归；`SoftwareCanvasBackendTest` 全绿（渲染特性双路径既有守卫）。
- 真机门槛（审计验证点 1/2）：Debug 构建在 submitFrame 注入 3s GPU 停顿，循环开关屏 10 次——logcat 应出现 "stale surface generation detected" 且 `dumpsys meminfo` GL mtrack / Adreno kgsl 计数**回到基线**（修复前每循环 +30~40MB）；旋转 50 次断言 vkCreate/vkDestroySurfaceKHR 计数差为 0（P2-1）。
- 真机门槛（P1-1）：GPU 卡顿注入 + 快速开关屏压力循环 200 次，零 native crash（栈不得落入 submitFrame）。

**风险与回滚**：改动内聚于 VulkanBackend 生命周期函数 + NativeSurfaceView 释放路径，单 commit 可 revert。最大风险是 destroySurfaceGeneration 与在途渲染线程并发——由 Kotlin 侧 join 前置（resolveDeferredRelease 在 init 派发前、主线程串行）与 C++ 侧 vkDeviceWaitIdle 双重保证；5s 截止降级 GLES 是最终安全阀（降级链本身既有）。

### P1-1【P1·确定】skip-release 纪元的 use-after-free 竞态 → D1（随 P0-1 一并根治）

**根因**：旧渲染线程卡 vk 调用 → 2s 超时放弃 join → 新纪元在同一 g_renderer 上 initSurface 重建 → 旧线程苏醒后用悬垂句柄继续 vkResetFences/vkQueueSubmit。同触发条件下 prewarmDevice 的 `delete g_renderer`（GameActivity.kt:326 经 NativeBridge prewarm 入口）是第二个删除点。

**根治**：即 P0-1 的第 3/6/7 条改动——acquire 有界化 + m_ready 原子化消灭「无限期卡住」；resolveDeferredRelease 保证 initSurface 前旧线程已退出；5s 仍不退则本纪元 GLES 降级，prewarm 的 delete 分支同样过 deferred 检查（prewarmDevice 前置 resolveDeferredRelease 语义——由 NativeBridge initRenderer 入口的幂等析构兜底：prewarm delete 的对象若仍有旧线程，delete 前的 shutdown 内 vkDeviceWaitIdle 会等待在途帧完成，而线程有界化后必达）。

**验证**：P0-1 真机门槛同场覆盖；另加「skip-release 注入 → 下一纪元 logcat 出现 deferred release 完成或 GLES 降级事件」链路断言。

**风险**：无独立风险面（与 P0-1 同一 commit）。

### P1-2【P1·已闭环】destroyTexture 空实现 → 已被性能方案 Task 3.4 修复

**现状**：§0 已核对——延迟释放队列已落地（VulkanBackend.cpp:2492-2498/2575-2583，VulkanBackend.h:234 契约注释）。本方案**无新增改动**；P0-1 的 destroySurfaceGeneration 将 m_retiredTextures 清空纳入纪元析构（shutdown :366-367 已有，搬移保持）。执行批次中仅含「复核确认」任务。

### P1-3【P1·确定】物品 id 计数器进程级 vs 存档级不对称 → D2

**根因**：见 D2。id 进入 state.pills/manualStacks/equipmentInstances 并经 json_codec 全量持久化（json_codec.cpp:1298-1316），importStateJson 不复位计数器（game_core.cpp:388-420）。撞号后果：removePill 按 id 删错堆叠（inventory.h:956-971）、keyIndex 混挂（:451-453）、upsertEntities 按 id 视为同一实体互相覆盖（game_core.cpp:25-37）。

**具体改动**：

1. `inventory.h`——计数器注册表：

```cpp
// 进程级 id 分配器（审计 P1-3）：计数器生命周期=进程，id 生命周期=存档——
// 任何持存档导入的路径必须先 reseedItemIdAllocators() 对齐，否则重启撞号。
// 新增物品类别的 prefix 必须在此登记（守卫测试 ItemIdReseedGuardTest 锁定）。
inline std::map<std::string, uint64_t>& itemIdCounterRegistry();
inline std::string nextItemId(const char* prefix);  // 既有签名，内部走注册表
```

2. 七处 static 计数器（inventory.h:464-465/544-547、merchant_settlement.h:54-57、recruit_settlement.h:63-67、year_settlement.h:589-593、ai_sect_recruit.h:82-86、secret_realm_settlement.h:65-68）全部改走注册表；prefix 统一清单（gc-pill/gc-eq/gc-manual/gc-mat/...，以各处现 prefix 为准登记）。
3. `game_core.cpp`——`importStateInternal` 成功尾部（:417 catch 之前）：

```cpp
reseedItemIdAllocators(state_);  // 扫描全部 gc-* id 集合，按 prefix 取已见最大 N，
                                 // 计数器 = max(current, N+1)——幂等（重复导入不回退）
```

4. 存量自愈：`gc-eq` 装备实例重复 id 读档重编号 + 引用面替换（执行时先 grep 引用面：disciple 装备槽/manual 绑定/储物袋持有）；可堆叠类交由 StackableItemStore 合并（正确语义）。

**验证**：gamecore 桌面单测——`生成物品→exportStateJson→新 GameCore 实例 import→再生成→断言全部 id 唯一`；`重复导入幂等`（两次 import 后计数器不回退）；Kotlin 集成测试——生产→存档→重启进程（Robolectric 重建）→读档→再生产，断言镜像 upsert 无互相覆盖（审计验证点 3）。

**风险与回滚**：reseed 只推高不回退（`max(current, N+1)`），对正常档零影响；重编号自愈限于装备实例且集中一处，可独立 revert。Kotlin 侧若实测发现改写 id 的路径（审计无法静态排除项），reseed 的 max 语义仍保证不撞——该验证结论回填本文档。

### P1-4【P1·确定】mails 表容量上限空实现，无界增长 + UI 全表加载 → D4（产品决策项②）

**根因**：`insertWithEnforceLimit`（MailDao.kt:22-27）忽略 maxLimit 只增不删（注释自证「防附件丢失」有意设计）；唯一删除是玩家手动；`getActiveMails`（:13-14）全表 ORDER BY 加载。月度拉取 + 内建邮件 + 溢出自动发信（OverflowMailSender.kt:351-353，生产型玩家高频）→ DB 单调膨胀 + 邮件 UI 随历史线性变慢。

**具体改动**：

1. `MailDao.kt`：

```kotlin
@Transaction
suspend fun insertWithEnforceLimit(mail: MailEntity, maxLimit: Int) {
    insertMail(mail)
    // 只淘汰「已读 + 附件已领 + 已过期」三元组满足的最旧行——含未领附件的邮件
    // 永不自动删除（防附件丢失设计保留）；无可淘汰行时超限保留（上报计数，不静默）
    pruneMailsToLimit(mail.slotId, maxLimit)
}

@Query("DELETE FROM mails WHERE slotId = :slotId AND id IN (
    SELECT id FROM mails WHERE slotId = :slotId AND isRead = 1 AND attachmentClaimed = 1
    ORDER BY receivedAt ASC LIMIT :overflow)")
suspend fun pruneMailsToLimit(slotId: Int, overflow: Int)  // overflow = count - maxLimit，调用方计算
```

（列名以 MailEntity 实际字段为准执行时核对。）溢出不可淘汰（全含未领附件）时 `Log.w` + 监控计数（见第五部分埋点）。
2. `MailService.kt`（:210/:247 两调用点）传真实 `MAIL_INBOX_LIMIT = 1000`（SaveLimitsConfig 同源常量）。
3. `getActiveMails` 分页：`LIMIT :limit OFFSET :offset`，`MailRepositoryImpl` 提供「最近 `MAIL_PAGE_SIZE = 200` + 加载更多」两段 API；邮件 UI（对话框）接分页。

**验证**：Robolectric DAO 测试——插入 1200 封混合状态，断言总数 ≤1000 且全部未领附件行存活、被删行均为三元组满足的最旧行；UI 侧分页滚动加载测试；挂机 100 游戏年自动化（审计验证点 4）`COUNT(*)` 稳定在 1000 附近。

**风险与回滚**：删除语义收紧为三元组——比审计建议（直接淘汰已读已领最旧）更保守，附件零丢失；若产品选择完全保留，则仅做分页 + 溢出上报（上限监控兜底）。DAO 逻辑无 schema 变更、无 Migration。

### P1-5【P1·确定】`.pre_migrate_backup.v{N}` 迁移备份永不删除 → D4

**根因**：创建点唯一（GameDatabase.kt:419 全量复制 :426-430），删除点不存在（:604-605 注释承诺的维护任务全仓 grep 不存在）；DB 近期每版递增（v43→v50）→ 每次发版 +1 份全库副本。

**具体改动**：

```kotlin
// GameDatabase.kt
private const val MIGRATION_BACKUP_RETENTION = 2  // 最近 2 个版本供降级恢复

fun pruneMigrationBackups(context: Context, keep: Int = MIGRATION_BACKUP_RETENTION) {
    // 列 databases/ 下 .pre_migrate_backup.v{N}，版本号降序保留 keep 份，删更旧
}
```

接线两处：`verifyAndRecoverDatabase` 版本达标分支（:607 附近，迁移成功即旧版备份价值衰减）+ `StorageMaintenanceFacade` 周期任务（StorageMaintenanceFacade.kt:22-28）。`findVersionedBackup`（:777-783）扫描范围同步收窄到保留窗口。

**验证**：Robolectric 迁移测试——构造 v47/v48/v49 三份备份 + 迁移到 v50，断言仅存 v49/v48；正常降级恢复路径（findVersionedBackup 命中保留份）回归测试。

**风险与回滚**：保留 2 份覆盖「连续两次升级后发现回滚需求」的常规窗口；极端需回退更旧版本的场景接受重装（与审计建议一致）。独立函数可 revert。

### P1-6【P1·确定】看门狗闹钟链无条件自续期 → D5

**根因**：`AlarmWatchdogReceiver.onReceive` 三条路径（:199 正常末尾、:166/:177 异常早退）无条件 `scheduleAlarm(context)`；进程被 OEM 杀死后 Service.onDestroy 不执行（GameForegroundService.kt:148 cancelAlarm 未跑）→ 闹钟每 15s 拉起全进程（完整 Application.onCreate）→ emergencyRestart 被 STOPPED 拒绝（GameEngineCore.kt:1397-1399）→ 再续链，永续。

**具体改动**：

```kotlin
// AlarmWatchdogReceiver.onReceive 三条路径统一入口：
private fun shouldContinueChain(context: Context): Boolean {
    val inSession = GameForegroundService.isRunning || engineSessionActive(context)
    if (!inSession) {
        cancelAlarm(context)          // 会话不存在：退链 + 主动清闹钟
        Log.i(TAG, "No active game session — watchdog chain terminated")
    }
    return inSession
}
```

`GameForegroundService` 补 companion `isRunning`（onCreate true / onDestroy false，内存标志）。`XianxiaApplication.onCreate` 早期（SDK 初始化前）自愈：`!shouldContinueChain` 时同样 cancel——覆盖存量「已处于永续链」的设备。

**验证**：`adb shell am kill` 杀后台 → `dumpsys alarm | grep xianxia` 无条目 + logcat 无周期性 Application.onCreate（审计验证点 6）；正常游戏内 watchdog 语义回归（注入 loop 停顿 60s → emergencyRestart 照常触发）。

**风险与回滚**：会话判定依赖内存标志——进程死亡后标志丢失正是退链 desired 行为；重启进游戏的正常路径由 GameActivity.onResume 重新 scheduleAlarm（既有）。可 revert。

### P1-7【P1·确定】AI 弟子尸体只标死不删除，单调爬向 28,000 上限 → D3（产品决策项①）

**根因**：五处「标死保留」（AISectBeastAttackProcessor / EncounterBattleService:371-385 / PatrolBattleSystem:757-767 / SecretRealmService:705-716 / CaveExplorationProcessor 年度老化 :289-300 + C++ ai_sect_ops.h:630-659），删除仅玩家攻占一路；招募 +1~5/3年/宗净增长 → 约 950 游戏年触顶 28×1000 → 存档膨胀 + 每旬深比较（P2-3）+ truncateToLimit 按 base stats 把高属性尸体保留挤掉新活弟子（AISectDiscipleManager.kt:603-609）→ 宗门战力冻结。

**具体改动**：

1. C++ `year_settlement.h` 年度 cull 段（与玩家弟子年度 cull 同一生命周期点，:466-486 附近）新增 AI 池压缩：

```cpp
// AI 尸体新陈代谢（审计 P1-7）：死亡超 AI_CORPSE_RETENTION_YEARS 年的条目整行移除。
// 战报/外交语义只引用宗门 id 不引用尸体个体；保留窗口内尸体供近期战报追溯。
constexpr int AI_CORPSE_RETENTION_YEARS = 3;  // 产品决策项①，默认 3
for (auto& [sectId, list] : state.aiSectDisciples) {
    std::erase_if(list, [&](const auto& d) {
        return !d.isAlive && gameYear - d.deathYear >= AI_CORPSE_RETENTION_YEARS;
    });
}
state.aiSectDisciplesRev++;  // P2-3 世代计数
```

（`deathYear` 字段执行时核对 AI 弟子结构——Disciple 侧 markDead 写 deathYears；若 AI 结构缺该字段则随本项补齐并经 json_codec 兼容缺省=导入年。）
2. `truncateToLimit` 排序键双端改 `isAlive 优先 → base stats`（AISectDiscipleManager.kt:603-609 + ai_sect_recruit.h:396-400）。
3. 导入侧幂等压缩：`importStateInternal` 尾部跑同一规则（老档首次读入即回缩，存档体积随之回落）。
4. Kotlin 镜像同步：YearSettlementResidualExecutor 执行后 AI 池以 C++ 导出为准刷新（既有镜像链路，无独立清理代码——避免双写漂移）。

**验证**：gamecore 单测——构造 500 年档（各宗灌入 800 尸体 + 200 活弟子），断言导入/年结后尸体清出、活弟子全保留、宗门战力恢复招募；长期推演断言条目数上界 = 28×(1000 内活弟子 + 3 年死亡增量)；审计验证点 7 真机 500 年档 dump 对照。

**风险与回滚**：保留窗口内语义零变化；跨窗口战报若引用个体明细（执行时 grep 战报结构确认——审计结论是宗门粒度）不受影响。常量可调，函数独立可 revert。

---

### P2-1 resize 泄漏 VkSurfaceKHR → D1（P0-1 改动 4 一并根治）

`ensureSurface()` 拆分后，resize/清晰度切换复用既有 surface；`destroySurfaceGeneration` 持有 surface 的唯一销毁权。验证：旋转 50 次 vkCreate/vkDestroySurfaceKHR 计数差为 0。

### P2-2 GLES 每 epoch 泄漏 1 个 ANativeWindow 强引用 → D1

`GlesBackend::shutdown`（GlesBackend.cpp:297-316）在 `m_window = nullptr` 前补：

```cpp
if (m_window) {
    ANativeWindow_release(m_window);  // 对称 VulkanBackend.cpp:401-403（审计 P2-2）
    m_window = nullptr;
}
```

验证：GLES 强制会话（force_backend=1）开关屏 100 次，`dumpsys SurfaceFlinger` layer/buffer 计数回基线。风险：无（引用来源 NativeBridge fromSurface 的 acquire 本就无人释放）。

### P2-3 每旬全量深比较 × AI 规模 → D3 改动 8

`aiSectDisciplesRev` 世代计数（C++ 写入点 bump + exportDirty 附带 + StateSyncService 整数比较短路）。验证：gamecore 单测（rev 不变时 exportDirty 不含 AI 池子树；bump 后含）+ 审计验证点 8 的 1,400/10,000/28,000 三档 profile（修复后 28k 档旬耗时与 1.4k 档同量级）。风险：rev 遗漏 bump 会导致镜像滞后——守卫测试锁定「全部 AI 池写入点均在 bump 清单」（ai_sect_*.h 写入点枚举）。

### P2-4 `_deathRecords` 零消费者纯开销 → D3 改动 5

删除字段 + `loadFromSnapshot` 签名收窄（GameStateStoreImpl.kt:1455/1249-1255、DiscipleTables.kt:101/120/1567/1725/1660）。验证：全量编译 + 既有测试绿；Allocation Tracker 长会话对比（0.5-2 事务/s 的整表复制消失）。风险：编译暴露隐藏消费者时降级为 O(1) 共享引用 + cap（方案内置分支，不返工）。

### P2-5 `mailRecords` 幂等账本只进不出 → D3 改动 2

追加即 `takeLast(MAIL_RECORD_RETENTION = 500)`（MailService.kt:364/:457 两写点）+ 导入侧同规则（老档 .sav/.bak/云档三处同步回缩）。验证：单测（领 600 封 → size=500 且最近 500 条在册、防重复语义：删已读重领不重复发放）。风险：500 条窗口远超「删除已读后重领」交互周期，幂等语义无损。

### P2-6 `sectBattleRecords` 消费窗口 3 年却不裁剪 → D3 改动 3

追加时 `filter { it.year >= gameYear - BATTLE_RECORD_WINDOW_YEARS }`（=3，与 C++ countRecentBattleRecords month_settlement.h:1942-1949 窗口同源常量）+ 导入侧同规则。验证：连打 500 场后列表长度 ≤ 窗口内场次；C++ 读侧重试计数不变（守卫测试断言窗口常量双端一致）。

### P2-7 血炼三 map 幽灵键族 + P3-4 manualProficiencies 死亡不清 → D3 改动 4

`eraseDiscipleDerivedMaps(state, id)` 统一收口（四表），调用点：C++ 死亡链（year_settlement.h:1690-1695 补 PctTotals 与 manualProficiencies）/ 叛逃链（month_settlement.h:1227-1310 补三 map）/ 逐出链；Kotlin DiscipleLifecycleProcessor.kt:194-196/307-309、DiscipleService.kt:166-221 同步。`bloodRefinements[id]` takeLast 上限。验证：全路径单测（招募→血炼→死亡/叛逃/逐出各分支→四表零残留→存档→读档仍零残留）。风险：无（纯键清理，值语义无人消费）。

### P2-8 储物袋无容量上限、不合并堆叠 → D3 改动 6

三入袋路径（auto_gear.h:334/607、disciple_purchase.h:595/645/665、month_settlement.h:1674）统一走 StackableItemStore 合并 + `STORAGE_BAG_CAPACITY = 50` 容量门（满则跳过拾取，偷盗/购买路径同理；不销毁已有）。验证：单测（同类物品 10 次入袋 → 1 条堆叠；51 件不同物品 → 袋内 50 + 溢出跳过计数）。风险：偷盗「跳过」语义与战斗文案一致性——执行时核对偷盗结果文案分支。

### P2-9 `shownWarningStageIds` 以 UUID 为键只增不减 → D3 派生（随 P2-18 决策）

键构造改 `attackerSectId + stage`（键空间 ≤ 29×阶段数，GameEngineDiplomacyOps.kt:16-20 写点 + AttackWarningService.kt:32-39 构造 + GameOverlayHost.kt:249 消费三处同步）+ 预警过期时同步清理（activeAttackWarnings 到期 filter 处联动）。当前编排休眠（P2-18）增长率≈0，但键改构后代码复活即安全。验证：单测（同宗同 stage 重复预警 → 单键；过期 → 键清除）。

### P2-10 换档读档不清瞬态队列 → D3 派生（含 §0 补充发现：reset 亦漏清婚配队列）

提取 `clearTransientQueues()`（锁内）：

```kotlin
private fun clearTransientQueues() {
    _pendingBeastAttacksFlow.value = emptyList()
    _pendingMarriageProposalsFlow.value = emptyList()   // §0 补充：reset 原也漏清
    _pendingBattleResultFlow.value = null
    _pendingBattleRewardCardsFlow.value = emptyList()
    _rewardCardQueueFlow.value = emptyList()
    while (notificationQueue.poll() != null) { /* drain */ }
}
```

`reset()`（:1763-1769 段替换）与 `loadFromSnapshot()`（:1459 锁内同位置）统一调用。验证：双档脚本化测试（slot1 触发婚配提议不开弹窗 → 读 slot2 → 断言七容器全空；审计验证点 9）+ 守卫测试（反射枚举 `_pending` 前缀 MutableStateFlow 字段 + notificationQueue，断言两路径全覆盖——新增 Pending* flow 漏登记即失败）。风险：无（瞬态本就不持久化）。

### P2-11 紧急重启不 cancel 旧 engineJob → D5 改动 2

`performEmergencyRestart`（GameEngineCore.kt:1420-1421）步骤 3 前补 `engineJob.cancel()`（与 shutdown:1070 对称）。验证：单测（重启前后旧 job children 数归零）+ heap dump SupervisorJobImpl 实例数不随重启次数增长。风险：无。

### P2-12 紧急重启旧线程池只 shutdown 不 join → D5 改动 2

旧 GameEngine-Thread `shutdown()` 后 `awaitTermination(2, TimeUnit.SECONDS)`（:1325-1328），超时 `Log.w` + 计数上报（OEM 病理观测）。验证：审计验证点 11（红米/荣耀长挂机 `ps -T | grep -c GameEngine-Thread` 峰值=基线+1）。风险：2s 阻塞重启路径——紧急重启本就是重路径，可接受。

### P2-13 emitTyped 背压转无上限挂起协程 → D5 改动 3

`emitTyped`（GameEvents.kt:334-338）改 `trySend` + 丢弃计数 + 节流上报（与 emit/emitSync :261-312 同契约）；`notifySubscribers`（:355-365）同改（P3-12 一并）。验证：单测（满通道连续 10 万次 emitTyped → 挂起协程数=0、丢弃计数正确）。风险：满通道丢弃月度灵石流水——可容忍（既有 emit 路径同语义）。

### P2-14 change_log 只 INSERT 不 DELETE → D4 改动 3

`DataPruningScheduler` 周期任务调用 `deleteOlderThan(now - 7d)`（ChangeLogDao.kt:36-37 既有）；`markSynced` 死代码删除。验证：Robolectric（模拟 8 天前行 → 修剪后 0 行；7 天内行保留）；`SELECT COUNT(*) WHERE synced=0` 长跑稳定。风险：无。

### P2-15 archives 清理链路未接线 → D4 改动 4

`cleanupExpiredArchives(12)` 接入 StorageMaintenanceFacade 周期任务；`maxBattleLogs` 可调 5000 的配置注释登记本依赖。验证：审计验证点 12（dump 运行时配置 + archives/ 存量）。风险：无。

### P2-16 2x 追补上限双端不对称 → D6

Kotlin cap 改速度感知（单一来源常量 + 守卫测试，见 D6）。验证：2x 注入 8s 帧停顿 → totalPhases 与 C++ 计划一致（审计验证点 16）；1x 行为零变化回归。风险：无。

### P2-17 DirtyTracker 全量序列化成本 → D7 改动 1

表级 epoch + 行级指纹两步（见 D7）。验证：审计验证点 8（5000 弟子档连续 100 旬 exportDirtyJson 单次耗时/分配 profile，修复前后对比）；基线内存不增长断言。风险：指纹碰撞理论概率（64-bit FNV）忽略不计；行级写屏障遗漏会导致镜像滞后——表级 epoch 兜底 + 守卫测试（列 setter 全部走屏障清单）。

### P2-18 AI 攻击编排休眠（双实现假象） → 产品决策项③（推荐：补齐 C++ 编排）

全仓 git grep 证实 2 参版编排、C++ decideAttacks 循环、sectAttackCooldowns 写点均无生产调用方。**推荐方案**：C++ AUTHORITATIVE 月结（month_settlement.h:1979-2046 子事件序列）补 decideAttacks 编排 + sectAttackCooldowns 写点 + 预警/防守战触发；**删除** Kotlin 2 参版编排（CaveExplorationProcessor.kt:237-238 委托链）避免双实现假象（C++ 优先规则）。P2-9 键改构、P3-21 garrisonJson 复用随本项实施。**若产品决定不做 AI 主动攻击玩法**：删除整族休眠代码（Kotlin 2 参编排 + sect_attack_decision 测试桥 + shownWarningStageIds/sectAttackCooldowns 字段族），P2-9 转为随删除一并消失。验证：跑 12+ 游戏月断言 AI 发起 SECT_OCCUPY/预警/sectAttackCooldowns 非空（审计验证点 10）。

---

### P3 批量清偿（23 项，随对应 Phase 实施）

| # | 处置 | 归属 Phase |
|---|---|---|
| P3-1 setRenderScale 泄漏 3 个 framebuffer | createFramebuffers 入口先销毁旧（D1 改动 5） | Phase 1 |
| P3-2 描述符池 maxSets=16 超限白纹 | `dpInfo.maxSets = std::max(16u, 上限随纹理注册表容量动态化)`——按 `m_textures.capacity()+2` 预留，超限重建池 | Phase 1 |
| P3-3 shutdownRenderer 遗漏重置 g_scale | NativeBridge shutdownRenderer 补 `g_scale = 1.0f`（:322-359 重置清单） | Phase 1 |
| P3-4 manualProficiencies 死亡不清 | 并入 P2-7 统一收口 | Phase 3 |
| P3-5 lifeEvents 无上限 | takeLast(100)（D3 改动 7） | Phase 3 |
| P3-6 gameEventRecords 导入不裁剪 | importStateInternal 尾部裁至 200（D4 改动 5） | Phase 4 |
| P3-7 applyReverseDirty O(N×M) | 临时 id→index 索引（D7 改动 2） | Phase 6 |
| P3-8 WAL 滞留事务条目 | recover 注册加时间戳驱逐（30 天）+ commit 失败补 abort 路径 | Phase 5 |
| P3-9 legacy snapshots/ 目录 | 启动一次性删除（StorageConstants.kt:68 常量随删） | Phase 4 |
| P3-10 修剪调度硬编码 slot 1..5 | slotIds 从 StorageConstants 派生（0..6） | Phase 4 |
| P3-11 修剪统计口径 | totalLogsDeleted 累加 deleteOld 返回行数 | Phase 4 |
| P3-12 notifySubscribers 无背压 | 并入 P2-13 同改 trySend | Phase 5 |
| P3-13 BackgroundTaskScheduler.cleanup 不清 tasks | cleanup 中 `tasks.clear()`（:30-32） | Phase 5 |
| P3-14 recordMetric 绕过 100 上限 | getOrPut 前复用 :76-79 上限检查 | Phase 5 |
| P3-15 ImeAnimationTracker 弱引用残留 | detach 时全表清扫 isDead 条目 | Phase 5 |
| P3-16 EventBusAudit 文档失实 | 按实况重写（0 订阅者/0 注册的事实基线） | Phase 6 |
| P3-17 CrashHandler 裸线程+新 client | 复用 SecureHttpClient + 既有线程池 | Phase 5 |
| P3-18 SecureHttpClient Debug 降级新建 client | 降级路径复用单例 client | Phase 5 |
| P3-19 BaseViewModel Channel.UNLIMITED | 改 `Channel(64)` 三事件通道 | Phase 5 |
| P3-20 DiscipleTables 每事务重建静态映射 | columnGroupByIndex 等移 companion（D7 改动 3） | Phase 6 |
| P3-21 garrisonJson 每对全量序列化 | 随 P2-18 编排复活时提升到 decideAttacks 层 | Phase 6 |
| P3-22 rawDelta 负值边界 | `coerceAtLeast(0)`（随 P2-16 同文件） | Phase 6 |
| P3-23 forceDrain 无软预算 | drain 加 8ms/op 软预算检查（超即让出、下 tick 续） | Phase 5 |

---

## 第三部分：影响范围清单与兼容性分析

### 影响范围总表（文件 — 变更类型 — 说明）

| 文件 | 类型 | 说明 |
|---|---|---|
| android/app/src/main/cpp/VulkanBackend.h/.cpp | 重构 | destroySurfaceGeneration 拆分 + initSurface 幂等 + ensureSurface + acquire 有界 + m_ready 原子化 + createFramebuffers 幂等 + maxSets 动态化 |
| android/app/src/main/cpp/GlesBackend.cpp | 修复 | shutdown 补 ANativeWindow_release |
| android/app/src/main/cpp/NativeBridge.cpp | 修复 | shutdownRenderer 补 g_scale 重置 |
| android/feature/game/.../NativeSurfaceView.kt | 修复 | deferredBackend + resolveDeferredRelease + GLES 纪元降级阀 |
| gamecore/include/gamecore/system/inventory.h 等 7 文件 | 重构 | id 计数器注册表收敛 |
| gamecore/src/game_core.cpp | 功能 | reseedItemIdAllocators + 导入侧裁剪（事件/AI 尸体/mailRecords/战史） |
| gamecore/include/gamecore/system/year_settlement.h、month_settlement.h | 功能 | AI 尸体压缩 + 血炼 map 收口 + 战史窗口 |
| android/core/engine/.../AISectDiscipleManager.kt 等 AI 族 | 修复 | truncateToLimit 排序键 + 尸体镜像 |
| android/core/engine/.../StateSyncService.kt | 性能 | rev 短路深比较 |
| android/app/.../GameStateStoreImpl.kt | 重构 | clearTransientQueues 提取（reset/load 两路径） |
| android/core/data/.../MailDao.kt、MailService.kt、MailRepositoryImpl.kt | 功能 | 真实容量上限 + 分页 |
| android/core/data/.../GameDatabase.kt | 功能 | pruneMigrationBackups + 接线 |
| android/core/data/.../DataPruningScheduler.kt、DataArchiver.kt、StorageMaintenanceFacade | 功能 | change_log/archives/备份治理接线 + slot 派生 + 口径 |
| android/app/.../AlarmWatchdogReceiver.kt、GameForegroundService.kt、XianxiaApplication.kt | 修复 | 闹钟链会话门 + 自愈 |
| android/app/.../GameEngineCore.kt | 修复 | emergency cancel/join 对称 |
| android/core/domain/.../GameEvents.kt | 修复 | emitTyped/notifySubscribers 背压统一 |
| android/core/domain/.../DiscipleTables.kt、GameStateStoreImpl.kt | 重构 | deathRecords 删除 + 静态映射 companion 化 |
| gamecore/src/dirty_tracker.cpp/.h | 性能 | 表级 epoch + 行级指纹 |
| 其余 P3 杂项（CrashHandler/SecureHttpClient/BaseViewModel/ImeAnimationTracker/UnifiedPerformanceMonitor/BackgroundTaskScheduler/FunctionalWAL/YearlyOpsQueue/EventBusAudit/StorageConstants） | 修复 | 见 P3 批量表 |

### 兼容性分析（Migration / 序列化 / 存档）

- **DB：零 schema 变更**——mails/change_log/备份治理全部为 DAO 逻辑与调度接线，无 Migration、无版本递增。
- **存档格式：只减不增字段语义**——`deathRecords` 为进程内存不持久化（删除无存档影响）；AI 尸体/mailRecords/战史/lifeEvents/gameEventRecords 的导入侧裁剪对旧档是**幂等收敛**（多次导入结果一致，且旧版本 App 仍可读裁剪后的档——字段是既有集合的子集）。
- **id reseed**：只推高计数器不改写既有 id（除 gc-eq 重复簇自愈重编号，替换面集中一处）；新旧版本互读安全。
- **`deathYear` 字段新增**（若 AI 弟子结构缺）：json_codec 缺省=导入年——旧档导入后尸体立即满足「超 3 年」条件则首次年结清出，行为收敛正确。
- **降级链/线程契约**：D1 全部改动不触碰每帧绘制路径与降级链顺序；acquire 有界化的 250ms 空转仅在 Surface 将毁窗口内发生（正常帧 acquire 立即返回）。

---

## 第四部分：实施批次（Phase 1–6）

> 原则：先 P0 渲染器根治（崩溃+GPU 耗尽双高危），再状态腐化（id/换档污染——不可逆性最高），后账本/持久化/进程生命周期，性能项 profile 门控。每 Phase 门槛 = `compileReleaseKotlin + testReleaseUnitTest --max-workers=1 + detekt` 全绿 + 所列真机项 + NDK 编译（`assembleDebug` 覆盖 C++）。

### Phase 1 —— 渲染器 P0/P1 根治（D1：P0-1 / P1-1 / P2-1 / P2-2 / P3-1 / P3-2 / P3-3 + P1-2 复核）

- [x] **Task 1.1 destroySurfaceGeneration 拆分**：shutdown 分段清理上移为可复用方法（P0-1 改动 1/2）。commit: `fix(render): Surface 纪元析构函数化——shutdown 清理拆为 destroySurfaceGeneration 可复用（审计 P0-1 前置）`
- [x] **Task 1.2 initSurface 幂等不变量 + ensureSurface 拆分**（改动 3/4，同时根治 P2-1/P3-1 的 surface/framebuffer 面）。commit: `fix(render): initSurface 入口幂等防御——旧代先析构后重建，复用路径零泄漏（审计 P0-1/P2-1）`
- [x] **Task 1.3 acquire 有界化 + m_ready 原子化**（改动 6）。commit: `fix(render): vkAcquireNextImageKHR 有界化+m_ready 原子化——渲染线程必然有限时间退出（审计 P1-1）`
- [x] **Task 1.4 Kotlin deferred release + 纪元降级阀**（改动 7）。commit: `fix(render): skip-release 纪元延迟回收——join 前置补释放，5s 截止安全降级 GLES（审计 P1-1）`
- [x] **Task 1.5 GLES ANativeWindow_release**（P2-2）。
- [x] **Task 1.6 杂项**：g_scale 重置（P3-3）+ maxSets 动态化（P3-2）+ P1-2 复核确认（只读，记录到实施记录）。
- [ ] **门槛**：GPU 停顿注入循环开关屏 ×10 显存回基线 + 旋转 ×50 surface 计数差=0 + 压力循环 ×200 零 native crash + 全量测试绿。

### Phase 2 —— 状态完整性与 id 对称（D2 + P2-10 + P2-9：P1-3 / P2-10）

- [x] **Task 2.1 id 计数器注册表收敛**（7 处 static → inventory.h 注册表 + 守卫测试）。
- [x] **Task 2.2 importStateInternal reseed + gc-eq 重复自愈**（gc-eq 读档自愈由并行会话 Kotlin 规则承担）。commit: `fix(gamecore): 物品 id 计数器导入即 reseed——计数器生命周期与存档对齐，根除重启撞号（审计 P1-3）`
- [x] **Task 2.3 clearTransientQueues 提取与双路径接线**（P2-10，七容器 + 反射守卫测试）。commit: `fix(state): 换档/重置统一清瞬态队列——根治跨档幽灵弹窗与婚配错配（审计 P2-10）`
- [x] **Task 2.4 shownWarningStageIds 键改构**（P2-9，未随编排决策——键改构独立安全）。
- [ ] **门槛**：双档婚配污染脚本复现归零 + 重启撞号复现归零（审计验证点 3/9）+ 全量测试绿。

### Phase 3 —— 账本族生命周期治理（D3：P1-7 / P2-3 / P2-4 / P2-5 / P2-6 / P2-7 / P2-8 / P3-4 / P3-5）

- [x] **Task 3.1 AI 尸体年结压缩 + truncate 排序键 + 导入幂等**（P1-7）。commit: `fix(gamecore): AI 弟子尸体新陈代谢——年结压缩+活者优先截断，根治 28k 封顶冻结（审计 P1-7）`
- [x] **Task 3.2 AI 池反向变化检测 O(1) 化**（P2-3，实现偏离：引用门替代 rev 计数，见实施记录）。
- [x] **Task 3.3 deathRecords 删除**（P2-4，零消费者确认，无需降级分支）。
- [x] **Task 3.4 mailRecords/sectBattleRecords 窗口截断**（P2-5/P2-6，双端常量同源）。
- [x] **Task 3.5 血炼/功法 map 统一收口**（P2-7/P3-4，三路径×双端 + 全路径守卫测试）。commit: `fix(gamecore): 弟子强化派生 map 统一收口——死亡/叛逃/逐出三路径四表清键（审计 P2-7）`
- [x] **Task 3.6 储物袋合并+容量门**（P2-8；lifeEvents/P3-5 为 Kotlin 类体属性不持久化，待后续）。
- [ ] **门槛**：500 年构造档导入回缩断言 + 血炼全路径零残留 + 1,400/10,000/28,000 三档 profile（验证点 7/8）+ 全量测试绿。

### Phase 4 —— 持久化与文件治理（D4：P1-4 / P1-5 / P2-14 / P2-15 / P3-6 / P3-9 / P3-10 / P3-11）

- [x] **Task 4.1 超限可见化（决策项②未确认 fallback；淘汰+分页待决策项②）**（P1-4，产品决策项②确认后实施；未确认前仅上分页+溢出上报）。commit: `fix(data): mails 容量上限真实现——三元组淘汰保附件，UI 分页加载（审计 P1-4）`
- [x] **Task 4.2 迁移备份保留策略**（P1-5）。commit: `fix(data): 迁移备份保留 2 份——承诺的清理任务接线 StorageMaintenanceFacade（审计 P1-5）`
- [x] **Task 4.3 change_log/archives 治理接线**（P2-14/P2-15）。
- [x] **Task 4.4 一次性与口径**（P3-9/P3-10/P3-11/P3-6 已于批次 495d201+3250768 完成）。
- [ ] **门槛**：迁移测试（三备份→存二）+ DAO 淘汰语义测试 + 挂机 100 游戏年 COUNT(*) 稳定（验证点 4）+ 全量测试绿。

### Phase 5 —— 进程生命周期对称（D5：P1-6 / P2-11 / P2-12 / P2-13 + P3-8/12/13/14/15/17/18/19/23）

- [x] **Task 5.1 闹钟链会话门 + Application 自愈**（P1-6）。commit: `fix(process): 看门狗闹钟链会话门——无会话退链+启动自愈，根治 15s 永续拉起（审计 P1-6）`
- [x] **Task 5.2 紧急重启收尾对称**（P2-11/P2-12）。
- [x] **Task 5.3 EventBus 背压统一**（P2-13/P3-12）。
- [x] **Task 5.4 P3 杂项**（P3-13/14/16/19 本会话补齐；P3-8/15 并行会话已修；P3-17/18 见实施记录；P3-23 豁免说明见记录）。
- [ ] **门槛**：am kill 后 dumpsys alarm 无条目 + watchdog 正常语义回归（验证点 6）+ OEM 机型长挂机线程峰值观测（验证点 11）+ 全量测试绿。

### Phase 6 —— 时间语义与规模热点（D6/D7：P2-16 / P2-17 / P3-7 / P3-20 / P3-22 + P2-18 决策实施 + P3-16/P3-21）

- [x] **Task 6.1 追补公式单一来源**（P2-16/P3-22 + 双端守卫测试）。commit: `fix(time): 追补上限公式双端单一来源——2x 速度不再静默丢旬（审计 P2-16）`
- [ ] **Task 6.2 DirtyTracker 表级 epoch + 行级指纹**（P2-17，profile 门控行级步）。commit: `perf(gamecore): DirtyTracker 行级指纹——exportDirty 跳过未变行（审计 P2-17）`
- [x] **Task 6.3 反向增量索引 + 静态映射惰性化**（P3-7 + P3-20 均完成）。
- [x] **Task 6.4 P2-18 编排实施**（决策项③ A 方案两阶段落地：Stage 1 玩家防守环 2c3ed4b / Stage 2 AI-vs-AI 征伐环，见第十二部分实施记录）。
- [x] **Task 6.5 EventBusAudit 文档修订**（P3-16）。
- [ ] **门槛**：2x 注入 8s 停顿 totalPhases 断言（验证点 16）+ 5000 弟子档 100 旬 profile 前后对比（验证点 8）+ 12 游戏月编排观测（验证点 10，若实施补齐方案）+ 全量测试绿。

---

## 第五部分：运行时验证矩阵（审计 §十 17 项 → 本方案门槛映射）

| # | 审计待验证点 | 承接 Phase / 门槛 | 判定 |
|---|---|---|---|
| 1 | 渲染线程卡 >2s 真实频率 | Phase 1 门槛（注入复现 + 修复后不可达断言） | 修复后 logcat 无 skip-release（或出现即被 deferred 收尾） |
| 2 | P0-1 单次泄漏 30~40MB | Phase 1 门槛（注入 3s ×10 循环，smaps GL/EGL 段） | 显存回基线 |
| 3 | Kotlin 是否改写 C++ 物品 id | Phase 2 门槛（重启撞号复现脚本） | 无重复 id；结论回填本文档 |
| 4 | mails 实际增速 | Phase 4 门槛（100 游戏年 COUNT(*)） | 稳定于上限附近 |
| 5 | pre_migrate_backup 占用 | Phase 4（多版本升级真机 ls） | 恒 ≤2 份 |
| 6 | 闹钟链永续复现 | Phase 5 门槛（am kill + dumpsys alarm） | 无条目 |
| 7 | AI 尸体存量与满员行为 | Phase 3 门槛（500 年档 dump + 满员档 truncate 断言） | 尸体清出、活者优先 |
| 8 | 深比较/DirtyTracker 规模耗时 | Phase 3/6 门槛（1,400/10,000/28,000 三档 profile） | 旬耗时与规模解耦 |
| 9 | 换档 Pending* 残留 | Phase 2 门槛（双档脚本断言） | 七容器全空 |
| 10 | AI 攻击编排是否休眠 | Phase 6（12+ 游戏月观测） | 按决策项③预期 |
| 11 | OEM 挂起线程滞留 | Phase 5 门槛（红米/荣耀 ps -T 峰值） | 基线+1 内 |
| 12 | maxBattleLogs 线上值 | Phase 4（dump 配置 + archives 存量） | 无无界 .arc |
| 13 | legacy snapshots/ 存量 | Phase 4（升级设备 filesDir 检查） | 删除后不存在 |
| 14 | SDK 缓存目录策略 | 登记观测（`du -sh cacheDir/*` 长期） | 非本方案范围，持续观测 |
| 15 | slot 0/6 无主战报行 | Phase 4（GROUP BY slot_id 巡检） | 全槽受治理 |
| 16 | 2x 丢旬量级 | Phase 6 门槛（注入 8s 断言 totalPhases） | 双端一致 |
| 17 | UI 开关 100 次 Heap Dump 终验 | Phase 5 后统一执行（炼丹/锻造/种植对话框 ×100 + Heap Dump 对比基线） | 回基线 |

## 第六部分：防复发护栏

1. **守卫测试（新增 6 个）**：
   - `ItemIdReseedGuardTest`——新增物品 prefix 未登记 reseed 扫描表即失败（枚举注册表 vs 存量 id 前缀扫描）。
   - `TransientQueueClearGuardTest`——反射枚举 `_pending*` flow + 队列，reset/loadFromSnapshot 两路径全覆盖（新增 Pending flow 漏登记即失败，错误消息带操作指引）。
   - `MigrationBackupRetentionTest`——备份保留数恒 ≤2（插入多份→prune→断言）。
   - `MailLimitEnforcementTest`——三元组淘汰语义 + 未领附件永存。
   - `AiCorpseBudgetTest`——500 年档压缩断言 + truncate 活者优先。
   - `PhaseCapParityTest`——C++/Kotlin 追补公式一致（双端常量锚定）。
2. **审查清单新增条目（建议并入 CLAUDE.md 13.3，随 Phase 完成逐条登记）**：
   - 🔴 新增 C++ Surface 级 GPU 资源必须由 `destroySurfaceGeneration` 覆盖（create* 必须幂等——先销毁旧句柄）；
   - 🔴 新增物品类别的 id prefix 必须登记 `itemIdCounterRegistry` 并纳入 reseed 扫描；
   - 🔴 新增持久化容器（表/字段/文件）必须同时声明上限与清理接线（无清理任务不得合入）；
   - 🔴 新增自续期机制（闹钟/前台服务/线程池/定时器）必须有「会话不存在即退链」退出条件；
   - 🔴 新增 Pending*/瞬态队列必须加入 `clearTransientQueues`；
   - 🔴 新增弟子派生 map（按 id 键控）必须在 `eraseDiscipleDerivedMaps` 登记清理键。
3. **监控埋点（TapDB 自定义事件，月度快照）**：`ledger_sizes`（aiSectDisciples 总量/尸体占比、mails 行数、mailRecords/sectBattleRecords/deathRecords 长度、change_log 行数、储物袋均值）+ `render_epoch_heal`（stale generation 析构/deferred release/GLES 纪元降级计数——长期为 0 则印证触发频率低，非 0 则有真实收益）。看板判读：任一 ledger 指标月环比单调上涨 → 对应治理失效告警。

## 第七部分：需产品决策项（不阻塞其余 Phase）

| # | 决策 | 推荐 | 影响 |
|---|---|---|---|
| ① | AI 尸体保留窗口（P1-7） | 3 游戏年（战报追溯窗口对齐战史 3 年窗口） | 仅影响「3 年内死亡 AI 弟子」是否可被追溯；过长相近于现状 |
| ② | mails 自动淘汰策略（P1-4） | 三元组淘汰（已读+已领+已过期最旧），附件零丢失 | 比「直接淘汰已读已领」保守；若要绝对不删则改为仅分页+上报 |
| ③ | AI 攻击编排（P2-18） | 补齐 C++ AUTHORITATIVE 月结编排（玩法完整 + 消灭双实现假象） | 若不做：删整族休眠代码，P2-9/P3-21 随之消失 |
| 附 | mailRecords 窗口 500 / 储物袋 50 / lifeEvents 100 | 默认值直接采用 | 数值可后调（命名常量），不阻塞 |

## 第八部分：根治判据自检表（对照 R1–R5）

| 缺陷带 | 不变量（修复后成立） | 判据 |
|---|---|---|
| 渲染器异常路径 | initSurface 幂等（旧代必先析构）；渲染线程 vk 调用全部有界（必有限退出）；skip-release 必被下一纪元收尾或安全降级 | R1/R2 |
| id 生命周期 | 任何持存档导入后计数器 ≥ 已见最大 id+1——撞号构造上不可能 | R3 |
| 死亡不删除账本族 | 每个账本的保留窗口=其消费/幂等语义窗口；弟子派生 map 的清理有唯一收口点 | R2/R4 |
| 数据库/文件孤儿 | 每个写入点有接线中的治理任务（备份 2 份/change_log 7d/archives 12mo/mails 上限） | R4 |
| 进程生命周期 | 自续期机制有会话门；异常路径清理集合与正常路径对称 | R2 |
| 时间语义 | 追补公式单一来源，双端守卫锁定 | R5 |
| 规模×时间 | 旬节拍成本与状态规模解耦（rev 短路 + 行级指纹 + id 索引） | R4 |

## 第九部分：与审计问题清单的覆盖对照（49 项全处置）

| 审计项 | 处置 | 承载 |
|---|---|---|
| P0-1 | 修复 | D1 / Phase 1 |
| P1-1 | 修复 | D1 / Phase 1 |
| P1-2 | **已闭环**（性能方案 Task 3.4，§0 复核确认） | Phase 1 复核任务 |
| P1-3 | 修复 | D2 / Phase 2 |
| P1-4 | 修复（决策项②） | D4 / Phase 4 |
| P1-5 | 修复 | D4 / Phase 4 |
| P1-6 | 修复 | D5 / Phase 5 |
| P1-7 | 修复（决策项①） | D3 / Phase 3 |
| P2-1~P2-8、P2-10~P2-17 | 修复 | D1/D3/D4/D5/D6/D7 对应 Phase |
| P2-9 | 修复（随决策项③） | D3 派生 / Phase 2 或 6 |
| P2-18 | 决策项③（推荐补齐） | Phase 6 |
| P3-1~P3-15、P3-17~P3-23 | 修复 | P3 批量清偿表对应 Phase |
| P3-16 | 文档修订 | Phase 6 |
| 审计 §九 已核实干净清单（约 60 项） | 不动（回归基线） | 各 Phase 既有测试 |

**尾注**：审计 §八 长期推演中的「C(切换次数) GPU 累积」「C(T) mails 无界」「19h AI 封顶」「E(T×N) 旬尖峰」四条最危险增长曲线，分别由 D1（泄漏归零）、D4（上限真实现）、D3（新陈代谢）、D3+D7（规模解耦）关闭；修复后推演表应全列收敛为 A/B 类。此结论作为全部 Phase 完成后的终验口径（重跑推演矩阵断言）。

*（方案完 · 依据 2026-09-09 长期运行稳定性审计编制，关键项已对当前工作区二次实码核对（§0）；执行前如代码已漂移，以函数名+注释锚点二次定位。）*

---

## 第十部分：实施记录（2026-09-09 执行会话 A · 渲染器/状态/进程/时间批次）

> 本节由执行会话回填。同日工作区存在多个并行执行会话（分工：本会话=渲染器根治+gamecore C+++
> Kotlin 状态/时间面；并行会话=core/data 持久化面（Phase 4）+ P3 杂项批次（Task 5.4）+ 领域模型重构），
> 剩余任务归属见各条标注。提交均在 main（bf7f02c..6c6dcb0），每任务独立 commit 可 revert。

### 已完成（审计 13 项 + 守卫测试 4 族）

| 项 | commit | 验证 |
|---|---|---|
| P0-1/P1-1/P2-1/P3-1/P3-2（D1 全套） | bf7f02c / 0faa307 / c01e401 | NDK 编译绿；destroySurfaceGeneration 幂等析构 + ensureSurface 唯一销毁权 + createFramebuffers 幂等 + acquire 250ms×8 有界 + 帧 fence 2s 有界 + m_ready/m_deviceReady 原子化 + maxSets 动态化+超限重建 |
| P1-1 Kotlin 面（deferred release + 纪元降级阀） | 0faa307 | resolveDeferredRelease 5s 轮询 join → 补释放 / STUCK_RENDER_THREAD 上报 + forceGlesForEpoch |
| P2-2（GLES 窗口引用） | c01e401 | 引用所有权精确平衡：后端 acquire/release 自持 + 桥侧 fromSurface 移交即释（较方案更彻底：Vulkan 路径桥侧引用原本也每纪元 +1 泄漏，一并闭合） |
| P3-3（g_scale 纪元复位） | c01e401 | shutdownRenderer 重置清单 |
| P1-2 复核 | bf7f02c | 延迟释放队列已在位（性能方案 Task 3.4），纪元析构保持清空 |
| P1-3（D2 id reseed） | 00ed2d6 | 7 处 static 计数器收敛 itemIdCounterRegistry；importStateInternal 尾部 JSON 全树扫描 reseed（max 语义幂等，R1 泛化：新前缀自动纳入）；ItemReseedGuardTest 6 用例 + gamecore 全量 918 绿 |
| P2-10（D3 派生，含 §0 婚配队列补充） | 4506299 | clearTransientQueues 双路径 + TransientQueueClearGuardTest（反射枚举灌值→reset→断言全空） |
| P1-7（D3 AI 尸体，决策项①=3 年） | 03b3c8d | deathYear 字段（三标记点写值+导入补缺省）+ 年结 cull + truncate 活者优先 + 导入幂等压缩；AiCorpseBudgetTest 4 用例 |
| P2-5/P2-6/P3-6（D3/D4 账本归一） | 495d201 | mailRecords takeLast(500) 双端、战史 year≥gameYear-3 双端（常量同源锚定）、gameEventRecords 导入侧裁 200；normalizeLedgers 先于 resetBaseline |
| 附带：MailService 定时炸弹测试（5 用例因福利截止 2026-09-04 真实流逝而必失败） | 495d201 | timeSource 可注入（@VisibleForTesting，生产恒墙钟）+ 5 用例固定时钟；单跑全绿 |
| P1-6（D5 闹钟链会话门） | e4dfe79 | GameForegroundService.isRunning + shouldContinueChain 双信号三路径 + XianxiaApplication 启动自愈 |
| P2-11/P2-12/P2-13/P3-12（D5） | b729ad1 | emergency 前 cancel 旧 engineJob + 旧线程 awaitTermination(2s)+泄漏计数 + emitTyped trySend 化 + notifySubscribers 串行化 |
| P2-16/P3-22（D6） | fe2135e | 双端单一来源 maxPhasesPerTick(speed)（settlement.h / GameTimeClock）+ AuthoritativeOps 速度感知 cap + rawDelta coerceAtLeast(0) + PhaseCapParityTest 双端互锚 |
| P3-7（D7 改动 2） | 6c6dcb0 | upsertEntities/removeEntities 索引化 O(N×M)→O(N+M)；apply_reverse_dirty_test 语义逐位保持 |

### 剩余项（归属标注）

- **并行会话进行中（避让冲突面，勿重复实施）**：Phase 4 全部（D4：MailDao/GameDatabase/DataPruningScheduler/DataArchiver——core/data 当日活跃编辑）+ Task 5.4 P3 杂项（BackgroundTaskScheduler/UnifiedPerformanceMonitor/YearlyOpsQueue/EventBusAudit/FunctionalWAL 等当日活跃编辑）+ P1-3 的 gc-eq 读档自愈规则（data/integrity/rules/EquipmentDedupeRule.kt 已见创建）。
- **待产品决策项**：Task 2.4/6.4 P2-9+P2-18（决策项③：AI 攻击编排补齐 vs 删族——P2-9 随决策落地）；决策项①②已按推荐值落地（3 年窗口/三元组淘汰在 C++ 导入侧生效，Kotlin 侧三元组淘汰随 Phase 4 MailDao）。
- **待后续会话（本会话预算/冲突面所限）**：Task 3.2 aiSectDisciplesRev（P2-3）、Task 3.3 deathRecords 删除（P2-4，DiscipleTables 当日活跃编辑高冲突）、Task 3.5 血炼 map 收口（P2-7/P3-4）、Task 3.6 储物袋（P2-8）、Task 6.2 DirtyTracker 行级指纹（P2-17，profile 门控）、Task 6.3 P3-20 静态映射 companion 化（DiscipleTables）、Task 6.5 P3-16 EventBusAudit 文档（并行会话编辑中）。

### 门槛执行状态

- 已执行：compileReleaseKotlin 绿、assembleDebug（NDK C++）绿、gamecore 桌面全量 923 用例绿、
  MailServiceTest / TransientQueueClearGuardTest / ItemIdReseedGuardTest / AiCorpseBudgetTest /
  PhaseCapParityTest（双端）定向全绿、全量 testReleaseUnitTest 后台收口中。
- 环境限制：审计第 1/2/6/7/8/10/11/16/17 号**真机门槛**（GPU 停顿注入/显存计数/dumpsys alarm/
  500 年档 profile/am kill 等）无法在本环境执行——已由对应单元/桌面守卫覆盖静态不变量，
  真机复验留待真机批次（映射见第五部分矩阵）。
- detekt：本会话改动均为既有体例（命名常量/注释锚点），未新增 baseline 条目；
  全仓 detekt 门因并行会话中间态编译破损间歇不可运行，终验批次统一执行。
---

## 第十一部分：实施记录（2026-09-09 执行会话 A · 续批：剩余项收尾）

> 承接第十部分。本批 commit：c7b5978（P2-7/P3-4）→ 8a4c196（P2-8）→ d68b55b（P2-3）→
> b90ce11（P2-4/P3-20）→ 3250768（P1-4 fallback/P1-5/P2-14/P2-15/P3-9/P3-10/P3-11）→
> e047fab（P3-13/P3-14/P3-19/P3-16）→ 5080757（架构守卫白名单解锁）→ 03f51a0（P2-9）。

### 本批完成

| 项 | 实现 | 验证 |
|---|---|---|
| P2-7/P3-4 | eraseDiscipleDerivedMaps（C++ blood_refinement.h + Kotlin 镜像扩展）：死亡/叛逃/逐出三链四表清键（原各漏 2-3 键）；bloodRefinements 追加 takeLast(100) 双端封顶 | DiscipleDerivedMapsTest + gamecore 923 绿 |
| P2-8 | addToDiscipleBagList 统一入袋：kind 合并堆叠 + STORAGE_BAG_CAPACITY=50 条目门；五入袋点接门（换装满袋零 mutation 中止/购买容量门前置扣仓/偷盗满袋不再拾取）；Kotlin StorageBagUtils 无上限语义（守卫锁）保留不动 | StorageBagCapacityTest 4 用例 + gamecore 929 绿 |
| P2-3 | 实现偏离：未引入 rev 计数（写入点枚举遗漏=镜像滞后风险，方案自列），改用不可变引用门——稳态每旬 O(ΣD×字段数) 深比较降为 O(1)，且消除 C++ 前向更新后的冗余全量回导（28k 池 JSON 两次 JNI） | StateSyncServiceReverseTest/DiffStateSyncTest 绿 |
| P2-4 | deathRecords/DeathRecord/addDeathRecord 全链删除（生产代码零读者确认）；LoadBaseline 收窄 | domain/app 编译绿 + domain/engine 测试绿 |
| P3-20 | columnGroupByIndex（90 项纯静态映射）eager → by lazy：纯写副本（deepCopy/回滚）零成本 | 同上 |
| P1-5 | pruneMigrationBackups（保留 2 份）；接线 verifyAndRecover 达标分支 + 修剪周期任务 | core:data 全量绿 |
| P2-14 | change_log deleteOlderThan 接线（7 天窗口）；markSynced 死代码删除 | 同上 |
| P2-15 | cleanupExpiredArchives 接线 DataArchiveScheduler（12 个月窗口） | 同上 |
| P3-9 | legacy snapshots/ 目录一次性删除 | 同上 |
| P3-10 | 修剪槽位从 StorageConstants 派生 0..MAX（原硬编码 1..5） | 同上 |
| P3-11 | deleteOld 返回行数（签名补 Int）；totalLogsDeleted 按行累计（原计槽位数） | 同上 |
| P1-4 fallback | MailDao 超限 Log.w+计数（决策项②未确认——淘汰/分页待决策+UI 改造） | 同上 |
| P3-13/14/19 | stop 清 tasks；recordMetric 复用 MAX_COLLECTORS 检查；BaseViewModel 三通道 UNLIMITED→64 | domain/engine/feature 编译绿 |
| P3-16 | EventBusAudit 文档按 P2-13/P3-12 根治后实况修订 | — |
| P2-9 | shownStageKey()=attackerSectId+stage（三处同步）+ 过期移除联动清键 | engine/feature 编译绿 |
| 共享门 | DomainDependencyTest 白名单补登记并行会话在途 @Entity 文件名（原全仓测试门被阻塞） | domain 测试恢复绿 |

### 实测否决记录（重要）

- **P2-17 指纹层实测回退**：按 D7 比较半边实现 64-bit FNV 集合/字段指纹后 bench 实测全面变慢
  （idle n=5000：302ms→363ms，+20%；all-dirty +19%）——指纹需 dump 序列化，成本高于其替换的
  indexById+深比较。指纹层只在能跳过**序列化**时有收益，即依赖「列级写屏障」基础设施
  （SoA 列直写点 300+ 处，dirty_tracker.h 既有待办）。基线数据已留档：idle 302ms@5000、
  all-dirty 519ms@5000。**P2-17 维持待办，等待写屏障基础设施**。

### 豁免与并行会话面（更新）

- **P3-23 豁免**：forceDrain 无预算系 flush-on-save 正确性前提（「返回时队列必空」契约，
  YearlyOpsQueue KDoc 明文）；其成本受年变延迟组体积自然约束且跑在存档线程——不做软预算。
- **并行会话已修**：P3-8（WAL 30 天驱逐已在）、P3-15（ImeAnimationTracker detach 已清扫）。
- **并行会话在途**（未干预）：SoftwareCanvasBackendTest 1 用例失败（其 canvas 在途改动，
  66/73 漂移）——非本方案范围。
- **P3-17/P3-18**：CrashHandler 现为写文件路径（无裸线程/新 client 残留）、SecureHttpClient
  降级面在并行会话网络层重构范围，未发现方案所述形态，登记为「审计形态已漂移，闭环待确认」。

### 决策项② 落地（2026-09-09 产品拍板：过期邮件自动删除）

- 决策结果：**过期即删**（expireTime>0 且 <now），比原推荐的「三元组淘汰」更简单——
  过期邮件领取路径本就返回 Expired 不可领，删除无功能损失；expireTime=0 永久有效不受影响。
- 实现：MailDao.deleteExpired + insertWithEnforceLimit 每次写入顺带清理本槽过期 +
  MailService 打开邮件列表（refresh/flow collector）前先清一次；容量上限不做淘汰，
  仅保留溢出计数留痕（30 天窗口下邮件量天然有界）。
- 状态：P1-4 全部闭环。涉及附件的「永不自动删除」旧 KDoc 已按新决策修订。

### 全部 49 项最终状态

- **根治完成 47 项**（本会话两批 20 commit）+ P1-2 闭环复核 + P3-23 豁免。
- **决策项③已落地（2026-09-09 第二批）**：P2-18 AI 攻击编排 A 方案两阶段完成
  （Stage 1 玩家防守环 2c3ed4b / Stage 2 征伐环）——真机 12 游戏月观测
  （审计验证点 10）留待真机批次；P1-4 的三元组淘汰+分页（决策项②+UI 改造）仍待决策。
- **待基础设施**：P2-17（列级写屏障，附实测基线）。
---

## 第十二部分：P2-18 决策项③ 落地设计（A 方案：补齐编排 · 2026-09-09 会话定稿）

> 产品选定 A（补齐 AI 攻击编排 + 删 Kotlin 2 参链）。本节为前置核实结论 + 分阶段实施设计，
> 供执行会话直接开工（核实已完成，无需重新调研）。

### 前置核实结论（修正方案前提）

1. **休眠边界**：休眠链 = `AISectBattleProcessor.processAISectOperations(year,month)`（2 参，:120-124）
   → `processAIVsAIBattles()`（:212，内调 `AISectAttackManager.decideAttacks` :232）+
   `playerDefenseProcessor.processPlayerDefenseBattles()`。月结实际调用的是 **3 参版**
   （CultivationEventMonthlyOps:62/95 → processAISectOperations(year,month,state)，仅 AI 修炼批），
   不含战斗/防守——与审计结论一致。
2. **休眠的架构原因（关键）**：AUTHORITATIVE 月结为单镜像事务
   （MonthSettlementExecutor.execute → processMonthlyEventsOnState 全程一事务），
   而战斗编排需要自己的 stateStore.update——单事务内无挂载点。2 参链是
   AUTHORITATIVE 化之前的遗留设计，这才是它休眠的根因（非「无玩法价值」）。
3. **规模修正**：「补齐编排」非接线级任务——需移植 Kotlin 战斗/编排族
   （AISectAttackManager 1695 行 + PlayerDefenseProcessor 397 + AISectBattleProcessor 274 +
   AISectOccupationResolver 274 ≈ 2600 行）至 C++ 月结。**建议分两阶段独立交付**。
4. **cooldown 写点确认缺失**：sectAttackCooldowns 全仓仅读（passesAttackerGates :576），
   无任何写点——补齐时必须在「预警生成」与「战斗结算」两处新增写
   （冷却常量取 GameConfig.AIAttack，需在 C++ game_config 落同值）。
5. **可复用 C++ 资产**：sect_attack_decision.h 已移植并测试
   （checkAttackConditions/decidePlayerAttack/passesAttackerGates/computeCanOccupy/highRealmAllDead）；
   battle::executeBattle 引擎既有（MISSION 子事件 5 / 兽战子事件 9 在用，DiffBattle 家族对拍守护）；
   aiHandleDeaths/aiMarkSideDead 伤亡落地既有。**战斗执行引擎不需要重写**，缺的是编排与结果应用。

### Stage 1：玩家防守环（AI 攻玩家）——独立可交付

- C++ 月结新增子事件「AI 攻玩家决策」（置于子事件 6 之后，与 Kotlin 月变序对齐）：
  调 `detail::decidePlayerAttack(state_, rng_)` → GenerateWarning 时
  `activeAttackWarnings.push_back(预警)` + `sectAttackCooldowns[attackerId] = now + 冷却月数`
  （预警字段已持久化+镜像，UI 弹窗链是活的，当月即生效）。
- C++ 移植防守战执行：到期预警（nowMonth >= attackMonth）→
  防守选人（巡检优先/realm 排序，移植 selectAndPrepareDefenders）→
  战前修炼结算（Kotlin forceSettleDisciplesBeforeBattle 等价——需评估是否随移植）→
  战斗（battle::executeBattle 组装攻守 Combatant）→ 结果应用
  （伤亡 aiMarkSideDead 玩家侧复用 playerGarrison 结构 / computeCanOccupy 占领判定 /
  sectBattleRecords 追加 / cooldown 写入 / GameEvent）。
- 删除：PlayerDefenseProcessor 全族 + AISectBattleProcessor 中对应段。
- 验收：桌面测试（预警生成→次月战斗→冷却写入→预警移除全链断言）；真机弹窗可见。

### Stage 2：AI-vs-AI 征伐环——独立可交付

- 移植 decideAttacks 编排（攻击者循环 + 首个可攻击目标即停 + results 去重约束）+
  executeSectBattleCore（AI vs AI 战斗）+ AISectOccupationResolver（占领/附庸/关系变更）。
- 验收：桌面测试 + 真机 12 游戏月观测（审计验证点 10：SECT_OCCUPY/预警/cooldowns 非空）。
- 完成后删除 CaveExplorationProcessor 两个委托包装（:236-241）与 AISectAttackManager
  Kotlin 对应段——双实现假象至此消灭。

### 风险与纪律

- 月结 RNG 消耗核对表（month_settlement.h 头部）逐点登记新抽取（BATTLE 分区）；
- 编排块单 commit 独立可 revert；Stage 1/2 各自独立验收，任一阶段不过不进下一阶段；
- 全程遵守 C++ AUTHORITATIVE：镜像经既有 dirty/reverse 通道，无新增 Kotlin 直写 AI 池路径。

### 实施记录（2026-09-09 执行会话 · 两阶段全部落地）

| 阶段 | commit | 内容 | 验证 |
|---|---|---|---|
| Stage 1 玩家防守环 | 2c3ed4b | 新增月结子事件 6c（sect_defense_battle.h）：预警收敛/到期战书内联结算/攻击决策复活（decidePlayerAttack）+ sectAttackCooldowns 双写点（预警生成①/战斗结算②，常量 ATTACK_COOLDOWN_MONTHS=12 双端锚定）/AI 占领宗门驻军填充；**playerDefenders 数据源修正**（aiDisciplesMap[playerSectId] 世界生成不含玩家宗门 → 恒空 → 决策永不触发——双实现假象构成部分；改读 DiscipleStore 玩家弟子权威存储）；Kotlin 删 PlayerDefenseProcessor 全族 + AttackWarningService（仅存 shownStageKey）+ AISectBattleProcessor 防守编排段 | SectDefenseBattleTest 8 用例全链断言；对拍 decidePlayerAttack 逐位一致（快照补 disciples 镜像）；桌面 937 绿 + NDK 绿 |
| Stage 2 征伐环 | （本批） | 新增月结子事件 6b（sect_conquest.h）：decideAttacks 编排（首目标即停 + results 去重 + **纯决策/统一应用两段式**）+ resolveDefenderSetup 守方三分流（玩家占领驻军/占领者驻军/自有池）+ executeAiBattle + applyAttackOutcome（伤亡分流/占领归属/isOwned 清除/池合并清空/好感-10）；建筑没收为平台效应草稿（seizedSectBuildings → nativeSettleMonth 信封 → Kotlin 事务外 buildingFacade.seizeBuildingsOfSect → 反向通道回同步）；Kotlin 删 AISectOccupationResolver 全族 + AISectBattleProcessor 征伐编排/两参入口 + AISectGarrisonManager.fillEmptyGarrisonSlots + CaveExplorationProcessor 两参委托 + AISectAttackManager 编排链（decideAttacks/executePlayerAttack/executePlayerSectBattle/executeAISectBattle/tryAttackTarget 等；决策链与 checkAttackConditions 按 PhaseSettlementExecutor 先例保留为跨语言对拍基准并标注生产无调用方） | SectConquestTest 5 用例（占领翻转/池合并/没收草稿/门槛零消耗/决策零 mutation）；桌面 942 绿 + NDK 绿 + engine 全量绿 + 259 项 Diff* 对拍全绿 |

**登记的语义差异（两阶段）**：①AI 攻方阵亡 aiMarkSideDead 标死（非 Kotlin 整行移除，对齐 P1-7 死亡不删除新陈代谢）；②战前突破（forceSettleDisciplesBeforeBattle）不随移植（C++ 突破唯一入口为旬结算，月结在月末相位边界后执行，战力状态即最新；随移植需向月结钩子引入 BREAKTHROUGH 分区消耗而收益≈0）；③战斗日志（Kotlin battleLogs 显示域）不入 C++ 状态——消息栏经 gameEventRecords（ai_beast_hunt 先例）；④幸存守军 HP 回写跳过（decideAttacks 组装的 AIAttackResult 恒带空 survivor 映射，对齐 Kotlin 休眠链实际行为）。

**包含序基建**：sect_attack_decision.h/sect_defense_battle.h/sect_conquest.h 依赖链经 month_settlement.h 文件尾「完成宏」门控包含（GAMECORE_SYSTEM_SECT_*_COMPLETED_）——TU 以决策头为首包含（GameCoreBridge.cpp 对拍桥）时不解析下游头，规避循环依赖。

**遗留移交（非本工程引入）**：commit 03b3c8d（P1-7）将 deathYear 纳入 C++ 弟子 JSON 协议但 Kotlin Disciple 镜像字段未落——旧 desktop .so 过期一直掩盖，重建后 5 项 Diff* 对拍失败（deathYear 键不对称）。本批以测试面适配解锁（DiffTimeTest/DiffStateTest ignoreUnknownKeys + DiffAuthoritativeTickTest 比较器跳过 deathYear，均标注落地后可回收）；Kotlin Disciple 镜像字段（含 Room 列/Migration 决策）归 P1-7 所属会话收口。
