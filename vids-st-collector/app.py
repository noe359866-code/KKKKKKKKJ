"""Web para recolectar videos de cualquier web y almacenarlos en vids.st.

Endpoints:
  GET  /            - formulario principal
  POST /collect     - extrae el video y lo sube a vids.st
  GET  /health      - estado del servicio
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List

import requests
from dotenv import load_dotenv
from flask import (
    Flask,
    flash,
    redirect,
    render_template_string,
    request,
    url_for,
)

from collector import extract_video_url, upload_to_vids_st

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)
HISTORY_FILE = DATA_DIR / "history.json"

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "cambia-esta-clave-en-produccion")

VIDS_ST_API_KEY = os.environ.get("VIDS_ST_API_KEY", "")
VIDS_ST_API_BASE = os.environ.get("VIDS_ST_API_BASE", "https://vids.st/api/")
VIDS_ST_ACTION = os.environ.get("VIDS_ST_ACTION", "remote_upload")

LAYOUT_CSS = """
* { box-sizing: border-box; }
body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Arial, sans-serif;
       max-width: 720px; margin: 40px auto; padding: 0 16px; color: #1f2937;
       background: #f8fafc; line-height: 1.5; }
h1 { font-size: 1.5rem; margin: 0 0 8px; }
p.sub { color: #6b7280; font-size: 0.95rem; margin-bottom: 24px; }
.card { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
label { display: block; font-weight: 600; font-size: 0.9rem; margin: 18px 0 6px; color: #374151; }
input[type=text], input[type=url], input[type=password] {
    width: 100%; padding: 10px 12px; border: 1px solid #cbd5e1; border-radius: 8px;
    font-size: 1rem; }
input[type=text]:focus { outline: none; border-color: #2563eb; ring: 2px; }
button { margin-top: 24px; background: #2563eb; color: #fff; border: none; border-radius: 8px;
         padding: 12px 24px; font-size: 1rem; font-weight: 600; cursor: pointer; }
button:hover { background: #1d4ed8; } button:disabled { opacity: .6; cursor: not-allowed; }
.alert { padding: 12px 14px; border-radius: 8px; margin: 16px 0; font-size: 0.95rem; }
.alert.error { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; }
.alert.ok { background: #f0fdf4; border: 1px solid #bbf7d0; color: #166534; }
.alert.info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
a.link { color: #2563eb; text-decoration: none; word-break: break-all; }
a.link:hover { text-decoration: underline; }
table { width: 100%; border-collapse: collapse; margin-top: 24px; font-size: 0.9rem; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #e2e8f0; }
th { color: #6b7280; font-weight: 700; }
.code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.85rem;
        background: #f1f5f9; padding: 2px 6px; border-radius: 4px; }
.note { font-size: 0.85rem; color: #6b7280; margin-top: 6px; }
"""

def page(title: str, body: str) -> str:
    """Envuela el cuerpo de la pagina con el diseno base."""
    return f"""<!doctype html><html lang="es"><head><meta charset="utf-8">
<title>{title}</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>{LAYOUT_CSS}</style></head><body>{body}</body></html>"""

FORM_BODY = """
<h1>🎬 Recolector de videos para vids.st</h1>
<p class="sub">Pega la URL de una pagina o de un archivo de video directo. El video se
extrae y se envia a tu cuenta de vids.st mediante su API de remote upload.</p>

<div class="card">
  <form method="post" action="{{ url_for('collect') }}">
    <label>URL de la pagina o video</label>
    <input type="url" name="source_url" required
           placeholder="https://ejemplo.com/pagina-con-video  o  https://.../video.mp4">
    <label>Titulo (opcional)</label>
    <input type="text" name="title" placeholder="Nombre del video">
    <label>API key de vids.st (opcional, si no esta en .env)</label>
    <input type="password" name="api_key" placeholder="sk_...">
    <p class="note">La API key se obtiene en el panel de cuenta de https://vids.st/.</p>
    <button type="submit">Recolectar y subir a vids.st</button>
  </form>
</div>

{% with messages = get_flashed_messages(with_categories=true) %}
  {% if messages %}
    {% for category, message in messages %}
      <div class="alert {{ 'error' if category == 'error' else 'ok' if category == 'success' else 'info' }}">{{ message }}</div>
    {% endfor %}
  {% endif %}
{% endwith %}
"""

RESULT_OK_BODY = """
<h1>Resultado de la recoleccion</h1>
<div class="alert ok">
  <strong>Listo.</strong> El video fue enviado a vids.st.<br>
  <strong>Titulo:</strong> {{ title }}<br>
  <strong>Codigo de video:</strong> <span class="code">{{ filecode }}</span><br>
  <strong>Pagina de vids.st:</strong>
  <a class="link" href="{{ video_page }}" target="_blank" rel="noopener">{{ video_page }}</a><br>
  <strong>Enlace directo:</strong> <span class="code">{{ direct_link }}</span><br>
  <strong>Enlace de incrustacion:</strong>
  <a class="link" href="{{ embed_link }}" target="_blank" rel="noopener">{{ embed_link }}</a>
</div>
<p><a class="link" href="{{ url_for('index') }}">&#8592; Recolectar otro video</a></p>
"""

RESULT_ERROR_BODY = """
<h1>Resultado de la recoleccion</h1>
<div class="alert error">
  <strong>Error:</strong> {{ error }}
</div>
<p><a class="link" href="{{ url_for('index') }}">&#8592; Intentar de nuevo</a></p>
"""


def load_history() -> List[Dict[str, Any]]:
    if not HISTORY_FILE.exists():
        return []
    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (json.JSONDecodeError, OSError):
        return []


def save_record(record: Dict[str, Any]) -> None:
    history = load_history()
    history.insert(0, record)
    history = history[:50]
    with open(HISTORY_FILE, "w", encoding="utf-8") as fh:
        json.dump(history, fh, ensure_ascii=False, indent=2)


@app.get("/health")
def health():
    return {"status": "ok", "service": "vids-st-collector"}, 200


@app.get("/")
def index():
    return render_template_string(page("Recolector vids.st", FORM_BODY))


@app.post("/collect")
def collect():
    source_url = (request.form.get("source_url") or "").strip()
    title = (request.form.get("title") or "").strip() or None
    api_key = (request.form.get("api_key") or "").strip() or VIDS_ST_API_KEY

    if not source_url:
        flash("Debes proporcionar una URL", "error")
        return redirect(url_for("index"))
    if not api_key:
        flash(
            "Falta la API key de vids.st. Configurala en .env (VIDS_ST_API_KEY) "
            "o ponla en el formulario.",
            "error",
        )
        return redirect(url_for("index"))

    try:
        video_url, detected_title = extract_video_url(source_url)
        if title is None:
            title = detected_title
    except Exception as exc:  # noqa: BLE001 - se muestra al usuario tal cual
        flash(f"No se pudo extraer el video: {exc}", "error")
        return redirect(url_for("index"))

    try:
        status_code, data = upload_to_vids_st(
            video_url, api_key, base_url=VIDS_ST_API_BASE, action=VIDS_ST_ACTION
        )
    except requests.RequestException as exc:  # pragma: no cover - depende de red externa
        flash(
            "No se pudo contactar con la API de vids.st: "
            f"{exc}. Verifica tu conexion y la API key.",
            "error",
        )
        return redirect(url_for("index"))

    ok = status_code == 200 and data.get("status") in (200, "200", "ok", "success", None)
    if not ok and status_code == 200 and data.get("status") not in (200, "200"):
        ok = False

    if ok:
        filecode = data.get("filecode") or data.get("id") or data.get("video_id") or ""
        video_page = f"https://vids.st/e/{filecode}" if filecode else ""
        direct_link = f"https://vids.st/v/{filecode}" if filecode else ""
        embed_link = f"https://vids.st/embed/{filecode}" if filecode else ""

        record = {
            "title": title,
            "source_url": source_url,
            "video_url": video_url,
            "filecode": filecode,
            "video_page": video_page,
            "direct_link": direct_link,
            "embed_link": embed_link,
            "api_response": data,
        }
        save_record(record)
        return render_template_string(
            page("Resultado - Recolector vids.st", RESULT_OK_BODY),
            ok=True,
            title=title,
            filecode=filecode,
            video_page=video_page,
            direct_link=direct_link,
            embed_link=embed_link,
        )

    msg = data.get("msg") or data.get("message") or json.dumps(data)[:300]
    flash(f"vids.st respondio ({status_code}): {msg}", "error")
    return redirect(url_for("index"))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=True)
