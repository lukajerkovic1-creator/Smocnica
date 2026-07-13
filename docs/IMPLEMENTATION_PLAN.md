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

