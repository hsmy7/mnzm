package com.xianxia.sect.core.state

/**
 * 引擎瞬态事件通道的事件类型。
 *
 * 当前无事件变体；通道管线（GameStateStore 的 pendingNotification / notifications /
 * enqueueNotification / consumeNotification）为扩展预留。
 */
sealed interface GameNotification
