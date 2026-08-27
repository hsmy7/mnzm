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

    /** 添加装备实例 */
    const val INV_ADD_EQUIPMENT_INSTANCE: Int = 1011

    /** 添加功法堆叠 */
    const val INV_ADD_MANUAL_STACK: Int = 1012

    /** 添加功法实例 */
    const val INV_ADD_MANUAL_INSTANCE: Int = 1013

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

}
