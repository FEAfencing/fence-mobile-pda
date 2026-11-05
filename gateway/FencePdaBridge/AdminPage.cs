namespace FencePdaBridge;

public static class AdminPage
{
    public const string Html = """
<!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fence! PDA Bridge</title>
<style>
body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#101820;color:#eef}
header{background:#c1121f;padding:14px 22px;font-size:20px;font-weight:600}
main{display:grid;grid-template-columns:minmax(300px,380px) 1fr;gap:18px;padding:18px}
section{background:#1b2733;border-radius:10px;padding:16px}
h2{margin:0 0 10px;font-size:16px;color:#ffb703}
img{background:#fff;padding:8px;border-radius:8px;width:260px;height:260px}
table{width:100%;border-collapse:collapse;font-size:13px}td,th{padding:5px 6px;border-bottom:1px solid #2c3b4a;text-align:left}
button,select{background:#2a9d8f;border:0;color:#fff;padding:6px 12px;border-radius:6px;cursor:pointer}
button.r{background:#c1121f}.ok{color:#8ce99a}.ko{color:#ff8787}small{color:#9ab}code{color:#ffd166;word-break:break-all}
@media(max-width:800px){main{grid-template-columns:1fr}}
</style></head><body>
<header>Fence! Mobile PDA — Bridge</header>
<main>
<div>
<section><h2>Associa uno smartphone</h2>
<p><small>Indirizzo del computer visibile dai telefoni:</small> <select id="ip"></select></p>
<img id="qr" alt="QR"><p><small>Il QR e' monouso e scade dopo pochi minuti. Nell'app: "Scansiona QR".</small></p>
<button onclick="newQr()">Nuovo QR</button></section>
<section style="margin-top:18px"><h2>Stato</h2><div id="status">…</div></section>
</div>
<div>
<section><h2>Dispositivi associati</h2><table id="devs"></table></section>
<section style="margin-top:18px"><h2>Registro operazioni</h2><table id="audit"></table></section>
</div>
</main>
<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function newQr(){$('#qr').src='/admin/qr.png?ip='+encodeURIComponent($('#ip').value)+'&t='+Date.now();}
async function load(){
 const s=await (await fetch('/admin/api/status')).json();
 if(!$('#ip').options.length){s.ips.forEach(i=>$('#ip').add(new Option(i,i)));newQr();$('#ip').onchange=newQr;}
 $('#status').innerHTML='Versione '+esc(s.version)+'<br>Porta API HTTPS: <b>'+s.port+'</b><br>Impronta certificato:<br><code>'+esc(s.fingerprint)+'</code><br>Import automatico su Assalti: <b>'+(s.autoImport?'attivo':'disattivato')+'</b><br>PIN responsabile: <b>'+(s.supervisorPinSet?'impostato':'NON impostato (correzioni disabilitate)')+'</b><hr>'+
  Object.entries(s.db).map(([k,v])=>'<div>'+esc(k)+': <span class="'+(String(v).startsWith('ERRORE')?'ko':'ok')+'">'+esc(v)+'</span></div>').join('');
 const d=await (await fetch('/admin/api/devices')).json();
 $('#devs').innerHTML='<tr><th>PDA</th><th>Modello</th><th>Ultimo contatto</th><th>Batt.</th><th></th></tr>'+d.map(x=>'<tr><td><b>'+esc(x.id)+'</b></td><td>'+esc(x.model)+'</td><td>'+esc((x.lastSeen||'').replace('T',' ').slice(0,19))+'</td><td>'+(x.battery||'')+'</td><td>'+(x.revoked?'<span class="ko">revocato</span>':'<button class="r" onclick="rev(\''+esc(x.id)+'\')">Revoca</button>')+'</td></tr>').join('');
 const a=await (await fetch('/admin/api/audit')).json();
 $('#audit').innerHTML='<tr><th>Ora (UTC)</th><th>PDA</th><th>Azione</th><th>Dettaglio</th></tr>'+a.map(x=>'<tr><td>'+esc(x.at.replace('T',' ').slice(0,19))+'</td><td>'+esc(x.dev)+'</td><td>'+esc(x.action)+'</td><td>'+esc(x.detail)+'</td></tr>').join('');
}
async function rev(id){if(confirm('Revocare '+id+'?')){await fetch('/admin/api/revoke/'+encodeURIComponent(id),{method:'POST'});load();}}
load();setInterval(load,5000);
</script></body></html>
""";
}
