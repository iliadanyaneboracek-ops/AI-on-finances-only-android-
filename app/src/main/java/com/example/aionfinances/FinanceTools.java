package com.example.aionfinances;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Инструменты, которые локальная модель вызывает сама (это называется function calling):
 *   get_key_rate       — ключевая ставка Банка России за период;
 *   get_exchange_rates — официальные курсы валют на дату или за период;
 *   draw_chart         — график по данным, которые вернул один из двух инструментов выше.
 *
 * Модель не знает сегодняшних цифр: её знания заканчиваются датой обучения. Поэтому она
 * не отвечает «по памяти», а просит данные у инструмента, а мы берём их у ЦБ.
 * Итоги (изменение, минимум, максимум) считаем здесь же: маленькие модели часто ошибаются
 * в арифметике, а готовое число им остаётся только пересказать.
 *
 * В draw_chart модель передаёт не сами числа, а chart_id из результата инструмента.
 * График строится прямо из данных ЦБ, поэтому модель не может исказить в нём цифры.
 */
public final class FinanceTools {

    public static final String KEY_RATE = "get_key_rate";
    public static final String EXCHANGE_RATES = "get_exchange_rates";
    public static final String DRAW_CHART = "draw_chart";

    /** Не больше стольких точек в ответе модели и на графике: иначе данные не влезут в контекст. */
    private static final int MAX_POINTS = 30;
    /** Не больше стольких валют за один вызов. */
    private static final int MAX_CURRENCIES = 6;
    /** Ключевую ставку Банк России ввёл 13 сентября 2013 года: раньше данных нет. */
    private static final String KEY_RATE_START = "2013-09-13";
    /** С 1 июля 1992 года ЦБ устанавливает официальные курсы. */
    private static final String RATES_START = "1992-07-01";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** Страницы ЦБ, на которые ссылаемся как на источники: их можно открыть в браузере. */
    private static final String URL_KEY_RATE = "https://www.cbr.ru/hd_base/KeyRate/";
    private static final String URL_DAILY = "https://www.cbr.ru/currency_base/daily/";
    private static final String URL_DYNAMICS = "https://www.cbr.ru/currency_base/dynamics/";

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern RU_DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    /** Дата в результатах инструментов: ровно ГГГГ-ММ-ДД. */
    private static final Pattern RESULT_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** Месяцы в родительном падеже: «27 октября 2025». */
    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая",
            "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
    /** Сколько точек самое большее на графике ставки (365 — каждый день года). */
    private static final int MAX_CHART_DAYS = 400;

    /** Модель иногда пишет валюту словом. Ищем по началу слова, чтобы подошли все падежи. */
    private static final String[][] CURRENCY_WORDS = {
            {"доллар", "USD"}, {"евро", "EUR"}, {"юан", "CNY"}, {"фунт", "GBP"},
            {"иен", "JPY"}, {"йен", "JPY"}, {"франк", "CHF"}, {"тенге", "KZT"},
            {"лир", "TRY"}, {"дирхам", "AED"}, {"рупи", "INR"}, {"белорусск", "BYN"}
    };

    /** Ошибка в параметрах вызова: её текст получит модель, чтобы исправиться. */
    private static final class BadArgument extends Exception {
        BadArgument(String message) {
            super(message);
        }
    }

    private final CbrData cbr;

    public FinanceTools(CbrData cbr) {
        this.cbr = cbr;
    }

    /** Инструмент данных выполняется сразу; draw_chart обрабатывает экран. */
    public static boolean isDataTool(String name) {
        return KEY_RATE.equals(name) || EXCHANGE_RATES.equals(name);
    }

    // ---------- Описания инструментов для модели ----------

