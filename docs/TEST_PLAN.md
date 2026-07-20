# Plan testiranja

## Automatizirani testovi

### Domena (JVM)

- Zbroj zalihe po policama, zabrana negativne količine.
- Izračun točne razlike do minimuma i detekcija samo prijelaza ispod minimuma.
- Privatne obavijesti zadano ne sadrže naziv ni količine; detaljni sadržaj šalje se samo uređajima s izričito potvrđenom postavkom. Testirati i stari uređaj bez polja te duplicirani token kod kojeg privatnost mora imati prednost.
- Jedinstvenost barkoda i podržani EAN/UPC formati.
- Sigurno brisanje police, inventurne razlike, merge/replace uvoza.
- Debounce skenera, filtriranje i statusni prijelazi sinkronizacije.

### Data/repository

- Room transakcija uvijek zajedno mijenja read-model i outbox.
- `PERMISSION_DENIED` na pantry/members listeneru odmah zaustavlja listenere, trajno karantenira smočnicu, uklanja je iz aktivnog UI toka i isključuje njezine operacije iz outboxa.
- Karantena preživljava pozadinu i ponovno stvaranje procesa; offline potvrda ostaje blokirana, a uspješan `listMyPantries` nakon povratka mreže vraća pristup ili potpuno briše lokalne podatke opozvane smočnice.
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
- Kartice polica, zaliha i dashboard na širini 360 dp ostaju čitljivi pri font-scaleu 150 % i 200 %; sekundarne akcije prelaze u izbornik ili novi red.
- Interaktivne akcije na policama i zalihama imaju najmanje 48 × 48 dp te izložene hrvatske semantičke nazive.
- Dashboard u tamnom načinu koristi semantičke boje teme; ključni parovi imaju najmanje 3:1 za ikone/velike brojke i 4,5:1 za tekst.

### Integracija

- APK s minimalnim backend API-jem 2 blokira rad uz jasan retry kada `getBackendCapabilities` ne postoji, vrati stariju verziju ili nema obveznu capability oznaku; prolazi s aktualnim odgovorom i samo pri privremenom mrežnom prekidu smije koristiti prethodno potvrđenu kompatibilnu verziju.
- Produkcijski post-deploy smoke uspoređuje `functions:list` sa statičkim manifestom svih funkcija, provjerava stvarni capability odgovor i potvrđuje da svaka zaštićena callable funkcija odbija neautorizirani zahtjev.
- Prijava → stvaranje smočnice → polica → artikl → add/remove → auto-shopping.
- Uređaj A offline mijenja količinu, uređaj B online mijenja istu količinu, sinkronizacija delta operacija.
- Poziv i pridruživanje drugog korisnika; owner/member negativne akcije.
- Export → preview → merge/replace → jednakost modela.
- Preuzimanje fixture APK-a: dobar hash/potpis, loš hash i tuđi potpis.

## Ručne provjere prije izdanja

- Android 10 i aktualni Android, uređaj s/bez Google Play usluga.
- Kamera, bljeskalica i sva četiri formata stvarnim barkodovima.
- Airplane mode, prekid procesa usred sinkronizacije, ponovno pokretanje i konflikt.
- Ljudski prolaz kroz TalkBack na stvarnom uređaju te svijetla/tamna tema na najmanje jednom OLED i jednom LCD zaslonu.
- Font-scale 150 %/200 %, približno 360 dp i landscape automatizirano su pokriveni Compose testovima; prije izdanja na stvarnom uređaju vizualno potvrditi cijele ekrane, ne samo ciljane komponente.
- Nadogradnja potpisanog starog APK-a na novi bez gubitka Room/Firestore podataka.

## Izlazni kriteriji

`test`, `connectedDebugAndroidTest` (na emulatoru), `lintDebug`, rules/functions testovi i `assembleDebug` prolaze. Nema visoko-kritičnih lint/rules nalaza, tajni u Git povijesti ni PII sadržaja u Crashlytics pozivima.
