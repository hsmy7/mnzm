package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.render.RenderBackend
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics

/**
 * Canvas 软件渲染后端适配器 — 将 [RenderBackend] 契约翻译为
 * [SoftwareCanvasBackend] + Surface lockCanvas 提交。
 *
 * ## 职责
 * - [resize] → SoftwareCanvasBackend 视口重建
 * - [setCamera] → 缓存相机值（渲染时合并覆盖 [RenderFrame] 中的帧率门控旧值）
 * - [renderFrame] → 软件渲染到帧缓冲 Bitmap → lockCanvas 贴图提交
 * - [release] → SoftwareCanvasBackend.release（宿主 surfaceDestroyed 时调用）
 *
 * ## 与 Vulkan 路径的一致性
 * - 命令总线建筑快照消费逻辑与 [VulkanRenderBackend] 完全相同（TOCTOU 防御）
 * - 相机走独立通道（setCamera 值优先于 frame 值），与 Vulkan 路径同语义
 *
 * 渲染线程调用（与宿主 [NativeSurfaceView.RenderThread] 同线程），
 * 异常由渲染循环统一捕获（见 RenderThread.run）。
 */
class SoftwareRenderBackend(private val host: NativeSurfaceView) : RenderBackend {

    /** 独立相机通道缓存值（由 [setCamera] 写入，renderFrame 时合并覆盖） */
    private var camX = 0f
    private var camY = 0f
    private var scale = 1f

    override fun resize(width: Int, height: Int) {
        host.softwareRenderer?.resize(width, height)
    }

    override fun setCamera(camX: Float, camY: Float, scale: Float, viewportW: Int, viewportH: Int) {
        this.camX = camX
        this.camY = camY
        this.scale = scale
    }

    override fun renderFrame(frame: RenderFrame, viewportW: Int, viewportH: Int): Boolean {
        val sb = host.softwareRenderer
        val atlas = host.atlasBitmap
        if (sb == null || atlas == null) return false

        val mergedFrame = mergeCameraAndBuildingData(frame)
        val rendered = renderSoftwareFrame(sb, atlas, mergedFrame, viewportW, viewportH)
        return finishFrame(rendered)
    }

    override fun release() {
        host.softwareRenderer?.release()
    }

    // ── 内部拆分（2026-08-10 detekt：LongMethod/Cyclomatic/NestedBlockDepth 收敛） ──

    /** 合并独立相机通道 + 命令总线建筑快照到帧契约（与 Vulkan 路径同语义） */
    private fun mergeCameraAndBuildingData(frame: RenderFrame): RenderFrame {
        val snapshot = resolveBuildingSnapshot(frame)
        return frame.copy(
            camX = camX, camY = camY, scale = scale,
            buildingData = snapshot.data,
            buildingCount = snapshot.count,
            selectedBuildingIndex = if (snapshot.busWasDirty) -1 else frame.selectedBuildingIndex,
            // 拆除高亮 markers 与 buildingData 同序——总线脏帧时 markers 是旧列表
            // 的值，与新数据索引错位，本帧置 null 跳过高亮（下帧随 Compose 重组恢复；
            // 网格线与建筑索引无对齐关系，无需跳帧）
            demolishHighlightData = if (snapshot.busWasDirty) null else frame.demolishHighlightData
        )
    }

    /**
     * 从命令总线读取建筑数据快照（消除 TOCTOU 竞态）。
     * busWasDirty：建筑数据本次刚被推送——frame.selectedBuildingIndex 是
     * Compose 帧率门控旧值，与新数据可能错位，本帧跳过高亮（下帧自动恢复）
     */
    private fun resolveBuildingSnapshot(frame: RenderFrame): BuildingSnapshot {
        val bus = host.commandBus
        val busWasDirty = bus?.buildingDirty?.get() ?: false
        val busSnapshot = bus?.consumeBuildingData()
        if (busSnapshot != null) {
            return BuildingSnapshot(
                busWasDirty = busWasDirty,
                data = busSnapshot.data,
                count = busSnapshot.count.coerceAtMost((busSnapshot.data?.size ?: 0) / 5)
            )
        }
        return BuildingSnapshot(busWasDirty = busWasDirty, data = frame.buildingData, count = frame.buildingCount)
    }

