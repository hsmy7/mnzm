package com.xianxia.sect.core.nativebridge

/**
 * ActionIds — 业务操作码（与 C++ action_ids.h 由 gen-action-ids.mjs 同源生成）。
 * 禁止手改：修改清单后运行 `node scripts/gen-action-ids.mjs`。
 */
object ActionIds {
    /** 灵石增加（事务内） */
    const val WALLET_ADD: Int = 1000

    /** 灵石扣除（含自动售卖补差价） */
    const val WALLET_DEDUCT: Int = 1001

    /** 灵石批量变更（原子） */
    const val WALLET_BATCH: Int = 1002

    /** 灵石余额查询（按品阶） */
    const val WALLET_BALANCE: Int = 1003

    /** 全部品阶按售卖价总价值 */
    const val WALLET_TOTAL_SELL_VALUE: Int = 1004

    /** 添加装备堆叠（合并+溢出转邮件） */
    const val INV_ADD_EQUIPMENT_STACK: Int = 1010

    /** 添加功法堆叠 */
    const val INV_ADD_MANUAL_STACK: Int = 1012

    /** 添加丹药（按品阶合并） */
    const val INV_ADD_PILL: Int = 1014

    /** 添加材料 */
    const val INV_ADD_MATERIAL: Int = 1015

    /** 添加草药 */
    const val INV_ADD_HERB: Int = 1016

    /** 添加种子 */
    const val INV_ADD_SEED: Int = 1017

    /** 添加储物袋 */
    const val INV_ADD_STORAGE_BAG: Int = 1018

    /** 移除装备（按 id+数量） */
    const val INV_REMOVE_EQUIPMENT: Int = 1019

    /** 移除功法 */
    const val INV_REMOVE_MANUAL: Int = 1020

    /** 移除丹药 */
    const val INV_REMOVE_PILL: Int = 1021

    /** 移除材料 */
    const val INV_REMOVE_MATERIAL: Int = 1022

    /** 移除草药 */
    const val INV_REMOVE_HERB: Int = 1023

    /** 移除种子 */
    const val INV_REMOVE_SEED: Int = 1024

    /** 仓库是否有空余槽位 */
    const val INV_CAN_ADD_ITEM: Int = 1025

    /** 仓库容量信息 */
    const val INV_CAPACITY_INFO: Int = 1026

    /** 仓库堆叠合并（批 8-3） */
    const val INV_CONSOLIDATE: Int = 1027

    /** 仓库整理=合并+排序（含实例轨道） */
    const val INV_SORT: Int = 1028

    /** 堆叠锁定翻转（按 id+类型） */
    const val INV_TOGGLE_LOCK: Int = 1029

    /** 灵田月度收获（成熟判定+续种+年度报告） */
    const val SPIRIT_FIELD_HARVEST: Int = 1030

    /** 弟子基础属性乘区法计算 */
    const val DISCIPLE_BASE_STATS: Int = 1100

    /** 每旬修炼速度（乘区法） */
    const val DISCIPLE_CULTIVATION_PER_PHASE: Int = 1101

    /** 突破概率（乘区法） */
    const val DISCIPLE_BREAKTHROUGH_CHANCE: Int = 1102

    /** 弟子最大寿元 */
    const val DISCIPLE_MAX_AGE: Int = 1103

    /** 修炼检查点同步 */
    const val DISCIPLE_CHECKPOINT: Int = 1104

    /** 每旬修炼累积（钳制上限） */
    const val DISCIPLE_ACCUMULATE_CULTIVATION: Int = 1105

    /** 弟子老化（年龄+1/5岁回正/寿元判定） */
    const val DISCIPLE_AGE: Int = 1106

    /** 突破执行（连续突破循环） */
    const val DISCIPLE_BREAKTHROUGH: Int = 1107

    /** 突破完成月份预估 */
    const val DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH: Int = 1108

    /** 乘区法最终伤害计算 */
    const val BATTLE_FINAL_DAMAGE: Int = 1200

    /** 境界压制三因子 */
    const val BATTLE_REALM_GAP_FACTORS: Int = 1201

    /** 跨境界斩杀判定 */
    const val BATTLE_CHECK_INSTANT_KILL: Int = 1202

    /** 闪避概率 */
    const val BATTLE_DODGE_CHANCE: Int = 1203

    /** 护盾吸收 */
    const val BATTLE_SHIELD_ABSORPTION: Int = 1204

