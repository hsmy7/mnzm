# 重构方案跨会话派工台账（native-engine-refactor-plan-2026-09-17）

> 本文件是跨会话编排的**唯一权威状态**。定时看护每 10 分钟触发一次，触发轮
> **不依赖任何对话上下文**，只凭本文件 + 方案文件 + 仓库现状决策。
> 方案文件：`docs/native-engine-refactor-plan-2026-09-17.md`（下称"方案"）。
> 仓库根：`C:\Mnzm\XianxiaSectNative`。

---

## 看护运行手册（每轮必读必照做）

1. **读状态**：读本文件"当前状态"一节。
2. **防重入**：任何写操作（验收、派工、改台账状态）前，先把"看护锁"写成本轮时间戳。
   若发现锁已有值且距今 < 8 分钟：本轮**只做截屏 + 监控日志追加**，然后结束。
3. **状态 = in_progress（子会话施工中）**：
   - 用 windows-mcp `Screenshot` 截屏，观察 ZCode 窗口中子会话的进展；
   - 仍在工作（输出在增长/工具调用滚动）→ 监控日志追加一行（时间 + 一句话），结束本轮；
   - 异常（权限确认弹窗 → 点击允许；报错弹窗 → 记录；**连续 3 轮截屏无任何输出变化** → 判定停滞）→ 按第 5 条处置；
   - 子会话已给出**最终完成报告**（输入框空闲、报告含提交号与测试数字）→ 把状态改为 `verifying`，进入第 4 条。
4. **状态 = verifying（验收）**：验收人 = 看护自己，**不信任子会话自述**：
   a. `git log --oneline -15` 核对本批要求的提交全部存在且逐子项独立；
   b. 亲自复跑批次文件"验收门"的全部命令（长命令可用 run_in_background）；
   c. 核对方案 §7 已新增本批登记、CHANGELOG.md 已更新、`git status` 干净（无未提交遗留）；
   d. 全过 → 本批状态改 `accepted`，证据列填提交号 + 测试数字，状态改回 `dispatch`（待派下批）；
      任一不过 → 状态改 `fix_needed`，缺陷清单写进"当前状态"。
5. **状态 = dispatch 或 fix_needed（派工）**：
   - `dispatch`：依据方案 §3 对应条目 + "经验教训"，写下一批批次文件 `docs/parallel-batches-w5/batch-XXX.md`
     （模板照 `batch-R1A.md`：任务 / 红线 / 验收门 / 完成报告格式；范围严格限本批）；
   - `fix_needed`：批次文件沿用原批 + 顶部追加缺陷清单，指令改为仅修复所列缺陷；
   - **GUI 开新会话**：截屏确认桌面 → 聚焦 ZCode 窗口 → 点侧栏"新建任务"（或聚焦后 Ctrl+N）→
     截屏确认进入新会话输入页、右下模型为 **GLM-5.3-Flash**（不是则点模型选择器改选）→
     点输入框 → 输入短指令（内容见下）→ 回车 → 截屏确认子会话已开跑；
   - 新会话指令模板：`读取 docs/parallel-batches-w5/batch-XXX.md，严格按该文件实施批次 XXX。完成后按文件内验收门自检，并按完成报告格式给出报告。`
   - 台账"当前状态"更新：状态 `in_progress`、当前批、派发时间；提交台账变更。
6. **终局**：批次总表全部 `accepted` → 状态改 `completed`，在台账写收官总结
   （各批提交号、G1–G7 目标达成核对、范围外项：R5 为 iOS 条件项、R4.1 删臂待灰度一个版本周期），
   然后 `CronList` 找到本定时任务并 `CronDelete` 删除，向用户报告收官。

---

## 批次总表（顺序即依赖序，依据方案 §3/§4）

| 批 | 内容 | 方案条目 | 状态 | 证据（提交/测试） |
|---|---|---|---|---|
| B01 | map 重建提升到步骤入口 + getMaxHpMp 临时 map 消除 | R1.1 + R1.5 | dispatching | — |
| B02 | committedDisciples 去物化 + 字符串键 → dense 索引 | R1.2 + R1.3 | pending | — |
| B03 | DirtyTracker 列级写屏障 + destroyDiscipleEntities 去 O(D²) + R1 收官 bench（证明 G1） | R1.4 + R1.6 | pending | — |
| B04 | 秘境战斗切 native（会话/邮件/暂停租约留 Kotlin） | R4.2 | pending | — |
| B05 | 探索/巡逻生产结果下沉 | R4.3 | pending | — |
| B06 | GameView proto 定义 + nativeExportDirty 信封 + StateSyncService 解码 | R2.1 + R2.2 | pending | — |
| B07 | UI 消费面第一波：只换传输（镜像仍全量、二进制） | R2.3(一) | pending | — |
| B08 | 第二波：replaceAll 退役 + GameViewStore 投影态 + ViewModel 逐块迁移 | R2.3(二) | pending | — |
| B09 | 月/年信封并入 eventFeed + 残留执行器退化为平台效应适配器；G2/WS-1 验收 | R2.4 | pending | — |
| B10 | C++ SceneStore 新模块 + JNI 面重构（drawAllTiles 17 参数退役） | R3.1 + R3.2 | pending | — |
| B11 | overlay 几何 C++ 生成（消 258 drawRect）+ 脏更新协议 | R3.3 + R3.4 | pending | — |
| B12 | 远景观看容量路径（带黑名单验证）+ GLES 后端同构改造（Canvas 兜底不动） | R3.5 + R3.6 | pending | — |
| B13 | 原生浮层与文本通道 Tier1（浮字池/事件驱动 spawn）；G3/G4 + 截图回归验收 | R3.8 | pending | — |
| B14 | RNG 分区独立（消 per-roll JNI，对拍重定基线，存档版本说明） | R4.4 | pending | — |
| B15 | 图集离线化（build-atlas.mjs 直出 KTX/ASTC，消运行时 Canvas 拼图） | R6.1 | pending | — |
| B16 | 数值外置（C++ 头文件 DB → 数据文件加载，nativeSetGameConfig 扩展） | R6.2 | pending | — |
| B17 | CI 与度量执法：bench 门禁 + JNI 面计数静态门禁 + 平台纯度 gate；方案收官核对 | §CI | pending | — |

**范围外（不派工）**：R5 循环归属 C++（iOS 立项才启动）；R4.1 删臂 BattleSystem 转 golden
夹具（按 R4 统一流程，需灰度一个版本周期，产品节奏决定，非工程可单方面完成）。

---

## 当前状态

- **看护锁**：—
- **状态**：`dispatching`（B01 批次文件已就绪，待 GUI 派发）
- **当前批**：B01
- **派发时间**：—
- **缺陷清单**：—
- **监控日志**：
  - 2026-09-17 22:5x 编排建立：台账 + B01 批次文件就绪，等待首次 GUI 派发。

## 经验教训（随批追加）

- （待首批派发后积累）
