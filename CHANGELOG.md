# 模拟宗门 - 更新日志

## [2.3.23] - 2026-04-23

### 改进
- 弟子筛选组件从单选改为多选机制，支持同时选择多个筛选条件
  - 灵根筛选：从单选 Int? 改为多选 Set<Int>，可同时选择多个灵根类型
  - 属性筛选：从单选 String? 改为多选 Set<String>，可同时选择多个属性排序
  - 境界筛选：从单选 Int? 改为多选 Set<Int>，可同时选择多个境界
- 选中状态增加金色高光效果
  - 选中背景：GameColors.Gold.copy(alpha = 0.3f)
  - 选中边框：GameColors.Gold
  - 选中文字：GameColors.GoldDark
- 涉及界面：山峰弟子选择、执法堂弟子选择、任务大厅弟子选择、藏经阁弟子选择、联盟游说/探查弟子选择、灵矿场弟子选择

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
