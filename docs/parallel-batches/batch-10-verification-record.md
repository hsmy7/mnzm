# Batch-10 真机验证记录（实施中落档）

| 项 | 内容 |
|---|---|
| 批次 | 10（真机验证批，[batch-10-device-verification.md](batch-10-device-verification.md)） |
| 本记录性质 | §2.39 附件：逐项验证记录与证据落档（随执行回填） |
| 环境 | MuMu 模拟器 12（网易）：Android 15 / SDK 35，机型档案 Redmi K50（22041216C），abilist `x86_64,arm64-v8a,x86`（ARM64 经转译层运行），adb `127.0.0.1:16416` |
| 偏差登记 | 批文档设备矩阵要求物理真机（arm64 主力机 + ASTC 不支持低端机 + vivo OriginOS）。本次会话仅模拟器可用——模拟器可覆盖：JNI/ART 真实线程模型（B 组核心信号）、渲染三后端（A 组，经 RenderDebugSwitches 强制）、玩法流程（C 组）、视觉（D 组）。**物理真机专属残留**：① vivo OriginOS 勘误机（§2.7 生命周期路径）② ASTC 缺失低端机真硬件解码路径 ③ 真实热状态 ThermalMonitor 档位切换 ④ 前台服务 OEM 省电杀进程恢复。逐项在结果表标注"模拟器"或"真机待补" |
| 构建产物 | debug（守卫激活）/ release（NDEBUG 守卫擦除）双 APK，本批当日构建 |

## 0. 代码侧可观测标记速查（验证期间的 logcat 锚点）

| 标记 | 来源 | 含义 |
|---|---|---|
| `RNG 通道跨线程进入：<入口>（owner tid=%d, 当前 tid=%d）` | `jniWarnRngOffEngineThread`（仅 debug） | B 组核心观察对象；WARN 不 abort |
| `P1-4 线程契约违规：<入口>` + abort | `jniRequireEngineThread`（仅 debug） | 断言升级后首犯即崩标记 |
| `P1-4 owner rebased to tid=%d` | `jniMaybeRebaseOwner`（仅 debug） | 紧急重启换线程重锚——合法信息项 |
| `buildAtlas: ASTC compressed atlas uploaded (id=N)` | AtlasAsyncPipeline | ASTC 压缩路径成功 |
| `buildAtlas: ASTC upload rejected, re-running RGBA assembly` | AtlasAsyncPipeline | RGBA 回退路径触发 |
| `buildAtlas: assembly failed` 等 | AtlasAsyncPipeline | 拼装失败（缺陷） |
| `NativeSurfaceView`（LOG_TAG） | 渲染链 | 图集就绪/淡入时序 |
| `RenderDebugSwitches` | `shared_prefs/render_debug.xml` 键 `force_backend` | debug 强制后端：0=Vulkan / 1=GPU GLES / 2=软件渲染 / -1=跟随策略 |

## A. P0-3 图集异步管线

| 项 | 通过标准 | 结果 | 证据 |
|---|---|---|---|
| A1 异步拼装 + direct 上传链路 | 图集就绪前纯黑、就绪后淡入（无空白窗口）；无 OOM/ANR | **模拟器通过** | logcat 03:29:31.110 `RenderThread started: mode=VULKAN fadeStart set`→fade 0.041→0.264→1.0 渐进（就绪前纯黑、就绪后淡入）；03:29:32 `buildAtlas: ASTC compressed atlas uploaded (id=1)`；RenderHealth 持续 rendered≈55-59/skipped=0；无 OOM/ANR。截图 12-created.png |
| A2 Vulkan RGBA 回退路径 | ASTC 不可用设备（或强制 allowCompressed=false）地图正常渲染 | **模拟器不适用（ASTC 可用）→ 真机待补** | MuMu 模拟 GPU（Adreno 640 profile）支持 ASTC（`Logical device created (ASTC=1, ETC2=1)`），主路径即压缩路径；RGBA 回退分支未被自然触发。logcat 03:29:14.075 |
| A3 软渲染路径 | force_backend=2：地图可玩、无花屏 | **模拟器通过** | `run-as` 注入 `shared_prefs/render_debug.xml` force_backend=2 → 强停重启 → 读档：logcat 04:13:34-04:13:42 `mode=SOFTWARE rendered≈49-55 skipped=0 fade=1.0`；截图 56-sw-ingame.png 地图正常无花屏；恢复默认后 `mode=VULKAN rendered=60` |
| A4 surface 旋转/销毁重建 | 旋屏/切后台：拼装中断不崩溃、重建后图集正常（纪元守卫丢弃旧结果） | **模拟器部分通过**：① 游戏锁定横屏，系统 user_rotation 不触发表面旋转重建（登记：旋屏路径需真机传感器验证）；② HOME 切后台 12s→重进：无崩溃、回到模式选择→读档重进成功、图集正常重建。截图 38-bgfg.png/41-loaded3.png | |

