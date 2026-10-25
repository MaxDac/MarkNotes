# MarkNotes Plus branding

The MarkNotes Plus icon. It keeps the shared MarkNotes page and "M↓" mark (see `libre/branding/`) but must stay
visibly different from the F-Droid app: an indigo tile instead of the blue one, and an amber plus badge.

| File | Use |
| --- | --- |
| `marknotes-plus-icon.svg` | Master app icon: rounded tile in indigo `#3949AB`, white page with a folded corner (`#C5CAE9`), the "M↓" mark in indigo and an amber (`#FFC107`) badge with an indigo plus in the bottom-right corner. |
| `marknotes-plus-mark.svg` | Page, "M↓" and badge on a transparent background, for in-app logos. |
| `marknotes-plus-monochrome.svg` | Single-colour silhouette for themed (monochrome) icons. |
| `png/marknotes-plus-icon-512.png` | Store-style icon (512 × 512). |
| `png/marknotes-plus-icon-1024.png` | High-resolution export. |

The SVGs are the source of truth. The Android assets are generated from them and committed under
`app/src/main/res`, where they override the shared resources of the same name in `libre/app-shared`, so the
F-Droid app keeps its own icon and builds need no extra tooling:

- `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (adaptive icon);
- `drawable/ic_launcher_foreground.xml` (content scaled into the 66dp safe zone);
- `drawable/ic_launcher_monochrome.xml` (themed icon layer);
- `values/ic_launcher_background.xml` (`#3949AB`);
- `drawable/marknotes_mark.xml` (in-app logo);
- the PNGs in `png/`.

The app name override ("MarkNotes Plus") is in `app/src/main/res/values/strings.xml`.

To regenerate the assets after changing an SVG, run the shared generator from the repository root (needs only a
JDK 17+, e.g. the one used for the Gradle build):

```sh
java libre/branding/GenerateIcons.java GENERATOR_ARGS
```
