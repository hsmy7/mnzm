# 派发件 · B7 离线语义（实时结算线）

> 本文件为完整派发文本，原样实施即可；文末《前批交付事实附录》由看护维护。

## 0. 工作区与纪律（每批相同）

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-realtime`（git worktree），分支 `feat/realtime-settlement`。开工第一件事：`git branch --show-current`（必须 = feat/realtime-settlement）+ `git status`（应为净树或仅本批工作面）。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **B7 一批**（实时结算方案 §10 对应行），完成即收官提交；不开下一批、不顺手做其他批。
- 实施前先读：方案 `docs/realtime-settlement-plan-2026-09-27.md` 对应节（§1.4 离线口径、§3.4.4、§10 B7 行）+ `docs/realtime-watch/DISPATCH-LEDGER.md`（只读；异议/需拍板事项写其 §9 留言区）+ 本文件附录。
- 报告：`docs/report-B7.md`，含门禁实测原数字（禁「应该通过」措辞、禁 `{{...}}` 占位符残留——G10 曾有未填占位符被判 needs-fix 先例），随收官笔入库。
- 收官：单笔提交，格式 `feat(engine): B7 离线语义——<要点>`；内容含代码+测试+报告+双 changelog（`CHANGELOG.md` 与 `android/app/src/main/assets/changelog_entries.json`——玩家向文案通俗、不泄数值细节；**版本号不自增**）。
- 构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 等 codegen 产物，仅 CRLF/LF 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 通用红线（AGENTS.md §5）：禁 `!!`/`runBlocking`/空 catch；领域失败用 sealed 结果；跨域新增枚举/配置必写守卫测试；detekt baseline 只缩不增；`CancellationException` 先抛；C++ 优先；RNG 新增逻辑走确定性 RNG 分区；如涉 Migration 先读 `rules/database-migration.md`。

## 1. 批次任务（方案 §10 B7 行原文）

> | 批 | 内容 | 关键文件 | 验收标准 |
> |---|---|---|---|
> | **B7 离线语义** | 上限/速率/注入路径 + UI 回归提示（口径已定：12h 全额 + 50% 至 24h 硬顶，§1.4） | `GameTimeClock.kt`、`GameEngineCore*Ops`、UI 层 | 离线边界 7 档断言绿；经济总量对拍在容差内 |

补充要点（档案）：

- 注入端口 `GameTimeClock.addOfflineGameMs`；离线上限/速率口径 = **12 小时内全额 + 之后 50% 速率至 24 小时硬顶**（§1.4 已定，不再改口）。
- 离线边界 7 档断言写测试锁死（0 / 短时段 / 12h 整 / 12–24h 线性段 / 24h 硬顶 / 超 24h / 边界毫秒级 ±1）。
- 真机项**不阻塞本批验收**，报告登记 pending-device 即可：START_STICKY 后台重建是否推进时间（方案未确认项 D8）。
- UI 回归提示：离线回归面板文案口径同步（给玩家看，通俗、不泄数值）。
- 边界决定：连续收割（收获判定挪出月界）与年俸连续化**无批次承载、方案未采纳**——勿顺手实施。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 worktree 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1`（六模块全量，**必须含 feature:game**——B 批门禁教训）
3. 若触碰 C++：先在 worktree 根 `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`，再跑 engine Diff 门：
   `./gradlew.bat :core:engine:testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`
   （DiffAuthoritativeTickTest 等**必须带该参数**，否则假红；未触 C++ 也要跑第 2 条全量即可）
4. `./gradlew.bat lintRelease detekt`
5. 桌面 ctest 全量（基线 1465/1465）：llvm-mingw PATH 必带——`C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` + `C:/Users/cp050/AppData/Local/Android/Sdk/cmake/3.22.1/bin`（直接跑 game-core-tests.exe 也带，UCRT DLL 在其 bin）
6. worktree 根：`node scripts/check-jni-count.mjs`（基线 87/87；新增 JNI 必须同步 `jni-count.baseline.json` 并在报告记豁免理由）
7. worktree 根：`node scripts/check-agent-instructions.mjs`（如报缺依赖，从主树拷 `scripts/node_modules`，gitignored 不入库）

## 3. 环境教训（必读，前车之鉴）

- ctest 缺 llvm-mingw PATH = 0xc0000135 假红。
- 改 C++（如 `time_system.h`）后忘重跑 `build-desktop-jni.ps1` → Diff 全系假红（B5 实证）。
- feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程（PowerShell CommandLine 匹配工作区名甄别）；daemon 卡死（output.bin 1h+ 不动、worker 消失）→ 杀本工作区相关 java 重跑。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 不新增结算循环/新线程 tick；新逻辑落既有惰性结算四层（L0–L4）。
- 诚实纪律：门禁失败须归因入报告；不得为绿而改断言迁就。

## 4. 前批交付事实附录（B6，看护填）

1. B6 收官笔 `d01fdfb91 feat(engine): B6 L3+L4 边界层拆分——月结积分型四项析出连续轨 + runMonthEvents 判定入口 + 臂甄别接线`；工作树当前净。
2. 门禁基线：ctest **1465**/1465 全绿；六模块 JVM 全量（含 feature:game）；detekt 六模块 0/0；lintRelease 绿；jni-count **87/87**；check-agent-instructions 绿。
3. 连续轨臂甄别现态：settleOnePhase 清 / accrue 置 `accrualMode`；灰度旗标 `NativeEngineFlag.realtimeAccrual` **仍默认 false**——任何时刻可回退旧行为；B7 行为验证注意双臂对拍口径（§5.2）。
4. 月结四项已走整数分子制进位（分子 += 月费×Δms 整除 6000、扣款余数保留，零浮点）；灵矿毫秒差分字段 `spiritMineLastSettledGameMs` 已激活；离散臂 settleMonth 后 ms 双写保双臂切换基准新鲜。
5. 口径主轴 = **y*12+m**（toAbsoluteMonth 实为 y*12+m，方案原文方向已纠）；**自比较域内口径（lastSettled*/冷却）不许再动**（破坏旧档差分）。
6. `DiffAuthoritativeTickTest` 排除面已登记 `spiritMineLastSettledGameMs`；`MonthAccrualTest` 10 例在库。
7. `runMonthEvents` = runMonthSettlement 去四项（两臂月判定逐位一致）；政策成本表已提炼 `PolicyCostEntry`（表序=tryDeduct 原序）。
8. 边界决定：连续收割与年俸连续化**未采纳、无批次承载**——勿顺手实施。
