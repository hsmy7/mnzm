package com.xianxia.sect.core.state

import com.xianxia.sect.core.state.reversechannel.w4AClosedUnits
import com.xianxia.sect.core.state.reversechannel.w4ADomainEvidence
import com.xianxia.sect.core.state.reversechannel.w4ARetainedGameDataFields
import com.xianxia.sect.core.state.reversechannel.w4BClosedUnits
import com.xianxia.sect.core.state.reversechannel.w4BDomainEvidence
import com.xianxia.sect.core.state.reversechannel.w4BRetainedGameDataFields
import com.xianxia.sect.core.state.reversechannel.w4CClosedUnits
import com.xianxia.sect.core.state.reversechannel.w4CDomainEvidence
import com.xianxia.sect.core.state.reversechannel.w4CRetainedGameDataFields
import com.xianxia.sect.core.state.reversechannel.w4DClosedUnits
import com.xianxia.sect.core.state.reversechannel.w4DDomainEvidence
import com.xianxia.sect.core.state.reversechannel.w4DRetainedGameDataFields

/**
 * ReverseChannelPolicy — 反向增量通道**分域关闭策略**（batch-21 终局收敛批）。
 *
 * ## 通道语义
 * AUTHORITATIVE 稳态下 C++ 是真相源，Kotlin 侧 `stateStore.update {}`（非
 * [GameStateStore.updateMirror]）产生的变更由 `captureReverseDirty` 累积，
 * 每个 tick 经 `StateSyncService.applyDirtyToNative()` 回导 C++。本策略决定
 * **哪些状态单元仍需要这条回导**（未列出的单元一律照常传输——默认开放，
 * 关闭必须是显式且带证据的决定）。
 *
 * ## 为什么默认开放
 * 关闭某单元 = 该单元的 Kotlin 写入**永不到达 C++**（下一次前向镜像会以 C++
 * 侧值覆盖）。只有当"该单元在 AUTHORITATIVE 稳态下不存在 Kotlin 写者"被穷尽
 * 审计证实时才可关闭；因此未分类单元保持传输，配合
 * `ReverseChannelPolicyGuardTest` 的**穷尽分类守卫**（新增字段必须显式归类），
 * 关闭清单只增不减地接受审查。
 *
 * ## 本体的职责边界（W4-00 并行前置批切分后）
 * 本文件只保留**类型、协议常量、聚合与开关/回滚/检测 API**；三处审计数据
 * （关闭清单 / 在册保留字段 / 逐域结论证据）按批次拆到
 * `state.reversechannel` 包下的四个文件，使三个并行批次各写各的文件而互不冲突：
 *
 * | 文件 | 归属 | 域 |
 * |---|---|---|
 * | `W4AChannelClosures.kt` | W4-A 弟子与建设轴 | DISCIPLE / LIFE_CYCLE / BUILDING / ROAD / PRODUCTION |
 * | `W4BChannelClosures.kt` | W4-B 内政与经济运营轴 | PATROL / BOUNDARY / INVENTORY / DIPLOMACY / SAVE_LOAD |
 * | `W4CChannelClosures.kt` | W4-C 战斗与世界协议轴 | BATTLE / SECRET_REALM |
 * | `W4DChannelClosures.kt` | W4-D 汇流波（串行收口） | RECRUIT / AI_SECT + 未分配经济/世界/运营字段 |
 *
 * 🔴 **本文件此后冻结**：三个并行批次不得修改本文件（`git diff --name-only
 * w4-base..<batch-branch>` 中出现即为越界）；新增/调整关闭单元只改自己那一份
 * closures 文件。见 `docs/parallel-batches-w4/README.md` §3.1 项 5 与 §5.3。
 *
 * ## 逐域回滚
 * 每个关闭单元归属一个 [Domain]；[reopenDomain] 可把某域**整体**恢复为开启
 * （紧急回滚路径，见 batch-21 §9 风险表"关完后发现漏域 → 单域回滚"）。
 *
 * ## 关闭检测
 * 关闭后若仍有 Kotlin 写者触碰该单元，即为数据丢失缺陷。检测点：
 * - 集合/弟子通道：捕获阶段（`captureCollection` / 弟子脏 id）——引用变化即命中；
 * - gameData 字段：信封构建阶段（当前值 vs C++ 已知值）——见
 *   `StateSyncService.buildReverseEnvelope` 的关闭域写入检测。
 * 命中时记录 ERROR 日志并把单元名写入诊断计数（[noteClosedWrite]），
 * 使"漏域"从静默丢数据变为**可观测缺陷**。
 */
