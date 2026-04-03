#!/usr/bin/env python3
import re

# Read the file
with open('app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace all occurrences of result.log. with battleResult.log.
content = content.replace('result.log.', 'battleResult.log.')

# Replace all occurrences of result.victory with battleResult.victory
content = content.replace('result.victory', 'battleResult.victory')

# Replace all occurrences of result.battle with battleResult.battle
content = content.replace('result.battle', 'battleResult.battle')

# Replace processEquipmentNurtureAfterBattle(disciples, result,
content = content.replace('processEquipmentNurtureAfterBattle(disciples, result,', 'processEquipmentNurtureAfterBattle(disciples, battleResult,')

# Replace val result = BattleSystem.executeBattle(battle) with val battleResult = BattleSystem.executeBattle(battle)
content = content.replace('val result = BattleSystem.executeBattle(battle)', 'val battleResult = BattleSystem.executeBattle(battle)')

# Replace TeamStatus.EXPLORING with ExplorationStatus.EXPLORING
content = content.replace('TeamStatus.EXPLORING', 'ExplorationStatus.EXPLORING')

# Write the file back
with open('app/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print("File updated successfully")
