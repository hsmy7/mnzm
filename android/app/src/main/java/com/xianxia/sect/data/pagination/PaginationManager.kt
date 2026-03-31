package com.xianxia.sect.data.pagination

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.GameDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object PaginationConfig {
    const val DEFAULT_PAGE_SIZE = 50
    const val MAX_PAGE_SIZE = 500
    const val PREFETCH_DISTANCE = 2
    const val CACHE_PAGE_COUNT = 5
    const val STREAM_BATCH_SIZE = 100
}

data class PageRequest(
    val page: Int,
    val pageSize: Int = PaginationConfig.DEFAULT_PAGE_SIZE,
    val filter: String? = null,
    val orderBy: String? = null,
    val ascending: Boolean = false
)

data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val isFirstPage: Boolean get() = page == 1
    val isLastPage: Boolean get() = !hasNextPage
}

data class CursorInfo(
    val cursor: String,
    val timestamp: Long,
    val itemCount: Int
)

interface PageLoader<T> {
    suspend fun loadPage(request: PageRequest): PageResult<T>
    suspend fun loadAfter(cursor: String, limit: Int): List<T>
    suspend fun loadBefore(cursor: String, limit: Int): List<T>
    suspend fun getTotalCount(): Int
}

class PaginationManager(
    private val database: GameDatabase,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PaginationManager"
    }

    private val pageCache = ConcurrentHashMap<String, CachedPage<*>>()
    private val cursorIndex = ConcurrentHashMap<String, CursorInfo>()
    
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    private val loaders = ConcurrentHashMap<String, PageLoader<*>>()

    sealed class LoadingState {
        object Idle : LoadingState()
        data class Loading(val page: Int) : LoadingState()
        data class Error(val message: String) : LoadingState()
    }

    data class CachedPage<T>(
        val items: List<T>,
        val page: Int,
        val pageSize: Int,
        val timestamp: Long,
        val filter: String?,
        val orderBy: String?
    )

    init {
        registerDefaultLoaders()
    }

    private fun registerDefaultLoaders() {
        registerLoader("disciple", DisciplePageLoader(database))
        registerLoader("equipment", EquipmentPageLoader(database))
        registerLoader("battle_log", BattleLogPageLoader(database))
        registerLoader("event", EventPageLoader(database))
    }

    fun <T : Any> registerLoader(tableName: String, loader: PageLoader<T>) {
        loaders[tableName] = loader
    }

    suspend fun <T : Any> loadPaginated(
        tableName: String,
        slot: Int,
        pageSize: Int = PaginationConfig.DEFAULT_PAGE_SIZE,
        filter: String? = null,
        orderBy: String? = null,
        page: Int = 1
    ): PageResult<T> {
        val cacheKey = buildCacheKey(tableName, slot, filter, orderBy)
        
        val cachedPage = getCachedPage<T>(cacheKey, page, pageSize)
        if (cachedPage != null) {
            return cachedPage
        }
        
        _loadingState.value = LoadingState.Loading(page)
        
        return try {
            @Suppress("UNCHECKED_CAST")
            val loader = loaders[tableName] as? PageLoader<T>
                ?: throw IllegalArgumentException("No loader registered for table: $tableName")
            
            val request = PageRequest(
                page = page,
                pageSize = pageSize.coerceAtMost(PaginationConfig.MAX_PAGE_SIZE),
                filter = filter,
                orderBy = orderBy
            )
            
            val result = loader.loadPage(request)
            
            cachePage(cacheKey, result)
            updateCursorIndex(cacheKey, result)
            
            _loadingState.value = LoadingState.Idle
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load paginated data for $tableName", e)
            _loadingState.value = LoadingState.Error(e.message ?: "Unknown error")
            PageResult(
                items = emptyList(),
                page = page,
                pageSize = pageSize,
                totalCount = 0,
                totalPages = 0,
                hasNextPage = false,
                hasPreviousPage = false
            )
        }
    }

    suspend fun <T : Any> loadNextPage(
        tableName: String,
        slot: Int,
        currentPage: Int,
        pageSize: Int = PaginationConfig.DEFAULT_PAGE_SIZE,
        filter: String? = null,
        orderBy: String? = null
    ): PageResult<T> {
        return loadPaginated(tableName, slot, pageSize, filter, orderBy, currentPage + 1)
    }

    suspend fun <T : Any> loadPreviousPage(
        tableName: String,
        slot: Int,
        currentPage: Int,
        pageSize: Int = PaginationConfig.DEFAULT_PAGE_SIZE,
        filter: String? = null,
        orderBy: String? = null
    ): PageResult<T> {
        return if (currentPage > 1) {
            loadPaginated(tableName, slot, pageSize, filter, orderBy, currentPage - 1)
        } else {
            PageResult(
                items = emptyList(),
                page = 1,
                pageSize = pageSize,
                totalCount = 0,
                totalPages = 0,
                hasNextPage = false,
                hasPreviousPage = false
            )
        }
    }

    suspend fun <T : Any> streamData(
        tableName: String,
        slot: Int,
        pageSize: Int = PaginationConfig.STREAM_BATCH_SIZE,
        filter: String? = null,
        orderBy: String? = null
    ): Flow<List<T>> = kotlinx.coroutines.flow.flow {
        var page = 1
        var hasMore = true
        
        while (hasMore) {
            val result = loadPaginated<T>(tableName, slot, pageSize, filter, orderBy, page)
            if (result.items.isNotEmpty()) {
                emit(result.items)
            }
            hasMore = result.hasNextPage
            page++
        }
    }

    suspend fun <T : Any> loadByCursor(
        tableName: String,
        cursor: String,
        direction: CursorDirection,
        limit: Int = PaginationConfig.DEFAULT_PAGE_SIZE
    ): List<T> {
        @Suppress("UNCHECKED_CAST")
        val loader = loaders[tableName] as? PageLoader<T>
            ?: throw IllegalArgumentException("No loader registered for table: $tableName")
        
        return when (direction) {
            CursorDirection.AFTER -> loader.loadAfter(cursor, limit)
            CursorDirection.BEFORE -> loader.loadBefore(cursor, limit)
        }
    }

    fun prefetchPages(
        tableName: String,
        slot: Int,
        currentPage: Int,
        pageSize: Int = PaginationConfig.DEFAULT_PAGE_SIZE,
        filter: String? = null,
        orderBy: String? = null
    ) {
        scope.launch {
            val pagesToPrefetch = (1..PaginationConfig.PREFETCH_DISTANCE).map { offset ->
                currentPage + offset
            }
            
            pagesToPrefetch.forEach { page ->
                try {
                    loadPaginated<Any>(tableName, slot, pageSize, filter, orderBy, page)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to prefetch page $page for $tableName", e)
                }
            }
        }
    }

    fun invalidateCache(tableName: String, slot: Int) {
        val prefix = buildCacheKey(tableName, slot, null, null)
        pageCache.keys.removeAll { it.startsWith(prefix) }
        cursorIndex.keys.removeAll { it.startsWith(prefix) }
    }

    fun clearCache() {
        pageCache.clear()
        cursorIndex.clear()
    }

    private fun buildCacheKey(tableName: String, slot: Int, filter: String?, orderBy: String?): String {
        return "${tableName}_${slot}_${filter ?: "all"}_${orderBy ?: "default"}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getCachedPage(cacheKey: String, page: Int, pageSize: Int): PageResult<T>? {
        val key = "${cacheKey}_$page"
        val cached = pageCache[key] as? CachedPage<T> ?: return null
        
        if (System.currentTimeMillis() - cached.timestamp > 60_000) {
            pageCache.remove(key)
            return null
        }
        
        if (cached.pageSize != pageSize) {
            return null
        }
        
        val totalCount = cursorIndex[cacheKey]?.itemCount ?: return null
        val totalPages = (totalCount + pageSize - 1) / pageSize
        
        return PageResult(
            items = cached.items,
            page = page,
            pageSize = pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            hasNextPage = page < totalPages,
            hasPreviousPage = page > 1
        )
    }

    private fun <T : Any> cachePage(cacheKey: String, result: PageResult<T>) {
        val key = "${cacheKey}_${result.page}"
        pageCache[key] = CachedPage(
            items = result.items,
            page = result.page,
            pageSize = result.pageSize,
            timestamp = System.currentTimeMillis(),
            filter = null,
            orderBy = null
        )
        
        cleanupOldPages(cacheKey)
    }

    private fun updateCursorIndex(cacheKey: String, result: PageResult<*>) {
        val existing = cursorIndex[cacheKey]
        if (existing == null || existing.itemCount != result.totalCount) {
            cursorIndex[cacheKey] = CursorInfo(
                cursor = "${result.page}_${result.pageSize}",
                timestamp = System.currentTimeMillis(),
                itemCount = result.totalCount
            )
        }
    }

    private fun cleanupOldPages(cacheKey: String) {
        val pages = pageCache.keys.filter { it.startsWith(cacheKey) }.sorted()
        if (pages.size > PaginationConfig.CACHE_PAGE_COUNT) {
            pages.take(pages.size - PaginationConfig.CACHE_PAGE_COUNT).forEach {
                pageCache.remove(it)
            }
        }
    }

    fun getCacheStats(): CacheStats {
        return CacheStats(
            pageCount = pageCache.size,
            cursorCount = cursorIndex.size,
            estimatedSize = pageCache.values.sumOf { 
                (it as? CachedPage<*>)?.items?.size ?: 0 
            }
        )
    }

    data class CacheStats(
        val pageCount: Int,
        val cursorCount: Int,
        val estimatedSize: Int
    )

    enum class CursorDirection {
        AFTER,
        BEFORE
    }

    fun shutdown() {
        clearCache()
        Log.i(TAG, "PaginationManager shutdown completed")
    }
}

