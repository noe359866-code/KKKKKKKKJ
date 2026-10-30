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
import _livekit
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


class TypedSourceTest(unittest.TestCase):
    """`sources: [{file, type}]`: el `type` dice que es vídeo sin extensión."""

    def test_jwplayer_sources_without_extension(self):
        html = (
            '<script>var p = jwplayer("v").setup({sources: ['
            '{ file: "https://cdn.example/v/abc123", type: "application/x-mpegURL" },'
            '{ file: "https://cdn.example/v/def456", type: "video/mp4" },'
            '{ file: "https://cdn.example/arte/poster.jpg", type: "image/jpeg" }'
            ']});</script>'
        )
        self.assertEqual(
            _htmlkit.typed_media_urls(html, "https://fx/pelicula/x"),
            [("https://cdn.example/v/abc123", "hls"), ("https://cdn.example/v/def456", "mp4")],
        )

    def test_type_before_the_url(self):
        html = '<video><source type="video/mp4" src="https://cdn.example/v/stream"></video>'
        self.assertEqual(_htmlkit.typed_media_urls(html), [("https://cdn.example/v/stream", "mp4")])

    def test_relative_manifests_are_resolved(self):
        html = '<source src="/hls/master.m3u8"><script>file: "./v/clip.mp4"</script>'
        self.assertEqual(
            _htmlkit.relative_media_urls(html, "https://fx/pelicula/x/"),
            ["https://fx/hls/master.m3u8", "https://fx/pelicula/x/v/clip.mp4"],
        )
        self.assertIn(
            "https://fx/pelicula/x/v/clip.mp4",
            _htmlkit.media_urls(html, "https://fx/pelicula/x/"),
        )

    def test_media_kind_parsing(self):
        cases = [
            ("application/x-mpegURL", "hls"),
            ("HLS", "hls"),
            ("application/dash+xml", "dash"),
            ("video/mp4", "mp4"),
            ("image/jpeg", ""),
            ("text/html", ""),
        ]
        for value, expected in cases:
            with self.subTest(value=value):
                self.assertEqual(_htmlkit.media_kind(value), expected)
        self.assertEqual(_htmlkit.media_kind_of_url("https://x/y.m3u8?a=1"), "hls")
        self.assertEqual(_htmlkit.media_kind_of_url("https://x/y.mpd"), "dash")
        self.assertEqual(_htmlkit.media_kind_of_url("https://x/y"), "")

    def test_the_ficha_turns_typed_sources_into_direct_signals(self):
        html = (
            '<script>var p = jwplayer("v").setup({sources: ['
            '{ file: "https://cdn.example/v/abc123", type: "application/x-mpegURL" },'
            '{ file: "https://cdn.example/arte/poster.jpg", type: "image/jpeg" }'
            ']});</script>'
        )
        servers = _peliskit.harvest_servers(html, "https://fx/pelicula/x", {})
        self.assertEqual([server["url"] for server in servers], ["https://cdn.example/v/abc123"])
        self.assertTrue(servers[0]["direct"])
        self.assertEqual(servers[0]["media_type"], "hls")

    def test_a_stream_carries_the_type_in_the_behavior_hints(self):
        from _base import stream

        item = stream("https://cdn.example/v/abc123", "n", media_type="hls")
        self.assertEqual(item["behaviorHints"]["mediaType"], "hls")
        self.assertEqual(stream("https://x/y.m3u8", "n")["behaviorHints"], {"notWebReady": False})


class DownloadOnlyHostsTest(unittest.TestCase):
    def test_the_anime_site_download_host_is_not_a_stream(self):
        """JkAnime sirve sus descargas en `c1.jkplayers.com/d/<id>`."""
        self.assertTrue(_hosters.is_download_only("https://c1.jkplayers.com/d/PccD5609gkYcb/"))
        self.assertFalse(_hosters.is_download_only("https://streamwish.to/e/abc123"))


