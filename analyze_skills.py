import json
from collections import Counter

with open('android/app/src/main/assets/data/manuals.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

all_skills = []
for cat in ['attackManuals', 'defenseManuals', 'supportManuals', 'mindManuals']:
    manuals = data.get(cat, [])
    for m in manuals:
        if 'skillName' in m and m['skillName']:
            skill = {
                'manualName': m.get('name',''),
                'skillName': m['skillName'],
                'skillType': m.get('skillType',''),
                'skillDamageType': m.get('skillDamageType',''),
                'damageMultiplier': m.get('skillDamageMultiplier', 0),
                'hits': m.get('skillHits', 0),
                'isAoe': m.get('skillIsAoe', False),
                'healPercent': m.get('skillHealPercent', 0),
                'healType': m.get('skillHealType',''),
                'buffType': m.get('skillBuffType'),
                'buffValue': m.get('skillBuffValue', 0),
                'buffDuration': m.get('skillBuffDuration', 0),
                'buffs': m.get('skillBuffs', []),
                'targetScope': m.get('skillTargetScope', ''),
            }
            all_skills.append(skill)

print('=== Skill Effect Analysis ===')
print('Total skills:', len(all_skills))
print()

# Pure damage
pure_attack = [s for s in all_skills if s['damageMultiplier'] > 0 and s['healPercent'] == 0 and not s['buffType'] and not s['buffs']]
print('1. Pure damage:', len(pure_attack))
print('   single physical:', len([s for s in pure_attack if s['skillDamageType'] == 'physical' and not s['isAoe']]))
print('   aoe physical:', len([s for s in pure_attack if s['skillDamageType'] == 'physical' and s['isAoe']]))
print('   single magic:', len([s for s in pure_attack if s['skillDamageType'] == 'magic' and not s['isAoe']]))
print('   aoe magic:', len([s for s in pure_attack if s['skillDamageType'] == 'magic' and s['isAoe']]))
multi = [s for s in pure_attack if s['hits'] > 1]
print('   multi-hit:', len(multi))

# Heal
heal = [s for s in all_skills if s['healPercent'] > 0]
print()
print('2. Heal:', len(heal))
for h in heal[:5]:
    print('  ', h['skillName'], '| heal', h['healPercent'], '%', h['healType'], '| scope:', h['targetScope'])

# Single buff
single_buff = [s for s in all_skills if s['buffType']]
print()
print('3. Single buff:', len(single_buff))
bt = Counter(s['buffType'] for s in single_buff)
for k, v in bt.most_common():
    print('  ', k, ':', v)

# Multi-buff
multi_buff = [s for s in all_skills if s['buffs'] and len(s['buffs']) > 0]
print()
print('4. Multi-buff:', len(multi_buff))
all_buff_types = Counter()
for s in multi_buff:
    for b in s['buffs']:
        all_buff_types[b['type']] += 1
for k, v in all_buff_types.most_common():
    print('  ', k, ':', v)

# Damage+buff
dmg_buff = [s for s in all_skills if s['damageMultiplier'] > 0 and (s['buffType'] or s['buffs'])]
print()
print('5. Damage + buff/debuff:', len(dmg_buff))

# Target scope
scopes = Counter(s['targetScope'] for s in all_skills if s['targetScope'])
print()
print('6. Target scopes:')
for k, v in scopes.most_common():
    print('  ', k, ':', v)

# Some sample debuff skills
debuf = [s for s in all_skills if s['buffs'] and any(b['type'].endswith('_reduce') for b in s['buffs'])]
print()
print('7. Debuff skills:', len(debuf))
for d in debuf[:5]:
    debuffs = [b for b in d['buffs'] if b['type'].endswith('_reduce')]
    for b in debuffs:
        print('  ', d['skillName'], '->', b['type'], b['value'], b['duration'])

# Status effects
status = [s for s in all_skills if s['buffs'] and any(b['type'] in ('poison','burn','stun','freeze','silence','taunt') for b in s['buffs'])]
print()
print('8. Status effect skills:', len(status))
