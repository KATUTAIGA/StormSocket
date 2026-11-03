// engine-sim の基準音と同じ条件（回転・スロットルの時間変化 CSV）で、自作の物理モデル（コア単体）を回して WAV にする
// usage: node render_core_ref.js <ref_out_dir> <outdir> [engine ids...]
const fs = require('fs'), path = require('path'); const EngSim = require('../src/engsim.js'); const { writeWav } = require('./wav.js');
const REF = path.resolve(process.argv[2]), OUT = path.resolve(process.argv[3]); fs.mkdirSync(OUT, { recursive: true }); const SR = 48000;
const ARCH = { i4_vtec: 'i4', i6_2jz: 'i6', v6_60: 'v6', v6_even: 'v6', v6_odd: 'v6o', v8_ls: 'v8x', v8_f136: 'v8f', v10_lfa: 'v10', v12_412: 'v12' }; const RACE = { i4_vtec: 1, v10_lfa: 1, v8_f136: 1, v12_412: 1 };
const ids = process.argv.slice(4).length ? process.argv.slice(4) : Object.keys(ARCH); const scns = ['idle', 'wot3000', 'part25', 'wot80', 'sweep', 'decel', 'decel_fuelcut'];
const OVR = process.env.PXO ? JSON.parse(process.env.PXO) : {};
for (const id of ids) {
  const ej = JSON.parse(fs.readFileSync(path.join(REF, id, 'engine.json'))), n = ej.cylinder_count, arch = ARCH[id], race = !!RACE[id], nb = ej.bank_count;
  const red = Math.min(ej.redline_rpm, 9500), idle = 900;
  const cfg = Object.assign({ n, arch, vd: ej.displacement_L / n, redline: red, idleRpm: idle, sb: race ? 0.8 : 0.92, cr: race ? 12 : 10.5, vt: race ? { evo: 106, evc: 395, ivo: 330, ivc: 625 } : {},
    ex: { pL: race ? 0.5 : 0.42, sL: 0.5, mL: 0.9, tL: 0.9, cat: race ? 0.15 : 0.5, muf: race ? [{ L: 0.3, r: 2.4, o: 0.1, lp: 0.8, g: 0.99 }] : [{ L: 0.35, r: 5, o: 0.12, lp: 0.58, g: 0.978 }, { L: 0.2, r: 4, o: 0.1, lp: 0.62, g: 0.98 }], dual: n >= 8 || race, xp: race ? 2 : (arch === 'v8x' ? 1 : 0), ng: 0, pD: 0.036 * Math.pow(ej.displacement_L / n / 0.5, 0.33), sD: 0.055 * Math.pow(ej.displacement_L / 3, 0.33) }, in: {} }, OVR);
  for (const sc of scns) {
    const f = path.join(REF, id, sc + '.csv'); if (!fs.existsSync(f)) continue;
    const rows = fs.readFileSync(f, 'utf8').trim().split('\n').slice(1).map(l => l.split(',').map(Number)), T = rows[rows.length - 1][0], N = Math.round(T * SR);
    const E = new EngSim(SR, cfg); E.setTemps(0.5, true); const out = new Float32Array(N); let th = 0, k = 0, rpmF = rows[0][1], fcut = /fuelcut/.test(sc);
    for (let i = 0; i < N; i += 256) {
      const t = i / SR; while (k < rows.length - 2 && rows[k + 1][0] < t) k++; const r0 = rows[k], r1 = rows[k + 1], u = Math.min(1, Math.max(0, (t - r0[0]) / Math.max(1e-6, r1[0] - r0[0]))), rpm = r0[1] + (r1[1] - r0[1]) * u, thr = r0[2] + (r1[2] - r0[2]) * u;
      E.ctl({ rpm, thr: sc.startsWith('decel') ? (t < 0.4 ? 1 : 0) : thr, fcut: fcut && t > 0.4 ? 1 : 0 }); if (((i / 256) | 0) % 48 === 0) E.setTemps(E.load);
      for (let j = 0; j < 256 && i + j < N; j++) { th += rpm * 6 / SR; if (th >= 720) th -= 720; out[i + j] = E.step(th); }
    }
    let pk = 0; for (let i = 0; i < N; i++) pk = Math.max(pk, Math.abs(out[i])); fs.mkdirSync(path.join(OUT, id), { recursive: true }); writeWav(path.join(OUT, id, sc + '.wav'), out.map(v => v * 0.5 / (pk || 1)), SR); fs.writeFileSync(path.join(OUT, id, sc + '.json'), JSON.stringify({ rms: Math.sqrt(out.reduce((a, v) => a + v * v, 0) / N) }));
  }
  console.log('rendered', id, arch);
}
