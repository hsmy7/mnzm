package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import com.xianxia.sect.BuildConfig
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

        @Volatile
        private var instance: CrashHandler? = null

        /**
         * 获取单例实例
         */
        fun getInstance(): CrashHandler {
            return instance ?: throw IllegalStateException("CrashHandler not initialized")
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

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handlingCrash.compareAndSet(false, true)) {
            Process.killProcess(Process.myPid())
            return
        }

        // ★ 使用 stackTraceToString() 避免 printStackTrace(Writer) 在
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
            // 1. 通知崩溃自愈引擎（用于安全模式判定）
            CrashRecoveryEngine.recordCrash(stackTrace)
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
            ?: Process.killProcess(Process.myPid())
    }

    /**
     * 尝试上传崩溃日志到远程服务器
     */
    private fun tryUploadCrashLog(crashLogFile: File?) {
        try {
            if (crashLogFile == null || !crashLogFile.exists()) return
            val content = crashLogFile.readText().take(8000)

            Thread {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .build()
                    val body = okhttp3.FormBody.Builder()
                        .add("version", BuildConfig.VERSION_NAME)
                        .add("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .add("sdk", Build.VERSION.SDK_INT.toString())
                        .add("stack", content)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}crash-report")
                        .post(body)
                        .build()
                    client.newCall(request).execute().close()
                } catch (_: Exception) { /* 静默失败 */ }
            }.start()
        } catch (_: Exception) { /* 静默失败 */ }
    }


    /**
     * 将崩溃日志写入文件
     *
     * @param stackTrace 预计算的堆栈跟踪字符串（避免在崩溃处理中调用 printStackTrace）
     */
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

                    printWriter.println("=== App Info ===")
                    try {
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        printWriter.println("Version Name: ${packageInfo.versionName}")
                        @Suppress("NewApi")
                        printWriter.println("Version Code: ${packageInfo.longVersionCode}")
                    } catch (e: Exception) {
                        printWriter.println("Version: Unknown")
                    }
                    printWriter.println()

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
    private fun cleanupOldCrashLogs(crashLogDir: File) {
        try {
            val logFiles = crashLogDir.listFiles { file ->
                file.name.startsWith(CRASH_LOG_PREFIX) && file.name.endsWith(CRASH_LOG_EXTENSION)
            }?.sortedByDescending { it.lastModified() }

            if (logFiles != null && logFiles.size > MAX_CRASH_LOGS) {
                logFiles.drop(MAX_CRASH_LOGS).forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old crash log: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old crash logs", e)
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
