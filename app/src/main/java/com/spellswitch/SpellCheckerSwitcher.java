package com.spellswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;
import android.view.textservice.TextServicesManager;

/**
 * Переключает язык системной службы проверки орфографии, и хранит
 * настраиваемый пользователем порядок цикла (RU/UK/EN в любом порядке).
 *
 * Ключ "selected_spell_checker_subtype" — скрытая (@hide) настройка
 * Settings.Secure, публичной Java-константы для неё нет, поэтому обращаемся
 * по строковому имени напрямую. Запись требует android.permission.WRITE_SECURE_SETTINGS:
 *   adb shell pm grant com.spellswitch android.permission.WRITE_SECURE_SETTINGS
 *
 * Текущий выбранный язык мы не спрашиваем у системы (публичный метод для
 * этого был удалён из API — см. историю коммитов), а храним индекс в порядке
 * цикла сами в SharedPreferences: раз это единственный компонент, который его
 * меняет, наша копия и есть источник истины.
 */
public class SpellCheckerSwitcher {

    private static final String[] ALL_CODES = {"ru", "uk", "en"};
    private static final String[] ALL_LABELS = {"RU", "UK", "EN"};
    private static final String[] ALL_MENU_NAMES = {"Русский", "Українська", "English"};
    private static final String[] ALL_FLAGS = {
            "\uD83C\uDDF7\uD83C\uDDFA", // 🇷🇺
            "\uD83C\uDDFA\uD83C\uDDE6", // 🇺🇦
            "\uD83C\uDDEC\uD83C\uDDE7"  // 🇬🇧
    };

    private static final String PREFS_NAME = "spellswitch_prefs";
    private static final String KEY_LANG_INDEX = "current_lang_index";
    private static final String KEY_LANG_ORDER = "lang_order";
    private static final String KEY_SHOW_FLAG = "show_flag";
    private static final String DEFAULT_ORDER = "ru,uk,en";

    public static String labelFor(String code) {
        int i = indexInAll(code);
        return i >= 0 ? ALL_LABELS[i] : code.toUpperCase();
    }

    public static String flagFor(String code) {
        int i = indexInAll(code);
        return i >= 0 ? ALL_FLAGS[i] : code;
    }

    public static String menuNameFor(String code) {
        int i = indexInAll(code);
        return i >= 0 ? ALL_MENU_NAMES[i] : code;
    }

    public static String[] getOrder(Context context) {
        String stored = prefs(context).getString(KEY_LANG_ORDER, DEFAULT_ORDER);
        String[] codes = stored.split(",");
        if (codes.length == 0) return DEFAULT_ORDER.split(",");
        return codes;
    }

    public static void setOrder(Context context, String[] newOrder) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newOrder.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(newOrder[i]);
        }
        prefs(context).edit().putString(KEY_LANG_ORDER, sb.toString()).apply();
    }

    public static String currentLanguageLabel(Context context) {
        return labelFor(currentCode(context));
    }

    /** Текст для оверлея — флаг или буквенная метка, в зависимости от настройки. */
    public static String currentDisplayText(Context context) {
        boolean showFlag = prefs(context).getBoolean(KEY_SHOW_FLAG, false);
        String code = currentCode(context);
        return showFlag ? flagFor(code) : labelFor(code);
    }

    public static String currentCode(Context context) {
        String[] order = getOrder(context);
        int idx = currentIndex(context) % order.length;
        return order[idx];
    }

    public static void cycleNext(Context context) {
        String[] order = getOrder(context);
        int nextIndex = (currentIndex(context) + 1) % order.length;
        setLanguage(context, order[nextIndex]);
    }

    public static void setLanguage(Context context, String langTag) {
        TextServicesManager tsm = (TextServicesManager)
                context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (tsm == null) return;

        SpellCheckerInfo info = tsm.getCurrentSpellCheckerInfo();
        if (info == null) return;

        for (int i = 0; i < info.getSubtypeCount(); i++) {
            SpellCheckerSubtype subtype = info.getSubtypeAt(i);
            String locale = subtype.getLocale();
            if (locale != null && locale.toLowerCase().startsWith(langTag)) {
                Settings.Secure.putInt(context.getContentResolver(),
                        "selected_spell_checker_subtype", subtype.hashCode());
                saveIndex(context, indexOfInOrder(context, langTag));
                return;
            }
        }
    }

    private static int indexInAll(String code) {
        for (int i = 0; i < ALL_CODES.length; i++) {
            if (ALL_CODES[i].equals(code)) return i;
        }
        return -1;
    }

    private static int indexOfInOrder(Context context, String langTag) {
        String[] order = getOrder(context);
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(langTag)) return i;
        }
        return 0;
    }

    private static int currentIndex(Context context) {
        return prefs(context).getInt(KEY_LANG_INDEX, 0);
    }

    private static void saveIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_LANG_INDEX, index).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
