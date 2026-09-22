# SR-6 完成报告——存量迁移引导（首启检测矩阵 + 完成度记账 + CLOUD_TRANSITION 收口）

> 日期：2026-09-22　批次：SR-6（方案 §4 SR-6；用户口头「新开分支完成第六批」直接驱动）
> 施工卡：[batch-SR6.md](batch-SR6.md)　权威依据：
> [docs/save-system-refactor-plan-2026-09-21.md](../save-system-refactor-plan-2026-09-21.md)
> §0 D1/D2/D3、§2 模式开关与读路径、§3 IN1/IN2/IN3/IN6/IN7/IN8、§4 SR-6、§5 门 1/2/3/6
> 分支：**`w5/sr6-cloud-migration`**（SR 系列首个非 main 施工批，自 SR-5 收官笔 `45e025bbb` 开启）
> 状态：**delivered（`accepted` 归用户）**；真机项见 §7 pending-device，本批未声称达标。

---

## 0. 一句话结论

方案 §4 SR-6 的三件事（首启检测矩阵 / MMKV 迁移完成度记账 / 未完成迁移不推 CLOUD_ONLY）
全部落地，并额外补掉三处有实证的既有缺口（F1 待传续传无调用者、F2 迁移上传缺邮件快照、
F12 仲裁 U11 在迁移语境下会静默覆盖他端档）；`SaveBackendModeProvider.set` 有了 SR-2 就绪后的
**第一个生产调用者**，形态是**玩家确认式**升 `CLOUD_TRANSITION`（施工卡 §7 S1），
`CLOUD_ONLY` 仍生产零写入且有静态守卫钉住。**本批第一次把"云上传"这件事真正交到玩家手里**，
所以 §7 真机门不是形式项。

规模：**21 文件 / +2,703 / −129**（android 面，纯本批 10 笔），
**零 C++、零 wire/proto、零 Room schema、零迁移链触及**；新增用例 **69 例**
（矩阵 22 + 协调器 20 + 落盘段 9 + 卡面 7 + 记账 6 + 守卫 4 + 队列节奏 1）。

---

## 1. 交付链（10 笔，逐子项独立 commit）

> **哈希口径**：本批十笔经 `git rebase --onto main 45e025bbb` 重落为"`main` + SR-6"的独立单元
> （用户 2026-09-22 指示"做"），下表取 **rebase 后（当前分支）哈希**，括注 rebase 前原哈希备查
> （原哈希仍在 `sr5/tail-on-w5` 与 reflog 上可达）。

| # | commit（rebase 前） | 内容 |
|---|---|---|
| C1 | `81ebe2739`（`e5c6f9391`） | 施工卡立卡（勘察 F1-F15 + 三条自定口径 S1-S3）+ 台账 SR-6 行 in_progress |
| C2 | `355d450ab`（`ccbd262c2`） | `SaveMigrationLedger`（MMKV per-slot 上云确认标记，四态 + 失败封闭 + 删槽清理）6 例 |
| C3 | `d52de93a3`（`3367d533a`） | `SaveMigrationPlanner` 六步短路矩阵 + `canPromoteToCloudOnly` 门槛 22 例（**方案门**） |
| C4 | `1613f6bdd`（`21dfa05ca`） | 云档→本地缓存落盘段抽为 `CloudSaveCacheWriter`（可跨槽、不 boot）+ 9 例；SR-3 的 13 例改持真实组件跑等价断言 |
| C5+C6 | `45a07e2b8`（`544fde0e8`） | `SaveMigrationCoordinator`：两阶段（本地扫描 / 玩家显式开始）+ 待传续传 + 矩阵派发 + 二选一 + 存量单档落地 + 升档确认 |
| C7 | `f7f6b2fbb`（`50bf54f1b`） | 上传节奏实测：新 Q13 用例 + `Config.debounceMs` 参数保持原值（SR-4 §8 建议经推导回绝） |
| C8 | `9a52debd4`（`63784ec1f`） | 主菜单迁移卡 + `MainActivity` 接线（四动作位）+ 卡面守卫 7 例 |
| C9 | `ce63dde26`（`dd6a87071`） | 完成率指标 `#save_migration_result`（埋点字典四处同步）+ 上报点 |
| C10 | `9bdd108c0`（`7c31f9861`） | `SaveMigrationGuardTest`：模式总闸唯一写入点 + `CLOUD_ONLY` 零写入 + 迁移判定族零时钟 4 例 |
| C6 自纠 | `38a987537`（`775217a58`） | **实施期自查缺陷修复**：队列挂起侧的"改用云端"未把云端内容落回本机缓存（协调器 18→20 例） |

