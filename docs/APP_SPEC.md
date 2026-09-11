# Smočnica — specifikacija aplikacije

## 1. Cilj i opseg

„Smočnica” je nativna Android aplikacija za evidenciju kućnih zaliha hrane i drugih potrošnih artikala po policama. Mora omogućiti da dva ili više korisnika na različitim Android uređajima u stvarnom vremenu znaju:

- koji se artikli nalaze u smočnici
- koliko pakiranja postoji
- na kojoj se polici nalaze
- koji su artikli ispod minimalne zalihe
- što se nalazi na popisu za kupnju
- tko je, odnosno koji uređaj, izvršio promjenu

Prva verzija podržava jednu zajedničku smočnicu po korisniku. Model podataka mora koristiti `pantryId` kako bi se kasnije mogla dodati podrška za više smočnica bez velike migracije.

## 2. Tehnologija

Obvezno:

- Kotlin
- Jetpack Compose
- Material 3
- Android 10+ (`minSdk 29`)
- MVVM + repository pattern
- dependency injection
- Firebase Authentication
- Google prijava
- Cloud Firestore
- Firebase Storage
- Firebase Cloud Messaging
- Firebase Crashlytics
- Firebase Emulator Suite za lokalne testove
- Cloud Functions za privilegirane operacije, članstvo i push-obavijesti

Koristiti aktualne stabilne i nepovučene biblioteke. Ne spremati tajne, signing ključeve ni privatne Firebase konfiguracije u javni repozitorij.

## 3. Korisnici, smočnica i članstvo

### 3.1 Prijava

- Svaki korisnik prijavljuje se vlastitim Google računom.
- Prvi korisnik kreira zajedničku smočnicu i postaje vlasnik.
- Aplikacija generira kratki pozivni kod i QR-kod.
- Drugi korisnik pridružuje se unosom ili skeniranjem koda.
- Pozivni kod mora se moći poništiti i ponovno generirati.
- Pridruživanje mora ići kroz sigurnu Cloud Function; kod ne smije omogućiti čitanje podataka bez članstva.

### 3.2 Ovlasti

Svi članovi imaju jednake operativne ovlasti za:

- artikle
- količine
- police
- kategorije
- kupnju
- inventuru
- povijest
- koš

Samo vlasnik može:

- ukloniti člana
- prenijeti vlasništvo
- obrisati cijelu smočnicu
- rotirati pozivni kod

### 3.3 Naziv uređaja

- Pri prvom pokretanju predložiti naziv modela uređaja.
- Korisnik ga može promijeniti, npr. „Android 1”, „Android 2”.
- Svaka promjena u povijesti bilježi `deviceId` i `deviceDisplayName`, ne osobno ime.

## 4. Početni ekran i dizajn

Odobrena referenca za početni popis, dodavanje i bočni izbornik: `docs/design/minimal-inventory-reference.png`. Korisnik je izričito odabrao ovaj izgled umjesto prethodne nadzorne ploče.

Početni ekran prikazuje sve aktivne generičke artikle u ravnim kompaktnim redcima na svijetloj površini, odvojene tankim linijama. Svaki redak ima fotografiju lijevo (44 × 56 dp, cijelo pakiranje), naziv i kratku oznaku pakiranja/police, količinu desno i tri točkice. Podaci, fotografije, duljina naziva i sustavne trake ovise o stvarnim podacima i uređaju. Veća slova povećavaju visinu retka.

Na vrhu su naslov „Smočnica”, tri crtice za bočni izbornik, pretraga te neposredan odabir police i redoslijeda: „Abecedno”, „Najnovije dodano” (datum nastanka artikla) ili „Po policama” (redoslijed polica, potom naziv). Artikli na više polica pojavljuju se jednom, prema prvoj zauzetoj polici; artikli bez zalihe idu na kraj. Dodatni filtri ostaju dostupni ikonicom filtra.

Donje navigacije nema. Bočni izbornik sadrži „Svi artikli”, „Popis za kupnju”, „Police”, „Skeniraj barkod”, „Inventura”, „Povijest”, „Koš” i „Postavke”. Odabir sekcije zatvara izbornik i otvara njezin početni prikaz bez obnavljanja prethodnih podstranica.

