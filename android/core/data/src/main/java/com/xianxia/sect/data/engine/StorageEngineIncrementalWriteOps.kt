package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.state.SaveDirtySet
import com.xianxia.sect.core.state.SaveDirtyTables
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.GameHeavyDataDao
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageResult
import androidx.room.withTransaction

// StorageEngine 的增量写域（SS5）：变化行 upsert + id 双向对账 + heavy key 粒度
// 跳过/重编码 + 配方派生表条件重写。写入口接收者为 GameDatabase（与全量写面
// 同口径，直测免引擎装配）；事务边界由调用方 performTransactionSave 承担。
// 按域独立成文件（模块既有拆分惯例，同 MailOps/HeavyDataOps 口径）。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

/**
 * 增量路径（默认）：变化行 upsert（只传脏 id）+ 删除集对账 delete + 未变
 * heavy key 跳过（不删不写）+ 变动 heavy key 整 key 重编码；轻量行/域表/
 * 邮件/生产槽恒整写（小表），配方表仅在派生源变化时整表重写。
 *
 * 行级正确性双保险：
 * 1. 脏 id（[SaveDirtySet.upsertIds]）行按快照内容 upsert；
 * 2. 每张行级表做「已落盘 id ↔ 快照 id」双向对账——快照缺失的已落盘行删除，
 *    快照持有而 DB 缺失的行补写（归档调度搬行/异常缺行的结构性自愈）。
 * 未触达的行零写放大；未变 heavy key 零编码零删除（本批性能收益核心）。
 */
internal suspend fun GameDatabase.writeIncrementalDataToDatabase(
    data: SaveData,
    dirty: SaveDirtySet
): StorageResult<Unit> {
    Log.d(TAG, "writeIncrementalDataToDatabase: " +
        "${data.disciples.size} disciples, " +
        "dirtyTables=${dirty.upsertIds.keys.size}, heavyKeys=${dirty.heavyKeys.size}, " +
        "recruitList=${data.gameData.recruitList.size} unrecruited")

    if (data.gameData.worldMapSects.isEmpty()) {
        Log.e(TAG, "存档前检测到 worldMapSects 为空 — 数据管线异常，" +
            "世界地图宗门数据已丢失。请检查 ensureHeavyDataLoaded 日志。")
    }
    if (data.gameData.sectName.isBlank()) {
        Log.w(TAG, "存档前检测到 sectName 为空")
    }

    writeChangedHeavyData(data, dirty.heavyKeys)

    val lightGameData = buildLightGameData(data)
    withTransaction {
        gameDataDao().insert(lightGameData)
        writeDomainEntities(data)
        // 邮件快照读自写前同一 DB（SR-1 整对象替换），整写为同内容回填
        mailDao().deleteAll()
        writeMails(data)
        rewriteProductionSlotsAndRecipes(data, dirty)
        reconcileDisciples(data, dirty)
        reconcileItemCollections(data, dirty)
        reconcileLogAndBagCollections(data, dirty)
    }

    return StorageResult.success(Unit)
}

/**
 * 变动 heavy key 整 key 重编码；未变 key 直接跳过（不删不写）。
 * 读档被跳过的 key 内存值为空、不得重写——重写即以空值覆盖 DB 完整数据
 * （审计 §12-A），此处 fail-fast 断言承载该语义（全量路径的对应防线见
 * clearHeavyDataByPrefix 的排除逻辑）。
 */
internal suspend fun GameDatabase.writeChangedHeavyData(data: SaveData, heavyKeys: Set<String>) {
    val heavyDao = gameHeavyDataDao()
    for (key in GameHeavyData.ALL_KEYS) {
        if (key !in heavyKeys) continue
        checkForLoadSkippedHeavyKey(key)
        heavyDao.deleteByKeyPrefix(key)
        encodeHeavyKey(heavyDao, key, data)
    }
}

/** 读档跳过集断言：被跳过的 heavy key 不允许进入重编码清单。 */
private fun checkForLoadSkippedHeavyKey(key: String) {
    check(key !in skippedHeavyKeys) {
        "heavy key '$key' 在读档时被跳过（内存值为空）却被标脏重写——" +
            "以空值覆盖 DB 完整数据属数据丢失回归（审计 §12-A）"
    }
}

