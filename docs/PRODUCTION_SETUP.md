# Produkcijska integracijska priprema

## Google Play privatnost i brisanje računa

- URL politike privatnosti za Play Console: `https://lukajerkovic1-creator.github.io/Smocnica/privacy-policy.html`
- URL brisanja računa za obrazac Data safety: `https://lukajerkovic1-creator.github.io/Smocnica/delete-account.html`
- in-app put: `Izbornik → Izbriši korisnički račun`
- kontakt zahtjeva bez pristupa aplikaciji: `luka.jerkovic1@gmail.com`

Prije slanja Play obrasca otvoriti oba URL-a u anonimnom pregledniku i potvrditi HTTP 200, čitljivost na mobitelu te ispravan `mailto:` gumb. GitHub Pages workflow objavljuje isključivo mapu `public/`; privatni ključevi ni Firebase konfiguracija ne ulaze u artefakt.

Brisanje računa zahtijeva backend API 7 i capability `account-deletion:v1`, stoga se backend mora objaviti prije APK-a koji traži tu verziju.

Ovaj dokument priprema postojeću funkcionalnost aplikacije Smočnica za potpisani release candidate, dva stvarna Android uređaja, produkcijski Firebase i javni GitHub Releases kanal. Ne sadrži stvarne ključeve, lozinke, tokene ni Firebase konfiguraciju.

## 1. Vrijednosti koje administrator mora pripremiti

| Vrijednost | Gdje se čuva | Ne smije u Git |
|---|---|---|
| Firebase project ID `smocnica-aplikacija` | lokalni `.firebaserc` | da |
| produkcijski `google-services.json` za `hr.smocnica` | `app/src/release/google-services.json` ili Actions secret | da |
| razvojni `google-services.json` za `hr.smocnica.debug` | `app/src/debug/google-services.json` | da |
| release JKS/keystore | izvan repozitorija i sigurnosna kopija | da |
| store password, alias i key password | lokalni `local.properties` ili Actions Secrets | da |
| javni GitHub `lukajerkovic1-creator/Smocnica` | release workflow ga čita iz `GITHUB_REPOSITORY` | ne |
| rastući `versionCode` | Gradle argument / workflow input | ne |
| `versionName` | Gradle argument / tag, npr. `1.0.0-rc1` | ne |
| javni GitHub remote `https://github.com/lukajerkovic1-creator/Smocnica.git` | lokalna Git konfiguracija | ne |

Prije svakog commita provjeriti:

```powershell
git status --short --ignored
git ls-files | Select-String 'google-services\.json|\.env|\.jks$|\.keystore$|\.p12$|\.pfx$|service.?account|firebase-adminsdk|local\.properties|\.firebaserc'
```

Druga naredba mora vratiti samo dokumentaciju ili primjer bez stvarnih vrijednosti. `.gitignore` pokriva `local.properties`, `keystore.properties`, keystore formate, `.env*`, `.firebaserc`, `google-services.json`, service-account JSON, Firebase debug logove i CI-generirani release update resource.

## 2. Produkcijski Firebase projekt

1. U Firebase Console izraditi zaseban produkcijski projekt. Za Cloud Functions 2nd gen i zakazane funkcije potreban je Blaze plan.
2. U Project settings > General registrirati Android aplikaciju:
   - package name: `hr.smocnica`;
   - nadimak: primjerice `Smočnica Production`.
3. Za odvojeno debug testiranje registrirati drugu Android aplikaciju:
   - package name: `hr.smocnica.debug`;
   - nadimak: primjerice `Smočnica Debug`.
4. Ne registrirati `hr.smocnica.debug` kao produkcijsku aplikaciju i ne koristiti produkcijski release certifikat za debug package.

### SHA-1 i SHA-256 certifikati

Debug fingerprinti mogu se dobiti bez produkcijskih tajni:

```powershell
.\gradlew.bat signingReport
```

Ili iz standardnog debug keystorea:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
  -alias androiddebugkey `
  -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -storepass android `
  -keypass android
```

Release fingerprinti dobivaju se iz trajnog produkcijskog keystorea izvan repozitorija:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
  -alias SMOCNICA_RELEASE_ALIAS `
  -keystore "C:\SIGURNA\PUTANJA\smocnica-release.jks"
