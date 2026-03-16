# Tasks

- [x] Task 1: 创建兑换码数据模型
  - [x] SubTask 1.1: 在 `core/model/` 下创建 `RedeemCode.kt`，定义 RedeemCode 数据类
  - [x] SubTask 1.2: 定义 RedeemRewardType 枚举（SPIRIT_STONES, EQUIPMENT, MANUAL, PILL, MATERIAL, HERB, SEED, DISCIPLE）
  - [x] SubTask 1.3: 定义 DiscipleRewardConfig 数据类，包含弟子奖励配置（境界、灵根、天赋、属性等）
  - [x] SubTask 1.4: 在 `GameData.kt` 中添加已使用兑换码列表字段 `usedRedeemCodes: List<String>`

- [x] Task 2: 创建兑换码管理器
  - [x] SubTask 2.1: 在 `core/engine/` 下创建 `RedeemCodeManager.kt`
  - [x] SubTask 2.2: 实现预置兑换码配置列表
  - [x] SubTask 2.3: 实现兑换码验证逻辑（存在性、启用状态、过期时间、使用次数、重复使用检查）
  - [x] SubTask 2.4: 实现奖励发放逻辑（灵石、装备、功法、丹药、材料、灵草、种子）
  - [x] SubTask 2.5: 实现弟子奖励生成逻辑，支持指定灵根、天赋、属性等配置

- [x] Task 3: 更新 GameViewModel
  - [x] SubTask 3.1: 添加兑换码对话框显示状态 `_showRedeemCodeDialog`
  - [x] SubTask 3.2: 添加 `openRedeemCodeDialog()` 和 `closeRedeemCodeDialog()` 方法
  - [x] SubTask 3.3: 添加 `redeemCode(code: String)` 方法，调用 RedeemCodeManager 进行兑换

- [x] Task 4: 更新 GameEngine
  - [x] SubTask 4.1: 在 GameEngine 中注入 RedeemCodeManager
  - [x] SubTask 4.2: 添加 `redeemCode()` 方法供 ViewModel 调用

- [x] Task 5: 创建兑换码UI组件
  - [x] SubTask 5.1: 在 `MainGameScreen.kt` 的 SettingsTab 中添加"兑换码"按钮
  - [x] SubTask 5.2: 创建 `RedeemCodeDialog` 组件，包含输入框和确认/取消按钮
  - [x] SubTask 5.3: 实现兑换结果显示（成功/失败提示，弟子奖励显示弟子信息）

- [ ] Task 6: 测试验证
  - [ ] SubTask 6.1: 测试灵石兑换码
  - [ ] SubTask 6.2: 测试装备兑换码
  - [ ] SubTask 6.3: 测试功法兑换码
  - [ ] SubTask 6.4: 测试丹药兑换码
  - [ ] SubTask 6.5: 测试过期兑换码
  - [ ] SubTask 6.6: 测试重复使用兑换码
  - [ ] SubTask 6.7: 测试弟子兑换码 - 基础弟子
  - [ ] SubTask 6.8: 测试弟子兑换码 - 指定灵根弟子
  - [ ] SubTask 6.9: 测试弟子兑换码 - 指定天赋弟子
  - [ ] SubTask 6.10: 测试弟子兑换码 - 指定属性弟子

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 2
- Task 4 depends on Task 2
- Task 5 depends on Task 3
- Task 6 depends on Task 5
