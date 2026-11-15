package com.monotonnoe.oneglass.engine;

import com.monotonnoe.oneglass.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The module's settings, opened from LSPosed or the launcher: iOS 26 Settings' look (Apple's
 * Human Interface Guidelines) with Kyant's liquid glass controls. A first page leads to the
 * Home Screen (with a live preview drawn by the Home screen's own glass renderer), the Lock
 * Screen, Advanced and Backup pages.
 *
 * <p>Every value is kept in the module's preferences, which {@link SettingsProvider} serves to
 * the hooked processes, and sent to One UI Home with {@link Settings#ACTION_CHANGED} as it is
 * changed: switches at once, sliders when they are let go (the preview follows them live).
 */
public final class SettingsActivity extends Activity {
    private static final int PICK_PICTURE = 1;
    private static final int EXPORT_SETTINGS = 2;
    private static final int IMPORT_SETTINGS = 3;
    private static final int PICK_FONT_TITLE = 4;
    private static final int PICK_FONT_TEXT = 5;
    /** Longest side the picture is stored at: plenty for the glass, which uses half the screen. */
    private static final int PICTURE_MAX_SIDE = 2400;
    private static final String STATE_PAGES = "pages";

    private static final String PAGE_MAIN = "main";
    private static final String PAGE_HOME = "home";
    private static final String PAGE_LOCK = "lock";
    private static final String PAGE_ADVANCED = "advanced";
    private static final String PAGE_BACKUP = "backup";

    /** The lock screen clock's values: no longer shown, kept as they are (and by a reset). */
    private static final int[] CLOCK = {Settings.GLASS_CLOCK, Settings.CLOCK_REFRACTION,
            Settings.CLOCK_TINT, Settings.CLOCK_BLUR, Settings.CLOCK_SATURATION,
            Settings.CLOCK_FROST, Settings.CLOCK_BEVEL, Settings.CLOCK_HIGHLIGHT,
            Settings.CLOCK_IOS27, Settings.CLOCK_IOS27_LAYER, Settings.CLOCK_RIM,
            Settings.CLOCK_HAIRLINE, Settings.CLOCK_IOS26, Settings.CLOCK_FONT};

    /** Glass colours to pick from; 0 is the automatic light or dark tone. */
    private static final int[] TINT_COLORS = {
            0, 0x8E8E93, 0x0A84FF, 0x5E5CE6, 0xBF5AF2, 0xFF375F, 0xFF9F0A, 0xFFD60A,
            0x30D158, 0x64D2FF,
    };
    private static final int[] RIM_COLOR_NAMES = {R.string.rim_white, R.string.rim_cool,
            R.string.rim_warm, R.string.rim_lilac, R.string.rim_pink, R.string.rim_mint};

    // How a slider's value is shown.
    private static final int PERCENT = 0;
    private static final int DP = 1;
    private static final int PX = 2;
    private static final int TENTH_DP = 3;
    private static final int TENTH_TIMES = 4;
    private static final int COUNT = 5;
    private static final int PERCENT_OR_HOME = 6;

    private SharedPreferences prefs;
    private Ui ui;
    private Rows rows;
    private NavHost nav;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int opacity;
    private int tintColor;
    private int style;
    /** {@link Settings#GLASS_KEYS} values. */
    private int[] glass;

    private HomePreview preview;
    private GlassSlider tintedSlider;
    private LinearLayout tintRow;
    private TextView tintFooter;
    private TextView homeValue;
    private TextView lockValue;
    private TextView statusHome;
    private TextView statusLock;
    private TextView statusFooter;
    private TextView pictureValue;
    private View pictureRemove;
    private TextView fontTitleValue;
    private TextView fontTextValue;
    private TextView iconPackValue;
    private TextView perAppValue;
    private TextView transferFooter;

    private final ContentObserver lockObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            updateStatus();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (p, key) -> {
                if (Settings.KEY_STATUS.equals(key)) runOnUiThread(() -> updateStatus());
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);
        Settings.migrate(prefs);
        loadValues();
        ui = new Ui(this);
        rows = new Rows(ui);

        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.setNavigationBarContrastEnforced(false);
        w.setStatusBarContrastEnforced(false);
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(attrs);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.background));

        nav = new NavHost(ui);
        setContentView(nav);
        WindowInsetsController bars = w.getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(ui.night ? 0 : light, light);
        }

        nav.push(page(PAGE_MAIN), false);
        String[] restore = savedInstanceState != null
                ? savedInstanceState.getStringArray(STATE_PAGES) : null;
        if (restore != null) {
            for (int i = 1; i < restore.length; i++) nav.push(page(restore[i]), false);
        }
        applyPreview();
        sendToPhotoTile();
    }

    private void loadValues() {
        opacity = prefs.getInt(Settings.KEY_OPACITY, Settings.DEFAULT_OPACITY);
        tintColor = prefs.getInt(Settings.KEY_TINT_COLOR, Settings.DEFAULT_TINT);
        style = prefs.getInt(Settings.KEY_STYLE, Settings.DEFAULT_STYLE);
        if (style != Settings.STYLE_BLUR) style = Settings.STYLE_LIQUID;
        glass = new int[Settings.GLASS_KEYS.length];
        for (int i = 0; i < glass.length; i++) {
            glass[i] = prefs.getInt(Settings.GLASS_KEYS[i], Settings.GLASS_DEFAULTS[i]);
        }
        // The load readout was a diagnostic for the old status text, which is gone: off.
        glass[Settings.LOAD_READOUT] = 0;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> ids = new ArrayList<>();
        for (Page p : nav.pages()) ids.add(p.id);
        out.putStringArray(STATE_PAGES, ids.toArray(new String[0]));
    }

    @Override
    public void onBackPressed() {
        if (nav.busy()) return;
        if (!nav.pop()) super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(statusListener);
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor(Settings.GLOBAL_LOCK_STATUS), false,
                lockObserver);
        updateStatus();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(statusListener);
        getContentResolver().unregisterContentObserver(lockObserver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (preview != null) preview.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------------ pages

    private Page page(String id) {
        Page p = new Page(ui, id, getString(titleOf(id)), PAGE_MAIN.equals(id));
        build(p);
        return p;
    }

    private static int titleOf(String id) {
        switch (id) {
            case PAGE_HOME:
                return R.string.page_home;
            case PAGE_LOCK:
                return R.string.page_lock;
            case PAGE_ADVANCED:
                return R.string.page_advanced;
            case PAGE_BACKUP:
                return R.string.page_backup;
            default:
                return R.string.app_name;
        }
    }

    private void build(Page p) {
        switch (p.id) {
            case PAGE_HOME:
                buildHome(p.content);
                break;
            case PAGE_LOCK:
                buildLock(p.content);
                break;
            case PAGE_ADVANCED:
                buildAdvanced(p.content);
                break;
            case PAGE_BACKUP:
                buildBackup(p.content);
                break;
            default:
                buildMain(p.content);
                break;
        }
    }

    private void open(String id) {
        if (nav.busy()) return;
        nav.push(page(id), true);
    }

    /** Every page's rows again (after a reset or an import), where they were scrolled. */
    private void rebuildAll() {
        if (preview != null) {
            preview.release();
            preview = null;
        }
        tintedSlider = null;
        for (Page p : nav.pages()) {
            int y = p.scrollY();
            p.clearRows();
            build(p);
            p.scroll.post(() -> p.scroll.scrollTo(0, y));
        }
        applyPreview();
        updateStatus();
    }

    // ------------------------------------------------------------------------ main page

    private void buildMain(LinearLayout c) {
        Rows.Section s = rows.section(c, null, null);
        Rows.Row home = rows.navRow(s.card, 0xFF0A84FF, Rows.GLYPH_HOME,
                getString(R.string.page_home), null, () -> open(PAGE_HOME));
        homeValue = home.value;
        Rows.Row lock = rows.navRow(s.card, 0xFF5E5CE6, Rows.GLYPH_LOCK,
                getString(R.string.page_lock), null, () -> open(PAGE_LOCK));
        lockValue = lock.value;
        updateMainValues();

        // OneGlass: the iOS 27 dock platter (Figma) and the iOS grid live on their own screen
        s = rows.section(c, null, null);
        rows.navRow(s.card, 0xFF34C759, Rows.GLYPH_HOME, getString(R.string.page_dock_grid),
                null, () -> startActivity(new android.content.Intent(this,
                        com.monotonnoe.oneglass.ui.SettingsActivity.class)));

        s = rows.section(c, null, null);
        rows.navRow(s.card, 0xFF8E8E93, Rows.GLYPH_SLIDERS, getString(R.string.page_advanced),
                null, () -> open(PAGE_ADVANCED));
        rows.navRow(s.card, 0xFF30B158, Rows.GLYPH_BACKUP, getString(R.string.page_backup),
                null, () -> open(PAGE_BACKUP));

        s = rows.section(c, getString(R.string.status), getString(R.string.status_hint));
        statusHome = rows.valueRow(s.card, getString(R.string.status_home), "", false, null).value;
        statusLock = rows.valueRow(s.card, getString(R.string.status_lock), "", false, null).value;
        statusHome.setMaxWidth(ui.px(200));
        statusLock.setMaxWidth(ui.px(200));
        statusFooter = s.footer;
        updateStatus();

        String version = Config.VERSION;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        TextView about = rows.footer(c, getString(R.string.about, version));
        about.setPadding(ui.px(32), ui.px(28), ui.px(32), 0);
    }

    private void updateMainValues() {
        if (homeValue != null) {
            homeValue.setText(style == Settings.STYLE_BLUR ? R.string.style_blur
                    : R.string.style_liquid);
        }
        if (lockValue != null) {
            lockValue.setText(glass[Settings.LOCK_NOTIF_GLASS] != 0 ? R.string.on
                    : R.string.off);
        }
    }

    /**
     * What the Home screen and System UI last reported, in a word: the module running there,
     * running another version (the Home screen wants a restart), or not reporting at all.
     */
    private void updateStatus() {
        if (statusHome == null) return;
        String s = prefs.getString(Settings.KEY_STATUS, null);
        int home;
        boolean restart = false;
        if (s == null) {
            home = R.string.status_not_running;
        } else if (!s.startsWith("[" + Config.VERSION + "]")) {
            home = R.string.status_restart;
            restart = true;
        } else if (style == Settings.STYLE_BLUR) {
            home = R.string.status_active_blur;
        } else if (s.contains("off, plain blur") || s.contains("BUT")) {
            home = R.string.status_blur_only;
        } else {
            home = R.string.status_active;
        }
        statusHome.setText(home);
        statusHome.setTextColor(restart ? ui.red : ui.secondaryLabel);
        String lock = null;
        try {
            lock = android.provider.Settings.Global.getString(getContentResolver(),
                    Settings.GLOBAL_LOCK_STATUS);
        } catch (Throwable ignored) {
        }
        statusLock.setText(lock != null && !lock.isEmpty() ? R.string.status_active
                : R.string.status_not_running);
        if (statusFooter != null) {
            statusFooter.setText(home == R.string.status_blur_only
                    ? getString(R.string.status_hint_picture) + "\n\n"
                    + getString(R.string.status_hint) : getString(R.string.status_hint));
        }
    }

    // ------------------------------------------------------------------------ Home Screen

    private void buildHome(LinearLayout c) {
        if (preview != null) preview.release();
        preview = new HomePreview(ui, () -> pickPicture());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.leftMargin = ui.px(16);
        pp.rightMargin = ui.px(16);
        pp.topMargin = ui.px(8);
        c.addView(preview, pp);

        // iOS 26's Clear / Tinted: the tone behind the glass, from none to Solid.
        Rows.Section s = rows.section(c, null, getString(R.string.tinted_hint));
        ((LinearLayout.LayoutParams) s.card.getLayoutParams()).topMargin = ui.px(16);
        int max = Settings.OPACITY_PRESETS[Settings.OPACITY_PRESETS.length - 1];
        tintedSlider = new GlassSlider(ui, 0, max, Math.min(max, opacity));
        tintedSlider.setClearTintedIcons(true);
        tintedSlider.setTicks(Settings.OPACITY_PRESETS);
        tintedSlider.setContentDescription(getString(R.string.tinted));
        tintedSlider.setListener((v, done) -> {
            opacity = v;
            applyPreview();
            if (done) save();
        });
        rows.sliderCell(s.card, null, tintedSlider);

        s = rows.section(c, getString(R.string.style), getString(R.string.style_hint));
        GlassSegmented effect = new GlassSegmented(ui, new String[]{
                getString(R.string.style_liquid), getString(R.string.style_blur)}, style);
        effect.setListener(i -> {
            style = i == 1 ? Settings.STYLE_BLUR : Settings.STYLE_LIQUID;
            applyPreview();
            save();
            updateMainValues();
        });
        rows.cell(s.card, effect);

        s = rows.section(c, getString(R.string.tint_color), null);
        HorizontalScrollView chips = new HorizontalScrollView(this);
        chips.setHorizontalScrollBarEnabled(false);
        chips.setClipToPadding(false);
        tintRow = new LinearLayout(this);
        tintRow.setOrientation(LinearLayout.HORIZONTAL);
        tintRow.setPadding(0, ui.px(6), 0, ui.px(6));
        chips.addView(tintRow);
        for (int color : TINT_COLORS) {
            View chip = new TintChip(color);
            chip.setOnClickListener(v -> pickTint(color));
            chip.setContentDescription(color == 0 ? getString(R.string.tint_automatic)
                    : String.format(Locale.ROOT, "#%06X", color));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(40), ui.px(40));
            lp.rightMargin = ui.px(8);
            tintRow.addView(chip, lp);
        }
        rows.cell(s.card, chips);
        rows.actionRow(s.card, getString(R.string.tint_custom), false, () -> customTint());
        tintFooter = rows.footer(c, "");
        updateTint();

        s = rows.section(c, getString(R.string.group_glass), getString(R.string.group_glass_hint));
        slider(s.card, R.string.glass_blur, Settings.GLASS_BLUR, PERCENT);
        slider(s.card, R.string.lens_amount, Settings.LENS_AMOUNT, PERCENT);
        slider(s.card, R.string.glass_rim, Settings.GLASS_RIM, PERCENT);
        slider(s.card, R.string.glass_hairline, Settings.GLASS_HAIRLINE, PERCENT);

        s = rows.section(c, getString(R.string.group_where), null);
        toggle(s.card, R.string.glass_folders, 0, Settings.GLASS_FOLDERS);
        toggle(s.card, R.string.glass_widgets, 0, Settings.GLASS_WIDGETS);
        toggle(s.card, R.string.glass_search, R.string.glass_search_sub, Settings.GLASS_SEARCH);
        // OneGlass: the One UI apps screen stays stock (no glass toggle for it)

        s = rows.section(c, getString(R.string.group_folder), null);
        toggle(s.card, R.string.glass_folder_panel, 0, Settings.GLASS_FOLDER_PANEL);
        toggle(s.card, R.string.glass_open_folder, 0, Settings.GLASS_OPEN_FOLDER);
        toggle(s.card, R.string.folder_morph, 0, Settings.FOLDER_MORPH);

        s = rows.section(c, getString(R.string.group_widgets), getString(R.string.gallery_hint));
        toggle(s.card, R.string.widget_ios_corners, R.string.widget_ios_corners_sub,
                Settings.WIDGET_IOS_CORNERS);
        GlassSegmented look = new GlassSegmented(ui, new String[]{
                getString(R.string.gallery_look_full), getString(R.string.gallery_look_mono),
                getString(R.string.gallery_look_tinted)},
                Math.max(0, Math.min(2, glass[Settings.GALLERY_LOOK])));
        look.setContentDescription(getString(R.string.gallery_look));
        look.setListener(i -> {
            glass[Settings.GALLERY_LOOK] = i;
            save();
        });
        TextView lookTitle = rows.text(getString(R.string.gallery_look), Ui.BODY, ui.label);
        lookTitle.setPadding(0, ui.px(8), 0, 0);
        LinearLayout lookCell = rows.cell(s.card, look);
        lookCell.addView(lookTitle, 0);
        slider(s.card, R.string.gallery_light, Settings.GALLERY_LIGHT, PERCENT);
        toggle(s.card, R.string.gallery_spatial, R.string.gallery_spatial_sub,
                Settings.GALLERY_SPATIAL);

        s = rows.section(c, getString(R.string.group_motion), null);
        slider(s.card, R.string.glass_light, Settings.GLASS_LIGHT, PERCENT);
        toggle(s.card, R.string.glass_condense, R.string.glass_condense_sub,
                Settings.GLASS_CONDENSE);

        s = rows.section(c, getString(R.string.group_icons), getString(R.string.icons_hint));
        toggle(s.card, R.string.pack_peel, R.string.pack_peel_sub, Settings.PACK_PEEL);
        toggle(s.card, R.string.hide_badges, 0, Settings.HIDE_BADGES);

        s = rows.section(c, getString(R.string.picture), getString(R.string.picture_hint));
        pictureValue = rows.valueRow(s.card, getString(R.string.picture_current), "", false,
                null).value;
        rows.actionRow(s.card, getString(R.string.picture_choose), false, () -> pickPicture());
        pictureRemove = rows.actionRow(s.card, getString(R.string.picture_remove), true,
                () -> removePicture());
        updatePicture();

        s = rows.section(c, null, null);
        rows.navRow(s.card, 0xFF8E8E93, Rows.GLYPH_SLIDERS, getString(R.string.page_advanced),
                null, () -> open(PAGE_ADVANCED));
    }

    /** A colour to pick for the glass: a dot, ringed when it is the one in use. */
    private final class TintChip extends View {
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TintChip(int color) {
            super(SettingsActivity.this);
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            boolean selected = color == 0 ? tintColor == 0 : tintColor == (0xFF000000 | color);
            float dot = selected ? r - ui.dp(5) : r - ui.dp(2);
            paint.setStyle(Paint.Style.FILL);
            if (color == 0) {
                // Automatic: light over dark, as the tone follows the theme.
                paint.setColor(0xFFFFFFFF);
                c.drawArc(cx - dot, cy - dot, cx + dot, cy + dot, 135f, 180f, true, paint);
                paint.setColor(0xFF1C1C1E);
                c.drawArc(cx - dot, cy - dot, cx + dot, cy + dot, 315f, 180f, true, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, ui.dp(0.5f)));
                paint.setColor(ui.separator);
                c.drawCircle(cx, cy, dot, paint);
            } else {
                paint.setColor(0xFF000000 | color);
                c.drawCircle(cx, cy, dot, paint);
            }
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f));
                paint.setColor(ui.accent);
                c.drawCircle(cx, cy, r - ui.dp(1.5f), paint);
            }
        }
    }

    private void pickTint(int color) {
        tintColor = color == 0 ? 0 : 0xFF000000 | color;
        if (tintColor != 0 && opacity == 0) {
            // A colour needs some tone to show: start from Light.
            opacity = Settings.OPACITY_PRESETS[2];
            if (tintedSlider != null) tintedSlider.setValue(opacity);
        }
        updateTint();
        applyPreview();
        save();
    }

    private void customTint() {
        EditText input = new EditText(this);
        input.setHint("#RRGGBB");
        input.setSingleLine(true);
        if (tintColor != 0) {
            input.setText(String.format(Locale.ROOT, "#%06X", tintColor & 0xFFFFFF));
        }
        LinearLayout box = new LinearLayout(this);
        box.setPadding(ui.px(20), ui.px(8), ui.px(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.tint_custom)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        pickTint(Color.parseColor(input.getText().toString().trim()) & 0xFFFFFF);
                    } catch (IllegalArgumentException e) {
                        if (tintFooter != null) tintFooter.setText(R.string.tint_invalid);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateTint() {
        if (tintRow != null) {
            for (int i = 0; i < tintRow.getChildCount(); i++) tintRow.getChildAt(i).invalidate();
        }
        if (tintFooter != null) {
            String which = tintColor == 0 ? getString(R.string.tint_auto)
                    : getString(R.string.tint_value,
                    String.format(Locale.ROOT, "#%06X", tintColor & 0xFFFFFF));
            tintFooter.setText(which + " " + getString(R.string.tint_color_hint));
        }
    }

    // ------------------------------------------------------------------------ Lock Screen

    private void buildLock(LinearLayout c) {
        Rows.Section s = rows.section(c, getString(R.string.group_lock_notif),
                getString(R.string.lock_notif_hint));
        toggle(s.card, R.string.lock_notif_glass, 0, Settings.LOCK_NOTIF_GLASS);
        toggle(s.card, R.string.lock_notif_bottom, 0, Settings.LOCK_NOTIF_BOTTOM);
        toggle(s.card, R.string.lock_notif_pile, 0, Settings.LOCK_NOTIF_PILE);

        s = rows.section(c, getString(R.string.group_card_look),
                getString(R.string.lock_notif_refraction_hint));
        GlassSegmented cardStyle = new GlassSegmented(ui, new String[]{
                getString(R.string.lock_notif_style_ios), getString(R.string.lock_notif_style_rim)},
                glass[Settings.LOCK_NOTIF_STYLE] != 0 ? 1 : 0);
        cardStyle.setContentDescription(getString(R.string.lock_notif_style));
        cardStyle.setListener(i -> {
            glass[Settings.LOCK_NOTIF_STYLE] = i;
            save();
        });
        rows.cell(s.card, cardStyle);
        slider(s.card, R.string.lock_notif_radius, Settings.LOCK_NOTIF_RADIUS, DP);
        slider(s.card, R.string.lock_notif_shade, Settings.LOCK_NOTIF_SHADE, PERCENT);
        slider(s.card, R.string.lock_notif_blur, Settings.LOCK_NOTIF_BLUR, PX);
        slider(s.card, R.string.lock_notif_refraction, Settings.LOCK_NOTIF_REFRACTION, PERCENT);

        s = rows.section(c, getString(R.string.font), getString(R.string.font_hint));
        fontTitleValue = rows.valueRow(s.card, getString(R.string.font_title), "", true,
                () -> pickFont(PICK_FONT_TITLE)).value;
        fontTextValue = rows.valueRow(s.card, getString(R.string.font_text), "", true,
                () -> pickFont(PICK_FONT_TEXT)).value;
        rows.actionRow(s.card, getString(R.string.font_system), false, () -> removeFonts());
        updateFonts();

        s = rows.section(c, getString(R.string.lock_icons), getString(R.string.lock_icons_hint));
        iconPackValue = rows.valueRow(s.card, getString(R.string.lock_icons_pack), "", true,
                () -> pickIconPack()).value;
        perAppValue = rows.valueRow(s.card, getString(R.string.lock_icons_apps), "", true,
                () -> pickAppForIcon()).value;
        updateLockIcons();

        s = rows.section(c, getString(R.string.group_fingerprint),
                getString(R.string.lock_hide_fp_icon_hint));
        toggle(s.card, R.string.lock_hide_fp_icon, 0, Settings.LOCK_HIDE_FP_ICON);
    }

    // ------------------------------------------------------------------------ Advanced

    private void buildAdvanced(LinearLayout c) {
        Rows.Section s = rows.section(c, getString(R.string.group_home), null);
        slider(s.card, R.string.lens_height, Settings.LENS_HEIGHT, PERCENT);
        slider(s.card, R.string.glass_saturation, Settings.GLASS_SATURATION, PERCENT);
        toggle(s.card, R.string.lens_depth, R.string.lens_depth_sub, Settings.LENS_DEPTH);

        // OneGlass: no apps-screen section, the One UI apps screen stays stock.

        s = rows.section(c, getString(R.string.group_folder), null);
        slider(s.card, R.string.folder_blur, Settings.FOLDER_BLUR, PERCENT);
        slider(s.card, R.string.folder_panel_tint, Settings.FOLDER_PANEL_TINT, PERCENT);
        slider(s.card, R.string.folder_panel_lens, Settings.FOLDER_PANEL_LENS, PERCENT);

        s = rows.section(c, getString(R.string.group_search), null);
        slider(s.card, R.string.search_gray, Settings.SEARCH_GRAY, PERCENT);
        slider(s.card, R.string.search_blur, Settings.SEARCH_BLUR, PERCENT);
        slider(s.card, R.string.search_saturation, Settings.SEARCH_SATURATION, PERCENT);
        slider(s.card, R.string.search_lens, Settings.SEARCH_LENS, PERCENT);

        s = rows.section(c, getString(R.string.group_gallery), null);
        slider(s.card, R.string.gallery_glare, Settings.GALLERY_GLARE, PERCENT);

        s = rows.section(c, getString(R.string.group_card_rim),
                getString(R.string.card_rim_hint));
        slider(s.card, R.string.lock_notif_rim, Settings.LOCK_NOTIF_RIM, PERCENT);
        colorRow(s.card, R.string.lock_notif_rim_color, Settings.LOCK_NOTIF_RIM_COLOR);
        slider(s.card, R.string.lock_notif_rings, Settings.LOCK_NOTIF_RINGS, COUNT);
        slider(s.card, R.string.lock_notif_band, Settings.LOCK_NOTIF_BAND, TENTH_DP);
        slider(s.card, R.string.lock_notif_corner_shadow, Settings.LOCK_NOTIF_CORNER_SHADOW,
                TENTH_TIMES);
        slider(s.card, R.string.lock_notif_border, Settings.LOCK_NOTIF_BORDER, TENTH_DP);
        colorRow(s.card, R.string.lock_notif_border_color, Settings.LOCK_NOTIF_BORDER_COLOR);

        GlassButton reset = new GlassButton(ui, getString(R.string.glass_reset),
                GlassButton.GLYPH_NONE, false);
        reset.setColors(ui.night ? 0xFF2C2C2E : 0xFFFFFFFF, ui.red);
        reset.setOnClickListener(v -> confirmReset());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = ui.px(12);
        rp.rightMargin = ui.px(12);
        rp.topMargin = ui.px(30);
        c.addView(reset, rp);
        rows.footer(c, getString(R.string.glass_reset_hint));
    }

    private void colorRow(Rows.Card card, int title, int index) {
        Rows.Row r = rows.valueRow(card, getString(title), colorName(glass[index]), true, null);
        rows.tappable(r, () -> {
            CharSequence[] names = new CharSequence[RIM_COLOR_NAMES.length];
            for (int i = 0; i < names.length; i++) names[i] = getString(RIM_COLOR_NAMES[i]);
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(names, Math.max(0, Math.min(names.length - 1,
                            glass[index])), (d, w) -> {
                        glass[index] = w;
                        r.value.setText(colorName(w));
                        save();
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private String colorName(int v) {
        return getString(RIM_COLOR_NAMES[Math.max(0, Math.min(RIM_COLOR_NAMES.length - 1, v))]);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.glass_reset_confirm)
                .setMessage(R.string.glass_reset_hint)
                .setPositiveButton(R.string.glass_reset_do, (d, w) -> resetGlass())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The defaults again, but for the lock screen clock's values, which stay as they are. */
    private void resetGlass() {
        for (int i = 0; i < glass.length; i++) {
            boolean clock = false;
            for (int k : CLOCK) clock |= k == i;
            if (!clock) glass[i] = Settings.GLASS_DEFAULTS[i];
        }
        glass[Settings.LOAD_READOUT] = 0;
        opacity = Settings.DEFAULT_OPACITY;
        tintColor = Settings.DEFAULT_TINT;
        style = Settings.DEFAULT_STYLE;
        save();
        rebuildAll();
    }

    // ------------------------------------------------------------------------ Backup

    private void buildBackup(LinearLayout c) {
        Rows.Section s = rows.section(c, null, getString(R.string.transfer_hint));
        rows.actionRow(s.card, getString(R.string.transfer_export), false,
                () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "oneglass-settings.txt"),
                        EXPORT_SETTINGS));
        rows.actionRow(s.card, getString(R.string.transfer_import), false,
                () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        IMPORT_SETTINGS));
        transferFooter = rows.footer(c, "");
        transferFooter.setTextColor(ui.label);
    }

    private void transferStatus(String s) {
        if (transferFooter != null) transferFooter.setText(s);
    }

    // ------------------------------------------------------------------------ controls

    private void toggle(Rows.Card card, int title, int subtitle, int index) {
        rows.toggleRow(card, getString(title), subtitle != 0 ? getString(subtitle) : null,
                glass[index] != 0, on -> {
                    glass[index] = on ? 1 : 0;
                    applyPreview();
                    save();
                    if (index == Settings.LOCK_NOTIF_GLASS) updateMainValues();
                });
    }

    private void slider(Rows.Card card, int title, int index, int format) {
        GlassSlider bar = new GlassSlider(ui, Settings.GLASS_MIN[index], Settings.GLASS_MAX[index],
                glass[index]);
        Rows.SliderCell cell = rows.sliderCell(card, getString(title), bar);
        cell.value.setText(format(glass[index], format));
        bar.setListener((v, done) -> {
            glass[index] = v;
            cell.value.setText(format(v, format));
            applyPreview();
            if (done) save();
        });
    }

    private String format(int v, int format) {
        switch (format) {
            case DP:
                return getString(R.string.value_dp, String.valueOf(v));
            case PX:
                return getString(R.string.value_px, v);
            case TENTH_DP:
                return v == 0 ? getString(R.string.off)
                        : getString(R.string.value_dp, tenths(v));
            case TENTH_TIMES:
                return getString(R.string.value_times, tenths(v));
            case COUNT:
                return String.valueOf(v);
            case PERCENT_OR_HOME:
                return v == 0 ? getString(R.string.value_as_home)
                        : getString(R.string.value_percent, v);
            default:
                return getString(R.string.value_percent, v);
        }
    }

    private static String tenths(int v) {
        return String.format(Locale.getDefault(), "%.1f", v / 10f);
    }

    /** The values into this process's Config for the preview, which is drawn again. */
    private void applyPreview() {
        Settings.preview(glass, opacity, style, tintColor);
        if (preview != null) preview.refresh();
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit().putInt(Settings.KEY_OPACITY, opacity)
                .putInt(Settings.KEY_STYLE, style).putInt(Settings.KEY_TINT_COLOR, tintColor);
        Intent intent = new Intent(Settings.ACTION_CHANGED)
                .setPackage(Settings.LAUNCHER)
                .putExtra(Settings.KEY_OPACITY, opacity)
                .putExtra(Settings.KEY_STYLE, style)
                .putExtra(Settings.KEY_TINT_COLOR, tintColor);
        for (int i = 0; i < glass.length; i++) {
            e.putInt(Settings.GLASS_KEYS[i], glass[i]);
            intent.putExtra(Settings.GLASS_KEYS[i], glass[i]);
        }
        e.apply();
        sendBroadcast(intent);
        sendToPhotoTile();
    }

    /** Photo Tile draws its widgets with the gallery widget's look: it gets the same values. */
    private void sendToPhotoTile() {
        try {
            sendBroadcast(new Intent(Settings.ACTION_CHANGED)
                    .setPackage("dev.flexy.phototile")
                    .putExtra(Settings.KEY_TINT_COLOR, tintColor)
                    .putExtra(Settings.GLASS_KEYS[Settings.GALLERY_LOOK], glass[Settings.GALLERY_LOOK])
                    .putExtra(Settings.GLASS_KEYS[Settings.GALLERY_LIGHT],
                            glass[Settings.GALLERY_LIGHT]));
        } catch (Throwable ignored) {
            // Photo Tile is not installed.
        }
    }

    // ------------------------------------------------------------------------ picture

    private File pictureFile() {
        return new File(getFilesDir(), SettingsProvider.PICTURE_FILE);
    }

    private void updatePicture() {
        boolean set = pictureFile().isFile();
        if (pictureValue != null) {
            pictureValue.setText(set ? R.string.picture_set : R.string.picture_none);
        }
        if (pictureRemove != null) pictureRemove.setVisibility(set ? View.VISIBLE : View.GONE);
    }

    private void pickPicture() {
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES), PICK_PICTURE);
        } catch (Throwable t) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PICTURE);
            } catch (Throwable ignored) {
                // No picker at all.
            }
        }
    }

    private void removePicture() {
        if (pictureFile().delete()) announcePicture();
        updatePicture();
        if (preview != null) preview.reloadPicture();
    }

    private void announcePicture() {
        sendBroadcast(new Intent(Settings.ACTION_CHANGED)
                .setPackage(Settings.LAUNCHER)
                .putExtra(Settings.KEY_PICTURE, true));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == EXPORT_SETTINGS) {
            exportSettings(uri);
            return;
        }
        if (requestCode == IMPORT_SETTINGS) {
            importSettings(uri);
            return;
        }
        if (requestCode == PICK_FONT_TITLE || requestCode == PICK_FONT_TEXT) {
            String which = requestCode == PICK_FONT_TITLE ? LockFonts.TITLE : LockFonts.TEXT;
            TextView status = requestCode == PICK_FONT_TITLE ? fontTitleValue : fontTextValue;
            if (status != null) status.setText(R.string.picture_saving);
            new Thread(() -> {
                boolean ok = storeFont(uri, which);
                runOnUiThread(() -> {
                    updateFonts();
                    if (!ok) toast(R.string.font_failed);
                    else announceFonts();
                });
            }, "BlurIconsFont").start();
            return;
        }
        if (requestCode != PICK_PICTURE) return;
        if (pictureValue != null) pictureValue.setText(R.string.picture_saving);
        new Thread(() -> {
            boolean ok = storePicture(uri);
            runOnUiThread(() -> {
                updatePicture();
                if (!ok) {
                    toast(R.string.picture_failed);
                } else {
                    announcePicture();
                    if (preview != null) preview.reloadPicture();
                }
            });
        }, "BlurIconsPicture").start();
    }

    private void toast(int text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    /** Decodes the picture (any format the system reads), downsized, and keeps it as a JPEG. */
    private boolean storePicture(Uri uri) {
        File tmp = new File(getFilesDir(), SettingsProvider.PICTURE_FILE + ".tmp");
        try {
            Bitmap bmp = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) -> {
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        float s = Math.min(1f, PICTURE_MAX_SIDE / (float) Math.max(w, h));
                        decoder.setTargetSize(Math.max(1, Math.round(w * s)),
                                Math.max(1, Math.round(h * s)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            try (OutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            bmp.recycle();
            return tmp.renameTo(pictureFile());
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }

    // ------------------------------------------------------------------------ settings file

    /** Every setting as a properties file: the glass values, the tint, the style, the colour. */
    private void exportSettings(Uri uri) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty(Settings.KEY_OPACITY, "" + opacity);
        p.setProperty(Settings.KEY_STYLE, "" + style);
        p.setProperty(Settings.KEY_TINT_COLOR, "" + tintColor);
        p.setProperty(Settings.KEY_WHITE, "" + prefs.getBoolean(Settings.KEY_WHITE,
                Settings.DEFAULT_WHITE));
        for (int i = 0; i < glass.length; i++) {
            p.setProperty(Settings.GLASS_KEYS[i], "" + glass[i]);
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            p.store(out, "Blur Icons " + Config.VERSION);
            transferStatus(getString(R.string.transfer_exported));
        } catch (Throwable t) {
            transferStatus(getString(R.string.transfer_failed, String.valueOf(t.getMessage())));
        }
    }

    /**
     * Reads a file {@link #exportSettings} wrote (by any version: keys are read by name, so
     * one this version no longer has, as the removed ionic theme's, is skipped); keys it lacks
     * keep their values.
     */
    private void importSettings(Uri uri) {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            p.load(in);
        } catch (Throwable t) {
            transferStatus(getString(R.string.transfer_failed, String.valueOf(t.getMessage())));
            return;
        }
        int read = 0;
        int newOpacity = opacity;
        int newStyle = style;
        int newTint = tintColor;
        int[] newGlass = glass.clone();
        Boolean white = null;
        try {
            if (p.containsKey(Settings.KEY_OPACITY)) {
                newOpacity = Math.max(0, Math.min(255,
                        Integer.parseInt(p.getProperty(Settings.KEY_OPACITY).trim())));
                read++;
            }
            if (p.containsKey(Settings.KEY_STYLE)) {
                newStyle = Integer.parseInt(p.getProperty(Settings.KEY_STYLE).trim());
                if (newStyle != Settings.STYLE_BLUR) newStyle = Settings.STYLE_LIQUID;
                read++;
            }
            if (p.containsKey(Settings.KEY_TINT_COLOR)) {
                newTint = Integer.parseInt(p.getProperty(Settings.KEY_TINT_COLOR).trim());
                read++;
            }
            if (p.containsKey(Settings.KEY_WHITE)) {
                white = Boolean.parseBoolean(p.getProperty(Settings.KEY_WHITE).trim());
                read++;
            }
            for (int i = 0; i < newGlass.length; i++) {
                String v = p.getProperty(Settings.GLASS_KEYS[i]);
                if (v == null) continue;
                int value = Integer.parseInt(v.trim());
                newGlass[i] = Math.max(Settings.GLASS_MIN[i], Math.min(Settings.GLASS_MAX[i],
                        value));
                read++;
            }
        } catch (Throwable t) {
            transferStatus(getString(R.string.transfer_failed, String.valueOf(t.getMessage())));
            return;
        }
        opacity = newOpacity;
        style = newStyle;
        tintColor = newTint;
        glass = newGlass;
        glass[Settings.LOAD_READOUT] = 0;
        if (white != null) prefs.edit().putBoolean(Settings.KEY_WHITE, white).apply();
        save();
        rebuildAll();
        transferStatus(getString(R.string.transfer_imported, read));
    }

    // ------------------------------------------------------------------------ fonts

    private void pickFont(int request) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), request);
        } catch (Throwable ignored) {
            // No document picker.
        }
    }

    /** Copies a font file into the module's files, if the system can read it as a font. */
    private boolean storeFont(Uri uri, String which) {
        File out = SettingsProvider.fontFile(this, which);
        File tmp = new File(out.getPath() + ".tmp");
        try {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            }
            new android.graphics.fonts.Font.Builder(tmp).build();
            if (!tmp.renameTo(out)) return false;
            prefs.edit().putString(fontNameKey(which), displayName(uri)).apply();
            return true;
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "?";
    }

    private static String fontNameKey(String which) {
        return "font_" + which + "_name";
    }

    private void removeFonts() {
        boolean any = false;
        for (String which : new String[]{LockFonts.TITLE, LockFonts.TEXT}) {
            any |= SettingsProvider.fontFile(this, which).delete();
            prefs.edit().remove(fontNameKey(which)).apply();
        }
        if (any) announceFonts();
        updateFonts();
    }

    private void updateFonts() {
        String none = getString(R.string.font_none);
        String title = SettingsProvider.fontFile(this, LockFonts.TITLE).isFile()
                ? prefs.getString(fontNameKey(LockFonts.TITLE), "?") : null;
        String text = SettingsProvider.fontFile(this, LockFonts.TEXT).isFile()
                ? prefs.getString(fontNameKey(LockFonts.TEXT), "?") : null;
        // One file serves both (see LockFonts).
        if (fontTitleValue != null) {
            fontTitleValue.setText(title != null ? title : text != null ? text : none);
        }
        if (fontTextValue != null) {
            fontTextValue.setText(text != null ? text : title != null ? title : none);
        }
    }

    private void announceFonts() {
        sendBroadcast(new Intent(LockFonts.ACTION_FONTS).setPackage(LockScreenDiag.SYSTEMUI));
    }

    // ------------------------------------------------------------------------ lock icons

    private void updateLockIcons() {
        String pack = prefs.getString(IconPacks.KEY_PACK, "");
        String name = pack.isEmpty() ? getString(R.string.lock_icons_oneui)
                : IconPacks.APP.equals(pack) ? getString(R.string.lock_icons_own)
                : IconPacks.installed(this).getOrDefault(pack, pack);
        int chosen = 0;
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith(IconPacks.KEY_FOR)) chosen++;
        }
        if (iconPackValue != null) iconPackValue.setText(name);
        if (perAppValue != null) {
            perAppValue.setText(chosen == 0 ? getString(R.string.lock_icons_none_chosen)
                    : getResources().getQuantityString(R.plurals.lock_icons_chosen, chosen,
                    chosen));
        }
    }

    /** The made icons dropped and System UI told: the lock screen shows the new ones. */
    private void announceLockIcons() {
        IconPacks.clearCache(this);
        sendBroadcast(new Intent(LockIcons.ACTION).setPackage(LockScreenDiag.SYSTEMUI));
        updateLockIcons();
    }

    private void pickIconPack() {
        java.util.Map<String, String> packs = IconPacks.installed(this);
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        keys.add("");
        labels.add(getString(R.string.lock_icons_oneui));
        keys.add(IconPacks.APP);
        labels.add(getString(R.string.lock_icons_own));
        for (java.util.Map.Entry<String, String> e : packs.entrySet()) {
            keys.add(e.getKey());
            labels.add(e.getValue());
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.lock_icons_pack)
                .setItems(labels.toArray(new CharSequence[0]), (d, w) -> {
                    prefs.edit().putString(IconPacks.KEY_PACK, keys.get(w)).apply();
                    IconPacks.forget();
                    announceLockIcons();
                });
        if (packs.isEmpty()) b.setMessage(R.string.lock_icons_no_packs);
        b.show();
    }

    /** Every app: the launcher's, and those whose cards showed on the lock screen. */
    private void pickAppForIcon() {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.Map<String, String> apps = new java.util.HashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            apps.put(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
        }
        for (String pkg : prefs.getStringSet(IconPacks.KEY_SEEN, new java.util.HashSet<>())) {
            if (apps.containsKey(pkg)) continue;
            String label = pkg;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable ignored) {
                // Only in Secure Folder: its package name.
            }
            apps.put(pkg, label);
        }
        java.util.List<String> pkgs = new java.util.ArrayList<>(apps.keySet());
        pkgs.sort((a, b) -> apps.get(a).compareToIgnoreCase(apps.get(b)));
        CharSequence[] items = new CharSequence[pkgs.size()];
        for (int i = 0; i < items.length; i++) {
            String choice = prefs.getString(IconPacks.KEY_FOR + pkgs.get(i), "");
            items[i] = choice.isEmpty() ? apps.get(pkgs.get(i))
                    : apps.get(pkgs.get(i)) + "  ·  " + choiceName(choice);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.lock_icons_apps)
                .setItems(items, (d, w) -> chooseIconFor(pkgs.get(w), apps.get(pkgs.get(w))))
                .show();
    }

    private String choiceName(String choice) {
        if (IconPacks.APP.equals(choice)) return getString(R.string.lock_icons_app_own);
        if (IconPacks.ONEUI.equals(choice)) return getString(R.string.lock_icons_app_oneui);
        if (choice.startsWith(IconPacks.FROM_PACK)) {
            return choice.substring(IconPacks.FROM_PACK.length());
        }
        return getString(R.string.lock_icons_default);
    }

    private void chooseIconFor(String pkg, String label) {
        String pack = prefs.getString(IconPacks.KEY_PACK, "");
        boolean hasPack = !pack.isEmpty() && !IconPacks.APP.equals(pack);
        java.util.List<CharSequence> options = new java.util.ArrayList<>();
        options.add(getString(R.string.lock_icons_default));
        options.add(getString(R.string.lock_icons_app_own));
        options.add(getString(R.string.lock_icons_app_oneui));
        if (hasPack) options.add(getString(R.string.lock_icons_from_pack));
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(options.toArray(new CharSequence[0]), (d, w) -> {
                    String key = IconPacks.KEY_FOR + pkg;
                    if (w == 0) {
                        prefs.edit().remove(key).apply();
                    } else if (w == 1) {
                        prefs.edit().putString(key, IconPacks.APP).apply();
                    } else if (w == 2) {
                        prefs.edit().putString(key, IconPacks.ONEUI).apply();
                    } else {
                        pickPackIcon(pkg, label, pack);
                        return;
                    }
                    announceLockIcons();
                })
                .show();
    }

    /** The pack's icons in a grid, searched by name; the one tapped is the app's. */
    private void pickPackIcon(String pkg, String label, String pack) {
        java.util.List<String> all = IconPacks.drawables(this, pack);
        java.util.List<String> shown = new java.util.ArrayList<>();
        android.util.LruCache<String, android.graphics.drawable.Drawable> cache =
                new android.util.LruCache<>(150);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.px(16), ui.px(8), ui.px(16), 0);
        EditText search = new EditText(this);
        search.setHint(R.string.lock_icons_search);
        search.setSingleLine(true);
        box.addView(search);
        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(ui.px(8));
        box.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.px(420)));
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int i) {
                return shown.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View old, ViewGroup parent) {
                android.widget.ImageView iv = old instanceof android.widget.ImageView
                        ? (android.widget.ImageView) old : new android.widget.ImageView(
                        SettingsActivity.this);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(ui.px(52),
                        ui.px(52)));
                String name = shown.get(i);
                android.graphics.drawable.Drawable d = cache.get(name);
                if (d == null) {
                    d = IconPacks.packDrawable(SettingsActivity.this, pack, name);
                    if (d != null) cache.put(name, d);
                }
                iv.setImageDrawable(d);
                return iv;
            }
        };
        grid.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT)
                    .replace(' ', '_');
            shown.clear();
            for (String n : all) {
                if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
                if (shown.size() >= 300) break;
            }
            adapter.notifyDataSetChanged();
        };
        // Starts with what the pack has for it, else its name.
        String mapped = IconPacks.drawableFor(this, pack, pkg);
        String first = label.split(" ")[0].toLowerCase(Locale.ROOT);
        search.setText(mapped != null ? mapped : first);
        filter.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                filter.run();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(label)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            prefs.edit().putString(IconPacks.KEY_FOR + pkg,
                    IconPacks.FROM_PACK + shown.get(pos)).apply();
            dialog.dismiss();
            announceLockIcons();
        });
        dialog.show();
    }
}
