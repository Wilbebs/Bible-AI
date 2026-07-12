---
name: Fixing uploaded images with checkerboard baked into pixels
description: How to tell a "transparent-looking" PNG upload is actually flattened onto a checkerboard, and how to recover real alpha when the AI background-removal service is unavailable.
---

Some uploaded PNGs *look* transparent (checkerboard pattern) but were exported/flattened by
some tool so the checkerboard is literally baked into RGB pixel data, with no alpha channel at
all. Always verify before trusting the visual: `identify -verbose <file> | grep -i type` — a
plain "Truecolor" / `color-type-orig: 2` (not 6/RGBA) means there's no real transparency yet,
regardless of what it looks like in a viewer.

**Why:** Compositing this image directly (e.g. as a logo) would show the gray/white checker
squares as opaque background in the app — not the transparency the user expects.

**How to apply:** Prefer the `removeImageBackground` callback first. If it fails repeatedly
(e.g. "Failed to upload image to Fal" — an infra-side issue, retries didn't help), fall back to
a local ImageMagick pipeline:
1. Dump raw RGB bytes (`magick in.png -depth 8 RGB:raw.rgb`).
2. In JS/Node, flag pixels as "checker-like" using a strict grayscale test (max(R,G,B) -
   min(R,G,B) below a small threshold — checker squares are pure achromatic gray, unlike most
   foreground content such as skin, fabric with hue, gold, etc).
3. BFS/flood-fill from the image border through only grayscale-flagged pixels — this reaches
   the checkerboard (contiguous, touches every edge) without touching disconnected grayscale
   regions inside the subject (e.g. dark hair), since those aren't connected to the border via
   an unbroken chain of grayscale pixels.
4. Build a mask (0 = background, 255 = keep), blur it slightly for anti-aliased edges, then
   composite back onto the original with `-compose CopyOpacity` to produce a real RGBA PNG.

This is a pure-geometry/connectivity trick specific to a checkerboard-flattened background — it
would falsely cut white/gray *foreground* elements (e.g. a white robe) if they touched the
image border, so check the composition first (subject should be inset from all four edges).
