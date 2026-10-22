const { chromium } = require('playwright');
(async()=>{ const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium',headless:true}); const ctx=await b.newContext({serviceWorkers:'block',viewport:{width:1280,height:900}}); const p=await ctx.newPage();
p.on('pageerror',e=>console.log('PAGE ERROR:',e.message));
await p.goto('http://localhost:8080/login'); await p.fill('#username','grace.pastor_799791'); await p.fill('#password','d4Au6JsxN4'); await p.click('button[type=submit], #loginBtn'); await p.waitForURL(/home|dashboard/i,{timeout:30000}).catch(()=>{}); await p.waitForTimeout(1500);
for (const q of ['Create an income report for this month.','Show the financial report for this month.','Expense report for the last two months','Financial report for 2026','Where is the Tax Report?']) {
  const d=await p.evaluate(q=>fetch('/api/ai-search/data',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query:q})}).then(r=>r.json()),q);
  console.log('> '+q+'\n  ['+(d.intent||'not handled')+'] '+String(d.answer||'').slice(0,150)+(d.rows?' | rows='+d.rows.length:'')+(d.link?' | link='+d.link.url:'')+(d.summary?' | '+JSON.stringify(d.summary):''));
}
// pre-filled report pages
await p.goto('http://localhost:8080/income-report?startDate=2026-10-01&endDate=2026-10-31'); await p.waitForTimeout(2500);
console.log('income-report prefilled:', await p.evaluate(()=>[document.getElementById('startDate').value, document.getElementById('endDate').value, (document.body.innerText.match(/Grand Total[^\n]*/)||[''])[0].slice(0,60)]));
await p.goto('http://localhost:8080/financial-report?year=2026'); await p.waitForTimeout(2500);
console.log('financial-report year:', await p.evaluate(()=>document.getElementById('yearSelect').value), '| rendered:', await p.evaluate(()=>/Net|Total Income/i.test(document.body.innerText)));
await p.goto('http://localhost:8080/transactions-report?startDate=2026-09-01&endDate=2026-10-31'); await p.waitForTimeout(2500);
console.log('transactions-report prefilled:', await p.evaluate(()=>[document.getElementById('startDate').value, document.getElementById('endDate').value, /Net|Total/i.test(document.body.innerText)]));
await b.close(); })().catch(e=>{console.error('FAILED',e.message);process.exit(1);});
