# SR-3 完成报告（云主路径 + 槽位云化 + 初始化如实 · 产品可见面）

> 日期：2026-09-22
> 批次：SR-3（施工卡 = `docs/parallel-batches-w5/batch-SR3.md`；方案 =
> `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-3」+ §2 读路径/触发矩阵 + §5 门 5）
> 权威依据：SR-2 交付资产（SaveBackend/UploadQueue/SaveArbiter/三态开关，report-SR2
> §8 消费面提示）+ SR-0 侦察（slot_N 映射 §3.4 + 剧本 S1-S10 §4.2 + 6 项待真机 §3.3）。
> 性质：**产品可见面批**——云下载落盘、冷启动云槽位列表、初始化如实阻断、真冲突弹窗。
> 纪律声明：本会话只做 SR-3 一批；LEGACY 模式行为零变化红线（旧云读档/上传链零触碰，
> 新链模式门控隔离）；迁移链历史基线零触碰（本批无 wire/schema 变更）；方案文档保持
> untracked 未提交；**代码交付后批次停在 pending-device 状态等用户**（真机硬门 +
> aiSectDisciples 拍板），不开 SR-4；`accepted` 不自登（归用户）。

---

## 1. 交付链（8 笔提交，每子项独立）

| commit | 内容 |
|---|---|
| `3f9326bb2` | C1 施工卡立卡 + 台账 SR-3 行证据补记 + 监控日志开跑条目 |
| `8ca74f3f4` | C2 云下载落盘：云槽位链 download→迁移/校验→**落缓存**→账本收敛→boot（LEGACY 门控隔离）；list() 摘要富化（桥 ArchiveEntry 扩 extra/size + parseSummary）；测试 15 例 |
| `a02740a27` | C3 冷启动云槽位列表：SaveBackend.list→主菜单 slot_N 摘要卡（LEGACY 短路）+ EXTRA_CLOUD_SLOT 分发 + visibleCloudSlots 守卫测试 |
| `b547fe31c` | C4 初始化 3 次全败静默放行修正（审计 §12-J）：阻断态 + 如实错误屏（重试）+ 模式感知文案；测试 4 例 |
| `973998f05` | C5 真冲突弹窗：双源置态（conflicts 流 + ConflictHeld）+ resolveCloudConflict 二选一收口 + 模态弹窗（选谁留档明确可见）；测试 +6 例 |
| `36169a76a` | C7a 组合门 detekt 修复（风格级零逻辑变化，SR-1/SR-2 同口径） |
| 收官笔 | C8 本报告 + 台账 SR-3 行 delivered（标注 pending-device 清单） |

## 2. 交付内容与生产落点

### 2.1 云下载落盘（审计 §3/§12-I 修复面，方案 §2 读路径）

新链 `SaveLoadViewModelCloudSlotOps.kt`（CLOUD_TRANSITION+ 生效）：

```
loadCloudSlot(slot)  ──守卫族对齐 slot 0 云下载入口（boot/重启/保存/云锁/加载互斥）
  └─ performCloudSlotLoad(slot)
       ├─ 模式门控：LEGACY ⇒ 拒绝（硬红线机制面：非仅靠 UI 不入口）
       ├─ SaveBackend.download(slot)（SR-2 仲裁内建）
       │    ├─ CONFLICT ⇒ 不落盘不报错，冲突弹窗接管（ConflictPending）
       │    └─ 其余失败 ⇒ 操作态 Error 如实反馈
       └─ handleCloudSlotPayload
            ├─ verdict 分流：UPLOAD_PENDING ⇒ 拒绝覆盖（本机有未上传新进度，
            │   下载会丢进度——如实提示等队列补传，区分于真冲突，SR-0 S5 语义）；
            │   LOCAL_BEHIND / IN_SYNC ⇒ 继续
            ├─ 云档管线：版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建
            ├─ **落缓存**：storageFacade.save(slot, processed)——审计"只进内存"
            │   缺陷根治；失败 = 如实报错中止，不带病 boot
            ├─ 账本基线收敛：W 已知 ⇒ adoptCloudState(slot, W)（下载即采纳云端，
            │   非新保存——不走 recordLocalSave 不制造假脏标志）；W 未知（存量档
            │   U11）保持原状
            └─ 既有 boot 链：applyCloudSaveToEngine(processed, slot,
                pendingSlot=slot)（pendingSlot 参数化，默认 0 = 既有云会话调用
                行为零变化）
