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
- release: `app/build/outputs/apk/release/app-release-unsigned.apk`, 29.778.145 bajtova;
- provjereni emulator: Android API 35.

Release APK je namjerno nepotpisan jer privatni produkcijski ključ nije dostupan u repozitoriju. Njegovo produkcijsko potpisivanje i nadogradnja postojećeg izdanja nisu ovim prolazom potvrđeni.

## Sigurnosni i dependency nalaz

Pretragom radnog stabla nisu pronađeni privatni ključevi, Firebase API ključevi, service-account JSON ni stvarne lozinke. Primjer keystore konfiguracije sadrži samo `CHANGE_ME` vrijednosti, a datoteke s tajnama su ignorirane. Repozitorij nema commit povijest pa nije bilo moguće pregledati povijesne revizije na slučajno spremljene tajne.

`npm audit --omit=dev --audit-level=high` završava bez visokih ili kritičnih nalaza, ali prijavljuje devet umjerenih tranzitivnih `uuid` nalaza kroz službeni Firebase Admin/Google Cloud lanac. Automatski predloženi popravak zahtijeva nekompatibilan major downgrade paketa `firebase-admin`, pa nije primijenjen.

## Status ključnih provjera

Automatizirano su potvrđeni owner-only administrativni pozivi, izolacija smočnica pravilima, jednokratna hashirana pozivnica, zabrana negativne zalihe, idempotentne operacije, auto-stavka kupnje i ponovno aktiviranje praga, atomski uvoz, konflikt zastarjele inventure, pravila privatnih fotografija, poredak outboxa i rebase lokalnog konflikta.

Kodom, testovima i buildom potvrđeni su verzionirana JSON validacija, UTF-8 CSV generiranje, dohvat Open Food Facts s timeoutom/fallbackom, provjera update hash-a i certifikata, Android instalacijski intent, routing glavnih Compose akcija te loading/empty/error stanja. Vanjske integracije navedene ispod i dalje zahtijevaju završnu provjeru u stvarnom okruženju.

## Što nije bilo moguće stvarno provjeriti

- dva stvarna Google računa i uređaja u produkcijskom Firebase projektu, stvarni App Check, FCM dostava, Crashlytics i oporavak podataka nakon ponovne instalacije;
- release potpisivanje, GitHub Release objava, rate-limit ponašanje, preuzimanje i Android nadogradnja jer nisu dostupni privatni keystore, Actions secrets ni javni ciljni repozitorij;
- kamera, bljeskalica, fotografiranje i fizički EAN-8/EAN-13/UPC-A/UPC-E kodovi na stvarnom Android 10+ uređaju;
- Android 10 i OEM uređaji; lokalna instrumentacija izvedena je samo na API 35 emulatoru;
- višednevni rad više stvarnih uređaja, stvarni prekidi procesa/mreže i cloud oporavak izvan emulatora;
- TalkBack, svi font-scale/landscape slučajevi i sve OEM varijante instalacijskog dijaloga;
- produkcijski scheduled cleanup i FCM ponašanje, koje Firebase Emulator Suite ne emulira u cijelosti;
- vizualni dashboard nakon stvarne prijave, jer produkcijska Google/Firebase konfiguracija nije dostupna; navigacija i UI stanja provjereni su kodom i Compose testovima.

## Odluka

**UVJETNO SPREMNO.** U lokalno provjerljivom opsegu nema otvorenog P0/P1 nalaza; build, lint, JVM, instrumentacijski i Firebase rules/functions testovi prolaze. Prije stvarnog javnog korištenja moraju se uspješno dovršiti ručne provjere iz `docs/TEST_PLAN.md` s produkcijskim Firebase projektom, dvama računima i uređajima te release-potpisanim APK-om.