---

## 2. 三条自定口径与两处卡面偏差（登记待用户改判）

| # | 议题 | 实施口径 |
|---|---|---|
| S1 | 模式升档是否真写 | 写。全员收口后迁移卡出现「启用云存档」，**玩家点下才** `set(CLOUD_TRANSITION)`；不静默升档、也不无限悬置（方案 §2"SR-3/SR-6 逐级切换"字面归本批收口，SR-3 §8.6 又把时机移交用户 ⇒ "玩家显式确认"是唯一同时满足两者的形态） |
| S2 | 存量单档 `mnzm_cloud_save` | 作"云有档"进矩阵，玩家点定落到哪个**空槽**（跨槽落盘、不 boot、不抄序号）；旧云会话 UI 并存，废除仍归 SR-7 |
| S3 | 引导形态 | 主菜单**常驻迁移卡**（滚动列表之外），非弹窗 |

偏差（卡 §2.6 已同步）：① **C5 与 C6 合并一笔**——续传的唯一调用者就在协调器 `scan()` 内，
拆开会先提交一个无人调用的函数（违 IN6），合并后每笔仍可独立回滚；
② **删掉 `SKIPPED` 态与 `noticeSeen`/`promotionConfirmed` 两个一次性标记**——不标记即保持
`NONE`，下次冷启动照样列出，"随手点掉暂不"不该永久熄灭引导；而常驻卡不需要弹窗式抑制位。

---

## 3. 勘察发现的处置（本批真正修掉的三处既有缺口 + 一处收敛）

1. **F1 `UploadLedger.pendingSaveId` 生产零消费者**：SR-2 §2.4 与 Q6 单测声称的
   "重启后以账本待传指针同 id 重入队"配方**没有任何调用者** ⇒ 进程在"入队后、确认前"被杀，
   该槽脏标志永久挂着（既是 IN6 违例也是真耐久性缺口）。本批 `scan()` 成为那个缺失的调用者，
   且**只在非 LEGACY 下自动执行**（`shouldEnqueueCloudUpload` 门控）——默认模式零自动云动作，
   LEGACY 下这类槽改由阶段 B 的玩家显式动作收口。
2. **F2 迁移上传缺邮件快照**：`StorageFacade.load` 不填 `SaveData.mails`（由保存编排从 `mails`
   表注入），而云恢复侧是"整对象替换回表" ⇒ 直接 `load→upload` 会产出空邮件云档，
   换设备即丢邮件——正是 SR-1 收编要防的事故。`loadForUpload` 与保存编排同源补
   `getMailsForSlot`，且**读邮件失败即中止该槽上传**（宁可失败也不上传缺邮件的档）。
3. **F12 `SaveArbiter` U11 在迁移语境下会放行静默覆盖**：W 未知按 `W==C` 保守重算 ⇒
   `L>C && W(null)<=C` ⇒ `UPLOAD_PENDING` ⇒ 队列直接覆盖上传。日常链路里 W 未知只可能来自
   slot 0 存量单档，但迁移是"第一次把本机档推上 `slot_N`"的通道 ⇒ 一旦云端存在无 `saveId` 的
   历史档就静默覆盖另一台设备的进度（方案 §6 风险"双设备真冲突静默丢档 低/极高"）。
   ⇒ planner 层前置拦截（云档无 `saveId` 或本机 `C==0` 一律 `ResolveConflict`），
   `SaveArbiter` 本体零改动（U11 在它自己的调用面仍是对的）。
4. **SR-3 §5.2 登记的重复被收敛**：抽出 `CloudSaveCacheWriter`，兜底 = 既有 13 例改持**真实组件**
   （吃同一批 mock，不 stub 空转）跑原断言。跨槽迁移不抄源槽序号（`UploadLedger` 序号按槽独立）。

### 3.1 实施期自查缺陷（C6 自纠 `775217a58`）

`resolveConflict(keepLocal=false)` 在"队列已挂起该槽"分支只调了
`UploadQueue.resolveConflict`——它按 SR-2 Q10 语义仅"丢弃待传 + 基线收敛"，
**本机 Room 缓存仍是分歧的那一份**：玩家按了"改用云端进度"，下次进该槽玩的还是本机旧进度，
且账本已收敛 ⇒ 分歧档再也不会被上传，等于**静默保留本机**（与本批红线相反的方向）。
修法是两条"改用云端"路径统一走 `migrateCloudOverLocal`（下载→落缓存→账本收敛，不 boot），
成功才记 `CLOUD_PREFERRED`，失败留在待裁决。+2 例锚定挂起侧的两个选项。

