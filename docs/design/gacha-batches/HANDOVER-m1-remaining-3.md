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
> - [`TASKBOOK-G09.md`](TASKBOOK-G09.md) —— **G09 派工细则的唯一真源**（D-1…D-18 + 六项产品拍板 +
>   §2 上位失真 12 条 + §3.6.1 来源文案整改细则）。本文件 §8.F 已改为 G09 收官纪要，
>   §2.2 门禁表已换成 G09 终树值，§2.3 新增坑 19-22（桌面静态表面 / `data_store.cpp` 漏登 /
>   密封接口 mock / 历史序 vs 抽取序）。
> - [`TASKBOOK-G11.md`](TASKBOOK-G11.md) —— **G11 派工细则的唯一真源**（D-1…D-12 + 上位失真清单 +
>   §0 剩余总纲 + §5 切片 + §6 门禁清单）。本文件 §8.G 的内联清单已被其取代（§8.G 现改为 G11 收官纪要）。
>   🔴 任务书 §2.1 的六条失真经实测**复核为真**，另**新增 6 条**（`report-G11.md` §4）。
> - [`report-G10.md`](report-G10.md)（**尚未产生**）—— G10 报告；G10 开工前须按 §8.C-1 写 `TASKBOOK-G10.md`。
> - **本文件** —— 收官基线 + 修订后的作业规程 + 剩余指针。
> **更新时点**：**G11 收官（提交 `a2923bced`）后**；§1/§1.1/§1.2/§2.2/§2.3/§6.2/§7/§8.G/§9 已是 G11 值
> （§2.2 的上一列是 G09 值，G08/G16/G15/G04 值以括注与 §1.1 表保留）。

---

## 1. 当前状态一页速览

| 项 | 值 |
|---|---|
| 分支 | `feat/gacha-m0-m1` |
| HEAD | **`a2923bced`（G11 单次提交，39 文件 +4035/−108）**，其下 `19405358a` = 看护会话文档提交（零代码）、`0fe23b8d7` = G09 独立验收 + G11 任务书入库、`15eef2b2d` = G09 收官 |
| 工作树 | **干净**（提交后 `git status --porcelain` 只剩 `docs/research/`×2） |
| 未跟踪 | 仅 `docs/research/`×2（与本批无关，**不提交**）。🔴 `模拟宗门美术素材/`（572 MB）与 `模拟宗门音乐音效/`（1.6 MB）**已写入 `.gitignore`** —— 2026-09-24 拍板：作为项目指定素材/音频源目录，**只登记不入库**；位置与边界见 `rules/media-source-assets.md`。**旧表述「未跟踪 4 组永不提交」自本文件起作废**。⚠️ **G16 起该目录是素材管线的硬依赖**：`scaffold-source-mapping.mjs` / `import-art-assets.mjs` 经 `android/scripts/art-source.mjs` 解析到它，**缺失即抛错**（不再静默产出全 null 映射），换机器后须先同步素材或用 `MNZM_ART_SOURCE` 指向 |
| 已完成 | **10 / 11 批**（G02、G05、G06、G03、G04、G15、G16、G08、G09、**G11**；分母 11 = 原 9 批 + 拍板新增 G15、G16）。🔴 **但 M1 的完成判据尚未满足**：G10 未做 ＋ **G11 真机未验**（本机无可连设备/模拟器，12 项清单登记在 `report-G11.md` §7） |
| 下一批 | **G10**（唯一一次 RNG 金黄重录 + 全量回归 + 死代码 grep 清零 + 文档收口）——任务书未写，开工前按 §8.C-1 先写 `TASKBOOK-G10.md`；🔴 **另有一条独立交接口**：G11 的 12 项真机清单需用户接设备或授权启动模拟器后另起一轮（`report-G11.md` §7 已把每项的通过标准与已得替代证据逐条列好） |
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

| **G09** | `15eef2b2d` | [report-G09.md](report-G09.md) | **68 文件 +5394/−249**（17 新增：C++ 6 / Kotlin 4 / 测试 2 / 报告 1 …） | **1437/1434/3**（**与 G08/G16/G15 逐条同名 ⇒ 零新增 B 类**；`actual=0xb4f3c6912207f597` 逐字符不变；+36 逐文件归因） | **7419/0/0/18 skip**（对 G08 **+28**，engine +27 / app +1，逐模块账闭合） | 抽卡核心下沉 C++（`gacha_tx.h` + 1871/1872 + `data_inject` 注入两张卡池表）；🔴 **独立随机分区 `GACHA(12)`**（D-3 已拍板，与 `CHAT(10)` 同因）；星级乘区**口径 A** 双端同名同参；保底=第 10 抽本身、碎片单点复用、解锁走 `instantiateTemplate` 唯一口 + 读档幂等补齐；物品统一入库 + 满仓转邮件；`SOURCE_DISPLAY_NAMES` 25→22 文案整改。ActionId **199→201 / maxId 1870→1872**，retired **24==24**；Room 仍 **v59**、proto 零改、JNI **86/86**（新增端口只在测试桥）、game-data sha256 不变、图集 41/`6a122ed2…` 不变、detekt 六模块 **0 error 0 warning**、lint 36 警告全预存。🔴 **首轮实跑抓出 5 条真缺陷并全部根因修复**（桌面注入通道 / 回退臂格序倒置 / 密封接口 mock / detekt 6 条 / 报告数字失真），见 §5.2 与 §2.3 坑 19-22 |

