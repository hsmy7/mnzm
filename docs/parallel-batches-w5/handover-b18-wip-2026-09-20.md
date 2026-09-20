# 交接文档：B18 回滚臂删除批——中断现场与续作施工卡（2026-09-20 晚）

> 交接对象：接手实施者。来源：B18 施工进行到臂 2 半程，用户指示停止并交接。
> 上游文档：`batch-B18.md`（批次定义与纪律）、`handover-closing-batch-2026-09-20.md`
> （收口批交接，已完成）、`a17a3330f`（判据缺口核查）。
>
> ## ⚠️ 首要警示：工作区当前**不可编译**（臂 2 半程态）
> `NativeEngineFlag.gameViewProjection` 旗标已从定义处删除，但 `GameEngine.kt`
> 3 处**代码引用**与 4 个测试文件的引用未及清理。接手后第一步是按 §2 完成
> 臂 2 剩余编辑（约 6 个文件，全部改法本文已给出），恢复可编译后再走验证。

---

## 0. 进度总览与决策记录

| 项 | 状态 |
|---|---|
| **臂 1（传输臂）** | ✅ **已提交 `df6b70d5a` 且三重验证**（ctest 1556/1556 + 单进程 1553/1553 exit=0 + MirrorConsumerSurfaceGuardTest 6/6 + 双侧编译过）——干净落库，勿动 |
| **臂 2（投影臂 gameViewProjection）** | ⏸ **半程中断**：主源 5 处 gate 已删、旗标已删、mergeGameDataChanges 旧臂已删（工作区未提交、不可编译）；GameEngine 3 块 + 4 个测试文件 + 3 处 KDoc 未清 |
| 臂 3（列级臂 dirtyColumnExport） | 未动（触点已勘察，见 §4） |
| 臂 β（渲染臂 sceneStoreRender/drawAllTiles） | 未动（触点已勘察，见 §5） |
| 臂 γ（G5 战斗臂 BattleSystem→golden） | 未动 |
| 吸收项（month_settlement indexById / aiBeastEncounterTargets 死臂 / Room 死列 / upsertsJson typed 化 / 注释收口） | 未动（定义见 batch-B18.md §2） |

**治理决策记录（重要，验收必读）**：`batch-B18.md` 的硬前置（一个完整发版周期 +
零回滚事件）经 `a17a3330f` 核查**实测不满足**（version.properties 从未涨号、无回滚
计数实现）。**用户于 2026-09-20 晚两次显式指令开工 B18**（知情覆盖该判据）。
接手者继续执行时，应在 B18 完成报告中如实记录此决策链；`batch-B18.md` §硬前置
需随批补一段"用户决策开工"的记录（不建议删原文——保留判据与豁免记录）。

**JNI 基线注意**：臂 1 已删 `nativeSetDirtyExportProtobuf` 端口（42→41），但
`scripts/jni-count.baseline.json` 仍是 42+49=91。门禁脚本对收缩只提示不失败，
**建议随臂 2 提交时一并 `node scripts/check-jni-count.mjs --update` 降基线（90）**。

---

## 1. 工作区未提交变更清单（git status 实测）

| 文件 | 内容 | 处置 |
|---|---|---|
| `nativebridge/NativeEngineFlag.kt` | gameViewProjection 旗标块（19 行 KDoc+var）已删 | 随臂 2 提交 |
| `nativebridge/StateSyncService.kt` | 4 处 gate 已删（reprojectFromSnapshot / importToNative / decodeView 常量化 / feedProjection）+ mergeGameDataChanges 旧 JSON 往返臂已删 + golden 注释 | 随臂 2 提交；:58/:413/:465 三处 KDoc 残留引用待清（见 §2.1） |
| `app/src/main/assets/atlas/atlas-rgba-manifest.json` | **构建副产物**（gradle 触发图集任务重写的 manifest） | **勿混入臂 2 提交**：先 `git diff` 查看内容，若为 frameSha256/时间戳类非语义漂移则 `git checkout --` 还原；若为真实图集变化则单独核查登记 |

---

## 2. 臂 2 剩余施工卡（恢复可编译 → 验证 → 提交）

### 2.1 StateSyncService.kt：3 处 KDoc 残留引用（无编译影响但须清）

