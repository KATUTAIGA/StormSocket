const EngSim = require('../src/engsim.js'); const { writeWav } = require('./wav.js'); const { mk } = require('./drive_core.js'); const SR = 48000;
function run(name, opts) {
  const cfg = mk('i6', { redline: 7500, ex: { pL: 0.42, sL: 0.5, mL: 0.8, tL: 0.8, cat: 0.2, muf: [{ L: 0.3, r: 3, o: 0.12 }], turb: 1 } }), E = new EngSim(SR, cfg); E.setTemps(1, true); const N = SR * 6, out = new Float32Array(N); let th = 0, rpm = 5500, nb = 0, next = 0;
  for (let i = 0; i < N; i += 256) { const t = i / SR, lift = t > 2.0; rpm = lift ? Math.max(3200, 5500 - (t - 2.0) * 700) : 5500; const als = lift && opts.als;
    E.ctl({ rpm, thr: lift ? (als ? 0.25 : 0) : 1, boostPa: lift ? 0.6e5 : 0.9e5, fcut: lift && !als ? 1 : 0, adv: als ? -44 : undefined, als, mis: als ? 0.3 : 0, turbRR: 0.3 }); if (((i / 256) | 0) % 48 === 0) E.setTemps(E.load);
    if (lift && opts.pops && t >= next) { E.bang(0.5 + Math.random()); next = t + 0.08 + Math.random() * 0.25; nb++; }
    for (let j = 0; j < 256; j++) { th += rpm * 6 / SR; if (th >= 720) th -= 720; out[i + j] = E.step(th); } }
  let rw = 0, rl = 0, pl = 0; for (let i = SR * 1; i < SR * 2; i++) rw += out[i] ** 2; for (let i = SR * 3; i < SR * 6; i++) { rl += out[i] ** 2; pl = Math.max(pl, Math.abs(out[i])); } rw = Math.sqrt(rw / SR); rl = Math.sqrt(rl / (3 * SR));
  let pk = 0; for (let i = 0; i < N; i++) pk = Math.max(pk, Math.abs(out[i])); writeWav('../out/' + name + '.wav', out.map(v => v * 0.6 / pk), SR);
  console.log(name, 'WOT rms', rw.toFixed(0), '| lift rms', rl.toFixed(0), 'peak', pl.toFixed(0), '(peak/WOTrms', (pl / rw).toFixed(1) + ')', 'bangs', nb, 'misfires', E.stat.mis);
}
run('lift_fuelcut', {}); run('lift_pops', { pops: 1 }); run('lift_als', { als: 1 }); run('lift_als_pops', { als: 1, pops: 1 });
