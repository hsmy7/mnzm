# G09 独立验收报告（寻访抽卡核心批）

> **验收对象**：`15eef2b2d`（代码批）＋ `report-G09.md`（声称）＋ `HANDOVER-m1-remaining-3.md` §1/§2.2（回写值）。
> **验收方式**：本会话在**当前树**上独立复跑全部门禁与静态对账，**不采信报告自述**
> （G04 四处失实、G09 自身「首轮未跑新测试」两次教训）。
> **验收时点**：2026-09-26，分支 `feat/gacha-m0-m1`，HEAD 含 G09 代码批与本报告之前的文档回写。
> **验收边界**：只读复跑 ＋ 静态对账；**未做**真机验证、未构建 APK（`assembleRelease`）、未验证后端链路——
> 这三项与 G09 报告 §六「未完成」一致，本报告不重复主张。

---

## 1. 验收结论

| # | 结论 | 证据 |
|---|---|---|
| 1 | ✅ **门禁全部可复现**，与 `report-G09.md` §三/§3.2 逐值吻合（ctest / JUnit / detekt / lint / node 四门 / 退役集） | §3 |
| 2 | ✅ **三条金黄红与 G08 逐条同名且 `actual` 指纹逐字符不变** ⇒ 独立分区 `GACHA(12)` ＋ 星级乘区**未平移任何既有掷点** | §3 |
| 3 | ✅ **六项产品拍板全部落地**（口径 A／来源文案整改／格序豁免／独立分区／保底全随机／突破补偿不做） | §4 |
| 4 | ✅ **边界纪律成立**：Room 仍 v59（零迁移）、零新增 `external fun`（86/86）、零逆向通道、`db.gachaPools` 走注入而非第二份硬编码 | §5 |
| 5 | ✅ **报告自称的 5 条首轮缺陷修复全部在树**（A 注入端口／B 抽取序与历史序分离／C sealed 接口 mock／D detekt 真修／E 计数勘误） | §5.3 |
| 6 | ⚠️ **三处需如实标注**：① G09 报告 §六-6 的「map 就地改不发射」疑虑在**抽卡路径**已被实测否定（`pullLocally` 新建实例），但仍需 G11 接线前复验；② 报告 §三 与 §3.2 的 `cmake --build` 输出行号（`[4/4]` vs `[36/36]`）随增量状态变化，非事实差异；③ 「星级乘区是否进弟子属性面板」仍待产品拍板 | §6 |
| 7 | ✅ **G09 的验收判据①–⑩全部达成**，无一挂空 | §2 |

**总判**：G09「已收官」的声称**成立**——所有门禁可复现、六项拍板落地、边界纪律未破、报告自披露的缺陷修复经核验在位。
无失实项；3 条待办已分别移交 G11 / G10 / 产品。

---

## 2. 验收判据逐条（对 `TASKBOOK-G09.md` §1 的十条）

