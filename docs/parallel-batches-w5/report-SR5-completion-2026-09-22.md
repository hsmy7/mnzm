# SR-5 完成报告 —— 时间与签名面收敛（墙钟接口化 + 云档载荷 HMAC 最小子集）

> 日期：2026-09-22　批次：SR-5（方案 §4，用户口头「完成第五批」直接驱动）
> 施工卡：[batch-SR5.md](batch-SR5.md)　权威依据：
> [docs/save-system-refactor-plan-2026-09-21.md](../save-system-refactor-plan-2026-09-21.md) §0 D1/D3/D4、
> §3 IN2/IN3/IN5/IN6/IN8、§4 SR-5、§5 门 1/2/3/6
> 状态：**delivered（`accepted` 归用户）**；真机项见 §7 pending-device，本批未声称达标。

---

## 1. 交付概览

| 子项 | 内容 | commit |
|---|---|---|
| C1 | 施工卡 + 台账 in_progress | `08fba3d2c` |
| C2 | `WallClock` 提升为共享抽象 + `CalibratedWallClock`（系统钟 + 云校正偏移）+ DI 改绑 | `5adbe45b4` |
| C3 | 周冷却单一判据 `SectLevelRewardCooldown` + 引擎 2 处/UI 1 处取时改注入 | `4dc0b2b99` |
| C3b | mock 引擎核心补桩 `wallClock`（否则 NPE 被 catch-all 吞成 Error） | `5e3ed9a86` |
| C4 | 兑换码限流 5 处裸钟改入参下传（`object` 内零取时） | `f93fa6235` |
| C5 | 邮件链 **16 个取时点**收敛（含 `MailDao` 事务内绕行点）+ UI 过期文案 | `9c8ba90e1` |
| C6 | `WallClockReflowGuardTest` 零回流守卫 | `a7c459a90` |
| C6b | **自纠**：守卫首轮组合门判红——相对路径分隔符未归一致排除清单静默失效 | `04c4567a8` |
| C7+C8 | 载荷 HMAC 签名（`SavePayloadSigner`）+ 上传后云端钟漂移采样 | `122f43dea` |
| C9 | 门禁 + 本报告 + CHANGELOG + 方案 §4 SR-5 补记 + 台账 delivered | `45e025bbb` |
| C10 | **纳管**门禁跑动期间出现的并发在制品（邮件取时下沉 `MailDelegate`） | `2d5e9fa16` |

规模：**SR-5 自身 11 笔提交**（`08fba3d2c`…`2d5e9fa16`）逐笔累计 **+1,795 / −158**、
去重后 **55 个文件**（`android/` 面 51，其余为施工卡/报告/台账/CHANGELOG）；
**零 C++、零 wire/proto、零 Room schema、零迁移链触及**。
⚠ 计数口径：不能用 `b9d198aa1..HEAD` 区间 diff（该区间另含并发 SR-6 会话的 10 笔提交，
区间差会把 SR-6 计入，实测虚高为 71 文件 / +3,976）。

四项用户拍板（P1-P4）落点：

| 拍板 | 落点 |
|---|---|
| P1 沿用 `WallClock` 名 | 新共享文件 `core/engine/.../engine/system/WallClock.kt`；命名偏离在方案 §4 SR-5 补记与本报告 §2 双登记 |
| P2 游戏语义全族 | 实际改点 24 处（C3 3 + C4 5 + C5 16），非方案点名的 4 处；详见 §3 |
| P3 复用既有云查询取偏移 | 施工期修正：改挂"上传成功后读回"（§4 条 1）；零新增启动往返 |
| P4 验签失败降级放行 | `SavePayloadIntegrity` 四态 + 云读档成功态文案 + 日志留痕 |

---

## 2. 抽象与落点（P1）

```
core:engine .../engine/system/WallClock.kt
  ├─ fun interface WallClock { currentTimeMillis(): Long }      ← 原 JadeSymbolService.kt:39 提升
  ├─ object SystemWallClock                                     ← 未校正实现（测试直构默认值）
  ├─ @Singleton class CalibratedWallClock                       ← 生产实现：系统钟 + 进程内偏移
  └─ @Module WallClockModule  (WallClock ← CalibratedWallClock)
```

