import React, {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { X, Package, Share, LoaderCircle } from "lucide-react";
import { errorText } from "./outbox";
import { iconDataUrl, suggestIcon } from "./product-icons";
export const Context = createContext(null);
export const useApp = () => useContext(Context);
export function Field({ label, children, ...props }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children || <input {...props} />}
    </label>
  );
}
export function Select({ label, rows, ...props }) {
  return (
    <Field label={label}>
      <select {...props}>
        {rows.map((r) => (
          <option key={r.id} value={r.id}>
            {r.name}
          </option>
        ))}
      </select>
    </Field>
  );
}
export function Form({
  children,
  onSubmit,
  submit = "Spremi",
  cancel,
  destructive = false,
  submitDisabled = false,
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        setError("");
        try {
          await onSubmit(new FormData(e.currentTarget));
        } catch (e) {
          setError(errorText(e));
        } finally {
          setBusy(false);
        }
      }}
    >
      <fieldset disabled={busy}>
        {children}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button
          className={destructive ? "danger primary" : "primary"}
          type="submit"
          disabled={submitDisabled}
        >
          {busy ? (
            <>
              <LoaderCircle className="spin" /> Spremanje…
            </>
          ) : (
            submit
          )}
        </button>
        {cancel && (
          <button type="button" onClick={cancel}>
            Odustani
          </button>
        )}
      </fieldset>
    </form>
  );
}
export function Sheet({ title, children, close }) {
  const ref = useRef();
  useEffect(() => {
    const d = ref.current;
    d.showModal();
    return () => d.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="sheet"
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      onClick={(e) => {
        if (e.target === ref.current) close();
      }}
    >
      <div className="sheet-inner">
        <div className="grab" />
        <header>
          <h2>{title}</h2>
          <button className="icon" aria-label="Zatvori" onClick={close}>
            <X />
          </button>
        </header>
        {children}
      </div>
    </dialog>
  );
}
export function Empty({ children, icon: Icon = Package }) {
  return (
    <div className="empty">
      <Icon size={38} />
      <p>{children}</p>
    </div>
  );
}
export function InstallHelp() {
  return (
    <aside className="install">
      <Share />
      <div>
        <strong>Dodajte Smočnicu na početni zaslon</strong>
        <p>
          U Safariju otvorite dijeljenje i odaberite „Dodaj na početni zaslon”.
        </p>
      </div>
    </aside>
  );
}
export function Photo({ variant, name = "" }) {
  const { api } = useApp();
  const [src, setSrc] = useState(null);
  useEffect(() => {
    let live = true,
      url;
    setSrc(null);
    if (variant?.photoUrl)
      api
        .photo(variant.photoUrl)
        .then((value) => {
          url = value;
          if (live) setSrc(value);
          else if (value.startsWith("blob:")) URL.revokeObjectURL(value);
        })
        .catch(() => setSrc(null));
    return () => {
      live = false;
      if (url?.startsWith("blob:")) URL.revokeObjectURL(url);
    };
  }, [api, variant?.photoUrl, variant?.updatedAt]);
  return src ? (
    <img
      className="product-photo"
      src={src}
      alt=""
      onError={() => setSrc(null)}
    />
  ) : (
    <img className="product-photo" src={iconDataUrl(suggestIcon(`${name} ${variant?.displayName || ""}`))} alt="" />
  );
}
export function download(name, body, type) {
  const url = URL.createObjectURL(new Blob([body], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
