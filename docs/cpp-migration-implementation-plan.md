# C++ 迁移总实施方案

| 项 | 内容 |
|---|---|
| 文档性质 | 迁移整改总实施方案（依据 2026-09-04 独立审计结论 + 8 项产品/架构决策） |
| 依据文档 | [cpp-migration-audit-report.md](cpp-migration-audit-report.md)（下称《审计报告》） |
| 执行约束 | 分阶段交付，每阶段有独立验收标准；每批下沉必须过桌面对拍后方可删 Kotlin 路径；ECS 改造前置保序验证 |
| 状态 | 待批准执行（本文档本身不改任何代码） |

---

## 1. 决策记录（2026-09-04，最终版）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 双真相源终态 | **选 A**：C++ 收敛为唯一模拟真相源。残留执行器逐批下沉 C++，Kotlin 只剩 UI 镜像读取 + 持久化。终态每旬只走两步（nativeSettlePhase + 前向 diff），反向同步通道整个删除。先做通道降本再逐批下沉，不一步到位 |
| 2 | 自动存档 | 纯手动存档为**既定产品决策**（历史决策见 docs/report-移除自动存档-接入云存档.md，changelog_entries.json:813）。本次任务：**残留彻底清理**，杜绝再次被误判为功能缺失 |
| 3 | ECS | **游戏要全面使用 ECS**。ECS 成为实体层运行时载体：弟子/NPC/建筑皆为实体，所有系统皆注册为 World 中的 System（方案取舍见 WS-3） |
| 4 | iOS | **暂缓**。删除文档中"iOS 可复用/双端"表述，目标降级为"核心可移植"；保留 gamecore 零 Android 依赖的 CI 护栏 |
| 5 | nativeExecute 未接线域 | **与决策 1 联动**：随下沉批次逐域接线，不单独接线 |
| 6 | NPC 移动 | **游戏将新增 NPC 地图移动**。它是决策 1+3 的第一个真实负载："C++ 拥有状态、ECS 承载实体、C++ 直接供渲染"的试点 |
| 7 | 包体/渠道 | **两项均删除**：① 移除 armeabi-v7a（仅保留 arm64-v8a）；② 移除无效的纹理分包开关 |
| 8 | 过程债务 | **债务必须实际解决**：detekt baseline 3075 行压制逐批清偿；零 TODO 政策修订为受控 marker；Diff 对拍 CI 强制化 |

---

## 2. 总体路线：三个决策咬合成一个主轴

> **ECS 成为实体层的运行时载体（WS-3）→ 弟子/NPC/建筑成为 ECS 实体 → NPC 移动是 ECS 的第一个真实负载（WS-4）→ 实体状态天然存 C++ → 反向同步通道可以按域关闭（WS-2）→ 决策 1 完成。**

NPC 移动是"每帧数据"的天然试点——它本就不该走每旬 JSON 通道，正好验证"C++ 拥有状态、C++ 直接供渲染"的终态架构。

```
M0 止血清残 ──► M1 减税+试点 ──► M2 主轴成型 ──► M3 收敛
 WS-0/6/7(部分)   WS-1 + WS-2(S1-S3) + WS-3(E1-E2)   WS-2(S4-S8) + WS-4 + WS-5   WS-2(收尾) + WS-7(收尾)
```

---

## 3. 工作流详单

### WS-0 止血与清残（1-2 周，全部可自动修复）

#### WS-0.a 决策 2：自动存档残留彻底清理