| **G11** | `a2923bced` | [report-G11.md](report-G11.md) | **39 文件 +4035/−108**（生产 12 新 + 21 改 / 测试 7 新类 + 4 改点 / C++ 3 头 1 断言 / 文档 4） | **1437/1434/3**（**与 G09/G08/G16/G15 逐条同名 ⇒ 零新增 B 类**；`actual=0xb4f3c6912207f597` 逐字符不变——本批动 C++ 只动三处显示串常量） | **7471/0/0/18 skip**（对 G09 **+52**：engine +6 / ui +5 / feature:game +41，逐模块账闭合） | 最简寻访 UI（主界面 + Q30 结果页 + 图鉴/公示/历史，单窗口四面 + 窗口级 overlay 叠结果层，零新 DialogType）＋ `GachaDelegate` 引擎线程接线（含 4 个 VM 测试构造点）。🔴 **两条上位修法被实测推翻**：① D-5 只点了一张 Kotlin 色表，实测灵根数色另有 **3 份 C++ 同口径副本**（写进持久化 `discipleSpiritRootColor`，被灵矿/巅峰/世界地图槽位边框消费）⇒ 四份一起对齐 Q31 + 同批改 `exploration_tx_test.cpp` 断言 + 新守卫钉住；② D-6 让照 `LizhanDialog` 做「`sweepGradient` 旋转流光」，实测全仓 `sweepGradient/rotate/InfiniteTransition` **零命中**且本版本 `Brush.sweepGradient` **无 `start/end` 角度入参** ⇒ 取 Q30 另一措辞「描边扫光」（`linearGradient` 逐帧平移 + `GpuTier.LOW` 静态降级，未动 native 渲染链）。🔴 **D-7 根因修复**：`GachaService` 两条臂原地改 `GameData` 字段 ⇒ 四个订阅流不发射（先证伪 `expected:<2> but was:<1>`，再按 `SpiritStoneWallet` 范式改 `copy()`）。ActionId **201/maxId 1872 幂等零漂移**、Room 仍 **v59 零迁移**、零 proto、零新镜像字段、零预载清单、JNI **86/86**、game-data sha256 不变、图集 **41/`6a122ed2…`** 不变、detekt 六模块 **0 error 0 warning**、lint 36 警告全预存、`.so` 由当前树重建（9015808 字节 / sha `7e9353…→40de6b…` / mtime 16:34 三件套）。🔴 **真机未做**：本机 `adb devices` 空、`127.0.0.1:16416` 拒连 ⇒ M1「真机通」判据未满足，12 项清单与已得替代证据登记在 `report-G11.md` §7。⚠️ 组合门跑 **4 轮**（首轮 2 条 detekt 红；源码扫描守卫曾遇 **UP-TO-DATE 假绿**，见 §2.3 坑 23） |

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
> ✅ **G09 是「实施会话中断 + 收官会话复核合一」**：实施会话（2026-09-26 凌晨）落地代码面并写了
> 一份把 Kotlin 门禁标为「尚未执行」的报告；收官会话以复核身份同轮重跑 §2.2 全表，
> **首轮实测 5 条 JUnit 红 + 6 条 detekt 红**（报告初稿写「代码面全部落地」，而新测试从未跑过一次），
> 五条全部按根因修复后第二轮 `BUILD SUCCESSFUL in 23m17s` 全绿。**首轮即判别力自证**——
> 守卫抓的是真缺陷（回退臂十连格序倒置、桌面静态表面缺注入通道），不是跟着实现改断言。
> **九批已提交者均为：全门禁实测绿 + 双 changelog + `report-Gxx.md` + 单次提交。**

> ⚠️ **G04 的提交由「复核会话」完成**：实施会话留下未提交工作树 + 一份把 ctest/detekt/JUnit 误记为绿的报告；
> 复核会话在同一棵树逐门重跑，实测出 **4 处失实**（3 条 `SceneEquivalenceTest` A 类红、6 条 detekt 红、2 个陈旧图集守卫、规模数字失真）并当场修复。
> 教训已回写 report-G04 §二·补 与本文件 §2.3 坑 9 —— **「报告声称绿」不构成门禁证据，必须同轮复跑**。

### 1.2 剩余顺序（**2026-09-26 G11 收官后**）

**只剩 G10**（＋ 一条与 G10 正交的 **G11 真机验证**交接口）。

- ✅ **G09 已单次提交（`15eef2b2d`）⇒ G11 的三条硬前置解除**：① 抽卡结果 DTO 已冻结
  （`GachaPullResult.Success(rows[], unlockedTemplateIds[], pityAfter, …)`，`rows` = **抽取序**、
  格序即下标、只带 id 不带资源键）；② `NotReady` 占位已删除 ⇒ `GachaDelegate.pullOnce/pullTen`
  直接可用（仍零实例化，接线归 G11）；③ 星级乘区双端同名同参 + 战力缓存星级敏感 ⇒
  结果页/详情页读镜像字段不会拿到旧口径。
  🔴 **G09 移交给 G11 的三条实测项**（详见 `report-G09.md` §六）：`GachaPullRow` KDoc 的
  512 档 `avatarKey` 口径、`GachaService` 就地改 map 不 `copy()` 可能使 `starMap`/`fragmentCounts`
  订阅不发射（接 UI 前必须核）、`DialogFeatureRoutes` 的寻访占位对话框。
- ✅ **G08 已单次提交（`8b3c10578`）**：模板实例化、碎片入账单点、开局周明 + 5 万灵石口径已落。
- ✅ **G16 已单次提交（`4ba6c1bdd`）⇒ G11 的「12 个角色精灵键未注册」硬阻塞解除**：
  注册表 / 映射 / 双模块 WebP / UID 四环齐备，运行时入口 `SpriteResRegistry.resolve("avatar_zhouming")` 等
  12 键可解析，且新增 `GachaCharacterSpriteGuardTest` 锁住四方一致（详见 `report-G16.md` §一）。
  ⚠️ **G08 追加一条 G11 必办项**：`portraitRes` 现承载 1024 档全身像，而 512 档 `avatar_<id>` **仍零消费方** ⇒
  小头像位改读 `avatarKey` 属 G11 显示尺寸口径（`report-G08.md` §七-7）。
- ✅ G15 已单次提交（`325da9d5a`），共享面（`models.h` / `disciple_store.h` / `column_dirty.h` /
  `DiscipleTables*.kt` / `Disciple.kt` / `game_view.proto` / `ActionIds.kt`）已落定 ⇒ 后续删列批可在此基线上串行。
- **G09 之后新增/变更的共享面**（G11、G10 必须串行编辑，禁并行分片触碰）：
  `gamecore/system/gacha_tx.h`、`system/star_zone.h`、`data/gacha_pool_db.h`、`src/dispatch_gacha.cpp`
  （1870–1872 段）、`include/gamecore/rng/rng_manager.h`（分区枚举 + `kMaxPartitionId` + 播种三处同体）、
  `core/engine/.../util/RngPartition.kt`、`nativebridge/DiffRngBridge.kt`（测试桥端口）、
  `scripts/build-desktop-jni.{ps1,linux.sh}`（**手写源清单，新增 gamecore 源必须两改**）、
  `GachaFacade(-Impl)/GachaService/GachaPullLedger/GachaPoolConfig.kt`、`test/gacha_tests.cmake`、
  `ActionIds.kt`/`action_ids.h`（生成物）、`scripts/action-catalog/gacha.mjs`。
- ~~**G11 → G10 是依赖链**（G10 的 RNG 重录范围取决于 G11 是否引入新掷点）~~ ✅ **G11 已交付并给出答案**：
  G11 是纯 UI 批，**零新掷点**，且动 C++ 只动三处显示串常量 ⇒ `actual` 逐字符不变、三条 B 类同名
  ⇒ **G10 的重录范围不含 G11**（依赖链已闭合，剩下的就是 G10 自身）。
