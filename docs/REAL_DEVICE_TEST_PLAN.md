# Plan integracijskog testa na dva stvarna uređaja

Ovaj plan provjerava postojeću produkcijsku funkcionalnost potpisanih izdanja `1.0.0-rc1` i `1.0.0-rc2`. Nijedan scenarij ne smije se označiti PASS samo zato što postoji gumb; potrebno je izvršiti korake i provjeriti rezultat na oba uređaja i u Firebase Console.

## Evidencija prolaza

| Polje | Vrijednost |
|---|---|
| Datum i tester |  |
| Commit/tag RC1 |  |
| RC1 versionCode / SHA-256 |  |
| Commit/tag RC2 |  |
| RC2 versionCode / SHA-256 |  |
| Release signer SHA-256 |  |
| Firebase project ID |  |
| Uređaj A / Android / serijski broj |  |
| Uređaj B / Android / serijski broj |  |
| Google račun A | zabilježiti samo internu oznaku, ne e-mail u javnom izvještaju |
| Google račun B | zabilježiti samo internu oznaku, ne e-mail u javnom izvještaju |

Status svakog testa mora biti točno jedan od: **PASS**, **FAIL**, **NIJE PROVJERENO**. Za FAIL zapisati korake reprodukcije, vrijeme, uređaj, screenshot bez osobnih podataka i relevantni tehnički kod/log bez sadržaja smočnice.

## Preduvjeti

- oba uređaja su Android 10 ili noviji, imaju aktualne Google Play services i zaključavanje zaslona;
- oba imaju instaliran isti `hr.smocnica` RC1, potpisan istim trajnim release certifikatom;
- Firebase Auth, Firestore, Storage, Functions, FCM, Crashlytics i App Check konfigurirani su prema `PRODUCTION_SETUP.md`;
- uređaji koriste dva različita Google računa;
- tester ima pristup Firebase Console, Firestore podacima, Functions logovima, App Check metrici i Crashlyticsu;
- pripremljeni su fizički EAN-8, EAN-13, UPC-A i UPC-E kodovi te jedan nepoznati kod;
- pripremljena je mala valjana JSON sigurnosna kopija i namjerno oštećena kopija;
- mreža se može zasebno isključiti na svakom uređaju.

## A. Instalacija, identitet i Google prijava

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| A-01 | Na oba uređaja instalirati RC1. U postavkama aplikacije provjeriti package/version. | Package je `hr.smocnica`; versionName/versionCode odgovaraju RC1; Android ne javlja nevaljan potpis. |  |  |
| A-02 | Na A odabrati Google prijavu i račun A. | Credential Manager prikazuje račun; prijava završava bez OAuth/SHA pogreške; korisnik dolazi na onboarding/dashboard. |  |  |
| A-03 | Na B prijaviti račun B. | Račun B ima drugi Firebase UID i ne vidi smočnicu računa A prije poziva. |  |  |
| A-04 | U Firebase Authentication provjeriti oba korisnika. | Postoje dva odvojena korisnika s Google providerom; nema duplikata nastalih ponovnom prijavom. |  |  |
| A-05 | Odjaviti A, zatim prijaviti B na isti uređaj A. | Lokalni podaci i privatne fotografije računa A nisu prikazani računu B; nakon povratka računa A podaci se obnavljaju iz clouda. |  |  |

## B. Zajednička smočnica i ovlasti

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| B-01 | Račun A izradi novu smočnicu. | A postaje OWNER; početne police/kategorije prikazane su bez duplikata. |  |  |
| B-02 | A generira pozivni kod/QR. B se pridružuje kodom ili skeniranjem. | B vidi istu pantry ID/sadržaj; kod se ne može iskoristiti ponovno; aktivnost navodi uređaj B. |  |  |
| B-03 | B pokuša upravljati članovima, vlasništvom i brisanjem smočnice. | Owner-only radnje nisu dostupne ili backend vraća permission denied; podaci ostaju nepromijenjeni. |  |  |
| B-04 | B dodaje artikl i policu, a A ih uređuje. | Oba člana imaju jednake operativne ovlasti i vide iste rezultate. |  |  |
| B-05 | A ukloni B iz članstva. | B gubi pristup smočnici i ne može je čitati/mijenjati ni nakon ponovnog pokretanja; A zadržava podatke. |  |  |
| B-06 | Ponovno pridružiti B, zatim A prenese vlasništvo na B. | Uloge se atomski zamijene; samo novi owner B može administrirati članove i brisanje. |  |  |