- 为什么不叫 `TimeSource`：`GameTimeClock.kt:17-19`（`elapsedRealtime`）与
  `core/animation/TimeSource.kt:16-23`（`nanoTime`）已是两个**单调钟**同名类型，且有 20+
  测试 fake 实现它们；再造第三个同名异义类型是净负债（用户 P1 认可偏离）。
- 为什么落 `core:engine` 的 `system` 包而非 `*.service`：`EngineServiceAnnotationTest`
  要求 `*.service` 包内类带 `@GameService`，非服务类进该包要加豁免条目（守卫"只缩不增"）；
  `system` 包已是单调 `TimeSource` + `GameTimeClock` 的同源落点。
- **`core:data` 不能反向依赖 `core:engine`**：因此 `MailDao` 一律"调用方传 `now` 形参"，
  `:app MailRepositoryImpl` 负责把注入墙钟的读数递下去（接口 `MailRepository` 签名零改 ⇒
  现有 fake 零改）。这一约束也是 §8 遗留项（存档时间戳族未收敛）的根因。

---

## 3. 取时点清单（P2 实测口径，24 处）

| 族 | 收敛前 | 收敛后 |
|---|---|---|
| 玉符日额 | `JadeSymbolService.kt:459` 已走 `WallClock`（方案收编项为**既成事实**） | 抽象搬迁，判据零改 |
| 周奖励冷却 | `GameEngineSectLevelOps.kt:63` / `:350`、`GameViewModel.kt:519`（内联硬编码 7 天，可与引擎闸门分歧） | 3 处同源 `SectLevelRewardCooldown` + 注入墙钟；UI 判据经 `GameViewModel.mailDisplayNowMs` |
| 兑换码限流 | `RedeemCodeRateLimitOps.kt:27`/`:131`、`RedeemCodeLifecycleOps.kt:115`/`:141`、`RedeemCodeManager.kt:355` | 5 处改 `nowMs` 形参；宿主 `object` 无 DI，故由注入式入口 `RedeemCodeService.localRedeem` **一次采样**下传（正向副作用：四层限流与清理起算同刻，此前 5 次采样会错位） |
| 邮件链 | `MailService.kt` 5 个 `timeSource()` 读点、`MailAttachmentDistributeOps.kt` 3 个、`MailDao.kt:46`、`OverflowMailSender.kt` 4 个、`SecretRealmService.kt` 2 个、`MailDialog.kt` 1 个 | 16 处收敛。删除 `@VisibleForTesting var timeSource`（实测全仓**零写入者**，其 KDoc 声称的 `MailServiceTest PINNED_NOW_MS` 不存在 ⇒ IN6 不留无人使用的机制） |

两条勘察纠错（子代理清单与方案字面均有出入，已逐条复核代码后修正）：

1. 方案 §4 SR-5 写 `MailService.deleteExpiredMails` —— 该方法不在 `MailService` 上
   （契约在 `core:domain MailRepository:30`，实现 `:app MailRepositoryImpl:50`，SQL `MailDao:31`）；
2. 邮件族真正的**守卫抓不到的绕行点**是 `MailDao.insertWithEnforceLimit`（`@Transaction` 内
   每次插入顺带删一次 30 天过期，用的是 DAO 内裸钟）——只改 `MailService` 完全无效。
   另：`MailAttachmentDistributeOps.kt` 的 3 个读点（领取过期判据 + `mailRecords.claimedAt`
   落账）在勘察清单里漏计，由编译器抓到后补入。

> **计数口径以本报告为准**：C5 的 `9c8ba90e1` commit 信息写"13 处"是施工中期估算，
> 收官实测为 **16 处**（8 处经 `MailService` 旧 `timeSource` lambda 间接取时 +
> 8 处直接裸读 `System.currentTimeMillis`）。不改历史 commit（宁可留痕于报告）。

