package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;

/** Правила выполнения одного запроса, включая продолжение диалога после вызова инструмента. */
final class ChatRequest {
    private static final int MAX_ROUNDS = 8;

    interface Listener extends AiClient.StreamListener {
        void startRound();
        void endRound();
        void onChart(ChartSpec spec);
        void onNotice(String text);
    }

    static void execute(AiClient client, String apiKey, JSONArray h, JSONArray content, Listener live)
            throws JSONException, IOException, AiClient.ApiException {
        h.put(new JSONObject().put("role", "user").put("content", content));
        for (int round = 0; round < MAX_ROUNDS; round++) {
            live.startRound();
            AiClient.Result result = client.stream(apiKey, h, live);
            live.endRound();
            if (result.content.length() == 0) throw new AiClient.ApiException("Модель вернула пустой ответ.");
            h.put(new JSONObject().put("role", "assistant").put("content", result.content));

            JSONArray toolResults = handleTools(result.content, live);

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
                live.onNotice("Ответ обрезан: достигнут предел длины. Попросите продолжить.");
            }
            return;
        }
        throw new AiClient.ApiException("Достигнут предел вызовов инструментов.");
    }

    /**
     * Находит в ответе вызовы инструмента draw_chart, показывает графики
     * и готовит для модели результаты вызовов.
     */
    private static JSONArray handleTools(JSONArray blocks, Listener live) throws JSONException {
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
                    live.onChart(spec);
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

}
