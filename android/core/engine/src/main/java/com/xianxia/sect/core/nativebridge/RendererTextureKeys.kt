package com.xianxia.sect.core.nativebridge

/**
 * TextureKey 位段 schema 的 Kotlin 镜像（与 C++ `TextureCache.h` / `texture_key`
 * 命名空间**同值同语义**；方案 D2.1——禁止裸 Long 自造键）。
 *
 * 布局：`[63:48] format | [47:32] variant | [31:0] assetId`。
 * 同一资产的不同压缩臂（ASTC/RGBA）必须不同键（format 位段区分）。
 * 双端一致由 [com.xianxia.sect.core.nativebridge.RendererTextureKeysMirrorGuardTest]
 * 与 C++ `texture_cache_test` 共同锁定。
 *
 * 生产调用点（[NativeBridge.textureAcquire] / `textureRelease` / 上传路径登记）
 * 必须只经本对象常量族——`TextureUploadPathGuardTest` 扫描源码锁定。
 */
object RendererTextureKeys {

    // ── format 位段 [63:48]（合法值仅两个）──
    const val FORMAT_RGBA8 = 1
    const val FORMAT_ASTC4X4 = 2

    // ── variant 位段 [47:32] ──
    const val VARIANT_SINGLE_LEVEL = 0
    const val VARIANT_MIP_CHAIN = 1
    const val VARIANT_REPEAT = 2
    const val VARIANT_REPEAT_MIP_CHAIN = 3

    // ── assetId 位段 [31:0] ──
    const val ASSET_ATLAS = 1
    const val ASSET_GROUND_GRASS = 2
    const val ASSET_ROCK_BASE = 3

    /** 位段打包（与 C++ `TextureKey::pack` 恒等） */
    fun pack(assetId: Int, format: Int, variant: Int): Long =
        (format.toLong() shl 48) or (variant.toLong() shl 32) or assetId.toLong()

    /** pack 的逆变换（与 C++ `TextureKey::unpack` 恒等；观测/日志用） */
    fun unpack(packed: Long): Triple<Int, Int, Int> {
        val assetId = (packed and 0xFFFF_FFFFL).toInt()
        val variant = ((packed ushr 32) and 0xFFFFL).toInt()
        val format = ((packed ushr 48) and 0xFFFFL).toInt()
        return Triple(assetId, format, variant)
    }

    // ── 生产键常量（与 C++ NativeBridge.cpp 辅助函数 / texture_cache_test 同值）──

    /** 主图集 ASTC 臂（KTX mip 链；pinned） */
    val KEY_ATLAS_ASTC: Long =
        pack(ASSET_ATLAS, FORMAT_ASTC4X4, VARIANT_MIP_CHAIN)

    /** 主图集 RGBA mip 链臂（ASTC 失败回退的多级路径；pinned） */
    val KEY_ATLAS_RGBA_MIP: Long =
        pack(ASSET_ATLAS, FORMAT_RGBA8, VARIANT_MIP_CHAIN)

    /** 主图集 RGBA 单级臂（mip 被拒后的单级回退；pinned） */
    val KEY_ATLAS_RGBA_SINGLE: Long =
        pack(ASSET_ATLAS, FORMAT_RGBA8, VARIANT_SINGLE_LEVEL)

    /** 地皮草 REPEAT（map_grass_1；当前 surface 必需 = pinned） */
    val KEY_GROUND: Long =
        pack(ASSET_GROUND_GRASS, FORMAT_RGBA8, VARIANT_REPEAT)

    /** 底部岩石 REPEAT（map_rock_base；当前 surface 边缘材质 = pinned） */
    val KEY_ROCK: Long =
        pack(ASSET_ROCK_BASE, FORMAT_RGBA8, VARIANT_REPEAT)

    /** 全部生产键（轮次释放/镜像守卫遍历用） */
    val ALL_PRODUCTION_KEYS: List<Long> = listOf(
        KEY_ATLAS_ASTC,
        KEY_ATLAS_RGBA_MIP,
        KEY_ATLAS_RGBA_SINGLE,
        KEY_GROUND,
        KEY_ROCK,
    )
}