## C. Sinkronizacija i izolacija

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| C-01 | A doda kategoriju, policu i artikl s količinom na dvije police. | B promjene prima bez ručnog refresha; zbroj količina i raspodjela odgovaraju A. |  |  |
| C-02 | B promijeni naziv, minimum i opis artikla. | A vidi jednu konzistentnu verziju; nema dupliciranog artikla ni izgubljenih lokacija. |  |  |
| C-03 | Izraditi drugu smočnicu samo za A. | B ne može vidjeti drugu smočnicu ni izravnim pokušajem pristupa; Firestore rules bilježe denied, ne curenje. |  |  |
| C-04 | Zatvoriti aplikaciju na B, napraviti promjene na A, ponovno pokrenuti B. | B dohvaća cloud promjene; sync status završava kao “Sinkronizirano”. |  |  |
| C-05 | Na A obrisati sinkroniziran prazan entitet, zatim otvoriti B. | Hard-delete/koš stanje usklađeno je; obrisani entitet se ne vraća iz lokalnog cachea. |  |  |

## D. Offline rad, prekid i istodobne promjene

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| D-01 | Isključiti mrežu na A. Dodati artikl, promijeniti količinu i ručnu shopping stavku. | UI odmah prikazuje lokalne promjene i status čekanja; nakon force-stop/restarta promjene ostaju u Room/outboxu. |  |  |
| D-02 | Dok je A offline, B online promijeni isti artikl. Zatim uključiti mrežu na A. | Operacije se ne prepisuju tiho; konflikt ili siguran rebase vidljiv je, a konačno stanje jednako je na oba uređaja. |  |  |
| D-03 | Na A offline pokušati izvaditi više od lokalno dostupne količine. | Operacija je odbijena prije slanja; količina nikad nije negativna lokalno ni u Firestoreu. |  |  |
| D-04 | Prekinuti mrežu neposredno nakon potvrde mutacije, zatim force-stop i restart. | Idempotentni retry ne udvostručuje količinu, aktivnost ni shopping stavku. |  |  |
| D-05 | Istodobno na A i B dodati po 1 komad istog artikla. | Konačni zbroj raste za 2; nijedna promjena nije izgubljena. |  |  |
| D-06 | Istodobno preimenovati isti artikl različito. | Jedna promjena je potvrđena, druga dobiva jasan konflikt/rješenje; nema beskonačnog sync loopa. |  |  |

## E. Skener, kamera i artikli

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| E-01 | Prvi put otvoriti skener, odbiti kameru, zatim je omogućiti u Settings. | Prikazana je razumljiva poruka i akcija za postavke; povratak aktivira kameru bez rušenja. |  |  |
| E-02 | Skenirati EAN-8, EAN-13, UPC-A i UPC-E. Za svaki uključiti/isključiti bljeskalicu. | Svaki format se prepoznaje jednom; bljeskalica odgovara stanju; nema višestruke mutacije zbog istog kontinuiranog kadra. |  |  |
| E-03 | Unijeti valjani kod ručno i nevaljani kod. | Valjani kod vodi na proizvod/unos; nevaljani kod ne omogućuje potvrdu i prikazuje razlog. |  |  |
| E-04 | Skenirati poznati Open Food Facts kod uz mrežu. | Dohvaćeni podaci i slika popunjavaju editor; korisnik može lokalno ispraviti podatke bez javnog slanja. |  |  |
| E-05 | Skenirati nepoznati kod i ponoviti uz nedostupnu mrežu/timeout. | Otvara se puni ručni unos; nema mrtvog loading stanja ni gubitka skeniranog koda. |  |  |
| E-06 | Dodati artikl bez barkoda, zatim naknadno povezati barkod. | Artiklu se dodaje kod bez duplikata i količine/lokacije ostaju sačuvane. |  |  |
| E-07 | Dodati isti naziv s različitim barkodom/pakiranjem. | Nastaju dva odvojena artikla. |  |  |
| E-08 | Dodati fotografiju kamerom i galerijom. | Dozvole rade; fotografija se prikaže na B; Storage objekt nije javno dostupan bez autorizacije. |  |  |

