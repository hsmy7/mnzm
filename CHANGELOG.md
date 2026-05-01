# 模拟宗门 - 更新日志

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