    /**
     * 软件渲染到帧缓冲（异常统一吞为 null——渲染循环上层负责计数与提交失败）。
     *
     * @Suppress(TooGenericExceptionCaught)：SoftwareCanvasBackend 对异常 ROM/资源状态
     * 抛精确 RuntimeException 子类不可穷举（Bitmap 回收/尺寸异常/驱动缺陷）——
     * 渲染线程防御性兜底，统一 log-and-continue，上层 RenderThread 全链路兜底
     */
    @Suppress("TooGenericExceptionCaught")
    private fun renderSoftwareFrame(
        sb: SoftwareCanvasBackend,
        atlas: Bitmap,
        frame: RenderFrame,
        viewportW: Int,
        viewportH: Int
    ): Bitmap? {
        return try {
            sb.renderFrame(
                frame = frame,
                atlas = atlas,
                vpW = viewportW.coerceAtLeast(1),
                vpH = viewportH.coerceAtLeast(1),
                // ★ 地图淡入 alpha（WP4）：渲染线程每帧计算，合成 paint.alpha 应用
                fadeAlpha = host.fadeAlpha
            )
        } catch (e: RuntimeException) {
            android.util.Log.e("SoftwareRenderBackend", "renderFrame failed: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            // 显式 GC 无益（ART 自动回收）——只记录指标，渲染循环 OOM 由上层统一捕获
            android.util.Log.e("SoftwareRenderBackend", "renderFrame OOM: ${e.message}", e)
            null
        }
    }

    /**
     * 帧后处理：指标 + lockCanvas 提交。
     * rendered == null（渲染失败/异常）只计数一次（原 catch 内与 null 分支双计数
     * 已合并——renderFrameNull 语义 = "帧渲染失败次数"，异常与 null 同属一次失败）。
     */
    private fun finishFrame(rendered: Bitmap?): Boolean {
        if (rendered == null) {
            RenderMetrics.renderFrameNull.incrementAndGet()
            return false
        }
        RenderMetrics.softwareFrames.incrementAndGet()
        RenderMetrics.totalFrames.incrementAndGet()
        RenderMetrics.recordFrame()
        return commitToSurface(rendered)
    }

    /**
     * lockCanvas 提交（失败重试，防 Surface 竞争窗口）。
     * 循环退出条件（isReady/interrupted）统一收敛到 [canRetryLock]，循环体零 break。
     *
     * @Suppress(TooGenericExceptionCaught)：lockCanvas/drawBitmap 对异常 ROM 抛精确
     * RuntimeException 子类不可穷举（Surface 已销毁/已锁定/驱动异常）——
     * 渲染线程防御性兜底，统一 log-and-continue 重试，上层 RenderThread 全链路兜底
     */
    @Suppress("TooGenericExceptionCaught")
    private fun commitToSurface(rendered: Bitmap): Boolean {
        var retries = LOCK_RETRIES
        var committed = false
        var interrupted = false
        while (canRetryLock(retries, committed, interrupted)) {
            try {
                val surfaceCanvas = host.holder.lockCanvas()
                if (surfaceCanvas == null) {
                    RenderMetrics.lockCanvasRetries.incrementAndGet()
                    retries--
                    // ★ 对抗性审查修复：continue 前退出标志已由 while 条件重查（时序安全）
                    continue
                }
                commitBitmap(surfaceCanvas, rendered)
                host.holder.unlockCanvasAndPost(surfaceCanvas)
                committed = true
            } catch (e: RuntimeException) {
                retries--
                interrupted = onLockFailure(retries, e)
            }
        }
        return committed
    }

    /**
     * 帧缓冲提交到物理 surface。
     *
     * render scale = 1.0 时走逐位兼容的直贴路径（drawBitmap 原尺寸零缩放）；
     * render scale < 1.0 时降采样帧缓冲双线性上采样拉伸到物理 surface
     * （render scale 2026-08-14 平板省电——与 Vulkan 路径 vkCmdBlitImage 同语义）。
     */
    private fun commitBitmap(surfaceCanvas: android.graphics.Canvas, rendered: Bitmap) {
        val rs = host.softwareRenderer?.renderScale ?: 1.0f
        val needsUpscale = rs < 1.0f &&
            (rendered.width < surfaceCanvas.width || rendered.height < surfaceCanvas.height)
        if (!needsUpscale) {
            surfaceCanvas.drawBitmap(rendered, 0f, 0f, null)
            return
        }
        val upscalePaint = upscalePaint()
        surfaceCanvas.drawBitmap(
            rendered, null,
            android.graphics.Rect(0, 0, surfaceCanvas.width, surfaceCanvas.height),
            upscalePaint
        )
    }

    /** 上采样 Paint（双线性滤波；懒创建复用——提交路径仅本方法使用） */
    private fun upscalePaint(): android.graphics.Paint {
        var p = upscalePaintRef
        if (p == null) {
            p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                isDither = false
                isAntiAlias = false
            }
            upscalePaintRef = p
        }
        return p
    }

    /** 上采样 Paint 缓存（懒创建，渲染线程单消费者无竞态） */
    private var upscalePaintRef: android.graphics.Paint? = null

    /**
     * 重试条件每循环重估（isReady/中断标志实时变化）。
     *
     * @param retries 剩余重试次数
     * @param committed 是否已提交成功
     * @param interrupted 内部退避 sleep 是否被中断
     * @return true = 继续重试
     */
    private fun canRetryLock(retries: Int, committed: Boolean, interrupted: Boolean): Boolean {
        val budgetLeft = retries > 0 && !committed
        val threadAlive = !interrupted && host.isReady && !Thread.interrupted()
        return budgetLeft && threadAlive
    }

    /**
     * lockCanvas 失败处理：重试耗尽 → 计数 + 告警；否则记录 + 退避 sleep。
     *
     * @param retries 剩余重试次数（已递减）
     * @return true = 退避 sleep 被中断（调用方置中断标志退出循环）
     */
    private fun onLockFailure(retries: Int, e: RuntimeException): Boolean {
        if (retries == 0) {
            RenderMetrics.lockCanvasFailed.incrementAndGet()
            android.util.Log.w("SoftwareRenderBackend",
                "lockCanvas failed after $LOCK_RETRIES retries: ${e.message}")
            return false
        }
        android.util.Log.d("SoftwareRenderBackend", "lockCanvas retry $retries: ${e.message}")
        return try {
            Thread.sleep(RETRY_SLEEP_MS)
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }
    }

    /** 命令总线建筑快照（data + count + 脏标记，合并帧用） */
    private data class BuildingSnapshot(
        val busWasDirty: Boolean,
        val data: FloatArray?,
        val count: Int
    )

    companion object {
        /** lockCanvas 失败重试次数 */
        private const val LOCK_RETRIES = 3
        /** 重试间隔（毫秒） */
        private const val RETRY_SLEEP_MS = 5L
    }
}