    /** DoT 持续伤害结算 */
    const val BATTLE_DOT: Int = 1205

    /** 技能冷却更新 */
    const val BATTLE_COOLDOWN_UPDATE: Int = 1206

    /** 政策月度成本（三模式扣除） */
    const val GOV_POLICY_COSTS: Int = 1300

    /** 政策月度忠诚/道德效果 */
    const val GOV_POLICY_MONTHLY_EFFECTS: Int = 1301

    /** 灵矿月度产出（时间戳差分） */
    const val GOV_SPIRIT_MINE_MONTHLY: Int = 1302

    /** 年度年俸发放 */
    const val GOV_ANNUAL_SALARY: Int = 1303

    /** 乘区法通用计算 */
    const val GOV_ZONE_CALCULATE: Int = 1304

    /** 世界关卡月度处理（清理+刷新+移动） */
    const val WORLD_LEVEL_MONTHLY: Int = 1400

    /** 关卡过期判定 */
    const val WORLD_LEVEL_CHECK_EXPIRED: Int = 1401

    /** 按年份加权随机选取妖兽境界 */
    const val LEVEL_SELECT_BEAST_REALM: Int = 1402

    /** 生成世界关卡（妖兽/洞府，含属性预生成） */
    const val LEVEL_GENERATE_LEVELS: Int = 1403

    /** 标记弟子死亡（isAlive/status/deathYears + 年死亡计数 + 装备断言） */
    const val DISCIPLE_MARK_DEAD: Int = 1404

    /** 列表 copy 模式补写 deathYears（replaceAll 清空后恢复） */
    const val DISCIPLE_BACKFILL_DEATH_YEARS: Int = 1405

    /** 存活成员平均境界（全灭取上限） */
    const val SECRET_REALM_PLAYER_AVG_REALM: Int = 1406

    /** 秘境妖兽境界随机 [avg-1, avg+2] clamp 0..9 */
    const val SECRET_REALM_ROLL_BEAST_REALM: Int = 1407

    /** 生成遭遇妖兽事件（类型/境界/层数/数量） */
    const val SECRET_REALM_GENERATE_BEAST_EVENT: Int = 1408

    /** 方向选择后下一事件（一次 nextDouble 分段判定） */
    const val SECRET_REALM_ROLL_NEXT_EVENT: Int = 1409

    /** 妖兽最终属性预生成（层数倍率+随机方差+偷袭减血） */
    const val SECRET_REALM_BUILD_BEAST_STATS: Int = 1410

    /** 妖兽战斗胜利掉落（加权选取，每只 2 材料） */
    const val SECRET_REALM_ROLL_BEAST_LOOT: Int = 1411

    /** 遗迹秘宝描述符生成（候选模板列表参数化） */
    const val SECRET_REALM_GENERATE_RUINS_TREASURE: Int = 1412

    /** 遗迹探索结算（离开/搜寻判定+结果文本+方向事件） */
    const val SECRET_REALM_RESOLVE_RUINS: Int = 1413

    /** 战斗失败丢失背包物品（比例+洗牌选取） */
    const val SECRET_REALM_LOOT_LOSS: Int = 1414

    /** AI 宗门探索队伍派遣（存活境界最高 4 名） */
    const val SECRET_REALM_AI_DISPATCH: Int = 1415

    /** 秘境空闲位置寻找（避宗门随机+兜底最远扫描） */
    const val SECRET_REALM_FIND_POSITION: Int = 1416

    /** 选择选项后体力计算（非法消耗 clamp 防篡改） */
    const val SECRET_REALM_STAMINA: Int = 1417

    /** 年变现世冷却判据（负冷却 clamp 防篡改） */
    const val SECRET_REALM_YEARLY_SPAWN_CHECK: Int = 1418

    /** 秘境精灵变体随机（1×nextInt） */
    const val SECRET_REALM_ROLL_SPRITE: Int = 1419

    /** AI 四因素加权判定概率（攻击/结盟/附属） */
    const val SECT_DECISION_CHANCE: Int = 1420

    /** 附属脱离概率（战力/丢失/胜负/好感度反向） */
    const val SECT_DECISION_BREAKAWAY: Int = 1421

    /** 弟子战力（永久基础属性公式） */
    const val SECT_POWER_DISCIPLE: Int = 1422

