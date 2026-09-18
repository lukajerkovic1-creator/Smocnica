import React, { useEffect, useRef, useState } from "react";
import { Camera, Flashlight } from "lucide-react";
import { Field, Form, useApp } from "./ui";
import { ProductEditor, ProductDetail } from "./Inventory";
export default function Scanner({ onFound, qr = false }) {
  const video = useRef(),
    controls = useRef(),
    stopped = useRef(false);
  const [error, setError] = useState(""),
    [running, setRunning] = useState(false),
    [torch, setTorch] = useState(false);
  useEffect(
    () => () => {
      stopped.current = true;
      controls.current?.stop();
    },
    [],
  );
  async function start() {
    setError("");
    try {
      const [{ BrowserMultiFormatReader }, { DecodeHintType, BarcodeFormat }] =
        await Promise.all([import("@zxing/browser"), import("@zxing/library")]);
      if (stopped.current) return;
      const hints = new Map([
        [
          DecodeHintType.POSSIBLE_FORMATS,
          qr
            ? [BarcodeFormat.QR_CODE]
            : [
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
              ],
        ],
      ]);
      const reader = new BrowserMultiFormatReader(hints);
      setRunning(true);
      let found = false;
      const control = await reader.decodeFromConstraints(
        { video: { facingMode: { ideal: "environment" } }, audio: false },
        video.current,
        (result, err, scanner) => {
          if (result && !found && !stopped.current) {
            found = true;
            scanner.stop();
            setRunning(false);
            Promise.resolve(onFound(result.getText())).catch((e) =>
              setError(e.message || "Kod nije moguće obraditi."),
            );
          }
        },
      );
      if (stopped.current) control.stop();
      else controls.current = control;
    } catch (e) {
      setRunning(false);
      setError(
        "Kamera nije dostupna. Dopustite kameru u postavkama Safarija ili upišite kod ručno.",
      );
    }
  }
  return (
    <section className="scanner">
      <p>
        {qr
          ? "Usmjerite kameru prema pozivnom QR-kodu."
          : "Usmjerite kameru prema barkodu na ambalaži."}
      </p>
      <video ref={video} muted playsInline aria-label="Prikaz kamere" />
      <div className="row">
        <button disabled={running} onClick={start}>
          <Camera />
          Uključi kameru
        </button>
        {running && (
          <button
            aria-label="Bljeskalica"
            onClick={async () => {
              try {
                const track = video.current.srcObject.getVideoTracks()[0];
                await track.applyConstraints({ advanced: [{ torch: !torch }] });
                setTorch(!torch);
              } catch {
                setError("Ova kamera ne podržava bljeskalicu.");
              }
            }}
          >
            <Flashlight />
          </button>
        )}
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <Form
        submit="Pronađi"
        onSubmit={async (f) => {
          controls.current?.stop();
          await onFound(String(f.get("code")).trim());
        }}
      >
        <Field
          label={qr ? "Pozivni kod" : "Barkod"}
          name="code"
          inputMode={qr ? "text" : "numeric"}
          pattern={qr ? undefined : "[0-9]{8}|[0-9]{12}|[0-9]{13}"}
          required
        />
      </Form>
    </section>
  );
}
export function ScanFlow({ initialShelf = "" }) {
  const app = useApp(),
    [found, setFound] = useState(null),
    [loading, setLoading] = useState(false),
    [error, setError] = useState("");
  const known = found?.barcode && app.data.variants.find(
    (v) => !v.deletedAt && v.barcode === found?.barcode,
  );
  if (known)
    return (
      <ProductDetail productId={known.productId} selectedVariant={known.id} />
    );
  if (found)
    return <ProductEditor initial={found} initialShelf={initialShelf} />;
  return (
    <>
      {loading ? (
        <p role="status">Tražim proizvod…</p>
      ) : (
        <Scanner
          onFound={async (code) => {
            setLoading(true);
            setError("");
            try {
              const exists = app.data.variants.some(
                (v) => !v.deletedAt && v.barcode === code,
              );
              if (exists) {
                setFound({ barcode: code });
                return;
              }
              let initial = { barcode: code };
              try {
                const response = await fetch(
                  `https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(code)}.json?fields=product_name,product_name_hr,brands,quantity,image_front_url&app_name=Smocnica&app_version=1.0&app_contact=https%3A%2F%2Fgithub.com%2Flukajerkovic1-creator%2FSmocnica`,
                  { signal: AbortSignal.timeout(8000) },
                );
                if (response.ok) {
                  const body = await response.json();
                  if (body.status === 1) {
                    const p = body.product;
                    initial = {
                      ...initial,
                      name: p.product_name_hr || p.product_name || "",
                      manufacturer: p.brands || "",
                      packageLabel: p.quantity || "",
                      photoUrl: p.image_front_url || null,
                      photoSource: p.image_front_url
                        ? "OPEN_FOOD_FACTS"
                        : "NONE",
                    };
                  }
                }
              } catch {
                setError(
                  "Javni katalog nije dostupan. Podatke možete unijeti ručno.",
                );
              }
              setFound(initial);
            } finally {
              setLoading(false);
            }
          }}
        />
      )}
      {error && <p role="status">{error}</p>}
      {!loading&&<button onClick={()=>setFound({})}>Unesi artikl bez barkoda</button>}
    </>
  );
}
export async function compressPhoto(file) {
  if (file.size > 25 * 1024 * 1024)
    throw new Error("Odaberite fotografiju manju od 25 MB.");
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    const scale = Math.min(1, 1600 / Math.max(img.width, img.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(img.width * scale);
    canvas.height = Math.round(img.height * scale);
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((r) => canvas.toBlob(r, "image/jpeg", 0.85));
    if (!blob || blob.size > 5 * 1024 * 1024)
      throw new Error("Fotografija je prevelika.");
    return blob;
  } finally {
    URL.revokeObjectURL(url);
  }
}
