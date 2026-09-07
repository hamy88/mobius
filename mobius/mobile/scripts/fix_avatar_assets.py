#!/usr/bin/env python3
"""
Defringe + recenter the Mo / Mobius brand PNG assets.

Root cause of the reported "black edge / shadow" on:
  - the install/launcher icon (ic_launcher)            <- rendered by Android framework, NOT Compose
  - the app header top-left logo (MomoLogo 28dp)        <- Compose
  - the conversation-list avatar (MomoLogo 48dp)        <- Compose
All three draw the SAME `mo_avatar` artwork. That PNG stores BLACK (0,0,0) RGB in its
transparent + anti-aliased edge pixels. When the 1024px master is downscaled to a small
display size (28dp / a launcher icon), bilinear resampling blends that stored black into
the soft alpha edge, producing a dark halo/fringe over any light background. Tweaking the
Compose layer cannot fix the launcher icon path, so we fix the assets themselves.

This script does three things, per asset:
  1. DEFRINGE (color decontamination): re-paint every pixel's RGB from its nearest
     near-opaque source pixel (alpha >= SRC_ALPHA), leaving the alpha channel UNTOUCHED.
     Result: no black RGB remains anywhere, so resampling can never introduce a dark fringe.
  2. (master avatar only) RECENTER + CROP-TO-FIT: the original is off-center (transparent
     margin T=23 B=71 L=21 R=71) and never fills the canvas. We center on the alpha
     centroid and crop to the artwork's alpha extent so the circle is inscribed in the
     square -> a Compose CircleShape clip shows the full, clean circle with no offset.
  3. (master avatar only) GENTLE EDGE TIGHTEN: the feathered edge is very wide (alpha
     ramp from 11..255), which reads as a "shadow" over colored backgrounds. A mild alpha
     contrast remap shrinks the halo without altering the artwork.

Usage:
  uv run --with Pillow --with numpy --with scipy python3 scripts/fix_avatar_assets.py
"""
import os
from PIL import Image
import numpy as np
from scipy import ndimage

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SRC_ALPHA = 220      # treat pixels at/above this alpha as opaque "color source"
OUT_ALPHA = 8        # bbox threshold: include pixels down to this alpha (preserve AA edge)
EDGE_GAMMA = 1.6     # alpha contrast (>1 tightens the soft edge / removes shadowy halo)


def nearest_source_rgb(a):
    """Repaint every pixel's RGB with the RGB of its nearest alpha>=SRC_ALPHA pixel."""
    alpha = a[..., 3]
    src = alpha >= SRC_ALPHA
    # If (almost) nothing qualifies as a hard source, lower the bar so we still defringe.
    if src.sum() < 100:
        thr = max(1, int(np.percentile(alpha[alpha > 0], 90)))
        src = alpha >= thr
    # nearest source index for every pixel via Euclidean distance transform
    _, (iy, ix) = ndimage.distance_transform_edt(~src, return_indices=True)
    out = a.copy()
    out[..., :3] = a[iy, ix, :3]
    return out


def tighten_edge(a):
    """Mildly raise low alphas / cut the long feather tail so the edge isn't a shadowy halo."""
    al = a[..., 3].astype(np.float32) / 255.0
    # power curve: pulls mid-low alphas up toward opaque along the edge
    al = np.power(al, 1.0 / EDGE_GAMMA)
    # keep a tiny bit of AA at the very edge for smoothness
    out = a.copy()
    out[..., 3] = np.clip(al * 255.0, 0, 255).astype(np.uint8)
    return out


def recenter_crop(a, target):
    """Crop to the artwork's alpha extent, symmetrically pad to a centered square, resize.

    The circle's bounding box becomes the crop, so the disc is INSCRIBED in the square;
    symmetric padding guarantees the alpha-centred content sits dead-centre, so a Compose
    CircleShape clip shows the full, concentric circle with nothing clipped.
    """
    alpha = a[..., 3]
    mask = alpha > OUT_ALPHA
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return a
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    crop = a[y0:y1 + 1, x0:x1 + 1]
    h, w = crop.shape[:2]
    s = max(h, w)
    pad_top = (s - h) // 2
    pad_bot = s - h - pad_top
    pad_l = (s - w) // 2
    pad_r = s - w - pad_l
    # constant pad (alpha 0); colour is irrelevant — defringe repaints it afterwards.
    padded = np.pad(crop, ((pad_top, pad_bot), (pad_l, pad_r), (0, 0)), mode="constant")
    img = Image.fromarray(padded, "RGBA").resize((target, target), Image.LANCZOS)
    return np.array(img)


