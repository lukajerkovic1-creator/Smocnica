// Shared vector shapes keep the extended catalogue small and crisp at icon size.
const dot = (x, y, r, color) => `<circle cx="${x}" cy="${y}" r="${r}" fill="${color}"/>`;
const leaf = '<path d="M50 31q-1-20 19-15-4 17-19 15" fill="#78a36c"/>';
const grain = '<g fill="#d8b67b"><ellipse cx="42" cy="51" rx="3" ry="7"/><ellipse cx="55" cy="53" rx="3" ry="7"/><ellipse cx="47" cy="65" rx="3" ry="7"/><ellipse cx="60" cy="66" rx="3" ry="7"/></g>';
const drop = '<path d="M50 43q-22 26 0 29 22-3 0-29Z" fill="#5eafd3"/>';
const fish = '<path d="M35 60q15-18 30 0-15 18-30 0l-10 9V51Z" fill="#d7e7ec"/><circle cx="57" cy="57" r="2" fill="#536477"/>';
const bottle = (color, label = drop) => `<rect x="41" y="10" width="18" height="10" rx="3" fill="#76a5bd"/><path d="M41 20v12L29 45v36q0 7 7 7h28q7 0 7-7V45L59 32V20Z" fill="${color}"/><path d="M30 47h40v29H30Z" fill="#fffaf0"/>${label}`;
const jar = (color, detail) => `<rect x="28" y="18" width="44" height="12" rx="3" fill="#acb58d"/><rect x="25" y="30" width="50" height="55" rx="9" fill="${color}"/><rect x="28" y="42" width="44" height="31" rx="4" fill="#fff7de"/>${detail}`;
const bag = (color, detail) => `<path d="m28 18 44 0-4 15 9 52H23l9-52Z" fill="${color}"/><path d="M28 26h44M25 79h50" fill="none" opacity=".3"/>${detail}`;
const box = (color, detail) => `<path d="m24 22 43-7 10 13v57H24Z" fill="${color}"/><path d="m24 22 10 11h43M34 33v52" fill="none" opacity=".3"/>${detail}`;
const cup = (color, detail) => `<path d="m25 32 50 0-7 51H32Z" fill="${color}"/><ellipse cx="50" cy="31" rx="28" ry="8" fill="#fff9ed"/>${detail}`;
const can = (color, detail) => `<rect x="24" y="27" width="52" height="55" rx="7" fill="${color}"/><ellipse cx="50" cy="28" rx="26" ry="8" fill="#dae2df"/><ellipse cx="50" cy="28" rx="8" ry="3" fill="none"/>${detail}`;
const plate = (detail) => `<ellipse cx="50" cy="71" rx="36" ry="16" fill="#dae6e8"/>${detail}`;
const fruit = (color, detail = "") => `${leaf}<path d="M50 37C22 20 12 48 24 71c10 19 19 9 26 10 7-1 17 9 27-10C90 47 75 21 50 37Z" fill="${color}"/>${detail}`;
const round = (color, detail = "") => `${leaf}${dot(50, 58, 27, color)}${detail}`;
const label = (value, color = "#79644a") => `<text x="50" y="64" text-anchor="middle" font-family="Arial,sans-serif" font-size="16" font-weight="bold" fill="${color}" stroke="none">${value}</text>`;
const bean = (color) => `<path d="M38 41c-20 5-20 32-1 36 17 5 31-5 29-18-2-10-13-3-17-7-4-4 0-15-11-11Z" fill="${color}"/>`;
const flakes = '<path d="m35 45 10-6 5 11-12 5Zm20 10 12-6 2 13-12 5Zm-20 9 10-6 5 12-12 3Z" fill="#e7bd62"/>';
const noodles = '<path d="M37 44q25 0 3 10-15 10 21 8m-18-19q25 0 3 10-15 10 21 8" fill="none" stroke="#d4a64e" stroke-width="4"/>';
const entries = [
  // Specific compound names precede ingredients: e.g. coconut milk is not a coconut.
  ["plant-milk", "Biljni napitci", "Pića", /(?:zoben|sojin|bademov|riz(in|in)|kokosov).*(?:napit|mlijek)|(?:napit|mlijek).*(?:zob|soj|badem|kokos)/, bottle("#dce1bc", leaf)],
  ["sparkling-water", "Mineralna i gazirana voda", "Pića", /mineraln|\bgaziran.*vod|radenska|jamnica|donat\b/, bottle("#cce4d9", dot(44,52,4,"#a5cee3")+dot(57,59,5,"#a5cee3")+dot(45,69,3,"#a5cee3"))],
  ["water", "Voda za piće", "Pića", /\bvod[aeu]?\b|negaziran|\bjana\b|\bwater\b/, bottle("#c4e5f1")],
  ["juice", "Voćni sok i nektar", "Pića", /\bsok\b|sokovi|nektar|juice|cedevita/, box("#e8b56f", dot(53,59,15,"#f3cf77")+'<path d="m52 46 10-5" stroke="#80a26a" stroke-width="4"/>')],
  ["soda", "Gazirani sokovi", "Pića", /cola|kola\b|fanta|sprite|pepsi|tonic|tonik|gaziran.*(sok|pic)/, can("#d88b7d", '<path d="m53 40-15 19h12l-4 16 18-22H51Z" fill="#fff4d5"/>')],
  ["energy", "Energetska i sportska pića", "Pića", /energetsk|energy|izoton|red bull|monster/, can("#aeb8d5", '<path d="m53 40-15 19h12l-4 16 18-22H51Z" fill="#e8d174"/>')],
  ["beer", "Pivo", "Pića", /pivo|piv[ae]|beer|lager/, bottle("#b58c49", label("0.5"))],
  ["wine", "Vino", "Pića", /\bvino\b|\bvina\b|wine|prosek|sampanj/, bottle("#947a96", dot(46,53,5,"#957394")+dot(55,53,5,"#957394")+dot(50,63,5,"#957394"))],
  ["spirits", "Žestoka pića", "Pića", /rakij|vodk|viski|whisk|liker|\brum\b|\bgin\b/, bottle("#d5b782", label("★"))],
  ["tea", "Čaj", "Pića", /\bcaj|\btea\b|kamilic/, box("#b4c7a0", '<path d="M43 42h18v28H43Z" fill="#fffbdf"/><path d="m52 42 5-10" fill="none"/>'+leaf)],
  ["cocoa", "Kakao i čokolada u prahu", "Pečenje i slastice", /kakao|cocoa|cokolad.*prah/, can("#bd907b", bean("#825b42"))],
  ["yogurt", "Jogurt i kefir", "Mliječno i jaja", /jogurt|yogurt|kefir|acidofil/, cup("#a0c5d6", drop)],
  ["cream", "Vrhnje", "Mliječno i jaja", /vrhnj|slatko vrhnje|sour cream/, cup("#d9c3db", '<path d="M35 65q15-27 30 0-15 10-30 0" fill="#fffaf0"/>')],
  ["butter", "Maslac i margarin", "Mliječno i jaja", /maslac|margarin|butter/, plate('<path d="m27 48 36-10 12 12v22H27Z" fill="#f0d386"/><path d="m27 48 35 5 13-3M62 53v20" fill="none"/>')],
  ["eggs", "Jaja", "Mliječno i jaja", /\bjaj|\beggs?\b/, plate('<ellipse cx="38" cy="53" rx="14" ry="22" fill="#e5c7a5"/><ellipse cx="62" cy="56" rx="14" ry="22" fill="#fff4d8"/>')],
  ["fresh-cheese", "Svježi i krem sir", "Mliječno i jaja", /(?:svjez|krem|posn|cottage).*sir|mascarpone|ricotta|mozzarella/, cup("#b4cdb1", '<ellipse cx="50" cy="55" rx="16" ry="10" fill="#fffaf0"/>')],
  ["ice-cream", "Sladoled", "Smrznuto i gotova jela", /sladoled|ice cream/, '<path d="m33 47 34 0-17 40Z" fill="#d9b680"/><path d="m40 60 19 12m-1-12-14 12" fill="none"/>'+dot(50,37,21,"#eac2bc")],
  ["frozen-veg", "Smrznuto povrće", "Smrznuto i gotova jela", /(?:smrzn|zamrzn).*(povrc|mjesavin|grasak|brokul)/, bag("#b6d4cf", '<path d="M50 40v31M37 47l26 17M37 64l26-17" stroke="#f8ffff" stroke-width="5"/>')],
  ["frozen-fruit", "Smrznuto voće", "Smrznuto i gotova jela", /(?:smrzn|zamrzn).*(voc|jagod|malin|bob)/, bag("#c8b3d0", '<path d="M50 40v31M37 47l26 17M37 64l26-17" stroke="#fff7ff" stroke-width="5"/>')],
  ["pizza", "Pizza", "Smrznuto i gotova jela", /pizza|pizz[ae]/, '<path d="m21 30 59 0-28 55Z" fill="#edd38a"/><path d="M21 30q30-13 59 0" stroke="#bc8e59" stroke-width="9"/>'+dot(42,43,6,"#c87865")+dot(59,44,6,"#c87865")+dot(50,61,6,"#c87865")],
  ["dumplings", "Njoki i okruglice", "Smrznuto i gotova jela", /njok|okruglic|knedl|raviol|tortell/, plate('<g fill="#e4cd9c"><ellipse cx="33" cy="61" rx="11" ry="7"/><ellipse cx="51" cy="51" rx="11" ry="7"/><ellipse cx="65" cy="64" rx="11" ry="7"/></g>')],
  ["soup", "Juhe i temeljci", "Smrznuto i gotova jela", /juha|juhe|temeljac|temeljci|bujon|soup/, '<path d="M17 49h66q-3 34-33 34T17 49" fill="#bbced2"/><ellipse cx="50" cy="49" rx="33" ry="9" fill="#dfb46a"/><path d="M36 36q-7-7 0-15m14 15q-7-7 0-15m14 15q-7-7 0-15" fill="none"/>'],
  ["tomato-sauce", "Pasirana rajčica i kečap", "Umaci i konzervirano", /pasiran.*rajc|pasat|passata|kecap|ketchup|koncentrat.*rajc|umak.*rajc/, jar("#bd705b", dot(50,57,13,"#d9876c")+'<path d="m42 46 8 4 8-4" stroke="#83a373" stroke-width="4"/>')],
  ["mayo", "Majoneza", "Umaci i konzervirano", /majonez|mayonnaise/, jar("#e5d8ab", label("M"))],
  ["mustard", "Senf", "Umaci i konzervirano", /senf|mustard/, jar("#d3b46c", label("S"))],
  ["pesto", "Pesto", "Umaci i konzervirano", /pesto/, jar("#97a878", leaf)],
  ["soy-sauce", "Sojin i azijski umaci", "Umaci i konzervirano", /soja.*umak|sojin.*umak|soy sauce|teriyaki|worcester|tabasco/, bottle("#917164", label("S"))],
  ["ajvar", "Ajvar i namazi od povrća", "Umaci i konzervirano", /ajvar|pindur|ljutenic|namaz.*povrc/, jar("#c77850", '<path d="M39 50q20-14 23 6-3 17-20 10-9-6-3-16" fill="#d98964"/>')],
  ["honey", "Med", "Namazi i doručak", /\bmed\b|\bmeda\b|honey/, jar("#d9b363", '<path d="m50 46 11 6v12l-11 6-11-6V52Z" fill="#efcc74"/>')],
  ["jam", "Pekmez i džem", "Namazi i doručak", /pekmez|dzem|marmelad|\bjam\b/, jar("#b97d92", dot(44,57,7,"#b6778a")+dot(56,59,8,"#b6778a"))],
  ["nut-spread", "Čokoladni i orašasti namazi", "Namazi i doručak", /nutella|eurokrem|linolada|(?:cokolad|ljesnjak|kikiriki|badem).*namaz|namaz.*(?:cokolad|ljesnjak|kikiriki)|maslac.*kikirik/, jar("#a68064", bean("#986c4d"))],
  ["cereal", "Pahuljice, müsli i granola", "Namazi i doručak", /pahulj|musli|muesli|granola|corn.?flakes|cokolin/, box("#d8ba7f", flakes)],
  ["oats", "Zob", "Žitarice i mahunarke", /\bzob|oats/, bag("#c3caa4", grain)],
  ["cornmeal", "Palenta i kukuruzna krupica", "Žitarice i mahunarke", /palenta|krupic|kukuruzn.*brasn/, bag("#e6c36a", grain)],
  ["couscous", "Kus-kus, bulgur i kvinoja", "Žitarice i mahunarke", /kus.?kus|couscous|bulgur|kvinoj|quinoa|heljd|jecam|proso/, bag("#d4b49d", dot(42,51,4,"#f4e7bf")+dot(55,49,4,"#f4e7bf")+dot(48,63,4,"#f4e7bf")+dot(61,63,4,"#f4e7bf"))],
  ["beans", "Grah", "Žitarice i mahunarke", /\bgrah|beans/, bag("#b9baa0", bean("#a87669"))],
  ["lentils", "Leća", "Žitarice i mahunarke", /\bleca\b|\blece\b|lentil/, bag("#d7b195", dot(41,52,6,"#b2a371")+dot(57,53,6,"#b2a371")+dot(49,67,6,"#b2a371"))],
  ["chickpeas", "Slanutak i humus", "Žitarice i mahunarke", /slanut|humus|hummus|chickpea/, can("#d3c6a4", dot(41,53,7,"#e8d6a0")+dot(58,54,7,"#e8d6a0")+dot(50,68,7,"#e8d6a0"))],
  ["peas", "Grašak", "Povrće", /grasak|graska/, '<path d="M19 65q21-55 62-21-20 48-62 21Z" fill="#8db36f"/>'+dot(35,59,8,"#c1d19b")+dot(49,53,8,"#c1d19b")+dot(64,49,8,"#c1d19b")],
  ["corn", "Kukuruz", "Povrće", /kukuruz|sweetcorn/, '<ellipse cx="50" cy="49" rx="17" ry="29" fill="#e9c26b"/><path d="M39 26v45m11-48v49m11-46v46M35 38h30M34 49h32M35 60h30" stroke="#ba9758"/><path d="M50 83q-35-12-28-37 13 7 28 37 16-32 28-37 8 25-28 37" fill="#8eaa75"/>'],
  ["nuts", "Orašasti plodovi", "Grickalice i sjemenke", /orah|orasi|badem|ljesnjak|pistaci|indijsk.*orasc|nuts/, bag("#c5ab8d", '<ellipse cx="43" cy="55" rx="9" ry="15" fill="#ae855b"/><path d="m42 42 2 25" fill="none"/><ellipse cx="59" cy="62" rx="8" ry="12" fill="#c59b68"/>')],
  ["peanuts", "Kikiriki", "Grickalice i sjemenke", /kikirik|peanut/, bag("#d7c398", '<path d="M45 39q17-1 11 16 16 20-3 21-12 0-10-16-16-17 2-21" fill="#bda178"/>')],
  ["seeds", "Sjemenke", "Grickalice i sjemenke", /sjemenk|sezam|chia|lanen|bundevin/, bag("#c4d0b5", grain)],
  ["chips", "Čips", "Grickalice i sjemenke", /cips|chips|pringles/, bag("#dca58f", flakes)],
  ["crackers", "Krekeri, štapići i pereci", "Grickalice i sjemenke", /kreker|stapic|perec|pretzel|smoki|flips/, bag("#deb881", '<path d="m38 44 5 26m9-27 3 26m8-26 2 24" stroke="#ba8954" stroke-width="6"/>')],
  ["popcorn", "Kokice", "Grickalice i sjemenke", /kokic|popcorn/, box("#d3a195", dot(43,51,8,"#fff1c7")+dot(59,52,8,"#fff1c7")+dot(50,66,8,"#fff1c7"))],
  ["dried-fruit", "Suho voće", "Grickalice i sjemenke", /suhe?\b.*(?:voc|sljiv|smokv|marelic)|grozdic|datulj|brusnic/, bag("#c7a4b7", dot(42,53,7,"#967383")+dot(58,52,7,"#967383")+dot(51,67,7,"#967383"))],
  ["sugar", "Šećer", "Pečenje i slastice", /secer|sugar/, bag("#d6c7d8", '<path d="m35 50 14-6 10 8-14 6Z" fill="#fff"/><path d="m35 50 10 8v13l-10-7Zm10 8 14-6v13l-14 6Z" fill="#eee6e5"/>')],
  ["salt", "Sol", "Začini", /\bsol\b|\bsoli\b|\bsalt\b/, jar("#dddcd0", label("SOL"))],
  ["pepper", "Papar", "Začini", /papar|papra|pepper/, jar("#b6a78f", dot(42,54,4,"#716453")+dot(57,54,4,"#716453")+dot(49,65,4,"#716453"))],
  ["spices", "Začini i sušeno bilje", "Začini", /zacin|vegeta|cimet|kurkum|curry|origano|bosilj|persin|lovor|ruzmarin|timijan|cesnjak.*prah|paprik.*(?:mljeven|prah)|mljeven.*paprik/, jar("#c2b992", leaf)],
  ["vinegar", "Ocat", "Začini", /ocat|octa|vinegar|aceto/, bottle("#c5ab8e", label("O"))],
  ["yeast", "Kvasac", "Pečenje i slastice", /kvasac|kvasca|yeast/, box("#b2c8b4", label("K"))],
  ["baking", "Prašak za pecivo i soda", "Pečenje i slastice", /prasak.*peciv|soda.*bikarbon|baking/, bag("#c9c4dc", label("P"))],
  ["vanilla", "Vanilija i arome", "Pečenje i slastice", /vanil|arom|ekstrakt/, bag("#e1c98f", '<path d="m40 43 17 29m-9-31 15 25" stroke="#896b50" stroke-width="4"/>')],
  ["pudding", "Puding i želatina", "Pečenje i slastice", /puding|pudding|zelatin|gustin|skrob/, plate('<path d="M29 71h43L65 43H36Z" fill="#e6c78d"/><ellipse cx="51" cy="43" rx="15" ry="5" fill="#bd8a67"/>')],
  ["candy", "Bomboni i žvakaće gume", "Pečenje i slastice", /bombon|zvakac|gumeni|candy/, '<path d="m33 47-17-9v33l17-8m34-16 17-9v33l-17-8" fill="#beaed5"/><rect x="30" y="39" width="40" height="34" rx="10" fill="#d697a6"/><path d="m42 41 15 30" stroke="#f5dce2" stroke-width="7"/>'],
  ["cake", "Kolači i torte", "Pečenje i slastice", /kolac|torta|torte|muffin|biskvit/, plate('<path d="m23 69 5-33 43 8 7 25Z" fill="#c8a089"/><path d="m28 36 43 8-24 15-24 10Z" fill="#eed6b1"/><path d="m25 55 25 6 23-12" fill="none" stroke="#f0d6c7" stroke-width="5"/>')],
  ["banana", "Banane", "Voće", /banan/, '<path d="M28 25q0 43 46 35-18 42-45 13-19-22-1-48Z" fill="#edcf73"/><path d="M29 35q-4 41 37 35" fill="none"/><path d="m28 25 4-9" stroke-width="6"/>'],
  ["citrus", "Naranče i mandarine", "Voće", /naranc|mandarin|klementin|grejp/, round("#e8b568", '<path d="M38 43q-10 8-6 19" stroke="#f6dba0" stroke-width="4" fill="none"/>')],
  ["lemon", "Limun i limeta", "Voće", /limun|limet/, '<path d="M24 48q17-30 43-11l11 10-3 12q-18 30-44 11l-11-9Z" fill="#e7d477"/><path d="m32 48 12-8" stroke="#fff1b8" stroke-width="4"/>'],
  ["pear", "Kruške", "Voće", /krusk/, leaf+'<path d="M38 35q12-14 24 0 0 15 15 25 12 28-26 26-39 0-29-25 15-10 16-26Z" fill="#c7ce85"/>'],
  ["berries", "Jagode i maline", "Voće", /jagod|malin|kupin/, '<path d="M23 42q27-22 54 0-5 29-27 43-21-15-27-43Z" fill="#d48a89"/><path d="m31 30 19 6 19-6-11 16-8-8-8 8Z" fill="#8ca87b"/>'+dot(38,52,2,"#f2d5a1")+dot(59,53,2,"#f2d5a1")+dot(48,67,2,"#f2d5a1")],
  ["grapes", "Grožđe i borovnice", "Voće", /grozd|borovnic/, leaf+dot(38,43,11,"#a08cab")+dot(61,43,11,"#a08cab")+dot(32,61,11,"#a08cab")+dot(53,61,11,"#a08cab")+dot(44,78,11,"#a08cab")],
  ["stone-fruit", "Breskve, marelice i šljive", "Voće", /breskv|nektarin|marelic|sljiv/, round("#d8a08c", '<path d="M51 35q-12 22 0 46" fill="none"/>')],
  ["cherries", "Trešnje i višnje", "Voće", /tresnj|visnj/, '<path d="M34 61q15-14 17-42 5 26 16 36" fill="none" stroke="#7d966c" stroke-width="3"/>'+dot(33,67,14,"#c37e86")+dot(67,63,14,"#b8727d")],
  ["melon", "Lubenica i dinja", "Voće", /lubenic|dinj/, '<path d="M16 38h68q-7 46-34 46T16 38" fill="#86ac7b"/><path d="M24 42h52q-8 32-26 32T24 42" fill="#d89690"/>'+dot(39,52,2,"#775e53")+dot(61,52,2,"#775e53")+dot(50,63,2,"#775e53")],
  ["tropical", "Ananas i mango", "Voće", /ananas|mango|papaja/, '<path d="m50 36-21-23 20 9 13-15-2 22 15-5-11 16" fill="#85a777"/><ellipse cx="50" cy="60" rx="24" ry="27" fill="#dbb976"/><path d="m33 44 31 31m-39-20 33 30m8-38-32 31m22-42-31 30" stroke="#b19361"/>'],
  ["avocado", "Avokado", "Voće", /avokad|avocado/, '<path d="M38 27q14-15 24 3l17 34q1 27-28 24-33 1-30-22Z" fill="#91aa76"/><path d="M41 35q10-15 19 4l11 25q1 17-20 17-22 0-22-15Z" fill="#e1dca1"/>'+dot(50,62,13,"#aa7c59")],
  ["coconut", "Kokos", "Voće", /kokos/, round("#a98668", '<ellipse cx="53" cy="57" rx="20" ry="22" fill="#fffaf0"/><ellipse cx="53" cy="57" rx="10" ry="12" fill="#e1dacf"/>')],
  ["tomato", "Rajčica", "Povrće", /rajc|paradaj/, round("#d9937e", '<path d="m32 35 18 5 18-5-11 14-7-8-8 8Z" fill="#84a779"/>')],
  ["pepper-veg", "Paprika", "Povrće", /paprik/, '<path d="M50 35c-32-14-37 22-23 45 8 9 17 3 23 1 9 9 25 4 28-16 7-31-12-39-28-30Z" fill="#cf9276"/><path d="M50 37q-9-20 10-22M49 42v33" fill="none" stroke="#829c6f" stroke-width="4"/>'],
  ["cucumber", "Krastavci i tikvice", "Povrće", /krastav|tikvic/, '<path d="M29 78q-15-6-3-22l32-34q16-11 22 5 3 12-14 27L42 79q-6 6-13-1Z" fill="#95b080"/><path d="m33 67 30-33" fill="none" stroke="#cbd7aa" stroke-width="5"/>'],
  ["potato", "Krumpir i batat", "Povrće", /krump|batat/, '<ellipse cx="50" cy="55" rx="31" ry="23" transform="rotate(-25 50 55)" fill="#c5ab86"/>'+dot(36,56,2,"#947b5e")+dot(52,45,2,"#947b5e")+dot(62,62,2,"#947b5e")],
  ["onion", "Luk", "Povrće", /\bluk\b|\bluka\b|onion/, '<path d="M48 26q-5 11-19 20-24 35 22 40 41-3 21-39-20-14-18-21Z" fill="#dab996"/><path d="M48 37q-20 26 2 46m5-44q17 21 0 42m-7-55-3-12m9 12 5-12" fill="none"/>'],
  ["garlic", "Češnjak", "Povrće", /cesnjak|garlic/, '<path d="M44 25h13v16q33 10 20 34-10 20-43 7-27-23 10-41Z" fill="#eee0cb"/><path d="M47 42q-21 19-6 39m10-37q13 18 3 40" fill="none"/>'],
  ["mushrooms", "Gljive", "Povrće", /gljiv|sampinjon|vrganj/, '<path d="M42 51h18l7 33H36Z" fill="#e5d4bf"/><path d="M16 53q3-40 35-34 31-1 34 34-29 17-69 0Z" fill="#b69a82"/>'+dot(40,36,5,"#e1cbb1")+dot(60,42,6,"#e1cbb1")],
  ["greens", "Salata, špinat i kupus", "Povrće", /salat|spinat|blitv|kupus|kelj|rukol/, '<path d="M48 85Q10 68 24 43q-3-24 19-19 18-19 26 5 23-2 14 23 6 31-35 33Z" fill="#9bb77e"/><path d="M49 82V35m0 26-18-12m18 23 21-17" fill="none" stroke="#d0d8a9" stroke-width="3"/>'],
  ["broccoli", "Brokula i cvjetača", "Povrće", /brokul|cvjetac|karfiol/, '<path d="m42 42 18 0-4 43H43Z" fill="#bbcca2"/>'+dot(30,44,16,"#8da77f")+dot(50,30,18,"#8da77f")+dot(70,44,16,"#8da77f")],
  ["chicken", "Piletina i puretina", "Meso i riba", /pilet|puret|pilec|purec|chicken/, plate('<path d="m54 59 14 17 8-4-14-20" fill="#eee1c9"/><path d="M26 59q-7-23 16-24 24-3 22 18-8 24-28 16Z" fill="#cfad92"/>')],
  ["sausages", "Kobasice i hrenovke", "Meso i riba", /kobasic|hrenov|sausage/, plate('<path d="M29 45q-3 21 38 17" fill="none" stroke="#bd8a75" stroke-width="17"/><path d="m26 39 8 10m24 5 8 11" stroke="#edd0ad" stroke-width="2"/>')],
  ["ham", "Šunka, salama i slanina", "Meso i riba", /sunk|salam|slanin|prsut|pancet|bacon/, plate('<path d="m26 41 49 7-7 29-48-7Z" fill="#d19992"/><path d="m25 50 47 7m-50 5 48 7" stroke="#f0d8c5" stroke-width="5"/>')],
  ["fresh-fish", "Svježa i smrznuta riba", "Meso i riba", /losos|oslic|orada|brancin|pastrv|saran|sku[sš]|svjez.*rib|smrzn.*rib/, plate(fish)],
  ["seafood", "Plodovi mora", "Meso i riba", /kozic|skamp|lignj|dagnj|hobotnic|rakov/, plate('<path d="M69 43q-40-22-41 13 3 26 29 10l-8-9q-12 8-11-4 9-12 22-2Z" fill="#d6a18e"/><path d="m66 43 12-12m-8 17 13-7" fill="none"/>')],
  ["tofu", "Tofu i zamjene za meso", "Meso i riba", /tofu|seitan|tempeh|vegansk.*(?:burger|meso)/, plate('<path d="m27 45 32-10 15 14v24H27Z" fill="#eee2c9"/><path d="m27 45 18 10 29-6M45 55v18" fill="none"/>')],
  ["tortilla", "Tortilje i dvopek", "Kruh i peciva", /tortil|wrap|dvopek|prezle|krusn.*mrv/, plate('<ellipse cx="50" cy="59" rx="29" ry="20" fill="#e2c89d"/>'+dot(36,57,2,"#b59465")+dot(51,48,2,"#b59465")+dot(64,59,2,"#b59465")+dot(49,69,2,"#b59465"))],
  ["croissant", "Kroasani", "Kruh i peciva", /kroasan|croissant/, '<path d="M20 70q-16-25 13-28 16-25 34 0 29 4 13 28-5-17-16-10-15 10-28 0-11-7-16 10Z" fill="#d9b47d"/><path d="m35 41 5 19m22-19-5 19" fill="none"/>'],
  ["noodles", "Rezanci i instant tjestenina", "Žitarice i mahunarke", /rezanc|noodl|ramen/, cup("#d8b09a", noodles)],
  ["baby-food", "Dječje kašice", "Ostalo", /kasica|kasice|djecj.*hran|baby food/, jar("#d6caa5", dot(50,58,13,"#e9d4a8")+dot(45,56,1.5,"#7d6a55")+dot(55,56,1.5,"#7d6a55")+'<path d="M45 63q5 5 10 0" fill="none"/>')],
];
export const extraFoodIcons = entries.map(([id, name, category, pattern, art]) => ({ id, name, category, pattern, art }));
