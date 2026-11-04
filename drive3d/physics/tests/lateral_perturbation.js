(async () => { const game = window.game, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), out = {};
  game.renderer.render = () => {}; HW.TY5.on = true; HW.TY5.dbg = null;
  game.setCar(HW.CARS.findIndex((c) => c.id === 'supra'), 0); await sleep(200);
  { const base = HW.CARS.find((c) => c.id === 'supra'), mt = HW.NPCTUNE.makeMad(base); if (mt && mt.tune) { game.tunes.supra = JSON.parse(JSON.stringify(mt.tune)); game.retune(); await sleep(200); } }
  game.setRoute('drag4000'); await sleep(1500); if (game.mode !== 'drive') game.startDrive(); await sleep(500);
  const R = HW.Race; R.cfg.opp = 'none'; R.cfg.dn = 3; R.cfg.ntune = 'mad'; R.cfg.tree = 'pro';
  R.start(game, 'drag'); await sleep(300); HW.RAP.set(game, true);
  const st = R.st, P = game.P; const targets = window.__targets || [150, 300, 400]; let ti = 0, phase = 'accel', tP = 0, rows = [], dOff = 1.0, log = {};
  for (let i = 0; i < 60 * 200 && st.phase !== 'done'; i++) {
    game.simulate(1 / 60);
    const vk = P.v * 3.6;
    if (phase === 'accel' && st.phase === 'run' && vk >= targets[ti]) { phase = 'pert'; tP = 0; rows = []; P.d += dOff; }
    if (phase === 'pert') { tP += 1 / 60; rows.push([+tP.toFixed(2), Math.round(vk), +(P.d - (st.laneD || 0)).toFixed(3), +((P.psi||0)*57.3).toFixed(3), +((P.sc||0)).toFixed(3), +((P.yaw||0)*57.3).toFixed(3)]); if (tP > 8) { log[targets[ti]] = rows; ti++; if (ti >= targets.length) break; phase = 'accel'; } }
    if (P.crash) break;
  }
  function A_sc() { return HW.RAP && game.A ? 0 : 0; }
  return JSON.stringify(log); })()
