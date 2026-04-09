package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 数据库迁移失败安全处理器
 *
 * 当 Room schema 迁移失败时，提供安全的回退策略：
 * 1. 记录详细错误日志（包含 DB 文件大小等诊断信息）
 * 2. 将原始数据库文件备份为 .corrupted_<timestamp> 后缀
 * 3. 删除原始文件以便 Room 重新创建
 *
 * 设计原则：
 * - 绝不自动丢弃用户数据，始终先备份
 * - 备份文件保留时间戳，便于排查问题
 * - 返回布尔值告知调用者处理结果
 */
object MigrationFallbackHandler {
    private const val TAG = "MigrationFallback"

    /**
     * 处理数据库迁移失败场景
     *
     * @param context 应用上下文，用于获取数据库路径
     * @param dbFile 目标数据库文件（通常是 getDatabasePath 返回的 File）
     * @param reason 失败原因描述，会记录到日志中
     * @return true 表示处理成功（备份完成 + 原文件已删除），false 表示处理失败
     */
    fun handleMigrationFailure(context: Context, dbFile: File, reason: String): Boolean {
        return try {
            Log.e(TAG, "Schema migration failed: $reason")
            Log.e(TAG, "Database file: ${dbFile.absolutePath} (${dbFile.length()} bytes)")

            val backupFile = File(dbFile.parentFile, "${dbFile.name}.corrupted_${System.currentTimeMillis()}")
            if (dbFile.exists()) {
                dbFile.copyTo(backupFile, overwrite = true)
                Log.i(TAG, "Original database backed up to: ${backupFile.name}")

                val deleted = dbFile.delete()
                Log.i(TAG, "Original database file deleted: $deleted")
                deleted
            } else {
                Log.w(TAG, "Database file does not exist, nothing to clean up")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle migration failure", e)
            false
        }
    }

    /**
     * 获取指定数据库名称对应的 File 对象
     *
     * @param context 应用上下文
     * @param dbName 数据库文件名（如 "xianxia_sect.db"）
     */
    fun getDatabaseFile(context: Context, dbName: String): File {
        return context.getDatabasePath(dbName)
    }
}
