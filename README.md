# Smočnica

Nativna Android aplikacija za zajedničko, offline-first vođenje kućne smočnice. Kotlin/Compose klijent koristi Room kao lokalni izvor istine i sinkronizira idempotentni outbox preko Firebase callable funkcija. Produkcijski podaci nisu uključeni u repozitorij.

## Preduvjeti

- Android Studio s JDK 21 i Android SDK 37
- Node.js 22 i npm
- Firebase CLI (`npm` ga već instalira lokalno u `functions`)
- Firebase projekt na Blaze planu za raspoređene Functions i zakazane poslove

Projekt ima `minSdk 29` (Android 10). Na Windowsu Android Gradle Plugin odbija neke ne-ASCII putanje; ako je projekt u takvoj putanji, mapirajte ga na slovo diska, primjerice `subst S: "C:\putanja\do\projekta"`, pa Gradle pokrećite iz `S:\`.

## Firebase i Google prijava

1. U Firebase Console izradite dvije Android aplikacije: produkcijsku `hr.smocnica` i razvojnu `hr.smocnica.debug`.
2. Dodajte SHA-1 i SHA-256 otiske debug i release certifikata. U Authentication > Sign-in method uključite Google.
3. Razvojni `google-services.json` spremite u `app/src/debug/google-services.json`, a produkcijski u `app/src/release/google-services.json`. Root fallback `app/google-services.json` koristi release workflow. Sve su te datoteke namjerno u `.gitignore`.
4. Uključite Firestore, Storage, Cloud Messaging, Crashlytics i App Check. Za release registrirajte Play Integrity provider; za debug registrirajte token koji SDK ispiše u Logcatu. Za release APK iz GitHub Releasesa postavite `PLAY_RECOGNIZED` i `LICENSED` na “Not required” te zahtijevajte “Device integrity”, jer se APK distribuira izvan Google Playa.
5. Postavite projekt: kopirajte `.firebaserc.example` u `.firebaserc`, zamijenite ID, pa pokrenite:

```powershell
cd functions
npm ci
npm run build
cd ..
npx --prefix functions firebase-tools deploy --only firestore:rules,firestore:indexes,storage,functions
```

Firestore i Storage klijentski write je namjerno zabranjen osim ograničenog uploada fotografija; sve poslovne mutacije prolaze kroz Functions. Produkcijske poslovne callable funkcije zahtijevaju valjani Firebase Auth i App Check token; javni `getBackendCapabilities` vraća samo statički kompatibilnosni manifest.

Za produkciju se preporučuje ručni workflow `Deploy production backend` s GitHub Environment odobrenjem i Workload Identity Federationom. On objavljuje Functions, oba skupa pravila i indekse te nakon deploya provjerava cijeli manifest i svaku callable krajnju točku. APK prije prvog udaljenog poziva dodatno provjerava `getBackendCapabilities`, pa zastarjeli backend više ne može tiho raditi s novijim klijentom.

Za 12-mjesečno automatsko čišćenje može se dodatno uključiti Firestore TTL na polju `expiresAt` kolekcijske grupe `activities`; zakazana funkcija i dalje provodi pravilo koša od 30 dana.

## Emulator Suite

Rules i backend testovi koriste demo projekt i ne dotiču stvarne podatke:

```powershell
cd functions
npm ci
npm run test:emulator
```

Za interaktivni backend:

```powershell
cd functions
npm run serve
```

Android debug build spojite na emulatore pokrenute na računalu s:

```powershell
./gradlew :app:installDebug -PFIREBASE_EMULATORS=true
```

Emulator koristi `10.0.2.2` i standardne portove iz `firebase.json`. FCM nema lokalni emulator.

## Build i testovi

```powershell
./gradlew testDebugUnitTest lintDebug assembleDebug
cd functions
npm run build
npm run test:emulator
```

UI/repository instrumentacijski testovi zahtijevaju pokrenut Android emulator ili uređaj:

```powershell
./gradlew connectedDebugAndroidTest
```

Debug APK nastaje u `app/build/outputs/apk/debug/app-debug.apk`. Bez `google-services.json` projekt se može izgraditi i testirati, ali prijava i Firebase funkcije namjerno prikazuju konfiguracijsku pogrešku.

## Potpisivanje i čuvanje ključeva

Vrijednosti iz `local.properties.example` dodajte u postojeći neversionirani `local.properties` ili postavite varijable `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` i `ANDROID_KEY_PASSWORD`. Build task `verifyReleaseSigningConfiguration` odbija nedostajuću ili djelomičnu produkcijsku signing konfiguraciju. Keystore, lozinke, `google-services.json`, servisni računi i App Check tokeni nikada se ne spremaju u Git, Gradle datoteke ili Actions artefakte.

GitHub Actions release traži sljedeće repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON_BASE64`

Workflow iz certifikata izračunava ugrađeni SHA-256 fingerprint, dodaje Firebase konfiguraciju iz tajne, potpisuje i provjerava APK, generira `release-manifest.json` sa stvarnim bilješkama i objavljuje oba artefakta u javni GitHub Release. Workflow se zaustavlja ako repozitorij nije javan. Tag mora biti oblika `v1.2.3` ili `v1.2.3-rc1`; `versionCode` uvijek mora rasti. Za ručni workflow obvezno unesite oba broja.

## GitHub ažuriranja

Release build dobiva URL `https://api.github.com/repos/<owner>/<repo>/releases/latest` i fingerprint potpisnika. Aplikacija pri otvaranju i ručno provjerava manifest, dopušta samo GitHub HTTPS hostove, provjerava SHA-256 i APK certifikat te tek tada otvara standardnu Android potvrdu instalacije. `minSupportedVersionCode` ili `forceUpdate` određuje obavezno ažuriranje. Nadogradnja istim certifikatom čuva Room bazu i Firebase podatke.

Korisnik mora jednom dopustiti instalaciju iz ovog izvora. Aplikacija ne pokušava zaobići Android Package Installer.

## Fotografije i Open Food Facts

Fotografija iz kamere ili galerije dekodira se, smanjuje na najviše 2048 px, ponovno kodira kao JPEG (čime se uklanjaju EXIF metapodaci) i ograničava na 5 MiB prije Storage uploada. Offline tekstualne i količinske mutacije ostaju u Room/outboxu; novi upload vlastite fotografije zahtijeva mrežu i korisniku vraća jasnu pogrešku ako Storage nije dostupan. Open Food Facts služi samo za početno popunjavanje; lokalne ispravke se ne šalju javnoj bazi.

## Privatnost i Crashlytics

Crashlytics je isključen u debug buildu. Produkcijski reporter koristi samo unaprijed definirane kodove i tehnički sloj; ne postavlja Firebase UID i ne šalje nazive artikala, barkodove, količine, shopping stavke, pozivne kodove, fotografije ni sadržaj sigurnosne kopije. Izvoz kroz Android Storage Access Framework ostaje na odredištu koje odabere korisnik.

Detalji modela i odluka nalaze se u [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), faze u [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), matrica provjere u [docs/TEST_PLAN.md](docs/TEST_PLAN.md), a rezultati zadnjeg lokalnog prolaza u [docs/VERIFICATION.md](docs/VERIFICATION.md). Produkcijske ručne vrijednosti i signing/deploy postupak opisani su u [docs/PRODUCTION_SETUP.md](docs/PRODUCTION_SETUP.md), a obvezni test na dva uređaja u [docs/REAL_DEVICE_TEST_PLAN.md](docs/REAL_DEVICE_TEST_PLAN.md).
