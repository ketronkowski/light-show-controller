// Screenshots of every page at phone, landscape and laptop sizes, plus a check that nothing
// scrolls sideways. Attach the pictures to UI pull requests.
//
//   node scripts/phone-screenshots/shoot.mjs                      # static pages + made-up data
//   node scripts/phone-screenshots/shoot.mjs --base https://light-show.lab.ri.tronkowski.net/pr-61/
//   options: --out <dir> (default build/phone-screenshots)  --pages show,preshow  --sizes phone-412,laptop
//
// Needs Node 18+ and Playwright with Chromium (npm i -g playwright && npx playwright install chromium).
// Without --base it serves src/main/resources/static itself and answers the API from fixtures.mjs;
// pages whose API isn't faked there (Controllers, Latest light test) show their empty or error state.
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, existsSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import * as fx from './fixtures.mjs';

// require() also finds a globally installed Playwright (NODE_PATH), which an ESM import does not.
const { chromium } = createRequire(import.meta.url)('playwright');

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) =>
  a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']] : acc, []));

const SIZES = {
  'phone-360': { width: 360, height: 800, mobile: true },
  'phone-412': { width: 412, height: 915, mobile: true },
  landscape: { width: 915, height: 412, mobile: true },
  laptop: { width: 1366, height: 800, mobile: false },
};
const PAGES = ['show', 'controllers', 'preshow', 'lighttest', 'measure'];
const sizes = (args.sizes ? args.sizes.split(',') : Object.keys(SIZES)).filter(s => SIZES[s]);
const pages = args.pages ? args.pages.split(',') : PAGES;
const out = args.out || 'build/phone-screenshots';
mkdirSync(out, { recursive: true });

const API = {
  'api/show/status': () => fx.showStatus,
  'api/show/playlists': () => fx.showPlaylists,
  'api/preshow/runs': () => fx.preshowRuns,
  'api/lighttest/status': () => fx.lighttestStatus,
  'api/system/version': () => fx.version,
};

let server = null;
let base = args.base;
if (!base) {
  const root = fileURLToPath(new URL('../../src/main/resources/static/', import.meta.url));
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
  server = createServer((req, res) => {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
    const send = (status, type, body) => { res.writeHead(status, { 'Content-Type': type }); res.end(body); };
    if (API[path]) return send(200, 'application/json', JSON.stringify(API[path]()));
    if (path === 'api/measure/plan') return send(200, 'application/json', fx.measurePlan());
    if (/^api\/preshow\/runs\/[^/]+\/report$/.test(path)) return send(200, 'text/html', fx.preshowReport);
    if (path.startsWith('api/')) return send(404, 'application/json', '{"error":"not in fixtures"}');
    const file = normalize(join(root, path));
    if (!file.startsWith(root) || !existsSync(file)) return send(404, 'text/plain', 'not found');
    send(200, types[extname(file)] || 'application/octet-stream', readFileSync(file));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}/`;
}
if (!base.endsWith('/')) base += '/';

const browser = await chromium.launch();
const problems = [];
for (const size of sizes) {
  const { width, height, mobile } = SIZES[size];
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 2, isMobile: mobile, hasTouch: mobile, ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  for (const name of pages) {
    await page.goto(`${base}${name}.html`, { waitUntil: 'networkidle' }).catch(() => {});
    await page.waitForTimeout(500);
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (wide > 0) problems.push(`${name} at ${size}: scrolls sideways by ${wide}px`);
    await page.screenshot({ path: join(out, `${name}-${size}.png`) });
  }
  await ctx.close();
}
await browser.close();
server?.close();

console.log(`Screenshots in ${out}`);
if (problems.length) { console.log(problems.join('\n')); process.exitCode = 1; }
