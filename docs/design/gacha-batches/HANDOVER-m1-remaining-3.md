# M1 剩余批次交接（第三份）

> **本文件用途**：G03 收官时的完整交接 —— ① 已收官 4 批的实测基线；② 门禁数字与本轮新增的实操坑；③ 修订后的作业规程（分片粒度 / A·B 类判据）；④ 剩余批次落点指针与硬阻塞；⑤ G10 累积登记与**条目数复核差异披露**。
> **与前几份的分工**（四份各有职责，互不重复）：
> - [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) —— **批次清单 / 顺序理由 / 产品口径表 / 串行约束 / 待拍板项**的权威（§4 口径表现含新增口径 15、§5#1 已关闭）。
> - [`HANDOVER-m1-remaining-2.md`](HANDOVER-m1-remaining-2.md) —— G03 失败复跑任务书与三次实操规程；**其 §2 门禁基线已被本文件取代**（G06 数值已过时）。
> - [`TASKBOOK-G04.md`](TASKBOOK-G04.md) —— **G04 派工细则的唯一真源**（切片表、每片文件面、grep 口径纠正、前置扫描）。本文件不重复，只给指针。
> - **本文件** —— 收官基线 + 修订后的作业规程 + 剩余指针。
> **更新时点**：G03 收官（`cc66d7918`）+ G04 任务书入库（`77839b867`）后。

---

## 1. 当前状态一页速览

| 项 | 值 |
|---|---|
| 分支 | `feat/gacha-m0-m1` |
| HEAD | `77839b867`（G04 任务书），其下 `cc66d7918`（**G03 收官**） |
| 工作树 | **干净**（非未跟踪残留 = 0） |
| 未跟踪 | 仅 `docs/research/`×2（与本批无关，**不提交**）。🔴 `模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）**已写入 `.gitignore`** —— 2026-09-24 拍板：作为项目指定素材/音频源目录，**只登记不入库**；位置与边界见 `rules/media-source-assets.md`。**旧表述「未跟踪 4 组永不提交」自本文件起作废** |
| 已完成 | **4 / 9 批**（G02、G05、G06、**G03**） |
| 下一批 | **G04**（任务书 = [`TASKBOOK-G04.md`](TASKBOOK-G04.md)，可直接派工） |
| 远端 | **未推送**（本仓多会话共用一棵工作树，推送由用户指令决定） |

### 1.1 已收官批次（门禁值以各批报告实测为准）

| 批次 | 提交 | 报告 | 规模 | ctest | JUnit | 其他要点 |
|---|---|---|---|---|---|---|
| G02 | `5dbaac1e3` | [report-G02.md](report-G02.md) | 443 文件 +10775/−26845 | 1532/1536→4 B 类 | 7893/0 | Room v54→v55 |
| G05 | `8e593a71b` | [report-G05.md](report-G05.md) | 143 文件 +5624/−8401 | 1487/1483/4 | 7720/0 | JNI 89→86；v55→v56 |
| G06 | `e1a69d8e9` | [report-G06.md](report-G06.md) | 42 文件 +179/−1309 | 1483/1479/4 | 7709/0 | ActionId 1590/1740 退役 |
| **G03** | `cc66d7918` | [report-G03.md](report-G03.md) | **171 文件 +6084/−5186** | **1470/1467/3** | **7662/0** | ActionId 1592/1750 退役；**Room v56→v57（3 表 11 列）**；lintRelease 绿 |

四批均为：全门禁实测绿 + 双 changelog + `report-Gxx.md` + **单次提交**。

### 1.2 剩余顺序（**2026-09-24 用户拍板后重排**）

**G04 → G15（师徒系统下线）→ G16（角色素材批）→ G08 → G09 → G11 → G10**

- **G04 与 G15 都是删列批**，与已合入的 G02/G03 共享 `models.h` / `disciple_store.h` / `column_dirty.h` / `DiscipleTables*.kt` / `Disciple.kt` → **必须串行**（每片开工前 `git status` 必须干净）。G15 的任务书**内联在本文件 §10**。
- **G16 素材批**按用户指令插在 G04 之后、G09 之前（与删除批零文件冲突，可并行插空），用于解除 G11 硬阻塞。
- G08 → G09 → G11 是依赖链（G09 解锁依赖 G08 模板读取层；G11 依赖 G09 + G16）。
- G10 最后（唯一一次 RNG 重录窗口）。
- **M1 完成判据**：G10 全绿 + G11 最简 UI 真机通。

### 1.3 本轮新增的三条产品拍板（2026-09-24）

| # | 拍板 | 影响的批次 |
|---|---|---|
| P-1 | 🔴 **师徒系统整体下线**（不只是删「关系」对话框 UI，而是**连根拆掉整个师徒玩法**） | **新增 G15**；并使 G03 报告与本文件原「师徒反向守卫」全部**作废**（见 §10.1） |
| P-2 | 🔴 **血炼池建筑连建筑一起拆掉**（不采用 G02「建筑保留、只断功能」先例） | **G04**（`TASKBOOK-G04.md` §1 范围扩大，见 §5） |
| P-3 | **角色素材批单独开一批**，插在 G04 之后、G09 之前 | **新增 G16**；解除 G11 硬阻塞 |

> 另：原「G04 待拍板项②——`comprehensionAdd` 是否死字段」经实测**自动消解**：配方侧有 **36 条非零**（智悟丹/悟德丹/灵悟丹/明悟丹…），`pill_system.h:219` 真实写入悟性 → **该字段是活功能，随悟性一并保留，不删**。G02 下架的是另一组 `pillType=comprehension` 丹方。

---

## 2. 门禁基线与运行方式（**G03 收官实测，取代 HANDOVER-2 §2**）

### 2.1 环境前置

```powershell
# C++ 桌面构建：PATH 前置三段（缺一不可，缺 llvm-mingw bin 会 STATUS_DLL_NOT_FOUND）
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH
# 桌面构建目录：android/app/src/main/cpp/gamecore/build/desktop-test （不是同级 build/，那是旧套件）
```

### 2.2 门禁表

| 门 | 命令 | G03 收官实测值 |
|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（desktop-test 目录） | EXIT=0 |
| 桌面 ctest | `ctest`（同目录） | **1470 总 / 1467 过 / 3 败**；3 败 = B 类（§6.2） |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | EXIT=0 |
| 测试源编译 | 五模块 `compileReleaseUnitTestKotlin --max-workers=1 --continue` | **0 错误** |
| detekt | 六模块 | EXIT=0（baseline 只缩不增） |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<绝对 .so>"` | **7662 / 0 / 0 / 17 skip**（app 1011 / domain 1646 / data 808 / engine 3085 / ui 146 / feature:game 966） |
| lint | `./gradlew.bat lintRelease` | BUILD SUCCESSFUL（约 17m；六模块 lintAnalyze + 报告） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `android/core/engine/build/desktop-jni/libgamecorejni.so` | EXIT=0；**改任何 C++ 后必须重建**再跑 JUnit |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86** |
| ActionId | `node scripts/gen-action-ids.mjs` | **198 动作 / maxId=1861**（无 `--check`；零漂移自证 = `git diff --exit-code`） |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `915563485e9fd41d14c77d76b2f25ff711c535e82f384d34a4faca5b10be84d2` |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | EXIT=0（改 `docs/`、`rules/`、任何 `AGENTS.md` 后必跑；预存告警 2 条属正常） |
| Room | `DATABASE_VERSION` = **57**；`schemas/…/57.json` 已入库；`disciples` **95 列**（v56 为 102） | 禁 `DROP COLUMN`；历史 schema JSON 只增不改；G04 → **v58** |

