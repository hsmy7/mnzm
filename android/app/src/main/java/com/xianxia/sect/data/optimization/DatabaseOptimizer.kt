package com.xianxia.sect.data.optimization

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object DatabaseOptimizationConfig {
    const val PRAGMA_CACHE_SIZE = -64000
    const val PRAGMA_MMAP_SIZE = 268435456L
    const val PRAGMA_TEMP_STORE = "MEMORY"
    const val PRAGMA_SYNCHRONOUS = "NORMAL"
    const val PRAGMA_JOURNAL_MODE = "WAL"
    
    const val INDEX_MAINTENANCE_INTERVAL_MS = 3600_000L
    const val VACUUM_THRESHOLD_MB = 100
    const val ANALYZE_THRESHOLD_OPS = 10000
    
    val OPTIMIZED_INDICES = listOf(
        IndexDefinition("disciple", "idx_disciple_slot_realm_alive", "slot, realm, isAlive"),
        IndexDefinition("disciple", "idx_disciple_slot_status", "slot, status"),
        IndexDefinition("disciple", "idx_disciple_cultivation", "slot, cultivation DESC"),
        IndexDefinition("disciple", "idx_disciple_age", "slot, age"),
        IndexDefinition("equipment", "idx_equipment_slot_rarity", "slot, rarity"),
        IndexDefinition("equipment", "idx_equipment_slot_level", "slot, level DESC"),
        IndexDefinition("manual", "idx_manual_slot_rarity", "slot, rarity"),
        IndexDefinition("pill", "idx_pill_slot_rarity", "slot, rarity"),
        IndexDefinition("material", "idx_material_slot_type", "slot, type"),
        IndexDefinition("battle_log", "idx_battle_log_slot_time", "slot, timestamp DESC"),
        IndexDefinition("battle_log", "idx_battle_log_slot_type", "slot, type"),
        IndexDefinition("game_event", "idx_event_slot_time", "slot, timestamp DESC"),
        IndexDefinition("game_event", "idx_event_slot_type", "slot, type"),
        IndexDefinition("change_log", "idx_change_log_table_time", "table_name, timestamp DESC"),
        IndexDefinition("change_log", "idx_change_log_synced", "synced, timestamp")
    )
    
    val PARTITION_CONFIGS = mapOf(
        "battle_log" to PartitionConfig(
            partitionBy = PartitionType.TIME,
            partitionInterval = PartitionInterval.MONTHLY,
            retentionDays = 30,
            archiveEnabled = true
        ),
        "game_event" to PartitionConfig(
            partitionBy = PartitionType.TIME,
            partitionInterval = PartitionInterval.MONTHLY,
            retentionDays = 60,
            archiveEnabled = true
        )
    )
}

data class IndexDefinition(
    val table: String,
    val name: String,
    val columns: String,
    val unique: Boolean = false,
    val condition: String? = null
)

enum class PartitionType {
    TIME,
    RANGE,
    LIST
}

enum class PartitionInterval {
    DAILY,
    WEEKLY,
    MONTHLY
}

data class PartitionConfig(
    val partitionBy: PartitionType,
    val partitionInterval: PartitionInterval,
    val retentionDays: Int,
    val archiveEnabled: Boolean
)

data class DatabaseStats(
    val totalSize: Long = 0,
    val walSize: Long = 0,
    val shmSize: Long = 0,
    val pageCount: Long = 0,
    val pageSize: Long = 0,
    val freePages: Long = 0,
    val indexCount: Int = 0,
    val tableCount: Int = 0,
    val fragmentationPercent: Double = 0.0
)

data class OptimizationResult(
    val success: Boolean,
    val message: String,
    val statsBefore: DatabaseStats? = null,
    val statsAfter: DatabaseStats? = null,
    val durationMs: Long = 0
)

