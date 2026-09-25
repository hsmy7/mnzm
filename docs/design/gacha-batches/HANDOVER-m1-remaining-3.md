# M1 剩余批次交接（第三份）

> **本文件用途**：G03 收官时的完整交接 —— ① 已收官 4 批的实测基线；② 门禁数字与本轮新增的实操坑；③ 修订后的作业规程（分片粒度 / A·B 类判据）；④ 剩余批次落点指针与硬阻塞；⑤ G10 累积登记与**条目数复核差异披露**。
> **与前几份的分工**（四份各有职责，互不重复）：
> - [`HANDOVER-m1-remaining.md`](HANDOVER-m1-remaining.md) —— **批次清单 / 顺序理由 / 产品口径表 / 串行约束 / 待拍板项**的权威（§4 口径表现含新增口径 15、§5#1 已关闭）。
> - [`HANDOVER-m1-remaining-2.md`](HANDOVER-m1-remaining-2.md) —— G03 失败复跑任务书与三次实操规程；**其 §2 门禁基线已被本文件取代**（G06 数值已过时）。
> - [`TASKBOOK-G04.md`](TASKBOOK-G04.md) —— G04 派工细则（切片表、每片文件面、grep 口径纠正、前置扫描）。本文件不重复，只给指针。
> - [`TASKBOOK-G15.md`](TASKBOOK-G15.md) —— **G15 派工细则的唯一真源**。🔴 **本文件 §10.2 的实测文件面已被其 §2 取代**
>   （回查出 13 处漏项 + 2 处假阳性；§10 其余判据仍有效，两处冲突以 TASKBOOK-G15 为准）。
> - [`TASKBOOK-G16.md`](TASKBOOK-G16.md) —— **G16 派工细则的唯一真源**。🔴 **本文件 §11 的内联任务书已被其取代**
>   （§11 只有 6 行、无文件面无决策依据；实测差异与本批抓出的三处管线缺陷见该 TASKBOOK §2 与 `report-G16.md` §三）。
> - [`TASKBOOK-G08.md`](TASKBOOK-G08.md) —— **G08 派工细则的唯一真源**（13 片文件面 + 20 项决策 +
>   🔴 **上位交接 16 处失真清单**）。本文件 §8.D 已改为 G08 收官纪要；§8.D-5 原「Room → v60」那条**被实测作废**。
> - **本文件** —— 收官基线 + 修订后的作业规程 + 剩余指针。
> **更新时点**：**G08 收官（提交 `8b3c10578`）后**；§1/§2.2/§2.3/§6.2/§6.3/§7/§8/§9 已是 G08 值
> （§2.2 的上一列是 G16 值，G04/G15 值以括注保留；§11 的 G16 内联任务书与 §10 的 G15 内联任务书均已作废）。

---

## 1. 当前状态一页速览