U donjem desnom kutu je ljubičasti gumb samo s ikonicom plus (pristupačni naziv „Dodaj artikl”). Otvara donji panel s dvije radnje: „Dodaj ručno — Upiši naziv, količinu i policu” i „Fotografiraj — Slikaj proizvod i provjeri podatke”. Fotografiranje otvara kameru uz dopuštenje, predlaže podatke postojećim prepoznavanjem i zadržava fotografiju za spremanje. Ni jedan put ne sprema artikl bez potvrde korisnika; odabir police ostaje obvezan.

Zadržani su svijetli/tamni način, dodirne zone od najmanje 48 dp, upozorenja sinkronizacije, pristupačne radnje količine i višestruki odabir dugim pritiskom.

U redoslijedu „Po policama” svaka neprazna skupina ima traku preko širine popisa u boji teme, s podebljanim nazivom police i pristupačnom oznakom zaglavlja. Trake su prilagođene svijetlom i tamnom načinu. Kod filtriranja se artikl svrstava na prvu zauzetu policu među odabranim policama. Skupina bez zalihe nalazi se na kraju, pod nazivom „Bez zalihe na policama”. Ostali redoslijedi nemaju trake.

## 5. Police

Broj komada zbraja sve aktivne varijante na polici. Artikli i varijante u košu ne zauzimaju policu i ne blokiraju njezino brisanje. Njihove sačuvane količine ostaju dostupne za obnovu; ako je obrisana i polica, prvo treba vratiti policu iz koša pa artikl. Brisanje police s aktivnom zalihom ostaje zabranjeno.

Povlačenje kartice artikla ulijevo otkriva gumbe „+1” i „−1” na desnom rubu. Pomak je ograničen na širinu tih gumba; kartica ne može skliznuti iz prikaza niti se iz zatvorenog položaja pomaknuti udesno. Povlačenje otvorene kartice udesno zatvara radnje. Gesta sama ne mijenja količinu; dodir gumba izvršava promjenu i zatvara radnje. „−1” je onemogućen pri nultoj zalihi, a „+1” ostaje dostupan. U načinu višestrukog odabira povlačenje je isključeno.

Pri unosu novog artikla podaci njegove prve varijante spremaju se zajedno s artiklom u jednoj lokalnoj transakciji i jednom outbox zapisu. Ne stvara se dodatna prazna varijanta. U prikazu se izostavlja samo prepoznatljiv stari automatski početni zapis bez podataka o pakiranju, kupnji, minimumu i zalihi, ako postoji stvarna varijanta; izvorni zapis ostaje sačuvan u bazi i izvozu. Stvarne varijante s količinom nula ostaju dostupne.

Kartice polica i proizvoda slijede referentni dizajn: zaobljeni rubovi, diskretni obrubi i sjene, ljubičasti naglasci te odvojeni nazivi, količine i radnje. Kartice su kompaktne: polica ima ilustraciju od 48 dp, naziv, broj komada i gumbe „Skeniraj” / „Dodaj”; redoslijed, premještanje, preimenovanje i brisanje dostupni su u izborniku s tri točkice. Proizvodi koriste ravni popis opisan u odjeljku 4, s fotografijom, kratkim podacima, količinom i izbornikom s tri točkice. Stalne tipke + / − nisu prikazane; promjena količine dostupna je povlačenjem ulijevo, a čitaču zaslona i kroz prilagođene radnje kartice. Dodirne površine ostaju najmanje 48 dp, uz podršku svijetloj i tamnoj temi. Svi ekrani dijele usklađene boje površina, tipografiju i zaobljenja obrazaca i dijaloga; visina sadržaja raste kad korisnik poveća slova.

- Početno koristiti numerirane police.
- Korisnik može:
  - dodati policu
  - preimenovati policu
  - promijeniti redoslijed
  - pregledati artikle i količine na polici
  - skupno premjestiti artikle na drugu policu
- Policu nije moguće obrisati dok sadrži artikle.
- Prije brisanja ponuditi skupno premještanje.
- Obrisana polica ide u koš 30 dana.

