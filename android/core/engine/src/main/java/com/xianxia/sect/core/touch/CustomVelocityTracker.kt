package com.xianxia.sect.core.touch

import kotlin.math.abs

/**
 * 跨平台速度追踪器（替代 Android VelocityTracker）。
 *
 * 保留最近 N 帧的位置采样，抬起时用**最小二乘法**拟合速度。
 * 纯 Kotlin 实现，零 Android 依赖，可在 iOS 侧复用。
 *
 * 参考：Android FlingCalculator (friction=0.015)、
 *       Flutter ClampingScrollSimulation (threshold=25px/s)
 *
 * @param historySize 保留的历史帧数，越大速度越平滑但延迟越高
 */
class CustomVelocityTracker(
    private val historySize: Int = 6
) {
    private data class Sample(
        val x: Float,
        val y: Float,
        val time: Long // nanoTime
    )

    private val samples = ArrayDeque<Sample>(historySize)

    /** 添加一帧位置采样 */
    fun addPosition(x: Float, y: Float, time: Long) {
        if (samples.size >= historySize) {
            samples.removeFirst()
        }
        samples.addLast(Sample(x, y, time))
    }

    /**
     * 计算当前速度 (px/s)。
     * 使用**二次最小二乘回归**（匹配 Android VelocityTracker LSQ2 策略），
     * 速度 = 拟合多项式在最新采样点处的一阶导数。
     *
     * 当采样点不足 3 个时降级到线性最小二乘（一次拟合），
     * 确保 2 个采样点时也能给出合理速度估计。
     *
     * 参考：Android AOSP frameworks/native/libs/input/VelocityTracker.cpp
     *       — Jeff Brown (2011), degree=2, historySize=20, horizon=100ms
     *
     * 至少需要 2 个采样点。
     */
    fun computeVelocity(): Velocity2D {
        val n = samples.size
        if (n < 2) return Velocity2D(0f, 0f)
        return leastSquaresVelocity(samples, n)
    }

    /** 估算当前位置的瞬时速度（使用最近 3 帧，同样二次最小二乘） */
    fun computeInstantVelocity(): Velocity2D {
        val recent = samples.takeLast(3)
        val n = recent.size
        if (n < 2) return Velocity2D(0f, 0f)
        return leastSquaresVelocity(recent, n)
    }

    /**
     * 最小二乘拟合速度计算核心。
     * n ≥ 3 → 二次拟合（3 系数），n = 2 → 一次拟合（2 系数）。
     * 速度 = 拟合曲线在最新采样点处的一阶导数。
     */
    private fun leastSquaresVelocity(data: List<Sample>, n: Int): Velocity2D {
        val t0 = data.last().time  // 时间原点 = 最新采样点（速度求值点）

        if (n < 3) {
            // 线性最小二乘（一次拟合）：position = B0 + B1*t，速度 = B1
            var sumT = 0.0; var sumTT = 0.0
            var sumX = 0.0; var sumTX = 0.0
            var sumY = 0.0; var sumTY = 0.0

            for (s in data) {
                val t = (s.time - t0) / 1_000_000_000.0
                sumT += t; sumTT += t * t
                sumX += s.x.toDouble(); sumTX += t * s.x.toDouble()
                sumY += s.y.toDouble(); sumTY += t * s.y.toDouble()
            }

            val denom = n * sumTT - sumT * sumT
            if (kotlin.math.abs(denom) < 1e-12) return Velocity2D(0f, 0f)

            return Velocity2D(
                x = ((n * sumTX - sumT * sumX) / denom).toFloat(),
                y = ((n * sumTY - sumT * sumY) / denom).toFloat()
            )
        }

        // 二次最小二乘拟合：position = B0 + B1*t + B2*t²
        // t0 = 最新采样点 → 最新点处 t=0，速度 = B1（一阶导数）
        // 参考 Android AOSP VelocityTracker LSQ2 策略

        var sumT = 0.0; var sumT2 = 0.0; var sumT3 = 0.0; var sumT4 = 0.0
        var sumX = 0.0; var sumTX = 0.0; var sumT2X = 0.0
        var sumY = 0.0; var sumTY = 0.0; var sumT2Y = 0.0

        for (s in data) {
            val t = (s.time - t0) / 1_000_000_000.0
            val t2 = t * t
            sumT += t; sumT2 += t2; sumT3 += t2 * t; sumT4 += t2 * t2
            sumX += s.x.toDouble()
            sumTX += t * s.x.toDouble()
            sumT2X += t2 * s.x.toDouble()
            sumY += s.y.toDouble()
            sumTY += t * s.y.toDouble()
            sumT2Y += t2 * s.y.toDouble()
        }

        /* 正规方程组 (normal equations):
         * [ n     Σt   Σt² ] [B0]   [Σx]
         * [ Σt   Σt²  Σt³ ] [B1] = [Σtx]
         * [ Σt²  Σt³  Σt⁴ ] [B2]   [Σt²x]
         * 用高斯消元求解，速度 = B1
         */
        val m = arrayOf(
            doubleArrayOf(n.toDouble(), sumT, sumT2),
            doubleArrayOf(sumT, sumT2, sumT3),
            doubleArrayOf(sumT2, sumT3, sumT4)
        )
        val bx = doubleArrayOf(sumX, sumTX, sumT2X)
        val by = doubleArrayOf(sumY, sumTY, sumT2Y)

        // 高斯消元（3x3 列主元）
        for (col in 0..1) {
            // 选主元
            var maxRow = col
            for (row in col..2) {
                if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[maxRow][col])) {
                    maxRow = row
                }
            }
            if (kotlin.math.abs(m[maxRow][col]) < 1e-12) return Velocity2D(0f, 0f)
            // 交换行
            val tmpM = m[col]; m[col] = m[maxRow]; m[maxRow] = tmpM
            val tmpX = bx[col]; bx[col] = bx[maxRow]; bx[maxRow] = tmpX
            val tmpY = by[col]; by[col] = by[maxRow]; by[maxRow] = tmpY
            // 消去下方行
            for (row in (col + 1)..2) {
                val factor = m[row][col] / m[col][col]
                for (c in col..2) m[row][c] -= factor * m[col][c]
                bx[row] -= factor * bx[col]
                by[row] -= factor * by[col]
            }
        }
        // 回代
        val coeffX = DoubleArray(3)
        val coeffY = DoubleArray(3)
        for (row in 2 downTo 0) {
            coeffX[row] = bx[row]
            coeffY[row] = by[row]
            for (c in (row + 1)..2) {
                coeffX[row] -= m[row][c] * coeffX[c]
                coeffY[row] -= m[row][c] * coeffY[c]
            }
            coeffX[row] /= m[row][row]
            coeffY[row] /= m[row][row]
        }

        return Velocity2D(
            x = coeffX[1].toFloat(),
            y = coeffY[1].toFloat()
        )
    }

    /** 清除所有历史 */
    fun clear() {
        samples.clear()
    }

    /** 当前速度（标量） */
    fun speed(): Float {
        val v = computeVelocity()
        return kotlin.math.sqrt(v.x * v.x + v.y * v.y)
    }
}

/**
 * 二维速度（纯 Kotlin 跨平台）。
 */
data class Velocity2D(
    val x: Float,
    val y: Float
)
