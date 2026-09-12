# 长期运行稳定性审计报告（无限增长 · 资源泄漏 · 生命周期泄漏）

> 审计类型：全项目静态审计（只读，未修改任何代码）
> 审计依据：《写作(3).docx》审计任务书（38 项要求）
> 审计基线：磁盘当前工作区状态（含未提交改动；HEAD=145c64f）。排除了 `.worktrees/`、`build/`、`node_modules/`、`gamecore/third_party/googletest` 与一切测试代码（测试仅作"预期行为证据"引用）。
> 审计方式：7 路并行子系统深挖（C++ 核心 / 渲染器与 GPU / 游戏时间×规模 / 持久化与文件 / 引擎生命周期与并发 / 领域状态与 UI / AI·战斗·规模复杂度）+ 主会话交叉分析与汇总。所有结论均定位到 文件:行号 实码证据。
> 行号说明：所有 行号 均为审计时刻磁盘状态实测，后续改动可能导致漂移。

---

## 一、总体结论

**本项目的生命周期纪律整体显著优于平均水平**：绝大多数容器、线程、协程、监听器、缓存、文件都有经代码证实的真实上限或释放闭环（见第九节"已核实干净清单"，约 60 项）。任务书担心的"每保存一次→新建文件→永不删旧"模式在主存档路径上**不存在**（`.sav/.bak` 固定名双缓冲就地覆盖）；游戏事件（cap 200）、年报（cap 100）、战报（cap 100/5000）、AI 弟子池（cap 1000/宗）等关键容器均有硬上限。

**但审计确认了 49 项真实问题（1 × P0、7 × P1、18 × P2、23 × P3）**，集中于五个结构性缺陷带：

1. **渲染器异常路径**（P0 通道）：正常的前后台/Surface 循环完全干净，但"渲染线程 2 秒内停不下来就放弃释放"这一设计（NativeSurfaceView.kt:826-836），叠加 C++ 侧 `initSurface`/`createSwapchain`/`createVertexBuffer` 全部缺少"重建前先销毁旧句柄"的幂等防御、`destroyTexture` 又是空实现——三者构成同一触发条件（GPU 卡顿 + Surface 销毁，恰是本项目历史上原生崩溃的场景）下单次 30~40MB 的 GPU 资源泄漏通道，并派生 use-after-free 竞态。
2. **"死亡不删除"的账本族**：AI 弟子尸体（只标 `isAlive=false`）、`_deathRecords`、`bloodRefinementPctTotals`、`mailRecords`、`sectBattleRecords`、`mails` 表——全部随时间/行为单调增长，其中多项进入存档与每旬深比较路径。
3. **数据库与文件侧的孤儿**：`.pre_migrate_backup.v{N}` 迁移备份永不删除（注释承诺的清理任务不存在）、`change_log` 表清理代码是死代码、`mails` 表"容量上限 1000"是空实现。
4. **进程级 vs 存档级生命周期不对称**：C++ 物品 id 计数器是函数级 `static`（进程生命周期），而 id 进入存档（存档生命周期）——重启后必然撞号，导致物品合并/覆盖式状态腐化。
5. **看门狗闹钟链无退出条件**：进程异常死亡后每 15 秒永久拉起整个进程（`AlarmWatchdogReceiver` 无条件自续期）。

**对任务书核心问题的直接回答**：游戏在**正常路径**下长时间运行、反复切后台、反复开关 UI 不会出现资源累积（多条路径已逐行推演 100 次循环）；**异常路径**（GPU 卡顿、进程被 OEM 杀死、紧急重启、换档）存在 5 条会随次数/时间累积的通道，其中渲染器复用路径长期运行必然导向 GPU 耗尽或原生崩溃。

**iOS**：零代码（已暂缓，见 `docs/adr/ios-migration-plan.md`），本审计不适用，无 iOS 侧结论。

---

## 二、审计范围

| 子系统 | 审计深挖范围 | 对应任务书章节 |
|---|---|---|
| A. C++ 核心 | `android/app/src/main/cpp/gamecore`（51 个 system 头文件、ECS、DiscipleStore、json_codec、GameCoreBridge.cpp） | 总体原则、内存、Entity 生命周期、对象池、静态/单例、Debug 数据 |
| B. 渲染器与 GPU | `cpp/` 顶层（VulkanBackend 2745 行、GlesBackend、Rhi.h、TextureAtlas、SpriteBatcher、KtxLoader、SkyBackground、NativeBridge 27 个全局）+ Kotlin 侧 NativeSurfaceView/AndroidSurfaceProvider/软渲染 | GPU 资源泄漏、Renderer 生命周期、地图渲染数据、资源加载卸载、Surface 重建 |
| C. 游戏时间×规模 | engine_loop.h、time_system.h、phase/month/year_settlement.h、GameTimeClock.kt、残留执行器、YearlyOpsQueue、归档/修剪调度 | 游戏时间（重点）、离线收益、随规模增长、时间×规模（非常重要） |
| D. 持久化与文件 | Room（GameDatabase+50 迁移）、FunctionalWAL、SaveFileManager、DataArchiver、崩溃日志、云存档、ChangeTracker | 存档、数据库、历史数据、文件系统、日志、云存档重试 |
| E. 引擎生命周期与并发 | GameEngineCore 线程模型与紧急重启、EventBus、协程/定时器、队列重试、网络、前台服务/闹钟/唤醒锁、前后台循环、Debug 采样、SDK 初始化 | Event/Listener、Timer/Coroutine、线程、队列重试、网络、移动端生命周期、异常路径 |
| F. 领域状态与 UI | EntityStore/DiscipleTables、GameStateStoreImpl/SlotCache/各缓存、幽灵对象自愈规则、Pending* 瞬态、约 20 个对话框与 16 处 DisposableEffect、对象池、读档一致性 | Entity 生命周期、缓存、UI 生命周期、对象池、读档 |
| G. AI·战斗·规模 | ai_sect_*.h/battle*.h、AISectAttackManager 等 Kotlin 双实现、aiSectDisciples 生命周期、O(N²) 扫描、战斗历史 | AI/NPC、随玩家规模增长、战斗历史、时间×规模 AI 维度 |

**任务书专项问题的结论**：
- **离线收益（任务书第十节）**：项目**无离线结算设计**（离线时间不产生收益、不补偿，`GameTimeClock.kt:25-28` 暂停/保存/加载一律 `consumeDeadTime`）。后台追补经 `phasesToAdvance.coerceAtMost(MAX_PHASES_PER_TICK)` 封顶，超限**丢弃**而非累积——方向正确；但 2x 速度下 Kotlin 消费侧 cap 未随速度缩放，存在静默丢旬（见 P2-16）。
- **游戏时间系统（第九节"重点"）**：存在"每时间单位创建数据"的路径，其中事件/年报/战报/年累计器全部有 cap（干净）；**邮件**（P1-4）、**血炼记录**（P2-7）无 cap。时间推进使用 `elapsedRealtime`（单调，不受用户改系统时间影响，`GameTimeClock.kt:25-28`），时间跳变安全设计正确。
- **重复开关 UI 100 次后监听器数量（第十四节）**：EventBus 生产代码 0 注册/0 注销（grep 全仓证实，`GameEvents.kt:245,320-332`），主要监听面是 ViewModel scope 内的 flow 收集（VM onCleared 级联取消）——推演 100 次开关后监听器数量回到基线。约 20 个对话框未发现向单例注册监听器/位图的路径；建议运行时 Heap Dump 终验（见第十一节）。

---

## 三、问题总数统计

| 等级 | 数量 | 判定口径（任务书第三十三节） |
|---|---|---|
| **P0** | **1** | 长期运行必然导向崩溃/资源耗尽/不可玩（条件触发频率需运行时确认，触发条件即历史崩溃场景） |
| **P1** | **7** | 长时间运行明显卡顿/内存持续上涨/存储膨胀/后台耗电，或状态腐化 |
| **P2** | **18** | 长期增长、短期轻微；或低频/病理条件下累积；或数据污染 |
| **P3** | **23** | 轻微、纯理论、当前不可达（缺防御上限）、或非增长类缺陷 |
| **合计** | **49** | 另有 1 项跨层口径澄清（usedRedeemCodes/watchedItemIds/pendingTraitAdds，见第九节） |

问题分布：渲染器 8 · C++ 核心 4 · 时间/结算 3 · 持久化/文件 7 · 并发/生命周期 10 · 领域状态/UI 7 · AI/规模 2 · 跨层 3（含合并）。

---

## 四、P0 问题清单

### P0-1 渲染器实例复用路径整体泄漏旧一代全部 GPU 资源（单次 ≈30~40MB，含 UAF 竞态同根）

