const { mk, run } = require('./drive_core.js'); const { writeWav } = require('./wav.js'); const fs = require('fs');
const archs = ['i3', 'i4', 'b4', 'i5', 'i6', 'v6', 'v6o', 'v8x', 'v8f', 'v10', 'v12']; const rpm = +process.argv[2] || 3000; const out = {};
for (const a of archs) { const n = require('../src/engsim.js').ARCH[a].n; const cfg = mk(a, { n, vd: n <= 4 ? 0.5 : n <= 6 ? 0.5 : 0.55, arch: a, redline: 7000, ex: { pL: 0.42, sL: 0.5, mL: 0.8, tL: 0.8, cat: 0.35, muf: [{ L: 0.35, r: 4, o: 0.15 }], ng: 0, xp: a === 'v8x' ? 0 : 0, dual: false } });
  const r = run(cfg, () => ({ rpm, thr: 1 }), 4, null); let pk = 0; for (let i = 48000; i < r.out.length; i++) pk = Math.max(pk, Math.abs(r.out[i])); const o = r.out.map(v => v * 0.5 / pk); writeWav('../out/ord_' + a + '.wav', o, 48000); }
