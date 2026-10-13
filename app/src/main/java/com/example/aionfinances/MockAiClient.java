package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * Мок (подделка) сетевого слоя: ведёт себя как Claude API, но ничего не отправляет в интернет.
 *
 * Он присылает те же события, что и настоящий клиент: размышления, поисковый запрос, текст
 * по кусочкам, ссылки на источники и вызов инструмента draw_chart. Поэтому вся остальная
 * часть приложения (лента, графики, история, сохранение) работает и проверяется по-настоящему.
 *
 * ВСЕ ЦИФРЫ ЗДЕСЬ ВЫДУМАНЫ. Ответы помечены как демо, ссылки ведут на example.com —
 * зарезервированный адрес-заглушку, а не на реальные сайты.
 *
 * Сценарий выбирается по словам в вопросе:
 *   «ставк»                       — ключевая ставка, линейный график;
 *   «инфляц»                      — инфляция, столбчатый график;
 *   «курс», «доллар», «евро», «валют» — курсы валют, график из двух линий;
 *   «ошибк»                       — тестовая ошибка, чтобы проверить красное сообщение;
 *   приложены файлы               — ответ о файлах;
 *   всё остальное                 — подсказка, что умеет демо-режим.
 *
 * Блоки, которые создаёт мок, настоящий API не примет (у размышлений поддельная подпись),
 * поэтому чат, начатый в демо-режиме, после включения настоящего API лучше не продолжать.
 */
public final class MockAiClient implements AiClient {

    private static final String DEMO_NOTE =
            "Демо-режим: ответ сгенерирован на телефоне, все цифры вымышлены. ";

    @Override
    public Result stream(String apiKey, JSONArray messages, StreamListener listener)
            throws IOException, JSONException, ApiException {
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        JSONArray lastContent = last.getJSONArray("content");
        Response out = new Response(listener);

        // Второй круг: модель вызвала draw_chart и получила результат — дописываем вывод.
        if (hasToolResult(lastContent)) {
            String question = lastQuestion(messages).toLowerCase(Locale.ROOT);
            out.text(afterChartText(question));
            return out.finish("end_turn");
        }

        String question = questionText(lastContent);
        String q = question.toLowerCase(Locale.ROOT);
        int[] files = countFiles(lastContent);

        if (q.contains("ошибк")) {
            out.thinking("Пользователь просит показать ошибку. Имитирую сбой сервера.");
            throw new ApiException("Демо-режим: это тестовая ошибка, сервер не вызывался. "
                    + "Так приложение показывает сбой сети или отказ API.");
        }
        if (files[0] + files[1] + files[2] > 0) {
            answerFiles(out, files);
            return out.finish("end_turn");
        }
        if (q.contains("ставк")) {
            out.thinking("Вопрос о ключевой ставке. Нужна динамика за последние месяцы. "
                    + "Ищу данные на сайте центрального банка, затем покажу их графиком.");
            out.search("ключевая ставка Банка России динамика 2026");
            out.text(DEMO_NOTE + "По демо-источнику ключевая ставка за полгода снижалась "
                    + "с 18 до 15,5 процента.");
            out.textDone(citation(1, "Демо-источник: история ключевой ставки"));
            out.chart(new JSONObject()
                    .put("title", "Ключевая ставка (демо-данные)")
                    .put("chart_type", "line")
                    .put("x_labels", strings("Апр", "Май", "Июн", "Июл", "Авг", "Сен"))
                    .put("series", new JSONArray().put(series("Ставка", 18, 18, 17, 17, 16, 15.5)))
                    .put("y_unit", "%")
                    .put("source", "Демо-данные MIDS, не настоящие"));
            return out.finish("tool_use");
        }
        if (q.contains("инфляц")) {
            out.thinking("Вопрос об инфляции. Сравню годовую инфляцию по кварталам. "
                    + "Ищу официальную статистику.");
            out.search("инфляция в России по кварталам");
            out.text(DEMO_NOTE + "По демо-источнику годовая инфляция за четыре квартала "
                    + "замедлилась с 9,1 до 6,4 процента.");
            out.textDone(citation(1, "Демо-источник: индекс потребительских цен"));
            out.chart(new JSONObject()
                    .put("title", "Годовая инфляция по кварталам (демо-данные)")
                    .put("chart_type", "bar")
                    .put("x_labels", strings("I кв.", "II кв.", "III кв.", "IV кв."))
                    .put("series", new JSONArray().put(series("Инфляция", 9.1, 8.3, 7.2, 6.4)))
                    .put("y_unit", "%")
                    .put("source", "Демо-данные MIDS, не настоящие"));
            return out.finish("tool_use");
        }
        if (q.contains("курс") || q.contains("доллар") || q.contains("евро") || q.contains("валют")) {
            out.thinking("Вопрос о курсах валют. Возьму официальные курсы доллара и евро "
                    + "за несколько недель и сравню их на одном графике.");
            out.search("официальный курс доллара и евро");
            out.text(DEMO_NOTE + "По демо-источнику доллар за пять недель подорожал "
                    + "с 92,4 до 95,1 рубля");
            out.textDone(citation(1, "Демо-источник: официальные курсы валют"));
            out.text(", а евро — с 100,2 до 103,8 рубля.");
            out.textDone(citation(2, "Демо-источник: курсы на конец недели"));
            out.chart(new JSONObject()
                    .put("title", "Курсы доллара и евро (демо-данные)")
                    .put("chart_type", "line")
                    .put("x_labels", strings("Нед. 1", "Нед. 2", "Нед. 3", "Нед. 4", "Нед. 5"))
                    .put("series", new JSONArray()
                            .put(series("Доллар", 92.4, 93.0, 93.8, 94.6, 95.1))
                            .put(series("Евро", 100.2, 101.0, 102.1, 103.0, 103.8)))
                    .put("y_unit", "руб.")
                    .put("source", "Демо-данные MIDS, не настоящие"));
            return out.finish("tool_use");
        }

        out.thinking("Вопрос не про ставку, инфляцию или курсы. Объясню, что умеет демо-режим.");
        out.text("Сейчас MIDS работает в демо-режиме: настоящая модель не подключена, "
                + "и ответы придумываются прямо на телефоне.\n\n"
                + "Чтобы проверить разные части приложения, спроси:\n"
                + "— про ключевую ставку — будет поиск, источник и линейный график;\n"
                + "— про инфляцию — столбчатый график;\n"
                + "— про курс доллара или евро — график из двух линий и два источника;\n"
                + "— со словом «ошибка» — красное сообщение об ошибке;\n"
                + "— или прикрепи файл.");
        out.textDone(null);
        return out.finish("end_turn");
    }

