"""Offline tests for the shared scraper kits (`server/scrapers/_*.py`).

Everything here is synthetic: no provider is contacted, no DNS is used. The
HTTP layer is driven through `_htmlkit.install_url_opener`, which is the same
seam the providers already expose, so the tests exercise the real code paths
(retries, decoding, quality inference, matching, soft-404 recovery).
"""
import gzip
import io
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "server/scrapers")]

import _animekit
import _hosters
import _htmlkit
import _peliskit
from _htmlkit import Deadline, guess_quality, is_media_url


# ---------------------------------------------------------------------------
# Fixture HTTP: un opener falso para `_htmlkit`
# ---------------------------------------------------------------------------


class FakeHeaders(dict):
    """Cabeceras mínimas: sólo lo que `_htmlkit` consulta."""

    def get_content_charset(self):
        content_type = self.get("Content-Type") or ""
        if "charset=" in content_type.lower():
            return content_type.lower().split("charset=")[-1].split(";")[0].strip()
        return None


class FakeResponse:
    def __init__(self, body, url, content_type="text/html; charset=utf-8", encoding=""):
        self.headers = FakeHeaders({"Content-Type": content_type, "Content-Encoding": encoding})
        self._body = body
        self._url = url

    def read(self, size=-1):
        return self._body[:size] if size >= 0 else self._body

    def geturl(self):
        return self._url

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


class RouteOpener:
    """`opener(request, timeout)` que sirve rutas de un diccionario."""

    def __init__(self, routes):
        self.routes = routes
        self.requests = []

    def __call__(self, request, timeout=None):
        url = getattr(request, "full_url", str(request))
        self.requests.append(url)
        entry = self.routes.get(url)
        if entry is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, io.BytesIO(b"nope"))
        if callable(entry):
            return entry(url)
        if isinstance(entry, FakeResponse):
            return entry
        if isinstance(entry, tuple):
            status, body = entry[:2]
            if status >= 400:
                raise urllib.error.HTTPError(url, status, "error", {}, io.BytesIO(body))
        return FakeResponse(entry, url)


class HttpLayerTest(unittest.TestCase):
    def setUp(self):
        self.addCleanup(_htmlkit.install_url_opener, None)

    def test_gzip_without_header_is_decoded(self):
        body = gzip.compress("<html><body>hola</body></html>".encode("utf-8"))
        opener = RouteOpener({"https://fx/x": FakeResponse(body, "https://fx/x")})
        _htmlkit.install_url_opener(opener)
        text, final_url, _ = _htmlkit.http_get("https://fx/x", {}, 2.0)
        self.assertEqual(text, "<html><body>hola</body></html>")
        self.assertEqual(final_url, "https://fx/x")

    def test_utf8_body_wins_over_wrong_declared_charset(self):
        body = "Pokémon: la película".encode("utf-8")
        opener = RouteOpener({
            "https://fx/x": FakeResponse(body, "https://fx/x", "text/html; charset=iso-8859-1"),
        })
        _htmlkit.install_url_opener(opener)
        text, _, _ = _htmlkit.http_get("https://fx/x", {}, 2.0)
        self.assertIn("Pokémon", text)
        self.assertNotIn("Ã", text)

    def test_meta_charset_is_used_when_nothing_else_helps(self):
        body = "<meta charset=windows-1252>Señor".encode("cp1252")
        opener = RouteOpener({"https://fx/x": FakeResponse(body, "https://fx/x", "text/html")})
        _htmlkit.install_url_opener(opener)
        text, _, _ = _htmlkit.http_get("https://fx/x", {}, 2.0)
        self.assertIn("Señor", text)

    def test_transient_503_is_retried_once(self):
        calls = {"n": 0}

        def flaky(url):
            calls["n"] += 1
            if calls["n"] == 1:
                raise urllib.error.HTTPError(url, 503, "unavailable", {}, io.BytesIO(b""))
            return FakeResponse("<html>ok</html>", url)

        _htmlkit.install_url_opener(RouteOpener({"https://fx/x": flaky}))
        text, _, _ = _htmlkit.http_get("https://fx/x", {}, 3.0)
        self.assertEqual(text, "<html>ok</html>")
        self.assertEqual(calls["n"], 2)

    def test_client_errors_are_not_retried(self):
        opener = RouteOpener({"https://fx/x": (404, b"missing")})
        _htmlkit.install_url_opener(opener)
        with self.assertRaises(urllib.error.HTTPError):
            _htmlkit.http_get("https://fx/x", {}, 3.0)
        self.assertEqual(len(opener.requests), 1)

    def test_timeout_is_not_retried(self):
        calls = {"n": 0}

        def slow(url):
            calls["n"] += 1
            raise TimeoutError("too slow")

        _htmlkit.install_url_opener(RouteOpener({"https://fx/x": slow}))
        with self.assertRaises(OSError):
            _htmlkit.http_get("https://fx/x", {}, 0.5)
        self.assertEqual(calls["n"], 1)