- 【问题名称】initSurface 覆盖式重建，旧 swapchain/VBO/纹理/命令池/同步对象全量泄漏
- 【风险等级】P0（触发条件＝渲染线程在 Surface 销毁时卡 >2s，恰为 docs 记载的历史崩溃场景；若真机证实高频则必然导向 GPU 耗尽/驱动 OOM）
- 【文件路径】`android/app/src/main/cpp/VulkanBackend.cpp`；`android/feature/game/src/main/java/com/xianxia/sect/ui/game/sect/NativeSurfaceView.kt`；`android/app/src/main/cpp/NativeBridge.cpp`
- 【类/函数】VulkanBackend::initSurface / createSwapchain / createVertexBuffer / createCommandObjects / createSynchronization；NativeSurfaceView.stopRenderThread；NativeBridge.initRenderer
- 【变量/容器/资源】m_surface、m_swapchain、m_swapchainViews、m_framebuffers、m_commandPool、m_imageAvailable/m_renderFinished/m_inFlightFences、m_vertexBuffers[3]/m_vertexMemories[3]、m_whiteTexture、m_pipeline 族、m_descriptorPool、m_textures（旧图集+地面纹理 17~22MB）、m_nativeWindow
- 【产生位置】跳过释放入口 NativeSurfaceView.kt:826-836（`stopRenderThread` 2s join 截止后**跳过 backend release**）；复用入口 NativeBridge.cpp:277-286（`isDeviceReady()` 为真直接 `initSurface`，不 delete）；覆盖点 VulkanBackend.cpp:598/666/680/691/734/1659/1681-1695/1607-1642（3×VBO ≈11.25MB）/1214-1422/95-209/261-262
- 【增长路径】Surface 销毁时渲染线程卡在 vkWaitForFences/vkAcquireNextImageKHR/vkQueuePresentKHR 超 2s → release 被跳过 → 下一 epoch `initRenderer` 见 `isDeviceReady()==true` → **同一实例**上重跑 initSurface → 全部成员句柄被覆盖，旧句柄无人持有。第二条触发链：VulkanInit 在销毁后才成功，`handleVulkanInitSuccess` 被 epoch 守卫丢弃（NativeSurfaceView.kt:1012）→ activeBackend 恒 null → release 永不执行
- 【释放/删除位置】未找到（仅"当前"句柄会被 shutdown 销毁；被覆盖的旧句柄直到进程结束无人引用）
- 【为什么会增长】initSurface 无"已存在即先销毁"幂等防御；各 create* 均不先 destroy 旧句柄
- 【增长条件】GPU 卡顿/驱动挂起 + Surface 销毁；或 VulkanInit 迟到成功。反复切后台（放置游戏挂机常态）放大暴露概率
- 【长期影响】每次 ≈11.25MB VBO + 17~22MB 纹理 + swapchain/命令池/同步对象/ANativeWindow 强引用/VkSurfaceKHR → GPU 显存/地址空间耗尽、驱动 OOM
- 【是否确定】是（两条代码路径均直接读码确认）；触发频率需运行时统计【需要运行时验证】
- 【建议验证方法】GPU 卡顿注入下循环开关屏幕 50 次，观察 `dumpsys meminfo` 的 EGL/GL mtrack 与厂商显存计数（Adreno `/sys/kernel/debug/kgsl/`）；logcat 过滤 "did not stop after 2s deadline"
- 【建议解决方向】initSurface 入口幂等防御（若 m_surface/m_swapchain/m_vertexBuffers 非空先分段销毁——把 shutdown 的分段清理拆为可复用函数）；或 initRenderer 遇"上一代未 shutdown"时强制 delete 重建。低成本、可同时消除 P1-1/P1-2 的放大效应

---

## 五、P1 问题清单

### P1-1 跳过 release 的 epoch 中，残留旧 RenderThread 与新 initSurface 的 use-after-free 竞态

- 【问题名称】旧渲染线程苏醒后在已重建的 g_renderer 上继续提交（随机 SIGSEGV）
- 【风险等级】P1（崩溃风险，与 P0-1 同根同触发条件）
- 【文件路径】`android/feature/game/src/main/java/com/xianxia/sect/ui/game/sect/NativeSurfaceView.kt`；`android/app/src/main/cpp/VulkanBackend.cpp`
- 【类/函数】NativeSurfaceView.stopRenderThread / VulkanBackend.submitFrame
- 【变量/容器/资源】g_renderer、m_swapchain、m_inFlightFences
- 【产生位置】NativeSurfaceView.kt:826-831（超时后 "skipping backend release" 继续）；VulkanBackend.cpp:2466-2495（fence 等待/检查/重置窗口）
- 【增长路径】旧线程卡 vk 调用 → 2s 超时放弃 join → 新 epoch 在同一 g_renderer 上 initSurface 重建 swapchain → 旧线程苏醒后用悬垂句柄继续 vkResetFences/vkQueueSubmit；或 prewarmDevice（GameActivity.kt:326）在旧线程存活时 `delete g_renderer`
- 【释放/删除位置】不适用
- 【为什么会增长】"避免 UAF"的手段（不释放）把 UAF 推迟到重建时刻
- 【增长条件】同 P0-1
- 【长期影响】随机原生崩溃——与 docs/render-thread crash strategy 等三篇文档记载的历史崩溃模式一致
- 【是否确定】是（竞态窗口代码事实存在）；实际触发率需运行时统计【需要运行时验证】
- 【建议验证方法】GPU 卡顿注入 + 快速开关屏幕压力循环，观察 native crash 栈是否落在 submitFrame
- 【建议解决方向】submitFrame 的 vk 调用可中止化（fence 带超时 + m_ready 双检）；或跳过 release 时将 g_renderer 标记"待回收"，下次 initRenderer 前 poll/join 旧线程

### P1-2 VulkanBackend::destroyTexture 是空实现，纹理注册表只能随整个 Renderer 销毁

- 【问题名称】destroyTexture 无操作，m_textures 只进不出
- 【风险等级】P1（当前为"潜伏的结构性缺陷"：生产路径每 epoch 恰好整体重建故日常不触发；一旦出现"地图/场景切换不换 renderer"的需求立即暴露，并放大 P0-1 的泄漏）
- 【文件路径】`android/app/src/main/cpp/VulkanBackend.cpp`
- 【类/函数】VulkanBackend::destroyTexture
- 【变量/容器/资源】m_textures（vector<VkImage+VkImageView+VkDeviceMemory+VkSampler+descSet>，VulkanBackend.h:217）
- 【产生位置】VulkanBackend.cpp:2394-2396（函数体仅一行注释"纹理在 shutdown 时统一清理"）
- 【增长路径】任何上传后不再使用的纹理永久驻留至 shutdown；P0-1 复用路径下每 epoch 新上传 17~22MB 纹理叠加
- 【释放/删除位置】仅 VulkanBackend.cpp:350-356（shutdown 遍历销毁）
- 【为什么会增长】Rhi.h:96 定义了接口、GlesBackend.cpp:386-394 有真实实现，Vulkan 实现为空；无按 id 回收能力
- 【增长条件】纹理生命周期 < 渲染器实例生命周期的任何场景
- 【长期影响】使 P0-1 泄漏从"设备级资源"扩大到"纹理注册表"；vector 容量单调增长
- 【是否确定】是（函数体为空是硬事实）
- 【建议验证方法】代码评审即可确认；运行时在复用 epoch 后 log `m_textures.size()`
- 【建议解决方向】实现按 id 查找 + vkDestroy 四件套 + erase；至少在 initSurface 复用路径先清空 m_textures

### P1-3 C++ 物品 id 计数器进程级 vs id 存档级不对称——重启后撞号、状态腐化

- 【问题名称】函数级 static id 计数器重启归零，与存档中既有 gc-* id 冲突
- 【风险等级】P1（物品丢失/重复、按 id 去重失效、upsert 互相覆盖，长期趋向不可玩）
- 【文件路径】`android/app/src/main/cpp/gamecore/include/gamecore/system/inventory.h`（另 6 处同模式）
- 【类/函数】StackableItemStore::generateNewId、nextItemId（inventory.h:464-467、544-547）、merchant_settlement.h:54-57、recruit_settlement.h:63-67、year_settlement.h:589-593、ai_sect_recruit.h:82-86、secret_realm_settlement.h:65-68
- 【变量/容器/资源】`static uint64_t counter`（函数局部静态）→ "gc-pill-N" 等 id 进入 state.pills/manualStacks/equipmentInstances 并经 json_codec 全量持久化（json_codec.cpp:1298-1316）
- 【产生位置】任意物品生成（生产/任务/秘境/商人/招募）
- 【增长路径】生成物品 → 存档 → 杀进程 → 读档（importStateJson 不复位任何计数器，game_core.cpp:388-420）→ 再生成同类物品 → 与存档中存活条目同 id
- 【释放/删除位置】未找到（对照：弟子 id 为状态派生 max+1，无此问题，recruit_settlement.h:375-382）
- 【为什么会增长】计数器生命周期=进程，id 生命周期=存档，两者不对称
- 【增长条件】任意一次"C++ 生成过物品 → 进程重启 → 读档 → 再生成"
- 【长期影响】重复 id 导致 removePill 按 id 删错堆叠（inventory.h:956-971）、StackableItemStore keyIndex 混挂（inventory.h:451-453）、DirtyTracker/upsertEntities 按 id 视为同一实体互相覆盖（game_core.cpp:25-37）、Kotlin 镜像物品合并丢失
- 【是否确定】是（机制完全由读码确认）；仅"Kotlin 是否改写 id"无法静态排除【需要运行时验证】
- 【建议验证方法】生产 1 颗丹药 → 存档 → 重启进程 → 读档 → 再生产 → 检查 `state.pills` 重复 id 与 exportDirty upsert 行为
- 【建议解决方向】importStateJson 成功后扫描全部实体集合，重置各计数器为"已见最大 N+1"；或改用状态派生 id（同 nextDiscipleId）

### P1-4 邮件表"容量上限"是空实现，邮件永久累积且 UI 全表加载

- 【问题名称】insertWithEnforceLimit 忽略 maxLimit=1000，只增不删（两路审计独立确认）
- 【风险等级】P1（放置游戏挂机常态下长期存储膨胀 + 邮件 UI 随历史线性变慢）
- 【文件路径】`android/core/data/src/main/java/com/xianxia/sect/data/local/MailDao.kt`；`android/core/engine/src/main/java/com/xianxia/sect/core/engine/service/MailService.kt`
- 【类/函数】MailDao.insertWithEnforceLimit / MailService.processMonthlyMails / MailRepositoryImpl
- 【变量/容器/资源】Room `mails` 表（每 slot）
- 【产生位置】MailDao.kt:22-27（注释明言"只增不删"，maxLimit 参数整体忽略）；调用点 MailService.kt:210/247；溢出邮件自动转邮件 OverflowMailSender.kt:351-353 → MailRepositoryImpl.kt:139-150
- 【增长路径】每月网络拉取 + 内建邮件 + 仓库/储物袋溢出自动发信（生产型玩家高频）→ 每封一行永久
- 【释放/删除位置】仅玩家手动 `deleteAllReadAndClaimed`（MailDao.kt:52-53）；DataPruningScheduler 只修剪 battleLogs，不碰 mails——**无任何自动清理**
- 【为什么会增长】创建速度（月度拉取+溢出自动发信）> 销毁速度（纯手动）；容量参数形同虚设
- 【增长条件】长跑 + 玩家不手动"删除已读"。按 1x=72s/游戏年，重度挂机数小时即数百游戏月
- 【长期影响】DB 单调膨胀；`getActiveMails` 全表 ORDER BY 加载全部行进内存（MailDao.kt:13-14），邮件 UI 随历史线性恶化
- 【是否确定】是（DAO/Repository 两层显式忽略 limit，注释自证设计如此）
- 【建议验证方法】自动化挂机 100 游戏年（约 2 小时），`SELECT COUNT(*) FROM mails`
- 【建议解决方向】真实现 insertWithEnforceLimit（淘汰已读已领最旧）；或给溢出邮件加合并/过期机制
- 【备注】这是任务书第七节"只 INSERT 不 DELETE"模式的现实实例，但**属有意设计**（防附件丢失，MailDao.kt:9-12 注释）——需产品决策而非直接"修复"

