package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Один элемент ленты чата: сообщение пользователя, ответ, размышления, график или ошибка.
 * Поля text, sources и expanded меняются только в главном потоке (потоке интерфейса).
 */
public final class ChatItem {
    public static final int USER = 0;
    public static final int ASSISTANT = 1;
    public static final int CHART = 2;
    public static final int ERROR = 3;
    public static final int THINKING = 4;

    /** Источник: заголовок страницы и её адрес. */
    public static final class Source {
        public final String title;
        public final String url;

        public Source(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    public final int type;
    /** Текст меняется, пока ответ печатается по частям. */
    public String text;
    /** Для размышлений: показан весь текст (true) или только первые строки (false). */
    public boolean expanded;
    public final List<Source> sources = new ArrayList<>();
    public final List<String> fileNames = new ArrayList<>();
    public final ChartSpec chart;

    private ChatItem(int type, String text, ChartSpec chart) {
        this.type = type;
        this.text = text;
        this.chart = chart;
    }

    public static ChatItem user(String text, List<String> fileNames) {
        ChatItem item = new ChatItem(USER, text, null);
        item.fileNames.addAll(fileNames);
        return item;
    }

    public static ChatItem assistant(String text) {
        return new ChatItem(ASSISTANT, text, null);
    }

    public static ChatItem thinking(String text) {
        ChatItem item = new ChatItem(THINKING, text, null);
        item.expanded = true;
        return item;
    }

    public static ChatItem chart(ChartSpec spec) {
        return new ChatItem(CHART, "", spec);
    }

    public static ChatItem error(String text) {
        return new ChatItem(ERROR, text, null);
    }

    /** Превращает элемент в JSON, чтобы записать переписку в файл. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject().put("type", type).put("text", text);
        if (!sources.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Source s : sources) {
                arr.put(new JSONObject().put("title", s.title).put("url", s.url));
            }
            o.put("sources", arr);
        }
        if (!fileNames.isEmpty()) o.put("files", new JSONArray(fileNames));
        if (chart != null) o.put("chart", chart.raw);
        return o;
    }

    /** Восстанавливает элемент из файла. */
    public static ChatItem fromJson(JSONObject o) throws JSONException {
        int type = o.getInt("type");
        ChartSpec spec = type == CHART ? ChartSpec.fromJson(o.getJSONObject("chart")) : null;
        ChatItem item = new ChatItem(type, o.optString("text"), spec);
        JSONArray src = o.optJSONArray("sources");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject s = src.getJSONObject(i);
                item.sources.add(new Source(s.optString("title"), s.optString("url")));
            }
        }
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) item.fileNames.add(files.getString(i));
        }
        return item;
    }
}
