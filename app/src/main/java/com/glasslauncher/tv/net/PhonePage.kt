package com.glasslauncher.tv.net

/**
 * The "Manage from phone" page itself (served by [PhoneServer]): one small page, no libraries, nothing from the
 * internet. It is laid out as Settings on the TV is: a home list of sections, each a page of rows under headings,
 * long lists on their own pages ("Section › Page"), a search that finds any row, and every value typed in a small
 * window. Rows are built from the TV's state (/api/state) each time something changes. The look is Glassio's:
 * liquid glass panes over the setup's soft lights (the TV sends that picture, /bg.jpg), always dark, burgundy accent.
 *
 * Plain JavaScript in the old style, with no dollar sign before a name or a brace: this is a Kotlin string.
 */
internal object PhonePage {
    const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark"><meta name="theme-color" content="__C__"><meta name="apple-mobile-web-app-capable" content="yes"><meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"><meta name="apple-mobile-web-app-title" content="Glassio">
<link rel="manifest" href="/manifest.webmanifest?t=__T__"><link rel="apple-touch-icon" href="/icon.png?t=__T__"><link rel="icon" href="/icon.png?t=__T__"><title>Glassio</title><style>
/* Liquid glass over Glassio's own lights (the setup's picture, sent by the TV), always dark; the accent is burgundy. */
:root{color-scheme:dark;--ink:#fff;--sub:rgba(255,255,255,.64);--line:rgba(255,255,255,.12);--field:rgba(255,255,255,.11);--accent:#8c1c3a;--hi:#e08aa0;--red:#ff9a8f;--green:#6fe7a4;--pic:none}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%;background:__C__ var(--pic) center/cover no-repeat}
body{margin:0;min-height:100vh;color:var(--ink);font:16px/1.35 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-font-smoothing:antialiased}
body:before{content:"";position:fixed;inset:-4%;z-index:-1;background:linear-gradient(rgba(5,6,11,.28),rgba(5,6,11,.5)),var(--pic) center/cover no-repeat,#05060b}
button,input,textarea{font:inherit;color:inherit}button{appearance:none;border:0;background:none;padding:0;cursor:pointer;text-align:inherit}
header{position:sticky;top:0;z-index:5;padding:max(14px,env(safe-area-inset-top)) 16px 10px;display:flex;align-items:center;gap:6px;max-width:620px;margin:0 auto;
-webkit-backdrop-filter:blur(22px);backdrop-filter:blur(22px);-webkit-mask-image:linear-gradient(#000 78%,transparent);mask-image:linear-gradient(#000 78%,transparent)}
#back{width:36px;height:36px;margin-left:-8px;border-radius:18px;font-size:26px;line-height:1;text-align:center;color:var(--sub)}#back[hidden]{display:none}
h1{font-size:22px;font-weight:650;letter-spacing:-.01em;margin:0;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}h1 span{color:var(--sub);font-weight:500}
main{max-width:620px;margin:0 auto;padding:0 16px max(96px,env(safe-area-inset-bottom))}
.find{width:100%;border-radius:14px;padding:11px 14px;outline:none;margin:2px 0 6px;background:linear-gradient(135deg,rgba(255,255,255,.15),rgba(255,255,255,.06));-webkit-backdrop-filter:blur(26px) saturate(170%);backdrop-filter:blur(26px) saturate(170%);border:1px solid rgba(255,255,255,.16);box-shadow:inset 0 1px 0 rgba(255,255,255,.24),0 10px 30px rgba(0,0,0,.22)}.find::placeholder{color:var(--sub)}.find:focus-visible{outline:2px solid var(--hi)}
h2{font-size:12.5px;font-weight:600;color:var(--sub);margin:22px 14px 7px;letter-spacing:.02em}
.card{border-radius:18px;overflow:hidden;background:linear-gradient(135deg,rgba(255,255,255,.15),rgba(255,255,255,.06));-webkit-backdrop-filter:blur(26px) saturate(170%);backdrop-filter:blur(26px) saturate(170%);border:1px solid rgba(255,255,255,.16);box-shadow:inset 0 1px 0 rgba(255,255,255,.24),0 10px 30px rgba(0,0,0,.22)}.card+.card{margin-top:10px}
.row{display:flex;align-items:center;gap:12px;width:100%;min-height:52px;padding:10px 14px;position:relative}
.row+.row:before{content:"";position:absolute;top:0;left:14px;right:0;border-top:1px solid var(--line)}
button.row:active{background:rgba(255,255,255,.1)}button.row:focus-visible{outline:2px solid var(--hi);outline-offset:-2px}
.rt{flex:1;min-width:0}.rt b{display:block;font-weight:500;overflow:hidden;text-overflow:ellipsis}.rt small{display:block;color:var(--sub);font-size:13px;margin-top:1px;overflow-wrap:anywhere}
.rv{color:var(--sub);font-size:14px;max-width:46%;text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rv.ok{color:var(--green)}
.ch{color:var(--sub);font-size:20px;line-height:1;margin-left:-4px}.red .rt b{color:var(--red)}.hl{animation:hl 1.6s ease-out}
@keyframes hl{0%,40%{background:rgba(224,138,160,.3)}100%{background:transparent}}
@media (prefers-reduced-motion:reduce){.hl{animation:none;outline:2px solid var(--hi);outline-offset:-2px}}
.foot{color:var(--sub);font-size:13px;margin:8px 14px 0}.empty{color:var(--sub);font-size:14px;padding:16px 14px}
dialog{padding:18px 16px 16px;width:calc(100% - 24px);max-width:460px;border-radius:22px;color:var(--ink);background:linear-gradient(135deg,rgba(60,60,72,.62),rgba(24,24,32,.62));
-webkit-backdrop-filter:blur(34px) saturate(180%);backdrop-filter:blur(34px) saturate(180%);border:1px solid rgba(255,255,255,.18);box-shadow:inset 0 1px 0 rgba(255,255,255,.26),0 18px 50px rgba(0,0,0,.45)}
dialog.edit{margin:9vh auto auto}dialog.pick{margin:auto auto max(12px,env(safe-area-inset-bottom))}
dialog:modal{overscroll-behavior:contain;max-height:84vh}dialog::backdrop{background:rgba(3,3,8,.42);overflow:hidden;overscroll-behavior:contain}
dialog h3{font-size:17px;font-weight:650;margin:0 2px 4px}dialog p{color:var(--sub);font-size:13.5px;margin:0 2px 10px;overflow-wrap:anywhere}
dialog label{display:block;color:var(--sub);font-size:12.5px;margin:10px 2px 4px}
dialog input,dialog textarea{display:block;width:100%;border:1px solid rgba(255,255,255,.1);border-radius:12px;background:var(--field);padding:11px 12px;outline:none;margin-top:8px}
dialog input::placeholder,dialog textarea::placeholder{color:rgba(255,255,255,.42)}
dialog textarea{min-height:120px;resize:vertical}dialog input:focus-visible,dialog textarea:focus-visible{outline:2px solid var(--hi)}
dialog pre{background:rgba(0,0,0,.28);border-radius:12px;padding:10px 12px;font:12px/1.45 ui-monospace,Menlo,monospace;white-space:pre-wrap;overflow-wrap:anywhere;max-height:44vh;overflow:auto;margin:0 0 4px}
.bar{display:flex;gap:8px;margin-top:14px;flex-wrap:wrap}.bar button{flex:1;min-height:44px;border-radius:13px;text-align:center;font-weight:600;background:var(--field);border:1px solid rgba(255,255,255,.1);padding:0 12px;white-space:nowrap}
.bar button.pri{background:linear-gradient(180deg,#a3213f,#7d1630);border-color:rgba(255,255,255,.2);box-shadow:inset 0 1px 0 rgba(255,255,255,.28);color:#fff}.bar button:focus-visible{outline:2px solid var(--hi);outline-offset:2px}
.opts{display:flex;flex-direction:column;gap:6px;margin-top:6px}.opts button,.opts a{display:flex;align-items:center;justify-content:space-between;min-height:46px;border-radius:13px;background:var(--field);border:1px solid rgba(255,255,255,.08);padding:0 14px;font-weight:500;color:inherit;text-decoration:none}
.opts .red{color:var(--red)}.opts .on{color:var(--hi);border-color:rgba(224,138,160,.5)}.opts button:focus-visible,.opts a:focus-visible{outline:2px solid var(--hi)}
#msg{position:fixed;left:50%;bottom:max(20px,env(safe-area-inset-bottom));transform:translate(-50%,160%);visibility:hidden;color:#fff;font-size:14.5px;padding:11px 18px;border-radius:22px;transition:transform .25s,visibility .25s;max-width:calc(100% - 32px);text-align:center;z-index:9;
background:rgba(30,30,38,.6);-webkit-backdrop-filter:blur(24px) saturate(170%);backdrop-filter:blur(24px) saturate(170%);border:1px solid rgba(255,255,255,.18)}
#msg.show{transform:translate(-50%,0);visibility:visible}#msg.bad{background:rgba(140,28,58,.72)}@media (prefers-reduced-motion:reduce){#msg{transition:none}}
/* Without backdrop blur the glass would be see-through over the lights: plain dark panes instead. */
@supports not ((backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px))){.card,.find{background:rgba(30,30,38,.9)}dialog{background:#1a1a22}#msg{background:#1e1e26}header{background:#05060b;-webkit-mask-image:none;mask-image:none}}
[hidden]{display:none!important}
</style></head><body>
<header><button id="back" type="button" aria-label="Back" hidden>&#8249;</button><h1 id="title">Glassio</h1></header>
<main><div id="home"><input id="q" class="find" type="search" placeholder="Search" aria-label="Search every setting" autocomplete="off"><div id="menu"></div><p class="foot" id="about"></p></div><div id="view" hidden></div></main>
<div id="msg" role="status"></div>
<dialog id="sheet" class="edit" closedby="any" aria-labelledby="sh-t"><form id="sh-f" method="dialog"><h3 id="sh-t"></h3><p id="sh-n"></p><div id="sh-b"></div>
<div class="bar"><button type="button" id="sh-x">Cancel</button><button type="button" id="sh-alt" hidden></button><button type="submit" id="sh-ok" class="pri">Save</button></div></form></dialog>
<dialog id="pick" class="pick" closedby="any" aria-labelledby="pk-t"><h3 id="pk-t"></h3><p id="pk-n"></p><div class="opts" id="pk-b"></div><div class="bar"><button type="button" id="pk-x">Cancel</button></div></dialog>
<dialog id="show" class="edit" closedby="any" aria-labelledby="pv-t"><h3 id="pv-t"></h3><p id="pv-n"></p><pre id="pv-b" tabindex="0"></pre><div class="bar"><button type="button" id="pv-x">Cancel</button><button type="button" id="pv-ok" class="pri"></button></div></dialog>
<input type="file" id="file" hidden>
<script>
var T=new URLSearchParams(location.search).get('t')||'',D=null,HL='',mt=0,poll=0,busy='';
document.documentElement.style.setProperty('--pic','url("/bg.jpg?t='+encodeURIComponent(T)+'")');
function G(i){return document.getElementById(i)}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined&&x!==null)e.textContent=x;return e}
function say(t,ok,stay){var m=G('msg');m.textContent=t;m.className=(ok?'':'bad ')+'show';clearTimeout(mt);if(!stay)mt=setTimeout(function(){m.className=ok?'':'bad'},3400)}
function api(p,b){return fetch(p+(p.indexOf('?')<0?'?':'&')+'t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function act(p,b,then){return api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){if(then)then(r);load()}return r}).catch(function(){say('Cannot reach the TV. Is the page still open there?',false)})}
function host(u){var m=/^[a-z]+:\/\/([^\/?#]+)/i.exec(u||'');return m?m[1].replace(/^[^@]*@/,''):(u||'')}
function plural(n,w){return n+' '+w+(n==1?'':'s')}

/* ---- Small windows: type a value, pick from a list, read before sending ---- */
function closing(d){d.addEventListener('close',function(){d.returnValue=''});
 if(!('closedBy' in HTMLDialogElement.prototype)){var out=false,o=function(e){var r=d.getBoundingClientRect();return e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom};
  d.addEventListener('pointerdown',function(e){out=o(e)});d.addEventListener('pointerup',function(e){if(out&&o(e))d.close()})}}
['sheet','pick','show'].forEach(function(i){closing(G(i))});
G('sh-x').onclick=function(){G('sheet').close()};G('pk-x').onclick=function(){G('pick').close()};G('pv-x').onclick=function(){G('show').close()};
function edit(o){var d=G('sheet'),b=G('sh-b');G('sh-t').textContent=o.t;G('sh-n').textContent=o.note||'';G('sh-n').hidden=!o.note;b.textContent='';
 var ins=o.f.map(function(f,i){var x=el(f.area?'textarea':'input');if(!f.area)x.type=f.type||'text';x.id='sh-i'+i;x.placeholder=f.ph||'';x.value=f.val||'';
  x.setAttribute('autocapitalize','off');x.setAttribute('autocomplete',f.ac||'off');x.spellcheck=!!f.area;if(f.mode)x.inputMode=f.mode;if(i==0)x.autofocus=true;
  if(f.label){var l=el('label','',f.label);l.htmlFor=x.id;b.appendChild(l)}else x.setAttribute('aria-label',f.ph||o.t);b.appendChild(x);return x});
 function vals(){var r={};o.f.forEach(function(f,i){r[f.k]=f.type=='password'?ins[i].value:ins[i].value.trim()});return r}
 G('sh-ok').textContent=o.ok||'Save';G('sh-f').onsubmit=function(e){e.preventDefault();d.close();o.save(vals())};
 var a=G('sh-alt');a.hidden=!o.alt;if(o.alt){a.textContent=o.alt.t;a.onclick=function(){d.close();o.alt.save(vals())}}
 d.showModal()}
function pick(t,note,opts){var d=G('pick'),b=G('pk-b');G('pk-t').textContent=t;G('pk-n').textContent=note||'';G('pk-n').hidden=!note;b.textContent='';
 opts.forEach(function(o){if(!o)return;var x;if(o.href){x=el('a','',o.t);x.href=o.href;x.target='_blank';x.rel='noopener';x.onclick=function(){d.close()}}
  else{x=el('button',(o.red?'red':'')+(o.on?' on':''),o.t);x.type='button';x.onclick=function(){d.close();o.go()}}
  if(o.on)x.appendChild(el('span','','✓'));b.appendChild(x)});d.showModal()}
function ask(t,note,label,fn){pick(t,note,[{t:label,red:true,go:fn}])}
function show(t,note,text,label,fn){G('pv-t').textContent=t;G('pv-n').textContent=note||'';G('pv-b').textContent=text;G('pv-ok').textContent=label;G('pv-ok').onclick=function(){G('show').close();fn()};G('show').showModal()}
function choose(accept,fn){var f=G('file');f.accept=accept;f.value='';f.onchange=function(){if(f.files[0])fn(f.files[0])};f.click()}

/* ---- Sending files ---- */
function iconUp(pkg,f){say('Sending…',true,true);fetch('/api/icon/upload?pkg='+encodeURIComponent(pkg)+'&t='+encodeURIComponent(T),{method:'POST',body:f}).then(function(r){return r.json()})
 .then(function(r){say(r.ok?r.message:r.error,r.ok);load()}).catch(function(){say('Upload failed',false)})}
function bgUp(f){var x=new XMLHttpRequest();busy='Uploading 0%';draw();
 x.open('POST','/api/bg/upload?use=1&name='+encodeURIComponent(f.name)+'&t='+encodeURIComponent(T));
 x.upload.onprogress=function(e){if(e.lengthComputable){busy='Uploading '+Math.round(e.loaded*100/e.total)+'%';say(busy,true,true)}};
 x.onload=function(){busy='';try{var r=JSON.parse(x.responseText);say(r.ok?r.message:r.error,r.ok)}catch(e){say('Upload failed',false)}load()};
 x.onerror=function(){busy='';say('Upload failed',false);draw()};say('Uploading…',true,true);x.send(f)}
function restore(f){ask('Replace every setting on this TV?','Nothing is merged, and Glassio restarts. Icons you made, background pictures and TV access (ADB) stay as they are on this TV.','Restore this backup',function(){
 f.text().then(function(t){say('Restoring…',true,true);return fetch('/api/restore?t='+encodeURIComponent(T),{method:'POST',headers:{'Content-Type':'application/json'},body:t})})
 .then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok)}).catch(function(){say('Restore failed',false)})})}
function report(){edit({t:'Report a problem',note:'What happened, and what you did just before. You see everything before it is sent.',ok:'Next',f:[{k:'text',area:true,ph:'What went wrong?'}],save:function(v){
 if(v.text.length<10){say('Write a few more words, so the problem can be found',false);return}
 api('/api/report/preview',{text:v.text}).then(function(r){if(!r.ok){say(r.error,false);return}
  show('This is what goes to GitHub','Addresses, names, links and keys were removed. It becomes a public issue.',r.message,'Send',function(){say('Sending…',true,true);
   api('/api/report/send',{}).then(function(s){var m=s.ok?s.message:s.error,u=/https:\/\/\S+/.exec(m||'');say(m.replace(/\s*https:\/\/\S+/,''),s.ok);
    if(u)pick(s.ok?'Sent':'Not sent from the TV',s.ok?'':'You can open the report on GitHub from this phone instead.',[{t:s.ok?'Open the issue':'Open on GitHub',href:u[0]}])})
   .catch(function(){say('Cannot reach the TV',false)})})}).catch(function(){say('Cannot reach the TV',false)})}})}

/* ---- The sections (as on the TV: Settings) ---- */
var BG={carousel:'Titles',image:'Picture',photos:'Photos',aerial:'Videos',video:'One video'};
function txt(t,d,v,o){return {t:t,d:d,v:v,ok:o&&o.okv,k:o&&o.k,go:function(){edit(o.e)}}}
function setOr(b){return b?'Set':'Not set'}
function model(){var d=D,A=d.accounts,N=d.nuvioServer,g=d.bg,P=d.photos,H=d.home;
 function signIn(name,path,note){return function(){edit({t:'Sign in to '+name,note:note,ok:'Sign in',f:[{k:'email',ph:'Email',type:'email',ac:'username',mode:'email'},{k:'password',ph:'Password',type:'password',ac:'current-password'}],save:function(v){act(path,v)}})}}
 function onTv(name){return function(){say(name+' signs in with a code on the TV: Settings → Accounts & profiles',true)}}
 function addonRows(){var r=[{t:'Add an add-on',d:'A manifest.json link, or a stremio:// link',go:function(){edit({t:'Add an add-on',ok:'Add',f:[{k:'url',ph:'https://…/manifest.json',mode:'url'}],save:function(v){if(v.url)act('/api/addon/add',v)}})}},{h:plural(d.addons.length,'add-on')}];
  d.addons.forEach(function(x){r.push({t:x.name,d:x.host,v:x.account?'Nuvio account':'',go:x.account?null:function(){ask(x.name,x.host,'Remove this add-on',function(){act('/api/addon/remove',{url:x.url})})}})});return r}
 function appRows(){return d.apps.map(function(x){return {t:x.label,v:[x.hidden?'Hidden':'',x.custom?'Own icon':''].filter(String).join(' · '),go:function(){pick(x.label,'',[
   {t:'Icon from a link',go:function(){edit({t:'Icon for '+x.label,note:'A link to a picture (png, jpg, webp).',f:[{k:'url',ph:'https://…',mode:'url'}],save:function(v){if(v.url)act('/api/icon/url',{pkg:x.pkg,url:v.url})}})}},
   {t:'Upload an icon from this phone',go:function(){choose('image/*',function(f){iconUp(x.pkg,f)})}},
   x.custom?{t:'Back to the original icon',go:function(){act('/api/icon/reset',{pkg:x.pkg})}}:null,
   x.own?null:{t:x.hidden?'Show in the dock':'Hide from the dock',go:function(){act('/api/app/hide',{pkg:x.pkg})}}])}}})}
 function fileRows(){if(!g.files.length)return [{empty:'Nothing saved on the TV yet.'}];return g.files.map(function(f){return {t:f.name,d:(f.video?'Video':'Picture')+' · '+f.mb+' MB',v:f.used?'In use':'',ok:f.used,go:function(){pick(f.name,'',[
   {t:'Use as the background',go:function(){act('/api/bg/use',{name:f.name})}},{t:'Delete from the TV',red:true,go:function(){act('/api/bg/delete',{name:f.name})}}])}}})}
 function aerialRows(){var r=[{t:'Add a video link',d:d.youtube?'An mp4, or a YouTube video or playlist':'An mp4',go:function(){edit({t:'Add a video',ok:'Add',f:[{k:'url',ph:'https://…/video.mp4',mode:'url'}],save:function(v){if(v.url)act('/api/aerial/add',v)}})}},{h:plural(d.aerial.length,'video')}];
  d.aerial.forEach(function(u){r.push({t:host(u),d:u.replace(/^.*\//,'').replace(/\?.*/,''),go:function(){ask(host(u),u,'Remove this video',function(){act('/api/aerial/remove',{url:u})})}})});return r}
 function camRows(){var r=[{t:'Add a camera',d:'An rtsp:// or http(s):// stream',go:function(){edit({t:'Add a camera',ok:'Add',f:[{k:'name',ph:'Name, e.g. Garden'},{k:'url',ph:'rtsp://user:pass@camera:554/stream',mode:'url'}],save:function(v){if(v.url)act('/api/camera/add',v)}})}},{h:plural(d.cams.length,'camera')}];
  d.cams.forEach(function(x,i){r.push({t:x.name,d:x.host,go:function(){pick(x.name,x.host,[{t:'Rename',go:function(){edit({t:'Camera name',f:[{k:'name',ph:'Name',val:x.name}],save:function(v){if(v.name)act('/api/camera/rename',{i:i,name:v.name})}})}},
   {t:'Remove',red:true,go:function(){act('/api/camera/remove',{i:i})}}])}})});return r}
 var connected=['nuvio','stremio','trakt','simkl','jellyfin'].filter(function(k){return A[k]}).length;
 function prefRows(sec){var l=(d.prefs||[]).filter(function(p){return p.sec==sec});if(!l.length)return [];var r=[{h:'Settings'}];
  l.forEach(function(p){if(p.opts){var cur=p.opts.filter(function(o){return o[0]==p.v})[0];
   r.push({t:p.t,d:p.d,v:cur?cur[1]:p.v,k:'setting',go:function(){pick(p.t,p.d,p.opts.map(function(o){return {t:o[1],on:o[0]==p.v,go:function(){act('/api/pref',{k:p.k,v:o[0]})}}}))}})}
   else{var on=p.v=='true';r.push({t:p.t,d:p.d,v:on?'On':'Off',ok:on,k:'setting',go:function(){act('/api/pref',{k:p.k,v:on?'false':'true'})}})}});return r}
 var M=[
 {id:'accounts',t:'Accounts & profiles',v:connected+' of 5',rows:function(){return [
  {h:'Profile'},
  txt('Name','As Glassio shows this profile. Empty: the name it has in Nuvio',d.profile.name||'As in Nuvio',{e:{t:'Profile name',f:[{k:'name',ph:'Empty: the name in Nuvio',val:d.profile.name}],save:function(v){act('/api/profile/name',v)}}}),
  {h:'Accounts'},
  {t:'Nuvio',d:A.nuvio?'Catalogs, Library and Continue Watching':'With email and password here, or with a code on the TV',v:A.nuvio?'Connected':'Sign in',ok:A.nuvio,go:signIn('Nuvio','/api/nuvio/login','Sent to your Nuvio server only. The password is not kept.')},
  {t:'Stremio',d:'Library and Continue Watching',v:A.stremio?'Connected':'Sign in',ok:A.stremio,go:signIn('Stremio','/api/stremio/login','Sent to Stremio only. The password is not kept.')},
  {t:'Trakt',d:'Signs in with a code on the TV',v:A.trakt?'Connected':'On the TV',ok:A.trakt,go:onTv('Trakt')},
  {t:'Simkl',d:'Signs in with a code on the TV',v:A.simkl?'Connected':'On the TV',ok:A.simkl,go:onTv('Simkl')},
  {t:'Jellyfin',d:A.jellyfin?A.jellyfinWho:'Your own server: its libraries, what you are watching, favourites and search',v:A.jellyfin?'Connected':'Sign in',ok:A.jellyfin,k:'jellyfin server media',
   go:A.jellyfin?function(){ask('Jellyfin',A.jellyfinWho,'Sign out',function(){act('/api/jellyfin/logout',{})})}:function(){edit({t:'Sign in to Jellyfin',note:'Sent to your Jellyfin server only. The password is not kept.',ok:'Sign in',
   f:[{k:'server',label:'Server',ph:'jellyfin.local:8096 or https://…',mode:'url'},{k:'user',label:'User name',ph:'User name',ac:'username'},{k:'password',label:'Password',ph:'Empty if it has none',type:'password',ac:'current-password'}],save:function(v){act('/api/jellyfin/login',v)}})}},
  txt('Simkl client ID','Simkl asks every app for its own: simkl.com/settings/developer',setOr(d.simklClient),{okv:d.simklClient,e:{t:'Simkl client ID',f:[{k:'id',ph:'Client ID'}],save:function(v){act('/api/simkl/client',v)}}}),
  {h:'Your own Nuvio server'},
  txt('Server','Only for a self-hosted Nuvio backend. Changing it signs out',N.custom?N.host:'Official',{k:'supabase self-hosted backend',e:{t:'Nuvio server',note:'Empty address: the official server. The anon key is in Supabase → Project Settings → API. Without a TV sign-in page, sign in above with email and password.',
   f:[{k:'url',label:'Address',ph:'https://your-project.supabase.co',mode:'url'},{k:'key',label:'Anon (public) key',ph:'eyJ…'},{k:'login',label:'TV sign-in page (optional)',ph:'https://…',mode:'url',val:N.login}],save:function(v){act('/api/nuvio/server',v)}}})]}},
 {id:'catalogs',t:'Catalogs & add-ons',v:plural(d.addons.length,'add-on'),rows:function(){return [
  {t:'Add-ons',d:'Where the carousel\'s titles come from',v:d.addons.length,more:'addons'},
  txt('TMDB token','Logos, synopsis, ratings and backdrops',setOr(d.tmdb),{okv:d.tmdb,k:'key logos ratings',e:{t:'TMDB token',note:'themoviedb.org → Settings → API: the long API Read Access Token (starts eyJ…), not the short API Key. Empty removes it.',f:[{k:'token',ph:'eyJ…'}],save:function(v){act('/api/tmdb',v)}}})]},
  subs:{addons:{t:'Add-ons',rows:addonRows}}},
 {id:'dock',t:'Dock & icons',v:plural(d.apps.length-1,'app'),rows:function(){return [
  {t:'Apps',d:'Each app\'s icon, and whether it shows in the dock',v:d.apps.length-1,more:'apps'},
  {t:'Settings button picture',d:'The round button at the top of Glassio',v:d.apps[0].custom?'Own picture':'',go:function(){pick('Settings button picture','',[{t:'Upload a picture from this phone',go:function(){choose('image/*',function(f){iconUp(d.apps[0].pkg,f)})}},d.apps[0].custom?{t:'Remove the picture',red:true,go:function(){act('/api/icon/reset',{pkg:d.apps[0].pkg})}}:null])}}]},
  subs:{apps:{t:'Apps',find:true,rows:function(){return appRows().slice(1)}}}},
 {id:'look',t:'Look & menu',v:d.city,rows:function(){return [
  {h:'Weather'},txt('City','For the weather in the menu and on the clock',d.city,{k:'weather temperature',e:{t:'Weather city',f:[{k:'city',ph:'Baghdad',val:d.city}],save:function(v){if(v.city)act('/api/weather',v)}}})]}},
 {id:'bg',t:'Background & screensaver',v:BG[g.mode]||g.mode,rows:function(){return [
  {h:'Background'},
  {t:'Shows',d:'What is behind the home screen',v:BG[g.mode]||g.mode,k:'mode wallpaper',go:function(){pick('The background shows','',Object.keys(BG).map(function(m){return {t:BG[m],on:g.mode==m,go:function(){act('/api/bg/mode',{mode:m})}}}))}},
  g.mode=='aerial'||g.mode=='video'?{t:'Plays',d:'All the videos (the collections and your videos), only your videos, or one video on a loop',v:g.mode=='video'?'One video':(g.videos=='mine'?'Your videos':'All the videos'),k:'videos background',go:function(){pick('The background plays','',[{t:'All the videos',on:g.mode=='aerial'&&g.videos!='mine',go:function(){act('/api/bg/videos',{videos:'all'})}},{t:'Your videos',on:g.mode=='aerial'&&g.videos=='mine',go:function(){act('/api/bg/videos',{videos:'mine'})}},{t:'One video',on:g.mode=='video',go:function(){act('/api/bg/mode',{mode:'video'})}}])}}:null,
  {t:'Upload from this phone',d:busy||g.downloading&&('Downloading '+g.downloading+'…')||g.last||'A picture or a video, saved on the TV',k:'photo file',go:function(){choose('image/*,video/*',bgUp)}},
  txt('Picture','From a link: used as it is, or saved on the TV',g.image,{k:'url link image wallpaper',e:{t:'Picture link',note:'Use the link as it is, or save the picture on the TV first (it then shows without the internet).',ok:'Use link',f:[{k:'url',ph:'https://… (jpg, png, webp)',mode:'url'}],
   save:function(v){if(v.url)act('/api/bg/link',{kind:'image',url:v.url})},alt:{t:'Save on TV',save:function(v){if(v.url)act('/api/bg/download',{url:v.url,use:true})}}}}),
  txt('Video','From a link: used as it is, or saved on the TV',g.video,{k:'url link mp4',e:{t:'Video link',note:'Use the link as it is, or save the video on the TV first (up to 1.5 GB).',ok:'Use link',f:[{k:'url',ph:'https://… (mp4, mkv, webm)',mode:'url'}],
   save:function(v){if(v.url)act('/api/bg/link',{kind:'video',url:v.url})},alt:{t:'Save on TV',save:function(v){if(v.url)act('/api/bg/download',{url:v.url,use:true})}}}}),
  {t:'Saved on the TV',d:'Pictures and videos you uploaded or saved',v:g.files.length,more:'files'},
  {h:'Photos'},
  {t:'Source',d:'Where the Photos background gets its pictures',v:(P.sources.filter(function(s){return s.id==P.source})[0]||{}).name,k:'bing spotlight unsplash pexels wikimedia paintings art',go:function(){pick('Photos from','',P.sources.map(function(s){return {t:s.name,on:s.id==P.source,go:function(){act('/api/photos/source',{source:s.id})}}}))}},
  txt('Unsplash key','unsplash.com/developers → New application → Access Key',setOr(P.unsplash),{okv:P.unsplash,e:{t:'Unsplash Access Key',note:'Empty removes it.',f:[{k:'key',ph:'Access Key'}],save:function(v){act('/api/unsplash',v)}}}),
  {h:'Videos'},
  {t:'Your videos',d:'Links the screensaver and the background play with the collections',v:d.aerial.length,more:'aerial',k:'aerial mp4 youtube playlist'},
  txt('Pexels key','Free videos from pexels.com for your words: pexels.com/api → your key',d.pexels.key?'Set':'Not set',{okv:d.pexels.key,k:'pexels videos',e:{t:'Pexels key',note:'Kept on the TV only. Empty removes it.',f:[{k:'key',ph:'Your Pexels key'}],save:function(v){act('/api/pexels',v)}}}),
  txt('Pexels words','What its videos show: aerial, ocean, forest, city…',d.pexels.words,{k:'pexels',e:{t:'Pexels words',f:[{k:'words',ph:'aerial',val:d.pexels.words}],save:function(v){act('/api/pexels',{words:v.words})}}})]},
  subs:{files:{t:'Saved on the TV',rows:fileRows},aerial:{t:'Your videos',rows:aerialRows}}},
 {id:'home',t:'Smart home',v:d.cams.length?plural(d.cams.length,'camera'):'',rows:function(){return [
  {h:'Cameras'},{t:'Cameras',d:'Shown in the menu, the corner, multi-view and for the doorbell',v:d.cams.length,more:'cams',k:'rtsp onvif'},
  {h:'Home Assistant'},
  txt('Link and token','Glassio watches the doorbell sensor itself',H.url?host(H.url):'Not set',{okv:!!H.url,k:'doorbell long-lived',e:{t:'Home Assistant',note:'Token: Home Assistant → your profile → Security → Long-lived access tokens. Then pick the sensor on the TV: Settings → Smart home → Doorbell.',
   f:[{k:'url',label:'Address',ph:'http://homeassistant.local:8123',mode:'url',val:H.url},{k:'token',label:'Token (empty keeps the one saved)',ph:'Long-lived access token',type:'password'}],save:function(v){act('/api/ha/doorbell',v)}}}),
  txt('Doorbell words','The title and the line shown with the camera',H.title,{k:'title message',e:{t:'Doorbell words',f:[{k:'title',label:'Title',ph:'Front Door',val:H.title},{k:'message',label:'Message',ph:'Someone is at the door',val:H.message}],save:function(v){act('/api/doorbell/text',v)}}}),
  txt('Webhook','Called when you open an app',setOr(d.ha),{okv:d.ha,e:{t:'Home Assistant webhook',note:'Empty removes it.',f:[{k:'url',ph:'http://homeassistant.local:8123/api/webhook/…',mode:'url'}],save:function(v){act('/api/ha',v)}}}),
  {h:'Philips Hue'},
  txt('Bridge address','The Hue app shows it: Settings → Bridges. Then Connect on the TV',H.hue||'Not set',{okv:!!H.hue,e:{t:'Hue bridge address',f:[{k:'ip',ph:'The address the Hue app shows',val:H.hue,mode:'decimal'}],save:function(v){act('/api/hue',v)}}})]},
  subs:{cams:{t:'Cameras',rows:camRows}}},
 {id:'system',t:'System',v:'',rows:function(){return [
  {h:'Backup'},
  {t:'Download a backup',d:'Every setting of this TV, as one file',go:function(){location.href='/api/backup?t='+encodeURIComponent(T)}},
  {t:'Restore from a file',d:'Replaces everything on this TV',go:function(){choose('.json,application/json',restore)}},
  {note:'Another TV: download the backup here, open this page on the other TV and restore it there. '+d.backup.moves+' '+d.backup.stays},
  {h:'This page on your phone'},
  d.byName?{t:'Address by name',d:'Stays the same when the TV gets another IP from the router. Open it, then Share → Add to Home Screen',v:d.name,k:'web app home screen ip address fixed',go:function(){
   if(location.hostname==d.name){say('This page is already open by its name',true);return}
   say('Looking for the TV by its name…',true,true);
   fetch(d.byName.replace('/?','/api/ping?'),{mode:'cors'}).then(function(r){if(!r.ok)throw 0;location.href=d.byName})
   .catch(function(){say('This phone can\'t find the TV by its name. Give the TV a fixed address in your router instead',false)})}}:null,
  {h:'Help'},
  {t:'Report a problem',d:'Typed here, sent from the TV to GitHub with its log',go:report,k:'bug issue feedback'}]}}];
 M.forEach(function(s){var f=s.rows;s.rows=function(){return f().concat(prefRows(s.id))}});return M}

/* ---- Drawing ---- */
var GROUPS=[['accounts','catalogs'],['dock','look','bg'],['home','system']];
function rowEl(r,sec){var go=r.go||(r.more?function(){location.hash=sec.id+'/'+r.more}:null),b=el(go?'button':'div','row'+(r.red?' red':''));if(go){b.type='button';b.onclick=go}
 var t=el('span','rt');t.appendChild(el('b','',r.t));if(r.d)t.appendChild(el('small','',r.d));b.appendChild(t);
 if(r.v!==undefined&&r.v!==''&&r.v!==null)b.appendChild(el('span','rv'+(r.ok?' ok':''),String(r.v)));if(r.more)b.appendChild(el('span','ch','›'));
 if(HL&&r.t==HL){b.classList.add('hl');setTimeout(function(){b.scrollIntoView({block:'center'})},30);HL=''}return b}
function rows(into,list,sec,filter){var card=null;list.forEach(function(r){
 if(!r)return;
 if(r.h!==undefined){if(!filter){into.appendChild(el('h2','',r.h))}card=null;return}
 if(r.note){if(!filter)into.appendChild(el('p','foot',r.note));card=null;return}
 if(r.empty){if(!filter)into.appendChild(el('p','empty',r.empty));return}
 if(filter&&(r.t+' '+(r.d||'')+' '+(r.v||'')).toLowerCase().indexOf(filter)<0)return;
 if(!card){card=el('div','card');into.appendChild(card)}card.appendChild(rowEl(r,sec))})}
function found(q,M){var out=[];M.forEach(function(s){function add(list,where,hash){list.forEach(function(r){if(!r||!r.t||r.h!==undefined)return;
  if((r.t+' '+(r.d||'')+' '+(r.k||'')+' '+where).toLowerCase().indexOf(q)<0)return;out.push({t:r.t,d:where,v:r.v,ok:r.ok,go:function(){HL=r.t;G('q').value='';if(location.hash=='#'+hash)draw();else location.hash=hash}})})}
 add(s.rows(),s.t,s.id);Object.keys(s.subs||{}).forEach(function(k){add(s.subs[k].rows(),s.t+' › '+s.subs[k].t,s.id+'/'+k)})});return out}
function draw(){if(!D)return;var M=model(),h=location.hash.replace(/^#/,'').split('/'),sec=M.filter(function(s){return s.id==h[0]})[0],sub=sec&&sec.subs&&sec.subs[h[1]];
 G('back').hidden=!sec;G('home').hidden=!!sec;G('view').hidden=!sec;var ti=G('title');ti.textContent='';if(sec)G('menu').textContent='';
 if(!sec){ti.textContent='Glassio';var m=G('menu'),q=G('q').value.trim().toLowerCase();m.textContent='';
  if(q){var f=found(q,M);if(f.length)rows(m,f,null);else m.appendChild(el('p','empty','Nothing found for "'+q+'"'))}
  else GROUPS.forEach(function(g){var c=el('div','card');c.style.marginTop='12px';g.forEach(function(id){var s=M.filter(function(x){return x.id==id})[0];c.appendChild(rowEl({t:s.t,v:s.v,more:1,go:function(){location.hash=s.id}},s))});m.appendChild(c)});
  G('about').textContent='Glassio '+D.version+' · '+D.tv+' · This page works while Settings → System → Manage from phone keeps it on (Always: whenever Glassio runs). To keep it as an app: Share → Add to Home Screen.';return}
 var v=G('view'),keep=sub&&sub.find&&G('sf')?G('sf').value:'';v.textContent='';
 if(sub){ti.appendChild(el('span','',sec.t+' › '));ti.appendChild(document.createTextNode(sub.t));
  if(sub.find){var i=el('input','find');i.id='sf';i.type='search';i.placeholder='Find';i.setAttribute('aria-label','Find in this list');i.value=keep;var box=el('div');i.oninput=function(){box.textContent='';rows(box,sub.rows(),sec,i.value.trim().toLowerCase())};v.appendChild(i);v.appendChild(box);rows(box,sub.rows(),sec,keep.trim().toLowerCase())}
  else rows(v,sub.rows(),sec)}
 else{ti.textContent=sec.t;rows(v,sec.rows(),sec)}}
function load(){return api('/api/state').then(function(d){D=d;draw();clearTimeout(poll);if(d.bg.downloading)poll=setTimeout(load,2000)}).catch(function(){say('Cannot reach the TV. Is the page still open there?',false)})}
G('back').onclick=function(){var h=location.hash.replace(/^#/,'').split('/');location.hash=h.length>1?h[0]:''};
G('q').oninput=draw;window.addEventListener('hashchange',function(){window.scrollTo(0,0);draw()});
document.addEventListener('visibilitychange',function(){if(!document.hidden)load()});
load();
</script></body></html>"""

    /** A small page with one big button: pick a picture on the phone, and it becomes Glassio's settings button. */
    const val BUTTON = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark"><meta name="theme-color" content="#05060b"><link rel="icon" href="data:,"><title>Glassio · Settings button</title><style>
:root{color-scheme:dark;--text:#fff;--sub:rgba(255,255,255,.64);--ok:#6fe7a4;--bad:#ff9a8f;--pic:none}
*{box-sizing:border-box}html{background:#05060b}body{margin:0;min-height:100vh;color:var(--text);font:17px/1.4 system-ui,-apple-system,sans-serif;
display:flex;align-items:center;justify-content:center;padding:24px 16px}
body:before{content:"";position:fixed;inset:-4%;z-index:-1;background:linear-gradient(rgba(5,6,11,.28),rgba(5,6,11,.5)),var(--pic) center/cover no-repeat,#05060b}
.card{width:100%;max-width:380px;border-radius:26px;padding:28px 22px;text-align:center;background:linear-gradient(135deg,rgba(255,255,255,.15),rgba(255,255,255,.06));-webkit-backdrop-filter:blur(26px) saturate(170%);backdrop-filter:blur(26px) saturate(170%);border:1px solid rgba(255,255,255,.16);box-shadow:inset 0 1px 0 rgba(255,255,255,.24),0 10px 30px rgba(0,0,0,.22)}
@supports not ((backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px))){.card{background:rgba(30,30,38,.9)}}
h1{font-size:22px;margin:0 0 6px}p{margin:0;color:var(--sub);font-size:15px}
.pv{width:96px;height:96px;margin:22px auto;border-radius:22px;background:rgba(255,255,255,.1);display:flex;align-items:center;justify-content:center;overflow:hidden}
.pv img{width:100%;height:100%;object-fit:cover}.pv svg{width:40px;height:40px;fill:var(--sub)}
button{width:100%;border:1px solid rgba(255,255,255,.2);border-radius:16px;padding:16px;font:600 17px system-ui,-apple-system,sans-serif;background:linear-gradient(180deg,#a3213f,#7d1630);box-shadow:inset 0 1px 0 rgba(255,255,255,.28);color:#fff}
button:active{opacity:.8}button[disabled]{opacity:.5}button:focus-visible{outline:2px solid #e08aa0;outline-offset:2px}
.msg{margin-top:14px;font-size:15px;min-height:21px}.ok{color:var(--ok)}.bad{color:var(--bad)}
</style></head><body><div class="card">
<h1>Settings button</h1><p>Choose a picture: it becomes the button at the top of Glassio, on your TV.</p>
<div class="pv" id="pv"><svg viewBox="0 0 16 16"><path d="M16 13.25A1.75 1.75 0 0 1 14.25 15H1.75A1.75 1.75 0 0 1 0 13.25V2.75C0 1.784.784 1 1.75 1h12.5c.966 0 1.75.784 1.75 1.75ZM1.75 2.5a.25.25 0 0 0-.25.25v10.5c0 .138.112.25.25.25h.94l.03-.03 6.077-6.078a1.75 1.75 0 0 1 2.412-.06L14.5 10.31V2.75a.25.25 0 0 0-.25-.25Zm12.5 11a.25.25 0 0 0 .25-.25v-.917l-4.298-3.889a.25.25 0 0 0-.344.009L4.81 13.5ZM7 6a2 2 0 1 1-3.999.001A2 2 0 0 1 7 6ZM5.5 6a.5.5 0 1 0-1 0 .5.5 0 0 0 1 0Z"/></svg></div>
<input type="file" id="f" accept="image/*" hidden>
<button id="b" onclick="document.getElementById('f').click()">Upload a picture</button>
<div class="msg" id="m" role="status"></div></div>
<script>
var T=new URLSearchParams(location.search).get('t')||'';document.documentElement.style.setProperty('--pic','url("/bg.jpg?t='+encodeURIComponent(T)+'")');
var f=document.getElementById('f'),b=document.getElementById('b'),m=document.getElementById('m');
function say(t,c){m.textContent=t;m.className='msg '+(c||'')}
f.onchange=function(){var x=f.files[0];if(!x)return;b.disabled=true;say('Sending…');
var im=new Image();im.onload=function(){
// Made small on the phone first (a photo can be many MB): 512 px on its longer side, as PNG.
var s=Math.min(1,512/Math.max(im.width,im.height)),c=document.createElement('canvas');c.width=Math.round(im.width*s);c.height=Math.round(im.height*s);
c.getContext('2d').drawImage(im,0,0,c.width,c.height);var pv=document.getElementById('pv'),i=new Image();i.alt='';i.src=c.toDataURL();pv.textContent='';pv.appendChild(i);
c.toBlob(function(bl){fetch('/api/icon/upload?pkg=glassio.settingsbutton&t='+encodeURIComponent(T),{method:'POST',body:bl})
.then(function(r){return r.json()}).then(function(r){say(r.ok?'Done ✓  Look at your TV':(r.error||'Failed'),r.ok?'ok':'bad');b.disabled=false;b.textContent='Choose another'})
.catch(function(){say('Couldn\'t reach the TV. Same Wi-Fi?','bad');b.disabled=false})},'image/png')};
im.onerror=function(){say('That file is not a picture','bad');b.disabled=false};im.src=URL.createObjectURL(x);f.value=''}
</script></body></html>"""
}
