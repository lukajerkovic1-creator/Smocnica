# Završna verifikacija

Datum provjere: 13. srpnja 2026.

## Automatizirani rezultati

| Sloj | Naredba | Rezultat |
|---|---|---|
| Čisti Gradle prolaz | `gradlew --no-daemon clean test lintDebug assembleDebug` | `BUILD SUCCESSFUL` |
| Debug i release varijante | `gradlew --no-daemon test lintDebug assembleDebug lintRelease assembleRelease` | `BUILD SUCCESSFUL`; release R8/minifikacija uspješna |
| Domenski JVM testovi | uključeni u `test` | 6/6 prošlo |
| Android instrumentacija | `gradlew --no-daemon connectedDebugAndroidTest` | 8/8 prošlo na API 35 emulatoru: 6 Room/repository i 2 Compose testa |
| Cloud Functions | `npm --prefix functions run build` | TypeScript kompilacija uspješna |
| Firebase Emulator Suite | `npm --prefix functions run test:emulator` | 18/18 prošlo: Firestore/Storage pravila i integracijske operacije |
| Runtime smoke | `adb install -r`, brisanje podataka, hladni start i `logcat` | instalacija i start uspješni; hrvatski login renderiran; nema fatalne iznimke |
| Workflow sintaksa | parsiranje `ci.yml` i `release.yml` Node YAML parserom | oba workflowa valjana |
| APK manifest | `aapt dump badging` | debug `hr.smocnica.debug`, release `hr.smocnica`, `minSdk 29`, `targetSdk 37`, `versionCode 1` |

Lint za aplikaciju i `core:data`, u debug i release varijantama, završava s 0 pogrešaka i 0 fatalnih nalaza. Preostala upozorenja su informativna (novije verzije ovisnosti/Gradlea, preporuka KTX API-ja i dinamički dohvat generirane update konfiguracije).

## APK artefakti

- debug: `app/build/outputs/apk/debug/app-debug.apk`, 116.839.672 bajta, SHA-256 `9FCA2E61DD20037E7755BF6A50B84D33E3B7ADD41D7675E4786FBF533392AA6C`;
- debug potpis: APK Signature Scheme v2, jedan potpisnik (razvojni debug certifikat);
- release: `app/build/outputs/apk/release/app-release.apk`, 29.832.536 bajtova, SHA-256 `11EC6297DCCBE276D106C1F160449E33573CC3E2EB9AF93C876A6F681537C366`;
- release potpis: APK Signature Scheme v3, jedan RSA-4096 potpisnik, certifikat SHA-256 `AAEDD1CFBA45A8E61F155EE6B43DF77648C82AB76408F3205D536A22EE678644`;
- provjereni emulator: Android API 35.

Release APK je lokalno potpisan trajnim produkcijskim ključem izvan repozitorija i `apksigner verify --verbose --print-certs` ga potvrđuje. Nadogradnja prethodne instalacije istim certifikatom i dalje zahtijeva dva stvarna uređaja.

## Sigurnosni i dependency nalaz

Pretragom radnog stabla i postojeće Git povijesti nisu pronađeni privatni ključevi, Firebase API ključevi, service-account JSON ni stvarne lozinke. Primjer signing konfiguracije sadrži samo `CHANGE_ME` vrijednosti, a datoteke s tajnama su ignorirane.

`npm audit --omit=dev --audit-level=high` završava bez visokih ili kritičnih nalaza, ali prijavljuje devet umjerenih tranzitivnih `uuid` nalaza kroz službeni Firebase Admin/Google Cloud lanac. Automatski predloženi popravak zahtijeva nekompatibilan major downgrade paketa `firebase-admin`, pa nije primijenjen.

## Status ključnih provjera

