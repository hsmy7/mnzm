package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.util.BagMaterializeInput
import com.xianxia.sect.core.util.StorageBagMaterializer
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// StorageEngine 的重数据域:合并恢复/域表回填/解码/实体装载与槽位隔离处置。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

private const val LOW_MEMORY_THRESHOLD_MB = StorageEngine.LOW_MEMORY_THRESHOLD_MB

/**
 * 一次重型数据安全读取的结果：成功读取的行 + 因单行超 CursorWindow 被跳过的 key。
 */
internal data class HeavyDataLoadReport(
    val rows: List<GameHeavyData>,
    val skippedKeys: Set<String>
)

/**
 * 进程内、按 slot 记录"最近一次读档被跳过的 heavy key"。
 *
 * 被跳过的 key 其内存值为空，若下一次保存照常 `deleteByKeyPrefix` 再用空值重写，
 * 会**永久覆盖** DB 中的完整数据（exploredSects/scoutInfo 等无再生源）。
 * clearHeavyDataByPrefix 据此排除这些 key，保住 DB 原值。
 */
internal val skippedHeavyKeysBySlot = ConcurrentHashMap<Int, MutableSet<String>>()

internal suspend fun StorageEngine.mergeHeavyData(gameData: GameData, slot: Int): GameData {
    val report = loadHeavyDataSafeWithReport(slot)
    val allRows = report.rows

    // heavy_data 表无数据时，依次从 domain state 表 fallback 恢复所有重型字段。
    // 这 5 个 domain state 表与 heavy_data 在同一事务中写入（writeAllDataToDatabase
    // Phase B 第 740-791 行），若 heavy_data 因写入中断丢失，domain state 表仍有完整数据。
    // 防止写入中断后重型字段永久为空（世界地图空白/招募列表为空等）。
    if (allRows.isEmpty()) {
        val restored = restoreHeavyDataFromDomainTables(gameData = gameData, slot = slot)
        if (restored != null) return restored
        Log.w(TAG, "mergeHeavyData: all domain state tables empty for slot $slot")
        return gameData
    }

    val decoded = decodeHeavyDataFromRows(gameData = gameData, allRows = allRows)

    // 有 key 被跳过（超 CursorWindow）或 7 个 heavy key 任一缺失时，用 domain state
    // 表回填**缺失**的 key（只补缺失，不覆盖已存在）。审计 §12-A 关键防线：
    // 被跳过的 key 内存值为空，若原样存回会永久抹掉 DB 中的完整数据。
    val presentKeys = GameHeavyData.ALL_KEYS.filter { prefix ->
        allRows.any { it.dataKey == prefix || it.dataKey.startsWith("$prefix/") }
    }.toSet()
    val missingKeys = keysNeedingBackfill(GameHeavyData.ALL_KEYS, presentKeys)
    if (missingKeys.isNotEmpty()) {
        Log.w(TAG, "mergeHeavyData: slot $slot missing heavy keys=$missingKeys, " +
            "skipped=${report.skippedKeys}, backfilling from domain tables")
        return restoreMissingHeavyKeysFromDomainTables(decoded, slot, missingKeys.toSet())
    }
    if (report.skippedKeys.isNotEmpty()) {
        Log.w(TAG, "mergeHeavyData: slot $slot skipped=${report.skippedKeys} " +
            "but no heavy key missing from rows; nothing to backfill")
    }
    return decoded
}

/**
 * 从 domain state 表恢复重型字段：heavy_data 表为空时
 * 兜底恢复世界地图/外交/生产等字段。
 *
 * @return 恢复后的数据；无可用 fallback 数据时返回 null
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend fun StorageEngine.restoreHeavyDataFromDomainTables(gameData: GameData, slot: Int): GameData? {
    val worldMapEntity = try {
        core.database.worldMapStateDao().getBySlot(slot)
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
          Log.w(TAG, "mergeHeavyData: worldMapStateDao fallback failed", e)
          null
      }
    val diplomacyEntity = try {
        core.database.diplomacyStateDao().getBySlot(slot)
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
          Log.w(TAG, "mergeHeavyData: diplomacyStateDao fallback failed", e)
          null
      }
    val productionEntity = try {
        core.database.productionStateDao().getBySlot(slot)
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
          Log.w(TAG, "mergeHeavyData: productionStateDao fallback failed", e)
          null
      }

    val hasFallbackData = worldMapEntity?.worldMapSects?.isNotEmpty() == true
    if (hasFallbackData) {
        Log.w(TAG, "mergeHeavyData: heavy_data empty for slot $slot, " +
            "falling back to domain state tables " +
            "(worldSects=${worldMapEntity?.worldMapSects?.size}, " +
            "sectDetails=${diplomacyEntity?.sectDetails?.size}, " +
            "manualProficiencies=${productionEntity?.manualProficiencies?.size})")
        return gameData.copy(
            worldMapSects = worldMapEntity?.worldMapSects ?: gameData.worldMapSects,
            aiSectDisciples = worldMapEntity?.aiSectDisciples ?: gameData.aiSectDisciples,
            sectDetails = diplomacyEntity?.sectDetails ?: gameData.sectDetails,
            exploredSects = diplomacyEntity?.exploredSects ?: gameData.exploredSects,
            scoutInfo = diplomacyEntity?.scoutInfo ?: gameData.scoutInfo,
            manualProficiencies = productionEntity?.manualProficiencies ?: gameData.manualProficiencies
            // recruitList 无 domain state 表可恢复，保持 gameData 原有值
        )
    }
    return null
}

/**
 * 从 heavy_data 行解码重型字段：已有值保持，空值按 key 解码填充。
 */
