// Theme B (Pure Black + ODO) 本地渲染验证 — Playwright 截图
// 用法: ulimit -v unlimited; node shot_newb.js
const { chromium } = require('/home/<USER>/.openclaw/workspace/jarvis/node_modules/openclaw/node_modules/playwright-core');
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = '/home/<USER>/.openclaw/workspace/jarvis/hud-apk/app/src/main/assets';
const outDir = '/home/<USER>/.openclaw/workspace/jarvis/hud-apk/hud-design-refs';
const CHROME = process.env.HUD_CHROME || '';

const server = http.createServer((req, res) => {
  let p = req.url === '/' ? '/dashboard.html' : decodeURIComponent(req.url.split('?')[0]);
  const f = path.join(root, p);
  if (fs.existsSync(f) && fs.statSync(f).isFile()) {
    const ext = path.extname(f);
    const ct = ext === '.ttf' ? 'font/ttf' : ext === '.html' ? 'text/html' : 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': ct });
    res.end(fs.readFileSync(f));
  } else { res.writeHead(404); res.end('nf'); }
});

const DATA = { speed: 140, rpm: 5691, engineLoad: 45, voltage: 13.6, fuelLevel: 81,
  power: 100, chargePower: 242, dischargePower: 60, odo: 36632.8, heading: 90,
  piOn: true, nanoOn: true, wifiOn: true };

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const url = `http://127.0.0.1:${port}/dashboard.html`;
  const browser = await chromium.launch({ args: ['--no-sandbox'], executablePath: CHROME || undefined });
  const errs = [];
  const vps = [
    { name: '480x240', w: 480, h: 240 },
    { name: '640x320', w: 640, h: 320 },
    { name: '640x360', w: 640, h: 360 },
  ];
  for (const vp of vps) {
    const page = await browser.newPage({ viewport: { width: vp.w, height: vp.h } });
    page.on('pageerror', e => errs.push(`${vp.name} pageerror: ${e.message}`));
    page.on('console', m => { if (m.type() === 'error') errs.push(`${vp.name} console: ${m.text()}`); });
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForTimeout(150);
    // Theme B + 注入数据
    await page.evaluate(d => { switchTheme(1); updateData(d); }, DATA);
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${outDir}/newb_${vp.name}.png` });
    // 顺带截 Theme A 确认不崩
    await page.evaluate(() => switchTheme(-1));
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${outDir}/newa_${vp.name}.png` });
    await page.close();
  }
  await browser.close();
  server.close();
  console.log('DONE');
  if (errs.length) { console.log('ERRORS:\n' + errs.join('\n')); process.exit(1); }
});
