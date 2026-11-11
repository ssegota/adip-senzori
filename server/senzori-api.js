/* =========================================================
   ADIP Senzori — poslužiteljski dio
   Astronomsko društvo "Istra" Pula

   Prima mjerenja iz Android aplikacije "ADIP Senzori"
   (akcelerometar ax/ay/az, svjetlo lux, infrazvuk, barometar p_hpa).

   Javno (bez prijave):
     GET    /api/senzori/status          → provjera rada
     GET    /api/senzori/sesije          → popis sesija, najnovije prve
     GET    /api/senzori/sesije/:id      → metapodaci + zadnjih ?limit=N redaka
     GET    /api/senzori/sesije/:id/csv  → preuzimanje CSV-a
   (javni odgovori ne sadrže deviceId)

   Token aplikacije (Authorization: Bearer <token>):
     GET    /api/senzori/token           → provjera tokena
     POST   /api/senzori/batch           → dio mjerenja uživo (JSON, do ~2 MB)
     POST   /api/senzori/upload          → cijela sesija kao CSV (multipart, do 50 MB)

   Lozinka (u tijelu ili zaglavlju X-Senzori-Lozinka):
     POST   /api/senzori/tokeni          → novi token {lozinka, naziv}
     GET    /api/senzori/tokeni          → popis tokena
     DELETE /api/senzori/tokeni/:id      → opoziv tokena

   Token ILI lozinka:
     DELETE /api/senzori/sesije/:id      → brisanje sesije

   Lozinka NIJE u repozitoriju — samo njezin scrypt hash (LOZINKA_HASH),
   vidi README.md za promjenu.

   Podaci: data/senzori/<sessionId>.csv, index.json, tokeni.json.
   Deploy isključuje data/, pa mjerenja i tokeni preživljavaju svaki deploy.

   ── UGRADNJA ────────────────────────────────────────────
   U server.js, IZNAD reda `app.listen(...)`, dodaj jedan red:

       require('./senzori/server/senzori-api')(app);

   Modul koristi samo ugrađene Node module (fs, path, crypto, stream)
   te express i multer, koji su već ovisnosti stranice.
   ========================================================= */

'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Transform } = require('stream');

const STUPCI = ['t_ms', 'ax', 'ay', 'az', 'lux', 'infra', 'p_hpa'];
const ID_UZORAK = /^[A-Za-z0-9_-]{1,64}$/;

/* scrypt hash lozinke za upravljanje tokenima: 'scrypt$<sol hex>$<hash hex>'.
   Lozinka sama nije u repozitoriju. Promjena: vidi README.md. */
const LOZINKA_HASH = 'scrypt$80a5dc86cab5017fae6f1879909abd55$167447d394e0562e22be2330cc3d1cad678ccf4fcc53eee85a91bd1c534d8df7';

const MAX_PROMASAJA = 10;                 // pogrešnih lozinki po IP-u…
const PROZOR_PROMASAJA = 15 * 60 * 1000;  // …u 15 minuta, zatim 429
const LAST_USED_RAZMAK = 60 * 1000;       // lastUsed se zapisuje najviše jednom u minuti

