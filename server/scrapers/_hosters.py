"""
scrapers/_hosters.py
------------------------------------------------------------------------------
Resolución de los "servidores" que usan los sitios de anime.

Los tres sitios (AnimeFLV, JkAnime, Monoschinos) no publican el `.m3u8`
directamente: dan una lista de servidores externos por episodio y cada uno
esconde el video a su manera. Este módulo entiende los más comunes.

    host                    cómo se saca el video
    ----------------------  ---------------------------------------------------
    HLS (`/play/<id>`)      la propia URL cambia `/play/` → `/m3u8/`
    Streamwish / Vidhide    HTML con `sources: [{file: "…"}]` o JS empaquetado
    VOE                     HTML con `sources`/`file`, a veces tras un redirect
    Filemoon                `sources` si es plana (la variante cifrada se omite)
    Mixdrop                 `MDCore.wurl = "…"` dentro de JS empaquetado
    Streamtape              `id="ideoooolink"` + token (`get_video?id=…&token=…`)
    Mp4upload               `src: "….mp4"` en el script del reproductor
    YourUpload              `<meta property="og:video" content="…">`
    Okru                    `data-options` con `hlsMasterPlaylistUrl`
    Doodstream              `window.location`/`/pass_md5/` (mejor esfuerzo)

Del resto (Mega, Mediafire, 1fichier…) no hay forma de reproducir en ExoPlayer:
se descartan y se anotan en `meta.unresolved`, para que el cliente sepa que
existían pero no eran señales válidas.

Todo es librería estándar, apoyado en `_htmlkit` (HTTP con deadline, desempaquetado
del JS empaquetado y extracción genérica de URLs de medio).
"""

from __future__ import annotations

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
    # JkAnime publica sus descargas en `c1.jkplayers.com/d/<id>`: es el mismo
    # archivo que el reproductor, pero por HTTP de descarga (no es un medio).
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
#: Prefijos de **ruta** donde los hosters sirven el manifiesto directamente.
HLS_MANIFEST_PREFIXES: Tuple[str, ...] = ("/m3u8/", "/hls/")
#: Prefijos de ruta donde el mismo id sigue siendo una **página** con el
#: reproductor dentro (hay que descargarla y mirar el HTML).
HLS_EMBED_PREFIXES: Tuple[str, ...] = ("/e/", "/embed/", "/v/")
#: Algunos hosters prefijan el id en lugar de la ruta (`/e/e-<id>`).
HLS_ID_PREFIXES: Tuple[str, ...] = ("", "e-", "embed-", "v-")

#: Un `src:`/`file:` suelto con un .mp4 (Mp4upload y otros).
MP4_FIELD_RE = re.compile(
    r"""(?:src|file|source)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""", re.IGNORECASE
)


# Android installs the Kotlin implementation; the optional Node backend keeps
# the Python version while the migration proceeds.
_native_hoster_tools = None


def install_native_hoster_tools(tools) -> None:
    global _native_hoster_tools
    _native_hoster_tools = tools


def hoster_label(url: str) -> str:
    """Nombre legible del servidor ("Streamwish", "VOE", …)."""
    if _native_hoster_tools is not None:
        return str(_native_hoster_tools.label(url))
    host = host_of(url)
    for hint, label in HOSTER_LABELS:
        if hint in host:
            return label
    if not host:
        return "Servidor"
    base = host.replace("www.", "").split(".")
    return base[0].capitalize() if base else "Servidor"


def is_download_only(url: str) -> bool:
    if _native_hoster_tools is not None:
        return bool(_native_hoster_tools.isDownloadOnly(url))
    host = host_of(url)
    return any(hint in host for hint in DOWNLOAD_ONLY)


#: Servidores que hoy resuelven con más frecuencia. Si el sitio publica una
#: lista larga se añaden al final (nunca se reordena: el orden del sitio es el
#: que el propio sitio considera de mejor a peor).
KNOWN_GOOD_HOSTS = (
    "streamwish", "filemoon", "vidhide", "voe", "streamtape", "mixdrop",
    "mp4upload", "okru", "filelions", "lulustream",
)


def is_known_good(url: str) -> bool:
    """¿Es de una familia de servidores que sabemos resolver?"""
    if _native_hoster_tools is not None:
        return bool(_native_hoster_tools.isKnownGood(url))
    host = host_of(url)
    return any(hint in host for hint in KNOWN_GOOD_HOSTS)