@Suppress("TooManyFunctions")  // 反向通道策略契约面：审计结论查询（域/单元/字段/集合/段）+ 逐域回滚 +
// 关闭域写入检测诊断 三类只读/开关入口不可合并（每类各 4–6 个具名查询面），属协议边界而非可下放助手
object ReverseChannelPolicy {

    /**
     * 反向通道域（与 `docs/ui-read-surface.md` §4.1 逐域写者审计表一一对应）。
     *
     * @property displayName 中文域短名（日志/文档口径）
     */
    enum class Domain(val displayName: String) {
        INVENTORY("库存"),
        BUILDING("建筑"),
        ROAD("道路"),
        PATROL("巡逻/住所/矿场"),
        DISCIPLE("弟子管理"),
        RECRUIT("招募/派遣/俘虏"),
        PRODUCTION("生产/灵田"),
        SECRET_REALM("秘境"),
        BOUNDARY("月年编排"),
        DIPLOMACY("外交/好感/附庸"),
        AI_SECT("AI 宗门"),
        BATTLE("战斗/探索"),
        LIFE_CYCLE("弟子生命周期"),
        SAVE_LOAD("存档/读档/自愈"),
    }

    /** 传输单元种类。 */
    enum class Kind {
        /** gameData 字段级补丁单元（名字 = GameData JSON 键） */
        GAME_DATA_FIELD,

        /** 实体集合段（名字 = 集合名，如 `equipmentStacks`） */
        COLLECTION,

        /** 弟子通道（脏 id → 全实体 upsert / removed） */
        DISCIPLE_CHANNEL,

        /** 顶层 @Transient 段（名字 = 段名，如 `aiSectDisciples`） */
        TOP_LEVEL_SECTION,
    }

    /** 域关闭状态。 */
    enum class Status {
        /** 该域稳态写者全部归 C++——关闭单元即该域全部传输面 */
        CLOSED,

        /** 部分单元已关闭，其余仍有稳态 Kotlin 写者（[DomainVerdict.residualEvidence] 给出证据） */
        PARTIAL,

        /** 仍有稳态 Kotlin 写者，该域整体保留传输 */
        OPEN,
    }

    /**
     * 已关闭的传输单元。
     *
     * @property domain 归属域（回滚粒度）
     * @property kind 单元种类
     * @property name 单元名（gameData 字段名 / 集合名 / 段名）
     */
    data class ClosedUnit(
        val domain: Domain,
        val kind: Kind,
        val name: String,
    )

    /**
     * 逐域审计结论（batch-21 前置复核产物，逐条可追溯）。
     *
     * @property domain 域
     * @property status 关闭状态
     * @property closedUnits 该域已关闭的传输单元（[Status.CLOSED] 时即该域全部传输面）
     * @property residualEvidence 保留传输的稳态 Kotlin 写者证据（`文件:行 函数` 形式；
     *           [Status.CLOSED] 时必须为空——空证据的保留视为审计未完成）
     */
    data class DomainVerdict(
        val domain: Domain,
        val status: Status,
        val closedUnits: List<ClosedUnit>,
        val residualEvidence: List<String>,
    )

    /** 弟子通道单元名（信封键 = `disciples`）。 */
    const val DISCIPLE_CHANNEL_NAME = "disciples"

    /** 顶层段名：AI 宗门弟子池（`@Transient`，反向信封单独全量段）。 */
    const val SECTION_AI_SECT_DISCIPLES = "aiSectDisciples"

    /** 顶层段名：妖兽视图锁定集（`@Transient`，反向信封单独全量段）。 */
    const val SECTION_LOCKED_BEAST_IDS = "lockedBeastIds"

    /**
     * 实体集合段名全集（反向信封协议名，与 `StateSyncService` 的集合常量同源）。
     *
     * 用途：关闭清单的合法性与覆盖性守卫（`ReverseChannelPolicyGuardTest`）——
     * 集合名新增/改名时守卫失败，强制同步本清单与关闭结论。
     */
    val COLLECTION_NAMES: Set<String> = linkedSetOf(
        "equipmentStacks",
        "equipmentInstances",
        "manualStacks",
        "manualInstances",
        "pills",
        "materials",
        "herbs",
        "seeds",
        "storageBags",
    )

