# Plan testiranja

## Automatizirani testovi

### Domena (JVM)

- Zbroj zalihe po policama, zabrana negativne količine.
- Izračun točne razlike do minimuma i detekcija samo prijelaza ispod minimuma.
- Privatne obavijesti zadano ne sadrže naziv ni količine; detaljni sadržaj šalje se samo uređajima s izričito potvrđenom postavkom. Testirati i stari uređaj bez polja te duplicirani token kod kojeg privatnost mora imati prednost.
- Jedinstvenost barkoda i podržani EAN/UPC formati.
- Sigurno brisanje police, inventurne razlike, merge/replace uvoza.
- Debounce skenera, filtriranje i statusni prijelazi sinkronizacije.
- Deterministički hrvatski sinonimi samo predlažu grupiranje; bez izričite potvrde nastaje samostalan generički artikl.
- Točan zbroj miješanih varijanti u mg/ml/kom, zaokruživanje potrebnih cijelih pakiranja te prikaz donje granice kada veličina pakiranja nije poznata.
- Generički minimum, minimum pojedine varijante i preferirana varijanta daju jednu automatsku shopping stavku bez dvostrukog manjka.

### Data/repository

- Room transakcija uvijek zajedno mijenja read-model i outbox.
- Skupna promjena kategorije, brisanje i premještanje unaprijed validiraju cijeli odabir. Namjerno nepostojeći srednji artikl mora poništiti promjene prvog i zadnjeg, aktivnosti i outbox; uspješan skupni potez stvara točno jednu outbox operaciju.
- Migracija Room 4→5 čuva podatke, popunjava normalizirane nazive, dodaje `categoryId` stavkama kupnje i ostavlja točno jednu aktivnu zadanu kategoriju.
- Migracija Room 5→6 svaki stari artikl pretvara u jednu početnu varijantu, čuva stabilni `productId`, URI fotografije i outbox te svaku zalihu veže uz deterministični `variantId` bez promjene količine pakiranja.
- CRUD varijante, zaliha po varijanti i polici, preferirana varijanta, premještanje/grupiranje/razdvajanje te brisanje zadnje varijante izvršavaju se u jednoj Room transakciji s jednim idempotentnim outbox zapisom.
- Repository odbija nazive polica/kategorija jednake nakon NFKC/case/space normalizacije; artikle, filtere i stavke kupnje povezuje isključivo preko ID-a.
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
- Dva paralelna ili ponovljena zahtjeva za stvaranje koriste isti idempotency ključ i rezultiraju jednom smočnicom; korisnik s aktivnom smočnicom ne može stvoriti ni pridružiti se drugoj.
- Izravni klijentski pristup `userPantryAccess` locku je zabranjen; uklanjanje člana i brisanje smočnice uklanjaju samo pripadajući lock.
- Backend odbija 11. člana, 51. policu, 51. kategoriju, 501. artikl i drugu aktivnu pozivnicu bez djelomičnih zapisa ili potrošnje koda.
- Paralelno stvaranje/preimenovanje ne može rezervirati isti normalizirani naziv police ili kategorije; klijent ne može čitati ni pisati pomoćne rezervacije.
- Klijentski `isDefault` ne mijenja zadanu kategoriju; backend i migracija održavaju točno jednu aktivnu zadanu kategoriju.
- Artikl i ručna/automatska stavka kupnje odbijaju nepostojeći `categoryId`, a prikazani naziv uvijek dolazi iz poslužiteljskog dokumenta kategorije.
- Dva registrirana uređaja istodobno šalju offline dodavanje iste normalizirane ručne stavke: postoji jedan deterministički dokument, količine se zbrajaju, checked se vraća na false, retry istog operationId-a ne povećava količinu i drugačiji identitet nad istim ID-em se odbija.
- Skupna promjena kategorije, brisanje i premještanje odbijaju cijelu operaciju ako je srednji artikl nepostojeći, obrisan ili nema dovoljnu zalihu; nijedan dokument ni activity zapis ne smije biti djelomično promijenjen. Uspješna skupna operacija stvara jedan idempotentni operation zapis i zasebnu strukturiranu aktivnost za svaki artikl.
- Član čita; klijent ne piše izravno ni uz lažni ownerUid/quantity.
- Samo owner callable može upravljati članovima, prijenosom i brisanjem.
- Kôd je jednokratan/istekao/revoked; operationId je idempotentan.
- Konkurentno vađenje ne može dati negativnu zalihu.
- Storage odbija ne-sliku, preveliku datoteku i pogrešan pantry.
- Stari Firestore artikl migrira se idempotentno u generički artikl + varijantu; ponovljeni i prekinuti migracijski/import posao nastavlja od spremljenog kursora i ne zbraja istu zalihu dvaput.
- Barkod je jedinstven na razini varijante; poznati barkod mijenja upravo očitanu varijantu, a paralelna grupiranja s istom očekivanom revizijom daju jedan uspjeh i jedan konflikt.
- Owner-only zajednički sinonimi i `doNotGroup` odbijaju člana, dok običan član smije potvrditi jednokratno grupiranje varijante bez stvaranja trajnog pravila.