## 6. Artikli i količine

### 6.1 Generički artikl i varijante

Generički artikl ima stabilni interni ID, kanonski naziv, kategoriju, generički minimum, preferiranu varijantu, uključeno/isključeno automatsko dodavanje na kupnju, oznaku „Ne grupiraj”, datume/reviziju i status aktivan/obrisan.

Svaka kupovna varijanta pripada točno jednom generičkom artiklu i ima vlastiti ID, proizvođača/naziv varijante, opcionalni jedinstveni barkod, količinu u pakiranju i mjernu jedinicu, opis/oznaku pakiranja, fotografiju i izvor fotografije, opcionalni minimum u pakiranjima, datume/reviziju i status. Barkod i fotografija pripadaju varijanti, ne generičkom artiklu.

Grupiranje se predlaže determinističkim lokalnim hrvatskim sinonimima ili owner-potvrđenim zajedničkim pravilima. Prijedlog uvijek prikazuje generički naziv i sigurnost, ali se nikada ne primjenjuje bez izričite potvrde korisnika. Samo vlasnik mijenja zajednički rječnik i oznaku „Ne grupiraj”.

### 6.2 Pravila količine

- Zaliha se vodi isključivo po cijelom broju pakiranja za kombinaciju `variantId + shelfId`; agregirana količina nikada nije zaseban izvor istine.
- „Glatko brašno 1 kg” i „Glatko brašno 500 g” različite su varijante istog generičkog artikla kada korisnik potvrdi grupiranje.
- Isti artikl smije biti na više polica.
- Prikaz:
  - broj pakiranja svake varijante po polici
  - ukupan broj pakiranja generičkog artikla
  - točan izvedeni zbroj mase, volumena ili brojivih jedinica kada su veličine poznate
  - donju granicu poznatog zbroja i zasebno nepoznata pakiranja kada dio varijanti nema poznatu veličinu
- Dodavanje i vađenje zadano nude 1 kom, uz promjenu količine.
- Vađenje iznad dostupnog stanja mora biti spriječeno.
- Ručni artikl bez barkoda mora se kasnije moći povezati sa skeniranim barkodom.
- Brisanje zadnje aktivne varijante stavlja i generički artikl u koš; vraćanje obnavlja veze i zalihe.
- Omogućiti premještanje varijante u drugi generički artikl i razdvajanje u novi generički artikl uz revizijsku zaštitu od konflikta.

## 7. Skeniranje barkoda

Podržati:

- EAN-8
- EAN-13
- UPC-A
- UPC-E

Funkcije:

- jedan glavni ekran skenera
- uključivanje bljeskalice
- ručni unos broja barkoda
- zaštita od višestrukog očitanja istog koda u kratkom intervalu
- pristupačan prikaz dozvole za kameru i oporavak nakon odbijene dozvole

Nakon očitanja:

1. pronaći točnu lokalnu varijantu po barkodu
2. ako ne postoji, pokušati Open Food Facts
3. prikazati podatke proizvoda
4. ponuditi:
   - „Dodaj u smočnicu”
   - „Izvadi iz smočnice”
5. kod dodavanja odabrati policu i količinu
6. kod vađenja, ako je proizvod na više polica, prikazati sve lokacije i količine

## 8. Open Food Facts i fotografije

