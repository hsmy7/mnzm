# TASKBOOK-G16 · 角色素材批（P-3 拍板产物）

> **本文件是 G16 的派工真源**，取代 `HANDOVER-m1-remaining-3.md` §11 的内联任务书（§11 只有 6 行目标描述，
> 无文件面、无决策依据）。开工时点：**2026-09-25，HEAD `acf745398`，`git status --porcelain` = 2 行
> （仅 `docs/research/`×2，与本批无关且不提交）**——已满足上位交接 §8.B-3 的开工前置。
> 侦察方式：本会话同轮实跑（脚本全文读 + 384 张源图 sharp 实测 + 五个精灵守卫测试逐行读 + 子代理穷举面审计），
> 下表所有数字均为命令输出原文，非推测。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 让 6 位抽卡角色的 12 个精灵键（`avatar_*` / `portrait_*`）**真正进包可显示**，解除 G11 最简寻访 UI 的硬阻塞 |
| 验收判据① | 12 个键在 `resource-registry.json` 有注册条目、在 `source-mapping.json` 有**非 null source**、双模块 `drawable-nodpi` 各有 1 份无损 WebP |
| 验收判据② | `SpriteResRegistry.resolve("avatar_zhouming")` 等 12 个键可解析（注册由 codegen 自动产出，故判据等价于「`SpriteCodegenSyncTest` 绿 + 新守卫绿」） |
| 验收判据③ | 新增守卫 `GachaCharacterSpriteGuardTest`：以 `game-data.json` 的 `characterTemplates` 为锚点遍历 12 键，四方（配置 ↔ 注册表 ↔ 映射 ↔ 双模块产物）任一环缺失即红并给出操作指引 |
| 验收判据④ | §2.3 坑 9 的三处手工复刻表（`SceneEquivalenceTest` / `SpriteAtlasDefGeneratedTest` / `BuildingSpriteFootprintGuardTest`）**实测仍绿**——本批不新增图集槽位，若红即说明误动了 `LAYOUT` |
| 不做 | 不改 UI（G11 做）、不改 `Disciple.portraitRes` 解析链（G08 做）、不处理第 7 角色 `月城雪` 与 `未归名大立绘`（Q10 拍板先不管）、不删 37 张通用弟子肖像（§4 口径：存量保留） |

---

## 2. 侦察实测：三处上位交接未记录的结构性缺陷

### 2.1 🔴 素材管线在本机是**断的**——`SOURCE_DIR` 指向不存在的路径

| 证据 | 实测 |
|---|---|
| `android/scripts/scaffold-source-mapping.mjs:40` | `const SOURCE_DIR = 'D:/模拟宗门美术素材';`（另 `:6` 注释、`:404` 生成的 description 字段） |
| `android/scripts/import-art-assets.mjs:176` | `path.join(entry.sourceDir ?? 'D:/模拟宗门美术素材', entry.source)` |
| `rules/static-resources.md:18,92,99` | 「素材源目录（唯一权威源）：`D:\模拟宗门美术素材`」 |
| `ls -d D:/模拟宗门美术素材` | **No such file or directory** |
| 真实目录 | `C:/Mnzm/XianxiaSectNative/模拟宗门美术素材/`（572 MB / 384 PNG，已 `.gitignore`），即 `rules/media-source-assets.md` §1 于 **2026-09-24 拍板**的「项目指定源目录」 |

