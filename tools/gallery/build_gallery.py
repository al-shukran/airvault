#!/usr/bin/env python3
"""Build the screen gallery: every Roborazzi golden of :app and :lumio on one page.

Standard library only. Images are downscaled with macOS sips. The page groups the goldens app, then screen, then scenario, using the
manifest screens.tsv. Goldens no manifest row names are listed under "Not in the manifest". Manifest rows whose expected goldens are
missing are listed under "Not covered". Run through build-gallery.sh.
"""

import argparse
import base64
import datetime
import html
import pathlib
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MANIFEST = HERE / "screens.tsv"
SINGLE_LIMIT_BYTES = 15 * 1024 * 1024

# Module key, the name on the page, and the golden directory that the module's tests record into.
MODULES = {
    "app": ("Lensly", "app/src/test/screenshots"),
    "lumio": ("Lumio", "lumio/src/test/screenshots"),
}


@dataclass
class Screen:
    module: str
    screen_id: str
    title: str
    expected: list[str]
    goldens: list[str] = field(default_factory=list)
    missing: list[str] = field(default_factory=list)


def parse_manifest(path: pathlib.Path) -> list[Screen]:
    screens = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            sys.exit(f"{path}:{number}: want 4 tab-separated fields: module, screen id, title, expected golden names")
        module, screen_id, title, names = (f.strip() for f in fields)
        if module not in MODULES:
            sys.exit(f"{path}:{number}: unknown module '{module}', want one of {', '.join(MODULES)}")
        screens.append(Screen(module, screen_id, title, names.split()))
    return screens


def matches(pattern: str, name: str) -> bool:
    """An expected name matches exactly, or as a prefix when it ends with '*'."""
    return name.startswith(pattern[:-1]) if pattern.endswith("*") else name == pattern


def specificity(pattern: str) -> int:
    return len(pattern.rstrip("*"))


def assign(screens: list[Screen], module: str, names: list[str]) -> list[str]:
    """Give each golden of [module] to the screen whose most specific pattern matches it. Returns the goldens no screen names."""
    unlisted = []
    rows = [s for s in screens if s.module == module]
    for name in names:
        best, best_len = None, -1
        for screen in rows:
            for pattern in screen.expected:
                if matches(pattern, name) and specificity(pattern) > best_len:
                    best, best_len = screen, specificity(pattern)
        if best is None:
            unlisted.append(name)
        else:
            best.goldens.append(name)
    for screen in rows:
        for pattern in screen.expected:
            if not any(matches(pattern, g) for g in names):
                screen.missing.append(pattern)
    return unlisted


def scenario_label(screen_id: str, name: str) -> str:
    rest = name[len(screen_id) + 1:] if name.startswith(screen_id + "_") else name
    return (rest or name).replace("_", " ")


def collapse_light_dark(names: list[str]) -> list[tuple[str, list[str]]]:
    """Pair NAME_light and NAME_dark into one card when both exist. Every other golden is a card of its own."""
    present = set(names)
    cards, used = [], set()
    for name in names:
        if name in used:
            continue
        for suffix in ("_light", "_dark"):
            if name.endswith(suffix):
                base = name[: -len(suffix)]
                pair = [base + "_light", base + "_dark"]
                if all(p in present for p in pair):
                    cards.append((base, pair))
                    used.update(pair)
                    break
        else:
            cards.append((name, [name]))
            used.add(name)
    return cards


def golden_files(module: str) -> list[pathlib.Path]:
    directory = ROOT / MODULES[module][1]
    return sorted(directory.glob("*.png")) if directory.is_dir() else []


def downscale(source: pathlib.Path, target: pathlib.Path, width: int) -> None:
    """Write [source] at [width] px wide as a JPEG at quality 80. A 360 px thumbnail reads the same, at a fifth of the PNG size."""
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["sips", "--resampleWidth", str(width), "-s", "format", "jpeg", "-s", "formatOptions", "80", str(source), "--out", str(target)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def git(*args: str) -> str:
    result = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else ""


def render(screens: list[Screen], unlisted: dict[str, list[str]], image_src, header: dict[str, str]) -> str:
    """The page. [image_src] gives the src of a golden: a relative path, or a data URI when the page is single-file."""
    counts = {m: sum(len(s.goldens) for s in screens if s.module == m) + len(unlisted.get(m, [])) for m in MODULES}
    parts = [
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">",
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        "<title>AirVault screen gallery</title>",
        "<style>",
        ":root{--bg:#000;--card:#111;--ink:#f2f2f2;--muted:#8a8a8a;--line:#262626}",
        "@media (prefers-color-scheme: light){:root:not([data-theme=\"dark\"]){--bg:#f5f5f5;--card:#fff;--ink:#111;--muted:#666;--line:#ddd}}",
        "body{margin:0;padding:16px;background:var(--bg);color:var(--ink);font:15px/1.4 -apple-system,system-ui,sans-serif}",
        "h1{font-size:22px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 4px}h3{font-size:14px;margin:18px 0 8px;color:var(--muted);font-weight:600}",
        ".meta{color:var(--muted);font-size:13px}",
        "nav a{margin-right:12px;color:var(--ink)}",
        ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px}",
        ".card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px}",
        ".pair{display:flex;gap:6px}.pair figure{flex:1;margin:0}",
        ".card img{width:100%;height:auto;display:block;border-radius:6px}",
        ".card .cap{font-size:13px;margin-top:6px}.card .file{font-size:11px;color:var(--muted);word-break:break-all}",
        ".missing li{font-family:ui-monospace,Menlo,monospace;font-size:13px}",
        "</style></head><body>",
        "<h1>AirVault screen gallery</h1>",
        f"<div class=\"meta\">Commit {html.escape(header['sha'])}{html.escape(header['dirty'])} · built {html.escape(header['built'])} · "
        f"{counts['app']} Lensly goldens, {counts['lumio']} Lumio goldens</div>",
        "<nav>" + "".join(f"<a href=\"#{m}\">{html.escape(MODULES[m][0])}</a>" for m in MODULES) +
        "<a href=\"#not-covered\">Not covered</a></nav>",
    ]
    for module, (name, _) in MODULES.items():
        parts.append(f"<h2 id=\"{module}\">{html.escape(name)}</h2>")
        for screen in (s for s in screens if s.module == module and s.goldens):
            parts.append(f"<h3>{html.escape(screen.title)} <span class=\"meta\">({len(screen.goldens)})</span></h3><div class=\"grid\">")
            parts.append(card(screen.screen_id, collapse_light_dark(screen.goldens), image_src, module))
            parts.append("</div>")
        if unlisted.get(module):
            parts.append("<h3>Not in the manifest</h3><div class=\"grid\">")
            parts.append(card("", collapse_light_dark(unlisted[module]), image_src, module))
            parts.append("</div>")
    parts.append("<h2 id=\"not-covered\">Not covered</h2>")
    gaps = [s for s in screens if s.missing]
    if not gaps:
        parts.append("<p class=\"meta\">Every screen in the manifest has its goldens.</p>")
    for screen in gaps:
        parts.append(
            f"<h3>{html.escape(MODULES[screen.module][0])} · {html.escape(screen.title)}</h3>"
            "<ul class=\"missing\">" + "".join(f"<li>{html.escape(m)}</li>" for m in screen.missing) + "</ul>"
        )
    parts.append("</body></html>")
    return "\n".join(parts)


