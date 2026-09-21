package com.xianxia.sect.taptap

import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy

// ── 云存档反射 API 桥（SR-2 自 TapCloudSaveManager 整体搬移）─────────────────────
// 纯代码搬移（零逻辑变化）：原为 TapCloudSaveManager 内部私有嵌套声明，为让
// TapTapSaveBackend（SaveBackend 门面）在同一模块内复用 SDK 探测逻辑而不重写
//（方案 D4/IN3 + SR-0 §3.1），提取为同包 internal 顶层声明。
// 语义保持：SDK 无编译期类型依赖（全反射），探测顺序 XD → Tap 不变，
// 15s 回调超时兜底不变，错误码→异常 message 映射不变。

/** TapTap SDK 回调超时上限（ms）——SDK 回调永不触发时
 *  协程永久挂起 + cloudOpLock 永久占用，云功能直到重启才恢复 */
internal const val CLOUD_OP_TIMEOUT_MS = 15_000L

/**
 * 云存档操作超时异常（普通 Exception 而非
 * TimeoutCancellationException）——由 withTimeout 超时抛出，被上层
 * catch (e: Exception) 转为 NetworkError；真正的协程取消不受影响。
 */
class CloudSaveOperationTimeoutException(message: String) : Exception(message)

/** 云存档原始信息（TapTap API 返回） */
internal data class CloudSaveRawInfo(
    val lastModifiedTime: Long,
    val fileSize: Long,
    val description: String,
    val extra: String = "{}"
)

/** 云端存档条目摘要 */
internal data class ArchiveEntry(
    val uuid: String,
    val name: String,
    val modifiedTime: Long
)

/** 反射调用的云存档 API 接口 */
internal interface CloudSaveApi {
    val className: String
    /** 上传存档，返回云端分配的 UUID。提供 uuid 时直接更新，否则创建新存档。 */
    suspend fun createOrUpdateArchive(archiveName: String, summary: String, filePath: String, uuid: String? = null,
        extra: String = "{}"): String?
    suspend fun downloadArchive(archiveName: String): ByteArray?
    suspend fun queryArchiveInfo(archiveName: String): CloudSaveRawInfo?
    /** 列出所有云端存档 */
    suspend fun listAllArchives(): List<ArchiveEntry>
    /** 删除指定 UUID 的云端存档 */
    suspend fun deleteArchive(uuid: String)
}

/**
 * 运行时反射解析 TapTap Cloud Save SDK。
 *
 * `tap-cloudsave` v4.10.x 的 Android API 在文档中未公开，
 * 因此通过反射动态检测可用的 API 类和回调接口。
 * 一旦 SDK API 确认，可替换为直接调用。
 */
internal object CloudSaveApiReflector {
    private const val TAG_REFL = "CloudSaveReflector"
    private var resolvedApi: CloudSaveApi? = null

    fun resolve(): CloudSaveApi? {
        if (resolvedApi != null) return resolvedApi
        resolvedApi = tryDetectXDSdkApi()
            ?: tryDetectTapSdkApi()
        if (resolvedApi != null) {
            DomainLog.i(TAG_REFL, "Resolved TapTap Cloud Save API: ${resolvedApi?.className ?: "unknown"}")
        } else {
            DomainLog.w(TAG_REFL, "No TapTap Cloud Save API found at runtime")
        }
        return resolvedApi
    }