## B. RNG 警告日志观察 → P1-4 正式收口

**协议**（handover §2.6「护栏升级条件」+ 批文档 §1.B）：

1. debug 构建 ≥1 小时混合操作：存档/读档/重启/战斗/秘境/血炼（曾的主线程调用面
   HeavenlyTrial/BloodRefining/SaveLoad）+ 前台后台循环重启。
2. 过滤 `RNG 通道跨线程进入`：零出现 → 执行四入口断言升级（见下节补丁预案）；
   出现 → 登记（入口名/owner tid/当前 tid/复现路径），**不升级**。
3. 升级后复跑确认无误杀（尤其 loopStart 生命周期合法路径——§2.7 勘误教训）。
4. `buildSaveSnapshot` 引擎线程采样回归：存档大小/耗时正常、读回一致。

| 步骤 | 结果 | 证据 |
|---|---|---|
| B1 观察窗 ≥1h（引擎激活 03:29:32 起，混合操作持续覆盖） | 观察窗内混合操作实测：新档创建/保存×2/读档×2（两条路径）/EMERGENCY restart（读档触发）/生产排班/招募面板/建造放置/切后台-回前台×2/道侣结成+叛逃事件（涌现） | logcat-session.txt 全量落盘 03:02 起；观察脚本 `scripts/batch10-rng-watch.sh` 烟测通过 |
| B2 RNG 警告计数 | **0**（最终判定见文末结论） | watch 脚本判定输出 + logcat-session.txt grep |
| B3 断言升级执行与否 | **已执行（2026-09-10，B2 零警告判定后）**：`GameCoreBridge.cpp` 四入口 `jniWarnRngOffEngineThread`→`jniRequireEngineThread` + 过渡守卫函数删除 + 三段注释更新；`GameCoreBridge.kt` 契约头"过渡警告守卫"条目并入 kEngineOnly；`GameRngManager.kt` 线程契约注释同步（2 行，契约注释第三处）。**已验证**：`:app:externalNativeBuildRelease` BUILD SUCCESSFUL；debug 变体原生产物字符串核验——WARN 串 `RNG 通道跨线程进入` 0 次、断言串 `P1-4 线程契约违规` 1 次（升级生效）；release 产物零 P1-4 字符串（NDEBUG 擦除不变）。**门禁全绿（worktree 干净检出补跑）**：detekt 六模块 + 引擎全量对拍 3130/0/0/0——详见文末「门禁补跑」 | 批内 diff（三文件）；worktree 构建日志 |
| B4 升级后复跑 | **通过（无误杀）**：断言版原生库重打包安装（zipalign + 项目 keystore 重签，本会话 05:12）后实测——① 启动→读档 B10 档：全路径（loadGame/nativeImportState/rngRestorePartition/EMERGENCY restart/owner 重锚）零 abort；② 存档：buildSaveSnapshot/rngSnapshotPartition 引擎线程采样零 abort（槽位更新 第13年2月，saveGame SUCCESS 5998ms）；③ 切后台/回前台正常恢复；④ 复跑窗零 `P1-4 线程契约违规`、零 FATAL/SIGABRT | 截图 63-66；logcat 05:14:45 loadGame / 05:16:37 saveGame SUCCESS |
| B5 buildSaveSnapshot 引擎采样回归 | **通过**：saveGame SUCCESS elapsed=8264ms（3 弟子小档，模拟器转译环境绝对值偏慢仅记录）；读回逐字段一致（year=12/month=12/phase=0/spiritStones=14140/disciples=3 与存档行完全匹配）×2 次 | logcat 03:51:14 / 03:56:49 / 03:58:50 三条 SaveLoadViewModel SUCCESS |