    // ---------- Сценарии ----------

    private static void answerFiles(Response out, int[] files) throws IOException, JSONException {
        out.thinking("Пользователь приложил файлы. В демо-режиме я их не читаю, "
                + "но могу подтвердить, что они дошли и в каком виде.");
        StringBuilder sb = new StringBuilder(DEMO_NOTE).append("Файлы дошли до сетевого слоя:");
        if (files[0] > 0) sb.append("\n— изображений: ").append(files[0]);
        if (files[1] > 0) sb.append("\n— PDF-документов: ").append(files[1]);
        if (files[2] > 0) sb.append("\n— текстовых файлов: ").append(files[2]);
        sb.append("\n\nНастоящая модель прочитала бы их содержимое. Мок проверяет только, "
                + "что приложение правильно упаковало файлы в запрос.");
        out.text(sb.toString());
        out.textDone(null);
    }

    private static String afterChartText(String question) {
        if (question.contains("инфляц")) {
            return "На графике видно, что каждый квартал инфляция была ниже предыдущего. "
                    + "Напоминаю: это демо-данные для проверки интерфейса.";
        }
        if (question.contains("ставк")) {
            return "На графике видно плавное снижение ставки с паузами в мае и июле. "
                    + "Напоминаю: это демо-данные для проверки интерфейса.";
        }
        return "На графике видно, что обе валюты дорожали примерно с одной скоростью. "
                + "Напоминаю: это демо-данные для проверки интерфейса.";
    }

    // ---------- Разбор истории ----------

