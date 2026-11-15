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
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Сетевой слой для локальной модели, которую запускает Ollama на компьютере.
 *
 * Ollama — бесплатная программа: она загружает модель в видеокарту и принимает запросы
 * по адресу http://компьютер:11434. Этот класс:
 *  1) переводит историю диалога из формата Claude (в нём её хранит приложение) в формат Ollama;
 *  2) отправляет запрос на /api/chat и читает ответ по частям: каждая строка ответа —
 *     отдельный JSON (формат NDJSON) с кусочком размышлений, текста или с вызовом инструмента;
 *  3) если модель вызвала инструмент данных (ключевая ставка, курсы), сам выполняет его,
 *     дописывает результат в разговор и снова спрашивает модель — пока она не ответит;
 *  4) вызов draw_chart отдаёт наверх, в ChatViewModel, как это делает Claude: график рисует экран.
 *
 * С Ollama соединяется напрямую, мимо прокси: она на этом же компьютере. Адресов может быть
 * несколько (Config.OLLAMA_URLS): если по первому соединиться не удалось, пробует следующий
 * и запоминает тот, что ответил.
 *
 * Для ChatViewModel всё выглядит так же, как с Claude: те же события и те же блоки ответа.
 * Вызовы инструментов данных сохраняются в истории блоками mids_tool_use и mids_tool_result,
 * чтобы в следующих вопросах модель помнила, какие данные уже получила. Такие блоки понимает
 * только этот класс, поэтому чат, начатый с Ollama, после переключения на Claude лучше не продолжать.
 *
 * Работает синхронно: вызывать только из фонового потока.
 */
public final class OllamaClient implements AiClient {

    /**
     * Типы блоков истории для вызова нашего инструмента и его результата. У Claude таких нет,
     * поэтому их не спутать с веб-поиском Claude (server_tool_use) и не принять за график (tool_use).
     */
    static final String CALL_BLOCK = "mids_tool_use";
    static final String RESULT_BLOCK = "mids_tool_result";

    /** Сколько раз подряд модель может вызвать инструменты данных за один запрос. */
    private static final int MAX_TOOL_ROUNDS = 6;

    /** Адреса Ollama в порядке из Config.OLLAMA_URLS. */
    private final String[] baseUrls;
    private final String model;


    /** Номер адреса, с которым соединились в прошлый раз: с него и начинаем. */
    private volatile int working;

    /**
     * Сколько ждать соединения, в миллисекундах. Ollama на этом же компьютере отвечает
     * мгновенно; если за 5 секунд соединения нет, его не будет: пора пробовать другой адрес.
     */
    int connectTimeoutMs = 5_000;

    /**
     * Сколько ждать очередного кусочка ответа. Бывают долгие паузы: модель загружается в
     * видеопамять (на первый вопрос уходит около минуты) или читает длинную историю.
     */
    int readTimeoutMs = 300_000;

    /** Что умеет модель: tools, thinking, vision… Узнаём один раз через /api/show. */
    private Set<String> capabilities;

    /**
     * @param baseUrls адреса Ollama по порядку, например {"http://10.0.2.2:11434"}
     * @param model    имя модели, как в команде ollama pull, например qwen3:14b
     */
    public OllamaClient(String[] baseUrls, String model) {
        this.baseUrls = baseUrls.clone();
        this.model = model;

    }

    /** То же для одного адреса. */
    public OllamaClient(String baseUrl, String model, FinanceTools tools) {
        this(new String[]{baseUrl}, model);
    }

    @Override
    public Result stream(String apiKey, JSONArray history, StreamListener listener)
            throws IOException, JSONException, ApiException {
        // Ключ API не нужен: Ollama работает на своём компьютере.
        try {
            return answer(history, listener);
        } catch (NotConnected e) {
            throw new ApiException(describe(e));
        } catch (SocketTimeoutException e) {
            // Соединение было, запрос ушёл, но Ollama замолчала.
            throw new ApiException("Ollama приняла запрос, но за " + duration(readTimeoutMs)
                    + " не прислала ни слова. Похоже, модель работает слишком медленно: например, "
                    + "не поместилась в видеопамять. Возьмите модель поменьше или уменьшите "
                    + "OLLAMA_CONTEXT в Config.java.");
        } catch (IOException e) {
            throw new ApiException("Связь с Ollama оборвалась: " + e.getMessage());
        }
    }

