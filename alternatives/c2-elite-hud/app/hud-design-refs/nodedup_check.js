// no-data 布局对比实验：全无数据 / 停车状态 / 满数据
const { chromium } = require('/home/<USER>/.openclaw/workspace/jarvis/node_modules/openclaw/node_modules/playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = '/home/<USER>/.openclaw/workspace/jarvis/hud-apk/app/src/main/assets';
const outDir = '/home/<USER>/.openclaw/workspace/jarvis/hud-apk/hud-design-refs';

const server = http.createServer((req, res) => {
  const f = path.join(root, req.url === '/' ? '/dashboard.html' : req.url.split('?')[0]);
  if (fs.existsSync(f)) { res.writeHead(200); res.end(fs.readFileSync(f)); }
  else { res.writeHead(404); res.end(); }
});

const STATES = {
  empty: {},  // 全无数据（不注入）
  parked: { speed: 0, rpm: -1, voltage: -1, fuelLevel: -1, power: 100, chargePower: 0, odo: 38813.6, heading: 220, piOn: true, nanoOn: true, wifiOn: true },
  full: { speed: 140, rpm: 5691, engineLoad: 45, voltage: 13.6, fuelLevel: 81, power: 100, chargePower: 242, dischargePower: 60, odo: 36632.8, heading: 90, piOn: true, nanoOn: true, wifiOn: true },
};

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--no-sandbox'], executablePath: process.env.HUD_CHROME });
  for (const [name, data] of Object.entries(STATES)) {
    const page = await browser.newPage({ viewport: { width: 640, height: 320 } });
    await page.goto(`http://127.0.0.1:${port}/dashboard.html`, { waitUntil: 'load' });
    await page.waitForTimeout(150);
    await page.evaluate(d => { switchTheme(1); if (Object.keys(d).length) updateData(d); }, data);
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${outDir}/nodedup_${name}.png` });
    // 收集各元素位置，检测重叠
    const boxes = await page.evaluate(() => {
      const ids = ['npw', 'npi', 'nnan', 'ncp', 'ntime', 'nsp', 'ngr', 'nfv', 'nff', 'nbv', 'nbf', 'nod', 'nrv', 'nrm', 'nvt'];
      const out = {};
      for (const id of ids) {
        const el = document.getElementById(id);
        if (el) { const r = el.getBoundingClientRect(); out[id] = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; }
      }
      return out;
    });
    console.log(`== ${name} ==`);
    console.log(JSON.stringify(boxes, null, 0));
    await page.close();
  }
  await browser.close();
  server.close();
  console.log('DONE');
});