class DisciplePageLoader(private val database: GameDatabase) : PageLoader<Disciple> {
    override suspend fun loadPage(request: PageRequest): PageResult<Disciple> {
        val allItems = database.discipleDao().getAllAliveSync()
        val totalCount = allItems.size
        val offset = (request.page - 1) * request.pageSize
        val items = allItems.drop(offset).take(request.pageSize)
        val totalPages = (totalCount + request.pageSize - 1) / request.pageSize
        
        return PageResult(
            items = items,
            page = request.page,
            pageSize = request.pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            hasNextPage = request.page < totalPages,
            hasPreviousPage = request.page > 1
        )
    }
    
    override suspend fun loadAfter(cursor: String, limit: Int): List<Disciple> {
        val allItems = database.discipleDao().getAllAliveSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index < 0) return emptyList()
        return allItems.drop(index + 1).take(limit)
    }
    
    override suspend fun loadBefore(cursor: String, limit: Int): List<Disciple> {
        val allItems = database.discipleDao().getAllAliveSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index <= 0) return emptyList()
        val startIndex = maxOf(0, index - limit)
        return allItems.subList(startIndex, index)
    }
    
    override suspend fun getTotalCount(): Int = database.discipleDao().getAllAliveSync().size
}

class EquipmentPageLoader(private val database: GameDatabase) : PageLoader<Equipment> {
    override suspend fun loadPage(request: PageRequest): PageResult<Equipment> {
        val allItems = database.equipmentDao().getAllSync()
        val totalCount = allItems.size
        val offset = (request.page - 1) * request.pageSize
        val items = allItems.drop(offset).take(request.pageSize)
        val totalPages = (totalCount + request.pageSize - 1) / request.pageSize
        
        return PageResult(
            items = items,
            page = request.page,
            pageSize = request.pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            hasNextPage = request.page < totalPages,
            hasPreviousPage = request.page > 1
        )
    }
    
