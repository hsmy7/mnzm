package com.xianxia.sect.ui.game.sect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.render.IslandCliffBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IslandCliffTextureLoader — 浮空岛崖壁独立纹理加载器（地图边缘系统）。
 *
 * ## 三级降级（逐张独立，单张失败只丢对应侧，不整层消失）
 * ```
 * ① ASTC KTX   → uploadIslandCliffKtx（仅 Vulkan 且设备支持 ASTC）
 *                  ├ 设备不支持/资产缺失/KTX 校验失败 → ②
 * ② RGBA mip 链 → uploadIslandCliffMipChain（仅 Vulkan）
 *                  └ 非 Vulkan → ③
 * ③ RGBA 单级   → uploadTextureDirect（GLES；Canvas 路径走位图不上传）
 *     任一步全败 → 该张 ID = 0 → 布局合成时以 textureMask 排除（部分降级）
 * ```
 *
 * ## 线程模型
 * [prepare] 在**后台线程**（解码 + RGBA/mip 编码 + 磁盘 IO），[upload] 在**主线程**
 * （仅 native 调用，与 `AtlasAsyncPipeline` 同纪律：native 侧 `g_renderer` 无锁，
 * 主线程调用是既有约定）。
 *
 * ## 内存口径（重要）
 * 崖壁 7 张源分辨率 RGBA8 合计 **117MB**；mip 链版 **≈160MB**。
 * 故 [prepare] 逐张「解码 → 编码 → 释放位图」，编码缓冲全部持有（上传需同步回退）
 * 是本模块的**显存/内存权衡点**：不支持 ASTC 的设备（GLES/部分 Vulkan）会占用
 * 上述量级。低端设备兜底见 [IslandCliffTextureLoader.upload] 的逐张失败降级；
 * 若真机实测吃紧，回退动作 = 调 `source-mapping.json` 的 bake 为 `maxDim`
 * （世界布局不变，仅纹理密度下降）。
 */
internal class IslandCliffTextureLoader(private val context: Context) {
    /**
     * 单张纹理的后台准备产物。
     *
     * @param index 纹理下标（[IslandCliffBridge.TextureIndex]）
     * @param width 烘焙宽（像素）
     * @param height 烘焙高（像素）
     * @param ktx KTX 资产字节（ASTC 可用时为非 null；否则 null 走 RGBA）
     * @param mipPixels level-major 紧凑 RGBA8（含全部 mip 级；RGBA 路径用）
     * @param mipCount mip 级数（含首级；= 1 时 [mipPixels] 即单级）
     */
    internal class Prepared(
        val index: Int,
        val width: Int,
        val height: Int,
        val ktx: ByteArray?,
        val mipPixels: ByteBuffer?,
        val mipCount: Int
    ) {
        /** 首级 RGBA（单级回退用——mip 链为 level-major，首级即完整图） */
        val singleLevel: ByteBuffer? get() = mipPixels
    }

    /**
     * 后台准备：逐张解码 + 编码（不触碰 native）。
     *
     * @param astcSupported 设备是否支持 ASTC（true 时优先只取 KTX 资产，省掉 RGBA 编码）
     * @return 逐张产物（下标序）；解码/编码失败张以 null 占位（后续降级跳过）
     */
    fun prepare(astcSupported: Boolean): List<Prepared?> {
        val out = ArrayList<Prepared?>(IslandCliffTextureSet.TEXTURE_COUNT)
        for (index in 0 until IslandCliffTextureSet.TEXTURE_COUNT) {
            out.add(prepareOne(index, astcSupported))
        }
        return out
    }

