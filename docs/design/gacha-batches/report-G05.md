# G05 · 删招募链 + recruitList 恒空 + 招募按钮占位 — 批次报告

> 批次：G05（M1 序列第 2 批，前置 G02 `5dbaac1e3`）；执行：三子代理并行（C++ 面 / Kotlin 生产+UI / Kotlin 测试）+ 主线程集成（Room v56 / 恒空裁决 / 门禁 / 收口）。
> 侦察：`recon-G05-G06-G08-G09.md` §G05-1~6 + §X-2（施工前全部行号 grep 复核）。

## 一、做了什么

### 1. 删除面（三端）

| 端 | 删除/改造 |
|---|---|
| **C++**（38 文件 +180/−2975） | `recruit_settlement.h`、`recruit_tx.h`、`test/manual_recruit_test.cpp`、`test/recruit_tx_test.cpp` 整删；`year_settlement.h` 删 processRefreshRecruitList 整段（招贤乘区 / 广纳门徒 +50% / lastRecruitYear 写入 / 惰性复位+processAutoRecruit）+ 招募常量 6 + 强制伴随 processAutoReject / processRecruitAging / 年变 T2 四调用点；`government.h` 删三年扣费段 + openRecruitmentToggleTx + field 映射；`ai_sect_recruit.h` 删 generateYearlyAiRecruits + runSectRecruitmentIfDue + 间隔常量三件（**:116-174 AI 宗生成面一字未动**）；`child_birth.h` 删 autoRecruit 钩子 + **同摘 recruitList.push_back(child)**（跨语言统一裁决）；`month_settlement.h` 删 processAutoRecruit 子事件；`models.h`/`json_codec.cpp` 删 8 字段（openRecruitment / openRecruitmentLastPaidMonth / autoRecruitSpiritRootFilter / autoRejectSpiritRootFilter / autoRecruitIdle / autoRejectIdle / lastRecruitYear / lastAiSectRecruitYear）；`lock_beast_tx.h` SETTINGS_PATCH 白名单两字段（17→15）；`execute_dispatch.cpp` 删 handleRecruitTx + 1681 case（冻结口径：只删退役分支）；JNI 删 4 实现（manual/all/resetIdle + GameCoreJni 对拍 nativeCoreManualRecruitFromList，nativeMemoryTrim 误删当场还原） |
| **Kotlin 生产** | 整删 7 文件（RecruitService / GameEngineRecruitOps / CultivationServiceRecruitOps（商人方法迁入 CultivationService）/ RecruitAllEnvelope / ManualRecruitEnvelope / RecruitIntegrity / RecruitDialog）；DiscipleDelegate 招募 6 方法 + Facade 契约/实现 + 功法Ops1 三方法 + SettingsAssign/SettingsOps 过滤器 + GameCoreBridge 3 external fun；调用点全清（月结 autoRecruit×2、年结 refreshRecruitList/autoReject/recruitAging、洞窟/生育钩子、新档 refreshRecruitList(1)、读档惰性重置×2、CultivationSettlement 月扣块、SectPolicyToggleUseCase 广纳门徒 + VM×2 + 天书 PolicyItem + GameConfig 4 常量）；字段删 5+1（lastRecruitYear/lastAiSectRecruitYear/openRecruitmentLastPaidMonth/autoRecruitSpiritRootFilter/autoRejectSpiritRootFilter + SectPolicies.openRecruitment）；AISectDiscipleManager 招募生成面连根删（recruitYearlyDisciples + generateYearlyRecruits + 2 常量 + generateQiRefiningDisciple 连带） |
| **UI** | `renderRecruit` 分支体 → 「寻访功能尚未开放」占位（UnifiedGameDialog + 关闭钮，签名保留 @Suppress 供 G11）；**按钮 / DialogType.Recruit / GameRoute.Recruit / 路由映射 / 备用入口全部保留** |

### 2. recruitList 恒空契约（三端字段保留）
- 生产两处：`RecruitListCleanupRule` 恒 `Repaired(gameData.copy(recruitList = emptyList()), listOf("招募链已下线，招募列表清空"))`（无论输入，触发落盘）+ `GameEngineLoadDataOps.sanitizeRecruitListAfterLoad` 读档整表清空（cache 命中路径兜底）；`NumericSanitizeRule` recruitList 分支删除（否则抢写死码）。
- 保留白名单：Proto(24) / Room 列 / `GameHeavyData.KEY_RECRUIT_LIST` / Storage 读写回填 / `SaveDataVersionMigrator:84` 缩放 / `GameDataFieldPatch` 协议面 / 年报 newDisciples 读清 + `DiscipleService:172`、`RedeemCodeService:339/504` 写入点（G08 改道）。
- grep 判据实测：src/main 玩法追加臂 = 0（恒空两处 + Storage 槽位重置 + 必保白名单）；8 模式仅剩 13 处冻结历史迁移命中。

