package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.RenderFrame

// ── 帧/相机输入通道扩展（自 NativeSurfaceView 拆出，行为零变更）──────────────
// setCamera / updateRenderState 是 Compose 层 → 渲染线程的两条输入通道；
// batch-02 TooManyFunctions 收敛（类内 ≤19，沿同文件 advanceClouds/
// reportRenderFallback 顶层函数收敛纪律）外移为同包扩展，调用点语法不变。

/**
 * 从 Compose 层独立推送相机状态（不经过 [NativeSurfaceView.updateRenderState] 的帧率门控）。
 * 触摸拖拽时每帧调用，确保相机响应不延迟。
 */
fun NativeSurfaceView.setCamera(camX: Float, camY: Float, scale: Float) {
    renderCamX = camX
    renderCamY = camY
    renderScale = scale
    cameraDirty.set(true)
}

/** 从 Compose 层原子更新渲染帧数据 */
fun NativeSurfaceView.updateRenderState(frame: RenderFrame) {
    // 尺寸不匹配时不更新 currentFrame（先校验后赋值——校验前置防坏帧污染：
    // 渲染线程读取 currentFrame 后 SoftwareCanvasBackend ChunkTile.rebuild
    // 会 ArrayIndexOutOfBoundsException，被渲染循环 catch 吞掉后永久黑屏）
    if (frame.tileData.size != config.worldWidthCells * config.worldHeightCells) {
        android.util.Log.e("NativeSurfaceView",
            "RenderFrame tileData size mismatch: ${frame.tileData.size} " +
            "vs expected ${config.worldWidthCells * config.worldHeightCells}")
        return
    }

    // 仅当 tileData/buildingData 引用变化时拷贝（防止 Compose 线程后续修改）。
    // flatTileData 使用 remember() 缓存同一引用直至数据变化，稳态帧无需重复复制。
    val prevTileData = currentFrame?.tileData
    val safeTileData = if (frame.tileData === prevTileData) prevTileData else frame.tileData.copyOf()
    val prevBuildingData = currentFrame?.buildingData
    val safeBuildingData = if (frame.buildingData != null && frame
        .buildingData === prevBuildingData) prevBuildingData else frame.buildingData?.copyOf()
    // roadData 尺寸校验（与 tileData 同式防坏帧）；roadData 可为 null（无道路）
    val roadTotal = config.worldWidthCells * config.worldHeightCells
    val roadArr = frame.roadData
    if (roadArr != null && roadArr.size != roadTotal) {
        android.util.Log.e("NativeSurfaceView",
            "RenderFrame roadData size mismatch: ${roadArr.size} vs expected $roadTotal")
        return
    }
    val prevRoadData = currentFrame?.roadData
    val safeRoadData = if (roadArr != null && roadArr === prevRoadData) prevRoadData else roadArr?.copyOf()
    currentFrame = frame.copy(
        tileData = safeTileData,
        buildingData = safeBuildingData,
        roadData = safeRoadData
    )
    cameraDirty.set(true)
}
