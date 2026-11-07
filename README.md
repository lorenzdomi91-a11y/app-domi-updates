# app-domi-updates

File pubblici letti dall'app **VENATOR**.

| File | A cosa serve |
|---|---|
| `version.json` | Avviso di aggiornamento in-app |
| `config.json` | Impostazioni modificabili senza aggiornare l'app (vedi sotto) |
| `channels.txt` | Canali Telegram letti dallo scraper |
| `PRIVACY.md` | Privacy policy (link nel menu dell'app) |
| branch `data` → `offerte.json` | Offerte raccolte dallo scraper ogni 15 minuti |
| branch `apk` → `VENATOR.apk` | Ultimo APK per l'aggiornamento in-app |
| `scraper/` | Scraper (copia da VENATOR, `tools/sync-scraper.sh`) |

## config.json

- `realAds`: `false` = annunci di prova; `true` = annunci veri (solo con account AdMob approvato).
- `affiliateTag`: il tuo tag Amazon Associates (es. `nome-21`), vuoto = nessun tag.
- `feedMaxAgeHours`: oltre questa età il feed è considerato vecchio e l'app legge Telegram da sola.
- `moderatorPinSha256` / `promoCodesSha256`: impronte SHA-256 di `venator:` + PIN (o codice promo in minuscolo).
  Per calcolarle: `printf 'venator:NUOVOPIN' | sha256sum`.