    private Result answer(JSONArray history, StreamListener listener)
            throws IOException, JSONException, ApiException {
        loadCapabilities();

        // Что уже известно из истории: все результаты инструментов (по ним ищем chart_id)
        // и источники текущего вопроса — их нужно показать под ответом.
        int question = lastQuestionIndex(history);
        List<JSONObject> results = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>(); // адрес → название
        Set<String> cited = new HashSet<>();
        scanHistory(history, question, results, sources, cited);

        JSONArray messages = toOllamaMessages(history, question);
        JSONArray blocks = new JSONArray(); // ответ в формате Claude: его сохранит ChatViewModel
        // Если продолжаем ответ после графика, новые размышления отделим от прежних.
        boolean thinkingGap = question < history.length() - 1;
        boolean wroteText = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Reply reply = chat(messages, listener, thinkingGap, wroteText);

            if (reply.thinking.length() > 0) {
                blocks.put(new JSONObject().put("type", "thinking")
                        .put("thinking", reply.thinking.toString()));
            }
            String text = reply.text.toString().trim();
            if (!text.isEmpty()) {
                JSONObject block = new JSONObject().put("type", "text").put("text", text);
                // Источники ставим к тексту, который написан после получения данных.
                JSONArray citations = newCitations(sources, cited);
                if (citations != null) block.put("citations", citations);
                blocks.put(block);
                listener.onTextBlockDone(citations);
                wroteText = true;
            }
            if (reply.calls.isEmpty()) {
                return new Result(blocks, "length".equals(reply.doneReason) ? "max_tokens" : "end_turn");
            }

            // Модель вызвала инструменты. Её сообщение — в разговор, следом — результаты.
            messages.put(assistantMessage(reply));
            JSONArray callBlocks = new JSONArray();
            JSONArray resultBlocks = new JSONArray();
            List<JSONObject> toolMessages = new ArrayList<>();
            boolean chartShown = false;
            boolean searched = false;

            // Сначала инструменты данных: draw_chart может сослаться на их результат.
            for (ToolCall call : reply.calls) {
                if (!MarketSource.TOOL.equals(call.name)) continue;
                MarketSource source = MarketSource.find(call.args);
                listener.onSearch(source == null ? call.args.optString("id") : source.title);
                searched = true;
                JSONObject result = source == null
                        ? FinanceTools.error("Выберите источник из каталога.") : source.read();
                if (result.has("error")) listener.onToolError(result.optString("error"));
                results.add(result);
                addSource(sources, result);
                callBlocks.put(callBlock(call));
                resultBlocks.put(resultBlock(call, result));
                toolMessages.add(toolMessage(call.name, call.id, result.toString()));
            }
            for (ToolCall call : reply.calls) {
                if (MarketSource.TOOL.equals(call.name)) continue;
                boolean isChart = FinanceTools.DRAW_CHART.equals(call.name);
                JSONObject spec = null;
                if (isChart) {
                    spec = call.args.has("series") ? call.args : chartFor(call.args, results);
                }
                if (spec != null) {
                    // График рисует экран: отдаём вызов в ChatViewModel, как это делает Claude.
                    callBlocks.put(new JSONObject().put("type", "tool_use").put("id", call.id)
                            .put("name", call.name).put("input", spec));
                    chartShown = true;
                    continue;
                }
                JSONObject error = FinanceTools.error(isChart
                        ? "Нет данных с chart_id «" + call.args.optString("chart_id") + "». Сначала "
                        + "используй реальные числовые ряды из материала или вложения. Старый график требует "
                        + "chart_id из его результата."
                        : "Нет такого инструмента: " + call.name + ".");
                callBlocks.put(callBlock(call));
                resultBlocks.put(resultBlock(call, error));
                toolMessages.add(toolMessage(call.name, call.id, error.toString()));
            }
            appendAll(blocks, callBlocks);
            appendAll(blocks, resultBlocks);

            // ChatViewModel покажет график, ответит «график показан» и вызовет нас снова.
            if (chartShown) return new Result(blocks, "tool_use");

            for (JSONObject m : toolMessages) messages.put(m);
            if (searched) thinkingGap = false; // строка «Поиск: …» уже отделила размышления
            else if (reply.thinking.length() > 0) thinkingGap = true;
        }
        throw new ApiException("Модель слишком много раз подряд вызвала инструменты и так и не "
                + "ответила. Попробуйте переформулировать вопрос.");
    }

    // ---------- Запрос к Ollama ----------

    private Reply chat(JSONArray messages, StreamListener listener, boolean thinkingGap,
                       boolean textGap) throws IOException, JSONException, ApiException {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", MarketSource.definitions())
                .put("stream", true)
                .put("keep_alive", Config.OLLAMA_KEEP_ALIVE)
                .put("options", new JSONObject().put("num_ctx", Config.OLLAMA_CONTEXT));
        // Без поля think модель, которая умеет размышлять, размышляет. false — отвечает сразу.
        if (!Config.OLLAMA_THINK) body.put("think", false);

        HttpURLConnection conn = connect("/api/chat", body);
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw new ApiException(modelNotFound());
            if (code >= 400) throw new ApiException(describeError(code, readError(conn)));
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return readReply(reader, listener, thinkingGap, textGap);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Ollama присылает ответ построчно, каждая строка — JSON с очередным кусочком:
     * {"message":{"role":"assistant","content":"","thinking":"Нужно узнать ставку…"},"done":false}
     * {"message":{"role":"assistant","content":"Ставка снизилась"},"done":false}
     * {"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1",
     *     "function":{"index":0,"name":"get_key_rate","arguments":{}}}]},"done":false}
     * {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
     */
    private static Reply readReply(BufferedReader reader, StreamListener listener,
                                   boolean thinkingGap, boolean textGap)
            throws IOException, JSONException, ApiException {
        Reply reply = new Reply();
        // Пробелы и переводы строк в конце текста придерживаем до следующего слова: иначе
        // номер источника [1], который допишет ChatViewModel, уехал бы на новую строку.
        StringBuilder spaces = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            JSONObject chunk = new JSONObject(line);
            if (chunk.has("error")) {
                throw new ApiException("Ollama прервала ответ: " + chunk.optString("error"));
            }
            JSONObject message = chunk.optJSONObject("message");
            if (message != null) {
                String thinking = message.optString("thinking");
                if (reply.thinking.length() == 0) thinking = stripLeading(thinking);
                if (!thinking.isEmpty()) {
                    if (reply.thinking.length() == 0 && thinkingGap) listener.onThinking("\n\n");
                    reply.thinking.append(thinking);
                    listener.onThinking(thinking);
                }
                String text = message.optString("content");
                if (reply.text.length() == 0) text = stripLeading(text);
                int end = text.length();
                while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
                if (end == 0) {
                    spaces.append(text); // одни пробелы: ждём следующего слова
                } else {
                    String piece = spaces + text.substring(0, end);
                    spaces.setLength(0);
                    spaces.append(text.substring(end));
                    // Новый кусок ответа после предыдущего отделяем пустой строкой.
                    if (reply.text.length() == 0 && textGap) listener.onText("\n\n");
                    reply.text.append(piece);
                    listener.onText(piece);
                }
                JSONArray calls = message.optJSONArray("tool_calls");
                for (int i = 0; calls != null && i < calls.length(); i++) {
                    JSONObject raw = calls.optJSONObject(i);
                    if (raw != null) reply.calls.add(ToolCall.parse(raw, reply.calls.size()));
                }
            }
            if (chunk.optBoolean("done")) {
                reply.doneReason = chunk.optString("done_reason");
                return reply;
            }
        }
        throw new IOException("Ollama закрыла соединение, не закончив ответ");
    }

    /** Что умеет модель. Без tools работать нельзя: модель не сможет получить данные ЦБ. */
    boolean supportsVision() throws IOException, JSONException, ApiException {
        loadCapabilities();
        return capabilities.contains("vision");
    }

    private void loadCapabilities() throws IOException, JSONException, ApiException {
        if (capabilities != null) return;
        Set<String> found = new HashSet<>();
        HttpURLConnection conn = connect("/api/show", new JSONObject().put("model", model));
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw new ApiException(modelNotFound());
            if (code >= 400) throw new ApiException(describeError(code, readError(conn)));
            JSONArray list = new JSONObject(readAll(conn.getInputStream())).optJSONArray("capabilities");
            if (list == null) {
                found.add("tools"); // старая версия Ollama не сообщает возможностей — просто пробуем
            } else {
                for (int i = 0; i < list.length(); i++) found.add(list.optString(i));
            }
        } finally {
            conn.disconnect();
        }
        if (!found.contains("tools")) throw new ApiException(noTools());
        capabilities = found;
    }

    // ---------- История: формат Claude → формат Ollama ----------

    /**
     * В Claude ответ — список блоков (размышления, текст, вызовы, результаты), а в Ollama —
     * отдельные сообщения: system, user, assistant (с полем tool_calls) и tool (результат).
     */
    private JSONArray toOllamaMessages(JSONArray history, int question) throws JSONException {
        JSONArray out = new JSONArray();
        out.put(new JSONObject().put("role", "system").put("content", SystemPrompt.buildOllama()));
        Map<String, String> toolNames = new HashMap<>(); // номер вызова → имя инструмента
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.getJSONObject(i);
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            if ("assistant".equals(message.optString("role"))) {
                // Размышления к прошлым вопросам модели не нужны, только к текущему.
                addAssistant(out, content, i > question, toolNames);
            } else {
                addUser(out, content, toolNames);
            }
        }
        return out;
    }

    private static void addAssistant(JSONArray out, JSONArray blocks, boolean keepThinking,
                                     Map<String, String> toolNames) throws JSONException {
        JSONObject message = null;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            String name = b.optString("name");
            if ("text".equals(type) || "thinking".equals(type)) {
                // Текст после вызовов инструментов — уже следующее сообщение модели.
                if (message == null || message.has("tool_calls")) {
                    flush(out, message);
                    message = new JSONObject().put("role", "assistant").put("content", "");
                }
                if ("text".equals(type)) appendField(message, "content", b.optString("text"));
                else if (keepThinking) appendField(message, "thinking", b.optString("thinking"));
            } else if ("tool_use".equals(type) || CALL_BLOCK.equals(type)) {
                if (message == null) message = new JSONObject().put("role", "assistant").put("content", "");
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls == null) {
                    calls = new JSONArray();
                    message.put("tool_calls", calls);
                }
                toolNames.put(b.optString("id"), name);
                calls.put(new JSONObject()
                        .put("id", b.optString("id"))
                        .put("function", new JSONObject()
                                .put("index", calls.length())
                                .put("name", name)
                                .put("arguments", modelArguments(b))));
            } else if (RESULT_BLOCK.equals(type)) {
                flush(out, message);
                message = null;
                out.put(toolMessage(name, b.optString("tool_use_id"), b.optString("content")));
            }
            // Остальные блоки (например, веб-поиск из чата с Claude) локальной модели не нужны.
        }
        flush(out, message);
    }

    private void addUser(JSONArray out, JSONArray blocks, Map<String, String> toolNames)
            throws JSONException {
        StringBuilder text = new StringBuilder();
        JSONArray images = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            if ("tool_result".equals(type)) {
                // Ответ ChatViewModel на draw_chart: «График показан пользователю».
                String id = b.optString("tool_use_id");
                String name = toolNames.containsKey(id) ? toolNames.get(id) : "";
                out.put(toolMessage(name, id, resultText(b)));
            } else if ("text".equals(type)) {
                appendParagraph(text, limit(b.optString("text")));
            } else if ("image".equals(type)) {
                JSONObject source = b.optJSONObject("source");
                if (capabilities.contains("vision") && source != null) {
                    images.put(source.optString("data"));
                } else {
                    appendParagraph(text, "[Пользователь приложил изображение, но модель " + model
                            + " не видит картинок. Скажи ему об этом.]");
                }
            } else if ("document".equals(type)) {
                appendParagraph(text, "[Пользователь приложил PDF-файл, но локальная модель пока не "
                        + "читает PDF. Попроси прислать текст или таблицу CSV.]");
            }
        }
        if (text.length() > 0 || images.length() > 0) {
            JSONObject message = new JSONObject().put("role", "user").put("content", text.toString());
            if (images.length() > 0) message.put("images", images);
            out.put(message);
        }
    }

    /** Для draw_chart в историю пишем то, что прислала модель, а не развёрнутые данные графика. */
    private static JSONObject modelArguments(JSONObject block) throws JSONException {
        JSONObject input = block.optJSONObject("input");
        if (input == null) return new JSONObject();
        if (FinanceTools.DRAW_CHART.equals(block.optString("name")) && input.has("chart_id")) {
            return new JSONObject()
                    .put("chart_id", input.optString("chart_id"))
                    .put("title", input.optString("title"));
        }
        return input;
    }

    private static JSONObject assistantMessage(Reply reply) throws JSONException {
        JSONObject message = new JSONObject()
                .put("role", "assistant")
                .put("content", reply.text.toString());
        if (reply.thinking.length() > 0) message.put("thinking", reply.thinking.toString());
        JSONArray calls = new JSONArray();
        for (int i = 0; i < reply.calls.size(); i++) {
            ToolCall call = reply.calls.get(i);
            calls.put(new JSONObject()
                    .put("id", call.id)
                    .put("function", new JSONObject()
                            .put("index", i)
                            .put("name", call.name)
                            .put("arguments", call.args)));
        }
        return message.put("tool_calls", calls);
    }

    private static JSONObject toolMessage(String name, String id, String content)
            throws JSONException {
        return new JSONObject()
                .put("role", "tool")
                .put("tool_name", name)
                .put("tool_call_id", id)
                .put("content", content);
    }

    private static JSONObject callBlock(ToolCall call) throws JSONException {
        return new JSONObject().put("type", CALL_BLOCK).put("id", call.id)
                .put("name", call.name).put("input", call.args);
    }

    private static JSONObject resultBlock(ToolCall call, JSONObject result) throws JSONException {
        return new JSONObject().put("type", RESULT_BLOCK).put("tool_use_id", call.id)
                .put("name", call.name).put("content", result.toString());
    }

    /** Номер последнего сообщения пользователя с вопросом (а не с результатом инструмента). */
    private static int lastQuestionIndex(JSONArray history) {
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject message = history.optJSONObject(i);
            if (message == null || !"user".equals(message.optString("role"))) continue;
            JSONArray content = message.optJSONArray("content");
            for (int k = 0; content != null && k < content.length(); k++) {
                JSONObject b = content.optJSONObject(k);
                if (b != null && !"tool_result".equals(b.optString("type"))) return i;
            }
        }
        return -1;
    }

    /** Собирает из истории результаты инструментов и уже показанные источники. */
    private static void scanHistory(JSONArray history, int question, List<JSONObject> results,
                                    Map<String, String> sources, Set<String> cited) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            JSONArray blocks = message.optJSONArray("content");
            for (int k = 0; blocks != null && k < blocks.length(); k++) {
                JSONObject b = blocks.optJSONObject(k);
                if (b == null) continue;
                if (RESULT_BLOCK.equals(b.optString("type"))) {
                    try {
                        JSONObject result = new JSONObject(b.optString("content"));
                        results.add(result);
                        if (i > question) addSource(sources, result);
                    } catch (JSONException ignored) {
                        // повреждённый результат просто пропускаем
                    }
                } else if (i > question && "text".equals(b.optString("type"))) {
                    JSONArray citations = b.optJSONArray("citations");
                    for (int c = 0; citations != null && c < citations.length(); c++) {
                        JSONObject citation = citations.optJSONObject(c);
                        if (citation != null) cited.add(citation.optString("url"));
                    }
                }
            }
        }
    }

    // ---------- Источники и графики ----------

    private static void addSource(Map<String, String> sources, JSONObject result) {
        String url = result.optString("url");
        if (!url.isEmpty() && !sources.containsKey(url)) sources.put(url, result.optString("source", url));
    }

    /** Источники текущего вопроса, которые ещё не показаны, или null. */
    private static JSONArray newCitations(Map<String, String> sources, Set<String> cited)
            throws JSONException {
        JSONArray citations = null;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (cited.contains(e.getKey())) continue;
            if (citations == null) citations = new JSONArray();
            citations.put(new JSONObject()
                    .put("type", "mids_source")
                    .put("url", e.getKey())
                    .put("title", e.getValue()));
            cited.add(e.getKey());
        }
        return citations;
    }

    /** Описание графика по chart_id. Маленькие модели иногда пишут «1» или «chart1» — поймём и так. */
    private static JSONObject chartFor(JSONObject args, List<JSONObject> results)
            throws JSONException {
        String wanted = args.optString("chart_id").trim();
        String digits = wanted.replaceAll("\\D", "");
        for (int i = results.size() - 1; i >= 0; i--) {
            JSONObject result = results.get(i);
            String id = result.optString("chart_id");
            if (id.isEmpty()) continue;
            if (id.equals(wanted) || (!digits.isEmpty() && id.equals("chart_" + digits))) {
                JSONObject spec = FinanceTools.chartSpec(result, args.optString("title"));
                if (spec != null) spec.put("chart_id", id);
                return spec;
            }
        }
        return null;
    }

    // ---------- Ошибки ----------

    private String modelNotFound() {
        String installed = installedModels();
        return "Модель «" + model + "» не скачана в Ollama. На компьютере выполните в терминале: "
                + "ollama pull " + model + (installed.isEmpty() ? "." : ". Уже скачаны: " + installed
                + " — любую из них можно указать в Config.OLLAMA_MODEL.");
    }

    private String noTools() {
        return "Модель «" + model + "» не умеет вызывать инструменты (tools), а без них MIDS не "
                + "может читать рыночные источники. Скачайте модель с пометкой tools, например: "
                + "ollama pull qwen3:14b, и укажите её в Config.OLLAMA_MODEL.";
    }

    /** Имена скачанных моделей через запятую или "", если узнать не удалось. */
    private String installedModels() {
        try {
            HttpURLConnection conn = connect("/api/tags", null);
            try {
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return "";
                JSONArray models = new JSONObject(readAll(conn.getInputStream())).optJSONArray("models");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; models != null && i < models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(m.optString("name"));
                }
                return sb.toString();
            } finally {
                conn.disconnect();
            }
        } catch (IOException | JSONException e) {
            return "";
        }
    }

    private String describeError(int code, String body) {
        String detail = body;
        try {
            detail = new JSONObject(body).optString("error", body);
        } catch (JSONException ignored) {
            // тело ответа не в формате JSON — покажем как есть
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("does not support tools")) return noTools();
        if (lower.contains("memory") || lower.contains("cuda")) {
            return "Модели не хватает памяти. Закройте лишние программы, уменьшите OLLAMA_CONTEXT "
                    + "в Config.java или возьмите модель поменьше. (Ollama: " + detail + ")";
        }
        return "Ollama вернула ошибку (код " + code + "): " + detail;
    }

    // ---------- Сеть и строки ----------

    /**
     * Открывает соединение с Ollama; body == null — запрос GET, иначе POST с JSON.
     * Адреса пробует по очереди, начиная с того, что сработал в прошлый раз. К следующему
     * адресу переходит, только если с предыдущим не удалось даже соединиться: значит,
     * запрос туда точно не ушёл и повторить его безопасно.
     */
    private HttpURLConnection connect(String path, JSONObject body) throws IOException {
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        NotConnected failure = new NotConnected();
        int start = working;
        for (int i = 0; i < baseUrls.length; i++) {
            int index = (start + i) % baseUrls.length;
            HttpURLConnection conn;
            try {
                conn = open(baseUrls[index] + path, payload);
            } catch (IOException e) {
                failure.causes.put(baseUrls[index], e);
                continue;
            }
            working = index;
            if (payload == null) return conn;
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
                return conn;
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
        }
        throw failure;
    }

    /**
     * Соединяется с адресом напрямую (Proxy.NO_PROXY): прокси, например от VPN, искал бы
     * этот адрес у себя и Ollama бы не нашёл.
     */
    private HttpURLConnection open(String url, byte[] payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            if (payload != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
            }
            conn.connect();
            return conn;
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
    }

    /** Ни с одним адресом не удалось соединиться: запрос до Ollama не дошёл. */
    private static final class NotConnected extends IOException {
        /** Адрес → что помешало соединиться. */
        final Map<String, IOException> causes = new LinkedHashMap<>();

        NotConnected() {
            super("Не удалось соединиться с Ollama");
        }
    }

    /** Объясняет, почему не удалось соединиться, и что с этим сделать. */
    private String describe(NotConnected failure) {
        for (Map.Entry<String, IOException> entry : failure.causes.entrySet()) {
            if (String.valueOf(entry.getValue().getMessage()).contains("CLEARTEXT")) {
                return "Android запретил незашифрованное соединение с " + entry.getKey()
                        + ". Добавьте этот адрес в res/xml/network_security_config.xml.";
            }
        }
        // Причину называем по главному адресу — первому в списке.
        String url = baseUrls[0];
        IOException cause = failure.causes.get(url);
        if (cause == null) cause = failure.causes.values().iterator().next();
        String detail = " Техническая причина: " + cause.getMessage();
        String tunnel = "выполните на компьютере команду adb reverse tcp:11434 tcp:11434";
        if (baseUrls.length > 1) {
            tunnel += ": приложение само подключится через туннель adb по адресу "
                    + baseUrls[baseUrls.length - 1];
        }
        if (cause instanceof SocketTimeoutException) {
            // Компьютер молчит, а не отказывает: соединение перехватили по дороге.
            return "Запрос не дошёл до Ollama: по адресу " + url + " за " + duration(connectTimeoutMs)
                    + " никто не ответил. Обычно соединение эмулятора перехватывает прокси или VPN. "
                    + "Выберите в настройках эмулятора «No proxy» (Extended Controls → Settings → "
                    + "Proxy) или " + tunnel + "." + detail;
        }
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException) {
            return "Не удалось подключиться к Ollama по адресу " + url + ". Проверьте, что Ollama "
                    + "запущена на компьютере (значок ламы рядом с часами). Адрес 10.0.2.2 работает "
                    + "только в эмуляторе на этом же компьютере; для телефона по USB " + tunnel + "."
                    + detail;
        }
        return "Не удалось подключиться к Ollama по адресу " + url + "." + detail;
    }

    /** «5 секунд», «5 минут»: срок словами для сообщений об ошибках. */
    private static String duration(int ms) {
        if (ms >= 60_000 && ms % 60_000 == 0) return plural(ms / 60_000, "минуту", "минуты", "минут");
        return plural(ms / 1000, "секунду", "секунды", "секунд");
    }

    /** Число со словом в нужной форме: 1 минуту, 2 минуты, 5 минут. */
    private static String plural(int n, String one, String few, String many) {
        int tens = n % 100;
        int ones = n % 10;
        String word = tens >= 11 && tens <= 14 ? many
                : ones == 1 ? one
                : ones >= 2 && ones <= 4 ? few
                : many;
        return n + " " + word;
    }

    private static String readError(HttpURLConnection conn) throws IOException {
        InputStream err = conn.getErrorStream();
        return err == null ? "" : readAll(err);
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

    private static String resultText(JSONObject toolResult) {
        Object content = toolResult.opt("content");
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) appendParagraph(sb, part.optString("text"));
            }
            return sb.toString();
        }
        return content == null ? "" : String.valueOf(content);
    }

    /** Длинный файл обрезаем: модель видит не больше Config.OLLAMA_CONTEXT токенов за раз. */
    private static String limit(String text) {
        int max = Config.OLLAMA_CONTEXT * 2; // примерно половина контекста: токен — 3–4 буквы
        if (text.length() <= max) return text;
        return text.substring(0, max) + "\n[текст обрезан: локальная модель видит не больше "
                + max + " знаков за раз]";
    }

    private static void appendParagraph(StringBuilder sb, String text) {
        if (text.isEmpty()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(text);
    }

    private static void appendField(JSONObject message, String key, String text)
            throws JSONException {
        if (text.isEmpty()) return;
        String old = message.optString(key);
        message.put(key, old.isEmpty() ? text : old + "\n\n" + text);
    }

    /** Добавляет сообщение модели, если в нём что-то есть. */
    private static void flush(JSONArray out, JSONObject message) {
        if (message == null) return;
        if (message.optString("content").isEmpty() && !message.has("tool_calls")
                && !message.has("thinking")) return;
        out.put(message);
    }

    private static void appendAll(JSONArray target, JSONArray items) {
        for (int i = 0; i < items.length(); i++) target.put(items.opt(i));
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    /** Один ответ модели: размышления, текст и вызовы инструментов. */
    private static final class Reply {
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> calls = new ArrayList<>();
        String doneReason = "";
    }

    /** Вызов инструмента: номер вызова, имя инструмента и параметры. */
    private static final class ToolCall {
        final String id;
        final String name;
        final JSONObject args;

        private ToolCall(String id, String name, JSONObject args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }

        static ToolCall parse(JSONObject raw, int index) {
            JSONObject function = raw.optJSONObject("function");
            if (function == null) function = new JSONObject();
            String id = raw.optString("id");
            if (id.isEmpty()) id = "call_" + System.currentTimeMillis() + "_" + index;
            // Параметры обычно приходят объектом, но на всякий случай понимаем и строку с JSON.
            Object a = function.opt("arguments");
            JSONObject args = null;
            if (a instanceof JSONObject) {
                args = (JSONObject) a;
            } else if (a instanceof String) {
                try {
                    args = new JSONObject((String) a);
                } catch (JSONException ignored) {
                    // не JSON — считаем, что параметров нет
                }
            }
            return new ToolCall(id, function.optString("name"), args == null ? new JSONObject() : args);
        }
    }
}
