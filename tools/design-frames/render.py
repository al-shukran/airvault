#!/usr/bin/env python3
"""Renders the approved design frames to design/frames/: one PNG per frame and theme at 1 px = 1 dp, and one <id>.spec.json per frame.

The spec holds, per theme, the frame's ink bands and every element's computed values (see README.md). Run it on a Mac or Linux box with
Chrome or Chromium: `python3 tools/design-frames/render.py --mocks <dir with the approved html files> [--only p1-05 ...]`.
The frames are the result; the design gate (lumio/src/test/.../design/DesignGate.kt) reads the committed files and never runs this tool.
"""
import argparse
import json
import re
from pathlib import Path

import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "design" / "frames"
FONTS = ROOT / "shared" / "src" / "main" / "res" / "font"
STATUS_BAR = 40  # dp, the mock's status bar: the app has none, so every y of a spec is the mock's y minus this
INK_THRESHOLD = 12  # per channel, 0-255, against the frame background
BAND_GAP = 1  # rows without ink that still join two ink runs

# Source files by frame id prefix. p1 and p2 frames are the .ph elements in document order, named and themed by tools/mockup-capture/ids.tsv;
# h1 frames carry their id in the html.
SOURCES = [
    ("p1", "2026-11-21-lumio-clear-air-part-1.html"),
    ("p2", "2026-11-21-lumio-clear-air-part-2.html"),
    ("h1", "2026-11-26-lumio-home-d1-revised.html"),
]

FONT_CSS = f"""
@font-face{{font-family:Archivo;src:url('file://{FONTS}/archivo_variable.ttf');font-weight:100 900;font-stretch:62% 125%}}
@font-face{{font-family:'JetBrains Mono';src:url('file://{FONTS}/jetbrains_mono_variable.ttf');font-weight:100 800}}
"""

# Everything but the frame is hidden, and the frame is pinned to the corner, square, with the system chrome (status bar, gesture pill) hidden.
ISOLATE_CSS = """
html *{visibility:hidden!important}
.__t,.__t *{visibility:visible!important}
.__t .sb,.__t .sb *,.__t .hi{visibility:hidden!important}
.__t{position:fixed!important;left:0!important;top:0!important;margin:0!important;border-radius:0!important;z-index:2147483000!important;zoom:1!important}
html,body{background:#fff!important;overflow:hidden!important}
"""