    override suspend fun loadAfter(cursor: String, limit: Int): List<Equipment> {
        val allItems = database.equipmentDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index < 0) return emptyList()
        return allItems.drop(index + 1).take(limit)
    }
    
    override suspend fun loadBefore(cursor: String, limit: Int): List<Equipment> {
        val allItems = database.equipmentDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index <= 0) return emptyList()
        val startIndex = maxOf(0, index - limit)
        return allItems.subList(startIndex, index)
    }
    
    override suspend fun getTotalCount(): Int = database.equipmentDao().getAllSync().size
}

class BattleLogPageLoader(private val database: GameDatabase) : PageLoader<BattleLog> {
    override suspend fun loadPage(request: PageRequest): PageResult<BattleLog> {
        val allItems = database.battleLogDao().getAllSync()
        val totalCount = allItems.size
        val offset = (request.page - 1) * request.pageSize
        val items = allItems.drop(offset).take(request.pageSize)
        val totalPages = (totalCount + request.pageSize - 1) / request.pageSize
        
        return PageResult(
            items = items,
            page = request.page,
            pageSize = request.pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            hasNextPage = request.page < totalPages,
            hasPreviousPage = request.page > 1
        )
    }
    
    override suspend fun loadAfter(cursor: String, limit: Int): List<BattleLog> {
        val allItems = database.battleLogDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index < 0) return emptyList()
        return allItems.drop(index + 1).take(limit)
    }
    
    override suspend fun loadBefore(cursor: String, limit: Int): List<BattleLog> {
        val allItems = database.battleLogDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index <= 0) return emptyList()
        val startIndex = maxOf(0, index - limit)
        return allItems.subList(startIndex, index)
    }
    
    override suspend fun getTotalCount(): Int = database.battleLogDao().getAllSync().size
}

class EventPageLoader(private val database: GameDatabase) : PageLoader<GameEvent> {
    override suspend fun loadPage(request: PageRequest): PageResult<GameEvent> {
        val allItems = database.gameEventDao().getAllSync()
        val totalCount = allItems.size
        val offset = (request.page - 1) * request.pageSize
        val items = allItems.drop(offset).take(request.pageSize)
        val totalPages = (totalCount + request.pageSize - 1) / request.pageSize
        
        return PageResult(
            items = items,
            page = request.page,
            pageSize = request.pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            hasNextPage = request.page < totalPages,
            hasPreviousPage = request.page > 1
        )
    }
    
    override suspend fun loadAfter(cursor: String, limit: Int): List<GameEvent> {
        val allItems = database.gameEventDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index < 0) return emptyList()
        return allItems.drop(index + 1).take(limit)
    }
    
    override suspend fun loadBefore(cursor: String, limit: Int): List<GameEvent> {
        val allItems = database.gameEventDao().getAllSync()
        val index = allItems.indexOfFirst { it.id == cursor }
        if (index <= 0) return emptyList()
        val startIndex = maxOf(0, index - limit)
        return allItems.subList(startIndex, index)
    }
    
    override suspend fun getTotalCount(): Int = database.gameEventDao().getAllSync().size
}