---

## 4. 云校正与签名（P3 修正 + P4）

1. 🔴 **冷启动云列表 mtime 不可作偏移样本**（施工期实测证伪，按同一意图修正而非照抄）：
   `CloudSaveEntry.modifiedTimeMs` = **上次归档写入时刻**，按其算
   `offset = cloudMtime - localNow` 等于把本地钟往回拨"距上次上传过了多久"（小时/天量级），
   会直接打穿玉符日额跨天与邮件过期判据。⇒ 采样点改挂 `TapTapSaveBackend.upload`
   成功之后的 `queryArchiveInfo` 读回（此刻服务端 mtime 才等价"现在"），并加四重约束：
   进程内至多接受一次、`|漂移| > 5min` 丢弃、取样必须用 `uncalibratedNowMs()`
   （不能用已校正的钟测自己的校正量）、观测函数**必须不抛**（异常外溢会被外层 catch
   翻译成 Failure，把已成功的存档误报成失败）。
   成本如实登记：非 LEGACY 下每次进程生命周期内**多一次元数据读回**（仅在首次上传成功后）。
2. **偏移不跨进程持久化**：持久化一个错误偏移的代价高于"冷启动不校正"；重启后由下一次
   新鲜写入重新采样。玩家大幅改钟的根治要等后端真源（D4 `GameServerSaveBackend`）。
3. **LEGACY 零行为变化**：LEGACY 不上传 ⇒ 永不采样 ⇒ 偏移恒 0 ⇒
   `CalibratedWallClock.currentTimeMillis()` 与 `System.currentTimeMillis()` **逐位一致**
   （`WallClockCalibrationTest` 首条判据锚定）。
4. **签名域 = 压缩后载荷字节**；`extra` 元数据不入签名域（服务端往返的自由文本，键序/空白
   不受控 ⇒ 纳入会让验证侧脆弱）。后果如实登记：`extra.saveId` **可被伪造**。不影响 IN2
   仲裁（真相源是本端账本脏标志），但意味着签名不是完整性闭环。
5. **验签零新增往返**：`arbitrateAgainstCloud` 本就 `queryArchiveInfo` 拿 `extra`，
   签名就地解析；`fetchAndDeserialize` 改为同时返回字节与反序列化结果，验签在字节域做。
6. **诚实局限三条**（方案 §4 SR-5 原文口径，勿当安全闭环）：验签在客户端做、密钥也在同一
   设备（能取到本机密钥者可自造合法签名）；**验签发生在反序列化之后**（本批不承诺
   "未验签不解析"）；`KEY_UNAVAILABLE` 与 `MISMATCH` 分列，避免把密钥故障判成玩家篡改。
7. **密钥域分离**：master 复用 `.secure_key`（`SecureKeyManager.getOrCreateKey`），派生式与
   网络签名链同构（SHA-256×1000）但 **salt 独立**；本类**不清零 master**——
   `SecureKeyManager` 以数组引用缓存，清零会污染同进程后续取键（对照 §8 条 2）。

---

## 5. 守卫与 residual 债务（C6）

`WallClockReflowGuardTest`（`core:engine/src/test/.../architecture/`，仿 `RngSourceGuardTest`）：

1. 收敛清单 15 文件内 `System.currentTimeMillis` **零回流**（清单条目失效即红，
   不许直接删判据）；
2. 清单规模锁死 15（放宽豁免须显式改常量）；
3. 六模块族外裸钟**精确登记**（注释剔除、排除收敛清单与抽象本体）：
   `core:engine 40 / core:data 68 / core:domain 9 / core:ui 0 / feature:game 25 / app 26`
   = **168 处**，变多判红、变少要求同步下调。
   （首轮登记值是 42/170——守卫自己的路径分隔符 bug 让抽象本体的 2 行合法裸读被误计，
   判红后自纠，见 §6 门 2 与 commit `04c4567a8`。）

