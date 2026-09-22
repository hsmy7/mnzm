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

p = 'core/data/src/main/java/com/xianxia/sect/data/backup/SaveFileManager.kt'
sub(p, r'    // =+\r?\n    // 信息查询\r?\n    // =+\r?\n\r?\n', '', 'SFM-empty-section')

with open('c:/Mnzm/XianxiaSectNative/.clash-repair/_editlog4.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(log) + '\n')