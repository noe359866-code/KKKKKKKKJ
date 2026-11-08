"""Tests del verificador sin compilar Android: APK sintético, sin código ejecutable."""
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
    def make_apk(self, *, native=True, python_nova=False, cache=True, python_cache=False, http=True, python_http=False, engine=True):
        app = io.BytesIO()
        with zipfile.ZipFile(app, "w") as modules:
            for name in verifier.PYTHON_PROVIDERS | verifier.HELPERS:
                modules.writestr(name + ".py", "")
            if python_nova:
                modules.writestr("novahd.py", "")
            if python_cache:
                modules.writestr("_embedded_cache.py", "")
            if python_http:
                modules.writestr("_android_http.py", "")
        temp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        path = Path(temp.name)
        temp.close()
        self.addCleanup(path.unlink)
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("assets/chaquopy/app.imy", app.getvalue())
            apk.writestr("assets/providers.json", json.dumps({"providers": [{"id": p, "enabled": True} for p in verifier.PROVIDERS]}))
            dex = verifier.NATIVE_PROVIDERS["novahd"] if native else b"other"
            if cache:
                dex += verifier.NATIVE_HELPERS["_embedded_cache"]
            if http:
                dex += verifier.NATIVE_HELPERS["_android_http"]
            if engine:
                dex += b"".join(verifier.NATIVE_ENGINE_CLASSES.values())
            apk.writestr("classes.dex", dex)
            for abi in verifier.ABIS:
                for library in ("libpython3.11.so", "libchaquopy_java.so"):
                    apk.writestr(f"lib/{abi}/{library}", b"")
        return path

    def test_accepts_native_nova_without_python_copy(self):
        self.assertIn("novahd", verifier.verify(self.make_apk())["embeddedProviders"])

    def test_requires_native_class(self):
        with self.assertRaisesRegex(ValueError, "Kotlin provider missing"):
            verifier.verify(self.make_apk(native=False))

    def test_requires_kotlin_engine_components(self):
        with self.assertRaisesRegex(ValueError, "Kotlin engine component missing"):
            verifier.verify(self.make_apk(engine=False))

    def test_rejects_duplicate_python_scraper(self):
        with self.assertRaisesRegex(ValueError, "Native providers"):
            verifier.verify(self.make_apk(python_nova=True))

    def test_requires_native_cache_and_rejects_duplicate_python_copy(self):
        with self.assertRaisesRegex(ValueError, "Kotlin helper missing"):
            verifier.verify(self.make_apk(cache=False))
        with self.assertRaisesRegex(ValueError, "Native helpers"):
            verifier.verify(self.make_apk(python_cache=True))
        with self.assertRaisesRegex(ValueError, "Kotlin helper missing"):
            verifier.verify(self.make_apk(http=False))
        with self.assertRaisesRegex(ValueError, "Native helpers"):
            verifier.verify(self.make_apk(python_http=True))
