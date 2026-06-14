package com.xianxia.sect.core.data.migration

/**
 * 存档迁移策略接口（预留）
 *
 * 当前版本 4.0.00 数据库 version=1 + fallbackToDestructiveMigration，无增量迁移。
 * 本接口为未来版本升级时预留扩展点：
 * - 实现此接口可定义版本间增量迁移逻辑（如 1→2 添加新字段，2→3 重构数据结构）
 * - MigrationRegistry 管理所有已注册的 SaveMigrationStrategy 实现
 * - 存档加载时按链式顺序执行所有匹配版本的迁移
 *
 * 使用时机：当需要在不破坏现有存档的情况下升级数据格式时，
 * 实现 SaveMigrationStrategy 并在 DI 中注册到 MigrationRegistry。
 *
 * @param fromVersion 源版本号（SchemaVersion major）
 * @param toVersion   目标版本号（SchemaVersion major）
 */
interface SaveMigrationStrategy {
    val fromVersion: Int
    val toVersion: Int

    /**
     * 迁移存档数据。
     *
     * @param data 原始存档 JSON 字符串（待反序列化）
     * @return 迁移后的存档 JSON 字符串
     * @throws MigrationException 迁移失败时抛出，调用方应回滚或降级
     */
    fun migrate(data: String): String

    /**
     * 迁移是否可逆。可逆迁移支持回滚（用于降级场景）。
     */
    val isReversible: Boolean get() = false

    /**
     * 回滚迁移（仅当 [isReversible] 为 true 时实现）。
     *
     * @param data 已迁移的存档 JSON 字符串
     * @return 回滚后的存档 JSON 字符串
     */
    fun rollback(data: String): String {
        throw UnsupportedOperationException("Migration $fromVersion->$toVersion is not reversible")
    }
}

/**
 * 存档迁移异常。
 */
class MigrationException(
    val fromVersion: Int,
    val toVersion: Int,
    message: String,
    cause: Throwable? = null
) : RuntimeException("Save migration $fromVersion->$toVersion failed: $message", cause)

/**
 * 迁移注册表（预留）。
 *
 * 未来启用增量迁移时，在此注册所有 SaveMigrationStrategy 实现。
 * 加载存档时从当前 SchemaVersion 到 SchemaVersion.CURRENT 依次执行。
 */
object MigrationRegistry {
    private val strategies = mutableListOf<SaveMigrationStrategy>()

    fun register(strategy: SaveMigrationStrategy) {
        strategies.add(strategy)
    }

    fun getMigrationChain(fromVersion: Int, toVersion: Int): List<SaveMigrationStrategy> {
        return strategies
            .filter { it.fromVersion >= fromVersion && it.toVersion <= toVersion }
            .sortedBy { it.fromVersion }
    }
}
