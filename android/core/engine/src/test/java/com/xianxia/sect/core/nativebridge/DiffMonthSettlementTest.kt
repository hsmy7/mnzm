package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.model.AutoBuyEntry
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffMonthSettlementTest — 月变结算跨语言差分对拍（T2.2 验收核心）。
 *
 * 守护目标：C++ `gamecore::system::runMonthSettlement`（注册于 onMonthChange，
 * 经 nativeCoreAdvancePhases 跨月界触发）与 Kotlin `GameEngineCore` 同构组合管线
 * （TimeSystem 推进至边界 → PhaseSettlementExecutor → MonthSettlementExecutor）
 * 的月变八步语义**逐位一致**。
 *
 * 场景覆盖（对照 t2-2-semantics.md §3 九条规避约束）：
 * ① 灵矿 lastSettledMonth 无条件推进（矿空 rate=0 仍推进——差分保护语义）
 * ② 伴侣配对 SYSTEM 流：两男两女适格（age≥18 / 无道侣 / 无血亲 /
 *    bannedRootCounts 空），males 外层 × females 内层每组合恰 1 次 nextDouble，
 *    以 RNG 分区终态锁定抽取次数与顺序（0.006 概率下预期全不命中 → partnerId 全空）
 * ③ 政策忠诚：仁政爱徒 loyalty delta=+1（50→51 coerceIn(0,100)）+
 *    S1 政策月费 100×4 弟子经真实钱包扣除
 *
 * ⑥（批 10-3）偷盗兜底：弟子 16 道德 10（候选）但入伍月 13 保护期未满
 * （绝对月差 14-13=1 < 12，双端口径均 < 12）→ 候选排除，零抽取零标记——
 * 任何虚假 SYSTEM 抽取都会移位叛逃候选抽取序列而对拍失败；门控通过
 * （平均忠诚 42 < 50）与 hasCandidate 路径（道德 < 30）仍被真实覆盖。
 * ⑦（批 10-4）附庸脱离：玩家宗门 p1 + 附属 ai-3（至交 100，战力比 ≥5x
 * → 概率 0.0）恰抽 1 次 SYSTEM 必不脱离——契约保留零事件；玩家宗门在场
 * 使 gameOverCheck 走"本宗未被占领 → 不触发"路径。
 * ⑧（批 11-1）自动招募：recruitList 含 1 名匹配（灵根 1 根，无装备/功法——
 * 俘虏落库 no-op 规避 UUID 分叉）+ 1 名不匹配；autoRecruitSpiritRootFilter
 * {1} → 弟子 17 入宗（id=max+1、资质 50→82 散列补算、recruitedMonth=14、
 * annualNewDisciples+1），r2 保留在列表；零 RNG 抽取（SYSTEM 抽取序零扰动）。
 * 规避清单落实：政策仅开仁政爱徒（自动排班六开关全关）/ 除弟子 16 外
 * morality≥阈值（reactive 偷盗钩子零触发）/ consentRequired=false /
 * worldLevels 空（precomputeTargets 纯早退）/ spiritFieldPlants 空 /
 * activeBloodRefinements 空 / 无秘境·巡逻·任务 / 非 12 月 / timestamp 对拍排除。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffMonthSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 推进 3 旬：(1,1,上旬) → (1,2,上旬)，恰好跨一个月界。
     * 刻意避开 month % 3 == 0 的任务自动刷新月（missionRefresh 属任务批次未下沉）。
     */
    private companion object {
        const val PHASES = 3
        const val SEED = 20260901L

        /** 仁政爱徒月费（PolicyConfig：100 × 全体弟子数） */
        const val BENEVOLENT_MONTHLY_COST_PER_DISCIPLE = 100L

        /** 初始忠诚缺省（DiscipleTables.loyalties getOrDefault 50） */
        const val BASE_LOYALTY = 50

        /** 仁政爱徒月度忠诚增量（kBenevolentLoyaltyPerMonth） */
        const val BENEVOLENT_LOYALTY_DELTA = 1

        /** 弟子数（2 男 2 女 + 1 叛逃候选 + 1 偷盗保护期候选） */
        const val DISCIPLE_COUNT = 6

        /** 批 10-3 偷盗保护期候选 id（道德 10 但入伍月 13 → 候选排除） */
        const val PROTECTED_THIEF_ID = "16"

        /** 偷盗保护期候选入伍绝对月（年 1 月 1 = 13；月变时绝对月 14，差 1 < 12） */
        const val PROTECTED_THIEF_RECRUITED_MONTH = 13

        /** 叛逃候选 id（忠诚 0 → 概率 (30-1)×0.01=0.29，月结 step8 判定） */
        const val DESERTER_ID = "15"

        /** 自动招募匹配候选 id（recruitList 侧；入宗后分配新 id=max+1=17） */
        const val RECRUIT_MATCH_ID = "r1"

        /** 自动招募不匹配候选 id（2 灵根 ∉ filter{1}，保留在列表） */
        const val RECRUIT_KEEP_ID = "r2"

        /** 自动招募入宗新弟子 id（现有弟子 11..16 → max=16 → 17） */
        const val NEW_RECRUIT_ID = 17

        /** 新弟子资质散列补算期望（id=17，1 灵根 → 80 + floorMod(8990,21)=2） */
        const val NEW_RECRUIT_APTITUDE = 82

        /** 库存集合路径锚点（镜像生成 id 排除用；集合内容本身参与 diff——
         *  批 11-4 FakeGameStateStore 嵌套事务修复后） */
        private val INVENTORY_COLLECTION_KEYS = setOf(
            "equipmentStacks", "equipmentInstances", "manualStacks",
            "manualInstances", "pills", "materials", "herbs", "seeds", "storageBags"
        )
    }

    // ── 场景构建 ────────────────────────────────────────────────────

    /** 两男两女适格弟子（配对流断言核心）+ 仁政爱徒开启 + 灵石充足 */
    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 批 13-2b：预置刷新月 == 当前绝对月（13）→ 关卡刷新不触发
            //（既有场景专注政策/执法域；世界关卡刷新对拍由场景⑭独立覆盖）
            worldLevelLastRefreshMonth = 1 * 12 + 1
            // 场景③：仁政爱徒（S1 按弟子数计费 100×N + S2 忠诚 +1）
            sectPolicies = sectPolicies.copy(benevolentGovernance = true)
            // 场景②前提：自动配对模式（提案分支不在协议）
            daoCompanionConsentRequired = false
            // 场景④（批 10-1）：侦察过期清理——ai-1 过期(1,1)、ai-2 未过期(2,2)；
            // worldLevels 空（precomputeTargets 纯早退）
            scoutInfo = mapOf(
                "ai-1" to SectScoutInfo(
                    sectId = "ai-1", sectName = "青岚宗",
                    expiryYear = 1, expiryMonth = 1,
                    disciples = mapOf(5 to 3)
                ),
                "ai-2" to SectScoutInfo(
                    sectId = "ai-2", sectName = "赤水宗",
                    expiryYear = 2, expiryMonth = 2,
                    resources = mapOf("灵石" to 42)
                )
            )
            sectDetails = mapOf(
                "ai-1" to SectDetail(
                    sectId = "ai-1", portraitRes = "sect_ai1", lastGiftYear = 1,
                    scoutInfo = SectScoutInfo(
                        sectId = "ai-1", sectName = "青岚宗",
                        expiryYear = 1, expiryMonth = 1,
                        disciples = mapOf(5 to 3)
                    )
                )
            )
            // 场景⑦（批 10-4）：附庸脱离场景（玩家宗门 + 至交附属 + AI 弟子）
            applyVassalBreakawayScene()
            // 场景⑧⑨（批 11-1/11-2）：自动招募 + 秘境 AI 队伍派遣
            applyAutoRecruitAndRealmScene()
        }
        return NativeGameState(
            gameData = gameData,
            // 批 10-4：AI 弟子池经顶层字段承载（GameData 侧 @Transient 不入
            // gameData 序列化——快照协议顶层键，C++ GameState.aiSectDisciples）
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(
                pairingDisciple("11", "甲一", "male"),
                pairingDisciple("12", "甲二", "male"),
                pairingDisciple("13", "乙一", "female"),
                pairingDisciple("14", "乙二", "female"),
                // 场景⑤（批 10-2）：叛逃候选——忠诚 0（政策 +1 后 1 < 30）、
                // 未成年（16 岁不参与伴侣配对，避免额外 SYSTEM 抽取改变既有
                // 4 组合序列）、IDLE、recruitedMonth 0（保护期 25-0 ≥ 12）
                pairingDisciple(DESERTER_ID, "丙一", "male").copy(
                    age = 16,
                    skills = SkillStats(loyalty = 0)
                ),
                // 场景⑥（批 10-3）：偷盗候选（道德 10）但入伍月 13 → 保护期
                // （12 月）未满 → 候选排除零抽取；未成年（16 岁）不参与配对、
                // 忠诚 50 非叛逃候选（不扰动既有 SYSTEM 抽取序列）
                pairingDisciple(PROTECTED_THIEF_ID, "丁一", "male").copy(
                    age = 16,
                    skills = SkillStats(morality = 10),
                    usage = UsageTracking(recruitedMonth = PROTECTED_THIEF_RECRUITED_MONTH)
                )
            )
        )
    }

    /**
     * 场景⑧⑨（批 11-1/11-2）：自动招募——1 根灵根匹配 filter{1}（无装备/功法，
     * 俘虏落库 no-op 规避 UUID 分叉）+ 2 根灵根不匹配保留列表；秘境 AI 队伍
     * 派遣——秘境存在（spawnYear=1 未到期，规避子事件 15 关闭）+ ai-3 有存活
     * 弟子 → 子事件 16 派遣 1 队。
     */
    private fun GameData.applyAutoRecruitAndRealmScene() {
        autoRecruitSpiritRootFilter = setOf(1)
        recruitList = listOf(
            recruitCandidate(RECRUIT_MATCH_ID, "戊一", "metal"),
            recruitCandidate(RECRUIT_KEEP_ID, "己一", "metal,fire")
        )
        secretRealmState = com.xianxia.sect.core.model.SecretRealmState(
            id = "sr1", name = "远古秘境", x = 100f, y = 100f, spawnYear = 1
        )
    }

    /**
     * 场景⑦（批 10-4）：附庸脱离——玩家宗门在场（gameOverCheck 判"本宗未被
     * 占领" → 不触发）；附属 ai-3 至交好感 100 + 战力比 ≥5x（powerScore 0）
     * → 脱离概率 0.0，恰抽 1 次 SYSTEM 必不脱离；契约保留 + 零事件。AI 弟子
     * 与玩家弟子同规格（realm 9 无天赋）→ 战力比 = 存活弟子数（5 或 6，由
     * 叛逃结果决定）≥ 5 精确成立。
     */
    private fun GameData.applyVassalBreakawayScene() {
        vassalContracts = listOf(
            VassalContract(vassalSectId = "ai-3", establishedYear = 1)
        )
        sectRelations = listOf(
            SectRelation(sectId1 = "p1", sectId2 = "ai-3", favor = 100)
        )
        aiSectDisciples = mapOf(
            "ai-3" to listOf(
                Disciple(
                    id = "90", name = "玄一", realm = 9, realmLayer = 1,
                    cultivation = 10.0, spiritRootType = "metal",
                    age = 20, gender = "male",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1)
                )
            )
        )
        worldMapSects = listOf(
            WorldSect(id = "p1", name = "青云宗", isKnown = true, isPlayerSect = true),
            WorldSect(id = "ai-1", name = "青岚宗", isKnown = true),
            WorldSect(id = "ai-2", name = "赤水宗", isKnown = true),
            WorldSect(id = "ai-3", name = "玄水宗", isKnown = true)
        )
    }

    /** 配对适格弟子：成年 / 无道侣 / 无血亲 / 低修为（不触发突破）/ 满血哨兵 */
    private fun pairingDisciple(id: String, name: String, gender: String) =
        Disciple(
            id = id, name = name, realm = 9, realmLayer = 1,
            cultivation = 10.0, spiritRootType = "metal",
            age = 20, gender = gender,
            combat = CombatAttributes(currentHp = -1, currentMp = -1)
        )

    /**
     * 场景⑩（批 11-3）：12 月自动购买——(1,11,上旬) 起 3 旬跨 11→12 月界，
     * 月结时 gameMonth=12 → autoBuy 触发。商人商品均为已知模板（精铁剑/
     * 聚气丹，零 RNG 主路径）；availableMissions 由 Kotlin 侧非托管 RNG
     * 生成（任务批次边界 S-19）——C++ 协议无该字段，diff 面天然不比较。
     */
    private fun buildDecemberSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 11, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 批 13-2b：预置刷新月 == 月变时绝对月（12 月 = 24）→ 不刷新
            worldLevelLastRefreshMonth = 1 * 12 + 12
            autoBuyList = listOf(
                AutoBuyEntry(itemName = "精铁剑", itemType = "equipment", rarity = 1),
                AutoBuyEntry(itemName = "聚气丹", itemType = "pill", rarity = 1)
            )
            travelingMerchantItems = listOf(
                MerchantItem(
                    id = "m1", name = "精铁剑", type = "equipment",
                    rarity = 1, price = 100, quantity = 3
                ),
                MerchantItem(
                    id = "m2", name = "聚气丹", type = "pill",
                    rarity = 1, price = 50, quantity = 2, grade = "中品"
                )
            )
            // 非空 AI 弟子池（规避空表 null vs {} 协议不对称——批 10-4 空表
            // 不导出键保 null 往返；秘境不存在 → 不派遣，零影响）。@Transient
            // 双通道一致：GameData 侧（Kotlin 臂基准）+ 顶层（快照协议）
            aiSectDisciples = mapOf(
                "ai-1" to listOf(
                    Disciple(
                        id = "90", name = "玄一", realm = 9, realmLayer = 1,
                        cultivation = 10.0, spiritRootType = "metal",
                        age = 20, gender = "male",
                        combat = CombatAttributes(currentHp = -1, currentMp = -1)
                    )
                )
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(pairingDisciple("11", "甲一", "male"))
        )
    }

    /**
     * 场景⑪（批 12-1）：弟子智能购买——(1,1,上旬) 起 3 旬跨 1→2 月界，
     * 月结时子事件 12 触发。上架已知模板（精铁剑/聚气丹，模板路径确定性
     * 回退散列选池）、仓库有货、弟子有灵石 → 决策 + 扣仓库 + 入袋 +
     * 灵石（先袋后身）+ 宗门入账。SYSTEM 分区 shuffled（单元素洗牌零
     * 消费——决策集各 1 名候选，规避 RNG 序列跨语言对拍风险）。
     */
    private fun buildPurchaseSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 0L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 批 13-2b：预置刷新月（13）→ 不刷新
            worldLevelLastRefreshMonth = 1 * 12 + 1
            // 上架商品（玩家卖出后进 playerListedItems；itemId 指向仓库堆叠）
            playerListedItems = listOf(
                MerchantItem(
                    id = "list-e1", name = "精铁剑", type = "equipment",
                    itemId = "wh-e1", rarity = 1, price = 100, quantity = 1
                ),
                MerchantItem(
                    id = "list-p1", name = "聚气丹", type = "pill",
                    itemId = "wh-p1", rarity = 1, price = 50, quantity = 1,
                    grade = "中品"
                )
            )
            // 非空 AI 弟子池（规避空表协议不对称）
            aiSectDisciples = mapOf(
                "ai-1" to listOf(
                    Disciple(
                        id = "90", name = "玄一", realm = 9, realmLayer = 1,
                        cultivation = 10.0, spiritRootType = "metal",
                        age = 20, gender = "male",
                        combat = CombatAttributes(currentHp = -1, currentMp = -1)
                    )
                )
            )
        }
        // 仓库库存 + 弟子（批 12-1：购买候选有灵石）
        val equipmentStacks = purchaseEquipmentStacks()
        val pills = purchasePills()
        val disciples = purchaseDisciples()
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = disciples,
            equipmentStacks = equipmentStacks,
            pills = pills
        )
    }

    /** 购买场景仓库装备堆叠（精铁剑 ×1 未锁定） */
    private fun purchaseEquipmentStacks(): List<EquipmentStack> = listOf(
        EquipmentStack(
            id = "wh-e1", name = "精铁剑", rarity = 1, quantity = 1,
            slot = com.xianxia.sect.core.model.EquipmentSlot.WEAPON,
            minRealm = 9
        )
    )

    /** 购买场景仓库丹药（聚气丹 ×1 未锁定） */
    private fun purchasePills(): List<com.xianxia.sect.core.model.Pill> = listOf(
        com.xianxia.sect.core.model.Pill(
            id = "wh-p1", name = "聚气丹", rarity = 1, quantity = 1,
            grade = PillGrade.MEDIUM
        )
    )

    /** 购买场景弟子：2 名成年练气弟子，随身/储物袋灵石充足 */
    private fun purchaseDisciples(): List<Disciple> = listOf(
        pairingDisciple("11", "甲一", "male").copy(
            equipment = pairingDisciple("11", "甲一", "male").equipment.copy(
                spiritStones = 1000
            )
        ),
        pairingDisciple("12", "甲二", "male").copy(
            equipment = pairingDisciple("12", "甲二", "male").equipment.copy(
                storageBagSpiritStones = 600
            )
        )
    )

    /**
     * 场景⑬（批 13-2a）：教化之道偷盗判定钩子——moralEducation 开启 +
     * 1 名道德 28 忠诚 40 弟子（从众门控通过），(1,1) 起 3 旬跨 1→2 月界。
     * 月结步骤 2 钩子：道德 28→29（仍 < 30 阈值）→ 单弟子偷盗判定——
     * 偷盗概率 prob=(30-29)×0.01=0.01 种子不中 → 标记（theftJudgements
     * ThisMonth+1 + lastTheftJudgementYears）+ SYSTEM 恰抽 1 次；子事件 3
     * 月度兜底：theftJudgementsThisMonth 首行无条件归零 + 已判定（年标记）
     * 排除 → 零抽取。终态：道德 29 + 计数归零 + SYSTEM 共 1 抽。
     */
    private fun buildMoralEducationSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 批 13-2b：预置刷新月（13）→ 不刷新（场景⑬ 专注教化之道钩子）
            worldLevelLastRefreshMonth = 1 * 12 + 1
            sectPolicies = sectPolicies.copy(moralEducation = true)
            // 非空 AI 弟子池（规避空表 null vs {} 协议不对称——批 10-4 同款；
            // worldLevels 空 → precomputeTargets 纯早退，零影响）
            aiSectDisciples = mapOf(
                "ai-1" to listOf(
                    Disciple(
                        id = "90", name = "玄一", realm = 9, realmLayer = 1,
                        cultivation = 10.0, spiritRootType = "metal",
                        age = 20, gender = "male",
                        combat = CombatAttributes(currentHp = -1, currentMp = -1)
                    )
                )
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(
                pairingDisciple("11", "甲一", "male").copy(
                    skills = SkillStats(morality = 28, loyalty = 40)
                )
            )
        )
    }

    /**
     * 场景⑭（批 13-2b）：世界关卡刷新生成——玩家宗门 p1 + worldLevels 空 +
     * lastRefreshMonth=0（默认应刷新）+ 1 名 realm 9 弟子（playerAvgRealm=9），
     * (1,1) 起 3 旬跨 1→2 月界。月结步骤 4e：shouldRefresh（0==0）→ p1 存在 →
     * LevelGenerator.generateWorldLevels（maxNewLevels=6 → nextInt(6)+1 个）+
     * lastRefreshMonth 推进 14 + 妖兽移动（EXPLORATION 消费与 Kotlin 真实
     * ExplorationSystem 逐位对齐）。
     */
    private fun buildWorldLevelRefreshSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 玩家宗门（刷新门控通过）+ 无初始关卡（从零生成）
            worldMapSects = listOf(
                WorldSect(id = "p1", name = "青云宗", isPlayerSect = true, x = 0f, y = 0f)
            )
            // 非空 AI 弟子池（规避空表协议不对称）
            aiSectDisciples = mapOf(
                "ai-1" to listOf(
                    Disciple(
                        id = "90", name = "玄一", realm = 9, realmLayer = 1,
                        cultivation = 10.0, spiritRootType = "metal",
                        age = 20, gender = "male",
                        combat = CombatAttributes(currentHp = -1, currentMp = -1)
                    )
                )
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(pairingDisciple("11", "甲一", "male"))
        )
    }

    /**
     * 场景⑮（批 13-3）：月度自动排班——灵矿分配（autoMineRootCounts={1} +
     * 2 名 mining 60/40 弟子 + 1 灵矿空槽，月变步骤 6 执行）。选择灵矿路径
     * 对拍：不依赖 BuildingFeature 注册表（feature/game 测试环境未注册——
     * 住所分配空）与 repo 回滚面（生产槽 batchAssign 异步回写 mock 会回滚
     * 镜像）；灵矿为直接 state 写入，跨语言逐位一致。住所/生产分配由
     * GTest 黄金序列守护（AutoAssign* 3 用例）。
     */
    private fun buildAutoAssignSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 批 13-2b：预置刷新月（13）→ 不刷新
            worldLevelLastRefreshMonth = 1 * 12 + 1
            // 批 13-3：自动灵矿政策（灵根 1 根匹配）
            sectPolicies = sectPolicies.copy(
                autoMineRootCounts = listOf(1), autoMineThreshold = 1
            )
            // 灵矿 1 空槽
            spiritMineSlots = listOf(
                com.xianxia.sect.core.model.SpiritMineSlot(index = 0)
            )
            // 非空 AI 弟子池（规避空表协议不对称）
            aiSectDisciples = mapOf(
                "ai-1" to listOf(
                    Disciple(
                        id = "90", name = "玄一", realm = 9, realmLayer = 1,
                        cultivation = 10.0, spiritRootType = "metal",
                        age = 20, gender = "male",
                        combat = CombatAttributes(currentHp = -1, currentMp = -1)
                    )
                )
            )
        }
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = gameData.aiSectDisciples,
            disciples = listOf(
                pairingDisciple("11", "甲一", "male").copy(
                    skills = SkillStats(mining = 60)
                ),
                pairingDisciple("12", "甲二", "male").copy(
                    skills = SkillStats(mining = 40)
                )
            )
        )
    }

    @Test
    fun `purchase settlement matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildPurchaseSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景⑪ 显式断言：购买发生（仓库扣减 + 入袋 + 弟子灵石扣减 +
        // 宗门灵石入账）——精铁剑 100 + 聚气丹 50 = 150 入宗门
        assertEquals("购买后宗门灵石应为 150", 150L, actual.gameData.spiritStones)
        assertEquals("精铁剑堆叠应被扣减", 0, actual.equipmentStacks.size)
        assertEquals("聚气丹堆叠应被扣减", 0, actual.pills.size)
        val buyer = actual.disciples.first { d ->
            d.equipment.storageBagItems.isNotEmpty() ||
                d.equipment.spiritStones < 1000 ||
                d.equipment.storageBagSpiritStones < 600
        }
        assertTrue("至少一名弟子应购买入袋", buyer.equipment.storageBagItems.isNotEmpty())

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    @Test
    fun `december settlement triggers autoBuy matching Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildDecemberSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景⑩ 显式断言：灵石扣除 400（3×100 + 2×50）、商人库存清空、
        // 年度来源追踪、年度支出追踪
        assertEquals("12 月自动购买灵石扣除错误", 9600L, actual.gameData.spiritStones)
        assertEquals("商人库存应清空", 0, actual.gameData.travelingMerchantItems.size)
        assertEquals("自动购买年度装备来源追踪错误", 3,
            actual.gameData.annualEquipmentBySource["merchant:1"])
        assertEquals("自动购买年度丹药来源追踪错误", 2,
            actual.gameData.annualPillBySource["merchant:MEDIUM"])
        assertEquals("自动购买年度支出错误", 400L, actual.gameData.annualTotalExpenditure)

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    @Test
    fun `moral education hook triggers theft judgement matching Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildMoralEducationSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景⑬ 显式断言：道德 28→29（教化之道 +1）、钩子触发标记后被子事件 3
        // 兜底归零、SYSTEM 恰 1 抽（偷盗概率 prob=0.01 不中——已判定年标记
        // 排除兜底重复判定）
        val disciple = actual.disciples.first { it.id == "11" }
        assertEquals("道德应提升至 29（教化之道 +1）", 29, disciple.skills.morality)
        assertEquals("钩子标记后月度兜底无条件归零", 0,
            actual.gameData.theftJudgementsThisMonth)
        assertEquals("SYSTEM 分区终态不一致（钩子 1 抽 + 兜底 0 抽）",
            expected.gameData.rngStates[RngPartition.SYSTEM.id],
            actual.gameData.rngStates[RngPartition.SYSTEM.id])

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    @Test
    fun `world level refresh generates levels matching Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildWorldLevelRefreshSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景⑭ 显式断言：生成 1~6 个新关卡 + 刷新月推进 14（1 年 2 月绝对月）
        assertTrue("世界关卡刷新应生成新关卡", actual.gameData.worldLevels.isNotEmpty())
        assertEquals("lastRefreshMonth 应推进到月变绝对月",
            1 * 12 + 2, actual.gameData.worldLevelLastRefreshMonth)
        assertEquals("EXPLORATION 分区终态不一致（生成+移动消费）",
            expected.gameData.rngStates[RngPartition.EXPLORATION.id],
            actual.gameData.rngStates[RngPartition.EXPLORATION.id])

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    @Test
    fun `auto assign mine matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildAutoAssignSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 场景⑮ 显式断言：灵矿空槽由 mining 最高弟子（60）占用——11 槽占用
        // 扫描排除已占用弟子、候选排序按 mining 降序
        val mineSlot = actual.gameData.spiritMineSlots.firstOrNull()
        assertEquals("灵矿空槽应被占用", "11", mineSlot?.discipleId)
        assertEquals("灵矿分配弟子名", "甲一", mineSlot?.discipleName)

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /** 招募候选：未成年（16 岁不参与伴侣配对）/ 资质缺省 50（触发散列补算）/
     *  无装备功法（俘虏落库 no-op 规避 UUID 分叉）/ 满血哨兵 */
    private fun recruitCandidate(id: String, name: String, roots: String) =
        Disciple(
            id = id, name = name, realm = 9, realmLayer = 1,
            cultivation = 10.0, spiritRootType = roots,
            age = 16, gender = "male",
            combat = CombatAttributes(currentHp = -1, currentMp = -1)
        )

    /** 初始 RNG 分区状态：seed+partitionId 播种后各抽取 3 次（非平凡状态） */
    private fun initialRngStates(seed: Long): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(seed + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    // ── 验收测试 ───────────────────────────────────────────────────

    @Test
    fun `month settlement matches Kotlin bit-for-bit across one boundary`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        // 场景⑧（批 11-1）：自动招募惰性门复位——Kotlin 侧为 JVM 单例
        //（跨用例共享，其他测试可能已置 true）；C++ 侧瞬态字段读档默认 false
        RecruitService.RecruitLazyState.autoRecruitIdle = false

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinMonthSide(snapshot, PHASES)

        // ── C++ 被测侧 ──
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        assertExplicitAssertions(actual)
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /**
     * ⑥（批 10-3）偷盗保护期候选零效果断言：候选被保护期排除后不得产生任何
     * 偷盗副作用——无失窃灵石入袋、无入袋物品、无年度偷盗计数。
     */
    private fun assertTheftProtectedCandidateZeroEffect(actual: NativeGameState) {
        actual.disciples.firstOrNull { it.id == PROTECTED_THIEF_ID }?.let {
            assertEquals("保护期候选不应有失窃灵石入袋", 0L,
                it.equipment.storageBagSpiritStones)
            assertTrue("保护期候选不应入袋物品", it.equipment.storageBagItems.isEmpty())
        }
        assertEquals("偷盗兜底不应产生年度偷盗计数",
            0, actual.gameData.annualTheftCount)
    }

    /**
     * ⑦（批 10-4）附庸脱离零效果断言：至交好感 + 战力比 ≥5x → 概率 0.0，
     * 恰抽 1 次 SYSTEM 必不脱离——契约保留、零脱离事件。
     */
    private fun assertVassalBreakawayStays(actualGd: GameData) {
        assertEquals("附属契约不应脱离", 1, actualGd.vassalContracts.size)
        assertEquals("附属契约应保持原附属", "ai-3",
            actualGd.vassalContracts[0].vassalSectId)
        assertTrue(
            "不应产生脱离事件",
            actualGd.gameEventRecords.none { it.eventType == "vassal_breakaway" }
        )
    }

    /** 场景显式断言（可读性优先，全量结构对拍兜底） */
    private fun assertExplicitAssertions(actual: NativeGameState) {
        val actualGd = actual.gameData
        // ① 灵矿 lastSettledMonth 无条件推进到当前绝对月（1 年 × 12 + 2 月 = 14；
        //    矿空 rate=0 不入账但推进——差分回档保护语义，双端同构）
        assertEquals(
            "灵矿 lastSettledMonth 未无条件推进",
            (1 * 12 + 2).toLong(),
            actualGd.spiritMineLastSettledMonth.toLong()
        )
        // ③ 政策忠诚 + ⑤（批 10-2/10-3）叛逃/偷盗 + ② 伴侣配对
        assertLoyaltyAndLawEnforcementEffects(actual, actualGd)
        // ⑧（批 11-1）自动招募 + ⑨（批 11-2）秘境 AI 队伍派遣
        assertAutoRecruitEffects(actual, actualGd)
        assertSecretRealmAiTeams(actualGd)
        // ③ S1 政策月费经真实钱包扣除：100 × 全体弟子数（DISCIPLE_COUNT）
        assertEquals(
            "仁政爱徒月费未正确扣除",
            10000L - BENEVOLENT_MONTHLY_COST_PER_DISCIPLE * DISCIPLE_COUNT,
            actualGd.spiritStones
        )
        // ⑦（批 10-4）附庸脱离：至交好感 + 战力比 ≥5x → 概率 0.0，恰抽 1 次
        //    SYSTEM 必不脱离——契约保留、零脱离事件
        assertVassalBreakawayStays(actualGd)
        // ④（批 10-1）侦察过期清理：ai-1 过期移除 + isKnown 翻转 + 明细清空；
        // ai-2 未过期保留 + 明细新建刷新
        assertEquals("侦察过期条目未移除", setOf("ai-2"), actualGd.scoutInfo.keys)
        assertEquals(
            "未过期侦察信息内容漂移",
            "赤水宗", actualGd.scoutInfo["ai-2"]?.sectName
        )
        assertEquals("ai-1 明细应保留", true, actualGd.sectDetails.containsKey("ai-1"))
        assertEquals(
            "ai-1 明细 scoutInfo 应清空为默认",
            "", actualGd.sectDetails["ai-1"]?.scoutInfo?.sectId
        )
        assertEquals(
            "ai-1 明细非侦察字段应保留",
            "sect_ai1", actualGd.sectDetails["ai-1"]?.portraitRes
        )
        assertEquals(
            "ai-2 明细应由剩余侦察条目新建刷新",
            "ai-2", actualGd.sectDetails["ai-2"]?.scoutInfo?.sectId
        )
        val sectById = actualGd.worldMapSects.associateBy { it.id }
        assertEquals("ai-1 isKnown 未翻转", false, sectById["ai-1"]?.isKnown)
        assertEquals("ai-2 isKnown 应保持", true, sectById["ai-2"]?.isKnown)
    }

    /** ③ 政策忠诚 + ⑤（批 10-2/10-3）叛逃/偷盗 + ② 伴侣配对 组合断言 */
    private fun assertLoyaltyAndLawEnforcementEffects(
        actual: NativeGameState,
        actualGd: GameData
    ) {
        // ③ 政策忠诚：仁政爱徒 +1（50 → 51，coerceIn(0,100)）；原四弟子全部生效
        // （自动招募新弟子在 step8 入宗——晚于 step2 政策效果，忠诚保持 50）
        for (d in actual.disciples) {
            if (d.id == DESERTER_ID || d.id == NEW_RECRUIT_ID.toString()) continue
            assertEquals(
                "弟子 ${d.id} 忠诚未按仁政爱徒 +1",
                (BASE_LOYALTY + BENEVOLENT_LOYALTY_DELTA),
                d.skills.loyalty
            )
        }
        // ⑤ 叛逃候选：忠诚 0 + 政策 +1 = 1（若未叛逃离场）；偷盗兜底只归零
        // theftJudgementsThisMonth（其余弟子道德 50 ≥ 30 非候选；弟子 16 保护期
        // 排除 → 无标记递增，零抽取）
        assertEquals(0, actualGd.theftJudgementsThisMonth)
        assertTheftProtectedCandidateZeroEffect(actual)
        actual.disciples.firstOrNull { it.id == DESERTER_ID }?.let {
            assertEquals("叛逃候选忠诚应为 0+1", 1, it.skills.loyalty)
        }
        assertTrue(
            "叛逃判定后弟子数应为 6 或 7（偷盗保护期候选恒在场；叛逃候选由 SYSTEM 抽取序列决定去留；自动招募 +1 固定）",
            actual.disciples.size == 6 || actual.disciples.size == 7
        )
        assertTrue(
            "annualDesertedDisciples 应为 0 或 1",
            actualGd.annualDesertedDisciples == 0 || actualGd.annualDesertedDisciples == 1
        )
        // ② 伴侣配对：0.006 概率下预期无命中（partnerId 保持 null）；SYSTEM
        // 分区终态已含 4 组合各一次 nextDouble 的状态推进（全量对拍兜底）
        for (d in actual.disciples) {
            assertEquals("弟子 ${d.id} 意外配对", null, d.social.partnerId)
        }
    }

    /** ⑧（批 11-1）自动招募效果断言：匹配候选入宗（id 17/资质 82/recruitedMonth
     *  14/annualNewDisciples+1），不匹配候选保留列表 */
    private fun assertAutoRecruitEffects(actual: NativeGameState, actualGd: GameData) {
        val newRecruit = actual.disciples.firstOrNull { it.id == NEW_RECRUIT_ID.toString() }
        assertTrue("自动招募候选未入宗（缺 id 17）", newRecruit != null)
        newRecruit?.let {
            assertEquals("自动招募新弟子资质散列补算错误", NEW_RECRUIT_APTITUDE, it.skills.aptitude)
            assertEquals("自动招募新弟子 recruitedMonth 应为 14", 14, it.usage.recruitedMonth)
            // step8 入宗晚于 step2 政策忠诚效果 → 忠诚保持初始 50
            assertEquals("自动招募新弟子忠诚应为初始 50", 50, it.skills.loyalty)
        }
        assertEquals("不匹配候选应保留在 recruitList", listOf(RECRUIT_KEEP_ID),
            actualGd.recruitList.map { d -> d.id })
        assertEquals("自动招募应计入 annualNewDisciples", 1, actualGd.annualNewDisciples)
        assertEquals("自动招募后本月招募计数应为 1", 1, actualGd.recruitCountThisMonth)
    }

    /** ⑨（批 11-2）秘境 AI 队伍派遣断言：ai-3 有存活弟子 → 恰 1 队（幂等去重，
     *  id 为镜像生成字段已排除；成员按境界升序取 4） */
    private fun assertSecretRealmAiTeams(actualGd: GameData) {
        assertEquals("秘境 AI 队伍应恰派遣 1 队", 1, actualGd.secretRealmAITeams.size)
        actualGd.secretRealmAITeams.firstOrNull()?.let { team ->
            assertEquals("AI 队伍宗门应匹配", "ai-3", team.sectId)
            assertEquals("AI 队伍宗门名应回退/匹配宗门", "玄水宗", team.sectName)
            assertEquals("AI 队伍成员应取存活弟子", listOf("90"), team.members.map { it.discipleId })
            assertEquals("AI 队伍成员境界应保持", listOf(9), team.members.map { it.realm })
        }
    }

    // ── JSON 结构对拍（C++ 导出键集为权威覆盖面；与 T2.1 同构） ──────

    private fun assertCppSurfaceMatches(expected: JsonElement, actual: JsonElement) {
        assertNodeMatches(expected, actual, "$")
    }

    private fun assertNodeMatches(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            actual is JsonObject && expected is JsonObject ->
                compareObjects(expected, actual, path)
            actual is JsonArray && expected is JsonArray ->
                compareArrays(expected, actual, path)
            else -> assertPrimitiveEquals(expected, actual, path)
        }
    }

    private fun compareObjects(
        expected: JsonObject,
        actual: JsonObject,
        path: String
    ) {
        for ((k, a) in actual) {
            if (isMirrorGeneratedField(path, k)) continue
            val e = expected[k]
            assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失（协议漂移）", e != null)
            assertNodeMatches(e!!, a, "$path.$k")
        }
    }

    /**
     * diff 面排除的镜像生成/边界字段：
     * - timestamp：现实墙钟（Clock 注入边界）
     * - 任务 id（批 12-2）：Mission.id 为镜像生成（C++ 确定性自增 vs Kotlin
     *   UUID，语义等价仅保证唯一）；任务内容（template/rewards/difficulty）
     *   双端一致参与对拍——S-19 清偿后 MISSION(8) 分区双端消费对齐
     * - 库存集合 + 年度 by-source：InventorySystem 嵌套 update 写入
     *   （FakeGameStateStore 嵌套事务不回写外层 buffer——S-14 committed 读
     *   口径差家族），Kotlin-Fake 臂丢失，C++ 侧 GTest 黄金守护（603/603
     *   含入库/年度追踪断言）
     * - 秘境 AI 队伍 id：镜像生成（C++ 确定性自增 vs Kotlin UUID，语义等价
     *   仅保证唯一——inventory.h generateNewId 同款契约）
     */
    private fun isMirrorGeneratedField(path: String, k: String): Boolean = when {
        k == "timestamp" -> true
        k == "id" && path.contains("availableMissions") -> true
        k == "id" && path.contains("worldLevels") -> true   // 批 13-2b：Kotlin UUID vs C++ 空串
        k == "id" && isMirrorIdPath(path) -> true
        else -> false
    }

    /**
     * 镜像生成 id 字段路径（C++ 确定性自增 vs Kotlin UUID，语义等价仅保证
     * 唯一——inventory.h generateNewId 同款契约）：
     * - 秘境 AI 队伍（批 11-2）
     * - 库存集合（批 11-4：FakeGameStateStore 嵌套事务修复后库存内容已纳入
     *   diff 对拍面，仅 id 为镜像生成字段排除）
     */
    private fun isMirrorIdPath(path: String): Boolean {
        if (path.contains("secretRealmAITeams")) return true
        return INVENTORY_COLLECTION_KEYS.any { path.contains(it) }
    }

    private fun compareArrays(
        expected: JsonArray,
        actual: JsonArray,
        path: String
    ) {
        assertEquals("$path size", expected.size, actual.size)
        actual.forEachIndexed { i, a ->
            assertNodeMatches(expected[i], a, "$path[$i]")
        }
    }

    /** 数字统一 IEEE754 double 位比较（C++ 导出整值 double 规范化为整数形式） */
    private fun assertPrimitiveEquals(
        expected: JsonElement,
        actual: JsonElement,
        path: String
    ) {
        require(actual is JsonPrimitive && expected is JsonPrimitive) {
            "$path 结构不匹配：期望=$expected 实际=$actual"
        }
        assertTrue("$path 期望=$expected 实际=$actual", primitivesEqual(expected, actual))
    }

    private fun primitivesEqual(expected: JsonPrimitive, actual: JsonPrimitive): Boolean =
        when {
            actual === JsonNull || expected === JsonNull -> actual == expected
            actual.booleanOrNull != null || expected.booleanOrNull != null ->
                actual.booleanOrNull == expected.booleanOrNull
            else -> numericOrStringEquals(expected, actual)
        }

    private fun numericOrStringEquals(
        expected: JsonPrimitive,
        actual: JsonPrimitive
    ): Boolean {
        val eD = expected.doubleOrNull
        val aD = actual.doubleOrNull
        return if (eD != null && aD != null) {
            java.lang.Double.doubleToLongBits(eD) ==
                java.lang.Double.doubleToLongBits(aD)
        } else {
            actual.content == expected.content
        }
    }
}