/** 按 key 分发重编码（与全量写面 writeHeavyDataIncremental 逐 key 等价）。 */
private suspend fun GameDatabase.encodeHeavyKey(
    heavyDao: GameHeavyDataDao,
    key: String,
    data: SaveData
) {
    when (key) {
        GameHeavyData.KEY_AI_SECT_DISCIPLES -> ProtobufConverters.encodeDiscipleListMapIncremental(
            data.gameData.aiSectDisciples, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_SECT_DETAILS -> ProtobufConverters.encodeSectDetailMapIncremental(
            data.gameData.sectDetails, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_EXPLORED_SECTS -> ProtobufConverters.encodeExploredSectInfoMapIncremental(
            data.gameData.exploredSects, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_SCOUT_INFO -> ProtobufConverters.encodeSectScoutInfoMapIncremental(
            data.gameData.scoutInfo, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_MANUAL_PROFICIENCIES ->
            ProtobufConverters.encodeManualProficiencyMapIncremental(
                data.gameData.manualProficiencies, key
            ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_RECRUIT_LIST -> ProtobufConverters.encodeDiscipleListIncremental(
            data.gameData.recruitList, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        GameHeavyData.KEY_WORLD_MAP_SECTS -> ProtobufConverters.encodeWorldSectListIncremental(
            data.gameData.worldMapSects, key
        ) { chunks -> heavyDao.upsertAll(chunks) }
        else -> error("未知 heavy key: $key")
    }
}

/**
 * 生产槽与配方的增量写：生产槽整删后整写（与全量路径等价，防仓储面删槽残留）；
 * 配方由 gameData.unlockedRecipes 派生，仅在派生源标记重写时整表重写。
 */
private suspend fun GameDatabase.rewriteProductionSlotsAndRecipes(
    data: SaveData,
    dirty: SaveDirtySet
) {
    productionSlotDao().deleteAll()
    data.productionSlots.chunked(MAX_BATCH_SIZE).forEach { batch ->
        productionSlotDao().upsertAll(batch)
    }
    if (SaveDirtyTables.RECIPES in dirty.rewriteTables) {
        recipeDao().deleteAll()
        data.gameData.unlockedRecipes?.map { recipeRowOf(it) }?.let { recipes ->
            recipeDao().upsertAll(recipes)
        }
    }
}

/** 弟子表增量对账（SS5 核心：脏 id upsert + 双向 id 对账）。 */
private suspend fun GameDatabase.reconcileDisciples(data: SaveData, dirty: SaveDirtySet) {
    reconcileCollection(
        table = SaveDirtyTables.DISCIPLES,
        snapshotRows = data.disciples,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.DISCIPLES],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { discipleDao().upsertAll(it) } },
        persistedIds = { discipleDao().getAllIds() },
        deleteByIds = { ids -> ids.chunked(MAX_BATCH_SIZE).forEach { discipleDao().deleteByIds(it) } }
    )
}

/** 物品族表增量对账（装备/功法堆叠/功法实例/丹药/材料）。 */
private suspend fun GameDatabase.reconcileItemCollections(data: SaveData, dirty: SaveDirtySet) {
    reconcileCollection(
        table = SaveDirtyTables.EQUIPMENT_INSTANCES,
        snapshotRows = data.equipmentInstances,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.EQUIPMENT_INSTANCES],
        upsert = { rows ->
            rows.chunked(MAX_BATCH_SIZE).forEach { equipmentInstanceDao().upsertAll(it) }
        },
        persistedIds = { equipmentInstanceDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { equipmentInstanceDao().deleteByIds(it) }
        }
    )
    reconcileCollection(
        table = SaveDirtyTables.MANUAL_STACKS,
        snapshotRows = data.manualStacks,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.MANUAL_STACKS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { manualStackDao().upsertAll(it) } },
        persistedIds = { manualStackDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { manualStackDao().deleteByIds(it) }
        }
    )
    reconcileCollection(
        table = SaveDirtyTables.MANUAL_INSTANCES,
        snapshotRows = data.manualInstances,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.MANUAL_INSTANCES],
        upsert = { rows ->
            rows.chunked(MAX_BATCH_SIZE).forEach { manualInstanceDao().upsertAll(it) }
        },
        persistedIds = { manualInstanceDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { manualInstanceDao().deleteByIds(it) }
        }
    )
    reconcileCollection(
        table = SaveDirtyTables.PILLS,
        snapshotRows = data.pills,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.PILLS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { pillDao().upsertAll(it) } },
        persistedIds = { pillDao().getAllIds() },
        deleteByIds = { ids -> ids.chunked(MAX_BATCH_SIZE).forEach { pillDao().deleteByIds(it) } }
    )
    reconcileCollection(
        table = SaveDirtyTables.MATERIALS,
        snapshotRows = data.materials,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.MATERIALS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { materialDao().upsertAll(it) } },
        persistedIds = { materialDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { materialDao().deleteByIds(it) }
        }
    )
}

