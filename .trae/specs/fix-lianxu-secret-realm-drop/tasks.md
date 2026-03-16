# Tasks

- [x] Task 1: 修复 `getRarityRangeByRealm` 方法中的境界品阶映射
  - [x] SubTask 1.1: 将 `avgRealm >= 9.0` 作为炼气期条件
  - [x] SubTask 1.2: 将 `avgRealm >= 8.0` 作为筑基期条件
  - [x] SubTask 1.3: 将 `avgRealm >= 7.0` 作为金丹期条件
  - [x] SubTask 1.4: 将 `avgRealm >= 6.0` 作为元婴期条件
  - [x] SubTask 1.5: 将 `avgRealm >= 5.0` 作为化神期条件，返回 `Pair(2, 3)`（灵品-宝品）
  - [x] SubTask 1.6: 将 `avgRealm >= 4.0` 作为炼虚期条件，返回 `Pair(3, 5)`（宝品-地品）
  - [x] SubTask 1.7: 将 `avgRealm >= 3.0` 作为合体期条件
  - [x] SubTask 1.8: 将 `avgRealm >= 2.0` 作为大乘期条件
  - [x] SubTask 1.9: 将 `avgRealm >= 1.0` 作为渡劫期条件，返回 `Pair(5, 6)`（地品-天品）
  - [x] SubTask 1.10: 更新注释使其与实际逻辑一致

# Task Dependencies
- 无依赖关系，直接修复即可
