# Besplatno prepoznavanje fotografija

1. U Google AI Studiju stvoriti zaseban projekt, npr. `Smocnica Free`, bez povezivanja billing računa. Provjeriti oznaku **Free tier** uz ključ. Ne koristiti ključ postojećeg plaćenog Firebase projekta.
2. Stvoriti Gemini API ključ. Ne slati ga u chat, Git, APK ili issue. Spremiti ga izravno u Firebase Secret Manager kao `GEMINI_API_KEY` u projektu `smocnica-aplikacija`, preko sigurnog lokalnog unosa ili Cloud konzole.
3. Objaviti funkcije i provjeriti `recognizeProductPhoto` prije Android izdanja. Za emulator je dopušten ignorirani `functions/.secret.local` s istom varijablom; nikada ne ispisivati sadržaj.
4. Provjeriti stvarnu kvotu za `gemini-3.6-flash` u AI Studiju. Besplatni limiti mogu se promijeniti i nisu zajamčeni. Aplikacija pri 429 prestaje s pokušajem i nudi ručni unos; nema automatskog prelaska na naplatu.
5. Provjeriti najmanje deset stvarnih ambalaža: generički naziv, pakiranje i proizvođača, nečitljivu sliku, prekid veze, iscrpljenu kvotu, ispravak prijedloga, grupiranje i ponovno skeniranje spremljenog barkoda.

Besplatan Gemini odnosi se na AI zahtjeve; postojeći Firebase hosting, funkcije i pohrana podliježu svojim kvotama i cjeniku. Izdanje se ne smatra dostupnim korisniku prije provjerenog potpisanog APK-a na GitHub Releaseu.

Dokumentacija: https://ai.google.dev/gemini-api/docs/billing i https://ai.google.dev/gemini-api/docs/rate-limits
