// 気筒の 1 サイクルが実機の典型値に入っているか（吸気管圧・燃焼最大圧・排気弁が開くときの圧力と温度・ブローダウン後の背圧）
const EngSim = require('../src/engsim.js'); const { mk } = require('./drive_core.js'); const SR = 48000;
const cases = [['アイドル 800rpm 全閉', 800, 0, { map: [30, 50], pk: [10, 35], evo: [1.1, 2.6] }], ['部分負荷 3000rpm 25%', 3000, 0.25, { map: [45, 75], pk: [18, 50], evo: [1.8, 4.2] }], ['全開 3000rpm', 3000, 1, { map: [92, 101], pk: [40, 85], evo: [3.5, 6.2] }], ['全開 6500rpm', 6500, 1, { map: [88, 101], pk: [40, 95], evo: [3.5, 6.2] }]];
let ok = true; console.log('arch   case                    MAP(kPa)  pPeak(bar)  pEVO(bar)  Tevo(K)  back-pressure(kPa)  判定');
for (const arch of ['i4', 'i6', 'v8x', 'v12']) for (const [nm, rpm, thr, rg] of cases) {
  const E = new EngSim(SR, mk(arch, { redline: 7000 })); E.setTemps(Math.max(0.3, thr), true); E.warm(800, 0.3); let th = 0; for (let i = 0; i < SR * 1.6; i += 256) { E.ctl({ rpm, thr }); for (let j = 0; j < 256; j++) { th += rpm * 6 / SR; if (th >= 720) th -= 720; E.step(th); } }
  const avg = (a) => Array.from(a).reduce((x, y) => x + y, 0) / a.length, map = E.pman / 1000, pk = avg(E.stat.pPk) / 1e5, ev = avg(E.stat.pEvo) / 1e5, te = avg(E.stat.tEvo), bp = (E.pexM - 101325) / 1000;
  const good = map >= rg.map[0] && map <= rg.map[1] && pk >= rg.pk[0] && pk <= rg.pk[1] && ev >= rg.evo[0] && ev <= rg.evo[1] && bp < 30; if (!good) ok = false;
  console.log(arch.padEnd(6), nm.padEnd(22), map.toFixed(0).padStart(7), pk.toFixed(0).padStart(10), ev.toFixed(1).padStart(10), te.toFixed(0).padStart(8), bp.toFixed(1).padStart(14), '        ', good ? 'OK' : 'NG');
}
console.log(ok ? 'CYCLE ALL OK' : 'CYCLE: SOME NG'); process.exit(ok ? 0 : 1);
