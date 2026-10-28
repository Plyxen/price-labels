package hu.arcimke.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.json.JSONObject;

/**
 * Hosts the label editor (assets/index.html) in a WebView. The page builds the PDF, parses
 * imported product lists and reads barcodes itself; this class only connects it to Android's
 * print, share, Downloads, file-picker, camera and package-installer APIs.
 */
public class MainActivity extends Activity {
    static final String AUTHORITY = "hu.arcimke.app.files";
    static final String SHARE_DIR = "share";
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String DOWNLOAD_FOLDER = "Arcimke";
    private static final int REQ_PICK_FILE = 41;
    private static final int REQ_CAMERA = 42;
    private static final int REQ_PHOTO = 43;
    /** the camera app's photo of a barcode, in cache/share/ (the only file the provider lets another app write) */
    static final String PHOTO_NAME = "barcode-photo.jpg";
    private static final int PHOTO_MAX = 3000; // px on the long side handed to the page
    private static final int MAX_IMPORT_BYTES = 25 * 1024 * 1024;
    private static final int MAX_UPDATE_BYTES = 50 * 1024 * 1024;

    private WebView web;
    private float density;
    private int insetTop, insetBottom;
    private boolean keyboard;
    private ValueCallback<Uri[]> fileCallback;
    // the page's camera request, waiting for the user to answer Android's permission prompt
    private PermissionRequest pendingCamera;
    // a file opened with / shared to the app, waiting for the page to pick it up
    private volatile String pendingName;
    private volatile byte[] pendingData;
    // the camera app's photo, ready for the page to read the barcode from (base64 JPEG)
    private volatile String pendingPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? Color.rgb(11, 15, 23) : Color.rgb(243, 245, 248);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupSystemBars(night);