---

## 4. LEGACY / 既有行为零变化核对（硬红线）

| 面 | 结论 | 证据 |
|---|---|---|
| 冷启动网络 | LEGACY 扫描**零云请求**（纯 Room + MMKV） | `LEGACY 扫描 - 零云请求零入队，只列清单` |
| 自动云动作 | LEGACY 不 enqueue、不 requestDrain、不自动续传 | 上述 + `LEGACY 扫描 - 即使有待传指针也不自动入队` |
| 既有 slot 0 云会话链 | 逐行零触碰（`SaveLoadViewModelCloudLoadOps`/`CloudOps` 不在 diff 内） | `git diff --name-only` |
| 游戏内 `loadCloudSlot` | LEGACY 仍拒绝；模式闸保留在 VM 入口，**未**下沉进共用落盘段 | SR-3 `legacy mode rejects...` 例保持绿 |
| 手动/自动保存链 | 零改动 | `SaveLoadViewModelLoadTest` 38 例 + `AutoSaveTest` 10 例零改绿 |
| 上传节奏参数 | 一字未改（`debounceMs=2s`/冷却 60s） | §4 条 C7 + `UploadQueueTest` Q13 |
| 存档/协议/wire/schema | 零触及 | diff 无 .proto/.cpp/.h/schema/迁移文件；`Diff*` 0 skip |
| 全量回归 | 六模块组合门判绿轮 | §6 |

**唯一新增玩家可见面 = 本批主题**（迁移卡），其网络动作全部由玩家显式点击触发；
唯一模式写入 = 玩家确认后的 `CLOUD_TRANSITION`。

---

## 5. 完成率指标定义（方案 §4 SR-6「运营侧可查」）

事件 `#save_migration_result`：一次引导跑到终态**边沿触发一条**（同阶段不重复；
玩家再点「开始迁移」复位并产生新记录）。

| 属性 | 含义 |
|---|---|
| `migrated_total` | 已收口槽数 = 上云确认到账 ∪ 玩家裁决"以云端为准" |
| `pending_total` | 仍需动作槽数 = 待上云 ∪ 上云中 ∪ 待裁决 ∪ 上云失败 |
| `conflict_total` | 仍待二选一槽数（双端各有新进度 或 云态不可证 F12） |
| `blocked_total` | 本机损坏被跳过槽数（既未上云也未覆盖） |
| `mode_after` | 上报时 `SaveBackendMode` 名（区分是否已升档） |

**设备级完成率 = `migrated_total / (migrated_total + pending_total)`**；分母只计"本机确实有档"
的槽（云端独有槽走 `cloudOnlyTotal` 观测，不混进完成率）。
注册面按 `rules/data-analytics.md` 1.2 四处：常量 ✓ / 守卫测试三处集合 ✓ / 知识库字典行 ✓ /
**TapDB 后台「事件管理」✗（本环境无凭据，见 §8 条 1）**。

---

## 6. 门禁实测（判绿看 XML executed 计数 + 时间戳，不看 BUILD SUCCESSFUL）

命令（六模块组合门，照方案 §5.1 口径）：
`./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=…/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`

桥 `.so` mtime 2026-09-21 21:40：本批零 C++/JNI 面 ⇒ 复用，豁免口径同 SR-1..SR-5。

