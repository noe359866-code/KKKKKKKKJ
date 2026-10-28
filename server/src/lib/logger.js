/**
 * lib/logger.js
 * ---------------------------------------------------------------------------
 * Tiny dependency-free logger. Verbose by default in development, structured
 * JSON in production so it can be piped to Loki/CloudWatch/Datadog.
 */

import config from '../config.js';

const LEVELS = { error: 0, warn: 1, info: 2, debug: 3 };
const COLORS = { error: '\x1b[31m', warn: '\x1b[33m', info: '\x1b[36m', debug: '\x1b[90m' };
const RESET = '\x1b[0m';

const currentLevel = LEVELS[config.logging.level] ?? LEVELS.info;

function serialize(level, scope, message, meta) {
  const entry = {
    ts: new Date().toISOString(),
    level,
    scope,
    msg: message,
    ...(meta && Object.keys(meta).length > 0 ? { meta } : {}),
  };

  if (config.logging.json) return JSON.stringify(entry);

  const color = COLORS[level] ?? '';
  const time = entry.ts.slice(11, 23);
  const suffix = meta && Object.keys(meta).length > 0 ? ` ${JSON.stringify(meta)}` : '';
  return `${color}${time} ${level.toUpperCase().padEnd(5)}${RESET} [${scope}] ${message}${suffix}`;
}

/**
 * Create a namespaced logger.
 * @param {string} scope - e.g. "http", "cache", "bridge"
 */
export function createLogger(scope) {
  const write = (level, message, meta) => {
    if (LEVELS[level] > currentLevel) return;
    const line = serialize(level, scope, message, meta);
    if (level === 'error' || level === 'warn') console.error(line);
    else console.log(line);
  };

  return {
    error: (message, meta) => write('error', message, meta),
    warn: (message, meta) => write('warn', message, meta),
    info: (message, meta) => write('info', message, meta),
    debug: (message, meta) => write('debug', message, meta),
    /** Duration helper: `const done = log.timer(); ... done('label')` */
    timer() {
      const start = process.hrtime.bigint();
      return (message, meta) => {
        const ms = Number(process.hrtime.bigint() - start) / 1e6;
        write('debug', message, { ...meta, durationMs: Number(ms.toFixed(2)) });
        return ms;
      };
    },
  };
}

export default createLogger;