`Calendar.getInstance()` **不纳入**判据：`JadeSymbolService.getTodayStartMs` 用它做本地时区
零点化（`timeInMillis` 由入参显式赋值），不构成取时，纳入即误报。

residual 168 处的构成（本批不动的理由）：日志耗时/看门狗/FPS/缓存 TTL/熔断计数/
WAL 时间戳/崩溃恢复账本/归档文件名与保留期/RNG 播种/UI 动画与系统栏冻结/网络请求签名
时间戳，以及 §8 条 1 的时间戳盖写族。均非"玩家可通过改钟获利"的游戏语义判据。

---

## 6. 门禁实测（判绿看 XML executed 计数 + 时间戳，不看 BUILD SUCCESSFUL）

**门 1 · 桌面 ctest**（纯 Kotlin 批，零 C++ 面）：`cmake --build .` → **`ninja: no work to do.`**
⇒ 本批零 C++ 变更实证；`ctest` → **100% tests passed, 0 tests failed out of 1561**（57.60s，
llvm-mingw-20260616 + SDK cmake 3.22.1 入 PATH）＝ SR-1..SR-4 基线 1561 持平。

**门 2 · 六模块组合门（三轮，逐轮如实登记）**

命令 = `testReleaseUnitTest --max-workers=1 --rerun-tasks -Dgamecore.jni.path=.../libgamecorejni.so
detekt compileReleaseKotlin lintRelease`（桥 `.so` mtime 2026-09-21 21:40：本批零 C++/JNI 面 ⇒
复用，口径同 SR-1..SR-4 豁免）。

| 轮 | 结果 | 说明 |
|---|---|---|
| 第一轮 | `BUILD FAILED in 10m38s`，`GATE_EXIT=1`，186 任务 executed | 唯一失败 = **本批新守卫自己**：`WallClockReflowGuardTest.kt:90`。根因两层——① Windows 下 `File.relativeTo().path` 用反斜杠，与清单里 `/` 永不相等 ⇒ 排除清单**静默失效**；② C7 给 `WallClock.kt` 加 `uncalibratedNowMs()`（+1 合法裸读）后未重登记。守卫行为正确（它同时抓住了这两件事）。独立笔 `04c4567a8` 按仓库既有写法（`RngEngineIsolationGuardTest.kt:68` 的 `replace(File.separatorChar,'/')`）修正，登记值 core:engine 42→**40** |
| 第二轮 | 92 任务后中断，`GATE_EXIT=1` | **非判绿轮**：`:feature:game:compileReleaseKotlin` 处 `Gradle build daemon has been stopped: stop command received`（外部 stop，全日志无任务 FAILED）⇒ 不构成门禁结论，原样重跑 |
| 第三轮 | **`BUILD SUCCESSFUL in 25m57s`，`GATE_EXIT=0`，404 个 Task 行 / 339 任务全 executed** | 判绿轮，逐模块见下表；detekt 六模块全绿、`compileReleaseKotlin` 全绿、`lintRelease` 全绿（**无 detekt 修复笔**，与 SR-1..SR-4 每轮都被 detekt 判红一次不同） |

第三轮逐模块 XML 实测（**时间戳全部落在本轮 05:50–05:57 UTC 单窗，非 UP-TO-DATE**）：

| 模块 | 测试类 | 用例 | skip | fail | err | XML 时间戳（UTC） | 对 SR-4 |
|---|---|---|---|---|---|---|---|
| `:app` | 90 | 998 | 2（既有） | 0 | 0 | 05:50:03–05:51:10 | ±0 |
| `:core:data` | 77 | 774 | 15（既有） | 0 | 0 | 05:51:29–05:51:46 | +6（签名器） |
| `:core:domain` | 88 | 1,748 | 0 | 0 | 0 | 05:51:54–05:52:01 | +5（冷却纯函数） |
| `:core:engine` | 333 | 3,414 | 0 | 0 | 0 | 05:52:50–05:54:43 | +15（墙钟 7 + 兑换码 5 + 守卫 3） |
| `:core:ui` | 20 | 146 | 0 | 0 | 0 | 05:54:58–05:55:11 | ±0 |
| `:feature:game` | 89 | 934 | 0 | 0 | 0 | 05:56:02–05:57:24 | +2（extra 签名写读） |
| **合计** | 697 | **8,014** | **17** | **0** | **0** | — | **+28 = 本批新用例** |

