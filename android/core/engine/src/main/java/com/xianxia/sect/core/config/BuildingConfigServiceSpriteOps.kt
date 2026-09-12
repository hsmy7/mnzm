package com.xianxia.sect.core.config

// ── 建筑精灵尺寸域（自 BuildingConfigService 拆出，行为零变更） ──

/** 获取建筑精灵视觉比例尺寸，为 0 时回退到占地尺寸 */
fun BuildingConfigService.getBuildingSpriteSize(displayName: String): Pair<Int, Int> {
    val config = getBuildingConfigByDisplayName(displayName)
    return config?.run { effectiveSpriteWidth() to effectiveSpriteHeight() } ?: (2 to 2)
}

/** 获取所有建筑的精灵视觉比例尺寸映射 */
fun BuildingConfigService.getAllBuildingSpriteSizes(): Map<String, Pair<Int, Int>> {
    return ensureConfigLoaded().buildings.values.associate {
        it.displayName to getBuildingSpriteSize(it.displayName)
    }
}
