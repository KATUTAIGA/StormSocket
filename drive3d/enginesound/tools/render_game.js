// usage: node render_game.js <html> <outdir> <spec.json>   spec: [{id, px:true|false, scn:'idle'|'wot3000'|'sweep'|..., secs, inside:0|1, name}]
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path'); const { writeWav } = require('./wav.js');
const THREE = process.env.THREE_JS || '';      // three.min.js をローカルに置いたときのパス（無ければ CDN から読む）
(async () => {
  const html = path.resolve(process.argv[2]), outdir = path.resolve(process.argv[3]), spec = JSON.parse(fs.readFileSync(process.argv[4], 'utf8')); fs.mkdirSync(outdir, { recursive: true });
  const b = await chromium.launch(); const ctx = await b.newContext();
  if (THREE) await ctx.route(/cdnjs\.cloudflare\.com.*three\.min\.js/, r => r.fulfill({ body: fs.readFileSync(THREE), contentType: 'application/javascript' }));
  await ctx.route(/fonts\.(googleapis|gstatic)\.com/, r => r.fulfill({ body: '', contentType: 'text/css' }));
  const p = await ctx.newPage(); const errs = []; p.on('pageerror', e => errs.push('PE ' + (e.stack || e.message).slice(0, 400)));
  await p.goto('file://' + html, { waitUntil: 'load', timeout: 180000 }); await p.waitForTimeout(3000);
  for (const cs of spec) {
    const r = await p.evaluate(async (cs) => {
      const HW = window.HW, sr = 48000, prof = HW.SND[cs.id]; if (!prof) return { err: 'no profile ' + cs.id };
      const S = new HW.EngineSynth(sr), P = S.p, redline = cs.redline || (prof.cyl >= 10 ? 9000 : prof.cyl === 8 ? 7000 : prof.cyl === 6 ? 7200 : 7000), idle = cs.idle || prof.idleRpm || 800;
      P.prof = prof; P.snd = cs.snd || {}; P.idle = idle; P.redline = redline; P.inside = cs.inside || 0; P.vol = 1; P.ev = 0; P.rough = cs.rough || 0; P.sx = cs.sx || undefined;
      let tb = null; if (prof.tb && !cs.noturbo) { const s = prof.tb; tb = { fl: s.fl * 1.5, fh: Math.min(12500, s.fh * 1.45), lv: s.lv * 0.7, tone: 0.5, hiss: 0.9, fall: 0.6, bov: s.bov * 0.8, dur: 0.36, fc: 6500, flut: 0, twin: !!s.twin, recirc: true, wg: 0 }; if (cs.als) tb.als = true; } if (cs.tb) tb = Object.assign(tb || {}, cs.tb); P.tb = tb; P.peakBar = 0.9;
      P.pxc = cs.px ? HW.PX.cfgFor(prof, cs.spec || null, cs.id) : null; if (cs.px && cs.pxo && P.pxc) Object.assign(P.pxc, cs.pxo);
      const sc = { idle: (t) => [idle, 0], wot3000: (t) => [3000, 1], wot6000: (t) => [Math.min(6000, redline * 0.85), 1], part3000: (t) => [3000, 0.25], cruise2500: (t) => [2500, 0.12],
        sweep: (t, T) => [idle + (redline * 0.98 - idle) * Math.min(1, t / T), 1], sweepPart: (t, T) => [idle + (redline * 0.9 - idle) * Math.min(1, t / T), 0.3],
        decel: (t, T) => [Math.max(idle * 1.5, redline * 0.85 - (redline * 0.85 - 2000) * Math.min(1, t / (T * 0.6))), t < 0.4 ? 1 : 0], blip: (t) => { const k = t % 2; return [idle + (redline * 0.6 - idle) * (k < 0.5 ? k / 0.5 : Math.max(0, 1 - (k - 0.5) / 0.7)), k < 0.5 ? 1 : 0]; },
        lift: (t, T) => [redline * 0.7 - (redline * 0.7 - 2500) * Math.min(1, t / T), t < 1.0 ? 1 : 0], limiter: (t) => [redline * 1.0, 1] }[cs.scn];
      const secs = cs.secs || 6, N = Math.round(secs * sr), blk = cs.blk || 2048, out = new Float32Array(N), rp = []; let boost = 0, ld0 = 0;
      for (let i = 0; i < N; i += blk) {
        const t = i / sr, [rpm, load] = sc(t, secs); rp.push(+rpm.toFixed(1)); P.rpm = rpm; P.load = load; P.thrE = undefined; P.lim = cs.scn === 'limiter' && rpm >= redline * 0.985;
        const bt = tb ? Math.min(1, rpm / (redline * 0.55)) * load : 0; boost += (bt - boost) * 0.12; P.boost = boost; P.boostPk = boost; P.shaftN = undefined;
        const o = new Float32Array(Math.min(blk, N - i)); S.render(o, o.length, null, null); out.set(o, i);
      }
      let bad = 0, pk = 0; for (let i = 0; i < N; i++) { const v = out[i]; if (!isFinite(v)) bad++; else pk = Math.max(pk, Math.abs(v)); }
      let s = ''; const bytes = new Uint8Array(out.buffer); const CH = 0x8000; for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
      return { b64: btoa(s), bad, pk, rp, blk, sr, redline, idle, pxOn: !!S.pxM, ms: 0 };
    }, cs);
    if (r.err) { console.log(cs.name, 'ERR', r.err); continue; }
    const buf = Buffer.from(r.b64, 'base64'), f32 = new Float32Array(buf.buffer, buf.byteOffset, buf.length / 4); const nm = cs.name || (cs.id + '_' + (cs.px ? 'px' : 'leg') + '_' + cs.scn);
    writeWav(path.join(outdir, nm + '.wav'), f32, r.sr); fs.writeFileSync(path.join(outdir, nm + '.json'), JSON.stringify({ rpm: r.rp, blk: r.blk, sr: r.sr, redline: r.redline, idle: r.idle, cs }));
    console.log(nm, 'peak', r.pk.toFixed(3), 'bad', r.bad, 'px', r.pxOn);
  }
  if (errs.length) console.log('ERRORS', errs.slice(0, 5).join('\n')); await b.close();
})();
