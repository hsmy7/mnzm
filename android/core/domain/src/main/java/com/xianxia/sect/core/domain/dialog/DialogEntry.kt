package com.xianxia.sect.core.domain.dialog

/**
 * 对话框实例 — 类型 + 参数。
 */
data class DialogEntry(
    val type: DialogType,
    val params: Map<String, Any?> = emptyMap()
)
