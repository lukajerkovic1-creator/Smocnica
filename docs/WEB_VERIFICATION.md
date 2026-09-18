# Provjera web-aplikacije, 18. rujna 2026.

Objavljena aplikacija: https://lukajerkovic1-creator.github.io/Smocnica/

## Izvršeno

- 15 web-testova prolazi: količine i minimumi, grupiranje i zajednička pravila, izvoz i kontrolni sažetak, nadogradnja starog izvoza, zaštita CSV-a, trajni outbox, ponavljanje istog zahtjeva, sukobi i odvajanje računa.
- 85 Firebase emulator testova prolazi, uključujući Web Push registraciju, odbijanje neautoriziranog pristupa i nevaljanih odredišta. Firebase CI također prolazi.
- Produkcijski web-build prolazi. Vite upozorava na veličinu glavnog paketa; skener i QR generator izdvojeni su u odgođene pakete.
- Na izoliranim testnim podacima u pregledniku širine 393 px provjereni su popis artikala, promjena količine, ručni unos, otvaranje skenera, dodavanje stavke kupnje i označavanje kupljenog. Popis kupnje nema vodoravno prelijevanje (393/393 px).
- Javna stranica prikazuje prijavu i upute za početni zaslon. Produkcijski korisnički podaci nisu mijenjani tijekom UI provjere.

## Vizualna usporedba

Uspoređeni su koncept `docs/design/iphone-web-reference.png` i snimke stvarnog prikaza: bijela podloga, ljubičaste akcije, prozračni redci s tankim razdjelnicima, pretraživanje i odabir police na vrhu, plutajući gumb za dodavanje te istaknuta Google prijava i upute za instalaciju. Testni artikli bez fotografija namjerno imaju zamjensku ikonu. Produkcijski status povezivanja dodan je radi jasnog prikaza sinkronizacije. Dodirne kontrole imaju najmanje 48 px; obrazac se pomiče okomito.

## Preostala provjera na uređaju

Stvarna Google prijava, podaci postojećeg računa, Safari instalacija, fizička kamera i dostava obavijesti zatvorenoj aplikaciji još nisu potvrđeni na korisnikovu iPhoneu. Testni adapter nije dokaz rada tih funkcija u produkciji.

Postojeći Android CI završio je pogreškom pri instalaciji SDK paketa `tools`, prije provjere Android koda (run 35326677438). Android/Kotlin nije mijenjan i novi APK nije objavljen. Pages objava i Firebase provjere prošle su neovisno o tome.
