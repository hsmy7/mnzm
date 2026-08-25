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

}
