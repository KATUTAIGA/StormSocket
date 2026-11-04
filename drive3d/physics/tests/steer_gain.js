(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), out = [];
  game.renderer.render = () => {};
  game.setCar(HW.CARS.findIndex((c) => c.id === (window.__car || 'supra')), 0); await sleep(200);
  game.setRoute('circuit_oval'); await sleep(1800); if (game.mode !== 'drive') game.startDrive(); await sleep(800);
  const P = game.P; P.cruise = false; P.lka = false; if (game.AP && game.AP.on && game.autoToggle) game.autoToggle();
  const sim = game.simulate.bind(game); let FIX = null;
  game.simulate = function (dt) { if (FIX) { P.v = FIX.v; P.sc = FIX.sc; P.d = 0; P.psi = 0; P.crash = null; } const r = sim(dt); if (FIX) { FIX.n++; if (FIX.n > FIX.skip) { FIX.ay += P.v * P.yaw; FIX.m++; FIX.b += P.tyBeta || 0; } } return r; };
  for (const v of [15, 25, 35, 45]) for (const sc of [0.3, 0.6, 0.85]) {
    FIX = { v, sc, n: 0, skip: 90, ay: 0, m: 0, b: 0 }; await sleep(2500);
    const gU = P.ty5 ? P.ty5.gU : 0; out.push([v, sc, +(FIX.ay / FIX.m).toFixed(2), +(sc * gU).toFixed(2), +((FIX.ay / FIX.m) / (sc * gU)).toFixed(3), +(FIX.b / FIX.m).toFixed(2), +(P.ty5 ? P.ty5.trim || 0 : 0).toFixed(4)]);
  }
  FIX = null; return JSON.stringify(out); })()
