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

    /** 存储指标周期聚合（保存/读档/备份/账本漂移/change_log/归档读数，30 分钟节拍） */
    const val STORAGE_METRICS_REPORT = "#storage_metrics_report"

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

    // ==================== 存储指标事件属性（#storage_metrics_report） ====================

    /** 累计保存次数 */
    const val PROP_STORAGE_SAVE_COUNT = "storage_save_count"

    /** 累计读档次数 */
    const val PROP_STORAGE_LOAD_COUNT = "storage_load_count"

    /** 累计缓存命中次数 */
    const val PROP_STORAGE_CACHE_HIT_COUNT = "storage_cache_hit_count"

    /** 累计缓存未命中次数 */
    const val PROP_STORAGE_CACHE_MISS_COUNT = "storage_cache_miss_count"

    /** 累计备份写入失败次数 */
    const val PROP_STORAGE_BACKUP_FAILURE_COUNT = "storage_backup_failure_count"

    /** 累计备份恢复次数 */
    const val PROP_STORAGE_BACKUP_RESTORE_COUNT = "storage_backup_restore_count"

    /** 累计备份超限跳过次数 */
    const val PROP_STORAGE_BACKUP_SKIPPED_COUNT = "storage_backup_skipped_count"

    /** 账本↔派生缓存不一致次数（C++ 以账本为准重锚） */
    const val PROP_STORAGE_JADE_DRIFT_COUNT = "storage_jade_drift_count"

    /** change_log 行数（7 天排障窗口内） */
    const val PROP_STORAGE_CHANGE_LOG_PENDING = "storage_change_log_pending"

    /** 归档战报表行数 */
    const val PROP_STORAGE_ARCHIVE_BATTLE_LOG_ROWS = "storage_archive_battle_log_rows"

    /** 归档弟子表行数 */
    const val PROP_STORAGE_ARCHIVE_DISCIPLE_ROWS = "storage_archive_disciple_rows"

    /** 增量路径落盘次数（SS5 路径分布） */
    const val PROP_STORAGE_INCREMENTAL_SAVE_COUNT = "storage_incremental_save_count"

    /** 全量路径落盘次数（SS5 路径分布，含首保基线建立） */
    const val PROP_STORAGE_FULL_SAVE_COUNT = "storage_full_save_count"

    /** 脏集越界/溢出回退全量次数（SS5 异常回退） */
    const val PROP_STORAGE_DIRTY_FALLBACK_COUNT = "storage_dirty_fallback_count"

    /** 最近一次全量路径原因名（SS5 诊断归因） */
    const val PROP_STORAGE_LAST_FULL_SAVE_REASON = "storage_last_full_save_reason"

}
