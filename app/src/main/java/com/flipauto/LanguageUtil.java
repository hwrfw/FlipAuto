package com.flipauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** 语言切换工具：按偏好切换应用内语言。 */
final class LanguageUtil {
    private LanguageUtil() { }

    static String getLanguage(Context context) {
        return new Prefs(context).flipLanguage();
    }

    static void setLanguage(Context context, String lang) {
        new Prefs(context).flipLanguage(lang);
        updateResources(context, lang);
    }

    /** 应用指定语言到 App 上下文（未设置时默认使用中文）。 */
    static Context applyLanguage(Context context) {
        String lang = getLanguage(context);
        if (lang == null || lang.isEmpty()) lang = "zh"; // 未设置时默认中文
        return updateResources(context, lang);
    }

    private static Context updateResources(Context context, String lang) {
        try {
            Locale locale = lang.equals("en") ? Locale.ENGLISH : Locale.CHINESE;
            Locale.setDefault(locale);
            Configuration config = new Configuration(context.getResources().getConfiguration());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(new LocaleList(locale));
                return context.createConfigurationContext(config);
            } else {
                config.locale = locale;
                return context.createConfigurationContext(config);
            }
        } catch (Exception ignored) { }
        return context;
    }
}