        // The page draws behind the status/navigation bars and pads itself with --inset-* values.
        // The keyboard is handled natively by shrinking the WebView so focused fields stay visible.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, ime;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getStableInsetBottom();
                int sys = insets.getSystemWindowInsetBottom();
                ime = sys > bottom ? sys : 0;
            }
            keyboard = ime > 0;
            v.setPadding(0, 0, 0, ime);
            insetTop = top;
            insetBottom = keyboard ? 0 : bottom;
            pushInsets();
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setTextZoom(100);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); // the scanner's camera preview
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        request.deny();
                    } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        if (pendingCamera != null) pendingCamera.deny();
                        pendingCamera = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }


            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                // any type: CSV/Excel files arrive with many different MIME types
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(pick, REQ_PICK_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets();
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        handleIncomingFile(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingFile(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        if (requestCode == REQ_PHOTO) {
            if (resultCode != RESULT_OK) {
                js("window.onPhoto&&window.onPhoto(false)");
                return;
            }
            new Thread(() -> {
                try {
                    pendingPhoto = photoForPage(new File(new File(getCacheDir(), SHARE_DIR), PHOTO_NAME));
                    js("window.onPhoto&&window.onPhoto(true)");
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    js("window.onPhoto&&window.onPhoto(false)");
                }
            }).start();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_CAMERA || pendingCamera == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            pendingCamera.deny();
        }
        pendingCamera = null;
    }

    /** The camera app's photo, at most PHOTO_MAX px on its long side, as base64 JPEG. */
    private static String photoForPage(File f) throws IOException {
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), size);
        if (size.outWidth <= 0) throw new IOException("no photo");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (Math.max(size.outWidth, size.outHeight) / (o.inSampleSize * 2) >= PHOTO_MAX) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("no photo");
        float k = Math.min(1f, (float) PHOTO_MAX / Math.max(b.getWidth(), b.getHeight()));
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 92, out);
        f.delete();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** Runs a line of JavaScript in the page (from any thread). */
    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /** "Open with" / "Share to" a product list file: read it and let the page import it. */
    @SuppressWarnings("deprecation")
    private void handleIncomingFile(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        final Uri source = uri;
        new Thread(() -> {
            try {
                String name = displayName(source);
                byte[] data = readAll(source);
                pendingName = name;
                pendingData = data;
                runOnUiThread(() -> web.evaluateJavascript("window.onAppFile&&window.onAppFile()", null));
            } catch (IOException e) {
                toast(e.getMessage());
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
            // fall back to the URI's last segment
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "import.csv";
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_IMPORT_BYTES) throw new IOException("File is too large");
            }
            return out.toByteArray();
        }
    }

    private void setupSystemBars(boolean night) {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                // the app bar and bottom bar follow the page theme, so the system bar icons do too
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void pushInsets() {
        if (web == null) return;
        String js = "(function(s){s.setProperty('--inset-top','" + (insetTop / density) + "px');"
                + "s.setProperty('--inset-bottom','" + (insetBottom / density) + "px');"
                + "document.documentElement.classList.toggle('kb'," + keyboard + ");})"
                + "(document.documentElement.style)";
        web.evaluateJavascript(js, null);
    }

    private void registerBackCallback() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    /** Lets the page close dialogs / switch tabs first; leaves the app only if it didn't. */
    private void handleBack() {
        web.evaluateJavascript("(window.onAndroidBack&&window.onAndroidBack())?1:0", result -> {
            if (!"1".equals(result)) moveTaskToBack(true);
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "labels.pdf" : s;
    }

    /** Writes a file to cache/share/, where ShareProvider can serve it to other apps. */
    private File writeShareFile(String base64, String fileName) throws IOException {
        File dir = new File(getCacheDir(), SHARE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File f : old) if (f.lastModified() < cutoff) f.delete();
        }
        File f = new File(dir, safeName(fileName));
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(Base64.decode(base64, Base64.DEFAULT));
        }
        return f;
    }

    private class Bridge {
        @JavascriptInterface
        public void printPdf(String base64, String fileName, int pages) {
            final File f;
            try {
                f = writeShareFile(base64, fileName);
            } catch (IOException e) {
                toast(e.getMessage());
                return;
            }
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();
                String jobName = getApplicationInfo().loadLabel(getPackageManager()).toString();
                pm.print(jobName, new PdfFileAdapter(f, f.getName(), pages), attrs);
            });
        }

        @JavascriptInterface
        public void shareFile(String base64, String fileName, String mime, String mode, String email, String subject) {
            final File f;
            try {
                f = writeShareFile(base64, fileName);
            } catch (IOException e) {
                toast(e.getMessage());
                return;
            }
            final Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
            final boolean emailOnly = "email".equals(mode);
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.setClipData(ClipData.newRawUri(f.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (emailOnly) {
                    if (email != null && !email.trim().isEmpty()) {
                        send.putExtra(Intent.EXTRA_EMAIL, new String[]{email.trim()});
                    }
                    // limits the chooser to email apps while still sending the attachment
                    send.setSelector(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")));
                }
                try {
                    startActivity(Intent.createChooser(send, null));
                } catch (ActivityNotFoundException e) {
                    send.setSelector(null);
                    startActivity(Intent.createChooser(send, null));
                }
            });
        }

        @JavascriptInterface
        public void shareText(String text, String subject) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                startActivity(Intent.createChooser(send, null));
            });
        }

        @JavascriptInterface
        public boolean hasPendingFile() {
            return pendingData != null;
        }

        @JavascriptInterface
        public String pendingFileName() {
            return pendingName;
        }

        @JavascriptInterface
        public String takePendingFile() {
            byte[] data = pendingData;
            pendingData = null;
            return data == null ? "" : Base64.encodeToString(data, Base64.NO_WRAP);
        }

        @JavascriptInterface
        public String savePdf(String base64, String fileName) {
            return saveFile(base64, fileName, "application/pdf");
        }

        /** Saves to Downloads/Arcimke (PDFs, data backups). Returns "ok:<path>" or "err:<message>". */
        @JavascriptInterface
        public String saveFile(String base64, String fileName, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String name = safeName(fileName);
                String folder = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER;
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "err:Could not create the file";
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) return "err:Could not open the file";
                    out.write(data);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                return "ok:" + folder + "/" + name;
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        /** "versionName|versionCode" of the installed app. */
        @JavascriptInterface
        public String appVersion() {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return info.versionName + "|" + info.getLongVersionCode();
            } catch (PackageManager.NameNotFoundException e) {
                return "|0";
            }
        }

        /**
         * Fetches the latest GitHub release (JSON) and hands it to window.onUpdateJson. Without internet,
         * or if GitHub can't be reached, nothing happens: the update check is simply skipped.
         */
        @JavascriptInterface
        public void checkUpdate(String apiUrl) {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(apiUrl).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "PriceLabels-Android");
                    if (c.getResponseCode() != 200) return;
                    String body;
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buf, 0, n);
                        body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }
                    js("window.onUpdateJson&&window.onUpdateJson(" + JSONObject.quote(body) + ")");
                } catch (Exception ignored) {
                    // offline or GitHub unreachable: skip the update check
                }
            }).start();
        }

        /** Whether Android lets this app open the installer (Settings → Install unknown apps). */
        @JavascriptInterface
        public boolean canInstall() {
            return getPackageManager().canRequestPackageInstalls();
        }

        @JavascriptInterface
        public void allowInstall() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()))));
        }

        /**
         * Opens the phone's own camera app for a photo of a barcode: full resolution and the camera app's focus,
         * for codes the live picture is too soft to read. The result comes back through window.onPhoto(ok).
         */
        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(() -> {
                try {
                    File dir = new File(getCacheDir(), SHARE_DIR);
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache");
                    File f = new File(dir, PHOTO_NAME);
                    if (f.exists() && !f.delete()) throw new IOException("old photo");
                    if (!f.createNewFile()) throw new IOException("no photo file"); // the provider only serves existing files
                    Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(PHOTO_NAME).build();
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                    cam.setClipData(ClipData.newRawUri("", uri));
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(cam, REQ_PHOTO);
                } catch (IOException | RuntimeException e) { // no camera app, or the camera not allowed
                    toast(e.getMessage());
                    js("window.onPhoto&&window.onPhoto(false)");
                }
            });
        }

        /** The photo taken with takePhoto(), as base64 JPEG ("" when there is none). Read once. */
        @JavascriptInterface
        public String takePhotoData() {
            String data = pendingPhoto;
            pendingPhoto = null;
            return data == null ? "" : data;
        }

        /** Full brightness and the screen kept on while a barcode is shown for the register's scanner. */
        @JavascriptInterface
        public void brightScreen(boolean on) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /**
         * Downloads the update APK into cache/share/ and opens Android's installer. The installer only accepts
         * an APK signed with the same key, and an update keeps the app's data (products, labels, settings).
         * Progress goes to window.onUpdateState('download', percent) / ('install') / ('error', message).
         */
        @JavascriptInterface
        public void installUpdate(String apkUrl) {
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), SHARE_DIR);
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                    File f = new File(dir, "update.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "PriceLabels-Android");
                    if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                    long total = c.getContentLengthLong(), done = 0;
                    int lastPct = -1;
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (done > MAX_UPDATE_BYTES) throw new IOException("Update file is too large");
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            if (pct != lastPct) {
                                lastPct = pct;
                                js("window.onUpdateState&&window.onUpdateState('download'," + pct + ")");
                            }
                        }
                    }
                    Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
                    js("window.onUpdateState&&window.onUpdateState('install')");
                    runOnUiThread(() -> {
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(uri, APK_MIME);
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(install);
                    });
                } catch (Exception e) {
                    js("window.onUpdateState&&window.onUpdateState('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }
    }

    /** Feeds an already-rendered PDF file to the Android print framework. */
    private static class PdfFileAdapter extends PrintDocumentAdapter {
        private final File file;
        private final String name;
        private final int pages;

        PdfFileAdapter(File file, String name, int pages) {
            this.file = file;
            this.name = name;
            this.pages = pages;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                             CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pages > 0 ? pages : PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build();
            callback.onLayoutFinished(info, oldAttributes == null || !oldAttributes.equals(newAttributes));
        }

        @Override
        public void onWrite(PageRange[] pageRanges, ParcelFileDescriptor destination,
                            CancellationSignal cancellationSignal, WriteResultCallback callback) {
            try (InputStream in = new FileInputStream(file)) {
                // the framework owns the destination descriptor, so only flush it
                OutputStream out = new FileOutputStream(destination.getFileDescriptor());
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (IOException e) {
                callback.onWriteFailed(e.getMessage());
            }
        }
    }
}
