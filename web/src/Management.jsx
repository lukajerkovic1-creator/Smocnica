import React, { useEffect, useState } from "react";
import {
  Plus,
  ArrowUp,
  ArrowDown,
  Trash2,
  Edit,
  Check,
  Download,
  Users,
  Bell,
  Folder,
  BookOpen,
  RefreshCw,
} from "lucide-react";
import {
  useApp,
  Field,
  Select,
  Form,
  Empty,
  InstallHelp,
  download,
} from "./ui";
import {
  active,
  sorted,
  integer,
  manualId,
  hash,
  timestamp,
  packageDisplay,
  csvCell,
  snapshot,
  validateSnapshot,
  totals,
} from "./domain";
import { database } from "./outbox";
import { inventoryPackageLabel } from "./inventory-label";
import { trashRows } from "./trash-presentation";
import Scanner from "./Scanner";
import { exportBackup, readBackup, mergeConflicts } from "./backup";
export function Shopping() {
  const { data, api, open, close, run, confirm } = useApp();
  const rows = active(data.shoppingItems).filter((i) => i.requiredQuantity > 0);
  const categories = sorted(active(data.categories));
  return (
    <section className="section">
      <p className="muted">
        Označite kupljeno. Zaliha se mijenja tek kad artikl dodate u smočnicu.
      </p>
      {!rows.length && <Empty>Popis za kupnju je prazan.</Empty>}
      {categories.map((c) => {
        const items = rows.filter((i) => i.categoryId === c.id);
        return items.length ? (
          <div key={c.id}>
            <h2 className="section-label">{c.name}</h2>
            {items.map((i) => (
              <div
                className={`shopping-row ${i.checked ? "checked" : ""}`}
                key={i.id}
              >
                <label className="check">
                  <input
                    type="checkbox"
                    checked={i.checked}
                    onChange={() =>
                      run(() =>
                        api.mutate(
                          "upsert_shopping",
                          i.id,
                          { item: { ...i, checked: !i.checked } },
                          i.revision,
                          "SHOPPING_ITEM",
                        ),
                      )
                    }
                  />
                  <span>
                    <strong>{i.name}</strong>
                    <small>
                      {i.manual ? "Ručno dodano" : "Prema minimalnoj zalihi"}
                    </small>
                  </span>
                </label>
                <span>{i.requiredQuantity} kom</span>
                {i.manual && (
                  <>
                    <button
                      className="icon"
                      aria-label={`Uredi ${i.name}`}
                      onClick={() =>
                        open("Uredi kupnju", <ShoppingForm item={i} />)
                      }
                    >
                      <Edit size={18} />
                    </button>
                    <button
                      className="icon danger"
                      aria-label={`Obriši ${i.name}`}
                      onClick={() =>
                        confirm(
                          "Obriši stavku",
                          "Ukloniti ručnu stavku s popisa?",
                          () =>
                            api.mutate(
                              "delete_shopping",
                              i.id,
                              { itemId: i.id },
                              i.revision,
                              "SHOPPING_ITEM",
                            ),
                        )
                      }
                    >
                      <Trash2 size={18} />
                    </button>
                  </>
                )}
              </div>
            ))}
          </div>
        ) : null;
      })}
      <button
        className="primary"
        onClick={() => open("Dodaj na popis", <ShoppingForm />)}
      >
        <Plus />
        Dodaj stavku
      </button>
    </section>
  );
}
function ShoppingForm({ item }) {
  const { api, data, pantry, close } = useApp();
  return (
    <Form
      cancel={close}
      onSubmit={async (f) => {
        const name = f.get("name"),
          categoryId = f.get("category"),
          requiredQuantity = integer(f.get("quantity"), 1);
        const id = item?.id || (await manualId(pantry.id, categoryId, name));
        await api.mutate(
          "upsert_shopping",
          id,
          {
            item: {
              ...item,
              id,
              name,
              categoryId,
              requiredQuantity,
              manual: true,
              productId: null,
              checked: item?.checked || false,
            },
            ...(!item ? { quantityDelta: requiredQuantity } : {}),
          },
          item?.revision || 0,
          "SHOPPING_ITEM",
        );
        close();
      }}
    >
      <Field
        label="Naziv"
        name="name"
        required
        defaultValue={item?.name || ""}
        maxLength={100}
      />
      <Select
        label="Kategorija"
        name="category"
        defaultValue={
          item?.categoryId || data.categories.find((c) => c.isDefault)?.id
        }
        rows={sorted(active(data.categories))}
      />
      <Field
        label="Broj pakiranja"
        name="quantity"
        type="number"
        min="1"
        defaultValue={item?.requiredQuantity || 1}
        required
      />
    </Form>
  );
}
export function ShelfManager() {
  return <ResourceManager kind="shelves" />;
}
function ResourceManager({ kind }) {
  const { data, api, open, close, pantry, run, confirm } = useApp();
  const shelf = kind === "shelves",
    rows = sorted(active(data[kind]));
  const label = shelf ? "policu" : "kategoriju";
  const edit = (r) =>
    open(
      r ? "Preimenuj" : `Dodaj ${label}`,
      <Form
        cancel={close}
        onSubmit={async (f) => {
          const id = r?.id || crypto.randomUUID(),
            name = f.get("name");
          await api.mutate(
            shelf ? (r ? "rename_shelf" : "create_shelf") : "upsert_category",
            id,
            shelf
              ? r
                ? { shelfId: id, name }
                : { shelf: { id, name, sortOrder: rows.length } }
              : {
                  category: {
                    ...r,
                    id,
                    name,
                    sortOrder: r?.sortOrder || rows.length,
                  },
                },
            r?.revision || 0,
            shelf ? "SHELF" : "CATEGORY",
          );
          close();
        }}
      >
        <Field
          label="Naziv"
          name="name"
          defaultValue={r?.name || ""}
          required
          maxLength={100}
        />
      </Form>,
    );
  const reorder = (index, direction) =>
    run(async () => {
      const ids = rows.map((r) => r.id);
      [ids[index], ids[index + direction]] = [
        ids[index + direction],
        ids[index],
      ];
      await api.mutate(
        shelf ? "reorder_shelves" : "reorder_categories",
        pantry.id,
        { [shelf ? "orderedShelfIds" : "orderedCategoryIds"]: ids },
        pantry.revision,
        "PANTRY",
      );
    });
  return (
    <section>
      {rows.map((r, index) => (
        <article className="resource" key={r.id}>
          <div>
            <strong>{r.name}</strong>
            {shelf && (
              <small>
                {
                  new Set(
                    data.stocks
                      .filter(
                        (s) =>
                          s.shelfId === r.id &&
                          s.quantity > 0 &&
                          data.products.some(
                            (p) => p.id === s.productId && !p.deletedAt,
                          ) &&
                          data.variants.some(
                            (v) => v.id === s.variantId && !v.deletedAt,
                          ),
                      )
                      .map((s) => s.productId),
                  ).size
                }{" "}
                artikala
              </small>
            )}
            {r.isDefault && <small>Zadana kategorija</small>}
          </div>
          <div className="row compact">
            <button
              className="icon"
              disabled={index === 0}
              aria-label={`Pomakni gore: ${r.name}`}
              onClick={() => reorder(index, -1)}
            >
              <ArrowUp size={18} />
            </button>
            <button
              className="icon"
              disabled={index === rows.length - 1}
              aria-label={`Pomakni dolje: ${r.name}`}
              onClick={() => reorder(index, 1)}
            >
              <ArrowDown size={18} />
            </button>
            <button
              className="icon"
              aria-label={`Preimenuj ${r.name}`}
              onClick={() => edit(r)}
            >
              <Edit size={18} />
            </button>
            <button
              className="icon danger"
              aria-label={`Obriši ${r.name}`}
              disabled={r.isDefault}
              onClick={() =>
                open(
                  `Obriši ${label}`,
                  <DeleteResource resource={r} kind={kind} />,
                )
              }
            >
              <Trash2 size={18} />
            </button>
          </div>
        </article>
      ))}
      <button className="primary" onClick={() => edit()}>
        <Plus />
        Dodaj {label}
      </button>
    </section>
  );
}
function DeleteResource({ resource: r, kind }) {
  const { api, data, close } = useApp();
  const shelf = kind === "shelves";
  const stock = data.stocks.filter(
    (s) =>
      s.shelfId === r.id &&
      s.quantity > 0 &&
      data.products.some((p) => p.id === s.productId && !p.deletedAt) &&
      data.variants.some((v) => v.id === s.variantId && !v.deletedAt),
  );
  const alternatives = active(data[kind]).filter((x) => x.id !== r.id);
  return (
    <Form
      cancel={close}
      destructive
      submit={shelf && stock.length ? "Premjesti sadržaj" : "Obriši"}
      onSubmit={async (f) => {
        if (shelf && stock.length) {
          await api.mutate("bulk_move_stock", r.id, {
            moves: stock.map((s) => ({
              productId: s.productId,
              variantId: s.variantId,
              fromShelfId: r.id,
              toShelfId: f.get("replacement"),
              quantity: s.quantity,
            })),
          });
          close();
        } else {
          await api.mutate(
            shelf ? "delete_shelf" : "delete_category",
            r.id,
            shelf
              ? { shelfId: r.id }
              : {
                  categoryId: r.id,
                  replacementCategoryId: f.get("replacement"),
                },
            r.revision,
            shelf ? "SHELF" : "CATEGORY",
          );
          close();
        }
      }}
    >
      <p>
        {shelf && stock.length
          ? "Polica sadrži zalihu. Najprije je premjestite, a zatim ponovno odaberite brisanje police."
          : `Obrisati „${r.name}”? Zapis će ostati u košu 30 dana.`}
      </p>
      {(!shelf || stock.length > 0) && (
        <Select
          label={shelf ? "Premjesti na policu" : "Zamjenska kategorija"}
          name="replacement"
          required
          rows={alternatives}
        />
      )}{" "}
      {!alternatives.length && stock.length > 0 && (
        <p>Najprije dodajte drugu policu.</p>
      )}
    </Form>
  );
}
export function Audit() {
  const { data, api, pantry, open, close, run, confirm } = useApp();
  const [draft, setDraft] = useState(null),
    [loaded, setLoaded] = useState(false);
  const [selectedVariantId, setSelectedVariantId] = useState("");
  const key = `audit:${api.uid()}:${pantry.id}`;
  useEffect(() => {
    let live = true;
    database()
      .then((db) => db.get("drafts", key))
      .then((d) => {
        if (live) {
          setDraft(d || null);
          setLoaded(true);
        }
      });
    return () => {
      live = false;
    };
  }, [key]);
  const save = async (value) => {
    const db = await database();
    await db.put("drafts", value, key);
    setDraft(value);
  };
  const start = async (f) => {
    const shelfId = f.get("shelf");
    const stocks = data.stocks.filter((s) => s.shelfId === shelfId);
    const revision = parseInt(
      (
        await hash(
          [...stocks]
            .sort((a, b) => a.variantId.localeCompare(b.variantId))
            .map((s) => `${s.variantId}:${s.quantity}`)
            .join("|"),
        )
      ).slice(0, 12),
      16,
    );
    await save({
      id: crypto.randomUUID(),
      shelfId,
      revision,
      expected: Object.fromEntries(
        stocks.map((s) => [s.variantId, s.quantity]),
      ),
      counts: {},
    });
  };
  if (!loaded) return <p>Učitavanje nacrta…</p>;
  if (!draft)
    return (
      <section className="section">
        <p>
          Prebrojite fizičku zalihu. Ništa se ne mijenja prije završne potvrde.
        </p>
        <Form submit="Započni inventuru" onSubmit={start}>
          <Select
            name="shelf"
            label="Polica"
            rows={sorted(active(data.shelves))}
            required
          />
        </Form>
      </section>
    );
  const variants = active(data.variants).filter((v) =>
    data.products.some((p) => p.id === v.productId && !p.deletedAt),
  );
  const packageLabel = (v) => inventoryPackageLabel(
    data.products.find((p) => p.id === v.productId)?.name,
    v,
  );
  const selectedVariant = variants.find((v) => v.id === selectedVariantId) || variants[0];
  const rows = variants.filter(
    (v) =>
      Object.hasOwn(draft.expected, v.id) || Object.hasOwn(draft.counts, v.id),
  );
  const differences = rows.filter(
    (v) => (draft.expected[v.id] || 0) !== (draft.counts[v.id] || 0),
  );
  return (
    <section className="section">
      <h2>{data.shelves.find((s) => s.id === draft.shelfId)?.name}</h2>
      <p>
        Neskenirani artikli računaju se kao 0 komada. Nacrt se čuva na ovom
        uređaju.
      </p>
      <button
        onClick={() =>
          open(
            "Skeniraj za inventuru",
            <Scanner
              onFound={async (code) => {
                const v = variants.find((v) => v.barcode === code);
                if (!v)
                  throw new Error(
                    "Najprije dodajte ovaj proizvod u smočnicu, zatim nastavite inventuru.",
                  );
                await save({
                  ...draft,
                  counts: {
                    ...draft.counts,
                    [v.id]: (draft.counts[v.id] || 0) + 1,
                  },
                });
                close();
              }}
            />,
          )
        }
      >
        Skeniraj barkod
      </button>
      <Form
        submit="Dodaj brojanje"
        submitDisabled={!selectedVariant}
        onSubmit={async (f) => {
          const id = f.get("variant");
          await save({
            ...draft,
            counts: { ...draft.counts, [id]: integer(f.get("count")) },
          });
        }}
      >
        <Select
          label="Koje pakiranje brojite?"
          name="variant"
          required
          value={selectedVariant?.id || ""}
          onChange={(event) => setSelectedVariantId(event.target.value)}
          rows={variants.map((v) => ({
            id: v.id,
            name: packageLabel(v),
          }))}
        />
        {selectedVariant && (
          <p className="audit-package-summary" aria-live="polite">
            Odabrano pakiranje: <strong>{packageLabel(selectedVariant)}</strong>
          </p>
        )}
        <Field
          label="Stvarni broj pakiranja"
          name="count"
          type="number"
          min="0"
          defaultValue="1"
          required
        />
      </Form>
      {rows.map((v) => (
        <div className="audit-row" key={v.id}>
          <span>
            {packageLabel(v)}
            <small>Evidentirano: {draft.expected[v.id] || 0}</small>
          </span>
          <strong>{draft.counts[v.id] || 0} kom</strong>
        </div>
      ))}
      <button
        className="primary"
        disabled={!differences.length}
        onClick={() =>
          open(
            "Potvrdi inventuru",
            <Form
              submit="Primijeni inventuru"
              onSubmit={async () => {
                await api.mutate(
                  "apply_inventory",
                  draft.id,
                  {
                    session: {
                      id: draft.id,
                      shelfId: draft.shelfId,
                      differences: differences.map((v) => ({
                        productId: v.productId,
                        variantId: v.id,
                        actualQuantity: draft.counts[v.id] || 0,
                      })),
                    },
                  },
                  draft.revision,
                  "INVENTORY",
                );
                const db = await database();
                await db.delete("drafts", key);
                setDraft(null);
                close();
              }}
            >
              <p>
                {differences.length} razlika. Ako se zaliha promijenila tijekom
                brojanja, poslužitelj će odbiti primjenu.
              </p>
              {differences.map((v) => (
                <p key={v.id}>
                  {packageLabel(v)}: {draft.expected[v.id] || 0} →{" "}
                  {draft.counts[v.id] || 0}
                </p>
              ))}
            </Form>,
          )
        }
      >
        Pregledaj {differences.length} razlika
      </button>
      <button
        className="danger"
        onClick={() =>
          confirm(
            "Odbaci inventuru",
            "Odbaciti ovaj nacrt? Stvarna zaliha ostaje nepromijenjena.",
            async () => {
              const db = await database();
              await db.delete("drafts", key);
              setDraft(null);
            },
          )
        }
      >
        Odbaci nacrt
      </button>
    </section>
  );
}
const activityNames = {
  STOCK_ADDED: "Dodana zaliha",
  STOCK_REMOVED: "Izvađena zaliha",
  STOCK_MOVED: "Premještena zaliha",
  PRODUCT_UPDATED: "Uređen artikl",
  PRODUCT_CREATED: "Dodan artikl",
  PRODUCT_DELETED: "Obrisan artikl",
  PRODUCT_RESTORED: "Vraćen artikl",
  INVENTORY_APPLIED: "Inventura",
  SHOPPING_UPDATED: "Promjena kupnje",
  SHELF_CREATED: "Dodana polica",
  SHELF_RENAMED: "Preimenovana polica",
  PANTRY_CREATED: "Izrađena smočnica",
  MEMBER_JOINED: "Pridružen član",
  MEMBER_REMOVED: "Uklonjen član",
  OWNERSHIP_TRANSFERRED: "Preneseno vlasništvo",
  IMPORT_APPLIED: "Uvoz podataka",
};
export function HistoryScreen() {
  const { data, api, run } = useApp();
  const [older, setOlder] = useState([]),
    [end, setEnd] = useState(false);
  const all = [
    ...data.activities,
    ...older.filter((a) => !data.activities.some((r) => r.id === a.id)),
  ];
  const [search, setSearch] = useState("");
  return (
    <section className="section">
      <Field
        label="Pretraži povijest"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />
      <p className="muted">Povijest se čuva 12 mjeseci.</p>
      {all
        .filter((a) =>
          [a.displayLabel, a.deviceDisplayName, activityNames[a.type]]
            .join(" ")
            .toLocaleLowerCase("hr")
            .includes(search.toLocaleLowerCase("hr")),
        )
        .map((a) => (
          <article className="history" key={a.id}>
            <strong>{activityNames[a.type] || "Promjena u smočnici"}</strong>
            <p>
              {a.displayLabel ||
                data.products.find((p) => p.id === a.productId)?.name ||
                ""}
              {a.quantityDelta != null
                ? ` · ${a.quantityDelta > 0 ? "+" : ""}${a.quantityDelta}`
                : ""}
            </p>
            {(a.oldValue || a.newValue) && (
              <small>
                {a.oldValue} → {a.newValue}
              </small>
            )}
            <small>
              {new Date(timestamp(a.createdAt)).toLocaleString("hr")} ·{" "}
              {a.deviceDisplayName}
            </small>
          </article>
        ))}
      {!end && data.activities.length === 500 && (
        <button
          onClick={() =>
            run(async () => {
              const rows = await api.history(all.at(-1)?.id);
              setOlder((previous) => [...previous, ...rows]);
              setEnd(rows.length < 500);
            })
          }
        >
          Učitaj starije aktivnosti
        </button>
      )}
    </section>
  );
}
export function TrashScreen() {
  const { data, api, run, confirm, pantry } = useApp();
  const rows = trashRows(data);
  return (
    <section className="section">
      <p>
        Obrisani zapisi čuvaju se 30 dana. Prvo vratite potrebne police i
        kategorije, zatim artikle.
      </p>
      {!rows.length && <Empty>Koš je prazan.</Empty>}
      {rows.map((r) => (
        <article key={`${r.kind}:${r.id}`}>
          <strong>{r.typeLabel}: {r.title}</strong>
          {r.details.map((detail, index) => <p key={index}>{detail}</p>)}
          {r.restoresTogether && <p>Artikl se vraća zajedno s prikazanim pakiranjima.</p>}
          <small>Obrisano: {new Date(timestamp(r.deletedAt)).toLocaleString("hr")}</small>
          <small>
            Trajno brisanje:{" "}
            {new Date(timestamp(r.purgeAfter)).toLocaleDateString("hr")}
          </small>
          <div className="row">
            <button
              onClick={() =>
                run(() =>
                  api.mutate(
                    "restore",
                    r.id,
                    { targetType: r.type, id: r.id },
                    r.revision,
                    r.type,
                  ),
                )
              }
            >
              Vrati
            </button>
            <button
              className="danger"
              onClick={() =>
                confirm(
                  `Trajno obrisati: ${r.typeLabel.toLocaleLowerCase("hr")} ${r.title}?`,
                  `${r.details.join(" · ")}\nOvaj zapis i pripadajuće fotografije bit će trajno obrisani. Radnja se ne može poništiti.`,
                  () =>
                    api.call("purgeTrash", {
                      pantryId: pantry.id,
                      id: r.id,
                      type: r.type,
                    }),
                )
              }
            >
              Trajno obriši
            </button>
          </div>
        </article>
      ))}
    </section>
  );
}
function usePantryId(data, row) {
  return row.pantryId;
}
export function SettingsScreen({ reload }) {
  const { api, pantry, user, owner, open, close, run, confirm, notify } =
    useApp();
  const [theme, setTheme] = useState(
    localStorage.getItem("smocnica-theme") || "system",
  );
  return (
    <section className="section settings">
      <h2>{pantry.name}</h2>
      <p className="muted">{owner ? "Vlasnik smočnice" : "Član smočnice"}</p>
      <Form
        onSubmit={async (f) => {
          const name = String(f.get("device")).trim();
          await api.register({ deviceDisplayName: name });
          localStorage.setItem("smocnica-device-name", name);
          notify("Naziv uređaja je spremljen.");
        }}
      >
        <Field
          label="Naziv ovog uređaja"
          name="device"
          defaultValue={api.deviceName()}
          required
          minLength={2}
          maxLength={40}
        />
      </Form>
      <Select
        label="Izgled"
        value={theme}
        onChange={(e) => {
          const t = e.target.value;
          setTheme(t);
          localStorage.setItem("smocnica-theme", t);
          document.documentElement.dataset.theme = t;
        }}
        rows={[
          { id: "system", name: "Prema iPhoneu" },
          { id: "light", name: "Svijetlo" },
          { id: "dark", name: "Tamno" },
        ]}
      />
      <button className="nav" onClick={() => open("Police", <ShelfManager />)}>
        <Folder />
        Police
      </button>
      <button
        className="nav"
        onClick={() =>
          open("Kategorije", <ResourceManager kind="categories" />)
        }
      >
        <Folder />
        Kategorije
      </button>
      <button
        className="nav"
        onClick={() => open("Članovi i pozivnice", <Members />)}
      >
        <Users />
        Članovi i pozivnice
      </button>
      <button
        className="nav"
        onClick={() => open("Obavijesti", <Notifications />)}
      >
        <Bell />
        Obavijesti
      </button>
      <button className="nav" onClick={() => open("Uvoz i izvoz", <Backups />)}>
        <Download />
        Uvoz i izvoz
      </button>
      {owner && (
        <button
          className="nav"
          onClick={() => open("Pravila povezivanja pakiranja", <Rules />)}
        >
          <BookOpen />
          Pravila povezivanja pakiranja
        </button>
      )}
      <button className="nav" onClick={() => location.reload()}>
        <RefreshCw />
        Provjeri novu web-verziju
      </button>
      <InstallHelp />
      <hr />
      <p>Smočnica za web · 1.0.0</p>
      <p>
        Podaci o proizvodima:{" "}
        <a
          href="https://world.openfoodfacts.org"
          target="_blank"
          rel="noreferrer"
        >
          Open Food Facts
        </a>{" "}
        (ODbL). Fotografije: CC BY-SA.
      </p>
      <p>
        <a href={`${import.meta.env.BASE_URL}privacy-policy.html`}>
          Politika privatnosti
        </a>{" "}
        ·{" "}
        <a href={`${import.meta.env.BASE_URL}delete-account.html`}>
          Brisanje računa
        </a>
      </p>
      {owner && (
        <button
          className="danger"
          onClick={() =>
            confirm(
              "Obriši smočnicu",
              "Svi članovi izgubit će pristup. Smočnica će biti trajno uklonjena nakon 30 dana.",
              async () => {
                await api.call("deletePantry", { pantryId: pantry.id });
                reload();
              },
            )
          }
        >
          Obriši smočnicu
        </button>
      )}
      <button
        className="danger"
        onClick={() =>
          confirm(
            "Izbriši korisnički račun",
            "Vaš račun i pristup bit će trajno obrisani. Ako postoje drugi članovi, vlasništvo će se prenijeti jednom od njih; inače se briše i smočnica.",
            async () => {
              await api.call("deleteAccount", { confirmation: "DELETE" });
              await api.forget();
              location.reload();
            },
          )
        }
      >
        Izbriši korisnički račun
      </button>
    </section>
  );
}
function Members() {
  const { data, api, pantry, owner, user, run, confirm } = useApp();
  const [invitation, setInvitation] = useState(null),
    [qr, setQr] = useState(null);
  return (
    <>
      <p>Pozivni kod vrijedi 24 sata i može se iskoristiti jednom.</p>
      {owner && (
        <button
          className="primary"
          onClick={() =>
            run(async () => {
              const i = await api.call("createInvitation", {
                pantryId: pantry.id,
              });
              setInvitation(i);
              const QR = await import("qrcode");
              setQr(await QR.toDataURL(i.code, { width: 240, margin: 2 }));
            })
          }
        >
          Izradi novi pozivni kod
        </button>
      )}
      {invitation && (
        <div className="invitation">
          <strong>{invitation.code}</strong>
          {qr && <img src={qr} alt="QR-kod pozivnice" />}
          <button
            onClick={() =>
              run(async () => {
                await navigator.clipboard.writeText(invitation.code);
              })
            }
          >
            Kopiraj kod
          </button>
          <p>
            Vrijedi do {new Date(invitation.expiresAt).toLocaleString("hr")}
          </p>
        </div>
      )}
      {data.members
        .filter((m) => m.active)
        .map((m) => (
          <article key={m.id}>
            <strong>{m.displayName || "Član"}</strong>
            <small>{m.role === "OWNER" ? "Vlasnik" : "Član"}</small>
            {owner && m.id !== user.uid && (
              <div className="row wrap">
                <button
                  onClick={() =>
                    confirm(
                      "Prenesi vlasništvo",
                      `Predati upravljanje članu „${m.displayName}”?`,
                      () =>
                        api.call("transferOwnership", {
                          pantryId: pantry.id,
                          newOwnerUid: m.id,
                        }),
                    )
                  }
                >
                  Prenesi vlasništvo
                </button>
                <button
                  className="danger"
                  onClick={() =>
                    confirm(
                      "Ukloni člana",
                      `Ukloniti pristup članu „${m.displayName}”?`,
                      () =>
                        api.call("manageMember", {
                          pantryId: pantry.id,
                          memberUid: m.id,
                          action: "REMOVE",
                        }),
                    )
                  }
                >
                  Ukloni
                </button>
              </div>
            )}
          </article>
        ))}
    </>
  );
}
function Notifications() {
  const { api, notify, run } = useApp();
  return (
    <Form
      submit="Uključi obavijesti"
      onSubmit={async (f) => {
        await api.notifications(f.get("detailed") === "on");
        notify("Obavijesti su uključene na ovom uređaju.");
      }}
    >
      <p>
        Na iPhoneu otvorite Smočnicu putem ikone na početnom zaslonu pa
        uključite obavijesti.
      </p>
      <label className="check">
        <input
          type="checkbox"
          name="detailed"
          defaultChecked={localStorage.getItem("smocnica-detailed") === "true"}
        />
        Detaljne obavijesti s nazivima i količinama
      </label>
      <p className="muted">
        Bez ove opcije obavijest ne prikazuje sadržaj vaše smočnice.
      </p>
      <button
        type="button"
        onClick={() =>
          run(async () => {
            await api.disableNotifications();
            notify("Obavijesti su isključene na ovom uređaju.");
          })
        }
      >
        Isključi obavijesti
      </button>
    </Form>
  );
}
function Rules() {
  const { data, api, close } = useApp();
  return (
    <>
      {active(data.synonymRules).map((r) => (
        <p key={r.id}>
          {r.sourceNormalized} → {r.genericName}
        </p>
      ))}
      <Form
        onSubmit={async (f) => {
          const name = f.get("source");
          const existing = data.synonymRules.find(
            (r) => r.sourceNormalized === name.toLocaleLowerCase("hr"),
          );
          const id = existing?.id || crypto.randomUUID();
          await api.mutate(
            "upsert_synonym_rule",
            id,
            {
              rule: {
                id,
                sourceNormalized: name,
                genericName: f.get("generic"),
                productId: f.get("product") || null,
              },
            },
            existing?.revision || 0,
            "SYNONYM_RULE",
          );
          close();
        }}
      >
        <Field label="Naziv koji prepoznajemo" name="source" required />
        <Field label="Zajednički naziv artikla" name="generic" required />
        <Select
          label="Postojeći artikl (neobvezno)"
          name="product"
          rows={[{ id: "", name: "Bez veze" }, ...active(data.products)]}
        />
      </Form>
    </>
  );
}
function Backups() {
  const { data, pantry, api, close, run } = useApp();
  const [preview, setPreview] = useState(null),
    [error, setError] = useState(""),
    [history, setHistory] = useState(false),
    [strategy, setStrategy] = useState("merge");
  const conflicts =
    preview && strategy === "merge" ? mergeConflicts(data, preview) : [];
  return (
    <>
      <label className="check">
        <input
          type="checkbox"
          checked={history}
          onChange={(e) => setHistory(e.target.checked)}
        />
        Uključi povijest u izvoz
      </label>
      <button
        onClick={() =>
          run(async () => {
            const payload = await exportBackup(
              history ? { ...data, activities: await api.allHistory() } : data,
              pantry,
              history,
            );
            download(
              "smocnica.json",
              JSON.stringify(payload, null, 2),
              "application/json",
            );
          })
        }
      >
        Izvezi JSON
      </button>
      <button
        onClick={() => {
          const rows = [
            [
              "Naziv",
              "Barkodovi",
              "Pakiranja",
              "Kategorija",
              "Ukupno pakiranja",
              "Minimum",
              "Police",
              "Kupnja",
            ],
            ...active(data.products).map((p) => {
              const vs = active(data.variants).filter(
                (v) => v.productId === p.id,
              );
              return [
                p.name,
                vs
                  .map((v) => v.barcode)
                  .filter(Boolean)
                  .join(" | "),
                vs.map(packageDisplay).join(" | "),
                p.category,
                totals(p, vs, data.stocks).packages,
                p.minimumAmountBase,
                data.stocks
                  .filter((s) => s.productId === p.id && s.quantity > 0)
                  .map(
                    (s) =>
                      `${data.shelves.find((sh) => sh.id === s.shelfId)?.name}: ${s.quantity}`,
                  )
                  .join(" | "),
                active(data.shoppingItems).some((i) => i.productId === p.id)
                  ? "Na popisu"
                  : "",
              ];
            }),
          ];
          download(
            "smocnica.csv",
            "\uFEFF" + rows.map((r) => r.map(csvCell).join(";")).join("\r\n"),
            "text/csv;charset=utf-8",
          );
        }}
      >
        Izvezi CSV za Excel
      </button>
      <hr />
      <Field
        label="Uvezi JSON"
        type="file"
        accept="application/json,.json"
        onChange={async (e) => {
          setError("");
          setPreview(null);
          try {
            const file = e.target.files[0];
            if (!file) return;
            if (file.size > 20 * 1024 * 1024)
              throw new Error("Najveća veličina je 20 MB.");
            setPreview(await readBackup(await file.text()));
          } catch (e) {
            setError(e.message);
          }
        }}
      />
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {preview && (
        <Form
          submit="Potvrdi uvoz"
          submitDisabled={conflicts.length > 0}
          onSubmit={async (f) => {
            await api.mutate(
              "import_snapshot",
              pantry.id,
              {
                snapshot: preview,
                replaceExisting: f.get("mode") === "replace",
              },
              pantry.revision,
              "PANTRY",
            );
            close();
          }}
        >
          <p>
            {preview.products.length} artikala, {preview.variants.length}{" "}
            pakiranja, {preview.shelves.length} polica.
          </p>
          <p>
            {
              preview.products.filter((p) =>
                data.products.some((old) => old.id === p.id),
              ).length
            }{" "}
            postojećih artikala bit će ažurirano.
          </p>
          {conflicts.map((message, i) => (
            <p className="error" key={i}>
              {message}
            </p>
          ))}
          <p>
            Isti identifikatori zamjenjuju se vrijednostima iz datoteke. Prije
            uvoza izvezite trenutačno stanje.
          </p>
          <Select
            label="Način uvoza"
            name="mode"
            value={strategy}
            onChange={(e) => setStrategy(e.target.value)}
            rows={[
              { id: "merge", name: "Spoji s postojećim zapisima" },
              { id: "replace", name: "Zamijeni postojeće zapise" },
            ]}
          />
        </Form>
      )}
    </>
  );
}
