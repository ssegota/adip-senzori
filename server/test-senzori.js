/* Dimni test za senzori-api.js — pokretanje (iz korijena adip-web):
     node senzori/server/test-senzori.js
   express/multer/body-parser se uzimaju iz node_modules ove stranice (ili ADIP_WEB=/put). */
'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = require('assert');

const WEB = process.env.ADIP_WEB || path.join(__dirname, '..', '..');
const iz = function (ime) { return require(require.resolve(ime, { paths: [WEB] })); };
const express = iz('express');
const multer = iz('multer');
const bodyParser = iz('body-parser');

const crypto = require('crypto');
const LOZINKA = 'test-lozinka';
const sol = crypto.randomBytes(16);
const lozinkaHash = 'scrypt$' + sol.toString('hex') + '$' + crypto.scryptSync(LOZINKA, sol, 32).toString('hex');
const korijen = fs.mkdtempSync(path.join(os.tmpdir(), 'senzori-test-'));
const dataDir = path.join(korijen, 'data', 'senzori');

let prolaz = 0;
function ok(uvjet, opis) { assert.ok(uvjet, opis); prolaz++; console.log('  ✓ ' + opis); }

async function main() {
  const app = express();
  app.use(bodyParser.json());   // kao u server.js (limit 100 kB)
  app.use(express.static(korijen));   // kao u server.js: cijeli korijen je statika, ispred modula
  require('./senzori-api')(app, { dataDir: dataDir, express: express, multer: multer, lozinkaHash: lozinkaHash });
  const srv = await new Promise(function (r) { const s = app.listen(0, function () { r(s); }); });
  const B = 'http://127.0.0.1:' + srv.address().port + '/api/senzori';
  const POST = function (url, o, h) { return fetch(url, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, h || {}), body: JSON.stringify(o) }); };
  let auth, json;

  try {
    let r = await fetch(B + '/status'); let j = await r.json();
    ok(r.status === 200 && j.ok && j.service === 'adip-senzori', 'status bez tokena');

    /* ── tokeni ── */
    r = await POST(B + '/tokeni', { lozinka: 'krivo', naziv: 'x' }); j = await r.json();
    ok(r.status === 401 && j.ok === false && j.error === 'Pogrešna lozinka', 'kriva lozinka → 401');
    r = await POST(B + '/tokeni', { lozinka: LOZINKA, naziv: 'Pixel test' }); j = await r.json();
    ok(r.status === 200 && j.ok && /^[0-9a-f]{64}$/.test(j.token) && /^[0-9a-f]{8}$/.test(j.id) && j.naziv === 'Pixel test' && j.created, 'novi token');
    const T1 = j.token, ID1 = j.id;
    const spremljeno = JSON.parse(fs.readFileSync(path.join(dataDir, 'tokeni.json'), 'utf8'));
    ok(spremljeno.length === 1 && spremljeno[0].hash === crypto.createHash('sha256').update(T1).digest('hex') && !JSON.stringify(spremljeno).includes(T1), 'tokeni.json sadrži samo sha256 hash');
    r = await POST(B + '/tokeni', { lozinka: LOZINKA, naziv: 'Drugi' }); const T2 = (await r.json()).token;

    auth = { Authorization: 'Bearer ' + T1 };
    json = function (o) { return { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, auth), body: JSON.stringify(o) }; };

    r = await fetch(B + '/token', { headers: auth }); j = await r.json();
    ok(r.status === 200 && j.ok && j.id === ID1 && j.naziv === 'Pixel test' && j.created && !j.hash && !j.token, 'GET /token s valjanim tokenom');
    r = await fetch(B + '/token', { headers: { Authorization: 'Bearer ' + 'a'.repeat(64) } }); j = await r.json();
    ok(r.status === 401 && j.ok === false && j.error, 'GET /token s nevaljanim → 401');
    r = await fetch(B + '/token', { headers: { Authorization: 'Bearer fake-jwt-token-adip' } });
    ok(r.status === 401, 'stari fake-jwt-token-adip više ne vrijedi');

    r = await fetch(B + '/tokeni'); ok(r.status === 401, 'popis tokena bez lozinke → 401');
    r = await fetch(B + '/tokeni', { headers: { 'X-Senzori-Lozinka': LOZINKA } }); j = await r.json();
    ok(j.ok && j.tokeni.length === 2 && !('hash' in j.tokeni[0]) && j.tokeni[0].id === ID1 && j.tokeni[0].lastUsed, 'popis tokena bez hasheva, lastUsed postavljen');

    r = await fetch(B + '/sesije'); ok(r.status === 200 && Array.isArray(await r.json()), 'sesije su javne');

    const cols = ['t_ms', 'ax', 'ay', 'az', 'lux', 'infra', 'p_hpa'];
    const base = { deviceId: 'dev1', deviceModel: 'Pixel', appVersion: '1.0', startedAt: '2026-11-11T10:00:00Z', sampleRateHz: 50, columns: cols };

    r = await fetch(B + '/batch', json(Object.assign({ sessionId: '../etc', rows: [] }, base)));
    ok(r.status === 400, 'loš sessionId → 400');
    r = await fetch(B + '/batch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    ok(r.status === 401, 'batch bez tokena → 401');
    r = await POST(B + '/batch', Object.assign({ sessionId: 'x', rows: [] }, base), { Authorization: 'Bearer fake-jwt-token-adip' });
    ok(r.status === 401, 'batch sa starim fake tokenom → 401');

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

    r = await fetch(B + '/sesije'); j = await r.json();
    ok(Array.isArray(j) && j.length === 2, 'popis ima 2 sesije (javno)');
    ok(j.every(function (s) { return !('deviceId' in s) && !('tokenId' in s); }) && j.some(function (s) { return s.deviceModel === 'Pixel'; }), 'javni popis bez deviceId, s deviceModel');
    const idx = JSON.parse(fs.readFileSync(path.join(dataDir, 'index.json'), 'utf8'));
    ok(idx.s1.tokenId === ID1 && idx.s1.deviceId === 'dev1', 'index.json bilježi tokenId i deviceId');
    const up = j.find(function (s) { return s.sessionId === 'up_1'; });
    ok(up.sampleRateHz === 100 && up.deviceModel === 'Samsung' && up.rows === 3 && up.bytes > 0, 'metapodaci uploada iz komentara');
    ok(j[0].startedAt >= j[1].startedAt, 'poredak: najnovije prve');

    r = await fetch(B + '/sesije/s1?limit=5'); j = await r.json();
    ok(!('deviceId' in j), 'javni detalj bez deviceId');
    ok(j.sessionId === 's1' && j.rows.length === 5 && j.totalRows === 8002 && j.rows[4][0] === 1000 + 7999 * 20, 'get: zadnjih 5 redaka');
    r = await fetch(B + '/sesije/up_1'); j = await r.json();
    ok(j.rows.length === 3 && j.rows[0][4] === null && j.rows[2][6] === null, 'get: prazna ćelija → null');
    r = await fetch(B + '/sesije/nema'); ok(r.status === 404, 'nepostojeća sesija → 404');

    r = await fetch(B + '/sesije/s1/csv'); const t = await r.text();
    ok(r.headers.get('content-type').startsWith('text/csv') && t.includes('# sampleRateHz=50') && t.includes('\nt_ms,ax,ay,az,lux,infra,p_hpa\n') && t.trim().split('\n').length === 5 + 8002, 'csv preuzimanje (javno)');
    ok(!t.includes('deviceId') && !t.includes('dev1') && fs.readFileSync(path.join(dataDir, 's1.csv'), 'utf8').includes('# deviceId=dev1'), 'csv bez # deviceId= (na disku ostaje)');

    const W = B.replace('/api/senzori', '');
    fs.writeFileSync(path.join(korijen, 'data', 'kontrola.json'), '[]');
    r = await fetch(W + '/data/kontrola.json'); ok(r.status === 200, '(kontrola: statika radi)');
    r = await fetch(W + '/data/senzori/index.json'); const r2 = await fetch(W + '/data/senzori/tokeni.json');
    ok(r.status === 404 && r2.status === 404, 'data/senzori (index.json, tokeni.json) nije dostupan kao statika');

    r = await fetch(B + '/sesije/s1', { method: 'DELETE' }); ok(r.status === 401, 'delete bez autorizacije → 401');
    r = await fetch(B + '/sesije/s1', { method: 'DELETE', headers: { 'X-Senzori-Lozinka': 'krivo' } }); ok(r.status === 401, 'delete s krivom lozinkom → 401');
    r = await fetch(B + '/sesije/up_1', { method: 'DELETE', headers: { 'X-Senzori-Lozinka': LOZINKA } }); ok((await r.json()).ok, 'delete lozinkom');
    r = await fetch(B + '/sesije/s1', { method: 'DELETE', headers: { Authorization: 'Bearer ' + T2 } }); j = await r.json();
    ok(j.ok && !fs.existsSync(path.join(dataDir, 's1.csv')), 'delete');
    r = await fetch(B + '/sesije/s1', { method: 'DELETE', headers: auth }); ok(r.status === 404, 'ponovni delete → 404');
    r = await fetch(B + '/sesije'); ok((await r.json()).length === 0, 'popis nakon brisanja');

    /* opoziv */
    r = await fetch(B + '/tokeni/' + ID1, { method: 'DELETE' }); ok(r.status === 401, 'opoziv bez lozinke → 401');
    r = await fetch(B + '/tokeni/' + ID1, { method: 'DELETE', headers: { 'X-Senzori-Lozinka': LOZINKA } }); ok((await r.json()).ok, 'opoziv tokena');
    r = await fetch(B + '/tokeni/' + ID1, { method: 'DELETE', headers: { 'X-Senzori-Lozinka': LOZINKA } }); ok(r.status === 404, 'ponovni opoziv → 404');
    r = await fetch(B + '/token', { headers: auth }); ok(r.status === 401, 'opozvani token → 401');
    r = await fetch(B + '/batch', json(Object.assign({ sessionId: 's9', rows: [] }, base))); ok(r.status === 401, 'batch s opozvanim tokenom → 401');

    /* ograničenje pokušaja: dosad 3 promašaja s ove IP; još 7 → zatim 429 */
    for (let i = 0; i < 7; i++) await POST(B + '/tokeni', { lozinka: 'krivo' });
    r = await POST(B + '/tokeni', { lozinka: LOZINKA, naziv: 'x' }); ok(r.status === 429, '10 promašaja → 429 (i za ispravnu lozinku)');
    r = await POST(B + '/tokeni', { lozinka: LOZINKA, naziv: 'x' }, { 'X-Forwarded-For': '203.0.113.5, 10.0.0.1' }); ok(r.status === 200, 'druga IP (X-Forwarded-For) nije blokirana');
    ok(fs.readdirSync(path.join(dataDir, '.tmp')).length === 0, 'nema zaostalih privremenih datoteka');

    console.log('\nSVE PROŠLO: ' + prolaz + ' provjera.');
  } finally {
    srv.close();
    fs.rmSync(korijen, { recursive: true, force: true });
  }
}

main().catch(function (e) { console.error('\nPAD:', e.message); process.exit(1); });
