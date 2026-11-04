// usage: THREE=/path/to/three.min.js node harness.js ../../index.html lap_autopilot.js  -> ページの中で script を実行し、結果を表示
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext();
  await ctx.route(/cdnjs\.cloudflare\.com.*three\.min\.js/, r => r.fulfill({ body: fs.readFileSync(process.env.THREE || 'three.min.js'), contentType: 'application/javascript' }));
  await ctx.route(/fonts\.(googleapis|gstatic)\.com/, r => r.fulfill({ body: '', contentType: 'text/css' }));
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push('PE ' + (e.stack || e.message).slice(0, 600)));
  p.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errs.push('CE ' + m.text().slice(0, 600)); });
  await p.goto('file://' + require('path').resolve(process.argv[2]), { waitUntil: 'load', timeout: 180000 });
  await p.waitForTimeout(+(process.env.WAIT || 3000));
  const src = fs.readFileSync(process.argv[3], 'utf8');
  try { const r = await p.evaluate(src); console.log(typeof r === 'string' ? r : JSON.stringify(r, null, 1)); } catch (e) { console.log('EVAL ERR', e.message.slice(0, 2000)); }
  if (errs.length) console.log('ERRORS:\n' + errs.slice(0, 15).join('\n'));
  await b.close();
})();
