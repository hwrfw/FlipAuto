package com.flipauto;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import java.util.Locale;

/** 首启语言选择，使用深色开屏设计。 */
public final class OnboardingActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LanguageUtil.applyLanguage(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language_select);
        Button chinese = findViewById(R.id.btnChinese);
        Button english = findViewById(R.id.btnEnglish);
        chinese.setOnClickListener(v -> choose("zh"));
        english.setOnClickListener(v -> choose("en"));
    }

    private void choose(String lang) {
        LanguageUtil.setLanguage(this, lang);
        startActivity(new Intent(this, PermissionActivity.class));
        finish();
    }
}
