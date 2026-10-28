# ---------------------------------------------------------------------------
# Production image: Node for the addon + Python for the scrapers.
# Build from the ./server directory:  docker build -t streamflix-addon .
# ---------------------------------------------------------------------------
FROM node:22-alpine

# python3 is required by the scraper bridge; tini gives us correct signal
# handling so `docker stop` reaches the Node graceful-shutdown path.
RUN apk add --no-cache python3 tini \
    && ln -sf python3 /usr/bin/python

WORKDIR /app

# Install dependencies first to leverage the Docker layer cache.
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund || npm install --omit=dev --no-audit --no-fund

COPY src ./src
COPY scrapers ./scrapers
COPY public ./public
COPY config ./config
COPY scripts ./scripts

ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=7000 \
    SCRAPER_PYTHON=python3 \
    SCRAPER_DIR=/app/scrapers

# Run as the unprivileged user shipped with the official Node image.
USER node

EXPOSE 7000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||7000)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "src/server.js"]