### Compose UI

- Dashboard prikazuje sažetak, primarni skener, četiri pločice, aktivnosti i navigaciju.
- Sinkronizacijski status i konflikt su čitljivi i imaju akciju.
- Forma validira naziv/količine/barkod; vađenje je ograničeno dostupnim stanjem.
- Unos varijante čuva generički naziv odvojeno od proizvođača/barkoda/pakiranja, prikazuje prijedlog grupiranja s postotkom i ne dopušta spajanje bez potvrdnog checkboxa.
- Kartica generičkog artikla prikazuje reprezentativnu varijantu i točan objedinjeni zbroj; detalj prikazuje sve varijante, police i količine, a promjena veličine pakiranja pokazuje pregled novog generičkog zbroja prije spremanja.
- Brze +/− radnje traže varijantu i policu, ponovno nude zadnju korištenu policu, onemogućuju nedostupnu zalihu i poznati barkod preskače izbor varijante.
- Pretraga po proizvođaču, barkodu, pakiranju ili opisu prikazuje generički artikl s odgovarajućom varijantom kao reprezentativnom.
- Kupljena stavka je precrtana, ali ostaje prisutna.
- Inventura ne mijenja zalihu prije potvrde.
- Kartice polica, zaliha i dashboard na širini 360 dp ostaju čitljivi pri font-scaleu 150 % i 200 %; sekundarne akcije prelaze u izbornik ili novi red.
- Interaktivne akcije na policama i zalihama imaju najmanje 48 × 48 dp te izložene hrvatske semantičke nazive.
- Dashboard u tamnom načinu koristi semantičke boje teme; ključni parovi imaju najmanje 3:1 za ikone/velike brojke i 4,5:1 za tekst.

### Integracija

- Standardni CI i release gate automatski pokreću `:core:data:connectedDebugAndroidTest` i `:app:connectedDebugAndroidTest` na emulatorima API 29 i API 35. Time su Room migracije i Compose UI testovi obvezna prepreka mergeu i izdanju, a ne samo lokalna provjera.
- Izravna migracija iz stvarne rc9 Room sheme v1 na aktualnu shemu v6 mora očuvati pantry/member zapise, police, kategorije, artikle, URI fotografije, zalihe, shopping stavke, inventuru, aktivnosti i nesinkronizirani outbox te stvoriti kanonske početne varijante.
- APK s minimalnim backend API-jem 9 blokira rad uz jasan retry kada `getBackendCapabilities` ne postoji, vrati stariju verziju ili nema `generic-products:v1`, `product-variants:v1`, `variant-stock:v1`, `resumable-snapshot-import:v1` ili drugu obveznu capability oznaku; prolazi s aktualnim odgovorom i samo pri privremenom mrežnom prekidu smije koristiti prethodno potvrđenu kompatibilnu verziju.
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

`test`, API 29/35 instrumentation gate, `lintDebug`, rules/functions testovi i `assembleDebug` prolaze. Stvarna rc9 → rc19 nadogradnja bez deinstalacije dodatno mora biti PASS na dva fizička uređaja prema `REAL_DEVICE_TEST_PLAN.md`. Nema visoko-kritičnih lint/rules nalaza, tajni u Git povijesti ni PII sadržaja u Crashlytics pozivima.
