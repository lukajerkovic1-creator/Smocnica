# Open Food Facts integracija

Smočnica trenutačno čita javni kompatibilni API v2 samo za dohvat podataka po barkodu. Ne šalje lokalne ispravke u Open Food Facts.

## Atribucija

- baza proizvoda: Open Food Facts, Open Database License (ODbL);
- pojedinačni sadržaj baze: Database Contents License;
- fotografije: Creative Commons Attribution-ShareAlike (CC BY-SA).

Atribucija i poveznica na uvjete prikazuju se u zaslonu `O aplikaciji`.

## Identitet klijenta

Svaki zahtjev šalje `User-Agent` oblika:

`Smocnica/<BuildConfig.VERSION_NAME> (https://github.com/lukajerkovic1-creator/Smocnica; luka.jerkovic1@gmail.com)`

Verzija dolazi iz istog `BuildConfig.VERSION_NAME` koji se prikazuje i potpisuje u APK-u; nije tvrdo kodirana u podatkovnom modulu.

## Plan prijelaza na API v3

1. Dodati zasebne DTO-e za `GET /api/v3/product/{barcode}` i ugovorne testove na spremljenim, anonimiziranim odgovorima.
2. Usporediti mapiranje naziva, pakiranja, kategorija i dopuštenih domena fotografija s postojećim v2 tokom.
3. Uvesti v3 iza internog adaptera bez promjene domenskog sučelja i zadržati ručni unos za timeout/error/empty.
4. Provesti testove na EAN-8, EAN-13, UPC-A i UPC-E uz rate-limit/timeout scenarije.
5. Ukloniti v2 tek nakon produkcijske provjere v3 i ažuriranja atribucije ako se uvjeti promijene.