| 轮 | 时间 | 结果 | 说明 |
|---|---|---|---|
| 第一轮 | 14:47→15:11 | **GATE_EXIT=0 / BUILD SUCCESSFUL 24m21s / 339 任务全 executed** | 六模块合计 **8,081 用例 / 0 失败 / 0 错误**（= SR-5 基线 8,014 + 本批 67）。**但不含 C6 自纠笔** ⇒ 不作本批判绿凭据 |
| 第二轮 | 15:43→15:50 | `BUILD FAILED 6m42s` | `:app` `CrashHandlerBacklogTest` 1 例红（1005 例中）。当时按"单类连跑 3×4/4 绿 + 首轮同树绿"判为跨类抖动——**该归因已被第五轮推翻**（独立树重现），根因见第五轮行 |
| 第三轮 | 17:45→17:46 | `BUILD FAILED 1m39s` | **非判绿轮**：`:core:domain:bundleLibRuntimeToJarRelease` 抛 `FileSystemException: classes.jar 另一个程序正在使用此文件`——SR-5 并发会话的构建占用（台账已记该已知封锁，处理法 `gradlew --stop`）。**我那次 --stop 同时打断了对方正在跑的一轮，对方已在 `eb65ec973` 如实登记** |
| 第四轮 | 17:47→17:56 | `BUILD FAILED 8m16s` | `:app` `ActionModeSafeCallbackTest` 3 例红——与 SR-6 零交集（ActionMode 回调主线程时序），且与对方 `sr5_gate5b` 构建**并发**；第五/六轮在独立树同用例全绿 ⇒ 判争用抖动（归因有对照组，与第二轮不同） |
| 合并树轮 | 18:15→18:48 | **GATE_EXIT=0 / BUILD SUCCESSFUL 32m59s / 404 个 Task 行**，六模块 **8,086 / 0 失败 / 0 错误 / 17 skip** | 树 = `main`（含 SR-5 尾巴）+ 本批十笔；8,086 = 基线 8,014 + 对方尾巴新增 3 例（`SecureKeyManagerKeyAliasTest`）+ 本批 69 例，逐项对上；skip 17 与基线一致零新增。🔴 但跑动期间 SR-7 会话在同一工作树落了 `cba8ae6cb`(18:31 立卡) 与 `3902a8f73`(18:35 `core:data` 代码自纠) ⇒ 编译期早于该笔、测试期与其重叠，**树边界不可精确界定** ⇒ 不作最终判绿，转独立树 |
| 第五轮（独立 worktree 首轮） | 18:58→19:09 | `BUILD FAILED 11m4s`，`:app` 1005 例中 1 红 | 红例 = `CrashHandlerBacklogTest > success uploads and deletes local files`，`expected:<2> but was:<1>`。**独立树、无并发 ⇒ 第二轮的"争用/跨类抖动"归因被推翻**。根因链：该断言计数的唯一条件是 `uploader(content) && file.delete()`，测试注入的 uploader 恒 true ⇒ **只可能是 `file.delete()` 返回 false**（`CrashHandler.kt:201-207` 该分支既不记日志也不计数，静默）。属既有 Windows 文件句柄释放时序非确定性 + 一处"删除失败静默"的既有缺陷，**SR-6 零触碰该文件**（`git diff --name-only` 不含 `CrashHandler.kt`），登记交用户裁定（§8 条 8），不改测试、不在本批夹带修复 |
| 判绿轮（独立 worktree 第二轮） | 19:11→19:42 | **`BUILD SUCCESSFUL in 30m37s` / `GATE_EXIT=0` / 404 个 Task 行**，六模块 **8,086 / 0 失败 / 0 错误 / 17 skip**（逐模块见 §6.1） | 同一 worktree 复跑整轮（缓存与文件系统状态已热），树 = `w5/sr6-cloud-migration` @ `38a987537` = `main` + 本批十笔，**不含任何他方代码**；第五轮红例本轮绿（`CrashHandlerBacklogTest` 4/4），归因为该用例的非确定性而非本批修复 ⇒ 已列 §8 条 8 |

### 6.1 判绿轮实测（独立 worktree `C:\Mnzm\XianxiaSectNative-sr6`，第二轮 19:11→19:42）

**`BUILD SUCCESSFUL in 30m37s` / `GATE_EXIT=0` / 404 个 Task 行**，命令同 §6 表头。逐模块 XML 实测：

| 模块 | 测试类 | 用例 | skip | fail | err | 本轮 XML 数 |
|---|---|---|---|---|---|---|
| `:app` | 91 | 1,005 | 2（既有） | 0 | 0 | 91 |
| `:core:data` | 81 | 810 | 15（既有） | 0 | 0 | 81 |
| `:core:domain` | 88 | 1,748 | 0 | 0 | 0 | 88 |
| `:core:engine` | 333 | 3,414 | 0 | 0 | 0 | 333 |
| `:core:ui` | 20 | 146 | 0 | 0 | 0 | 20 |
| `:feature:game` | 91 | 963 | 0 | 0 | 0 | 91 |
| **合计** | **704** | **8,086** | **17** | **0** | **0** | **704（全部落在本轮窗口内，非 UP-TO-DATE）** |