- 基线对照：SR-4 收官轮 7,986 → **8,014（+28，逐模块差额见上表，合计与新增用例数完全吻合）**；
  **skip 17 与基线完全一致零新增**；
- **门 3 `Diff*` 对拍（IN8）**：50 类 / **273 用例 / 0 失败 / 0 跳过**（携对拍桥真加载，非 skip 冒充）。

**门 4 · 本批新增/改判据定向单测**（逐类计数，均出自第三轮判绿轮）：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `WallClockCalibrationTest`（新） | 7 | 偏移 0 与系统钟逐位一致（LEGACY 红线锚）、样本接受/丢弃、阈值边界、单次约束、非法参数 |
| `SectLevelRewardCooldownTest`（新） | 5 | `>= WEEK_MS` 边界、墙钟回拨保守、`nextClaimableAt`、按等级取记录 |
| `RedeemCodeWallClockTest`（新） | 5 | 四层限流与清理起算全部按入参时刻开窗 |
| `WallClockReflowGuardTest`（新，C6） | 3 | 15 文件零回流、清单规模锁死、六模块计数精确登记 168 |
| `SavePayloadSignerTest`（新） | 6 | 签名确定性与 hex 形状、单字节篡改检得、`UNSIGNED`/`MISMATCH`/长度异常不抛、master 缓存不被清零 |
| `TapTapSaveBackendTest`（补 2 例，8→10） | 10 | 有签名才写 `sig`/`sigVer`、无签名不写键（向后兼容）、`parseSignature` 降级 |
| `OverflowMailSenderTest`（改判据） | — | `createdAt > 0` 升级为 `createdAt == pinned 注入钟`（判据可复现） |

**门 5 · 真机**：见 §7，本环境不可自动化，逐项 pending-device，未声称达标。
**门 6 · 文档三件套**：完成报告（本文件）+ `CHANGELOG.md` 4.01.15 段新增 SR-5 小节 +
台账 SR-5 行 delivered + 方案 §4 SR-5 实施补记（方案文档按 SR 纪律保持 untracked）。

### 6.9 并发在制品处置记录（判绿轮曾覆盖他方未提交改动，已纳管归位）

第三轮组合门跑动期间（13:31→14:09），工作区出现**三处非本会话所作**的源码改动
（`GameViewModel.kt` 13:35:28 / `MailDelegate.kt` 13:35:49 / `MailDialog.kt` 13:35:54，
内容为把本批 C5 的 `mailDisplayNowMs()` 从 VM 挪进 `MailDelegate` 并下传墙钟），
而 `:feature:game` 测试恰在 13:56 跑过它们 ⇒ 判绿轮覆盖的是
"本批 9 笔提交 + 这三处未提交改动"的**合并树**，不是本批提交树。

**处置 = 用户 2026-09-22 指示"直接处理他的改动" ⇒ 逐条复核后纳管，不回退不丢弃**
（commit `2d5e9fa16`）。复核依据：

1. 判据语义零变化——同一个 Hilt 单例 `WallClock`，只是取时点从 VM 移到邮件委托；
2. `MailDelegate` 全仓仅一个构造点（`GameViewModel.kt:135`），无测试直构 ⇒ 零连带改面；
3. 无悬挂引用——`mailDisplayNowMs` 全仓零残留；
4. 分层上优于原状（邮件 UI 取时归邮件委托，VM 不再挂邮件专用公开函数）；
5. 纳管后复验：`WallClockReflowGuardTest` 3/0（六模块登记值不变，feature:game 仍 25、
   族外合计仍 **168**）+ `:feature:game --tests "*Mail*"` 与 `"*TapTapSaveBackend*"` 全绿。

