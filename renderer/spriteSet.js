// Legacy sprite rendering, loaded in character.html before character.js (exposes
// window.SpriteSet). Mirrors Android's SpriteSet.kt: each character has a folder
// renderer/sprites/<id>/ with stand01, walk01..05, run01..12, fall01..02, pinch01..07,
// bounce01..04, trip01..06 PNGs, copied from the Android app's assets/sprites/. Missing frames
// are tolerated (skipped, same as Android), and kinds with no sprite art at all (sit/angry/
// sleep/tired/chew/drink/custom/climb) fall back to stand exactly like the Android sprite path.
// Characters without a sprite folder (custom/user-created ones) report not-ready and character.js
// falls back to rig rendering for them.

(function () {
  const FRAME_COUNTS = { stand: 1, walk: 5, run: 12, fall: 2, pinch: 7, bounce: 4, trip: 6 };

  class SpriteSet {
    constructor(characterId) {
      this.characterId = characterId;
      this.frames = {};
      this.ready = false;
      this.onReady = null;
    }

    load() {
      return fetch(`sprites/${this.characterId}/stand01.png`)
        .then((res) => {
          if (!res.ok) {
            this.ready = false;
            this.onReady && this.onReady(false);
            return;
          }
          return this._loadAll();
        })
        .catch(() => {
          this.ready = false;
          this.onReady && this.onReady(false);
        });
    }

    _loadAll() {
      return Promise.all(
        Object.keys(FRAME_COUNTS).map(async (kind) => {
          const imgs = [];
          for (let i = 1; i <= FRAME_COUNTS[kind]; i++) {
            const img = new Image();
            img.src = `sprites/${this.characterId}/${kind}${String(i).padStart(2, '0')}.png`;
            await new Promise((resolve) => {
              img.onload = resolve;
              img.onerror = resolve;
            });
            if (img.width > 0) imgs.push(img);
          }
          return [kind, imgs];
        })
      )
        .then((entries) => {
          this.frames = Object.fromEntries(entries);
          this.ready = true;
          this.onReady && this.onReady(true);
        })
        .catch(() => {
          this.ready = false;
          this.onReady && this.onReady(false);
        });
    }

    frameFor(kind, frame) {
      const set = this.frames[kind];
      if (set && set.length) return set[Math.abs(frame) % set.length];
      const stand = this.frames.stand;
      return (stand && stand[0]) || null;
    }
  }

  window.SpriteSet = {
    load(characterId) {
      const set = new SpriteSet(characterId);
      // Kick the async load immediately - without this the instance's `ready` stays false
      // forever and character.js would draw nothing (an invisible window, no rig fallback).
      set.load();
      return set;
    },
  };
})();