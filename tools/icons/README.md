# Icon tools

`convert.mjs` turns the logo SVGs into Android VectorDrawables in `app/src/main/res/drawable/`. It also writes
`app/src/main/java/app/lensly/ui/components/RegistryIcons.kt`, the map from each icon name to its drawable.

## Sources and license

| Input | Contents | License |
| --- | --- | --- |
| `chains/*.svg` | 61 chain logos from web3icons | MIT, see [ATTRIBUTION-chains.md](ATTRIBUTION-chains.md) |
| `tokens/*.svg` | 67 token logos from web3icons and Iconify | MIT, see [ATTRIBUTION-tokens.md](ATTRIBUTION-tokens.md) |
| `assets/lensly.svg` | The Lensly logo, used for the launcher icon and the logo | Lensly project asset |

Logos and trademarks remain the property of their owners. The SVG sources are conversion inputs only. They are not
committed. Keep them in `.inbox/`, which is gitignored, and point the script at that folder with `--src`.

## Regenerate

```sh
cd tools/icons
npm ci
npm run convert -- --src /path/to/.inbox
```

Only `chains/`, `tokens/` and `assets/lensly.svg` are read. Then run `./gradlew :app:assembleDebug`. The generated
files are committed, so the app builds without these tools.

## Rules

- An icon up to 64 KB is kept as converted.
- A larger icon is simplified, with curves flattened and nearby points merged, until it is under 30 KB. An icon that
  still cannot get under 30 KB is dropped. The script prints every simplified and dropped icon.
- Filters (blur and shadow) are removed, because VectorDrawable cannot draw them.
- Black fills and strokes are drawn in white. The app is dark only, so black would vanish against the background.
- Names: `chain_<slug>` with `-` replaced by `_`, `token_<symbol>`, `logo_lensly`, and the adaptive launcher layers
  `ic_launcher_background`, `ic_launcher_foreground` and `ic_launcher_monochrome`.
- `ic_unknown_token.xml` is drawn by hand and is not generated.
