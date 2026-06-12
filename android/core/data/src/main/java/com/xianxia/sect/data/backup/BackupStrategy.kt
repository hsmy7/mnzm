package com.xianxia.sect.data.backup

/**
 * 备份类型枚举
 * 区分不同来源的备份，用于应用不同的保留策略
 */
enum class BackupType {
    /** 自动备份：系统定期自动创建的备份 */
    AUTO,
    /** 手动备份：用户主动触发的备份 */
    MANUAL,
    /** 关键节点备份：如境界突破等重要游戏事件时自动创建 */
    CRITICAL,
    /** 紧急备份：系统异常或崩溃前创建的保护性备份 */
    EMERGENCY;

    companion object {
        fun fromString(value: String): BackupType? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 备份重要性标记
 * 决定备份的保留优先级和清理顺序
 */
enum class BackupImportance {
    /** 普通备份：可被清理 */
    NORMAL,
    /** 重要备份：保留时间更长 */
    IMPORTANT,
    /** 永久备份：永不清理（除非手动删除） */
    PERMANENT;

    companion object {
        fun fromString(value: String): BackupImportance? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 备份策略配置
 * 定义不同类型备份的保留规则
 */
data class BackupRetentionPolicy(
    val backupType: BackupType,
    /** 最大保留版本数 */
    val maxVersions: Int,
    /** 默认重要性等级 */
    val defaultImportance: BackupImportance,
    /** 是否允许被自动清理 */
    val autoCleanable: Boolean = true
) {
    companion object {
        /** 自动备份策略：保留最近 5 个版本 */
        val AUTO_POLICY = BackupRetentionPolicy(
            backupType = BackupType.AUTO,
            maxVersions = 5,
            defaultImportance = BackupImportance.NORMAL,
            autoCleanable = true
        )

        /** 手动备份策略：保留最近 10 个版本 */
        val MANUAL_POLICY = BackupRetentionPolicy(
            backupType = BackupType.MANUAL,
            maxVersions = 10,
            defaultImportance = BackupImportance.IMPORTANT,
            autoCleanable = true
        )

        /** 关键节点备份策略：永久保留 */
        val CRITICAL_POLICY = BackupRetentionPolicy(
            backupType = BackupType.CRITICAL,
            maxVersions = Int.MAX_VALUE,
            defaultImportance = BackupImportance.PERMANENT,
            autoCleanable = false
        )

        /** 紧急备份策略：永久保留 */
        val EMERGENCY_POLICY = BackupRetentionPolicy(
            backupType = BackupType.EMERGENCY,
            maxVersions = Int.MAX_VALUE,
            defaultImportance = BackupImportance.PERMANENT,
            autoCleanable = false
        )

        /**
         * 根据备份类型获取对应的策略
         */
        fun forType(type: BackupType): BackupRetentionPolicy = when (type) {
            BackupType.AUTO -> AUTO_POLICY
            BackupType.MANUAL -> MANUAL_POLICY
            BackupType.CRITICAL -> CRITICAL_POLICY
            BackupType.EMERGENCY -> EMERGENCY_POLICY
        }
    }
}

/**
 * 增强的备份信息
 * 包含备份类型、重要性等元数据
 */
data class EnhancedBackupInfo(
    val id: String,
    val slot: Int,
    val type: BackupType,
    val importance: BackupImportance,
    val timestamp: Long,
    val size: Long,
    val checksum: String,
    val description: String? = null
)

/**
 * 备份选择结果
 * 用于恢复时选择最佳备份
 */
data class BackupSelectionResult(
    val selected: EnhancedBackupInfo?,
    val reason: SelectionReason,
    val availableBackups: List<EnhancedBackupInfo>
)

/**
 * 备份选择原因
 */
enum class SelectionReason {
    /** 找到最佳匹配 */
    BEST_MATCH,
    /** 使用最近的可用备份 */
    MOST_RECENT,
    /** 无可用备份 */
    NO_BACKUPS_AVAILABLE,
    /** 用户指定了特定备份 */
    USER_SPECIFIED
}

/**
 * 备份策略管理器
 * 提供备份选择算法和智能清理逻辑
 */
class BackupStrategy {

    companion object {
        private const val TAG = "BackupStrategy"

        // 各类备份的最大全局限制
        const val MAX_AUTO_BACKUPS_PER_SLOT = 5
        const val MAX_MANUAL_BACKUPS_PER_SLOT = 10
        const val MAX_CRITICAL_BACKUPS_PER_SLOT = 20

        // 全局备份总数限制（所有槽位合计）
        const val GLOBAL_MAX_TOTAL_BACKUPS = 50
    }

    /**
     * 根据备份类型获取文件名前缀
     */
    fun getFileNamePrefix(type: BackupType): String = when (type) {
        BackupType.AUTO -> "auto"
        BackupType.MANUAL -> "manual"
        BackupType.CRITICAL -> "critical"
        BackupType.EMERGENCY -> "emergency"
    }

    /**
     * 从文件名解析备份类型
     */
    fun parseBackupTypeFromFileName(fileName: String): BackupType? {
        return when {
            fileName.startsWith("auto_") -> BackupType.AUTO
            fileName.startsWith("manual_") -> BackupType.MANUAL
            fileName.startsWith("critical_") -> BackupType.CRITICAL
            fileName.startsWith("emergency_") -> BackupType.EMERGENCY
            else -> null
        }
    }

    /**
     * 生成备份文件名
     * 格式: {type}_slot_{slot}_{timestamp}.bak
     */
    fun generateBackupFileName(slot: Int, type: BackupType, timestamp: Long = System.currentTimeMillis()): String {
        val prefix = getFileNamePrefix(type)
        return "${prefix}_slot_${slot}_${timestamp}.bak"
    }

    /**
     * 解析备份文件名
     * 返回解析出的组件，如果格式不匹配则返回 null
     */
    fun parseBackupFileName(fileName: String): ParsedBackupName? {
        // 匹配格式: {type}_slot_{slot}_{timestamp}.bak
        val regex = Regex("""^(auto|manual|critical|emergency)_slot_(\d+)_(\d+)\.bak$""")
        val match = regex.find(fileName) ?: return null

        val typeStr = match.groupValues[1]
        val slot = match.groupValues[2].toIntOrNull() ?: return null
        val timestamp = match.groupValues[3].toLongOrNull() ?: return null
        val type = BackupType.fromString(typeStr) ?: return null

        return ParsedBackupName(
            type = type,
            slot = slot,
            timestamp = timestamp,
            originalFileName = fileName
        )
    }

    /**
     * 选择最佳备份用于恢复
     *
     * 算法优先级：
     * 1. PERMANENT 级别的备份优先
     * 2. CRITICAL/EMERGENCY 类型的备份优先
     * 3. 时间戳最新的优先
     * 4. IMPORTANT 级别优先于 NORMAL
     *
     * @param backups 可用的备份列表
     * @param preferredType 偏好的备份类型（可选）
     * @return 备份选择结果
     */
    fun selectBestBackup(
        backups: List<EnhancedBackupInfo>,
        preferredType: BackupType? = null
    ): BackupSelectionResult {
        if (backups.isEmpty()) {
            return BackupSelectionResult(
                selected = null,
                reason = SelectionReason.NO_BACKUPS_AVAILABLE,
                availableBackups = emptyList()
            )
        }

        // 如果用户指定了类型，优先从该类型中选择
        val filteredBackups = if (preferredType != null) {
            val typed = backups.filter { it.type == preferredType }
            if (typed.isNotEmpty()) typed else backups
        } else {
            backups
        }

        // 排序策略：
        // 1. 重要性降序（PERMANENT > IMPORTANT > NORMAL）
        // 2. 类型优先级（CRITICAL/EMERGENCY > MANUAL > AUTO）
        // 3. 时间戳降序（最新优先）
        val sorted = filteredBackups.sortedWith(
            compareByDescending<EnhancedBackupInfo> { it.importance }
                .thenByDescending { getTypePriority(it.type) }
                .thenByDescending { it.timestamp }
        )

        val selected = sorted.first()
        val reason = if (preferredType != null && selected.type == preferredType) {
            SelectionReason.USER_SPECIFIED
        } else {
            SelectionReason.BEST_MATCH
        }

        return BackupSelectionResult(
            selected = selected,
            reason = reason,
            availableBackups = sorted
        )
    }

    /**
     * 获取类型的优先级数值（越高越优先）
     */
    private fun getTypePriority(type: BackupType): Int = when (type) {
        BackupType.CRITICAL -> 4
        BackupType.EMERGENCY -> 4
        BackupType.MANUAL -> 3
        BackupType.AUTO -> 2
    }

    /**
     * 智能清理：确定哪些备份应该被删除
     *
     * 清理策略：
     * 1. 永不删除 PERMANENT 级别的备份
     * 2. 优先删除旧的自动备份
     * 3. 其次删除旧的手动备份中的 NORMAL 级别
     * 4. 遵循各类型的最大版本数限制
     *
     * @param backups 当前所有备份
     * @return 应该被删除的备份列表（按删除优先级排序）
     */
    fun getBackupsToClean(backups: List<EnhancedBackupInfo>): List<EnhancedBackupInfo> {
        if (backups.isEmpty()) return emptyList()

        val toDelete = mutableListOf<EnhancedBackupInfo>()

        // 按类型分组
        val groupedByType = backups.groupBy { it.type }

        // 对每种类型检查是否超过限制
        for ((type, typeBackups) in groupedByType) {
            val policy = BackupRetentionPolicy.forType(type)
            if (!policy.autoCleanable) continue // 不可自动清理的类型跳过

            // 按 timestamp 排序（旧的在前）
            val sortedByVersion = typeBackups.sortedBy { it.timestamp }

            // 分离出不可清理的备份
            val permanentOnes = sortedByVersion.filter { it.importance == BackupImportance.PERMANENT }
            val cleanableOnes = sortedByVersion.filter { it.importance != BackupImportance.PERMANENT }

            // 计算超出的数量
            val exceedCount = cleanableOnes.size - policy.maxVersions + permanentOnes.size
            if (exceedCount > 0) {
                // 标记最旧的超出部分为待删除
                toDelete.addAll(cleanableOnes.take(exceedCount))
            }
        }

        // 排序删除优先级：
        // 1. AUTO 类型优先
        // 2. NORMAL 重要性优先
        // 3. 时间戳最旧的优先
        return toDelete.sortedWith(
            compareBy<EnhancedBackupInfo> { it.type != BackupType.AUTO } // AUTO 类型排在前面
                .thenBy { it.importance } // NORMAL < IMPORTANT
                .thenBy { it.timestamp } // 旧的在前
        )
    }

    /**
     * 根据游戏事件判断是否应该创建关键节点备份
     *
     * @param eventType 游戏事件类型
     * @param additionalContext 额外上下文信息
     * @return 是否应该创建 CRITICAL 备份
     */
    fun shouldCreateCriticalBackup(
        eventType: String,
        additionalContext: Map<String, Any>? = null
    ): Boolean {
        // 定义应该触发关键备份的事件
        val criticalEvents = setOf(
            "REALM_BREAKTHROUGH",      // 境界突破
            "DISCIPLE_BREAKTHROUGH",   // 弟子突破
            "SECT_LEVEL_UP",           // 宗门升级
            "BOSS_DEFEATED",           // 击败Boss
            "RARE_ITEM_OBTAINED",      // 获得稀有物品
            "PLOT_MILESTONE"           // 剧情里程碑
        )

        return eventType in criticalEvents
    }

    /**
     * 根据上下文建议备份的重要性级别
     */
    fun suggestImportance(
        type: BackupType,
        context: Map<String, Any>? = null
    ): BackupImportance {
        // CRITICAL 和 EMERGENCY 类型默认为 PERMANENT
        if (type == BackupType.CRITICAL || type == BackupType.EMERGENCY) {
            return BackupImportance.PERMANENT
        }

        // 手动备份默认为 IMPORTANT
        if (type == BackupType.MANUAL) {
            return BackupImportance.IMPORTANT
        }

        // 自动备份默认为 NORMAL，但可以根据上下文提升
        if (context?.get("is_milestone") == true) {
            return BackupImportance.IMPORTANT
        }

        return BackupImportance.NORMAL
    }
}

/**
 * 解析后的备份文件名组件
 */
data class ParsedBackupName(
    val type: BackupType,
    val slot: Int,
    val timestamp: Long,
    val originalFileName: String
)
