// Show Control page: FPP playback, power and board status, plus switches such as the road speakers.
'use strict';

const POLL_MS = 5000;
const $ = id => document.getElementById(id);
let timer = null;
let lastOk = 0;
let lastStatus = null;

function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v; else if (k === 'text') e.textContent = v; else e.setAttribute(k, v);
  }
  for (const k of kids.flat()) if (k != null) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
}

const fmtTime = s => (s == null || s < 0) ? '–' : `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const num = (v, d = 0) => v == null ? '–' : Number(v).toFixed(d);

function led(kind) { return el('span', { class: `sc-led ${kind || ''}` }); }

function renderNow(f) {
  const body = $('nowBody');
  if (!f.reachable) {
    body.replaceChildren(
      el('span', { class: 'sc-state' }, led('err'), 'FPP not answering'),
      el('p', { class: 'sc-sub', text: `${f.host || 'no address'}${f.error ? ` · ${f.error}` : ''}` }));
    return;
  }
  const status = f.status || 'unknown';
  const playing = status === 'playing' || status.startsWith('stopping');
  const kids = [el('span', { class: 'sc-state' }, led(playing ? 'ok' : status === 'idle' ? '' : 'warn'), status)];
  if (playing) {
    kids.push(el('p', { class: 'sc-song', text: f.song || f.sequence || '—' }));
    const pos = f.index && f.count ? ` · ${f.index} of ${f.count}` : '';
    kids.push(el('p', { class: 'sc-sub', text: `${f.playlist || ''}${pos}` }));
    const played = f.secondsPlayed ?? 0, left = f.secondsRemaining ?? 0, total = played + left;
    const bar = el('div', { class: 'sc-progress' }, el('span'));
    bar.firstChild.style.width = total > 0 ? `${Math.min(100, played * 100 / total)}%` : '0';
    kids.push(bar, el('div', { class: 'sc-times' }, el('span', { text: fmtTime(played) }), el('span', { text: `−${fmtTime(left)}` })));
  } else {
    kids.push(el('p', { class: 'sc-sub', text: 'WLED boards are on their own looks.' }));
  }
  const kv = el('dl', { class: 'sc-kv' });
  const add = (k, v) => { if (v != null && v !== '') kv.append(el('dt', { text: k }), el('dd', { text: v })); };
  if (f.scheduledPlaylist) add('Schedule', `${f.scheduledPlaylist}${f.scheduledEnd ? ` until ${f.scheduledEnd}` : ''}`);
  if (f.nextPlaylist) add('Next', `${f.nextPlaylist}${f.nextStart ? ` at ${f.nextStart}` : ''}`);
  if (f.schedulerEnabled === false) add('Scheduler', 'off');
  if (f.volume != null) add('Volume', `${f.volume}${f.expectedVolume != null && f.expectedVolume !== f.volume ? ` (show level ${f.expectedVolume})` : ''}`);
  kids.push(kv);
  body.replaceChildren(...kids);
}

// Where the pointer sits on the power graph (0..1 across), kept so the 5 s re-render keeps the readout.
let sparkHover = null;
// Tapping anywhere off the graph clears a touch readout (one listener; the graph is rebuilt every poll).
document.addEventListener('pointerdown', e => {
  if (!e.target.closest?.('.sc-spark-plot')) {
    sparkHover = null;
    document.querySelectorAll('.sc-spark-cursor, .sc-spark-dot, .sc-spark-tip').forEach(n => { n.hidden = true; });
  }
});

const fmtClock = ms => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' });

function sparkline(history) {
  if (!history || history.length < 2) return null;
  const W = 300, H = 48, pad = 2;
  const ws = history.map(p => p.watts);
  const lo = Math.min(...ws), hi = Math.max(...ws), span = Math.max(hi - lo, 1);
  const t0 = Date.parse(history[0].at), t1 = Date.parse(history.at(-1).at), dt = Math.max(t1 - t0, 1);
  const pts = history.map(p => [((Date.parse(p.at) - t0) / dt) * W, H - pad - ((p.watts - lo) / span) * (H - 2 * pad)]);
  const d = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join('');
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`); svg.setAttribute('preserveAspectRatio', 'none');
  svg.setAttribute('class', 'sc-spark'); svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', `Show circuit power over the last ${Math.round(dt / 60000)} minutes, ${Math.round(lo)} to ${Math.round(hi)} W`);
  for (const [cls, path] of [['area', `${d}L${W} ${H}L0 ${H}Z`], ['line', d]]) {
    const p = document.createElementNS(ns, 'path'); p.setAttribute('class', cls); p.setAttribute('d', path); svg.append(p);
  }

  // Hover / touch readout: a cursor line and dot on the nearest sample, with its W · A · V and time.
  const cursor = el('div', { class: 'sc-spark-cursor', hidden: '' });
  const dot = el('div', { class: 'sc-spark-dot', hidden: '' });
  const tip = el('div', { class: 'sc-spark-tip', hidden: '' });
  const plot = el('div', { class: 'sc-spark-plot' }, svg, cursor, dot, tip);
  const show = frac => {
    sparkHover = frac;
    const t = t0 + frac * dt;
    let i = 0;
    for (let k = 1; k < history.length; k++) if (Math.abs(Date.parse(history[k].at) - t) < Math.abs(Date.parse(history[i].at) - t)) i = k;
    const s = history[i], [x, y] = pts[i];
    const left = `${(x / W) * 100}%`;
    cursor.style.left = left; dot.style.left = left; dot.style.top = `${(y / H) * 100}%`;
    const parts = [`${num(s.watts)} W`];
    if (s.amps != null) parts.push(`${num(s.amps, 1)} A`);
    if (s.volts != null) parts.push(`${num(s.volts)} V`);
    tip.replaceChildren(el('b', { text: parts.join(' · ') }), el('span', { text: fmtClock(Date.parse(s.at)) }));
    tip.classList.toggle('flip', x / W > 0.5);
    tip.style.left = left;
    cursor.hidden = dot.hidden = tip.hidden = false;
  };
  const hide = () => { sparkHover = null; cursor.hidden = dot.hidden = tip.hidden = true; };
  const at = e => { const r = plot.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
  plot.addEventListener('pointermove', e => show(at(e)));
  plot.addEventListener('pointerdown', e => show(at(e)));
  // A mouse leaving clears it; a finger lifting leaves the last reading up until the next tap elsewhere.
  plot.addEventListener('pointerleave', e => { if (e.pointerType === 'mouse') hide(); });
  if (sparkHover != null) show(sparkHover);

  const wrap = el('div', {}, plot, el('div', { class: 'sc-spark-cap' },
    el('span', { text: `${Math.round(dt / 60000)} min ago` }), el('span', { text: `${Math.round(lo)}–${Math.round(hi)} W` }), el('span', { text: 'now' })));
  return wrap;
}

