// 長時間・不規則な運転でも発散しない（NaN・無限大・暴走が無い）ことを確かめる
const EngSim = require('../src/engsim.js'); const SR = 48000; const { mk } = require('./drive_core.js');
const archs = ['i3', 'i4', 'b4', 'i6', 'v6o', 'v8x', 'v8f', 'v10', 'v12']; let seed = 7; const R = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
let allOk = true;
for (const a of archs) {
  const n = EngSim.ARCH[a].n, cfg = mk(a, { n, arch: a, redline: 7500, ex: { pL: 0.4, sL: 0.5, mL: 0.8, tL: 0.8, cat: 0.3, muf: [{ L: 0.3, r: 4, o: 0.15 }], turb: (a === 'v8x' || a === 'i6') ? 1 : 0, dual: a.startsWith('v8'), xp: a === 'v8f' ? 2 : 1 } });
  const E = new EngSim(SR, cfg); let th = 0, bad = 0, mx = 0, mn = 1e9, pmx = 0, t = 0, rpm = 900, thr = 0, tr = 900, tt = 0, fc = 0, bst = 0, als = 0, nextChg = 0, tmx = 0;
  const T0 = Date.now(); const secs = 90;
  for (let i = 0; i < SR * secs; i += 512) {
    t = i / SR; if (t >= nextChg) { tr = 800 + R() * 6800; tt = R() < 0.35 ? 0 : R() < 0.5 ? 1 : R(); fc = R() < 0.2 ? 1 : 0; bst = R() < 0.5 ? R() * 1.2e5 : 0; als = R() < 0.1 ? 1 : 0; nextChg = t + 0.1 + R() * 1.2; }
    rpm += (tr - rpm) * 0.04; E.ctl({ rpm, thr: tt, boostPa: bst, fcut: fc, adv: als ? -44 : undefined, als, rough: R() < 0.1 ? 0.8 : 0, mis: R() < 0.05 ? 0.5 : 0, turbRR: 0.3 }); if (((i / 512) | 0) % 24 === 0) E.setTemps(E.load);
    if (R() < 0.01) E.bang(0.3 + 1.2 * R());
    for (let j = 0; j < 512; j++) { th += rpm * 6 / SR; if (th >= 720) th -= 720; const v = E.step(th); const w = E.o_in, b = E.o_blk; if (!isFinite(v) || !isFinite(w) || !isFinite(b)) bad++; else { mx = Math.max(mx, Math.abs(v)); pmx = Math.max(pmx, Math.abs(b)); } }
    for (const c of E.cyl) { if (!isFinite(c.p) || !isFinite(c.T) || c.p < 0) bad++; tmx = Math.max(tmx, c.T); }
    mn = Math.min(mn, E.pman);
  }
  const ok = bad === 0 && mx < 5e6; if (!ok) allOk = false;
  console.log(a, ok ? 'OK ' : 'FAIL', 'bad', bad, 'max|ex|', mx.toExponential(2), 'max|blk|', pmx.toExponential(2), 'minPman kPa', (mn / 1000).toFixed(0), 'maxT', tmx.toFixed(0), 'time', ((Date.now() - T0) / 1000).toFixed(1) + 's for ' + secs + 's');
}
console.log(allOk ? 'STRESS ALL OK' : 'STRESS FAILED');
