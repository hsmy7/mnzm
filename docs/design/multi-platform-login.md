# 多平台发布登录设计方案（TapTap / 好游快爆 / 4399）

> 生成日期：2026-08-20 | 状态：待评审 | 决策分级：**架构级重构**（多渠道抽象，触达登录主流程与构建体系）
> 需求来源：用户希望游戏发布到 TapTap、好游快爆、4399 三个平台，玩家在各自平台点击「进入游戏」后自动登录**对应平台**的账号，绝不串号。

---

## 一、背景与目标

### 1.1 现状

- 游戏当前**仅接 TapTap SDK**：登录（`TapTapAuthManager`）、防沉迷实名（`tap-compliance`）、云存档（`TapCloudSaveManager`）、统计（`TapDBManager`）、排行榜（`TapTapLeaderboardApi`）全部耦合 TapTap。
- 登录入口：主界面「进入游戏」按钮（本次改造后），点击后调 `TapTapAuthManager.login()` → 静默登录/授权页 → 防沉迷验证 → 模式选择。

### 1.2 用户痛点（业务语言）

> 游戏要上三个平台：TapTap、好游快爆、4399。玩家从 TapTap 下载的包，点「进入游戏」就该自动登录他的 TapTap 账号；从 4399 下载的包，就该自动登录 4399 账号。**怎么保证绝不串号、且不用玩家手动选平台？**

### 1.3 核心答案（一句话）

> **渠道包（Channel Build）隔离**：每个平台发布一个独立 APK，包内**只内嵌该平台自己的 SDK**。玩家从哪个平台下载，装的包就只认识哪个平台的账号系统——登录是构建期决定的，不是运行期选择的，物理上不可能串号。

### 1.4 成功标准

| # | 标准 | 验收方式 |
|---|------|---------|
| 1 | 三平台各出一包，包内仅含对应平台登录 SDK | 反编译检查依赖 + 构建产物清单 |
| 2 | 玩家点「进入游戏」→ 该平台 SDK 登录（已登录则静默）→ 防沉迷 → 进游戏 | 三平台真机/云真机验证 |
| 3 | 跨渠道绝不串号（Tap 包无 4399 登录入口） | 渠道包签名/依赖审计测试 |
| 4 | 登录 SDK 初始化/登出与主流程解耦（延续 safeRunAfterSdkInit 契约） | SafeRunAfterSdkInitTest 继续全绿 |

---

## 二、技术方案

### 2.1 总体架构：Gradle 多渠道（productFlavors） + 平台登录抽象

```
app/build.gradle
├─ productFlavors
│  ├─ taptap   → 仅依赖 tap-login / tap-compliance / tap-cloudsave / tap-db
│  ├─ kuaibao  → 仅依赖 好游快爆 SDK（快爆登录/实名）
│  └─ m4399    → 仅依赖 4399 SDK（4399 登录/实名）
│  （每个 flavor 注入 BuildConfig.CHANNEL = "taptap"/"kuaibao"/"m4399"）
│
└─ 运行期：PlatformLoginManager 接口（按渠道注入实现）
   ├─ TapTapChannelLogin    → TapTapAuthManager（现状）
   ├─ KuaibaoChannelLogin   → 快爆 SDK 登录（新建）
   └─ M4399ChannelLogin     → 4399 SDK 登录（新建）
```

**渠道包如何做到"自动登录对应平台账号"：**
1. 玩家从 TapTap 下载的 APK 是 `taptap` flavor 构建的，包内**只有 TapTap SDK 的类**。
2. 点「进入游戏」→ `PlatformLoginManager.login()` 走 `TapTapChannelLogin` → `TapTapAuthManager.login()` → TapTap SDK 检查设备上 TapTap App/授权态：**已授权直接返回账号（静默登录）**，未授权弹 TapTap 授权页。
3. 4399 包同理只走 4399 SDK；因为 4399 SDK 类根本不在 TapTap 包内，**Tap 包永远无法唤起 4399 登录**——这就是"不串号"的物理保证。

### 2.2 关键接口设计

```kotlin
// app 层（src/main/java/com/xianxia/sect/taptap/ 同级新建 channel/ 包）
interface PlatformLoginManager {
    val channel: String                       // BuildConfig.CHANNEL
    fun isSdkReady(): Boolean
    fun isLoggedIn(): Boolean
    fun login(activity: Activity, callback: LoginResultCallback)   // 静默/授权
    fun logout()
    fun getAccountUserId(): String?           // 平台 openid
}
// 回调复用现有 LoginData（openid/unionid/name/avatar/token 字段通用）
```

