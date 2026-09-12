package com.xianxia.sect.core.engine.service

// ── 招募/商人/洞府委托域（自 CultivationService 拆出，行为零变更） ──

internal fun CultivationService.refreshTravelingMerchantManual(): Boolean {
        return merchantAndRecruitService.refreshTravelingMerchantManual()
}
