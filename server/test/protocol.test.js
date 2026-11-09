import test from 'node:test';
import assert from 'node:assert/strict';

import { buildManifest } from '../src/lib/stremio.js';
import { isHls, rewriteHlsPlaylist } from '../src/lib/http.js';
import { isPrivateHost, parseProxyUrl } from '../src/routes/proxy.js';

test('manifest declares catalog and metadata, but no playback resources', () => {
  const manifest = buildManifest('http://localhost:7000');
  assert.deepEqual([...manifest.types].sort(), ['anime', 'movie', 'series']);
  assert.deepEqual(manifest.resources.map(({ name }) => name), ['catalog', 'meta']);
  assert.ok(manifest.id && manifest.version && manifest.name);
});

test('HLS detection and playlist rewriting preserve protocol directives', () => {
  assert.equal(isHls('https://cdn.example/a/index.m3u8'), true);
  assert.equal(isHls('https://cdn.example/video.mp4'), false);

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

test('proxy rejects private targets (SSRF guard)', () => {
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