    private static boolean hasToolResult(JSONArray content) {
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && "tool_result".equals(b.optString("type"))) return true;
        }
        return false;
    }

    /** Текст вопроса — последний текстовый блок сообщения (перед ним идут файлы). */
    private static String questionText(JSONArray content) {
        String text = "";
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && "text".equals(b.optString("type"))) text = b.optString("text");
        }
        return text;
    }

    /** Последний вопрос пользователя, пропуская сообщения с результатами инструментов. */
    private static String lastQuestion(JSONArray messages) throws JSONException {
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.getJSONObject(i);
            if (!"user".equals(m.optString("role"))) continue;
            JSONArray content = m.getJSONArray("content");
            if (!hasToolResult(content)) return questionText(content);
        }
        return "";
    }

    /** @return {изображения, PDF, текстовые файлы} в сообщении */
    private static int[] countFiles(JSONArray content) {
        int[] counts = new int[3];
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            if ("image".equals(type)) counts[0]++;
            else if ("document".equals(type)) counts[1]++;
            else if ("text".equals(type) && b.optString("text").startsWith("Содержимое файла")) counts[2]++;
        }
        return counts;
    }

    // ---------- Маленькие помощники для JSON ----------

    private static JSONArray strings(String... values) {
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        return arr;
    }

    private static JSONObject series(String name, double... values) throws JSONException {
        JSONArray arr = new JSONArray();
        for (double v : values) arr.put(v);
        return new JSONObject().put("name", name).put("values", arr);
    }

    private static JSONObject citation(int n, String title) throws JSONException {
        return new JSONObject()
                .put("type", "web_search_result_location")
                .put("url", "https://example.com/mids-demo/source-" + n)
                .put("title", title)
                .put("cited_text", "");
    }

    /**
     * Собирает ответ так же, как его прислал бы сервер: шлёт события слушателю с паузами
     * и параллельно складывает блоки, которые потом попадут в историю диалога.
     */
    private static final class Response {
        private final StreamListener listener;
        private final JSONArray blocks = new JSONArray();
        /** Текущий текстовый блок: текст копится, пока не вызван textDone(). */
        private StringBuilder text;
        private int toolCounter;

        Response(StreamListener listener) {
            this.listener = listener;
        }

        void thinking(String s) throws IOException, JSONException {
            for (String piece : pieces(s)) {
                listener.onThinking(piece);
                pause(Config.MOCK_DELAY_MS / 2);
            }
            blocks.put(new JSONObject().put("type", "thinking").put("thinking", s)
                    .put("signature", "mock"));
        }

        void search(String query) throws IOException, JSONException {
            String id = "srvtoolu_mock_" + (++toolCounter);
            blocks.put(new JSONObject().put("type", "server_tool_use").put("id", id)
                    .put("name", "web_search").put("input", new JSONObject().put("query", query)));
            listener.onSearch(query);
            pause(Config.MOCK_DELAY_MS * 20); // «поиск» занимает заметное время
            blocks.put(new JSONObject().put("type", "web_search_tool_result")
                    .put("tool_use_id", id).put("content", new JSONArray()));
        }

        void text(String s) throws IOException {
            if (text == null) text = new StringBuilder();
            for (String piece : pieces(s)) {
                text.append(piece);
                listener.onText(piece);
                pause(Config.MOCK_DELAY_MS);
            }
        }

        /** Закрывает текстовый блок; citation — ссылка на источник или null. */
        void textDone(JSONObject citation) throws JSONException {
            if (text == null) return;
            JSONObject block = new JSONObject().put("type", "text").put("text", text.toString());
            JSONArray citations = null;
            if (citation != null) {
                citations = new JSONArray().put(citation);
                block.put("citations", citations);
            }
            blocks.put(block);
            listener.onTextBlockDone(citations);
            text = null;
        }

        void chart(JSONObject input) throws JSONException {
            blocks.put(new JSONObject().put("type", "tool_use")
                    .put("id", "toolu_mock_" + (++toolCounter))
                    .put("name", "draw_chart").put("input", input));
        }

        Result finish(String stopReason) throws JSONException {
            textDone(null);
            return new Result(blocks, stopReason);
        }

        /** Режет текст на слова вместе с пробелами — так он «печатается» по словам. */
        private static String[] pieces(String s) {
            return s.split("(?<=\\s)");
        }

        private static void pause(long ms) throws IOException {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("ответ прерван");
            }
        }
    }
}
