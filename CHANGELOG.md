# 模拟宗门 - 更新日志

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