| # | 残留项 | 位置 | 处置 |
|---|---|---|---|
| 1 | 退出确认弹窗文案"游戏进度会自动保存"（与事实相反，且出现在退出瞬间，是导致审计误判的直接原因） | feature/game/.../tabs/SettingsTab.kt:356 | 改为如实文案，推荐："确定要退出游戏吗？未保存的进度将会丢失。" |
| 2 | `autoSaveIntervalSeconds: 60` / `autoSaveDebounceMs: 30000`（零消费者的配置键） | app/src/main/assets/config/game_config.json:6-7 | 直接删除两行 |
| 3 | `autoSaveIntervalMonths` Room 列（**两张表都有**：game_data、sect_policy_state） | core/data Room schema；GameDatabase | Room 手动迁移：建新表（无该列）→ COPY → DROP 旧表 → RENAME（API24 的 SQLite 3.9 不支持 DROP COLUMN，不能偷懒）。迁移号顺延，现有用户存档必须无损升级 |
| 4 | `autoSaveIntervalMonths` 在旧档兼容 DTO 的 proto 字段 139 | core/data/.../backwardcompat/OldSerializableSaveData.kt:126 | **保留字段用于旧档解析兼容**（parse-and-ignore，不映射新模型）——旧档能读，新档不再写 |
| 5 | `flushDirtyState`（GameStateRepository.kt:122）与 `consumeDirty`（GameStateStoreImpl.kt:134、GameStateRepository.kt:134-139）：零调用死代码 | core/data、app | 先清查 `markDirty` 完整读写链（markDirtyFor → repository.markDirty，GameStateStoreImpl.kt:1359-1360）：若 dirty 标志确为 write-only 则**整链删除**；若有隐藏读者（如设置页"未保存更改"提示）则保留该读者并记录 |
| 6 | GameTimeClock.kt:97 注释"**自动保存**已累积的游戏时间" | core/engine/.../GameTimeClock.kt:97 | 指内存时钟累积而非磁盘存档，非残留；建议措辞改"保留"以免未来 grep 误命中（可选） |
| 7 | 防复发护栏 | docs/architecture.md / CODE_WIKI.md | 增加一行权威记录："存档为纯手动（产品决策，2026-09-04 确认，历史决策见 docs/report-移除自动存档-接入云存档.md）。禁止重新实现自动保存、禁止引用 autoSave* 命名。" |

验收：全仓生产代码 grep `autoSave|自动保存` 仅命中历史 changelog 与防复发说明；Room 迁移测试覆盖"旧 schema 档案无损升级"；退出弹窗文案如实。

#### WS-0.b 死代码与死导出清除

| 项 | 位置 | 处置 |
|---|---|---|
| `NativeBridge.isRendererReady`（Kotlin 声明、C++ 无导出，调用即崩） | NativeBridge.kt:215 | 删除声明 |
| `nativePollEvents` + `pollEventsJson` 恒空桩 | GameCoreBridge.kt:237；GameCoreBridge.cpp:349；game_core.cpp:419-422 | 双侧删除 |
| `nativeAdvance`（生产死导出，仅基准测试使用） | GameCoreBridge.kt:65 / cpp:211 | 删除 Android 导出；基准测试改走桌面 DiffRngBridge 通道 |
| Kotlin `TimeSystem.onPhaseTick` 生产死代码 | core/engine/.../system/TimeSystem.kt:33 | 删除（Diff 测试如依赖则随批迁移到 C++ 路径断言） |
| 注释纠偏："46 个 ActionId"、"事件队列 批次1"、模拟器必走软渲、gamecore"禁异常/RTTI"、game_core.h:151 | GameEngineNativeOps.kt:15；game_core.cpp:419；NativeSurfaceView.kt:35；gamecore/CMakeLists.txt:7；game_core.h:151 | 逐条改为与事实一致；`GameLoopDelegate` 重命名为 WatchdogDelegate（可选，改名涉及引用面，P3） |

#### WS-0.c 决策 7：包体两项删除

| 项 | 位置 | 处置 | 影响 |
|---|---|---|---|
| 移除 armeabi-v7a | app/build.gradle:60-62：`abiFilters 'armeabi-v7a', 'arm64-v8a'` → `abiFilters 'arm64-v8a'`；重写 :60 注释（删除 libhoudini 表述） | 全仓 grep `armeabi` 确认无其他引用（baselineprofile/脚本/CI） | APK native 包体约 -40%；2018 前纯 32 位设备不再支持（决策已接受）；模拟器需用 arm64 镜像或 API 30+ ARM 转译镜像（Robolectric 纯 JVM 不受影响） |
| 移除纹理分包开关 | app/build.gradle:163-167：删除 `bundle { texture { enableSplit = true } }` | 无 | 纯清理：只有一种 KTX 格式，split 从未生效 |