**防沉迷/实名按渠道隔离**：TapTap 用现有 `ComplianceManager`（tap-compliance）；好游快爆/4399 各按其平台实名 SDK 文档接入，同样走 `ComplianceCallbackHost` 回调端口（D-42 已把合规回调抽象成宿主转发，渠道实现只换 SDK 调用，宿主契约不变）。

### 2.3 数据流（进入游戏 → 进游戏）

```
[进入游戏按钮] → PlatformLoginManager.login(activity, cb)
   ├─ 未同意隐私 → toast（现状不变）
   ├─ SDK 未就绪 → toast「平台登录服务正在初始化」（文案泛化，不再写死 TapTap）
   ├─ 登录成功 → SessionManager.saveLoginSession(loginType = channel)
   │            → TapDB/渠道统计 setUser（渠道实现各自处理，TapTap 保留 TapDB）
   │            → safeRunAfterSdkInit { 渠道 SDK 服务初始化; 防沉迷验证 }（契约不变）
   └─ 登录失败 → onLoginError（文案泛化）
```

### 2.4 现状代码改造点（最小侵入）

| 文件 | 改动 |
|------|------|
| `MainActivity.kt` | `TapTapAuthManager.*` 直引 → `PlatformLoginManager` 接口注入；toast 文案泛化（"TapTap SDK 正在初始化" → "平台登录服务正在初始化"） |
| `MainActivity.kt`（登录回调） | `loginType = "taptap"` 硬编码 → `channel` |
| `BridgeBindingsModule.kt` | 按 `BuildConfig.CHANNEL` 绑定 `PlatformLoginManager` 实现（`@Named` 或工厂） |
| `XianxiaApplication.kt`（AccountBindingProvider） | `TapTapAuthManager` → `PlatformLoginManager`（登录态/账号 ID 来源） |
| `GameActivity.kt`（登出路径） | 同步走 `PlatformLoginManager.logout()` |
| `app/build.gradle` | 新增 productFlavors + 各渠道 SDK 依赖 + `BuildConfig.CHANNEL` |
| `api.properties` | 新增 `KUAIBAO_*`、`M4399_*` 密钥占位（不提交真实密钥） |
| 登录按钮 UI | 本次已完成：`btn_enter_game.webp` 替换「使用 TapTap 登录」按钮，点击即自动登录 |

### 2.5 各平台 SDK 接入要点（第三方文档）

