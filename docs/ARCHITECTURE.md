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
  shelves/{shelfId}: name, sortOrder, revision, deletedAt?, purgeAfter?
  categories/{categoryId}: name, sortOrder, isDefault, revision, deletedAt?, purgeAfter?
  products/{productId}: name, normalizedName, barcode?, description, category,
    photoUrl?, photoSource, minimumQuantity, autoShopping, totalQuantity, revision,
    createdAt, updatedAt, deletedAt?, purgeAfter?
  stocks/{productId_shelfId}: productId, shelfId, quantity, revision, updatedAt
  shoppingItems/{itemId}: productId?, name, category, requiredQuantity, checked,
    manual, revision, createdAt, updatedAt, deletedAt?
  inventorySessions/{inventoryId}: shelfId, status(APPLIED), expectedRevision,
    differences[], actorUid, deviceId, deviceDisplayName, createdAt, appliedAt
  activities/{activityId}: type, aggregateId, quantityDelta?, oldValue?, newValue?,
    deviceId, deviceDisplayName, actorUid, createdAt, expiresAt
  operations/{operationId}: actorUid, resultRevision, appliedAt, resultDigest
```

Globalni `barcodes/{sha256(pantryId:barcode)}` dokument rezervira barkod u transakciji i pokazuje na `productId`. `inviteCodes/{sha256(code)}` pokazuje na smočnicu bez otkrivanja koda u čistom obliku. Svi klijentski zapisi idu kroz callable funkcije; pravila dopuštaju izravan read aktivnim članovima, a write samo administrativnom SDK-u. Time se složene transakcijske invarijante ne mogu zaobići modificiranim klijentom.

Nacrti inventure ostaju u Roomu dok ih korisnik ne primijeni ili odbaci. Potvrđena inventura šalje jednu atomarnu operaciju s hashom trenutačnih količina police; poslužitelj u Firestore sprema samo sanitizirani zapis primijenjene inventure, nikada nedovršeni nacrt.

## Cloud Functions

- `createPantry`, `listMyPantries`, `createInvitation`, `joinPantry`, `manageMember`, `transferOwnership`, `deletePantry`, `registerDevice`, `unregisterDevice` i `purgeTrash`.
- `applyOperation`: validira i atomarno primjenjuje police, artikle, zalihe, kupnju i obnovu.
- `apply_inventory` grana u `applyOperation`: atomarno validira SHA-256 izvedeni snapshot količina police i primjenjuje potvrđene razlike.
- Transakcija zalihe na prijelazu ispod minimuma stvara jedinstveni notification dokument; Firestore trigger šalje FCM svim aktivnim uređajima samo za taj prijelaz.
- dnevni `purgeExpiredData` briše tombstone i pripadajuće Storage objekte nakon 30 dana; `purgeOldActivities` briše aktivnosti starije od 365 dana.

## Sigurnost i privatnost

- Auth token je obvezan; produkcijske callable funkcije provode App Check (Play Integrity), dok ga Emulator Suite namjerno isključuje.
- Pozivni kod koristi 16 kriptografski nasumičnih znakova iz skupa bez dvosmislenih znakova (80 bita entropije), pohranjen je samo kao SHA-256, jednokratan i vremenski ograničen.
- Storage put je `pantries/{pantryId}/products/{productId}/main.jpg`; pravila provjeravaju aktivno članstvo, postojanje aktivnog artikla, točan put/metapodatak, JPEG MIME i najviše 5 MiB. Klijent u Room sprema privatni `gs://` URI i sliku dohvaća autoriziranim Storage SDK-om, bez trajnog bearer download tokena.
- Crashlytics bilježi samo šifru pogreške, sloj, verziju aplikacije i nasumični installation ID. U produkciji se `setUserId` ne poziva i nema poslovnih polja u porukama iznimke.
- Izvozi se stvaraju lokalno preko Storage Access Frameworka i nisu slani poslužitelju.

## Ažuriranja

GitHub release sadrži potpisani APK i `release-manifest.json`. Manifest sadrži `versionCode`, `versionName`, `minSupportedVersionCode`, `forceUpdate`, `sha256`, `apkUrl` i `releaseNotes`. Aplikacija koristi javni HTTPS API, ograničava hostove, provjerava hash, zatim certifikat APK-a naspram fingerprinta ugrađenog tijekom release builda prije standardnog `ACTION_VIEW` Package Installer tijeka preko `FileProvider`a.