| 判据 | 本会话复核方式 | 结论 |
|---|---|---|
| ① native 事务 1871/1872 ＋ 回读；`NotReady` 消失；镜像只读 | ActionId 实测 **201/maxId 1872**；全仓 `NotReady` **零命中**；`DiffGachaPullTest` 6/6；`MirrorReadOnlyGuardTest` 在全量绿集内 | ✅ |
| ② 保底＝第 10 抽本身、不 roll 类别 | `gacha_tx.h:339-352`（`pity += 1` → `pity >= threshold` 分支只做角色抽取并 `pity = 0`）；`ctest -R Gacha` 43/43 | ✅ |
| ③ 碎片入账单点 | 全仓生产侧 `gachaFragmentCounts[...] =` / `gachaStarMap[...] =` 仅 **`gacha_fragment.h:86,89`** 两处（`star_zone.h`/`StarZone.kt` 为只读反查） | ✅ |
| ④ 解锁单点且计数一次 | 报告 §一④/§五 明写复用 `DiscipleService.instantiateTemplate` 与 `insertTemplateDisciple` 单点（D-6 双计规避）；`DiscipleCreationPathGuardTest` 绿 | ✅ |
| ⑤ 三类物品统一入口 ＋ 溢出转邮件 ＋ 品阶 ≤4 | 报告 §五-3 披露「C++ 建不了邮件 ⇒ 草稿经信封回传 Kotlin 投递」并已接；`checkPool` 校验 `maxRarity` 与权重 | ✅ |
| ⑤·补 来源文案整改 | `SOURCE_DISPLAY_NAMES` 实测 **22 条**（20 保留 + 新增 `gacha_pull→仙缘寻访` + `unknown` 兜底）；**4 条死条目已删**；标题模板已改；`OverflowMailSenderTest` 断言同步（含新增「弟子遗物归还」例） | ✅ |
| ⑥ 历史环 50、新在前、`isPity` | `gacha_tx.h` 写环；`GachaService.kt:160 (drawn.asReversed() + data.gachaHistory).take(50)` | ✅ |
| ⑦ 星级乘区双端同式（口径 A） | `star_zone.h:55-57` `extra = star > 1 ? star-1 : 0`（**star=0 不退化成 ×0.92**，头注释 `:10-12` 明写该判据唯一点）；Kotlin `StarZone.kt:28-33` 同式；`star_zone_test` **10/10** | ✅ |
| ⑧ 零新增 A 类；三条 B 类同名且指纹不变 | ctest 1437/3（837/838/1071 同名）；`actual=0xb4f3c6912207f597` 与 G08/G15/G16 逐字符相同 | ✅ |
| ⑨ 三向常量守卫 ＋ 运行期 key 域守卫 | `GachaConfigGuardTest` 7/7、`GachaPullGuardTest` 9/9、`CharacterTemplateGuardTest` 在全量绿集内；`starBattlePctPerStar` 三向臂见 `CharacterTemplateGuardTest` | ✅ |
| ⑩ 双 changelog ＋ 报告 ＋ 单次提交 | `CHANGELOG.md` 有 G09 段；`changelog_entries.json` 可解析且「寻访」15 处；代码＋测试＋双 changelog＋报告为 `15eef2b2d` 单次提交 | ✅ |

---

## 3. 门禁对账表（声称 vs 本会话实测）

| 门 | `report-G09.md` 声称 | 本会话实测 | 判定 |
|---|---|---|---|
| 桌面 C++ 构建 | `[4/4] Linking`（第二轮增量）/ `[36/36]`（同轮另一处） | `cmake --build .` → `[34/36]…[36/36] Linking CXX executable test\game-core-tests.exe`，48 条预存 warning | ✅ 可构建 |
| 桌面 `ctest` | **1437 / 3 failed**，`Total 55.07 sec`；失败 837/838/1071 | **1437 注册 / 3 failed**，`Total Test time (real) = 54.79 sec`；失败 **837 / 838 / 1071**（逐条同名） | ✅ 逐值一致 |
| 三条红的定性与指纹 | 与 G08/G16/G15 同名；`actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921` | `ctest -R DigestMatchesGoldenBaseline --output-on-failure` → 同 `actual`/`golden`；`DigestIsStableAcrossRepeatedRuns` **Passed** | ✅ |
| `ctest -R "GachaPull\|DataStore"` | 32/32 passed | **32/32** | ✅ |
| `ctest -R "StarZone\|Gacha"` | 43/43 passed | **43/43** | ✅ |
| `ctest -R SceneEquivalence` | 13/13 | **13/13** | ✅ |
| 用例增量归因 | 1401 → 1437，增量 **36** ＝ `gacha_pull_test` 19 ＋ `star_zone_test` 10 ＋ `rng_test` GACHA 5 ＋ `data_store_test` 卡池 2 | `gacha_pull_test.cpp` **19** ✓、`star_zone_test.cpp` **10** ✓（另两项在 32/32 与 43/43 子集内） | ✅ |
| 桌面 JNI | `.so` 8656384 → **9015808** 字节，mtime 13:41:23 | **由当前树重建**：`9015808` 字节、mtime **14:35:40**（体积与 G09 收官轮一致 ⇒ C++ 无后续漂移） | ✅ |
| ActionId | 201 动作 / maxId 1872，regen 零漂移二次自证 | `gen-action-ids: 201 actions (maxId=1872)`；两份产物 SHA256 **regen 前后不变**；`git diff --exit-code` **EXIT=0** | ✅ |
| `game-data.json` | sha256 `035066cb…94ef` 未变 | 同值 | ✅ |
| JNI 面计数 | 86/86 | `total=86/86，双桥无扩散` | ✅ |
| 规范分发门禁 | 规则①–⑤ 全 ✓，EXIT=0 | `✓ 规范分发架构门禁全部通过`（本轮 0 告警：根 `AGENTS.md` 已于本批之后被并发会话压回预算） | ✅ |
| catalog ↔ guard 退役集 | 24 == 24 双向零差集，24 个 id 名称两侧逐条相同 | catalog **24** / guard **24**，双向逐名差集**均为空** | ✅ |
| Kotlin 组合门 | `BUILD SUCCESSFUL in 23m17s`，零 FAILED 任务 | `BUILD SUCCESSFUL in **23m 52s**`，`339 actionable tasks: 339 executed` | ✅ |
| 全量 JUnit | **7419 / 0 fail / 0 error / 18 skip**（app 1011/2skip、domain 1571、data 814/15skip、engine 2930/1skip、ui 146、feature 947） | **7419 / 0 / 0 / 18**，逐模块**逐值一致**；XML 685 个（G08 683 ＋ 两个新测试类） | ✅ |
| G09 新测试类 | `DiffGachaPullTest` 6/6、`GachaPullGuardTest` 9/9、`GachaConfigGuardTest` 7/7 | **6/6**、**9/9**、**7/7**（另 `DiffGachaFragmentTest` 6/6、`GachaCharacterSpriteGuardTest` 6/6） | ✅ |
| detekt | 六模块 XML `Error`/`Warning` 计数**全 0** | 六模块 `detekt.xml`：`error=0 warning=0` | ✅ |
| lint | `Lint found 36 warnings (and 3 filtered by baseline)`、0 error | 组合门输出原文同句；`BUILD SUCCESSFUL`（lint 有 error 会中断）⇒ **0 error** | ✅ |
| Room | 仍 v59（零迁移） | `DATABASE_VERSION = 59`；schemas 目录 50 个 JSON、**无 `60.json`** | ✅ |