class MediaUrlTest(unittest.TestCase):
    def test_manifest_in_query_string_counts_as_media(self):
        self.assertTrue(is_media_url("https://cdn.example/player.php?id=7&file=master.m3u8"))
        self.assertTrue(is_media_url("https://cdn.example/play?url=https%3A%2F%2Fx%2Findex.mp4"))

    def test_pages_and_static_files_are_not_media(self):
        self.assertFalse(is_media_url("https://cdn.example/pelicula/ver/"))
        self.assertFalse(is_media_url("https://cdn.example/player.php?id=7"))
        self.assertFalse(is_media_url("https://doubleclick.example/x.m3u8"))


class QualityTest(unittest.TestCase):
    def test_labels_and_urls(self):
        cases = [
            (("Latino HD",), "720p"),
            (("HD 1080p",), "1080p"),
            (("FHD",), "1080p"),
            (("4K",), "4K"),
            (("UHD 2160p",), "4K"),
            (("SD",), "SD"),
            (("CAM",), "CAM"),
            (("https://cdn.example/1080/master.m3u8",), "1080p"),
            (("https://cdn.example/hls/720p/index.m3u8",), "720p"),
            (("", None), None),
            (("Streamwish",), None),
            (("https://hd.tubeload.example/e/1",), None),
            (("https://streamtape.example/v/ts/1",), None),
        ]
        for values, expected in cases:
            with self.subTest(values=values):
                self.assertEqual(guess_quality(*values), expected)

    def test_stream_builders_carry_the_quality(self):
        signal = {"url": "https://cdn.example/1080p/index.m3u8", "server": "Latino HD"}
        movie = _peliskit.build_streams("Fx", "Película (2026)", [signal], "UA")
        anime = _animekit.build_streams("Fx", "Anime", 4, [signal], "UA")
        self.assertEqual(movie[0]["quality"], "1080p")
        self.assertEqual(anime[0]["quality"], "1080p")

    def test_unknown_quality_stays_absent(self):
        signal = {"url": "https://cdn.example/index.m3u8", "server": "Streamwish"}
        streams = _peliskit.build_streams("Fx", "Película", [signal], "UA")
        self.assertNotIn("quality", streams[0])


class MatchTest(unittest.TestCase):
    def hit(self, title, slug=None):
        return {"title": title, "slug": slug or _htmlkit.slugify(title), "url": f"https://fx/{title}"}

    def test_exact_title_wins(self):
        hits = [self.hit("Dune: Parte Dos"), self.hit("Dune")]
        self.assertEqual(_animekit.best_hit("Dune", hits)["title"], "Dune")

    def test_release_noise_is_ignored(self):
        hits = [self.hit("Street Fighter Latino HD 1080p")]
        self.assertEqual(_animekit.match_score("Street Fighter", hits[0]), 100)

    def test_sequel_prefix_still_matches(self):
        score, hit = _animekit.best_match("Spider-Man", [self.hit("Spider-Man: No Way Home")])
        self.assertGreaterEqual(score, _animekit.STRONG_MATCH)
        self.assertIn("Spider-Man", hit["title"])

    def test_short_query_does_not_match_longer_word(self):
        self.assertIsNone(_animekit.best_hit("up", [self.hit("Upload")]))
        self.assertEqual(_animekit.match_score("up", self.hit("Up")), 100)

    def test_unrelated_hit_scores_zero(self):
        self.assertEqual(_animekit.match_score("One Piece", self.hit("Naruto Shippuden")), 0)


