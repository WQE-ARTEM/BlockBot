package com.blockbot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private MediaProjectionManager mpm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(60, 60, 60, 60);
        layout.setBackgroundColor(Color.parseColor("#181F39"));

        TextView title = new TextView(this);
        title.setText("Block Blast AI Bot");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        Button btnOverlay = makeBtn("1. Разрешить «Поверх других окон»");
        btnOverlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Уже разрешено!", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnOverlay);

        Button btnAccess = makeBtn("2. Включить Спецвозможности (BlockBot)");
        btnAccess.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        layout.addView(btnAccess);

        Button btnStart = makeBtn("3. ЗАПУСТИТЬ БОТА (Захват экрана)");
        btnStart.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnStart.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выполни шаг 1!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (BotAccessibilityService.instance == null) {
                Toast.makeText(this, "Сначала включи BlockBot в Спецвозможностях (шаг 2)!", Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        });
        layout.addView(btnStart);

        setContentView(layout);
    }

    private Button makeBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#3A508B"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 30, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            MediaProjection mp = mpm.getMediaProjection(resultCode, data);
            if (BotAccessibilityService.instance != null) {
                BotAccessibilityService.instance.initProjection(mp);
                Toast.makeText(this, "Панель бота запущена! Открой Block Blast", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
