/* Dimni test za senzori-api.js — pokretanje:  node server/test-senzori.js
   express/multer/body-parser se uzimaju iz ~/Documents/ADIP/adip-web/node_modules. */
'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = require('assert');

const WEB = process.env.ADIP_WEB || path.join(os.homedir(), 'Documents', 'ADIP', 'adip-web');
const iz = function (ime) { return require(require.resolve(ime, { paths: [WEB] })); };
const express = iz('express');
const multer = iz('multer');
const bodyParser = iz('body-parser');

const TOKEN = 'fake-jwt-token-adip';
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'senzori-test-'));

let prolaz = 0;
function ok(uvjet, opis) { assert.ok(uvjet, opis); prolaz++; console.log('  ✓ ' + opis); }

async function main() {
  const app = express();
  app.use(bodyParser.json());   // kao u server.js (limit 100 kB)
  require('./senzori-api')(app, { dataDir: dataDir, express: express, multer: multer });
  const srv = await new Promise(function (r) { const s = app.listen(0, function () { r(s); }); });
  const B = 'http://127.0.0.1:' + srv.address().port + '/api/senzori';
  const auth = { Authorization: 'Bearer ' + TOKEN };
  const json = function (o) { return { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, auth), body: JSON.stringify(o) }; };

  try {
    let r = await fetch(B + '/status'); let j = await r.json();
    ok(r.status === 200 && j.ok && j.service === 'adip-senzori' && j.version === 1, 'status bez tokena');

    r = await fetch(B + '/sesije'); ok(r.status === 401 && (await r.json()).ok === false, 'sesije bez tokena → 401');
    r = await fetch(B + '/sesije', { headers: { Authorization: 'Bearer krivo' } }); ok(r.status === 401, 'krivi token → 401');

    const cols = ['t_ms', 'ax', 'ay', 'az', 'lux', 'infra', 'p_hpa'];
    const base = { deviceId: 'dev1', deviceModel: 'Pixel', appVersion: '1.0', startedAt: '2026-11-11T10:00:00Z', sampleRateHz: 50, columns: cols };

    r = await fetch(B + '/batch', json(Object.assign({ sessionId: '../etc', rows: [] }, base)));
    ok(r.status === 400, 'loš sessionId → 400');
    r = await fetch(B + '/batch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    ok(r.status === 401, 'batch bez tokena → 401');

    r = await fetch(B + '/batch', json(Object.assign({ sessionId: 's1', rows: [[1, 0.1, 0.2, 9.8, 100, null, 1013.2], [21, 0.1, 0.2, 9.8, null, 0.5, 1013.2]] }, base)));
    j = await r.json(); ok(j.ok && j.received === 2 && j.total === 2, 'prvi batch kreira sesiju');

    /* velik batch (>100 kB) — provjera da globalni parser ne smeta */
    const veliki = []; for (let i = 0; i < 8000; i++) veliki.push([1000 + i * 20, 0.123456, -0.5, 9.81, 120.5, 0.001, 1013.25]);
    r = await fetch(B + '/batch', json(Object.assign({ sessionId: 's1', rows: veliki }, base)));
    j = await r.json(); ok(r.status === 200 && j.total === 8002, 'veliki batch (' + JSON.stringify(veliki).length + ' B) dodan, total 8002');

    r = await fetch(B + '/batch', json(Object.assign({}, base, { sessionId: 's1', columns: ['t_ms', 'ax'], rows: [[1, 2]] })));
    ok(r.status === 409, 'drugačiji stupci → 409');
    r = await fetch(B + '/batch', json(Object.assign({ sessionId: 's1', rows: [[1, 2]] }, base)));
    ok(r.status === 400, 'redak krive duljine → 400');

    /* multipart upload */
    const csv = '# sampleRateHz=100\n# deviceModel=Samsung\nt_ms,ax,ay,az,lux,infra,p_hpa\n1,0,0,9.8,,0.1,1000\n11,0,0,9.8,5,,1000\n21,0,0,9.8,5,0.2,\n';
    const fd = new FormData();
    fd.append('sessionId', 'up_1'); fd.append('deviceId', 'dev2');
    fd.append('file', new Blob([csv], { type: 'text/csv' }), 'up_1.csv');
    r = await fetch(B + '/upload', { method: 'POST', headers: auth, body: fd });
    j = await r.json(); ok(j.ok && j.sessionId === 'up_1' && j.rows === 3, 'upload CSV → 3 retka');

    const fd2 = new FormData(); fd2.append('sessionId', 'a/b'); fd2.append('deviceId', 'x');
    fd2.append('file', new Blob([csv]), 'x.csv');
    r = await fetch(B + '/upload', { method: 'POST', headers: auth, body: fd2 });
    ok(r.status === 400, 'upload s lošim sessionId → 400');

    r = await fetch(B + '/sesije', { headers: auth }); j = await r.json();
    ok(Array.isArray(j) && j.length === 2, 'popis ima 2 sesije');
    const up = j.find(function (s) { return s.sessionId === 'up_1'; });
    ok(up.sampleRateHz === 100 && up.deviceModel === 'Samsung' && up.rows === 3 && up.bytes > 0, 'metapodaci uploada iz komentara');
    ok(j[0].startedAt >= j[1].startedAt, 'poredak: najnovije prve');

    r = await fetch(B + '/sesije/s1?limit=5', { headers: auth }); j = await r.json();
    ok(j.sessionId === 's1' && j.rows.length === 5 && j.totalRows === 8002 && j.rows[4][0] === 1000 + 7999 * 20, 'get: zadnjih 5 redaka');
    r = await fetch(B + '/sesije/up_1', { headers: auth }); j = await r.json();
    ok(j.rows.length === 3 && j.rows[0][4] === null && j.rows[2][6] === null, 'get: prazna ćelija → null');
    r = await fetch(B + '/sesije/nema', { headers: auth }); ok(r.status === 404, 'nepostojeća sesija → 404');

    r = await fetch(B + '/sesije/s1/csv', { headers: auth }); const t = await r.text();
    ok(r.headers.get('content-type').startsWith('text/csv') && t.includes('# sampleRateHz=50') && t.includes('\nt_ms,ax,ay,az,lux,infra,p_hpa\n') && t.trim().split('\n').length === 6 + 8002, 'csv preuzimanje');

    r = await fetch(B + '/sesije/s1', { method: 'DELETE', headers: auth }); j = await r.json();
    ok(j.ok && !fs.existsSync(path.join(dataDir, 's1.csv')), 'delete');
    r = await fetch(B + '/sesije/s1', { method: 'DELETE', headers: auth }); ok(r.status === 404, 'ponovni delete → 404');
    r = await fetch(B + '/sesije', { headers: auth }); ok((await r.json()).length === 1, 'popis nakon brisanja');
    ok(fs.readdirSync(path.join(dataDir, '.tmp')).length === 0, 'nema zaostalih privremenih datoteka');

    console.log('\nSVE PROŠLO: ' + prolaz + ' provjera.');
  } finally {
    srv.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
}

main().catch(function (e) { console.error('\nPAD:', e.message); process.exit(1); });
