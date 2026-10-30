"""Offline engine integration tests: all HTTP is a local, synthetic fixture."""
import json
import base64
import urllib.request
import urllib.error
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import contextlib
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "server/scrapers")]

import embedded_engine as embedded
import _embedded_catalogs as catalogs
from _embedded_cache import JsonCache

REGISTRY = json.loads((ROOT / "server/config/providers.json").read_text())
LIVE = json.loads((ROOT / "server/config/live-catalog.json").read_text())
PADDING = "<!--" + "x" * 500 + "-->"


@contextlib.contextmanager
def live_offline(**overrides):
    """Todos los cargadores de los proveedores de directo devuelven [] (o lo que se pida).

    La estantería de TV/agenda pregunta a cada proveedor: en las pruebas tiene
    que ser determinista y sin red, así que se sustituyen los cargadores.
    """
    with contextlib.ExitStack() as stack:
        for provider_id in catalogs.LIVE:
            module = catalogs.module_for(provider_id)
            for loader in ("load_index", "load_channels", "load_agenda"):
                if hasattr(module, loader):
                    stack.enter_context(patch.object(module, loader, return_value=overrides.get(provider_id, [])))
        yield


class FixtureHandler(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.requests.append(self.path)
        routes = {
            "/": '<a href="/pelicula/fixture"><img data-src="/poster.jpg" alt="Película de prueba"></a>'
                  '<a href="/serie/serie-fixture"><img src="/series.jpg" alt="Serie de prueba"></a>'
                  '<a href="https://ad.invalid/pelicula/spam">Publicidad</a>'
                  '<a href="/ver/anime-fixture-2"><img src="/anime.jpg" alt="Anime de prueba 2"></a>',
            "/pelicula/fixture": '<h1>Película de prueba</h1><iframe src="/embed/fixture"></iframe>',
            "/serie/serie-fixture": '<h1>Serie de prueba</h1><meta property="og:description" content="Sinopsis">'
                                   '<a href="/serie/serie-fixture/temporada/1/capitulo/2">Episodio dos</a>'
                                   '<a href="/serie/serie-fixture/temporada/1/capitulo/1">Episodio uno</a>',
            "/serie/serie-fixture/temporada/1/capitulo/2": '<h1>Serie de prueba 1x02</h1><iframe src="/embed/episode"></iframe>',
            "/anime/anime-fixture": '<h1>Anime de prueba</h1><script>var episodes=[[1,"x"],[2,"y"]];</script>',
            "/ver/anime-fixture-2": '<h1>Anime de prueba 2</h1><script>var videos=[{server:"sw",code:"/embed/anime"}];</script>',
            "/embed/fixture": 'jwplayer("v").setup({sources:[{file:"BASE/video/fixture.m3u8"}]});',
            "/embed/episode": 'jwplayer("v").setup({sources:[{file:"BASE/video/episode-2.mp4"}]});',
            "/meta/movie/tt1234567.json": json.dumps({"meta": {"id": "tt1234567", "name": "Fixture title", "imdbRating": "8.8"}}),
            "/meta/series/tt1234567.json": json.dumps({"meta": {"id": "tt1234567", "name": "Fixture series", "videos": [
                {"season": 2, "episode": 3, "name": "Third"}]}}),
            "/catalog/series/top/genre=Animation.json": json.dumps({"metas": [{"id": "tt7654321", "type": "series", "name": "Anime fixture"}]}),
        }
        body = routes.get(self.path)
        if body is None and self.path.startswith("/search"):
            # Buscador del sitio: es la ruta que usa un scraper cuando sólo
            # conoce el título (p. ej. un id IMDb de Cinemeta).
            body = '<a href="/pelicula/fixture">Fixture title<img src="/poster.jpg"></a>'
        self.send_response(200 if body is not None else 404)
        self.send_header("Content-Type", "application/json" if ".json" in self.path else "text/html; charset=utf-8")
        self.end_headers()
        if body is not None:
            origin = f"http://127.0.0.1:{self.server.server_port}"
            self.wfile.write((body.replace("BASE", origin) + ("" if ".json" in self.path else PADDING)).encode())


class EngineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.engine = embedded.Engine(self.temp.name, REGISTRY, LIVE, self.base)
        FixtureHandler.requests.clear()

    def tearDown(self):
        self.engine.close()
        self.temp.cleanup()

    def test_scrapers_are_bundled_but_publish_no_catalog_rows(self):
        # La lista sale del registro: añadir un proveedor no debe obligar a
        # tocar el test, pero sí debe reflejarse aquí (y en el APK).
        expected = {
            item["id"] for item in REGISTRY["providers"]
            if item.get("enabled", True) and "demo" not in item["id"]
        }
        self.assertEqual(expected, set(self.engine.providers))
        self.assertIn("livelive24", expected)
        self.assertIn("damitv", expected)
        self.assertIn("ntv", expected)
        self.assertIn("streamcorner", expected)
        self.assertFalse(any("demo" in key for key in self.engine.providers))
        with patch("urllib.request.urlopen", side_effect=AssertionError("No network at discovery")):
            rows = self.engine.dispatch("catalogs", {})["catalogs"]
            health = self.engine.dispatch("health", {})
        self.assertEqual(len(expected), len(health["providers"]))
        # Los scrapers resuelven streams; no son catálogo. La Home lleva canales
        # en vivo, el área de deportes (y Cinemeta cuando el cliente lo pide).
        self.assertEqual(["iptvorg", "bundled_live", "canales", "deportes"],
                         [row["id"] for row in rows])
        self.assertEqual("sports", rows[-1]["layout"])
        self.assertFalse({row["id"] for row in rows} & set(self.engine.providers))
        self.assertTrue(all(row["source"] in ("embedded", "iptv") for row in rows))
        self.assertTrue(all(callable(catalogs.module_for(p).scrape) for p in health["providers"]))

    def test_sports_row_lists_todays_matches_with_league_teams_and_state(self):
        agenda = [
            {"id": "evento:mlb-yankees-vs-red-sox", "title": "MLB: Yankees vs Red Sox",
             "category": "beisbol", "date": "2026-10-29", "time": "21:00",
             "status": "Programado", "links": ["https://la18hd.su/vivo/x"]},
            {"id": "evento:liga-1-cienciano-vs-atletico-grau",
             "title": "Liga 1: Cienciano vs Atlético Grau", "category": "futbol",
             "date": "2026-10-29", "time": "19:00", "status": "En vivo",
             "links": ["https://la18hd.su/vivo/y"]},
        ]
        channels = [
            {"name": "ESPN 2", "logo": "https://logos.invalid/espn.png",
             "url": "https://cdn.invalid/espn/index.m3u8"},
            {"name": "Canal de cocina", "logo": "", "url": "https://cdn.invalid/food/index.m3u8"},
        ]
        with live_offline(futbolibre=agenda), \
                patch.object(embedded, "iptv_channels", return_value=channels):
            result = self.engine.dispatch("catalog", {"type": "tv", "id": "deportes"})
            metas = result["metas"]
        self.assertTrue(metas)
        # El partido en directo va primero, después el programado y los canales.
        live, upcoming = metas[0], metas[1]
        self.assertEqual("tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau", live["id"])
        self.assertEqual("Cienciano vs Atlético Grau", live["name"])
        self.assertEqual("Cienciano", live["home"])
        self.assertEqual("Atlético Grau", live["away"])
        self.assertEqual("Liga 1", live["league"])
        self.assertEqual(["Deportes", "Liga 1"], live["genres"])
        self.assertTrue(live["onAir"])
        self.assertEqual("19:00", live["startTime"])
        self.assertEqual(self.engine.providers["futbolibre"]["label"], live["sourceName"])
        self.assertFalse(upcoming["onAir"])
        self.assertEqual("MLB", upcoming["league"])
        self.assertEqual("Hoy · 21:00", upcoming["releaseInfo"])
        # Canales: sólo los deportivos, y el de cocina no entra.
        self.assertTrue(any(meta["name"] == "ESPN 2" and meta["onAir"] for meta in metas))
        self.assertFalse(any(meta["name"] == "Canal de cocina" for meta in metas))
        # Y el partido se reproduce por el camino de siempre (el id del scraper).
        module = catalogs.module_for("futbolibre")
        with patch.object(module, "scrape", return_value=([{"url": "https://cdn.invalid/live.m3u8"}], {})):
            self.assertTrue(self.engine.streams("tv", live["id"])["streams"])

    def test_sports_titles_drop_the_broadcast_suffix_from_team_names(self):
        meta = embedded.sports_event_meta(
            {"id": "futbolibre", "label": "Fútbol Libre"},
            {"id": "evento:nba", "title": "NBA: Lakers vs Celtics (Final)",
             "time": "02:30", "status": "En vivo"},
        )
        self.assertEqual("Lakers", meta["home"])
        self.assertEqual("Celtics", meta["away"])
        self.assertEqual("Lakers vs Celtics", meta["name"])

    def test_sports_row_is_empty_rather_than_stale_when_there_is_no_agenda(self):
        with live_offline(), patch.object(embedded, "iptv_channels", return_value=[]):
            self.assertEqual([], self.engine.dispatch("catalog", {"type": "tv", "id": "deportes"})["metas"])

    def test_cinemeta_is_explicit_opt_in_and_always_first_when_enabled(self):
        base_rows = self.engine.catalogs()["catalogs"]
        extra_rows = self.engine.catalogs(True)["catalogs"]
        self.assertEqual(len(base_rows) + len(embedded.CINEMETA_CATALOGS), len(extra_rows))
        self.assertFalse(any("cinemeta" in row["id"] for row in base_rows))
        # Cinemeta encabeza el Home: sus pósters y backdrops son el arte bueno.
        self.assertTrue(all(row["provider"] == "Cinemeta" for row in extra_rows[:len(embedded.CINEMETA_CATALOGS)]))
        self.assertEqual("iptvorg", extra_rows[len(embedded.CINEMETA_CATALOGS)]["id"])
        self.assertEqual("bundled_live", extra_rows[len(embedded.CINEMETA_CATALOGS) + 1]["id"])

    def test_iptv_playlist_parsing_drops_geo_blocked_and_non_hls(self):
        playlist = "\r\n".join((
            "#EXTM3U",
            '#EXTINF:-1 tvg-logo="https://logos.invalid/a.png",Canal Abierto',
            "https://cdn.invalid/a/index.m3u8",
            '#EXTINF:-1 tvg-logo="https://logos.invalid/a.png",Canal Abierto',
            "https://cdn.invalid/a/index.m3u8",
            '#EXTINF:-1,Canal Geobloqueado [Geo-blocked]',
            "https://cdn.invalid/b/index.m3u8",
            "#EXTINF:-1,Nada reproducible",
            "https://cdn.invalid/c/embed",
            "#EXTINF:-1,Canal DASH",
            "https://cdn.invalid/d/manifest.mpd",
        ))
        channels = embedded.parse_playlist(playlist)
        self.assertEqual(["Canal Abierto", "Canal DASH"], [item["name"] for item in channels])
        self.assertEqual("https://logos.invalid/a.png", channels[0]["logo"])

    def test_iptv_channels_are_playable_without_any_provider(self):
        channel = {"name": "Canal de prueba", "logo": "https://logos.invalid/x.png",
                   "url": "https://cdn.invalid/live/index.m3u8"}
        with patch.object(embedded, "iptv_channels", return_value=[channel]):
            metas = self.engine.catalogue("tv", "iptvorg")["metas"]
            self.assertEqual(1, len(metas))
            self.assertEqual("Canal de prueba", metas[0]["name"])
            self.assertEqual("https://logos.invalid/x.png", metas[0]["poster"])
            streams = self.engine.streams("tv", metas[0]["id"])["streams"]
        self.assertEqual(1, len(streams))
        self.assertEqual("https://cdn.invalid/live/index.m3u8", streams[0]["url"])
        self.assertTrue(streams[0]["isLive"])
        self.assertEqual("Canal de prueba", streams[0]["title"])

    def test_can_browse_bundled_live_cards_without_network_or_url(self):
        with live_offline(), patch("urllib.request.urlopen", side_effect=AssertionError("No backend")):
            result = self.engine.dispatch("catalog", {"type": "tv", "id": "bundled_live"})
        self.assertTrue(result["metas"])
        self.assertTrue(all(item["type"] == "tv" for item in result["metas"]))
        self.assertTrue(all("demo" not in item["id"] for item in result["metas"]))

    def test_live_search_and_pagination_remain_filtered_when_cached(self):
        with live_offline():
            first = self.engine.catalogue("tv", "bundled_live", "ESPN", skip=1)
        again = self.engine.catalogue("tv", "bundled_live", "ESPN", skip=1)
        self.assertEqual(first, again)
        self.assertTrue(first["metas"])
        self.assertTrue(all("espn" in item["name"].lower() for item in first["metas"]))
        all_matches = self.engine.catalogue("tv", "bundled_live", "ESPN")
        self.assertEqual(all_matches["metas"][1:], first["metas"])

    def test_channels_row_collects_every_provider_and_skips_duplicates(self):
        """La fila de canales se llena sola: curdos primero, luego todos los proveedores."""
        channels_by_provider = {
            "futbolibre": [{"slug": "espn-1", "name": "ESPN 1"},
                           {"slug": "fox-sports", "name": "Fox Sports"}],
            "ntv": [{"id": "nfl-network", "name": "NFL Network"}],
            "streamcorner": [{"id": "nfl/nfl-network", "name": "NFL Network"},
                             {"id": "mlb/mlb-network", "name": "MLB Network"}],
        }
        overrides = {name: items for name, items in channels_by_provider.items()}
        with live_offline(**overrides):
            metas = self.engine.dispatch("catalog", {"type": "tv", "id": "canales"})["metas"]
        names = [meta["name"] for meta in metas]
        # El curado (ESPN 1) va primero y bloquea al duplicado del proveedor.
        self.assertEqual("ESPN 1", names[0])
        self.assertEqual(1, names.count("ESPN 1"))
        # NFL Network sale en NTV y StreamCorner: una sola tarjeta.
        self.assertEqual(1, names.count("NFL Network"))
        self.assertIn("MLB Network", names)
        self.assertIn("Fox Sports", names)
        # Todo resuelve por el camino de siempre y trae su proveedor.
        self.assertTrue(all(meta["id"].startswith("tv:") for meta in metas))
        self.assertTrue(all(meta.get("sourceName") for meta in metas))
        self.assertTrue(all(meta["type"] == "tv" for meta in metas))

    def test_without_any_provider_the_channels_row_still_shows_the_curated_list(self):
        with live_offline():
            shelf = self.engine.live_shelf()
        curated = [item["name"] for item in LIVE["items"]]
        self.assertEqual(curated, [meta["name"] for meta in shelf["channels"]])
        self.assertEqual([], shelf["events"], "sin agenda real la fila se queda vacía")

    def test_a_failed_scrape_keeps_stale_channels_but_never_old_matches(self):
        stale = {
            "channels": [{"id": "tv:ntv:viejo", "type": "tv", "name": "Canal viejo"}],
            "events": [{"id": "tv:futbolibre:evento:ayer", "type": "tv", "name": "Ayer vs Viejo"}],
        }

        def fake_get(key, stale_seconds=0):
            # La copia "fresca" ya caducó; la rancia sí se puede leer.
            return None if stale_seconds == 0 else stale

        empty_curated = embedded.Engine(self.temp.name, REGISTRY, {"items": []}, self.base)
        try:
            with live_offline(), patch.object(empty_curated.cache, "get", side_effect=fake_get):
                shelf = empty_curated.live_shelf()
        finally:
            empty_curated.close()
        self.assertEqual(["Canal viejo"], [meta["name"] for meta in shelf["channels"]])
        self.assertEqual([], shelf["events"], "un partido de ayer es una tarjeta muerta")
        self.assertTrue(shelf["meta"]["degraded"])

    def test_channels_row_is_cached_between_requests(self):
        calls = {"futbolibre": 0}

        def counting(headers, deadline):
            calls["futbolibre"] += 1
            return [{"slug": "espn-1", "name": "ESPN 1"}]

        with live_offline(), patch.object(catalogs.module_for("futbolibre"), "load_channels", side_effect=counting):
            first = self.engine.dispatch("catalog", {"type": "tv", "id": "canales"})["metas"]
            second = self.engine.dispatch("catalog", {"type": "tv", "id": "canales"})["metas"]
        self.assertTrue(first)
        self.assertEqual(first, second)
        self.assertEqual(1, calls["futbolibre"], "la estantería se cachea: un scrape por ventana")



    def test_scraper_resolves_streams_without_publishing_a_catalog(self):
        with patch.dict(os.environ, {"PELISPLUSHD_BASE": self.base}), patch("subprocess.Popen", side_effect=AssertionError("No child processes")):
            # El motor no expone la fila del scraper: pedirla devuelve vacío.
            self.assertEqual({"metas": []}, self.engine.dispatch("catalog", {"type": "movie", "id": "pelisplushd"}))
            # Pero sigue resolviendo la ficha y el stream con su parser.
            listed = catalogs.catalogue(self.engine.providers["pelisplushd"], "movie")
            movie = next(m for m in listed if m["id"] == "pelisplushd:fixture")
            self.assertEqual("Película de prueba", movie["name"])
            self.assertEqual(self.base + "/poster.jpg", movie["poster"])
            self.assertTrue(any(m["type"] == "series" for m in listed))
            sources = self.engine.streams("movie", movie["id"])["streams"]
        self.assertEqual([self.base + "/video/fixture.m3u8"], [s["url"] for s in sources])
        self.assertIn("Referer", sources[0]["behaviorHints"]["requestHeaders"])
        self.assertIn("User-Agent", sources[0]["behaviorHints"]["requestHeaders"])
        self.assertEqual("PelisPlus", sources[0]["provider"])
        self.assertFalse(sources[0]["isLive"])

    def test_subtitles_ride_with_the_streams_and_are_cached_once(self):
        """Los subtítulos del `meta` del proveedor salen en la misma respuesta.

        En el APK no hay un segundo viaje: el reproductor necesita las pistas
        **antes** de crear el `MediaItem`, y volver a raspar retrasaría el
        arranque del vídeo.
        """
        module = embedded.providers.module_for("pelisplushd")
        payloads = []

        def fake_scrape(payload):
            payloads.append(payload)
            return (
                [{"url": self.base + "/video/fixture.m3u8", "name": "PelisPlus", "quality": "1080p"}],
                {"subtitles": [
                    {"url": self.base + "/subs/movie.en.vtt", "lang": "English"},
                    {"url": self.base + "/subs/movie.es.srt", "lang": "SPA"},
                    {"url": self.base + "/subs/movie.es.srt", "lang": "es"},
                    {"url": self.base + "/embed/player.html", "lang": "es"},
                ]},
            )

        with patch.object(module, "scrape", side_effect=fake_scrape):
            self.engine.providers = {"pelisplushd": self.engine.providers["pelisplushd"]}
            result = self.engine.streams("movie", "pelisplushd:fixture")
            # `dispatch("subtitles")` reutiliza la caché de streams: cero scrapes.
            again = self.engine.dispatch("subtitles", {"type": "movie", "id": "pelisplushd:fixture"})

        self.assertEqual(1, len(payloads))
        self.assertEqual(1, len(result["streams"]))
        self.assertEqual(
            [(item["lang"], item["label"], item.get("format")) for item in result["subtitles"]],
            [("es", "Español", "srt"), ("en", "Inglés", "vtt")],
        )
        self.assertEqual(result["subtitles"], again["subtitles"])
        # Sin duplicados y sin la página de embed del proveedor.
        self.assertEqual(2, len({item["url"] for item in result["subtitles"]}))

    def test_live_content_never_publishes_subtitles(self):
        module = embedded.providers.module_for("futbolibre")
        with patch.object(module, "scrape", return_value=(
            [{"url": "https://video.invalid/live.m3u8", "name": "Fútbol Libre"}],
            {"subtitles": [{"url": "https://video.invalid/live.es.vtt", "lang": "es"}]},
        )):
            self.engine.providers = {"futbolibre": self.engine.providers["futbolibre"]}
            result = self.engine.streams("tv", "futbolibre:espn-1")
        self.assertEqual(1, len(result["streams"]))
        self.assertEqual([], result["subtitles"])

    def test_subtitle_normalization_rejects_what_media3_cannot_open(self):
        provider = {"label": "NovaHD"}
        self.assertIsNone(embedded.normalize_subtitle({"url": "https://cdn/a.mp4"}, provider))
        self.assertIsNone(embedded.normalize_subtitle({"url": "https://cdn/page.html"}, provider))
        self.assertIsNone(embedded.normalize_subtitle({"url": "/subs/a.srt"}, provider))
        self.assertIsNone(embedded.normalize_subtitle("nope", provider))
        self.assertEqual(
            {"id": "https://cdn/a.srt", "url": "https://cdn/a.srt", "lang": "es",
             "label": "Español", "provider": "NovaHD", "format": "srt"},
            embedded.normalize_subtitle({"url": "https://cdn/a.srt", "lang": "español"}, provider),
        )
        # Sin idioma ni etiqueta, al menos el nombre del proveedor.
        self.assertEqual("NovaHD", embedded.normalize_subtitle({"url": "https://cdn/subs/9"}, provider)["label"])
        self.assertEqual("es-419", embedded.normalize_language("es_419"))
        self.assertEqual("", embedded.normalize_language("subtítulos en español"))
        self.assertEqual("Español (Latino)", embedded.language_name("ES-419"))

    def test_cinemeta_titles_resolve_streams_through_the_scraper_search(self):
        self.engine.providers = {"pelisplushd": self.engine.providers["pelisplushd"]}
        with patch.dict(os.environ, {"PELISPLUSHD_BASE": self.base}):
            streams = self.engine.streams("movie", "tt1234567")["streams"]
        # Nadie publicó una fila de pelisplushd: el título salió de Cinemeta y el
        # scraper lo encontró buscando en su propio sitio.
        self.assertEqual([self.base + "/video/fixture.m3u8"], [item["url"] for item in streams])
        self.assertTrue(any(path.startswith("/search") for path in FixtureHandler.requests))
        self.assertEqual("PelisPlus", streams[0]["provider"])

    def test_local_series_details_get_real_numbered_episodes_and_the_selected_episode(self):
        with patch.dict(os.environ, {"PELISPLUSHD_BASE": self.base}):
            meta = self.engine.meta("series", "pelisplushd:serie-fixture")["meta"]
            self.assertEqual([1, 2], [v["episode"] for v in meta["videos"]])
            self.assertEqual("Sinopsis", meta["description"])
            sources = self.engine.streams("series", "pelisplushd:serie-fixture:1:2")["streams"]
        self.assertEqual([self.base + "/video/episode-2.mp4"], [s["url"] for s in sources])

    def test_anime_cards_have_episode_ids_and_real_detail_list(self):
        module = catalogs.module_for("animeflv")
        with patch.object(module, "BASE_URL", self.base):
            items = catalogs.catalogue(self.engine.providers["animeflv"], "anime")
            self.assertEqual("animeflv:anime-fixture:2", items[0]["id"])
            meta = self.engine.meta("anime", "animeflv:anime-fixture")["meta"]
        self.assertEqual([1, 2], [v["episode"] for v in meta["videos"]])
        self.assertEqual("animeflv:anime-fixture:1:2", meta["videos"][1]["id"])

    def test_optional_anime_metadata_maps_to_series_upstream_without_losing_type(self):
        result = self.engine.catalogue("anime", "cinemeta_top")
        self.assertEqual("anime", result["metas"][0]["type"])
        self.assertIn("/catalog/series/top/genre=Animation.json", FixtureHandler.requests)

    def test_imdb_ids_resolve_by_title_without_a_tmdb_key_and_keep_episode_numbers(self):
        captured = []
        self.engine.providers = {"pelisplushd": self.engine.providers["pelisplushd"]}
        module = catalogs.module_for("pelisplushd")
        def scrape(payload):
            captured.append(payload)
            return [{"url": "https://video.invalid/episode.mp4"}], {}
        with patch.object(module, "scrape", scrape), patch.dict(os.environ, {"TMDB_API_KEY": ""}):
            result = self.engine.streams("series", "tt1234567:2:3")
        self.assertEqual("pelisplushd:Fixture series", captured[0]["id"])
        self.assertEqual((2, 3), (captured[0]["season"], captured[0]["episode"]))
        self.assertEqual(1, len(result["streams"]))

    def test_priority_is_not_completion_order_and_broken_providers_are_isolated(self):
        self.engine.providers = {p: self.engine.providers[p] for p in ("gnula", "cuevana", "pelisplushd")}
        def slow(_):
            time.sleep(.025)
            return [{"url": "https://video.invalid/first.m3u8"}], {}
        with patch.object(catalogs.module_for("gnula"), "scrape", slow), \
             patch.object(catalogs.module_for("cuevana"), "scrape", return_value=([{"url": "https://video.invalid/second.mp4"}], {})), \
             patch.object(catalogs.module_for("pelisplushd"), "scrape", side_effect=ValueError("broken")):
            result = self.engine.streams("movie", "fixture title")
        self.assertEqual(["Gnula", "Cuevana"], [s["provider"] for s in result["streams"]])

    def test_streams_cached_on_disk_are_reused_without_scraping_again(self):
        module = catalogs.module_for("pelisplushd")
        with patch.object(module, "scrape", return_value=([{"url": "https://video.invalid/test.mp4"}], {})) as scrape:
            first = self.engine.streams("movie", "pelisplushd:fixture")
            self.assertEqual(first, self.engine.streams("movie", "pelisplushd:fixture"))
            self.assertEqual(1, scrape.call_count)
        other = embedded.Engine(self.temp.name, REGISTRY, LIVE)
        try:
            with patch.object(module, "scrape", side_effect=AssertionError("Must use disk cache")):
                self.assertEqual(first, other.streams("movie", "pelisplushd:fixture"))
        finally:
            other.close()

    def test_empty_stream_results_are_not_cached_and_are_retried(self):
        module = catalogs.module_for("pelisplushd")
        with patch.object(module, "scrape", side_effect=[([], {}), ([{"url": "https://video.invalid/test.mp4"}], {})]) as scrape:
            self.assertEqual([], self.engine.streams("movie", "pelisplushd:fixture")["streams"])
            self.assertTrue(self.engine.streams("movie", "pelisplushd:fixture")["streams"])
        self.assertEqual(2, scrape.call_count)

    def test_cinemeta_rows_survive_a_restart_when_the_metadata_source_is_offline(self):
        expected = self.engine.catalogue("anime", "cinemeta_top")
        self.assertTrue(expected["metas"])
        other = embedded.Engine(self.temp.name, REGISTRY, LIVE)
        try:
            other.cache.clock = lambda: time.time() + 3700
            with patch.object(embedded, "http_get_json", side_effect=OSError("offline")):
                self.assertEqual(expected, other.catalogue("anime", "cinemeta_top"))
        finally:
            other.close()

    def test_signed_stream_cache_never_outlives_expiry(self):
        expires = int(time.time() + 40)
        with patch.object(catalogs.module_for("pelisplushd"), "scrape", return_value=([{"url": f"https://video.invalid/test.mp4?e={expires}"}], {})):
            result = self.engine.streams("movie", "pelisplushd:fixture")
        self.assertGreater(result["cacheMaxAge"], 0)
        self.assertLessEqual(result["cacheMaxAge"], 25)
        self.engine.cache.clock = lambda: time.time() + 45
        self.assertIsNone(self.engine.cache.get("streams:movie:pelisplushd:fixture"))

    def test_headers_live_flag_and_playable_filter(self):
        provider = self.engine.providers["futbolibre"]
        result = embedded.normalize_stream({"url": "https://video.invalid/test.m3u8", "referer": "https://embed.invalid/",
            "behaviorHints": {"requestHeaders": {"cookie": "session=fixture", "Origin": "https://origin.invalid"}}}, provider, True)
        self.assertTrue(result["isLive"])
        self.assertEqual("session=fixture", result["behaviorHints"]["requestHeaders"]["Cookie"])
        self.assertEqual("https://embed.invalid/", result["behaviorHints"]["requestHeaders"]["Referer"])
        for url in ("file:///test.mp4", "magnet:test", "https://site.invalid/embed/1", "https://site.invalid/a.mp4?e=1000000000"):
            self.assertIsNone(embedded.normalize_stream({"url": url}, provider, True))

    def test_extensionless_media_survives_the_playable_filter(self):
        """Un HLS en `/v/abc123` es una fuente válida: antes se tiraba."""
        provider = self.engine.providers["pelisjuanita"]

        typed = embedded.normalize_stream({
            "url": "https://cdn.invalid/v/abc123",
            "behaviorHints": {"mediaType": "hls"},
        }, provider, False)
        self.assertEqual("hls", typed["behaviorHints"]["mediaType"])
        self.assertEqual("https://cdn.invalid/v/abc123", typed["url"])

        # Sin pista explícita también se acepta: el failover decide.
        self.assertIsNotNone(embedded.normalize_stream({"url": "https://cdn.invalid/v/def456"}, provider, False))
        # Manifiesto dentro de la consulta del reproductor.
        self.assertIsNotNone(embedded.normalize_stream(
            {"url": "https://cdn.invalid/player.php?id=7&file=master.m3u8"}, provider, False))
        # Lo que es una página o una imagen se sigue rechazando.
        for url in ("https://cdn.invalid/embed/1", "https://cdn.invalid/pelicula/x",
                    "https://cdn.invalid/a.html", "https://cdn.invalid/poster.jpg",
                    "https://cdn.invalid/serie/x/1x03"):
            self.assertIsNone(embedded.normalize_stream({"url": url}, provider, False), url)
            self.assertFalse(embedded.is_playable_stream(url, {"mediaType": "hls"}), url)

    def test_invalid_requests_cannot_import_arbitrary_scripts(self):
        for name in ("../../outside", "template_provider", "example_provider", "os"):
            with self.assertRaises(ValueError):
                catalogs.module_for(name)
        for request in ({"type": "bad", "id": "x"}, {"type": "movie", "id": ""}):
            with self.assertRaises(ValueError):
                self.engine.dispatch("streams", request)
        with self.assertRaises(ValueError):
            self.engine.dispatch("unknown", {"type": "movie", "id": "x"})

    def test_common_signed_url_expiry_formats_are_respected(self):
        self.assertEqual(1700000000, embedded.declared_expiry("https://fixture.invalid/a.m3u8?expires=1700000000"))
        self.assertEqual(1700000000, embedded.declared_expiry("https://fixture.invalid/a.m3u8?exp=1700000000000"))
        self.assertEqual(1700000000, embedded.declared_expiry("https://fixture.invalid/a.m3u8?hdnea=st%3D1~exp%3D1700000000~acl%3D*"))
        self.assertIsNone(embedded.declared_expiry("https://fixture.invalid/a.m3u8?expires=3600"))

    def test_metadata_normalizes_dirty_values(self):
        meta = embedded.normalize_meta({"id": "tt1234567", "name": "Fixture", "imdbRating": "8.8", "releaseInfo": "2019–2023",
                                        "genres": None, "videos": [{"season": "1", "episode": "2"}, {"season": None}]}, "series")
        self.assertEqual(2019, meta["year"])
        self.assertEqual(8.8, meta["rating"])
        self.assertEqual([], meta["genres"])
        self.assertEqual(1, len(meta["videos"]))
        self.assertNotIn("rating", embedded.normalize_meta({"id": "x", "imdbRating": "NaN"}, "movie"))


class ParserTests(unittest.TestCase):
    def test_external_ad_links_are_not_catalogue_titles_and_lazy_artwork_is_kept(self):
        items = catalogs.cards("pelisplushd", '<a href="https://ads.invalid/pelicula/ad">Ad</a>'
            '<a href="/pelicula/fixture"><img data-src="/poster.jpg" alt="Fixture"></a>'
            '<a href="/pelicula/fixture">Duplicate</a>', "https://fixture.invalid/", "movie", "Fixture")
        self.assertEqual(["pelisplushd:fixture"], [item["id"] for item in items])
        self.assertEqual("https://fixture.invalid/poster.jpg", items[0]["poster"])

    def test_gnula_episode_attributes_are_preserved_and_sorted(self):
        html = '<a href="#" data-s="2" data-e="3">Tres</a><a href="#" data-s="1" data-e="2">Dos</a>'
        result = catalogs.episode_videos("gnula", "gnula:fixture", "fixture", html, "https://fixture.invalid/ver/fixture")
        self.assertEqual(["gnula:fixture:1:2", "gnula:fixture:2:3"], [video["id"] for video in result])

    def test_anime_and_series_id_numbering(self):
        self.assertEqual(("animeflv:one-piece", 1, 1180), catalogs.split_id("animeflv:one-piece:1180", "anime"))
        self.assertEqual(("pelisplushd:fixture", 2, 3), catalogs.split_id("pelisplushd:fixture:2:3", "series"))
        self.assertEqual(("tt1234567", None, None), catalogs.split_id("tt1234567", "movie"))


class AndroidTransportTests(unittest.TestCase):
    def test_adapts_post_headers_body_redirect_url_and_overall_timeout(self):
        from _android_http import AndroidUrlOpener
        captured = []
        class Transport:
            def request(self, *args):
                captured.append(args)
                return json.dumps({"url": "https://fixture.invalid/final", "status": 200,
                                   "headers": {"Content-Type": "text/html; charset=utf-8"},
                                   "body": base64.b64encode("áé".encode()).decode()})
        request = urllib.request.Request("https://fixture.invalid/start", data=b'{"fixture":true}',
                                         headers={"Referer": "https://embed.invalid/", "Content-Type": "application/json"}, method="POST")
        with AndroidUrlOpener(Transport())(request, timeout=100) as response:
            self.assertEqual("áé", response.read().decode())
            self.assertEqual("utf-8", response.headers.get_content_charset())
            self.assertEqual("https://fixture.invalid/final", response.geturl())
        self.assertEqual("POST", captured[0][1])
        self.assertEqual("application/json", json.loads(captured[0][2])["Content-type"])
        self.assertEqual(8000, captured[0][3])
        self.assertEqual(b'{"fixture":true}', base64.b64decode(captured[0][4]))

    def test_post_without_content_type_keeps_the_urllib_form_default(self):
        from _android_http import AndroidUrlOpener
        captured = []
        class Transport:
            def request(self, *args):
                captured.append(args)
                return json.dumps({"url": args[0], "status": 200, "headers": {}, "body": ""})
        with AndroidUrlOpener(Transport())(urllib.request.Request("https://fixture.invalid", data=b"id=fixture")):
            pass
        self.assertEqual("application/x-www-form-urlencoded", json.loads(captured[0][2])["Content-Type"])
        self.assertEqual(b"id=fixture", base64.b64decode(captured[0][4]))

    def test_native_failures_and_http_errors_keep_the_urllib_contract(self):
        from _android_http import AndroidUrlOpener
        class FailingTransport:
            def request(self, *args):
                raise RuntimeError("native timeout")
        with self.assertRaises(urllib.error.URLError):
            AndroidUrlOpener(FailingTransport())(urllib.request.Request("https://fixture.invalid"))
        class NotFoundTransport:
            def request(self, *args):
                return json.dumps({"url": "https://fixture.invalid/", "status": 404,
                                   "headers": {}, "body": ""})
        with self.assertRaises(urllib.error.HTTPError) as context:
            AndroidUrlOpener(NotFoundTransport())(urllib.request.Request("https://fixture.invalid"))
        self.assertEqual(404, context.exception.code)


class CacheTests(unittest.TestCase):
    def test_expiry_stale_window_corruption_clear_and_capacity(self):
        now = [1000]
        with tempfile.TemporaryDirectory() as directory:
            cache = JsonCache(directory, max_entries=2, clock=lambda: now[0])
            cache.put("one", {"title": "Uno"}, 10)
            self.assertEqual({"title": "Uno"}, cache.get("one"))
            now[0] += 11
            self.assertIsNone(cache.get("one"))
            self.assertEqual({"title": "Uno"}, cache.get("one", stale_seconds=20))
            now[0] += 20
            self.assertIsNone(cache.get("one", stale_seconds=20))
            cache.put("two", {}, 100)
            cache.put("three", {}, 100)
            self.assertLessEqual(len(list(Path(directory).glob("*.json"))), 2)
            cache._path("two").write_text("broken")
            self.assertIsNone(cache.get("two"))
            cache.clear()
            self.assertEqual([], list(Path(directory).glob("*.json")))


if __name__ == "__main__":
    unittest.main()
