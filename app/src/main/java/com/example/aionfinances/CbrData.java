package com.example.aionfinances;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Данные Банка России с его открытых адресов (ключ и регистрация не нужны):
 *   ключевая ставка — веб-служба DailyInfo (протокол SOAP: запрос и ответ в виде XML);
 *   курсы всех валют на дату — scripts/XML_daily.asp;
 *   динамика курса одной валюты за период — scripts/XML_dynamic.asp.
 *
 * Все даты в этом классе — строки вида ГГГГ-ММ-ДД, например 2026-10-13.
 * Методы работают синхронно: вызывать их можно только из фонового потока.
 */
public final class CbrData {

    /** Одно значение ряда: дата и число. */
    public static final class Point {
        public final String date;
        public final double value;

        Point(String date, double value) {
            this.date = date;
            this.value = value;
        }
    }

    /** Курс одной валюты на дату. */
    public static final class Rate {
        /** Внутренний код валюты в базе ЦБ, например R01235 у доллара. Нужен для динамики. */
        public final String id;
        /** Буквенный код ISO, например USD. */
        public final String code;
        /** Название по-русски, например «Доллар США». */
        public final String name;
        /** За сколько единиц валюты указан курс: обычно за 1, у иены и тенге — за 100. */
        public final int nominal;
        /** Сколько рублей стоят nominal единиц валюты. */
        public final double value;

