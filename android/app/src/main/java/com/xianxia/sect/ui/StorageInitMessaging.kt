package com.xianxia.sect.ui

import com.xianxia.sect.data.cloud.SaveBackendMode

/**
 * StorageFacade 初始化失败的用户文案（SR-3，审计 §12-J 修复；模式感知，纯函数可测）。
 *
 * 背景：初始化 3 次全败后旧实现仅记日志即放行进主菜单（"proceeding with empty
 * cache"）——本地存档不可用的会话里，保存会静默失败、读档全空，玩家进度被静默丢弃
 * （审计 §12-J）。本批改为如实阻断 + 重试 UI，文案按云存档模式感知：
 * - [SaveBackendMode.LEGACY]：本地存档是唯一进度真相——点明"无法进入游戏"的原因；
 * - [SaveBackendMode.CLOUD_TRANSITION]：双写期本地缓存仍必成——点明"缓存不可写 =
 *   保存/云上传/云下载都无法完成"；
 * - [SaveBackendMode.CLOUD_ONLY]：云为真相——点明"本地缓存不可用且云端不可达"。
 */
internal fun storageInitFailureMessage(mode: SaveBackendMode, lastError: String?): String {
    val detail = lastError?.takeIf { it.isNotBlank() } ?: "未知错误"
    val modeHint = when (mode) {
        SaveBackendMode.LEGACY ->
            "本地存档是唯一的进度来源，初始化失败期间无法进入游戏（避免进度静默丢失）。"
        SaveBackendMode.CLOUD_TRANSITION ->
            "本地存档缓存不可写：本机进度保存、云存档上传与下载都无法完成，暂时无法进入游戏。"
        SaveBackendMode.CLOUD_ONLY ->
            "本地缓存不可用且云端存档服务不可达，暂时无法进入游戏。"
    }
    return "存档初始化失败（已重试 3 次）：$detail\n\n$modeHint\n请点击「重试」；若多次失败，请重启应用或检查设备存储空间。"
}
