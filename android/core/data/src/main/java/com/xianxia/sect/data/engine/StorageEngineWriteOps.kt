package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.DiplomacyState
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.PatrolStateEntity
import com.xianxia.sect.core.model.ProductionState
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.SectPolicyState
import com.xianxia.sect.core.model.WorldMapStateEntity
import com.xianxia.sect.data.local.GameHeavyDataDao
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageResult
import androidx.room.withTransaction

// StorageEngine 的数据库写层:轻量游戏数据行 + 重数据增写 + 核心实体/域实体族
// 写入与容量辅助。跨文件消费类内 internal 字段(同模块,行为零变更)。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

private const val LOW_MEMORY_THRESHOLD_MB = StorageEngine.LOW_MEMORY_THRESHOLD_MB

internal suspend fun StorageEngine.writeAllDataToDatabase(data: SaveData): StorageResult<Unit> {
    Log.d(TAG, "writeAllDataToDatabase: " +
        "${data.disciples.size} disciples, " +
        "recruitList=${data.gameData.recruitList.size} unrecruited")

    // ── 存档前数据完整性校验 ──
    if (data.gameData.worldMapSects.isEmpty()) {
        Log.e(TAG, "存档前检测到 worldMapSects 为空 — 数据管线异常，" +
            "世界地图宗门数据已丢失。请检查 ensureHeavyDataLoaded 日志。")
    }
    if (data.gameData.sectName.isBlank()) {
        Log.w(TAG, "存档前检测到 sectName 为空")
    }

    val heavyDao = core.database.gameHeavyDataDao()

    // 重型数据清理/写入/轻量实体写入分别提取（行为逐行一致）
    clearHeavyDataByPrefix(heavyDao)
    writeHeavyDataIncremental(heavyDao, data)

    // ── 轻型 GameData（所有大型字段已清空，TypeConverter 编码近乎零开销）──
    val lightGameData = buildLightGameData(data)
    core.database.withTransaction {
        clearOldEntities(data)
        writeCoreEntities(data, lightGameData)
        writeDomainEntities(data)
    }

    return StorageResult.success(Unit)
}

/**
 * 计算本次可安全清除的 heavy 前缀 = 全部 key 中排除"读档时被跳过"的 key。
 *
 * 被跳过的 key 其 DB 原值必须保留（读档跳过后内存值为空，若照常删除再用空值
 * 重写会永久覆盖）。纯函数，便于直测。
 */
internal fun heavyPrefixesToClear(allKeys: List<String>, skippedKeys: Set<String>): List<String> =
    allKeys.filterNot { it in skippedKeys }

/** 清除旧重型数据（按前缀批量删除；读档被跳过的 key 除外，见审计 §12-A）。 */
internal suspend fun StorageEngine.clearHeavyDataByPrefix(heavyDao: GameHeavyDataDao) {
    val skipped = skippedHeavyKeys
    val prefixes = heavyPrefixesToClear(GameHeavyData.ALL_KEYS, skipped)
    if (skipped.isNotEmpty()) {
        Log.w(TAG, "clearHeavyDataByPrefix: 保留被跳过的 heavy key $skipped，" +
            "避免用空内存值覆盖 DB 完整数据")
    }
    for (prefix in prefixes) {
        heavyDao.deleteByKeyPrefix(prefix)
    }
}