def process_master_avatar(path, target=1024):
    im = Image.open(path).convert("RGBA")
    a = np.array(im)
    # Tighten on the ORIGINAL feather first (where it is naturally uniform), then do
    # geometry, then defringe LAST so no black survives the resize/pad.
    a = tighten_edge(a)              # shrink the wide shadowy halo on the original feather
    a = recenter_crop(a, target)     # crop to bbox + symmetric pad to a centred square + resize
    a = nearest_source_rgb(a)        # DEFRINGE LAST — repaints every pixel, kills all black RGB
    Image.fromarray(a, "RGBA").save(path)
    return a


def process_defringe_only(path):
    """For icons/logos that must keep their margins: defringe in place, no crop/resize."""
    a = np.array(Image.open(path).convert("RGBA"))
    a = nearest_source_rgb(a)
    Image.fromarray(a, "RGBA").save(path)
    return a


def report(label, a_before, a_after):
    def black_in_transparent(a):
        t = a[a[..., 3] == 0]
        return None if len(t) == 0 else t[..., :3].max()
    print(f"  {label:42} transp-black-before={black_in_transparent(a_before)!s:6} "
          f"after={black_in_transparent(a_after)!s:6}  "
          f"min-edge-rgb-before={a_before[(a_before[...,3]>0)&(a_before[...,3]<255)][...,:3].min() if ((a_before[...,3]>0)&(a_before[...,3]<255)).any() else '-'} "
          f"after={a_after[(a_after[...,3]>0)&(a_after[...,3]<255)][...,:3].min() if ((a_after[...,3]>0)&(a_after[...,3]<255)).any() else '-'}")


def main():
    # 1) Master avatar (Compose resource) — recenter + defringe + tighten
    masters = [
        os.path.join(ROOT, "shared/src/commonMain/composeResources/drawable/mo_avatar.png"),
        os.path.join(ROOT, "webPreview/src/commonMain/composeResources/drawable/mo_avatar.png"),
        os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/MoAvatar.imageset/mo_avatar.png"),
    ]
    print("== mo_avatar master (recenter + defringe + tighten) ==")
    # Rebuild from the shared master first, then copy identical bytes everywhere.
    shared_master = masters[0]
    before = np.array(Image.open(shared_master).convert("RGBA"))
    process_master_avatar(shared_master, target=1024)
    after = np.array(Image.open(shared_master).convert("RGBA"))
    report("shared mo_avatar", before, after)
    # propagate identical file to the other two copies
    import shutil
    for p in masters[1:]:
        shutil.copyfile(shared_master, p)
        print(f"  propagated -> {os.path.relpath(p, ROOT)}")

    # 2) Launcher icons — defringe only (keep Android safe-zone margins)
    print("== ic_launcher / ic_launcher_round (defringe only, keep margin) ==")
    for dp in ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
        for name in ["ic_launcher.png", "ic_launcher_round.png"]:
            p = os.path.join(ROOT, f"androidApp/src/androidMain/res/mipmap-{dp}/{name}")
            if not os.path.exists(p):
                continue
            b = np.array(Image.open(p).convert("RGBA"))
            process_defringe_only(p)
            r = np.array(Image.open(p).convert("RGBA"))
            report(f"mipmap-{dp}/{name}", b, r)

    # 3) mobius_logo (wordmark) — defringe only
    print("== mobius_logo (defringe only) ==")
    for p in [
        os.path.join(ROOT, "shared/src/androidMain/res/drawable/mobius_logo.png"),
        os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/MobiusLogo.imageset/mobius_logo.png"),
        os.path.join(ROOT, "desktopApp/icons/mobius_logo.png"),
        os.path.join(ROOT, "desktopApp/src/desktopMain/resources/mobius_logo.png"),
        os.path.join(ROOT, "desktopPreview/src/desktopMain/resources/mobius_logo.png"),
    ]:
        if not os.path.exists(p):
            continue
        b = np.array(Image.open(p).convert("RGBA"))
        process_defringe_only(p)
        r = np.array(Image.open(p).convert("RGBA"))
        report(os.path.relpath(p, ROOT), b, r)

    print("done.")


if __name__ == "__main__":
    main()
