#!/usr/bin/env node
/**
 * scripts/cache-stats.js
 * ---------------------------------------------------------------------------
 * Prints cache metrics from a running addon (`npm run cache:stats`).
 * Since the cache lives in memory, we query the /health endpoint instead of
 * creating a second, empty cache in this process.
 *
 * Usage: PORT=7000 node scripts/cache-stats.js [--watch]
 */

const port = process.env.PORT || 7000;
const baseUrl = process.env.BASE_URL || `http://127.0.0.1:${port}`;
const watch = process.argv.includes('--watch');

function bar(value, max = 30) {
  const filled = Math.round(Math.min(1, value) * max);
  return '█'.repeat(filled) + '░'.repeat(max - filled);
}

async function printOnce() {
  try {
    const res = await fetch(`${baseUrl}/health`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const health = await res.json();
    const { cache } = health;

    const lines = [
      '',
      `  ${health.service} v${health.version}  (${health.env})`,
      `  uptime: ${health.uptimeSeconds}s   memory: ${health.memory.rssMb} MB   node: ${health.node}`,
      '',
      `  cache entries : ${cache.size} / ${cache.maxEntries} (fresh ${cache.fresh}, stale ${cache.stale})`,
      `  hit rate      : ${(cache.hitRate * 100).toFixed(1)}%  ${bar(cache.hitRate)}`,
      `  hits ${cache.hits} · stale ${cache.staleHits} · misses ${cache.misses} · coalesced ${cache.coalesced}`,
      `  evictions ${cache.evictions} · expired ${cache.expired} · errors ${cache.errors}`,
      `  in flight     : ${cache.inflight}`,
      `  TTL live/VOD  : ${health.addon.liveTtlHours}h / ${health.addon.vodTtlHours}h`,
      `  providers     : ${health.providers.enabled.map((p) => `${p.id}(${p.priority})`).join(', ') || 'none'}`,
      '',
    ];
    console.log(lines.join('\n'));
  } catch (error) {
    console.error(`  cannot reach ${baseUrl}/health: ${error.message}`);
    process.exitCode = 1;
  }
}

await printOnce();
if (watch) {
  setInterval(printOnce, 3000);
}
