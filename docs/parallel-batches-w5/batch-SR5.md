# SR-5 时间与签名面收敛施工卡（TimeSource 落地名 = WallClock + payload HMAC 最小子集）

> 立卡：2026-09-22（用户口头「完成第五批」直接驱动，看护 cron 已于 SR-3 终局删除；
> 本会话 = 实施会话，非派发）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-5」+ §0 D1/D3/D4/D7 +
> §3 IN2/IN3/IN5/IN6/IN8 + §5 门 1/2/3/6；前置资产 = SR-2（SaveBackend/UploadQueue/
> UploadLedger/SaveArbiter/三态开关/postSaveWarning）+ SR-3（云主路径，批次停 pending-device）
> + SR-4（月变/onStop 自动触发面）。
> **方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表（看护已终局，SR 行由实施会话自记，
> 沿用 SR-0..SR-4 口径；`accepted` 仍归用户，本卡不自行登记）。
> 纪律：本会话只做 SR-5 一批，不开下一批；**LEGACY 模式行为零变化红线不变**；
> 迁移链与 wire/schema 面零触碰（本批无 Room schema 变更、无 proto tag 变更）；每子项独立 commit、不夹带。

---

## 0. 定位

SR 系列的**时间与签名面收敛批**（方案 §4 SR-5，联网化前置）。三件事：

1. **墙钟接口化**：把"客户端墙钟信任族"（游戏日额/周冷却/兑换码限流/邮件过期）从散落的
   `System.currentTimeMillis()` 收进单一可注入抽象，生产实现 = **系统钟 + 云端可校验偏移**；
2. **payload HMAC 签名最小子集**：云档字节签名写入 extra 元数据，下载侧**客户端验签**，
   结果作为类型化判据显式暴露（方案自陈：无服务器验证前是接口与格式预埋，不是安全闭环）；
3. **零回流静态守卫**：游戏语义族文件内裸墙钟读数归零并锁死（只缩不增）。

**门**：墙钟/签名纯函数与消费面单测 + 零回流静态守卫 + 定向测试 + 六模块组合门
+ `Diff*` 0 skip（IN8）+ 桌面 ctest 照跑 + 真机项 pending-device 登记（本环境不可自动化，不虚报）。

---

## 1. 四项用户拍板（2026-09-22 会话，本卡据此施工）

| # | 议题 | 拍板 | 施工口径 |
|---|---|---|---|
| P1 | 接口命名 | **沿用既有 `WallClock` 名** | 方案 §4 SR-5 字面写"新 `TimeSource` 接口"，实测 `TimeSource` 已被两个**单调钟**占用（`GameTimeClock.kt:17-19` 的 `elapsedRealtime` 与 `core/animation/TimeSource.kt:16-23` 的 `nanoTime`），且 epoch 墙钟抽象**已存在** = `WallClock`（`JadeSymbolService.kt:39-41`，已 Hilt 绑定）。⇒ 本批把 `WallClock` 提升为共享类型并作为方案"TimeSource"的落地名，**命名偏离在方案 §4 SR-5 与本批完成报告双登记**，不新增第三个同名 `TimeSource`。 |
| P2 | 收敛范围 | **游戏语义全族** | 不止方案点名的 4 处：周奖励 engine 2 处 + UI 内联重复 1 处、兑换码 5 处、邮件全链（`MailService` 5 读点 + `MailDao` 事务内裸钟 + `OverflowMailSender` 4 处 + `SecretRealmService` 2 处 + `MailDialog` 1 处）+ 存档时间戳盖写点。守卫用**文件白名单 + 计数只缩不增**锁死。 |
| P3 | 云 mtime 校正落点 | **复用既有云查询，零新增启动往返** | 见 §2 条 6 的实测修正：冷启动列表 `modifiedTimeMs` 在时间语义上**不可**作偏移样本，故按同一意图改挂"本端刚写完云档"的读回观测点，进程内最多采样一次。 |
| P4 | 验签失败策略 | **降级放行 + 显式留痕** | `CloudSavePayload` 增类型化 `integrity`（`VERIFIED`/`UNSIGNED`/`MISMATCH`），MISMATCH 时如实日志 + 告警通道留痕，仍允许玩家用档；**不硬阻断**（密钥轮换/换设备/历史无签档会把玩家锁在档外）。 |