### 2.3 八条实操坑（前三条承自 HANDOVER-2 并复验，后五条本轮新增）

1. **KSP 会就地改写历史 schema JSON**：bump 版本后若 KSP 把 `56.json` 等历史快照改小，立即 `git checkout -- <该 json>`；只允许新增当前版本 JSON。（G03 实测：仅 `57.json` 新增，历史零改写。）
2. **构建副产物必须还原**：`sprite-uid-map.json`、`atlas-rgba-manifest.json` 每次构建都改时间戳 → 提交前 `git checkout --`。⚠️ **`lintRelease` 也会改**（本轮实测：提交前它又变脏了一次）。
3. **文本编辑用文件编辑工具，不要用脚本拼接**：JSON 追加后必须 `node -e "JSON.parse(...)"` 校验。
4. 🔴 **Bash 工具的 `cd` 绝对路径不可靠**：`cd "C:/..." && ./gradlew.bat` 会 **EXIT=127**（命令找不到）。正确做法是**不写 `cd`，用 Bash 的 `dir_path` 参数**定位到 `C:\Mnzm\XianxiaSectNative\android`。子代理反复踩到过。
5. 🔴 **`grep -c` 无命中返回 exit 1**，会把"实际成功"的后台命令包装成 failed（本轮 `lintRelease` BUILD SUCCESSFUL 却被通知为 exit 1）。⇒ **判定后台命令成败必须读它自己的日志**（`BUILD SUCCESSFUL|FAILED` 行），不能信复合命令的退出码。
6. **CRLF 警告 ≠ 行尾污染**：Git 报 "in the working copy of X, LF will be replaced by CRLF" 时，`git show :file | grep -c $'\r'` 的输出不可信（autocrlf 会在输出路径上做转换）。**判据是 `git diff --cached --numstat`**：若某文件出现"增删双侧 ≈ 全文件行数"才是真翻转；G03 实测 171 文件无此类。
7. **`--tests` 过滤作用于该次调用所有 Test 任务**；多模块一起跑须按模块分别调用，否则某模块无匹配用例直接判红。
8. **同机只跑一路 Gradle 组合门**：并行会互锁 `classes.jar` 并制造假红。子代理在途时主线程优先只跑 `compile*`，全量测试留终树复跑。

