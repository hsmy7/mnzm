package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一键招募 native 信封协议（[GameCoreBridge.nativeRecruitAllFromList]）。
 *
 * C++ 侧返回：`{"ok":bool, "count":int, "reason":"SUCCESS|MONTHLY_LIMIT|UNKNOWN"}`。
 * 独立文件承载（internal 可见性）供单元测试直接验证解析，不依赖 native 库加载。
 */
@Serializable
internal data class RecruitAllEnvelope(
    val ok: Boolean = false,
    val count: Int = 0,
    val reason: String = "UNKNOWN"
)

/** 信封解析（宽松读；字节为空/JSON 非法返回 null——调用方回退 Kotlin 原实现） */
internal fun parseRecruitAllEnvelope(raw: ByteArray): RecruitAllEnvelope? {
    if (raw.isEmpty()) return null
    return runCatching {
        RECRUIT_ALL_JSON.decodeFromString(RecruitAllEnvelope.serializer(), raw.decodeToString())
    }.getOrNull()
}

/** native 信封 reason：意外异常兜底（调用方回退 Kotlin 原实现） */
internal const val RECRUIT_ENVELOPE_REASON_UNKNOWN = "UNKNOWN"

/** 信封解析 JSON（宽松读，防 C++ 侧新增字段破坏） */
private val RECRUIT_ALL_JSON = Json { ignoreUnknownKeys = true }
