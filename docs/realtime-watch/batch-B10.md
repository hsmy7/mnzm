# 派发件 · B10 文档与规范（实时结算线末批）

> 本文件为派发文本主体；文末《前批交付事实附录》由看护派发时从前批报告提取 6–8 条追加后全文粘贴。

## 0. 工作区与纪律（每批相同）

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-realtime`（git worktree），分支 `feat/realtime-settlement`。开工第一件事：`git branch --show-current`（必须 = feat/realtime-settlement）+ `git status`。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **B10 一批**（实时结算方案 §10 对应行），完成即收官提交；不开下一批。
- 实施前先读：方案 `docs/realtime-settlement-plan-2026-09-27.md`（§3.6 文档与规范清单、§10 B10 行）+ `docs/realtime-watch/DISPATCH-LEDGER.md`（只读；异议写其 §9 留言区）+ 本文件附录。
- 报告：`docs/report-B10.md`，含门禁实测原数字（禁「应该通过」措辞、禁 `{{...}}` 占位符残留），随收官笔入库。
- 收官：单笔提交，格式 `docs(realtime): B10 文档与规范——<要点>`；内容含文档+测试（如涉）+报告+双 changelog（**版本号不自增**）。
- 构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 本批为实时结算线**末批**：收官笔落库后看护启动台账 §7 合并手术（merge --no-ff → 冲突三文件取主线显式启动案 → 全门禁 → push → 删支）。

## 1. 批次任务（方案 §10 B10 行原文）

> | 批 | 内容 | 关键文件 | 验收标准 |
> |---|---|---|---|
> | **B10 文档与规范** | §3.6 全部条目 | `rules/`、`docs/`、双 CHANGELOG | 规范与代码零冲突；门禁绿 |

补充要点（档案）：

- `rules/expansion-playbook.md` 改写（新玩法结算层指引对齐现实时间连续结算）。
- `rules/economy-design.md` §4 定稿（离线收益口径与 §1.4 一致：12h 全额 + 50% 至 24h 硬顶）。
- 双 CHANGELOG 收口（外部 `CHANGELOG.md` + 游戏内 `changelog_entries.json`；版本号不自增，合并后由用户拍板发版号）。
- §3.6 = 文档与规范清单（方案 788 行起）：**规范与代码零冲突**为验收核心；新增/修改 docs 后 `node scripts/check-agent-instructions.mjs` 全绿（引用无死链）。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 worktree 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量，**必须含 feature:game**；**必须带该参数**——IN8 出厂门要求全量跑也带参，防 45 个 Diff*Test 静默 skip，B7 实证不带必红）
3. engine Diff 门（`-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`；若本批未触 C++ 复用既有 .so 即可）
4. `./gradlew.bat lintRelease detekt`
5. 桌面 ctest 全量（基线以附录为准）：llvm-mingw PATH 必带
6. worktree 根：`node scripts/check-jni-count.mjs` + `node scripts/check-agent-instructions.mjs`（如报缺依赖，从主树拷 `scripts/node_modules`）

## 3. 环境教训（必读，前车之鉴）

- ctest 缺 llvm-mingw PATH = 0xc0000135 假红；改 C++ 后忘重跑 `build-desktop-jni.ps1` → Diff 全系假红。
- feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程；daemon 卡死 → 杀本工作区相关 java 重跑。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录

（看护派发时从前批报告提取 6–8 条追加于此）
