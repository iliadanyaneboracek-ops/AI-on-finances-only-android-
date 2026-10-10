package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Отправляет запросы в Claude API и читает ответ по частям (потоковая передача).
 * Работает синхронно: вызывать только из фонового потока.
 */
public final class ClaudeClient {

    /** Ошибка, текст которой можно показать пользователю. */
    public static final class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    /** Сюда приходят кусочки ответа по мере генерации. Вызывается в фоновом потоке. */
    public interface StreamListener {
        /** Очередной фрагмент размышлений модели. */
        void onThinking(String delta);

        /** Очередной фрагмент текста ответа. */
        void onText(String delta);

        /** Текстовый блок закончен; citations — ссылки на источники для него или null. */
        void onTextBlockDone(JSONArray citations);

        /** Модель отправила поисковый запрос в интернет. */
        void onSearch(String query);
    }

    /** Итог одного запроса: собранные блоки ответа и причина остановки. */
    public static final class Result {
        public final JSONArray content;
        public final String stopReason;

        Result(JSONArray content, String stopReason) {
            this.content = content;
            this.stopReason = stopReason;
        }
    }

    /** @param messages история диалога в формате API */
    public Result stream(String apiKey, JSONArray messages, StreamListener listener)
            throws IOException, JSONException, ApiException {
        JSONObject body = new JSONObject();
        body.put("model", Config.MODEL);
        body.put("max_tokens", Config.MAX_TOKENS);
        body.put("stream", true);
        // Модель сама решает, сколько думать, и присылает краткое изложение размышлений.
        body.put("thinking", new JSONObject().put("type", "adaptive").put("display", "summarized"));
        body.put("system", SystemPrompt.build());
        body.put("tools", buildTools());
        body.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL(Config.API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(180_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", Config.API_VERSION);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "text/event-stream");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                throw new ApiException(describeError(code, err == null ? "" : readAll(err)));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return readEvents(reader, listener);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Сервер присылает события построчно: строка "data: {...}" содержит JSON одного события.
     * Ответ состоит из блоков (размышления, текст, вызов инструмента, результаты поиска);
     * каждый блок приходит как «начало — кусочки — конец». Собираем блоки обратно целиком,
     * потому что их нужно вернуть в историю диалога без изменений.
     */
    private static Result readEvents(BufferedReader reader, StreamListener listener)
            throws IOException, JSONException, ApiException {
        Map<Integer, JSONObject> blocks = new TreeMap<>();
        Map<Integer, StringBuilder> buffers = new TreeMap<>();
        Map<Integer, StringBuilder> signatures = new TreeMap<>();
        String stopReason = null;
        boolean finished = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            JSONObject event = new JSONObject(line.substring(5).trim());
            String type = event.optString("type");

            if ("content_block_start".equals(type)) {
                int index = event.getInt("index");
                blocks.put(index, event.getJSONObject("content_block"));
                buffers.put(index, new StringBuilder());
                signatures.put(index, new StringBuilder());

            } else if ("content_block_delta".equals(type)) {
                int index = event.getInt("index");
                JSONObject block = blocks.get(index);
                StringBuilder buffer = buffers.get(index);
                if (block == null || buffer == null) continue;
                JSONObject delta = event.getJSONObject("delta");
                String deltaType = delta.optString("type");
                if ("text_delta".equals(deltaType)) {
                    String piece = delta.optString("text");
                    buffer.append(piece);
                    listener.onText(piece);
                } else if ("thinking_delta".equals(deltaType)) {
                    String piece = delta.optString("thinking");
                    buffer.append(piece);
                    if (!piece.isEmpty()) listener.onThinking(piece);
                } else if ("signature_delta".equals(deltaType)) {
                    signatures.get(index).append(delta.optString("signature"));
                } else if ("input_json_delta".equals(deltaType)) {
                    buffer.append(delta.optString("partial_json"));
                } else if ("citations_delta".equals(deltaType)) {
                    JSONArray citations = block.optJSONArray("citations");
                    if (citations == null) {
                        citations = new JSONArray();
                        block.put("citations", citations);
                    }
                    citations.put(delta.getJSONObject("citation"));
                }

            } else if ("content_block_stop".equals(type)) {
                int index = event.getInt("index");
                JSONObject block = blocks.get(index);
                StringBuilder buffer = buffers.get(index);
                if (block == null || buffer == null) continue;
                String blockType = block.optString("type");
                if ("text".equals(blockType)) {
                    block.put("text", buffer.toString());
                    JSONArray citations = block.optJSONArray("citations");
                    if (citations != null && citations.length() == 0) {
                        block.remove("citations");
                        citations = null;
                    }
                    listener.onTextBlockDone(citations);
                } else if ("thinking".equals(blockType)) {
                    block.put("thinking", buffer.toString());
                    block.put("signature", signatures.get(index).toString());
                } else if ("tool_use".equals(blockType) || "server_tool_use".equals(blockType)) {
                    JSONObject input = buffer.length() == 0
                            ? new JSONObject() : new JSONObject(buffer.toString());
                    block.put("input", input);
                    if ("server_tool_use".equals(blockType)
                            && "web_search".equals(block.optString("name"))) {
                        listener.onSearch(input.optString("query"));
                    }
                }

            } else if ("message_delta".equals(type)) {
                JSONObject delta = event.optJSONObject("delta");
                if (delta != null && !delta.isNull("stop_reason")) {
                    stopReason = delta.optString("stop_reason");
                }

            } else if ("message_stop".equals(type)) {
                finished = true;
                break;

            } else if ("error".equals(type)) {
                JSONObject error = event.optJSONObject("error");
                String message = error == null ? "" : error.optString("message");
                throw new ApiException("Сервер прервал ответ: " + message);
            }
        }
        if (!finished) throw new IOException("соединение оборвалось до конца ответа");

        JSONArray content = new JSONArray();
        for (JSONObject block : blocks.values()) {
            // Пустой текстовый блок API обратно не принимает — пропускаем его.
            if ("text".equals(block.optString("type")) && block.optString("text").isEmpty()) continue;
            content.put(block);
        }
        return new Result(content, stopReason == null ? "" : stopReason);
    }

    /** Два инструмента: встроенный веб-поиск и наш собственный «нарисовать график». */
    private static JSONArray buildTools() throws JSONException {
        JSONArray domains = new JSONArray();
        for (String d : Config.TRUSTED_DOMAINS) domains.put(d);

        JSONObject search = new JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", Config.MAX_SEARCHES)
                .put("allowed_domains", domains);

        JSONObject stringType = new JSONObject().put("type", "string");
        JSONObject seriesItem = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("name", new JSONObject().put("type", "string")
                                .put("description", "Название ряда для легенды"))
                        .put("values", new JSONObject().put("type", "array")
                                .put("items", new JSONObject().put("type", "number"))
                                .put("description", "Значения, по одному на каждую подпись x_labels")))
                .put("required", new JSONArray().put("name").put("values"));

        JSONObject props = new JSONObject()
                .put("title", new JSONObject().put("type", "string")
                        .put("description", "Заголовок графика"))
                .put("chart_type", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("line").put("bar"))
                        .put("description", "line — динамика во времени, bar — сравнение величин"))
                .put("x_labels", new JSONObject().put("type", "array").put("items", stringType)
                        .put("description", "Подписи оси X по порядку, например даты"))
                .put("series", new JSONObject().put("type", "array").put("items", seriesItem))
                .put("y_unit", new JSONObject().put("type", "string")
                        .put("description", "Единица измерения, например % или млрд руб."))
                .put("source", new JSONObject().put("type", "string")
                        .put("description", "Откуда взяты данные: название источника и дата"));

        JSONObject chart = new JSONObject()
                .put("name", "draw_chart")
                .put("description", "Показывает пользователю график в приложении. Используй только "
                        + "реальные данные из результатов поиска или из файлов пользователя.")
                .put("input_schema", new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new JSONArray().put("title").put("chart_type")
                                .put("x_labels").put("series").put("source")));

        return new JSONArray().put(search).put(chart);
    }

    private static String describeError(int code, String text) {
        String detail = "";
        try {
            detail = new JSONObject(text).getJSONObject("error").optString("message", "");
        } catch (JSONException ignored) {
            // тело ответа не в формате JSON — покажем только код
        }
        String hint;
        if (detail.contains("credit balance")) {
            hint = "На счёте Claude API закончились средства. Пополните его в консоли Anthropic "
                    + "(Plans & Billing). Подписка на приложение Claude на API не распространяется.";
        } else {
            switch (code) {
                case 401: hint = "Неверный ключ API. Проверьте его в меню «Ключ API»."; break;
                case 403: hint = "Доступ запрещён. Возможно, API недоступен из вашего региона или у ключа нет прав."; break;
                case 413: hint = "Запрос слишком большой. Попробуйте файл поменьше."; break;
                case 429: hint = "Превышен лимит запросов. Повторите чуть позже."; break;
                case 529: hint = "Серверы Claude перегружены. Повторите через минуту."; break;
                default: hint = code >= 500 ? "Ошибка на стороне сервера." : "Запрос отклонён.";
            }
        }
        return hint + " (код " + code + (detail.isEmpty() ? "" : ": " + detail) + ")";
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