function renderPower(p) {
  const body = $('powerBody');
  if (!p.configured) { body.replaceChildren(el('p', { class: 'sc-dim', text: 'Home Assistant is not configured.' })); return; }
  const kids = [];
  const c = p.circuit;
  if (c) {
    const st = c.state || 'unknown';
    kids.push(el('ul', { class: 'sc-rows' }, el('li', {},
      led(st === 'on' ? 'ok' : st === 'off' ? 'err' : 'warn'),
      el('span', { class: 'sc-name' }, 'Show circuit', el('span', { class: 'sc-note', text: 'every WLED board, Front Far Left PoE, FM transmitter' })),
      el('span', { class: 'sc-right', text: st }))));
    kids.push(el('div', { class: 'sc-meters' },
      el('div', { class: 'sc-meter' }, el('b', { text: num(c.watts) }), el('small', { text: 'watts' })),
      el('div', { class: 'sc-meter' }, el('b', { text: num(c.amps, 1) }), el('small', { text: c.ampsEstimated ? 'amps (est.)' : 'amps' })),
      el('div', { class: 'sc-meter' }, el('b', { text: num(c.volts) }), el('small', { text: 'volts' }))));
    const sp = sparkline(p.history);
    if (sp) kids.push(sp);
  }
  if (p.plugs.length) {
    kids.push(el('ul', { class: 'sc-rows' }, p.plugs.map(pl => el('li', {},
      led(pl.state === 'on' ? 'ok' : pl.state === 'off' ? 'err' : 'warn'),
      el('span', { class: 'sc-name' }, pl.label,
        (pl.boards.length > 1 || pl.boards[0] !== pl.label) ? el('span', { class: 'sc-note', text: pl.boards.join(', ') }) : null),
      el('span', { class: 'sc-right', text: pl.watts != null ? `${num(pl.watts)} W · ${pl.state}` : pl.state })))));
  }
  if (p.switches?.length) {
    kids.push(el('ul', { class: 'sc-rows sc-switches' }, p.switches.map(sw => {
      const on = sw.state === 'on', known = sw.state === 'on' || sw.state === 'off';
      const busy = switchBusy.has(sw.label);
      const btn = el('button', { type: 'button', class: `sc-toggle${on ? ' on' : ''}`, disabled: !known || busy ? '' : null,
        'aria-pressed': on ? 'true' : 'false', text: busy ? '…' : on ? 'Turn off' : 'Turn on' });
      btn.addEventListener('click', () => flipSwitch(sw.label, !on));
      return el('li', {}, led(on ? 'ok' : known ? '' : 'warn'),
        el('span', { class: 'sc-name' }, sw.label),
        el('span', { class: 'sc-right' }, el('span', { class: 'sc-sw-state', text: sw.state }), btn));
    })));
    if (switchError) kids.push(el('p', { class: 'sc-err', text: switchError }));
  }
  body.replaceChildren(...kids);
}

