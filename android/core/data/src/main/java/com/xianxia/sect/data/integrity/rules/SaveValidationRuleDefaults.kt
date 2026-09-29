package com.xianxia.sect.data.integrity.rules

/**
 * 注册所有内置 [SaveValidationRule] 的默认集合。
 */
fun SaveValidationRuleRegistry.registerDefaults() {
    registerAll(
        listOf(
            NumericSanitizeRule,         // order=0（最先执行，NaN/负值消毒防穿透 cap 规则）
            DiscipleIdBoundsRule,      // order=1（C3-b：大 id 弟子扩容 OOM 前置拦截）
            SectNameRule,              // order=1
            GameDateRule,              // order=2
            GamePhaseRangeRule,        // order=4
            CultivationCapRule,        // order=5
            EquipmentRefRule,          // order=6
            BuildingRefRule,           // order=8
            DuplicateDiscipleIdRule,   // order=9
            GhostDiscipleCleanupRule,  // order=10
            GhostRefCleanupRule,       // order=11
            SpiritStoneNonNegativeRule,// order=12
            DiscipleRealmConsistencyRule, // order=13
            DiscipleDeadStatusRule,    // order=14
            EquipmentDedupeRule,       // order=15
            SlotRefRule,               // order=16
            BloodPoolBuildingCleanupRule, // order=17（血炼池建筑下线：残留建筑与关联槽位清空）
            ItemRefConsistencyRule,    // order=18
            EntityCountBoundsRule,     // order=19
            RecruitListCleanupRule,    // order=20（招募链下线：恒空清表）
            BattleLogRefRule,          // order=21（battleLogs 条目结构校验）
            // order=22（邮件弟子附件下线：存量附件摘除，恒 Repaired 触发落盘收敛）
            // 排在结构/引用类校验（EntityCountBounds=19、BattleLogRef=21）之后——
            // 清洗只改写已判定结构合格的邮件快照，避免对被截断/判损坏的档做无谓改写；
            // 必须早于任何读取 attachments 的消费方：校验链内当前无规则读该字段，
            // 链外首个读取点是邮件领取流程，其数据源正是本规则产出的修复后快照
            MailDiscipleAttachmentCleanupRule,
            JadeSymbolNonNegativeRule, // order=23（玉符字段负值/超限钳制）
            // order=24（执法堂/监牢下线：残留建筑与关联槽位清理 + 思过/执法弟子状态归一化）
            LawEnforcementPrisonCleanupRule,
            TimeAxisRule,              // order=25（双轨时间权威轴：旧档回填 + 投影一致性，结算改造 B3）
            // order=26（孕养类加成丹药 R11 退役：存量折算补偿 + 配方回滚 + 幂等标记同事务；
            // 排链尾——只改写已判定结构合格的资产面，与邮件附件清理规则同理由）
            NurturePillRetirementRule,
        )
    )
}
