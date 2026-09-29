# -*- coding: utf-8 -*-
"""B1 C++ 脚本 7：store 头列声明 + column_dirty + gameview_encode + 剩余系统头"""
import os
import re
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android\app\src\main\cpp\gamecore')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

def patch(path, subs):
    s = rd(path)
    for old, new in subs:
        if old not in s:
            print('SKIP already/missing:', path, repr(old[:60]))
            continue
        s = s.replace(old, new)
    wr(path, s)

# ── disciple_store.h：新列声明（attackVariances 有了，补 defenseVariances 与 innate） ──
p = 'include/gamecore/state/disciple_store.h'
s = rd(p)
if 'defenseVariances;' not in s:
    s = s.replace('    std::vector<int32_t> attackVariances;',
                  '    std::vector<int32_t> attackVariances;\n    std::vector<int32_t> defenseVariances;')
if 'innateDamageTypes;' not in s:
    s = s.replace('    std::vector<int32_t> speedVariances;',
                  '    std::vector<int32_t> speedVariances;\n    std::vector<std::string> innateDamageTypes;')
wr(p, s)
print('store.h decl ok')

# ── disciple_store.cpp：物化/追加/reserve/clear/erase/swap 面补新列 ──
p = 'src/disciple_store.cpp'
s = rd(p)
# 行化重命名已做；补 defenseVariances/innateDamageTypes 的对应操作行（对 attackVariances/speedVariances 模式）
lines = s.split('\n')
out = []
for ln in lines:
    out.append(ln)
    st = ln.strip()
    if st.startswith('attackVariances'):
        indent = ln[:len(ln) - len(ln.lstrip())]
        expr = st[len('attackVariances'):]
        # 物化读：d.attackVariance = attackVariances[row]; → 补 defense
        m = re.match(r'\[\s*(\w+)\s*\]\s*=\s*d\.attackVariance\s*;', st)
        if m:
            out.append('%sdefenseVariances[%s] = d.defenseVariance;' % (indent, m.group(1)))
        m2 = re.match(r'=\s*d\.attackVariance\s*;', st)
        if m2 and not m:
            out.append('%sdefenseVariances = d.defenseVariance;' % indent)
    if st.startswith('speedVariances') and 'innateDamageTypes' not in s:
        pass
s = '\n'.join(out)
wr(p, s)
print('store.cpp partial ok')

print('patch7 ok')
