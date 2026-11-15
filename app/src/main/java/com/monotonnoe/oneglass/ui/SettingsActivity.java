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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.monotonnoe.oneglass.Config;

/** Module settings: live preview + switches and sliders, pushed to One UI Home instantly. */
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
    private boolean worldReadable;
    private Config config;
    private PreviewView preview;
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
            // LSPosed allows this for enabled modules and serves the file to the launcher.
            SharedPreferences p = getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
            worldReadable = true;
            return p;
        } catch (SecurityException e) {
            worldReadable = false;
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
        title.setText("OneGlass");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);

        TextView status = new TextView(this);
        status.setText(worldReadable
                ? "Модуль включён в LSPosed. Изменения применяются к One UI Home сразу."
                : "Модуль не включён в LSPosed (или LSPosed не запущен). Включите его для «One UI Home» и перезапустите лаунчер.");
        status.setAlpha(0.75f);
        status.setPadding(0, dp(4), 0, dp(12));
        list.addView(status);

        preview = new PreviewView(this);
        preview.setConfig(config);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(8);
        list.addView(preview, plp);

        section("Где стекло");
        toggle("Сетка iOS 27", "Иконки, виджеты и док по сетке макета: иконки 60 pt, шаг 90×97.8, виджеты 158/338. В One UI выберите сетку 4×6",
                new BoolProp() {
                    public boolean get(Config c) { return c.iosGrid; }
                    public void set(Config c, boolean v) { c.iosGrid = v; }
                });
        toggle("Док", "Стеклянная подложка под иконками дока (Figma: 357×99, радиус 35)",
                new BoolProp() {
                    public boolean get(Config c) { return c.dock; }
                    public void set(Config c, boolean v) { c.dock = v; }
                });
        toggle("Таблетка индикатора страниц", "Стеклянная «Search»-таблетка за точками страниц",
                new BoolProp() {
                    public boolean get(Config c) { return c.pill; }
                    public void set(Config c, boolean v) { c.pill = v; }
                });
        toggle("Виджеты", "Стеклянный фон виджетов. Прозрачность фона самого виджета поставьте на 100% в его настройках",
                new BoolProp() {
                    public boolean get(Config c) { return c.widgets; }
                    public void set(Config c, boolean v) { c.widgets = v; }
                });
        toggle("Скругление виджетов", "Обрезать виджеты по непрерывному скруглению iOS",
                new BoolProp() {
                    public boolean get(Config c) { return c.widgetClip; }
                    public void set(Config c, boolean v) { c.widgetClip = v; }
                });
        toggle("Папки", "Стеклянная подложка иконок папок",
                new BoolProp() {
                    public boolean get(Config c) { return c.folders; }
                    public void set(Config c, boolean v) { c.folders = v; }
                });
        toggle("Открытая папка", "Стеклянная панель открытой папки",
                new BoolProp() {
                    public boolean get(Config c) { return c.folderPanel; }
                    public void set(Config c, boolean v) { c.folderPanel = v; }
                });
        toggle("Стекло на иконках", "Default: слой GLASS поверх иконки. Clear/Tinted: стеклянная плитка и символ иконки (меню приложений не меняется)",
                new BoolProp() {
                    public boolean get(Config c) { return c.iconRim; }
                    public void set(Config c, boolean v) { c.iconRim = v; }
                });

        section("Стиль iOS 27 (Figma Playground)");
        choice("Вид", new String[]{"Default", "Clear", "Tinted"},
                new int[]{Config.STYLE_DEFAULT, Config.STYLE_CLEAR, Config.STYLE_TINTED}, new IntProp() {
                    public int get(Config c) { return c.style; }
                    public void set(Config c, int v) { c.style = v; }
                });
        choice("Оформление", new String[]{"Как в системе", "Светлое", "Тёмное"},
                new int[]{Config.TINT_AUTO, Config.TINT_LIGHT, Config.TINT_DARK}, new IntProp() {
                    public int get(Config c) { return c.tintMode; }
                    public void set(Config c, int v) { c.tintMode = v; }
                });
        colors();

        section("Материал (100% = как в макете)");
        toggle("Свет от наклона", "Блик стекла поворачивается вслед за наклоном телефона, как в iOS 26/27",
                new BoolProp() {
                    public boolean get(Config c) { return c.lightTilt; }
                    public void set(Config c, boolean v) { c.lightTilt = v; }
                });
        slider("Матовость (frost)", 0, 400, "%", new IntProp() {
            public int get(Config c) { return c.blur; }
            public void set(Config c, int v) { c.blur = v; }
        });
        slider("Преломление", 0, 300, "%", new IntProp() {
            public int get(Config c) { return c.refraction; }
            public void set(Config c, int v) { c.refraction = v; }
        });
        slider("Дисперсия", 0, 300, "%", new IntProp() {
            public int get(Config c) { return c.dispersion; }
            public void set(Config c, int v) { c.dispersion = v; }
        });
        slider("Освещение стекла", 0, 300, "%", new IntProp() {
            public int get(Config c) { return c.rim; }
            public void set(Config c, int v) { c.rim = v; }
        });

        section("Геометрия");
        slider("Радиус дока (макет: 35)", 0, 60, "dp", new IntProp() {
            public int get(Config c) { return c.dockRadius; }
            public void set(Config c, int v) { c.dockRadius = v; }
        });
        slider("Док шире макета на", -20, 40, "pt", new IntProp() {
            public int get(Config c) { return c.dockPadH; }
            public void set(Config c, int v) { c.dockPadH = v; }
        });
        slider("Док выше макета на", -20, 40, "pt", new IntProp() {
            public int get(Config c) { return c.dockPadV; }
            public void set(Config c, int v) { c.dockPadV = v; }
        });
        slider("Радиус виджетов (макет: 27)", 0, 48, "dp", new IntProp() {
            public int get(Config c) { return c.widgetRadius; }
            public void set(Config c, int v) { c.widgetRadius = v; }
        });
        slider("Ширина таблетки (0 = авто)", 0, 240, "dp", new IntProp() {
            public int get(Config c) { return c.pillWidth; }
            public void set(Config c, int v) { c.pillWidth = v; }
        });
        slider("Сила стекла на иконках", 0, 100, "%", new IntProp() {
            public int get(Config c) { return c.iconRimAlpha; }
            public void set(Config c, int v) { c.iconRimAlpha = v; }
        });

        section("Отладка");
        toggle("Диагностика", "Один раз записать дерево view лаунчера в журнал LSPosed (нужен перезапуск лаунчера)",
                new BoolProp() {
                    public boolean get(Config c) { return c.diag; }
                    public void set(Config c, boolean v) { c.diag = v; }
                });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        Button reset = new Button(this);
        reset.setText("Сбросить");
        reset.setOnClickListener(v -> {
            config = new Config();
            save();
            recreate();
        });
        Button restart = new Button(this);
        restart.setText("Перезапустить One UI Home");
        restart.setOnClickListener(v -> restartLauncher());
        buttons.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(restart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        list.addView(buttons);
        return scroll;
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAlpha(0.6f);
        t.setPadding(0, dp(20), 0, dp(4));
        list.addView(t);
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

    private void choice(String name, String[] names, int[] values, IntProp prop) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, 0);
        list.addView(t);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(names[i]);
            b.setId(View.generateViewId());
            final int value = values[i];
            g.addView(b);
            if (prop.get(config) == value) {
                b.setChecked(true);
            }
            b.setOnCheckedChangeListener((btn, on) -> {
                if (on) {
                    prop.set(config, value);
                    changed();
                }
            });
        }
        list.addView(g);
    }

    /** Tint colours for the Tinted style (first one is the Figma kit's #24A8F1). */
    private void colors() {
        TextView t = new TextView(this);
        t.setText("Цвет тинта (Tinted)");
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, dp(6));
        list.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] colors = {0xFF24A8F1, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF8E8E93};
        for (int col : colors) {
            View sw = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(col);
            d.setStroke(dp(config.tintColor == col ? 3 : 1), config.tintColor == col ? 0xFFFFFFFF : 0x40000000);
            sw.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(10);
            sw.setOnClickListener(v -> {
                config.tintColor = col;
                changed();
                recreate();
            });
            row.addView(sw, lp);
        }
        list.addView(row);
    }

    private void changed() {
        preview.setConfig(config);
        save();
        handler.removeCallbacks(push);
        handler.postDelayed(push, 250);
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        config.write(e);
        e.commit();
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
                    done ? "One UI Home перезапущен" : "Нет root-доступа: перезапустите лаунчер вручную (Настройки → Приложения → One UI Home → Остановить)",
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
