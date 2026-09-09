// Generates the legacy build's icon from Red's classic stand sprite (the very art the legacy
// version renders), as both a 256x256 PNG and an .ico (manual PNG-payload ICO container - since
// Vista, Windows accepts PNG-compressed icons, so no extra encoder dependency is needed).
const fs = require('fs');
const path = require('path');
const Jimp = require('jimp');

function buildIco(png) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(1, 4);
  const entry = Buffer.alloc(16);
  entry.writeUInt8(0, 0);
  entry.writeUInt8(0, 1);
  entry.writeUInt8(0, 2);
  entry.writeUInt8(0, 3);
  entry.writeUInt16LE(1, 4);
  entry.writeUInt16LE(32, 6);
  entry.writeUInt32LE(png.length, 8);
  entry.writeUInt32LE(22, 12);
  return Buffer.concat([header, entry, png]);
}

async function main() {
  const src = path.join(__dirname, '..', 'renderer', 'sprites', 'Red', 'stand01.png');
  const outDir = path.join(__dirname, '..', 'build', 'icons');
  fs.mkdirSync(outDir, { recursive: true });

  const img = await Jimp.read(src);
  img.contain(256, 256);

  const outPng = path.join(outDir, 'legacy-icon.png');
  await img.write(outPng);

  const png = await img.getBufferAsync(Jimp.MIME_PNG);
  fs.writeFileSync(path.join(outDir, 'legacy-icon.ico'), buildIco(png));
  console.log('legacy icon written:', outPng, '(png', png.length, '/ ico', buildIco(png).length, 'bytes)');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});