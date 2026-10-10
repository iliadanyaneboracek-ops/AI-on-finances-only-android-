package com.example.aionfinances;

import android.content.Context;
import android.content.SharedPreferences;

/** Хранит ключ API в закрытом хранилище приложения (SharedPreferences). */
public final class Prefs {
    private static final String FILE = "mids";
    private static final String KEY_API = "api_key";

    private Prefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getApiKey(Context c) {
        return prefs(c).getString(KEY_API, "");
    }

    public static void setApiKey(Context c, String key) {
        prefs(c).edit().putString(KEY_API, key.trim()).apply();
    }
}
