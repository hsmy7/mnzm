package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 手动招募 native 信封协议（[com.xianxia.sect.core.nativebridge.GameCoreBridge.nativeManualRecruitFromList]）。
 *
 * C++ 侧返回：`{"ok":bool, "newId":string, "age":int, "name":string,
 *   "reason":"SUCCESS|MONTHLY_LIMIT|NOT_FOUND|CORRUPTED|UNKNOWN"}`。
 * 独立文件承载（internal 可见性）供单元测试直接验证解析/文案映射，
 * 不依赖 native 库加载（JVM 测试环境 GameCoreBridge.isLoaded=false 走 Kotlin 回退）。
 */
internal const val MANUAL_RECRUIT_MAX_NAME_DISPLAY_LEN = 30

/** 手动招募 native 信封（宽松读，防 C++ 侧新增字段破坏） */
@Serializable
internal data class ManualRecruitEnvelope(
    val ok: Boolean = false,
    val newId: String = "",
    val age: Int = 0,
    val name: String = "",
    val reason: String = "UNKNOWN"
)

/** 信封解析（宽松读；字节为空/JSON 非法返回 null——调用方回退 Kotlin 原实现） */
internal fun parseManualRecruitEnvelope(raw: ByteArray): ManualRecruitEnvelope? {
    if (raw.isEmpty()) return null
    return runCatching {
        MANUAL_RECRUIT_JSON.decodeFromString(
            ManualRecruitEnvelope.serializer(), raw.decodeToString()
        )
    }.getOrNull()
}

/** 业务失败提示文案（与 Kotlin 原路径逐字一致；reason 未知回退通用数据异常） */
internal fun ManualRecruitEnvelope.failureMessage(): String = when (reason) {
    "MONTHLY_LIMIT" -> "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）"
    "NOT_FOUND" -> "招募失败：该弟子已不在招募列表中"
    else -> "招募失败：「${name.take(MANUAL_RECRUIT_MAX_NAME_DISPLAY_LEN)}」数据异常"
}

/** 信封解析 JSON（宽松读，防 C++ 侧新增字段破坏） */
private val MANUAL_RECRUIT_JSON = Json { ignoreUnknownKeys = true }
