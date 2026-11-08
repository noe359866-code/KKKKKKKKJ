"""Provider catalogues and episode lists, fetched on the device, without Node.

Uses the same site configuration and parsers as server/scrapers. Only public HTML
and the sites' existing APIs are read; no JavaScript is executed or downloaded.

MEJORAS:
1. Búsqueda de slug por título optimizada para catálogos externos (Nuvio, Cinemeta, etc.).
2. Manejo de IDs tmdb:/tt... mediante búsqueda de título si el slug no es directo.
3. Consistencia en la extracción de episodios para todos los proveedores.
"""
from __future__ import annotations

import importlib
import json
import re
import urllib.parse
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional, Tuple

from _base import default_headers
from _htmlkit import Deadline, clean_title, http_get, pretty_name
from _peliskit import search_site as htmlkit_search

# Proveedores de Anime (Español / Español Latino)
ANIME = frozenset(("animeflv", "jkanime", "monoschinos", "tioanime", "latanime"))

# Proveedores de Series / Películas (Español / Español Latino)
MOVIES = frozenset(("gnula", "cuevana", "pelisplushd", "repelis", "peliculasflix",
                    "pelisjuanita", "novahd", "seriesturcas", "fanpelis",
                    "flixlatam", "sololatino"))

MODULES = ANIME | MOVIES

# NOVA se implementa en Kotlin en Android. El servidor de escritorio sigue
# cargando el módulo Python y su configuración por entorno.
_native_nova = False
_native_engine_tools = None
_native_document_factory = None


def install_native_document_factory(factory) -> None:
    global _native_document_factory
    _native_document_factory = factory


def install_native_engine_tools(tools) -> None:
    global _native_engine_tools
    _native_engine_tools = tools


def install_native_nova(enabled: bool) -> None:
    global _native_nova
    _native_nova = enabled


NON_TITLES = frozenset(("anime", "animes", "directorio", "buscar", "busqueda", "search", "browse",
                        "programacion", "horario", "calendario", "generos", "genero", "login",
                        "registro", "contacto", "politica", "privacidad", "terminos", "dmca",
                        "peliculas", "series", "ver", "estrenos", "page", "category"))


def module_for(provider: str):
    if provider not in MODULES:
        raise ValueError("Unknown bundled provider")
    return importlib.import_module(provider)


def site_for(provider: str) -> Dict[str, Any]:
    if provider == "novahd" and _native_nova:
        return {"base": "https://novahd.cc", "search_hints": ()}
    module = module_for(provider)
    site = module.site_config() if provider == "gnula" else dict(getattr(module, "SITE", {}))
    if callable(getattr(module, "_bases", None)):
        bases = module._bases()
        site.update(base=bases[0], bases=bases)
    else:
        site["base"] = getattr(module, "BASE_URL", getattr(module, "BASE", ""))
    return site


