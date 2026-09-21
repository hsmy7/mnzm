package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.DiplomacyState
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleCompact
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleEquipment
import com.xianxia.sect.core.model.DiscipleExtended
import com.xianxia.sect.core.model.BloodRefinementPctTotal
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

internal suspend fun StorageEngine.writeAllDataToDatabase(slot: Int, data: SaveData): StorageResult<Unit> {
    Log.d(TAG, "writeAllDataToDatabase: slot=$slot, " +
        "${data.disciples.size} disciples, " +
        "recruitList=${data.gameData.recruitList.size} unrecruited")

    // ── 存档前数据完整性校验 ──
    if (data.gameData.worldMapSects.isEmpty()) {
        Log.e(TAG, "存档前检测到 worldMapSects 为空 slot=$slot — 数据管线异常，" +
            "世界地图宗门数据已丢失。请检查 ensureHeavyDataLoaded 日志。")
    }
    if (data.gameData.sectName.isBlank()) {
        Log.w(TAG, "存档前检测到 sectName 为空 slot=$slot")
    }

    val heavyDao = core.database.gameHeavyDataDao()

    // 重型数据清理/写入/轻量实体写入分别提取（行为逐行一致）
    clearHeavyDataByPrefix(heavyDao, slot)
    writeHeavyDataIncremental(heavyDao, slot, data)

    // ── 轻型 GameData（所有大型字段已清空，TypeConverter 编码近乎零开销）──
    val lightGameData = buildLightGameData(data, slot)
    core.database.withTransaction {
        clearOldSlotEntities(slot, data)
        writeCoreEntities(slot, data, lightGameData)
        writeDomainEntities(slot, data)
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
internal suspend fun StorageEngine.clearHeavyDataByPrefix(heavyDao: GameHeavyDataDao, slot: Int) {
    val skipped = skippedHeavyKeysBySlot[slot].orEmpty()
    val prefixes = heavyPrefixesToClear(GameHeavyData.ALL_KEYS, skipped)
    if (skipped.isNotEmpty()) {
        Log.w(TAG, "clearHeavyDataByPrefix: 保留被跳过的 heavy key $skipped (slot=$slot)，" +
            "避免用空内存值覆盖 DB 完整数据")
    }
    for (prefix in prefixes) {
        heavyDao.deleteByKeyPrefix(slot, prefix)
    }
}

/** 增量编码写入重型数据（每项编码完立即写入，立即释放 ByteArray）。 */
internal suspend fun StorageEngine.writeHeavyDataIncremental(heavyDao: GameHeavyDataDao, slot: Int, data: SaveData) {
    ProtobufConverters.encodeDiscipleListMapIncremental(
        data.gameData.aiSectDisciples, slot, GameHeavyData.KEY_AI_SECT_DISCIPLES
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeSectDetailMapIncremental(
        data.gameData.sectDetails, slot, GameHeavyData.KEY_SECT_DETAILS
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeExploredSectInfoMapIncremental(
        data.gameData.exploredSects, slot, GameHeavyData.KEY_EXPLORED_SECTS
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeSectScoutInfoMapIncremental(
        data.gameData.scoutInfo, slot, GameHeavyData.KEY_SCOUT_INFO
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeManualProficiencyMapIncremental(
        data.gameData.manualProficiencies, slot, GameHeavyData.KEY_MANUAL_PROFICIENCIES
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeDiscipleListIncremental(
        data.gameData.recruitList, slot, GameHeavyData.KEY_RECRUIT_LIST
    ) { chunks -> heavyDao.upsertAll(chunks) }

    ProtobufConverters.encodeWorldSectListIncremental(
        data.gameData.worldMapSects, slot, GameHeavyData.KEY_WORLD_MAP_SECTS
    ) { chunks -> heavyDao.upsertAll(chunks) }
}

/** 构建轻型 GameData（大型字段清空，TypeConverter 编码近乎零开销）。 */
internal fun StorageEngine.buildLightGameData(data: SaveData, slot: Int): GameData =
    data.gameData.copy(
        slotId = slot,
        id = "game_data_$slot",
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
internal suspend fun StorageEngine.clearOldSlotEntities(slot: Int, data: SaveData) {
    core.database.discipleDao().deleteAll(slot)
    core.database.discipleCoreDao().deleteAll(slot)
    core.database.discipleCombatStatsDao().deleteAll(slot)
    core.database.discipleEquipmentDao().deleteAll(slot)
    core.database.discipleExtendedDao().deleteAll(slot)
    core.database.discipleAttributesDao().deleteAll(slot)
    // 堆叠删表守卫：旧格式存档（stacksSerialized = false，如旧备份恢复）的
    // 堆叠未进入 SaveData，此时不删除 DB 残留的堆叠行——保留完好的既有堆叠，
    // 重建结果以 upsert 合并。
    if (data.stacksSerialized) {
        core.database.equipmentStackDao().deleteAll(slot)
        core.database.manualStackDao().deleteAll(slot)
    }
    core.database.equipmentInstanceDao().deleteAll(slot)
    core.database.manualInstanceDao().deleteAll(slot)
    core.database.pillDao().deleteAll(slot)
    core.database.materialDao().deleteAll(slot)
    core.database.herbDao().deleteAll(slot)
    core.database.seedDao().deleteAll(slot)
    core.database.storageBagDao().deleteAll(slot)
    core.database.battleLogDao().deleteAll(slot)
    core.database.recipeDao().deleteAll(slot)
    core.database.productionSlotDao().deleteBySlot(slot)
    core.database.discipleCompactDao().deleteAll(slot)
    // 邮件整对象替换的删侧（SR-1）：SaveData.mails 是槽位邮件的唯一真相——
    // 旧档无该字段 ⇒ 快照空表 ⇒ 替换后表为空（方案明示单向兼容，与堆叠的
    // stacksSerialized 条件保留语义**不同**，此处无条件删，交由写侧回填快照）。
    core.database.mailDao().deleteAllForSlot(slot)
}

/** 写入核心实体（轻型 GameData + 弟子/堆叠/实例/生产槽等）。 */
internal suspend fun StorageEngine.writeCoreEntities(slot: Int, data: SaveData, lightGameData: GameData) {
    core.database.gameDataDao().insert(lightGameData)

    // bloodRefinementPctTotals 拍快照防止并发修改
    val bptSnapshot = data.gameData.bloodRefinementPctTotals
    writeDisciples(slot, data, bptSnapshot)
    writeStackedItems(slot, data)
    writeMails(slot, data)
    writeProductionSlotsAndRecipes(slot, data)

    syncSlotMetadata(slot, data)
    syncSlotMetadata(slot, data)
}

/** 弟子族实体分批写入：核心/战斗/装备/扩展/属性五表 + 紧凑表 */
internal suspend fun StorageEngine.writeDisciples(
    slot: Int,
    data: SaveData,
    bptSnapshot: Map<String, BloodRefinementPctTotal>
) {
    data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
        val withSlot = batch.map { d -> d.copy(slotId = slot) }
        core.database.discipleDao().upsertAll(withSlot)
        core.database.discipleCoreDao().upsertAll(batch.map { d -> DiscipleCore.fromDisciple(d)
            .copy(slotId = slot) })
        core.database.discipleCombatStatsDao().upsertAll(batch.map { d -> DiscipleCombatStats.fromDisciple(d)
            .copy(slotId = slot) })
        core.database.discipleEquipmentDao().upsertAll(batch.map { d -> DiscipleEquipment.fromDisciple(d)
            .copy(slotId = slot) })
        core.database.discipleExtendedDao().upsertAll(batch.map { d -> DiscipleExtended.fromDisciple(d)
            .copy(slotId = slot) })
        core.database.discipleAttributesDao().upsertAll(batch.map { d -> DiscipleAttributes.fromDisciple(d)
            .copy(slotId = slot) })
            core.database.discipleCompactDao().insertAll(batch.map { d ->
                DiscipleCompact.fromDisciple(d, bptSnapshot).copy(slotId = slot)
            })
    }
}

/** 堆叠/实例/日志族分批写入 */
internal suspend fun StorageEngine.writeStackedItems(slot: Int, data: SaveData) {
    data.equipmentStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentStackDao().upsertAll(it
        .map { e -> e.copy(slotId = slot) }) }
    data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentInstanceDao().upsertAll(it
        .map { e -> e.copy(slotId = slot) }) }
    data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.manualStackDao().upsertAll(it.map { m -> m
        .copy(slotId = slot) }) }
    data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.manualInstanceDao().upsertAll(it
        .map { m -> m.copy(slotId = slot) }) }
    data.pills.chunked(MAX_BATCH_SIZE).forEach { core.database.pillDao().upsertAll(it.map { p -> p
        .copy(slotId = slot) }) }
    data.materials.chunked(MAX_BATCH_SIZE).forEach { core.database.materialDao().upsertAll(it.map { m -> m
        .copy(slotId = slot) }) }
    data.herbs.chunked(MAX_BATCH_SIZE).forEach { core.database.herbDao().upsertAll(it.map { h -> h
        .copy(slotId = slot) }) }
    data.seeds.chunked(MAX_BATCH_SIZE).forEach { core.database.seedDao().upsertAll(it.map { s -> s
        .copy(slotId = slot) }) }

    data.storageBags.chunked(MAX_BATCH_SIZE).forEach { core.database.storageBagDao().upsertAll(it.map { b -> b
        .copy(slotId = slot) }) }

    data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { core.database.battleLogDao().upsertAll(it.map { b -> b
        .copy(slotId = slot) }) }
}

/** 生产槽与配方写入：空槽告警 + 分批 upsert + 解锁配方 */
internal suspend fun StorageEngine.writeProductionSlotsAndRecipes(slot: Int, data: SaveData) {
    val productionSlotsToSave = data.productionSlots
    if (productionSlotsToSave.isEmpty()) {
        Log.w(TAG, "writeAllDataToDatabase: productionSlotsToSave is EMPTY for slot $slot — " +
            "data.productionSlots.size=${data.productionSlots.size}")
    }
    productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
        core.database.productionSlotDao().upsertAll(batch.map { it.copy(slotId = slot) })
    }

    data.gameData.unlockedRecipes?.map { Recipe(it, slotId = slot) }?.let { recipes ->
        core.database.recipeDao().upsertAll(recipes)
    }
}

/** 写入领域实体表（外交/生产状态/巡逻/世界地图/政策——Phase B 细粒度读取路径）。 */
internal suspend fun StorageEngine.writeDomainEntities(slot: Int, data: SaveData) {
    val gd = data.gameData
    core.database.diplomacyStateDao().upsert(DiplomacyState(
        slotId = slot,
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
    core.database.productionStateDao().upsert(ProductionState(
        slotId = slot,
        spiritFieldPlants = gd.spiritFieldPlants,
        unlockedRecipes = gd.unlockedRecipes ?: emptyList(),
        unlockedManuals = gd.unlockedManuals ?: emptyList(),
        manualProficiencies = gd.manualProficiencies
    ))
    core.database.patrolStateDao().upsert(PatrolStateEntity(
        slotId = slot,
        patrolSlots = gd.patrolSlots,
        patrolConfig = gd.patrolConfig,
        patrolConfigs = gd.patrolConfigs,
        patrolBattleResultPopup = gd.patrolBattleResultPopup
    ))
    core.database.worldMapStateDao().upsert(WorldMapStateEntity(
        slotId = slot,
        worldMapSects = gd.worldMapSects,
        aiSectDisciples = gd.aiSectDisciples,
        cultivatorCaves = gd.cultivatorCaves,
        caveExplorationTeams = gd.caveExplorationTeams,
        aiCaveTeams = gd.aiCaveTeams,
        worldLevels = gd.worldLevels
    ))
    core.database.sectPolicyStateDao().upsert(SectPolicyState(
        slotId = slot,
        sectPolicies = gd.sectPolicies,
        autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter,
        daoCompanionBannedRootCounts = gd.daoCompanionBannedRootCounts,
        daoCompanionConsentRequired = gd.daoCompanionConsentRequired,
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
