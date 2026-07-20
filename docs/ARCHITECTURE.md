# Arhitektura

## Moduli i ovisnosti

```text
app (Compose, MVVM, Android integracije)
  ├── core:domain (use-caseovi i repository ugovori)
  └── core:data (Room, Firestore, Storage, Open Food Facts, sync)
        ├── core:domain
        └── core:model
core:domain ── core:model
```

`core:model` ne poznaje Android ni Firebase. `core:domain` sadrži invarijante i portove. `core:data` je jedino mjesto koje poznaje lokalnu i udaljenu pohranu. `app` sadrži Hilt composition root, ViewModele, navigaciju i Compose zaslone.

## Lokalni model i offline operacije

Room je autoritativni izvor za UI i trajni offline cache. Svaka korisnička mutacija u jednoj lokalnoj transakciji:

1. validira domenske invarijante;
2. ažurira lokalne read-modele;
3. dodaje `PendingOperation(operationId UUID, pantryId, aggregateType, aggregateId, kind, baseRevision, payload, deviceName, createdAt, attempts, state)`;
4. označava zahvaćene retke `PENDING`.

`SyncWorker` uz mrežnu vezu redom umetanja šalje idempotentne operacije callable Cloud Functionu `applyOperation`. Sinkronizacija staje na prvoj privremenoj pogrešci ili konfliktu kako kasnije zavisne operacije ne bi preskočile prethodnika. Poslužitelj u Firestore transakciji provjerava članstvo, ovlasti, očekivanu reviziju i `operationId`. Već obrađena operacija vraća prethodni rezultat. Potvrda dopušta realtime sloju da zamijeni lokalni zapis poslužiteljskim stanjem i postavlja `SYNCED`; privremena pogreška ostaje `PENDING`, a konflikt postaje `CONFLICT` i prikazuje se korisniku.

Nakon prijave callable `listMyPantries` obnavlja aktivna članstva i pokreće realtime slušatelje, pa se cloud podaci vraćaju i nakon ponovne instalacije. Odjava je dopuštena tek nakon pražnjenja outboxa, deaktivira FCM token i zatim briše sve Room tablice i privatni cache fotografija kako drugi račun na istom uređaju ne bi vidio prethodne podatke.

### Konflikti

- Količinske promjene su komutativni delta događaji; poslužitelj odbija samo rezultat ispod nule.
- Preimenovanje/opis/minimum koriste optimističku `revision`. Klijent nudi „Zadrži moje” (nova operacija nad zadnjom revizijom) ili „Prihvati udaljeno”.
- Redoslijed polica šalje cijeli poredak i revision smočnice; konflikt se ponovno bazira na trenutačnom skupu polica.
- Inventura šalje očekivanu revision i konačni skup količina; primjenjuje se sve ili ništa.
- Brisanja su tombstone zapisi; obnova nadjačava brisanje samo prije `purgeAfter` i uz novu reviziju.

Firestore offline cache nije izvor prikaza i isključen je kako ne bi postojao drugi nesinkronizirani cache; Room + outbox čine jedinstvenu offline strategiju.

## Firestore model

```text
users/{uid}
  displayName, photoUrl, createdAt, lastSeenAt
  devices/{deviceId}: name, fcmToken, platform, updatedAt, active

pantries/{pantryId}
  name, ownerUid, memberUids[], revision, createdAt, updatedAt, deletedAt?, purgeAfter?
  members/{uid}: role(OWNER|MEMBER), joinedAt, invitedBy, active
  shelves/{shelfId}: name, normalizedName, sortOrder, revision, deletedAt?, purgeAfter?
  shelfNames/{sha256(normalizedName)}: normalizedName, shelfId, updatedAt
  categories/{categoryId}: name, normalizedName, sortOrder, isDefault, revision, deletedAt?, purgeAfter?
  categoryNames/{sha256(normalizedName)}: normalizedName, categoryId, updatedAt
  products/{productId}: name, normalizedName, barcode?, description, categoryId, category,
    photoUrl?, photoSource, minimumQuantity, autoShopping, totalQuantity, revision,
    createdAt, updatedAt, deletedAt?, purgeAfter?
  stocks/{productId_shelfId}: productId, shelfId, quantity, revision, updatedAt
  shoppingItems/{itemId}: productId?, name, categoryId, category, requiredQuantity, checked,
    manual, revision, createdAt, updatedAt, deletedAt?
  inventorySessions/{inventoryId}: shelfId, status(APPLIED), expectedRevision,
    differences[], actorUid, deviceId, deviceDisplayName, createdAt, appliedAt
  activities/{activityId}: type, aggregateId, productId?, shelfId?, fromShelfId?,
    toShelfId?, quantityDelta?, oldValue?, newValue?, deviceId,
    deviceDisplayName, actorUid, createdAt, expiresAt
  operations/{operationId}: actorUid, resultRevision, appliedAt, resultDigest
```

