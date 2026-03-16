# Tasks

## 第一阶段：配置修改

- [x] Task 1: 修改GiftConfig配置
  - [x] SubTask 1.1: 在GiftConfig中新增ItemFavorPercentageConfig对象
  - [x] SubTask 1.2: 定义小型宗门百分比配置（凡品30%、灵品60%、宝品200%、玄品300%、地品400%、天品500%）
  - [x] SubTask 1.3: 定义中型宗门百分比配置（凡品20%、灵品50%、宝品100%、玄品150%、地品200%、天品400%）
  - [x] SubTask 1.4: 定义大型宗门百分比配置（玄品40%、地品100%、天品200%）
  - [x] SubTask 1.5: 定义顶级宗门百分比配置（玄品30%、地品80%、天品160%）
  - [x] SubTask 1.6: 添加getItemFavorPercentage(sectLevel, rarity)方法

## 第二阶段：逻辑修改

- [x] Task 2: 修改GameEngine物品送礼逻辑
  - [x] SubTask 2.1: 修改giftItem方法中的好感度计算逻辑
  - [x] SubTask 2.2: 实现百分比计算：新好感度 = 当前好感度 + 当前好感度 * 百分比 / 100
  - [x] SubTask 2.3: 添加好感度下限保护（当前好感度为0时至少增加1点）
  - [x] SubTask 2.4: 确保好感度上限为100
  - [x] SubTask 2.5: 处理多数量物品送礼的好感度累加

## 第三阶段：测试验证

- [x] Task 3: 功能测试
  - [x] SubTask 3.1: 测试小型宗门各稀有度物品的好感度增长
  - [x] SubTask 3.2: 测试中型宗门各稀有度物品的好感度增长
  - [x] SubTask 3.3: 测试大型宗门玄品/地品/天品的好感度增长
  - [x] SubTask 3.4: 测试顶级宗门玄品/地品/天品的好感度增长
  - [x] SubTask 3.5: 测试好感度为0时的下限保护
  - [x] SubTask 3.6: 测试好感度上限不超过100
  - [x] SubTask 3.7: 测试多数量物品送礼的好感度累加

# Task Dependencies

- Task 2 depends on Task 1
- Task 3 depends on Task 2
