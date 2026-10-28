# Price Labels

A small Android app for shops: print 3×7 cm shelf price labels on A4, keep a product list with stock levels, and build the next order. English and Hungarian.

The app ships empty. Your products come from your own CSV/XLSX export (or are added by hand) and stay on the phone.

## Features

- **Labels** – pick products or type labels in, preview the A4 sheet, then print it, e-mail it, share it or save it as a PDF. Cut lines, price format, layout and DRS deposit text are configurable.
- **Products** – search, category chips, stock and minimum level per product, one-tap "add to labels".
- **Ordering** – everything at or below its minimum level, grouped by category, shareable as text. Product groups can be excluded from ordering.
- **Import** – CSV or XLSX exports from a stock-keeping program. Two modes:
  - *Daily stock* updates prices and stock.
  - *Fresh delivery* also records the delivered amount as each product's full-stock level.
- **Barcode scanner** – point the camera at an EAN-13/EAN-8/UPC code to see the price and stock, add the product to the label sheet, change its stock, or link an unknown code to a product.
- **Backup / restore** – the whole database as one JSON file in `Downloads/Arcimke`.
- **Updates from GitHub** – see below.

Requires Android 10 or newer.

## Updates

When the phone is online, the app checks this repository's [latest release](../../releases/latest) (at most every 6 hours, or on demand in *More → App*). With no internet the check is skipped. If a newer version exists, the app offers to download and install it. The first time, Android asks you to allow "Install unknown apps" for Price Labels.

Updates keep all data. The products, label sheet and settings live in the app's own storage, which Android keeps when an APK signed with the same key installs over the old one. The app also saves a backup file before each update.

## Building

No Gradle needed. You need the Android SDK (build-tools 36.1.0, platform android-36), JDK 17 and Python, and a bash shell (Git Bash works on Windows).

```bash
bash build.sh
```

This writes `Price-Labels.apk`.

- **Signing key.** The first build creates `keystore/arcimke.jks` with a random password, stored in `keystore/password.txt`. You can also supply the password in the `ARCIMKE_KEYPASS` environment variable.
- **Keep `keystore/` safe and out of git.** Android only installs an update over an existing app if both are signed with the same key.

### Releasing an update

1. Bump `VERSION_CODE` and `VERSION_NAME` in `build.sh`.
2. Run `bash build.sh`.
3. Publish the APK as a GitHub release whose tag is `v<VERSION_NAME>`:

```bash
gh release create v1.7 Price-Labels.apk --title "Price Labels 1.7"
```

Installed apps pick it up on their next check.

## Layout

| Path | What it is |
| --- | --- |
| `app/assets/index.html` | The whole UI and logic: a single page running in a WebView. |
| `app/src/.../MainActivity.java` | WebView host. Provides the bridge for printing, files, sharing, camera permission and updates. |
| `app/src/.../ShareProvider.java` | Shares exported files with other apps. |
| `app/res/` | Icon, name and theme. |
| `build.sh` | The build. |