（`extractNativeLibs=true` 与 `useLegacyPackaging=true` 为独立权衡项，本次不动。）

#### WS-0.d 渲染/CI/性能止血

| 项 | 内容 |
|---|---|
| 提交未提交修复 | VulkanBackend.cpp 清屏色改纯黑（配合已提交的 451f669c） |
| P0-3 修复 | 图集拼装移出主线程（surface 就绪前后台完成）；RGBA 路径统一 2048 封顶；`toRgbaByteArray` 改 DirectByteBuffer 免 ByteArray 中转 |
| P1-4 修复 | JNI 桥层 debug 线程断言（owner 线程记录，非法线程进入即 abort） |
| P1-7 修复 | release `debugSymbolLevel "SYMBOL_TABLE"` + native 符号归档/上传任务 |
| CI 加固 | ① 桌面 libgamecorejni.so 进 CI 构建，45 个 Diff 对拍测试 **fail 而非 skip**；② astcenc 缺失时 `generateAstcAtlas` fail 而非静默跳过；③ detekt baseline 只许缩小不许增大（决策 8 守卫，先上） |

### WS-1 同步通道降本（决策 1 过渡期减税，第 3-6 周）

1. 去掉"每次库存操作→全状态导出+全表替换"（GameEngineNativeOps.kt:81），改字段级回读。
2. 反向信封 gameData 从"引用变了整段重发"改为基于变更字段的 dirty 集（`markDirty` 基建已存在，改为记录字段名；依赖 WS-0.a-5 的 dirty 链清查结论）。
3. C++ DirtyTracker 从"基线全树深比较"改为写屏障/版本号标记（dirty_tracker.h 注释自述的既定待办）。
4. **镜像契约收敛**：审计 UI 实际读取的字段，产出《UI 读取面清单》——它是未来 Kotlin 镜像的合法内容上限，也是反向通道分域关闭的依据。

验收：2x 速下引擎线程每旬非 nativeLoopFrame 耗时 <2ms；反向通道 gameData 段体积下降 ≥90%。

### WS-2 残留执行器下沉 + ActionId 接线（决策 1 主体 + 决策 5，第 3-14 周贯穿）

每批固定流水线：**C++ 实现/启用 → ActionId 接线 → 桌面对拍验证（现有 Diff 基建）→ 删 Kotlin 路径与回退 → CI 绿**。

| 批次 | 内容 | 说明 |
|---|---|---|
| S1 | 突破（breakthrough.h 已存在） | 接线上限最低 |
| S2 | 丹药（pill_system.h 已存在） | 同上 |
| S3 | 自动装备（auto_gear.h 已存在） | 同上；完成即删除 Kotlin PhaseSettlementExecutor 对应三段 |
| S4 | 月结残留第一组：炼丹/锻造完成结算 | 需新写 C++ 系统；接 wallet/内政相关 ActionId |
| S5 | 邮件/任务结算 | 需新写 C++ 系统 |
| S6 | 秘境探索模拟 | SecretRealmService 1278 行，最大单体 |
| S7 | 生产排程（ProductionProcessor 1862 行） | 下沉时顺手修 O(slots×N)（P1-5 部分） |
| S8 | AI 宗门剩余（决策/兽战已半接） | AISectAttackManager 收尾；顺修 O(sects²)（P1-5 部分） |

决策 1 的含义是**无双实现**：每批完成后该域 Kotlin 回退实现删除；native 失败 = 跳旬 refund + 看门狗重启（契约已存在）。月结残留扇出（MonthSettlementResidualExecutor）随批次缩小，S4-S8 完成后应仅剩 UI 通知类残留。

### WS-3 ECS 运行时化（决策 3 主体，第 3-10 周，与 WS-2 并行）

**方案取舍（决策内的设计决策，需评审确认）**：