### 3. Room v55→v56（主线程）
- `GameDatabaseMigrationsV56.kt`：`V56_GAME_DATA_DROPPED_COLUMNS`（lastRecruitYear / last_ai_sect_recruit_year / open_recruitment_last_paid_month / autoRecruitSpiritRootFilter / autoRejectSpiritRootFilter）+ `V56_SECT_POLICY_DROPPED_COLUMNS`（autoRecruitSpiritRootFilter）——**列名以 55.json 文本权威核对**（五列均归属 game_data + sect_policy_state createSql 实证；exploration 无独立表）；双表 `rebuildTableDroppingColumns`（game_data pk=(id,slot_id)+5 索引；sect_policy_state pk=(slot_id)+1 索引）。
- `DATABASE_VERSION` 55→56 + ALL_MIGRATIONS 注册 + 版本史注释；**KSP 曾就地改写 55.json（4+/41−）→ 立即 git checkout 恢复权威快照，bump 后 KSP 只出 56.json**（新 identity 六列残留全 0，55.json git 状态为空）。
- 新增 `RoomMigrationV55To56Test`（真实 Room 校验 + pre/post 列断言 + 索引重建 + 数据保留，3 helper 防 LongMethod）；`RoomMigrationV51To52Test` 全链期望改**交集语义**（注册删列集 ∩ v39 起点既有列——容纳 last_ai_sect_recruit_year 这类 v41 链内生灭列），KDoc 同步。

### 4. 共享符号迁移（G06/G08 兼容）
- `isSamePerson` + 签名三件套迁入 `disciple_tx.h` 紧邻 renameDiscipleTx 的 `namespace detail`（KDoc 明写唯一消费者），调用点改 `detail::`；`grep recruit_settle` 在 disciple_tx.h 零命中，rename 净化用例随 ctest 绿。
- `nextInstanceId`→`inventory.h`；`minRealmForRarity`/`kotlinDoubleString`→`settlement_detail.h`（equipment_db.h 系生成物禁手改）。

### 5. ActionId 退役与 JNI 面
- 1630/1631/1632/1681 四号 desc 改【已退役，编号禁复用】（core.mjs 串行批内修订 + regen 双产物 ±4 行），`kAllActionIdsCount=198`、maxId=1861 不变；dispatch 删 case + `dispatch_guard_test.cpp` retired 集登记四号。
- JNI：Kotlin 3 external fun 删 → `check-jni-count --update` 同步降基线 **89→86**，复验 `total=86/86，双桥无扩散` EXIT=0。

## 二、主线程裁决（中期通报处置）

| # | 裁决 | 理由 |
|---|---|---|
| 1 | **三个 recruitList 追加臂全摘**：① ChildBirth 新生儿写入行（系统留 G03）② CaveExploration AI 占领死臂 ③ **GameEngineBattleOps:350 战俘收编（活路径）** | 占位 UI 不渲染列表 + 恒空兜底 ⇒ 任何写入 = 玩家不可见必坏路径；③ 登记产品变更：**战俘收编随招募链下线，G11 寻访为唯一新增弟子渠道，如需保留另开批次** |
| 2 | `processSectDisciplesYearlyRecruitment` **连根删** | 无调用者（T2 入口已删）+ recruitList 写入者双重该删；必保清单原意 = C++ `:116-174` AI 宗自身成长面，非列表桥梁 |
| 3 | autoReject 过滤链**对称全删**（Kotlin 字段/函数 + C++ models/lock_beast 白名单） | 评估器 processAutoReject 已死，留字段 = 不对称死态 |
| 4 | AI 宗周期招募整体下线；`generateRandomAiDisciple` 死面**按指令保留**候 G08/G10 | 撰写面产物 = 玩家招募列表候选，与恒空规则冲突 |

## 三、验证（全部实跑）

