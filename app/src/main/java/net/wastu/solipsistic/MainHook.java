package net.wastu.solipsistic;

import android.content.Context;
import android.database.Cursor;
import android.database.CrossProcessCursorWrapper;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String[] EXTRA_SENSITIVE_EXIF_TAGS = new String[] {
        "Make",
        "Model",
        "Software",
        "BodySerialNumber",
        "CameraOwnerName",
        "DeviceSettingDescription",
        "ImageUniqueID",
        "LensMake",
        "LensModel",
        "LensSerialNumber",
        "LensSpecification",
        "OwnerName",
        "SpectralSensitivity",
        "UserComment",
        "Artist",
        "Copyright"
    };

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<String>(Arrays.asList(
        "mp4", "mkv", "mov", "3gp", "3gpp", "3g2", "webm", "avi", "flv", "ts", "m4v", "wmv"
    ));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<String>(Arrays.asList(
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma", "mid"
    ));

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if ("net.wastu.solipsistic".equals(lpparam.packageName)) {
            return;
        }

        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            SystemServerPrivacyShield.init(lpparam);
            return;
        }

        if ("com.android.providers.settings".equals(lpparam.packageName)) {
            SystemServerPrivacyShield.hookSettingsProvider(lpparam);
            return;
        }

        if ("com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        if ("com.android.providers.media.module".equals(lpparam.packageName)
                || "com.android.providers.media".equals(lpparam.packageName)
                || "com.google.android.providers.media.module".equals(lpparam.packageName)) {
            XposedBridge.log("[Solipsistic] Hooking MediaProvider in " + lpparam.packageName);

            // A. Generic Filenames for Photo Picker & MediaProvider queries
            try {
                XC_MethodHook wrapCursorHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Cursor cursor = (Cursor) param.getResult();
                        if (cursor == null) return;

                        int callingUid = Binder.getCallingUid();
                        if (param.args.length >= 4 && param.args[3] instanceof Integer) {
                            callingUid = ((Integer) param.args[3]).intValue();
                        }
                        if (callingUid <= 10000 && callingUid != 0) return;

                        String callingPkg = null;
                        try {
                            callingPkg = (String) XposedHelpers.callMethod(param.thisObject, "getCallingPackage");
                        } catch (Throwable ignored) {}

                        if (callingPkg == null && param.args.length >= 5 && param.args[4] instanceof String) {
                            callingPkg = (String) param.args[4];
                        }

                        if (callingPkg != null) {
                            if ("com.android.photopicker".equals(callingPkg)
                                    || "com.google.android.photopicker".equals(callingPkg)
                                    || "com.android.providers.media.module".equals(callingPkg)
                                    || "com.miui.gallery".equals(callingPkg)
                                    || "com.google.android.apps.photos".equals(callingPkg)
                                    || callingPkg.contains("gallery")) {
                                return;
                            }
                        }

                        Uri uri = (Uri) param.args[0];
                        param.setResult(wrapPickerCursor(cursor, uri));
                    }
                };

                try {
                    XposedHelpers.findAndHookMethod(
                        "com.android.providers.media.PickerUriResolver",
                        lpparam.classLoader,
                        "query",
                        Uri.class,
                        String[].class,
                        int.class,
                        int.class,
                        String.class,
                        wrapCursorHook
                    );
                    XposedBridge.log("[Solipsistic] Successfully hooked PickerUriResolver.query for generic names");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsistic] Error hooking PickerUriResolver.query: " + t.getMessage());
                }

                try {
                    XposedHelpers.findAndHookMethod(
                        "com.android.providers.media.MediaProvider",
                        lpparam.classLoader,
                        "query",
                        Uri.class,
                        String[].class,
                        Bundle.class,
                        CancellationSignal.class,
                        wrapCursorHook
                    );
                    XposedBridge.log("[Solipsistic] Successfully hooked MediaProvider.query(Uri, String[], Bundle, CancellationSignal)");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsistic] Error hooking MediaProvider.query(Bundle): " + t.getMessage());
                }

                try {
                    XposedHelpers.findAndHookMethod(
                        "com.android.providers.media.MediaProvider",
                        lpparam.classLoader,
                        "query",
                        Uri.class,
                        String[].class,
                        String.class,
                        String[].class,
                        String.class,
                        wrapCursorHook
                    );
                    XposedBridge.log("[Solipsistic] Successfully hooked MediaProvider.query(5 args)");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsistic] Error hooking MediaProvider.query(5 args): " + t.getMessage());
                }
            } catch (Throwable t) {
                XposedBridge.log("[Solipsistic] Error setting up generic filename query hooks: " + t.getMessage());
            }

            // B. Force Redaction for Third-Party Apps
            try {
                Class<?> pendingOpenInfoClass = XposedHelpers.findClass(
                    "com.android.providers.media.MediaProvider$PendingOpenInfo",
                    lpparam.classLoader
                );
                XposedBridge.hookAllConstructors(pendingOpenInfoClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int uid = (Integer) param.args[0];
                        if (uid > 10000 && uid != android.os.Process.myUid()) {
                            param.args[2] = true; // force shouldRedact = true
                        }
                    }
                });
                XposedBridge.log("[Solipsistic] Successfully hooked MediaProvider$PendingOpenInfo constructor for forced redaction");
            } catch (Throwable t) {
                XposedBridge.log("[Solipsistic] Error hooking PendingOpenInfo constructor: " + t.getMessage());
            }

            // C. Sensitive EXIF & Video/Audio Metadata Scrubbing
            try {
                Class<?> redactionUtilsClass = XposedHelpers.findClass(
                    "com.android.providers.media.util.RedactionUtils",
                    lpparam.classLoader
                );
                XC_MethodHook redactionHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        FileInputStream fis = null;
                        String mimeType = null;
                        if (param.args.length == 2 && param.args[0] instanceof FileInputStream) {
                            fis = (FileInputStream) param.args[0];
                            mimeType = (String) param.args[1];
                        } else if (param.args.length == 1 && param.args[0] instanceof java.io.File) {
                            java.io.File f = (java.io.File) param.args[0];
                            if (f != null && f.exists()) {
                                try {
                                    fis = new FileInputStream(f);
                                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        MimeTypeMap.getFileExtensionFromUrl(f.getAbsolutePath())
                                    );
                                } catch (Throwable ignored) {}
                            }
                        }
                        if (fis == null || mimeType == null) return;

                        long[] origRanges = (long[]) param.getResult();
                        long[] extraRanges = null;

                        if (ExifInterface.isSupportedMimeType(mimeType)) {
                            extraRanges = getExtraSensitiveExifRanges(fis, mimeType);
                        } else if (isVideoOrAudioMime(mimeType)) {
                            extraRanges = getVideoSensitiveRanges(fis, mimeType, origRanges);
                        }

                        if (extraRanges != null && extraRanges.length > 0) {
                            long[] combined;
                            if (origRanges == null || origRanges.length == 0) {
                                combined = extraRanges;
                            } else {
                                combined = new long[origRanges.length + extraRanges.length];
                                System.arraycopy(origRanges, 0, combined, 0, origRanges.length);
                                System.arraycopy(extraRanges, 0, combined, origRanges.length, extraRanges.length);
                            }
                            param.setResult(normalizeRanges(combined));
                        }
                    }
                };

                for (java.lang.reflect.Method m : redactionUtilsClass.getDeclaredMethods()) {
                    if ("getRedactionRanges".equals(m.getName())) {
                        XposedBridge.hookMethod(m, redactionHook);
                        XposedBridge.log("[Solipsistic] Successfully hooked RedactionUtils." + m.getName() + " with " + m.getParameterTypes().length + " params");
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[Solipsistic] Error hooking RedactionUtils.getRedactionRanges: " + t.getMessage());
            }
            return;
        }

        if ("com.android.photopicker".equals(lpparam.packageName)) {
            return;
        }

        // Apply Privacy Shield (USB debugging, developer options, and accessibility hiding) to target apps
        PrivacyShieldHook.init(lpparam);
    }

    private static Cursor wrapPickerCursor(final Cursor cursor, final Uri uri) {
        return new CrossProcessCursorWrapper(cursor) {
            private final int mDisplayNameCol = cursor.getColumnIndex("_display_name");
            private final int mTitleCol = cursor.getColumnIndex("title");
            private final int mDataCol = cursor.getColumnIndex("_data");

            @Override
            public CursorWindow getWindow() {
                return null;
            }

            @Override
            public void fillWindow(int position, CursorWindow window) {
                try {
                    XposedHelpers.callStaticMethod(DatabaseUtils.class, "cursorFillWindow", this, position, window);
                } catch (Throwable t) {
                    super.fillWindow(position, window);
                }
            }

            @Override
            public String getString(int columnIndex) {
                if (columnIndex >= 0) {
                    if (columnIndex == mDisplayNameCol) {
                        String origName = null;
                        try {
                            origName = super.getString(mDisplayNameCol);
                        } catch (Throwable ignored) {}
                        String genericName = computeGenericName(this, uri, origName, true);
                        if (getCount() <= 1) {
                            notifyFilenameAnonymized(genericName);
                        }
                        return genericName;
                    } else if (columnIndex == mTitleCol) {
                        return computeGenericName(this, uri, null, false);
                    } else if (columnIndex == mDataCol) {
                        String origData = null;
                        try {
                            origData = super.getString(columnIndex);
                        } catch (Throwable ignored) {}
                        if (origData != null) {
                            int lastSlash = origData.lastIndexOf('/');
                            if (lastSlash >= 0) {
                                String origName = origData.substring(lastSlash + 1);
                                String genericName = computeGenericName(this, uri, origName, true);
                                return origData.substring(0, lastSlash + 1) + genericName;
                            }
                        }
                    }
                }
                return super.getString(columnIndex);
            }
        };
    }

    private static boolean isVideoOrAudioMime(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase();
        return lower.startsWith("video/") || lower.startsWith("audio/")
            || "application/ogg".equals(lower) || "application/x-flac".equals(lower);
    }

    private static String computeGenericName(Cursor cursor, Uri uri, String origName, boolean withExtension) {
        String id = null;
        try {
            int idIdx = cursor.getColumnIndex("_id");
            if (idIdx >= 0) {
                id = cursor.getString(idIdx);
            }
        } catch (Throwable ignored) {}

        if (id == null || id.isEmpty()) {
            if (uri != null) {
                id = uri.getLastPathSegment();
            }
        }
        if (id == null || id.isEmpty()) {
            try {
                id = String.valueOf(cursor.getPosition());
            } catch (Throwable ignored) {
                id = "1";
            }
        }

        String mime = null;
        try {
            int mimeIdx = cursor.getColumnIndex("mime_type");
            if (mimeIdx >= 0) {
                mime = cursor.getString(mimeIdx);
            }
        } catch (Throwable ignored) {}

        String ext = null;
        if (origName != null) {
            int dot = origName.lastIndexOf('.');
            if (dot >= 0 && dot < origName.length() - 1) {
                ext = origName.substring(dot + 1).toLowerCase();
            }
        }

        String prefix = "image";
        if (mime != null) {
            if (mime.startsWith("video/")) {
                prefix = "video";
            } else if (mime.startsWith("audio/")) {
                prefix = "audio";
            }
        } else {
            if (uri != null) {
                String uriStr = uri.toString().toLowerCase();
                if (uriStr.contains("video")) {
                    prefix = "video";
                } else if (uriStr.contains("audio")) {
                    prefix = "audio";
                }
            }
            if ("image".equals(prefix) && ext != null) {
                if (VIDEO_EXTENSIONS.contains(ext)) {
                    prefix = "video";
                } else if (AUDIO_EXTENSIONS.contains(ext)) {
                    prefix = "audio";
                }
            }
        }

        if (!withExtension) {
            return prefix + "_" + id;
        }

        if (ext == null || ext.isEmpty()) {
            if (mime != null) {
                String fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (fromMime != null && !fromMime.isEmpty()) {
                    ext = fromMime.toLowerCase();
                }
            }
        }

        if (ext == null || ext.isEmpty()) {
            if ("video".equals(prefix)) {
                ext = "mp4";
            } else if ("audio".equals(prefix)) {
                ext = "mp3";
            } else {
                ext = "jpg";
            }
        }

        return prefix + "_" + id + "." + ext;
    }

    private static long[] getExtraSensitiveExifRanges(FileInputStream fis, String mimeType) {
        if (mimeType == null || !ExifInterface.isSupportedMimeType(mimeType)) {
            return null;
        }
        try {
            FileDescriptor fd = fis.getFD();
            if (fd == null || !fd.valid()) return null;

            ExifInterface ex = new ExifInterface(fd);
            List<Long> ranges = new ArrayList<Long>();
            List<String> redactedNames = new ArrayList<String>();

            // Check if GPS tags were present (these are redacted by AOSP RedactionUtils)
            if (ex.getAttribute("GPSLatitude") != null || ex.getAttribute("GPSLongitude") != null || ex.getAttribute("GPSAltitude") != null) {
                redactedNames.add("GPS");
            }

            for (String tag : EXTRA_SENSITIVE_EXIF_TAGS) {
                long[] r = ex.getAttributeRange(tag);
                if (r != null && r.length == 2 && r[1] > 0) {
                    ranges.add(r[0]);
                    ranges.add(r[0] + r[1]);
                    redactedNames.add(tag);
                }
            }

            notifyExifChecked(redactedNames, "image");

            if (ranges.isEmpty()) {
                return null;
            }
            long[] result = new long[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                result[i] = ranges.get(i);
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[Solipsistic] Error extracting extra sensitive EXIF ranges: " + t.getMessage());
            return null;
        }
    }

    private static long[] getVideoSensitiveRanges(FileInputStream fis, String mimeType, long[] origRanges) {
        if (fis == null) return null;

        List<Long> extraRanges = new ArrayList<Long>();
        Set<String> redactedNames = new LinkedHashSet<String>();

        // If AOSP RedactionUtils already identified GPS boxes (loci, ©xyz, gps )
        if (origRanges != null && origRanges.length > 0) {
            redactedNames.add("GPS");
        }

        FileChannel channel = null;
        long origPos = -1;
        try {
            channel = fis.getChannel();
            origPos = channel.position();
            long fileSize = channel.size();
            scanIsoBoxes(channel, 0, fileSize, extraRanges, redactedNames, 0);
        } catch (Throwable t) {
            XposedBridge.log("[Solipsistic] Error scanning video boxes: " + t.getMessage());
        } finally {
            if (channel != null && origPos >= 0) {
                try {
                    channel.position(origPos);
                } catch (Throwable ignored) {}
            }
        }

        String mediaKind = (mimeType != null && mimeType.startsWith("audio/")) ? "audio" : "video";
        notifyExifChecked(new ArrayList<String>(redactedNames), mediaKind);

        if (extraRanges.isEmpty()) {
            return null;
        }
        long[] result = new long[extraRanges.size()];
        for (int i = 0; i < extraRanges.size(); i++) {
            result[i] = extraRanges.get(i);
        }
        return result;
    }

    private static void scanIsoBoxes(FileChannel channel, long start, long end, List<Long> ranges, Set<String> tags, int depth) throws IOException {
        if (depth > 10) return;
        long pos = start;
        ByteBuffer buf = ByteBuffer.allocate(16);

        while (pos + 8 <= end) {
            channel.position(pos);
            buf.clear().limit(8);
            if (channel.read(buf) < 8) break;
            buf.flip();

            long size = buf.getInt() & 0xFFFFFFFFL;
            int typeInt = buf.getInt();
            long headerSize = 8;

            if (size == 1) {
                if (pos + 16 > end) break;
                buf.clear().limit(8);
                if (channel.read(buf) < 8) break;
                buf.flip();
                size = buf.getLong();
                headerSize = 16;
            } else if (size == 0) {
                size = end - pos;
            }

            if (size < headerSize) break;
            long boxEnd = Math.min(pos + size, end);

            // Container boxes: 'moov' (0x6D6F6F76), 'trak' (0x7472616B), 'mdia' (0x6D646961), 'minf' (0x6D696E66)
            if (typeInt == 0x6D6F6F76 || typeInt == 0x7472616B || typeInt == 0x6D646961 || typeInt == 0x6D696E66) {
                scanIsoBoxes(channel, pos + headerSize, boxEnd, ranges, tags, depth + 1);
            } else if (typeInt == 0x6D766864 || typeInt == 0x746B6864 || typeInt == 0x6D646864) {
                // Header boxes: 'mvhd', 'tkhd', 'mdhd'
                channel.position(pos + headerSize);
                buf.clear().limit(1);
                if (channel.read(buf) == 1) {
                    buf.flip();
                    int version = buf.get() & 0xFF;
                    long dateLen = (version == 1) ? 16 : 8;
                    long timeStart = pos + headerSize + 4; // 1 byte version + 3 bytes flags
                    long timeEnd = timeStart + dateLen;
                    if (timeEnd <= boxEnd) {
                        ranges.add(timeStart);
                        ranges.add(timeEnd);
                        tags.add("DateTime");
                    }
                }
            } else if (typeInt == 0x75647461) {
                // 'udta' (0x75647461) - User Data box: camera model, make, Android version, etc.
                if (boxEnd > pos + 4) {
                    ranges.add(pos + 4);
                    ranges.add(boxEnd);
                    tags.add("Device");
                }
            }

            pos += size;
        }
    }

    private static long[] normalizeRanges(long[] ranges) {
        if (ranges == null || ranges.length < 2) return ranges;
        int count = ranges.length / 2;
        long[][] pairs = new long[count][2];
        for (int i = 0; i < count; i++) {
            pairs[i][0] = ranges[i * 2];
            pairs[i][1] = ranges[i * 2 + 1];
        }
        Arrays.sort(pairs, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        List<Long> merged = new ArrayList<Long>();
        long curStart = pairs[0][0];
        long curEnd = pairs[0][1];
        for (int i = 1; i < count; i++) {
            if (pairs[i][0] <= curEnd) {
                curEnd = Math.max(curEnd, pairs[i][1]);
            } else {
                merged.add(curStart);
                merged.add(curEnd);
                curStart = pairs[i][0];
                curEnd = pairs[i][1];
            }
        }
        merged.add(curStart);
        merged.add(curEnd);

        long[] result = new long[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            result[i] = merged.get(i);
        }
        return result;
    }

    private static final Object sMediaPrivacyLock = new Object();
    private static String sPendingFilename = null;
    private static List<String> sPendingExifTags = null;
    private static String sPendingMediaKind = null;
    private static boolean sHasExifCheck = false;
    private static Runnable sMediaPrivacyRunnable = null;

    private static void notifyFilenameAnonymized(final String genericName) {
        synchronized (sMediaPrivacyLock) {
            sPendingFilename = genericName;
            scheduleMediaPrivacyToastLocked();
        }
    }

    private static void notifyExifChecked(final List<String> redactedTags, final String mediaKind) {
        synchronized (sMediaPrivacyLock) {
            sPendingExifTags = redactedTags != null ? new ArrayList<String>(redactedTags) : null;
            sPendingMediaKind = mediaKind;
            sHasExifCheck = true;
            scheduleMediaPrivacyToastLocked();
        }
    }

    private static void scheduleMediaPrivacyToastLocked() {
        if (sMediaPrivacyRunnable != null) {
            return;
        }
        sMediaPrivacyRunnable = new Runnable() {
            @Override
            public void run() {
                String filename;
                List<String> tags;
                String mediaKind;
                boolean hadExif;
                synchronized (sMediaPrivacyLock) {
                    filename = sPendingFilename;
                    tags = sPendingExifTags;
                    mediaKind = sPendingMediaKind;
                    hadExif = sHasExifCheck;
                    sPendingFilename = null;
                    sPendingExifTags = null;
                    sPendingMediaKind = null;
                    sHasExifCheck = false;
                    sMediaPrivacyRunnable = null;
                }

                StringBuilder sb = new StringBuilder();
                if (filename != null) {
                    sb.append("🔒 ").append(filename);
                }
                if (hadExif) {
                    if (sb.length() > 0) sb.append("\n");
                    boolean isVideo = "video".equals(mediaKind) || (filename != null && (filename.startsWith("video_")
                        || filename.endsWith(".mp4") || filename.endsWith(".mov")
                        || filename.endsWith(".mkv") || filename.endsWith(".3gp")
                        || filename.endsWith(".webm")));
                    boolean isAudio = "audio".equals(mediaKind) || (filename != null && (filename.startsWith("audio_")
                        || filename.endsWith(".mp3") || filename.endsWith(".m4a")
                        || filename.endsWith(".aac") || filename.endsWith(".wav")
                        || filename.endsWith(".flac") || filename.endsWith(".opus")));

                    String typeLabel = (isVideo || isAudio) ? "metadata" : "EXIF";
                    String cleanLabel = isVideo ? "🛡️ Video metadata clean" :
                                       (isAudio ? "🛡️ Audio metadata clean" : "🛡️ EXIF clean");

                    if (tags != null && !tags.isEmpty()) {
                        sb.append("🛡️ Redacted ").append(tags.size()).append(" ")
                          .append(typeLabel).append(" (").append(String.join(", ", tags)).append(")");
                    } else {
                        sb.append(cleanLabel);
                    }
                }

                if (sb.length() > 0) {
                    showToast(sb.toString());
                }
            }
        };

        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                new Handler(mainLooper).postDelayed(sMediaPrivacyRunnable, 300);
            } else {
                sMediaPrivacyRunnable.run();
            }
        } catch (Throwable t) {
            sMediaPrivacyRunnable.run();
        }
    }

    private static volatile long sLastToastTime = 0;
    private static volatile String sLastToastMsg = "";

    private static void showToast(final String message) {
        if (message == null || message.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (MainHook.class) {
            if (now - sLastToastTime < 2000) {
                return;
            }
            if (message.equals(sLastToastMsg) && (now - sLastToastTime < 4000)) {
                return;
            }
            sLastToastTime = now;
            sLastToastMsg = message;
        }

        try {
            Context targetCtx = null;
            try {
                targetCtx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication"
                );
            } catch (Throwable ignored) {}

            if (targetCtx != null) {
                final Context appCtx = targetCtx.getApplicationContext() != null
                        ? targetCtx.getApplicationContext()
                        : targetCtx;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            XposedBridge.log("[Solipsistic] Toast failed: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
}