⇒ 三处并发在制品经用户指示纳管为 `2d5e9fa16`（不回退不丢弃），该改动本身语义零变化。

🔴 **但本报告此前一句结论是错的，就地更正**：曾写"纳管后 HEAD 源码树与判绿轮重新一致"。
实测并非如此——**同一分支上另有并发 SR-6 会话在绿灯之后继续提交**：
`e5c6f9391`(14:18)…`775217a58`(15:42) 共 **10 笔 SR-6 提交**落在本批 13:31→14:09 判绿轮之后。
因此：

| 证据 | 覆盖的树 | 是否覆盖当前 HEAD |
|---|---|---|
| 六模块组合门 8,014/0/17、`Diff*` 273/0、detekt/lint/compile 全绿 | 13:31–14:09 的树 = 本批至 `04c4567a8`/`122f43dea` 的提交 + 三处未提交在制品 | ❌ 不覆盖（HEAD 另含 SR-6 的 10 笔 + 本批 `45e025bbb`/`2d5e9fa16`） |
| 桌面 ctest 1561/1561 + `ninja: no work to do.` | 14:1x 实测；本批零 C++ 面 ⇒ 对 SR-6 是否改 C++ 未复核 | ⚠ 仅对本批成立 |
| 逐子项定向实跑（C2-C7 每笔落库前后） | 各自提交点 | ✅ 逐笔成立（这是本批真正的独立证据链） |
| 纳管笔 `2d5e9fa16` 复验：`WallClockReflowGuardTest` 3/0 + `:feature:game` `*Mail*`/`*TapTapSaveBackend*` 全绿 | 16:1x 的当前树（含 SR-6） | ✅ 覆盖 HEAD，但只是**定向**范围，非六模块整轮 |

**结论口径**：SR-5 的验收判据以"逐子项定向实跑 + 逐笔独立提交"为凭；
六模块整轮绿灯是 13:31–14:09 那个树的结论，**不能当作当前 HEAD 的绿灯**。
补一条覆盖 HEAD 的整轮需要现在重跑组合门——跑出来的是 **SR-5 + SR-6 合并树**的结论，
不再纯化为本批证据 ⇒ 是否重跑、由谁跑（SR-6 会话仍在活动，跑期间树会继续漂移）
交用户裁定（§8 条 6）。

旁证留档：第二轮组合门在 `:feature:game:compileReleaseKotlin` 处收到外部
`gradlew --stop`（全日志无任务 FAILED）——与本批并发存在的第二操作方（SR-6 会话）
疑为其所致。**教训已入项目记忆**（门禁前后各比一次 `git status` 与 `git log`，见
`memory/gate-tree-pollution-check.md`）。





---

## 6A. 收官后追加门禁（根治笔与 detekt 自纠之后，四轮实录）

§6 的绿灯属于 13:43–14:09 那棵树；其后本批又落了密钥别名根治两笔与一笔风格自纠，
故按"判绿轮必须带树指纹"的新纪律追加四段实测（`TREE-BEGIN/TREE-END` 原文存
`/tmp/sr5_gate4.log`、`/tmp/sr5_gate5.log`、`/tmp/sr5_gate5b.log`）：