**B 组观察窗重要旁证**：
- `03:59:44 EMERGENCY restart: year=12, month=12, recruitList.size=7`——读档触发的紧急重启路径
  （§2.7 vivo 勘误的原始复现形态）在 debug 守卫全开下正常完成，随后 `P1-4 owner rebased`
  恰好触发 1 次——**重锚机制按设计工作，无误杀**。
- `EngineLoop: tick catch-up capped at 3 phases, dropped N`——后台挂机丢旬上限机制正常。
- 全窗零 `FATAL EXCEPTION` / 零 `SIGABRT`。

### B2 断言升级补丁预案（零警告证据到手后实施——本批唯一生产代码改动）

文件：`android/app/src/main/cpp/GameCoreBridge.cpp` + `android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/GameCoreBridge.kt`（本批所有权文件）。

C++ 侧四处，模式统一（以 nativeRngNextInt 为例）：

```cpp
// 前：
Java_..._nativeRngNextInt(JNIEnv*, jobject, jint partitionId) {
    jniWarnRngOffEngineThread("nativeRngNextInt");
// 后：
Java_..._nativeRngNextInt(JNIEnv*, jobject, jint partitionId) {
    jniRequireEngineThread("nativeRngNextInt");
```

四个入口：`nativeRngNextInt` / `nativeRngSnapshotPartition` / `nativeRngRestorePartition` / `nativeRngInitSeed`。
同步注释改动：

- `GameCoreBridge.cpp`：RNG 通道入口处三段"过渡警告守卫/真机验证调用面清干净后可升级"注释改为"kEngineOnly 断言（P1-4 正式收口，见 handover §2.39）"；`jniWarnRngOffEngineThread` 函数删除（release 分支 inline 空函数同步删）；入口分类注释"kEngineOnly（收敛中）"→"kEngineOnly"。
- `GameCoreBridge.kt`：类头契约注释第 25 行 `jniWarnRngOffEngineThread 对非法线程只 WARN 不 abort` 段更新为四 RNG 入口已纳入 `jniRequireEngineThread` 断言（kEngineOnly，debug 首犯即崩/release 零开销）。

验收（批文档 §3.2 = README §4 模板①③⑤）：`:app:externalNativeBuildRelease` + `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<桌面 jni 绝对路径>` + detekt 六模块。

## C. S1-S8 AUTHORITATIVE 真机回归

模拟器会话覆盖说明：新档 B10 从第 1 年连续推进至 19+ 年（≈220 旬结算全过 C++
`runPhaseSettlementCore` 七步管线），期间完成下表所列交互；战斗/秘境/血炼深度
流程受进度门控（需 任务阁/血炼池 等建筑或世界解锁）未直接驱动，登记为真机待补。

