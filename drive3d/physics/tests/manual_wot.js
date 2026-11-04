(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), res = {};
  game.renderer.render = () => {};
  for (const [name, car, tune] of (window.__cases || [['stock', 'supra', null]])) {
    game.setCar(HW.CARS.findIndex((c) => c.id === car), 0); await sleep(200);
    const T = HW.TUNE, t = game.tunes[car] = T.blankTune(); if (tune) Object.assign(t, tune); game.retune(); await sleep(200);
    game.setRoute(window.__route || 'circuit_oval'); await sleep(1800); if (game.mode !== 'drive') game.startDrive(); await sleep(800);
    const P = game.P, k = game.keys = game.keys || {}; P.cruise = false; P.lka = false; if (game.AP && game.AP.on && game.autoToggle) game.autoToggle();
    const L = []; const sample = (tag) => { const w = P.tyW4 || []; L.push(tag + ' ' + Math.round(P.v * 3.6) + 'km/h g' + (P.gear !== undefined ? P.gear : (game.pt && game.pt.gear)) + ' β' + (P.tyBeta || 0).toFixed(1) + ' r' + ((P.yawR || P.yaw || 0) * 57.3).toFixed(0) + ' ay' + ((P.aLat || 0) / 9.8).toFixed(2) + 'g αF' + (w[0] ? w[0].al.toFixed(1) : '-') + ' αR' + (w[2] ? w[2].al.toFixed(1) + '/' + w[3].al.toFixed(1) : '-') + ' kapR' + (w[2] ? w[2].kap.toFixed(0) : '-') + '% os' + (P.os || 0).toFixed(2) + ' us' + (P.us || 0).toFixed(2) + ' d' + (P.d || 0).toFixed(1) + (P.crash ? ' CRASH hits' + (P.hits || 0) : '')); };
    // 1) 加速して 70km/h へ
    k.accel = true; for (let i = 0; i < 40 && P.v < 70 / 3.6; i++) await sleep(100); k.accel = false;
    sample('start'); // 2) 右へ切ったまま全開 2.5 秒
    k.right = true; k.accel = true; for (let i = 0; i < 14; i++) { await sleep(100); if (i % 2 === 1) sample('steer+WOT'); }
    k.right = false; k.accel = false; for (let i = 0; i < 15; i++) await sleep(100); sample('release');
    res[name] = L; }
  return JSON.stringify(res, null, 1); })()
