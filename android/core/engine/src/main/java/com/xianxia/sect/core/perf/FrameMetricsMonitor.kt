package com.xianxia.sect.core.perf

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.xianxia.sect.core.util.DomainLog
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class FrameMetricsEvent(
    val totalDurationNs: Long,
    val drawDurationNs: Long,
    val layoutDurationNs: Long,
    val isJank: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

@Singleton
class FrameMetricsMonitor @Inject constructor() {
    companion object {
        private const val TAG = "FrameMetrics"
        private const val JANK_THRESHOLD_NS = 16_666_667L
        private const val SEVERE_JANK_THRESHOLD_NS = 50_000_000L
    }

    private val _jankEvents = MutableSharedFlow<FrameMetricsEvent>(extraBufferCapacity = 64)
    val jankEvents: SharedFlow<FrameMetricsEvent> = _jankEvents.asSharedFlow()

    private var frameMetrics: FrameMetrics? = null
    private var observer: Any? = null
    private var isMonitoring = false

    private val totalFrames = AtomicLong(0)
    private val jankFrames = AtomicLong(0)
    private val severeJankFrames = AtomicLong(0)
    private val totalDurationNs = AtomicLong(0)

    @RequiresApi(Build.VERSION_CODES.N)
    fun startMonitoring(window: Window) {
        if (isMonitoring) return
        try {
            observer = object : Window.OnFrameMetricsAvailableListener {
                override fun onFrameMetricsAvailable(
                    window: Window?,
                    frameMetrics: FrameMetrics?,
                    dropCount: Int
                ) {
                    if (frameMetrics == null) return
                    val total = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val draw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
                    } else -1L
                    val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                    } else -1L

                    totalFrames.incrementAndGet()
                    totalDurationNs.addAndGet(total)

                    val isJank = total > JANK_THRESHOLD_NS
                    if (isJank) {
                        jankFrames.incrementAndGet()
                        val isSevere = total > SEVERE_JANK_THRESHOLD_NS
                        if (isSevere) severeJankFrames.incrementAndGet()

                        _jankEvents.tryEmit(FrameMetricsEvent(
                            totalDurationNs = total,
                            drawDurationNs = draw,
                            layoutDurationNs = layout,
                            isJank = true
                        ))

                        if (isSevere) {
                            DomainLog.w(TAG, "Severe jank: ${total / 1_000_000}ms (draw=${draw / 1_000_000}ms, layout=${layout / 1_000_000}ms)")
                        }
                    }
                }
            }
            window.addOnFrameMetricsAvailableListener(
                observer as Window.OnFrameMetricsAvailableListener,
                Handler(Looper.getMainLooper())
            )
            isMonitoring = true
            DomainLog.i(TAG, "FrameMetrics monitoring started")
        } catch (e: Exception) {
            DomainLog.e(TAG, "Failed to start FrameMetrics monitoring", e)
        }
    }

    fun stopMonitoring(window: Window) {
        if (!isMonitoring || observer == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                window.removeOnFrameMetricsAvailableListener(observer as Window.OnFrameMetricsAvailableListener)
            }
        } catch (e: Exception) {
            DomainLog.w(TAG, "Failed to stop FrameMetrics monitoring", e)
        }
        isMonitoring = false
        DomainLog.i(TAG, "FrameMetrics monitoring stopped. Stats: $getStatsSummary")
    }

    private val getStatsSummary: String
        get() = "frames=${totalFrames.get()}, jank=${jankFrames.get()}(${if (totalFrames.get() > 0) jankFrames.get() * 100 / totalFrames.get() else 0}%), severe=${severeJankFrames.get()}"

    fun getStats(): FrameMetricsStats = FrameMetricsStats(
        totalFrames = totalFrames.get(),
        jankFrames = jankFrames.get(),
        severeJankFrames = severeJankFrames.get(),
        averageFrameTimeMs = if (totalFrames.get() > 0) totalDurationNs.get() / totalFrames.get() / 1_000_000.0 else 0.0,
        jankRate = if (totalFrames.get() > 0) jankFrames.get().toDouble() / totalFrames.get() else 0.0
    )

    fun resetStats() {
        totalFrames.set(0)
        jankFrames.set(0)
        severeJankFrames.set(0)
        totalDurationNs.set(0)
    }
}

@Immutable
data class FrameMetricsStats(
    val totalFrames: Long,
    val jankFrames: Long,
    val severeJankFrames: Long,
    val averageFrameTimeMs: Double,
    val jankRate: Double
)
