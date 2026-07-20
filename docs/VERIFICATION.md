# Završna verifikacija

Datum zadnje provjere: 20. srpnja 2026.

## Automatizirani rezultati

| Sloj | Naredba | Rezultat |
|---|---|---|
| Čisti Gradle prolaz | `gradlew --no-daemon clean test lintDebug assembleDebug` | `BUILD SUCCESSFUL` |
| Debug i release varijante | `gradlew --no-daemon test lintDebug assembleDebug lintRelease assembleRelease` | `BUILD SUCCESSFUL`; release R8/minifikacija uspješna |
| JVM testovi | uključeni u `test` | 34/34 prošlo kroz app/core module |
| Android instrumentacija | `gradlew --no-daemon connectedDebugAndroidTest` | 57/57 prošlo na API 35 emulatoru: 27 Room/repository i 30 app/Compose testova |
| Cloud Functions | `npm --prefix functions run build` | TypeScript kompilacija uspješna |
| Firebase Emulator Suite | `npm --prefix functions run test:emulator` | 58/58 prošlo: Firestore/Storage pravila, kanonski nazivi, ID veze, atomske skupne operacije, istodobno offline dodavanje, integracijske operacije i tri release-workflow sigurnosna testa |
| Runtime smoke | `adb install -r`, brisanje podataka, hladni start i `logcat` | instalacija i start uspješni; hrvatski login renderiran; nema fatalne iznimke |
| Workflow sintaksa | parsiranje svih datoteka u `.github/workflows` Node YAML parserom | `ci.yml`, `deploy-production-backend.yml` i `release.yml` valjani; sve vanjske Actions reference pinane na puni commit SHA |
| Produkcijski backend | puni deploy Functions/rules/indexes/storage + produkcijski smoke | PASS; 15/15 funkcija ACTIVE iz commita `27e6009`, backend API 6 + `atomic-bulk-products:v1`, capability odgovor HTTP 200, 11/11 zaštićenih callable funkcija HTTP 401 bez vjerodajnice |
| Potpisani GitHub Release | Actions run `29764569551` + anonimni ponovni download | PASS; RC26 je čekao ručno `production` odobrenje, rano brisanje tajni prošlo prije objave, a javni APK i manifest potvrđeni HTTP 200 |
| APK manifest | `aapt dump badging` | debug `hr.smocnica.debug`, release `hr.smocnica`, `minSdk 29`, stabilni `targetSdk 36`, `versionCode 26`, `versionName 1.0.0-rc26` |

Lint za aplikaciju i `core:data`, u debug i release varijantama, završava s 0 pogrešaka i 0 fatalnih nalaza. Preostala upozorenja su informativna (novije verzije ovisnosti/Gradlea, preporuka KTX API-ja i dinamički dohvat generirane update konfiguracije).

## APK artefakti

- debug: `app/build/outputs/apk/debug/app-debug.apk`, 116.899.859 bajta, SHA-256 `1DB1F70367F1785B34C47B1841DBF60DF50087B7FD7480A81D14BB7CB355F3FA`;
- debug potpis: APK Signature Scheme v2, jedan potpisnik (razvojni debug certifikat);
- javni release: `smocnica-1.0.0-rc26.apk`, 30.242.934 bajta, SHA-256 `30ABA97C7A9FF23309B2AE7ABFC9D1C4AB5495BC4B851070D5C0CA6ECA6E6A9F`;
- release potpis: APK Signature Scheme v3, jedan RSA-4096 potpisnik, certifikat SHA-256 `AAEDD1CFBA45A8E61F155EE6B43DF77648C82AB76408F3205D536A22EE678644`;
- provjereni emulator: Android API 35.

Release APK iz GitHub Releasea potpisan je trajnim produkcijskim ključem iz zaštićenog Environment Secreta; nakon anonimnog ponovnog preuzimanja `apksigner verify --verbose --print-certs` potvrđuje isti očekivani certifikat. Manifestov SHA-256 jednak je stvarnom hashu preuzetog APK-a. RC26 workflow dokazano briše dekodirani keystore i produkcijski `google-services.json` prije generiranja manifesta i objave. Nadogradnja prethodne instalacije i očuvanje podataka i dalje zahtijevaju stvarni uređaj.

