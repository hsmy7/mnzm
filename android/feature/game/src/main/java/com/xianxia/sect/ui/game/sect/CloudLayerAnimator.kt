package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.SpriteAtlasDef
import kotlin.random.Random

/**
 * 云层动画引擎 — 世界顶部动态云朵（纯 Kotlin，无平台依赖，iOS 可移植）。
 *
 * ## 行为契约（需求硬性规定）
 * - **只在世界外生成**：右移云朵生成在左边缘外（`x + w ≤ 0`），左移云朵生成在右边缘外
 *   （`x ≥ worldW`），随后横向穿越世界；
 * - **在世界外消失**：完全移出对侧边缘（右移 → `x ≥ worldW`；左移 → `x + w ≤ 0`）后销毁；
 * - **速度固定 3 格/秒**（1 格 = [GameConfig.SectMap.TILE_SIZE] 世界像素）；
 * - **随机**：随机云层类型（5 种精灵）、随机方向、随机 Y（世界顶部条带内）、
 *   随机缩放/透明度、随机生成间隔与并发目标数。
 *
 * ## 驱动方式
 * 渲染线程每节拍调用 [update]（即使跳帧也随节拍唤醒推进——生成定时器不因静止画面卡死），
 * 返回值 = 本帧是否必须渲染（云朵移动/生成/销毁）。双后端（Vulkan/Canvas）只消费
 * [snapshot] 输出的同一份实例数据快照，保证像素级一致。
 *
 * ## 确定性
 * 通过构造参数注入 [Random]（测试传固定 seed，生产传世界种子派生的固定实例）与
 * 外部传入时间戳，可复现。**不得有默认值**：原 `random: Random = Random.Default`
 * 是 R5 明令禁止的"默认值陷阱"——任何省略实参的调用方都会静默接到
 * `Random.Default`（进程启动随机、不入档、不可复现）。云朵为纯表现装饰
 *（不进存档、不参与引擎确定性分区），故用**局部固定种子实例**而非
 * `PresentationRandom`（后者为 UI/引擎共享单例，本类构造于渲染线程且在测试中
 * 需要独立可控实例）。
 */
