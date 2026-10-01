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
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.GameHeavyDataDao
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageResult
import androidx.room.withTransaction

// StorageEngine 的全量写域（兜底路径）：heavy 全删全写 + 实体表整表清空重写 +
// 域状态表回填 + 容量辅助。写入口接收者为 GameDatabase（与增量写面同口径，
// 直测免引擎装配）；事务边界由调用方 performTransactionSave 承担。
// 增量路径（默认）在 StorageEngineIncrementalWriteOps.kt（按域拆分惯例）。

private val TAG = StorageEngine.TAG

private const val MAX_BATCH_SIZE = StorageEngine.MAX_BATCH_SIZE

/**
 * 全量路径（兜底）：heavy 全删全写 + 实体表整表清空重写。
 * 触发条件与计数见 resolveSavePath；正确性语义 = DB 终态与快照逐字段一致。
 */
internal suspend fun GameDatabase.writeAllDataToDatabase(data: SaveData): StorageResult<Unit> {
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

    val heavyDao = gameHeavyDataDao()

    // 重型数据清理/写入/轻量实体写入分别提取（行为逐行一致）
    clearHeavyDataByPrefix(heavyDao)
    writeHeavyDataIncremental(heavyDao, data)

    // ── 轻型 GameData（所有大型字段已清空，TypeConverter 编码近乎零开销）──
    val lightGameData = buildLightGameData(data)
    withTransaction {
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
internal suspend fun GameDatabase.clearHeavyDataByPrefix(heavyDao: GameHeavyDataDao) {
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
internal suspend fun GameDatabase.writeHeavyDataIncremental(
    heavyDao: GameHeavyDataDao,
    data: SaveData
) {
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
internal fun GameDatabase.buildLightGameData(data: SaveData): GameData =
    data.gameData.copy(
        id = "game_data",
        lastSaveTime = data.timestamp,
        aiSectDisciples = emptyMap(),
        sectDetails = emptyMap(),
        exploredSects = emptyMap(),
        scoutInfo = emptyMap(),
        manualProficiencies = emptyMap(),
        recruitList = emptyList(),
        worldMapSects = emptyList()
    )

/** 清空实体表旧数据（先清后写，防止旧存档高 ID 行残留）——全量路径删侧。 */
internal suspend fun GameDatabase.clearOldEntities(data: SaveData) {
    discipleDao().deleteAll()
    // 堆叠删表防线（SS5 降级为断言）：删档重置（SS0）后全部 SaveData 生产者
    // 均置 stacksSerialized = true（快照构建/DB 构建/堆叠重建协调器三处），
    // false 只能来自生产者回归——fail-fast 拒绝保存，不静默保留残留行。
    // 增量路径为默认后，堆叠表每保存按 id 对账，旧"条件保留"分支的保护对象
    // （无条件整表删除）已不存在。
    check(data.stacksSerialized) {
        "SaveData.stacksSerialized = false：堆叠数据未序列化却进入落盘链路" +
            "（生产者回归），拒绝整表删除以防堆叠行被清空"
    }
    manualStackDao().deleteAll()
    equipmentInstanceDao().deleteAll()
    manualInstanceDao().deleteAll()
    pillDao().deleteAll()
    materialDao().deleteAll()
    herbDao().deleteAll()
    seedDao().deleteAll()
    storageBagDao().deleteAll()
    battleLogDao().deleteAll()
    recipeDao().deleteAll()
    productionSlotDao().deleteAll()
    // 邮件整对象替换的删侧（SR-1）：SaveData.mails 是邮件的唯一真相——
    // 快照空表 ⇒ 替换后表为空（写侧回填快照，删侧无条件执行）。
    mailDao().deleteAll()
}

/** 写入核心实体（轻型 GameData + 弟子/堆叠/实例/生产槽等）——全量路径。 */
internal suspend fun GameDatabase.writeCoreEntities(data: SaveData, lightGameData: GameData) {
    gameDataDao().insert(lightGameData)

    writeDisciples(data)
    writeStackedItems(data)
    writeMails(data)
    writeProductionSlotsAndRecipes(data)
}

/**
 * 弟子分批写入——**只写 `disciples` 一张表**。
 *
 * v53（SR-7 schema 第二刀）前此处还会把每个弟子二次投影成核心/战斗/装备/扩展/属性五表
 * ＋紧凑表；六表的 SELECT 方法全仓零调用者，且每行都由本行的 `Disciple` 经
 * `X.fromDisciple(...)` 派生（零外部输入）⇒ 纯冗余副本，已随迁移删除，
 * 内存侧的同名领域类不受影响（`DiscipleAggregate` 构造路径不经 DB）。
 */
internal suspend fun GameDatabase.writeDisciples(data: SaveData) {
    data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
        discipleDao().upsertAll(batch)
    }
}

/** 堆叠/实例/日志族分批写入 */
internal suspend fun GameDatabase.writeStackedItems(data: SaveData) {
    // 装备堆叠不写回（B3：equipment_stacks 表已 DROP；deprecated 载体仅作旧档
    // 补偿读取面，补偿置位后恒空——运行时装备一律 equipment_instances 一行一件）
    data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { equipmentInstanceDao().upsertAll(it
        ) }
    data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { manualStackDao().upsertAll(it) }
    data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { manualInstanceDao().upsertAll(it
        ) }
    data.pills.chunked(MAX_BATCH_SIZE).forEach { pillDao().upsertAll(it) }
    data.materials.chunked(MAX_BATCH_SIZE).forEach { materialDao().upsertAll(it) }
    data.herbs.chunked(MAX_BATCH_SIZE).forEach { herbDao().upsertAll(it) }
    data.seeds.chunked(MAX_BATCH_SIZE).forEach { seedDao().upsertAll(it) }

    data.storageBags.chunked(MAX_BATCH_SIZE).forEach { storageBagDao().upsertAll(it) }

    data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { battleLogDao().upsertAll(it) }
}

/** 生产槽与配方写入：空槽告警 + 分批 upsert + 解锁配方 */
internal suspend fun GameDatabase.writeProductionSlotsAndRecipes(data: SaveData) {
    val productionSlotsToSave = data.productionSlots
    if (productionSlotsToSave.isEmpty()) {
        Log.w(TAG, "writeAllDataToDatabase: productionSlotsToSave is EMPTY — " +
            "data.productionSlots.size=${data.productionSlots.size}")
    }
    productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
        productionSlotDao().upsertAll(batch)
    }

    data.gameData.unlockedRecipes?.map { Recipe(it) }?.let { recipes ->
        recipeDao().upsertAll(recipes)
    }
}

/** 写入领域实体表（外交/生产状态/巡逻/世界地图/政策——Phase B 细粒度读取路径）。 */
internal suspend fun GameDatabase.writeDomainEntities(data: SaveData) {
    val gd = data.gameData
    diplomacyStateDao().upsert(DiplomacyState(
        sectRelations = gd.sectRelations,
        alliances = gd.alliances,
        playerAllianceSlots = gd.playerAllianceSlots,
        playerProtectionEnabled = gd.playerProtectionEnabled,
        playerProtectionStartYear = gd.playerProtectionStartYear,
        playerHasAttackedAI = gd.playerHasAttackedAI,
        sectDetails = gd.sectDetails,
        exploredSects = gd.exploredSects,
        scoutInfo = gd.scoutInfo
    ))
    productionStateDao().upsert(ProductionState(
        spiritFieldPlants = gd.spiritFieldPlants,
        unlockedRecipes = gd.unlockedRecipes ?: emptyList(),
        unlockedManuals = gd.unlockedManuals ?: emptyList(),
        manualProficiencies = gd.manualProficiencies
    ))
    patrolStateDao().upsert(PatrolStateEntity(
        patrolSlots = gd.patrolSlots,
        patrolConfig = gd.patrolConfig,
        patrolConfigs = gd.patrolConfigs,
        patrolBattleResultPopup = gd.patrolBattleResultPopup
    ))
    worldMapStateDao().upsert(WorldMapStateEntity(
        worldMapSects = gd.worldMapSects,
        aiSectDisciples = gd.aiSectDisciples,
        cultivatorCaves = gd.cultivatorCaves,
        caveExplorationTeams = gd.caveExplorationTeams,
        worldLevels = gd.worldLevels
    ))
    sectPolicyStateDao().upsert(SectPolicyState(
        sectPolicies = gd.sectPolicies,
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