`userPantryAccess/{uid}` je posžlužiteljski upravljan, kanonski lock za točno jednu aktivnu smočnicu po korisniku. Stvaranje smočnice u istoj transakciji zapisuje lock i prihvaća obvezni `requestId`; ponavljanje istog zahtjeva vraća isti zapis, dok drugi zahtjev ili pridruživanje drugoj smočnici završava s `ALREADY_EXISTS`. Uklanjanje člana i brisanje smočnice uklanjaju odgovarajući lock. Klijent nema izravan read/write pristup toj kolekciji.

Poslužiteljski limiti su 10 aktivnih članova, 50 aktivnih polica, 50 aktivnih kategorija, 500 aktivnih artikala i jedna aktivna pozivnica po smočnici. Limiti se provjeravaju u istoj transakciji za stvaranje, vraćanje i uvoz kako paralelni zahtjevi ne bi prekoračili granicu.

Globalni `barcodes/{sha256(pantryId:barcode)}` dokument rezervira barkod u transakciji i pokazuje na `productId`. `inviteCodes/{sha256(code)}` pokazuje na smočnicu bez otkrivanja koda u čistom obliku. Svi klijentski zapisi idu kroz callable funkcije; pravila dopuštaju izravan read aktivnim članovima, a write samo administrativnom SDK-u. Time se složene transakcijske invarijante ne mogu zaobići modificiranim klijentom.

Za svaku novu operativnu mutaciju poslužitelj u istoj transakciji provjerava da je `deviceId` aktivan pod `users/{actorUid}/devices`. `deviceDisplayName`, naziv artikla i nazive polica izvodi isključivo iz poslužiteljskih dokumenata. Aktivnost čuva strukturirane identifikatore; Android generira opis iz trenutačnih lokalnih zapisa, uz poslužiteljski snapshot teksta samo kao kompatibilni prikaz starih aktivnosti. Room shema 2 dodaje te identifikatore aditivnom migracijom 1→2 bez brisanja podataka.

`categoryId` je kanonska veza artikla i svake ručne ili automatske stavke kupnje na aktivni dokument kategorije; `shelfId` je jedina veza prema polici. Klijent može spremiti poslužiteljski naziv radi offline prikaza, ali poslovna logika nikada iz njega ne izvodi ID. `applyOperation` ignorira poslani naziv i dohvaća ga iz `categories/{categoryId}`. Room shema 5 aditivnom migracijom 4→5 dodaje `categoryId` stavkama kupnje i `normalizedName` policama/kategorijama bez brisanja podataka.

Nazivi polica i kategorija normaliziraju se Unicode NFKC postupkom, uklanjanjem rubnih i sažimanjem višestrukih razmaka te hrvatskim lowercaseom. Poslužitelj u istoj transakciji provjerava aktivne dokumente i determinističku rezervaciju u `shelfNames`/`categoryNames`, pa dva paralelna zahtjeva ne mogu stvoriti isto ime s različitim velikim slovima ili razmacima. Klijent nema izravan pristup rezervacijama. `listMyPantries` idempotentno kanonizira starije zapise prije realtime sinkronizacije; ako su stari podaci već dvosmisleni, odbija pristup uz jasnu administratorsku pogrešku umjesto proizvoljnog spajanja. U svakoj smočnici točno jedna aktivna kategorija ima `isDefault=true`; klijentski `isDefault` se ignorira, a zadana kategorija ne može se obrisati. Open Food Facts taksonomija mapira se samo na deset lokalnih kategorija; udaljena javna fotografija dopuštena je samo preko HTTPS hosta `images.openfoodfacts.org` i puta `/images/products/`.