- G10 最后（唯一一次 RNG 重录窗口）。**G15/G16/G08/G09 四轮均零新增 B 类**（§6.2），
  G09 更是「新增 36 条 C++ 用例 + 引入新分区」而 `actual` 逐字符不变的正例。
- **M1 完成判据**：G10 全绿 + G11 最简 UI 真机通。

### 1.3 本轮新增的三条产品拍板（2026-09-24）

| # | 拍板 | 影响的批次 |
|---|---|---|
| P-1 | 🔴 **师徒系统整体下线**（不只是删「关系」对话框 UI，而是**连根拆掉整个师徒玩法**） | **新增 G15**；并使 G03 报告与本文件原「师徒反向守卫」全部**作废**（见 §10.1） |
| P-2 | 🔴 **血炼池建筑连建筑一起拆掉**（不采用 G02「建筑保留、只断功能」先例） | **G04**（`TASKBOOK-G04.md` §1 范围扩大，见 §5） |
| P-3 | **角色素材批单独开一批**，插在 G04 之后、G09 之前 | **新增 G16**；解除 G11 硬阻塞 |

> 另：原「G04 待拍板项②——`comprehensionAdd` 是否死字段」经实测**自动消解**：配方侧有 **36 条非零**（智悟丹/悟德丹/灵悟丹/明悟丹…），`pill_system.h:219` 真实写入悟性 → **该字段是活功能，随悟性一并保留，不删**。G02 下架的是另一组 `pillType=comprehension` 丹方。

---

## 2. 门禁基线与运行方式（**G11 终树实测，取代 G09 列与本文件初版的 G04/G15/G16/G08 列**）

> 🔴 **G11 列的值由交付会话同轮跑出的原始输出**：组合门跑了**四轮**——首轮实测 2 条 detekt 红
> （新写测试的 `MayBeConst` + `pullLocally` 触 `LongMethod`，都真修未绕），第 2/3 轮为中途候选树，
> **表内数字取第 4 轮（交付树 `a2923bced`，`BUILD SUCCESSFUL in 25m 10s`）**；各轮覆盖哪棵树、
> 以及一次 **UP-TO-DATE 假绿**的实捕过程见 `report-G11.md` §9·§10 与 §2.3 坑 23。
> 🔴 G11 是本序列**第一次动 C++ 显示常量**的一批：三条 B 类与 `actual` 指纹逐字符不变，
> 说明「同表四份副本对齐」未平移任何掷点——这个结论对 G10 的重录范围判定有用。
> G09/G08 列由各自收官会话同轮跑出（各两轮，取第二轮）；G16/G15 值由各自实施会话同轮跑出
> （G15 提交前另经复核会话同轮重跑）；G04 值由复核会话跑出。下表可作 G10 复核的对照基准。

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