    /** 妖兽战力（同公式 + 防篡改 clamp） */
    const val SECT_POWER_BEAST: Int = 1423

    /** 永久基础属性缓存指纹（Java hashCode） */
    const val SECT_POWER_FINGERPRINT: Int = 1424

    /** 品阶时间曲线抽样（1×nextDouble） */
    const val SECT_RARITY_ROLL: Int = 1425

    /** 年份可出最高品阶 */
    const val SECT_RARITY_MAX: Int = 1426

    /** 年份保底品阶（下一分段） */
    const val SECT_RARITY_PITY: Int = 1427

    /** 年份品阶权重表（归一化） */
    const val SECT_RARITY_WEIGHTS: Int = 1428

    /** 宗门交易确定性种子（sectId.hashCode + year） */
    const val SECT_TRADE_SEED: Int = 1429

    /** 商品库存量抽样（消耗品/耐用品两档曲线） */
    const val SECT_TRADE_STOCK: Int = 1430

    /** 商品价格波动（±20% 一位小数截断） */
    const val SECT_TRADE_PRICE: Int = 1431

    /** 灵石商品映射（上品/中品 + 年份上限判定） */
    const val SECT_TRADE_SPIRIT_STONE: Int = 1432

    /** 清除弟子全部槽位引用（11 类槽位纯数据变换） */
    const val SLOT_CLEAR_ALL: Int = 1433

    /** 兑换码格式校验（trim/长度/字符集） */
    const val REDEEM_VALIDATE_INPUT: Int = 1434

    /** 灵根类型解析（配置/数量随机/权重生成 + java.util.Random 洗牌） */
    const val REDEEM_ROLL_SPIRIT_ROOT: Int = 1435

    /** 年龄区间 + 境界寿元 ±10% 波动 */
    const val REDEEM_RESOLVE_AGE_LIFESPAN: Int = 1436

    /** 灵根阶梯属性掷点 + 避开哨兵 50 */
    const val REDEEM_ROLL_SKILLS: Int = 1437

    /** 属性方差生成（-50..50） */
    const val REDEEM_GENERATE_VARIANCE: Int = 1438

    /** 邮件附件列表 → JSON 字符串（kotlinx 对齐） */
    const val MAIL_ATTACHMENT_ENCODE: Int = 1439

    /** 出发探索（校验+会话写入+初始妖兽事件） */
    const val SECRET_REALM_START: Int = 1440

    /** 选择选项（结算+战斗+下一事件+会话结束） */
    const val SECRET_REALM_CHOOSE: Int = 1441

    /** 结束探索（背包结算入仓+秘境清场） */
    const val SECRET_REALM_END: Int = 1442

    /** 手动排班事务（配方校验+材料检查消耗+槽位WORKING） */
    const val PRODUCTION_START: Int = 1443

    /** 手动重置事务（槽位回IDLE全清空） */
    const val PRODUCTION_RESET: Int = 1444

    /** 建筑放置事务（等级/环界门楼/限建/占位/灵石校验+建筑写入） */
    const val BUILDING_PLACE: Int = 1450

    /** 建筑迁移事务（存在性/环界门楼校验+坐标改写） */
    const val BUILDING_MOVE: Int = 1451

    /** 建筑升级事务（等级/差价/canFit校验+原地变换） */
    const val BUILDING_UPGRADE: Int = 1452

    /** 建筑批量拆除事务（存在性/幽灵防御+灵石返还） */
    const val BUILDING_REMOVE: Int = 1453

    /** 建筑批量升级事务（整批等级/候选稳定序/可负担上限/增量canFit） */
    const val BUILDING_UPGRADE_BATCH: Int = 1454

    /** 道路放置事务（占位校验+扣灵石+邻域掩码重算） */
    const val ROAD_PLACE: Int = 1470

    /** 道路拆除事务（存在性校验+邻域掩码重算） */
    const val ROAD_REMOVE: Int = 1471

    /** 装备穿戴事务（校验链+堆叠扣减/实例铸造+槽位列写） */
    const val DISCIPLE_TX_EQUIP: Int = 1480

    /** 装备卸下事务（实例入袋+实例表移除+槽位列清） */
    const val DISCIPLE_TX_UNEQUIP: Int = 1481

    /** 功法学习事务（资格守卫+堆叠消耗+实例铸造+HP/MP增量） */
    const val DISCIPLE_TX_LEARN_MANUAL: Int = 1482

