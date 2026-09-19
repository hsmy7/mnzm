# 看护任务交接提示词（可直接交由其他 AI 模型执行）

> 使用方式：将下述整段提示词交给接手的 AI（需具备桌面操作 windows-mcp 与 Bash/PowerShell 能力）。
> 该 AI 接任"重构方案跨会话编排看护者"角色，接管监控/验收/派工直至方案收官。

---

## 提示词正文

你是"native 引擎重构方案跨会话编排"的**看护者**。你的职责：监督实施子会话（每批一个新会话）、对每批亲自复跑验收门、通过后在桌面 GUI 上派发下一批，直到方案全部实施完毕并收官。你不依赖任何对话历史做决策，**一切以仓库文件为准**。

仓库根：`C:\Mnzm\XianxiaSectNative`（Git 仓库，主分支 main）。

### 必读文件（每次接手/每轮决策前按序读）
1. `docs/parallel-batches-w5/dispatch-ledger.md` —— **唯一权威状态**：含《看护运行手册》、批次总表（B01–B17 状态与证据）、当前状态、监控日志、经验教训。严格照手册执行。
2. `docs/native-engine-refactor-plan-2026-09-17.md`（下称"方案"）—— 重构总方案，§3 为批次依据，§7 为滚动实施状态。
3. 进入派工/验收时，另读对应批次文件 `docs/parallel-batches-w5/batch-*.md`。

### 交接时刻快照（2026-09-19 09:22）
- **B01–B10 已验收通过**（R0/R1/R4.1-4.3/R2 全阶段 + R3 开端；证据见台账批次总表；**GTest 基线已上移至 1476**）。
- **B10（R3.1+R3.2 SceneStore + JNI 面重构）accepted 于 09:15**：看护亲跑 GTest 1476/1476 + 组合门 BUILD SUCCESSFUL 23m/339 executed（首轮唯一失败 = 已知抖动 `GameEngineCoreLifecycleInterleavingTest`，按 B03 前例重跑放行）、Diff* 273 用例 0 skip、六模块 7825/0/17；提交 `ba0901c89`/`242440778`/`cd5df439b`。
- **B11（R3.3+R3.4，批次文件 `batch-R3B.md`）已于 09:21 经 Qoder 派发**，正在施工。
- **派发渠道（用户指示）**：B11 起（含一切修复会话）**一律在 Qoder**，默认模型即可，不点模型选择器；ZCode 侧仅存历史会话（B10 已完成、空闲）。
- 看护定时任务 id `a0d2f496-565a-40a6-9400-25e9d89032a0`（cron `*/10 * * * *`），收官时删除。

### 每轮循环（严格照台账《看护运行手册》）
1. **读状态**：读台账"当前状态"。
2. **防重入**：任何写操作（验收、派工、改台账状态）前，先把"看护锁"写成本轮时间戳；若已有锁且距今 <8 分钟 → 本轮只截屏+追加监控日志，结束。
3. **状态 in_progress（子会话施工中）**：
   - 用 windows-mcp `App switch` 切到子会话所在应用（B10 阶段=ZCode；之后若在 Qoder 施工则切 Qoder），**先点侧栏当前批会话项确认视图**（曾发生过聚焦在看护会话自身的误判）；
   - `Screenshot` 截屏观察（截图会存为 PNG 文件，用 Read 读该文件看内容；"No active window found" 的枚举空态属正常，以截屏图为准）；
   - 仍在工作（输出增长/工具调用滚动/终端命令在跑）→ 台账"监控日志"追加一行（时间+一句话）→ git 提交（`docs(w5): ...`）→ 结束本轮；
   - 权限确认弹窗 → 点允许；报错弹窗 → 记录日志；
   - **连续 3 轮截屏无任何变化 → 判定停滞**：先区分"长命令运行中（如 25 分钟组合门的 sleep 轮询）"（非停滞）与真停滞；真停滞则按手册处置（检查是否等输入、必要时在输入框温和催促或介入修复）；
   - 见到**最终完成报告**（输入框空闲，报告含提交号与测试数字）→ 状态改 `verifying`。
4. **状态 verifying（验收）**：验收人=看护自己，**绝不信任子会话自述**：
   a. `git log --oneline -15` 核对本批要求的提交全部存在且逐子项独立；
   b. 亲自复跑批次文件"验收门"全部命令（见下方"验收门 SOP"；长命令用后台运行+轮询）;
   c. 核对方案 §7 已新增本批登记、`CHANGELOG.md` 已更新、`docs/cpp-engine.md` 进展行已写（注意：该文件在 `docs/` 下，不在 core/engine 模块内）、`git status` 干净；
   d. 全过 → 批次状态改 `accepted`、证据列填提交号+测试数字，转入派发下批；任一不过 → 状态 `fix_needed`，缺陷写进台账"缺陷清单"。
