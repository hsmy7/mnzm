package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.umeng.UmengManager
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
// import com.huawei.agconnect.crash.AGConnectCrash  // 待 AGC Crash SDK 依赖就绪后启用
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局崩溃处理器
 * 负责捕获未处理异常、记录崩溃日志、管理崩溃状态
 */
@Singleton
@Suppress("TooManyFunctions") // 崩溃处理面：落盘/上传/积压重传/状态查询均为独立公共职责
class CrashHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"

        /** 崩溃日志打印的最大字符数（防崩溃处理期日志 IO 过大） */
        private const val MAX_LOG_STACK_LENGTH = 2000
        private const val PREFS_NAME = "crash_prefs"
        private const val KEY_CRASH_FLAG = "crash_flag"
        private const val KEY_CRASH_TIME = "crash_time"
        private const val KEY_CRASH_MESSAGE = "crash_message"
        private const val KEY_CRASH_STACK_TRACE = "crash_stack_trace"
        private const val CRASH_LOG_DIR = "crash_logs"
        private const val MAX_CRASH_LOGS = 5
        private const val CRASH_LOG_PREFIX = "crash_"
        private const val CRASH_LOG_EXTENSION = ".log"

        /** 单次上报的堆栈内容截断上限（崩溃处理期减小网络载荷与服务端压力） */
        private const val MAX_UPLOAD_CONTENT_LENGTH = 8000

        /** 崩溃上报连接超时（秒）——崩溃路径/启动路径都不应被慢网络拖住 */
        private const val CRASH_UPLOAD_CONNECT_TIMEOUT_SECONDS = 3L

        @Volatile
        private var instance: CrashHandler? = null

        /**
         * 获取单例实例
         */
        fun getInstance(): CrashHandler {
            return checkNotNull(instance) { "CrashHandler not initialized" }
        }

        /**
         * 初始化崩溃处理器
         */
        fun init(crashHandler: CrashHandler) {
            instance = crashHandler
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 注册崩溃处理器
     */
    fun register() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "CrashHandler registered")
    }

    /**
     * 取消注册崩溃处理器
     */
    fun unregister() {
        Thread.setDefaultUncaughtExceptionHandler(defaultExceptionHandler)
        Log.i(TAG, "CrashHandler unregistered")
    }

    private val handlingCrash = java.util.concurrent.atomic.AtomicBoolean(false)

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // TapTap lateinit 无害崩溃（SDK 未同意前其内部 Toast 抛出）：
        //   必须在记录/落盘/上报/进程退出之前直接吞掉——即使守卫已被 Bugly 覆盖、
        //   CrashHandler 成为默认处理器，也不得上报或导致进程退出。
        if (TapTapCrashGuard.isSuppressible(throwable)) {
            Log.w(TAG, "Suppressed TapTap lateinit crash (SDK not yet consented)", throwable)
            return
        }

        if (!handlingCrash.compareAndSet(false, true)) {
            // 友盟官方契约：kill 类杀进程前保存统计数据（全防御封装，异常不影响退出）
            UmengManager.onKillProcess(context)
            Process.killProcess(Process.myPid())
            return
        }

        // 使用 stackTraceToString() 避免 printStackTrace(Writer) 在
        //    StackOverflowError / 循环 cause 链场景下二次崩溃
        val stackTrace = try {
            throwable.stackTraceToString()
        } catch (e: Exception) {
            "Stack trace unavailable: ${e.message}"
        }

        // 日志截断：崩溃处理期系统日志 IO 同样占用崩溃线程（沙盒 hook 下被放大，
        // 见 Bugly #13006），全量 stackTrace 可能极大，前 2000 字符足够定位
        Log.e(TAG, "Uncaught exception in thread ${thread.name}\n${stackTrace.take(MAX_LOG_STACK_LENGTH)}")

        try {
            // 1. 通知崩溃自愈引擎（用于安全模式判定；传线程名——归因收窄：
            //    只有渲染链线程的崩溃计入安全模式，OOM/SDK 崩溃不再误判）
            CrashRecoveryEngine.recordCrash(stackTrace, thread.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify CrashRecoveryEngine", e)
        }

        try {
            // 2. 记录崩溃日志到文件（传入已计算的 stackTrace 避免二次 printStackTrace）
            val crashLogFile = writeCrashLogToFile(thread, throwable, stackTrace)

            // 3. 上传崩溃日志到远程服务器
            tryUploadCrashLog(crashLogFile)

            Log.i(TAG, "Crash handling completed, crash log saved to: ${crashLogFile?.absolutePath}")
        } catch (e: Exception) {
            // 确保崩溃处理本身不会抛出异常
            Log.e(TAG, "Error during crash handling", e)
        }

        // 调用默认的异常处理器
        defaultExceptionHandler?.uncaughtException(thread, throwable)
            ?: run {
                // 友盟官方契约：kill 类杀进程前保存统计数据（全防御封装，异常不影响退出）
                UmengManager.onKillProcess(context)
                Process.killProcess(Process.myPid())
            }
    }

    /**
     * 尝试上传崩溃日志到远程服务器（崩溃路径即时上传）。
     *
     * 崩溃时刻进程即将退出、网络状态未知——失败不重试（由下次启动的
     * [uploadPendingCrashLogs] 积压重传兜底），仅记录结果日志。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 崩溃路径异常源不可枚举, 降级跳过+日志留痕, 非静默吞噬
    private fun tryUploadCrashLog(crashLogFile: File?) {
        if (crashLogFile == null || !crashLogFile.exists()) return
        val content = try {
            crashLogFile.readText().take(MAX_UPLOAD_CONTENT_LENGTH)
        } catch (e: Exception) {
            Log.w(TAG, "崩溃日志读取失败，跳过即时上报（本地保留）", e)
            return
        }
        Thread {
            val success = postCrashReport(content)
            Log.i(TAG, "崩溃即时上报${if (success) "成功" else "失败（本地保留，待启动重传）"}")
        }.start()
    }

    /**
     * 重传本地积压的崩溃日志（R0.5 遥测最小闭环：下次启动兜底通道）。
     *
     * 崩溃时刻的上传大概率失败（进程退出/网络不可达），闭环的关键是
     * **启动时扫描 crash_logs 积压并重传**：上传成功即删除本地文件，
     * 失败保留待下次启动。必须在后台线程调用（同步网络请求）。
     *
     * @param uploader 上传函数（默认 [postCrashReport]；测试注入桩）
     * @return 成功上传并清理的日志数
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: IO/网络异常源不可枚举, 单文件失败不阻断其余积压+日志留痕
    internal fun uploadPendingCrashLogs(
        uploader: (content: String) -> Boolean = { postCrashReport(it) }
    ): Int {
        var uploaded = 0
        for (file in getCrashLogFiles()) {
            try {
                val content = file.readText().take(MAX_UPLOAD_CONTENT_LENGTH)
                if (uploader(content) && file.delete()) {
                    uploaded++
                    Log.i(TAG, "积压崩溃日志已上传并清理: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "积压崩溃日志上传失败（保留待下次）: ${file.name}", e)
            }
        }
        return uploaded
    }

    /**
     * POST 崩溃日志到服务端（`${API_BASE_URL}crash-report`）。
     *
     * @return HTTP 2xx 视为成功；网络/服务端异常返回 false（失败已记日志，非静默）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 网络异常源不可枚举, 失败返回false+日志留痕, 非静默吞噬
    private fun postCrashReport(content: String): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(CRASH_UPLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val body = FormBody.Builder()
                .add("version", BuildConfig.VERSION_NAME)
                .add("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .add("sdk", Build.VERSION.SDK_INT.toString())
                .add("stack", content)
                .build()
            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}crash-report")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "崩溃上报请求失败（本地日志保留）", e)
            false
        }
    }


    /**
     * 记录被捕获异常到本地崩溃日志（供引擎循环异常等非崩溃路径归因）。
     *
     * 与 [uncaughtException] 的区别：不触发崩溃状态标记/上报通道，仅落盘，
     * 作为 Bugly 不可用时的兜底（引擎模块异常归因端口 EngineCrashReporter 的回退）。
     *
     * @param throwable 被捕获的异常
     * @param context 结构化上下文（年/月/旬/tickCount 等，写入日志头部）
     */
    fun recordCaughtException(throwable: Throwable, context: Map<String, String> = emptyMap()) {
        val stackTrace = buildString {
            if (context.isNotEmpty()) {
                append("Context: ")
                append(context.entries.joinToString(", ") { "${it.key}=${it.value}" })
                append("\n\n")
            }
            append(throwable.stackTraceToString())
        }
        writeCrashLogToFile(Thread.currentThread(), throwable, stackTrace)
    }

    /**
     * 将崩溃日志写入文件
     *
     * @param stackTrace 预计算的堆栈跟踪字符串（避免在崩溃处理中调用 printStackTrace）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    private fun writeCrashLogToFile(thread: Thread, throwable: Throwable, stackTrace: String): File? {
        return try {
            val crashLogDir = getCrashLogDir()
            cleanupOldCrashLogs(crashLogDir)

            val timestamp = fileDateFormat.format(Date())
            val crashLogFile = File(crashLogDir, "$CRASH_LOG_PREFIX$timestamp$CRASH_LOG_EXTENSION")

            FileWriter(crashLogFile, false).use { writer ->
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("=== Crash Log ===")
                    printWriter.println("Time: ${dateFormat.format(Date())}")
                    printWriter.println("Thread: ${thread.name} (id=${thread.id})")
                    printWriter.println("Process: ${Process.myPid()}")
                    printWriter.println()

                    printWriter.println("=== Device Info ===")
                    printWriter.println("Brand: ${Build.BRAND}")
                    printWriter.println("Device: ${Build.DEVICE}")
                    printWriter.println("Model: ${Build.MODEL}")
                    printWriter.println("Product: ${Build.PRODUCT}")
                    printWriter.println("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    printWriter.println()

                    writeAppInfoSection(printWriter)

                    writeRenderMetricsSection(printWriter)

                    printWriter.println("=== Exception ===")
                    printWriter.println("Type: ${throwable.javaClass.name}")
                    printWriter.println("Message: ${throwable.message}")
                    printWriter.println()

                    printWriter.println("=== Stack Trace ===")
                    printWriter.println(stackTrace)
                }
            }

            Log.d(TAG, "Crash log written to: ${crashLogFile.absolutePath}")
            crashLogFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log to file", e)
            null
        }
    }

    /**
     * 写入应用版本信息段（包信息查询失败时降级为 Unknown，不中断崩溃日志写入）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨SDK不可枚举, 降级继续, 非静默吞噬
    private fun writeAppInfoSection(printWriter: PrintWriter) {
        printWriter.println("=== App Info ===")
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            printWriter.println("Version Name: ${packageInfo.versionName}")
            @Suppress("NewApi")
            printWriter.println("Version Code: ${packageInfo.longVersionCode}")
        } catch (e: Exception) {
            printWriter.println("Version: Unknown (${e.javaClass.simpleName}: ${e.message})")
        }
        printWriter.println()
    }

    /**
     * 写入渲染健康段（RenderMetrics 快照——归因"崩溃前渲染是否已异常"，
     * GPU 驱动多样性场景下真机远程排查的主线索）。
     *
     * 纯内存读取本不会失败；防御性捕获仅为保证崩溃处理路径自身永不抛出
     * （该纪律优先于"provably safe 不加 catch"的一般原则）。
     */
    @Suppress("TooGenericExceptionCaught") // 崩溃路径防御兜底: 崩溃处理自身永不抛出的纪律优先
    private fun writeRenderMetricsSection(printWriter: PrintWriter) {
        try {
            printWriter.println("=== Render Metrics ===")
            printWriter.println(RenderMetrics.formatForCrashReport())
            printWriter.println()
        } catch (e: Exception) {
            printWriter.println("Render Metrics: unavailable (${e.javaClass.simpleName}: ${e.message})")
            printWriter.println()
        }
    }

    /**
     * 获取崩溃日志目录
     */
    private fun getCrashLogDir(): File {
        val dir = File(context.filesDir, CRASH_LOG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 清理旧的崩溃日志，保留最新的 MAX_CRASH_LOGS 个
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun cleanupOldCrashLogs(crashLogDir: File) {
        try {
            val logFiles = crashLogDir.listFiles { file ->
                file.name.startsWith(CRASH_LOG_PREFIX) && file.name.endsWith(CRASH_LOG_EXTENSION)
            }?.sortedByDescending { it.lastModified() }

            if (logFiles != null && logFiles.size > MAX_CRASH_LOGS) {
                deleteExcessCrashLogs(logFiles)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old crash logs", e)
        }
    }

    /**
     * 删除超出保留上限的旧崩溃日志
     */
    private fun deleteExcessCrashLogs(logFiles: List<File>) {
        logFiles.drop(MAX_CRASH_LOGS).forEach { file ->
            if (file.delete()) {
                Log.d(TAG, "Deleted old crash log: ${file.name}")
            }
        }
    }

    // ==================== 公共 API ====================

    /**
     * 检查上次是否异常退出
     */
    fun hasCrashed(): Boolean {
        return prefs.getBoolean(KEY_CRASH_FLAG, false)
    }

    /**
     * 获取崩溃时间
     */
    fun getCrashTime(): Long {
        return prefs.getLong(KEY_CRASH_TIME, 0)
    }

    /**
     * 获取崩溃消息
     */
    fun getCrashMessage(): String? {
        return prefs.getString(KEY_CRASH_MESSAGE, null)
    }

    /**
     * 获取崩溃堆栈跟踪
     */
    fun getCrashStackTrace(): String? {
        return prefs.getString(KEY_CRASH_STACK_TRACE, null)
    }

    /**
     * 获取崩溃日志文件列表
     */
    fun getCrashLogFiles(): List<File> {
        val crashLogDir = getCrashLogDir()
        return crashLogDir.listFiles { file ->
            file.name.startsWith(CRASH_LOG_PREFIX) && file.name.endsWith(CRASH_LOG_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 获取最新的崩溃日志内容
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun getLatestCrashLogContent(): String? {
        return try {
            getCrashLogFiles().firstOrNull()?.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read latest crash log", e)
            null
        }
    }

    /**
     * 清除崩溃状态（在应用正常启动后调用）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun clearCrashState() {
        try {
            prefs.edit {
                remove(KEY_CRASH_FLAG)
                remove(KEY_CRASH_TIME)
                remove(KEY_CRASH_MESSAGE)
                remove(KEY_CRASH_STACK_TRACE)
            }
            Log.d(TAG, "Crash state cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash state", e)
        }
    }

    /**
     * 清除所有崩溃日志
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun clearAllCrashLogs() {
        try {
            val crashLogDir = getCrashLogDir()
            crashLogDir.listFiles()?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Deleted crash log: ${file.name}")
                }
            }
            Log.d(TAG, "All crash logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash logs", e)
        }
    }

    /**
     * 获取崩溃摘要信息
     */
    fun getCrashSummary(): CrashSummary? {
        if (!hasCrashed()) return null

        return CrashSummary(
            time = getCrashTime(),
            message = getCrashMessage(),
            stackTracePreview = getCrashStackTrace()?.take(500)
        )
    }

    /**
     * 崩溃摘要数据类
     */
    data class CrashSummary(
        val time: Long,
        val message: String?,
        val stackTracePreview: String?
    )
}
