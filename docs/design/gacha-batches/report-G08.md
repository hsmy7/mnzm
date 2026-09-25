# report-G08 · 角色模板层批（模板实例化收敛 + 开局周明/5 万 + 兑换码改道碎片 + 立绘解析链根治）

> **本批派工真源**：[`TASKBOOK-G08.md`](TASKBOOK-G08.md)（含上位交接 16 条失真清单 §2、20 项决策 §3、13 片文件面 §4）。
> 开工时点 **2026-09-25，HEAD `fa853b541`（G16 收官回写提交）**，`git status --porcelain` = 2 行
> （仅 `docs/research/`×2，非本批、不提交）——满足上位 §8.D 的开工前置。
> 实施形态：**分片并行派工 + 主线程终树集成**（13 片，接口形参/构造链按「实现处枚举」定面，坑 10）。
> 本文件全部数字为**同轮命令输出原文**，非推测、非引用旧报告。

---

## 一、做了什么（分类表）

### ① 新增只读镜像与解析层（Kotlin 侧零写权）

| 落点 | 内容 |
|---|---|
| `core/domain/.../model/CharacterTemplate.kt`（新） | `@Immutable CharacterTemplate`（id/name/gender/spiritRoots/avatarKey/portraitKey 六项，**不新增数值字段**）+ `CharacterTemplateDb{ALL, byId, ids, STARTUP_TEMPLATE_ID="zhouming", STARTUP_REALM=9, STARTUP_REALM_LAYER=1, STARTER_STAR=1}`。`ALL` 是产物 `db.characterTemplates` 的**逐字段镜像**，等值由 `CharacterTemplateGuardTest` 锁定（含顺序） |
| `core/ui/.../PortraitResolver.kt`（新） | `resolvePortraitResId(name)`：空白→0 → `PortraitPool.getResourceId`（37 张通用像，O(1)）→ `SpriteResRegistry.resolve`（15 分类，含 CHARACTER）→ 0。**根治 report-G16 §七-1 的具名角色回落通用像**；10 处读点 / 7 个文件统一改走它（台账 `REGISTERED_CONSUMER_FILES` 由守卫逐文件断言，直引肖像池的白名单只留 resolver 本体 + `ResourcePreloader` 两处） |
| `core/engine/.../domain/gacha/GachaFragmentLedger.kt`（新） | 碎片账本 Kotlin 回退臂，与 C++ `addFragment` **逐字同式**（拒绝条件 / 累加 / 升星循环 / 满星不截断 / 星级账本稀疏五处一致） |
| `nativebridge/GachaNativeTx.kt`（新） | `tryExecuteNative(ActionIds.GACHA_FRAGMENT_GRANT_TX)` + `applyDirtyFromNative`，**零新增 `external fun`**；契约登记进 `docs/threading-contract.md` 表四 |
| `GameEngineLoadDataOps.kt` | 三臂（新档 / 读档重建 / 引导）统一经 `GameData.withStartupLedger()` 写开局灵石与星级账本 |

### ② 删除或转只读（「直造随机弟子」全链下线）

| 删除项 | 影响面 |
|---|---|
| `DiscipleService.recruitDisciple` | 换为 `instantiateTemplate(templateId)`：查模板 → 限持判定 → **绕开** `NameService`/`SpiritRootGenerator` → 工厂 → `allocateAndInsert`；顺带删掉对恒空 `recruitList` 的空转读取 |
| `RedeemCodeManager.generateDisciple` + `buildRedeemDisciple` + 6 个 helper | 兑换码不再直造弟子；`predefinedCodes` 置空（唯一条目 `8982` = 10 名弟子） |
| `MailAttachmentVariantsOps.distributeDiscipleAttachment`、`ItemCardData.isDisciple` + 其 `when` 分支 + `MailDialog` 两处生产者 | 邮件「弟子」附件在**读档期**由新规则 `MailDiscipleAttachmentCleanupRule`（`order=22`）摘除，UI 不再有可能是弟子的附件卡 |
| `RedeemRewardType.DISCIPLE` / `STARTER_PACK`、`DiscipleRewardConfig`（整文件）、`GameConfigData.starting` + `StartingSection`、`game_config.json` 的 `starting` 段 | 枚举删中间值牵动 `RedeemCodeTest` 的「取值集合」断言与 `ordinal` 上 wire 的地雷（已核：该枚举**不**按 ordinal 落盘，见项目记忆 [[project-proto-ordinal-trap]]） |
| ActionId **1436 / 1438** 的 dispatch case | catalog 保留条目 + `desc` 标注「已退役，编号禁复用」；`dispatch_guard_test.cpp` 退役集 +2 ⇒ **24 == 24 双向零差**（§二实测） |
| `RedeemCodeService` 类内碎片入账 4 函数 | 拆到 `service/RedeemCodeFragmentOps.kt`（同包 internal 扩展，`MailService`/`MailAttachmentVariantsOps` 既有先例），类函数数 22→18 回 detekt 阈值内 |