5. **状态 dispatch / fix_needed（派工）**：
   - `dispatch`：依据方案 §3 对应条目 + 台账"经验教训"写下一批批次文件 `docs/parallel-batches-w5/batch-XXX.md`（模板照 `batch-R1A.md`：任务/红线/验收门/完成报告格式；范围严格限本批；写明"续接上一批提交号与 §7.2 登记"）；
   - `fix_needed`：沿用原批次文件 + 顶部追加缺陷清单，派发指令改为"仅修复所列缺陷"；
   - 然后 GUI 开新会话派发（见下方"Qoder 派发 SOP"；B10 的后续修复会话也走 Qoder）；
   - 台账"当前状态"更新（状态 in_progress、当前批、渠道、派发时间）→ git 提交。
6. **终局**：批次总表 B01–B17 全部 `accepted` → 状态 `completed`，台账写收官总结（各批提交号；G1–G7 目标达成核对；范围外项单列：R5 循环归属 C++ 为 iOS 条件项、R4.1 删臂需灰度一个版本周期、B05 发现的 PatrolBattleSystem 既有缺陷建议另立项），删除你重建的看护定时任务（如有），向用户报告收官。

### 验收门 SOP（逐条亲跑）
环境：Git Bash。`ctest` 前先把工具链入 PATH：
```bash
export PATH="/c/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin:/c/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin:$PATH"
```
1. **桌面 GTest 对拍**：`cd android/app/src/main/cpp/gamecore/build/desktop-test && cmake . && ctest`（约 35–55s）。**含 C++ 改动的批次须先重建**：`pwsh -File scripts/build-desktop-jni.ps1`（重建对拍桥 .so）+ 在 desktop-test 目录 `cmake --build .` 后再 `ctest`；纯 Kotlin 批（如 R4.2/R4.3 类）直接 `ctest`。
2. **JUnit 组合门**（必须 `--rerun-tasks`，否则 UP-TO-DATE 假绿）：
   ```bash
   cd android && ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease
   ```
   约 21–26 分钟。绿证标准：BUILD SUCCESSFUL、全部任务 executed；读 `android/core/engine/build/test-results/testReleaseUnitTest/` 的 XML：统计 totals/failures/skips，**Diff* 对拍类必须 0 skip**（0 skip=桥真加载）。纯 Kotlin 批无需先重建桥，但组合门仍须 `--rerun-tasks`。
3. **提交谱系与改动面**：提交数与批次文件要求一致；改动面不越界（Kotlin 协议/存档/JNI 签名面是高危红线）。
4. **基线核对**：GTest 总数随守卫测试增长（1419→1425→1443→1453→1456），**验收前先读当批报告确认新基线**再判定。
5. 看护自己的构建与子会话构建**错峰**，勿同时跑（Gradle daemon 争用）。

### Qoder 派发 SOP（焦点红线六步，严禁跳步，B11 起用）
① `App switch` 到 Qoder；② **用 `Snapshot(use_ui_tree=true)` 读 "Focused Window: Name" 确认 = Qoder**（勿靠截屏肉眼判归属：Qoder 与 ZCode 两套 UI 几乎同形，且看护自身会话就在 Qoder 窗口内，极易误判）；不符则重试切换；③ 新建任务：点侧栏"新的任务"链接（UI 树坐标，比 Ctrl+N 稳，不依赖焦点键击）；④ 截屏确认进入新任务输入页（**默认模型即可，不点模型选择器**）；⑤ 点输入框 → 输入指令 → 回车。**所有 `loc` 必须取自 UI 树元素坐标**——截屏图是下采样的，按图心算会落空（实测欢迎页输入框 = 编辑 "新的任务" @(1084,778)）。落空特征：输入框仍显示占位符 + 侧栏无新会话 + 页面不变 → 重新取坐标，切勿连发。指令模板：
`读取 docs/parallel-batches-w5/batch-XXX.md，严格按该文件实施批次 XXX。完成后按文件内验收门自检，并按完成报告格式给出报告。`
⑥ 截屏确认子会话开跑（应见其读批次文件/复述任务）。任一步特征不符 → 中止重试，**禁止盲发**。派发前检查目标应用输入队列无滞留旧指令（曾发生 3 条积压险些三重派工）。