const switchBusy = new Set();
let switchError = null;
async function flipSwitch(label, on) {
  switchBusy.add(label); switchError = null;
  if (lastStatus) renderPower(lastStatus.power);
  try {
    const res = await fetch('api/show/switch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ label, on }) });
    if (!res.ok) {
      const b = await res.json().catch(() => ({}));
      switchError = `${label}: ${b.error || `HTTP ${res.status}`}`;
    }
  } catch (e) {
    switchError = `${label}: ${e.message}`;
  } finally {
    switchBusy.delete(label);
    await poll();
  }
}

function renderBoards(boards) {
  const body = $('boardsBody');
  if (!boards.length) { body.replaceChildren(el('p', { class: 'sc-dim', text: 'No WLED boards: upload the xLights show folder on the main page.' })); return; }
  const up = boards.filter(b => b.reachable).length, live = boards.filter(b => b.live).length;
  // WLED's current estimate only means something when its brightness limiter is on (maxpwr > 0).
  const metered = boards.filter(b => b.maxMilliamps);
  const totalMa = metered.reduce((s, b) => s + (b.milliamps || 0), 0);
  const est = metered.length ? ` · ${(totalMa / 1000).toFixed(1)} A estimated on ${metered.length}` : '';
  body.replaceChildren(
    el('p', { class: 'sc-dim', text: `${up} of ${boards.length} answering · ${live} live${est}` }),
    el('ul', { class: 'sc-rows' }, boards.map(b => el('li', {},
      led(!b.reachable ? 'err' : b.live ? 'ok' : ''),
      el('span', { class: 'sc-name' }, b.name, el('span', { class: 'sc-note', text: b.reachable ? (b.plug ? `plug: ${b.plug}` : b.ip) : (b.error || 'not answering') })),
      b.live ? el('span', { class: 'sc-pill live', text: 'live' }) : null,
      el('span', { class: 'sc-right', text: b.reachable && b.maxMilliamps ? `${b.milliamps} mA` : '' })))));
}

function renderFpp(f) {
  const body = $('fppBody');
  if (!f.reachable) { body.replaceChildren(el('p', { class: 'sc-err', text: `Not answering${f.error ? `: ${f.error}` : ''}` })); return; }
  const kv = el('dl', { class: 'sc-kv' });
  const add = (k, v) => { if (v != null) kv.append(el('dt', { text: k }), el('dd', { text: v })); };
  add('Version', f.version);
  add('CPU', f.cpuTempC != null ? `${num(f.cpuTempC)} °C` : null);
  add('Media disk', f.mediaFreeGb != null ? `${num(f.mediaFreeGb)} GB free${f.mediaFreePercent != null ? ` (${num(f.mediaFreePercent)}%)` : ''}` : null);
  add('Pi power', f.powerBad ? 'under-voltage!' : 'ok');
  add('Scheduler', f.schedulerEnabled == null ? null : f.schedulerEnabled ? 'on' : 'off');
  add('Address', f.host);
  body.replaceChildren(kv);
}

async function loadPlaylists() {
  const body = $('plBody');
  try {
    const res = await fetch('api/show/playlists');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const lists = await res.json();
    if (!lists.length) { body.replaceChildren(el('p', { class: 'sc-dim', text: 'No playlists on FPP.' })); return; }
    body.replaceChildren(...lists.map(pl => {
      const secs = pl.items.reduce((s, i) => s + (i.seconds || 0), 0);
      return el('details', { class: 'sc-pl', 'data-name': pl.name },
        el('summary', {}, pl.name, el('span', { class: 'sc-right', text: `${pl.items.length} · ${fmtTime(secs)}` })),
        pl.error ? el('p', { class: 'sc-err', text: pl.error })
          : el('ol', { class: 'sc-songs' }, pl.items.map(i => el('li', { 'data-pos': i.position },
            el('span', { text: i.title }), el('span', { class: 'sc-right', text: i.seconds ? fmtTime(i.seconds) : '' })))));
    }));
    markCurrent();
  } catch (e) {
    body.replaceChildren(el('p', { class: 'sc-err', text: `Playlists not readable: ${e.message}` }));
  }
}

function markCurrent() {
  const f = lastStatus?.fpp;
  for (const li of document.querySelectorAll('.sc-songs li')) li.classList.remove('current');
  if (!f?.playlist || !f.index) return;
  const pl = [...document.querySelectorAll('.sc-pl')].find(d => d.dataset.name === f.playlist);
  pl?.querySelector(`li[data-pos="${f.index - 1}"]`)?.classList.add('current');
}

async function poll() {
  try {
    const res = await fetch('api/show/status', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const s = await res.json();
    lastStatus = s; lastOk = Date.now();
    renderNow(s.fpp); renderPower(s.power); renderBoards(s.boards); renderFpp(s.fpp); markCurrent();
    $('scUpdated').textContent = new Date(s.at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' });
    $('scUpdated').classList.remove('stale');
  } catch (e) {
    if (Date.now() - lastOk > 15000) { $('scUpdated').textContent = 'connection lost'; $('scUpdated').classList.add('stale'); }
  }
}

function start() { if (!timer) { poll(); timer = setInterval(poll, POLL_MS); } }
function stop() { clearInterval(timer); timer = null; }
document.addEventListener('visibilitychange', () => document.hidden ? stop() : start());

loadPlaylists();
start();
