# Tasks

## 第一阶段：送礼核心逻辑

- [x] Task 1: 创建灵石送礼配置
  - [x] SubTask 1.1: 创建SpiritStoneGiftConfig对象，定义四个档位
  - [x] SubTask 1.2: 定义薄礼(20000灵石/6好感)、厚礼(200000灵石/18好感)、重礼(800000灵石/30好感)、大礼(4000000灵石/40好感)

- [x] Task 2: 创建稀有度基础好感度配置
  - [x] SubTask 2.1: 创建RarityFavorConfig对象
  - [x] SubTask 2.2: 定义凡品(1)、灵品(5)、宝品(15)、玄品(25)、地品(35)、天品(50)的基础好感度

- [x] Task 3: 创建宗门拒绝概率配置
  - [x] SubTask 3.1: 创建SectRejectConfig对象
  - [x] SubTask 3.2: 定义小型宗门拒绝概率（凡品50%、灵品20%、宝品及以上0%）
  - [x] SubTask 3.3: 定义中型宗门拒绝概率（凡品70%、灵品50%、宝品30%、玄品及以上0%）
  - [x] SubTask 3.4: 定义大型宗门拒绝概率（凡品/灵品/宝品100%、玄品30%、地品10%、天品0%）
  - [x] SubTask 3.5: 定义顶级宗门拒绝概率（凡品/灵品/宝品100%、玄品50%、地品20%、天品0%）

## 第二阶段：数据结构扩展

- [x] Task 5: 扩展WorldSect数据结构
  - [x] SubTask 5.1: 在WorldSect中添加lastGiftYear字段
  - [x] SubTask 5.2: 设置lastGiftYear默认值为0

## 第三阶段：送礼方法实现

- [x] Task 6: 扩展GameEngine送礼方法
  - [x] SubTask 6.1: 创建giftSpiritStones方法（支持多档位灵石送礼）
  - [x] SubTask 6.2: 创建giftItem方法（支持功法/装备/丹药送礼，扣除仓库物品）
  - [x] SubTask 6.3: 实现好感度计算（基础好感 × 数量）
  - [x] SubTask 6.4: 实现每年一次送礼限制检查
  - [x] SubTask 6.5: 实现宗门拒绝概率判断
  - [x] SubTask 6.6: 更新interactWithSect方法支持新的送礼逻辑

- [x] Task 7: 实现送礼交互反馈
  - [x] SubTask 7.1: 创建SectResponseTexts对象
  - [x] SubTask 7.2: 定义小型宗门收礼反馈文本（感激涕零）
  - [x] SubTask 7.3: 定义中型宗门收礼反馈文本（客气感激）
  - [x] SubTask 7.4: 定义大型宗门收礼反馈文本（矜持认可）
  - [x] SubTask 7.5: 定义顶级宗门收礼反馈文本（淡然欣赏/勉强接受）
  - [x] SubTask 7.6: 定义拒绝礼物反馈文本

- [x] Task 8: 实现年度重置逻辑
  - [x] SubTask 8.1: 在advanceYear方法中添加重置逻辑
  - [x] SubTask 8.2: 重置所有宗门的lastGiftYear为0

## 第四阶段：UI实现

- [x] Task 9: 创建送礼界面组件
  - [x] SubTask 9.1: 创建GiftDialog组件
  - [x] SubTask 9.2: 实现灵石送礼Tab（显示四个档位按钮）
  - [x] SubTask 9.3: 实现物品送礼Tab（显示仓库功法/装备/丹药列表）
  - [x] SubTask 9.4: 显示"每年只能送一次"提示

- [x] Task 10: 更新宗门详情界面
  - [x] SubTask 10.1: 修改送礼按钮，点击后打开GiftDialog
  - [x] SubTask 10.2: 显示宗门等级送礼偏好提示
  - [x] SubTask 10.3: 显示本年是否已送礼状态

## 第五阶段：测试验证

- [x] Task 11: 功能测试
  - [x] SubTask 11.1: 测试灵石送礼四个档位
  - [x] SubTask 11.2: 测试物品送礼（功法/装备/丹药三种类型）
  - [x] SubTask 11.3: 测试宗门等级差异化拒绝概率
  - [x] SubTask 11.4: 测试每年一次送礼限制
  - [x] SubTask 11.5: 测试宗门拒绝概率
  - [x] SubTask 11.6: 测试年度重置逻辑

# Task Dependencies

- Task 6 depends on Task 1, Task 2, Task 3, Task 5
- Task 7 depends on Task 3
- Task 8 depends on Task 5
- Task 9 depends on Task 6
- Task 10 depends on Task 9
- Task 11 depends on Task 10
