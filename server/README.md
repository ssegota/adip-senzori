# ADIP Senzori — poslužiteljski API

Express modul koji prima mjerenja iz Android aplikacije **ADIP Senzori**
(akcelerometar `ax/ay/az`, svjetlo `lux`, infrazvuk `infra`, barometar `p_hpa`).
Ugrađuje se u stranicu `adip-web` (https://adip.hr) jednim redom.

## Ugradnja

1. Kopiraj ovu mapu `server/` u adip-web kao `senzori/server/`:
   ```
   adip-web/senzori/server/senzori-api.js
   adip-web/senzori/server/test-senzori.js   (nije nužno)
   ```
2. U `server.js`, **iznad** reda `app.listen(...)` (uz red za tečaj) dodaj:
   ```js
   require('./senzori/server/senzori-api')(app);
   ```
3. Deploy kao i obično. Nema novih ovisnosti (samo `express` i `multer`, već su tu).

Opcije (drugi argument): `{ lozinkaHash, dataDir, batchLimit, uploadLimit }`.

### Podaci

- `data/senzori/<sessionId>.csv` — jedna datoteka po sesiji
- `data/senzori/index.json` — metapodaci (zapisuje se atomarno: tmp + rename)
- `data/senzori/tokeni.json` — tokeni aplikacije (samo sha256 hash tokena, nikad sam token)
- `data/senzori/.tmp/` — privremene datoteke uploada

`deploy/sftp-deploy.sh` isključuje `data/`, pa mjerenja **preživljavaju svaki deploy**.
Mapa se stvara sama; Passenger korisnik mora imati pravo pisanja u `data/`.

`server.js` poslužuje cijeli direktorij kao statiku, pa modul na početak Express
lanca stavlja blokadu za `/data/senzori/*` (404) — `index.json` (s `deviceId`) i
`tokeni.json` nisu javno dostupni.

### Napomena o JSON parseru

`server.js` ima globalni `bodyParser.json()` s ograničenjem 100 kB. Modul zato
registrira vlastiti parser (2 MB) samo za `/api/senzori/batch` i premješta ga na
početak Express lanca; globalni parser tada preskače već pročitano tijelo.
Ostale rute stranice nisu pogođene.

## Autentikacija

| Što | Kako |
|---|---|
| čitanje (`/status`, `/sesije`, `/sesije/:id`, `/sesije/:id/csv`) | **javno**, bez prijave; odgovori ne sadrže `deviceId` |
| slanje mjerenja (`/batch`, `/upload`), provjera tokena (`/token`) | `Authorization: Bearer <token>` |
| upravljanje tokenima (`/tokeni`) | lozinka (`lozinka` u tijelu ili `X-Senzori-Lozinka`) |
| brisanje sesije | token **ili** `X-Senzori-Lozinka` |

Stari fiksni `fake-jwt-token-adip` više **ne vrijedi**. Bez ispravnog tokena/lozinke:
`401 {"ok":false,"error":"..."}`.

### Lozinka

Lozinka za izradu tokena **nije u repozitoriju** (repozitorij je javan) — u
`senzori-api.js` je samo njezin scrypt hash (`LOZINKA_HASH`, oblik
`scrypt$<sol hex>$<hash hex>`), a provjera ide s `crypto.timingSafeEqual`.
Najviše 10 pogrešnih pokušaja po IP adresi u 15 minuta, zatim `429`
(iza Apache proxyja IP je prvi unos `X-Forwarded-For`).

**Promjena lozinke** — izračunaj novi hash:
```
node -e "const c=require('crypto'),s=c.randomBytes(16);console.log('scrypt\$'+s.toString('hex')+'\$'+c.scryptSync(process.argv[1],s,32).toString('hex'))" 'NOVA-LOZINKA'
```
i zatim **ili** zamijeni `LOZINKA_HASH` u `senzori-api.js` (commit + deploy),
**ili** postavi varijablu okoline `SENZORI_LOZINKA_HASH` (cPanel → Setup Node.js
App → Environment variables) pa restartaj aplikaciju. Redoslijed prednosti:
opcija `lozinkaHash` → `SENZORI_LOZINKA_HASH` → `LOZINKA_HASH`. Postojeći tokeni
ostaju valjani.

### Tokeni

Token je 32 nasumična bajta (64 hex znaka) i prikazuje se **samo jednom**, pri
izradi. U `tokeni.json` se sprema `{id, naziv, hash: sha256(token), created, lastUsed}`;
`lastUsed` se osvježava najviše jednom u minuti. Id tokena koji je poslao
mjerenja bilježi se u metapodacima sesije (`tokenId`, nije javan).
Tokene se izrađuje i opoziva na stranici https://adip.hr/senzori/ (odjeljak
»Tokeni za aplikaciju«) ili izravno preko API-ja (dolje). U aplikaciji:
Postavke → Web servis → URL `https://adip.hr`, Token.

## CSV format (zajednički s aplikacijom)

```
# sampleRateHz=50
# deviceModel=Pixel 8
t_ms,ax,ay,az,lux,infra,p_hpa
1762855200000,0.12,-0.03,9.81,120.5,0.0012,1013.25
1762855200020,0.11,-0.02,9.80,,0.0010,
```
- opcionalni reci komentara `# kljuc=vrijednost` na početku
- zaglavlje, zatim brojčani reci; prazno polje = nedostaje vrijednost
- jedinice: `t_ms` unix epoha u ms, `a*` m/s², `lux`, `infra` pojasno filtriran
  (≈0,5–20 Hz) signal mikrofona u relativnim jedinicama (ili Pa ako je kalibriran),
  `p_hpa` hPa