| 项 | 值 |
|---|---|
| 分支 | `feat/gacha-m0-m1` |
| HEAD | **`8b3c10578`（G08 收官单次提交）**，其下 `fa853b541` = G16 回写、`4ba6c1bdd` = G16 收官、`acf745398` = G15 回写 |
| 工作树 | **干净**（非未跟踪残留 = 0；提交后 `git status --porcelain` 只剩 `docs/research/`×2） |
| 未跟踪 | 仅 `docs/research/`×2（与本批无关，**不提交**）。🔴 `模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）**已写入 `.gitignore`** —— 2026-09-24 拍板：作为项目指定素材/音频源目录，**只登记不入库**；位置与边界见 `rules/media-source-assets.md`。**旧表述「未跟踪 4 组永不提交」自本文件起作废**。⚠️ **G16 起该目录是素材管线的硬依赖**：`scaffold-source-mapping.mjs` / `import-art-assets.mjs` 经 `android/scripts/art-source.mjs` 解析到它，**缺失即抛错**（不再静默产出全 null 映射），换机器后须先同步素材或用 `MNZM_ART_SOURCE` 指向 |
| 已完成 | **8 / 11 批**（G02、G05、G06、G03、G04、G15、G16、**G08**；分母 11 = 原 9 批 + 拍板新增 G15、G16） |
| 下一批 | **G09**（抽卡核心 `gacha_tx`：roll / 保底 / 碎片 / 升星 / 解锁 / 入库）。硬前置已由 G08 解除——`CharacterTemplateDb` 现在有生产读取方（`instantiateTemplate` 与开局链），碎片入账口 `GachaFacade.grantFragments` 与 C++ `addFragment` 已单点收敛（§8.E）。🔴 **开工前先写 `TASKBOOK-G09.md`**（§8.C-1 规程） |
| 远端 | **未推送**（本仓多会话共用一棵工作树，推送由用户指令决定） |

### 1.1 已收官批次（门禁值以各批报告实测为准）

| 批次 | 提交 | 报告 | 规模 | ctest | JUnit | 其他要点 |
|---|---|---|---|---|---|---|
| G02 | `5dbaac1e3` | [report-G02.md](report-G02.md) | 443 文件 +10775/−26845 | 1532/1536→4 B 类 | 7893/0 | Room v54→v55 |
| G05 | `8e593a71b` | [report-G05.md](report-G05.md) | 143 文件 +5624/−8401 | 1487/1483/4 | 7720/0 | JNI 89→86；v55→v56 |
| G06 | `e1a69d8e9` | [report-G06.md](report-G06.md) | 42 文件 +179/−1309 | 1483/1479/4 | 7709/0 | ActionId 1590/1740 退役 |
| **G03** | `cc66d7918` | [report-G03.md](report-G03.md) | **171 文件 +6084/−5186** | **1470/1467/3** | **7662/0** | ActionId 1592/1750 退役；**Room v56→v57（3 表 11 列）**；lintRelease 绿 |
| **G04** | `4078f12c9` | [report-G04.md](report-G04.md) | **364 文件 +6448/−24737**（整删 48） | **1413/1410/3** | **7397/0/0/18 skip** | ActionId 1613/1614/1615/1616/1732/1733/1746（+1437 顺延）退役，retired 集 13→**21**；**Room v57→v58（2 表 9 列）**；game-data 三键整个消失 sha256 `035066cb…94ef`；detekt/lint/图集门全绿 |
| **G15** | `325da9d5a` | [report-G15.md](report-G15.md) | **122 文件（117 跟改 + 5 新增）+426/−3850 跟改侧**（整删 9） | **1394/1391/3** | **7336/0/0/18 skip** | 师徒整体下线；ActionId **1591** 退役，retired 集 **21→22**（catalog↔guard 双向零差集已实测）；**Room v58→v59（disciples 1 列，91→90）**；`DiscipleColumn` 枚举 92→91；proto 双侧 `reserved 93` + 镜像 `reserved 85`；赠礼三文件整删（消费方 **2** 个）；JNI 86/86、game-data sha256 **不变**、lint 36 警告全预存、两 baseline 零改动 |
| **G16** | `4ba6c1bdd` | [report-G16.md](report-G16.md) | **42 文件（14 改 + 28 新增）+1195/−62**（24 份 WebP 为二进制不计行） | **1394/1391/3**（**与 G15 逐条同名 ⇒ 零新增 B 类**） | **7343/0/0/18 skip**（对 G15 **+7 = 新守卫 5 例 + 映射守卫 +2 例**，逐模块账闭合） | 寻访 12 键四方齐备（registry 分类 **14→15**、`SpriteCategory.CHARACTER(2)`、UID **493–504** 既有零漂移、双模块 24 份无损 WebP 单模块 4.08 MB）；🔴 **素材管线三处结构性缺陷根治**（`SOURCE_DIR` 失效路径 / 脚手架静默丢映射 / preserve 承载高清素材）；图集 **41 精灵 / layoutHash `6a122ed2…` 不变**、`SceneEquivalenceTest 13/13`、Room 仍 **v59**、game-data sha256 **逐字符不变**、JNI 86/86、ActionId 198/1861 幂等、lint 36 警告全预存、两 baseline 零改动 |
| **G08** | `8b3c10578` | [report-G08.md](report-G08.md) | **89 文件（72 改 + 17 新增）+5386/−1038**（实测分类：`src/main` Kotlin 43 / `src/test` 19 / C++·cmake 17 / 脚本·数据·文档 10） | **1401/1398/3**（**与 G16 逐条同名 ⇒ 零新增 B 类**；`DeterminismProbe actual=0xb4f3c6912207f597` 不变） | **7391/0/0/18 skip**（683 XML；对 G16 **+48** = 5 新测试类 + 改道新增，逐模块账闭合） | 模板层 `CharacterTemplateDb` 六行镜像；`recruitDisciple` → `instantiateTemplate`；兑换码/邮件**不再直造弟子**、改经 `GachaFacade.grantFragments`；开局=周明+5 万灵石+1 星账本；ActionId **198→199 / maxId 1861→1870**，retired **22→24** 双向零差；🔴 **PortraitPool 解析链缺口根治**（`PortraitResolver` 两跳，10 处读点/7 文件）；**四轮守卫判别力自证实跑**；Room 仍 **v59**（`templateId` 自 v54 在库，上位「→v60」是失真）；图集 41/`6a122ed2…` 不变、game-data sha256 不变、JNI 86/86、两 baseline 零改动、detekt 六模块 0 error |

> ✅ **G15 提交前由同一会话以复核身份同轮重跑了全部门禁**（ctest / 全量 JUnit / detekt / lint / 四个 node 门），
> 数值与本表一致；`report-G15.md` §十 诚实标注了两处降级（lint 与编译的复核轮是 UP-TO-DATE 增量）
> 与一处未做的抽验（`GameViewDiscipleProjectionTest` 双射守卫的判别力自证）。
> ✅ **G16 是「实施 + 复核合一」的会话**：每个数字都取自本会话同轮命令输出（ctest 三度跑含 `-R` 定性与
> 判别、JUnit 全量按 678 个 XML 逐模块汇总、detekt/lint/五个 node 门全部实跑），
> 并对**自己新写的两处守卫做了「退回旧状态判红」的判别力自证**（做法与结果见 `report-G16.md` §五）；
> 未做项只有 APK 构建与真机验证，明写在 `report-G16.md` §八。
> ✅ **G08 同为「实施 + 复核合一」**：终树组合门（编译 + 全量 JUnit + detekt + lint）**跑了两次**——第一次跑完
> 才发现「拆分后类内残留同名私有成员 = 静默死码」（§2.3 坑 16），删成员版后整条组合门重跑，
> 表内三项数字取自第二次（终树）；守卫判别力**四轮实跑**（藏模板行 / 只改 C++ 常量 / 撤白名单一条 /
> 删解析第二跳），判红消息原文见 `report-G08.md` §五。
> **八批已提交者均为：全门禁实测绿 + 双 changelog + `report-Gxx.md` + 单次提交。**

> ⚠️ **G04 的提交由「复核会话」完成**：实施会话留下未提交工作树 + 一份把 ctest/detekt/JUnit 误记为绿的报告；
> 复核会话在同一棵树逐门重跑，实测出 **4 处失实**（3 条 `SceneEquivalenceTest` A 类红、6 条 detekt 红、2 个陈旧图集守卫、规模数字失真）并当场修复。
> 教训已回写 report-G04 §二·补 与本文件 §2.3 坑 9 —— **「报告声称绿」不构成门禁证据，必须同轮复跑**。

### 1.2 剩余顺序（**2026-09-25 G08 收官后**）

**G09 → G11 → G10**

- ✅ **G08 已单次提交（`8b3c10578`）⇒ G09 的两条硬前置解除**：① `characterTemplates` 不再是「零生产读取方」
  （`DiscipleService.instantiateTemplate` + 开局链读 `CharacterTemplateDb`）；② 碎片入账口已单点收敛
  （C++ `gacha_fragment.h::addFragment` + Kotlin 回退臂 `GachaFragmentLedger` + 门面 `GachaFacade.grantFragments`），
  G09 的 roll/保底/升星直接在此之上写，**不得再开第二个碎片写者**。
  🔴 另：`report-G16.md` §七-1 的 PortraitPool 解析链缺口已由 G08 的 `PortraitResolver` 根治（§7 指针表已更新）。
- ✅ **G16 已单次提交（`4ba6c1bdd`）⇒ G11 的「12 个角色精灵键未注册」硬阻塞解除**：
  注册表 / 映射 / 双模块 WebP / UID 四环齐备，运行时入口 `SpriteResRegistry.resolve("avatar_zhouming")` 等
  12 键可解析，且新增 `GachaCharacterSpriteGuardTest` 锁住四方一致（详见 `report-G16.md` §一）。
  ⚠️ **G08 追加一条 G11 必办项**：`portraitRes` 现承载 1024 档全身像，而 512 档 `avatar_<id>` **仍零消费方** ⇒
  小头像位改读 `avatarKey` 属 G11 显示尺寸口径（`report-G08.md` §七-7）。
- ✅ G15 已单次提交（`325da9d5a`），共享面（`models.h` / `disciple_store.h` / `column_dirty.h` /
  `DiscipleTables*.kt` / `Disciple.kt` / `game_view.proto` / `ActionIds.kt`）已落定 ⇒ 后续删列批可在此基线上串行。
- **G08 之后新增的共享面**（G09 必须串行编辑，禁并行分片触碰）：
  `gamecore/system/gacha_fragment.h`、`src/dispatch_gacha.cpp`（G 批独占端口 1870 段）、
  `ActionIds.kt`/`action_ids.h`（生成物）、`scripts/action-catalog/gacha.mjs`、`GachaFacade(-Impl).kt`、
  `GameEngineLoadDataOps.kt`（三臂）、`test/gacha_tests.cmake` 与两份**手写**桌面 JNI 源清单。
- **G09 → G11 是依赖链**（G11 依赖 G09 的抽卡结果 DTO + G16 的素材 + G08 的模板层）。
- G10 最后（唯一一次 RNG 重录窗口）。**G15/G16/G08 三轮均零新增 B 类**（§6.2），且 G15 首次正面实证
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

## 2. 门禁基线与运行方式（**G08 终树实测，取代本文件初版的 G04 值与 G15/G16 列**）

> 🔴 G08 列的值由**实施会话同轮**跑出的原始输出（G08 未另派复核会话，但按 §8.C-4 做了
> 「终树同轮重跑 + 读每条命令自己的日志 + 验产物 mtime/体积」，并做了**四轮**守卫判别力自证，见 `report-G08.md` §五；
> 组合门因 §2.3 坑 16 跑了两轮，表内数字是**第二轮（终树）**）。
> G16/G15 值由各自实施会话同轮跑出（G15 提交前另经复核会话同轮重跑）；G04 值由复核会话跑出。下表可作下一批复核的对照基准。

### 2.1 环境前置

```powershell
# C++ 桌面构建：PATH 前置三段（缺一不可，缺 llvm-mingw bin 会 STATUS_DLL_NOT_FOUND）
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;" +
            "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;" +
            "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;" + $env:PATH
