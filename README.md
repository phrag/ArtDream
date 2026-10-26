# fArtSaver

An Android TV screensaver that shows high-resolution, public-domain paintings
from museum open-access collections. Built for NVIDIA Shield and stock Android TV
boxes. Plain Kotlin, no third-party dependencies. GPL-3.0.

- Paintings are fitted to the screen over a blurred, dimmed copy of the same painting, so non-16:9 works still look good.
- Slow Ken Burns drift and crossfades, which also help against OLED burn-in.
- Optional caption: title, artist, date and the lending museum.
- Plays from an on-disk cache (about 1.5 MB per painting, capped at 120 paintings) and tops it up in the background each time it starts and once a day, so it never stalls on the network.

## Image sources

All are free, need no API key, and only request public domain / CC0 images — except
Wikimedia Commons, where CC BY / CC BY-SA photos are also accepted (no NC/ND) and the
author + licence are carried in the artwork (`credit`, `license`) for the caption.

| Source | Category | Endpoint | Image size |
|---|---|---|---|
| Cleveland Museum of Art | Paintings | `openaccess-api.clevelandart.org/api/artworks/?cc0=1&type=Painting` | ~3400 px "print" JPEG |
| The Met (European Paintings) | Paintings | `collectionapi.metmuseum.org/public/collection/v1.1/search` | full original, downscaled to ≤4096 px |
| Art Institute of Chicago | Paintings | `api.artic.edu/api/v1/artworks/search` + IIIF | 1686 px (843 px fallback) |
| Rijksmuseum | Paintings | `data.rijksmuseum.nl/search/collection?type=painting&imageAvailable=true` + one Linked Art JSON-LD fetch per object | IIIF (`iiif.micr.io`), 3000 px wide |
| SMK, Copenhagen | Paintings | `api.smk.dk/api/v1/art/search/?filters=[public_domain:true],[object_names:maleri]` | IIIF (`iip.smk.dk`), fits 3000 px |
| National Gallery of Art, Washington | Paintings | bundled list of 2906 open-access paintings (`src/main/resources/nga_paintings.tsv`) + IIIF at `api.nga.gov/iiif` | fits 3000 px (1500 px fallback) |
| Masterpieces (curated) | Paintings | hard-coded list of ~35 famous works (van Gogh, Vermeer, Monet, Caravaggio...) resolved through the Met, Cleveland and AIC object endpoints | same as those sources |
| NASA Image Library (space) | Space | `images-api.nasa.gov/search?media_type=image&q=...` | `~orig` (typically 2000-8000 px), falls back to 1920 px `~large` |
| NASA Image Library (Earth) | Landscapes & nature | same, Earth/landscape queries | same |
| Wikimedia Commons featured pictures (nature) | Landscapes & nature | `commons.wikimedia.org/w/api.php` (`generator=categorymembers`, `imageinfo`) | 3000 px JPEG thumbnail |
| Wikimedia Commons featured pictures (astronomy) | Space | same | 3000 px |
| Wikimedia Commons featured pictures (cities) | Cities & architecture | same | 3000 px |

Notes:
- The Met retired `/v1/search` on 2026-10-01. This app uses the paginated `/v1.1/search`.
- AIC's IIIF image host is behind Cloudflare and challenged requests from a datacenter
  during development. It may work fine from a home network. If it fails, those works
  are skipped and the other sources carry on.
- NASA items whose credit line mentions a non-PD licence or copyright are skipped.
- Wikimedia is queried with a random `gcmstarthexsortkey` for variety; the licence
  (`extmetadata.LicenseShortName`) is checked per file. Shared/datacenter IPs can get
  HTTP 429 from Wikimedia; see "Rate limits" below.

- Rijksmuseum: the old key-based `/api/en/collection` now returns 410 Gone. The source uses the
  new keyless Linked Art service; only objects whose image carries the Public Domain Mark are used.
- NGA publishes no search API, only 80+ MB CSV dumps (github.com/NationalGalleryOfArt/opendata), so
  the list of open-access paintings (openaccess=1, classification=Painting, short side >= 1500 px)
  was extracted once into `nga_paintings.tsv`; regenerate it from `objects.csv` and
  `published_images.csv` to pick up new acquisitions. Images are fetched live over IIIF.
