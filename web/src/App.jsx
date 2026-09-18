import React, { useEffect, useRef, useState } from "react";
import {
  Menu,
  Package,
  ShoppingCart,
  ScanBarcode,
  ClipboardCheck,
  History,
  Trash2,
  Settings,
  LogOut,
  X,
  RefreshCw,
  Check,
  AlertCircle,
} from "lucide-react";
import { Context, Sheet, Form, Field, InstallHelp } from "./ui";
import { errorText } from "./outbox";
import Inventory from "./Inventory";
import {
  Shopping,
  Audit,
  HistoryScreen,
  TrashScreen,
  SettingsScreen,
} from "./Management";
import Scanner, { ScanFlow } from "./Scanner";
const blank = () =>
  Object.fromEntries(
    [
      "shelves",
      "categories",
      "products",
      "variants",
      "stocks",
      "shoppingItems",
      "members",
      "synonymRules",
      "activities",
    ].map((k) => [k, []]),
  );
const pages = [
  ["inventory", "Svi artikli", Package],
  ["shopping", "Popis za kupnju", ShoppingCart],
  ["scan", "Skeniraj barkod", ScanBarcode],
  ["audit", "Inventura", ClipboardCheck],
  ["history", "Povijest", History],
  ["trash", "Koš", Trash2],
  ["settings", "Postavke", Settings],
];
export default function App({ api }) {
  const [user, setUser] = useState(null),
    [phase, setPhase] = useState("loading"),
    [data, setData] = useState(blank),
    [pantry, setPantry] = useState(null),
    [page, setPage] = useState(
      location.hash === "#shopping" ? "shopping" : "inventory",
    ),
    [menu, setMenu] = useState(false),
    [modal, setModal] = useState(null),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [pending, setPending] = useState([]),
    [online, setOnline] = useState(navigator.onLine),
    [busy, setBusy] = useState(false);
  const session = useRef(0),
    stop = useRef(null);
  const clear = () => {
    stop.current?.();
    stop.current = null;
    setData(blank());
    setPantry(null);
    setModal(null);
    setMenu(false);
    setNotice("");
  };
  const connect = async (generation) => {
    const result = await api.initialize();
    if (generation !== session.current) return;
    const entry = result.pantries[0];
    if (!entry) {
      setPhase("onboarding");
      return;
    }
    setPantry(entry.pantry);
    let received = new Set();
    stop.current = api.subscribe(
      entry.pantry.id,
      (name, value) => {
        if (generation !== session.current) return;
        if (name === "pantry") setPantry(value);
        else {
          setData((d) => ({ ...d, [name]: value }));
          received.add(name);
          if (received.size === 9) setPhase("ready");
        }
      },
      (e) => {
        if (generation === session.current) {
          clear();
          setError(errorText(e));
          setPhase("error");
        }
      },
    );
    await api.outbox.flush();
  };
  useEffect(() => {
    const off = api.observeAuth((u) => {
      const generation = ++session.current;
      clear();
      setUser(u);
      setError("");
      if (!u) {
        setPhase("login");
        return;
      }
      setPhase("loading");
      connect(generation).catch((e) => {
        if (generation === session.current) {
          setError(errorText(e));
          setPhase("error");
        }
      });
    });
    return () => {
      ++session.current;
      off();
      stop.current?.();
    };
  }, [api]);
  useEffect(() => {
    const refresh = () =>
      api.outbox
        .pending()
        .then(setPending)
        .catch((e) => setError(errorText(e)));
    const network = () => setOnline(navigator.onLine);
    refresh();
    window.addEventListener("smocnica-outbox", refresh);
    window.addEventListener("online", network);
    window.addEventListener("offline", network);
    const hash = () => {
      if (location.hash === "#shopping") setPage("shopping");
    };
    window.addEventListener("hashchange", hash);
    return () => {
      window.removeEventListener("smocnica-outbox", refresh);
      window.removeEventListener("online", network);
      window.removeEventListener("offline", network);
      window.removeEventListener("hashchange", hash);
    };
  }, [user]);
  useEffect(() => {
    const theme = localStorage.getItem("smocnica-theme") || "system";
    document.documentElement.dataset.theme = theme;
  }, []);
  const run = async (work) => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      return await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const close = () => setModal(null),
    open = (title, content) => setModal({ title, content });
  const confirm = (title, message, work) =>
    open(
      title,
      <Form
        cancel={close}
        submit="Potvrdi"
        destructive
        onSubmit={async () => {
          await work();
          close();
        }}
      >
        <p>{message}</p>
      </Form>,
    );
  const context = {
    api,
    data,
    pantry,
    user,
    owner: pantry?.ownerUid === user?.uid,
    open,
    close,
    confirm,
    run,
    notify: setNotice,
    setPage,
    pending,
  };
  const reload = () => {
    clear();
    setError("");
    setPhase("loading");
    connect(++session.current).catch((e) => {
      setError(errorText(e));
      setPhase("error");
    });
  };
  return (
    <Context.Provider value={context}>
      <div className="app">
        {phase === "login" || phase === "loading" || phase === "error" ? (
          <main className="welcome">
            <img
              src={`${import.meta.env.BASE_URL}icon.svg`}
              alt=""
              className="brand"
            />
            <h1>Smočnica</h1>
            <p className="subtitle">Vaša smočnica, na svim uređajima.</p>
            {phase === "loading" ? (
              <p role="status">Otvaranje smočnice…</p>
            ) : phase === "login" ? (
              <button
                className="google"
                disabled={busy}
                onClick={() => run(() => api.login())}
              >
                <span className="google-g">G</span> Nastavi s Googleom
              </button>
            ) : (
              <>
                <button className="primary" onClick={reload}>
                  Pokušaj ponovno
                </button>
                <button onClick={() => run(() => api.logout())}>
                  Odjavi se
                </button>
              </>
            )}
            <InstallHelp />
            <p className="privacy">
              Podaci su dostupni članovima vaše smočnice.
              <br />
              <a href={`${import.meta.env.BASE_URL}privacy-policy.html`}>
                Privatnost
              </a>
            </p>
          </main>
        ) : phase === "onboarding" ? (
          <main className="onboarding">
            <h1>Dobro došli</h1>
            <p>Pridružite se zajedničkoj smočnici ili napravite novu.</p>
            <button
              onClick={() =>
                open(
                  "Skeniraj pozivni kod",
                  <Scanner
                    qr
                    onFound={async (code) => {
                      await api.call("joinPantry", { code });
                      reload();
                    }}
                  />,
                )
              }
            >
              Skeniraj pozivni QR-kod
            </button>
            <Form
              submit="Pridruži se"
              onSubmit={async (f) => {
                await api.call("joinPantry", { code: f.get("code") });
                reload();
              }}
            >
              <Field
                label="Pozivni kod"
                name="code"
                required
                maxLength={32}
                autoCapitalize="characters"
              />
            </Form>
            <hr />
            <Form
              submit="Izradi smočnicu"
              onSubmit={async (f) => {
                const id =
                  localStorage.getItem(`smocnica-create-${user.uid}`) ||
                  crypto.randomUUID();
                localStorage.setItem(`smocnica-create-${user.uid}`, id);
                await api.call("createPantry", {
                  name: f.get("name"),
                  requestId: id,
                });
                reload();
              }}
            >
              <Field
                label="Naziv nove smočnice"
                name="name"
                required
                maxLength={60}
                defaultValue="Naša smočnica"
              />
            </Form>
            <button onClick={() => run(() => api.logout())}>Odjavi se</button>
          </main>
        ) : (
          <>
            <header className="topbar">
              <button
                className="icon"
                aria-label="Otvori izbornik"
                onClick={() => setMenu(true)}
              >
                <Menu />
              </button>
              <h1>
                {page === "inventory"
                  ? "Smočnica"
                  : pages.find((p) => p[0] === page)?.[1]}
              </h1>
              <span className="header-spacer" />
            </header>
            <div
              className={`sync ${pending.length || !online ? "warning" : ""}`}
              role="status"
            >
              {pending.length ? (
                <>
                  <AlertCircle size={14} />
                  {pending.length} promjena čeka potvrdu{" "}
                  <button
                    onClick={() => open("Nepotvrđene promjene", <Pending />)}
                  >
                    Pregledaj
                  </button>
                </>
              ) : !online ? (
                "Nema internetske veze"
              ) : (
                <>
                  <Check size={14} /> Povezano · {pantry.name}
                </>
              )}
            </div>
            <main aria-busy={busy}>
              {page === "inventory" ? (
                <Inventory />
              ) : page === "shopping" ? (
                <Shopping />
              ) : page === "scan" ? (
                <ScanFlow />
              ) : page === "audit" ? (
                <Audit />
              ) : page === "history" ? (
                <HistoryScreen />
              ) : page === "trash" ? (
                <TrashScreen />
              ) : (
                <SettingsScreen reload={reload} />
              )}
            </main>
            {menu && (
              <Sheet title="Smočnica" close={() => setMenu(false)}>
                <nav>
                  {pages.map(([id, label, Icon]) => (
                    <button
                      key={id}
                      className={page === id ? "nav active" : "nav"}
                      onClick={() => {
                        setPage(id);
                        setMenu(false);
                        setModal(null);
                      }}
                    >
                      <Icon />
                      {label}
                    </button>
                  ))}
                  <hr />
                  <button
                    className="nav"
                    onClick={() => run(() => api.logout())}
                  >
                    <LogOut />
                    Odjavi se
                  </button>
                </nav>
              </Sheet>
            )}
          </>
        )}
        {error && (
          <div className="toast error" role="alert">
            <span>{error}</span>
            <button
              className="icon"
              aria-label="Zatvori poruku"
              onClick={() => setError("")}
            >
              <X />
            </button>
          </div>
        )}
        {notice && (
          <div className="toast" role="status">
            <span>{notice}</span>
            <button
              className="icon"
              aria-label="Zatvori poruku"
              onClick={() => setNotice("")}
            >
              <X />
            </button>
          </div>
        )}
        {modal && (
          <Sheet title={modal.title} close={close}>
            {modal.content}
            {error && (
              <p role="alert" className="error">
                {error}
              </p>
            )}
            {notice && <p role="status">{notice}</p>}
          </Sheet>
        )}
      </div>
    </Context.Provider>
  );
}
function Pending() {
  const { pending, api, run, close } = React.useContext(Context);
  return (
    <>
      <p>Dok ne razriješite prethodnu promjenu, nove se neće slati.</p>
      {pending.map((p) => (
        <article key={p.operationId}>
          <strong>
            {p.state === "CONFLICT"
              ? "Podaci su promijenjeni na drugom uređaju"
              : p.state === "FAILED"
                ? "Promjena je odbijena"
                : "Čeka se potvrda"}
          </strong>
          <p>{p.error}</p>
          {p.state !== "PENDING" && (
            <button
              onClick={() =>
                run(async () => {
                  await api.outbox.discard(p.operationId);
                  close();
                })
              }
            >
              Prihvati stanje poslužitelja
            </button>
          )}
        </article>
      ))}
      <button
        onClick={() =>
          run(async () => {
            await api.outbox.flush();
            close();
          })
        }
      >
        <RefreshCw />
        Ponovno provjeri
      </button>
      <p>
        Za zadržavanje svoje izmjene nakon konflikta prihvatite novo stanje,
        pregledajte podatke i ponovno unesite izmjenu.
      </p>
    </>
  );
}
