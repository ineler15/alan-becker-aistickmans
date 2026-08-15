const { exec, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

function openApp(target) {
  return new Promise((resolve, reject) => {
    const child = spawn('cmd.exe', ['/c', 'start', '""', target], { shell: false, detached: true });
    child.on('error', reject);
    setTimeout(resolve, 300);
  });
}

function closeApp(processName) {
  return new Promise((resolve, reject) => {
    exec(`taskkill /IM "${processName}" /F`, (err, stdout, stderr) => {
      if (err) return reject(stderr || err.message);
      resolve(stdout);
    });
  });
}

function listDir(dirPath) {
  return fs.promises.readdir(dirPath, { withFileTypes: true }).then((entries) =>
    entries.map((e) => ({ name: e.name, isDir: e.isDirectory() }))
  );
}

function readFile(filePath) {
  return fs.promises.readFile(filePath, 'utf8');
}

async function writeFile(filePath, content) {
  await fs.promises.mkdir(path.dirname(filePath), { recursive: true });
  await fs.promises.writeFile(filePath, content, 'utf8');
}

function deleteFile(filePath) {
  return fs.promises.unlink(filePath);
}

function runCommand(command) {
  return new Promise((resolve, reject) => {
    exec(command, { timeout: 20000 }, (err, stdout, stderr) => {
      if (err) return reject(stderr || err.message);
      resolve(stdout);
    });
  });
}

module.exports = { openApp, closeApp, listDir, readFile, writeFile, deleteFile, runCommand };
