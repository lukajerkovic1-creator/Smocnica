import React, { useRef, useState } from "react";
import { useApp, Photo } from "./ui";
import { active, sorted, packageDisplay } from "./domain";
import { entryShelf, recentEntries, stockDelta } from "./quick-entry";
import { errorText } from "./outbox";

export default function RecentProducts({ shelf }) {
  const { data, api, pantry } = useApp();
  const [busy, setBusy] = useState(false), [undo, setUndo] = useState(null), [error, setError] = useState("");
  const locked = useRef(false);
  const rows = recentEntries(data), shelves = sorted(active(data.shelves));
  async function adjust(entry, targetShelf, delta) {
    if (locked.current) return;
    locked.current = true; setBusy(true); setError("");
    try {
      await api.mutate("adjust_stock", entry.product.id, stockDelta(entry, targetShelf, delta));
      setUndo(delta > 0 ? { entry, shelf: targetShelf } : null);
      if (delta > 0) localStorage.setItem(`smocnica-last-shelf:${pantry.id}`, targetShelf);
    } catch (e) { setError(errorText(e)); }
    finally { locked.current = false; setBusy(false); }
  }
  if (!rows.length) return null;
  return <section className="recent-products" aria-label="Nedavni artikli">
    <h2>Dodaj ponovno <span>· 1 pakiranje</span></h2>
    <div className="recent-strip">{rows.map(entry => <button key={entry.variant.id} disabled={busy || !shelves.length}
      onClick={() => adjust(entry, entryShelf(shelves, shelf, localStorage.getItem(`smocnica-last-shelf:${pantry.id}`)), 1)}>
      <Photo variant={entry.variant} name={entry.product.name} />
      <span><strong>{entry.product.name}</strong><small>{packageDisplay(entry.variant)}</small></span>
    </button>)}</div>
    {busy && <p role="status">Spremanje…</p>}
    {undo && <div className="quick-undo" role="status">Dodano 1 pakiranje: {undo.entry.product.name}
      <button disabled={busy} onClick={() => adjust(undo.entry, undo.shelf, -1)}>Poništi</button>
    </div>}
    {error && <p role="alert" className="error">{error}</p>}
  </section>;
}
