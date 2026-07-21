# Završna verifikacija

Datum zadnje provjere: 21. srpnja 2026.

## Automatizirani rezultati

| Sloj | Naredba ili dokaz | Rezultat |
|---|---|---|
| Čisti Android build | `gradlew --no-daemon clean test lintDebug lintRelease assembleDebug assembleRelease -PVERSION_CODE=29 -PVERSION_NAME=1.0.0-rc29` | `BUILD SUCCESSFUL`; debug i minificirani release APK izrađeni |
| JVM testovi | Gradle `test` | 42/42 PASS, 0 failures, 0 errors |
| Lokalna instrumentacija | `:core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest` | 62/62 PASS na API 35: 28 Room/repository + 34 app/Compose testova |
| Release instrumentacija | GitHub Actions run `29797567529` | API 29 PASS i API 35 PASS; oba posla morala su završiti prije produkcijskog odobrenja i signing posla |
| Cloud Functions | `npm --prefix functions run build` i `npm --prefix functions test` | TypeScript kompilacija PASS; 18/18 brzih testova PASS |
| Firebase Emulator Suite | `npm --prefix functions run test:emulator` | 63/63 PASS: Firestore/Storage pravila, backend operacije, TTL konfiguracija i negativni sigurnosni slučajevi |
| Produkcijski Firebase | deploy Functions, Firestore rules/indexes i Storage rules + `smoke-production.mjs` | PASS iz commita `ed5623a`; 16/16 funkcija ACTIVE, backend API 7, capability HTTP 200 i 12/12 zaštićenih callable funkcija HTTP 401 bez vjerodajnice |
| Potpisani GitHub Release | GitHub Actions run `29797567529` + anonimni javni download | PASS; RC29 objavljen nakon ručnog `production` odobrenja, hash manifesta jednak je preuzetom APK-u |
| APK manifest | `aapt dump badging` nad javno preuzetim APK-om | `hr.smocnica`, `versionCode 29`, `versionName 1.0.0-rc29`, `minSdk 29`, `targetSdk 36` |

Lint za aplikaciju i `core:data`, u debug i release varijantama, završava bez fatalnih nalaza. Prvi RC29 workflow (`29797192903`) ispravno je blokirao objavu zbog viewport-ovisnog Compose testa. Test je popravljen semantičkim pomicanjem `LazyColumn`, lokalno ponovno potvrđen i završni workflow `29797567529` je PASS na oba API-ja.

## Provjere poboljšanja iz RC29

- JSON import odbija deklarirano preveliku datoteku prije otvaranja toka i prekida čitanje nepoznate duljine čim prijeđe 20 MiB; nema neograničenog `readBytes()`.
- minimalna i početna količina prihvaćaju samo cijele brojeve `0..1 000 000`; prazan, negativan, prevelik i `Int` overflow unos ne pretvaraju se u nulu i onemogućuju spremanje.
- klijentski Storage `delete` je zabranjen pravilima; fotografije brišu samo pouzdane Admin SDK operacije pri trajnom brisanju artikla, smočnice ili računa.
- novi `operations` zapisi dobivaju `expiresAt` za 365 dana, obrađene `notifications` dobivaju `processedAt` i `expiresAt` za 30 dana, a Firestore TTL konfiguracija sadrži oba polja. Raspoređeno čišćenje pokriva i stare zapise nastale prije TTL polja.
- kanal `low_stock` stvara se u `Application.onCreate()`, prije mogućeg FCM događaja; instrumentacijski test potvrđuje da kanal postoji odmah nakon pokretanja.

## APK artefakti

- lokalni debug: `app/build/outputs/apk/debug/app-debug.apk`, 116.932.639 bajta, SHA-256 `E49E9A86F62E0FA9C00B49694FD3328B8317F6C84771D0FCB4A9FABBDE3583D2`;
- javni release: `smocnica-1.0.0-rc29.apk`, 30.259.316 bajta, SHA-256 `10E35DDF382F762A1F8A3E0B387360990C61AB9D101708B1754BB560A0986AAC`;
- release potpis: APK Signature Scheme v3, certifikat SHA-256 `AAEDD1CFBA45A8E61F155EE6B43DF77648C82AB76408F3205D536A22EE678644`;
- javni APK: <https://github.com/lukajerkovic1-creator/Smocnica/releases/download/v1.0.0-rc29/smocnica-1.0.0-rc29.apk>;
- update manifest: <https://github.com/lukajerkovic1-creator/Smocnica/releases/download/v1.0.0-rc29/release-manifest.json>.

Release workflow briše dekodirani keystore i produkcijski `google-services.json` odmah nakon potpisivanja i provjere APK-a, prije generiranja manifesta i objave. Ponovni download obavljen je bez GitHub tokena; stvarni hash, manifest, verzija i certifikat su potvrđeni.

## Što nije bilo moguće automatizirano potvrditi

- stvarnu FCM dostavu i prikaz kanala na različitim OEM uređajima;
- stvarno izvršenje Firestore TTL brisanja, koje je asinkrono i može kasniti nakon `expiresAt` vremena;
- ponašanje Android document providera koji ne prijavljuje veličinu i prekida tok usred čitanja na fizičkom uređaju; granična logika pokrivena je JVM testovima;
- ručnu nadogradnju RC28 → RC29 bez deinstalacije na dva fizička uređaja; stabilni `applicationId`, potpisni certifikat i rastući `versionCode` automatizirano su potvrđeni.

## Odluka

**UVJETNO SPREMNO.** Za implementirane P2 promjene nema otvorenog P0/P1 nalaza; build, lint, JVM, instrumentacijski, Firebase emulator, produkcijski deploy i potpisani release prolaze. Preostale stavke su fizičke/OEM i asinkrone cloud provjere navedene iznad.
