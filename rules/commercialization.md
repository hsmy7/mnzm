# 规则：商业化与运营扩展规范（含 LiveOps）

> ⚠️ **预留规范（未实现，约束未来设计）**：本文约束未来商业化扩展（IAP/月卡/战令/运营活动配置化/RemoteConfig 激活）。现状基线：仅 2 个激励广告位（`AdPurpose`：BREAKTHROUGH_BONUS/MERCHANT_REFRESH）、0 IAP、运营邮件全为客户端内置 `BuiltinMailConfig`、RemoteConfig 未绑定（`CoreModule.kt:157` 注释状态）。见 `docs/knowledge-base.md#扩展性现状盘点`。

## 1. 广告扩展（🔴）

新广告类型的完整接入流程（引用 `docs/knowledge-base.md#免广告特权白名单`，此处不复制正文）：

1. **引擎层枚举注册**：`AdPurpose`（`core/engine/.../service/AdService.kt`）加枚举值
2. **ViewModel 统一入口**：`adService.watchAd(AdPurpose.NEW) { 发放奖励() }`——不需要任何白名单判断（白名单守卫在 `AdServiceImpl` 集中完成）
3. **奖励验证后计冷却**：`onRewardVerify` 返回 `rewardVerify = true` 后调用 `markAdWatched()`（rules/ad-cooldown.md）
4. **白名单守卫测试自动继承**：新增广告类型无需额外白名单代码
5. **频率策略预留**：60 秒冷却为底线，未来可按 AdPurpose 配置每日上限（参照 `AdsDelegate` 每日次数限制模式），上限配置化（RemoteConfig 可下发）

## 2. IAP / 月卡 / 战令（🔴 预留约束，未实现时即约束设计）

### 2.1 通用付费点接入

- **购买校验**：防重放（订单号去重）、防漏发（服务器确认后才发奖）、双端一致性（Android/iOS 订单对齐）
- **IAP 不适用广告冷却**（rules/ad-cooldown.md 边界声明：真钱购买无频控需求）
- **支付 SDK 变更走隐私合规**（第 6 条）：第三方 SDK 变更必须同步更新隐私政策双入口

### 2.2 月卡设计约束

- 每日领取窗口 + **过期补领规则**（领取状态持久化，不是冷却计时）
- 到期提醒（需推送能力时走推送频控）

### 2.3 战令设计约束（对标行业 Battle Pass 实践）

- **大奖前置**：核心奖励从高等级前置到 1 级附近（王者荣耀 55 级 → 1 级先例：降低破冰门槛）
- **沉没成本设计**：免费线必要（无门槛塑造沉没价值 + 与付费线比价）；阶段性大奖提前展示
- **最终大奖不宜是硬战斗力**（影响大 R 价值锚定）——限定外观/养成类更优
- **周期与赛季同步**：活动周期与游戏内"年"或现实赛季对齐，周期过长过短均伤留存
- **防"肝满后不再上线"**：每日经验上限或任务节奏设计（对局类游戏教训）

## 3. 运营活动配置化（🔴）

1. **历战入口注册**：新活动接入 `LizhanDialog` 卡片轮转（卡片列表 + 图标 + 描述），活动时间窗三态完整（未开启/开启中/已结束）
2. **运营邮件扩展路径**：当前 `BuiltinMailConfig` 客户端内置（节日/白名单/QQ 群）→ 未来 RemoteConfig 推送（邮件属**凭据类发放**——领取失败保留凭据可重试，溢出语义见 CLAUDE.md 13.3）
3. **活动日历**：多活动并存时维护活动日历（开始/结束时间窗），禁止时间窗硬编码在 UI 层
4. **离线/跨版本兼容**：活动时间窗按游戏时间（年/月）锚定，读档后状态一致（参照秘境断线续玩先例）

## 4. RemoteConfig 使用规范（🟡 激活前置条件）

当前 `RemoteConfigProvider`（core/domain 接口）+ `HttpRemoteConfigProvider`（core/engine 实现，10s 超时）已存在但**未绑定**（`CoreModule.kt:157` 注释状态）。激活前必须：

1. **先补服务端能力**：远程配置 JSON 的托管端点、版本管理、下发策略——未具备服务端能力前禁止直接改 `CoreModule` 绑定
2. **本地默认值兜底**：每个配置项必须带本地默认值（配置缺失/拉取失败不崩溃，回退本地值）
3. **Key 命名规范**：`模块.配置名`（如 `ad.daily_limit`、`activity.mi_jing_interval_years`）
4. **版本化变更**：配置项增删改按版本管理，旧版本客户端兼容处理
5. **A/B 测试驱动**：实验配置项按玩家分组下发（见 rules/data-analytics.md）

## 5. 慷慨原则（🟡 活动投放评审标准）

对标 CoC GDC 2020 四大原则，作为运营活动投放设计的评审清单：

- **转化/留存优先**：活动设计先问"是否提升回访与留存"，而非先问流水
- **理解你的游戏**：活动奖励与游戏核心循环（修炼/生产/突破）挂钩，不发放与玩家无关的道具
- **慷慨（Be Generous）**：频繁小福利优于罕见大礼包；白名单/节日邮件模式已示范
- **长期主义**：宁可牺牲短期变现，确保所有玩家都能得到想要的结果

## 6. 隐私合规（🔴 双入口强制）

涉及以下变更时必须同步更新隐私政策**两个入口**（CLAUDE.md 设计方案原则 5）：
1. **游戏内**：`PrivacyConsentScreen.kt`（展示内容或三方 SDK 链接）
2. **网站版**：`docs/index.html`（发布在 https://hsmy7.github.io/mnzm/）

触发场景：新增/变更第三方 SDK（广告/支付/推送/分析）、新增权限申请（推送通知权限等）、新增网络请求端点、新增数据共享第三方、广告/分析/推送模块变更。

## 行业依据

- GDC 2020 Eino Joas《Clash of Clans: Bigger, Better, Battle Pass》：https://www.gamedeveloper.com/business/video-how-supercell-designed-the-i-clash-of-clans-i-battle-pass（四大原则/转化/慷慨/长期主义）
- GameRes《游戏设计左道，BattlePass 新思考》：https://www.gameres.com/906556.html（沉没成本/免费线/大奖前置/价格歧视）
- gamelook《Supercell"再次发疯"革自己命》：http://www.gamelook.com.cn/2025/07/574409/（皇室战争 2025 BP 改革：简化系统 + 收入连增）
- CSDN《休闲游戏都在用的"Battle Pass"，为何能成为营收与留存的利器？》：https://blog.csdn.net/GameGoing/article/details/154131934（留存铁三角：短期 7 日奖励/中期赛季通行证/长期社交绑定）
- 两江新区《重庆吉艾斯球如何用 6.9 万成本创收过亿》：https://www.liangjiang.gov.cn/mixmedia/a/202510/23/WS68f99863e4b0840ed5677139.html（修仙品类商业化验证）