---

## 2. 关键勘察结论（2026-09-22 直读代码确认；子代理线索已逐条复核）

1. **玉符日额已收敛**（方案 §1.3 的此项已是既成事实）：`JadeSymbolService.kt:104` 注入单调
   `TimeSource`、`:106` 注入 `WallClock`，唯一墙钟读点 `:459`，跨天派生 `:510-516` 用
   `Calendar.getInstance()` 做本地时区零点化。⇒ 本批对它只做**类型搬迁**（提取到共享文件）
   + 把生产实现换成带偏移的实例，零判据改动。
2. **周冷却有两份实现且会分歧**：`GameEngineSectLevelOps.kt:63`（领取闸门，`nowMs` 已形参化
   到 `findSectLevelCooldownResult`）与 `:350`（`canClaimSectLevelReward` 独立裸读）；
   另有 UI 侧内联重复 `GameViewModel.kt:519-520`（硬编码 `7L*24*60*60*1000`）。
   ⇒ 收敛为**单一纯函数**（同 `findSectLevelCooldownResult` 语义）+ 两侧共用，消除徽章与闸门分歧。
3. **兑换码族 5 处裸钟，宿主是 `object`**：`RedeemCodeRateLimitOps.kt:27`（一次采样喂 4 层限流）
   与 `:131`、`RedeemCodeLifecycleOps.kt:115`/`:141`、`RedeemCodeManager.kt:355`。
   `RedeemCodeManager` 为 `object`（`:28`），**无 DI 绑定**；生产入口是注入式
   `RedeemCodeService`（`@Singleton @Inject`，`:376` 调 `validateCode`、`:394` 调 `generateReward`）。
   ⇒ **不采用** `MerchantItemConverter.initialize()` 式进程级可变注入（`RngEngineIsolationGuardTest`
   的论据：进程级全局注入有双实例互踩事故前科）；改为**显式把 `nowMs` 形参从 `RedeemCodeService`
   线程化下传**（服务 ctor 注入 `WallClock`，一次兑换采样一次），object 内零时钟。
4. **邮件过期是跨 3 模块链且有一处守卫抓不到的绕行**：方案写 `MailService.deleteExpiredMails`
   ——该方法不在 `MailService` 上（`MailRepository.deleteExpiredMails(slotId, now)` 在
   `core:domain`，实现在 `:app/MailRepositoryImpl.kt:50`，SQL 在 `MailDao.kt:31`）。
   `MailService.kt:87-88` 已有第三种时钟缝（`@VisibleForTesting var timeSource: () -> Long`）。
   🔴 真正的绕行点：`MailDao.insertWithEnforceLimit`（`@Transaction`，`:41-46`）在**每次插入**
   时用裸 `System.currentTimeMillis()` 删一次过期——若只改 `MailService`，这条链仍在裸钟上。
   ⇒ DAO 改形参 `now: Long`；由 `:app/MailRepositoryImpl` 注入 `WallClock` 供时
   （`MailRepository` 接口签名不动 ⇒ 现有 fake 零改）。
5. **模块依赖方向**：`feature:game` → `core:engine`/`core:data`（`feature/game/build.gradle:65-67`），
   `:app` 全可见，`core:domain` 零 Hilt 模块。⇒ 共享 `WallClock` 落 `core:engine`
   `...core.engine.system` 包（与单调 `TimeSource`、`GameTimeClock` 同目录，纯 JVM）；
   **不落 `*.service` 包**（`EngineServiceAnnotationTest` 要求该包类带 `@GameService`）。