    /** 单张准备（失败记日志返回 null——调用方按缺失降级，不抛） */
    @Suppress("TooGenericExceptionCaught")  // 解码/分配失败模式无稳定异常契约（OOM/ROM 差异）
    private fun prepareOne(index: Int, astcSupported: Boolean): Prepared? {
        val width = IslandCliffTextureSet.widthOf(index).toInt()
        val height = IslandCliffTextureSet.heightOf(index).toInt()
        val ktx = if (astcSupported) readKtxAsset(index) else null
        if (ktx != null) {
            // ASTC 命中：无需 RGBA 编码（省 117MB 内存的关键路径）
            return Prepared(index, width, height, ktx, null, 1)
        }
        val bitmap = decodeCliffBitmap(index) ?: return null
        return try {
            if (bitmap.width != width || bitmap.height != height) {
                // 尺寸契约破坏（素材被替换但常量未同步）→ 以实测为准并告警：
                // 布局按常量算，纹理按实测传，UV 归一化后仍正确（仅尺寸表需修）
                android.util.Log.w(
                    LOG_TAG,
                    "texture[$index] 实测 ${bitmap.width}x${bitmap.height} " +
                        "与常量 ${width}x$height 不符——请同步 IslandCliffTextureSet.TEXTURE_SIZES"
                )
            }
            val mipCount = mipCountFor(bitmap.width, bitmap.height)
            val mip = encodeMipChain(bitmap, mipCount) ?: return null
            Prepared(index, bitmap.width, bitmap.height, null, mip, mipCount)
        } catch (t: Throwable) {
            android.util.Log.e(LOG_TAG, "prepare texture[$index] failed", t)
            null
        } finally {
            // 位图不 recycle（国产 ROM double-free 教训，同 AtlasAsyncPipeline）——
            // 置空让 GC 回收
            bitmap.recycleSafely()
        }
    }

    /**
     * 主线程上传（逐张；每张内部按 ①→②→③ 降级），并**注册到 C++ 侧纹理表**。
     *
     * 注册（[NativeBridge.setIslandCliffTextures]）在本方法内完成而非交给调用方：
     * 上传者才持有「纹理下标 → GPU ID」的映射，漏注册会让 drawIslandCliffs
     * 拿不到任何 ID 而整层不画（静默无输出，难排查）。
     *
     * @return 长度 = 纹理数的 ID 表；0 = 该张不可用（布局以 textureMask 排除）
     */
    fun upload(prepared: List<Prepared?>): IntArray {
        val ids = IntArray(IslandCliffTextureSet.TEXTURE_COUNT)
        for (index in prepared.indices) {
            val p = prepared[index] ?: continue
            ids[index] = uploadOne(p)
        }
        NativeBridge.setIslandCliffTextures(ids)
        return ids
    }

    /** 单张上传：① ASTC → ② mip 链 → ③ 单级（异常按「该张缺失」降级返回 0） */
    private fun uploadOne(p: Prepared): Int {
        val id = try {
            uploadCliffTexture(p)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // 刻意的防御性 catch：native 上传失败模式无稳定异常契约（跨 JNI/驱动/GL）
            android.util.Log.e(LOG_TAG, "cliff[${p.index}] 上传失败，跳过该张", t)
            0
        }
        return id
    }

    /** 上传主体（无异常路径：失败均以 0 返回，由调用方 textureMask 排除） */
    private fun uploadCliffTexture(p: Prepared): Int {
        val ktx = p.ktx
        if (ktx != null) {
            val id = NativeBridge.uploadIslandCliffKtx(ktx)
            if (id == 0) {
                // ASTC 失败（设备不支持/驱动拒绝）→ 无 RGBA 缓冲，该张不可用
                // 由调用方以 textureMask 排除（prepare 阶段已知 astcSupported，
                // 此路径只在「报告支持但上传被拒」时命中）
                android.util.Log.w(LOG_TAG, "cliff[${p.index}] ASTC 上传被拒，该张降级跳过")
            }
            return id
        }
        val mip = p.mipPixels ?: return 0
        if (p.mipCount > 1) {
            val id = NativeBridge.uploadIslandCliffMipChain(mip, p.width, p.height, p.mipCount)
            if (id != 0) return id
        }
        return NativeBridge.uploadTextureDirect(mip, p.width, p.height)
    }

    // ── 内部工具 ──

    /** 解码崖壁 drawable（失败返回 null 并按缺失降级） */
    @Suppress("TooGenericExceptionCaught")
    private fun decodeCliffBitmap(index: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(context.resources, IslandCliffTextureSet.TEXTURE_DRAWABLES[index], opts)
    } catch (t: Throwable) {
        android.util.Log.e(LOG_TAG, "decode texture[$index] failed", t)
        null
    }