### P1-5 数据库迁移前备份 `.pre_migrate_backup.v{N}` 只创建不删除

- 【问题名称】每次 DB 版本升级复制一份全库备份，永不清理
- 【风险等级】P1（老玩家历经 N 次升级残留 N 份全库副本，可达 GB 级）
- 【文件路径】`android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt`
- 【类/函数】GameDatabase.Companion.backupDatabaseForMigration / findVersionedBackup
- 【变量/容器/资源】`{db}.pre_migrate_backup.v{N}` 文件族
- 【产生位置】GameDatabase.kt:419（全量复制 426-430）。DB 版本当前 v50，近期几乎每版递增（v43→v50 每版一个迁移文件）→ 每次升级触发一次
- 【增长路径】升级 App → DB 版本递增 → 复制整库为 `.pre_migrate_backup.v{N}` → 永久保留
- 【释放/删除位置】**未找到**——GameDatabase.kt:604-605 注释称"由维护任务按保留期清理"，但该维护任务不存在（DataPruningScheduler/DataArchiveScheduler 均不涉及；全仓 grep `pre_migrate` 仅创建/扫描处）
- 【为什么会增长】注释承诺的清理链路未实现（"多版本保留供降级恢复"决策无配套治理）
- 【增长条件】用户每次升级 App（每次发版几乎必触发）
- 【长期影响】单文件≈当时 DB 全量（100MB 量级配置）；50 次版本升级后残留数 GB；`findVersionedBackup`（GameDatabase.kt:777-783）还扫描 v2..v49 全部版本
- 【是否确定】是——创建点唯一、删除点经全仓库检索不存在
- 【建议验证方法】多次升级的测试机 `ls filesDir/databases/ | grep pre_migrate` 统计份数×大小
- 【建议解决方向】迁移成功（verifyAndRecoverDatabase 版本达标分支，GameDatabase.kt:607）后仅保留最近 1~2 个版本，删除更旧版本

### P1-6 看门狗闹钟链无条件自续期——进程异常死亡后每 15s 永久拉起整个进程

- 【问题名称】AlarmWatchdogReceiver.onReceive 无论判定结果如何都重新调度自身，无"游戏不在运行"退出条件
- 【风险等级】P1（后台耗电 + 每 15s 全量 Application 初始化的 CPU/IO 开销；用户感知为"杀不掉的后台常驻"）
- 【文件路径】`android/app/src/main/java/com/xianxia/sect/core/util/AlarmWatchdogReceiver.kt`
- 【类/函数】AlarmWatchdogReceiver.onReceive / scheduleAlarm
- 【变量/容器/资源】AlarmManager `setExactAndAllowWhileIdle` 链式精确闹钟（15s 间隔）
- 【产生位置】AlarmWatchdogReceiver.kt:199（onReceive 末尾无条件 `scheduleAlarm(context)`；166/177 两条异常早退分支也各自续期）
- 【增长路径】进程在后台被 OEM 杀死（Service.onDestroy 不执行，GameForegroundService.kt:148 的 cancelAlarm 未跑）→ 闹钟仍留 AlarmManager → 触发 → 系统拉起进程（完整重跑 XianxiaApplication.onCreate：MMKV/精灵注册/监控启动）→ 判定循环未运行 → emergencyRestart 被 D-07 状态机以 phase=STOPPED 拒绝（GameEngineCore.kt:1397-1399）→ **再次 scheduleAlarm** → 链条永续至重启/强停
- 【释放/删除位置】仅 GameForegroundService.kt:148；游戏未运行期间无任何停止续链分支——未找到
- 【为什么会增长】续链是"游戏运行中看门狗"设计，但 onReceive 不检查游戏是否处于会话中
- 【增长条件】任意一次后台 OOM kill/OEM 杀后台/native crash 未走 Service.onDestroy。SCHEDULE_EXACT_ALARM 权限由 GameActivity.onResume 主动引导授予（授予即命中）
- 【长期影响】电池/CPU/日志噪音；与"放置挂机"用户群体（前台服务常驻）高度重叠
- 【是否确定】是（无条件续链与 LoopStalled 判据均为实读代码）
- 【建议验证方法】`adb shell am kill` 杀后台进程 → `dumpsys alarm | grep xianxia` + logcat 观察每 15s 一次 Application onCreate
- 【建议解决方向】onReceive 检测"loop 未运行且无前台会话"时不续链并主动 cancelAlarm；或 Application 初始化早期检测"无游戏会话"时取消闹钟

### P1-7 AI 弟子"尸体"只标死不删除——列表单调爬向 28,000 条上限

- 【问题名称】aiSectDisciples 死亡条目仅置 `isAlive=false`，实际删除几乎只有"玩家攻占"一条路径
- 【风险等级】P1（存档膨胀 + 每旬深比较放大 + 满员后宗门停止"新陈代谢"的功能退化）
- 【文件路径】`android/core/engine/src/main/java/com/xianxia/sect/core/exploration/AISectBeastAttackProcessor.kt`（另 4 处 Kotlin + C++ 双侧）
- 【类/函数】handleAIDeaths、EncounterBattleService.applyDeathsForSect（:371-385 注释明言"标记死亡但保留在列表中"）、PatrolBattleSystem.markAiDeaths（:757-767）、SecretRealmService.markAiTeamDefeated（:705-716）、CaveExplorationProcessor.processSectDisciplesAging（:289-300 年度老化标死）、C++ ai_sect_ops.h:630-659
- 【变量/容器/资源】`GameData.aiSectDisciples: Map<String, List<Disciple>>`（28 个 AI 宗门，每条目是完整 Disciple 结构，随存档持久化）
- 【产生位置】妖兽讨伐/巡逻冲突/秘境/遭遇战/老化死亡 → 只写 `isAlive=false`；每 3 年每宗招募 +1~5（ai_sect_recruit.h:341-356）→ 条目数单调递增（净 +约 1/宗/年）
- 【释放/删除位置】仅 AISectOccupationResolver.kt:87-89（AI-vs-AI 战死过滤——但该编排当前休眠，见 P2-18）与 GameEngineBattleOps.kt:206-215（玩家攻占 removeDeadDefenders）。其余未找到
- 【为什么会增长】创建速度恒定 > 销毁速度≈0；1000/宗上限只约束招募路径的截断排序，不清理尸体
- 【增长条件】随游戏年月线性：按 1x=72s/游戏年，约 950 游戏年（≈19 小时连续 1x，或放置挂机数天）逼近 1000/宗 → 28×1000=28,000 完整弟子
- 【长期影响】数十 MB 堤内存 + 存档 Room TEXT 列全量重写膨胀 + 每月变化时全量 JSON 两次过 JNI + `truncateToLimit` 按 base stats 排序把高属性尸体保留、挤掉新活弟子（AISectDiscipleManager.kt:603-609）→ 宗门战力冻结
- 【是否确定】是——五处"标死保留"与两处删除均逐行核实，Kotlin/C++ 双侧一致
- 【建议验证方法】构造 500 年存档 dump 各宗 size 与 `isAlive=false` 占比；对比存档体积曲线
- 【建议解决方向】月结/年结统一压缩（移除尸体或仅保留 N 代内战死者）；`truncateToLimit` 排序键纳入 isAlive（活者优先）；或招募合并前先剔除全死条目

---

## 六、P2 问题清单

### P2-1 resize() 每次 swapchain 重建泄漏 1 个 VkSurfaceKHR
- 【风险等级】P2 ｜ `VulkanBackend.cpp` ｜ createSwapchain:598 无条件 `vkCreateAndroidSurfaceKHR` 覆盖 m_surface；destroySwapchain:716-726 不销毁 surface，仅 shutdown:385 销毁最后一个 ｜ 增长路径：每次真实 resize（旋转/分屏/折叠机）泄漏 1 个 ｜ 为什么：createSwapchain 承担了"建 surface"与"建 swapchain"两个职责 ｜ 长期影响：100 次旋转累积 100 个，部分驱动连带 BufferQueue ｜ 确定：是 ｜ 验证：旋转 20 次统计 vkCreate/vkDestroySurface 计数差 ｜ 方向：surface 创建移出 createSwapchain，或入口先 `vkDestroySurfaceKHR`（标准 swapchain 重建本不需要新 surface）

### P2-2 GlesBackend 从不 ANativeWindow_release——每 GLES epoch 泄漏 1 个窗口强引用（确定性）
- 【风险等级】P2 ｜ `GlesBackend.cpp` / `NativeBridge.cpp:254` ｜ shutdown（GlesBackend.cpp:297）只置 `m_window=nullptr` 无 release；全仓 `ANativeWindow_release` 仅 VulkanBackend.cpp:391 一处 ｜ GLES 是低端机降级主路径，每个 surface epoch（含完全正常的销毁/重建）泄漏 1 个引用，100 次切后台=100 个 ｜ 泄漏的 ANativeWindow 使 Surface/BufferQueue native 侧无法释放 ｜ 确定：是 ｜ 验证：GLES 设备循环开关屏幕，`dumpsys SurfaceFlinger` 观察 layer/buffer 计数 ｜ 方向：shutdown 中补 `ANativeWindow_release`