def card(screen_id: str, cards, image_src, module: str) -> str:
    out = []
    for label_name, names in cards:
        label = scenario_label(screen_id, label_name) if screen_id else label_name
        if len(names) == 1:
            body = f"<img src=\"{image_src(module, names[0])}\" alt=\"{html.escape(names[0])}\" loading=\"lazy\">"
        else:
            body = "<div class=\"pair\">" + "".join(
                f"<figure><img src=\"{image_src(module, n)}\" alt=\"{html.escape(n)}\" loading=\"lazy\"><figcaption class=\"file\">"
                f"{html.escape(n.rsplit('_', 1)[1])}</figcaption></figure>" for n in names
            ) + "</div>"
        out.append(
            f"<div class=\"card\">{body}<div class=\"cap\">{html.escape(label)}</div>"
            f"<div class=\"file\">{html.escape(names[0])}.png</div></div>"
        )
    return "".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", default=str(ROOT / "out" / "gallery"), help="output folder (default: out/gallery)")
    parser.add_argument("--width", type=int, default=360, help="image width in px (default: 360)")
    parser.add_argument("--single", action="store_true", help="also write gallery-single.html with the images embedded")
    args = parser.parse_args()

    if shutil.which("sips") is None:
        sys.exit("build-gallery: sips is required (macOS)")

    out = pathlib.Path(args.out)
    img_root = out / "img"
    if img_root.exists():
        shutil.rmtree(img_root)

    screens = parse_manifest(MANIFEST)
    unlisted: dict[str, list[str]] = {}
    names_of: dict[str, list[str]] = {}
    for module in MODULES:
        files = golden_files(module)
        names_of[module] = [f.stem for f in files]
        for f in files:
            downscale(f, img_root / module / f"{f.stem}.jpg", args.width)
        unlisted[module] = assign(screens, module, names_of[module])

    dirty = bool(git("status", "--porcelain", "--", *(d for _, d in MODULES.values())))
    header = {
        "sha": git("rev-parse", "--short", "HEAD") or "unknown",
        "dirty": " + uncommitted goldens" if dirty else "",
        "built": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
    }

    def relative_src(module: str, name: str) -> str:
        return f"img/{module}/{name}.jpg"

    out.mkdir(parents=True, exist_ok=True)
    (out / "index.html").write_text(render(screens, unlisted, relative_src, header), encoding="utf-8")
    print(f"gallery: {out / 'index.html'}")

    if args.single:
        cache: dict[tuple[str, str], str] = {}

        def data_src(module: str, name: str) -> str:
            key = (module, name)
            if key not in cache:
                data = (img_root / module / f"{name}.jpg").read_bytes()
                cache[key] = "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii")
            return cache[key]

        single = out / "gallery-single.html"
        single.write_text(render(screens, unlisted, data_src, header), encoding="utf-8")
        size = single.stat().st_size
        if size > SINGLE_LIMIT_BYTES:
            sys.exit(f"gallery-single.html is {size} bytes, over the 15 MB limit. Lower --width.")
        print(f"single file: {single} ({size / 1024 / 1024:.1f} MB)")

    for module in MODULES:
        screened = sum(len(s.goldens) for s in screens if s.module == module)
        print(f"{module}: {len(names_of[module])} goldens, {screened} on the manifest, {len(unlisted[module])} not in the manifest")
        for name in unlisted[module]:
            print(f"  not in the manifest: {name}")
    gaps = sum(len(s.missing) for s in screens)
    print(f"not covered: {gaps} expected golden(s) missing from the manifest screens")


if __name__ == "__main__":
    main()