| 轮 | 起树 / 止树（源码脏文件数） | 结果 | 归属 |
|---|---|---|---|
| 第四轮 17:03 | `8f669fa30`(1) → `a93938f34`(1) | 六模块测试**全绿 8,086/0 失败/17 skip**（= SR-5 8,014 + SR-6 72 例）+ `Diff*` 273/0；**`:core:data:detekt` 判红** = 本批新代码 `SavePayloadSigner.kt:109` 用裸 `throw IllegalStateException`（`UseCheckOrError`） | 测试面覆盖 SR-5+SR-6 合并树；判红点是本批自身 |
| 自纠 | — | `f3af824b6`：改 `check(master.any { it != 0.toByte() }) { … }`（`check` 抛的正是 `IllegalStateException`，仍由同一 catch 归口降级 ⇒ 零行为变化）；本地 `:core:data:detekt` + `SavePayloadSignerTest` 6 例复绿 | 本批 |
| 第五轮 17:2x | `f3af824b6`(0) → `f3af824b6`(0) | **`:core:data` 810/0/15、`:core:domain` 1,748/0、`:core:engine` 3,414/0、`:core:ui` 146/0、`:feature:game` 963/0 全绿 + 六模块 `detekt` 全绿 + 六模块 `compileReleaseKotlin` 全绿 + `Diff*` 273/0 skip**；`**`:app:testReleaseUnitTest` 与 `lintRelease` 被外部 `gradlew --stop` 打断**（日志止于 `lintAnalyzeRelease` 段、无任务 FAILED 行、前后树指纹完全一致 ⇒ 非本批代码所致，与第二轮的中断同因同源） | 五模块 + 风格门覆盖合并树；`:app`/lint 未取证 |
| 补跑 ×2 | 同上 | **未跑成**：`:core:domain:bundleLibCompileToJarRelease` 报 `classes.jar` 被另一进程占用（`FileSystemException`）⇒ 有另一路构建正在同一构建目录上活着。**本会话刻意不执行 `gradlew --stop`**——那会打断对方（SR-6）的构建，两败；改日重跑或按 §8 条 6 的隔离方案处理 | 零结论 |

### 6A.1 现状结论口径（不吹绿）

- SR-5 自身的验收凭据仍是 §6 的**逐子项定向实跑**（每笔落库前后各跑一次，含
  `SecureKeyManagerKeyAliasTest` 的"退回旧语义判红"自证）；
- 合并树（SR-5 + SR-6）当前已知：五模块测试 + 六模块 detekt + 六模块 compile +
  `Diff*` 273/0 **绿**；`:app` 测试最近一次完整绿是第四轮 1,005/0/2（其后的两笔改动
  只落在 `core:data/crypto` 与文档面，未触 `:app` 源码）；**`lintRelease` 在根治笔之后
  尚未取证**——这是本批唯一未闭环的门禁项；
- 追加两轮还顺手坐实了并发根因的另一半：两边共用同一 Gradle 守护与 `build/` 目录时，
  一方按 SOP 执行 `--stop` 解 jar 锁，就会把另一方的整轮打停在半途（第二、五轮皆此形）。

### 6A.2 分支落位（收尾后）

`main` == `sr5/closing-gates` == `e76e60aca`（16 笔，纯 SR-5，含 detekt 自纠，未 push，
回退锚点 `git branch -f main 122f43dea`）；`w5/sr6-cloud-migration` 仍为 SR-6 工作分支，
其上的 SR-5 尾巴原身与本分支副本是"同内容不同哈希"，合并时取任一即可。

---

## 7. pending-device（真机硬门，本环境不可测，逐项不虚报）

1. **签名链路真机字节稳定性**：LZ4 + 序列化往返后跨设备读回是否逐位一致——
   若不一致则每次跨设备下载都判 `MISMATCH`，**整批签名能力失效**（本批单测只能证同进程内确定）；
2. 上传成功后元数据读回的真实延迟与服务端 mtime 精度（秒级）能否落进 `|漂移| ≤ 5min` 阈值；
3. extra 经 TapTap 服务端往返后 `sig`/`sigVer` 是否原样回带（元数据最终一致性延迟）；
4. 历史无签云档（SR-2/SR-3 期间上传）在 `UNSIGNED` 判据下下载链路是否正常；
5. 偏移非 0 时玉符跨天、邮件过期、周冷却的真实观感（有无误重置/误判过期）；
6. `MailDao` 新增 `now` 形参后，真机高频插入（溢出邮件 drain）路径的删除时机是否不变。

---

## 8. 遗留与建议（交用户裁决，本批未夹带）

1. **存档时间戳与模型默认时间戳族未收敛**：`StorageEngine.kt:232`
   （`stamped.copy(timestamp = …)`，进 `SaveData` proto 且被
   `SaveDataVersionMigrator:131` 当迁移判据读）、`SaveService.kt:90`、
   `core/domain` 7 处模型字段默认值、`SpiritStoneTransaction:21`、`WarehouseModels:55/87`、
   `LeaderboardManager:60`（日桶）。根因是 `WallClock` 在 `core:engine`，`core:data`
   不能反向依赖 ⇒ **需要一次落点决策**（上移到 `core:domain` 或按 MailDao 先例继续传形参）。
2. ✅ **主密钥缓存别名缺陷已根治**（收官后用户指示"根治解决"，`04ae099f6` + `1039591e3`）：
   `SecureKeyManager.getOrCreateKey` 命中缓存时曾返回 `KeyCache.key` 的**引用**，而
   `RequestSigner.kt:184` 与 `SecureHttpClient.kt:425` 都按"清自己副本"的意图写着
   `masterKey.fill(0)` ⇒ 全零密钥被写回进程级缓存，且缓存是"命中即续期"的滑动 TTL
   （`copy(lastAccess = now)`），只要有取键流量就可**无限期存活**（不是 ≤5 分钟自愈）。
   三层后果：响应解密派生错误密钥 / 本批 `SavePayloadSigner` 用全零 master 派出恒定密钥并
   钉死整进程 / `verifyKeyIntegrity` 哈希必不匹配 ⇒ **误报"密钥丢失"并触发恢复预警**。
   根治只一处（两条返回路径各 `copyOf()`），因此那两句 `fill(0)` **保持不动**——它们从此
   就是本来想做的"擦除私有副本"，无需跨模块改网络链。配套契约测试
   `SecureKeyManagerKeyAliasTest` 3 例：修复前**临时退回旧语义实测 3 例全红**
   （其中"两次取键引用不同"必须预热缓存才有判别力，已写进测试注释），修复后全绿；
   `:core:data` 810 用例/0 失败/15 既有跳过、`:app` 1005/0/2 全绿。
   SR-5 侧另收口两处（`1039591e3`）：签名器不再永久缓存密钥（主密钥轮换后不用陈旧密钥）、
   全零 master 拒绝派生并归口降级为"不签名/KEY_UNAVAILABLE"而非误判篡改。
   🔴 **待办转交**：本根治动了 `core:data/crypto`，**尚未跑六模块整轮**（只在
   `:core:data`+`:app` 全量与 `:core:data` crypto 定向取证）⇒ 需要一条覆盖当前 HEAD 的
   整轮门禁补证（与 §8 条 6 的裁定的同一件事）。
3. `RedeemCodeManager.validateCodeWithServerAuth` + `remoteValidator` 注册链实测**零调用者**
   （IN6 议题，本批只透传 `nowMs` 不改语义、不删）。
4. `RedeemCodeRateLimitOps.verifySignature` 是"SHA-256 + 静态后缀"的**无密钥哈希**，
   名为签名实非 MAC ⇒ 建议纳入后续"防作弊"议题一并处置（本批不动，防夹带）。
5. 建议把 `WallClockReflowGuardTest` 的 residual 168 处按族拆成**逐文件预算**
   （现按模块预算，族内文件间搬移不会被抓到）。
6. 🔴 **并发操作方需用户裁定（本批最重要的流程发现）**：同一分支 `w5/sr6-cloud-migration`
   上存在**第二个会话在并行提交 SR-6**（绿灯后 10 笔），且它曾在门禁跑动中途就地改我的
   C5 文件、还疑似发起过 `gradlew --stop` 中断第二轮门。三处并发在制品已按指示纳管
   （`2d5e9fa16`，语义零变化 + 定向复验），但**六模块整轮绿灯不再覆盖当前 HEAD**（§6.9）。
   需裁定：① 是否现在重跑整轮（结果是 SR-5+SR-6 合并树，且 SR-6 仍在动，跑期间会继续漂）；
   ② 后续批次是否强制串行/分分支，避免两个实施会话共用一棵工作树。
   **建议 ②**——共树并发已被实证会污染门禁证据。
