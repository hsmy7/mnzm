package com.xianxia.sect.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var emergencySaveCallback: (() -> Boolean)? = null

    /**
     * 设置紧急保存回调
     * 在崩溃发生时调用，用于尝试保存游戏数据
     */
    fun setEmergencySaveCallback(callback: () -> Boolean) {
        emergencySaveCallback = callback
    }

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

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)

        try {
            // 1. 尝试紧急保存
            performEmergencySave()

            // 2. 记录崩溃日志到文件
            val crashLogFile = writeCrashLogToFile(thread, throwable)

            // 3. 保存崩溃状态到 SharedPreferences
            saveCrashState(throwable, crashLogFile)

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
     * 执行紧急保存
     * 添加超时保护，防止崩溃处理阻塞过久
     */
    private fun performEmergencySave() {
        try {
            val callback = emergencySaveCallback
            if (callback != null) {
                val startTime = System.currentTimeMillis()
                val timeoutMs = 5000L
                
                val saveThread = Thread {
                    try {
                        val success = callback.invoke()
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.i(TAG, "Emergency save ${if (success) "succeeded" else "failed"} in ${elapsed}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in emergency save thread", e)
                    }
                }
                saveThread.start()
                
                saveThread.join(timeoutMs)
                
                if (saveThread.isAlive) {
                    Log.w(TAG, "Emergency save timed out after ${timeoutMs}ms, interrupting...")
                    saveThread.interrupt()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during emergency save", e)
        }
    }

    /**
     * 将崩溃日志写入文件
     */
    private fun writeCrashLogToFile(thread: Thread, throwable: Throwable): File? {
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
                    throwable.printStackTrace(printWriter)

                    // 打印 cause 链
                    var cause = throwable.cause
                    while (cause != null) {
                        printWriter.println()
                        printWriter.println("=== Caused by: ${cause.javaClass.name} ===")
                        printWriter.println("Message: ${cause.message}")
                        cause.printStackTrace(printWriter)
                        cause = cause.cause
                    }
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

    /**
     * 保存崩溃状态到 SharedPreferences
     */
    private fun saveCrashState(throwable: Throwable, crashLogFile: File?) {
        try {
            val stackTrace = getStackTraceString(throwable)
            prefs.edit()
                .putBoolean(KEY_CRASH_FLAG, true)
                .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                .putString(KEY_CRASH_MESSAGE, throwable.message)
                .putString(KEY_CRASH_STACK_TRACE, stackTrace.take(4000)) // 限制长度
                .apply()
            Log.d(TAG, "Crash state saved to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash state", e)
        }
    }

    /**
     * 获取堆栈跟踪字符串
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
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
            prefs.edit()
                .remove(KEY_CRASH_FLAG)
                .remove(KEY_CRASH_TIME)
                .remove(KEY_CRASH_MESSAGE)
                .remove(KEY_CRASH_STACK_TRACE)
                .apply()
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