    /**
     * Описания в формате Ollama: имя, что делает, какие параметры принимает (JSON Schema).
     * Модель читает их и сама решает, какой инструмент вызвать и с какими параметрами.
     */
    public static JSONArray definitions() throws JSONException {
        JSONObject keyRate = function(KEY_RATE,
                "Ключевая ставка Банка России: значение на начало и конец периода и все её "
                        + "изменения за период. Без дат — за последний год.",
                new JSONObject()
                        .put("date_from", text("Начало периода в формате ГГГГ-ММ-ДД. По умолчанию — год назад."))
                        .put("date_to", text("Конец периода в формате ГГГГ-ММ-ДД. По умолчанию — сегодня.")),
                new JSONArray());

        JSONObject rates = function(EXCHANGE_RATES,
                "Официальные курсы валют Банка России в рублях. Без date_from — курсы на одну "
                        + "дату (date_to, по умолчанию последние). С date_from — динамика за период.",
                new JSONObject()
                        .put("currencies", new JSONObject().put("type", "array")
                                .put("items", new JSONObject().put("type", "string"))
                                .put("description", "Коды валют ISO, например [\"USD\", \"EUR\", \"CNY\"]."))
                        .put("date_from", text("Начало периода в формате ГГГГ-ММ-ДД. Указывай, только если нужна динамика."))
                        .put("date_to", text("Конец периода или дата курса в формате ГГГГ-ММ-ДД. По умолчанию — сегодня.")),
                new JSONArray().put("currencies"));

        JSONObject chart = function(DRAW_CHART,
                "Показывает пользователю график по данным, которые вернул get_key_rate или "
                        + "get_exchange_rates. Числа передавать не нужно: только chart_id из результата.",
                new JSONObject()
                        .put("chart_id", text("Значение chart_id из результата инструмента, например chart_1."))
                        .put("title", text("Заголовок графика по-русски.")),
                new JSONArray().put("chart_id").put("title"));

        return new JSONArray().put(keyRate).put(rates).put(chart);
    }