### ③ 命令进 C++ / 回执出 C++（AUTHORITATIVE）

| 落点 | 内容 |
|---|---|
| `gamecore/include/gamecore/system/gacha_fragment.h`（新） | `kFragmentsPerStar=100` / `kMaxStar=5` + `addFragment(GameData&, tid, count)` + `grantFragmentsTransaction`；碎片入账**唯一**写者 |
| `gamecore/src/dispatch_gacha.cpp`（新）+ `execute_dispatch.cpp` 1 行端口认领 | 独立 dispatch 文件（`execute_dispatch.cpp` 冻结口径）；port = `GACHA_FRAGMENT_GRANT_TX` = **1870** |
| `gamecore/include/gamecore/system/disciple_factory.h` | seed +`templateId` / +`portraitResOverride`；`portraitResOverride` 非空 ⇒ 用之并**跳过**肖像那 1 次 `nextInt` ⇒ 模板路径零 RNG 消费，空键路径逐字节不变 |
| `jni/GameCoreJni.cpp` | seed 解析补两 key（**不新增 JNI 导出**，计数仍 86/86） |
| `test/gacha_fragment_test.cpp`（新，9 例）+ `test/gacha_tests.cmake` + 两处 CMakeLists | 桌面与 Android 构建图都进（`scripts/build-desktop-jni.ps1:58` 与 `-linux.sh:38` 是**手写源清单**，同批补 `dispatch_gacha.cpp` + 预存遗漏的 `src/data_store.cpp`，否则桌面对拍全红） |

---

## 二、旧用例处置表（删 / 改断言 / 保留 + 理由）