| 门 | 命令 | G11 交付树实测值（G09 / G08 / G16 / G15 值沿革见括注与 §1.1 表） |
|---|---|---|
| 桌面 C++ 编译 | `cmake --build .`（desktop-test 目录） | `[36/36] Linking CXX executable test\game-core-tests.exe`，**0 error**（48 条预存 warning）。G09 新增 `gacha_tx.h`/`gacha_pool_db.h`/`star_zone.h` + 两份 GTest 源进构建图（头文件形态，`test/gacha_tests.cmake` 是唯一追加点） |
| 桌面 ctest | `ctest`（同目录） | **1437 总 / 1434 过 / 3 败**，`Total Test time (real) = 53.18 sec`（G09 为 55.07s；G08 1401/1398/3）。**G11 零新增 C++ 用例**（只改 3 处显示串常量 + 1 条断言字面量），故总数与三条失败用例名**与 G09 逐条同名**（837/838/1071）。🔴 `GAMECORE_BUILD_BENCH=ON`（本仓现状）⇒ 计数含 10 条 bench 用例，报数须注明 |
| 🔴 `SceneEquivalenceTest`（坑 9） | `ctest -R SceneEquivalence` | **13/13 全绿**（本批不动图集；图集/生成物变更批必查项） |
| 🔴 `DeterminismProbe` | `ctest -R Determinism` | `actual=0xb4f3c6912207f597 golden=0x490e8dc522e12921`——**与 G08/G16/G15 逐字符相同**；`DigestIsStableAcrossRepeatedRuns` **Passed** ⇒ 独立分区 `GACHA(12)` + 星级乘区**未平移任何既有掷点**（`DeterminismProbe` 哈希行为 transcript、不含 `rngStates`，且金黄夹具不做抽卡） |
| Kotlin 编译 | `./gradlew.bat compileReleaseKotlin` | 含在组合门内，第二轮 `BUILD SUCCESSFUL in 23m 17s`（实施会话首轮报 8 个编译错，已修，见 `report-G09.md` §四） |
| 测试源编译 | 六模块 `compileReleaseUnitTestKotlin` | 0 错误。🔴 G09 教训：**新增测试文件必须至少跑过一次**才算落地——本批 5 条 JUnit 红全部来自「文件在树上但从未执行」 |
| detekt | 六模块 `./gradlew.bat detekt` | `BUILD SUCCESSFUL`；六份 `detekt.xml` 的 `severity="Error"` 与 `"Warning"` 计数**全 0**；两份 baseline **零改动**（只缩不增）。首轮 `:core:engine` 6 条违规逐条真修（`report-G09.md` §5.2-D）：`poolError` 圈复杂度 24 / 16 return、`historyEntry` 8 参、`GameEngineLoadDataOps.kt` 文件级 15 函数、测试面 `UseCheckOrError` + `MaxLineLength`。🔴 **新量化口径**：`LongParameterList functionThreshold: 8` 是「**≥8 即红**」，但 `ignoreDefaultParameters: true` ⇒ 给参数加默认值即不计数；`TooManyFunctions` 文件 ≥15 / 类 ≥20 / **object ≥12** 即红 |
| JUnit 全量 | `./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks --continue "-Dgamecore.jni.path=<绝对 .so>"` | **7471 / 0 / 0 / 18 skip**（app **1011** / domain **1571** / data **814** / engine **2936** / ui **151** / feature:game **988**；692 份 XML 汇总）。对 G09 **7419 = +52**：engine +6（`GachaSubscribeEmissionTest` 2 + `GachaColorSingleSourceGuardTest` 4）、ui +5（`UnifiedGameDialogOverlayLayerTest`，Robolectric 容器能力）、feature:game +41（`GachaRenderModelTest` 18 + `GachaViewModelTest` 8 + `GachaDelegateTest` 5 + `GachaRecruitDialogTest` 10），逐模块账闭合。🔴 本批起 `:feature:game` 有 **Robolectric Compose 渲染/交互测试**（寻访面板真渲染），G10 若再红要先看是否是本批引入的面板 |
| lint | `./gradlew.bat lintRelease` | `Lint found 36 warnings (and 3 warnings filtered by baseline lint-baseline.xml)`、**0 errors**——**36 与 G15/G16/G08 同值** ⇒ 全预存。⚠️ 跑完仍要查 `atlas-rgba-manifest.json`（本批被改脏一次，实测只有 `generatedAt` 一键 ⇒ 提交前 `git checkout --`） |
| 跨语言对拍库 | `pwsh -File scripts/build-desktop-jni.ps1` → `…/desktop-jni/libgamecorejni.so` | `.so` **9015808 字节 / mtime 16:34 / sha256 `40de6b25b2e03512…`**（重建前 `7e93532cefce61074ec2…`）。🔴 **G11 是本序列第一次「改 C++ 但不改体积」**：只换三处 inline 字符串常量 ⇒ **体积逐字节同值而 sha 变**，所以三件套判据里 **sha256 是唯一能证明"由当前树重建"的那一件**，只看体积会把旧库当新库。坑 21 的两条教训依旧有效（先删产物后链接、可 rc=0 零输出而什么都没建） |
| 🔴 桌面静态表面 | `DiffRngBridge.nativeCoreSetGameData`（本批新增**测试桥**端口） | 生产注入走 `GameCoreBridge.nativeSetGameData`（`app/src/main/cpp/GameCoreBridge.cpp`），**桌面 .so 不编该文件** ⇒ 无内联兜底的 `db.gachaPools`/`db.characterTemplates` 在桌面对拍恒为 `PoolNotFound`。需要静态表的 Diff 族必须先注一次（同 `data_store` 的「仅初始化期一次」状态机）。`check-jni-count` 只扫 `src/main` 两个生产桥 ⇒ 测试桥端口不入面 |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **86 / 86**（不变；抽卡双臂复用 `tryExecuteNative`，零新增生产 `external fun`） |
| ActionId | `node scripts/gen-action-ids.mjs` | **201 动作 / maxId=1872**（G08 为 199/1870）；新增 1871 `GACHA_PULL_ONCE` / 1872 `GACHA_PULL_TEN` 落在 1870–1889 独占段内。零漂移判据同前（regen 前后自比，本批**两次**自证） |
| catalog↔guard 退役集 | 脚本扫 `scripts/action-catalog/*.mjs` + `dispatch_guard_test.cpp` 的 `std::set<int32_t> retired` | catalog **201 条**；退役标注 **24 == guard 24**，双向零差集且 24 个 id 的名称两侧逐条相同（G09 只增号，退役集不变）。坑 17 仍有效：guard 侧是 `action::NAME` 形态，带引号 grep 必得假红 |
| 游戏数据 | `node scripts/gen-game-data.mjs --check` | sha256 `035066cbcb891aa124397667e58879d51d4dd4124177ae38b550e1b2c22094ef`——**与 G04/G15/G16/G08 逐字符相同**（G09 只**读**该产物：C++ 注入 `db.gachaPools`/`db.characterTemplates`、Kotlin `GachaPoolConfig` 解析同一份，中性源未动） |
| 素材管线 | `node scripts/scaffold-source-mapping.mjs` → `import-art-assets.mjs` → `resource-manifest.mjs` → `build-atlas.mjs --codegen` | 本批**零 drawable 变更** ⇒ 仅跑 `--codegen`：`显示尺寸保真校验通过：18 栋建筑 + 9 个装饰`、`SpriteRegistryData.kt / TextureAtlas.h 内容未变化，跳过生成`；`sprite-uid-map.json` / `sources-imported.json` 零变化（G16 的 UID 契约未被触碰）。⚠️ 该脚本 cwd = `android/`，`gen-action-ids.mjs` cwd = 仓库根，串在一条复合命令里会让前者 `ENOENT`（坑 4 变体） |
| 规范门禁 | `node scripts/check-agent-instructions.mjs` | `✓ 规范分发架构门禁全部通过`（EXIT=0），规则①–⑤ 全 ✓：① 根文件 26763 / 32768 字节、③ 42 篇文档 444 条内部引用零死链、⑤ 最坏链路 32180 / 32768。**G09 改动了 `docs/ui-read-surface.md` 与本文件 ⇒ 改后复跑**（`b052eeac5` 规范压缩后已无「预存告警 2 条」，G08 那列值作废） |
| Room | `DATABASE_VERSION` = **59**；`disciples` **90 列**、`game_data` 128 列 | **G09 零迁移**（四本抽卡账本与 `templateId` 列自 **v54** 在库；D-12/D-13 豁免历史「格序」字段 ⇒ 零 `@ProtoNumber` 新增）。G08 那条上位失真「动 `templateId` ⇒ v60」依旧有效。**下一批删列则 → v60** |
| 图集不变量 | `android/app/src/main/assets/atlas/atlas-manifest.json` | `sprites=41 / format=ASTC_4x4_LDR / layoutHash=6a122ed22d66bee5`（与 G04/G15/G16 一致 ⇒ 图集零变化） |
| 精灵注册面 | `SpriteCodegenSyncTest` / `SpriteSourceMappingGuardTest` / `ResourceManifestCompletenessTest` / `GachaCharacterSpriteGuardTest` / `PortraitResolverTest`（新） | 全绿。分类仍 **15**；G08 给 `GachaCharacterSpriteGuardTest` 加第 6 例（12 键经**生产注册入口 + 运行时 resolver**可解析），并新增 `PortraitResolverTest` 的**消费点台账**（读点必须经 `resolvePortraitResId`；直引 `PortraitPool` 的白名单只剩 resolver 本体 + `ResourcePreloader`） |

### 2.3 二十六条实操坑（前三条承自 HANDOVER-2，4-8 条 G03 新增，第 9 条 G04 新增，10-12 条 G15 新增，13-15 条 G16 新增，16-18 条 G08 新增，19-22 条 G09 新增，**23-26 条 G11 新增**；第 13 条结论已由 G08 修正）

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
19. 🔴 **G09 新坑：`mockSmart`（`RETURNS_SMART_NULLS`）遇到返回 `sealed interface` 的方法即抛
    `MockitoException: cannot mock this class`，且报错点看起来在别处**。
    触发链是间接的：`withTrackingSource` 的 doAnswer 把 block **真实执行**，block 里的
    `addHerb/addSeed/addMaterial` 未被 stub ⇒ Mockito 要为返回类型 `DomainResult` 造 SmartNull
    ⇒ ByteBuddy 无法代理密封接口。⇒ 凡 mock 的对象上有返回 `DomainResult`/`DeductResult` 等密封类型的方法，
    必须显式 `Mockito.doAnswer { DomainResult.Success(it.getArgument(0)) }` 或 `doReturn`
    （仓库既有注释先例：`ExplorationPatrolRouteTest:98`）。