/** 灵植/种子/储物袋/战报表增量对账。 */
private suspend fun GameDatabase.reconcileLogAndBagCollections(
    data: SaveData,
    dirty: SaveDirtySet
) {
    reconcileCollection(
        table = SaveDirtyTables.HERBS,
        snapshotRows = data.herbs,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.HERBS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { herbDao().upsertAll(it) } },
        persistedIds = { herbDao().getAllIds() },
        deleteByIds = { ids -> ids.chunked(MAX_BATCH_SIZE).forEach { herbDao().deleteByIds(it) } }
    )
    reconcileCollection(
        table = SaveDirtyTables.SEEDS,
        snapshotRows = data.seeds,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.SEEDS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { seedDao().upsertAll(it) } },
        persistedIds = { seedDao().getAllIds() },
        deleteByIds = { ids -> ids.chunked(MAX_BATCH_SIZE).forEach { seedDao().deleteByIds(it) } }
    )
    reconcileCollection(
        table = SaveDirtyTables.STORAGE_BAGS,
        snapshotRows = data.storageBags,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.STORAGE_BAGS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { storageBagDao().upsertAll(it) } },
        persistedIds = { storageBagDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { storageBagDao().deleteByIds(it) }
        }
    )
    reconcileCollection(
        table = SaveDirtyTables.BATTLE_LOGS,
        snapshotRows = data.battleLogs,
        idOf = { it.id },
        dirtyIds = dirty.upsertIds[SaveDirtyTables.BATTLE_LOGS],
        upsert = { rows -> rows.chunked(MAX_BATCH_SIZE).forEach { battleLogDao().upsertAll(it) } },
        persistedIds = { battleLogDao().getAllIds() },
        deleteByIds = { ids ->
            ids.chunked(MAX_BATCH_SIZE).forEach { battleLogDao().deleteByIds(it) }
        }
    )
}

/**
 * 单表对账：脏行 upsert（只传变化 id）+ 删除集（已落盘 − 快照）批删 +
 * 快照缺失行补写。全部发生在调用方事务内。
 */
private suspend fun <T> GameDatabase.reconcileCollection(
    table: String,
    snapshotRows: List<T>,
    idOf: (T) -> String,
    dirtyIds: Set<String>?,
    upsert: suspend (List<T>) -> Unit,
    persistedIds: suspend () -> List<String>,
    deleteByIds: suspend (List<String>) -> Unit
) {
    val snapshotById = HashMap<String, T>(snapshotRows.size)
    for (row in snapshotRows) snapshotById[idOf(row)] = row

    var upserted = 0
    if (!dirtyIds.isNullOrEmpty()) {
        val changedRows = dirtyIds.mapNotNull { snapshotById[it] }
        if (changedRows.isNotEmpty()) {
            upsert(changedRows)
            upserted = changedRows.size
        }
    }

    val persisted = persistedIds()
    val persistedSet = persisted.toHashSet()
    val deletedIds = persisted.filterNot { it in snapshotById }
    val missingIds = snapshotById.keys.filterNot { it in persistedSet }
    if (deletedIds.isNotEmpty()) deleteByIds(deletedIds)
    if (missingIds.isNotEmpty()) {
        upsert(missingIds.mapNotNull { snapshotById[it] })
    }
    if (upserted > 0 || deletedIds.isNotEmpty() || missingIds.isNotEmpty()) {
        Log.d(TAG, "reconcileCollection[$table]: upsert=$upserted, " +
            "deleted=${deletedIds.size}, backfilled=${missingIds.size}")
    }
}

/** 配方行构造（与全量写面 writeProductionSlotsAndRecipes 同一派生式）。 */
private fun recipeRowOf(id: String) = com.xianxia.sect.core.model.Recipe(id)
