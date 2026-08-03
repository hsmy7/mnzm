package com.xianxia.sect.core.performance

import com.xianxia.sect.core.concurrent.DeviceCapabilityProfiler
import com.xianxia.sect.core.concurrent.ThermalController

enum class TextureCompressionFormat(
    val label: String,
    val bytesPerPixel: Float,
    val supportsAlpha: Boolean
) {
    ASTC_6x6("astc_6x6", 0.67f, true),
    ASTC_8x8("astc_8x8", 0.38f, true),
    ETC2_RGB("etc2_rgb", 0.50f, false),
    ETC2_RGBA("etc2_rgba", 0.50f, true),
    ETC1("etc1", 0.50f, false),
    WEBP("webp_lossless", 1.33f, true);

    companion object {
        fun forTier(tier: TextureTier): TextureCompressionFormat = when (tier) {
            TextureTier.HIGH -> ASTC_6x6
            TextureTier.MEDIUM -> ASTC_8x8
            TextureTier.LOW -> WEBP
        }
    }
}

enum class TextureTier(
    val label: String,
    val priority: Int
) {
    HIGH("high", 3),
    MEDIUM("medium", 2),
    LOW("low", 1);

    companion object {
        fun fromProfiler(profiler: DeviceCapabilityProfiler): TextureTier = when {
            profiler.isHighEnd -> HIGH
            profiler.isLowEnd -> LOW
            else -> MEDIUM
        }
    }
}

data class TextureCompressionConfig(
    val tier: TextureTier,
    val preferredFormat: TextureCompressionFormat,
    val atlasSize: Int,
    val use565: Boolean,
    val downscaleFactor: Float,
    val spriteMaxDimension: Int,
    val reduceAtlasResolution: Boolean
) {
    val atlasPixelCount: Int get() = atlasSize * atlasSize

    val atlasEstimatedBytes: Long get() =
        atlasPixelCount.toLong() * if (use565) 2L else 4L

    val isLowTier: Boolean get() = tier == TextureTier.LOW

    val isHighTier: Boolean get() = tier == TextureTier.HIGH

    val allowMipmaps: Boolean get() = !isLowTier

    fun preferredConfig565(): Boolean = use565

    fun sampleSize(): Int = if (isLowTier) 2 else 1

    companion object {
        val HIGH = TextureCompressionConfig(
            tier = TextureTier.HIGH,
            preferredFormat = TextureCompressionFormat.ASTC_6x6,
            atlasSize = 2048,
            use565 = false,
            downscaleFactor = 1.0f,
            spriteMaxDimension = 300,
            reduceAtlasResolution = false
        )

        val MEDIUM = TextureCompressionConfig(
            tier = TextureTier.MEDIUM,
            preferredFormat = TextureCompressionFormat.ASTC_8x8,
            atlasSize = 2048,
            use565 = true,
            downscaleFactor = 0.875f,
            spriteMaxDimension = 256,
            reduceAtlasResolution = false
        )

        val LOW = TextureCompressionConfig(
            tier = TextureTier.LOW,
            preferredFormat = TextureCompressionFormat.WEBP,
            atlasSize = 1024,
            use565 = true,
            downscaleFactor = 0.75f,
            spriteMaxDimension = 200,
            reduceAtlasResolution = true
        )

        private val THERMAL_YELLOW = TextureCompressionConfig(
            tier = TextureTier.MEDIUM,
            preferredFormat = TextureCompressionFormat.ETC2_RGBA,
            atlasSize = 2048,
            use565 = true,
            downscaleFactor = 0.8f,
            spriteMaxDimension = 220,
            reduceAtlasResolution = false
        )

        private val THERMAL_ORANGE = TextureCompressionConfig(
            tier = TextureTier.LOW,
            preferredFormat = TextureCompressionFormat.ETC2_RGB,
            atlasSize = 1024,
            use565 = true,
            downscaleFactor = 0.65f,
            spriteMaxDimension = 180,
            reduceAtlasResolution = true
        )

        private val THERMAL_RED = TextureCompressionConfig(
            tier = TextureTier.LOW,
            preferredFormat = TextureCompressionFormat.ETC1,
            atlasSize = 1024,
            use565 = true,
            downscaleFactor = 0.5f,
            spriteMaxDimension = 140,
            reduceAtlasResolution = true
        )

        fun forProfiler(profiler: DeviceCapabilityProfiler): TextureCompressionConfig {
            return when (TextureTier.fromProfiler(profiler)) {
                TextureTier.HIGH -> if (profiler.totalRamGB >= 8) HIGH
                    else HIGH.copy(spriteMaxDimension = 280)
                TextureTier.LOW -> LOW
                TextureTier.MEDIUM -> if (profiler.totalCores >= 6 && profiler.totalRamGB >= 4) MEDIUM
                    else MEDIUM.copy(downscaleFactor = 0.8f, spriteMaxDimension = 220)
            }
        }

        fun downgradeByThermal(
            config: TextureCompressionConfig,
            thermalLevel: ThermalController.DegradationLevel
        ): TextureCompressionConfig = when (thermalLevel) {
            ThermalController.DegradationLevel.GREEN -> config
            ThermalController.DegradationLevel.YELLOW -> config.copy(
                use565 = true,
                spriteMaxDimension = (config.spriteMaxDimension * 0.85f).toInt()
                    .coerceAtMost(config.spriteMaxDimension - 20)
            )
            ThermalController.DegradationLevel.ORANGE -> THERMAL_ORANGE.copy(
                spriteMaxDimension = config.spriteMaxDimension.coerceAtMost(180)
            )
            ThermalController.DegradationLevel.RED -> THERMAL_RED.copy(
                spriteMaxDimension = config.spriteMaxDimension.coerceAtMost(140)
            )
        }

        fun resolve(
            profiler: DeviceCapabilityProfiler,
            thermalLevel: ThermalController.DegradationLevel
        ): TextureCompressionConfig {
            val base = forProfiler(profiler)
            return downgradeByThermal(base, thermalLevel)
        }
    }

    val summary: String get() =
        "TextureConfig[tier=${tier.label}, format=${preferredFormat.label}, " +
        "atlas=${atlasSize}x${atlasSize}, " +
        "pixelFormat=${if (use565) "RGB_565" else "ARGB_8888"}, " +
        "downscale=${"%.2f".format(downscaleFactor)}, " +
        "spriteMax=${spriteMaxDimension}px, " +
        "reduceAtlas=$reduceAtlasResolution, " +
        "mipmaps=$allowMipmaps, " +
        "estMem=${atlasEstimatedBytes / 1024}KB]"
}
