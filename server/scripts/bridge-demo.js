#!/usr/bin/env node
/**
 * scripts/bridge-demo.js
 * ---------------------------------------------------------------------------
 * Runs one Python scraper through the Node bridge, without HTTP/cache.
 * The fastest way to debug a new scraper.
 *
 * Usage:
 *   node scripts/bridge-demo.js [script.py] [json-payload]
 *
 * Examples:
 *   node scripts/bridge-demo.js example_provider.py '{"type":"tv","id":"demo-live","isLive":true,"provider":"demo-live"}'
 *   node scripts/bridge-demo.js example_provider.py '{"type":"movie","id":"tt0133093","provider":"demo-vod"}'
 */

import { runScraper } from '../src/bridge/pythonBridge.js';
import { loadProviders } from '../src/providers/index.js';

const script = process.argv[2] || 'example_provider.py';
const rawPayload = process.argv[3] || '{"type":"tv","id":"demo-live","isLive":true,"provider":"demo-live"}';

let payload;
try {
  payload = JSON.parse(rawPayload);
} catch (error) {
  console.error('Invalid JSON payload:', error.message);
  process.exit(1);
}

await loadProviders();

const started = process.hrtime.bigint();
const result = await runScraper(script, payload);
const ms = Number(process.hrtime.bigint() - started) / 1e6;

console.log(`\nscript : ${script}`);
console.log(`payload: ${JSON.stringify(payload)}`);
console.log(`ok     : ${result.ok}`);
console.log(`took   : ${ms.toFixed(1)} ms`);
console.log(`streams: ${result.streams.length}`);
console.log(JSON.stringify(result, null, 2));

process.exit(result.ok ? 0 : 1);
