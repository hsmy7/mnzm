# -*- coding: utf-8 -*-
"""为跨包调用方自动补扩展函数 import（caller_report 驱动）。"""
import json
import os
import re

r = json.load(open('/tmp/caller_report.json'))
BS = chr(92)
need_import = {}
for fn, d in r.items():
    if not d['main']:
        continue
    imp = 'import %s.%s' % (d['pkg'], fn)
    for line in d['main']:
        m = re.search(r'(java[\\/].*?\.kt):', line)
        if not m:
            continue
        f = m.group(1).replace(BS, '/')
        rel = f.split('java/', 1)[-1]
        need_import.setdefault('android/core/engine/src/main/java/' + rel,
                               set()).add(imp)

for path, imps in sorted(need_import.items()):
    if not path.endswith('.kt') or not os.path.isfile(path):
        print('skip', path)
        continue
    t = open(path, encoding='utf-8').read()
    add = sorted(i for i in imps if i not in t)
    if not add:
        continue
    lines = t.split('\n')
    last = max(i for i, l in enumerate(lines) if l.startswith('import '))
    for k, imp in enumerate(add):
        lines.insert(last + 1 + k, imp)
    open(path, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
    print(os.path.basename(path), '+', len(add), add)