## API

U primjerima: `H='Authorization: Bearer <token>'`, `L='X-Senzori-Lozinka: <lozinka>'`, `U=https://adip.hr/api/senzori`.

### GET /status (javno)
```
curl $U/status
→ {"ok":true,"service":"adip-senzori","version":2,"time":"2026-11-11T10:00:00.000Z"}
```

### POST /tokeni — novi token (lozinka)
```
curl -X POST -H 'Content-Type: application/json' $U/tokeni -d '{"lozinka":"...","naziv":"Pixel 8 — terenski"}'
→ {"ok":true,"token":"<64 hex>","id":"1a2b3c4d","naziv":"Pixel 8 — terenski","created":"2026-11-11T10:00:00.000Z"}
```
Pogrešna lozinka → `401 {"ok":false,"error":"Pogrešna lozinka"}`; previše pokušaja → `429`.

### GET /tokeni — popis tokena (lozinka u zaglavlju)
```
curl -H "$L" $U/tokeni
→ {"ok":true,"tokeni":[{"id":"1a2b3c4d","naziv":"...","created":"...","lastUsed":"..."|null}]}
```

### DELETE /tokeni/:id — opoziv (lozinka u zaglavlju)
```
curl -X DELETE -H "$L" $U/tokeni/1a2b3c4d
→ {"ok":true}        (nepostojeći → 404)
```

### GET /token — provjera tokena (za aplikaciju)
```
curl -H "$H" $U/token
→ {"ok":true,"id":"1a2b3c4d","naziv":"...","created":"..."}     (nevaljan → 401)
```

### POST /batch — dio mjerenja uživo (JSON, do ~2 MB)
```
curl -X POST -H "$H" -H 'Content-Type: application/json' $U/batch -d '{
  "sessionId":"2026-11-11_pixel_01", "deviceId":"abc123", "deviceModel":"Pixel 8",
  "appVersion":"1.0", "startedAt":"2026-11-11T10:00:00Z", "sampleRateHz":50,
  "columns":["t_ms","ax","ay","az","lux","infra","p_hpa"],
  "rows":[[1762855200000,0.12,-0.03,9.81,120.5,0.0012,1013.25],
          [1762855200020,0.11,-0.02,9.80,null,0.0010,null]] }'
→ {"ok":true,"sessionId":"2026-11-11_pixel_01","received":2,"total":2}
```
- `sessionId` mora odgovarati `^[A-Za-z0-9_-]{1,64}$` (inače 400)
- prvi batch stvara sesiju (CSV s komentarima + zaglavljem); sljedeći dodaju retke
- `columns` mora biti jednak stupcima sesije (inače 409); svaki redak mora imati
  isto broj vrijednosti, brojeve ili `null` (inače 400)

### POST /upload — cijela sesija kao CSV (multipart, do 50 MB)
```
curl -X POST -H "$H" -F file=@sesija.csv -F sessionId=2026-11-11_pixel_01 \
     -F deviceId=abc123 -F deviceModel='Pixel 8' -F sampleRateHz=50 $U/upload
→ {"ok":true,"sessionId":"2026-11-11_pixel_01","rows":15000}
```
Zamjenjuje postojeću sesiju istog id-a. `sampleRateHz`/`deviceModel`/`startedAt`
mogu doći i iz `#` komentara u CSV-u (polja forme imaju prednost). Prevelika datoteka → 413.

### GET /sesije — popis, najnovije prve (javno)
```
curl $U/sesije
→ [{"sessionId":"...","deviceModel":"Pixel 8","appVersion":"1.0",
    "startedAt":"2026-11-11T10:00:00Z","sampleRateHz":50,"columns":[...],
    "rows":15000,"updatedAt":"...","bytes":812345}]
```

### GET /sesije/:id?limit=N — metapodaci + zadnjih N redaka (zadano 1000, javno)
```
curl "$U/sesije/2026-11-11_pixel_01?limit=3"
→ {"ok":true,"sessionId":"...", ...metapodaci..., "columns":[...],
   "totalRows":15000, "rows":[[t,ax,ay,az,lux,infra,p],...]}
```
Ovdje je `rows` niz redaka (prazna ćelija → `null`), a ukupan broj je u `totalRows`.
Nepostojeća sesija → 404.

### GET /sesije/:id/csv — preuzimanje (javno)
```
curl -o sesija.csv $U/sesije/2026-11-11_pixel_01/csv
```
CSV se šalje kako je spremljen, osim reda `# deviceId=…` koji se izbacuje.

### DELETE /sesije/:id — token ili lozinka
```
curl -X DELETE -H "$H" $U/sesije/2026-11-11_pixel_01      # ili -H "$L"
→ {"ok":true}
```

## Test

```
node senzori/server/test-senzori.js
```
Pokreće Express na slučajnom portu (express/multer/body-parser iz
`node_modules` ove stranice, ili `ADIP_WEB=/put`), s privremenom
mapom podataka i vlastitom testnom lozinkom (`lozinkaHash`), i prolazi sve rute
(tokeni, ograničenje pokušaja, javna čitanja bez `deviceId`, blokada statike).