# 桌面构建目录：android/app/src/main/cpp/gamecore/build/desktop-test （不是同级 build/，那是旧套件）
# 素材管线（G16 起为硬依赖）：仓库根的 模拟宗门美术素材/ 必须存在，或用 MNZM_ART_SOURCE 指向
```

### 2.2 门禁表

| 门 | 命令 | G08 终树实测值（G16 / G15 / G04 值沿革） |
|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（desktop-test 目录） | `[9/9] Linking CXX executable test\game-core-tests.exe`（**本批必跑**：G16/G15 有零 C++ 改动的先例，G08 新增 `gacha_fragment.h`/`dispatch_gacha.cpp` 进构建图）。🔴 判别力旁证：注入常量并还原后**仍触发重链接**且 ctest 同值 ⇒ 还原是行为等价 |
| 桌面 ctest | `ctest`（同目录） | **1401 总 / 1398 过 / 3 败**，`Total Test time (real) = 58.64 sec`（G16 为 1394/1391/3、G04 1413/1410/3）。**+7 = 新增碎片 9 例 + 工厂 2 例 − 兑换码 4 例**。3 败与 G16 **逐条同名** ⇒ 零新增 B 类；断言原文实测为金序列常量 ⇒ 按 §3.3 属 B 类 |
| 🔴 `SceneEquivalenceTest`（坑 9） | `ctest -R SceneEquivalence` | **13/13 全绿**（本批不动图集；图集/生成物变更批必查项） |
| 🔴 `DeterminismProbe` | `ctest -R Determinism` | `actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`——**与 G15/G16 逐字符相同**；`DigestIsStableAcrossRepeatedRuns` **Passed** ⇒ 确定性未坏，坏的只是金常量 |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | 含在下一条的组合门内，`BUILD SUCCESSFUL in 26m 39s` |
| 测试源编译 | 六模块 `compileReleaseUnitTestKotlin` | 0 错误（隐含在组合门；🔴 本批在此**真红过一次**：`:app` 测试源 `fold` 的类型推断失败——编译错误只在日志 `^e:` 行，后台任务退出码仍 0，坑 5 复现） |
| detekt | 六模块 `./gradlew.bat detekt` | `BUILD SUCCESSFUL`；六份 `detekt.xml` 的 `<error>` 计数**全 0**；`detekt-baseline.xml` 与 `lint-baseline.xml` **零改动**（只缩不增）。🔴 **坑 13 修正**：`detekt` **确实覆盖 `src/test`**（本批两条测试源码违规就是它报的），只是**免类型解析**——需要类型解析的规则仍要另跑 `:<模块>:detekt<Variant>UnitTest` |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<绝对 .so>"` | **7391 / 0 / 0 / 18 skip**（app **1010** / domain **1571** / data **814** / engine **2903** / ui 146 / feature:game **947**；**683** 个 XML 汇总）。G16 为 7343（app 1002），**+48 逐模块对账闭合**。组合门整体 `BUILD SUCCESSFUL in 26m 39s` |
| lint | `./gradlew.bat lintRelease` | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`、**0 errors**——**36 与 G15/G16 同值** ⇒ 全预存。⚠️ 跑完仍要查 `atlas-rgba-manifest.json`（本批两轮各脏一次，坑 2） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `…/desktop-jni/libgamecorejni.so` | **本批必重建**：`.so` **8526336 → 8542208 字节 / mtime 20:57**（坑 11 用作正面证据）。🔴 两份 `scripts/build-desktop-jni.{ps1,linux.sh}` 是**手写源文件清单**，漏 `src/dispatch_gacha.cpp` 会让桌面对拍 `.so` undefined symbol、**全部 `Diff*Test` 红**（G08 同批补，并补预存遗漏的 `src/data_store.cpp`） |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86**（不变；新事务复用 `tryExecuteNative`，零新增 `external fun`） |
| ActionId | `node scripts/gen-action-ids.mjs` | **199 动作 / maxId=1870**（G16 为 198/1861）；新增 1870 = G 批独占空段的有意递增，1436/1438 为退役标注改 desc。零漂移判据 = regen 前 `cp` 两份、regen 后**与工作树自比**（坑 12）→ `REGEN_IDEMPOTENT=yes` |
| catalog↔guard 退役集 | 脚本扫 `scripts/action-catalog/*.mjs` + `dispatch_guard_test.cpp` 的 `std::set<int32_t> retired` | catalog **199 条、唯一 199**；退役标注 **24** == guard **24**，双向零差集（G15 为 22）。🔴 **坑 17**：guard 侧退役集是 `action::NAME` 形态，按 `"NAME"` 带引号 grep 会得到「命中 0」的假红 |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`——**与 G04/G15/G16 逐字符相同**（G08 未触碰配置中性源） |
| 素材管线 | `node scripts/scaffold-source-mapping.mjs` → `import-art-assets.mjs` → `resource-manifest.mjs` → `build-atlas.mjs --codegen` | 本批**零 drawable 变更** ⇒ 仅跑 `--codegen`：`显示尺寸保真校验通过：18 栋建筑 + 9 个装饰`、`SpriteRegistryData.kt / TextureAtlas.h 内容未变化，跳过生成`；`sprite-uid-map.json` / `sources-imported.json` 零变化（G16 的 UID 契约未被触碰）。⚠️ 该脚本 cwd = `android/`，`gen-action-ids.mjs` cwd = 仓库根，串在一条复合命令里会让前者 `ENOENT`（坑 4 变体） |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | `✓ 规范分发架构门禁全部通过`（EXIT=0）；预存告警 **2 条**（规则③ 1 处、规则⑤ 最坏链路 37890 字节）——与 G16 同值（坑 14） |
| Room | `DATABASE_VERSION` = **59**；`disciples` **90 列**、`game_data` 128 列 | **G08 零迁移**（🔴 上位 §8.D-5「动 `templateId` ⇒ v60」是失真：该列自 **v54** 在库，`GameDatabaseMigrationsV54.kt:21`）；`schemas/59.json` 零改写。**下一批删列则 → v60** |
| 图集不变量 | `android/app/src/main/assets/atlas/atlas-manifest.json` | `sprites=41 / format=ASTC_4x4_LDR / layoutHash=6a122ed22d66bee5`（与 G04/G15/G16 一致 ⇒ 图集零变化） |
| 精灵注册面 | `SpriteCodegenSyncTest` / `SpriteSourceMappingGuardTest` / `ResourceManifestCompletenessTest` / `GachaCharacterSpriteGuardTest` / `PortraitResolverTest`（新） | 全绿。分类仍 **15**；G08 给 `GachaCharacterSpriteGuardTest` 加第 6 例（12 键经**生产注册入口 + 运行时 resolver**可解析），并新增 `PortraitResolverTest` 的**消费点台账**（读点必须经 `resolvePortraitResId`；直引 `PortraitPool` 的白名单只剩 resolver 本体 + `ResourcePreloader`） |

### 2.3 十八条实操坑（前三条承自 HANDOVER-2，4-8 条 G03 新增，第 9 条 G04 新增，10-12 条 G15 新增，13-15 条 G16 新增，**16-18 条 G08 新增**；第 13 条结论已由 G08 修正）

1. **KSP 会就地改写历史 schema JSON**：bump 版本后若 KSP 把 `56.json` 等历史快照改小，立即 `git checkout -- <该 json>`；只允许新增当前版本 JSON。（G03 实测：仅 `57.json` 新增，历史零改写。）
2. **构建副产物必须还原**：`sprite-uid-map.json`、`atlas-rgba-manifest.json` 每次构建都改时间戳 → 提交前 `git checkout --`。⚠️ **`lintRelease` 也会改**（G16 再证实：`atlas-rgba-manifest.json` 的 `generatedAt` 一行又被写脏一次）。
   🔴 **但素材批（G16 修正）必须先分辨「时间戳脏」与「真新增」**：新增/删除 drawable 时
   `sprite-uid-map.json`（+UID）、`sources-imported.json`（+源 MD5/尺寸）、`source-mapping.json`（+映射）
   三份是**必须随批提交的真源数据**，无脑 `git checkout --` 会丢 UID 稳定引用契约、并让下次 import 误判重烘焙。
   判据 = `git diff --numstat` 看该文件是不是只有时间戳那一行变化；细则已写进 `rules/media-source-assets.md` §5。
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
13. 🔴 **G16 新坑：`detekt` 门禁不覆盖测试源码**。`:app` 的 `app/build/reports/detekt/detekt.xml` 里
    `src/test` 命中数 = **0**（实测），即 AGENTS.md §2 的 `./gradlew.bat detekt` 对测试文件是空跑；
    改用带类型解析的 `:app:detektReleaseUnitTest` 立刻暴露 **39 条 weighted issues**（全为既存）。
    ⇒ 写测试时不能以「detekt 绿」自证质量；需要时用 `:<模块>:detekt<Variant>UnitTest` 自查并按文件过滤
    （是否把它纳入门禁属工程口径，已登记 G10）。
    🔴 **G08 修正本条结论**：「不覆盖 `src/test`」过强——`detekt.xml` **确实会报 `src/test` 的规则**
    （G08 的 `NestedBlockDepth`/`TooManyFunctions` 两条测试源码违规就是 `./gradlew.bat detekt` 报出来的）。
    G16 实测到 0 的原因是**免类型解析**：`detekt` 主任务不做类型求解，故那 39 条依赖类型的规则看不见，
    而不是整个测试源不在射程。⇒ 正确口径：`src/test` 在射程内但**只有免类型规则生效**；
    需要类型解析的规则仍须 `:<模块>:detekt<Variant>UnitTest`。
14. 🔴 **G16 新坑：写 rules/docs 时裸文件名会把规范门禁的「规则③ 不精确」计数顶高**。
    本批新写两行引用（`source-mapping.json`、`game-data.json`）把该计数从 **1 处顶到 3 处**，
    而门禁本身仍是 `✓ 全部通过`（**告警不是红，极易漏看**）。判据是**「几处」这个数目不得因本批增长**；
    修法见 `docs/AGENTS.md` 引用路径规范第 1 条（写可解析的全路径）。
15. 🔴 **G16 新坑：素材管线的三个静默失败面**（任一处都会被"编译 + 既有守卫全绿"放过）——
    ① **源目录路径**：两脚本曾硬编码 `D:/模拟宗门美术素材`，该路径本机不存在 ⇒ 管线自 2026-09-24 拍板
    「源目录=仓库根」起实际不可跑，`scaffold` 的 `if (fs.existsSync(SOURCE_DIR))` 让缺失**静默**变成
    「全部 source = null」，`import` 对 null 又直接 `continue` ⇒ 一直到下次重烘焙才暴露。
    ② **脚手架整体重写会冲掉人工映射**：`map_rock_base` 整条丢弃、`map_grass_1` 因表内目录记错退化为 null。
    ⇒ 现统一走 `android/scripts/art-source.mjs`（缺失抛错 + `MNZM_ART_SOURCE` 覆盖），
    `MAP_KNOWN` 补真源并支持可选 `modules`，`scaffold` 加 `assertNoMappingLoss()`；
    **要改映射只能改脚手架的表，不能手补生成物**（已写进 `rules/static-resources.md` §2.2 步骤 4）。
    ③ **分类默认 preserve**：`scaffold` 未登记分类的 bake 兜底是 `preserve`，
    而角色源图实测最大 `5016×5016` / `4096×6144` ⇒ `MAX_BAKE_DIM=4096` 夹完后单张解码 ARGB ≈ 67 MB。
    现 `SpriteSourceMappingGuardTest` 要求**每个分类必须显式登记档位**（漏登记即红），
    `import-art-assets.mjs` 对 >3 MB 或顶到 4096 的产物高声告警。
16. 🔴 **G08 新坑：把函数「拆出去」后，旧类里的同名成员不会被任何工具发现是死码**。
    G08 为消 `TooManyFunctions` 把 `RedeemCodeService` 的碎片入账 4 个函数搬到同包 `RedeemCodeFragmentOps.kt`，
    **漏删了成员版 `grantFragmentRewards`** ⇒ Kotlin **成员优先于扩展**解析，调用点照旧绑成员、扩展版成了死码。
    编译绿、detekt 绿（函数数 19 已在阈值 20 内，连 `TooManyFunctions` 都不响）、JUnit 全绿——**三道门无一能发现**。
    发现方式：用 `grep -cE "^    (private |internal |suspend )*fun "` 数类内函数数，与 detekt 报的 `with '22' functions`
    对账（22−4 应为 18，实测 19 ⇒ 少删一个）。
    ⇒ 搬迁/拆分片收尾必做**「旧位置按名字逐个 grep 清零」**，并把「函数数对账」写进该片自检项。
17. 🔴 **G08 新坑：`dispatch_guard_test.cpp` 的退役集不是字符串字面量，按带引号 grep 会得到「命中 0」的假红**。
    该集形如 `const std::set<int32_t> retired = { action::REDEEM_RESOLVE_AGE_LIFESPAN, //1436 … };`，
    首轮核对用 `/"([A-Z0-9_]+)"/` 扫 ⇒ 判成「catalog 24 条 guard 全缺」，几乎误登一条"契约破了"的债。
    ⇒ 正确判据 = 先切出 `std::set<int32_t> retired = {` 到 `};` 的块，再取 `action::([A-Z0-9_]+)`；
    与 catalog 侧 `desc` 含「已退役」的条目做**双向差集**（G08 实测 24 == 24 双向零差）。
18. 🔴 **G08 新坑：守卫断言的期望值类型必须与被测字段类型一致，否则永久判红且看不出差异**。
    `assertEquals(emptySet(), dangling)` 中 `dangling` 是 `List` ⇒ 两侧打印都是 `[]`，但断言恒红，
    读报告的人会误判为「有悬挂引用」。改 `emptyList<String>()` 即绿。
    ⇒ 通用口径：写守卫断言前**先确认被测表达式的实际类型**（`List` / `Set` / `Map` / 数值宽度），
    期望值按同一类型构造；跨类型比较要显式转换（`.toLong()` / `.toSet()`），不要依赖 JUnit 重载。

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
- [ ] **素材批专用（G16 起）**：`scaffold-source-mapping.mjs` 跑两次验幂等 + 用脚本比对「重生成后对 HEAD 的净差异」
  确认既有条目零丢失零改写；`import-art-assets.mjs` 先 `--dry-run` 再看「将生成」清单是否只含本批目标；
  `sprite-uid-map.json` / `sources-imported.json` / `source-mapping.json` 属**真新增须提交**（坑 2 修正），
  `atlas-rgba-manifest.json` 等时间戳脏须还原；产物逐张验 `VP8L` 无损
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

### 6.2 当前 B 类登记（C++，**G08 终树实测：仍是 3 条，逐条同名 ⇒ 零新增**）

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
> ⚠️ `DeterminismProbe` 的 golden 摘要自 G02 起未再变（G15 实测 `actual=0xb4f3c6912207f597`；
> **G16 同轮复测 `FP determinism transcript diverged from golden baseline. actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`——actual 与 G15 同值**；
> **G08 三轮复测同一 `actual`，且 `DigestIsStableAcrossRepeatedRuns` 同轮 Passed**）
> ⇒ 该指纹对师徒面删除不敏感，G10 重录一次到位即可。
> 🔴 **G08 零新增 B 类的因果链**（report-G08 §六）：本批 C++ 改动**不触碰任何 RNG 消费点**——
> `disciple_factory.h` 只是把既有的一次肖像掷点包进 `if (portraitResOverride.empty())`
> （空键路径的调用次数与顺序逐字节不变，模板路径**少**一次但只在具名角色分支），
> `gacha_fragment.h` / `dispatch_gacha.cpp` 是纯账本运算（零 `rng` 引用，`RngSourceGuardTest` 同轮复核）。
> 三条旁证：`actual` 逐字符不变、金序列两例实际值仍为 G16 记录常量、ctest 总数 +7 全部可归因到用例增减。
> 🔴 **并且**：派工期间有子代理建议「G08 顺手把三条红重录为绿」——**已按 §6.1 红线否决**，
> 否则 G10 的「唯一一次重录窗口」会被污染成两个。
> 🔴 **G16 零新增 B 类（比 G15 更强的"零"，因果链极简）**：本批**零 C++ 改动**（`git status` 的 M 列表内
> `gamecore/**` 命中 0）、**零配置源改动**（`gen-game-data --check` sha256 逐字符不变）、
> Kotlin 侧只加了一枚 `SpriteCategory.CHARACTER` 枚举值 + 一处 KDoc，**RNG 消费点集合完全未动**
> ⇒ ctest 三条红与 G15 逐条同名，且 `ctest -R` 复核到断言原文为金序列常量
> （`Which is: "male_disciple_15"` / `56 / 73 / 80 / 57 / 35 / 28 / 48 / 43`）⇒ 按 §3.3 判据是 B 类，
> Kotlin 侧 0 条。**素材/文档类批次的"零新增"应当这样证：先证改动面不含 RNG 消费点，再证三条同名同因。**

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
| **G16** | **「G10 / 后续登记」7 条** | [report-G16.md](report-G16.md) §七。**最要紧的三条**：① 🔴 **`PortraitPool.getResourceId` 不查 `SpriteResRegistry`**（只枚举 `male/female_disciple_*`）⇒ G08 把 `portraitRes` 置为 `portrait_zhouming` 后，弟子卡/详情页会回落**通用像**——该缺口属 **G08 的解析链**，不在素材批内，G08 任务书必须列一行；② 🔴 **全仓没有「双模块放置」硬守卫**（`ResourceManifestCompletenessTest` 实为并集 + `count >= 1`），37 张通用肖像等 **61 个单模块资源**靠「没断言」放行，是否收债待拍板；③ 🔴 **`背景图/普通招募背景图.png` 源图已被美术换成 6144×3456** 而该条目仍是 `preserve` ⇒ 重烘焙静默产出 4096×2304 / 6.8 MB（ARGB 37.7 MB）。本批**未夹带**该美术升级（产物与台账已还原），只加了超预算告警；**档位重定属产品口径待拍板**。另：④ 🟡 `detekt` 门禁不覆盖 `src/test`（更严档实测 39 条既存违规，见坑 13）；⑤ 第 7 角色 `月城雪/` 缺头像属 M3 |
| **G08** | **「G10 / 后续登记」8 条** | [report-G08.md](report-G08.md) §七。**最要紧的三条**：① 🔴 **坑 13 的口径要改**——`./gradlew.bat detekt` **确实覆盖 `src/test`**（本批两条测试源码违规即由它报出），G16 看到的 0 命中是**免类型解析**所致，不是"测试源不在射程"；与 G16-④/⑦ 合并处置（要不要把 `detekt<Variant>UnitTest` 纳入门禁仍待拍板）；② 🔴 **`predefinedCodes` 置空后兑换码只剩服务端下发一条活路**（无 RemoteConfig 绑定、无后台对接），要不要留本地种子码属运营口径 → **G12 待拍板**；③ 🔴 **档位错配移交 G11**：`portraitRes` 承载 1024 档全身像进 40~56dp 头像位，而 512 档 `avatar_<id>` 在本批之后**仍零消费方**。另：④ `RngSourceGuardTest` 的两组登记计数随删除漂移（14→1 / 19→12），与 G15-③ 的 `rng-source-inventory.md` 整行重跑同批做；⑤ `DiscipleFacade.addDisciple`→`DiscipleLifecycleManager.addDisciple` 生产零调用（已作 `LEGACY_CRUD` 登记并注明禁止新增调用方）；⑥ `scripts/split_engine/specs/redeem_code.json` 函数清单继续失真 |

**G03 登记摘要（逐条见 report-G03 §八）**：侦察结构性缺陷两处；B 类定性澄清（G10 工作量重估）；`ai_sect_ops.h`/`disciple_stats.h` 各一行 `rootCount = 1` 自赋值无效语句；`applyCombatInjury` 仍调 `markDead`（名=死亡/行为=重伤）+ 测试注释失实 + 测试弟子命名「阵亡者」；两处裸 `mock(GameRngManager)` 违反 `mockSmart`；`FakeAtomicStateStore` 未登记进 `rules/testing.md` 共享工厂表；「关系」对话框只剩师徒、改名待拍板；`prisonerSpiritRootFilter` UI 写入面残留；子代理 150 轮上限的工程事实。

**G04 登记摘要（逐条见 report-G04 §八，共 10 条）**：`DiscipleFactory.h` 头注释「技能 9 × gaussianInt」与实际 8 次不符（金序列重录时须核对）；`PatrolBattleSystem.applyVictoryRewards` 分区注释失真（SYSTEM vs EXPLORATION）；`ManualTalentRefRule` 删除后 manualIds 悬空清理语义是否并入 `ItemRefConsistencyRule` 需拍板；`ai_sect_recruit.h` 年龄注释失真 + `BeastMaterialDatabase` 四个零消费者辅助方法；`GameEngineWorldBattleOps.survivorIds` 已删 + 1781 事务评估=保留；🔴 **图集 manifest 契约「只写 map 精灵集」vs「全量列出」两条路线不得并存**（若改全量须同步 `AtlasManifestSyncTest.reproduceSpriteEntries` 并写 ADR）；`EffectKeyNames` 部分键失去生产引用；突破率 `inner/outerElderPositionBonus` 现恒 0（预案 recon §11.3）；**活文档（`docs/cpp-engine.md` 目录树 `trait_db` 行 + 序列化预算表血炼/三表行、`architecture.md`、`knowledge-base.md`、`CODE_WIKI.md`、`ui-read-surface.md`）仍描述已删玩法** → G10 文档收口；6 处注释残留「血炼」表述 → G10 注释七项检查。**另有 `FormulaService.calculateSuccessRateBonus` 死链：复核会话已按 detekt 判红整删（原 §八-5「保留」判定作废）。**

---

## 7. 剩余批次指针与已知阻塞

| 批次 | 内容 | 侦察 / 任务书 | 关键前置 / 阻塞 |
|---|---|---|---|
| ~~G04~~ | ~~删洗炼/资质/三表/血炼/职位特质/战斗随机成长~~ | ✅ **已收官**（report-G04.md） | — |
| **G15** | 师徒系统整体下线（赠礼三文件整删、`masterId` 列链、ActionId 1591 退役、Room v59、双乘区形参链、关系面板） | ✅ **已收官 `325da9d5a`**（[`TASKBOOK-G15.md`](TASKBOOK-G15.md) 派工 + [`report-G15.md`](report-G15.md)）——本文件 §10.2 的实测文件面**已被 TASKBOOK §2 取代**（13 处漏项 + 2 处假阳性） | — |
| **G16** | 12 个角色精灵键注册（6 角色 × 头像/全身像） | ✅ **已收官 `4ba6c1bdd`**（[`TASKBOOK-G16.md`](TASKBOOK-G16.md) 派工 + [`report-G16.md`](report-G16.md)）——本文件 §11 的内联任务书**已被 TASKBOOK-G16 取代**（§11 无文件面无决策依据；实测差异与三处管线缺陷见 report §三） | — |
| **G08** | 角色模板层 + `templateId` 实例化 + 开局周明/5 万 + 兑换码改道 | ✅ **已收官 `8b3c10578`**（[`TASKBOOK-G08.md`](TASKBOOK-G08.md) 派工 + [`report-G08.md`](report-G08.md)）——`recon-G05-G06-G08-G09.md` 的 `G08` 段落与 `HANDOVER-1` §4#9~#12 口径**已被 TASKBOOK §2/§3 取代**（上位 16 处失真，含「Room v59→v60」实为**零迁移**）；🔴 §七-1 的 PortraitPool 解析链缺口**已根治**（`PortraitResolver` 两跳） | — |
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库） | `recon-G05-G06-G08-G09.md` `G09` | ✅ **G08 已交付模板读取层与碎片入账单点**（`CharacterTemplateDb` 有生产读取方；`gacha_fragment.h::addFragment` + `GachaFragmentLedger` + `GachaFacade.grantFragments`，**G09 不得开第二个碎片写者**）；C++ AUTHORITATIVE；入库来源名须同批登记 `OverflowMailSender.SOURCE_DISPLAY_NAMES`；端口段：G08 已占 **1870**（`dispatch_gacha.cpp`），G09 新事务续增 |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | 同上 `§G11` | ✅ **素材注册硬阻塞已由 G16 解除**：12 个键（`avatar_*`/`portrait_*`）注册表/映射/双模块 WebP/UID 四环齐备，UI 直接用 `SpriteImage("avatar_zhouming")` 或 `SpriteResRegistry.resolve(键)`；档位头像 512 / 立绘 1024 且**大图类不进预加载**（`ResourcePreloader` 现有决策）。⚠️ 若 G11 要新增 `SpriteCategory`，必须同步 `SpriteSourceMappingGuardTest` 的档位策略表 + `SpriteCodegenSyncTest` 的分类清单（漏一处即红）。🔴 **G08 追加一条必办**：40~56dp 的小头像位应改读 `avatarKey`（512 档）——现 `portraitRes` 单字段承载 1024 档全身像、`avatar_<id>` 零消费方（`report-G08.md` §七-7）。剩余口径：色表强制 Q31（`GameConfig.Gacha` 单源），禁用 `ItemCard.getRarityColor` 旧色表 |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 + 文档收口 | `EXECUTION-PROTOCOL.md` §2 | 必须 G02–G07 全合入后**唯一一次**重录；G09/G11 建议同窗。**§6.3 已累积 G02/G03/G04/G05/G06/G15/G16/G08 八份登记**；Kotlin `Diff*` 预计无需重录（G15 已实证一次，仍待终树再证）；G15/G16/G08 三轮**零新增 B 类** ⇒ 三条集自 G04 起未变 |

---

## 8. 下次开工 checklist

> ✅ **G08 的复核与提交已完成（`8b3c10578`）**——原 §8.D 的 G08 五条清单已逐条走完，其中**第 5 条按实测作废**
> （`templateId` 列自 v54 在库 ⇒ 本批零迁移，见 `report-G08.md` §三 Room 行），第 3 条（PortraitPool 解析链）
> 已用 `PortraitResolver` 根治并配 E-3 判别力自证。过程与上位 16 处失真记在 `TASKBOOK-G08.md` §2 / `report-G08.md` §三·§五。
> **下一会话直接走 §8.F（G09）。**

### 8.D G08 收官纪要（细则看 `TASKBOOK-G08.md` 与 `report-G08.md`，本文件不重复）

| 项 | 结论 |
|---|---|
| 构造路径 | `DiscipleService.instantiateTemplate(templateId)` 是**唯一**具名弟子生产口；三条直造臂（兑换码 / 邮件 / `recruitDisciple`）全删，`DiscipleCreationPathGuardTest` 把「入册口 ⊆ 白名单」固化成 CI 红线 |
| 碎片 | 入账单一函数：C++ `gacha_fragment.h::addFragment` + Kotlin 回退臂 `GachaFragmentLedger` + 门面 `GachaFacade.grantFragments`；端口 1870；三向常量（配置/Kotlin/C++）由 `CharacterTemplateGuardTest` 看护 |
| 开局 | 新档 = 周明（`portrait_zhouming`）+ 5 万灵石（不进年度收入）+ `gachaStarMap["zhouming"]=1` 且碎片账本无该键（Q34 字面） |
| 显示链 | `resolvePortraitResId` 两跳（通用像域 → 注册表域）；10 处读点/7 文件统一；直引 `PortraitPool` 只剩 resolver 本体 + 预载清单 |
| 移交 | G09 无遗留前置；G11 领一条必办（头像位改读 `avatarKey`）；G10 领 8 条登记（§6.3） |

### 8.F G09（抽卡核心批）——下一批

1. 🔴 **先写 `TASKBOOK-G09.md` 再切片**（§8.C-1）：`recon-G05-G06-G08-G09.md` 的 `G09` 段落同样是
   按维度切分的落点表，须做 §3.4 两项回查（交叉归属 + 非弟子表三端环）后才能派工；
   ⚠️ **G08 刚改过这块的三处活路径**（`dispatch_gacha.cpp` 端口段、`GachaFacade(-Impl)`、
   `gacha_fragment.h`），recon 里凡指「抽卡面在 `execute_dispatch.cpp` 内」的行号**按 recon 初版对待即失真**。
2. 需求口径：`docs/character-gacha-redesign-2026-09-23.md` §15.3（池/保底/历史的数据结构，
   `map<templateId,…>` 与 `map<poolId,pity>` 的写法与「仅 List 禁 Set/Map」的 Room 侧边界）、
   §15.5（星级乘区 `StarZone` 三端同名同参）、§15.6（碎片入账单函数——G08 已交付，G09 复用不得另开写者）。
3. 🔴 **抽卡必走确定性 RNG 分区 `SYSTEM`**（`GameRngManager.getRng`），禁 `kotlin.random.Random`；
   新增随机逻辑要同步 `RngSourceGuardTest` 登记表（**注意坑 16 与 G08 §七-4：登记计数会随删除漂移，
   盘点整行留 G10，批内只做同步**）。
4. 🔴 **金黄仍不重录**（§6.1 唯一窗口是 G10）：G09 若改既有消费序，三条 B 类红允许变化但必须
   逐条登记 + `actual` 指纹写进报告；G08 的实证是「两侧同步删除 ⇒ `Diff*` 继续绿」，G09 属**新增**消费，
   风险等级更高，切片时把「是否触碰既有掷点序」列为必答项。
5. 入库（碎片→弟子解锁 / 物品）一律 `InventorySystem.withTrackingSource("<来源>")` 且来源名同批登记
   `OverflowMailSender.SOURCE_DISPLAY_NAMES`；新增 `gachaPools[]` 配置走中性源 + 生成器（禁手改产物）。
6. UI 读取面：结果页要读的每个新字段先过 `docs/ui-read-surface.md` §2（镜像合法面）；
   碎片/星级/保底经 GameData 镜像回读，**禁止**为 UI 开第二条同步通道。
7. 素材与模板面已就绪：无需再跑 `import-art-assets.mjs`；`CharacterTemplateDb` 加行即自动进
   `CharacterTemplateGuardTest` 的逐字段比对（漏同步即红）。

### 8.C 通用（每批都走，G04/G15/G16/G08 四轮已验证有效）

1. **派工前必做 §3.4 两项侦察缺口回查，并把结果写成独立 `TASKBOOK-Gxx.md` 再切片**——
   G15 靠这一步抓出上位交接的 13 处漏项（含 `battle_residual_tx.h` 这个第二赠礼消费方，
   照上位清单派工直接 C++ 编译失败）与 2 处假阳性；
   **G16 靠这一步抓出上位 §11 内联任务书的三处结构性缺陷**（失效的 `SOURCE_DIR`、脚手架静默丢映射、
   `PORTRAIT` 分类强制 preserve 会产出 4096 级巨型纹理）——三者都不会被编译或既有守卫发现。
2. **≤10 文件/片**；接口形参删除类批次**按「实现处枚举」定测试面**，不要按符号名字面量 grep（坑 10）。
3. 分片并行期整树不可编译 ⇒ **明确禁止子代理跑 gradle/cmake**，只做精确编辑 + grep 自检；
   全部门禁由主线程终树复跑（G15 切 9+4 片，无一撞 150 轮上限）。
4. 提交前**同轮重跑全部门禁**，并读每条后台命令自己的日志 + 验产物 mtime/体积（坑 5、坑 11）。
5. 双 changelog **一律用编辑工具改**，改完 `node -e "JSON.parse(...)"` 校验（坑 3）；
   游戏内那份是**未发布版本块内**的条目，若被后续批次证伪可就地改写（G15 改写过 G03 的两条）。

---

## 9. 诚实状态声明

- **M1 完成度：8 / 11 批已提交**（G02 / G05 / G06 / G03 / G04 / G15 / G16 / **G08** = `8b3c10578`，
  每批都是全门禁实测绿 + 双 changelog + 报告 + 单次提交）；
  G09 / G11 / G10 **尚未开始实施**（分母 11 = 原 9 批 + 拍板新增的 G15、G16）。
- 🔴 **G08 的诚实账（四点要如实说）**：
  ① **同样是「实施 + 复核合一」**，但实测纪律升级了一档：**终树组合门跑了两次**——第一轮 `BUILD SUCCESSFUL`
  之后才发现一处**三道门都抓不到的静默死码**（拆分后类内残留同名私有成员，坑 16），删除成员版后
  **整条 `compileReleaseKotlin + testReleaseUnitTest + detekt + lintRelease` 重跑**，表内数字是第二轮的。
  ② **守卫判别力四轮实跑**（藏模板行 / 只改 C++ 头文件常量 / 撤白名单一条 / 删解析链第二跳），
  判红消息原文逐条抄进 `report-G08.md` §五；E-3 恰好**复现了 report-G16 §七-1 的原始缺陷形态**，
  这是「新守卫真能抓老 bug」的直接证据。E-3 的复绿并入终轮全量（未单独复跑），这一点报告里写明了。
  ③ **两处主动不做**：`assembleRelease` 未跑（本批无新增素材，不涉及包体）；真机未验 ⇒
  「周明立绘在弟子卡上真的画出来了」只有静态证据链（解析链返回非 0 资源 ID），**无视觉证据**，
  与 `pending-device` 批同批做。兑换码「服务端下发 fragment → 入账 → 结果卡片」需后端配合，同属未验面。
  ④ **上位 §8.D-5 被实测作废**：交接书写「动 `templateId` 列 ⇒ Room v59→v60 四件套 + 迁移测试」，
  而该列**自 v54 就在库**（`GameDatabaseMigrationsV54.kt:21`）⇒ 本批零迁移。
  照字面做会白造一条空迁移并撞 `RoomMigrationTest` 的期望集。⇒ 印证 §8.C-1：**上位交接的必做项必须实测**。
- 🔴 **G16 的诚实账（三点要如实说）**：
  ① **本批是「实施 + 复核合一」的会话**，没有另派复核会话。缓解措施是实测纪律：每个数字取自同轮命令原文
  （ctest 三度跑，含 `-R SceneEquivalence` 与 `-R Determinism…` 两次定性复核；JUnit 按 678 个 XML 逐模块汇总
  并与 G15 基线做 **+7 的逐项对账**；detekt/lint/五个 node 门全部实跑），
  且**对自己新写的两处守卫做了「退回旧状态判红」的判别力自证**（藏副本 → 双模块断言 FAILED；
  删档位分支 → 策略断言 FAILED），当场还原并复跑判绿——这是 G15 留下的「未做抽验」项，本批补做了。
  ② **两处主动不做，不是遗漏**：`assembleRelease` 未跑（⇒ 包体 +4.08 MB 是按单模块产物字节求和算的，
  非 APK 实测）；真机未验（与全停摆的 `pending-device` 批同批做）。「十连结果页真显示头像」这一条
  只有静态证据链（编译期 `R.drawable.<key>` 引用 + 注册表 + codegen 生成物），**没有视觉证据**。
  ③ **一处越界改动的处置值得记**：`import-art-assets.mjs` 实跑时连带把 `bg_recruit_normal.webp`
  重烘焙成 4096×2304 / 6.8 MB（因美术已在 2026-09-20 把源图换成 6144×3456，而该条目仍是 `preserve`）。
  这不在 G16 范围内，**已把产物与台账记录还原为在库版**（`git status` 里它不再是 M），
  只留下超预算告警与 `report-G16.md` §七-3 的待拍板项。**是否采用新美术 = 产品口径，不由素材批代拍**。
- ✅ **已核实（G16，全部本会话同轮实测）**：12 键四方逐项命中（registry 12 行 / 映射 12 条 source 非 null /
  双模块各 12 份 / UID 12 条 493–504）；24 份产物逐张 `VP8L` 无损；烘焙尺寸与字节逐张实测；
  `sprite-uid-map.json` 既有 UID 变化条数 = 0；重生成映射对 HEAD 净差异 = +12 条（既有 309 条零变化、零丢失）；
  ctest 1394/1391/3 且三条为金序列断言、`SceneEquivalenceTest` 13/13、`DeterminismProbe` actual 仍 `0xb4f3c6912207f597`；
  JUnit 7343/0/0/18 逐模块；detekt/lint 两 baseline 零改动、lint 36 警告全预存且本批符号零命中；
  game-data sha256 与 G04/G15 逐字符相同；JNI 86/86；ActionId 198/1861 且 regen 幂等；
  图集 41 精灵 / `layoutHash 6a122ed22d66bee5` 不变；Room 仍 v59；规范门禁 EXIT=0 且规则③ 回到 1 处。
- ⚠️ **G16 未重跑的两门（如实登记，非「应该没事」）**：`catalog↔guard` 退役集扫描、`cmake --build`（桌面重编）。
  判据是本批零 ActionId 改动 + 零 C++ 改动（`git status` M 列表内 `gamecore/**` 命中 0）+
  `.so` 与生成物 mtime/内容均为 G15 值。**下一批若动 catalog 或 C++ 必须重跑这两门**。
- 🔴 **G15 的诚实账（与 G04 的区别要说清）**：本批的**每个数字都是同轮命令的输出原文**（ctest 三度跑、
  JUnit 两度全量并按 XML 逐模块汇总、lint/detekt/node 门全部实跑），不含「应该通过」类自述；
  提交前另以复核身份把 §2.2 全表重跑了一轮，数值一致才落提交。期间抓到并纠正了 **3 处自身错误**：
  ① `RoomMigrationSupport.verifyDisciplesColumnsExist` 误判链尾版本（以为是当前版本，实为 v40），
     被 `RoomMigrationTest` 实测撞红后改回 `assertTrue`；
  ② `powershell` 跑 JNI 构建脚本失败却回报 exit 0，靠比对 `.so` 体积（8560640→8526336）才发现，
     换 `pwsh` 重跑；
  ③ 任务书初版把「分派区间起点迁移」的守卫后果写错（`isDispatchGap` 也接受 `UNKNOWN_ACTION`，
     起点不动守卫不会红），实施期反查后已就地校正 TASKBOOK §3.2-3 并登记。
  ⚠️ **两处降级已在 report-G15 §十 明写**：复核轮的 `lintRelease` 与 `compileReleaseKotlin` 是
  UP-TO-DATE 增量（输入未变），完整分析证据取自本会话更早那轮。
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
- **远端未推送**；`docs/research/`×2 仍为未跟踪且不提交。G15 的 5 个新增文件（V59 迁移、`59.json`、
  `RoomMigrationV58To59Test`、`TASKBOOK-G15.md`、`report-G15.md`）已随 `325da9d5a` 入库；
  **G08 的 17 个新增文件**（C++ 4：`gacha_fragment.h` / `dispatch_gacha.cpp` / `gacha_fragment_test.cpp` /
  `gacha_tests.cmake`；Kotlin 主源 6：`CharacterTemplate.kt`、`PortraitResolver.kt`、`GachaFragmentLedger.kt`、
  `GachaNativeTx.kt`、`RedeemCodeFragmentOps.kt`、`MailDiscipleAttachmentCleanupRule.kt`；
  Kotlin 测试 5：`CharacterTemplateGuardTest`、`DiscipleCreationPathGuardTest`、`DiffGachaFragmentTest`、
  `PortraitResolverTest`、`MailDiscipleAttachmentCleanupRuleTest`；文档 2）已随 `8b3c10578` 入库，
  一次性诊断脚本**未入库**。
- ⚠️ **G15 未做的判别力自证**（留给 G10 或后续复核）：`GameViewDiscipleProjectionTest` 的
  「`requiredScalarFields` ↔ `ROW_SPECS` 逐项双射」是否**真双向**（漏删会不会红）未验证——
  JUnit 全绿只证明当前一致，不证明漏改会被抓。做法见 `report-G15.md` §十 末段。
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

> 🔴 **本 §11 已被 [`TASKBOOK-G16.md`](TASKBOOK-G16.md) 取代为派工真源，G16 亦已收官（`4ba6c1bdd`）**。
> 保留本节是为了留下「上位交接的任务书为何不可直接派工」的对照证据：§11 只有 6 行目标/流程描述，
> **无文件面、无档位口径、且把注册代码落点写成「`XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)`」
> ——实测注册代码由 codegen 生成，该文件本批零改动**。
> 照 §11 字面派工会直接踩三处结构性缺陷（失效的 `SOURCE_DIR` / 脚手架静默丢映射 / `PORTRAIT` 强制 preserve
> 承载 5016² 源图），三处的证据与根治见 `TASKBOOK-G16.md` §2 与 `report-G16.md` §三。
> **收官结果**：12 键四方齐备、G11 硬阻塞解除、`SpriteCategory.CHARACTER` 新增、图集与 Room 零变化、
> 零新增 B 类、登记 7 条（`report-G16.md` §七）。

| 项 | 内容 |
|---|---|
| 目标 | 解除 G11 硬阻塞：注册 **12 个角色精灵键**（6 角色 × 头像 + 全身像，键名如 `avatar_zhouming` / `portrait_zhouming`） |
| 现状（侦察证据） | 键**只存在于** `android/app/src/main/assets/data/game-data.json`；`resource-registry.json` 无、`sprite-uid-map.json` 无、双模块 `drawable-nodpi` 无文件 |
| 源图 | `模拟宗门美术素材/<角色名>/{头像,全身像}.png` —— 🔴 **该目录永不提交**，本批只提交转换后的 WebP 与注册代码 |
| 流程 | 严格走 `rules/static-resources.md` 七步：无损 WebP → **双模块**放置 → `XianxiaApplication.kt` 经 `SpriteResRegistry.register(...)` → `ResourcePreloader` 同步点 → `SpriteImage("名称")` / Canvas `drawSprite` 使用 → 图集 codegen → 守卫测试 |
| 门禁 | `node scripts/check-agent-instructions.mjs`；`SpriteAtlasDefGeneratedTest` / `BuildingSpriteFootprintGuardTest` 一类精灵/图集守卫必须绿；`detekt`；全量 JUnit（确认无"未注册键被引用"红） |
| 冲突面 | 与 G04/G15 **无共享文件**（除 `XianxiaApplication.kt`，该文件 G03 已因 System 注册改过）⇒ 可与删除批**任意顺序插空**，用户选定位置 = G04 之后、G09 之前 |
| 注意 | `sprite-uid-map.json` / `atlas-rgba-manifest.json` 是构建副产物（§2.3 坑 2），提交前 `git checkout --`；本批**新增素材文件**才是要提交的东西 |
