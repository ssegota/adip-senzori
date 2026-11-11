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

Opcije (drugi argument): `{ token, dataDir, batchLimit, uploadLimit }`.

### Podaci

- `data/senzori/<sessionId>.csv` — jedna datoteka po sesiji
- `data/senzori/index.json` — metapodaci (zapisuje se atomarno: tmp + rename)
- `data/senzori/.tmp/` — privremene datoteke uploada

`deploy/sftp-deploy.sh` isključuje `data/`, pa mjerenja **preživljavaju svaki deploy**.
Mapa se stvara sama; Passenger korisnik mora imati pravo pisanja u `data/`.

### Napomena o JSON parseru

`server.js` ima globalni `bodyParser.json()` s ograničenjem 100 kB. Modul zato
registrira vlastiti parser (2 MB) samo za `/api/senzori/batch` i premješta ga na
početak Express lanca; globalni parser tada preskače već pročitano tijelo.
Ostale rute stranice nisu pogođene.

## Autentikacija

Sve osim `GET /api/senzori/status` traži zaglavlje:
```
Authorization: Bearer fake-jwt-token-adip
```
Bez ispravnog tokena: `401 {"ok":false,"error":"..."}`.

> **TODO:** token je trenutno isti fiksni niz koji vraća `/api/login`. Kasnije ga
> treba zamijeniti pravom prijavom (npr. potpisani JWT s rokom trajanja ili
> zaseban API ključ po uređaju). Do tada se može proslijediti drugi:
> `require('./senzori/server/senzori-api')(app, { token: 'novi' })`.

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

U primjerima: `H='Authorization: Bearer fake-jwt-token-adip'`, `U=https://adip.hr/api/senzori`.

### GET /status (bez tokena)
```
curl $U/status
→ {"ok":true,"service":"adip-senzori","version":1,"time":"2026-11-11T10:00:00.000Z"}
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

### GET /sesije — popis, najnovije prve
```
curl -H "$H" $U/sesije
→ [{"sessionId":"...","deviceId":"abc123","deviceModel":"Pixel 8","appVersion":"1.0",
    "startedAt":"2026-11-11T10:00:00Z","sampleRateHz":50,"columns":[...],
    "rows":15000,"updatedAt":"...","bytes":812345}]
```

### GET /sesije/:id?limit=N — metapodaci + zadnjih N redaka (zadano 1000)
```
curl -H "$H" "$U/sesije/2026-11-11_pixel_01?limit=3"
→ {"ok":true,"sessionId":"...", ...metapodaci..., "columns":[...],
   "totalRows":15000, "rows":[[t,ax,ay,az,lux,infra,p],...]}
```
Ovdje je `rows` niz redaka (prazna ćelija → `null`), a ukupan broj je u `totalRows`.
Nepostojeća sesija → 404.

### GET /sesije/:id/csv — preuzimanje
```
curl -H "$H" -o sesija.csv $U/sesije/2026-11-11_pixel_01/csv
```

### DELETE /sesije/:id
```
curl -X DELETE -H "$H" $U/sesije/2026-11-11_pixel_01
→ {"ok":true}
```

## Test

```
node server/test-senzori.js
```
Pokreće Express na slučajnom portu (express/multer/body-parser iz
`~/Documents/ADIP/adip-web/node_modules`, ili `ADIP_WEB=/put`), s privremenom
mapom podataka, i prolazi sve rute.
