(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), out = {};
  game.renderer.render = () => {}; HW.TY5.on = !window.__off; HW.TY5.dbg = null;
  game.setCar(HW.CARS.findIndex((c) => c.id === 'supra'), 0); await sleep(200);
  { const base = HW.CARS.find((c) => c.id === 'supra'), mt = HW.NPCTUNE.makeMad(base); if (mt && mt.tune) { game.tunes.supra = JSON.parse(JSON.stringify(mt.tune)); game.retune(); await sleep(200); } }
  game.setRoute('drag4000'); await sleep(1500); if (game.mode !== 'drive') game.startDrive(); await sleep(500);
  const R = HW.Race; R.cfg.opp = 'none'; R.cfg.dn = 3; R.cfg.ntune = 'mad'; R.cfg.tree = 'pro';
  R.start(game, 'drag'); await sleep(300);
  const st = R.st, P = game.P; const targets = window.__targets || [100, 380];
  const log = {}; let ti = 0, phase = 'accel', tPh = 0, rows = [], sc = 0;
  HW.RAP.set(game, false); game.keys.accel = true; game.keys.brake = false;
  for (let i = 0; i < 60 * 200; i++) {
    const vk = P.v * 3.6;
    if (phase === 'accel') { game.keys.accel = true; if (st.phase === 'run' && vk >= targets[ti]) { phase = 'steer'; tPh = 0; rows = []; } }
    if (phase === 'steer') { tPh += 1 / 60; sc = tPh < 0.5 ? window.__sc : 0; P.sc = sc; if (tPh > 3.0) { log[targets[ti]] = rows; ti++; if (ti >= targets.length) break; phase = 'accel'; } rows.push([+tPh.toFixed(2), Math.round(vk), +(P.psi * 57.3).toFixed(4), +(P.d).toFixed(3), +(P.yaw * 57.3).toFixed(3), +(P.tyBeta || 0).toFixed(3), +(sc).toFixed(3)]); }
    if (phase !== 'steer' && P.sc !== undefined) P.sc = P.sc;
    game.simulate(1 / 60);
    if (phase === 'steer') { if (P.sc !== undefined) P.sc = sc; }
    if (phase === 'steer') { game.keys.accel = true; }
    if (st.phase === 'done') break;
  }
  return JSON.stringify(log); })()
