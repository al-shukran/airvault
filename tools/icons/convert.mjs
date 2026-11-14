#!/usr/bin/env node
/**
 * Converts the Lensly logo sources into Android VectorDrawables, and writes the Kotlin map from icon names to
 * drawables. tools/icons/README.md covers the sources, the license and how to run this.
 *
 * Usage, from the repository root:
 *   node tools/icons/convert.mjs [--src <dir>]
 *
 * --src  The folder that holds chains/, tokens/ and assets/lensly.svg. Defaults to .inbox at the repository root.
 *        Only those three inputs are read.
 */
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const svgToVectorDrawable = require('svg2vectordrawable');

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Chain and token drawables, and the map that names them, are used by shared code, so they live in :shared.
// The logo and the launcher icon are Lensly-only and stay in :app.
const SHARED_DRAWABLE_DIR = join(REPO_ROOT, 'shared', 'src', 'main', 'res', 'drawable');
const APP_DRAWABLE_DIR = join(REPO_ROOT, 'app', 'src', 'main', 'res', 'drawable');
const KOTLIN_MAP_FILE = join(
    REPO_ROOT, 'shared', 'src', 'main', 'java', 'app', 'lensly', 'ui', 'components', 'RegistryIcons.kt',
);

/** An icon up to this size is kept exactly as converted. */
const KEEP_AS_CONVERTED_BYTES = 64 * 1024;

/** An icon over KEEP_AS_CONVERTED_BYTES is simplified until it is under this size, or it is dropped. */
const SIMPLIFIED_TARGET_BYTES = 30 * 1024;

/** Simplification tolerances in viewport units (24 for chains and tokens), tried in order. */
const SIMPLIFY_TOLERANCES = [0.05, 0.1, 0.15, 0.2, 0.3, 0.4];

/** Curves are flattened into at most this many straight pieces, one piece per CURVE_STEP units of control length. */
const MAX_CURVE_STEPS = 12;
const CURVE_STEP = 0.25;

/** Generated files, by name prefix. ic_unknown_token.xml is hand-drawn and is not in this list. */
const GENERATED_PREFIXES = ['chain_', 'token_', 'logo_lensly', 'ic_launcher_'];

/** Adaptive launcher foreground: the glyph is scaled to 0.6 of the 1024 grid, then moved so its centre sits at 512. */
const LAUNCHER_SCALE = 0.6;
const LAUNCHER_TRANSLATE_X = 194;
const LAUNCHER_TRANSLATE_Y = 216;
const LAUNCHER_DP = 108;
const LOGO_DP = 96;

function parseArgs(argv) {
  const args = { src: join(REPO_ROOT, '.inbox') };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--src' && argv[i + 1]) {
      args.src = resolve(argv[i + 1]);
      i += 1;
    } else {
      throw new Error(`unknown or incomplete argument: ${argv[i]}`);
    }
  }
  return args;
}

