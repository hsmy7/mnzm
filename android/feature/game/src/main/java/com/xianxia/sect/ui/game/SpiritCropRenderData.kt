package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.util.TimeProgressUtil

/**
 * 灵田作物渲染数据构建（从 MainGameScreen.kt 拆分——文件行数收敛到 FileLength 阈值内）。
 */

/** 灵田建筑显示名（与 buildBuildingDataArray 的灵田判定同源） */
private const val SPIRIT_FIELD_NAME = "灵田"

/** 灵田作物数据单条步长（[gx, gy, progress01]） */
private const val CROP_DATA_STRIDE = 3

/**
 * 构建灵田作物渲染数据（WP6）。
 *
 * 输入为已按 sectId 过滤的放置建筑列表与种植记录。仅灵田建筑
 * （displayName == [SPIRIT_FIELD_NAME]）且该田存在种植记录（seedId 非空、同宗门）时
 * 输出 [gx, gy, progress01] 三元组；progress01 = 游戏时间进度
 * （[TimeProgressUtil.calculateProgressFraction]，与生产结算同源）。
 * 无作物时返回 null（后端跳过作物层——渲染零开销）。
 *
 * 注意：与 [buildBuildingDataArray] 不同，本函数不做 Y 排序——作物与灵田建筑同格
 * 绘制（作物绘制在建筑层之后，灵田之间互相遮挡无意义），且数组索引与建筑数组
 * 无关联（后端按三元组独立解析、双端同数学）。
 *
 * @param buildings 已按 sectId 过滤的放置建筑列表
 * @param plants 全部种植记录（内部按 sectId 过滤）
 * @param currentYear 当前游戏年
 * @param currentMonth 当前游戏月
 * @param sectId 当前宗门 ID（跨宗门记录防御性跳过）
 */
internal fun buildSpiritCropData(
    buildings: List<GridBuildingData>,
    plants: List<SpiritFieldPlant>,
    currentYear: Int,
    currentMonth: Int,
    sectId: String
): FloatArray? {
    val plantByBuilding = HashMap<String, SpiritFieldPlant>()
    for (plant in plants) {
        // 跨宗门记录防御性跳过 + 未种植的田无作物（if 包裹避免 continue）
        if (plant.sectId == sectId && plant.seedId.isNotEmpty()) {
            plantByBuilding[plant.buildingInstanceId] = plant
        }
    }

    var count = 0
    val buffer = FloatArray(buildings.size * CROP_DATA_STRIDE)
    for (b in buildings) {
        val plant = plantByBuilding[b.instanceId]
        if (b.displayName == SPIRIT_FIELD_NAME && plant != null) {
            val progress = TimeProgressUtil.calculateProgressFraction(
                startYear = plant.plantYear,
                startMonth = plant.plantMonth,
                duration = plant.growTime,
                currentYear = currentYear,
                currentMonth = currentMonth
            )
            val idx = count * CROP_DATA_STRIDE
            buffer[idx] = b.gridX.toFloat()
            buffer[idx + 1] = b.gridY.toFloat()
            buffer[idx + 2] = progress
            count++
        }
    }
    // 单 return：无种植 → null；全部命中 → 原数组；部分命中 → 截断
    if (count == 0) return null
    return if (count == buildings.size) buffer else buffer.copyOf(count * CROP_DATA_STRIDE)
}