class ResolveAllTest(unittest.TestCase):
    def test_second_chance_rescues_a_flaky_server(self):
        calls = {"n": 0}
        real = _hosters.resolve

        def flaky(url, headers, deadline, budget, referer="", max_docs=2):
            calls["n"] += 1
            if url.endswith("/flaky") and calls["n"] == 1:
                return [], {"errors": ["request_failed"]}
            return [{"url": "https://cdn.example/index.m3u8", "label": "Streamwish",
                     "referer": referer}], {"errors": []}

        with patch.object(_hosters, "resolve", side_effect=flaky):
            signals, meta = _hosters.resolve_all(
                [{"name": "Streamwish", "url": "https://fx/flaky"}],
                {}, Deadline(8000), "https://fx/ep", parallel=2,
            )
        self.assertEqual(real, _hosters.resolve)
        self.assertEqual(len(signals), 1)
        self.assertEqual(meta["unresolved"], [])
        self.assertEqual(calls["n"], 2)

    def test_resolved_hosts_reports_what_worked(self):
        def only_wish(url, headers, deadline, budget, referer="", max_docs=2):
            if "wish" in url:
                return [{"url": "https://cdn.example/a.m3u8", "label": "Streamwish",
                         "referer": referer}], {"errors": []}
            return [], {"errors": ["http_404"]}

        with patch.object(_hosters, "resolve", side_effect=only_wish):
            _, meta = _hosters.resolve_all(
                [{"name": "Streamwish", "url": "https://fx/wish"},
                 {"name": "HQQ", "url": "https://fx/dead"}],
                {}, Deadline(8000), "https://fx/ep", parallel=2, second_chance=0,
            )
        self.assertEqual(meta["resolved_hosts"], ["Streamwish"])
        self.assertEqual(meta["unresolved"], ["HQQ"])


class ResolveSignalsTest(unittest.TestCase):
    def test_known_hosts_beyond_the_cap_are_kept_as_extra_failover(self):
        servers = [
            {"name": f"Raro {index}", "url": f"https://raro{index}.example/e/{index}"}
            for index in range(6)
        ] + [{"name": "Streamwish", "url": "https://streamwish.example/e/good"}]
        with patch.object(_peliskit, "resolve_all") as resolve_all:
            resolve_all.return_value = ([], {})
            _, meta = _peliskit.resolve_signals(
                servers, {}, Deadline(8000), "https://fx/page", parallel=2, max_servers=6,
            )
        resolved = [server["url"] for server in resolve_all.call_args[0][0]]
        self.assertEqual(len(resolved), 7)
        self.assertEqual(resolved[-1], "https://streamwish.example/e/good")

    def test_download_only_servers_are_dropped_before_the_cap(self):
        servers = [{"name": "Mega", "url": "https://mega.nz/file/1"}] + [
            {"name": f"S{index}", "url": f"https://s{index}.example/e/{index}"} for index in range(8)
        ]
        with patch.object(_peliskit, "resolve_all") as resolve_all:
            resolve_all.return_value = ([], {})
            _, meta = _peliskit.resolve_signals(servers, {}, Deadline(8000), "https://fx/page")
        resolved = [server["url"] for server in resolve_all.call_args[0][0]]
        self.assertNotIn("https://mega.nz/file/1", resolved)
        self.assertIn("Mega", meta["download_only"])


