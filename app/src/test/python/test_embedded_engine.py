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


class NativeEngineBridgeTests(unittest.TestCase):
    def test_subtitle_bridge_and_python_fallback(self):
        class Tools:
            def normalizeLanguage(self, value): return "native-language"
            def languageName(self, value): return "native-label"
            def subtitleFormat(self, value): return "vtt"
            def normalizeSubtitleJson(self, item, provider):
                if json.loads(item).get("url") == "fallback": return ""
                return json.dumps({"url": "https://native.invalid", "provider": json.loads(provider)["label"]})
            def mergeSubtitleIndicesJson(self, rows): return "[1,0]"

        with patch.object(embedded, "_native_engine_tools", Tools()):
            self.assertEqual("native-language", embedded.normalize_language("en"))
            self.assertEqual("native-label", embedded.language_name("en"))
            self.assertEqual("vtt", embedded.subtitle_format("x"))
            self.assertEqual({"url": "https://native.invalid", "provider": "P"},
                             embedded.normalize_subtitle({"url": "x"}, {"label": "P"}))
            self.assertIsNone(embedded.normalize_subtitle({"url": "fallback"}, {"label": "P"}))
            rows = [{"url": "a"}, {"url": "b"}]
            merged = embedded.merge_subtitles(rows)
            self.assertIs(merged[0], rows[1])
            self.assertIs(merged[1], rows[0])
            # Unpaired surrogates must never cross the Java string bridge.
            self.assertEqual("", embedded.normalize_language("\ud800"))
            self.assertEqual("", embedded.language_name("\ud800"))
            self.assertIsNone(embedded.subtitle_format("\ud800"))

    def test_metadata_bridge_and_fallback(self):
        class Tools:
            def normalizeMetadataJson(self, value, media_type):
                raw = json.loads(value)
                if raw.get('id') == 'fallback': return ''
                if not raw.get('id'): return 'null'
                return json.dumps({'id': raw['id'], 'type': media_type, 'name': 'native', 'genres': []})
        with patch.object(embedded, "_native_engine_tools", Tools()):
            self.assertEqual('native', embedded.normalize_meta({'id': 'tt1'}, 'movie')['name'])
            self.assertIsNone(embedded.normalize_meta({}, 'movie'))
            self.assertEqual('Python', embedded.normalize_meta({'id': 'fallback', 'name': 'Python'}, 'movie')['name'])
            # Non-string keys in nested videos are not silently converted to JSON keys.
            self.assertEqual('fallback', embedded.normalize_meta({'id': 'fallback', 'videos': [{1: 'x'}]}, 'movie')['id'])

    def test_playability_bridge_keeps_url_parse_and_fallback(self):
        class Tools:
            def __init__(self): self.calls = []
            def playableDirect(self, path, query):
                self.calls.append((path, query))
                return path.endswith(".mp4")
            def playablePage(self, path, hint):
                self.calls.append((path, hint))
                return path == "/allowed"
        tools = Tools()
        with patch.object(embedded, "_native_engine_tools", tools):
            self.assertFalse(embedded.is_playable_stream("file:///allowed"))
            self.assertTrue(embedded.is_playable_stream("https://host/CLIP.MP4"))
            self.assertTrue(embedded.is_playable_stream("https://host/allowed", {"mediaType": " HLS "}))
            self.assertFalse(embedded.is_playable_stream("https://host/player/a"))
            self.assertIn(("/allowed", "hls"), tools.calls)
            before = len(tools.calls)
            self.assertTrue(embedded.is_playable_stream("https://host/\ud800"))
            self.assertEqual(before, len(tools.calls))

    def test_stream_parts_bridge_preserves_other_hint_objects(self):
        class Tools:
            def normalizeStreamPartsJson(self, raw, provider, headers):
                self.headers = json.loads(headers)
                return json.dumps({'name': 'Native', 'title': 'Native title', 'provider': 'P', 'headers': {'Cookie': 'x=1'}})
        tools = Tools()
        nested = {'nested': ['retain identity']}
        raw = {'url': 'https://site/video.mp4', 'behaviorHints': {'custom': nested, 'requestHeaders': {'cookie': 'x=1'}}}
        with patch.object(embedded, '_native_engine_tools', tools), \
                patch.object(embedded, 'is_playable_stream', return_value=True), \
                patch.object(embedded, 'declared_expiry', return_value=None):
            result = embedded.normalize_stream(raw, {'label': 'P'})
        self.assertEqual('Native', result['name'])
        self.assertEqual({'Cookie': 'x=1'}, result['behaviorHints']['requestHeaders'])
        self.assertIs(nested, result['behaviorHints']['custom'])
        self.assertEqual('x=1', tools.headers['cookie'])

    def test_expiry_bridge_and_unicode_fallback(self):
        class Tools:
            def declaredExpiryJson(self, query, token):
                parsed = json.loads(query)
                self.query, self.token = parsed, json.loads(token)
                return '' if any(not k.isascii() or any(not v.isascii() for v in values) for k, values in parsed.items()) else '1600000000'
        tools = Tools()
        with patch.object(embedded, '_native_engine_tools', tools):
            self.assertEqual(1600000000, embedded.declared_expiry('https://site/a?e=1700000000&hdnea=exp%3D1600000000'))
            self.assertEqual(1700000000, tools.token)
            self.assertEqual(1600000000, embedded.declared_expiry('https://site/a?exp=١٦٠٠٠٠٠٠٠٠'))

    def test_headers_bridge_and_non_string_key_fallback(self):
        class Tools:
            def __init__(self): self.calls = []
            def normalizeHeadersJson(self, value):
                self.calls.append(json.loads(value))
                return '' if self.calls[-1].get('odd') == 42 else '{"Referer":"native"}'
        tools = Tools()
        with patch.object(embedded, "_native_engine_tools", tools):
            self.assertEqual({"Referer": "native"}, embedded.normalize_stream_headers({"referer": "x"}))
            self.assertEqual({"odd": "42"}, embedded.normalize_stream_headers({"odd": 42}))
            before = len(tools.calls)
            self.assertEqual({"5": "ok"}, embedded.normalize_stream_headers({5: "ok"}))
            self.assertEqual(before, len(tools.calls))

    def test_episode_link_bridge_preserves_dedup_and_provider_fallback(self):
        class Tools:
            def __init__(self): self.calls = []
            def catalogEpisodeLinksJson(self, links, refs, slug, anime):
                self.calls.append((json.loads(links), json.loads(refs), slug, anime))
                return '[[1,2,0],[1,2,1]]'
            def catalogEpisodeVideosJson(self, entries, base_id):
                self.rendered = (json.loads(entries), base_id)
                return ''  # Exercise the existing fallback for final rendering.
        tools = Tools()
        html = '<a href="/fixture/1/2">First</a><a href="/fixture/1/2">Second</a>'
        with patch.object(catalogs, '_native_engine_tools', tools):
            result = catalogs.episode_videos('gnula', 'gnula:fixture', 'fixture', html, 'https://site/')
        self.assertEqual([{'id': 'gnula:fixture:1:2', 'season': 1, 'episode': 2, 'name': 'Second'}], result)
        self.assertEqual(2, len(tools.calls[0][0]))
        self.assertFalse(tools.calls[0][3])

    def test_native_catalog_document_bridge_and_replay(self):
        class NativeDocument:
            def __init__(self, fallback): self.events = []; self.fallback = fallback
            def start(self, tag, attrs): self.events.append(('start', tag, json.loads(attrs))); return True
            def data(self, text): self.events.append(('data', text)); return not self.fallback
            def end(self, tag): self.events.append(('end', tag))
            def snapshot(self): return json.dumps({'links': [{'url': 'native'}], 'meta': {'title': 'native'}, 'heading': ['native']})
        class Factory:
            def __init__(self, fallback): self.fallback = fallback
            def create(self, page, urljoin):
                self.page, self.urljoin = page, urljoin
                self.document = NativeDocument(self.fallback)
                return self.document
        factory = Factory(False)
        with patch.object(catalogs, '_native_document_factory', factory):
            document = catalogs.Document('<div>ignored</div><a href="/show">Show</a>', 'https://site/')
        self.assertEqual('native', document.links[0]['url'])
        self.assertEqual('https://site/show', factory.urljoin(factory.page, '/show'))
        self.assertNotIn(('data', 'ignored'), factory.document.events)
        self.assertIn(('data', 'Show'), factory.document.events)
        fallback = Factory(True)
        with patch.object(catalogs, '_native_document_factory', fallback):
            document = catalogs.Document('<h1>Heading</h1><a href="/show">Show</a>', 'https://site/')
        self.assertEqual(['Heading'], document.heading)
        self.assertEqual('https://site/show', document.links[0]['url'])
        self.assertEqual('Show', document.links[0]['title'])

    def test_episode_render_bridge_receives_deduplicated_rows(self):
        class Tools:
            def catalogEpisodeLinksJson(self, links, refs, slug, anime): return '[[1,2,0],[1,2,1],[2,3,2]]'
            def catalogEpisodeVideosJson(self, entries, base_id):
                self.entries = json.loads(entries)
                self.base_id = base_id
                return '[{"id":"native","season":1,"episode":2,"name":"rendered"}]'
        tools = Tools()
        html = '<a href="/x">First</a><a href="/y">Second</a><a href="/z">Third</a>'
        with patch.object(catalogs, '_native_engine_tools', tools):
            result = catalogs.episode_videos('gnula', 'gnula:show', 'show', html, 'https://site/')
        self.assertEqual('native', result[0]['id'])
        self.assertEqual([[1, 2, 'Second'], [2, 3, 'Third']], tools.entries)
        self.assertEqual('gnula:show', tools.base_id)

    def test_catalog_card_bridge_and_unicode_fallback(self):
        class Tools:
            def __init__(self): self.calls = []
            def catalogCardRefJson(self, provider, path, default_type, anime):
                self.calls.append((provider, path, default_type, anime))
                return 'null' if path == 'anime/buscar' else json.dumps({"id": "native", "type": "anime", "slug": "x", "episode": None})
        tools = Tools()
        with patch.object(catalogs, "_native_engine_tools", tools):
            self.assertIsNone(catalogs.card_ref("animeflv", "https://other/anime/x", "https://site/"))
            self.assertEqual("native", catalogs.card_ref("animeflv", "https://site/anime/x", "https://site/")["id"])
            self.assertEqual(("animeflv", "anime/x", "movie", True), tools.calls[-1])
            self.assertIsNone(catalogs.card_ref("animeflv", "https://site/anime/buscar", "https://site/"))
            before = len(tools.calls)
            self.assertEqual("gnula:niño", catalogs.card_ref("gnula", "https://site/pelicula/ni%C3%B1o", "https://site/")["id"])
            self.assertEqual(before, len(tools.calls))

    def test_catalog_id_bridge_and_fallback(self):
        class Tools:
            def splitCatalogIdJson(self, content_id, media_type, anime_provider):
                self.last = (content_id, media_type, anime_provider)
                return '' if '999999999999' in content_id else '["native",1,2]'
        tools = Tools()
        with patch.object(catalogs, "_native_engine_tools", tools):
            self.assertEqual(("native", 1, 2), catalogs.split_id("animeflv:show:2", "anime"))
            self.assertEqual(("animeflv:show:2", "anime", True), tools.last)
            self.assertEqual(("show", 1, 999999999999), catalogs.split_id("show:1:999999999999", "series"))
            self.assertEqual(("show", 1, 2), catalogs.split_id("show:1:٢", "series"))


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

    def test_registry_bridge_preserves_provider_identity_and_priority(self):
        class Tools:
            def registryIndicesJson(self, rows, allowed):
                self.rows, self.allowed = json.loads(rows), json.loads(allowed)
                return '[1,0]'
        tools = Tools()
        a = {'id': 'gnula', 'script': 'gnula.py', 'label': 'first', 'priority': 2}
        b = {'id': 'cuevana', 'script': 'cuevana.py', 'label': 'second', 'priority': 1}
        with patch.object(embedded, '_native_engine_tools', tools):
            other = embedded.Engine(self.temp.name, {'providers': [a, b]}, self.base)
            try:
                self.assertEqual(['cuevana', 'gnula'], list(other.providers))
                self.assertIs(a, other.providers['gnula'])
                self.assertIs(b, other.providers['cuevana'])
                self.assertIn('gnula', tools.allowed)
            finally:
                other.close()

    def test_native_dispatch_plan_routes_and_preserves_python_fallback(self):
        class Tools:
            def dispatchPlanJson(self, operation, encoded):
                request = json.loads(encoded)
                if request.get('id') == 'fallback': return ''
                if operation == 'catalog':
                    return json.dumps({'action': 'catalog', 'type': 'movie', 'id': 'native', 'search': 's', 'genre': 'g', 'skip': 2})
                if operation == 'streams': return json.dumps({'action': 'streams', 'type': 'movie', 'id': 'native'})
                return '{"action":"invalid"}'
        with patch.object(embedded, '_native_engine_tools', Tools()), \
                patch.object(self.engine, 'catalogue', return_value={'metas': []}) as catalogue, \
                patch.object(self.engine, 'streams', return_value={'streams': []}) as streams:
            self.assertEqual({'metas': []}, self.engine.dispatch('catalog', {'type': 'movie', 'id': 'x'}))
            catalogue.assert_called_once_with('movie', 'native', 's', 'g', 2)
            self.assertEqual({'streams': []}, self.engine.dispatch('streams', {'type': 'movie', 'id': 'x'}))
            self.assertEqual({'streams': []}, self.engine.dispatch('streams', {'type': 'movie', 'id': 'fallback'}))
            self.assertEqual([('movie', 'native'), ('movie', 'fallback')], [call.args for call in streams.call_args_list])
            with self.assertRaisesRegex(ValueError, 'Invalid content request'):
                self.engine.dispatch('meta', {'type': 'bad', 'id': 'x'})

    def test_catalog_descriptors_use_native_helper(self):
        class Tools:
            def __init__(self): self.calls = []
            def catalogDescriptorsJson(self, enabled):
                self.calls.append(enabled)
                return json.dumps({'catalogs': [{'id': 'native'}] if enabled else []})
        tools = Tools()
        with patch.object(embedded, '_native_engine_tools', tools):
            self.assertEqual({'catalogs': []}, self.engine.catalogs(False))
            self.assertEqual({'catalogs': [{'id': 'native'}]}, self.engine.catalogs(True))
        self.assertEqual([False, True], tools.calls)

    def test_native_scheduler_bridge_passes_callable_and_provider_objects(self):
        class Ticket:
            def __init__(self, result): self.value = result
            def awaitReady(self, timeout): return True
            def isDone(self): return True
            def isCancelled(self): return False
            def cancelIfQueued(self): return False
            def result(self): return self.value
        class Scheduler:
            def __init__(self): self.calls = []; self.closed = False
            def submit(self, key, callable_, provider, payload):
                self.calls.append((key, provider, payload))
                return Ticket(callable_(provider, payload))
            def shutdown(self): self.closed = True
        scheduler = Scheduler()
        self.engine.native_scheduler = scheduler
        provider = self.engine.providers['gnula']
        stream = {'url': 'https://cdn.invalid/video.mp4'}
        with patch.object(self.engine, '_scrape', return_value={'streams': [stream], 'subtitles': []}), \
                patch.object(embedded, 'declared_expiry', return_value=None):
            result = self.engine.streams('movie', 'gnula:fixture')
        self.assertIs(stream, result['streams'][0])
        self.assertIs(provider, scheduler.calls[0][1])
        self.assertEqual('gnula:fixture', scheduler.calls[0][2]['id'])
        self.engine.close()
        self.assertTrue(scheduler.closed)
        self.engine.native_scheduler = None

    def test_native_scheduler_cancels_pending_work_at_deadline(self):
        class Ticket:
            def __init__(self): self.cancelled_flag = False; self.waited = []
            def awaitReady(self, timeout): self.waited.append(timeout); return False
            def isDone(self): return self.cancelled_flag
            def isCancelled(self): return self.cancelled_flag
            def cancelIfQueued(self): self.cancelled_flag = True; return True
            def result(self): raise AssertionError('cancelled work must not be read')
        class Scheduler:
            def __init__(self): self.ticket = Ticket()
            def submit(self, *args): return self.ticket
            def shutdown(self): pass
        scheduler = Scheduler()
        self.engine.native_scheduler = scheduler
        result = self.engine.streams('movie', 'gnula:fixture')
        self.assertEqual([], result['streams'])
        self.assertTrue(scheduler.ticket.cancelled_flag)
        self.assertGreater(scheduler.ticket.waited[0], 0)

    def test_native_scheduler_failure_does_not_hide_other_providers(self):
        class Ticket:
            def __init__(self, value): self.value = value
            def awaitReady(self, timeout): return True
            def isDone(self): return True
            def isCancelled(self): return False
            def cancelIfQueued(self): return False
            def result(self):
                if self.value is None: raise ValueError('failed')
                return self.value
        class Scheduler:
            def submit(self, key, callable_, provider, payload):
                return Ticket(None if provider['id'] == 'gnula' else {'streams': [{'url': 'https://cdn.invalid/a.mp4'}], 'subtitles': []})
            def shutdown(self): pass
        self.engine.native_scheduler = Scheduler()
        self.engine.providers = {name: self.engine.providers[name] for name in ('gnula', 'cuevana')}
        with patch.object(embedded, 'declared_expiry', return_value=None):
            result = self.engine.streams('movie', 'fixture')
        self.assertEqual(['https://cdn.invalid/a.mp4'], [item['url'] for item in result['streams']])

    def test_native_stream_dedup_keeps_first_provider_and_object(self):
        first = {'url': 'https://cdn.invalid/x.mp4#one'}
        duplicate = {'url': 'https://cdn.invalid/x.mp4#two'}
        second = {'url': 'https://cdn.invalid/y.mp4'}
        class Tools:
            def selectProviderIndicesJson(self, names, explicit, allowed): return '[0,1]'
            def uniqueStreamIndicesJson(self, urls, previous):
                seen, indices = set(json.loads(previous)), []
                for index, url in enumerate(json.loads(urls)):
                    key = url.split('#', 1)[0]
                    if key not in seen:
                        indices.append(index)
                        seen.add(key)
                return json.dumps(indices)
        self.engine.providers = {name: self.engine.providers[name] for name in ('gnula', 'cuevana')}
        def scrape(provider, payload):
            return {'streams': [first] if provider['id'] == 'gnula' else [duplicate, second], 'subtitles': []}
        with patch.object(embedded, '_native_engine_tools', Tools()), \
                patch.object(self.engine, '_scrape', side_effect=scrape), \
                patch.object(embedded, 'declared_expiry', return_value=None), \
                patch.object(embedded, 'merge_subtitles', return_value=[]):
            result = self.engine.streams('movie', 'unknown')
        self.assertEqual([first, second], result['streams'])
        self.assertIs(first, result['streams'][0])
        self.assertIs(second, result['streams'][1])

    def test_native_provider_selection_preserves_registry_objects(self):
        class Tools:
            def selectProviderIndicesJson(self, names, explicit, allowed):
                self.names, self.explicit, self.allowed = json.loads(names), explicit, json.loads(allowed)
                return '[1]'
        tools = Tools()
        gnula = self.engine.providers['gnula']
        cuevana = self.engine.providers['cuevana']
        self.engine.providers = {'gnula': gnula, 'cuevana': cuevana}
        with patch.object(embedded, '_native_engine_tools', tools), \
                patch.object(self.engine, '_scrape', return_value={'streams': [], 'subtitles': []}) as scrape:
            self.engine.streams('movie', 'unknown')
        self.assertEqual(['gnula', 'cuevana'], tools.names)
        self.assertEqual('unknown', tools.explicit)
        self.assertIs(cuevana, scrape.call_args.args[0])

    def test_engine_uses_native_cache_without_python_cache_module(self):
        class Cache:
            def __init__(self): self.entries = {}
            def getJson(self, key, stale): return self.entries.get(key)
            def putJson(self, key, value, ttl): self.entries[key] = value
            def clear(self): self.entries.clear()

        cache = Cache()
        with patch.object(embedded, "JsonCache", None):
            engine = embedded.Engine(self.temp.name, REGISTRY, self.base, native_cache=cache)
            try:
                engine.providers = {"pelisplushd": engine.providers["pelisplushd"]}
                module = catalogs.module_for("pelisplushd")
                with patch.object(module, "scrape", return_value=([{"url": "https://cdn.invalid/a.mp4"}], {})) as scrape:
                    first = engine.streams("movie", "pelisplushd:fixture")
                    second = engine.streams("movie", "pelisplushd:fixture")
                self.assertEqual(first, second)
                self.assertEqual(1, scrape.call_count)
                self.assertTrue(cache.entries)
                self.assertEqual({"ok": True}, engine.dispatch("clear_cache", {}))
                self.assertFalse(cache.entries)
            finally:
                engine.close()

    def test_engine_imports_when_python_cache_is_not_packaged(self):
        import builtins
        import runpy
        original = builtins.__import__
        def without_cache(name, *args, **kwargs):
            if name == "_embedded_cache":
                raise ModuleNotFoundError("not bundled", name="_embedded_cache")
            return original(name, *args, **kwargs)
        class Cache:
            def getJson(self, key, stale): return None
            def putJson(self, key, value, ttl): pass
            def clear(self): pass
        with patch("builtins.__import__", side_effect=without_cache):
            namespace = runpy.run_path(str(ROOT / "app/src/main/python/embedded_engine.py"))
        self.assertIsNone(namespace["JsonCache"])
        engine = namespace["Engine"](self.temp.name, REGISTRY, self.base, native_cache=Cache())
        try:
            self.assertEqual("embedded", engine.dispatch("health", {})["mode"])
        finally:
            engine.close()

    def test_native_http_adapter_keeps_urllib_response_and_errors(self):
        import io
        import runpy
        import urllib.response
        from email.message import Message
        import _htmlkit
        class Cache:
            def getJson(self, key, stale): return None
            def putJson(self, key, value, ttl): pass
            def clear(self): pass
        class Adapter:
            def open(self, request, timeout):
                if request.full_url.endswith("/failed"): return None
                code = 404 if request.full_url.endswith("/missing") else 200
                headers = Message()
                headers["Content-Type"] = "text/html; charset=utf-8"
                return urllib.response.addinfourl(io.BytesIO("Señal".encode()), headers, request.full_url, code)
        namespace = runpy.run_path(str(ROOT / "app/src/main/python/embedded_engine.py"))
        previous_tempdir = tempfile.tempdir
        self.addCleanup(setattr, tempfile, "tempdir", previous_tempdir)
        import builtins
        original = builtins.__import__
        def without_http(name, *args, **kwargs):
            if name == "_android_http":
                raise AssertionError("Python HTTP adapter must not be imported")
            return original(name, *args, **kwargs)
        with patch("builtins.__import__", side_effect=without_http):
            namespace["configure"](self.temp.name, json.dumps(REGISTRY), http_transport=object(), native_cache=Cache(), native_http_adapter=Adapter())
        engine = namespace["configure"].__globals__["_engine"]
        try:
            body, final_url, content_type = _htmlkit.http_get("https://fixture.invalid/ok", {}, 2)
            self.assertEqual(("Señal", "https://fixture.invalid/ok", "text/html; charset=utf-8"), (body, final_url, content_type))
            with self.assertRaises(urllib.error.HTTPError) as raised:
                _htmlkit.http_get("https://fixture.invalid/missing", {}, 2, retries=0)
            self.assertEqual(404, raised.exception.code)
            with self.assertRaises(urllib.error.URLError):
                _htmlkit.http_get("https://fixture.invalid/failed", {}, 2, retries=0)
        finally:
            engine.close()
            _htmlkit.install_url_opener(None)

    def test_native_nova_is_used_and_normalized(self):
        class NativeNova:
            def scrape(self, request):
                payload = json.loads(request)
                assert payload["id"] == "novahd:155"
                return json.dumps({"streams": [{"url": "https://cdn.invalid/play", "name": "Nova",
                    "behaviorHints": {"mediaType": "hls"}}], "meta": {"subtitles": [
                    {"url": "https://cdn.invalid/sub.vtt", "lang": "es"}]}})

        self.engine.native_nova = NativeNova()
        provider = self.engine.providers["novahd"]
        with patch.object(catalogs, "module_for", side_effect=AssertionError("Python NOVA called")):
            result = self.engine._scrape(provider, {"id": "novahd:155"})
        self.assertEqual("https://cdn.invalid/play", result["streams"][0]["url"])
        self.assertEqual("hls", result["streams"][0]["behaviorHints"]["mediaType"])
        self.assertEqual("es", result["subtitles"][0]["lang"])

    def test_native_nova_does_not_require_its_python_module(self):
        class NativeNova:
            def scrape(self, request):
                return json.dumps({"streams": [{"url": "https://cdn.invalid/video.mp4"}]})

        self.engine.native_nova = NativeNova()
        catalogs.install_native_nova(True)
        self.addCleanup(catalogs.install_native_nova, False)
        original = catalogs.module_for

        def no_nova(name):
            if name == "novahd":
                raise AssertionError("NOVA Python must not be imported")
            return original(name)

        with patch.object(catalogs, "module_for", side_effect=no_nova):
            self.assertIn("novahd", self.engine.dispatch("health", {})["providers"])
            self.assertEqual("https://novahd.cc", catalogs.site_for("novahd")["base"])
            self.assertEqual([], catalogs.episode_videos("novahd", "novahd:155", "155", "", "https://novahd.cc/m/155"))
            with patch.object(catalogs, "http_get", return_value=("<h1>Example</h1>", "https://novahd.cc/m/155", "text/html")):
                detail = catalogs.detail(self.engine.providers["novahd"], "movie", "novahd:155", {})
            self.assertEqual("Example", detail["name"])
            result = self.engine._scrape(self.engine.providers["novahd"], {"id": "novahd:155"})
            self.assertEqual("https://cdn.invalid/video.mp4", result["streams"][0]["url"])

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