---

## 3. 单批作业规程（G03 **修订版**，替代 HANDOVER-2 §3 的分片粒度）

### 3.1 五步循环（结构不变）

1. **读侦察** → 2. **多代理并行派工** → 3. **主线程集成 + 独立 grep 终态核验** → 4. **双 changelog + report** → 5. **单次提交**。

### 3.2 🔴 修订一：分片粒度必须按「文件面」且 **≤10 文件/片**

**为什么改**：G03 的 5 个分片（A1 C++ 结算 / T1 / T2 / A3 / c340）**全部撞上子代理 150 轮硬上限中断**，改动虽落盘但**无收尾报告**，全部由主线程接管 + 二次细分片（S1/S2/S3，其中 S3 亦撞顶）才收口。单个子代理的实际产能 ≈ **8–12 个需要 grep 复核 + Edit 的源码文件**（150 轮 ÷ 每文件约 12–15 轮）。

⇒ 派工前先按文件清单数片数；测试面（G03 51 文件、G04 预计更多）**至少切 3–4 片**。
⇒ **代理中断不等于失败**：改动在树上，主线程 `git status` 固化清单 → 按剩余文件重切小片续跑即可。**但绝不在"混入半成品且编译不过"的树上让新代理继续叠加**（那是上一会话 G03 首次失败要全量回退的成因）。

### 3.3 🔴 修订二：A / B 类失败判据（血的教训，详见 report-G03 §二·补）

中间批次的红**不许按"失败用例所属测试家族"归类**，要读异常类型与抛出点：

| 观察 | 判定 |
|---|---|
| C++ GTest 断言固定摘要/固定序列常量不符 | **B 类**（RNG 平移）→ 逐条登记，**不重录** |
| Kotlin `Diff*` 抛 `JsonDecodingException` / `unknown key` / 缺符号 / 类型不符 | **A 类协议漂移，必修** |
| `Diff*` 数值漂移（极少） | 先查是否单侧漏改；两侧同步删除时 `Diff*` **应当继续绿**（它比的是 C++ == Kotlin legacy，不是黄金值） |

⇒ 推论（供 G10 排期）：**G10 重录工作量集中在 C++ GTest 金序列 + `DeterminismProbe` 摘要**，Kotlin Diff 家族预计无需重录（G10 终树实测后方可定论）。

### 3.4 🔴 修订三：派工前必做两项「侦察缺口回查」

G03 实测两处「侦察落点表按维度切分导致漏项」，代价分别是编译期与**运行期**：

1. **交叉归属回查**：recon 的 §9.x / 「归属结论」段落里被判给本批的项，**是否真的进了本批落点表**。
   （实例：§9.7 判归 G03 的 `sect_defense_battle.h` 哀悼块未进 §G03-2 → 五份任务书无人认领。）
2. **非弟子表的三端环回查**：凡删**非弟子列**的持久化字段，逐字段确认下列每一环都在任务书里有一行 ——
   C++ `models.h` 声明 + `json_codec.cpp` 的 `GC_TO`/`GC_FROM`（`to_json`/`from_json`）+ Room 列 + `@ProtoNumber` reserved + **`lock_beast_tx.h` SETTINGS_PATCH 三处清单（apply / isSettingUnchanged / isKnownSettingField）+ 计数注释**。
   （实例：`GameData.daoCompanion*` 在 §G03-1 整表无行 → 首轮 JUnit 16 条红才暴露。）

### 3.5 主线程集成清单（沿用 HANDOVER-2 §3.3，加两项）

- [ ] 独立 grep 终态（删除模式全 0 + **保留清单命中贴证**，双向都要）
- [ ] **不信子代理自证**：分片报告说"改完"要自己 grep / 编译复核；报告缺失（撞顶）时按文件面重切
- [ ] 全门禁复跑（§2.2 全表，含**重建 JNI 库后**跑全量 JUnit + lint）
- [ ] Room 四件套 + 迁移测试（含既有迁移链期望集用「注册删列集 ∩ 起点既有列」交集语义）
- [ ] ActionId 退役：保号 + desc【已退役，编号禁复用】+ dispatch case 删 + `dispatch_guard_test` retired 登记 + regen **198/1861**
- [ ] `@ProtoNumber` 空缺号 `reserved` 登记（两处 proto 定义 + proto 文件）
- [ ] 配置源：改中性源 → 跑生成器 → 记录新 sha256 → `--check` 复验；**删静态表时键整个消失，不得留空数组**（见 §5）
- [ ] 双 changelog（外部 `CHANGELOG.md` + 游戏内 `assets/changelog_entries.json`，后者改完必须 JSON 可解析校验）+ `report-Gxx.md`
- [ ] `check-agent-instructions` EXIT=0
- [ ] 暂存危险扫描（排除 `docs/research/` + 构建副产物；素材两目录已被 `.gitignore` 兜住）→ 单次提交 → 验证「非未跟踪残留 = 0」
- [ ] 树指纹：门禁前后各记一次 `git rev-parse HEAD` / `git status --porcelain` 行数（本仓多会话共用一棵工作树）