/** 增量编码写入重型数据（每项编码完立即写入，立即释放 ByteArray）。 */
internal suspend fun StorageEngine.writeHeavyDataIncremental(heavyDao: GameHeavyDataDao, data: SaveData) {
    ProtobufConverters.encodeDiscipleListMapIncremental(
        data.gameData.aiSectDisciples, GameHeavyData.KEY_AI_SECT_DISCIPLES
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeSectDetailMapIncremental(
        data.gameData.sectDetails, GameHeavyData.KEY_SECT_DETAILS
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeExploredSectInfoMapIncremental(
        data.gameData.exploredSects, GameHeavyData.KEY_EXPLORED_SECTS
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeSectScoutInfoMapIncremental(
        data.gameData.scoutInfo, GameHeavyData.KEY_SCOUT_INFO
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeManualProficiencyMapIncremental(
        data.gameData.manualProficiencies, GameHeavyData.KEY_MANUAL_PROFICIENCIES
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeDiscipleListIncremental(
        data.gameData.recruitList, GameHeavyData.KEY_RECRUIT_LIST
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeWorldSectListIncremental(
        data.gameData.worldMapSects, GameHeavyData.KEY_WORLD_MAP_SECTS
    ) { chunks -> heavyDao.upsertAll(chunks) }
}

/** 构建轻型 GameData（大型字段清空，TypeConverter 编码近乎零开销）。 */
internal fun StorageEngine.buildLightGameData(data: SaveData): GameData =
    data.gameData.copy(        id = "game_data",
        lastSaveTime = data.timestamp,
        aiSectDisciples = emptyMap(),
        sectDetails = emptyMap(),
        exploredSects = emptyMap(),
        scoutInfo = emptyMap(),
        manualProficiencies = emptyMap(),
        recruitList = emptyList(),
        worldMapSects = emptyList()
    )

/** 清空槽位旧数据（先清后写，防止旧存档高 ID 行残留）。 */
internal suspend fun StorageEngine.clearOldEntities(data: SaveData) {
    core.database.discipleDao().deleteAll()
    // 堆叠删表守卫：旧格式存档（stacksSerialized = false，如旧备份恢复）的
    // 堆叠未进入 SaveData，此时不删除 DB 残留的堆叠行——保留完好的既有堆叠，
    // 重建结果以 upsert 合并。
    if (data.stacksSerialized) {
        core.database.manualStackDao().deleteAll()
    }
    core.database.equipmentInstanceDao().deleteAll()
    core.database.manualInstanceDao().deleteAll()
    core.database.pillDao().deleteAll()
    core.database.materialDao().deleteAll()
    core.database.herbDao().deleteAll()
    core.database.seedDao().deleteAll()
    core.database.storageBagDao().deleteAll()
    core.database.battleLogDao().deleteAll()
    core.database.recipeDao().deleteAll()
    core.database.productionSlotDao().deleteAll()
    // 邮件整对象替换的删侧（SR-1）：SaveData.mails 是槽位邮件的唯一真相——
    // 旧档无该字段 ⇒ 快照空表 ⇒ 替换后表为空（方案明示单向兼容，与堆叠的
    // stacksSerialized 条件保留语义**不同**，此处无条件删，交由写侧回填快照）。
    core.database.mailDao().deleteAll()
}

/** 写入核心实体（轻型 GameData + 弟子/堆叠/实例/生产槽等）。 */
internal suspend fun StorageEngine.writeCoreEntities(data: SaveData, lightGameData: GameData) {
    core.database.gameDataDao().insert(lightGameData)

    writeDisciples(data)
    writeStackedItems(data)
    writeMails(data)
    writeProductionSlotsAndRecipes(data)

    syncSlotMetadata(data)
}

/**
 * 弟子分批写入——**只写 `disciples` 一张表**。
 *
 * v53（SR-7 schema 第二刀）前此处还会把每个弟子二次投影成核心/战斗/装备/扩展/属性五表
 * ＋紧凑表；六表的 SELECT 方法全仓零调用者，且每行都由本行的 `Disciple` 经
 * `X.fromDisciple(...)` 派生（零外部输入）⇒ 纯冗余副本，已随迁移删除，
 * 内存侧的同名领域类不受影响（`DiscipleAggregate` 构造路径不经 DB）。
 */
internal suspend fun StorageEngine.writeDisciples(data: SaveData) {
    data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
        core.database.discipleDao().upsertAll(batch)
    }
}

/** 堆叠/实例/日志族分批写入 */
internal suspend fun StorageEngine.writeStackedItems(data: SaveData) {
    // 装备堆叠不写回（B3：equipment_stacks 表已 DROP；deprecated 载体仅作旧档
    // 补偿读取面，补偿置位后恒空——运行时装备一律 equipment_instances 一行一件）
    data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentInstanceDao().upsertAll(it
        ) }
    data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.manualStackDao().upsertAll(it) }
    data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.manualInstanceDao().upsertAll(it
        ) }
    data.pills.chunked(MAX_BATCH_SIZE).forEach { core.database.pillDao().upsertAll(it) }
    data.materials.chunked(MAX_BATCH_SIZE).forEach { core.database.materialDao().upsertAll(it) }
    data.herbs.chunked(MAX_BATCH_SIZE).forEach { core.database.herbDao().upsertAll(it) }
    data.seeds.chunked(MAX_BATCH_SIZE).forEach { core.database.seedDao().upsertAll(it) }

    data.storageBags.chunked(MAX_BATCH_SIZE).forEach { core.database.storageBagDao().upsertAll(it) }

    data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { core.database.battleLogDao().upsertAll(it) }
}

/** 生产槽与配方写入：空槽告警 + 分批 upsert + 解锁配方 */
internal suspend fun StorageEngine.writeProductionSlotsAndRecipes(data: SaveData) {
    val productionSlotsToSave = data.productionSlots
    if (productionSlotsToSave.isEmpty()) {
        Log.w(TAG, "writeAllDataToDatabase: productionSlotsToSave is EMPTY — " +
            "data.productionSlots.size=${data.productionSlots.size}")
    }
    productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
        core.database.productionSlotDao().upsertAll(batch)
    }

    data.gameData.unlockedRecipes?.map { Recipe(it) }?.let { recipes ->
        core.database.recipeDao().upsertAll(recipes)
    }
}

/** 写入领域实体表（外交/生产状态/巡逻/世界地图/政策——Phase B 细粒度读取路径）。 */
internal suspend fun StorageEngine.writeDomainEntities(data: SaveData) {
    val gd = data.gameData
    core.database.diplomacyStateDao().upsert(DiplomacyState(        sectRelations = gd.sectRelations,
        alliances = gd.alliances,
        playerAllianceSlots = gd.playerAllianceSlots,
        playerProtectionEnabled = gd.playerProtectionEnabled,
        playerProtectionStartYear = gd.playerProtectionStartYear,
        playerHasAttackedAI = gd.playerHasAttackedAI,
        sectDetails = gd.sectDetails,
        exploredSects = gd.exploredSects,
        scoutInfo = gd.scoutInfo
    ))
    core.database.productionStateDao().upsert(ProductionState(        spiritFieldPlants = gd.spiritFieldPlants,
        unlockedRecipes = gd.unlockedRecipes ?: emptyList(),
        unlockedManuals = gd.unlockedManuals ?: emptyList(),
        manualProficiencies = gd.manualProficiencies
    ))
    core.database.patrolStateDao().upsert(PatrolStateEntity(        patrolSlots = gd.patrolSlots,
        patrolConfig = gd.patrolConfig,
        patrolConfigs = gd.patrolConfigs,
        patrolBattleResultPopup = gd.patrolBattleResultPopup
    ))
    core.database.worldMapStateDao().upsert(WorldMapStateEntity(        worldMapSects = gd.worldMapSects,
        aiSectDisciples = gd.aiSectDisciples,
        cultivatorCaves = gd.cultivatorCaves,
        caveExplorationTeams = gd.caveExplorationTeams,
        worldLevels = gd.worldLevels
    ))
    core.database.sectPolicyStateDao().upsert(SectPolicyState(        sectPolicies = gd.sectPolicies,
        breakthroughAutoPillFocused = gd.breakthroughAutoPillFocused,
        breakthroughAutoPillRootCounts = gd.breakthroughAutoPillRootCounts,
        autoEquipFromWarehouseFocused = gd.autoEquipFromWarehouseFocused,
        autoEquipFromWarehouseRootCounts = gd.autoEquipFromWarehouseRootCounts,
        autoLearnFromWarehouseFocused = gd.autoLearnFromWarehouseFocused,
        autoLearnFromWarehouseRootCounts = gd.autoLearnFromWarehouseRootCounts,
        yearlySalary = gd.yearlySalary,
        yearlySalaryEnabled = gd.yearlySalaryEnabled
    ))
}

/**
 * 当前可用内存（MB）。
 */
internal fun StorageEngine.availableMemoryMB(): Long {
    val runtime = Runtime.getRuntime()
    val maxMem = runtime.maxMemory()
    val usedMem = runtime.totalMemory() - runtime.freeMemory()
    return (maxMem - usedMem) / 1024 / 1024
}
