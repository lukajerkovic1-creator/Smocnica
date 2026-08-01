# Plan implementacije

## Faza 0 — temelj

- Više-modularni Gradle projekt, Hilt, Compose tema i navigacija.
- Domenski model, Room shema, Firestore/Storage pravila i emulator konfiguracija.
- Testni kostur, CI i politika tajni.

## Faza 1 — identitet i zajednička smočnica

- Credential Manager Google prijava, profil uređaja, stvaranje/pridruživanje smočnici.
- Vlasničke akcije i pozivni kod/QR.
- Room cache, outbox, WorkManager i vidljiv status sinkronizacije.

## Faza 2 — police, artikli i zaliha

- CRUD i redoslijed polica sa sigurnim brisanjem.
- Artikli, raspodjela po policama, fotografije, barkod naknadno povezivanje.
- Atomske delta operacije i povijest aktivnosti.

## Faza 3 — skener i katalog

- CameraX + ML Kit formati, bljeskalica, ručni unos i debounce.
- Dodaj/izvadi tijek i Open Food Facts početno popunjavanje.

## Faza 4 — kupnja, pretraga i inventura

- Automatske i ručne stavke, FCM prijelaz ispod minimuma, označavanje kupljenog.
- Pretraga/filtri i grupiranje.
- Draft inventura po polici, razlike i potvrđena atomska primjena.

## Faza 5 — životni ciklus podataka

- Povijest 12 mjeseci, koš 30 dana i obnova.
- Verzijski JSON pregled/merge/replace i CSV izvoz.
- GitHub Releases provjera, hash/potpis i Android instalacija.

## Faza 6 — očvršćivanje i isporuka

- Unit, repository, rules, Compose i integracijski testovi.
- Emulator test, lint, debug APK, release workflow i dokumentacija postavljanja.
- Ručna provjera pristupačnosti, svijetle/tamne teme, offline/online prijelaza i nadogradnje.

Svaka faza mora ostaviti kompajlirajući projekt; privremeni lažni backend ili nedovršene javne funkcije nisu prihvatljivi.

## Faza 7 — generički artikli i varijante

1. **Kompatibilna lokalna shema:** postojeći artikl zadržava stabilni ID i postaje generički artikl; migracija stvara jednu početnu varijantu te svaku zalihu veže uz `variantId` i policu. Količina ostaje cijeli broj pakiranja, a masa/volumen/brojiva količina računaju se u cjelobrojnim baznim jedinicama.
2. **Domena i offline mutacije:** uvode se varijante, sinonimi, potvrđena pravila grupiranja, preferirana varijanta, varijantni minimum i revizijski zaštićene operacije grupiranja. Svaka mutacija i dalje atomarno mijenja Room read-model i jedan idempotentni outbox zapis.
3. **Firestore migracija:** smočnica dobiva verziju sheme i nastavljiv migracijski posao. Svaki stari artikl migrira se idempotentno u generički zapis + varijantu; mutacije su blokirane dok posao nije završen, bez jedne prevelike transakcije.
4. **Backend i pravila:** barkod rezervira varijantu, zaliha pripada varijanti i polici, a owner-only rječnik i grupiranja provjeravaju se u Cloud Functions. Grupiranje/razgrupiranje je atomsko i koristi očekivanu reviziju kako bi paralelne izmjene postale vidljiv konflikt.
5. **UI bez općeg redizajna:** kartice prikazuju objedinjenu količinu i reprezentativnu fotografiju; detalj prikazuje varijante i police; skener poznatog barkoda izravno bira varijantu, dok nepoznati barkod traži potvrdu prijedloga generičkog naziva.
6. **Minimumi, pretraga i backup:** automatska kupnja postaje generička, pretraga obuhvaća sinonime i podatke varijanti, a JSON shema dobiva novu verziju uz uvoz svih starijih formata i nastavljiv serverski import.
7. **Isporuka:** prolaze migracije 1→nova i 5→nova, JVM/repository/Compose/API 29+35/emulator testovi, zatim se objavljuje potpisani APK s većim `versionCode` i deploya kompatibilni backend prije dostupnosti APK-a.