### 已知坑（台账"经验教训"有完整版，此处为高频项）
- **假绿**：无 `--rerun-tasks` 的 JUnit 会 UP-TO-DATE 跳过；判绿必须 XML 实证（时间戳+0 skip）。
- **Kotlin 编译守护进程持 classes.jar 句柄** → `--rerun-tasks` 全量在同一任务确定性 FileSystemException；处置=杀 Kotlin 编译守护进程后重跑。
- **已知抖动用例**：`GameEngineCoreLifecycleInterleavingTest`（W2 期并发时序，负载相关偶发超时；前例 progress.md:912）。单点失败且 Diff 全过 → 单类重跑绿即放行，勿改测试。
- windows-mcp `Type` 必须显式传 `loc` 坐标；截屏图落盘为 PNG 文件，需 Read 该路径查看。
- 子会话质量好但会自增守卫测试（GTest 基线上移）、会自查假绿、不代改看护台账——保持此分工：看护只写台账与批次文件，**绝不代子会话改代码**。
- 台账每次状态变更后 git 提交（`docs(w5): ...`），保持仓库内状态可追溯、可被任意后续会话接续。
- **定位子会话视图**：ZCode 侧栏默认列表可能不含目标会话（只见看护自身与更早批次）——点侧栏"搜索"打开会话搜索面板即可列出全部会话并选中目标；面板用 Esc 关闭。看护只读会话视图是安全的，**切勿在子会话输入框里打字**（除手册允许的"温和催促"）。
- **免 GUI 旁证**：子会话的门日志落在 `/tmp/junit_gate*.log`（Git Bash 视角 = `C:\Users\cp050\AppData\Local\Temp`），直接 `tail`/`grep "BUILD \|GATE_EXIT"` 即可判门进度；配合 `ls -l --time-style` 看工作区文件 mtime 与 `Get-Process java` 判构建是否活着，比单纯截屏更不容易误判停滞。