6. 🔴 **P3 前提的实测缺陷（施工期发现，按同一意图修正而非照抄）**：冷启动云列表的
   `CloudSaveEntry.modifiedTimeMs` 是**上次归档写入时刻**，不是"服务器当前时刻"——用它算
   `offset = cloudMtime - localNow` 会把本地钟往回拨"距上次上传多久"（小时/天量级），
   直接打穿玉符日额与邮件过期判据。⇒ 采样点改为**上传成功后的元数据读回**（此刻写入必然
   发生在毫秒级前，样本才有意义），并加约束：进程内最多一次、`|drift|` 超阈值即丢弃留痕、
   任何异常不影响上传结果、**不跨进程持久化**（偏移源不可信时宁可不校正）。
   真正的玩家改钟根治要等后端真源（方案 D4 的 `GameServerSaveBackend`），本批只交付接口与算式。
7. **签名落点唯一自然位 = extra JSON**：`TapTapSaveBackend.upload`（`:74-101`）序列化 →
   临时文件 → `api.createOrUpdateArchive(extra=...)`；`buildSummaryAndExtra`（`:274-287`）
   已在写协议 `year/month/sect/disciples/stones/version/saveId`。下载侧
   `arbitrateAgainstCloud`（`:143-183`）**已经** `queryArchiveInfo` 拿到 `rawInfo.extra`
   ⇒ 验签零新增往返（复用同一次元数据查询）。反射桥的 `extra` 是自由文本槽
   （`CloudSaveApiBridge.kt:337-343` 读侧；写侧经 `createOrUpdateArchive(extra=)`）。
8. **密钥与比较原语现成**：`SecureKeyManager.getOrCreateKey(context)`（`core:data/crypto:125`，
   public；`getOrCreateDerivedKey` 是 private）已被网络签名链以"masterKey + salt + SHA-256×1000"
   派生（`RequestSigner.kt:170-192`）。⇒ 云档载荷**另用独立 salt**派生，与网络签名密钥域分离
   （SR-7 明令保留 `.secure_key` 网络签名链，故密钥体系只读不改造）；
   比较走 `SecurityPrimitives.timingSafeEqual`（`core:data/crypto:17`，KDoc 本就写着"HMAC 签名验证"）。
9. **守卫基建有现成模板**：`RngSourceGuardTest.kt`（`core:engine/src/test/.../architecture/`）
   已实现 `mainSourcePath()` 跨 6 模块取源、注释剥离（避免"改注释即改守卫"）、
   **计数只缩不增**登记表、模块源目录不可达时 `AssertionError`（不是 skip）。
   `RngEngineIsolationGuardTest.kt` 另有"白名单条目数本身被断言"（新增豁免即红）。
   ⇒ C6 直接沿用这两套机制，不自造路径解析。
10. **测试基建**：`WallClock` 是 `fun interface` ⇒ 测试 fake 就是 `WallClock { fixed }`；
    `MailServiceTest` 现有 `timeSource = { PINNED_NOW_MS }` 写法搬迁为构造注入，逐类核对。
    `GameViewModel`/`GameEngineCore` 直构点极多 ⇒ 新 ctor 形参一律带默认值
    （`SystemWallClock`），仓库先例：`GameEngineCore` 的 `engineCrashReporter`/`overflowMailHandler`
    等"默认供测试直构、生产由 Hilt 注入"。
11. **零 C++/wire/schema**：本批不碰 proto tag、不碰 Room 版本、不碰迁移链 ⇒ ctest 预期
    `ninja: no work to do.`，`Diff*` 必须仍 273 例 0 skip。

---

