package com.xianxia.sect.ui.game.sect

import java.util.concurrent.atomic.AtomicBoolean

/**
 * RenderCommandBus — 渲染命令总线。
 *
 * 提供一条从 ViewModel/GameEngine 直达渲染线程的建筑数据通道，
 * 绕过 Compose 反应式管线和帧率门控。
 *
 * ## KMP 化备注（iOS 迁移）
 * 本类暂留 :feature:game（使用 java.util.concurrent.atomic.AtomicBoolean）。
 * iOS 侧等价物：kotlinx.atomicfu（KMP 官方原子库），迁移时仅替换
 * AtomicBoolean → atomicfu，类结构零改动。渲染契约（RenderFrame 等）
 * 已在 :core:engine，双平台共用。
 *
 * ## 设计对标
 *
 * - **UE ENQUEUE_RENDER_COMMAND**：游戏线程将参数化的命令推入线程安全队列，
 *   渲染线程在 `ProcessRenderThread()` 中按序消费。本项目使用单槽覆盖式
 *   SPSC 模式替代队列，因为只有单一数据类型且 latest-value-wins。
 * - **Godot RenderingServer.call_on_render_thread**：命令通过锁自由
 *   CommandQueueMT 从主线程推送到渲染线程。本项目使用 @Volatile +
 *   AtomicBoolean 实现更轻量的等效模式。
 * - **本项目相机独立通道**：`setCamera()` → @Volatile renderCamX/Y/scale，
 *   渲染线程直接读取覆盖 RenderFrame。本总线复制此已验证模式。
 *
 * ## 线程安全性（SPSC 模型）
 *
 * - 单一生产者（ViewModel 协程），单一消费者（RenderThread）
 * - `@Volatile` 保证引用可见性（Java final 字段语义保证数组内容在写入
 *   引用前初始化完成）
 * - `copyOf()` 防御性拷贝防止调用方后续修改已推送的数组
 * - `AtomicBoolean` 保证脏标记的原子 RMW 操作
 * - 写-写不竞争（单一生产者），读-读不竞争（单一消费者）
 *
 * @param initialTileData 可选初始瓦片数据（加载时即推送，减少首帧延迟）
 */
class RenderCommandBus {

    /** 建筑数据 FloatArray（格式与 [buildBuildingDataArray] 一致） */
    @Volatile
    var buildingData: FloatArray? = null
        private set

    /** 建筑数量 */
    @Volatile
    var buildingCount: Int = 0
        private set

    /** 建筑数据脏标记，渲染线程消费后复位（用于遥测） */
    val buildingDirty = AtomicBoolean(false)

    /**
     * 推送建筑数据到渲染线程。
     *
     * 覆盖式语义：最新调用覆盖之前的值，不排队。
     * 适用于建筑放置/移动/拆除后批量推送整个 buildingData 数组。
     *
     * @param data buildingData FloatArray（内部会做防御性拷贝）
     * @param count 建筑数量
     */
    fun postBuildingData(data: FloatArray?, count: Int) {
        // 校验：count 必须 >= 0 且不超过 data 可容纳的最大建筑数
        val safeCount = if (data != null) {
            count.coerceIn(0, data.size / 5)
        } else {
            0
        }
        this.buildingData = data?.copyOf()
        this.buildingCount = safeCount
        this.buildingDirty.set(true)
    }

    /** 渲染线程消费建筑数据（消费后清除脏标记，返回快照） */
    fun consumeBuildingData(): BuildingDataSnapshot {
        buildingDirty.set(false)
        return BuildingDataSnapshot(buildingData, buildingCount)
    }

    /** 重置所有槽位（渲染器重建/release 时调用） */
    fun reset() {
        buildingData = null
        buildingCount = 0
        buildingDirty.set(false)
    }

    /** 建筑数据快照 */
    data class BuildingDataSnapshot(
        val data: FloatArray?,
        val count: Int
    )
}
