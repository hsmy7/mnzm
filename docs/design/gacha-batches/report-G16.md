# report-G16 · 角色素材批（寻访 12 键入库 + 素材管线根因修复）

> 批次依据：[`TASKBOOK-G16.md`](TASKBOOK-G16.md)（本批派工细则唯一真源）；
> 上位交接 `HANDOVER-m1-remaining-3.md` §11 的内联任务书已被该 TASKBOOK 取代（差异见本文 §三）。
> 产品拍板：P-3「角色素材批单独开一批，插在 G04 之后、G09 之前」（2026-09-24）。
> 开工基线：HEAD `acf745398`，`git status --porcelain` = 2 行（仅 `docs/research/`×2）。
> **本文所有数字均为本会话同轮命令输出原文**，非引用、非「应该通过」类自述；未跑的项目在 §八 明列。

---

## 一、做了什么（分类表）

**目标**：解除 G11 的素材硬阻塞——6 位寻访角色的 12 个精灵键（`avatar_<角色id>` / `portrait_<角色id>`）
此前只存在于 `game-data.json` 的配置字符串里，注册表/映射/产物/UID 四个环节**全部为空**（recon §G11-4 实测）。

| 面 | 文件 | 改动 |
|---|---|---|
| **素材产物** | `app` + `feature/game` 两模块 `drawable-nodpi/{avatar,portrait}_*.webp` | **新增 24 份**（12 键 × 双模块），无损 WebP（`VP8L` 逐张实测），单模块合计 **4.08 MB** |
| **注册源** | `android/scripts/resource-registry.json` | 新增 `CHARACTER` 分类 12 行（`name == res == 键名`），分类数 14 → **15** |
| **映射真源** | `android/scripts/source-mapping.json` | +12 条 CHARACTER（`source` 全部非 null）；`sourceDir` 由机器绝对路径改仓库根相对写法；条目 309 → **321** |
| **UID 契约** | `android/scripts/sprite-uid-map.json` | +12 条（UID **493–504**，既有 max=492）；**既有 UID 零变化**（脚本实测 `变化条数 0`） |
| **导入台账** | `android/scripts/sources-imported.json` | +12 条源 MD5 + 烘焙后尺寸（`96 insertions(+), 0 deletions(-)`） |
| **枚举** | `core/ui/.../SpriteResRegistry.kt` | `SpriteCategory` 新增 **`CHARACTER(2)`** + KDoc（键名口径、优先级理由） |
| **预加载** | `feature/game/.../ResourcePreloader.kt` | 仅 KDoc：大图类不预载清单补 `CHARACTER`（与 BEAST/BACKGROUND 等同类，**零逻辑改动**） |
| **管线脚本** | `android/scripts/art-source.mjs` | **新增**：源目录唯一解析入口（仓库根 / `MNZM_ART_SOURCE` 覆盖 / 缺失 fail-fast） |
| | `android/scripts/scaffold-source-mapping.mjs` | `+126/−12`：源目录改走 `art-source.mjs`、`CHARACTERS` 登记表、`DRAWABLE_BAKE`（按 drawable 覆写档位）、`MAP_KNOWN` 两条真源修正 + 可选 `modules`、`assertNoMappingLoss()` 防丢失守卫 |
| | `android/scripts/import-art-assets.mjs` | `+38/−2`：源目录改走 `art-source.mjs`、产物超预算告警（`BAKE_BUDGET_BYTES = 3 MB` / 长边顶到 4096） |
| **守卫** | `app/src/test/.../GachaCharacterSpriteGuardTest.kt` | **新增 5 例**：配置 ↔ 注册表 ↔ 映射 ↔ 双模块 四方一致（锚点 = `characterTemplates`，新增角色自动纳入） |
| | `app/src/test/.../SpriteSourceMappingGuardTest.kt` | `+87/−18`：白名单 `if/else` → 「分类 → 档位策略」表驱动；新增 2 例（分类必须已登记档位 / 分类名必须在枚举内）；`companion object` 提常数（禁魔法数） |
| | `app/src/test/.../SpriteCodegenSyncTest.kt` | 分类期望 14 → 15（`CHARACTER` 追加在 `PORTRAIT` 后，与 registry 顺序一致） |
| **文档** | `rules/static-resources.md` `+30/−14`、`rules/media-source-assets.md` `+31/−10` | 源目录路径统一、`CHARACTER` 分类行与档位口径、**禁止手补生成物**、素材批「时间戳脏 vs 真新增」三份必提交清单、G11 阻塞状态改「已解除」 |
| **更新日志** | `CHANGELOG.md` `+53/−0`、`assets/changelog_entries.json` `+2/−1` | 双份同批；游戏内条目走玩家视角且明写「本次玩法与数值无变化」；JSON 改后 `require()` 解析复验 |