        Rate(String id, String code, String name, int nominal, double value) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.nominal = nominal;
            this.value = value;
        }
    }

    /** Курсы всех валют на одну дату. */
    public static final class DailyRates {
        /** Дата, на которую ЦБ установил эти курсы. */
        public final String date;
        public final List<Rate> rates;

        DailyRates(String date, List<Rate> rates) {
            this.date = date;
            this.rates = rates;
        }

        /** Курс по коду ISO или null, если ЦБ такой валюты не устанавливает. */
        public Rate find(String code) {
            for (Rate r : rates) {
                if (r.code.equalsIgnoreCase(code)) return r;
            }
            return null;
        }
    }

    /** Ответ больше этого размера считаем ошибкой: так не займём всю память телефона. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** Кодировка из первой строки XML: <?xml version="1.0" encoding="windows-1251"?>. */
    private static final Pattern XML_ENCODING =
            Pattern.compile("^\\s*<\\?xml[^>]*encoding=[\"']([A-Za-z0-9._-]+)[\"']");

    private final String baseUrl;

    /** @param baseUrl адрес сайта ЦБ без косой черты в конце: https://www.cbr.ru */
    public CbrData(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Ключевая ставка по дням за период; даты по возрастанию. */
    public List<Point> keyRate(String from, String to) throws IOException {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
                + " xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body><KeyRateXML xmlns=\"http://web.cbr.ru/\">"
                + "<fromDate>" + from + "T00:00:00</fromDate>"
                + "<ToDate>" + to + "T00:00:00</ToDate>"
                + "</KeyRateXML></soap:Body></soap:Envelope>";
        byte[] answer = request(baseUrl + "/DailyInfoWebServ/DailyInfo.asmx",
                envelope.getBytes(StandardCharsets.UTF_8), "http://web.cbr.ru/KeyRateXML");

        // Внутри ответа записи вида <KR><DT>2026-10-13T00:00:00+03:00</DT><Rate>16.50</Rate></KR>.
        NodeList records = parseXml(answer).getElementsByTagName("KR");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < records.getLength(); i++) {
            Element record = (Element) records.item(i);
            String date = childText(record, "DT");
            String rate = childText(record, "Rate");
            if (date.length() < 10 || rate.isEmpty()) continue;
            points.add(new Point(date.substring(0, 10), parseNumber(rate)));
        }
        sortByDate(points); // ЦБ присылает записи от новых к старым
        return points;
    }

    /** Курсы всех валют на дату; date == null — последние установленные курсы. */
    public DailyRates daily(String date) throws IOException {
        String url = baseUrl + "/scripts/XML_daily.asp";
        if (date != null) url += "?date_req=" + toCbrDate(date);
        Document doc = parseXml(request(url, null, null));

        // <ValCurs Date="13.10.2026"><Valute ID="R01235"><CharCode>USD</CharCode>…</Valute>…
        String day = fromCbrDate(doc.getDocumentElement().getAttribute("Date"));
        NodeList list = doc.getElementsByTagName("Valute");
        List<Rate> rates = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element v = (Element) list.item(i);
            String code = childText(v, "CharCode");
            String value = childText(v, "Value");
            if (code.isEmpty() || value.isEmpty()) continue;
            rates.add(new Rate(v.getAttribute("ID"), code, childText(v, "Name"),
                    parseNominal(childText(v, "Nominal")), parseNumber(value)));
        }
        return new DailyRates(day, rates);
    }

    /**
     * Динамика курса одной валюты за период; даты по возрастанию.
     *
     * @param id      внутренний код валюты в базе ЦБ (см. {@link Rate#id})
     * @param nominal за сколько единиц валюты привести курс
     */
    public List<Point> dynamic(String id, int nominal, String from, String to) throws IOException {
        String url = baseUrl + "/scripts/XML_dynamic.asp?date_req1=" + toCbrDate(from)
                + "&date_req2=" + toCbrDate(to) + "&VAL_NM_RQ=" + id;

        // <Record Date="02.04.2024" Id="R01235"><Nominal>1</Nominal><Value>92,7549</Value></Record>
        NodeList records = parseXml(request(url, null, null)).getElementsByTagName("Record");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < records.getLength(); i++) {
            Element r = (Element) records.item(i);
            String value = childText(r, "Value");
            if (value.isEmpty()) continue;
            // Номинал мог меняться за годы, поэтому сначала считаем курс за одну единицу.
            double perUnit = parseNumber(value) / parseNominal(childText(r, "Nominal"));
            points.add(new Point(fromCbrDate(r.getAttribute("Date")), perUnit * nominal));
        }
        sortByDate(points);
        return points;
    }

    // ---------- Сеть ----------

    /**
     * GET-запрос, если body == null, иначе POST с телом в формате SOAP.
     * @return тело ответа как есть, в байтах: кодировку определит parseXml
     */
    private static byte[] request(String url, byte[] body, String soapAction) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            // Некоторые сайты не отвечают программам, которые не представились.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MIDS/1.0");
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                conn.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("сайт ЦБ ответил кодом " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_RESPONSE_BYTES) throw new IOException("слишком большой ответ ЦБ");
        }
        return out.toByteArray();
    }

    // ---------- Разбор XML ----------

    /**
     * Разбирает XML. Сначала сами переводим байты в текст: ЦБ отвечает то в UTF-8,
     * то в windows-1251 (старая русская кодировка), и указывает её в первой строке.
     */
    static Document parseXml(byte[] bytes) throws IOException {
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        Charset charset = StandardCharsets.UTF_8;
        Matcher m = XML_ENCODING.matcher(head.replace("﻿", "").replace("ï»¿", ""));
        if (m.find()) {
            try {
                charset = Charset.forName(m.group(1));
            } catch (IllegalArgumentException ignored) {
                // неизвестная кодировка — пробуем UTF-8
            }
        }
        String text = new String(bytes, charset);
        // Текст уже раскодирован, поэтому первая строка с кодировкой разборщику не нужна.
        text = text.replaceFirst("^﻿?\\s*<\\?xml[^>]*\\?>", "");
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(text)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("ЦБ прислал ответ, который не удалось разобрать");
        }
    }

    /** Текст первого вложенного элемента с таким именем или "". */
    private static String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) return "";
        String text = list.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    /** ЦБ пишет числа то с запятой (92,7549), то с точкой (16.50). */
    private static double parseNumber(String raw) throws IOException {
        String s = raw.replace(" ", "").replace(" ", "").replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IOException("непонятное число в ответе ЦБ: " + raw);
        }
    }

    private static int parseNominal(String raw) {
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void sortByDate(List<Point> points) {
        // Даты вида ГГГГ-ММ-ДД правильно сортируются как обычные строки.
        Collections.sort(points, (a, b) -> a.date.compareTo(b.date));
    }

    /** 2026-10-13 → 13/10/2026: так даты ждут адреса XML_daily и XML_dynamic. */
    private static String toCbrDate(String iso) {
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    /** 13.10.2026 → 2026-10-13. */
    private static String fromCbrDate(String cbr) throws IOException {
        String s = cbr.trim();
        if (!s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) throw new IOException("непонятная дата в ответе ЦБ: " + cbr);
        return s.substring(6, 10) + "-" + s.substring(3, 5) + "-" + s.substring(0, 2);
    }
}