---

## 4. 六项产品拍板落地核验（`TASKBOOK-G09.md` §7.1）

| 拍板 | 要求 | 实测证据 | 判定 |
|---|---|---|---|
| **P-1 星级乘区口径 A** | `1★` 基线 ×1.00，`×(1 + (star-1)×pct)`，5★ 战斗 +32% / 修炼 +20% | `star_zone.h:34-38,51-59`；`StarZone.kt:28-33`；`star_zone_test` 10/10 | ✅ |
| **P-2 来源文案整改** | ASCII 内部键 ＋ 中文玩家出处；**既有 25 项一并改**；邮件模板去「奖励」；死条目清 | `SOURCE_DISPLAY_NAMES` 22 条：`forge→锻造台`、`alchemy→丹房`、`storage_bag→储物袋开启`、`sect_level→宗门升级`、`quest→任务奖励`、`confiscate→没收弟子物品`、`disciple_death→弟子遗物归还`、`gacha_pull→仙缘寻访`；`cave`/`disciple_reward`/`disciple_unequip`/`disciple_expel` **0 命中**；模板 `【仓库已满】来自「X」的物品已转入邮件`；`OverflowMailTest` 同步；`OverflowMail.kt:9` KDoc 举例改 `spirit_field`；`BattleLogDialogs.kt:284,313` 对齐 | ✅ |
| **P-3 历史格序豁免** | 不加 `@ProtoNumber(9)` | `GachaHistoryEntry.kt` 仍 8 字段；零 proto 变更 | ✅ |
| **P-4 独立随机分区** | `kGacha = 12` 双侧 ＋ 上界上移 ＋ `seed+12` ＋ 守卫登记 | `rng_manager.h:70 kGacha = 12`、`:78 kMaxPartitionId = kGacha`、`:102 fromSeed(seed + 12)`；`RngPartition.kt:118 GACHA(12)`；`RngSourceGuardTest:144` 登记集 `0..12`、`:288` GACHA 断言 | ✅ |
| **P-5 保底全随机** | 六选一，`pickMode == "random"` | `checkPool` 对非 `random` 返回 `PoolMalformed`；保底分支从 `characterPool(pool)` 均匀取 | ✅ |
| **P-6 突破补偿不做** | 不实现 | 全仓无新 `breakthroughCompBonus` 消费点（常量仍在配置，未接线） | ✅ |