**注册代码落点与上位交接的偏差**（有据）：`HANDOVER-3` §11 与 `recon` §G11-4 都把「`XianxiaApplication.kt` 经
`SpriteResRegistry.register(...)`」列为本批动作。实测该文件 `:146` 只调 `registerAllSprites()`，
`SPRITES_CHARACTER` 与对应 `register` 行由 `build-atlas.mjs --codegen` 生成（生成物实测见下），
**故 `XianxiaApplication.kt` 本批零改动**。预加载侧同理：`ResourcePreloader` 按分类显式枚举，大图类不预载，
无需新增同步点。

---

## 二、门禁（终树同轮实测）

> 全部门禁在**同一棵工作树**上跑完，前后各打一次指纹（§九）。JUnit/detekt/lint 见 §二·补（本轮跑完后回填）。

| 门 | 实测 | 判定 |
|---|---|---|
| 桌面 ctest | `99% tests passed, 3 tests failed out of 1394` / `Total Test time (real) = 59.29 sec` | **1394 / 1391 / 3**，与 G15 基线**逐条同名** ⇒ 零新增 B 类 |
| 三条红的定性 | `ctest -R "DiscipleFactory.GoldenSequence\|DeterminismProbe…"` `--output-on-failure`：断言原文为 `Expected equality of these values: Which is: "male_disciple_15"` / `Which is: 56 / 73 / 80 / 57 / 35 / 28 / 48 / 43` 等**固定金序列常量** | **B 类（RNG 平移）**，非 A 类协议漂移（判据见上位 §3.3：读异常类型与抛出点，不按家族归类） |
| 🔴 `SceneEquivalenceTest`（坑 9 判据） | `ctest -R SceneEquivalence` → `100% tests passed, 0 tests failed out of 13` | 图集/生成物变更批必查项**全绿**（本批未动 `LAYOUT`，该结论由实测支撑） |
| 素材 codegen | `node scripts/build-atlas.mjs --codegen` → `显示尺寸保真校验通过：18 栋建筑 + 9 个装饰` + 生成 `SpriteRegistryData.kt` / `TextureAtlas.h` / `scene_uv_tables.h` | `scene_uv_tables.h` 重跑后**与 HEAD 零差异**（未进 `git status` 的 M 列表） |
| 资源清单 | `node scripts/resource-manifest.mjs` → `resource-manifest: 718 条资源` | 694 → 718 = **+24**（12 键 × 双模块）✓ |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` → `BUILD SUCCESSFUL in 54s`（112 tasks: 25 executed） | 生成物以 `R.drawable.<key>` 静态引用 12 项 ⇒ 编译期即证明双模块资源存在 |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` → `校验通过：…（sha256 035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef）` | **与 G04/G15 逐字符相同**（本批零配置源改动） |
| JNI 面 | `node scripts/check-jni-count.mjs` → `✓ JNI 面计数在基线内：total=86/86，双桥无扩散` | 不变（本批零 C++ 改动） |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` → `✓ 规范分发架构门禁全部通过。`（EXIT=0） | 预存告警：规则① 预算闸 32473/32768、规则③ 1 处（`static-resources.md:293` atlas-manifest basename）、规则⑤ 最坏链路 37890 字节。**本批一度把规则③ 顶到 3 处**（新写的裸文件名 `source-mapping.json` / `game-data.json`），已逐处改为全路径回到 1 处 |
| 图集不变量 | `atlas-manifest.json` → `sprites=41 format=ASTC_4x4_LDR layoutHash=6a122ed22d66bee5` | 与 G04 记录（41 精灵 / `6a122ed2…`）一致 ⇒ 图集零变化 |
| Room | `GameDatabase.kt:95 const val DATABASE_VERSION = 59` | 不变（上位 §2.2 明写「G16 素材批不删列」） |
| 跨语言对拍桥 | `core/engine/build/desktop-jni/libgamecorejni.so` = **8526336 字节 / mtime 2026-09-25 15:48**（G15 重建值） | 本批零 C++ 改动 ⇒ 合法复用，未重建（G15 坑 11 的「验产物 mtime/体积」在此体现为**证明无需重建**） |

### 二·补 JUnit / detekt / lint（同轮实测）

| 门 | 命令 | 实测 |
|---|---|---|
| JUnit 全量 | `testReleaseUnitTest --max-workers=1 --rerun-tasks --continue -Dgamecore.jni.path=…libgamecorejni.so` | `BUILD SUCCESSFUL in 11m 42s` / `EXIT=0`；按 XML 逐模块汇总（678 个 `TEST-*.xml`）：**app 1002 / domain 1575 / data 806 / engine 2866 / ui 146 / feature:game 948 = 7343 例，0 失败 0 错误 18 skip** |
| JUnit 对账 | G15 基线 7336（app 995） | **+7 = 本批新增用例数**：`GachaCharacterSpriteGuardTest` 5 例 + `SpriteSourceMappingGuardTest` 3→5 例（+2），其余五模块逐项相等 ⇒ 无既例被吞、无静默跳过 |
| 测试源编译 | 隐含于上一轮（`testReleaseUnitTest` 聚合任务编译六模块 `compileReleaseUnitTestKotlin`） | 0 错误（`--continue` 下无任何 `FAILED` 任务） |
| detekt | `./gradlew.bat detekt` → `BUILD SUCCESSFUL`；`detekt-baseline.xml` 与 `lint-baseline.xml` 均**未出现在 M 列表**（只缩不增） | 绿 |
| 🔴 detekt 覆盖面自证 | 追加更严的 `:app:detektReleaseUnitTest`（带类型解析、覆盖 `src/test`） | 该任务 `Analysis failed with 39 weighted issues`——**全部为测试源码的既存违规**（此任务不在本项目门禁清单内，AGENTS.md §2 的门禁是六模块 `detekt`）。按文件过滤后本批三个测试文件**仅 1 条命中**：`SpriteCodegenSyncTest.kt:172 UseOrEmpty`，且该行不在本批 diff 内（本批只改 `:55-58` 分类期望）⇒ 预存。**新写的 `GachaCharacterSpriteGuardTest` 与重写段 `SpriteSourceMappingGuardTest` 零命中** |
| ActionId 幂等 | `cp` 两份 → `node scripts/gen-action-ids.mjs` → 与工作树自比 | `198 actions (maxId=1861)`；`action_ids.h 零差异`、`ActionIds.kt 零差异`，且两文件未进 M 列表（本批不改 desc，与 HEAD 也一致） |
| lint | `./gradlew.bat lintRelease` | `BUILD SUCCESSFUL in 3m 38s` / `EXIT=0`；`0 errors, 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)` —— **36 与 G15 同值 ⇒ 零新增**。按本批符号过滤（`avatar_`、`portrait_`、`SpriteResRegistry`、`GachaCharacterSprite`、`SpriteSourceMapping`、`SpriteCodegenSync`、`ResourcePreloader`）在 `lint-results-release.txt` 中**零命中** ⇒ 36 条全预存。`lint-baseline.xml` 未进 M 列表（只缩不增）。跑完复查 `atlas-rgba-manifest.json` 确被改脏（仅 `generatedAt` 一行），已 `git checkout --` 还原（坑 2 再次实证） |

---

## 三、🔴 侦察结论：上位交接不可直接派工，且管线有三处结构性缺陷

`HANDOVER-3` §11 的 G16 任务书只有 6 行（目标 / 现状 / 源图 / 流程 / 门禁 / 冲突面），
按其字面派工会踩三个坑。三处均已在实施前定案并落地：

| # | 缺陷 | 因果链与证据 | 处置 |
|---|---|---|---|
| 1 | 🔴 **素材管线自 2026-09-24 起实际不可跑** | `scaffold-source-mapping.mjs:40` 与 `import-art-assets.mjs:176` 硬编码 `D:/模拟宗门美术素材`，而 `ls -d D:/模拟宗门美术素材` = **No such file or directory**；`rules/media-source-assets.md` §1 已于 2026-09-24 拍板权威源为**仓库根** `模拟宗门美术素材/`（572 MB / 384 PNG，`.gitignore` 内）。⇒ 文档真源已迁、脚本与 `rules/static-resources.md` 未跟 | 新增 `android/scripts/art-source.mjs` 作唯一解析入口（仓库根 + `MNZM_ART_SOURCE` 覆盖 + **缺失即抛错**），两脚本共用；`rules/static-resources.md` 五处路径表述统一为仓库根 |
| 2 | 🔴 **脚手架会把人工映射静默冲掉**（首次真跑即暴露） | `scanUnusableSources()` 对缺失目录静默跳过、`fileExists()` 一律 false、末尾**无条件** `writeFileSync` ⇒ 旧版跑一次即把全部 309 条 `source` 写成 null；且 `MAP_KNOWN` 把草皮源记成 `装饰物/草皮.png`（实为 `宗门地图/草皮.png`，`find` 实测），`map_rock_base`（`宗门地图/底部.png`，仅 `feature/game`）**根本不在任何表里** ⇒ 整条丢弃 + 单模块放置信息无处承载。编译与既有守卫**零报警**，坏要拖到下次重烘焙才暴露 | ① `MAP_KNOWN` 补两条真源 + 支持可选 `modules`；② 新增 `assertNoMappingLoss()`：旧映射带 source 的条目在新映射里丢失/退化、而其 WebP 产物仍在 `drawable-nodpi` ⇒ 直接抛错要求登记进表；③ 实测重生成对 HEAD 净差异 = **+12 条 CHARACTER + `sourceDir` 一行**，既有 309 条 source **零变化、零丢失**（脚本比对输出 `条目 309 -> 321`、无 `~变化`/`-丢失` 行） |
| 3 | 🔴 **`PORTRAIT` 分类不能承载角色素材**（按字面派工必炸） | `SpriteSourceMappingGuardTest` 的 `preserveCats` 含 `PORTRAIT` 并断言 `bake.preserve == true`，`scaffold` 对未登记分类也默认 `preserve`；而 12 张源图实测为头像 `1254² / 2508² / 4096² / 5016²×3`、立绘 `941×1672 … 4096×6144×3`，`MAX_BAKE_DIM=4096` 只夹尺寸不夹后果 ⇒ 单张头像解码后 ARGB ≈ **67 MB** | 新增 `SpriteCategory.CHARACTER(2)`（非首屏、与大图类一样不预载），并在守卫里给它显式档位策略 |

**另两处上位交接与实测不符（登记，不返工）**：
① §11 的「`XianxiaApplication.kt` 经 `register(...)`」——注册代码是 codegen 产物，该文件零改动（见 §一）；
② §11 未记 `atlas-rgba-manifest.json` 会被 codegen 改脏（实测仅 `generatedAt` 一行变化），已按坑 2 还原。

---

## 四、烘焙档位：实测选型而非估

同轮对 12 张源图按候选档位实烘取样（`lossless:true, effort:6`）：

| maxDim | 12 张合计 | 头像单张 | 立绘单张 | 立绘解码后 ARGB |
|---|---|---|---|---|
| 512 | 2.2 MB | 512×512 ≈ 234–265 KB | 341×512 ≈ 104–164 KB | 0.7 MB |
| 768 | 4.7 MB | 768² ≈ 488–563 KB | 512×768 ≈ 214–330 KB | 1.5 MB |
| **1024（立绘采纳）** | 8.0 MB | — | 683×1024 ≈ 352–542 KB | 2.7 MB |
| 1536 | 15.8 MB | — | 1024×1536 ≈ 732–2013 KB | 6.0 MB |

**采纳 = 头像 `maxDim 512` + 立绘 `maxDim 1024`**（混合档位，12 张单模块合计 4.08 MB）。
依据是**显示尺寸余量**：Q30 结果页两行五列正方形框（横屏高 1080px ⇒ 格子约 170px）用头像；
图鉴 6 格用立绘（约 120dp 宽 ≈ 360px@3x）。同屏最坏解码内存 ≈ 6×2.7 + 6×1.0 ≈ 22 MB，
且属按需解码（大图类不预载），离开界面即回收。
调档 = 改 `scaffold-source-mapping.mjs` 的两个常数 + 重跑 `import-art-assets.mjs`（映射幂等，不欠债）。
守卫侧把 512/1024 固化为期望值，改档位不同步改守卫即红。

---

## 五、守卫判别力自证（两处，均实跑）

「测试全绿」只证明当前一致，不证明漏改会被抓。两处都做了**退回旧状态判红**的实验，做完当场还原：

| 实验 | 做法 | 结果 |
|---|---|---|
| 双模块断言 | 藏掉 `feature/game/…/avatar_zhouming.webp`（app 副本仍在 ⇒ 编译不受影响，专测断言） | `GachaCharacterSpriteGuardTest > 配置键全部双模块放置 … FAILED`，`5 tests completed, 1 failed` ✅ 能抓 |
| 档位策略表 | 删掉 `expectedBakePolicy` 的 `CHARACTER` 分支（等价于「新增分类未登记表」） | `SpriteSourceMappingGuardTest > 每个注册分类都已登记烘焙策略 … FAILED`，`5 tests completed, 1 failed` ✅ 能抓（旧版此处**静默放行**，正是 §三-3 的洞） |

还原后复跑：`GachaCharacterSpriteGuardTest` + `SpriteSourceMappingGuardTest` + `SpriteCodegenSyncTest` +
`ResourceManifestCompletenessTest` + `AtlasManifestSyncTest` + `SpriteSizingFidelityTest`
六类同轮 `BUILD SUCCESSFUL`（154 tasks: 13 executed）。

---

## 六、本批新增 B 类清单（交 G10）

**零新增。** 因果链：本批零 C++ 改动（`git status` 的 M 列表里 `gamecore/**` 命中数 = 0）、
零配置源改动（`gen-game-data --check` sha256 逐字符不变）、零 Kotlin 业务逻辑改动
（仅 `SpriteCategory` 加一枚举值 + 一处 KDoc）。⇒ RNG 消费点集合完全未动，
ctest 三条红与 G15 逐条同名（`DiscipleFactory.GoldenSequenceSeed42`、
`DiscipleFactory.GoldenSequenceSeed987654321Female`、`DeterminismProbeTest.DigestMatchesGoldenBaseline`），
Kotlin 侧仍 0 条。**G10 的 B 类集不因此批增长。**

---

## 七、G10 / 后续登记（诚实发现，本批未做）

| # | 项 | 证据 | 归属 |
|---|---|---|---|
| 1 | 🔴 **`PortraitPool.getResourceId` 不查 `SpriteResRegistry`** ⇒ 具名角色在弟子卡/详情页会显示成通用像 | `PortraitPool.kt:13-16` 只枚举 `male_disciple_1..20` / `female_disciple_1..17`；`:33` 用 `getIdentifier` 预构建；消费方 `DiscipleComponents.kt:175-179`、`DiscipleSlotComponents.kt:57-70,238-251`、`DetailRightPanel.kt:110` 的兜底分支是 `SpriteResRegistry.resolve("disciple_portrait")`。G08 把 `portraitRes` 改为模板强制置 `portrait_zhouming` 后，`PortraitPool` 返回 0 ⇒ 走兜底 = **通用像** | **G08**（属 `portraitRes` 解析链，非素材注册批范围）。修法建议：解析顺序改 `SpriteResRegistry.resolve(name)` 优先，`PortraitPool` 只作 37 张通用肖像的性能缓存 |
| 2 | 🔴 **全仓库没有「双模块放置」硬守卫** | `ResourceManifestCompletenessTest` 名字是「双模块完整性」，实则两目录 `flatMap` 后 `.toSet()` 并集比对，第二例只断言 `count >= 1`；`build-atlas.mjs` 的三条「必须同时放置」报错只覆盖图集 LAYOUT 内精灵。实测 `app` 317 / `feature/game` 377 个文件，**61 个单模块资源**（37 张通用肖像 + 部分 `building_*`/`ui_*`）靠「没有断言」放行 | **G10 / 待拍板**：要不要收这 61 个的债。本批已在 `GachaCharacterSpriteGuardTest` 里为 12 个角色键建立真双模块断言 |
| 3 | 🔴 **`背景图/普通招募背景图.png` 源图已被美术换成 6144×3456**，而该条目仍是 `preserve` ⇒ 重烘焙会静默产出 **4096×2304 / 6.8 MB**（解码 ARGB 37.7 MB，在库版是 1672×941 / 1.78 MB） | `import-art-assets.mjs` 实跑一次即连带重写 `bg_recruit_normal.webp`（双模块），`sources-imported.json` 记录 `hash 54d7f023… target 1672×941` → `0721b843… target 4096×2304`；sharp 实测源图 6144×3456 / 16.9 MB / mtime 2026-09-20 | **本批不夹带**：产物与该条记录已还原为在库版（`git status` 里该 webp 不再是 M）。已在 `import-art-assets.mjs` 加超预算告警使问题下次会自己喊出来。**档位重定（是否采用新美术、采用哪档）属产品口径，待拍板** |
| 4 | 第 7 角色 `月城雪/` 只有 `全身像.png`（10 MB 级）+ `轮换池背景图.png`，**无头像**；`未归名大立绘` 同理 | `ls 模拟宗门美术素材/月城雪`；`docs/character-gacha-redesign-2026-09-23.md` §12-Q10「先不管」、M3「轮换池需新立绘」 | **M3**（另开素材批；本批 `CHARACTERS` 表加一行即可复用全流程） |
| 5 | `docs/design/art-asset-pipeline-improvement.md` §1.1#1 与 `recon-G05-G06-G08-G09.md` §G11-4 仍写「权威源 = `D:\模拟宗门美术素材`」 | grep 全仓 `D:[\\/]模拟宗门` | 专题方案与 recon 属**一次性过程档案，不回改**（`docs/AGENTS.md` 目录性质表）；现行口径以 `rules/media-source-assets.md` §1 与 `rules/static-resources.md` §1.2 为准，已在两处写明「以本文件为准」 |
| 6 | 本批顺带把 `scaffold` 生成的 `description` 文案里的 `D:\\模拟宗门美术素材` 改掉 ⇒ `source-mapping.json` 第 3 行随之变化 | 该文件 `sourceDir`/`description` 两行 | 已随批提交，无遗留 |
| 7 | 🟡 **测试源码不在 detekt 门禁射程内**：AGENTS.md §2 的门禁 `./gradlew.bat detekt` 对 `:app` 只分析主源——实测 `app/build/reports/detekt/detekt.xml` 里 `src/test` 命中数 **0**；改用带类型解析的 `:app:detektReleaseUnitTest` 立即暴露 **39 条 weighted issues**（全在 `src/test`，均预存） | 上一行是本会话跑出来的报告文件计数；39 那条为该任务自己的输出行 `Analysis failed with 39 weighted issues.` | **G10**（要不要把 `detekt<Variant>UnitTest` 纳入门禁、以及 39 条是修还是建 baseline，属工程口径待拍板）。本批自身新写测试在该更严档下零新增违规（见 §二·补） |

---

## 八、诚实状态声明

**已核实（本会话同轮实测，全部为命令输出原文）**：ctest 1394/1391/3 且三条红为金序列常量断言；
`SceneEquivalenceTest` 13/13 绿；12 键注册表/映射/双模块产物/UID 四方逐项命中数；
24 份产物 `VP8L` 无损逐张核验；烘焙尺寸与字节逐张实测；`sprite-uid-map.json` 既有 UID 零漂移
（`变化条数 0`）；重生成映射对 HEAD 净差异 = +12 条（既有 309 条 source 零变化）；
game-data sha256、JNI 86/86、Room v59、图集 41 精灵 / `layoutHash 6a122ed22d66bee5` 全部不变；
**JUnit 7343 / 0 / 0 / 18 skip 逐模块汇总**、`compileReleaseKotlin` 绿、detekt 绿（两 baseline 零改动）、
lint `0 errors / 36 warnings` 与本批文件零命中、ActionId regen 幂等、六个精灵守卫类 + 新守卫同轮绿；
两处守卫判别力自证（判红 → 还原 → 复跑判绿）。

**未做 / 未核实（如实列出）**：
1. **未构建 APK、未真机验证**：本批判据是「素材进包 + 注册可解析」，静态证据链完整
   （`compileReleaseKotlin` 通过 ⇒ 12 个 `R.drawable.<key>` 引用成立；`release` 开 `shrinkResources=true`，
   生成代码里的 `R.drawable.*` 静态引用即资源收缩可达性依据）。但**「十连结果页真的显示出头像」**
   要等 G11 UI 落地后在真机核对——与已停摆的真机验证批同批做。
2. **未做 `assembleRelease`**：包体 +4.08 MB（单模块，双模块副本经 merger 只入一份）是推算值而非 APK 实测值。
3. **档位选择（512/1024）是本批按显示尺寸余量作出的工程判断**，非美术侧签认；若美术要求更高清晰度，
   改档成本 = 两个常数 + 一次重烘焙（实测：1536 档合计 15.8 MB）。
4. `GachaCharacterSpriteGuardTest` 的「配置 ↔ 注册表」双向断言只覆盖 `avatarKey`/`portraitKey` 两键，
   未对 `characterTemplates` 其余字段做断言（`GachaConfigGuardTest:98-102` 已锁 id 集合，不重复）。

---

## 九、树指纹与提交身份

| 时点 | HEAD | `git status --porcelain` |
|---|---|---|
| 开工 | `acf745398` | 2 行（仅 `docs/research/`×2，非本批、不提交） |
| 全部门禁跑完 + 临时诊断清理 + 副产物还原后 | `acf745398` | **42 行 = 14 修改 + 28 新增**（本批全部）+ 2 行 `docs/research/`；非未跟踪残留 = 0 |
| 提交后 | `4ba6c1bdd`（实施单次提交） | 42 行全部入库；残留 = 2 行 `docs/research/`（非本批、不提交） |

本批文件清单：**修改 14 个跟踪文件** = 2 份 rules 文档 + `CHANGELOG.md` + 2 脚本
（`scaffold-source-mapping.mjs` / `import-art-assets.mjs`）+ 4 数据/台账
（`resource-registry.json` / `source-mapping.json` / `sprite-uid-map.json` / `sources-imported.json`）
+ 4 Kotlin（`SpriteResRegistry.kt` 主源 + `ResourcePreloader.kt` KDoc + 2 个守卫测试）
+ 1 游戏内 changelog；**新增 28 个文件** = 24 份 WebP + `android/scripts/art-source.mjs`
+ `GachaCharacterSpriteGuardTest.kt` + `TASKBOOK-G16.md` + 本报告。
`atlas-rgba-manifest.json` 被 lint 改脏后已还原；`scene_uv_tables.h` 重跑零差异未进列表。
临时诊断文件（映射对拍副本、JUnit 汇总脚本、两枚门禁日志、档位取样目录）**已全部删除，不入库**。

**提交规模实测**：`42 files changed, 1195 insertions(+), 62 deletions(-)`
（24 份二进制 WebP 不计行；CRLF 翻转按 `git diff --cached --numstat` 逐文件核对为零——单文件最大增量
`source-mapping.json +142/−2`，无「双侧 ≈ 全文件行数」形态）。

---

## 十、提交状态

- ✅ 实施单次提交 = **`4ba6c1bdd`**（`feat(gacha): G16 角色素材批……`），含素材、管线、守卫、双 changelog、
  本任务书与本报告；**未推送远端**（本仓多会话共用一棵工作树，推送由用户指令决定）。
- ⏭ 本文件 §九/§十 的提交后信息与 `HANDOVER-m1-remaining-3.md` 的收官回写走紧随其后的
  `docs(gacha)` 提交——**沿用 G15 先例**（`325da9d5a` 实施 + `acf745398` 回写），
  因回写内容需要引用实施提交自身的 hash，无法与实施同批完成。
