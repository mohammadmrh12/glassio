#!/usr/bin/env python3
# Collects the English strings the screens show (for translation): string literals of the UI code that read as words.
import re, glob, sys, json
root='app/src/main/java/com/glasslauncher/tv/'
files=sorted(glob.glob(root+'ui/*.kt')+glob.glob(root+'saver/*.kt'))+[root+'data/Settings.kt']
lit=re.compile(r'"((?:[^"\\\n]|\\.)*)"')
drop=re.compile(r'(Glog\.[a-z]|Log\.[a-z]|error|Regex|require|check|Bg\.io|getSharedPreferences|getStringExtra|getBooleanExtra|putExtra|optString|optJSONObject|optJSONArray|optInt|optLong|startsWith|endsWith|removePrefix|removeSuffix|substringAfter|substringBefore|contains|setPackage|parse|label =|key =|TAG =)\s*\(?\s*$')
def unesc(t):
    return t.replace('\\"','"').replace("\\'","'").replace('\\n','\n').replace('\\\\','\\')
out={}
for f in files:
    s=open(f).read()
    s=re.sub(r'(?m)^\s*//.*$','',s); s=re.sub(r'\s//\s.*$','',s,flags=re.M)
    s=re.sub(r'"""[\s\S]*?"""','""',s)
    for m in lit.finditer(s):
        t=m.group(1)
        if '$' in t or not re.search(r'[A-Za-z]{2}',t): continue
        before=s[max(0,m.start()-40):m.start()]
        if drop.search(before): continue
        t=unesc(t)
        k=t.strip()
        k=re.sub(r'^·\s*','',k); k=re.sub(r'^→\s*','',k)
        if len(k)<2: continue
        if re.fullmatch(r'[a-z0-9_.:/,\-+#@%=&?]+',k): continue          # keys, ids, formats
        if ' ' not in k and not k[0].isupper(): continue
        if re.match(r'(https?:|content:|file:|glass[_:]|com\.|android\.|androidx\.)',k): continue
        if re.fullmatch(r'[A-Z0-9_]{3,}',k) and k not in ('OK','HDR','CAM','TV'): continue
        if re.fullmatch(r'#?[0-9A-Fa-f]{6,8}',k): continue
        out.setdefault(k,f.split('/')[-1])
keys=sorted(out, key=lambda k:(out[k],k.lower()))
open('build/i18n/en.json','w').write(json.dumps(keys,ensure_ascii=False,indent=0))
print(len(keys),'strings',sum(len(k.split()) for k in keys),'words')
import collections
print(collections.Counter(out.values()).most_common(8))
