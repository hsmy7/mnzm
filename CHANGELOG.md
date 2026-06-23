# 模拟宗门 - 更新日志

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
