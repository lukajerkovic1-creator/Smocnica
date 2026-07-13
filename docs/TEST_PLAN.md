# Plan testiranja

## Automatizirani testovi

### Domena (JVM)

- Zbroj zalihe po policama, zabrana negativne količine.
- Izračun točne razlike do minimuma i detekcija samo prijelaza ispod minimuma.
- Jedinstvenost barkoda i podržani EAN/UPC formati.
- Sigurno brisanje police, inventurne razlike, merge/replace uvoza.
- Debounce skenera, filtriranje i statusni prijelazi sinkronizacije.

### Data/repository

- Room transakcija uvijek zajedno mijenja read-model i outbox.
- Idempotentna potvrda operacije, retry, konflikt i odbijanje trajne pogreške.
- Firestore DTO mapiranje ne gubi revizije/tombstone.
- Open Food Facts: rezultat, 404/prazan rezultat, mrežna pogreška.
- JSON checksum/schema i CSV escaping.
- GitHub manifest, SHA-256 i provjera certifikata.

### Firebase Emulator Suite

- Neprijavljen korisnik nema pristup.
- Ne-član ne čita smočnicu, podkolekcije ni slike.
- Član čita; klijent ne piše izravno ni uz lažni ownerUid/quantity.
- Samo owner callable može upravljati članovima, prijenosom i brisanjem.
- Kôd je jednokratan/istekao/revoked; operationId je idempotentan.
- Konkurentno vađenje ne može dati negativnu zalihu.
- Storage odbija ne-sliku, preveliku datoteku i pogrešan pantry.

### Compose UI

- Dashboard prikazuje sažetak, primarni skener, četiri pločice, aktivnosti i navigaciju.
- Sinkronizacijski status i konflikt su čitljivi i imaju akciju.
- Forma validira naziv/količine/barkod; vađenje je ograničeno dostupnim stanjem.
- Kupljena stavka je precrtana, ali ostaje prisutna.
- Inventura ne mijenja zalihu prije potvrde.

### Integracija

- Prijava → stvaranje smočnice → polica → artikl → add/remove → auto-shopping.
- Uređaj A offline mijenja količinu, uređaj B online mijenja istu količinu, sinkronizacija delta operacija.
- Poziv i pridruživanje drugog korisnika; owner/member negativne akcije.
- Export → preview → merge/replace → jednakost modela.
- Preuzimanje fixture APK-a: dobar hash/potpis, loš hash i tuđi potpis.

## Ručne provjere prije izdanja

- Android 10 i aktualni Android, uređaj s/bez Google Play usluga.
- Kamera, bljeskalica i sva četiri formata stvarnim barkodovima.
- Airplane mode, prekid procesa usred sinkronizacije, ponovno pokretanje i konflikt.
- TalkBack, dinamička veličina fonta, landscape, svijetla/tamna tema.
- Nadogradnja potpisanog starog APK-a na novi bez gubitka Room/Firestore podataka.

## Izlazni kriteriji

`test`, `connectedDebugAndroidTest` (na emulatoru), `lintDebug`, rules/functions testovi i `assembleDebug` prolaze. Nema visoko-kritičnih lint/rules nalaza, tajni u Git povijesti ni PII sadržaja u Crashlytics pozivima.
