package kr.sehotech.foodcourtcaller.server

import kr.sehotech.foodcourtcaller.common.StoreConfig
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 방송 폰이 내보내는 웹페이지(상태 페이지, 직원 휴대폰용 예비 입구) */
object WebPages {

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun time(ms: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(ms))

    fun resultText(r: String): String = when (r) {
        "played" -> "방송함"
        "duplicate" -> "중복 무시"
        "dropped" -> "대기열 넘침 버림"
        "error" -> "방송 실패"
        else -> r
    }

    private const val STYLE = """
        body{font-family:-apple-system,Roboto,'Noto Sans KR',sans-serif;margin:0;padding:16px;background:#f4f5f7;color:#1d1d1f}
        h1{font-size:22px;margin:0 0 12px}
        .card{background:#fff;border-radius:12px;padding:14px 16px;margin-bottom:12px;box-shadow:0 1px 2px rgba(0,0,0,.08)}
        table{width:100%;border-collapse:collapse;font-size:14px}
        td,th{padding:6px 4px;border-bottom:1px solid #eee;text-align:left}
        a{color:#1f4e79}
        .big{font-size:28px;font-weight:700}
        .warn{color:#c62828;font-weight:700}
    """

    /** GET / : 상태 페이지 */
    fun statusPage(
        address: String,
        version: String,
        queue: Int,
        ttsLabel: String,
        ttsOk: Boolean,
        stores: List<StoreConfig>,
        recent: List<CallLog.Entry>,
    ): String {
        val rows = recent.joinToString("") {
            "<tr><td>${time(it.time)}</td><td>${esc(it.store)}</td><td>${esc(if (it.test) "테스트" else it.number ?: "")}</td>" +
                "<td>${esc(it.source)}</td><td>${esc(resultText(it.result))}</td></tr>"
        }.ifEmpty { "<tr><td colspan=5>아직 기록이 없습니다.</td></tr>" }
        val links = stores.filter { it.enabled }.joinToString("") {
            "<li><a href=\"/s/${enc(it.name)}\">${esc(it.name)}</a></li>"
        }
        return """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="10">
<title>호출 방송 상태</title><style>$STYLE</style></head><body>
<h1>호출 방송 · 상태</h1>
<div class="card"><div class="big">${esc(address)}</div>
<div>버전 ${esc(version)} · 대기열 ${queue}건 · TTS <span class="${if (ttsOk) "" else "warn"}">${esc(ttsLabel)}</span></div></div>
<div class="card"><b>직원 휴대폰 페이지</b><ul>$links</ul></div>
<div class="card"><b>최근 방송 20건</b>
<table><tr><th>시각</th><th>매장</th><th>번호</th><th>입구</th><th>결과</th></tr>$rows</table></div>
</body></html>"""
    }

    /** GET /s/{매장} : 직원 휴대폰용 예비 입구 */
    fun storePage(store: StoreConfig, tokenFromUrl: String): String {
        val storeJs = JSONObject.quote(store.name)
        val tokenJs = JSONObject.quote(tokenFromUrl)
        return """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>${esc(store.name)} 방송</title>
<style>
body{font-family:-apple-system,Roboto,'Noto Sans KR',sans-serif;margin:0;padding:12px;background:#f4f5f7;color:#1d1d1f;max-width:480px;margin:auto}
h1{font-size:22px;margin:4px 0 10px;text-align:center}
#num{font-size:56px;font-weight:700;text-align:center;background:#fff;border-radius:12px;padding:10px;min-height:68px;letter-spacing:4px}
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:10px 0}
.pad button{font-size:34px;padding:18px 0;border:0;border-radius:12px;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.12)}
.pad button:active{background:#dde6f0}
#send{width:100%;font-size:30px;padding:18px;border:0;border-radius:12px;background:#1f4e79;color:#fff;font-weight:700}
#send:disabled{background:#9aa9b8}
#msg{text-align:center;min-height:26px;margin:8px 0;font-size:18px}
.err{color:#c62828}.ok{color:#2e7d32}
ul{list-style:none;padding:0;margin:0;background:#fff;border-radius:12px}
li{padding:8px 12px;border-bottom:1px solid #eee;font-size:15px}
</style></head><body>
<h1>${esc(store.name)} 호출 방송</h1>
<div id="num"></div>
<div class="pad">
<button onclick="d('1')">1</button><button onclick="d('2')">2</button><button onclick="d('3')">3</button>
<button onclick="d('4')">4</button><button onclick="d('5')">5</button><button onclick="d('6')">6</button>
<button onclick="d('7')">7</button><button onclick="d('8')">8</button><button onclick="d('9')">9</button>
<button onclick="c()">지움</button><button onclick="d('0')">0</button><button onclick="b()">←</button>
</div>
<button id="send" onclick="s()">방송</button>
<div id="msg"></div>
<b>최근 방송</b>
<ul id="log"><li>불러오는 중…</li></ul>
<script>
var STORE=$storeJs, TOKEN=$tokenJs, n='';
function show(){document.getElementById('num').textContent=n;}
function d(x){if(n.length<5){n+=x;show();}}
function b(){n=n.slice(0,-1);show();}
function c(){n='';show();}
function msg(t,cls){var m=document.getElementById('msg');m.textContent=t;m.className=cls||'';}
function s(){
  if(!n){msg('번호를 누르세요','err');return;}
  var btn=document.getElementById('send');btn.disabled=true;
  var num=n, h={'Content-Type':'application/json'}; if(TOKEN)h['X-Token']=TOKEN;
  fetch('/call',{method:'POST',headers:h,body:JSON.stringify({store:STORE,number:num,source:'web',device:'직원 휴대폰',test:false})})
   .then(function(r){return r.json();})
   .then(function(j){
     if(j.ok&&j.duplicate)msg(num+'번은 방금 방송했습니다','ok');
     else if(j.ok)msg(num+'번 방송 요청 완료','ok');
     else msg('실패: '+j.error,'err');
     n='';show();load();
   })
   .catch(function(){msg('방송 장치에 연결 안 됨','err');})
   .finally(function(){setTimeout(function(){btn.disabled=false;},2000);});
}
function load(){
  fetch('/log?limit=5&store='+encodeURIComponent(STORE)).then(function(r){return r.json();}).then(function(j){
    var ul=document.getElementById('log');ul.innerHTML='';
    if(!j.entries.length){ul.innerHTML='<li>아직 기록이 없습니다.</li>';return;}
    j.entries.forEach(function(e){
      var li=document.createElement('li');var t=new Date(e.time);
      li.textContent=t.toLocaleTimeString('ko-KR')+'  '+(e.test?'테스트':(e.number+'번'))+'  '+e.resultText;
      ul.appendChild(li);
    });
  }).catch(function(){});
}
show();load();setInterval(load,5000);
</script></body></html>"""
    }

    fun notFoundPage(name: String): String =
        """<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>없는 매장</title></head>
<body style="font-family:sans-serif;padding:20px"><h2>매장 "${esc(name)}"을(를) 찾을 수 없거나 사용하지 않습니다.</h2>
<a href="/">상태 페이지로</a></body></html>"""
}
