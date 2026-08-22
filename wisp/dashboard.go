package main

import "net/http"

const uiHTML = `<!doctype html>
<html lang="en">
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta charset="utf-8">
<title>Wisp AI Trader</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b1020;color:#eef;margin:0;padding:20px}
main{max-width:900px;margin:auto}.card{background:#151d33;border:1px solid #293452;border-radius:16px;padding:18px;margin:12px 0}
button{border:0;border-radius:10px;padding:10px 14px;margin:5px;cursor:pointer}.ok{background:#2dd4bf}.no{background:#fb7185}
.muted{color:#9aa7c1}.row{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.pill{padding:5px 9px;border-radius:999px;background:#263252}
</style>
</head>
<body><main>
<h1>Wisp AI Trader</h1>
<p class="muted">Paper trading dashboard — approvals never send real exchange orders.</p>
<section><h2>Pending / recent proposals</h2><div id="proposals">Loading…</div></section>
<section><h2>Paper executions</h2><div id="executions">Loading…</div></section>
</main>
<script>
async function api(path){const r=await fetch(path);if(!r.ok)throw new Error(await r.text());return r.json()}
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function approve(id){await fetch('/approve?id='+encodeURIComponent(id));load()}
async function deny(id){await fetch('/deny?id='+encodeURIComponent(id));load()}
async function load(){
 try{
 const ps=await api('/proposals');
 document.querySelector('#proposals').innerHTML=ps.length?ps.slice().reverse().map(p=>`<div class="card"><div class="row"><b>${esc(p.action).toUpperCase()} ${esc(p.market)}</b><span class="pill">${esc(p.status)}</span></div><p>Amount: ${Number(p.quote_amount).toFixed(2)} · Confidence: ${(Number(p.confidence)*100).toFixed(1)}%</p><p class="muted">${esc(p.reason)}</p>${p.status==='pending'?`<button class="ok" onclick="approve('${esc(p.id)}')">Approve & Execute Paper</button><button class="no" onclick="deny('${esc(p.id)}')">Reject</button>`:''}</div>`).join(''):'<p class="muted">No proposals yet.</p>';
 const xs=await api('/executions');
 document.querySelector('#executions').innerHTML=xs.length?xs.slice().reverse().map(x=>`<div class="card"><b>${esc(x.action).toUpperCase()} ${esc(x.market)}</b><p>${Number(x.quote_amount).toFixed(2)} · ${esc(x.status)}</p><small class="muted">${esc(x.simulated_ref)}</small></div>`).join(''):'<p class="muted">No paper executions yet.</p>';
 }catch(e){document.querySelector('#proposals').textContent='Dashboard error: '+e.message}
}
load();setInterval(load,5000)
</script>
</body></html>`

func dashboardHandler(html string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(html))
	}
}