### P2-3 StateSyncService 每旬对整张 aiSectDisciples 深比较；变化月全量 JSON 两次过 JNI（时间×规模热点）
- 【风险等级】P2 ｜ `android/core/engine/.../nativebridge/StateSyncService.kt:463-467` ｜ 深比较无法短路（Map<String,List<Disciple>> 每弟子数十字段），每旬（gameData 变更旬）执行（GameEngineCoreAuthoritativeOps.kt:57-83）｜ 成本随 P1-7 的 ΣD 线性放大：N=28,000 时每旬数千万次字段比较、每月数十 MB 字符串峰值 ｜ 确定：是（绝对耗时需实测）【需要运行时验证】｜ 验证：1,400/10,000/28,000 三档 profile buildReverseEnvelope ｜ 方向：为 aiSectDisciples 引入版本号/世代计数，整数比较替代深比较

### P2-4 `_deathRecords` 无上限且每事务全量复制——零消费者的纯开销
- 【风险等级】P2 ｜ `android/core/domain/.../state/DiscipleTables.kt:101,120,1567,1725,1660` ｜ 每死亡/年变剔除 +1 条；仅 clear()（读档/新档）释放；deepCopy:1660 每笔 update 事务整表复制 ｜ 全库 grep 证实 feature/data 层零消费者、不持久化 ｜ update 频率约 0.5-2 次/s → 分配与 GC 压力随累计死亡数线性上涨（1x 下 1 小时≈50 游戏年的弟子轮转量）｜ 确定：是 ｜ 验证：埋点 deathRecords.size 长会话曲线 + Allocation Tracker ｜ 方向：deepCopy 改共享不可变引用 O(1)；或加上限；或直接删除该字段

### P2-5 GameData.mailRecords 只进不出，随存档/.bak/云档三处持久化
- 【风险等级】P2 ｜ `MailService.kt:364,457`（唯一写点均为追加）｜ 作为"删除已读后防重复发放"的幂等账本设计，无 cap、无按时间裁剪（全库无 takeLast/drop/clear）｜ 增长与领取邮件总数线性；进 Room 行 + .sav + .bak + 云存档（TapTap 10MB 上限，TapCloudSaveManager.kt:47,226-231——极端老档云上传可能失败）｜ 且每次 `gameData.copy()`（每事务）复制整表 → O(事务数×累计领取数) 隐性乘法 ｜ 确定：是 ｜ 验证：读 >1 年老档对比 mailRecords.size 与存档字节数 ｜ 方向：保留近 N 条；过期邮件（已过期+已落 Room 领取状态）的记录按时间截断

### P2-6 sectBattleRecords 只增不裁，消费语义"近 3 年"却不裁剪
- 【风险等级】P2 ｜ `GameEngineBattleOps.kt:302-312`（唯一写点）｜ 消费方 C++ countRecentBattleRecords（month_settlement.h:1942-1949）、AISectAttackManager.kt:447 均只读"近 3 年"窗口，窗口外永不删除（Kotlin/C++ 均无 erase）｜ 增长：玩家每场宗门战 +1；月度附庸检查与每次 AI 决策全量扫描退化为 O(总战史) ｜ 确定：是 ｜ 验证：连打 500 场后观察列表长度与月结耗时 ｜ 方向：追加时按 `year >= gameYear-3` 裁剪（消费窗口就是 3 年，双端同步改）

### P2-7 血炼三 map 幽灵键：bloodRefinementPctTotals 全库无任何清理；叛逃路径三 map 全不清；manualProficiencies 死亡不清
- 【风险等级】P2 ｜ C++ `month_settlement.h:340,343-344`、`year_settlement.h:1690-1691`（死亡仅清 BonusTotals+bloodRefinements，**不清 PctTotals**）；叛逃 desertDiscipleCleanup（month_settlement.h:1227-1310）三个 map 都不清；Kotlin 侧同构确认：DiscipleLifecycleProcessor.kt:194-196,307-309 不删 pctTotals、DiscipleService.kt:166-221（逐出）两者都不删；pctTotals 的 remove 全库未找到 ｜ 每个完成过血炼的弟子（无论后续死亡/被逐出/寿终）永久贡献 1 条记录并入存档（json_codec.cpp:1303/1307）｜ 确定：是 ｜ 验证：招募→血炼→开除→读档，检查 map 是否仍含该 id ｜ 方向：在死亡/叛逃/逐出统一收口处 erase 三 map + manualProficiencies[id]；bloodRefinements[id] 追加处加 takeLast 上限

### P2-8 弟子储物袋 storageBagItems 无容量上限，多路径无条件 push 且不合并堆叠
- 【风险等级】P2 ｜ C++ `auto_gear.h:334,607`、`disciple_purchase.h:595/645/665`、`month_settlement.h:1674`（偷盗）｜ 换装旧装备/自动购买/偷盗全部直接 push_back，无堆叠合并、无容量检查（对照：仓库有 capacityPerBuilding 上限）｜ 释放仅靠消耗/装备化/死亡清袋——存在闭环但无主动上限（month_settlement.h:1682 旁注释自证"容量无上限"）｜ 每弟子袋列表随 T 线性增长 → 存档与镜像 diff 膨胀 ｜ 确定：是 ｜ 验证：开启智能购买+低忠诚弟子挂机 10 游戏年对比袋长度 ｜ 方向：入袋走 StackableItemStore 合并（Kotlin 侧已有此模式 GameEngineSectLevelOps.kt:272）+ 袋容量

### P2-9 GameData.shownWarningStageIds 以一次性 UUID 为键只增不减（持久化）
- 【风险等级】P2（潜在——AI 攻击编排当前休眠 P2-18，实际增长率≈0；代码复活即恢复增长）｜ `GameEngineDiplomacyOps.kt:16-20`（唯一写点）｜ 键构造 AttackWarningService.kt:32-39（每次预警新 UUID）+ GameOverlayHost.kt:249（"warningId:STAGE"），全库无删除点 ｜ UI 消费为 O(R) 线性包含判断 ｜ 对照：GameViewModel.acknowledgedBeastAttackIds 有剪枝（:516-521），此字段无对称治理 ｜ 确定：是 ｜ 方向：改为 attackerSectId+stage 为键（≤29×阶段数），或预警过期时同步清理

### P2-10 换档读档不清瞬态队列——跨档幽灵弹窗 + 婚配按数字 id 匹配跨档碰撞（数据污染）
- 【风险等级】P2（含数据污染风险，建议按高优先处理）｜ `android/app/.../core/state/GameStateStoreImpl.kt` ｜ `loadFromSnapshot`(:1459) 是 `reset`(:1739) 清理面的子集：_pendingBeastAttacksFlow(:306)、_pendingMarriageProposalsFlow(:307)、_pendingBattleResultFlow(:299)、_pendingBattleRewardCardsFlow(:304)、_rewardCardQueueFlow(:305)、notificationQueue(:303) 六个容器 load 路径**未找到清理**，仅 reset:1763-1769 清空 ｜ 游戏内从 A 档读 B 档（SaveLoadViewModel.kt:893 证实路径存在）→ 旧档婚配提议/妖兽预警滞留新档；婚配 accept（GameEngine.kt:264-294）按数字 id 匹配弟子，各档弟子 id 都从 1 起分配，**跨档 id 必然碰撞**——旧提议会在新档为两个无关弟子结成道侣 ｜ 确定：是（loadFromSnapshot 全文核对无这些字段写入）｜ 验证：slot1 触发婚配提议不开弹窗→读 slot2→断言 _pendingMarriageProposalsFlow.value ｜ 方向：提取 reset:1763-1769 为 clearTransientQueues()，在 loadFromSnapshot 锁内同位置调用

### P2-11 performEmergencyRestart 不 cancel 旧 engineJob——悬挂 SupervisorJob+孤儿协程随重启次数累积
- 【风险等级】P2 ｜ `GameEngineCore.kt:1420-1421`（对照 shutdown:1070 有 cancel，emergency 路径漏配对）｜ 每次紧急重启：旧 scope 上在途协程失去归属；旧 executor 已 shutdown 但挂起其上的续体永不恢复，被悬挂 SupervisorJob 持有至进程结束 ｜ 触发：看门狗 60s 限频（:438）/主线程 HealthCheck/闹钟三路 ｜ 单次量小（0-2 个协程）故 P2 ｜ 确定：是 ｜ 验证：重启前后打印旧 engineJob children 数；heap dump 观察 SupervisorJobImpl 实例数 ｜ 方向：emergency 步骤 3 前补 `engineJob.cancel()`（与 shutdown 一致）

### P2-12 紧急重启旧 GameEngine-Thread 只 shutdown 不 join——被 OEM 挂起的非守护线程可跨多次重启滞留
- 【风险等级】P2 ｜ `GameEngineCore.kt:1313-1331`（:1325-1328 shutdown 无 awaitTermination/join）｜ 每次 restart 新建 1 个单线程池；旧线程若被 OEM 电源管理挂起（触发重启的前提场景，HyperOS/MagicOS 等），滞留至苏醒发现队列空才退出 ｜ 极端"永久挂起"设备上 100 次重启=100 个滞留线程（每线程 1MB 栈保留）｜ 确定：是（幅度依赖 OEM 行为）【需要运行时验证】｜ 验证：`ps -T | grep -c GameEngine-Thread` 在红米/荣耀长挂机观察峰值 ｜ 方向：shutdown 后 awaitTermination(短超时)；滞留超阈值告警上报

### P2-13 EventBus.emitTyped 通道饱和时背压转化为无上限挂起协程
- 【风险等级】P2（当前事件量极低——月/年级别，短期无感；防御性缺陷）｜ `android/core/domain/.../event/GameEvents.kt:334-338` ｜ emit/emitSync 走 trySend+丢弃计数兜底，emitTyped 却 `scope.launch { channel.send(event) }`——满通道时每个事件挂起一个协程，无上限无计数 ｜ 唯一生产调用方 SpiritStoneWallet.flushPendingEvents（月变/灵石事务后批量 flush）｜ 确定：是（无界性确定）｜ 验证：256 满通道下连续 emitTyped 10 万次断言挂起协程数 ｜ 方向：emitTyped 也走 trySend+丢弃计数

