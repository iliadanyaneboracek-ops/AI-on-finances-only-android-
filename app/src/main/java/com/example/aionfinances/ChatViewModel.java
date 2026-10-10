package com.example.aionfinances;

import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Хранит состояние чата и общается с Claude в фоновом потоке.
 * ViewModel переживает поворот экрана, поэтому переписка при повороте не теряется.
 *
 * Правило потоков: список items и поля элементов меняются только в главном потоке
 * (потоке интерфейса); история history — только в фоновом, пока идёт запрос.
 */
public class ChatViewModel extends AndroidViewModel {

    /** Экран подписывается на эти события, чтобы обновлять список. */
    public interface Listener {
        void onItemAdded(int index);

        void onItemChanged(int index);

        void onItemRemoved(int index);

        /** Лента заменена целиком: новый чат или загружен сохранённый. */
        void onChatReplaced();

        void onBusyChanged(boolean busy);

        void onMessage(String text);
    }

    /** Файл, выбранный пользователем, но ещё не отправленный. */
    public static final class PendingFile {
        public final Uri uri;
        public final String name;

        public PendingFile(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    /** Сколько раз подряд модель может вызвать инструменты в одном ответе. */
    private static final int MAX_ROUNDS = 8;
    /** Как часто (в миллисекундах) обновлять текст на экране, пока он печатается. */
    private static final long PUSH_INTERVAL_MS = 70;

    public final List<ChatItem> items = new ArrayList<>();
    public final List<PendingFile> pendingFiles = new ArrayList<>();
    public final ChatStore store;

    /** История диалога в формате API. */
    private JSONArray history = new JSONArray();
    /** Идентификатор текущего чата; null, пока в нём нет сообщений. */
    private String chatId;
    private String chatTitle = "";

    private final ClaudeClient client = new ClaudeClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private Listener listener;
    private boolean busy;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        store = new ChatStore(application);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isBusy() {
        return busy;
    }

    /** Начинает новый диалог. Нельзя вызывать, пока идёт ответ. */
    public void clearChat() {
        if (busy) return;
        items.clear();
        pendingFiles.clear();
        history = new JSONArray();
        chatId = null;
        chatTitle = "";
        if (listener != null) listener.onChatReplaced();
    }

    /** Удаляет текущий чат из памяти телефона и открывает пустой. */
    public void deleteCurrentChat() {
        if (busy) return;
        final String id = chatId;
        if (id != null) executor.execute(() -> store.delete(id));
        clearChat();
    }

    /** Загружает сохранённый чат. */
    public void loadChat(String id) {
        if (busy) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                JSONObject chat = store.read(id);
                JSONArray jsonItems = chat.getJSONArray("items");
                final List<ChatItem> loaded = new ArrayList<>();
                for (int i = 0; i < jsonItems.length(); i++) {
                    loaded.add(ChatItem.fromJson(jsonItems.getJSONObject(i)));
                }
                final JSONArray loadedHistory = chat.getJSONArray("history");
                final String title = chat.optString("title");
                mainThread.post(() -> {
                    items.clear();
                    items.addAll(loaded);
                    pendingFiles.clear();
                    history = loadedHistory;
                    chatId = id;
                    chatTitle = title;
                    if (listener != null) listener.onChatReplaced();
                    setBusy(false);
                });
            } catch (IOException | JSONException | RuntimeException e) {
                mainThread.post(() -> {
                    setBusy(false);
                    if (listener != null) listener.onMessage("Не удалось открыть чат");
                });
            }
        });
    }

    public void send(String text, String apiKey) {
        if (busy) return;
        final List<PendingFile> files = new ArrayList<>(pendingFiles);
        pendingFiles.clear();

        List<String> names = new ArrayList<>();
        for (PendingFile f : files) names.add(f.name);
        final String prompt = text.isEmpty()
                ? getApplication().getString(R.string.default_file_prompt) : text;
        if (chatId == null) {
            chatId = String.valueOf(System.currentTimeMillis());
            chatTitle = prompt.length() > 48 ? prompt.substring(0, 48) + "…" : prompt;
        }
        addItem(ChatItem.user(prompt, names));
        setBusy(true);

        final JSONArray h = history;
        executor.execute(() -> runRequest(h, prompt, files, apiKey));
    }

    /** Выполняется в фоновом потоке. */
    private void runRequest(JSONArray h, String prompt, List<PendingFile> files, String apiKey) {
        // Запоминаем длину истории: при ошибке откатимся к ней, чтобы история осталась целой.
        final int mark = h.length();
        final LiveAnswer live = new LiveAnswer();
        try {
            ContentResolver cr = getApplication().getContentResolver();
            JSONArray content = new JSONArray();
            for (PendingFile f : files) {
                content.put(AttachmentReader.toContentBlock(cr, f.uri, f.name));
            }
            content.put(new JSONObject().put("type", "text").put("text", prompt));
            h.put(new JSONObject().put("role", "user").put("content", content));

            for (int round = 0; round < MAX_ROUNDS; round++) {
                live.startRound();
                ClaudeClient.Result result = client.stream(apiKey, h, live);
                live.endRound();
                if (result.content.length() == 0) break;
                h.put(new JSONObject().put("role", "assistant").put("content", result.content));

                JSONArray toolResults = handleTools(result.content);

                if ("tool_use".equals(result.stopReason)) {
                    // Модель вызвала наш инструмент и ждёт результат, чтобы продолжить ответ.
                    h.put(new JSONObject().put("role", "user").put("content", toolResults));
                    continue;
                }
                if ("pause_turn".equals(result.stopReason)) {
                    // Сервер приостановил долгий поиск: повторяем запрос, и модель продолжит.
                    continue;
                }
                if ("max_tokens".equals(result.stopReason)) {
                    postError("Ответ обрезан: достигнут предел длины. Попросите продолжить.");
                }
                break;
            }
        } catch (AttachmentReader.FileException | ClaudeClient.ApiException e) {
            rollback(h, mark);
            postError(e.getMessage());
        } catch (IOException e) {
            rollback(h, mark);
            postError("Нет связи с сервером Claude. Проверьте интернет и VPN. Подробности: "
                    + e.getMessage());
        } catch (JSONException | RuntimeException e) {
            rollback(h, mark);
            postError("Не удалось обработать ответ: " + e.getMessage());
        } finally {
            live.finish();
            mainThread.post(() -> {
                setBusy(false);
                saveCurrentChat();
            });
        }
    }

    /**
     * Показывает ответ по мере генерации. Методы вызываются в фоновом потоке, а на экран
     * изменения отправляются через mainThread.post(...) — готовыми копиями текста.
     */
    private final class LiveAnswer implements ClaudeClient.StreamListener {
        private final ChatItem thinking;
        private final StringBuilder thinkingText = new StringBuilder();
        /** false, пока в элементе размышлений стоит заглушка «Думаю…». */
        private boolean thinkingReal;

        private ChatItem answer;
        private StringBuilder answerText = new StringBuilder();
        private List<ChatItem.Source> sources = new ArrayList<>();
        private Map<String, Integer> numberByUrl = new HashMap<>();

        private long lastPush;

        LiveAnswer() {
            thinking = ChatItem.thinking(getApplication().getString(R.string.thinking_placeholder));
            mainThread.post(() -> addItem(thinking));
        }

        /** Каждый запрос к API получает свой элемент ответа со своей нумерацией источников. */
        void startRound() {
            answer = null;
            answerText = new StringBuilder();
            sources = new ArrayList<>();
            numberByUrl = new HashMap<>();
        }

        void endRound() {
            pushThinking(true);
            pushAnswer(true);
        }

        @Override
        public void onThinking(String delta) {
            thinkingReal = true;
            thinkingText.append(delta);
            pushThinking(false);
        }

        @Override
        public void onSearch(String query) {
            if (query.isEmpty()) return;
            thinkingReal = true;
            if (thinkingText.length() > 0) thinkingText.append("\n\n");
            thinkingText.append(getApplication().getString(R.string.search_line, query)).append("\n\n");
            pushThinking(true);
        }

        @Override
        public void onText(String delta) {
            if (answer == null) {
                // Убираем пустые строки в начале ответа.
                int start = 0;
                while (start < delta.length() && Character.isWhitespace(delta.charAt(start))) start++;
                delta = delta.substring(start);
                if (delta.isEmpty()) return;
                pushThinking(true);
                final ChatItem created = ChatItem.assistant("");
                answer = created;
                mainThread.post(() -> addItem(created));
            }
            answerText.append(delta);
            pushAnswer(false);
        }

        @Override
        public void onTextBlockDone(JSONArray citations) {
            if (citations == null || answer == null) return;
            // Нумеруем источники и ставим номера [1], [2] после фрагмента, который на них опирается.
            Set<Integer> numbers = new LinkedHashSet<>();
            for (int i = 0; i < citations.length(); i++) {
                JSONObject c = citations.optJSONObject(i);
                if (c == null) continue;
                String url = c.optString("url");
                if (url.isEmpty()) continue;
                Integer n = numberByUrl.get(url);
                if (n == null) {
                    n = sources.size() + 1;
                    numberByUrl.put(url, n);
                    sources.add(new ChatItem.Source(c.optString("title", url), url));
                }
                numbers.add(n);
            }
            for (int n : numbers) answerText.append(" [").append(n).append("]");

            final ChatItem item = answer;
            final String text = answerText.toString();
            final List<ChatItem.Source> copy = new ArrayList<>(sources);
            mainThread.post(() -> {
                item.text = text;
                item.sources.clear();
                item.sources.addAll(copy);
                notifyChanged(item);
            });
        }

        /** Конец всего ответа: сворачиваем размышления или убираем заглушку. */
        void finish() {
            endRound();
            final boolean real = thinkingReal;
            mainThread.post(() -> {
                if (real) {
                    thinking.text = thinking.text.trim();
                    thinking.expanded = false;
                    notifyChanged(thinking);
                } else {
                    int index = items.indexOf(thinking);
                    if (index >= 0) {
                        items.remove(index);
                        if (listener != null) listener.onItemRemoved(index);
                    }
                }
            });
        }

        private void pushThinking(boolean force) {
            if (!thinkingReal) return;
            push(thinking, thinkingText, force);
        }

        private void pushAnswer(boolean force) {
            if (answer != null) push(answer, answerText, force);
        }

        /** Обновляет текст на экране, но не чаще чем раз в PUSH_INTERVAL_MS. */
        private void push(ChatItem item, StringBuilder text, boolean force) {
            long now = SystemClock.uptimeMillis();
            if (!force && now - lastPush < PUSH_INTERVAL_MS) return;
            lastPush = now;
            final String snapshot = text.toString();
            mainThread.post(() -> {
                item.text = snapshot;
                notifyChanged(item);
            });
        }
    }

    /**
     * Находит в ответе вызовы инструмента draw_chart, показывает графики
     * и готовит для модели результаты вызовов.
     */
    private JSONArray handleTools(JSONArray blocks) throws JSONException {
        JSONArray toolResults = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (!"tool_use".equals(block.optString("type"))) continue;

            JSONObject result = new JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", block.getString("id"));
            if ("draw_chart".equals(block.optString("name"))) {
                try {
                    final ChartSpec spec = ChartSpec.fromJson(block.getJSONObject("input"));
                    mainThread.post(() -> addItem(ChatItem.chart(spec)));
                    result.put("content", "График показан пользователю.");
                } catch (JSONException e) {
                    result.put("content", "График не построен: данные заданы неверно.");
                    result.put("is_error", true);
                }
            } else {
                result.put("content", "Неизвестный инструмент.");
                result.put("is_error", true);
            }
            toolResults.put(result);
        }
        return toolResults;
    }

    /** Записывает текущий чат в файл. Вызывается в главном потоке после каждого ответа. */
    private void saveCurrentChat() {
        if (chatId == null) return;
        final String id = chatId;
        final String title = chatTitle;
        final JSONArray h = history;
        final JSONArray jsonItems = new JSONArray();
        try {
            for (ChatItem item : items) jsonItems.put(item.toJson());
        } catch (JSONException e) {
            return;
        }
        executor.execute(() -> {
            try {
                store.write(id, title, jsonItems, h);
            } catch (IOException | JSONException | RuntimeException e) {
                mainThread.post(() -> {
                    if (listener != null) listener.onMessage("Не удалось сохранить чат");
                });
            }
        });
    }

    private static void rollback(JSONArray h, int mark) {
        while (h.length() > mark) h.remove(h.length() - 1);
    }

    private void postError(String message) {
        mainThread.post(() -> addItem(ChatItem.error(message)));
    }

    private void addItem(ChatItem item) {
        items.add(item);
        if (listener != null) listener.onItemAdded(items.size() - 1);
    }

    private void notifyChanged(ChatItem item) {
        int index = items.indexOf(item);
        if (index >= 0 && listener != null) listener.onItemChanged(index);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (listener != null) listener.onBusyChanged(value);
    }

    @Override
    protected void onCleared() {
        listener = null;
        executor.shutdown();
    }
}