internal fun StorageEngine.decodeHeavyDataFromRows(gameData: GameData, allRows: List<GameHeavyData>): GameData {
    return gameData.copy(
        aiSectDisciples = if (gameData.aiSectDisciples.isEmpty())
            ProtobufConverters.decodeDiscipleListMapFromRows(allRows, GameHeavyData.KEY_AI_SECT_DISCIPLES)
        else gameData.aiSectDisciples,

        sectDetails = if (gameData.sectDetails.isEmpty())
            ProtobufConverters.decodeSectDetailMapFromRows(allRows, GameHeavyData.KEY_SECT_DETAILS)
        else gameData.sectDetails,

        exploredSects = if (gameData.exploredSects.isEmpty())
            ProtobufConverters.decodeExploredSectInfoMapFromRows(allRows, GameHeavyData.KEY_EXPLORED_SECTS)
        else gameData.exploredSects,

        scoutInfo = if (gameData.scoutInfo.isEmpty())
            ProtobufConverters.decodeSectScoutInfoMapFromRows(allRows, GameHeavyData.KEY_SCOUT_INFO)
        else gameData.scoutInfo,

        manualProficiencies = if (gameData.manualProficiencies.isEmpty())
            ProtobufConverters.decodeManualProficiencyMapFromRows(allRows, GameHeavyData.KEY_MANUAL_PROFICIENCIES)
        else gameData.manualProficiencies,

        recruitList = if (gameData.recruitList.isEmpty())
            ProtobufConverters.decodeDiscipleListFromRows(allRows, GameHeavyData.KEY_RECRUIT_LIST)
        else gameData.recruitList,

        worldMapSects = if (gameData.worldMapSects.isEmpty())
            ProtobufConverters.decodeWorldSectListFromRows(allRows, GameHeavyData.KEY_WORLD_MAP_SECTS)
        else gameData.worldMapSects
    )
}

/**
 * 需要域表回填的 key = [allKeys] 中不在 [presentKeys] 的 key（缺失或被跳过）。
 * 纯函数，便于直测。
 */
internal fun keysNeedingBackfill(allKeys: List<String>, presentKeys: Set<String>): List<String> =
    allKeys.filterNot { it in presentKeys }

/** 薄封装：仅返回行，供既有调用点（loadHeavyDataForSlot 等）使用。 */
internal suspend fun StorageEngine.loadHeavyDataSafe(slot: Int): List<GameHeavyData> =
    loadHeavyDataSafeWithReport(slot).rows