    /** 读取 KTX 资产（不存在/读取失败返回 null → 走 RGBA 路径） */
    @Suppress("TooGenericExceptionCaught")
    private fun readKtxAsset(index: Int): ByteArray? = try {
        val name = IslandCliffTextureSet.TEXTURE_KTX_ASSETS[index]
        context.assets.open(name).use { it.readBytes() }
    } catch (ignored: Throwable) {
        // 资产缺失不是错误（RGBA 回退路径本就可用，与 build-edge-ktx.mjs 的
        // 「产物缺失不致命」契约一致）——debug 级记录便于排查
        android.util.Log.d(LOG_TAG, "KTX 资产缺失 texture[$index]，走 RGBA 回退")
        null
    }

    companion object {
        private const val LOG_TAG = "IslandCliff"

        /** mip 级数（与 C++ KtxLoader 的 `max(4, base>>level)` 级数口径一致） */
        internal fun mipCountFor(width: Int, height: Int): Int {
            var count = 1
            var w = width
            var h = height
            while (w > 4 || h > 4) {
                w = (w shr 1).coerceAtLeast(4)
                h = (h shr 1).coerceAtLeast(4)
                count++
            }
            return count
        }

        /**
         * RGBA mip 链编码（level-major 紧凑布局，与 `uploadIslandCliffMipChain`
         * 消费契约一致：首级 = 完整 width×height，逐级 `max(4, base>>level)`）。
         *
         * 与 C++/`lib/ktx1.mjs` 的级尺寸推导**同式**——否则 Vulkan 侧逐级
         * VkBufferImageCopy 的 extent 会与缓冲布局错位。
         *
         * 实现要点：每级**一次性**取整幅像素（`getPixels` 逐行调用会产生
         * 数千次 JNI 往返——1180×3552 逐行取曾测得数百毫秒级阻塞）。
         */
        internal fun encodeMipChain(source: Bitmap, mipCount: Int): ByteBuffer? {
            var total = 0
            var w = source.width
            var h = source.height
            for (level in 0 until mipCount) {
                total += w * h * BYTES_PER_PIXEL
                if (level < mipCount - 1) {
                    w = (w shr 1).coerceAtLeast(4)
                    h = (h shr 1).coerceAtLeast(4)
                }
            }
            val buffer = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
            var level = 0
            var current: Bitmap = source
            while (level < mipCount) {
                val lw = current.width
                val lh = current.height
                val argb = IntArray(lw * lh)
                current.getPixels(argb, 0, lw, 0, 0, lw, lh)
                val rgba = ByteArray(lw * lh * BYTES_PER_PIXEL)
                argbToRgba(argb, rgba)
                buffer.put(rgba)
                level++
                if (level >= mipCount) break
                val nextW = (lw shr 1).coerceAtLeast(4)
                val nextH = (lh shr 1).coerceAtLeast(4)
                current = Bitmap.createScaledBitmap(current, nextW, nextH, true)
            }
            buffer.rewind()
            return buffer
        }

        private const val BYTES_PER_PIXEL = 4

        /** ARGB_8888 数组 → RGBA8 字节（整幅一次转换） */
        private fun argbToRgba(argb: IntArray, out: ByteArray) {
            for (i in argb.indices) {
                val c = argb[i]
                val o = i * BYTES_PER_PIXEL
                out[o] = ((c shr 16) and 0xFF).toByte()      // R
                out[o + 1] = ((c shr 8) and 0xFF).toByte()   // G
                out[o + 2] = (c and 0xFF).toByte()           // B
                out[o + 3] = ((c ushr 24) and 0xFF).toByte() // A
            }
        }

        /** 安全回收（失败不影响主流程） */
        private fun Bitmap.recycleSafely() {
            try {
                if (!isRecycled) recycle()
            } catch (_: Throwable) {
                // 忽略：回收失败交给 GC（国产 ROM 回收异常不宜打断加载）
            }
        }
    }
}
