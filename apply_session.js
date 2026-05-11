const fs = require('fs'), path = require('path');
const f = process.argv[2];
if (!f) { process.exit(1); }
const lines = fs.readFileSync(f, 'utf-8').split('\n').filter(l => l.trim());
let a = 0, s = 0;
for (const line of lines) {
    try {
        const obj = JSON.parse(line);
        if (obj.type !== 'assistant') continue;
        const c = obj.message?.content;
        if (!Array.isArray(c)) continue;
        for (const it of c) {
            if (it.type !== 'tool_use') continue;
            const { name, input } = it;
            if (name === 'Write') {
                const fp = input.file_path;
                if (!fp || !input.content) continue;
                const d = path.dirname(fp);
                if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
                fs.writeFileSync(fp, input.content);
                a++; console.log('WRITE: ' + path.basename(fp));
            } else if (name === 'Edit') {
                const fp = input.file_path, o = input.old_string, n = input.new_string;
                if (!fp || o === undefined || n === undefined) continue;
                if (!fs.existsSync(fp)) { s++; continue; }
                let ct = fs.readFileSync(fp, 'utf-8');
                if (ct.includes(n)) { s++; continue; }
                if (!ct.includes(o)) { s++; continue; }
                ct = input.replace_all ? ct.split(o).join(n) : ct.replace(o, n);
                fs.writeFileSync(fp, ct);
                a++;
            }
        }
    } catch(e) {}
}
console.log('Applied: ' + a + ' Skipped: ' + s);