```

`keytool` će sigurno zatražiti lozinku. Ne stavljati lozinku u naredbu ni shell povijest.

U Firebase Console otvoriti Project settings > General > Your apps:

1. otvoriti `hr.smocnica.debug`;
2. Add fingerprint, unijeti debug SHA-1, zatim debug SHA-256;
3. otvoriti `hr.smocnica`;
4. Add fingerprint, unijeti release SHA-1, zatim release SHA-256;
5. spremiti promjene;
6. nakon uključivanja Google prijave preuzeti nove `google-services.json` datoteke jer moraju sadržavati ažurirani OAuth web client.

SHA-1 je potreban Google prijavi, a SHA-256 produkcijskom Play Integrity App Checku i provjeri potpisnika nadogradnje.

### Authentication i Google prijava

1. Firebase Console > Authentication > Sign-in method.
2. Omogućiti Google provider, odabrati project support e-mail i spremiti.
3. Preuzeti ažurirane konfiguracije za oba package namea.
4. Produkcijsku datoteku spremiti kao `app/src/release/google-services.json`.
5. Razvojnu datoteku spremiti kao `app/src/debug/google-services.json`.
6. Provjeriti da produkcijska datoteka sadrži client s package nameom `hr.smocnica`. Google Services plugin iz nje generira `default_web_client_id` koji Credential Manager koristi za ID token.

### Firestore, Storage, Functions i FCM

Produkcijsko stanje ponovno potvrđeno 20. srpnja 2026.:

- Firebase projekt: `smocnica-aplikacija` (Blaze);
- Firestore `(default)`: `europe-west1`;
- Storage bucket: `gs://smocnica-aplikacija.firebasestorage.app`, `europe-west1`, Standard;
- objavljena su aktualna Firestore i Storage pravila te indeksi;
- aktivno je svih 16 očekivanih Node.js 22 funkcija u `europe-west1`, uključujući `deleteAccount` i `getBackendCapabilities` API 7 s capabilityjima `single-active-pantry:v1`, `canonical-names:v1`, `manual-shopping-merge:v1`, `atomic-bulk-products:v1` i `account-deletion:v1`;
- svih 15 funkcija objavljeno je iz commita `27e6009995951e92fefcd690525bdc022fb93694` s istim Firebase source hashom `7c8be49ef539a58592ee69435ee153049063d52b`;
- produkcijski smoke provjerio je capability odgovor i svih 11 zaštićenih callable funkcija; dokaz je u `docs/PRODUCTION_BACKEND_SMOKE.json`;
- Artifact Registry automatski briše build slike starije od 7 dana radi ograničenja troška.

1. Izraditi Firestore bazu u Native načinu. Lokaciju izabrati prije prvog zapisa; naknadno se ne može jednostavno promijeniti. Funkcije su konfigurirane za `europe-west1`, pa odabrati europsku lokaciju usklađenu s pravilima organizacije.
2. Uključiti Cloud Storage i potvrditi produkcijski bucket.
3. Provjeriti da je Cloud Messaging API omogućen. Aplikacija ne koristi legacy server key; FCM šalje Admin SDK iz Cloud Functions.
4. Instalirati CLI i prijaviti se administratorskim računom bez spremanja service-account ključa u repozitorij:

```powershell
npm --prefix functions ci
npx --prefix functions firebase-tools login
Copy-Item .firebaserc.example .firebaserc
```

U lokalnom, ignoriranom `.firebaserc` zamijeniti `your-firebase-project-id` stvarnim ID-jem, zatim:

```powershell
npm --prefix functions run build
npm --prefix functions run test:emulator
npx --prefix functions firebase-tools use smocnica-aplikacija
npx --prefix functions firebase-tools deploy --only firestore:rules,firestore:indexes,storage,functions
```

Nakon deploya u Firebase Console provjeriti da su callable funkcije u `europe-west1`, Firestore indeksi završili izgradnju i da su objavljena aktualna Firestore/Storage pravila.

#### Kontrolirani GitHub deployment

Workflow `.github/workflows/deploy-production-backend.yml` odvojen je od APK releasea i pokreće se samo ručno (`Actions > Deploy production backend > Run workflow`). Prije prve uporabe:

