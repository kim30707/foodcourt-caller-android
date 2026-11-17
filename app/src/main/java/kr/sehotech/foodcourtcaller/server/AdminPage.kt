package kr.sehotech.foodcourtcaller.server

/** GET /admin : 원격 관리 페이지(PIN 필요). 데이터는 /admin/api/ 아래 주소로 주고받는다. */
object AdminPage {
    val html = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>호출 방송 관리</title>
<style>
body{font-family:-apple-system,Roboto,'Noto Sans KR',sans-serif;margin:0;padding:12px;background:#f4f5f7;color:#1d1d1f;max-width:720px;margin:auto}
h1{font-size:22px;margin:6px 0 12px}
h2{font-size:17px;margin:0 0 8px;color:#1f4e79}
.card{background:#fff;border-radius:12px;padding:14px 16px;margin-bottom:12px;box-shadow:0 1px 2px rgba(0,0,0,.08)}
table{width:100%;border-collapse:collapse;font-size:14px}
td,th{padding:6px 4px;border-bottom:1px solid #eee;text-align:left;vertical-align:top}
.ok{color:#2e7d32;font-weight:700}.bad{color:#c62828;font-weight:700}.mut{color:#777}
button{font-size:16px;padding:10px 14px;border:0;border-radius:10px;background:#1f4e79;color:#fff;margin:4px 4px 4px 0}
button.gray{background:#888}
input,select{font-size:16px;padding:8px;border:1px solid #ccc;border-radius:8px}
label{display:block;margin:8px 0 2px;font-size:14px;color:#555}
input[type=range]{width:100%}
#msg{position:sticky;top:0;background:#1f4e79;color:#fff;padding:10px;border-radius:10px;display:none;margin-bottom:10px}
</style></head><body>
<h1>호출 방송 · 원격 관리</h1>
<div id="msg"></div>

<div class="card" id="login">
<h2>관리 PIN</h2>
<p class="mut">방송 장치 앱의 [원격 관리]에서 정한 PIN을 넣으세요.</p>
<input id="pin" type="password" inputmode="numeric" placeholder="PIN">
<button onclick="login()">들어가기</button>
</div>

<div id="main" style="display:none">
<div class="card"><h2>방송 장치</h2><div id="dev"></div>
<button onclick="act('restart')">서버 다시 시작</button>
<button onclick="act('check_update')" class="gray">업데이트 확인</button>
<button onclick="logout()" class="gray">이 휴대폰에서 로그아웃</button>
<p class="mut">PIN은 이 휴대폰에 저장되어 다음부터 바로 열립니다. 홈 화면에 추가해 두면 앱처럼 쓸 수 있습니다.</p></div>

<div class="card"><h2>매장 태블릿</h2>
<p class="mut">태블릿이 1분마다 신호를 보냅니다. 3분 넘게 신호가 없으면 빨간색입니다.</p>
<table id="tabs"></table></div>

<div class="card"><h2>테스트 방송</h2>
<select id="store"></select> <button onclick="act('test',{store:val('store')})">테스트 방송</button></div>

<div class="card"><h2>소리</h2>
<label>방송 음량 <b id="v_volume"></b></label><input type="range" min="0" max="100" id="volume" oninput="sv(this)">
<label>목소리 키우기 <b id="v_voiceBoost"></b></label><input type="range" min="0" max="100" id="voiceBoost" oninput="sv(this)">
<label>말하기 속도(%) <b id="v_speechRate"></b></label><input type="range" min="50" max="200" id="speechRate" oninput="sv(this)">
<label>반복 횟수 <b id="v_repeat"></b></label><input type="range" min="1" max="3" id="repeat" oninput="sv(this)">
<label><input type="checkbox" id="chime"> 차임(딩동)</label>
<button onclick="saveSound()">소리 저장</button></div>

<div class="card"><h2>음악 자동 켜기/끄기</h2>
<label><input type="checkbox" id="musicAuto"> 자동 사용</label>
<label>켜는 시각</label><input id="musicOn" placeholder="10:30">
<label>끄는 시각</label><input id="musicOff" placeholder="21:00">
<div><button onclick="saveMusic()">음악 시간 저장</button>
<button class="gray" onclick="act('music_on')">지금 켜기</button>
<button class="gray" onclick="act('music_off')">지금 끄기</button></div>
<div id="musicLast" class="mut"></div></div>

<div class="card"><h2>오류·시스템 기록</h2><table id="events"></table></div>
<div class="card"><h2>최근 방송</h2><table id="calls"></table></div>
</div>

<script>
var PIN='';
try{PIN=localStorage.getItem('pin')||sessionStorage.getItem('pin')||'';}catch(e){}
function val(id){return document.getElementById(id).value;}
function el(id){return document.getElementById(id);}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function msg(t){var m=el('msg');m.textContent=t;m.style.display='block';clearTimeout(window._mt);window._mt=setTimeout(function(){m.style.display='none';},4000);}
function sv(i){el('v_'+i.id).textContent=i.value;}
function api(path,body){
  return fetch(path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json','X-Admin-Pin':PIN},body:body?JSON.stringify(body):undefined})
   .then(function(r){return r.json().then(function(j){if(!r.ok)throw j;return j;});});
}
function errText(j){return {pin_not_set:'방송 장치 앱에서 관리 PIN을 먼저 정하세요',wrong_pin:'PIN이 틀렸습니다',locked:'5번 틀려서 5분 동안 잠겼습니다'}[j&&j.error]||('오류: '+(j&&j.error||'연결 안 됨'));}
function savePin(p){try{if(p)localStorage.setItem('pin',p);else localStorage.removeItem('pin');sessionStorage.removeItem('pin');}catch(e){}}
function login(){PIN=val('pin');savePin(PIN);load(true);}
function logout(){PIN='';savePin('');el('pin').value='';el('login').style.display='block';el('main').style.display='none';msg('로그아웃했습니다');}
function ago(s){return s<60?s+'초 전':s<3600?Math.floor(s/60)+'분 전':Math.floor(s/3600)+'시간 전';}
var filled=false;
function load(first){
  api('/admin/api/state').then(function(j){
    el('login').style.display='none';el('main').style.display='block';
    el('dev').innerHTML='<div><b>'+esc(j.address)+'</b></div><div>버전 '+esc(j.version)+(j.latest&&j.latest!==j.version?' <span class="bad">(최신 '+esc(j.latest)+')</span>':'')+
      ' · 음성 <span class="'+(j.tts==='ready'?'ok':'bad')+'">'+esc(j.ttsLabel)+'</span> · 대기열 '+j.queue+'건 · 켜진 지 '+ago(j.uptimeSec)+'</div>';
    var t='<tr><th>매장</th><th>상태</th><th>버전</th><th>마지막 전송</th></tr>';
    j.devices.forEach(function(d){
      var st=d.online?'<span class="ok">연결 '+ago(d.agoSec)+'</span>':'<span class="bad">신호 없음 '+ago(d.agoSec)+'</span>';
      if(!d.accessibility)st+='<br><span class="bad">키패드 기능 꺼짐</span>';
      if(!d.battery)st+='<br><span class="bad">배터리 제한 켜짐</span>';
      t+='<tr><td>'+esc(d.store)+'<br><span class="mut">'+esc(d.device)+' '+esc(d.ip)+'</span></td><td>'+st+'</td><td'+(d.outdated?' class="bad"':'')+'>'+esc(d.version)+'</td><td>'+esc(d.lastSend)+'</td></tr>';
    });
    if(!j.devices.length)t+='<tr><td colspan=4 class="mut">아직 신호를 보낸 태블릿이 없습니다(태블릿 앱 v0.3.0 이상 필요).</td></tr>';
    el('tabs').innerHTML=t;
    if(!filled||first){
      var s=el('store');s.innerHTML='';j.stores.forEach(function(n){var o=document.createElement('option');o.textContent=n;s.appendChild(o);});
      ['volume','voiceBoost','speechRate','repeat'].forEach(function(k){el(k).value=j.sound[k];sv(el(k));});
      el('chime').checked=j.sound.chime;
      el('musicAuto').checked=j.music.auto;el('musicOn').value=j.music.on;el('musicOff').value=j.music.off;
      filled=true;
    }
    el('musicLast').textContent=j.music.last||'';
    el('events').innerHTML=j.events.map(function(e){return '<tr><td class="mut">'+esc(e.time)+'</td><td>'+esc(e.result)+'</td><td>'+esc(e.note)+'</td></tr>';}).join('')||'<tr><td class="mut">기록 없음</td></tr>';
    el('calls').innerHTML=j.calls.map(function(e){return '<tr><td class="mut">'+esc(e.time)+'</td><td>'+esc(e.store)+'</td><td>'+esc(e.what)+'</td><td>'+esc(e.result)+'</td></tr>';}).join('')||'<tr><td class="mut">기록 없음</td></tr>';
  }).catch(function(e){
    var err=e&&e.error;
    if(!first&&!err){msg('방송 장치에 잠시 연결 안 됨 · 다시 시도 중');return;}
    if(err==='wrong_pin'||err==='pin_not_set'){PIN='';savePin('');}
    el('login').style.display='block';el('main').style.display='none';
    if(first||err)msg(errText(e));
  });
}
function act(a,extra){var b=extra||{};b.action=a;api('/admin/api/action',b).then(function(j){msg(j.message||'완료');setTimeout(load,1500);}).catch(function(e){msg(errText(e));});}
function saveSound(){api('/admin/api/settings',{volume:+val('volume'),voiceBoost:+val('voiceBoost'),speechRate:+val('speechRate'),repeat:+val('repeat'),chime:el('chime').checked}).then(function(){msg('소리 설정 저장');}).catch(function(e){msg(errText(e));});}
function saveMusic(){api('/admin/api/settings',{musicAuto:el('musicAuto').checked,musicOn:val('musicOn'),musicOff:val('musicOff')}).then(function(j){msg(j.message||'저장');}).catch(function(e){msg(errText(e));});}
if(PIN)load(true);
setInterval(function(){if(el('main').style.display!=='none')load(false);},10000);
document.addEventListener('visibilitychange',function(){if(!document.hidden&&PIN)load(false);});
</script></body></html>"""
}