| 系统 | 重点 | 结果 | 证据 |
|---|---|---|---|
| C1 S1-S3 每旬七步结算 | 亲属赠送/道德减益丹偷盗钩子实际触发；BREAKTHROUGH_SUCCESS + FTUE 首次突破埋点上报 | **模拟器部分通过**：① 突破实际发生（端木流萤 炼气1→炼气2，弟子详情 646/652 4.8/旬 + 突破率 39.0% 面板）；② 涌现事件：第16年2月 秦云深×端木流萤结为道侣（关系域）、第16年5月 端木流萤脱离宗门（忠诚衰减链）；③ 偷盗钩子未自然触发（需道德减益条件，真机待补）；④ 埋点上报见 logcat TapDB/TapDBManager 活动（真机网络环境上报待确认） | 截图 18/19（弟子面板）、48（消息条涌现事件）、61-62（日志面板） |
| C2 S4 月结生产 | 到期槽结算产出/职业晋升/Room 写回（restoreSlots 整表重放）/手动启动后月结视图（B5 分叉自愈） | **模拟器通过**：矿场 340/月 产线月结持续产出（灵石 1000→2.8万）；年报日志第 4-11 年连续年结记录（第10/11年 +3060/+5400 与产线吻合）；读档往返 restoreSlots 整表重放后生产延续 | 截图 26（产线）、62（年报日志）；logcat save/load SUCCESS |
| C3 S5 任务完成 | 战斗任务结算/奖励入库/幸存者魂力 | **未驱动**（任务阁建筑未建，进度门控）→ 真机待补 | |
| C4 S6 秘境交互会话 | 出发/事件选择/断线续玩/自动结束/手动结算；战报重建与死亡袋物化溢出邮件 | **未驱动**（秘境入口未解锁/不可达）→ 真机待补 | |
| C5 S7 生产排程 | 手动排班/重置 C++ 事务 + Room 后置写回 | **模拟器通过**：灵矿场执事（秦云深）+矿工（端木流萤/施洛神）手动任命成功，总产量 0→340/月；排班经存/读档往返保持 | 截图 23→26 |
| C6 S8 洞天 AI/兽战 | AI 修炼演化（含热档 12/6/3 切换）/兽战遭遇/宗门升级补全 | **部分**：AI 宗门世界推进在跑（世界地图正常）；热档真实切换需真机热状态（模拟器不可达）→ 真机待补；兽战遭遇未自然触发 | 截图 35（世界地图） |
| C7 月结残留三项 | 4g 邮件 / S-17 秘境关闭 / S-20 购买日志平台效应（扇出 ≤3 不回退） | **部分通过**：S-20 购买事务实测（云游商人购精铁刀，灵石 1.4万→9648 扣款精确、库存 2→1）；4g 邮件/秘境关闭未自然触发 → 真机待补 | 截图 59-60 |

## D. WS-5 地图渲染真机视觉

| 项 | 通过标准 | 结果 | 证据 |
|---|---|---|---|
| D1 地形 C++ 生成 | 多种子进出地图视觉正常（位级已由 DiffSectTerrainTest 锁定，此处看渲染链） | **模拟器通过**：新档随机种子地形（草簇/矿洞/岛边缘 drawIslandEdges 141 pieces）渲染正常；本档单一种子，多种子对比待真机补充 | 截图 12/34/41；logcat `drawIslandEdges: 141 pieces (uv=37, atlas=1)` |
| D2 建筑占位增量 | 放置/搬迁/拆除：占位即时正确、无残影 | **模拟器部分通过**：建造栏选择→网格幽灵预览（重叠红=非法占位即时判定/空地绿=合法）正常；确认放置步自动化未完成（拖拽后 ✓ 命中未达）→ 放置/拆除全链待真机人工补验 | 截图 50（红框非法）、52（绿框合法） |
| D3 道路增量装配 | 铺路/拆路：增量与全量视觉一致、autotile 拼接正确 | 未驱动（道路 UI 入口未在本次会话达）→ 真机待补 | |
| D4 chunk 参数化 | 现生产 128²/48px 视觉零差异 | **模拟器通过**：`VulkanBackend initDevice: world=6144x6144 tile=48`（128×48=6144 生产行为）；网格叠加视觉正常（截图 50-52）；无视觉异常 | logcat 03:29:13.697 |
| D5 大规模图性能 | 滑动/缩放帧率同档（chunk 分帧预算生效） | **模拟器通过（小图档）**：RenderHealth rendered≈55-60 skipped=0（Vulkan）/≈53（软件渲）全程零掉帧标志；大规模存档待真机 | RenderHealth 全程日志 |

## E. 稳定性横切

