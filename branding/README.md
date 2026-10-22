# MarkNotes branding

The shared MarkNotes icon, used by the Android app, the desktop (Electron) app and the website.

| File | Use |
| --- | --- |
| `marknotes-icon.svg` | Master app icon: rounded tile in the default accent Blue `#1E5EB4`, white page with a folded corner (`#A8C8FF`) and the markdown "M↓" mark. |
| `marknotes-mark.svg` | Page + "M↓" only, on a transparent background, for in-app logos. |
| `marknotes-monochrome.svg` | Single-colour silhouette for themed (monochrome) icons. |
| `png/marknotes-icon-512.png` | Play Store icon (512 × 512). |
| `png/marknotes-icon-1024.png` | High-resolution export. |

The SVGs are the source of truth. The Android assets are generated from them and committed, so builds need
no extra tooling:

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (adaptive icon);
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (content scaled into the 66dp safe zone);
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` (themed icon layer);
- `app/src/main/res/values/ic_launcher_background.xml` (`#1E5EB4`);
- `app/src/main/res/drawable/marknotes_mark.xml` (in-app logo);
- the PNGs in `png/`.

To regenerate them after changing an SVG, run from the repository root (needs only a JDK 17+, e.g. the one
used for the Gradle build):

```sh
java branding/GenerateIcons.java
```