EXTRACT_JS = r"""
(args) => {
  const ph = document.querySelector('.__t');
  const o = ph.getBoundingClientRect();
  const shiftY = args.shiftY;
  const rgba = (c) => { const m = c.match(/[\d.]+/g).map(Number); return [m[0], m[1], m[2], m.length > 3 ? m[3] : 1]; };
  const num = (v) => Math.round(v * 100) / 100;
  const rect = (r) => ({x: num(r.left - o.left), y: num(r.top - o.top - shiftY), w: num(r.width), h: num(r.height)});
  const clipOf = (el) => {
    let c = {l: o.left, t: o.top + shiftY, r: o.right, b: o.bottom};
    for (let p = el.parentElement; p && p !== ph.parentElement; p = p.parentElement) {
      const s = getComputedStyle(p);
      if (s.overflowX !== 'visible' || s.overflowY !== 'visible') {
        const r = p.getBoundingClientRect();
        c = {l: Math.max(c.l, r.left), t: Math.max(c.t, r.top), r: Math.min(c.r, r.right), b: Math.min(c.b, r.bottom)};
      }
    }
    return c;
  };
  const hidden = (el) => {
    for (let p = el; p && p !== ph.parentElement; p = p.parentElement) {
      const s = getComputedStyle(p);
      if (s.display === 'none' || parseFloat(s.opacity) < 0.02) return true;
      if (p.classList.contains('sb') || p.classList.contains('hi')) return true;
    }
    return false;
  };
  // An element whose centre lies under an opaque element that is not its own is not on screen: the tab bar over the last row of a list. The top left corner is tested, so a row cut off at its bottom still counts.
  const covered = (el, u) => {
    const top = document.elementFromPoint(u.l + 2, u.t + 2);
    if (!top || el.contains(top) || top.contains(el)) return false;
    for (let p = top; p && p !== ph.parentElement; p = p.parentElement) {
      if (p.contains(el)) break;
      if (rgba(getComputedStyle(p).backgroundColor)[3] >= 0.95) return true;
    }
    return false;
  };
  const name = (el) => {
    const cls = [...el.classList].filter((c) => c !== '__t').slice(0, 2).join('.');
    return el.tagName.toLowerCase() + (cls ? '.' + cls : '');
  };
  // CONSTANTS are a frame's layout, containers, fonts and colours. VARIABLES are what the data decides: a token logo, an avatar, a monogram, a
  // sparkline, and the words of a data row (name, symbol, price, 24 h change) or any text that is wholly data (an amount, a percentage, an address,
  // a date). The gate checks that a variable is there, where it is, and its font and colour, not what it says or draws (README.md).
  // A logo is a picture the data chooses: a token or chain mark (<use href="#l-...">, i.lm), an account avatar (#emb-...) or a monogram. The
  // UI's own icons (#i-...) and the "?" monogram of an unknown address are constants.
  const MONO = '.mono-d:not(.ghost)';
  const MARK = 'i.lm,use[href^="#l-"],use[href^="#emb-"],' + MONO;
  const NUM = /^[▲▼+−\-≈~]*[$€£]?\d[\d.,:]*[%KMBkmb]?$/;
  const OTHER = /^(?:0x[0-9a-fA-F…]+|(?=.*\d)[0-9a-f…]{4,}|#\d[\d,]*|•+|·|—|▲|▼|[+−\-≈~]|[$€£])$/;
  const UNIT = /^(?:[A-Z]{2,6}|[Tt]oday|[Nn]ow|gwei|sat\/vB|min|h|s)$/; // a unit, after a number
  const isData = (t) => {
    const parts = t.split(/\s+/).filter(Boolean);
    return /[\d•▲▼]/.test(t) && parts.every((q, i) => NUM.test(q) || OTHER.test(q) || (i > 0 && NUM.test(parts[i - 1]) && UNIT.test(q) && q !== 'OF'));
  };
  const variableText = (el, text) => {
    if (el.closest(MONO)) return 'logo';
    const row = el.closest('.row');
    if (row && row.querySelector(MARK)) return 'row';
    return isData(text) ? 'data' : null;
  };
  const variableIcon = (el) => (el.matches('svg.spk') ? 'chart' : el.matches('i.lm') || el.querySelector('use[href^="#l-"],use[href^="#emb-"]') || el.closest(MONO) ? 'logo' : null);
  // Where a variable text is held when its words change: the edge that does not move when the text shrinks to one letter.
  const anchorOf = (el, before) => {
    const nodes = [...el.childNodes].filter((n) => n.nodeType === 3 && n.textContent.trim());
    const saved = nodes.map((n) => n.textContent);
    nodes.forEach((n, i) => { n.textContent = i === 0 ? 'i' : ''; });
    const rs = nodes.flatMap((n) => { const rg = document.createRange(); rg.selectNodeContents(n); return [...rg.getClientRects()].filter((q) => q.width > 0); });
    nodes.forEach((n, i) => { n.textContent = saved[i]; });
    if (!rs.length) return 'left';
    const l = Math.min(...rs.map((q) => q.left)), r = Math.max(...rs.map((q) => q.right));
    if (Math.abs(l - before.l) < 0.6) return 'left';
    if (Math.abs(r - before.r) < 0.6) return 'right';
    return Math.abs((l + r) / 2 - (before.l + before.r) / 2) < 0.6 ? 'center' : 'left';
  };
  const SKIP = ['path', 'use', 'defs', 'g', 'circle', 'rect', 'line', 'polyline', 'polygon', 'ellipse', 'symbol', 'style', 'script', 'text', 'tspan', 'stop', 'lineargradient', 'clippath', 'mask'];
  const out = [];
  let seq = 0;
  for (const el of ph.querySelectorAll('*')) {
    if (hidden(el)) continue;
    const tag = el.tagName.toLowerCase();
    if (SKIP.includes(tag) || el.closest('svg') !== null && tag !== 'svg') continue;
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const clip = clipOf(el);
    const visible = (a) => Math.min(a.r, clip.r) - Math.max(a.l, clip.l) > 0.5 && Math.min(a.b, clip.b) - Math.max(a.t, clip.t) > 0.5;
    const path = () => { const parts = []; for (let p = el; p && p !== ph; p = p.parentElement) { parts.unshift(name(p) + '[' + [...p.parentElement.children].indexOf(p) + ']'); } return parts.join('>'); };
    // The element's own words: its direct text nodes, with the box of the text itself.
    const words = [];
    for (const n of el.childNodes) {
      if (n.nodeType === 3 && n.textContent.trim()) {
        const rg = document.createRange(); rg.selectNodeContents(n);
        const rs = [...rg.getClientRects()].filter((q) => q.width > 0);
        if (!rs.length) continue;
        const u = {l: Math.min(...rs.map((q) => q.left)), t: Math.min(...rs.map((q) => q.top)), r: Math.max(...rs.map((q) => q.right)), b: Math.max(...rs.map((q) => q.bottom))};
        words.push({text: n.textContent, box: u, lines: new Set(rs.map((q) => Math.round(q.top))).size});
      }
    }
    if (words.length) {
      let text = words.map((q) => q.text).join('').replace(/\s+/g, ' ').trim();
      if (s.textTransform === 'uppercase') text = text.toUpperCase();
      const u = {l: Math.min(...words.map((q) => q.box.l)), t: Math.min(...words.map((q) => q.box.t)), r: Math.max(...words.map((q) => q.box.r)), b: Math.max(...words.map((q) => q.box.b))};
      if (visible(u) && !covered(el, u)) {
        const variable = variableText(el, text);
        out.push({
          id: 'e' + (seq++), kind: 'text', node: name(el), path: path(), text,
          ...(variable ? {var: variable, anchor: anchorOf(el, u)} : {}),
          rect: rect({left: u.l, top: u.t, width: u.r - u.l, height: u.b - u.t}), lines: Math.max(...words.map((q) => q.lines)),
          font: {family: s.fontFamily, size: parseFloat(s.fontSize), weight: parseInt(s.fontWeight), stretch: s.fontStretch, lineHeight: s.lineHeight === 'normal' ? null : parseFloat(s.lineHeight), letterSpacing: s.letterSpacing === 'normal' ? 0 : parseFloat(s.letterSpacing)},
          color: rgba(s.color),
        });
      }
    }
    // The element's own box: a fill, a border, a shadow, or an icon or image.
    const bg = rgba(s.backgroundColor);
    const bw = Math.max(...['Top', 'Right', 'Bottom', 'Left'].map((d) => s['border' + d + 'Style'] === 'none' ? 0 : parseFloat(s['border' + d + 'Width'])));
    const shadow = s.boxShadow !== 'none';
    const isIcon = tag === 'svg' || tag === 'img' || (s.backgroundImage !== 'none' && s.backgroundImage.startsWith('url'));
    const fills = bg[3] > 0.02;
    if ((fills || bw > 0 || shadow || isIcon) && visible({l: r.left, t: r.top, r: r.right, b: r.bottom}) && r.width > 0 && r.height > 0 && !covered(el, {l: r.left, t: r.top, r: r.right, b: r.bottom})) {
      out.push({
        id: 'e' + (seq++), kind: isIcon ? 'icon' : 'box', node: name(el), path: path(), rect: rect(r), ...(isIcon && variableIcon(el) ? {var: variableIcon(el)} : {}),
        fill: fills ? bg : null, border: bw > 0 ? {width: bw, color: rgba(s.borderTopColor)} : null, radius: s.borderTopLeftRadius,
        shadow: shadow ? s.boxShadow : null,
      });
    }
  }
  return {width: o.width, height: o.height - shiftY, bg: rgba(getComputedStyle(ph).backgroundColor), elements: out};
}
"""


