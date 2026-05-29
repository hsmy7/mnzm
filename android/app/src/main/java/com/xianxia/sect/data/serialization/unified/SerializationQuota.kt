package com.xianxia.sect.data.serialization.unified

data class SerializationQuota(
    val coreMaxBytes: Int = 64 * 1024,
    val discipleMaxBytes: Int = 10 * 1024 * 1024,
    val inventoryMaxBytes: Int = 5 * 1024 * 1024,
    val worldMaxBytes: Int = 5 * 1024 * 1024,
    val combatMaxBytes: Int = 20 * 1024 * 1024,
    val missionMaxBytes: Int = 2 * 1024 * 1024,
    val totalMaxBytes: Int = 200 * 1024 * 1024,
    val maxDiscipleCount: Int = 5000,
    val maxBattleLogCount: Int = 10000
) {
    companion object {
        val STRICT = SerializationQuota(
            coreMaxBytes = 64 * 1024,
            discipleMaxBytes = 10 * 1024 * 1024,
            inventoryMaxBytes = 5 * 1024 * 1024,
            worldMaxBytes = 5 * 1024 * 1024,
            combatMaxBytes = 20 * 1024 * 1024,
            missionMaxBytes = 2 * 1024 * 1024,
            totalMaxBytes = 200 * 1024 * 1024,
            maxDiscipleCount = 5000,
            maxBattleLogCount = 10000
        )
        val LENIENT = SerializationQuota(
            coreMaxBytes = 128 * 1024,
            discipleMaxBytes = 50 * 1024 * 1024,
            inventoryMaxBytes = 20 * 1024 * 1024,
            worldMaxBytes = 20 * 1024 * 1024,
            combatMaxBytes = 100 * 1024 * 1024,
            missionMaxBytes = 10 * 1024 * 1024,
            totalMaxBytes = 500 * 1024 * 1024,
            maxDiscipleCount = 20000,
            maxBattleLogCount = 50000
        )
    }
}

data class QuotaValidationResult(
    val isValid: Boolean,
    val violations: List<QuotaViolation> = emptyList(),
    val estimatedTotalBytes: Long = 0L
) {
    val hasViolations: Boolean get() = violations.isNotEmpty()
}

data class QuotaViolation(
    val domain: String,
    val actualValue: Long,
    val limit: Long,
    val unit: String = "bytes"
) {
    override fun toString(): String =
        "QuotaViolation(domain='$domain', actual=$actualValue$unit, limit=$limit$unit)"
}
