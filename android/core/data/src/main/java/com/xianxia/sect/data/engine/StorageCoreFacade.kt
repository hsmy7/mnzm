package com.xianxia.sect.data.engine

import com.xianxia.sect.data.cache.CacheLayer
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的核心持久化依赖（数据库、缓存、槽位锁），
 * 将 StorageEngine 构造参数减少 2 个。
 *
 * 事务编排由 Room 事务（`database.withTransaction`）承担，无应用级事务日志。
 */
@Singleton
class StorageCoreFacade @Inject constructor(
    val database: GameDatabase,
    val cache: CacheLayer,
    val lockManager: SlotLockManager
)