| 项 | 通过标准 | 结果 | 证据 |
|---|---|---|---|
| E1 长跑 ≥2h 或 ≥100 游戏月 | 无 ANR/内存泄漏趋势/前台服务被杀后恢复正确（loopOnRestart 重锚 owner） | **模拟器通过（≥100 游戏月口径）**：第 1→19+ 游戏年（≈220 月）连续结算，观察窗内零 ANR/零崩溃/零 FATAL；GameMonitorManager 内存监控全程 Memory Usage ~1%、Recommended GC: NONE；前台服务+EMERGENCY restart 路径正常（owner rebased 1 次）。2h 真机长跑待补 | logcat 全窗；GameMonitorManager 周期日志 |
| E2 云存档上传下载 | 真机链路顺带覆盖 | **保护性跳过**：设备上存在用户真实云存档（青云宗 87年10月，2026-09-07 同步），上传会覆盖用户云端数据——本会话不触碰。待用户陪同执行 | 截图 28（云存档可见，未操作） |
| E3 WS-1 绝对值顺带观测 | 2x 速每旬非 nativeLoopFrame 耗时 + 反向信封体积抽测——只记录不设门 | **无生产观测面**：代码中无每旬耗时/信封体积的日志埋点（bench 为桌面 llvm-mingw -O3 环境数据）。本批实测替代数据：saveGame 全流程 8264ms（3 弟子小档，模拟器 ARM 转译环境，绝对值仅供参考）；登记建议：WS-1 真机绝对值观测需先补 debug 埋点（另行小批） | logcat 03:51:14 |

## 结论汇总（2026-09-10 会话终稿）

- **RNG 日志结论**：≥60 分钟引擎运行观察窗（debug 构建守卫激活，混合操作：存档×3/读档×4/
  紧急重启×1/生产排班/商人购买/建造流程/招募面板/切后台-回前台×4/软渲强制会话/约 220 旬
  连续结算；90,738 行 logcat）`RNG 通道跨线程进入` 零出现 → **断言升级已执行（P1-4 正式
  收口）**，复跑确认无误杀（B4）。
- **本批验证全绿项**：A1/A3/A4（切后台）/B1-B5/C2/C5/C7（S-20）/D1/D4/D5/E1（≥100 游戏月口径）。
- **模拟器已覆盖、真机残留**：A2（ASTC 缺失机 RGBA 回退）、A4（旋屏，应用锁横屏模拟器不可达）、
  C1 残留（偷盗钩子自然触发、TapDB 上报确认）、C6 残留（ThermalMonitor 真实热档）。
- **进度门控未驱动（真机/深度游玩补验）**：C3（S5 战斗任务，任务阁未建）、C4（S6 秘境，
  入口未解锁）、D2 残留（放置确认步）、D3（道路装配）。
- **保护性跳过**：E2 云存档（设备上有用户真实云存档，上传会覆盖用户数据，不触碰）。
- **WS-1 真机绝对值**：无生产日志埋点，登记"先补 debug 埋点"待办（另行小批）。
- **门禁补跑（worktree 干净检出，§2.32/§2.36 先例）——最终全绿**：主树收口时 batch-09 未提交
  WIP（GameEngine.kt/RoadFacadeImpl/CultivationService 等调用点中途态，core:engine 编译红）
  曾阻塞整模块门禁；本批提交（24a60a4）后以独立 worktree 干净检出补跑：①
  `:app:externalNativeBuildRelease` BUILD SUCCESSFUL（主树实跑）；②
  `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<worktree 重建
  desktop-jni>` → **3130 用例 0 失败 0 错误 0 skip**；③ detekt 六模块 BUILD SUCCESSFUL。
  worktree 环境最小补齐（如实登记）：`api.properties`/`keystore.properties`（gitignored 密钥
  配置）+ `road_tx.h`/`diplomacy_tx.h`（batch-07/09 已提交共享文件引用但在途未入库的头，
  取自主工作树）+ node_modules（junction 指向主树）。
- **本批触碰面验证（主树中途即已成立）**：debug/release 原生产物字符串核验（断言在位/守卫
  擦除）；本批触碰 Kotlin 文件（GameCoreBridge.kt/GameRngManager.kt）在 detekt 报告中零
  违规（主树失败报告所列违规全在并行在途文件）；引擎对拍与 GameCoreBridge.cpp 结构无关
  （desktop-jni 编译面 = gamecore/* + GameCoreJni.cpp，不含桥层）。
- **设备/构建来源**：构建 = HEAD(8b52ad8 前后) + 并行批 WIP 工作树，未提交清单快照见
  [batch-10-build-provenance.txt](batch-10-build-provenance.txt)；守卫代码所在三文件均为
  batch-10 所有权文件，未受并行批触碰（README §3.2 协议）。