20. 🔴 **G09 新坑：桌面 JNI 的 `.so` 里静态模板表是空的**——生产的注入端口
    `GameCoreBridge.nativeSetGameData` 在 `app/src/main/cpp/GameCoreBridge.cpp`，而
    `scripts/build-desktop-jni.ps1` **只列 `jni/GameCoreJni.cpp`** ⇒ 凡「刻意不做内联兜底」的新表
    （G09 的 `db.gachaPools`/`db.characterTemplates`）在桌面对拍里恒 `PoolNotFound`，
    症状与「双臂逻辑分叉」长得一模一样。⇒ 走 `DiffRngBridge.nativeCoreSetGameData`（测试桥同语义端口）；
    连带第 21 条。
21. 🔴 **G09 新坑：`build-desktop-jni.ps1` 会「先删产物、后链接」，失败时 `.so` 直接消失，
    而后台会话调用可以 rc=0 且零输出**。实测两次：① 分离会话跑完 rc=0，`.so` mtime/体积均未变；
    ② 源清单缺 `src/data_store.cpp` 时 `ld.lld: undefined symbol: gamecore::data::loadFromJson`
    且 `libgamecorejni.so` 不存在（后续 JUnit 会以 `UnsatisfiedLinkError`/全 `Diff*` 红的形式二次误导）。
    ⇒ 判据只认 **mtime + 体积 + sha256** 三件套；`.ps1`/`.sh` 两份手写清单**每次新增 gamecore 源都要同批两改**。
22. 🟠 **G09 新坑：「历史环序」与「结果格序」是两个口径，用一个列表兼两职会静默倒置十连格序**。
    历史环是**新在前**（`rows.add(0, …)`），结果 DTO 是**抽取序**（第 10 格在末位，格序即下标，D-10/D-13）。
    G09 回退臂把前者直接当后者 ⇒ 十连 10 格整体反序，而单抽看不出问题、C++ 臂也正常，
    只有双臂对拍能抓。⇒ 两条序必须分别构造，并在 DTO 的 KDoc 上钉死（本批已钉）。
23. 🔴 **G11 新坑：源码扫描型守卫不是 Gradle 测试任务的输入 ⇒ `testReleaseUnitTest` 会 UP-TO-DATE 重放旧结论**。
    实测：把 `year_settlement.h` 的灵根色改回旧值（Kotlin 侧已复原），同一条
    `:core:engine:testReleaseUnitTest --tests "*GachaColorSingleSourceGuardTest"` 报
    **`BUILD SUCCESSFUL`、`Task :core:engine:testReleaseUnitTest UP-TO-DATE`**（测试根本没跑）；
    加 `--rerun-tasks` 立刻 **FAILED** 并点名该文件。⇒ 凡「读仓库文件的守卫」（本坑这种 +
    `RngSourceGuardTest` / `MirrorReadOnlyGuardTest` 一族）**复判红必须带 `--rerun-tasks`**；
    报告里写"守卫绿"而该轮是 UP-TO-DATE，等于没测。§2.2 的门禁命令因此一律带 `--rerun-tasks`。
24. 🔴 **G11 新坑：`Modifier.clickable` 会把子树语义合并，Compose 测试的三种查询因此全变形**。
    (a) `onNodeWithText("重复文案")` 在合并树里塌成**一个整窗节点**——对它 `performClick()` 点的是
    **节点中心**（正好命中中间的格子），不是那段文字；(b) `onAllNodesWithText(x).assertCountEquals(n)`
    在合并树里恒得 1；(c) 叠层里与下层**同名的按钮**会让 `onNodeWithText` 直接 "Expected exactly 1 node
    but found 2"。⇒ 数重复文案用 `useUnmergedTree = true`；测「点空白关闭」要在层背景上
    `performTouchInput { click(Offset(角标坐标)) }` 而不是点文本节点；叠层控件给**生产侧 testTag**
    （比靠树序/索引稳）。本批三条红就是这么逐个定位的（`report-G11.md` §6-D/E）。
25. 🟠 **G11 新坑：渲染测试里 `var x by remember { mutableStateOf(派生值) }` 会永久吞掉后到的状态**。
    升星队列原本是 `remember { mutableStateOf(inputs.starUps) }`，而 `starUps` 由 `starMap` **流**派生
    （账本提交经 `stateIn` 异步入队，不在引擎线程回调那一瞬更新）⇒ 首帧算成空的轮次这一层永远不出现。
    改「已确认条数」游标（`inputs.starUps.drop(confirmed)`）后自洽。同时：**抽前快照必须成对下发**
    （`GachaPullShowcase.anchorStarMap`），在回调里现读 `starMap.value` 拿到的是提交前旧值 ⇒ 跳变恒 0。
26. 🔴 **G11 新坑：「把某张表收敛到单一源」的批次必须跨端清点副本——三道门都发现不了双端分叉**。
    `SpiritRoot.countColor` 除自身定义外，在 `exploration_tx.h` / `year_settlement.h` /
    `sect_defense_battle.h` 有**三份 inline 字面量副本**（注释自陈「Kotlin 同式」），它们写进
    **持久化**的 `GarrisonSlot.discipleSpiritRootColor`，被灵矿/巅峰/世界地图槽位边框消费；
    旧五值在 Kotlin `src/test` **零断言**（唯一硬断言在 `exploration_tx_test.cpp:423`），
    所以只改 Kotlin ⇒ 编译绿、detekt 绿、Kotlin 全量绿，而同一屏出现两套颜色。
    ⇒ 收口前 `grep -ri "<旧字面量>"` 要跨 `.kt/.h/.cpp` 全仓扫，副本一并纳入切片文件面，
    并写**跨端源码扫描守卫**（本批 `GachaColorSingleSourceGuardTest` 即此形状）。
    ⚠️ 同批改 C++ 常量后 `.so` **体积可以逐字节不变而 sha256 变**（见 §2.2 对拍库行）——只看体积会误判"没重建"。

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

