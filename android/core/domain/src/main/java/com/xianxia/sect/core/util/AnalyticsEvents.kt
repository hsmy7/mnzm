package com.xianxia.sect.core.util

/**
 * 数据埋点事件字典 —— 事件名与属性键的唯一真相源。
 *
 * 与 `docs/knowledge-base.md#事件字典` 章节一一对应；新增事件必须：
 * 1. 在本对象登记常量
 * 2. 同步知识库事件字典 + 在 TapDB 后台「事件管理」录入
 * 3. 同步 [AnalyticsEventsDictionaryTest] 的登记集合（遗漏即测试失败）
 *
 * 命名规范（TapDB 官方文档）：预置/特殊事件与自定义事件名以 `#` 开头；
 * `game_start`/`battle_end` 为历史兼容事件，保持旧名不变。
 */
object AnalyticsEvents {

    // ==================== TapDB 预置/特殊事件 ====================

    /** 广告变现收入（TapDB 后台开启「变现收入」扩展功能后生效） */
    const val AD_SHOW = "#ad_show"

    // ==================== 自定义事件 ====================

    /** 新档创建（FTUE 漏斗：首日转化） */
    const val GAME_NEW_SAVE = "#game_new_save"

    /** 首次战斗胜利（FTUE 漏斗：Aha Moment 验证） */
    const val BATTLE_FIRST_WIN = "#battle_first_win"

    /** 突破成功（引擎结算，供突破漏斗与 Aha Moment 分析） */
    const val BREAKTHROUGH_SUCCESS = "#breakthrough_success"

    /** 首次突破成功（首次语义由 app 层 FirstEventTracker 去重） */
    const val BREAKTHROUGH_FIRST = "#breakthrough_first"

    /** 广告奖励发放成功（广告价值分析） */
    const val AD_REWARD_CLAIM = "#ad_reward_claim"

    /**
     * 存量迁移引导收口（SR-6 完成率指标）：一次迁移跑到 DONE / PARTIAL_FAILED 时上报一次。
     * 属性见 `PROP_MIGRATION_*`（去标识化：只有计数与模式，零槽位内容、零 PII）。
     */
    const val SAVE_MIGRATION_RESULT = "#save_migration_result"

    // ==================== 历史兼容事件（保持旧名） ====================

    /** 游戏会话开始（GameActivity PLAYING 上报） */
    const val GAME_START = "game_start"

    /** 战斗结束（引擎 CaveExplorationProcessor 上报） */
    const val BATTLE_END = "battle_end"

    // ==================== 属性键 ====================

    /** 战斗结果（win / lose） */
    const val PROP_OUTCOME = "outcome"

    /** 敌方类型（洞府名等） */
    const val PROP_ENEMY_TYPE = "enemy_type"

    /** 战斗回合数 */
    const val PROP_TURNS = "turns"

    /** 出战队伍人数 */
    const val PROP_TEAM_SIZE = "team_size"

    /** 存档槽位 */
    const val PROP_SLOT = "slot"

    /** 宗门名 */
    const val PROP_SECT_NAME = "sect_name"

    /** 新境界（realm 数值越小境界越高） */
    const val PROP_REALM = "realm"

    /** 境界小层 */
    const val PROP_REALM_LAYER = "realm_layer"

    /** 弟子名（游戏内名称，非个人身份信息） */
    const val PROP_DISCIPLE_NAME = "disciple_name"

    /** 广告用途（AdPurpose 枚举名） */
    const val PROP_PURPOSE = "purpose"

    /** 广告奖励名 */
    const val PROP_REWARD_NAME = "reward_name"

    /** 广告奖励数量 */
    const val PROP_REWARD_AMOUNT = "reward_amount"

    /** 游戏版本号 */
    const val PROP_GAME_VERSION = "game_version"

    // ==================== SR-6 存量迁移指标属性（#save_migration_result）====================

    /** 收口时仍未上云的槽位数（完成率分母的未完成侧） */
    const val PROP_MIGRATION_PENDING_TOTAL = "pending_total"

    /** 收口时已确认上云/已裁决以云端为准的槽位数（完成率的分子） */
    const val PROP_MIGRATION_MIGRATED_TOTAL = "migrated_total"

    /** 收口时仍待玩家二选一的槽位数（双端冲突或云态不可证） */
    const val PROP_MIGRATION_CONFLICT_TOTAL = "conflict_total"

    /** 收口时本机损坏而被跳过的槽位数（既未上云也未覆盖） */
    const val PROP_MIGRATION_BLOCKED_TOTAL = "blocked_total"

    /** 上报时的云后端模式（SaveBackendMode 枚举名，运营侧区分是否已升档） */
    const val PROP_MIGRATION_MODE_AFTER = "mode_after"
}
