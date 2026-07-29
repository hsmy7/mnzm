# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。
>
> **更新于 2026-07-29**：2026-07-28 全量治理已完成，详见 CHANGELOG.md。
> 以下为当前仍保留的 ⏸️（暂缓）项和持续监控项。

---

## ⏸️ 暂缓项（低优先级）

以下项目当前行为正确，无用户可见问题，暂不修复：

### 生命周期低优项

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

### 写入守卫架构债务

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：
- `store` 底层存储绕过守卫（`@PublishedApi internal val store`）
- `requireWrite` / `onWrite` 为 `@JvmField var`

### 云存档序列化远期优化

- **`SerializableBattleLogMember` 字段命名对齐** — `discipleId` vs `id` 等
- **方案 C：KSP 代码生成** — 从 GameData 自动生成 SerializableGameData，已非必需

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