/** A resource name: lowercase letters, digits and underscores. */
function slug(name) {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

function svgFiles(dir) {
  return readdirSync(dir).filter((file) => file.endsWith('.svg')).sort();
}

/** VectorDrawable cannot draw filters, so blur and shadow effects are removed before conversion. */
function stripFilters(svg) {
  return svg
    .replace(/<filter\b[\s\S]*?<\/filter>/g, '')
    .replace(/\sfilter="[^"]*"/g, '');
}

const PATH_TOKEN = /[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?/g;
const ARITY = { M: 2, L: 2, H: 1, V: 1, C: 6 };

/** Points along a cubic Bezier from p0 to p3, excluding p0 and including p3. */
function cubicSamples(x0, y0, x1, y1, x2, y2, x3, y3) {
  const controlLength = Math.hypot(x1 - x0, y1 - y0)
    + Math.hypot(x2 - x1, y2 - y1)
    + Math.hypot(x3 - x2, y3 - y2);
  const steps = Math.min(MAX_CURVE_STEPS, Math.max(1, Math.ceil(controlLength / CURVE_STEP)));
  const samples = [];
  for (let s = 1; s <= steps; s += 1) {
    const t = s / steps;
    const u = 1 - t;
    const a = u * u * u;
    const b = 3 * u * u * t;
    const c = 3 * u * t * t;
    const e = t * t * t;
    samples.push([a * x0 + b * x1 + c * x2 + e * x3, a * y0 + b * y1 + c * y2 + e * y3]);
  }
  return samples;
}

/**
 * The subpaths of a path made of moves, lines, horizontal and vertical lines, and cubic curves, with curves flattened
 * into lines. Each subpath is { points: [[x, y], ...], closed }, in absolute coordinates. Returns null for any other
 * command (quadratic curves, arcs), which are left as they are.
 */
function polylinesOf(d) {
  const tokens = d.match(PATH_TOKEN) ?? [];
  const isCommand = (token) => /^[A-Za-z]$/.test(token);
  const subpaths = [];
  let current = null;
  let x = 0;
  let y = 0;
  let startX = 0;
  let startY = 0;
  let i = 0;

  const lineTo = (px, py) => {
    if (!current) {
      current = { points: [[x, y]], closed: false };
      subpaths.push(current);
    }
    current.points.push([px, py]);
    x = px;
    y = py;
  };

  while (i < tokens.length) {
    if (!isCommand(tokens[i])) return null;
    const letter = tokens[i];
    i += 1;
    const upper = letter.toUpperCase();
    const relative = letter !== upper;
    if (upper === 'Z') {
      if (current) current.closed = true;
      x = startX;
      y = startY;
      current = null;
      continue;
    }
    if (!(upper in ARITY)) return null;
    let kind = upper;
    do {
      const args = [];
      for (let k = 0; k < ARITY[kind]; k += 1) {
        const token = tokens[i];
        if (token === undefined || isCommand(token)) return null;
        args.push(Number(token));
        i += 1;
      }
      const ox = relative ? x : 0;
      const oy = relative ? y : 0;
      if (kind === 'H') {
        lineTo(args[0] + ox, y);
      } else if (kind === 'V') {
        lineTo(x, args[0] + oy);
      } else if (kind === 'C') {
        const samples = cubicSamples(
          x, y,
          args[0] + ox, args[1] + oy,
          args[2] + ox, args[3] + oy,
          args[4] + ox, args[5] + oy,
        );
        for (const [px, py] of samples) lineTo(px, py);
      } else {
        const px = args[0] + ox;
        const py = args[1] + oy;
        if (kind === 'M') {
          current = { points: [[px, py]], closed: false };
          subpaths.push(current);
          startX = px;
          startY = py;
          x = px;
          y = py;
          kind = 'L'; // Pairs after a move are implicit lines.
        } else {
          lineTo(px, py);
        }
      }
    } while (i < tokens.length && !isCommand(tokens[i]));
  }
  return subpaths;
}

const formatNumber = (n) => {
  const rounded = Math.round(n * 1000) / 1000;
  return Object.is(rounded, -0) ? '0' : String(rounded);
};

function perpendicularDistance([px, py], [ax, ay], [bx, by]) {
  const dx = bx - ax;
  const dy = by - ay;
  const length = Math.hypot(dx, dy);
  if (length === 0) return Math.hypot(px - ax, py - ay);
  return Math.abs(dx * (ay - py) - (ax - px) * dy) / length;
}

/** Ramer-Douglas-Peucker: keeps the points that deviate more than [tolerance] from the simplified line. */
function keepSignificantPoints(points, tolerance) {
  if (points.length < 3) return points;
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [first, last] = stack.pop();
    let worst = -1;
    let worstDistance = 0;
    for (let i = first + 1; i < last; i += 1) {
      const distance = perpendicularDistance(points[i], points[first], points[last]);
      if (distance > worstDistance) {
        worst = i;
        worstDistance = distance;
      }
    }
    if (worst !== -1 && worstDistance > tolerance) {
      keep[worst] = 1;
      stack.push([first, worst], [worst, last]);
    }
  }
  return points.filter((_, i) => keep[i] === 1);
}

/** The path data rebuilt as simplified straight subpaths, or null when the path has commands that are not supported. */
function simplifyPathData(d, tolerance) {
  const subpaths = polylinesOf(d);
  if (subpaths === null) return null;
  return subpaths.map(({ points, closed }) => {
    const [first, ...rest] = keepSignificantPoints(points, tolerance);
    return `M${formatNumber(first[0])} ${formatNumber(first[1])}`
      + rest.map(([px, py]) => `L${formatNumber(px)} ${formatNumber(py)}`).join('')
      + (closed ? 'Z' : '');
  }).join('');
}

function simplifyPaths(svg, tolerance) {
  return svg.replace(/(\sd=")([^"]*)(")/g, (whole, open, d, close) => {
    const simplified = simplifyPathData(d, tolerance);
    return simplified === null ? whole : `${open}${simplified}${close}`;
  });
}

/**
 * The app is dark only, so a black fill or stroke would vanish against the black background. Those are drawn in white
 * instead, which keeps the shape of the mark.
 */
function brightenBlack(svg) {
  return svg.replace(/\s(fill|stroke)="(black|#000|#000000)"/gi, (_, attribute) => ` ${attribute}="#FFFFFF"`);
}

