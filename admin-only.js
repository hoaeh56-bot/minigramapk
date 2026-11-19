#!/usr/bin/env node
/*
 * Только админ-панель MiniGram.
 *
 * Поднимает локальный сервер на вашем компьютере, который показывает ТОЛЬКО админку
 * и передаёт её запросы на ваш настоящий сервер. Мессенджер и остальные страницы
 * через него недоступны.
 *
 *   node tools/admin-only.js https://ваш-сервер.ru
 *   SERVER_URL=https://ваш-сервер.ru PORT=9000 node tools/admin-only.js
 *
 * Потом откройте http://127.0.0.1:9000 и войдите паролем админки.
 * Порт слушает только ваш компьютер (127.0.0.1). Чтобы открыть доступ по сети: HOST=0.0.0.0
 * (делайте это, только если понимаете риск: пароль админки пойдёт по обычному HTTP).
 * Нужен Node.js 18+, ничего устанавливать не надо.
 */
'use strict';
const http = require('http');
const https = require('https');

const raw = (process.argv[2] || process.env.SERVER_URL || '').trim();
if (!raw) {
  console.error('Укажите адрес сервера: node tools/admin-only.js https://ваш-сервер.ru');
  process.exit(1);
}
let target;
try { target = new URL(/^https?:\/\//i.test(raw) ? raw : 'https://' + raw); }
catch { console.error('Неверный адрес сервера: ' + raw); process.exit(1); }

const PORT = Number(process.env.PORT) || 9000;
const HOST = process.env.HOST || '127.0.0.1';
const lib = target.protocol === 'https:' ? https : http;

// что разрешено пропускать: сама страница админки, её API и картинки
const ALLOWED = [/^\/admin\/?$/, /^\/api\/admin\//, /^\/a\//, /^\/u\//, /^\/icon-(192|512)\.png$/, /^\/favicon/];

const server = http.createServer((req, res) => {
  const path = (req.url || '/').split('?')[0];
  if (path === '/') { res.writeHead(302, { Location: '/admin' }); return res.end(); }
  if (!ALLOWED.some(r => r.test(path))) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('Здесь доступна только админ-панель'); }

  const headers = Object.assign({}, req.headers, { host: target.host });
  delete headers.origin; delete headers.referer;
  const up = lib.request({
    protocol: target.protocol, hostname: target.hostname, port: target.port || (target.protocol === 'https:' ? 443 : 80),
    method: req.method, path: req.url, headers, timeout: 60000,
  }, r => {
    res.writeHead(r.statusCode || 502, r.headers);
    r.pipe(res);
  });
  up.on('timeout', () => up.destroy(new Error('timeout')));
  up.on('error', e => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Сервер ' + target.host + ' не отвечает: ' + e.message);
  });
  req.pipe(up);
});

server.listen(PORT, HOST, () => {
  console.log('Админ-панель: http://' + (HOST === '0.0.0.0' ? '127.0.0.1' : HOST) + ':' + PORT + '  ->  ' + target.origin);
});
