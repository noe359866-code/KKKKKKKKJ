"""Puente embebido para los scrapers de Streamflix.

Chaquopy empaqueta `server/scrapers` como módulos Python de nivel superior.
Este módulo expone una única función síncrona que Kotlin llama desde un pool de
hilos: recibe el nombre del script y un payload JSON, y devuelve JSON con
`ok`, `streams` y `meta`. Nunca escribe en stdout.
"""

from __future__ import annotations

import importlib
import json
import os
import traceback
from typing import Any, Dict

# Los scrapers usan urllib/https. En Android el almacén de certificados del
# sistema no siempre es visible para Python; certifi viaja en la APK.
try:
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())
except Exception:  # noqa: BLE001 - si falta, se intenta con el sistema
    pass


def _normalize_result(result: Any) -> Dict[str, Any]:
    if isinstance(result, tuple) and len(result) == 2:
        streams, meta = result
    elif isinstance(result, dict):
        streams = result.get("streams", [])
        meta = result.get("meta", {})
    elif isinstance(result, list):
        streams, meta = result, {}
    else:
        streams, meta = [], {}
    if not isinstance(streams, list):
        streams = []
    if not isinstance(meta, dict):
        meta = {}
    return {"ok": True, "streams": streams, "meta": meta}


def run(script: str, payload_json: str) -> str:
    """Ejecuta `scrape(payload)` del scraper pedido y devuelve JSON."""
    try:
        payload = json.loads(payload_json or "{}")
        if not isinstance(payload, dict):
            payload = {}
        module_name = str(script or "").removesuffix(".py")
        if not module_name or "/" in module_name or "\\" in module_name:
            raise ValueError("nombre de scraper inválido")
        module = importlib.import_module(module_name)
        scrape = getattr(module, "scrape")
        return json.dumps(_normalize_result(scrape(payload)), ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001 - un proveedor no debe tumbar la app
        return json.dumps(
            {
                "ok": False,
                "streams": [],
                "meta": {"error": str(exc), "traceback": traceback.format_exc(limit=6)},
            },
            ensure_ascii=False,
        )
