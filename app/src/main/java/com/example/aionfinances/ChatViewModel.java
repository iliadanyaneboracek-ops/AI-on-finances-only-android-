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
 * Хранит состояние чата и общается с моделью в фоновом потоке.
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
        public final JSONObject content;
        public final String name;

        public PendingFile(JSONObject content, String name) {
            this.content = content;
            this.name = name;
        }
    }

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

    /** Сетевой слой: мок, локальная модель в Ollama или Claude API — см. Config.BACKEND. */
    private final AiClient client = createClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private Listener listener;
    private boolean busy;
    private boolean privateChat;
    private JSONArray failedContent;
    private int failedRow = -1;

    public boolean isPrivateChat() { return privateChat; }
    public boolean canRetry() { return failedContent != null; }

    public void setPrivateChat(boolean enabled) {
        if (chatId == null && !busy) privateChat = enabled;
    }

    public void pickFiles(List<Uri> uris) {
        if (busy || uris.isEmpty()) return;
        setBusy(true);
        executor.execute(() -> {
            ContentResolver resolver = getApplication().getContentResolver();
            List<PendingFile> accepted = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            for (Uri uri : uris) {
                String name = AttachmentReader.displayName(resolver, uri);
                try {
                    accepted.add(new PendingFile(AttachmentReader.toContentBlock(resolver, uri, name, client), name));
                } catch (AiClient.ApiException | AttachmentReader.FileException e) {
                    issues.add(name + " · " + e.getMessage());
                } catch (IOException | JSONException | RuntimeException e) {
                    issues.add(name + " · Не удалось подготовить вложение: " + e.getMessage());
                }
            }
            mainThread.post(() -> {
                pendingFiles.addAll(accepted);
                setBusy(false);
                if (listener != null && !issues.isEmpty()) listener.onMessage(android.text.TextUtils.join("\n", issues));
            });
        });
    }

    public ChatViewModel(@NonNull Application application) {
        super(application);
        store = new ChatStore(application);
    }

    /** Создаёт реализацию AiClient, выбранную в Config.BACKEND. */
    private static AiClient createClient() {
        switch (Config.BACKEND) {
            case OLLAMA:
                return new OllamaClient(Config.OLLAMA_URLS, Config.OLLAMA_MODEL);
            case CLAUDE:
                return new ClaudeClient();
            default:
                return new MockAiClient();
        }
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
        privateChat = false;
        failedContent = null;
        failedRow = -1;
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
                    privateChat = false;
                    failedRow = chat.optInt("failedRow", -1);
                    failedContent = failedRow >= 0 && failedRow < loaded.size()
                            && loaded.get(failedRow).type == ChatItem.USER ? chat.optJSONArray("failedContent") : null;
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
        JSONArray content = new JSONArray();
        for (PendingFile f : files) content.put(f.content);
        try {
            content.put(new JSONObject().put("type", "text").put("text", prompt));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        failedContent = null;
        addItem(ChatItem.user(prompt, names));
        startRequest(content, apiKey, items.size() - 1);
    }

    public void retry(String apiKey) {
        if (busy || failedContent == null) return;
        while (items.size() > failedRow + 1) items.remove(items.size() - 1);
        if (listener != null) listener.onChatReplaced();
        startRequest(failedContent, apiKey, failedRow);
    }

    private void startRequest(JSONArray content, String apiKey, int row) {
        setBusy(true);
        executor.execute(() -> runRequest(history, content, apiKey, row));
    }

    private void runRequest(JSONArray h, JSONArray content, String apiKey, int row) {
        final int mark = h.length();
        final LiveAnswer live = new LiveAnswer();
        String failure = null;
        try {
            ChatRequest.execute(client, apiKey, h, content, live);
        } catch (AiClient.ApiException e) {
            failure = e.getMessage();
        } catch (IOException e) {
            failure = "Ошибка соединения с " + Config.BACKEND + ". " + e.getMessage();
        } catch (JSONException | RuntimeException e) {
            failure = "Не удалось обработать ответ: " + e.getMessage();
        }
        final String error = failure;
        if (error != null) rollback(h, mark);
        live.finish();
        mainThread.post(() -> {
            failedContent = error == null ? null : content;
            failedRow = error == null ? -1 : row;
            if (error != null) {
                while (items.size() > row + 1) items.remove(items.size() - 1);
                items.add(ChatItem.error("Не отправлено. " + error));
                if (listener != null) listener.onChatReplaced();
            }
            saveCurrentChat();
            setBusy(false);
        });
    }

    /**
     * Показывает ответ по мере генерации. Методы вызываются в фоновом потоке, а на экран
     * изменения отправляются через mainThread.post(...) — готовыми копиями текста.
     */
    private final class LiveAnswer implements ChatRequest.Listener {
        private final ChatItem thinking;
        private final StringBuilder thinkingText = new StringBuilder();
        /** false, пока в элементе размышлений стоит заглушка «Думаю…». */
        private boolean thinkingReal;

        private ChatItem answer;
        private StringBuilder answerText = new StringBuilder();
        private List<ChatItem.Source> sources = new ArrayList<>();
        private Map<String, Integer> numberByUrl = new HashMap<>();

        private long lastPush;

        public void onChart(ChartSpec spec) {
            mainThread.post(() -> addItem(ChatItem.chart(spec)));
        }

        public void onNotice(String text) { postError(text); }

        LiveAnswer() {
            thinking = ChatItem.thinking(getApplication().getString(R.string.thinking_placeholder));
            mainThread.post(() -> addItem(thinking));
        }

        /** Каждый запрос к API получает свой элемент ответа со своей нумерацией источников. */
        public void startRound() {
            answer = null;
            answerText = new StringBuilder();
            sources = new ArrayList<>();
            numberByUrl = new HashMap<>();
        }

        public void endRound() {
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
            addLine(getApplication().getString(R.string.search_line, query));
        }

        @Override
        public void onToolError(String message) {
            if (message.isEmpty()) return;
            addLine(getApplication().getString(R.string.tool_error_line, message));
        }

        /** Служебная строка в «Размышлениях» (поиск, ошибка инструмента) — отдельным абзацем. */
        private void addLine(String line) {
            thinkingReal = true;
            int length = thinkingText.length();
            boolean blankLineBefore = length >= 2 && thinkingText.charAt(length - 1) == '\n'
                    && thinkingText.charAt(length - 2) == '\n';
            if (length > 0 && !blankLineBefore) thinkingText.append("\n\n");
            thinkingText.append(line).append("\n\n");
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

    /** Записывает текущий чат в файл. Вызывается в главном потоке после каждого ответа. */
    private void saveCurrentChat() {
        if (privateChat || chatId == null) return;
        final String id = chatId;
        final String title = chatTitle;
        final JSONArray h = history;
        final JSONArray retryContent = failedContent;
        final int retryRow = failedRow;
        final JSONArray jsonItems = new JSONArray();
        try {
            for (ChatItem item : items) jsonItems.put(item.toJson());
        } catch (JSONException e) {
            return;
        }
        executor.execute(() -> {
            try {
                store.write(id, title, jsonItems, h, retryContent, retryRow);
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