### 3.6 分片文件面模板（G03 实际生效版）

| 分片 | 允许改 | 禁止改 |
|---|---|---|
| **T** | `gamecore/**`（含 test/CMakeLists）、`scripts/action-catalog/**`、生成物 `action_ids.h` | Kotlin 手写源、Room、docs |
| **A** | 各模块 `src/main`、生成物 `ActionIds.kt`、配置中性源 | `src/test`、`.cpp/.h`、Room 迁移与迁移测试、docs |
| **c340** | 各模块 `src/test` | `src/main`、`.cpp/.h`、Room 迁移本体（只改断言）、docs |
| **主线程** | Room 迁移四件套、迁移测试本体、双 changelog、report、所有提交与门禁 | — |

G04 的具体切片（T-a…T-e / A-a…A-d / c340×N，各片文件清单）见 [`TASKBOOK-G04.md`](TASKBOOK-G04.md) §3。

---

## 4. 仍然有效的硬口径（细则回原文）

1. **玩家侧弟子永不死亡 = 重伤**（钳 `INJURED_HP`、`isAlive` 保 1、不清槽/不解绑/不物化行囊/不计年报死亡）；回血复用既有每旬机制，**不新增机制与配置项**。
2. 🔴 **悟性系统整体保留、不删**（用户 2026-09-24 拍板）—— 全文见 `HANDOVER-m1-remaining.md` §4 口径 15。要点：`comprehension` 列、`SkillStats.comprehension`、`comprehensionBreakthroughBonus`、`ELDER_SKILL_BASELINE` 四常数、长老有效悟性提取与「悟性加成」展示行全部存续；G04 删除面收窄为八项；`recon-G04.md` §5#1「突破率公式无定义」缺口**自动关闭**，§11 三口径降级为「将来若要删悟性」的预案。
3. **ActionId 只增不复用**，且**已固化走「catalog 保留条目 + 保号 + desc 标废弃」路线**（G02/G05/G06/G03 四批均如此，`kAllActionIdsCount` 恒 198）。⚠️ `recon-G04.md` §5.2 提过「删 catalog 条目」替代路线 —— **不要中途换路线**，换路线要写 ADR。
4. **Room 列禁 `DROP COLUMN`**：`rebuildTableDroppingColumns`（create-copy-drop-rename）+ 递增版本 + 注册 + 迁移测试；旧 `Index` 声明不得删。
5. **ProtoBuf `@ProtoNumber` 只增不复用**：删字段在两处 proto 定义写 `reserved`。
6. **C++ AUTHORITATIVE**：`MirrorReadOnlyGuardTest` 必须零命中；Kotlin 稳态只读。
7. **UI 不驱动系统 tick**；界面实时数据订阅 `GameEngine` StateFlow 派生。
8. **三端字段链同 commit**（铁律 6）+ **确定性 RNG** + **入库唯一入口 + `SOURCE_DISPLAY_NAMES` 同批登记**（详见 `EXECUTION-PROTOCOL.md`）。
9. 完整口径表见 [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) §4（现 15 条）。

---

## 5. G04 指针（**细则看 TASKBOOK-G04.md，本文件不重复**）

| 项 | 内容 |
|---|---|
| 范围 | 删 洗炼 / 资质 `aptitude` / 天赋·体质·词条三表 / 血炼 / 职位特质数值源 / 战斗随机成长。**悟性不在其中** |
| 侦察 | [`recon-G04.md`](recon-G04.md)（子代理自己读；§4/§6/§7 是其文件清单来源） |
| 任务书 | [`TASKBOOK-G04.md`](TASKBOOK-G04.md)：§1.1 **必须从 §8 grep 清单划掉的三条**（#14 的 `baseComprehension`/`comprehensionBreakthroughBonus`、#18 整条、#19 `comprehensionAdd`）；§1.2 **已实测推翻的 `data_inject.h` 结论**；§1.3 前置扫描；§3 切片表；§4 ActionId 7 个（1613/1614/1615/1616/1732/1733/1746） |
| 唯一真红线 | 删三表时中性源与 `game-data.json` 里 `talents`/`physiques`/`affixes` **三个键必须整个消失**；「键在而数组为空」才会让 `applyGameData` `return false` → 10 表静默落内联兜底。（侦察 §12#9 把因果写成"删键即拒"，**已推翻**） |
| Room | v57 → **v58** |
| 待核实 | `comprehensionAdd`（`PillEffect` @ProtoNumber(24)）是否已零生产者 —— G02 已下架「悟丹」丹方；若确实零生产 → **登记 G10 死字段，本批不删**（删它要动 Room/proto 链，成本不对等） |