`:58`、`:413`、`:465` 三处 KDoc 仍写 `[NativeEngineFlag.gameViewProjection]`。改法：
- `:58`（Decoded.discipleProjections 注释）："（R2.3 第二波，[...] 开时" → "（B18 后恒开——原第一波 JSON 造树形态已随臂删除"
- `:413`（feedProjection KDoc）："（R2.3 第二波，[...] 灰度）：" → "（B18 后恒馈送）："
- `:465`（mergeGameDataChanges KDoc "两臂（R2.3 第二波灰度，[...]）："）→ "（B18 投影臂退役：恒字段级应用，原灰度两臂删除）："

### 2.2 GameEngine.kt：三个 UI 消费块删回滚取数臂（**编译断点，必改**）

`:417-429`（resourcesHeader）、`:440-452`（configEcho）、`:462-~475`（eventLog）。
三块同构，目标形态（以块 ① 为例，②③ 同理替换为 `gameViewStore.configEcho` /
`gameViewStore.eventLog`，eventLog 的 else 分支终止行号需现场核到 `}` 闭合）：

```kotlin
    /**
     * UI 消费块①「资源头部」取数入口（B18 后恒 GameViewStore 投影来源——镜像
     * 按封触及才重投）。"从整份 gameData 快照派生"的第一波回滚取数面已随臂
     * 删除（viewOf 派生函数保留在 GameViewStore 供投影重投与测试 golden 使用）。
     */
    val resourcesHeader: StateFlow<com.xianxia.sect.core.gameview.ResourcesHeaderView> by lazy {
        gameViewStore.resourcesHeader
    }
```

即：删 `if (NativeEngineFlag.gameViewProjection) { ... } else { stateStore.gameData
.map{...}.distinctUntilChanged().stateIn(...) }` 整个分支结构，只留投影分支体。
（原 KDoc 语义"两臂共用 viewOf、同输入同输出"随之改写。）

### 2.3 MirrorConsumerSurfaceGuardTest.kt（**编译断点，必改**）

臂 1 提交版里 `传输臂已退役（B18 后单臂源码面）` 测试仍断言
`flagSource.contains("var gameViewProjection: Boolean = true")` 与运行期
`NativeEngineFlag.gameViewProjection`。改为：

```kotlin
        assertFalse(
            "投影旗标不得回流（gameViewProjection 已随 B18 删除）",
            flagSource.contains("gameViewProjection")
        )
```
（替换上述两条 assertTrue；该测试其余断言保持。）

### 2.4 GameDataFieldPatchGuardTest.kt：两臂对照 → golden 夹具（核心改法）

原"生产两臂"（旗标开/关走 applyDirty 两条分支）的对照面已删。**已设计好的改法：
把被删生产臂的最终语义转写为测试侧 golden**（batch-B18.md 纪律"对照臂保留为
golden 夹具"的落实）：

1. `feed(changes)` 删 `projection` 参数与旗标开关，恒走生产路径
   （`StateSyncService(store).applyDirty(envelopeJson(changes))`）。
2. 新增 golden 参考实现（即被删 mergeGameDataChanges 旧臂的逐行转写）：
```kotlin
    /** golden 夹具：被删生产臂（整份 JSON 往返 + @Transient 承载）的最终语义转写。 */
    private fun goldenRoundTrip(changes: List<Pair<String, JsonElement>>): GameData {
        val before = richGameData()
        val currentJson = json.encodeToJsonElement(GameData.serializer(), before).jsonObject
        val merged = buildJsonObject {
            currentJson.forEach { (k, v) -> put(k, v) }
            changes.forEach { (name, value) -> put(name, value) }
        }
        val decoded = json.decodeFromJsonElement(GameData.serializer(), merged)
        GameDataTransientFace.carryOver(before, decoded)
        return decoded
    }
```
3. 六个测试的 `feed(changes, projection = false)` → `goldenRoundTrip(changes)`
   （返回 GameData，断言处 `legacy.gameDataValue` → `golden`；两臂计数断言改为
   `applyResult` 与 `changes.size` 直判）；`feed(changes, projection = true)` →
   `feed(changes)`。
4. `防复发` 测试删 `for (projection in listOf(true, false))` 循环，单次生产馈送。
5. 类 KDoc "对照面用生产两臂" 段改写为 "对照面 = 测试侧 golden 转写（被删
   生产臂最终语义的冻结快照，B18）"。
