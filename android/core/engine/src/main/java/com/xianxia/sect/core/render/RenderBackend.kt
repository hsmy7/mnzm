package com.xianxia.sect.core.render

/**
 * 渲染后端抽象 — 渲染线程的唯一执行入口。
 *
 * 双后端实现：
 * - Vulkan（GPU，C++ NativeBridge）：见 feature 模块 `VulkanRenderBackend`
 * - Canvas（CPU，SoftwareCanvasBackend）：见 feature 模块 `SoftwareRenderBackend`
 *
 * ## 设计原则
 * - **契约统一**：双后端消费同一份 [RenderFrame]，行为级一致性由
 *   [RenderBackendContractTest]（FakeBackend 调用序列）与
 *   SoftwareCanvasBackendTest（像素断言）共同锁住
 * - **图集注入不进接口**：Vulkan=GPU 纹理 ID / Canvas=Bitmap / iOS=MTLTexture
 *   形态各异，由实现类特有方法注入（`setAtlasTextureId` / `setAtlasBitmap`）
 * - **调用方约定**：渲染循环先 `setCamera`（相机变化时）再 `renderFrame`；
 *   `resize` 在窗口变化时调用（幂等）；`release` 在 surface 销毁后调用，
 *   release 之后不得再调用任何方法
 *
 * ## 跨平台
 * 接口零 Android 依赖，iOS Metal 实现（MTLRenderCommandEncoder 封装）
 * 直接实现本接口即可接入同一渲染循环。
 */
interface RenderBackend {

    /**
     * 视口尺寸变化（幂等，可在任意时刻调用）。
     *
     * @param width 新视口宽度（像素）
     * @param height 新视口高度（像素）
     */
    fun resize(width: Int, height: Int)

    /**
     * 推送相机状态。渲染循环在相机变化时调用（首帧 + 脏标记），
     * 实现类需缓存最新值供 [renderFrame] 使用。
     *
     * @param camX 相机 X（世界像素，视口左上角）
     * @param camY 相机 Y（世界像素，视口左上角）
     * @param scale 相机缩放
     * @param viewportW 视口宽度（像素）
     * @param viewportH 视口高度（像素）
     */
    fun setCamera(camX: Float, camY: Float, scale: Float, viewportW: Int, viewportH: Int)

    /**
     * 渲染一帧（阻塞至本帧渲染完成，符合渲染线程帧节奏契约）。
     *
     * @param frame 渲染帧契约（不可变快照，实现类不得修改）
     * @param viewportW 视口宽度（像素）
     * @param viewportH 视口高度（像素）
     * @return 是否成功呈现一帧（失败时渲染循环应记录指标但不中断）
     */
    fun renderFrame(frame: RenderFrame, viewportW: Int, viewportH: Int): Boolean

    /**
     * 释放后端资源。surface 销毁后由宿主调用，此后不得再调用本实例任何方法。
     */
    fun release()
}