    /**
     * 在册保留传输的 gameData 字段（逐域审计证据 → **不可关闭**）= 四份分片之并集。
     *
     * 判定口径：该字段在 AUTHORITATIVE 稳态下存在 Kotlin 写者（UI 操作面无 native 臂、
     * native 事务后的 Kotlin 残差、月年编排内的 Kotlin 活路、自愈/平台效应）。
     * 关闭其中任一字段 = 该写入永不到达 C++（数据丢失缺陷）。
     *
     * 与 [DomainVerdict.residualEvidence] 的关系：本清单是**字段级**落点，
     * 结论表给出域级叙事与证据；两者由 `ReverseChannelPolicyGuardTest` 的
     * 穷尽分类守卫绑定（关闭清单 ∪ 本清单 == GameData 序列化面全字段）。
     *
     * 新增/摘除条目请改 `state.reversechannel` 下对应批次的 closures 文件（本文件冻结）。
     */
    val transportedGameDataFields: Set<String> =
        w4ARetainedGameDataFields + w4BRetainedGameDataFields +
            w4CRetainedGameDataFields + w4DRetainedGameDataFields

    /**
     * 已关闭的传输单元（batch-21 逐域关闭清单）= 四份分片之并集。
     *
     * 每条都经**穷尽写者审计**判定为"AUTHORITATIVE 稳态下无 Kotlin 写者"
     * （仅回退臂 / 读档新档 / 死代码 / 非运行期写入），证据见
     * `docs/ui-read-surface.md` §4.4 与 `docs/cpp-migration-handover-m0.md` §2.53。
     * 逐域回滚用 [reopenDomain]。
     */
    private val closedUnits: List<ClosedUnit> =
        w4AClosedUnits + w4BClosedUnits + w4CClosedUnits + w4DClosedUnits

