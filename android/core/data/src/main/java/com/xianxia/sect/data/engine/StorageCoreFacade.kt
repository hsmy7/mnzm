package com.xianxia.sect.data.engine

import com.xianxia.sect.data.cache.CacheLayer
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.wal.WALProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的核心持久化依赖（数据库、缓存、WAL、槽位锁），
 * 将 StorageEngine 构造参数减少 3 个。
 */
@Singleton
class StorageCoreFacade @Inject constructor(
    val database: GameDatabase,
    val cache: CacheLayer,
    val wal: WALProvider,
    val lockManager: SlotLockManager
)
