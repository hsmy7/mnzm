# TapTap合规认证接入 Spec

## Why
按照国家新闻出版署《关于进一步严格管理 切实防止未成年人沉迷网络游戏的通知》，游戏需要落实实名认证和防沉迷策略。TapTap合规认证服务可自动对接中宣部网络游戏防沉迷实名认证系统，帮助游戏达成合规要求。

## What Changes
- 添加TapTap合规认证SDK依赖 (tap-compliance:4.9.5)
- 创建合规认证管理器 `ComplianceManager` 处理认证逻辑
- 在TapTap SDK初始化时配置合规认证选项
- 注册合规认证回调，处理各种认证结果码
- 在登录成功后调用合规认证启动接口
- 处理充值前的额度限制检查

## Impact
- Affected specs: 用户登录流程、游戏进入流程、充值功能
- Affected code: 
  - `build.gradle` - 添加依赖
  - `TapTapAuthManager.java` - 添加合规认证初始化和回调
  - `MainActivity.kt` - 登录成功后触发认证
  - `GameActivity.kt` - 处理认证结果和充值检查

## ADDED Requirements

### Requirement: 合规认证初始化
系统应在TapTap SDK初始化时配置合规认证选项，包括是否显示切换账号按钮和是否使用年龄段信息。

#### Scenario: SDK初始化成功
- **WHEN** 应用启动并初始化TapTap SDK
- **THEN** 合规认证模块同时初始化，配置showSwitchAccount=true, useAgeRange=false

### Requirement: 合规认证流程
系统应在用户TapTap登录成功后自动启动合规认证流程。

#### Scenario: 登录成功触发认证
- **WHEN** 用户完成TapTap登录
- **THEN** 系统使用用户的unionId作为userIdentifier调用合规认证启动接口

#### Scenario: 认证通过进入游戏
- **WHEN** 合规认证返回code=500（LOGIN_SUCCESS）
- **THEN** 用户正常进入游戏

#### Scenario: 用户退出认证
- **WHEN** 合规认证返回code=1000（EXITED）
- **THEN** 系统执行登出操作，返回登录页面

#### Scenario: 用户切换账号
- **WHEN** 合规认证返回code=1001（SWITCH_ACCOUNT）
- **THEN** 系统执行登出操作，返回登录页面

#### Scenario: 时间限制
- **WHEN** 合规认证返回code=1030（PERIOD_RESTRICT）
- **THEN** 显示提示，用户只能退出或切换账号

#### Scenario: 时长限制
- **WHEN** 合规认证返回code=1050（DURATION_LIMIT）
- **THEN** 显示提示，用户只能退出或切换账号

#### Scenario: 年龄限制
- **WHEN** 合规认证返回code=1100（AGE_LIMIT）
- **THEN** 显示适龄限制提示，引导玩家退出游戏

#### Scenario: 网络错误
- **WHEN** 合规认证返回code=1200（INVALID_CLIENT_OR_NETWORK_ERROR）
- **THEN** 引导玩家检查网络并重试

#### Scenario: 实名认证关闭
- **WHEN** 合规认证返回code=9002（REAL_NAME_STOP）
- **THEN** 返回登录页面，可重新开始认证

### Requirement: 充值额度限制检查
系统应在用户发起充值前检查充值额度限制。

#### Scenario: 充值检查通过
- **WHEN** 未成年人用户发起充值请求且金额在限制范围内
- **THEN** 允许充值操作

#### Scenario: 充值被限制
- **WHEN** 未成年人用户发起充值请求但金额超出限制
- **THEN** 拒绝充值并提示限制原因