module.exports = function registrirajSenzoriApi(app, opcije) {
  opcije = opcije || {};

  /* express i multer iz adip-web/node_modules; test ih može proslijediti. */
  const express = opcije.express || require('express');
  const multer = opcije.multer || require('multer');

  const LOZINKA = opcije.lozinkaHash || process.env.SENZORI_LOZINKA_HASH || LOZINKA_HASH;
  const DATA_DIR = path.resolve(opcije.dataDir || path.join(__dirname, '..', '..', 'data', 'senzori'));
  const INDEX_FILE = path.join(DATA_DIR, 'index.json');
  const TOKENI_FILE = path.join(DATA_DIR, 'tokeni.json');
  const TMP_DIR = path.join(DATA_DIR, '.tmp');

  fs.mkdirSync(TMP_DIR, { recursive: true });

  /* ── pomoćne funkcije ─────────────────────────────── */

  function greska(res, kod, poruka) {
    return res.status(kod).json({ ok: false, error: poruka });
  }

  function sha256(s) {
    return crypto.createHash('sha256').update(String(s), 'utf8').digest('hex');
  }

  /* Usporedba u konstantnom vremenu (oba niza iste duljine — hex hashevi). */
  function jednako(a, b) {
    const x = Buffer.from(String(a)), y = Buffer.from(String(b));
    return x.length === y.length && crypto.timingSafeEqual(x, y);
  }

  /* Klijentska IP adresa. Iza Apache proxyja ZADNJI unos X-Forwarded-For dodaje sam
     proxy (stvarna IP klijenta); prve unose klijent može lažirati pa ih ne koristimo. */
  function ipAdresa(req) {
    const xff = req.headers['x-forwarded-for'];
    if (typeof xff === 'string' && xff.trim()) {
      const dijelovi = xff.split(',').map(s => s.trim()).filter(Boolean);
      if (dijelovi.length) return dijelovi[dijelovi.length - 1];
    }
    return (req.socket && req.socket.remoteAddress) || '?';
  }

  /* ── lozinka + ograničenje pokušaja ───────────────── */

  const promasaji = new Map();   // ip → { n, od }

  function blokiran(ip) {
    const z = promasaji.get(ip);
    if (!z) return false;
    if (Date.now() - z.od > PROZOR_PROMASAJA) { promasaji.delete(ip); return false; }
    return z.n >= MAX_PROMASAJA;
  }

  function zabiljeziPromasaj(ip) {
    const sada = Date.now();
    const z = promasaji.get(ip);
    if (!z || sada - z.od > PROZOR_PROMASAJA) promasaji.set(ip, { n: 1, od: sada });
    else z.n++;
    if (promasaji.size > 10000) {   // zaštita memorije
      for (const [k, v] of promasaji) if (sada - v.od > PROZOR_PROMASAJA) promasaji.delete(k);
    }
  }

  function lozinkaIspravna(lozinka) {
    if (typeof lozinka !== 'string' || !lozinka || lozinka.length > 200) return false;
    const d = String(LOZINKA).split('$');
    if (d.length !== 3 || d[0] !== 'scrypt') return false;
    const ocekivano = Buffer.from(d[2], 'hex');
    if (!ocekivano.length) return false;
    const izracun = crypto.scryptSync(lozinka, Buffer.from(d[1], 'hex'), ocekivano.length);
    return crypto.timingSafeEqual(izracun, ocekivano);
  }

  /* Provjeri lozinku; pri neuspjehu pošalje 401/429 i vrati false. */
  function provjeriLozinku(req, res, lozinka) {
    const ip = ipAdresa(req);
    if (blokiran(ip)) {
      greska(res, 429, 'Previše pogrešnih pokušaja — pokušajte ponovno za 15 minuta.');
      return false;
    }
    if (!lozinkaIspravna(lozinka)) {
      zabiljeziPromasaj(ip);
      greska(res, 401, 'Pogrešna lozinka');
      return false;
    }
    return true;
  }

  function lozinkaIzZaglavlja(req) {
    const v = req.headers['x-senzori-lozinka'];
    return typeof v === 'string' ? v : '';
  }

  /* ── tokeni ───────────────────────────────────────── */

  function citajTokene() {
    try {
      const v = JSON.parse(fs.readFileSync(TOKENI_FILE, 'utf8'));
      return Array.isArray(v) ? v : [];
    } catch (e) {
      return [];
    }
  }

  function zapisiAtomarno(datoteka, vrijednost) {
    const tmp = datoteka + '.' + process.pid + '.' + crypto.randomBytes(4).toString('hex') + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(vrijednost, null, 2), 'utf8');
    fs.renameSync(tmp, datoteka);
  }

  function javniToken(t) {
    return { id: t.id, naziv: t.naziv, created: t.created, lastUsed: t.lastUsed || null };
  }

  /* Token iz Authorization: Bearer … → zapis iz tokeni.json ili null. */
  function nadjiToken(req) {
    const m = /^Bearer\s+(\S+)\s*$/i.exec(req.headers.authorization || '');
    if (!m) return null;
    const h = sha256(m[1]);
    let nadjen = null;
    for (const t of citajTokene()) if (typeof t.hash === 'string' && jednako(t.hash, h)) nadjen = t;
    return nadjen;
  }

  const zadnjiZapisKoristenja = new Map();   // id → ms

  function oznaciKoristenje(t) {
    const sada = Date.now();
    if (sada - (zadnjiZapisKoristenja.get(t.id) || 0) < LAST_USED_RAZMAK) return;
    zadnjiZapisKoristenja.set(t.id, sada);
    try {
      const svi = citajTokene();
      const z = svi.find(function (x) { return x.id === t.id; });
      if (!z) return;
      z.lastUsed = new Date(sada).toISOString();
      zapisiAtomarno(TOKENI_FILE, svi);
    } catch (e) {
      console.warn('[senzori] lastUsed nije zapisan:', e.message);
    }
  }

  function provjeriToken(req, res, next) {
    const t = nadjiToken(req);
    if (!t) return greska(res, 401, 'Nije dopušteno — neispravan ili nedostaje token.');
    req.senzoriToken = t;
    oznaciKoristenje(t);
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
    zapisiAtomarno(INDEX_FILE, idx);
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

  /* Javni metapodaci — bez deviceId (i bez tokenId). */
  function javniMeta(m) {
    return {
      sessionId: m.sessionId,
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
  function naPocetak(opis) {
    try {
      const stog = app.router && app.router.stack;
      if (Array.isArray(stog) && stog.length > 1) stog.unshift(stog.pop());
    } catch (e) {
      console.warn('[senzori] ne mogu premjestiti ' + opis + ' na početak:', e.message);
    }
  }

  const jsonVeliki = express.json({ limit: opcije.batchLimit || '2mb' });
  app.use('/api/senzori/batch', jsonVeliki);
  naPocetak('JSON parser');

  /* server.js poslužuje cijeli direktorij kao statiku (express.static(__dirname)),
     pa bi data/senzori/ (index.json s deviceId, tokeni.json) bio javno dostupan.
     Blokada ide na sam početak lanca, ispred statike. */
  app.use('/data/senzori', function (req, res) { res.status(404).send('Not found'); });
  naPocetak('blokadu data/senzori');

  const jsonMali = express.json({ limit: '10kb' });

  const upload = multer({
    dest: TMP_DIR,
    limits: { fileSize: opcije.uploadLimit || 50 * 1024 * 1024, files: 1 }
  });

  /* ── rute ─────────────────────────────────────────── */

  app.get('/api/senzori/status', function (req, res) {
    res.json({ ok: true, service: 'adip-senzori', version: 2, time: new Date().toISOString() });
  });

  /* ── upravljanje tokenima (lozinka) ── */

  app.post('/api/senzori/tokeni', jsonMali, function (req, res) {
    const b = req.body || {};
    if (!provjeriLozinku(req, res, b.lozinka)) return;
    const naziv = tekst(b.naziv, 100).replace(/[\r\n\t]/g, ' ').trim() || 'Bez naziva';
    try {
      const svi = citajTokene();
      const token = crypto.randomBytes(32).toString('hex');
      let id;
      do { id = crypto.randomBytes(4).toString('hex'); } while (svi.some(function (t) { return t.id === id; }));
      const zapis = { id: id, naziv: naziv, hash: sha256(token), created: new Date().toISOString(), lastUsed: null };
      svi.push(zapis);
      zapisiAtomarno(TOKENI_FILE, svi);
      res.json({ ok: true, token: token, id: id, naziv: naziv, created: zapis.created });
    } catch (e) {
      console.error('[senzori] zapis tokena nije uspio:', e);
      greska(res, 500, 'Zapis tokena nije uspio (' + (e.code || e.message) + ').');
    }
  });

  app.get('/api/senzori/tokeni', function (req, res) {
    if (!provjeriLozinku(req, res, lozinkaIzZaglavlja(req))) return;
    res.json({ ok: true, tokeni: citajTokene().map(javniToken) });
  });

  app.delete('/api/senzori/tokeni/:id', function (req, res) {
    if (!provjeriLozinku(req, res, lozinkaIzZaglavlja(req))) return;
    try {
      const svi = citajTokene();
      const ostali = svi.filter(function (t) { return t.id !== req.params.id; });
      if (ostali.length === svi.length) return greska(res, 404, 'Token ne postoji.');
      zapisiAtomarno(TOKENI_FILE, ostali);
      zadnjiZapisKoristenja.delete(req.params.id);
      res.json({ ok: true });
    } catch (e) {
      greska(res, 500, 'Opoziv nije uspio (' + (e.code || e.message) + ').');
    }
  });

  /* Provjera tokena iz aplikacije. */
  app.get('/api/senzori/token', provjeriToken, function (req, res) {
    const t = req.senzoriToken;
    res.json({ ok: true, id: t.id, naziv: t.naziv, created: t.created });
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
          createdAt: sada,
          tokenId: req.senzoriToken.id
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
      m.tokenId = req.senzoriToken.id;
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
          tokenId: req.senzoriToken.id,
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

  app.get('/api/senzori/sesije', function (req, res) {
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

  app.get('/api/senzori/sesije/:id', function (req, res) {
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

  /* Izbacuje red "# deviceId=…" iz komentara na početku CSV-a; ostatak
     (od zaglavlja nadalje) prolazi nepromijenjen. */
  function bezDeviceId() {
    let glava = true, ostatak = '';
    return new Transform({
      transform: function (chunk, enc, cb) {
        if (!glava) return cb(null, chunk);
        ostatak += chunk.toString('utf8');
        let out = '';
        let i;
        while (glava && (i = ostatak.indexOf('\n')) !== -1) {
          const l = ostatak.slice(0, i + 1);
          ostatak = ostatak.slice(i + 1);
          if (/^#\s*deviceId\s*=/i.test(l)) continue;
          if (l.trim() && !l.startsWith('#')) glava = false;
          out += l;
        }
        if (!glava) { out += ostatak; ostatak = ''; }
        cb(null, out);
      },
      flush: function (cb) {
        cb(null, /^#\s*deviceId\s*=/i.test(ostatak) ? '' : ostatak);
      }
    });
  }

  app.get('/api/senzori/sesije/:id/csv', function (req, res) {
    const s = nadjiSesiju(req, res);
    if (!s) return;
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', 'attachment; filename="' + req.params.id + '.csv"');
    fs.createReadStream(s.p).pipe(bezDeviceId()).pipe(res);
  });

  /* Brisanje: valjani token ILI lozinka u X-Senzori-Lozinka. */
  function tokenIliLozinka(req, res, next) {
    const t = nadjiToken(req);
    if (t) { req.senzoriToken = t; oznaciKoristenje(t); return next(); }
    const loz = lozinkaIzZaglavlja(req);
    if (!loz) return greska(res, 401, 'Nije dopušteno — potreban je token ili lozinka.');
    if (provjeriLozinku(req, res, loz)) next();
  }

  app.delete('/api/senzori/sesije/:id', tokenIliLozinka, function (req, res) {
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
