// 由 scripts/gen-action-ids.mjs 生成 — 禁止手改（与 ActionIds.kt 同源）
#pragma once

// ============================================================
// ActionId 协议（业务操作码）— 与 Kotlin ActionIds.kt 同步生成
// 参数/结果一律 JSON 字节（nlohmann/json ↔ kotlinx.serialization）
// ============================================================
namespace gamecore {

/// 业务操作码（Kotlin GameCoreBridge.nativeExecute 的 actionId）
namespace action {
/// 灵石增加（事务内）
inline constexpr int32_t WALLET_ADD = 1000;

/// 灵石扣除（含自动售卖补差价）
inline constexpr int32_t WALLET_DEDUCT = 1001;

/// 灵石批量变更（原子）
inline constexpr int32_t WALLET_BATCH = 1002;

/// 灵石余额查询（按品阶）
inline constexpr int32_t WALLET_BALANCE = 1003;

/// 全部品阶按售卖价总价值
inline constexpr int32_t WALLET_TOTAL_SELL_VALUE = 1004;

/// 添加装备堆叠（合并+溢出转邮件）
inline constexpr int32_t INV_ADD_EQUIPMENT_STACK = 1010;

/// 添加装备实例
inline constexpr int32_t INV_ADD_EQUIPMENT_INSTANCE = 1011;

/// 添加功法堆叠
inline constexpr int32_t INV_ADD_MANUAL_STACK = 1012;

/// 添加功法实例
inline constexpr int32_t INV_ADD_MANUAL_INSTANCE = 1013;

/// 添加丹药（按品阶合并）
inline constexpr int32_t INV_ADD_PILL = 1014;

/// 添加材料
inline constexpr int32_t INV_ADD_MATERIAL = 1015;

/// 添加草药
inline constexpr int32_t INV_ADD_HERB = 1016;

/// 添加种子
inline constexpr int32_t INV_ADD_SEED = 1017;

/// 添加储物袋
inline constexpr int32_t INV_ADD_STORAGE_BAG = 1018;

/// 移除装备（按 id+数量）
inline constexpr int32_t INV_REMOVE_EQUIPMENT = 1019;

/// 移除功法
inline constexpr int32_t INV_REMOVE_MANUAL = 1020;

/// 移除丹药
inline constexpr int32_t INV_REMOVE_PILL = 1021;

/// 移除材料
inline constexpr int32_t INV_REMOVE_MATERIAL = 1022;

/// 移除草药
inline constexpr int32_t INV_REMOVE_HERB = 1023;

/// 移除种子
inline constexpr int32_t INV_REMOVE_SEED = 1024;

/// 仓库是否有空余槽位
inline constexpr int32_t INV_CAN_ADD_ITEM = 1025;

/// 仓库容量信息
inline constexpr int32_t INV_CAPACITY_INFO = 1026;

/// 仓库堆叠合并（批 8-3）
inline constexpr int32_t INV_CONSOLIDATE = 1027;

/// 仓库整理=合并+排序（含实例轨道）
inline constexpr int32_t INV_SORT = 1028;

/// 堆叠锁定翻转（按 id+类型）
inline constexpr int32_t INV_TOGGLE_LOCK = 1029;

/// 灵田月度收获（成熟判定+续种+年度报告）
inline constexpr int32_t SPIRIT_FIELD_HARVEST = 1030;

/// 弟子基础属性乘区法计算
inline constexpr int32_t DISCIPLE_BASE_STATS = 1100;

/// 每旬修炼速度（乘区法）
inline constexpr int32_t DISCIPLE_CULTIVATION_PER_PHASE = 1101;

/// 突破概率（乘区法）
inline constexpr int32_t DISCIPLE_BREAKTHROUGH_CHANCE = 1102;

/// 弟子最大寿元
inline constexpr int32_t DISCIPLE_MAX_AGE = 1103;

/// 修炼检查点同步
inline constexpr int32_t DISCIPLE_CHECKPOINT = 1104;

/// 每旬修炼累积（钳制上限）
inline constexpr int32_t DISCIPLE_ACCUMULATE_CULTIVATION = 1105;

/// 弟子老化（年龄+1/5岁回正/寿元判定）
inline constexpr int32_t DISCIPLE_AGE = 1106;

/// 突破执行（连续突破循环）
inline constexpr int32_t DISCIPLE_BREAKTHROUGH = 1107;

/// 突破完成月份预估
inline constexpr int32_t DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH = 1108;

/// 乘区法最终伤害计算
inline constexpr int32_t BATTLE_FINAL_DAMAGE = 1200;

/// 境界压制三因子
inline constexpr int32_t BATTLE_REALM_GAP_FACTORS = 1201;

/// 跨境界斩杀判定
inline constexpr int32_t BATTLE_CHECK_INSTANT_KILL = 1202;

/// 闪避概率
inline constexpr int32_t BATTLE_DODGE_CHANCE = 1203;

/// 护盾吸收
inline constexpr int32_t BATTLE_SHIELD_ABSORPTION = 1204;

/// DoT 持续伤害结算
inline constexpr int32_t BATTLE_DOT = 1205;

/// 技能冷却更新
inline constexpr int32_t BATTLE_COOLDOWN_UPDATE = 1206;

/// 政策月度成本（三模式扣除）
inline constexpr int32_t GOV_POLICY_COSTS = 1300;

/// 政策月度忠诚/道德效果
inline constexpr int32_t GOV_POLICY_MONTHLY_EFFECTS = 1301;

/// 灵矿月度产出（时间戳差分）
inline constexpr int32_t GOV_SPIRIT_MINE_MONTHLY = 1302;

/// 年度年俸发放
inline constexpr int32_t GOV_ANNUAL_SALARY = 1303;

/// 乘区法通用计算
inline constexpr int32_t GOV_ZONE_CALCULATE = 1304;

/// 世界关卡月度处理（清理+刷新+移动）
inline constexpr int32_t WORLD_LEVEL_MONTHLY = 1400;

/// 关卡过期判定
inline constexpr int32_t WORLD_LEVEL_CHECK_EXPIRED = 1401;

/// 按年份加权随机选取妖兽境界
inline constexpr int32_t LEVEL_SELECT_BEAST_REALM = 1402;

/// 生成世界关卡（妖兽/洞府，含属性预生成）
inline constexpr int32_t LEVEL_GENERATE_LEVELS = 1403;

/// 标记弟子死亡（isAlive/status/deathYears + 年死亡计数 + 装备断言）
inline constexpr int32_t DISCIPLE_MARK_DEAD = 1404;

/// 列表 copy 模式补写 deathYears（replaceAll 清空后恢复）
inline constexpr int32_t DISCIPLE_BACKFILL_DEATH_YEARS = 1405;

/// 存活成员平均境界（全灭取上限）
inline constexpr int32_t SECRET_REALM_PLAYER_AVG_REALM = 1406;

/// 秘境妖兽境界随机 [avg-1, avg+2] clamp 0..9
inline constexpr int32_t SECRET_REALM_ROLL_BEAST_REALM = 1407;

/// 生成遭遇妖兽事件（类型/境界/层数/数量）
inline constexpr int32_t SECRET_REALM_GENERATE_BEAST_EVENT = 1408;

/// 方向选择后下一事件（一次 nextDouble 分段判定）
inline constexpr int32_t SECRET_REALM_ROLL_NEXT_EVENT = 1409;

/// 妖兽最终属性预生成（层数倍率+随机方差+偷袭减血）
inline constexpr int32_t SECRET_REALM_BUILD_BEAST_STATS = 1410;

/// 妖兽战斗胜利掉落（加权选取，每只 2 材料）
inline constexpr int32_t SECRET_REALM_ROLL_BEAST_LOOT = 1411;

/// 遗迹秘宝描述符生成（候选模板列表参数化）
inline constexpr int32_t SECRET_REALM_GENERATE_RUINS_TREASURE = 1412;

/// 遗迹探索结算（离开/搜寻判定+结果文本+方向事件）
inline constexpr int32_t SECRET_REALM_RESOLVE_RUINS = 1413;

/// 战斗失败丢失背包物品（比例+洗牌选取）
inline constexpr int32_t SECRET_REALM_LOOT_LOSS = 1414;

/// AI 宗门探索队伍派遣（存活境界最高 4 名）
inline constexpr int32_t SECRET_REALM_AI_DISPATCH = 1415;

/// 秘境空闲位置寻找（避宗门随机+兜底最远扫描）
inline constexpr int32_t SECRET_REALM_FIND_POSITION = 1416;

/// 选择选项后体力计算（非法消耗 clamp 防篡改）
inline constexpr int32_t SECRET_REALM_STAMINA = 1417;

/// 年变现世冷却判据（负冷却 clamp 防篡改）
inline constexpr int32_t SECRET_REALM_YEARLY_SPAWN_CHECK = 1418;

/// 秘境精灵变体随机（1×nextInt）
inline constexpr int32_t SECRET_REALM_ROLL_SPRITE = 1419;

/// AI 四因素加权判定概率（攻击/结盟/附属）
inline constexpr int32_t SECT_DECISION_CHANCE = 1420;

/// 附属脱离概率（战力/丢失/胜负/好感度反向）
inline constexpr int32_t SECT_DECISION_BREAKAWAY = 1421;

/// 弟子战力（永久基础属性公式）
inline constexpr int32_t SECT_POWER_DISCIPLE = 1422;

/// 妖兽战力（同公式 + 防篡改 clamp）
inline constexpr int32_t SECT_POWER_BEAST = 1423;

/// 永久基础属性缓存指纹（Java hashCode）
inline constexpr int32_t SECT_POWER_FINGERPRINT = 1424;

/// 品阶时间曲线抽样（1×nextDouble）
inline constexpr int32_t SECT_RARITY_ROLL = 1425;

/// 年份可出最高品阶
inline constexpr int32_t SECT_RARITY_MAX = 1426;

/// 年份保底品阶（下一分段）
inline constexpr int32_t SECT_RARITY_PITY = 1427;

/// 年份品阶权重表（归一化）
inline constexpr int32_t SECT_RARITY_WEIGHTS = 1428;

/// 宗门交易确定性种子（sectId.hashCode + year）
inline constexpr int32_t SECT_TRADE_SEED = 1429;

/// 商品库存量抽样（消耗品/耐用品两档曲线）
inline constexpr int32_t SECT_TRADE_STOCK = 1430;

/// 商品价格波动（±20% 一位小数截断）
inline constexpr int32_t SECT_TRADE_PRICE = 1431;

/// 灵石商品映射（上品/中品 + 年份上限判定）
inline constexpr int32_t SECT_TRADE_SPIRIT_STONE = 1432;

/// 清除弟子全部槽位引用（11 类槽位纯数据变换）
inline constexpr int32_t SLOT_CLEAR_ALL = 1433;

/// 兑换码格式校验（trim/长度/字符集）
inline constexpr int32_t REDEEM_VALIDATE_INPUT = 1434;

/// 灵根类型解析（配置/数量随机/权重生成 + java.util.Random 洗牌）
inline constexpr int32_t REDEEM_ROLL_SPIRIT_ROOT = 1435;

/// 年龄区间 + 境界寿元 ±10% 波动
inline constexpr int32_t REDEEM_RESOLVE_AGE_LIFESPAN = 1436;

/// 灵根阶梯属性掷点 + 避开哨兵 50
inline constexpr int32_t REDEEM_ROLL_SKILLS = 1437;

/// 属性方差生成（-50..50）
inline constexpr int32_t REDEEM_GENERATE_VARIANCE = 1438;

/// 邮件附件列表 → JSON 字符串（kotlinx 对齐）
inline constexpr int32_t MAIL_ATTACHMENT_ENCODE = 1439;

/// 出发探索（校验+会话写入+初始妖兽事件）
inline constexpr int32_t SECRET_REALM_START = 1440;

/// 选择选项（结算+战斗+下一事件+会话结束）
inline constexpr int32_t SECRET_REALM_CHOOSE = 1441;

/// 结束探索（背包结算入仓+秘境清场）
inline constexpr int32_t SECRET_REALM_END = 1442;

/// 手动排班事务（配方校验+材料检查消耗+槽位WORKING）
inline constexpr int32_t PRODUCTION_START = 1443;

/// 手动重置事务（槽位回IDLE全清空）
inline constexpr int32_t PRODUCTION_RESET = 1444;

/// 建筑放置事务（等级/环界门楼/限建/占位/灵石校验+建筑写入）
inline constexpr int32_t BUILDING_PLACE = 1450;

/// 建筑迁移事务（存在性/环界门楼校验+坐标改写）
inline constexpr int32_t BUILDING_MOVE = 1451;

/// 建筑升级事务（等级/差价/canFit校验+原地变换）
inline constexpr int32_t BUILDING_UPGRADE = 1452;

/// 建筑批量拆除事务（存在性/幽灵防御+灵石返还）
inline constexpr int32_t BUILDING_REMOVE = 1453;

/// 建筑批量升级事务（整批等级/候选稳定序/可负担上限/增量canFit）
inline constexpr int32_t BUILDING_UPGRADE_BATCH = 1454;

/// 道路放置事务（占位校验+扣灵石+邻域掩码重算）
inline constexpr int32_t ROAD_PLACE = 1470;

/// 道路拆除事务（存在性校验+邻域掩码重算）
inline constexpr int32_t ROAD_REMOVE = 1471;

/// 装备穿戴事务（校验链+堆叠扣减/实例铸造+槽位列写）
inline constexpr int32_t DISCIPLE_TX_EQUIP = 1480;

/// 装备卸下事务（实例入袋+实例表移除+槽位列清）
inline constexpr int32_t DISCIPLE_TX_UNEQUIP = 1481;

/// 功法学习事务（资格守卫+堆叠消耗+实例铸造+HP/MP增量）
inline constexpr int32_t DISCIPLE_TX_LEARN_MANUAL = 1482;

/// 功法卸下事务（实例入袋+manualIds/熟练度清理）
inline constexpr int32_t DISCIPLE_TX_UNLEARN_MANUAL = 1483;

/// 任命事务（clearAllSlots+亲传/藏经阁槽覆写）
inline constexpr int32_t DISCIPLE_TX_ASSIGN_SLOT = 1484;

/// 卸任事务（亲传/藏经阁单槽重置）
inline constexpr int32_t DISCIPLE_TX_UNASSIGN_SLOT = 1485;

/// 外交事务（结盟请求/解除结盟——仅结盟消费 SYSTEM 1×nextDouble）
inline constexpr int32_t DIPLOMACY_TX = 1500;

/// 赠礼事务（校验链+拒绝 roll SYSTEM 1×nextInt(100)+好感写入+扣费）
inline constexpr int32_t FAVOR_GIFT = 1501;

/// 附庸事务（附属请求/解除——仅请求消费 SYSTEM 1×nextDouble）
inline constexpr int32_t VASSAL_TX = 1502;

/// 单类出售事务（存在/锁定/数量守卫+售价入账+堆叠扣减）
inline constexpr int32_t INV_SELL_ITEM = 1520;

/// 批量出售事务（逐条扣减零入账+末尾一次 Sell(bulk) 入账）
inline constexpr int32_t INV_BULK_SELL = 1521;

/// 商人收购事务（非法参数拒绝+仓库实售量+扣仓入账+收购项回写）
inline constexpr int32_t MERCHANT_SELL_ACQUISITION = 1522;

/// 玩家上架事务（装备/功法/丹药三段首命中登记，不扣仓库）
inline constexpr int32_t MERCHANT_LIST_ITEMS = 1523;

/// 撤下上架项事务（playerListedItems 按 id 移除）
inline constexpr int32_t MERCHANT_REMOVE_LISTED = 1524;

/// 按名称+品阶消耗材料（未锁定按列表序扣减，快照语义）
inline constexpr int32_t INV_CONSUME_MATERIAL = 1525;

/// 商人购买事务（判定序+按型槽位预算容量预测+先加后扣+商家库存扣减；模板缺失failure信封回退）
inline constexpr int32_t INV_BUY_MERCHANT_ITEM = 1530;

/// 充公事务（幂等探测+三态物化+溢出抑制+仅Success移除袋条目）
inline constexpr int32_t INV_CONFISCATE_BAG_ITEM = 1531;

/// 世界关卡战斗事务（校验链+组装 pregens/基础值双分支+BATTLE 分区执行+伤亡写回，回传终态/幸存/阵亡）
inline constexpr int32_t EXPLORE_TX_ATTACK_WORLD_LEVEL = 1570;

/// 宗门侦察事务（AI 守卫选取 alive∧7..9 取 8+PvP 组装满血 ATTACKER+BATTLE 分区执行+伤亡写回，回传守卫展示段）
inline constexpr int32_t EXPLORE_TX_SCOUT_SECT = 1571;

/// 分舵驻守分配（存在/存活校验+同宗已驻静默跳过+旧occupant捕获+全槽清理+槽位字段写，零 RNG）
inline constexpr int32_t EXPLORE_TX_ASSIGN_GARRISON = 1572;

/// 分舵驻守移除（occupant 捕获+槽位清空保留索引，零 RNG）
inline constexpr int32_t EXPLORE_TX_REMOVE_GARRISON = 1573;

/// 逐出事务（存在/存活/非血炼校验+12类槽位清理含住所+实例销毁+派生map收口+行删除；袋物品信封回传Kotlin物化）
inline constexpr int32_t DISCIPLE_LIFECYCLE_EXPEL = 1590;

/// 拜师事务（三相校验+masterIds落表；双侧日志草稿回写lifeEvents）
inline constexpr int32_t DISCIPLE_LIFECYCLE_APPRENTICE = 1591;

/// 婚姻批准事务（已有道侣防御+partnerIds双向绑定+MARRIAGE事件直写；提议移除留Kotlin）
inline constexpr int32_t DISCIPLE_LIFECYCLE_MARRY_APPROVE = 1592;

/// 释放思过事务（statusData思过双键定向移除+状态回IDLE；静默no-op同义）
inline constexpr int32_t DISCIPLE_LIFECYCLE_RELEASE_REFLECTION = 1593;

/// 境界年俸开关事务（yearlySalaryEnabled[realm]覆写，无校验）
inline constexpr int32_t DISCIPLE_LIFECYCLE_SALARY_TOGGLE = 1594;

/// 长老单值槽任命（存在/存活校验+全槽清理+槽位字段写+亲传列表清空，回传被顶替者）
inline constexpr int32_t ELDER_APPOINT_TX = 1610;

/// 长老单值槽卸任（槽位字段清空+亲传列表清空，回传被卸任者）
inline constexpr int32_t ELDER_DISMISS_TX = 1611;

/// 仓库驻守分配（存在/存活校验+旧occupant捕获+全槽清理+条目替换）
inline constexpr int32_t WAREHOUSE_GARRISON_TX = 1612;

/// 洗炼灵根（先扣玉符后抽取：保底/双灵根判定+元素洗牌，SYSTEM 分区）
inline constexpr int32_t SPIRIT_ROOT_WASH_TX = 1613;

/// 新增特质刷新（上限/候选预检+扣玉符+品阶抽取+pending 落盘，SYSTEM 分区）
inline constexpr int32_t TRAIT_ADD_ROLL_TX = 1614;

/// 新增特质确认（上限/合法性校验+追加+lifespan 同步+checkpoint+清 pending，零 RNG）
inline constexpr int32_t TRAIT_ADD_CONFIRM_TX = 1615;

/// 特质单槽洗炼（目标校验+排除集+扣玉符+保底/品阶抽取，SYSTEM 分区）
inline constexpr int32_t TRAIT_WASH_SLOT_TX = 1616;

/// 招募列表移除条目（按 id 过滤幂等，零 RNG）
inline constexpr int32_t RECRUIT_REMOVE_TX = 1630;

/// 年度招募列表刷新（差值门+宗门等级/长老魅力加成+广纳门徒+候选生成，SYSTEM 分区）
inline constexpr int32_t RECRUIT_REFRESH_TX = 1631;

/// 招募列表老化+净化（age+1/超寿元移除/损坏过滤/三级去重/跨表残留，零 RNG）
inline constexpr int32_t RECRUIT_AGE_TX = 1632;

/// 生产槽弟子任命（槽位存在校验+全槽位清理+目标槽写+他槽清空，零 RNG）
inline constexpr int32_t PROD_UI_ASSIGN_SLOT = 1650;

/// 生产槽弟子卸任（占用捕获+WORKING 剩余时长归一+槽位清空，零 RNG）
inline constexpr int32_t PROD_UI_REMOVE_SLOT = 1651;

/// 自动续炼开关翻转（槽位存在校验+镜像字段翻转，零 RNG）
inline constexpr int32_t PROD_UI_TOGGLE_AUTO_RESTART = 1652;

/// 生产槽惰性建槽/镜像槽维护（按 buildingId+slotIndex 覆写或追加，零 RNG）
inline constexpr int32_t PROD_UI_ADD_SLOT = 1653;

/// 灵田单块播种（种子存在/未锁定/余量>0+空地匹配+同事务扣种，零 RNG）
inline constexpr int32_t SPIRIT_FIELD_PLANT_ONE = 1654;

/// 灵田批量播种（余量约束上限+地块镜像序播种+按实际播种数扣种，零 RNG）
inline constexpr int32_t SPIRIT_FIELD_PLANT_BATCH = 1655;

/// 灵田单块移除（按实例首命中清空种植字段，零 RNG）
inline constexpr int32_t SPIRIT_FIELD_REMOVE_ONE = 1656;

/// 灵田批量移除（按实例集合清空种植字段，零 RNG）
inline constexpr int32_t SPIRIT_FIELD_REMOVE_BATCH = 1657;

/// 引导计数递增（键+增量，缺省 0 起算，零 RNG）
inline constexpr int32_t BOUNDARY_GUIDE_COUNTER_INCREMENT_TX = 1670;

/// 自动分配策略+引导计数合并写（策略整包替换+三激活计数增量，零 RNG）
inline constexpr int32_t BOUNDARY_AUTO_ASSIGN_GUIDE_TX = 1671;

/// 建筑建造计数回填（按显示名 max 语义幂等，零 RNG）
inline constexpr int32_t BOUNDARY_BUILDING_GUIDE_BACKFILL_TX = 1672;

/// 政策开关（可负担校验+首月扣费+置位+激活计数+修炼 checkpoint，零 RNG）
inline constexpr int32_t GOV_POLICY_TOGGLE_TX = 1680;

/// 广纳门徒开关（固定费用+付费月戳+激活计数，零 RNG）
inline constexpr int32_t GOV_OPEN_RECRUITMENT_TOGGLE_TX = 1681;

/// 灵矿增产开关（免费+激活计数+灵矿结算月戳，零 RNG）
inline constexpr int32_t GOV_SPIRIT_MINE_BOOST_TOGGLE_TX = 1682;

/// 宗门升级写回（存在性/目标等级校验+玩家宗门 level/levelName 改写，零 RNG）
inline constexpr int32_t SECT_LEVEL_UPGRADE_TX = 1690;

/// 宗门等级奖励领取落账（7 天冷却校验+材料/储物袋/灵石入账+领取记录 upsert，凭据类溢出抑制，零 RNG）
inline constexpr int32_t SECT_LEVEL_CLAIM_TX = 1691;

/// 玉符购买商人刷新落账（上限校验先于扣款+玉符扣减+刷新次数累加钳制，零 RNG）
inline constexpr int32_t JADE_PURCHASE_MERCHANT_REFRESH_TX = 1692;

/// 玉符购买突破率加成落账（弟子存在/存活/上限校验先于扣款+玉符扣减+statusData 写回，零 RNG）
inline constexpr int32_t JADE_PURCHASE_BREAKTHROUGH_BONUS_TX = 1693;

/// 读档恢复会话域判定（到期关闭 closeSecretRealmByExpiry 状态段/死局 endSession 重置/成员净化写回，零 RNG）
inline constexpr int32_t SECRET_REALM_CONTINUE_TX = 1710;

}  // namespace action

}  // namespace gamecore
