// Holds the latest webcam frame (base64 jpeg, no data-uri prefix) pushed from the hidden
// webcam renderer window every ~2s. null until the first frame arrives or if the camera
// failed/was denied.
let latestFrame = null;

function set(frameBase64) {
  latestFrame = frameBase64;
}

function get() {
  return latestFrame;
}

module.exports = { set, get };