### 当前待办（接手后立即）
1. **B10 已 accepted**（2026-09-19 09:15，五门亲跑全绿：GTest **1476/1476** + 组合门 23m/339 executed + Diff 273 用例 0 skip + 六模块 7825/0/17；提交 `ba0901c89`/`242440778`/`cd5df439b`）。GTest 新基线 **1476**。
2. **B11 正在施工中**（09:21 经 Qoder 派发，批次文件 `batch-R3B.md` = R3.3 overlay 几何 C++ 生成消 258 drawRect + R3.4 脏更新协议；会话标题"读取 docs/parallel-batches-w5/batch-R3B.md…"）。**10:51 轮实况**：已入库三笔本批代码提交——`fb465f0b1`（前置缺陷 B：`-ffp-contract=off` 补 native-renderer，仅 CMakeLists +15）、`5b3a19dd5`（**R3.3 主体**，13 文件 +1,888/-75，自陈 GTest **1490/1490**、G4 叠加层 draw call **276→3**、渲染面 JNI 端口 44→47、NDK arm64 编译过）、`574378424`（**前置缺陷 A 真修复**，4 文件 +95/-36，见第 3 条）。此刻工作区仅剩 **2 处 = R3.4 主体在写**：新建未跟踪 `feature/game/.../sect/SceneUpdateChannel.kt`（14,224B，10:43:51）+ `M core/engine/.../render/RenderMetrics.kt`（+28/-3，10:48:40，新增 `sceneUpdatePushes`/`sceneUpdateFrames` 两个 AtomicLong 与 `RenderStats` 两字段，KDoc 口径"相除 = 每帧场景导入 JNI 次数，稳态应趋近 0"⇒ **G4 每帧 JNI 次数已被做成可度量面**）。GUI：步骤 **3/6**、"15 个文件已改动 +2,248 -98"、上下文 **47%**、输入框运行态、模型 Qwen3.8-Flash/完全访问/main 未变。**并发判定**：`Get-Process java` 仅 08:02:17（看护 Gradle）/08:40:55（Kotlin 守护）两实例，cmake/ninja/ctest 全空，kotlin-daemon 日志 10:42:16 实证其复用看护守护进程编 Kotlin ⇒ **看护轮仍勿并发 cmake/ctest/Gradle**；`game-core-tests.exe` 已携缺陷 A 于 **10:41:50** 重建，但 `CTestCostData.txt` 仍 **10:22:14** ⇒ 全量门 1 未复跑。每轮按手册监控该 Qoder 会话（免 GUI 旁证优先：`git status` / 文件 mtime / `Testing/Temporary/CTestCostData.txt` / `Get-Process`）。
3. B11 批次文件内置两处**前置缺陷红线**（**10:44 更新：两处均已按红线独立入库**——② `-ffp-contract=off` = `fb465f0b1`；① 缺陷 A 修复 = **`574378424`（10:42:51，4 文件 +95/-36）**，commit body 自带修复前后 rect 计数对照表（scale 2.00 33→39、1.00 64→77、0.50 127→153、0.30/0.17 无差异因已被世界边界钳住）、两臂同口径改且仍逐顶点等价、三端口径防复发锁、Canvas 兜底零改动（`SoftwareCanvasBackendGridTest` 5/5 绿）⇒ **交付形态判定合格**，下方 a)/b)/c) 中仅"**§7.2/CHANGELOG 把该行为变更（视口底部补齐横线）与量化表登记在案**"仍是验收必查；下文按 10:41 快照描述，读时以本括注为准）：**② `-ffp-contract=off` 覆盖 native-renderer —— 已按红线独立提交 `fb465f0b1`（仅 `cpp/CMakeLists.txt` +15 行）**，验收核其仍为独立单笔即可。**① 网格线 Y 轴漏乘 `TOPDOWN_Y_SCALE` —— 交付形态已于 10:41 由选项②转为选项①"真修复"**：`5b3a19dd5` 入库时确为"逐位复刻现状"（守卫用例 `GridRowRangeReplicatesLegacyUncompressedFormula`），但**当前工作区**（4 文件 +62/-34）已把 `scene_draw.h::buildOverlayLayers` 的 `lastRow` 改为 `viewportH/(scaleSafe * kTopdownYScale)`、并同步给 **Kotlin 回退臂** `drawGridOverlay` 补 `SpriteAtlasDef.TOPDOWN_Y_SCALE`；守卫用例改名 `GridRowRangeFollowsProjectedViewportBand`，`SceneOverlayProtocolGuardTest` 新增"三端口径防复发锁"（同时锁 Vulkan 旧臂 / Canvas / C++ 三处公式）。截屏自陈 "12/12 pass with the before/after evidence table"。⇒ **验收必查（取代 10:34 轮的"选项②单列登记"判据）**：a) 该修复须为**独立 commit**（R3.3 已先行入库，故须核实它没有 amend/混入 `5b3a19dd5`）；b) **回退臂行为变更须显式登记**——B10 起"回退臂行为 = 开工前现状"的不变量被有意打破，§7.2/CHANGELOG 须写为**行为变更**（视口底部补齐横线、与 Canvas 对齐）并重述灰度回退语义；c) 改动面须仅限该公式，不得顺带其他行为。
4. B11 验收要点：C++ 批口径须先重建对拍桥再跑门 1（**`libgamecorejni.so` 仍是 08:38:58 的 B10 期产物，本批未重建**；基线预期 **1490**，以当批报告为准）；**R3.4 新增 JNI 端口 3 个**（`sceneSetSelection` / `sceneSetDemolishMarkers` / `sceneSetPreview`，渲染面 44→47）⇒ 验收核其报告与 §7.2 是否逐个登记豁免理由；**待交**：R3.4 脏更新协议独立 commit（须含"稳态相机静止帧零跨线"与 `frameDirty` 早退语义不退化）、文档三件套（§7.2 B11 行 / CHANGELOG 4.01.15 / `docs/cpp-engine.md` 进展行）、G4 的**每帧 JNI 次数**数字。**判据修正（勿再误判）**：前几轮以"`git diff` 被删 `drawRect` 行数 = 0"当作"258 drawRect 未替换"的证据是**错的**——`drawRect` 在 helper 内部，本批是**调用点重组**：`renderFrame` 按 `NativeEngineFlag.sceneStoreRender`（默认 true）分叉，新路径仅 `pushSceneUpdates` + overlayFlags 装配 + `drawFrame`，五段逐 rect 路径整体移入 `renderLegacyOverlayPath` 回退臂（红线要求"旧路径代码不得删除"），另有 `SceneOverlayProtocolGuardTest`（4 用例）含"新路径零逐 rect 调用点"静态门禁 ⇒ 验收按"结构 + 静态门禁"复核，不再要求 drawRect 删除负增量。
5. B11 通过后按台账批次总表滚动 B12–B17（B12=R3.5+R3.6 远景容量+GLES 同构；B13=R3.8 原生浮层 Tier1 + G3/G4 截图回归；B14=R4.4 RNG 分区；B15=R6.1 图集离线化；B16=R6.2 数值外置；B17=CI 与度量执法 + 收官核对），直至收官。