### P2-14 change_log 表每次保存 INSERT 一行、永不删除（清理代码是死代码）
- 【风险等级】P2 ｜ `android/core/data/.../incremental/ChangeLogPersistence.kt` / `ChangeLogDao.kt` ｜ 写入点 StorageEngine.kt:1228-1232（每次成功保存）；清理 `cleanupOldLogs`（:62-72）只删 `synced=1` 的行，而 `markSynced()`（ChangeLogDao.kt:30-31）与 `deleteOlderThan()`（:36-37）经全仓 grep **无任何生产调用方** → 所有行 synced=0 永久滞留 ｜ 增长与保存次数线性（每天数十次×数年=数万行）｜ 确定：是 ｜ 验证：`SELECT COUNT(*), COUNT(*) FROM change_log WHERE synced=0` ｜ 方向：DataPruningScheduler 定期 deleteOlderThan(7d)；或保存改 Upsert 同 recordId

### P2-15 archives/*.arc 与 archive_index.pb 的 12 个月清理链路未接线（条件触发）
- 【风险等级】P2 ｜ `android/core/data/.../archive/DataArchiver.kt` ｜ `cleanupExpiredArchives(retentionMonths=12)`（:267-289）经全仓 grep 无生产调用方；每次归档事件新建 `.arc` 文件（:190-201,363-369），索引追加（:403-408）｜ 当前默认配置很难触发（内存态战报 takeLast(100)，归档触发阈值 maxBattleLogs=1000，SaveLimitsConfig.kt:60）；但阈值可运行时调至 5000（:63），一旦调高即激活无界路径 ｜ 确定：是（代码层）；是否实际触发需运行时确认 ｜ 验证：检查 filesDir/archives/ 是否有存量 + dump maxBattleLogs 运行时值 ｜ 方向：将 cleanupExpiredArchives 接入 StorageMaintenanceFacade 定时任务（StorageMaintenanceFacade.kt:22-28）

### P2-16 2x 速度追补上限双端不对称——单 tick 超过 3 旬的追补被静默丢弃（时间漂移）
- 【风险等级】P2（时间语义缺陷，非增长）｜ C++ `engine_loop.h:145`（phaseCap=3×max(s,1)，2x 单 tick 计划最多 6 旬且**已从累积器扣减**）vs Kotlin `GameEngineCoreAuthoritativeOps.kt:54`（`coerceAtMost(3)` 不随速度缩放）｜ 2x + 挂起 ≥6s → 计划 6 旬只执行 3 旬，其余既未执行也未 refund → 2x 长跑游戏时间相对墙钟持续变慢（每 incident 最多丢 3 旬）｜ 确定：是（两端 cap 公式逐行读实，1x 完全一致）｜ 验证：2x 下注入 8s 帧停顿断言 totalPhases ｜ 方向：Kotlin 消费侧 cap 改 `3*speed`，或 native 侧 plan 直接按 3 封顶

### P2-17 DirtyTracker 每次导出全量序列化 + 常驻基线 JSON 树（O(状态) 成本随规模×时间上涨）
- 【风险等级】P2（不泄漏，CPU/分配成本问题）｜ `android/app/src/main/cpp/gamecore/src/dirty_tracker.cpp:53-59,66-67,126`、dirty_tracker.h:62 ｜ "增量导出"实现为：常驻 baselineJson_（≈1 份全状态 JSON）+ 每次导出再序列化一份全量 + 深比较；每旬 exportDirty 对 5000 弟子 SoA+仓库+AI 池完整编码 ｜ 内存有界（基线 move 替换:126），成本随状态规模（弟子×124 列）线性上涨 ｜ 确定：是（成本结构确定；毫秒级影响需实测）【需要运行时验证】｜ 验证：5000 弟子档位连续 100 旬测 exportDirtyJson 单次耗时/分配 ｜ 方向：列级写屏障（头文件 dirty_tracker.h:33-35 已登记为既定待办）或行 hash 跳过未变行

### P2-18 AI 攻击编排（AI-vs-AI 攻打 + 攻玩家预警/防守战）在双实现中均无生产调用方——休眠代码
- 【风险等级】P2（功能缺失/双实现假象；直接决定 P2-9 等路径"是否真的执行"）｜ `AISectBattleProcessor.kt:120-126`（2 参版编排）｜ 全仓 git grep：2 参版唯一委托 CaveExplorationProcessor.kt:237-238 无生产调用方；Kotlin 月结回退只调 3 参版（CultivationEventMonthlyOps.kt:62,95）；C++ AUTHORITATIVE 月结子事件 1-16（month_settlement.h:1979-2046）同样没有 decideAttacks 循环；sect_attack_decision.h 仅经测试桥 GameCoreJni.cpp:1110-1113 可达；**sectAttackCooldowns 全仓库只有读没有写** ｜ 影响：decideAttacks/预警/executePlayerAttack/驻防轮换的触发面消失——这既是功能缺失，也意味着 P2-9（UUID 键）等"休眠增长点"暂不增长 ｜ 确定：是（静态）；不能排除动态调用【需要运行时验证】｜ 验证：跑 10+ 游戏月观察 AI 发起的 SECT_OCCUPY/预警弹窗/sectAttackCooldowns 非空 ｜ 方向：要么在 C++ 月结补编排，要么删除 Kotlin 2 参编排避免假象；补 sectAttackCooldowns 写点或删字段

---

## 七、P3 问题清单

> 每条 15 字段压缩呈现：名称 ｜ 位置(文件:行号) ｜ 机制与增长路径 ｜ 释放位置 ｜ 长期影响 ｜ 确定 ｜ 方向。

| # | 问题 | 位置 | 机制/增长 | 释放 | 影响 | 确定 | 方向 |
|---|---|---|---|---|---|---|---|
| P3-1 | setRenderScale 每次调用泄漏 3 个 swapchain framebuffer | VulkanBackend.cpp:734,924-926 | 清晰度切换/热控→重建 framebuffers 直接覆盖无销毁；resize 路径不泄漏（先 destroySwapchain），此路径**未找到释放** | 仅 destroySwapchain/shutdown | 低频累积，驱动侧旧 FB 引用 | 是 | setRenderScale 跳过 createFramebuffers（FB 不依赖 scale）或先销毁旧值 |
| P3-2 | 描述符池 maxSets=16 硬上限，超限静默白纹（容量缺陷，反向的"无最大容量适配"） | VulkanBackend.cpp:1260-1266,1448,2603-2614 | 未来纹理 >15 张时第 16 张起 descSet 恒空→白纹回归；当前每 epoch 仅 2 张安全 | 池销毁自动回收（1486-1493） | 表现为花屏而非泄漏 | 是 | 按 m_textures.capacity 动态定 maxSets 或失败时扩容重建 |
| P3-3 | shutdownRenderer 遗漏重置 g_scale（残留状态，非泄漏） | NativeBridge.cpp:77,561,322-359 | 重置清单覆盖 proj/视口/热控/flags/fade/crop/sky，遗漏 g_scale | 未找到 | 新 epoch 首个 setCamera 前单帧级视觉异常 | 是 | 补 `g_scale = 1.0f` |
| P3-4 | manualProficiencies 死亡路径不清（叛逃路径有清，不对称） | year_settlement.h:1679-1715（死亡链无该 erase）；清理仅 month_settlement.h:1304 | 弟子死亡后 map 键滞留入存档（json_codec.cpp:1306） | 仅叛逃 | 小条目缓慢膨胀 | 是 | 并入 P2-7 的统一收口 |
| P3-5 | lifeEvents 单弟子存活期无上限逐年追加 | DiscipleLifecycleManager.kt:98-105、DiscipleFacadeImpl.kt:245、YearSettlementResidualExecutor.kt:106-110 | 每年 1-3 条/弟子；DiscipleTables.kt:479 注释自认"逐年增长线性恶化"；该列脏时 toList 深拷贝（ComponentTable.kt:754） | 弟子被剔除时随行删除（DiscipleTables.kt:1531） | 高境界长寿弟子字符串膨胀+偶发 churn | 是 | 软上限（保留近 100 条） |
| P3-6 | gameEventRecords 导入侧不裁剪（生成侧 cap 200 正确） | json_codec.cpp:1394（GC_FROM 无裁剪）；cap 在 settlement_detail.h:121-125 | >200 条的旧档/篡改档在下一次事件前不被压缩 | 下次 recordGameEvent 才裁 | 轻微；异常档一次性放大 | 是 | 导入后统一裁到 200 |
| P3-7 | applyReverseDirty 实体 upsert O(N×M) 线性扫描 | game_core.cpp:24-48 | 每条反向增量对整个集合 find_if；仓库物品可达数千 | 不适用（CPU） | 月结后大批量回写延迟尖峰 | 是 | id→index 临时索引 |
| P3-8 | FunctionalWAL 异常路径 activeTransactions/txnLocks 条目滞留至进程重启；checkpoint 永久保留滞留 BEGIN 条目 | FunctionalWAL.kt:483-489,510-516,744-754,820-826 | commit 异常跳过 txnLocks.remove；recover 注册"仅登记供监控"无驱逐；30 天保留只限新注册 | 仅 clear()（无生产调用方）与进程重启 | 条目极小（30-60B/条），量级低 | 是 | recover 注册加时间戳驱逐；commit 失败补 abort |
| P3-9 | 遗留 snapshots/ 目录无清理代码（能力 2026-08-01 已移除） | StorageConstants.kt:68（SNAPSHOT_DIR_NAME 全仓零引用） | 旧版本升级用户的一次性残留（旧上限单 50MB/总 200MB） | 未找到 | 一次性，不再增长 | 否（是否存在残留需运行时确认） | 启动时一次性删除该目录 |
| P3-10 | 修剪/归档调度硬编码 slot 1..5，槽 0/6 不受治理 | DataPruningScheduler.kt:20、DataArchiveScheduler.kt:23（合法槽 0..6，SaveFileManager.kt:385） | 若 slot 0/6（云存档）存在战报行则永不过期 | 不适用 | 理论性防御缺口 | 否（缺口确定，数据是否存在需查） | slotIds 从 StorageConstants 派生 |
| P3-11 | DataPruningScheduler 统计口径错误 | DataPruningScheduler.kt:127 | `totalLogsDeleted++` 计的是槽位数而非删除行数 | 不适用 | 仅监控数据失真 | 是 | 累加 deleteOld 返回值 |
| P3-12 | notifySubscribers 每订阅者每事件 launch 无背压 | GameEvents.kt:355-365 | **当前全仓生产 0 个订阅者、0 处 subscribe**——路径不可达 | 各自完成 | 纯防御性缺陷 | 是（不可达） | 未来接入订阅者时改 trySend+计数 |
| P3-13 | BackgroundTaskScheduler.register 只增不减，cleanup 不清 tasks | BackgroundTaskScheduler.kt:21,30-32；GameMonitorManager.kt:208-215 只 stop 不清 | 当前仅 onCreate 一次性注册 6 个（isInitialized 守卫）安全；未来反复 initialize/cleanup 将任务翻倍 | 无 | 当前不可达 | 是 | cleanup 中 clear tasks |
| P3-14 | UnifiedPerformanceMonitor.recordMetric 绕过 MAX_COLLECTORS=100 上限 | UnifiedPerformanceMonitor.kt:85-91（未复用 :76-79 检查） | 每样本内部有界 1000（PerformanceMetrics.kt:44-52）；当前注册名固定 6 个，recordMetric 无调用者 | — | 当前不可达 | 是 | getOrPut 前复用上限检查 |
| P3-15 | ImeAnimationTracker.windows 残留弱引用条目 | ImeAnimationTracker.kt:47,97-99,130-135 | Dialog onDispose 丢失场景残留已死 WeakReference 至下一次任意 detach | 惰性清理 | 条目极小 | 是 | detach 时全表清扫 |
| P3-16 | EventBusAudit.kt 审计文档与代码严重不符 | EventBusAudit.kt:44-100,120-124 | 声称的 DiplomacyService/EconomySubsystem 订阅、"GameEngineCore 全事件 collect"实测均不存在 | — | 不影响运行，但系统性误导后续审计 | 是 | 修订文档 |
| P3-17 | CrashHandler.tryUploadCrashLog 每次崩溃新建 OkHttpClient+裸线程 | CrashHandler.kt:157-181 | 上传内容 take(8000) 有界、日志文件 MAX_CRASH_LOGS=5 轮换→无累积 | 崩溃即进程退出 | 纯理论 | 是 | 复用 SecureHttpClient |
| P3-18 | SecureHttpClient Debug 降级路径每次固定失败新建 OkHttpClient | SecureHttpClient.kt:578-584（BuildConfig.DEBUG 门控 :533） | 临时 client 连接池/线程依赖 GC | GC | Debug-only | 是 | — |
| P3-19 | BaseViewModel 三个事件 Channel.UNLIMITED 无背压 | BaseViewModel.kt:17-42 | 无收集者期间（主菜单）trySend 无界积压；静态无法证明存在持续 error 源 | 收集者挂载即排空 | 理论性 | 否 | 换 SharedFlow(DROP_OLDEST) 或 Channel(64) |
| P3-20 | DiscipleTables 每事务常量重分配：90 组件表对象+90 ref+65 项映射 | DiscipleTables.kt:377-380,539-630；GameStateStoreImpl.kt:1249-1255 | columnGroupByIndex 是纯静态数据却按实例重建；0.5-2 事务/s 稳定年轻代压力（与 P2-4 叠加） | 旧事务缓冲可 GC | 非增长，常量 churn | 是 | 静态映射移入 companion |
| P3-21 | tryNativeCheckAttackConditions 每次(攻击者×目标)全量序列化玩家驻军 JSON | AISectAttackManager.kt:1011-1034 | 休眠编排复活后 O(A×N)/月 | — | 纯理论（编排休眠） | 是 | garrisonJson 提升到 decideAttacks 层复用 |
| P3-22 | rawDelta 负值理论边界（elapsedRealtime 仅整机重启归零，进程必然已死） | GameTimeClock.kt:153；C++ 镜像 engine_loop.h:135-159 | 若发生，累积变负留存至后续 delta 补偿 | 后续正 delta 回补 | 无实际影响 | 否 | `rawDelta.coerceAtLeast(0)` |
| P3-23 | YearlyOpsQueue 无最大长度声明（实际三重兜底有界：30ms/tick drain+跨月 forceDrain+读档 clear） | YearlyOpsQueue.kt:26,84-101；CultivationEventProcessor.kt:104,121-148 | 理论上 op 秒级阻塞可积压，也被下月 forceDrain 强制清空（代价单帧长卡） | drain/clear | 无累积 | 是（有界） | 如求稳妥 forceDrain 加软预算 |

