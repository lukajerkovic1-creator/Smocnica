# Brisanje korisničkog računa

Javna procedura: https://lukajerkovic1-creator.github.io/Smocnica/delete-account.html

## U aplikaciji

1. Prijaviti se.
2. Otvoriti `Izbornik`.
3. Odabrati `Izbriši korisnički račun`.
4. Pročitati posljedice i potvrditi.

## Bez pristupa aplikaciji

Zahtjev poslati s Google računa korištenog za prijavu na `luka.jerkovic1@gmail.com`, s naslovom `Smočnica - zahtjev za brisanje računa`. Ne slati lozinke, fotografije ni izvezene sigurnosne kopije. Prije brisanja traži se razmjerna potvrda identiteta.

## Poslužiteljski učinak

- svi uređaji odmah se deaktiviraju i uklanjaju se FCM tokeni;
- korisnik se uklanja iz svih članstava i briše se `userPantryAccess/{uid}`;
- ako je vlasnik, vlasništvo se prenosi najranije pridruženom aktivnom članu;
- ako drugih aktivnih članova nema, smočnica se označava za trajno brisanje nakon 30 dana;
- zajedničke aktivnosti, operacije, inventure i podaci o pozivatelju anonimiziraju se;
- `users/{uid}` sa svim uređajima i Firebase Authentication račun trajno se brišu.