class ProbeTest(unittest.TestCase):
    def test_probe_recognises_what_it_downloads(self):
        cases = [
            (b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/i.m3u8", "hls"),
            (b'<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"></MPD>', "dash"),
            (b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00", "mp4"),
            (b"<!DOCTYPE html><html><body>404</body></html>", "html"),
            (b"", ""),
        ]
        for head, expected in cases:
            with self.subTest(expected=expected):
                with patch.object(_hosters, "http_peek", return_value=head):
                    self.assertEqual(
                        _hosters.probe_media_kind("https://cdn/v/abc", {}, Deadline(4000)),
                        expected,
                    )

    def test_resolve_marks_the_type_and_drops_pages(self):
        page = (
            '<html><body><script>var p = jwplayer("v").setup({sources: ['
            '{ file: "https://cdn.example/v/abc123", type: "application/x-mpegURL" }]});</script>'
            '<meta property="og:video" content="https://cdn.example/v/pagina">'
            '</body></html>'
        )

        def fake_get(url, headers, timeout, retries=1):
            return page, url, "text/html"

        def fake_peek(url, headers, timeout, size=2048):
            return b"<html><body>no soy un video</body></html>"

        with patch.object(_hosters, "http_get", side_effect=fake_get), \
                patch.object(_hosters, "http_peek", side_effect=fake_peek):
            signals, meta = _hosters.resolve(
                "https://embed.example/e/1", {}, Deadline(6000), referer="https://fx/x",
            )
        self.assertEqual([signal["url"] for signal in signals], ["https://cdn.example/v/abc123"])
        self.assertEqual(signals[0]["media_type"], "hls")
        self.assertIn("not_media", meta["errors"])


class FillTemplateTest(unittest.TestCase):
    def test_two_digit_placeholders(self):
        self.assertEqual(
            _peliskit.fill("/serie/{slug}/{season}x{episode2}", slug="x", season=1, episode=3),
            "/serie/x/1x03",
        )
        self.assertEqual(
            _peliskit.fill("/serie/{slug}/temporada/{season2}/capitulo/{episode2}",
                           slug="x", season=2, episode=10),
            "/serie/x/temporada/02/capitulo/10",
        )

    def test_season_zero_and_text_episodes_survive(self):
        self.assertEqual(_peliskit.fill("/v/{season2}/{episode2}", season=0, episode="1"),
                         "/v/00/01")
        self.assertEqual(_peliskit.fill("/ver/{slug}", slug="x"), "/ver/x")


class PlayerPageExpansionTest(unittest.TestCase):
    SITE = {"id": "fx", "label": "Fx", "base": "https://fx/"}

    def test_own_player_page_is_opened_and_replaced_by_its_embeds(self):
        def fake_fetch(url, headers, deadline):
            return '<html><body><iframe src="https://embed.example/e/1"></iframe></body></html>', url

        with patch.object(_peliskit, "fetch_html", side_effect=fake_fetch):
            servers, meta = _peliskit.expand_player_pages(
                [
                    {"name": "", "url": "https://fx/player/999"},
                    {"name": "Streamwish", "url": "https://streamwish.example/e/2"},
                ],
                "https://fx/pelicula/x", self.SITE, {}, Deadline(4000),
            )
        self.assertEqual([server["url"] for server in servers],
                         ["https://embed.example/e/1", "https://streamwish.example/e/2"])
        self.assertEqual(meta["opened_players"], ["https://fx/player/999"])

    def test_only_the_first_pages_are_opened(self):
        asked = []

        def fake_fetch(url, headers, deadline):
            asked.append(url)
            return '<html><body><iframe src="https://embed.example/e/1"></iframe></body></html>', url

        with patch.object(_peliskit, "fetch_html", side_effect=fake_fetch):
            _peliskit.expand_player_pages(
                [{"url": f"https://fx/player/{index}"} for index in range(3)],
                "https://fx/pelicula/x", self.SITE, {}, Deadline(4000), max_pages=1,
            )
        self.assertEqual(asked, ["https://fx/player/0"])

    def test_failed_page_is_kept_for_the_hoster_extractors(self):
        with patch.object(_peliskit, "fetch_html", return_value=("", "")):
            servers, meta = _peliskit.expand_player_pages(
                [{"name": "", "url": "https://fx/player/999"}],
                "https://fx/pelicula/x", self.SITE, {}, Deadline(4000),
            )
        self.assertEqual([server["url"] for server in servers], ["https://fx/player/999"])
        self.assertEqual(meta["opened_players"], ["https://fx/player/999"])

    def test_what_we_can_already_resolve_is_not_opened(self):
        for url, expected in [
            ("https://fx/player/1", True),
            ("https://player.fx/embed/1", True),
            ("https://otro.example/reproductor/abc", True),
            ("https://streamwish.to/e/abc", False),
            ("https://mega.nz/file/abc", False),
            ("https://cdn.example/x/index.m3u8", False),
            ("https://otro.example/pelicula/spider-man", False),
        ]:
            with self.subTest(url=url):
                self.assertEqual(_peliskit.looks_like_own_player(url, "https://fx/"), expected)


class FallbackQueryTest(unittest.TestCase):
    SITE = {
        "id": "fx", "label": "Fx", "base": "https://fx/",
        "search_paths": ["/?s={query}"],
        "search_hints": ["/ver/"],
    }

    def test_year_and_parentheses_are_stripped(self):
        self.assertEqual(
            _peliskit.fallback_queries("Spider-Man: No Way Home (2021)"),
            ["Spider-Man: No Way Home (2021)", "Spider-Man: No Way Home"],
        )
        self.assertEqual(
            _peliskit.fallback_queries("Dune Parte Dos 2024"),
            ["Dune Parte Dos 2024", "Dune Parte Dos"],
        )
        self.assertEqual(_peliskit.fallback_queries(""), [])

    def test_search_retries_with_the_clean_title(self):
        import urllib.parse

        asked = []

        def fake_fetch(url, headers, deadline):
            query = urllib.parse.unquote(url.split("s=")[-1]).replace("+", " ")
            asked.append(query)
            if "2021" in query:
                return '<a href="/ver/otra-cosa/">Otra cosa</a>', url
            return '<a href="/ver/spider-man-no-way-home/">Spider-Man: No Way Home</a>', url

        with patch.object(_peliskit, "fetch_html", side_effect=fake_fetch):
            url = _peliskit.find_by_search(
                self.SITE, "Spider-Man: No Way Home (2021)", {}, Deadline(4000),
            )
        self.assertEqual(url, "https://fx/ver/spider-man-no-way-home/")
        self.assertEqual(asked, ["Spider-Man: No Way Home (2021)", "Spider-Man: No Way Home"])


class EpisodeFallbackTest(unittest.TestCase):
    """Un capítulo sin reproductores se rescata desde la ficha del anime."""

    ANIME_PAGE = "https://anime.example/anime/one-piece"
    EPISODE_PAGE = "https://anime.example/ver/one-piece-1180"
    LATINO_PAGE = "https://anime.example/ver/one-piece-1180-latino"

    def setUp(self):
        self.addCleanup(_htmlkit.install_url_opener, None)

    def install(self):
        ficha = (
            "<html><body><h1>One Piece</h1><ul class=\"ListEpisodios\">"
            "<li><a href=\"/ver/one-piece-1179\">Episodio 1179</a></li>"
            "<li><a href=\"/ver/one-piece-1180-latino\">Episodio 1180</a></li>"
            "</ul>" + "x" * 500 + "</body></html>"
        )
        episode = (
            "<html><body><h1>One Piece</h1><div class=\"player\">"
            "<iframe src=\"/ext/streamwish\"></iframe></div>" + "y" * 500 + "</body></html>"
        )
        return RouteOpener({
            self.ANIME_PAGE: FakeResponse(ficha, self.ANIME_PAGE),
            self.LATINO_PAGE: FakeResponse(episode, self.LATINO_PAGE),
        })

    def test_the_episode_number_at_the_end_of_an_href_matches(self):
        html = ('<a href="/ver/one-piece-1180">Episodio 1180</a>'
                '<a href="/ver/one-piece-1">Episodio 1</a>')
        self.assertEqual(_peliskit.candidate_episode_links(html, 1, 1180), ["/ver/one-piece-1180"])
        # El episodio 1 no casa con `-1180`: el número lleva a la derecha un dígito.
        self.assertEqual(_peliskit.candidate_episode_links(html, 1, 1), ["/ver/one-piece-1"])

    def test_servers_are_found_on_the_anime_page_when_the_episode_has_none(self):
        opener = self.install()
        _htmlkit.install_url_opener(opener)

        servers, page = _peliskit.fallback_episode_servers(
            "<html><body><h1>One Piece</h1><p>Sin servidores</p></body></html>",
            self.EPISODE_PAGE,
            {},
            {},
            Deadline(8000),
            episode=1180,
            anime_url=self.ANIME_PAGE,
        )

        self.assertTrue(servers)
        self.assertEqual(page, self.LATINO_PAGE)
        self.assertEqual(servers[0]["url"], "https://anime.example/ext/streamwish")
        # Sólo se abre la ficha y la página que sí tiene reproductores.
        self.assertEqual(opener.requests, [self.ANIME_PAGE, self.LATINO_PAGE])

    def test_the_number_of_pages_is_capped(self):
        opener = self.install()
        _htmlkit.install_url_opener(opener)

        servers, page = _peliskit.fallback_episode_servers(
            "<html><body>Sin nada</body></html>",
            self.EPISODE_PAGE,
            {},
            {},
            Deadline(8000),
            episode=1180,
            anime_url=self.ANIME_PAGE,
            max_pages=1,
        )

        self.assertEqual((servers, page), ([], ""))
        self.assertEqual(opener.requests, [self.ANIME_PAGE])


class EpisodeLinkServersTest(unittest.TestCase):
    """Ficha → página del capítulo: cinco enlaces, no sólo los tres primeros."""

    def setUp(self):
        self.addCleanup(_htmlkit.install_url_opener, None)

    def test_the_fourth_episode_link_is_still_tried(self):
        links = "".join(f'<a href="/cap/{name}/1x04">Episodio 4</a>' for name in "abcd")
        page = "<html><body><h1>Una serie</h1>" + links + "z" * 500 + "</body></html>"
        routes = {
            f"https://fx/cap/{name}/1x04": FakeResponse(
                "<html><body><h1>Otro capítulo</h1>" + "w" * 500 + "</body></html>",
                f"https://fx/cap/{name}/1x04",
            )
            for name in "abc"
        }
        routes["https://fx/cap/d/1x04"] = FakeResponse(
            '<html><body><h1>Episodio 4</h1><iframe src="/ext/streamwish"></iframe>'
            + "w" * 500 + "</body></html>",
            "https://fx/cap/d/1x04",
        )
        _htmlkit.install_url_opener(RouteOpener(routes))

        servers, page_url = _peliskit.episode_link_servers(
            page, "https://fx/serie/una-serie", 1, 4, {}, Deadline(8000)
        )

        self.assertEqual(page_url, "https://fx/cap/d/1x04")
        self.assertEqual([server["url"] for server in servers],
                         ["https://fx/ext/streamwish"])


class PageForTest(unittest.TestCase):
    """Elección de ficha: variantes de slug, fichas extra y tope de pedidos."""

    def setUp(self):
        self.addCleanup(_htmlkit.install_url_opener, None)

    def test_slug_candidates_are_tried_after_the_first_slug_runs_out(self):
        page = "<html><body><h1>Una serie</h1>" + "z" * 600 + "</body></html>"
        opener = RouteOpener({
            "https://fx/serie/una-serie-2026/1/2": FakeResponse(page, "https://fx/serie/una-serie-2026/1/2"),
        })
        _htmlkit.install_url_opener(opener)
        site = {
            "id": "fx",
            "base": "https://fx",
            "series_paths": ["/serie/{slug}/{season}/{episode}", "/serie/{slug}"],
            "search_paths": [],
            "search_hints": [],
        }
        ref = {"slug": "una-serie", "slug_candidates": ["una-serie-2026"], "season": 1, "episode": 2}

        url, how = _peliskit.page_for(ref, site, {}, Deadline(8000))

        self.assertEqual(how, "direct")
        self.assertEqual(url, "https://fx/serie/una-serie-2026/1/2")

    def test_ficha_paths_add_the_anime_page_after_the_series_one(self):
        page = "<html><body><h1>Un anime</h1>" + "z" * 600 + "</body></html>"
        opener = RouteOpener({
            "https://fx/anime/un-anime": FakeResponse(page, "https://fx/anime/un-anime"),
        })
        _htmlkit.install_url_opener(opener)
        site = {
            "id": "fx",
            "base": "https://fx",
            "series_paths": [
                "/serie/{slug}/temporada/{season}/capitulo/{episode}",
                "/serie/{slug}",
            ],
            "ficha_paths": ["/anime/{slug}"],
            "search_paths": [],
            "search_hints": [],
        }
        ref = {"slug": "un-anime", "season": 1, "episode": 3}

        url, how = _peliskit.page_for(ref, site, {}, Deadline(8000))

        self.assertEqual(how, "direct")
        self.assertEqual(url, "https://fx/anime/un-anime")


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


# ---------------------------------------------------------------------------
# Kit de directo: lo que comparten livelive24 / damitv / ntv / streamcorner
# ---------------------------------------------------------------------------


class LiveKitTests(unittest.TestCase):
    """El kit sólo publica señales reales, nunca la web del proveedor."""

    def test_decodes_the_manifest_hidden_in_a_player_query(self):
        url = _livekit.decode_embedded_url(
            "/player/jw/?url=%2Fc%2F346.m3u8&name=Willow%20Cricket", "https://damitv.st"
        )
        self.assertEqual(url, "https://damitv.st/c/346.m3u8")
        # Sin pista de manifiesto no se inventa nada.
        self.assertEqual(_livekit.decode_embedded_url("/embed/?id=abc", "https://damitv.st"), "")

    def test_media_candidates_cover_data_attributes_js_and_typed_sources(self):
        html = (
            '<div data-src="/hls/a.m3u8"></div>'
            '<script>var p={file:"https:\\/\\/cdn.example\\/v\\/b.m3u8"};</script>'
            '<video><source src="https://cdn.example/c.m3u8" type="application/x-mpegURL"></video>'
        )
        found = _livekit.media_candidates(html, "https://site.example/watch/1")
        self.assertIn("https://site.example/hls/a.m3u8", found)
        self.assertIn("https://cdn.example/v/b.m3u8", found)
        self.assertIn("https://cdn.example/c.m3u8", found)
        self.assertTrue(all(item.endswith(".m3u8") for item in found))

    def test_anchor_entries_skip_source_labels_and_other_site_links(self):
        html = (
            '<a href="/watch/espn"><span class="src">Football24</span> ESPN</a>'
            '<a href="https://otro.example/watch/x">Ajeno</a>'
            '<a href="/contacto">Contacto</a>'
        )
        entries = _livekit.anchor_entries(html, "https://livelive24.com/", hints=("/watch/",))
        names = [entry["name"] for entry in entries]
        # La etiqueta de origen desaparece; los enlaces de navegación no entran.
        self.assertEqual(names, ["ESPN", "Ajeno"])
        self.assertNotIn("Contacto", names)
        self.assertEqual(entries[0]["id"], "watch/espn")

    def test_build_live_streams_marks_hls_and_deduplicates(self):
        media = [
            {"url": "https://cdn.example/live/index.m3u8", "referer": "https://site.example/", "source": "Sky"},
            {"url": "https://cdn.example/live/index.m3u8", "referer": "https://site.example/", "source": "dup"},
            {"url": "https://cdn.example/live/backup.m3u8", "hd": 1, "source": "Backup"},
        ]
        streams = _livekit.build_live_streams("NTV", "NFL Network", media, "UA/1.0")
        self.assertEqual(len(streams), 2)
        first = streams[0]
        self.assertEqual(first["isLive"], True)
        self.assertEqual(first["behaviorHints"]["mediaType"], "hls")
        self.assertEqual(first["referer"], "https://site.example/")
        self.assertEqual(first["userAgent"], "UA/1.0")
        self.assertIn("NFL Network", first["title"])
        self.assertIn("Sky", first["title"])
        self.assertEqual(streams[1]["quality"], "720p")

    def test_env_bases_never_falls_back_to_the_real_site(self):
        with patch.dict("os.environ", {"XX_BASE": "http://127.0.0.1:1", "XX_MIRRORS": "http://127.0.0.1:2"}):
            self.assertEqual(
                _livekit.env_bases("https://real.example", "XX_BASE", "XX_MIRRORS"),
                ["http://127.0.0.1:1", "http://127.0.0.1:2"],
            )
        with patch.dict("os.environ", {"XX_BASE": "", "XX_MIRRORS": ""}):
            self.assertEqual(
                _livekit.env_bases("https://real.example", "XX_BASE", "XX_MIRRORS"),
                ["https://real.example"],
            )

    def test_strip_live_prefix_keeps_channels_that_start_with_live(self):
        self.assertEqual(_livekit.strip_live_prefix("LiveNFL Network"), "NFL Network")
        self.assertEqual(_livekit.strip_live_prefix("Live NBA TV"), "NBA TV")
        self.assertEqual(_livekit.strip_live_prefix("Liverpool TV"), "Liverpool TV")

    def test_page_title_drops_the_site_suffix(self):
        self.assertEqual(
            _livekit.page_title("<title>NFL Network - Watch Live Stream | NTVSTREAM</title>"),
            "NFL Network",
        )
        self.assertEqual(_livekit.page_title("<title>NFL NETWORK - StreamCorner</title>", "StreamCorner"),
                         "NFL NETWORK")
        self.assertEqual(_livekit.page_title("<title>ESPN 2 en vivo | Live24</title>"), "ESPN 2")

    def test_harvest_follows_the_player_iframe_and_stops_at_the_first_signal(self):
        class Opener(RouteOpener):
            def __init__(self):
                super().__init__({
                    "https://site.example/watch/1": FakeResponse(
                        '<html><head><title>Canal Uno - Watch Live</title></head><body>'
                        '<iframe src="/player.php?id=7"></iframe>'
                        '<a href="/watch/2">otro canal</a></body></html>',
                        "https://site.example/watch/1",
                    ),
                    "https://site.example/player.php?id=7": FakeResponse(
                        '<html><body><script>var s={file:"https:\\/\\/cdn.example\\/uno\\/a.m3u8"};'
                        "</script></body></html>",
                        "https://site.example/player.php?id=7",
                    ),
                })

        opener = Opener()
        _htmlkit.install_url_opener(opener)
        try:
            media, meta = _livekit.harvest(
                "https://site.example/watch/1",
                {"User-Agent": "UA/1.0", "Referer": "https://site.example/"},
                Deadline(8000),
            )
        finally:
            _htmlkit.install_url_opener(None)

        self.assertEqual([item["url"] for item in media],
                         ["https://cdn.example/uno/a.m3u8"])
        self.assertEqual(meta["title"], "Canal Uno")
        # La ficha dice "otro canal": es navegación, no un reproductor.
        self.assertNotIn("/watch/2", opener.requests)


if __name__ == "__main__":
    unittest.main()