6. 失败语义测试里 `assertSame(legacy.gameDataValue, legacy.store.gameDataValue)`
   → `assertSame(feed.store 的初值实例, feed.gameDataValue)`（golden 无 store，
   引用不变断言落到生产臂 Feed 上）。

### 2.5 GameViewStoreGuardTest.kt（**编译断点，必改**）

删除整个用例 `` `灰度旗标关闭即不馈送投影（回滚臂第一波形态）` ``（:140-152 一带，
测的是已删除的旗标关闭行为）。可补一条同位测试：`镜像馈送恒推进投影`（feed 后
`viewStore.resourcesHeader.value.spiritStones == 777L`）——保留"投影被馈送"的
正向覆盖。

### 2.6 MirrorSegmentProjectionBenchTest.kt（**编译断点，必改**）

- `measure(bytes, projection)`：删 projection 参数与旗标开关；decodeView 恒
  `includeDiscipleJson = false`（生产常量；`discipleRowsAsPatches` 参数本测试
  的 decode 段可不传——生产由 dirtyColumnExport 决定，臂 3 退役后恒 true）。
- `runOneScale`：删旧臂计时（`measure(bytes, projection = false)`）与
  "投影臂不得比旧臂慢"断言、"两臂落库全等"断言、"回滚臂不馈送投影"断言；
  保留单臂（生产臂）计时打印（B08-mirror-bench 行改为单臂口径）+ "投影块 ==
  resourcesViewOf(store) 全等" + "投影臂被馈送"断言。
- `measureColumn`：删两旗标保存/设置/恢复（恒生产形态）。
- 类 KDoc "两臂共享同一 store…" 段改写为单臂趋势口径（对照数据引用 B08/B09
  历史登记，不再有生产旧臂）。

### 2.7 GameEngineCoreMonthOps.kt:167

KDoc 文本引用 `mirrorProtobufTransport=false` 的 JSON 回滚臂——随臂 2 改为
"（B18 后无此回滚臂；事件在场证明口径见历史批次登记）"。

### 2.8 验证与提交（臂 2 收口）

```bash
export JAVA_HOME="C:/Users/cp050/.jdks/jdk-21.0.12.1+1"
cd /c/Mnzm/XianxiaSectNative/android
./gradlew :core:engine:testReleaseUnitTest --tests 'com.xianxia.sect.core.gameview.*' \
  --tests 'com.xianxia.sect.core.architecture.MirrorConsumerSurfaceGuardTest' \
  --tests 'com.xianxia.sect.core.nativebridge.MirrorProtoFeedEquivalenceTest'
# 预期计数：GameDataFieldPatchGuardTest 6（含 golden 化改写）+ GameViewStoreGuardTest
# 原数-1+1 + Equivalence 2 + SurfaceGuard 6 —— 全绿后：
node ../scripts/check-jni-count.mjs --update   # 基线 91 → 90（臂 1 已 -1）
```
提交信息：`refactor(engine/mirror): B18-臂2 投影臂退役——gameViewProjection 删除+GameEngine 三块恒投影+旧全量往返臂转测试 golden（…验证数字…）`
（含 NativeEngineFlag/StateSyncService/GameEngine/四测试/MonthOps 注释 +
jni-count.baseline.json 降基线；atlas-rgba-manifest.json 按 §1 处置后不入本提交）。

---

## 3. 臂 1 内容备忘（已落库 df6b70d5a，仅供理解，勿重做）

删 `mirrorProtobufTransport` 旗标 + `nativeSetDirtyExportProtobuf` 端口（Kotlin 声明
+ C++ JNI 实现 + GameCore setter/member）+ applyDirtyFromNative JSON 分支 +
queueViewEvent/harvestBreakthroughEvents 两处 `!dirtyExportProtobuf_` 早退。
C++ `exportDirty()` 恒 `exportDirtyProto()`；`exportDirtyJson` 保留供对拍/测试。
验证：ctest 1556/1556（25.51s）+ 单进程 1553/1553 exit=0 + SurfaceGuard 6/6。

## 4. 臂 3（dirtyColumnExport 列级臂）触点勘察（未动）

