package com.phrag.artdream

/**
 * The page [UploadServer] serves at its token URL. One self-contained file (no external
 * requests). All paths are relative, so it works under any "/<token>/". Avoid the dollar
 * sign here: this is a Kotlin raw string.
 */
object UploadPage {
    val HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Add to fArtSaver</title>
<style>
:root{color-scheme:dark;--bg:#101216;--card:#1b1f27;--text:#f2f3f5;--dim:#aeb4c0;--accent:#f0b35a;--bad:#ff8a80;--ok:#7bd88f}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,sans-serif;padding:20px}
main{max-width:640px;margin:0 auto}
h1{font-size:26px;margin:8px 0 4px}
p{color:var(--dim);margin:4px 0 16px}
.drop{border:2px dashed #3a4150;border-radius:14px;padding:28px 16px;text-align:center;background:var(--card);cursor:pointer}
.drop.over{border-color:var(--accent)}
.drop b{display:block;font-size:18px;margin-bottom:4px}
.drop .picks{justify-content:center;margin-top:14px}
button{font:inherit;border:0;border-radius:10px;padding:10px 16px;background:var(--accent);color:#111318;font-weight:600;cursor:pointer}
button.quiet{background:#2a303b;color:var(--text);font-weight:400;padding:6px 12px}
ul{list-style:none;padding:0;margin:12px 0}
li{background:var(--card);border-radius:10px;padding:10px 12px;margin:6px 0;display:flex;gap:10px;align-items:center}
li .n{flex:1;overflow-wrap:anywhere}
li .s{color:var(--dim);font-size:14px;white-space:nowrap}
li.err .s{color:var(--bad)} li.done .s{color:var(--ok)}
progress{width:90px}
h2{font-size:18px;margin:26px 0 4px}
.row{display:flex;gap:8px;flex-wrap:wrap}
input[type=url]{flex:1;min-width:200px;font:inherit;padding:10px 12px;border-radius:10px;border:1px solid #3a4150;background:var(--card);color:var(--text)}
#ytmsg{margin:8px 0;min-height:1.4em;color:var(--ok)} #ytmsg.bad{color:var(--bad)}
small{color:var(--dim)}
</style></head><body><main>
<h1>Add to fArtSaver</h1>
<p>Pictures and videos you add here join the screensaver. They stay on the TV and nowhere else.</p>
<div class="drop" id="drop" tabindex="0"><b>Drop files here, or choose them</b>
<span style="color:var(--dim)">jpg, png, webp, gif &middot; mp4, mkv, webm, mov</span>
<div class="row picks"><button id="pickmedia" type="button">Photos &amp; videos</button><button id="pickfiles" type="button" class="quiet">Browse files</button></div>
</div>
<input id="pick" type="file" multiple accept="image/*,video/*,.mkv,.webm,.mov,.mp4" hidden>
<input id="pickany" type="file" multiple hidden>
<ul id="queue"></ul>
<h2>YouTube link</h2>
<p style="margin:0 0 8px">&ldquo;Play link&rdquo; streams it in the screensaver (the TV needs internet; no sound unless you turn it on). <span id="dlnote"></span></p>
<div class="row"><input id="yt" type="url" placeholder="https://www.youtube.com/watch?v=&hellip;" autocomplete="off">
<button id="ytplay">Play link</button><button id="ytsave" class="quiet" hidden>Save to TV</button></div>
<div id="ytmsg"></div>
<ul id="jobs"></ul>
<h2>On the TV now</h2>
<small id="quota"></small>
<ul id="have"></ul>
</main>
<script>
var drop=document.getElementById('drop'),pick=document.getElementById('pick'),pickany=document.getElementById('pickany'),
    queue=document.getElementById('queue'),have=document.getElementById('have'),quota=document.getElementById('quota');
function mb(b){return b>=1073741824?(b/1073741824).toFixed(1)+' GB':(b/1048576).toFixed(b<10485760?1:0)+' MB';}
function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;}
function upload(file){return new Promise(function(done){
  var li=el('li'),n=el('span','n',file.name),s=el('span','s','waiting'),p=el('progress');p.max=1;p.value=0;
  li.append(n,p,s);queue.prepend(li);
  var x=new XMLHttpRequest();
  x.open('PUT','upload?name='+encodeURIComponent(file.name));
  x.upload.onprogress=function(e){if(e.lengthComputable){p.value=e.loaded/e.total;s.textContent=Math.round(100*e.loaded/e.total)+'%';}};
  x.onload=function(){
    var r={};try{r=JSON.parse(x.responseText);}catch(e){}
    p.remove();
    if(x.status==200&&r.ok){li.className='done';s.textContent='added';refresh();}
    else{li.className='err';s.textContent=r.error||('failed ('+x.status+')');}
    done();
  };
  x.onerror=function(){p.remove();li.className='err';s.textContent='connection lost';done();};
  x.send(file);
});}
var chain=Promise.resolve();
function add(files){Array.prototype.forEach.call(files,function(f){chain=chain.then(function(){return upload(f);});});}
// Phones open the photo library for an image/video accept list, so "Browse files" uses an
// input with no accept list to get the regular file picker. The server checks the type.
// The inputs sit outside the drop zone: their click() bubbles, and the zone would then open
// the photo picker instead.
drop.onclick=function(){pick.click();};
document.getElementById('pickmedia').onclick=function(e){e.stopPropagation();pick.click();};
document.getElementById('pickfiles').onclick=function(e){e.stopPropagation();pickany.click();};
pick.onchange=function(){add(pick.files);pick.value='';};
pickany.onchange=function(){add(pickany.files);pickany.value='';};
drop.ondragover=function(e){e.preventDefault();drop.classList.add('over');};
drop.ondragleave=function(){drop.classList.remove('over');};
drop.ondrop=function(e){e.preventDefault();drop.classList.remove('over');add(e.dataTransfer.files);};
var yt=document.getElementById('yt'),ytmsg=document.getElementById('ytmsg'),ytsave=document.getElementById('ytsave');
function sendLink(mode){
  var u=yt.value.trim();if(!u)return;
  ytmsg.className='';ytmsg.textContent='Working…';
  fetch('youtube?mode='+mode+'&url='+encodeURIComponent(u),{method:'POST'})
    .then(function(r){return r.json();})
    .then(function(d){
      if(d.ok){ytmsg.textContent=(mode==='download'?'Saving: ':'Added: ')+d.name;yt.value='';refresh();}
      else{ytmsg.className='bad';ytmsg.textContent=d.error||'Failed';}
    }).catch(function(){ytmsg.className='bad';ytmsg.textContent='Connection lost';});
}
document.getElementById('ytplay').onclick=function(){sendLink('stream');};
ytsave.onclick=function(){sendLink('download');};
yt.onkeydown=function(e){if(e.key==='Enter')sendLink('stream');};
function refresh(){
  fetch('list').then(function(r){return r.json();}).then(function(d){
    ytsave.hidden=!d.canDownload;
    if(d.canDownload)document.getElementById('dlnote').textContent='“Save to TV” downloads it to the TV so it plays offline and without internet. YouTube’s terms only allow downloading through its own features, so that’s your call.';
    var jobs=document.getElementById('jobs');jobs.textContent='';
    d.jobs.slice().reverse().forEach(function(j){var li=el('li',j.failed?'err':(j.active?'':'done'));li.append(el('span','n',j.text));jobs.append(li);});
    if(d.jobs.some(function(j){return j.active;}))setTimeout(refresh,1500);
    quota.textContent=mb(d.usedBytes)+' of '+mb(d.quotaBytes)+' used';
    have.textContent='';
    d.items.forEach(function(it){
      var li=el('li'),b=el('button','quiet','Remove');
      b.onclick=function(){b.disabled=true;fetch('delete?key='+encodeURIComponent(it.key),{method:'POST'}).then(refresh);};
      li.append(el('span','n',it.youtube?it.key.replace(/\.yt$/,''):it.key),el('span','s',it.youtube?'YouTube link':(it.video?'video':'picture')+' · '+mb(it.bytes)),b);
      have.append(li);
    });
    if(!d.items.length)have.append(el('li',null,'Nothing yet'));
  });
}
refresh();
</script></body></html>
"""
}