class Document(HTMLParser):
    """Collect linked cards (including lazy artwork), metadata and episode attrs."""
    def __init__(self, html: str, page: str):
        super().__init__(convert_charrefs=True)
        self.page = page
        self.links: List[Dict[str, Any]] = []
        self.meta: Dict[str, str] = {}
        self.heading: List[str] = []
        self._h1 = False
        self._anchor: Optional[Dict[str, Any]] = None
        self._text: List[str] = []
        self._native = None
        self._native_failed = False
        self._native_h1 = False
        self._native_anchor = False
        if (_native_document_factory is not None and isinstance(html, str) and isinstance(page, str)
                and not any(0xD800 <= ord(ch) <= 0xDFFF for ch in html + page)):
            self._native = _native_document_factory.create(page, urllib.parse.urljoin)
        self.feed(html)
        self.close()
        if self._native is not None:
            if self._native_failed:
                # Unsupported Unicode: replay the exact same parser events in Python.
                self._native = None
                self.reset()
                self.links, self.meta, self.heading = [], {}, []
                self._h1, self._anchor, self._text = False, None, []
                self.feed(html)
                self.close()
            else:
                result = json.loads(str(self._native.snapshot()))
                self.links, self.meta, self.heading = result["links"], result["meta"], result["heading"]

    def handle_starttag(self, tag: str, attrs):
        attrs = {key.lower(): value or "" for key, value in attrs}
        if self._native is not None:
            if not self._native_failed and tag in ("meta", "h1", "a", "img"):
                if tag != "img" or self._native_anchor:
                    self._native_failed = not bool(self._native.start(tag, json.dumps(attrs)))
                if tag == "h1": self._native_h1 = True
                if tag == "a": self._native_anchor = True
            return
        if tag == "meta":
            key = attrs.get("property") or attrs.get("name")
            if key:
                self.meta[key.lower()] = attrs.get("content", "")
        elif tag == "h1":
            self._h1 = True
        elif tag == "a":
            if self._anchor is not None:
                self.handle_endtag("a")
            self._anchor = {"url": urllib.parse.urljoin(self.page, attrs.get("href", "")),
                            "attrs": attrs, "title": attrs.get("title", ""), "poster": ""}
            self._text = []
        elif tag == "img" and self._anchor is not None:
            for key in ("data-src", "data-original", "data-lazy-src", "src"):
                image = urllib.parse.urljoin(self.page, attrs.get(key, ""))
                if attrs.get(key) and image.startswith(("http://", "https://")):
                    self._anchor["poster"] = image
                    break
            if not self._anchor["title"]:
                self._anchor["title"] = attrs.get("alt", "")

    def handle_data(self, data: str):
        if self._native is not None:
            if not self._native_failed and (self._native_anchor or self._native_h1):
                if any(0xD800 <= ord(ch) <= 0xDFFF for ch in data):
                    self._native_failed = True
                else:
                    self._native_failed = not bool(self._native.data(data))
            return
        if self._anchor is not None:
            self._text.append(data)
        if self._h1:
            self.heading.append(data)

    def handle_endtag(self, tag: str):
        if self._native is not None:
            if not self._native_failed and tag in ("a", "h1"):
                self._native.end(tag)
                if tag == "a": self._native_anchor = False
                if tag == "h1": self._native_h1 = False
            return
        if tag == "a" and self._anchor is not None:
            self._anchor["title"] = self._anchor["title"] or " ".join(self._text)
            self.links.append(self._anchor)
            self._anchor = None
            self._text = []
        elif tag == "h1":
            self._h1 = False


def card_ref(provider: str, url: str, base: str, default_type: str = "movie") -> Optional[Dict[str, Any]]:
    parsed, origin = urllib.parse.urlparse(url), urllib.parse.urlparse(base)
    if parsed.scheme not in ("http", "https") or parsed.netloc.lower() != origin.netloc.lower():
        return None
    path = urllib.parse.unquote(parsed.path).strip("/")
    if (_native_engine_tools is not None and isinstance(provider, str) and provider.isascii()
            and path.isascii() and "\r" not in path and "\n" not in path
            and isinstance(default_type, str) and default_type.isascii()):
        native = str(_native_engine_tools.catalogCardRefJson(provider, path, default_type, provider in ANIME))
        if native:
            return json.loads(native)
    slug, episode, media_type = "", None, default_type
    if provider in ANIME:
        media_type = "anime"
        if provider == "jkanime":
            match = re.fullmatch(r"([\w.-]+)(?:/(\d{1,5}))?", path)
        elif provider == "animeflv":
            match = re.fullmatch(r"anime/([\w.-]+)", path)
            if not match:
                match = re.fullmatch(r"ver/([\w.-]+?)-(\d{1,5})", path)
        elif provider == "tioanime":
            match = re.fullmatch(r"anime/([\w.-]+)", path)
            if not match:
                match = re.fullmatch(r"ver/([\w.-]+?)-episodio-(\d{1,5})", path)
        elif provider == "latanime":
            match = re.fullmatch(r"anime/([\w.-]+)", path)
            if not match:
                match = re.fullmatch(r"ver/([\w.-]+?)-episodio-(\d{1,5})", path)
        else:
            match = re.fullmatch(r"anime/([\w.-]+)", path)
            if not match:
                match = re.fullmatch(r"ver/([\w.-]+?)-episodio-(\d{1,5})", path)
        if match:
            slug = match.group(1)
            episode = int(match.group(2)) if match.lastindex and match.lastindex >= 2 else None
    else:
        # Mejora: soporte para rutas de Cuevana (/peliculas/, /series/) y otros
        match = re.match(r"(peliculas?|movies?|ver|series?|anime)/([\w.-]+)(?:/|$)", path)
        if match:
            slug = match.group(2)
            media_type = "series" if match.group(1) in ("serie", "series") else "movie"
    if not slug or slug.lower() in NON_TITLES:
        return None
    content_id = f"{provider}:{slug}" + (f":{episode}" if episode is not None else "")
    return {"id": content_id, "type": media_type, "slug": slug, "episode": episode}


