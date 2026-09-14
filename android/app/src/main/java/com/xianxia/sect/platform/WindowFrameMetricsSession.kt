package com.xianxia.sect.platform

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import com.xianxia.sect.core.perf.FrameMetricsSession

/**
 * Window 帧指标采集实现（FrameMetricsSession 端口）。
 * 采集语义随端口化自原 FrameMetricsMonitor.startMonitoring 原样迁入：
 * DRAW_DURATION/LAYOUT_MEASURE_DURATION 仅 API 31+ 可用（低版本以 -1 表示）。
 */
@RequiresApi(Build.VERSION_CODES.N)
class WindowFrameMetricsSession(private val window: Window) : FrameMetricsSession {

    private var listener: Window.OnFrameMetricsAvailableListener? = null

    override fun start(onSample: (totalDurationNs: Long, drawDurationNs: Long, layoutDurationNs: Long) -> Unit) {
        val l = object : Window.OnFrameMetricsAvailableListener {
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
                onSample(total, draw, layout)
            }
        }
        listener = l
        window.addOnFrameMetricsAvailableListener(l, Handler(Looper.getMainLooper()))
    }

    override fun stop() {
        listener?.let { window.removeOnFrameMetricsAvailableListener(it) }
        listener = null
    }
}
