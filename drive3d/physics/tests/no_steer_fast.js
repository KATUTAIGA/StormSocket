(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), res = [];
  game.renderer.render = () => {}; HW.TY5.on = true; HW.TY5.dbg = window.__dbg || null;
  const car = window.__car || 'supra', route = window.__route || 'drag4000';
  for (let trial = 0; trial < (window.__n || 4); trial++) {
    game.setCar(HW.CARS.findIndex((c) => c.id === car), 0); await sleep(200);
    { const base = HW.CARS.find((c) => c.id === car), mt = HW.NPCTUNE.makeMad(base); if (mt && mt.tune) { game.tunes[car] = JSON.parse(JSON.stringify(mt.tune)); game.retune(); await sleep(200); } }
    game.setRoute(route); await sleep(1500); if (game.mode !== 'drive') game.startDrive(); await sleep(500);
    const R = HW.Race; R.cfg.opp = 'none'; R.cfg.dn = 3; R.cfg.ntune = 'mad'; R.cfg.tree = 'pro';
    R.start(game, 'drag'); await sleep(300); game.P.lka = false; game.P.cruise = false;
    const st = R.st, P = game.P; let maxPsi = 0, maxDev = 0, maxYaw = 0, maxR = 0, tRun = null, tDiv = null, vAtDiv = null;
    for (let i = 0; i < 60 * 150 && st.phase !== 'done'; i++) {
      game.keys.accel = st.phase === 'run' || st.phase === 'tree'; if (game.P.sc) game.P.sc = 0; game.P.lka = false; game.keys.brake = false; game.keys.left = false; game.keys.right = false;
      game.simulate(1 / 60);
      if (st.phase === 'run' && tRun === null) tRun = st.t;
      maxDev = Math.max(maxDev, Math.abs(P.d - (st.laneD || 0))); maxPsi = Math.max(maxPsi, Math.abs(P.psi || 0));
      const S = P.ty5 || {}; maxR = Math.max(maxR, Math.abs(S.r || 0));
      if (tDiv === null && Math.abs(P.d - (st.laneD || 0)) > 2) { tDiv = +(st.t).toFixed(2); vAtDiv = Math.round(P.v * 3.6); }
    }
    res.push({ maxDev: +maxDev.toFixed(2), div: tDiv, vDiv: vAtDiv, maxRdeg: +(maxR * 57.3).toFixed(2), maxPsi: +(maxPsi*57.3).toFixed(3), crash: !!P.crash, phase: st.phase });
  }
  return JSON.stringify(res); })()