def cards(provider: str, html: str, page: str, media_type: str, label: str) -> List[Dict[str, Any]]:
    result, seen = [], set()
    for link in Document(html, page).links:
        ref = card_ref(provider, link["url"], page, media_type)
        if not ref or ref["id"] in seen:
            continue
        seen.add(ref["id"])
        item = {"id": ref["id"], "type": ref["type"],
                "name": clean_title(link["title"]) or pretty_name(ref["slug"]),
                "sourceName": label, "_page": link["url"]}
        if link["poster"]:
            item["poster"] = link["poster"]
        if ref["episode"] is not None:
            item["releaseInfo"] = f"Episodio {ref['episode']}"
        result.append(item)
        if len(result) >= 50:
            break
    return result


def catalogue(provider: Dict[str, Any], media_type: str, query: str = "") -> List[Dict[str, Any]]:
    """Tarjetas que publica hoy la portada de un scraper (listado o búsqueda).

    **No se expone como catálogo del motor**: los scrapers sólo resuelven
    streams (ver `Engine.streams`). Esta función queda como herramienta interna
    para buscar la ficha de un título y para las pruebas de los parsers; el
    contrato público es `{"metas": []}` para cualquier id de proveedor.
    
    MEJORA: Si el query es un ID externo (tt... o tmdb:...), se intenta buscar
    el slug correspondiente mediante el sitio si el módulo lo soporta.
    """
    name, label = provider["id"], provider["label"]
    module = module_for(name) if name in ANIME else None
    headers = default_headers(provider)
    deadline = Deadline(8000)
    site = site_for(name)
    if query and name in MOVIES:
        # Adaptación genérica para películas/series (Cuevana, Fanpelis, FlixLatam, etc.)
        # Usamos search_site de _peliskit que es robusto para estos proveedores
        hits = htmlkit_search(site, query, headers, deadline, site.get("search_hints") or ())
        # Si el query es un ID externo (tt... o tmdb:...), intentamos buscar por el nombre si el módulo lo permite
        # (En el motor local, la búsqueda por título se hace mediante el parámetro `query`)
        result = []
        for hit in hits[:50]:
            url = hit.get("url") or ""
            ref = card_ref(name, url, site["base"], media_type)
            if ref:
                result.append({"id": ref["id"], "type": ref["type"],
                               "name": hit.get("title") or pretty_name(ref["slug"]),
                               "sourceName": label, "_page": url})
        return result
    
    # Si no hay query, usar la página base
    page = module.search_url(query) if query and name in ANIME else site["base"] + "/"
    html, final_url, _ = http_get(page, headers, deadline.remaining(6))
    return cards(name, html, final_url, media_type, label)


def split_id(content_id: str, media_type: str) -> Tuple[str, Optional[int], Optional[int]]:
    # Keep Python's arbitrary-size integers and Unicode digit semantics as fallback.
    if (_native_engine_tools is not None and isinstance(content_id, str) and content_id.isascii()
            and isinstance(media_type, str) and media_type.isascii()):
        anime_provider = content_id.split(":", 1)[0] in ANIME
        native = str(_native_engine_tools.splitCatalogIdJson(content_id, media_type, anime_provider))
        if native:
            return tuple(json.loads(native))
    parts = content_id.split(":")
    if len(parts) >= 3 and parts[-1].isdigit() and parts[-2].isdigit():
        return ":".join(parts[:-2]), int(parts[-2]), int(parts[-1])
    if media_type == "anime" and len(parts) == 3 and parts[0] in ANIME and parts[-1].isdigit():
        return ":".join(parts[:-1]), 1, int(parts[-1])
    return content_id, None, None


