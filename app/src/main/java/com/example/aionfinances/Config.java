package com.example.aionfinances;

/** Все настройки приложения в одном месте. */
public final class Config {
    private Config() {}

    /** Кто отвечает в чате: какая реализация AiClient работает. */
    public enum Backend {
        /** Мок (MockAiClient): заготовленные ответы прямо на телефоне, для проверки интерфейса. */
        MOCK,
        /** Локальная модель в Ollama на компьютере (OllamaClient): бесплатно, без ключа. */
        OLLAMA,
        /** Claude API (ClaudeClient): нужен ключ и деньги на счёте в консоли Anthropic. */
        CLAUDE
    }

    /** Переключатель «мозга» приложения. */
    public static final Backend BACKEND = Backend.OLLAMA;

    // ---------- Ollama ----------

    /**
     * Адреса Ollama. Приложение пробует их по очереди и запоминает тот, с которым соединилось.
     * Новый адрес нужно разрешить и в res/xml/network_security_config.xml.
     */
    public static final String[] OLLAMA_URLS = {
            // Так эмулятор Android видит компьютер, на котором он запущен.
            "http://10.0.2.2:11434",
            // Туннель adb: работает и в эмуляторе, и на телефоне по USB. Включается командой
            // на компьютере adb reverse tcp:11434 tcp:11434 (повторять после каждого запуска
            // устройства). Идёт мимо сети, поэтому прокси и VPN ему не мешают.
            "http://127.0.0.1:11434"
    };

    /**
     * Модель в Ollama. Скачивается командой ollama pull qwen3:14b. Подойдёт любая модель
     * с пометкой tools; модели с пометкой vision вдобавок видят приложенные картинки.
     */
    public static final String OLLAMA_MODEL = "qwen3:14b";

    /**
     * Контекст в токенах: сколько текста модель видит за раз (инструкция, история, данные ЦБ,
     * ответ). Больше — помнит длиннее, но занимает больше видеопамяти.
     */
    public static final int OLLAMA_CONTEXT = 16384;

    /** Сколько модель остаётся в видеопамяти после ответа: следующий ответ начнётся быстрее. */
    public static final String OLLAMA_KEEP_ALIVE = "30m";

    /** true — модель размышляет перед ответом (если умеет); false — отвечает сразу и быстрее. */
    public static final boolean OLLAMA_THINK = true;

    /** Сайт Банка России: отсюда инструменты берут ключевую ставку и курсы валют. */
    public static final String CBR_URL = "https://www.cbr.ru";

    // ---------- Мок ----------

    /** Пауза мока между кусочками ответа, в миллисекундах: имитирует скорость генерации. */
    public static final long MOCK_DELAY_MS = 35;

    // ---------- Claude API ----------

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
            "moex.com", "indexes.nasdaqomx.com", "ishares.com",
            "berkshirehathaway.com", "oaktreecapital.com"
    };
    // ---------- Вложения ----------

    /** Ограничения на вложения. */
    public static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 200_000;
    public static final int MAX_IMAGE_SIDE = 1568;
}
