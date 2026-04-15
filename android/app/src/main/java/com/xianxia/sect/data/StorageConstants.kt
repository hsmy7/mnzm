package com.xianxia.sect.data

/**
 * 存储系统统一常量定义
 *
 * 将散布在代码各处的硬编码魔法数字集中到此处。
 * 所有存储相关的超时、大小限制、间隔等配置值均在此定义。
 *
 * 设计原则：
 * 1. 单一真实来源：所有常量在此定义，其他文件引用此处
 * 2. 语义化命名：常量名清晰表达用途，避免裸数字
 * 3. 分类组织：按功能域分组（缓存、WAL、DB、网络等）
 * 4. 可运行时调整：部分值可通过 RuntimeConfig 覆盖
 */
object StorageConstants {
    
    // ==================== 槽位与存档 ====================
    
    /** 默认最大存档槽位数 */
    const val DEFAULT_MAX_SLOTS = 6
    
    /** 自动存档槽位 ID */
    const val AUTO_SAVE_SLOT = 0
    
    /** 紧急存档槽位 ID */
    const val EMERGENCY_SLOT = -1

    // ==================== 缓存配置 ====================
    
    /** 内存缓存默认大小 (MB) - 已精简 */
    const val DEFAULT_MEMORY_CACHE_SIZE_MB = 8L
    
    /** 磁盘缓存默认大小 (MB) */
    const val DEFAULT_DISK_CACHE_SIZE_MB = 32L
    
    /** 缓存条目最大数量 */
    const val DEFAULT_MAX_CACHE_ENTRIES = 1000
    
    /** SWR (Stale-While-Revalidate) TTL: 1 小时 */
    const val SWR_TTL_MS = 3600_000L
    
    /** SWR 宽限期：在 TTL 过后仍返回旧数据的时间窗口 */
    const val SWR_STALE_WHILE_REVALIDATE_MS = 300_000L
    
    /** 清理间隔：30 秒 */
    const val CACHE_CLEANUP_INTERVAL_MS = 30_000L
    
    /** 统计日志输出间隔：5 分钟 */
    const val STATS_LOG_INTERVAL_MS = 300_000L
    
    /** 内存压力检查间隔：10 秒 */
    const val MEMORY_CHECK_INTERVAL_MS = 10_000L

    // ==================== 数据库操作 ====================
    
    /** Room 批量操作默认批次大小 */
    const val DEFAULT_BATCH_SIZE = 200
    
    /** 增量保存阈值（字节）：小于此值尝试增量保存 */
    const val INCREMENTAL_THRESHOLD_BYTES = 50 * 1024  // 50KB

    // ==================== WAL (Write-Ahead Log) ====================
    
    /** WAL 目录名 */
    const val WAL_DIR_NAME = "wal_v4"
    
    /** WAL 文件名 */
    const val WAL_FILE_NAME = "transactions.wal"
    
    /** WAL 快照目录名 */
    const val SNAPSHOT_DIR_NAME = "snapshots"
    
    /** WAL 最大文件大小 (MB) - 超过后触发 compact */
    const val MAX_WAL_SIZE_BYTES = 10L * 1024 * 1024  // 10MB
    
    /** 单个快照最大大小 (MB) */
    const val MAX_SNAPSHOT_SIZE_BYTES = 50L * 1024 * 1024  // 50MB
    
    /** 所有快照合计最大大小 (MB) */
    const val MAX_TOTAL_SNAPSHOTS_SIZE_BYTES = 200L * 1024 * 1024  // 200MB
    
    /** 快照最大保留时间：12 小时 */
    const val MAX_SNAPSHOT_AGE_MS = 12 * 60 * 60 * 1000L
    
    /** 最小保留快照数（即使过期也不清理） */
    const val MIN_SNAPSHOTS_TO_KEEP = 3
    
    /** Checkpoint 操作间隔（每 N 次 commit 后执行） */
    const val CHECKPOINT_INTERVAL = 100
    
    /** BufferedWALWriter buffer 大小 */
    const val WAL_BUFFER_SIZE_BYTES = 64 * 1024  // 64KB
    