## F. Minimum, kupnja i FCM

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| F-01 | Za artikl postaviti minimum 5 i stanje 7, zatim izvaditi 3. | Stanje je 4; nastaje točno jedna auto-stavka količine `max(5-4,0)=1`. |  |  |
| F-02 | Provjeriti obavijest na oba uređaja pri prvom prijelazu ispod minimuma. | Svaki aktivni registrirani uređaj prima jednu push-obavijest; dodir vodi na relevantno odredište. |  |  |
| F-03 | Dok je stanje i dalje ispod minimuma, ponovno smanjiti količinu. | Auto-stavka se ažurira na točnu razliku; nema druge threshold obavijesti ni duplikata stavke. |  |  |
| F-04 | Označiti auto-stavku kupljenom. | Ostaje precrtana i druge boje; nije uklonjena samim označavanjem. |  |  |
| F-05 | Stvarno unijeti dio pa cijeli manjak u smočnicu. | Količina auto-stavke se smanjuje, zatim uklanja tek kad manjka nema. |  |  |
| F-06 | Vratiti stanje na/iznad minimuma pa ponovno prijeći ispod. | Threshold se ponovno aktivira i dolazi točno jedna nova obavijest. |  |  |
| F-07 | Dodati/urediti/označiti ručnu stavku istog naziva. | Ručna stavka ostaje neovisna i ne kvari automatsku stavku. |  |  |
| F-08 | U App Check/Functions logovima pregledati FCM tijek. | Pozivi imaju valjan App Check; nema slanja uklonjenom uređaju/članu ni osobnih podataka u logovima. |  |  |

## G. Inventura po polici

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| G-01 | Pokrenuti inventuru police s nekoliko artikala, skenirati manji broj jednog, veći broj drugog i neočekivani artikl. | Pregled odvojeno prikazuje nedostajuće, neočekivane te količinske viškove/manjkove. |  |  |
| G-02 | Izaći prije potvrde i ponovno otvoriti inventuru. | Nacrt se može nastaviti/odbaciti; stvarna zaliha nije promijenjena. |  |  |
| G-03 | Na B promijeniti zalihu nakon snapshot-a, zatim na A pokušati potvrditi staru inventuru. | Primjena je odbijena kao zastarjela; B-ova promjena nije prepisana. |  |  |
| G-04 | Ponoviti inventuru iz aktualnog stanja i potvrditi. | Sve razlike primjenjuju se atomski, povijest navodi uređaj, a oba uređaja prikazuju isto stanje. |  |  |

## H. Povijest, koš i fotografije

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| H-01 | Izvesti nekoliko radnji s A i B. | Povijest ima ispravne vrste radnje, vrijeme i nazive uređaja bez zamjene autora. |  |  |
| H-02 | Obrisati artikl s fotografijom. | Artikl odlazi u koš, nestaje iz aktivnih zaliha i može se vratiti s količinama/fotografijom. |  |  |
| H-03 | Trajno obrisati stavku koša kao owner/ovlašteni operativni korisnik prema UI pravilima. | Firestore zapisi i Storage fotografija dosljedno se uklanjaju; ponovljeni purge je idempotentan. |  |  |
| H-04 | Provjeriti testne zapise s kontrolirano postavljenim istekom u emulatoru/stagingu ili pričekati scheduler u produkciji. | Koš stariji od 30 dana i povijest starija od 12 mjeseci brišu se, noviji zapisi ostaju. |  |  |
| H-05 | Pokušati obrisati nepraznu policu, zatim premjestiti sadržaj i obrisati praznu. | Neprazna polica je blokirana; prazna se sigurno briše. |  |  |

## I. Izvoz i uvoz

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| I-01 | Izvesti JSON i CSV s hrvatskim dijakriticima. | JSON ima verziju sheme; CSV se otvara kao UTF-8 i prikazuje č/ć/š/ž/đ ispravno. |  |  |
| I-02 | Uvesti valjani JSON uz “Spoji”, prvo pregledati promjene. | Ništa se ne mijenja prije potvrde; nakon potvrde spoj je atomaran i nema duplikata automatskih stavki. |  |  |
| I-03 | Ponoviti uz “Zamijeni”, zatim uz “Odustani”. | Zamijeni daje točno pregledano stanje; Odustani ne mijenja podatke. |  |  |
| I-04 | Uvesti oštećen JSON, prevelike/negativne količine, duple ID-jeve i orphan lokacije. | Svaki zlonamjeran unos je u cijelosti odbijen; postojeća smočnica ostaje bit-identično/logički nepromijenjena. |  |  |
| I-05 | Prekinuti mrežu/proces tijekom potvrđenog importa. | Nakon restarta nema djelomične korupcije; operacija je u cijelosti primijenjena jednom ili nije primijenjena. |  |  |

