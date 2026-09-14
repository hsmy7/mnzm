package com.xianxia.sect.core.registry

/**
 * 全部 @GameService 系统的静态注册清单。
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

        // ── core/engine/service（月变/年变/领域服务主体 + AI 宗门/玉符/秘境）──
        register("engine.service", "AutoBuyService")
        register("engine.service", "AutoPillService")
        register("engine.service", "CaveExplorationProcessor")
        register("engine.service", "CultivationCore")
        register("engine.service", "CultivationEventProcessor")
        register("engine.service", "CultivationRateCalculator")
        register("engine.service", "CultivationService")
        register("engine.service", "CultivationSettlement")
        register("engine.service", "DiplomacyEventProcessor")
        register("engine.service", "DiscipleBreakthroughHandler")
        register("engine.service", "DiscipleLifecycleProcessor")
        register("engine.service", "DisciplePurchaseService")
        register("engine.service", "EquipmentNurtureService")
        register("engine.service", "FormulaService")
        register("engine.service", "HpMpRecoveryService")
        register("engine.service", "LawEnforcementProcessor")
        register("engine.service", "MailService")
        register("engine.service", "ManualProficiencyService")
        register("engine.service", "MerchantAndRecruitService")
        register("engine.service", "OverflowMailSender")
        register("engine.service", "ProductionProcessor")
        register("engine.service", "RecruitService")
        register("engine.service", "RedeemCodeService")
        register("engine.service", "RelativeGiftHandler")
        // AI 宗门/玉符/秘境等引擎侧服务（原 core/engine/service 组） ──
        register("engine.service", "AISectBattleProcessor")
        register("engine.service", "JadeSymbolService")
        register("engine.service", "MonthSettlementExecutor")
        register("engine.service", "MonthSettlementResidualExecutor")
        register("engine.service", "PhaseSettlementExecutor")
        register("engine.service", "YearSettlementExecutor")
        register("engine.service", "YearSettlementResidualExecutor")
        register("engine.service", "SecretRealmService")

        // ── core/engine/domain（战斗/探索 AI 处理） ──
        register("engine.domain", "HeavenlyTrialService")
        register("engine.domain", "SecretRealmAIProcessor")

        // ── core/engine/domain（弟子/建筑/外交领域服务）──
        register("engine.domain", "BuildingService")
        register("engine.domain", "DiscipleFactory")
        register("engine.domain", "DisciplePillManager")
        register("engine.domain", "DiscipleService")
        register("engine.domain", "PillEffectApplier")
        register("engine.domain", "VassalService")

        // ── core/engine 根（崩溃上报等基础设施） ──
        register("engine", "EngineCrashReporter")
    }

    private fun register(category: String, className: String) {
        GameSystemRegistry.register(className, category, className)
    }
}