---

## 5. 静态对账（新增面与边界）

### 5.1 新增面在位
| 项 | 证据 |
|---|---|
| C++ 卡池数据面 | `data_inject.h:46-47` 两个 `AppliedCounts` 字段、`:123-131` 两段注入（含「段在而空 ⇒ `return false`」判据）；`gacha_pool_db.h` 144 行且头注释明写**不做内联兜底**的理由 |
| C++ 抽卡本体 | `gacha_tx.h` 448 行：消费序写在头注释（角色抽 2 次、物品抽 3 次 `nextInt`）、`checkPool` 九段校验、`pity` 分支、`addFragment` 单点调用 |
| Kotlin 面 | 新增 `StarZone.kt`(51)、`GachaPoolConfig.kt`(148)、`GachaPullLedger.kt`(261)、`GachaRosterSync.kt`(31)；`GachaService.kt`(227)、`GachaFacadeImpl.kt`(168)、`GachaNativeTx.kt`(126) 扩展 |
| 测试面 | `DiffGachaPullTest.kt`(723) 6 例、`GachaPullGuardTest.kt`(487) 9 例、`gacha_pull_test.cpp` 19 例、`star_zone_test.cpp` 10 例 |
| 登记面 | `docs/ui-read-surface.md:47-50` 四字段已登记（写者归属/稀疏口径/协议面行号）；`CHANGELOG.md` G09 段 ＋ `changelog_entries.json`（JSON 可解析、「寻访」15 处） |

### 5.2 边界纪律
| 纪律 | 实测 | 判定 |
|---|---|---|
| Room 零迁移 | v59、无 `60.json` | ✅ |
| 零新增 `external fun` | 86/86（新事务复用既有通道；桌面测试桥端口只在 `src/test` 面，不进生产桥计数） | ✅ |
| 单一真源（概率不双抄） | `GachaPoolConfig` 解析 `assets/data/game-data.json` 的 `db.gachaPools`（与 C++ 注入同一份文件），**不在 Kotlin 内置概率** | ✅ |
| 零逆向通道 | `MirrorReadOnlyGuardTest` 在全量绿集内；改动面无 `applyDirtyToNative` 族符号 | ✅ |
| 金币扣费的键分裂规避 | 新增 `SpiritStoneReason.Gacha` / `SpiritStoneSource.Gacha` 两个 `object`（报告 §五-1 披露该连带面） | ✅ |

### 5.3 报告自披露的 5 条首轮缺陷 —— 逐条核验修复在树
| 条 | 修复主张 | 实测 |
|---|---|---|
| A 注入通道缺失 | 新增桌面测试桥端口 ＋ 两脚本补 `data_store.cpp` | `GameCoreJni.cpp:342-344` `DiffRngBridge_nativeCoreSetGameData → inject::injectFromJson`；`DiffRngBridge.kt:69` 声明；`.ps1` 与 `.sh` 各 1 处 `data_store.cpp` | ✅ |
| B 抽取序/历史序混用 | `GachaService.kt:134-167` 分离 `drawn` 与历史环 | `:134 drawn`、`:153 drawn += step.row`、`:160 (drawn.asReversed() + …).take(50)`、`:162 rows = drawn`、`:174` KDoc 钉死两口径 | ✅ |
| C sealed 接口 mock 报错 | 三个 `add*` 显式 `doAnswer { DomainResult.Success(…) }` | 全量 JUnit **0 fail**（若未修该 2 例必红） | ✅ |
| D detekt 六条 | `poolError` 三段化 ＋ `syncGachaUnlockedRoster` 下沉新文件 ＋ 测试面两条改写 | `GachaRosterSync.kt` 存在；`GachaPullLedger.poolError` 现为 `when`；detekt 六模块 **0** | ✅ |
| E 计数勘误 | 1437 / `star_zone_test` 10 例 | 实测 1437、10 例 ✓ | ✅ |

---

## 6. 差异、待办与未验面（诚实清单）