    /** 功法卸下事务（实例入袋+manualIds/熟练度清理） */
    const val DISCIPLE_TX_UNLEARN_MANUAL: Int = 1483

    /** 任命事务（clearAllSlots+亲传/藏经阁槽覆写） */
    const val DISCIPLE_TX_ASSIGN_SLOT: Int = 1484

    /** 卸任事务（亲传/藏经阁单槽重置） */
    const val DISCIPLE_TX_UNASSIGN_SLOT: Int = 1485

    /** 外交事务（结盟请求/解除结盟——仅结盟消费 SYSTEM 1×nextDouble） */
    const val DIPLOMACY_TX: Int = 1500

    /** 赠礼事务（校验链+拒绝 roll SYSTEM 1×nextInt(100)+好感写入+扣费） */
    const val FAVOR_GIFT: Int = 1501

    /** 附庸事务（附属请求/解除——仅请求消费 SYSTEM 1×nextDouble） */
    const val VASSAL_TX: Int = 1502

    /** 单类出售事务（存在/锁定/数量守卫+售价入账+堆叠扣减） */
    const val INV_SELL_ITEM: Int = 1520

    /** 批量出售事务（逐条扣减零入账+末尾一次 Sell(bulk) 入账） */
    const val INV_BULK_SELL: Int = 1521

    /** 商人收购事务（非法参数拒绝+仓库实售量+扣仓入账+收购项回写） */
    const val MERCHANT_SELL_ACQUISITION: Int = 1522

    /** 玩家上架事务（装备/功法/丹药三段首命中登记，不扣仓库） */
    const val MERCHANT_LIST_ITEMS: Int = 1523

    /** 撤下上架项事务（playerListedItems 按 id 移除） */
    const val MERCHANT_REMOVE_LISTED: Int = 1524

    /** 按名称+品阶消耗材料（未锁定按列表序扣减，快照语义） */
    const val INV_CONSUME_MATERIAL: Int = 1525

    /** 商人购买事务（判定序+按型槽位预算容量预测+先加后扣+商家库存扣减；模板缺失failure信封回退） */
    const val INV_BUY_MERCHANT_ITEM: Int = 1530

    /** 充公事务（幂等探测+三态物化+溢出抑制+仅Success移除袋条目） */
    const val INV_CONFISCATE_BAG_ITEM: Int = 1531

    /** 世界关卡战斗事务（校验链+组装 pregens/基础值双分支+BATTLE 分区执行+伤亡写回，回传终态/幸存/阵亡） */
    const val EXPLORE_TX_ATTACK_WORLD_LEVEL: Int = 1570

    /** 宗门侦察事务（AI 守卫选取 alive∧7..9 取 8+PvP 组装满血 ATTACKER+BATTLE 分区执行+伤亡写回，回传守卫展示段） */
    const val EXPLORE_TX_SCOUT_SECT: Int = 1571

    /** 分舵驻守分配（存在/存活校验+同宗已驻静默跳过+旧occupant捕获+全槽清理+槽位字段写，零 RNG） */
    const val EXPLORE_TX_ASSIGN_GARRISON: Int = 1572

    /** 分舵驻守移除（occupant 捕获+槽位清空保留索引，零 RNG） */
    const val EXPLORE_TX_REMOVE_GARRISON: Int = 1573

    /** 逐出事务（存在/存活/非血炼校验+12类槽位清理含住所+实例销毁+派生map收口+行删除；袋物品信封回传Kotlin物化） */
    const val DISCIPLE_LIFECYCLE_EXPEL: Int = 1590

    /** 拜师事务（三相校验+masterIds落表；双侧日志草稿回写lifeEvents） */
    const val DISCIPLE_LIFECYCLE_APPRENTICE: Int = 1591

    /** 婚姻批准事务（已有道侣防御+partnerIds双向绑定+MARRIAGE事件直写；提议移除留Kotlin） */
    const val DISCIPLE_LIFECYCLE_MARRY_APPROVE: Int = 1592

    /** 释放思过事务（statusData思过双键定向移除+状态回IDLE；静默no-op同义） */
    const val DISCIPLE_LIFECYCLE_RELEASE_REFLECTION: Int = 1593

    /** 境界年俸开关事务（yearlySalaryEnabled[realm]覆写，无校验） */
    const val DISCIPLE_LIFECYCLE_SALARY_TOGGLE: Int = 1594