/**
 * 安全加载重型数据：逐 key 读取，跳过超过 CursorWindow 限制的单行，并**报告**被跳过的 key。
 *
 * 被跳过的行保留在 DB（不删），同时把 key 记入 [skippedHeavyKeysBySlot]，
 * 使下一次保存的 clearHeavyDataByPrefix 跳过它，避免用空内存值覆盖 DB 完整数据。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun StorageEngine.loadHeavyDataSafeWithReport(slot: Int): HeavyDataLoadReport {
    val keys = try {
        core.database.gameHeavyDataDao().getLoadedKeys(slot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load heavy data keys for slot $slot, skipping", e)
        return HeavyDataLoadReport(emptyList(), emptySet())
    }

    val result = mutableListOf<GameHeavyData>()
    val skipped = mutableSetOf<String>()
    for (key in keys) {
        try {
            val row = core.database.gameHeavyDataDao().getByKey(slot, key)
            if (row != null) result.add(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 超大行跳过不删——删除会静默丢失数据：sectDetails/exploredSects/
            // scoutInfo 等无再生源（ensureGameDataIntegrity 仅告警不重生），
            // 删除后该数据永久消失。跳过保持 DB 原样，并登记 skipped 以防
            // 下次保存用空内存值覆盖（见 clearHeavyDataByPrefix）。
            skipped.add(key)
            Log.w(TAG, "Heavy data key '$key' exceeds CursorWindow limit, skipping (kept in DB)", e)
        }
    }

    if (skipped.isEmpty()) {
        skippedHeavyKeysBySlot.remove(slot)
    } else {
        skippedHeavyKeysBySlot[slot] = skipped
    }
    return HeavyDataLoadReport(result, skipped)
}

internal suspend fun StorageEngine.buildSaveDataFromDatabase(slot: Int,
    gameData: GameData): SaveData = withContext(Dispatchers.IO) {
    val loaded = loadAllEntities(slot = slot)

    val alliances = gameData.alliances ?: emptyList()

    val productionSlots = productionSlotsWithFallback(
        productionSlots = loaded.productionSlots,
        gameData = gameData
    )

    // 储物袋独立存储兼容：老存档引用式袋条目（payload==null）物化为持有数据，
    // 并从仓库扣减对应数量（防复制）；悬空条目直接删除。物化幂等。
    val materialized = StorageBagMaterializer.materializeDiscipleBagItems(
        BagMaterializeInput(
            disciples = loaded.disciples,
            equipmentStacks = loaded.equipmentStacks,
            equipmentInstances = loaded.equipmentInstances,
            manualStacks = loaded.manualStacks,
            manualInstances = loaded.manualInstances,
            pills = loaded.pills,
            materials = loaded.materials,
            herbs = loaded.herbs,
            seeds = loaded.seeds
        )
    )
    if (materialized.materializedCount > 0) {
        Log.i(TAG, "储物袋物化迁移 ${materialized.materializedCount} 条（D-03 独立存储）")
    }
    if (materialized.droppedCount > 0) {
        Log.w(TAG, "储物袋悬空条目清理 ${materialized.droppedCount} 条（引用不存在，防复制删除）")
    }

    SaveData(
        gameData = gameData,
        disciples = materialized.disciples,
        equipmentStacks = materialized.equipmentStacks,
        equipmentInstances = materialized.equipmentInstances,
        manualStacks = materialized.manualStacks,
        manualInstances = materialized.manualInstances,
        pills = materialized.pills,
        materials = materialized.materials,
        herbs = materialized.herbs,
        seeds = materialized.seeds,
        storageBags = loaded.storageBags,
        battleLogs = loaded.battleLogs,
        alliances = alliances,
        productionSlots = productionSlots,
        stacksSerialized = true
    ).also {
        Log.d(
            TAG,
            "loadFromDatabase: slot=$slot, ${loaded.disciples.size} disciples, " +
                "recruitList=${gameData.recruitList.size} unrecruited disciples"
        )
    }
}

/**
 * 并行读取槽位全量实体：async 并发 + await 汇总。
 */
internal suspend fun StorageEngine.loadAllEntities(slot: Int): DbLoadResult = withContext(Dispatchers.IO) {
    val deferredDisciples = async { core.database.discipleDao().getAllSync(slot) }
    val deferredEquipmentStacks = async { core.database.equipmentStackDao().getAllSync(slot) }
    val deferredEquipmentInstances = async { core.database.equipmentInstanceDao().getAllSync(slot) }
    val deferredManualStacks = async { core.database.manualStackDao().getAllSync(slot) }
    val deferredManualInstances = async { core.database.manualInstanceDao().getAllSync(slot) }
    val deferredPills = async { core.database.pillDao().getAllSync(slot) }
    val deferredMaterials = async { core.database.materialDao().getAllSync(slot) }
    val deferredHerbs = async { core.database.herbDao().getAllSync(slot) }
    val deferredSeeds = async { core.database.seedDao().getAllSync(slot) }
    val deferredStorageBags = async { core.database.storageBagDao().getAll(slot) }
    val deferredBattleLogs = async { core.database.battleLogDao().getAllSync(slot) }
    var deferredProductionSlots = async { core.database.productionSlotDao().getBySlotSync(slot) }

    DbLoadResult(
        disciples = deferredDisciples.await(),
        equipmentStacks = deferredEquipmentStacks.await(),
        equipmentInstances = deferredEquipmentInstances.await(),
        manualStacks = deferredManualStacks.await(),
        manualInstances = deferredManualInstances.await(),
        pills = deferredPills.await(),
        materials = deferredMaterials.await(),
        herbs = deferredHerbs.await(),
        seeds = deferredSeeds.await(),
        storageBags = deferredStorageBags.await(),
        battleLogs = deferredBattleLogs.await(),
        productionSlots = deferredProductionSlots.await()
    )
}

