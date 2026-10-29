"""urllib-compatible adapter to Android's bounded OkHttp transport.

The transport is an object passed by Kotlin, not a Java class imported by name.
Desktop engine tests and the Node backend keep using standard urllib.
"""
import base64
from email.message import Message
import io
import json
import urllib.error


class Response(io.BytesIO):
    def __init__(self, body: bytes, url: str, headers: Message, status: int):
        super().__init__(body)
        self.url = url
        self.headers = headers
        self.status = self.code = status

    def geturl(self):
        return self.url

    def getcode(self):
        return self.status


class AndroidUrlOpener:
    def __init__(self, transport):
        self.transport = transport

    def __call__(self, request, timeout=8):
        data = request.data
        encoded = base64.b64encode(data).decode("ascii") if data is not None else None
        headers = dict(request.header_items())
        if data is not None and not any(name.lower() == "content-type" for name in headers):
            # This is the default normally supplied by urllib's HTTPHandler.
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        try:
            raw = self.transport.request(
                request.full_url, request.get_method(), json.dumps(headers),
                max(100, min(8000, int(timeout * 1000))), encoded,
            )
            result = json.loads(str(raw))
        except Exception as error:
            # Existing scrapers catch URLError/OSError; do not expose JNI exceptions to them.
            raise urllib.error.URLError("Android HTTP request failed") from error
        headers = Message()
        for name, value in result["headers"].items():
            headers[name] = str(value)
        body = base64.b64decode(result["body"])
        response = Response(body, result["url"], headers, result["status"])
        if result["status"] >= 400:
            raise urllib.error.HTTPError(result["url"], result["status"], "HTTP error", headers, response)
        return response
