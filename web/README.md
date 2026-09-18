# Smočnica za iPhone i web

Javna aplikacija: https://lukajerkovic1-creator.github.io/Smocnica/

Otvoriti u Safariju, odabrati **Dijeljenje → Dodaj na početni zaslon**, otvoriti ikonu Smočnica i prijaviti se postojećim Google računom. Obavijesti uključiti u **Postavke → Obavijesti**. Kamera se traži tek nakon dodira gumba za uključivanje kamere. Ručni unos ostaje dostupan.

## Razvoj i provjere

Node.js 22+, `npm ci`, `npm test`, `npm run dev`, `npm run build`. Lokalna adresa je `http://127.0.0.1:5173/Smocnica/`. `npm run build` stvara `dist/` s relativnim rutama koje rade na projektnoj GitHub Pages adresi. Pravna dokumentacija kopira se iz korijenskog `public/` da ostane jedan izvor istine.

Lokalni neversionirani `web/.env.local` i GitHub Pages workflow koriste:

| Lokalna varijabla | GitHub secret | Sadržaj |
| --- | --- | --- |
| VITE_FIREBASE_CONFIG | WEB_FIREBASE_CONFIG | Firebase Web SDK JSON konfiguracija |
| VITE_RECAPTCHA_SITE_KEY | WEB_RECAPTCHA_SITE_KEY | Javni reCAPTCHA Enterprise site key |
| VITE_VAPID_KEY | WEB_VAPID_KEY | Javni Web Push VAPID ključ |

Nikada ne stavljati servisni račun, privatni VAPID ključ ili administratorski token u varijablu koja počinje s `VITE_`. Sve `VITE_` vrijednosti vidljive su pregledniku. Stvarni pristup štite prijava, App Check, članstvo i backend, ne tajnost web-konfiguracije.

Firebase Web aplikacija pripada istom projektu kao Android. Authentication mora dopuštati domenu `lukajerkovic1-creator.github.io`. reCAPTCHA Enterprise/App Check registracija mora pokrivati tu domenu. Storage CORS dopušta GET s tog origin-a, uz nepromijenjena pravila pristupa. Za lokalno prijavljeno testiranje koristiti zaseban razvojni Firebase projekt ili emulatore; produkcijski App Check se ne isključuje radi testiranja.

`test/fixture.html` je izolirani prikaz za testiranje UI-ja s lokalnim izmišljenim podacima i izričito ograničenim adapterom. Nije dio produkcijskog builda i ne spaja se na stvarne korisničke podatke. Testovi domene i outboxa pokreću se kroz `npm test`; sigurnosni i transakcijski testovi kroz korijenski `functions` Emulator Suite.

## Objavljivanje

Workflow `.github/workflows/pages.yml` na izmjene u `web/` i `public/` provodi instalaciju, testove, build i objavu. Putanja `/Smocnica/` mora ostati usklađena u Vite konfiguraciji, manifestu i ikoni. Service worker obrađuje obavijesti i ne sprema privatne podatke u cache. Nova se verzija dobiva ponovnim otvaranjem/učitavanjem aplikacije.

Podrška Web Pushu na serveru koristi Secret Manager `WEB_PUSH_VAPID` s JSON objektom `publicKey` i `privateKey`. Privatni ključ ne smije se mijenjati bez plana za ponovno pretplaćivanje postojećih uređaja. `registerDevice`, `unregisterDevice` i `notifyLowStock` kompatibilni su s postojećim Android klijentom.

## Granice provjere

Povezivanje sa stvarnim Google računom, fizičko skeniranje, dopuštenja iPhonea i dostavu obavijesti zatvorenoj aplikaciji treba potvrditi na stvarnom iPhoneu. Izolirani UI testovi nisu dokaz takve provjere. Android APK nije mijenjan ni ponovno objavljivan ovim web-projektom.
