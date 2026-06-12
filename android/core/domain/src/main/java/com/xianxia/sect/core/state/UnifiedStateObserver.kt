package com.xianxia.sect.core.state

interface UnifiedStateObserver {
    fun onStateChanged(newState: UnifiedGameState)
}
