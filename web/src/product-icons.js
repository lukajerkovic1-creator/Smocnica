// Fixed vector artwork: no user text or external URLs enter the SVG markup.
import { extraFoodIcons } from "./food-icons.js";
const cookie = '<circle cx="45" cy="59" r="15" fill="#dda75d"/><circle cx="62" cy="66" r="16" fill="#bc783d"/><g fill="#563023" stroke="none"><circle cx="40" cy="55" r="3"/><circle cx="49" cy="64" r="3"/><circle cx="61" cy="58" r="3"/><circle cx="69" cy="70" r="3"/><circle cx="56" cy="72" r="3"/></g>';
const box = (color, detail) => `<path d="M22 25 70 18 80 30v55H22Z" fill="${color}"/><path d="m22 25 10 10h48M32 35v50" fill="none" opacity=".35"/>${detail}`;
const bag = (color, detail) => `<path d="m29 20 42 0-3 13 8 52H24l8-52Z" fill="${color}"/><path d="M29 27h42M26 79h48" fill="none" opacity=".35"/>${detail}`;
export const productIcons = [
  ...extraFoodIcons,
  { id: "cookies", name: "Čokoladni keksi i napolitanke", pattern: /napolit|keks|cookie|wafer|vafl/, art: box("#b95547", cookie) },
  { id: "chocolate", name: "Čokolada", pattern: /cokolad|cocoa|kakao/, art: '<rect x="28" y="18" width="44" height="68" rx="5" fill="#79452f"/><path d="M42 20v40m15-40v40M29 34h42M29 48h42" fill="none"/><path d="m24 54 52 0-4 33H28Z" fill="#a890dc"/><path d="m24 54 20 9 32-9" fill="#eee5ff"/>' },
  { id: "milk", name: "Mlijeko", pattern: /mlijek|milk|napitak.*(zob|soj)/, art: '<path d="m30 31 10-16h24l9 16v55H30Z" fill="#fffdf4"/><path d="M30 43h43v33H30Z" fill="#92cce5"/><path d="m40 15 2 16h31M42 31v55" fill="none"/><path d="M54 48q-15 18 0 20 15-2 0-20" fill="white"/>' },
  { id: "pasta", name: "Tjestenina", pattern: /tjesten|fusill|spaget|makaron|penne|pasta|lazanj/, art: bag("#80b4bd", '<rect x="34" y="39" width="32" height="32" rx="5" fill="#fff1b5"/><path d="m40 45 6 18m6-20 6 20m2-17 4 13" stroke="#d49b35" stroke-width="5"/>') },
  { id: "flour", name: "Brašno i žitarice", pattern: /brasn|griz|pahulj|zob|zitar/, art: bag("#f2dfb3", '<path d="M50 69V40m0 10-10-8m10 18-10-8m10-2 10-8m-10 18 10-8" fill="none" stroke="#ad7e37" stroke-width="4"/>') },
  { id: "rice", name: "Riža", pattern: /riza|rice/, art: bag("#a9c78f", '<ellipse cx="50" cy="57" rx="18" ry="19" fill="#fffbea"/><g fill="#e2cda2" stroke="none"><ellipse cx="44" cy="50" rx="3" ry="6" transform="rotate(-25 44 50)"/><ellipse cx="56" cy="53" rx="3" ry="6"/><ellipse cx="48" cy="65" rx="3" ry="6" transform="rotate(25 48 65)"/></g>') },
  { id: "oil", name: "Ulje i ocat", pattern: /ulje|ocat|oil/, art: '<rect x="43" y="14" width="16" height="12" rx="3" fill="#6f8450"/><path d="M43 26v12L32 49v34q20 7 40 0V49L59 38V26Z" fill="#e7c653"/><rect x="35" y="54" width="34" height="21" rx="3" fill="#fff9d8"/><path d="m45 69 15-10" stroke="#719154" stroke-width="5"/>' },
  { id: "coffee", name: "Kava i čaj", pattern: /kava|kav[eu]|caj|coffee|tea/, art: bag("#bd8d6c", '<ellipse cx="51" cy="56" rx="12" ry="17" transform="rotate(30 51 56)" fill="#714b37"/><path d="m55 43-8 26" stroke="#d7b99c" fill="none"/>') },
  { id: "jar", name: "Staklenka i umaci", pattern: /umak|rajc|paradaj|pekmez|dzem|med|namaz|ajvar|krastav|maslin/, art: '<rect x="29" y="17" width="44" height="12" rx="4" fill="#83a17b"/><rect x="25" y="29" width="52" height="56" rx="10" fill="#c66e4e"/><rect x="29" y="44" width="44" height="26" rx="4" fill="#fff0cb"/><circle cx="51" cy="58" r="9" fill="#de8f62"/>' },
  { id: "fruit", name: "Voće", pattern: /jabuk|banan|naranc|limun|voci|voce|krusk|breskv/, art: '<path d="M50 36c-30-23-38 18-23 40 10 15 18 5 23 6 5-1 15 9 25-7 16-25 0-54-25-39Z" fill="#dc7868"/><path d="M50 35V21m1 8q10-19 23-12-8 16-23 12" fill="#83ad74"/>' },
  { id: "vegetables", name: "Povrće", pattern: /povrc|mrkv|krump|luk|paprik|grasak|grah|leca|kukuruz/, art: '<path d="M34 36q15-14 28 5L34 84q-7 8-7-5Z" fill="#e7a15a"/><path d="m43 34-5-18m10 16 7-19m-2 23 18-9" stroke="#80a46c" stroke-width="6"/><path d="m36 48 12 5m-16 10 9 4" fill="none"/>' },
  { id: "bread", name: "Kruh i peciva", pattern: /kruh|peciv|tost|kroasan|bread/, art: '<path d="M28 46c-21-20 6-42 22-28 18-14 42 9 23 28v38H28Z" fill="#d4a267"/><path d="M36 44c-12-16 3-24 14-16 13-9 28 1 15 16v31H36Z" fill="#f5ddb0"/>' },
  { id: "dairy", name: "Sir i mliječni proizvodi", pattern: /sir|jogurt|vrhnj|maslac|kefir/, art: '<path d="m23 60 46-29 10 18v32H23Z" fill="#efcc65"/><path d="m23 60 56-11" fill="none"/><g fill="#d5a342" stroke="none"><circle cx="35" cy="71" r="4"/><circle cx="57" cy="67" r="5"/><circle cx="70" cy="74" r="3"/></g>' },
  { id: "fish", name: "Riba i konzerve", pattern: /riba|tun[ae]|sardin|losos|konzerv/, art: '<rect x="21" y="33" width="60" height="46" rx="10" fill="#9ebbc7"/><ellipse cx="51" cy="34" rx="30" ry="10" fill="#d6e4e9"/><path d="M33 60q15-20 29 0-15 18-29 0l-8 10V50Z" fill="#faf7e7"/><circle cx="54" cy="57" r="2" fill="#52697c"/>' },
  { id: "meat", name: "Meso i jaja", pattern: /meso|pilet|puret|goved|svinj|kobasic|salam|jaj/, art: '<rect x="18" y="27" width="66" height="58" rx="12" fill="#e3d7cc"/><path d="M29 66q-7-28 14-30 15-3 22 9 21 23-6 26-16-5-30-5" fill="#d9988e"/><ellipse cx="49" cy="53" rx="9" ry="6" fill="#fff4db"/>' },
  { id: "cleaning", name: "Sredstva za čišćenje", pattern: /deterdz|sapun|sampon|cisc|omeks|pranje/, art: '<path d="M38 20h31v9H54v13q20 6 20 25v18H28V63q0-14 15-21V29h-5Z" fill="#80babb"/><path d="M38 20h31v9H54" fill="#546e88"/><rect x="35" y="55" width="32" height="22" rx="5" fill="#e7f5ed"/>' },
  { id: "pantry", name: "Ostale namirnice", pattern: /./, art: box("#b6a4d7", '<rect x="39" y="45" width="31" height="23" rx="4" fill="#f5efff"/><path d="M46 56h17" stroke="#9a82bf" stroke-width="4"/>') },
];
export function suggestIcon(name = "") {
  const normalized = normalizeIconText(name);
  if (/maslac.*kikirik|kikirik.*maslac|(?:cokolad|ljesnjak|kikiriki|badem).*namaz/.test(normalized)) return "nut-spread";
  if (/napolit|keks|cookie|wafer|vafl/.test(normalized)) return "cookies";
  if (/cokolad.*mlijek|mlijek.*cokolad/.test(normalized)) return "milk";
  return productIcons.find((icon) => icon.pattern.test(normalized))?.id || "pantry";
}
export function normalizeIconText(value = "") {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase().trim();
}
export function findIcons(query = "") {
  const text = normalizeIconText(query);
  if (!text) return productIcons;
  const suggested = suggestIcon(text);
  return productIcons.filter((icon) =>
    normalizeIconText(`${icon.name} ${icon.category || ""}`).includes(text) ||
    (icon.id !== "pantry" && (icon.pattern.test(text) || icon.id === suggested)));
}
export function iconDataUrl(id) {
  const icon = productIcons.find((item) => item.id === id) || productIcons.at(-1);
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="20" fill="#faf7f2"/><ellipse cx="51" cy="87" rx="30" ry="4" fill="#e7e0d6"/><g stroke="#66594f" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round">${icon.art}</g></svg>`)}`;
}
export async function iconPhoto(id) {
  const img = new Image();
  img.src = iconDataUrl(id);
  await img.decode();
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 320;
  const context = canvas.getContext("2d");
  context.fillStyle = "#fff";
  context.fillRect(0, 0, 320, 320);
  context.drawImage(img, 0, 0, 320, 320);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.92));
  if (!blob) throw new Error("Ikonicu nije moguće pripremiti. Pokušajte ponovno.");
  return blob;
}