    private fun tryDetectXDSdkApi(): CloudSaveApi? {
        return try {
            val clazz = Class.forName("com.xd.sdk.taptap.XDTapCloudSave")
            // 注意: XDTapCloudSave 在 tap-cloudsave artifact 中不存在,
            // 需要额外依赖 com.xd.sdk:xdsdk-taptap 才可用
            clazz.getMethod("getArchiveList",
                Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback"))
            DomainLog.i(TAG_REFL, "Detected XDSDK TapCloudSave API")
            ReflectiveCloudSaveApi(clazz, "XDTapCloudSave")
        } catch (_: Exception) { null }
    }

    private fun tryDetectTapSdkApi(): CloudSaveApi? {
        return try {
            val clazz = Class.forName("com.taptap.sdk.cloudsave.TapTapCloudSave")
            DomainLog.i(TAG_REFL, "Detected native TapTapCloudSave API")
            ReflectiveCloudSaveApi(clazz, "TapTapCloudSave")
        } catch (_: Exception) { null }
    }
}

/**
 * 反射桥接的 CloudSave API 实现。
 *
 * 通过运行时反射调用静态方法，适配 TapTap Cloud Save SDK。
 * 同时支持 XDSDK (`com.xd.sdk.taptap.XDTapCloudSave`) 和
 * 原生 SDK (`com.taptap.sdk.cloudsave.TapTapCloudSave`)。
 * 两个 SDK 的静态方法签名完全一致。
 *
 * 回调桥接使用 [kotlinx.coroutines.suspendCancellableCoroutine]。
 * 由于目标回调接口的方法签名是运行时检测的，使用反射进行动态派发。
 */
// TooManyFunctions：CloudSaveApi 端口契约（上传/下载/列表/删除全生命周期）+ 反射适配层，
// 21 个 override = 云存档协议下界，驻留类体承载协议分发
@Suppress("TooManyFunctions")
internal class ReflectiveCloudSaveApi(
    private val apiClass: Class<*>,
    override val className: String
) : CloudSaveApi {

    @Suppress("UNCHECKED_CAST")
    override suspend fun createOrUpdateArchive(archiveName: String, summary: String, filePath: String,
        uuid: String?, extra: String): String? {
        val metadataClass = Class.forName("com.taptap.sdk.cloudsave.ArchiveMetadata")
        val builderClass = Class.forName("com.taptap.sdk.cloudsave.ArchiveMetadata\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builder.javaClass.getMethod("setName", String::class.java).invoke(builder, archiveName)
        builder.javaClass.getMethod("setSummary", String::class.java).invoke(builder, summary)
        builder.javaClass.getMethod("setExtra", String::class.java).invoke(builder, extra)
        builder.javaClass.getMethod("setPlaytime", Integer.TYPE)
            .invoke(builder, (System.currentTimeMillis() / 1000).toInt())
        val metadata = builder.javaClass.getMethod("build").invoke(builder)

        val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")

        val effectiveUuid = uuid ?: findArchiveUuidByName(archiveName)

        if (effectiveUuid != null) {
            val result = suspendCallbackNullable<Any>(callbackClass) { callback ->
                apiClass.getMethod("updateArchive",
                    String::class.java, metadataClass, String::class.java, String::class.java, callbackClass
                ).invoke(null, effectiveUuid, metadata, filePath, null, callback)
            }
            return result?.let { getUuid(it) }
        } else {
            val result = suspendCallbackNullable<Any>(callbackClass) { callback ->
                apiClass.getMethod("createArchive",
                    metadataClass, String::class.java, String::class.java, callbackClass
                ).invoke(null, metadata, filePath, null, callback)
            }
            return result?.let { getUuid(it) }
        }
    }

    override suspend fun downloadArchive(archiveName: String): ByteArray? {
        val uuid = findArchiveUuidByName(archiveName) ?: return null
        val fileId = findArchiveFileIdByName(archiveName) ?: return null
        val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")

        return suspendCallbackNullable<ByteArray>(callbackClass) { callback ->
            apiClass.getMethod("getArchiveData",
                String::class.java, String::class.java, callbackClass
            ).invoke(null, uuid, fileId, callback)
        }
    }

    override suspend fun queryArchiveInfo(archiveName: String): CloudSaveRawInfo? {
        val archives = listArchives()
        val target = archives?.firstOrNull { getName(it) == archiveName } ?: return null
        return CloudSaveRawInfo(
            lastModifiedTime = getModifiedTime(target) * 1000,
            fileSize = getSaveSize(target),
            description = getSummary(target) ?: "",
            extra = getExtra(target) ?: ""
        )
    }

    override suspend fun listAllArchives(): List<ArchiveEntry> {
        val archives = listArchives() ?: return emptyList()
        return archives.mapNotNull { a ->
            val uuid = getUuid(a) ?: ""
            val name = getName(a) ?: ""
            if (uuid.isBlank()) null else ArchiveEntry(uuid, name, getModifiedTime(a))
        }
    }

    override suspend fun deleteArchive(uuid: String) {
        val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")
        suspendCallbackUnit(callbackClass) { callback ->
            apiClass.getMethod("deleteArchive",
                String::class.java, callbackClass
            ).invoke(null, uuid, callback)
        }
    }

    private suspend fun findArchiveUuidByName(archiveName: String): String? {
        return listArchives()?.firstOrNull { getName(it) == archiveName }?.let { getUuid(it) }
    }

    private suspend fun findArchiveFileIdByName(archiveName: String): String? {
        return listArchives()?.firstOrNull { getName(it) == archiveName }?.let { getFileId(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun listArchives(): List<Any>? {
        val callbackClass = Class.forName("com.taptap.sdk.cloudsave.internal.TapCloudSaveRequestCallback")
        @Suppress("UNCHECKED_CAST")
        val result: Any? = suspendCallbackNullable<Any?>(callbackClass) { callback ->
            apiClass.getMethod("getArchiveList", callbackClass).invoke(null, callback)
        }
        return result as? List<Any>
    }

    /** 返回 Unit 的回调桥接 */
    private suspend fun suspendCallbackUnit(
        callbackClass: Class<*>,
        invoke: (callback: Any) -> Unit
    ) {
        suspendCallbackNullable<Unit>(callbackClass, invoke)
    }

    /** 返回可为 null 的回调桥接 */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> suspendCallbackNullable(
        callbackClass: Class<*>,
        invoke: (callback: Any) -> Unit
    ): T? = try {
        // SDK 回调无超时——回调永不触发时协程永久挂起 +
        // cloudOpLock 永久占用，云功能直到重启 App 都失效；15s 超时转普通
        // 异常由上层 catch 转 NetworkError（e.cause == null 才是超时——
        // 协程取消导致的 TimeoutCancellationException 须重抛保持结构化并发）
        withTimeout(CLOUD_OP_TIMEOUT_MS) {
            suspendCallbackCoroutine(callbackClass, invoke)
        }
    } catch (e: TimeoutCancellationException) {
        if (e.cause == null) {
            throw CloudSaveOperationTimeoutException("云存档操作超时")
        }
        throw e
    }

    private suspend fun <T> suspendCallbackCoroutine(
        callbackClass: Class<*>,
        invoke: (callback: Any) -> Unit
    ): T? = suspendCancellableCoroutine { continuation ->
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _: Any, method: java.lang.reflect.Method, args: Array<*>? ->
            handleCallbackMethod(method.name, args, object : CallbackHandler {
                override fun onSuccess(value: Any?) {
                    if (continuation.isActive) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resumeWith(Result.success(value as? T))
                    }
                }
                override fun onError(e: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
            null
        }
        invoke(callback)
    }

    /** 回调结果处理器 */
    private interface CallbackHandler {
        fun onSuccess(value: Any?)
        fun onError(e: Throwable)
    }

    /**
     * 处理回调方法调用。
     *
     * 根据方法名分派到 [CallbackHandler]。
     * TapTap 回调模式：
     * - onArchiveCreated(ArchiveData) / onArchiveUpdated(ArchiveData) / onArchiveDeleted()
     *   → 成功，resume(Unit)
     * - onArchiveListResult(List<ArchiveData>) → 成功，返回列表
     * - onArchiveDataResult(byte[]) → 成功，返回数据（实际签名仅 1 参数）
     * - onRequestError(code, message) → 失败，根据 code 决定恢复或异常
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleCallbackMethod(
        methodName: String,
        args: Array<*>?,
        handler: CallbackHandler
    ) {
        when (methodName) {
            "onArchiveCreated", "onArchiveUpdated", "onArchiveDeleted" -> {
                // 返回 ArchiveData 对象（包含 uuid），供调用方缓存
                handler.onSuccess(args?.getOrNull(0))
            }
            "onArchiveListResult" -> {
                handler.onSuccess(args?.getOrNull(0))
            }
            "onArchiveDataResult" -> {
                // 实际签名: onArchiveDataResult(byte[] data)，仅 1 参数
                handler.onSuccess(args?.getOrNull(0))
            }
            "onArchiveCoverResult" -> {
                handler.onSuccess(args?.getOrNull(0))
            }
            "onRequestError" -> {
                val code = args?.getOrNull(0) as? Int ?: 0
                val msg = args?.getOrNull(1) as? String ?: ""
                when (code) {
                    400002 -> handler.onSuccess(null) // 存档不存在
                    400100 -> handler.onSuccess(null) // SDK 未就绪
                    400003 -> handler.onError(RuntimeException("云端存储空间不足或存档数量超限，请在 TapTap 中管理旧存档 [$code]: $msg"))
                    400007 -> handler.onError(RuntimeException("并发操作不允许，请稍后重试 [$code]: $msg"))
                    else -> handler.onError(RuntimeException("TapTap cloud save error [$code]: $msg"))
                }
            }
            else -> {
                DomainLog.w(TAG_BRIDGE, "Unhandled callback method: $methodName")
            }
        }
    }

    private fun getName(archive: Any): String? = invokeGetterString(archive, "getName")
    private fun getSummary(archive: Any): String? = invokeGetterString(archive, "getSummary")
    private fun getExtra(archive: Any): String? = invokeGetterString(archive, "getExtra")
    private fun getSaveSize(archive: Any): Long = invokeGetterLong(archive, "getSaveSize")
    private fun getModifiedTime(archive: Any): Long = invokeGetterLong(archive, "getModifiedTime")
    private fun getUuid(archive: Any): String? = invokeGetterString(archive, "getUuid")
    private fun getFileId(archive: Any): String? = invokeGetterString(archive, "getFileId")

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun invokeGetterString(archive: Any, methodName: String): String? {
        return try {
            archive.javaClass.getMethod(methodName).invoke(archive)?.toString()
        } catch (e: Exception) {
            DomainLog.w(
                TAG_BRIDGE,
                "invokeGetterString failed: method=$methodName " +
                    "class=${archive.javaClass.simpleName}",
                e
            )
            null
        }
    }

    private fun invokeGetterLong(archive: Any, methodName: String): Long {
        return try {
            archive.javaClass.getMethod(methodName).invoke(archive) as? Long ?: 0L
        } catch (_: Exception) { 0L }
    }

    // 搬移注记：原引用 TapCloudSaveManager companion 的 TAG（"TapCloudSaveManager"），
    // 独立成文件后改用本文件 TAG_BRIDGE——仅日志 tag 文案差异，无行为语义
    private companion object {
        private const val TAG_BRIDGE = "TapCloudSaveManager"
    }
}