---

## 6. RNG / 金黄纪律与 B 类登记

### 6.1 纪律（不变）

1. 金黄 / baseline **只在 G10 重录**；中间批次 A 类必修、B 类逐条登记不重录。
2. 门禁绿判定 = 编译 EXIT=0 **且** 失败集 ⊆ B 类登记集 **且** 零 A 类（A/B 判据见 §3.3）。
3. 每批报告必须产出「本批新增 B 类清单」，G10 汇总消化。

### 6.2 当前 B 类登记（C++，**G03 收官：3 条**）

```
DiscipleFactory.GoldenSequenceSeed42
DiscipleFactory.GoldenSequenceSeed987654321Female
DeterminismProbeTest.DigestMatchesGoldenBaseline
```

> 第四项 `ChildBirth.GoldenSequenceSingleBirth` 已随 `android/app/src/main/cpp/gamecore/test/child_birth_test.cpp` 整文件删除而消失，登记为「随文件删除」，非重录。
> Kotlin 侧 **0 条**：G03 全量 JUnit 终树 7662 全绿，`Diff*` 家族零锚改动。

### 6.3 G10 待办汇总（累积登记）

⚠️ **条目数以本报告为准** —— HANDOVER-2 §6.3 的 G02/G06 计数与文件实际不符，本轮已复核：

| 来源 | 复核后条目数 | 定位 |
|---|---|---|
| G02 | 「未完成 / 登记」**9** 条 + 「保留项登记」**13** 条（HANDOVER-2 记作 14，含表头/分隔行误计） | [report-G02.md](report-G02.md)（含 `SettingsPatchFieldCoverageTest` 类死引用、`markDead` 命名收口、`isOutsideSect` 语义过载等） |
| G05 | 「途中发现登记」**8** 条 | [report-G05.md](report-G05.md)（战俘收编下线、`prisonerSpiritRootFilter` 死设置、`RecruitFailed` 零发布者、`RECRUIT_MONTHLY_LIMIT` 残留等） |
| G06 | 「G10 登记」**4** 条（HANDOVER-2 记作 6，**高估**） | [report-G06.md](report-G06.md)（`clearAllDiscipleSlotsForRemoval` 零调用方、`eraseDiscipleDerivedMaps` 生产零消费、recon 行号过期、既有 B 类持平） |
| **G03** | **「G10 登记」9 条** | [report-G03.md](report-G03.md) §八。**最要紧的两条**：① 🔴 侦察文档结构性缺陷（§9.x 归属结论与落点表不双向校验 + 字段链表漏非弟子表的 C++ 环）→ 已转为 §3.4 的前置扫描规程；② `Diff*` 不等于 B 类的判据方法（§二·补） |

**G03 登记摘要（逐条见 report-G03 §八）**：侦察结构性缺陷两处；B 类定性澄清（G10 工作量重估）；`ai_sect_ops.h`/`disciple_stats.h` 各一行 `rootCount = 1` 自赋值无效语句；`applyCombatInjury` 仍调 `markDead`（名=死亡/行为=重伤）+ 测试注释失实 + 测试弟子命名「阵亡者」；两处裸 `mock(GameRngManager)` 违反 `mockSmart`；`FakeAtomicStateStore` 未登记进 `rules/testing.md` 共享工厂表；「关系」对话框只剩师徒、改名待拍板；`prisonerSpiritRootFilter` UI 写入面残留；子代理 150 轮上限的工程事实。

---

## 7. 剩余批次指针与已知阻塞

| 批次 | 内容 | 侦察 / 任务书 | 关键前置 / 阻塞 |
|---|---|---|---|
| **G04** | 删洗炼/资质/三表/血炼/职位特质/战斗随机成长（**悟性保留**） | `recon-G04.md` + **`TASKBOOK-G04.md`** | 见 §5；前置扫描 §3.4 必做 |
| **G08** | 角色模板层 + `templateId` 实例化 + 开局周明/5 万 + 兑换码改道 | `recon-G05-G06-G08-G09.md` `G08`；口径 `HANDOVER-1` §4#9~#12 | 兑换码改道牵动 4 处测试 + 6 个死 helper（`HANDOVER-1` §5#6）；AI 宗弟子构造保持旁路 `templateId=""` |
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | 同上 `G09` | 硬依赖 G08 模板读取层（`characterTemplates` 当前零生产读取方）；C++ AUTHORITATIVE；入库来源名须同批登记 `OverflowMailSender.SOURCE_DISPLAY_NAMES` |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | 同上 `§G11` | 🔴 **素材注册硬阻塞**：12 个角色精灵键全未注册，源图在 `模拟宗门美术素材/<角色名>/{头像,全身像}.png`（该目录**永不提交**），七步流程见 `rules/static-resources.md`；色表强制 Q31（`GameConfig.Gacha` 单源），禁用 `ItemCard.getRarityColor` 旧色表 |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 + 文档收口 | `EXECUTION-PROTOCOL.md` §2 | 必须 G02–G07 全合入后**唯一一次**重录；G08/G09 建议同窗。**按 §6.3 重估：工作量集中在 C++ GTest 金序列 + `DeterminismProbe`** |