| 门 | 结果 |
|---|---|
| 桌面 `game-core` 编译 | ✅ EXIT=0（38 文件改动含 4 删除） |
| 桌面 ctest | ✅ **1487 总 / 1483 过 / 4 败**——与 G02 基线**同名同因同一组**（ChildBirth.GoldenSequenceSingleBirth / DiscipleFactory.GoldenSequenceSeed42 / Seed987654321Female / DeterminismProbe，均 B 类既留），**零新增**；总数 −49 = 招募域用例删除 |
| `compileReleaseKotlin` | ✅ BUILD SUCCESSFUL（A 分片 4 轮 + 主线程终树复验） |
| detekt 六模块 | ✅ EXIT=0（首跑 7 条批内违规根因修：V56 超长行 / DiscipleFacadeImpl 孤儿 import×3+死常量 / BattleOps 孤儿 DomainLog / CultivationSettlement 悬空参数 deductedPolicies（广纳门徒块唯一消费点，参数+调用点同删）） |
| JUnit 六模块（`--max-workers=1` + Diff JNI 桥） | ✅ **7720/7720 全绿**：app 1015 / data 812（v56 落位后 17 红→0）/ domain 1649 / engine 3130 / ui 146 / feature:game 968 |
| `gen-action-ids` | ✅ 198 actions (maxId=1861) |
| `gen-game-data --check` | ✅ sha256 `915563485e9fd41d14c77d76b2f25ff711c535e82f384d34a4faca5b10be84d2` 不变 |
| `check-jni-count` | ✅ 86/86（基线随批降） |
| `check-agent-instructions` | ✅ EXIT=0 |
| 55.json 历史快照 | ✅ git 状态为空（KSP 就地改写已恢复，bump 后不再触碰） |

## 四、旧用例处置表

**Kotlin 测试（c340 分片，42 项）**
- 整删 ×10：RecruitServiceTest / RecruitNativeTxGateTest / GameEngineRecruitTest / RecruitAllEnvelopeTest / ManualRecruitEnvelopeTest / DiscipleFacadeImplRecruitTest / DiscipleDelegateRecruitGuardTest / RecruitIntegrityTest / RecruitDedupeEquivalenceTest / DiscipleTablesRecruitTest
- 清单内改写 ×13：RecruitListCleanupRuleTest（恒空 5 用例：非空/空/混合均恒 Repaired + details 精确相等 + 二次校验仍 Repaired + 注册表 order=20 实例恒等）、MerchantAndRecruitServiceTest（删招募段保 merchant）、GameEngineCoordination/DiscipleOpsNativeTxGate/ResidualNativeTxGate、PolicyNativeTxGate（广纳门徒 2 用例）、GameViewModelTest/DialogTypeRenderCoverageTest（核对不动，568-72 实为按钮保留面）、DiffMonthSettlement（场景⑧摘除+弟子数7→6+生育恒空断言）、DiffYearSettlement（AI 招募用例删+fixture）、DiffAuthoritativeTick（手动招募对拍删）、DiffMission/DiffProduction（import+复位）
- 清单外补漏 ×17：NumericSanitizeRuleTest / StateEntitiesTest / GameDataTest / DiffStateTest / GameViewStoreGuard / GameDataFieldPatchGuard（五字段同步）、SaveValidatorTest+Integration（恒空连带 10 处 Passed→恒 Repaired）、CaveExplorationProcessorTest、ChildBirthSystemTest、AISectDiscipleManagerTest、GameEngineRenameTest、SaveDataVersionMigrator/SaveDataDirectSerialization（恒空判据换载体）、DiscipleFactoryTest 注释、DiffRngBridge external fun 删、6 文件 9 处 recruitService 构造参数删
- 保留+登记：Room 迁移历史列面、SectLevel.recruitRange、GameEvents.DiscipleRecruitedEvent（死事件观察）、GameNotification.RecruitFailed（sealed 成员）、协议面非空载体四处
- 终扫：src/test 已删符号零引用；recruitList「期望非空」断言唯一残留 = 恒空断言本身

**C++ 测试（T 分片）**：manual_recruit_test.cpp / recruit_tx_test.cpp 整删（CMake 注册同步）；month/year settlement 招募段处置；child_birth 金序列测试重构（断言主体改「processMonthlyBirth 断恒空 + 同种子直调 createChild 复现原黄金值」，黄金原值未动）。

**RNG 纪律**：金黄/baseline **零重录**（G10 专属）。

## 五、G10 登记（本批新增观察项，不重录）