## Sigurnosni i dependency nalaz

Pretragom radnog stabla i postojeće Git povijesti nisu pronađeni privatni ključevi, Firebase API ključevi, service-account JSON ni stvarne lozinke. Primjer signing konfiguracije sadrži samo `CHANGE_ME` vrijednosti, a datoteke s tajnama su ignorirane.

`npm audit --omit=dev --audit-level=high` završava bez visokih ili kritičnih nalaza, ali prijavljuje devet umjerenih tranzitivnih `uuid` nalaza kroz službeni Firebase Admin/Google Cloud lanac. Automatski predloženi popravak zahtijeva nekompatibilan major downgrade paketa `firebase-admin`, pa nije primijenjen.

## Status ključnih provjera

Automatizirano su potvrđeni owner-only administrativni pozivi, izolacija smočnica pravilima, jednokratna hashirana pozivnica, zabrana negativne zalihe, idempotentne operacije, auto-stavka kupnje i ponovno aktiviranje praga, atomski uvoz, konflikt zastarjele inventure, pravila privatnih fotografija, poredak outboxa i rebase lokalnog konflikta.

Kodom, testovima i buildom potvrđeni su verzionirana JSON validacija, UTF-8 CSV generiranje, dohvat Open Food Facts s timeoutom/fallbackom, provjera update hash-a i certifikata, Android instalacijski intent, routing glavnih Compose akcija te loading/empty/error stanja. Vanjske integracije navedene ispod i dalje zahtijevaju završnu provjeru u stvarnom okruženju.

## Što nije bilo moguće stvarno provjeriti

- dva stvarna Google računa i uređaja u produkcijskom Firebase projektu, stvarni App Check, FCM dostava, Crashlytics i oporavak podataka nakon ponovne instalacije;
- GitHub rate-limit ponašanje i Android nadogradnja RC25→RC26 s očuvanjem lokalnih podataka na stvarnom uređaju; objava i anonimni javni download RC26 potvrđeni su;
- kamera, bljeskalica, fotografiranje i fizički EAN-8/EAN-13/UPC-A/UPC-E kodovi na stvarnom Android 10+ uređaju;
- Android 10 i OEM uređaji; lokalna instrumentacija izvedena je samo na API 35 emulatoru;
- višednevni rad više stvarnih uređaja, stvarni prekidi procesa/mreže i cloud oporavak izvan emulatora;
- ljudski TalkBack prolaz i sve OEM varijante instalacijskog dijaloga; semantički nazivi, dodirne površine 48 dp, font-scale 150 %/200 %, približno 360 dp i landscape ciljano su provjereni Compose testovima na API 35 emulatoru 20. srpnja 2026.;
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
| produkcijski Firebase deploy | 20. srpnja 2026. iz commita `4d637b4`: Firestore/Storage pravila i indeksi objavljeni; svih 15 Node.js 22 funkcija aktivno u `europe-west1`, isti source hash; post-deploy smoke PASS |
| App Check | debug i production aplikacije registrirane; production zahtijeva Device integrity, bez PLAY_RECOGNISED/LICENSED zahtjeva; API enforcement ostavljen isključen do testa uređaja |
| Artifact Registry cleanup | slike buildova starije od 7 dana automatski se brišu |

Objavljen je potpisani javni RC26 s očekivanim certifikatom, produkcijskom Firebase konfiguracijom i provjerenim update manifestom. Release je prošao ručno odobreni `production` Environment; pet signing/Firebase tajni postoje samo kao Environment Secrets, a repozitorijski Secrets istih naziva uklonjeni su. Instalacija, stvarni App Check promet, atomske skupne operacije na dva uređaja i nadogradnja na uređaju moraju se evidentirati u `docs/REAL_DEVICE_TEST_PLAN.md`.
