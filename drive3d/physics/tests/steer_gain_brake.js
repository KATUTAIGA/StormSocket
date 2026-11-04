(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), out = [];
  game.renderer.render = () => {};
  game.setCar(HW.CARS.findIndex((c) => c.id === (window.__car || 'supra')), 0); await sleep(200);
  game.setRoute('circuit_oval'); await sleep(1800); if (game.mode !== 'drive') game.startDrive(); await sleep(800);
  const P = game.P; P.cruise = false; P.lka = false; if (game.AP && game.AP.on && game.autoToggle) game.autoToggle();
  const sim = game.simulate.bind(game); let FIX = null;
  game.simulate = function (dt) { if (FIX) { P.v = FIX.v; P.sc = FIX.sc; P.d = 0; P.psi = 0; P.crash = null; } const r = sim(dt); if (FIX) { FIX.n++; if (FIX.n > FIX.skip) { FIX.ay += P.v * P.yaw; FIX.m++; FIX.b += P.tyBeta || 0; } } return r; };
  const kk = game.keys = game.keys || {}; for (const br of [0, 1]) for (const v of [25, 35]) for (const sc of [0.6, 0.85]) { kk.brake = !!br;
    FIX = { v, sc, n: 0, skip: 90, ay: 0, m: 0, b: 0 }; await sleep(2500);
    const gU = P.ty5 ? P.ty5.gU : 0; const W = P.ty5 ? P.ty5.w : []; out.push([br, W.map((x) => [Math.round(x.fz), Math.round(x.fx0), Math.round(x.Dx), +(x.rho||0).toFixed(2), Math.round(x.Fymax), Math.round(x.D)]), v, sc, +(FIX.ay / FIX.m).toFixed(2), +(sc * gU).toFixed(2), +((FIX.ay / FIX.m) / (sc * gU)).toFixed(3), +(FIX.b / FIX.m).toFixed(2), +(P.ty5 ? P.ty5.trim || 0 : 0).toFixed(4)]);
  }
  FIX = null; kk.brake = false; return JSON.stringify(out); })()
