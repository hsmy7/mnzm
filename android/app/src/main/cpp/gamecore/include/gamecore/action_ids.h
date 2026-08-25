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

}  // namespace action

}  // namespace gamecore
