# 派发件 · B8 UI 与遥测（实时结算线）

> 本文件为派发文本主体；文末《前批交付事实附录》由看护派发时从前批报告提取 6–8 条追加后全文粘贴。

## 0. 工作区与纪律（每批相同）

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-realtime`（git worktree），分支 `feat/realtime-settlement`。开工第一件事：`git branch --show-current`（必须 = feat/realtime-settlement）+ `git status`（应为净树或仅本批工作面）。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **B8 一批**（实时结算方案 §10 对应行），完成即收官提交；不开下一批、不顺手做其他批。
- 实施前先读：方案 `docs/realtime-settlement-plan-2026-09-27.md` 对应节 + `docs/realtime-watch/DISPATCH-LEDGER.md`（只读；异议写其 §9 留言区）+ 本文件附录。
- 报告：`docs/report-B8.md`，含门禁实测原数字（禁「应该通过」措辞、禁 `{{...}}` 占位符残留），随收官笔入库。
- 收官：单笔提交，格式 `feat(ui): B8 UI 与遥测——<要点>`；内容含代码+测试+报告+双 changelog（`CHANGELOG.md` 与 `android/app/src/main/assets/changelog_entries.json`——玩家向文案通俗、不泄数值细节；**版本号不自增**）。
- 构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 通用红线（AGENTS.md §5）：禁 `!!`/`runBlocking`/空 catch；领域失败用 sealed 结果；跨域新增枚举/配置必写守卫测试；detekt baseline 只缩不增；C++ 优先；UI 读新游戏状态字段先读 `docs/ui-read-surface.md` §2（镜像合法面纪律，新读面必须登记）。

## 1. 批次任务（方案 §10 B8 行原文）

> | 批 | 内容 | 关键文件 | 验收标准 |
> |---|---|---|---|
> | **B8 UI 与遥测** | 旬进度→时间进度、文案、`GameViewStore` 块、bench 门禁 | `ProductionTheme.kt`、`SectInfoCard.kt`、`GameData.kt:831` | 面板读数不再出现「差一点不结算」；积分段 < 1ms@5000 |

补充要点（档案）：

- 旬进度展示改造为**时间进度投影**（INV-1 派生投影口径；游戏日历年/月/旬保留，不删）。
- UI 不驱动系统 tick（§6.5）：进度类数据直接订阅 GameEngine StateFlow 派生（`map + stateIn` 模式）。
- 遥测/埋点新增须按 `rules/data-analytics.md` 三处同步 + 守卫测试。
- bench 门禁：积分段 < 1ms@5000（5000 弟子规模积分段耗时写进 bench 并给门禁断言）。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 worktree 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量，**必须含 feature:game**；**必须带该参数**——IN8 出厂门要求全量跑也带参，防 45 个 Diff*Test 静默 skip，B7 实证不带必红）
3. 若触碰 C++：先 `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`，再跑 engine Diff 门（`-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`）
4. `./gradlew.bat lintRelease detekt`
5. 桌面 ctest 全量（基线以附录为准）：llvm-mingw PATH 必带
6. worktree 根：`node scripts/check-jni-count.mjs` + `node scripts/check-agent-instructions.mjs`（如报缺依赖，从主树拷 `scripts/node_modules`）

## 3. 环境教训（必读，前车之鉴）

- ctest 缺 llvm-mingw PATH = 0xc0000135 假红；改 C++ 后忘重跑 `build-desktop-jni.ps1` → Diff 全系假红。
- feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程；daemon 卡死 → 杀本工作区相关 java 重跑。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 不新增结算循环/新线程 tick；诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（B7，看护填）

1. B7 收官笔 `568c01921 feat(engine): B7 离线语义——12h 全额+50% 至 24h 硬顶折算 + GameCore::injectOfflineGameMs 注入路径 + 云游归来 UI 提示`（29 文件 +1270/−7）；工作树当前净。
2. **门禁基线更新**：ctest **1473**/1473（=1465+8）；jni-count **88/88**（+1 = `nativeInjectOfflineGameMs`，豁免理由在 `jni-count.baseline.json` _doc）；detekt/lint 六模块绿（lint 警告均为存量基线：app 36/data 5/domain 1/ui 1/feature:game 52，本批零新增）；check-agent-instructions 绿。
3. **离线口径已落地（勿改口）**：`GameConfig.Time.offlineGameMs` = ≤12h 全额（1x 与在线速度档解耦）→ 12–24h 段 50%（整数分子制 1/2）→ 24h 硬顶；总量上限 18h 游戏时间；结果 **floor 到旬**（GAME_MS_PER_PHASE=2000 整数倍，INV-1 旬网格对齐前提）。
4. **注入路径两段式**（线程契约表四已登记「离线收益」行）：staging 在 `BootSequenceController.bootCore` **Step 6.5**（`lastSaveTime>0` 分岔，新档零注入）→ consume 在 `ensureAuthoritativeNative` 统一成功出口尾部（消费即清零幂等；native 就绪走 `nativeInjectOfflineGameMs`+镜像同步+`accruedElapsedGameMs` 重锚；不可用回退臂 `GameTimeClock.addOfflineGameMs`）。B8 若动 UI/boot 面注意不要打乱该时序。
5. **C++ 入口语义边界**：`GameCore::injectOfflineGameMs` = L1 连续积分 + 三轴同步跳变（PhaseClock 纳秒轴/SettlementEngine 已积分轴新增 `advanceGameMs` + GameData 旬投影 `projectCalendar` set）+ L3 月度连续积分；**判定轨 0 次/月年离散事件 0 次/RNG 零消耗**；与 `realtimeAccrual` 灰度旗标正交（旗标仍默认 false 未动）。
6. **UI 回归提示已建**：`OfflineReturnReport` 流（GameEngineCore→GameEngine→GameViewModel）→ 主界面 `StandardPromptDialog`「云游归来」（60% 遮罩合规）+ `OfflineReturnFormatter.formatOfflineDuration`；展示后 ack 清空防重复弹出。B8 改面板文案时沿用「不泄数值细节」口径。
7. **预存事实（登记不修，B8 勿顺手改）**：`SettlementEngine.settlement_.elapsedGameMs_` 离散臂生产路径恒 0（离散臂旬推进不经 advanceByGameMs）——注入向其加 X 与离散臂基态有语义差，现无行为影响（报告 §5.4）；若未来离散臂消费该轴须在双臂切换点一并重锚。
8. **真机 pending-device +1**：D8（START_STICKY 后台重建是否推进时间，`GameForegroundService.kt:144,113-121`）；D8 不阻塞任何批次验收。