    /**
     * 逐域审计结论证据（域级叙事）= 四份分片之并集。
     *
     * 用 `groupBy` 归并而非 `Map.plus`：万一同一域的证据被写进两个分片文件，
     * `plus` 会**静默丢弃**其中一份，而 `groupBy` 会保留全部（便于守卫/人工发现）。
     */
    private val domainEvidence: Map<Domain, List<String>> = (
        w4ADomainEvidence.entries + w4BDomainEvidence.entries +
            w4CDomainEvidence.entries + w4DDomainEvidence.entries
        )
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, lists) -> lists.flatten() }

    /**
     * 逐域审计结论（域级叙事 + 证据；关闭单元由 [closedUnits] 按域归并）。
     *
     * 状态由证据自动判定：有关闭单元且有稳态写者证据 ⇒ [Status.PARTIAL]；
     * 仅关闭单元 ⇒ [Status.CLOSED]；仅有稳态写者 ⇒ [Status.OPEN]。
     */
    private val verdicts: List<DomainVerdict> = Domain.values().map { domain ->
        verdict(domain, domainEvidence[domain].orEmpty())
    }

    /** 构造域级结论（状态按"关闭清单 × 稳态写者证据"自动判定）。 */
    private fun verdict(domain: Domain, residualEvidence: List<String>): DomainVerdict {
        val closed = closedUnits.filter { it.domain == domain }
        val status = when {
            closed.isNotEmpty() && residualEvidence.isEmpty() -> Status.CLOSED
            closed.isNotEmpty() -> Status.PARTIAL
            else -> Status.OPEN
        }
        return DomainVerdict(domain, status, closed, residualEvidence)
    }

    /** 关闭键 → 归属域（逐域回滚用）。 */
    private val closedUnitDomains: Map<String, Domain> =
        closedUnits.associate { key(it.kind, it.name) to it.domain }

    /** 紧急回滚开关：被重新打开的域（[reopenDomain]）。 */
    @Volatile
    private var reopenedDomains: Set<Domain> = emptySet()

    /**
     * 关闭清单覆盖（null = 使用内置审计结论）。
     *
     * 仅用于测试与演练：验证"关闭后仍被写入"的检测链路、逐域回滚语义、
     * 以及关闭前后的信封面差分测量。生产代码不得调用。
     */
    @Volatile
    private var closedUnitsOverride: List<ClosedUnit>? = null

    /** 关闭域写入检测记录（诊断面；有上限，仅保留最近 [MAX_CLOSED_WRITE_RECORDS] 条）。 */
    private val closedWriteRecords = ArrayDeque<String>()

    /** 关闭域写入累计计数（自进程启动；仅诊断，不参与逻辑）。 */
    @Volatile
    private var closedWriteCount: Long = 0

    /** 单元是否仍参与反向传输（默认 true = 保持现状）。 */
    fun isTransported(kind: Kind, name: String): Boolean {
        val override = closedUnitsOverride
        if (override != null) {
            val hit = override.firstOrNull { it.kind == kind && it.name == name } ?: return true
            return hit.domain in reopenedDomains
        }
        val domain = closedUnitDomains[key(kind, name)] ?: return true
        return domain in reopenedDomains
    }

    /** gameData 字段是否仍参与反向传输。 */
    fun isGameDataFieldTransported(field: String): Boolean =
        isTransported(Kind.GAME_DATA_FIELD, field)

    /** 实体集合是否仍参与反向传输。 */
    fun isCollectionTransported(collection: String): Boolean =
        isTransported(Kind.COLLECTION, collection)

    /** 顶层段是否仍参与反向传输。 */
    fun isSectionTransported(section: String): Boolean =
        isTransported(Kind.TOP_LEVEL_SECTION, section)

    /** 弟子通道是否仍参与反向传输。 */
    fun isDiscipleChannelTransported(): Boolean =
        isTransported(Kind.DISCIPLE_CHANNEL, DISCIPLE_CHANNEL_NAME)

    /** 生效的关闭单元（覆盖优先；覆盖为 null 时用内置审计结论）。 */
    private fun effectiveClosedUnits(): List<ClosedUnit> = closedUnitsOverride ?: closedUnits

    /** 已关闭的 gameData 字段（关闭域写入检测的比较面；回滚域不计入）。 */
    fun closedGameDataFields(): Set<String> = effectiveClosedUnits()
        .filter { it.kind == Kind.GAME_DATA_FIELD && it.domain !in reopenedDomains }
        .mapTo(HashSet()) { it.name }

    /** 已关闭的域集合（观测面）。 */
    fun closedDomains(): Set<Domain> = verdicts.filter { it.status != Status.OPEN }.mapTo(HashSet()) { it.domain }

    /** 某域的完整审计结论（不存在时为 null——守卫测试要求全覆盖）。 */
    fun verdictOf(domain: Domain): DomainVerdict? = verdicts.find { it.domain == domain }

    /** 逐域审计结论快照（守卫测试/文档一致性用）。 */
    fun verdictsSnapshot(): List<DomainVerdict> = verdicts

    /**
     * 紧急回滚：把某域全部关闭单元恢复为传输。
     *
     * @param domain 目标域
     */
    fun reopenDomain(domain: Domain) {
        reopenedDomains = reopenedDomains + domain
    }

    /** 撤销回滚（恢复关闭语义）。 */
    fun closeDomain(domain: Domain) {
        reopenedDomains = reopenedDomains - domain
    }

    /** 测试/回滚后复位：清空回滚开关与诊断计数。 */
    fun resetSwitches() {
        reopenedDomains = emptySet()
        closedUnitsOverride = null
        synchronized(closedWriteRecords) { closedWriteRecords.clear() }
        closedWriteCount = 0
    }

    /**
     * 覆盖关闭清单（null = 恢复内置审计结论）。
     *
     * 仅测试/演练使用（验证检测链路、回滚语义与关闭前后信封面差分）。
     */
    fun overrideClosedUnitsForTest(units: List<ClosedUnit>?) {
        closedUnitsOverride = units
    }

    /**
     * 登记一次"关闭域仍被 Kotlin 写入"检测（诊断，不影响状态）。
     *
     * @param kind 单元种类
     * @param name 单元名
     * @param source 检测点（日志定位用）
     */
    fun noteClosedWrite(kind: Kind, name: String, source: String) {
        closedWriteCount++
        synchronized(closedWriteRecords) {
            if (closedWriteRecords.size >= MAX_CLOSED_WRITE_RECORDS) closedWriteRecords.removeFirst()
            closedWriteRecords.addLast("${kind.name}:$name@$source")
        }
    }

    /** 关闭域写入检测累计次数（诊断面）。 */
    fun closedWriteCountSnapshot(): Long = closedWriteCount

    /** 关闭域写入检测明细快照（最近若干条）。 */
    fun closedWriteRecordsSnapshot(): List<String> = synchronized(closedWriteRecords) {
        closedWriteRecords.toList()
    }

    private fun key(kind: Kind, name: String): String = "${kind.name}:$name"

    /** 诊断记录上限（防无界增长）。 */
    private const val MAX_CLOSED_WRITE_RECORDS = 64
}
