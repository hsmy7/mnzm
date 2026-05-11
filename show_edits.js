const fs=require('fs');
const f=process.argv[2];
const lines=fs.readFileSync(f,'utf-8').split('\n').filter(l=>l.trim());
for(const line of lines){try{
const obj=JSON.parse(line);if(obj.type!=='assistant')continue;
const c=obj.message?.content;if(!Array.isArray(c))continue;
for(const it of c){if(it.type!=='tool_use'||(it.name!=='Edit'&&it.name!=='Write'))continue;
const i=it.input;const fn=(i.file_path||'').split('\').pop().split('/').pop();
console.log('=== '+it.name+' | '+fn+' ===');
if(i.old_string)console.log('OLD:\n'+i.old_string);
console.log('---');
if(i.new_string)console.log('NEW:\n'+i.new_string);
if(i.content)console.log('CONTENT len: '+i.content.length);
console.log();
}}
}catch(e){}}