    /** Строковый параметр с описанием. */
    private static JSONObject text(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONObject function(String name, String description, JSONObject properties,
                                       JSONArray required) throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length() > 0) parameters.put("required", required);
        return new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put("parameters", parameters));
    }

    // ---------- Выполнение ----------

    /** Понятная человеку строка о вызове: её видно в размышлениях как «Поиск: …». */
    public static String label(String name, JSONObject args) {
        try {
            if (KEY_RATE.equals(name)) {
                String[] p = period(args, 365, KEY_RATE_START);
                return "Банк России, ключевая ставка за " + ru(p[0]) + " — " + ru(p[1]);
            }
            if (EXCHANGE_RATES.equals(name)) {
                String codes = join(currencyCodes(args));
                if (parseDate(args, "date_from") == null) {
                    String date = parseDate(args, "date_to");
                    return "Банк России, курсы " + codes
                            + (date == null ? " на сегодня" : " на " + ru(date));
                }
                String[] p = period(args, 30, RATES_START);
                return "Банк России, курсы " + codes + " за " + ru(p[0]) + " — " + ru(p[1]);
            }
        } catch (BadArgument ignored) {
            // неверные параметры — модель узнает о них из результата
        }
        return "инструмент " + name;
    }

    /**
     * Выполняет инструмент данных. Исключений не бросает: при любой ошибке возвращает
     * {"error": "…"}, модель прочитает текст ошибки и объяснит её пользователю.
     *
     * @param chartId какой chart_id дать результату, если по нему можно построить график
     */
    public JSONObject run(String name, JSONObject args, String chartId) {
        try {
            JSONObject result;
            if (KEY_RATE.equals(name)) result = keyRate(args, chartId);
            else if (EXCHANGE_RATES.equals(name)) result = exchangeRates(args, chartId);
            else return error("Нет такого инструмента: " + name + ".");
            if (!result.has("error")) addDateWords(result);
            return result;
        } catch (BadArgument e) {
            return error(e.getMessage());
        } catch (IOException e) {
            return error("Не удалось получить данные с сайта Банка России: " + reason(e));
        } catch (JSONException | RuntimeException e) {
            return error("Не удалось обработать данные ЦБ: " + e.getMessage());
        }
    }

    private JSONObject keyRate(JSONObject args, String chartId)
            throws BadArgument, IOException, JSONException {
        String[] p = period(args, 365, KEY_RATE_START);
        List<CbrData.Point> days = cbr.keyRate(p[0], p[1]);
        String note = null;
        if (days.isEmpty()) {
            // На выходные и праздники записей нет: возьмём ближайший предыдущий рабочий день.
            days = cbr.keyRate(shift(p[0], -14), p[1]);
            note = "На запрошенные даты записей нет (выходные или праздники), "
                    + "поэтому показано значение на ближайший предыдущий рабочий день.";
        }
        if (days.isEmpty()) {
            return error("ЦБ не прислал данных о ключевой ставке за " + ru(p[0]) + " — " + ru(p[1]) + ".");
        }
        CbrData.Point first = days.get(0);
        CbrData.Point last = days.get(days.size() - 1);

        // Решения ЦБ — дни, когда ставка отличается от предыдущего дня.
        JSONArray decisions = new JSONArray();
        double previous = first.value;
        for (CbrData.Point day : days) {
            if (day.value != previous) {
                decisions.put(new JSONObject()
                        .put("date", day.date)
                        .put("rate", day.value)
                        .put("change_pp", round(day.value - previous, 2)));
                previous = day.value;
            }
        }
        JSONObject result = new JSONObject()
                .put("kind", "key_rate")
                .put("source", "Ключевая ставка Банка России")
                .put("url", URL_KEY_RATE)
                .put("unit", "% годовых")
                .put("period", new JSONObject().put("from", p[0]).put("to", p[1]))
                .put("at_start", point(first))
                .put("at_end", point(last))
                .put("change_pp", round(last.value - first.value, 2))
                .put("decisions", decisions)
                .put("chart_id", chartId);
        if (note != null) result.put("note", note);
        else if (decisions.length() == 0) result.put("note", "За этот период ставка не менялась.");
        return result;
    }

    private JSONObject exchangeRates(JSONObject args, String chartId)
            throws BadArgument, IOException, JSONException {
        List<String> codes = currencyCodes(args);
        if (parseDate(args, "date_from") == null) {
            String date = parseDate(args, "date_to");
            if (date != null && date.compareTo(today()) > 0) date = null; // будущее — берём последние
            return ratesOnDate(codes, date);
        }
        String[] p = period(args, 30, RATES_START);
        return ratesHistory(codes, p[0], p[1], chartId);
    }

    private JSONObject ratesOnDate(List<String> codes, String date) throws IOException, JSONException {
        CbrData.DailyRates daily = cbr.daily(date);
        JSONArray rates = new JSONArray();
        JSONArray missing = new JSONArray();
        for (String code : codes) {
            CbrData.Rate r = daily.find(code);
            if (r == null) {
                missing.put(code);
                continue;
            }
            rates.put(new JSONObject()
                    .put("code", r.code)
                    .put("name", r.name)
                    .put("per", r.nominal)
                    .put("rate", round(r.value, 4)));
        }
        JSONObject result = new JSONObject()
                .put("kind", "fx_daily")
                .put("source", "Официальные курсы валют Банка России")
                .put("url", URL_DAILY)
                .put("unit", "рублей за per единиц валюты")
                .put("date", daily.date)
                .put("rates", rates);
        if (missing.length() > 0) {
            result.put("not_found", missing)
                    .put("note", "ЦБ не устанавливает официальный курс для этих кодов.");
        }
        return result;
    }

    private JSONObject ratesHistory(List<String> codes, String from, String to, String chartId)
            throws IOException, JSONException {
        // Список валют на конец периода нужен, чтобы узнать их внутренние коды в базе ЦБ.
        CbrData.DailyRates directory = cbr.daily(to);
        Map<CbrData.Rate, List<CbrData.Point>> series = new LinkedHashMap<>();
        JSONArray missing = new JSONArray();
        TreeSet<String> allDates = new TreeSet<>();
        for (String code : codes) {
            CbrData.Rate r = directory.find(code);
            List<CbrData.Point> points = r == null
                    ? new ArrayList<CbrData.Point>() : cbr.dynamic(r.id, r.nominal, from, to);
            if (points.isEmpty()) {
                missing.put(code);
                continue;
            }
            series.put(r, points);
            for (CbrData.Point p : points) allDates.add(p.date);
        }
        if (series.isEmpty()) {
            return error("У ЦБ нет курсов " + join(codes) + " за "
                    + ru(from) + " — " + ru(to) + ".");
        }
        // Общие для всех валют даты, не больше MAX_POINTS штук: их же покажет график.
        List<String> dates = sample(new ArrayList<>(allDates), MAX_POINTS);

        JSONArray currencies = new JSONArray();
        for (Map.Entry<CbrData.Rate, List<CbrData.Point>> e : series.entrySet()) {
            CbrData.Rate r = e.getKey();
            List<CbrData.Point> points = e.getValue();
            CbrData.Point first = points.get(0);
            CbrData.Point last = points.get(points.size() - 1);
            CbrData.Point min = first;
            CbrData.Point max = first;
            for (CbrData.Point p : points) {
                if (p.value < min.value) min = p;
                if (p.value > max.value) max = p;
            }
            currencies.put(new JSONObject()
                    .put("code", r.code)
                    .put("name", r.name)
                    .put("per", r.nominal)
                    .put("first", point(first))
                    .put("last", point(last))
                    .put("min", point(min))
                    .put("max", point(max))
                    .put("change_rub", round(last.value - first.value, 4))
                    .put("change_pct", round((last.value - first.value) / first.value * 100, 2))
                    .put("values", valuesOn(dates, points)));
        }
        JSONObject result = new JSONObject()
                .put("kind", "fx_history")
                .put("source", "Динамика официальных курсов валют Банка России")
                .put("url", URL_DYNAMICS)
                .put("unit", "рублей за per единиц валюты")
                .put("period", new JSONObject().put("from", from).put("to", to))
                .put("dates", new JSONArray(dates))
                .put("currencies", currencies)
                .put("chart_id", chartId);
        if (missing.length() > 0) {
            result.put("not_found", missing).put("note", "По этим кодам у ЦБ нет курсов за период.");
        }
        return result;
    }

    /** Значение ряда на каждую из дат: последнее известное на эту дату или null. */
    private static JSONArray valuesOn(List<String> dates, List<CbrData.Point> points)
            throws JSONException {
        JSONArray values = new JSONArray();
        int i = 0;
        Double current = null;
        for (String date : dates) {
            while (i < points.size() && points.get(i).date.compareTo(date) <= 0) {
                current = points.get(i).value;
                i++;
            }
            if (current == null) values.put(JSONObject.NULL);
            else values.put(round(current, 4));
        }
        return values;
    }

    // ---------- Графики ----------

    /**
     * Описание графика в том же виде, что Claude присылает в draw_chart (его понимает
     * ChartSpec), по сохранённому результату инструмента. null — по этим данным график не строится.
     */
    public static JSONObject chartSpec(JSONObject result, String title) throws JSONException {
        String kind = result.optString("kind");
        if ("key_rate".equals(kind)) return keyRateChart(result, title);
        if ("fx_history".equals(kind)) return ratesChart(result, title);
        return null;
    }

    /**
     * Ставка меняется ступенькой в день решения ЦБ. Поэтому берём её значение на каждый день
     * (на длинных периодах — через равное число дней) и просим нарисовать график уступами:
     * наклонные линии между точками показали бы плавное снижение, которого не было.
     */
    private static JSONObject keyRateChart(JSONObject result, String title) throws JSONException {
        String from = result.getJSONObject("at_start").getString("date");
        String to = result.getJSONObject("at_end").getString("date");
        double rate = result.getJSONObject("at_start").getDouble("rate");
        JSONArray decisions = result.optJSONArray("decisions");
        int next = 0; // решения идут по порядку дат: двигаемся по ним вместе с днями

        JSONArray labels = new JSONArray();
        JSONArray values = new JSONArray();
        for (String date : chartDates(from, to)) {
            while (decisions != null && next < decisions.length()
                    && decisions.getJSONObject(next).getString("date").compareTo(date) <= 0) {
                rate = decisions.getJSONObject(next).getDouble("rate");
                next++;
            }
            labels.put(ru(date).substring(0, 6) + date.substring(2, 4)); // 13.10.25
            values.put(rate);
        }
        return new JSONObject()
                .put("title", title.trim().isEmpty() ? "Ключевая ставка Банка России" : title.trim())
                .put("chart_type", "line")
                .put("step", true)
                .put("x_labels", labels)
                .put("series", new JSONArray().put(new JSONObject()
                        .put("name", "Ключевая ставка").put("values", values)))
                .put("y_unit", "%")
                .put("source", "Банк России (cbr.ru), " + ru(from) + " — " + ru(to));
    }

    private static JSONObject ratesChart(JSONObject result, String title) throws JSONException {
        JSONArray dates = result.getJSONArray("dates");
        JSONArray labels = new JSONArray();
        for (int i = 0; i < dates.length(); i++) {
            String date = dates.getString(i);
            labels.put(ru(date).substring(0, 6) + date.substring(2, 4));
        }
        JSONArray series = new JSONArray();
        JSONArray currencies = result.getJSONArray("currencies");
        for (int i = 0; i < currencies.length(); i++) {
            JSONObject c = currencies.getJSONObject(i);
            int per = c.optInt("per", 1);
            series.put(new JSONObject()
                    .put("name", c.getString("code") + (per == 1 ? "" : " за " + per))
                    .put("values", c.getJSONArray("values")));
        }
        JSONObject period = result.getJSONObject("period");
        return new JSONObject()
                .put("title", title.trim().isEmpty() ? "Курсы валют Банка России" : title.trim())
                .put("chart_type", "line")
                .put("x_labels", labels)
                .put("series", series)
                .put("y_unit", "руб.")
                .put("source", "Банк России (cbr.ru), " + ru(period.getString("from"))
                        + " — " + ru(period.getString("to")));
    }

    /** Даты от from до to с шагом: неделя для коротких периодов, месяц или квартал для длинных. */
    /**
     * Даты для графика ставки: каждый календарный день, а на длинных периодах — через равное
     * число дней, чтобы точек было не больше MAX_CHART_DAYS. Шаг всегда одинаковый, поэтому
     * расстояния по оси времени не искажаются.
     */
    private static List<String> chartDates(String from, String to) {
        List<String> dates = new ArrayList<>();
        try {
            long span = dayNumber(to) - dayNumber(from);
            int step = (int) Math.max(1, (span + MAX_CHART_DAYS - 1) / MAX_CHART_DAYS);
            Calendar c = Calendar.getInstance(UTC);
            c.setTime(iso().parse(from));
            String date = from;
            while (date.compareTo(to) < 0) {
                dates.add(date);
                c.add(Calendar.DAY_OF_MONTH, step);
                date = iso().format(c.getTime());
            }
        } catch (ParseException ignored) {
            // даты пришли из нашего же результата и всегда верные
        }
        dates.add(to);
        return dates;
    }

    // ---------- Разбор параметров от модели ----------

    /** Период [from, to]: подставляет значения по умолчанию и исправляет перепутанные даты. */
    private static String[] period(JSONObject args, int defaultDays, String earliest)
            throws BadArgument {
        String today = today();
        String to = parseDate(args, "date_to");
        String from = parseDate(args, "date_from");
        if (to == null || to.compareTo(today) > 0) to = today;
        if (from == null) from = shift(to, -defaultDays);
        if (from.compareTo(to) > 0) {
            String t = from;
            from = to;
            to = t;
        }
        if (to.compareTo(earliest) < 0) {
            throw new BadArgument("Данных за этот период нет: они начинаются с " + ru(earliest) + ".");
        }
        if (from.compareTo(earliest) < 0) from = earliest;
        return new String[]{from, to};
    }

    /**
     * Дата от модели. Принимаем ГГГГ-ММ-ДД и ДД.ММ.ГГГГ, лишнее (например, время) отбрасываем.
     * @return дата вида ГГГГ-ММ-ДД или null, если её не указали
     */
    private static String parseDate(JSONObject args, String key) throws BadArgument {
        String raw = args.optString(key, "").trim();
        if (raw.isEmpty() || "null".equals(raw)) return null;
        int year;
        int month;
        int day;
        Matcher m = ISO_DATE.matcher(raw);
        if (m.find()) {
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
            day = Integer.parseInt(m.group(3));
        } else {
            m = RU_DATE.matcher(raw);
            if (!m.find()) {
                throw new BadArgument("Неверная дата «" + raw + "» в " + key
                        + ": нужен формат ГГГГ-ММ-ДД.");
            }
            day = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
            year = Integer.parseInt(m.group(3));
        }
        String date = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
        try {
            iso().parse(date); // строгая проверка: 2025-02-30 не пройдёт
        } catch (ParseException e) {
            throw new BadArgument("Такой даты не бывает: «" + raw + "» в " + key + ".");
        }
        return date;
    }

    /** Коды валют из параметра currencies. Модель может прислать список, строку или слова. */
    private static List<String> currencyCodes(JSONObject args) {
        List<String> words = new ArrayList<>();
        Object raw = args.opt("currencies");
        if (raw == null || raw == JSONObject.NULL) raw = args.opt("currency");
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) words.addAll(Arrays.asList(arr.optString(i).split("[^\\p{L}]+")));
        } else if (raw != null && raw != JSONObject.NULL) {
            words.addAll(Arrays.asList(String.valueOf(raw).split("[^\\p{L}]+")));
        }
        List<String> codes = new ArrayList<>();
        for (String word : words) {
            String code = currencyCode(word);
            if (code != null && !codes.contains(code)) codes.add(code);
        }
        if (codes.isEmpty()) codes.addAll(Arrays.asList("USD", "EUR", "CNY"));
        return codes.size() > MAX_CURRENCIES ? codes.subList(0, MAX_CURRENCIES) : codes;
    }

    private static String currencyCode(String word) {
        String w = word.trim().toLowerCase(new Locale("ru"));
        if (w.isEmpty() || w.startsWith("рубл") || w.equals("rub")) return null;
        if (w.matches("[a-z]{3}")) return w.toUpperCase(Locale.ROOT);
        for (String[] pair : CURRENCY_WORDS) {
            if (w.startsWith(pair[0])) return pair[1];
        }
        return null;
    }

    // ---------- Маленькие помощники ----------

    /** USD, EUR, CNY. (String.join на Android есть только с версии 8.0, а приложение работает с 7.0.) */
    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString();
    }

    private static JSONObject point(CbrData.Point p) throws JSONException {
        return new JSONObject().put("date", p.date).put("rate", round(p.value, 4));
    }

    static JSONObject error(String message) {
        try {
            return new JSONObject().put("error", message);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // не бывает: ключ не пустой
        }
    }

    /**
     * Рядом с каждой датой вида 2025-10-27 кладёт её запись словами («27 октября 2025»)
     * в поле с окончанием _text. Модель берёт готовую запись и не переписывает даты
     * в формате ГГГГ-ММ-ДД, а сами даты остаются как были: по ним строится график.
     */
    static void addDateWords(JSONObject object) throws JSONException {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof String && RESULT_DATE.matcher((String) value).matches()) {
                object.put(key + "_text", words((String) value));
            } else if (value instanceof JSONObject) {
                addDateWords((JSONObject) value);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof JSONObject) addDateWords((JSONObject) item);
                }
            }
        }
    }

    /** «2025-10-27» → «27 октября 2025». */
    static String words(String iso) {
        int month = Integer.parseInt(iso.substring(5, 7));
        int day = Integer.parseInt(iso.substring(8, 10));
        if (month < 1 || month > 12) return iso;
        return day + " " + MONTHS[month - 1] + " " + iso.substring(0, 4);
    }

    /** Почему не удалось связаться с сайтом, словами: по этому видно, на каком шаге оборвалась связь. */
    static String reason(IOException e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e instanceof UnknownHostException) {
            return "не удалось узнать адрес сайта, ошибка DNS (" + detail + ")";
        }
        if (e instanceof SocketTimeoutException) return "сайт не ответил вовремя (" + detail + ")";
        if (e instanceof SSLException) {
            return "не удалось установить защищённое соединение (" + detail + ")";
        }
        if (e instanceof ConnectException) return "соединение не установлено (" + detail + ")";
        return detail;
    }

    /** Отбирает не больше max элементов с равным шагом; первый и последний остаются всегда. */
    private static List<String> sample(List<String> items, int max) {
        if (items.size() <= max) return items;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            String item = items.get((int) Math.round(i * (items.size() - 1) / (double) (max - 1)));
            if (result.isEmpty() || !result.get(result.size() - 1).equals(item)) result.add(item);
        }
        return result;
    }

    private static double round(double value, int digits) {
        double k = Math.pow(10, digits);
        return Math.round(value * k) / k;
    }

    /** Сегодняшняя дата по часам телефона. */
    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** Строгий разбор и запись дат ГГГГ-ММ-ДД; считаем в UTC, чтобы не мешал часовой пояс. */
    private static SimpleDateFormat iso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(UTC);
        f.setLenient(false);
        return f;
    }

    /** Номер дня от 1 января 1970 года: так удобно считать разницу между датами. */
    private static long dayNumber(String date) throws ParseException {
        return iso().parse(date).getTime() / DAY_MS;
    }

    private static String shift(String date, int days) {
        try {
            return iso().format(new Date((dayNumber(date) + days) * DAY_MS));
        } catch (ParseException e) {
            return date;
        }
    }

    /** 2026-10-13 → 13.10.2026. */
    private static String ru(String iso) {
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4);
    }
}