- **方案 X（推荐）**：DiscipleStore 保留为存储后端，ECS 成为**系统调度与实体关系层**——`DiscipleRef{row}` 组件（disciple_component.h 已存在）做桥，各结算系统注册为真正的 `ecs::System` 经 View 迭代（现状系统签名带 `World&` 却弃用，改为真用）。NPC/建筑为新实体，用纯 ECS 组件（Position/SpriteId/PathState/Occupancy 等）。
- 方案 Y（不推荐）：弟子数据整体迁入 ECS 组件存储、废弃 DiscipleStore——更"纯粹"，但破坏确定性对拍/脏追踪/存档三套基建，工作量与风险大一个量级。

**关键前置：迭代序确定性红线。** 稀疏集 dense 迭代序 ≠ DiscipleStore 行序（行序=RNG 确定性红线，disciple_store.h:22-27）。E1 必须先做保序验证：View 迭代映射回行序（或显式迁移基线），对拍全绿后才允许后续系统迁移。

| 阶段 | 内容 |
|---|---|
| E1（第 3-4 周） | 定实体模型与桥接规范；`PhaseCoreBatchSystem` 真用 World/View + 保序验证 |
| E2（第 5-10 周） | 逐系统迁移为 View 迭代：修炼/HP/MP → 生产 → 突破/丹药/装备（节奏与 WS-2 S1-S7 对齐，每系统迁移即其对拍批次） |
| E3（第 8-9 周） | NPC 实体组件族落地（Position/Velocity/PathIndex/SpriteId/AnimState），为 WS-4 供数 |

### WS-4 NPC 移动系统（决策 6，第 8-14 周，依赖 WS-3 E1/E3 + WS-5）

1. **模拟在 C++、按逻辑 tick 推进**：NPC 实体每旬推进路点（移动系统为 ecs::System）。
2. **渲染插值用现成机制**：EngineLoop 已产出 alpha 插值因子（本为此设计），渲染线程按 alpha 在两路点间插值。
3. **过渲染通道不走 JSON**：每旬一次紧凑原始类型数组（id,x,y,spriteId,animFrame）进 Kotlin 渲染状态（几百 NPC 量级，可忽略），渲染线程插值后经扩展的 drawAllTiles 实体层提交 native。不打通两个 .so 的共享内存（架构改动不值）。
4. **寻路**：128² 可行走网格在 C++ 建立（静态地形 + 建筑占位，占位镜像已存在 models.h GridBuildingData）；A* 按需 + 路径缓存；建筑放置/拆除增量更新网格。
5. **交互**：Kotlin 渲染线程持插值坐标做点击命中 → 走现有 UI 流程。
6. **渲染层扩展**：RenderFrame/NativeBridge 增加实体层（现契约无实体层，明确接口扩展点）；行走帧动画 = SpriteBatcher UV 按帧切换（现渲染器无帧动画，属新增小能力）。
7. **美术依赖（最大外部风险）**：图集无任何人物精灵。MVP 用 scripts/build-atlas.mjs 现有管线程序化生成占位小人，正式美术后到后替换。**需要美术排期立项**。
8. NPC 数量上限、生成/消亡规则、与弟子系统（招募/派遣）的关系 = **需要一份小型玩法设计文档**后再进入实现（本方案只锁定技术架构）。

### WS-5 地图数据模型改造（决策 6 前置，第 6-8 周）

1. 占位（occupancy）真相源迁 C++（NPC 寻路需要）；Kotlin 保留 UI 即时校验缓存（拖拽中每次校验为 O(脚印) Set 查询，成本可忽略）。
2. 修三个 O(全图) 点：flatTileData 展平改"每种子建一次 + 建筑变动局部更新"；roadData 同理；Canvas chunk 网格 128 硬编码参数化。
3. 完成后地图尺寸可配置，NPC 系统有干净地基。

### WS-6 iOS 暂缓落地（决策 4，半天）

1. 清理 docs/注释中"iOS 可复用/双端"表述（clock.h:12、road_system.h:23、gamecore/CMakeLists.txt:6、cpp/CMakeLists.txt:59、docs/cpp-engine.md 相关段），改为"Android 为主，核心保持可移植"。
2. 保留护栏：gamecore 禁止引入 Android 依赖的桌面 CI 编译检查（现成桌面构建即是）。
3. RHI 不投入；唯一要求：新增渲染功能不得把 Android API 进一步漏进 Rhi.h。