Nacrti inventure ostaju u Roomu dok ih korisnik ne primijeni ili odbaci. Potvrđena inventura šalje jednu atomarnu operaciju s hashom trenutačnih količina police; poslužitelj u Firestore sprema samo sanitizirani zapis primijenjene inventure, nikada nedovršeni nacrt.

## Cloud Functions

- `getBackendCapabilities` je javni, neosjetljivi handshake koji vraća samo `backendApiVersion`, statičke capability oznake i očekivani manifest funkcija. Android prije registracije uređaja i obnove cloud podataka zahtijeva API 4 te `operation:delete_shopping`, `device-registration:v2`, `notification-privacy:v1`, `single-active-pantry:v1` i `canonical-names:v1`. Potvrđena verzija lokalno se pamti samo za privremeni offline fallback; izričito zastario ili nepotpun odgovor uvijek blokira udaljene pozive.
- `createPantry`, `listMyPantries`, `createInvitation`, `joinPantry`, `manageMember`, `transferOwnership`, `deletePantry`, `registerDevice`, `unregisterDevice` i `purgeTrash`.
- `applyOperation`: validira i atomarno primjenjuje police, artikle, zalihe, kupnju i obnovu.
- `apply_inventory` grana u `applyOperation`: atomarno validira SHA-256 izvedeni snapshot količina police i primjenjuje potvrđene razlike.
- Transakcija zalihe na prijelazu ispod minimuma stvara jedinstveni notification dokument; Firestore trigger šalje FCM svim aktivnim uređajima samo za taj prijelaz.
- `users/{uid}/devices/{deviceId}.detailedNotifications` je poslužiteljski izvor postavke privatnosti zaključanog zaslona. Nedostajuća ili neispravna vrijednost tretira se kao privatna. Trigger grupira tokene prema postavci, a kod dupliciranog tokena uvijek pobjeđuje privatni način, tako da generički primatelji nikada ne dobivaju naziv ni količine u FCM notification payloadu.
- dnevni `purgeExpiredData` briše tombstone i pripadajuće Storage objekte nakon 30 dana; `purgeOldActivities` briše aktivnosti starije od 365 dana.

## Sigurnost i privatnost

- Auth token je obvezan za sve poslovne funkcije; produkcijske poslovne callable funkcije provode App Check (Play Integrity), dok ga Emulator Suite namjerno isključuje. Jedina iznimka je `getBackendCapabilities`, koji je dostupan bez prijave i App Checka jer vraća samo statičku verziju/manifest bez korisničkih ili poslovnih podataka.
- Pozivni kod koristi 16 kriptografski nasumičnih znakova iz skupa bez dvosmislenih znakova (80 bita entropije), pohranjen je samo kao SHA-256, jednokratan i vremenski ograničen.
- Storage put je `pantries/{pantryId}/products/{productId}/main.jpg`; pravila provjeravaju aktivno članstvo, postojanje aktivnog artikla, točan put/metapodatak, JPEG MIME i najviše 5 MiB. Klijent u Room sprema privatni `gs://` URI i sliku dohvaća autoriziranim Storage SDK-om, bez trajnog bearer download tokena.
- Crashlytics bilježi samo šifru pogreške, sloj, verziju aplikacije i nasumični installation ID. U produkciji se `setUserId` ne poziva i nema poslovnih polja u porukama iznimke.
- Izvozi se stvaraju lokalno preko Storage Access Frameworka i nisu slani poslužitelju.

## Ažuriranja

GitHub release sadrži potpisani APK i `release-manifest.json`. Manifest sadrži `versionCode`, `versionName`, `minSupportedVersionCode`, `forceUpdate`, `sha256`, `apkUrl` i `releaseNotes`. Aplikacija koristi javni HTTPS API, ograničava hostove, provjerava hash, zatim certifikat APK-a naspram fingerprinta ugrađenog tijekom release builda prije standardnog `ACTION_VIEW` Package Installer tijeka preko `FileProvider`a.