1. U GitHubu izraditi Environment naziva `production` i uključiti **Required reviewers**. Odobrenje u tom Environmentu je obvezna ljudska kontrola prije deploy koraka.
2. U Google Cloudu konfigurirati Workload Identity Federation za ovaj javni repozitorij i ograničiti ga na workflow s grane `master`.
3. U GitHub Environment Secrets dodati samo identifikatore `GCP_WORKLOAD_IDENTITY_PROVIDER` i `GCP_DEPLOY_SERVICE_ACCOUNT`. Ne spremati service-account JSON ni Firebase token.
4. Deployment service accountu dati najmanje ovlasti potrebne za Firebase Functions 2nd gen, Firestore rules/indexes i Storage rules deploy te `iam.serviceAccounts.actAs` samo nad runtime računom.
5. Workflow pokrenuti unosom točne potvrde `DEPLOY`, a zatim ga odobriti u Environmentu `production`.

Workflow prije objave ponovno kompajlira Functions i pokreće Emulator Suite. Nakon objave dohvaća produkcijski `functions:list`, zahtijeva cijeli manifest funkcija, poziva `getBackendCapabilities` i šalje neautorizirani, nedestruktivni zahtjev svakoj ostaloj callable funkciji. Uspjeh znači da je javni handshake vratio očekivani API/capabilities i da je svaka zaštićena funkcija dostupna te odbila zahtjev s HTTP 401/403. Izvještaj `production-smoke-report.json` ostaje kao Actions artefakt vezan uz commit.

Provjera 20. srpnja 2026. pokazala je da Environment postoji i traži odobrenje, ali OIDC identifikatori još nisu postavljeni pa se workflow zaustavlja prije autentikacije i ne mijenja Firebase. API 7 je zato taj put objavljen lokalno prijavljenim administratorskim Firebase CLI-jem i zasebno potvrđen produkcijskim smokeom. Prije sljedećeg automatiziranog deploya administrator mora unijeti oba gore navedena Environment Secreta; service-account JSON se ne smije koristiti kao zamjena.

### Produkcijski App Check za GitHub APK

Release varijanta koristi `PlayIntegrityAppCheckProviderFactory`; debug varijanta koristi Debug provider. Sve poslovne callable funkcije u produkciji imaju `enforceAppCheck: true`. Javni `getBackendCapabilities` namjerno je izuzet jer vraća samo statički kompatibilnosni manifest potreban prije ostalih poziva.

Za APK distribuiran izvan Google Playa obvezno:

1. U Google Play Console dodati aplikaciju i u App integrity > Play Integrity API povezati isti Google Cloud/Firebase projekt.
2. Firebase Console > App Check > Apps > `hr.smocnica` > Play Integrity.
3. Registrirati release SHA-256 certifikata.
4. U naprednim postavkama odabrati distribuciju izvan Google Playa:
   - `PLAY_RECOGNIZED`: **Not required**;
   - `LICENSED`: **Not required**;
   - minimum device integrity: **Device integrity**.
5. Prvo ostaviti enforcement u monitoring načinu, instalirati RC na oba uređaja i potvrditi Verified requests.
6. Zatim uključiti enforcement za Authentication, Firestore i Storage. Callable Functions već provjeravaju token u kodu.
7. Ako Functions runtime javlja nedostatak ovlasti za provjeru tokena, servisnom računu koji koristi 2nd gen funkcija dodijeliti IAM ulogu `Firebase App Check Token Verifier` (`roles/firebaseappcheck.tokenVerifier`) i ponovno deployati funkcije.

Zadana postavka zahtijeva `PLAY_RECOGNIZED`; sideloadani GitHub APK taj verdict nema i bez gornje promjene bio bi odbijen.

### Crashlytics

1. Otvoriti Firebase Console > Crashlytics za `hr.smocnica` i dovršiti inicijalizaciju.
2. Release build automatski uključuje collection; debug build je isključuje.
3. Na testnom RC-u izazvati kontrolirani sync transport error: napraviti offline mutaciju, omogućiti mrežu prema namjerno nedostupnom backendu, zatim vratiti ispravnu konfiguraciju i ponovno pokrenuti aplikaciju.
4. U Crashlyticsu potvrditi non-fatal događaj s tehničkim kodom `SYNC_TRANSPORT` bez naziva artikla, barkoda, e-maila, UID-a, naziva uređaja ili sadržaja izvoza.
5. Ne dodavati trajni “test crash” gumb u aplikaciju. Ako Firebase Console i dalje traži prvi fatalni događaj, koristiti samo privremeni lokalni test prema službenim Firebase uputama, ukloniti ga i potvrditi čist `git diff` prije RC builda.