class SearchContinuationTest(unittest.TestCase):
    def test_weak_hit_does_not_stop_the_search_paths(self):
        site = {
            "id": "fx", "label": "Fx", "base": "https://fx/",
            "search_paths": ["/?s={query}", "/buscar?q={query}"],
            "search_hints": ["/ver/"],
        }
        pages = {
            "https://fx/?s=One+Piece": '<a href="/ver/one-day/">One Day</a>',
            "https://fx/buscar?q=One+Piece": '<a href="/ver/one-piece/">One Piece</a>',
        }

        def fake_fetch(url, headers, deadline):
            return pages.get(url, ""), url

        with patch.object(_peliskit, "fetch_html", side_effect=fake_fetch):
            hits = _peliskit.search_site(site, "One Piece", {}, Deadline(4000), ("/ver/",))
        self.assertEqual([hit["slug"] for hit in hits], ["one-day", "one-piece"])
        self.assertEqual(_animekit.best_hit("One Piece", hits)["slug"], "one-piece")

    def test_strong_hit_short_circuits(self):
        site = {
            "id": "fx", "label": "Fx", "base": "https://fx/",
            "search_paths": ["/?s={query}", "/buscar?q={query}"],
            "search_hints": ["/ver/"],
        }
        calls = []

        def fake_fetch(url, headers, deadline):
            calls.append(url)
            return '<a href="/ver/one-piece/">One Piece</a>', url

        with patch.object(_peliskit, "fetch_html", side_effect=fake_fetch):
            _peliskit.search_site(site, "One Piece", {}, Deadline(4000), ("/ver/",))
        self.assertEqual(len(calls), 1)


class SoftNotFoundTest(unittest.TestCase):
    """Un 200 con la portada (404 blando) ya no se queda en `no_servers`."""

    SITE = {
        "id": "fx", "label": "Fx", "base": "https://fx/",
        "movie_paths": ["/ver/{slug}/"],
        "search_paths": ["/?s={query}"],
        "search_hints": ["/ver/"],
        "not_found_markers": ["404"],
        "max_servers": 3,
    }

    def setUp(self):
        self.addCleanup(_htmlkit.install_url_opener, None)
        listing = "".join(
            f'<a href="/ver/pelicula-{index}/">Película {index}</a>' for index in range(20)
        )
        self.routes = {
            "https://fx/ver/bad-slug/": FakeResponse(
                f"<html><body><h1>Inicio</h1>{listing}{'x' * 500}</body></html>",
                "https://fx/ver/bad-slug/",
            ),
            "https://fx/?s=Bad+Slug": FakeResponse(
                '<html><body><a href="/ver/bad-slug-2026/">Bad Slug</a></body></html>',
                "https://fx/?s=Bad+Slug",
            ),
            "https://fx/ver/bad-slug-2026/": FakeResponse(
                '<html><body><h1>Bad Slug</h1>'
                '<iframe src="https://embed.example/e/1"></iframe></body></html>',
                "https://fx/ver/bad-slug-2026/",
            ),
            "https://embed.example/e/1": FakeResponse(
                '<html><body><source src="https://cdn.example/hls/index.m3u8"></body></html>',
                "https://embed.example/e/1",
            ),
        }
        _htmlkit.install_url_opener(RouteOpener(self.routes))

    def test_soft_404_falls_back_to_the_site_search(self):
        payload = {"id": "fx:bad-slug", "timeoutMs": 8000}
        streams, meta = _peliskit.scrape_site(payload, dict(self.SITE))
        self.assertEqual(meta["resolved_by"], "search_retry")
        self.assertEqual(meta["page"], "https://fx/ver/bad-slug-2026/")
        self.assertEqual([stream["url"] for stream in streams],
                         ["https://cdn.example/hls/index.m3u8"])

    def test_a_page_with_servers_is_not_retried(self):
        self.routes["https://fx/ver/bad-slug/"] = FakeResponse(
            '<html><body><h1>Bad Slug</h1>'
            '<iframe src="https://embed.example/e/1"></iframe>'
            f"{'x' * 500}</body></html>",
            "https://fx/ver/bad-slug/",
        )
        payload = {"id": "fx:bad-slug", "timeoutMs": 8000}
        streams, meta = _peliskit.scrape_site(payload, dict(self.SITE))
        self.assertEqual(meta["resolved_by"], "direct")
        self.assertEqual(len(streams), 1)


if __name__ == "__main__":
    unittest.main()