- SMK: works without an IIIF image id (thumbnail only) are skipped.
- Masterpieces: ids were checked against the live APIs; a work that disappears is skipped.

### Rate limits

Shared IPs (VPN exits such as Proton VPN, CGNAT) often get HTTP 429 from Wikimedia, AIC and
others. When a source answers 429 (or 503 with a `Retry-After` header), to its API or its image
host, it is left alone until the time `Retry-After` gives (seconds or an HTTP date, at most a day),
or else for 15 min, doubling on each further limit up to 24 h. The first successful batch
resets this. Settings → Diagnostics shows such a source as "Waiting · Rate limited, retrying
in 40 min". The logic is in `Backoff.kt`; the state is per source in SharedPreferences.

### Download limits

Images are downloaded at full quality. As a safety net, a download is aborted above 100 MB
(`Http.MAX_DOWNLOAD_BYTES`, far above the largest real image, NASA originals at about 40 MB)
or when the server says its `Content-Type` isn't `image/*`. Nothing partial is kept.

To add a source: add a class implementing `source/ArtSource.kt` and list it in `ArtSource.ALL`.

## Build

```bash
./gradlew :app:assembleDebug     # or open in Android Studio → Build APK
./gradlew :app:testDebugUnitTest # parser tests against saved API responses
```

CI (`.github/workflows/build.yml`) builds and lints every push and pull request.
Every push to `main` also refreshes the **Latest build** release, so the newest APK
is always at a public, no-login link:

https://github.com/phrag/ArtDream/releases/latest/download/fartsaver.apk

(Branch and PR builds are only available as run artifacts, which need a GitHub login.)
Builds are signed with the committed `app/debug.keystore`, so a new APK installs
over the old one without losing the downloaded paintings.

The app updates itself from that release (`Updater.kt`): it asks GitHub at most once a day
when the settings screen opens, offers a newer build, and checks the APK's size, sha256 and
signing key before handing it to Android's installer. Settings → Setup shows the installed
build and has "Automatic update checks" (on by default) and "Check for updates now".

## Install and enable

```bash
adb connect <tv-ip>:5555
adb install -r fartsaver.apk   # or app/build/outputs/apk/debug/app-debug.apk
```

**Formerly ArtDream:** the app was renamed to fArtSaver. The app id (`com.phrag.artdream`),
the screensaver service (`.ArtDream`) and the source package keep the old name, so existing
installs update in place and stay selected as the screensaver.

**Upgrading from a build before the package rename:** the app id changed from
`com.example.artdream` to `com.phrag.artdream`, so Android sees a new app. Uninstall the old
one once (`adb uninstall com.example.artdream`, or Settings → Apps), install the new APK, and
choose fArtSaver as the screensaver again (Google TV: rerun the `adb shell settings` command
below). Downloaded paintings and settings don't carry over.

Open **fArtSaver** from the home screen once and press **Download more paintings now**.
Then:

- **Shield / stock Android TV:** Settings → Device Preferences → Screen saver → fArtSaver.
- **Google TV** (only offers Ambient mode in the menu):
  ```bash
  adb shell settings put secure screensaver_components com.phrag.artdream/.ArtDream
  ```
  Some firmware updates reset this setting.

## Layout

```
app/src/main/java/com/phrag/artdream/
├── ArtDream.kt          # the DreamService (class keeps its old name, see below): slides, crossfade, Ken Burns, caption
├── ArtCache.kt          # on-disk playlist + background top-up / trim
├── Backoff.kt           # per-source rate-limit backoff (Retry-After, 15 min → 24 h)
├── RefreshJobService.kt # daily background top-up
├── Updater.kt           # self-update from the GitHub release
├── SettingsActivity.kt  # launcher entry and the screensaver's settings gear
├── Prefs.kt             # interval, captions, landscape-only, per-source toggles
├── Artwork.kt / Http.kt
└── source/              # one class per museum API
```

## TODO

- Optional clock overlay; "favourite / never show again" from the settings screen.
