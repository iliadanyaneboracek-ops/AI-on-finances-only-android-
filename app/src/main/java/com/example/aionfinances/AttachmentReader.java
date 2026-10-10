package com.example.aionfinances;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Превращает выбранный файл в блок содержимого для Claude API. */
public final class AttachmentReader {
    private AttachmentReader() {}

    /** Файл не подходит: слишком большой или неподдерживаемого формата. */
    public static final class FileException extends Exception {
        public FileException(String message) {
            super(message);
        }
    }

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "csv", "tsv", "md", "json", "xml", "log", "html", "htm"));

    /** Имя файла, как его видит пользователь. */
    public static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // поставщик файла не отдал имя — возьмём его из адреса
        }
        String last = uri.getLastPathSegment();
        return last == null ? "файл" : last;
    }

    public static JSONObject toContentBlock(ContentResolver cr, Uri uri, String name)
            throws IOException, JSONException, FileException {
        String mime = cr.getType(uri);
        if (mime == null) mime = "";

        // Документы и таблицы Google на Диске — «виртуальные» файлы: у них нет готового
        // содержимого, его нужно запросить в одном из форматов, которые предлагает Диск.
        String exportMime = null;
        if (isVirtual(cr, uri)) {
            exportMime = pickExportType(cr.getStreamTypes(uri, "*/*"));
            if (exportMime == null) {
                throw new FileException("«" + name + "»: этот файл Google Диска нельзя "
                        + "получить в формате PDF, CSV или текста.");
            }
            mime = exportMime;
        }
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);

        if (mime.startsWith("image/")) {
            return imageBlock(readBytes(cr, uri, exportMime, 40 * 1024 * 1024, name), name);
        }
        if (mime.equals("application/pdf") || (exportMime == null && ext.equals("pdf"))) {
            byte[] bytes = readBytes(cr, uri, exportMime, Config.MAX_PDF_BYTES, name);
            return new JSONObject()
                    .put("type", "document")
                    .put("source", new JSONObject()
                            .put("type", "base64")
                            .put("media_type", "application/pdf")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)));
        }
        if (mime.startsWith("text/") || mime.equals("application/json")
                || mime.equals("application/xml")
                || (exportMime == null && TEXT_EXTENSIONS.contains(ext))) {
            String text = new String(readBytes(cr, uri, exportMime, 4 * 1024 * 1024, name),
                    StandardCharsets.UTF_8);
            if (text.length() > Config.MAX_TEXT_CHARS) {
                text = text.substring(0, Config.MAX_TEXT_CHARS) + "\n[файл обрезан: он слишком длинный]";
            }
            return new JSONObject()
                    .put("type", "text")
                    .put("text", "Содержимое файла «" + name + "»:\n" + text);
        }
        throw new FileException("«" + name + "»: формат не поддерживается. "
                + "Сейчас можно прикреплять изображения, PDF и текстовые файлы (TXT, CSV, JSON).");
    }

    private static InputStream open(ContentResolver cr, Uri uri, String exportMime)
            throws IOException {
        if (exportMime == null) return cr.openInputStream(uri);
        AssetFileDescriptor fd = cr.openTypedAssetFileDescriptor(uri, exportMime, null);
        return fd == null ? null : fd.createInputStream();
    }

    /** Уменьшает картинку до разумного размера и кодирует в JPEG: так запрос меньше и дешевле. */
    private static JSONObject imageBlock(byte[] bytes, String name)
            throws JSONException, FileException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new FileException("«" + name + "»: не удалось прочитать изображение.");
        }
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (longSide / (opts.inSampleSize * 2) >= Config.MAX_IMAGE_SIDE) opts.inSampleSize *= 2;

        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bitmap == null) {
            throw new FileException("«" + name + "»: не удалось прочитать изображение.");
        }
        int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (side > Config.MAX_IMAGE_SIDE) {
            float k = (float) Config.MAX_IMAGE_SIDE / side;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * k)),
                    Math.max(1, Math.round(bitmap.getHeight() * k)), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        bitmap.recycle();

        return new JSONObject()
                .put("type", "image")
                .put("source", new JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)));
    }

    private static boolean isVirtual(ContentResolver cr, Uri uri) {
        if (!"content".equals(uri.getScheme())) return false;
        try (Cursor c = cr.query(uri, new String[]{DocumentsContract.Document.COLUMN_FLAGS},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return (c.getInt(0) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0;
            }
        } catch (RuntimeException ignored) {
            // поставщик файла не знает такого столбца — значит, файл обычный
        }
        return false;
    }

    /** Выбирает формат выгрузки: таблицы лучше как CSV, документы — как PDF. */
    private static String pickExportType(String[] offered) {
        if (offered == null) return null;
        String[] preferred = {"text/csv", "application/pdf", "text/plain", "text/html"};
        for (String want : preferred) {
            for (String have : offered) {
                if (want.equals(have)) return want;
            }
        }
        return null;
    }

    /** @param exportMime формат выгрузки для виртуального файла или null для обычного */
    private static byte[] readBytes(ContentResolver cr, Uri uri, String exportMime,
                                    int limit, String name)
            throws IOException, FileException {
        try (InputStream in = open(cr, uri, exportMime)) {
            if (in == null) throw new IOException("Не удалось открыть файл «" + name + "»");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > limit) {
                    throw new FileException("«" + name + "»: файл слишком большой (предел "
                            + (limit / (1024 * 1024)) + " МБ).");
                }
            }
            return out.toByteArray();
        }
    }
}