---

## 八、长期运行推演模型（任务书第三十一节）

游戏时间换算（1x 速度）：1 旬(phase)=2s → 1 月=6s → **1 游戏年=72s**；连续挂机 1 小时 ≈ 50 游戏年。前台服务（GameForegroundService）+ 唤醒锁使循环可在后台持续运行——以下推演按"放置挂机"最坏常态。

| # | 指标 | 10 分钟 | 1 小时 | 24 小时 | 7 天 | 30 天 | 1 年 | 分类（A固定/B有上限/C线性/D随规模/E乘法/F指数/G无法确认） |
|---|---|---|---|---|---|---|---|---|
| 1 | C++ 核心内存（状态容器） | 稳定 | 稳定 | 稳定 | 稳定 | 稳定 | 稳定 | **B**（事件 200/年报 100/仓库预算/ECS freelist 全部闭环） |
| 2 | GPU 资源（正常路径） | 基线 | 基线 | 基线 | 基线 | 基线 | 基线 | **B**（每 epoch shutdownRenderer 全量释放重建） |
| 3 | GPU 资源（异常路径 P0-1） | ≈基线 | 触发即 +30~40MB/次 | 触发即累积 | 耗尽风险 | — | — | **C(切换次数)**，触发频率 G【需要运行时验证】 |
| 4 | Android 堆（缓存/快照） | 稳定 | 稳定 | 缓涨（deathRecords churn） | 缓涨 | 缓涨 | 明显 | **B+C 混合**（缓存 retainAll/LRU 有界；P2-4 churn 随轮转线性） |
| 5 | AI 弟子条目（含尸体，P1-7） | +约 230 | +约 1,400 | **触及 28,000 封顶（约 19h）** | 封顶 | 封顶 | 封顶 | **C(T)→B(硬上限)**；封顶后功能退化（新陈代谢停止） |
| 6 | mails 表（P1-4） | 数十~百行 | 数百 | 数千 | 数千+ | 持续 | 无界 | **C(T×邮件率)**【无法证明存在上限】 |
| 7 | 存档体积 | 微涨 | 微涨 | 缓涨（AI 尸体+mailRecords+储物袋） | 缓涨 | 缓涨 | 可观 | **C(T)**（多项 P2 合力；弟子/战报/事件各项本身有上限） |
| 8 | DB 体积（除 mails） | 稳定 | 稳定 | change_log 缓涨（P2-14） | 缓涨 | 缓涨 | 缓涨 | **B+C**（battle_logs 500+180 天归档有界；change_log/save 计数线性） |
| 9 | 文件数 | 固定 | 固定 | 固定+升级时 +1（P1-5） | 固定+ | 固定+ | 随发版数 | **B + C(升级次数)**（.sav/.bak 固定名、crash≤5、.tmp 清理均干净） |
| 10 | 日志落盘 | 0 | 0 | 0 | 0 | 0 | 0 | **A**（Kotlin/C++ 均仅 logcat，不写文件） |
| 11 | 线程数 | 基线 | 基线 | OEM 病理时随紧急重启次数上探（P2-12） | — | — | — | **B（正常）/ C(重启次数)（病理）** |
| 12 | 定时器/协程 | 基线 | 基线 | 基线 | 基线 | 基线 | 基线 | **B**（对称启停+viewModelScope；emitTyped 饱和路径除外 P2-13） |
| 13 | 事件/任务队列 | 有界 | 有界 | 有界 | 有界 | 有界 | 有界 | **B**（Channel 256+丢弃计数；YearlyOpsQueue 三重兜底） |
| 14 | 闹钟/前台服务 | 正常 | 正常 | 异常死亡后 15s 永续链（P1-6） | 永续 | 永续 | 永续 | **C(时间)（耗电），无退出条件** |
| 15 | 缓存大小 | 有界 | 有界 | 有界 | 有界 | 有界 | 有界 | **B**（GameDataCacheManager 2000 LRU+TTL、powerCache retainAll、SlotCache 重建） |
| 16 | 历史记录（事件/年报/战报） | cap 内 | cap 内 | cap 内 | cap 内 | cap 内 | cap 内 | **B**（200/100/100+5000）；mailRecords/sectBattleRecords 例外为 **C(T)** |
| 17 | 旬节拍 CPU 尖峰（P2-3×P1-7、P2-17） | 可忽略 | 可忽略 | AI 封顶后每旬深比较 28k 条目 | 同左 | 同左 | 同左 | **E(T×N) 热点**，封顶后转恒定高开销 |

**结论**：无 F（指数）类增长。最危险的组合是 **AI 尸体（约 19 小时触顶）× 每旬深比较 × 存档全量重写** 与 **渲染器异常路径随切换次数的 GPU 累积**；最确定的持续膨胀是 **mails 表（无任何上限）**。

---

## 九、已核实干净清单（生命周期闭环审计，任务书第三十二节）

> 以下对象经逐行读码确认"有真实上限或完整释放闭环"。关键对象的 Create→Release 闭环缺步者已在问题清单标记。

