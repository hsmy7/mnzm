# 修仙宗门游戏 API 文档

## 目录
1. [配置信息](#配置信息)
2. [第三方 SDK API](#第三方-sdk-api)
3. [本地数据存储 API](#本地数据存储-api)
4. [网络依赖配置](#网络依赖配置)

---

## 配置信息

### API 配置文件

**文件路径**: `android/api.properties`

```properties
# TapTap SDK 配置
TAPTAP_CLIENT_ID=csg5qlajcgr157ix01
TAPTAP_CLIENT_TOKEN=bvDH35Gw2SCcgkmNFrmQxLi0xaMylxLXEB6VNMUK
TAPTAP_IS_CN=true

# API 配置
API_BASE_URL=https://api.xianxia.com/api/
DEV_API_BASE_URL=http://10.0.2.2:3000/api/
PROD_API_BASE_URL=https://api.xianxia.com/api/
```

### BuildConfig 字段

在 `android/app/build.gradle` 中定义的 BuildConfig 字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `API_BASE_URL` | String | API 基础 URL |
| `TAPTAP_CLIENT_ID` | String | TapTap 客户端 ID |
| `TAPTAP_CLIENT_TOKEN` | String | TapTap 客户端 Token |
| `TAPTAP_IS_CN` | boolean | 是否中国区 |
| `DEBUG_MODE` | boolean | 是否为调试模式 |

---

## 第三方 SDK API

### 1. TapTap 登录认证 API

**文件**: `TapTapAuthManager.java`

#### 初始化 SDK
```java
public static void init(Activity activity, String clientId, String clientToken, boolean isCN)
```
- **功能**: 初始化 TapTap SDK
- **参数**:
  - `activity`: 当前 Activity
  - `clientId`: TapTap 客户端 ID
  - `clientToken`: TapTap 客户端 Token
  - `isCN`: 是否中国区

#### 用户登录
```java
public static void login(Activity activity, final LoginResultCallback callback)
```
- **功能**: 发起 TapTap 登录
- **回调**:
  - `onSuccess(LoginData data)`: 登录成功
  - `onFailure(Exception error)`: 登录失败或取消

#### 获取登录状态
```java
public static boolean isLoggedIn()
```
- **返回**: 当前是否已登录

#### 获取当前账号
```java
public static TapTapAccount getCurrentAccount()
```
- **返回**: 当前登录的账号信息

#### 登出
```java
public static void logout()
```
- **功能**: 退出当前登录

### 2. 登录数据模型

**文件**: `LoginData.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `openid` | String | 用户唯一标识 |
| `unionid` | String | 联合 ID |
| `name` | String | 用户名 |
| `avatar` | String | 头像 URL |
| `kid` | String | Token Key ID |
| `tokenType` | String | Token 类型 |
| `macKey` | String | MAC 密钥 |
| `macAlgorithm` | String | MAC 算法 |

### 3. 防沉迷合规 API

**文件**: `ComplianceManager.kt`

#### 回调码常量

| 常量名 | 值 | 说明 |
|--------|-----|------|
| `CODE_LOGIN_SUCCESS` | 500 | 认证通过 |
| `CODE_EXITED` | 1000 | 退出防沉迷认证 |
| `CODE_SWITCH_ACCOUNT` | 1001 | 用户切换账号 |
| `CODE_PERIOD_RESTRICT` | 1030 | 时间限制 |
| `CODE_DURATION_LIMIT` | 1050 | 时长限制 |
| `CODE_AGE_LIMIT` | 1100 | 年龄限制 |
| `CODE_NETWORK_ERROR` | 1200 | 网络错误 |
| `CODE_REAL_NAME_STOP` | 9002 | 用户关闭实名认证窗口 |

#### 注册回调
```kotlin
fun registerCallback(callback: ComplianceCallback)
```

#### 启动合规认证
```kotlin
fun startup(activity: Activity, userIdentifier: String)
```
- **参数**:
  - `activity`: 当前 Activity
  - `userIdentifier`: 用户标识（通常使用 openid）

#### 退出合规认证
```kotlin
fun exit()
```

#### ComplianceCallback 接口
```kotlin
interface ComplianceCallback {
    fun onLoginSuccess()      // 认证通过
    fun onExited()            // 退出认证
    fun onSwitchAccount()     // 切换账号
    fun onPeriodRestrict()    // 时间限制
    fun onDurationLimit()     // 时长限制
    fun onAgeLimit()          // 年龄限制
    fun onNetworkError()      // 网络错误
    fun onRealNameStop()      // 关闭实名窗口
}
```

---

## 本地数据存储 API

### 1. 会话管理

**文件**: `SessionManager.kt`

#### 存储字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `isLoggedIn` | Boolean | 是否已登录 |
| `userId` | String? | 用户 ID |
| `userName` | String? | 用户名 |
| `loginType` | String? | 登录类型 |
| `hasAgreedPrivacy` | Boolean | 是否同意隐私政策 |

#### 方法

```kotlin
// 保存登录会话
fun saveLoginSession(userId: String, userName: String, loginType: String)

// 清除会话
fun clearSession()
```

---

## 网络依赖配置

### Gradle 依赖

**文件**: `android/app/build.gradle`

```gradle
// TapTap SDK
implementation 'com.taptap.sdk:tap-login:4.9.5'
implementation 'com.taptap.sdk:tap-core:4.9.5'
implementation 'com.taptap.sdk:tap-common:4.9.5'
implementation 'com.taptap.sdk:tap-compliance:4.9.5'

// Network (Retrofit + OkHttp)
implementation 'com.squareup.retrofit2:retrofit:2.11.0'
implementation 'com.squareup.retrofit2:converter-gson:2.11.0'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'

// Gson
implementation 'com.google.code.gson:gson:2.12.1'
```

### 网络安全配置

**文件**: `android/app/src/main/res/xml/network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
    
    <domain-config>
        <domain includeSubdomains="true">taptap.com</domain>
        <domain includeSubdomains="true">tapsdk.com</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

---

## 使用示例

### 初始化 TapTap SDK

```kotlin
TapTapAuthManager.init(
    activity,
    BuildConfig.TAPTAP_CLIENT_ID,
    BuildConfig.TAPTAP_CLIENT_TOKEN,
    BuildConfig.TAPTAP_IS_CN
)
```

### 登录流程

```kotlin
TapTapAuthManager.login(activity, object : TapTapAuthManager.LoginResultCallback {
    override fun onSuccess(data: LoginData) {
        // 保存登录信息
        sessionManager.saveLoginSession(
            data.openid,
            data.name,
            "taptap"
        )
        
        // 启动防沉迷认证
        ComplianceManager.startup(activity, data.openid)
    }
    
    override fun onFailure(error: Exception) {
        // 处理登录失败
    }
})
```

### 防沉迷认证

```kotlin
ComplianceManager.registerCallback(object : ComplianceManager.ComplianceCallback {
    override fun onLoginSuccess() {
        // 认证通过，进入游戏
    }
    
    override fun onExited() {
        // 退出游戏
    }
    
    override fun onSwitchAccount() {
        // 切换账号
    }
    
    override fun onPeriodRestrict() {
        // 显示时间限制提示
    }
    
    override fun onDurationLimit() {
        // 显示时长限制提示
    }
    
    override fun onAgeLimit() {
        // 显示年龄限制提示
    }
    
    override fun onNetworkError() {
        // 显示网络错误
    }
    
    override fun onRealNameStop() {
        // 用户关闭实名窗口
    }
})
```

---

## 注意事项

1. **API URL 已配置但未使用**: 项目已配置后端 API 地址，但尚未实现具体的后端 API 调用
2. **Retrofit 依赖已添加**: 项目已集成 Retrofit 和 OkHttp，可用于后续开发后端 API
3. **游戏数据本地存储**: 当前游戏数据使用 Room 数据库和 SharedPreferences 本地存储
4. **TapTap SDK 必需**: 游戏依赖 TapTap SDK 进行用户登录和防沉迷认证
