// Made-up API replies, so the pages render with data when no app or show is reachable.
// Shapes follow what the pages read (show.js, preshow.html, lighttest.html, measure.js).
import { readFileSync } from 'node:fs';

const now = Date.now();
const iso = msAgo => new Date(now - msAgo).toISOString();

const boardNames = ['Front Far Left', 'Mega Tree 1', 'Mega Tree 2', 'Mega Tree 3', 'Mega Tree 4', 'Octa 5', 'Octa 6', 'Tune In'];

export const showStatus = {
  at: iso(0),
  fpp: {
    reachable: true, host: 'fpp.show.nh.tronkowski.net', status: 'playing', playlist: 'Main Show', index: 3, count: 12,
    song: 'Carol of the Bells', secondsPlayed: 74, secondsRemaining: 101,
    scheduledPlaylist: 'Main Show', scheduledEnd: '10:00 PM', nextPlaylist: 'Background', nextStart: '10:00 PM',
    schedulerEnabled: true, volume: 70, expectedVolume: 70, version: '9.2', cpuTempC: 51, mediaFreeGb: 21, mediaFreePercent: 68, powerBad: false,
  },
  power: {
    configured: true,
    circuit: { state: 'on', watts: 1184, amps: 9.8, volts: 121, ampsEstimated: false },
    history: Array.from({ length: 40 }, (_, i) => ({ at: iso((40 - i) * 15000), watts: 1050 + Math.round(140 * Math.sin(i / 4)), amps: 9 + Math.sin(i / 4), volts: 121 })),
    plugs: [
      { label: 'Octa 5–8', state: 'on', watts: null, boards: ['Octa 5', 'Octa 6', 'Octa 7', 'Octa 8'] },
      { label: 'Mega Tree pixels', state: 'on', watts: null, boards: ['Mega Tree 1', 'Mega Tree 2', 'Mega Tree 3', 'Mega Tree 4'] },
    ],
    switches: [{ label: 'Road speakers', state: 'off' }],
  },
  boards: boardNames.map((name, i) => ({
    name, ip: `192.168.6.${20 + i}`, reachable: i !== 7, live: i !== 7, plug: i < 5 ? null : 'Octa 5–8',
    maxMilliamps: 5000, milliamps: 1200 + i * 90, error: i === 7 ? 'timed out' : null,
  })),
};

export const showPlaylists = [
  { name: 'Main Show', items: ['Intro', 'Wizards in Winter', 'Carol of the Bells', 'Mad Russian', 'Sleigh Ride'].map((title, position) => ({ title, position, seconds: 150 + position * 20 })) },
  { name: 'Background', items: [{ title: 'Twinkle loop', position: 0, seconds: 600 }] },
];

export const preshowRuns = [
  { id: 'r3', startedAt: iso(3600e3), verdict: 'GO_WITH_WARNINGS', headline: 'GO with 1 warning', pass: 'scheduled' },
  { id: 'r2', startedAt: iso(25 * 3600e3), verdict: 'GO', headline: 'GO', pass: 'scheduled' },
  { id: 'r1', startedAt: iso(49 * 3600e3), verdict: 'NO_GO', headline: 'NO GO: 2 failures', pass: 'manual' },
];

const td = t => `<td style="padding:6px 8px;border-top:1px solid #ffffff">${t}</td>`;
const row = (o, bg, fg, target, check, details) =>
  `<tr class="o-${o}" style="background:${bg};color:${fg}"><td style="padding:6px 8px;font-weight:bold;border-top:1px solid #ffffff">${o}</td>${td(target)}${td(check)}${td(details)}</tr>`;
const table = rows => `<table style="width:100%;border-collapse:collapse;font-size:13px"><tr class="head" style="background:#1a73e8;color:#ffffff;font-weight:bold">${['Status', 'Target', 'Check', 'Details'].map(h => `<td style="padding:6px 8px">${h}</td>`).join('')}</tr>${rows.join('')}</table>`;
const h2 = t => `<h2 class="section" style="font-size:18px;margin:24px 0 8px 0;padding-bottom:4px;border-bottom:2px solid #1a73e8">${t}</h2>`;
export const preshowReport = `<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>@media (max-width:520px){body{padding:0 !important}.report-card{padding:14px !important;border-radius:0 !important;box-shadow:none !important}td{padding:5px 6px !important}h1{font-size:20px !important}}</style></head><body style="margin:0;padding:16px;background:#f1f3f4;font-family:Arial,Helvetica,sans-serif;color:#202124"><div class="report-card" style="background:#ffffff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.15);max-width:700px;margin:0 auto;padding:24px"><h1 style="font-size:24px;margin:0 0 12px 0">Pre-show Check — sample</h1><div class="banner v-GO_WITH_WARNINGS" style="background:#fff3cd;color:#856404;border-radius:6px;padding:12px;font-weight:bold;text-align:center;font-size:16px">⚠️ GO with 1 warning</div><p class="muted" style="font-size:13px;color:#5f6368;margin:12px 0 0 0">Pass: <b>scheduled</b> · took 41 s</p>${h2('🚨 Needs attention')}${table([row('WARN', '#fff3cd', '#856404', 'Tune In', 'Reachable', 'Answered after 2 retries (1.8 s)')])}${h2('💡 Boards')}${table(boardNames.slice(0, 7).map(n => row('PASS', '#d4edda', '#155724', n, 'Reachable', 'WLED 0.15.1, 42 ms')))}</div></body></html>`;

export const lighttestStatus = { configured: true, busy: false, calibratedAt: iso(20 * 3600e3), visibleModels: 61, calibratedModels: 64, message: '' };

export const measurePlan = () => readFileSync(new URL('../../src/main/resources/measure/default-plan.json', import.meta.url), 'utf8');

export const version = { version: '0.0.0-sample', sha: 'sample' };