class DatabaseOptimizer(
    private val context: Context
) {
    companion object {
        private const val TAG = "DatabaseOptimizer"
    }
    
    private val operationCount = AtomicLong(0)
    private var lastAnalyzeCount = 0L
    
    fun configureDatabase(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("PRAGMA journal_mode = ${DatabaseOptimizationConfig.PRAGMA_JOURNAL_MODE}")
            db.execSQL("PRAGMA synchronous = ${DatabaseOptimizationConfig.PRAGMA_SYNCHRONOUS}")
            db.execSQL("PRAGMA cache_size = ${DatabaseOptimizationConfig.PRAGMA_CACHE_SIZE}")
            db.execSQL("PRAGMA temp_store = ${DatabaseOptimizationConfig.PRAGMA_TEMP_STORE}")
            db.execSQL("PRAGMA mmap_size = ${DatabaseOptimizationConfig.PRAGMA_MMAP_SIZE}")
            db.execSQL("PRAGMA foreign_keys = ON")
            
            Log.i(TAG, "Database configured with optimization settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure database", e)
        }
    }
    
    suspend fun createOptimizedIndices(db: SupportSQLiteDatabase): OptimizationResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val statsBefore = getDatabaseStats(db)
            
            try {
                DatabaseOptimizationConfig.OPTIMIZED_INDICES.forEach { index ->
                    createIndexIfNotExists(db, index)
                }
                
                val statsAfter = getDatabaseStats(db)
                val duration = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "Created optimized indices in ${duration}ms")
                OptimizationResult(
                    success = true,
                    message = "Created ${DatabaseOptimizationConfig.OPTIMIZED_INDICES.size} indices",
                    statsBefore = statsBefore,
                    statsAfter = statsAfter,
                    durationMs = duration
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create indices", e)
                OptimizationResult(
                    success = false,
                    message = e.message ?: "Unknown error",
                    statsBefore = statsBefore,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }
    
    private fun createIndexIfNotExists(db: SupportSQLiteDatabase, index: IndexDefinition) {
        try {
            val indexExists = checkIndexExists(db, index.name)
            if (indexExists) {
                Log.d(TAG, "Index ${index.name} already exists")
                return
            }
            
            val uniqueClause = if (index.unique) "UNIQUE " else ""
            val whereClause = index.condition?.let { " WHERE $it" } ?: ""
            
            val sql = "CREATE ${uniqueClause}INDEX IF NOT EXISTS ${index.name} ON ${index.table}(${index.columns})$whereClause"
            db.execSQL(sql)
            
            Log.d(TAG, "Created index: ${index.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create index ${index.name}: ${e.message}")
        }
    }
    
    private fun checkIndexExists(db: SupportSQLiteDatabase, indexName: String): Boolean {
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName)
        )
        return cursor.use { it.count > 0 }
    }
    
    suspend fun analyzeDatabase(db: SupportSQLiteDatabase): OptimizationResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                db.execSQL("PRAGMA optimize")
                db.execSQL("ANALYZE")
                
                lastAnalyzeCount = operationCount.get()
                val duration = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "Database analyzed in ${duration}ms")
                OptimizationResult(
                    success = true,
                    message = "Database analyzed successfully",
                    durationMs = duration
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze database", e)
                OptimizationResult(
                    success = false,
                    message = e.message ?: "Unknown error",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }
    
    suspend fun vacuumDatabase(db: SupportSQLiteDatabase): OptimizationResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val statsBefore = getDatabaseStats(db)
            
            try {
                if (statsBefore.fragmentationPercent < 10) {
                    return@withContext OptimizationResult(
                        success = true,
                        message = "Vacuum not needed, fragmentation below threshold",
                        statsBefore = statsBefore
                    )
                }
                
                db.execSQL("VACUUM")
                
                val statsAfter = getDatabaseStats(db)
                val duration = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "Database vacuumed in ${duration}ms, " +
                        "size reduced from ${statsBefore.totalSize} to ${statsAfter.totalSize}")
                
                OptimizationResult(
                    success = true,
                    message = "Database vacuumed successfully",
                    statsBefore = statsBefore,
                    statsAfter = statsAfter,
                    durationMs = duration
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to vacuum database", e)
                OptimizationResult(
                    success = false,
                    message = e.message ?: "Unknown error",
                    statsBefore = statsBefore,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }
    
    suspend fun checkpointDatabase(db: SupportSQLiteDatabase, mode: CheckpointMode = CheckpointMode.PASSIVE): OptimizationResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val statsBefore = getDatabaseStats(db)
            
            try {
                db.execSQL(mode.query)
                
                val statsAfter = getDatabaseStats(db)
                val duration = System.currentTimeMillis() - startTime
                
                Log.i(TAG, "Database checkpoint (${mode.name}) completed in ${duration}ms, " +
                        "WAL reduced from ${statsBefore.walSize} to ${statsAfter.walSize}")
                
                OptimizationResult(
                    success = true,
                    message = "Checkpoint completed",
                    statsBefore = statsBefore,
                    statsAfter = statsAfter,
                    durationMs = duration
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to checkpoint database", e)
                OptimizationResult(
                    success = false,
                    message = e.message ?: "Unknown error",
                    statsBefore = statsBefore,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }
    
    fun getDatabaseStats(db: SupportSQLiteDatabase): DatabaseStats {
        return try {
            val path = db.path ?: return DatabaseStats()
            
            val dbFile = File(path)
            val walFile = File("$path-wal")
            val shmFile = File("$path-shm")
            
            val pageCount = getPragmaValue(db, "page_count")
            val pageSize = getPragmaValue(db, "page_size")
            val freePages = getPragmaValue(db, "freelist_count")
            
            val totalSize = dbFile.length()
            val fragmentation = if (pageCount > 0 && pageSize > 0) {
                (freePages.toDouble() / pageCount) * 100
            } else 0.0
            
            val tableCount = countTables(db)
            val indexCount = countIndices(db)
            
            DatabaseStats(
                totalSize = totalSize,
                walSize = if (walFile.exists()) walFile.length() else 0,
                shmSize = if (shmFile.exists()) shmFile.length() else 0,
                pageCount = pageCount,
                pageSize = pageSize,
                freePages = freePages,
                indexCount = indexCount,
                tableCount = tableCount,
                fragmentationPercent = fragmentation
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get database stats", e)
            DatabaseStats()
        }
    }
    
    private fun getPragmaValue(db: SupportSQLiteDatabase, pragma: String): Long {
        return try {
            val cursor = db.query("PRAGMA $pragma")
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun countTables(db: SupportSQLiteDatabase): Int {
        return try {
            val cursor = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun countIndices(db: SupportSQLiteDatabase): Int {
        return try {
            val cursor = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            0
        }
    }
    
    fun recordOperation() {
        val count = operationCount.incrementAndGet()
        
        if (count - lastAnalyzeCount >= DatabaseOptimizationConfig.ANALYZE_THRESHOLD_OPS) {
            lastAnalyzeCount = count
        }
    }
    
    fun shouldAnalyze(): Boolean {
        return operationCount.get() - lastAnalyzeCount >= DatabaseOptimizationConfig.ANALYZE_THRESHOLD_OPS
    }
    
    enum class CheckpointMode(val query: String) {
        PASSIVE("PRAGMA wal_checkpoint(PASSIVE)"),
        FULL("PRAGMA wal_checkpoint(FULL)"),
        TRUNCATE("PRAGMA wal_checkpoint(TRUNCATE)"),
        RESTART("PRAGMA wal_checkpoint(RESTART)")
    }
}

class QueryOptimizer {
    companion object {
        private const val TAG = "QueryOptimizer"
        
        private val SLOW_QUERY_THRESHOLD_MS = 100L
        private val QUERY_CACHE_SIZE = 100
    }
    
    private val queryCache = mutableMapOf<String, QueryPlan>()
    private val slowQueries = mutableListOf<SlowQueryRecord>()
    
    data class QueryPlan(
        val sql: String,
        val estimatedRows: Long,
        val usesIndex: Boolean,
        val indexName: String?,
        val isFullScan: Boolean
    )
    
    data class SlowQueryRecord(
        val sql: String,
        val executionTimeMs: Long,
        val timestamp: Long,
        val rowCount: Int
    )
    
    fun explainQuery(db: SupportSQLiteDatabase, sql: String, args: Array<out Any?>? = null): QueryPlan? {
        return try {
            val explainSql = "EXPLAIN QUERY PLAN $sql"
            val cursor = db.query(explainSql, args ?: emptyArray())
            
            var usesIndex = false
            var indexName: String? = null
            var isFullScan = false
            var estimatedRows = 0L
            
            cursor.use {
                while (it.moveToNext()) {
                    val detail = it.getString(3)
                    if (detail.contains("USING INDEX", ignoreCase = true)) {
                        usesIndex = true
                        val match = Regex("USING INDEX (\\w+)").find(detail)
                        indexName = match?.groupValues?.get(1)
                    }
                    if (detail.contains("SCAN", ignoreCase = true)) {
                        isFullScan = true
                    }
                    if (detail.contains("~(\\d+)".toRegex())) {
                        val match = Regex("~(\\d+)").find(detail)
                        estimatedRows = match?.groupValues?.get(1)?.toLong() ?: 0
                    }
                }
            }
            
            QueryPlan(sql, estimatedRows, usesIndex, indexName, isFullScan)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to explain query: $sql", e)
            null
        }
    }
    
    fun <T> executeWithOptimization(
        db: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?>? = null,
        block: (android.database.Cursor) -> T
    ): T? {
        val startTime = System.currentTimeMillis()
        
        return try {
            val cursor = db.query(sql, args ?: emptyArray())
            val result = cursor.use(block)
            
            val duration = System.currentTimeMillis() - startTime
            if (duration > SLOW_QUERY_THRESHOLD_MS) {
                recordSlowQuery(sql, duration, 0)
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Query execution failed: $sql", e)
            null
        }
    }
    
    private fun recordSlowQuery(sql: String, durationMs: Long, rowCount: Int) {
        val record = SlowQueryRecord(
            sql = sql,
            executionTimeMs = durationMs,
            timestamp = System.currentTimeMillis(),
            rowCount = rowCount
        )
        
        synchronized(slowQueries) {
            slowQueries.add(record)
            if (slowQueries.size > 50) {
                slowQueries.removeAt(0)
            }
        }
        
        Log.w(TAG, "Slow query detected: ${durationMs}ms - ${sql.take(100)}")
    }
    
    fun getSlowQueries(): List<SlowQueryRecord> {
        return synchronized(slowQueries) {
            slowQueries.sortedByDescending { it.executionTimeMs }.toList()
        }
    }
    
    fun suggestOptimization(sql: String): List<String> {
        val suggestions = mutableListOf<String>()
        val upperSql = sql.uppercase()
        
        if (upperSql.contains("SELECT *")) {
            suggestions.add("Consider selecting specific columns instead of SELECT *")
        }
        
        if (upperSql.contains("WHERE") && !upperSql.contains("INDEXED BY")) {
            suggestions.add("Consider using an index hint for better performance")
        }
        
        if (upperSql.contains("LIKE") && upperSql.contains("'%")) {
            suggestions.add("Leading wildcard LIKE queries cannot use indexes efficiently")
        }
        
        if (upperSql.contains("ORDER BY") && !upperSql.contains("LIMIT")) {
            suggestions.add("Consider adding LIMIT clause when using ORDER BY")
        }
        
        if (upperSql.contains("OR") && upperSql.contains("WHERE")) {
            suggestions.add("Consider using UNION or IN clause instead of OR for better index usage")
        }
        
        return suggestions
    }
    
    fun clearCache() {
        queryCache.clear()
    }
}
