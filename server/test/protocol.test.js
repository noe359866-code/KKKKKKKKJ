/**
 * test/protocol.test.js
 * Unit tests for the Stremio protocol helpers, id parsing, quality ranking
 * and the HLS/media URL utilities.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { buildManifest, buildStream, normalizeHeaders } from '../src/lib/stremio.js';
import { parseMediaId } from '../src/lib/ids.js';
import { normalizeQuality, qualityRank } from '../src/lib/quality.js';
import { extractMediaUrl, looksLikeMediaUrl, rewriteHlsPlaylist } from '../src/lib/http.js';
import { isPrivateHost, parseProxyUrl } from '../src/routes/proxy.js';

test('manifest declares the required content types', () => {
  const manifest = buildManifest('http://localhost:7000');
  assert.deepEqual([...manifest.types].sort(), ['anime', 'movie', 'series', 'tv']);
  assert.equal(manifest.resources[0].name, 'stream');
  assert.ok(manifest.id && manifest.version && manifest.name);
});

test('buildStream produces the required stream shape', () => {
  const stream = buildStream({
    name: 'Provider 1 · 1080p',
    title: 'Provider 1 · 1080p',
    url: 'https://cdn.example/live/index.m3u8',
    referer: 'https://provider.example/',
    userAgent: 'CustomUA/1.0',
    isLive: true,
  });

  assert.equal(stream.url, 'https://cdn.example/live/index.m3u8');
  assert.equal(stream.name, 'Provider 1 · 1080p');
  assert.equal(stream.title, 'Provider 1 · 1080p');
  assert.equal(stream.behaviorHints.requestHeaders.Referer, 'https://provider.example/');
  assert.equal(stream.behaviorHints.requestHeaders['User-Agent'], 'CustomUA/1.0');
  assert.equal(stream.behaviorHints.isLive, true);
});

test('buildStream rejects missing urls', () => {
  assert.equal(buildStream({ name: 'x', title: 'x' }), null);
  assert.equal(buildStream({ name: 'x', url: '' }), null);
});

test('header names are normalised to the casing ExoPlayer expects', () => {
  const headers = normalizeHeaders({ referer: 'a', 'user-agent': 'b', 'x-extra': 'c', empty: '' });
  assert.deepEqual(headers, { Referer: 'a', 'User-Agent': 'b', 'x-extra': 'c' });
});

test('parseMediaId handles movies, episodes and live ids', () => {
  const movie = parseMediaId('tt1254207');
  assert.equal(movie.imdbId, 'tt1254207');
  assert.equal(movie.isSeries, false);
  assert.equal(movie.isLive, false);

  const episode = parseMediaId('tt0944947:2:5');
  assert.equal(episode.base, 'tt0944947');
  assert.equal(episode.season, 2);
  assert.equal(episode.episode, 5);
  assert.equal(episode.isSeries, true);

  const channel = parseMediaId('tv:canal-13');
  assert.equal(channel.isLive, true);

  const sports = parseMediaId('sports:espn:1:2026-10-28');
  assert.equal(sports.isSports, true);
  assert.equal(sports.isLive, true);
});

test('quality normalisation and ranking', () => {
  assert.equal(normalizeQuality('FULL HD'), '1080p');
  assert.equal(normalizeQuality('1080'), '1080p');
  assert.equal(normalizeQuality('4k'), '4K');
  assert.equal(normalizeQuality(''), 'Unknown');
  assert.ok(qualityRank('4K') > qualityRank('1080p'));
  assert.ok(qualityRank('1080p') > qualityRank('720p'));
  assert.ok(qualityRank('720p') > qualityRank('480p'));
});

test('media URL detection and extraction', () => {
  assert.equal(looksLikeMediaUrl('https://cdn.example/a/index.m3u8'), true);
  assert.equal(looksLikeMediaUrl('https://cdn.example/watch?v=1&file=/hls/x.m3u8'), true);
  assert.equal(looksLikeMediaUrl('https://cdn.example/page'), false);
  assert.equal(looksLikeMediaUrl('javascript:alert(1)'), false);

  const html = '<script>player.setup({file: "https://cdn.example/hls/master.m3u8?token=1"});</script>';
  assert.equal(extractMediaUrl(html, 'https://provider.example/watch'), 'https://cdn.example/hls/master.m3u8?token=1');

  const relative = '<video><source src="/media/video.mp4"></video>';
  assert.equal(extractMediaUrl(relative, 'https://provider.example/watch'), 'https://provider.example/media/video.mp4');
});

test('HLS playlists are rewritten through the proxy', () => {
  const playlist = [
    '#EXTM3U',
    '#EXT-X-STREAM-INF:BANDWIDTH=800000',
    '720p/index.m3u8',
    '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"',
    'https://cdn.example/seg/1.ts',
    '',
  ].join('\n');

  const out = rewriteHlsPlaylist(playlist, 'https://cdn.example/hls/master.m3u8', 'http://me/proxy?url=');
  assert.match(out, /#EXTM3U/);
  assert.match(out, /#EXT-X-STREAM-INF:BANDWIDTH=800000/);
  assert.match(out, /http:\/\/me\/proxy\?url=https%3A%2F%2Fcdn\.example%2Fhls%2F720p%2Findex\.m3u8/);
  assert.match(out, /URI="http:\/\/me\/proxy\?url=https%3A%2F%2Fcdn\.example%2Fhls%2Fkey\.bin"/);
  assert.match(out, /http:\/\/me\/proxy\?url=https%3A%2F%2Fcdn\.example%2Fseg%2F1\.ts/);
});

test('proxy blocks private targets (SSRF guard)', () => {
  assert.equal(isPrivateHost('localhost'), true);
  assert.equal(isPrivateHost('127.0.0.1'), true);
  assert.equal(isPrivateHost('192.168.1.10'), true);
  assert.equal(isPrivateHost('10.0.0.5'), true);
  assert.equal(isPrivateHost('172.20.0.1'), true);
  assert.equal(isPrivateHost('169.254.169.254'), true);
  assert.equal(isPrivateHost('::1'), true);
  assert.equal(isPrivateHost('cdn.example.com'), false);

  assert.equal(parseProxyUrl('http://127.0.0.1/secret'), null);
  assert.equal(parseProxyUrl('file:///etc/passwd'), null);
  assert.equal(parseProxyUrl('https://cdn.example/a.m3u8').hostname, 'cdn.example');
});
