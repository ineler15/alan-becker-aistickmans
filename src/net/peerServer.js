const http = require('http');
const fs = require('fs');
const path = require('path');
const { screen } = require('electron');
const shimeji = require('../jsEngine/jsShimejiController');
const history = require('../memory/history');
const config = require('../config');
const CHARACTERS = require('../characters');
const health = require('../loop/health');

// Lets the Android port, this desktop app and a browser "home" page all see (and render) each
// other's characters - same lightweight visibility (position + last thing said) that characters
// on one device already have of each other, just carried over the LAN. Personality/memory stay
// separate per device on purpose - this is not a shared brain.
const PORT = config.peerPort;
const REMOTE_STALE_MS = 30000;
const REMOTE_PEERS_FILE = path.join(config.workspaceDir, 'remote-peers.json');
const IMG_DIR = path.join(path.dirname(config.shimeji.jarPath), 'img');

let remotePeers = [];
let remoteReceivedAt = 0;
let remoteScreenWidth = 0;

function localPeersPayload() {
  return CHARACTERS.filter((c) => health.isOk(c.id)).map((c) => {
    const status = shimeji.readStatus(c.id);
    const lastAction = history.recent(c.id, 1)[0] || null;
    return {
      id: c.id,
      displayName: c.displayName,
      device: 'pc',
      x: status ? status.x : null,
      y: status ? status.y : null,
      lastSay: lastAction && lastAction.tool === 'say' ? lastAction.args?.text : null,
    };
  });
}

// Text-context version for agentLoop.js's "peers" merge - just the array, already staleness-checked.
function getRemotePeers() {
  if (Date.now() - remoteReceivedAt > REMOTE_STALE_MS) return [];
  return remotePeers;
}

function freshRemotePeersTagged() {
  return getRemotePeers().map((p) => ({ ...p, device: 'tablet', screenWidth: remoteScreenWidth }));
}

// Writes what the Android port reported to a plain file so the Java engine (which can't reach
// into this Node process directly) can render ghost sprites for it - same idea as the existing
// ai-command/ai-status file bridge between this process and the Java side.
function writeRemotePeersFile(screenWidth, peers) {
  const payload = { screenWidth, peers, receivedAt: Date.now() };
  try {
    fs.writeFileSync(REMOTE_PEERS_FILE, JSON.stringify(payload), 'utf8');
  } catch (e) {
    console.warn('[peerServer] no se pudo escribir remote-peers.json:', e.message);
  }
}

// A safe-ish static sprite file server for the web "home" page - id/file are restricted to a
// plain filename via path.basename so a crafted URL can't escape IMG_DIR.
function serveSprite(req, res, id, file) {
  const safeId = path.basename(id);
  const safeFile = path.basename(file);
  const filePath = path.join(IMG_DIR, safeId, safeFile);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=3600' });
    res.end(data);
  });
}

function start() {
  const server = http.createServer((req, res) => {
    const url = req.url.split('?')[0];

    if (req.method === 'GET' && url === '/peers') {
      const width = screen.getPrimaryDisplay().size.width;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ screenWidth: width, peers: localPeersPayload() }));
      return;
    }

    if (req.method === 'GET' && url === '/peers/all') {
      const pcScreenWidth = screen.getPrimaryDisplay().size.width;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          pcScreenWidth,
          peers: [
            ...localPeersPayload().map((p) => ({ ...p, screenWidth: pcScreenWidth })),
            ...freshRemotePeersTagged(),
          ],
        })
      );
      return;
    }

    if (req.method === 'POST' && url === '/peers') {
      let body = '';
      req.on('data', (chunk) => {
        body += chunk;
      });
      req.on('end', () => {
        try {
          const parsed = JSON.parse(body);
          remotePeers = Array.isArray(parsed.peers) ? parsed.peers : [];
          remoteReceivedAt = Date.now();
          remoteScreenWidth = parsed.screenWidth || 0;
          writeRemotePeersFile(remoteScreenWidth, remotePeers);
        } catch (e) {
          // ignore malformed payloads
        }
        res.writeHead(204);
        res.end();
      });
      return;
    }

    const spriteMatch = url.match(/^\/sprites\/([^/]+)\/([^/]+)$/);
    if (req.method === 'GET' && spriteMatch) {
      serveSprite(req, res, spriteMatch[1], spriteMatch[2]);
      return;
    }

    if (req.method === 'GET' && (url === '/' || url === '/home')) {
      fs.readFile(path.join(__dirname, 'home.html'), (err, data) => {
        if (err) {
          res.writeHead(500);
          res.end();
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(data);
      });
      return;
    }

    res.writeHead(404);
    res.end();
  });
  server.on('error', (err) => {
    console.warn('[peerServer] no se pudo iniciar:', err.message);
  });
  server.listen(PORT, '0.0.0.0');

  // Lado cliente: cada instancia de escritorio tambien le habla al peerServer de la OTRA
  // instancia (la legacy en 8788 y la moderna en 8787) para que los stickmen de ambas apps se
  // vean entre si como si compartieran pantalla. Simetrico: ambas son servidor y cliente a la
  // vez. Solo tiene sentido cuando los puertos difieren (no hay nada que sincronizar contra uno
  // mismo).
  const remotePort = config.peerRemotePort;
  if (remotePort !== config.peerPort) {
    let announced = false;
    const syncWithRemote = () => {
      try {
        const width = screen.getPrimaryDisplay().size.width;
        // (a) Posteamos nuestros peers a la otra instancia por su misma ruta /peers - pasa por
        // el handler POST normal (guarda remote-peers.json del otro lado, igual que Android).
        fetch('http://127.0.0.1:' + remotePort + '/peers', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ screenWidth: width, peers: localPeersPayload() }),
        }).catch(() => {});
        // (b) Traemos los peers de la otra instancia. Al guardarlos en remotePeers se
        // normaliza cualquier device:'pc' a undefined (se omiten) para que al AI le lean como
        // stickmen del mismo escritorio, caminables, no como tablets remotas; los 'tablet' se
        // respetan tal cual. Esta data NO se escribe a remote-peers.json (ese archivo solo lo
        // escribe el lado que RECIBE un POST, o sea cuando un dispositivo nos reporta).
        fetch('http://127.0.0.1:' + remotePort + '/peers')
          .then((r) => r.json())
          .then((data) => {
            if (data && Array.isArray(data.peers)) {
              remotePeers = data.peers.map((p) =>
                p && p.device === 'pc' ? { ...p, device: undefined } : p
              );
              remoteReceivedAt = Date.now();
              remoteScreenWidth = data.screenWidth || 0;
              if (!announced) {
                announced = true;
                console.log('[peerServer] sincronizando con instancia en', remotePort);
              }
            }
          })
          .catch(() => {});
      } catch (e) {
        // La otra instancia puede no estar corriendo: un fetch fallido nunca debe crashear ni
        // hacer spam en los logs.
      }
    };
    // Dispara la primera sincronizacion de inmediato y luego cada 4000 ms.
    syncWithRemote();
    setInterval(syncWithRemote, 4000);
  }
}

module.exports = { start, getRemotePeers };
