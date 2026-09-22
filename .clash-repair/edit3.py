import re
BASE = 'c:/Mnzm/XianxiaSectNative/android/'
log = []

def rd(p):
    with open(BASE + p, encoding='utf-8', newline='') as f:
        return f.read()

def wr(p, s):
    with open(BASE + p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

def sub(p, pat, new, tag, flags=0):
    s = rd(p)
    s2, n = re.subn(pat, new, s, flags=flags)
    if n == 0:
        log.append('MISS ' + tag)
    else:
        wr(p, s2); log.append('OK(%d) %s' % (n, tag))

p = 'core/data/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt'
sub(p, r'        return try \{\n\n', '        return try {\n', 'SF-blank-after-try')
sub(p, r'            val result = engine\.load\(slot\)\n\n            if \(result\.isSuccess\) \{\n            \} else \{\n            \}\n\n            result\.toUnifiedResult\(\)',
       '            val result = engine.load(slot)\n\n            result.toUnifiedResult()', 'SF-empty-if-else')

with open('c:/Mnzm/XianxiaSectNative/.clash-repair/_editlog3.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(log) + '\n')