- Open Food Facts je primarni javni izvor.
- Podaci moraju biti tretirani kao prijedlog i korisnik ih može ispraviti.
- Ako nema rezultata, otvoriti brzi unos s fotografiranjem prednje strane ambalaže. Google Gemini predlaže generički naziv na hrvatskom, proizvođača i veličinu pakiranja. Prijedlog nije potvrđena identifikacija: korisnik može ispraviti podatke prije spremanja. Nepoznati proizvođač i veličina ostaju prazni.
- Brzi unos prikazuje naziv, opcionalnog proizvođača, veličinu pakiranja, policu i broj pakiranja (zadano 1). Polica je obvezna; kontekstualna polica ima prednost, inače se pamti zadnja uspješno korištena polica te smočnice na uređaju. Obrisana polica se ne koristi.
- Kategorija ima valjanu zadanu vrijednost. Naziv varijante, barkod, opis, minimumi i automatska kupnja dostupni su u dodatnim postavkama; prazni barkod može se skenirati u brzom unosu. Postojeća podudaranja generičkog artikla nude kratku, izričitu potvrdu grupiranja.
- Prepoznavanje fotografije treba internet i koristi samo Gemini projekt na besplatnom paketu bez uključenog billinga. Dosegnuta kvota ili pogreška nude ponavljanje i ručni unos, bez automatskog prelaska na plaćenu uslugu. Već spremljeni barkodovi i dalje rade lokalno. Fotografija ne mijenja zalihe prije korisnikova spremanja.
- Lokalne izmjene ostaju samo u zajedničkoj smočnici i ne šalju se automatski javnoj bazi.
- Ako postoji javna fotografija, prikazati je.
- Korisnik može snimiti vlastitu fotografiju ili odabrati postojeću.
- Fotografija upotrijebljena za prepoznavanje zadano se sprema uz varijantu. Pregled slike ima uključenu opciju „Prikaži ovu fotografiju na kartici”; korisnik je može isključiti za prepoznavanje bez spremanja ili odabrati drugu fotografiju. Slika se prenosi tek pritiskom na „Spremi”.
- Generička kartica koristi fotografiju reprezentativne varijante, a ako je nema, fotografiju druge aktivne varijante (prvo preferirane, zatim zadnje ažurirane). Kartica pronađena točnim podudaranjem varijante ne prikazuje fotografiju drugog pakiranja.
- Vlastitu fotografiju:
  - smanjiti i komprimirati prije prijenosa
  - spremiti u Firebase Storage
  - čuvati jednu glavnu fotografiju po varijanti
  - obrisati iz Storagea pri trajnom brisanju varijante, uz zaštitu od slučajnog gubitka tijekom 30-dnevnog koša

## 9. Minimalna zaliha i popis za kupnju

### 9.1 Automatsko pravilo

Za generički artikl s uključenim automatskim dodavanjem generički i varijantni minimumi daju jednu objedinjenu stavku kupnje. Potrebni broj preferirane varijante zaokružuje se prema gore na cijelo pakiranje:

`potrebnoZaKupnju = max(minimalnaKoličina - ukupnaKoličina, 0)`

Primjer:

- minimalno 2 kom
- trenutno 1 kom
- automatski dodati 1 kom na popis

Ne postoji posebna ciljana zaliha.

### 9.2 Obavijest

- Push-obavijest šalje se svim registriranim uređajima samo pri prijelazu iz stanja `na minimumu ili iznad` u stanje `ispod minimuma`.
- Ne ponavljati obavijest dok je artikl i dalje ispod minimuma.
- Kad se zaliha vrati na minimum ili iznad, budući novi pad ponovno može poslati obavijest.
- Svaki uređaj bira privatnost sadržaja push-obavijesti. Zadani način je **Privatne obavijesti** s generičkim tekstom bez naziva artikla i količina. **Detaljne obavijesti** izričito uključuju naziv artikla i količine na zaključanom zaslonu.
- Promjena na manje privatni način vrijedi tek nakon što je sigurno spremljena uz registraciju tog uređaja na poslužitelju.
- Dodirom otvoriti odgovarajući artikl/stavku kupnje.
- Primjer:
  - „Glatko brašno 1 kg: preostao je 1 kom, na popis je dodan 1 kom.”

### 9.3 Stanje stavke kupnje

- Stavke se grupiraju po kategorijama.
- Podržati automatske i ručne stavke.
- Količina ručne stavke može se uređivati.
- Označavanjem „kupljeno” stavka ostaje na popisu:
  - precrtana
  - u drugoj boji
- Samo označavanje kupljenim ne mijenja stanje smočnice.
- Pri stvarnom skeniranju i unosu proizvoda u smočnicu automatska stavka se smanjuje prema preostalom manjku.
- Kad manjak postane 0, stavka se uklanja iz aktivnog popisa ili arhivira kao izvršena.
- Ne dopustiti duplikate automatske stavke za isti artikl.

