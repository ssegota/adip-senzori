/* =========================================================
   ADIP Senzori — poslužiteljski dio
   Astronomsko društvo "Istra" Pula

   Prima mjerenja iz Android aplikacije "ADIP Senzori"
   (akcelerometar ax/ay/az, svjetlo lux, infrazvuk, barometar p_hpa).

     GET    /api/senzori/status          → provjera rada (bez tokena)
     POST   /api/senzori/batch           → dio mjerenja uživo (JSON, do ~2 MB)
     POST   /api/senzori/upload          → cijela sesija kao CSV (multipart, do 50 MB)
     GET    /api/senzori/sesije          → popis sesija, najnovije prve
     GET    /api/senzori/sesije/:id      → metapodaci + zadnjih ?limit=N redaka
     GET    /api/senzori/sesije/:id/csv  → preuzimanje CSV-a
     DELETE /api/senzori/sesije/:id      → brisanje sesije

   Svi pozivi osim /status traže zaglavlje
       Authorization: Bearer <token>
   (isti token koji /api/login u server.js vraća pri prijavi).

   Podaci: data/senzori/<sessionId>.csv + data/senzori/index.json.
   Deploy isključuje data/, pa mjerenja preživljavaju svaki deploy.

   ── UGRADNJA ────────────────────────────────────────────
   U server.js, IZNAD reda `app.listen(...)`, dodaj jedan red:

       require('./senzori/server/senzori-api')(app);

   Ništa drugo. Modul koristi samo ugrađene Node module (fs, path, os)
   te express i multer, koji su već ovisnosti stranice.
   ========================================================= */

'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

const STUPCI = ['t_ms', 'ax', 'ay', 'az', 'lux', 'infra', 'p_hpa'];
const ID_UZORAK = /^[A-Za-z0-9_-]{1,64}$/;

