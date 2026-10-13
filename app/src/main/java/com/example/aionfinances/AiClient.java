package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;

/**
 * Сетевой слой приложения: «отправить историю диалога и получить ответ по частям».
 *
 * Это интерфейс — договорённость о том, какие методы есть, без их кода. Реализаций две:
 * {@link MockAiClient} (подделка без интернета, для разработки) и {@link ClaudeClient}
 * (настоящий Claude API). Какая используется, решает {@link Config#USE_MOCK}.
 * Остальной код знает только этот интерфейс, поэтому замена одной реализации на другую
 * не требует правок в ChatViewModel и на экране.
 */
public interface AiClient {

    /**
     * @param apiKey   ключ API (мок его не проверяет)
     * @param messages история диалога в формате Claude API
     * @param listener сюда приходят кусочки ответа по мере «генерации»
     */
    Result stream(String apiKey, JSONArray messages, StreamListener listener)
            throws IOException, JSONException, ApiException;

    /** Ошибка, текст которой можно показать пользователю. */
    final class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    /** Сюда приходят кусочки ответа по мере генерации. Вызывается в фоновом потоке. */
    interface StreamListener {
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
    final class Result {
        public final JSONArray content;
        public final String stopReason;

        public Result(JSONArray content, String stopReason) {
            this.content = content;
            this.stopReason = stopReason;
        }
    }
}
