const EngSim = require('../src/engsim.js'); const { writeWav } = require('./wav.js');
const SR = 48000;
const base = new Proxy({}, { get: (t, k) => ({ i3: { n: 3, vd: 0.5 }, i4: { n: 4, vd: 0.5 }, b4: { n: 4, vd: 0.5 }, i5: { n: 5, vd: 0.5 }, i6: { n: 6, vd: 0.5 }, v6: { n: 6, vd: 0.5 }, v6o: { n: 6, vd: 0.5 }, v8x: { n: 8, vd: 0.55 }, v8f: { n: 8, vd: 0.56 }, v10: { n: 10, vd: 0.5 }, v12: { n: 12, vd: 0.54 } })[k] });
function mk(arch, o) { const b = base[arch]; return Object.assign({ arch, n: b.n, vd: b.vd, redline: 7000, idleRpm: 800, ex: { pL: 0.45, sL: 0.5, mL: 0.8, tL: 0.8, cat: 0.35, muf: [{ L: 0.35, r: 4, o: 0.15 }], ng: 0, xp: 0 }, in: {} }, o || {}); }
exports.mk = mk;
function run(cfg, sched, secs, outPath, blk) {
  const E = new EngSim(SR, cfg), N = Math.round(secs * SR), out = new Float32Array(N), oi = new Float32Array(N), ob = new Float32Array(N); let th = 0; blk = blk || 256;
  let t0 = process.hrtime.bigint();
  for (let i = 0; i < N; i += blk) {
    const t = i / SR, s = sched(t); E.ctl(s); if (i % (SR / 4) < blk) E.setTemps(E.load);
    for (let j = 0; j < blk && i + j < N; j++) { th += E.rpm * 6 / SR; if (th >= 720) th -= 720; out[i + j] = E.step(th); oi[i + j] = E.o_in; ob[i + j] = E.o_blk; }
  }
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  return { E, out, oi, ob, ms };
}
exports.run = run;
if (require.main === module) {
  const arch = process.argv[2] || 'i4', rpm = +process.argv[3] || 3000, thr = +(process.argv[4] || 1), secs = +(process.argv[5] || 2);
  const r = run(mk(arch), () => ({ rpm, thr }), secs, null);
  const E = r.E; let mx = 0, rms = 0; for (let i = SR; i < r.out.length; i++) { const v = r.out[i]; if (!isFinite(v)) { console.log('NaN!'); break; } mx = Math.max(mx, Math.abs(v)); rms += v * v; } rms = Math.sqrt(rms / (r.out.length - SR));
  console.log(arch, rpm, 'thr', thr, 'time', r.ms.toFixed(0) + 'ms for ' + secs + 's audio (' + (r.ms / secs / 10).toFixed(1) + '% of RT)');
  console.log(' out peak', mx.toFixed(3), 'rms', rms.toFixed(3), 'pman kPa', (E.pman / 1000).toFixed(1), 'pexM kPa', (E.pexM / 1000).toFixed(1), 'mTot', E.mTot.toFixed(4));
  console.log(' pEvo bar', Array.from(E.stat.pEvo).map(v => (v / 1e5).toFixed(1)).join(' '), '| pPk bar', Array.from(E.stat.pPk).map(v => (v / 1e5).toFixed(0)).join(' '), '| Tevo', Array.from(E.stat.tEvo).map(v => v.toFixed(0)).join(' '));
  if (process.argv[6]) { let pk = 0; for (let i = SR; i < r.out.length; i++) pk = Math.max(pk, Math.abs(r.out[i])); writeWav(process.argv[6], r.out.map(v => v * 0.5 / pk), SR); }
}