---

## 8. 下次开工 checklist

1. `git status` 确认非未跟踪残留 = 0（素材两目录已由 `.gitignore` 兜住，不再出现在未跟踪列表）；构建副产物若被改（含 `lintRelease` 造成的）→ `git checkout --` 还原。
2. 读 [`EXECUTION-PROTOCOL.md`](EXECUTION-PROTOCOL.md) + 本文件 §2/§3 + [`TASKBOOK-G04.md`](TASKBOOK-G04.md) 全文 + `recon-G04.md`（**行号是快照，逐处 grep 复核**）。
3. **先做 §3.4 两项侦察缺口回查**，把结果写进任务书再派工 —— G03 两次漏项的代价（一次编译期、一次运行期）已证明这一步比返工便宜。
4. 按 **≤10 文件/片**切分（`TASKBOOK-G04.md` §3 已给切片表），每份 prompt 自包含（子代理看不到会话上下文）。
5. 三~四路并行 → 主线程集成（§3.5）→ 全门禁（§2.2，含重建 JNI 库后跑 JUnit + lint）→ 双 changelog + `report-G04.md` → 单次提交。
6. 若分片再次撞 150 轮上限：`git status` 固化清单 → 按剩余文件重切小片续跑，**不要回退已落盘的正确改动**，也不要在编译不过的树上叠加。

---

## 9. 诚实状态声明