    /** 长老单值槽任命（存在/存活校验+全槽清理+槽位字段写+亲传列表清空，回传被顶替者） */
    const val ELDER_APPOINT_TX: Int = 1610

    /** 长老单值槽卸任（槽位字段清空+亲传列表清空，回传被卸任者） */
    const val ELDER_DISMISS_TX: Int = 1611

    /** 仓库驻守分配（存在/存活校验+旧occupant捕获+全槽清理+条目替换） */
    const val WAREHOUSE_GARRISON_TX: Int = 1612

    /** 洗炼灵根（先扣玉符后抽取：保底/双灵根判定+元素洗牌，SYSTEM 分区） */
    const val SPIRIT_ROOT_WASH_TX: Int = 1613

    /** 新增特质刷新（上限/候选预检+扣玉符+品阶抽取+pending 落盘，SYSTEM 分区） */
    const val TRAIT_ADD_ROLL_TX: Int = 1614

    /** 新增特质确认（上限/合法性校验+追加+lifespan 同步+checkpoint+清 pending，零 RNG） */
    const val TRAIT_ADD_CONFIRM_TX: Int = 1615

    /** 特质单槽洗炼（目标校验+排除集+扣玉符+保底/品阶抽取，SYSTEM 分区） */
    const val TRAIT_WASH_SLOT_TX: Int = 1616

    /** 招募列表移除条目（按 id 过滤幂等，零 RNG） */
    const val RECRUIT_REMOVE_TX: Int = 1630

    /** 年度招募列表刷新（差值门+宗门等级/长老魅力加成+广纳门徒+候选生成，SYSTEM 分区） */
    const val RECRUIT_REFRESH_TX: Int = 1631

    /** 招募列表老化+净化（age+1/超寿元移除/损坏过滤/三级去重/跨表残留，零 RNG） */
    const val RECRUIT_AGE_TX: Int = 1632

    /** 生产槽弟子任命（槽位存在校验+全槽位清理+目标槽写+他槽清空，零 RNG） */
    const val PROD_UI_ASSIGN_SLOT: Int = 1650

    /** 生产槽弟子卸任（占用捕获+WORKING 剩余时长归一+槽位清空，零 RNG） */
    const val PROD_UI_REMOVE_SLOT: Int = 1651

    /** 自动续炼开关翻转（槽位存在校验+镜像字段翻转，零 RNG） */
    const val PROD_UI_TOGGLE_AUTO_RESTART: Int = 1652

    /** 生产槽惰性建槽/镜像槽维护（按 buildingId+slotIndex 覆写或追加，零 RNG） */
    const val PROD_UI_ADD_SLOT: Int = 1653

    /** 灵田单块播种（种子存在/未锁定/余量>0+空地匹配+同事务扣种，零 RNG） */
    const val SPIRIT_FIELD_PLANT_ONE: Int = 1654

    /** 灵田批量播种（余量约束上限+地块镜像序播种+按实际播种数扣种，零 RNG） */
    const val SPIRIT_FIELD_PLANT_BATCH: Int = 1655

    /** 灵田单块移除（按实例首命中清空种植字段，零 RNG） */
    const val SPIRIT_FIELD_REMOVE_ONE: Int = 1656

    /** 灵田批量移除（按实例集合清空种植字段，零 RNG） */
    const val SPIRIT_FIELD_REMOVE_BATCH: Int = 1657

    /** 引导计数递增（键+增量，缺省 0 起算，零 RNG） */
    const val BOUNDARY_GUIDE_COUNTER_INCREMENT_TX: Int = 1670

    /** 自动分配策略+引导计数合并写（策略整包替换+三激活计数增量，零 RNG） */
    const val BOUNDARY_AUTO_ASSIGN_GUIDE_TX: Int = 1671

    /** 建筑建造计数回填（按显示名 max 语义幂等，零 RNG） */
    const val BOUNDARY_BUILDING_GUIDE_BACKFILL_TX: Int = 1672

    /** 政策开关（可负担校验+首月扣费+置位+激活计数+修炼 checkpoint，零 RNG） */
    const val GOV_POLICY_TOGGLE_TX: Int = 1680

    /** 广纳门徒开关（固定费用+付费月戳+激活计数，零 RNG） */
    const val GOV_OPEN_RECRUITMENT_TOGGLE_TX: Int = 1681