def bands_of(img: np.ndarray, bg: np.ndarray) -> list:
    """Horizontal runs of rows that hold ink: a pixel more than INK_THRESHOLD from the frame background in any channel."""
    ink = (np.abs(img[:, :, :3].astype(int) - bg[None, None, :3]).max(axis=2) > INK_THRESHOLD).any(axis=1)
    bands, start, last = [], None, None
    for y, on in enumerate(ink):
        if on:
            if start is None:
                start = y
            elif y - last > BAND_GAP + 1:
                bands.append([start, last + 1])
                start = y
            last = y
    if start is not None:
        bands.append([start, last + 1])
    return bands


def frame_list(mocks: Path):
    ids = {}
    for line in (ROOT / "tools" / "mockup-capture" / "ids.tsv").read_text().splitlines():
        fid, themes, title = line.split("\t")
        ids[fid] = (themes.split(","), title)
    for prefix, name in SOURCES:
        path = mocks / name
        html = path.read_text()
        if prefix == "h1":
            # The id names the theme too (home-light, home-dark): one frame id, two themes.
            found = {}
            for m in re.finditer(r'<div class="ph (lt|dk)" id="([a-z0-9-]+?)-(light|dark)"', html):
                found.setdefault(m.group(2), []).append((m.group(3), f"#{m.group(2)}-{m.group(3)}"))
            for name, variants in found.items():
                yield f"h1-{name}", path, dict(variants), [t for t, _ in variants], f"Home d1 revised, {name}"
        else:
            for i in range(len(re.findall(r'<div class="ph[ "]', html))):
                fid = f"{prefix}-{i:02d}"
                themes, title = ids[fid]
                yield fid, path, i, themes, title


