package net.wastu.solipsist;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Cleans app-owned camera output before the caller receives its activity result. */
final class CameraCaptureHook {
    private static final long MAX_AGE_MS = 30 * 60 * 1000L;
    private static final int MAX_CAPTURED = 64;
    private static final Object LOCK = new Object();
    private static final WeakHashMap<Activity, Map<Integer, Uri>> PENDING = new WeakHashMap<>();
    private static final LinkedHashMap<String, Capture> CAPTURED = new LinkedHashMap<>();
    private static final HashMap<String, Capture> PATHS = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile boolean hasCaptured;

    private CameraCaptureHook() {}

    static void install() {
        try {
            if (XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!RuntimeState.isEnabled() || param.args.length < 2
                            || !(param.args[0] instanceof Intent) || !(param.args[1] instanceof Integer)) return;
                    Intent intent = (Intent) param.args[0];
                    String action = intent.getAction();
                    if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                            && !MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action)) return;
                    Uri output = outputUri(intent);
                    if (output == null) return;
                    synchronized (LOCK) {
                        Map<Integer, Uri> requests = PENDING.get((Activity) param.thisObject);
                        if (requests == null) {
                            requests = new HashMap<>();
                            PENDING.put((Activity) param.thisObject, requests);
                        }
                        requests.put((Integer) param.args[1], output);
                    }
                }
            }).isEmpty()) throw new NoSuchMethodException("startActivityForResult");
            RuntimeState.reportInstalled("Media.cameraCapture.start");
        } catch (Throwable t) {
            RuntimeState.reportHook("Media.cameraCapture.start", t);
        }

        try {
            if (XposedBridge.hookAllMethods(Activity.class, "dispatchActivityResult", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 3 || !(param.args[1] instanceof Integer)
                            || !(param.args[2] instanceof Integer)) return;
                    Uri output;
                    synchronized (LOCK) {
                        Map<Integer, Uri> requests = PENDING.get((Activity) param.thisObject);
                        output = requests == null ? null : requests.remove((Integer) param.args[1]);
                        if (requests != null && requests.isEmpty()) PENDING.remove((Activity) param.thisObject);
                    }
                    if (output == null || (Integer) param.args[2] != Activity.RESULT_OK
                            || !RuntimeState.isEnabled()) return;
                    try {
                        File file = appOwnedOutput((Activity) param.thisObject, output);
                        if (file == null) return;
                        if (!stripJpegMetadata(file)) {
                            RuntimeState.reportHook("Media.cameraCapture.format",
                                    new UnsupportedOperationException("jpeg_required"));
                            return;
                        }
                        remember(output, file);
                        RuntimeState.count("media_redaction");
                    } catch (Throwable t) {
                        RuntimeState.reportHook("Media.cameraCapture.clean", t);
                    }
                }
            }).isEmpty()) throw new NoSuchMethodException("dispatchActivityResult");
            RuntimeState.reportInstalled("Media.cameraCapture.result");
        } catch (Throwable t) {
            RuntimeState.reportHook("Media.cameraCapture.result", t);
        }

        // Apps can derive upload names from either OpenableColumns or the captured URI/file.
        try {
            if (XposedBridge.hookAllMethods(File.class, "getName", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!RuntimeState.isEnabled() || !hasCaptured) return;
                    Capture capture;
                    synchronized (LOCK) { capture = PATHS.get(((File) param.thisObject).getPath()); }
                    if (capture != null && !expired(capture)) param.setResult(capture.name);
                }
            }).isEmpty()) throw new NoSuchMethodException("File.getName");
            RuntimeState.reportInstalled("Media.cameraCapture.fileName");
        } catch (Throwable t) {
            RuntimeState.reportHook("Media.cameraCapture.fileName", t);
        }

        try {
            Class<?> hierarchicalUri = XposedHelpers.findClass(
                    "android.net.Uri$AbstractHierarchicalUri", null);
            if (XposedBridge.hookAllMethods(hierarchicalUri, "getLastPathSegment", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    String name = capturedName((Uri) param.thisObject);
                    if (name != null) param.setResult(name);
                }
            }).isEmpty()) throw new NoSuchMethodException("Uri.getLastPathSegment");
            RuntimeState.reportInstalled("Media.cameraCapture.uriName");
        } catch (Throwable t) {
            RuntimeState.reportHook("Media.cameraCapture.uriName", t);
        }
    }

    static boolean isCapturedUri(Uri uri) { return capturedName(uri) != null; }

    private static String capturedName(Uri uri) {
        if (!RuntimeState.isEnabled() || !hasCaptured || uri == null) return null;
        Capture capture;
        synchronized (LOCK) { capture = CAPTURED.get(uri.toString()); }
        return capture != null && !expired(capture) ? capture.name : null;
    }

    private static Uri outputUri(Intent intent) {
        try {
            Uri output = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class)
                    : intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
            if (output != null) return output;
            ClipData clip = intent.getClipData();
            return clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getUri() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File appOwnedOutput(Context context, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        File file;
        if ("file".equals(scheme)) {
            file = new File(uri.getPath());
        } else if ("content".equals(scheme)) {
            ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            if (provider == null || !context.getPackageName().equals(provider.packageName)) return null;
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null) return null;
                String path = Os.readlink("/proc/self/fd/" + descriptor.getFd());
                if (path.endsWith(" (deleted)")) return null;
                file = new File(path);
            } catch (Exception e) {
                throw new IOException("camera_output_unreadable", e);
            }
        } else {
            return null;
        }
        file = file.getCanonicalFile();
        if (!file.isFile() || !file.canRead() || !file.canWrite()) return null;
        File[] roots = new File[] {
                context.getDataDir(), context.createDeviceProtectedStorageContext().getDataDir(),
                context.getExternalFilesDir(null), context.getExternalCacheDir()
        };
        for (File root : roots) {
            if (root != null && within(file, root)) return file;
        }
        for (File root : context.getExternalMediaDirs()) {
            if (root != null && within(file, root)) return file;
        }
        return null;
    }

    private static boolean within(File file, File root) throws IOException {
        String path = file.getCanonicalPath();
        String base = root.getCanonicalPath();
        return path.startsWith(base + File.separator);
    }

    private static void remember(Uri uri, File file) {
        String id = Long.toUnsignedString(RANDOM.nextLong(), 36);
        Capture capture = new Capture("image_" + id + ".jpg", System.currentTimeMillis());
        synchronized (LOCK) {
            CAPTURED.put(uri.toString(), capture);
            PATHS.put(file.getPath(), capture);
            hasCaptured = true;
            Iterator<Map.Entry<String, Capture>> iterator = CAPTURED.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Capture> entry = iterator.next();
                if (CAPTURED.size() <= MAX_CAPTURED && !expired(entry.getValue())) break;
                PATHS.values().remove(entry.getValue());
                iterator.remove();
            }
        }
    }

    private static boolean expired(Capture capture) {
        return System.currentTimeMillis() - capture.time > MAX_AGE_MS;
    }

    private static boolean stripJpegMetadata(File source) throws IOException {
        try (InputStream probe = new FileInputStream(source)) {
            if (probe.read() != 0xff || probe.read() != 0xd8) return false;
        }
        int orientation = new ExifInterface(source.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        File cleaned = File.createTempFile(".solipsist-", ".jpg", source.getParentFile());
        try {
            try (PushbackInputStream input = new PushbackInputStream(
                    new BufferedInputStream(new FileInputStream(source)), 2);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(cleaned))) {
                rewriteJpeg(input, output, orientation);
            }
            try {
                Files.move(cleaned.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(cleaned.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            if (cleaned.exists()) cleaned.delete();
        }
    }

    private static void rewriteJpeg(PushbackInputStream input, OutputStream output,
                                    int orientation) throws IOException {
        if (input.read() != 0xff || input.read() != 0xd8) throw new IOException("invalid_jpeg");
        output.write(0xff);
        output.write(0xd8);
        if (orientation >= 2 && orientation <= 8) writeOrientation(output, orientation);
        for (;;) {
            if (input.read() != 0xff) throw new IOException("invalid_jpeg_marker");
            int marker;
            do { marker = input.read(); } while (marker == 0xff);
            if (marker < 0 || marker == 0x00) throw new IOException("invalid_jpeg_marker");
            if (marker == 0xd9) {
                output.write(0xff);
                output.write(marker);
                return; // Drop any trailing camera metadata after EOI.
            }
            if (marker == 0xd8 || marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) {
                output.write(0xff);
                output.write(marker);
                continue;
            }
            int high = input.read();
            int low = input.read();
            int length = high << 8 | low;
            if (high < 0 || low < 0 || length < 2) throw new IOException("invalid_jpeg_length");
            boolean metadata = marker >= 0xe0 && marker <= 0xef || marker == 0xfe;
            if (metadata) {
                skipFully(input, length - 2);
            } else {
                output.write(0xff);
                output.write(marker);
                output.write(high);
                output.write(low);
                copyFully(input, output, length - 2);
            }
            if (marker == 0xda) copyEntropy(input, output);
        }
    }

    private static void copyEntropy(PushbackInputStream input, OutputStream output)
            throws IOException {
        int value;
        while ((value = input.read()) >= 0) {
            if (value != 0xff) {
                output.write(value);
                continue;
            }
            int next = input.read();
            if (next < 0) throw new IOException("truncated_jpeg");
            if (next == 0xff) {
                output.write(0xff); // Marker fill byte; parse the following marker normally.
                input.unread(next);
                return;
            }
            if (next == 0x00 || next >= 0xd0 && next <= 0xd7) {
                output.write(0xff);
                output.write(next);
            } else {
                input.unread(next);
                input.unread(0xff);
                return;
            }
        }
        throw new IOException("truncated_jpeg");
    }

    private static void copyFully(InputStream input, OutputStream output, int count)
            throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int read = input.read(buffer, 0, Math.min(count, buffer.length));
            if (read < 0) throw new IOException("truncated_jpeg");
            output.write(buffer, 0, read);
            count -= read;
        }
    }

    private static void skipFully(InputStream input, int count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int read = input.read(buffer, 0, Math.min(count, buffer.length));
            if (read < 0) throw new IOException("truncated_jpeg");
            count -= read;
        }
    }

    private static void writeOrientation(OutputStream output, int orientation) throws IOException {
        byte[] tag = {
                (byte) 0xff, (byte) 0xe1, 0, 0x22,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2a, 0, 0, 0, 8,
                0, 1, 1, 0x12, 0, 3, 0, 0, 0, 1,
                0, (byte) orientation, 0, 0, 0, 0, 0, 0
        };
        output.write(tag);
    }

    private static final class Capture {
        final String name;
        final long time;
        Capture(String name, long time) { this.name = name; this.time = time; }
    }
}