| 用例文件（`+新增/−删除` 行） | 处置 | 理由 |
|---|---|---|
| `redeem_code_test.cpp` (0/−44) | **删 4 例** | `RollBySpiritRootCountRanges` / `GenerateVarianceRange` / `ResolveAgeAndLifespan` / `ResolveAgeMinGtMax`——断言对象是本次退役的 `REDEEM_RESOLVE_AGE_LIFESPAN`(1436) 与 `REDEEM_GENERATE_VARIANCE`(1438) 两个 op 的行为（`RollBySpiritRootCountRanges` 是同域随灵根生成的连带例），本体已删，保留即锁死复活 |
| `dispatch_guard_test.cpp` (+2/−0) | 改登记 | 退役集 +1436/+1438；分派不可达是退役的**预期形态** |
| `disciple_factory_test.cpp` (+148/−2) | **保留原断言值 + 新增** | `GoldenSequenceSeed42` 与 `GoldenSequenceSeed987654321Female` 两条金序列断言的期望值一字未改（本批只在文件尾部追加，两条用例现位于 `:80` / `:111`）；新增 2 例 `TemplatePortraitOverridePinsPortraitAndSkipsPoolRoll` + `EmptyPortraitOverrideKeepsGenericPoolSequence` |
| `DiscipleFactoryTest.kt` (+208/−11) | 改断言 + 新增 | 中文性别字面量 `"男"/"女"` → `"male"/"female"`（与 C++ 侧同域）；新增模板强制例 |
| `DiffDiscipleFactoryTest.kt` (+72/−9) | 改断言 + 新增 | seed JSON 补两 key；`NameResult` 改**命名实参**（原位置实参把 `fullName`/`surname` 传反，此前无任何用例比较二者故为潜伏态） |
| `RedeemCodeServiceTest.kt` (+288/−82) | 改断言 + 新增 | 弟子发放断言 → 碎片经门面入账断言；补「码未消耗则碎片不双增」的顺序用例 |
| `RedeemCodeManagerTest.kt` (+217/−0) | 保留 + 新增改道守卫 | 穷举私有码表需反射：`declaredMemberProperties` 取该 object 属性抛 `IllegalArgumentException`，改 `getDeclaredField`；字段消失**显式失败**不给静默跳过 |
| `RedeemCodeTest.kt` (+138/−78) | 改断言 | 枚举取值集合换血 + `templateId` 字段；`when` 穷举无 else 故必须同步 |
| `DiffRedeemCodeTest.kt` (+14/−3) | 改断言 | 3 个 REDEEM op 退役后的对拍面 |
| `BeastAndStartingConfigTest.kt` (0/−19)、`GameConfigConsistencyTest.kt` (0/−13) | **删用例** | 断言对象 `starting` 配置段本体已删 |
| `MailAttachmentToItemCardDataTest.kt` (0/−13) | **删用例** | `isDisciple` 分支本体已删 |
| `GameEngineCoordinationTest.kt` (+237/−35) | 改断言 | 开局名册（1 名具名弟子）/ 5 万灵石 / `gachaStarMap` 账本 |
| `GachaConfigGuardTest.kt` (+119/−32) | 改断言 + **自纠 1 处** | 池/碎片常量双向；期望值 `emptySet()` → `emptyList<String>()`——被测字段是 `List`，用 `EmptySet` 会**永久判红**且报不出差异 |
| `RngSourceGuardTest.kt` (+19/−1) | 改登记 | 消费点登记值随删除漂移（见 §七-2） |
| `InitialMineSizeGuardTest.kt` (+18/−8) | 改断言 | 引导文案文件名随 `starting` 段删除同步 |
| `GachaCharacterSpriteGuardTest.kt` (+59) | 保留 + 新增第 6 例 | 前五例锁素材链四环，新例锁**最后一公里**（用生产注册入口 `registerAllSprites()`，不自造假 map） |
| `PortraitResolverTest.kt`、`CharacterTemplateGuardTest.kt`、`DiscipleCreationPathGuardTest.kt`、`DiffGachaFragmentTest.kt`、`MailDiscipleAttachmentCleanupRuleTest.kt` | **新增** | 验收⑤⑥⑦的守卫面；判别力自证见 §五 |

---

## 三、门禁（终树同轮实测，判据为命令输出原文）

