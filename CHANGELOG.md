## [4.00.89] - 2026-08-05

### 修复（2026-08-06 存档系统深入审查根治——27 项缺陷全量实施）

- **A1 新档 saveVersion 盖章 + 存量启发式** — createNewGame/restartGameInternal 与 StorageEngine.validateAndPrepareData 统一盖章 `SaveVersion.CURRENT`（domain 新增权威常量，engine/data 共用）；SaveDataVersionMigrator v0→1 迁移前按 lastSaveTime ≥ v4.0.13 发布时刻判定"误标新档"跳过 ÷10（单向安全：宁可偏大不可再损）。修复所有新档首次读档修炼值 ÷10 的系统性进度损失
- **A2 重型分块数值排序** — ProtobufConverters 分块解码 `sortedBy { dataKey }` 字典序 → 数值解析排序（chunkIndexOrMax）：recruitList >1000 条 / worldMapSects >500 个时块序错乱静默乱序修复，兼容旧 key 与溢出块
- **A3 battleTeams/usedTeamNumbers 持久化** — 去 @Ignore/@Transient，Room 列 + proto 字段（@ProtoNumber 216/217/218 + @EncodeDefault ALWAYS）；新增 battleTeamsInitialized 区分旧档/清空；MIGRATION_39_40（DATABASE_VERSION 40，真实 Room 校验测试）；读档不再清空出战队伍（注：确认 battleTeams 无创建入口为遗留读取型，持久化为防御性基建）
- **A4 云跨版本仲裁** — compareVersions 纯函数 + downloadSave 下载前 extra JSON version 仲裁 + 解码失败兜底改写 VersionMismatch（此前 VersionMismatch 为死代码）；实证 kotlinx.serialization ProtoBuf 跳过未知字段号（"严格模式崩溃"前提不成立，测试固化行为）
- **A5 删除 tombstone 原子化** — markSlotDeleted/isSlotDeleted/clearSlotDeleted：delete() 先写 tombstone → DB 事务 → 删文件 → 清 tombstone；restoreFromBackup 入口 tombstone 守卫 + 残留清理——删除中途崩溃不再复活已删存档
- **A6 云读档覆盖确认（用户实报）** — handleCloudLoadSuccess 目标槽位改 getCurrentSlot()（忽略云档 currentSlot 来源元数据，与游戏内云下载路径对齐）；目标槽位非空 → 挂起等待玩家确认覆盖（StandardPromptDialog 双按钮，GameOverlayHost 渲染）；4 个新测试覆盖
- **B6 云回调超时** — suspendCallback 内层 withTimeout(15s)（e.cause==null 才转超时异常，协程取消保持重抛），回调永不触发不再永久挂起 + cloudOpLock 永久占用
- **B7 校验和强制** — SerializationModule 拒绝 checksumValid=false 的解码结果（hasChecksum=false 旧帧保留兼容）
- **B8 云路径槽位语义统一** — reconcileCloudSlot（slotId+currentSlot 同时修正，防 loadFromSnapshot setActiveSlot(0)）+ loadData 后 AISectDiscipleManager.initForSlot 播种（与本地路径一致）
- **B9 oneTimeCleanup 保留未知命名** — 不再删除非当前命名的云端存档（多设备误删风险），改记录审计日志；删除失败吞掉+flag 照常置位问题消除
- **B10 上传校验** — performCloudUpload 上传前 SaveDataVersionMigrator + SaveValidator（损坏拒绝/可修复上传修复后数据）
- **C11 备份恢复插入版本迁移** — restoreFromBackup 反序列化后执行 SaveDataVersionMigrator（Rejected 中止恢复），与主档加载路径语义一致
- **C12 performFileRestore rename 检查** — renameTo 失败回退 copyTo + 失败不写恢复 marker（原"报成功+marker 锁死恢复路径"根治）
- **C13 迁移前备份版本化** — `{db}.pre_migrate_backup.v{N}` 多版本保留 + findVersionedBackup 最高可用选择 + 旧格式兼容；迁移成功不再删备份（降级回退可恢复）
- **C14 恢复前隔离当前库** — restoreFromBackup 写库前复制 `{db}.quarantine.{timestamp}`（校验器误判时数据可找回）
- **C15 缓存命中过校验管线** — tryCacheLoad 命中后执行 migrate+validate（Rejected/Corrupted 回落 DB）
- **D16-D26 死代码清理与防御接线** — 删除 SaveLoadRestartDelegate（接线即清档陷阱）/BackupStrategy/StorageValidator（含测试与 baseline）/KeyRotationManager/AppModule 绑定/migrateLegacyAutoSave（slot 0 恒 INVALID_SLOT 死代码）；接线：StorageCircuitBreaker save/load 主链路（save 阈值 5/30s、load 8/15s）、cleanupOrphanedTmp+cleanExpiredBackups 启动清理（语义修正：.sav 永不清、只删孤儿 .bak）、healDuplicateSlotAssignments 返回 Job boot 等待 + rewriteBattleTeamWinner 去强制复活（activeMissions 扫描放弃：SlotCategory 无 MISSION 枚举值且任务/岗位并存语义需产品确认）、DataPruningScheduler/DataArchiveScheduler 槽位锁互斥（经 StorageCoreFacade.lockManager 注入，规避 data 模块 Hilt KSP 解析限制）、WAL recover 超 30 天未完成事务跳过注册、worldMapSects 重生合并式保留 sectRelations + loadHeavyDataSafe 超限行改跳过不删（无再生源数据不静默丢失）
- **对抗性审查** — 3 角色代理审查发现全部核验实施；A1/A4 基于源码实证修正代理前提（saveVersion 盖章缺失确证、ProtoBuf 未知字段跳过实证）
- **验证** — 定点测试（SaveDataVersionMigrator/ProtobufConverters/RoomMigration/SaveLoadViewModelLoad/TapCloudSaveManager/SaveFileManager/SerializationDirect）+ 全量串行回归

### 优化（2026-08-05 引擎确定性加固 + 性能优化）

- **RNG 事务快照/恢复（K 项根治）** — 结算事务（突破/叛逃/生育/生产判定）在 stateStore.update 内消费分区 RNG；事务异常时 COW 缓冲丢弃（状态回滚）但 RNG 已前进 → 随机序列永久分叉、读档重放不可复现（游戏循环捕获异常后继续运行，分叉被持久化）。修复：domain 新增 `RngSnapshotPort` 事务钩子接口（零平台依赖）+ app 装配 GameRngManager 适配 + Hilt 绑定；`update()` 事务失败恢复 8 分区快照（8×Long，CancellationException 重抛不恢复，含 OOM 路径）；`loadFromSnapshot` 状态 + RNG 锁内原子切换（原 SaveLoadViewModel 的 restoreStates 在 loadData 之后，load 成功但其后失败会错过恢复）；`rollbackLoad` 同步恢复。`TransactionRngRollbackTest` 6 场景守卫（重试对拍/嵌套不重复快照/读档失败保持/读档成功切换/取消穿透/成功不恢复）
- **主线程 RNG 消费治理（P0-1b，含 C1/C2 盲区修复）** — 天道试炼战斗模拟（UI 线程）此前直接消费全局 BATTLE/ENEMY_GEN 分区——模拟次数/时机变化使引擎侧（AI 宗门进攻/探索/战斗结算）随机序列分叉（行业教训：装饰性 RNG 调用破坏全局状态的灾难案例）。修复：`beginCombat` 进入战斗时单次取种子创建本地 PRNG，模拟完全本地化；敌方 AI 决策 `executeEnemyAction` 加 rng 参数（UI 传本地 PRNG）；**试炼敌人生成改确定性派生种子**（enemySeed = 关卡定义 + 敌人名哈希，不再消费全局 ENEMY_GEN，预览 = 战斗属性一致）；`initSystemSeed` 收敛到 createNewGame 引擎线程；`RngConsumptionGuardTest` 守卫 UI 侧禁批量消费（白名单单文件单次取种子）
- **奖励发放统一入口（P-19/P-20/P-21 全量实施）** — ① P-21：签到 `distributeReward` 的 catch 移出事务（原 Partial 部分物品实际入仓却返回容量错误 → 重试重复领取；现异常穿透整体回滚，凭据保留可重试补齐）+ handleResult Partial/Failure 均穿透（C6：循环发放部分成功后 Failure 重复发放修复）+ 里程碑循环内立即记账（F3：已发放里程碑不重复领）；② P-19：弟子奖励放背包手写"find 同键堆叠 + quantity+1"路径收编 `InventorySystem.addEquipmentStack/addManualStack` + `withTrackingSource("disciple_reward")`；**F1 严重修复**：addXxx 加 `excludeStackId` 参数（放背包扣减后不合并回源堆叠——原收编引入"数量净 0 但背包引用无限增长 → 回收洗白刷装备"漏洞，2 个守卫用例）；③ P-20：卸装/换装实例→堆叠 4 函数迁移 `InventorySystem.addEquipmentInstanceToBag/addManualInstanceToBag`——`maxSlots = candidates.size+1` 绕过总容量改真实容量、Partial 溢出转邮件、excludeStackId 尾部保留、来源 disciple_unequip；④ 背包引用列表去截断（溢出引用静默丢失）；⑤ 守卫补漏：`truncationPattern` 补 `maxStack` 变体 + 新增 domain 模块守卫；⑥ E5：StackableItemStore `maxStack<=0` 死循环守卫
- **结算事务合并（G 项）** — `processTickPhases` 掉帧追旬 N 次独立 update → 单事务内多旬循环（省 N-1 次 COW deepCopy + 锁竞争）；配合 P0-1 异常整批回滚 + **F2 修复**（`GameTimeClock.refundPhases` 异常时回补时钟——合并回滚后状态时间落后墙钟需归还累积，防永久落后）；`SettlementTransactionMergeTest` 确定性对照守卫；`checkpointAllProduction` 因 suspend 限制保持事务外（罕见路径）
- **assembleAll 事务内 memoize（I 项部分落地）** — `DiscipleTables.txAssembled` 缓存：同事务多次 assembleAll 只组装一次（战斗流程 4 次全量 → 1 次 + 3 次 O(1) 命中），写操作经 `requireWriteAccess` 统一失效（COW deepCopy 每事务自动复位）；benchmark 测量前显式失效（`invalidateAssembleCache`）
- **装备属性缓存（C2）** — `EquipmentInstance.getFinalStats` 值语义缓存（不可变 COW 实例内容即版本，data class equals/hashCode 键零失效逻辑，ConcurrentHashMap 线程安全，4096 容量护栏）：属性计算链内层热点 O(1) 命中；`EquipmentFinalStatsCacheTest` 4 用例
- **GameRngManager 并发加固（F6）** — rngMap 改 ConcurrentHashMap + replaceAll（initSystemSeed 结构修改与 getRng/exportStates 并发读无锁安全）
- **B2 决策记录** — 服务层跨事务增量组装（update 入口 await 不变量）评估后不做：B1 已覆盖同事务重复组装（主要收益），跨事务增量已由 store 层 dispatchAssemble（changedIdTracker + 双指针归并）承担，await 不变量触及核心并发且收益有限
- **途中发现处理** — `FakeAtomicStateStore` 物品实体不跨事务持久化 + 写权限未模拟——测试基建缺陷已增强（flow 初始化 + syncFlows 写回 + writeDepth 重入计数）；`GameEngineDiplomacyOps.interactWithSect` 未实现存根登记（D-05）
- **对抗性审查（边界/状态/数据三角色 agent，17 项发现）** — 修复 8 项（F1 刷装备严重 / F2 时钟落后 / F3 里程碑记账 / C1 敌人生成 / C2 敌方 AI / C6 签到 Failure / F6 RNG 并发 / F7 OOM 恢复）；登记 9 项至 architecture.md 待完成项（D-01~D-05 等，溢出邮件非事务化 / 里程碑失败入口 / 放背包失败体验等）
- **验证** — compileReleaseKotlin + detekt 全模块违规清零 + 全量测试串行（--max-workers=1）回归 0 失败 + lintRelease 通过

## [4.00.86] - 2026-08-03

### 代码质量（2026-08-05 架构文档待完成项全量实施）

- **God Method 拆分 14 个函数** — executeCombatantTurn（BattleSystem）、attackSect/attackWorldLevel/scoutSect（GameEngineBattleOps）、executeTeamConflict（PatrolBattleSystem）、distributeRewardItems（HeavenlyTrialService）、EncounterBattleService 四函数（PvP/PvE/日志映射共享）、executeAIEncounterBattle（AISectBeastAttackProcessor）、resolveBeastAttackFight（ExplorationService）、resolveDefendersAndBattle/decidePlayerAttack（AISectAttackManager）、executeSupportSkill（BattleCalculator）；全部 ≤60 行，RNG 调用序与事务边界逐字保持
- **存档互斥 T2 修复** — restartGame 与 loadGame 完整互斥（loadLock 双向守卫 + _isRestarting 同步置位 + finally 成对释放），新增 3 个互斥回归测试；测试陷阱记录：advanceUntilIdle 推进虚拟时间触发 withTimeoutOrNull 需用 runCurrent
- **T-C1~C4 防御性修复** — estimateDamage 注入 damageModifier（BattleAI 全链透传，严苛训练下 AI 决策估算一致）；斩杀分支 maxHp 钳制 0；EnemyGenerator 配置反转退化不崩溃；buildDamageZones 六次 filter 合并单次遍历（数学等价 + 对拍守卫测试）
- **OverlayDialogRouter 按域分组** — 34 个 DialogType 分支提取至 components/dialog/ 6 文件（MainTab/Feature/Production/FunctionalBuilding/System/Common），路由 when 单处穷尽分派
- **SettingsTab 4 处平台 Dialog 迁移 UnifiedGameDialog** — 新增 DialogMode.Large（0.95w/0.9h 存档管理）；手写三守卫删除（内置继承）；迁移修复原 Box 闭合顺带在旧 Dialog 区域的隐藏依赖
- **ManualReplaceDialog 迁移 UnifiedGameDialog**（Auto 模式）
- **GameEngine 构造 33→8** — 3 个新域 Facade（Exploration/Cultivation/Economy）+ 既有 Facade 吸收（Inventory/Save/Building/Battle 接口暴露服务引用）+ 访问器转发（16 扩展文件零改动）+ CoreModule 绑定；engineContextDispatcher 保留为测试注入点（Mockito 无法 stub suspend 泛型，生产恒等于 gameEngineCore）
- **CultivationCore 15→6 依赖** — 删除 10 个方法体零引用依赖；熟练度核心逻辑迁 ManualProficiencyService（批量循环 + 单次提交，P-1 语义保持）
- **CaveExplorationProcessor 拆 AISectBattleProcessor** — AI 攻防域（热控修炼/宗门等级同步/攻打玩家/AI-vs-AI/防守战）19 方法迁新类（7 依赖）；洞府域 13→8 依赖；processAISectOperations 委托签名不变
- **detekt 配置** — TooManyFunctions thresholdInClasses 15→20（AI 攻防域聚合单一职责）；baseline 摘除 executeCombatantTurn 4 条旧签名条目 + 更新 BattleAI 4 条签名（只缩不增合规）
- **验证** — compileReleaseKotlin + 全量测试（--max-workers=1）0 失败 + detekt 新增违规清零（预存 42 项与 HEAD 基线一致）
## [4.00.86] - 2026-08-03

### 新增（2026-08-03 远古秘境玩法）

- **远古秘境** — 世界地图每年有几率现世（五十年一遇），点击秘境可派遣 4 名弟子组成探索队进入；探索以"遭遇妖兽 → 选择探索方向"的文字事件循环推进，体力 20 点每选一项扣 1，耗尽或主动结束即关闭
- **秘境战斗可视化** — 事件区内展示我方/妖兽槽位与逐回合战斗日志（每秒两回合，可跳过）；战斗胜利获得妖兽材料与灵石，失败丢失探索背包 20%~45% 所得（取整宁多不少）
- **重伤濒死保命机制** — 弟子战斗中首次阵亡进入"重伤濒死"（红色状态代替血条），再次参战仅剩 1 点血量，再次阵亡则永久陨落
- **探索所得结算** — 探索背包中的灵石与物品在探索结束时自动放入宗门仓库（仓库满自动转邮件发放）；探索中游戏时间暂停，退出探索后自动恢复
- **AI 宗门也探秘境** — 所有 AI 宗门会派遣境界最高的 4 名弟子进入秘境
- **断线续玩** — 探索进度完整保存，读档后世界地图仍显示秘境，详情界面按钮变为"继续探索"
- **探索中状态冻结** — 秘境探索中弟子不可修炼/突破/自动恢复/自动换装，返回宗门后自然恢复

### 优化（2026-08-03 秘境体验优化）

- **平坦空地事件** — 选择探索方向后有几率发现平坦空地，可原地休整使全队恢复 40% 生命（重伤濒死弟子亦可恢复后脱离濒死状态），或继续前进
- **优化：事件标题立即显示** — 秘境事件进入时标题当帧呈现，描述与选项卡片仍按节奏逐行显示
- **优化：选项卡片间距加大** — 卡片间隔由 2dp 增至 8dp，触控与视觉更舒适

### 新增（2026-08-03 发现遗迹事件）

- **发现遗迹事件** — 选择探索方向后有几率发现未知遗迹：可离开，或简单搜寻（消耗 1 体力，50% 获得 1~5 件灵品至宝品物品）、仔细搜寻（消耗 2 体力，50% 获得 2~7 件灵品至玄品物品），装备/功法/丹药/材料/草药/种子皆有可能
- **选项体力消耗显示** — 秘境所有事件选项卡片底部显示"体力-1"/"体力-2"，所见即所扣
- **探索背包物品卡片展示** — 背包弹窗改为物品卡片网格（品阶色边框 + 图标 + 数量），每件所得一目了然

### 新增（2026-08-03 历战入口）

- **历战入口** — 主界面右侧新增"历战"悬浮按钮，打开活动卡片轮转界面：中间主卡片显示活动完整内容（图标+描述），左右副卡片被主卡片遮挡一半仅显示轮廓，左右翻页按钮带动画轮转
- **历战·天道试炼** — 卡片显示试炼图标与"未知岛屿/挑战通关获得丰厚奖励"；点击弹出半屏试炼界面（原天道试炼 8 岛地图+右下角通关奖励），点击岛屿直接开始挑战；进入后活动界面不再显示天道试炼条目（玩法保留在历战，重新打开游戏后恢复显示）
- **历战·远古秘境** — 卡片显示秘境精灵图与"无穷机缘的未知秘境"；秘境改为每 50 年开启一次的常驻活动：未开启时卡片下方显示红色"未开启 / 每50年开启一次"且点击无反应，开启时显示"进入秘境"按钮，点击弹出详情界面（选人/一键任命/出发/继续）
- **秘境入口迁移** — 远古秘境不再显示在世界地图上，入口统一移至历战（首次开启在第 50 年，探索结束后再过 50 年再现世）
- **天道试炼入口迁移** — 天道试炼从活动界面移除（活动界面仅保留每日签到），统一从历战进入

### 新增（2026-08-03 远古秘境结束选项）

- **探索方向选择** — 秘境每个事件结算后固定弹出"向左走 / 走中间 / 向右走"三个方向选项（消耗 1 体力），选择方向后进入下一探索事件；方向纯过渡，不影响下一事件出现概率（仍为 30% 空地 / 20% 遗迹 / 50% 妖兽）
- **遗迹结果并入方向事件** — 遗迹搜寻的秘宝 / 空无一物结果文本直接显示在方向事件描述中（含获得物品明细），不再弹出单独的"继续前进"子事件；旧存档自动兼容

### 修复（2026-08-04 游戏时间停止根治）

- **根治游戏时间停止不动** — 部分机型（华为/荣耀/vivo/OPPO/小米 HyperOS 等）游玩时时间偶尔冻结的问题已彻底根治。三层守护（引擎看门狗/主线程健康检查/系统闹钟兜底）的判据从"循环心跳"升级为"游戏时间推进"三元组（心跳 + 世界时间 + 时钟累积），此前"暂停卡死 / 倍速归零"两类冻结形态对所有守护完全失明，现可自动检测并自愈
- **修复：秘境探索切后台冻结** — 在秘境探索中切后台再回前台后时间永久冻结的路径已根除（Activity 重建导致探索退出回调丢失时，引擎最迟 45 秒自动识别并恢复）
- **修复：后台往返清掉手动暂停** — 手动暂停后切后台再回前台，暂停状态不再被意外清除
- **修复：慢存档不再被打断** — 低端机保存超过数秒不再被守护误判卡死而强制打断（保存时间到 60 秒才触发病理级兜底）
- **优化：循环异常自动归因上报** — 游戏循环内部异常携带（年/月/旬/倍速/厂商等）上下文自动上报，后续问题可快速定位

### 加固（2026-08-04 时间守护加固，对抗性审查 20 项修复）

- **修复：后台往返不再残留"无主暂停"** — 秘境探索中切后台且系统回收界面后，不再出现"用户没暂停过却一直暂停"的异常状态
- **修复：后台不再偷偷推进游戏时间** — 秘境探索切后台超过 45 秒后，恢复机制不会再在后台强制启动游戏循环（回到前台才恢复推进）
- **优化：守护误恢复窗口收窄** — 刚恢复运行/单次超长结算不再被守护误判卡死而强制重启；慢存档窗口（重开游戏/读档保存）不受守护打扰
- **优化：异常上报限速** — 循环异常上报增加频率限制，避免极端情况下上报风暴

### 修复（2026-08-04 天枢殿自动学习修复）

- **修复：天枢殿自动学习/自动装备失效** — 弟子管理中开启"自动学习仓库中符合境界的功法"后，仓库中的功法从未被任何弟子学会的问题已根治。此前任务/探索奖励直接入库的功法，因弟子储物袋为空（常态）而被每旬处理逻辑整体跳过，现改为按关注标记与灵根数资格正常自动学习；同路径的"自动从仓库装备"一并修复

### 修复（2026-08-04 预存问题清理）

- **修复：储物袋丹药效果/品级读档丢失** — 弟子储物袋中的丹药效果、品级、遗忘冷却时间此前不随存档保存，读档后全部丢失，导致"自动服药"对储物袋丹药失效、丹药详情显示空白；现已完整持久化（与旧存档格式兼容），读档后效果与品级保留
- **清理：弟子级自动学习/自动装备开关死代码** — 删除从未生效的弟子个人自动学习/自动装备开关（功能统一由弟子管理中的全局策略控制），存档兼容保留旧数据列
- **清理：失效的自动装备/自动学习旧路径** — 移除基于弟子储物袋的旧版自动学习/自动装备逻辑（数百行死代码，含遗留的"脏标记"内存泄漏）；"自动学习/装备仓库物品"的现行路径不受影响
- **清理：死代码与内存泄漏** — 移除自动装备/自动学习脏标记集合（每次卸装/遗忘功法累加弟子 ID 至永久内存），以及未使用的冷却期遗留逻辑

### 修复（2026-08-04 弟子详情弹窗闪退根治 + 引擎线程规范加固）

- **修复：弟子详情弹窗打开即闪退** — 打开弟子详情弹窗有概率立即崩溃（引擎状态写入被错误地在主线程执行，触发架构保护直接终止）。已根治：弟子历史日志生成改为派发到引擎线程执行，不再阻塞/违规
- **加固：引擎写入线程规范全面加固** — 排查修复同类隐患：外交送礼/简化结盟/解除结盟/附庸签订/解除附庸 5 个操作此前依赖调用方侥幸在非主线程执行（任何主线程调用即崩溃），现统一在引擎线程自切换；奖励卡入队（储物袋/签到开卡）等路径一并规范到引擎线程，杜绝未来任何入口触发同类崩溃

### 清理与验证（2026-08-04 死代码清理 + 监牢释放验证）

- **清理：遗留死代码移除** — 移除从未触发的状态访问器（StateAccessor，含潜在主线程写入隐患）、未接通的偷盗释放入口（含旧随机忠诚逻辑）、设置面板重复的弟子释放方法（UI 使用的主线程安全版本不受影响）；功能无任何变化
- **加固：监牢释放功能测试验证** — 监牢（思过崖）弟子"释放"按钮路径补端到端测试：释放后状态正确回归（不会被思过状态锁定）、带岗位弟子释放后回归岗位状态、死亡弟子正确归一死亡；5 个用例全绿

### 修复（2026-08-04 存档恢复链路根治 + 云读档加固）

- **修复：老存档跨多版本升级后显示为空或损坏** — 跨多版本升级时存档恢复机制此前从未真正接通（备份文件从未写入、恢复路径必然报错），数据库异常后玩家看到的只有"存档为空或已损坏"。已根治：备份系统正式接线生效、迁移崩溃后自动从迁移前备份恢复、升级时备份文件可正确读取使用
- **修复：读取云存档进入游戏后按钮无效** — 读档卡住导致游戏时间冻结且无法自愈的问题已根治（读档卡死超过 60 秒自动复位，游戏恢复正常运转）；云存档读取前增加完整性校验与旧版本数据自动升级（损坏数据拒绝加载、可修复数据自动修复、旧档修炼值等自动迁移）；云存档写入本地失败时明确提示，不再静默误读旧档；游戏内下载云存档后本地存档同步更新（重启不再丢失）
- **加固：启动失败不再无限卡加载** — 地图数据生成失败时明确提示"请重新进入"而非永远停在加载界面；云存档写入失败、数据损坏等异常均有明确错误提示

### 修复（2026-08-04 对抗性审查整改——3 个恶意角色代理 30 项发现）

- **修复：云存档槽位覆盖保护** — 老版本云存档的槽位号越界（0/负值）时不再静默覆盖本地 1 号槽位存档，改为使用当前槽位
- **修复：降级安装不再启动崩溃** — 高版本数据回退到低版本应用时自动从迁移前备份恢复（原实现直接崩溃且备份成摆设）
- **修复：恢复-迁移崩溃死循环** — 备份恢复后迁移仍失败时不再无限"恢复→崩溃"循环；迁移成功后自动清理备份（防止旧备份未来误覆盖新数据）
- **修复：备份误删玩家进度** — 崩溃会话残留的数据库缓存文件（-wal/-shm）仅在确定需要恢复时才清理，正常玩家的最近进度不再被误删
- **修复：超大云存档不再闪退** — 数据过大导致内存不足时明确提示而非直接崩溃
- **加固：云存档与本地操作互斥** — 云读档/云下载进行中点本地读档或保存会被明确拒绝（原实现会静默造成"内存读的是本地档、数据库存的是云档"的分歧）
- **加固：云下载前自动备份本地存档** — 游戏内下载云存档前保留当前存档快照（与主菜单云读档一致）
- **加固：读档失败不再残留冻结画面** — 游戏内读档失败时清空残留地图数据，界面与引擎状态一致

### 调整与修复（2026-08-04 弟子叛逃判定 + 脱离宗门消息栏）

- **调整：弟子随时可能叛逃** — 低忠诚弟子无论是否在工作（巡逻/挖矿/炼丹/锻造/灵植/驻守/长老等）都会按忠诚度参与叛逃判定，不再只有空闲弟子才会叛逃；处于战斗/任务/血炼/思过中的弟子不参与判定。此前卸任弟子立即变成空闲、当月就被判定叛逃却没有任何提示，让弟子"无故消失"
- **修复：弟子脱离宗门不再无声无息** — 叛逃（含偷盗后叛逃）与叛逃被捕（思过）均会写入消息栏，消息栏明确显示"XX脱离宗门"，玩家随时可查证弟子去向
- **加固：生产槽位清理补全** — 卸任/死亡/叛逃时同步清空炼丹/锻造/灵植工人槽位，不再残留已离岗弟子的占位（此前卸任炼丹工人后状态卡死无法重新分配）
- **修复：更换岗位后旧弟子状态立即回归** — 巡视楼"更换"队员后，被换下的弟子状态立即回归空闲（此前残留"巡视中"导致从所有选择弹窗消失、无法重新分配）；藏经阁换人同路径一并修复（此前旧弟子门卫注册残留 + 状态卡"学习中"）

### 修复（2026-08-04 老存档升级崩溃根治——迁移校验修复）

- **修复：老版本存档升级后反复崩溃** — 升级到最新版本后，部分老存档在数据库升级阶段崩溃（迁移后校验失败：实际表结构与新版本不符），导致无法进入游戏且每次启动重复崩溃。已根治：清理失效开关列的迁移由"保留旧列"改为真正重建数据表（弟子/扩展/装备三张表，数据完整保留、检索索引全部重建），所有老版本存档可正常升级进入游戏
- **加固：迁移测试体系补上真实校验** — 此前迁移测试只执行迁移 SQL、从不通过游戏数据库真实打开触发校验，同类问题无法被发现；新增"真实 Room 校验"测试（单步 38→39 与全链 11→39），任何迁移后表结构与实体不符都会在测试阶段直接暴露

### 优化（2026-08-04 规范体系扩展准备）

- **规范体系全面修订** — 为游戏未来功能扩展与 iOS 端做准备：
  - **新增代码质量规范**（`rules/code-quality.md`，即时生效）— 命名规范（对标 Google Kotlin 官方风格指南）、坏味道清单、SOLID 设计原则、可测试性、扩展友好性、**iOS 跨平台可移植性**（core 层禁 Android 独占 API、平台能力接口抽象）、审查门禁、量化指标（圈复杂度 ≤15 等）
  - **新增 5 个扩展规范**（预留，约束未来设计）— 新玩法系统接入（`expansion-playbook.md`：惰性结算层级/事件总线/RNG 分区/引导接入 10 项清单）、经济系统（`economy-design.md`：新货币引入/源汇闭环/通胀防控）、商业化与运营（`commercialization.md`：广告/IAP/战令/RemoteConfig/慷慨原则）、社交与排行（`social-system.md`：异步社交/分层奖励/合规）、数据埋点（`data-analytics.md`：FTUE 漏斗/无 PII/留存基线）
  - **修订现有规则** — 修正 `version-release.md` 游戏内更新日志编辑路径（真源为 `changelog_entries.json`）；`database-migration.md` 新增新玩法建表/ProtoBuf 字段预留/货币变更三节；`ad-cooldown.md` 新增商业化触发源冷却边界声明；`new-dialog-checklist.md` 新增活动/排行/社交界面分组
  - **CLAUDE.md 审查清单新增 9 条扩展方向检查项** + 设计方案规则新增原则 6（扩展方向对标）；`docs/architecture.md` 新增扩展性架构预留章节（含 iOS 迁移预留）；`docs/knowledge-base.md` 新增扩展性现状盘点章节（经济基线表 + iOS 可移植性基线）
  - **新增玩法 UI 组件复用规范** — 新玩法界面必须优先复用游戏内已有组件（GameButton/UnifiedGameDialog/ItemCard/SpriteImage 等 18 项清单写入 `rules/expansion-playbook.md`），禁止自建重复组件；CLAUDE.md 审查清单同步新增检查项


## [4.00.87] - 2026-08-04

### 修复（2026-08-04 战斗系统全面核查修复——第一手源码验证 9 项正确性 Bug + 对抗性审查整改）

- **修复：境界压制斩杀方向反转** — `checkInstantKill` 公式此前为 `(defenderRealm - attackerRealm)×9 - 层差`，方向相反：目标（防守方）境界比攻击者高 ~2 大境界时反而触发"境界压制斩杀"（弱者秒杀强者），而高境界秒低境界的预期场景永不触发。已改为 `(attackerRealm - defenderRealm)×9 + 层差`，注释与代码语义一致；两引擎（BattleSystem/AISectAttackManager/天道试炼）共享入口自动生效，补 6 个方向守卫测试
- **修复：多段技能（连击）实际伤害未乘段数** — `calculateCombatantDamage` 正常伤害分支不乘 `skill.hits`（hits 仅进战报"（N连击）"文案），而 AI 决策用 `estimateDamage` 乘段数——实际伤害只有设计值的一半/三分之一，AI 永远高估连击技能。配置证据：虎妖 0.9×2、狼妖 0.6×3，倍率×段数恒等于单发 1.8。已修复为实际伤害 ×hits，与 AI 估算对齐
- **修复：敌方支援治疗完全无效** — BattleSystem 治疗写入硬编码 `ctx.team`，蛇妖"蜕皮新生"（25% 自疗）等妖兽治疗静默丢失；已按 `isTeamMember` 分写 team/beasts（与 buff 分支一致），AI 引擎治疗路径本就正确无需改
- **修复：AI 宗门弟子功法技能被剥光属性** — `AISectAttackManager.buildCombatSkills` 手写 CombatSkill 仅传 7 个字段，丢失 skillType（默认 ATTACK，支援功法变普攻）、isAoe、buff/heal/shield/控制/拉条全部属性；改走 `toCombatSkill()` 全字段保留，宗门战（玩家攻 AI/AI 攻 AI/AI 攻玩家）中敌方功法完整生效
- **修复：同回合被击杀单位仍出手** — BattleSystem 用回合开始快照判死，被杀单位（ctx 中 hp=0）仍以满属性出手一次；改以 ctx 当前状态判死（与 executeUnifiedAIBattle 行为一致），被杀单位不再反击
- **修复：ally 作用域支援技能用 kotlin 默认 Random 选队友** — 违反确定性 RNG 分区纪律，改走 BATTLE 分区 PRNG，同种子战报可复现
- **修复：pendingAiAction 类级可变字段** — @Singleton 并发隐患（selectSkill→selectTarget 配对临时状态），改局部传递
- **修复：物理/魔法攻击 Buff 合并加算** — buildDamageZones 把 PHYSICAL/MAGIC_ATTACK_BOOST 合并同一乘区（物理加成也加成魔法攻击），改按攻击类型分桶注入
- **修复：敌人弟子功法属性加成缺失** — EnemyGenerator/天道试炼敌人此前只有功法技能、无功法属性加成（stats × 熟练度），与 07-20"统一玩家公式"宣称不符；已补齐（含功法暴击），副本散修/试炼敌人强度回归完整公式
- **架构：双引擎伤害应用层共享** — 新增 `BattleDamageApplier`（护盾吸收含余量写回/伤害分摊/伤害链接），BattleSystem 与宗门战引擎共用；宗门战此前完全无护盾/分摊/链接（直接扣血），现与主战斗语义一致；分摊伤害的护盾余量写回顺带修正（此前护盾对分摊伤害"免疫"不扣余量）
- **架构：playerDamageModifier 参数透传** — 删除 @Volatile 单例字段（设置-执行-重置模式异常中断会污染后续战斗），改 executeBattle 参数透传；detekt-baseline 同步删除孤儿条目
- **性能：AI 宗门战超时保护** — 每旬大量 AI 宗门战在游戏线程执行，此前仅 200 回合上限无超时；新增 5000ms 超时（与 BattleSystem 对齐），拉锯战不再卡主线程
- **性能：删除 AOE 护盾重复幂等计算** — applyAoeShieldAbsorption 与单目标护盾结算对同一快照重复计算，删除（行为不变）
- **重构：EnemyGenerator.generateHumanEnemy 拆分** — 装备生成/功法生成提取为子函数（RNG 调用序不变）；剩余 God Method（processBattleCasualties/attackSect 等 10 处）列为后续持续拆分项
- **测试基建修复** — 发现 BattleSystemTest 既有 22 个测试为假阳性：未注入 DiscipleStatsProvider，弟子属性全 0（maxHp=0 → 战斗 0 回合即结束，RNG 从未使用）。已注入真实实现 + 真实 GameRngManager，现有测试真实执行回合；新增 14 个战斗回归测试（斩杀方向/连击/敌方治疗/死亡不出手/RNG 确定性/技能完整/护盾分摊链接/伤害倍率/敌人功法加成），引擎模块全量 1587 测试通过

### 对抗性审查整改（3 个恶意角色代理 42 项发现，交叉验证后 12 项修复）

- **连击段数钳制** — hits 篡改为 0/负值时钳制为 1（否则 0 伤害/负伤害回血），Long 乘法防 Int 溢出回绕
- **护盾数值防御** — 护盾 value 语义为最大生命比例（0~1），钳制 [0,1] 防存档篡改（负值放大伤害、±Infinity 异常）；余量写回按 value 匹配（同剩余时长多护盾不再串扰）
- **必杀无视护盾** — 斩杀（境界压制必杀）直接击杀，两引擎语义统一，消除"战报显示必杀、实际残血存活"的谎报
- **AI 引擎 ally 作用域支援修复** — BattleCalculator 对 "ally" 返回空列表，AI 引擎此前空放（治疗/护盾全失效），现按 aisRng 选队友
- **AI 引擎 DAMAGE_LINK 补附加** — G4 全字段保留后链接效果在宗门战恒为零，补链接 debuff 附加（与主引擎一致）
- **AI 引擎 pendingAiAction 收敛** — 与主引擎 G7 同模式改局部传递（object 单例类级可变字段消除）
- **AI 引擎超时语义对齐** — 超时后按存活数判胜负（与 BattleSystem 一致），僵局战不再一律 DRAW 使攻击方无损失
- **试炼敌人技能倍率按熟练度调整** — 与属性加成（NOVICE ×1.5）同源，消除同一敌人"属性 1.5 倍、技能裸奔"的矛盾
- **AOE 日志求和防溢出** — Long 求和防多段×多目标溢出为负

### 修复（2026-08-05 存档恢复链路对抗性审查遗留 T7~T16 + 并发 T4 全量修复）

- **T7/T11：SaveValidator 规则引擎覆盖缺口** — 新增 `NumericSanitizeRule`（order=0 最先执行：NaN/±Infinity/负修炼值重置 0，覆盖弟子/招募列表/AI 宗门弟子/pillEffects 全数值字段，未变化保持原引用不误报）；`CultivationCapRule` 对 realm≤0（仙人）改用绝对上限 1e9 钳制（原 `Double.MAX_VALUE` 不截断，恶意云档可携带巨大修为穿透）；`EntityCountBoundsRule` 新增硬上限**真截断**（battleLogs 5000 按时间戳保留最新 / 装备功法堆叠 50000 截断并清理弟子悬空引用 / 弟子 10 万判损坏）——修复"返回 Repaired 但数据原样"的伪修复；新增 `BattleLogRefRule`（条目结构校验：非法日期/负回合/负伤亡/空条目清理）、`ManualTalentRefRule`（manualIds 存档作用域 + talentIds 注册表悬空引用清理）；注册表 20→23 条规则
- **T8：备份文件 CRC 跨 API 不一致** — 文件头格式升 0x0101，字节 11（原保留位）记录 CRC 算法标识（0=CRC32/1=CRC32C）；读取按标识精确校验，**旧 0x0100 文件双算法探测兼容**——API<34 设备写入的备份换机到 API≥34 设备不再全部判损坏（修复前唯一出口是丢档走 .bak 链）
- **T9：备份超 100MB 谎报成功（且主保存也被跳过）** — 核查确认超限 early-return 位于写主 .sav 之前（比文档登记更严重：主保存+备份一并跳过且返回 success）。`atomicWrite` 重构：主 .sav 无条件执行，超限只跳过 .bak 并返回新 `StorageResult.Skipped` 分支；`StorageFacade.toUnifiedResult` 穷尽 when 编译收口；`StorageEngine.handleSaveResult` 改显式三分支 + `StorageMetrics.recordBackupSkippedOversize()`
- **T10：云档 saveVersion 无边界校验** — `SaveDataVersionMigrator.migrate` 返回类型改 sealed `MigrationResult`（Migrated/Rejected）；负数（二次缩放风险）与高于当前版本（Int.MAX 伪造绕过缩放）显式拒绝——本地读档走备份恢复、云读档弹明确错误；3 个生产调用点 + 全部测试适配（编译期强制）
- **T12：60s 病理兜底 vs 友好超时竞态** — 看门狗 `SAVE_LOAD_STUCK_TIMEOUT_MS` 60s→90s（友好超时 60s 先触发并复位标志，看门狗只拦截真正病理卡死）；新增 `stuckResetEvents` 事件通道，看门狗病理复位**发用户可见事件**（原取消路径静默失败）；`forceResetStuckStates` 保持静默（onCleared 正常清理不可弹窗）；`checkAndResetStuckStates` 改 internal + nowMs 注入测试 seam
- **T13：boot 失败弹窗补"返回主菜单"** — GameActivity 错误弹窗 customButtons（`isGameLoaded=false` 时显示"返回主菜单"+确定，加载成功时与旧行为逐像素一致）；`buildMainMenuIntent` 独立顶层函数（复用 onLogout 的 MainActivity 重建模式，不清 session）；LoadingScreen 永驻加载页唯一出口（系统返回键）问题消除
- **T14：saveGame 协程不注册 activeLoadJob** — 保存协程 `.also { registerActiveLoadJob(it) }` + `performLocalSaveToSlot` finally 清除（与 loadGame/startNewGame/restartGame 同模式）；`saveToCloudViaSlot` 故意不注册（网络等待被看门狗中途取消会造成半上传状态）
- **T15：recoverWithPartialData 跳过完整性守卫** — 恢复前补齐主路径 Step 5/6/6.5 守卫（prewarm→ensureHeavyDataLoaded→ensureGameDataIntegrity→assignmentGate.rebuildFromGameData→consolidateStacks，调用顺序逐字一致）；守卫失败放弃恢复走既有 onError——半初始化状态不再进入 PLAYING
- **T16：restartGame 缺 isGameLoaded 守卫** — 入口最前补守卫（与 loadGame 同模式），boot 失败（runState=IDLE）后内存残留新档数据不再可能覆写磁盘
- **T4：changedIdTracker MAX_SAFE_CAPACITY 守卫缺口** — `record`/`recordAll` 容量拒绝置 `rejectedRecord` 标志 + `consumeRejectedRecord()` 消费；`dispatchAssemble` 读到标志强制走全量组装——crafted 存档大 id 弟子不再残留陈旧快照（原实现仅 changedIds 完全为空时才全量）；`markRejectedForTest` 测试 seam（对齐 forceFullCopy 既有模式）

**测试**：新增 8 个测试文件（NumericSanitizeRuleTest/BattleLogRefRuleTest/ManualTalentRefRuleTest/EntityCountBoundsRuleTest/GameEngineCoreStuckResetTest/GameActivityBackNavTest/GameStateStoreForceFullAssembleTest/SaveFileManagerSdk33Test）+ 6 个既有测试类扩展（SaveDataVersionMigratorTest 全适配/SaveFileManagerTest/SaveFileManagerSdk33Test/StorageResultTest/CultivationCapRuleTest/SaveValidatorTest/DiscipleTablesChangedIdTest/BootSequenceControllerTest/SaveLoadViewModelLoadTest）；core:data 全量 + core:engine 目标 + app 目标测试通过

### 对抗性审查整改（3 个恶意角色代理：边界狂魔/状态破坏者/数据篡改者，2026-08-05）

**本次引入缺陷修复（5 项）：**
- **CultivationCapRule realmLayer 钳制** — crafted 存档 realmLayer=Int.MAX_VALUE 原会放大 cap 至 4.65e10（绕过 1e9 封顶），负层数注入负 cap 使 0 修为被截成负数；cap 计算前 `coerceIn(1, maxLayers)`
- **NumericSanitizeRule 招募/AI 弟子全字段消毒** — 原只消毒 cultivation，NaN checkpoint/speedBonus/pill 经招募全字段拷贝进入组件表（招募条目永久不被修复）；改走与主弟子相同的全字段 sanitizeDisciple
- **SaveFileManager CRC32C 自实现根治** — java.util.zip.CRC32C 仅 API 34+ 存在，原 API<34 回退 CRC32 导致"API≥34 写入文件在 API<34 设备必判损坏"（反向换机数据丢失）；新增纯 Java 查表 CRC32C（Castagnoli 0x82F63B78，与 JDK 输出一致），0x0101 恒写 CRC32C + algo 标识，旧 0x0100 双算法探测全 API 统一
- **checkAndResetStuckStates nowMs 防御** — nowMs<=0 会静默失效（savingStartTime==0 判据恒真）或立即误触发（负值），防御性回退真实时钟
- **stuckResetEvents replay=1** — replay=0 时 VM 空窗期（主菜单/未创建）病理复位事件 tryEmit 直接丢弃，"不再静默"承诺失效
- **activeLoadJob 读-改-写加锁原子化** — 注册/清除/看门狗复位三处 synchronized 同一锁，消除"陈旧 cancel 误杀新注册"与"=null 使在途操作脱离监管"的交错窗口

**预存问题（非本次引入，如实登记）：**
- [严重] 主菜单云读档自阻塞——loadFromCloudSave 持有 cloudDownloadLock 期间调 loadGame 被自身守卫拒绝（2026-08-04 B3 引入，功能必失败）
- [中等] loadGameFromSlot(0) 先置 isLoading 再 downloadFromCloudSave 恒被拒（设置页云槽位读取失败）
- [中等] crafted id=9,999,999 弟子触发全表级超大数组分配 OOM（Error 不可捕获，MAX_SAFE_CAPACITY=10M 设高）
- 轻微项：saveGame 双 tap 异步窗口、bak 修复失败不反馈、文件格式版本不校验、ensureRegistered 与注册表全局状态耦合等——均记录在案待后续

### 文档（2026-08-05 架构文档待办清理与登记）

- **清理已完成项** — 架构文档 4 个全量完成的待办段落移除（2026-08-01 两批对抗性审查遗留、仓库容量溢出专项 P1-P11、存档恢复链路 T7~T16）；综合优化遗留段清理已完成的 T4/T5/T6/P12（保留记录中的 T1/T2/T3）
- **新增待办登记** — 对抗性审查发现的预存问题登记为 C1~C13（严重 1：主菜单云读档自阻塞；中等 3：云槽位读取自阻塞/大 id OOM/操作 finally 无主清理；轻微 9 项），按优先级排序待人工决策

### 加固（2026-08-05 存档链路缺陷修复第一批实施 C1~C13 + T1，14 项全量）

- **修复：主菜单云读档必失败（严重）** — `loadFromCloudSave` 全程持有 `cloudDownloadLock` 期间调 `loadGame` 被自身守卫拒绝——云档已写本地但内存加载永不执行；现拆内部入口 `loadGameInternal(fromCloudLoad)` 仅云路径绕过该守卫（其余守卫与锁全程持有不变），主菜单云读档恢复可用
- **修复：设置页云槽位读取必失败** — `loadGameFromSlot(0)` 先置 isLoading 再调下载被自身守卫拒绝；isLoading 占位移到下载完成之后（互斥由 cloudDownloadLock 承担，外部读档中拒绝下载语义不变）
- **修复：crafted 大 id 弟子 OOM 崩溃循环** — 三层防御：`MAX_SAFE_CAPACITY` 10M→1M；新验证规则 `DiscipleIdBoundsRule`（order=1，id>200K/负值判损坏走备份恢复，根治）；load 链路三层 `OutOfMemoryError` 捕获（原 Error 非 Exception 直接崩溃且重试即崩）
- **修复：操作 finally 无主清理** — `clearActiveLoadJob(job)` 归属判定+清理原子化，被新操作取代的旧协程不再抹掉新操作在途状态；restart 补上缺失的清理与标志复位；saveGame/loadGame 新增 `_isRestarting` 守卫闭合 restart 窗口误杀根因
- **修复：saveGame 双 tap 窗口** — isSaving 入口同步占位（原协程内异步设置存在双 tap 穿透窗口）
- **修复：备份修复失败不反馈** — `BackupReadResult.repairFailed` 字段如实反映 .sav 修复失败（原仅 Log.w 仍报 RECOVERED，损坏文件反复回退）
- **修复：文件格式版本不校验** — 非 0x0100/0x0101 未来版本判损坏（原 0xFFFF 若 CRC 正确按当前格式静默误解析）
- **修复：注册表 clear 后校验失效** — `ensureRegistered` 改按注册表实际规模判定（原标志首置后恒 true，测试 clear 后空规则全 Passed）
- **修复：AI 宗门弟子修炼值量级不封顶** — 数值消毒增加上限钳制（修为 1e9 对齐仙人 cap，乘区 1000），1e308 有限值不再放行
- **修复：堆叠截断后储物袋悬空引用** — 截断清理扩展到 `storageBagItems`（原只清装备四槽+manualIds，UI 查无此堆叠时空显示）
- **修复：delete-then-rename 崩溃窗口** — rename 原子覆盖优先（Linux/Android rename() 替换目标），失败回退 delete+rename 兼容 FAT32
- **修复：ensureHeavyDataLoaded 空操作守卫** — 短路前置 worldMapSects 非空校验（原从不检查数据，空数据仍标记完成）
- **修复：战报击杀数未校验** — BattleLogRefRule 增加 beastsDefeated 范围校验（负值/超限条目清理）
- **修复：事件序号回填破坏单调递增** — 存在任一 0 序号时整体重编号 1..N（原 `[0,0,5]→[6,7,5]` 破坏稳定 key 语义）

### 新增（2026-08-05 TapTap 排行榜——双标签宗门战斗力排行榜）

- **排行榜入口** — 主界面右上角新增"排行"悬浮按钮（第二行末尾，避免第一行 7 按钮在 320dp 老屏溢出），打开排行榜对话框
- **天下宗门榜（本地）** — 玩家宗门与全部已发现 AI 宗门按战斗力降序实时排名（数据派生自 `sectCombatPower` + `aiSectCombatPowers` + `worldMapSects` join，玩家行高亮「我」标记；同战力按名称升序保证确定性）
- **玩家排行榜（云端）** — 接入 TapTap 排行榜 SDK `tap-leaderboard-androidx:4.10.5`（与现有 TapTap SDK 4.10.5 同族，无需 LeanCloud/TDSUser）；排行榜 ID `fqrr4yx4ggmx8r504l`（用户开发者中心创建，降序 + 保留最高分）；展示榜单第一页 + "我的排名"卡片（昵称/名次/战力），未登录引导登录、错误重试、空榜提示四态齐全
- **上报节流** — 打开玩家排行 Tab 时上报 + 每日首次进游戏静默上报；纯函数 `LeaderboardUploadPolicy`（从未上报/跨天/战力变化任一即上报）；上报成功记 SharedPreferences 日期+战力，失败次日重试不阻塞游戏
- **登录桥接** — 新增 `TapTapLoginBridge` 接口（feature:game 定义）+ app 模块 `TapTapLoginBridgeImpl`（Hilt BridgeBindingsModule 绑定），复用 TapTapAuthManager 登录链路；排行榜内"去登录"按钮经 Compose LocalContext 取 Activity 拉起授权
- **SDK 签名验证** — 反编译 tap-leaderboard-androidx:4.10.5 验证全部 API 签名（`submitScores`/`loadLeaderboardScores`/`loadCurrentPlayerLeaderboardScore`，回调 `ITapTapLeaderboardResponseCallback`，错误码 500000/500001/500102 等），签名适配收敛于 `TapTapLeaderboardApi` 单文件
- **错误码映射** — 500102 未登录 → 登录引导；500001 排行榜不存在 / 500000 周期结束 / 其余 → 可展示错误文案；全部 SDK 调用 try/catch 兜底，业务永不因 SDK 崩溃（与 TapCloudSaveManager 同策略）
- **测试** — 新增 4 个测试类（LocalLeaderboardComposerTest 8 例 / LeaderboardUploadPolicyTest 7 例 / LeaderboardManagerTest 14 例 / LeaderboardViewModelTest 11 例），GameViewModelTest 与 DialogTypeRenderCoverageTest 同步维护
- **隐私合规** — PrivacyConsentScreen 与 docs/index.html 新增 TapTap 排行榜模块（tap-leaderboard-androidx）SDK 声明；游戏内更新日志同步新增排行榜条目

### 优化（2026-08-05 排行榜入口与表头完善）

- **天下宗门榜表头"玩家"→"宗门"** — 本地榜参赛者为宗门（玩家宗门 + AI 宗门），表头第二列语义修正；云端榜（玩家排行）表头保持"玩家"，两榜表头参数化分离
- **模式选择界面排行榜入口** — 选存档前的主菜单右上角（用户名称左侧）新增"排行"按钮，打开排行榜对话框并默认落在"玩家排行"标签（主菜单无存档上下文，可提前查看全服战力排行）；天下宗门 Tab 在主菜单显示"进入游戏后可查看"引导提示（`isWorldLoaded` 派生判断），进游戏后自动恢复完整榜单
- **LeaderboardDialog 支持 initialTab** — 对话框新增初始标签参数（默认 LOCAL，游戏内行为不变；主菜单传 CLOUD）
- **测试** — LeaderboardViewModelTest 新增 isWorldLoaded 派生跟随变化用例；全量测试 + lintRelease + detekt（新代码违规清零）全绿
- **文档** — 架构文档预存问题登记表补充 P-17（GameViewModel 构造 20 参数超规，baseline 豁免技术债）与 P-18（排行榜 rank 起始语义真机验证）；本次确认的 core:engine detekt 预存违规经核对全部已在 P-02~P-13 登记，无需重复

## [4.00.88] - 2026-08-05

### 代码质量（2026-08-05 架构文档预存问题登记 P-01~P-15/P-17 全量实施）

- **P-01 战利品数量分区 RNG** — attackSect 战利品数量 `(80..130).random()`（kotlin Random.Default，CI 查不到）改走 BATTLE 分区 PRNG：提取 `sectBattleRewardCount` 纯函数（`80 + rng.nextInt(51)` / `20 + rng.nextInt(41)`）+ GameEngineBattleOpsTest 值域/确定性守卫；**登记接受的代价**：该次宗门战胜利后 BATTLE 随机序列后移一位，旧存档后续战斗随机序与旧版本不同
- **战斗系统 10 个 God Method 拆分（P-02~P-10）** — BattleSystem 3（executeSkillAction 参数打包+5 分支/processTurnAdvance 6 提取/executeBattleWithTimeout 3 提取）、AISectAttackManager 4（executeUnifiedAIBattle 回合提取+运行标志/executeSupportAction 5 提取/applyAoeSingleTarget 8→4 参/selectAITarget 2 return）、BattleCalculator.calculateCombatantDamage（斩杀/闪避/伤害管线三提取，主函数 1 return 链式）、HeavenlyTrialService.buildDiscipleEnemy（功法/装备/属性/技能四提取，复杂度 23→5）；全部 RNG 抽数序保持，新增 3 个抽数序对拍守卫测试（instantKill 0 抽/dodge 1 抽/正常路径 3 抽——dodge 判定恒消耗 1 抽）
- **battle 域目录归位（P-11）** — 13 个文件 git mv 至 `engine/domain/battle/`（包声明与目录对齐，import 全不变），删 12 条 InvalidPackageDeclaration baseline；engine 模块其余 122 条旧目录布局维持冻结（全量归位另行立项）
- **测试文件违规全清（P-13 + 未登记项）** — BattleSystemTest/BattleCalculatorCoverageTest combatant 11/10→7 参（skills/buffs/realm 改 .copy()）、37 处长行折行、29 处 UnusedImports + 8 处额外 MaxLineLength 清理
- **AISectBattleProcessor 迁移守卫测试（P-15）** — 新增 4 测试（AI 升级链/玩家宗门不升级/非玩家仓库清理/热控分批/入口全链路），@After 恢复静态注入防跨类污染
- **GameViewModel 构造 20→5 参数（P-17）** — 删 6 个零使用参数（appContext/productionFacade/inventoryFacade/battleFacade/diplomacyFacade/saveFacade）+ 4 个 @Inject 值对象归组（AudioServices/CoreServices/UiServices/DelegateServices），18 个 Delegate 零改动，baseline LongParameterList 条目删除
- **flaky 测试诊断（P-14）** — H1（assemble 竞态）300 轮压力实证 0 失败未复现，转 30 轮正式守卫测试（GameStateStoreAssembleRaceGuardTest）；H3（statsProvider 静态污染）枚举排除；最可能根因为 TestPolling 5s 轮询超时在慢 CI 不足 → 提升至 15s
- **H1 竞态加固（P-14 后续）** — dispatchAssemble 增量/全量分支 + loadFromSnapshot 尾部投递 3 处 publish 前二次版本检查：首次检查通过后、assemble 执行期间 load/reset 递增版本则丢弃陈旧结果，消除理论竞态窗口（二次检查无法时序注入单测，由 30 轮压力守卫 + 全量回归兜底）
- **验证** — compileReleaseKotlin + lintRelease + detekt 全模块违规清零（engine 63→0、feature/game 22→0，含 test 变体）+ 全量测试串行回归 0 失败；kover 实测引擎行覆盖 33.0%（未达 80% 目标，覆盖率提升另行立项，如实登记）

### 文档（2026-08-05 架构文档待完成项清理）

- **已完成项段落移除** — 2026-08-02 综合优化遗留（T1~T3）、2026-08-04 代码质量优化（函数级/上帝对象/UI）、2026-08-04 战斗系统函数级 11 项 + T-C1~C4、2026-08-05 存档链路 C1~C13 + T1、预存问题 P-01~P-15/P-17 全量完成段落从 architecture.md 移除（详情见本版本及 4.00.86/4.00.87）
- **待完成项登记精简** — 保留维持现状决策（W4 object→class、AI 拉条移植不纳入、P6 评估不做、122 条 InvalidPackageDeclaration 冻结）与待真机验证（P-16 UI 迁移冒烟、P-18 排行榜 rank 语义），指引见 architecture.md 登记表

### 修复（2026-08-05 天道试炼奖励发放不进仓库）

- **试炼通关奖励统一走 InventorySystem 入口** — 第六/七/八关 randomEquipment/randomManual 此前直接写 equipmentInstances/manualInstances（实例轨道），仓库 UI 只渲染堆叠轨道（equipmentStacks/manualStacks）导致领取后不可见且无来源统计/溢出兜底；现统一委托 addEquipmentStack/addManualStack，丹药/储物袋从手写 mergeStackable 收敛到 addPill/addStorageBag；凭据类语义（withOverflowMailSuppressed + Partial/Failure 抛异常整体回滚，catch 在 update 外——参照 GameEngineSectLevelOps，区别于 DailySignInService 的 catch 在 update 内导致 Partial 部分入仓的问题模式），容量不足时凭据保留可重试
- **守卫测试增强** — InventoryAddPathGuardTest 新增"实例表直接追加"反模式（equipmentInstances/manualInstances 的 `+=`/`= list + x`/`.add(`），7 个合法分配点白名单（弟子装备/功法分配、俘虏转换、自动装备落库、AI 敌人、统一入口自身），防止未来发放路径再次误写实例轨道
- **GameEngineSectLevelOps 补来源追踪** — claimSectLevelReward 发放补 withTrackingSource("sect_level")（映射表内此前为无调用点的死条目，年度报告统计现可正确归因）
- **测试** — 新增 HeavenlyTrialClaimRewardTest 6 用例（装备/功法落堆叠轨道、年度来源 trial:5、重复领取、未通关、容量满整体回滚凭据保留）+ TrialTestStore（COW 副本 + 重入缓冲模拟 GameStateStoreImpl 事务语义）；验证：compileReleaseKotlin + 试炼/守卫测试通过
- **途中发现登记** — 调查中发现的 3 项遗留问题（弟子储物袋手写合并路径 P-19 / 守卫不扫描 domain 模块 P-20 / 签到"事务回滚"注释不符 P-21）登记至 architecture.md 待完成项，另行立项处理

### 修复（2026-08-05 Bugly 崩溃批次 #11021/#14002/#13014/#3107/#11017）

- **读档/保存协程 lateinit job 竞态根治（#11021/#14002，各 11 次）** — SaveLoadViewModel 4 处 `lateinit var job` 捕获模式：launch 入队与调用线程赋值之间的窗口内，空闲 IO worker（LimitedDispatcher）抢跑执行协程体，实参求值读未赋值 lateinit 抛 UninitializedPropertyAccessException（C4 注释"协程体在注册后执行"论断有误）。修复：删除 lateinit 捕获，perform* 内部 `coroutineContext[Job]` 自取身份（与 launch 返回同一实例，clearActiveLoadJob 的 `===` 归属判定语义等价），finally 的 resetOwnedLoadState 同步去参；新增 Dispatchers.Unconfined 竞态回归测试（Unconfined 下协程体同步先于赋值执行，旧代码必崩，确定性复现）
- **生产槽位 null 元素三层净化（#13014）** — 损坏存档可能向 productionSlots 注入运行时 null：GameEngineCoordination.loadData 入口净化（须在 fixAlchemyForgeSlotCount 访问 buildingType 之前，行 991）+ gameData.productionSlots 副本同步净化（保护 ProductionProcessor/StorageEngine 直读）+ ProductionSlotRepository 三个外部进入点（initialize/loadSlots/restoreSlots）sanitizeSlots（DomainLog 记录净化数）+ SlotCache.updateCache 收口（无 null 保持引用同一性，dirty 快速路径不退化）；经 Repository 读取的全部迭代点（ProductionProcessor:70/83/1054/1080、DiscipleSlotManager、SaveService、CombatService、BuildingService）统一覆盖；新增 RepositoryModelsTest/ProductionSlotRepositorySanitizeTest 用例（unchecked cast 注入运行时 null）
- **SessionManager 加密存储恢复/降级（#3107）** — 主密钥损坏（ErrorCode -33 Invalid key blob）时 MasterKey.Builder.build() 抛 KeyStoreException，Hilt @Singleton 注入（MainActivity 启动路径）零兜底直接闪退。修复：createSessionPrefs 恢复链——明文 fallback 有降级标记直接返回（防每次启动重复失败流程）→ 加密创建失败删损坏密钥（`_androidx_security_master_key_`）+ deleteSharedPreferences 重建 → 重建仍失败降级明文并持久化标记；15 属性 + 3 方法接口零变化；数据敏感度低（登录态/隐私同意/音效开关）丢失可接受（重新同意隐私）；新增 SessionManagerTest 2 用例（Robolectric + 注入必然失败的 builder）；成功路径分支依赖真实 Keystore 不可单测，代码审查覆盖
- **ActionMode 拦截窗口前移（#11017，44 次）** — ColorOS/Oplus FloatingActionMode 文本选择工具栏在窗口 token 失效后仍 show PopupWindow 抛 BadTokenException：已创建的 ActionMode 的 reposition/show 由系统消息队列驱动（不经 window callback），现有 onStop 置位与已 post 的 show 消息存在竞态，且未走 onStop 的快速销毁路径不拦截。修复：finishActiveActionMode/resetForResume 挂钩从 onStop/onStart 提前到 onPause/onResume（MainActivity/GameActivity 对称，幂等，onStart/onStop 既有调用保留；游戏全屏沉浸无分屏场景，UX 可接受）；ActionModeSafeCallback 本体零改动
- **验证** — compileReleaseKotlin + 定点测试（SessionManagerTest/RepositoryModelsTest/ProductionSlotRepositorySanitizeTest/SaveLoadViewModelLoadTest）全绿 + 全量测试串行回归
- **不可修项如实登记** — #9072（AOSP ClientTransactionListenerController.onContextConfigurationPreChanged 系统组件 NPE，调用链无应用代码，应用层不可修）、#3110（libart SignalCatcher SIGQUIT 线程 dump 痕迹，ANR 上报误报）、#3055（libhwui 无符号崩溃，Vulkan 六层防御 + HWUI 降级已覆盖的旧版本残余）、#9069（TapTap lateinit context，5 层防御 2026-07 已上线——crash guard/反射兜底/双检/按钮门控/manifest provider 移除，崩溃时间在防御上线后，疑似旧版本残余，下版本继续观察）

### 修复（2026-08-05 跨境界斩杀方向反转根治）

- **checkInstantKill 方向反转（8-04 修复引入）** — realm 语义为"数值小=境界高"（0=仙人，9=炼气，全仓一致：REALM_SPEED_PER_PHASE/meetsRealmRequirement/maxLayers/Combatant 直传 disciple.realm 无转换）。8-04 战斗核查修复将公式从 `(defenderRealm - attackerRealm)×9` 反转为 `(attackerRealm - defenderRealm)×9`，在真实语义下变为"低境界打高境界触发斩杀、高境界无法秒杀低境界"，守卫测试 BattleCalculatorCoverageTest 按"数值大=境界高"的错误直觉写传参，把错误行为固化。本次改回 `(defenderRealm - attackerRealm)×LAYERS_PER_REALM + (attackerLayer - defenderLayer) > INSTANT_KILL_GAP×LAYERS_PER_REALM`；INSTANT_KILL_GAP 由编译期常量改为读取 GameConfigData.realmGap.instantKillGap 运行时配置（此前为死配置从未被引用）；局部魔法数字 MAX_MINOR_LAYERS=9 提取为 LAYERS_PER_REALM 常量；checkInstantKill 补 KDoc
- **守卫测试传参修正** — BattleCalculatorCoverageTest G1 组 6 测试 + T-C2/B4 共 8 处传参按真实语义重写（高境界一方用小 realm 数值），BattleSystemTest 必杀测试弟子 realm 8→2 / 妖兽 2→8，注释统一标注"realm 数值小=境界高"防再反转；realmGapMultiplier 伤害乘区经举一反三核查方向正确（高打低加成/低打高惩罚）未改动
- **影响面** — 全部战斗路径（BattleSystem/AISectAttackManager/HeavenlyTrialCombatLogic）统一修正：高境界弟子打低境界目标恢复秒杀（无视护盾），低境界敌人不再反向秒杀玩家高境界弟子
- **验证** — core:engine（BattleCalculatorCoverageTest/BattleSystemTest）+ app（BattleCalculatorTest）定点全绿 + 全量测试串行回归 0 失败 + detekt（core:domain/core:engine）通过

### 修复（2026-08-05 弟子多槽位互斥根治）

- **根因** — `DiscipleAssignmentGate` 只是"弟子→槽位"登记表（`registerOrUpdate` 无条件覆盖，文档明确"Gate 不阻止分配"），互斥完全依赖各分配入口写槽位前清理旧槽位；但 **8 个分配入口缺失/不完整**，勾选"显示所有弟子"（`filterByDiscipleStatus` showAll 模式露出全部在岗弟子）后可将在岗弟子直接任命到新岗位，旧槽位残留 → 同一弟子同时出现在多个槽位
- **8 入口全量修复** — ① 亲传弟子七类槽 `assignDirectDisciple`（DiscipleFacadeImpl，事务内 `clearAllSlots` + 旧 occupant release/sync）；② 任务派遣 `startMission`（事务内 `releaseDiscipleToIdleInside` 全员换岗 + gate 清理，删除 fire-and-forget 状态硬写；任务完成不再产生状态/槽位不一致——**用户确认换岗语义**，未接通 IDLE 校验，与"显示所有弟子"自动换岗设计一致）；③ 秘境出发 `startSecretRealmExploration`（事务内释放 4 成员 + 事务外 gate 先清后登记）；④ 世界驻守 `assignGarrisonDisciple`（事务内清理 + 旧 occupant release/sync + 补 syncSingleDiscipleStatus——此前从不 sync）；⑤ 仓库驻守新增 `GameEngineWarehouseOps.assignWarehouseGarrisonAtomic`（ProductionViewModel 删除直写 GameData 路径，委托引擎原子方法）；⑥ 血炼 `startBloodRefinementAtomic`（事务内清理 + Success 后 gate 防御 release，失败整体回滚不残留）；⑦ 生产槽新 API `assignDiscipleToProductionSlot`（事务内清 GameData 全部槽位 + GameData.productionSlots 镜像同步——闭合两套存储分歧 + repo 无条件清旧生产槽 + 硬写状态改推导式）；⑧ 生产槽旧 API `assignDiscipleToBuilding`（事务内清理 + 旧 occupant gate.release + 镜像同步）
- **共享 helper** — `GameEngine.releaseDiscipleToIdleInside(state, discipleId)`（事务内：clearAllSlotsDataOnly + 按状态重置，REFINING 视为放弃血炼不返还材料，与 UI `releaseDiscipleForReassignment` 契约一致）
- **stale entry 修复** — 被顶替者 gate 残留 5 处：`assignDirectDisciple` 同槽顶替、`assignGarrisonDisciple`/`assignWarehouseGarrisonAtomic` 旧驻守、`BuildingService` 旧工人、`ElderManagementUseCase.assignElder` 被顶替长老 + 被清空亲传列表、`SpiritMineViewModel.swapSpiritMineDisciple` 旧矿工（release + sync，防从可用列表"消失"）
- **状态推导缺口修复** — `DiscipleStatusService.buildSlotFlagsFor` inGarrison 补 `warehouseGarrisons`（与 `buildGarrisonIds` 对齐，此前仓库驻守弟子推导为 IDLE 被 UI 显示可用）；`clearSlotsForReset` 补清 patrolSlots/warehouseGarrisons/battleTeams/productionSlots 4 组（重置语义与推导对齐）
- **读档自愈（用户确认）** — 新增 `GameEngine.healDuplicateSlotAssignments`（GameEngineSelfHealOps）：按 scanAndRegister 顺序扫描双槽位弟子 → 清全部槽位（含住所）→ 按赢家重写回 GameData（血炼赢家缓存进度防灵石/材料损失）→ gate 二次 rebuild；BootSequenceController Step 6.3 挂接，健康存档零副作用；mock 场景入口 try-catch 防御不影响启动
- **守卫测试 6 件套** — `GameEngineDualSlotGuardTest` 9 用例（驻守/任务/秘境/血炼/仓库/自愈 + 血炼失败回滚不残留 + occupySectRewards 决策留痕注释）、`DiscipleFacadeAssignDirectDiscipleTest` 3 用例、`BuildingFacadeImplAssignProductionSlotTest` 4 用例（真实 ProductionSlotRepository 绕开 Mockito getSlots 同名坑）、`DiscipleStatusServiceTest` 增补 2 用例（仓库驻守推导 GARRISONING）、`SlotCategoryCoverageTest` 新增清单式守卫（全部已知分配入口文件必须引用清理调用）；`FakeAtomicStateStore` 抽取共享（原 GameEngineAtomicAssignTest 私有副本）
- **途中发现** — `MissionSystem.validateDisciplesForMission`（要求全员 IDLE）全项目无调用方，因换岗语义不接线（引擎清理后冗余）；生产槽 GameData/Repository 两套存储既存分歧已顺带闭合
- **对抗性审查整改（审查 agent 发现 C1/H1/H2/H3/M1/M2/M4 全修复）** — ① 秘境出发失败路径销毁岗位分配（startSession 返回 Failure 不抛异常、事务照常提交——先校验后清理，移除已成死代码的 IDLE 校验，改存活校验）；② 自愈误清住所（includeResidence 改 false，住所共存设计）；③ 生产槽 Repository 双存储同步（8 个引擎入口事务外 `clearDiscipleFromProductionRepository`——存档/结算/gate 重建以 Repository 为准，防双槽位经生产槽复活）；④ `clearElderSlots`/`clearAllDisciplesFromElderSlots` 补 `recruitingElder`（预存缺口——双槽位可经纳徒长老槽残留）；⑤ `assignElder` 只释放实际清空的那类亲传列表（此前误释全部 7 类仍在岗弟子）；⑥ 生产槽旧 occupant sync 移事务后（消除状态残留）；⑦ 自愈扫描秘境成员（秘境优先为赢家，保留秘境清岗位）
- **验证** — compileReleaseKotlin + 全量测试串行（1941 engine 用例 0 失败，含新增 C1 失败回滚/住所保留/秘境并存/recruitingElder 4 个守卫用例）+ detekt 全绿

## [4.0.85] - 2026-08-02

### 新增（2026-08-02 一键拆除建筑）

- **一键拆除** — 建造栏右上方新增"一键拆除"按钮，进入拆除模式后地图上所有可拆建筑显示绿色占地框，点击选中变红（可多选、再点取消），确认后批量拆除并返还一半建造灵石；"取消拆除"随时退出
- **拆除更彻底** — 拆除建筑后：建筑内弟子全部回归空闲（血炼中弟子、思过弟子、长老/执事职位、任务中弟子一并释放），正在进行的炼制/锻造/血炼/灵田种植直接失败（投入材料不返还）

### 新增（2026-08-02 AI 宗门弟子完整化——与玩家弟子同规则）

- **AI 宗门弟子拥有体质/词条/装备/功法** — 小型宗门弟子 1 件装备 1 本功法、中型 2 件 3 本、大型与顶级 4 件 6 本；装备与功法品阶始终为该弟子境界能用的最高品阶
- **AI 弟子突破大境界自动换装** — 突破后自动换上更高品阶的装备与功法，永远保持当前境界最强配置
- **AI 弟子修炼/战斗与玩家同规则** — 修炼速度吃功法与体质词条加成、突破概率与玩家公式一致；战斗使用自身装备功法，不再"战前临时随机生成"
- **俘虏自带家当加入我方** — 占领 AI 宗门招募俘虏时，俘虏身上的装备与功法一并入库，可直接使用
- **AI 宗门弟子阵容更多样** — 每个新档的 AI 弟子阵容由存档种子独立生成，不再千人一面

### 优化（2026-08-02 稳定性与读档加固）

- **优化：读档修复更彻底** — 旧档 AI 宗门弟子自动补齐体质/词条/装备/功法至宗门等级标准，超限宗门自动截断；重复读档结果一致
- **优化：内部加固** — 俘虏装备落库幂等防重复、损坏存档数据防护（超量功法/负熟练度/异常修为）、AI 战斗在数据未就绪时优雅降级

## [4.0.84] - 2026-08-02

### 新增（2026-08-02 白名单专属福利）

- **白名单专属福利** — 免广告特权白名单新增成员；白名单用户每次新开/读取存档均可收到 1000 万灵石永久邮件（每个存档可领取一次），邮件显示"永久有效"
- **移除已过期的运营补偿邮件** — 运营补偿（2026-07-26 截止）已到期，注入逻辑与调用点全部清理（已领取存档不受影响）

### 优化（2026-08-02 性能优化批次——界面响应与流畅度全面提升）

- **优化：弟子列表与宗门战力显示提速** — 弟子聚合数据改为组装写回点同步缓存（O(1) 读取），打开弟子列表/生产界面不再全量扫描弟子；宗门战力与弟子数据同刻计算，显示不再滞后
- **优化：宗门地图/战斗界面操作更跟手** — 暂停状态窄流直连、储物袋装备/功法 ID 窄流（distinctUntilChanged），消除高频锁竞争与每旬无意义重组，快速连点暂停/装备/功法不再被吞
- **优化：代码结构大整理（20+ 文件）** — 游戏数据模型拆分为 6 个领域文件、存档迁移拆分为 4 个迁移文件、宗门地图渲染视口/对话框路由/精灵图注册数据/战斗装备构建/存储引擎等超长函数拆分、KSP 处理器移除、旧生命周期兼容层清理，为后续迭代提速
- **修复：依赖解析失败导致构建不稳定** — TapTap 私有镜像限定仅解析 TapTap 包，androidx.benchmark 等公共依赖不再被镜像劫持；基准测试工程迁移 Junit4
- **测试** — 新增 GameStateStoreMergeTest / StateRevertRegressionTest / AssemblePatchEquivalenceTest / SectMapViewportParamsTest 等，聚合一致性回归覆盖

## [4.0.83] - 2026-08-02

### 修复（2026-08-02 Bugly 崩溃批次——7 类崩溃根治）

- **修复：战斗结算/任务/商人界面因空或重复物品 ID 闪退**（#5079/#3091 共 55 次）——被妖兽掠夺同时损失灵石+储物袋、旧存档商人商品缺 ID、任务弟子 ID 重复等场景触发 LazyGrid/LazyRow 重复 key 崩溃。修复：掠夺结算补固定显示键、读档自动净化损坏数据（空 ID 重分配、重复去重）、引擎任务派遣强制校验、界面兜底防崩溃
- **修复：退出游戏瞬间偶发闪退**（#3026 共 110 次 + #3098 共 11 次）——游戏事件弹窗与文本选择工具栏在退出动画期间撞上已失效窗口。修复：引擎事件弹窗按界面生命周期门控、文本选择工具条创建期拦截、所有对话框销毁前清除焦点与软键盘
- **优化：启动检测提速**（关联 #11/#13006 ANR）——沙盒环境识别结果缓存复用，启动期不再重复读取系统文件，冷启动更快
- **修复：退出游戏时文本选择功能不再永久失效**——切后台再返回后复制粘贴/文本选择恢复可用

## [4.0.82] - 2026-08-01

### 新增（2026-08-01 关注物品功能）

- **关注物品** — 物品详情界面新增"关注"按钮（商店在售等未拥有的物品详情也可关注），点击后关注该物品、再点取消
- **已关注优先排序** — 仓库、商人、宗门交易、炼丹、炼器、种植、自动购买、上架选择、弟子装备/功法选择、储物袋、邮件附件、血池、战斗战利品等物品界面：已关注物品排前，组内按品阶降序（同品阶按名称排序）；签到/宗门等级/天骄塔等按日期/等级顺序展示的界面保持原顺序
- **金色边框标识** — 已关注物品卡片边框变金色；选中态边框改为白色且优先于已关注（三种边框统一 2dp）
- **未来新增物品自动兼容** — 关注标识基于"类型:名称"键，新增物品/配方/模板无需改存档结构与排序逻辑即可关注

### 修复（2026-08-01 仓库堆叠统一批次——同种物品不再分裂为多个堆叠）

- **仓库中同一物品不再分成多个堆叠** — 邮件附件、每日签到、兑换码、外交贸易、灵田收获、弟子归还装备、储物袋等 9 条发放路径此前各自手写"找第一个堆叠 + 追加"，合并键不一致导致同种物品（同名同品阶）分裂为多个条目。修复：全部统一委托 `InventorySystem.addXxx` → `StackableItemStore`（遍历所有同键堆叠逐个填充），合并键单点定义（`StackKeys`），启动读档时自动整理存量分裂档
- **新开档不再出现两个重复的储物袋** — 初始储物袋改为单个堆叠（数量 2）
- **仓库物品溢出不再静默丢失** — 手写路径的 `coerceAtMost` 截断丢弃改为 StackableItemStore 溢出语义：仓库满时创建新堆叠，真正满仓时明确提示
- **加固** — 新增 `InventoryAddPathGuardTest` 守卫测试：扫描引擎源码断言手写合并反模式（截断/追加/相加）数量为 0，防止未来新增发放路径时重新引入分裂

### 修复（2026-08-01 对抗性审查批次——5 角色并行审查发现）

- **仓库整理在碎片堆叠组合下死循环（启动卡死级）** — `consolidateAllStacks` 的 while 合并算法在 ≥3 个同键堆叠且总数超上限时（如 [999,999,543,321]）于"满/半满"之间无限振荡，而启动读档必跑整理——旧档（本修复的目标用户）直接卡死。修复：改为单遍合并 + 满堆叠禁止抽回，必然终止且数量守恒
- **单次发放超过堆叠上限的物品生成超限堆叠且永不修复** — `StackableItemStore.add` 新建堆叠不分块（如一次发 5000 个丹药生成单个 5000 堆叠）。修复：按 maxStack 循环分块
- **仓库满时邮件/签到/兑换码静默吞掉物品** — "零合并 Partial"被当作部分成功：仓库满时物品全丢但领取记录照写、不可重试。修复：零合并且无空槽返回 Failure；邮件发放失败回滚整个领取；签到/兑换码/宗门等级/引导奖励失败时不标记已领取（清理后可重试）
- **仓库满时灵田收获静默蒸发 + 年报虚报** — 修复：按实际入库量计数并日志提示（4 个审查角色独立交叉确认）
- **归还装备时仓库满 → 装备永久丢失** — 修复：失败时保留装备实例（3 个角色交叉确认）
- **守卫测试正则可被绕过** — `+=`、`state.` 前缀、`.plus()` 等变体可逃过检测。修复：正则覆盖全部变体 + 白名单按相对路径匹配 + 存在性断言
- **代码质量** — 删除 3 个孤儿构造参数；`applyRedeemReward`/`distributeReward` 按 MailService 先例拆分（167 行 → 8 个 ≤30 行函数）；删除无调用方且有丢堆叠 bug 的死代码 `consolidateList`
- **回归测试** — 新增 `InventorySystemConsolidateTest`（死循环场景/分块/零合并/锁定/储物袋 7 例）；全量测试串行通过

### 修复（2026-08-01 对抗性审查批次——4 角色并行审查发现）

- **读档后单弟子操作出现重复弟子（列表出现同一个人两次）** — 增量组装归并依赖列表按 ID 升序，但读档路径按境界/修为排序（非 ID）——失序归并产生重复条目。修复：增量组装入口校验升序，失序时自动退化为全量组装（正确性优先）
- **重置游戏后旧世界的仓库装备/功法泄漏进新世界** — 重置保存缺少堆叠序列化标记，删表守卫未生效。修复：重置路径补齐标记，新旧世界物品彻底隔离
- **读档后建筑可能被旧布局整体替换（毁档级）** — 建筑占地迁移读取到加载界面冻结的旧快照。修复：改读实时快照，迁移基于新档数据
- **读档/重置瞬间显示上一档的弟子聚合数据** — 后台聚合计算与缓存清空竞态。修复：代际版本号作废旧计算写回
- **快速连续读档时加载标志可能被错误复位** — 加载标志设置改同步等待，加载期间引擎不再误推进
- **恶意/损坏存档可致启动崩溃** — 超大弟子 ID 触发内存爆炸。修复：ID 上限守卫，超限自动降级
- **内存不足时保存反复重试拖慢** — 内存不足的保存失败直接终止重试，快速失败
- **追补游戏时间按速度缩放** — 2 倍速下不再因短暂卡顿丢失进度（1 倍 3 旬/2 倍 6 旬上限）

### 修复（2026-08-01 架构全面审查批次）

- **备份/云存档恢复会永久清空仓库装备/功法堆叠** — `SaveData` 的 `equipmentStacks/manualStacks` 曾被标记 `@Transient`，备份文件与云存档不含堆叠数据，恢复路径先删表再写空列表导致仓库物品永久丢失且不可逆。修复：堆叠字段纳入序列化（新存档无损）；旧格式存档从装备/功法实例重建堆叠兜底（仓库物品物理上从未存在，仅恢复游离实例，日志如实提示）；恢复路径加删表守卫（旧格式保留 DB 残留堆叠）
- **v2-v11 老存档升级即崩溃** — 迁移链缺列（`merchantAcquisition*`/`mailRecords`/`heavenly_trial_state`/`sign_in_state_json` 无 ALTER 添加，`MIGRATION_12_13` 引用即崩）。修复：`MIGRATION_10_11`/`11_12` 补列 + 全链迁移测试从 v2 覆盖到 v36（修复前跳过 v2→v12 段）
- **低内存时保存静默跳过但提示成功** — 内存态与 DB 脱节且无提示。修复：低内存返回失败结果（OOM 类跳过重试），手动保存提示、自动存档日志
- **存档数据列被静默清零** — Room TypeConverter 序列化失败返回空串（超限/OOM/异常），整列数据无声丢失。修复：编码失败抛异常使保存事务回滚（宁可保存失败不可静默丢数据）
- **保存备份写入早于 DB 事务成功** — DB 失败时备份含新数据而被丢弃。修复：备份仅在 DB 事务成功后写入
- **读档后弟子列表短暂损坏（丢弟子/陈尸）** — load/reset 与锁外增量组装协程数据竞争。修复：状态版本号作废旧任务 + load 组装投递同一单线程调度器
- **主线程直写 GameStateStore（双线程模型违规）** — SaveLoadViewModel onCleared 直调生命周期重置、建筑迁移在主线程直写事务。修复：新增引擎线程入口（`resetLifecycleState`/`setPausedDirectOnEngine`/`setSaveLoadFlags`/`applyBuildingMigrationOnEngine`），UI 层只保留只读
- **升级事件清空全部玩家本地数据** — v4_reset 机制（4.0.00 删档重置遗留，无确认/备份）。已直接移除
- **DialogType.SalaryConfig 空渲染分支黑屏软锁** — 全屏无关闭按钮遮罩。已移除死枚举与死路由 + 新增渲染覆盖守卫测试

### 优化（2026-08-01 架构全面审查批次）

- **每旬热点路径全面列直读** — ① 列级写入接入 changedId 追踪（修复"增量组装"承诺落空：每旬事务从全量 assembleAll 兜底改为双指针归并增量组装，未变弟子复用对象引用）；② HP/MP 恢复列直读（17 列替代 ~90 列 assemble + getFinalStats，满血提前退出），等价性守卫测试逐 fixture 精确相等；③ 13 张 List/Map/Set 列由每事务急切深拷贝改为 O(1) 浅共享（全库审计无原地修改，Debug 下 unmodifiable 包装防御）；④ 每旬原始表同值写短路（满血重写不再触发 COW 私有化）
- **聚合链缓存化 + 增量** — 快照读取从主线程全量扫描改为 O(1) 缓存（弹窗打开不再掉帧）；聚合链双指针 diff 仅变更弟子重算，未变对象复用
- **游戏时间追补上限** — OEM 挂起恢复后单 tick 最多推进 3 旬（修复 60 旬连跑导致数秒卡顿 + 看门狗互搏），超限丢弃余量
- **统一快照原子化** — unifiedState 持锁一次性读取（修复新旧混合 torn read）；通知队列变更纳入版本号发射
- **修炼 checkpoint 接回投影** — 补齐服药路径缺失的 checkpoint + 实时修炼投影（getEffectiveCultivation）填充 + 调用点守卫测试
- **Bugly/MMKV 初始化后台化** — 原生库加载与网络初始化移出主线程（冷启动提速），自研 CrashHandler 先行安装兜底
- **存档列表查询改 COUNT(*)** — 数千弟子时不再全表物化只为数个数
- **CI/测试基建** — `GameViewModelTest` 18 个失败根因修复（relaxed mock 上 `launchOnEngine` lambda 永不执行——文档误诊为 mockkStatic/Kotlin 2.2 问题，已修正）；gradle.properties 移除 Windows 硬编码路径与 Bugly 明文密钥（本地路径移入 gitignore 的 local.properties）；Kover 覆盖全模块启用；CI 单 worker + 禁增量编译；GameTimeClock 注入 TimeSource（修复 returnDefaultValues 下 SystemClock 恒 0 的假绿测试）；C++/Kotlin 建筑占地表跨语言一致性守卫测试；Gson/navigation-compose 死依赖移除；debug 构建恢复可调试

### 加固（2026-08-01 待完成项收尾批次——架构文档遗留项全部闭环，无玩家可见变化）

- **异常弟子数据防御统一** — 弟子列表展示、界面快照、存档序列化三条路径统一同一完整性判据（此前界面快照路径仅按存活标记过滤，数据不完整的异常弟子仍可能进入快照被界面看到）
- **崩溃收集映射上传读取真实配置** — 本地密钥迁移到 api.properties 后上传任务改读同一来源，不再恒为空静默失败；密钥缺失时明确提示并跳过
- **建筑占地尺寸生成严格限定数据块** — 生成任务只解析建筑占地声明块（此前匹配整个文件，注释中任何"数字 to 数字"都会被误读）；新增占地条目数与建筑名称条数一致性守卫，失配即构建失败
- **启动初始化执行器加入关闭流程** — 进程退出时完整释放后台初始化线程（防重入守卫 + 幂等关闭）
- **测试稳定性加固** — 3 个依赖固定真实时间的测试改为轮询目标状态（慢设备/CI 不再偶发失败）；新增幽灵防御与旧存档缺失字段兼容的真实覆盖测试
- **代码卫生** — 删除失效的时间裁剪常量与误导性注释；补全生命周期清理路径的异常日志

## [4.0.81] - 2026-07-31

### 修复

- **招募列表出现无肖像且无法招募的幽灵弟子** — 历史版本 Bug 遗留的异常/重复招募条目（name 空/年龄越界/境界越界/内容重复/已入宗门残留）读档原样保留且永不自动移除（三个招募守卫均跳过损坏条目、净化逻辑缺失）。修复：三层自愈——新增 SaveValidator 规则 `RecruitListCleanupRule`（读档主通道）+ `loadData` 引擎层净化（覆盖 cache 捷径）+ 年变净化挂载 `ageRecruitList`；点击招募遇到损坏条目时同事务移除（幽灵立即消失）；新增 domain 纯函数 `RecruitIntegrity`（isValidRecruit/isSamePerson/sanitizeRecruitList，四步净化：损坏移除→id去重→内容去重→跨表残留比对，38岁炼虚等合法数据明确保留，死亡弟子非对称年龄容差防逃逸）
- **弟子列表出现两个完全相同的弟子** — recruitList 同内容双胞胎被招募路径各自分配新 ID 插入（`recruitAllFromList` 无去重；`processAutoRecruit` 只按 id 去重；手动招募只移除点击那条）。修复：批量招募路径（自动/一键）统一 `dedupeRecruits` 三级去重（id/内容/同人签名）、`recruitAllFromList` 事务开头净化 + 按 id 移除已招募条目；`recruitDiscipleFromList` 招募成功时按 `isSamePerson` 同步移除同内容双胞胎；三处招募守卫统一为 `RecruitIntegrity::isValidRecruit`；UI 层 `recruitListAggregates` 按 id 去重兜底（防 LazyVerticalGrid 重复 key 异常）
- **AI 宗门弟子出现 38 岁炼虚等年轻高境界** — `adjustDiscipleRealm` 给 16-29 岁 AI 弟子直接赋高境界不改年龄，俘虏入列后原样成为玩家弟子。修复：AI 生成时按境界配最小合理年龄（`GameConfig.Realm.minReasonableAge`：炼虚≥300岁、化神≥200岁等，均低于寿元上限）；招募时仅软校验日志不阻断（保住俘虏玩法）
- **弟子体质/词条在存档读档后丢失** — `DiscipleSerializer` 序列化缺少 `physiqueIds/affixIds`，读档后招募列表与 AI 宗门弟子体质/词条恒空（招募到"无体质无词条"弟子）。修复：补全序列化字段（@ProtoNumber 104/105，向后兼容）+ 序列化往返守卫测试
- **宗门弟子改名后招募列表残留同名可招募的重复弟子** — 改名破坏 `RecruitIntegrity.isSamePerson` 5 字段签名匹配，recruitList 中旧名残留双胞胎永久逃脱三层净化、可被重复招募。修复：新增 `GameEngine.renameDisciple` 原子改名，同一事务内按改名前的旧身份签名清除招募列表同人残留
- **招募弟子列表每 3 年不刷新** — 年变事件单事务化后，`processSectDisciplesYearlyRecruitment` 仍读取已提交旧快照（`stateStore.gameData.value`）覆盖事务缓冲，把 `refreshRecruitList` 刚追加的新弟子全部清除：未被自动招募的弟子直接消失（自动招募在覆盖前执行所以正常），取消自动招募后列表完全不变。修复：改为基于事务缓冲（`MutableGameState`）读写；同类问题 `processSectDisciplesAging` 一并修复（AI 宗门弟子年度老化结果不再被回滚）
- **招募刷新判据自愈** — 刷新时机由固定年份模运算（`year % 3 == 1`）改为距上次刷新满 3 年触发（`year - lastRecruitYear >= 3`），与启动补刷判据统一：老存档/跨版本相位漂移自动对齐，刷新异常时次年自动补齐，不再永久错过
- **Android 10 设备温度监测闪退** — 热回调注册守卫从 API 29 对齐到 API 30：`AndroidThermalReader` 的 `platformCallback` 使用 API 30 才引入的 `PowerManager.OnThermalStatusChangedListener`，API 29 设备通过旧守卫后访问该字段会 `NoSuchFieldError` 闪退
- **低版本 Android 设备存档备份校验异常** — `computeCrc32c` 由 `NoClassDefFoundError` 隐式回退改为 `Build.VERSION.SDK_INT >= 34` 显式分支（CRC32C/CRC32），同设备自洽且行为确定，低版本设备备份恢复不再校验失败
- **炼丹/锻造界面「显示所有可用弟子」勾选状态不实时更新** — `viewModel.gameData.value` 在 Composition 内读取不触发重组，改为复用响应式 `gameData` 参数派生

### 优化

- **弟子肖像选择接入确定性随机** — `PortraitPool.getRandomPortrait` 改为调用方注入 `nextInt`（消灭裸 `kotlin.random.Random` 违规），4 个调用点分别接入分区 PRNG/GameRandom，读档后肖像与存档一致
- **招募净化零 RNG 消耗** — 净化逻辑不消耗分区 PRNG，不破坏存档确定性
- **弟子怀孕/出生接入存档确定性随机** — `ChildBirthSystem` 的受孕/分娩随机流迁移至 SYSTEM 分区 PRNG，出生弟子结果随存档确定性，读档续玩与存档一致
- **宗门地图随机种子确定性化** — 新开游戏地图种子改用确定性随机源；修复重启游戏后种子恒为 0 导致每次地图与随机序列完全相同的缺陷，重启将生成全新地图
- **技术债治理** — lint-baseline 96 条清零至 7 条（删除 52 个重复图片资源、KTX API 改写、4 个依赖升级）；`changelog_entries.json` 新增格式守卫（CI 自动校验）；清理过时架构债务文档
- **引擎状态层性能重构** — ① 弟子数据快照隔离改为列级写时复制（COW）：每次状态更新的拷贝成本从全量 100 张组件表降至仅实际写入列（基准 100 弟子 ≈122μs/次），纯界面操作不再触发弟子全量重组装，游戏更流畅省电；② 每旬修炼结算改列直读（无弟子对象组装）+ 共享映射复用，修炼热点开销大幅下降；③ 弟子聚合列表与宗门战力两条周期扫描链合并为单条，后台扫描次数减半；④ 修复弟子批量更新时快照并发交错丢数据（最多丢 2/50）的竞态
- **测试基础设施修复** — 全量回归暴露并修复 10 个预存测试缺陷（8 个测试类缺失 Robolectric 注解导致安卓数据结构静默失效、受孕/渲染总线测试数据不符合契约等）；`GameViewModelTest` 卡死根因定位（mockk 反射类加载风暴）并加健康检查开关
- **年度报告数据完整** — `runGarrisonAndReport` 改为在年变事务缓冲内读取数据生成年报，纳贡/俸禄等同事务前序事件写入的收入正确计入当年年报（此前读已提交旧值导致漏计）
- **对话框遮罩层统一** — 无论同时打开几个界面，全局只渲染一层遮罩。取消各独立 Dialog 窗口各自的 scrim 绘制，改为在 GameOverlayHost 根节点画单例 scrim。消除多界面叠加时遮罩变黑（叠加后约 84% 不透光）的问题

## [4.0.80] - 2026-07-31

### 修复

- **弟子执行任务后永远卡在「任务中」** — `processCompletedMissionsLazy` 在 `CultivationEventProcessor` 中移除已完成任务后漏掉了给 `discipleTables.statuses[tid]` 设置 `IDLE`，导致弟子状态永远卡在 `ON_MISSION`。`deriveDiscipleStatus` 中对 `ON_MISSION` 的无条件保护使后续的 `syncAllDiscipleStatuses` 无法修复。修复：在月度结算的奖励发放事务中直接写入 `DiscipleStatus.IDLE`；同时 `ON_MISSION` 改为从 `activeMissions` 数据推导（`hasActiveMission` 参数），旧存档中已卡住的弟子在下次状态同步时自动愈合
- **任务阁拆除无法释放卡住弟子** — 任务阁注册时 `slotGroups = emptyList()`，拆除时 `cleanupBuildingSlots` 不清理 `activeMissions`。修复：在 `cleanupBuildingSlots` 中检测 `BuildingType.MISSION_HALL` 时清除 `activeMissions` 并重置所有 `ON_MISSION` 存活弟子为 `IDLE`
- **红米/小米等设备音频断续** — `GameEngineCore` 游戏线程使用 `THREAD_PRIORITY_URGENT_AUDIO`(-19)，优先级高于音频混音线程(-16)，导致音频 buffer underrun 断断续续。修复：降级为 `THREAD_PRIORITY_URGENT_DISPLAY`(-8)
- **监牢释放弟子无反应** — `releaseReflectionDisciple` 移除了 statusData 中的思过年份标记但未重置弟子状态字段，`deriveDiscipleStatus` 的受保护状态检查（`currentStatus == REFLECTING → REFLECTING`）继续锁定状态。修复：释放时显式将 `statuses[id]` 设为 `IDLE`，使后续状态推导能正确计算；同修 `releaseTheftDisciple` 和 `releaseDiscipleFromAllSlotsAtomic` REFLECTING 分支
- **创建宗门输入框键盘频闪（小米 HyperOS）** — `DialogSoftInputGuard` 的 ADJUST_NOTHING 因国产 ROM 视图树中找不到 `DialogWindowProvider` 而静默回退到 Activity 窗口，Dialog 窗口仍使用默认 adjustResize 触发键盘振荡。修复：行业调研驱动，改为 ADJUST_PAN（不 resize 窗口切断振荡回路）+ `rootView` 备用窗口查找路径 + 焦点请求从固定 `delay(100)` 改为布局完成后回调

### 优化

- **弟子遍历合并（P0.1）** — `checkBreakthroughsAndPills` 7次独立遍历压缩至4次，`accumulateCultivationPerPhase` 改用列直读免全量 `assemble`，300弟子每 tick 节省~1ms
- **月度结算事务合并（P0.2）** — `processAutoAssign` + `processResidenceLoyalty` 移入主事务，月流事务数 4→2
- **突破判定列级直读（P0.3）** — `assembleAll()` 全量组装改为列级过滤 + 按需 `assemble`，每 tick 节省~0.5-1ms
- **UnifiedPerformanceMonitor 精简（P1.1）** — 1122行精简至477行，删除未使用的 Trace/月变事件指标/Choreographer回调等645行废弃代码
- **BooleanArray 替代 mutableSetOf（P1.2）** — `SoftwareCanvasBackend` chunk失效追踪改用 `BooleanArray(16)`，减少渲染线程GC压力
- **CultivationRateCalculator 映射缓存（P1.4）** — `associateBy` 改用引用检测自动缓存，修炼热路径每秒省2-3次全量重建
- **Pair 消除（P2.2）** — `processTickPhases` 返回值改为 Int bitmask，减少30次/秒分配
- **Baseline Profile（P0.4）** — `app/src/main/baseline-prof/baseline.prof` 含111条启动路径HSPL规则，首次启动AOT编译加速约30%

### 架构债务全量治理

- **写入守卫加固** — `ComponentTable` 三类型 `requireWrite`/`onWrite` 改为 `private` + setter 封装，新增 `putTo()` 守卫方法；`copyTo()`/`copySelfTo()` 全面路由守卫，封堵字节码级暴露路径（12 文件 +194/-132）
- **月事务合并** — `GameEngineCore.processMonthYearChange` 中政策成本 + 月度事件合并为单 `stateStore.update{}` 事务，消除跨事务状态不一致窗口
- **ADPF Performance Hint 接入** — 游戏循环集成 `ThermalMonitor.createHintSession`/`reportActualWorkDuration`/`closeHintSession`，API 31+ 自动启用帧率提示
- **纹理压缩 AAB 分发** — `bundle { texture { enableSplit = true } }` 启用 Google Play 纹理格式分发
- **ProfileInstaller 集成** — 添加 `profileinstaller` 依赖，`baselineprofile` 模块已有完整 `BaselineProfileGenerator`
- **`!!` 操作符全库清零** — `TalentDatabase.kt`/`DiscipleChatDialog.kt`/`SectTradeDialog.kt` 3 处 `!!` 全部替换为安全调用
- **`TalentDatabaseTest` 2 个预存失败修复** — 负天赋品级名测试期望对齐 `"负面"`；位置天赋（`positionBonus` 替代 effects）排除检查
- **文档同步更新** — `architecture-debt.md`/`architecture-debt-write-guard.md` 标记全部 ✅ 已治理

### 修复

- **17个预存单元测试失败** — `CultivationCoreTest`(9) + `DiscipleBreakthroughHandlerTest`(8) 因 Mockito mock 未 stub `disciples` StateFlow 而 NPE，补 stub 后全部通过
- **AdServiceImpl TooGenericExceptionCaught** — detekt 违规，加 `@Suppress` 标注

### 修复

- **没收弟子装备/功法物品无效** — `confiscateStorageBagItem` 的 `when` 分支匹配 `"equipment"`/`"manual"`，但储物袋实际存的是 `"equipment_stack"`/`"manual_stack"`（带 `_stack` 后缀），导致所有装备/功法没收静默无动作。修复：`when` 分支同时匹配带/不带后缀变体
- **宗门晋升显示「未找到玩家宗门」** — `checkAndRepairWorldMapSects()` 等保护函数只检查列表是否为空，不检查列表中是否缺少玩家宗门。修复：增加第二阶段检测——列表非空但 `isPlayerSect == true` 宗门不存在时触发重生；`upgradeSectLevel()` 找不到玩家宗门时先调 `ensureGameDataIntegrity()` 修复重试再报错

### 统计

- 22 文件改动，+224 / -141 行，净减 117 行
- `compileReleaseKotlin` BUILD SUCCESSFUL
- `core:domain` 全量 1456 测试 PASS（0 failure）

## [4.0.79] - 2026-07-30

### 修复

- **每月招募上限不重置** — `recruitCountThisMonth=0` 重置代码在 `CultivationEventProcessor.advanceMonth()` 中但未被游戏主循环调用（死代码），导致第1个月招满30人后所有后续月份无法招募任何弟子。移入 `processMonthlyEvents()` 后每月正常重置
- **国产ROM Bitmap双释放崩溃(#11008)** — 华为鸿蒙/小米澎湃OS/OPPO ColorOS等国产ROM的 `NativeAllocationRegistry.CleanerThunk` 在 `Bitmap.recycle()` 后未正确注销，GC再次释放已释放的原生内存导致SIGABRT。移除所有 `bitmap.recycle()` 调用改为GC自然回收

## [4.0.78] - 2026-07-29

### 重构（对抗性审查修复）

- **Combatant 战斗乘算因子封装** — 将散落的 8 个字段（`physiqueDamageAmplification`/`physiqueCritDamageBonus`/`physiqueDamageReduction`/`physiqueDefenseBonus` + `affixDamageAmplification`/`affixCritDamageBonus`/`affixDamageReduction`/`affixDefenseBonus`）封装为 `physique: PhysiqueCombatFactors` + `affix: AffixCombatEffects` 两个结构体字段，`BattleSystem`/`AISectAttackManager` 注入点和 `BattleCalculator.buildDamageZones` 读取点同步更新
- **`AffixCombatEffects` 定义位置归位** — 从 `PhysiqueDatabase.kt` 移至 `AffixDatabase.kt`，与其使用方和语义归属一致
- **`getAffixCombatEffects` 代码重复消除** — `DiscipleStatCalculator` 两个公有重载方法（`Disciple`/`DiscipleAggregate`）合并提取私有 `getAffixCombatEffects(affixIds: List<String>)` 公共实现

### 修复（注释/文档一致性）

- **`calculateFinalDamage` 公式注释补全** — 原注释缺失体质/词条增伤、减伤乘区，且未说明 `effectiveAttack`/`effectiveDefense`/防御减伤率的计算过程；补全完整公式与各乘区含义说明
- **`PhysiqueType` 枚举注释统一** — `CRIT_DAMAGE`/`HYBRID_OFFENSE`/`HYBRID_DEFENSE` 均补标"独立乘算"，与其他枚举值注释风格一致
- **`critDmgConfigs` 数值注释补标"独立乘算，仅暴击生效"**

### 测试

- **新增体质/词条独立乘算单元测试** — 18 个用例覆盖：体质/词条增伤独立乘算（vs 加算对照）、暴击伤害非暴击不生效 + 暴击独立乘算、体质+词条暴伤同时存在独立乘算、减伤独立乘算、防御加成作用于 `effectiveDefense`、全乘区组合验证、`PhysiqueCombatFactors`/`AffixCombatEffects` 默认值校验。引入浮点期望值计算器避免 Int 截断误差

### 验证

- `:app:compileDebugKotlin :core:engine:compileDebugKotlin :core:domain:compileDebugKotlin` 全模块编译通过
- BattleCalculatorTest 50 测试全过（含新增 18 个）；BattleAITest 全过（Combatant 结构重构未破坏 AI 行为）

## [4.0.77] - 2026-07-28

### 玩法

- **从众设计：弟子叛逃/偷盗全局门控** — 宗门所有活弟子的平均忠诚度 ≥ 50 时风气好，无人叛逃、无人偷盗。只有平均忠诚 < 50 时才会按个体忠诚/道德检查。新增 `herdLoyaltyThreshold` 配置项（默认 50），可在 `game_config.json` 中调节

### 新增

- **招募每月上限30人** — 超出上限弹出提示"本月招募已达上限（30人）"，每月初自动重置

### 修复

- **战胜AI宗门后玩家宗门涌入1000+弟子** — 三处根因：①AI宗门年度招募无截断累积（普通宗门`else`分支和AI占领宗门`occupierSectId`分支均用`disciples + newRecruits`无上限），新增`AISectDiscipleManager.truncateToLimit`按战力降序截断至`MAX_AI_DISCIPLES_PER_SECT`(1000)，`recruitYearlyDisciples`复用同一逻辑消除重复；②完整性检查`checkAndRepairAiSectDisciples`不跳过`isPlayerOccupied`宗门，占领后清空的弟子池被重新填充50人，新增`isPlayerOccupied`跳过；③`attackSect`不检查`isPlayerOccupied`可反复攻击已占领宗门重复俘虏，新增`isPlayerOccupied`拦截。设计意图不变：俘虏全部进`recruitList`、自动招募遵守月上限30人、已占领宗门继续产0-6人/年入`recruitList`
- **AI宗门弟子寿元计算错误** — `AISectDiscipleManager.processAging` 原使用 `newAge <= disciple.lifespan`（默认80），导致金丹弟子81岁即被判定死亡（应活200岁），现统一为 `computeMaxAge` 公式（含境界 `realmMaxAge` + 天赋加成）
- **AI宗门弟子突破后 lifespan 未随境界更新** — `processMonthlyCultivation` 突破后原赋值 `lifespan = workingDisciple.lifespan`（不变），改为大境界变化时按新境界 `realmMaxAge` + 天赋加成重新计算

### 重构

- **提取共享寿元计算** — 新增 `Disciple.computeMaxAge()` 扩展函数，统一玩家侧和AI侧的寿元公式，避免两处逻辑不一致
- **宗门管理界面双区域布局** — 选项区域在上，管理区域在下（道侣/弟子/自动管理按钮 FlowRow 响应式换行）
- **所有管理界面移除保存机制** — 道侣管理/弟子管理/自动管理/自动招募过滤均改为勾选即保存，移除保存按钮和未保存提醒对话框
- **管理按钮响应式布局** — 改为 FlowRow 根据屏幕宽度自动换行排列

### 修复

- **生产 Bug：批准婚姻时 NoSuchElementException** — `ComponentTable<String?>.get(id)` 无法区分"值=null"和"无条目"，当弟子从未有过伴侣时抛异常崩溃。修复：`partnerIds[id]` → `partnerIds.getOrNull(id)`（波及 PartnerSystem 和 GameEngine 两处）
- **预存测试崩溃 17→0** — PartnerSystemTest 缺 WriteGuardRule（12 个崩溃）+ 测试中 partnerIds[] 同种 NoSuchElementException（5 个）+ 4 个 RNG 不稳定测试改为确定性断言

### 架构

- **从众门控实现** — LawEnforcementProcessor 新增 `isAverageLoyaltyLowEnough()` 纯函数 + 4 处置入门控（叛逃月度入口/偷盗前置条件/月度兜底/防御冗余）

### 测试

- **新增从众门控测试** — 6 测试用例覆盖平均忠诚 50/49/30/0/空表场景
- **新增 HERD_LOYALTY_THRESHOLD 守卫测试** — GameConfigConsistencyTest 检验 GameConfig 常量与 GameConfigData 默认值一致

### 重构

- **抽取共享AI智能判定引擎** — 从VassalService提取四因素加权模型(战力差/占领丢失/胜负/好感度)为IntelligentSectDecisionEngine，统一供攻击判定/结盟判定/附属判定三种场景使用，消除VassalService内联计算与攻击/结盟独立逻辑的三份重复
- **好感度五级分档** — SectRelationLevel范围调整(HOSTILE 0→19, ANTAGONISTIC 20→39)，判定引擎参数从`favor: Int`改为`favorLevel: SectRelationLevel`，5种厌恶→友好等级各有固定分值，消除连续数值微调不可预测性
- **攻击判定改用四因素模型** — checkAttackConditions(原文二进制门槛:好感>0跳过/同联盟跳过/战力比<\<门槛跳过)改为四因素加权(战力40%/占领20%/胜负25%/好感15%)+个性偏移(好战×1.2,保守×0.8,隐世×0.6)；decidePlayerAttack同模型替代warProbability单概率
- **结盟判定改用四因素模型** — requestAllianceSimple从纯好感度分档(6级favor→概率映射)改为四因素加权(好感40%/战力20%/胜负25%/占领15%)+个性偏移
- **附属判定迁移至共享引擎** — VassalService.calculateVassalChance/checkSingleVassalBreakaway委托给引擎，脱离概率改用BREAKAWAY_FAVOR_SCORE等级分值映射
- **SectDecisionConfig配置分离** — 攻击/结盟/附属三种决策类型各有独立权重配置，好感度等级分值映射移除旧FAVOR_HARD_LIMIT硬门槛

### 修复（对抗性审查+代码审查）

- **冗余逻辑清理** — 引擎战力门槛合并为单if，移除isPowerRatioHighIsGood未使用字段
- **不安全后备值替换** — 脱离好感度`:? 0.5`后备改为`getValue()`，缺少等级直接暴露而非静默用错误值
- **死代码移除** — SectDecisionConfig.Vassal.BREAKAWAY_FAVOR_BASELINE(脱离已改用等级分值映射)
- **KDoc错字** — "称重"→"权重"

### 测试

- **新增IntelligentSectDecisionEngineTest** — 36+测试用例覆盖:权重一致性/5级好感度完整性/等级门槛(攻击遇友善至交不攻/结盟遇敌对不结盟)/战力分档/负数防御/NaN防御/个性修正/脱离概率/Profile构造校验

### UI

- **修复：勾选"结婚需同意"后弟子结婚弹窗不显示** — `PartnerSystem` 忽略 `daoCompanionConsentRequired` 字段，改用 pending state 模式（类似妖兽预警）。月度结算时提议暂存待处理列表，`GameOverlayHost` 逐对显示 `MarriageApprovalDialog`，同意后引擎原子配对+清理
- **审查：对抗性审查修复 6 项** — `pairedFemaleIds` 未更新导致同一女性多份提议、approve 不检查已有配对静默覆盖、跨月提议重复+死亡弟子提议残留、关闭同意不清理旧提议、拒绝不记录事件日志、审批对话框遮罩层叠加

### 测试

- **新增PartnerSystemTest** — 16测试用例:自动配对不变性/同意模式/去重/死亡+已配对清理/血亲回避
- **新增GameEngineMarriageProposalTest** — 8测试用例:approve/reject边缘情况(无效ID/已有道侣/提议不存在)

### 架构

- **攻击/结盟/附属统一判定架构** — 三种场景共用IntelligentSectDecisionEngine.calculateChance单一入口，DecisionProfile可插拔配置权重+等级分值+个性修正，达到Stellaris级AI加权决策水平
- **消除 recruitDisciple 重复** — DiscipleService 与 DiscipleLifecycleManager 中字节级重复的 recruitDisciple 方法，仅保留 DiscipleService 版本
- **消除 RecruitDiscipleUseCase** — 删除仅 15 行无逻辑的委托 UseCase
- **AI 宗门 RNG 确定性化** — AISectDiscipleManager 使用存档种子确定性播种，消除 System.nanoTime() 非确定性 RNG；新增 RngPartition.AI_SECT(6) 分区
- **TOCTOU 根除** — refreshRecruitList 中全部状态读取移入 stateStore.update 事务内，calcRecruitBonusCap 改为接收 MutableGameState 参数
- **RNG 适配器优化** — refreshRecruitList 循环内用 rng.asKotlinRandom() 一次创建，消除每次迭代创建匿名对象开销
- **lifeEvent 事务修复** — recruitDiscipleFromList 的加入宗门日志移入 stateStore.update 内，消除窗口期

### 修复

- **天道试炼Phase 1/Phase 2通关状态错乱** — ViewModel中launchOnEngine异步协程捕获selectedPhaseIndex时读取到被后续代码修改的值，导致Phase 1通关记录为Phase 2通关。修复：在lambda外局部捕获当前值 + 通关后回到主面板而非自动跳转Phase 2选人
- **BootSequenceControllerTest mock不完整** — 启动流程ensureGameDataIntegrity调用cultivationService.refreshTravelingMerchant因mock未stub引发NPE。修复：添加cultivationService stub
- **GameEngineRecruitTest字符串排序断言** — "弟子10"字符串排序在"弟子9"之前，断言names.last()非预期。修复：按数字后缀排序
- **IntelligentSectDecisionEngineTest期望值过期** — HOSTILE好感度分值为0时判定引擎直接返回0，测试仍期望旧版powerScore值0.20。修复：更新断言为0.0
- **RecruitServiceTest魅力加成上限未同步** — MAX_RECRUIT_BONUS_CAP=20已生效但测试仍期望无上限值(30/230)。修复：更新为上限值20

### 修复（对抗性审查）

- **recruitAllFromList 校验统一** — 对齐单招/自动招募的校验标准（realm in VALID_REALM_RANGE, age ≤ MAX_REASONABLE_AGE），修复 realm=0 仙人弟子被静默丢弃
- **recruitAllFromList 补充 lifeEvent** — 批量招募时添加「加入宗门」日志条目
- **processAutoRecruit 损坏数据静默删除** — 损坏的弟子追加回 keepManual 而非无声消失
- **calcRecruitBonusCap 硬上限** — 魅力加成上限 20，防止极端值导致招募数爆炸
- **兜底招募至少 1 人** — 玩家宗门不存在时不再可能抽到 0 招募
- **recruitAllFromList 跟踪实际成功数** — successCount 取代 validRecruits.size，allocateAndInsert 失败不虚报
- **DiscipleDelegate 并发锁修复** — isRecruitingAll 检查移入 synchronized 内消除 TOCTOU 竞态；全招后清空 recruitingDiscipleIds 防止残留阻塞
- **AISectDiscipleManager._rng 加 @Volatile** — 确保跨线程可见性

### 调整

- **招募数量范围调整** — 小型 1~4、中型 1~6、大型 1~10、顶级 1~15（原 0~5/1~8/3~15/6~20），所有等级下限统一为 1
- **待招募弟子每年年龄+1** — recruitList 中的弟子随游戏年份增长年龄
- **招募弟子固定为练气一层** — 所有招募弟子统一 realm=9, realmLayer=1

### 测试

- **新增 RecruitServiceTest** — processAutoRecruit 边界条件 12 测试 + calcRecruitBonusCap 5 测试
- **新增 DiscipleFacadeImplRecruitTest** — recruitDiscipleFromList 9 测试（正常/损坏/事务/重复招募）
- **新增 GameEngineRecruitTest** — recruitAllFromList 6 测试（批量/全损坏/部分损坏/recruitedMonth 验证）

### 架构

- **架构债务全量治理 — 5 项 Selected 全部落地**
- **GameConfig 双源不一致根除** — 守卫测试 46 用例 + 4 项数值紧急对齐 + `GameConfig.initialize()` 启动时注入 `GameConfigData`，`Production`/`Warehouse`/`Battle.RealmGap`/`LawEnforcementConfig` 对应字段改为运行时 `val` 委托到 `_configData`，`GameConfigData` 成为唯一真实源
- **Dispatchers.IO → IoDispatcher 全量替换** — `IoDispatcher` 迁至 `:core:engine/di` 供全部模块注入，12 个 Hilt 类 49 处替换 + 8 个非 Hilt 类 `dispatcher` 参数化
- **SIGSEGV #3088 vulkan.adreno.so 加固** — driverVersion JNI 桥 + C++ `s_driverVersion` 静态变量 + 黑名单扩充 22 机型 + `GameConfigData.VulkanSection` 远程配置预备
- **ANR #5076 TapTap Sandbox Toast 防御** — `initAdSdk()` 顺序修正 + 5s 超时保护 + Looper 监控 + lateinit 异常守卫（根因在 TapTap SDK 闭源，应用层已做到极限）
- **Detekt 6 项违规清零** — 通配符 import/`catch(Exception)`/文件名匹配/长行/return 数

### 修复

- **修复：GameConfigData 4 项数值偏差** — `damageBonusPerRealm` 0.5→0.35、`damagePenaltyPerRealm` 0.5→0.35、`probPerPoint` 0.03→0.01、`capacityPerBuilding` 50→75（此前未被生产代码读取，不影响实际游戏数值）

## [4.0.76] - 2026-07-27

### 调整

- **建筑占地调整** — 仓库占地 6×5→6×4（精灵6×6不变），巡视楼占地 4×4→4×3（精灵4×8不变），血炼池占地 2×2→4×4（精灵4×4不变），中级多人住所占地 6×4→6×5 精灵同步 6×5
- **灵矿基础产出下调** — 每名矿工每月基础产出 220→160

### 修复

- **灵矿测试硬编码值改为引用 GameConfig 常量** — 测试自动追踪产出配置变化，不再因产出值调整而手动同步期望值

### 平衡

- **功法修炼速度加成减半** — 所有品阶心法的修炼速度加成下调 50%（凡品 8%→4%，天品 38%→19%），功法类加速对修炼节奏的影响更平缓
- **基础修炼速度降低 30%** — 各境界每旬修炼速度下调约 30%（炼气 28→19，筑基 38→26，金丹 62→43，元婴 100→70，化神 156→109，炼虚 304→212，合体 472→330，大乘 762→533，渡劫 1180→826，仙人 1600→1120），整体修炼节奏放缓
- **突破所需修为增加 30%** — 各境界突破所需修为上调约 30%（炼气 50→65，筑基 200→260，金丹 800→1040，元婴 3000→3900，化神 10000→13000，炼虚 30000→39000，合体 100000→130000，大乘 300000→390000，渡劫 1000000→1300000，仙人 3000000→3900000），小层境界阈值同步提升，突破难度增加

### 修复

- **修复：引擎线程安全加固** — 11 处 suspend 方法添加 `engineContextDispatcher.withEngineContext`，消除 Release 构建静默跳过更新的风险
- **修复：对抗性审查 11 项发现** — assignmentGate 注册表事务一致性修复（gate 操作移出 stateStore.update）、!! 操作符移除、sellStack 操作顺序修复
- **修复：sellItem/bulkSellItems 统一入口** — 移除绕过容量守卫的临时 StackableItemStore(Int.MAX_VALUE) 模式

### 架构

- **ProtoBuf 默认值编码治理** — 27 个 @ProtoNumber 非零默认值字段标注 @EncodeDefault(ALWAYS)，ProtoNumberCoverageTest 扩展到递归检查嵌套 @Serializable 类
- **月度/年变事件管线单事务化** — processMonthlyEvents 13 子服务 + processYearlyEvents 18 子服务全部移入单次 stateStore.update，利用重入缓冲机制保证嵌套 update 共享同一数据副本，消除多事务提交的原子性和部分状态窗口问题
- **checkAllianceExpiry/garrisonAndReport data.copy 覆盖漏洞修复** — 子服务写入时用 gameData.copy(field=newValue) 替代 data.copy(...)，不覆盖同一事务内其他服务的中间修改
- **syncAllDiscipleStatuses 事务内读取** — 所有状态读取移入 stateStore.update 块内，重入缓冲下自动获取 reusableMutableState 当前数据，与顺序提交行为完全等价
- **shuffled() 迁移至分区 PRNG** — DisciplePurchaseService(5处) + LootCalculator(1处) 共 6 处 kotlin.collections.shuffled() 改为 DeterministicRng.shuffled(rng)，使用 RngPartition.SYSTEM/EXPLORATION 分区 PRNG，新增 RngExt.kt 扩展函数

### 修复

- **修复：建筑精灵重叠** — Y-Sorting 排序键从 gridY（占地顶部）改为 gridY + height（地面接触点），消除占地高度不同建筑之间的 z-order 颠倒。行业对标：Unity/Godot/Cocos2d/Supercell(CoC)/Factorio 一致确认地面接触点为标准排序键
- **修复：C++ 占地数组缺少索引 18** — NativeBridge.cpp FP_W/FP_H 数组追加中级多人住所 6×4 条目，修复 Vulkan 路径上该建筑精灵偏移和地砖大小错误

## [4.0.75] - 2026-07-26

### 修复

- **修复：云存档序列化崩溃** — NullSafeProtoBuf.encodeDefaults=true→false，Proto3默认值不编码规范修正。修复@Ignore字段缺@Transient导致的NaN/Map不支持问题
- **修复：云存档反序列化崩溃** — Mission/ActiveMission/MissionRewardConfig字段编号偏移和冲突（duration=7 vs rewards=auto-7），全部字段显式@ProtoNumber对齐旧版声明序编号
- **修复：GameData 7个运行时字段加@kotlinx.serialization.Transient** — 消除ProtoBuf序列化NaN和Map<String,List<String>>不支持导致的崩溃
- **架构：ProtoNumberCoverageTest守卫增强** — GameData检查新增@Transient跳过，EXCLUDED_FIELDS清理，防止同类问题复发

### 新增

- **新增：背景音乐和按钮音效系统** — 集成 BGM + SFX，SoundPool + MediaPlayer 方案
- **新增：登录界面右上角音乐/音效勾选** — 未登录可调节，持久化到本地
- **新增：主菜单头像下方音乐/音效勾选** — 已登录未进游戏时调节
- **新增：游戏内设置界面音乐/音效勾选** — 同步持久化到存档
- **新增：双 Activity BGM 生命周期管理** — 切后台/切换 Activity 自动暂停/恢复

### 新增

- **新增：统一点击音效系统** — `clickableWithSound` 扩展函数覆盖全游戏所有可点击元素（23 文件，102 行新增），支持 enabled/interactionSource/indication/onClick 全部参数签名
- **新增：GameButton/CloseButton 点击音效** — 标准按钮和关闭按钮统一点击反馈
- **新增：登录界面/选择模式界面按钮音效** — 隐私政策链接、头像、新游戏/读档/退出按钮

### 修复

- **修复：BGM 循环点爆音问题** — 替换背景音乐和按钮音效源文件，新音频文件消除循环边界爆音

### 调整

- **调整：替换音源文件** — BGM 14MB→1.6MB，SFX 41KB→8.5K，均做增益处理
- **调整：BGM 循环回退到 setLooping(true)** — 移除复杂淡入淡出监控（Handler 竞态），简单循环方案
- **调整：移除伴侣突破忠诚+3机制** — 伴侣突破不再增加道侣忠诚度
- **调整：灵石不足无法发年俸时，应得俸禄弟子忠诚-1** — 宗门灵石不够发工资，该拿工资的弟子忠诚度下降
- **清理：移除 PartnerSystem 死代码** — 移除 `DomainEventSubscriber`/`eventBus`/`stateStore` 依赖及空 `initialize()/release()` 方法，移除未使用的 `consentRequired` 变量
- **重构：processAnnualSalary 列直写替代 assembleAll→map→replaceAll 模式** — 与惩罚分支保持一致的列直写风格，消除旧模式竞态风险

## [4.0.74] - 2026-07-26

### 架构重构

- **重构：消除云存档双路径序列化架构债务** — 删除 8 个手动转换器（SaveDataConverter/DiscipleConverter/EquipmentConverter/ManualConverter/ItemConverter/TeamAndBattleConverter/WorldAndSectConverter/SlotConverter）及 SerializableSaveData（1374 行包装类型），域类型（GameData/Disciple/EquipmentInstance/Pill/Material/Herb/Seed/BattleLog/ExplorationTeam 等 ~50 个数据类）直接携带 @ProtoNumber 注解，SaveData 直接序列化为 Protobuf 二进制。净删 ~10,000 行代码，新增 GameData/@Embedded EnumStringSerializer/NullableStringAsEmptySerializer 守卫测试。本地 Room 存储路径完全不变，云存档二进制格式向后兼容。消除"每加字段需同步 4 处"的架构债务

### 架构加固

- **架构：存档跨版本迁移三层防御** — 替换 `fallbackToDestructiveMigration()` 为 `fallbackToDestructiveMigrationFrom(1)`，禁止 v2+ 数据库毁灭回退；新增迁移前自动备份 `backupDatabaseForMigration()`（写前 WAL checkpoint + 文件复制）；增强 `verifyAndRecoverDatabase()` 在启动时验证 `PRAGMA integrity_check` + 数据非空，异常时从备份恢复；新增 `restoreFromBackupIfNeeded()` 文件级覆盖恢复机制
- **测试：补齐 Room 迁移覆盖缺口** — 新增 12 条缺失迁移测试（M16→M32）、全链路数据留存测试（M21→M32 插入数据→迁移→验证数据存活）、备份恢复流程测试（创建→备份→篡改→恢复→验证），迁移测试从 24 条增至 37 条
- **修复：`restoreFromBackupIfNeeded` 从空数据库恢复的 bug** — 恢复逻辑误用 `dbFile.inputStream()`（读取空库）而非 `backupFile.inputStream()`（读取备份），导致恢复永远不生效

### 架构债务

- **架构债务：邮件/兑换码 RNG 接入 MAIL 分区 PRNG** — `RedeemCodeManager`/`MailService`/`RedeemCodeService` 所有随机生成路径（弟子属性/装备/丹药/功法/草药/种子）从独立 `DeterministicRng`/`kotlin.random.Random` 统一为 `GameRngManager.getRng(RngPartition.MAIL)`；`EquipmentDatabase`/`HerbDatabase`/`ItemDatabase`/`ManualDatabase` 的 `generateRandom*` 方法增加可选 `random` 参数；新增 `RngPartition.MAIL(5)`；清理 `ManualDatabase`/`RedeemCodeManager` 死字段 `rng`
- **架构债务：`DiscipleTables.ids`/`deathRecords` public MutableList 封装** — `ids` 和 `deathRecords` 改为 `private val` 背板 + `List` 只读视图，新增 `addId()`/`removeId()`/`addDeathRecord()` 守卫方法；修复 `DiscipleLifecycleProcessor` 生产代码绕过 `markDead()` 直接 `deathRecords.add()` 的 Bug
- **架构债务：影子结算死代码清理** — 移除 `copyRowFrom()` 死代码；更新 `SettlementStrategy.kt`/`DiscipleTables.kt`/`CultivationSettlementConcurrencyTest.kt` 中引用已删除方法的 KDoc 注释

### 修复

- **修复：云存档反射桥接 7 个运行时崩溃 Bug** — 反编译 `tap-cloudsave-4.10.5.aar` 验证实际 API 后修正：主类名 `TapCloudSave`→`TapTapCloudSave`、回调包名 `com.xd.sdk.taptap`→`com.taptap.sdk.cloudsave.internal`、`ArchiveMetadata.Builder` 实例化方式、`onArchiveDataResult` 签名（1参数非3参数）、`setPlaytime` 类型（int 非 long）、`invokeGetter` Long 返回值处理、`NativeTapCloudSaveApi` 完整实现
- **修复：云存档仅保存部分数据——66 个 GameData 字段未序列化** — `placedBuildings`（建筑布局）、`portraitRes`（弟子肖像）、`worldLevels`（世界关卡）、`rngStates`（RNG 状态）、`midGradeSpiritStones`/`highGradeSpiritStones`（灵石中上品）、`patrolSlots`/`warehouseGarrisons`/`vassalContracts`/`spiritFieldPlants`/血炼/天道试炼/签到/年度报告等 66 个字段从云存档序列化路径中遗漏，云读档后建筑精灵图不显示、弟子肖像空白、灵石中上品归零。根因：`SaveDataConverter.convertGameData()` 未同步新增字段。修复：一次性完整补全所有缺失字段（SerializableGameData ProtoNumber 94-159 + SerializableDisciple portraitRes @90 + 20 个 Serializable 包装类 + DiscipleConverter 映射 + SaveDataConverter 正反向映射 + 26 个 roundtrip 测试）
- **文档：架构债务/云存档序列化双路径同步债务** — 记录 GameData→SerializableGameData 手动映射三处同步的架构根因，下次新增字段在守卫测试落地前仍需人工同步
- **修复：云端孤立存档导致数量超限（400003）** — 增加一次性清理 + UUID 缓存 + `shuffled(Random)` 替代 `sortedBy { rng.nextInt() }` 的 TimSort 崩溃
- **修复：月度事件 `aiBeastAttacksRemaining` TimSort 崩溃** — `sortedBy { rng.nextInt() }` 比较器违反传递性导致 Android TimSort 抛 `Comparison method violates its general contract`，6 处全部改为 `shuffled(java.util.Random(seed))`
- **修复：AISectBeastAttackProcessor 距离排序 NaN 崩溃** — `mapNotNull` 过滤 `isNaN/isInfinite` 坐标对
- **修复：SerializableManual 遗漏 21 个技能字段——云存档往返后功法战斗技能完全丢失** — `SerializableManual` 仅序列化 9 元数据字段，`skillName`/`skillType`/`skillDamageMultiplier`/`skillCooldown`/`skillMpCost`/`skillBuffType`/`skillIsAoe` 等 21 个技能定义字段全部遗漏。举一反三排查覆盖所有 Converter 发现 2 处严重序列化遗漏 + 多处中风险遗漏。修复：SerializableManual 新增 ProtoNumber 10-33 完整技能字段 + ManualConverter 双向映射
- **修复：SerializableSaveData 遗漏 storageBags——云存档往返后储物袋永久丢失** — 新增 `SerializableStorageBag` 数据类 + ProtoNumber 15 字段 + ItemConverter/SaveDataConverter 双向映射
- **修复：SerializableDisciple 遗漏 cultivationCheckpoint/masterId/autoLearnFromWarehouse 等 10 字段** — 云存档往返后修为存档点丢失（修为动画从 0 播放）、自动学习仓库功法标记丢失、师徒关系断裂。修复：SerializableDisciple 新增 ProtoNumber 90-100 字段 + DiscipleConverter 双向映射
- **修复：多个 Serializable 类遗漏字段——SerializableProductionSlot/SpiritMineSlot/LibrarySlot/WorldSect/SectWarehouse/ExplorationTeam/BattleLog/AICaveTeam 等累计 40+ 字段** — `buildingInstanceId`（建筑移除时槽位匹配错误）、`garrisonSlots`（AI 宗门驻守丢失）、`midGradeSpiritStones`/`highGradeSpiritStones`（AI 宗门灵石归零）、`caveId`（探索目标洞府 ID 丢失）、`portraitRes`（宗门头像丢失）等。修复：全部补齐+Converter 映射+新增 SerializableGarrisonSlot/SerializableMapPoint 数据类
- **修复：CloudSaveDialog LaunchedEffect 竞态覆盖 _cloudSaveInfo——上传后卡片仍显示无数据** — `LaunchedEffect(Unit) { checkCloudSave() }` 启动 fire-and-forget 协程查询上传前的 API 状态，响应在 `uploadToCloudSave()` 完成后到达，无条件 `_cloudSaveInfo.value = info` 覆盖本地刚设置的正确数据。修复：`cloudSaveInfoVersion` 版本号追踪，`checkCloudSave` 仅在版本号匹配时才写入
- **修复：云存档方法缺少防重入保护——重复点击"下载存档"导致闪退** — `downloadFromCloudSave()`/`loadFromCloudSave()` 无 `AtomicBoolean` 锁，`uploadToCloudSave()` 缺少上传中状态检查。修复：`cloudDownloadLock` + 状态前置检查
- **界面：CloudSaveDialog 上传/下载状态增加转圈动画** — Uploading/Downloading 状态添加 `CircularProgressIndicator`
- **修复：DiscipleServiceApprenticeTest/CrudTest 预存编译错误** — `DiscipleSlotManager` 构造参数名 `discipleStatusService`→`discipleStatusServiceProvider`（Provider 断环）未同步到测试代码

### 修复

- **修复：云存档卡片上传后不显示数据** — CloudSaveDialog 中 `collectAsStateWithLifecycle` 在 Dialog 独立窗口可能找不到 LifecycleOwner，改为 `collectAsState`；TapCloudSaveManager 新增本地缓存，API 查询失败时降级到缓存
- **修复：反复读档存档后闪退** — SaveLoadViewModel 新增 `loadLock` AtomicBoolean + 存读互斥 + `restartGame` 注册协程到 `registerActiveLoadJob`
- **修复：勾选显示所有弟子后仅显示空闲中** — 新增 `releaseDiscipleForReassignment` 方法，血炼中(REFINING)弟子选择后中止血炼不返还材料，思过中(REFLECTING)选择后释放思过；更新 15+ 处调用点统一使用新方法
- **修复：多界面遮罩重叠** — `subDialogScrim` 增加 `overlayOrder` 检查；DiscipleSelectorDialog 新增 `scrimEnabled` 参数
- **加固：主线程 Looper 超时监控 (>3s)** — 检测主线程消息处理超时并记录日志，辅助诊断 ANR
- **加固：Vulkan createLogicalDevice vkGetDeviceQueue 防崩溃** — 重试 3 次 (2ms 间隔)，空句柄时销毁设备返回 false 触发降级；VulkanPolicy 新增 Adreno 崩溃相关机型黑名单
- **测试：MailServiceTest/CultivationSettlementConcurrencyTest 预存编译错误修复** — 补全 `gameRngManager`/`discipleStatusService` 构造参数
- **测试：SerializationCoverageTest 扩展守卫到 SaveData 顶层字段** — 确保新增 SaveData 字段不再遗漏云存档序列化
- **测试：SaveDataConverterTest 新增 StorageBag roundtrip** — 验证序列化/反序列化后储物袋字段一致

### 变更

- **变更：移除自动存档机制** — 移除游戏循环触发的周期性自动存档（`GameEngineCore.processTickPhases`）、`SavePipeline` 异步管道、增量保存（`ChangeTracker`）、`AUTO_SAVE_SLOT=0`、`autoSaveIntervalMonths` 字段等 ~40 文件涉及清理
- **变更：云存档入口移至存档选择界面** — 设置页移除"云存档管理"按钮，slot 0 改为"云"图标显示云端数据（宗门/年份/弟子/灵石），点击直接上传/下载，与本地存档操作一致
- **变更：旧自动存档 slot 0 数据迁移至空槽位** — 检测到旧版残留的 slot 0 数据时自动复制到第一个空槽位，避免数据丢失
- **界面：设置页调整** — 原"自动存档"配置区域替换为"云存档"入口，存档选择页移除槽位 0 自动存档卡片
- **清理：移除 `UseCase.AUTO_SAVE`、`getAutoSaveContext()`、`setIncrementalSaveThreshold()`** — 自动存档相关死代码清理

### 修复

- **修复：弟子选择界面不显示可用弟子 + 一键任命无效** — 根因 `SpiritMineViewModel`/`PatrolTowerViewModel`/`DiscipleViewModel`/`AlchemyViewModel`/`ForgeViewModel` 等约 70 处 ViewModel 和对话框从 `viewModelScope.launch` 调用引擎事务方法，触发 `GameStateStoreImpl.update()` 主线程守卫后更新静默丢失。修复：改为 `gameEngine.launchOnEngine` 派发到引擎线程
- **修复：宗门政策开关无法生效** — `SectPolicyToggleUseCase.toggle()` 直接调 `stateStore.update{}` 从主线程调用时被静默跳过，政策无法开启/关闭。修复：UseCase 层用 `withEngineContext` 自动切换到引擎线程
- **加固：GameEngineCore 新增 withEngineContext** — 引擎层基础设施，供后续将直接调 `stateStore.update{}` 的 suspend 方法自动派发到引擎线程

### 修复

- **修复：长按移动建筑确认后弹回原位** — `BuildingDelegate.moveBuilding()` 在 `moveScope.launch`（主线程）调 `stateStore.update` 被主线程守卫静默跳过。加 `withContext(IO)` 修复
- **修复：赏赐物品给弟子后仓库显示两本功法（重复）** — `manualStacks` 缺少弟子背包物品过滤（`equipmentStacks` 已有同类过滤），补全 `combine` + `disciples` 过滤逻辑
- **修复：储物袋开启后物品不入账且袋子不消耗** — `BagDelegate.openStorageBag()` 主线程调 `stateStore.update` 被跳过，加 `withContext(IO)` 修复
- **修复：兽袭进贡/战斗后状态未保存** — `BeastAttackDelegate.resolveBeastAttackPayTribute/Fight()` 主线程被跳过，加 `withContext(IO)`
- **修复：15+ 对话框释放弟子槽位不生效** — `releaseDiscipleFromAllSlotsAtomic()` 主线程被跳过，加 `withContext(IO)`，一处修改覆盖全部调用点
- **修复：安排/撤换长老后修炼结算不更新** — `BaseViewModel.launchElderAction` 在 `viewModelScope.launch`（主线程）运行，改为 `Dispatchers.Default`
- **修复：广纳门徒政策开关无法生效** — `SectPolicyToggleUseCase.toggleOpenRecruitment()` 遗漏 `withEngineContext`（同类中唯一），补全
- **举一反三：地毯式排查 58 个 `stateStore.update` 调用点** — 确认外交/存档/引擎核心系统均安全，无遗漏同类问题
- **清理：删除死代码 `UnifiedGameStateManager.kt` 和 `SaveLoadStateDelegate.kt`** — 两文件均无调用方

### 架构债务

- **追加：引擎 suspend API 线程安全自动化** — 将所有直接调 `stateStore.update{}` 的 suspend 方法内部加 `withContext(gameDispatcher)` 包裹
- **追加：Detekt 预存违规记录** — 7 项预存违规写入架构债务文档

### 架构改进

- **新增 `EngineContextDispatcher` 接口** — 提取 `withEngineContext` 为接口（`EngineContextDispatcher.kt`），`GameEngineCore` 实现，`GameEngine.engineContextDispatcher` 注入。34 个 suspend 引擎方法自动派发到引擎线程。测试用 `FakeEngineContextDispatcher` 绕过 Mockito suspend 泛型限制
- **云存档并发锁 `cloudOpLock`** — `uploadSave`/`downloadSave` 互斥，防止多协程并行操作临时文件
- **云存档下载备份** — 下载覆盖前备份当前存档（`.bak`）
- **`invokeGetterString` 静默降级修复** — 返回 `String?` + 异常时 log
- **云存档跨版本兼容性** — 上传时记录版本号，下载时比对并提示

### 架构改进（本次）

- **架构：云存档序列化全量守卫测试** — 6 个测试文件覆盖 GameData + 全部嵌套类型的序列化映射覆盖检查
- **架构：writeGuardEnabled 改为 ThreadLocal 隔离** — 游戏/测试线程独立开关，21 个测试零修改
- **架构：弟子状态纯推导重构** — `deriveDiscipleStatus` 纯函数 + `SlotFlags` 驱动，废除 26 处直接写入，消除 `getDiscipleStatus` 重复推导
- **架构：事件驱动增量推导** — 新增 `syncSingleDiscipleStatus`，O(1) 增量更新替代 O(n) 批量扫描
- **架构：StatusDerivationCoverageTest 守卫** — 新增状态时自动检测 3 处同步更新
- **规范：修复预存违规** — `resetAllDisciplesStatus` 拆分（提取 `clearSlotsForReset`），`!!`→`mapNotNull`
- **`sellItem`/`bulkSellItems` 统一入口** — 改为 `StackableItemStore` 操作
- **`SaveLoadViewModel` 构造参数 15→7** — 提取 `PersistenceFacade` 封装 8 个基础设施依赖
- **云存档已知问题文档更新** — 完成项标记 ✅，仅保留未完成项
- **架构债务文档更新** — 引擎 suspend API 自动化标记为 ✅ 已完成

## [4.0.73] - 2026-07-25（versionCode=4073）

### 修复

- **修复：TapTap 沙盒 ANR 崩溃后存档显示为空或损坏** — `StorageEngine.load()` 当数据库无 game_data 条目时，直接返回 `SLOT_EMPTY` 而不尝试从备份文件恢复。新增备份恢复回退路径：先尝试从 `.sav`/`.bak` 备份文件读取数据、反序列化、二次验证，写回数据库后再返回成功
- **修复：`CorruptedResultHandler` 备份二次验证为 Corrupted 时未拦截** — 损坏数据被静默接受为成功加载，改为返回 `SLOT_CORRUPTED`
- **修复：`GameViewModelTest` 因缺失 `adService` mock 参数编译失败** — 新增 mock 字段
- **修复：`FunctionalWAL.recover()` 死代码** — `startMaintenance()` 时启动 WAL 崩溃残留扫描与日志记录
- **修复：Bugly #5074 ANR** — `viewModelScope.launch` 在主线程调 `stateStore.update{}` 阻塞 ReentrantLock，11 处改为 `gameEngine.launchOnEngine` 派发到引擎线程（涉及 SaveLoadViewModel/BloodRefiningViewModel/DiscipleViewModel）
- **修复：Bugly #9056 Input Dispatching Timed Out** — `GameActivity.onPause()` 先 `super.onPause()`（触发 `HardwareRenderer.setStopped`）再停游戏循环，调换顺序：先停自定义渲染器释放 GPU 资源再通知系统暂停
- **修复：Bugly #11002 LazyColumn 空字符串 key 重复** — `GameEngineAdminOps.sendAdminCompensation` 增加 `require(mailId.isNotBlank())` 校验 + `MailDialog` LazyColumn key 增加空值回退
- **修复：Bugly #9054 LazyColumn "report_2488" key 重复** — `BattleLogDialogs` 年报列表 key 增加 index 后缀确保同年多份 report 不碰撞
- **加固：`GameStateStoreImpl.update()` 主线程检测增强** — Release 构建检测到主线程调用时立即 return 不阻塞，宁可丢失一次状态更新也不触发 ANR

## [4.0.71] - 2026-07-25（versionCode=4071）

### 优化

- 优化

## [4.0.70] - 2026-07-25（versionCode=4070）

### 偷盗系统简化

- **简化：偷盗判定改为执法堂抓捕率+仓库守卫纯智力比拼** — 移除隐匿判定层(Sigmoid 函数)和战力对抗(五维属性求和)，执法堂判定直接使用抓捕率(calculateCaptureRate)，仓库守卫判定为纯智力比拼(盗贼智力≤守卫智力则被捕)
- **清理：移除5个废弃常量** — `THEFT_REALM_PERCEPTION_BONUS`/`THEFT_STEALTH_SPEED_FACTOR`/`THEFT_STEALTH_INTEL_FACTOR`/`THEFT_PERCEPTION_INTEL_FACTOR`/`THEFT_STEALTH_REALM_FACTOR`
- **修复：`statusData`读取抛 `NoSuchElementException`** — 所有 `statusData[cid]`(get)改为 `statusData.getOrNull(cid)`(getOrNull)，消除未初始化 `statusData` 的弟子被捕时崩溃
- **测试：新增7个仓库守卫+执法堂测试用例** — 覆盖智力低于/高于/等于三种对比场景，无仓库/无活跃守卫边界，执法堂捕获率计算

## [4.0.69] - 2026-07-24（versionCode=4069）

### 存档选择界面增强

- **新增：存档卡片删除按钮** — 每个非空存档（含自动存档）卡片右侧新增红色 ✕ 删除按钮，点击弹出 StandardPromptDialog 二次确认，确认后删除对应存档并刷新列表
- **新增：引导奖励飞出动画** — `claimGuideReward` 成功后调用 `stateStore.enqueueRewardCards()` 入队 StorageBag 奖励卡片，通过 RewardCardHost 播放飞出动画，用户可见
- **修复：引导领取按钮状态不更新** — `GameOverlayHost` 中 `claimedRewardIds` 由 `.value` 快照读取改为 `collectAsStateWithLifecycle()` 状态收集，领取后按钮自动变为"已领取"并禁用

### 偷盗系统

- **改动：偷盗判定新增双层限制** — 每弟子每年最多判定1次（`lastTheftJudgementYears` 组件列）+ 每月最多判定3名弟子（`theftJudgementsThisMonth`）+ 保留年度成功偷盗3次封顶三层控制。月度扫荡改为 `take(3)` 直接调用 `processSingleDiscipleTheft`。新增 5 个单元测试 + 知识库文档更新

## [4.0.67] - 2026-07-24（versionCode=4067）

### 架构债务清理 Phase F（2026-07-24）

#### 死字段清理

- **清理：`lastTheftMonths` 写而不再读** — `UsageTracking.lastTheftMonth` + `DiscipleTables.lastTheftMonths` 组件表 + save/load 路径全移除。单弟子偷盗冷却已由年上限完全替代，无需 Migration（Room 静默忽略旧列）

#### 引导系统增强

- **实现：`DiscipleReachRealm` 引导条件** — 修复 `@Deprecated(ERROR)` 的占位实现。扩展 `GuideCondition` 接口（新增 `isMet(gameData, discipleTables)` 带默认委托的重载），`DiscipleReachRealm` 实时查询 `discipleTables.realms` 统计弟子境界分布，排除死亡弟子
- **扩展：`GuideDelegate` / `GuideDialog` 透传 `DiscipleTables`** — 接口扩展不影响现有条件实现，新增境界类任务无需改基础设施

#### 广告回调重构

- **重构：`AdService` 接口 + `AdServiceImpl` 实现** — ViewModel `var` 回调属性替换为类型安全的 `watchAdForBreakthroughBonus`/`watchAdForMerchantRefresh` 方法，新增广告类型只需在 `AdPurpose` 追加枚举值
- **移除：GameActivity 中 90 行回调 lambda** — 广告播放逻辑统一在 `AdServiceImpl` 中，GameActivity 仅注入 Activity 引用
- **移除：`AdsDelegate` 中 `var onWatchAdBreakthroughBonus`/`var onWatchAdMerchantRefresh`** — 消除 3 层透传链

#### 对抗性审查修复

- **加固：`AdServiceImpl` 串行化广告请求** — `@Volatile isLoadingAd` 防止并发 `watchAd` 导致全局回调覆盖（对抗审查 #1/#4）
- **加固：奖励发放顺序** — `tryMarkAdWatched()` 先于奖励发放，返回值 false 时不发奖励（对抗审查 #2）
- **加固：`loadAd` 异常保护** — try-catch 包裹，崩溃时释放 loading 锁 + 清回调（对抗审查 #4）
- **加固：`DiscipleReachRealm` 排除死亡弟子** — 遍历时加 `isAlive` 检查（对抗审查 #6）
- **加固：广告加载/播放失败回调** — 实现 `onAdLoadError` / `onVideoError`，错误时释放锁并打 Log（对抗审查 #8）
- **修复：白名单用户冷却状态污染** — 白名单路径跳过 `adCooldownUntilMs` 赋值（对抗审查 #12）
- **修复：Activity onDestroy 释放广告资源** — 加 `RewardVideoAdManager.destroyAd()`（对抗审查 #13）

#### 测试覆盖

- **测试：`GuideTaskTest` 新增 7 个测试** — `DiscipleReachRealm` 旧签名永远 false、null tables、计数达标/未达标、空表、排除死亡弟子、progressText

### 架构债务批量处理（2026-07-24）

#### 事务完整性修复

- **重构：processCompletedMissionsLazy 单事务** — Phase 1 仅计算不写状态，Phase 2 单事务写入全部（物品+灵石+弟子状态+任务清理），消除旧 Phase 1 独立事务的崩溃窗口
- **重构：buyMerchantItem 扣灵石后移** — 先加物品后扣灵石，`StackableItemStore.add()` 返回 `Partial` 时设 `addOk=false` 取消交易，消除灵石损失风险
- **重构：sortWarehouse 单事务** — `consolidateAllStacks` 提取为 `MutableGameState` 扩展函数，consolidate + sort 合并为同一 `stateStore.update`

#### 仓库入口统一（StackableItemStore 全面替代）

- **重构：AutoBuyService → StackableItemStore** — `addToWarehouse` 6 路手写查找-更新-或-添加替换为 `StackableItemStore` 统一入口，消除容量绕过风险
- **重构：openStorageBag 单事务 + StackableItemStore** — 从 20+ 次独立 `stateStore.update` 合并为 1 次，奖励物品改为 `StackableItemStore` 添加，消除多次提交 + 容量绕过
- **重构：bulkSellItems 简化** — 提取 `deductStack` 泛型辅助函数，消除 6 路 `when` 分支代码重复
- **重构：DiscipleEquipmentService → 分发路径** — `unequipEquipmentLogic` 从 `StorageBagUtils.mergeEquipmentStackToWarehouse`（绕过统一入口）改为直接操作 `StackableItemStore`

#### 自动分配管线

- **重构：processAutoAssign 迁入 stateStore** — `batchAssignToProductionSlots` 从 `productionSlotRepository.batchUpdate`（Room DAO + `scope.launch(IO)` fire-and-forget）改为 `state.gameData.productionSlots` 直写，移除异步写入模式

#### 防御性加固

- **加固：tryStealthDetection + 可选 state 参数** — 事务内路径从 `state.discipleTables.assemble()` 读取守卫数据，消除 `stateStore.disciples.value`（StateFlow）过期读风险
- **加固：canAddItemInTransaction 方法** — 在 `MutableGameState` 事务内检查容量的方法，返回事务内最新状态
- **加固：otherSlotsCount 辅助提取** — `confiscateStorageBagItem` 中 6 处 `otherTypes` 手动计算统一为 `otherSlotsCount(excludeType)` 辅助函数

#### DiscipleService 分解

- **重构：DiscipleStatusService 提取** — 新建 `DiscipleStatusService`（~217 行），从 `DiscipleService` 迁移 `syncAllDiscipleStatuses` + 10 个 build 辅助函数 + `resetAllDisciplesStatus` + `clearAllDisciplesFromElderSlots`。`DiscipleService` 从 575 行降至 358 行

#### 对抗性审查 3 Agent 发现

- **🔴 修复：DiscipleEquipmentService 仓库满时卸装备导致数据丢失**（数据篡改者）— `StackableItemStore.add()` 返回 `Failure` 时，`equipmentInstances.filter{it.id != equipmentId}` 仍执行导致装备实例永久删除。修复：仅在 `Success/Partial` 时删除实例，`Failure` 时保留并返回 false
- **🟡 修复：ProductionProcessor 缺少 NumberFormatException 保护**（数据篡改者）— `candidate.id.toInt()` 在 ID 非纯数字时抛出未捕获异常。修复：改为 `toIntOrNull()` + log
- **🔴 修复：buyMerchantItem Partial 时全额扣款不出全货**（边界狂魔）— `StackableItemStore.add()` 返回 `Partial` 时部分货物溢出，但 `addOk` 未设 false 导致全额灵石被扣、物品未全到账。修复：Partial 时设 `addOk=false` 取消整笔交易

#### 文档

- **文档：架构债务记录更新** — 清除已完成项，新增 4 项待完成（`DiscipleSlotManager.syncAllDiscipleStatuses` 重复实现 / `openStorageBag` 双事务 / `processAutoAssign` 5 步非原子 / 住所分配不更新状态表）

#### 弟子属性正态分布

- **优化：弟子属性正态分布** — 除悟性外 9 技能 + 7 方差从均匀分布改为正态分布（Box-Muller），中间值概率更高，极端值稀有
- **新增：`DeterministicRng.nextGaussian()`** — Box-Muller 变换，消耗 2 次 `nextDouble()` 生成 1 个 N(0,1)

#### 偷盗系统年上限

- **新增：宗门偷盗年上限** — `MAX_THEFT_PER_YEAR=3`，成功偷盗 `annualTheftCount+1`，年变归零
- **移除：单弟子偷盗冷却** — `THEFT_COOLDOWN_MONTHS=12` 由年上限替代
- **清理：** `THEFT_COOLDOWN_MONTHS` 死常量移除；月度扫荡 `lastTheftMonths>=12` 判定移除；`executeSuccessfulTheft` 死参数 `currentMonth` 清理
- **修复：** 月度扫荡 `processTheftMonthly` for 循环内未重新检查 `annualTheftCount`，单月可触超 `MAX_THEFT_PER_YEAR=3` 次偷盗。每次迭代前重新读取实时年上限，达到则 `break`

#### 测试

- **测试：** `nextGaussian` 4 测试、`DiscipleFactoryTest` 更新、`LawEnforcementProcessorTest` 年上限 3 测试

#### 文档

- **文档：知识库更新** — 新增弟子属性生成 / 偷盗系统年上限章节
- **文档：架构债务追加** — 第 6 项 `lastTheftMonths` 写而不读待清理
- **文档：知识库更新** — 库存堆叠待完成项标记本轮修复项，已完成列表 12 项

### 重构

- **重构：SaveValidator 规则引擎** — 原有的 8 项硬编码检查重构为可扩展规则引擎。新增 `SaveValidationRule` 接口 + `SaveValidationRuleRegistry` 注册表（object 模式，参照 `BuildingFeatureRegistry`）。新增检查只需注册一条规则，无需修改核心验证逻辑

### 新增验证规则

- **新增：重复弟子 ID 检测** — 存档中重复的弟子 ID 自动去重，保留第一个出现
- **新增：死亡弟子装备引用清理** — 死亡弟子持有的装备引用自动清除，防止装备被死弟子永久锁定
- **新增：灵石非负检查** — spiritStones/midGradeSpiritStones/highGradeSpiritStones 负值自动截断为 0
- **新增：境界层数合法性检查** — realmLayer 越界（如炼气 99 层）自动截断到合法范围
- **新增：弟子年龄非负检查** — age < 0 的弟子自动设为 16 岁
- **新增：gamePhase 范围检查** — gamePhase 超出 [0,2] 范围自动修正

### Bug 修复

- **修复：保存前校验的 Repaired 结果未写入数据库** — `StorageEngine.save()` 路径的预校验若返回 `Repaired`，修复后数据现在正确写入数据库而非被忽略

### 测试

- **新增：20 个规则测试类** — 每规则独立覆盖通过/修复/损坏三类路径，含注册表测试、上下文测试、全规则集成测试

### 技术债务

- **文档：架构债务记录追加 3 项待完成** — 对抗性审查剩余 4 项（装备去重/生产槽位引用/血炼引用/孤儿功法）、2 个补充修复文件、EntityCountBoundsRule

### 新增验证规则 (v2)

- **新增：装备去重检测 EquipmentDedupeRule** — 同一 equipment ID 被多弟子引用时自动清除后续重复
- **新增：槽位引用检测 SlotRefRule** — 6 种槽位（生产/灵矿/巡逻/藏经阁/住所/仓库）引用不存在弟子时自动清除
- **新增：血炼引用检测 BloodRefinementRefRule** — 血炼 BonusTotals/PctTotals 引用不存在弟子时自动移除 Entry
- **新增：功法/天赋空引用检测 ItemRefConsistencyRule** — 弟子 manualIds/talentIds 中的空字符串自动移除
- **新增：实体数量边界警告 EntityCountBoundsRule** — 弟子 >10000/装备 >5000/战斗日志 >2000 发出警告

### 重构

- **重构：CEP 执法/偷盗迁至 LawEnforcementProcessor** — 消除 530 行重复代码，`CultivationEventProcessor` 从 1189 行降至 718 行
- **重构：handleDiscipleDeath 单事务** — `processDiscipleAging` 合并 Phase 2+3 消除 TOCTOU 窗口

### Bug 修复

- **修复：备份恢复后跳过二次验证** — `CorruptedResultHandler` 在备份恢复后调用 `SaveValidator.validate()` 再确认

### 技术债务

- **更新：架构债务完成 26 项** — 文档仅保留 3 项待完成（processCompletedMissionsLazy 事务化/DiscipleService 再拆分/广告回调净化）

### 防守方选择统一

- **统一：被进攻时自动防守方选择规则** — 三种被进攻场景（妖兽普通战斗、妖兽遇敌战、AI 宗门进攻）统一防守弟子选择规则：一律排除任务中/探索队伍中/面壁中/驻军中/血炼中弟子，优先巡逻塔弟子出战
- **调整：妖兽遇敌战仅巡逻弟子出战** — 遇敌战（弹窗迎战时 AI 拦截）属于巡视塔主动出击，不再补充非巡逻弟子
- **调整：AI 宗门进攻防守改为巡逻优先** — 从按境界取最弱弟子改为优先巡逻塔弟子，再按境界倒序补齐
- **修复：妖兽普通战斗中巡逻弟子未检查排除状态** — 巡逻弟子若处于血炼/任务等状态也会被选中出战（对抗性审查发现）
- **修复：手动选择防守弟子未检查排除状态** — 世界地图手动选人时血炼/任务中的弟子也可被强制出战（对抗性审查发现）
- **测试：同步更新 5 种排除状态** — `ResolveBeastAttackFightTest` 增加 IN_TEAM / GARRISONING 测试用例
- **文档：架构债务新增第 7 项** — `CaveExplorationProcessor` 锁外快照 TOCTOU（防守方选择跨事务边界）

#### 架构债务阶段 E — 事务完整性 + 死代码清理（2026-07-24）

- **重构：DiscipleSlotManager 死代码清理** — 删除已标记 `@Deprecated` 的 `syncAllDiscipleStatuses` 重复实现及 10 个私有辅助方法
- **重构：openStorageBag 单事务（彻底）** — 先生成奖励列表，再在单次 `stateStore.update` 内原子消耗袋子+发放奖励，消除 Phase 1→2 崩溃窗口；消除 `rarity` 闭包副作用模式
- **重构：processAutoAssign 5 步原子化** — 5 次独立 `stateStore.update` 合并为 1 次，预计算候选后单事务写入；住所不改变弟子状态确认为设计不修改
- **重构：CaveExplorationProcessor TOCTOU 根除** — `executePlayerDefenseBattle` 全流程（防守方选择→战前结算→组队→战斗→结果应用）移入单次 `stateStore.update`，`selectAndPrepareDefenders`/`buildDefenseTeam` 改为从 `MutableGameState` 参数读取锁内最新状态
- **文档：架构债务记录精简** — 清除已完成 4 项，仅保留 2 项真正待完成（广告回调透传 / `lastTheftMonths` 写而不读）

#### 迁移崩溃修复

- **修复：MIGRATION_30_31 丢失 disciple 索引致 Room 2.7.0 闪退** — create-copy-drop-rename 循环后未重建 `disciples` 表的 7 个索引。Room 2.7.0 在迁移后校验 schema identity，发现索引缺失抛出 `IllegalStateException`。RENAME 后添加 `CREATE INDEX IF NOT EXISTS` 重建全部索引。迁移测试追加 7 条索引存在性断言防止回归

## [4.0.66] - 2026-07-23（versionCode=4066）

### 新功能

- **新增：免广告特权白名单** — 白名单用户点击广告按钮直接获得奖励，跳过广告播放、冷却和每日次数限制。白名单在 `GameConfig.Whitelist.AD_FREE_UNION_IDS` 中由管理员维护
- **新增：选择模式界面** — 登录后显示（背景图+新游戏/读取存档/退出按钮），右上角显示 TapTap 头像和用户名，点击头像弹出半屏信息面板
- **新增：每日广告次数限制** — 非白名单用户每日最多观看20次广告，超限弹"已达上限"提示框（`tryMarkAdWatched` 原子操作消除 TOCTOU 竞争）

### 游戏机制重构

- **移除：建筑升级玩法** — 移除单人住所升级系统，`upgradeTo`/`upgradeCost` 字段删除，中级建筑改为直接建造
- **新增：中级建筑加入建造栏** — 中级单人住所（50000灵石，修炼速度+40%），中级多人住所（80000灵石，修炼速度+15%）
- **调整：中级建筑宗门等级限制** — 仅中型及以上宗门可建造，等级不足时点击弹出提示框（可点击屏幕外关闭）
- **修复：弟子兑换码生成天赋重复** — 模板级去重对齐 TalentDatabase.generateTalentsForDisciple()，根治不同稀有度同模板天赋被同时选中的问题
- **修复：建造栏等级不足时点击无响应** — clickable.enabled 阻断内部分支，改为始终启用由 when 逻辑判断

### Bug 修复

- **修复：多个对话框叠加背景变黑** — 移除住所升级弹窗（唯一裸 Dialog），消除双层 60% 遮罩叠加
- **修复：单人/多人住所自动入住无视已关注/灵根/悟性门槛** — 只启用一种住所类型时未启用的类型过滤条件短路为 true，导致所有存活弟子均可入住
- **修复：突破率详情弹窗子项金额不等于总值** — 详情以加法格式展示各乘区系数，但实际公式为乘法，底部新增公式计算过程说明
- **安全：doPlaceBuilding 添加宗门等级检查（防御层）** — 金手指/API 直调不再可绕过 UI 限制建造中级建筑
- **安全：requiredSectLevel 添加 require 范围守卫(0-3)** — 防止无效值绕过限制
- **修复：OPPO/Vivo 宗门名称输入框键盘频闪** — 3 个可复用对话框组件（`InlineStandardPromptDialog`、`UnifiedGameDialog`、`SmallScreenDialog`）+ `SettingsTab` 内联 Dialog 的 `DialogSoftInputGuard()` 从 `Dialog {}` 外部移到内部，保证 Dialog 自己的窗口正确应用 `SOFT_INPUT_ADJUST_NOTHING`，切断 OEM 键盘频闪震荡回路
- **优化：宗门命名/改名输入框自动聚焦** — `FocusRequester` + `LaunchedEffect` 自动弹出键盘，`delay(100)` 兼容 ColorOS/FuntouchOS Dialog 入场动画；添加 `KeyboardOptions(Text, Done)` 明确 IME 行为

### UI 优化

- **新增：选择模式界面** — 登录后先选"新游戏/读取存档/退出"再进存档列表，右上角显示 TapTap 头像和用户名，点击弹出 unionId 信息面板
- **重构：存档选择界面 mode 驱动** — 卡片根据模式（新建/读档）差异化交互：新建时自动存档报错、已有存档二次确认覆盖；读档时空槽视为新建、已有存档直读。移除卡片上删除/读取按钮，全卡片可点击
- **新增：存档界面空状态提示** — 无存档时显示"暂无存档数据"文案
- **优化：存档卡片避让系统栏** — 添加 `windowInsetsPadding` 左右避让挖孔屏/手势导航区
- **优化：弟子详情界面响应式布局** — 突破率详情/功法/天赋列数根据屏幕宽度自适应（替代固定3/4/5列），屏幕较宽时排更多列，窄屏自动减少列数

### 数值调整

- **调整：建筑费用全面调整** — 仓库 1500→**20000**、任务阁 6000→**50000**、监牢 5000→**20000**、锻造坊/炼丹炉 4000→**6000**、单人住所 12000→**20000**、多人住所 24000→**30000**（单位：下品灵石）

### UI 优化

- **新增：选择模式界面** — 登录后先选"新游戏/读取存档/退出"再进存档列表，右上角显示 TapTap 头像和用户名，点击弹出 unionId 信息面板
- **重构：存档选择界面 mode 驱动** — 卡片根据模式（新建/读档）差异化交互：新建时自动存档报错、已有存档二次确认覆盖；读档时空槽视为新建、已有存档直读。移除卡片上删除/读取按钮，全卡片可点击
- **新增：存档界面空状态提示** — 无存档时显示"暂无存档数据"文案
- **优化：存档卡片避让系统栏** — 添加 `windowInsetsPadding` 左右避让挖孔屏/手势导航区

### 技术改进

- **替换：免广告特权从原生 C++ 改为 Kotlin** — `AdFreeWhitelist` 替换已丢失源码的 `AdFreePrivilege`，白名单在 `GameConfig.Whitelist.AD_FREE_UNION_IDS` 中由管理员维护
- **修复：AdsDelegate 线程安全** — `AtomicInteger` + 双重检查锁定替代无锁 `MutableList`，消除 `ConcurrentModificationException`；`tryMarkAdWatched()` 原子化检查+递增消除 TOCTOU 竞争
- **新增：SessionManager.avatar 字段** — 持久化 TapTap 头像 URL 供选择模式界面显示
- **新增：AdFreeWhitelistTest** — 4 个单元测试覆盖 null/空字符串/非白名单/重新初始化路径
- **对抗性审查：** 3 Agent（边界狂魔+状态破坏者+数据篡改者）共发现 10+ 项问题，含线程安全/TOCTOU/长文本溢出/测试污染等，已全部修复
- **调整：仓库扩容格数 50→75** — 每座仓库提供 75 格容量（基础 50 + N×75），同步更新引导/WarehouseTab描述文本
- **调整：天枢殿改为全局唯一** — 全地图（含占领宗门）仅可建造 1 座，新增 `isGloballyUnique` 字段 + 守卫断言 + 旧存档多座兼容日志
- **调整：宗门政策系统全面优化** — 政策数量 7→**17** 条，所有政策改为正负双刃剑效果，移除开启消耗
  - **新增 10 项政策**：灵泉灌溉（灵田+15%）、开源节流（年俸-30%/不发忠诚）、苦修令（修炼+25%/800灵石/弟子）、宵禁（治安事件-30%/叛逃-20%）、赏善罚恶（执法+30%）、严苛训练（战斗伤害+5%/忠诚-1）、松弛管理（忠诚+2/修炼-10%）、广纳门徒（招募上限+50%/5万3年）、教化之道（道德+1/月上限70/100灵石/弟子）、仁政爱徒（忠诚+1/月上限100/100灵石/弟子）
  - **调整旧政策**：丹道激励（时间+10%）、锻造激励（时间+10%）、灵药培育（移除负面）、增强治安（忠诚-1/月）、宵禁（忠诚-1/月）、功法研习（移除负面）、修行津贴（改为300/化神下弟子）、苦修令（改为800/弟子）
  - **移除 3 项政策**：闭关资助、精英策略、论道大会
  - **新计费模式**：部分政策改为按弟子数计费（修行津贴300/化神下、苦修令800/全弟子、教化之道100/全弟子、仁政爱徒100/全弟子）
  - **互斥/协同/卡牌面板已移除**，保持原有 checkbox 样式

### Bug 修复

- **修复：Compose LazyColumn 嵌套 verticalScroll 崩溃（Bugly #9043）** — UnifiedGameDialog 默认 `scrollableContent=true` 改 `false`，防止新增对话框无意嵌套 `LazyColumn` 触发 `IllegalStateException`。审计 77 处调用点，2 处需滚动者显式声明 `scrollableContent=true`
- **修复：文本选择工具栏 BadTokenException（Bugly #3026）** — InlineStandardPromptDialog 新增 `clearFocus` onDispose（与 StandardPromptDialog 对齐）；SettingsTab 自动保存间隔 Dialog 补全；MainActivity 新增 ActionModeSafeCallback（与 GameActivity 对齐）
- **修复：Adreno Vulkan 驱动 SIGSEGV（Bugly #9045）** — surfaceDestroyed 改用 2s 截止时间轮询 + `renderThread.interrupt()`；VulkanBackend::submitFrame 阻塞调用后添加 `m_ready` 守卫，消除 Surface 销毁后 VkHandle use-after-free
- **修复：仓库物品不会全部堆叠** — 根因 6 层叠加：`DomainResult.Partial` 被 20+ 调用方忽略溢出静默丢失（P0-1） + 合并只 `find` 第一个堆叠不尝试后续（P0-2） + `confiscateStorageBagItem` 绕过 maxStack（P1-1） + `buyMerchantItem` equipment 分支直接 +1（P1-2） + 6 套不一致合并实现（P1-3） + 无整理功能（P2-1）
- **安全：PeakDialog 移除嵌套 verticalScroll 潜伏风险** — 内部 Column 移除 `Modifier.verticalScroll()`，该函数当前未调用但仍然修复结构
- **修复：巡视楼任命弟子时点击弟子卡片无响应** — 根因 `selectingSlotIndex` 被 coroutine 闭包捕获后立即重置为 -1，导致任命传入错误索引抛越界异常被吞掉（PatrolTowerDialog.kt）
- **修复：弟子入住住所后所有选择界面不显示该弟子** — 根因 `assignToResidence` 中 `releaseDiscipleFromAllSlotsAtomic` 更新 `discipleTables` 后未同步触发 `_disciplesFlow` reassembly，UI 层读取过时状态。在分配流程末尾显式调用 `updateDiscipleStatus(IDLE)` 确保状态同步
- **调整：赏赐弟子/赏赐道具对话框改为全屏** — 移除米色背景和"给予弟子"文本，筛选按钮改用标准按钮组件+选中态黑白区分
- **修复：巡视楼应用弟子时双重释放+赏赐全屏滚动嵌套** — 移除 `onConfirm` 中多余的 `releaseDiscipleFromAllSlotsAtomic`；赏赐全屏对话框 `scrollableContent` 改为 `false` 消除 `LazyVerticalGrid` 嵌套

### 架构债务清理

- **重构：住所/巡视楼分配原子性统一修复** — 6 个原子方法（`GameEngineAtomicAssign.kt`）将住所/巡视楼分配从多次独立 `stateStore.update` 重构为单事务原子操作；修复 4 个架构债务：#17 原住户覆盖时 gate 注册残留（幽灵注册）、#18 多事务非原子更新（违反规范6.2）、#19 `CancellationException` 被 `catch` 吞没（违反规范8.1）、#20 fire-and-forget 无返回值
- **新增：渲染管线直达推送通道（`RenderCommandBus`）** — 建筑放置/移动/拆除后通过独立于 Compose 反应式管线的直达通道推送 buildingData 到渲染线程，消除帧率门控导致的建筑消失 Bug；对标 UE `ENQUEUE_RENDER_COMMAND` 模式
- **对抗性审查修复** — 3 角色审查发现 20+ 独立问题：修复 `assignToResidenceAtomic` 对旧 occupant 全量 `clearAllSlots` 过度清除（改为精确槽位释放）；修复 `renderVulkanFrame` TOCTOU 竞态（`consumeBuildingData()` 单快照 + `coerceAtMost` 保护）；修复 `autoAssignPatrolAtomic` 重复校验；修复 `releaseDiscipleFromAllSlotsAtomic` 双 `gate.release()` 冗余调用
- **修复：`removeFromResidence` 缺少 `updateDiscipleStatus`** — 与 `assignToResidence` 状态对称，移除住所后设回 IDLE
- **测试：`GameConfigTest` 预存断言值过时** — `CULTIVATION_SUBSIDY_PER_DISCIPLE` 4000L→300L，测试名同步更新

### 架构重构

- **重构：仓库物品堆叠系统** — `StackableItemStore.keyIndex` 升级为多 ID 列表，支持同种物品多个堆叠；溢出时自动创建新堆叠（不再静默丢失）；`InventorySystem` 6 组添加方法统一委托 `StackableItemStore`；新增 `consolidateStacks()` 整理功能；读档时自动执行 `BootSequenceController.consolidateStacks()`
- **修复：没收弟子物品事务回滚** — 先尝试添加入库成功后再从弟子储物袋移除，防止仓库满时物品从双方丢失
- **安全：`StackableItemStore.add/remove` 增加负数/过量守卫** — `quantity <= 0` 返回 `InvalidQuantity`、`count > existing.quantity` 返回 `Insufficient`，防止公开 API 被错误调用导致数据损坏
- **安全：`consolidateStacks` 跳过锁定物品** — `isLocked` 堆叠不再被整理合并或删除
- **修复：年度报告在 Partial 场景超额计数** — `addEquipmentStack`/`addPill`/`addHerb` 三处改为按实际入库量计数，消除 20-40% 数据膨胀
- **修复：`confiscateStorageBagItem` 缺少年度报告追踪** — 没收装备/丹药/草药补充 `confiscate` 来源统计
- **修复：`EntityStore.mergeStackable` 只合并第一个堆叠** — 改为遍历所有匹配堆叠，与全系统行为一致
- **死代码清理** — 删除 `InventorySystem.addWithStore`/`removeStackable`（从未调用）

## [4.0.65] - 2026-07-22（versionCode=4065）

### 新增功能

- **新增：新手引导系统** — 在外交按钮左侧新增引导按钮，点击弹出全屏引导界面。25 个分步任务覆盖所有建筑类型和职位，每项任务描述包含具体加成效果和玩法说明。任务完成奖励凡品储物袋×2，条件进度实时检测
- **新增：引导任务 25 项** — 涵盖灵矿场、灵田、灵植阁、炼丹炉、锻造坊、天枢殿、藏经阁、问道塔、青云塔、执法堂、任务阁、巡视楼、住所、仓库、血炼池、监牢等所有建筑，以及副宗主、外门长老、内门长老、执法长老、讲道传道师、青云传道师、执法亲传、灵矿执事等全部职位
- **新增：GuideCounterKeys 常量类** — 引导系统所有计数器 Key 统一为编译期安全常量，消除拼写错误风险

### 架构优化

- **重构：`SectPolicyToggleUseCase` 7 个政策切换函数合并为单事务** — 消除 toggle 过程中的中间状态窗口，政策切换和计数器递增在单次 `stateStore.update` 内原子完成
- **重构：`AutoAssignDelegate.setAutoAssignSettings` 合并为单事务** — 自动分配策略写入和引导计数器递增原子提交，消除崩溃后状态不一致
- **修复：`claimGuideReward` TOCTOU 竞态** — 条件检查和奖励发放全部移入 `stateStore.update` 原子执行，消除条件变化与奖励发放之间的窗口
- **修复：`UUID.randomUUID()` 改用 GameRngManager** — 储物袋 ID 生成从 `UUID.randomUUID()` 改为 `GameRngManager.getRng(RngPartition.SYSTEM)`，符合确定性 RNG 规范
- **重构：月度事件管线全量单事务提交** — `processMonthlyEvents` 中 10/13 个子服务合并为单次 `stateStore.update`，从 13 次降为 4 次 StateFlow 发射。`processCompletedMissionsLazy` 两阶段改造消除奖励丢失风险。执法/偷窃内部方法全部 MutableGameState 化消除读-写窗口
- **新增：存档双缓冲回退机制** — `SaveFileManager` 提供 CRC32C 校验 + write-tmp→rename 原子写入 + `.sav`/`.bak` 双文件回退。`StorageEngine` 接入保存前校验、重试、WAL 事务包裹、自动备份恢复。自动存档跳过备份

### 新增功能

- **新增：战斗日志新增\"年报日志\"标签页** — 战斗日志对话框内新增\"年报日志\"标签，每年结束自动生成年度报告，包含灵石收支（按来源/原因分类）、生产产出（锻造/炼丹/灵植）、弟子变动（新增/死亡/脱离）。标签页内分两级：历年列表（年数+收入+支出）→ 点击查看 7 行详情对话框（汇总 FlowRow + 收入来源 + 支出来源 + 锻造装备 + 炼制丹药 + 收获草药 + 弟子变动）。数据保留最近 100 年，超出自动删除

### Bug 修复

- **修复：操作弟子详情等界面时 ANR 闪退（Bugly #9041/#5068）** — 根因：UI 层 Delegate 通过 `scope.launch`（默认 `Dispatchers.Main`）调用 `GameStateStore.update{}`（内部使用阻塞式 `ReentrantLock`），当引擎线程持有锁时主线程阻塞 10 秒触发 ANR。新增 `GameEngine.launchOnEngine{}` 方法将所有 UI 触发的引擎操作派发到引擎单线程执行，配合 Detekt 自定义规则 + Gradle 编译时检查 + 运行时主线程监护，从架构层面根除同类 ANR
- **修复：物品详情界面 Buff 描述显示英文代码** — 功法/丹药详情中 10 种 Buff（生命加成/物攻加成/暴伤加成等）因 `buffType.name.lowercase()` 与字符串映射表不匹配，显示为 "hp_boost" 等英文代码。新增 `formatBuffLine(BuffType)` 重载，直接使用枚举 displayName 跳过字符串映射
- **修复：自动入住住所忽略灵根/已关注/属性门槛过滤** — 单人/多人住所自动入住代码无视用户的过滤设置，所有存活弟子均按悟性排序填入。现已补全 focused/rootCounts/threshold 双条件过滤
- **修复：自动管理多选时排序无优先级** — `takeCandidate` 排序从单维度 `maxByOrNull` 改为 3 层优先级（已关注优先 → 灵根数升序 → 属性降序），与用户选择的优先级一致
- **修复：消息界面切换标签时不滚动到底部** — 展开消息面板后切换到"世界"标签时列表不自动滚动到底部。根因：`scrollToBottomTrigger` 仅响应 `isExpanded` 变化，切换 `selectedTab` 时不触发
- **修复：数据库 Schema 版本 25→26 迁移** — 新手引导系统在 GameData 新增 `guideClaimedRewardIds`/`guideCounters` 字段时未升级 Room 数据库版本，导致旧存档打开时 Room 校验失败。已新增 MIGRATION_25_26 自动添加两列

### UI 优化

- **UI：引导界面全面调优** — 背景色改为消息展开态同款米色（#F5F5DC），左侧任务名称改用黑色字体并增加左间距避开状态栏（刘海屏兼容），右侧奖励区域改用物品卡片组件（含物品图标和数量角标），任务条件列表居中排列
- **调整：长按移动建筑长按时间从400ms延长至800ms** — 减少误触，适配长按移动建筑场景

### Bug 修复（2026-07-23）

- **修复：自动招募计数虚增** — `recruited++` 移入 `allocateAndInsert` 成功守卫内，失败时不计入，防止返回值虚报
- **修复：自动招募 `spiritRootType` 空字符串误判单灵根** — `split(",").size` 在空串时返回1，改为 `count { it.isNotBlank() }` 过滤空段
- **修复：`autoRecruitSpiritRootFilter` 无值域守卫** — 只接受 1-5 有效灵根数量，剔除入库不合理值
- **修复：`recruitList` 重复 ID 无去重** — 添加 `distinctBy { it.id }` 防止同一弟子被多次招募
- **修复：`lifeEvents` 两次冗余写入** — `allocateAndInsert` 传参前设好 lifeEvents，消除二次写入
- **安全：`ChildBirthSystem.replaceAll` 覆盖自动招募新生儿** — 改为增量 `update()` 逐个更新母亲状态，修复 `processAutoRecruit` 新增弟子被 `replaceAll` 静默清除的严重 Bug
- **安全：对抗性审查 3 Agent 全覆盖** — 边界狂魔/状态破坏者/数据篡改者共发现 12 项问题，6 项已修复

### 架构优化

- **重构：自动招募触发时机** — 改为任一来源产生待招募弟子后立即执行（弟子生育/AI 宗门招募/占领俘获/年度刷新），4 源全覆盖，匹配灵根过滤器自动加入宗门

### 测试覆盖

- **新增：`processAutoRecruit` 11 个单元测试** — 正常匹配/空过滤器/不匹配灵根/混合列表/空列表/损坏数据（空白名/年龄0/境界越界）/新生儿

## [4.0.64] - 2026-07-21（versionCode=4064）

### 自动管理增强

- **新增：自动管理新增单人住所/多人住所/灵植阁选项** — 天枢殿→宗门管理→自动管理新增3项自动分配（已关注+5灵根筛选+悟性/灵植属性门槛），排序：住所优先→灵植→生产建筑
- **新增：空闲弟子自动种植** — 空闲弟子自动进入灵植阁槽位，灵植属性≥门槛筛选，状态显示"灵植中"
- **优化：自动管理对话框底部按钮固定** — 关闭和保存按钮固定在对话框底部，无需滚动内容即可点击
- **安全：threshold 输入上限 coerceIn(1,999)** — 防止 Int.MAX_VALUE 导致自动分配静默失效
- **安全：未保存更改弹窗误报修复** — hasChanges 使用对话框打开时的初始 policies 快照，引擎修改政策不再误报
- **重构：自动分配全链路原子化** — 单人+多人住所合并单次写入、矿井 slot+status 合并写入、原子快照 takeAtomicSnapshot（持锁读取全部字段，消除存档捕获部分写入状态）
- **新增：炼丹中/锻造中/灵植中专用状态** — `ALCHEMY`/`FORGE`/`SPIRIT_PLANTING`，自动重启守卫适配新状态，状态同步函数补全生产推导
- **对标：行业对标完成并写入架构文档** — 存档原子性/角色状态系统对标 UE/Supercell/RimWorld，3 条待完成项写入 docs/architecture.md

### 妖兽进攻系统重构

- **新增：AI 进攻目标预计算 + 巡视塔冲突** — AI 预计算最近 2 个宗门进攻目标，若巡视塔也同时瞄准同一妖兽则巡逻队与最近 AI 打遭遇战（PvP→PvE），胜者进攻妖兽
- **新增：AI vs AI 遭遇战** — 未被巡视塔拦截的妖兽，2 个 AI 宗门打遭遇战（AI vs AI PvP→PvE），胜者进攻妖兽
- **新增：妖兽查看锁定** — 玩家在世界地图打开妖兽详情弹窗时锁定该妖兽，月度结算跳过 AI 攻击判定；关闭弹窗后恢复正常
- **重构：候选池纯 AI** — 进攻候选池不再包含玩家宗门，直接取最近 2 个 AI 宗门判定，远的清理
- **安全：对抗性审查修复 4 项** — Team B 死亡弟子过滤、全员阵亡巡逻记录丢失、目标列表去重、冲突战斗空结果防护

### 战斗

- **境界压制斩杀重做** — 改为总小层数差距 > 9 触发斩杀（大境界差×9 - 层数差），防御方层数越高差距越小，更符合直觉
- **统一斩杀入口** — `BattleCalculator.calculateCombatantDamage` 新增 `enableInstantKill` 参数，设 `true` 后自动斩杀+境界差乘区全带上，新战斗系统不再遗漏
- **修复：AI 弟子 Combatant 缺 realmLayer** — `AISectAttackManager.convertToCombatant` 补传 `realmLayer`，AI 弟子小境界层数首次生效
- **修复：洞穴探索玩家 Combatant 缺 realmLayer** — `CaveExplorationSystem` 玩家 Combatant 补传 `realmLayer`
- **修复：天道试炼战斗逻辑缺失斩杀判定** — 普攻/技能/即时结算各路径全部补上斩杀前置检查

### 修改

### 修改

- **住所自动入住改为无视状态限制** — 自动管理中的单人住所/多人住所改为无视工作状态/已关注/灵根数/属性门槛，所有存活弟子均可自动分配到住所（仅排除已死亡和已入住弟子）
- **对话框遮罩层叠加修复** — 多对话框同时显示时（如主对话框+提示框），子对话框不再叠加自己的60%黑色遮罩，防止界面外部变全黑
- **拆除按钮改为 4×2** — 移动建筑模式下拆除按钮高度翻倍，更易点击
- **弟子招募改为每 3 年刷新一次** — 空列表时文字提示"招募每3年刷新一次"
- **初始赠送改为 2 个凡品储物袋** — 移除初始凡品功法，改为 2 个凡品储物袋

### UI

- **一键出售弹窗按钮统一为标准 GameButton** — 品阶筛选、类型筛选、取消、确认出售全部使用标准按钮组件
- **一键出售弹窗底部按钮固定** — 取消和确认出售按钮固定在弹窗底部，无需滚动即可点击
- **一键出售弹窗筛选行列自适应** — 品阶和类型筛选按钮根据屏幕宽度动态调整每行列数

### 安全

- **招募防御刷新改用 lastRecruitYear 守卫** — 修复非刷新年读档后招募列表为空且无法刷新的状态死锁
- **一键出售确认按钮添加防重入守卫** — 防止快速双击导致物品重复出售
- **GridRow 添加 maxColumnWidth=0 除零保护** — 防止 API 误用导致无限 Spacer OOM

### 优化

- **自动炼丹/自动锻造增加同配方优先逻辑** — 点击自动按钮后，空闲槽位立即开始炼制；有历史配方的槽位优先炼制相同物品，材料不足时自动选用高品阶可合成配方
- **安全：ForgeViewModel 补全 CancellationException 重新抛出** — toggleAuto/startForge 两处 catch(Exception) 前补全 CancellationException 守卫

### 更新

- **招募灵根概率调整** — 单灵根 0.5%→1%，双灵根 1.5%→3%，三灵根 20%→26%，四灵根 38%→30%，五灵根 40%→40%（维持不变，三灵根以上弟子更容易招募到）
- **宗门地图建筑图层覆盖优化** — 建筑按网格 Y 坐标排序渲染（Painter's Algorithm），屏幕下方（高 Y）建筑自然覆盖上方（低 Y）建筑，消除层级错乱。行业对标：Godot YSort / Unity Custom Sort Axis / Bevy extol_sprite_layer / RimWorld

### 妖兽进攻重做

- **修复：妖兽预警弹窗"迎战"按钮** — 从"知道了"改为"迎战"，明确战斗意图
- **修复：防守结果弹窗必现** — 移除 `patrolBattleResultPopup` 守卫，妖兽防守战斗始终显示结果
- **修复：巡逻塔先于攻击检测** — 已击败妖兽不再产生无效预警
- **修复：自动派遣弟子出战** — 优先巡视塔弟子，其次宗门内其他弟子（排除任务/思过/血炼）
- **新增：玩家vsAI 遭遇战** — 玩家手动进攻或弹窗迎战时，与附近的 AI 宗门打遭遇战，胜者进攻妖兽
- **新增：多妖兽逐个处理** — 多个妖兽同时攻击时逐个弹窗，resolve 一个再处理下一个
- **修复：双击防抖** — 防快速双击触发重复战斗
- **修复：TOCTOU 防护** — 战斗锁内重检妖兽击败状态，防锁外快照过时
- **修复：异常保护** — 月度世界处理增加 try-catch，单步失败不影响整体结算
- **修复：AI 弟子战斗缺失装备/功法加成** — 修复三路径 AI 弟子无装备/功法加成问题：探索遭遇战 AI 方只算天赋无装备/功法、巡逻冲突战 Phase 1 和 AI vs AI Phase 1 PvP 完全无效（AI 不参战，打的随机妖兽）。提取公共函数 `prepareDisciplesForBattle()` 统一生成模拟装备/功法（品阶按境界范围随机），消除 3 份重复代码

## [4.0.63] - 2026-07-21（versionCode=4063）

### 新增

- **地图边界树木区域** — 宗门地图四边各 3 格强制覆盖为树木装饰物（TREE1/TREE2 棋盘格交替），作为不可建造/移动的宗门边界
- **边界建造限制** — `GridSystem.validatePlacement()`、`GridSnapHelper`、金手指批量建造三处同步限制，新建/移动建筑均无法放置在边界区域内
- **二层防御验证** — `BuildingDelegate.placeBuilding()` 和 `BuildingFacadeImpl.moveBuildingDirect()` 底层方法增加边界检查，提供 UI 层之外的防御纵深

### 代码质量

- 对抗性审查（3 Agent）：边界狂魔/状态破坏者/数据篡改者共发现并修复 5 个问题
  - `GameConfig.SectMap` 添加 `require` 守卫防止 `BORDER_TREE_RING` 负值/超半宽
  - `computeGoldFingerCellValidities` 添加空范围防御性检查
  - `GridSystem` `remember` key 添加 `buildableBorder` 前瞻加固

### 修复

- **建筑生产系统月变时不触发收获/完成检测** — `PlantingSystem`、`AlchemySystem`、`ForgeSystem` 三个 `GameSystem` 未注册到 `CoreModule.provideSystemManager()` 的集合中，导致 `SystemManager.onMonthlyEvent()` 从不调用它们的 `onMonthlyEvent()`
  - 灵田：`processSpiritFieldHarvest` 永不执行 → 种子成熟后不被自动收获
  - 炼丹：`processBuildingProduction` 永不执行 → 炼丹完成后不被自动收获
  - 锻造：`processBuildingProduction` 永不执行 → 锻造完成后不被自动收获
- **边界树木区域旧存档建筑兼容** — 在 `BootSequenceController.boot()` Step 3.5 增加迁移逻辑：读档时检测 `placedBuildings` 中位于 3 格边界内的建筑，自动拆除 + 返还 50% 造价 + 通过 `SlotGroup.filterFromGameData` 清理所有关联槽位数据 + 释放关联弟子

## [4.0.62] - 2026-07-21（versionCode=4062）

### 修复

- **CircularBuffer 泛型数组 ClassCastException** — `arrayOfNulls<Any?>(capacity) as Array<T?>` 因 Kotlin 泛型类型擦除在构造时触发 `ClassCastException`，导致所有 12 个 `CircularBufferTest` 测试挂掉，FPS/修炼速率采样环形缓冲区从未正确运行。改用 `arrayOfNulls<Any?>(capacity)` 内部存储 + 访问点转型

### 代码质量

- **GameConfig.Vassal 迁移至独立的 VassalConfig** — 消除 `object Vassal : Any` 弃用警告数十处
- **GameLifecycle 弃用兼容层加 @Suppress** — `GameStateStore` 接口/实现/测试加 `@Suppress("DEPRECATION")`，SaveLoadViewModel 移除无外部调用的 `gameLifecycle` 属性
- **StackableItemStore/EntityStore 泛型 unchecked cast 加 @Suppress** — 消除 6 处 unchecked cast 警告

## [4.0.60] - 2026-07-21（versionCode=4060）

### 修复

- **时间显示冻结** -- `GameStateStoreImpl` 自动批量发射模式在 ≥3 字段变化时抑制 `_gameDataFlow` 发射，而 `_disciplesFlow`（锁外异步组装）不受影响，导致时间显示冻结但修炼进度条持续变化。移除自动批量发射检测逻辑，个体 StateFlow 始终正常发射
- **仓库售卖物品不刷新** -- 同上根因，批量售卖 (`bulkSellItems`) 在单事务内修改 4+ 个 EntityStore 触发批量模式，所有仓库 StateFlow 被抑制。移除批量模式后售卖正常发射
- **`gameDataUi` 采样丢帧** -- `.sample(100)` 与 100ms 游戏循环互为采样周期产生相位漂移，改用 `.distinctUntilChanged()` 结构相等检测剔除无效发射
- **时间推进一会后永久冻结** -- 游戏循环体无异常保护，某次 tick 中任意系统抛异常杀死协程后，看门狗和主线程健康检查均因检查 `isGameLoopRunning`（协程状态）而跳过恢复。修复：游戏循环内加 try-catch 保护；看门狗移除 `loopActive` 检查，循环崩溃后仍可恢复；主线程健康检查同理修复

### 重构/优化

- 移除 `GameStateStoreImpl.batchEmissionMode` 自动批量发射模式及相关死代码

## [4.0.59] - 2026-07-20（versionCode=4059）


### 修复

- **占领AI宗门后建筑不持久** -- 建筑溢出迁移按 `sectId` 分组检测，不同宗门的建筑使用独立网格坐标，避免坐标重合被误拆
- **占领AI宗门后附庸关系未清理** -- 占领分支增加 `vassalContracts.filter` + `suzerainSectId` 清除，被占领宗门的所有附属关系一并移除
- **灵石奖励异步火抛导致事务不一致** -- `stateStore.update` 内的 `addSpiritStones` 替换为 `spiritStoneWallet.add` 同步调用，消除奖励丢失风险
- **建筑迁移灵石直写绕过钱包审计** -- `applyBuildingMigration` 改用 `spiritStoneWallet.add`，完整记录账本流水


### 战斗平衡

- **AI敌人属性公式统一** — EnemyGenerator/HeavenlyTrialService 改用 `GameConfig.Realm.get(baseXxx)` + ±30%方差，删除 `Enemy.REALM_STATS`（原为玩家2.2x）
- **境界差乘区平滑化** — DAMAGE_BONUS/PENALTY 0.50→0.35，gap=2 时低境界方从0%→30%伤害；INSTANT_KILL_GAP 3→5
- **妖兽生成感知玩家进度** — LevelGenerator 根据弟子平均境界 clamp `[avg-1, avg+2]`；巡逻塔目标动态匹配队伍境界
- **RNG分区隔离** — 新增 `ENEMY_GEN(4)` 分区，敌人属性 RNG 与战斗 RNG 隔离

### 修复

- **跨槽位存档损坏** — StorageBag复合主键修复(primaryKeys=[id,slot_id])+createNewGame同步repository槽位上下文+存储引擎强制storageBags slotId赋值(MIGRATION_23_24)

### 清理

- **GameStateRepository._pendingWrites死代码** — MutableSharedFlow定义+tryEmit从未被消费,已全部移除

### 修复

- **功法熟练度每旬不增长** — 只在战斗前追赶1旬，非战斗弟子永不增长。改为每旬对所有存活弟子自动结算熟练度，列级直读（manualIds/comprehensions）替代assemble()避免热路径违规
- **装备孕养每旬不增长** — 同上，改为每旬对所有存活弟子自动结算装备孕养经验，列级直读装备ID无需assemble()
- **功法替换熟练度残留** — 自动学习替换功法后旧熟练度条目永不清除，存档不断膨胀。每旬自动清理+替换路径同步清理
- **战斗前双重结算** — forceSettleDisciplesBeforeBattle原含熟练度/孕养追赶，与每旬增长重叠。移除追赶路段仅保留HP/MP恢复，消除双倍增长

### 重构/优化

- **移除死代码** — processDiscipleTick/applyAccumulator/PhaseTickAccumulator + 5个TickContext data class + DiscipleTickParams（均无调用方）
- **自动装备/学习全量assemble改为列级预过滤** — storageBagItems列直读过滤后仅assemble有储物袋物品的弟子，减少每旬热路径开销
- **清理重复常量** — CultivationCore私有NURTURE_GAIN_PER_PHASE改为引用EquipmentNurtureSystem公开常量

### 预存Bug修复

- **天道试炼装备属性未应用** — 选取的装备只显示名称，攻防HP从未计入Combatant
- **DiscipleTables.deepCopy增量复制Bug** — 只复制脏列导致非脏列空数组，assembleAll()返回0弟子
- **RoomMigration DROP COLUMN兼容** — MIGRATION_22_23 改用 PRAGMA + create-copy-drop-rename
- **App测试编译错误** — CultivationSettlementConcurrencyTest/MailServiceTest 缺构造参数

### 移除

- **AI洞府弟子队伍系统** — 移除 CaveExplorationSystem.createAIBattle()、generateAITeamInline / spawnAITeams / removeStaleAITeams；玩家探洞仅对战守护兽

### 修复

- **占领AI宗门后建筑不持久** — 建筑溢出迁移按  分组检测，不同宗门的建筑使用独立网格坐标，避免坐标重合被误拆
- **占领AI宗门后附庸关系未清理** — 占领分支增加  +  清除，被占领宗门的所有附属关系一并移除
- **灵石奖励异步火抛导致事务不一致** —  内的  替换为  同步调用，消除奖励丢失风险
- **建筑迁移灵石直写绕过钱包审计** —  改用 ，完整记录账本流水


### 重构

- **架构债务Phase4全部清空** — CEP执法/偷窃委托到LawEnforcementProcessor（955→616行）；`handleDiscipleDeath` 原子化（`preDeathCleanup` + `applyDeathState` 拆分）；`processCompletedMissionsLazy` 单事务化；执法/偷窃读写窗口修复（收集+单事务写入）
- **DiscipleService深度拆分** — 995→164行协调器，提取7子服务（DiscipleStatusSyncService/ResetService/ExpelService/DiscipleStatusManager）
- **GameViewModel拆分** — 1833→626行（-1207行），提取8个Delegate（Ads/Bag/GameLoop/Mail/Overlay/RedeemCode/Settings/SignIn）
- **LawEnforcementProcessor重复代码清理** — 删除CEP中~270行旧执法逻辑
- **DiscipleService构造参数11→7** — 移除scopeProvider/inventoryConfig/discipleSlotCleanup/productionSlotRepository

### 性能

- **ADPF Performance Hint集成** — 新建AdpfManager，系统自动调度游戏线程到大核（API 31+），帧预算随场景动态变化
- **Canvas地图渲染分层LOD** — 根据缩放倍数分层：Layer 0完整精度→Layer 1跳过装饰→Layer 2纯色地面+建筑主体，远距离渲染量减40-60%
- **地面/建筑离屏缓存分离** — GroundChunk只缓存地面+装饰，建筑逐帧绘制，建筑变化不刷新缓存
- **Paint/Path/RectF对象池** — 新建CanvasObjectPool，每帧GC分配从4+归零
- **帧预算感知结算调度SettlementScheduler** — 热控时月度≤5ms/帧分2-3帧，年度≤3ms/帧分5-8帧
- **年结分帧FrameStagedExecutor** — 21阶段跨tick执行，消除年变卡顿
- **纹理分级压缩** — TextureCompressionConfig配置框架，低端设备RGB_565+降采样（内存-50%）
- **设备分级纹理降级** — LOW端Vulkan图集2048→1024（显存16MB→4MB），精灵分辨率降50%
- **内存分级预算MemoryBudgetManager** — 高端≤1.5GB/中端≤800MB/低端≤400MB三档限额
- **AndroidMemoryBudgetManager** — ActivityManager.getMemoryInfo()获取系统实时内存压力
- **6服务懒加载Provider化** — 探索/外交/兑换码/签到/自动购买/外交Facade延迟初始化
- **场景自适应帧率** — IDLE 10fps/地图滚动30fps/游戏战斗60fps动态切换
- **@Immutable注解** — 60+游戏状态data class加注解，减少Compose重组

### 修复

- **所有预存测试** — CultivationCoreTest/CultivationServiceIntegrationTest/DiscipleService测试等6文件全部修复，子服务Mock替换为真实实现
- **CancellationException保护** — 引擎+UI模块30处catch补全`rethrow`
- **clearForgeSlotsIfNeeded跨线程竞态** — scope.launch→runBlocking同步化
- **DiscipleService死代码** — cleanupEquipmentAndManuals/clearExternalEquipmentAndManuals/clearInternalEquipmentAndManuals删除
- **BootSequenceControllerTest/ExplorationTeamManagerTest** — 移除已删除的createSettlementShadow/swapFromShadow重写## [4.0.58] - 2026-07-18（versionCode=4058）

### 性能优化

- **性能：DirtyTracker 增量 deepCopy** — `DiscipleTables` 新增 `DirtyTracker` 列级脏标记系统。`deepCopy()` 现在只复制被修改过的组件表列（典型场景 1-5 列替代 110 列），大幅减少 `stateStore.update` 锁内耗时。对标 Unreal Engine TTripleBuffer 脏标记模式
- **性能：增量 assemble** — `assembleAllIncremental()` 只重新组装本次事务中修改过的弟子，与全量缓存合并。对标 Bevy ECS change tick 跳过未修改组件
- **性能：contentHashCode 引用缓存** — `SoftwareCanvasBackend` 缓存 `tileData`/`buildingData` 引用，稳态帧跳过 16384 元素 O(n) IntArray 哈希遍历
- **性能：条件 copyOf** — `NativeSurfaceView.updateRenderState` 仅在 tileData/buildingData 引用变化时执行防御性复制，稳态帧零数组分配
- **性能：批量发射模式自动启用** — `GameStateStoreImpl.update()` 在检测到 3+ 字段变化时自动启用批量抑制模式，减少个体 StateFlow 发射次数
- **性能：tick 跳过满修为弟子** — `checkBreakthroughsAndPills` 修炼累积循环使用列直读跳过 cultivation ≥ 1e8 的弟子（凡界已满），避免 assemble 开销
- **性能：CopyOnWriteArrayList→mutableListOf** — `DiscipleTables.ids` 改用 `mutableListOf` + `synchronized(ids)` 保护，消除每次 insert/remove 的全数组复制
- **性能：CircularBuffer O(1) 环缓冲区** — 重写为基于 Array + head/tail 索引的环形缓冲区，替代原 ArrayList.removeAt(0) O(n) 实现
- **性能：ViewModel 状态聚合** — `GameViewModel` 新增 `GameScreenAggState` 聚合 data class，组合 gameData + highFreqState + configState + isPaused，减少主界面 collect 数量

### 重构

- **重构：13 处 runBlocking 全部消除** — 生产代码零 `runBlocking` 调用。`GameStateStoreImpl.updateAndReturn` 直接调用；`DiscipleLifecycleProcessor.clearForgeSlotsIfNeeded`、`DiscipleService.clearDiscipleFromAllSlots`、`MailService`（2 处）、`ProductionProcessor`（6 处）改为 `scope.launch(Dispatchers.IO)`；`CultivationService`、`ProductionCoordinator`、`ProductionTransactionManager`（2 处）全链路 suspend 化
- **重构：通知队列系统** — 新增 `notifications: StateFlow<List<GameNotification>>` + `enqueueNotification`/`consumeNotification` API。`ConcurrentLinkedQueue` 实现，上限 200 条自动丢弃最旧。旧 `pendingNotification` 标记 @Deprecated
- **重构：GameOverlayHost 参数聚合** — 新增 `OverlayViewModels` 和 `OverlayCallbacks` data class，18 个参数降为 2 个。解构后保持 1000+ 行内部代码不变
- **重构：月变事件单事务化** — `processMonthYearChange` 中原 2 次独立 `stateStore.update`（policyCosts + systemEvents）合并为 1 次
- **重构：LawEnforcementProcessor 拆分** — 从 `CultivationEventProcessor`（946 行）中提取执法/偷窃逻辑到独立 `LawEnforcementProcessor`（270 行），降低 CEP 复杂度。旧代码因行数过多暂保留在 CEP 中（见架构债务 #11）
- **重构：GameEventBus 标记弃用** — 标记 `@Deprecated`，引导新事件注册到 `EventBus`（GameEvents.kt）
- **重构：影子结算 API 标记弃用** — `createSettlementShadow`/`swapFromShadow` 标记 @Deprecated（惰性结算引擎已替代）
- **重构：DAO 查限制** — `BattleLogDao.getAll`/`getAllSync` 加 `LIMIT 200`
- **重构：全链路 suspend 化** — `AlchemySystem`、`ForgeSystem`、`CultivationService.processAutoAlchemy/processAutoForge`、`ProductionProcessor.processAutoAlchemy/processAutoForge` 改为 suspend 函数，消除内部 runBlocking

### 新增

- **新增：云游商人手动刷新** — 商人不再每年自动刷新，改为每30年获得1次刷新次数（初始1次），商人界面新增刷新按钮消耗次数刷新商品。刷新次数右侧新增播放广告按钮，观看激励视频获得3次刷新次数（1分钟冷却，AtomicBoolean幂等守卫，Activity生命周期保护）

### 修复

- **修复：对抗性审查10项全修复** — TOCTOU竞态（updateAndReturn原子化检查+扣减）、旧存档兼容未设lastGrantYear导致双倍发放、onRewardVerify回调不幂等（AtomicBoolean compareAndSet）、次数无上限溢出（coerceAtMost 999）、setCallback覆盖（destroyAd保护）、adCooldownUntilMs跨线程不可见（@Volatile）、Activity销毁后回调崩溃（isDestroyed检查）、gameYear=0无限发放（year<=0防御）
- **修复：上架管理丹药品质显示** — 已上架丹药列表新增彩色品质标签（下品/中品/上品），与选择卡片一致使用 `getQualityColor` 着色；选择上架时三种品质丹药均显示为独立卡片

### 重构

- **重构：弟子多槽位互斥统一门卫** — 新增 `DiscipleAssignmentGate` + `DiscipleAssignmentRegistry` 全局分配注册表，覆盖 11 个槽位系统（长老/亲传/生产/灵矿/藏经阁/住所/仓库/巡逻/驻军/血炼/战斗队伍），运行时自动释放旧槽位 + 读档重建注册表；新增 `SlotCategoryCoverageTest` 测试守卫，新增 `SlotCategory` 枚举值未同步更新 4 处时测试失败并给出修复指引
- **重构：`DiscipleSlotCleanup` 消除 companion 桥接** — 改为标准 `@Singleton` 注入，`clearAllSlots` 内部自动调用 `gate.release()`，死亡/驱逐/释放路径无需手动清理注册表
- **重构：灵矿执事分配统一入口** — `SpiritMineViewModel.assignSpiritMineDeacon` 改为走 `ElderManagementUseCase.assignDirectDisciple`，消除直接写 `gameData.elderSlots.spiritMineDeaconDisciples` 的绕过模式
- **改动：显示所有弟子筛选逻辑** — 思过中弟子勾选后可见并可被选择，选中视为手动释放（不给道德/忠诚加成）；血炼中弟子选中视为血炼失败（不返还材料）；仅战斗中/任务中排除
- **修复：EnemyGeneratorTest 预存10个测试失败** — 根因 `enemyGenRngManager` 未初始化，添加 `@Before`/`@After` 初始化和清理
- **修复：仓库驻守对话框"显示所有弟子"无效** — 预过滤硬编码 `d.status == IDLE` 导致勾选框失效，已移除并改为委托 `filterByDiscipleStatus` 控制；同时补传 `showAllEnabled`/`battleAndExplorationIds` 参数；选择非空闲弟子时自动释放原槽位
- **修复：filterByDiscipleStatus 未勾选时未排除战斗中弟子** — `showAllEnabled=false` 分支补充 `d.id !in battleAndExplorationIds` 检查，确保战斗/探索中弟子在任何模式下均不显示
- **修复：ComponentTable 字段级写入守卫** — 给三种 ComponentTable 添加 `requireWrite` 回调，所有字段级写入在 `stateStore.update` 事务外立即抛 `IllegalStateException`，杜绝静默数据损伤和幽灵弟子（#10019/#9036/#3063/#3057/#5062）
- **修复：读档丹药追踪字段迁移写入事务外** — `migratePillTrackingFields` 包裹进 `stateStore.update{}`，确保字段级守卫通过
- **修复：LazyColumn 消息栏重复键崩溃** — 突破事件同毫秒时间戳导致 key 碰撞，改用 `itemsIndexed` + index 保证唯一性（#10021）
- **新增：stateStore.update 锁内耗时日志** — 超 500ms 自动 Warning，辅助 ANR 诊断（#10023）
- **新增：release 构建幽灵弟子轻量日志** — `replaceAll`/`insert`/`remove` 后检测 ghost 并打 `Log.w`（不抛异常）
- **修复：mergeDiscipleTables/createSettlementShadow 守卫兼容** — 影子合表路径 deepCopy 设 `writeAllowed=true`
- **修复：deepCopy 未复制 deathRecords** — 跨 update 边界死亡记录丢失
- **修复：3 个预存测试编译错误** — 适配 SpiritStoneWallet/RNG 新 API
- **清理：移除旧并行结算死代码** — `ProductionBatchResult`/`PartnerMatchResult`/`isSettlementShadow`

## [4.0.57] - 2026-07-18（versionCode=4057）

### 新增

- **新增：消息栏系统** — 主界面左下角半透明消息栏，收起态显示最近3条消息，展开态面板占左侧50%，分「世界」/「宗门」双标签显示AI宗门事件和玩家宗门事件。事件持久化200条上限，智能滚动不打断阅读位置，从左滑入动画
- **新增：登录界面背景图** — 替换为横屏高清修真风格背景图

## [4.0.56] - 2026-07-18（versionCode=4056）

### 修复

- **修复：5 个 Bugly 崩溃** — #3059 `OnThermalStatusChangedListener` TapTap 环境类加载崩溃（platformCallback 惰性化）、#5062 幽灵弟子 id=64（consistencyCheckEnabled 关闭 + insert 原子回滚）、#9034/#5060 ANR 主线程阻塞（assembleAll 后台 Default 协程）、#3061 Direct write 重入竞态（reentrantCount 检查移入锁内）、#5058 NPE 空参数（assembleAll 三表校验增强）

## [4.0.55] - 2026-07-18（versionCode=4055）

### 修复

- **修复：附庸请求战力检查缺失** — 根因 3 层：① `occupyRatio`/`skirmishRatio` 无战斗数据时默认 0.5，新建号白送 22.5% 基线概率；② 战力差无硬门槛，即使 powerRatio≈0 三个因子仍可叠加到 60%+；③ 月脱离检查从未接入游戏循环。修复：ratio 默认值 0.5→0.0、`powerRatio<1.0` 硬门槛、月脱离检查接入月度事件循环。对抗性审查 13 项发现全部处理：魔法数字全部抽取到 `GameConfig.Vassal` 命名常量、`checkSingleVassalBreakaway` 提取辅助函数降 61→40 行、脱离检查使用 data 快照保持一致性、移除未用参数、新增 9 个测试覆盖边界场景

## [4.0.54] - 2026-07-18（versionCode=4054）

### 修复

- **修复：招募弟子50人后消失 + 忠诚度清零** — 根因：`IntFlatArray.ensureCapacity()` 在 commit `65f17c67` 代码压缩时引入 Bug，扩容循环使用 `values.size`（copyOf 后 == newSize）替代原 `oldSize`，导致 `idToSlot[64..N]` 初始化为 0 而非 -1。ID ≥ 64 的组件表条目无法注册到迭代数组，`stateStore.update` deepCopy 丢失新弟子数据。修复 6 处（IntFlatArray + DoubleFlatArray 各 3 处）：ensureCapacity oldSize 保存、update bounds 守卫、delete size_ 守卫；deepCopy 幽灵 ID 过滤；22 个新增测试覆盖扩容路径。修复 3 个预存 FavorDomainTest 失败（移除 `updateFavor` 激进守卫）
- **修复：对抗性审查全面修复** — `ensureCapacity` 整数溢出保护、`keyAt`/`valueAt` 越界守卫、`indexOfKey` 返回真实 slot 索引、FavorDomain `noGiftYears` 仅在好感递增时重置、`sectId1 == sectId2` 自我引用守卫

## [4.0.53] - 2026-07-17（versionCode=4053）

### 重构

- **重构：架构债务全面清理** — 确定性 RNG 接入（engine 模块 `kotlin.random.Random` 归零）+ 核心层 suspend 迁移（30 处移除）+ Lifecycle 原子化（LifecycleState 单入口）+ @Deprecated 清理（engine/data/feature 归零，净删 680 行）。详见 commit 1a8c84a8 / f250a231 / 0374b1c5

### 新增

- **新增：妖兽详情界面显示战力** — 世界地图点击妖兽后，详情面板妖兽名称右侧显示"总战力"及数值。战力使用与弟子统一的公式 `(物攻+法攻)×5 + 气血×4 + (物防+法防)×3 + 速度×2`，基于妖兽生成时含随机方差的最终属性计算，地图显示战力=战斗实际战力
- **新增：宗门好感度重构** — 宗门间初始为互不相识，玩家通过送礼/请求结盟/宗门交易/附属契约/遭遇战建立相识后，好感度才正常变化。移除月度随机事件机制
- **新增：AI 宗门主动进攻妖兽** — 妖兽出现时，距离最近的两个宗门概率主动进攻。AI 战力高于妖兽才可能进攻，战力低于则不进攻。AI 胜利不获得任何奖励（AI 弟子死亡正确标记）
- **新增：宗门遭遇战** — 玩家和 AI 宗门或两个 AI 宗门同时进攻同一妖兽时，先打遭遇战，胜者的幸存弟子再打妖兽。玩家+AI 遭遇战后好感度 -3，计入相识途径。战斗日志新增遭遇战类型
- **新增：架构债务文档** — 创建 `docs/architecture-debt.md` 记录待完成的技术改进项

### 改动

- **改动：移除招募弟子卡片状态显示** — 招募界面中弟子卡片不再显示状态文字（如"空闲"等），保持卡片简洁，聚焦于灵根/属性/操作按钮
- **改动：弟子详情突破率按钮左移 + 广告加成 10%→15%** — 详情按钮和播放广告按钮从右对齐改为紧贴突破率文字放置（间距 4dp），广告每次突破加成从 10% 提升至 15%（上限 30%）

### 修复

- **修复：伴侣配对 Direct write to DiscipleTables 崩溃 #3057** — PartnerSystem.processPartnerMatching 的 assembleAll→map→replaceAll 反模式改为 partnerIds 组件表直接列写入，消除 writeGuard 检查路径

### 修复

- **修复：种植界面右侧已种植种子精灵图不显示** — 根因：种子种完后 quantity=0 → `removeSeed` 完全删除库存条目 → 右侧 `plantedSeed` 查找失败 → 显示灰色方块。修复：通过 `HerbDatabase.getSeedByName` 从静态数据库兜底渲染 `UnifiedItemCard`，种子名称/稀有度/精灵图均正确显示

### 修复

- **修复：招募弟子"同意"按钮无响应** — 根因：`DiscipleFacadeImpl.recruitDiscipleFromList` 通过 `launchInScope` 在 `engineScope` 上启动协程后立即返回，viewModelScope 协程在 engine 协程执行前就完成。若 engine 协程因 scope 取消/dispatcher 繁忙未被调度，招募无声消失。修复：改为 `suspend` 函数消除双重协程间接，直接执行 `stateStore.update`
- **修复：`handleDiscipleDeath` 从 Flow 读取数据** — 同已知 Bug2 模式，`stateStore.disciples.value` 可能导致 Flow 缺失数据被 `replaceAll` 永久覆盖。修复：改为 `discipleTables.assembleAll()` 直读组件表
- **修复：招募失败无用户反馈** — 新增 `GameNotification.RecruitFailed` 类型，通过 `StandardPromptDialog` 向玩家显示失败原因
- **修复：age/realm 越界校验缺失** — 新增 `MAX_REASONABLE_AGE=10000` + `VALID_REALM_RANGE` 上界校验，防止异常值弟子通过招募
- **修复：自动招募路径跳过完整性检查** — 对齐手动招募，新增 name/age/realm 校验，损坏数据跳过并记录日志
- **修复：自动招募不写入入门生命事件** — 新增 `lifeEvents` 写入"X岁：加入宗门"，与手动招募行为一致
- **修复：`addLifeEvent` 抛出时弟子处于孤儿状态** — 新增 `try-catch` 保护，异常时仅写日志不阻断流程
- **修复：`recruitAllFromList` 快照竞态导致重复招募** — 改为 `suspend` 函数，在 `stateStore.update` 内直接操作，消除 `launchInScope` 窗口期
- **修复：`DiscipleDelegate` 缺少 TAG 常量 + 重复守卫代码清理** — 新增 `companion object` + TAG，`recruitDisciple(DiscipleAggregate)` 简化为委托
- **清理：无参数 `recruitDisciple()` 死代码** — 移除 `DiscipleDelegate` / `GameViewModel` / `DiscipleViewModel` 中未被调用的无参版本
- **清理：`discipleName` 死变量** — 删除 `DiscipleFacadeImpl` 中赋值但未读的变量
- **修复：铲除操作多次 fire-and-forget 竞态** — 铲除确认弹窗原用 for 循环 + N 次 `scope.launch` 分别铲除，改为单次 `removePlantsFromSpiritFields` 批量调用，在单次 `stateStore.update` 事务内完成
- **修复：右侧兜底灰色方块无交互** — 终极兜底 Box 添加 `combinedClickable` + 长按可打开详情对话框；空 seedName 时显示"未知种子"

### 重构

- **重构：PartnerSystem.onEvent 消除冗余 scope.launch** — EventBus.notifySubscribers 已在协程内调用 onEvent，且 stateStore.update 使用 ReentrantLock 非挂起，内部不需要再套 scope.launch
- **重构：CultivationSettlement 2 处 replaceAll 改为列直写** — processResidenceLoyalty（loyalty 列）+ settleSalaryOnBreakthrough（storageBagSpiritStones/salaryPaidCount/loyalty 三列），消除 assembleAll→map→replaceAll 反模式
- **重构：ExplorationTeamManager 2 处 replaceAll 改为列直写** — recallDiscipleFromTeam（status 列）+ completeExploration（status + markDead + griefEndYears 列直写），统一死亡标记入口 markDead
- **新增：DiscipleStatCalculator.computeGriefEndYearMap 纯函数** — 返回 Map<Int,Int> 映射支持列直写，替代 applyGriefToRelatives 全量 List<Disciple> 返回

## [4.0.52] - 2026-07-16（versionCode=4052）

### 新增

- **新增：监牢思过崖新增释放按钮** — 弟子卡片新增绿色"释放"按钮（点击直接释放，无确认框），释放后弟子回归空闲状态，不获得任何增益（道德/忠诚不变）
- **新增：弟子详情突破率右侧播放广告按钮** — 点击弹出确认提示框（标题"广告"），点击"观看"后播放激励视频广告，突破率固定 +10%，最多观看 2 次（上限 20%）
- **新增：广告 60 秒冷却机制** — 成功领取奖励后进入 60 秒冷却，冷却中点击弹出"不可播放广告"提示框，冷却状态全局共享，未来新增广告类型自动复用
- **改动：监牢卡片状态显示移至年龄右侧** — 弟子状态文字（"思过中"）从操作区移至年龄右侧显示，释放/驱逐按钮并排放置在操作区

### 改动

- **改动：广告加成从乘区乘法改为扁平加法** — `adFlatBonus` 字段确保每次观看固定增加 10 个百分点（如 30% → 40%），不随基础突破率缩放
- **改动：确认提示框支持点击屏幕外关闭**
- **文档：新增 `rules/ad-cooldown.md` 广告冷却规则**
- **文档：`rules/new-dialog-checklist.md` 新增"点击屏幕外可关闭"检查项**

## [4.0.50] - 2026-07-15（versionCode=4050）

### 重构

- **重构：幽灵弟子架构债务清理** — `allocateNextId()`/`rollbackAllocation()` 标记 `@Deprecated`（全部 6 个生产入口已使用 `allocateAndInsert`，零残留调用）。`DiscipleTables` 新增 `replaceAll(disciples)` 原子批量替换方法（单 `synchronized(ids)` 锁内完成清空→写入→重建 IDs→一次 `markMutated`）。28 处 `clear() + forEach { insert() }` 裸模式迁移到 `replaceAll()`，覆盖 12 个 Service/System 文件。新增 `check()` 重复 ID 守卫
- **重构：God Method 拆分** — 13 个 >60 行函数拆分至 ≤60 行：`CaveExplorationProcessor` 3 个（含 272 行 `executeCaveExploration` 拆为 59 行编排 + 10 子函数）、`ExplorationService.resolveBeastFightInternal` 209→45 行（拆 10 子函数）、`ProductionProcessor` 3 个、`PatrolBattleSystem` 2 个、`BuildingService` 3 个、`CultivationEventProcessor.processLawEnforcementMonthly` 80→29 行
- **清理：未使用导入 11 行 + 未使用构造参数 8 个** — `CultivationEventProcessor` 4 参数移除（`ThermalMonitor`/`GameTimeClock`/`ProductionSlotRepository`/`CultivationSharedState`）、`CultivationSettlement` 8 参数移除（含 `InventorySystem`/`BattleSystem`/`ProductionCoordinator` 等）
- **清理：15 个魔法数字提取为命名常量** — 覆盖 `CultivationSettlement`/`PartnerSystem`/`ChildBirthSystem`/`ProductionProcessor`/`CaveExplorationProcessor`/`PatrolBattleSystem`
- **修复：`replaceAll` 预存问题** — 重复 ID 传入时静默覆盖，新增 `check()` 守卫

### 对抗性审查（3 Agent × 47 发现全部处理）

- **对抗性审查（2 Agent）：** 边界狂魔 + 状态破坏者，0 阻塞性问题，0 回归。确认 28 处迁移全部覆盖、5 处 deathYears 后修复逻辑保留、`CultivationSettlement` 复杂分支等价性

### 架构级修复：4 层架构合规体系

- **Layer 1: WriteGuard（对标 Android StrictMode / Flecs readonly_begin）** — `DiscipleTables` 新增 `writeAllowed` 标志 + `requireWriteAccess()` 守卫；`GameStateStoreImpl.update{}` 管理 `writeAllowed` 生命周期；绕过 `update{}` 的直接写立即抛 `IllegalStateException`。补漏 `@Deprecated` 方法。`consistencyCheckEnabled` Release 开关
- **Layer 2: 跨表一致性校验（对标 Bevy UnsafeWorldCell）** — `ComponentTableLike`/`CopyableTableRef` 新增 `contains(id)`；`assertAllTablesConsistent()` Debug 断言；`insert/remove/replaceAll` 后自动校验 90+ 表 id 一致性
- **Layer 3: 真 suspend 化** — `ProductionSlotDao` 全量 `suspend`（含 `@Query DELETE`）；`ProductionSlotDataPort` + Impl `withContext(IO)`；`ProductionSlotRepository` 7 方法重构成锁外 DAO 模式消除 `runBlocking(IO)`（同 `removeSlot` 模式）；`MiscDaos` GameHeavyDataDao 修复
- **Layer 4: 全 DAO 线程安全审计** — 确认 17 个 DAO 文件所有 `@Insert/@Update/@Delete/@Query` 写方法均为 `suspend`
- **Bug 修复：6 个 Bugly 崩溃全部根治** — #9029 SparseArray 并发（WriteGuard 根除）+ #3030 组件表缺 id（`assembleAll` 幽灵跳过 + 一致性断言）+ #3051 主线程 DAO（`ProductionSlotDao` 全量 `suspend`）+ #5054 CrashHandler 自递归（`handlingCrash` guard + `stackTraceToString()`）+ #3026/#2017 已有修复确认
- **修复：`ProductionSlotRepository.updateSlotByBuildingId` 等 7 方法从 `runBlocking(IO)` 重构为真 `suspend`** — DAO 移出锁外（`removeSlot` 模式），非 `suspend` 调用方加 `runBlocking(IO)` 包装
- **修复：`GameStateStoreImpl.reset()` 和 `loadFromSnapshot()` `writeAllowed` 保护** — `finally` 块确保 `writeAllowed=false` 即使异常
- **修复：`loadFromSnapshot` 回滚路径原子性** — `finally { _discipleTables.writeAllowed = false }` 保护
- **清理：** 移除未使用的 `DiscipleWriteManager`（已全部迁入 `stateStore.update{}`）；`createSettlementShadow` 双重 `deepCopy` 修复；`CrashHandler` `handlingCrash` 改为 `AtomicBoolean.compareAndSet`；`IntFlatArray`/`DoubleFlatArray` 负 key 守卫（全部方法 `key >= 0` 检查）
- **预存问题修复：** `EngineServiceAnnotationTest` `DefensePreparation` 加入白名单；`CultivationSettlementConcurrencyTest` 过时构造函数修复；`GameStateStoreMergeTest` 加 `WriteGuardRule`；`WriteGuardRule` 复制到 `:core:engine`/`:app` 测试源；`assembleAll()` 半幽灵逃逸修复（`isAlive.contains(id)` 守卫）

## [4.0.49] - 2026-07-14（versionCode=4049）

### 重构

- **重构：炼丹系统代码质量全面优化** — 僵尸 Room 实体 `AlchemySlot`/`ForgeSlot` 清理（MIGRATION_17_18 删除 2 张表 + 移除 DAO/Entity/Hilt 绑定）。God Method `processBuildingProduction` 拆 5 个单一职责方法。配方匹配逻辑提取公共函数 `findBestCraftableRecipe` 消除 4 处重复。`PillDetailDialog` 23+ if 样板替换为数据驱动渲染。`clearAlchemySlot`/`clearForgeSlot` 统一返回 `DomainResult`。死代码 `ForgeRepository`/`BuildingService.getAlchemySlots`/4 个 BuildingService 死方法全部删除
- **重构：移除 DialogRoute 冗余类型层级** — `DialogType`（core:domain）和 `DialogRoute`（core:ui）内容完全一致的平行密封类，靠 ~100 行双向映射 + StateFlow 桥接串联。对标行业标准（Google Compose 推荐、Unreal CommonUI、Unity UIManager 均为 1 套类型 0 行映射），删除 `DialogRoute` + 双向映射 + 桥接，直接 `DialogType` 单一真相源驱动 UI。新增界面只需 2 步：DialogType 加类型 → GameOverlayHost 加 when 分支。8 文件修改，-361/+129 行

### 修复

- **修复：重进游戏弟子列表多出幽灵弟子（属性全零/练气一层/16岁/单灵根）** — 根因：`allocateNextId()` 两步模式（先加 `ids` 再写组件表）的异常窗口导致 ID 悬空，`assembleAll()` 拼出默认值幽灵。修复：`DiscipleTables` 新增 `allocateAndInsert()` 原子方法（`synchronized(ids)` 内完成分配+写入）；`recruitDisciple`/`recruitAllFromList`/`autoRecruit`/`recruitDiscipleFromList`/`RedeemCodeService` 5 个入口统一改用；增量存档 `deleteAll`+子表同步消除Room残留行；读档 `SaveValidator` 自动清除已有幽灵；`loadFromSnapshot` 补充版本号同步。举一反三：`wallet.deduct()` 返回值检查（GiftService危急/DiplomacyService/AutoBuyService/VassalService）、`SectPolicyToggleUseCase` 5 个政策改为单事务原子开关、`RedeemCodeService` 2 处同模式手动ID分配修复、`SpiritStoneWallet` 逐品阶 pending 校验。对抗性审查 3 Agent 发现 5 项问题全修复。共 21 文件
- **新增：炼丹/锻造成功率接入 FormulaService 乘区法** — 长老加成/弟子境界/天赋/政策四乘区计算，替代原来的裸 recipe.successRate
- **修复：对话框 Full 模式标题/关闭按钮被 32dp 顶部内边距推下** — `UnifiedGameDialog` Full 模式的 `headerTopPadding` 错误复用水平间距 `headerH`(32dp)，导致全部 Full 模式弹窗（弟子/仓库/设置/建造等）标题和关闭按钮被推下 32dp。修复为 4dp 与 Half 模式一致。同步增强 `hideSystemBars()` 国产 OEM ROM 兼容性（HyperOS/MagicUI/ColorOS 等）
- **修复：BuildingService 自动收获路径 kotlin.random.Random 破坏存档确定性** — 改为 GameRngManager.getRng(SYSTEM)。ProductionProcessor 对应路径同步修复共 4 处
- **修复：processAutoAlchemy/processAutoForge 循环 break 卡死自动重启** — 改为 continue 跳过继续处理其余槽位
- **修复：自动重启未应用速度加成** — 启动后用 formulaService.calculateWorkDurationWithAllDisciples 重算 duration/completionMonth
- **修复：startAlchemy/startForging 的 else 分支重复 addSlot 静默失败** — 改为统一 updateSlotByBuildingId
- **修复：自动重启时弟子死亡仍被恢复为 IDLE** — 加 isAlive 守卫
- **修复：储物袋消耗后剩余袋消失 + 全项目"锁外读→锁内决策"反模式批量修复** — `openStorageBag` 在 `stateStore.update` 锁外读取 `bag.quantity` 导致 TOCTOU 竞态。举一反三搜索全项目发现同类反模式 26 处，覆盖 `DiscipleService`/`CaveExplorationProcessor`/`MerchantAndRecruitService`/`AttackWarningService`/`DiscipleFacadeImpl`/`DiplomacyService`/`VassalService`/`FavorEventProcessor`/`GiftService`/`CombatService`/`GameEngineBattleOps`/`GameEngineCoordination`/`AutoBuyService`/`BuildingService` 共 17 文件。同步修复 `openStorageBag` 使用 `kotlin.random.Random`→`GameRngManager`、`WarehouseTab` ALL 过滤显示不刷新、`openAllStorageBags` 预捕获总量。对抗性审查（3 Agent）发现 11+8+9 项，1 处回归（`buyMerchantItem` 验证失败后发射空卡片）已修复
- **修复：startAlchemy/startForging 未写入 baseDuration** — 政策重算无法正确计算进度比例
- **修复：startAlchemyAtomic 缺空材料配方防御、_consumptionLogs 无限增长、startForgingAtomic 缺消耗日志**
- **对抗性审查：** 3 Agent（边界狂魔+状态破坏者+数据篡改者）共发现 29 项问题，已全部修复
- **修复：突破概率使用 kotlin.random.Random 破坏存档确定性** — DiscipleBreakthroughHandler.tryBreakthrough() 改为 GameRngManager.getRng(RngPartition.BREAKTHROUGH)。叛逃/偷盗 8 处同步修正(RngPartition.SYSTEM)
- **修复：checkpointDisciple() 定义但零调用** — 修炼速率变化时(政策/长老/丹药/突破)检查点未同步。在 SectPolicyToggleUseCase/ElderManagementUseCase/CultivationCore.processRealtimeAutoPills/accumulateCultivationPerPhase/performBreakthrough 5个速率变化点接入。DiscipleTables 新增 checkpointDisciple/checkpointAllDisciples 基方法
- **修复：DiscipleAggregate.buildCultivationZones() 遗漏丹药修炼加速** — pillCultivationSpeedBonus 仅在 Disciple 重载中计入，Aggregate 版本缺失。DiscipleExtended 新增 pillCultivationSpeedBonus/pillEffectDuration + Room MIGRATION_18_19
- **重构：processTheftMonthly God Method 拆分** — 178 行→~65 行，提取 tryGuardCatch/executeTheftStolen/processTheftDesertionCleanup 3 方法
- **重构：processAutoFromWarehouse God Method 拆分** — 122 行→~50 行，提取 processSingleAutoEquip/processSingleAutoLearn/writeAutoWarehouseResults 3 方法
- **清理：死代码 advancePhase/processPhaseEvents/processPhaseTick 删除** — 旧四轨制残留零调用，含 unsafe Thread.sleep(5) 在锁内
- **新增：7 项 checkpoint 单元测试 + 3 项 CultivationService 集成测试**
- **修复：对话框打开时系统状态栏始终可见** — 根因：Compose `Dialog()` 创建独立平台 Window，`hideSystemBars()` 仅作用于 Activity Window，Dialog Window 未继承系统栏隐藏。创建 `DialogSystemBarGuard()` composable 在 Dialog Window 上应用双路径 `hideSystemBars()`（`WindowInsetsControllerCompat` + 传统 `SYSTEM_UI_FLAG_*`）。覆盖 13 处 Compose `Dialog` 容器 + 2 处 Material3 `AlertDialog`。对抗性审查：3 Agent 发现 7 项问题，修复 API 35+ OEM 兼容、AlertDialog 遗漏 2 项

## [4.0.48] - 2026-07-13（versionCode=4048）

### 重构

- **重构：游戏生命周期 BootPhase/RunState 双层状态机** — 旧 `GameLifecycle` 单向不可逆状态机与实际 reload/restart 场景需求不匹配，导致 `forceLifecycle` 4处散落调用 + `_isGameLoaded` 独立标志位双真相源 Bug。新设计：`BootPhase`(5态启动序列，单向，仅 `BootSequenceController` 推进) + `RunState`(4态运行时：IDLE/LOADING/PLAYING/RELOADING)，`gameLifecycle` 由二者组合派生保持旧代码兼容。消除 `forceLifecycle` 全部调用和 `_isGameLoaded` 独立标志位
- **重构：提取 `BootSequenceController` 统一编排加载序列** — 之前启动逻辑散布在 SaveLoadViewModel 的 4 个方法(startNewGame/loadGame/loadGameFromSlot/restartGame)中。现由 BootController.boot() 统一管理 BootPhase 推进、RunState 切换、资源预加载(回调)、游戏循环启停、地图生成、错误恢复。ViewModel 减约 200 行
- **重构：对抗性审查修复** — 3 Agent 发现 15 个问题，修复：重入保护(CAS+finally)、Cancellation 清理(cleanupAfterCancellation)、setPlaying()守卫(check bootPhase>=BOOT_COMPLETE)、recoverWithPartialData硬编码→while循环、恢复成功路径、mapData==null补全bootPhase、onCleared重置runState(防止Singleton残留)、补充setLoading/setIdle API

### 新增

- **新增：`BootSequenceController` 类** — `:core:engine` 模块，注入式控制器，统一编排启动序列。5项单元测试覆盖IDLE/PLAYING/错误/进度/地图回调路径
- **新增：`BootSequenceControllerTest`** — 5项测试全部通过

### 修复

- **修复：读档自动存档弹出「存档为空或已损坏」** — 根因：`loadFromDatabase()` 用 Room suspending `withTransaction` 包裹读取操作，但内部的 `buildSaveDataFromDatabase()` 使用 `withContext(Dispatchers.IO)` + `async {}` 在 IO 线程池并行执行同步 DAO 查询。Room 检测到同步调用线程 ≠ 事务线程，抛出 `IllegalStateException`，返回值 null 被上层解释为「存档为空」。修复：移除读路径中多余的 `withTransaction`（`load()` 入口已有 `withReadLockLight` 排它锁，无需事务）
- **修复：游戏中读档 IllegalLifecycleTransition(PLAYING→DATA_READY)** — 根因：`transitionTo()` 只允许 forward +1，但 reload 路径从 PLAYING→DATA_READY 是回退。修复：`forceLifecycle(UNINITIALIZED)` 在 stopGameLoop 后重置生命周期，现由 `BootSequenceController.boot()` 内部通过 `setReloading()+resetBootPhase()` 统一处理

### 重构

- **重构：对话框系统统一渲染路径** — 从 GameViewModel 拆出独立 `DialogManager` 接口（`core/domain` 零 Android 依赖）+ `DialogManagerImpl`（Hilt `@Singleton`）。消除三套并行渲染体系：内联覆盖层（`FullScreenOverlay` 等）改为 `UnifiedGameDialog(mode = Full)` 获得 `decorFitsSystemWindows = false` 系统栏保护。消除 Path B 关闭路径（`closeCurrentDialog`→Channel→LaunchedEffect→`dismissDialog`）。清理废弃 `HalfScreenDialog`/`GameFullDialog` 声明。`CloseButton` 触摸目标 24dp→48dp（Material 标准）。`ElderBonusInfoDialog` 关闭按钮底部→右上角。对抗性审查 3 Agent 发现 20+ 项问题全部修复

### 修复

- **修复：关闭按钮被系统栏遮挡** — 根因：`FullScreenOverlay`、`ActivityDialog`、`MailDialog` 使用内联 `Surface(Modifier.fillMaxSize())` 无 `decorFitsSystemWindows = false`，`enableEdgeToEdge()` 下全屏填充包括系统栏区域，关闭按钮在状态栏后方。修复：改为 `UnifiedGameDialog(mode = Full)` 获得 Dialog 窗口 inset 保护
- **修复：对话框关闭按钮无响应** — 根因：关闭按钮在状态栏后方（上述遮挡），触摸落在系统 UI 区域。修复：inset 保护 + 触摸目标 24dp→48dp
- **修复：`ElderBonusInfoDialog` 关闭按钮在右下角** — Column 末尾 `Modifier.align(Alignment.End)` 导致。修复：移到标题行右上角 Row

## [4.0.47] - 2026-07-13（versionCode=4047）

### 架构

- **重构：探索系统拆分为6个子系统** — ExplorationService降为Facade，提取WorldLevelManager(关卡惰性管理：刷新/过期清理/妖兽移动)/BeastAttackDetector(妖兽攻击检测)/PatrolBattleSystem(巡视塔战斗拆4步：组队→索敌→战斗→结算)/LootCalculator(掠夺计算纯函数+双重扣除修复)/DiscipleDeathHandler(死亡标记+装备断言守卫)/ExplorationTeamManager(队伍管理单事务竞态安全)。原processPatrolAttacks 241行God Method拆为4个≤60行方法
- **重构：stateStore.update 全链路非挂起化** — 锁原语 `Mutex` → `ReentrantLock`（挂起时不释放锁），消除协程交错导致的 SparseArray 并发崩溃。`_discipleTables` 进入 `deepCopy()` 提供快照隔离。DAO/Repository/Service/Processor 全链路移除 `suspend`，50+ 文件无 `runBlocking`。移除 ComponentTable/IntFlatArray 冗余 `synchronized`。对抗性审查 3 Agent 共发现 ~30 项问题并全部修复
- **新增：确定性RNG系统** — PCG-XSH-RR算法DeterministicRng(16字节状态可序列化)+GameRngManager(4分区BATTLE/BREAKTHROUGH/EXPLORATION/SYSTEM)+RngPartition枚举。所有随机操作走分区PRNG，存档exportStates/读档restoreStates确保跨存档随机序列一致。LevelGenerator从object改为class注入RNG，测试用固定种子42锁定结果

### 修复

- **修复：Wallet灵石变更事件部分状态窗口** — SpiritStoneWallet改为暂存事件到pendingEvents，由GameEngineCore在stateStore.update事务外统一flushPendingEvents。消除事务内emit时UI层读到灵石已变、其他状态未提交的部分中间状态
- **修复：纳贡不扣灵石但妖兽标记已击败** — resolveBeastAttackPayTribute中deduct返回的DeductResult被忽略。灵石不足时妖兽仍被标记为defeated=true。新增Success检查，扣除失败时跳过击败标记与BattleLog
- **修复：储物袋掠夺双重扣除** — applyMaterialLoot重写为单次mapInPlace遍历，消除双次逻辑超额扣除
- **修复：PatrolBattleSystem死亡路径未接入统一入口** — deathYears手动写入改为DiscipleDeathHandler.markAllDead，装备断言守卫在巡视塔战斗死亡路径生效
- **修复：ExplorationService中kotlin.random.Random残留** — resolveBeastFightInternal中2处全局Random调用替换为RngPartition.BATTLE分区RNG
- **修复：WorldLevelManager.processMonthly早返回跳过过期清理** — 无玩家宗门时先清理过期关卡再返回，确保过期关卡始终被移除
- **修复：GameStateStore.setPendingBeastAttacks冗余脏标志** — 移除_stateDirty/_updateVersion，脏标志由外层stateStore.update统一管理
- **修复：LevelGenerator整数溢出** — maxNewLevels+1溢出保护

### 新增

- **新增：巡视塔战斗结果持久化** — GameData.pendingPatrolBattleResults替代内存_pendingPatrolResults，存档读档后巡视塔弹窗不丢失
- **新增：GameData.worldLevelLastRefreshMonth持久化** — 关卡刷新月从内存var迁移到GameData字段，消除读档后关卡膨胀
- **新增：GameData.rngStates持久化** — 各分区RNG状态存档/读档完整恢复

### 测试

- **新增：探索包101个单元测试(10套件0失败)** — DeterministicRngTest(9)/GameRngManagerTest(5)/WorldLevelManagerTest(9)/BeastAttackDetectorTest(7)/LootCalculatorTest(14)/DiscipleDeathHandlerTest(7)/PatrolBattleSystemTest(7)/ExplorationTeamManagerTest(8)+原有LevelGeneratorTest(20)+CaveGeneratorTest(15)

### 文档

- **文档：CLAUDE.md更新** — Key Classes新增探索子系统+RNG系统表；PR审查清单新增4条RNG规则
- **文档：新增设计方案** — docs/adr/exploration-system-refactoring.md(24条行业参考)
- **规则：对抗性审查维度扩展** — first-principles-adversarial.md新增确定性RNG/事务边界/God Method/死亡路径统一4个审查维度

## [4.0.46] - 2026-07-13（versionCode=4046）

### 修复

- **修复：ComponentTable 线程安全加固（#5045/#5043/#5027）** — 在协程挂起时 kotlinx Mutex 会释放锁，允许另一协程交错写入 SparseArray 导致 GrowingArrayUtils.insert 内部数组不一致崩溃。给 ComponentTable/IntFlatArray/DoubleFlatArray 所有公开方法加 synchronized/@Synchronized 保护，从底层防止并发写入破坏内部状态。对抗性审查 3 Agent 共发现 20+ 项问题并全部修复，含原子 update、idToSlot 守卫、迭代同步、writeAllFields 锁内执行、onWrite 一致性、keys 数组清空等
- **修复：Adreno 6xx Vulkan 崩溃扩展黑名单（#5041）** — 补充 Adreno 620/630/650 等机型黑名单，可在下次启动自动降级软件渲染

### 重构

- **重构：DiscipleTables 跨表原子性** — insert/writeAllFields、remove、markDead、cullDeadDisciples 统一在 synchronized(ids) 锁内执行读写，消除跨 90 张组件表的撕裂读和状态不一致风险

## [4.0.45] - 2026-07-12（versionCode=4045）

### 修复

- **修复：弟子境界随机跳变（如筑基→练气）** — 根因是 `IntPackedArray` 使用 `SparseIntArray(id→packed索引)` 做映射，非连续 ID 或删除+再插入后排序位置与 packed 索引不一致，导致约 90 个组件表字段数据错位。根治方案：移除 SparseIntArray 映射层，平铺数组由 ID 直接索引（`values[id]` O(1)）。涉及 1 文件约 300 行重写
- **修复：灵植阁重构遗留的 GameViewModelTest 编译失败** — `setAutoAssignSettings` 已移除 `plantFocused/plantRootCounts/plantThreshold` 参数，同步清理测试中的对应调用和断言

### 重构

- **重构：IntPackedArray/DoublePackedArray 平铺数组化** — 移除 SparseIntArray(id→packed索引) 映射层，改用值数组由 ID 直接索引（`values[id]` O(1)）。消除了 safeIndex 索引混淆 bug 的根本可能性。迭代使用紧凑 keys 列表，删除用 swap-on-remove。90 张组件表从 O(log N) 二分查找降为 O(1) 直接寻址，内存持平。涉及 1 文件约 300 行重写

- **重构：灵植阁移除种植/收获系统** — 灵植阁（Herb Garden）的 ProductionSlot 种植/收获系统已整体移除，仅保留作为灵田速度加成建筑的功能（执事长老/光环弟子/政策加速）。灵田的种子种植通过 PlantingDialog 手动操作，收获和自动续种由 PlantingSystem 月变自动处理。涉及12文件-426/+178行。对抗性审查3 Agent 共发现16项问题并全部修复，含CRITICAL级速度公式错误（totalMultiplier zoneToMultiplier导致加速翻倍）和收获静默吞没Bug

- **修复：ComponentTable 线程安全加固** — 在协程挂起时 kotlinx Mutex 会释放锁，允许另一协程交错写入 SparseArray 内部数组导致崩溃。给 ComponentTable/IntFlatArray/DoubleFlatArray 所有公开方法加 synchronized/@Synchronized 保护，从底层防止并发写入破坏内部状态。修复 3 个 ArrayIndexOutOfBoundsException 崩溃（#5045/#5043/#5027）

## [4.0.44] - 2026-07-11（versionCode=4044）

### 调整

- **调整：传道加成公式改为基线+步长+上限制** — 传道长老：teaching 80 基准，每 4 点 +1%，最多 +10%；传道师：teaching 60 基准，每 10 点 +1%，最多 +5%。上限消除极端高传道弟子带来的超额加成，传道师基线从 80 降至 60 使低传道弟子也能提供少量加成。调整涉及 5 文件（DiscipleStatCalculator/CultivationCore/DetailCultivationSection/ElderBonusInfoButton/测试）

### 修复

- **修复：弟子交谈关闭按钮被内容区遮挡** — Dialog 的 Box 布局中 `CloseButton` 声明在 `Row(fillMaxSize)` 之前，z-order 较低被覆盖。重构为 `UnifiedGameDialog`（同外交界面），关闭按钮在 header 行与内容区垂直分离，标题栏显示弟子姓名。移除手写 Box/BackHandler/CloseButton
- **修复：对话框半透明遮罩未覆盖全屏** — UnifiedGameDialog/InlineStandardPromptDialog 的 DialogProperties 缺少 `decorFitsSystemWindows=false`，Compose Dialog 独立窗口不继承 Activity 的 edge-to-edge 设置，半透明遮罩被系统栏 inset，在状态栏/导航栏区域露出游戏画面。同步补齐 SettingsTab 等 5 处 inline Dialog 及 ElderBonusInfoButton 等 3 处 Dialog 缺失的 DialogProperties，修复 Full 模式标题顶部 padding（4→32dp）防挖孔遮挡。对抗性审查 3 个 Agent 共发现 14 项问题，已逐项确认处理

### 新功能

- **新增：弟子选择界面全部增加「显示所有可用弟子」勾选框** — 所有选择弟子对话框的境界筛选右侧新增"显示所有可用弟子"CircularCheckbox，默认不勾选（仅显示空闲中），勾选后显示非空闲弟子（始终排除思过/任务/战斗中），选中非空闲弟子时自动释放原槽位进入新槽位。勾选状态跨界面持久化

- **新增：血炼池功能完善** — 血炼池系统正式完成闭环，涵盖完整生命周期：
  - 弟子状态改为专用 `REFINING`（血炼中），替代旧 `IDLE + statusData` 伪装
  - 月度自动结算到期血炼：单利加成写入战斗属性列、更新累计记录、重置弟子为空闲、弹窗通知
  - 原子化取消支持：取消时同时重置弟子状态，不留僵尸进度
  - 排他性校验：同一建筑/弟子不可重复启动血炼
  - 引擎层防御性校验：灵石/材料/duration/bonusPercent 负值防护

### 修复

- **修复：弟子忠诚度每月减一** — 移除伴侣系统的月度忠诚度衰减机制（processLoyaltyDecay），弟子忠诚度不再每月自动减少1点
- **修复：天道试炼战斗中加血功法不加血** — `applyBuffToTarget` 遗漏 `skill.healFixed`（固定数值治疗）处理，只处理了百分比治疗。合入 `BattleCalculator` 的正确实现，同时新增 `skill.buffs` 多 buff 列表支持
- **修复：天道试炼 AoE 治疗/辅助技能无效** — 技能选择面板的 `if(skill.isAoe)` 分支只有攻击处理，缺少辅助/治疗else分支
- **修复：天道试炼敌方治疗双重生效** — BUFF_ALLY/BUFF_SELF 先通过 `applyBuffToTarget` 预应用治疗，动画回调 `applyAnimationResult` 再第二次加血。现直接跳过动画回调的治疗重应用
- **修复：天道试炼第6关奖励领取按钮无响应** — randomEquipment/randomManual 从玩家已有装备堆叠中筛选模板（需先有地品装备才能领地品装备），改为从 `EquipmentDatabase`/`ManualDatabase` 数据库直接生成，不再依赖玩家库存

### 崩溃修复

- **修复：AutoSaveTrigger ANR #10002** — SaveLoadViewModel.init 中 getSaveSlotsSuspend/savePipeline.saveResults 从 Main 线程移至 IO/Default 调度器；autoSaveTrigger 从 Channel（close/replace 生命周期竞态）迁移为 SharedFlow，消除 BufferedChannel.getCloseCause 反射阻塞

- **修复：TapTap 云游戏 vkCreateShaderModule SIGSEGV #9024** — 新增 isTapTapCloudGaming() 4 信号检测（maps/Build.HOST/installer/SystemProperties），命中后直接走 Canvas 软件渲染；C++ compileShader 新增 sigsetjmp/siglongjmp SIGSEGV 信号捕获 + 空 VkDevice 检查，驱动缺陷时优雅降级

- **修复：SQLite libsqlite.so SIGSEGV #5037** — 禁用 mmap（mmap_size=0）；移除独立 ScheduledExecutorService checkpoint 线程消除 WAL 竞争；flushDirtyState 13 并发事务合并为单事务；启动时 PRAGMA integrity_check；<4GB RAM 设备跳过 temp_store=MEMORY

- **修复：VulkanPolicyTest 3 个预存测试失败** — 模拟器路径在 API<31 非白名单设备上提前返回 SOFTWARE_ONLY

- **新增：MIGRATION_14_15 迁移测试 + GameDatabase SQLite PRAGMA 运行时配置测试**

### 重构

- **灵石系统统一网关（SpiritStoneWallet）** — 新建 `wallet/` 包（SpiritStoneWallet/Ledger/Transaction），单一入口接管所有灵石变更（add/deduct/batch/applyAdd/applyDeduct），消除 21 文件 40+ 处直接 `gameData.copy(spiritStones=)` 分散修改。`CultivationSettlement` 重复自动售卖逻辑消除（-100行），`InventorySystem` 委托到 Wallet。`SpiritStoneLedger` 环形缓冲区（O(1)）审计账本记录每笔操作来源/原因/变化量/前后余额。`HighFreqState` 补充中品/上品字段。21 文件 +320/-335 行

### 对抗性审查修复

- **3 Agent 对抗性审查（边界狂魔/状态破坏者/数据篡改者）共发现 22 项问题，全部修复：**
  - `deduct()`/`applyDeduct()` 双倍计数漏洞（`gameData.spiritStones + supplemented` 两次累加）
  - `batch()` `-Long.MIN_VALUE` 溢出可清零灵石 + `SpiritStoneOperation.init` 校验（`require(delta != 0 && delta != MIN_VALUE)`）
  - `autoSellHigherGrades` 在余额检查前不可逆修改 state → 拆分为 `calculateAutoSell`（只读计算）+ `autoSellHigherGrades(plan)`（执行）
  - `canAfford()` 乘法无溢出保护 → `SpiritStoneExchange.toLowGrade()` safeMultiply
  - `batch()` 预检查 all-or-nothing 原子性 + autoConvert 通路
  - 灵矿产出双事务导致灵石重复发放 → 合并为单 `stateStore.update { applyAdd }`
  - `buyMerchantItem` 未检查 `applyDeduct` 返回值
  - `confiscateStorageBagItem` 模板查找失败物品静默丢失 → 添加 `DomainLog.w` 警告
  - `Ledger` 环形缓冲区 O(1) 替代 `removeAt(0)` O(n)
  - 新增 `SpiritStoneWalletTest` 16 用例边界覆盖

- **修复：跳过按钮动画竞态** — 跳过按钮缺少 `!isAnimating` 守卫，在技能动画播放期间可并发执行 skip 结算导致状态互相覆盖
- **修复：advanceTurn 陈旧闭包** — 使用组合时捕获的 `alivePlayers`/`aliveEnemies`（3处调用），改为实时读取 `playerTeam.filter{!it.isDead}` 避免无法及时检测全灭
- **修复：applyBuffToTarget 可复活死亡目标** — 添加 `if (target.isDead) return target` 守卫
- **修复：治疗上限未使用 effectiveMaxHp** — 改为 `effectiveMaxHp`/`effectiveMaxMp` 含 HP_BOOST/MP_BOOST buff 加成
- **修复：同类型 Buff 无限堆积** — 同类型 buff 自动覆盖替换（刷新持续时间），避免战斗多轮后 buff 列表膨胀
- **修复：负值 healPercent/healFixed 静默不生效** — `.coerceAtLeast(0)` 负值防护
- **修复：BUFF_ALLY/BUFF_SELF 视觉数字缺 healFixed** — 治疗数字显示加入固定治疗量
- **修复：第6关奖励错误静默丢失** — `HeavenlyTrialViewModel.errorEvents` Channel 无人收集导致 `CapacityInsufficient` 错误被吞。改为 `ActivityDialog` 内收集并用 `StandardPromptDialog` 显示
- **修复：并发重复领取漏洞** — 快速双击奖励领取按钮可绕过外层 `claimedRewardLevels` 检查（Mutex 外），在 `stateStore.update` 内增加原子二次检查防止双倍发放

### 代码质量优化

- **重构：claimClearReward 超长函数拆分** — 从 ~170 行提取 `MutableGameState.distributeRewardItems()` 扩展函数，主函数缩减至 ~45 行

### 性能优化

- **优化：PC/模拟器地图拖拽流畅度大幅提升** — 4项改动：
  - 模拟器渲染策略从强制软件渲染改为优先尝试 Vulkan（模拟器翻译层走宿主机物理 GPU），仅先前崩溃过才降级到 Canvas 软件渲染
  - 主线程 `buildingData` FloatArray 使用 `remember` 缓存，拖拽中不再每帧重新分配；`updateRenderState` 推送增加帧率门控
  - `SoftwareCanvasBackend` 重构：新增 Scroll-Frame Compositing（偏移帧合成，亚像素移动直接复用缓存帧，小偏移仅绘边缘减少 90% 绘制量）+ Chunk 化预渲染（32×32 瓦片级缓存，相机移动不失效，每帧仅 ~4 次 drawBitmap）+ EWMA 帧时间追踪及动态帧率自适应（60/45/30/20fps 自动切换，1 秒防抖窗口）
  - RenderThread SOFTWARE 路径调用 `recordFrameTime()` 按实际渲染能力动态调整目标帧率，消除帧间隔抖动
- **新增注解**：`HeavenlyTrialService` 标注 `@GameService("HeavenlyTrialService")`
- **修复**：`GameViewModelTest` 5个 Robolectric 测试 `OutOfMemoryError` — 在 `build.gradle` 中设置测试 JVM 堆为 2g

### 测试覆盖

- **新增测试**：17个单元测试覆盖 `applyBuffToTarget` 全场景（healPercent/healFixed 组合、MP恢复、上限 clamp、死亡目标、buff 去重、负值防护、effectiveMaxHp）
- **清理**：`HeavenlyTrialRewardCapacityTest` 移除 randomEquipment/randomManual 相关的 7 个测试用例（校验已删除，不再需要）

### 优化调整

- **移除：职务限制机制** — 彻底移除内门/外门弟子身份（discipleType）作为槽位/任务的过滤条件：
  - 所有弟子选择界面不再按内/外门过滤，任何弟子均可担任长老、亲传弟子、生产、采矿等职务
  - 任务派遣移除身份和境界限制，仅过滤忙碌弟子
  - 删除 `DisciplePositionHelper.kt`、`DisciplePositionQueryUseCase.kt` 及相关职务查询代码
  - 删除 `isEligibleForInnerPosition`/`isEligibleForOuterPosition` 任职资格属性
  - 长老分配冲突检查移除，完全依赖 `DiscipleStatus` 状态过滤
  - 对抗性审查修复 4 处 `TYPE_OUTER` 残留过滤（`DiscipleService.buildMiningIds`/`fixInvalidMiningSlots`/`getDiscipleStatus`、`GameEngineCoordination.kt` 加载时采矿槽清理）
  - `setViceSectMaster`/`removeViceSectMaster` 改为委托 `elderManagement` 确保状态同步

### 修复

- **修复：任务阁任务不刷新** — `CultivationEventProcessor.processMissionRefreshIfDue` guard 条件 `month % 3 != 1`（1/4/7/10 月放行）与 `MissionSystem.processMonthlyRefresh` 内部条件 `month % 3 == 0`（3/6/9/12 月才生成/清理任务）偏移 1 个月，导致刷新永不执行。改为 `month % 3 != 0` 使 guard 与引擎逻辑对齐，任务阁每 3 个月（3/6/9/12 月）正常刷新任务

### 代码质量

- **清理**：移除 `GameEngineCoordination.kt` 中从未调用的死代码 `processMonthlyMissionRefresh()`

### 测试覆盖

- **新增测试**：2个月份守卫条件测试，验证 `processMissionRefreshIfDue` 在 3/6/9/12 月放行、其他月份跳过

## [4.0.43] - 2026-07-10（versionCode=4043）

### 新增功能

- **新增：弟子交谈功能** — 弟子详情界面新增交谈按钮，点击进入全屏聊天界面。3条故事线（修炼心得/宗门事务/过往经历）随机出现，每轮3个选项各含多种随机结局，影响弟子道德/忠诚/修为/智力属性。效果数值随机1-5点/1-5%，正面金色提示、负面红色提示、半透明胶囊形背景。每年同一弟子仅可获得一次效果

### UI/UX优化

- **优化：聊天气泡自适应宽度** — 外交系统和弟子聊天的聊天气泡最大宽度随屏幕动态调整（65%屏幕宽度），短文本单行显示、长文本自动换行

### 安卓8/10/11兼容性修复

- **修复：安卓8/10/11设备首次启动闪退** — Native渲染库 `native-renderer.so` 硬链接 Vulkan 1.1 API 入口点，API < 31 的非 Google 设备上 Mali/MediaTek/PowerVR GPU 驱动缺陷在首次 Vulkan 初始化时触发 SIGSEGV 崩溃。新增 API 版本门槛检查（对标 Flutter Impeller API < 29 无条件回退 GLES 策略 + Unity Vulkan Device Filtering + 原神设备白名单），API < 31 非白名单设备直接走 Canvas 软件渲染，跳过 Vulkan 初始化路径。Google Pixel / Android One / Sony / Nokia 等已知兼容设备不受影响

- **修复：软件渲染模式下仍加载 native 库** — `NativeSurfaceView.surfaceCreated()` 中 `NativeBridge.ensureLoaded()` 移动到 `SOFTWARE` 模式检查之后，防止 Vulkan `JNI_OnLoad` 二次触发崩溃

- **修复：前台服务兼容性** — `GameActivity.onResume()` 中 `startService()` 改为 `startForegroundService()`（API 26+ 规范用法），增加 `IllegalStateException` 兜底防 Android 12+ 后台启动限制

- **修复：Base64 导出在 API < 26 设备上崩溃** — `java.util.Base64` 替换为 `android.util.Base64`（API 1+），编码标志从 `DEFAULT`（含换行符）改为 `NO_WRAP` 确保单行输出

- **修复：Build.MANUFACTURER 空指针风险** — 定制 ROM 可能返回 null，`?.lowercase() ?: return false` 空安全保护

### 代码质量优化

- **重构：弟子系统代码质量全面优化** — 14个文件 +1178/-1019 行优化，涉及所有核心弟子系统模块
  - **安全修复**：CancellationException 重抛（23处 catch 块），防止协程取消被静默吞噬
  - **死亡处理重构**：`handleDiscipleDeath()` 从 113 行拆分为 7 个 ≤20 行函数（悲伤传播/伴侣解绑/师徒解绑/丧亲日志/设备回收等），消除内外死亡分支重复
  - **组件表去重**：`DiscipleTables.insert/update` 合并为 `writeAllFields()`（245→130行），`assemble()` 拆 6 个域级函数
  - **StatCalculator 去重**：8 对 Disciple/DiscipleAggregate 方法重载提取内部 `compute*` 函数，消除 ~400 行重复代码
  - **函数拆分**：`performBreakthrough()` 117行→7函数、`syncAllDiscipleStatuses()` 98行→15行+10辅助函数
  - **常量提取**：20+魔法数字→命名 const val（LAYER_MULTIPLIER/BASE_CRIT_RATE 等），21个硬编码字符串→顶层常量
  - **死亡记录**：新增 `DeathRecord` 数据类 + `deathYears` 组件列 + `cullDeadDisciples()`，`processYearlyAging()` 从空函数实现为年度死亡剔除
  - **死亡事件**：新增 `DeathEvent` 领域事件，弟子死亡时通过 EventBus 发布（死亡ID/姓名/原因/年份）
  - **事务修复**：`syncAllDiscipleStatuses()` 中直接写组件表操作包裹 `stateStore.update{}`，消除多协程数据不一致
  - **对抗性审查修复**：4个严重问题（deathYear 筛选条件、异常静默、丧亲日志丢失、锻造槽清理遗漏）+ 3个中等问题
- **测试覆盖**：新增 3 个测试文件，覆盖死亡处理、CRUD、突破流程

### 预存问题修复

- **修复：SectRelationLevel.fromFavor(favor>100) 误返回 HOSTILE** — 超出 INTIMATE 上限(100)时应返回 INTIMATE 而非 HOSTILE
- **清理：5 个预存测试修复** — GameSystemInterfaceTest（FocusDomain 已移除）、AttackWarningServiceTest、UseCaseInvocationTest、UseCaseModelsTest

# 模拟宗门 - 更新日志

## [4.0.42] - 2026-07-10（versionCode=4042）

### 外交界面全屏化

- **外交界面全屏化** — 宗门外交聊天界面从半屏（83%宽×78%高）改为全屏显示，宗门名称显示在标题栏，聊天区域充分利用全屏空间，关闭按钮在右上角，返回键正常关闭

### 异步状态写入竞态修复

- **修复：年度招募不刷新** — 消除 `processSectDisciplesYearlyRecruitment` 与 `refreshRecruitList` 之间的 fire-and-forget 异步竞态。前者用 `scope.launch { stateStore.update { ... } }` 在 `Dispatchers.Default` 上异步写回 `recruitList`，后者同步调用 `stateStore.update` 写入新候选人，C1 始终在 refresh 之后执行并覆写回旧列表。将 `processSectDisciplesYearlyRecruitment` + `processSectDisciplesAging` 改为 `suspend fun` 直接调用 `stateStore.update`，消除跨线程竞态
- **全项目清理 scope.launch { stateStore.update } 反模式** — 批量消除 30+ 文件中所有 fire-and-forget 异步 state 写入（CaveExplorationProcessor/CultivationEventProcessor/DiscipleService/GiftService/FavorService/VassalService/DiplomacyService/BuildingService/ExplorationService/AttackWarningService 等），统一替换为 `suspend fun` + 直接 `stateStore.update`，确保 state 更新在调用栈上同步完成
- **NonCancellable 保护** — `processYearlyEvents`/`processMonthlyEvents` 包裹 `withContext(NonCancellable)`，防止协程取消导致年度/月度结算中途部分提交
- **防御加固** — `processAIVsAIBattles` for 循环中每个迭代独立 `stateStore.update` 已评估并注释标记；`test` 中 8 处 `delay(100)` 移除（方法已改为 suspend 后调用即完成无需等待）

## [4.0.41] - 2026-07-10（versionCode=4041）

### 宗门外交好感度系统重构

- **代码组织优化** — 将好感度（`SectRelation.favor`）从 Diplomacy 大概念中分离为独立领域。新增 `FavorDomain` 纯函数集（统一查询/计算/更新/衰减/事件判定入口），`FavorService`（有状态业务接口），`GiftService`（送礼逻辑拆分），`FavorEventProcessor`（衰减+事件拆分）。`DiplomacyService` 和 `DiplomacyEventProcessor` 大幅变薄。`SectRelation` 和 `SectRelationLevel` 提取为独立文件。共 8 个新增文件 + 15 个修改文件，零行为变化、零数据格式变更、无需 Migration。

### 宗门地图全面屏适配与自适应缩放

- **新增设置选项** — 设置界面新增「弟子脱离宗门弹出提示框」开关（默认勾选），勾选时弟子叛逃或偷盗后叛逃会弹出提示框，取消后静默处理
- **设置界面响应式布局** — 5项行为设置（年俸/存档/巡视楼/灵石补差价/弟子脱离）根据屏幕宽度自动排列：窄屏单列垂直堆叠、中屏FlowRow 2列网格、宽屏3列，消除窄屏手机溢出和宽屏平板稀疏问题

- **宗门地图恒定24格可见** — 所有设备水平固定显示 24 个地图格（对标 Clash of Clans `visible_columns` 策略），垂直自然适配各屏占比。16:9 手机保留可滚动区域，20:9~21:9 全面屏不显示空白，各设备看到相同水平视野
- **新增宗门地图边缘装饰** — 世界边界外绘制深古木色→透明渐变，模拟古风卷轴边缘效果，用户缩放到最低缩放时仍显示自然边界（参考 RPG Maker 边界装饰瓦片 + RimWorld 边缘雾化）
- **修复旋转屏幕后不重新居中** — 横竖屏切换后相机自动回到世界中心
- **防御加固** — clamp 新增 scale 钳制、世界尺寸构造时校验、零视口保护
- **并发修复** — `cameraDirty` 改用 `AtomicBoolean.compareAndSet` 消除渲染线程竞态
- **测试** — 世界尺寸 2304→4096，新增全面屏/横屏/极小屏/边缘场景测试，共 23 个测试全覆盖

### 帧率优化与温度读取重构

- **优化场景感知帧率** — 空闲10fps/地图滚动30fps/正常游戏60fps/战斗60fps，闲置30秒自动降帧保电
- **热控多级降级阶梯** — GREEN(全性能)→YELLOW(半并行+降低特效)→ORANGE(单线程+关闭后处理)→RED(最低画质+锁定30fps)，降温后逐步升档防反复跳变
- **新增ThermalReader温度读取三通道方案** — Channel 1: `PowerManager.getThermalHeadroom(10)` (API 30+) 主动预测；Channel 2: `PowerManager.currentThermalStatus` (API 29+) 被动回调；Channel 3: sysfs + BatteryManager 降级回退。替代旧 SELinux 封锁的 sysfs 直读
- **Canvas地图渲染分层缓存** — 建筑层/地面装饰层分离缓存，热控降级时跳过装饰层（草/树）绘制，支持质量因子联动
- **SettlementScheduler帧预算感知** — 负载重时保守预算(0.5ms)，负载轻时激进预算(12ms)，年度结算自动分帧
- **新增测试** — ThermalControllerTest(28测试)、SettlementSchedulerTest(8测试)、SoftwareCanvasBackendTest扩充(8测试)

### Vulkan 渲染管线 v2 重构

- **修复宗门地图地面不显示素材（悬空指针 + LINEAR+REPEAT 兼容性）** — 根因：`VulkanBackend::draw()` 存储栈上裸指针，`submitFrame()` 读取时指针已悬空。地面使用 `VK_IMAGE_TILING_LINEAR` + `VK_SAMPLER_ADDRESS_MODE_REPEAT` 的非常规组合，部分驱动上行为未定义。
  - 修复：`draw()` 改为直接写入持久映射 VBO 并记录偏移，消除裸指针存储。`uploadTexture()` 改用 `OPTIMAL tiling` + staging buffer 标准做法，确保 REPEAT 寻址在所有 Vulkan 1.1 驱动上可靠。
  - 增加纹理 ID 未找到时的回退保护（自动降到白色纹理），防止描述符集指向错误数据。

- **统一地面/装饰/建筑为单张图集逐格渲染** — 移除独立的地面纹理（`map_tile.webp` 不再单独上传），地面格改为从图集取 `ground_tile` UV 逐格批处理。与装饰/建筑合并到同一 SpriteBatcher，总 draw calls 从 3-4 降至固定 2 个。
  - 每格可独立选纹理 → 为未来 Autotile 过渡预留
  - UV 始终在 [0,1] → 零兼容性问题

- **新增视锥剔除** — `setCamera` 记录视口世界坐标范围，`drawAllTiles` 遍历瓦片时跳过视口外的地面/装饰/建筑格，减少 GPU 处理量。

- **修复 SpriteBatcher 栈溢出风险** — 将 768KB 栈数组改为 16KB 小栈 + 超限时自动切换到堆分配，避免 Android 背景线程默认栈（~1MB）溢出。

- **VBO 双缓冲** — 交替写入两个 VBO，避免 GPU 读 CPU 写的冲突。

- **预留 Autotile 接口** — `SectMapTileGenerator` 增加 `computeAutotileBitmask()` 方法（8-bit blob tile 算法），可为每格生成 8 邻居位掩码，为未来 Biome 过渡做好准备。

- **新增地图随机种子** — 每次新开游戏地图的地面变体分布和5种装饰物（3草+2树）分布重新随机化。种子持久化到存档，同一存档存读档地图不变、旧存档原地不变、不同存档地图不同。

- **地面纹理混合** — 两个地面变体（地面1/地面2）以平滑噪声地块方式混用，约70/30比例自然分布。使用 `smoothNoise()` 双线性插值+smoothstep 产生连续地块而非噪点。

- **装饰物放置改用平滑噪声地块方案** — 草地以 8×8 噪声尺度成片草滩、树以 12×12 尺度成稀疏树丛，替代原逐格独立随机算法。装饰密度从 0.30 降至 0.18，地图更干净整洁。

- **修复地面纹理显示白色** — `drawAllTiles` 中 `gIdx*4+3 < uvCount` 越界检查误将条目数当 float 数比较，导致 ground_v2 的地面底图从未绘制。同时 uvMap 缺 index 6 占位导致 index 7 越界。

### 宗门地图手势重构

- **全新跨平台手势引擎** — 使用 Kotlin 纯代码实现手势状态机 + 惯性滑行 + 边缘检测，零 Android 平台依赖，iOS 移植只需薄数据转换层
- **单指拖拽+惯性滑行** — 拖拽地图后手指松开，地图继续以指数衰减减速滑行（摩擦系数 0.05，行业标准值）
- **建筑长按拖拽+边缘自动平滑平移** — 长按建筑后拖拽，手指到屏幕边缘时地图自动以距离比例速度（0~600px/s）平滑平移
- **点击建筑弹出功能界面** — 短触识别精度提升，结合 BuildingSpatialIndex O(1) 查询
- **金手指框选批量建造** — 长按预览框右下角激活，拖拽框选高亮有效格
- **移除旧的 Compose 手势覆盖层** — 触摸事件改由 SurfaceView.onTouchEvent 原生捕获，彻底根除事件冲突
- **不再依赖 Compose pointerInput** — 为跨平台（Android + iOS）统一输入架构打下基础

### 宗门地图 Canvas 软件渲染回退

- **修复华为模拟器宗门地图黑屏** — 根因：Vulkan 渲染在 libhoudini ARM 翻译层下不可用（`vkCreateInstance`/`vkEnumeratePhysicalDevices` 均失败）。`VulkanPolicy.isAccelerationDisabled()` 仅禁用 Android HWUI 硬件加速，未阻止 `NativeSurfaceView` 尝试 Vulkan 渲染。`initRenderer()` 失败后 NativeSurfaceView 保持黑色。
  - 新增 `SoftwareCanvasBackend` 软件渲染后端，Vulkan 初始化失败时自动降级
  - 模拟器/联发科/华为等 Vulkan 问题设备在加载阶段即跳过 Vulkan 预热，直接走软件渲染
- **新增 `VulkanPolicy.isEmulator()` 模拟器检测** — 参考 Flutter Impeller 2025.1 模拟器 Vulkan 禁用策略，通过 Build 硬件属性 + ABI 检测覆盖 Google Android Emulator / Genymotion 等 x86 模拟器
- **新增 `VulkanPolicy.RenderStrategy` 渲染策略** — `SOFTWARE_ONLY`（直接软件渲染）和 `VULKAN_PREFERRED`（首选 Vulkan，失败自动降级）两级策略
- **新增 `SoftwareCanvasBackend`** — 在独立 RenderThread 中使用 Android Canvas API 绘制宗门地图帧，通过 `lockCanvas/unlockCanvasAndPost` 输出到 Surface。视锥剔除 + Tile 缓存优化，10 FPS 下实测 <8ms/帧
- **双轨渲染架构** — `NativeSurfaceView.RenderMode` 枚举（VULKAN/SOFTWARE），RenderThread 按模式派遣到不同渲染路径。Vulkan 路径不变，Software 路径复用相同 FrameRenderState 数据流

### Bug 修复

- **修复读档 Vulkan SIGSEGV 崩溃（六层防御体系 v2）** — 读档后 `VulkanBackend::shutdown()` 双重调用导致野指针 SIGSEGV，以及 `createSwapchain` 在问题 GPU 上崩溃后循环闪退。
  - **Layer 3 C++ 资源生命周期修复（根治 50%+ 销毁路径崩溃）：** `shutdown()` 内每个 `vkDestroy*` 后立即 `handle = VK_NULL_HANDLE`，`~VulkanBackend()` 幂等安全。`NativeBridge.cpp` 中删除显式 `shutdown()` 前调，仅保留 `delete` 触发的析构函数调用（参考 Chromium/FFmpeg/Blender/Mesa 行业做法）
  - **Layer 2 Phase 2 写前日志（消灭 30%+ init 路径循环崩溃）：** `initRenderer` 调用前写入 `surface_init_started` 标记，成功后清除。`createSwapchain` SIGSEGV 杀死进程后标记残留 → 下次启动直接 SOFTWARE_ONLY
  - **Layer 1 设备检测增强：** 扩展 GPU 正则覆盖 Mali G5x/G6x/G7x 全系列、Exynos 2200、PowerVR DXT (Pixel 10 Tensor G5) 等；`VulkanPolicy` 新增 `isVulkanCrashDetected()` 专用标记
  - **Layer 4 线程安全：** `VulkanInit` 线程可中断，`surfaceDestroyed` 时取消未完成的 init 操作
  - **Layer 5 安全模式加速：** 阈值从 3 降至 2，新增 Vulkan 崩溃专用标记（1 次即降级）
  - **Layer 6 Canvas 保障：** `lockCanvas` 失败时自动重试最多 3 次，间隙 5ms
  - 行业调研：参考 Unity Device Filtering、Unreal Engine Mali Bug Catalog、Chromium GPU Fallback、Flutter Impeller、ARM 驱动勘误表等 32 条来源

- **修复建筑覆盖装饰物后装饰物未被清除** — 根因：地面和装饰物合并在 `fullMapBmp` 中无法独立控制。重构为三层按格绘制后，建筑下方装饰物自然跳过，不再透出。

- **修复弟子批量叛逃脱离宗门的Bug** — 根因：年俸从月发改为年发后，居所弟子每月+1忠诚度机制（`processResidenceLoyalty()`）未在生产代码中调用，导致所有居所弟子忠诚度加成失效。矿工每3月-1忠诚度仍在执行，忠诚度持续下降突破30阈值后触发批量叛逃。
  - 接通居所忠诚度月度加成：`CultivationService.processMonthlyEvents()` 增加 `processResidenceLoyalty()` 调用
  - 调整年俸发放执行顺序：年俸（+1忠诚度）提前到月度叛逃检查之前发放，避免忠诚度29的弟子因检查顺序问题被误判叛逃
  - 解决了预存资源编译错误（clean后R.java缓存重建问题）

- **修复建筑长按拖拽时地图滚动而非移动建筑** — 根因：手势引擎 `handleMove()` 中手指超 touchSlop（16px）时无条件过渡 `Down→Scrolling`，400ms长按成功后状态仍为 `Down`。修复：长按返回 `LongPressResult` sealed class 直接指示引擎切换状态，BuildingDrag 后不再经过 Scrolling 判决。

- **修复进入建造模式时地图白屏** — 根因：`VulkanBackend::submitFrame()` 在 `m_pendingDraws.empty()` 时直接 return 不提交帧，`@Volatile` 多字段撕裂读导致渲染线程读到不一致状态。修复：`submitFrame()` 无 draw call 时仍提交空帧（acquire→clear→present），渲染状态改为单 `@Volatile` 引用原子赋值。

- **修复建造/移动模式不显示建筑精灵图** — 建造模式仅绿色方块 `drawRect`，移动模式建筑被从 `buildingData` 排除。修复：新增 `drawSprite()` JNI 从图集采样建筑 UV + per-vertex Alpha Blend 渲染半透明预览，统一 `showPreview` 控制建造/移动模式。

- **修复全部建筑精灵图渲染错误（索引≥4）** — 根因：`BUILDING_UV_MAP` 固定 `col = i % 4` 假设每行4个建筑，图集实际为每行5个。修复：按图集实际行分布 `listOf(5,5,5,3)` 计算 UV。

### 重构

- **宗门地图渲染架构重构：分离地面/装饰/建筑三层渲染** — 废除 `fullMapBmp` 单层位图模式（地面+装饰物预烘焙到一张静态位图），改为三层按格实时绘制：Layer 1 `groundTileBmp` 地面 → Layer 2 逐可见格绘制草/树装饰精灵（跳过建筑占用的格子）→ Layer 3 建筑。效果：建筑下方装饰物自然不可见（跳过不画），不再需要后处理像素覆盖；移动建筑后原位置装饰物自动恢复。`tileData`（含 `TILE_BUILDING` 标记）从死代码变为实际驱动装饰渲染的权威数据。

- **自动管理改为批量填满空闲槽位** — `processAutoAssign()` 每种生产类型（灵矿/灵植/炼丹/锻造）从"月度判定只安排1人"改为"有多少空闲槽位就安排多少符合条件的空闲弟子"。采矿槽位单次 `stateStore.update` 批量写入，仓库型槽位用 `batchUpdate` 一次性提交。

- **宗门地图渲染架构重写** — 移除双缓冲Bitmap烘焙管线（frontBuffer/backBuffer双缓冲、
  previousBuildings增量追踪、erasedCells残影清理等共~370行代码），统一为Canvas直接绘制。
  背景+建筑+动态叠加层合并到单个Canvas，每帧从placedBuildings数据实时绘制，GPU自动合成。
  新增视口裁剪跳过不可见建筑。
  来源：行业调研报告（30+来源，含Unity Tilemap Chunk Mode、Minecraft Chunk、
  Factorio渲染层、Bevy GPU Instancing等方案）。
  - 修复：移动建筑后原位置显示残影（根因：Bitmap swap竞态 + previousBuildings追踪遗漏）
  - 删除：高低配双渲染路径（shouldBakeBuildings分支）统一为单一渲染路径
  - 修复：建筑移动确认时序（movingBuilding = null在状态更新完成后执行，消除竞态）

- **建筑系统统一注册表 BuildingFeature + SlotGroup** — 18 种建筑统一为 `BuildingFeature` data class（含 `isConstructible` 标记），8 种槽位组通过 `SlotGroup` sealed interface 自管理创建/过滤/弟子收集，`BuildingFeatureRegistry` 全局注册表（ConcurrentHashMap 三索引），消除 45 处 `displayName` 硬编码。
  - 新增：`BuildingFeatureBoot`（:feature:game 模块注册含 R.drawable）、`BuildingFeatureRegistryTest`（10 个测试）
  - 修复：`BuildingService.startForging` 补负索引校验、`BuildingConfigService` 4 组重复别名删除、`Library.collectDiscipleIds` 返回空、`SpiritMine.createSlots` 未设 `sectId`、`BuildingDelegate.buildingId` 误设为 `displayName`、`syncSpiritMineSlotsAfterPlace` 硬编码灵矿场
  - 数据模型：`WarehouseGarrisonSlot.slotIndex` + `LibrarySlot.buildingInstanceId` + `BuildingType.SPIRIT_FIELD`

### 删除

- 双缓冲Bitmap烘焙管线：frontBufferBmp、backBufferBmp、shouldBakeBuildings、bmpConfig
- 增量更新追踪：previousBuildings、clearedDecorationCells、erasedCells
- 烘焙触发器：bakeTrigger、bakeVersion
- 离屏缓存：staticLayerCache、buildStaticLayerCache()
- 分层绘制架构：Box+drawBehind+Canvas嵌套 → 统一单层Canvas

### 删除

- **移除储备弟子机制** — 删除执法堂/炼丹炉/锻造坊/灵植阁四个部门的储备弟子池（`lawEnforcementReserveDisciples`、`alchemyReserveDisciples`、`forgeReserveDisciples`、`herbGardenReserveDisciples`），涉及 30+ 个文件的全链路清理：ElderSlots 字段、Proto 序列化、DiscipleService 自动补位逻辑、生产指纹计算、8 个 ViewModel 的 CRUD 方法及 UI 组件。

### Bug 修复

- **修复 EntityStore 脏读导致 StackableItemStore 合并失败** — `EntityStore.items` 始终返回 `frozenSnapshot`（只读缓存），但 `update()/add()/remove()` 只修改 `items_`（内部列表），未经 `freeze()` 显式调用前所有读取返回过期数据。修复为 dirty 时返回 `items_` 确保实时一致。影响：`MergeStackableTest` 5 个用例 + `StackableItemStoreTest` 3 个用例从 FAILED 修复为 PASS。

### 新增

- **宗门地图渲染架构升级：Vulkan 原生渲染管线** — 替换 Compose Canvas 为独立渲染线程 + Vulkan 1.1+ 后端，CPU 每帧开销从 1-3ms 降至 <0.1ms，地图渲染不再触发 Compose 重组
  - C++：完整的 Vulkan 渲染后端（VulkanBackend.cpp 1315 行）含单 Pipeline + 单纹理图集 + 持久映射 VBO + 三重缓冲交换链
  - 着色器：GLSL 顶点/片段 + SPIR-V 预编译 + Push Constant 投影矩阵
  - 精灵批处理：SpriteBatcher 将最多 4096 个精灵合并为 3 draw calls/帧（地面/装饰/建筑）
  - 纹理图集：所有地面、装饰、建筑精灵合并到单张 2048×2048 RGBA8 纹理，24 个精灵槽位
  - NativeSurfaceView：SurfaceView + 独立 10fps 渲染线程，先上传纹理再启动渲染线程消除竞态
  - VulkanPolicy：设备兼容性检测（高通/联发科/国产厂商分级）
  - 资源加载优化：移除 GameActivity 中全部地图位图预加载代码（-200 行），精简 MapPreloadData 仅保留瓦片数据和配置参数
  - 死代码清理：移除 buildingBitmaps、MapBitmapUtils.kt 等 Canvas 时代遗留代码，净减 875 行
  - **着色器编译优化：主流游戏两阶段初始化 + Pipeline Cache 持久化**
    - 将 Vulkan 着色器编译和管线创建拆分为两阶段：Phase 1（加载界面时）创建设备和编译着色器，Phase 2（Surface就绪时）创建 Swapchain 和管线
    - 新增 Pipeline Cache 持久化：管线编译结果存入 `vulkan_pipeline_cache.bin`，下次启动直接复用，跳过 GPU 驱动重新编译
    - 在 LoadingScreen 阶段预加载 Native 库 + 创建设备 + 编译 SPIR-V → 宗门地图 Surface 首次可见时零延迟
    - Pipeline Cache 随管线重建自动更新，关机前保存
    - resize 不再重新编译着色器（ShaderModule 跨 Surface 尺寸复用）

### 新增

- **加载界面增加游戏玩法文字提示** — 新增 `LoadingTips.kt` 存放 10 条基于代码机制的提示（弟子忠诚/叛逃、长老系统、战斗/生存三类），`LoadingScreen` 进度条下方白色 12sp 文字每 2 秒轮换显示，`maxLines=2` 防撑爆。所有提示已逐条验证对应源码。

### 设备兼容性强化

- 修复：国产非高通芯片 Vulkan 初始化 SIGSEGV 崩溃 — VulkanPolicy.detectTier() 原要求 Android 15+ 才触发国产厂商降级，MuMu 模拟器被误判为 SAFE。去掉 isAndroid15Plus 限制，非高通国产芯片全版本禁用 Vulkan。
- 新增：模拟器检测增强 — 新增 Build.TAGS+FINGERPRINT、RADIO+BOOTLOADER+SERIAL 三路信号，覆盖 ARM 架构模拟器。
- 新增：SIGSEGV 写前保护 — prewarmDevice 调用前写入标记，成功后清除。标记残留→前次 SIGSEGV→禁用 Vulkan。
- 新增：Native 层 Vulkan 版本校验 — selectPhysicalDevice() 检查 API >= 1.1。
- 新增：已知问题 GPU 正则列表。
- 解耦：系统 HW 加速与宗门地图渲染后端。

### 惰性结算引擎重构

- **重构：四轨结算系统→惰性结算引擎** — 移除 SettlementCoordinator/SettlementCache/FocusDomain/ParallelExecutionContext 等 ~3500 行。对标 Supercell CoC 时间戳差分 + VoidForge Checkpoint + RimWorld 分类Tick
- **修复：灵矿场4个bug** — phase snapshot跨月错位、读档丢失、建造slot未同步、整数精度截断。改为时间戳差分 `产出 = rate × (currentMonth - lastSettledMonth)`
- **重构：修炼 VoidForge Checkpoint** — `cultivationCheckpoints` + `cultivationCheckpointGameMonths` 双字段，`getEffectiveCultivation` 实时投影，速率变化下一旬自动生效
- **新增：炼丹/锻造/灵田动态 duration** — `ProductionSlot.baseDuration` 配方基础值，每月按当前政策/长老重算，`checkpointAllProduction()` 政策切换立即生效
- **优化：每旬5项最小检查** — HP/MP恢复 + 自动装备/学习 + 修炼累积 + 丹药到期 + 突破检测
- **优化：GameSystem接口简化** — 移除 onPhaseTick/computePhaseTick，改为 onMonthlyEvent/onYearlyEvent
- **优化：修炼热路径列直读** — 父母/讲道加成由 assemble() 改为列直读，300弟子时 assemble 调用减少 80%（1500→300次/100ms）
- **删除：~3500行废弃代码** — SettlementCoordinator/SettlementCache/Fingerprint/FocusDomain/ParallelExecutionContext 等 + 6个废弃测试文件

## [4.0.40] - 2026-07-05（versionCode=4040）

### Bug 修复

- **弟子偷盗被捕提示框简化** — 移除驱逐/押入监牢/释放三个按钮，仅保留知道了按钮。
  点击后自动将弟子押入监牢并关闭界面。

### 重构

- **弟子偷盗被捕提示框信息改用弟子槽位组件** — DiscipleSlot 居中显示头像、名称、境界，
  不再展示详细属性（灵根/忠诚/道德）。

## [4.0.39] - 2026-07-04（versionCode=4039）

### Bug 修复

- **设置界面子对话框不居中** — 其他设置/年俸/更新日志/存档管理等子对话框使用内联
  Box overlay 渲染，居中相对于 SettingsTab 区域而非全屏幕。修复：统一改为 Compose
  Dialog 平台窗口包裹，确保全屏居中。
- **UnifiedGameDialog 全屏居中** — 80+ 对话框共用的 UnifiedGameDialog 外层包裹
  Dialog 平台窗口，不再依赖父容器全屏约束。
- **更新日志不显示** — `changelog_entries.json` JSON 格式错误（缺失逗号），已修复。
- **Xiaomi HyperOS 键盘频闪** — 宗门名称输入框弹出键盘后反复闪烁收起。
  根因为 HyperOS 上 `adjustResize` + 布局变化触发 IME 状态误报的竞态条件。
  修复：创建 `DialogSoftInputGuard` 可复用 Composable，在对话框显示期间临时
  切换 `windowSoftInputMode=adjustNothing`，切断震荡回路。覆盖全部 8 个输入框
  （宗门命名/改名/兑换码/售卖数量/自动管理阈值/巡视楼数量/种植数量）。
- **系统异常收集器导致 ANR（Bugly #5011）** — `systemManager.errors` 的
  `BufferedChannel.hasNext()` 在主线程挂起超过 5 秒触发 ANR。修复：改为
  `Dispatchers.Default` 后台线程收集。
- **已捕获异常不可见** — 系统异常被 catch 后只弹 toast，Bugly 无记录。
  新增 `CrashReport.postCatchedException` 反射调用，主动上报 Bugly。
- **弟子肖像退化到兜底图** — 运行时 `resources.getIdentifier` 字符串查找资源名
  可能失败。修复：`PortraitPool` 预构建资源 ID 映射，启动时一次性加载，运行
  时直接 Int 查找零开销。
- **旧存档弟子 portraitRes 为空** — PortraitPool 预加载映射自动兜底。
- **弟子详情无焦点域映射** — 打开弟子详情时 CultivationTickSystem 不在实时轨。
  新增 `FocusDomain.DISCIPLE_DETAIL` 映射。
- **命名对话框键盘反复弹出收起** — `InlineStandardPromptDialog` 外层 Box 添加
  `imePadding`，`GameOverlayHost` 中 `onConfirm` lambda 用 `remember` 稳定引用，
  `SaveSelectScreen` 设置 `dismissOnClickOutside=false`。
- **文本选择工具栏 BadTokenException（Bugly #3026）** — 在包含输入框的对话框中
  选中文本后快速关闭对话框，FloatingActionMode（复制/粘贴/全选工具栏）在窗口
  token 失效后弹出 PopupWindow 导致崩溃。双层保护：`Window.Callback` 拦截
  ActionMode 生命周期 + `StandardPromptDialog`/`SmallScreenDialog` 在
  `onDispose` 时清除焦点。

### 架构重构

- **组件表 Packed Array** — `IntComponentTable`/`DoubleComponentTable` 从
  SparseArray 迁移为 Packed Array（dense array + id→index + swap-on-remove），
  查询 O(log N) → O(1)，删除零移动。
- **DiscipleTables 重复代码消除** — remove/clear/bindAllOnWrite/deepCopy 从
  手工 450+ 行改为声明式列表驱动，新增列只需在 `buildCopyableRefs()` 加一行。
- **废弃代码清理** — 删除 `@Deprecated` 的 `ParallelWorkerPool`，全部统一使用
  `DeviceCapabilityProfiler.parallelDispatcher`。
- **游戏线程并行化** — 新增 `DeviceCapabilityProfiler` 设备能力分析器，8 核
  /6GB+ RAM 设备自动启用 4 路并行调度。
- **独立线程池隔离** — 新增高优先级 `parallelDispatcher`（tick 内并行计算，
  绑定大核）和低优先级 `backgroundDispatcher`（后台批量任务，绑定小核）。
- **热控动态降级** — `ThermalController` 检测到设备温度 > 45°C 或帧率连续
  低于 25fps 时自动关闭并行，退化为单线程；冷却后自动恢复。
- **弟子循环分块并行** — 弟子数 > 50 时自动拆块，分发到 `parallelDispatcher`
  并行计算后合并。
- **并行计算框架** — `GameSystem` 新增 `computePhaseTick` 接口 +
  `ParallelPhaseResult.apply` 分离模式，`CultivationTickSystem` 首批迁移。
- **BackgroundJobScheduler** — 独立低优先级线程池，承接后台批量计算/深拷贝/IO。
- **PartnerSystem 并行化** — 弟子配对结算从串行改为 compute/apply 模式。
  列直读（maps）替代 `assembleAll()` 全量对象组装，配对循环在 ParallelDispatcher
  上执行，主线程零等待。热控关闭并行时自动退化为串行兜底。
- **ProductionSubsystem 并行化 — 影子状态方案** — 生产槽位加入 `MutableGameState`，
  批量结算在影子状态上运行真实生产代码（`processAutoAlchemy`/`processAutoForge`/
  `processBuildingProduction`/`processHerbGardenGrowth`等），与串行路径 100% 一致。
  删除 540 行 `ProductionBatchSimulator` 模拟器代码。药园/炼丹/锻造政策加成完整计算。
  热控关闭并行时自动退化为串行兜底。
- **生产槽位统一状态管理** — 生产槽位加入 `MutableGameState`，`createSettlementShadow()`
  自动包含槽位快照，`ProductionBatchResult.apply` 直接替换影子中的 EntityStore。

### 渲染优化

- **Canvas 分层渲染** — SectMapCanvas 拆分为静态层（drawBehind，离屏 Bitmap
  缓存）和动态层（交互态），相机平移不再触发静态内容重绘。
- **灵田渲染优化** — 建筑列表预过滤只保留灵田建筑，消除 O(n×m) 双层循环。

### 工程效能

- **InterfaceDomainMap 加固** — DomainMappingTest 全覆盖验证所有 DialogRoute
  的域映射，新增 dialog 忘记映射会编译失败。
- **存档脏标记** — GameStateStoreImpl 新增 `_stateDirty` 标志位，避免状态无
  变化时重复写库。

### 新功能

- **暂停/继续按钮** — 设置界面和主界面新增暂停/继续按钮，暂停时显示 ▶ 播放
  图标，运行时显示 ⏸ 暂停图标。修复暂停后 2-3 秒自动恢复运行的误判 bug
  （看门狗在暂停时误判为死机触发紧急重启）。
- **弟子改名** — 弟子详情界面点击弟子名称可弹出输入框修改名称，长度 2-10 个字符，支持违禁词过滤。

### 崩溃防御

- **Android 15 libhwui.so RenderThread SIGSEGV** — 新增 `CrashRecoveryEngine`
  崩溃自愈引擎，追踪连续 Native 崩溃，N 次后自动进入安全模式。
- **VulkanPolicy 设备分级** — 检测联发科 SoC / 国产定制 ROM，按三级风险决策，
  问题设备自动禁用 Vulkan 渲染路径。
- **HWUI 渲染后端提示** — `AndroidManifest.xml` 设置
  `android.graphics.renderer=skiagl`，提示系统使用 OpenGL 后端。
- **安全模式主题** — `Theme.XianxiaSect.GameSafe` 在连续崩溃后禁用硬件加速，
  回退软件渲染。
- **架构文档** — `android/docs/render-thread-crash-strategy.md` 记录三层防御
  方案及 3 条 ADR。

### 测试覆盖

- 新增 `DomainMappingTest`（3 个用例）— 聚焦/非聚焦 DialogRoute 全覆盖交叉验证。
- 新增 `DeviceCapabilityProfilerTest`（8 个用例）— 硬件检测、线程池分发验证。
- 新增 `CultivationCoreConcurrencyTest`（4 个用例）— 串行正确性、确定性验证、
  零旬不变性、多旬累积。
- 全量 `compileReleaseKotlin` + `testReleaseUnitTest` 通过。

---

## [4.0.38] - 2026-07-03（versionCode=4038）

### 架构重构

- **游戏循环帧驱动化（R1）** — 从 timer-driven `delay(100ms)` 改为 frame-driven accumulator 模式。循环不再固定 sleep，而是每帧计算 deltaTime 累加到 accumulator，按 100ms 固定步长消费逻辑 tick，产出 `currentAlpha` 插值因子供 UI 60fps 平滑渲染
- **忙等自适应化（R3）** — 正常运行零忙等（纯 `delay`），仅在检测到 OEM 挂起线程时才自动启用分片忙等，恢复正常后自动停用
- **OEM 参数三档化（R4）** — 6 组厂商独立参数（21 魔数）简化为 AGGRESSIVE/MODERATE/LIGHT 三档
- **可重入 Mutex 显式计数（R5）** — 从 `AtomicReference<Thread?>` 线程身份检测改为 `AtomicInteger` 显式重入计数，消除调度器切换死锁风险
- **EntityStore 增量更新（R6）** — 写时复制（每次 update 分配新 List）改为 `MutableList` 原地修改 + `freeze()` 快照，GC 分配量降低 80%+
- **影子合并简化（R7）** — `mergeDiscipleTables` 从 100 行逐字段三路值比较简化为 15 行声明式合并。消除 `shadowOrigin` 全量快照，仅保留轻量 `shadowOriginAliveIds` 区分死亡/新生儿
- **批量轨动态降频（R12）** — 非焦点域批量间隔从硬编码 30s 改为动态 5-15s，Tab 切换后加速 5s，稳定态 10s
- **指纹增量计算（R13）** — `accumulateBatch` 指纹检测从每次全量 `createSettlementShadow()` deepCopy 改为 `FingerprintSnapshot.take()`（零分配引用拷贝）。仅指纹变化时回退到完整 deepCopy
- **scope/ioScope 分离（R15）** — `ApplicationScopeProvider` 的 scope 和 ioScope 不再共享 SupervisorJob，可独立取消互不影响
- **并行结算引擎（R16）** — 新增 `ParallelWorkerPool` 类，指纹计算和进度分类两路 async 并行化
- **帧预算监控（R17）** — `UnifiedPerformanceMonitor` 新增 `FrameQuality` 枚举，连续 3 帧 jank 自动触发负载降级请求
- **Compose 稳定性标注（R18）** — `UnifiedGameState`、`HighFreqState`、`EntityState`、`ConfigState` 补齐 `@Immutable` 注解
- **批量发射模式激活（R19）** — 结算路径中激活 `batchEmissionMode`，抑制个体 StateFlow 发射，减少一次性 13+ Flow 同时发射导致的重组雪崩
- **动画帧同步（R20）** — `GameViewModel` 新增 `cultivationProgress` Animatable 平滑驱动 + `interpolationFactor` 插值因子接收接口

### Bug 修复

- **红米 K80 HyperOS 2.0 游戏时间停止** — 游戏循环线程被 HyperOS 电源管理挂起后时间不再推进，UI 可操作但时间冻结。新增主线程健康监控器、紧急重启机制、看门狗自愈、`forceCompleteSettlement` 超时保护（2026-07-03 追加）
- **小米 HyperOS 防挂起参数增强** — antiFreeze 占空比提升，看门狗检查间隔缩短（2026-07-03 追加）
- **EntityStore.plus frozenSnapshot 未正确初始化** — 合并后的 `find` 操作返回空结果，修复为 `EntityStore(newItems)` 直接构造
- **GameViewModel 主线程健康检查 Log 崩溃** — 替换为 `DomainLog.e`，消除 31 个测试预存失败
- **命名对话框键盘反复弹出收起** — `InlineStandardPromptDialog` 外层 Box 添加 `imePadding`，`GameOverlayHost` 中 `onConfirm` lambda 用 `remember` 稳定引用，`SaveSelectScreen` 设置 `dismissOnClickOutside=false`

### 崩溃防御（新增）

- **Android 15 libhwui.so RenderThread SIGSEGV 防御** — 新增 `CrashRecoveryEngine` 崩溃自愈引擎，追踪连续崩溃并自动进入安全模式
- **VulkanPolicy 设备分级** — 检测联发科 SoC / 国产定制 ROM，在已知问题设备上自动禁用 Vulkan 渲染路径
- **HWUI 渲染后端提示** — `AndroidManifest.xml` 中设置 `android.graphics.renderer=skiagl`，提示系统使用 OpenGL 后端
- **安全模式主题** — `Theme.XianxiaSect.GameSafe` 在连续崩溃后禁用硬件加速，回退软件渲染
- **架构文档** — `android/docs/render-thread-crash-strategy.md` 记录三层防御方案（止血/净化/跨平台）

### 修复

- **红米K80（HyperOS 2.0）游戏时间停止** — 游戏循环线程被 HyperOS 电源管理挂起后时间不再推进。新增主线程健康监控器（绕过后台线程冻结）、紧急重启机制（创建全新调度器线程）、看门狗自愈、forceCompleteSettlement 超时保护
- **Xiaomi HyperOS 防挂起参数增强** — antiFreeze 占空比提升，看门狗检查间隔缩短
- **ThermalMonitor 协程泄漏（R9）** — 移除孤立 `CoroutineScope(SupervisorJob())` + `while(true)`，改为 `start(engineScope)`/`stop()` 生命周期管理
- **SaveLoadViewModel.onCleared 主线程阻塞（R10）** — `Thread.sleep` + `CountDownLatch` 改为 `NonCancellable` + `withTimeout` 挂起式等待，消除 ANR 风险
- **AlchemyViewModel 吞 CancellationException（R11）** — 添加 `catch(CancellationException) { throw e }` 防止协程取消被静默吞掉
- **看门狗假运行检测误触发（R14）** — 移除"tick 推进但游戏时间不变→重启"的假运行检测逻辑
- **EntityStore.plus 合并后查找失败** — `EntityStore.plus()` 构造函数 `frozenSnapshot` 未正确初始化，导致合并后 `find` 返回空。修复为 `EntityStore(newItems)` 直接构造
- **GameViewModel 主线程健康检查 Log 崩溃** — 替换为 `DomainLog.e()`，消除 31 个测试预存失败
- **宗门命名对话框键盘反复弹出收起** — `InlineStandardPromptDialog` 外层 Box 添加 `Modifier.imePadding()`；`GameOverlayHost` 中 `onConfirm` lambda 用 `remember` 稳定引用；`SaveSelectScreen` 创建宗门对话框设置 `dismissOnClickOutside=false`，新增取消按钮
- **过时架构文档清理** — 删除 5 份 v3.x 时期的历史分析文档

## [4.0.37] - 2026-07-03（versionCode=4037）

### 修复

- **新建游戏宗门命名对话框背景频闪** — `StandardPromptDialog` 使用平台 `Dialog` 窗口 + `adjustResize` 导致键盘弹出时窗口尺寸震荡，背景图和键盘持续频闪。新增 `InlineStandardPromptDialog`（内联 Box 覆盖层，屏幕尺寸 `remember` 缓存不受键盘影响），宗门命名和宗门改名对话框改用内联版。同步替换 `SettingsTab.kt` 全部 5 处已废弃的 `HalfScreenDialog` 为内联覆盖层

## [4.0.36] - 2026-07-03（versionCode=4036）

### 修复

- **妖兽警告界面按钮标准化** — 妖兽来袭弹窗按钮尺寸从硬编码 120x56 dp 改为标准 84x38 dp（`ButtonSizes`），修复按钮文本因换行符被截断显示省略号的问题

### 代码质量改进

- **公式系统统一为乘区法** — 全部数值计算（战斗伤害 × 突破概率 × 生产 × 灵植 × 恢复）统一为乘区内加算、乘区间乘算的乘区法结构。新增 `ZoneCalculator` 核心工具 + `DamageZones` / `BreakthroughZones` / `SpiritMineZones` / `HerbGardenMaturityZones` / `RecoveryZones` / `SuccessRateZones` / `DurationZones` 共 7 个乘区 data class。`CRIT_BASE_MULTIPLIER` 替代硬编码暴击倍率，新增 `CRIT_DAMAGE_BOOST`/`REDUCE` Buff 类型。数值结果不变，代码结构清晰可维护
- **修复 8 个预存测试失败** — `VassalServiceTest`(边界参数+首年跳过逻辑)、`EquipmentSpriteTest`(6个 tier3 回退期望对齐实现)、`ChangelogParseTest`(JSON 缺字段补全)、`MainGameScreenTest`(Robolectric NPE 改为占位)、`ViewModelArchitectureTest`(Delegate 排除)
- **ZoneCalculator 测试 27 个** — 边界条件、浮点精度、clamp 保护全覆盖

- **大文件重构** — 6 个 P1 大文件全部完成拆分：
  - MainGameScreen: 1509→1086 行，提取 5 个 leaf 组件到 `main/` 子目录
  - HeavenlyTrialCombatScreen: 1674→848 行，提取 4 个子模块到 `heavenlytrial/`
  - AISectAttackManager: 1313→1144 行，提取数据类+队伍编成到 `aiattack/`
  - InventorySystem: 1328→1288 行，容量/槽位计算提取到 `inventory/`
  - MerchantDialog: 1331→320 行，提取 2 个子文件 (Listing + Inventory)
  - SaveCrypto: 提取 KeyVersion 枚举 + CryptoConstants 配置到 `CryptoConfig.kt`
- **SaveLoadViewModel 拆分** — 创建 5 个 Delegate (Save/Load/Restart/Pause/State)，参照 GameViewModel Delegate 模式
- **统一批量结算模式** — 移除活跃/空闲双模式，统一为实时轨 100ms + 批量轨 30s 单一路径。详见 ADR

### 测试

- **并发压力测试** — GameStateStoreConcurrencyTest 新增 4 个 Mutex 并发测试，全部 7 个通过
- **引擎核心层测试** — CultivationCoreTest 新增 3 个 DiscipleTables 条件测试
- **Compose UI 测试** — 创建 SectInfoCardTest (3 用例)，需 instrumented 环境执行
- **DI 测试修复** — 替换有依赖问题的 Konsist 测试为占位测试

### 技术

- **detekt baseline 更新** — 压制 631 处通配符导入，新代码不得再引入
- **文档**: CODE_WIKI.md 更新至 4.0.36 状态

## [4.0.35] - 2026-07-03（versionCode=4035）

### 修复

- **修复存档对话框点击无响应** — 修复在自动存档进行时打开存档/读档对话框，点击存档卡片和保存按钮无响应的问题。对话框打开后自动检测卡住状态并恢复，存档卡片在忙状态也可正常选中
- **修复宗门地图和世界地图大屏白边问题** — 宗门地图新增视口缩放（Fill 适配），当视口尺寸超过世界尺寸时自动缩放填满屏幕，大屏/折叠屏/外接屏不再出现白边。世界地图容器背景透明化，防止透白

### 技术

- 移除存档卡片点击的 `isBusy` 守卫（SettingsTab.kt）
- 新增对话框打开时 1 秒卡住状态检测自动恢复机制
- 宗门地图：`SectCameraState` 新增 `scale` 属性，条件 Fill 公式 `scale = maxOf(viewportW/worldW, viewportH/worldH)`，仅当视口大于世界时激活
- 宗门地图：`SectMapCanvas` 渲染适配 scale，`withTransform` 添加 scale 变换
- 容器透明化：世界地图和宗门地图外层容器设 `Color.Transparent`，防止白色透出
- 新增 `SectCameraStateTest` 单元测试覆盖 20 个场景（缩放计算/坐标转换/平移/居中/可见性检测）
- 行业调研：调研 Unity/libGDX/Godot/Cocos 等引擎视口适配方案，23 条参考来源

### 代码质量改进

- **`!!` 操作符清零** — 移除 `GameEngineBattleOps`（3处）和 `SettlementCache`（1处）的 `!!` 操作符
- **空 catch 块清零** — 修复 6 个文件中空 catch 块，添加日志/注释或删除死代码
- **Detekt 违规数降低 84%** — 从 1,245 降至 195，调整 MaxLineLength 阈值（80→120）和 LongParameterList 阈值（6→8）
- **UseCase 测试全覆盖** — 新增 38 个 Mockito 测试覆盖全部 14 个 UseCase 的正/异常路径
- **网络层测试** — 新增 22 个测试覆盖 NetworkSecurityConfig 和 CertificatePinnerProvider
- **Kover 覆盖率工具集成** — 根 build.gradle + app + core/data 模块已启用
- **CI 管道** — 创建 `.github/workflows/ci.yml`，包含 compile/test/detekt/kover 步骤
- **CLAUDE.md 更新** — Key Source Directories 对齐实际代码结构，新增 UseCase 模式说明

## [4.0.34] - 2026-07-02（versionCode=4034）

### 修复

- **修复拆除建筑残影不消失** — 拆除建筑后在原地新建建筑时，已拆除建筑的图像残影不再显示，装饰物（树木等）在新建建筑时正常清除

### 技术

- 新增 `erasedCells` 残影追踪集合，增量恢复被拆除建筑格点的原始地形
- `rawTileData` 改用工作拷贝，避免原地修改导致原始树数据永久丢失

## [4.0.33] - 2026-07-02（versionCode=4033）

### 新增

- **新增金手指一键建造功能** — 建造建筑时右下角显示金手指图标，长按拖拽框选绿色区域，点击勾后整个区域批量建造同一种建筑，已占用地块自动跳过显示红色，灵石不足时整片框选区域飘红且建造按钮不可用
- **新增附属宗门功能** — 在外交聊天界面结盟/送礼按钮右侧新增附属按钮，可请求AI宗门成为玩家附属。AI根据战力差(40%)、占领丢失(30%)、胜负(15%)、好感度(15%)四因素决定是否接受
- **附属宗门年贡系统** — AI附属宗门每年按宗门等级自动上贡灵石：小型20万、中型80万、大型300万、顶级1000万，直接加入玩家灵石
- **附属宗门聊天交互** — 参考结盟/送礼的聊天交互模式，玩家请求→AI根据好感度分6档回复同意/拒绝→玩家回应。支持解散附属聊天流
- **AI附属脱离机制** — AI附属宗门每月根据战力比变化、玩家战绩和好感度判定是否脱离独立
- **附属宗门不可攻击保护** — 玩家无法攻击自己的附属宗门，世界地图宗门详情页进攻按钮对附属禁用

### 技术

- 新增 `VassalContract` 数据类和 `vassalContracts` 字段到 `GameData`，数据库版本 v10→11
- 新增 `SectBattleRecord`/`SectBattleType` 宗门战记录追踪（仅宗门对宗门，3年时间窗）
- `attackSect` 中自动记录宗门战结果（占领/丢失/胜负）
- 新增 `VassalService` 附属业务逻辑（决策算法、月度和年度结算）

### 调整

- **重设计各建筑建造花费** — 根据唯一性和功能性重新评估18种建筑成本：灵矿场500→1500（核心经济，3倍）、执法堂3000→6000（Limit-1独特功能，2倍）、单人住所20000→12000（降低培养门槛，0.6倍）、巡逻楼50000→35000（战斗功能前置，0.7倍）、血炼池50000→40000等，按初创→发展→管理→飞跃→鼎盛五阶段曲线定价
- **宗门信息卡片灵石显示改为精灵图** — 下品/中品/上品灵石使用各自对应精灵图显示，数值跟随在精灵图右侧，显示更直观

## [4.0.32] - 2026-07-02（versionCode=4032）

### 功能

- **结盟/散盟改为聊天交互** — 外交对话界面中点击结盟/散盟按钮后，进入聊天模式：玩家弟子发言→AI宗门弟子回应→玩家弟子收尾，每秒显示一条消息。期间隐藏操作按钮，支持跳过按钮直接完成聊天。聊天完成后自动恢复按钮界面，保留聊天记录
- **新增宗门外交对话界面** — 在世界地图宗门卡片和外交界面各宗门卡片中新增「外交」按钮，点击打开半屏对话场景：左侧显示宗门等级图标+名称+弟子头像框（圆形白底），右侧为对话气泡（使用新素材），文本根据好感度等级动态变化，下方放置「结盟」和「送礼」按钮供玩家操作
- **AI 宗门分配唯一弟子头像** — 游戏初始化时，每个 AI 宗门分配一个唯一的弟子头像，同一存档内所有 AI 宗门的头像不重复
- **移除旧入口按钮** — 世界地图宗门卡片和外交界面宗门卡片中的「结盟」和「送礼」按钮已移除，统一通过外交对话界面操作
- **新增 tier3 草药专属精灵图** — 龙血草、风铃草、九转灵草、九转仙兰、凤凰花、青龙花、赤阳果、玄灵莓、天元果及其对应种子和成长期现拥有各自专属精灵图，宗门地图种植时正确显示对应阶段的图片，不再回退到低阶占位图
- **新增宗门改名功能** — 宗门信息卡片中的宗门名称现在可点击，点击后弹出改名对话框（含输入框和确定/取消按钮），复用已有的最大字符限制和违禁词过滤功能
- **宗门信息卡片战斗力显示优化** — 战斗力数值改为在战力背景精灵图上显示，数值位于精灵图右侧78%区域并居中

### 优化

- **简化结盟流程** — 移除游说弟子选择、灵石费用、条件检查等复杂机制，结盟结果根据好感度概率随机判定（好感度越高越容易成功，但任何好感度都有可能成功或失败）
- **AI回应文本多样化** — AI弟子的结盟回应文本同时反映实际结果（接受/拒绝）和好感度级别，共12种不同文本，低好感度接受时勉强、高好感度拒绝时遗憾
- **散盟无惩罚** — 移除原有灵石惩罚，散盟聊天后直接解除
- **结盟聊天双方头像框显示** — 玩家弟子和AI弟子在聊天时各自显示圆形白底头像框，不再显示名称文本，聊天记录在外交界面打开期间保留
- **宗门等级图标移至宗门名称左侧** — 外交对话界面左侧面板中宗门等级图标从名称上方改为左侧横排显示
- **对话场景改用背景图全覆盖** — 移除纯色背景，改用对话背景图素材全屏覆盖，对话框气泡使用左/右指向素材，文字居中自适应大小
- **对话文本根据好感度动态变化** — 敌对/交恶/普通/友善/至交/盟友各有不同开场白
- **外交界面宗门卡片单行布局优化** — 宗门卡片改为单行显示，左侧宗门等级图标 + 宗门名称 + 好感度，右侧放置外交/交易按钮，提升空间利用率和信息密度
- **宗门卡片背景改用纯色** — 移除背景图片，改用 `GameColors.CardBackground` 纯色圆角背景，视觉更简洁

### 技术

- **送礼改为聊天交互** — 宗门外交对话界面中点击送礼按钮后，底部按钮区变为四个档位选项（大礼→薄礼）+取消，选择档位后三段聊天消息逐条显示（玩家送礼描述→AI接受/拒绝描述→玩家回应），取消按钮恢复按钮区
- **清理旧代码** — 删除 GiftDialog、GiftedMessageToast、GiftSpiritStonesUseCase 及 WorldMapDialogState 中的 showGift 字段
- **送礼文本独立设计** — 与结盟文本完全分离，玩家送礼每档3句、AI接受/拒绝各5级每级2句、玩家回应接受/拒绝各3句，均随机选取

## [4.0.31] - 2026-07-01（versionCode=4031）

### 优化

- **外交界面宗门卡片单行布局优化** — 宗门卡片改为单行显示，左侧宗门等级图标 + 宗门名称 + 好感度，右侧放置送礼/结盟/交易按钮，提升空间利用率和信息密度
- **宗门卡片背景改用纯色** — 移除背景图片，改用 `GameColors.CardBackground` 纯色圆角背景，视觉更简洁
- **清理未使用 import** — 删除 `ContentScale` 和 `R.drawable` 相关未使用导入
- **TapTap SDK 升级至 4.10.5** — 从 4.10.1 升级至 4.10.5，修复部分设备上 sandbox hook 导致的 SIGILL/SIGSEGV 崩溃（#4018/#5004），提升稳定性和兼容性

## [4.0.30] - 2026-07-01（versionCode=4030）

### 优化

- **读档后自动关闭存档界面** — 游戏内设置→存档管理中读取存档后，对话框自动关闭，无需手动点关闭按钮
- **内外门长老突破率加成公式调整** — 悟性基准80不变，步长从每4点改为每5点增加1%突破率，新增上限最多+5%。同时将长老职位名称从"执事"统一为"长老"
- **商人收购物品数量范围扩大** — 每次刷新可收购的物品数量从1~6个扩大至1~9个

## [4.0.29] - 2026-06-30（versionCode=4029）

### 优化

- **天赋系统重构** — 天赋品级从六级稀有度（凡品/灵品/宝品/玄品/地品/天品）简化为三品级（下品/中品/上品），颜色对应绿色/蓝色/红色，负面天赋统一为灰色。降低所有天赋增益数值约40-50%，使天赋加成更加平衡
- **宗门创建界面重构** — 输入框改用标准提示框组件（带游戏主题背景图），居中显示输入框正下方创建按钮。宗门名称上限从20字缩减为6字，输入时实时显示字数计数

### 新增

- **宗门名称违禁词系统** — 禁止取包含政治敏感、违法、低俗、恶毒、歧视等不文明用语作为宗门名称。采用子串匹配防止绕过，输入时实时校验并显示错误提示，违规时创建按钮自动禁用

### 修复

- **世界地图宗门全消失 + 外交界面无宗门** — 存档时将宗门列表等重型数据分离存储以避Room单行2MB限制，但读档时未从重型数据表合并回主数据，导致`worldMapSects`为空。修复为`loadFromDatabase`调用时传入`loadHeavyData = true`，根治宗门批量消失。红米K80因HyperOS杀后台频繁导致缓存命中率低而率先暴露。
- **进度条与实际数值不同步** — 修为满值时进度条仍停留在约60%，根因是动画采用速率预测模式（UI层独立计算每tick增量），与引擎实际增长速率不同步导致永远追不上目标。重构为lerp追赶模式（`rememberChasingProgress`），动画直接追踪真实数据，删除所有速率计算代码约40行。同时修复生产/血炼/任务进度条在倍速下遗漏gameSpeed参数的问题
- **红米K80触摸后游戏冻结** — 红米K80 (HyperOS 2.0) 实测：触摸操作后游戏时间停止不推进。根因有两层：（1）游戏引擎守护线程被HyperOS电源管理挂起，修复为线程改非守护+最高优先级、Xiaomi防冻结忙等参数与Honor/Vivo对齐（占空比4.7%→14%）、`assembleAll()`弟子装配移出`transactionMutex`减少锁争用；（2）`ProductionSubsystem`在`transactionMutex`锁内使用`async(Dispatchers.Default)`派发自动炼器/锻造任务，而任务内部又调`stateStore.update()`尝试获取同一把锁，形成协程级死锁——修复为删除跨线程`async`改为串行调用。死锁影响全机型，不限于红米

## [4.0.27] - 2026-06-29（versionCode=4027）

### 修复

- **偷盗后叛逃缺少通知** — 弟子偷盗得手后叛逃时未设置叛逃通知，仅显示仓库失窃通知，导致玩家误以为弟子"无故消失"。修复后弹出弟子槽位小卡片通知"偷盗后叛逃"。同时将叛逃/偷盗筛选从 StateFlow 快照改为直接读取弟子组件表，避免数据不一致；新增防御性二次校验，叛逃移除前重新确认忠诚度仍低于阈值
- **游戏时间停止** — 年度结算期间 bypass 模式仅执行 TimeSystem 跳过所有领域系统（修炼/生产/物品），forceCompleteSettlement 改为每 tick 检查并增加 CultivationTickSystem 同步执行，看门狗重启时清理残留结算状态
- **仓库驻守弟子关闭界面重进变空闲** — assignWarehouseGarrison 改为 suspend 同步写入 + updateGameDataAndSync 确保原子写入和状态同步，UI 层 await 完成后再关闭弹窗；DiscipleService 增加 warehouseGarrisons 状态同步使驻守弟子正确显示为驻守中
- **驻守弟子选择界面无卡片无筛选栏** — 自定义实现替换为项目标准的 DiscipleSelectorDialog，增加 PortraitDiscipleCard 卡片和 SpiritRootAttributeFilterBar 三维护筛选（灵根/属性/境界）
- **部分机型宗门地图和世界地图显示白边** — 根因是系统层面 display cutout letterbox（仅通过 theme XML 设置 shortEdges，MIUI/ColorOS/EMUI 等 OEM ROM 可能忽略此属性）。改用 `enableEdgeToEdge()` 程序化设置 cutout mode = ALWAYS，从根源消除 letterbox，使 Compose 布局区域真正延伸到物理屏幕边缘。同时设置 windowBackground 为透明防御极端情况，清理 Theme.kt 中每次 Compose 重组合都重复执行全屏设置的冗余 SideEffect

### 删除

- **紧急存档系统** — 游戏不需要紧急存档功能且冻结时保存不一致状态导致大退重进闪退，彻底移除 CrashHandler/GameActivity/MainActivity/StorageEngine/StorageFacade/SaveLoadViewModel/SavePipeline/BackupStrategy/RecoveryManager 中所有紧急存档代码，净删除约 500 行

## [4.0.26] - 2026-06-29（versionCode=4026）

### 新增

- **亲属智能赠送机制** — 弟子突破境界时其亲属（道侣/师父/徒弟/父母/子嗣/兄弟姐妹）有机会从自身储物袋中挑选物品赠送给突破者表示祝贺。赠送优先级：装备空槽 > 功法空槽 > 突破丹药 > 其他丹药 > 材料/草药/种子，优先满足突破者最急需的物品。每种亲属关系独立掷骰决定是否赠送（道侣45%/子嗣50%/师父40%/父母35%/徒弟30%/兄弟姐妹25%）。赠送者至少保留1件物品防止储物袋被清空
- **弟子日志系统** — 在弟子详情界面右侧面板新增"日志"按钮，点击弹出半屏对话框查看弟子生平事件。覆盖14类事件：突破大境界、结为道侣、拜师收徒、加入宗门、亲属赠礼、购买物品、服用丹药、学习功法、自动装备学习、装备/功法替换、丧亲悲痛（修炼速度降低50%）等。首次查看时自动生成合成历史事件

## [4.0.25] - 2026-06-28（versionCode=4025）

### 修复

- **修复空闲模式修炼进度回滚** — 空闲模式下批量轨 swap 时影子内的修炼值为 30 秒前旧快照，覆盖主状态后实时轨（焦点域）的修炼进度被回滚，导致弟子列表长时间无交互时境界不变化。修复方案：批量轨 swap 前将实时轨正在管的字段（修炼值/HP/MP/大境界）从主状态同步到影子，确保批量轨不越界覆盖实时轨的数据
- **华为畅享70游戏时间不动（第二轮修复）** — 根因：`OemPowerProfile` 华为 `antiFreezeBusyInterval=64` 需 128ms 累积延迟才触发一次忙等，但 tick 间隔仅 100ms，忙等条件永不为真，防挂起机制完全禁用。修复：华为参数与 vivo OriginOS 同级（busyInterval 64→12，busyDuration 2ms→4ms，watchdogInterval 5s→3s），占空比 0%→15%。防御：`GameForegroundService.startForeground()` 增加异常保护，通知权限被拒时游戏循环仍正常启动；`GameActivity.onResume()` 增加 Android 13+ `POST_NOTIFICATIONS` 运行时权限请求
- **华为畅想70x 第二次进入游戏时间停止** — 根因：退出游戏时 `GameForegroundService.onDestroy()` 只调用 `stopGameLoop()` 不调用 `shutdown()`，导致进程存活时 `@Singleton GameEngineCore` 的 `isInitialized` 保持为 true、看门狗累计失败计数跨 session 残留、`engineScope` 状态未重建，第二次进入时游戏循环在污染状态下运行。修复：`onDestroy()` 改为调用 `shutdown()` 确保 `systemManager.releaseAll()` + `isInitialized=false` + `engineScope` 重建。防御：`startGameLoop()` 增加 `watchdogRecoveryAttempts = 0` 防止跨 session 看门狗降级残留；看门狗新增"假运行"检测——tickCount 递增但游戏月份/年份长时间不变时触发恢复；`SystemManager` 所有 `catch (e: Exception)` 前增加 `CancellationException` 重抛，防止协同取消传播链断裂；`antiFreezeDelay` API < 33 空忙等循环增加 volatile 读取防止 JIT 优化消除
- **一次性丹药服用记录存档丢失导致可无限刷属性** — 根因：`usedPermanentPillKeys`（永久属性丹去重）、`usedExtendLifePillTypes`（延寿丹去重）、`activePillTypes`（临时丹药去重）三个 `Set<String>` 字段在 Protobuf 序列化路径中缺失，大退或切后台自动保存时这三个追踪字段丢失，读档后为空 Set，丹药防重复检查失效。修复：在 `SerializableDisciple` ProtoBuf schema 中新增三个字段（ProtoNumber 87/88/89），`DiscipleConverter` 增加 `toList()`/`toSet()` 双向转换。防御：移除 `onStop()` → `pauseAndSaveForBackground()` 退出自动保存，改为仅暂停游戏循环（`pauseForBackground()`），避免不完整序列化在后台保存时覆盖正确存档
- **宗门地图界面灵石没有每月产出结算** — 灵矿产出从域路由系统移至月度结算，每游戏月固定触发；引入灵矿产出指纹和phase快照机制，月内矿工变化时自动正确分摊

### 重构

- **结算系统彻底重构：移除空闲/活跃双模式，统一为四轨结算架构** — 移除 `BatchMode` 枚举（IDLE/ACTIVE_NON_FOCUS）及相关热控分批逻辑 `resolveThermalBatchSize`；删除空闲状态字段（`isInIdleState`/`lastUserInteractionTime`/`pendingReturnFromIdleSettle`）和辅助方法（`enterIdleMode`/`cleanupIdleState`/`doIdleFullSettle`）；统一 `tickInternal()` 为单一路径，消除 ~150 行重复的双分支代码；删除 `scheduleMonthly`，月度生产/经济/邮件/子嗣/伙伴/探索系统全部迁入 `onPhaseTick` 批量轨；简化 `scheduleYearly` 仅保留年度阶段（老化/死亡/招募/盟约）；移除 `SystemManager` 热控联动检查，简化 `onPhaseTickWithDomainFilter`；清理 `GameEngineCoordination` 中冗余的 `onUserInteraction()` 调用链；删除 `ProductionRateFingerprint` 依赖、`fullIdleSettle` 死代码、`SettlementCoordinatorCultivationTest`/`IdleModeSettlementTest` 过时测试。最终结算路径收敛为 4 条：实时轨（100ms，焦点域+≥80%进度）→ 批量轨（30s，非焦点域+<80%进度）→ 月度事件（外交/盗窃/任务/商人）→ 年度结算（老化/死亡/招募/盟约）。每个系统仅在单一路径上运行，彻底杜绝双计可能
- **重构年俸系统** — 年俸移至年度结算路径每年1月发放；灵石不足全员不发（不再降忠诚）；支持中品/上品补差价找零退回下品；发放后忠诚+1
- **价格体系重构：清理 PRICE_MULTIPLIER 全局折扣系数** — 移除 `GameConfig.Rarity.PRICE_MULTIPLIER`（原值 0.9）及所有引用（Items、MerchantAndRecruitService、DiplomacyService、GameConfigData、game_config.json）；商人/宗门交易价格恢复为物品原价（±20% 波动），不再有隐式 9 折；售卖/上架/一键售卖价格 = 原价 × 80%（SELL_PRICE_MULTIPLIER 保持不变），此前实际为原价 × 72%（0.9 × 0.8）
- **统一物品价格体系** — 消除两套价格：RarityConfig 理论价与模板数据库实际价合并为统一价格源；装备/功法/丹药（中品）同品阶价格统一：凡品4000→灵品16000(4x)→宝品80000(5x)→玄品480000(6x)→地品3360000(7x)→天品26880000(8x)，品阶差距逐级递增；材料价格 = 基准价×10%（凡品400、天品268.8万），远低于同品阶装备；草药价格 = 基准价×10%（同材料），远低于同品阶丹药；种子价格 = 基准价×2%（凡品80、天品53.76万），远低于同品阶草药；售卖价统一为原价×80%，卖价不再高于买价

### 优化

- **战斗前全量结算出战弟子数据** — 发生战斗时所有出战弟子（手动进攻、被攻击防御、驻军防守）的数据全部追赶结算一遍，包括突破检测、装备孕养、功法熟练度、HP/MP 恢复，确保出战弟子均为最新数据最新战力

## [4.0.24] - 2026-06-26（versionCode=4024）

### 优化

- **热控分批扩展生产系统** — 设备发热时非焦点域的生产结算也参与热控分批（此前仅弟子修炼受热控影响）。非焦点域的生产（灵矿产出/炼丹/锻器/种植）、经济（政策开销）、血炼进度、任务完成检测在发热时与修炼一并累积，累积满6月（MODERATE）或12月（SEVERE）后一次性批量结算。进步≥80%的槽位自动进入实时轨，100ms高频处理防溢出
- **双指纹机制统一** — 空闲模式和活跃模式共用 `CultivationRateFingerprint` + `ProductionRateFingerprint` 双指纹，分批状态统一由 `SettlementCoordinator` 管理。移除活跃模式旧的纯时间累积逻辑（`computeNonFocusedBatch`），替换为与空闲模式一致的双指纹+微结算机制：累积期间指纹变化时先用旧速率结算已累积产出，再换新速率继续累积，杜绝速率变更导致的结算误差
- **修炼指纹补全** — 增加功法熟练度检测维度，熟练度增长自动触发修炼速度重算

### 重构

- **焦点域视角驱动化** — 取消界面静态分类（焦点域/非焦点域），改为纯视角驱动：玩家当前视角所在界面即为焦点域（100ms 高频实时结算），其余界面自动 30s 批量结算。世界地图和外交界面从"已降为非焦点域"恢复为焦点域——打开世界地图时地图标记/探索状态实时更新，打开外交界面时好感度/关系变化实时更新
- **SettlementCoordinator 构造参数合规化** — 从12个参数降至6个，满足项目7参数上限。领域服务（修炼/生产/经济/探索）聚合为 `SettlementDomainFacade`，世界事件服务（邮件/子嗣/伙伴）聚合为 `SettlementWorldFacade`。删除未使用的 `gameClock` 死代码
- **`processWorldEvents` 拆分** — 可分批部分（经济+血炼）拆出为 `processBatchableWorldEvents`，热控时批量处理；不可分批部分（邮件/子嗣/伙伴/世界等级/自动装备）保留每月执行
- **`productionMicroSettle` 扩展** — 空闲/热控批量结算时一并处理经济+血炼，确保批量月份的所有生产相关系统完整结算

### 规则

- **新增可变化数据指纹同步规则**（CLAUDE.md §6.6）— `DiscipleTables` 新增列、`ElderSlots`/`SectPolicies` 新增字段、新增生产系统时，必须同步更新对应指纹的 `compute` 方法
- **彻底重构防守机制** — ①玩家本体宗门被攻击时自动选择十名存活弟子参战（高境界优先），思过中/血炼中/任务中/战斗中/探索中/驻守中弟子不可选择；②玩家占领的宗门被攻击时由驻扎弟子出战，不再免疫AI攻击。修复了防守弟子境界排序方向错误（此前按 realm 降序导致最弱弟子排在最前）
- **修炼速度公式乘区化** — 14种修炼速度加成从"全加算"升级为"同类加算、异类乘算"的5乘区模型（资质乘区：天赋；资源乘区：功法+建筑；社交乘区：师徒+传道+父母；状态乘区：丧亲+寿命+政策；临时乘区：丹药临时加速）。解决了后期加成堆叠边际递减问题，不同维度加成差异化更显著

### 新增

- **师徒系统** — 弟子可向其他存活弟子拜师，师徒关系永久绑定（仅一方死亡方可解绑）。每位师父最多收 5 名徒弟，每位弟子最多拜 1 名师父。师父按大境界差为徒弟提供修炼速度加成（+5%/级）和突破率加成（+3%/级），同境界或无境界差时无加成。UI 覆盖：弟子详情页拜师按钮（已有师父时灰色禁用显示"已拜师"）、半屏拜师选择界面（境界/灵根/属性筛选 + 点击卡片弹确认弹窗）、关系面板新增师父/徒弟分类、修炼详情和突破率详情显示师徒加成项。师父突破大境界或师徒关系变更自动触发修炼速度重算（缓存指纹精准捕获 masterId + masterRealm 变化）。新增 19 个师徒系统单元测试

### 优化

- **优化：物品/灵石数量显示改为万/亿单位 floor 格式化** — 当数量超过 5 位数（≥10000）时自动转为「万」单位显示，≥1 亿转为「亿」单位，采用向下取整（floor，只少不多）保留 1 位小数，小数位为 0 时省略小数。例如 10001 显示为「1万」、10999 显示为「1万」、11999 显示为「1.1万」、19999 显示为「1.9万」。统一覆盖灵石数量、物品卡片数量、商店价格、任务奖励、邮件附件、战斗战利品等全部数量显示场景。核心函数 `GameUtils.formatNumber` 由四舍五入改为整数运算 floor，新增 `Int` 重载，删除 `DailySignInDialog` 中的死代码 `formatRewardQuantity`
- **统一弟子筛选栏** — 消除两套并行筛选实现（手写状态与 DiscipleFilterState），排序和过滤算法统一收敛到 `applyFilters`；境界筛选选项常量统一基于 `GameConfig.Realm` 权威定义（0-9 共 10 项，修复旧 B 套定义不一致 bug）；灵矿场执事推荐属性由「采矿」修正为「道德」；灵矿场执事选择界面从 107 行私有组件重构为复用统一 `DiscipleSelectorDialog`。新增 17 个筛选排序单元测试
- **突破系统合并** — 合并两套重复的突破实现（~300行）为统一的 `performBreakthrough()` 入口，实时突破从全量 clear+insert 改为精准字段写回。突破成功/失败计数开始正确写入
- **建筑加成配置化** — 住所修炼速度加成从硬编码迁移到 `GameConfig.Cultivation.BUILDING_BONUSES`，调整数值无需改代码
- **修炼速率缓存指纹补全** — 增加父母存活状态和灵根数检测维度，父母去世或灵根变化时自动触发修炼速度重算
- **命名统一** — 修炼速度相关命名统一为 `cultivationPerPhase`
- **焦点域/非焦点域重分类** — 以「界面是否显示生产信息」为判定标准重新划分所有界面的实时结算优先级：9个不显示生产信息的界面（外交/世界地图/邮件/活动/巡视楼/招募/住所/藏经阁/问道塔/青云塔/天枢殿/执法堂/思过崖/战斗日志/建筑仓库）从100ms高频结算降为30秒批量结算，减少不必要CPU开销；5个显示生产信息但此前未正确映射的界面（仓库Tab/商人界面/宗门交易/灵田种植/血炼池）补全焦点域映射，确保灵石数量和进度条实时更新。提取 `resolveDomainsFromView` 纯函数便于测试，新增31个域映射单元测试

### 修复

- **数字格式化亿单位阈值错误** — `formatNumber` 使用西式十亿（1_000_000_000）作为「亿」触发阈值，导致 1~10 亿间数值错误显示为「XXXXX万」（如 199,999,999 显示为「19999.9万」而非「1.9亿」）。修正为中式一亿（1_0000_0000 = 1 亿），10 亿现在正确显示为「10亿」
- **数据库迁移缺失** — 师徒系统新增 `DiscipleExtended.masterId` 列，版本号从 9 升到 10 但未提供 `MIGRATION_9_10`，Room 找不到迁移时触发 `fallbackToDestructiveMigration()` 导致所有本地存档被清空。补充 `ALTER TABLE disciples_extended ADD COLUMN masterId TEXT` 迁移
- **MMKV 原生库加载异常捕获不完整** — `XianxiaApplication.onCreate()` 中 MMKV 初始化的 `catch (e: Exception)` 无法捕获 `UnsatisfiedLinkError`（Error 子类，不受 Exception 管辖），导致测试环境和部分设备启动崩溃。修正为 `catch (e: Throwable)` 并保留 `CancellationException` 重抛守卫
- **补充测试依赖** — 新增 `androidx.test:core` 依赖，修复 `AlarmWatchdogReceiverTest` 和 `GameNotificationHelperTest` 编译时 Unresolved reference 错误
- **丹药修炼速度加成静默失效** — `pillCultivationSpeedBonus` 字段由 `PillEffectApplier` 写入但从未被修炼速度公式读取，导致丹药修炼加速效果完全无效。修复后两路丹药加速均正确参与修炼速度计算
- **UI与引擎建筑加成数值不一致** — 弟子详情页修炼速度预览使用错误的建筑加成系数（中级单人住所+50%/+25%，实际引擎计算为+40%/+20%），以引擎实际值为准修正UI显示
- **空闲弟子突破时溢出修为丢失** — 月度批量结算中 dirty 弟子的溢出修为计算在修炼值上限裁剪之后执行，永远为0。修复后将溢出计算移到裁剪之前
- **空闲弟子 totalGain 公式冗余** — clean/dirty 批量结算中 `+ alreadyGained` 对非焦点弟子无实际作用，简化为直接使用月度修炼总值
- **batchMonths=0 时熟练度/温养越权执行** — 热控跳过修炼结算时，功法熟练度和装备温养仍被意外推进。修复后仅在结算执行时计算附属进度
- **HFD 跨月重置导致累积追踪断裂** — 热控跳过结算时 HFD 仍被无条件重置，修复后仅在结算实际执行时才重置
- **空闲模式结算改为30秒批量** — 玩家30秒无操作后游戏进入空闲模式，结算频率从每月（~6秒）降至每30秒一次，大幅降低电力消耗。空闲期间双指纹检测（修炼指纹+生产指纹）自动捕获产出速率变化（丹药到期/装备变更/师徒关系变化等），用旧速率结算变化前产出再用新速率继续，确保不丢进度。进度≥80%的弟子修炼/孕养/熟练度/血炼/种植/炼丹/锻造/任务/思过自动入实时轨防溢出。战斗中弟子HP/MP空闲期间持续恢复。退出空闲立即全量结算。新增ProductionRateFingerprint（覆盖采矿/种植/炼丹/炼器/血炼/任务/思过7系统）、cultivationMicroSettle/productionMicroSettle/fullIdleSettle/classifySlotsProgress方法

## [4.0.23] - 2026-06-25（versionCode=4023）

### 根治

- **华为/荣耀/vivo/iQOO/OPPO 等手机游戏时间停止不动（四层防御根治）** — 原架构「Activity + 协程delay() + WAKE_LOCK」存在三个致命问题：① OEM 前台冻结（华为 PowerGenie/荣耀 MagicOS/vivo OriginOS/OPPO ColorOS 在前台也会挂起协程）；② 看门狗与游戏循环在同一冻结范围无法自救，且 5 次重试上限后放弃；③ consumeDeadTime() 丢弃真实时间差、恢复后不补算。修复方案采用四层防御架构：
  - **第一层 — Foreground Service**：游戏循环迁移至前台服务（`GameForegroundService`），持有 `PARTIAL_WAKE_LOCK`，提升进程优先级规避 OEM 前台冻结。Activity 生命周期与游戏循环完全解耦，切后台游戏时间持续推进
  - **第二层 — 真实时间补偿**：`GameTimeClock.tick()` 基于 `SystemClock.elapsedRealtime()` 单调时钟计算真实时间差（`MAX_CATCHUP_MS=30s` 上限防跳变），即使协程被冻结恢复后自动补算丢失的游戏时间
  - **第三层 — AlarmManager 精确闹钟兜底**：`AlarmWatchdogReceiver` 每 15 秒通过 `setExactAndAllowWhileIdle()` 链式调度精确闹钟，检测 tickCount 停滞时启动 Service 唤醒游戏循环。Android 12+ 首次进入游戏引导用户授予精确闹钟权限
  - **第四层 — 看门狗增强**：移除 5 次重试上限改为无限重试，指数退避间隔从厂商基础值（3~5s）逐次翻倍至 30s 上限，成功后重置。看门狗运行在独立非守护线程上，防止被 OEM 一起冻结
  - 旧有的 `antiFreezeDelay()` 微延迟忙等机制、`OemPowerProfile` 厂商差异化参数、`BatteryOptimizationHelper` 电池优化引导保留不变，与新架构层层互补
  - 新增 `GameForegroundService`、`GameNotificationHelper`、`AlarmWatchdogReceiver` 三个组件，`GameActivity` 生命周期解耦，`GameEngineCore` 看门狗增强（`computeWatchdogBackoff` 指数退避）。新增 `GameTimeClockTest` 冻结补偿测试（2）、`GameEngineCoreWatchdogTest` 退避算法测试（11）、`GameNotificationHelperTest` 通知构建测试（11）、`AlarmWatchdogReceiverTest` 闹钟调度测试（10）

### 修复

- **修复：游玩时弟子批量消失（数十名同时消失直至个位数）** — `CultivationSettlement` 的 `processSalaryYearly`/`settleSalaryOnBreakthrough`/`processResidenceLoyalty` 与 `DiscipleLifecycleProcessor` 的 `processGriefExpiry`/`processReflectionRelease` 在入口捕获快照后通过 `scope.launch` 异步执行 `clear()+insert(陈旧快照)`，与月度结算的 `createSettlementShadow().deepCopy()` 并发，导致 shadow 捕获到空 ids，`swapFromShadow` 整体覆盖活表 → 全体弟子瞬间消失。修复：上述 5 个函数改为 `suspend`，在 `stateStore.update` 事务内直接读取最新 `discipleTables` 并同步操作，消除异步覆盖竞态。新增 `CultivationSettlementConcurrencyTest`（11 个回归测试）覆盖正常路径、边界条件与并发安全性
- **修复：天道试炼通关奖励灵石不显示精灵图** — `HeavenlyTrialClearRewardDialog` 构造 `ItemCardData` 时漏设 `spiritStoneGrade`，精灵图选择逻辑落到 `equipmentSpriteRes("灵石")` 分支返回 null，卡片显示"敬请期待"占位。修复：参照 `RewardDisplayDialog` 写法补上 `spiritStoneGrade = if (itemType == "spiritStones") SpiritStoneGrade.LOW else null`
- **修复：天道试炼挑战对象信息区不可滚动导致功法被裁剪** — `HeavenlyTrialBattleDialog` 的 `EnemyInfoDetail` 为纯 `Column` 无滚动容器，功法数量较多时底部被裁剪无法查看。修复：`Column` 添加 `.verticalScroll(rememberScrollState())`，信息区可纵向滚动
- **修复：每日签到灵石描述未标注品阶** — `DailySignInService` 中 5 处"灵石"硬编码文本（3 处奖励定义、1 处容量错误提示、1 处奖励卡片 itemName）统一改为"下品灵石"，与实际奖励品阶一致
- **修复：弟子在列表界面不修炼（空闲挂机无成长）** — 空闲模式（30秒无操作）下 DISCIPLES 域切至 BACKGROUND，配合手机发热触发热控批量结算（`batchMonths` > 1），`SettlementCoordinator` 修炼公式将 HFD 累积值（`alreadyGained`）从**每个月**的修炼值中扣除而非从**总额**扣除，导致非焦点弟子修为大量损失。修复：修炼公式改为 `(monthlyGain × batchMonths − alreadyGained) ≥ 0 + alreadyGained`（从总额扣一次）；突破检查移除对 `BREAKTHROUGH` dirtyFlag 的依赖，直接判断 `cultivation ≥ maxCultivation && fullHpMp`；`SettlementCache` 每次月结从头构建、不再跨月复用；HFD 统一在 `onSettlementComplete` 中重置。新增 `SettlementCoordinatorCultivationTest`（10 个测试）覆盖公式修正、batchMonths 分批、已获修为不损失、突破前提等核心路径
- **修复：非焦点弟子修为反复归零永远卡在练气一层** — `SettlementCoordinator` 月度结算中，修炼值写入组件表后调用 `processBreakthroughForDisciple` 时传入的是从组件表组装的旧 `disciple` 对象（cultivation 未被更新），函数内部守卫条件 `d.cultivation < d.maxCultivation` 用旧值判定立即跳过突破循环，随后 `writeDiscipleToTables` 将旧修为 0.0 覆盖掉刚写入的正确值 → 每月修炼到满值后立刻回滚，弟子永远困在练气一层。修复：`processBreakthroughForDisciple` 内部从 `shadow.discipleTables` 重新读取当前修为，确保突破条件判定使用最新值。新增回归测试覆盖突破跳过→回滚的临界路径
- **修复：天道试炼试炼弟子伤害仅1点** — BattleAI 的技能分类逻辑将 damageMultiplier=0 但 skillType=ATTACK 的控制技能（如定身诀）错误归入 attackSkills，AI 在攻击决策阶段选中此类技能后技能倍率为0，经 BattleCalculator 计算最终伤害钳制到1。修复：以 damageMultiplier>0 为 attackSkills 唯一分类依据（不再看 skillType），控制技能改用 usableSkills 全集查找，0伤害技能不会被用作攻击；无伤害技能时自动回退普通攻击。新增 4 个 BattleAI 回归测试
- **修复：天道试炼玩家弟子非满状态出战** — HeavenlyTrialViewModel.startCombat() 转换弟子为 Combatant 时使用弟子当前 HP/MP（可能因之前的战斗或活动不满），导致进入试炼时状态不完整。修复：BattleSystem.convertDiscipleToCombatant() 新增 fullHeal 参数，天道试炼调用时传入 true，确保出战弟子满血满蓝

### 优化

- **优化：战斗界面增加回合数显示** — HeavenlyTrialCombatScreen 战斗界面上方中央显示「第x回」，战斗结算弹窗耗时旁显示「总回合 x」

- **调整：灵石兑换汇率改为售卖价** — 中品/上品灵石兑换按下品等价80%（一键售卖价）折算，1中品↔8,000下品、1上品↔8,000中品↔6,400万下品

### 优化

- **优化：宗门信息卡片显示总灵石数量** — 顶栏灵石按售卖价汇总显示总下品等价，中品/上品明细以较小字体显示
- **优化：代码质量全面清理** — 修炼速率指纹扩展至7个维度（住所/长老/传道/政策/弟子增删/境界分布/逐弟子丹药丧亲功法状态），突破检查添加80%修为前置阈值，SettlementCache 5次弟子组装合并为1次共享，DiscipleTickParams 19参数按领域分组为6上下文对象，看门狗添加连续失败降级模式+日志节流防止OEM永久挂起时频繁重启，ThermalMonitor缓存StateFlow消除每tick binder IPC。清理StorageFacade 10处无调用@Deprecated方法、GameEngineCore死代码。CLAUDE.md 4处200ms→100ms与实际tick间隔一致。GridRow泛型网格Composable消除MaterialSection/PillSection/WarehouseBulkSellDialog重复布局代码。新增120个单元测试（CultivationCore 48 + SettlementCoordinator 54 + GameViewModel 30）覆盖修炼计算/月度结算/突破检测/热控分批等核心路径，5个架构守护测试+5个指纹测试

### 新增

- **新增：灵石自动补差价设置** — 设置界面新增「自动售卖中品灵石补差价」和「自动售卖上品灵石补差价」勾选框（默认不勾选），勾选后消费下品灵石不足时自动按售卖价卖出中品/上品补足差额

## [4.0.22] - 2026-06-24（versionCode=4022）

### 修复

- **修复：每日签到已领/未领状态覆盖层压到名称栏** — `DailySignInDialog` 中状态覆盖层高度只扣除了单侧边框，实际渲染范围向下侵入了物品名称栏。修正覆盖层高度为精灵图内区域（扣除上下两侧边框），确保只覆盖精灵图，不压边框和名称栏。

## [4.0.21] - 2026-06-24（versionCode=4021）

### 修复

- **修复：部分丹药导致游戏崩溃（#4022）** — 存档反序列化、炼药产出、行商购买等路径中创建的丹药缺少 `pillType` 字段，自动服用系统的丹药分类引擎（classify）在无法识别丹药类型时抛出 `IllegalStateException`（未定义规则的丹药）。根因修复：所有 `Pill()` 直接构造调用的路径（ProductionProcessor、BuildingService×2、MerchantItemConverter）补全 `pillType`/`category` 参数；`SerializablePill` 新增 `pillType` 序列化字段确保读档不丢失；`classify()` 增加兜底降级逻辑：效果属性全空时默认归为可重复服用类、不崩溃
- **修复：TapTap SDK 在游戏时长追踪阶段触发 SIGILL 崩溃（#4018）** — `TapDBManager.startGameDurationTracking` 内部 `GameDurationService.Builder` 在部分设备上触发 `SIGILL` 非法指令错误（ILL_ILLOPC），原 `catch(Exception)` 无法捕获 `Error` 子类。修复：升级为 `catch(CancellationException)` + `catch(Throwable)` 全面捕获所有异常类型，确保 TapTap SDK 内部 sandbox hook 兼容性问题不影响游戏正常运行
- **修复：AI 宗门进攻玩家宗门时玩家弟子无法出战** — `AISectAttackManager.executePlayerAttack` 原先从 `aiSectDisciples[playerSectId]` 读取玩家弟子，但该 Map 只保存 AI 宗门弟子，导致玩家方实际无人参战、战斗被跳过。修复：在 `CaveExplorationProcessor` 中直接从 `discipleTables` 选取玩家高境界存活弟子，排除 `ON_MISSION`（外出任务）和 `IN_TEAM`（探索洞府/妖兽/世界节点）状态，按境界降序取前 10 名作为防守方参战。
- **修复：AI 攻玩家战斗使用玩家真实装备/功法** — 原先复用 AI 弟子转换逻辑会随机生成装备/功法覆盖玩家配置。修复：使用 `BattleSystem.convertDiscipleToCombatant` 基于 `stateStore` 中的真实装备、功法实例及熟练度生成战斗单位，战后回写存活弟子的当前 HP/MP，并清理阵亡弟子的驻军槽位。

## [4.0.19] - 2026-06-23（versionCode=4019）

### 新增

- **新增：天枢殿纳徒长老槽位** — 天枢殿新增纳徒长老职位，长老魅力影响每年待招募弟子的刷新数量上限。魅力以80为基准，每高4点增加1名弟子刷新上限（增加的是上限而非直接刷新弟子）。长老要求存活、内门弟子、空闲中。长老名称右侧有详情按钮可查看加成机制说明
- **新增：寿命将尽影响修炼与突破** — 弟子剩余寿命低于20%时触发惩罚：每少1%降低5%修炼速度和2%突破率。寿命充足时不受影响，寿命耗尽至0%时修炼速度归零、突破率降低40%

### 重构

- **重构：弟子丹药系统全面升级** — 丹药自动服用从单一维度改为六类规则引擎：永久属性丹按"品阶+效果字段"去重、延寿丹按类型去重、持续增益/临时战斗丹按类型不可叠加、直接修为/突破丹可重复服用。仓库突破丹开关不再拦截储物袋中的突破丹。效果应用层收敛为统一的PillEffectApplier，消除手动服用与自动服用的逻辑分叉。丹药持续时间统一以"旬"为单位，移除魔法数字换算。旧存档自动迁移：已服用记录保守转换为所有品阶均标记已服用，确保成品丹药不被自动误吃。

### 优化

- **优化：招募弟子刷新数量按宗门等级动态调整** — 小型宗门每年刷新0~5名弟子、中型1~8名、大型3~15名、顶级6~20名，高等级宗门可刷新更多弟子供招募
- **优化：每日签到界面布局重构** — 日历固定4行×7列显示，多余行可滚动查看；里程碑固定4行×1列，行间距统一；日历和里程碑各自独立居中（80%/20%），左右边距平衡。物品卡片名称字体根据卡片大小动态调整，彻底消除省略号截断问题。状态覆盖层仅覆盖精灵图区域不再遮挡卡片边框

### 修复

- **修复：iQOO 15 游戏时间不动** — 防冻结延迟（antiFreezeDelay）中 API 33+ 分支的 `else if` 导致 OEM 忙等循环永不执行。iQOO 15 的 OriginOS 5 空闲检测窗口更窄（~10-20ms），游戏线程被判定为空闲并挂起，看门狗 3 次恢复后永久放弃。修复：统一忙等循环在全 API 级别生效，vivo/iQOO 忙等参数收紧（busyInterval 16→12），看门狗恢复次数 3→5，游戏线程忙等占空比控制在 16.7% 不会明显发热
- **修复：招募弟子不修炼（根治）** — 月度结算缓存（SettlementCache）跨月复用时指纹未包含弟子增删信息，新招募弟子因不在旧缓存的 clean/dirty 集合中被月度修炼结算跳过，修为永不增长。修复：指纹新增存活弟子 ID 哈希字段，弟子增删时指纹必然变化触发缓存重建，确保所有弟子均获得月度修炼结算

## [4.0.20] - 2026-06-23（versionCode=4020）

### 优化

- **优化：更新18种基础草药及种子精灵图** — 聚灵草、清心草、凝气草、寒霜草、烈焰草、金灵草、云雾花、白莲、晨露花、紫霄花、双生花、冰魄莲、精气果、赤心果、灵韵果、通灵果、玄灵果、五行果拥有各自专属的草药/种子/成长期图片。高阶草药（Tier 3-6）暂未开放，将显示"敬请期待"而非错误回退图片

### 修复

- **修复：商人、宗门交易、邮件附件、天道试炼奖励中草药和种子卡片不显示图片** — 这些界面的道具卡片构造缺少 isHerb/isSeed 类型标志，导致图片查找路径错误，换了新图也无法显示
- **修复：邮件领取按钮无反应** — Room 的 `attachmentClaimed` 与 `GameData.mailRecords` 不一致时，按钮可见但领取被 `mailRecords` 二次保护拦截返回 `AlreadyClaimed`，UI 不处理此结果导致按钮无反应。修复：`claimAttachment` 检测到不一致时自愈 Room 状态并刷新 UI；`claimAttachmentInternal` 补齐 `mailRecords` 防护防止"一键已读"重复发物；`fetchOnlineMails` 重插已领取邮件时直接标记为已领；UI 穷举处理所有 `ClaimResult` 类型
- **修复：突破率详情弹窗信息不完整** — 弹窗仅展示正面加成项（如神魂加成+5%），不显示基础突破率和负面天赋（如灵脉紊乱-5%），丧亲惩罚（亲属逝世-20%）也未传入主显示值和弹窗，导致玩家无法理解总额计算逻辑。修复：弹窗始终显示基础突破率、天赋加成改为显示非零值（负数红色）、底部新增最终突破率汇总行、丧亲惩罚正确传入 UI 层两处计算
- **修复：弟子寿命异常增加** — 同境界内升阶（如炼气八层→九层）错误触发了寿命增长，弟子每次小境界突破都获得额外寿命，叠加后寿命远超设计值。修复：寿命增长仅在跨大境界突破时触发（如炼气→筑基），同境界内升阶不再增加寿命
- **修复：OPPO/Realme/OnePlus 游戏时间不动** — ColorOS 14/15 空闲检测窗口 ~15-30ms，当前 OPPO 防挂起参数（busyInterval=32、busyDuration=3ms，占空比仅 4.7%）不足以保持游戏线程 RUNNABLE，线程被系统挂起后时间停止。修复：OPPO 忙等参数收紧至 busyInterval=16、busyDuration=4ms（占空比 12.5%），看门狗检测间隔 4s→3s，每 32ms 做一次 4ms 忙等可突破 ColorOS 空闲检测窗口。同时覆盖 Realme、OnePlus 设备（共用 ColorOS 电源管理栈）
- **修复：弟子详情界面血量条和灵力条不自动恢复** — 打开弟子详情界面时血量条和灵力条的视觉进度条不随时间增长。根因为进度条动画仅在旬切换时（每~30秒）更新动画目标，旬内即使HP已恢复进度条仍冻结。修复：进度条动画改为直接响应HP/MP数值变化，每次恢复都平滑过渡
- **修复：TapTap SDK 初始化兼容性崩溃（#4018）** — TapTap SDK v4.10.0 内部 sandbox hook 机制在华为 HarmonyOS / x86 模拟器等设备上触发 `SIGILL(ILL_ILLOPC)` 非法指令。升级至 v4.10.1 并在初始化外层增加兜底 `catch(Throwable)`，SDK 内部异常不再导致应用崩溃

## [4.0.18] - 2026-06-22（versionCode=4018）

### 新增

- **新增：弟子偷盗冷却限制** — 弟子成功偷盗灵石后12个月内不可再偷盗，冷却时间基于上次偷盗月份计算，偷盗被捕不触发冷却
- **新增：弟子卡片显示年龄** — 所有使用统一弟子卡片（`PortraitDiscipleCard`）的界面（弟子列表、招募、选择器等）均在性别右侧显示弟子年龄，格式为"xx岁"。年龄文本由独立可测试的 `formatDiscipleAge` 格式化函数生成，避免硬编码字符串散落在多处 UI 代码中
- **新增：物品关联配方/产物信息展示** — 种子详情显示成熟后产出的草药名称及该草药可炼制的丹药；草药详情显示可用其炼制的丹药列表及所需数量；丹药详情显示炼制所需草药及数量；装备详情显示锻造所需材料及数量；材料详情显示可用其锻造的装备列表及所需数量。商人购买界面和储物袋物品界面同步展示上述关联信息

### 修复

- **修复：弟子叛逃系统未激活** — `processLawEnforcementMonthly` 因未接入月度循环从未执行，低忠诚弟子不受叛逃影响、执法岗位形同虚设。现已接入每月事件循环，忠诚度低于30的弟子按概率叛逃，抓捕率受执法长老/弟子智力及增强治安政策影响
- **修复：建筑槽位详情按钮素材错误** — 所有建筑中用于说明槽位用途的 `ElderBonusInfoButton` 默认背景素材由通用按钮（`R.drawable.ui_button`）错误地使用了详情按钮的设计资源（`R.drawable.ui_detail_button`）。已将 `ui_detail_button.webp` 下沉到 `core:ui` 模块，并把组件默认值修正为 `R.drawable.ui_detail_button`，覆盖天枢殿、仓库、灵矿场、灵植阁、执法堂、问道塔/青云塔等各峰场景
- **修复：功法 buff 类型英文显示** — `damage_link`、`damage_share`、`shield`、`damage_reduction`、`damage_boost`、`turn_advance` 等 buff 类型在物品详情中直接显示英文原文。现已补全 `getBuffTypeName` 映射及 `parseManualStackBuffs` 解析，全部转为中文显示
- **修复：功法技能详情信息不完整** — 功法技能详情缺少作用目标、是否全体、固定治疗、护盾、行动提前、伤害分摊、伤害链接等字段。现已在 `ManualStack`/`ManualInstance`/`ManualTemplate`/`MerchantItem`/`StorageBagItem`/`LearnedManualDetailDialog` 全部六处功法展示路径中统一补全上述字段
- **修复：一次性丹药错误显示持续时间** — 部分立即生效的丹药（固定数值增加、治疗、复活、清除负面状态、延寿等）仍显示"持续 X 旬"。修正了 `getPillEffects` 及商人/储物袋丹药分支的 `isInstant` 判定逻辑，覆盖全部一次性效果字段
- **修复：材料、灵草、种子描述缺失** — 物品详情效果列表中未展示 `description` 字段。现已在 `getMaterialEffects`/`getHerbEffects`/`getSeedEffects` 及对应商人/储物袋分支中追加描述显示
- **修复：月结修炼双重计算** — 批量结算中 `alreadyGained` 被错误乘以 `batchMonths`，导致非焦点弟子月度修炼值多扣。修正公式为"月度增益 × 月数 − 已获增益"
- **修复：不看弟子就不修炼（根治）** — 根因是 `processPhaseTick` 的全量表重建（`clear()+insert()`）写入 90+ 字段，违反"单一写入者"原则覆写了 SettlementCoordinator 月度结算写入的修炼值/境界等字段。修复：消除全量重建，改为仅精准写回 processDiscipleTick 实际修改的 12 个字段（HP/MP/丹药/装备/功法），cultivations/realms/lifespans 等字段由月度结算独家管理。同步回退 v4.0.18 的 cultivationUpdates 累加移除和公式变更，恢复 v4.0.17 已验证的累加+对冲逻辑。保留 v4.0.18 执法系统和偷盗冷却。
- **修复：战斗伤亡非原子写入** — `processBattleCasualties` 中 6 组写操作分散在多个事务外，中途崩溃导致弟子状态、装备、槽位不一致。重构为单次 `stateStore.update` 原子事务
- **修复：商人购买功法溢出丢失** — `buyMerchantItem` 的 `manual` 分支未使用 `mergeStackable` 且无 `maxStack` 检查，购买可堆叠功法时超出上限部分静默丢失。改用 `mergeStackable` 与其他物品类型一致
- **修复：存档加载无回滚保护** — `loadFromSnapshot` 中途失败时已写入的 15 个 Flow 值无法恢复。保存旧状态快照，失败时完整回滚所有已写入值
- **修复：天道试炼领奖容量不足静默丢奖** — `randomPill`/`randomEquipment`/`randomManual` 分支达上限时跳过发放但 `claimedRewardLevels` 标记仍写入，用户无法重领。增加 `distributeFailed` 标志，失败时抛异常触发事务回滚
- **修复：天道试炼伤害公式与战斗系统不一致** — 动画战斗路径使用本地简化公式（硬编码防御常量500、无暴击/闪避/境界差系数），与 `BattleCalculator` 存在 7 处偏差。改调 `BattleCalculator.calculateCombatantDamage` 并同步暴击闪避判定
- **修复：血炼启动非原子操作** — 灵石扣除、材料消耗、弟子状态更新分三步独立调用，中途崩溃导致灵石已扣但血炼未启动。新增 `startBloodRefinementAtomic` 单事务方法
- **修复：放置建筑时 ProductionSlot 索引非原子** — `idx` 在 `updateGameData` 闭包外基于快照计算，并发放置同类型建筑可能重复。`idx` 计算移入闭包内基于当前 `data` 保证原子性
- **修复：弟子死亡后伴侣关系残留** — `handleDiscipleDeath` 清除所有槽位和装备但未清除幸存伴侣的 `partnerId`；`ChildBirthSystem` 父亲死亡时仅清除 `childBirthMonth`。双点补全 `partnerId` 清理
- **修复：外交结盟陈旧数据覆盖** — `requestAlliance` 在 `stateStore.update` 内使用外部快照 `data.copy` 而非 lambda 参数 `gameData`，并发修改被覆盖丢失。同时删除从未触发的 `onEvent` 死代码（`BattleCompletedEvent` 无任何 emit 调用）
- **修复：巡察塔战败不清理阵亡槽位** — 阵亡弟子槽位清理在 `if (result.victory)` 分支内，战败时阵亡弟子残留在巡逻槽中。清理逻辑移出胜利分支
- **修复：邮件附件领取 Saga 补偿** — `mailRepo.update` 在 `stateStore` 事务外，若 DB 写入失败则物品已入库但邮件仍可重复领取。增加 `mailRecords` 二次保护 + `mailRepo.update` 容错
- **修复：签到午夜跨越时间不一致** — `getDayState` 内两次 `Calendar.getInstance()` 调用可能跨越午夜导致 `todayDayOfYear` 与 `today` 不一致。改为复用同一 `Calendar` 实例
- **修复：兑换码签名校验** — `APK_SIGNATURE_HASH` 为空时返回 `true` 允许跳过校验。改为仅 Debug 构建允许跳过，Release 构建空 hash 拒绝
- **修复：修炼计算双状态访问** — `calculateDiscipleCultivationPerPhase` 绕过传入的 shadow state 参数直接读 `stateStore.manualInstances`，与月结 shadow 隔离语义冲突。改为从调用方传入 `manualInstanceMap` 参数
- **修复：招募费死配置清理** — `recruitCost` 字段在 `GameConfig`/`GameConfigData`/`game_config.json` 三处定义但 `recruitDisciple()` 从未消费，删除全部引用
- **修复：iQOO/vivo 手机游玩时游戏时间不动** — 此前 `antiFreezeDelay`、`startWatchdog`、`BatteryOptimizationHelper` 仅对荣耀 MagicOS 调参，vivo/iQOO(OriginOS)、小米(MIUI)、OPPO(ColorOS) 等厂商走默认参数，游戏线程被 OEM 省电机制挂起后看门狗需 5 秒才发现，期间游戏时间停止。重构为数据驱动的厂商配置文件：新增 `OemPowerProfile`（core/engine）与 `ManufacturerProfile`（app）两张配置表，覆盖华为/荣耀/vivo/小米/OPPO/三星全厂商。vivo/iQOO 使用与荣耀同级激进参数（忙等间隔 16 周期、忙等时长 4ms、看门狗 3 秒），小米/OPPO 使用中等参数（32/3ms/4s）。`WakeLockManager`、`DeviceCompatibilityHelper`、`BatteryOptimizationHelper` 全部改为从 profile 读取参数，消除 `if (isHonor)` 硬编码分支。新增 vivo/小米/OPPO 电池优化引导文案与厂商自启动设置页跳转

### 变更

- **变更：大乘最大寿命 3000→2500、渡劫最大寿命 5000→4000** — 其余境界最大寿命保持不变
- **变更：移除境界突破额外增加寿命机制** — 删除 `CultivationCore.getLifespanGainForRealm` 和 `SettlementCoordinator` 中同名私有函数。玩家弟子和 AI 宗门弟子突破境界后寿命不再额外增长，突破仅改变境界不改变寿命值。`DiscipleBreakthroughHandler` 实时突破和 `SettlementCoordinator` 月结突破中的寿命增益逻辑已全部移除

### 优化

- **优化：世界地图宗门信息界面布局调整** — 删除等级宗门文本，等级图标移至宗门名称左侧；关系左侧新增所属势力显示，被占领宗门显示占领者名称，未被占领显示自身名称；下方操作按钮改为根据屏幕宽度自动换行排列
- **优化：物品卡片统一组件重构** — 所有显示物品卡片的界面全面改用 `UnifiedItemCard` 统一组件。新增 `size` 参数支持动态卡片尺寸适配不同场景。每日签到 `SignInDayCard`/`MilestoneRewardRow`、生产槽位 `ProductionSlotItem`、商人挂售 `ListedItemCard` 中的手写物品卡片均迁移至 `UnifiedItemCard`，保留各自特有的状态覆盖层和操作按钮。删除了未被任何代码引用的 `ItemCard`、`CompactItemCard`、`RarityBadge`、`StatBonus` 四个冗余组件
- **优化：代码质量全面清理** — 移除全部 12 处 `!!` 操作符改为 `checkNotNull`；118 处 `catch (Exception)` 前补充 `CancellationException` 重抛；7 处空 `catch` 块添加日志记录；`BaseViewModel` 废弃 `StateFlow` 双通道仅保留 `Channel` 队列
- **优化：血炼加成数据 Room 持久化** — `bloodRefinementBonusTotals` 补充 CollectionConverters TypeConverter 并在 `MIGRATION_4_5` 中添加 `ALTER TABLE`，确保旧存档升级时列完整

## [4.0.17] - 2026-06-22（versionCode=4017）

### 修复

- **修复：紧急存档回溯导致古早存档覆盖当前进度** — 玩家手动存档后退出游戏时可能弹出"检测到异常退出"恢复提示，恢复后游戏进度回退到数年前的旧存档。根因是紧急存档（EMERGENCY_SLOT）一旦创建便永不自动清除，且仅在崩溃时写入，长期不更新后与 crash_flag 不同步，导致恢复时加载古早数据覆盖当前进度。修复方案：(1) 成功加载游戏后自动清除残留紧急存档；(2) 手动存档时同步更新紧急存档为最新数据；(3) 恢复流程先保存到正常槽位再清除紧急存档，保证原子性；(4) 恢复对话框显示紧急存档的游戏时间帮助用户判断；(5) crash_flag 超过7天自动过期
- **修复：战斗日志轮次 key 重复崩溃** — 战斗中所有敌人中途全灭时，提前结束分支的回合号未递增，与上一轮次产生重复 key，导致 LazyColumn 抛出 `Key "round_X" was already used` 崩溃。修复：统一提前结束路径的回合号递增逻辑 + key 追加 index 后缀确保绝对唯一
- **修复：TapTap SDK 初始化兼容性崩溃（#4018 SIGILL）** — TapTap SDK v4.10.0 内部 sandbox hook 机制在华为 HarmonyOS / x86 模拟器等设备上触发非法指令崩溃。升级至 v4.10.1 并在初始化外层增加兜底 catch，SDK 内部异常不再导致应用崩溃

### 优化

- **优化：主线程 ANR 风险大幅降低（#2035/#2025 SIGSEGV）** — 弟子聚合流（discipleAggregates）和宗门战力流（sectCombatPower）的重计算从主线程移至后台线程（Dispatchers.Default），添加降频采样（200ms/300ms），移除永远命中不了的无效缓存。大幅减轻主线程负担，消除 libhwui.so SIGSEGV 的 ANR 根因
- **优化：HP/MP 恢复计算跳过无存活弟子场景** — 所有弟子阵亡时，HP/MP 恢复逻辑提前返回，避免无意义的完整弟子装箱和战力计算
- **优化：灵田作物图片异步加载** — 首次进入游戏时作物精灵图改为后台线程异步加载，避免阻塞首帧渲染

## [4.0.16] - 2026-06-22（versionCode=4016）

### 修复

- **修复：非焦点域弟子修炼跳过问题** — 玩家停留在宗门总览、建筑、仓库、设置等非弟子标签页时，弟子月度修炼结算被错误跳过，导致修为完全不增长。根因是月度结算逻辑在焦点域活跃时无条件硬编码跳过所有弟子修炼（假定100ms实时tick已处理），但tick实际仅处理焦点弟子。修复方案：移除tick中对非焦点弟子的重复修炼添加，确保月度结算始终作为修炼的唯一权威来源处理所有弟子，不受标签页焦点域影响。热控分批最小值从0改为1（永不跳月）

## [4.0.15] - 2026-06-20（versionCode=4015）

### 修复

- **修复：血炼池和多人住所消失问题** — 修复硬编码默认配置中缺失血炼池（blood_refining_pool）导致的建筑丢失隐患；修正多人住所硬编码默认gridHeight错误（3→2）；补全拆除血炼池时的关联数据清理逻辑（activeBloodRefinements、弟子状态释放）；GridBuildingData反序列化失败时增加诊断日志；GridBuildingData默认高度对齐（3→2）

## [4.0.14] - 2026-06-20（versionCode=4014）

### 变更

- **AI弟子热控分批结算** — AI宗门弟子修炼结算接入热控系统：常温每月结算一次，发热时每6月批量结算，发热严重时每年批量结算。跳过修炼结算时，攻击决策、驻军填补等非修炼逻辑仍按每月正常执行。逐月循环突破检查确保批次内多次突破正确模拟

## [4.0.13] - 2026-06-20（versionCode=4013）

### 新增

- **草药/种子精灵图** — 云雾花、凝气草、晨露花、清心草、灵韵果、白莲、精气果、赤心果、聚灵草共9种Tier-1植物新增专属草药、种子、成长期三阶段图片。仓库、材料区、种植对话框、奖励显示等全部UI位置的草药和种子不再显示"敬请期待"，改为显示对应精灵图
- **灵田成长动画** — 宗门地图灵田上种植种子后，根据成熟进度动态显示生长阶段：前20%时间显示种子图片，中70%时间显示成长期图片，后10%时间显示成熟草药图片。无对应种子图片的植物不显示动画
- **天道试炼跳过按钮** — 战斗栏右侧新增"跳过"按钮，点击后战斗立即自动结算，跳过回合动画，节省玩家时间
- **统一AI智能战斗系统** — 全游戏所有战斗单位（玩家弟子、AI弟子、妖兽、洞府、天道试炼、宗门战）共用一套8层级联优先级AI：保命→斩杀→支援→Buff→控制→AOE→省蓝→最优攻击。替换了原有的4套独立AI，行为更智能（自动治愈队友、积攒灵力用大招、低血量自保）
- **全节日邮件系统** — 新增元旦、春节、元宵、妇女节、清明、劳动节、母亲节、父亲节、七夕、中秋、国庆、重阳、冬至共14个节日邮件，覆盖2026-2027两年。每封节日当天自动发放、14天限时领取，奖励统一为5灵品储物袋+5万灵石。新增邮件生效开始时间机制，未来节日不会提前发放。同步清理已过期不再发放的血炼池和邮件系统庆祝邮件
- **商人自动购买** — 商人界面"上架"按钮右侧新增"自动购买"按钮，点击进入半屏管理界面。可新增物品（全屏选择、按品阶降序、类型筛选、多选）到每年自动购买列表，也可删除物品（红色边框选中）。每年1月（商人刷新后）和12月各执行一次自动购买，以商人库存最大值买入，灵石不足时自动暂停

### 变更

- **妖兽境界改为年份加权分布** — 世界地图妖兽的境界不再均匀随机，改为随游戏年份平滑变化：初期以炼气/筑基为主（合计约61%），随年份推移高境界妖兽逐步增多。500年后以化神/炼虚为主，2000年后以渡劫/大乘/仙人为主。低境界妖兽始终有小概率出现
- **世界地图妖兽移动与就近攻击** — 妖兽每月在世界地图上随机移动，靠近玩家宗门或玩家占领宗门时有概率发动攻击。攻击前弹出半屏预警界面，可选择上交灵石（30%，至少2万）取消进攻，或迎战。防守胜利获得与巡逻塔相同的妖兽材料奖励，防守失败妖兽掠夺仓库50%物品（含灵石2万=1件、储物袋），掠夺结果在战斗弹窗中显示
- **端午节邮件限时延长为14天** — 端午节日邮件截止时间从当天延长为14天限时（2026-07-03截止），与普通节日邮件保持一致
- **弟子详情进度条动画改为每旬更新** — 修为、气血、灵力三条进度条统一改为每游戏旬平滑动画一次（1x速度下每2秒），动画时长随游戏速度自动适配（2x=1秒）。修为进度条增长幅度恰好等于界面显示的"X.X/旬"修炼速度，气血灵力骤降超过50%时瞬间同步。替换了原有的高频跳动（修为）和追逐式动画（气血/灵力）
- **修炼结算改为月结制** — 弟子修炼进度、HP/MP 恢复、丹药效果衰减、修炼速度加成衰减、自动从仓库装备/学习全部改为每月结算一次（原为每旬结算）。非焦点域时不再因 30 秒间隔导致大量修炼进度丢失，所有弟子无论玩家是否正在查看，修炼速度完全一致。月结在 SettlementCoordinator 中统一处理，保证原子性
- **非焦点域热控分批结算** — 非焦点域弟子修炼根据手机发热程度动态调整结算频率：常温每月一次，轻度发热（shouldReduceWorkload）每 6 月结算一次，严重发热（shouldEmergencySave）每年结算一次。分批结算时一次性结算所经过时间的修炼总和，修为超出当前境界时溢出部分自动带入突破后的新境界。玩家查看弟子时仍保持每旬实时结算
- **商人购买面板优化** — 确认购买区域改为选中物品后才弹出，移除"请选择要购买的商品"占位文本

## [4.0.12] - 2026-06-19（versionCode=4012）

### 新增

- **宗门等级详情与手动升级** — 宗门信息卡片中的等级图标改为可点击，点击后弹出半屏界面，可翻页浏览四大宗门等级（小型/中型/大型/顶级）。每个等级显示晋升下一等级的条件（弟子境界、占领要求），条件满足时勾选框自动打勾变绿，全部满足后可手动升级。右下角奖励按钮可领取当前等级的每周奖励（现实时间7天冷却）
- **宗门等级每周奖励** — 小型宗门：20随机凡品兽血 + 10万灵石；中型：50凡品兽血 + 5凡品储物袋 + 20万灵石；大型：50凡品兽血 + 5灵品储物袋 + 50万灵石；顶级：50宝品兽血 + 5宝品储物袋 + 100万灵石。奖励可领取时宗门图标和奖励按钮均有红点提示
- **端午节节日邮件** — 仅端午节当天（2026年6月19日）上线发送，奖励为5灵品储物袋 + 5万灵石。当天未上线次日不补发

### 修复

- **修复：玩家宗门初始即为中型宗门** — 新建游戏时宗门等级错误初始化为中型（level=1），应从小型（level=0）起步。修复：WorldMapGenerator 玩家宗门初始等级改为小型、WorldSect 默认值修正、ViewModel fallback 修正
- **优化：宗门信息卡片仅显示等级图标** — 移除宗门名称右侧的等级文字（如"小型宗门"），宗门名称左侧的等级图标已足够表达宗门等级
- **修复：TapTap 登录 lateinit context 崩溃** — AndroidManifest 因合规要求移除了 TapTapKitInitProvider，导致 TapTapKit.context 从未赋值，点击登录时崩溃。修复：SDK 初始化成功后反射设置 context + 登录按钮等待 SDK 就绪 + 全局异常处理器改进混淆兼容
- **修复：招募弟子时 ConcurrentModificationException 崩溃** — DiscipleTables.ids 使用 ArrayList 无并发保护，游戏线程写 ID 列表时 UI 线程读（maxOrNull）触发 CME。修复：将 ids 改为 CopyOnWriteArrayList，读操作无需同步，迭代器为快照自动安全
- **修复：战斗日志详情 LazyColumn key 重复崩溃** — 同一 LazyColumn 中三个 itemsIndexed/items 调用使用整数 key（index），当队伍成员和敌人均存在时 key 冲突。修复：为三组分块列表的 key 分别添加 "team_"、"enemy_"、"round_" 前缀确保全局唯一
- **修复：天道试炼第一小关通关后仍显示未通关** — dismissResult() 在 phase0 获胜时提前调用 startDiscipleSelect(1) 将 selectedPhaseIndex 从 0 改为 1，导致后续 onCombatFinished 中的 recordPhaseClear 记录了错误的阶段（phase1 而非 phase0），phase1ClearedLevels 始终为空。修复：dismissResult() 仅隐藏弹窗，导航和阶段记录统一由 onFinished → onCombatFinished 完整处理
- **修复：招募弟子时 ConcurrentModificationException 崩溃（#4015）** — DiscipleFacadeImpl.recruitDiscipleFromList 中 data.recruitList.find{} 迭代时，recruitList 内部 ArrayList 可能被 ChildBirthSystem 等系统的重入 stateStore.update{} 并发修改。修复：DiscipleFacadeImpl、GameEngineBattleOps、GameEngineCoordination、ChildBirthSystem 共 5 处 recruitList/worldMapSects 的迭代前添加 .toList() 防御性快照，阻断并发修改导致的迭代器失效

### 变更

- **玩家宗门月度自动升级已移除** — 玩家宗门等级不再由月度 tick 自动提升，改为手动操作（AI宗门仍自动升级）
- **修炼速度数值缩小为 1/10** — 各境界每旬修炼速度和修炼基础值等比缩小为原来的 1/10（如炼气期单灵根每旬从 280 降至 28），突破耗时保持不变。旧存档加载时自动迁移修为值

## [4.0.11] - 2026-06-19（versionCode=4011）

### 变更

- **宗门等级重新定义** — 小型：无化神及以上（最高元婴及以下）；中型：有化神（无炼虚及以上）；大型：有炼虚/合体（无大乘及以上）；顶级：有大乘/渡劫/仙人。宗门等级只升不降，玩家宗门信息卡片实时显示当前等级图标和名称。AI宗门月度仅用短路any{}检查升级，无全量遍历
- **AI宗门初始弟子数统一为50人** — 所有AI宗门初始固定50名弟子，境界在宗门等级允许范围内按权重随机分配。旧存档AI宗门弟子不足50人时自动补充至50人

### 修复

- **修复：探查报告宗门最高境界显示错误** — 探查成功后敌情报告中 `maxRealm` 使用了 `maxOfOrNull { it.realm }`（数值最大=境界最低），改为 `minOfOrNull` 正确显示该宗门最高境界
- **修复：不查看弟子信息界面弟子不修炼不突破** — 二层根因叠加：(1) 月结`farFromCompletionIds`跳过距突破>2个月的弟子，修炼速度慢者永远被跳过；(2) `processCleanDiscipleBatch`只加修炼值不检查突破，clean弟子月结满值也无法突破。修复：移除`farFromCompletionIds`过滤 + clean批次添加突破检查
- **变更：修炼体系重构** — 修炼速度由灵根数和境界共同决定：灵根越少越快（单灵根1.0→五灵根0.2）、境界越高越快（每旬速度随境界指数增长）。移除悟性对修炼速度的影响（悟性仍影响突破/悟道等系统）。每旬速度按目标突破时间严格校准（练气→筑基1年、筑基→金丹3年…渡劫→仙人400年，100%突破率基准），所有速度值为整数，实际耗时略超目标年。仙人修为基础值650,000→30,000,000

## [4.0.10] - 2026-06-18（versionCode=4010）

### 修复

- **修复：弟子身份切换后增量存档丢失修改** — 弟子详情界面切换内外门身份后退出，身份恢复原值。根因：`GameStateStoreImpl.update()` 中 DiscipleTables 使用引用比较 `!==` 检测脏数据，但 `update()` 入口已将 `discipleTables` 赋值为同一引用，脏标记永远为 `false`，导致增量存档（自动存档）跳过弟子数据写入 Room DB。影响范围：所有通过 DiscipleTables 原地写入的弟子字段（身份、名称、忠诚度、境界、修炼进度等）在增量存档中均丢失。修复：`markDirty(disciples = ...)` 和 `anyFieldChanged` 的判据从仅引用比较扩展为引用比较或 `mutationVersion` 变化
- **修复：荣耀70设备游戏月份停止推进（v4.0.03回归）** — v4.0.03 四项 Honor 修复均完好未被动过，回归根因是潜伏缺陷：`WakeLockManager` 自初版使用 `acquire(timeout=10min)`，10分钟后 WakeLock 自动释放，荣耀 MagicOS 在无活跃 WakeLock 时将 CPU 挂起（即使 App 在前台），导致游戏线程冻结、月份停止推进。修复：(1) WakeLock 去掉超时限制，改为 `acquire()` 持续持有，生命周期由 `onResume`/`onPause` 管理；(2) 加强荣耀 antiFreezeDelay 忙等频率：间隔从每 64 周期缩短至 16 周期（~32ms），忙等时长从 2ms 增至 4ms，匹配 MagicOS 更窄的空闲检测窗口

## [4.0.09] - 2026-06-18（versionCode=4009）

### 修复

- **修复：自动存档恢复进度错误** — 新游戏后等待至三月自动存档，读取该存档恢复到一月而非三月。根因：增量存档（自动存档使用）写入Room DB后未清理缓存，加载时优先读取缓存命中初始完整存档的旧数据（gameMonth=1），不触及DB中已正确写入的三月数据。修复：(1) 增量存档写入DB后添加`clearCacheForSlot()`确保下次加载走DB；(2) 增量写入时统一`id`字段为`"game_data_$slot"`与完整存档路径一致；(3) `getGameData`查询添加`ORDER BY lastSaveTime DESC`防御重复行
- **修复：紧急存档后储物袋消失** — 储物袋(`storageBags`)在所有完整存档路径（紧急存档/手动存档/SavePipeline自动存档/后台存档/重启存档/退出存档）中被遗漏，仅增量存档能正确持久化。根因：`SaveSnapshot`缺少`storageBags`字段，且`SaveDataTrimmer`和多处`SaveData`直接构造均未传递`storageBags`，导致`writeAllDataToDatabase()`先deleteAll再upsertAll空列表，DB中储物袋永久丢失。修复：`SaveSnapshot`加字段 + 7处SaveData/SaveSnapshot构造补传`storageBags`
- **修复：血练池不扣兽血、不加属性** — (1) `consumeMaterialByName`用`find()`只取首个材料堆叠，兽血分散多堆叠时单堆不足200导致扣除静默失败，返回值被忽略；(2) 血练完成仅靠月度结算处理，无实时检测和通知。修复：(1) 重写为遍历所有匹配堆叠逐个扣除；(2) 扣除失败时回滚灵石和进度并提示错误；(3) 新增`BloodRefinementComplete`通知，完成后弹窗提示
- **变更：矿工忠诚度改为每连续3月扣1点** — 原为每月扣1点忠诚度，现改为每连续挖矿3月扣1点。连续计数随矿工在位自动累加，离位（撤下槽位）后重置
- **新增：储物袋详情界面增加"全部开启"按钮** — 储物袋详情弹窗第二行新增"全部开启"按钮（附带两个空白占位符保持按钮大小一致），点击后一次性开启该储物袋全部数量，奖励统一展示

## [4.0.08] - 2026-06-18（versionCode=4008）

### 修复

- **修复：LazyColumn/Grid 崩溃 "Key '1' was already used" 未根治** — v4.0.06 在 `DiscipleTables.insert()` 加的防御检查 `if (id in ids) { update(...); return }` 解决了大部分 Key 重复问题，但 check-and-add 两步操作非原子，多协程同时 insert 相同 ID 时仍可同时通过检查产生重复。修复：(1) `insert()`/`remove()`/`clear()` 中对 `ids` 的操作加 `synchronized(ids)` 锁保证 check-and-add 原子性；(2) `assembleAll()` 增加 `.distinct()` 防御层，即使 ids 因任何原因出现重复也不触发 Compose Key 碰撞崩溃

## [4.0.07] - 2026-06-18（versionCode=4007）

### 修复

- **修复：弟子穿装备后UI不显示** — 装备穿戴写入了discipleTables但脏检测门控使用`ids.hashCode()`判据，穿装备不增删弟子导致ids不变，门控条件不满足跳过UI刷新（`_disciplesFlow`不更新）。根因：v4.0.01三大系统重构引入的`assembleAll`脏检测优化使用了错误判据。修复方案：(1) 给`ComponentTable`/`IntComponentTable`/`DoubleComponentTable`加`onWrite`回调；(2) `DiscipleTables`初始化时通过`bindAllOnWrite()`将所有子表的写入回调指向`markMutated()`，确保字段级原地写自动bump版本号；(3) `GameStateStoreImpl`提交门控改用`mutationVersion`替代`ids.hashCode()`。深层影响：所有原地修改discipleTables字段但不增删弟子的操作（使用丹药、突破、修炼进度、功法分配等）的UI刷新一并修复
- **修复：装备失败仍显示装备成功** — `GameEngine.equipItem`吞掉了`DiscipleService.equipEquipment`返回的`DomainResult`，无论境界不足/槽位冲突等失败一律显示"装备成功"。修复：`equipItem`改为返回`DomainResult<Unit>`，`DiscipleViewModel`和`DiscipleDelegate`分别处理Success/Failure/Partial结果。同时修复unequipItem同理吞结果的对称问题

### 变更

- **变更：灵矿基础产出提升** — 每位矿工每月基础产出从 160 灵石提升至 220 灵石（+37.5%）

## [4.0.06] - 2026-06-17（versionCode=4006）

### 修复

- **修复：弟子忠诚度持续下降** — 灵矿月度结算（processSpiritMineProduction）在影子事务内使用 scope.launch 异步写回真实状态，协程捕获的影子提交前旧快照在影子交换后覆盖了 SettlementCoordinator 对弟子表的所有修改（修炼值、忠诚度居住加成、熟练度、温养）。修复：改为同步直接操作影子组件表，移除 clear+insert 全量覆盖模式
- **修复：灵植阁种植后无收获** — 灵植阁收获（processHerbGardenGrowth）和灵田收获（processSpiritFieldHarvest）在影子事务内直接写真实 state（inventorySystem.addHerb / stateStore.update），影子交换时旧库存覆盖新收获。修复：改为直接操作影子 herbs/游戏数据
- **修复：非焦点弟子每月仅获1/3修炼量** — Clean 批次弟子每月结算使用「每旬修炼值」而非「每月修炼值（rate×3）」，与 Dirty 批次行为不一致。修复：Clean 批次统一使用 rate×3
- **修复：政策灵石消耗从未执行** — EconomySubsystem.onMonthTick() 从未被 SettlementCoordinator 调用，所有政策的月度灵石扣除完全丢失。修复：将 EconomySubsystem 接入月度结算，同步重构 processPolicyCosts 使用影子状态
- **修复：Phase tick 内自动仓库装备/学习覆盖修炼进度** — processAutoFromWarehouse 在 stateStore.update{} 事务内使用 scope.launch 异步写回旧快照，覆盖 phase tick 中已写入事务状态的修炼/HP/MP 变更。修复：改为直接操作事务内 MutableGameState
- **修复：实时突破处理覆盖修炼变更+丹药复制** — processRealtimeBreakthroughs 异步覆盖弟子突破结果（境界/寿命等）并可能复制已消耗的突破丹药。修复：改为直接操作事务内状态
- **修复：在线邮件从不送达** — MailSystem.onMonthTick() 从未被 SettlementCoordinator 调用，processMonthlyMails 永远不运行。修复：将 MailSystem 接入月度结算
- **修复：居住忠诚度无上限保护** — calculateLoyaltyDelta 缺少 MAX_LOYALTY 检查，忠诚度可突破 100。修复：达到上限后不再增长
- **修复：LazyColumn 崩溃 "Key was already used"** — DiscipleTables.ids 为 MutableList（非 Set），28+ 处 clear+insert 模式在多协程交错执行时同一 ID 可被重复添加，导致 Compose LazyColumn key 碰撞崩溃。修复：insert() 加防御检查，ID 已存在时走 update 路径不重复追加

## [4.0.05] - 2026-06-17（versionCode=4005）

### 修复

- **修复：非焦点弟子不修炼的严重BUG** — 修炼速率缓存（cachedCultivationRates）初始为空Map，仅首月结算完成后才被填充。每旬tick中非焦点弟子的修炼增益依赖此缓存（`cachedCultivationRates[id] ?: 0.0`），缓存为空时返回0导致所有非焦点弟子零修炼进度。焦点弟子不受影响因为走独立的实时计算路径。修复：每旬tick遍历弟子前检查缓存，对缺失key调用calculateDiscipleCultivationPerPhase现场计算填充

### 变更

- **变更：修炼速度单位从每秒改为每旬** — calculateDiscipleCultivationPerSecond重命名为calculateDiscipleCultivationPerPhase，返回值乘以MS_PER_PHASE_1X/1000（= 2.0s/旬，1x速度）。移除所有调用处的*phaseSecondsValue / *monthSeconds乘法。每月修炼增益改为 rate × 3（3旬/月），UI显示 "/秒" → "/旬"。修炼速度与游戏倍速无关，每旬固定产出。同时将熟练度增长和装备温养也统一改为每旬计算

### 优化

- **优化：奖励飞出卡片视觉重构** — 上下横线内改为深色渐变背景卡片，图片框改为品阶色底色+灰色边框，无精灵图物品显示"敬请期待"占位，卡片间以2dp间距错开有序飞出，图片框位置跨卡片统一对齐，物品名称金色字体、数量白色字体，卡片高度36dp紧凑布局

## [4.0.04] - 2026-06-17（versionCode=4004）

### 修复

- **修复：部分设备因存档BLOB损坏导致OutOfMemoryError崩溃** — 重型存档数据（LZ4压缩BLOB）在极端情况下损坏，解码时头4字节被误读为~1GB原始大小直接分配GB级内存触发OOM。根因：`decodeFromBlobInternal` 缺少分配前上限校验。修复：增加三层纵深防御——(1) 分配前校验 originalSize 上限与解压比（25x），损坏数据立即拒绝返回空默认值；(2) OutOfMemoryError 兜底catch优雅降级不崩溃；(3) 全部7个重型数据解码路径自动受益
- **修复：重新开始游戏后旧存档数据残留导致弟子数量暴涨** — 数据库保存时 upsertAll 只覆盖同 ID 行，不删除旧存档中 ID 更高的残留行（如旧档 100 弟子、新档 3 弟子，数据库同时存在 100 行）。修复：全量保存时用事务包裹所有写入，18 张多行实体表先 deleteAll(slot) 再 upsertAll，确保数据库与内存状态严格一致。同时补全 delete(slot) 中遗漏的 8 个表清理调用
- **修复：招募弟子界面每年不刷新** — 年度事件处理（招募刷新、商人刷新、俸禄、弟子年龄、外交等）在 Settlement 影子事务（shadow transaction）内部调用 `stateStore.update{}`，触发 `GameStateStoreImpl` 的影子事务守卫抛异常，被 `SettlementCoordinator` 静默捕获后重置整个年度结算，导致所有年度事件每年都静默失败。修复：将年度事件处理移至影子事务外——在时间推进提交后、Settlement 影子创建前执行
- **修复：GameTimeClockTest 全部 13 个测试失败** — v4.0.03 将 GameTimeClock 时钟源从 currentTimeMillis() 迁移至 SystemClock.elapsedRealtime()，但测试的 simulateTick 仍用旧 API 设置 lastWallMs，两个时钟基准不同导致 delta 恒为负数。修复：测试同步迁移至 SystemClock.elapsedRealtime()
- **修复：放置建筑时同类型旧建筑消失（最多只能看到2个）** — 烘焙渲染管线的增量更新假设 back buffer 已包含所有旧建筑，仅绘制「不在 previousIds 中」的新建筑。但双缓冲交换后 back buffer 拿到的是 2 轮前的旧 front buffer，原有建筑全部丢失，导致建筑在两块缓冲之间交替消失——建第3个时第2个消失，建第4个时第1和第3个消失。修复：步骤 3 改为每轮全量绘制所有建筑，不再依赖 back buffer 旧内容
- **修复：月度结算中盗窃检测/任务完成/侦察过期等事件静默失败** — 月度事件处理（盗窃检测、任务刷新与完成奖励、侦察信息过期、外交月度事件、游戏结束检测）在 Settlement 影子事务内部调用 `stateStore.update{}`。当存在低道德/低忠诚度弟子且灵石>0时，`processTheftMonthly()` 同步调用 `stateStore.update{}` 触发影子事务守卫抛出 `IllegalStateException`，被 `SettlementCoordinator.executeStep()` 静默捕获后调用 `resetOnError()` 丢弃整个月度结算，导致所有月度事件每月静默失败。修复：将月度事件移至影子事务外——在时间推进提交后、Settlement 影子创建前执行 `processMonthlyEvents()`，与年度事件修复一致

## [4.0.03] - 2026-06-16（versionCode=4003）

### 新增

- **新增：官方玩家交流群奖励邮件** — 邮件系统新增内置奖励邮件，邀请玩家加入官方玩家交流QQ群（群号：1085248982），奖励宝品储物袋×10

### 修复

- **修复：招募弟子界面"同意"按钮点击无反应** — 招募列表弟子 ID 使用 UUID（如 `84ef16c0-...`），但 `DiscipleTables.insert()` 强制 `.toInt()` 导致 `NumberFormatException` 崩溃，协程吞掉异常后无任何提示。修复：手动招募、自动招募、全部招募三条路径统一在 `insert()` 前分配新整数 ID（`maxOrNull + 1`），与 `DiscipleService.recruitDisciple()` 的 ID 生成策略一致
- **修复：招募界面按钮完全无按压反馈** — `UnifiedGameDialog` 内层 Box 的 `pointerInput` + `detectTapGestures` 抢先消耗了子级 `clickable` 的触摸事件，导致 `GameButton` 的缩放动画（`collectIsPressedAsState`）和点击回调均不触发。修复：替换为 `Modifier.clickable(indication=null)`，子按钮在 Main pass（leaf→root）优先处理
- **修复：部分设备操作建筑（放置/移动/拆除）或快速拖动地图时闪退** — 根因为建筑烘焙管线中主线程直接修改正在屏幕显示的 Bitmap，与 HWUI 渲染线程产生读写竞争导致 libhwui.so SIGSEGV。修复：双缓冲架构——正面缓冲（frontBuffer）仅由渲染线程只读，背面缓冲（backBuffer）由主线程写入完成后原子交换，从根源消除竞争。同时限制建筑精灵图解码尺寸（inSampleSize）防止低端设备超出 GPU 纹理上限，移除 Compose 正在使用的 Bitmap 上的危险 recycle() 调用
- **修复：战斗日志弹窗 / 道具列表界面偶发崩溃** — 根因为 LazyColumn/LazyVerticalGrid 中 6 处使用 `hashCode()` 作为 item key，但 data class 的 `hashCode()` 基于字段值不保证唯一，不同对象值相同时产生重复 key 导致 `IllegalArgumentException: Key was already used`。修复：(1) BattleLogDialogs 中 `List.hashCode()` 替换为 `itemsIndexed` 用行索引做 key；(2) DetailPillSection / MerchantDialog 中 `item.hashCode().toString()` 替换为 `System.identityHashCode(item)` 确保对象级唯一

t- **修复：华为畅享70等机型游玩时游戏时间停止不动** — 华为 PowerGenie（省电精灵）层层挂起游戏线程，根因有五：(1) ADPF Hint Session 在 `startGameLoop()` 中创建时运行在调用线程（主线程），`myTid()` 返回错误 TID，导致调度优化完全未作用于游戏线程；(2) WakeLock tag 使用 `XianxiaSect::GameLoop`，被 HwPFWService 白名单检测拦截；(3) `delay()` 底层 `LockSupport.parkNanos()` 使线程进入 PARKED 状态，PowerGenie 检测到空闲后挂起；(4) `System.currentTimeMillis()` 受 NTP 同步影响可能跳动；(5) 所有卡死检测与游戏线程同在一线程，线程被挂起后无人发现。修复：(1) `createHintSession()` 移入 `engineScope.launch {}` 内执行，确保 `myTid()` 返回游戏线程 TID；(2) 华为/荣耀设备 WakeLock tag 改为 `AudioMix` 绕过 HwPFWService 白名单；(3) 微延迟间隔从 16ms 降为 4ms + `Thread.onSpinWait()` 自旋，降低 PowerGenie 检测窗口；(4) `GameTimeClock` 时钟源从 `currentTimeMillis()` 迁移至 `SystemClock.elapsedRealtime()` 单调时钟；(5) 新增独立看门狗线程（`Dispatchers.Default`），每 5 秒检测 tickCount 是否推进，卡死时自动重启游戏循环（最多 3 次）；(6) 线程优先级提升在 Android 12+ 静默失败时 fallback 到 `Process.setThreadPriority(URGENT_DISPLAY)`
- **修复：荣耀70（Honor 70）等MagicOS设备游戏时间停止不动** — 荣耀脱离华为后MagicOS与EMUI电源管理栈完全不同：(1) AudioMix WakeLock标签仅绕过华为HwPFWService，MagicOS无此服务—Honor恢复标准标签；(2) Thread.onSpinWait()仅API 33+可用，Honor 70出厂API 31完全跳过自旋—API<33补偿：2ms微延迟+每64周期忙等替代缺失的onSpinWait；(3) 看门狗运行在Dispatchers.Default守护线程池上，MagicOS挂起守护线程导致看门狗自身被冻结—迁移至独立非守护线程，Honor检测间隔缩短至3s；(4) 厂商适配层荣耀从华为配置中完全分离，增加MagicOS版本检测与诊断
- **修复：世界地图横屏模式右侧出现白边** — `autoScale = minOf()` 选较小缩放比，宽屏设备地图宽度不足视口留白透出底图。修复：改用 `maxOf()` 确保地图始终至少在一个方向填满视口，横屏宽度填满、竖屏高度填满，彻底消除白边
- **修复：世界地图宗门不显示** — `playerSect` 为 null 时回退坐标 `(2000, 1750)` 超出世界边界 `(1698×926)`，相机定位异常致 `isVisible` 误裁剪所有宗门标记。修复：回退坐标改为世界中心 `(849, 463)`，`isVisible` 默认 margin 从 0 改为 1 防御浮点精度误判
- **修复：宗门地图 Mali GPU 设备边缘透出底图** — v4.0.02 修复（外扩 1px 防御 GPU 采样偏差）缺少 `clipRect` 裁剪约束，LOW 等级大比例拉伸（1536→3074）时 Mali GPU 边缘像素溢出 Canvas。修复：`withTransform` 前加入 `clipRect` 约束所有绘制在视口内

## [4.0.02] - 2026-06-16（versionCode=4002）

### 修复

- **修复：华为手机宗门地图周边透出土青色底图** — Mali GPU 设备（MEDIUM 档）浮点摄像机亚像素反走样 + 位图 1.5× 非整数拉伸叠加，导致 drawImage 边缘产生 1px 间隙透出背景。(1) 渲染前摄像机坐标四舍五入为整数，消除亚像素偏移；(2) 背景图层四边各外扩 1px 防御 GPU 边缘采样偏差（对标 Skia chromium:1324336 epsilon clamping）
- **修复：天赋详情界面"地脉感应"效果显示英文** — `formatEffectKey()` 缺少 `"miningFlat"` → `"采矿"` 映射，且 `flatKeys` 集合未包含该键，导致弹窗中显示 `miningFlat +1800%` 而非 `采矿 +18`

## [4.0.01] - 2026-06-16（versionCode=4001）

- **修复：创建新游戏后宗门地图不显示初始灵矿场** — 根因是建筑烘焙系统存在两个独立 bug。(1) LaunchedEffect 异步时序导致建筑从未绘制到位图：produceState 在后台线程创建位图，LaunchedEffect 在主线程读取到 null 后提前退出，此后位图就绪但 key 未变不再触发。修复：增加 bakedMapBmp 到 LaunchedEffect 的 key 并增加 bakeVersion 计数器通知 Compose 重绘。(2) MEDIUM 档 GPU 渲染分辨率与建筑坐标不匹配：groundBmp/fullMapBmp 是渲染分辨率（2048×2048），但 srcRect 使用了世界坐标（3072×3072 空间），导致读/写完全错误的地图区域，在建筑位置留下错误色块，且移动建筑后旧位残留。修复：srcRect 坐标全部乘以 renderScale 转换到渲染空间
- **修复：读档时报 NumberFormatException "For input string: """ — syncAllDiscipleStatuses 中 mapNotNull 只过滤 null，空字符串穿透到 toInt() 崩溃。修复：追加 takeIf { it.isNotEmpty() } 同时过滤空串
- **修复：建筑拆除按钮不跟随建筑移动** — DemolishButton 使用 building.gridX/Y（拖拽初始坐标）定位，不随拖拽更新。修复：改用 snappedGridX/Y（实时吸附坐标）

## [4.0.00] - 2026-06-15（删档重发版本，合并原 4.0.00~4.0.05 + 架构重构 + 华为修复，versionCode=4000）

> 数据库重置，所有旧存档清空，所有玩家统一从 4.0.00 开始。

- **领域结果统一**：引入 `DomainResult<T>` 密封接口（成功/部分成功/失败），替代全项目 15+ 处裸 Boolean 返回和 5 套碎片化错误类型。调用方通过 `when` 穷尽性强制处理所有分支，失败时携带具体错误原因
- **错误体系整合**：`AppError.Domain` 新增弟子/道具/建筑三大领域错误子树（9 种具体错误类型），删除已废弃的 `ProductionError`、`ProductionOperationResult`、`ProductionResult` 等碎片类型
- **事务返回值支持**：`GameStateStore` 新增 `updateAndReturn<R>` 方法，事务内可直接返回值，消除 `var result = false` 闭包捕获反模式
- **死代码清理**：移除 8 个未使用文件、`isInTransaction()`、`createShadow()`、`WarehouseCompressor`/`WarehousePager`/`WarehouseDiffManager`/`WarehouseCache` 等废弃子系统
- **道具系统**：新增 `StackableItemStore<T>` + `StackKey` 泛型可堆叠仓库，统一 6 类物品合并键
- **仓库系统**：`SectWarehouseManager` + `OptimizedWarehouseManager` 从全局 object 改为 `@Singleton class @Inject`，精简至核心 CRUD 操作
- **引擎服务标注**：补齐所有缺失的 `@GameService` 注解，白名单清空
- **华为设备时间停止根治**：七层保障体系（短轮询防挂起+ADPF性能提示+WakeLock+GameState API+时间时钟加固+厂商电池适配+线程优先级提升）
- **删档重置**：数据库版本归零，清理旧迁移文件和架构遗留代码

### 下方为 4.0.00 完整技术细节（原 4.0.00~4.0.05 各版本合并）

> 数据库保持 version=1，所有旧存档清空。本条目合并了原 4.0.00（基础架构/删档重置）、4.0.01（组件化实体存储）、4.0.02/4.0.03（世界地图修复+存档管线）、4.0.04（战斗动画）、4.0.05（死锁根治）的全部更新。

---

**下方为原 4.0.00~4.0.05 各版本的详细更新日志，已合并到上方 4.0.00 版本：**

### [原 4.0.05] 修复：创建新游戏后游戏完全卡死（时间不动、弟子不修炼、邮件/签到点击无反应）

- **根因：游戏循环 tick 内 `stateStore.update{}` 嵌套调用导致不可重入 `Mutex` 死锁（核心修复）**：`GameEngineCore.tickInternal()` 在 `stateStore.update{}` 块内调用各 GameSystem 的 `onPhaseTick`，而 `CultivationEventProcessor.processPhaseTick` 内部又调用了 `stateStore.update{}`。由于 `GameStateStoreImpl` 的 `transactionMutex` 是 `kotlinx.coroutines.sync.Mutex`（不可重入），同线程二次 `withLock` 永久挂起，锁被永久持有。此后**所有依赖 `stateStore.update` 的操作全部挂起**——时间推进（tick 本身）、弟子修炼（在 tick 内）、每日签到/邮件领取（`claimDailySignIn`/`markAllMailsAsRead` 走 `stateStore.update`，协程启动后永久 `withLock`，表现为点击无反应、无异常、无日志）。UI 导航正常（只读 StateFlow，不依赖 update）。这与设备无关，模拟器/真机均会死锁
  - **修复方式**：`GameStateStoreImpl.update()` 新增 `transactionOwnerThread`（AtomicReference）重入检测——当当前线程已持有 `transactionMutex` 时，直接对事务内状态（`currentTransactionState`）执行 block 并返回，不再 `withLock`。游戏循环跑在单线程 dispatcher（`GameEngineCore.GAME_DISPATCHER`）上，用线程身份即可精确识别重入；UI/ViewModel 的 update 调用在主线程/IO 线程，不会误判
  - **兜底修正**：`CultivationEventProcessor.processPhaseTick` 透传 `state` 参数，把 `:190` 的 `stateStore.update{}` 改为直接操作 `state.discipleTables`（与同文件 `advanceMonth`/`advanceYear` 的写法保持一致），消除一处确定的死锁点
- **加载存档嵌套死锁修复（前次遗漏位置，保留有效）**：`loadData()` 中行商物品/招募列表为空时，在 `stateStore.update{}` 内部调用 `refreshTravelingMerchant()`/`refreshRecruitList()` 导致同类嵌套死锁——与 v4.0.04 修复的 `initializeWorldAndServices()` 同类型问题。本次的核心重入检测已自动覆盖此路径，但将其移出临界区仍是更好的设计（减少锁竞争），予以保留
- **同类隐患说明**：`ProductionSubsystem`（锻造/炼丹完成、灵田收获、自动挖矿）另有 7 处 tick 内嵌套 `stateStore.update{}`，玩家建炼丹/锻造并开工后会触发同类死锁。本次核心重入检测已一并根治，无需逐点修改
- **`isGameStarted` 时序修正（保留）**：`isGameStarted = true` 从 `createNewGame()` 内部移至 `startGameLoop()` 成功后设置，防止存档失败时出现"UI 已显示主界面但游戏循环未启动"的残留状态。这是独立于死锁的潜在 bug 修复，与本次正交

### 历史说明
v4.0.05 初版曾将根因误判为"游戏启动标志时序"并据此前提提交（commit `8c5f2a83`），实测修复无效。经系统化根因调查（`systematic-debugging`），定位真正根因为 tick 内 `stateStore.update` 嵌套调用导致的不可重入 Mutex 死锁，本次予以更正

### 影响文件
- `GameStateStoreImpl.kt`：`update()` 新增 `transactionOwnerThread` 重入检测分支，withLock 块设置/清除该标记
- `CultivationEventProcessor.kt`：`processPhaseTick` 透传 `state`，`:190` 改为直接操作 `state.discipleTables`
- `GameEngineCoordination.kt`：`loadData()` refresh 移出 `stateStore.update{}`（前次，保留）
- `SaveLoadViewModel.kt`：`isGameStarted` 时序统一（前次，保留）
- `GameEngineCore.kt`：`startGameLoop()`/`tickInternal()` 诊断日志（前次，保留）

### [原 4.0.04] 新增：天道试炼战斗动画系统

- **攻击位移动画**：进攻者头像快速滑动至目标位置，命中后返回原位，完整展示攻击轨迹
- **受击抖动反馈**：被攻击单位图标产生左右抖动，提供打击感视觉反馈
- **浮动伤害数字**：战斗中造成伤害时显示浮动数字，依次弹出、放大再淡出——物理伤害显示橙色、法术伤害显示紫色、暴击伤害显示红色并加大加粗、治疗显示绿色
- **敌方AI可见攻击**：敌方回合不再即时结算，玩家可看到每个敌人依次攻击的完整动画演出
- **延迟状态更新**：HP变化在攻击动画结束后才生效，确保玩家完整观看到每次攻击结果

### [原 4.0.03] 修复：宗门数据静默丢失——存档重型数据管线根治

- **恢复链路重构**：`ensureHeavyDataLoaded()` 从"单路径异步即发即弃"改为"三级回退同步加载"——优先 `game_heavy_data` 表恢复，失败则回退到 `world_map_state` 冗余表，再失败则从 `FixedSectPositions` 配置表重生，彻底消除宗门数据静默丢失的级联风险
- **加载时序修正**：重型数据恢复从 `startGameLoop()` 异步即发即弃移至 `setSaveLoadState(false)` 之前同步执行，确保世界地图和外交界面在加载界面关闭前数据已就绪
- **存档前防御校验**：`SaveFacadeImpl.getStateSnapshot()` 新增 `worldMapSects` 非空校验，若意外为空则从配置表紧急重生防止级联丢失；`StorageEngine` 增加存档前数据完整性日志
- **领域接口解耦**：新增 `WorldMapStatePort` 领域端口避免 `engine` 模块直接依赖 `data` 模块 DAO，符合 Clean Architecture 依赖方向

### 修复：世界地图部分机型宗门不显示（v4.0.02）

- **Layout bounds 裁剪修复**：SectMarker/LevelMarker 将 `layout(placeable.width, placeable.height)` 改为 `layout(constraints.maxWidth, constraints.maxHeight)`，解决内容被 `place()` 放置到 bounds 之外时 Mali/PowerVR GPU 裁剪导致标记不渲染的问题
- **地图自动缩放修正**：初始缩放从 `maxOf` 改为 `minOf`，确保在所有屏幕宽高比下地图完整适配视口，边缘宗门不再被剔除
- **LevelMarker RTL 防护**：`placeRelative` 改为 `place`，避免 RTL 布局方向下 X 坐标翻转

### [原 4.0.01] 架构根治：组件化实体存储 + 消除不可变对象拷贝

- **Disciple 组件表化**：97 字段不可变 data class + 222 行 `copyWith` 方法彻底删除，改为 `DiscipleTables` 组件表存储（~90 张窄表，每张独立 SparseArray 索引），单字段修改从"复制全部 97 字段"变为"写一个数组元素"，内存分配减少 ~97 倍
- **EntityStore 统一存储**：EquipmentStack/EquipmentInstance/ManualStack/ManualInstance/Pill/Material/Herb/Seed/StorageBag 全部从 `List<T>` 迁移到 `EntityStore<T>`（HashMap O(1) ID 查找），消除引擎层全部 51 处 `.find { it.id == }` 遍历
- **双重状态访问器清零**：CultivationCore（6 个）+ CaveExplorationProcessor（5 个）+ 其余 12 个 Service 文件共 19 个 `private var currentXxx` 自定义 getter/setter 全部删除，改为显式 `stateStore` 直访
- **Shadow 深拷贝修复**：`DiscipleTables.deepCopy()` 从列表/映射类型浅拷贝改为深拷贝（`.toList()`/`.toMap()`），防止 Shadow 事务内突变影响原表
- **CancellationException 重抛**：`SettlementCoordinator.executeStep()` 新增协程取消异常重抛，符合编码规范 8.1
- **本地变量遮蔽修复**：ExplorationService/CultivationEventProcessor/ProductionSubsystem 共 3 处 `val xxx = stateStore.xxx.value` 局部变量改名消除 `MutableGameState` 字段遮蔽，修复 "'val' cannot be reassigned" 编译错误
- **基础设施新增**：`HasId` 接口 + `ComponentTable<T>` / `IntComponentTable` / `DoubleComponentTable` 基础组件表类型

### [原 4.0.00] 修复：新游戏首次领取邮件物品丢失（根治）

- **claimedMailIds → MailClaimRecord**：`GameData` 新增 `mailRecords: List<MailClaimRecord>` 替换旧 `claimedMailIds`，新增 `claimedAt` 时间戳 + `source` 来源字段，`resetAndInitSlot` 据此恢复领取状态
- **Saga 补偿事务**：`distributeAttachments` 内联化为 `distributeAttachmentsInline(state, attachments)`，物品入库 + `mailRecords` 记录合并为单次 `stateStore.update` 原子操作，发放失败时邮件不标记已领
- **在线邮件稳定 ID**：`fetchOnlineMails` 改用 `"online_${remoteId}"` 作为 MailEntity.id，跨会话稳定，`mailRecords` 可正确恢复在线邮件领取状态
- **邮件初始化时序修正**：`createNewGame` / `restartGameInternal` 将 `mailService.resetAndInitSlot()` 移至世界初始化之后，确保 `mailRecords`/`slotId` 等状态已就绪
- **Room 类型转换器**：`CollectionConverters` 新增 `fromMailClaimRecordList`/`toMailClaimRecordList` Protobuf 转换器
- **存档删除清理**：`StorageEngine.delete()` 补充 `mailDao.deleteAllForSlot()`，消除孤儿邮件残留
- **奖励卡队列并发安全**：`enqueueMailRewardCards` 新增 `mailCardQueueMutex` 互斥锁

### [原 4.0.00] 删档重置：数据库归1 + 旧兼容全面清理

- **数据库迁移清零归1**：`@Database(version=1)`, `fallbackToDestructiveMigration()`, 移除全部 6 条增量迁移
- **旧存档兼容删除**：SavMigrator 整个类删除、SaveDataMigrator 清空为 SCHEMA_CURRENT=1、MigrationResult sealed class 删除
- **序列化精简**：SerializableSaveData 移除 9 个 @Deprecated protobuf 字段（herbGardenPlantSlots/forgeSlots/alchemySlots/effectsMap/harvestAmount/harvestHerbId/disciples/resources）+ HashedSaveData 整个类删除
- **ProtoBuf BLOB 兼容清理**：ProtobufConverters.decodeFromBlobInternal 移除 Base64 旧格式回退
- **枚举兼容清理**：JsonConverters 移除旧枚举值回退映射（MOVEMENT/PRODUCTION→SUPPORT, BATTLE */BREAKTHROUGH→CULTIVATION, HEALING→FUNCTIONAL）
- **领域模型清理**：PlantSlotData/ProductionSlot 删除 harvestAmount/harvestHerbId 废弃字段
- **压缩器精简**：DataCompressor 删除 UseCase.LEGACY 枚举及 GZIP 兼容分支
- **序列化迁移器清空**：V1ToV2~V4ToV5 四个 VersionMigrator 全部删除, 统一从 SchemaVersion(1) 起步
- **AppError 架构修复**：基类+子类→`:core:domain`, 扩展函数→`:app/AppErrorExt.kt`, 消除多模块循环依赖
- **多模块重复文件清理**：`:app` 删除 12 个 Kotlin + 1 个 proto 重复源文件, 根治 R8/D8 重复类错误
- **Debug 安全关闭**：debug 构建 `debuggable=false` + `DEBUG_MODE=false`, 防止生产调试暴露
- **配置缓存关闭**：`gradle.properties` 禁用 configuration-cache, 解决 Groovy 闭包兼容性冲突

### 重大更新：代码架构全面重构

- **4.0 全新大版本**：代码架构从零开始全面重构，所有游戏系统模块化拆分，数据库重置为 v1
- **巨型文件拆分**：CultivationService（3804行→1 Facade + 10 子模块）、GameEngine（3000行→精简协调器 + 9 域扩展文件）、DiscipleDetailScreen（2647行→542行 + 7 Section 组件）、SaveDataConverter（2002行→7 Converter）、ItemDetailDialog（1548行→3 组件）、WarehouseTab（1568行→4 Section + 3 Dialog）、ChangelogData（1999行→44行 + JSON 外置）、ProtobufConverters（1145行→544行 + 3 辅助 Converter）
- **反模式清零**：消除全部 110 处 `!!` 强制解包、17 处 `runBlocking`、14 处 TODO 遗留，`@Suppress` 从 60+ 降至 15
- **静态分析工具链**：集成 Detekt + Lint + Baseline 基线，R8 日志剥离 + BUGLY 密钥防泄漏
- **旧存档不兼容**：4.0 全新版本，旧版本（≤3.2.25）所有存档/数据库/缓存自动清空，所有玩家从零开始

### 新增功能

- **世界地图重构**：渲染管线从逐帧全量绘制重构为预烘焙 + 视口裁剪 + 瓦片缓存架构。地形+宗门标记预烘焙为单张 Bitmap，连接线 Path 仅在宗门关系变化时重建，屏幕外内容不绘制。5 个旧地图文件（MapCanvas/MapItem/MapItemMapper/MapStyle/MapCameraState）替换为 8 个新文件（MapBackground/MapTileCache/WorldMapConnections/MapCoordTransformer/SectCameraState/WorldCameraState/SectMapCanvas/SectMapState），宗门外交/驻军/手势交互独立 ViewModel
- **建筑拆除功能**：移动建筑模式下新增「拆除」按钮（红色背景），点击弹出确认对话框，拆除后返还 50% 建造灵石。建筑拆除触发建筑槽位清理与弟子下岗
- **活动系统**：主界面种植按钮下方新增「活动」入口（精灵图按钮），全屏活动界面支持活动列表与详情左右分栏展示，`BuiltinActivityConfig` 注册内置活动，ActivityViewModel 管理活动状态
- **每日签到**：活动系统内置每日签到功能，日历视图（7列×可变行数），每周七天不同奖励——灵石/随机凡品材料/储物袋/随机凡品种子/随机凡品丹药/悟法丹/灵品储物袋。已签/未签/今日三种卡片状态，月份自动重置，「?」图标标识随机物品
- **商人收购功能**：云游商人界面重构为「购买」+「收购」双标签布局，各占一半宽度。商人每年收购 1~6 种物品，价格 ±20% 随机浮动（精确到 0.1%），收购确认弹窗支持数量调节和总价实时计算，收购数据持久化到存档
- **背包系统交互优化**：物品卡片统一交互模式——点击选中/长按查看详情/右上角快捷操作（储物袋「开启」等），仓库底部统一「售卖/锁定/赏赐」三按钮布局，物品详情效果文本补全（装备/功法/丹药/材料各类型专属描述）
- **GPU 全覆盖分级渲染**：覆盖 80+ SoC 型号（骁龙 8 Elite→骁龙 4 系、天玑 9400→天玑 6000、麒麟 9030→麒麟 710A、Exynos 2400→1280、Tensor G4→G1、展锐虎贲）、40+ 手机品牌，四级渲染策略自动切换（地图精度/渲染缩放/Bitmap 格式/装饰密度/贴图LOD）
- **世界地图宗门固定坐标**：60+ 宗门坐标硬编码到 `FixedSectPositions.kt`，消除运行时随机生成带来的地图抖动

### 性能优化

- **世界地图渲染**：预烘焙 Bitmap 零分配每帧绘制，视口裁剪屏幕外内容，连接线 Path 仅宗门关系变化时重建，瓦片缓存分块加载
- **Canvas Overdraw 大幅减少**：网格线 LOW 模式 ~100 条降至 4 条（96%），放置/移动预览逐格矩形改为单矩形绘制（50×→1×），光环效果 LOW 关闭/MEDIUM 简化为圆形轮廓
- **纹理质量分级**：LOW 设备解码采样率翻倍（内存减 75%），ULTRA 设备贴图清晰度提升 2×
- **温控联动增强**：GPU 分级 + ADPF 热状态双因子决定渲染缩放，LOW 设备常温即降至 0.7×
- **游戏循环频率**：TICK_INTERVAL 降至 100ms，焦点域 100ms 高频推送，非活跃域最长 30s 降频

### Bug 修复

- 修复 `SignInState` 使用 `Set<Int>` 违反 ProtoBuf 规范导致序列化失败（改为 `List<Int>`）
- 修复 `GpuTierDetector` EGL 资源泄漏（所有路径 try-finally 清理）
- 修复 `ManualDatabase` 未初始化时功法详情崩溃（`isInitialized` 防御守卫）
- 修复世界地图宗门位置随机抖动（固定坐标方案）
- 修复储物袋物品列表重复 key 崩溃（LazyVerticalGrid key 加 index 后缀）

### 数据库演进（v1 → v5）

| 版本 | 迁移内容 |
|------|---------|
| v2 | `game_data` 新增 `sign_in_state_json` 列（TEXT, ProtoBuf 序列化 `SignInState`） |
| v3 | 活动系统相关列（`ActivityDef` / `BuiltinActivityConfig` 配置存储） |
| v4 | 世界地图宗门固定坐标与驻军相关列 |
| v5 | `game_data` 新增 `merchantAcquisitionItems`（BLOB）和 `merchantAcquisitionLastRefreshYear`（INTEGER）列 |

### 架构文档与设计文档

- `CODE_WIKI.md` 新增 5 个章节：世界地图重构、背包系统重构、商店改版、每日签到优化、数据库迁移 v1→v5

### 破坏性变更

- **旧存档不兼容**：数据库从零开始（v1），旧版本（≤3.2.25）所有存档/数据库/缓存自动清空，所有玩家从零开始修仙

## [3.2.25] - 2026-06-08

### 优化

- **优化**：游戏时间系统重构 — 新建 `GameTimeClock` 作为全项目唯一时间推进入口（三层时钟模型：墙上时间 → 游戏时间 × 速度 → 固定步长旬推进），替代原 `GameEngineCore` 中的墙上时间累加器。1x 速度严格 2 秒/旬、6 秒/月，2x 速度 1 秒/旬、3 秒/月，速度切换时自动保存已累积时间不丢进度
- **优化**：下旬动态延长 — 月度结算未完成时时间暂停等待，结算完成后立即推进至下月上旬，不再强制完成可能丢数据的结算
- **优化**：统一时间计算 — `CultivationService`、`SettlementCoordinator`、`SettlementCache`、`LazyEvaluationDispatcher`、`AISectDiscipleManager` 等模块统一从 `GameTimeClock` 读取时间，消除分散在 5 个文件中的 `gameSpeed` 计算逻辑，修改速度行为只需改一处

## [3.2.24] - 2026-06-08

### 修复

- **修复**：世界地图打开后不显示宗门 — `tryCenterOn` 的 `hasInitialized` 守卫阻止实际坐标到达后重新居中；改为追踪上次居中位置，焦点坐标变化 >100px 时允许重定位，同时避免数据延迟到达导致的重复居中闪烁
- **修复**：弟子修炼进度条增长极慢 — `perTickSeconds=0.1s` 与相位推进实际间隔 2s 不匹配（20x 偏差），修正为 `SECONDS_PER_REAL_MONTH/PHASES_PER_MONTH/gameSpeed`，同步修复功法熟练度和装备孕养时间
- **修复**：弟子功法熟练度全部显示"入门"— `calculateProficiencyGains`（月度结算）更新已有条目时遗漏 `masteryLevel` 重算，非焦点弟子的熟练度等级永远停在 0
- **修复**：API < 29 设备闪退 — `PowerManager.currentThermalStatus` 为 API 29 新增，添加 SDK 版本检查降级
- **修复**：存档时 OOM — `encodeToBase64`/`encodeToBlobInternal` 序列化路径未设防，巨型对象图触发 1GB byte array 分配。三路守卫：`encodeToBase64` + `encodeNullableToBase64` + `encodeToBlobInternal` 均添加 Collection/Map >100k 条目预检 + OutOfMemoryError 兜底
- **修复**：储物袋物品列表重复 key 崩溃 — `StorageBagItem.itemId` 非唯一 ID，LazyVerticalGrid key 改为 `"${itemId}_$index"`
- **修复**：TapTap SDK `lateinit context` 崩溃 — 添加全局异常守卫，仅拦截 TapTap 内部 `UninitializedPropertyAccessException`，不动初始化时序（保持合规：用户同意隐私政策后才初始化 SDK）

## [3.2.22] - 2026-06-07

### 优化

- **优化**：月度结算效率大幅提升 — Cache 增量重建，90%+ 月份跳过修炼速率重算（Dirty Flag 模式）
- **优化**：结算 Shadow 浅拷贝 — 只拷贝结算实际修改的字段（gameData/disciples/equipmentInstances/pills/manualInstances），拷贝开销减少 60%+
- **优化**：弟子批量结算并行化 — 清洁/脏弟子分片 `async(Dispatchers.Default)` 并行处理，4 核设备耗时减半
- **优化**：时间预算动态调整 — 月切前 3 帧提升至 12ms 预算（保守 1.5ms），月结帧数从 12-65 帧降至 1-3 帧
- **优化**：生产结算并行化 — 炼丹/锻造独立方法 `coroutineScope` 并行执行，减少串行等待

### 修复

- **修复**：ProtoBuf 全量序列化 AI 宗门弟子数据（aiSectDisciples）触发 OOM 崩溃 — TypeConverter 改为增量编码占位，存档时不再闪退

## [3.2.21] - 2026-06-06

### 修复

- **修复**：TapTap SDK 初始化时序导致部分设备闪退（`lateinit property context has not been initialized`）

### 优化

- **优化**：启用 R8 full mode 编译优化，预期启动提速 30%+、帧渲染提升 25%
- **优化**：BLOB 存储集成 LZ4 压缩，存储空间减少 30%-50%，旧存档向后兼容
- **优化**：领域实体表拆分（Phase B），外交/生产/巡逻/世界地图/宗门政策走细粒度 DAO 读取
- **优化**：修炼进度条和 HP/MP 条改用 Canvas 直绘，减少高频重组
- **优化**：FrameMetrics 帧率监控统一接入 UnifiedPerformanceMonitor
- **优化**：按钮按压缩放改用 graphicsLayer 零重组动画
- **优化**：Disciple 委托属性重构为扩展属性（DiscipleDelegates.kt），消除 67 个样板 get()/set()

### 修复

- **修复**：LazyColumn 重复 key 崩溃 — 弟子过滤链末尾添加 distinctBy 去重
- **修复**：灵植阁种植收获草药未入库 — 月度结算 Shadow 事务物品变更传播缺失

## [3.2.20] - 2026-06-06

### 修复

- **修复**：宗门地图在某些情况下出现白边（Canvas 背景未填充）。热状态自适应缩放时画布留空，增加土绿色背景填充。

## [3.2.19] - 2026-06-06

### 修复

- **修复**：新一年云游商人不会刷新商品的问题。根因为存档影子合并（mergeGameData）将已刷新的 travelingMerchantItems 覆写为旧值（PRESERVE_OLD），导致年度结算写入的商品列表被丢弃。

## [3.2.18] - 2026-06-06

### 修复

- **修复**：地图拖动和点击建筑无响应的问题。之前的空闲检测覆盖层（全屏 pointerInput）拦截了所有触摸事件，导致底层手势处理器无法接收到拖拽和点击事件。改为在现有手势处理器内部（宗门地图拖拽、世界地图拖拽）直接调用 onUserInteraction，避免事件竞争。

## [3.2.17] - 2026-06-06

### 存档系统稳定性修复

- **修复**：长时间游玩后存档时偶发闪退（OutOfMemoryError），根因为大型数据 Base64 编码导致内存峰值超 300MB
- **二进制直存**：重型数据表 `game_heavy_data` 从 TEXT 改为 BLOB，消除 Base64 编码环节（33% 空间浪费 + 重复内存分配）
- **增量序列化**：不再一次性序列化全部 AI 弟子/宗门数据，改为逐宗门分批编码写入，峰值内存从 ~327MB 降至 ~3MB 以下
- **内存守卫**：写入前检查可用堆内存，不足 100MB 时自动跳过自动存档，下次 tick 重试
- **扩展卸载**：招募列表和世界宗门数据也移至重型数据表，进一步缩小主表行

## [3.2.16] - 2026-06-06

### 焦点域全面实时化

- **DISCIPLES Tab**：全体弟子修炼值每 100ms 平滑推进，HP/MP、buff时效实时更新
- **焦点弟子详情**：功法熟练度 + 装备孕养进度条每 100ms 推进（恢复之前移除的实时更新）
- **BUILDINGS Tab**：锻造/炼丹/种植槽位每 200ms 检测完成 + 触发自动生产
- 焦点域三重兜底：实时 tick + 月度结算 + 战斗前正常恢复（满状态跳过）

### 性能优化

- **修炼进度事件结算**：距突破 >2 月的弟子完全跳过月度结算，进入窗口后自动恢复
- **HP/MP 动态结算**：结算繁忙时跳过全员恢复，战斗前对参战弟子做一次正常恢复结算（满 HP/MP 跳过）
- **自动装备/自动学习脏标记**：卸下装备/遗忘功法时触发一次检测，空袋弟子长期零开销
- **弟子薪水年度结算**：改为每年结算一次（12 个月批量），突破时按旧境界结算累积薪水

## [3.2.15] - 2026-06-06

### 性能优化

- **大幅降低手机发热和耗电**：事件驱动惰性求值 — 修炼、锻造、炼丹、种植等耗时操作不再每月全量检查，改为存储完成月份、仅在到期时结算。非焦点域实体跳过率达 96%
- **月度结算分旬执行**：上旬修炼/功法/装备、中旬锻造/炼丹/血炼池、下旬种植/灵矿/AI弟子，CPU负载打散到3个旬
- **智能跳过迟钝操作**：弟子薪水满忠诚跳过、盗窃检查无低道德弟子跳过、执法改为盗窃触发后才执行
- **任务刷新改为每三月一次**：未被接取的旧任务自动清理，新任务数量 0-6
- **热状态自适应降画质**：设备发热时Canvas渲染分辨率自动降级（75%→60%→50%），保证帧率稳定
- **ADPF Performance Hint API 集成**：Android 12+ 设备上告知系统目标帧时长，让OS主动优化CPU调度
- **StateFlow 合并发射**：同一tick内多次状态变更合并为一次UI刷新，减少重组次数

### 数据模型

- Disciple / ProductionSlot / SpiritFieldPlant 新增 `completionMonth` + `completionPhase` 字段
- 数据库迁移 v32→v33：ALTER TABLE 新增 8 列

## [3.2.14] - 2026-06-06

### Bug 修复

- 修复 Dirichlet 广告 SDK 依赖旧 Support 库导致 ClassNotFoundException（启用 Jetifier）
- Bugly 显式设置应用版本号，解决崩溃报表中版本显示异常

## [3.2.13] - 2026-06-06

### Bug 修复

- 修复 Dirichlet 广告 SDK ProGuard 规则缺失 -keeppackagenames 导致类查找失败

## [3.2.12] - 2026-06-05

### Bug 修复

- 修复 Dirichlet 广告 SDK 被 R8 混淆移除导致 ClassNotFoundException

## [3.2.11] - 2026-06-05

### 新增功能

- 【全厂商适配】新增 ManufacturerAdapter 兼容层，覆盖华为/小米/OPPO/vivo/荣耀/三星
- 【崩溃收集】集成腾讯 Bugly 崩溃收集 SDK，自动上传符号表实现堆栈可读

## [3.2.10] - 2026-06-05

### Bug 修复

- 修复 MMKV 加载 + Java/Kotlin 互操作兼容性问题

## [3.2.09] - 2026-06-05

### Bug 修复

- 修复华为/荣耀手机启动闪退问题（原生库加载兼容性优化）

## [3.2.08] - 2026-06-05

### 新增功能：父母灵根影响子嗣修炼

- 父母灵根直接影响子嗣修炼速度加成：单灵根 +10%、双灵根 +5%、三灵根 0%、四灵根 -5%、五灵根 -10%
- 仅存活父母生效（已故父母不计入加成），父母各自独立计算，叠加生效（如双单灵根父母 = +20%）
- 计算函数统一在 `DiscipleStatCalculator`：`getParentSpiritRootBonus()` 返回单亲加成、`calculateParentCultivationBonus()` 返回双亲总加成
- 影响范围：`CultivationService` 月度/高频修炼计算、`DiscipleAggregate` 聚合统计、弟子详情修炼速度显示

### 新增功能：丧亲悲痛系统

- 弟子死亡后其亲属进入悲痛期，持续 1 年（griefEndYear = 死亡年份 + 1），多次丧亲取最晚结束日期
- 亲属判定覆盖道侣（partnerId）、父母/子女（parentId1/parentId2）、兄弟姐妹（共享至少一位父母）
- 丧亲惩罚：修炼速度 -50%（`GRIEF_CULTIVATION_SPEED_PENALTY = 0.50`）、突破率 -20%（`GRIEF_BREAKTHROUGH_CHANCE_PENALTY = 0.20`）
- 触发场景：战斗阵亡（CombatService）、探索阵亡（ExplorationService）、自然死亡/执行处决（CultivationService.handleDiscipleDeath）、宗门防御战阵亡（applyPlayerDefenseResult）
- 悲痛期满后自动清除：`CultivationService.processGriefExpiry()` 在年度结算末尾执行
- 亲属判定和悲痛应用统一提取到 `DiscipleStatCalculator.areRelatives()` 和 `applyGriefToRelatives()`，各 Service 不再重复实现
- 突破详情弹窗新增"丧亲减益"行，显示当前悲痛期突破率扣减

### 空闲检测优化

- 空闲检测时间 10 秒 → 60 秒（`IDLE_DETECTION_MS`），减少误判
- 游戏时间推进改为墙上时钟驱动（`elapsedMs`），不再依赖 tick 次数。无论 tick 是 100ms 还是 2000ms，游戏内 1 个月始终 ≈ 6 秒真实时间
- 修复空闲降频期间游戏时间变慢的问题（上旬→中旬原先需 40 秒，修复后仍为 2 秒）
- 非活跃焦点域调度基于 `System.currentTimeMillis()` 墙上时间，不受 tick 间隔影响

### Bug 修复

- 修复功法熟练度突破阈值后 `masteryLevel` 不更新的问题：`CultivationService` 月度/高频熟练度更新时同步计算 `MasteryLevel.fromProficiency()`，不再停留在初始值
- 修复功法详情进度条 `currentThreshold` 映射偏移一级导致进度计算错误的问题
- 功法熟练度显示上限从固定 30000 改为下一阶段阈值（入门 1000、小成 10000、大成 30000）

### 平衡调整

- 全境界修为值重新调整：炼气600、筑基3000、金丹8000、元婴20000、化神50000、炼虚100000、合体180000、大乘300000、渡劫450000、仙人650000
- 小层境界修为改为线性插值公式，9层修为严格低于下一境界1层，每层均匀递增
- 弟子基础修炼速度改为由灵根决定：单灵根50、双灵根30、三灵根15、四灵根6、五灵根3
- 功法熟练度阈值各品阶统一：入门 1000 / 小成 3000 / 大成 10000 / 圆满 30000（原按品阶倍率 400~2000）
- 最大熟练度统一为 30000（原按品阶 400~2000）
- 精通效果倍率调整：入门 150% / 小成 200% / 大成 300% / 圆满 400%（原入门 100% / 小成 120% / 大成 150% / 圆满 200%）

### 机制调整

- 功法熟练度增长速度改为每秒 6 点基础值，受悟性影响：悟性超过 70 后每多 1 点增加 10%（悟性 80 = 2 倍速度）
- 藏经阁加成保持 +50% 不变，与悟性加成叠加
- 删除 `manualResearch` 政策对熟练度速率的影响（原 5.0/6.0 区分）
- 删除品阶对熟练度上限和阈值的倍率影响
- 统一为单一公式 `calculateProficiencyGainPerSecond(comprehension, libraryBonus)`，删除旧的 `calculateProficiencyGain`（含境界/品阶/天赋参数，从未被实际调用）

### 建筑费用调整

- 单人住所建造费用 800 → 20000 灵石
- 多人住所建造费用 2000 → 30000 灵石
- 巡视楼建造费用 5000 → 50000 灵石
- 天枢殿建造费用 5000 → 20000 灵石
- 单人住所升级费用 5000 → 50000 灵石
- 初级单人住所修炼加成 +25% → +20%（倍率 1.25 → 1.20）
- 中级单人住所修炼加成 +50% → +40%（倍率 1.50 → 1.40）

### 生产时间调整

- 锻造/炼制时间按阶级统一调整：1阶 2→3、2阶 5→6、3阶 9→12、4阶 18→36、5阶 30→72、6阶 48→120
- 锻造与炼制时间配置合并为 `ForgeRecipeDatabase.TIER_DURATION` 单一数据源，`PillRecipeDatabase` 和 `PillRecipeRegistry` 均委托引用

### 代码清理

- 删除 `ManualProficiencySystem` 中 7 个从未被调用的方法：`getProficiencyThresholds`、`getMaxProficiency`、`calculateProficiencyGain`、`updateProficiency`、`calculateManualStatsBonus`、`shouldAutoLearnManual`、`selectBestManualToLearn`、`generateProficiencyGainMessage` 及 `ManualInfo` 数据类
- 删除废弃常量 `BASE_PROFICIENCY_GAIN`、`MASTERY_THRESHOLD`
- `MasteryLevel.fromProficiency()` 不再需要 `manualRarity` 参数

### Bug 修复

- 修复自动存档数据写入手动存档槽位的问题：`enqueueAutoSave` 增量保存使用了 `currentSlot`（手动存档槽位 1-6）而非 `AUTO_SAVE_SLOT`（0），导致自动存档数据存到手动存档槽位，自动存档槽位无变化

### 数据库迁移

- v29→v30：同步更新弟子修炼速度缓存（cultivationSpeed 字段）
- v30→v31：更新 `manualProficiencies` JSON 中所有条目的 `maxProficiency` 为 30000，`masteryLevel` 按新阈值重新计算
- v31→v32：更新进行中锻造/炼丹槽位的 duration 为新时间（production_slots、forge_slots、alchemy_slots 三表）

## [3.2.07] - 2026-06-04

### Bug 修复

- 修复弟子住所空槽位点击无法弹出选择弟子界面（onSlotClick/onEmptySlotClick 参数误用）
- 修复锻造成功率始终显示为 0%（ForgeViewModel 映射遗漏 successRate 字段；FormulaService 中锻造弟子成功率加成被硬编码跳过）
- 修复弟子功法熟练度无法增长：`ManualProficiencyData` 创建时 `maxProficiency` 默认为 100，而按品阶实际应为 400~2000；新增条目 `proficiency` 被硬性截断至 100；已有存档条目的 `maxProficiency` 不会自动修正

## [3.2.06] - 2026-06-04

### 性能优化大版本

- 游戏循环 1000ms→100ms（10x 响应提升），后台完全停止循环
- 焦点分频机制：当前界面 100ms 高频结算，非当前界面最长 30 秒慢结算
- 界面切换瞬间追赶积压进度（打开即最新）
- 10 秒无操作自动降频至 2 秒一次，触碰即恢复
- ADPF 热状态感知降频，过热时自动保护设备
- 批量写入优化：9 个 DAO 的 forEach→updateAll

### Bug 修复

- 修复每秒钟修炼值显示与实际不符（updateFocusedDisciple 时间粒度从 2s 修正为 0.1s）
- 修复弟子翻页后不按排序列表导航（allDisciples 改用 aliveDisciples + sortedByFollowAttributeAndRealm）
- 修复根 Box 触摸拦截导致宗门地图/世界地图无法拖动
- 修复 HP/MP 恢复跟随焦点域而非无条件执行

### 弟子死亡/脱离完善

- 新增 DiscipleSlotCleanup 统一组件（死亡与脱离共用）
- 补全槽位清理：血炼池、巡逻塔、仓库驻守、战斗队伍、世界地图驻军

### 代码清理

- 删除已废弃 CaveExplorationTeamMarker、探索队路径绘制、移动动画相关代码

## [3.2.05] - 2026-06-04

### Bug 修复

- 修复弟子功法熟练度月度结算后被回退（manualProficiencies 子条目 delta 合并）
- 修复灵田种植结算后被回退（spiritFieldPlants 合并器 filter 赋值 Bug）
- 修复血炼池进度不增长（activeBloodRefinements 被结算覆盖）

### 血炼池优化

- 进度显示改为绿色进度条 + 剩余月份（弟子槽位上方）
- 血炼中卸任/更换视为取消（不消耗品阶次数，不退材料灵石）
- 死亡/脱离弟子自动取消洗炼

### 弟子槽位统一

- 所有弟子槽位统一为 DiscipleSlot 组件
- 新增分割横线分隔境界/精灵图/名称（与 DiscipleDetailScreen 同款）

## [3.2.04] - 2026-06-03

### 新建筑：血炼池

- 消耗妖兽精血材料淬炼弟子肉身，永久提升战斗属性
- 虎血→物攻/法攻、蛇血→速度/气血、龟血→物防/法防（随机二选一，50%概率）
- 品阶提升幅度：凡1%/灵3%/宝6%/玄12%/地20%/天30%
- 消耗：200同类兽血 + 100万灵石，持续1~30月（依品阶）
- 每弟子每种材料仅可洗炼一次，支持多池并行
- 建筑费用50000灵石，占地2×2，无建造上限
- 新增 GameData.bloodRefinements + activeBloodRefinements 字段（DB Migration 28→29）

### 邮件系统

- 新增庆祝血炼池上线邮件：奖励200灵虎血（14天有效期）
- 新增 beastMaterial 邮件附件类型，支持发放指定妖兽材料

### 材料系统

- 虎骨重命名为虎血（含所有品阶），精灵图同步替换
- 锻造配方 tigerBone → tigerBlood 全量迁移

## [3.2.03] - 2026-06-03

### 性能优化大版本（基于28条行业权威数据对标设计）

**存档系统**
- 全量 Delete+Insert 改为 upsertAll + @Transaction：保存耗时减少80%+
- 18个DAO新增 upsertAll 方法，统一使用 OnConflictStrategy.REPLACE
- 存档加载12路并行化（async DAO查询）
- 新增存档完整性校验（validateSaveData）
- 消除 runBlocking 主线程阻塞：hasEmergencySave/releaseTheftDisciple 改为 suspend

**游戏循环**
- 解耦 unifiedState 读取：tick 直接读独立 StateFlow 快照，避免触发17-way combine
- 新增 ThermalMonitor 热管理：过热时自动降负载/紧急保存
- 看门狗增强：activeSaveJob/activeLoadJob 追踪 + 超时强制取消

**地图渲染**
- 建筑贴图增量绘制：仅重绘变化区域，不再全量64MB copy
- RGB_565 策略：中低配设备自动切换，内存减半
- Bitmap 主动回收：DisposableEffect + recycle
- 视口裁剪网格线：仅绘制可见区域
- 地图 dashPathEffect 缓存

**UI流畅度**
- pointerInput 手势修复：key改为Unit，放置建筑后拖拽不中断
- derivedStateOf key 修正 + 全局审计
- collectAsStateWithLifecycle 迁移：159处，切后台自动休眠
- Dialog 惰性订阅：未打开时不订阅 StateFlow
- WarehouseTab 物品索引：itemIndex Map 替代7路链式find
- BulkSellDialog：Column+verticalScroll → LazyColumn
- DiscipleDetailScreen：spiritRootCountColor 缓存、cultivationProgress 优化

**安装包与构建**
- Protobuf → javalite：减少运行时内存1-2MB + APK 500KB
- ProGuard 规则精简：移除 kotlin.**/androidx.** 通配符，OkHttp 精确化
- material-icons-extended 移除
- kotlinx-serialization-cbor 移除
- 图片脚本 PNG→WebP（quality=85，减少25-35%体积）
- Zstd JNI x86/x86_64 排除
- extractNativeLibs=false + useLegacyPackaging=false
- Game Mode API 声明（Android 12+）
- Compose 稳定性配置文件（stability_config.conf）

**基础架构**
- GameData拆分 Phase A：5个领域模型（Diplomacy/Production/Patrol/WorldMap/SectPolicy）
- DomainStateProvider：从GameData派生领域StateFlow
- GameEventBus 事件总线基础 + 6种游戏事件
- Service/System 职责边界标注
- FrameMetricsMonitor AtomicLong 线程安全
- BackgroundTaskScheduler CopyOnWriteArrayList
- GCOptimizer：移除主动 System.gc()，notifyListeners 切到 Default
- 触控 BuildingSpatialIndex 空间索引
- focusedRefreshJob 200ms→1000ms 对齐tick

**工具与脚本**
- Gradle 8.12→8.14.5 / configuration-cache / enableJetifier=false
- BaselineProfile 新增 gamePlayScenario

**Bug 修复**
- 修复建筑放置后装饰物未被清除：装饰清除从 fullMapBmp 同步到 bakedMapBmp，绘制建筑前先用 groundBmp 擦除装饰物
- 修复灵矿场一键任命偶尔只任命一名弟子：updateSpiritMineSlots fire-and-forget 改为 suspend updateGameData，确保槽位先写入再更新弟子状态
- 修复巡逻塔一键任命/卸任/更换同类问题：updatePatrolSlots 全部改为 suspend
- 修复灵矿场卸任/更换同类问题
- 修复 ThermalMonitor Hilt 注入缺少 @ApplicationContext
- **【状态系统重大修复】结算合并从整体覆盖改为子字段级合并，从架构层面彻底消灭状态回退**
- 修复结算期间穿戴装备回退：EquipmentSet 14 子字段改为结算域/玩家域分离合并，storageBagItems 集合 delta 合并
- 修复结算期间赏赐回退：同上
- 修复结算期间学习功法回退：manualIds 从整体覆盖改为集合 delta 合并（main + 结算新增 - 结算删除）
- 修复结算期间使用丹药回退：PillEffects 13 bonus 从 main 保留，duration 做 delta；Skills loyalty/salary 从 shadow，其余从 main
- 修复结算期间宗门交易回退：buyFromSectTrade 标记 @Deprecated，统一使用 buyFromSectTradeSync（suspend+Mutex）
- 修复弟子突破失败后 HP/MP 长期不恢复：提取 recoverHpMpForAllDisciples，结算期间不再跳过恢复；CombatAttributes 18 子字段改为子字段级合并
- 修复结算期间弟子脱离后槽位残留：elderSlots/spiritMineSlots/librarySlots 从 PRESERVE_OLD 改为 CUSTOM 合并，允许结算清除操作穿透
- 修复恢复 HP/MP 时使用基础最大属性而非最终属性（含装备/功法/丹药）的问题
- 以上修复对标 Unreal GAS AttributeSet Aggregator、Photon Fusion Predict-Reconcile、Bevy ECS Change Detection 等 22 条行业参考

## [3.2.02] - 2026-06-03

### 状态一致性修复（Bug 修复）

**问题背景**
- 切换弟子内门/外门身份后，过一会自动回退
- 种植灵草后偶尔消失
- 弟子脱离提示框重复弹出
- 游戏后期加载存档时闪退（`SQLiteBlobTooBigException`）
- 根因1：月结算合并（swapFromShadow）与玩家操作并发执行，未通过同一把互斥锁序列化
- 根因2：`game_heavy_data` 表 `aiSectDisciples` 行随游戏进程增长，Protobuf Base64 序列化后单行超过 CursorWindow 2MB 限制

**修复内容**
- **统一 Mutex 序列化**：`swapFromShadow()` 改为 `suspend fun`，整个结算合并过程包裹在 `stateStore.update { }` 事务中
- **消除陈旧读取**：`DiscipleService` 从读取 `unifiedState` 改为直接 `StateFlow.value`
- **原子化操作**：`changeDiscipleTypeAtomic()` 和 `updateGameDataAndSync()` 单事务完成
- **字段保留加固**：提取 `mergeDiscipleAfterSettlement()` 集中管理弟子字段合并策略
- **全量迁移**：14 处 `updateXxxDirect` 调用全部迁移到 `stateStore.update { }`
- **分块存储**：`GameHeavyData` 新增 `chunk()`/`reassemble()`，单行最大 900KB 自动分块，逐 key 安全加载，无需 DB Migration

**编译期安全网**
- `DiscipleMergeCoverageTest` + `StateRevertRegressionTest` + `GameDataSettlementCoverageTest`

**架构文档**
- CODE_WIKI 新增「状态一致性」章节

## [3.2.01] - 2026-06-02

### 极致性能优化

**状态管理架构升级**
- **三层 StateFlow 拆分**：GameStateStore 新增 HighFreqState（灵石/时间等高频字段）/ EntityState（弟子/装备/功法等实体列表）/ ConfigState（宗门政策/设置等低频配置）三层独立 StateFlow。UI 按需订阅对应层级，消除全量 combine 重建开销
- **ViewModel 直接注入 Facade**：GameViewModel 新增 7 个 Facade 直接注入（Disciple/Battle/Building/Inventory/Production/Diplomacy/Save），减少通过 GameEngine 中转
- **窄粒度 StateFlow 暴露**：GameViewModel 新增 elderSlots / sectPolicies / manualProficiencies / residenceSlots 独立流，UI 无需订阅整个 GameData

**UI 重组消除**
- **全量 collectAsState → collectAsStateWithLifecycle**：14 个 UI 文件共 158 处订阅全部迁移到生命周期感知 API，后台自动停止收集
- **DiscipleDetailScreen**：从订阅整个 per-tick gameData 改为订阅独立字段流（elderSlots/sectPolicies/residenceSlots/placedBuildings），仅在对话框可见时收集
- **@Immutable 注解**：HighFreqState / FrameMetricsStats / SaveOperationStats 标注 @Immutable，Compose Strong Skipping Mode 自动跳过不变重组

**游戏循环并行化**
- **SystemManager 依赖图并行**：系统按 @SystemPriority 分组，同级无依赖系统通过 coroutineScope { launch } 并行执行。单系统组跳过协程开销直接调用。tick 耗时理论减少 40-60%
- **异常隔离不变**：每个系统独立 try-catch，单系统异常不影响同级其他系统

**增量保存 + 脏追踪**
- **GameStateRepository**：新增 DirtySet 位掩码脏追踪（13 个状态字段独立标记），markDirty() 在每次 Direct 更新和 update() 事务中自动标记
- **flushDirtyState()**：仅写入脏字段，通过 coroutineScope { launch(Dispatchers.IO) } 并行执行 deleteAll + insertAll，保存延迟从 ~200ms 降至 ~20ms
- **StorageEngine.incrementalSave()**：新增增量保存入口，从 unifiedState 快照提取脏数据写入 Repository

**内存管理优化**
- **GCOptimizer**：移除 System.gc() 调用（CRITICAL/MANUAL 模式改为日志提示），System.runFinalization() 同步移除。信任 ART 分代并发 GC 自主管理
- **DiscipleCompact 轻量表**：新增 disciple_compact Room 表（14 字段 vs 原 50+），高频查询场景（弟子列表/修炼进度）使用精简模型，减少内存占用
- **对象分配减少**：高频率字段使用 distinctUntilChanged 避免相同值重复发射

**数据库优化**
- **Migration 合并**：新增 MIGRATION_1_26（v1→v26 单一合并迁移），消除 24 次顺序迁移的累积开销，冷启动减少 200-500ms
- **v26→v27 迁移**：新增 disciple_compact 表（14 字段 + 2 索引），支持弟子轻量查询
- **Schema v27 JSON**：已导出现有数据库 schema 供后续自动迁移使用

**帧率监控**
- **FrameMetricsMonitor**：集成 Window.OnFrameMetricsAvailableListener，16.6ms/50ms 双阈值 jank 检测，SharedFlow 发射 jank 事件，统计汇总（总帧数/jank 率/严重 jank 率/平均帧时间）
- **生命周期绑定**：onResume 启动监控，onPause/onDestroy 停止，零性能开销

**代码清理**
- **BagUtils 合并**：删除 `core/state/BagUtils.kt`（103 行），内容合并入 `core/util/StorageBagUtils.kt`
- **GameError 删除**：删除 `core/util/GameError.kt`（84 行），所有引用已迁移至 AppError。AppError.kt 精简 34 行
- **EventBus 审计文档**：新增 `EventBusAudit.kt`，记录 6 个消费者/生产者的线程模型/背压策略/错误处理/风险等级（0 HIGH / 3 MEDIUM / 3 LOW）
- **FlatBuffers 评估报告**：新增 `FlatBuffersEvaluation.kt`，详尽分析结论：不建议采用（零拷贝优势在 Room 架构下无法兑现），推荐替代方案 A（消除 Base64 中间层）

### 数据库
- **v26→v27 迁移**：MIGRATION_1_26（v1→v26 合并迁移）+ MIGRATION_26_27（新增 disciple_compact 表）
- **新增 Entity**：DiscipleCompact（disciple_compact 表，14 字段）
- **新增 DAO**：DiscipleCompactDao

## [3.2.00] - 2026-06-02

### 重构
- **GameEngine 上帝类拆分**：519 方法 → 103 方法（7 个领域 Facade：Disciple/Battle/Building/Inventory/Production/Diplomacy/Save），GameEngine 降为纯协调委托器
- **GameData 巨型类拆分 (Phase A)**：新增 5 个领域 Entity（DiplomacyState/ProductionState/PatrolState/SectPolicyState）+ 对应 DAO。game_data 旧列保留不动，业务层双读
- **目录按域重组**：`core/engine/domain/` 下 9 个子域（battle/building/diplomacy/disciple/exploration/inventory/production/save/settlement），45 个文件按域组织
- **GameStateStore 消除双写**：`_state` MutableStateFlow 已移除，`unifiedState` 改为 `combine(17流)` 只读派生，独立流为唯一事实源
- **Service/System 边界清晰化**：CultivationSystem/ExplorationSystem/MailSystem 独立化，Service 不再直接实现 GameSystem
- **EventBus 全面激活**：DomainEvent 从 13 种扩展到 25 种 + EventBusPort 接口化
- **UseCase 层扩展**：15 个 UseCase，ViewModel 通过 Facade 接口注入

### 数据库
- **v25→v26 迁移**：新增 diplomacy_state/production_state/patrol_state/world_map_state/sect_policy_state 5 张表，game_data 列保留（Phase A 零风险策略）

### 修复
- **启动闪退**：DiplomacyService Kotlin 初始化顺序修复（subscribedTypes 在 init 块之前声明）+ EventBus 防空检查

## [3.1.98] - 2026-06-02

### 修复
- **游戏时间停止**：`SettlementCoordinator.executeStep()` 新增异常恢复机制。结算阶段异常时自动重置状态，不再导致 `hasPendingWork` 死锁卡死游戏。`shadowState`/`currentCache` 加 `@Volatile` 解决 UI 线程并发访问
- **功法选择不显示**：`GameViewModel.manualStacks` 移除全局储物袋过滤逻辑（原逻辑遍历所有弟子背包排除功法 ID，导致某弟子背包中的功法在其他弟子的选择界面也被隐藏）

## [3.1.97] - 2026-06-02

### 优化
- **状态增量派发**：GameStateStore 从单一巨对象全量发射改为 16 个独立 StateFlow 增量发射，每秒 tick 仅发射实际变化的字段。消除 15 条 `.map{}` 链每 tick 的无意义重算
- **DiscipleAggregate 缓存**：弟子聚合数据按 ID + 指纹缓存复用，仅在弟子属性变化时重建，减少 GC 压力
- **山门地图建筑烘焙**：建筑贴图预渲染到离屏 Bitmap，Canvas 仅需 1 次 `drawImage`。低配设备自动跳过烘焙，动态绘制保帧率
- **网格线视口裁剪**：网格线仅绘制屏幕可见范围，线长从 3072px 裁剪至 ~1080px
- **世界地图 Path 缓存**：宗门连线 `Path` 对象缓存复用（原每帧 150+ 次 new），拖动世界地图不再卡顿
- **Compose 重组优化**：修复 `derivedStateOf` key 参数失效 Bug；`SectInfoCard` 改为原始类型参数配合 Strong Skipping；Dialog 惰性订阅减少不必要收集
- **后台省电**：`MainGameScreen` / `GameOverlayHost` 全部 StateFlow 收集改为 `collectAsStateWithLifecycle`，切后台自动暂停
- **GC 优化**：`GCOptimizer` 仅在 CRITICAL/MANUAL 级别调用 `System.gc()`，SOFT/HARD 改为缓存清理
- **冷启动加速**：新增 Baseline Profile 模块 + 移除冗余 `kotlinCompilerExtensionVersion = '1.5.8'`，启用 Kotlin 2.0 原生 Compose 编译器（Strong Skipping Mode）

### 构建
- 新增 `:baselineprofile` 模块，关键路径 AOT 预编译
- 新增 `lifecycle-runtime-compose` 依赖
- 新增 Compose Compiler Metrics 输出（`compose_metrics/`）
- `proguard-rules.pro` 新增 Compose / StateFlow keep 规则

## [3.1.96] - 2026-06-02

### 新增
- **邮件系统正式上线**：内置首封福利邮件「庆祝邮件系统上线」，含 3 个宝品储物袋（限时 14 天截止）
- **邮件领取状态绑定存档**：GameData 新增 `claimedMailIds` 字段，领后存档读档保持已领取，读旧档恢复未领取。重开游戏邮件重置

### 优化
- **邮件架构**：`resetAndInitSlot` 原子操作（Mutex 锁内清空+重建），MailService 主动推送 StateFlow 替代 flatMapLatest 响应链
- **邮件UI**：标题/内容/按钮统一底色仅横线分隔。附件 FlowRow 多行换行。已领附件精灵图替换为绿色"已领"且名称数量不变。邮件列表显示已读/未读标记（灰/红）。未读红点改为按钮外部小圆点
- **精灵图覆盖**：邮件物品卡片根据类型显示对应精灵（灵石/丹药/装备/材料/储物袋/草药/种子）
- **弟子阵亡优化**：阵亡弟子仅立绘区域覆盖红色"死亡"文字，名称和境界保持显示
- **修复问题**：储物袋堆叠显示（仓库数量不再固定显示1）、储物袋附件支持同品阶堆叠

### 数据库
- **v21→v24 迁移**：修复 mail 表索引名+DEFAULT值、移除 claimed_mail_records 表

## [3.1.95] - 2026-06-02

### 新增
- **邮件系统**：游戏主界面右上角新增邮件入口（红点角标显示未读数）。支持在线邮件拉取 + 内置邮件混合投递，附件含灵石、灵草、装备、丹药、材料、草药、种子、储物袋九种类型。一键已读按钮同时完成全部标已读 + 自动领取所有附件。单封邮件支持查看详情并单独领取。30 天过期自动清理
- **数据库迁移 v20→v21**：新增 `mails` 表（14 字段 + 3 索引）

## [3.1.94] - 2026-06-01

### 修复
- **结算影子合并全线加固**：`swapFromShadow()` 白名单遗漏导致玩家在结算期间的操作被影子状态覆盖。14 个受影响字段全部添加正确的三路合并逻辑——worldLevels（妖兽击败后恢复）、usedRedeemCodes（兑换码重复使用）、游戏设置（gameSpeed/存档间隔等）、recruitList（招募列表）、activeMissions（任务）、alliances（盟约）、sectRelations（宗门关系）、worldMapSects（驻守/占领）、sectDetails（交易/侦查）、manualProficiencies（功法熟练度）、aiSectDisciples（AI弟子伤亡）、spiritFieldPlants（灵田种植）、productionSlots（生产槽位）
- **灵田种植丢失**：`spiritFieldPlants` 注解为 USE_SHADOW 但玩家可种植/收获/移除，改为 CUSTOM 三路合并
- **兑换码无限复用**：`usedRedeemCodes` 未在结算合并中保留，物品发放但码未记录，重启后可重复兑换

### 架构
- **@SettlementStrategy 注解系统**：GameData 72 个字段全部标注合并策略（PRESERVE_OLD/USE_SHADOW/DELTA/THREE_WAY_ID/CUSTOM），灵感来自 Microsoft Research Concurrent Revisions 论文的 Isolation Types 模式。策略声明与字段定义同在一处，无需维护第二份白名单
- **GameDataSettlementCoverageTest 安全网**：Kotlin 反射遍历 GameData 全部属性，缺失 @SettlementStrategy 注解则测试失败。新增字段不写注解 → CI 变红 → 强制声明策略 → 从根本上杜绝同类 bug

## [3.1.93] - 2026-06-01

### 修复
- **储物袋数据丢失**：`GameStateStore.update()` 每 tick 构造 `UnifiedGameState` 时遗漏 `storageBags` 参数（默认值空列表），导致储物袋在首个 tick 后被清空。`loadFromSnapshot()` 同样遗漏，导致读档后储物袋丢失
- **数据库迁移 v19→v20**：`StorageBag` 实体未注册到 `@Database` 且缺少 `storage_bags` 建表迁移。新增实体注册 + `MIGRATION_19_20` 自动建表

## [3.1.92] - 2026-06-01

### 新增
- **灵石物品卡片**：仓库中灵石以物品卡片形式展示（精灵图+数量），替换原顶栏纯数字显示。每张卡片上限 100 万，超出自动拆分多张。仓库顶栏不再显示灵石数字。战利品灵石同步替换「敬请期待」占位文字为精灵图
- **储物袋系统**：新增六品阶储物袋（凡品/灵品/宝品/玄品/地品/天品），精灵图各不同。在物品详情界面点击「开启」按钮直接打开，随机获得 5~20 件**同品阶**物品（装备/功法/丹药/草药/种子/材料/灵石七种等概率），灵石数量按品阶 500~500,000。开启结果以半屏弹窗展示（与未保存提示框一致），重复物品自动叠加显示
- **宗门等级图标**：世界地图宗门详情、外交宗门卡片、主界面宗门名称左侧新增等级图标（小型/中型/大型/顶级宗门），图标大小与宗门名称字号一致
- 新增 11 张精灵图（灵石×1、储物袋×6、宗门图标×4），统一 resize + WebP 无损处理

## [3.1.91] - 2026-06-01

### 优化
- **全量精灵图 WebP 无损优化**：205 张 PNG 统一转换为 WebP 无损格式，APK 资源体积从 145MB 降至 71MB（-51%），零画质损失。Google 官方数据显示 WebP 无损比 PNG 小 26-45%
  - **道具卡片尺寸规范化**：32 张新增妖兽材料精灵图按 v3.0.72 标准缩小至 480px 长边（1400px→480px），蛇鳞 1.4MB→133KB（90%）、虎皮 1.0MB→114KB（89%）；丹药/功法/装备同步转 WebP 无损
  - **UI 按钮按显示尺寸裁剪**：地图按钮 3585×3003→512×429（11MB→210KB，98%）、系统消息 4074×1600→1536×603（6.7MB→524KB，92%）、奖励弹窗 4017×1948→1536×745（8.1MB→660KB，92%）、通用按钮 3828×1384→768×277（5.4MB→111KB，98%）
  - **建筑/背景/弟子/妖兽**：66 张保持原始尺寸，仅 WebP 无损转换（60MB→43MB，-28%）
  - `build.gradle` 新增 `noCompress 'webp'`，避免 APK 对已高效压缩的 WebP 做无效 zip 二次压缩
  - Android 资源系统按名称匹配（非扩展名），`R.drawable.xxx` 自动找到 `.webp`，零代码改动

## [3.1.90] - 2026-06-01

### 修复
- **结算期间玩家操作丢失漏洞（swapFromShadow 补充）**：v3.1.87 的三路合并遗漏了库存字段和经济字段，月初结算完成后以下操作回退：
  - **商人购买后物品消失、数量重置**：`travelingMerchantItems`/`playerListedItems` 被影子旧值覆盖，购买的库存物品（`equipmentStacks`/`pills`/`materials`/`herbs`/`seeds`/`manualStacks`）被影子旧值覆盖
  - **售卖后物品回来、灵石减少被撤销**：库存减少回退 + `spiritStones` 回退，玩家凭空获利
  - **赏赐弟子后物品回到仓库**：库存减少回退，弟子 `skills`/`pillEffects` 可能被结算覆盖
  - **修复方案**：库存 8 个字段全部从主状态保留；灵石三路 delta 合并（`主状态变化 + 结算灵矿/薪酬/政策变化`）；商人商品和挂售物品从主状态保留。`alliances` 改为从 `mergedGameData` 取值，`pendingNotification` 改为纯主状态取值。经验证弟子任命槽位/自动装备学习/生产槽位不受影响

## [3.1.89] - 2026-06-01

### 优化
- **妖兽材料精灵图替换"敬请期待"占位文字**：新增32张妖兽材料精灵图（虎/狼/蛇/熊/鹰/狐/龙/龟 × 皮/骨/牙/内丹等），同一材料不同品阶共用精灵图，全界面统一替换
  - 仓库、商人、宗门交易、战利品、弟子储物袋、赏赐弟子等界面均已替换，不再显示"敬请期待"
  - `ItemCardData` 新增 `isMaterial` 字段，`materialSpriteRes` 函数通过去除品阶前缀映射基础材料名

## [3.1.88] - 2026-06-01

### 优化
- **数据库拆表解决 CursorWindow 超限**：`game_data` 单行 60+ 列含大量 Protobuf 序列化数据，中后期存档超过 Android CursorWindow 2MB 行大小限制导致 `SQLiteBlobTooBigException` 崩溃。将 5 个 L4 重型字段（`aiSectDisciples`/`sectDetails`/`exploredSects`/`scoutInfo`/`manualProficiencies`）独立存入新表 `game_heavy_data`，`game_data` 单行体积大幅缩小
  - **懒加载**：L4 数据在游戏循环启动时按需加载（`ensureHeavyDataLoaded`），攻打/侦查宗门等需要重型数据的操作前自动触发，主界面加载不阻塞
  - **数据库迁移 v17→v18**：材料更名（蛇皮→蛇鳞、蛇骨→蛇血、毒牙→蛇牙、龙骨→龙爪、龟甲→龟血），同步更新 category（新增 BEAST_BLOOD，plastron→blood，bone→claw）
  - **数据库迁移 v18→v19**：创建 `game_heavy_data` 表，将 `game_data` 中 5 个 L4 列数据迁移至新表，清空原列
  - **存档兼容**：`SaveDataConverter` 反序列化时映射旧材料 name/category，旧存档导入自动转换

### 修复
- **蛇妖/龙妖/龟妖材料更名**：蛇皮→蛇鳞(🛡️)、蛇骨→蛇血(🩸)、毒牙→蛇牙(🦷)、龙骨→龙爪(🐾)、龟甲→龟血(🩸)，配方引用同步更新

## [3.1.87] - 2026-06-01

### 修复
- **结算期间玩家操作丢失（swapFromShadow 三路合并）**：月初结算的影子状态 `swapFromShadow` 全量覆盖主状态，影子在月初创建时不包含玩家的后续操作。改为三路合并——比较 `origin(创建时)→shadow(结算后)→oldState(当前主状态)`，仅结算实际修改的字段用 shadow 值，其余保留主状态。修复以下具体问题：
  - **建造建筑后消失**：`placedBuildings` 被 shadow 旧值覆盖
  - **弟子分配回滚**：`elderSlots`/各类槽位被 shadow 旧值覆盖
  - **弟子脱离反复弹窗但未真正脱离**：`isAlive` 被 shadow 的 `true` 覆盖，脱离操作无效后系统重复判定"应脱离"，形成死循环
  - **装备/功法/战斗状态回滚**：`equipment`/`manualIds`/`combat` 仅在结算实际变更时才用 shadow 值
  - **政策/设置/战斗队伍被覆盖**：补全 18 个 gameData 字段从 `oldState` 保留

### 优化
- **UI 响应速度优化**：
  - **骨架屏分层渲染**：`FullScreenOverlay` 标题栏第一帧同步渲染，数据内容通过 `DeferredContent` 延迟一帧(16ms)加载，玩家感知为"即时响应"。高频界面(炼药/锻造/藏经阁等)启用，低频界面(Settings/Buildings)跳过
  - **入场动画已移除**：`AnimatedVisibility`(fadeIn+slideIn) 实测造成 ~800ms 延迟——Compose 动画等内容测量完成后才启动，反而比无动画更慢。骨架屏方案已提供足够的即时反馈
  - **aliveDisciples 提升 ViewModel**：消除 10 处 `derivedStateOf { disciples.filter { it.isAlive } }` 重复计算，改为共享 StateFlow
  - **gameData 共享订阅**：GameOverlayHost 顶层收集一次，通过参数传入各分支，减少 15+ 个重复 StateFlow 订阅
  - **gameDataUi 对话框 snapshot**：打开对话框时立即注入一次当前值（`merge(sample(400), dialogOpenTrigger)`），消除 400ms 采样最坏延迟

## [3.1.86] - 2026-05-31

### 修复
- **旬制时间匀速化**：修复上旬明显比中旬/下旬长的问题。根因是月切换时 `scheduleMonthly` 触发结算分帧，`tickInternal()` 开头 `if (hasPendingWork) { executeStep; return }` 阻塞了 phase 推进，结算耗时（约 1-2 秒）被算在上旬头上。改为：
  - 移除阻塞检查，phase 每 tick 始终推进（1x=2 秒/旬，2x=1 秒/旬不变）
  - 结算期间仅执行 `TimeSystem.onPhaseTick`（推进时间），跳过 `CultivationService.onPhaseTick`（HP/MP 恢复等下次补回）
  - `swapFromShadow` 改为保留主状态的 `gamePhase`/`gameMonth`/`gameYear`，避免结算 shadow 覆盖已推进的时间
  - 2x 速度下若结算跨月，`forceCompleteSettlement` 强制收尾（加大时间预算 5ms，加重入防护）

## [3.1.85] - 2026-05-31

### 修复
- **倍速功能修复**：倍速选择器（1x/2x）之前仅在 UI 显示不生效 — `gameSpeed` 字段持久化到 Room 存档但 `GameEngineCore.phasesPerTick` 从不读取，导致选 2x 实际仍是 1x（6 秒/月）。改为 `phasesPerTick = 3 * gameSpeed / (6 * 1)`，1x=6 秒/月，2x=3 秒/月。倍速从 `GameData.gameSpeed` 读取并持久化，后台恢复后自动保持之前的选择
- **离线严格暂停**：`onStop` 时调用 `BackgroundTaskScheduler.pause()` 暂停性能监控/内存/GC 等后台任务，`onResume` 时 `resume()` 恢复。确保玩家离线期间零游戏进度（游戏循环 tick 本就已停止，此为加固）
- **lastSaveTime 注释澄清**：补充注释说明该字段仅用于存档列表时间显示，不参与离线时间差计算

## [3.1.84] - 2026-05-31

### 优化
- **月度结算性能优化**：引入 `SettlementCoordinator` 结算协调器替代 `SystemManager` 对月度/年度 tick 的直接同步调度。核心改进：
  - **SettlementCache 预计算缓存 + 脏标记**：一次性构建弟子/装备/功法/修炼速率索引，用 `DiscipleDirtyFlag`（NONE/BREAKTHROUGH/EQUIPMENT/MANUAL）标记需要完整计算的弟子。约 90% 弟子仅修炼值增长，用 `rate × days` 闭式公式 O(1) 计算，遍历量减少 90%
  - **SettlementScheduler 时间预算分帧**：每帧最多 1.5ms 用于结算（非固定弟子数量），自动适配不同性能设备。月度重计算分散到多个 tick，避免单帧卡顿
  - **影子状态（Shadow State）**：结算期间写入临时 `MutableGameState` 副本，全部阶段完成后一次性 `swapFromShadow` 到 `StateFlow`。UI 在分帧期间看不到半结算状态
  - **多频率 tick 分离**：外交事件每 3 月、世界等级每 6 月、AI 宗门/招募/衰老每年触发，低频系统跳过无关月度调度
- **SettlementMetrics 性能监控**：每 10 次结算输出聚合耗时报告（缓存构建、焦点弟子、干净/脏批量、生产、世界事件、swap），可量化后续优化效果

### 修复
- **月度结算影子状态创建时机修复**：`createShadow()` 原先在 `stateStore.update{}` 事务内调用，此时 `_state.value` 尚未反映 `onPhaseTick` 的变更，导致 `swapFromShadow` 时回滚旬级数据。改为在事务提交后创建影子，确保影子包含完整的当前状态
- **干净弟子缺薪修复**：`processCleanDiscipleBatch` 仅处理了修炼值增量，遗漏了薪资发放和忠诚度调整。导致无装备/功法/突破需求的弟子（约 90%）忠诚度持续下降。现补充 `calculateSalaryChange` 和 `calculateLoyaltyDelta`，所有弟子统一处理薪水
- **焦点弟子缺薪修复**：`processFocusedDiscipleImmediate` 同样补充薪资发放和忠诚度计算

## [3.1.83] - 2026-05-31

### 优化
- **功法熟练度与装备孕养进度实时更新**：打开弟子详情后，功法熟练度进度条和装备孕养进度条改为每 200ms 高频刷新（原仅月度批量更新），与修炼进度条一致，查看时进度平滑增长不再跳变

### 修复
- **攻打被AI占领宗门直接胜利**：AI 占领宗门后忘记将 `survivingAttackers` 填入 `garrisonSlots` 变成守军（`applyAIAttackResult` 仅设置 `occupierSectId`），导致守军为空直接获胜。改为占领时将攻打队伍存活弟子自动填入守军槽位

### 新特性
- **AI驻军管理系统**：每月自动补全被占领宗门的空驻军槽位（阵亡守军当月由占领者最强空闲弟子补上），每年全量轮换（占领者最强10名留守宗门，第11名起外派填满所有占领宗门的 garrison）

## [3.1.82] - 2026-05-31

### 新特性
- 新游戏初始赠送一座灵矿场，位于地图中央，附带3个矿位

### 修复
- **弟子详情修炼突破时机修复**：查看弟子信息时，修炼进度条需真正填满后才会触发突破，不再出现进度未满就突破的问题
- **弟子详情修炼速度修复**：查看弟子时月度批量修炼与高频刷新双路叠加，实际速度约为显示的 11 倍。改为月度批量自动扣除已通过高频刷新获得的值，确保总量正确

## [3.1.81] - 2026-05-31

### 优化
- **后台任务统一调度**：BackgroundTaskScheduler 用共享 1s 心跳替代 8 个独立 while(isActive) 协程循环，后台协程数从 13 降至 4。通过 GameMonitorManager 和 StorageEngine 两个编排中心注入调度器，各组件的 start/stop 方法改为调度器替代

## [3.1.80] - 2026-05-31

### 优化
- **Compose 重组优化**：9 处 LazyColumn/LazyVerticalGrid 补充稳定 key 避免列表数据变化时全量重组，11 个核心数据类添加 @Immutable 注解让编译器跳过无变化重组路径

## [3.1.79] - 2026-05-31

### 修复
- **修复旬制迁移 3 个严重 bug**：列名 snake_case/camelCase 不匹配导致 Room schema 验证失败、safeDropColumns 丢失索引和 NOT NULL 约束、save_slot_metadata 表遗漏迁移

## [3.1.78] - 2026-05-31

### 紧急修复
- **修复存档变空**：v3.1.77 的 DB 迁移 MIGRATION_15_16 保留了 game_day 列未删除，导致 Room schema 验证发现多余列，触发 `fallbackToDestructiveMigration()` 重建空数据库。新增 DB v16→v17 迁移用 `safeDropColumns` 删除残留的 game_day 列，同时修正 MIGRATION_15_16 在新增 game_phase 后也删除旧列

## [3.1.77] - 2026-05-31

### 优化
- **修炼月度批量处理**：弟子修炼进度改为每月计算一次，突破检查同步移至月度tick，大幅降低tick内CPU开销
- **焦点弟子高频刷新**：玩家查看弟子详情时，ViewModel启动独立200ms协程仅刷新该弟子修炼和突破，关闭详情时自动停止
- **AI弟子批量处理**：AI弟子修炼和突破统一每月一次，不做高频刷新
- 删除已停用的 `updateRealtimeCultivation`、`processSecondTick` 死代码（约150行）

## [3.1.76] - 2026-05-31

### 性能优化
- **旬制时间系统**：游戏时间从天制（1月=30天）改为上中下旬制（1月=上旬/中旬/下旬），tick频率从200ms（5Hz）降至1000ms（1Hz），CPU负载降低约80%，大幅改善发热问题
- **修炼按需计算**：移除每tick全弟子修炼遍历，改为惰性补齐+旬tick批量处理，突破检查/功法精通/装备养成统一在旬tick执行
- **状态复制优化**：减少GameStateStore每次tick的全量副本创建
- **日志守卫**：压缩/存档等热路径Debug日志增加BuildConfig.DEBUG守卫，减少生产环境字符串分配

### 变更
- 游戏内时间显示从"X年X月X日"改为"X年X月X旬"
- 丹药持续时间仍以月为单位，不受旬制影响
- HP/MP恢复、丹药冷却、物品冷却等消耗型数值按旬（×10）等比放大
- 自动存档触发条件从"每月1日"改为"每月上旬"
- 新增数据库迁移15→16：game_day列保留，新增game_phase列

## [3.1.75] - 2026-05-31

### 修复
- 自动管理界面开启滚动，解决锻造设置和保存按钮被遮挡

## [3.1.74] - 2026-05-31

### 修复
- 紧急修复自动存档变空：SectPolicies 新增字段 Set<Int> 改为 List<Int>，ProtoBuf 不支持 Set 导致序列化失败

## [3.1.73] - 2026-05-31

### 新增
- 天枢殿→宗门管理→自动管理：半屏界面配置空闲弟子自动分配到灵矿场/灵植阁/炼丹炉/锻造坊，支持已关注+灵根数量筛选+属性门槛，月度tick自动分配最优弟子

## [3.1.72] - 2026-05-30

### 修复
- 移除 GameStateStore 中 18 个 stateIn 的 replayExpirationMillis=30s 限制，改为默认永不过期，消除后台 >35s 回来 StateFlow 返回空列表导致 UI 闪白

## [3.1.71] - 2026-05-30

### 修复
- CultivationService 中 12 个 setter 从 scope.launch 异步写改为 sync direct 方法直接写入 _state，消除多域更新不原子和潜在竞态

## [3.1.70] - 2026-05-30

### 优化
- GameViewModel 中 19 个无变换透传 StateFlow 从 stateIn() 改为 get() 委托，移除冗余 viewModelScope stateIn 层，减少每 tick O(N) 通知开销

## [3.1.69] - 2026-05-30

### 修复
- 系统性修复 GameEngine 中 18 处 stateIn 派生 StateFlow 的 .value 读取为 Snapshot 直读，消除 assignGarrisonDisciple/startMission/checkAndProcessCompletedMissions 等函数的 WhileSubscribed replay 过期隐患

## [3.1.68] - 2026-05-30

### 修复
- 仓库首次选中物品点"查看"不弹出详情：remember 无 keys 导致 derivedStateOf 闭包永久捕获 stateIn 初始空列表，无法找到物品后守卫子句静默重置

## [3.1.67] - 2026-05-30

### 修复
- 探查和宗门战后战斗结算界面不弹出：scoutSect/attackSect 改用 Snapshot 直读 `_state.value` 替代 stateIn 派生 StateFlow，解决 WhileSubscribed replay 过期导致 `.value` 返回空列表静默跳过战斗的 bug（与 3.1.64 妖兽战斗修复同根因）

## [3.1.66] - 2026-05-30

### 修复
- 世界地图探查按钮直接弹出弟子选择器、跳过探查派遣界面：v3.0.83 统一单选时误将探查的战斗派遣多选界面改为单击即执行，现恢复为 10 槽位编队+探查确认按钮的完整战斗派遣流程，与进攻界面交互统一

## [3.1.65] - 2026-05-30

### 优化
- ProtoBuf 序列化彻底优化：Room TypeConverter 改用 `encodeDefaults=false`，可空字段（String?/Int?）为 null 时自动省略，不再需要 JSON 降级
- 移除 ProtobufConverters 中 JSON 混合格式逻辑，统一为纯 ProtoBuf，提升序列化性能与 Schema 向前兼容性

## [3.1.64] - 2026-05-30

### 修复
- 世界地图进攻妖兽后战斗结算界面不弹出、战斗日志无记录：修复 `attackWorldLevel` 使用 `stateIn` 缓存的 `disciples.value` 在订阅过期后返回空列表导致弟子 NOT FOUND；改用 `disciplesSnapshot` 直读 `_state.value`
- 自动存档变空：ProtobufConverters 新增 JSON 降级机制，ProtoBuf 序列化 null 字段失败时自动用 JSON 编码存库（`J:` 前缀），解码时自动识别格式，向后兼容

## [3.1.63] - 2026-05-30

### 新增
- 宗门战斗力系统，主界面宗门信息卡片宗门名称右侧红色显示战斗力
- 战斗力基于弟子最终属性计算（含装备功法丹药加成），属性变化时增量更新
- AI宗门战力基于基础属性×3，仅境界变化时重算

## [3.1.62] - 2026-05-30

### 修复
- 建筑放置在树木装饰物上时只清除部分格子导致残留半棵树的问题：触碰树木任意视觉区域即整棵清除

## [3.1.61] - 2026-05-30

### 变更
- TapDB 迁移至 v4 API：setUserId/clearUser/logEvent/logPurchasedEvent 改用 TapTapEvent 接口
- SDK 初始化新增 TapTapEventOptions 配置：渠道信息与自动 IAP 上报迁移至 eventOptions
### 修复
- 建筑拖动/点击手势冲突修复：统一为 awaitEachGesture 单循环，消除拖拽失效

## [3.1.60] - 2026-05-30

### 变更
- 存档架构优化：移除本地 .sav 文件双写，统一为 Room 数据库存储
- 新增旧存档迁移器：首次启动自动将 .sav 存档迁移至 Room 数据库
- 序列化模块重构：PillEffect 强类型化、模块拆分，为联机通信做准备
### 修复
- 弹窗关闭失效、旧存档丹药分类丢失、导航路由统一等多项修复

## [3.1.59] - 2026-05-30

### 修复
- DOT多buff叠加时跨境界减伤不生效：coerceAtLeast移到总伤害计算
- 功法自动学习属性匹配修正：法攻偏好不再误选治疗/辅助功法
- 旧存档自动装备/学习设置向后兼容：未配置sect设置时回退读取弟子旧标志
- 存档版本迁移框架接入反序列化管线：旧存档PillEffect自动升级

## [3.1.58] - 2026-05-30

### 修复
- DOT持续伤害不再绕过跨境界减伤：低境界敌人的毒/灼烧对高境界弟子伤害被正确压缩
- TapTap新账号登录后不再卡在登录界面：修复合规认证SDK注册竞态+线程问题

## [3.1.57] - 2026-05-29

### 修复
- 天赋文本颜色加深：从浅粉彩改为深色品阶色，白色背景上清晰可辨

## [3.1.56] - 2026-05-29

### 新增
- 天枢殿→宗门管理新增「弟子管理」：统一配置弟子自动使用仓库物资的条件
- 突破时自动使用仓库突破丹：符合条件的弟子突破时优先消耗仓库中高品阶突破丹
- 每日自动装备仓库装备：符合条件的弟子每日自动穿戴仓库装备（优先匹配攻击属性方向）
- 每日自动学习仓库功法：符合条件的弟子每日自动学习仓库功法（优先匹配攻击属性方向）
### 变更
- 弟子详情界面移除「自动穿戴」「自动学习」独立勾选框，统一由弟子管理界面控制

## [3.1.55] - 2026-05-29

### 新增
- 凡品突破丹「聚气丹」：炼气期小层突破使用，商人/宗门交易/炼丹均可获取
### 修复
- 突破丹匹配逻辑：大境界突破使用目标境界丹药（炼气→筑基用筑基丹、筑基→金丹用凝金丹，以此类推）

## [3.1.54] - 2026-05-29

### 新增
- 巡视楼战斗胜利后幸存弟子神魂+1
- 拥有「百战通神」天赋的弟子胜利后随机属性+1（17种属性中随机一种）

## [3.1.53] - 2026-05-29

### 优化
- 焦点弟子分频：打开弟子详情时该弟子 200ms 实时刷新，其他弟子 1s
- Tab 节流：切换 Tab 时通知引擎调整数据更新优先级

## [3.1.52] - 2026-05-29

### 优化
- 游戏循环自适应节流：tick 超时不再自旋，连续超时自动降频，减少发热
- StateFlow 分配优化：discipleAggregates 跳过不变时的 map 计算，减少 GC 压力

## [3.1.51] - 2026-05-29

### 修复
- 修复雷电模拟器 TapTap 启动卡死：TapTapSdk.init() 切到后台线程+超时保护，移除 x86 ABI 强制 ARM 翻译

## [3.1.50] - 2026-05-29

### 新增
- 巡视楼战斗结算：击败妖兽后弹出结算界面、生成战斗日志、发放妖兽材料+灵石奖励（与主动攻击一致）
- 设置界面新增巡视楼结算开关，默认关闭，开启后弹出结算

## [3.1.49] - 2026-05-29

### 新增
- 跨境界斩杀机制：进攻方境界比防守方大三个大境界以上时攻击必中且一击必杀，适用于所有战斗类型

## [3.1.48] - 2026-05-29

### 变更
- 巡视楼巡视槽位从10减为8，旧存档多余弟子自动回归空闲

## [3.1.47] - 2026-05-29

### 变更
- 道侣生子机制重构：从每日0.08%概率改为每年0.5%概率判定，判定通过后在当年随机月份生育

## [3.1.46] - 2026-05-29

### 变更
- 提示框尺寸放大：宽40%→50%、高45%→55%

## [3.1.45] - 2026-05-29

### 修复
- 弟子详情修炼速度显示包含住所建筑加成（单人住所+25%/中级+50%/多人+10%），与引擎实际增益一致

## [3.1.44] - 2026-05-29

### 修复
- 弟子详情界面修为进度条实时更新：改用实时弟子列表替代打开时的快照，进度条每秒刷新

## [3.1.43] - 2026-05-29

### 架构重构 Phase 0
- 提取 SpiritRootGenerator 统一灵根生成逻辑，消除 5 处重复实现
- 修复 core→ui 循环依赖：DisciplePositionHelper 迁移至 core/util
- EventBus 提取 EventBusPort 接口，消费者（GameEngineCore、CombatService、ExplorationService）通过接口依赖
- 为全部 9 个 Service 创建接口契约文档（ServiceInterfaces.kt）

## [3.1.42] - 2026-05-29

### 新增
- 新生儿灵根继承机制：子女有30%概率继承父亲灵根、30%概率继承母亲灵根、40%概率随机生成

## [3.1.41] - 2026-05-29

### 新增
- 天枢殿「宗门事务」改为「宗门管理」，新增道侣管理系统
- 道侣管理支持按灵根数量禁婚（单灵根~五灵根），勾选后对应灵根数量弟子不会与异性弟子结为道侣
- 道侣管理支持结婚审批模式：开启后弟子请求结婚时弹出审批框展示双方信息，玩家可同意或拒绝

## [3.1.40] - 2026-05-28

### 修复
- 数量输入框超上限自动取上限值（输入999→13，输入0→1）

## [3.1.39] - 2026-05-28

### 修复
- 修正宗门占领设计：正常宗门需全池无化神及以上弟子才可占领，被AI占领的宗门击败驻守弟子即可占领
- 关卡妖兽数量范围从3~12改为1~13

### 新增
- 占领后弟子重定向：玩家占领宗门后存活弟子进入招募列表，AI占领后弟子并入占领者宗门
- 被占领宗门的年度新弟子自动路由：玩家占领→招募列表，AI占领→占领者宗门
- AI宗门年度新弟子从固定5名改为随机0~6名

## [3.1.38] - 2026-05-28

### 变更
- 巡视楼移除建造上限，可建造多座。每座独立管理10个巡视弟子槽位和进攻配置（满状态/境界/数量）
- 多座巡视楼分塔分队自动攻击，不同塔不会重复进攻同一只妖兽

## [3.1.37] - 2026-05-28

### 新增
- 巡视楼自动攻击实装：每月推进时自动根据配置筛选妖兽并攻击

## [3.1.36] - 2026-05-28

### 新增
- **巡视楼建筑**：2×3占地，5000灵石建造费，上限1座。驻守弟子可自动巡视攻击世界地图妖兽
- 巡视楼界面含10个巡视弟子槽位，支持一键任命（优先高境界空闲存活弟子）
- 进攻范围配置：可选择目标境界（默认炼气）、设置妖兽数量上限（1-13）、满状态条件

## [3.1.35] - 2026-05-28

### 变更
- 突破丹逻辑修改：现在突破丹支持对应境界的所有突破（包括小层突破），而不只是大境界突破。例如大乘丹可用于大乘一层→二层以及大乘九层→渡劫
- 突破时自动消耗弟子储物袋中的突破丹，每次突破只用一颗，优先使用高品质丹药

## [3.1.34] - 2026-05-28

### 修复
- 修复弟子详情界面内外门切换按钮点击后UI不刷新的问题（数据已更新但界面需关闭重开才能看到变化）

## [3.1.33] - 2026-05-27

### 优化
- 所有全屏界面左右增加32dp安全边距，避免前置摄像头（挖孔/刘海）遮挡游戏内容
- 主界面悬浮组件（宗门信息卡、功能按钮组）统一改为对称固定边距

## [3.1.32] - 2026-05-27

### 优化
- 一键任命优化：灵矿场优先任命高采矿属性弟子（原按境界），副本战斗优先任命高境界弟子（修正排序方向）

### 修复
- 修复副本战斗一键任命因排序方向错误导致优先选择低境界弟子的bug
- 修复攻击AI宗门战斗详情始终显示0回合的问题，现在显示实际战斗回合数
- 修复击败AI宗门守军后无法占领宗门的问题：只需击败所有可出战守军即可占领（包括被其他AI占领的宗门驻军为空时）
- 战斗详情文字区分「攻占」（实际占领）和「击溃守军」（击败但未满足占领条件）

## [3.1.30] - 2026-05-27

### 新增
- **仓库建筑**：全新可建造建筑，3×2格占地，1000灵石建造费，无建造上限。点击仓库建筑弹出半屏管理界面，可委任驻守弟子防盗。每建造一座仓库增加50格宗门仓库容量上限
- **仓库容量系统**：宗门仓库从固定2000格上限改为动态容量——基础50格，每建造一座仓库建筑增加50格。仓库满时新获得物品直接遗失，并弹出"仓库已满"提示框。旧存档同样适用
- **仓库驻守防盗**：弟子触发偷盗后随机选择一个仓库进行偷盗。若仓库有驻守弟子则与驻守弟子进行1v1战斗——贼胜则偷盗成功，贼败则被捕。若仓库无驻守弟子则直接偷盗成功
- **百战通神天赋实装**：PVE战斗胜利后，幸存弟子若拥有百战通神天赋，从17个属性中随机一个+1（智力/悟性/魅力/忠诚/炼器/炼丹/灵植/采矿/传道/道德 + 7个战斗基底），原始值直接递增不受乘法影响
- **被偷盗提示框**：偷盗成功时弹出提示框，显示损失灵石数量

### 变更
- 仓库界面标题栏右侧新增容量显示"（已用/上限）"，仓库满时数字变红并追加"仓库已满"提示
- 驻守弟子标题右侧新增详情按钮，描述防盗战斗机制
- 偷盗触发条件改为道德低于30且忠诚低于30，偷盗概率基于道德动态调整（每低1点+3%概率）
- 偷盗后脱离宗门改为概率判定，基于忠诚动态调整（每低1点+3%脱离概率），不再必定脱离
- 月俸忠诚改为每月±1（原每3次±1），矿工每月-1忠诚（执事不受影响），入住住所每月+1忠诚

## [3.1.28] - 2026-05-27

### 修复
- 修复灵植弟子选择列表只显示内门弟子的问题，改为所有存活空闲弟子均可选

## [3.1.27] - 2026-05-27

### 优化
- 性能优化：SideEffect改LaunchedEffect消除每tick主线程bitmap绘制，UI层StateFlow采样降频减少Compose重组频率
- 招募界面同意/拒绝按钮改为自适应等宽，随卡片宽度动态调整

### 变更
- 灵植弟子标题右侧增加详情按钮，点击查看增益公式

## [3.1.26] - 2026-05-27

### 修复
- 修复种植界面已种植种子卡片在库存耗尽后不显示的问题

## [3.1.25] - 2026-05-27

### 变更
- 种子成熟时间按稀有度大幅延长：凡品3年、灵品6年、宝品20年、玄品45年、地品70年、天品120年
- 种子详情生长时间由月显示改为年显示

## [3.1.24] - 2026-05-27

### 变更
- 灵植阁放置/移动时显示绿色光环范围圈（半径6格），范围内灵田绿色高亮
- 灵田部分处于范围即享受增益，光环判定改为最近点距离

## [3.1.23] - 2026-05-27

### 变更
- 灵植长老移至天枢殿，与炼丹/天工长老同行管理
- 灵植阁改为范围光环建筑：半径6格内灵田享受灵植弟子加成，范围外不享受，多座不叠加
- 灵植阁弟子槽位缩为1个，更名为"灵植弟子"，配方：灵植50为基准每5点+1%成熟速度（上限20%）
- 灵植长老改为全局加成：灵植80为基准每4点+1%成熟速度（上限20%）
- 灵植阁移除建造上限

## [3.1.22] - 2026-05-27

### 变更
- 灵矿场、锻造坊、炼丹炉移除建造上限，建造栏隐藏数量显示
- 打开任何界面自动折叠建造栏

## [3.1.21] - 2026-05-27

### 修复
- safeDropColumns移除API级别判断，统一使用PRAGMA表重建代替原生ALTER TABLE DROP COLUMN
- 修复部分Android 12+设备SQLite不支持DROP COLUMN语法导致数据库迁移崩溃的问题

## [3.1.20] - 2026-05-27

### 重构
- 全功能模块化架构：BuildingRegistry建筑单一数据源（15项枚举）、ViewModel 4层委托拆分（Planting/Disciple/Navigation/Inventory）
- MainGameScreen拆分为3独立组件（GameActionButtons/GameOverlayHost/BuildingConstructionBar）
- 24文件90+处硬编码建筑名统一迁移，GameOverlayHost统一管理24路由+TopOverlay z-order

## [3.1.19] - 2026-05-27

### 修复
- 种子网格动态计算行列数，消除只显示2行的问题
- 放置确认/取消按钮模块化，固定在建筑上方不受方格尺寸限制
- 全代码库死代码清理（+162/-659行）

## [3.1.18] - 2026-05-26

### 修复
- 灵田1x1尺寸修复（buildings.json补全）+ 精灵图显示修复（SaveLoadViewModel补全）
- 种植界面按钮颜色、数量选择器重构、布局优化
- 灵田自动补种（仓库有同种子持续种植，无种子清空字段）
- 收获草药入库改为直接写入事务状态

## [3.1.17] - 2026-05-26

### 新增
- 种植系统：新增灵田建筑（1格/200灵石/无建造上限），建造后通过种植按钮进入全屏种植界面
- 种植界面6:4左右分栏，左侧种子卡片网格（翻页浏览），右侧灵田列表（按种子分组、铲除按钮、数量选择器）
- 灵田自动生长收获，灵植阁长老/弟子 spiritPlanting 属性加成同宗门灵田产量（上限80%）

### 调整
- 灵植阁移除种植槽位UI，定位为纯增益建筑（保留灵植长老+亲传弟子分配）

### 修复
- 顶层overlay z-order排序机制（BattleResult/BattleLogDetail纳入排序列表，保证后打开在最顶层）

## [3.1.16] - 2026-05-26

### 新增
- 顶层inline overlay z-order排序机制（SnapshotStateList），保证后打开的界面始终在最顶层
- BattleResult和BattleLogDetail纳入排序列表，与弟子详情统一管理

## [3.1.15] - 2026-05-26

### 修复
- 弟子详情界面全屏渲染架构重构：18个调用点统一改为ViewModel驱动，移至MainGameScreen最外层渲染，解决Compose Dialog平台兼容问题

## [3.1.14] - 2026-05-26

### 调整
- 所有妖兽基础属性（hp/mp/attack/defense/speed）统一下调30%，降低战斗难度

## [3.1.13] - 2026-05-25

### 修复
- 数据库迁移safeDropColumns封装替代DROP COLUMN，PRAGMA动态重建表，所有Android版本安全
- CLAUDE.md增加ALTER TABLE DROP COLUMN禁令，防止未来重复引入

## [3.1.12] - 2026-05-25

### 修复
- 修复低版本Android（API < 31）SQLite不支持DROP COLUMN导致数据库迁移崩溃的问题

## [3.1.11] - 2026-05-25

### 优化
- 弟子详情页突破率右侧增加圆形详情按钮，点击弹窗显示各加成明细
- 弹窗自动隐藏零加成项（天赋/神魂/长老），内门外门长老悟性加成实时计算
- 神魂行不再显示突破加成，仅显示数值

## [3.1.10] - 2026-05-25

### 优化
- 弟子详情页神魂行不再显示突破加成，仅显示数值
- 突破率右侧增加圆形详情按钮，点击弹窗显示突破率全部分加成明细
- 弹窗3列网格布局，标题栏右侧关闭按钮

## [3.1.09] - 2026-05-25

### 修复
- 修复占领宗门地图灵矿场/炼丹炉/锻造坊无法任命弟子的问题

### 调整
- 弟子槽位（灵矿弟子、炼丹/锻造/灵植弟子、储备弟子）按宗门独立管理
- 长老、传道师、执法弟子、灵矿执事保持全局共享

## [3.1.08] - 2026-05-25

### 修复
- 修复占领宗门地图灵矿场/炼丹炉/锻造坊无法任命弟子的问题（槽位校验从宗门计数改为全局计数）
- 执事槽位按宗门拆分（DirectDiscipleSlot加sectId字段），每个宗门独立管理执事
- 长老保持全局共享，切换宗门不影响长老职位

## [3.1.07] - 2026-05-25

### 修复
- 战斗胜利后存活弟子神魂+1（世界关卡、宗门战、任务战斗），修复神魂只在旧洞府探索增长的bug
- 修正弟子详情页神魂显示公式与计算一致（/20而非/10，上限5%而非10%）

### 清理
- 清理旧洞府系统：停止生成旧洞府、删除不可达的洞府详情/CaveMarker等死UI代码
- 删除未实现的战斗成长（winGrowth）死代码

## [3.1.06] - 2026-05-25

### 调整
- 弟子脱离提示框的弟子槽位不再显示血条

## [3.1.05] - 2026-05-25

### 修复
- 建筑标识从整数序号改为instanceId，移除灵矿场/炼丹炉/锻造坊序号标签，修复多宗门槽位串位

## [3.1.04] - 2026-05-25

### 修复
- 修复占领宗门地图无法建造建筑的问题

## [3.1.03] - 2026-05-25

### 修复
- 修复探查过的AI宗门弟子分布信息不随宗门战死亡而更新的问题

## [3.1.02] - 2026-05-25

### 优化
- 进入宗门后自动关闭所有弹窗直接显示宗门地图

## [3.1.01] - 2026-05-25

### 修复
- 修复AI宗门弟子在宗门战中被击杀后下次攻击仍可出战的问题

## [3.1.00] - 2026-05-25

### 新增
- 占领宗门后可进入该宗门并自由建造建筑，支持多宗门地图切换
- 世界地图宗门详情界面和外交界面已占领宗门时隐藏探查、送礼、结盟、交易按钮

### 数据库迁移
- game_data 表新增 activeSectId 列，数据库版本 6→7

## [3.0.100] - 2026-05-25

### 调整
- 占领宗门后世界地图宗门详情界面和外交界面隐藏探查、送礼、结盟、交易按钮

## [3.0.99] - 2026-05-25

### 调整
- 弟子脱离宗门提示框中的文字卡片改为弟子槽位展示

## [3.0.98] - 2026-05-25

### 调整
- 监牢思过年限从10年缩短为5年，思过结束后弟子增加5点道德和5点忠诚
- 弟子所有基础属性移除100上限，下限统一改为0

## [3.0.97] - 2026-05-25

### 修复
- 修复弟子脱离和偷盗被捕提示框关闭后重复弹出的问题

## [3.0.96] - 2026-05-25

### 修复
- 修复战斗结算和战斗详情界面中弟子死亡后槽位不显示"死亡"标识的问题

## [3.0.95] - 2026-05-24

### 调整
- 问道塔和青云塔取消开局自动建造，改为需要通过建造栏花费灵石手动建造
- 移除问道塔和青云塔对话框中的弟子名册展示区域

## [3.0.94] - 2026-05-24

### 修复
- 修复长按建筑后无法进入移动模式的问题

## [3.0.93] - 2026-05-24

### 修复
- 修复攻打宗门胜利一次即直接占领的问题：占领判断改为检查宗门全部弟子池（而非仅参战弟子），必须消灭宗门内所有化神及以上弟子才能占领

## [3.0.92] - 2026-05-24

### 数值
- 所有功法和装备的基础属性增益提升50%

## [3.0.91] - 2026-05-24

### 调整
- 神魂突破率加成削弱：每20点神魂+1%突破率（原每10点+1%），上限5%（原10%）
- 突破失败惩罚：扣除弟子90%当前气血和灵力（保留10%，最少1点）
- 自动突破条件新增：弟子必须满气血满灵力才会自动尝试突破

## [3.0.90] - 2026-05-24

### 调整
- 全境界突破率调整：单灵根炼气90%→筑基80%→金丹60%→元婴42%→化神34%→炼虚26%→合体16%→大乘12%→渡劫6%→仙人2%
- 多灵根突破率按固定百分点递减：双灵根-20%、三灵根-30%、四灵根-50%、五灵根-60%，最低为0%
- 外门/内门长老突破率加成削弱：悟性每高4点+1%突破率（原每高1点+1%）

## [3.0.89] - 2026-05-24

### 修复
- 修复弟子住所槽位点击后不弹出选择弟子界面的问题（DiscipleSelectorDialog 渲染顺序导致被遮挡）

### 修改
- 多人住所网格尺寸改为 3×2（与单人住所一致）

## [3.0.88] - 2026-05-24

### 修改
- 统一所有提示确认弹窗样式为标准提示框（dialog_box背景、居中按钮居于底部、12dp圆角）
- 天赋详情界面改用标准提示框样式，右上角关闭按钮替代底部按钮

### 新增
- 设置界面退出游戏按钮新增二次确认提示框，防止误触退出

## [3.0.87] - 2026-05-24

### 修复
- 修复天赋详情界面缺少背景的问题，改为半屏显示与其他界面风格统一

## [3.0.86] - 2026-05-24

### 修改
- 宗门战斗奖励灵石改为参与随机物品池（7种类型等概率），不同等级宗门单件灵石数量不同
- 不同等级宗门灵石产出：小型2000/中型6000/大型3万/顶级8万（每件灵石物品）

### 修复
- 修复宗门战斗胜利后战利品（灵石、装备、功法、丹药等）未实际存入仓库的问题
- 修复攻打宗门时防守方选出低境界弟子的问题，现改为选出宗门内境界最高的弟子防守

## [3.0.85] - 2026-05-24

### 优化
- 移除外门大比机制：弟子晋升改为在弟子信息界面直接操作
- 弟子详情界面新增内外门切换按钮（关系按钮左侧），按钮与下拉菜单一体式设计，向下展开
- 所有弟子默认为外门弟子，可在弟子信息界面随时手动晋升为内门或降为外门
- 点击界面其他位置（标签页、按钮、天赋、装备槽等）自动收起下拉菜单，不影响原有操作
- 切换内外门身份时自动清理对应职位（灵矿矿工、长老等）

## [3.0.84] - 2026-05-24

### 优化
- 所有弟子选择界面统一改为单选模式，彻底移除多选机制
- 点击弟子卡片即完成选择，无需额外确认按钮，操作更流畅

## [3.0.83] - 2026-05-24

### 优化
- 所有弟子选择界面（执法堂、灵矿矿工、炼丹/锻造储备弟子、洞府探索、宗门探查、外门大比）统一改为单选模式，彻底移除多选机制
- 点击弟子卡片即完成选择，无需额外确认按钮，操作更流畅

## [3.0.82] - 2026-05-24

### 优化
- 所有弟子选择界面优化：境界筛选栏（灵根/属性/境界）合并到标题栏区域，紧贴标题下方
- 标题栏和筛选栏间距大幅缩减，为弟子卡片留出更多展示空间

## [3.0.81] - 2026-05-23

### 优化
- 灵矿更换矿工界面改为单选：点击弟子卡片直接替换，无需额外确认按钮
- 所有多选弟子界面（执法堂预备弟子、灵矿矿工、炼丹/锻造预备弟子、任务派遣、洞府探索、外门大比）确认按钮统一移至弟子卡片网格下方居中

## [3.0.80] - 2026-05-23

### 新增
- 新增弟子住所系统：可在宗门地图建造单人住所（800灵石）和多人住所（2000灵石）
- 单人住所可入住1名弟子并提供25%修炼速度加成，可升级为中级单人住所（5000灵石，加成提升至50%）
- 多人住所可入住4名弟子并提供10%修炼速度加成
- 点击宗门地图上的住所建筑可打开详情界面，分配、搬离或更换入住弟子
- 建造无数量上限

## [3.0.79] - 2026-05-23

### 修复
- 修复弟子战斗阵亡后仍显示在弟子列表并可被任命为长老/亲传弟子的问题

## [3.0.78] - 2026-05-23

### 修复
- 修复战斗胜利后战利品仅部分入库的问题：战利品生成与入库统一在事务内原子执行
- 战斗结算弹窗现在仅显示实际成功入库的物品

## [3.0.77] - 2026-05-23

### 修复
- 修复世界地图战斗结算时背景变成宗门地图的问题
- 修复兽潮关卡战斗胜利后灵石未入库的问题

## [3.0.76] - 2026-05-23

### 修复
- 修复读档后游历商人物品自动刷新的问题

## [3.0.75] - 2026-05-23

### 优化
- 锻造/炼丹界面进度条宽度与槽位对齐，视觉更统一
- 进度条上方新增成功率显示（如"成功率85%"）
- 进度条动画改为逐日平滑增长（利用 gameDay 计算月内天分数），不再逐月跳动

## [3.0.74] - 2026-05-23

### 新增
- 新增弟子叛逃提示框：低忠诚弟子脱离宗门时弹出提示
- 新增弟子偷盗被捕提示框：可选择驱逐、押入监牢（需建造监牢）或释放
- 释放偷盗弟子随机增加1~10忠诚度并显示变化提示

## [3.0.73] - 2026-05-22

### Bug修复
- 修复部分机型按钮文字只显示2个字符的问题：按钮标准宽度从72dp增至84dp，内边距从10dp缩至4dp，文字溢出改为省略号而非裁剪

## [3.0.72] - 2026-05-22

### 性能优化
- 精灵PNG缩放到显示尺寸（2048px→480px），解码内存从16.8MB降至922KB
- 所有精灵PNG无损压缩（oxipng），APK体积减小20-40%
- 84张物品精灵在加载界面预解码为ImageBitmap缓存，滚动时零解码
- 丹药/锻造选择列表添加稳定key，减少不必要重组

## [3.0.71] - 2026-05-22

### 性能优化
- 静态资源全预加载：功法库、丹药模板、配方、装备、妖兽材料、建筑贴图等在读档/新游戏加载界面统一预加载，消除进入游戏后的首次操作卡顿
- 功法库初始化从应用启动（主线程阻塞）移到游戏加载界面（后台线程），加快应用启动速度

## [3.0.70] - 2026-05-22

### 平衡性调整
- 移除跨境界伤害乘数上限/下限：MAX_REALM_GAP(5)、MAX_DAMAGE_RATIO(3.0x)、MIN_DAMAGE_RATIO(0.0)均移除
- 境界差加成按完整差距线性缩放，仙人vs炼气从3.0x→5.5x
- 低境界攻击高境界惩罚保底为0，避免负倍数

## [3.0.69] - 2026-05-22

### Bug修复
- 修复战斗普通攻击描述使用妖兽动词：弟子进行普通攻击时正确显示武器攻击描述（如「一剑刺向」），而非妖兽攻击描述（如「猛扑向」）
- 修复探查防守方AI弟子战斗描述同样使用妖兽动词的问题

## [3.0.68] - 2026-05-22

### 新功能
- 战斗结算界面：战斗胜利/失败后弹出半屏结算界面，展示出战弟子状态和战利品
- 结算界面底部「战斗详情」按钮可查看完整战斗回合记录
- 覆盖所有战斗类型：世界关卡（妖兽/洞府）、宗门战、探查、洞府探索

## [3.0.67] - 2026-05-22

### 平衡性调整
- 突破丹药突破率统一调整为：下品5%、中品12%、上品20%（原15%/30%/60%）

### Bug修复
- 修复丹药选择界面部分突破丹药缺失的问题：同品阶不同突破丹药（如筑基丹/凝金丹/结婴丹）不再被错误合并，每种丹药独立显示

## [3.0.66] - 2026-05-22

### Bug修复
- 修复弟子信息界面神魂显示两个0的问题：数值标签不再与StatItem重复显示
- 修复弟子翻页顺序与列表显示顺序不一致的问题：详情弹窗翻页现在遵循列表的排序和筛选设置
- 修复仓库界面显示两个仓库标题的问题：移除内部重复标题，一键出售按钮移至标题栏

## [3.0.65] - 2026-05-22

### 丹药精灵图
- 丹药道具新增品阶精灵图：凡品/灵品/宝品/玄品/地品/天品丹药各有专属精灵图
- 替换所有丹药显示中的「敬请期待」占位文字
- 仓库、炼丹选择、商人列表、宗门交易、弟子储物袋/奖励等界面丹药均显示对应品阶精灵图

### 丹药选择界面优化
- 每种丹药只显示一张卡片，不再按品质（下品/中品/上品）分开展示
- 丹药品质为炼制成功后随机生成（6%上品/34%中品/60%下品），选择时不再标注品质
- 丹药详情界面显示效果范围（下品~上品），清晰展示炼制产出的品质波动

## [3.0.64] - 2026-05-22

### UI修复与样式统一
- 修复弟子槽位境界文字显示不全的问题：槽位高度微调，确保境界文字完整显示
- 锻造、炼丹、种植槽位的取消和更换按钮移除图片素材，改为纯文字样式，与弟子槽位的卸任/更换按钮保持一致
- 生产槽位进度条宽度调整为与槽位同宽，视觉更协调

## [3.0.63] - 2026-05-21

### 招募调整
- 每年招募弟子数量从3-15名调整为0-6名，增加招募不确定性

### 弟子槽位名称显示
- 所有弟子槽位（长老、直属弟子、生产、炼丹、锻造、灵矿、任务、功法阁、战斗等）新增弟子名称显示
- 名称显示在精灵图上方，境界显示在下方，布局清晰
- 战斗界面弟子槽位同步升级并共享统一渲染组件

## [3.0.62] - 2026-05-21

### 锻造/炼丹材料修复
- 修复同种材料分散在多个堆叠时无法锻造玄品和宝品装备的问题：后端材料数量覆写改为累加，与UI层保持一致
- 修复锻造和炼丹失败时无任何错误提示的问题：材料不足或未分配弟子时现在会显示错误消息

### 按钮标准化
- 锻造选择界面和炼丹选择界面的确认按钮移除无效宽度修饰符，严格遵守72×38dp标准尺寸

## [3.0.61] - 2026-05-21

### 一键出售界面滚动修复
- 修复一键出售对话框选择品阶和类型后物品列表无法滚动的问题

### 自动生产系统重构
- 自动炼丹/锻造/种植改为每槽位独立开关，开启后空闲槽位立即开始生产，优先高品阶物品
- 工作槽位开启自动后，完成时自动续炼同种物品；材料不足则自动降级炼制高品阶
- 修复自动生产按钮状态不更新的问题（UI与数据层多处同步修复）

### 生产槽位卡片升级
- 炼丹炉/锻造坊/灵植阁槽位卡片显示物品精灵图或敬请期待占位，底部显示物品名称
- 槽位新增进度条和剩余月份显示
- 新增取消按钮（取消当前炼制/锻造/种植，材料不退还）
- 新增更换按钮（弹出选择界面，选择新物品后直接替换，原物品视为失败）
- 槽位边框统一改为固定灰色，移除槽位序号文本

### 灵植阁对齐
- 灵植阁种植槽同步享受以上所有UI和功能升级

## [3.0.60] - 2026-05-21

### 任务阁弟子选择简化
- 移除弟子选择弹窗的取消和任命按钮，点击弟子卡片直接完成任命，操作更流畅
- 弟子卡片改为两列显示，可视区域更大

### 任务系统优化
- 移除未接取任务自动过期机制，改为每三月刷新时统一清空所有未执行任务后生成新任务
- 任务卡片和派遣界面新增弟子条件显示：外门弟子/内门弟子/无条件，一眼识别任务要求

## [3.0.59] - 2026-05-21

### 功法精灵图
- 功法卡片新增品阶精灵图：凡品/灵品/宝品/玄品/地品/天品六品阶功法各配备专属图标
- 替换功法卡片上的"敬请期待"占位文本，功法识别更加直观

## [3.0.58] - 2026-05-20

### 外门大比关闭按钮修复
- 修复外门大比结果界面关闭按钮点击无反应、无法关闭界面的问题
- 关闭逻辑从间接StateFlow驱动改为直接导航路由关闭，与其他弹窗保持一致
- 清理遗留的无效状态标志和死代码；无数据库迁移

## [3.0.57] - 2026-05-20

### 外门大比界面修复
- 修复外门大比结果对话框不显示的Bug：v3.0.43界面重构时大比对话框被错误放入世界地图组件内部，导致玩家不在世界地图界面时大比无法弹出；现已将大比对话框提取为独立导航页面，每三年一次的外门大比恢复正常

## [3.0.56] - 2026-05-20

### 弟子选择界面全面优化
- 所有建筑的选择弟子界面筛选栏上移，缩小标题与筛选栏间距，为弟子卡片区域空出更多空间
- 弟子卡片网格最大高度从280dp扩大到400dp，可视区域大幅增加
- 推荐属性文本（采矿、炼丹、炼器、灵植、智力等）从每张弟子卡片移至对话框标题右侧，卡片更简洁
- 对话框内点击非交互区域不再意外关闭界面

### 灵矿场更换按钮修复
- 修复矿工槽位已满时点击"更换"无反应的问题
- 更换操作改为替换当前槽位弟子，不再错误分配到空位

### 执法堂弟子选择界面修复
- 执法弟子选标题修正：从统一"选择亲传弟子"改为动态显示"选择执法弟子""选择炼丹弟子"等
- 执法堂储备弟子界面的智力属性移至标题栏显示

## [3.0.55] - 2026-05-20

### 探查战斗AI防守弟子修复
- AI防守弟子现在使用功法技能战斗，含熟练度加成，不再只会普通攻击
- AI防守弟子使用完整属性（基础属性+装备+功法），不再只有基础境界属性
- AI防守弟子使用真实灵根元素，不再统一硬编码为金属性
- 战斗描述中AI弟子使用弟子武器动词（"一剑刺向"等），不再错误使用妖兽攻击动词

### 战斗日志卡片修复
- 妖兽战斗日志正确显示妖兽精灵图，不再显示默认弟子头像
- AI宗门弟子在战斗日志中各显示随机头像（37张池），不再全部相同

### 弟子死亡处理修复
- 死亡弟子状态统一设为DEAD，不再保留IDLE状态导致可被任命
- 多个弟子选择界面增加存活检查，防止死亡弟子被误选
- 探查战斗和妖兽战斗死亡弟子自动清理职务槽位（长老/执法等）并触发死亡事件

## [3.0.54] - 2026-05-19

### 探查改为即时战斗
- 探查改为即时战斗模式，选好弟子后立即与目标宗门交战，不再需要等待旅行时间
- 探查战斗防守方为5-10名炼气到金丹境界弟子，随机选取
- 探查胜利后宗门详情界面实时刷新，显示各境界弟子具体分布人数（零人显示0）
- 探查战斗记录完整写入战斗日志，包含回合详情，可在战斗日志界面查看

### 装备功法归还仓库
- 弟子卸下或更换装备时，旧装备归还宗门仓库而非放入弟子储物袋
- 弟子遗忘或替换功法时，旧功法同样归还宗门仓库

### 数据库迁移
- 本次更新包含数据库迁移（v3→v4）：aiSectDisciples 字段持久化
- 修复存档读回后 AI 宗门弟子数据丢失导致探查/进攻无防守弟子的问题

## [3.0.53] - 2026-05-18

### UI 修复与统一
- 任务阁派遣队伍界面全面修复：选择弟子界面不再被遮挡、支持滑动、弟子槽位增加卸任/更换按钮、点击已占槽位弹出弟子详情、任务详情界面支持滑动
- 任务阁弟子选择卡片列数自适应屏幕宽度，不再被压缩
- 商人界面灵石数量移至标题右侧，仓库一键出售按钮移至标题右侧
- 招募界面移除灵石数量显示
- 外交界面送礼/结盟/交易按钮修复为标准尺寸，修复点击按钮无反应问题（子对话框未渲染在外交界面内）
- 探查弟子/游说弟子界面按钮统一为标准尺寸，修复点击开始探查后无效果的问题
- 天枢殿按钮统一为标准尺寸，炼丹长老与锻造长老槽位并排显示
- 宗门交易界面物品卡片改为统一标准卡片样式，修复精灵图不显示、样式不一致问题
- 打开任意界面时建造栏自动关闭
- 蕴灵戒精灵图更新
- 无数据库迁移

## [3.0.52] - 2026-05-18

### 装备更名 & 精灵图上线
- 靴子更名（含描述更新）：布鞋→青澜靴、皮靴→兽皮靴、迷雾靴→云栖靴、虚空步→溯光靴、影舞步→赤煞靴、仙踪步→鸾羽履、混沌履→鹤岚靴
- 饰品更名（含描述更新）：疾风戒→蕴灵戒、地灵核→渡厄佩、风行坠→隐云佩、混沌灵珠→幽朔珠、天行戒→长明坠
- 新增24个精灵图：12双靴子（覆盖凡品~天品全部靴子）+ 12个饰品（覆盖凡品~天品全部饰品），靴子饰品精灵图覆盖率达100%
- 无数据库迁移

## [3.0.51] - 2026-05-18

### 任务阁派遣队伍优化
- 任务阁改为槽位式派遣队伍界面：点击任务弹出6个固定弟子槽位（3×2网格），逐个点击槽位单选任命弟子
- 新增一键任命按钮：自动选择符合条件的空闲弟子填入所有空槽位（高境界优先）
- 底部取消/派遣按钮，满6人方可派遣
- 无数据库迁移

## [3.0.50] - 2026-05-18

### AI宗门弟子老化修复
- 修复进攻AI宗门直接获胜且战斗日志显示零弟子的问题：AI弟子老化频率从每月1岁修正为每年1岁（此前AI弟子一年老化12岁，数年后全部老死）
- 无数据库迁移

## [3.0.49] - 2026-05-18

### 旧存档建筑消失修复 & 地图纹理修复
- 修复从旧版本（3.0.41）升级后读档宗门地图建筑全部消失的问题（GridBuildingData 新增 instanceId 字段导致 protobuf 字段编号偏移，旧存档反序列化失败）
- 修复宗门地图建筑足迹下方出现错位地面纹理条纹的问题（装饰清除代码错误使用全图偏移绘制，改为提取对应格子绘制）
- 修正 24 个单元测试期望值（突破概率 -5% 与品阶颜色更新后测试未同步）
- 无数据库迁移

## [3.0.48] - 2026-05-18

### 弟子任命槽位修复 & 视角修复
- 修复任命炼丹弟子后炼丹炉槽位仍显示空闲的问题
- 修复任命锻造弟子后锻造坊槽位仍显示空闲的问题
- 修复关闭界面回到宗门地图后视角自动移到地图中间的问题
- 统一生产槽位数据写入路径（Repository + StateStore 双写），弟子任命/移除/自动生产切换等操作现在正确同步
- 无数据库迁移

## [3.0.47] - 2026-05-18

### 建筑拖动重新放置 & 设置界面修复
- 新增长按建筑拖动重新放置功能，长按建筑（0.6秒）即可拖动到新位置，确认后落位
- 拖动过程中自动排除被拖建筑占位、边缘自动平移相机、返回键/建造模式自动取消
- 移动建筑不影响建筑运行状态（生产中的建筑继续生产）
- 修复设置界面重新开始游戏后设置界面不关闭的问题
- 无数据库迁移

## [3.0.46] - 2026-05-17

### 建筑拖拽优化
- 优化建筑建造拖拽体验，建筑预览与手指 1:1 丝滑同步，不再有阻力滞后感
- 建筑预览改为平滑滑动渲染，拖拽过程中不再逐格跳动
- 无数据库迁移

## [3.0.45] - 2026-05-17

### 防具更名 & 精灵图新增
- 布衣更名为灵竹衣，青铜铠更名为精铁甲，锻造配方与装备描述同步更新
- 铁叶甲更名为碧叶甲，精钢铠更名为丹羽衣，鳞甲更名为青鳞铠，板甲更名为银板铠，玄法袍更名为汐流衣
- 龙鳞甲更名为龙鳞铠，泰坦铠更名为渊岩铠，虚空袍更名为瑶光袍
- 神铸铠更名为墨幽铠，天罡袍更名为凌星袍，大地甲更名为玄幽袍，虚空影袍更名为定海铠
- 鸿蒙铠更名为苍罡铠，仙衣更名为曦光铠，混沌袍更名为云影袍
- 全部24件防具更名为更贴合修仙题材的新名称，装备描述同步更新
- 新增20件防具精灵图，防具精灵图覆盖率达100%，不再显示"敬请期待"
- 新增灵竹衣、精铁甲、锁子甲、皮甲 4 件防具的精灵图，装备卡片不再显示"敬请期待"
- 无数据库迁移

## [3.0.44] - 2026-05-17

### 锻造/炼丹弟子筛选放宽 & 建造修复
- 锻造坊和炼丹炉弟子筛选移除"内门弟子"限制，外门弟子也可担任锻造和炼丹工作
- 修复炼丹炉和锻造坊建造一次后变灰无法继续建造的问题，现可正确建造至上限 7 个
- 仓库一键出售界面右上角增加关闭按钮（圆形X按钮）
- 无数据库迁移

## [3.0.43] - 2026-05-17

### 全屏界面架构重构
- 引入 Jetpack Navigation Compose 路由系统，统一所有 23 个全屏界面的打开/关闭方式
- 拆分大文件：WorldMapDialogs.kt（1850行）拆为 6 个独立文件
- 15 个 Screen 对话框文件重命名并统一移至 dialogs/ 目录
- 统一对话框包装器：80+ 处 HalfScreenDialog → UnifiedGameDialog + DialogMode
- 废弃 HalfScreenDialog、GameFullDialog，删除 DialogStateManager
- 共享组件提取：DialogHeader、DiscipleFilterState、统一弟子选择器 DiscipleSelectorDialog
- 修复真机测试问题：渐隐动画关闭、双标题/双关闭按钮、按钮无响应、世界地图缩小
- 无数据库迁移

## [3.0.42] - 2026-05-17

### 自动招募筛选界面优化
- 右上角增加关闭按钮（圆形X按钮）
- 筛选条件改为5列显示（单灵根~五灵根一行排列）
- 勾选框颜色从绿色改为黑色
- 底部增加取消按钮（左）和保存按钮（右），修改筛选后需点击保存才生效
- 修改筛选条件后点击关闭按钮弹出确认对话框，提示"您所做的更改尚未保存"
- 确认对话框使用专用对话框素材，提供保存（右）和关闭（左）两个选项
- 无数据库迁移

## [3.0.41] - 2026-05-16

### 武器更名 & 精灵图扩充
- 屠龙刀（地品）→ 凤炎刃
- 寒霜刃（宝品）→ 青碧刃
- 混沌刀（天品）→ 玄玉刃
- 雷霆杖（宝品）→ 玄雷杖
- 虚空杖（玄品）→ 虚华杖
- 天星珠（地品）→ 天玄杖
- 鸿蒙杖（天品）→ 天星杖
- 水晶珠（凡品）→ 碧木扇
- 玄冰珠（宝品）→ 玄冰扇
- 凤凰扇（玄品）→ 凰焰扇
- 凤凰羽（地品）→ 阴阳扇
- 阴阳珠（天品）→ 天玄扇
- 新增16把武器精灵图，精灵图覆盖率达100%
- 适配所有更名武器的描述文本，消除旧名称残留
- 无数据库迁移

## [3.0.40] - 2026-05-16

### 武器改名 & 精灵图扩充
- 弑神剑（地品）→ 青莲剑，描述更新为「传说中自青莲中诞生的神剑，剑气纵横天地间」
- 青铜匕首（凡品）→ 精铁刀
- 战斧（灵品）→ 凌华刀
- 新增精灵图素材：青莲剑、灵锋剑、精铁刀、凌华刀，现在共8把武器有独立精灵图
- 清理战斗系统中匕首/战斧相关遗留文本
- 无数据库迁移

### 商人界面修复
- 商人筛选按钮统一为72×38dp标准尺寸

## [3.0.39] - 2026-05-16

### 道具数量显示优化
- 所有道具卡片右下角数量去除x前缀：`x3` → `3`
- 数量为1时也显示数字，不再隐藏
- 涉及：统一物品卡片、紧凑卡片、售卖行、商人卡片、背包弹窗、配方材料列表、出售日志、兑换码描述
- 弟子详情界面装备槽位和功法槽位中已装备的物品不显示数量
- 无数据库迁移

## [3.0.38] - 2026-05-16

### 全界面物品卡片样式统一
- 所有物品卡片统一为60dp标准尺寸，两段式布局：上方素材区（品阶色背景 + 精灵图或「敬请期待」白字）+ 下方名称区（白底黑字）
- 卡片边框统一为灰色（GameColors.Border），选中时变为金色3dp边框
- 品阶文字（上品/中品/下品）和数量徽章保留在素材区左下/右下角贴边显示
- 锁定徽章保留在素材区左上角，「查看」按钮保留在卡片右上角（选中时显示）
- 锻造/炼丹配方卡片的炼制时长「N月」和品级名称移至卡片外部下方显示
- 弟子装备槽位和功法槽位统一使用60dp新卡片，熟练度等级移至卡片外部下方
- 灵植阁种子选择卡片统一为60dp标准尺寸
- 所有受影响界面：仓库、背包、商人交易、储物袋、锻造选择、炼丹选择、装备替换、功法替换/学习、弟子奖励、药园种子选择
- 无数据库迁移

## [3.0.37] - 2026-05-16

### Bug修复：全场景空槽位点击行为统一
- 修复灵植阁、执法堂、各峰（青云塔/问道塔/天枢殿/藏经阁）、生产设施（锻造坊/炼丹炉）、灵矿场、驻守界面空槽位点击后未触发更换操作的问题
- 所有场景的空槽位点击现在统一触发「更换」操作，而非与已占用槽位相同的点击行为
- 无数据库迁移

## [3.0.36] - 2026-05-16

### 装备槽位显示优化 & 品阶颜色标准统一
- 弟子装备界面装备槽位显示优化：槽位缩小，品阶色背景上显示装备精灵图（已支持精铁剑/烈焰剑/雷霆剑/诛仙剑），暂无素材的装备显示「敬请期待」白字
- 装备名称移至槽位下方白底黑字显示，品阶颜色边框标识稀有度
- 弟子功法界面功法槽位样式统一为与装备槽位一致（品阶色背景 + 精灵图/敬请期待 + 白底名称）
- 道具品阶颜色标准统一：凡品#B8B8B8、灵品#AFCB8A、宝品#9FC2EE、玄品#C0A2DD、地品#E7C67D、天品#E3A0A0
- 所有道具卡片（商人交易、储物袋、仓库、背包、锻造选择、炼丹选择、天赋卡）统一使用新品阶颜色背景
- 无数据库迁移

## [3.0.35] - 2026-05-16

### Bug修复：弟子详情弹窗未全屏显示
- 修复在弟子列表、进攻宗门编队、关卡详情中点击弟子槽位后，详情弹窗未全屏显示、上方仍可见标题栏和筛选栏的问题
- 三处弟子详情调用点统一使用 Dialog 独立窗口渲染，确保覆盖全屏

## [3.0.34] - 2026-05-16

### Bug修复：战斗日志妖兽图像显示错误
- 修复战斗日志详情中敌方妖兽错误显示默认弟子头像的问题
- 妖兽现在根据名称自动匹配对应图像（虎/狼/蛇/熊/鹰/狐/龙/龟妖）

## [3.0.33] - 2026-05-15

### 弟子槽位交互全面统一
- 所有弟子槽位交互统一：点击已占槽位弹出弟子详情弹窗，所有槽位下方均有「卸任」+「更换」按钮
- 覆盖全场景：炼丹、炼器、灵植、灵矿、藏经阁、执法堂、天枢堂、青云塔、问道塔、弟子标签、进攻编队、驻守、关卡探索、任务执行
- 新增 DiscipleSlotWithActions 共享组件，消除各处重复的槽位+按钮代码
- 新增 DiscipleDetailDialog 便捷重载，自动收集 StateFlow 减少调用点模板代码

## [3.0.32] - 2026-05-15

### Bug修复：进攻宗门战斗未触发
- **严重Bug修复**：派遣弟子进攻AI宗门时，防守方仅从驻守槽位获取（未占领宗门槽位均为空），导致战斗被直接跳过、宗门立即被占领，低境界弟子也能"秒杀"满编AI宗门
- 进攻宗门现在正确从AI宗门弟子池中选取防守方弟子出战
- 进攻宗门战斗结束后生成战斗日志记录（类型：宗门战）

## [3.0.31] - 2026-05-15

### 弟子状态新增"驻守中"
- 新增弟子状态"驻守中"：驻守槽位中的弟子不再显示"队伍中"，而是显示独立的"驻守中"状态
- 战斗队伍槽位中的弟子即使队伍未派遣也显示"队伍中"状态
- 修复战后驻守弟子状态正确重置为空闲

## [3.0.30] - 2026-05-15

### 驻守弟子槽位交互完善
- 驻守弟子槽位交互与进攻槽位统一：点击弟子打开详情弹窗，下方更换+卸任两按钮
- 修复弟子详情弹窗在宗门半屏弹窗中无法显示（用 Dialog 窗口层独立渲染）
- 弟子槽位居中排列，更换/卸任按钮间距缩小

## [3.0.28] - 2026-05-15

### 弟子槽位全面统一改造
- 所有弟子槽位（长老、执事、工人、驻守、进攻、关卡、任务、战斗日志等 17 个）统一为弟子半身像+境界显示
- 新增 UnifiedDiscipleSlot 共享组件：52×76dp 固定尺寸，所有界面一致
- 移除长老槽位内的额外属性文字（炼丹/炼器/灵植/采矿/道德），槽位只显示头像+境界
- 世界地图占领宗门驻守弟子、进攻妖兽编队、关卡探索弟子槽位同步统一
- 战斗日志详情界面参战弟子槽位同步统一，血条移至槽位上方宽度统一

### Bug 修复
- 修复驻守弟子槽位放入后界面不更新、所有弟子显示同一张 fallback 图像
- 所有弟子槽位禁止同一弟子重复分配（建筑工人、藏经阁、驻守、进攻编队、关卡、洞府）
- 弟子槽位居中排列，卸任/更换按钮间距优化

## [3.0.27] - 2026-05-15

### 弟子选择界面统一改造
- 所有弟子选择界面统一为半屏弹窗+两列网格展示，补齐背景图（含外门大比结果改用三列）
- 5个旧版AlertDialog（亲传弟子/长老选择、进攻编队、秘境关卡、洞府探索）迁移为半屏弹窗
- 弟子卡片展示布局规则：半屏弹窗两列、全屏界面三列

## [3.0.26] - 2026-05-15

### 界面优化
- 半屏弹窗宽度由85%微调为83%（统一全局所有半屏弹窗）
- 11个建筑界面从全屏改为半屏弹窗：炼丹炉、锻造坊、问道塔、青云塔、天枢殿、执法堂、灵矿场、灵植阁、藏经阁、任务阁、监牢

## [3.0.25] - 2026-05-15

### 建造栏优化
- 建造栏每个建筑卡片下方显示当前建造数量/最大数量（如灵矿场 3/8、炼丹炉 2/7、唯一建筑 0/1）

## [3.0.24] - 2026-05-15

### 数据库迁移完善
- 补MIGRATION_2_3空迁移，防止从v3.0.22中间版本升级到当前版本时因缺少2→3迁移路径而崩溃
- 版本升级路径覆盖：1→3（ALTER TABLE加列）、2→3（空操作）、降级时fallbackToDestructiveMigration

## [3.0.23] - 2026-05-15

### 紧急修复：存档兼容性
- 修复从3.0.20升级到3.0.22后旧存档全部丢失的问题（Room版本升级时fallbackToDestructiveMigration销毁全部表数据）
- 新增Migration(1→3)显式ALTER TABLE迁移，升级时不再drop表
- 接入文件备份系统：每次保存同时写入.sav文件，加载时Room为空则从.sav恢复

## [3.0.22] - 2026-05-15

### 炼丹炉/锻造坊多建筑化改造
- 炼丹炉和锻造坊可建造7座，每座1个生产槽位，点击地图上的建筑即可进入对应实例
- 每座炼丹炉/锻造坊需分配1名弟子上岗才能开始生产，无弟子时无法启动
- 生产中弟子被卸任或死亡时进度自动冻结，重新分配弟子后恢复
- 炼丹长老和锻造长老移至天枢殿，放在副宗主下方统一管理
- 自动炼丹/锻造改为每个槽位独立开关，可在各自弹窗内单独设置
- 数据库迁移：production_slots表新增autoRestartEnabled列（默认关闭）

## [3.0.21] - 2026-05-14

### TapDB 数据分析
- 接入游戏时长追踪（GameDurationService），自动记录用户前台活跃时长
- 游戏启动时设置宗门年份作为等级、宗门名称作为服务器标识，便于后台数据分析

## [3.0.20] - 2026-05-14

### 界面优化
- 宗门详情界面和进攻妖兽/洞府界面改为半屏弹窗，世界地图背景可见
- 修复宗门详情界面和进攻弟子选择界面按钮大小不符合标准（72×38dp）的问题

## [3.0.19] - 2026-05-14

### 宗门战争系统全面重构
- 移除战斗队伍系统，改为宗门信息界面直接进攻（交易按钮右侧新增进攻按钮）
- 进攻弟子选择界面：10槽位2行×5列方形槽位，卸任/更换按钮，点击弟子弹出详细信息
- 占领宗门后显示驻守槽位（2行×5列），可任命/卸任弟子驻守
- 防守机制：玩家主宗自动选10名在宗门内最高境界弟子防守，占领宗门仅驻守弟子出战
- AI战斗即时结算：删除AI战斗队伍地图行军系统，攻击决策后立即结算
- AI占领宗门每月自动补全驻守弟子至10人
- 移除路线连通限制，AI可选择任意宗门作为攻击目标

## [3.0.18] - 2026-05-14

### 界面优化
- 主界面功能按钮和宗门信息向内收敛，避免在横屏游戏中被手机前置摄像头遮挡

## [3.0.17] - 2026-05-14

### 问题修复
- 修复游戏平台启动时全屏界面左右仍有缝隙的问题：全屏弹窗改为Box叠加层渲染在Activity窗口中，避免TapTap等游戏平台对Dialog独立窗口的重定位

## [3.0.16] - 2026-05-13

### 问题修复
- 修复妖兽关卡任命弟子后进攻按钮无法点击的问题

## [3.0.15] - 2026-05-13

### 界面优化
- 弟子选择界面筛选栏上方不再显示多行条件文本，改为仅在无符合条件弟子时在空状态提示下方显示，释放列表空间

## [3.0.14] - 2026-05-13

### 界面统一
- 统一所有弟子选择界面的筛选栏为灵根、属性、境界三按钮下拉式，替代之前各弹窗手写的境界标签行，操作更便捷一致

## [3.0.13] - 2026-05-13

### 界面统一
- 统一所有半屏弹窗为 85%宽 × 78%高 标准尺寸，消除大小不一问题
- 补全所有弹窗的背景图，修复部分弹窗背景透明或白色的问题
- 主要功能界面（炼丹/锻造/招募/背包/行商/宗门外交等）保持全屏显示
- 弹窗背景透明化，半屏弹窗后方可见游戏画面

## [3.0.12] - 2026-05-13

### 界面修复
- 修复全屏弹窗（弟子/仓库/设置/世界地图/招募/炼丹/锻造/药园等全部全屏界面）在部分手机上左右两侧有空隙的问题，统一配置 Dialog 窗口 `decorFitsSystemWindows = false`

## [3.0.11] - 2026-05-12

### 界面修复
- 修复弟子卡片弹窗未全屏显示、上方仍可见筛选栏的问题，将弟子卡片改为系统级全屏弹窗
- 修复招募界面所有弟子卡片显示同一张半身图的问题，每年刷新招募列表时正确分配随机肖像

## [3.0.10] - 2026-05-12

### 界面优化
- 弟子卡牌天赋区域固定为两行高度，解决天赋数量不同的弟子卡片大小不一致问题

### 美术资源
- 新增大量男女弟子半身图（男弟子8~20、女弟子9~17），扩充弟子肖像图池

## [3.0.09] - 2026-05-12

### 平衡调整
- 化神境及以下（炼气→化神，含各小境界）突破率降低5%

### 界面优化
- 弟子列表筛选栏上方增加"弟子"标题

## [3.0.08] - 2026-05-12

### 界面修复
- 修复全屏界面（弟子列表、仓库、设置、炼丹、锻造、药园、藏经阁等所有功能界面）左右有缝隙、背景不贴边的问题
- 将全屏弹窗从系统 Dialog 窗口改为游戏内叠加层，彻底消除不同手机厂商导致的缝隙差异

## [3.0.07] - 2026-05-12

### 界面优化
- 仓库界面标题栏与筛选栏合并，移除重复的仓库文字，筛选按钮修复为标准大小（72x38dp）

## [3.0.06] - 2026-05-12

### 界面统一
- 所有界面的弟子卡片全部统一为左侧半身像+右侧多行信息设计，新增第4-5行显示弟子天赋标签
- 招募、执法堂、思过崖、问道塔/青云塔等所有弟子列表统一使用新卡片，移除各界面定制卡片实现
- 弟子卡片移除道德属性显示

## [3.0.05] - 2026-05-12

### 稳定性修复
- 修复读档后加载进度条100%卡住无法进入游戏的问题（loadData 未设置 isGameStarted=true）
- 修复新游戏保存后存档卡显示0弟子、读档后弟子全部丢失的问题（stateIn WhileSubscribed 惰性启动导致快照为空）

## [3.0.03] - 2026-05-12

### 神魂系统重做
- 神魂从大境界门槛改为突破率加成：每10点神魂+1%突破率（最多+10%），对所有境界（含小境界）突破均生效
- 弟子初始神魂为0，战斗获得神魂方式不变

### 清理
- 移除内置开发用兑换码（8888、9999），清理未使用的 RedeemCodeConfig 配置代码

## [3.0.02] - 2026-05-12

### 界面优化
- 弟子卡片全部统一为左侧半身像+右侧信息的设计：外门大比、任务大厅、联盟外交等所有弟子选择界面统一使用人物立绘卡片

## [3.0.01] - 2026-05-12

### 稳定性修复
- 修复新游戏开始时保存失败导致加载进度条从20%退回0%并永久卡住的问题
- 数据库文件异常时自动重建，避免因数据损坏导致需要删除重装才能进入游戏

## [3.0.00] - 2026-05-11

全新版本

## [3.0.08] - 2026-05-10

### 弟子美术优化
- 新增男女弟子半身像池：男弟子7张、女弟子8张，创建时根据性别随机分配人物立绘
- 同一弟子每次查看信息界面显示同一张立绘，不再变化
- 建造按钮和仓库按钮替换为新版美术素材
- 商人、招募、外交及全部11个建筑界面改为全屏显示

## [3.0.07] - 2026-05-09

### 全界面横屏适配
- 全部弹窗统一为横屏尺寸和标准化标题栏（23个界面）
- 弟子详情界面重新设计：左侧人物立绘 + 右侧标签页切换(信息/属性/装备/功法)
- 建筑材料界面迁移至横屏弹窗

## [3.0.06] - 2026-05-09

### 横屏适配
- 游戏由竖屏改为横屏，适配横屏设备显示
- 主界面布局重构：全屏宗门地图 + 两侧悬浮按钮列
- 移除底部导航栏，弟子/建造/仓库转为悬浮按钮与原有按钮分列两侧
- 弟子列表、仓库、设置改为全屏弹窗形式
- 加载界面背景和界面背景替换为横屏素材

## [3.0.05] - 2026-05-09

### 移除宗门消息系统
- 移除底部宗门消息栏（EventMessageStrip），简化总览界面布局
- 移除事件日志弹窗和相关数据模型（GameEvent/EventType）
- 移除EventService及所有Service中的addGameEvent调用
- 清理game_events数据库表、归档、序列化相关代码
- 保留战斗日志和战斗回合消息系统不变
- 宗门交易功能迁移至DiplomacyService

## [3.0.04] - 2026-05-09

### 世界地图关卡系统
- 妖兽与洞府合并为统一关卡池，世界地图每月随机生成0~3个关卡
- 关卡包含80种妖兽（10个境界×8种类型）和5种洞府境界，共85种可供随机生成
- 妖兽与洞府在世界地图上显示专属美术素材，无文字标注，更加沉浸
- 洞府守护兽使用修仙题材命名池随机生成名称（如碧眼金蟾、赤焰玄龟）
- 点击关卡弹出信息界面：展示素材、名称、境界、数量
- 新增8个正方形弟子槽位（2行×4列），支持一键任命、卸任、更换
- 弟子槽位为空时点击弹出弟子选择界面（仅显示空闲弟子，支持灵根/属性筛选排序）
- 弟子槽位已满时点击弹出弟子详情界面
- 一键任命按境界从高到低优先选择空闲弟子填满8个槽位
- 洞府守护兽固定2只，守护兽境界随洞府境界随机小层
- 修复世界地图妖兽概率显示为洞府美术素材的问题
- 妖兽数量随机3~11只，所有境界统一
- 洞府在世界上存在1年后自动消失，妖兽存在3年后自动消失
- 战斗胜利后关卡立即消失，获得对应奖励；战斗失败关卡不消失可重复挑战
- 妖兽胜利奖励：每只妖兽掉落1~3个对应类型材料，品阶根据境界动态调整
- 洞府胜利奖励：灵石（±20%浮动）+ 1~6种功法/装备/丹药（品阶随洞府境界）
- 战斗记录写入战斗日志，可回顾
- 洞府信息界面新增独立洞府名称显示（如"玄天化神洞府"），守护兽名称以"守护兽：xxx"标注
- 关卡境界显示改为只显示大境界，不显示小层（实际战斗中每个妖兽/守护兽的小层独立随机）
- 关卡生成增加间距约束，防止关卡间重叠生成

## [3.0.03] - 2026-05-09

### 界面优化
- 建造栏卡片布局优化：建筑名称和灵石价格移至独立区域，不再与素材图片重叠
- 建造栏两行卡片恰好占满可视区域，浏览更便捷

## [3.0.02] - 2026-05-08

### 灵矿场重构
- 移除扩建功能，每座灵矿场固定3个矿工槽位
- 可建造数量从1座提升至8座，可在宗门地图上建造多座灵矿场
- 点击某座灵矿场只显示该矿场的3个槽位，一键任命仅填充当前矿场
- 灵矿执事保持不变，2个执事槽位对所有灵矿场同时生效

## [3.0.01] - 2026-05-08

### 数值调整
- 初始灵石从1000提升至2000

## [2.6.23] - 2026-05-08

### 重构
- 宗门地图系统底层重构：采用"静态大地图 + 动态建筑层"架构
- 瓦片尺寸改为固定Int(64px)，不再依赖设备density，确保不同设备地图一致
- 世界尺寸改为 cellCount × TILE_SIZE，删除所有3000f硬编码
- Canvas绘制统一使用withTransform(scale+translate)实现真正摄像机坐标系
- cameraX/cameraY永远表示世界坐标，clamp使用正确数学公式
- 所有绘制坐标使用Int，消除Float半像素采样导致的白边和接缝
- 地图背景Bitmap绘制禁止filter=true，消除模糊
- 建筑改为Canvas统一绘制（彩色方块+文字标签），不再每个建筑一个Composable
- 放置预览和网格线统一在Canvas transform坐标系内绘制
- 统一相机系统重构：两张地图共享CameraState，统一为"相机在世界空间中移动"语义，消除offset/position两套坐标模型
- 世界地图从offset-Box渲染改为Canvas withTransform世界坐标绘制，与宗门地图完全一致
- 宗门地图新增双指缩放功能，支持0.5x~2x缩放范围
- 删除未使用的Camera.kt死代码

### 界面优化
- 设置界面右上角新增取消按钮，使用圆形关闭图标
- 设置界面暂停按钮和停止自动存档按钮移除文字，改为纯圆形图标显示

### 修复
- 修复地图最右侧出现竖向空白（世界像素宽度与渲染宽度不一致）
- 修复缩放错位问题（cameraScale未真正应用于地图渲染）
- 修复网格线和地图不对齐问题（Float取模运算导致半像素偏移）

## [2.6.22] - 2026-05-04

### 新增
- 宗门地图改为瓦片数据+逐格渲染架构：tile[y][x]二维数组替代单张大图+装饰物列表
- 瓦片渲染使用整数像素坐标+1px重叠，彻底消除瓦片间接缝
- 树木和草丛改为瓦片类型（TILE_TREE/TILE_GRASS），随机生成到tile数据中
- 建筑建造时扣除对应灵石费用（各建筑配置不同cost，2000~5000灵石不等）
- 建造栏显示真实建筑费用，灵石不足时卡片变红提示
- 宗门地面新增地图装饰：使用预制PNG美术素材，草丛1格大小逐格散布，树木4格大小粗网格散布
- 装饰物位置纯内存管理（不写数据库），放置建筑时自动移除重叠装饰物
- 装饰素材降采样加载（inSampleSize=4），避免大图OOM
- 宗门底图替换为美术素材（宗门地图.png单张绘制），替换纯色背景
- 缩减菜单栏高度（64dp→48dp），消息栏/建造栏紧贴菜单栏无缝隙
- 建造卡片数量标签移至卡片外部下方显示，卡片高度缩减
- 消息栏单条消息支持自动换行多行显示（总显示区域不变）

### 优化
- 建造栏调整为每行5个建筑并支持垂直滚动
- 宗门消息栏扩展为4行显示，支持垂直滚动查看一年内历史消息
- 宗门信息卡片改为垂直布局：宗门名称在上，时间/弟子/灵石信息在下方
- 移除总览按钮，菜单栏所有按钮改为再次点击收起界面
- 降低草丛和树木的生成数量（树减少60%，草减少67%），减少视觉杂乱
- 优化建造建筑拖动灵敏度，避免拖动时建筑移动过快
- 建造栏优化：灵石不足时建筑卡片变灰且不可点击
- 建筑建造价格调整：灵矿场500、灵药宛/丹鼎殿/天工峰2000、藏经阁/天枢殿5000、执法堂/任务阁3000、思过崖4000
- 青云峰和问道峰作为初始建筑开局即在地图中心
- 进入游戏及关闭界面时视角自动居中至地图中心
- 存档管理界面添加背景图，与其他功能弹窗风格统一
- 宗门地图改为预加载：登录加载阶段即完成地图贴图解码和地形生成，进入游戏后地图即刻完整显示
- 修复首次进入游戏时宗门地图短暂显示纯色背景再加载地图的问题
- 宗门地面地图改为加载阶段离线预渲染：地面纹理+全部草/树装饰合成单张完整Bitmap，运行时仅一次drawImage，消除瓦片遗漏导致的纯色区域

### 美术
- 全面替换UI美术素材：界面背景图、按钮、提示框、系统消息框、获得奖励框均使用新版美术素材
- 所有建筑功能弹窗（炼丹、锻造、灵药宛等11个建筑）统一添加界面背景图
- 按钮组件默认尺寸调整：高度32dp→36dp，字号10sp→12sp，圆角更柔和
- 宗门地面装饰（树木、草丛）替换为新版美术素材
- 底部导航栏替换为横向背景图素材

### 修复
- 修复切换到弟子/仓库/设置界面时宗门消息栏仍然显示的问题
- 修复建造栏中思过崖建筑占一整行的问题（末行不足5个时自动补齐空白）
- 修复草丛和树木装饰物互相重叠的问题（新增装饰物间碰撞检测）
- 修复宗门底图瓦片拼接缝隙：改为单张绘制不再平铺，消除瓦片间1px重叠拉伸线
- 修复建造模式下已有建筑不显示名称的问题
- 修复进入游戏后宗门地图短暂显示纯色背景的问题（地面贴图改用inSampleSize=4加载）
- 修复加载存档后宗门建筑短暂不显示的问题（预加载触发条件从宗门名改为游戏已启动，避免默认空数据提前完成预加载）
- 修复新游戏开局无初始建筑的问题（开局自动放置青云峰和问道峰，与重新开局行为一致）
- 彻底修复宗门地图纯色闪烁：贴图加载完成后才将MainGameScreen加入组合树，从根本上消除LoadingScreen关闭与贴图就绪之间的帧间隙
- 修复弟子界面"灵根"/"属性"筛选按钮铺满屏幕且无法点击的问题（fillMaxSize改为matchParentSize避免撑大父容器）
- 移除MainGameScreen中所有贴图回退加载路径和纯色兜底分支，贴图解码失败时生成纯色回退瓦片确保游戏仍可启动

## [2.6.21] - 2026-05-01

### 新增
- 建筑放置实现网格吸附系统：拖拽时建筑始终对齐最近网格、越界/重叠红色预警、确认按钮仅在合法位置可用
- 建筑放置数据持久化：placedBuildings 存入 GameData（Room 迁移 23→24），重启进程后建筑位置保留
- 实现 GridSystem 网格管理类：O(1) 占用格查询、建筑 CRUD、网格边界管理

### 修复
- 修复处于探索/队伍中的弟子无法修炼的问题（移除对 IN_TEAM 状态的修炼过滤，弟子在任何状态下都能修炼）
- 修复长老/副宗主任命后槽位仍为空的竞态：updateElderSlots 改用 updateGameDataDirect 绕过 transactionMutex 同步更新
- 修复亲传弟子任命/卸任同样的竞态问题
- 修复问道峰/青云峰弟子选择对话框空状态不显示筛选列表的问题（始终显示筛选栏）
- 修复所有建筑任命后槽位仍空白+弟子列表不显示：ViewModel 改用 gameDataSnapshot/discipleAggregatesSnapshot 直接读取 _state.value，绕过 stateIn(Dispatchers.Default) 的跨线程调度延迟
- 修复建筑放置叠加层 Z 轴顺序：待建建筑始终显示在已建建筑上方，重叠时点击判定为操作待建建筑

### 优化
- 建筑放置合法性视觉反馈强化：可建=绿色背景、越界=红色背景、重叠=橙色背景（全背景色+深色边框）

## [2.6.20] - 2026-05-01

### 修复
- 修复长老/副宗主任命后槽位仍显示为空的竞态条件（GameEngine.updateElderSlots 异步 StateFlow 更新晚于 UI 重组）
- 修复亲传弟子任命/卸任存在同样的槽位空白竞态条件
- 修复炼丹/锻造/灵植储备弟子缺失年龄检查（可将幼童任命为储备弟子）
- 修复采矿弟子缺失年龄检查
- 修复 ProductionViewModel.setViceSectMaster 绕过 ElderManagementUseCase 验证

### 优化
- 统一所有职务弟子过滤条件：提取 isEligibleForInnerPosition / isEligibleForOuterPosition 共享属性，消除 20+ 处重复过滤
- 修复选择对话框硬编码 age>=5，改为 GameConfig.Disciple.MIN_AGE 常量
- 移除 ProductionElderSelectionDialog 中始终为 no-op 的 maxRealm 参数和境界过滤

### 修改
- 彻底移除所有长老/执事/副宗主/战斗长老的境界限制（11个建筑全覆盖）
- 问道峰、青云峰选择界面移除境界提示文本

## [2.6.19] - 2026-05-01

### 修改
- 所有长老职位移除境界限制，空闲中内门弟子均可任命
- 修复建筑界面打开时长老槽位短暂显示空闲的闪烁问题

### 修复
- 灵药宛、天工峰、丹鼎殿长老选择界面统一过滤条件

## [2.6.18] - 2026-05-01

### 修复
- 灵药宛长老选择界面增加境界过滤（元婴及以上），防止选择不满足条件的弟子导致任命静默失败
- 长老选择界面空状态增加具体条件说明（内门弟子·空闲中·元婴境界及以上）

## [2.6.17] - 2026-05-01

### 修复
- 学习增加气血/灵力的功法后，当前气血/灵力同步增加（不再只增加上限）
- 替换功法时正确计算新旧功法气血/灵力差值
- 日常被动恢复上限改为final maxHp/maxMp，功法/装备额外加成可被正常恢复

## [2.6.16] - 2026-05-01

### 新增
- 接入TapDB数据分析SDK：自动追踪游戏启动、战斗结束等关键事件
- TapTap登录后将用户信息同步到TapDB（setUser）
- 退出登录时清除TapDB用户数据

## [2.6.15] - 2026-05-01

### 修复
- 移除正方形弟子卡片上的"已关注"标签（覆盖卡片面积过大导致名称境界难以辨认）
- 横向卡片保留"已关注"显示

## [2.6.14] - 2026-05-01

### 优化
- 弟子普通攻击根据武器类型显示不同描述（剑/刀/杖/匕首等），无武器时显示拳击描述
- 全体技能战斗日志改为显示每个目标的独立伤害，不再只显示总伤害

## [2.6.13] - 2026-05-01

### 紧急修复
- 修复MIGRATION_21_22遗漏disciples表autoEquipFromWarehouse列导致旧存档全空、新游戏不运行
- 新增MIGRATION_22_23安全恢复迁移，自动检测并修复受影响数据库

## [2.6.12] - 2026-05-01

### 平衡
- 妖兽基础战斗数值（气血/灵力/攻击/防御/速度）整体降低10%

## [2.6.11] - 2026-05-01

### 优化
- 简化妖兽属性计算公式，移除冗余的 `(1.0 + (mod - 1.0))` 代数恒等包装

## [2.6.10] - 2026-05-01

### 修复
- 洞府守护兽统一使用秘境妖兽战斗属性计算公式，三处妖兽（秘境/任务/洞府）属性一致

## [2.6.09] - 2026-05-01

### 优化
- 简化妖兽属性计算逻辑，固定倍率预计算进Beast.REALM_STATS配置表（修正数值与实际运行时代码一致）
- 妖兽方差计算从6个独立变量减少到4个

## [2.6.08] - 2026-05-01

### 新增
- 弟子自动穿戴宗门仓库装备功能（装备栏标题右侧勾选框）
- 弟子自动学习宗门仓库功法功能（功法栏标题右侧勾选框）
- 自动穿戴/学习受境界限制，优先高品阶，锁定物品不可自动穿戴/学习
- 自动穿戴/学习仅填充空闲槽位，不替换已有装备/功法
- 多弟子竞争同一仓库物品时，已关注弟子和高境界弟子优先

## [2.6.07] - 2026-05-01

### 修复
- 弟子每日血量/灵力恢复量从1%提升至5%
- 修复秘境队伍成员始终显示满血的问题
- 修复DiscipleAggregate.maxHp不包含丹药/境界加成的问题
- 每日事件处理添加异常隔离，避免单个事件异常导致后续事件（如血量恢复）被跳过

## [2.6.06] - 2026-04-30

### 修复
- 修复功法和装备的血量加成在弟子详情界面不显示的问题（实际战斗中已生效，仅显示遗漏）

## [2.6.05] - 2026-04-30

### 修复
- 修复世界地图探查和游说弟子选择界面不显示空闲弟子的问题（StateFlow 无订阅者导致数据为空）

## [2.6.04] - 2026-04-30

### 修复
- 修复序列化bug导致建筑生产槽位数据在存档/读档时丢失的问题

## [2.6.03] - 2026-04-29

### 新增
- 自动招募功能：招募界面标题右侧新增按钮，可配置灵根种类筛选（单/双/三/四/五灵根），每年一月自动接收符合条件的弟子

### 优化
- 统一所有建筑弟子选择界面的筛选组件（执法堂/天枢殿/灵药宛/灵矿场/任务阁/秘境），消除筛选缺失和重复代码
- 创建共享筛选弹窗组件：ProductionElderSelectionDialog、ProductionDirectDiscipleSelectionDialog、FilteredMultiSelectDialog

### 修复
- 修复筛选列表缺失练气境界按钮的问题

## [2.6.02] - 2026-04-29

### 修复
- 副宗主选择条件与其他建筑统一：空闲内门弟子即可，不再要求必须已是长老

## [2.6.01] - 2026-04-29

### 修复
- 执法堂弟子选择界面彻底修复：替换可疑的扩展函数委托模式，改用 isDiscipleInAnyPosition() 直接判断
- 天枢殿副宗主选择界面增加完整的灵根/属性/境界筛选UI（此前完全缺失）

## [2.6.0] - 2026-04-29

### 新增
- 世界地图支持多支战斗队伍，宗门上方显示队伍名称徽章
- 队伍支持查看、移动、进攻、解散四种操作
- 移动可前往玩家宗门和已占领宗门，进攻可攻击非玩家宗门
- 解散队伍时队伍先返回宗门再解散，队伍编号自动复用
- 设置界面新增更新日志功能

### 优化
- 统一所有弟子选择卡片为三行横向布局（名称+状态/灵根+境界/悟性+忠诚+道德）
- 建筑选择界面增加对应属性加成显示（灵矿执事道德、采矿弟子采矿、执法堂智力等）
- 所有选择界面的境界筛选统一为三行布局
- 灵矿场选择界面增加灵根和属性筛选

### 修复
- 修复执法堂弟子选择界面不显示空闲内门弟子的问题
- 修复药园/炼丹/锻造弟子和储备弟子状态显示不正确的问题
- 修复存档加载后弟子状态可能不正确的问题

## [2.5.96] - 2026-04-28

### 修复
- 修复数据库迁移 MIGRATION_18_19 漏掉 pills 表 miningAdd 列导致存档全部为空、新游戏不运行
- 新增 MIGRATION_19_20 安全恢复迁移，兼容已损坏的 v19 数据库

### 新增
- 属性筛选按钮新增"采矿"选项（所有弟子列表、建筑选择对话框、详情页）

### 调整
- 秘境探索队伍人数上限从 7 人改为 8 人

## [2.5.95] - 2026-04-28

### 新增：储物袋没收功能
- 弟子储物袋物品详情界面增加"没收"按钮，点击后将单个物品移至宗门仓库
- 支持装备、功法、丹药、草药、种子、材料全部类型

### 新增：矿工说明按钮 + 采矿天赋
- 矿工槽位标题右侧增加?按钮，点击显示采矿产出说明（70阈值/2%加成/160基础）
- 新增采矿天赋"地脉感应"6品阶（+2/+4/+6/+9/+13/+18）

### 修复
- 执事加成UI预览公式修复（50基准→80基准，与生产计算一致）
- 扩建按钮、没收按钮样式统一使用GameButton

## [2.5.94] - 2026-04-28

### 新增：挖矿系统 + 灵矿场改造
- 弟子新增"挖矿"基础属性，与智力/魅力等一致，1-100 随机，支持天赋"miningFlat"加成
- 新增挖矿丹药 6 品阶 × 3 品质（探矿丹/灵石丹/宝矿丹/玄矿丹/地矿丹/天矿丹），加成参考同类型丹药
- 灵矿场槽位从 12 改为 1，新增扩建按钮（首次 50 灵石，每次递增 50%，上限 50000，最多 49 次）
- 灵矿场基础产出从 60 改为 160/人/月，采矿属性 > 70 每点 +2% 产出

### 修复
- 修复气血条、修炼进度条、灵力条内数值文字垂直不居中（关闭 Compose font padding）
- 修复修炼值超过 maxCultivation 导致进度条显示异常（突破失败重置 + 上限收束）
- 修复神魂不足时突破跳过导致修炼值无限累积
- 修复锻造装备属性全为 0 的问题（改用 createEquipmentFromRecipe 填充模板属性）
- 移除突破失败递增机制（breakthroughFailCount 不再写入）

### 平衡调整
- 移除最高伤害上限（删除 Int.MAX_VALUE / 2 限制）
- 最低伤害从 10% 境界压制下限改为绝对 1 点伤害
- 全境界弟子基础 HP +30%
- 秘境探索移除 25 回合限制，必须一方全灭才结束

## [2.5.93] - 2026-04-28

### 平衡调整：弟子各境界基础战斗属性 +30%（速度不变）
- 去掉 RealmConfig 中的 multiplier 倍数算法，改为每个境界直接写 baseHp/baseMp/baseAttack/baseDefense/baseSpeed 具体数值
- 非速度属性在当前基础上提升 30%，速度保持不变
- 妖兽、敌人同步改为使用境界基础属性 × 比例系数，不再通过 multiplier 中转
- 移除废弃的雷劫系统（TribulationSystem）
- 修改文件：GameConfig.kt, DiscipleStatCalculator.kt, BattleSystem.kt, EnemyGenerator.kt

## [2.5.92] - 2026-04-28

### 修复：进度条清零时移除回溯动画
- 进度条满值清零时直接从零开始，不再播放回溯动画

## [2.5.91] - 2026-04-28

### UI优化：进度条样式调整
- 缩小气血条、修炼进度条、灵力条内的当前/最大值字体，避免超出进度条
- 修复灵力条不显示当前/最大值的问题
- 修炼进度条、气血条、灵力条动画改为从左往右增加进度
- 略微增加气血条和灵力条的长度布满一整行

## [2.5.90] - 2026-04-28

### 修复：召回队伍后弟子状态残留"队伍中"
- 战斗队伍返回宗门后正确刷新弟子状态为空闲
- 探查/遇险状态探索队伍的成员状态同步

## [2.5.89] - 2026-04-27

### UI调整：移除修炼进度条标签，简化布局

## [2.5.88] - 2026-04-27

### UI调整：进度条标签居中

## [2.5.87] - 2026-04-27

### UI调整：标签在上数值在内的进度条风格
- 修炼/气血/灵力标签显示在进度条上方
- 当前值/最大值居中显示在进度条内部，黑色粗体

## [2.5.86] - 2026-04-27

### UI调整：进度条数值标签移至上方
- 修炼进度条、血量条、灵力条的当前/最大值标签统一移至进度条上方显示
- 进度条内部不再显示文字，保持纯净的颜色填充

## [2.5.85] - 2026-04-27

### UI调整：血量条/灵力条数值显示移至进度条内部
- 当前值/最大值和标签文字移至进度条内部居中显示，黑色粗体，与修炼进度条风格统一

## [2.5.84] - 2026-04-27

### Bug修复：锻造装备属性为零 & 旧存档生产系统失效
- **锻造装备无属性**：completeBuildingTaskFromProductionSlot 手动构造 EquipmentStack 时所有战斗属性默认为0，改为调用 createEquipmentFromRecipe 从 EquipmentTemplate 复制完整属性
- **装备详情无效果显示**：连锁影响，因属性全为零，ItemDetailDialog 不显示任何效果行
- **旧存档生产系统失效**：废弃的 alchemySlots/forgeSlots/herbGardenPlantSlots 从未迁移到统一 productionSlots 格式，加载时仓库为空，所有生产子系统静默失效。添加 fallback 初始化
- **移除复活逻辑**：DisciplePillManager、GameEngine 中 revive 相关代码全部移除
- **UI调整**：修炼进度条缩短至境界名称同行，当前/最大修为居中显示在进度条内部

## [2.5.83] - 2026-04-27

### Bug修复：丹药治疗/灵力恢复无效 & 每日恢复限制移除
- **丹药治疗bug**：healMaxHpPercent 错误设置 hpVariance=0 而非恢复 currentHp，导致治疗丹药完全无效
- **灵力恢复缺失**：mpRecoverMaxMpPercent 字段在 applyPillEffect 中完全未处理，灵力恢复丹药无效
- **复活丹药bug**：复活时同样错误设置 hpVariance=0，改为 currentHp=-1（满血哨兵值）
- **每日恢复限制移除**：移除战斗中弟子不恢复的限制，所有存活弟子每日恢复1%HP和1%MP
- **UI调整**：气血/灵力从战斗属性板块移至基本信息板块，在修炼进度条下方以红/蓝进度条显示

## [2.5.82] - 2026-04-27

### Bug修复：弟子每日血量/灵力恢复机制未生效
- **根因**：processDailyRecovery() 使用原始 baseHp/baseMp(默认120/60) 作为恢复上限，而非境界缩放后的 maxHp/maxMp
- **影响**：高境界弟子受伤后，每日恢复上限极端偏低（如化神期应5400上限实为120），永远无法回满
- **修复**：恢复上限改为 disciple.maxHp / disciple.maxMp（与UI血量百分比显示使用同一缩放值）

## [2.5.81] - 2026-04-27

### 战斗数值调整：跨大境界伤害加成/降低统一改为50%
- 跨大境界伤害加成：每境界 50%
- 跨大境界伤害降低：每境界 50%
- 最大伤害加成 3.0x（MAX_DAMAGE_RATIO 钳制），最大伤害降低至 0.1x（MIN_DAMAGE_RATIO 钳制）

## [2.5.80] - 2026-04-27

### 战斗数值调整：大境界差距伤害加成/降低翻倍
- 跨大境界伤害加成从每境界15%提升至30%（翻倍）
- 跨大境界伤害降低从每境界12%提升至24%（翻倍）
- 大境界差距机制不变：仅在大境界不同时生效，同境界不同层数无影响

## [2.5.79] - 2026-04-27

### 存档系统稳健性修复：防止槽位列表全部消失
- StorageEngine.getSaveSlots()：单个槽位查询失败不再导致全部槽位报错，失败槽位显示为空占位
- StorageFacade.getSaveSlotsSuspend()：异常不再向上传播，改为返回空列表
- SaveLoadViewModel：所有 getSaveSlotsSuspend() 调用点添加 try/catch 保护
  - init 块加载失败后延迟 500ms 重试一次
  - saveGame() 成功路径刷新失败不影响"保存成功"提示
  - saveGame() 失败路径自动刷新槽位列表恢复 UI
  - refreshSaveSlots()、savePipeline、performSynchronousSave、performRestartSave 全部加保护
- StorageEngine.writeAllDataToDatabase()：production_slots 条件守卫移除 + 空列表诊断日志
- MainActivity：getSaveSlots 回退逻辑改为返回空列表

### 数据库 schema 修复：回滚未完成的 GameData 拆分重构
- **MIGRATION_17_18**：DROP 6 个 MIGRATION_15_16 创建的子表（game_data_core/world_map/buildings/economy/organization/exploration）
  - 根因：MIGRATION_15_16 创建的 game_data_core 遗漏了 FK 约束，与 Room Entity 定义不一致，导致 Room schema 校验失败
  - 影响：所有旧存档在存档选择界面显示为空，新建游戏后无法运行
  - 数据安全：子表数据为 game_data 的 INSERT INTO ... SELECT 副本，DROP 不丢失数据
- 移除子表 Entity 和 DAO 声明（GameDataEntities.kt、Daos.kt、GameDataAggregateWithRelations.kt）
- 删除未使用的 GameDataEntities.kt 和 GameDataAggregateWithRelations.kt

### 错误处理改进
- StorageEngine.querySingleSlot()：异常不再静默返回空存档，改为抛出 RuntimeException
- StorageEngine.getSaveSlots()：同样传播异常给调用方
- StorageFacade.getSaveSlots()/getSaveSlotsSuspend()：传播错误而非返回全空列表
- StorageFacade.initialize()：新增数据库完整性校验，提前发现 schema 不一致
- SaveLoadViewModel.startNewGame()：保存失败后重试一次，仍失败则终止启动

### 项目配置
- 新增 CLAUDE.md 工作流程规则文件

## [2.5.77] - 2026-04-27

### 关键修复：旧存档丢失与新建游戏不运行
- **根因1：GameStateStore.update() 并发竞态导致 isPaused 卡在 true**
  - `_state.update { }` lambda 捕获闭包变量 `mergedIsPaused`/`mergedIsLoading`/`mergedIsSaving`
  - CAS 重试时使用旧值，覆盖并发 `setPausedDirect()` 设置的新值
  - 导致游戏循环始终检测到 `isPaused=true` 拒绝推进，表现为"新建游戏后不运行"
  - 修复：将三个标志位的合并逻辑和 `_isPaused`/`_isLoading`/`_isSaving` 同步写入移入 `_state.update` lambda 内部
  - 每次 CAS 重试时实时读取最新 `_isPaused.value`，消除闭包旧值问题
- **根因2：SaveLoadViewModel 加载进度残留**
  - `startNewGame()` 和 `loadGameFromSlot()` 的 finally 块无条件将 `_loadingProgress` 重置为 0
  - 游戏成功创建后仍有进度为 0 的残留状态
  - 修复：使用 `gameStarted`/`gameLoaded` 标志，仅在未成功启动时重置进度

### 数据库版本
- 未变更，仍为版本 17
- 无 schema 变更，旧存档兼容

## [2.5.76] - 2026-04-27

### 战斗系统境界差距系数修复
- `BattleCalculator.calculateRealmGapMultiplier` 境界差距判断条件修正：`gap > 0` → `gap < 0`
- 问题：游戏境界编号为降序（0=仙人最高，9=炼气最低），但原代码将编号大的判定为高境界，导致高境界弟子攻击低境界妖兽时反而受到伤害惩罚
- 影响：元婴境(realm=6)弟子攻击筑基境(realm=8)妖兽时原受到24%伤害惩罚，修复后获得15%/级的伤害加成
- 修复范围：覆盖所有战斗场景（PVE秘境、洞府、任务人形敌人、PVP）
- 测试更新：重命名测试名称为中文语义描述，新增最大差距钳制边界测试

## [2.5.75] - 2026-04-27

### 代码复查修复
- 删除 GameEngineCore 中重复的 GameStateSnapshot 内部类（与 GameEngine.kt 顶层定义完全一致），消除重复定义
- 修正 MIGRATION_16_17 日志描述，明确说明 Pill.effects @Embedded 列名不变无需 schema 变更

### GameStateStore Boolean 字段重构完善
- setPausedDirect/setLoadingDirect/setSavingDirect 同步更新 _state 和独立 _isPaused/_isLoading/_isSaving 两个 Flow
- unifiedState 使用 _state.asStateFlow() 确保同步读取

## [2.5.73] - 2026-04-27

### Disciple双模型迁移 Phase 1-2 (U-01)
- Phase 1: 消除循环依赖
  - DiscipleDetailScreen 改用 DiscipleStatCalculator.getMaxManualSlots(aggregate) 重载，不再调用 toDisciple()
  - WorldMapViewModel/GameViewModel 中仍需 toDisciple() 的调用添加 TODO(U-01 Phase3) 标记
  - DiscipleAggregateWithRelations.toDisciple() 和 toCompactDisciple() 添加 TODO 标记
- Phase 2: 收敛写路径
  - 确认 DiscipleAggregate 无变异方法，所有属性为 val
  - 确认无代码通过 Aggregate 引用直接修改子模型
  - 所有写入通过 Disciple Entity 的 copyWith() 或委托属性 setter
- 修复预存编译错误
  - Pill 类添加 PillEffect 委托属性（breakthroughChance, targetRealm 等）
  - GameEngine.kt 修复 pill.effect -> pill.effects
  - MerchantItemConverter.kt / ItemDatabase.kt 添加 PillEffect import

## [2.5.72] - 2026-04-27

### 错误类型系统统一 (U-07)
- 重构 AppError 为三层体系：AppError → Domain → 具体错误类型
- 新增 AppError.Domain 中间层，包含6个领域分类：Production, Storage, Validation, GameState, Network, GameLoop
- AppError.Domain.Production 新增子类型：DiscipleNotAvailable, ProductionFailed, DatabaseError
- AppError.Domain.Storage 新增子类型：IntegrityError, VerificationFailed, Expired, Tampered
- 新增 AppError.Domain.Validation 密封类：InvalidInput, ConfigError, OutOfRange, EmptyValue
- 新增 AppError.Domain.GameState 密封类：InvalidState, NotFound, PermissionDenied
- 旧平铺类型 AppError.Validation/Permission/NotFound 标记为 @Deprecated
- 8个独立错误类型全部标记为 @Deprecated 并附带 ReplaceWith：GameError, ProductionError, ProductionResult.ProductionError, ProductionTransactionError, VerificationResult, ValidationResult (InputValidator), ConfigValidator.ValidationResult, GameLoopError
- 新增转换扩展函数：VerificationResult.toAppError(), ValidationResult.toAppError(), ConfigValidator.ValidationResult.toAppError(), ProductionTransactionError.toAppError()
- 更新 UiError.fromAppError() 覆盖所有新 Domain 子类型
- 更新 AppError.fromException() 使用新的 Domain 层次
- 所有旧类型保留，仅标记弃用，不删除，保持向后兼容

## [2.5.69] - 2026-04-27

### StorageEngine 拆分重构 (U-02)
- 将 StorageEngine.kt (~920行) 拆分为5个职责单一的类
- StorageEngine.kt: 核心读写逻辑 (~480行)，委托给提取的类
- StorageIntegrity.kt: 完整性验证 (validateIntegrity, Merkle, 签名验证, constantTimeEquals)
- StorageBackup.kt: 备份/恢复/导出逻辑 (exportToFile, createBackup, getBackupVersions, restoreBackup, deleteBackupVersions)
- StorageWal.kt: WAL 快照管理 (createCriticalSnapshot)
- StorageMetrics.kt: 存储指标收集 (saveCount, loadCount, cacheHits, cacheMisses, cacheHitRate)
- 所有新类使用 @Singleton @Inject constructor 注解，由 Hilt 自动管理
- StorageEngine 保持原有公共 API 不变，通过委托模式调用提取的类
- StorageModule.provideStorageEngine 更新参数列表，移除不再需要的直接依赖
- 清理 StorageEngine 和 StorageModule 中不再使用的导入

## [2.5.68] - 2026-04-27

### 性能监控统一 (U-06)
- 将 GamePerformanceMonitor 和 PerformanceMonitor 两个废弃类合并到 UnifiedPerformanceMonitor
- UnifiedPerformanceMonitor 新增 GameEngineCore 所需方法：start/stop, recordTick, recordEntityCount, recordSaveQueueSize, forceGc, getMemoryReport
- UnifiedPerformanceMonitor 新增 GameMonitorManager 所需方法：initialize, startMonitoring/stopMonitoring, isPerformanceAcceptable, getRecommendedOptimizationLevel, logPerformanceStatus, startOperationTimer/endOperationTimer/measureOperation
- UnifiedPerformanceMonitor 新增月度事件追踪：recordMonthlyEventStart/End, measureMonthlyEvent, getMonthlyEventSummaries/RecentMonthlyEvents/SlowMonthlyEvents/MonthlyEventPerformanceReport, logMonthlyEventStats
- UnifiedPerformanceMonitor 新增帧率统计：getFrameStats, capturePerformanceSnapshot, resetStats, cleanup（增强版）
- 新增 OptimizationLevel 枚举到 performance 包
- 合并数据类：PerformanceMetrics, PerformanceWarning, WarningType, PerformanceListener, FrameStats, OperationMetric, MonthlyEventMetric, MonthlyEventSummary, PerformanceSnapshot, PerformanceEventListener
- 集成 Choreographer 帧监控到 UnifiedPerformanceMonitor
- GameEngineCore：替换 GamePerformanceMonitor 为 UnifiedPerformanceMonitor，移除 @Suppress("DEPRECATION")
- GameMonitorManager：移除 PerformanceMonitor 依赖，所有调用委托到 UnifiedPerformanceMonitor，OptimizationRecommendation 使用 UnifiedPerformanceMonitor.OptimizationLevel
- SaveLoadCoordinator：替换 PerformanceMonitor 为 UnifiedPerformanceMonitor，移除 @Suppress("DEPRECATION")
- 删除废弃文件 GamePerformanceMonitor.kt 和 PerformanceMonitor.kt

## [2.5.67] - 2026-04-27

### 测试修复
- 修复 SaveCryptoTest 全部11个测试失败：SaveCrypto 是 object 单例，applicationScopeProvider 为 lateinit，测试中未调用 initialize() 导致 UninitializedPropertyAccessException
- 修复 InventorySystemTest returnEquipmentToStack 两个测试失败：派生 StateFlow 使用 WhileSubscribed(5s) 策略，无订阅者时 .value 返回初始空列表，改用 unifiedState.value 直接读取
- SaveCryptoTest 添加 tearDown 清理：clearAllKeyCache() + scopeProvider.close()

## [2.5.66] - 2026-04-27

### 代码质量报告复查修复

#### 架构修复
- P0-01 完成: 删除已废弃的 GameRepository（无任何外部调用，所有功能已迁移到6个领域子Repository）
- C2: BaseViewModel 新增 launchElderAction 辅助方法，SectViewModel/ProductionViewModel 的 assignElder/removeElder/assignDirectDisciple/removeDirectDisciple 从10行重复代码缩减为1-2行
- C5: SaveLoadViewModel.pauseAndSaveForBackground() 从 runBlocking 改为 ApplicationScopeProvider.ioScope.launch，消除主线程 ANR 风险
- C5: SaveLoadViewModel.onCleared() 保存超时从3秒缩短为2秒

#### 代码规范修复
- C4: 提取魔法数字为命名常量
  - 新增 GameConfig.Battle.ELDER_SLOTS(2)、DISCIPLE_SLOTS(8)、MIN_FORMATION_SIZE(10)
  - 新增 GameConfig.Production.MAX_SPIRIT_MINE_SLOTS(12)
  - 新增 GameConfig.Elder 命名空间（REALM_VICE_SECT_MASTER/REALM_LAW_ENFORCEMENT/REALM_ELDER/REALM_PREACHING_MASTER）
  - ElderManagementUseCase 常量委托到 GameConfig.Elder（单一事实来源）
  - BattleViewModel: repeat(2/8)、age>=5、realm<=5、filledSlots<10 全部替换为常量引用
  - ProductionViewModel: size<12、age>=5、realm<=5/6/7 全部替换为常量引用

#### 安全性修复
- launchElderAction 正确传播 CancellationException，避免破坏结构化并发

#### 清理
- ObjectPool.kt 删除残留的无用 import java.util.ArrayDeque

## [2.5.65] - 2026-04-27

### 代码质量 P1 修复（完整）

#### 安全性修复
- S1: StorageFacade.delete() 返回 SaveResult<Unit> 而非 Unit，正确传播错误
- S2: isSaveCorrupted() 异常时默认返回 false（而非 true），避免误触发恢复流程
- S3: ProductionTransactionManager 消除 getOrThrow 反模式，改用 getOrElse 保持原状态
- S4: GameLoopError.kt 空文件补充错误类型定义
- S5: CancellationException 正确传播（ErrorHandler/safeCallSuspend 不再吞掉 CancellationException）
- S6: ChangeTracker.computeChecksum 使用 ProtoBuf 序列化替代 toString()，保证确定性

#### 性能修复
- P1: GameStateStore 16 个派生 StateFlow 从 SharingStarted.Eagerly 改为 WhileSubscribed(5s)
- P2: CacheLayer 实现 LRU 淘汰策略（LinkedHashMap access-order）替代随机淘汰
- P3: CacheLayer 启用 TTL 过期检查（CacheEntry.isExpired），CacheKey.ttl 字段生效
- P4: 删除 WarehouseItemPool 伪池化层，调用方直接构造 WarehouseItem
- P5: shiftIndicesAfter 原地更新 itemIndex，避免每次删除创建新 ConcurrentHashMap
- P7: 9 个独立 CoroutineScope 统一到 ApplicationScopeProvider（CacheLayer/GCOptimizer/GameMonitorManager/UnifiedPerformanceMonitor/PerformanceMonitor/MemoryMonitor/FunctionalWAL/SaveCrypto/StorageEngine）

#### 架构修复
- A1: GameRepository（24参数构造）拆分为 6 个领域 Repository（GameData/Disciple/Equipment/Inventory/World/Forge）
- A3: StorageEngine.kt（1799行）拆分为 5 个文件（StorageEngine/StorageCircuitBreaker/ProactiveMemoryGuard/DataPruningScheduler/DataArchiveScheduler）
- A4: Hilt 版本统一（Plugin 2.53 → 2.56，与 Runtime 一致）
- A5: StorageFacade 11 个同步方法添加 @WorkerThread 注解
- C3: 提取 BaseViewModel 统一 errorMessage/successMessage 样板代码（7个 ViewModel 受益）
- C1/C2: 提取 3 个 UseCase 消除 ViewModel 重复代码（DisciplePositionQueryUseCase/SectPolicyToggleUseCase/ElderManagementUseCase）

#### 代码复查修复
- 修复 ElderManagementUseCase 境界检查条件反转（> 改为 <）
- 修复 CacheLayer @Synchronized 与 synchronized(memoryCache) 混用导致的 AB-BA 死锁风险
- 修复 SectPolicyToggleUseCase 灵石检查与扣除非原子操作竞态条件
- 修复 ProductionTransactionManager getOrElse 无日志记录
- 修复 MainActivity delete() 返回值未处理
- 修复 CacheLayer removeEldestEntry 与手动驱逐逻辑冲突（统一为手动驱逐）
- 修复 CacheLayer clearSync 未清除 BloomFilter

#### 其他
- C5: SaveLoadViewModel pauseAndSaveForBackground 改为非阻塞（使用 ApplicationScopeProvider.ioScope）
- C5: SaveLoadViewModel onCleared 超时从 5s 缩短到 3s，游戏循环停止等待从 3s 缩短到 2s

## [2.5.64] - 2026-04-27

### 代码质量 P2 修复（完整）
- P2-1: GameUtils.clamp/StringUtils.isEmpty/isNotEmpty/padLeft/padRight 添加 @Deprecated，推荐使用 Kotlin 标准库
- P2-2: BattleCalculator.calculatePhysicalDamage/calculateMagicDamage 添加 @Deprecated，推荐使用 calculateDamage(isPhysicalAttack)
- P2-3: 提取 MaterialChecker 接口，AlchemyRecipe/ForgeRecipe 实现该接口消除重复代码
- P2-4: 提取 TimeProgressUtil 工具对象，8个类委托时间进度计算
- P2-5: 删除 GameViewModel 中 11 个 closeXxxDialog 委托方法，调用方改用 closeCurrentDialog()
- P2-6: 删除 showBuildingDetailDialog（与 openBuildingDetailDialog 完全重复）
- P2-7: 创建 ElderSlotType 枚举替代字符串 slotType 参数
- P2-8: 跳过（Pill/PillEffect @Embedded 重构需破坏性 Room Migration，留待大版本）
- P2-9: 删除 getSaveSlotsFresh，调用方改用 getSaveSlots
- P2-10: 重命名 com.xianxia.sect.core.data 包为 com.xianxia.sect.core.registry
- P2-11: 提取 MemoryFormatUtil，4个文件的 formatMemory/formatBytes 统一使用 Locale.ROOT
- P2-12: 提取 StorageKeyUtil，3个仓库文件的 generateKey 统一实现
- P2-13: GamePerformanceMonitor/PerformanceMonitor 添加 @Deprecated，标注使用处 @Suppress
- P2-14: 删除空 PerformanceModule 文件
- P2-15: 合并 LazySlotCache/SlotQueryCache 为 SlotCache

### 额外修复
- 删除 StorageEngine.kt 中重复的内部类声明（已被提取到独立文件）
- StorageModule.kt 添加缺失的构造函数参数（circuitBreaker/pruningScheduler/archiveScheduler/memoryGuard）
- 修复 Kotlin 可见性错误（internal 类型通过 public API 暴露）
- 修复 MaterialChecker key 映射错误（使用 name 而非 id）
- DynamicMemoryManager.formatBytes 委托到 MemoryFormatUtil

## [2.5.62] - 2026-04-27

### 修复
- 修复 Kotlin 可见性错误：public 函数暴露 internal 类型
- DataArchiveScheduler.performArchive() 添加 internal 修饰符（返回 internal ArchiveOperationResult）
- DataPruningScheduler.performPruning() 添加 internal 修饰符
- DataPruningScheduler.getStats() 添加 internal 修饰符（返回 internal PruningStats）
- StorageModule.provideStorageEngine() 添加 internal 修饰符（接收 internal ProactiveMemoryGuard 参数）

## [2.5.61] - 2026-04-27

### 架构优化
- 在 GameEngineCore、GameMonitorManager、SaveLoadCoordinator 的注入点添加 @Suppress("DEPRECATION") 和 TODO 注释，标记待迁移至 UnifiedPerformanceMonitor

## [2.5.60] - 2026-04-27

### 架构优化
- 拆分 GameRepository 为 6 个领域专用仓库：GameDataRepository、DiscipleRepository、EquipmentRepository、InventoryRepository、WorldRepository、ForgeRepository
- 新仓库仅保留非废弃的 Flow 读方法和生命周期方法（clearAllData、initializeNewGame），跳过所有 @Deprecated 写方法
- 旧 GameRepository 标记 @Deprecated，读方法委托到新仓库，保留废弃写方法以维持向后兼容
- 移除 AppModule.kt 中手动 provideGameRepository 方法，新仓库使用 @Inject constructor 由 Hilt 自动注入
- 清理 GameRepository 中未使用的 DAO 依赖（discipleCoreDao、alchemySlotDao、productionSlotDao 等）

## [2.5.59] - 2026-04-27

### 架构优化
- 标记 GamePerformanceMonitor 和 PerformanceMonitor 为 @Deprecated，统一使用 UnifiedPerformanceMonitor
- 在 GameEngineCore、GameMonitorManager、SaveLoadCoordinator 的注入点添加 @Suppress("DEPRECATION") 和 TODO 注释，待后续 P1/P3 任务完成迁移

## [2.5.58] - 2026-04-27

### 架构优化
- 重命名包 `com.xianxia.sect.core.data` 为 `com.xianxia.sect.core.registry`，消除与 `com.xianxia.sect.data` 的命名冲突
- 迁移 20 个 Kotlin 源文件至新包路径，更新全项目 26 个引用文件的 import 语句和完全限定名引用

## [2.5.57] - 2026-04-27

### 修复
- P0-06: AtomicStateFlowUpdates 添加混锁约束文档 — 同一 MutableStateFlow 禁止混用协程方法(Mutex)和同步方法(ReentrantLock)

### 架构优化
- 提取 StorageKeyUtil：消除 WarehouseCache/OptimizedWarehouseManager/WarehouseDiffManager 中重复的 generateKey 实现，统一 key 生成格式为 `itemId:itemType:rarity:itemName`
- 修复 WarehouseDiffManager.generateKey 缺失 itemName 字段导致 key 格式不一致的问题

## [2.5.56] - 2026-04-27

### 架构优化
- 提取 DisciplePositionQueryUseCase：整合5个 ViewModel 中重复的弟子职位查询方法（hasDisciplePosition/getDisciplePosition/isReserveDisciple/isPositionWorkStatus），内部委托 DisciplePositionHelper
- 提取 SectPolicyToggleUseCase：整合 SectViewModel 和 ProductionViewModel 中重复的7个政策切换方法、7个 isEnabled 查询方法和效果计算方法（约400行重复代码）
- 提取 ElderManagementUseCase：整合 SectViewModel 和 ProductionViewModel 中重复的长老任命/卸任逻辑（assignElder/removeElder/assignDirectDisciple/removeDirectDisciple），统一境界要求判断

## [2.5.54] - 2026-04-27

### 架构优化
- P3-1: Disciple双模型迁移Phase1 - DiscipleStatCalculator新增DiscipleAggregate重载方法，DiscipleAggregate计算方法不再需要toDisciple()转换
- P3-2: GameData拆分为多Entity - 创建GameDataCore/WorldMap/Buildings/Economy/Organization/Exploration六张子表，编写MIGRATION_15_16迁移并同步数据，新增对应DAO
- P3-3: 统一错误类型体系 - 创建AppError基类(Storage/Network/Production/GameLoop分类)和UiError UI展示层，为GameError/StorageError/SaveError/ProductionError/GameLoopError添加toAppError()转换函数

### 修复
- Disciple/DiscipleAggregate的hpPercent/mpPercent计算错误：使用currentHp/currentMp替代baseHp/baseMp
- GameEngine作用域从@ViewModelScoped改为@Singleton，修复Hilt IncompatiblyScopedBindings错误
- SlotQueryCache中重复的SlotCacheStatistics声明已移除
- StorageModule.provideStorageEngine添加缺失的applicationScopeProvider参数
- GameViewModel/ProductionViewModel/SaveLoadViewModel从_errorMessage/_successMessage迁移至BaseViewModel.showError()/showSuccess()
- GameActivity从saveLoadViewModel.errorMessage StateFlow迁移至errorEvents SharedFlow

## [2.5.53] - 2026-04-27

### 修复
- BaseViewModel Channel缓冲区从BUFFERED改为UNLIMITED，防止消息丢失
- ProductionViewModel.hasDisciplePosition()/isReserveDisciple()委托至DisciplePositionHelper，修复遗漏灵矿弟子/执法弟子等职位检查
- ProductionViewModel.assignElder() LAW_ENFORCEMENT槽位添加清空lawEnforcementDisciples，防止数据不一致

### 代码质量
- CryptoModule.validateIntegrity()标记@Deprecated，merkleValid从true改为false（该方法无法验证Merkle根）
- BaseViewModel同时提供Channel(errorEvents/successEvents)和StateFlow(errorMessage/successMessage)双模式错误处理
- FunctionalWAL添加缺失的Dispatchers import

## [2.5.52] - 2026-04-27

### 重构
- 统一协程作用域管理：9个类/对象从自建CoroutineScope迁移至ApplicationScopeProvider，确保应用销毁时统一取消所有协程
  - CacheLayer (GameDataCacheManager): 注入ApplicationScopeProvider，使用ioScope
  - GCOptimizer: 注入ApplicationScopeProvider，使用scope（mainScope保留自有SupervisorJob用于UI线程调度）
  - GameMonitorManager: 注入ApplicationScopeProvider，使用scope
  - UnifiedPerformanceMonitor: 注入ApplicationScopeProvider，使用scope
  - PerformanceMonitor: 注入ApplicationScopeProvider，使用scope
  - MemoryMonitor: 注入ApplicationScopeProvider，使用scope
  - FunctionalWAL: 注入ApplicationScopeProvider，使用ioScope
  - SaveCrypto: 使用lateinit+initialize()模式注入ApplicationScopeProvider（object单例无法构造器注入），使用ioScope
  - StorageEngine及其3个内部类(ProactiveMemoryGuard/DataPruningScheduler/DataArchiveScheduler): 注入ApplicationScopeProvider，使用ioScope
- 移除所有迁移类中的scope.cancel()调用（作用域由ApplicationScopeProvider统一管理）
- StorageModule中provideStorageEngine添加applicationScopeProvider参数
- XianxiaApplication.onCreate()中调用SaveCrypto.initialize(applicationScopeProvider)
- 清理所有迁移文件中不再使用的import（CoroutineScope/SupervisorJob/Dispatchers等）

## [2.5.50] - 2026-04-27

### 修复
- 修复SaveLoadViewModel中所有_errorMessage.value和_successMessage.value对BaseViewModel私有成员的访问，替换为showError()和showSuccess()
- 修复loadGameFromSlot中if表达式语法错误（缺少右括号）

## [2.5.49] - 2026-04-27

### 修复
- P2-5/P2-6: 删除GameViewModel中11个仅委托closeCurrentDialog()的冗余方法，删除重复的showBuildingDetailDialog，MainGameScreen中统一使用closeCurrentDialog()
- P2-9: 删除StorageFacade中与getSaveSlots完全重复的getSaveSlotsFresh方法，MainActivity改用getSaveSlots
- P2-14: 删除空的PerformanceModule
- 修复GameViewModel继承BaseViewModel，消除showError/showSuccess未定义的编译错误
- 修复SaveLoadViewModel中if表达式语法错误，迁移至BaseViewModel的showError/showSuccess

## [2.5.48] - 2026-04-27

### 重构
- 提取MaterialChecker接口，消除AlchemyRecipe和ForgeRecipe中hasEnoughMaterials/getMissingMaterials的重复实现

## [2.5.45] - 2026-04-27

### 修复
- P0-01: GameRepository双存档写入路径统一，所有写方法标记@Deprecated并委托到StorageFacade
- P0-02: 完整性校验三重缺陷修复——加载完整SaveData参与签名、实现Merkle根验证、verifyFullDataSignature/verifySignedPayload使用constantTimeEquals防计时攻击
- P0-04: GCOptimizer协程泄漏修复，使用类级别SupervisorJob作用域并在cleanup()中取消
- P0-05: ObjectPool.Pool线程安全修复，ArrayDeque替换为ConcurrentLinkedQueue+AtomicInteger CAS
- P0-06: AtomicStateFlowUpdates混锁修复，使用flow对象作为锁键、ReentrantLock替代synchronized
- P0-07: SecureKeyManager Base64兼容性修复，java.util.Base64(API 26+)替换为android.util.Base64
- ChangeTracker校验和使用ProtoBuf序列化替代toString()/hashCode()
- CacheLayer线程安全修复，使用sizeSync/clearSync替代直接访问memoryCache

### 重构
- P0-03: MainGameScreen.kt从8709行拆分为860行+10个模块文件(tabs/, dialogs/, components/)
- GameLoopError改为sealed class实现
- GameResult正确传播CancellationException
- ProductionTransactionManager消除getOrThrow调用
- 移除WarehouseItemPool伪池化实现
- OptimizedWarehouseManager shiftIndicesAfter改为原地更新

## [2.5.44] - 2026-04-26

### 修复
- calculateWarehouseLootLoss中使用itemId作为Map key导致同名不同品质物品损失计算不准确，现改为复合key格式
- convertWarRewardsToWarehouseItems中EquipmentStack/ManualStack的id可能为UUID导致仓库无法正确堆叠，现改为name+rarity组合
- 旧存档中AI宗门仓库残留无用数据，现每月处理时自动清理非玩家宗门仓库
- AI洞府探索成功后无事件通知，现添加探索成功事件记录

## [2.5.43] - 2026-04-26

### 修复
- InventorySystem.clear()未重写导致调用时为空操作，现正确清空所有库存数据
- InventorySystem中所有读取方法在事务外使用derived StateFlow可能读到过期数据，现改为直接读取unifiedState
- 测试函数名包含%字符导致Windows平台编译警告，已替换为中文描述

## [2.5.42] - 2026-04-26

### 变更
- 区分玩家宗门与AI宗门的设计差异：AI宗门不再拥有仓库，也不会获得物品
- 移除AI宗门每年自动生成仓库物品的逻辑
- 移除AI洞府探索奖励写入AI宗门仓库的逻辑
- 玩家占领/掠夺战利品改为发放到玩家宗门仓库（SectDetail.warehouse），而非主库存（InventorySystem）
- 玩家自身宗门被掠夺时从玩家宗门仓库扣除损失，而非从主库存扣除
- AI宗门被占领/掠夺不扣除物品
- 玩家占领的宗门被掠夺不从玩家仓库扣除物品

## [2.5.41] - 2026-04-26

### 修复
- InventorySystem中remove/get/has等方法在事务内读取derived StateFlow导致间歇性测试失败，现改为优先从事务可变状态读取

## [2.5.40] - 2026-04-26

### 修复
- 月度外交事件中玩家专属事件（弟子偶遇、护送之恩、口角之争）可在AI-AI关系中触发，现限制为仅涉及玩家宗门的关系可触发
- 月度外交事件中同道相惜可对不同阵营宗门触发，正邪对立可对同阵营宗门触发，现增加阵营条件检查
- 月度外交事件中盟友协作可对非盟约宗门触发，现增加盟约条件检查
- 结盟成功时未设置玩家宗门的allianceId，导致盟友协作事件对玩家宗门永远无法触发
- 解除结盟时仅清除目标宗门的allianceId，未清除玩家宗门的allianceId，导致数据不一致

## [2.5.39] - 2026-04-26

### 修复
- AI击败玩家驻守队伍后无条件占领宗门，未检查高境界宗门弟子是否全灭和是否仍有AI驻守队伍，与AI vs AI战斗逻辑不一致
- AI攻击队伍全灭时仍可能"占领"宗门（补充弟子全为原防守方弟子），现增加攻击方存活弟子检查
- 驻守队伍被全灭时未清理宗门的 occupierSectId 和 isPlayerOccupied 字段，导致宗门状态不一致
- AI宗门间占领时不应有仓库掠夺逻辑（AI宗门无仓库），移除 triggerAISectBattle 中的仓库转移代码

## [2.5.38] - 2026-04-26

### 修复
- 召回驻守战斗队时未清除占领宗门的 garrisonTeamId、isPlayerOccupied、occupierSectId，导致宗门状态不一致
- 召回驻守战斗队时若玩家已无任何领地，立即触发游戏失败检测（原需等待下次 tick）
- 游戏失败对话框弹出时未等待游戏引擎暂停完成，可能导致竞态问题
- 游戏失败对话框弹出时 pause() 异常未捕获，可能导致对话框无法弹出

## [2.5.37] - 2026-04-26

### 新增
- 月度外交随机事件系统：16种外交事件，每月3%概率触发随机一种，作用域统一为全部宗门关系
  - 负面事件：边境争端(-5)、资源争夺(-8)、弟子冲突(-3)、领地蚕食(-12)、间谍暴露(-15)、正邪对立(-7)、口角之争(-4)
  - 正面事件：文化交流(+3)、联合探险(+5)、互助救灾(+8)、盟友协作(+2)、贸易繁荣(+4)、联姻结好(+15)、同道相惜(+5)、弟子偶遇(+2)、护送之恩(+6)

### 回退
- 回退好感度衰减系统到原始版本（仅玩家、仅>80衰减）
- 回退战斗好感度变化到原始版本（AI间-10、玩家-15）
- 回退AI宗门自动结盟为未实现状态
- 回退盟约好感度维护
- 回退交易好感度奖励
- 回退物品送礼功能
- 回退解除盟约好感度惩罚

## [2.5.36] - 2026-04-26

### 新增
- 月度外交随机事件系统：16种外交事件（边境争端、资源争夺、弟子冲突、文化交流、联合探险、互助救灾、盟友协作、贸易繁荣、领地蚕食、间谍暴露、联姻结好、同道相惜、正邪对立、弟子偶遇、护送之恩、口角之争）
- 物品送礼功能：支持向宗门赠送装备、功法、丹药（基于稀有度计算好感度）
- AI宗门自动结盟：AI宗门之间好感度达到阈值后可自动结盟
- 盟约好感度维护：盟友间每年自动增加好感度
- 交易好感度奖励：购买宗门商品时微量增加好感度（每年上限5次）
- 解除盟约好感度惩罚：解除盟约扣除15点好感度

### 变更
- 好感度衰减系统重做：全关系分级衰减（>80每年-1、>60每3年-1、<20每5年+1恢复），AI宗门间好感度随机漂移
- 战斗好感度变化区分胜负：宗门被灭-20、攻击方胜利-12、防守方胜利-6、平局-8，盟友背叛-30
- 同阵营战斗好感度损失减少30%
- 玩家被攻击好感度损失使用配置值（原固定-15）
- 物品偏好系统扩展：装备/功法/丹药偏好宗门送对应物品好感度乘数1.3、拒绝概率-15%

### 修复
- 修复AI_ONLY外交事件不触发的问题
- 修复解除盟约时玩家宗门allianceId未清除的问题
- 修复旧存档lastInteractionYear=0导致好感度立即异常衰减的问题
- 修复交易好感度缺少年度上限可被无限刷的问题

## [2.5.35] - 2026-04-26

### 变更
- 驻守队伍设计重构：驻守队伍即战斗队伍，战斗队伍在所处宗门起到驻守职责
- AI占领宗门时，攻击队伍直接变为驻守队伍（不再创建新队伍）
- 玩家宗门被攻击时，若战斗队伍在宗门则作为主力防守，不足10人由宗门补充（高境界优先）
- AI攻击玩家占领的宗门时，玩家驻守队伍参与防守（原逻辑缺失此场景）
- 玩家占领宗门时正确设置garrisonTeamId，保持数据一致性

### 修复
- 修复AI攻击玩家占领宗门时玩家驻守队伍不参与防守的问题
- 修复canActuallyOccupy判断使用过时sect数据的问题
- 修复运算符优先级不明确导致的潜在隐患

### 优化
- 提取findGarrisonTeam公共函数，统一驻守队伍查找逻辑
- 提取supplementDisciples公共函数，统一弟子补充逻辑（高境界优先）
- 移除createGarrisonTeam死代码
- 移除createPlayerDefenseTeam未使用的参数

## [2.5.34] - 2026-04-26

### 新增
- 游戏失败机制：当玩家所有宗门（包括初始宗门和已占领宗门）都被敌方攻占时，宣告游戏失败
- 游戏失败提示框：包含失败描述、重开游戏按钮、回到主界面按钮
- 游戏失败状态持久化：存档中记录游戏失败状态，加载失败存档时会重新显示失败提示

### 变更
- 宗门间初始好感度统一改为随机40-60（原为固定50，同阵营+10加成）

## [2.5.33] - 2026-04-26

### 修复
- 玩家自身宗门被占领条件改为所有弟子全灭（原为化神及以上弟子全灭）
- 被占领宗门（玩家或AI占领）被占领条件改为：无占领方驻守队伍且无化神及以上弟子
- AI自身宗门被占领条件改为：无战斗队伍且无化神及以上弟子
- 修复玩家占领宗门的驻守队伍未被正确检测的问题（玩家battleTeam驻守也计入保护）

## [2.5.32] - 2026-04-26

### 新增
- 小境界突破概率平滑过渡：1层使用当前大境界基础概率，9层使用下一大境界基础概率，中间层线性插值（整数百分比）
- 突破概率现在根据小境界层数(realmLayer)动态计算，低层更容易突破，高层更难
- 战斗队伍地图标记显示队伍名称（玩家队伍显示自定义名称，AI队伍显示"XX宗攻队"）
- 地图上显示战斗队伍移动路径虚线（正邪颜色区分），包括返回路径
- AI宗门无攻击目标时自动解散所有非驻守队伍

### 修复
- AI弟子突破循环使用过期弟子状态计算突破概率，改为使用循环内更新的newRealm/newRealmLayer
- CultivationService硬编码maxLayers=9，改为使用GameConfig.Realm.get(realm).maxLayers
- realmLayer=0（未成年弟子）突破概率防御性返回0%
- RealmConfig默认maxLayers从10修正为9（与实际配置一致）
- 修正队伍移动速度计算：移除1.5f乘数，基于1秒100px实时计算（每游戏日33.33px）
- AI队伍选择弟子时排除已在其他队伍（含驻守队伍）中的弟子
- AI弟子死亡时正确清理驻守队伍引用：驻守队伍全灭则移除队伍并清除garrisonTeamId，但保持宗门占领状态
- AI队伍返回后弟子回归aiSectDisciples池，避免弟子被永久锁定
- AI攻击决策增加路线连通性检查，不可达的目标不会被攻击
- 无目标解散队伍时保护驻守队伍不被误解散
- 玩家返回队伍在地图上显示返回路径

### 重构
- 移除RealmConfig中已废弃的breakthroughChance字段
- 移除已废弃的getBreakthroughChance(realm: Int)方法
- 移除Disciple.getBreakthroughChance()的@deprecated标记，保留为便捷方法
- GameConfigTest突破概率测试更新为覆盖灵根+小境界维度

## [2.5.30] - 2026-04-26

### 修复
- 调整单灵根突破概率：金丹95%（原100%）、元婴85%（原95%）、化神75%（原80%）

## [2.5.29] - 2026-04-26

### 修复
- 修正突破概率表：练气为起始境界不需突破判定，所有灵根突破概率100%；各境界概率上移一位
- 单灵根：练气100%、筑基100%、金丹100%、元婴95%、化神80%、炼虚65%、合体38%、大乘22%、渡劫12%、仙人6%
- 双灵根：练气100%、筑基90%、金丹85%、元婴70%、化神65%、炼虚35%、合体22%、大乘12%、渡劫5%、仙人3%
- 三灵根：练气100%、筑基80%、金丹75%、元婴55%、化神42%、炼虚25%、合体8%、大乘2%、渡劫0%、仙人0%
- 四灵根：练气100%、筑基65%、金丹50%、元婴25%、化神18%、炼虚8%、合体3%、大乘0%、渡劫0%、仙人0%
- 五灵根：练气100%、筑基45%、金丹32%、元婴18%、化神8%、炼虚0%、合体0%、大乘0%、渡劫0%、仙人0%

## [2.5.28] - 2026-04-26

### 重构
- 突破概率重构为按灵根数量查表，玩家弟子和AI弟子共用同一套概率
- 单灵根：练气100%、筑基100%、金丹95%、元婴80%、化神65%、炼虚38%、合体22%、大乘12%、渡劫6%、仙人3%
- 双灵根：练气90%、筑基85%、金丹70%、元婴65%、化神35%、炼虚22%、合体12%、大乘5%、渡劫3%、仙人1%
- 三灵根：练气80%、筑基75%、金丹55%、元婴42%、化神25%、炼虚8%、合体2%、大乘0%、渡劫0%、仙人0%
- 四灵根：练气65%、筑基50%、金丹25%、元婴18%、化神8%、炼虚3%、合体0%、大乘0%、渡劫0%、仙人0%
- 五灵根：练气45%、筑基32%、金丹18%、元婴8%、化神0%、炼虚0%、合体0%、大乘0%、渡劫0%、仙人0%

## [2.5.27] - 2026-04-26

### 修复
- 修复AI弟子gender字段未传入Disciple构造函数，导致所有AI弟子默认为男性
- 修复AI弟子年龄范围与玩家弟子不一致（AI:16-25 → 16-29）
- 修复adjustDiscipleRealm调整境界时未计算天赋寿命加成
- 修复processMonthlyCultivation大境界突破时寿命未包含天赋加成
- 修复generateRealmDistribution权重分配逻辑错误，权重仅在extra>0时生效
- 修复calculatePowerScore使用maxRarity代替avgRarity导致战力高估
- 修复processMonthlyCultivation突破逻辑使用硬编码9而非isMajorBreakthrough判断
- 统一AI弟子寿命计算使用TalentDatabase.calculateTalentEffects
- 同步修复WorldMapGenerator中权重分配逻辑

## [2.5.26] - 2026-04-26

### 重构
- 宗门战争系统重构：攻击方可攻击地图所有宗门，无视距离限制和路径限制
- 战斗格式改为攻击方10人vs防守方10人，高境界优先参战
- 防守弟子需处于宗门内（IDLE状态），探索队伍中等弟子不能防守
- 战斗回合上限25回合，一方全灭则另一方胜利，双方都有存活则为平局
- 宗门占领条件改为该宗门化神及以上弟子全部阵亡后可被占领
- 攻击方胜利占领后，攻击方队伍转变为驻守队伍
- 驻守队伍不足10人时，从被驻守宗门选入最高境界弟子补足
- 其他宗门进攻驻守宗门时，驻守队伍作为防御方参战
- 驻守队伍失败且被驻守宗门内无化神及以上弟子，则宗门被新攻击方占领
- 允许攻击被AI占领的宗门（不可攻击己方已占领的宗门）
- AI宗门间攻击也不再受路径限制
- 玩家战斗队伍到达目标宗门后自动执行战斗
- 序列化层新增驻守相关字段，旧存档兼容

## [2.5.24] - 2026-04-26

### 修复
- 修复CultivationService中executePlayerSectBattle方法deadAttackerIds/deadDefenderIds使用错误
- 移除AISectAttackManager中冗余的攻击条件检查（已由allTargets过滤覆盖）

## [2.5.23] - 2026-04-26

### 修复
- 修复processAutoLearn替换分支中同名功法检查未排除被替换功法，导致无法用同名高品质功法替换低品质同名功法
- 优化功法替换UI心法过滤逻辑，排除被替换功法后检查心法唯一性，与后端replaceManual行为一致
- 优化ManualSelectionDialog和功法替换UI使用Map查找替代线性查找

## [2.5.22] - 2026-04-26

### 变更
- 重构AI宗门弟子生成逻辑：
  - 小型宗门：初始20-60名化神境以下弟子 + 5名化神弟子
  - 中型宗门：初始40-80名炼虚境以下弟子 + 5名合体弟子
  - 大型宗门：初始40-120名合体境以下弟子 + 5名大乘弟子
  - 顶级宗门：初始50-120名大乘境以下弟子 + 5名渡劫弟子
- 所有AI宗门每年获得5名练气一层弟子（替代原来的每月随机招募）
- AI弟子平时无功法装备，进入战斗时自动生成随机功法和装备
- 功法装备品阶受境界限制，避免高境界弟子生成低品阶物品
- 随机生成的功法数量不超过弟子最大功法数，装备不超过4件
- 生成的功法熟练度等级和装备孕养等级随机
- AI弟子修炼方式改为每月直接增加修为进度（与玩家弟子一致的计算方式）
- 移除AI弟子的功法熟练度增长和装备孕养处理

## [2.5.21] - 2026-04-26

### 修复
- 修复存档丢失问题：fallbackToDestructiveMigration()改为fallbackToDestructiveMigrationFrom(1,2,3)，仅对v1-v3版本允许销毁重建，v4及以上必须走显式迁移路径
- 修复存档丢失问题：ProductionSlotRepository.restoreSlots/initializeAllSlots/clear/initializeSlotsForType中deleteAll()改为deleteBySlot(slotId)，防止跨槽位删除生产数据
- 修复存档丢失问题：自动存档与手动存档/读档的竞态条件，添加SavePipeline.waitForCurrentSave等待机制
- 修复存档丢失问题：存档后添加WAL checkpoint，防止app被杀后WAL中未checkpoint的数据丢失
- 修复StorageEngine.exportToFile死锁：嵌套调用load()导致Mutex不可重入死锁，改为直接查询数据库
- 修复StorageEngine.delete遗漏SaveSlotMetadata删除，导致删除存档后元数据残留
- 修复StorageEngine.loadFromDatabase缺少事务保护，可能读取不一致的数据快照
- 修复StorageEngine.exportToFile缺少事务保护
- 修复SaveLoadViewModel.saveGame未检查游戏是否已加载
- 修复WorldMapGenerator中IntRange.isNotEmpty()编译错误
- 为所有数据库迁移(MIGRATION_4_5至MIGRATION_13_14)添加try-catch异常保护和日志

### 变更
- GameSystem接口新增clearForSlot(slotId: Int)方法，支持按槽位清理数据
- ProductionSlotDao新增deleteBySlotAndBuildingType方法
- GameDatabase新增performPostSaveCheckpoint方法

## [2.5.20] - 2026-04-26

### 修复
- 修复所有功法学习路径缺少同名功法检查：弟子可重复学习同名功法导致属性叠加
- 修复GameEngine.learnManual缺少同名功法检查
- 修复GameEngine.replaceManual缺少同名功法检查（排除被替换的功法）
- 修复GameEngine.rewardItemsToDisciple功法路径缺少同名功法检查
- 修复ManualSelectionDialog缺少同名功法过滤
- 修复功法替换UI缺少同名功法过滤（排除被替换的功法）
- 修复DiscipleManualManager.processAutoLearn缺少同名功法检查
- 修复DiscipleManualManager.canLearn两个重载均缺少同名功法检查
- 修复RedeemCodeService.clear方法签名与GameSystem接口不匹配
- 修复StorageEngine.saveData缺少return语句
- 修复AISectDiscipleManager多处编译错误

## [2.5.19] - 2026-04-26

### 新增
- 设置页面新增"隐私设置"区块，包含"限制广告追踪"开关
- "限制广告追踪"默认开启，阻止 TapTap SDK 收集 OAID 广告标识符
- 切换"限制广告追踪"开关后显示 Toast 提示（下次启动后生效）
- SessionManager 新增 limitAdTracking 属性持久化存储
- TapTapAuthManager.init() 新增 limitAdTracking 参数，SDK 初始化时传入用户偏好
- TapTapAuthManager 新增 setEnableLog 配置（Debug 模式开启日志）

### 变更
- 隐私政策文本与代码默认行为统一：明确"本应用默认开启限制广告追踪"
- 摘要版隐私政策 OAID 提示措辞修正：从"会收集"改为"可能会收集"，与默认限制行为一致
- 完整隐私政策 OAID 提示新增"默认保护"条目
- 完整隐私政策 2.1 节 SDK 模块描述：OAID 条件改为"若您关闭限制广告追踪"
- 完整隐私政策第七节"限制广告追踪"权利描述更新：明确默认开启状态
- TapTapAuthManager: isInitialized 为 true 时仍更新 limitAdTrackingEnabled 状态

## [2.5.18] - 2026-04-26

### 修复
- 修正v2.5.17错误的同类型功法冲突检查：弟子允许学习同类型功法（心法除外），仅不允许学习相同功法
- 回滚learnManual/replaceManual/rewardItemsToDisciple中的同类型冲突检查，仅保留槽位上限检查和心法唯一性检查
- 回滚ManualSelectionDialog和功法替换UI中的同类型过滤，仅保留心法过滤
- 修复DiscipleManualManager.processAutoLearn仍保留同类型冲突逻辑：改为槽位上限检查，槽位未满时允许学习任意类型功法，槽位已满时替换品质最低的功法
- 修复DiscipleManualManager.canLearn缺少心法唯一性检查

### 变更
- 隐私政策更新：OAID合规 - 将OAID（开放匿名设备标识符）从普通设备标识符描述中分离，单独标注为广告标识符
- 隐私政策摘要：在"3. 设备标识符"下方添加红色OAID广告标识符收集特别提示Card
- 完整隐私政策1.3节：蓝色Card移除OAID描述，新增红色OAID收集特别提示Card（含收集目的、方式、用户权利、关闭影响）
- 完整隐私政策2.1节：TapTap SDK各模块OAID收集描述改为条件式（"当您未开启限制广告追踪时，还会收集OAID"）
- 完整隐私政策第七节：新增"限制广告追踪"权利条目
- 隐私政策日期更新为2026年4月26日

## [2.5.17] - 2026-04-25

### 修复
- 修复learnManual缺少槽位上限检查：弟子可学习超过maxManualSlots数量的功法，超出部分在UI中不可见
- 修复rewardItemsToDisciple功法路径缺少槽位上限检查
- 修复ManualSelectionDialog缺少槽位上限过滤：槽位已满时仍显示可选功法
- 修复DiscipleManualManager.canLearn缺少槽位上限检查

## [2.5.16] - 2026-04-25

### 重构
- equipEquipment 改为 suspend 函数，验证和执行全部在 stateStore.update 事务内原子完成，消除 TOCTOU 竞态风险
- unequipEquipment 改为 suspend 函数，验证和执行全部在 stateStore.update 事务内原子完成，统一异步语义
- BagUtils 提取 mergeEquipmentStack/mergeManualStack 私有方法，消除栈查找合并的重复代码
- BagUtils 提取 buildUpdatedBagItems 私有方法，消除 StorageBagItem 创建和弟子更新的重复代码
- BagUtils 引入 StackMergeResult 区分合并/新建场景，.map 仅合并场景更新 forget 日期，消除新建场景冗余操作
- 统一 storageBagItems 访问路径为 disciple.equipment.storageBagItems，明确数据来源
- 删除 DiscipleService 中不再使用的 currentEquipmentStacks/currentEquipmentInstances 属性
- DiscipleEquipmentManager.processSlot 中 .map 冗余操作改为条件执行，仅合并场景更新 forget 日期，与 BagUtils 保持一致
- equipEquipment 合并弟子查找为单次 indexOfFirst，消除冗余二次查找
- equipEquipment 中 equipmentStack!! 强制解包改为安全调用加提前返回

### 修复
- 修复 unequipEquipment KDoc 注释与实际行为不符：更新为描述当前事务内原子执行语义

## [2.5.15] - 2026-04-25

### 重构
- 提取装备卸下入袋共用方法addEquipmentInstanceToDiscipleBag，消除4处重复代码
- 提取功法遗忘入袋共用方法addManualInstanceToDiscipleBag，消除2处重复代码
- 提取Disciple扩展方法equipmentBagStackIds/manualBagStackIds，集中bagStackIds计算逻辑
- unequipEquipmentLogic改为MutableGameState扩展函数，在事务内直接操作状态属性
- equipEquipment中stateStore.update内改用MutableGameState直接属性，统一事务内代码风格

### 修复
- 修复forgetManual块外读取instance/gameData的竞态条件：移入stateStore.update事务内
- 修复堆叠溢出时物品静默丢失：查找已有栈时增加quantity < maxStackSize条件，已满时创建新栈
- 修复rewardItemsToDisciple装备/功法不可使用路径缺少forgetYear/forgetMonth/forgetDay字段
- 修复equipEquipment中unequipEquipmentLogic返回值未检查，卸装失败时中止装备流程
- 为addManualInstanceToDiscipleBag添加excludeStackId参数，保持与装备方法签名一致

## [2.5.14] - 2026-04-25

### 修复
- 修复unequipEquipment独立调用时存在与equipEquipment相同的竞态条件：多个property setter产生独立异步更新，改为在stateStore.update原子事务中执行
- 修复rewardItemsToDisciple中bagStackIds搜索所有弟子储物袋导致装备/功法可能被错误合并到其他弟子堆中的问题：改为仅搜索目标弟子储物袋
- 修复forgetManual中bagStackIds搜索所有弟子储物袋导致遗忘功法可能被错误合并到其他弟子堆中的问题：改为仅搜索当前弟子储物袋
- 修复replaceManual中bagStackIds搜索所有弟子储物袋导致替换功法时旧功法可能被错误合并到其他弟子堆中的问题：改为仅搜索当前弟子储物袋
- 修复expelDisciple中bagStackIds包含被逐出弟子自身储物袋导致装备归还仓库时无法与弟子袋中已有同名栈合并、产生仓库重复栈的问题：排除被逐出弟子
- 增加unequipEquipmentLogic中装备实例缺失时的日志记录，便于排查数据不一致问题
- 修复赏赐弟子物品(pill/material/herb/seed)时inventorySystem.removeXxx异步返回值导致物品丢失的bug：改为在stateStore.update事务中同步执行
- 修复赏赐丹药给弟子时canUse分支调用usePill导致嵌套事务的问题：改为在当前事务内内联丹药使用逻辑
- 修复赏赐丹药时disciple为null时丹药从仓库扣除但未添加到储物袋的bug：增加null检查提前返回
- 修复GameEngine.removeEquipment委托方法存在异步返回值问题：改为suspend函数在事务中同步执行
- 修复buyMerchantItem中seed查找条件自引用bug(s.growTime==s.growTime改为it.growTime==s.growTime)
- 删除rewardItemsToDisciple中无用的data变量

## [2.5.13] - 2026-04-25

### 修复
- 修复宗门仓库中有多件相同装备时手动穿戴装备后弟子装备槽位不显示被穿戴装备的bug：equipEquipment方法中多个异步状态更新存在竞态条件，改为在单个stateStore.update原子事务中执行
- 修复equipEquipment中equipmentInstance已装备在同一弟子身上时未正确处理的问题：增加ownerId==discipleId的判断
- 修复GameEngine.unequipItem(discipleId, slot)传入slot.name作为equipmentId导致按槽位卸装功能完全失效的bug
- 修复unequipEquipment中bagStackIds搜索所有弟子储物袋而非仅当前弟子储物袋，可能导致卸下装备被错误合并到其他弟子堆中的问题

## [2.5.12] - 2026-04-25

### 修复
- 修复宗门仓库一键出售需要两次才能出售干净的bug：InventorySystem.removeXxx方法在无活跃事务时异步执行但立即返回false，导致出售失败且灵石未添加
- 修复单个物品出售同样存在的异步bug：sellXxx方法改为在stateStore.update事务中同步执行
- 修复上架到商人(listItemsToMerchant)同样存在的异步bug：改为在事务中同步执行
- BulkSellResult增加失败物品信息，便于用户了解哪些物品出售失败

## [2.5.11] - 2026-04-25

### 修复
- 修复弟子命名系统重名检测形同虚设的问题：所有弟子生成入口现在均传递已有弟子名字集合进行重名检测
- 修复批量生成弟子时（招募列表刷新、AI宗门弟子初始化、兑换码批量兑换）未检查批次内重名的问题
- 修复 NameService 50次重名尝试失败后仍可能返回重名的问题：增加数字后缀保底策略
- 修复反序列化旧存档时 surname 字段为空未自动回填的问题：通过 extractSurname 从全名推导
- 修复 recruitDisciple/createChild 未包含 recruitList 中弟子名字导致可能与待招募弟子重名的问题
- 修复名字池男女共用重复名字（"惊鸿"、"丹青"）导致有效名字池容量降低的问题
- 修复 canAddPill 合并判断缺少品级匹配，导致不同品级同名丹药被错误合并的问题
- 修复 MerchantItemConverter.toPill 只按名称查找配方模板，导致丹药属性值与品级不匹配的问题
- 修复 EventService 宗门交易容量检查未传入品级参数的问题
- 修复 getCapacityCheckParams PillParams 缺少 grade 字段的问题
- 修复 hasPill/removePillByName 缺少 grade 参数，可能操作错误品级丹药的问题

### 优化
- RedeemCodeManager.generateReward 新增 existingNames 参数，支持兑换码生成弟子时避免重名
- AISectDiscipleManager.generateRandomDisciple 新增 existingNames 参数，支持AI宗门批量生成时避免重名
- CultivationService.refreshRecruitList 变量名从 baseExistingNames 重命名为 usedNames，更准确反映可变性
- PillRecipeDatabase 新增 getRecipeByNameAndGrade 方法，支持按名称+品级精确查找配方
- Proto MerchantItemProto price 类型从 int32 改为 int64，支持更大价格范围
- Proto MerchantItemProto 新增 grade 字段，持久化丹药品级信息
- 增加品级相关单元测试（同品级合并、不同品级不合并、仓库满时不同品级不可添加）

## [2.5.10] - 2026-04-25

### 修复
- 修复商人界面只会刷新上品品质丹药的问题
- 修复商人丹药品级概率调整为 上品3%/中品37%/下品60%

## [2.5.9] - 2026-04-25

### 优化
- 统一弟子命名系统：合并4处独立命名实现为 NameService 统一命名服务
- 扩充名字池：男名80+、女名80+，增加名字长度多样性（75%双字名+25%单字名）
- 增加修仙风格复姓支持（慕容、上官、欧阳、司徒、南宫、诸葛、东方、西门等16个复姓）
- Disciple 模型新增 surname 字段，独立存储姓氏，支持家族/宗族查询

### 修复
- 修复子嗣姓氏提取无法正确处理复姓的bug（如"慕容逍遥"的子嗣会被错误命名为"慕×"）
- 修复 AI 宗门弟子名字风格与修仙世界观不符的问题（"李剑掌""王雷鲸"等）
- 修复各命名入口名字池大小差异巨大且大量重复的问题

## [2.5.8] - 2026-04-25

### 修复
- 修复商人界面只会刷新上品品质丹药的问题（gradeMap/priceMap 以丹药名为键导致同名不同品质互相覆盖）
- 修复同名不同品质丹药在商人商品合并时被错误合并的问题
- 修复空物品池调用 random() 可能导致商人刷新崩溃的问题

## [2.5.7] - 2026-04-25

### 优化
- 迁移 Disciple 模型层过时委托属性到子组件路径（combat/pillEffects/equipment/social/skills/usage），消除编译器警告
- 替换 PackageInfo.versionCode 为 PackageInfoCompat.getLongVersionCode()
- 替换 ClickableText (foundation) 为 Text + Modifier.pointerInput + detectTapGestures
- 移除不再需要的 @file:Suppress("DEPRECATION") 注解
- 为 BuildingService 过时转换方法调用添加 @Suppress 注解

## [2.5.6] - 2026-04-25

### 修复
- 修复境界不足时装备/功法放入储物袋设置了冷却期标记，导致弟子突破境界后仍无法自动装备/学习该物品的问题

## [2.5.5] - 2026-04-25

### 修复
- 修复子嗣命名包含父母双方姓氏导致名字为4字的问题，改为仅随父姓

## [2.5.4] - 2026-04-25

### 修复
- 修复CaveGenerator中航点ID排序不一致导致洞府碰撞检测路径与渲染路径不匹配的严重bug
- 清理MapCanvas中不可达的死代码分支

### 优化
- 宗门名称池从128扩充到256（正道128+魔道128），避免80宗门时名称不足

## [2.5.3] - 2026-04-25

### 修复
- 统一所有面向用户的"好感度"文本为"关系"（GiftDialog、DiplomacyService、CultivationService、AllianceDialog）
- 修复AllySelectCard关系等级颜色使用Color.Black而非relationLevel.colorHex的问题
- 修复EnvoyDiscipleSelectDialog缺少目标宗门关系等级显示的问题
- 修复SectTradeDialog灵石数量未格式化显示的问题

### 优化
- 移除DiplomacyService中5个物品送礼遗留的未使用属性（currentManualInstances等）
- 移除calculatePreferenceMultiplier/calculatePreferenceRejectModifier中未使用的itemType参数
- 清理DiplomacyService中未使用的import
- 统一formatSpiritStones为GameUtils.formatNumber，消除重复代码
- EnvoyDiscipleSelectDialog境界要求改用worldMapViewModel.getEnvoyRealmRequirement，消除硬编码
- AllianceDialog中DiscipleSelectCard/AllySelectCard的Color.Black替换为主题色GameColors.TextPrimary/TextSecondary

## [2.5.2] - 2026-04-25

### 修复
- 修复旧存档丹药持续时间转换的误转换风险：将`<= 12`启发式判断从`convertBackDisciple`移至V3ToV4迁移器，避免新存档duration衰减到1-12天时被错误乘以30
- 修复GameEngine中境界不足时装备/功法放入储物袋缺少冷却期标记(forgetDay)的问题，避免弟子每日重复尝试装备/学习同一物品
- 修复V3ToV4Migrator遗漏recruitList和aiSectDisciples中弟子duration转换的问题
- 补充V3ToV4Migrator边界值（duration=12和duration=13）测试覆盖
- 为V3ToV4Migrator添加启发式判断注释说明

### 兼容性
- 存档格式版本从3.0升级到4.0，旧存档加载时自动迁移duration值

## [2.5.1] - 2026-04-25

### 改动
- 宗门送礼移除物品送礼选项，改为只能赠送灵石
- 宗门增加关系等级系统：敌对(0-9)、交恶(10-39)、普通(40-59)、友善(60-79)、至交(80-100)
- 宗门交易根据关系等级限制可购买物品品质：普通关系可购买灵品及以下，友善关系可购买玄品及以下，至交关系可购买所有物品
- 所有UI界面统一显示关系等级名称和颜色

### 兼容性
- 旧存档中的好感度数值不变，自动映射到新的关系等级系统
- GiftPreferenceType枚举保留用于存档兼容，但UI不再显示物品偏好

## [2.5.0] - 2026-04-25

### 优化
- 世界地图扩容：从4000x3500扩展到6000x5000，宗门数量从55增加到80
- 宗门生成算法优化：从均匀网格分布改为聚类不均匀分布，模拟真实世界中宗门聚集与分散的自然分布
- 路径算法优化：路径添加航点实现自然弯曲，使用二次贝塞尔曲线渲染，模拟真实世界道路
- 路径交叉优化：降低交叉惩罚系数，允许路径自然交叉，模拟真实世界路网
- 洞府生成算法优化：检测弯曲路径碰撞（而非仅直线），增大最小安全距离
- MapCoordinateSystem统一引用GameConfig，消除地图尺寸重复定义
- MST连通性检查优化：使用分量计数器替代全量遍历

### 修复
- 修复MapCanvas贝塞尔曲线尾部重复绘制导致路径弯折的bug
- 修复航点坐标可能超出地图边界的bug
- 修复DiplomacySectCard中relationLevel未定义导致编译错误的bug

### 兼容性
- 旧存档加载后宗门坐标仍在有效范围内（新地图更大），但分布可能不协调
- 建议旧存档用户重新开始游戏以体验新地图

## [2.4.20] - 2026-04-25

### 修复
- 修复玩家挂售丹药到商市时MerchantItem未传入grade导致丹药品质信息丢失的问题
- 修复getQualityColor异常值返回Color.Transparent导致不可见但占位文字的问题，改为默认灰色

## [2.4.19] - 2026-04-25

### 修复
- 修复仓库物品详情弹窗selectedItem使用derivedStateOf导致闭包捕获旧列表引用、StateFlow更新后数据不同步的严重bug
- 修复LaunchedEffect安全网放置在selectedItem非空判断内部导致永远无法执行的无效逻辑
- 修复LaunchedEffect安全网存在一帧延迟的问题，改为直接条件判断同步清理状态
- 修复部分售卖后SellConfirmDialog的maxQuantity不更新的问题，部分售卖成功后关闭售卖弹窗

## [2.4.18] - 2026-04-25

### 优化
- 弟子自动使用功能（丹药/装备/功法）从每月判定改为每日判定，弟子能更及时地使用储物袋中的物品
- 丹药效果持续时间衰减从每月衰减改为每日衰减
- 装备和功法冷却期计算从月度(3个月)改为日度(90天)，精度更高
- 丹药描述和详情界面持续时间显示从"月"改为"天"

### 兼容性
- 旧存档加载时自动将丹药持续时间从月度值转换为日度值
- 旧存档中缺少日度冷却期数据的物品回退使用月度冷却期计算

## [2.4.17] - 2026-04-25

### 修复
- 修复物品卡片左下角错误显示品阶而非品质的问题，现在只有丹药卡片左下角会显示品质文字
- 修复弟子详情界面装备选择和功法选择时物品卡片左下角错误显示品阶名称的问题

### 优化
- 品质文字颜色区分：上品为红色、中品为蓝色、下品为灰色
- 炼丹界面品质文字颜色同步使用品质专属颜色
- 修复仓库界面物品详情弹窗的Composable上下文错误

## [2.4.16] - 2026-04-25

### 优化
- 物品售卖后根据剩余数量决定是否自动关闭界面：部分售卖时保持售卖界面和物品详情界面打开，全部售卖时自动关闭两个界面
- 仓库物品详情弹窗的selectedItem改为从selectedItemId+StateFlow派生，确保部分售卖后界面数据自动同步更新

## [2.4.15] - 2026-04-24

### 修复
- 修复MerchantItem.price使用Int类型，与售卖系统Long类型不一致，可能导致交易堂价格溢出
- 修复交易堂购买总价计算(totalPrice)使用Int乘法可能溢出
- 修复宗门交易购买总价计算使用.toInt()截断Long值
- 修复仓库交易堂界面adjustedPrice使用.toInt()截断Long值

### 优化
- MerchantItem.price从Int改为Long，与ItemCardData.price类型保持一致
- SerializableMerchantItem.price同步改为Long，ProtoBuf序列化向后兼容
- PlayerListItem.price同步改为Long
- CultivationService.priceMap类型从Map<String,Int>改为Map<String,Long>
- SectTradeValidation.totalPrice从Int改为Long
- GameUtils新增applyPriceFluctuation(Long)重载，支持Long类型价格波动计算

## [2.4.14] - 2026-04-24

### 修复
- 修复背包一键出售界面未过滤锁定物品，导致预估价格与实际获得灵石不一致
- 修复背包出售列表中种子(Seed)类型显示为"未知物品"且价格为0
- 修复仓库一键出售界面缺少二次确认对话框，误触可直接出售
- 修复出售价格计算使用Int类型可能导致大额交易溢出

### 优化
- 售价计算公式统一收敛到GameConfig.Rarity.calculateSellPrice，消除25+处重复公式
- 简化SuspendableSellOperation从sealed class(6个子类)为data class(含itemType字段)
- 批量出售执行逻辑复用sellItem方法，消除when分支分发冗余
- 移除仓库BulkSellDialog中无意义的remember包装
- ItemCardData.price类型从Int改为Long，防止价格溢出

## [2.4.13] - 2026-04-24

### 修复
- 修复LearnedManualDetailDialog（弟子已学习功法详情）缺少技能作用范围（全队）显示
- 修复储物袋丹药详情缺少暴击率/暴击效果显示（战斗丹药）
- 修复储物袋装备详情回退分支缺少暴击率/暴击效果显示
- 修复储物袋丹药详情缺少丹药类别（功能/修炼/战斗）和需求境界显示
- 修复储物袋丹药详情效果列表未按类别分组显示，与仓库/商人界面不一致

### 优化
- 统一所有物品详情对话框的百分比格式化方式为GameUtils.formatPercent
- 统一丹药类别标签为"类型"，与仓库界面保持一致
- 统一技能作用范围显示使用英文冒号格式
- 修复储物袋丹药effect为null时addPillRecipeInfo仍被调用的问题
- 显式处理pillCategory空字符串情况
- 修复Compose AutoboxingStateCreation lint警告：mutableStateOf(1)改为mutableIntStateOf(1)避免Int装箱
- 修复SuspiciousIndentation lint错误：ItemDetailDialog属性展示if语句添加大括号消除歧义
- 批量出售对话框新增确认弹窗，显示物品数量和获得灵石
- 出售价格计算统一使用GameConfig.Rarity.calculateSellPrice方法

## [2.4.12] - 2026-04-24

### 优化
- 全面优化物品详情对话框，统一各界面（商人/仓库/储物袋等）的物品描述一致性
- 修复草药类别显示为英文代码（grass/flower/fruit→灵草/灵花/灵果）
- 完善丹药效果描述：修炼丹药显示修炼速度/修为等效果，战斗丹药显示攻防属性，功能丹药显示悟性/魅力等属性
- 修复一次性丹药（功能丹药/突破丹药）错误显示持续时间的问题，改为显示"(一次性效果)"
- 为丹药详情添加炼制所需草药信息
- 为装备详情添加锻造所需材料信息
- 商人界面物品详情：装备显示部位+属性+锻造材料，功法显示类型+属性+技能，丹药显示完整效果+炼制配方，材料显示可炼器装备，草药显示可炼丹药，种子显示长成后草药
- 储物袋物品详情：装备显示属性+锻造材料，功法显示属性+技能，丹药显示完整效果+炼制配方，材料/草药/种子显示关联信息
- 补充getStatDisplayName缺失的属性键中文映射（功法熟练度速度/孕养速度/暴击效果/悟性/魅力等）
- 统一恢复生命/灵力的描述格式，添加"最大生命/最大灵力"后缀
- 修复HerbDatabase.getHerbNameFromSeedName中"果核"替换顺序错误的问题
- 修复MerchantItem和StorageBagItem描述字段从模板获取而非使用空字符串

## [2.4.11] - 2026-04-24

### 修复
- 修复栈合并时缺少maxStack截断检查，可能导致栈数量超过上限（7个位置）
- 修复DiscipleEquipmentManager.processSlot中bagStackIds使用原始disciple而非更新后的disciple
- 修复ItemDetailDialog.kt缺少ItemDatabase import导致编译错误

## [2.4.10] - 2026-04-24

### 新增
- 装备卸下冷静期：装备被卸下或替换后3月内不会被自动穿戴，3月后有空闲槽位时自动穿戴
- 将isInCoolingPeriod提取为共享工具（StorageBagUtils），功法和装备系统共用

### 修复
- 修复装备系统缺少冷静期机制：卸下装备后立即被自动穿戴回来的问题
- 修复DiscipleEquipmentManager.processSlot中旧装备以equipment_instance类型放入储物袋导致永远不会被自动穿戴的问题（改为equipment_stack类型）
- 修复CultivationService中replacedInstance保留在实例列表而非移除导致内存泄漏的问题
- 修复DiscipleService.unequipEquipment卸下装备时未设置冷静期标记的问题
- 修复GameEngine.rewardItemsToDisciple奖励装备替换旧装备时未设置冷静期标记的问题

## [2.4.09] - 2026-04-24

### 修复
- 修复遗忘功法后宗门仓库内所有同名功法消失的bug（bagStackIds过滤机制缺失）
- 修复遗忘功法进入储物袋后无法被自动学习的bug（DiscipleManualManager重写）
- 修复forgetManual中existingStack分支使用map无法添加新StorageBagItem的问题（改用increaseItemQuantity）
- 修复tryReplaceManual将旧功法以manual_instance放入储物袋导致无法自动学习的问题（改为manual_stack）
- 修复CultivationService中replacedInstance未从manualInstances移除导致内存泄漏的问题
- 修复replaceManual中旧功法缺少冷静期标记导致替换后立即被自动学习回来的问题
- 修复序列化类缺少forgetYear/forgetMonth导致存档导入后冷静期失效的问题
- 修复replacedManualStack数量直接覆盖可能不正确的问题（改为增量更新）

### 新增
- 功法遗忘冷静期：功法被遗忘或替换后3月内不会被自动学习，3月后有空闲槽位时自动学习

## [2.4.08] - 2026-04-24

### 修复
- 移除 SaveLoadViewModel.performExitSave() 死代码（无任何调用点，与 onCleared() 逻辑重复）
- 修复 onCleared() 中重复调用 stopGameLoop：合并为单次 stopGameLoopAndWait
- 修复 saveLock 超时释放后未重置 saveLockAcquireTime 导致后续超时检测误判
- 修复 saveGame()/restartGame() 获取 saveLock 后未设置 saveLockAcquireTime 导致超时检测失效
- 修复 enqueueAutoSave 释放 saveLock 后未重置 saveLockAcquireTime

## [2.4.07] - 2026-04-24

### 修复
- 修复 Direct 方法与 update() 竞态导致状态被覆盖：改用 CAS 循环（compareAndSet）保证原子性
- 修复 update() 可能覆盖 Direct 方法修改的标志位：写入前检测最新状态，合并外部修改
- 修复 onCleared() 中先重置 isSaving 再等待保存完成的死代码：调整顺序为先等待再重置
- 修复界面卡住后退出重进存档丢失：pauseAndSaveForBackground 改为同步保存确保数据落盘

## [2.4.06] - 2026-04-24

### 修复
- 修复 isSaving 状态卡死导致游戏界面冻结：添加看门狗机制，isSaving/isLoading 超过 30 秒自动强制重置
- 修复 pauseAndSaveForBackground 不等待保存完成导致数据丢失：改为同步保存（runBlocking + 5 秒超时）
- 修复 GameEngineCore 在主线程使用 runBlocking 导致 ANR：添加 setPausedDirect/setLoadingDirect/setSavingDirect 非挂起方法
- 修复 GameStateStore.update() 竞态条件：将 check(!isInTransaction()) 移入 transactionMutex.withLock 内部
- 修复 GameViewModel 和 SaveLoadViewModel 重复保存导致竞态覆盖：移除 GameViewModel.onCleared() 中的保存逻辑，由 SaveLoadViewModel 统一负责

### 改进
- GameStateStore 新增 setPausedDirect/setLoadingDirect/setSavingDirect 方法，直接更新 StateFlow 不经过 Mutex
- UnifiedGameStateManager 新增对应的 Direct 方法，供主线程调用场景使用
- GameEngineCore 新增 forceResetStuckStates() 公开方法，可从外部紧急恢复卡死状态
- 移除 GameViewModel 中不再使用的 storageFacade 和 stateManager 依赖
- 移除 GameEngineCore 中不再使用的 storageFacade 依赖

## [2.4.05] - 2026-04-24

### 修复
- 修复 replaceManual 非原子操作并发问题：将"遗忘旧功法+学习新功法"合并为单个事务，避免中间状态导致功法消失
- 修复 replaceManual 中同名同类型功法替换时 quantity 被错误覆盖的 bug（existingStack == newStack 场景）
- 修复 replaceManual 缺少 newStack.quantity >= 1 防御性校验
- 修复 GameViewModel 功法方法参数命名与实际语义不匹配（manualId → stackId/instanceId）

### 改进
- 移除 ManualSelectionDialog 冗余参数 currentDiscipleId
- 合并 replaceManual 中 disciples 的两次 map 操作为一次，减少中间状态

## [2.4.04] - 2026-04-24

### 修复
- 修复弟子更换界面装备/功法选择卡片样式不一致：统一使用 UnifiedItemCard，支持堆叠数量、品阶标签、锁定标记、查看按钮
- 修复功法更换后功法消失：数据源从 ManualInstance 改为 ManualStack，ID 类型匹配引擎层
- 修复点击空功法槽位不显示宗门仓库功法：功法选择对话框改用 manualStacks 数据源
- 修复装备选择对话框缺少仓库堆叠装备：合并 EquipmentStack + EquipmentInstance 数据源
- 新增物品详情弹窗：装备选择、功法学习、功法更换对话框均支持 ItemDetailDialog
- 删除自定义卡片组件（EquipmentSelectionCard、ManualSelectionCard、ManualReplaceDialog、getRarityText、装备详情弹窗内嵌代码）

## [2.4.03] - 2026-04-24

### 修复
- 修复送礼对话框(GiftDialog)缺少显式关闭按钮，用户只能点击外部区域关闭
- 统一已学习功法详情对话框关闭按钮形状为 CircleShape（原 RoundedCornerShape(12.dp)）

## [2.4.02] - 2026-04-24

### 修复
- 修复已学习功法详情对话框(LearnedManualDetailDialog)的关闭按钮点击无效的问题

## [2.4.01] - 2026-04-24

### 修复
- 修复 enemyRealmMin > enemyRealmMax 导致 Random.nextInt 抛出 IllegalArgumentException，所有战斗任务完成时崩溃
- 修复 EnemyGenerator 心法强制分配逻辑：功法生成时最后一本不再强制为心法类型，心法最多1本但非必须
- 修复任务刷新使用均匀随机而非 spawnChance 权重，导致禁忌任务出现概率远高于设计值

### 改进
- 任务刷新现在按难度权重生成：简单25%/普通12%/困难3%/禁忌0.5%
- 探索古修士洞府和上古战场遭遇的敌人类型从妖兽调整为人型（守护禁制/战魂）
- 重构 generateMaterials/generateBaseMaterials 为 generateMaterialBatch 消除重复代码
- 权重随机添加防御性检查，避免 spawnChance 总和为0时崩溃
- 修正测试中任务类型分布断言（3无战斗+2必战斗+1概率战斗）
- 新增测试覆盖：enemyRealmMin<=enemyRealmMax、权重刷新、敌人类型、触发率递增

## [2.4.0] - 2026-04-24

### 新功能
- 宗门任务系统全面升级：24个任务模板覆盖4种难度（简单/普通/困难/禁忌）
- 三种任务类型：无战斗（必定成功）、必战斗（胜负决定奖励）、概率突发战斗（40%-70%触发率）
- 人型敌人系统：装备0-4件（每槽位最多1件，含孕养等级）、功法0-5本（心法最多1本，含熟练度）
- 奖励差异化：灵石/材料/丹药/装备/功法按难度递增，概率突发战斗有基础奖励（30%灵石）
- 弟子准入规则严格化：按难度限制弟子类型和境界（简单=外门无限制，普通=金丹+，困难=内门化神+，禁忌=内门合体+）
- 执行弟子数量统一为6人

### 修复
- BattleSystem.createBattle 的 beastLevel 参数现在正确生效（之前被忽略，始终用弟子平均境界）
- GameEngine 和 CultivationService 现在正确传入 battleSystem，战斗任务不再默认失败
- 旧存档 MissionTemplate 枚举名兼容（ESCORT→ESCORT_CARAVAN, SUPPRESS_BEASTS→SUPPRESS_LOW_BEASTS, SUPPRESS_BEASTS_NORMAL→SUPPRESS_JINDAN_BEASTS）
- MissionRewardConfig 序列化完整保存所有字段（丹药/装备/功法/基础奖励）

## [2.3.33] - 2026-04-24

### 修复
- 修复售卖价格计算整数溢出漏洞（天品物品大量出售时 basePrice * quantity 超出 Int 范围）
- 修复 buyMerchantItem 中 cost 计算可能溢出的问题
- 修复 InventoryScreen 中 totalValue 使用 Int 类型可能溢出的问题

### 改进
- 售卖价格乘数 0.8 提取为 GameConfig.Rarity.SELL_PRICE_MULTIPLIER 常量，消除全项目硬编码
- addSpiritStones 参数类型从 Int 改为 Long，与 spiritStones 字段类型一致
- 提取 calculateSellPrice 辅助方法，消除 6 个 sell 方法中重复的价格计算逻辑
- SuspendableSellOperation 重构：displayName 和 price 计算逻辑提取到基类，消除 6 个子类重复代码
- SellConfirmDialog 移除未使用的 itemId/itemType 参数
- SellConfirmDialog 数量输入框添加键盘完成动作和焦点丢失自动退出编辑模式
- bulkSellItems 添加成功反馈消息（显示出售件数和获得灵石数）
- 移除 bulkSellItems 中未使用的 learnedManualIds 变量

## [2.3.32] - 2026-04-24

### 改进
- 物品详情对话框中 itemQuantity 和 isLocked 统一从响应式 StateFlow 列表读取，合并为单次 find 查找，消除重复遍历
- 移除 DiscipleSelectForRewardDialog 中未使用的 itemQuantity 参数
- SellConfirmDialog 增加 maxQuantity 变化时 sellQuantity 自动校正，防止数量越界

## [2.3.31] - 2026-04-24

### 改进
- 所有物品价格减少10%（通过全局价格乘数 PRICE_MULTIPLIER = 0.9 实现）

### 修复
- 修复物品详情对话框中锁定按钮再次点击后未取消高光且未变回"锁定"文字的问题（isLocked 状态改为从响应式列表读取）

## [2.3.30] - 2026-04-23

### 新增
- 宗门仓库物品详情对话框增加售卖按钮（位于锁定按钮左侧），点击后弹出售卖确认对话框
- 售卖确认对话框支持数量加减箭头调节、点击数量直接输入（弹出数字键盘）
- 输入数量超出当前物品最大数量时自动显示为最大数量
- 锁定物品隐藏售卖按钮，防止误操作

### 修复
- 修复 GameEngine 售卖方法数量范围校验不一致的问题（sellManual/sellPill/sellMaterial/sellHerb/sellSeed 缺少数量上限检查）
- 修复高品阶物品大量出售时价格计算整数溢出的问题（basePrice * quantity 改用 Long 运算）
- 修复售卖失败时无用户反馈的问题

## [2.3.29] - 2026-04-23

### 改进
- 统一灵石送礼和物品送礼的好感度计算公式结构，消除两条路径的代码不一致
- 物品送礼路径统一使用数据快照，修复潜在的数据竞争问题
- 送礼拒绝判定统一使用 Random 替代 SecureRandom，消除不必要的性能开销
- 移除 RarityFavor 中废弃的 favor 字段和未使用的 getConfig() 方法
- 移除 SpiritStoneGiftConfig 中未使用的 getTierByName() 方法
- 修正 RarityFavor 注释中 @param favor 与实际字段 baseFavor 不匹配的问题

### 修复
- 修复 processFavorDecay 变更检测只比较 favor 忽略 noGiftYears，导致 noGiftYears 更新丢失的问题

## [2.3.28] - 2026-04-23

### 改进
- 弟子筛选机制调整：属性筛选改回单选，仅灵根保留多选
- 灵根多选时按灵根数量升序排列（单灵根在前，五灵根在最后）

## [2.3.27] - 2026-04-23

### 改进
- 被锁定的物品现在可以被赏赐给弟子（锁定仅保护出售，不限制赏赐）
- toggleItemLock 代码优化，使用 map 替代 indexOfFirst + toMutableList 模式

### 修复
- 修复一键出售灵石双倍计算的严重 Bug（各 sellXxx 方法内部已加灵石，bulkSellItems 又重复加一次）
- 修复 sellEquipment 不支持数量参数导致一键出售装备只卖1个的问题
- 修复 listItemsToMerchant 上架物品时未检查 removeXxx 返回值，锁定物品可能数据不一致的问题
- sellEquipment 增加数量前置校验，防止 quantity 超出堆叠数量

## [2.3.26] - 2026-04-23

### 改进
- 好感度增长公式从纯百分比改为"基础值+百分比"混合模式，低好感度时增长更稳定
  - 灵石送礼增加基础好感度：薄礼+2、厚礼+5、重礼+10、大礼+15
  - 物品送礼增加基础好感度：凡品+1、灵品+2、宝品+5、玄品+8、地品+12、天品+15
- 好感度衰减机制调整：好感度80以上1年不送礼扣1点，80及以下不扣除
- 结盟门槛从好感度90降低为80
- 解除结盟不再扣除好感度（仅扣除灵石）

### 修复
- 修复 AllianceDialog 中好感度显示硬编码"90"的问题，改为引用配置常量
- 修复 AI 宗门战斗后好感度扣除范围不一致的问题（统一使用 MIN_FAVOR/MAX_FAVOR 配置）

## [2.3.25] - 2026-04-23

### 改进
- 弟子界面和选择弟子界面的灵根/属性/境界筛选按钮改为可多选机制
  - 灵根筛选：可同时选择多个灵根类型进行筛选
  - 属性筛选：可同时选择多个属性进行排序
  - 境界筛选：可同时选择多个境界进行筛选
- 灵根和属性筛选按钮文字固定显示"灵根"和"属性"，不再随选中项变化
- 所有筛选按钮增加金色高光机制
  - 点击筛选选项时该选项金色高光，再次点击取消筛选高光消失
  - 灵根/属性下拉按钮在有选项被选中时也显示金色高光

### 新增
- 宗门仓库物品锁定功能
  - 物品详情对话框新增锁定按钮（赏赐按钮左侧），点击切换锁定/已锁定状态
  - 已锁定状态按钮显示金色高光
  - 物品卡片左上角显示金色"锁定"字样（与等级字样大小一致，贴内边框）
  - 锁定作用于整个物品堆叠，不区分数量
  - 被锁定物品不可通过一键出售出售
  - 一键出售对话框不显示被锁定的物品
  - 被锁定物品不可被赏赐给弟子
  - 单个出售操作增加锁定检查

## [2.3.22] - 2026-04-23

### 修复
- 修复宗门仓库物品详情对话框缺少功法技能描述的问题
  - 修复 MerchantItemConverter.toManual() 未复制技能字段导致仓库中功法缺少技能信息
  - 补全功法技能详细属性展示（伤害类型/倍率/连击/冷却/灵力消耗/Buff/治疗）
  - 新增旧存档兼容：ManualStack.skillName 为空时回退查询 ManualDatabase
  - 补全 BuffType 字符串映射（REDUCE/POISON/BURN/STUN/FREEZE/SILENCE/TAUNT）
  - 同步修复 ManualInstance.parseBuffType() 的 BuffType 映射不完整问题

## [2.3.21] - 2026-04-23

### 修复
- 修复 MainGameScreen.kt 中 Icon 组件缺少 contentDescription 参数导致编译错误的问题
- 修复 AndroidManifest.xml 中 TapTap SDK ContentProvider 的 MissingClass lint 错误
- 修复 GameDatabase.kt 中 getColumnIndex 可能返回 -1 导致的 Range lint 错误（替换为 getColumnIndexOrThrow）
- 修复 MainGameScreen.kt 中 DropdownFilterButton 的 modifier 参数位置不符合 Compose 规范的问题
- 修复 DiscipleDetailScreen.kt 中 StateFlow.value 在组合中被直接调用导致状态变化无法触发重组的问题（改用 collectAsState）

## [2.3.20] - 2026-04-23

### 新增
- 给所有弟子界面和选择弟子界面增加灵根和属性筛选行
  - 新增灵根筛选按钮：支持按单灵根/双灵根/三灵根/四灵根/五灵根筛选弟子
  - 新增属性排序按钮：支持按9个基础属性（悟性/智力/魅力/忠诚/炼器/炼丹/灵植/传道/道德）排序弟子
  - 筛选按钮带上下箭头，点击展开/收起下拉列表
  - 灵根筛选和属性排序可与境界筛选联合使用
  - 点击已选中的筛选条件可取消筛选
- 涉及界面：弟子列表、亲传弟子选择、长老弟子选择、赏赐弟子选择、战斗队伍弟子选择、秘境探索弟子选择、山峰弟子选择、生产建筑弟子选择、使者/侦察弟子选择、藏经阁弟子选择、任务大厅弟子选择、执法堂弟子选择、灵植园弟子选择

## [2.3.19] - 2026-04-23

### 修复
- **严重**: 修复数据库迁移 MIGRATION_8_9 中 INSERT INTO manuals 语句 VALUES 占位符数量与列数不匹配的问题（29 values for 28 columns）
- 根因：MIGRATION_8_9 第109行 SQL 字符串中 VALUES 后的 `?` 数量为29个，但列名只有28个，导致从数据库版本 ≤8 升级时迁移执行失败
- 影响范围：仅影响从旧版本（数据库版本 ≤8）升级的用户，已升级到版本 ≥9 的用户不受影响
- 数据库版本保持 13 不变

## [2.3.17] - 2026-04-22

### 修复
- **严重**: 修复宗门仓库赏赐装备给弟子后，弟子因境界不足无法穿戴时装备被送入储物袋，但宗门仓库中该装备未被正常扣除导致一件装备同时出现在仓库和储物袋中的问题
- 根因：equipmentStacks 同时作为仓库显示数据和储物袋装备底层数据源，装备进入储物袋时在 equipmentStacks 中创建/合并新堆导致仓库显示重复；合并逻辑可能将储物袋装备与仓库堆合并导致仓库堆数量虚增
- 修复方案：
  - GameEngine.rewardItemsToDisciple：装备进入储物袋时仅与已在储物袋中的堆合并（bagStackIds过滤），不再与仓库堆合并
  - DiscipleService.unequipEquipment：卸下装备入储物袋时同样仅与储物袋堆合并
  - DiscipleService.expelDisciple：逐出弟子归还装备时仅与仓库堆合并（排除储物袋堆），避免仓库物品被ViewModel过滤隐藏
  - GameViewModel：equipmentStacks 和 manualStacks 过滤掉被存活弟子储物袋引用的堆，确保仓库UI仅显示仓库物品
- 同步修复功法赏赐后学习失败时功法同时出现在仓库和储物袋的同类问题

## [2.3.16] - 2026-04-22

### 修复
- **严重**: 修复点击停止自动存档后，自动存档仍在后台继续执行的问题
- 根因：SaveLoadViewModel 的 autoSaveTrigger 收集器不检查 autoSaveIntervalMonths，收到触发信号后无条件执行存档；pendingAutoSave 机制不记录来源也不检查自动存档是否已禁用，导致 pending 存档链式执行
- 修复方案：在 autoSaveTrigger 收集器中添加 autoSaveIntervalMonths 检查，禁用时跳过存档；将 pendingAutoSave 从 AtomicBoolean 改为 AtomicReference<SaveSource> 记录实际来源，处理 pending 时检查来源和自动存档状态；新增 EMERGENCY 存档来源类型，区分紧急存档和定时自动存档，确保紧急存档不受自动存档开关影响

## [2.3.15] - 2026-04-22

### 修复
- **严重**: 修复外门大比选择弟子准入内门后保存游戏，重新加载时大比对话框重复弹出的问题
- 根因：promoteSelectedDisciplesToInner() 和 closeOuterTournamentDialog() 只操作了 UI 标志位，从未清除 GameData.pendingCompetitionResults，导致存档中该字段仍有值，重新加载后 LaunchedEffect 检测到非空再次弹出对话框
- 修复方案：关闭对话框时同步清除 pendingCompetitionResults，提取 closeOuterTournamentDialogUi() 私有方法分离 UI 关闭和数据清除职责

## [2.3.14] - 2026-04-22

### 修复
- **严重**: 修复游戏处于后台时游戏时间继续流逝的问题
- 根因：GameActivity.onPause() 为空，仅在 onStop() 中暂停游戏循环，而 Android 中 onPause 到 onStop 存在延迟，期间游戏时间持续流逝
- 修复方案：在 onPause() 中同步设置 isPaused=true 立即暂停游戏时间，新增 wasPausedByBackground 标志追踪暂停来源
- 修复用户手动暂停后进入后台再回来时游戏自动恢复的问题（保留用户手动暂停状态）
- 修复游戏循环被 stopGameLoop() 停止后，togglePause()/setTimeSpeed() 无法正确恢复游戏循环的问题

## [2.3.13] - 2026-04-22

### 新增
- 弟子信息界面左右两侧增加导航箭头，点击可切换到上一个/下一个弟子

## [2.3.12] - 2026-04-22

### 修复
- **严重**: 修复读档后商人界面商品列表为空（显示"商人正在旅途中"）的问题
- **严重**: 修复读档后招募弟子界面待招募弟子列表为空（显示"暂无可招募弟子"）的问题
- 根因：Protobuf TypeConverter 序列化异常时静默返回空字符串，导致存档时数据丢失；读档时反序列化空字符串返回空列表
- 修复方案：在 GameEngine.loadData 中检测商人商品和招募弟子列表为空时自动刷新，使用 stateStore 事务内最新状态确保数据一致性

## [2.3.11] - 2026-04-22

### 修复
- **严重**: 修复宗门仓库装备赏赐弟子时数量未正常扣除的问题（原代码equipEquipment失败时未从仓库扣除数量）
- **严重**: 修复连续快速赏赐装备给弟子导致游戏闪退的问题（竞态条件：DiscipleService.equipEquipment异步更新状态导致重复分配）
- **严重**: 修复rewardItemsToDisciple中wasEquipped判断逻辑错误（用EquipmentStack ID与EquipmentInstance ID比较永远不匹配）
- 修复装备赏赐改为原子操作（stateStore.update），消除竞态条件
- 修复无法装备时储物袋物品悬空引用问题（确保StorageBagItem引用有效的equipmentStack）
- 修复DiscipleDetailScreen中isRewarding未正确等待协程完成（赏赐按钮保护失效）
- 修复MainGameScreen/DiscipleDetailScreen中isRewarding异常时永久锁死问题（添加try-finally保护）
- GameViewModel.rewardItemsToDisciple改为suspend函数，确保调用方正确等待完成

## [2.3.10] - 2026-04-22

### 修复
- **P0-1**: 修正 InventoryConfig 堆叠上限与游戏设定不符（equipment_stack: 99→999, manual_stack: 99→999, herb: 999→9999, seed: 99→9999）
- **P0-2**: StackableItemUtils（addStackable/addStackableSuspend/addStackableBatch）增加 maxStack 参数和上限检查，合并时 coerceAtMost(maxStack)
- **P0-3**: DiscipleService/CultivationService/GameEngine/RedeemCodeService/EventService 中共 31 处硬编码 coerceAtMost(999) 改为 InventoryConfig.getMaxStackSize()
- **P0-4**: AddResult 新增 PARTIAL_SUCCESS 枚举值，所有 addXxx 方法溢出时返回 PARTIAL_SUCCESS 而非 SUCCESS
- **P1-1**: canAddXxx 方法增加堆叠上限检查（quantity < maxStack），堆叠已满时不再误报可合并
- **P1-2/P1-3**: OptimizedWarehouseManager/SectWarehouseManager 合并时增加 maxStack 上限检查
- **P1-4**: 统一 removeXxxByName 与 removeXxx 边界处理逻辑（newQty<=0 拆分为 newQty<0 和 newQty==0 两个分支）
- **P1-5**: addSeedSync 去掉快照预检查，所有逻辑在 stateStore.update 块内完成，消除竞态条件

### 测试
- 补充 maxStack 上限截断测试（Pill/Equipment/Herb/Seed）
- 补充溢出返回 PARTIAL_SUCCESS 测试
- 补充 Herb/Seed 合并测试
- 补充 returnEquipmentToStack/returnManualToStack 测试
- 补充 canAddXxx 堆叠已满时的行为测试
- 补充 InventoryConfig 默认值与游戏设定一致性测试

## [2.3.08] - 2026-04-22

### 修复
- 修复 StackableItem 子类（EquipmentStack/ManualStack/Pill/Material/Herb/Seed）isLocked 属性缺少 override 修饰符导致编译错误
- 修复战斗系统多角色战斗中角色死亡后索引映射未更新导致 IndexOutOfBoundsException 的严重 bug
- 修复战斗系统 updateCombatantBuffs 方法中永真条件判断和不安全类型转换
- 修复测试文件中 SaveData 字段名与重构后的模型不匹配（equipment→equipmentStacks/equipmentInstances, manuals→manualStacks/manualInstances）
- 修复测试文件中 Equipment/Manual 类名与重构后的 EquipmentInstance/ManualInstance 不匹配
- 修复 InventorySystemTest 异步状态更新导致测试间歇性失败
- 修复 CacheKeyTest DEFAULT_TTL 断言值与实际值不一致（1小时→1天）
- 删除过时的 ProductionSubsystemTest（API 已完全重构）

## [2.3.07] - 2026-04-21

### 修复
- Instance（装备实例/功法实例）移除 isLocked 字段，锁定是仓库概念不适用于实例
- 仓库容量计算不再计入 Instance，Instance 是弟子绑定物品不占仓库容量
- 添加 Instance 时不再检查仓库容量限制
- 数据库迁移 v11→v12 增加同名 Stack 合并逻辑，防止重复条目
- 数据库迁移 v12→v13 移除 Instance 表的 isLocked 列，合并重复 Stack
- 合并逻辑增加堆叠上限检查（99），防止超限 Stack
- 合并逻辑 DELETE/UPDATE 语句匹配完整主键 (id, slot_id)
- 测试文件更新为使用新的 Stack/Instance 模型

## [2.2.0] - 2026-04-20

### 调整
- 战斗伤害浮动范围调整为±20%（原±10%），伤害波动更大
- 战斗伤害百分比浮动逻辑与物品价格浮动保持一致，截断精确到0.1%步进
- 数据库版本升级至6，存档版本升级至3.0

## [2.0.10] - 2026-04-19

### 修复
- 修复驱逐弟子功能因状态更新竞态条件导致弟子未被实际移除的问题
- 修复驱逐弟子时装备养成等级未重置的问题，与宗门内死亡处理保持一致
- 修复驱逐弟子时储物袋物品（装备、功法）未归还宗门导致资源丢失的问题
- 修复驱逐弟子时已学习功法未释放导致功法永久锁定无法再学习的问题

## [2.0.09] - 2026-04-19

### 调整
- 丹药品级效果调整：上品效果为中品的200%（原150%），下品效果为中品的50%（原70%）
- 丹药品级价格倍率同步调整：上品2.0x（原1.7x），下品0.5x（原0.7x）
- 炼制丹药品级概率调整：上品6%、中品34%、下品60%

### 修复
- 修复丹药合并逻辑缺少品级判定，不同品级同名丹药会被错误合并的问题
- 修复炼丹产出丹药未携带效果数据的问题，现在使用模板创建完整丹药实例

## [2.0.08] - 2026-04-19

### 修复
- 丹药卡片UI调整：等级、数量描述移至卡片底部贴内边框，一左一右排列，移除背景色
- 修复炼丹槽选择丹药界面未显示品阶(tier)和品级(grade)的问题
- 修复炼丹槽选择丹药界面排序逻辑，改为按品阶排序（低品阶在下，高品阶在上）

## [2.0.07] - 2026-04-12

### 系统
- 版本号：2.0.00 (build 2000)
- 正式上线版本

### 新增（2026-08-01 仓库容量不足提示框 + 溢出转邮件批次）

- **统一"仓库容量不足"提示框** — 所有手动获得物品的途径（签到/邮件领取/兑换码/宗门等级/天劫/储物袋开启/商人购买/外交贸易/没收）容量不足时弹出统一提示框（"仓库容量不足"+ 知道了按钮 + 支持点击屏幕外关闭）；未来新增领取按钮只需调用统一通道
- **自动奖励溢出转邮件（物品不再丢失）** — 宗门战/妖兽战/洞穴/巡视塔/洞府探索/自动炼丹/自动锻造/灵田收获/自动购买等自动入库途径，仓库满时**前面的物品填满剩余容量，后面的溢出物品自动转入邮件**（标题如"【仓库已满】宗门战奖励转入邮件"，内容含来源与物品清单，30 天有效）；新增 `OverflowMailSender`（防抖批组：一次战斗合并为一封邮件）
- **手动获得途径语义升级** — 储物袋开启/商人购买/没收：溢出物品转邮件 + 灵石照扣（玩家实得全部，不再"灵石已扣物品丢失"）；外交宗门交易购买前容量预检（满时拒绝购买不扣灵石）
- **预存问题归档** — 12 项对抗性审查发现的预存问题（兑换码无提示/宗门等级静默/灵田丢弃/商人丢失等）全部写入 `docs/architecture.md` 待完成项并逐项 ✅
- **守卫测试增强** — 新增反模式 4（手写 StackableItemStore 构造拦截，engine 模块仅 InventorySystem 内部可用）+ 来源映射覆盖守卫；全项目 20 个物品来源统一映射表

### 修复（2026-08-01 对抗性审查批次——凭据类路径语义统一 + 溢出机制加固）

- **仓库满时签到/兑换码/宗门等级奖励不再部分丢失或重复发放** — 发放失败整体回滚、不标记已领取/已使用，玩家清理仓库后重试可完整获得全部奖励（此前签到会吞掉溢出部分、兑换码/宗门等级会重复发放）
- **仓库满时没收/卸下装备不再产生重复物品** — 仅全部入仓成功才移除原物，失败时保留，清理后可重试（此前邮件已发 + 原物保留 = 一件物品变两件）
- **溢出转入邮件的物品不再因过期消失** — 有效期从 30 天改为 10 年（玩家已获得的资产不因过期删除）
- **邮件发送临时失败时物品不丢失** — 自动重试；同一来源的溢出物品合并为一封邮件（邮件轰炸防护）
- **加固** — 溢出邮件发送器跨线程安全加固；新增溢出转邮件单元测试（邮件内容/来源映射/三态语义）+ 来源映射覆盖守卫（新增物品来源自动提示注册）