### WS-7 过程债务清偿（决策 8，贯穿全年节奏）

1. **detekt baseline 3075 行分诊**：逐条分类 = 立即修 / 修代码消灭 / 确认合理压制（附理由）。按文件分批（baseline 共 6 个文件），每迭代至少烧一个文件；CI 守卫"只许缩小"（WS-0.d 先行上线）。
2. **零 TODO 政策修订**：允许在"已登记活跃债务"处使用受控 marker（统一格式 `// DEBT(<登记号>):`），使工具可扫描/计数/清零；CLAUDE.md:603 政策相应修订。债务不可见才是最大债务。
3. **死代码清零滚动清单**：以《审计报告》§5 为起点，WS-0 清一批、WS-2 每批删一批 Kotlin 回退；清单维护于本文件的附录（执行时建立）。
4. **数据表双轨防漂移**：加 CI 校验——scripts/gen-templates.mjs 生成的 C++ 静态表与 assets JSON 同源一致性断言。
5. **注释真值化**：WS-0.b 未覆盖的注释失实项随批纠正；对拍基建与实现不一致处（如 game_core.h:151 类）建立"改码必改注释"评审项。

---

## 4. 里程碑与验收

| 里程碑 | 时间 | 内容 | 验收标准 |
|---|---|---|---|
| M0 止血清残 | 第 1-2 周 | WS-0 全部 + WS-6 | 死导出清零；自动存档残留全项清理（§WS-0.a 验收）；对拍 CI 强制（缺 so 即红）；图集不碰主线程；arm64 单架构出包；文案与实现一致；baseline 守卫上线 |
| M1 减税+试点 | 第 3-6 周 | WS-1 + WS-2(S1-S3) + WS-3(E1) | 库存全量导出消失；反向通道 gameData 段体积 -90%；突破/丹药/自动装备三批下沉且对拍全绿；E1 保序验证通过；detekt 至少 1 个 baseline 文件清零 |
| M2 主轴成型 | 第 7-14 周 | WS-2(S4-S8) + WS-3(E2-E3) + WS-4 + WS-5 | 月结残留扇出 ≤3 项（仅 UI 通知类）；NPC 在地图行走（占位精灵）+ 点击可交互；占位真相源在 C++；月结 O(N²) 修复 |
| M3 收敛 | 第 15 周起 | WS-2 收尾 + WS-7 收尾 | 反向同步通道按域全关（终态：每旬 nativeSettlePhase + 前向 diff 两步）；detekt baseline 清零；全仓无双实现并行活路径；死代码滚动清单清零 |

## 5. 风险清单

| # | 风险 | 缓解 |
|---|---|---|
| 1 | **确定性红线**：ECS 化改迭代序可能让大规模结算对拍全红 | E1 保序验证绝对前置；任何系统迁移先过对拍再删 Kotlin 路径 |
| 2 | **美术资源**：NPC 无人物精灵与行走帧 | MVP 程序化占位；美术排期是唯一工程外依赖，需尽早立项 |
| 3 | **反向通道只能分域关**：写入者未全部下沉前该域通道必须存活 | 按《UI 读取面清单》分域管理，M3 全关依赖 WS-2 全批次完成 |
| 4 | **Room 迁移**：autoSaveIntervalMonths 删列涉及两张表 | 手动迁移重建表 + 升级路径测试覆盖旧 schema 档案 |
| 5 | **NPC 玩法设计缺位**：数量上限/生成规则/与弟子系统关系未定 | WS-4 实现前需补一份玩法设计文档（技术架构本文已锁定） |
| 6 | arm64-only 后模拟器/老设备兼容面变化 | 用 arm64 镜像或 API 30+ 转译镜像做 instrumented 测试；上线前用渠道设备数据复核 |

## 6. 执行顺序说明

M0 全部为可自动修复项，批准后可立即执行、无需再决策。M1 起的每个下沉/迁移批次独立可验收、可中断、可回滚（批内删 Kotlin 路径前对拍必须全绿）。WS-4 在进入实现前需要你补充一份 NPC 玩法设计（数量、生成规则、与弟子系统关系）。