Automatizirano su potvrđeni owner-only administrativni pozivi, izolacija smočnica pravilima, jednokratna hashirana pozivnica, zabrana negativne zalihe, idempotentne operacije, auto-stavka kupnje i ponovno aktiviranje praga, atomski uvoz, konflikt zastarjele inventure, pravila privatnih fotografija, poredak outboxa i rebase lokalnog konflikta.

Kodom, testovima i buildom potvrđeni su verzionirana JSON validacija, UTF-8 CSV generiranje, dohvat Open Food Facts s timeoutom/fallbackom, provjera update hash-a i certifikata, Android instalacijski intent, routing glavnih Compose akcija te loading/empty/error stanja. Vanjske integracije navedene ispod i dalje zahtijevaju završnu provjeru u stvarnom okruženju.

## Što nije bilo moguće stvarno provjeriti

- dva stvarna Google računa i uređaja u produkcijskom Firebase projektu, stvarni App Check, FCM dostava, Crashlytics i oporavak podataka nakon ponovne instalacije;
- GitHub Release objava, rate-limit ponašanje, javno preuzimanje i Android nadogradnja jer GitHub Actions Secrets i prvi release još nisu postavljeni;
- kamera, bljeskalica, fotografiranje i fizički EAN-8/EAN-13/UPC-A/UPC-E kodovi na stvarnom Android 10+ uređaju;
- Android 10 i OEM uređaji; lokalna instrumentacija izvedena je samo na API 35 emulatoru;
- višednevni rad više stvarnih uređaja, stvarni prekidi procesa/mreže i cloud oporavak izvan emulatora;
- TalkBack, svi font-scale/landscape slučajevi i sve OEM varijante instalacijskog dijaloga;
- produkcijski scheduled cleanup i FCM ponašanje na stvarnim podacima, koje Firebase Emulator Suite ne emulira u cijelosti;
- vizualni dashboard nakon stvarne Google prijave; produkcijska konfiguracija sada postoji, ali tijek još nije izveden na uređaju.

## Odluka

**UVJETNO SPREMNO.** U lokalno provjerljivom opsegu nema otvorenog P0/P1 nalaza; build, lint, JVM, instrumentacijski i Firebase rules/functions testovi prolaze. Prije stvarnog javnog korištenja moraju se uspješno dovršiti ručne provjere iz `docs/TEST_PLAN.md` s produkcijskim Firebase projektom, dvama računima i uređajima te release-potpisanim APK-om.

## Produkcijska integracijska priprema

Dodatni prolaz 13. srpnja 2026. nakon pripreme signing/Firebase/GitHub konfiguracije:

| Provjera | Rezultat |
|---|---|
| `gradlew --no-daemon clean test lintRelease assembleRelease` | `BUILD SUCCESSFUL` u 1 min 17 s |
| `npm --prefix functions run build` | TypeScript kompilacija uspješna |
| `npm --prefix functions run test:emulator` | 18/18 testova prošlo |
| `verifyReleaseSigningConfiguration` bez tajni | očekivano odbijen; nepotpisani build ne može se proglasiti produkcijskim RC-om |
| release workflow YAML | sintaktički valjan |
| ignore matrica | `local.properties`, keystore, `.env*`, `.firebaserc`, svi `google-services.json`, service-account JSON i generirani update config ignorirani |
| produkcijski Firebase deploy | Firestore/Storage pravila i indeksi objavljeni; svih 14 Node.js 22 funkcija aktivno u `europe-west1` |
| App Check | debug i production aplikacije registrirane; production zahtijeva Device integrity, bez PLAY_RECOGNISED/LICENSED zahtjeva; API enforcement ostavljen isključen do testa uređaja |
| Artifact Registry cleanup | slike buildova starije od 7 dana automatski se brišu |

Lokalni rezultat je potpisani `app-release.apk` s očekivanim certifikatom i produkcijskom Firebase konfiguracijom. Instalacija, stvarni App Check promet, GitHub Release download i nadogradnja moraju se evidentirati u `docs/REAL_DEVICE_TEST_PLAN.md`.
