package main

import "net/http"

const uiHTML = `<!doctype html>
<html lang="en"><head>
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta charset="utf-8"><meta name="theme-color" content="#0b1020">
<title>Wisp AI Trader</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{font-family:system-ui,-apple-system,sans-serif;background:#080d1a;color:#eef;margin:0;padding:14px}main{width:min(960px,100%);margin:auto}.top{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.card{background:#121a2b;border:1px solid #273451;border-radius:16px;padding:16px;margin:12px 0;box-shadow:0 8px 24px #0003}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.row{display:flex;justify-content:space-between;gap:10px;align-items:center;flex-wrap:wrap}.muted{color:#9aa7c1}.pill{padding:5px 9px;border-radius:999px;background:#263252}.stat{font-size:1.15rem;font-weight:700}.ok,.no,.refresh{border:0;border-radius:10px;padding:11px 14px;margin:5px;cursor:pointer;font-weight:700}.ok{background:#2dd4bf;color:#061a17}.no{background:#fb7185;color:#24060d}.refresh{background:#33415f;color:#fff}.danger{border-color:#7c2d4b}.mono{font-family:ui-monospace,monospace;font-size:.85rem;overflow-wrap:anywhere}select{background:#0d1424;color:#fff;border:1px solid #34415f;border-radius:10px;padding:10px;width:100%}@media(max-width:640px){body{padding:10px}.grid{grid-template-columns:1fr}.card{padding:13px}button{width:100%;margin:5px 0}.top h1{font-size:1.5rem}}
</style></head><body><main>
<div class="top"><div><h1>Wisp AI Trader</h1><p class="muted">Paper trading · explicit approval · read-only Nobitex connection</p></div><button class="refresh" onclick="load()">Refresh</button></div>
<section class="card"><div class="row"><h2>Read-only Nobitex</h2><span class="pill">NO ORDERS</span></div><div class="grid"><div><label class="muted">Market</label><select id="market"><option>BTCIRT</option><option>BTCUSDT</option><option>ETHIRT</option><option>ETHUSDT</option></select></div><div><div class="muted">Connection</div><div id="nstatus" class="stat">Not tested</div></div></div><div id="nobitex" class="muted" style="margin-top:12px">Set NOBITEX_API_TOKEN on the server, then press Refresh.</div></section>
<section><h2>Pending / recent proposals</h2><div id="proposals">Loading...</div></section>
<section><h2>Paper executions</h2><div id="executions">Loading...</div></section>
</main><script>
async function api(path){const r=await fetch(path);if(!r.ok)throw new Error(await r.text());return r.json()}
function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]})}
async function act(path,id){const r=await fetch(path+'?id='+encodeURIComponent(id));if(!r.ok)throw new Error(await r.text());await load()}
async function load(){
 try{
  const ps=await api('/proposals'),pc=document.querySelector('#proposals');
  pc.innerHTML=ps.length?ps.slice().reverse().map(function(p){var b=p.status==='pending'?'<button class="ok" data-a="'+esc(p.id)+'">Approve & Execute Paper</button><button class="no" data-d="'+esc(p.id)+'">Reject</button>':'';return '<div class="card"><div class="row"><b>'+esc(String(p.action||'').toUpperCase())+' '+esc(p.market)+'</b><span class="pill">'+esc(p.status)+'</span></div><p>Amount: '+Number(p.quote_amount||0).toFixed(2)+' · Confidence: '+(Number(p.confidence||0)*100).toFixed(1)+'%</p><p class="muted">'+esc(p.reason)+'</p>'+b+'</div>'}).join(''):'<p class="muted">No proposals yet.</p>';
  pc.querySelectorAll('[data-a]').forEach(function(b){b.onclick=function(){act('/approve',b.dataset.a)}});pc.querySelectorAll('[data-d]').forEach(function(b){b.onclick=function(){act('/deny',b.dataset.d)}});
  const xs=await api('/executions');document.querySelector('#executions').innerHTML=xs.length?xs.slice().reverse().map(function(x){return '<div class="card"><b>'+esc(String(x.action||'').toUpperCase())+' '+esc(x.market)+'</b><p>'+Number(x.quote_amount||0).toFixed(2)+' · '+esc(x.status)+'</p><small class="muted mono">'+esc(x.simulated_ref)+'</small></div>'}).join(''):'<p class="muted">No paper executions yet.</p>';
  const m=document.querySelector('#market').value,n=await api('/nobitex/readonly?market='+encodeURIComponent(m));document.querySelector('#nstatus').textContent='Connected · read only';document.querySelector('#nobitex').innerHTML='<div class="grid"><div><div class="muted">Market</div><div class="stat">'+esc(n.market)+'</div></div><div><div class="muted">Last price</div><div class="stat">'+esc((n.stats&&n.stats.lastTradePrice)||((n.orderbook&&n.orderbook.lastTradePrice)||'—'))+'</div></div></div><p class="muted">Wallet data received: '+(n.wallets&&Array.isArray(n.wallets.wallets)?n.wallets.wallets.length:'—')+' entries. No trading endpoint is called.</p>';
 }catch(e){document.querySelector('#nstatus').textContent='Unavailable';document.querySelector('#nobitex').textContent=e.message;document.querySelector('#proposals').textContent='Dashboard error: '+e.message}
}
load();setInterval(load,5000)
</script></body></html>`

func dashboardHandler(html string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(html))
	}
}