### 6.1 与报告的字面差异（均非失实）
1. `cmake --build` 输出行：报告写 `[4/4]`（增量）与 `[36/36]`（另一处），本会话为 `[34/36]…[36/36]`——**增量状态不同所致**，不是事实分歧。
2. 组合门耗时 23m17s（报告）vs **23m52s**（本会话）——机器负载差异。
3. JUnit XML 个数 685（本会话）；报告未声称该数；G08 为 683，差 2 ＝ 两个新测试类。

### 6.2 需 G11 承接（已写进 [`TASKBOOK-G11.md`](TASKBOOK-G11.md)）
1. 🔴 **G09 报告 §六-6 的「`grantFragmentsLocally` 就地改 map ⇒ UI 订阅可能不发射」**：本会话核实**抽卡路径**（`pullLocally`）已新建实例（`:156-157`）⇒ 该疑虑对抽卡不成立；但 `grantFragmentsLocally`（碎片发放路径）仍是就地改。**G11 接线第一步必须复验**（TASKBOOK-G11 D-7）。
2. 🔴 **色表地雷**：`UnifiedItemCard` 的品阶色**只进背景且硬编码旧表**（`ItemCard.kt:79,189`）＋ 灵根徽章最广源 `Disciple.spiritRoot.countColor`（`Disciple.kt:299-306`）与 Q31 **完全相反**（单红/双橙 vs 单金/双红）⇒ G11 必须自建奖励框 ＋ 把该 `countColor` 委托到 Q31（TASKBOOK-G11 §2.2 / D-5）。
3. 🟠 `GachaDelegate` 仍零实例化（G09 故意不接 UI）；接线要同改 `GameVmDelegateServices` 构造 ＋ **4 个测试构造点**。

### 6.3 需 G10 承接
1. 🔴 **唯一重录窗口**：三条 B 类红本批零重录、`actual` 不变 ⇒ G10 重录时须注意「G09 只在新输入上生效」这一实证，避免误判。
2. 🔴 G09 报告 §六-2/§6.2 追加的两条必核面：`rngStates` 键集含 12 号后的条数断言、`RngSourceGuardTest` 抽卡分区使用计数。
3. 🟠 `docs/rng-source-inventory.md` §2/§3 整行盘点（本批只加 §9 的 GACHA 登记行）。
4. 🟠 `GameNotification.RecruitFailed` 生产者存废；`OverflowMailSender` 提示条「部分奖励已转入邮件」措辞；测试面 `androidRoot()` 三份同形实现收敛。

### 6.4 需产品拍板 / 文档债（G12）
1. 🟠 **星级乘区是否进弟子详情属性面板**（G09 只钉战斗+修炼两链，`CachedPower` 指纹公式刻意未改）。
2. 🟠 `m0-economic-whitepaper.md:143-144` 正文仍写「采纳口径 B」，与已拍板 A 矛盾。
3. 🟠 `docs/character-gacha-redesign-2026-09-23.md:113/444` 与 `docs/design/character-gacha-implementation.md:85` 仍写 `withTrackingSource("仙缘寻访")` 中文字面量，与 D-9（ASCII 内部键）冲突。
4. 🟠 旧物品色表 4 张与 Q31 不一致（仓储/奖励弹窗色板对齐债）。

### 6.5 明确未验（与 G09 报告 §六一致，本报告不重复主张为已完成）
- **真机**：未做（G11 的 M1 判据含真机通，届时一并验证 G09 的抽卡链路）。
- **APK 包体**：未构建。
- **后端链路**：兑换码「服务端下发碎片」未验证。

---

## 7. 交下一批的输入

1. **[`TASKBOOK-G11.md`](TASKBOOK-G11.md)**（本会话同批产出）：G11 派出级任务书，含 §0 的 G11→G10 总纲、
   §2 上位失真 6 条 ＋ 色表/接线地雷、D-1…D-12 决策、8 片切片、门禁与真机清单。
2. **G11 的三条「开工第一步」**（本验收过程中识别）：先验 D-7（订阅发射）→ 再验 D-2（结果层关闭语义的能力边界）→ 再动 UI 文件。
3. **G10 的重录前提已知**：G09 之后三条 B 类红与 `actual` 指纹**一字未变**，重录范围仍以 G02–G08 的删除面为主。
