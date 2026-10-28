#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -d .venv ]; then
  python3 -m venv .venv
fi

# Instala dependencias (descomenta si hace falta actualizarlas)
# .venv/bin/pip install --upgrade pip
.venv/bin/pip install -r requirements.txt

if [ ! -f .env ]; then
  echo "=> Copiando .env.example a .env. Editalo con tu API key de vids.st antes de seguir."
  cp .env.example .env
fi

export $(grep -v '^#' .env | xargs)
.venv/bin/flask --app app run --host 0.0.0.0 --port "${PORT:-5000}"
