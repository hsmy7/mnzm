package com.xianxia.sect.data.engine
import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import kotlinx.coroutines.CancellationException

// StorageEngine 重数据域的**按 key 回填**：部分 heavy key 缺失/被跳过时，用 domain state
// 表按 key 粒度补齐（与 StorageEngineHeavyDataOps.restoreHeavyDataFromDomainTables 的
// "整表为空才全量兜底"互补）。审计 §12-A 勘误后新增：单 key 缺失同样会造成
// exploredSects/scoutInfo 等无再生源数据被空值永久覆盖。
//
// 本文件从 StorageEngineHeavyDataOps.kt 拆出（该文件已到 detekt 文件级函数阈值 15）。

private val TAG = StorageEngine.TAG

/**
 * 仅回填**缺失**的 heavy key（不覆盖已存在的 key）。
 *
 * 与 [restoreHeavyDataFromDomainTables] 的区别：后者仅在 heavy_data 整表为空时
 * 全量兜底；本函数在"部分 key 缺失/被跳过"时按 key 粒度补齐，避免缺失 key
 * 被空值永久抹掉。
 *
 * @return 回填后的数据（缺失 key 的域表值非空时覆盖，否则保持原值）
 */
// 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
@Suppress("TooGenericExceptionCaught")
internal suspend fun StorageEngine.restoreMissingHeavyKeysFromDomainTables(
    gameData: GameData,
    slot: Int,
    missingKeys: Set<String>
): GameData {
    var worldMapEntity: com.xianxia.sect.core.model.WorldMapStateEntity? = null
    var diplomacyEntity: com.xianxia.sect.core.model.DiplomacyState? = null
    var productionEntity: com.xianxia.sect.core.model.ProductionState? = null
    try {
        worldMapEntity = core.database.worldMapStateDao().getBySlot(slot)
        diplomacyEntity = core.database.diplomacyStateDao().getBySlot(slot)
        productionEntity = core.database.productionStateDao().getBySlot(slot)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "mergeHeavyData: domain-table backfill read failed", e)
    }

    var result = gameData
    result = result.backfillIfMissing(
        GameHeavyData.KEY_WORLD_MAP_SECTS in missingKeys, worldMapEntity?.worldMapSects, { it.isEmpty() }
    ) { gd, v -> gd.copy(worldMapSects = v) }
    result = result.backfillIfMissing(
        GameHeavyData.KEY_AI_SECT_DISCIPLES in missingKeys, worldMapEntity?.aiSectDisciples, { it.isEmpty() }
    ) { gd, v -> gd.copy(aiSectDisciples = v) }
    result = result.backfillIfMissing(
        GameHeavyData.KEY_SECT_DETAILS in missingKeys, diplomacyEntity?.sectDetails, { it.isEmpty() }
    ) { gd, v -> gd.copy(sectDetails = v) }
    result = result.backfillIfMissing(
        GameHeavyData.KEY_EXPLORED_SECTS in missingKeys, diplomacyEntity?.exploredSects, { it.isEmpty() }
    ) { gd, v -> gd.copy(exploredSects = v) }
    result = result.backfillIfMissing(
        GameHeavyData.KEY_SCOUT_INFO in missingKeys, diplomacyEntity?.scoutInfo, { it.isEmpty() }
    ) { gd, v -> gd.copy(scoutInfo = v) }
    result = result.backfillIfMissing(
        GameHeavyData.KEY_MANUAL_PROFICIENCIES in missingKeys, productionEntity?.manualProficiencies,
        { it.isEmpty() }
    ) { gd, v -> gd.copy(manualProficiencies = v) }
    // recruitList 无 domain state 表可恢复——缺失时保持原值（不臆造）
    return result
}

/**
 * 缺失 key 且域表值非空时应用回填，否则保持原值。
 *
 * 抽成单行调用点是为了把 [restoreMissingHeavyKeysFromDomainTables] 的圈复杂度
 * 留在阈值内（detekt `CyclomaticComplexMethod` 15），同时让"每个 key 一行"的
 * 映射关系保持可读。
 */
private inline fun <T> GameData.backfillIfMissing(
    missing: Boolean,
    value: T?,
    isEmpty: (T) -> Boolean,
    apply: (GameData, T) -> GameData
): GameData = if (missing && value != null && !isEmpty(value)) apply(this, value) else this
