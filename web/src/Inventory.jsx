import React, { useEffect, useRef, useState } from "react";
import {
  Plus,
  Search,
  SlidersHorizontal,
  ChevronRight,
  MoreHorizontal,
  Minus,
  Camera,
  ArrowRightLeft,
  Trash2,
  Edit,
  Layers,
} from "lucide-react";
import { useApp, Field, Select, Form, Empty, Photo } from "./ui";
import {
  active,
  sorted,
  quantity,
  packageDisplay,
  packageBase,
  integer,
  totals,
  normalize,
  timestamp,
  belowMinimum,
  groupingMatches,
} from "./domain";
import { ScanFlow, compressPhoto } from "./Scanner";
import { ShelfManager } from "./Management";
export default function Inventory() {
  const { data, open, close } = useApp();
  const [search, setSearch] = useState(""),
    [shelf, setShelf] = useState(""),
    [sort, setSort] = useState("alpha"),
    [category, setCategory] = useState(""),
    [filter, setFilter] = useState(""),
    [selected, setSelected] = useState([]),
    [selecting, setSelecting] = useState(false);
  const shelves = sorted(active(data.shelves)),
    products = active(data.products),
    variants = active(data.variants);
  const firstShelf = (p) =>
    shelves.find((s) =>
      data.stocks.some(
        (st) =>
          st.productId === p.id &&
          st.shelfId === s.id &&
          st.quantity > 0 &&
          variants.some((v) => v.id === st.variantId),
      ),
    )?.id || "";
  const list = products
    .filter(
      (p) =>
        (!shelf ||
          data.stocks.some(
            (s) =>
              s.productId === p.id &&
              s.shelfId === shelf &&
              s.quantity > 0 &&
              variants.some((v) => v.id === s.variantId),
          )) &&
        (!category || p.categoryId === category) &&
        (!filter ||
          (filter === "low" && belowMinimum(p, variants, data.stocks)) ||
          (filter === "shopping" &&
            data.shoppingItems.some(
              (i) => !i.deletedAt && i.productId === p.id,
            ))) &&
        normalize(
          [
            p.name,
            p.category,
            ...variants
              .filter((v) => v.productId === p.id)
              .map((v) =>
                [v.displayName, v.barcode, v.manufacturer, v.description].join(
                  " ",
                ),
              ),
          ].join(" "),
        ).includes(normalize(search)),
    )
    .sort((a, b) =>
      sort === "newest"
        ? timestamp(b.createdAt) - timestamp(a.createdAt)
        : sort === "shelf"
          ? (shelves.findIndex((s) => s.id === firstShelf(a)) < 0
              ? 999
              : shelves.findIndex((s) => s.id === firstShelf(a))) -
              (shelves.findIndex((s) => s.id === firstShelf(b)) < 0
                ? 999
                : shelves.findIndex((s) => s.id === firstShelf(b))) ||
            a.name.localeCompare(b.name, "hr")
          : a.name.localeCompare(b.name, "hr"),
    );
  return (
    <>
      <div className="inventory-controls">
        <label className="search">
          <Search size={21} />
          <input
            aria-label="Pretraži artikle"
            placeholder="Pretraži artikle"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <div className="filters">
          <select
            aria-label="Polica"
            value={shelf}
            onChange={(e) => {
              if (e.target.value === "manage") open("Police", <ShelfManager />);
              else setShelf(e.target.value);
            }}
          >
            <option value="">Sve police</option>
            {shelves.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
            <option value="manage">Uredi police…</option>
          </select>
          <select
            aria-label="Redoslijed"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            <option value="alpha">Abecedno</option>
            <option value="newest">Najnovije dodano</option>
            <option value="shelf">Po policama</option>
          </select>
          <button
            className="icon"
            aria-label="Filtri"
            onClick={() =>
              open(
                "Filtri",
                <Form
                  onSubmit={async (f) => {
                    setCategory(f.get("category"));
                    setFilter(f.get("filter"));
                    setSelecting(f.get("multiple") === "on");
                    close();
                  }}
                  submit="Primijeni"
                >
                  <Select
                    label="Kategorija"
                    name="category"
                    defaultValue={category}
                    rows={[
                      { id: "", name: "Sve kategorije" },
                      ...sorted(active(data.categories)),
                    ]}
                  />
                  <Select
                    label="Prikaz"
                    name="filter"
                    defaultValue={filter}
                    rows={[
                      { id: "", name: "Sve zalihe" },
                      { id: "low", name: "Ispod minimuma" },
                      { id: "shopping", name: "Na popisu za kupnju" },
                    ]}
                  />
                  <label className="check">
                    <input
                      name="multiple"
                      type="checkbox"
                      defaultChecked={selecting}
                    />
                    Višestruki odabir
                  </label>
                </Form>,
              )
            }
          >
            <SlidersHorizontal size={21} />
          </button>
        </div>
      </div>
      {selecting && (
        <div className="bulkbar">
          <button
            onClick={() =>
              open(
                "Skupne radnje",
                <BulkActions
                  ids={selected}
                  done={() => {
                    setSelected([]);
                    setSelecting(false);
                  }}
                />,
              )
            }
            disabled={!selected.length}
          >
            {selected.length} odabrano · Radnje
          </button>
          <button
            onClick={() => {
              setSelecting(false);
              setSelected([]);
            }}
          >
            Gotovo
          </button>
        </div>
      )}
      {!list.length && (
        <Empty>
          {search || category || filter
            ? "Nema artikala za odabrane filtre."
            : "Još nema artikala. Dodajte prvi skeniranjem barkoda."}
        </Empty>
      )}
      <div className="product-list">
        {list.map((p, index) => {
          const vs = variants.filter((v) => v.productId === p.id),
            matched = vs.find((v) => v.barcode && v.barcode === search.trim()),
            v =
              matched || vs.find((v) => v.id === p.preferredVariantId) || vs[0];
          const photo = matched || [v, ...vs].find((v) => v?.photoUrl) || v;
          const section = firstShelf(p);
          return (
            <React.Fragment key={p.id}>
              {sort === "shelf" &&
                (index === 0 || firstShelf(list[index - 1]) !== section) && (
                  <h2 className="shelf-heading">
                    {shelves.find((s) => s.id === section)?.name ||
                      "Bez zalihe na policama"}
                  </h2>
                )}
              <ProductRow
                p={p}
                photo={photo}
                label={[
                  v ? packageDisplay(v) : "",
                  shelves
                    .filter((s) =>
                      data.stocks.some(
                        (st) =>
                          st.productId === p.id &&
                          st.shelfId === s.id &&
                          st.quantity > 0,
                      ),
                    )
                    .map((s) => s.name)
                    .join(", "),
                ]
                  .filter(Boolean)
                  .join(" · ")}
                selecting={selecting}
                selected={selected.includes(p.id)}
                toggle={() =>
                  setSelected((ids) =>
                    ids.includes(p.id)
                      ? ids.filter((id) => id !== p.id)
                      : [...ids, p.id],
                  )
                }
                select={() => {
                  setSelecting(true);
                  setSelected([p.id]);
                }}
              />
            </React.Fragment>
          );
        })}
      </div>
      <button
        className="fab"
        aria-label="Dodaj artikl"
        onClick={() => open("Dodaj artikl", <ScanFlow initialShelf={shelf} />)}
      >
        <Plus size={30} />
      </button>
    </>
  );
}
function ProductRow({ p, photo, label, selecting, selected, toggle, select }) {
  const { data, open } = useApp();
  const [swipe, setSwipe] = useState(false),
    start = useRef(0),
    hold = useRef();
  const count = totals(p, data.variants, data.stocks).packages;
  useEffect(() => () => clearTimeout(hold.current), []);
  return (
    <div
      className="swipe-row"
      onPointerDown={(e) => {
        start.current = e.clientX;
        hold.current = setTimeout(select, 600);
      }}
      onPointerMove={(e) => {
        if (Math.abs(e.clientX - start.current) > 12)
          clearTimeout(hold.current);
      }}
      onPointerUp={(e) => {
        clearTimeout(hold.current);
        if (!selecting && e.clientX - start.current < -45) setSwipe(true);
        if (e.clientX - start.current > 45) setSwipe(false);
      }}
      onPointerCancel={() => clearTimeout(hold.current)}
    >
      <div className="swipe-actions">
        <button
          aria-label={`Dodaj ${p.name}`}
          onClick={() => {
            setSwipe(false);
            open(
              "Dodaj količinu",
              <StockForm productId={p.id} direction={1} />,
            );
          }}
        >
          +1
        </button>
        <button
          disabled={!count}
          aria-label={`Izvadi ${p.name}`}
          onClick={() => {
            setSwipe(false);
            open(
              "Izvadi količinu",
              <StockForm productId={p.id} direction={-1} />,
            );
          }}
        >
          −1
        </button>
      </div>
      <div className={`product-row ${swipe && !selecting ? "shifted" : ""}`}>
        {selecting && (
          <input
            type="checkbox"
            aria-label={`Odaberi ${p.name}`}
            checked={selected}
            onChange={toggle}
          />
        )}
        <button
          className="product-main"
          onClick={() =>
            selecting
              ? toggle()
              : open(p.name, <ProductDetail productId={p.id} />)
          }
        >
          <Photo variant={photo} />
          <span className="product-copy">
            <strong>{p.name}</strong>
            <small>{label}</small>
          </span>
          <span className="count">{count} kom</span>
        </button>
        <button
          className="icon more"
          aria-label={`Radnje: ${p.name}`}
          onClick={() => open(p.name, <ProductDetail productId={p.id} />)}
        >
          <MoreHorizontal size={21} />
        </button>
      </div>
    </div>
  );
}
export function ProductDetail({ productId, selectedVariant }) {
  const { data, open, api, confirm, close, owner } = useApp();
  const p = data.products.find((p) => p.id === productId);
  if (!p || p.deletedAt) return <Empty>Artikl nije dostupan.</Empty>;
  const variants = active(data.variants).filter((v) => v.productId === p.id);
  const total = totals(p, variants, data.stocks);
  return (
    <section className="detail">
      <h2>{p.name}</h2>
      <p className="muted">
        {p.category} · {total.packages} pakiranja
      </p>
      {Object.entries(total.amounts).map(([kind, amount]) => (
        <p key={kind}>
          {total.unknown ? "Najmanje " : ""}
          {(
            amount / (kind === "mg" ? 1000000 : kind === "ml" ? 1000 : 1)
          ).toLocaleString("hr")}{" "}
          {kind === "mg" ? "kg" : kind === "ml" ? "l" : "kom"}
        </p>
      ))}
      {total.unknown > 0 && <p>{total.unknown} pakiranja nepoznate veličine</p>}
      <div className="row">
        <button
          className="primary"
          onClick={() =>
            open(
              "Dodaj količinu",
              <StockForm
                productId={p.id}
                variantId={selectedVariant}
                direction={1}
              />,
            )
          }
        >
          <Plus />
          Dodaj
        </button>
        <button
          onClick={() =>
            open(
              "Izvadi količinu",
              <StockForm
                productId={p.id}
                variantId={selectedVariant}
                direction={-1}
              />,
            )
          }
          disabled={!total.packages}
        >
          <Minus />
          Izvadi
        </button>
      </div>
      {variants.map((v) => (
        <article
          className={
            v.id === selectedVariant ? "variant highlighted" : "variant"
          }
          key={v.id}
        >
          <div className="row">
            <Photo variant={v} />
            <div>
              <strong>{v.displayName}</strong>
              <p>
                {[v.manufacturer, packageDisplay(v)]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
              <small>{v.barcode || "Bez barkoda"}</small>
            </div>
          </div>
          {v.description && <p>{v.description}</p>}
          {data.stocks
            .filter((s) => s.variantId === v.id && s.quantity > 0)
            .map((s) => (
              <p key={s.id}>
                {data.shelves.find((x) => x.id === s.shelfId)?.name}:{" "}
                <strong>{s.quantity} kom</strong>
              </p>
            ))}
          <div className="row wrap">
            <button
              onClick={() =>
                open("Uredi varijantu", <VariantEditor variant={v} />)
              }
            >
              <Edit size={18} />
              Uredi
            </button>
            <button
              onClick={() =>
                open(
                  "Premjesti zalihu",
                  <StockForm productId={p.id} variantId={v.id} move />,
                )
              }
            >
              <ArrowRightLeft size={18} />
              Premjesti
            </button>
            <button
              onClick={() =>
                open(
                  "Grupiranje varijante",
                  <Grouping variant={v} product={p} />,
                )
              }
            >
              <Layers size={18} />
              Grupiraj
            </button>
            <button
              className="danger"
              onClick={() =>
                confirm(
                  "Obriši varijantu",
                  "Varijanta i njezina zaliha ostat će u košu 30 dana.",
                  () =>
                    api.mutate(
                      "soft_delete",
                      v.id,
                      { targetType: "VARIANT", id: v.id },
                      v.revision,
                      "VARIANT",
                    ),
                )
              }
            >
              <Trash2 size={18} />
            </button>
          </div>
        </article>
      ))}
      <button
        onClick={() =>
          open("Nova varijanta", <VariantEditor productId={p.id} />)
        }
      >
        <Plus />
        Dodaj varijantu
      </button>
      <button
        onClick={() => open("Uredi artikl", <ProductEditor product={p} />)}
      >
        Naziv, kategorija i minimum
      </button>
      {owner && (
        <button
          onClick={() =>
            confirm(
              "Automatsko grupiranje",
              p.doNotGroup
                ? "Dopusti prijedloge grupiranja ovog artikla?"
                : "Isključi prijedloge grupiranja ovog artikla?",
              () =>
                api.mutate(
                  "set_do_not_group",
                  p.id,
                  {
                    productId: p.id,
                    doNotGroup: !p.doNotGroup,
                    expectedGroupingRevision: p.groupingRevision || 0,
                  },
                  p.revision,
                ),
            )
          }
        >
          {p.doNotGroup ? "Dopusti grupiranje" : "Ne grupiraj ovaj artikl"}
        </button>
      )}
      <button
        className="danger"
        onClick={() =>
          confirm(
            "Obriši artikl",
            "Artikl, njegove varijante i zalihe ostat će u košu 30 dana.",
            () =>
              api.mutate(
                "soft_delete",
                p.id,
                { targetType: "PRODUCT", id: p.id },
                p.revision,
              ),
          )
        }
      >
        Premjesti artikl u koš
      </button>
    </section>
  );
}
export function StockForm({
  productId,
  variantId,
  direction = 1,
  move = false,
}) {
  const { data, api, close } = useApp();
  const variants = active(data.variants).filter(
      (v) => v.productId === productId,
    ),
    shelves = sorted(active(data.shelves));
  const [variant, setVariant] = useState(variantId || variants[0]?.id || ""),
    [shelf, setShelf] = useState("");
  const possible =
    direction < 0 || move
      ? shelves.filter((s) =>
          data.stocks.some(
            (st) =>
              st.variantId === variant &&
              st.shelfId === s.id &&
              st.quantity > 0,
          ),
        )
      : shelves;
  const source = shelf || possible[0]?.id || "";
  return (
    <Form
      cancel={close}
      onSubmit={async (f) => {
        const n = integer(f.get("quantity"), 1);
        await api.mutate(
          move ? "move_stock" : "adjust_stock",
          productId,
          move
            ? {
                productId,
                variantId: variant,
                fromShelfId: source,
                toShelfId: f.get("to"),
                quantity: n,
              }
            : {
                productId,
                variantId: variant,
                shelfId: source,
                delta: direction * n,
              },
        );
        close();
      }}
    >
      <Select
        label="Varijanta"
        value={variant}
        rows={variants.map((v) => ({
          id: v.id,
          name: `${v.displayName} · ${packageDisplay(v)}`,
        }))}
        onChange={(e) => {
          setVariant(e.target.value);
          setShelf("");
        }}
      />
      <Select
        label={move ? "S police" : "Polica"}
        value={source}
        rows={possible.map((s) => ({
          id: s.id,
          name: `${s.name} (${data.stocks.find((st) => st.variantId === variant && st.shelfId === s.id)?.quantity || 0} kom)`,
        }))}
        onChange={(e) => setShelf(e.target.value)}
        required
      />
      {move && (
        <Select
          label="Na policu"
          name="to"
          required
          rows={shelves.filter((s) => s.id !== source)}
        />
      )}
      <Field
        label="Broj pakiranja"
        name="quantity"
        type="number"
        inputMode="numeric"
        min="1"
        step="1"
        defaultValue="1"
        required
      />
      {!possible.length && (
        <p className="error">Nema dostupne police s ovom zalihom.</p>
      )}
    </Form>
  );
}
export function ProductEditor({ product, initial = {}, initialShelf = "" }) {
  const { data, api, close, open, pantry, owner } = useApp();
  const categories = sorted(active(data.categories)),
    shelves = sorted(active(data.shelves));
  const [name, setName] = useState(product?.name || initial.name || ""),
    [manufacturer, setManufacturer] = useState(initial.manufacturer || ""),
    [amount, setAmount] = useState(""),
    [unit, setUnit] = useState("UNKNOWN"),
    [photo, setPhoto] = useState(null),
    [photoError, setPhotoError] = useState(""),
    [recognizing, setRecognizing] = useState(false),
    [keepPhoto, setKeepPhoto] = useState(true),
    [group, setGroup] = useState("");
  const stage = useRef({
    id: product?.id || crypto.randomUUID(),
    variantId: crypto.randomUUID(),
    saved: false,
    stock: false,
  });
  const matches = groupingMatches(name, data.products, data.synonymRules);
  return (
    <Form
      cancel={close}
      onSubmit={async (f) => {
        const s = stage.current;
        const categoryId = String(f.get("categoryId"));
        const minimumMode = f.get("minimumMode");
        const minimum = integer(f.get("minimum"), 0, 1000000000000);
        const p = {
          ...product,
          id: s.id,
          name,
          categoryId,
          minimumQuantity: minimumMode === "PACKAGES" ? minimum : 0,
          minimumMode,
          minimumAmountBase: minimum,
          autoShopping: f.get("auto") === "on",
        };
        const v = {
          id: s.variantId,
          productId: group || s.id,
          displayName: f.get("displayName") || name,
          manufacturer,
          barcode: f.get("barcode") || null,
          packageAmountBase: packageBase(amount, unit),
          packageUnit: amount ? unit : "UNKNOWN",
          packageLabel: f.get("packageLabel") || "",
          description: f.get("description") || "",
          minimumPackages:
            f.get("variantMinimum") === ""
              ? null
              : integer(f.get("variantMinimum") || 0),
          photoSource: initial.photoSource || "NONE",
          photoUri: initial.photoUrl || null,
        };
        if (!s.saved) {
          if (group && !product) {
            await api.mutate(
              "upsert_variant",
              v.id,
              { variant: v },
              0,
              "VARIANT",
            );
          } else
            await api.mutate(
              "upsert_product",
              s.id,
              { product: p, ...(!product ? { initialVariant: v } : {}) },
              product?.revision || 0,
            );
          s.saved = true;
        }
        if (!product && !s.stock) {
          const n = integer(f.get("quantity"));
          if (n)
            await api.mutate("adjust_stock", group || s.id, {
              productId: group || s.id,
              variantId: v.id,
              shelfId: f.get("shelf"),
              delta: n,
            });
          s.stock = true;
          localStorage.setItem(
            `smocnica-last-shelf:${pantry.id}`,
            String(f.get("shelf")),
          );
        }
        if (!product && photo && keepPhoto) {
          const uri = await api.upload(v.id, photo);
          const latest = await api.record("variants", v.id);
          await api.mutate(
            "upsert_variant",
            v.id,
            { variant: { ...latest, photoUri: uri, photoSource: "CAMERA" } },
            latest.revision,
            "VARIANT",
          );
        }
        close();
        open(name, <ProductDetail productId={group || s.id} />);
      }}
    >
      <Field
        label="Naziv artikla"
        value={name}
        onChange={(e) => setName(e.target.value)}
        required
        maxLength={100}
      />
      {!product && (
        <>
          <Field
            label="Proizvođač"
            value={manufacturer}
            onChange={(e) => setManufacturer(e.target.value)}
            maxLength={100}
          />
          <div className="row">
            <Field
              label="Veličina pakiranja"
              value={amount}
              onChange={(e) => {
                setAmount(e.target.value);
                if (unit === "UNKNOWN") setUnit("G");
              }}
              inputMode="decimal"
            />
            <Select
              label="Jedinica"
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
              rows={unitRows}
            />
          </div>
          <Select
            label="Polica"
            name="shelf"
            required
            rows={shelves}
            defaultValue={
              shelves.find(
                (s) =>
                  s.id ===
                  (initialShelf ||
                    localStorage.getItem(`smocnica-last-shelf:${pantry.id}`)),
              )?.id
            }
          />
          <Field
            label="Broj pakiranja"
            name="quantity"
            type="number"
            min="0"
            step="1"
            defaultValue="1"
            required
          />
          <label className="photo-upload">
            <Camera />
            <span>Dodaj fotografiju</span>
            <small>Snimi ili odaberi iz galerije</small>
            <input
              type="file"
              accept="image/*"
              onChange={async (e) => {
                try {
                  if (e.target.files[0])
                    setPhoto(await compressPhoto(e.target.files[0]));
                  setPhotoError("");
                } catch (err) {
                  setPhotoError(err.message);
                }
              }}
            />
          </label>
          {photo && (
            <>
              <label className="check">
                <input
                  type="checkbox"
                  checked={keepPhoto}
                  onChange={(e) => setKeepPhoto(e.target.checked)}
                />
                Prikaži fotografiju na kartici
              </label>
              <button
                type="button"
                disabled={recognizing}
                onClick={async () => {
                  setRecognizing(true);
                  try {
                    const base64 = await new Promise((resolve, reject) => {
                      const r = new FileReader();
                      r.onload = () => resolve(r.result.split(",")[1]);
                      r.onerror = reject;
                      r.readAsDataURL(photo);
                    });
                    const suggestion = await api.call("recognizeProductPhoto", {
                      pantryId: pantry.id,
                      photoBase64: base64,
                    });
                    setName(suggestion.name);
                    setManufacturer(suggestion.manufacturer);
                    setAmount(suggestion.packageAmount);
                    setUnit(suggestion.packageUnit || "UNKNOWN");
                    setPhotoError(
                      "Prijedlog je spreman. Provjerite podatke prije spremanja.",
                    );
                  } catch (e) {
                    setPhotoError(e.message);
                  } finally {
                    setRecognizing(false);
                  }
                }}
              >
                {recognizing ? "Prepoznajem…" : "Prepoznaj s fotografije"}
              </button>
              <p className="muted small">Za prepoznavanje se fotografija šalje Google Geminiju. Provjerite prijedlog prije spremanja.</p>
            </>
          )}
          {photoError && <p role="status">{photoError}</p>}
          {matches.length > 0 && (
            <Select
              label="Grupiranje (samo uz vaš odabir)"
              value={group}
              onChange={(e) => setGroup(e.target.value)}
              rows={[
                { id: "", name: "Novi zasebni artikl" },
                ...matches.map((p) => ({
                  id: p.id,
                  name: `Nova varijanta: ${p.name}`,
                })),
              ]}
            />
          )}
        </>
      )}
      <details open={!!product}>
        <summary>Dodatne postavke</summary>
        <Select
          label="Kategorija"
          name="categoryId"
          defaultValue={
            product?.categoryId || categories.find((c) => c.isDefault)?.id
          }
          rows={categories}
        />
        <Select
          label="Minimum se izražava u"
          name="minimumMode"
          defaultValue={product?.minimumMode || "PACKAGES"}
          rows={[
            { id: "PACKAGES", name: "Pakiranjima" },
            { id: "MASS_MG", name: "Miligramima" },
            { id: "VOLUME_ML", name: "Mililitrima" },
            { id: "COUNT", name: "Komadima sadržaja" },
          ]}
        />
        <Field
          label="Minimalna zaliha"
          name="minimum"
          type="number"
          min="0"
          defaultValue={product?.minimumAmountBase || 0}
          required
        />
        <label className="check">
          <input
            type="checkbox"
            name="auto"
            defaultChecked={product?.autoShopping !== false}
          />
          Automatski dodaj na popis za kupnju
        </label>
        {!product && (
          <>
            <Field label="Naziv varijante" name="displayName" />
            <Field
              label="Oznaka pakiranja"
              name="packageLabel"
              defaultValue={initial.packageLabel || ""}
            />
            <Field
              label="Barkod"
              name="barcode"
              inputMode="numeric"
              defaultValue={initial.barcode || ""}
            />
            <Field label="Opis" name="description" maxLength={500} />
            <Field
              label="Minimum varijante (pakiranja)"
              name="variantMinimum"
              type="number"
              min="0"
            />
          </>
        )}
      </details>
      <p className="muted small">
        Promjene se prikazuju nakon potvrde poslužitelja.
        {!product
          ? " Artikl, početna zaliha i fotografija spremaju se redom. Ako korak ne uspije, dovršite ga prije zatvaranja obrasca."
          : ""}
      </p>
    </Form>
  );
}
const unitRows = [
  ["UNKNOWN", "Nije poznato"],
  ["G", "g"],
  ["KG", "kg"],
  ["ML", "ml"],
  ["L", "l"],
  ["PIECE", "kom"],
  ["ROLL", "rola"],
  ["BAG", "vrećica"],
  ["CAPSULE", "kapsula"],
].map(([id, name]) => ({ id, name }));
export function VariantEditor({ variant, productId }) {
  const { api, close } = useApp();
  const [photo, setPhoto] = useState(null);
  return (
    <Form
      cancel={close}
      onSubmit={async (f) => {
        const id = variant?.id || crypto.randomUUID();
        const unit = f.get("unit"),
          amount = f.get("amount");
        const v = {
          ...variant,
          id,
          productId: variant?.productId || productId,
          displayName: f.get("name"),
          manufacturer: f.get("manufacturer"),
          barcode: f.get("barcode") || null,
          packageAmountBase: packageBase(amount, unit),
          packageUnit: amount ? unit : "UNKNOWN",
          packageLabel: f.get("label"),
          description: f.get("description"),
          minimumPackages:
            f.get("minimum") === "" ? null : integer(f.get("minimum")),
          photoUri: variant?.photoUrl || null,
          photoSource: variant?.photoSource || "NONE",
        };
        await api.mutate(
          "upsert_variant",
          id,
          { variant: v },
          variant?.revision || 0,
          "VARIANT",
        );
        if (photo) {
          const uri = await api.upload(id, photo);
          const latest = await api.record("variants", id);
          await api.mutate(
            "upsert_variant",
            id,
            { variant: { ...latest, photoUri: uri, photoSource: "GALLERY" } },
            latest.revision,
            "VARIANT",
          );
        }
        close();
      }}
    >
      <Field
        label="Naziv varijante"
        name="name"
        defaultValue={variant?.displayName || ""}
        required
        maxLength={100}
      />
      <Field
        label="Proizvođač"
        name="manufacturer"
        defaultValue={variant?.manufacturer || ""}
      />
      <Field
        label="Barkod"
        name="barcode"
        defaultValue={variant?.barcode || ""}
        inputMode="numeric"
      />
      <div className="row">
        <Field
          label="Veličina"
          name="amount"
          defaultValue={
            variant?.packageAmountBase
              ? variant.packageAmountBase /
                ({ KG: 1000000, G: 1000, L: 1000 }[variant.packageUnit] || 1)
              : ""
          }
          inputMode="decimal"
        />
        <Select
          label="Jedinica"
          name="unit"
          rows={unitRows}
          defaultValue={variant?.packageUnit || "G"}
        />
      </div>
      <Field
        label="Oznaka pakiranja"
        name="label"
        defaultValue={variant?.packageLabel || ""}
      />
      <Field
        label="Opis"
        name="description"
        defaultValue={variant?.description || ""}
      />
      <Field
        label="Minimum u pakiranjima"
        name="minimum"
        type="number"
        min="0"
        defaultValue={variant?.minimumPackages ?? ""}
      />
      <Field
        label="Nova fotografija"
        type="file"
        accept="image/*"
        onChange={async (e) => {
          if (e.target.files[0])
            setPhoto(await compressPhoto(e.target.files[0]));
        }}
      />
    </Form>
  );
}
function Grouping({ variant, product }) {
  const { data, api, close } = useApp();
  return (
    <>
      <Form
        submit="Premjesti u artikl"
        onSubmit={async (f) => {
          await api.mutate(
            "move_variant",
            variant.id,
            {
              variantId: variant.id,
              fromProductId: product.id,
              toProductId: f.get("target"),
              expectedGroupingRevision: product.groupingRevision || 0,
            },
            variant.revision,
            "VARIANT",
          );
          close();
        }}
      >
        <Select
          label="Ciljni artikl"
          name="target"
          required
          rows={active(data.products).filter((p) => p.id !== product.id)}
        />
        <p>Zalihe ove varijante prelaze u odabrani artikl.</p>
      </Form>
      <hr />
      <Form
        submit="Izdvoji u novi artikl"
        onSubmit={async (f) => {
          await api.mutate(
            "split_variant",
            variant.id,
            {
              variantId: variant.id,
              fromProductId: product.id,
              expectedGroupingRevision: product.groupingRevision || 0,
              newProduct: {
                id: crypto.randomUUID(),
                name: f.get("name"),
                categoryId: product.categoryId,
              },
            },
            variant.revision,
            "VARIANT",
          );
          close();
        }}
      >
        <Field label="Naziv novog artikla" name="name" required />
      </Form>
    </>
  );
}
function BulkActions({ ids, done }) {
  const { api, data, close } = useApp();
  return (
    <>
      <Form
        submit="Promijeni kategoriju"
        onSubmit={async (f) => {
          await api.mutate("bulk_change_product_category", ids[0], {
            productIds: ids,
            categoryId: f.get("category"),
          });
          done();
          close();
        }}
      >
        <p>{ids.length} artikala</p>
        <Select
          label="Kategorija"
          name="category"
          rows={active(data.categories)}
        />
      </Form>
      <Form
        submit="Premjesti zalihu"
        onSubmit={async (f) => {
          await api.mutate("bulk_move_stock", ids[0], {
            moves: data.stocks
              .filter(
                (s) =>
                  ids.includes(s.productId) &&
                  s.shelfId === f.get("from") &&
                  s.quantity > 0 &&
                  data.variants.some(
                    (v) => v.id === s.variantId && !v.deletedAt,
                  ),
              )
              .map((s) => ({
                productId: s.productId,
                variantId: s.variantId,
                fromShelfId: s.shelfId,
                toShelfId: f.get("to"),
                quantity: s.quantity,
              })),
          });
          done();
          close();
        }}
      >
        <Select label="S police" name="from" rows={active(data.shelves)} />
        <Select label="Na policu" name="to" rows={active(data.shelves)} />
      </Form>
      <Form
        destructive
        submit="Premjesti odabrano u koš"
        onSubmit={async () => {
          await api.mutate("bulk_delete_products", ids[0], { productIds: ids });
          done();
          close();
        }}
      >
        <p>Odabrani artikli mogu se vratiti tijekom 30 dana.</p>
      </Form>
    </>
  );
}
