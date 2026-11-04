(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), res = [];
  game.renderer.render = () => {}; HW.TY5.on = true; HW.TY5.dbg = null;
  const jobs = window.__jobs || [['supra', 'drag4000'], ['gtr', 'drag4000']];
  for (const [car, route] of jobs) for (let trial = 0; trial < (window.__n || 4); trial++) {
    game.setCar(HW.CARS.findIndex((c) => c.id === car), 0); await sleep(200);
    const base = HW.CARS.find((c) => c.id === car), mt = HW.NPCTUNE.makeMad(base);
    if (mt && mt.tune) { game.tunes[car] = JSON.parse(JSON.stringify(mt.tune)); game.retune(); await sleep(200); }
    game.setRoute(route); await sleep(1500); if (game.mode !== 'drive') game.startDrive(); await sleep(500);
    const R = HW.Race; R.cfg.opp = 'random'; R.cfg.dn = 3; R.cfg.ntune = 'mad'; R.cfg.tree = 'pro';
    R.start(game, 'drag'); await sleep(300); HW.RAP.set(game, true);
    const st = R.st, P = game.P; let maxSc = 0, maxDev = 0, crash = 0;
    for (let i = 0; i < 60 * 150 && st.phase !== 'done'; i++) { game.simulate(1 / 60); if (st.phase === 'run') { maxSc = Math.max(maxSc, Math.abs(P.sc)); maxDev = Math.max(maxDev, Math.abs(P.d - (st.laneD || 0))); if (P.crash) crash++; } }
    res.push([car, route, +maxSc.toFixed(2), +maxDev.toFixed(2), crash, st.phase]);
  }
  return JSON.stringify(res); })()
