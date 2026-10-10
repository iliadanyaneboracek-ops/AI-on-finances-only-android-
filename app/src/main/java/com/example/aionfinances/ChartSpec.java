package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Описание графика, которое модель присылает через инструмент draw_chart. */
public final class ChartSpec {

    public static final class Series {
        public final String name;
        /** Значения по порядку подписей оси X; null означает «нет данных». */
        public final List<Float> values;

        Series(String name, List<Float> values) {
            this.name = name;
            this.values = values;
        }
    }

    public final String title;
    public final boolean bar;
    public final List<String> labels;
    public final List<Series> series;
    public final String unit;
    public final String source;
    /** Исходный JSON от модели: нужен, чтобы сохранить график вместе с перепиской. */
    public final JSONObject raw;

    private ChartSpec(JSONObject raw, String title, boolean bar, List<String> labels,
                      List<Series> series, String unit, String source) {
        this.raw = raw;
        this.title = title;
        this.bar = bar;
        this.labels = labels;
        this.series = series;
        this.unit = unit;
        this.source = source;
    }

    /** Разбирает JSON от модели. Бросает JSONException, если данных для графика нет. */
    public static ChartSpec fromJson(JSONObject in) throws JSONException {
        JSONArray jl = in.getJSONArray("x_labels");
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < jl.length(); i++) labels.add(String.valueOf(jl.get(i)));

        JSONArray js = in.getJSONArray("series");
        List<Series> series = new ArrayList<>();
        for (int i = 0; i < js.length(); i++) {
            JSONObject s = js.getJSONObject(i);
            JSONArray jv = s.getJSONArray("values");
            List<Float> values = new ArrayList<>();
            int n = Math.min(jv.length(), labels.size());
            for (int k = 0; k < n; k++) {
                double v = jv.optDouble(k, Double.NaN);
                values.add(Double.isNaN(v) ? null : (float) v);
            }
            series.add(new Series(s.optString("name", ""), values));
        }
        if (labels.isEmpty() || series.isEmpty()) {
            throw new JSONException("empty chart");
        }
        return new ChartSpec(
                in,
                in.optString("title", ""),
                "bar".equals(in.optString("chart_type")),
                labels,
                series,
                in.optString("y_unit", ""),
                in.optString("source", ""));
    }
}
