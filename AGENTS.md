# Smjernice za agente

## Izvor istine

1. `docs/APP_SPEC.md` definira ponašanje proizvoda.
2. `docs/ARCHITECTURE.md` definira granice modula, podatkovni model i sigurnosne invarijante.
3. Testovi su izvršiva specifikacija. Promjena ponašanja mora uključiti test.
4. Referentna slika `smocnica_dashboard_reference.png` ima prednost za vizualne odluke i pohranjena je u korijenu repozitorija.

## Obvezne invarijante

- Korisnik smije čitati i mijenjati samo smočnice čiji je aktivni član.
- Samo vlasnik upravlja članovima, prijenosom vlasništva i brisanjem smočnice.
- Zaliha i popis za kupnju mijenjaju se atomarno u transakciji; količina nikada ne smije biti negativna.
- Klijentski `deviceName`, `operationId` i `baseRevision` prate svaku mutaciju.
- Svaka lokalna mutacija prvo se trajno zapisuje u Room outbox. UI nikada ne tvrdi da je sinkronizirano prije potvrde poslužitelja.
- Crashlytics ne smije sadržavati naziv artikla, barkod, opis, fotografiju, e-mail, ime korisnika, naziv uređaja, pozivni kod ni sadržaj izvoza.
- Tajne, `google-services.json`, ključevi i keystore ne smiju u Git.
- Ne ostavljati TODO, `NotImplementedError`, prazne callbackove ili lažne uspješne rezultate u produkcijskom kodu.

## Radni postupak

- Kotlin i Compose kod mora prolaziti `./gradlew test lintDebug assembleDebug`.
- Firebase pravila provjeriti emulator testovima iz `functions` paketa.
- Za novu mutaciju dodati: domenski test, repository test i barem jedan negativni sigurnosni test ako mijenja ovlasti.
- Migracije baze su aditivne gdje je moguće i uvijek imaju test. Nadogradnja aplikacije ne briše Room podatke.
- Raditi male, koherentne promjene i ne prepisivati nevezane korisničke izmjene.
