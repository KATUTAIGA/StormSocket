window.__secs=115; window.__kind='attack';
(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const car = window.__car || 'supra'; game.setCar(HW.CARS.findIndex((c) => c.id === car), 0); await sleep(300);
  if (window.__tune) { const T = HW.TUNE, t = game.tunes[car] = T.blankTune(); Object.assign(t, window.__tune); game.retune(); await sleep(300); }
  game.setRoute('circuit_fuji'); await sleep(2000); if (game.mode !== 'drive') game.startDrive(); await sleep(1000);
  HW.Race.cfg.n = 1; HW.Race.cfg.quali = 0; HW.Race.cfg.laps = 2; HW.Race.start(game, window.__kind || 'race'); await sleep(300); HW.RAP.set(game, true);
  game.renderer.render = () => {}; const ring = [];
  if (window.__fps) { const sim = game.simulate.bind(game); let acc = 0; const fdt = 1 / window.__fps; game.simulate = function (dt) { acc += dt; if (acc + 1e-9 < fdt) return; acc -= fdt; return sim(fdt); }; }
  const P = game.P, S = { crash: 0, maxB: 0, maxAF: 0, maxAR: 0, maxUse: 0, us: 0, os: 0, n: 0, lap: null, snaps: [] }; const t0 = performance.now(); let lastLap = 0;
  for (let i = 0; i < (window.__secs || 120) * 10; i++) { await sleep(window.__dt || 100); const w = P.tyW4; if (P.crash) S.crash++; S.n++;
    if (w) { S.maxB = Math.max(S.maxB, Math.abs(P.tyBeta || 0)); S.maxAF = Math.max(S.maxAF, Math.abs(w[0].al), Math.abs(w[1].al)); S.maxAR = Math.max(S.maxAR, Math.abs(w[2].al), Math.abs(w[3].al)); S.maxUse = Math.max(S.maxUse, ...w.map((x) => x.use)); }
    S.us += P.us || 0; S.os += P.os || 0;
    if (w) { ring.push((i / 10).toFixed(1) + 's ' + Math.round(P.v * 3.6) + 'km/h sc' + (P.sc || 0).toFixed(2) + ' δ' + ((P.delta || 0) * 57.3).toFixed(1) + ' thr' + (P.thr || 0).toFixed(2) + ' brk' + (P.brk || 0).toFixed(2) + ' β' + (P.tyBeta || 0).toFixed(1) + ' r' + ((P.yawR || 0) * 57.3).toFixed(0) + ' αF' + w[0].al.toFixed(1) + ' αR' + w[2].al.toFixed(1) + '/' + w[3].al.toFixed(1) + ' Fz' + w.map((x) => (x.fz / 1000).toFixed(1)).join('/') + ' us' + (P.us||0).toFixed(2) + ' os' + (P.os||0).toFixed(2) + ' kap' + (P.kap||0).toFixed(2)); if (ring.length > 70) ring.shift(); }
    if (P.crash && !S.pre) S.pre = ring.slice();
    if (w) ring[ring.length - 1] += ' d' + (P.d || 0).toFixed(1) + ' cap' + (HW.RAP.cap || 0) + ' dbg' + JSON.stringify((HW.RAP.dbg || []).map((x) => +(+x).toFixed(1)));
    if (Math.abs(P.d || 0) > 6.5 && !S.off) S.off = ring.slice();
    if (i % 10 === 0) (S.tr || (S.tr = [])).push([i / 10, Math.round(P.s), Math.round(P.v * 3.6), +(P.d || 0).toFixed(1), +((P.thr || 0)).toFixed(2), +((P.brk || 0)).toFixed(2), +(P.tyBeta || 0).toFixed(1), +((P.sc || 0)).toFixed(2), +((HW.RAP && HW.RAP.gAd) || 0).toFixed(3)]);
    if (i % 50 === 0 && w) S.snaps.push(Math.round(P.v * 3.6) + 'km/h β' + (P.tyBeta || 0).toFixed(1) + ' αF' + w[0].al.toFixed(1) + '/' + w[1].al.toFixed(1) + ' αR' + w[2].al.toFixed(1) + '/' + w[3].al.toFixed(1) + ' Fz' + w.map((x) => (x.fz / 1000).toFixed(1)).join('/') + ' use' + w.map((x) => Math.round(x.use * 100)).join('/'));
    const st = HW.Race.st; if (st && st.lapTimes && st.lapTimes.length && !S.lap) S.lap = st.lapTimes.slice(); if (st && st.me && st.me.laps > lastLap) { lastLap = st.me.laps; }
  }
  const st = HW.Race.st; S.lapInfo = st ? JSON.stringify({ laps: st.lapsDone, times: st.lapTimes, best: st.best }) : null; S.us = +(S.us / S.n).toFixed(3); S.os = +(S.os / S.n).toFixed(3);
  return JSON.stringify(S, null, 1); })()