| ID | 项 | 状态 |
|---|---|---|
| 既有 | C++ 金序列 4 条（ChildBirth / DiscipleFactory×2 / DeterminismProbe） | 同名同因同组，B 类既留 |
| B-1 | DiffMonthSettlement 生育：黄金锚定已删，C++ 同摘 push_back 后双臂残余分叉待确认 | G10 复跑定性 |
| B-2 | DiffMonthSettlement 主场景⑧摘除轨迹（原契约 auto-recruit 零 RNG，预期不变） | G10 确认 |
| B-3 | DiffYearSettlement T2 11→10 双端同步 | G10 确认 |
| B-4 | DiffAuthoritativeTick 100 旬对拍（年变 T1/T2 组成变化，双端同步预期一致） | G10 确认 |
| 登记 | AI 招募确定性用例随 generateYearlyAiRecruits 删除下线——AI 弟子生成面现无专属金序列守卫 | G10 重建 AI 面时补 |

## 六、@ProtoNumber 空缺号（禁复用，reserved 注释已落）

25（lastRecruitYear）/ 93（openRecruitmentLastPaidMonth）/ 101（autoRecruitSpiritRootFilter）/ 29（openRecruitment）/ 210（autoRejectSpiritRootFilter）/ 219（lastAiSectRecruitYear）——OldSerializableSaveData 与 GameData 两处 G02 同款注释；C++ 侧无 OldSerializable 概念，json_codec 对应消费面同批清零。

## 七、途中发现登记（公约 12，本批不动）

1. **产品变更**：战役俘虏收编随招募链下线（裁决①-③）——G11 寻访为唯一新增弟子渠道。
2. `prisonerSpiritRootFilter` 字段+设置 UI 存活但战俘结算消费者已零（自读自写死设置）。
3. `GameNotification.RecruitFailed` + GameOverlayHost 分支：发布者已零。
4. `RECRUIT_MONTHLY_LIMIT`、`recruitCountThisMonth`：评估器已删，常量/字段残留。
5. `checkAndRepairMerchantAndRecruit` 函数名含 Recruit（实体为纯商人修复路径，改名超范围）。
6. `lock_beast_tx.h` 两条 KDoc 引用不存在的 `SettingsPatchFieldCoverageTest`（HEAD 既有死引用）——注释已改写为「Kotlin updateSettingsNative 字段名与三处逐字一致，新增入口两端同批核对」的实际约束陈述。
7. ActionIds 保号留洞（1630-1632/1681）随退役 desc 登记。
8. `GameViewModel.recruitListAggregates` 派生保留（recruitList 协议面派生，G11 可复用）。

## 八、集成收口清单（G06 开工前置，全部达成）

- [x] G05 单次提交（serial 约束）
- [x] isSamePerson 迁移就位（G06 renameDiscipleTx 依赖不断，X-2#2 满足）
- [x] recruitList 恒空 + RecruitListCleanupRule 新语义（X-2#3：G06 删除时面对的是恒空规则）
- [x] annualNewDisciples 保留 3 点核验（X-2#4）
- [x] 56.json 入库、55.json 权威不被改写
- [x] JNI 基线 86 落盘、action-id 退役登记

## 九、附录：分工

| 分片 | 交付 | 验证 |
|---|---|---|
| T·C++ | 38 文件 +180/−2975、4 文件删、isSamePerson/nextInstanceId/两工具函数迁移、ActionId 4 退役+regen、JNI cpp 4 删、jni 基线 --update | cmake EXIT=0；ctest 1487/1483（零新增）；build-desktop-jni EXIT=0；198/1861；86/86 |
| A·Kotlin 生产+UI | 7 整文件删、Delegate/Facade/Ops 全链、调用点全清、广纳门徒、字段 5+1、恒空两处、renderRecruit 占位、AISect 连根删 | compileReleaseKotlin 4 轮 EXIT=0；8+6 模式 grep 代码零命中（白名单外） |
| c340·Kotlin 测试 | 42 项处置（10 删 / 13 清单改写 / 17 补漏 / 保留登记） | data+domain 实跑（domain 1649/0；data 17 红=主线程 v56 在途，落位后 0）；engine+feature 测试编译 EXIT=0 |
| 主线程 | 三裁决+追加、Room v56 四件套、55.json 恢复、V51To52 交集语义、detekt 7 违规、死引用注释、双 changelog、本报告 | 全门禁实测见 §3 |
