(() => { const HW = window.HW, sr = 48000, res = {};
 for (const id of ['gr86', 'supra', 'ls3', 'c2_lfa', 'c2_countach21', 'c2_veyronjbT']) { const prof = HW.SND[id.replace(/T$/, '')] || HW.SND[id]; if (!prof) { res[id] = 'noprof'; continue; }
  for (const px of [false, true]) { const S = new HW.EngineSynth(sr), P = S.p; P.prof = prof; P.snd = {}; P.idle = 800; P.redline = 7500; P.inside = 0; P.vol = 1; P.ev = 0; P.tb = null; P.pxc = px ? HW.PX.cfgFor(prof, null, id.replace(/T$/, '')) : null; P.rpm = 5000; P.load = 1;
   const o = new Float32Array(2048); S.render(o, 2048, null, null); const t0 = performance.now(); let n = 0; for (let b = 0; b < 100; b++) { P.rpm = 3000 + 3500 * (b / 100); S.render(o, 2048, null, null); n += 2048; } const ms = performance.now() - t0;
   res[id + (px ? '_px' : '_leg')] = +(ms / (n / sr * 1000) * 100).toFixed(1) + '% of RT'; } }
 return JSON.stringify(res); })()