```

**LEGACY 红线落实**：既有 slot 0 云会话读档路径（`SaveLoadViewModelCloudLoadOps` /
`SaveLoadViewModelCloudOps` 两 handler）逐行零触碰；审计"不落盘"缺陷的修复按方案 §2
读路径标注（"CLOUD_TRANSITION 起"）落在新增模式门控链——LEGACY 默认模式下旧路径
行为与 SR-3 落库前一致（守卫测试：CloudSlotLoadTest LEGACY 拒绝例 + 组合门全量回归）。
管线序列与 LEGACY handler 有意重复 ~15 行（红线隔离的代价，如实登记，不夹带重构）。

### 2.2 冷启动云槽位列表（slot_N 映射 + 摘要渲染）

- **list() 富化**（`TapTapSaveBackend`）：SR-2 遗留的 summary=null/saveId=null 落地面
  补齐——反射桥 `ArchiveEntry` 扩 `extra`/`sizeBytes` 字段（SDK `ArchiveData` 十字段
  中本就携带，SR-0 §3.1 AAR 核验），一次 `getArchiveList` 往返即提取全部槽位的
  摘要与保存序号 W，避免 O(N) 次逐档 `queryArchiveInfo`（每次都是全列表往返）；
  `parseSummary(extra)` = internal 纯函数（extra JSON 现役协议直映射；缺失/解析失败/
  全空 = null——"有档无摘要"退化为占位文案，对齐 CloudSaveInfo
  .hasMeaningfulSummary 同纪律，非错误）。
- **主菜单**（`MainActivity` + `SaveSelectScreen`）：`queryCloudSlotEntries()`
  LEGACY 短路（零查询零 UI，硬红线）→ 非 LEGACY 经 `SaveBackend.list()` 取云端槽位
  存档；LOAD_SAVE 模式在本地槽位卡后追加云槽位卡（宗门/年月/弟子/灵石/云端保存时间，
  "点击下载"入口）；NEW_GAME 模式隐藏（新游戏=建本地档）。**本地槽位 UI 并存不动**
  ——CLOUD_TRANSITION 下本地照常可用，CLOUD_ONLY 后才移除（归 SR-7 收口批）。
  查询失败降级空列表（主菜单仍可用，日志如实留痕）。
- **分发链**：云槽位卡 → `launchGame(cloudSlot = N)` → 新 intent extra
  `EXTRA_CLOUD_SLOT` → `GameActivity` 分发 `saveLoadViewModel.loadCloudSlot(N)`；
  cloudSlot 不随 savedInstanceState 持久化——进程回收重建时缓存已落盘，自动走常规
  槽位加载（2.1 落盘的附带韧性收益）。

### 2.3 初始化 3 次全败静默放行修正（审计 §12-J）

- 旧行为（`MainActivity.proceedAfterPrivacyConsent`）：3 次重试全败后仅
  `Log.e("proceeding with empty cache")` + `isLoadComplete = true`——进度动画推满
  静默进主菜单；存档不可用的会话里保存静默失败/读档全空，玩家进度被静默丢弃。
- 新行为：初始化循环提取 `initializeStorageWithRetry()`；全败置 `storageInitError`
  阻断态——**进度动画停摆、onLoadingComplete 不触发**，加载页切换
  `StorageInitErrorScreen`（上次真实错误详情 + 影响说明 + 「重试」按钮；
  `retryStorageInitialization()` 清态重跑初始化循环）。
- **模式感知文案**（`storageInitFailureMessage` 纯函数，单测锚定）：LEGACY =
  "本地存档是唯一的进度来源……避免进度静默丢失"；CLOUD_TRANSITION = "本地存档缓存
  不可写：本机保存、云上传与云下载都无法完成"；CLOUD_ONLY = "本地缓存不可用且云端
  存档服务不可达"。
- 性质声明：§12-J 修复本身即**显式行为修正**（方案 §1.3 收编授权），非 LEGACY 红线
  违反（SR-2 重开预存修正同口径先例）；采用派发指令两方案中的"如实阻断 + 重试 UI"。

### 2.4 真冲突弹窗（本地脏 × 云端有更新，玩家二选一）

- **双源置态**（`SaveLoadViewModel.pendingCloudConflict`）：
  下载侧 = `SaveBackend.conflicts` 流（SR-2 `arbitrateAgainstCloud` 仲裁 CONFLICT 发
  事件 + 返回 CONFLICT Failure）；上传侧 = UploadQueue `ConflictHeld` 事件（SR-2
  VM 收集器 `else -> {}` 占位注释"弹窗归 SR-3"，本批接通）。
- **收口**（`resolveCloudConflict(keepLocal)` 公开扩展，幂等清态先行）：
  - 下载侧 keepLocal=true ⇒ 仅清待决态（本机/云端两侧均不动）；
  - 下载侧 keepLocal=false ⇒ `adoptCloudState(slot, W)` 基线收敛（L=C=W，IN2 序号
    语义）后重跑下载——仲裁转 IN_SYNC 通过（"玩家选云"的显式授权路径，非静默覆盖）；
  - 上传侧 ⇒ `UploadQueue.resolveConflict(slot, keepLocal)`（keepLocal 授权越过冲突
    闸上传本机档 / keepCloud 丢弃待传并基线收敛，SR-2 Q10 既有实现）。
- **弹窗**（`CloudConflictDialog`，GameContent 渲染，生命周期门控与既有错误弹窗同
  窗口纪律）：正文列出双方保存序号（本机第 L 次保存未上传 / 云端第 W 次保存），
  两个按钮各写明"留谁/丢什么"（下载侧与上传侧后果文案分别成文）；
  **模态**（禁点外关闭/禁返回键关闭）——二选一是必须的显式决策，误触关闭不得暗自
  替玩家弃选。
- 单测锚定：双源置态各 1 例 + keepCloud（adoptCloudState + 重下载 exactly=2）+
  keepLocal（零覆盖动作）+ 上传侧队列委托 + 无待决无副作用。

### 2.5 aiSectDisciples 拍板项处置（派发口径第 5 条）

**维持现役语义实施 = 零代码变化**：新链沿用同一 SaveData 管线，`aiSectDisciples` 等
@Transient 字段照旧不进云档 payload，换设备由 `ensureGameDataIntegrity` 按初始态
重生成（SR-0 §1.2 勘察结论的现役行为）。本批未嵌入任何新决策、未实现 heavy 并入
云档分支；**拍板项维持 open 等用户**（SR-0 §6.3 原样移交：接受重生成（现状）vs
heavy 域并入云档（体积可行 ~0.5MB 现实态 / 满编逼近 10MB 需配套裁剪））。实施中
未发现必须二选一的设计分叉（新链与旧链在该语义上完全同构），无需卡内停批。

## 3. 门禁自检实录（结果如实）

### 3.1 六模块组合门

- 命令照派发口径：`testReleaseUnitTest --max-workers=1 --rerun-tasks
  -Dgamecore.jni.path=… detekt compileReleaseKotlin lintRelease`；
- **第一轮**：`GATE_EXIT=1`——`:app:detekt` 2 weighted issues（全部为本批新代码
  风格级：`SaveSelectScreen.kt` 文件函数数 16>15（TooManyFunctions）+
  `CloudSlotEntryCard` LongMethod 78>60），`36169a76a` 独立 commit 修复
  （CloudSlotEntryCard 连同 visibleCloudSlots 拆出独立文件 + 容器拆 Icon/Text 两个
  子 Composable，纯搬移零逻辑变化，SR-1/SR-2 同口径）；**测试面第一轮已全绿**：
  六模块 7964/0 失败/2 skip（明细见下表）；
- **第二轮**：`:feature:game:detekt` 3 weighted issues（`parseSummary` 复杂条件
  ComplexCondition 5>4 + `loadCloudSlot` launch 体内 generic catch 未豁免 +
  `CloudSlotLoadOutcome` MatchingDeclarationName——声明级 Suppress 对该规则无效须
  文件级），`693ccdb31` 独立 commit 修复（复杂条件提取命名布尔 + 补豁免注记，
  零逻辑变化；detekt 三模块复核 0 issues）。**测试面第二轮同样全绿**：7964/0/17
  （skip 17 = 基线 17 完全一致零新增：core:data StorageSystemBenchmark 15 +
  app DiscipleTablesIntegrationTest 2）；
- **第三轮**：detekt/compile 全过；`:core:engine:testReleaseUnitTest` 1 例失败——
  `GameEngineCoreLifecycleInterleavingTest > stop during emergency restart…`
  （时序断言"emergency 必须进入 snapshot 阻塞点"5s 窗未达）。**已知抖动测试甄别**
  （B03/B20 先例）：本批 engine 零触碰（git 实证）+ 该类单跑 **12/12 全绿**
  （--rerun-tasks 逐轮，failures=0 ×12）⇒ 判定时序抖动与本批无关；
- **第四轮（最终判绿轮）✓**：`BUILD SUCCESSFUL in 26m 3s`，`GATE_EXIT=0`，
  339 tasks 全 executed；六模块 **7964 / 0 失败 / 0 错误 / 17 skip**，689 个
  TEST-*.xml 时间戳全部落在 08:25–08:31（本轮门窗口内）——判绿口径（XML executed
  计数 + 时间戳）满足。

| 模块 | tests | failures | errors | skipped |
|---|---|---|---|---|
| app | 998 | 0 | 0 | 2 |
| core/domain + core/data + core/engine + core/ui | 6049 | 0 | 0 | 15 |
| feature/game | 917 | 0 | 0 | 0 |
| **TOTAL** | **7964** | **0** | **0** | **17** |

  （skip 17 与基线完全一致零新增：core:data StorageSystemBenchmark 15 +
  app DiscipleTablesIntegrationTest 2。第一轮曾观测 core:data 15 例实跑通过
  （skip=2），第四轮回归基线 17——benchmark 类条件跳过行为随环境抖动，两轮
  均零新增 skip 合规。总数 7942 → 7964 的 **+22 = 本批新测试 22 例**（见 §3.4），
  failures 分布与基线同构（0）。）

### 3.2 Diff* 对拍（IN8）

**50 类 / 273 例 / 0 failures / 0 skip**（XML 实测）——与基线完全一致，差分管线信封
语义零破坏（D7）。

### 3.3 桌面 ctest

- 本批**零 C++/JNI 面**（git 实证：`3c7cf74fd..973998f05` 对
  `android/app/src/main/cpp/` 与 `android/core/engine` 零提交）⇒ 桥重建可免
  （SR-2 §3.3 同预案）；
- **ctest 照跑：`100% tests passed, 0 tests failed out of 1561`（64.31s）**，
  与基线 1561 完全一致；
- 运维注记（SOP 补充）：ctest.exe 本体在 Android SDK cmake
  （`%LOCALAPPDATA%/Android/Sdk/cmake/3.22.1/bin`），llvm-mingw bin 提供 bench
  运行时 DLL——两者同入 PATH 运行（SR-2 §5.4 只记了后者）。

### 3.4 本批新增测试清单（22 例）

| 测试类 | 例数 | 锚定 |
|---|---|---|
| `SaveLoadViewModelCloudSlotLoadTest`（feature，新） | 13 | 落盘链（落缓存+boot+账本收敛/W 未知不动账本）+ UPLOAD_PENDING 拒绝覆盖 + CONFLICT 短路 + **LEGACY 门控拒绝** + 缓存失败中止 boot + 迁移拒绝 + 冲突双源置态 ×2 + keepCloud（收敛+重载）+ keepLocal（零覆盖）+ 上传侧队列委托 + 无待决无副作用 |
| `TapTapSaveBackendTest` 增（feature） | 2 | parseSummary 协议直映射 + 缺失/空/全空降级 null |
| `SaveSelectCloudSlotsTest`（app，新） | 3 | 云槽位卡可见性（LOAD_SAVE 透传 / NEW_GAME 隐藏 / 空列表） |
| `StorageInitMessagingTest`（app，新） | 4 | 模式感知文案三分支 + 错误详情携带 + "已重试 3 次"如实 |
| `SaveLoadViewModelLoadTest` 补桩（feature） | 0 | 既有 38 例回归全绿（saveBackend 收集桩补齐，零语义变化） |

### 3.5 定向门（各 commit 内实跑）

| 轮次 | 范围 | 结果 |
|---|---|---|
| C2 后 | CloudSlotLoadTest + TapTapSaveBackendTest + LoadTest | 7/7 + 8/8 + 38/38（XML 0 失败） |
| C3 后 | SaveSelectCloudSlotsTest + SaveSlotDispatchTest + app 编译 | 3/3 + 5/5 |
| C4 后 | StorageInitMessagingTest + app 编译 | 4/4 |
| C5 后 | CloudSlotLoadTest(13) + LoadTest + app 编译 | 13/13 + 38/38 |

## 4. 设计决策与拍板点（验收轮关注点）

1. **"不落盘"缺陷修复落在新增模式门控链，LEGACY slot 0 路径保持只进内存**：方案 §2
   读路径自标注"CLOUD_TRANSITION 起"，且 LEGACY 硬红线要求既有云读档行为逐行保持；
   slot 0 云会话（存量单档 `mnzm_cloud_save`）的落盘化归 SR-6 迁移矩阵（本地有×云有
   等剧本）一并处理，本批不越界。审计 §3/§12-I 的"不落盘"在 CLOUD_TRANSITION+ 链路
   已根治。
2. **UPLOAD_PENDING 拒绝下载覆盖**：verdict=UPLOAD_PENDING（本机有未上传新进度、
   云端不更新）时下载会静默丢本机进度——本批如实拒绝并提示等队列补传，而非当作
   "可安全下载"。这是方案 §2 读路径"本地脏 ⇒ 真冲突弹窗"的细化：脏 × 云不新 =
   非冲突但同样禁止静默覆盖（SR-0 S5 语义：UPLOAD_PENDING 由队列自愈，非玩家动作）。
3. **冲突"选云" = adoptCloudState 基线收敛 + 重跑下载**：不做"下载绕过仲裁"后门
   （后门会同时放行真冲突静默覆盖）；收敛后仲裁自然转 IN_SYNC，玩家授权有账本语义
   可审计（IN2 序号口径）。
4. **冲突弹窗模态化**：禁点外关闭/返回键关闭——两选项都有破坏性后果（覆盖云端或
   丢弃本机未传进度），误触关闭不应暗自替玩家弃选。代价 = 弹窗只能二选一（不提供
   "稍后再说"）；上传侧条目保持队列挂起（原状），下载侧可重进入口重触发。
5. **初始化阻断采用"如实阻断 + 重试 UI"**（派发两方案择一）：全败后任何形式的
   "降级进入"在缓存不可写下都会以保存失败收场（storageFacade.save 的
   ensureInitialized 会拒绝写入），降级进入不成立；文案仍模式感知（LEGACY 点明
   本地唯一真相 / TRANSITION 点明缓存不可写影响 / CLOUD_ONLY 预置文案）。
6. **list() 富化走桥 ArchiveEntry 扩字段**而非逐档 queryArchiveInfo：后者每档一次
   全列表往返（O(N²) 网络放大），真机限频（1/min 共享冷却虽只约束创建/更新，查询
   频繁仍触发列表最终一致性窗口放大）。桥扩字段 = SDK 十字段本就携带（SR-0 §3.1
   javap 核验），零新增 SDK 面。

## 5. 如实登记（坑与局限）

1. **组合门两轮 detekt 失败**（三连批纪录）：第一轮 `:app:detekt` 2 issues
   （`SaveSelectScreen.kt` 文件函数数 16>15 + `CloudSlotEntryCard` LongMethod
   78>60）→ `36169a76a`；第二轮 `:feature:game:detekt` 3 issues（`parseSummary`
   ComplexCondition 5>4、`loadCloudSlot` launch 体内 generic catch、
   MatchingDeclarationName 文件名规则——**声明级 @Suppress 对其无效，须
   `@file:Suppress`**，基建发现）→ `693ccdb31`。全部风格级零逻辑变化。
   派发预警连续三批应验；本轮新教训 = detekt 复杂条件同样覆盖测试外的主源
   命名布尔提取是最小修复面。

1. **管线序列 ~15 行有意重复**（新链 processDownloadedCloudSave vs LEGACY
   handleCloudLoadSuccess/handleCloudDownloadSuccess）：红线隔离（LEGACY 路径零触碰）
   优先于 DRY；若后续批收敛，须以行为等价测试兜底单独走笔。
2. **云槽位卡无删除入口**：SaveBackend.delete 已备（SR-2），但云删档 UI 属产品决策
   （误删云端唯一档不可恢复——云唯一后 .bak 轮转保护不存在），本批不夹带，待产品
   拍板后补 UI。
3. **列表最终一致性窗口**（SR-0 §3.3 待真机 2）：上传后立刻打开选档页，云槽位卡
   可能显示旧摘要或暂缺新档（parseSummary 降级 null 的占位文案即为此窗口设计）；
   真机实测后决定是否需要刷新交互。
4. **冲突弹窗的"保存序号"对玩家是生涩概念**：文案以"第 N 次保存"呈现（序号语义
   如实），真机验收时留意可读性反馈。

## 6. 红线合规声明

- **LEGACY 硬红线** ✓：旧云读档/上传链零触碰（git diff 实证仅新增文件 + 挂点文件
  最小接线）；loadCloudSlot/queryCloudSlotEntries 模式门控短路；组合门全量回归兜底；
  唯一全模式行为变化 = §12-J 初始化阻断（审计收编授权，如实登记 §4.5）。
- **IN2 仲裁无时钟** ✓：新链零 mtime/挂钟比较；verdict 唯一来源 = SaveArbiter；
  "选云"收敛走序号语义 adoptCloudState。
- **IN1 原子性** ✓：落缓存失败中止 boot；账本收敛在落缓存成功后；上传收口沿用队列
  语义（失败只降级）。
- **IN3 接口隔离** ✓：新链零 SDK 类型（MainActivity/app 只依赖 SaveBackend 接口与
  core:data 类型）；既有 konsist/classpath 双守卫照跑。
- **IN7 boot 只读不写** ✓：boot 链未触碰；落缓存发生在 boot 前。
- **IN8 Diff\* 0 skip** ✓：50 类 / 273 例 / 0 failures / 0 skip（第四轮 XML 实测，
  与基线完全一致）。
- **真冲突禁止静默覆盖** ✓：CONFLICT 一律弹窗；UPLOAD_PENDING 拒绝覆盖；
  两选项后果文案明确。
- **迁移链** ✓：零 wire/schema 变更（本批无 proto/Room 触碰），迁移器零条目。
- **台账纪律** ✓：只动 SR-3 行与监控日志；方案文档保持 untracked；`accepted` 未自登。
- **CHANGELOG**：SR-0/1/2 先例均未追加（中间工程批口径），沿用；版本条目归用户
  发布轮决定。

## 7. 真机硬门 pending-device 清单（方案 §5.5，逐项如实登记，不虚报）

**以下全部未测**——本环境无真机/双设备/TapTap 沙盒，Robolectric 不可替代（方案 §5.5
硬门）。代码交付后批次停在 pending-device 状态等用户安排真机。

| # | 待真机项 | 依据 | 期望证据 |
|---|---|---|---|
| 1 | 双设备剧本 S1-S10 全套实跑（净循环/净净交替/双端同脏/A脏×云新/A脏×云旧/确认丢失幂等/下载中断/冷启动首接/双无/月变冲突联动） | SR-0 §4.2 矩阵 | 步骤录像/日志摘录；每剧本 verdict 与用户可见面对照 §4.2 期望列 |
| 2 | 冷启动云槽位下载→落盘→boot 全链（S8 主链路） | 本批 §2.1 | 双设备交替：B 上传 slot_N → A 选档下载 → 重启后本地槽位可常规加载（落盘实证） |
| 3 | 真冲突弹窗端到端（S3/S4）：弹窗出现、二选一各自后果、选云后重载、选本机后不覆盖 | 本批 §2.4 | 录像 + 日志（arbitrate CONFLICT → 弹窗 → 收口链路） |
| 4 | 云槽位列表摘要渲染 + SR-0 §3.3 六项（多档服务端放行/列表最终一致性/限频粒度/命名校验 400009/回调可靠性/大档传输） | SR-0 §3.3 | 摘要与上传端 gameData 对照；上传后立即列表的陈旧窗口实测 |
| 5 | 初始化失败阻断屏（清 App 数据+制造 DB 损坏 / 只读存储场景）→ 错误屏 → 重试恢复 | 本批 §2.3（§12-J） | 录像：3 败后错误屏出现、主菜单不放行、重试成功后正常进入 |
| 6 | LEGACY 回归抽测：默认模式下主菜单/游戏内云存档行为与 SR-2 版一致（云槽位卡不显示、slot 0 云会话只进内存） | 硬红线 | 抽测录像 + 与 SR-2 行为对照 |
| 7 | UploadQueue 冷却参数真机校准（SR-2 遗留 §7.4）：1/min 保守口径 vs 60/min | SR-2 §7.4 | 400001 实测触发与放宽结论 |
| 8 | aiSectDisciples 换设备重生成实测确认（现役语义）+ 拍板 | SR-0 §6.3（open） | 换设备日志 + 用户拍板结论 |

## 8. 待用户拍板 / 移交后续批

| # | 事项 | 归属 |
|---|---|---|
| 1 | aiSectDisciples @Transient 拍板（维持重生成现状 vs heavy 并入云档，SR-0 数据在案） | **用户（本批前置悬置项，维持 open）** |
| 2 | SR-2 §7.1 compareVersions 兼容闸拍板复核（CLOUD_TRANSITION 新链已实际绕开其进度语义——仲裁唯一入口 = SaveArbiter；物理删除与否不再阻塞） | 用户复核 |
| 3 | SR-2 §7.2 TapTapLeaderboardApi 既有 SDK 直引白名单 | 用户/后续批 |
| 4 | 云档删除 UI 是否提供（误删云唯一档无备份保护） | 产品拍板（本批未夹带） |
| 5 | 真机硬门 8 项安排（§7 清单）——**批次停在此等待** | 用户 |
| 6 | SaveBackendMode 升档时机（CLOUD_TRANSITION 逐设备推进的开关写入面——SR-2 `SaveBackendModeProvider.set` 就绪，生产零调用，待真机验证后由用户/看护拍板推入） | 用户/看护 |

## 9. SR-4 派发前置提示（给看护/用户）

本批已消费 SR-2 全部消费面提示（download verdict/conflicts 流/list/currentCloudSaveId/
resolveConflict 全部接线）。SR-4（月变自动存档 + onStop 收口）挂点依旧
（`GameEngineCoreAuthoritativeOps` 月变钩子 + `870be9771` 旗标），与 SR-3 无耦合；
依赖链上 SR-4 不阻塞于真机门（其自身真机项=后台杀场景，届时一并安排）。**但按停批
纪律：SR-3 代码交付后停在当前批，不自动开 SR-4**——待用户真机安排与拍板指示。