def render(only):
    OUT.mkdir(parents=True, exist_ok=True)
    mocks = Path(ARGS.mocks)
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=ARGS.chrome, args=["--font-render-hinting=none", "--disable-lcd-text"])
        for fid, path, key, themes, title in frame_list(mocks):
            if only and fid not in only:
                continue
            spec = {"id": fid, "title": title, "inkThreshold": INK_THRESHOLD, "bandGap": BAND_GAP, "themes": {}}
            keys = key
            for theme in themes:
                ctx = browser.new_context(device_scale_factor=1, viewport={"width": 1400, "height": 1000})
                page = ctx.new_page()
                page.route(re.compile(r"https://fonts\.(googleapis|gstatic)\.com/.*"), lambda r: r.abort())
                page.goto(path.as_uri())
                page.add_style_tag(content=FONT_CSS)
                page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme)
                key = keys[theme] if isinstance(keys, dict) else keys
                page.evaluate(
                    "k => { const all = [...document.querySelectorAll('.ph')]; (typeof k === 'number' ? all[k] : document.querySelector(k)).classList.add('__t'); }",
                    key,
                )
                page.add_style_tag(content=ISOLATE_CSS)
                page.evaluate("document.fonts.ready.then(() => 0)")
                page.wait_for_timeout(150)
                size = page.evaluate("() => { const r = document.querySelector('.__t').getBoundingClientRect(); return [r.width, r.height]; }")
                data = page.evaluate(EXTRACT_JS, {"shiftY": STATUS_BAR})
                png = OUT / f"{fid}-{theme}.png"
                page.screenshot(path=str(png), clip={"x": 0, "y": STATUS_BAR, "width": size[0], "height": size[1] - STATUS_BAR})
                ctx.close()
                img = np.array(Image.open(png).convert("RGB"))
                bg = np.array([round(c) for c in data["bg"][:3]])
                spec["width"], spec["height"] = int(size[0]), int(size[1]) - STATUS_BAR
                spec["themes"][theme] = {"png": png.name, "bg": data["bg"][:3], "bands": bands_of(img, bg), "elements": data["elements"]}
                print(f"{fid}-{theme}: {len(spec['themes'][theme]['bands'])} bands, {len(data['elements'])} elements")
            (OUT / f"{fid}.spec.json").write_text(json.dumps(spec, indent=1, ensure_ascii=False) + "\n")
        browser.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--mocks", required=True, help="directory with the approved mock html files")
    ap.add_argument("--chrome", default="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    ap.add_argument("--only", nargs="*", default=[])
    ARGS = ap.parse_args()
    render(set(ARGS.only))