## 10. Pretraga, filtri i prikazi

Prikazi:

- sve zalihe
- po polici
- po kategoriji
- ispod minimuma
- na popisu za kupnju

Rezultat pretrage prikazuje:

- fotografiju
- naziv
- opis pakiranja
- ukupnu količinu
- police i količine po polici
- status minimuma/kupnje

Kategorije:

- aplikacija dolazi s unaprijed definiranim kategorijama hrane i kućnih potrepština
- korisnik ih može dodavati, preimenovati, mijenjati redoslijed i brisati
- brisanje kategorije mora tražiti premještanje artikala ili dodjelu zadane kategorije

## 11. Inventura

Tijek:

1. korisnik odabire policu
2. započinje inventuru
3. skenira sve fizički prisutne artikle
4. za više jednakih komada može skenirati jednom i upisati količinu
5. aplikacija uspoređuje evidentirano i stvarno stanje
6. prikazuje:
   - artikle koji nedostaju
   - neočekivane artikle
   - razlike u količinama
7. nijedna promjena ne mijenja stvarno stanje prije završne potvrde
8. nakon potvrde promjene se izvršavaju atomarno koliko je moguće i zapisuju u povijest

Prekid inventure mora omogućiti nastavak ili sigurno odbacivanje nacrta.

## 12. Povijest aktivnosti

Bilježiti najmanje:

- dodavanje količine
- vađenje količine
- premještanje između polica
- ručnu korekciju
- uređivanje artikla
- brisanje i vraćanje
- inventuru
- promjene minimuma
- promjene popisa kupnje
- članstvo i administrativne događaje

Zapis sadrži:

- vrstu događaja
- datum/vrijeme poslužitelja
- artikl/policu
- staru i novu vrijednost
- količinu
- `deviceId`
- naziv uređaja
- korisnički UID samo za sigurnosnu sljedivost, bez prikaza osobnog imena

Čuvati 12 mjeseci. Za automatsko uklanjanje koristiti Firestore TTL ili sigurnu zakazanu funkciju.

## 13. Koš

- Obrisani artikli, police i relevantni zapisi idu u koš.
- Zadržavanje: 30 dana.
- Omogućiti:
  - vraćanje
  - trajno ranije brisanje uz jasnu potvrdu
- Automatsko trajno brisanje nakon 30 dana preko TTL-a ili zakazane funkcije.
- Vraćanje mora obnoviti veze s policama i kategorijama ili tražiti novu lokaciju ako izvor više ne postoji.

## 14. Offline-first i sinkronizacija

Aplikacija mora raditi bez interneta za pregled i uobičajene promjene.

Prikazati status:

- sinkronizirano
- promjene čekaju sinkronizaciju
- pogreška sinkronizacije
- konflikt koji traži odluku korisnika

Za količine ne koristiti običan „read-modify-write” bez zaštite.

Preporučena strategija:

- lokalni outbox za operacije koje još nisu potvrđene poslužiteljem
- online promjene kroz Firestore transakciju ili sigurnu Cloud Function
- offline operacije čuvati s jedinstvenim `operationId`
- pri povratku veze ponoviti idempotentno
- ne dopustiti tiho nastajanje negativne količine
- pri konfliktu korisniku prikazati stvarno stanje i ponuditi usklađivanje

Za istodobno dodavanje na dva uređaja operacije se moraju zbrojiti, ne prepisati.

Za uređivanje metapodataka može vrijediti posljednja potvrđena izmjena, uz zapis u povijesti.

## 15. Predloženi Firestore model

Codex može poboljšati model, ali mora zadovoljiti sigurnost, transakcije i upite.

```text
users/{uid}
pantries/{pantryId}
pantries/{pantryId}/members/{uid}
pantries/{pantryId}/devices/{deviceId}
pantries/{pantryId}/shelves/{shelfId}
pantries/{pantryId}/categories/{categoryId}
pantries/{pantryId}/products/{productId}
pantries/{pantryId}/variants/{variantId}
pantries/{pantryId}/synonymRules/{ruleId}
pantries/{pantryId}/stocks/{variantId_shelfId}
pantries/{pantryId}/shoppingItems/{shoppingItemId}
pantries/{pantryId}/activities/{activityId}
pantries/{pantryId}/trash/{trashId}
pantries/{pantryId}/inventorySessions/{sessionId}
inviteCodes/{hashedCode}
```

