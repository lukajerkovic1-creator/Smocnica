import React from "react";
import { productIcons, suggestIcon, iconDataUrl } from "./product-icons";
export default function IconPicker({ name, value, onChange, existing = false }) {
  const selected = value === "auto" ? suggestIcon(name) : value;
  const icon = productIcons.find((item) => item.id === selected);
  return <section className="icon-picker">
    <label className="field"><span>Prikaz artikla</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="auto">Ikonica prema nazivu (automatski)</option>
        {productIcons.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        <option value="photo">{existing ? "Zadrži postojeću sliku / nova fotografija" : "Fotografija proizvoda"}</option>
      </select>
    </label>
    {icon && <div className="icon-preview"><img src={iconDataUrl(selected)} alt={`Ikonica: ${icon.name}`} /><div><strong>{icon.name}</strong><p>Ova ilustracija prikazivat će se umjesto fotografije. Možete odabrati drugu.</p></div></div>}
    {value !== "photo" && <p className="muted small">Fotografija za AI služi samo za prepoznavanje; uz artikl se sprema odabrana ikonica.</p>}
  </section>;
}
