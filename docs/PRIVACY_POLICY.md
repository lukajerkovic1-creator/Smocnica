# Politika privatnosti

Javna, kanonska verzija objavljuje se na:
https://lukajerkovic1-creator.github.io/Smocnica/privacy-policy.html

Politika obuhvaća podatke računa i uređaja, sadržaj zajedničke smočnice, Firebase i Open Food Facts izvršitelje, svrhe obrade, međunarodne prijenose, sigurnost, rokove čuvanja, prava korisnika i kontakt `luka.jerkovic1@gmail.com`.

Korisnički račun briše se u aplikaciji putem `Izbornik → Izbriši korisnički račun`. Firebase Auth račun, `users/{uid}`, uređaji, FCM tokeni, `userPantryAccess/{uid}` i sva članstva brišu se odmah nakon uspješnog poslužiteljskog postupka. Zajednički revizijski zapisi anonimiziraju se. Ako je korisnik jedini član, smočnica se trajno uklanja nakon 30 dana; ako postoje drugi članovi, vlasništvo se prenosi aktivnom članu.

Vanjski postupak zahtjeva opisan je u [ACCOUNT_DELETION.md](ACCOUNT_DELETION.md).

Fotografiranje ili odabir slike u brzom unosu s prepoznavanjem šalje komprimiranu fotografiju Google Gemini API-ju radi prijedloga naziva, proizvođača i pakiranja. Prije snimanja prikazuje se obavijest o slanju. Rezultat korisnik provjerava prije spremanja. Aplikacija ne zapisuje slike, rezultate ni API ključ u dijagnostičke zapise; pohrana potvrđene fotografije uz artikl slijedi postojeća pravila zajedničke smočnice. Primjenjuju se važeći Google Gemini uvjeti obrade podataka, uključujući regionalne razlike.
