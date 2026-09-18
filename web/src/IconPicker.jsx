import React, { useState } from "react";
import { productIcons, suggestIcon, iconDataUrl, findIcons } from "./product-icons";
export default function IconPicker({ name, value, onChange, existing = false }) {
  const selected = value === "auto" ? suggestIcon(name) : value;
  const icon = productIcons.find((item) => item.id === selected);
  const [browsing, setBrowsing] = useState(false);
  const [query, setQuery] = useState("");
  const results = findIcons(query);
  return <section className="icon-picker">
    <label className="field"><span>Prikaz artikla</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="auto">Ikonica prema nazivu (automatski)</option>
        {productIcons.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        <option value="photo">{existing ? "Zadrži postojeću sliku / nova fotografija" : "Fotografija proizvoda"}</option>
      </select>
    </label>
    {icon && <div className="icon-preview"><img src={iconDataUrl(selected)} alt={`Ikonica: ${icon.name}`} /><div><strong>{icon.name}</strong><p>Ova ilustracija prikazivat će se umjesto fotografije. Možete odabrati drugu.</p></div></div>}
    <button type="button" aria-expanded={browsing} onClick={() => setBrowsing(!browsing)}>{browsing ? "Zatvori zbirku ikonica" : `Pregledaj i pretraži ikonice (${productIcons.length})`}</button>
    {browsing && <div className="icon-library">
      <label className="field"><span>Pretraži ikonice</span><input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Voda, jogurt, voće, začini…" /></label>
      <p className="muted small" role="status">{results.length ? `${results.length} ikonica` : "Nema podudaranja. Pokušajte s vrstom namirnice, npr. sok ili povrće."}</p>
      <div className="icon-grid">{results.map((item) => <button type="button" key={item.id} aria-label={`Odaberi ikonicu: ${item.name}`} aria-pressed={selected === item.id} onClick={() => { onChange(item.id); setBrowsing(false); }}><img loading="lazy" src={iconDataUrl(item.id)} alt=""/><span>{item.name}</span></button>)}</div>
    </div>}
    {value !== "photo" && <p className="muted small">Fotografija za AI služi samo za prepoznavanje; uz artikl se sprema odabrana ikonica.</p>}
  </section>;
}