async function toVectorDrawable(svg, tolerance) {
  const filtered = brightenBlack(stripFilters(svg));
  const prepared = tolerance > 0 ? simplifyPaths(filtered, tolerance) : filtered;
  return svgToVectorDrawable(prepared, { floatPrecision: 2, strict: false });
}

/**
 * The VectorDrawable for [svg] with its header. An icon up to KEEP_AS_CONVERTED_BYTES is kept as converted. A larger one
 * is simplified at the first tolerance that brings it under SIMPLIFIED_TARGET_BYTES. When none does, the result has
 * dropped set and the caller leaves the icon out.
 */
async function fitIcon(svg, header) {
  const exact = header + (await toVectorDrawable(svg, 0));
  if (Buffer.byteLength(exact, 'utf8') <= KEEP_AS_CONVERTED_BYTES) {
    return { xml: exact, tolerance: 0, dropped: false };
  }
  let last = { xml: exact, tolerance: 0 };
  for (const tolerance of SIMPLIFY_TOLERANCES) {
    const xml = header + (await toVectorDrawable(svg, tolerance));
    last = { xml, tolerance };
    if (Buffer.byteLength(xml, 'utf8') <= SIMPLIFIED_TARGET_BYTES) return { ...last, dropped: false };
  }
  return { ...last, dropped: true };
}

const headerFor = (source) =>
  `<!--\n    Generated from ${source} by tools/icons/convert.mjs. Do not edit by hand.\n-->\n`;

/** Sets the width and height of the root vector, in dp. */
function withRootSize(xml, dp) {
  return xml
    .replace(/android:width="[^"]*"/, `android:width="${dp}dp"`)
    .replace(/android:height="[^"]*"/, `android:height="${dp}dp"`);
}

/** Wraps the whole glyph in the launcher transform, so it fits the adaptive-icon safe zone. */
function withLauncherTransform(xml) {
  const open = xml.match(/<vector\b[^>]*>/);
  const close = xml.lastIndexOf('</vector>');
  const inner = xml.slice(open.index + open[0].length, close);
  const root = withRootSize(open[0], LAUNCHER_DP);
  return `${xml.slice(0, open.index)}${root}
    <group
        android:scaleX="${LAUNCHER_SCALE}"
        android:scaleY="${LAUNCHER_SCALE}"
        android:translateX="${LAUNCHER_TRANSLATE_X}"
        android:translateY="${LAUNCHER_TRANSLATE_Y}">${inner}</group>
</vector>
`;
}

/**
 * Splits lensly.svg into its background (the gradient field and its decorations) and its glyph (the card, the lens and
 * the sparkles). The rounded tile is left out: the launcher layers are full-bleed, and the system mask does the
 * rounding. The logo itself keeps the tile's clip path, which is the flattened form of the luminance mask.
 */
function splitLensly(svg) {
  const maskOpen = svg.match(/<g mask="url\(#[^"]+\)">/);
  const tileStart = svg.indexOf('<path d="M240.552 0H783.448');
  const backgroundStart = svg.indexOf('<path d="M1076.97 -52.9648H-52.9648V1076.97H1076.97V-52.9648Z"');
  const glyphStart = svg.indexOf('<path d="M121.379 337.656C121.379 302.345');
  if (!maskOpen || tileStart < 0 || backgroundStart < 0 || glyphStart < 0) {
    throw new Error('assets/lensly.svg has changed shape: update splitLensly in tools/icons/convert.mjs');
  }
  const contentEnd = svg.lastIndexOf('</g>', tileStart);
  const defs = svg.slice(svg.indexOf('<defs>'), svg.indexOf('</defs>') + '</defs>'.length);
  const header = '<svg width="1024" height="1024" viewBox="0 0 1024 1024" fill="none" '
    + 'xmlns="http://www.w3.org/2000/svg">';
  return {
    background: `${header}${defs}${svg.slice(backgroundStart, glyphStart)}</svg>`,
    glyph: `${header}${defs}${svg.slice(glyphStart, contentEnd)}</svg>`,
  };
}

/** The glyph in one flat colour, for the monochrome layer. The system tints it, so only the shape matters. */
function monochromeOf(glyphSvg) {
  return glyphSvg
    .replace(/\sfill="(?!none")[^"]*"/g, ' fill="#FFFFFF"')
    .replace(/\sstroke="(?!none")[^"]*"/g, ' stroke="#FFFFFF"');
}

