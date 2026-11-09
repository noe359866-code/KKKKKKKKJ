/**
 * Stremio Addon protocol helpers for the catalog/metadata-only backend.
 *
 * This server does not discover or resolve playback sources. Reproduction is
 * delegated to compatible Stremio addons selected in the Streamflix client.
 */

import config from '../config.js';
import { manifestCatalogs } from '../catalogs/registry.js';

/** Builds the catalog-only addon manifest. */
export function buildManifest(baseUrl = '') {
  const types = config.addon.types;
  return {
    id: config.addon.id,
    version: config.addon.version,
    name: config.addon.name,
    description: config.addon.description,
    ...(config.addon.logo ? { logo: config.addon.logo } : {}),
    types,
    resources: [
      { name: 'catalog', types },
      { name: 'meta', types },
    ],
    catalogs: manifestCatalogs(),
    ...(baseUrl ? { url: baseUrl } : {}),
  };
}

/** Validates the "type" path param. */
export function isSupportedType(type) {
  return config.addon.types.includes(String(type).toLowerCase());
}

export default { buildManifest, isSupportedType };
