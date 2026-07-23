package com.xianxia.sect.ui.game.delegate

class AdsDelegate {

    companion object {
        private const val AD_COOLDOWN_MS = 60_000L
    }

    var onWatchAdBreakthroughBonus: ((String) -> Unit)? = null
    var onWatchAdMerchantRefresh: (() -> Unit)? = null

    @Volatile private var adCooldownUntilMs: Long = 0L

    fun isAdOnCooldown(): Boolean = System.currentTimeMillis() < adCooldownUntilMs

    fun markAdWatched() {
        adCooldownUntilMs = System.currentTimeMillis() + AD_COOLDOWN_MS
    }
}
