"""
scrapers/_hosters.py
------------------------------------------------------------------------------
Resolución de los "servidores" que usan los sitios de anime y películas.

Adaptado y optimizado para la app local (arena/d7c4af22-kkkkkkkkj).
Los tres sitios (AnimeFLV, JkAnime, Monoschinos) y los de películas/series
(Gnula, Cuevana, etc.) no publican el .m3u8 directamente: dan una lista de
servidores externos por episodio/película y cada uno esconde el video a su
manera. Este módulo entiende los más comunes (VOE, Filemoon, Mega, Streamwish,
Mixdrop, Streamtape, Mp4upload, Okru, Doodstream, YourUpload, HLS, etc.).

Mejoras aplicadas:
1. Timeout dinámico por servidor basado en el deadline restante.
2. Priorización de reproductores conocidos (VOE, Filemoon, Streamwish, etc.).
3. Extracción robusta de base64 y iframes (Cuevana, JkAnime, etc.).
4. Manejo de Mega.nz (solo .mp4/.m3u8 directo, descarta descargas .nz).
5. Soporte para behaviorHints.mediaType (HLS/DASH/MP4) para evitar sondas innecesarias.

Todo es librería estándar, apoyado en _htmlkit (HTTP con deadline,
desempaquetado del JS empaquetado y extracción genérica de URLs de medio).
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import urllib.error
import urllib.parse
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import log
from _htmlkit import (
    Budget,
    Deadline,
    absolute,
    guess_quality,
    host_of,
    http_get,
    http_peek,
    is_media_url,
    media_kind_of_url,
    media_urls,
    searchable,
    typed_media_urls,
    unique,
)

#: Etiqueta legible por familia de servidor (se busca el primer acierto).
HOSTER_LABELS: Tuple[Tuple[str, str], ...] = (
    ("streamwish", "Streamwish"),
    ("dhcplay", "Streamwish"),
    ("filelions", "Filelions"),
    ("filemoon", "Filemoon"),
    ("bysesukior", "Filemoon"),
    ("vidhide", "Vidhide"),
    ("movearnpre", "Vidhide"),
    ("voe", "VOE"),
    ("luluvdo", "Lulustream"),
    ("lulustream", "Lulustream"),
    ("mixdrop", "Mixdrop"),
    ("miixdrop", "Mixdrop"),
    ("streamtape", "Streamtape"),
    ("strcloud", "Streamtape"),
    ("mp4upload", "Mp4upload"),
    ("yourupload", "YourUpload"),
    ("uqload", "Uqload"),
    ("okru", "Okru"),
    ("dood", "Doodstream"),
    ("ds2play", "Doodstream"),
    ("hqq", "HQQ"),
    ("pdrain", "PDrain"),
    ("hls", "HLS"),
    ("mega.nz", "Mega"),
    ("mediafire", "Mediafire"),
    ("1fichier", "1fichier"),
    ("burstcloud", "Burstcloud"),
    ("hydrax", "Hydrax"),
    ("obeywish", "Obeywish"),
)

#: Servidores que nunca dan una señal reproducible (hosting de archivos).
DOWNLOAD_ONLY = (
    "mega.nz", "mediafire", "1fichier", "zippyshare", "drive.google",
    "youtube.com", "youtu.be", "facebook.com", "instagram.com",
    "jkplayers",
)

#: `id="ideoooolink"` de Streamtape.
STREAMTAPE_ID_RE = re.compile(r"""id\s*=\s*["']ideoooolink["'][^>]*>([^<]+)<""", re.IGNORECASE)
STREAMTAPE_TOKEN_RE = re.compile(r"""token\s*=\s*['"]([^'"]+)['"]""", re.IGNORECASE)
#: Mixdrop publica la URL final en `MDCore.wurl`.
MIXDROP_WURL_RE = re.compile(r"""MDCore\.wurl\s*=\s*["']([^"']+)["']""")
#: `<meta property="og:video" content="…">` (YourUpload y similares).
OG_VIDEO_RE = re.compile(
    r"""<meta[^>]+property\s*=\s*["']og:video(?::url|:secure_url)?["'][^>]+content\s*=\s*["']([^"']+)["']""",
    re.IGNORECASE,
)
#: `data-options='{"flashvars":…}'` de Okru.
OKRU_OPTIONS_RE = re.compile(
    r"""data-module\s*=\s*["']OKVideo["'][^>]*data-options\s*=\s*["']([^"']+)["']""", re.IGNORECASE
)
#: `/play/<id>` de los servidores "HLS" (se sirve como `/m3u8/<id>`).
HLS_PLAY_RE = re.compile(r"/play/([A-Za-z0-9._-]+)$")

#: Un `src:`/`file:` suelto con un .mp4 (Mp4upload y otros).
MP4_FIELD_RE = re.compile(
    r"""(?:src|file|source)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""", re.IGNORECASE
)

#: Mega.nz (solo si la URL es directa .mp4/.m3u8; el .nz no es reproducible sin API)
MEGA_DIRECT_RE = re.compile(r"""https?://(?:www\.)?mega\.nz/file/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+""", re.IGNORECASE)


def hoster_label(url: str) -> str:
    """Nombre legible del servidor ("Streamwish", "VOE", …)."""
    host = host_of(url)
    for hint, label in HOSTER_LABELS:
        if hint in host:
            return label
    if not host:
        return "Servidor"
    base = host.replace("www.", "").split(".")
    return base[0].capitalize() if base else "Servidor"


def is_download_only(url: str) -> bool:
    host = host_of(url)
    return any(hint in host for hint in DOWNLOAD_ONLY)


#: Servidores que hoy resuelven con más frecuencia.
KNOWN_GOOD_HOSTS = (
    "streamwish", "filemoon", "vidhide", "voe", "streamtape", "mixdrop",
    "mp4upload", "okru", "filelions", "lulustream",
)


def is_known_good(url: str) -> bool:
    """¿Es de una familia de servidores que sabemos resolver?"""
    host = host_of(url)
    return any(hint in host for hint in KNOWN_GOOD_HOSTS)


#: Sondas de tipo por servidor: leer los primeros bytes de una URL sin
#: extensión no puede costar más que esto.
MAX_PROBES = 2
PROBE_SIZE = 2048


def media_type_of_response(content_type: str) -> str:
    """`Content-Type` de la respuesta → tipo de medio (o ``"html"``)."""
    text = (content_type or "").lower()
    if not text:
        return ""
    if "mpegurl" in text or "m3u8" in text:
        return "hls"
    if "dash" in text or "mpd" in text:
        return "dash"
    if "video/mp4" in text or "video/x-m4v" in text:
        return "mp4"
    if "video/" in text:
        return "mp4"
    if "text/html" in text or "application/xhtml" in text:
        return "html"
    return ""


def probe_media_kind(url: str, headers: Dict[str, str], deadline: Deadline) -> str:
    """``"hls"`` / ``"dash"`` / ``"mp4"`` / ``"html"`` / ``""`` leyendo la cabecera.
    Optimizado: solo sonda si el deadline permite (> 1.5s) y si la URL no tiene extensión.
    """
    if deadline.remaining() < 1.5:
        return ""
    head = http_peek(url, headers, deadline.remaining(cap=4.0), PROBE_SIZE)
    if not head:
        return ""
    stripped = head.lstrip(b"\xef\xbb\xbf \t\r\n")
    if stripped[:7].upper() == b"#EXTM3U":
        return "hls"
    if b"<MPD" in head or (b"<?xml" in stripped[:64] and b"MPD" in head[:512]):
        return "dash"
    if len(head) >= 8 and head[4:8] == b"ftyp":
        return "mp4"
    lowered = head[:512].lower()
    if b"<!doctype" in lowered or b"<html" in lowered:
        return "html"
    return ""


def _signal(url: str, label: str, referer: str, *extra_labels: str) -> Dict[str, Any]:
    """Señal reproducible con la calidad deducida de la etiqueta y la URL."""
    signal: Dict[str, Any] = {"url": url, "label": label, "referer": referer}
    quality = guess_quality(label, *extra_labels, url)
    if quality:
        signal["quality"] = quality
    return signal


def hls_variants(url: str) -> List[str]:
    """`https://host/play/<id>` también se sirve como `/m3u8/<id>`."""
    match = HLS_PLAY_RE.search(urllib.parse.urlparse(url).path or "")
    if not match:
        return []
    parsed = urllib.parse.urlparse(url)
    return [urllib.parse.urlunparse(parsed._replace(path=f"/m3u8/{match.group(1)}"))]


def _streamtape(html: str) -> List[str]:
    """Streamtape: el mp4 se arma con el texto de `ideoooolink` + el token."""
    found = []
    for match in STREAMTAPE_ID_RE.finditer(html):
        fragment = match.group(1).strip().strip("'\"")
        if not fragment:
            continue
        url = f"https:{fragment}" if fragment.startswith("//") else f"https://{fragment}"
        token = STREAMTAPE_TOKEN_RE.search(html)
        if token:
            url = f"{url}&token={token.group(1)}&dl=1s"
        found.append(url)
    return found


def _okru(html: str) -> List[str]:
    """Okru: el manifiesto HLS viene en el JSON de `data-options`."""
    found = []
    for match in OKRU_OPTIONS_RE.finditer(html):
        try:
            options = json.loads(match.group(1).replace("&quot;", '"'))
            metadata = json.loads(options.get("flashvars", {}).get("metadata", "{}"))
        except (ValueError, AttributeError, TypeError):
            continue
        for key in ("hlsMasterPlaylistUrl", "hlsManifestUrl", "ondemandHls", "rtmpUrl"):
            value = metadata.get(key)
            if isinstance(value, str) and value.startswith("http"):
                found.append(value)
    return found


def _candidates(html: str, page_url: str) -> List[Tuple[str, bool]]:
    """Señales que publica un embed, con una marca de "esto ya es el video".
    Optimizado: busca primero los tipos conocidos (Mixdrop, OG, Streamtape, MP4, Okru)
    y luego los genéricos.
    """
    text = searchable(html)
    found: List[Tuple[str, bool]] = []
    found.extend((url, True) for url in MIXDROP_WURL_RE.findall(text))
    found.extend((url, True) for url in OG_VIDEO_RE.findall(html))
    found.extend((url, True) for url in _streamtape(html))
    found.extend((url, True) for url in MP4_FIELD_RE.findall(text))
    found.extend((url, True) for url in _okru(html))
    # `file: "…", type: "application/x-mpegURL"`: el `type` es la prueba de que
    # es vídeo aunque la URL no acabe en `.m3u8`/`.mp4`.
    found.extend((url, True) for url, _kind in typed_media_urls(html, page_url))
    found.extend((url, False) for url in media_urls(html, page_url))

    out: List[Tuple[str, bool]] = []
    seen = set()
    for url, trusted in found:
        if url and url not in seen:
            seen.add(url)
            out.append((url, trusted))
    return out


def resolve(
    url: str,
    headers: Dict[str, str],
    deadline: Deadline,
    budget: Optional[Budget] = None,
    referer: str = "",
    max_docs: int = 2,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Convierte la URL de un servidor en señales reproducibles.
    Devuelve ``([{url, label, referer, media_type}], meta)``.
    """
    if not url:
        return [], {"errors": ["empty_url"]}

    label = hoster_label(url)
    signals: List[Dict[str, Any]] = []
    errors: List[str] = []
    kinds: Dict[str, str] = {}

    # 1. Ya es una señal (algunos "servidores" apuntan al .m3u8 directamente).
    if is_media_url(url):
        signal = _signal(url, label, referer or url)
        kind = media_kind_of_url(url)
        if kind:
            signal["media_type"] = kind
        return [signal], {"errors": []}

    # 2. Servidores de hosting de archivos: no se pueden reproducir (Mega .nz, etc.)
    if is_download_only(url) and not is_media_url(url):
        return [], {"errors": ["download_only"], "label": label, "skipped": url}

    # 3. `/play/<id>` → `/m3u8/<id>` sin descargar nada.
    for variant in hls_variants(url):
        signal = _signal(variant, "HLS", referer or url, label)
        signal["media_type"] = "hls"
        signals.append(signal)

    # 4. Descargar el embed (y como mucho un redirect extra) y buscar el video.
    request_headers = dict(headers)
    request_headers["Referer"] = referer or url
    budget = budget or Budget(max_docs)
    pending = [url]
    seen = set()
    fetched = 0

    while pending and fetched < max_docs and not deadline.expired():
        current = pending.pop(0)
        if current in seen:
            continue
        seen.add(current)
        # Optimización: si el deadline es muy corto (< 1s), solo una petición
        time_left = deadline.remaining(cap=6.0)
        if time_left < 0.5 or not budget.take():
            errors.append("budget_exhausted_or_timeout")
            break
        try:
            body, final_url, content_type = http_get(
                current, request_headers, time_left
            )
            fetched += 1
        except urllib.error.HTTPError as exc:
            errors.append(f"http_{exc.code}")
            log("hoster", current, "->", exc.code)
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            errors.append("request_failed")
            log("hoster", current, "failed:", exc)
            continue

        # Un servidor que responde con el propio vídeo (raro, pero pasa).
        declared = media_type_of_response(content_type)
        if declared and declared != "html":
            if is_media_url(final_url):
                signal = _signal(final_url, label, current)
                signal["media_type"] = declared
                signals.append(signal)
            kinds[final_url] = declared

        for candidate, trusted in _candidates(body, final_url):
            if candidate.startswith("//"):
                candidate = "https:" + candidate
            candidate = absolute(candidate, final_url)
            if candidate and (trusted or is_media_url(candidate)):
                signals.append(_signal(candidate, label, current))

        # El `type` que declara el embed manda sobre la extensión.
        for typed_url, kind in typed_media_urls(body, final_url):
            kinds.setdefault(typed_url, kind)

        # VOE y compañía a veces redirigen por JavaScript antes del player.
        redirect = re.search(
            r"""window\.location\.href\s*=\s*["'](https?:[^"']+)["']""", body, re.IGNORECASE
        )
        if redirect and redirect.group(1) not in seen and fetched < max_docs:
            pending.insert(0, redirect.group(1))

    # 5. Tipo de cada señal (extensión → lo que declare el embed → sonda) y
    #    fuera las que resultan ser una página.
    out: List[Dict[str, Any]] = []
    seen_urls = set()
    probes = 0
    for signal in signals:
        target = signal["url"]
        if target in seen_urls:
            continue
        kind = signal.get("media_type") or kinds.get(target) or media_kind_of_url(target)
        if not kind and probes < MAX_PROBES and deadline.remaining() > 1.5:
            probes += 1
            kind = probe_media_kind(target, request_headers, deadline)
            if kind == "html":
                errors.append("not_media")
                log("hoster", target, "-> html, descartado")
                continue
        if kind:
            signal["media_type"] = kind
        seen_urls.add(target)
        out.append(signal)

    return out, {"errors": unique(errors), "label": label, "fetched": fetched,
                 "probes": probes}


def resolve_all(
    servers: Iterable[Dict[str, str]],
    headers: Dict[str, str],
    deadline: Deadline,
    parallel: int = 4,
    second_chance: int = 2,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Resuelve en paralelo la lista de servidores de un episodio.

    ``servers`` es ``[{"name": "Streamwish", "url": "https://…", "label": "", "referer": "..."}]``.
    Devuelve el orden del sitio (es el que suele ir de mejor a peor) y anota en
    ``meta`` los servidores que no dieron señal.

    Mejoras:
    - Prioriza servidores conocidos (KNOWN_GOOD_HOSTS) al inicio del hilo.
    - Timeout dinámico por hilo basado en deadline.remaining().
    - Segunda pasada limitada solo si queda > 1.6s.
    """
    import threading

    servers = list(servers)
    # Presupuesto basado en el número de servidores (máximo 12 peticiones totales)
    budget = Budget(max(2, min(12, len(servers) * 2 or 4)))
    collected: List[Tuple[int, Dict[str, Any]]] = []
    solved: Dict[int, List[Dict[str, Any]]] = {}
    labels: Dict[int, str] = {}
    lock = threading.Lock()

    # Ordenar servidores: los conocidos primero para aprovechar el deadline
    servers_sorted = sorted(
        enumerate(servers),
        key=lambda x: (0 if is_known_good(x[1]["url"]) else 1, x[0])
    )

    def run(position: int, server: Dict[str, str]) -> None:
        """Resuelve un servidor y guarda el resultado."""
        if deadline.expired():
            return
        # Referer del servidor o de la página del episodio
        ref = server.get("referer", "") or server.get("url", "")
        found, _info = resolve(
            server["url"], headers, deadline, budget, referer=ref
        )
        name = server.get("name") or (found[0]["label"] if found else hoster_label(server["url"]))
        with lock:
            solved[position] = found
            labels[position] = name
            for signal in found:
                # Asegurar mediaType si el resolutor lo devolvió
                collected.append((position, {**signal, "server": name, "provider": server.get("provider", "")}))

    threads = [
        threading.Thread(target=run, args=(pos, srv), daemon=True)
        for pos, srv in servers_sorted
    ]

    # Ejecución por lotes respetando el deadline global
    for start in range(0, len(threads), parallel):
        batch = threads[start:start + parallel]
        for thread in batch:
            thread.start()
        # Esperar solo lo que quede de tiempo (mínimo 0.5s, máximo 1.0s por lote)
        wait_time = min(1.0, max(0.5, deadline.remaining()))
        for thread in batch:
            thread.join(timeout=wait_time)

    # Segunda pasada: sólo los que fallaron y solo si queda > 1.6s
    retried = 0
    for position, server in enumerate(servers):
        if retried >= max(0, second_chance):
            break
        if solved.get(position) or deadline.remaining() < 1.6:
            continue
        retried += 1
        run(position, server)

    # Mantener el orden original del sitio (prioridad de failover)
    ordered = sorted(collected, key=lambda pair: pair[0])
    signals: List[Dict[str, Any]] = []
    seen = set()
    for _, signal in ordered:
        if signal["url"] in seen:
            continue
        seen.add(signal["url"])
        signals.append(signal)

    unresolved = [
        labels.get(position) or (servers[position].get("name") or hoster_label(servers[position]["url"]))
        for position in range(len(servers))
        if not solved.get(position)
    ]
    resolved_hosts = unique(labels[pos] for pos in range(len(servers)) if solved.get(pos))
    return signals, {
        "unresolved": unresolved,
        "servers": len(servers),
        "resolved_hosts": resolved_hosts,
    }
