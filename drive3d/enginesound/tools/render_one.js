// 1 本だけ回す: node render_one.js '<json>' out.wav   json = { arch, n, vd, redline, idleRpm, rpm, thr, secs, ex:{...}, kJet, ... }
const EngSim = require('../src/engsim.js'); const { writeWav } = require('./wav.js'); const a = JSON.parse(process.argv[2]), SR = 48000;
const cfg = Object.assign({ redline: 7000, idleRpm: 800, in: {}, ex: { pL: 0.42, sL: 0.5, mL: 0.9, tL: 0.9, cat: 0.5, muf: [{ L: 0.35, r: 5, o: 0.12, lp: 0.58, g: 0.978 }, { L: 0.2, r: 4, o: 0.1, lp: 0.62, g: 0.98 }], dual: false, xp: 0, ng: 0 } }, a);
const E = new EngSim(SR, cfg), N = Math.round((a.secs || 4) * SR), out = new Float32Array(N); let th = 0; E.setTemps(a.thr === undefined ? 1 : a.thr, true);
for (let i = 0; i < N; i += 256) { const rpm = a.rpm, thr = a.thr === undefined ? 1 : a.thr; E.ctl({ rpm, thr, boostPa: a.boostPa || 0 }); if (((i / 256) | 0) % 48 === 0) E.setTemps(E.load); for (let j = 0; j < 256 && i + j < N; j++) { th += rpm * 6 / SR; if (th >= 720) th -= 720; out[i + j] = E.step(th); } }
let pk = 0; for (let i = SR; i < N; i++) pk = Math.max(pk, Math.abs(out[i])); writeWav(process.argv[3], out.map(v => v * 0.5 / (pk || 1)), SR);