function registryIconsKotlin(chainNames, tokenNames) {
  const entries = (names) => names.map((name) => `    "${name}" to R.drawable.${name},`).join('\n');
  return `// Generated by tools/icons/convert.mjs. Do not edit by hand.
package app.lensly.ui.components

import app.lensly.shared.R

/** Chain logos, keyed by the \`icon\` value of a network entry. */
internal val chainIconDrawables: Map<String, Int> = mapOf(
${entries(chainNames)}
)

/** Token logos, keyed by the \`icon\` value of a token entry. */
internal val tokenIconDrawables: Map<String, Int> = mapOf(
${entries(tokenNames)}
)
`;
}

async function main() {
  const { src } = parseArgs(process.argv.slice(2));
  const chainDir = join(src, 'chains');
  const tokenDir = join(src, 'tokens');
  const lenslyPath = join(src, 'assets', 'lensly.svg');

  for (const dir of [SHARED_DRAWABLE_DIR, APP_DRAWABLE_DIR]) {
    mkdirSync(dir, { recursive: true });
    for (const file of readdirSync(dir)) {
      if (file.endsWith('.xml') && GENERATED_PREFIXES.some((prefix) => file.startsWith(prefix))) {
        rmSync(join(dir, file));
      }
    }
  }

  const written = [];
  const dropped = [];
  const names = { chain: [], token: [] };
  const writeDrawable = (name, xml, tolerance) => {
    const dir = /^(chain_|token_)/.test(name) ? SHARED_DRAWABLE_DIR : APP_DRAWABLE_DIR;
    writeFileSync(join(dir, `${name}.xml`), xml, 'utf8');
    written.push({ name, bytes: Buffer.byteLength(xml, 'utf8'), tolerance });
  };

  const sources = [
    ...svgFiles(chainDir).map((file) => ({ kind: 'chain', file, dir: chainDir, prefix: 'chain_' })),
    ...svgFiles(tokenDir).map((file) => ({ kind: 'token', file, dir: tokenDir, prefix: 'token_' })),
  ];
  const seen = new Set();
  for (const { kind, file, dir, prefix } of sources) {
    const name = prefix + slug(basename(file, '.svg'));
    if (seen.has(name)) throw new Error(`two sources map to ${name}`);
    seen.add(name);
    const source = `${basename(dir)}/${file}`;
    const result = await fitIcon(readFileSync(join(dir, file), 'utf8'), headerFor(source));
    if (result.dropped) {
      const bytes = Buffer.byteLength(result.xml, 'utf8');
      dropped.push({ name, reason: `${bytes} bytes after simplification, over the ${SIMPLIFIED_TARGET_BYTES} limit` });
      continue;
    }
    writeDrawable(name, result.xml, result.tolerance);
    names[kind].push(name);
  }

  const lensly = readFileSync(lenslyPath, 'utf8');
  const logo = await fitIcon(lensly, headerFor('assets/lensly.svg'));
  writeDrawable('logo_lensly', withRootSize(logo.xml, LOGO_DP), 0);

  const { background, glyph } = splitLensly(lensly);
  const launcherBackground = await fitIcon(background, headerFor('assets/lensly.svg (launcher background)'));
  writeDrawable('ic_launcher_background', withRootSize(launcherBackground.xml, LAUNCHER_DP), 0);
  const launcherForeground = await fitIcon(glyph, headerFor('assets/lensly.svg (launcher foreground)'));
  writeDrawable('ic_launcher_foreground', withLauncherTransform(launcherForeground.xml), 0);
  const launcherMonochrome = await fitIcon(monochromeOf(glyph), headerFor('assets/lensly.svg (monochrome)'));
  writeDrawable('ic_launcher_monochrome', withLauncherTransform(launcherMonochrome.xml), 0);

  writeFileSync(KOTLIN_MAP_FILE, registryIconsKotlin(names.chain, names.token), 'utf8');

  const total = (filter) => written.filter(filter).reduce((sum, item) => sum + item.bytes, 0);
  console.log(`wrote ${written.length} drawables to ${DRAWABLE_DIR}`);
  console.log(`chains: ${names.chain.length}, tokens: ${names.token.length}`);
  console.log(`total bytes: ${total(() => true)} (chains ${total((i) => i.name.startsWith('chain_'))}, `
    + `tokens ${total((i) => i.name.startsWith('token_'))})`);
  for (const item of written.filter((i) => i.tolerance > 0)) {
    console.log(`simplified ${item.name} at tolerance ${item.tolerance} (${item.bytes} bytes)`);
  }
  for (const item of dropped) console.log(`DROPPED ${item.name}: ${item.reason}`);
  console.log(`kotlin map: ${KOTLIN_MAP_FILE}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