### 6.2 当前 B 类登记（C++，**G11 交付树实测：仍是 3 条，逐条同名 ⇒ 零新增**；G09 亦同）

```
DiscipleFactory.GoldenSequenceSeed42
DiscipleFactory.GoldenSequenceSeed987654321Female
DeterminismProbeTest.DigestMatchesGoldenBaseline
```

> 🔴 **G09 零新增 B 类的因果链**（report-G09 §三；本批风险等级高于前四轮，因为它是 M1 里第一次
> **新增** RNG 消费 + 第一次追加随机分区）：
> ① 抽卡掷点全部取**新分区** `RngPartition::kGacha = 12`（`rng_manager.h` 枚举 + `kMaxPartitionId`
> 上移 + `seed + 12` 播种，Kotlin `RngPartition.GACHA(12)` 同 id），既有 12 个分区的实例与序列**一字节未动**；
> ② 星级乘区取 `gachaStarMap`（稀疏），`star ≤ 1` 一律 ×1.00 ⇒ 既有名册（含开局周明 1★）与
> `templateId=""` 的存量旧弟子全部走恒 1 分支 ⇒ 不平移任何属性/战力期望；
> ③ `DeterminismProbe` 哈希的是**行为 transcript**、**不含 `rngStates` 映射**，且现有金黄夹具不做抽卡
> ⇒ 两条新面都不进 transcript（这条实测同时作废了本任务书初版「独立分区是为了少几条红」的表述——
> 两个方案在现有套件下都不扰动既有金黄，选独立分区是**结构性**理由：耦合 / 可解释性 / 公平性 / 并行前提）。
> 三条旁证同轮实测：`actual=0xb4f3c6912207f597` 逐字符不变、`DigestIsStableAcrossRepeatedRuns` Passed、
> ctest 1437 的 +36 全部可归因到本批新用例。**派工期间无任何"顺手重录"的口子被使用**（§6.1 红线）。
> ⚠️ 连带面已核：新增分区使 `syncRngStates` 导出的 `rngStates` **多一个键**，对键集/条数做断言的
> `RngSourceGuardTest`（登记 + 快照 12）、`ResidualRngLocalityGuardTest`（最大快照键 11→12）、
> `rng_test.cpp`（快照分区 11→12）同批改齐 ⇒ 零 A 类。

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
| **G09** | 抽卡核心 `gacha_tx`（roll/保底/碎片/升星/解锁/入库/历史/星级乘区/独立分区） | ✅ **已收官 `15eef2b2d`**（[`TASKBOOK-G09.md`](TASKBOOK-G09.md) 派工 + [`report-G09.md`](report-G09.md)）——`recon-G05-G06-G08-G09.md` §G09 落点表**已被 TASKBOOK §2 的 12 条失真清单取代**；🔴 本批首轮实跑抓出 5 条真缺陷（§5.2），教训回写 §2.3 坑 19-22 | — |
| **G11** | 最简寻访 UI：主界面 + 结果页 Q30/Q31 + 图鉴最小 + `GachaDelegate` | ✅ **已收官 `a2923bced`**（[`TASKBOOK-G11.md`](TASKBOOK-G11.md) 派工 + [`report-G11.md`](report-G11.md)）——任务书 §2.1 六条上位失真复核为真并**新增 6 条**（`report-G11.md` §4：C++ 三份色表副本漏项、`Brush.sweepGradient` 无角度入参、`byId!!` 撞红线、消费点 11≠10、`DialogFeatureRoutes:53` 零改、图鉴双 MAX 自撞）；🔴 根治 G08 遗留的「订阅流不发射」生产缺陷（§6-A，任务书 D-7 要求接 UI 前必核的那条） | 🔴 **真机未做**（本机无设备/模拟器）⇒ **M1「真机通」判据未满足**，12 项清单与已得替代证据在 `report-G11.md` §7，需接设备后另起一轮 |
| **G10** | RNG 对拍基线重录 + 全量回归 + 死代码 grep 清零 + 文档收口 | `EXECUTION-PROTOCOL.md` §2 | 必须 G02–G07 全合入后**唯一一次**重录；**§6.3 已累积 G02/G03/G04/G05/G06/G15/G16/G08/G09/G11 十份登记**；Kotlin `Diff*` 预计无需重录（G15 已实证一次，G09 再次实证「新增面只在新输入上生效」，G11 又实证「改 C++ 显示串不动 `actual`」）；G15/G16/G08/G09/G11 五轮**零新增 B 类** ⇒ 三条集自 G04 起未变；🔴 G09 追加两条必核面：`rngStates` 键集含 12 号后的条数断言、`RngSourceGuardTest` 抽卡分区使用计数；🔴 G11 追加三条：复跑一律 `--rerun-tasks`（坑 23 UP-TO-DATE 假绿）、`.so` 判据以 sha256 为准（坑 26）、测试替身债 `FakeGameStateStore.gameData` 断线桩（`report-G11.md` §8-4） |

---

## 8. 下次开工 checklist

> ✅ **G08 的复核与提交已完成（`8b3c10578`）**——原 §8.D 的 G08 五条清单已逐条走完，其中**第 5 条按实测作废**
> （`templateId` 列自 v54 在库 ⇒ 本批零迁移，见 `report-G08.md` §三 Room 行），第 3 条（PortraitPool 解析链）
> 已用 `PortraitResolver` 根治并配 E-3 判别力自证。过程与上位 16 处失真记在 `TASKBOOK-G08.md` §2 / `report-G08.md` §三·§五。
> ✅ **G09 的复核与提交已完成（`15eef2b2d`）**——`TASKBOOK-G09.md` §1 的十条验收判据全部实测达成
> （明细与同轮数字见 `report-G09.md` §二/§三/§四），其中首轮实跑暴露的五条真缺陷已按根因修复
> （`report-G09.md` §5.2）。**下一会话直接走 §8.G（G11 开工前置）**，
> 并且必须先按 §8.C-1 写 `TASKBOOK-G11.md`。

### 8.D G08 收官纪要（细则看 `TASKBOOK-G08.md` 与 `report-G08.md`，本文件不重复）

