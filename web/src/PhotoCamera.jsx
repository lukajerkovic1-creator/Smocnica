import React, { useEffect, useRef, useState } from "react";
import { compressPhoto } from "./Scanner";

export default function PhotoCamera({ onPhoto, cancel, additional = false }) {
  const video = useRef(null), stream = useRef(null);
  const [ready, setReady] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const media = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } } });
        if (!live) { media.getTracks().forEach(track => track.stop()); return; }
        stream.current = media; video.current.srcObject = media;
        await video.current.play();
        if (live) setReady(true);
      } catch { if (live) setError("Kamera nije dostupna. Dopustite kameru u Safariju ili upotrijebite rezervni odabir fotografije."); }
    })();
    return () => { live = false; stream.current?.getTracks().forEach(track => track.stop()); };
  }, []);
  async function capture() {
    if (!ready || busy) return;
    setBusy(true); setError("");
    try {
      const source = video.current, canvas = document.createElement("canvas");
      if (!source.videoWidth || !source.videoHeight) throw new Error("Pričekajte prikaz kamere pa pokušajte ponovno.");
      const scale = Math.min(1, 1600 / Math.max(source.videoWidth, source.videoHeight));
      canvas.width = Math.round(source.videoWidth * scale); canvas.height = Math.round(source.videoHeight * scale);
      canvas.getContext("2d").drawImage(source, 0, 0, canvas.width, canvas.height);
      const photo = await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", .85));
      if (!photo) throw new Error("Fotografiju nije moguće snimiti. Pokušajte ponovno.");
      await onPhoto(photo);
    } catch(e) { setError(e.message); setBusy(false); }
  }
  return <section className="photo-camera">
    <p>{additional ? "Približite stranu s gramažom ili volumenom." : "Usmjerite kameru prema prednjoj strani pakiranja."}</p>
    <video ref={video} muted playsInline aria-label="Prikaz fotoaparata" />
    <button className="primary" disabled={!ready || busy} onClick={capture}>{busy ? "Snimanje…" : "Snimi fotografiju"}</button>
    {error && <p role="alert">{error}</p>}
    <details><summary>Drugi način fotografiranja</summary>
      <label className="capture-button">Kamera ili galerija
        <input type="file" accept="image/*" capture="environment" disabled={busy} onChange={async e => {
          if (!e.target.files[0]) return;
          setBusy(true);
          try { await onPhoto(await compressPhoto(e.target.files[0])); }
          catch(e) { setError(e.message); setBusy(false); }
        }} />
      </label>
    </details>
    <p className="muted small">Fotografija se šalje Google Geminiju radi prepoznavanja. Podatke potvrđujete prije dodavanja.</p>
    <button disabled={busy} onClick={cancel}>Nastavi bez fotografiranja</button>
  </section>;
}