/**
 * 生产槽位回退：DB 查询为空时用 GameData 兜底。
 */
internal fun StorageEngine.productionSlotsWithFallback(
    productionSlots: List<ProductionSlot>,
    gameData: GameData
): List<ProductionSlot> {
    if (productionSlots.isEmpty()) {
        val fallbackSlots = gameData.productionSlots
        if (!fallbackSlots.isNullOrEmpty()) {
            Log.w(TAG, "Production slots empty in DB, using GameData fallback (${fallbackSlots.size} slots)")
            return fallbackSlots
        }
    }
    return productionSlots
}

/**
 * 备份路径物化兜底——老版本备份可能含引用式袋条目（payload 空），
 * 恢复后未物化会在取回（没收）等路径复制/丢失物品；物化幂等，写库前执行。
 *
 * @return 物化后的数据（引用式条目已铸造 payload，悬空条目已删除）
 */
internal fun SaveData.materializeRestoredBag(): SaveData {
    val bagMaterialized = StorageBagMaterializer.materializeDiscipleBagItems(
        BagMaterializeInput(
            disciples = disciples,
            equipmentStacks = equipmentStacks,
            equipmentInstances = equipmentInstances,
            manualStacks = manualStacks,
            manualInstances = manualInstances,
            pills = pills,
            materials = materials,
            herbs = herbs,
            seeds = seeds
        )
    )
    if (bagMaterialized.materializedCount > 0) {
        Log.i(TAG, "备份储物袋物化迁移 ${bagMaterialized.materializedCount} 条（D-03 独立存储）")
    }
    if (bagMaterialized.droppedCount > 0) {
        Log.w(TAG, "备份储物袋悬空条目清理 ${bagMaterialized.droppedCount} 条（引用不存在，防复制删除）")
    }
    return copy(
        disciples = bagMaterialized.disciples,
        equipmentStacks = bagMaterialized.equipmentStacks,
        equipmentInstances = bagMaterialized.equipmentInstances,
        manualStacks = bagMaterialized.manualStacks,
        manualInstances = bagMaterialized.manualInstances,
        pills = bagMaterialized.pills,
        materials = bagMaterialized.materials,
        herbs = bagMaterialized.herbs,
        seeds = bagMaterialized.seeds
    )
}

/**
 * 恢复前隔离当前数据库快照（`.quarantine.{timestamp}`）。
 *
 * .sav/.bak 备份整体覆写 DB 前保留当前库——校验器误判损坏时，较新的
 * DB 数据被旧备份覆盖不可逆；隔离文件供排查与手动恢复。失败非阻断
 *（恢复主流程不因隔离失败中止）。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun StorageEngine.quarantineCurrentDatabase() {
    try {
        val dbPath = core.database.openHelper.writableDatabase.path
        if (dbPath.isNullOrEmpty()) {
            Log.w(TAG, "无法获取数据库路径，跳过隔离")
            return
        }
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return
        val quarantine = File(dbPath + ".quarantine." + System.currentTimeMillis())
        dbFile.inputStream().use { input ->
            quarantine.outputStream().use { output -> input.copyTo(output) }
        }
        Log.w(TAG, "恢复前已隔离当前数据库: ${quarantine.name}")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "恢复前隔离当前数据库失败（非阻断）", e)
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
internal suspend fun StorageEngine.querySingleSlot(slot: Int): SaveSlot {
    return try {
        val meta = core.database.gameDataDao().getMetadataBySlot(slot)
        if (meta != null) {
            SaveSlot(
                slot = slot,
                name = "Save $slot",
                timestamp = meta.lastSaveTime,
                gameYear = meta.gameYear,
                gameMonth = meta.gameMonth,
                sectName = meta.sectName,
                discipleCount = core.database.discipleDao().getAliveCountSync(slot),
                spiritStones = meta.spiritStones,
                isEmpty = false,
                customName = meta.sectName,
            )
        } else {
            SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "querySingleSlot FAILED for slot $slot -- database may be unreachable or schema is mismatched",
            e)
        throw IllegalStateException("Failed to query save slot $slot: ${e.message}", e)
    }
}

/**
 * 一次槽位实体装载的行集合(loadAllEntities 的结构化结果载体)
 */
internal data class DbLoadResult(
    val disciples: List<Disciple>,
    val equipmentStacks: List<EquipmentStack>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val storageBags: List<StorageBag>,
    val battleLogs: List<BattleLog>,
    val productionSlots: List<ProductionSlot>
)
