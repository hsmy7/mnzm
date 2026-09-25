# M1 剩余批次交接（第三份）

> **本文件用途**：G03 收官时的完整交接 —— ① 已收官 4 批的实测基线；② 门禁数字与本轮新增的实操坑；③ 修订后的作业规程（分片粒度 / A·B 类判据）；④ 剩余批次落点指针与硬阻塞；⑤ G10 累积登记与**条目数复核差异披露**。
> **与前几份的分工**（四份各有职责，互不重复）：
> - [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) —— **批次清单 / 顺序理由 / 产品口径表 / 串行约束 / 待拍板项**的权威（§4 口径表现含新增口径 15、§5#1 已关闭）。
> - [`HANDOVER-m1-remaining-2.md`](HANDOVER-m1-remaining-2.md) —— G03 失败复跑任务书与三次实操规程；**其 §2 门禁基线已被本文件取代**（G06 数值已过时）。
> - [`TASKBOOK-G04.md`](TASKBOOK-G04.md) —— G04 派工细则（切片表、每片文件面、grep 口径纠正、前置扫描）。本文件不重复，只给指针。
> - [`TASKBOOK-G15.md`](TASKBOOK-G15.md) —— **G15 派工细则的唯一真源**。🔴 **本文件 §10.2 的实测文件面已被其 §2 取代**
>   （回查出 13 处漏项 + 2 处假阳性；§10 其余判据仍有效，两处冲突以 TASKBOOK-G15 为准）。
> - **本文件** —— 收官基线 + 修订后的作业规程 + 剩余指针。
> **更新时点**：**G15 实施完成（全门禁同轮实测判绿、未提交）后**；§1/§2.2/§6.2/§6.3/§7/§8 已是 G15 值
> （初版与 G04 版基线均被取代——G04 值在 §2.2 以括注保留）。

---

## 1. 当前状态一页速览

