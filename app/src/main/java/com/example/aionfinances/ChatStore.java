package com.example.aionfinances;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранит переписки в закрытой папке приложения: по одному файлу на чат
 * и отдельный файл-оглавление index.json со списком чатов.
 */
public final class ChatStore {

    /** Строка оглавления. */
    public static final class Entry {
        public final String id;
        public final String title;
        public final long updated;

        Entry(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }
    }

    private final File dir;

    public ChatStore(Context context) {
        dir = new File(context.getFilesDir(), "chats");
    }

    /** Список чатов, самые свежие первыми. */
    public synchronized List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray index = readIndex();
            for (int i = 0; i < index.length(); i++) {
                JSONObject o = index.getJSONObject(i);
                result.add(new Entry(o.getString("id"), o.optString("title"), o.optLong("updated")));
            }
        } catch (IOException | JSONException ignored) {
            // оглавление повреждено или отсутствует — показываем пустой список
        }
        return result;
    }

    public synchronized void write(String id, String title, JSONArray items, JSONArray history)
            throws IOException, JSONException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать папку чатов");
        JSONObject chat = new JSONObject()
                .put("title", title)
                .put("items", items)
                .put("history", history);
        writeFile(chatFile(id), chat.toString());

        JSONArray old = readIndex();
        JSONArray index = new JSONArray();
        index.put(new JSONObject().put("id", id).put("title", title)
                .put("updated", System.currentTimeMillis()));
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            if (!id.equals(o.optString("id"))) index.put(o);
        }
        writeFile(indexFile(), index.toString());
    }

    /** @return содержимое чата с полями title, items, history */
    public synchronized JSONObject read(String id) throws IOException, JSONException {
        return new JSONObject(readFile(chatFile(id)));
    }

    public synchronized void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        chatFile(id).delete();
        try {
            JSONArray old = readIndex();
            JSONArray index = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!id.equals(o.optString("id"))) index.put(o);
            }
            writeFile(indexFile(), index.toString());
        } catch (IOException | JSONException ignored) {
            // оглавление не обновилось — чат просто не откроется из списка
        }
    }

    private File chatFile(String id) {
        return new File(dir, id + ".json");
    }

    private File indexFile() {
        return new File(dir, "index.json");
    }

    private JSONArray readIndex() throws IOException, JSONException {
        File f = indexFile();
        return f.exists() ? new JSONArray(readFile(f)) : new JSONArray();
    }

    private static void writeFile(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
