package com.example.aionfinances;

import android.text.Html;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

enum MarketSource {
    IMOEX("Московская биржа · IMOEX","https://www.moex.com/ru/index/IMOEX","Индекс МосБиржи",
            "Официальная страница индекса IMOEX. Значений индекса в сохранённой справке нет."),
    NDX("Nasdaq · Nasdaq-100","https://indexes.nasdaqomx.com/Index/Overview/NDX","The Nasdaq-100 Index includes",
            "Индекс охватывает крупнейшие нефинансовые компании, акции которых торгуются на Nasdaq."),
    IVV("iShares · IVV","https://www.ishares.com/us/products/239726/ishares-core-sp-500-etf","The iShares Core S&P 500 ETF seeks",
            "IVV следует индексу S&P 500 и даёт доступ к акциям крупных компаний США."),
    AGG("iShares · AGG","https://www.ishares.com/us/products/239458/ishares-core-total-us-bond-market-etf","The iShares Core U.S. Aggregate Bond ETF seeks",
            "AGG следует индексу американского рынка облигаций инвестиционного качества."),
    BUFFETT("Уоррен Баффет · письмо Berkshire Hathaway за 1977 год","https://www.berkshirehathaway.com/letters/1977.html","We select our marketable equity",
            "В письме за 1977 год Баффет описывает отбор акций: понятный бизнес, долгосрочные перспективы, честное и компетентное руководство и привлекательная цена."),
    MARKS("Говард Маркс · Is It a Bubble?, 9 декабря 2025","https://www.oaktreecapital.com/insights/memo/is-it-a-bubble","Ours is a remarkable moment",
            "В записке от 9 декабря 2025 года Маркс рассматривает ажиотаж вокруг ИИ. По его мнению, страх упустить рост может вытеснить оценку цены и риска.");

    static final String TOOL = "read_market_source";
    final String title;
    final String url;
    final String marker;
    final String summary;

    MarketSource(String title, String url, String marker, String summary) {
        this.title = title;
        this.url = url;
        this.marker = marker;
        this.summary = summary;
    }

    static JSONArray definitions() throws JSONException {
        JSONArray ids = new JSONArray();
        StringBuilder catalogue = new StringBuilder("Прочитай материал по выбранной теме: ");
        for (MarketSource source : values()) {
            ids.put(source.name());
            catalogue.append(source.name()).append(" — ").append(source.title).append(". ");
        }
        JSONObject id = new JSONObject().put("type", "string").put("enum", ids);
        JSONObject params = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("id", id))
                .put("required", new JSONArray().put("id"));
        JSONObject function = new JSONObject().put("name", TOOL)
                .put("description", catalogue.toString()).put("parameters", params);
        JSONObject chart = ClaudeClient.chartDefinition();
        chart.put("parameters", chart.remove("input_schema"));
        return new JSONArray().put(new JSONObject().put("type", "function").put("function", function))
                .put(new JSONObject().put("type", "function").put("function", chart));
    }

    static MarketSource find(JSONObject args) {
        for (MarketSource source : values()) {
            if (source.name().equalsIgnoreCase(args.optString("id").trim())) return source;
        }
        return null;
    }

    JSONObject read() throws JSONException {
        JSONObject result = new JSONObject().put("source", title).put("url", url);
        try {
            return result.put("excerpt", fetchExcerpt()).put("retrieved_on", FinanceTools.today());
        } catch (IOException e) {
            return result.put("summary", summary).put("reviewed_on", "2026-11-15")
                    .put("notice", "Сайт недоступен для чтения. Использована сохранённая справка без свежих показателей.");
        }
    }

    private String fetchExcerpt() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("Размер страницы");
                    bytes.write(buffer, 0, count);
                }
                String html = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                        .replaceAll("(?is)<(script|style|nav)\\b[^>]*>.*?</\\1>", " ");
                String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                        .replaceAll("[\\s\\u00a0]+", " ");
                int start = text.indexOf(marker);
                if (start < 0) throw new IOException("Материал не найден");
                return text.substring(start, Math.min(text.length(), start + 6000));
            }
        } finally {
            connection.disconnect();
        }
    }
}