    /** 灵矿增产开关（免费+激活计数+灵矿结算月戳，零 RNG） */
    const val GOV_SPIRIT_MINE_BOOST_TOGGLE_TX: Int = 1682

    /** 宗门升级写回（存在性/目标等级校验+玩家宗门 level/levelName 改写，零 RNG） */
    const val SECT_LEVEL_UPGRADE_TX: Int = 1690

    /** 宗门等级奖励领取落账（7 天冷却校验+材料/储物袋/灵石入账+领取记录 upsert，凭据类溢出抑制，零 RNG） */
    const val SECT_LEVEL_CLAIM_TX: Int = 1691

    /** 玉符购买商人刷新落账（上限校验先于扣款+玉符扣减+刷新次数累加钳制，零 RNG） */
    const val JADE_PURCHASE_MERCHANT_REFRESH_TX: Int = 1692

    /** 玉符购买突破率加成落账（弟子存在/存活/上限校验先于扣款+玉符扣减+statusData 写回，零 RNG） */
    const val JADE_PURCHASE_BREAKTHROUGH_BONUS_TX: Int = 1693

    /** 读档恢复会话域判定（到期关闭 closeSecretRealmByExpiry 状态段/死局 endSession 重置/成员净化写回，零 RNG） */
    const val SECRET_REALM_CONTINUE_TX: Int = 1710

    /** 住所分配（释放原 occupant + 跨住所搬迁清旧槽 + name 写回，零 RNG） */
    const val PATROL_ASSIGN_RESIDENCE: Int = 1550

    /** 住所移除（空槽无操作，零 RNG） */
    const val PATROL_REMOVE_RESIDENCE: Int = 1551

    /** 巡逻分配（释放原 occupant 保留 buildingInstanceId + 清新弟子其它槽位 + 展示字段重建，零 RNG） */
    const val PATROL_ASSIGN: Int = 1552

    /** 巡逻移除（空槽无操作；index/buildingInstanceId 保留，零 RNG） */
    const val PATROL_REMOVE: Int = 1553

    /** 巡逻交换（同索引无操作；一方为空即移动；两侧 buildingInstanceId 各自保留，零 RNG） */
    const val PATROL_SWAP: Int = 1554

    /** 批量自动分配（前置校验重复槽/同弟子多槽 + 锁内全量预检 + releasedIds/confirmedIds 回执，零 RNG） */
    const val PATROL_AUTO_ASSIGN: Int = 1555

    /** 巡视配置覆写（补足到 towerIndex 的 PatrolConfig 默认填位 + 就地覆写，零 RNG） */
    const val PATROL_UPDATE_CONFIG: Int = 1556

    /** 矿场槽位整表覆写，零 RNG */
    const val PATROL_UPDATE_SPIRIT_MINE_SLOTS: Int = 1557

    /** 矿场槽位自愈（按灵矿场建筑重建 3 槽：孤儿引用清空 + index 重排 + buildingInstanceId 重锚 + sectId 对齐，零 RNG） */
    const val PATROL_FIX_SPIRIT_MINE: Int = 1558

    /** 年俸覆写，零 RNG */
    const val PATROL_UPDATE_YEARLY_SALARY: Int = 1559

    /** AI 阵亡守军清理（目标池过滤 + 目标宗门驻军槽清空保留索引，零 RNG） */
    const val SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX: Int = 1711

    /** 胜方存活玩家弟子魂魄 +1（行序 + 存活性过滤，零 RNG） */
    const val SECT_ATTACK_GRANT_SOUL_POWERS_TX: Int = 1712

    /** 妖兽视图锁定/解锁（Set 语义幂等 + 保序剔除 + lockedCount 回执，零 RNG） */
    const val BEAST_VIEW_LOCK_TX: Int = 1730

    /** 设置项字段补丁（17 字段通用：bool 开关 + Int 集，未知字段失败零写入，零 RNG） */
    const val SETTINGS_PATCH_TX: Int = 1731

    /** 洗炼灵根确认替换（元素串合法性 → 覆写 → checkpoint，零 RNG/零玉符） */
    const val SPIRIT_ROOT_WASH_CONFIRM_TX: Int = 1732

    /** 特质单槽确认替换（三态判定 → 替换 + lifespan 同步 + checkpoint，零 RNG/零玉符） */
    const val TRAIT_WASH_CONFIRM_TX: Int = 1733

