package com.monotonnoe.oneglass.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.monotonnoe.oneglass.Config;

/**
 * OneGlass' own options (opened from the main settings): the iOS 27 dock platter and the
 * iOS 27 grid. The glass itself (look, icons, folders, widgets, lock screen) is set up on the
 * main settings screen.
 */
public final class SettingsActivity extends Activity {

    private interface IntProp {
        int get(Config c);

        void set(Config c, int v);
    }

    private interface BoolProp {
        boolean get(Config c);

        void set(Config c, boolean v);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable push = this::pushToLauncher;

    private SharedPreferences prefs;
    private Config config;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = openPrefs();
        config = Config.read(prefs);
        setContentView(build());
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            return getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(list);

        TextView title = new TextView(this);
        title.setText("Док и сетка iOS 27");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Размеры из Figma «iOS 27 Widgets» → Playground. Стекло дока — то же, что у иконок "
                + "(вид и картинка для живых обоев настраиваются на главном экране настроек).");
        hint.setAlpha(0.75f);
        hint.setPadding(0, dp(4), 0, dp(12));
        list.addView(hint);

        toggle("Стеклянный док", "Подложка дока 357×99 pt, радиус 35, иконки по центру",
                new BoolProp() {
                    public boolean get(Config c) { return c.dock; }
                    public void set(Config c, boolean v) { c.dock = v; }
                });
        slider("Док шире макета на", -20, 40, "pt", new IntProp() {
            public int get(Config c) { return c.dockPadH; }
            public void set(Config c, int v) { c.dockPadH = v; }
        });
        slider("Док выше макета на", -20, 40, "pt", new IntProp() {
            public int get(Config c) { return c.dockPadV; }
            public void set(Config c, int v) { c.dockPadV = v; }
        });
        toggle("Сетка iOS 27", "Иконки 60 pt с шагом 90×97.8 pt, виджеты 158/338 pt. В One UI выберите сетку 4×6 "
                        + "и отключите SquareWidgets, иначе модули будут спорить за расположение",
                new BoolProp() {
                    public boolean get(Config c) { return c.iosGrid; }
                    public void set(Config c, boolean v) { c.iosGrid = v; }
                });
        toggle("Диагностика", "Один раз записать дерево view лаунчера в журнал LSPosed",
                new BoolProp() {
                    public boolean get(Config c) { return c.diag; }
                    public void set(Config c, boolean v) { c.diag = v; }
                });

        Button restart = new Button(this);
        restart.setText("Перезапустить One UI Home");
        restart.setOnClickListener(v -> restartLauncher());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        list.addView(restart, lp);
        return scroll;
    }

    private void toggle(String name, String summary, BoolProp prop) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(16);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(13);
        s.setAlpha(0.65f);
        texts.addView(t);
        texts.addView(s);
        Switch sw = new Switch(this);
        sw.setChecked(prop.get(config));
        sw.setOnCheckedChangeListener((b, on) -> {
            prop.set(config, on);
            changed();
        });
        row.setOnClickListener(v -> sw.toggle());
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        list.addView(row);
    }

    private void slider(String name, int min, int max, String unit, IntProp prop) {
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, 0);
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(prop.get(config));
        t.setText(name + ": " + prop.get(config) + " " + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prop.set(config, value);
                t.setText(name + ": " + value + " " + unit);
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        list.addView(t);
        list.addView(bar);
    }

    private void changed() {
        SharedPreferences.Editor e = prefs.edit();
        config.write(e);
        e.commit();
        handler.removeCallbacks(push);
        handler.postDelayed(push, 250);
    }

    private void pushToLauncher() {
        sendBroadcast(new Intent(Config.ACTION_RELOAD).setPackage(Config.LAUNCHER));
    }

    private void restartLauncher() {
        new Thread(() -> {
            boolean ok;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + Config.LAUNCHER});
                ok = p.waitFor() == 0;
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            handler.post(() -> Toast.makeText(this,
                    done ? "One UI Home перезапущен" : "Нет root-доступа: перезапустите лаунчер вручную",
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