- **TapTap**（已接入）：`TapTapLogin.loginWithScopes(activity, ["public_profile"], cb)` 即静默登录——用户已授权则直接回调当前账号，不弹页（[TapTap 登录功能介绍](https://developer.taptap.io/docs/zh-Hans/sdk/taptap-login/features/)）。
- **4399**：4399 开放平台提供登录 SDK（`sdkftp.4399doc.com` 在线版 `online_java_guide`），初始化需 AppKey，登录返回 4399 用户 ID + 服务器校验 token；上架需包名/签名报备（[4399 SDK 文档](https://sdkftp.4399doc.com/external/operate/3.18/online_java_guide.md)、[4399 登录接口](https://doc.my4399.com/wiki/%E7%99%BB%E5%BD%95%E6%8E%A5%E5%8F%A3)）。
- **好游快爆**：好游快爆开发者平台（`open.3839.com`）提供 SDK（登录/云存档等），个人开发者可申请上架（[好游快爆上架流程](https://juejin.cn/post/7352892698892517416)、[好游快爆开发者平台](https://open.3839.com/console/)）。
- **渠道包规范**：国内安卓渠道通行做法为多渠道打包（[腾讯 Bugly 多渠道打包](https://zhuanlan.zhihu.com/p/26674427)、[阿里云多渠道打包与 Gradle 优化](https://developer.aliyun.com/article/1746446)）。

> 说明：好游快爆/4399 的 SDK aar 需在其开发者后台申请后下载，本方案文档先行，实际依赖落地时按官方文档核对 API（SDK 版本以申请时最新为准）。

---

## 三、影响范围清单

| 文件路径 | 变更类型 | 变更说明 |
|---------|---------|---------|
| `android/app/build.gradle` | 改 | productFlavors（taptap/kuaibao/m4399）+ 渠道 SDK 依赖 + `BuildConfig.CHANNEL` |
| `android/gradle/libs.versions.toml` | 改 | 新增好游快爆/4399 SDK 版本声明 |
| `MainActivity.kt` | 改 | 登录入口/回调/文案泛化，走 `PlatformLoginManager` |
| `XianxiaApplication.kt` | 改 | `AccountBindingProvider` 换用渠道实现 |
| `GameActivity.kt` | 改 | 登出路径换用渠道实现 |
| `di/BridgeBindingsModule.kt` | 改 | 按渠道绑定登录实现 |
| `taptap/TapTapAuthManager.java` | 改（包一层） | 保持不动，新增 `TapTapChannelLogin` 适配 |
| `channel/KuaibaoChannelLogin.kt` | 新增 | 快爆 SDK 登录适配 |
| `channel/M4399ChannelLogin.kt` | 新增 | 4399 SDK 登录适配 |
| `channel/PlatformLoginManager.kt` | 新增 | 渠道登录抽象接口 |
| `api.properties` | 改 | 新增渠道密钥占位（不入库） |
| 登录按钮 UI / 肖像资源 | 已完成 | `btn_enter_game.webp` + 37 张弟子肖像同步 |
| `PrivacyConsentScreen.kt` | 改 | 隐私政策按渠道展示对应 SDK（4399/快爆 需补充披露，`隐私合规` 标签） |
| `docs/index.html` | 改 | 网站隐私政策同步渠道 SDK 披露（`隐私合规` 标签） |
| `changelog_entries.json` / `CHANGELOG.md` | 改 | 功能变更双日志 |
| **iOS 影响** | 评估 | 三平台均有 iOS SDK（TapTap iOS SDK 已有对等接口）；渠道登录抽象在 app 层，iOS 按同接口实现 |

---

## 四、兼容性分析

- **存档/序列化**：无 Entity、无 Migration、无序列化变更（`loginType` 字段已存在于 SessionManager，仅取值从写死 "taptap" 变为渠道名，旧档 "taptap" 值兼容）。
- **云存档**：TapTap 云存档仅 taptap flavor 编译进包；kuaibao/m4399 包默认无云存档（本地存档正常），待各自云存档能力评估后接入。
- **防沉迷**：渠道实现各自接平台实名 SDK，宿主回调契约（ComplianceCallbackHost）不变；TapTap 行为零变化。
- **构建产物**：新增渠道后 `assembleRelease` 产出三个 APK；AAB 分发同样按渠道出包。
- **统计**：TapDB 仅 taptap 包；其他渠道先降级为无统计（不阻断登录），后续按各平台统计 SDK 接入。

---

## 五、测试方案

| 测试 | 类型 | 说明 |
|------|------|------|
| `PlatformLoginManagerTest` | 单测 | 渠道工厂按 `BuildConfig.CHANNEL` 返回正确实现（守卫：新增渠道必须注册） |
| `LoginFlowChannelTest` | 单测 | 登录回调 `loginType` = channel；登出清 Session + 渠道 SDK 会话 |
| 渠道包依赖审计 | 构建守卫 | 反编译检查 taptap 包不含 4399 SDK 类（防串号守卫测试，`ChannelIsolationGuardTest`） |
| `SafeRunAfterSdkInitTest` | 回归 | 延续「SDK 服务初始化不阻断防沉迷验证」契约 |
| `AtlasManifestSyncTest` / `PortraitPoolTest` | 回归 | 本次资源替换守卫（已跑绿） |
| 墙钟核算 | — | 上述新增单测均 runTest 虚拟时间，单测 < 2s；守卫测试为静态检查，无迭代 |

---

## 六、风险评估与兜底

| 风险 | 等级 | 兜底 |
|------|------|------|
| 好游快爆/4399 SDK 文档以申请后实际为准 | 中 | 本方案只定抽象契约，SDK 具体 API 在接入时按官方文档核对 |
| 三平台密钥/包名/签名报备周期长 | 中 | flavor 先行落地（可先只出 taptap + 占位渠道），密钥就绪即插即用 |
| 渠道统计缺失影响数据 | 低 | 登录不依赖统计，可后补 |
| 云存档跨渠道不可用 | 低 | 本地存档兜底，产品层面提示"云存档仅 TapTap 支持" |

---

## 七、未来场景推演（≥6 个月档）

| 维度 | 推演 |
|------|------|
| 规模增长 | 新增第 4 个渠道 = 加一个 flavor + 一个 ChannelLogin 实现 + 一条守卫注册，成本线性 |
| 生命周期 | 渠道包构建/清缓存/CI 全流程一致；UID 映射追加式（本次 btn_enter_game 已验证） |
| 平台扩张 | iOS 端三平台 SDK 均存在，`PlatformLoginManager` 同接口实现，抽象不重做 |
| 运营演进 | 渠道活动（如 4399 礼包）经 RemoteConfig 下发，无需发版 |
| 兼容回退 | 出问题渠道可单独停发/回退旧包；`PlatformLoginManager` 有默认实现兜底 |

---

## 八、技术债与偿还计划

| 债项 | 产生原因（为何现在不全做） | 偿还时机 |
|------|--------------------------|---------|
| 好游快爆/4399 SDK 实际依赖未接入 | 需在开发者后台申请 SDK/aar 与密钥，超出本次范围 | 用户提供渠道后台凭证后按官方文档接入 |
| 渠道统计未接（非 TapTap 渠道） | 三平台统计 SDK 需各自申请 | 渠道上架审核要求统计时接入 |
| 渠道云存档未接（非 TapTap 渠道） | 快爆云存档 SDK 已有公开文档，4399 待查 | 产品确认多端云存档需求后接入 |

---

## 九、参考来源清单

| 来源 | 类型 | 说明 |
|------|------|------|
| [TapTap 登录功能介绍](https://developer.taptap.io/docs/zh-Hans/sdk/taptap-login/features/) | S 级官方文档 | 登录方式/静默登录/账号切换 |
| [TapTap 登录最佳实践](https://developer.taptap.io/docs/zh-Hans/sdk/taptap-login/best-practice/) | S 级官方文档 | 登录状态处理/切换账号 |
| [TapTap 开发者文档-防沉迷 FAQ](https://developer.taptap.cn/docs/en/sdk/anti-addiction/faq/) | S 级官方文档 | 实名认证/防沉迷要求 |
| [TapTap 登录产品介绍](https://developer.taptap.cn/product-intro/tap-login) | S 级官方文档 | 登录能力总览 |
| [4399 SDK 在线版接入指南](https://sdkftp.4399doc.com/external/operate/3.18/online_java_guide.md) | S 级官方文档 | 4399 登录 SDK 初始化/登录/回调 |
| [4399 登录接口文档](https://doc.my4399.com/wiki/%E7%99%BB%E5%BD%95%E6%8E%A5%E5%8F%A3) | S 级官方文档 | 登录接口参数/校验 |
| [4399 渠道配置说明（QuickSDK）](https://www.quicksdk.com/news-296.html) | B 级社区 | 4399 渠道接入要点 |
| [好游快爆开发者平台](https://open.3839.com/console/) | S 级官方入口 | 快爆 SDK/上架入口 |
| [Unity-好游快爆-安卓应用上架流程](https://juejin.cn/post/7352892698892517416) | B 级社区 | 快爆上架流程/包名签名要求 |
| [好游快爆云存档 SDK 接入文档](https://cloud.tencent.com.cn/developer/article/1937258) | B 级社区 | 快爆云存档能力 |
| [腾讯 Bugly 多渠道打包神器](https://zhuanlan.zhihu.com/p/26674427) | B 级社区 | 多渠道打包方案 |
| [阿里云：多渠道打包与 Gradle 优化](https://developer.aliyun.com/article/1746446) | B 级社区 | productFlavors 实践 |
| [渠道对接那点事儿（新浪游戏）](http://games.sina.com.cn/y/n/2015-08-26/fxhehqr6321679-p11.shtml) | C 级参考 | 渠道对接行业惯例 |

---

## 十、方案自检（design-plan-review.md）

- [x] 未来场景推演（≥6 个月档）— 第七节
- [x] 技术债与偿还计划 — 第八节（有明确触发条件）
- [x] YAGNI：`PlatformLoginManager` 有当前生产消费者（进入游戏按钮登录入口）
- [x] 测试墙钟核算 — 第五节（<2s/新增单测，守卫为静态检查）
- [x] rules/ 交叉核对：`sdk-init-lifecycle.md`（登录解耦契约延续）、`static-resources.md`（本次资源替换合规）、`commercialization.md`（渠道活动预留 RemoteConfig）、`data-analytics.md`（渠道统计差异显式声明）
- [x] 决策分级：架构级重构，但**最小切入路径**为「先落 flavor + 抽象 + TapTap 实现，快爆/4399 待凭证」
- [x] 影响范围含 `隐私合规` 与 `iOS` 标签项