- **对账**：8,014（SR-5 判绿基线）+ 3（`main` 上 SR-5 尾巴新增 `SecureKeyManagerKeyAliasTest`）
  + **69（本批）** = **8,086** ✓；skip 17 = 基线 17（`:core:data` StorageSystemBenchmark 15 +
  `:app` DiscipleTablesIntegrationTest 2）**零新增**；`detekt` / `compileReleaseKotlin` /
  `lintRelease` 六模块全绿（本批含 1 笔 detekt 无需修复笔，但见 §6.4）。
- **本批 69 例逐类**：`SaveMigrationPlannerTest` 22 + `SaveMigrationCoordinatorTest` 20 +
  `CloudSaveCacheWriterTest` 9 + `SaveMigrationCardTest` 7 + `SaveMigrationLedgerTest` 6 +
  `SaveMigrationGuardTest` 4 + `UploadQueueTest` Q13 1 = **69** ✓（逐类 XML 均 `failures=0`）。
- **`Diff*` 对拍（IN8）**：50 类 / **273 用例 / 0 skip / 0 失败 / 0 错误**——与基线完全一致，
  0 skip 即携对拍桥真加载（非 skip 冒充）⇒ 差分管线信封语义零破坏（D7）。

### 6.2 桌面 ctest（门 1）

在主树 `android/app/src/main/cpp/gamecore/build/desktop-test` 执行（worktree 未配置 C++ 构建，
本批零 C++ 面 ⇒ 二进制同源）：`cmake --build .` → **`ninja: no work to do.`**（本批零 C++ 变更实证）；
`ctest` → **100% tests passed, 0 tests failed out of 1561**（73.02s）。
PATH 口径：llvm-mingw-20260616 + Android SDK cmake 3.22.1 同入（SR-2 §5.4 / SR-3 §3.3 SOP）。

### 6.4 一处 detekt 判红自纠的更正

判绿轮**未**出现 detekt 判红（本轮一次通过），但本批实施期有 3 次"自己写完立刻被规则打回"的
即时自纠，均在对应子项笔内修复、未单独成笔：`ReturnCount`（`downloadIntoCache` 6 处早退，
按 SR-3 同口径加豁免注记）、`TooGenericExceptionCaught`（协调器 2 处防御兜底注记）、
`MaxLineLength`/`LongMethod`（迁移卡与选档页形参并排、动作位抽 `buildMigrationActions()`）；
另有 `capture` 冗余 import、`SaveBackendModeProvider` 漏 import 两处编译期纠正。
不粉饰成"零问题"，也不夸大成"两轮判红"。

### 6.3 证据污染声明（本批必读）

1. **共树事实与处置**：SR-5 收官会话在 14:1x–18:0x 间把 9 笔尾巴提交落在本批分支所在的
   同一工作树（其自述见 `a93938f34`/`eb65ec973`/`97f5e19f4`）；本批应用户指示执行
   `git rebase --onto main 45e025bbb`，把十笔重落成 **`main` + SR-6** 的独立单元
   （原哈希在 `sr5/tail-on-w5` 与 reflog 可达）；随后 SR-7 会话 18:31 起又在同一工作树开工
   并把 HEAD 切到 `w5/sr7-file-layer-retirement`（该树现含 Room v53 等大规模未提交在制品）。
   ⇒ **本批判绿轮改在独立 worktree `C:\Mnzm\XianxiaSectNative-sr6` 执行**：与对方零共享工作目录、
   零共享 build 产出，绿灯树 = `w5/sr6-cloud-migration` @ `38a987537`，**不含任何他方代码**；
2. **逐子项证据链独立成立**：C2/C3 的 `:core:data` 定向（cloud 包 67 例）、C4/C5/C6 的
   `:feature:game` 定向（42 例）、C8/C9 的 `:app` 定向（7+3+5 例）与四模块 `detekt`
   在每子项落库前后各自实跑（本报告各节附具体数字），不依赖整轮结论；
3. **隔离代价如实登记**：worktree 需补三样 gitignored 前置才能配置构建——
   `android/local.properties`、`android/api.properties`、`android/keystore.properties`；
   且 atlas 生成器缺 `android/scripts/node_modules`（`sharp`）会让
   `:core:engine:generateSpriteAtlasDef` 退出码 1 ⇒ 首轮 41s 即红（`ERR_MODULE_NOT_FOUND`），
   补齐后才开始有效整轮。这些都不进版本库；
4. **桥与副产物**：`.so` 复用主树路径（本批零 C++/JNI 面，豁免口径同 SR-1..SR-5）；
   worktree 内 atlas 生成会重写 `atlas-rgba-manifest.json`（构建副产物，按台账明令**不提交**，
   SR-4 `c25f507ed` 先例）；
