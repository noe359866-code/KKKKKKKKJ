"""Colector de videos: extrae la URL directa de un video desde una web y la
envia a https://vids.st/ mediante su API de remote upload.

Funciona en dos pasos:
  1. Extraccion: recibe la URL de una pagina (o un archivo de video directo)
     y usa yt-dlp para obtener la URL directa del archivo de video, sin
     descargarlo localmente.
  2. Subida remota: llama a la API de vids.st para que sus propios servidores
     descarguen el video y lo almacenen/codifiquen en HLS.

Requisitos del usuario:
  - Una cuenta en https://vids.st/ y su API key (se obtiene en el panel de
    cuenta de vids.st).
  - Solo usar videos de los que se tenga derecho legal de subir (ver README).
"""

from __future__ import annotations

import re
from typing import Optional, Tuple

import requests
import yt_dlp

DIRECT_VIDEO_EXTENSIONS = (".mp4", ".mkv", ".webm", ".flv", ".mov", ".avi")

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36"
)


def looks_like_direct_video(url: str) -> bool:
    """True si la URL parece apuntar directamente a un archivo de video."""
    path = url.split("?", 1)[0].lower()
    return any(path.endswith(ext) for ext in DIRECT_VIDEO_EXTENSIONS)


def extract_video_url(page_url: str) -> Tuple[str, Optional[str]]:
    """Devuelve (url_video_directo, titulo) a partir de una URL de web.

    Si la URL ya es un archivo de video directo, se devuelve tal cual.
    De lo contrario se consulta yt-dlp (sin descargar) para hallar el mejor
    formato de video disponible.
    """
    if looks_like_direct_video(page_url):
        return page_url, None

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "format": (
            "bestvideo[ext=mp4][vcodec!=none]+bestaudio[ext=m4a]/"
            "best[ext=mp4][vcodec!=none]/"
            "best[vcodec!=none]/best"
        ),
        "http_headers": {"User-Agent": USER_AGENT},
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(page_url, download=False)
        if not info:
            raise ValueError("No se pudo extraer informacion de la pagina")

        # yt-dlp devuelve un dict de playlist en algunos casos; tomar la primera entrada
        if info.get("_type") == "playlist" and info.get("entries"):
            info = info["entries"][0] or info

        formats = info.get("formats") or []
        best = None
        for f in formats:
            if f.get("url") and f.get("vcodec") not in (None, "none"):
                best = f
                break

        if best is None:
            # fallback: URL directa en el nivel superior
            best = info if info.get("url") else None

        if not best or not best.get("url"):
            raise ValueError("No se encontro una URL directa de video en la pagina")

    title = info.get("title") or info.get("id") or "video"
    return best["url"], title


def upload_to_vids_st(
    video_url: str,
    api_key: str,
    base_url: str = "https://vids.st/api/",
    action: str = "remote_upload",
    extra_params: Optional[dict] = None,
) -> Tuple[int, dict]:
    """Llama a la API de vids.st para subir el video por URL remota.

    Endpoint descubierto: GET https://vids.st/api/?key=XXX&action=remote_upload&url=...
    Devuelve (codigo_http, json_respuesta).
    """
    base_url = base_url.rstrip("/") + "/"
    params = {"key": api_key, "action": action, "url": video_url}
    if extra_params:
        params.update(extra_params)

    response = requests.get(base_url, params=params, timeout=120)
    try:
        data = response.json()
    except ValueError:
        data = {"status": response.status_code, "msg": response.text[:1000]}
    return response.status_code, data