| 项 | 值 |
|---|---|
| 分支 | `feat/gacha-m0-m1` |
| HEAD | **`a055bf9fe`（G15 实施完成但未提交，见下行）**；其下 `4078f12c9` = G04 收官 |
| 工作树 | 🔴 **G15 全量实施未提交**：117 跟改 + 5 新增（跟改侧 +426/−3850）、整删 9 文件；全门禁已由**实施会话同轮实测判绿**（数值见 §2.2 的 G15 列与 `report-G15.md` §二），但按 §8.5 规程**须由复核会话同轮重跑后才允许提交** |
| 未跟踪 | 仅 `docs/research/`×2（与本批无关，**不提交**）+ G15 的 5 个新增（`GameDatabaseMigrationsV59.kt`、`schemas/…/59.json`、`RoomMigrationV58To59Test.kt`、`TASKBOOK-G15.md`、`report-G15.md`）。🔴 `模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）**已写入 `.gitignore`** —— 2026-09-24 拍板：作为项目指定素材/音频源目录，**只登记不入库**；位置与边界见 `rules/media-source-assets.md`。**旧表述「未跟踪 4 组永不提交」自本文件起作废** |
| 已完成 | **5 / 11 批已提交**（G02、G05、G06、G03、G04）+ **G15 实施完成待复核提交**（分母 11 = 原 9 批 + 拍板新增 G15、G16；剩 G16/G08/G09/G11/G10 共 5 批未开工） |
| 下一批 | **G16**（角色素材批；任务书内联本文件 §11）。🔴 **但 G15 尚欠一次复核会话的同轮全门禁重跑 + 单次提交**，G16 不得在混入 G15 半提交态的树上开工 |
| 远端 | **未推送**（本仓多会话共用一棵工作树，推送由用户指令决定） |

### 1.1 已收官批次（门禁值以各批报告实测为准）

| 批次 | 提交 | 报告 | 规模 | ctest | JUnit | 其他要点 |
|---|---|---|---|---|---|---|
| G02 | `5dbaac1e3` | [report-G02.md](report-G02.md) | 443 文件 +10775/−26845 | 1532/1536→4 B 类 | 7893/0 | Room v54→v55 |
| G05 | `8e593a71b` | [report-G05.md](report-G05.md) | 143 文件 +5624/−8401 | 1487/1483/4 | 7720/0 | JNI 89→86；v55→v56 |
| G06 | `e1a69d8e9` | [report-G06.md](report-G06.md) | 42 文件 +179/−1309 | 1483/1479/4 | 7709/0 | ActionId 1590/1740 退役 |
| **G03** | `cc66d7918` | [report-G03.md](report-G03.md) | **171 文件 +6084/−5186** | **1470/1467/3** | **7662/0** | ActionId 1592/1750 退役；**Room v56→v57（3 表 11 列）**；lintRelease 绿 |
| **G04** | `4078f12c9` | [report-G04.md](report-G04.md) | **364 文件 +6448/−24737**（整删 48） | **1413/1410/3** | **7397/0/0/18 skip** | ActionId 1613/1614/1615/1616/1732/1733/1746（+1437 顺延）退役，retired 集 13→**21**；**Room v57→v58（2 表 9 列）**；game-data 三键整个消失 sha256 `035066cb…94ef`；detekt/lint/图集门全绿 |
| **G15** | **未提交**（HEAD 仍 `a055bf9fe`） | [report-G15.md](report-G15.md) | **117 跟改 + 5 新增 +426/−3850**（整删 9） | **1394/1391/3** | **7336/0/0/18 skip** | 师徒整体下线；ActionId **1591** 退役，retired 集 **21→22**（catalog↔guard 双向零差集已实测）；**Room v58→v59（disciples 1 列，91→90）**；`DiscipleColumn` 枚举 92→91；proto 双侧 `reserved 93` + 镜像 `reserved 85`；赠礼三文件整删（消费方 **2** 个）；JNI 86/86、game-data sha256 **不变**、lint 36 警告全预存 |

> ⚠️ **G15 的门禁值是实施会话同轮实测，不是复核会话实测**——按本报告 §十 的规程，提交前须由复核会话重跑 §2.2 全表。
> 五批已提交者均为：全门禁实测绿 + 双 changelog + `report-Gxx.md` + **单次提交**。

> ⚠️ **G04 的提交由「复核会话」完成**：实施会话留下未提交工作树 + 一份把 ctest/detekt/JUnit 误记为绿的报告；
> 复核会话在同一棵树逐门重跑，实测出 **4 处失实**（3 条 `SceneEquivalenceTest` A 类红、6 条 detekt 红、2 个陈旧图集守卫、规模数字失真）并当场修复。
> 教训已回写 report-G04 §二·补 与本文件 §2.3 坑 9 —— **「报告声称绿」不构成门禁证据，必须同轮复跑**。

### 1.2 剩余顺序（**2026-09-25 G15 实施完成后**）

**〔G15 复核 + 提交〕→ G16（角色素材批）→ G08 → G09 → G11 → G10**

- 🔴 **G15 已实施完成但欠一次复核**：本批动了 `models.h` / `disciple_store.h` / `column_dirty.h` /
  `DiscipleTables*.kt` / `Disciple.kt` / `game_view.proto` / `ActionIds.kt` 全套共享面，
  **下一批（含并行的 G16）必须等 G15 提交落定后再开工**，否则 §5 串行约束的前提（每片开工前 `git status` 干净）不成立。
- **G16 素材批**按用户指令插在 G04 之后、G09 之前（与删除批零文件冲突，可并行插空），用于解除 G11 硬阻塞；
  实测与 G15 的唯一共享文件是 `XianxiaApplication.kt`（G15 只删了 4 个 provider override 的形参），
  ⇒ G15 提交后 G16 再改该文件不冲突。
- G08 → G09 → G11 是依赖链（G09 解锁依赖 G08 模板读取层；G11 依赖 G09 + G16）。
- G10 最后（唯一一次 RNG 重录窗口）。**G15 未新增 B 类**（§6.2），且首次正面实证
  「两侧同步删除时 `Diff*` 继续绿」⇒ §3.3 那条 G10 排期推论已由实测替代。
- **M1 完成判据**：G10 全绿 + G11 最简 UI 真机通。

### 1.3 本轮新增的三条产品拍板（2026-09-24）

| # | 拍板 | 影响的批次 |
|---|---|---|
| P-1 | 🔴 **师徒系统整体下线**（不只是删「关系」对话框 UI，而是**连根拆掉整个师徒玩法**） | **新增 G15**；并使 G03 报告与本文件原「师徒反向守卫」全部**作废**（见 §10.1） |
| P-2 | 🔴 **血炼池建筑连建筑一起拆掉**（不采用 G02「建筑保留、只断功能」先例） | **G04**（`TASKBOOK-G04.md` §1 范围扩大，见 §5） |
| P-3 | **角色素材批单独开一批**，插在 G04 之后、G09 之前 | **新增 G16**；解除 G11 硬阻塞 |

> 另：原「G04 待拍板项②——`comprehensionAdd` 是否死字段」经实测**自动消解**：配方侧有 **36 条非零**（智悟丹/悟德丹/灵悟丹/明悟丹…），`pill_system.h:219` 真实写入悟性 → **该字段是活功能，随悟性一并保留，不删**。G02 下架的是另一组 `pillType=comprehension` 丹方。

---

## 2. 门禁基线与运行方式（**G15 实施终树实测，取代本文件初版的 G04 值**）

> 🔴 G15 列的值由**实施会话同轮**跑出的原始输出；G04 列的值由**复核会话**跑出的。
> G15 提交前仍须复核会话同轮重跑（§8.5 规程），下表可作复核的对照基准。

### 2.1 环境前置

```powershell
# C++ 桌面构建：PATH 前置三段（缺一不可，缺 llvm-mingw bin 会 STATUS_DLL_NOT_FOUND）
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH
# 桌面构建目录：android/app/src/main/cpp/gamecore/build/desktop-test （不是同级 build/，那是旧套件）
```

### 2.2 门禁表

| 门 | 命令 | G15 实施终树实测值（G04 值括注） |
|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（desktop-test 目录） | EXIT=0，`[5/5] Linking game-core-tests.exe` |
| 桌面 ctest | `ctest`（同目录） | **1394 总 / 1391 过 / 3 败**（G04：1413/1410/3）。−19 = `relative_gift_test.cpp` 整删 + `MasterDiscipleTest` 2 + 拜师 tx 3。3 败 = §6.2 同名三条 B 类，**零新增**。⚠️ 图集/生成物变更批仍须先确认 `SceneEquivalenceTest` 三条不红（坑 9） |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | `BUILD SUCCESSFUL in 1m 57s` |
| 测试源编译 | 五模块 + `:app` `compileReleaseUnitTestKotlin --max-workers=1 --continue` | **0 错误** |
| detekt | 六模块 | `BUILD SUCCESSFUL`；`detekt-baseline.xml` 与 `lint-baseline.xml` **零改动**（只缩不增） |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<绝对 .so>"` | **7336 / 0 / 0 / 18 skip**（app 995 / domain 1575 / data 806 / engine 2866 / ui 146 / feature:game 948）（G04：7397）。−61 = 整删拜师 12 + 赠礼 27 + 乘区/明细/解绑 19 散例 + 新增迁移测试 3。**仍必须带 `--continue` 并按 XML 逐模块汇总**（坑见 §2.3-8/G04 教训） |
| lint | `./gradlew.bat lintRelease` | `BUILD SUCCESSFUL in 6m 3s`（G04：约 17m——本次配置缓存 reused + 增量，非口径变化）；`Lint found 36 warnings`，本批文件按符号过滤**零命中** ⇒ 36 条全预存。⚠️ 跑完仍要查 `atlas-rgba-manifest.json` 是否被改脏（坑 2） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `android/core/engine/build/desktop-jni/libgamecorejni.so` | EXIT=0；**改任何 C++ 后必须重建**再跑 JUnit。🔴 必须验 `.so` 体积/时间戳真的变了（坑 11：`powershell` 而非 `pwsh` 会解析失败却仍报 exit 0） |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86**（G15 删的是既有 `external fun` 体内的 op 分支，计数不变） |
| ActionId | `node scripts/gen-action-ids.mjs` | **198 动作 / maxId=1861**（不变）。⚠️ 零漂移自证的正确基准是**regen 前后工作树自比**（`cp` 后 diff），不是 `git diff` 对 HEAD——desc 本批有意改了，对 HEAD 必差（坑 10） |
| catalog↔guard 退役集 | 脚本扫 `scripts/action-catalog/*.mjs` 六文件 + `dispatch_guard_test.cpp` | catalog 解析 198 == 生成器 198；标退役 **22** == guard **22**，两方向差集均空。⚠️ 只扫 `core.mjs` 会漏 3 条（1740/1746/1750 在其他分文件） |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`——**与 G04 逐字符相同**（G15 未触碰配置源） |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | `✓ 全部通过`；预存告警 2 条（规则③ `rules/static-resources.md:277` basename 不精确 1 处、规则⑤ 最坏链路 37890 字节）属正常 |
| Room | `DATABASE_VERSION` = **59**；`schemas/…/59.json` 已入库；`disciples` **90 列**、`game_data` 128 列（索引 5+5 全保留） | 禁 `DROP COLUMN`；历史 schema JSON 只增不改；下一批删列则 → **v60**（G16 素材批不删列） |

### 2.3 十二条实操坑（前三条承自 HANDOVER-2，4-8 条 G03 新增，第 9 条 G04 新增，10-12 条 G15 新增）

1. **KSP 会就地改写历史 schema JSON**：bump 版本后若 KSP 把 `56.json` 等历史快照改小，立即 `git checkout -- <该 json>`；只允许新增当前版本 JSON。（G03 实测：仅 `57.json` 新增，历史零改写。）
2. **构建副产物必须还原**：`sprite-uid-map.json`、`atlas-rgba-manifest.json` 每次构建都改时间戳 → 提交前 `git checkout --`。⚠️ **`lintRelease` 也会改**（本轮实测：提交前它又变脏了一次）。
3. **文本编辑用文件编辑工具，不要用脚本拼接**：JSON 追加后必须 `node -e "JSON.parse(...)"` 校验。⚠️ **G04 实证代价**：用脚本给 `assets/changelog_entries.json` 追加条目把**整个文件重排**（2,474 行全文件改写，语义没错但审查不可读），提交前不得不用 `git checkout HEAD -- <file>` + 编辑工具重做成 +7/−1 的最小追加，并逐条与 HEAD 比对确认零丢失。**双 changelog 一律用编辑工具改。**
4. 🔴 **Bash 工具的 `cd` 绝对路径不可靠**：`cd "C:/..." && ./gradlew.bat` 会 **EXIT=127**（命令找不到）。正确做法是**不写 `cd`，用 Bash 的 `dir_path` 参数**定位到 `C:\Mnzm\XianxiaSectNative\android`。子代理反复踩到过。
5. 🔴 **`grep -c` 无命中返回 exit 1**，会把"实际成功"的后台命令包装成 failed（本轮 `lintRelease` BUILD SUCCESSFUL 却被通知为 exit 1）。⇒ **判定后台命令成败必须读它自己的日志**（`BUILD SUCCESSFUL|FAILED` 行），不能信复合命令的退出码。
6. **CRLF 警告 ≠ 行尾污染**：Git 报 "in the working copy of X, LF will be replaced by CRLF" 时，`git show :file | grep -c $'\r'` 的输出不可信（autocrlf 会在输出路径上做转换）。**判据是 `git diff --cached --numstat`**：若某文件出现"增删双侧 ≈ 全文件行数"才是真翻转；G03 实测 171 文件无此类。
7. **`--tests` 过滤作用于该次调用所有 Test 任务**；多模块一起跑须按模块分别调用，否则某模块无匹配用例直接判红。
8. **同机只跑一路 Gradle 组合门**：并行会互锁 `classes.jar` 并制造假红。子代理在途时主线程优先只跑 `compile*`，全量测试留终树复跑。
9. 🔴 **G04 新坑：「手工复刻的静态期望表」是编译与生成器都抓不到的孤儿面**（三处，必须列入图集/生成物变更批的分片文件面）：
   - `gamecore/test/scene_equivalence_test.cpp` 的 `kotlinBuildingUv()` 等 rect 夹具（Kotlin `SpriteAtlasDef` 的**手工快照复刻**）——漂移只在 **ctest** 暴露（`SceneEquivalenceTest` 三条，属 A 类协议漂移，不是数值红）；
   - `core/engine/.../render/SpriteAtlasDefGeneratedTest.kt` 的解析期望表（19 栋名 / 19 对占地）——漂移只在**全量 JUnit** 暴露；
   - `core/engine/.../config/BuildingSpriteFootprintGuardTest.kt` 的兜底尺寸期望表——同上。
   旁证：`SceneUvTablesMirrorGuardTest`/`AtlasLayoutSyncTest`/`FootprintTableSyncTest` 全部动态取 `SpriteAtlasDef.BUILDING_NAMES.size`，生成物一致即绿，**恰好掩盖这一类**。
   另一条同形状教训：**「报告声称绿」不作证据**——G04 实施会话的 ctest/detekt/JUnit 三项绿均不可复现，且 JUnit 计数因无 `--continue` 的首轮 + 陈旧 XML 虚高 250 例；复核会话同轮重跑才拿到真值（见 report-G04 §二·补）。
10. 🔴 **G15 新坑：「按字面量 grep」定测试面有系统性盲区——接口形参删除类批次必须按「实现处枚举」定面**。
    实例：`DiscipleStatsProvider` 的 `masterDiscipleBonus` 形参在 src/main 删除后，18 个实现点里有 **6 个测试假实现
    用缩写形参名 `mdb: Double`** 声明它，`grep masterDiscipleBonus` 全部漏计（`:app` 的
    `DerivedAggregationTest`/`GameStateStoreRollbackTest`/`TransactionRngRollbackTest` + `:core:engine` 的
    `CultivationCoreRealtimeAutoPillsTest`/`DisciplePillManagerAutoUseEnhancementTest`/`CultivationServiceIntegrationTest`），
    靠子代理反查才发现，追加一片收口。
    ⇒ 定面命令应是 `grep -rln "override fun <方法名>"`，不是 `grep <形参名>`。
    **同形状**：`DiscipleMergeCoverageTest` 的反射分类清单含 `"social"` 字面量，字段本体删了清单不会红，
    是**判据面**而非断言面，只能靠人想到。
11. 🔴 **G15 新坑：`pwsh` 与 `powershell` 不可互换，且后台任务的 exit code 会盖住「脚本自己报解析错」**。
    实例：用 `powershell -File scripts/build-desktop-jni.ps1`（PS 5.1）解析 `-I (Join-Path $src 'include')` 直接
    UnexpectedToken 失败，`.so` 时间戳/体积完全没变，而外层包装命令仍回报 **exit 0**——差点拿旧库跑完 JUnit。
    ⇒ 除坑 5 的「读它自己的日志」之外，还要**验产物的 mtime 与字节数确实变化**（G15 实测 8560640 → 8526336 才算真重编）。
    跑 Compose/JNI 相关脚本一律 `pwsh`（PS 7）。
12. ⚠️ **G15 新坑：「零漂移自证」的基准容易用反**。`gen-action-ids.mjs` 之后若用
    `git diff --exit-code -- action_ids.h ActionIds.kt` 判零漂移，**在有意改 desc 的退役批里必然误判为"有漂移"**
    （生成物对 HEAD 就该差）。正确做法 = regen 前 `cp` 两份、regen 后与工作树自比（幂等性），
    再单独说明"对 HEAD 的差异是本批有意改 desc"。
    同形状：`check-agent-instructions.mjs` 的预存告警**是 2 条不是 0 条**（规则③ 1 处 + 规则⑤ 最坏链路），
    判据是「全部通过」那一行，不是"无告警"。

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

## 5. G04 收官纪要（细则看 TASKBOOK-G04.md 与 report-G04.md，本文件不重复）

| 项 | 内容 |
|---|---|
| 范围 | 删 洗炼 / 资质 `aptitude` / 天赋·体质·词条三表 / 血炼（P-2 连建筑拆）/ 职位特质数值源 / 战斗随机成长。**悟性未删**（口径 15 落地：`comprehension` 全链 + `comprehensionAdd` 丹效 + 三乘区形参恒 0 全部存续） |
| 结果 | 三端全链下线 + Room v58（2 表 9 列）+ ActionId 7+1 退役（retired 集 21）+ `BloodPoolBuildingCleanupRule`(order=17) 旧档清理 + 图集重生成（41 精灵 / layoutHash `6a122ed2…`） |
| 唯一真红线 | ✅ 已达成：`talents`/`physiques`/`affixes` **三个键整个消失**（`gen-game-data --check` sha256 `035066cb…94ef`，实测键不存在） |
| 前置扫描兑现 | 血炼 C++ 实际面 35 文件（recon 只列 9）、三表消费方 13 文件、`pendingTraitAdds` 字段链 recon 无行 —— 三项均在派工前入任务书 §6 并在集成期归零，**未出现 G03 式运行期 A 类爆红** |
| `comprehensionAdd` | 实测为**活字段**（配方 36 条非零 + `pill_system.h` 真实写入悟性）→ 随悟性保留，本批未删，也不再挂待核实 |
| 遗留 | report-G04 §八 1-10 项（含 3 处预存注释失真、`BeastMaterialDatabase` 零消费者辅助段、活文档仍描述已删玩法等）→ 登记 G10/G12 |

---

## 6. RNG / 金黄纪律与 B 类登记

### 6.1 纪律（不变）

1. 金黄 / baseline **只在 G10 重录**；中间批次 A 类必修、B 类逐条登记不重录。
2. 门禁绿判定 = 编译 EXIT=0 **且** 失败集 ⊆ B 类登记集 **且** 零 A 类（A/B 判据见 §3.3）。
3. 每批报告必须产出「本批新增 B 类清单」，G10 汇总消化。

### 6.2 当前 B 类登记（C++，**G15 实施终树实测：仍是 3 条，逐条同名 ⇒ 零新增**）

```
DiscipleFactory.GoldenSequenceSeed42
DiscipleFactory.GoldenSequenceSeed987654321Female
DeterminismProbeTest.DigestMatchesGoldenBaseline
```

> 🔴 **G15 零新增 B 类的因果链**（report-G15 §三，值得 G10 引用）：删赠礼 = 删一处 SYSTEM 每亲属一次
> `nextDouble`，但该消费是**门控**的（`findRelatives` 空 ⇒ 直接 return），而**五套 Diff 对拍夹具里
> 唯一播种过 `masterId` 的只有 `DiffPhaseSettlementTest` 的 S1 用例**（该用例本批整删）。
> 其余 100 旬 AUTHORITATIVE / 年变 / 月变场景从未播种师徒 ⇒ 序列本来就不含该消费，删除不平移。
> 加上乘区只删恒 0 项、剩余项相加顺序未动（`x + 0.0 == x`）⇒ ctest 三条红与 G04 逐条同名、Kotlin 零红。
> 第四项 `ChildBirth.GoldenSequenceSingleBirth` 已随 G03 的 `child_birth_test.cpp` 整删而消失（登记为「随文件删除」）。
> Kotlin 侧 **0 条**。
> ⚠️ `DeterminismProbe` 的 golden 摘要自 G02 起未再变（G15 实测 `actual=0xb4f3c6912207f597`）
> ⇒ 该指纹对师徒面删除不敏感，G10 重录一次到位即可。

### 6.2·补 §3.3 判据推论已被 G15 实证取代

原推论：「G10 重录工作量集中在 C++ GTest 金序列 + `DeterminismProbe` 摘要，Kotlin Diff 家族预计无需重录（G10 终树实测后方可定论）」。
⇒ **G15 提供了首个正面实证**：两侧同步删除同一消费时 `Diff*` 确实继续绿（不是 B 类、不需重录）。
结论未变，但从「推测」升为「有实测支撑」；G10 仍须终树再证。

### 6.3 G10 待办汇总（累积登记）

⚠️ **条目数以本报告为准** —— HANDOVER-2 §6.3 的 G02/G06 计数与文件实际不符，本轮已复核：

| 来源 | 复核后条目数 | 定位 |
|---|---|---|
| G02 | 「未完成 / 登记」**9** 条 + 「保留项登记」**13** 条（HANDOVER-2 记作 14，含表头/分隔行误计） | [report-G02.md](report-G02.md)（含 `SettingsPatchFieldCoverageTest` 类死引用、`markDead` 命名收口、`isOutsideSect` 语义过载等） |
| G05 | 「途中发现登记」**8** 条 | [report-G05.md](report-G05.md)（战俘收编下线、`prisonerSpiritRootFilter` 死设置、`RecruitFailed` 零发布者、`RECRUIT_MONTHLY_LIMIT` 残留等） |
| G06 | 「G10 登记」**4** 条（HANDOVER-2 记作 6，**高估**） | [report-G06.md](report-G06.md)（`clearAllDiscipleSlotsForRemoval` 零调用方、`eraseDiscipleDerivedMaps` 生产零消费、recon 行号过期、既有 B 类持平） |
| **G03** | **「G10 登记」9 条** | [report-G03.md](report-G03.md) §八。**最要紧的两条**：① 🔴 侦察文档结构性缺陷（§9.x 归属结论与落点表不双向校验 + 字段链表漏非弟子表的 C++ 环）→ 已转为 §3.4 的前置扫描规程；② `Diff*` 不等于 B 类的判据方法（§二·补） |
| **G04** | **「G10 登记」10 条** | [report-G04.md](report-G04.md) §八。**最要紧的三条**：① 图集 manifest 契约两条路线不得并存（管线级）；② 活文档仍描述已删玩法（`cpp-engine`/`architecture`/`knowledge-base`/`CODE_WIKI`/`ui-read-surface`）；③ 三处「手工复刻静态期望表」列入图集批文件面（§2.3 坑 9） |
| **G15** | **「G10 / 后续登记」14 条** | [report-G15.md](report-G15.md) §八。**最要紧的四条**：① 🔴 **接口形参删除批必须按「实现处枚举」定测试面**（6 个假实现用 `mdb` 缩写绕过字面量 grep）→ 已固化为 §2.3 坑 10；② 🔴 `pwsh` ≠ `powershell`，且 exit 0 会盖住脚本自己的解析错 → 必须验产物 mtime/体积（§2.3 坑 11）；③ `docs/rng-source-inventory.md` 的 `SYSTEM(3)`（35 处）整行**自 G02 起就是 stale**，本批只按因果摘掉 `RelativeGiftHandler` 一项，**整行须 G10 按 `grep -rn "RngPartition\."` 重跑盘点**，别做局部修补；④ 三组零消费者死代码归 G10 的「死代码 grep 清零」：`NullSafeProtoBuf.relationIdToProto/FromProto`（实测 **G15 之前就已零调用**，不是本批造成）、`NullableStringSerializer`/`NullableLongSerializer`、`DiffMonthSettlementFixture` 的两个死 helper |

**G03 登记摘要（逐条见 report-G03 §八）**：侦察结构性缺陷两处；B 类定性澄清（G10 工作量重估）；`ai_sect_ops.h`/`disciple_stats.h` 各一行 `rootCount = 1` 自赋值无效语句；`applyCombatInjury` 仍调 `markDead`（名=死亡/行为=重伤）+ 测试注释失实 + 测试弟子命名「阵亡者」；两处裸 `mock(GameRngManager)` 违反 `mockSmart`；`FakeAtomicStateStore` 未登记进 `rules/testing.md` 共享工厂表；「关系」对话框只剩师徒、改名待拍板；`prisonerSpiritRootFilter` UI 写入面残留；子代理 150 轮上限的工程事实。

**G04 登记摘要（逐条见 report-G04 §八，共 10 条）**：`DiscipleFactory.h` 头注释「技能 9 × gaussianInt」与实际 8 次不符（金序列重录时须核对）；`PatrolBattleSystem.applyVictoryRewards` 分区注释失真（SYSTEM vs EXPLORATION）；`ManualTalentRefRule` 删除后 manualIds 悬空清理语义是否并入 `ItemRefConsistencyRule` 需拍板；`ai_sect_recruit.h` 年龄注释失真 + `BeastMaterialDatabase` 四个零消费者辅助方法；`GameEngineWorldBattleOps.survivorIds` 已删 + 1781 事务评估=保留；🔴 **图集 manifest 契约「只写 map 精灵集」vs「全量列出」两条路线不得并存**（若改全量须同步 `AtlasManifestSyncTest.reproduceSpriteEntries` 并写 ADR）；`EffectKeyNames` 部分键失去生产引用；突破率 `inner/outerElderPositionBonus` 现恒 0（预案 recon §11.3）；**活文档（`docs/cpp-engine.md` 目录树 `trait_db` 行 + 序列化预算表血炼/三表行、`architecture.md`、`knowledge-base.md`、`CODE_WIKI.md`、`ui-read-surface.md`）仍描述已删玩法** → G10 文档收口；6 处注释残留「血炼」表述 → G10 注释七项检查。**另有 `FormulaService.calculateSuccessRateBonus` 死链：复核会话已按 detekt 判红整删（原 §八-5「保留」判定作废）。**

---

## 7. 剩余批次指针与已知阻塞

| 批次 | 内容 | 侦察 / 任务书 | 关键前置 / 阻塞 |
|---|---|---|---|
| ~~G04~~ | ~~删洗炼/资质/三表/血炼/职位特质/战斗随机成长~~ | ✅ **已收官**（report-G04.md） | — |
| **G15** | 师徒系统整体下线（赠礼三文件整删、`masterId` 列链、ActionId 1591 退役、Room v59、双乘区形参链、关系面板） | 🔴 **任务书真源已外移到 [`TASKBOOK-G15.md`](TASKBOOK-G15.md)**（本文件 §10 的 §10.2 文件面**已被其 §2 取代**，含 13 处漏项与 2 处假阳性）；报告 [`report-G15.md`](report-G15.md) | ⚠️ **实施完成、门禁已同轮实测判绿，但未提交**——须复核会话重跑 §2.2 全表后单次提交（§8.5 规程）。提交前 G16 不得开工（共享 `XianxiaApplication.kt`） |
| **G16** | 12 个角色精灵键注册（6 角色 × 头像/全身像） | **本文件 §11 内联任务书** | 源图在 `模拟宗门美术素材/`（不入库）；七步流程见 `rules/static-resources.md`；解除 G11 硬阻塞。🔴 须在 G15 提交后开工；🔴 图集批必须把 §2.3 坑 9 的三处手工复刻表列入文件面 |
| **G08** | 角色模板层 + `templateId` 实例化 + 开局周明/5 万 + 兑换码改道 | `recon-G05-G06-G08-G09.md` `G08`；口径 `HANDOVER-1` §4#9~#12 | 兑换码改道牵动 4 处测试 + 6 个死 helper（`HANDOVER-1` §5#6）；AI 宗弟子构造保持旁路 `templateId=""` |
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | 同上 `G09` | 硬依赖 G08 模板读取层（`characterTemplates` 当前零生产读取方）；C++ AUTHORITATIVE；入库来源名须同批登记 `OverflowMailSender.SOURCE_DISPLAY_NAMES` |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | 同上 `§G11` | 🔴 **素材注册硬阻塞**：12 个角色精灵键全未注册，源图在 `模拟宗门美术素材/<角色名>/{头像,全身像}.png`（该目录**永不提交**），七步流程见 `rules/static-resources.md`；色表强制 Q31（`GameConfig.Gacha` 单源），禁用 `ItemCard.getRarityColor` 旧色表 |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 + 文档收口 | `EXECUTION-PROTOCOL.md` §2 | 必须 G02–G07 全合入后**唯一一次**重录；G08/G09 建议同窗。**§6.3 已累积 G02/G03/G04/G05/G06/G15 六份登记**；Kotlin `Diff*` 预计无需重录（G15 已实证一次，仍待终树再证） |

---

## 8. 下次开工 checklist

### 8.A 🔴 第一优先：G15 的复核会话 + 单次提交（下一会话该做的是这个，不是开新批）

1. `git status` 应看到 **117 跟改 + 5 新增 + `docs/research/`×2**（后者永不提交）。
   若数字不符 ⇒ 有他会话在同一个工作树动过，**先停手报告**，不要在混合态上提交。
2. **同轮重跑 §2.2 全表**（不是信 `report-G15.md`，G04 的教训就是这条）：
   `cmake --build .` + `ctest` → `compileReleaseKotlin` → 五模块 + `:app` 测试源编译 →
   `pwsh -File scripts/build-desktop-jni.ps1`（**验 `.so` 体积变化**，坑 11）→
   `testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=…`（带 `--continue`、按 XML 逐模块汇总）→
   detekt 六模块 → `lintRelease` → 四个 node 门。
   报告里已把每条的实测值写清（§2.2 表），复核若拿到不同值，**以复核实测为准并回写报告**。
3. 三处高风险抽验点已列在 `report-G15.md` §十 末段（`battle_residual_tx.h` 赠礼段等价性、
   `requiredScalarFields`↔`ROW_SPECS` 双射守卫的判别力、`RoomMigrationSupport` 链尾 v40 判定）。
4. 抽验 **守卫判别力**（`feedback-audit-evidence-grading`）：挑 1–2 条本批"应当红"的删除面，
   退回旧实现确认对应用例真的判红（例如把 `requiredScalarFields` 的 `"masterId"` 加回去看
   `GameViewDiscipleProjectionTest` 是否红）。
5. `atlas-rgba-manifest.json` / `sprite-uid-map.json` 若被 `lintRelease` 改脏 → `git checkout --`。
6. 双 changelog 与 `report-G15.md` 已由实施会话写好（游戏内 +4/−3 含改写 G03 两条已失效条目；
   外部 +68/−0 新增 G15 段）→ 复核只需**核对文案与实测一致**，不要重写。
7. 单次提交（不含 `docs/research/`、不含构建副产物）；提交后把 §1 的 HEAD / 已完成改成
   **6 / 11**、下一批改 **G16**，并把本文件降级为「G15 已收官」指针。

### 8.B G16（角色素材批）——须等 8.A 提交落定

1. 读 §11 任务书 + `rules/static-resources.md` 七步 + `rules/media-source-assets.md`（素材源目录边界）。
2. 🔴 图集/生成物变更批必做：把 §2.3 **坑 9 的三处「手工复刻静态期望表」**
   （`scene_equivalence_test.cpp` rect 夹具、`SpriteAtlasDefGeneratedTest` 解析期望表、
   `BuildingSpriteFootprintGuardTest` 兜底尺寸表）**列入分片文件面**——它们只在 ctest/全量 JUnit 才暴露，
   编译与生成器都抓不到。
3. 开工前 `git status` 必须干净（G16 与 G15 共享 `XianxiaApplication.kt`）。
4. `sprite-uid-map.json` / `atlas-rgba-manifest.json` 是构建副产物（坑 2），提交前 `git checkout --`；
   本批要提交的是**转换后的 WebP 与注册代码**。

---

## 9. 诚实状态声明

- **M1 完成度：5 / 11 批已提交**（G02 / G05 / G06 / G03 / G04 门禁全实测绿并单次提交）；
  **G15 实施完成、全门禁同轮实测判绿、但未提交**（欠复核会话，见 §8.A）；
  G16 / G08 / G09 / G11 / G10 **尚未开始实施**（分母 11 = 原 9 批 + 拍板新增的 G15、G16）。
- 🔴 **G15 的诚实账（与 G04 的区别要说清）**：本报告 §2.2/§四/§五 与 `report-G15.md` 的**每个数字
  都是本会话同轮命令的输出原文**（ctest 两度跑、JUnit 按 XML 逐模块汇总、lint/detekt/node 门全部实跑），
  不含「应该通过」类自述；期间还抓到并纠正了 **3 处自身错误**：
  ① `RoomMigrationSupport.verifyDisciplesColumnsExist` 误判链尾版本（以为是当前版本，实为 v40），
     被 `RoomMigrationTest` 实测撞红后改回 `assertTrue`；
  ② `powershell` 跑 JNI 构建脚本失败却回报 exit 0，靠比对 `.so` 体积（8560640→8526336）才发现，
     换 `pwsh` 重跑；
  ③ 任务书初版把「分派区间起点迁移」的守卫后果写错（`isDispatchGap` 也接受 `UNKNOWN_ACTION`，
     起点不动守卫不会红），实施期反查后已就地校正 TASKBOOK §3.2-3 并登记。
  **仍按 §8.5 规程不自行提交**——判定权在复核会话。
- **已核实（G15，全部本会话同轮实测）**：ctest 1394/1391/3 且 3 败均为金序列/`kGoldenDigest` 断言（读 `LastTest.log`
  定性，非按家族归类）；JUnit 7336/0/0/18 skip 逐模块；detekt/lint 两 baseline 零改动；JNI 86/86；
  ActionId 198/maxId=1861 且 regen 幂等；catalog↔guard 退役集 22 双向零差集；
  game-data sha256 与 G04 逐字符相同；Room v59 `disciples` 90 列 / `game_data` 128 列 / 索引 5+5；
  历史 schema JSON 零改写；删除模式 grep 归零 + 保留清单 15 项命中数逐条贴证。
- **推测 / 未核实（G15）**：
  ① `GameViewDiscipleProjectionTest` 对 `requiredScalarFields`↔`ROW_SPECS` 的双射断言是否**双向**
  （漏删会不会红）未做退回旧实现的判别力自证 ⇒ 已列 §8.A 第 4 条交复核；
  ② `battle_residual_tx.h` 赠礼段摘除后 `beforeRealm` 收敛的语义等价性由子代理论证 + ctest 覆盖，
  主线程未独立做一次「退回旧实现对照数值」的实验；
  ③ 「G10 时 Kotlin `Diff*` 无需重录」现在是**有 G15 一次实证支撑的推论**，仍须 G10 终树再证。
- ✅ **原列 3 项待拍板已于 2026-09-24 全部关闭**（见 §1.3）：① 「关系」对话框 → 升级为**师徒系统整体下线**
  （G15 已实施；`RelationsDialog` 随 `DetailActionButtons.kt` 整文件删，对话框本身消失）；
  ② `comprehensionAdd` 经实测**是活字段** → 随悟性保留，G04 未删，G15 亦未动；
  ③ 素材 → **G16**，插在 G04 之后、G09 之前（任务书内联 §11）。另新增一项：**血炼池连建筑一起拆**（G04 已做完）。
- 🔴 **G15 新发现的、需用户拍板的一件事**（`report-G15.md` §八-13）：旧档 `social_masterId` 数据被**直接弃用**，
  没有任何补偿（已建立的师徒关系不折算灵石/好感）。本批按 P-1「连根拆掉」执行并给了结构性论证；
  若产品想要补偿口径，那是一次独立的经济设计，不在本批内。
- **远端未推送**；`docs/research/`×2 仍为未跟踪且不提交；G15 的 5 个新增文件同样未跟踪，等复核后随本批提交。
- 🔴 **G04 的诚实账**：实施会话（上一会话）已完成全量实施并写好报告，但**停在未提交状态**，且其报告把 **ctest / detekt / JUnit 三项记为绿——三项均不可复现**。本（复核）会话在同一棵树上逐门重跑，实测出 4 处失实并当场修复（3 条 `SceneEquivalenceTest` A 类红、6 条 detekt 红、2 个陈旧图集守卫、规模与 JUnit 计数失真），随后才提交。逐条根因与修复见 report-G04 §二·补。**结论：批次「实施完成」的判据是复核会话的同轮全门禁，不是实施会话的自述。**
- **为什么 G03 收在干净边界就停手**：G04 体量比 G03 更大（`aptitude` 单键 C++ 30+/Kotlin 60+/测试 200+ 命中），而 G03 的 5 个分片全部撞子代理轮次上限、靠主线程接管才收口；半开 G04 会把工作树留在编译不过的中间态。**G04 实际按 ≤10 文件/片切了 30 片，无一撞顶**；**G15 切 9 片（Wave-1）+ 4 片（测试 Wave-2），亦无一撞顶**（该规程两次有效）。
- **历史批次的核实状态**：G03 的 ctest / JUnit / detekt / lint / 生成器 / Room 数字为该会话同轮实测；**G04 的对应数字由复核会话同轮重跑取得**（ctest 1413/1410/3、JUnit 7397/0/0/18、detekt/lint EXIT=0、JNI 86/86、ActionId 198/1861 零漂移、game-data sha256、Room v58 逐列、退役集 21 双向一致）。
- 🔴 **素材/音频目录策略已变更**：`模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）自 2026-09-24 起是**项目指定的源资产目录**，登记于 `rules/media-source-assets.md`，但**按用户指令不入库**（已写进 `.gitignore`）。⇒ 换机器后这两个目录是空的，需项目外渠道同步。
---

