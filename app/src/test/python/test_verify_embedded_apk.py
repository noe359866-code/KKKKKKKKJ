"""Tests for the Android APK verifier without building Android code."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location("verify_embedded_apk", ROOT / "scripts/verify-embedded-apk.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ApkVerifierTests(unittest.TestCase):
    def make_apk(self, *, provider_config=None, bundled_provider=None, missing_helper=None, missing_engine=None):
        app = io.BytesIO()
        with zipfile.ZipFile(app, "w") as modules:
            for name in verifier.HELPERS:
                if name != missing_helper:
                    modules.writestr(name + ".py", "")
            if bundled_provider:
                modules.writestr(bundled_provider + ".py", "")
        temp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        path = Path(temp.name)
        temp.close()
        self.addCleanup(path.unlink)
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("assets/chaquopy/app.imy", app.getvalue())
            apk.writestr("assets/providers.json", json.dumps({"providers": provider_config or []}))
            dex = b"".join(verifier.NATIVE_HELPERS.values()) + b"".join(
                value for key, value in verifier.NATIVE_ENGINE_CLASSES.items() if key != missing_engine
            )
            apk.writestr("classes.dex", dex)
            for abi in verifier.ABIS:
                for library in ("libpython3.11.so", "libchaquopy_java.so"):
                    apk.writestr(f"lib/{abi}/{library}", b"")
        return path

    def test_accepts_client_apk_without_bundled_providers(self):
        result = verifier.verify(self.make_apk())
        self.assertEqual([], result["embeddedProviders"])
        self.assertEqual("Stremio addons", result["source"])

    def test_rejects_provider_registry_entries(self):
        with self.assertRaisesRegex(ValueError, "registry must be empty"):
            verifier.verify(self.make_apk(provider_config=[{"id": "animeflv", "enabled": True}]))

    def test_rejects_legacy_scraper_module(self):
        with self.assertRaisesRegex(ValueError, "Website scraper modules"):
            verifier.verify(self.make_apk(bundled_provider="animeflv"))

    def test_requires_catalog_helpers_and_native_runtime(self):
        with self.assertRaisesRegex(ValueError, "modules missing"):
            verifier.verify(self.make_apk(missing_helper="embedded_engine"))
        with self.assertRaisesRegex(ValueError, "Kotlin engine component missing"):
            verifier.verify(self.make_apk(missing_engine="engine tools"))


if __name__ == "__main__":
    unittest.main()