## 3. Stabilni release certifikat i lokalni signing

Keystore izraditi jednom, izvan radnog stabla, te napraviti najmanje dvije šifrirane sigurnosne kopije. Gubitak keystorea ili lozinke onemogućuje nadogradnju postojeće instalacije.

Primjer generiranja, koji administrator izvršava ručno izvan repozitorija:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore "C:\SIGURNA\PUTANJA\smocnica-release.jks" `
  -alias smocnica `
  -keyalg RSA -keysize 4096 -validity 10000
```

U postojeći ignorirani `local.properties` dodati vrijednosti prema `local.properties.example`:

```properties
smocnica.release.storeFile=C:/SIGURNA/PUTANJA/smocnica-release.jks
smocnica.release.storePassword=STVARNA_LOZINKA
smocnica.release.keyAlias=smocnica
smocnica.release.keyPassword=STVARNA_LOZINKA_KLJUCA
```

Build odbija djelomičnu signing konfiguraciju. `applicationId` release varijante ostaje `hr.smocnica`; debug je odvojen kao `hr.smocnica.debug`.

Za lokalni updater izraditi ignorirani `app/src/release/res/values/update_config.xml` s javnim repozitorijem i SHA-256 fingerprintom istog release certifikata bez dvotočaka:

```xml
<resources>
    <string name="github_releases_api_url" translatable="false">https://api.github.com/repos/lukajerkovic1-creator/Smocnica/releases/latest</string>
    <string name="expected_signer_sha256" translatable="false">64_HEX_ZNAKA</string>
</resources>
```

## 4. GitHub Actions Secrets i javni release

Repozitorij mora biti javan. Aplikacija ne ugrađuje GitHub token; dohvaća javni Releases API, javni `release-manifest.json` i javni APK.

U Settings > Environments izraditi Environment `production`, uključiti **Required reviewers**, ograničiti deployment na granu `master` i tek u njegov **Environment secrets** postaviti:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON_BASE64`

Siguran PowerShell unos binarnih tajni kroz GitHub CLI:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\SIGURNA\PUTANJA\smocnica-release.jks')) |
  gh secret set ANDROID_KEYSTORE_BASE64 --env production

