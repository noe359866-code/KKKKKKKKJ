# 🎬 Recolector de videos para vids.st

Web sencilla que **recolecta videos de cualquier pagina web y los almacena en [https://vids.st/](https://vids.st/)** usando la API oficial de vids.st (remote upload).

## Como funciona

1. Pegas la URL de una pagina que contenga un video (o un archivo de video directo `.mp4`, `.mkv`, `.webm`, etc.).
2. La web extrae la URL directa del video con [yt-dlp](https://github.com/yt-dlp/yt-dlp) **sin descargarlo en tu servidor**.
3. Llama a la API de vids.st (`https://vids.st/api/?key=...&action=remote_upload&url=...`) para que los servidores de vids.st descarguen el video, lo codifiquen en HLS y lo almacenen en tu cuenta.
4. Obtienes el enlace de vids.st, el enlace directo y el codigo de incrustacion.

## Requisitos

- Python 3.9+
- Una cuenta en [https://vids.st/](https://vids.st/) y su **API key** (se consigue en el panel de cuenta de vids.st).
- **Solo sube videos de los que tengas derechos legales** (ver aviso legal mas abajo).

## Instalacion y ejecucion

```bash
cd vids-st-collector

# crear entorno virtual e instalar dependencias
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# configurar la API key
cp .env.example .env
# edita .env y pon tu VIDS_ST_API_KEY

# ejecutar
./run.sh
# o: flask --app app run --host 0.0.0.0 --port 5000
```

Luego abre `http://localhost:5000` en el navegador.

## Variables de entorno (`.env`)

| Variable | Descripcion | Por defecto |
|---|---|---|
| `VIDS_ST_API_KEY` | Tu API key de vids.st (obligatoria) | — |
| `VIDS_ST_API_BASE` | Base de la API de vids.st | `https://vids.st/api/` |
| `VIDS_ST_ACTION` | Accion de la API usada para la subida remota | `remote_upload` |
| `PORT` | Puerto del servidor web | `5000` |
| `SECRET_KEY` | Clave secreta de Flask | `cambia-esta-clave-en-produccion` |

## API de vids.st

La integra con el endpoint descoberto:

```
GET https://vids.st/api/?key=<API_KEY>&action=remote_upload&url=<URL_DEL_VIDEO>
```

Respuestas esperadas:
- `401 Missing API key` — no se envio clave.
- `403 Invalid API key` — la clave no es valida.
- `200` con JSON — subida aceptada (el formato exacto del JSON depende de tu cuenta de vids.st; el codigo de video se lee de `filecode`, `id` o `video_id` segun la respuesta).

> Nota: no dispongo de una cuenta de vids.st para verificar el contrato exacto de respuesta. Si el campo del codigo de video cambia, ajustalo en `app.py` (busca `filecode`).

## Aviso legal / uso responsable

Esta herramienta es solo para fines **educativos y de uso personal**. Antes de usarla:

- Respeta los derechos de autor: solo recolecta y sube videos de los que seas el titular o tengas autorizacion expresa.
- Respeta los terminos de servicio de las paginas de origen y de vids.st (vids.st elimina cuentas y videos por violaciones de copyright, contenido ilegal, fraude o trafico falso).
- El almacenamiento y reproduccion publica de contenido ajeno puede ser ilegal en tu jurisdiccion. El usuario es el unico responsable del uso que le de a esta herramienta y del contenido que suba.

## Estructura del proyecto

```
vids-st-collector/
├── app.py            # servidor web (Flask) y rutas
├── collector.py      # extraccion de video + cliente API de vids.st
├── requirements.txt  # dependencias de Python
├── run.sh            # instalacion y arranque rapido
├── .env.example      # plantilla de variables de entorno
├── .gitignore
├── data/             # historial local de recolecciones (no se sube a git)
└── README.md
```

## Licencia

Este modulo se distribuye bajo la misma licencia Apache-2.0 del repositorio principal. VIDS.ST es una plataforma independiente; esta herramienta no esta afiliada con vids.st.
