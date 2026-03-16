# Tasks

## 第一阶段：配置修改

- [x] Task 1: 修改GiftConfig配置
  - [x] SubTask 1.1: 在GiftConfig中新增FavorPercentageConfig对象
  - [x] SubTask 1.2: 定义小型宗门百分比配置（薄礼60%、厚礼100%、重礼200%、大礼300%）
  - [x] SubTask 1.3: 定义中型宗门百分比配置（薄礼50%、厚礼80%、重礼160%、大礼250%）
  - [x] SubTask 1.4: 定义大型宗门百分比配置（重礼100%、大礼160%）
  - [x] SubTask 1.5: 定义顶级宗门百分比配置（重礼80%、大礼50%）
  - [x] SubTask 1.6: 添加getFavorPercentage(sectLevel, tier)方法

## 第二阶段：逻辑修改

- [x] Task 2: 修改GameEngine送礼逻辑
  - [x] SubTask 2.1: 修改giftSpiritStones方法中的好感度计算逻辑
  - [x] SubTask 2.2: 实现百分比计算：新好感度 = 当前好感度 + 当前好感度 * 百分比 / 100
  - [x] SubTask 2.3: 添加好感度下限保护（当前好感度为0时至少增加1点）
  - [x] SubTask 2.4: 确保好感度上限为100

## 第三阶段：测试验证

- [x] Task 3: 功能测试
  - [x] SubTask 3.1: 测试小型宗门四个档位的好感度增长
  - [x] SubTask 3.2: 测试中型宗门四个档位的好感度增长
  - [x] SubTask 3.3: 测试大型宗门重礼和大礼的好感度增长
  - [x] SubTask 3.4: 测试顶级宗门重礼和大礼的好感度增长
  - [x] SubTask 3.5: 测试好感度为0时的下限保护
  - [x] SubTask 3.6: 测试好感度上限不超过100

# Task Dependencies

- Task 2 depends on Task 1
- Task 3 depends on Task 2
