const { mk, run } = require('./drive_core.js'); const { writeWav } = require('./wav.js'); const fs = require('fs');
const arch = process.argv[2], idle = +process.argv[3] || 800, red = +process.argv[4] || 7000, secs = +process.argv[5] || 10, outp = process.argv[6];
const sch = (t) => ({ rpm: idle + (red - idle) * Math.min(1, t / secs), thr: 1 });
const r = run(mk(arch, { redline: red, idleRpm: idle }), sch, secs, null); let pk = 0; for (let i = 48000; i < r.out.length; i++) pk = Math.max(pk, Math.abs(r.out[i]));
writeWav(outp, r.out.map(v => v * 0.5 / pk), 48000); fs.writeFileSync(outp.replace('.wav', '.json'), JSON.stringify({ rpm: Array.from({ length: Math.ceil(secs * 10) }, (_, i) => sch(i / 10).rpm), blk: 4800, sr: 48000, idle, redline: red, cs: { id: arch } }));
console.log(arch, 'ms', r.ms.toFixed(0), 'for', secs, 's');
