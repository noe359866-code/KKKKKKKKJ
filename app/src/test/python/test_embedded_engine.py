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
PADDING = "<!--" + "x" * 500 + "-->"


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
        self.engine = embedded.Engine(self.temp.name, REGISTRY, self.base)
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
        self.assertIn("pelisplushd", expected)
        self.assertIn("novahd", expected)
        self.assertIn("animeflv", expected)
        self.assertFalse(any("demo" in key for key in self.engine.providers))
        with patch("urllib.request.urlopen", side_effect=AssertionError("No network at discovery")):
            rows = self.engine.dispatch("catalogs", {})["catalogs"]
            health = self.engine.dispatch("health", {})
        self.assertEqual(len(expected), len(health["providers"]))
        # Los scrapers resuelven streams; no son catálogo. La Home sólo lleva
        # Cinemeta cuando el cliente lo pide: sin metadatos, ninguna fila.
        self.assertEqual([], [row["id"] for row in rows])
        self.assertFalse({row["id"] for row in rows} & set(self.engine.providers))
        self.assertTrue(all(callable(catalogs.module_for(p).scrape) for p in health["providers"]))

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
        self.assertNotIn("isLive", sources[0])

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
        other = embedded.Engine(self.temp.name, REGISTRY)
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
        other = embedded.Engine(self.temp.name, REGISTRY)
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

    def test_extensionless_media_survives_the_playable_filter(self):
        """Un HLS en `/v/abc123` es una fuente válida: antes se tiraba."""
        provider = self.engine.providers["pelisjuanita"]

        typed = embedded.normalize_stream({
            "url": "https://cdn.invalid/v/abc123",
            "behaviorHints": {"mediaType": "hls"},
        }, provider)
        self.assertEqual("hls", typed["behaviorHints"]["mediaType"])
        self.assertEqual("https://cdn.invalid/v/abc123", typed["url"])

        # Sin pista explícita también se acepta: el failover decide.
        self.assertIsNotNone(embedded.normalize_stream({"url": "https://cdn.invalid/v/def456"}, provider))
        # Manifiesto dentro de la consulta del reproductor.
        self.assertIsNotNone(embedded.normalize_stream(
            {"url": "https://cdn.invalid/player.php?id=7&file=master.m3u8"}, provider))
        # Lo que es una página o una imagen se sigue rechazando.
        for url in ("https://cdn.invalid/embed/1", "https://cdn.invalid/pelicula/x",
                    "https://cdn.invalid/a.html", "https://cdn.invalid/poster.jpg",
                    "https://cdn.invalid/serie/x/1x03"):
            self.assertIsNone(embedded.normalize_stream({"url": url}, provider), url)
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