## 10. G15 任务书 · 师徒系统整体下线（P-1 拍板产物，**内联即派工就绪**）

> 🔴 **本 §10 已被 [`TASKBOOK-G15.md`](TASKBOOK-G15.md) 取代为派工真源**（保留本节是为了留下
> 「上位交接的文件面为何不可直接派工」的对照证据）。差异集中在三处：
> ① **§10.2 的实测文件面漏 13 项**——最要紧的是 `battle_residual_tx.h` 是 relative_gift 的**第二个**消费方
> （照 §10.5 切片会直接 C++ 编译失败）、`models.h`/`json_codec.cpp`/`gameview_encode.cpp`/`game_view.proto`
> 三端环整环未列（违反铁律 6）、`GameCoreJni.cpp` 的 `op="masterDiscipleBonus"` 对拍端口、
> `disciple_stats.h` 两 input struct、`SocialData` 需**整类拆除**（含 `AssembleGroup.SOCIAL` 组）、
> `DiscipleStatsProvider` 四签名、`GameSystemRegistryDefaults` 注册行、`RelativeGiftSection` 死配置；
> ② **§10.2 误列 2 项假阳性**——`SectViewModel`/`ProductionViewModelElderOps` 的命中实为 `viceSectMaster`（副宗主）；
> ③ **§10.3 的分派区间迁移理由写错**——`isDispatchGap()` 也接受 `UNKNOWN_ACTION`，区间起点不动守卫不会红，
> 迁移的真实理由是语义正确性（TASKBOOK §3.2-3 已校正并给出证据）。
> 其余判据（§10.1 作废三条反向守卫、§10.4 连带面清单、§10.6 RNG）经实测**全部成立**，未改。
>
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
