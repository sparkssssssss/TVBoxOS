package com.github.tvbox.osc.subtitle;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.subtitle.exception.FatalParsingException;
import com.github.tvbox.osc.subtitle.format.FormatASS;
import com.github.tvbox.osc.subtitle.format.FormatSRT;
import com.github.tvbox.osc.subtitle.format.FormatSTL;
import com.github.tvbox.osc.subtitle.format.TimedTextFileFormat;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.UnicodeReader;
import com.lzy.okgo.OkGo;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.io.input.ReaderInputStream;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.Charset;

import okhttp3.Response;

/**
 * @author AveryZhong.
 */

public class SubtitleLoader {
    private static final String TAG = SubtitleLoader.class.getSimpleName();

    private SubtitleLoader() {
        throw new AssertionError("No instance for you.");
    }

    public static void loadSubtitle(final String path, final Callback callback) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (path.startsWith("http://")
                || path.startsWith("https://")) {
            loadFromRemoteAsync(path, callback);
        } else {
            loadFromLocalAsync(path, callback);
        }
    }

    private static void loadFromRemoteAsync(final String remoteSubtitlePath,
                                            final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromRemote(remoteSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    private static void loadFromLocalAsync(final String localSubtitlePath,
                                           final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromLocal(localSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    public SubtitleLoadSuccessResult loadSubtitle(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            if (path.startsWith("http://")
                    || path.startsWith("https://")) {
                return loadFromRemote(path);
            } else {
                return loadFromLocal(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static SubtitleLoadSuccessResult loadFromRemote(final String remoteSubtitlePath)
            throws IOException, FatalParsingException, Exception {
        Log.d(TAG, "parseRemote: remoteSubtitlePath = " + remoteSubtitlePath);
        String referer = "";
        if (remoteSubtitlePath.contains("alicloud") || remoteSubtitlePath.contains("aliyundrive")) {
            referer = "https://www.aliyundrive.com/";
        } else if (remoteSubtitlePath.contains("assrt.net")) {
            referer = "https://secure.assrt.net/";
        }
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        Response response = OkGo.<String>get(remoteSubtitlePath.split("#")[0])
                .headers("Referer", referer)
                .headers("User-Agent", ua)
                .execute();
        byte[] bytes = response.body().bytes();
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String encoding = detector.getDetectedCharset();
        if (TextUtils.isEmpty(encoding)) encoding = "UTF-8";
        String content = new String(bytes, encoding);
        String filename = "";
        String contentDispostion = response.header("content-disposition", "");
        String[] cd = contentDispostion.split(";");
        if (cd.length > 1) {
            String filenameInfo = cd[1];
            filenameInfo = filenameInfo.trim();
            if (filenameInfo.startsWith("filename=")) {
                filename = filenameInfo.replace("filename=", "");
                filename = filename.replace("\"", "");
            } else if (filenameInfo.startsWith("filename*=")) {
                filename = filenameInfo.substring(filenameInfo.lastIndexOf("''")+2);
            }
            filename = filename.trim();
            filename = URLDecoder.decode(filename);
        }
        String filePath = filename;
        if (filename == null || filename.length() < 1) {
            Uri uri = Uri.parse(remoteSubtitlePath);
            filePath = uri.getPath();
        }
        if (!filePath.contains(".") && remoteSubtitlePath.contains("#")) {
            filePath = remoteSubtitlePath.split("#")[1];
            filePath = URLDecoder.decode(filePath);
        }
        // assrt 等站点会把单文件字幕也打包成 RAR 下发，这里检测并解压取首个字幕文件
        byte[] payload = bytes;
        String payloadName = filePath;
        if (isRar(bytes)) {
            try {
                Object[] extracted = extractFirstSubtitleFromRar(bytes);
                if (extracted != null) {
                    payload = (byte[]) extracted[0];
                    payloadName = (String) extracted[1];
                    filePath = payloadName;
                    // 用解压后的真实字节重测编码
                    detector = new UniversalDetector(null);
                    detector.handleData(payload, 0, payload.length);
                    detector.dataEnd();
                    String enc2 = detector.getDetectedCharset();
                    if (!TextUtils.isEmpty(enc2)) encoding = enc2;
                }
            } catch (Throwable th) {
                Log.w(TAG, "extractFirstSubtitleFromRar failed: " + th.getMessage());
            }
        }
        content = new String(payload, TextUtils.isEmpty(encoding) ? "UTF-8" : encoding);
        InputStream is = new ByteArrayInputStream(payload);
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
        subtitleLoadSuccessResult.fileName = filePath;
        subtitleLoadSuccessResult.content = content;
        subtitleLoadSuccessResult.subtitlePath = remoteSubtitlePath;
        return subtitleLoadSuccessResult;
    }

    private static SubtitleLoadSuccessResult loadFromLocal(final String localSubtitlePath)
            throws IOException, FatalParsingException {
        Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath);
        File file = new File(localSubtitlePath);
        if (!file.exists()) {
            Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath + " file not exsits");
            return null;
        }
        byte[] bytes = FileUtils.readSimple(file);
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String encoding = detector.getDetectedCharset();
        String content = new String(bytes, TextUtils.isEmpty(encoding) ? "UTF-8" : encoding);
        String filePath = file.getPath();
        // 本地字幕也可能是 RAR/ZIP 打包
        byte[] payload = bytes;
        if (isRar(bytes) || isZip(bytes)) {
            try {
                Object[] extracted = isRar(bytes)
                        ? extractFirstSubtitleFromRar(bytes)
                        : extractFirstSubtitleFromZip(bytes);
                if (extracted != null) {
                    payload = (byte[]) extracted[0];
                    filePath = (String) extracted[1];
                    content = new String(payload, TextUtils.isEmpty(encoding) ? "UTF-8" : encoding);
                }
            } catch (Throwable th) {
                Log.w(TAG, "extractFirstSubtitleFromArchive failed: " + th.getMessage());
            }
        }
        InputStream is = new ByteArrayInputStream(payload);
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        subtitleLoadSuccessResult.fileName = fileName;
        subtitleLoadSuccessResult.subtitlePath = localSubtitlePath;
        return subtitleLoadSuccessResult;
    }

    private static boolean isRar(byte[] bytes) {
        return bytes != null && bytes.length > 7
                && bytes[0] == 'R' && bytes[1] == 'a' && bytes[2] == 'r' && bytes[3] == '!'
                && bytes[4] == 0x1a && bytes[5] == 0x07;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length > 3
                && ((bytes[0] == 0x50 && bytes[1] == 0x4b && (bytes[2] & 0xff) == 0x03 && (bytes[3] & 0xff) == 0x04)
                 || (bytes[0] == 0x50 && bytes[1] == 0x4b && (bytes[2] & 0xff) == 0x05 && (bytes[3] & 0xff) == 0x06));
    }

    /**
     * 从 RAR 字节流中提取首个可识别的字幕文件字节。
     * 返回 [字幕字节, 文件名]，找不到返回 null。
     */
    private static Object[] extractFirstSubtitleFromRar(byte[] bytes) throws Exception {
        java.io.ByteArrayOutputStream bos = null;
        Archive archive = null;
        try {
            archive = new Archive(new java.io.ByteArrayInputStream(bytes));
            FileHeader firstHeader = null;
            // 先扫一遍找到第一个字幕扩展名
            for (FileHeader fh = archive.nextFileHeader(); fh != null; fh = archive.nextFileHeader()) {
                String name = fh.getFileNameString();
                if (name == null) continue;
                String lower = name.toLowerCase();
                if (lower.endsWith(".srt") || lower.endsWith(".ass") || lower.endsWith(".ssa")
                        || lower.endsWith(".stl") || lower.endsWith(".ttml")
                        || lower.endsWith(".sub") || lower.endsWith(".vtt")) {
                    firstHeader = fh;
                    break;
                }
            }
            if (firstHeader == null) {
                // 回退：取第一个普通文件
                archive.close();
                archive = new Archive(new java.io.ByteArrayInputStream(bytes));
                for (FileHeader fh = archive.nextFileHeader(); fh != null; fh = archive.nextFileHeader()) {
                    if (!fh.isDirectory()) { firstHeader = fh; break; }
                }
            }
            if (firstHeader == null) return null;
            bos = new java.io.ByteArrayOutputStream();
            archive.extractFile(firstHeader, bos);
            return new Object[]{ bos.toByteArray(), firstHeader.getFileNameString() };
        } finally {
            try { if (archive != null) archive.close(); } catch (Throwable ignored) {}
            try { if (bos != null) bos.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 从 ZIP 字节流中提取首个字幕文件。返回 [字幕字节, 文件名]。
     */
    private static Object[] extractFirstSubtitleFromZip(byte[] bytes) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            java.util.zip.ZipEntry target = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase();
                if (lower.endsWith(".srt") || lower.endsWith(".ass") || lower.endsWith(".ssa")
                        || lower.endsWith(".stl") || lower.endsWith(".ttml")
                        || lower.endsWith(".sub") || lower.endsWith(".vtt")) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                zis.close();
                try (java.util.zip.ZipInputStream zis2 = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    while ((entry = zis2.getNextEntry()) != null) {
                        if (!entry.isDirectory()) { target = entry; break; }
                    }
                    if (target == null) return null;
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zis2.read(buf)) > 0) bos.write(buf, 0, n);
                    return new Object[]{ bos.toByteArray(), target.getName() };
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
            return new Object[]{ bos.toByteArray(), target.getName() };
        }
    }

    private static TimedTextObject loadAndParse(final InputStream is, final String filePath)
            throws IOException, FatalParsingException {
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        String ext = "";
        if (fileName.lastIndexOf(".") > 0) {
            ext = fileName.substring(fileName.lastIndexOf("."));
        }
        Log.d(TAG, "parse: name = " + fileName + ", ext = " + ext);
        Reader reader = new UnicodeReader(is); //处理有BOM头的utf8
        InputStream newInputStream = new ReaderInputStream(reader, Charset.defaultCharset());
        if (".srt".equalsIgnoreCase(ext)) {
            return new FormatSRT().parseFile(fileName, newInputStream);
        } else if (".ass".equalsIgnoreCase(ext)) {
            return new FormatASS().parseFile(fileName, newInputStream);
        } else if (".stl".equalsIgnoreCase(ext)) {
            return new FormatSTL().parseFile(fileName, newInputStream);
        } else if (".ttml".equalsIgnoreCase(ext)) {
            return new FormatSTL().parseFile(fileName, newInputStream);
        }
        TimedTextFileFormat[] arr = {new FormatSRT(), new FormatASS(), new FormatSTL(), new FormatSTL()};
        for(TimedTextFileFormat oneFormat : arr) {
            try {
                TimedTextObject obj = oneFormat.parseFile(fileName, newInputStream);
                return obj;
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    public interface Callback {
        void onSuccess(SubtitleLoadSuccessResult SubtitleLoadSuccessResult);

        void onError(Exception exception);
    }
}