5. **未丢东西**：除本批分支外另留 `sr6/unit-10-commits`（同指 `38a987537`）与
   `sr5/tail-on-w5`（对方 rebase 前分支原 tip `97f5e19f4`）两个 ref；对方那条
   `17:2x 分支收尾` 台账记录（16 行）现只在 `sr5/tail-on-w5` 上，
   **是否落回 `main` 交用户/该会话裁定**（本会话不动共享的 `main`）；
6. 全程**未纳管、未改写、未回退**他方在制品；我在 17:46 的 `gradlew --stop` 打断了对方一轮
   门禁，事实已在第三轮行内与本条登记，不做掩盖。

---

## 7. pending-device（真机硬门，本环境不可测，逐项不虚报）

1. **双设备迁移剧本**：A 机 6 槽存量 → 迁移 → B 机首启矩阵应判"本地无 × 云有"并可下载落盘；
2. **逐槽上传真实耗时与限频**：6 槽在 1 次/分钟共享冷却下的真实分钟数、400001 退避表现；
3. **迁移卡 Compose 渲染未经设备目视**（无设备农场，7 例只证数据链路与文案全覆盖）；
4. **存量单档 → 空槽 → 再上云为 `slot_N`** 的真实往返（含列表最终一致性窗口）；
5. **二选一端到端后果**：保留本机覆盖云端 / 改用云端覆盖本机缓存后，进游戏的真实进度正确；
6. **升档后的真实代价**：SR-4 §7 的量化项（月变 6 秒节奏 × 60 秒冷却的发热/耗电/IO 抖动）
   要非 LEGACY 才第一次可测——本批正是那个开关；
7. **迁移中断续传**：入队后杀进程，下次启动 `scan()` 能否真把待传推完（F1 路径设备级验证）；
8. **TapDB 事件注册后属性白名单是否放行**（未注册属性被静默丢弃 ⇒ 指标看似恒 0）。

---

## 8. 遗留与建议（交用户裁决，本批未夹带）

1. **TapDB 后台「事件管理」录入 `#save_migration_result`**（运营动作，无它则指标不可查）；
2. **`canPromoteToCloudOnly` 目前无生产消费者**：它是 SR-7 推 `CLOUD_ONLY` 的前置门，
   本批按纯函数 + 单测 + 守卫交付，**不得**提前接线（SR-2 `set` 当时同形态交付过）；
3. **分支拓扑（已按用户 18:0x 指示处置）**：本批十笔已 `rebase --onto main` 清成
   "`main` + SR-6 十笔"独立单元（可独立评审/回滚），判绿轮在该树上于独立 worktree 执行。
   剩一件交对方/用户裁定：`a93938f34` 的"17:2x 分支收尾"台账记录（16 行）rebase 后只存在于
   `sr5/tail-on-w5`，需其自行落回 `main`（本会话不代改共享分支）；
4. SR-3 §8 悬置项照旧：云档删除 UI、`aiSectDisciples` 换设备重生成拍板；
5. SR-5 §8 遗留 5 项照旧（其中"主密钥缓存别名"已由对方在 `04ae099f6` 根治）；
6. 后续批若再需要"下载但不 boot"，直接复用 `CloudSaveCacheWriter`，不要再复制管线序列；
7. 迁移卡在 `NEW_GAME` 模式刻意不出现（与 SR-3 云槽位卡同纪律）；产品若希望新游戏前也提示，
   属产品决策不在本批夹带；
8. 🔴 **本批门禁撞出一处与 SR-6 无关的既有缺陷，建议单独立项**：
   `CrashHandler.uploadPendingCrashLogs`（`app/.../core/CrashHandler.kt:194-210`）以
   `if (uploader(content) && file.delete()) uploaded++` 计数——**`delete()` 返回 false 时既不记日志
   也不计数**，于是"已上传成功但本地未删"的崩溃日志会在下次启动**二次上传**（服务端收到重复崩溃报告），
   且现场看不到任何痕迹；在 Windows 上该分支还会让
   `CrashHandlerBacklogTest > success uploads and deletes local files` 概率性红
   （`expected:<2> but was:<1>`，第五轮独立树实测重现）。
   建议修法（不在本批夹带）：上传与删除分离——先计"上传成功"，删除失败单独 `Log.w` 并保留待下次
   只删不传（或按已上传 id 去重），使计数与日志各自诚实。
