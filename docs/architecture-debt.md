# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。
>
> **更新于 2026-07-30**：2026-07-30 全量治理已完成，详见下方 ✅ 标记项。
> 以下为当前仍保留的 ⏸️（暂缓）项和持续监控项。

---

## ✅ 已治理项（2026-07-30）

| 项 | 治理内容 | 状态 |
|---|---------|------|
| **写入守卫架构债务** | `store` 底层存储绕过守卫 → 新增 `putTo()` 守卫方法；`requireWrite`/`onWrite` 改为 `private` + setter | ✅ 100% |
| **ADPF Performance Hint API** | 已在 `ThermalMonitor` 中实现 session 创建/帧耗时报告/关闭，并接入 `GameEngineCore` 游戏循环 | ✅ 100% |
| **`processMonthlyEvents` 单事务化** | `GameEngineCore.processMonthYearChange` 中 policy 事务和月变事务合并为单 `stateStore.update{}` | ✅ 100% |
| **纹理分级压缩** | `build.gradle` 中 `bundle { texture { enableSplit = true } }` 已启用 AAB 纹理格式分发 | ✅ 100% |
| **Baseline Profile** | `profileinstaller` 依赖已添加；`baselineprofile` 模块已有完整 `BaselineProfileGenerator`（7 阶段 UI 自动化覆盖所有面板）；Release 前运行 `./gradlew :baselineprofile:publishBaselineProfile` 生成即可 | ✅ 100% |
| **R8 配置审计** | `android.enableR8.fullMode=true` 确认启用；`proguard-android-optimize.txt` 确认使用；Keep Rules 无过宽通配符 | ✅ 100% |
| **HP/MP 恢复统一** | `processMonthlyCultivationAndAuto` 注释说明使用 `recoverHpMpForAllDisciples`（一次构建 equipmentMap）比逐弟子调用 `recoverHpMpSingle` 更高效 | ✅ 已评估，无需修改 |
| **`discipleAggregates` 增量投影** | 当前实现 `.sample(200)` + `.flowOn(Default)` 在 500 弟子时已足够高效；增量优化的边际收益极低 | ✅ 已评估，无需修改 |
| **`!!` 操作符全库清理** | 清理 3 处 `!!`：`TalentDatabase.kt` / `DiscipleChatDialog.kt` / `SectTradeDialog.kt` | ✅ 100% |

---

## ⏸️ 暂缓项（低优先级）

以下项目当前行为正确，无用户可见问题，暂不修复：

### 生命周期低优项

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

### 云存档序列化远期优化

- **`SerializableBattleLogMember` 字段命名对齐** — `discipleId` vs `id` 等
- **方案 C：KSP 代码生成** — 从 GameData 自动生成 SerializableGameData，已非必需

### 写入守卫遗留（低风险，设计决策保留）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md) 中 `IntFlatArray`/`DoubleFlatArray` 的 `@PublishedApi internal` — 当前通过 `ComponentTable` 层的 `putTo()` 已封堵主要暴露路径，`IntFlatArray`/`DoubleFlatArray` 自身的 `@PublishedApi` 为 `inline` 函数（`ensureCapacity` 等）所需，不在威胁模型中。

| # | 项目 | 风险 | 修复成本 | 决策 |
|---|------|------|---------|------|
| 1 | `IntFlatArray`/`DoubleFlatArray` `@PublishedApi` 暴露 | 极低 | 高（需移除 inline + 性能损失） | ⏸️ 保留现状 |
| 2 | `IntFlatArray`/`DoubleFlatArray` `@JvmField var` 字段 | 极低 | 中（需重构 map 操作） | ⏸️ 保留现状 |

---

## 🔄 持续监控项

### Crash 1: ANR #5076 — TapTap Sandbox Toast

**状态**：⏸️ 待 TapTap SDK 更新

**当前防护**（全部就绪）：
- Looper 主线程超时监控
- ✅ 5s 超时保护 + 降级路径
- ✅ 异常守卫拦截 TapTap lateinit 崩溃
- ✅ `initAdSdk()` 调用顺序修正（2026-07-28）

**根因**：TapTap SDK 沙箱 hook `INotificationManager.enqueueToast`，闭源 SDK 内部 Binder 调用在主线程堵塞 >5s。

### Crash 2: SIGSEGV #3088 — vulkan.adreno.so

**状态**：✅ 已加固 — 继续从 Bugly 收集数据扩充黑名单

**当前防护**（全部就绪）：
- VulkanBackend.cpp: vkGetDeviceQueue 重试 3 次 + VK_NULL_HANDLE 检查
- ✅ driverVersion 检测 + 20+ 机型黑名单
- ✅ 远程配置预备（GameConfigData.VulkanSection）
- ✅ NDK 编译验证