Razmotriti denormalizirane agregate samo ako se dosljedno održavaju transakcijama/funkcijama.

## 16. Sigurnost

Firestore/Storage pravila moraju osigurati:

- korisnik vidi samo smočnicu čiji je član
- korisnik ne može sam sebi dodijeliti članstvo
- članstvo preko pozivnog koda obavlja backend
- samo vlasnik upravlja članovima i brisanjem smočnice
- operativne promjene dopuštene su članovima
- veličina i tip fotografija su ograničeni
- tuđi Storage objekti nisu dostupni
- klijent ne može lažirati vlasničke ovlasti
- osjetljive administrativne operacije idu kroz Cloud Functions

### Privatnost i brisanje korisničkog računa

- `Izbornik` mora nuditi trajno brisanje prijavljenog korisničkog računa uz jasnu potvrdu posljedica.
- Poslužitelj uklanja Firebase Auth račun, `users/{uid}` i uređaje, FCM tokene, pristupni lock te sva članstva.
- Ako korisnik posjeduje smočnicu s drugim aktivnim članovima, vlasništvo se prije uklanjanja deterministički prenosi aktivnom članu. Ako drugih članova nema, smočnica se stavlja u 30-dnevni rok trajnog brisanja.
- Zajednički revizijski zapisi koji ostaju drugim članovima moraju se anonimizirati; osobni identifikatori i nazivi uređaja ne smiju ostati u njima.
- Politika privatnosti i procedura zahtjeva za brisanje moraju biti javno dostupne bez instalacije te povezane iz zaslona `O aplikaciji`.
- `O aplikaciji` mora navesti Open Food Facts atribuciju i licence. Zahtjevi moraju slati stvarni naziv/verziju aplikacije i stvarni kontakt u `User-Agent` zaglavlju.

Ne zapisivati nazive artikala, sadržaj kupnje, barkodove ni osobne podatke u Crashlytics poruke.

## 17. Uvoz i izvoz

### JSON

Mora obuhvatiti:

- smočnicu
- police
- kategorije
- artikle
- lokacije i količine
- minimalne zalihe
- popis kupnje
- postavke
- povijest ako korisnik odabere

Uvoz:

- validacija sheme i verzije
- pregled promjena prije primjene
- opcije:
  - spoji
  - zamijeni postojeće
  - odustani
- jasno prikazati konflikte
- primjena mora biti transakcijska ili imati mogućnost sigurnog povratka

### CSV

Izvesti pregledan CSV za Excel s pravilnim UTF-8 kodiranjem i hrvatskim znakovima. Najmanje stupci:

- naziv
- barkod
- pakiranje
- kategorija
- ukupna količina
- minimalna količina
- police
- stanje kupnje

## 18. Ažuriranje preko GitHub Releasesa

Aplikacija nije na Google Playu.

Preporučeni model:

- izvorni kod može ostati privatan
- zaseban javni repozitorij s izdanjima i APK-ovima
- bez GitHub tokena ugrađenog u aplikaciju

Funkcionalnosti:

- automatska provjera pri svakom otvaranju
- ručna provjera u postavkama
- prikaz verzije, bilješki izdanja i veličine
- opcionalna i obavezna ažuriranja
- gumb „Preuzmi najnoviju verziju”
- mogućnost odgode opcionalnog ažuriranja
- vođenje korisnika kroz Android dopuštenje „Install unknown apps”
- standardna korisnička potvrda instalacije; nema tihog instaliranja

Svako izdanje treba imati strojno čitljiv manifest, primjerice:

```json
{
  "versionCode": 12,
  "versionName": "1.2.0",
  "minSupportedVersionCode": 10,
  "forceUpdate": false,
  "apkUrl": "...",
  "sha256": "...",
  "releaseNotes": "..."
}
```

