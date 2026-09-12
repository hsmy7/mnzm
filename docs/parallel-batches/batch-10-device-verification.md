# Batch-10：真机验证批（唯一剩余大类——P0-3 / RNG 收口 / S1-S8 AUTHORITATIVE / WS-5 视觉）

| 项 | 内容 |
|---|---|
| 批次 | 10 ｜ 并行组 D（**与全部代码批正交**：不碰生产代码，唯一例外是收尾的 RNG 断言升级小改） |
| 依赖 | 需真机（debug + release 双构建；建议含 vivo OriginOS 设备——P1-4 勘误事故的原始复现机） |
| 性质 | QA/验证批：桌面与 CI 已绿的批次在真机环境（真实 JNI/Vulkan/热状态/前台服务生命周期）的最终覆盖 |
| 来源 | handover §4.1 各"真机验证"登记项 + §5「真机验证批（唯一剩余大类）」 |
| 预分配 | handover §2.39 |

## 1. 验证项全集（按主题分组，逐项给出通过标准）

### A. P0-3 图集异步管线（handover §2.5 / §4.1）

| 项 | 通过标准 |
|---|---|
| 异步拼装 + direct 上传链路 | 进入宗门地图：图集就绪前纯黑、就绪后淡入（无"空白窗口"）；低端机无 OOM/ANR |
| Vulkan RGBA 回退路径 | ASTC 不可用设备（或强制 `allowCompressed=false`）：地图正常渲染 |
| 软渲染路径 | GLES/Vulkan 均不可用降级软渲：地图可玩、无花屏 |
| surface 旋转/销毁重建 | 旋屏/切后台：拼装中断不崩溃、重建后图集正常（纪元守卫丢弃旧结果） |

### B. RNG 警告日志观察 → P1-4 正式收口（handover §2.6「护栏升级条件」）

1. debug 构建真机跑 ≥1 小时混合操作（存档/读档/重启/战斗/秘境/血炼——覆盖曾的主线程
   调用面 HeavenlyTrial/BloodRefining/SaveLoad）+ 后台前台循环重启。
2. 过滤 logcat `RNG 通道跨线程进入`（`jniWarnRngOffEngineThread`）：**零出现** →
   执行升级：`nativeRngNextInt / nativeRngSnapshotPartition / nativeRngRestorePartition /
   nativeRngInitSeed` 四入口由 WARN 守卫改 `jniRequireEngineThread` 断言，分类
   kEngineOnly，`GameCoreBridge.kt` 契约注释同步——**P1-4 就此正式收口**（本批唯一
   代码改动，`GameCoreBridge.cpp/.kt` 本批所有权文件）。
3. 若仍出现 → 存在漏网调用面：登记日志（入口名/owner tid/当前 tid + 复现路径），
   该处局部加锁方案另批，**不升级断言**。
4. 升级后复跑步骤 1 确认无误杀（尤其 `loopStart` 主线程合法进入路径——§2.7 勘误
   教训：生命周期输入端口不得守卫）。
5. `buildSaveSnapshot` 引擎线程采样真机回归：存档大小/耗时正常、读回一致。

### C. S1-S8 AUTHORITATIVE 真机回归（handover §4.1 各登记项）

| 系统 | 重点 |
|---|---|
| S1-S3 每旬七步结算 | 亲属赠送/道德减益丹偷盗钩子实际触发；**埋点观察器**（BREAKTHROUGH_SUCCESS + FTUE 首次突破）正常上报 |
| S4 月结生产 | 到期槽结算产出/职业晋升/Room 写回（restoreSlots 整表重放）/手动启动后月结视图（B5 分叉自愈） |
| S5 任务完成 | 战斗任务结算/奖励入库/幸存者魂力 |
| S6 秘境交互会话 | 出发（换岗/gate/Room 清槽）/事件选择（妖兽战报播放/休整/遗迹/方向/AI 遭遇 PvP）/断线续玩/体力耗尽与全灭自动结束/手动结束结算；**战报重建（recordPlayerBattle）与死亡袋物化溢出邮件**为真机重点 |
| S7 生产排程 | 手动排班/重置 C++ 事务 + Room 后置写回 |
| S8 洞天 AI/兽战 | AI 修炼演化（含热档 12/6/3 切换——ThermalMonitor 真实热状态路径）/兽战遭遇/宗门升级补全 |
| 月结残留三项 | 4g 邮件 / S-17 秘境关闭 / S-20 购买日志平台效应正常（扇出 ≤3 项口径不回退） |

### D. WS-5 地图渲染真机视觉（handover §2.19）

| 项 | 通过标准 |
|---|---|
| 地形 C++ 生成 | 多种子进出地图视觉与迁移前一致（DiffSectTerrainTest 已锁位级；真机看渲染链） |
| 建筑占位增量 | 放置/搬迁/拆除建筑：占位即时正确、无残影（applyBuildingOccupancy） |
| 道路增量装配 | 铺路/拆路：RoadMaskTracker 增量与全量视觉一致、拼接处 autotile 正确 |
| chunk 参数化 | 现生产 128²/48px 视觉零差异；（可选）96² 配置冒烟验证扩容路径 |
| 大规模图性能 | 滑动/缩放帧率与迁移前同档（chunk 分帧预算生效） |

### E. 稳定性横切

长跑（≥2 小时或 ≥100 游戏月）：无 ANR/内存泄漏趋势/前台服务被杀后恢复正确
（loopOnRestart 重锚 owner 路径——§2.7）；云存档上传下载真机链路顺带覆盖。
**WS-1 性能绝对值顺带观测**（handover §4.1 WS-1 残留③）：真机 2x 速每旬非
nativeLoopFrame 耗时与反向信封体积抽测（bench 仅桌面 llvm-mingw -O3 环境，改进比率
同量级、绝对值以真机为准）——只记录不设门。

## 2. 执行建议

1. 设备矩阵：arm64-v8a 主力机 + 一台 ASTC 不支持/GLES-only 低端机 + vivo OriginOS
   （B 组原始复现机）。
2. 构建：B 组用 debug（守卫激活）；A/C/D/E 主体验证可用 release（守卫擦除零开销）。
3. 每项记录：设备/构建/步骤/结果/截图或 logcat 证据——全部进 §2.39 验证表。
4. 发现缺陷：登记（复现路径 + 归属批次/模块），缺陷修复**不混入本批**（除 B 组
   断言升级）——另开修复小批，避免验证批变成开发批。
5. C 组可分组多设备并行执行（每人认领 2-3 个系统）。

## 3. 验收

1. §1 表逐项有结论（通过/缺陷登记）；RNG 日志结论 + 是否升级断言明确落档。
2. 若执行断言升级：`externalNativeBuildRelease` + 引擎全量对拍 + detekt 全绿
   （§4 模板①③⑤）。
3. handover §2.39 + §4.1 勾销全部真机登记项（P0-3/RNG/S1-S8/S6/WS-5）+ CHANGELOG；
   「M0/M1 收尾项 P0-3 + RNG 收敛 + S1-S3 + S4 真机验证」就此关闭（§5 末段）。

## 4. 本批触碰文件声明

- 唯一生产代码（B 组断言升级）：`GameCoreBridge.cpp` / `GameCoreBridge.kt`
  （本批所有权文件，其他批禁改——README §3.2）。
- 验证记录文档（§2.39 附件或独立验证报告）。