    /** 开袋抽签（EXPLORATION 分区产出 count + kind 描述符序列，模板物化留 Kotlin） */
    const val STORAGE_BAG_OPEN_TX: Int = 1734

    /** 弟子改名事务（names行写+招募列表isSamePerson同人净化——按改名前身份） */
    const val DISCIPLE_OP_RENAME: Int = 1740

    /** 弟子类型直改事务（discipleTypes行写；状态推导由Kotlin调用方原序执行） */
    const val DISCIPLE_OP_CHANGE_TYPE: Int = 1741

    /** 弟子关注切换事务（statusData["followed"]翻转；返回followedAfter） */
    const val DISCIPLE_OP_TOGGLE_FOLLOW: Int = 1742

    /** 赏赐物品事务（pill/material/herb/seed四路合一：扣仓库+生效或入袋同一事务；pill走facade丹药链） */
    const val DISCIPLE_OP_REWARD_ITEM: Int = 1743

    /** 服药事务（canUsePill资格链+扣仓库+facade丹药链+服药日志草稿；moralityAfter回传供偷盗钩子判定） */
    const val DISCIPLE_OP_USE_PILL: Int = 1744

    /** 功法替换事务（七链校验+堆叠扣减+实例铸造+熟练度清理+旧实例入袋+替换日志草稿） */
    const val DISCIPLE_OP_REPLACE_MANUAL: Int = 1745

    /** 血炼启动原子事务（灵石/材料/排他校验链+11类槽位清理+进度写入+REFINING状态） */
    const val DISCIPLE_OP_START_BLOOD_REFINEMENT: Int = 1746

    /** 单弟子状态派生同步事务（14 flag推导+positionName定向写删——派生列唯一计算方） */
    const val DISCIPLE_OP_SYNC_STATUS: Int = 1747

    /** 全量弟子状态派生同步事务（含fixInvalidMiningSlots前置自愈） */
    const val DISCIPLE_OP_SYNC_ALL_STATUSES: Int = 1748

    /** 婚姻拒绝事务（MARRIAGE拒绝事件直写；零弟子表写入/零RNG/无失败臂；提议移除留Kotlin运行态） */
    const val DISCIPLE_LIFECYCLE_MARRY_REJECT: Int = 1750

    /** 建筑拆除/没收槽位清扫事务（十类槽位按槽组清除+长老殿末座判定+监牢/任务阁特例+REFINING破除；槽组知识由Kotlin组装传入） */
    const val BUILDING_RESIDUAL_CLEAR: Int = 1810

    /** 建筑放置槽位派生事务（SlotGroup.createSlots写段等价：八集合建槽+每塔一份PatrolConfig；生产槽id由Kotlin UUID生成传入） */
    const val BUILDING_PLACE_SLOTS: Int = 1811

    /** 玉符结算发放（settleGrants 下沉：整除发放/封顶冻结，回执回写运行时） */
    const val JADE_RUNTIME_SETTLE_TX: Int = 1766

    /** 玉符跨天重置/首锚（maybeDayReset 下沉：午夜锚点由 Kotlin 计算传入） */
    const val JADE_RUNTIME_DAY_RESET_TX: Int = 1767

    /** 玉符 checkpoint（四字段绝对值覆盖写；拿满冻结复用） */
    const val JADE_RUNTIME_CHECKPOINT_TX: Int = 1768

    /** 玉符广告发放（grantFromAd 落账段；广告 SDK 平台效应留 Kotlin） */
    const val JADE_RUNTIME_GRANT_AD_TX: Int = 1769

    /** 行商手动刷新次数年度发放（每30年+1，达上限零写入） */
    const val MERCHANT_CHANCE_GRANT_TX: Int = 1770

    /** 收购池整表覆写（items 由 Kotlin 以 SYSTEM 分区预生成） */
    const val MERCHANT_ACQUISITION_REFRESH_TX: Int = 1771

    /** 旅行商人池整表覆写 + 年份/刷新计数（保底相位 Kotlin 预计算） */
    const val MERCHANT_TRAVELING_REFRESH_TX: Int = 1772

    /** 手动刷新（chances 校验先行 + 扣凭据 + 池覆写单事务原子） */
    const val MERCHANT_MANUAL_REFRESH_TX: Int = 1773

    /** 预警阶段标记（shownWarningStageIds 追加，不去重） */
    const val DIPLOMACY_WARNING_STAGE_TX: Int = 1843

}