| 项 | 结论 |
|---|---|
| 构造路径 | `DiscipleService.instantiateTemplate(templateId)` 是**唯一**具名弟子生产口；三条直造臂（兑换码 / 邮件 / `recruitDisciple`）全删，`DiscipleCreationPathGuardTest` 把「入册口 ⊆ 白名单」固化成 CI 红线 |
| 碎片 | 入账单一函数：C++ `gacha_fragment.h::addFragment` + Kotlin 回退臂 `GachaFragmentLedger` + 门面 `GachaFacade.grantFragments`；端口 1870；三向常量（配置/Kotlin/C++）由 `CharacterTemplateGuardTest` 看护 |
| 开局 | 新档 = 周明（`portrait_zhouming`）+ 5 万灵石（不进年度收入）+ `gachaStarMap["zhouming"]=1` 且碎片账本无该键（Q34 字面） |
| 显示链 | `resolvePortraitResId` 两跳（通用像域 → 注册表域）；10 处读点/7 文件统一；直引 `PortraitPool` 只剩 resolver 本体 + 预载清单 |
| 移交 | G09 无遗留前置；G11 领一条必办（头像位改读 `avatarKey`）；G10 领 8 条登记（§6.3） |

### 8.F G09（抽卡核心批）——已收官（提交 `15eef2b2d`），下一批 G10（G11 已收官，见 §8.G）

1. ✅ **`TASKBOOK-G09.md` 已是该批唯一派工真源**（18 项决策 D-1…D-18 + 六项产品拍板 + 上位失真清单，
   含 12 条 `recon-G05-G06-G08-G09.md` §G09 的实测作废项）。开工前写的这份任务书把
   「1870 已实裁、`dispatch_gacha.cpp`/端口/`gacha_tests.cmake` 均已由 G08 建好、碎片与升星已单点」
   三处 recon 失真提前挡掉，实施期未复现 G16/G08 式的返工。
2. 🔴 **D-3 的最终形态是独立分区 `GACHA(12)`，不是任务书初版的「取 `SYSTEM`」**（用户 2026-09-26 拍板，
   本仓 `CHAT(10)` 为同形先例）。⇒ G11 之后任何新玩法若由**玩家点击时序**驱动，一律照此追加新分区，
   并同步三处：`rng_manager.h` 枚举 + `kMaxPartitionId` + `initSystemSeed` 播种、
   `RngPartition.kt` 枚举、`RngSourceGuardTest` 登记表 + `rngStates` 键集断言面（见 §6.2 连带面）。
3. 🔴 **上位交接的「复用钱包扣减语义」这类一句话会漏掉 sealed-class 键分裂**：`SpiritStoneReason`/
   `SpiritStoneSource` 的键 = 类名 ⇒ 新消费场景必须新增 `object Gacha`，否则两条臂写进两套
   `annualExpenditureByReason` 键（年报分裂）。同类连带面已在 `report-G09.md` §五 逐条登记。
4. 🔴 **C++ 建不了邮件**（`state::GameState` 无邮件字段，`month_settlement.h`/`merchant_settlement.h`
   两处注释明写「草稿丢弃」）⇒ 满仓溢出只有「信封回传 + Kotlin `InventoryNativeForward` 投递」一条路，
   任何新增发放类事务都必须接这一路，否则静默丢件。
5. ⚠️ **本批最大的工程教训（已回写 §2.2 / §2.3 坑 19-22）**：实施会话把「代码写完」当成「批次完成」，
   新测试从未执行 ⇒ 五条真缺陷全部漏到收官轮。⇒ §8.C 的「主线程终树同轮重跑」必须包含
   **新增测试至少跑一次**，且 `report-Gxx.md` 的验证节只允许抄同轮命令输出。
6. 📌 **移交 G11 的三条实测项**：结果格 `rows` = 抽取序（格序即下标）；`avatarKey` 512 档口径写在
   `GachaPullRow` KDoc；`GachaService` 就地改 map 不 `copy()` ⇒ UI 订阅可能不发射（接 UI 前必核）。
   另：`DialogFeatureRoutes.kt:68` 的寻访占位对话框由 G11 替换；星级乘区是否进弟子详情属性面板
   属**产品口径，仍待拍板**（`report-G09.md` §六-1）。

### 8.G G11 收官纪要 + G10 开工前置（下一批）

> ✅ **G11 已收官（`a2923bced`）**——`TASKBOOK-G11.md` §1 的十条验收判据里 **①–⑧⑩ 全达成**（
> 逐条实测证据见 `report-G11.md` §2），**⑨ 真机未做**（本机无可连设备/模拟器；12 项清单与
> 已得替代证据逐项登记在 `report-G11.md` §7）。任务书 §2.1 的六条上位失真里 **#1/#2/#3/#5 复核为真、
> #6 成立**，另**新增 6 条**（`report-G11.md` §4：C++ 三份色表副本漏项、`sweepGradient` 无角度入参、
> `byId(tid)!!` 撞红线、直接消费点 11≠10、`DialogFeatureRoutes:53` 本已指向、图鉴双 MAX 自撞）。
> 🔴 本批另根治一条 G08 遗留、G09 登记在案的生产缺陷：**`GachaService` 两条臂原地改 `GameData` 字段
> ⇒ 四个订阅流不发射**（`GameStateStoreImpl` 的提交判据是引用比较）。这条**只有接 UI 才会暴露**，
> 快照读面永远正确，所以服务侧测试全绿了四个批次。

**G10 开工时必须带走下面这些（都是 G11 实测出来的口径，不是推测）**：

1. 🔴 **金黄重录范围**：G11 未引入任何新掷点（只改三处**显示串常量** + 一个 UI 批），
   `actual=0xb4f3c6912207f597` 在改前/改后**逐字符不变**、三条 B 类同名 ⇒
   **G10 的重录窗口只需处理 G02–G09 累积的既有 3 条**，G11 不入账。开工第一步仍先
   `ctest -N` 与 §2.2 对账，别抄本报告外的旧计数（坑 9）。
2. 🔴 **两条 G09 追加必核面不变**：`rngStates` 键集条数断言（GACHA=12 已在册）、
   `RngSourceGuardTest` 抽卡分区使用计数——上限型守卫在「只删不改」时会反咬（坑：RngSourceGuardTest
   按模块断言违规点**数量上限**）。
3. 🔴 **§6.3 累积登记逐条处置**（九批 + G11），其中 G11 新塞进 4 条：
   `GameNotification.RecruitFailed` 生产者存废、色板对齐债（G12）、`gacha_pull`/`gacha_unlock`
   埋点上报（G12）、**测试替身债**（`FakeGameStateStore.gameData` 是每次访问新建一次性
   `MutableStateFlow` 的断线桩 ⇒ 用它测「派生流刷新」必然假绿；`FakeAtomicStateStore` 缺生产的
   `!==` 提交守卫）——这条是 G10「死代码/基建清零」的直接对象，见 `report-G11.md` §8-4。
