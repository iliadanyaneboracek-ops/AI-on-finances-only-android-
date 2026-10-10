package com.example.aionfinances;

import android.content.Context;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.BarLineChartBase;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.List;

/** Строит график по описанию ChartSpec с помощью библиотеки MPAndroidChart. */
public final class ChartRenderer {
    private ChartRenderer() {}

    /** Цвета рядов: морской, серый и несколько контрастных для дополнительных рядов. */
    private static final int[] SERIES_COLORS = {
            0xFF0E9AA7, 0xFF78909C, 0xFFE0884A, 0xFF5C6BC0, 0xFF9CCC65, 0xFFD46A8C
    };

    public static View create(Context context, ChartSpec spec) {
        return spec.bar ? barChart(context, spec) : lineChart(context, spec);
    }

    private static View lineChart(Context context, ChartSpec spec) {
        LineChart chart = new LineChart(context);
        List<ILineDataSet> sets = new ArrayList<>();
        for (int s = 0; s < spec.series.size(); s++) {
            ChartSpec.Series series = spec.series.get(s);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < series.values.size(); i++) {
                Float v = series.values.get(i);
                if (v != null) entries.add(new Entry(i, v));
            }
            LineDataSet set = new LineDataSet(entries, legendLabel(spec, series));
            int color = SERIES_COLORS[s % SERIES_COLORS.length];
            set.setColor(color);
            set.setCircleColor(color);
            set.setLineWidth(2f);
            set.setCircleRadius(2.5f);
            set.setDrawCircleHole(false);
            set.setDrawCircles(entries.size() <= 40);
            set.setDrawValues(false);
            sets.add(set);
        }
        chart.setData(new LineData(sets));
        styleCommon(context, chart, spec);
        chart.invalidate();
        return chart;
    }

    private static View barChart(Context context, ChartSpec spec) {
        BarChart chart = new BarChart(context);
        List<IBarDataSet> sets = new ArrayList<>();
        for (int s = 0; s < spec.series.size(); s++) {
            ChartSpec.Series series = spec.series.get(s);
            List<BarEntry> entries = new ArrayList<>();
            for (int i = 0; i < series.values.size(); i++) {
                Float v = series.values.get(i);
                entries.add(new BarEntry(i, v == null ? 0f : v));
            }
            BarDataSet set = new BarDataSet(entries, legendLabel(spec, series));
            set.setColor(SERIES_COLORS[s % SERIES_COLORS.length]);
            set.setDrawValues(false);
            sets.add(set);
        }
        BarData data = new BarData(sets);
        chart.setData(data);
        styleCommon(context, chart, spec);

        int count = sets.size();
        if (count > 1) {
            // Несколько рядов: столбцы одной подписи ставим рядом. Ширины подобраны так,
            // чтобы группа занимала ровно одно деление оси: (barWidth + barSpace) * count + groupSpace = 1
            float groupSpace = 0.2f;
            float barSpace = 0.02f;
            float barWidth = (1f - groupSpace) / count - barSpace;
            data.setBarWidth(barWidth);
            XAxis x = chart.getXAxis();
            x.setAxisMinimum(0f);
            x.setAxisMaximum(spec.labels.size());
            x.setCenterAxisLabels(true);
            chart.groupBars(0f, groupSpace, barSpace);
        } else {
            data.setBarWidth(0.6f);
            chart.setFitBars(true);
        }
        chart.invalidate();
        return chart;
    }

    private static String legendLabel(ChartSpec spec, ChartSpec.Series series) {
        return spec.unit.isEmpty() ? series.name : series.name + ", " + spec.unit;
    }

    private static void styleCommon(Context context, BarLineChartBase<?> chart, ChartSpec spec) {
        int textColor = ContextCompat.getColor(context, R.color.text_secondary);
        int gridColor = ContextCompat.getColor(context, R.color.surface_variant);

        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setScaleEnabled(false);   // без масштабирования, чтобы не мешать прокрутке чата
        chart.setDragEnabled(false);
        chart.setExtraBottomOffset(8f);
        chart.setNoDataText("Нет данных");

        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setGranularity(1f);
        x.setDrawGridLines(false);
        x.setTextColor(textColor);
        x.setAxisLineColor(gridColor);
        x.setLabelRotationAngle(spec.labels.size() > 6 ? -40f : 0f);
        x.setValueFormatter(new IndexAxisValueFormatter(spec.labels));

        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setTextColor(textColor);
        chart.getAxisLeft().setGridColor(gridColor);
        chart.getAxisLeft().setAxisLineColor(gridColor);

        Legend legend = chart.getLegend();
        legend.setTextColor(textColor);
        legend.setWordWrapEnabled(true);
    }
}