#: Sondas de tipo por servidor: leer los primeros bytes de una URL sin
#: extensión no puede costar más que esto.
MAX_PROBES = 2
PROBE_SIZE = 2048
#: Rutas de embed extra que se prueban por servidor. El presupuesto de
#: documentos se comparte entre todos los servidores del episodio, así que dos
#: es el máximo razonable: una de más y un hoster sano se queda sin turno.
HLS_MAX_EMBED_PAGES = 2


def media_type_of_response(content_type: str) -> str:
    """`Content-Type` de la respuesta → tipo de medio (o ``"html"``)."""
    if _native_hoster_tools is not None:
        return str(_native_hoster_tools.mediaTypeOfResponse(content_type or ""))
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

    Se usa con las fuentes cuya URL no delata el tipo (`https://cdn/v/abc123`).
    Saber que es HLS importa: ExoPlayer sin extensión intentaría abrirlo como
    progresivo y no reproduciría nada. Y si lo que hay es una página HTML, el
    candidato se descarta en vez de ofrecerlo como stream.
    """
    head = http_peek(url, headers, deadline.remaining(cap=4.0), PROBE_SIZE)
    if not head:
        return ""
    if _native_hoster_tools is not None:
        return str(_native_hoster_tools.classifyProbe(head.hex()))
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


def _rewrite_paths(url: str, prefixes: Tuple[str, ...]) -> List[str]:
    """URLs con la misma ruta sustituida por cada `prefix` + id de `/play/<id>`."""
    parsed = urllib.parse.urlparse(url)
    match = HLS_PLAY_RE.search(parsed.path or "")
    if not match:
        return []
    identifier = match.group(1)
    seen = {url}
    candidates: List[str] = []
    # El prefijo del id es la variable rara: se agrupa primero por ruta para
    # que `/m3u8/<id>` (el caso normal) quede en cabeza.
    for id_prefix in HLS_ID_PREFIXES:
        for prefix in prefixes:
            candidate = urllib.parse.urlunparse(
                parsed._replace(path=f"{prefix}{id_prefix}{identifier}")
            )
            if candidate in seen:
                continue
            seen.add(candidate)
            candidates.append(candidate)
    return candidates


def hls_variants(url: str) -> List[str]:
    """Manifiestos equivalentes a un `/play/<id>`.

    No todos los hosters sirven el `.m3u8` bajo `/m3u8/`: algunos usan
    `/hls/` y otros prefijan el id (`/m3u8/e-<id>`). Se devuelven todas las
    combinaciones, en orden de preferencia y sin repetir la URL original.

    Sólo salen rutas que **son** el manifiesto. Las que siguen siendo una
    página con el reproductor dentro (`/e/`, `/embed/`, `/v/`) no caben aquí:
    entregar HTML a ExoPlayer como si fuera HLS es peor que no ofrecer la
    fuente, así que van por `hls_embed_pages()` y se resuelven descargándolas.
    """
    if _native_hoster_tools is not None:
        import json
        return json.loads(str(_native_hoster_tools.manifestVariantsJson(url)))
    return _rewrite_paths(url, HLS_MANIFEST_PREFIXES)


def hls_embed_pages(url: str) -> List[str]:
    """Páginas equivalentes a un `/play/<id>` que aún hay que descargar.

    Mismo id bajo las rutas de embed de los hosters (`/e/`, `/embed/`, `/v/`),
    con y sin prefijo en el id. Quien llama las mete en la cola de descarga y
    busca el video en el HTML; una que responda 404 sólo cuesta una petición.
    """
    if _native_hoster_tools is not None:
        import json
        result = str(_native_hoster_tools.embedVariantsJson(url))
        if result:
            return json.loads(result)
    return _rewrite_paths(url, HLS_EMBED_PREFIXES)


def _streamtape(html: str) -> List[str]:
    """Streamtape: el mp4 se arma con el texto de `ideoooolink` + el token."""
    if _native_hoster_tools is not None:
        import json
        return json.loads(str(_native_hoster_tools.streamtapeJson(html or "")))
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
    import json

    if _native_hoster_tools is not None:
        native = str(_native_hoster_tools.okruJson(html or ""))
        if native:
            return json.loads(native)
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

    Los extractores específicos (Streamtape, Mixdrop, Okru, `og:video`, campos
    `src:`/`file:`) devuelven URLs finales aunque no acaben en `.mp4`; el
    extractor genérico sólo acepta lo que parece un medio.
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
    max_embed_pages: int = HLS_MAX_EMBED_PAGES,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Convierte la URL de un servidor en señales reproducibles.

    Devuelve ``([{url, label, referer}], meta)``. Nunca lanza: un servidor caído
    sólo aparece en ``meta.errors``.

    Después del embed original se prueban ``max_embed_pages`` rutas de embed
    del mismo id (``/e/``, ``/embed/``, ``/v/``). El presupuesto es compartido
    por todos los servidores del episodio, así que el tope es corto a propósito:
    una variante de más no puede dejar sin turno a los demás hosters.
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

    # 2. Servidores de hosting de archivos: no se pueden reproducir.
    if is_download_only(url):
        return [], {"errors": ["download_only"], "label": label, "skipped": url}

    # 3. `/play/<id>` → `/m3u8/<id>` (y `/hls/`) sin descargar nada.
    for variant in hls_variants(url):
        signal = _signal(variant, "HLS", referer or url, label)
        signal["media_type"] = "hls"
        signals.append(signal)

    # 4. Descargar el embed (y como mucho un redirect extra) y buscar el video.
    # El navegador manda como Referer la página donde está el embed (el
    # episodio), no la home del sitio: algunos servidores lo comprueban.
    request_headers = dict(headers)
    request_headers["Referer"] = referer or url
    budget = budget or Budget(max_docs)
    # Las rutas de embed del mismo id (`/e/`, `/embed/`, `/v/`) son otra
    # forma de llegar al reproductor: se descargan después del embed original
    # y antes de rendirse, recortadas al presupuesto de documentos.
    tail = hls_embed_pages(url)[:max(0, max_embed_pages)]
    # El embed original va primero en la cola: si el presupuesto compartido se
    # agota a mitad, se queda sin turno una variante, nunca el documento bueno.
    pending = [url] + tail
    max_fetches = max_docs + len(tail)
    seen = set()
    fetched = 0

    while pending and fetched < max_fetches and not deadline.expired():
        current = pending.pop(0)
        if current in seen:
            continue
        seen.add(current)
        if not budget.take():
            errors.append("budget_exhausted")
            break
        try:
            body, final_url, content_type = http_get(
                current, request_headers, deadline.remaining(cap=6.0)
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
        if not kind and probes < MAX_PROBES and deadline.remaining() > 2.0:
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
    episode_url: str,
    parallel: int = 4,
    second_chance: int = 2,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Resuelve en paralelo la lista de servidores de un episodio.

    ``servers`` es ``[{"name": "Streamwish", "url": "https://…"}]``.
    Devuelve el orden del sitio (es el que suele ir de mejor a peor) y anota en
    ``meta`` los servidores que no dieron señal.

    Dos mejoras sobre "una pasada y ya": los servidores que no devolvieron nada
    se reintentan una vez (sólo los primeros ``second_chance`` y sólo si queda
    tiempo), porque un embed que falló por un 503 o por un deadline justo suele
    funcionar al segundo intento; y ``meta.resolved_hosts`` deja ver qué
    familias resolvieron, que es lo que dice si hay que tocar un extractor.
    """
    import threading

    servers = list(servers)
    budget = Budget(max(2, min(12, len(servers) * 2 or 4)))
    collected: List[Tuple[int, Dict[str, Any]]] = []
    solved: Dict[int, List[Dict[str, Any]]] = {}
    labels: Dict[int, str] = {}
    lock = threading.Lock()

    def run(position: int, server: Dict[str, str]) -> None:
        """Resuelve un servidor y guarda el resultado (vacío si no hay señal)."""
        if deadline.expired():
            return
        found, _info = resolve(
            server["url"], headers, deadline, budget, referer=episode_url
        )
        name = server.get("name") or (found[0]["label"] if found else hoster_label(server["url"]))
        with lock:
            solved[position] = found
            labels[position] = name
            for signal in found:
                collected.append((position, {**signal, "server": name}))

    threads = [
        threading.Thread(target=run, args=(position, server), daemon=True)
        for position, server in enumerate(servers)
    ]
    for start in range(0, len(threads), parallel):
        batch = threads[start:start + parallel]
        for thread in batch:
            thread.start()
        for thread in batch:
            thread.join(timeout=max(1.0, deadline.remaining()))

    # Segunda pasada: sólo los que fallaron y sólo si el deadline da margen.
    retried = 0
    for position, server in enumerate(servers):
        if retried >= max(0, second_chance):
            break
        if solved.get(position) or deadline.remaining() < 1.6:
            continue
        retried += 1
        run(position, server)

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