4. 🔴 **复跑门禁一律带 `--rerun-tasks`**（坑 23）：G11 实测源码扫描守卫曾被 UP-TO-DATE 重放成假绿。
5. ⚠️ **动 C++ 显示常量的体积可能不变而 sha 变**（坑 26 尾）：`.so` 判据以 sha256 为准。
6. ✅ **UI 消费面已接入并登记**：`docs/ui-read-surface.md` §2.1 末句 + §3.2 行已在 G11 回写，
   G10 文档收口时不要重复登记，只需核对是否新增读面。

### 8.H G11 开工前置（历史，已由 `TASKBOOK-G11.md` 取代，保留备查）

1. 🔴 **先实测再写任务书**（§3.4 两项回查）：G09 的 DTO 已是 `Success(rows[], unlockedTemplateIds[],
   pityAfter, pricePaid, spiritStonesAfter, poolId)`——**只带 id 不带资源键**（D-10），
   资源键必须经 `CharacterTemplateDb.byId(tid)` 查（G08 的 `PortraitResolver` 链），
   禁止在 DTO 上再挂一份 `avatarKey`（第二真源）。
2. 硬门：UI 不驱动 tick（§6.5 焦点域已移除）；不新增第二通知总线；`GachaDelegate` 全仓零实例化
   是本批**故意**留的（G09 不接 UI），G11 必须补 `GameViewModel` 侧接线与 ViewModel 一个（继承 `BaseViewModel`）。
3. ~~流光/特效须 Vulkan + Canvas 双路径可过并更新 `android/docs/renderer-feature-checklist.md`~~
   🔴 **G11 实测作废**：寻访界面是 Compose `Dialog` 独立窗口，不经过 `NativeSurfaceView`/`SoftwareCanvasBackend`，
   该清单不适用（理由与代价见 `TASKBOOK-G11.md` §2.1-1 与 `report-G11.md` §4-1）。
   对话框**不必新增 `DialogType`**——`DialogType.Recruit` 已在 `OverlayDialogRouter` 穷举分支与
   `DialogFeatureRoutes` 路由内，换分支体即零改四个注册/守卫文件。
4. 验收含**真机通**（M1 完成判据之一）：`report-G11.md` 必须写清真机验证做到哪一步，未做项显式列出。
   ⚠️ G11 实际交付时**真机项整体未做**（无设备），已按本条要求逐项显式登记。


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

- **M1 完成度：10 / 11 批已提交**（G02 / G05 / G06 / G03 / G04 / G15 / G16 / G08 / G09 / **G11** = `a2923bced`，
  每批都是全门禁实测绿 + 双 changelog + 报告 + 单次提交）；只剩 **G10** 未开始实施
  （分母 11 = 原 9 批 + 拍板新增的 G15、G16）。
  🔴 **但「M1 完成」这句现在还不能说**：任务书把「G11 最简 UI **真机通**」写成 M1 判据之一，
  而 G11 的真机项**整体未做**（本机 `adb devices` 为空、batch-10 用过的 MuMu 端口 `127.0.0.1:16416`
  拒连、未找到 MuMu 安装位）。已做的最强替代证据是 Robolectric 真渲染 10 例 + Compose 容器能力 5 例
  （`GachaRecruitDialogTest` / `UnifiedGameDialogOverlayLayerTest`），它们能证明「渲染得出来、点下去反应正确」，
  **不能**证明帧率/解码耗时/低端机流光降级/关窗后宿主状态。12 项清单逐项待验，见 `report-G11.md` §7。
- 🔴 **G11 的诚实账（三点要如实说）**：
  ① **组合门跑了四轮**，不是一轮即绿：首轮 `BUILD FAILED`（新写测试触 2 条 detekt 红 + `pullLocally`
  触 `LongMethod`），第 2/3 轮是中途候选树，第 4 轮才是交付树——各轮覆盖范围在 `report-G11.md` §10 逐轮标注。
  ② **实捕一次 UP-TO-DATE 假绿**：把 C++ 色表副本改回旧值后，守卫复跑报 `BUILD SUCCESSFUL`，
  实际是 `testReleaseUnitTest UP-TO-DATE` 重放旧结论（C++ 头不是 Gradle 输入）；加 `--rerun-tasks` 才判红。
  这条已升格为 §2.3 坑 23，G10 复跑必须带。
  ③ **本批把「色表收口」从 1 处扩成 4 处**（Kotlin + 3 份 C++ inline 副本 + 1 条 C++ 断言），
  超出任务书 §5 的允许文件面——理由是「只改 Kotlin 会同屏两套色」这条因果链有直接证据
  （`GarrisonSlot.discipleSpiritRootColor` 的写者与三类读点逐文件点名）。同时金黄 `actual` 逐字符不变，
  没有占用 G10 的重录窗口。
- 🔴 **G09 的诚实账（五点要如实说）**：
  ① **本批是分两个会话完成的**：实施会话（2026-09-26 凌晨）落代码 + 写了一份**把 Kotlin 门禁显式标为
  「尚未执行」**的报告；收官会话以复核身份同轮重跑 §2.2 全表。⇒ 「报告自称落地」在本批**第一次**
  被实测证伪得如此彻底：首轮 5 条 JUnit 红 + 6 条 detekt 红，全部来自「新测试从未跑过一次」。
  ② **首轮红点里有两条是生产侧真缺陷**，不是测试写法问题：回退臂把历史序当结果格序（十连 10 格整体倒置）、
  桌面静态表注入通道缺失（连带 `data_store.cpp` 两腿漏登）。两条都按根因修复并复跑，
  **没有放宽任何断言、没有动 baseline**（只缩不增）。
  ③ **首轮数字同时修正了报告初稿的两处失实**：ctest 1427→**1437**、`star_zone_test.cpp` 9 例→**10 例**
  （初稿自身加法 `1401+19+9+5+2=1436` 已不自洽）。⇒ 教训：抄来的计数必须与 `ctest -N` 对账。
  ④ **`check-jni-count` 86/86 是靠「端口只加在测试桥」维持的**：`DiffRngBridge.nativeCoreSetGameData`
  不入生产面（该门禁只扫 `src/main` 两个在册桥）。这是**有意的绕行**，理由与代价已在
  `report-G09.md` §5.2-A 与 §2.2「桌面静态表面」行显式登记，不留隐性豁免。
  ⑤ **主动未做**：`assembleRelease` 未跑（本批零素材变更）；**真机未验**——「寻访点击后真出货」
  只有 C++ GTest + 跨语言双臂对拍的静态证据链，且 `GachaDelegate` 仍零实例化 ⇒ **无生产入口**（属 G11）；
  星级乘区是否进弟子详情属性面板**待产品拍板**（`report-G09.md` §六-1）。
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
