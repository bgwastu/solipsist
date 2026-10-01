package net.wastu.solipsist;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.CrossProcessCursorWrapper;
import android.database.CursorWindow;
import android.database.CharArrayBuffer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.webkit.MimeTypeMap;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.security.SecureRandom;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final int FILENAME_SALT = new SecureRandom().nextInt();

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
        if ("net.wastu.solipsist".equals(lpparam.packageName)) {
            return;
        }
        RuntimeState.bootstrap(lpparam);

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
            XposedBridge.log("[Solipsist] Hooking MediaProvider in " + lpparam.packageName);

            // A. Generic Filenames for Photo Picker & MediaProvider queries
            try {
                XC_MethodHook wrapCursorHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof android.content.ContentProvider) {
                            RuntimeState.attachContext(((android.content.ContentProvider) param.thisObject).getContext());
                        }
                        if (!RuntimeState.isEnabled()) return;
                        Cursor cursor = (Cursor) param.getResult();
                        if (cursor == null) return;

                        int callingUid = Binder.getCallingUid();
                        if (param.args.length >= 4 && param.args[3] instanceof Integer) {
                            callingUid = ((Integer) param.args[3]).intValue();
                        }
                        if (AppFilter.isExemptUid(callingUid)) return;

                        String callingPkg = null;
                        try {
                            callingPkg = (String) XposedHelpers.callMethod(param.thisObject, "getCallingPackage");
                        } catch (Throwable ignored) {}

                        if (callingPkg == null && param.args.length >= 5 && param.args[4] instanceof String) {
                            callingPkg = (String) param.args[4];
                        }

                        if (callingPkg != null && AppFilter.isExemptPackage(callingPkg, null)) {
                            return;
                        }

                        Uri uri = (Uri) param.args[0];
                        RuntimeState.count("media_query", callingUid);
                        param.setResult(wrapPickerCursor(cursor, uri, null));
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
                    XposedBridge.log("[Solipsist] Successfully hooked PickerUriResolver.query for generic names");
                    RuntimeState.reportInstalled("Media.PickerUriResolver.query");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsist] Error hooking PickerUriResolver.query: " + t.getMessage());
                    RuntimeState.reportHook("Media.PickerUriResolver.query", t);
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
                    XposedBridge.log("[Solipsist] Successfully hooked MediaProvider.query(Uri, String[], Bundle, CancellationSignal)");
                    RuntimeState.reportInstalled("Media.MediaProvider.queryBundle");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsist] Error hooking MediaProvider.query(Bundle): " + t.getMessage());
                    RuntimeState.reportHook("Media.MediaProvider.queryBundle", t);
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
                    XposedBridge.log("[Solipsist] Successfully hooked MediaProvider.query(5 args)");
                    RuntimeState.reportInstalled("Media.MediaProvider.queryLegacy");
                } catch (Throwable t) {
                    XposedBridge.log("[Solipsist] Error hooking MediaProvider.query(5 args): " + t.getMessage());
                    RuntimeState.reportHook("Media.MediaProvider.queryLegacy", t);
                }
            } catch (Throwable t) {
                XposedBridge.log("[Solipsist] Error setting up generic filename query hooks: " + t.getMessage());
                RuntimeState.reportHook("Media.filenameSetup", t);
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
                        if (!RuntimeState.isEnabled()) return;
                        int uid = (Integer) param.args[0];
                        if (uid > 10000 && uid != android.os.Process.myUid()) {
                            if (!AppFilter.isExemptUid(uid)) {
                                param.args[2] = true; // force shouldRedact = true only for non-exempt user apps
                                RuntimeState.count("media_redaction", uid);
                            }
                        }
                    }
                });
                XposedBridge.log("[Solipsist] Successfully hooked MediaProvider$PendingOpenInfo constructor for forced redaction");
                RuntimeState.reportInstalled("Media.PendingOpenInfo");
            } catch (Throwable t) {
                XposedBridge.log("[Solipsist] Error hooking PendingOpenInfo constructor: " + t.getMessage());
                RuntimeState.reportHook("Media.PendingOpenInfo", t);
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
                        if (!RuntimeState.isEnabled()) return;
                        int callingUid = Binder.getCallingUid();
                        if (callingUid != android.os.Process.myUid() && AppFilter.isExemptUid(callingUid)) return;
                        FileInputStream fis = null;
                        boolean ownsStream = false;
                        String mimeType = null;
                        if (param.args.length == 2 && param.args[0] instanceof FileInputStream) {
                            fis = (FileInputStream) param.args[0];
                            mimeType = (String) param.args[1];
                        } else if (param.args.length == 1 && param.args[0] instanceof java.io.File) {
                            java.io.File f = (java.io.File) param.args[0];
                            if (f != null && f.exists()) {
                                try {
                                    fis = new FileInputStream(f);
                                    ownsStream = true;
                                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        MimeTypeMap.getFileExtensionFromUrl(f.getAbsolutePath())
                                    );
                                } catch (Throwable ignored) {}
                            }
                        }
                        try {
                            if (fis == null || mimeType == null) return;
                            long[] origRanges = (long[]) param.getResult();
                            long[] extraRanges = null;

                            if (ExifInterface.isSupportedMimeType(mimeType)) {
                                extraRanges = getExtraSensitiveExifRanges(fis, mimeType);
                            } else if (isIsoBmffMime(mimeType)) {
                                extraRanges = getVideoSensitiveRanges(fis);
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
                        } finally {
                            if (ownsStream && fis != null) {
                                try { fis.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                };

                int installed = 0;
                for (java.lang.reflect.Method m : redactionUtilsClass.getDeclaredMethods()) {
                    if ("getRedactionRanges".equals(m.getName())) {
                        XposedBridge.hookMethod(m, redactionHook);
                        installed++;
                        XposedBridge.log("[Solipsist] Successfully hooked RedactionUtils." + m.getName() + " with " + m.getParameterTypes().length + " params");
                        RuntimeState.reportInstalled("Media.RedactionUtils.getRedactionRanges");
                    }
                }
                if (installed == 0) RuntimeState.reportHook("Media.RedactionUtils.getRedactionRanges", new NoSuchMethodException("getRedactionRanges"));
            } catch (Throwable t) {
                XposedBridge.log("[Solipsist] Error hooking RedactionUtils.getRedactionRanges: " + t.getMessage());
                RuntimeState.reportHook("Media.RedactionUtils.getRedactionRanges", t);
            }
            return;
        }

        if ("com.android.photopicker".equals(lpparam.packageName)) {
            return;
        }

        // Exclude camera apps and all system apps - only target third-party user apps
        if (AppFilter.isExemptPackage(lpparam.packageName, lpparam.appInfo)) {
            XposedBridge.log("[Solipsist] Skipping Privacy Shield for exempt package: " + lpparam.packageName);
            return;
        }

        // Apply Privacy Shield (USB debugging, developer options, and accessibility hiding) to target apps
        PrivacyShieldHook.init(lpparam);
        installAppMediaQueryHook();
        CameraCaptureHook.install();
        AdvancedReadObserver.install(lpparam);
    }

    private static void installAppMediaQueryHook() {
        try {
            Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(ContentResolver.class, "query", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!RuntimeState.isEnabled() || param.args.length == 0 || !(param.args[0] instanceof Uri)
                            || !(param.getResult() instanceof Cursor)) return;
                    Uri uri = (Uri) param.args[0];
                    if (!"content".equals(uri.getScheme())) return;
                    String authority = uri.getAuthority();
                    boolean cameraOutput = CameraCaptureHook.isCapturedUri(uri);
                    if (authority == null || !(cameraOutput || authority.contains("media")
                            || authority.contains("photopicker") || authority.contains("photos")
                            || authority.contains("documents"))) return;
                    Cursor cursor = (Cursor) param.getResult();
                    if (cursor.getColumnIndex("_display_name") < 0 && cursor.getColumnIndex("title") < 0
                            && cursor.getColumnIndex("_data") < 0) return;
                    String mime;
                    try {
                        mime = ((ContentResolver) param.thisObject).getType(uri);
                    } catch (Throwable ignored) {
                        return;
                    }
                    if (mime == null && cameraOutput) mime = "image/jpeg";
                    if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/")
                            || mime.startsWith("audio/"))) return;
                    RuntimeState.count("media_query");
                    param.setResult(wrapPickerCursor(cursor, uri, mime));
                }
            });
            if (installed.isEmpty()) RuntimeState.reportHook("Media.ContentResolver.query", new NoSuchMethodException("query"));
            else RuntimeState.reportInstalled("Media.ContentResolver.query");
        } catch (Throwable t) {
            RuntimeState.reportHook("Media.ContentResolver.query", t);
        }
    }

    private static Cursor wrapPickerCursor(final Cursor cursor, final Uri uri, final String mimeHint) {
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
                int oldPosition = getPosition();
                window.clear();
                window.setStartPosition(position);
                window.setNumColumns(getColumnCount());
                try {
                    for (int row = position; moveToPosition(row) && window.allocRow(); row++) {
                        for (int column = 0; column < getColumnCount(); column++) {
                            int type = getType(column);
                            boolean written;
                            switch (type) {
                                case Cursor.FIELD_TYPE_NULL:
                                    written = window.putNull(row, column);
                                    break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    written = window.putLong(getLong(column), row, column);
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT:
                                    written = window.putDouble(getDouble(column), row, column);
                                    break;
                                case Cursor.FIELD_TYPE_BLOB:
                                    written = window.putBlob(getBlob(column), row, column);
                                    break;
                                default:
                                    written = window.putString(getString(column), row, column);
                            }
                            if (!written) {
                                window.freeLastRow();
                                return;
                            }
                        }
                    }
                } finally {
                    moveToPosition(oldPosition);
                }
            }

            @Override
            public int getType(int columnIndex) {
                return RuntimeState.isEnabled() && columnIndex == mDataCol
                        ? Cursor.FIELD_TYPE_NULL : super.getType(columnIndex);
            }

            @Override
            public byte[] getBlob(int columnIndex) {
                return RuntimeState.isEnabled() && columnIndex == mDataCol ? null : super.getBlob(columnIndex);
            }

            @Override
            public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
                String value = getString(columnIndex);
                if (value == null) {
                    buffer.sizeCopied = 0;
                    return;
                }
                int length = value.length();
                if (buffer.data == null || buffer.data.length < length) buffer.data = new char[length];
                value.getChars(0, length, buffer.data, 0);
                buffer.sizeCopied = length;
            }

            @Override
            public String getString(int columnIndex) {
                if (!RuntimeState.isEnabled()) return super.getString(columnIndex);
                if (columnIndex >= 0) {
                    if (columnIndex == mDisplayNameCol) {
                        String origName = null;
                        try {
                            origName = super.getString(mDisplayNameCol);
                        } catch (Throwable ignored) {}
                    String genericName = computeGenericName(this, uri, origName, true, mimeHint);
                    return genericName;
                    } else if (columnIndex == mTitleCol) {
                        return computeGenericName(this, uri, null, false, mimeHint);
                    } else if (columnIndex == mDataCol) {
                        return null; // Use the content URI; a fabricated file path cannot be opened.
                    }
                }
                return super.getString(columnIndex);
            }
        };
    }

    private static boolean isIsoBmffMime(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase();
        return "video/mp4".equals(lower) || "video/quicktime".equals(lower)
            || "video/3gpp".equals(lower) || "video/3gpp2".equals(lower)
            || "audio/mp4".equals(lower) || "audio/x-m4a".equals(lower)
            || "application/mp4".equals(lower);
    }

    private static String computeGenericName(Cursor cursor, Uri uri, String origName, boolean withExtension, String mimeHint) {
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

        id = Integer.toUnsignedString((id + FILENAME_SALT).hashCode(), 36);

        String mime = mimeHint;
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

            for (String tag : EXTRA_SENSITIVE_EXIF_TAGS) {
                long[] r = ex.getAttributeRange(tag);
                if (r != null && r.length == 2 && r[1] > 0) {
                    ranges.add(r[0]);
                    ranges.add(r[0] + r[1]);
                }
            }

            if (ranges.isEmpty()) {
                return null;
            }
            long[] result = new long[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                result[i] = ranges.get(i);
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[Solipsist] Error extracting extra sensitive EXIF ranges: " + t.getMessage());
            return null;
        }
    }

    private static long[] getVideoSensitiveRanges(FileInputStream fis) {
        if (fis == null) return null;

        List<Long> extraRanges = new ArrayList<Long>();

        FileChannel channel = null;
        long origPos = -1;
        try {
            channel = fis.getChannel();
            origPos = channel.position();
            long fileSize = channel.size();
            scanIsoBoxes(channel, 0, fileSize, extraRanges, 0);
        } catch (Throwable t) {
            XposedBridge.log("[Solipsist] Error scanning video boxes: " + t.getMessage());
        } finally {
            if (channel != null && origPos >= 0) {
                try {
                    channel.position(origPos);
                } catch (Throwable ignored) {}
            }
        }

        if (extraRanges.isEmpty()) {
            return null;
        }
        long[] result = new long[extraRanges.size()];
        for (int i = 0; i < extraRanges.size(); i++) {
            result[i] = extraRanges.get(i);
        }
        return result;
    }

    private static void scanIsoBoxes(FileChannel channel, long start, long end, List<Long> ranges, int depth) throws IOException {
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
                scanIsoBoxes(channel, pos + headerSize, boxEnd, ranges, depth + 1);
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
                    }
                }
            } else if (typeInt == 0x75647461) {
                // 'udta' (0x75647461) - User Data box: camera model, make, Android version, etc.
                if (boxEnd > pos + 4) {
                    ranges.add(pos + 4);
                    ranges.add(boxEnd);
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
}