## 3. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-5 行 in_progress（实施会话自记） | 1 笔 |
| C2 | 墙钟抽象提升 | `WallClock`/`SystemWallClock`/`WallClockModule` 提取到 `core/engine/.../engine/system/WallClock.kt`；新增 `CalibratedWallClock`（系统钟 + 进程内 `@Volatile` 偏移，`applyDriftSample` 纯算式 + 阈值/单次约束）；`JadeSymbolService` 改消费共享类型；DI 生产绑定切到 `CalibratedWallClock`；单测（偏移 0 逐位等价、样本超阈值丢弃、只采一次） | 1 笔 |
| C3 | 周冷却收敛 | 提取纯函数（记录 + nowMs → 可领/剩余），`GameEngineSectLevelOps.kt:63/:350` 走注入墙钟；`GameViewModel:519` 改调同一纯函数（删内联硬编码）；单测覆盖"UI 与闸门同源" | 1 笔 |
| C4 | 兑换码限流收敛 | `RedeemCodeService` 注入 `WallClock`，一次采样 `nowMs` 线程化下传 `validateCode/checkRateLimit/cleanupExpiredAttempts/markCodeAsUsed/generateReward/getRateLimitStats`；object 内裸钟归零；单测用固定钟覆盖分钟/小时/日/基础冷却 4 层 | 1 笔 |
| C5 | 邮件链收敛 | `MailService`：`var timeSource` → 构造注入 `WallClock`（5 读点）；`MailDao.insertWithEnforceLimit` 增 `now: Long` 形参，`MailRepositoryImpl`（:app）注入 `WallClock` 供时（接口签名不动）；`OverflowMailSender` 4 处、`SecretRealmService` 2 处、`MailDialog` 1 处改形参/注入供时；单测：过期删除判据、偏移 0 行为等价 | 1 笔（过大则拆引擎侧 / Room+UI 侧 2 笔） |
| C6 | 零回流守卫 | 新 `WallClockReflowGuardTest`：游戏语义族文件清单 + `System.currentTimeMillis`/`Calendar.getInstance` 裸读计数（注释剥离、白名单条目数锁死、源不可达即 AssertionError）；范围覆盖 6 模块 `src/main` | 1 笔 |
| C7 | payload HMAC | 新 `SavePayloadSigner`（`core:data/crypto`，`@Singleton @Inject`，`SecureKeyManager` 独立 salt 派生，HMAC-SHA256 + `timingSafeEqual`）；`TapTapSaveBackend` 上传写 `extra.sig`/`sigVer`、下载复用 `queryArchiveInfo` 的 extra 验签；`CloudSavePayload` 增 `integrity` 判据（MISMATCH 降级放行 + 留痕）；单测：确定性、篡改检得、无签 `UNSIGNED`、密钥不可用不阻断上传 | 1 笔 |
| C8 | 云偏移采样接线 | `TapTapSaveBackend.upload` 成功后**至多一次**元数据读回 → `CalibratedWallClock.applyDriftSample`；LEGACY 天然不触发（上传本身非 LEGACY 才发生）；守卫：读回失败/异常不影响上传返回值 | 随 C2 或 C7 独立笔 |
| C9 | 门禁 | 定向测试 + 六模块组合门（XML executed 计数 + 时间戳判绿，基线 SR-4 收官 7,986/0/17）+ `Diff*` 0 skip + ctest（预期 no work to do） | 结果入完成报告 |
| C10 | 真机登记 | 云读回采样的真实表现、签名链路真机字节稳定性（LZ4/反序列化后再签是否逐位一致）、偏移校正对日额/邮件的真机观感 | 入完成报告 pending-device |
| C11 | 收尾 | 完成报告 `report-SR5-completion-2026-09-22.md` + 台账 SR-5 行 delivered（含 P1 命名偏离、P3 施工期修正、pending-device）+ CHANGELOG 4.01.15 段 SR-5 小节 + 方案 §4 SR-5 实施补记 | 1 笔 |

---

## 4. 红线复述（实施中逐条对照）

- **LEGACY / 现有行为零变化**：偏移仅在非 LEGACY 上传成功采样后非 0；采样未发生 ⇒
  `CalibratedWallClock.currentTimeMillis()` 与 `System.currentTimeMillis()` **逐位一致**（守卫测试锚定）；
  `TapCloudSaveManager` 旧云链不签名、不改写（LEGACY 镜像路径保持字节级原样，SR-7 退役）；
- **IN2 仲裁无时钟**：本批新增的一切"时刻"只用于日/周/过期**阈值判定**，不进仲裁；
  存档新旧判定仍唯一走 `SaveArbiter`（脏标志/序号）；云 mtime 采样是**时钟校正**不是"谁新"比较
  ——代码与注释必须写清这条界线，且采样结果不得回流到任何 `SaveArbiter` 入参；
