# Tasks

- [x] Task 1: 修改道具品质概率
  - [x] SubTask 1.1: 在 `generateMerchantItems` 函数中将 `baseProbabilities` 修改为 `listOf(75.0, 60.0, 22.6, 7.0, 2.8, 0.6)`
  - [x] SubTask 1.2: 在 `generateSectTradeItems` 函数中将 `baseProbabilities` 修改为 `listOf(75.0, 60.0, 22.6, 7.0, 2.8, 0.6)`

- [x] Task 2: 修改 GameConfig 中的基础价格
  - [x] SubTask 2.1: 修改 `GameConfig.Rarity.CONFIGS` 中的 `basePrice` 为新价格

- [x] Task 3: 修改物品模型的 basePrice 属性（简化算法）
  - [x] SubTask 3.1: Equipment.basePrice = GameConfig.Rarity.get(rarity).basePrice
  - [x] SubTask 3.2: Manual.basePrice = GameConfig.Rarity.get(rarity).basePrice
  - [x] SubTask 3.3: Pill.basePrice = GameConfig.Rarity.get(rarity).basePrice * 0.8
  - [x] SubTask 3.4: Material.basePrice = GameConfig.Rarity.get(rarity).basePrice * 0.05
  - [x] SubTask 3.5: Herb.basePrice = GameConfig.Rarity.get(rarity).basePrice * 0.05
  - [x] SubTask 3.6: Seed.basePrice = GameConfig.Rarity.get(rarity).basePrice * 0.05

- [x] Task 4: 简化出售价格计算逻辑
  - [x] SubTask 4.1: GameViewModel.kt 中统一使用 `item.basePrice * quantity * 0.8`
  - [x] SubTask 4.2: InventoryScreen.kt 中统一使用 `item.basePrice * quantity * 0.8`
  - [x] SubTask 4.3: GameEngine.listItemsToMerchant 使用 `item.basePrice * 0.8`

# Task Dependencies
- Task 1 和 Task 2 相互独立，可以并行执行
- Task 3 依赖 Task 2
- Task 4 依赖 Task 3