Sigurnost nadogradnje:

- stabilan `applicationId`
- svaki APK potpisan istim produkcijskim ključem
- provjera SHA-256
- provjera certifikata potpisnika APK-a prema ugrađenom očekivanom fingerprintu
- Android sustav također mora potvrditi kompatibilan potpis
- signing ključ nikad ne spremati u repozitorij
- GitHub Actions koristi encrypted secrets
- migracije lokalne baze moraju biti testirane
- promjene backend sheme moraju biti unatrag kompatibilne ili označene kao obavezno ažuriranje

Deinstalacija aplikacije ne smije izgubiti cloud podatke. Nakon ponovne instalacije i prijave korisnik ponovno dohvaća zajedničku smočnicu.

## 19. Crashlytics i dijagnostika

Uključiti Crashlytics uz sljedeća pravila:

- ne slati sadržaj smočnice
- ne slati fotografije, barkodove ni popis kupnje
- koristiti tehničke kodove događaja
- zapisivati neuspjeh sinkronizacije bez poslovnog sadržaja
- omogućiti jasno rukovanje pogreškama u UI-u

## 20. Testovi

Obvezno:

- unit testovi poslovne logike
- testovi izračuna minimalne zalihe i kupnje
- testovi prijelaza za jednokratnu obavijest
- testovi količina na više polica
- testovi konflikta i idempotentnih operacija
- repository testovi
- Firestore security-rules testovi u emulatoru
- Cloud Functions testovi
- Compose UI testovi ključnih tokova
- instrumentirani testovi skenera koliko je izvedivo
- testovi JSON uvoza/izvoza
- testovi migracije lokalne baze
- test provjere verzije, checksum-a i potpisa APK-a

Ključni korisnički scenariji:

1. kreiranje smočnice i pridruživanje drugog računa
2. skeniranje poznatog proizvoda
3. ručni unos nepoznatog proizvoda
4. dodavanje više komada na policu
5. vađenje s jedne od više polica
6. automatski pad ispod minimuma
7. samo jedna push-obavijest po prijelazu
8. označavanje kupljenog i stvarni unos u smočnicu
9. istodobna promjena s dva uređaja
10. offline promjena i kasnija sinkronizacija
11. inventura s razlikama
12. brisanje i vraćanje iz koša
13. uvoz JSON-a s konfliktima
14. ažuriranje APK-a bez gubitka podataka

## 21. Dokumentacija i isporuka

Repozitorij mora sadržavati:

- `AGENTS.md`
- `README.md`
- `docs/APP_SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/IMPLEMENTATION_PLAN.md`
- `docs/TEST_PLAN.md`
- Firebase security rules
- Storage rules
- Cloud Functions
- emulator konfiguraciju
- predložak lokalne konfiguracije bez tajni
- GitHub Actions build/release workflow
- upute za:
  - Firebase projekt
  - Google prijavu
  - SHA certifikate
  - FCM
  - Crashlytics
  - Open Food Facts
  - GitHub Releases
  - signing ključ
  - izradu i instalaciju APK-a
  - obnovu nakon deinstalacije

## 22. Definicija dovršenosti

Aplikacija nije dovršena samo zato što se kompilira.

Dovršena je kada:

- svi glavni tokovi rade s dva različita Google računa
- promjene se sinkroniziraju između dva uređaja
- offline promjene se sigurno usklade
- nema gubitka ili tihog prepisivanja količina
- skener i ručni unos rade
- Open Food Facts ima siguran fallback
- kupnja i minimum rade prema definiranoj formuli
- inventura primjenjuje promjene tek nakon potvrde
- koš, povijest, uvoz i izvoz rade
- GitHub updater provjerava verziju, checksum i potpis
- nadogradnja istim potpisom zadržava aplikacijske podatke
- ponovna instalacija nakon prijave vraća cloud podatke
- sigurnosna pravila prolaze testove
- nema P0 ni P1 grešaka
- nema nedokumentiranih placeholdera, lažnih gumba ni praznih implementacija
- README omogućuje drugom developeru ponoviti postavljanje projekta
