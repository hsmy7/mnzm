package com.xianxia.sect.core.registry

/**
 * 全部 @GameService 系统的静态注册清单（2026-08-13 批次 4）。
 *
 * 与源码 @GameService 标注一一对应——新增 @GameService 类后必须在本文件
 * 追加一行，否则 [GameSystemRegistryCoverageTest] 守卫失败并提示。
 * 类别规则 = 包路径归属：service / engine.service / engine.domain / domain / engine。
 * 调用入口：[GameSystemRegistryDefaults.registerAll]（引擎启动装配阶段调用一次）。
 */
object GameSystemRegistryDefaults {

    /** 注册全部内置系统（幂等：重复调用由 GameSystemRegistry.register 抛错提示） */
    fun registerAll() {
        val registry = GameSystemRegistry
        if (registry.size() > 0) return

        // ── core/service（月变/年变/领域服务主体） ──
        register("service", "AutoBuyService")
        register("service", "AutoPillService")
        register("service", "CaveExplorationProcessor")
        register("service", "CultivationCore")
        register("service", "CultivationEventProcessor")
        register("service", "CultivationRateCalculator")
        register("service", "CultivationService")
        register("service", "CultivationSettlement")
        register("service", "DiplomacyEventProcessor")
        register("service", "DiscipleBreakthroughHandler")
        register("service", "DiscipleLifecycleProcessor")
        register("service", "DisciplePurchaseService")
        register("service", "EquipmentNurtureService")
        register("service", "FormulaService")
        register("service", "HpMpRecoveryService")
        register("service", "LawEnforcementProcessor")
        register("service", "MailService")
        register("service", "ManualProficiencyService")
        register("service", "MerchantAndRecruitService")
        register("service", "OverflowMailSender")
        register("service", "ProductionProcessor")
        register("service", "RecruitService")
        register("service", "RedeemCodeService")
        register("service", "RelativeGiftHandler")

        // ── core/engine/service（AI 宗门/玉符/秘境等引擎侧服务） ──
        register("engine.service", "AISectBattleProcessor")
        register("engine.service", "AISectOccupationResolver")
        register("engine.service", "JadeSymbolService")
        register("engine.service", "PlayerDefenseProcessor")
        register("engine.service", "SecretRealmService")

        // ── core/engine/domain（战斗/探索 AI 处理） ──
        register("engine.domain", "AttackWarningService")
        register("engine.domain", "HeavenlyTrialService")
        register("engine.domain", "SecretRealmAIProcessor")

        // ── core/domain（弟子/建筑/外交领域服务） ──
        register("domain", "BuildingService")
        register("domain", "DiscipleFactory")
        register("domain", "DisciplePillManager")
        register("domain", "DiscipleService")
        register("domain", "PillEffectApplier")
        register("domain", "VassalService")

        // ── core/engine 根（崩溃上报等基础设施） ──
        register("engine", "EngineCrashReporter")
    }

    private fun register(category: String, className: String) {
        GameSystemRegistry.register(className, category, className)
    }
}
