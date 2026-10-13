package com.example.aionfinances;

/** Все настройки приложения в одном месте. */
public final class Config {
    private Config() {}

    /**
     * true — вместо настоящего Claude API работает мок (MockAiClient): ответы придумываются
     * на телефоне, без интернета, ключа и оплаты. false — настоящие запросы к Claude.
     */
    public static final boolean USE_MOCK = true;

    /** Пауза мока между кусочками ответа, в миллисекундах: имитирует скорость генерации. */
    public static final long MOCK_DELAY_MS = 35;

    /** Адрес Claude API (программного интерфейса, которому приложение отправляет запросы). */
    public static final String API_URL = "https://api.anthropic.com/v1/messages";
    public static final String API_VERSION = "2023-06-01";

    /** Модель. "claude-sonnet-5-5" быстрее и дешевле, "claude-opus-5-5" сильнее и дороже. */
    public static final String MODEL = "claude-sonnet-5-5";

    /**
     * Максимальная длина одного ответа в токенах (токен — примерно часть слова).
     * Размышления модели тоже входят в этот предел, поэтому он с запасом.
     */
    public static final int MAX_TOKENS = 16000;

    /** Сколько поисковых запросов в интернете модель может сделать на один вопрос. */
    public static final int MAX_SEARCHES = 5;

    /**
     * Белый список сайтов: веб-поиск берёт данные только с них (поддомены включены).
     * Чтобы добавить источник, допишите домен без "https://".
     */
    public static final String[] TRUSTED_DOMAINS = {
            "cbr.ru",               // Банк России
            "rosstat.gov.ru",       // Росстат
            "minfin.gov.ru",        // Минфин России
            "nalog.gov.ru",         // Федеральная налоговая служба
            "moex.com",             // Московская биржа
            "e-disclosure.ru",      // раскрытие отчётности российских компаний
            "imf.org",              // Международный валютный фонд
            "worldbank.org",        // Всемирный банк
            "oecd.org",             // Организация экономического сотрудничества и развития
            "bis.org",              // Банк международных расчётов
            "federalreserve.gov",   // Федеральная резервная система США
            "fred.stlouisfed.org",  // база экономических данных ФРС
            "sec.gov",              // Комиссия по ценным бумагам США (отчётность компаний)
            "treasury.gov",         // Минфин США
            "bls.gov",              // Бюро статистики труда США
            "bea.gov",              // Бюро экономического анализа США
            "ecb.europa.eu",        // Европейский центральный банк
            "ec.europa.eu"          // Евростат
    };

    /** Ограничения на вложения. */
    public static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 200_000;
    public static final int MAX_IMAGE_SIDE = 1568;
}
