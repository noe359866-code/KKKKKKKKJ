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
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "server/scrapers")]

import embedded_engine as embedded
import _embedded_catalogs as catalogs
from _embedded_cache import JsonCache

REGISTRY = json.loads((ROOT / "server/config/providers.json").read_text())
LIVE = json.loads((ROOT / "server/config/live-catalog.json").read_text())
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

    def test_twelve_real_providers_are_bundled_not_the_demo_providers(self):
        self.assertEqual(12, len(self.engine.providers))
        self.assertFalse(any("demo" in key for key in self.engine.providers))
        with patch("urllib.request.urlopen", side_effect=AssertionError("No network at discovery")):
            rows = self.engine.dispatch("catalogs", {})["catalogs"]
            health = self.engine.dispatch("health", {})
        self.assertEqual(12, len(health["providers"]))
        self.assertEqual("bundled_live", rows[0]["id"])
        self.assertTrue(all(row["source"] == "embedded" for row in rows))
        self.assertTrue(all(callable(catalogs.module_for(p).scrape) for p in health["providers"]))

    def test_cinemeta_is_explicit_opt_in_and_always_first_when_enabled(self):
        base_rows = self.engine.catalogs()["catalogs"]
        extra_rows = self.engine.catalogs(True)["catalogs"]
        self.assertEqual(len(base_rows) + len(embedded.CINEMETA_CATALOGS), len(extra_rows))
        self.assertFalse(any("cinemeta" in row["id"] for row in base_rows))
        # Cinemeta encabeza el Home: sus pósters y backdrops son el arte bueno.
        self.assertTrue(all(row["provider"] == "Cinemeta" for row in extra_rows[:len(embedded.CINEMETA_CATALOGS)]))
        self.assertEqual("bundled_live", extra_rows[len(embedded.CINEMETA_CATALOGS)]["id"])

    def test_can_browse_bundled_live_cards_without_network_or_url(self):
        with patch("urllib.request.urlopen", side_effect=AssertionError("No backend")):
            result = self.engine.dispatch("catalog", {"type": "tv", "id": "bundled_live"})
        self.assertTrue(result["metas"])
        self.assertTrue(all(item["type"] == "tv" for item in result["metas"]))
        self.assertTrue(all("demo" not in item["id"] for item in result["metas"]))

    def test_live_search_and_pagination_remain_filtered_when_cached(self):
        first = self.engine.catalogue("tv", "bundled_live", "ESPN", skip=1)
        again = self.engine.catalogue("tv", "bundled_live", "ESPN", skip=1)
        self.assertEqual(first, again)
        self.assertTrue(first["metas"])
        self.assertTrue(all("espn" in item["name"].lower() for item in first["metas"]))
        all_matches = self.engine.catalogue("tv", "bundled_live", "ESPN")
        self.assertEqual(all_matches["metas"][1:], first["metas"])

    def test_real_pelisplus_catalog_to_scraper_to_playable_stream_without_spawn(self):
        with patch.dict(os.environ, {"PELISPLUSHD_BASE": self.base}), patch("subprocess.Popen", side_effect=AssertionError("No child processes")):
            result = self.engine.catalogue("movie", "pelisplushd")
            movie = next(m for m in result["metas"] if m["id"] == "pelisplushd:fixture")
            self.assertEqual("Película de prueba", movie["name"])
            self.assertEqual(self.base + "/poster.jpg", movie["poster"])
            self.assertTrue(any(m["type"] == "series" for m in result["metas"]))
            sources = self.engine.streams("movie", movie["id"])["streams"]
        self.assertEqual([self.base + "/video/fixture.m3u8"], [s["url"] for s in sources])
        self.assertIn("Referer", sources[0]["behaviorHints"]["requestHeaders"])
        self.assertIn("User-Agent", sources[0]["behaviorHints"]["requestHeaders"])
        self.assertEqual("PelisPlus", sources[0]["provider"])
        self.assertFalse(sources[0]["isLive"])

    def test_local_series_details_get_real_numbered_episodes_and_the_selected_episode(self):
        with patch.dict(os.environ, {"PELISPLUSHD_BASE": self.base}):
            self.engine.catalogue("movie", "pelisplushd")
            meta = self.engine.meta("series", "pelisplushd:serie-fixture")["meta"]
            self.assertEqual([1, 2], [v["episode"] for v in meta["videos"]])
            self.assertEqual("Sinopsis", meta["description"])
            sources = self.engine.streams("series", "pelisplushd:serie-fixture:1:2")["streams"]
        self.assertEqual([self.base + "/video/episode-2.mp4"], [s["url"] for s in sources])

    def test_anime_cards_have_episode_ids_and_real_detail_list(self):
        module = catalogs.module_for("animeflv")
        with patch.object(module, "BASE_URL", self.base):
            items = self.engine.catalogue("anime", "animeflv")["metas"]
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

    def test_stale_titles_remain_browsable_after_restart_when_source_is_offline(self):
        with patch("_embedded_catalogs.catalogue", return_value=[{"id": "gnula:fixture", "type": "movie", "name": "Fixture"}]):
            expected = self.engine.catalogue("movie", "gnula")
        other = embedded.Engine(self.temp.name, REGISTRY, LIVE)
        try:
            other.cache.clock = lambda: time.time() + 3700
            with patch("_embedded_catalogs.catalogue", side_effect=OSError("offline")):
                self.assertEqual(expected, other.catalogue("movie", "gnula"))
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