⇒ **两个真源冲突**：`media-source-assets.md`（2026-09-24，新）说仓库根，`static-resources.md` + 两个脚本（2026-09-02，旧）说 `D:\`。
G16 正是第一个必须真正跑通管线的批次，故**路径根因修复归本批**。

**次生灾害（更要紧）**：`scaffold-source-mapping.mjs:117` 是 `if (fs.existsSync(SOURCE_DIR)) await walk(SOURCE_DIR)`，
`:120 fileExists()` 对缺失目录一律返回 false，`:423` 无条件 `fs.writeFileSync(OUT_FILE, ...)` ⇒
**照现状跑一次脚手架，就会把 `source-mapping.json` 全部 371 条 `source` 静默改写成 `null`**（映射真源被清空，
且 `import-art-assets.mjs:175` 对 null 条目直接 `continue`，故编译/守卫全都不会立刻报警——坏在下次重烘焙时才暴露）。
⇒ 修复必须同时包含 **fail-fast**：源目录不存在即抛错退出，禁止静默产出全 null 映射。

### 2.2 🔴 `PORTRAIT` 分类不能用于角色素材（守卫强制 `preserve`）

| 证据 | 实测 |
|---|---|
| `SpriteSourceMappingGuardTest.kt:130-133` | `preserveCats` 含 `"PORTRAIT"` |
| 同文件 `:143-145` | 命中 `preserveCats` ⇒ `assertTrue("$res($category) 应使用 preserve 烘焙而非 maxDim", preserve)` |
| `scaffold-source-mapping.mjs:352` | 新分类不写 `CAT_BAKE` 时**兜底 `{preserve:true}`** |
| 源图实测（sharp 读 12 张） | 头像 `1254²` / `2508²` / `4096²` / **`5016²` ×3**；全身像 `941×1672` / `2048×3072` / **`4096×6144` ×3** |
| `import-art-assets.mjs:46` | `MAX_BAKE_DIM = 4096`（preserve 也会被夹到 4096 长边） |

⇒ 若把 12 个键塞进 `PORTRAIT`：产物为 `4096×4096` 级无损 WebP（单张约 10 MB 级、解码后 ARGB 约 67 MB/张），
12 张 = 包体与内存双爆炸，且**守卫强制如此**、无法在映射里降级。
⇒ 结论：**新增 `SpriteCategory.CHARACTER`**（`rules/static-resources.md:100` 明写「新增分类需先在 SpriteCategory 枚举定义」）。

### 2.3 🟡 守卫对「未知分类」静默放行（本批顺带收口）

`SpriteSourceMappingGuardTest.kt:134-147` 用两个白名单集合做 `if/else if`，**新增分类若不在任一集合内则整个循环体不匹配、零断言**。
按 AGENTS.md §9.5「守卫测试三要素」（枚举驱动 / 明示排除 / 消息带指引），本批把该处改为**分类 → 烘焙策略表驱动**，
并新增「每个 registry 分类必须在策略表里登记」的断言——今后再加分类不会静默漏检。

---

## 3. 决策（含实测依据）

| # | 决策 | 依据 |
|---|---|---|
| D-1 | 源目录解析为 `<仓库根>/模拟宗门美术素材`，允许 `MNZM_ART_SOURCE` 环境变量覆盖；目录缺失 → **抛错退出**（不静默） | §2.1；与 `media-source-assets.md` §1 对齐；换机器后目录为空是**已拍板事实**（§2 不入库），此时必须显式报错而非产出空映射 |
| D-2 | 新增 `SpriteCategory.CHARACTER(2)` | §2.2；priority=2 = 非首屏（寻访/图鉴为对话框，不是首屏可见），符合 `static-resources.md` §2.4「首屏可见才 ≤1」 |
| D-3 | `头像 maxDim 512` / `全身像 maxDim 1024`，均**无损** WebP（`lossless:true, effort:6`） | 见下方实测表；显示尺寸上限：结果页 2×5 正方形框（横屏高 1080px ⇒ 格子 ≈170px）用头像，图鉴 6 格用立绘（≈120dp 宽 ⇒ 360px@3x） |
| D-4 | 12 个键 **双模块都放**（`feature/game` + `app`） | `static-resources.md` §1.1 硬性要求；codegen 见 app 有副本即用 app 的 `R`（`build-atlas.mjs:1303-1305`），避免生成物引入 `FeatureGameR` 分支 |
| D-5 | 不新增图集槽位（`LAYOUT.mapSprites` 不动） | 角色图不进地图图集；一旦误动会连锁撞 `AtlasManifestSyncTest` 的逐项下标比对与 `layoutHash`（§2.3 坑 9 同类） |
| D-6 | 预加载策略 = **与既有大图类一致，不预载**（仅更新 `ResourcePreloader` 的 KDoc 清单） | `ResourcePreloader.kt:263-274` 现有决策：大图类（BEAST/CAVE/HEAVENLY_TRIAL/BACKGROUND/PORTRAIT）不预载——「即使解码接入也会肉眼可见降质，纯占内存」；角色立绘同属此类，走 `SpriteImage` 的 `painterResource` 回退 |
| D-7 | `sprite-uid-map.json` / `sources-imported.json` 的**新增条目随本批提交**，`atlas-rgba-manifest.json` 等纯时间戳脏文件还原 | 前者是「新资源按字典序追加 max+1、既有 UID 永不漂移」的稳定引用真源（`resource-manifest.mjs:6-10`），还原它等于丢掉 UID 契约；后者属 §2.3 坑 2 |

**D-3 烘焙档位实测表**（同轮 sharp 实跑 12 张源图，`lossless:true effort:6`，双模块只计单份）：

| maxDim | 12 张合计产物体积 | 头像单张 | 全身像单张 | 全身像解码后 ARGB |
|---|---|---|---|---|
| 512 | 2.2 MB | 512×512 ≈ 250 KB | 341×512 ≈ 130 KB | 0.7 MB |
| 768 | 4.7 MB | 768×768 ≈ 530 KB | 512×768 ≈ 270 KB | 1.5 MB |
| **1024（采纳，仅全身像）** | 8.0 MB | — | 683×1024 ≈ 450 KB | 2.7 MB |
| 1536 | 15.8 MB | — | 1024×1536 ≈ 930 KB | 6.0 MB |

⇒ 采纳混合档位：**头像 512（≈250 KB/张）+ 全身像 1024（≈450 KB/张）= 12 张约 4.2 MB 包体**；
同时在场的最坏内存（图鉴 6 张立绘 + 结果页 6 张头像）≈ 6×2.7 + 6×1.0 ≈ 22 MB，且是按需解码、离开界面即回收。
调档 = 改 `scaffold-source-mapping.mjs` 一行 + 重跑 `import-art-assets.mjs`（映射是幂等的，不是技术债）。

---

## 4. 文件面（**实施清单，共 5 片**）

> 本批手写文件 <20 个、无跨模块符号链、无删列 ⇒ **不分派子代理**，主线程单会话串行实施。
> 上位交接 §3.2 的「≤10 文件/片」是为规避子代理 150 轮上限而设，对本批不适用；
> 反之分片并行会把整树留在不可编译中间态（§8.C-3），故不采用。

| 片 | 文件 | 动作 |
|---|---|---|
| **P1 管线** | `android/scripts/scaffold-source-mapping.mjs` | ① `SOURCE_DIR` → `resolveSourceDir()`（仓库根 `模拟宗门美术素材`，`MNZM_ART_SOURCE` 可覆盖，缺失 fail-fast）；② `fileExists()` 缺目录不再静默 null；③ 新增 `CHARACTER_SOURCES`（12 条 `drawable → "<角色目录>/<头像\|全身像>.png"`，源目录名全/半角括号原样照抄）+ `DRAWABLE_BAKE`（按 drawable 覆写 bake，解决「同分类内头像与立绘档位不同」）；④ `CAT_SRC_DIR`/`bakeDefaults` 补 `CHARACTER`；⑤ 生成的 `description` 文案里的 `D:\\` 改掉 |
| **P2 注册与映射** | `android/scripts/resource-registry.json` | 末尾追加 `CHARACTER` 分类块，12 条 `{ "name": "<key>", "res": "<key>" }`（**name == res**，因为运行时按 `avatarKey` 字符串 `resolve`） |
| | `android/scripts/source-mapping.json` | 由脚手架重生成：+12 条 CHARACTER；**逐行 diff 审查**，既有 371 条的 `source` 不得变化（变化即说明映射真源此前已漂移，须当场查明） |
| | `android/scripts/sprite-uid-map.json` | `resource-manifest.mjs` 追加 12 个 UID（字典序 max+1，既有零漂移） |
| | `android/scripts/sources-imported.json` | `import-art-assets.mjs` 产物（源 MD5 + bake 后尺寸） |
| **P3 素材** | `android/app/src/main/res/drawable-nodpi/{avatar_,portrait_}*.webp` ×12 | `node scripts/import-art-assets.mjs`（先 `--dry-run`） |
| | `android/feature/game/src/main/res/drawable-nodpi/…` ×12 | 同上（脚本按 `modules` 双写） |
| **P4 Kotlin** | `android/core/ui/.../SpriteResRegistry.kt` | 枚举 +`CHARACTER(2)` + KDoc 一句说明（角色头像/立绘，寻访与图鉴用） |
| | `android/feature/game/.../ResourcePreloader.kt` | 仅 KDoc：大图类不预载清单补 `CHARACTER`（D-6） |
| **P5 守卫与文档** | `android/app/src/test/.../SpriteCodegenSyncTest.kt` | `expectedCategories` 14 → 15（`"CHARACTER"` 追加在 `"PORTRAIT"` 之后，与 registry 顺序一致） |
| | `android/app/src/test/.../SpriteSourceMappingGuardTest.kt` | 分类 → 烘焙策略表驱动 + 「未知分类必须登记」断言（§2.3） |
| | `android/app/src/test/.../GachaCharacterSpriteGuardTest.kt` | **新增**：配置 ↔ 注册表 ↔ 映射 ↔ 双模块产物四方一致守卫（验收判据③） |
| | `rules/static-resources.md` | 源目录路径改仓库根；§2.1 注册表加 `CHARACTER` 行；§2.2 流程补「角色素材（逐角色一目录）」子流程与档位口径 |
| | `rules/media-source-assets.md` | §4 现状：12 键由「未注册」→「已注册（G16）」，G11 阻塞解除；补一句脚本侧解析口径 |
| | `CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json` | 双更新日志（编辑工具改，改后 `JSON.parse` 校验） |
| 主线程 | `report-G16.md` + `HANDOVER-m1-remaining-3.md` 回写 | 门禁实测值、B 类登记、下一批指针改 G08、进度 6/11 → 7/11 |

---

## 5. 门禁清单（提交前同轮重跑，判据是命令输出原文）

| 门 | 命令（`dir_path = C:\Mnzm\XianxiaSectNative\android`，除非注明） | 预期 |
|---|---|---|
| 脚手架幂等 | `node scripts/scaffold-source-mapping.mjs` 跑两次 | 第二次 `source-mapping.json` 与工作树自比零差异（坑 12：自比基准是 regen 前后，不是对 HEAD） |
| 素材导入 | `node scripts/import-art-assets.mjs` | 12 生成 / 其余「未变更」跳过；无 fail-fast |
| 图集未动 | `node scripts/build-atlas.mjs --atlas-def-only` + `--codegen` | 生成物内容与 G15 基线一致（仅 `SpriteRegistryData.kt` 多 1 个分类块） |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | BUILD SUCCESSFUL |
| 桌面 ctest | `cd android/app/src/main/cpp/gamecore/build/desktop-test && ctest`（PATH 三段前置，见上位 §2.1） | **1394 / 1391 / 3**，3 败必须是同名三条 B 类（`DiscipleFactory.GoldenSequenceSeed42` / `…Seed987654321Female` / `DeterminismProbeTest.DigestMatchesGoldenBaseline`），**零新增**；🔴 特别确认 `SceneEquivalenceTest` 三条不红（本批不动图集，红即误改 `LAYOUT`） |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<绝对 .so>"` | 基线 7336 + 新守卫用例数，**0 失败 0 错误**，按 XML 逐模块汇总（坑 8：必须 `--continue`） |
| detekt / lint | `./gradlew.bat detekt` / `lintRelease` | 绿；两个 baseline **零改动**（只缩不增） |
| 配置源 | `node scripts/gen-game-data.mjs --check` | sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef` **不变**（本批不碰配置中性源） |
| JNI 计数 | `node scripts/check-jni-count.mjs` | 86 / 86 不变（本批不碰 C++） |
| ActionId | `node scripts/gen-action-ids.mjs` | 198 / maxId 1861 不变 |
| 规范门禁 | `node scripts/check-agent-instructions.mjs`（仓库根） | `✓ 全部通过`（预存告警 2 条属正常，判据是「全部通过」行） |
| Room | — | `DATABASE_VERSION` 保持 **59**（素材批不删列，上位 §2.2 明写「G16 素材批不删列」） |
| 树污染 | `git status --porcelain` | 只剩本批文件 + `docs/research/`×2；`atlas-rgba-manifest.json` / `sprite-uid-map.json` 若仅时间戳脏 → 按 D-7 处置 |

**A/B 类判据沿用上位 §3.3**：本批零 C++ 改动 ⇒ 任何新增红都是 A 类，必修。

---

## 6. 登记给后续批次（本批不做，写进 report-G16 §遗留）

| # | 项 | 归属 |
|---|---|---|
| 1 | 🔴 `PortraitPool.getResourceId(name)` 只认 `male_disciple_1..20` / `female_disciple_17`，**不查 `SpriteResRegistry`**（`PortraitPool.kt:13-16`）。G08 把 `portraitRes` 改为模板强制后置为 `portrait_zhouming` 时，`DiscipleComponents.kt:175-179` 会走 `else` 分支回落到通用 `disciple_portrait`，**具名角色在弟子卡/详情页显示成通用像**。修法（解析优先走 `SpriteResRegistry.resolve`）属 G08/G11 显示链，不属素材注册批 | **G08** |
| 2 | `ResourceManifestCompletenessTest` 名为「双模块完整性」实则 `toSet()` 并集 + `count >= 1`（`AtlasManifestSyncTest` 无关），**全仓库无双模块硬守卫**，`male_disciple_*` 等 61 个单模块资源靠「没断言」放行。D-7 的新守卫先对 12 个角色键建立真正的双模块断言；是否推广到全量 drawable 需产品口径（要不要收 61 个单模块资源的债），登记待拍板 | **G10 / 待拍板** |
| 3 | `月城雪/`（第 7 角色，M3 暂缓）有 `全身像.png` + `轮换池背景图.png`（8.7 MB），**无头像**；`未归名大立绘` 属 Q10「先不管」。轮换池上线时另开素材批 | M3 |
| 4 | `docs/design/art-asset-pipeline-improvement.md:1.1#1` 与 `recon-G05-G06-G08-G09.md:656` 仍写「权威源 = `D:\模拟宗门美术素材`」——专题方案属一次性产出不回改，`recon` 的失效由本任务书 §2.1 取代 | 已在本批澄清 |