[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\SIGURNA\PUTANJA\google-services.json')) |
  gh secret set GOOGLE_SERVICES_JSON_BASE64 --env production

gh secret set ANDROID_KEYSTORE_PASSWORD --env production
gh secret set ANDROID_KEY_ALIAS --env production
gh secret set ANDROID_KEY_PASSWORD --env production
```

Zadnje tri naredbe vrijednost traže interaktivno. Base64 nije enkripcija; smije postojati samo kao vrijednost enkriptiranog Environment Secreta. Isti signing Secrets ne smiju ostati i na razini repozitorija. Job `release` koristi Environment `production`, pa se osjetljive vrijednosti ne učitavaju prije ručnog odobrenja.

Release workflow:

1. zahtijeva javni repozitorij;
2. validira semantički `versionName`, rastući `versionCode` i `minSupportedVersionCode`;
3. izvodi Functions build i Firebase emulator testove;
4. prije učitavanja produkcijskih tajni izvršava čisti Android unit-test prolaz, zatim dekodira Firebase config samo u release source-set i potvrđuje `hr.smocnica`;
5. dekodira keystore s restriktivnim pravima;
6. generira updater konfiguraciju s javnim repo URL-om i certifikatom;
7. izvodi `lintRelease`, signing provjeru i `assembleRelease` nakon već dovršenog clean/test prolaza;
8. `apksigner` potvrđuje APK i uspoređuje stvarni certifikat s očekivanim;
9. odmah nakon provjere APK-a briše dekodirani keystore i produkcijski `google-services.json` te potvrđuje da datoteke više ne postoje;
10. tek nakon brisanja tajni generira stvarne release notes i `release-manifest.json` sa SHA-256;
11. objavljuje APK i manifest ugrađenim GitHub CLI-jem (`gh release create`), bez vanjske release Action komponente.

Sve vanjske Action komponente u svim workflowima moraju biti pinane na puni commit SHA. Verzijske oznake poput `@v2`, `@v3` ili `@v4` nisu dopuštene; komentar uz SHA smije navesti ljudski čitljivu glavnu verziju.

Za ručni run u Actions > Signed Android release unijeti `version_name`, `version_code`, `minimum_supported_version_code`, opcionalni `force_update` i release notes. Za tag push workflow koristi tag kao `versionName` i rastući `github.run_number` kao `versionCode`.

Prije taga workflow mora biti commitan i pushan. Ako repozitorij još nema remote:

```powershell
git remote add origin https://github.com/lukajerkovic1-creator/Smocnica.git
git add .
git commit -m "Prepare production integration verification"
git push -u origin master
```

Tek tada izraditi i pushati novi, nepomični tag. Ne premještati postojeći `v1.0.0-rc1`, jer on ostaje sigurnosna točka prethodnog pregleda:

```powershell
git tag -a v1.0.0-rc2 -m "Smočnica production integration RC2"
git push origin v1.0.0-rc2
```

Napomena: aplikacija koristi GitHub endpoint `/releases/latest`, koji zanemaruje GitHub prerelease izdanja. RC namijenjen ovom integracijskom kanalu mora zato biti objavljen kao nedraftani obični release, iako naziv verzije sadrži `-rc1` ili `-rc2`.

## 5. Prvi lokalni potpisani release candidate

Preduvjeti: produkcijski `google-services.json`, četiri signing vrijednosti u `local.properties`, ignorirani update config, JDK 21 i Android SDK 37.

```powershell
.\gradlew.bat clean test lintRelease verifyReleaseSigningConfiguration assembleRelease `
  -PVERSION_CODE=1000001 `
  -PVERSION_NAME=1.0.0-rc1
```

Potpisani APK mora biti `app/build/outputs/apk/release/app-release.apk`, a ne `app-release-unsigned.apk`.

```powershell
$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter apksigner.bat |
  Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
& $apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Fingerprint iz `apksigner` rezultata mora biti jednak release SHA-256 u Firebase App Checku i `expected_signer_sha256` updater konfiguraciji.

## 6. Instalacija na dva uređaja

1. Uključiti USB debugging i spojiti oba Android 10+ uređaja.
2. Zabilježiti serijske brojeve s `adb devices -l`.
3. Instalirati isti RC:

```powershell
adb -s SERIAL_UREDAJA_A install -r app\build\outputs\apk\release\app-release.apk
adb -s SERIAL_UREDAJA_B install -r app\build\outputs\apk\release\app-release.apk
```

4. Na uređajima koristiti dva različita Google računa i izvršiti `REAL_DEVICE_TEST_PLAN.md`.
5. Za GitHub nadogradnju dopustiti “Install unknown apps” samo aplikaciji Smočnica kada Android prikaže standardnu potvrdu.
6. RC2 mora imati veći `versionCode`, isti `applicationId` i isti signing certifikat. `adb install -r` ili aplikacijski updater moraju zadržati Room bazu; cloud podaci dodatno se vraćaju nakon prijave.

## 7. Završni produkcijski gate

Release candidate smije u integracijski test tek kada vrijedi sve:

- `git status --short` je prazan;
- secret scan je čist;
- svi lokalni i Firebase emulator testovi prolaze;
- APK je potpisan očekivanim stabilnim certifikatom;
- SHA-1/SHA-256 su dodani pravim Firebase aplikacijama;
- App Check monitoring pokazuje Verified requests s oba sideloadana uređaja;
- release assets dostupni su bez GitHub autentikacije;
- `release-manifest.json` hash odgovara APK-u;
- scenariji u `REAL_DEVICE_TEST_PLAN.md` nemaju FAIL.

Službene reference: [Google prijava na Androidu](https://firebase.google.com/docs/auth/android/google-signin), [Play Integrity App Check](https://firebase.google.com/docs/app-check/android/play-integrity-provider), [App Check enforcement](https://firebase.google.com/docs/app-check/enable-enforcement), [App Check za callable Functions](https://firebase.google.com/docs/app-check/cloud-functions), [Crashlytics Android](https://firebase.google.com/docs/crashlytics/android/get-started), [FCM Android](https://firebase.google.com/docs/cloud-messaging/android/get-started) i [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).
