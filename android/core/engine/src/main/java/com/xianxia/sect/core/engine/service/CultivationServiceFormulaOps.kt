package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
// ── 修炼公式域（自 CultivationService 拆出，行为零变更） ──

/**
 * 根据境界/层数/修为计算当前境界满修为值（即突破所需修为上限）。
 * 公式与 [DiscipleCore.maxCultivation] 一致，用于列级直读过滤，
 * 避免为了获取 maxCultivation 而组装完整的 Disciple 对象。
 */
internal fun CultivationService.computeMaxCultivation(realm: Int, realmLayer: Int, cultivation: Double): Double {
    if (realm == 0) return cultivation
    val base = GameConfig.Realm.get(realm).cultivationBase
    val nextBase = GameConfig.Realm.get(realm - 1).cultivationBase
    val maxLayers = GameConfig.Realm.get(realm).maxLayers
    return base + (realmLayer - 1) * (nextBase - base).toDouble() / maxLayers
}
