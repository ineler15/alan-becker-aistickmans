const { ipcRenderer } = require('electron');

const video = document.getElementById('video');
const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');

// Laptops often expose an IR/"Smart Connect" camera for Windows Hello face login
// alongside the real webcam - grabbing whatever device is "default" can land on that IR
// one, which just streams a blank/branding frame instead of an actual photo. Prefer a
// device whose label doesn't look like an IR/Hello camera.
async function pickDeviceId() {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    const cameras = devices.filter((d) => d.kind === 'videoinput');
    const real = cameras.find((d) => !/ir\b|infrared|hello|smart connect/i.test(d.label));
    return (real || cameras[0] || {}).deviceId;
  } catch {
    return undefined;
  }
}

async function start() {
  try {
    const deviceId = await pickDeviceId();
    const constraints = { audio: false, video: deviceId ? { deviceId: { exact: deviceId } } : true };
    const stream = await navigator.mediaDevices.getUserMedia(constraints);
    video.srcObject = stream;
  } catch (err) {
    ipcRenderer.send('webcam:error', String(err && err.message ? err.message : err));
    return;
  }

  // Push a frame every 2s - the main process just keeps the latest one, no need for a
  // request/response round trip every tick.
  setInterval(() => {
    if (video.readyState < 2) return;
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const dataUrl = canvas.toDataURL('image/jpeg', 0.7);
    ipcRenderer.send('webcam:frame', dataUrl.replace(/^data:image\/jpeg;base64,/, ''));
  }, 2000);
}

start();