- **M1 完成度 4 / 9 批**（G02 / G05 / G06 / G03 已提交且门禁全实测绿）；G04 / G08 / G09 / G11 / G10 **尚未开始实施**。
- **G04 只完成到「任务书就绪」**：本文件与 `TASKBOOK-G04.md` 是规划与核验产物，**未改动任何 G04 代码**。
- **为什么 G03 收在干净边界就停手**：G04 体量比 G03 更大（`aptitude` 单键 C++ 30+/Kotlin 60+/测试 200+ 命中），而本轮 G03 的 5 个分片全部撞子代理轮次上限、靠主线程接管才收口；半开 G04 会把工作树留在编译不过的中间态，正是上一会话 G03 首次失败被迫全量回退、白烧一整批的失败模式。
- **已核实**：G03 的 ctest / JUnit / detekt / lint / 生成器 / Room 数字均为本会话同轮命令实测输出；`data_inject.h` 结论、`unbindMasterColumnsStep` 已在 G02 消失、`Diff*` 16 条为 A 类协议漂移，均有源码/日志直接证据。
- **推测 / 未核实**：G10「Kotlin Diff 家族无需重录」是**推论**（依据 = `Diff*` 断言的是跨语言等价而非黄金值，且 G03 终树实测全绿），须 G10 终树再证。
- ✅ **原列 3 项待拍板已于 2026-09-24 全部关闭**（见 §1.3）：① 「关系」对话框 → 升级为**师徒系统整体下线**（新增 G15，任务书内联 §10）；② `comprehensionAdd` 经实测**是活字段**（配方 36 条非零 + `pill_system.h:219` 真实写入悟性）→ 随悟性保留，不删；③ 素材 → 单独开 **G16**，插在 G04 之后、G09 之前（任务书内联 §11）。另新增一项：**血炼池连建筑一起拆**（G04 范围扩大，§5）。
- 🔴 **素材/音频目录策略已变更**：`模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）自 2026-09-24 起是**项目指定的源资产目录**，登记于 `rules/media-source-assets.md`，但**按用户指令不入库**（已写进 `.gitignore`）。⇒ 换机器后这两个目录是空的，需项目外渠道同步。
- **远端未推送**；`docs/research/`×2 仍为未跟踪且不提交。
---

## 10. G15 任务书 · 师徒系统整体下线（P-1 拍板产物，**内联即派工就绪**）

> 用户 2026-09-24 拍板：不只是删「关系」对话框 UI，而是**连根拆掉整个师徒玩法**。

### 10.1 🔴 先作废三条既有口径（否则会被反向守卫误导）

| 来源 | 原文 | G15 的处置 |
|---|---|---|
| `HANDOVER-m1-remaining.md` §7 / `recon-G02-G03.md` §G03-7 #12 | 「`masterId`/师徒/拜师/APPRENTICE 是**反向守卫，必须仍有命中**」 | **作废**。G15 正是这些符号的清零批。执行者不得拿它当保留依据 |
| report-G03 §七 | G03 收官 grep：`masterIds`/`masterBonusFor`/`kMasterGiftProb`/`social_masterId` 等「全在」贴证 | 该贴证在 G03 语境正确，G15 后**逐条反转为归零** |
| report-G03 §二-4 | `LifeEventDraft`/`lifeEvents` 因仍服务突破日志而保留 | 与本批无关，仍保留（勿顺手删） |

⚠️ G03 把亲属赠礼从 6 类收到**只剩师徒 2 类**（`kMasterGiftProb=0.40`/`kApprenticeGiftProb=0.30`）。G15 删师徒 ⇒ **赠礼系统整体消失**：`relative_gift.h` + `RelativeGiftHandler.kt` + `GiftRelationshipType.kt` 三个文件**整文件删**，`phase_settlement.h` 的赠礼接线（include + 调用）同批摘除。这条不是遗漏，是 P-1 的直接后果。

### 10.2 实测文件面（本轮 grep 得出，非推测）

| 面 | 数量 | 清单 |
|---|---|---|
| **C++ 生产** | **10** | `action_ids.h`（生成物）、`state/column_dirty.h`、`state/disciple_store.h`、`system/disciple.h`（`getMasterDiscipleRealmGap`/`getMasterDiscipleCultivationBonus`/`getMasterDiscipleBreakthroughBonus`/`kMasterCultBonusPerGap`/`kMasterBreakBonusPerGap`）、`system/disciple_lifecycle_tx.h`（**拜师事务 1591**）、`system/phase_settlement.h`（`masterBonusFor`）、`system/relative_gift.h`（整删）、`src/disciple_store.cpp`、`src/execute_dispatch.cpp`、`src/game_core.cpp` |
| **Kotlin `src/main`** | **45**（扣除 5 个历史 Migration 后 ~40） | `:core:domain` 模型/表 12 文件（`Disciple`/`DiscipleAggregate`/`DiscipleComponents`/`DiscipleExtended`/`DiscipleSerializer`/`GiftRelationshipType`/`AssembleGroup`/`DiscipleTables`+`Assemblers`+`ColumnRegistry`+`Write`/`GameConfigData`）；`:core:data` 序列化 4 文件 + `GameDatabase.kt`；`:core:engine` 17 文件（含 **`DiscipleMasterApprenticeService.kt` 整删**、`DiscipleFacade`/`DiscipleFacadeImpl`/`DiscipleService`/`DiscipleLifecycleNativeTx`/`DiscipleLifecycleManager`/`GameEngineDiscipleOps`/`DiscipleStatCalculator`+`修炼Ops6`+`属性Ops4`/`CultivationRateCalculator`/`DiscipleBreakthroughHandler`/`DiscipleLifecycleProcessor`/`RelativeGiftHandler`/`YearSettlementResidualExecutor`/`GameViewDiscipleRows`/`GameViewMirrorCodec`/`ActionIds.kt` 生成物）；`:feature:game` 10 文件（含 **`MasterApprenticeSelectDialog.kt` 整删**、`DetailActionButtons`/`DetailBasicInfoSection`/`DetailCultivationSection`/`DetailRightPanel`/`DiscipleDelegate`/`DiscipleDetailScreen`/`ProductionViewModelElderOps`/`SectViewModel`） |
| **Kotlin 测试** | **26** | 按 §3.2 切 3 片（各 ≤10 文件）；含 `DiscipleServiceApprenticeTest`（G03 报告点名的"师徒回归门"）**整类删** |
| **C++ 测试** | **3** | `disciple_lifecycle_tx_test.cpp`（拜师用例）/ `relative_gift_test.cpp`（整删）/ 列双射五件中的相关件 |
| **历史 Migration** | 4 | `GameDatabaseMigrationsV2ToV10/V21ToV30/V39` + `V57` 的 `social_masterId` 列名 —— 🔴 **一律不改**（迁移链不可改写） |

### 10.3 Room 与协议

- **Room v58 → v59**（G04 占 v58）：`disciples` 删 **`social_masterId`** 一列（第 3 批删列，仍走 `rebuildTableDroppingColumns` + `DATABASE_VERSION` + 注册 + `59.json` + `RoomMigrationV58To59Test`）。
- 🔴 **旧 `Index` 声明不得删**（铁律：`disciples` 现有 5 索引均与 masterId 无关，重建时原样带回）。
- `@ProtoNumber`：`masterId` 在 `SerializableDisciple` / `OldSerializableSaveData.SerializableDisciple` 的字段号 → `reserved` 追加（**开工先 grep 实际号，勿照抄 G03 清单**）。
- ActionId **1591 `DISCIPLE_LIFECYCLE_APPRENTICE`** → 保号 + desc【已退役，编号禁复用】+ dispatch case 删 + `dispatch_guard_test` retired 登记；regen 目标 **198/maxId=1861 不变**。
  ⚠️ G03 后 `disciple_lifecycle_tx.h` 只剩「拜师 / 年俸开关」**两事务**，删拜师后剩一事务 ⇒ 头注释与 `handleDiscipleLifecycleTx` **区间起点 1591 要迁移到下一个在册编号**（照 G06 的 1590→1591 先例处理，别留断裂区间）。

### 10.4 存档与引导连带面（**本批最易漏的一类**）

| 必查项 | 为什么 |
|---|---|
| 读档清理：`SaveValidator` 规则 / `sanitize*` 是否有师徒引用；旧档 `masterIds` 残留是否需恒清空 | 列删除后旧字节按 wire 跳过，但 Kotlin 侧若有显式读点会 NPE/越界 |
| `GuideTask.kt` / 引导任务条件是否有「拜师」类目标 | G02 先例：玩法下线后引导必须改判据，否则**卡死**（见 CHANGELOG G02「引导防卡死」） |
| 政策 / 长老位 / 天书殿是否有「师徒」类政策开关 | `MASTER_DISCIPLE_*` 常数与政策耦合 |
| `DiscipleChatDialog` 等交谈/好感面是否引用师徒 | 好感 ≠ 师徒，**勿误删**（G03 已确认 `FAVOR_GIFT` 1501 保留） |
| 年报 `annualNewDisciples` 等计数与师徒无关，**不动** | — |

### 10.5 切片建议（按 ≤10 文件，共 9–10 片）

`T-15a`（`disciple_lifecycle_tx.h` + `execute_dispatch.cpp` + `dispatch_guard_test` + `action-catalog` 保号）、`T-15b`（`disciple.h` 师徒三函数+2 常数、`phase_settlement.h` `masterBonusFor`、赠礼三文件整删 + include 摘除、`game_core.cpp`、`column_dirty.h`/`disciple_store.h`/`disciple_store.cpp` 的 `masterIds` 列）、`T-15c`（C++ 测试 3 文件 + 列双射同步）、`A15-a`（`:core:domain` 12）、`A15-b`（`:core:data` 5 + `:core:engine` 前 6）、`A15-c`（`:core:engine` 后 11）、`A15-d`（`:feature:game` 10）、`c340-15a/b/c`（测试 26 切 3 片）。主线程：Room v59 四件套 + 迁移测试 + 双 changelog + `report-G15.md` + 全门禁 + 单次提交。

### 10.6 RNG

删师徒赠礼 = 再删一处 **SYSTEM 分区**每亲属 `nextDouble` 消费（G03 已把关系收到 2 类，G15 归零）⇒ 旬结序列二次平移。按 §3.3 判据登记 B 类，**G15 内不重录**，交 G10 统一消化。师徒加成乘区本身零 RNG（纯境界差算术）⇒ 不影响序列。

---

## 11. G16 任务书 · 角色素材批（P-3 拍板产物）

| 项 | 内容 |
|---|---|
| 目标 | 解除 G11 硬阻塞：注册 **12 个角色精灵键**（6 角色 × 头像 + 全身像，键名如 `avatar_zhouming` / `portrait_zhouming`） |
| 现状（侦察证据） | 键**只存在于** `android/app/src/main/assets/data/game-data.json`；`resource-registry.json` 无、`sprite-uid-map.json` 无、双模块 `drawable-nodpi` 无文件 |
| 源图 | `模拟宗门美术素材/<角色名>/{头像,全身像}.png` —— 🔴 **该目录永不提交**，本批只提交转换后的 WebP 与注册代码 |
| 流程 | 严格走 `rules/static-resources.md` 七步：无损 WebP → **双模块**放置 → `XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)` → `ResourcePreloader` 同步点 → `SpriteImage("名称")` / Canvas `drawSprite` 使用 → 图集 codegen → 守卫测试 |
| 门禁 | `node scripts/check-agent-instructions.mjs`；`SpriteAtlasDefGeneratedTest` / `BuildingSpriteFootprintGuardTest` 一类精灵/图集守卫必须绿；`detekt`；全量 JUnit（确认无"未注册键被引用"红） |
| 冲突面 | 与 G04/G15 **无共享文件**（除 `XianxiaApplication.kt`，该文件 G03 已因 System 注册改过）⇒ 可与删除批**任意顺序插空**，用户选定位置 = G04 之后、G09 之前 |
| 注意 | `sprite-uid-map.json` / `atlas-rgba-manifest.json` 是构建副产物（§2.3 坑 2），提交前 `git checkout --`；本批**新增素材文件**才是要提交的东西 |
