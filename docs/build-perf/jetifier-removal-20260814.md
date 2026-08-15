# Jetifier 移除试验（2026-08-14）

> 阶段 1.6 产物。`android.enableJetifier=true` → `false` 的验证记录与决策。

## 背景

`gradle.properties` 原有 `android.enableJetifier=true`。Jetifier 是字节码级把
support 库引用改写为 androidx 的机制，有显著构建开销（每个 AAR 多一道 transform）。
项目依赖已全部坐标级迁移 androidx（依赖树中 `com.android.support:support-annotations:28.0.0
-> androidx.annotation:annotation:1.10.0` 重定向），疑似无 support 字节码残留。

## 验证过程（证据链）

| 步骤 | 结果 |
|---|---|
| 依赖树扫描 | 仅 `support-annotations`（坐标重定向到 androidx）+ `androidx.legacy`（本身是 androidx），无其他 support 坐标 |
| `enableJetifier=false` + assembleDebug | ✅ 构建成功 |
| 关闭时 APK dex 扫描 | 57 个唯一 `Landroid/support/*` 引用（152 处），**全部是 `annotation/` 包注解类** |
| 对照：开启时 APK dex 扫描 | 6 个唯一引用（`androidx.core` 的 support 别名类：INotificationSideChannel/ResultReceiver/Parcelizer 等，core 库自带这些类文件，安全） |
| 注解 retention 抽查（AnimRes/ColorInt/DrawableRes/Dimension/CallSuper/AnyThread/CheckResult） | 均无显式 `@Retention` → **默认 CLASS retention → 编译期保留、运行时永不加载** |

## 结论

- 关闭 jetifier 后 dex 中多出的引用全部是 **CLASS retention 注解**（编译期 lint 用途），
  运行时不会触发类加载 → **无 NoClassDefFoundError 风险**
- 编译期已验证（assembleDebug 通过）
- **残余风险：未做真机/模拟器冒烟**（本机无设备）——广告 SDK（TapADN 5.1.2.3）等
  第三方 AAR 的运行期行为未在真实环境验证

## 决策

- ~~保持 `enableJetifier=false`（构建提速：省去每 AAR 的 jetify transform）~~
- **已被推翻并回滚（2026-08 归档勘误）**：本试验之后（release 3.2.14，commit `60f99cca`），
  广告 SDK（TapADN）出现运行期 Support 库缺失，**启用 Jetifier 修复**——当前
  `gradle.properties:18` 为 `android.enableJetifier=true`，是**有意保留的修复状态**，
  不再是待还技术债。
- **偿还触发（重新关闭的前置条件）**：广告 SDK 升级到无 support 字节码残留版本后，
  重新执行本试验流程（依赖树扫描 + dex 扫描 + 真机冒烟），通过后可再次关闭。

## 回滚方式

```properties
# gradle.properties 第 15 行
android.enableJetifier=true   # 当前值（有意保留）
```
