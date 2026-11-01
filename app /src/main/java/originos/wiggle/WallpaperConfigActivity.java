package com.originos.wiggle;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen. It is both the launcher entry point and the wallpaper's
 * settingsActivity. Every change is written to SharedPreferences immediately;
 * the running wallpaper engine listens for those changes and updates live.
 */
public class WallpaperConfigActivity extends Activity {

    private static final int REQ_PICK_BG = 1001;

    private SharedPreferences prefs;
    private TextView bgStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);
        prefs = getSharedPreferences(WiggleWallpaperService.PREFS, MODE_PRIVATE);

        bindSeekBar(R.id.seek_shake, R.id.txt_shake_value,
                WiggleWallpaperService.KEY_SENSITIVITY, WiggleWallpaperService.DEFAULT_SENSITIVITY);
        bindSeekBar(R.id.seek_stiffness, R.id.txt_stiffness_value,
                WiggleWallpaperService.KEY_STIFFNESS, WiggleWallpaperService.DEFAULT_STIFFNESS);

        bindClockStyle();

        bgStatus = findViewById(R.id.txt_bg_status);
        refreshBgStatus();
        findViewById(R.id.btn_pick_bg).setOnClickListener(v -> pickImage());
        findViewById(R.id.btn_reset_bg).setOnClickListener(v -> {
            prefs.edit().remove(WiggleWallpaperService.KEY_BG_URI).apply();
            refreshBgStatus();
        });

        findViewById(R.id.btn_apply).setOnClickListener(v -> openWallpaperPicker());
    }

    private void bindSeekBar(int seekId, int valueId, final String key, int defaultValue) {
        SeekBar seek = findViewById(seekId);
        final TextView value = findViewById(valueId);
        int current = prefs.getInt(key, defaultValue);
        seek.setProgress(current);
        value.setText(current + "%");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private void bindClockStyle() {
        RadioGroup group = findViewById(R.id.rg_clock_style);
        int style = prefs.getInt(WiggleWallpaperService.KEY_CLOCK_STYLE,
                WiggleWallpaperService.DEFAULT_CLOCK_STYLE);
        switch (style) {
            case WiggleWallpaperService.STYLE_CLEAN:  group.check(R.id.rb_clean);  break;
            case WiggleWallpaperService.STYLE_FIRE:   group.check(R.id.rb_fire);   break;
            case WiggleWallpaperService.STYLE_COSMIC: group.check(R.id.rb_cosmic); break;
            default:                                  group.check(R.id.rb_glow);   break;
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            int value;
            if (checkedId == R.id.rb_clean) {
                value = WiggleWallpaperService.STYLE_CLEAN;
            } else if (checkedId == R.id.rb_fire) {
                value = WiggleWallpaperService.STYLE_FIRE;
            } else if (checkedId == R.id.rb_cosmic) {
                value = WiggleWallpaperService.STYLE_COSMIC;
            } else {
                value = WiggleWallpaperService.STYLE_GLOW;
            }
            prefs.edit().putInt(WiggleWallpaperService.KEY_CLOCK_STYLE, value).apply();
        });
    }

    private void refreshBgStatus() {
        String uri = prefs.getString(WiggleWallpaperService.KEY_BG_URI, null);
        bgStatus.setText(uri == null ? "Default gradient" : "Custom image selected");
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_BG);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            // Keep read access after the activity closes so the wallpaper service can load it.
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers do not offer persistable grants; loading may still work.
        }
        prefs.edit().putString(WiggleWallpaperService.KEY_BG_URI, uri.toString()).apply();
        refreshBgStatus();
    }

    private void openWallpaperPicker() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, WiggleWallpaperService.class));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Open your system wallpaper settings and pick OriginOS Wiggle",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