## J. Deinstalacija i cloud obnova

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| J-01 | Na A pričekati “Sinkronizirano”, deinstalirati aplikaciju, ponovno instalirati isti RC i prijaviti isti račun. | Postojeće članstvo i cloud podaci vraćaju se; nema nove prazne smočnice ni duplikata. |  |  |
| J-02 | Prije deinstalacije ostaviti lokalnu offline mutaciju koja nije sinkronizirana. | Tester je upozoren da nesinkronizirana lokalna promjena nije cloud backup; nakon deinstalacije cloud stanje ostaje konzistentno bez djelomične operacije. |  |  |
| J-03 | Nakon ponovne instalacije provjeriti privatne fotografije i članstvo. | Fotografije se autorizirano ponovno učitavaju; uklonjeno članstvo se ne vraća iz cachea. |  |  |

## K. Nadogradnja RC1 → RC2 bez gubitka podataka

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| K-01 | Objaviti RC1 i RC2 u javnom GitHub repozitoriju. RC2 ima veći versionCode, isti `applicationId` i certifikat. | Oba releasea imaju APK, `release-manifest.json`, bilješke i SHA-256; assets su dostupni u anonimnom browseru. |  |  |
| K-02 | Na RC1 ručno i automatski provjeriti ažuriranje. | Prikazuje se RC2 s bilješkama i ispravnim opcionalno/obavezno stanjem. |  |  |
| K-03 | Preuzeti RC2 unutar aplikacije. | Hash i certifikat prolaze; otvara se standardna Android potvrda instalacije, bez silent installa. |  |  |
| K-04 | Prvi put odbiti “Install unknown apps”, zatim omogućiti samo za Smočnicu i ponoviti. | Aplikacija daje jasan oporavak; nakon dopuštenja instalacija nastavlja. |  |  |
| K-05 | Dovršiti nadogradnju na oba uređaja bez deinstalacije. | Android prihvaća paket; verzija je RC2; prijava, Room podaci, outbox i odabrana smočnica ostaju sačuvani. |  |  |
| K-06 | Nakon nadogradnje napraviti mutaciju offline i online. | Lokalna baza/migracija radi, mutacija se sinkronizira jednom i drugi uređaj je vidi. |  |  |
| K-07 | Objaviti testni manifest s pogrešnim hashom ili APK-om drugog certifikata u zasebnom kontroliranom releaseu. | Aplikacija odbija paket prije Android instalacije i briše nevaljani download. |  |  |
| K-08 | Provjeriti offline, timeout i GitHub rate-limit stanje provjere ažuriranja. | Aplikacija ostaje upotrebljiva za opcionalni update i prikazuje razumljivu pogrešku; obavezni update ne dopušta lažno “ažurno” stanje. |  |  |

## L. App Check, Crashlytics i završni gate

| ID | Koraci | Očekivani rezultat / PASS kriterij | Status | Bilješka |
|---|---|---|---|---|
| L-01 | U App Check metrici filtrirati `hr.smocnica` tijekom rada oba uređaja. | Za Auth, Firestore i Storage dominiraju Verified requests; oba sideloadana uređaja rade uz postavke izvan Google Playa. |  |  |
| L-02 | Pokušati pozvati callable bez App Check tokena iz kontroliranog klijenta. | Produkcijska funkcija odbija zahtjev; legitimna aplikacija nastavlja raditi. |  |  |
| L-03 | Izazvati kontrolirani non-fatal `SYNC_TRANSPORT`, vratiti mrežu i ponovno pokrenuti aplikaciju. | Crashlytics primi tehnički događaj; nema PII ni sadržaja smočnice u keys/message/logovima. |  |  |
| L-04 | Pregledati Functions/Crashlytics logove nakon cijelog prolaza. | Nema e-maila, UID-a, naziva artikla, barkoda, fotografije, naziva uređaja, pozivnog koda ni sadržaja izvoza. |  |  |
| L-05 | Ponoviti secret scan i provjeru radnog stabla. | Git je čist i ne prati Firebase konfiguraciju, keystore, lozinke, `.env`, service-account JSON ni lokalni update config. |  |  |

## Odluka nakon testa

- **PASS za produkcijsku integraciju:** svi primjenjivi A–L scenariji su PASS, nema gubitka podataka, sigurnosnog curenja ni neobjašnjenog konflikta.
- **FAIL:** bilo koji sigurnosni problem, negativna količina, izgubljena/udvostručena potvrđena operacija, pogrešno članstvo, nevaljan update prihvaćen ili podatak izgubljen pri RC1 → RC2.
- **NIJE PROVJERENO:** samo kada vanjski preduvjet objektivno nije dostupan; razlog mora biti zapisan i takav scenarij ostaje release rizik.

Konačni rezultat: **________________ (PASS / FAIL / UVJETNO)**  
Odobrio: **________________**  
Datum: **________________**
