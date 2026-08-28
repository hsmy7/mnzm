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

}  // namespace action

}  // namespace gamecore
