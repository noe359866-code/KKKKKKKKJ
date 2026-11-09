"""Tests for the registry actually packaged into the TV client."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "app/src/main/python"))
import embedded_engine


class ClientRegistryTests(unittest.TestCase):
    def test_client_starts_without_bundled_providers(self):
        registry = json.loads((ROOT / "server/config/providers.json").read_text())
        self.assertEqual([], registry["providers"])
        with tempfile.TemporaryDirectory() as cache:
            engine = embedded_engine.Engine(cache, registry)
            try:
                self.assertEqual({}, engine.providers)
                self.assertEqual([], engine.catalogs(False)["catalogs"])
                self.assertTrue(engine.catalogs(True)["catalogs"])
                self.assertEqual([], engine.streams("movie", "tt1234567")["streams"])
            finally:
                engine.close()