class CloudLayerAnimator(
    /** 世界像素宽度（云朵在 [0, worldWidthPx) 区间内穿越） */
    private val worldWidthPx: Float,
    /** 表现随机源（**必传**——见类 KDoc"确定性"段） */
    private val random: Random
) {

    /** 活跃云朵实例（渲染线程单消费者，无锁） */
    private val clouds = mutableListOf<Cloud>()

    /** 上次 [update] 时间戳（纳秒；0 = 首次调用未初始化） */
    private var lastUpdateNs = 0L

    /** 下次生成时间戳（纳秒；0 = 尚未初始化） */
    private var nextSpawnNs = 0L

    /** 目标并发云数（每次生成后重 roll，保持云量起伏自然） */
    private var targetCount: Int = random.nextInt(TARGET_COUNT_MIN, TARGET_COUNT_MAX + 1)

    /**
     * 推进一帧（渲染线程每节拍调用；跳帧期间仍随节拍唤醒推进生成定时器）。
     *
     * @param nowNs 当前时间戳（纳秒）
     * @return true = 本帧必须渲染（有云朵移动/生成/销毁）；false = 可跳帧
     */
    fun update(nowNs: Long): Boolean {
        val dtMs = if (lastUpdateNs == 0L) {
            0L
        } else {
            (nowNs - lastUpdateNs).coerceIn(0L, DT_CLAMP_MS)
        }
        lastUpdateNs = nowNs

        var dirty = false

        // 1. 移动（x += dir × 速度 × dt）
        if (clouds.isNotEmpty()) {
            val stepPx = SPEED_PX_PER_MS * dtMs
            for (cloud in clouds) {
                cloud.x += cloud.dir * stepPx
            }
            dirty = true
        }

        // 2. 在世界外消失（完全移出对侧边缘）
        if (clouds.removeAll { it.isFullyOffWorld(worldWidthPx) }) {
            dirty = true
        }

        // 3. 只在世界外生成（维持目标并发数，按随机间隔节流）
        if (nextSpawnNs == 0L) {
            nextSpawnNs = nowNs + nextSpawnDelayMs()
        }
        if (nowNs >= nextSpawnNs) {
            if (clouds.size < targetCount) {
                clouds += spawnCloud()
                targetCount = random.nextInt(TARGET_COUNT_MIN, TARGET_COUNT_MAX + 1)
                dirty = true
            }
            nextSpawnNs = nowNs + nextSpawnDelayMs()
        }

        return dirty
    }

    /**
     * 当前云朵快照（渲染端单次消费，双后端共享）。
     *
     * @return `[x, y, w, h, spriteIndex, alpha] × N`，无云朵时返回 null
     */
    fun snapshot(): FloatArray? {
        if (clouds.isEmpty()) return null
        val data = FloatArray(clouds.size * CLOUD_DATA_STRIDE)
        for ((i, c) in clouds.withIndex()) {
            val base = i * CLOUD_DATA_STRIDE
            data[base] = c.x
            data[base + 1] = c.y
            data[base + 2] = c.w
            data[base + 3] = c.h
            data[base + 4] = c.spriteIndex.toFloat()
            data[base + 5] = c.alpha
        }
        return data
    }

    /** 当前活跃云朵数量（测试观测用） */
    fun activeCount(): Int = clouds.size

    // ── 私有：生成 / 参数 ──

    /** 生成一朵云（严格保证出生位置在世界外）。 */
    private fun spawnCloud(): Cloud {
        val spriteIndex = random.nextInt(CLOUD_TYPE_COUNT)
        val (nativeW, nativeH) = nativeSizeOf(spriteIndex)
        val scale = SCALE_MIN + random.nextFloat() * (SCALE_MAX - SCALE_MIN)
        val w = nativeW * scale
        val h = nativeH * scale
        val dir = if (random.nextBoolean()) DIR_RIGHT else DIR_LEFT
        // 世界外生成：右移 → 左缘外；左移 → 右缘外（各加随机余量，错开进入时机）
        val x = if (dir > 0) {
            -w - SPAWN_MARGIN_PX - random.nextFloat() * SPAWN_MARGIN_PX
        } else {
            worldWidthPx + SPAWN_MARGIN_PX + random.nextFloat() * SPAWN_MARGIN_PX
        }
        val y = random.nextFloat() * BAND_MAX_Y_PX
        val alpha = ALPHA_MIN + random.nextFloat() * (ALPHA_MAX - ALPHA_MIN)
        return Cloud(spriteIndex, x, y, w, h, dir, alpha)
    }

    /** 云层精灵原生尺寸（图集 rect，CLOUD_TYPE_COUNT 与 CLOUD_RECTS 同源）。 */
    private fun nativeSizeOf(spriteIndex: Int): Pair<Float, Float> {
        val rect = SpriteAtlasDef.CLOUD_RECTS.getOrNull(spriteIndex)?.second
            ?: SpriteAtlasDef.CLOUD_RECTS.first().second
        return rect.w.toFloat() to rect.h.toFloat()
    }

    /** 随机生成间隔（毫秒）。 */
    private fun nextSpawnDelayMs(): Long =
        random.nextLong(SPAWN_INTERVAL_MIN_MS, SPAWN_INTERVAL_MAX_MS + 1)

    /** 单朵云实例（x 可变，其余构造后不变）。 */
    private data class Cloud(
        val spriteIndex: Int,
        var x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val dir: Int,
        val alpha: Float
    ) {
        /** 完全移出世界（对侧边缘外）→ 可销毁。 */
        fun isFullyOffWorld(worldW: Float): Boolean =
            if (dir > 0) x >= worldW else x + w <= 0f
    }

    companion object {
        /** 云层移动速度（格/秒）——需求硬性规定（现实时间） */
        const val SPEED_TILES_PER_SECOND = 3

        /** 云层移动速度（世界像素/毫秒 = 3 格/秒 × tileSize / 1000） */
        val SPEED_PX_PER_MS: Float =
            SPEED_TILES_PER_SECOND * GameConfig.SectMap.TILE_SIZE / 1000f

        /** 云层顶部条带下界（世界像素；云朵 y ∈ [0, BAND_MAX_Y_PX)） */
        const val BAND_MAX_Y_PX = 480

        /** 目标并发云数区间（每次生成后重 roll） */
        const val TARGET_COUNT_MIN = 2
        const val TARGET_COUNT_MAX = 4

        /** 生成间隔区间（毫秒） */
        const val SPAWN_INTERVAL_MIN_MS = 1500L
        const val SPAWN_INTERVAL_MAX_MS = 4000L

        /**
         * 随机缩放区间（云朵大小 = 原生尺寸 × scale）。
         * 整体缩小 50%（0.8~1.6 → 0.4~0.8），所有云朵显示尺寸减半。
         */
        const val SCALE_MIN = 0.4f
        const val SCALE_MAX = 0.8f

        /** 随机透明度区间 */
        const val ALPHA_MIN = 0.85f
        const val ALPHA_MAX = 1.0f

        /** 生成/出界判定余量（世界像素）——保证生成时完全在视界外 */
        const val SPAWN_MARGIN_PX = 100f

        /** 帧间隔钳制（毫秒）——卡顿/后台恢复后云朵不瞬移 */
        const val DT_CLAMP_MS = 500L

        /** 云层数据单条步长（[x, y, w, h, spriteIndex, alpha]） */
        const val CLOUD_DATA_STRIDE = 6

        /** 云层精灵类型数（与 SpriteAtlasDef.CLOUD_RECTS 数量一致） */
        const val CLOUD_TYPE_COUNT = 5

        /** 右移（从左到右） */
        private const val DIR_RIGHT = 1

        /** 左移（从右到左） */
        private const val DIR_LEFT = -1
    }
}
