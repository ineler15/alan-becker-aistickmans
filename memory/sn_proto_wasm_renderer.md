---
name: sn-proto-wasm-renderer
description: Separate scratch prototype (outside the git repo) that renders real Stick Nodes .nodes rig files via a Rust->WASM parser
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T17:56:49.958Z
---

`.nodes` here means the actual **Stick Nodes** (the animation app) rig file format — not AI models. There's a standalone prototype at `D:\JMATEO\stickman-scratch\sn-proto\` (own folder, NOT part of the `proyecto stickman` git repo, no `.git` of its own) that parses `.nodes` files and renders the stick figure on a canvas.

- `pkg/sn_bridge.js` + `sn_bridge_bg.wasm`: a Rust crate (`sn_bridge`, presumably wrapping/mirroring `sticknodes-rs`) compiled to WASM, exposing `parse_nodes_file(bytes) -> json` which returns the parsed node tree (bones with `node_type`, `local_angle`, `length`, `thickness`, `color`, `children`, etc.).
- `index.html`: loads the WASM module, walks the tree (`layout()` mirrors `Node::get_global_angle`/`get_local_position` from sticknodes-rs) and draws it on a `<canvas>` with 2D context. Has buttons to load the 11 bundled AIStickmans character rigs (`Red SF.nodes`, `Blue SF.nodes`, ... `Victim SF.nodes`) plus a file-input for arbitrary `.nodes` files.
- Served via a local static server on `http://localhost:8934` (see `server.log` in that folder for request history).

**Why:** exploring whether real Stick Nodes rig data can be parsed/rendered directly (via WASM) instead of relying on pre-rendered sprite frames — a possible future rendering path for the stickman characters, decoupled from the current Shimeji/sprite-based engine in `java-engine/`.

**How to apply:** On 2026-08-16, fixed two rendering bugs found by visually testing this prototype in-browser:
1. Circle/FilledCircle head nodes were drawn with `radius = node.length * scale` while the bounding-box math assumed `radius = node.length/2` — heads rendered ~2x too big. Fixed to `node.length * 0.65 * scale` (a bit more than half looked right after live iteration).
2. The circle was centered at the segment's `start` (bbox math assumed `end`), causing the head to sink into the neck/torso. Fixed to center the circle at `start + radius * unit_direction`, so the bottom edge touches the neck joint instead of overlapping or floating with a gap.
Both were confirmed by rendering the "Red" character and getting live user feedback (too big → too small → floating gap → correct) before landing on the current values.

3. "The Chosen One", "The Dark Lord", "The Second Coming", and "Victim" have no `Circle` head node at all — their head is a closed ring of ~8-9 plain `RoundedSegment`/`Segment` bones, each with a nonzero `segment_curve_radius_and_default_curve_radius`. Drawing each as a straight line rendered a visible octagon; the user confirmed (checked the file in the real Stick Nodes app) it's genuinely circular there, so this was a renderer bug, not the source data being wrong. First attempt bowed each segment into its own quadratic arc — looked worse (a "flower of petals") since each segment's round line-cap still bulged at its joint on top of the added curve. Fix: detect a run of consecutive curved-radius segments (chained via object-identity - `child.start === parent.end`) and stroke the WHOLE run as ONE continuous path, smoothed through the joint points via the standard "quadratic curve through consecutive midpoints" trick, instead of one stroke per segment. That reads as a clean circle with no visible joints.
4. Same characters also showed a stray straight line cutting across the middle of the head - a real bone (a long ~190-unit-length structural "spoke" connecting the neck to the ring's start point) with `thickness: 0`. `Math.max(1, node.thickness * scale)` was clamping that to a visible 1px hairline instead of treating `thickness <= 0` as "don't stroke this at all" (Stick Nodes' actual convention - Red has an equivalent zero-thickness connector too, just short enough at ~13 units to not be noticeable). Fixed by skipping the stroke entirely when `node.thickness <= 0`.
Both of these are only implemented in the JS prototype (`index.html`) so far, not yet ported to the Android Kotlin renderer (see [[android_rig_renderer]]) since Red - the only character wired up on Android - has `curveRadius` 0 everywhere and its zero-thickness connector is too short to be visible; port both fixes when a second character with a segment-built head gets added there.

Known remaining issue (not yet fixed, user hasn't asked for it): "The Dark Lord Sword SF.nodes" (157 nodes, the most complex rig) uses `Triangle` node types (a real polygon shape, e.g. for a blade) that the renderer doesn't handle at all - it falls through to the generic line-segment draw, producing a jagged/broken mess instead of a recognizable shape. Needs actual polygon rendering support, not just the line/circle cases handled today.