| 对象 | 上限或释放机制 | 证据（文件:行号） |
|---|---|---|
| 主存档 .sav/.bak | 固定文件名就地覆盖（**非**"每保存新建"），原子 rename；.tmp 启动清理>5min；孤儿 .bak >7 天清理；.bak 超 100MB 跳过 | SaveFileManager.kt:387-390,119-181,263-276,284-301,79 |
| crash_logs | 写前轮换保留最新 5 个 | CrashHandler.kt:46,213,286-298 |
| 应用日志（Kotlin+C++） | 仅 logcat，不写文件，无 rotation 需求 | DomainLog.kt:28-31；logger.h:28-37 |
| FunctionalWAL 文件 | 100 次 commit 或 >10MB 触发 checkpoint；.cp/.old 原子替换；30 天崩溃事务注册保留期（D25 修复在位） | FunctionalWAL.kt:527-537,829-837,734-740 |
| Room wal | wal_autocheckpoint=1000 + journal_size_limit=5MB + 保存后 PASSIVE checkpoint + shutdown TRUNCATE | GameDatabase.kt:530-532,233-240,329 |
| battle_logs | 保存全量重写 + 7 天修剪 + 超 200 条热数据归档 + 归档表 180 天清理 + 存档侧硬 cap 5000 | StorageEngine.kt:1074,1124-1125；DataPruningScheduler.kt:19,116-126；DataArchiveScheduler.kt:22,109-111；EntityCountBoundsRule.kt:31-47 |
| 弟子 6 张分表 | 保存全量重写；死亡弟子 600s 归档后删除 | StorageEngine.kt:1055-1059,1086-1102 |
| 云存档 | 手动触发、单一命名档原地更新、无重试队列无离线积压、临时文件 finally 删除 | TapCloudSaveManager.kt:50,498-504,252-257 |
| usedRedeemCodes / watchedItemIds | ≤500（跨层口径：Kotlin 有 cap，C++ 仅搬运——审计 A 的"C++ 侧无上限"疑虑就此关闭） | GameData.kt:1017-1018,873-877 |
| pendingTraitAdds | 同 (弟子,类型) 覆盖式写入+确认即清除（键空间=活跃弟子×2，有界） | GameEngineTraitAddOps.kt:134-140,196-199 |
| gameEventRecords | cap 200 双端一致，追加即裁 | settlement_detail.h:121-125；MutableGameState.kt:138 |
| yearlyReports | cap 100 双端一致 | year_settlement.h:65,192-198 |
| annual* 十二项年累计器 | 年报快照后整体 clear（双端） | year_settlement.h:199-214；CultivationEventMonthlyOps.kt:238-252 |
| gameEventRecords/战报（内存） | 200/100 takeLast | MutableGameState.kt:98,138 |
| notificationQueue | 硬上限 200 FIFO 丢最旧；reset drain | GameStateStoreImpl.kt:795,1766 |
| disciplePowerCache/aiDisciplePowerCache | 每次 computeCombatPower `retainAll(aliveIds)` 驱逐；load/reset 全清 | GameStateStoreImpl.kt:625,708,1485-1486,1745-1746 |
| GameDataCacheManager | 2000 条 LRU + TTL 24h + onTrimMemory 分级逐出 | CacheLayer.kt:212-236,755-786,338-389 |
| SlotCache 5 索引 | rebuildIndexes 先 clear 后全量重建 | SlotCache.kt:24-36,67-83 |
| EventBus | 生产 0 注册/0 注销；Channel(256) trySend 满时丢弃+计数+节流上报；通知历史≤50 | GameEvents.kt:245,261-312,247,344-352 |
| ECS 实体/组件 | destroy 入 freelist（LIFO 复用）+ generation 防悬垂；erase 保序压缩 | ecs/entity.h:43-67；ecs/storage.h:90-113 |
| DiscipleStore 死亡行 | markDead 仅标记；年度 cull 整行 removeById | death_handler.h:57-70；year_settlement.h:466-486 |
| 叛逆弟子（叛逃） | 即时 removeById+11 类槽位清理（但见 P2-7：blood map 不在其列） | month_settlement.h:1220-1330 |
| 弟子槽位闭环（Kotlin） | DiscipleSlotCleanup 覆盖 12 类容器+装备/功法实例删除 | DiscipleSlotCleanup.kt:56-126 |
| aiSectDisciples 招募路径 | 1000/宗 truncateToAiLimit **覆盖全部写入路径**（招募双路由/初始化/读档修复/俘虏反向搬运） | ai_sect_recruit.h:396-400；AISectDiscipleManager.kt:869-875；GameEngineCoordination.kt:283-284 |
| sectRelations | 固定 29 宗 → 406 全对初始化 | WorldMapGenerator.kt:197-231 |
| scoutInfo/aiSectBeastSkipCooldowns | 键≤29 + 月度过期/滚动清理 | month_settlement.h:1044-1079,2107-2120 |
| activeAttackWarnings | 到期即 filter 删除；每月最多 1 条（但生成链休眠，见 P2-18） | PlayerDefenseProcessor.kt:80-89,200-204 |
| 秘境会话 | 体力 clamp≤20 次；结束整体重置；AI 队伍战败/关闭清空 | secret_realm.h:57-58；secret_realm_session.h:1457,1533-1536,1081-1086 |
| C++ importStateJson | 全量替换语义，重复导入不累积 | game_core.cpp:392；disciple_store.cpp:269-274 |
| JobSystem | 单次初始化；析构 stop+join | game_core.cpp:183；ecs/job_system.h:32-50 |
| 静态数据表（herb/manual/recipe/beast/equipment/trait） | 编译期 static const 只读 | herb_db.h:28-31；manual_db.h:54-55 |
| VulkanBackend 全量 shutdown | delete g_renderer→逐项销毁置空，幂等；纹理上传失败路径对称清理；staging buffer 先销毁旧再分配；离屏目标严格配对；sigjmp 安装/恢复对称；JNI 数组全部 Release | VulkanBackend.cpp:326-399,2015-2022,1713-1717,759-772,1153-1184；NativeBridge.cpp:857-1110 |
| Renderer 实例（正常路径） | shutdownRenderer delete+置空；prewarmDevice delete 旧实例且单次守卫 → 100 次切后台推演实例数=1 | NativeBridge.cpp:177-180,328-331；GameActivity.kt:222,305 |
| Surface 纪元守卫 | 创建/销毁双递增 generation，stale 回调丢弃 | AndroidSurfaceProvider.kt:162,181；NativeSurfaceView.kt:1012,1084 |
| SpriteBatcher/KtxLoader/SkyBackground | grow 封顶 MAX_VERTICES；零分配；固定数组 | SpriteBatcher.cpp:68-84；KtxLoader.cpp:113-114；SkyBackground.cpp:129-172 |
| GLES 纹理注册表 | destroyTexture 真实现（与 Vulkan 空实现对照） | GlesBackend.cpp:386-394 |
| 前后台循环 100 次推演 | pause/resume stop/start 配对；adpfTargetJob 每启必 cancel；thermal/watchdog 对称 | GameEngineCore.kt:729-786,1006-1016,1548-1590 |
| D-07 循环状态机 | phase+epoch CAS+loopOpLock，双循环/孤儿循环/毒化态闭合 | GameEngineCore.kt:50-74,649-720,1394-1451 |
| registerReceiver 全仓 | 唯一动态注册点 BatteryAwareController（进程级单次，刻意不反注册）；无 Activity 级 receiver 失配 | BatteryAwareController.kt:67-125 |
| SDK 初始化 | SdkInitGuard/UmengManager.initialized/Bugly 幂等守卫 | MainActivity.kt:674-811；UmengManager.kt:51 |
| VsyncGate/AndroidSurfaceProvider/IME/系统栏 | HandlerThread release join；回调注册注销配对；attach/detach 对称；冻结计数+10min 泄漏自愈 | VsyncGate.kt:96-123；AndroidSurfaceProvider.kt:62-133；StandardPromptDialog.kt:322-327；SystemBarFreezeScope.kt:53-102 |
| 性能采样容器 | tickTimes/frameTimes 定容 100 offer+poll；MetricCollector ≤1000；RenderMetrics 定长 120；CircularBuffer 环形；MemoryMonitor.history 零写入 | UnifiedPerformanceMonitor.kt:122-136；PerformanceMetrics.kt:44-52；RenderMetrics.kt:28-67 |
| 秘境暂停锁（历史 S4 问题） | 15s UI 续约 + 45s 看门狗自愈——已闭环 | GameEngineCore.kt:1257-1262,1505-1577 |
| PortraitPool/ObjectPool | 固定 37 项静态 map；每池 maxSize 满则丢弃可 GC（生产无注册调用点） | PortraitPool.kt:14-39；ObjectPool.kt:96-105 |
| 弟子组件表幽灵行 | deepCopy 按 isCompleteId 过滤自愈；insert 异常回滚 | DiscipleTables.kt:1655-1657,840-844 |
| 读档自愈规则族 | GhostDiscipleCleanup/GhostRefCleanup/DuplicateDiscipleId/EntityCountBounds/RecruitListCleanup 读档净化 | data/integrity/rules/*.kt |
| ChangeTracker | BoundedLinkedHashMap(1000) LRU；且无生产驱动调用方（不增长） | ChangeTracker.kt:19-21,93 |
| 管线缓存文件 | 固定单文件名覆写 | VulkanBackend.cpp:145,1578-1585 |

---

## 十、静态无法确认 / 需要运行时验证的问题（任务书第三十五、三十七节）

| # | 待验证点 | 关联问题 | 建议验证方法 |
|---|---|---|---|
| 1 | 渲染线程在 Surface 销毁时卡 >2s 的真实频率（决定 P0-1/P1-1 定级） | P0-1、P1-1 | 真机 logcat 监听 "did not stop after 2s deadline" + GPU 负载注入 100 次开关屏幕 + `dumpsys meminfo` GL mtrack |
| 2 | P0-1 复用路径单次泄漏量（推算 30-40MB） | P0-1 | stopRenderThread 注入 3s 延迟强制走跳过分支循环 10 次，对比 smaps GL/EGL 段 |
| 3 | Kotlin 侧是否改写 C++ 物品 id（决定 P1-3 实害） | P1-3 | 生产→存档→重启→读档→再生产，检查 state.pills 与 exportDirty upsert |
| 4 | mails 表实际增速（依赖服务器月度拉取与溢出频率） | P1-4 | 自动化挂机 100 游戏年，COUNT(*) 按来源分组 |
| 5 | pre_migrate_backup 实际占用 | P1-5 | 多版本升级真机 `ls databases/ \| grep pre_migrate` |
| 6 | 闹钟链永续的端上复现 | P1-6 | `am kill` 杀后台 → `dumpsys alarm` + logcat 每 15s 观察 Application onCreate |
| 7 | AI 尸体存量与满员行为 | P1-7、P2-3 | 500 年存档 dump 各宗 size/isAlive 占比；1000/宗 满员档验证 truncate 逆新陈代谢 |
| 8 | buildReverseEnvelope/DirtyTracker 在规模档位的耗时 | P2-3、P2-17 | 1,400/10,000/28,000 弟子三档 profile |
| 9 | 换档 Pending* 残留与跨档婚配错配实害 | P2-10 | 双档脚本化复现 + 断言 _pendingMarriageProposalsFlow.value |
| 10 | AI 攻击编排是否真的休眠 | P2-18 | 12+ 游戏月观察 AI 发起 SECT_OCCUPY/预警/sectAttackCooldowns 非空 |
| 11 | OEM 挂起线程滞留峰值 | P2-12 | 红米/荣耀长挂机 `ps -T \| grep -c GameEngine-Thread` |
| 12 | maxBattleLogs 线上实际值（决定 archives 无界路径是否激活） | P2-15 | 运行时 dump 配置 + 检查 filesDir/archives/ 存量 |
| 13 | legacy snapshots/ 目录是否存在于升级设备 | P3-9 | 升级设备检查 filesDir |
| 14 | Bugly/UMeng/TapTap SDK 本地缓存目录策略 | （任务书第十九节） | 真机 `du -sh cacheDir/*` 长期观察（SDK 内部静态不可见） |
| 15 | slot 0/6 是否存在无主战报行 | P3-10 | `GROUP BY slot_id` 巡检 |
| 16 | 2x 丢旬量级 | P2-16 | 2x 注入 8s 帧停顿断言 totalPhases |
| 17 | UI 开关 100 次 Heap Dump 终验（覆盖静态不可见的库内注册） | （任务书第二十一节） | 开关炼丹/锻造/种植对话框×100 + Heap Dump 对比基线 |

---

## 十一、长期风险 TOP 10

| 排名 | 问题 | 一句话理由 |
|---|---|---|
| 1 | P0-1 渲染器复用路径 GPU 整体泄漏 | 单次 30~40MB、触发条件即历史崩溃场景、放置挂机切后台常态放大 |
| 2 | P1-7 AI 尸体堆积至 28,000 | 约 19h 游戏时间触顶；存档膨胀+每旬深比较+满员功能退化三重打击 |
| 3 | P1-4 mails 表无界 | 全项目唯一完全无上限的持久表；UI 全表加载随历史线性变慢 |
| 4 | P1-5 pre_migrate_backup 永不清理 | 每次发版必然+1 份全库副本，纯文件系统膨胀 |
| 5 | P1-3 物品 id 撞号 | 重启即触发；状态腐化（物品丢失/覆盖）不可逆且难排查 |
| 6 | P1-6 看门狗闹钟永续链 | 进程异常死亡即命中；每 15s 拉起全进程，耗电+耗电+口碑 |
| 7 | P1-1+P1-2 UAF 竞态 + destroyTexture 空 | 同根的崩溃通道与结构性缺陷，一次修复可同时消除 |
| 8 | P2-10 换档不清瞬态队列 | 跨档婚配错配=玩家可感知的数据污染，且触发路径是正常功能 |
| 9 | P2-7 血炼幽灵键族 | 三条清理路径（死亡/叛逃/逐出）全部漏清 pctTotals，永不自愈 |
| 10 | P2-3 每旬深比较 × AI 规模 | 旬是游戏最频繁节拍，成本随 P1-7 线性放大为周期性尖峰 |

**最容易先出问题的模块**：`VulkanBackend.cpp`+`NativeSurfaceView.kt`（切后台密集+低端机）→ `aiSectDisciples`（放置长跑）→ `MailDao`（运营邮件+溢出自动发信）→ `GameDatabase` 迁移路径（每次发版）→ `AlarmWatchdogReceiver`（OEM 杀后台机型）。

---

## 十二、最可能的四类故障根因（任务书第三十四节 12-17）

- **最可能导致崩溃**：P1-1（跳过 release 后旧渲染线程苏醒在重建后的 g_renderer 上提交，submitFrame 处 SIGSEGV——与 docs 三篇渲染崩溃文档记载的历史模式同型）。次因：P1-3 id 撞号导致的镜像侧数据竞争。
- **最可能导致卡顿**：P2-3（每旬 28k 条目深比较）+ P2-17（每旬全状态 JSON 双序列化）叠加的旬节拍尖峰；P1-4（邮件 UI 全表加载）；P3-23 forceDrain 单帧长卡（病理性）。
- **最可能导致存储膨胀**：P1-5（迁移备份多版本）> P1-4（mails）> P1-7（AI 尸体入存档）> P2-5/P2-6/P2-7/P2-8（mailRecords/sectBattleRecords/血炼键/储物袋的存档内缓涨）。
- **最可能导致 GPU 耗尽**：P0-1（复用路径整体泄漏，纹理由 P1-2 扩大）。
- **最可能导致内存增长**：P1-7（AI 尸体，native+Kotlin 双侧）> P2-4（deathRecords churn）> P2-11/P2-12（紧急重启的协程/线程滞留，病理设备）> P2-13（emitTyped 饱和协程，条件性）。

---

## 十三、增长矩阵（任务书第三十六节，全量合并）

| 对象/数据 | 增长原因 | 是否有上限 | 是否自动清理 | 生命周期 | 长期风险 |
|---|---|---|---|---|---|
| Renderer 实例 g_renderer | 每 epoch delete 重建；复用路径覆盖不清理 | 正常有（=1）；复用路径无 | shutdownRenderer | epoch 级 | **P0-1** |
| GPU 纹理/VBO/sync（复用时） | initSurface 覆盖式重建 | 无 | 仅 shutdown | 跨 epoch | **P0-1/P1-1** |
| m_textures | destroyTexture 空实现 | 无（实例级） | 仅 shutdown | 实例级 | **P1-2** |
| VkSurfaceKHR | 每次 resize 重建 | 无 | 仅最后一个 | 跨 resize | P2-1 |
| ANativeWindow 引用（GLES） | shutdown 不 release | 无 | 无 | 跨 GLES epoch | P2-2 |
| gc-* 物品 id | 计数器进程级 vs id 存档级 | 无 | 无 | 存档级 | **P1-3** |
| Room mails 表 | 月度拉取+溢出自动发信 | **无**（1000 为死参数） | 仅玩家手删 | 永久 | **P1-4** |
| .pre_migrate_backup.v{N} | 每次发版升级 | 无 | 无（承诺的清理任务不存在） | 永久 | **P1-5** |
| 15s 看门狗闹钟链 | onReceive 无条件续期 | 无退出条件 | 仅 Service.onDestroy | 跨进程生死 | **P1-6** |
| aiSectDisciples 条目 | 招募 +1~5/3年/宗；死亡仅标记 | 1000/宗（尸体占额） | 仅玩家攻占/休眠路径 | 永久（入存档） | **P1-7** |
| _deathRecords | 每死亡 +1；每事务全量复制 | 无 | 仅读档/新档 clear | 进程内存 | P2-4 |
| GameData.mailRecords | 每领附件 +1 | 无 | 无 | 永久（.sav+.bak+云） | P2-5 |
| sectBattleRecords | 每场宗门战 +1 | 无 | 无（读侧滑窗不删） | 永久 | P2-6 |
| bloodRefinementPctTotals 等 | 每次血炼写入 | 无 | **任何路径均不清（pctTotals）** | 永久（入存档） | P2-7 |
| storageBagItems | 偷盗/购买/换装 push | 无容量上限 | 消耗/死亡闭环 | 弟子存活期 | P2-8 |
| shownWarningStageIds | 每预警 +UUID 键 | 无 | 无 | 永久（路径休眠） | P2-9 |
| Pending* 瞬态队列 | 月结/战斗产生 | 单批小 | 月结过滤；**换档不清** | 进程内 | P2-10 |
| 悬挂 engineJob/滞留线程 | 紧急重启不 cancel/join | 无（随重启次数） | 部分（线程苏醒自退） | 进程级 | P2-11/P2-12 |
| emitTyped 挂起协程 | 通道饱和背压 | 无 | 排空后释放 | 进程级 | P2-13 |
| change_log 表 | 每保存 +1 行 | 无（清理死代码） | 无 | 永久 | P2-14 |
| archives/*.arc | 每归档事件新建 | 无（清理未接线） | 无 | 条件激活 | P2-15 |
| DirtyTracker 基线+diff 成本 | 全量序列化×每旬 | 内存有界 | 基线替换 | 进程级 | P2-17（CPU） |
| lifeEvents（单弟子） | 逐年追加 | 无（随弟子存活） | 弟子剔除随行 | 存档 | P3-5 |
| WAL 滞留事务条目 | commit 异常/recover 注册 | 无（进程内） | 进程重启 | 单进程 | P3-8 |
| 弟子储物袋外仓库/AI 池上限/gameEventRecords/yearlyReports/battleLogs/annual*/缓存族/ECS/线程/采样容器/存档文件 | — | 全部有界 | 有 | — | 无（见第十节） |

---

## 十四、审计方法与覆盖率说明

- 7 路子代理深挖合计读取约 500+ 文件、执行 600+ 次 grep/read，全部结论带 文件:行号 实码证据；主会话对跨层矛盾点（usedRedeemCodes/watchedItemIds/pendingTraitAdds 的 Kotlin 侧上限、activeAttackWarnings 清理）做了二次实码裁决。
- 覆盖任务书全部 38 项要求；其中"离线收益""游戏时间系统""UI 开关 100 次"等专项问题已在第二节直接回答。
- 标记规约（任务书第三十七节）：凡静态无法证明上限处标【无法证明存在上限】（本报告 1 处：mails 表）；凡静态无法确认、必须运行确认处标【需要运行时验证】（共 17 项，见第十节）。
- 本阶段未修改任何代码、未运行构建/测试/游戏；所有验证建议均可在修复阶段以最小成本执行。

*（报告完 · 审计执行：ZCode · 2026-09-09）*