    /** BufferedWALWriter 自动 flush 间隔 */
    const val WAL_FLUSH_INTERVAL_MS = 1000L  // 1秒
    
    /** BufferedWALWriter pending 数据强制 flush 阈值 */
    const val WAL_MAX_PENDING_BYTES = 256 * 1024  // 256KB

    // ==================== Atomic Pipeline ====================
    
    /** Pipeline 各阶段超时时间 */
    const val PIPELINE_PHASE_TIMEOUT_MS = 10_000L  // 10秒

    // ==================== 存档文件格式 ====================
    
    /** 新版存档 magic header */
    const val MAGIC_NEW_FORMAT = "XSAV"
    
    /** 分片文件后缀格式模板 */
    const val SHARD_FILE_SUFFIX_PATTERN = ".shard_%03d"
    
    /** 分片 magic header */
    const val SHARD_MAGIC = "SHRD"

    // ==================== 重试策略 ====================
    
    /** 存档操作默认重试次数 */
    const val SAVE_RETRY_MAX_RETRIES = 3
    
    /** 存档重试初始延迟 (ms) */
    const val SAVE_RETRY_INITIAL_DELAY_MS = 100L
    
    /** 存档重试最大延迟 (ms) */
    const val SAVE_RETRY_MAX_DELAY_MS = 5000L
    
    /** 存档重试退避倍数 */
    const val SAVE_RETRY_BACKOFF_MULTIPLIER = 2.0
    
    /** 加载操作重试次数（比存档少） */
    const val LOAD_RETRY_MAX_RETRIES = 2
    
    /** 加载重试初始延迟 (ms) */
    const val LOAD_RETRY_INITIAL_DELAY_MS = 50L
    
    /** 加载重试最大延迟 (ms) */
    const val LOAD_RETRY_MAX_DELAY_MS = 2000L
    
    /** 重试抖动因子 (0~1) */
    const val RETRY_JITTER_FACTOR = 0.2

    // ==================== 配额管理 ====================
    
    /** 总存储预算 (MB) - 用于配额检查 */
    const val TOTAL_STORAGE_BUDGET_MB = 200L
    
    /** 分片阈值 (MB)：超过此大小的存档使用分片方案 */
    const val SHARDING_THRESHOLD_MB = 80L
    
    /** 单个分片最大大小 (MB) */
    const val SHARD_MAX_SIZE_MB = 40L

    // ==================== 数据归档 ====================
    
    /** BattleLog 默认保留数量 */
    const val DEFAULT_MAX_BATTLE_LOGS = 500
    
    /** GameEvent 默认保留数量 */
    const val DEFAULT_MAX_GAME_EVENTS = 1000

    // ==================== 存档大小估算常量（用于 estimateSaveSize）====================
    
    /** 基础框架开销 (bytes) */
    const val ESTIMATE_BASE_OVERHEAD = 2000L
    
    /** 序列化额外开销比例 (15%) */
    const val ESTIMATE_SERIALIZATION_OVERHEAD_RATIO = 0.15
    
    /** 各实体类型每项估算大小 (bytes) */
    object EntitySize {
        const val DISCIPLE = 800L
        const val EQUIPMENT = 350L
        const val MANUAL = 280L
        const val PILL = 180L
        const val MATERIAL = 150L
        const val HERB = 160L
        const val SEED = 140L
        const val BATTLE_LOG = 600L
        const val GAME_EVENT = 250L
        const val TEAM = 450L
        const val ALLIANCE = 300L
        const val PRODUCTION_SLOT = 200L
    }

    // ==================== 内存压力响应 ====================
    
    /** onTrimMemory 级别对应的归一化压力值 (0~1) */
    object TrimMemoryPressure {
        const val RUNNING_LOW = 0.15f       // 15%
        const val RUNNING_MODERATE = 0.35f   // 35%
        const val BACKGROUND = 0.55f         // 55%
        const val COMPLETE = 0.85f           // 85%
    }
    
    /** Sigmoid 曲线参数 */
    const val SIGMOID_K = 8.0
    const val SIGMID_MIDPOINT = 0.5f
}