- **IN3 接口隔离**：`WallClock`/`CalibratedWallClock` 纯 JVM；`SavePayloadSigner` 在 `core:data`，
  零 TapTap SDK 类型（`StorageLayerSdkIsolationGuardTest` 自动覆盖）；
- **IN5 尺寸红线**：签名 64 hex + `sigVer` 进 extra 元数据（非载荷），对 ≤10MB 载荷零影响；
- **IN6 机制要有调用者 + 测试**：`integrity` 判据必须有真实消费点（下载侧留痕 + 告警通道），
  不做"算了但没人看"的安全剧场；偏移必须有采样调用方，否则不落地；
- **IN8**：`Diff*` 0 skip；零 C++/wire/schema 变更；
- **诚实登记**：验签强度有限（无服务器验证）、偏移不持久化、玩家大幅改钟不在本批根治——
  写进完成报告与代码注释，不粉饰成安全闭环；
- **不夹带**：不顺手清扫 §2.5 类 instrumentation 裸钟（日志/看门狗/缓存 TTL/RNG 播种），
  `GameRngManager.kt:29` 的确定性缺陷归 RNG 批；`RedeemCodeRateLimitOps.verifySignature`
  的"SHA-256 静态后缀"假签名不在本批改造（另立项，登记）。

---

## 5. 真机硬门清单（本环境不可自动化，逐项 pending-device 登记）

- 上传成功后元数据读回的真实延迟与 mtime 精度（秒级）是否满足 `|drift|` 阈值；
- 签名/验签在真机 LZ4 + 序列化往返后是否逐位稳定（**若不稳定则整批签名判归无效**，须如实登记）；
- 偏移非 0 时玉符日额跨天、邮件过期、周冷却的真实观感（有无误重置）；
- extra 元数据经 TapTap 服务端往返后 `sig` 字段是否原样回带（元数据最终一致性延迟）；
- 历史无签云档（SR-2/SR-3 期间上传）在 `UNSIGNED` 判据下的下载链路是否正常。

---

## 6. 交付物清单

- `android/core/engine/src/main/java/com/xianxia/sect/core/engine/system/WallClock.kt`（新，共享抽象
  + `SystemWallClock` + `CalibratedWallClock` + Hilt 绑定）
- `android/core/engine/.../engine/service/JadeSymbolService.kt`（删本地 WallClock 声明，改 import）
- `android/core/engine/.../engine/GameEngineCore.kt`（墙钟访问器）、`GameEngineSectLevelOps.kt`、
  `SectLevelRewardCooldown`（纯函数，新）
- `android/core/engine/.../engine/RedeemCode{Manager,RateLimitOps,LifecycleOps}.kt`、
  `service/RedeemCodeService.kt`
- `android/core/engine/.../engine/service/{MailService,OverflowMailSender,SecretRealmService}.kt`
  （`OverflowMailSender` 经 `MailRepository` 供时的部分在 :app）
- `android/core/data/.../data/local/MailDao.kt`（`now` 形参）
- `android/app/.../di/MailRepositoryImpl.kt`（注入墙钟）
- `android/feature/game/.../ui/game/GameViewModel.kt`、`dialogs/MailDialog.kt`、
  `taptap/TapTapSaveBackend.kt`（签名/验签 + 采样）
- `android/core/data/.../data/crypto/SavePayloadSigner.kt`（新）
- `android/core/data/.../data/cloud/SaveBackend.kt`（`CloudSavePayload.integrity` + 判据枚举）
- 测试：`WallClockCalibrationTest`、`SectLevelRewardCooldownTest`（或并入既有）、
  `RedeemCodeRateLimitTest`、`MailServiceTest`（供时改注入）、`MailDao` 侧、
  `WallClockReflowGuardTest`（新守卫）、`SavePayloadSignerTest`、`TapTapSaveBackendTest`（补签名例）
- 文档：完成报告 + 台账 SR-5 行 + CHANGELOG + 方案 §4 SR-5 实施补记