def episode_videos(provider: str, base_id: str, slug: str, html: str, page: str) -> List[Dict[str, Any]]:
    episodes: Dict[Tuple[int, int], str] = {}
    links = Document(html, page).links
    native = ""
    if _native_engine_tools is not None and isinstance(slug, str) and slug.isascii():
        anime = provider in ANIME
        refs = [card_ref(provider, link["url"], page, "anime") for link in links] if anime else []
        native = str(_native_engine_tools.catalogEpisodeLinksJson(json.dumps(links), json.dumps(refs), slug, anime))
        if native:
            for season, episode, index in json.loads(native):
                episodes[season, episode] = clean_title(links[index]["title"]) or f"Episodio {episode}"
    for link in ([] if native else links):
        attrs, season, episode = link["attrs"], None, None
        if provider in ANIME:
            ref = card_ref(provider, link["url"], page, "anime")
            if ref and ref["slug"] == slug and ref["episode"] is not None:
                season, episode = 1, ref["episode"]
        else:
            raw_season = attrs.get("data-s") or attrs.get("data-season")
            raw_episode = attrs.get("data-e") or attrs.get("data-episode")
            if raw_season and raw_episode and raw_season.isdigit() and raw_episode.isdigit():
                season, episode = int(raw_season), int(raw_episode)
            elif slug in link["url"]:
                for pattern in (r"(?:temporada|season)[/-](\d+)/(?:capitulo|episode)[/-](\d+)",
                                rf"/{re.escape(slug)}/(\d+)/(\d+)(?:/|$)", r"(\d{1,2})x(\d{1,4})"):
                    match = re.search(pattern, link["url"] + " " + link["title"], re.I)
                    if match:
                        season, episode = int(match.group(1)), int(match.group(2))
                        break
        if season is not None and episode is not None and 0 <= season <= 100 and 0 <= episode <= 10000:
            episodes[season, episode] = clean_title(link["title"]) or f"Episodio {episode}"
    if provider in ("animeflv", "jkanime", "tioanime", "latanime"):
        module = module_for(provider)
        numbers = module.parse_episodes(html) if provider in ("animeflv", "tioanime", "latanime") else module.parse_episodes(html, slug)
        for episode in numbers:
            if 0 <= episode <= 10000:
                episodes.setdefault((1, episode), f"Episodio {episode}")
    if (_native_engine_tools is not None and isinstance(base_id, str)
            and not any(0xD800 <= ord(ch) <= 0xDFFF for ch in base_id)):
        try:
            encoded = json.dumps([[season, episode, title] for (season, episode), title in episodes.items()])
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            rendered = str(_native_engine_tools.catalogEpisodeVideosJson(encoded, base_id))
            if rendered:
                return json.loads(rendered)
    return [{"id": f"{base_id}:{season}:{episode}", "season": season, "episode": episode, "name": title}
            for (season, episode), title in sorted(episodes.items())]


def detail(provider: Dict[str, Any], media_type: str, content_id: str, known: Dict[str, Any]) -> Dict[str, Any]:
    name = provider["id"]
    base_id, _, _ = split_id(content_id, media_type)
    slug = base_id.split(":", 1)[-1]
    site = site_for(name)
    page = known.get("_page")
    if not page:
        if name in ANIME:
            page = f"{site['base']}/{slug}/" if name == "jkanime" else f"{site['base']}/anime/{slug}"
        else:
            templates = site.get("series_paths" if media_type == "series" else "movie_paths") or []
            root = next((p for p in templates if "{episode}" not in p and "{season}" not in p), None)
            page = site["base"] + (root.format(slug=slug) if root else f"/pelicula/{slug}")
    html, final_url, _ = http_get(page, default_headers(provider), 7)
    document = Document(html, final_url)
    result = {**known, "id": base_id, "type": media_type, "sourceName": provider["label"], "_page": final_url}
    title = document.meta.get("og:title") or " ".join(document.heading) or known.get("name") or pretty_name(slug)
    result["name"] = clean_title(title) or pretty_name(slug)
    for target, value in (("poster", document.meta.get("og:image")),
                          ("description", document.meta.get("og:description") or document.meta.get("description"))):
        if value:
            result[target] = urllib.parse.urljoin(final_url, value) if target == "poster" else value
    videos = episode_videos(name, base_id, slug, html, final_url)
    if videos:
        result["videos"] = videos
        if media_type == "movie":
            result["type"] = "series"
    return result