| 门 | 实测值 |
|---|---|
| 桌面 C++ 重编 | `[9/9] Linking CXX executable test\game-core-tests.exe`（exe mtime 22:32）——本批新增 `gacha_fragment.h` / `dispatch_gacha.cpp` 已进构建图 |
| 桌面 ctest | **1401 总 / 1398 过 / 3 败**，`Total Test time (real) = 58.64 sec`（G16 基线 1394 ⇒ **+7 = 新增碎片 9 例 + 工厂 2 例 − 兑换码 4 例**）。3 败逐条同名：`DiscipleFactory.GoldenSequenceSeed42`、`DiscipleFactory.GoldenSequenceSeed987654321Female`、`DeterminismProbeTest.DigestMatchesGoldenBaseline` ⇒ **零新增 B 类** |
| 🔴 同轮旁证（判别力） | 头文件常量注入 `100→99` 并还原后**重新链接**（`cmake --build` 触发 `[7/9]…[9/9]`）⇒ ctest 仍 1401/1398/3 且同名 ⇒ 还原是行为等价的，不是「没重编所以看不出」 |
| `SceneEquivalenceTest`（坑 9） | `100% tests passed, 0 tests failed out of 13`（本批不动图集） |
| `DeterminismProbe` | `actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`——**与 G15/G16 逐字符相同** ⇒ D-6「空模板路径零平移」成立；同批 `DigestIsStableAcrossRepeatedRuns` **Passed** ⇒ 确定性本身未坏，坏的只是金常量 |
| Kotlin 编译 | `compileReleaseKotlin` → 含在 §三·补 的组合门内（终树轮 `BUILD SUCCESSFUL in 26m 39s`） |
| JUnit 全量 | **7391 / 0 失败 / 0 错误 / 18 skip**（683 个 XML 逐模块汇总：app **1010** / domain **1571** / data **814** / engine **2903** / ui 146 / feature:game **947**）。G16 基线 7343 ⇒ **净 +48 全部逐模块对账闭合**：app +8（`PortraitResolverTest` 7 + `GachaCharacterSpriteGuardTest` 新第 6 例 1）、engine +37、data +8（清理规则 8 例）、domain −4（`starting` 段两文件删例）、feature:game −1 |
| detekt | 六模块 `detekt.xml` 的 `<error>` 计数**全部为 0**；`detekt-baseline.xml` / `lint-baseline.xml` **零改动**（只缩不增）。本批曾有的 2 条测试源码违规已实修（`RedeemCodeService` 22→18 函数：碎片入账域拆 `RedeemCodeFragmentOps.kt`；`PortraitResolverTest` 台账 `NestedBlockDepth` 压平为 `scanConsumerPoints`） |
| lint | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`、**0 errors**——与 G15/G16 同值 ⇒ 全预存；跑完 `atlas-rgba-manifest.json` 又被写脏一行 `generatedAt`（坑 2），按 `git diff --numstat = 1/1` 判为时间戳脏后 `git checkout --` 还原 |
| ActionId | 仓库根 `node scripts/gen-action-ids.mjs`：**199 动作 / maxId=1870**（G16 为 198/1861）；新增 1870 落在 G 批独占空段 = 有意递增。catalog 侧同轮 `199 条、唯一 199` |
| catalog↔guard 退役集 | catalog `已退役` 标注 **24** 条 == `dispatch_guard_test.cpp` `std::set retired` **24** 条；`catalog-only: (无)`、`guard-only: (无)` |
| 游戏数据 | `gen-game-data.mjs --check` → `校验通过… sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`——**与 G04/G15/G16 逐字符相同**（D-1 未动中性源） |
| JNI 计数 | `✓ JNI 面计数在基线内：total=86/86，双桥无扩散`（新事务复用 `tryExecuteNative`） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `libgamecorejni.so` **8542208 字节 / 20:57**（G16 时点为 8526336 ⇒ 本批确已重建，坑 11 的「验产物 mtime/体积」） |
| 图集/精灵 | `atlas-manifest.json`：`sprites 41` / `layoutHash 6a122ed22d66bee5` **不变**；`node scripts/build-atlas.mjs --codegen` → `显示尺寸保真校验通过：18 栋建筑 + 9 个装饰` + `SpriteRegistryData.kt / TextureAtlas.h 内容未变化，跳过生成`（零漂移）。⚠️ 该脚本以 `android/` 为工作目录、`gen-action-ids.mjs` 以仓库根为工作目录——在同一条复合命令里连跑两个会因 cwd 不同让前者抛 `ENOENT`（坑 4 的另一种形态） |
| Room | `GameDatabaseConfig.DATABASE_VERSION = 59`、`disciples` 90 列、无 schema JSON 改写——**本批零迁移**（`templateId` 列自 **v54** 在库，上位 §8.D-5「→ v60」是失真，见 TASKBOOK §2-1） |
| 规范门禁 | `✓ 规范分发架构门禁全部通过`；预存告警仍 **2 条**、规则③ 仍 **1 处**（坑 14） |

### 三·补 终轮组合门（一条命令、一次运行）

`./gradlew.bat compileReleaseKotlin testReleaseUnitTest detekt lintRelease --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=…libgamecorejni.so`

**该组合门在本批共跑两轮，上表三项数字取自第二轮（终树）**：
| 轮 | 结果 | 为什么还有第二轮 |
|---|---|---|
| 第一轮 | `BUILD SUCCESSFUL in 24m 28s`；JUnit 7391/0/0/18、六模块 detekt 0 error、lint 36 warnings + 3 filtered | 跑完后才发现 §五-3 的**成员/扩展同名死码**（改判据是数函数数与 detekt 报数对不上），于是删成员版 |
| 第二轮（终树） | **`BUILD SUCCESSFUL in 26m 39s`**；JUnit **7391 / 0 / 0 / 18 skip**（683 XML，逐模块 app 1010 / domain 1571 / data 814 / engine 2903 / ui 146 / feature:game 947，与第一轮**逐模块同值**）、六模块 `detekt.xml` 的 `<error>` 计数**全 0**、`Lint found 36 warnings (and 3 warnings filtered by baseline)` + 0 errors | 第二轮之后仅 `git checkout --` 还原 lint 写脏的 `atlas-rgba-manifest.json`（`--numstat 1/1` 时间戳脏），**无源码改动** ⇒ 门禁与终树一致 |

日志内 6 个 `testReleaseUnitTest` 任务与 6 个 `detekt` 任务全部出现且无 `FAILED` 行；三项数字与本报告**同一轮产出**，不是引用历史批。

---

## 四、RNG 与双臂口径（D-6 的红线）

1. **金黄不在本批重录**：HANDOVER §6.1 与 §3.3 判据——唯一重录窗口是 G10。派工期间有子代理建议「G08 顺手把 3 条红重录为绿」，**已否决**：那会把一个窗口污染成两个。三条红必须仍与 G16 逐条同名同值（已实测，见 §三）。
2. **模板路径零 RNG 消费**：`createDisciple` 的肖像掷点被包进 `if (seed.portraitResOverride.empty())`，模板臂不触 `rng`；空键臂连掷点次数都不变 ⇒ `DeterminismProbe` 摘要与金序列实际值全部平移为零。
3. **`getRandomPortrait` 实测只 1 次 `nextInt`**（侦察阶段一份报告称「两侧各消费」并建议按 2 次配平，已按实测否掉）。
4. **碎片入账两侧同式**：C++ `addFragment` 与 Kotlin `GachaFragmentLedger.grant` 在拒绝条件（空 tid / count≤0）、累加顺序、升星 `while` 循环、满星后进度不截断、`star==0` 不写星键五处逐字对齐；`GachaFacadeImpl` 的预闸判据与账本臂**同用 `isEmpty()`**——门面若改 `isBlank()` 会拒绝而账本臂接受同一入参并建键，双臂行为分叉（已在代码注释写明）。

---

## 五、守卫判别力自证（四轮，全部实跑，判红→还原→复跑判绿）

| # | 注入（模拟缺陷） | 判据落点 | 实测失败消息（原文摘） |
|---|---|---|---|
| E-1 | `CharacterTemplateDb` 删去 `suqing` 整行（藏一行） | `CharacterTemplateGuardTest.模板表与生成产物逐字段全等` | `条数：产物 6 条 ↔ 镜像 5 条 产物 id=suqing / 镜像 id=linxuetang id：产物="suqing" 镜像="li…"` |
| E-2 | C++ `gacha_fragment.h:37` `kFragmentsPerStar` 100→99（**只改头文件、不重编**） | 同类 `碎片升星常量三向一致` | `fragmentsPerStar（每升 1 星所需碎片）：配置/Kotlin/C++ = [100.0, 100.0, 99.0]` |
| E-4 | 从 `DiscipleCreationPathGuardTest.registeredSites` 撤掉 `insertTemplateDisciple` 一条（等价「新增入册口但不登记」） | `弟子入册与回写调用点集合不超过登记白名单` | `发现未登记的弟子入册/写回通道：core/engine \| …/DiscipleService.kt:186 \| fn=insertTemplateDisciple \| api=allocateAndInsert` |
| E-3 | `PortraitResolver.kt:47` 删掉 `SpriteResRegistry.resolve` 第二跳（**精确复现 report-G16 §七-1 的原缺陷形态**） | `PortraitResolverTest.角色立绘键…` + `GachaCharacterSpriteGuardTest.配置键运行时可解析…` | 前者 `角色键解析为 0 = CHARACTER 分类未注册或素材未入库（G08 验收⑤ / report-G16 §七 缺口…resolved=0）`；后者逐条列出 12 个键 `解析为 0` |

**红→绿对账**：E-1/E-2/E-4 三条红出现在同一次 `:core:engine` 定向运行（`BUILD FAILED`，`CharacterTemplateGuardTest failures="2"`、`DiscipleCreationPathGuardTest failures="1"`，**该类其余 5 例仍绿** ⇒ 无串扰）；三处还原后复跑 → `7/0/0` 与 `5/0/0`。E-3 运行于 `:app`（`PortraitResolverTest 7/1`、`GachaCharacterSpriteGuardTest 6/1`），还原后的复绿并入 §三·补 的终轮全量。

**三处自纠（不是「注入判红」，是实施/收尾期被实测推翻的写法）**：
1. `DiscipleCreationPathGuardTest` 首版两条判据**同时判红**——语义前置核对取的是 `Regex.groupValues[1]`（整段匹配）而非 `groupValues[2]`（捕获组）。教训：**不实跑就无法自证守卫**，静态读代码时该断言「看起来正确」。
2. `GachaConfigGuardTest` 期望值写 `emptySet()` 而 `dangling` 是 `List` ⇒ `assertEquals` 因**类型不同**永久判红，且消息里两侧打印看起来相同。已改 `emptyList<String>()`。
3. 🔴 **拆分后类内残留同名私有成员 = 静默死码**：把碎片入账 4 函数搬到 `RedeemCodeFragmentOps.kt` 后，`grantFragmentRewards` 的成员版漏删，而 Kotlin **成员优先于扩展**解析 ⇒ 调用点仍绑成员、扩展版成了死码，且编译/detekt 全都不报（函数数 19 仍在阈值 20 内，故连 `TooManyFunctions` 都不响）。发现方式不是读代码，是**用 `grep -cE "^    fun "` 数函数数与 detekt 报的 22 对不上**（22−4 应为 18，实测 19）。已删成员版，两处调用点改绑扩展，函数数 18。
   ⇒ 新增坑：**搬迁/拆分后必须按「名字逐个核对旧位置是否清零」**，判别力不体现在编译与静态扫描上。

---

## 六、本批新增 B 类清单（交 G10）

**零新增。** 因果链：本批 C++ 改动**不触碰任何 RNG 消费点**——`disciple_factory.h` 是把既有一次肖像掷点包进 `if`（空键路径的调用次数与顺序逐字节不变），`gacha_fragment.h`/`dispatch_gacha.cpp` 是纯账本运算（零 `rng` 引用，`RngSourceGuardTest` 侧同轮复核）。旁证三条：`DeterminismProbe actual=0xb4f3c6912207f597` 与 G16 逐字符相同、金序列两例的实际值仍为 G16 记录的常量、ctest 总数 +7 全部可归因到新增/删除用例数（9+2−4）。⇒ **G10 的 B 类集不因此批增长，仍是自 G04 起未变的那三条。**

---

## 七、G10 / 后续登记（诚实发现，本批未做）

| # | 项 | 证据 | 归属 |
|---|---|---|---|
| 1 | 🟡 **上位坑 13 的口径要修正**：`./gradlew.bat detekt` **确实覆盖 `src/test`**——本批两条测试源码违规（`PortraitResolverTest` 的 `NestedBlockDepth`、`RedeemCodeService` 的 `TooManyFunctions`）正是由 `app|core/engine/build/reports/detekt/detekt.xml` 报出的。G16 实测的「39 条只在 `:app:detektReleaseUnitTest` 暴露」仍然成立，但原因不是「测试源不在射程」，而是那 39 条**需要类型解析**（`detekt` 主任务是免类型解析档）。⇒ 门禁口径应改述为「免类型解析规则对 `src/test` 生效，类型相关规则须另跑 `detekt<Variant>UnitTest`」 | 本会话两轮 detekt.xml 的 `<error>` 行（修复前 1 条 / 修复后 0 条）；修复前 `detekt.xml:4` 指向 `app/src/test/…PortraitResolverTest.kt:139` | **G10**（与 G16-⑦ 合并处置，并回写 HANDOVER §2.2/§2.3） |
| 2 | 🟡 `RngSourceGuardTest` 的**登记值随删除漂移**：本批删掉的消费点使两组计数从 `14→1` / `19→12` 变化。守卫按 §9.5 要求同步更新了登记表，但**登记表本身不是盘点**——`docs/rng-source-inventory.md` 的 `SYSTEM(3)` 行自 G02 起就 stale（G15 已登记），本批不重跑盘点 | `git diff RngSourceGuardTest.kt +19/−1`；§6.1 红线「批内不重录」 | **G10**（与 G15-③ 同批：`grep -rn "RngPartition\."` 整行重跑） |
| 3 | 🟢 `DiscipleFacade.addDisciple` → `DiscipleLifecycleManager.addDisciple` 是**生产零调用**的转发口（仅测试触达）。本批不删（超出范围），已作为 `LEGACY_CRUD` 登记进构造路径守卫白名单并注明「禁止新增调用方」 | 守卫 `registeredSites` 第 2 条；`GameEngine.addDisciple` 调用方穷举 | **G10**（死代码 grep 清零） |
| 4 | 🟢 `scripts/split_engine/specs/redeem_code.json` 仍列 15 个函数（含 G04 已删 2 个 + 本批删的 4 个 + 本批拆出的碎片域）⇒ 拆分工具函数清单继续失真，`budget.py` 不校验存在性故不判红 | TASKBOOK §6-6 | **G10** |
| 5 | 🟢 `MIN_AGE` 死配置、`MailAttachment.extra` 零消费者、`ai_sect_recruit.h` C++ 死面（G05 留候本批）——**本批按口径保留 + 加注释**，未顺手清 | TASKBOOK §6-5 | **G10** |
| 6 | 🔴 **`predefinedCodes` 置空后，兑换码只剩「服务端下发」一条活路**：本地编译期码表零条目，`RedeemCodeManagerTest` 的守卫保证「不再直造弟子」，但**没有任何运营侧下发通道**（无 RemoteConfig 绑定、无后台接口对接）⇒ 玩家侧兑换码功能实际处于「只能用服务端已配码」状态。要不要留本地种子码属运营口径 | `RedeemCodeManager.kt:99-108`（D-10 置空）；`rules/commercialization.md` 的 RemoteConfig 未绑定现状 | **G12 / 待拍板** |
| 7 | 🔴 **档位错配未解（A-7 实测新发现）**：`portraitRes` 是弟子实例唯一的持久化显示字段，模板弟子置为 `portrait_<id>`（1024 档全身像）⇒ 40~56dp 的列表头像位按需解码全身像，而 **`avatar_<id>`（512 档头像）在本批之后仍无消费方**。修法要动 `DiscipleSlotComponents`/战报槽位的形参链（现只收 `portraitRes: String`），且取键规则本属寻访 UI 的显示尺寸口径 | TASKBOOK §6-9 | **G11** |
| 8 | 真机视觉验证（周明立绘/头像、外交头像）只有静态证据链，无视觉证据；`assembleRelease` 未跑（本批无新增素材，不涉及包体） | §五 E-3 只证明「解析链能给出非 0 资源 ID」，不证明「屏幕上真的画出来了」 | **pending-device** / 与历史批同口径 |

---

## 八、诚实状态声明

**已核实（本会话同轮实测，命令输出原文）**：ctest 1401/1398/3 且三条红为金序列常量断言、头文件注入-还原后重链接仍同值；`DeterminismProbe actual` 与 G15/G16 逐字符一致；`SceneEquivalence 13/13`；ActionId 199/maxId 1870 + catalog 199 唯一 + 退役集双向零差；game-data sha256 不变；JNI 86/86；图集 41/`6a122ed22d66bee5` 不变；`.so` 8542208 字节确认重建；Room v59 零迁移；规范门禁通过且告警数未增；四轮守卫判别力自证的失败消息原文（§五表格）；`:core:engine` 定向五类（`RedeemCodeServiceTest 6` / `GachaConfigGuardTest 5` / `CharacterTemplateGuardTest 7` / `DiscipleCreationPathGuardTest 5` / `RedeemCodeManagerTest 28`）与 `:app` 两类（`PortraitResolverTest 7` / `GachaCharacterSpriteGuardTest 6`）在还原后全绿；双臂账本逐分支比对。

**未做 / 未核实（如实列出）**：
1. **E-3（解析链第二跳注入）的「还原后复绿」并入 §三·补 的终轮全量**，未另做当场单独复跑——终轮覆盖这两个类（`PortraitResolverTest` / `GachaCharacterSpriteGuardTest` 均在 7391 内），判据仍出自同轮，但读者应知这一点；E-1/E-2/E-4 是当场还原当场复绿（`7/0/0`、`5/0/0`）。
2. **`GachaCharacterSpriteGuardTest` 第 6 例的另一半（素材链四环 ↔ 注册表漂移）未在本轮重做 codegen 级注入**：该链的判别力已在 G16 自证（report-G16 §五两处），本批只补了「最后一公里」。
3. **真机视觉验证**（周明立绘/头像、外交头像）只有静态证据链：§五 E-3 只证明「解析链能给出非 0 资源 ID」，不证明「屏幕上真的画出来了」；`assembleRelease` 未跑（本批无新增素材，不涉及包体）。
4. **兑换码改道的运行期真机路径未验**：本地双臂（`RedeemCodeServiceTest` 6 例）与 C++ 9 例覆盖逻辑，但「服务端下发含 fragment 奖励 → 入账 → 结果卡片」需后端配合，属 pending-device/联调面。
5. `Disciple` 改名链（G06 删）与模板名固定后的重名防护缺口按 TASKBOOK §6-3 登记，未在本批处理。

---

## 九、树指纹与提交身份

| 时点 | HEAD | `git status --porcelain` |
|---|---|---|
| 开工 | `fa853b541` | 2 行（仅 `docs/research/`×2，非本批、不提交） |
| 全部门禁跑完 + 四处注入还原 + 副产物还原 + 临时诊断删除后 | `fa853b541` | **91 行 = 72 修改 + 19 未跟踪**（其中 17 个为本批新文件、2 个为 `docs/research/`）；已暂存 0 行、非未跟踪残留 0 行 |
| 提交后 | `8b3c10578`（实施单次提交） | 89 行全部入库；残留 = 2 行 `docs/research/`（非本批、不提交） |

**提交规模实测**：`89 files changed, 5386 insertions(+), 1038 deletions(-)`
= 按 `git show --numstat` 实测分类：**`src/main` Kotlin 43 个 / `src/test` 测试文件 19 个 /
C++·cmake 17 个 / 脚本·数据·文档 10 个**（含两份 action-catalog、两份桌面 JNI 清单、`action_ids.h`+`ActionIds.kt`
两生成物按扩展名归 C++ 组、两份 changelog、`docs/threading-contract.md`、`game_config.json`、本任务书与本报告）。
CRLF 翻转按 `git diff --cached --numstat` 逐文件核对为零（无「双侧 ≈ 全文件行数」形态，最大单文件增量 465 行为新测试文件）。
`atlas-rgba-manifest.json` 两轮 lint 各写脏一次（`--numstat = 1/1` 时间戳脏）均已 `git checkout --` 还原；
`sprite-uid-map.json` / `sources-imported.json` 本批**无变化**（无新增 drawable）；
一次性诊断件 `android/scripts/tmp-g08-junit-tally.mjs`（JUnit 逐模块汇总脚本）**已删除，不入库**。

---

## 十、提交状态

- ✅ 实施单次提交 = **本文件的直接下游提交**（`feat(gacha): G08 角色模板层批……`），含模板层、C++ 碎片事务与端口、构造路径收敛、立绘解析链、守卫与旧用例处置、双 changelog、本任务书与本报告；**未推送远端**（本仓多会话共用一棵工作树，推送由用户指令决定）。
- ⏭ §九 的「提交后」行与「本批文件规模」需要引用实施提交自身的 hash/`--stat`，**无法与实施同批完成** → 与 `HANDOVER-m1-remaining-3.md` 的收官回写一起走紧随其后的 `docs(gacha)` 提交（沿用 G15/G16 先例：`325da9d5a`+`acf745398`、`4ba6c1bdd`+`fa853b541`）。
