package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import com.xianxia.sect.core.render.IslandCliffBridge

/**
 * IslandCliffTextureHolder — 崖壁纹理加载状态持有者（Compose 可观察）。
 *
 * ## 存在理由
 * 布局合成需要两个外部输入：**纹理尺寸表**（编译期常量，见
 * [IslandCliffTextureSet.TEXTURE_SIZES]）与**纹理可用掩码**
 * （[IslandCliffBridge.compose] 的 `textureMask`——未上传成功的条目由合成器跳过）。
 * 掩码只有**加载完成后**才知道，故此处以 Compose 可观察状态暴露：
 * 加载完成 → 掩码变化 → `rememberIslandCliffData` 重建布局（含正确的降级取舍）。
 *
 * 初始值取**全可用**（乐观）：绝大多数情况加载全成功，首帧即可出完整崖壁；
 * 若个别张失败，掩码降级后布局重建一次（低频、一次性）。
 *
 * ## 线程
 * [load] 内部自行编排：重活在**当前调用线程**顺序执行后经 `upload` 回调回主线程
 * 做 native 调用（调用方在渲染就绪回调内触发——
 * [NativeSurfaceView.loadIslandCliffTextures]）；状态写入经 `mutableStateOf`
 * （Compose 主线程读取）。
 */
class IslandCliffTextureHolder(private val context: Context) {

    /** 纹理可用掩码（bit i = 纹理 i 已上传成功；初始乐观全可用） */
    val textureMask = mutableStateOf((1 shl IslandCliffBridge.TextureIndex.COUNT) - 1)

    /** 是否已有任一张可用（掩码非 0 = 崖壁层可绘制；全失败则整层跳过，不画白） */
    internal val hasAnyTexture: Boolean get() = textureMask.value != 0

    /** 已成功上传的张数（掩码位计数，观测锚点用） */
    internal val availableCount: Int get() = Integer.bitCount(textureMask.value)

    /**
     * Canvas 路径位图集（下标序 = 纹理下标序；null 元素 = 该张不可用）。
     * Vulkan 路径不使用（走 GPU 纹理）。
     */
    val bitmaps = mutableStateOf<List<Bitmap?>?>(null)

    /** 是否已尝试过加载（幂等守卫——避免重复解码大图） */
    var started: Boolean = false
        private set

    /**
     * 后台准备 + 主线程上传（幂等：重复调用直接返回）。
     *
     * @param astcSupported 设备是否支持 ASTC（决定是否优先只读 KTX 资产）
     * @param upload 主线程上传动作（返回纹理 ID 表；Canvas 路径可传 null 跳过 GPU 上传）
     * @param keepBitmaps 是否保留位图给 Canvas 路径使用（Vulkan 路径为 false——省内存）
     */
    fun load(
        astcSupported: Boolean,
        keepBitmaps: Boolean,
        upload: (IntArray) -> Unit
    ) {
        if (started) return
        started = true
        val loader = IslandCliffTextureLoader(context)
        val prepared = loader.prepare(astcSupported)
        val ids = loader.upload(prepared)
        upload(ids)
        // 掩码：ID 非 0 视为可用；全失败 → 0（绘制端整层跳过，不画白）
        var mask = 0
        for (i in ids.indices) {
            if (ids[i] != 0) mask = mask or (1 shl i)
        }
        textureMask.value = mask
        if (mask == 0) {
            android.util.Log.w(LOG_TAG, "全部崖壁纹理加载失败——崖壁层整体跳过")
        } else if (mask != (1 shl IslandCliffTextureSet.TEXTURE_COUNT) - 1) {
            android.util.Log.w(LOG_TAG, "部分崖壁纹理加载失败 mask=0x${mask.toString(16)}（部分降级）")
        }
        if (keepBitmaps) {
            bitmaps.value = decodeBitmapsForCanvas()
        }
    }

    /** Canvas 路径位图（逐张解码；失败以 null 占位 → 绘制端跳过该条目） */
    private fun decodeBitmapsForCanvas(): List<Bitmap?> {
        val out = ArrayList<Bitmap?>(IslandCliffTextureSet.TEXTURE_COUNT)
        for (i in 0 until IslandCliffTextureSet.TEXTURE_COUNT) {
            out.add(
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                    android.graphics.BitmapFactory.decodeResource(
                        context.resources, IslandCliffTextureSet.TEXTURE_DRAWABLES[i], opts
                    )
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    // 刻意的防御性 catch：解码失败源跨资源 IO/素材状态/SDK，不可枚举——
                    // 按「该张缺失」降级（null 占位，绘制端跳过），异常已记录
                    android.util.Log.e(LOG_TAG, "canvas bitmap[$i] decode failed", t)
                    null
                }
            )
        }
        return out
    }

    private companion object {
        const val LOG_TAG = "IslandCliff"
    }
}