module.exports = function registrirajSenzoriApi(app, opcije) {
  opcije = opcije || {};

  /* express i multer iz adip-web/node_modules; test ih može proslijediti. */
  const express = opcije.express || require('express');
  const multer = opcije.multer || require('multer');

  /* Isti token kao /api/login u server.js. Kasnije zamijeniti pravom
     prijavom (vidi README). */
  const TOKEN = opcije.token || 'fake-jwt-token-adip';
  const DATA_DIR = path.resolve(opcije.dataDir || path.join(__dirname, '..', '..', 'data', 'senzori'));
  const INDEX_FILE = path.join(DATA_DIR, 'index.json');
  const TMP_DIR = path.join(DATA_DIR, '.tmp');

  fs.mkdirSync(TMP_DIR, { recursive: true });

  /* ── pomoćne funkcije ─────────────────────────────── */

  function greska(res, kod, poruka) {
    return res.status(kod).json({ ok: false, error: poruka });
  }

  function provjeriToken(req, res, next) {
    const z = req.headers.authorization || '';
    const m = /^Bearer\s+(.+)$/i.exec(z);
    if (!m || m[1].trim() !== TOKEN) return greska(res, 401, 'Nije dopušteno — neispravan ili nedostaje token.');
    next();
  }

  /* Putanja do CSV-a sesije. Id se provjerava regexom, a putanja se još
     jednom provjerava da ostaje unutar DATA_DIR (zaštita od ../). */
  function csvPutanja(id) {
    if (typeof id !== 'string' || !ID_UZORAK.test(id)) return null;
    const p = path.join(DATA_DIR, id + '.csv');
    if (path.dirname(p) !== DATA_DIR) return null;
    return p;
  }

  function citajIndex() {
    try {
      const v = JSON.parse(fs.readFileSync(INDEX_FILE, 'utf8'));
      return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
    } catch (e) {
      return {};
    }
  }

  /* Zapis preko privremene datoteke pa preimenovanje — nikad polovičan index. */
  function zapisiIndex(idx) {
    const tmp = INDEX_FILE + '.' + process.pid + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(idx, null, 2), 'utf8');
    fs.renameSync(tmp, INDEX_FILE);
  }

  function tekst(v, max) {
    return typeof v === 'string' ? v.slice(0, max || 200) : '';
  }

  /* Jedna ćelija CSV-a: broj ili prazno (nedostaje). */
  function celija(v) {
    if (v === null || v === undefined || v === '') return '';
    const n = Number(v);
    return Number.isFinite(n) ? String(n) : null;
  }

  /* Rastavi CSV na komentare, zaglavlje i retke podataka. */
  function rastaviCsv(sadrzaj) {
    const linije = sadrzaj.split(/\r?\n/);
    const komentari = [];
    let zaglavlje = null;
    const redovi = [];
    for (const l of linije) {
      if (!l.trim()) continue;
      if (zaglavlje === null) {
        if (l.startsWith('#')) { komentari.push(l); continue; }
        zaglavlje = l.split(',').map(function (s) { return s.trim(); });
        continue;
      }
      if (l.startsWith('#')) continue;
      redovi.push(l);
    }
    return { komentari: komentari, zaglavlje: zaglavlje, redovi: redovi };
  }

  function redUNiz(l) {
    return l.split(',').map(function (s) {
      s = s.trim();
      if (s === '') return null;
      const n = Number(s);
      return Number.isFinite(n) ? n : null;
    });
  }

  function javniMeta(m) {
    return {
      sessionId: m.sessionId,
      deviceId: m.deviceId || '',
      deviceModel: m.deviceModel || '',
      appVersion: m.appVersion || '',
      startedAt: m.startedAt || null,
      sampleRateHz: m.sampleRateHz == null ? null : m.sampleRateHz,
      columns: m.columns || STUPCI,
      rows: m.rows || 0,
      updatedAt: m.updatedAt,
      bytes: m.bytes || 0
    };
  }

  /* ── parser za /batch ─────────────────────────────────
     server.js već ima globalni bodyParser.json() s ograničenjem 100 kB,
     koji bi odbio veće dijelove. Zato vlastiti parser (2 MB) premještamo
     na početak Express lanca; globalni parser zatim preskače već
     pročitano tijelo. */
  const jsonVeliki = express.json({ limit: opcije.batchLimit || '2mb' });
  app.use('/api/senzori/batch', jsonVeliki);
  try {
    const stog = app.router && app.router.stack;
    if (Array.isArray(stog) && stog.length > 1) stog.unshift(stog.pop());
  } catch (e) {
    console.warn('[senzori] ne mogu premjestiti JSON parser na početak:', e.message);
  }

  const upload = multer({
    dest: TMP_DIR,
    limits: { fileSize: opcije.uploadLimit || 50 * 1024 * 1024, files: 1 }
  });

  /* ── rute ─────────────────────────────────────────── */

  app.get('/api/senzori/status', function (req, res) {
    res.json({ ok: true, service: 'adip-senzori', version: 1, time: new Date().toISOString() });
  });

  app.post('/api/senzori/batch', provjeriToken, function (req, res) {
    const b = req.body || {};
    const p = csvPutanja(b.sessionId);
    if (!p) return greska(res, 400, 'Neispravan sessionId (dopušteno A-Z a-z 0-9 _ -, najviše 64 znaka).');
    if (typeof b.deviceId !== 'string' || !b.deviceId) return greska(res, 400, 'Nedostaje deviceId.');
    if (!Array.isArray(b.columns) || !b.columns.length || !b.columns.every(function (c) { return typeof c === 'string' && /^[A-Za-z0-9_]{1,32}$/.test(c); })) {
      return greska(res, 400, 'Neispravni columns.');
    }
    if (!Array.isArray(b.rows)) return greska(res, 400, 'Nedostaje rows.');

    const ncol = b.columns.length;
    const linije = [];
    for (let i = 0; i < b.rows.length; i++) {
      const r = b.rows[i];
      if (!Array.isArray(r) || r.length !== ncol) return greska(res, 400, 'Redak ' + i + ' nema ' + ncol + ' vrijednosti.');
      const c = r.map(celija);
      if (c.indexOf(null) !== -1) return greska(res, 400, 'Redak ' + i + ' sadrži vrijednost koja nije broj ni null.');
      linije.push(c.join(','));
    }

    try {
      const idx = citajIndex();
      let m = idx[b.sessionId];
      const sada = new Date().toISOString();

      if (m && fs.existsSync(p)) {
        if ((m.columns || STUPCI).join(',') !== b.columns.join(',')) {
          return greska(res, 409, 'Stupci se ne podudaraju sa stupcima sesije: ' + (m.columns || STUPCI).join(','));
        }
      } else {
        /* Prvi dio — nova sesija: komentari s metapodacima + zaglavlje. */
        const sr = Number(b.sampleRateHz);
        m = {
          sessionId: b.sessionId,
          deviceId: tekst(b.deviceId),
          deviceModel: tekst(b.deviceModel),
          appVersion: tekst(b.appVersion, 50),
          startedAt: tekst(b.startedAt, 50) || sada,
          sampleRateHz: Number.isFinite(sr) ? sr : null,
          columns: b.columns.slice(),
          rows: 0,
          createdAt: sada
        };
        const glava =
          '# sessionId=' + m.sessionId + '\n' +
          '# deviceId=' + m.deviceId.replace(/[\r\n]/g, ' ') + '\n' +
          (m.deviceModel ? '# deviceModel=' + m.deviceModel.replace(/[\r\n]/g, ' ') + '\n' : '') +
          '# startedAt=' + m.startedAt.replace(/[\r\n]/g, ' ') + '\n' +
          (m.sampleRateHz != null ? '# sampleRateHz=' + m.sampleRateHz + '\n' : '') +
          m.columns.join(',') + '\n';
        fs.writeFileSync(p, glava, 'utf8');
      }

      if (linije.length) fs.appendFileSync(p, linije.join('\n') + '\n', 'utf8');
      m.rows = (m.rows || 0) + linije.length;
      m.updatedAt = sada;
      m.bytes = fs.statSync(p).size;
      idx[b.sessionId] = m;
      zapisiIndex(idx);

      res.json({ ok: true, sessionId: b.sessionId, received: linije.length, total: m.rows });
    } catch (e) {
      console.error('[senzori] zapis batcha nije uspio:', e);
      greska(res, 500, 'Zapis na disk nije uspio (' + (e.code || e.message) + ').');
    }
  });

  app.post('/api/senzori/upload', provjeriToken, function (req, res) {
    upload.single('file')(req, res, function (err) {
      const f = req.file;
      function pocisti() { if (f) fs.unlink(f.path, function () {}); }

      if (err) {
        pocisti();
        if (err.code === 'LIMIT_FILE_SIZE') return greska(res, 413, 'Datoteka je veća od dopuštenog (50 MB).');
        return greska(res, 400, 'Neispravan upload: ' + err.message);
      }
      const b = req.body || {};
      const p = csvPutanja(b.sessionId);
      if (!p) { pocisti(); return greska(res, 400, 'Neispravan sessionId (dopušteno A-Z a-z 0-9 _ -, najviše 64 znaka).'); }
      if (!b.deviceId) { pocisti(); return greska(res, 400, 'Nedostaje deviceId.'); }
      if (!f) return greska(res, 400, 'Nedostaje datoteka (polje "file").');

      try {
        const d = rastaviCsv(fs.readFileSync(f.path, 'utf8'));
        if (!d.zaglavlje || d.zaglavlje[0] !== 't_ms') {
          pocisti();
          return greska(res, 400, 'CSV nema zaglavlje koje počinje s t_ms.');
        }
        /* Metapodaci iz komentara (# kljuc=vrijednost), polja forme imaju prednost. */
        const kom = {};
        d.komentari.forEach(function (l) {
          const mm = /^#\s*([A-Za-z0-9_]+)\s*=\s*(.*)$/.exec(l);
          if (mm) kom[mm[1]] = mm[2].trim();
        });
        const sr = Number(b.sampleRateHz != null && b.sampleRateHz !== '' ? b.sampleRateHz : kom.sampleRateHz);
        const prvi = d.redovi.length ? redUNiz(d.redovi[0])[0] : null;
        const sada = new Date().toISOString();

        fs.renameSync(f.path, p);   // zamjenjuje postojeću sesiju (atomarno)

        const idx = citajIndex();
        const stari = idx[b.sessionId] || {};
        idx[b.sessionId] = {
          sessionId: b.sessionId,
          deviceId: tekst(b.deviceId),
          deviceModel: tekst(b.deviceModel) || kom.deviceModel || stari.deviceModel || '',
          appVersion: tekst(b.appVersion, 50) || kom.appVersion || stari.appVersion || '',
          startedAt: kom.startedAt || stari.startedAt || (prvi != null ? new Date(prvi).toISOString() : sada),
          sampleRateHz: Number.isFinite(sr) ? sr : (stari.sampleRateHz == null ? null : stari.sampleRateHz),
          columns: d.zaglavlje,
          rows: d.redovi.length,
          createdAt: stari.createdAt || sada,
          updatedAt: sada,
          bytes: fs.statSync(p).size
        };
        zapisiIndex(idx);
        res.json({ ok: true, sessionId: b.sessionId, rows: d.redovi.length });
      } catch (e) {
        pocisti();
        console.error('[senzori] upload nije uspio:', e);
        greska(res, 500, 'Zapis na disk nije uspio (' + (e.code || e.message) + ').');
      }
    });
  });

  app.get('/api/senzori/sesije', provjeriToken, function (req, res) {
    const idx = citajIndex();
    const popis = Object.keys(idx).map(function (k) { return javniMeta(idx[k]); });
    popis.sort(function (a, b) {
      return String(b.startedAt || b.updatedAt).localeCompare(String(a.startedAt || a.updatedAt));
    });
    res.json(popis);
  });

  /* Zajednička provjera za rute s :id */
  function nadjiSesiju(req, res) {
    const p = csvPutanja(req.params.id);
    if (!p) { greska(res, 400, 'Neispravan sessionId.'); return null; }
    const m = citajIndex()[req.params.id];
    if (!m || !fs.existsSync(p)) { greska(res, 404, 'Sesija ne postoji.'); return null; }
    return { p: p, m: m };
  }

  app.get('/api/senzori/sesije/:id', provjeriToken, function (req, res) {
    const s = nadjiSesiju(req, res);
    if (!s) return;
    let limit = parseInt(req.query.limit, 10);
    if (!Number.isFinite(limit) || limit < 0) limit = 1000;
    try {
      const d = rastaviCsv(fs.readFileSync(s.p, 'utf8'));
      const zadnji = limit ? d.redovi.slice(-limit) : [];
      const out = javniMeta(s.m);
      out.ok = true;
      out.columns = d.zaglavlje || out.columns;
      out.totalRows = d.redovi.length;
      out.rows = zadnji.map(redUNiz);
      res.json(out);
    } catch (e) {
      greska(res, 500, 'Čitanje nije uspjelo (' + (e.code || e.message) + ').');
    }
  });

  app.get('/api/senzori/sesije/:id/csv', provjeriToken, function (req, res) {
    const s = nadjiSesiju(req, res);
    if (!s) return;
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', 'attachment; filename="' + req.params.id + '.csv"');
    fs.createReadStream(s.p).pipe(res);
  });

  app.delete('/api/senzori/sesije/:id', provjeriToken, function (req, res) {
    const p = csvPutanja(req.params.id);
    if (!p) return greska(res, 400, 'Neispravan sessionId.');
    try {
      const idx = citajIndex();
      const postoji = !!idx[req.params.id] || fs.existsSync(p);
      if (!postoji) return greska(res, 404, 'Sesija ne postoji.');
      delete idx[req.params.id];
      zapisiIndex(idx);
      fs.rmSync(p, { force: true });
      res.json({ ok: true });
    } catch (e) {
      greska(res, 500, 'Brisanje nije uspjelo (' + (e.code || e.message) + ').');
    }
  });

  console.log('[senzori] API registriran → ' + DATA_DIR);
};