- Kotlin 生产：`GameEngineCoreAuthoritativeOps.kt:~201-204`（nativeSetDirtyExportColumn
  推送调用，臂 1 已删相邻的传输推送）；`StateSyncService.kt` decodeView 的
  `discipleRowsAsPatches = NativeEngineFlag.dirtyColumnExport`（臂 2 后已是唯一
  旗标引用）+ :64 KDoc；`GameCoreBridge.kt:~306-316` decl。
- C++：`GameCoreBridge.cpp` nativeSetDirtyExportColumn JNI（:~556 一带）；
  `game_core.h` setDirtyExportColumn/getter/columnLevelDirtyExport_ member +
  exportDirtyProto 的 `useColumnLevel = columnLevelDirtyExport_ && !columnExportBlocked_`
  （game_core.cpp:~711）；测试 `column_export_equivalence_test.cpp` /
  `DiffColumnExportMergeConvergenceTest.kt`（:104）。
- 改法与臂 1/2 同族：恒列级（`useColumnLevel = !columnExportBlocked_`——**异构
  锁存 columnExportBlocked_ 必须保留**，它是运行时正确性机制不是回滚臂）；
  exportDirtyColumnJson 保留作 golden。

## 5. 臂 β（渲染臂 sceneStoreRender）触点勘察（未动）

- 生产：`VulkanRenderBackend.kt:21/116/138/268/556`（renderLegacyDrawAllTilesPath
  / renderLegacyOverlayPath + 双路分支）；`NativeBridge.kt:286` KDoc + `:297-314`
  deprecated drawAllTiles 端口。
- C++：`NativeBridge.cpp` drawAllTiles JNI 实现（~:806-1333 大块）与相关 KDoc
  残句；`RenderLodPolicy.kt`/`SpiritCropRender.kt` 有 KDoc 级引用（核实）。
- 测试：`SceneUpdateChannelTest` / `SceneOverlayProtocolGuardTest`（其"新路径零
  逐 rect 调用点静态门禁"在新臂唯一后仍成立）/ `NativeSurfaceViewTest` 27 用例
  中引用旗标者 / `RenderBackendContractTest`。
- 纪律：Canvas 兜底（SoftwareCanvasBackend）零改动红线不变；删除后跑
  `:feature:game` 全测 + 桌面 GTest（NativeBridge.cpp 变更）+ NDK 构建。

## 6. 臂 γ（G5）与吸收项（未动，定义见 batch-B18.md §2）

G5（BattleSystem→golden）删除生产回退点前需先拍板"native 战斗 error 信封的
用户可见语义"（原回退臂承担了 error 降级）；Room 死列（battleTeam/aiBattleTeams）
须独立走批含 schema migration + 存档回归（batch-B18.md 纪律明文）。

## 7. 终局门禁（B18 全部完成后）

1. `pwsh -File scripts/build-desktop-jni.ps1` 重建桥 →
   `testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<so>"` +
   `detekt compileReleaseKotlin lintRelease`（XML 时间戳核在自己轮内；
   Diff* 0 skip——**DiffMirrorArmConvergenceTest 三臂中 F2 已随臂 1 消失，若该
   测试走旗标驱动需同步改 F1-vs-F3 口径，若直接调 applyDirty/applyDirtyProto
   则无需改——接手时先核这一点**）。
2. 桌面 `ctest`（应 1556）+ 单进程直跑（应 1553 exit=0）+ NDK
   `:app:externalNativeBuildRelease`。
3. `node scripts/check-jni-count.mjs`（基线随删端口逐臂下调：91 → 90（臂1 已生效
   待登记）→ 89（臂3）→ 88（β 的 drawAllTiles））。
4. 文档三件套：方案 §7.3 补 B18 完成行（含用户决策开工记录）；CHANGELOG 4.01.x
   段；`batch-B18.md` 硬前置节补"用户决策开工"记录；本 handover 勾销。

## 8. 红线复述（接手必读）

- 每臂独立 commit、臂内生产+测试同 commit；B05/发现 11 式行为变更记玩家可见口径。
- 删臂后守卫不得失去对照面：golden 夹具 = 转写冻结，不是删除断言。
- `columnExportBlocked_` 异构锁存、`exportDirtyJson`/`exportDirtyColumnJson`
  golden 出口、Canvas 兜底三件**不是回滚臂**，不得删。
- atlas-rgba-manifest.json 副产物不得混入功能提交。
