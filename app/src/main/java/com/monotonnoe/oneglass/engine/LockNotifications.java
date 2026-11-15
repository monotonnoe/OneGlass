package com.monotonnoe.oneglass.engine;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;

/**
 * Lock screen notifications as iOS cards: each notification's background, while the phone is
 * locked, is the wallpaper behind it blurred by the compositor (the window's background blur,
 * as the Home screen's plain blur uses), shaded dark, with large round corners, in place of
 * One UI's own card. Unlocked, the shade's notifications are left as they are. System UI.
 */
final class LockNotifications {
    static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    /** Indices in {@link LockGlass#settings}, after the clock's. */
    static final int ON = 12;
    static final int RADIUS = 13;
    static final int SHADE = 14;
    static final int BLUR = 15;
    static final int RIM = 16;
    /** 17 (the dark hairline), 20 (the lighter top) and 21 (the iOS style) are unused. */
    static final int GLOW = 18;
    static final int REFRACTION = 19;
    /** react-glass-rim's card: border-radius 24px. */
    static final float CARD_RADIUS_DP = 24f;

    /** The cards' corner radius in dp: the Card corners setting, iOS's 24 by default. */
    static float cardRadiusDp() {
        int[] st = LockGlass.settings;
        return st.length > RADIUS ? st[RADIUS] : CARD_RADIUS_DP;
    }
    static final int RIM_COLOR = 22;
    static final int RINGS = 23;
    static final int BAND = 24;
    static final int CORNER_SHADOW = 25;
    static final int BORDER = 26;
    static final int BORDER_COLOR = 27;
    private static final int[] RIM_RGB = {0xFFFFFF, 0xB4D2FF, 0xFFE0B8, 0xB69BFF, 0xFFB4D8, 0xB8F5DC};
    private static final Map<View, LockCard> CARDS = new WeakHashMap<>();
    /** The squircle's corners past the compositor's round rect (see draw). */
    private static final Map<View, LockCard> CORNERS = new WeakHashMap<>();

    private static final Map<View, Drawable> BLURS = new WeakHashMap<>();
    private static final Map<ViewTreeObserver, Boolean> OBSERVERS = new WeakHashMap<>();
    private static final ArrayList<View> SYNC_LIST = new ArrayList<>();
    private static Method getViewRootImpl;
    private static Method createBlur;
    private static Method setBlurRadius;
    private static Method setCornerRadius;
    private static Method setBlurColor;
    private static Method setPorterDuffMode;
    private static boolean reflectionFailed;
    private static Field clipTop;
    private static Field clipBottom;
    private static Field actualHeight;
    private static Field actualWidth;
    /** The card's view class (NotificationBackgroundView), for the stack. */
    private static Class<?> backgroundClass;
    private static boolean reported;
    private static boolean locked;
    private static long lockedCheckedAt;

    private LockNotifications() {
    }

    static void install(ClassLoader cl) {
        Class<?> bg;
        try {
            bg = Class.forName(BACKGROUND, false, cl);
        } catch (Throwable t) {
            XLog.i("lock notifications: no " + BACKGROUND + ", left as they are");
            return;
        }
        backgroundClass = bg;
        clipTop = field(bg, "mClipTopAmount");
        clipBottom = field(bg, "mClipBottomAmount");
        actualHeight = field(bg, "mActualHeight");
        actualWidth = field(bg, "mActualWidth");
        java.util.Set<?> hooks = XposedBridge.hookAllMethods(bg, "onDraw",
                SafeHook.before("notification background", LockNotifications::draw));
        XLog.i("lock notifications: hooks " + (hooks == null ? 0 : hooks.size()) + " on "
                + bg.getName() + " (fields: clip " + (clipTop != null) + ", height "
                + (actualHeight != null) + ", width " + (actualWidth != null) + ")");
        hookStockBlur();
        if (Config.DEBUG) logDimensions();
        hookOneLine();
        hookRowHeight(cl);
        hookShadeDrag(cl);
    }

    /** The rows the shade was pulled down from over the lock screen (until the screen is off). */
    private static final Map<View, Boolean> SHADE_DRAGGED = new WeakHashMap<>();

    /**
     * One UI expands the card the shade is pulled down from (goToLockedShadeInternal sets its
     * mUserExpanded): back on the lock screen that is not the user's, and 6.67 took it for
     * a touch, the pile opened by itself and the card stayed One UI's height.
     */
    private static void hookShadeDrag(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.android.systemui.statusbar.LockscreenShadeTransitionController", false,
                    cl);
            java.util.Set<?> h = XposedBridge.hookAllMethods(c, "goToLockedShadeInternal",
                    SafeHook.before("shade drag", p -> {
                        if (p.args.length > 0 && p.args[0] instanceof View
                                && isRow((View) p.args[0])) {
                            SHADE_DRAGGED.put((View) p.args[0], Boolean.TRUE);
                        }
                    }));
            XLog.i("lock notifications: shade drag hooks " + (h == null ? 0 : h.size()));
        } catch (Throwable t) {
            XLog.i("lock notifications: no shade drag (" + t + ")");
        }
    }

    /** iOS's collapsed lock screen card on a 360 dp wide screen: 58 dp tall. */
    static final float CARD_HEIGHT_DP = 58f;
    /** A collapsed One UI notification row on the lock screen: up to this tall it is fitted. */
    static final float COLLAPSED_MAX_DP = 110f;
    /** A collapsed notification's content: One UI's is 72 dp; an expanded one's is taller. */
    static final float CONTENT_COLLAPSED_MAX_DP = 80f;

    /**
     * As iOS's cards, a collapsed notification is 58 dp tall on the lock screen (One UI keeps
     * it at 82 dp, its content 72): the row's heights are capped, so the stack places the
     * cards closer, as iOS does. One UI shows a notification expanded by itself (a long text,
     * the only one): on the lock screen it is collapsed, as on iOS; one the user expanded is
     * left as it is.
     */
    private static void hookRowHeight(ClassLoader cl) {
        try {
            Class<?> row = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    false, cl);
            java.util.Set<?> ex = XposedBridge.hookAllMethods(row, "isExpanded",
                    SafeHook.after("row expanded", p -> {
                        if (!Boolean.TRUE.equals(p.getResult())) return;
                        View v = (View) p.thisObject;
                        if (!on() || !locked(v.getContext()) || !onKeyguard(v)
                                || userExpanded(v)) {
                            return;
                        }
                        p.setResult(false);
                    }));
            int n = ex == null ? 0 : ex.size();
            for (String m : new String[]{"getMinHeight", "getCollapsedHeight",
                    "getIntrinsicHeight"}) {
                java.util.Set<?> h = XposedBridge.hookAllMethods(row, m,
                        SafeHook.after("row height", p -> {
                            if (!(p.getResult() instanceof Integer)) return;
                            View v = (View) p.thisObject;
                            if (!on() || !locked(v.getContext()) || !onKeyguard(v)
                                    || userExpanded(v)) {
                                return;
                            }
                            float d = v.getResources().getDisplayMetrics().density;
                            int r = (Integer) p.getResult();
                            if (r <= CARD_HEIGHT_DP * d || r > COLLAPSED_MAX_DP * d) return;
                            p.setResult(Math.round(CARD_HEIGHT_DP * d));
                        }));
                n += h == null ? 0 : h.size();
            }
            XLog.i("lock notifications: row height hooks " + n);
        } catch (Throwable t) {
            XLog.e("lock notifications: row height", t);
        }
    }

    private static Field userExpandedField;
    private static Method isUserExpanded;

    /**
     * Whether the user expanded the row: its mUserExpanded, else its isUserExpanded(), else
     * not (6.22 took a failed call for expanded, and no row was fitted).
     */
    /** Whether the user expanded this notification row (for LockBottom). */
    static boolean userExpandedRow(View row) {
        return userExpanded(row);
    }

    private static boolean userExpanded(View row) {
        boolean expanded = userExpandedRaw(row);
        // Expanded by One UI as the shade was pulled from it: not on the lock screen itself;
        // once collapsed, the user's own again.
        if (!SHADE_DRAGGED.isEmpty() && SHADE_DRAGGED.containsKey(row)) {
            if (!expanded) {
                SHADE_DRAGGED.remove(row);
            } else if (onKeyguard(row)) {
                return false;
            }
        }
        return expanded;
    }

    private static boolean userExpandedRaw(View row) {
        try {
            if (userExpandedField == null && isUserExpanded == null) {
                for (Class<?> k = row.getClass(); k != null && userExpandedField == null;
                        k = k.getSuperclass()) {
                    try {
                        userExpandedField = k.getDeclaredField("mUserExpanded");
                        userExpandedField.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (userExpandedField == null) {
                    isUserExpanded = row.getClass().getMethod("isUserExpanded");
                }
                XLog.i("lock notifications: user expanded from "
                        + (userExpandedField != null ? "mUserExpanded" : "isUserExpanded()"));
            }
            return userExpandedField != null ? userExpandedField.getBoolean(row)
                    : Boolean.TRUE.equals(isUserExpanded.invoke(row));
        } catch (Throwable t) {
            if (!userExpandedFailed) {
                userExpandedFailed = true;
                XLog.e("lock notifications: user expanded", t);
            }
            return false;
        }
    }

    private static boolean userExpandedFailed;
    private static final java.util.Set<String> CARDS_LOGGED = new java.util.HashSet<>();
    private static int cardLogCalls;
    private static int layoutLogCalls;
    private static boolean rowMethodsLooked;
    private static Method rowExpanded;
    private static Method rowIntrinsic;

    /** Once per kind of card: where the stack has the row and where the card is drawn. */
    private static void logCardOnce(View background, int top, int bottom, int contentBottom) {
        if (!Config.DEBUG) return;
        // A few hundred draws are enough to see the kinds of cards: none after (6.67 built
        // the line and looked methods up in every card draw, for good).
        if (CARDS_LOGGED.size() > 16 || ++cardLogCalls > 300) return;
        View row = background.getParent() instanceof View ? (View) background.getParent() : null;
        if (row == null) return;
        float d = background.getResources().getDisplayMetrics().density;
        String expanded = "?";
        String intrinsic = "?";
        if (!rowMethodsLooked) {
            rowMethodsLooked = true;
            try {
                rowExpanded = row.getClass().getMethod("isExpanded");
            } catch (Throwable ignored) {
            }
            try {
                rowIntrinsic = row.getClass().getMethod("getIntrinsicHeight");
            } catch (Throwable ignored) {
            }
        }
        try {
            if (rowExpanded != null) expanded = String.valueOf(rowExpanded.invoke(row));
            if (rowIntrinsic != null) {
                intrinsic = String.valueOf(Math.round((Integer) rowIntrinsic.invoke(row) / d));
            }
        } catch (Throwable ignored) {
        }
        String line = "clip top " + Math.round(top / d) + ", actual "
                + Math.round(intOf(actualHeight, background, -1) / d) + ", clip bottom "
                + Math.round(intOf(clipBottom, background, 0) / d) + ", content bottom "
                + Math.round(contentBottom / d) + ", card " + Math.round(top / d) + ".."
                + Math.round(bottom / d) + ", row " + Math.round(row.getHeight() / d)
                + " at y " + Math.round(row.getTranslationY() / d) + ", expanded " + expanded
                + ", user expanded " + userExpanded(row) + ", intrinsic " + intrinsic;
        if (CARDS_LOGGED.add(line)) XLog.debug("lock notifications: card, dp: " + line);
    }

    /** The layouts already logged, by the ids of the content's views. */
    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

    /** Once per kind of notification layout: its views, to fit kinds not seen before. */
    private static void logLayoutOnce(View row, View content) {
        if (!Config.DEBUG) return;
        if (LOGGED.size() > 12 || ++layoutLogCalls > 300) return;
        StringBuilder ids = new StringBuilder();
        collectIds(content, ids, 0);
        if (!LOGGED.add(ids.toString())) return;
        logRow(row);
    }

    private static void collectIds(View v, StringBuilder out, int depth) {
        if (depth > 6 || out.length() > 600) return;
        if (v.getId() != View.NO_ID && v.getVisibility() == View.VISIBLE) {
            out.append(v.getId()).append(',');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectIds(g.getChildAt(i), out, depth + 1);
        }
    }

    /** The views moved for the iOS layout, with what they had, to put back when unlocked. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    private static int iconId = -1;
    private static int rowId = -1;

    /**
     * iOS's layout inside a collapsed card, on One UI's views, measured on an iOS 26 lock
     * screen against the card's width: the icon 35 dp (One UI's 44) 12.5 dp from the card's
     * left and centred, the texts starting 57.7 dp from it (One UI's 72), title and text the
     * same size, a line apart as iOS's, the two lines centred. Only moved, scaled and
     * resized; put back when unlocked.
     */
    private static void iosLayout(View background, boolean allowed) {
        COUNTS.remove(background);
        View rowView = background.getParent() instanceof View ? (View) background.getParent()
                : null;
        if (rowView == null) return;
        View content = findContent(rowView, 0);
        if (content == null) return;
        // Under the newest in the stack: its glass only, as iOS's stack shows (its texts would
        // show through the newest's glass).
        fade(content, allowed && !Float.isNaN(LockBottom.behindFrom(rowView)) ? 0f : -1f);
        float d = background.getResources().getDisplayMetrics().density;
        if (iconId == -1) {
            iconId = background.getResources().getIdentifier("icon", "id", "android");
            rowId = background.getResources().getIdentifier("notification_headerless_view_row",
                    "id", "android");
        }
        View texts = rowId != 0 ? content.findViewById(rowId) : null;
        // Collapsed: the content shown is One UI's collapsed one (72 dp, its texts in a row
        // beside the icon), whatever the row's height. Not the expanded one a touch opens
        // (80 dp for a short text, a header and the texts under it: 6.45 took it for
        // collapsed and moved its views as a collapsed one's, askew).
        boolean collapsed = allowed && texts != null && content.getHeight() > 0
                && content.getHeight() <= CONTENT_COLLAPSED_MAX_DP * d
                && !userExpanded(rowView);
        // Laid out in the card at rest, 58 dp from the row's top: not in the part the stack
        // shows now, which it clips and shortens while the cards slide (a quick swipe up):
        // the icon and texts then moved with every frame, askew for a moment.
        int contentTop = 0;
        for (View x = content; x != null && x != rowView; ) {
            contentTop += x.getTop();
            ViewParent vp = x.getParent();
            x = vp instanceof View ? (View) vp : null;
        }
        float cardTop = background.getTop() - contentTop;
        int cardHeight = Math.round(CARD_HEIGHT_DP * d);
        logLayoutOnce(rowView, content);
        // A notification whose content the lock screen hides (Secure Folder's, private
        // ones): One UI shows its header only, the app's name and time and an arrow. Laid out
        // as a collapsed card too, the name as its title.
        if (appNameId == -1) {
            appNameId = background.getResources().getIdentifier("app_name_text", "id",
                    "android");
        }
        View appName = appNameId != 0 ? content.findViewById(appNameId) : null;
        boolean headerOnly = allowed && !collapsed && texts == null
                && appName instanceof android.widget.TextView && appName.isShown()
                && appName.getWidth() > 0 && content.getHeight() > 0
                && content.getHeight() <= CONTENT_COLLAPSED_MAX_DP * d
                && !userExpanded(rowView) && !hasShownTitle(content);
        boolean compact = collapsed || headerOnly;
        View icon = iconId != 0 ? content.findViewById(iconId) : null;
        if (icon != null) {
            // The app's coloured icon from the chosen pack (or its own), as iOS's cards have,
            // collapsed or opened by a touch alike.
            String pkg = allowed ? LockIcons.packageOf(rowView) : null;
            LockIcons.set(icon, pkg != null ? LockIcons.get(background.getContext(), pkg) : null);
        }
        if (icon != null && icon.getWidth() > 0) {
            float s = compact ? Math.min(1f, ICON_DP * d / icon.getWidth()) : 1f;
            // Its top left corner from where it is laid out to 12.5 dp in from the card's.
            float ix = xWithin(icon, content);
            float iy = yWithin(icon, content);
            float tx = compact ? 12.5f * d - ix : 0f;
            float ty = compact ? cardTop + (cardHeight - ICON_DP * d) / 2f - iy : 0f;
            move(icon, s, tx, ty, compact);
        }
        // Where the texts' row is moved below, for the time placed in it now (6.67 used its
        // place before the move: the time sat 14 dp left until the card was drawn again).
        float textsTx = collapsed && texts != null ? Math.min(0f, 57.7f * d - texts.getLeft())
                : 0f;
        relaidOut = false;
        iosTexts(content, d, compact, texts, textsTx);
        headerOnly(content, appName, cardTop, cardHeight, d, headerOnly);
        if (texts != null) {
            float tx = collapsed ? 57.7f * d - texts.getLeft() : 0f;
            // Their middle on the card's middle (One UI centres them in a taller content).
            float ty = collapsed ? cardTop + cardHeight / 2f
                    - (texts.getTop() + texts.getHeight() / 2f) : 0f;
            View title = titleId > 0 ? content.findViewById(titleId) : null;
            View body = bodyId > 0 ? content.findViewById(bodyId) : null;
            float bodyShift = 0f;
            if (collapsed && title instanceof android.widget.TextView
                    && body instanceof android.widget.TextView && isUnder(title, texts)
                    && isUnder(body, texts) && ((android.widget.TextView) title).getBaseline() > 0
                    && ((android.widget.TextView) body).getBaseline() > 0) {
                // iOS's lines: the text's baseline a line below the title's, and the two lines
                // (the title's capitals to the text's baseline) centred in the card.
                float em = ((android.widget.TextView) title).getTextSize();
                float titleBase = yWithin(title, texts)
                        + ((android.widget.TextView) title).getBaseline();
                float bodyBase = yWithin(body, texts)
                        + ((android.widget.TextView) body).getBaseline();
                float pitch = LINE_PITCH * em;
                bodyShift = titleBase + pitch - bodyBase;
                float middle = (titleBase - CAP_HEIGHT * em + titleBase + pitch) / 2f;
                ty = cardTop + cardHeight / 2f - (texts.getTop() + middle);
            }
            if (body != null) move(body, 1f, 0f, bodyShift, collapsed && bodyShift != 0f);
            move(texts, 1f, Math.min(0f, tx), ty, collapsed);
        }
        // A text resized or set in another font is laid out again before the next frame:
        // the card is drawn once more then, its time and lines placed on the new layout.
        if (relaidOut) background.postInvalidateOnAnimation();
        // The group's count, from the texts' row to the background's coordinates.
        CountMark count = lastCount;
        lastCount = null;
        if (count != null && texts != null && collapsed) {
            float rowX = xWithin(texts, rowView) + texts.getTranslationX()
                    - background.getLeft();
            float rowY = yWithin(texts, rowView) + texts.getTranslationY()
                    - background.getTop();
            count.x += rowX;
            count.baseline += rowY;
            COUNTS.put(background, count);
        } else {
            COUNTS.remove(background);
        }
    }

    private static int appNameId = -1;
    private static final String[] DIVIDERS = {"time_divider", "header_text_divider",
            "header_text_secondary_divider"};

    /** A title with text shown in the content: not a header only. */
    private static boolean hasShownTitle(View content) {
        View t = titleId > 0 ? content.findViewById(titleId) : null;
        if (titleId == -1) {
            t = content.findViewById(content.getResources().getIdentifier("title", "id",
                    "android"));
        }
        return t instanceof android.widget.TextView && t.isShown()
                && ((android.widget.TextView) t).getText() != null
                && ((android.widget.TextView) t).getText().length() > 0;
    }

    /**
     * A header-only card as iOS's: the app's name where a title is, in the title's font and
     * size, its capitals centred in the card; the time at the right (iosTexts); the dot
     * between them and the arrow hidden; Secure Folder's badge right after the name.
     */
    private static void headerOnly(View content, View appName, float cardTop, int cardHeight,
                                   float d, boolean on) {
        if (!(appName instanceof android.widget.TextView)
                || !(appName.getParent() instanceof View)) {
            return;
        }
        android.widget.TextView name = (android.widget.TextView) appName;
        View line = (View) appName.getParent();
        android.graphics.Typeface titleFont = LockFonts.title();
        weigh(name, titleFont != null ? titleFont : semibold,
                titleFont != null ? null : "'wght' " + LockFonts.TITLE_WEIGHT,
                on && (titleFont != null || semibold != null));
        track(name, on && titleFont != null);
        float size = TEXT_SIZE_OF_WIDTH * content.getWidth();
        resize(name, size, on && size > 0f);
        android.content.res.Resources res = content.getResources();
        for (String id : DIVIDERS) {
            int i = res.getIdentifier(id, "id", "android");
            View dot = i != 0 ? content.findViewById(i) : null;
            if (dot != null) fade(dot, on ? 0f : -1f);
        }
        float tx = 0f;
        float ty = 0f;
        if (on && name.getBaseline() > 0) {
            if (line != content) {
                // Moved down into the card, past its parents' bounds: not cut off.
                for (View x = line; x != null && x != content; ) {
                    ViewParent vp = x.getParent();
                    if (vp instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) vp).setClipChildren(false);
                        ((android.view.ViewGroup) vp).setClipToPadding(false);
                    }
                    x = vp instanceof View ? (View) vp : null;
                }
            }
            tx = 57.7f * d - xWithin(name, content);
            float baseline = yWithin(name, content) + name.getBaseline();
            float em = name.getTextSize();
            ty = cardTop + cardHeight / 2f + CAP_HEIGHT * em / 2f - baseline;
        }
        move(line, 1f, tx, ty, on && name.getBaseline() > 0);
        // Secure Folder's badge (after the time in One UI's line) right after the name.
        int badgeId = res.getIdentifier("profile_badge", "id", "android");
        View badge = badgeId != 0 ? content.findViewById(badgeId) : null;
        if (badge != null && badge.getParent() == line && badge.getWidth() > 0) {
            float bx = on ? name.getLeft() + name.getWidth() + 6f * d - badge.getLeft() : 0f;
            move(badge, 1f, bx, 0f, on && badge.getVisibility() == View.VISIBLE);
        }
    }

    /** The group's count where iosLayout placed it, on the card. */
    private static void drawCount(Canvas canvas, View background) {
        CountMark m = COUNTS.get(background);
        if (m != null) canvas.drawText(m.text, m.x, m.baseline, m.paint);
    }

    /** {@code v}'s top in {@code ancestor}, as laid out. */
    private static float yWithin(View v, View ancestor) {
        float y = 0f;
        for (View x = v; x != null && x != ancestor; ) {
            y += x.getTop();
            ViewParent p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return y;
    }

    /** The icon's size in a card; One UI's picture fills about 0.95 of it. */
    private static final float ICON_DP = 35f;
    /**
     * The texts' size against the card's width: on iOS 26 their capitals are 2.77 % of it,
     * which in SF Pro (capitals 0.705 of the size) is a size of 3.93 % of the width. The
     * title, the text and the time are all that size.
     */
    private static final float TEXT_SIZE_OF_WIDTH = 0.0393f;
    private static final float CAP_HEIGHT = 0.705f;
    /** From the title's baseline to the text's, in the text's size, as on iOS 26. */
    private static final float LINE_PITCH = 1.24f;

    private static final Map<android.widget.TextView, android.graphics.Typeface> FACES =
            new WeakHashMap<>();
    private static final Map<android.widget.TextView, Float> SIZES = new WeakHashMap<>();
    private static int bodyId = -1;

    /** {@code v}'s text {@code size} px, or One UI's size back. */
    private static void resize(View v, float size, boolean on) {
        if (!(v instanceof android.widget.TextView)) return;
        android.widget.TextView t = (android.widget.TextView) v;
        Float was = SIZES.get(t);
        if (on) {
            if (was == null) {
                was = t.getTextSize();
                SIZES.put(t, was);
            }
            float want = size;
            if (Math.abs(t.getTextSize() - want) > 0.5f) {
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, want);
                relaidOut = true;
            }
        } else if (was != null) {
            SIZES.remove(t);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, was);
        }
    }
    private static final Map<View, Float> ALPHAS = new WeakHashMap<>();
    /** The contents fading out under the newest in the pile (not set at once meanwhile). */
    private static final Map<View, Boolean> HIDING = new WeakHashMap<>();
    /** The contents fading back in out of the pile, with their own alpha they fade to. */
    private static final Map<View, Float> REVEALING = new WeakHashMap<>();
    /** A text of the card being laid out was resized or set in another font, this pass. */
    private static boolean relaidOut;
    private static int titleId = -1;
    private static int timeId = -1;
    private static int expandId = -1;
    private static android.graphics.Typeface semibold;
    private static android.graphics.Typeface regular;
    private static final Map<android.widget.TextView, String> VARIATIONS = new WeakHashMap<>();
    private static final Map<android.widget.TextView, Float> SPACINGS = new WeakHashMap<>();

    /** {@code v}'s letters at SF Pro's tracking, or One UI's spacing back. */
    private static void track(View v, boolean on) {
        if (!(v instanceof android.widget.TextView)) return;
        android.widget.TextView t = (android.widget.TextView) v;
        if (on) {
            if (!SPACINGS.containsKey(t)) SPACINGS.put(t, t.getLetterSpacing());
            if (t.getLetterSpacing() != LockFonts.TRACKING) {
                t.setLetterSpacing(LockFonts.TRACKING);
                relaidOut = true;
            }
        } else if (SPACINGS.containsKey(t)) {
            t.setLetterSpacing(SPACINGS.remove(t));
        }
    }
    /** The typeface each text was given, and what it drew with after (a variation's). */
    private static final Map<android.widget.TextView, android.graphics.Typeface[]> APPLIED =
            new WeakHashMap<>();

    /**
     * {@code v}'s text in {@code face}, with {@code variation} on it when not null, or One
     * UI's back. Set again only when One UI changed it or the face changed.
     */
    private static void weigh(View v, android.graphics.Typeface face, String variation,
                              boolean on) {
        if (!(v instanceof android.widget.TextView)) return;
        android.widget.TextView t = (android.widget.TextView) v;
        if (on && face != null) {
            if (!FACES.containsKey(t)) {
                FACES.put(t, t.getTypeface());
                VARIATIONS.put(t, t.getFontVariationSettings());
            }
            android.graphics.Typeface[] was = APPLIED.get(t);
            if (was != null && was[0] == face && t.getTypeface() == was[1]) return;
            t.setFontVariationSettings(null);
            t.setTypeface(face);
            if (variation != null) t.setFontVariationSettings(variation);
            APPLIED.put(t, new android.graphics.Typeface[]{face, t.getTypeface()});
            relaidOut = true;
        } else if (FACES.containsKey(t)) {
            APPLIED.remove(t);
            t.setTypeface(FACES.remove(t));
            t.setFontVariationSettings(VARIATIONS.remove(t));
        }
    }

    /**
     * iOS's card texts: the title semibold, the text regular, the time at the card's top
     * right (where One UI's expand arrow was; the arrow is hidden but still answers a tap).
     */
    private static void iosTexts(View content, float d, boolean on, View texts,
                                 float textsTx) {
        android.content.res.Resources res = content.getResources();
        if (titleId == -1) {
            titleId = res.getIdentifier("title", "id", "android");
            timeId = res.getIdentifier("time", "id", "android");
            expandId = res.getIdentifier("expand_button", "id", "android");
        }
        View title = titleId != 0 ? content.findViewById(titleId) : null;
        if (bodyId == -1) bodyId = res.getIdentifier("text", "id", "android");
        View body = bodyId != 0 ? content.findViewById(bodyId) : null;
        if (semibold == null) {
            semibold = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600,
                    false);
            regular = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400,
                    false);
        }
        // As iOS's: the title semibold, the text under it regular, thinner. In the fonts the
        // user chose when there are (One UI's does not show the weights apart); else One UI's,
        // through its variable font's weight axis too.
        android.graphics.Typeface titleFont = LockFonts.title();
        android.graphics.Typeface textFont = LockFonts.text();
        weigh(title, titleFont != null ? titleFont : semibold,
                titleFont != null ? null : "'wght' " + LockFonts.TITLE_WEIGHT, on);
        weigh(body, textFont != null ? textFont : regular,
                textFont != null ? null : "'wght' " + LockFonts.TEXT_WEIGHT, on);
        // The time in the text's font when one was chosen, else One UI's (put back if set).
        View time = timeId != 0 ? content.findViewById(timeId) : null;
        weigh(time, textFont, null, on);
        // SF Pro's tracking, which iOS applies and Android does not, in a chosen font.
        track(title, on && titleFont != null);
        track(body, on && textFont != null);
        track(time, on && textFont != null);
        float size = TEXT_SIZE_OF_WIDTH * content.getWidth();
        resize(title, size, on && size > 0f);
        resize(body, size, on && size > 0f);
        resize(time, size, on && size > 0f);
        View expand = expandId != 0 ? content.findViewById(expandId) : null;
        if (expand != null) fade(expand, on ? 0f : -1f);
        if (time != null && time.getVisibility() == View.VISIBLE && time.getWidth() > 0) {
            float x = 0f;
            for (View v = time; v != null && v != content; ) {
                x += v.getLeft() + (v == time ? 0f : v == texts ? textsTx
                        : v.getTranslationX());
                ViewParent p = v.getParent();
                // Its line is narrower than the card: moved past it, it must not be cut off.
                if (on && p instanceof android.view.ViewGroup && p != content) {
                    ((android.view.ViewGroup) p).setClipChildren(false);
                }
                v = p instanceof View ? (View) p : null;
            }
            // Its right edge 16 dp in from the card's right, on the title's line.
            float tx = on ? content.getWidth() - 16f * d - (x + time.getWidth()) : 0f;
            move(time, 1f, Math.max(0f, tx), 0f, on);
        }
        lastCount = countAfterTitle(content, title, time, textFont, size, d, on);
    }

    /** The count iosTexts found, for iosLayout to place. */
    private static CountMark lastCount;

    private static int countId = -1;

    /** A group's count to draw on a card: its text, where, and in what paint. */
    private static final class CountMark {
        String text;
        float x;
        float baseline;
        android.graphics.Paint paint;
    }

    /** The counts drawn on the cards, by their notification backgrounds. */
    private static final Map<View, CountMark> COUNTS = new WeakHashMap<>();

    /**
     * One UI's count of a group's notifications ("2"), at the card's right where the time is
     * now: hidden there, and drawn by the card where the time was, right after the title on
     * its line, in the text's font and size (6.30 moved the view itself, which then did not
     * show). Positions in the texts' row, which is moved after.
     */
    private static CountMark countAfterTitle(View content, View title, View time,
                                             android.graphics.Typeface font, float size,
                                             float d, boolean on) {
        if (countId == -1) {
            android.content.res.Resources res = content.getResources();
            countId = res.getIdentifier("group_child_count_number", "id",
                    "com.android.systemui");
            if (countId == 0) {
                countId = res.getIdentifier("group_child_count_number", "id", "android");
            }
        }
        View count = countId > 0 ? content.findViewById(countId) : null;
        View row = rowId > 0 ? content.findViewById(rowId) : null;
        if (!(count instanceof android.widget.TextView)) return null;
        android.widget.TextView c = (android.widget.TextView) count;
        boolean shown = on && row != null && title instanceof android.widget.TextView
                && isUnder(title, row) && c.getVisibility() == View.VISIBLE
                && c.getText() != null && c.getText().length() > 0 && title.getWidth() > 0
                && ((android.widget.TextView) title).getBaseline() > 0;
        fade(c, shown ? 0f : -1f);
        if (!shown) return null;
        CountMark m = new CountMark();
        m.text = c.getText().toString();
        m.paint = new android.graphics.Paint(c.getPaint());
        m.paint.setColor(c.getCurrentTextColor());
        m.paint.setAlpha(Math.round(255 * ALPHAS.getOrDefault(c, 1f)));
        if (font != null) m.paint.setTypeface(font);
        if (size > 0f) m.paint.setTextSize(size);
        if (font != null) m.paint.setLetterSpacing(LockFonts.TRACKING);
        // Where the time was laid out, else 8 dp after the title (One UI's gap).
        m.x = time != null && time.getVisibility() == View.VISIBLE && isUnder(time, row)
                ? xWithin(time, row) : xWithin(title, row) + title.getWidth() + 8f * d;
        m.baseline = yWithin(title, row) + ((android.widget.TextView) title).getBaseline();
        return m;
    }

    /** {@code v}'s left in {@code ancestor}, as laid out. */
    private static float xWithin(View v, View ancestor) {
        float x = 0f;
        for (View w = v; w != null && w != ancestor; ) {
            x += w.getLeft();
            ViewParent p = w.getParent();
            w = p instanceof View ? (View) p : null;
        }
        return x;
    }

    /**
     * The texts of a card that was under the newest faded in, from where they are (the pile
     * opening, or spreading as the shade is pulled, or the newest swiped away).
     */
    static void revealContent(View row) {
        View content = findContent(row, 0);
        if (content == null) return;
        HIDING.remove(content);
        Float was = ALPHAS.remove(content);
        if (was == null) return;
        REVEALING.put(content, was);
        content.animate().cancel();
        content.animate().alpha(was).setDuration(300).setStartDelay(120)
                .withEndAction(() -> REVEALING.remove(content)).start();
    }

    /**
     * {@code v}'s own alpha kept before it is changed: the one a reveal fades it to, if one
     * runs (a quick pull and let go hid it again mid-fade, and its 0 or 0.3 was kept as its
     * own: its texts stayed dim or gone, unlocked too).
     */
    private static void keepAlpha(View v) {
        if (ALPHAS.containsKey(v)) return;
        Float own = REVEALING.remove(v);
        if (own != null) v.animate().cancel();
        ALPHAS.put(v, own != null ? own : v.getAlpha());
    }

    /**
     * The texts of a card going under the newest faded out (a new notification on top, the
     * shade let go): 6.67 hid them at once when its card was next drawn.
     */
    static void hideContent(View row) {
        View content = findContent(row, 0);
        if (content == null) return;
        keepAlpha(content);
        if (content.getAlpha() <= 0f) return;
        HIDING.put(content, Boolean.TRUE);
        content.animate().cancel();
        content.animate().alpha(0f).setDuration(200).setStartDelay(0)
                .withEndAction(() -> HIDING.remove(content)).start();
    }

    /** {@code alpha} on {@code v}, or its own back when {@code alpha} is negative. */
    private static void fade(View v, float alpha) {
        if (alpha >= 0f) {
            keepAlpha(v);
            // Fading out now: it gets there by itself.
            if (alpha == 0f && HIDING.containsKey(v)) return;
            if (v.getAlpha() != alpha) v.setAlpha(alpha);
        } else if (ALPHAS.containsKey(v)) {
            if (HIDING.remove(v) != null) v.animate().cancel();
            v.setAlpha(ALPHAS.remove(v));
        }
    }

    private static void move(View v, float scale, float tx, float ty, boolean on) {
        float[] was = MOVED.get(v);
        if (on) {
            if (was == null) {
                MOVED.put(v, new float[]{v.getScaleX(), v.getScaleY(), v.getTranslationX(),
                        v.getTranslationY(), v.getPivotX(), v.getPivotY()});
            }
            v.setPivotX(0f);
            v.setPivotY(0f);
            if (v.getScaleX() != scale) v.setScaleX(scale);
            if (v.getScaleY() != scale) v.setScaleY(scale);
            if (v.getTranslationX() != tx) v.setTranslationX(tx);
            if (v.getTranslationY() != ty) v.setTranslationY(ty);
            EXPECTED.put(v, new float[]{scale, tx, ty});
        } else if (was != null) {
            restore(v, was);
        }
    }

    /** Where each moved view was put: One UI may reset it behind the card's back. */
    private static final Map<View, float[]> EXPECTED = new WeakHashMap<>();
    private static final Map<ViewTreeObserver, Boolean> CHECKING = new WeakHashMap<>();
    private static int resetStreak;

    /**
     * Before each frame of the window the cards are in: when One UI has reset a moved view
     * (it puts the cards' views back to their own place, scale and size after a card is
     * swiped away and the others slide up, without the cards being drawn again), the cards
     * are drawn again, so they lay their views out again.
     */
    private static void checkMoved(View v) {
        ViewTreeObserver vto = v.getViewTreeObserver();
        if (!vto.isAlive() || CHECKING.containsKey(vto)) return;
        CHECKING.put(vto, Boolean.TRUE);
        vto.addOnPreDrawListener(() -> {
            if (EXPECTED.isEmpty() || !locked) return true;
            boolean reset = false;
            for (Map.Entry<View, float[]> e : EXPECTED.entrySet()) {
                View x = e.getKey();
                float[] want = e.getValue();
                if (x == null || !x.isAttachedToWindow() || !x.isShown()) continue;
                if (Math.abs(x.getScaleX() - want[0]) > 0.01f
                        || Math.abs(x.getTranslationX() - want[1]) > 0.5f
                        || Math.abs(x.getTranslationY() - want[2]) > 0.5f) {
                    reset = true;
                    break;
                }
            }
            // Drawn again at most three frames running: a view that stays elsewhere (One UI
            // animating it) is left until it is where it was put again.
            resetStreak = reset ? resetStreak + 1 : 0;
            if (reset && resetStreak <= 3) {
                for (View bg : new ArrayList<>(SEEN.keySet())) {
                    if (bg != null && bg.isAttachedToWindow()) bg.invalidate();
                }
            }
            return true;
        });
    }

    private static void restore(View v, float[] was) {
        MOVED.remove(v);
        EXPECTED.remove(v);
        v.setScaleX(was[0]);
        v.setScaleY(was[1]);
        v.setTranslationX(was[2]);
        v.setTranslationY(was[3]);
        v.setPivotX(was[4]);
        v.setPivotY(was[5]);
    }

    /** Puts every moved view back (the phone was unlocked). */
    private static void restoreAll() {
        LockIcons.restoreAll();
        for (Map.Entry<View, float[]> e : new ArrayList<>(MOVED.entrySet())) {
            if (e.getKey() != null) restore(e.getKey(), e.getValue());
        }
        for (Map.Entry<android.widget.TextView, android.graphics.Typeface> e
                : new ArrayList<>(FACES.entrySet())) {
            if (e.getKey() != null) {
                e.getKey().setTypeface(e.getValue());
                e.getKey().setFontVariationSettings(VARIATIONS.get(e.getKey()));
            }
        }
        FACES.clear();
        VARIATIONS.clear();
        for (Map.Entry<android.widget.TextView, Float> e : new ArrayList<>(SPACINGS.entrySet())) {
            if (e.getKey() != null) e.getKey().setLetterSpacing(e.getValue());
        }
        SPACINGS.clear();
        APPLIED.clear();
        for (Map.Entry<android.widget.TextView, Float> e : new ArrayList<>(SIZES.entrySet())) {
            if (e.getKey() != null) {
                e.getKey().setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, e.getValue());
            }
        }
        SIZES.clear();
        for (Map.Entry<View, Float> e : new ArrayList<>(REVEALING.entrySet())) {
            if (e.getKey() == null) continue;
            e.getKey().animate().cancel();
            e.getKey().setAlpha(e.getValue());
        }
        REVEALING.clear();
        for (Map.Entry<View, Float> e : new ArrayList<>(ALPHAS.entrySet())) {
            if (e.getKey() == null) continue;
            if (HIDING.remove(e.getKey()) != null) e.getKey().animate().cancel();
            e.getKey().setAlpha(e.getValue());
        }
        ALPHAS.clear();
        HIDING.clear();
        restoreLines();
    }

    /** Original max lines and cut of the notification texts made one line when locked. */
    private static final Map<android.widget.TextView, Integer> LINES = new WeakHashMap<>();
    private static final Map<android.widget.TextView, android.text.TextUtils.TruncateAt> CUTS =
            new WeakHashMap<>();
    /** Every notification text seen, measured again as the shade comes over the lock screen. */
    private static final Map<android.widget.TextView, Boolean> TEXTS = new WeakHashMap<>();
    private static int textId = -1;

    /**
     * As iOS's lock screen cards: a notification's text is one line, cut with an ellipsis,
     * while the phone is locked; unlocked, the shade shows it as One UI does. The text view is
     * One UI's ImageFloatingTextView, which measures to its max lines.
     */
    private static void hookOneLine() {
        try {
            Class<?> c = Class.forName("com.android.internal.widget.ImageFloatingTextView");
            java.util.Set<?> h = XposedBridge.hookAllMethods(c, "onMeasure",
                    SafeHook.before("one line", p -> {
                        android.widget.TextView t = (android.widget.TextView) p.thisObject;
                        if (textId == -1) {
                            textId = t.getResources().getIdentifier("text", "id", "android");
                        }
                        if (t.getId() != textId) return;
                        // On the lock screen itself: the shade pulled down over it shows
                        // the texts as One UI does (6.67 kept them one line there).
                        boolean one = on() && locked(t.getContext()) && inNotification(t)
                                && textOnKeyguard(t);
                        TEXTS.put(t, Boolean.TRUE);
                        Integer was = LINES.get(t);
                        if (one) {
                            if (t.getMaxLines() != 1) {
                                if (was == null) {
                                    LINES.put(t, t.getMaxLines());
                                    CUTS.put(t, t.getEllipsize());
                                }
                                t.setMaxLines(1);
                                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            }
                        } else if (was != null) {
                            LINES.remove(t);
                            t.setMaxLines(was);
                            t.setEllipsize(CUTS.remove(t));
                        }
                    }));
            XLog.i("lock notifications: one-line text hooks " + (h == null ? 0 : h.size()));
        } catch (Throwable t) {
            XLog.e("lock notifications: one-line text", t);
        }
    }

    /** A text on the lock screen itself: by its stack's state, else its row's. */
    private static boolean textOnKeyguard(View t) {
        View row = null;
        ViewParent p = t.getParent();
        for (int i = 0; i < 20 && p instanceof View; i++, p = p.getParent()) {
            View v = (View) p;
            if (v.getClass().getName().endsWith("NotificationStackScrollLayout")) {
                return LockBottom.onKeyguard(v);
            }
            if (row == null && isRow(v)) row = v;
        }
        return row == null || onKeyguard(row);
    }

    /**
     * The texts as the stack is now: One UI's lines back as the shade comes over the lock
     * screen, one line again on the lock screen (its own measure, which a row's does not
     * reach unless the text's spec changes). Called when the stack's state changes.
     */
    static void oneLineFor(boolean keyguard) {
        if (!keyguard) {
            restoreLines();
            return;
        }
        remeasureTexts();
    }

    private static void remeasureTexts() {
        for (android.widget.TextView t : new ArrayList<>(TEXTS.keySet())) {
            if (t != null && t.isAttachedToWindow()) t.requestLayout();
        }
    }

    private static void restoreLines() {
        for (Map.Entry<android.widget.TextView, Integer> e : new ArrayList<>(LINES.entrySet())) {
            android.widget.TextView t = e.getKey();
            if (t == null) continue;
            t.setMaxLines(e.getValue());
            t.setEllipsize(CUTS.get(t));
        }
        LINES.clear();
        CUTS.clear();
    }

    /**
     * Once each, System UI's notification sizes as they are read (the minimum height of a
     * notification, the gaps between them on the lock screen), to bring the stack to iOS's
     * spacing. Names are looked up once per resource id. Only with {@link Config#DEBUG}.
     */
    private static void logDimensions() {
        final Map<Integer, String> names = new java.util.HashMap<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        SafeHook hook = SafeHook.after("dimension", p -> {
            if (p.args.length != 1 || !(p.args[0] instanceof Integer) || seen.size() > 60) return;
            int id = (Integer) p.args[0];
            String name = names.get(id);
            if (name == null) {
                try {
                    name = ((android.content.res.Resources) p.thisObject).getResourceEntryName(id);
                } catch (Throwable t) {
                    name = "";
                }
                names.put(id, name);
            }
            if (name.isEmpty() || !name.contains("notification")) return;
            if (!(name.contains("height") || name.contains("padding") || name.contains("gap")
                    || name.contains("margin") || name.contains("spacing")
                    || name.contains("divider"))) {
                return;
            }
            if (!seen.add(name)) return;
            Object r = p.getResult();
            float d = ((android.content.res.Resources) p.thisObject).getDisplayMetrics().density;
            float v = r instanceof Integer ? (Integer) r : r instanceof Float ? (Float) r : -1f;
            XLog.debug("lock notifications: dimen " + name + " = " + Math.round(v / d * 10f) / 10f
                    + " dp");
        });
        for (String m : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset",
                "getDimension"}) {
            XposedBridge.hookAllMethods(android.content.res.Resources.class, m, hook);
        }
    }

    /**
     * One UI's own blur on the lock screen's notifications: Samsung's realtime blur
     * (View#semSetBlurInfo) or a RenderEffect, set on the notification's views. Over it the
     * glass card shows nothing of the wallpaper (it blurs a blur, or is hidden under it), so
     * while the phone is locked and the glass cards are on, it is not set. Each distinct
     * caller is logged once.
     */
    private static void hookStockBlur() {
        int n = 0;
        for (String name : new String[]{"semSetBlurInfo", "setRenderEffect"}) {
            java.util.Set<?> h = XposedBridge.hookAllMethods(View.class, name,
                    SafeHook.before(name, p -> {
                        if (p.args.length != 1 || p.args[0] == null) return;
                        View v = (View) p.thisObject;
                        if (!inNotification(v)) return;
                        // The shelf (overflow icons) keeps One UI's own blur: it gets no card.
                        if (isShelf(v.getClass())) return;
                        View row = v.getParent() instanceof View ? (View) v.getParent() : null;
                        View stack = stackOf(v);
                        // Neither in the stack nor a card's: One UI's blur stays.
                        if (stack == null && (row == null || !isRow(row))) return;
                        STOCK_VIEWS.put(v, name);
                        SEEN.put(v, Boolean.TRUE);
                        listen(v.getContext());
                        if (!on() || !locked(v.getContext())) return;
                        // The shade pulled down over the lock screen keeps One UI's look, its
                        // blur too (6.61 took it off there: the cards had no background).
                        // By the stack's state, which One UI sets before it gives the cards
                        // their blur for the shade (setStatusBarState, then
                        // updateDlsStateBlur): the cards' own state is set after, and 6.62
                        // took the blur off as theirs still said the lock screen.
                        boolean keyguard = stack != null ? LockBottom.onKeyguard(stack)
                                : onKeyguard(row);
                        if (!keyguard) return;
                        noteStock(name, v);
                        p.args[0] = null;
                        // One UI set this up instead of drawing the view (initBlurBackground):
                        // without its blur the view must draw now, with the glass card, not
                        // only once a touch makes it draw again.
                        redrawSoon(v);
                    }));
            n += h == null ? 0 : h.size();
        }
        XLog.i("lock notifications: stock blur hooks " + n);
    }

    private static final Map<Class<?>, Boolean> IN_NOTIFICATION = new java.util.HashMap<>();

    /** The view is a notification's, or inside one (a few levels up). */
    private static boolean inNotification(View v) {
        View x = v;
        for (int i = 0; i < 5 && x != null; i++) {
            Class<?> c = x.getClass();
            Boolean b = IN_NOTIFICATION.get(c);
            if (b == null) {
                String n = c.getName();
                b = n.startsWith("com.android.systemui.statusbar.notification.")
                        && !n.contains("Shelf") && !n.contains("StackScrollLayout");
                IN_NOTIFICATION.put(c, b);
            }
            if (b) return true;
            // The stack and what holds it (One UI 8.5's SharedNotificationContainer, under
            // statusbar.notification too) are not a notification's: 6.63-6.67 took the
            // stack's own blur behind the bouncer for a card's.
            if (c.getName().endsWith("NotificationStackScrollLayout")) return false;
            ViewParent p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static final java.util.Set<String> STOCK_SEEN = new java.util.HashSet<>();

    private static void noteStock(String method, View v) {
        if (!Config.DEBUG) return;
        String key = method + " " + v.getClass().getName();
        if (STOCK_SEEN.size() > 20 || !STOCK_SEEN.add(key)) return;
        String caller = "";
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("com.android.systemui.") || c.startsWith("com.samsung.")) {
                caller = c + "#" + e.getMethodName();
                break;
            }
        }
        XLog.debug("lock notifications: stock " + method + " skipped on "
                + v.getClass().getSimpleName() + (caller.isEmpty() ? "" : " by " + caller)
                + " (visible " + (v.getVisibility() == View.VISIBLE) + ", alpha " + v.getAlpha()
                + ", draws " + !v.willNotDraw() + ", " + v.getWidth() + "x" + v.getHeight() + ")");
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // Further up.
            }
        }
        return null;
    }

    private static int intOf(Field f, Object o, int fallback) {
        if (f == null) return fallback;
        try {
            return f.getInt(o);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Whether the phone is locked, asked of the system at most every 300 ms. */
    static boolean locked(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - lockedCheckedAt > 300) {
            lockedCheckedAt = now;
            try {
                KeyguardManager km = c.getSystemService(KeyguardManager.class);
                locked = km != null && km.isKeyguardLocked();
            } catch (Throwable t) {
                locked = false;
            }
        }
        return locked;
    }

    private static final Map<Class<?>, Boolean> SHELF = new java.util.HashMap<>();

    private static boolean isShelf(Class<?> c) {
        Boolean b = SHELF.get(c);
        if (b == null) {
            b = c.getName().contains("Shelf");
            SHELF.put(c, b);
        }
        return b;
    }

    private static java.lang.reflect.Method isOnKeyguard;
    private static boolean noOnKeyguard;

    /**
     * Whether a notification row is on the lock screen itself: not in the shade pulled down
     * over it (still locked, where the glass, the layout and the heights do not belong).
     */
    static boolean onKeyguard(View row) {
        // Not a row (6.63-6.67 were asked of the stack's container, failed, and said "on the
        // lock screen" for every card until System UI restarted: the shade got the lock
        // screen's cards): no answer from it, and nothing learnt from it.
        if (row == null || noOnKeyguard || !isRow(row)) return true;
        try {
            if (isOnKeyguard == null && onKeyguardField == null) {
                try {
                    findOnKeyguard(row);
                } catch (NoSuchFieldException e) {
                    // A row with no such state: asked no more.
                    noOnKeyguard = true;
                    XLog.e("lock notifications: on keyguard", e);
                    return true;
                }
            }
            if (isOnKeyguard != null) return Boolean.TRUE.equals(isOnKeyguard.invoke(row));
            return onKeyguardField.getBoolean(row);
        } catch (Throwable t) {
            // This call only.
            if (!onKeyguardFailed) {
                onKeyguardFailed = true;
                XLog.e("lock notifications: on keyguard", t);
            }
            return true;
        }
    }

    private static boolean onKeyguardFailed;
    private static final Map<Class<?>, Boolean> ROWS = new java.util.HashMap<>();

    /** A notification row's own view (One UI's ActivatableNotificationView or under it). */
    static boolean isRow(View v) {
        Class<?> c = v.getClass();
        Boolean b = ROWS.get(c);
        if (b == null) {
            b = false;
            for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
                if (k.getName().endsWith(".ActivatableNotificationView")) {
                    b = true;
                    break;
                }
            }
            ROWS.put(c, b);
        }
        return b;
    }

    private static java.lang.reflect.Field onKeyguardField;

    /**
     * The row's "on the lock screen" state: isOnKeyguard() if it has one (One UI 8.5 has
     * not), else its mOnKeyguard field, else a boolean field named for it. Logged once.
     */
    private static void findOnKeyguard(View row) throws NoSuchFieldException {
        StringBuilder seen = new StringBuilder();
        for (Class<?> k = row.getClass(); k != null && k != View.class; k = k.getSuperclass()) {
            try {
                isOnKeyguard = k.getDeclaredMethod("isOnKeyguard");
                isOnKeyguard.setAccessible(true);
                XLog.i("lock notifications: on keyguard from " + k.getSimpleName()
                        + "#isOnKeyguard()");
                return;
            } catch (NoSuchMethodException ignored) {
            }
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (f.getType() != boolean.class) continue;
                String n = f.getName();
                if (n.toLowerCase(java.util.Locale.ROOT).contains("keyguard")) {
                    seen.append(' ').append(k.getSimpleName()).append('.').append(n);
                }
                if (onKeyguardField == null && n.equals("mOnKeyguard")) {
                    f.setAccessible(true);
                    onKeyguardField = f;
                }
            }
        }
        if (onKeyguardField == null) {
            for (Class<?> k = row.getClass(); k != null && k != View.class && onKeyguardField
                    == null; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (f.getType() == boolean.class && n.contains("onkeyguard")) {
                        f.setAccessible(true);
                        onKeyguardField = f;
                        break;
                    }
                }
            }
        }
        XLog.i("lock notifications: on keyguard from " + (onKeyguardField == null ? "nothing"
                : onKeyguardField.getDeclaringClass().getSimpleName() + "."
                + onKeyguardField.getName()) + "; boolean keyguard fields:" + seen);
        if (onKeyguardField == null) throw new NoSuchFieldException("mOnKeyguard");
    }

    static boolean on() {
        int[] s = LockGlass.settings;
        return s.length > BLUR && s[ON] != 0;
    }

    /** Every notification background seen, to redraw when the phone locks or unlocks. */
    private static final Map<View, Boolean> SEEN = new WeakHashMap<>();
    /** Views One UI set its own blur on, to take it off when the phone locks. */
    private static final Map<View, String> STOCK_VIEWS = new WeakHashMap<>();
    private static boolean listening;

    /**
     * The phone locks with the notifications already drawn, and One UI does not draw them
     * again until something changes (a touch did): they kept the unlocked look. When the
     * screen goes off or on, or the phone is unlocked, the lock state is asked again and every
     * notification background is drawn again, now and twice more as the lock screen settles;
     * One UI's own blur set before the lock is taken off.
     */
    private static void listen(Context c) {
        if (listening) return;
        listening = true;
        LockFonts.start(c);
        LockIcons.start(c);
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        f.addAction(android.content.Intent.ACTION_SCREEN_ON);
        f.addAction(android.content.Intent.ACTION_USER_PRESENT);
        c.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, android.content.Intent intent) {
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    SHADE_DRAGGED.clear();
                }
                refreshAll();
                MAIN.postDelayed(LockNotifications::refreshAll, 350);
                MAIN.postDelayed(LockNotifications::refreshAll, 1200);
            }
        }, f);
    }

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Draws a notification's background, and its row, now and once the lock screen settles. */
    private static void redrawSoon(View v) {
        Runnable r = () -> {
            if (!v.isAttachedToWindow()) return;
            v.setWillNotDraw(false);
            v.invalidate();
            ViewParent p = v.getParent();
            if (p instanceof View) ((View) p).invalidate();
        };
        MAIN.post(r);
        MAIN.postDelayed(r, 300);
        MAIN.postDelayed(r, 1000);
    }

    /** Whether the rows were last measured locked (null: not yet). */
    private static Boolean laidOutLocked;

    static void refreshAll() {
        try {
            lockedCheckedAt = 0;
            View any = null;
            for (View v : SEEN.keySet()) {
                if (v != null) {
                    any = v;
                    break;
                }
            }
            boolean lockedNow = any != null && on() && locked(any.getContext());
            if (lockedNow) {
                clearStock();
            } else {
                restoreAll();
            }
            // Measured again only when the lock changed (the rows' heights, the one-line
            // texts): 6.67 asked every row at every screen on and off, and 350 ms and 1.2 s
            // after, and the lock screen stuttered as it came up.
            boolean relayout = laidOutLocked == null || laidOutLocked != lockedNow;
            boolean asked = false;
            for (View v : new ArrayList<>(SEEN.keySet())) {
                if (v == null || !v.isAttachedToWindow()) continue;
                v.setWillNotDraw(false);
                v.invalidate();
                ViewParent parent = v.getParent();
                if (parent instanceof View) {
                    ((View) parent).invalidate();
                    if (relayout) {
                        ((View) parent).requestLayout();
                        asked = true;
                    }
                }
            }
            if (asked) {
                laidOutLocked = lockedNow;
                if (lockedNow) remeasureTexts();
            }
        } catch (Throwable t) {
            XLog.e("lock notifications refresh", t);
        }
    }

    /** One UI's own blur off the views of this notification it was set on before the lock. */
    /** The notification stack {@code v} is in, if it is in one yet. */
    private static View stackOf(View v) {
        android.view.ViewParent p = v.getParent();
        for (int i = 0; i < 6 && p instanceof View; i++, p = p.getParent()) {
            if (p.getClass().getName().endsWith("NotificationStackScrollLayout")) return (View) p;
        }
        return null;
    }

    /** The views One UI's blur was given back to, in the shade over the lock screen. */
    private static final Map<View, Boolean> SHADE_BLUR = new WeakHashMap<>();
    private static java.lang.reflect.Method initBlur;
    private static boolean initBlurLooked;

    /**
     * The cards' own blur in the shade pulled down over the lock screen: given back by One
     * UI's initBlurBackground() when the shade shows, taken off again on the lock screen.
     */
    static void stockForShade(android.view.ViewGroup stack, boolean shade) {
        if (shade) {
            for (int i = 0; i < stack.getChildCount(); i++) {
                View row = stack.getChildAt(i);
                if (!(row instanceof android.view.ViewGroup)) continue;
                android.view.ViewGroup g = (android.view.ViewGroup) row;
                for (int j = 0; j < g.getChildCount(); j++) {
                    View bg = g.getChildAt(j);
                    if (!STOCK_VIEWS.containsKey(bg) || SHADE_BLUR.containsKey(bg)) continue;
                    SHADE_BLUR.put(bg, Boolean.TRUE);
                    initBlur(row);
                }
            }
        } else if (!SHADE_BLUR.isEmpty()) {
            for (View bg : new ArrayList<>(SHADE_BLUR.keySet())) {
                String name = bg != null ? STOCK_VIEWS.get(bg) : null;
                if (name == null) continue;
                try {
                    java.lang.reflect.Method m = stockSetter(name);
                    if (m != null) m.invoke(bg, new Object[]{null});
                } catch (Throwable ignored) {
                }
                bg.setWillNotDraw(false);
                bg.invalidate();
            }
            SHADE_BLUR.clear();
        }
    }

    /** Unlocked: One UI's blur stays as One UI has it. */
    static void forgetShadeBlur() {
        SHADE_BLUR.clear();
    }

    private static void initBlur(View row) {
        if (!initBlurLooked) {
            initBlurLooked = true;
            for (Class<?> k = row.getClass(); k != null && initBlur == null;
                    k = k.getSuperclass()) {
                try {
                    initBlur = k.getDeclaredMethod("initBlurBackground");
                    initBlur.setAccessible(true);
                } catch (NoSuchMethodException ignored) {
                }
            }
            XLog.i("lock notifications: shade blur back " + (initBlur != null
                    ? "through initBlurBackground()" : "not possible"));
        }
        try {
            if (initBlur != null && initBlur.getDeclaringClass().isInstance(row)) {
                initBlur.invoke(row);
            }
        } catch (Throwable t) {
            XLog.e("shade blur", t);
        }
    }

    private static void clearStock() {
        if (STOCK_VIEWS.isEmpty()) return;
        for (Map.Entry<View, String> e : new ArrayList<>(STOCK_VIEWS.entrySet())) {
            View v = e.getKey();
            if (v == null || !v.isAttachedToWindow() || SHADE_BLUR.containsKey(v)) continue;
            try {
                java.lang.reflect.Method m = stockSetter(e.getValue());
                if (m != null) m.invoke(v, new Object[]{null});
            } catch (Throwable ignored) {
                // Not this view's to clear.
            }
        }
    }

    private static final Map<String, java.lang.reflect.Method> STOCK_SETTERS =
            new java.util.HashMap<>();

    /** View's one-argument method of that name (semSetBlurInfo, setRenderEffect), found once. */
    private static java.lang.reflect.Method stockSetter(String name) {
        if (!STOCK_SETTERS.containsKey(name)) {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method x : View.class.getMethods()) {
                if (x.getName().equals(name) && x.getParameterTypes().length == 1) {
                    m = x;
                    break;
                }
            }
            STOCK_SETTERS.put(name, m);
        }
        return STOCK_SETTERS.get(name);
    }

    private static void draw(MethodHookParam p) throws Throwable {
        if (p.args.length != 1 || !(p.args[0] instanceof Canvas)) return;
        View v = (View) p.thisObject;
        Canvas canvas = (Canvas) p.args[0];
        if (!isShelf(v.getClass())) {
            SEEN.put(v, Boolean.TRUE);
            listen(v.getContext());
            checkMoved(v);
        }
        // The shelf under the notifications (its overflow icons) is not a card.
        if (isShelf(v.getClass())) return;
        View row = v.getParent() instanceof View ? (View) v.getParent() : null;
        if (!on() || !canvas.isHardwareAccelerated() || !locked(v.getContext())
                || !onKeyguard(row)) {
            // The shade pulled down over the lock screen is the shade: One UI's look.
            if (locked && row != null && !onKeyguard(row)) iosLayout(v, false);
            release(v);
            return;
        }
        int top = intOf(clipTop, v, 0);
        int bottom = intOf(actualHeight, v, v.getHeight()) - intOf(clipBottom, v, 0);
        int width = intOf(actualWidth, v, v.getWidth());
        if (width <= 0) width = v.getWidth();
        int left = v.isLayoutRtl() ? v.getWidth() - width : 0;
        // As iOS's cards, the glass hugs the content: One UI keeps a notification at least
        // 82 dp tall while a collapsed one's content is 72 dp, leaving an empty strip at the
        // bottom. The card ends where the content ends.
        int contentBottom = contentBottom(v);
        if (contentBottom > top + 24 && contentBottom < bottom) bottom = contentBottom;
        // Where the card ends at rest, without One UI's clip as it slides past the shelf
        // (6.67 piled a card from its clipped 2-4 dp, and it hung below the pile).
        int rest = intOf(actualHeight, v, v.getHeight());
        if (contentBottom > 24 && contentBottom < rest) rest = contentBottom;
        // A row's other background (One UI keeps two, one with no height: -1) is no card.
        if (row != null && rest > 0) {
            Integer restWas = CARD_REST.put(row, v.getTop() + rest);
            if (LockBottom.tracing() && (restWas == null || restWas != v.getTop() + rest)) {
                LockBottom.trace("card #" + LockBottom.id(row) + " rest " + restWas + " -> "
                        + (v.getTop() + rest) + " (actual " + intOf(actualHeight, v, -1)
                        + ", content " + contentBottom + ")");
            }
        }
        if (bottom - top <= 1 || width <= 1) {
            if (row != null && LockBottom.tracing()) {
                LockBottom.trace("card #" + LockBottom.id(row) + " not drawn, clipped to " + top
                        + ".." + bottom + ", width " + width);
            }
            return;
        }
        iosLayout(v, true);
        logCardOnce(v, top, bottom, contentBottom);
        if (row != null) {
            Integer bottomWas = CARD_BOTTOM.put(row, v.getTop() + bottom);
            if (LockBottom.tracing() && (bottomWas == null || bottomWas != v.getTop() + bottom)) {
                LockBottom.trace("card #" + LockBottom.id(row) + " drawn end " + bottomWas
                        + " -> " + (v.getTop() + bottom) + " (clip " + top + "/"
                        + intOf(clipBottom, v, 0) + ")");
            }
        }
        // A card under the newest in the stack is drawn whole, its glass under the newest's
        // too (6.60-6.64 drew only its part below the newest: with the newest swiped aside, the
        // rest was a dark band without glass). Only its texts are hidden (iosLayout).
        drawCard(p, v, canvas, left, top, width, bottom, top);
    }

    /** Where each row's card ends, in the row, as last drawn (for the stack). */
    static final Map<View, Integer> CARD_BOTTOM = new WeakHashMap<>();
    /** Where each row's card ends, in the row, unclipped (for the pile). */
    static final Map<View, Integer> CARD_REST = new WeakHashMap<>();

    /** The row's card (its shown NotificationBackgroundView), or null. */
    private static View background(View row) {
        if (backgroundClass == null || !(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup g = (android.view.ViewGroup) row;
        View any = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!backgroundClass.isInstance(c) || isShelf(c.getClass())) continue;
            // The shown one with a height (One UI keeps a second, with none: -1).
            if (c.getVisibility() == View.VISIBLE && intOf(actualHeight, c, c.getHeight()) > 0) {
                return c;
            }
            if (any == null) any = c;
        }
        return any;
    }

    /**
     * Where the row's card ends at rest, in the row, by its layout as {@link #CARD_REST} is
     * measured when it is drawn (for a card not drawn on the lock screen yet), or -1.
     */
    static int restBottom(View row) {
        View v = background(row);
        if (v == null) return -1;
        int rest = intOf(actualHeight, v, v.getHeight());
        int content = contentBottom(v);
        if (content > 24 && content < rest) rest = content;
        return rest > 0 ? v.getTop() + rest : -1;
    }

    /** For the stack's trace: the row's card as One UI has it now (top+actual, clips, content). */
    static String cardTrace(View row) {
        View v = background(row);
        if (v == null) return "card -";
        return "card " + v.getTop() + "+" + intOf(actualHeight, v, -1) + " clip "
                + intOf(clipTop, v, 0) + "/" + intOf(clipBottom, v, 0) + " content "
                + contentBottom(v);
    }

    private static void drawCard(MethodHookParam p, View v, Canvas canvas, int left, int top,
                                 int width, int bottom, int blurTop) throws Throwable {
        float density = v.getResources().getDisplayMetrics().density;
        float r = squircleRadius(cardRadiusDp() * density, width, bottom - top);
        int[] st = LockGlass.settings;
        // The edge bends what lies behind it through the blur, as iOS's cards do: the module
        // draws the whole card from the picture then (the compositor can only blur).
        int refraction = st.length > REFRACTION ? st[REFRACTION] : 60;
        // The compositor's blur is off in power saving (and on some thermal states): the
        // card then blurs the picture itself, or it would show the wallpaper sharp.
        boolean ownBlur = !crossWindowBlur(v.getContext());
        if ((refraction > 0 || ownBlur) && android.os.Build.VERSION.SDK_INT >= 33) {
            // Glass drawn by the module from the picture: no compositor blur.
            LockCard card = CARDS.get(v);
            if (card == null) {
                card = new LockCard();
                CARDS.put(v, card);
            }
            release(v);
            if (LockGlass.picture() == null) LockGlass.requestPicture();
            if (card.draw(canvas, v, left, top, width, bottom - top, r,
                    blurRadius(st[BLUR], density), refraction)) {
                watch(v);
                drawShade(canvas, v, left, top, left + width, bottom, r);
                drawRim(canvas, v, left, top, left + width, bottom);
                drawCount(canvas, v);
                p.setResult(null);
                return;
            }
        } else {
            LockCard card = CARDS.remove(v);
            if (card != null) card.release();
        }
        Drawable b = blurFor(v);
        if (b == null) return;
        // A card under the newest: blurred from its strip's top only (the canvas's clip does
        // not reach the compositor's blur, which showed One UI's shape under the newest).
        b.setBounds(left, blurTop, left + width, bottom);
        // The compositor blurs only round rects: its corners are made round enough
        // (1.38 times the squircle's radius) to lie inside the squircle everywhere...
        float hole = Math.min(r * 1.38f, Math.min(width, bottom - blurTop) / 2f);
        setCornerRadius.invoke(b, hole);
        b.draw(canvas);
        // ...and the squircle's corners past it are blurred by the module from the picture,
        // so the blurred shape is the card's own, not One UI's round rect.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            LockCard corners = CORNERS.get(v);
            if (corners == null) {
                corners = new LockCard();
                CORNERS.put(v, corners);
            }
            if (corners.draw(canvas, v, left, top, width, bottom - top, r,
                    blurRadius(st[BLUR], density), 0, hole)) {
                watchCorners(v);
            }
        }
        drawShade(canvas, v, left, top, left + width, bottom, r);
        drawRim(canvas, v, left, top, left + width, bottom);
        // One UI's own card is not drawn over the glass.
        p.setResult(null);
        if (!reported) {
            reported = true;
            XLog.i("lock notifications: glass card on " + v.getClass().getSimpleName() + " "
                    + width + "x" + (bottom - top));
            if (Config.DEBUG) logLayout(v);
        }
    }

    /** The view's blur, made once and set to the current settings on each draw. */
    private static Drawable blurFor(View v) throws Exception {
        if (!resolve()) return null;
        Object root = getViewRootImpl.invoke(v);
        if (root == null) return null;
        Drawable b = BLURS.get(v);
        if (b == null) {
            b = (Drawable) createBlur.invoke(root);
            if (b == null) return null;
            b.setCallback(v);
            setPorterDuffMode.invoke(b, PorterDuff.Mode.SRC_OVER);
            BLURS.put(v, b);
            watch(v);
        }
        int[] s = LockGlass.settings;
        float density = v.getResources().getDisplayMetrics().density;
        setBlurRadius.invoke(b, blurRadius(s[BLUR], density));
        // The shade is drawn by drawShade in the card's own shape, over a clear region.
        setBlurColor.invoke(b, 0);
        b.setAlpha(Math.round(255f * alphaOf(v)));
        return b;
    }


    /**
     * What makes it glass rather than a blur: a hairline rim lit at the top and fainter down
     * the sides, a little brighter again at the bottom edge, and a faint sheen over the upper
     * part of the card, as iOS's lock screen cards and media player have.
     */

    /**
     * The edge as iOS 27 draws its glass (ionic-theme-ios27's light-rim / dark-rim, as the
     * lock screen clock's glass has it): a thin dark hairline right at the outline, inside it a
     * light rim lit where the edge faces up or down and clear at the sides, and a soft glow a
     * couple of dp inside the top and bottom edges.
     */
    /**
     * The card's edge, as react-glass-rim's glass card (see drawGlassRim): its border, its
     * directional rim and its inset sheen.
     */
    private static void drawRim(Canvas canvas, View v, int left, int top, int right, int bottom) {
        int[] s = LockGlass.settings;
        int rim = s.length > RIM ? s[RIM] : 30;
        // No inset sheen: none shows on iOS 26's card.
        int glow = 0;
        float density = v.getResources().getDisplayMetrics().density;
        int h = bottom - top;
        int w = right - left;
        float r = squircleRadius(cardRadiusDp() * density, w, h);
        int alpha = Math.round(255 * alphaOf(v));
        canvas.save();
        canvas.translate(0, top);
        drawGlassRim(canvas, s, left, w, h, r, density, rim, glow, alpha);
        canvas.restore();
    }


    private static final android.graphics.Paint SHADE_PAINT =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Path PATH = new android.graphics.Path();

    /** The corner radius asked for: at most half the shorter side (a capsule). */
    private static float squircleRadius(float r, float w, float h) {
        return Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
    }

    /** Where Apple's corner bends from, in radii. */
    private static final float BEND = 1.52866483f;
    /**
     * One corner of Apple's continuous rounded rect of radius 1, in the corner's own frame (u
     * in from the side edge, v in from the top edge): the start on the top edge, then three
     * cubics (two controls and an end each) down to the side edge. iOS's UIBezierPath values.
     */
    private static final float[] APPLE_CORNER = {
            BEND, 0f,
            1.08849323f, 0f, 0.86840689f, 0f, 0.63149399f, 0.07491100f,
            0.37282392f, 0.16905899f, 0.16905899f, 0.37282392f, 0.07491100f, 0.63149399f,
            0f, 0.86840689f, 0f, 1.08849323f, 0f, BEND,
    };
    /** A quarter circle of radius 1 in the same frame and the same three cubics. */
    private static final float[] ROUND_CORNER = roundCorner();
    private static final float[] CORNER = new float[20];

    private static float[] roundCorner() {
        float[] out = new float[20];
        out[0] = 1f;
        out[1] = 0f;
        float k = (float) (4.0 / 3.0 * Math.tan(Math.toRadians(30) / 4.0));
        for (int s = 0; s < 3; s++) {
            double f0 = Math.toRadians(30 * s);
            double f1 = Math.toRadians(30 * (s + 1));
            int i = 2 + s * 6;
            // The point at angle f: (1 - sin f, 1 - cos f), its tangent (-cos f, sin f).
            out[i] = (float) (1 - Math.sin(f0) - k * Math.cos(f0));
            out[i + 1] = (float) (1 - Math.cos(f0) + k * Math.sin(f0));
            out[i + 2] = (float) (1 - Math.sin(f1) + k * Math.cos(f1));
            out[i + 3] = (float) (1 - Math.cos(f1) - k * Math.sin(f1));
            out[i + 4] = (float) (1 - Math.sin(f1));
            out[i + 5] = (float) (1 - Math.cos(f1));
        }
        return out;
    }

    /**
     * {@code out} set to a rectangle with Apple's continuous corners of radius {@code r} (the
     * curve iOS draws for a rounded rect: the straight sides run into the corners without a
     * break in curvature, the bend starting 1.53 r from each corner). A radius whose bend does
     * not fit the shorter side is drawn at the largest that does (19 dp on a 58 dp card), as
     * iOS does; past iOS's 24 the corners go on, smoothly, to a capsule at half the shorter
     * side, so a larger setting still rounds them more.
     */
    static void squircle(android.graphics.Path out, float l, float t, float rr, float b,
                         float r) {
        out.reset();
        float w = rr - l;
        float h = b - t;
        r = squircleRadius(r, w, h);
        if (r <= 0f) {
            out.addRect(l, t, rr, b, android.graphics.Path.Direction.CW);
            return;
        }
        float half = Math.min(w, h) / 2f;
        float fit = half / BEND;
        float apple = Math.min(r, fit);
        // As on iOS a radius past the fit draws the corner at the fit (iOS's 24 dp on a 58 dp
        // card is its 19), up to 1.265 times it (24 there); from there on to the capsule.
        float from = Math.min(half, fit * 1.265f);
        float mix = r > from && half > from ? (r - from) / (half - from) : 0f;
        for (int i = 0; i < CORNER.length; i++) {
            CORNER[i] = APPLE_CORNER[i] * apple * (1f - mix) + ROUND_CORNER[i] * half * mix;
        }
        // From the top left corner's end on the top edge, clockwise.
        out.moveTo(l + CORNER[19], t + CORNER[18]);
        corner(out, rr, t, -1f, 1f, false);
        corner(out, rr, b, -1f, -1f, true);
        corner(out, l, b, 1f, -1f, false);
        corner(out, l, t, 1f, 1f, true);
        out.close();
    }

    /**
     * One corner of {@link #CORNER} at ({@code x0}, {@code y0}), its frame turned by the signs
     * and, for the corners run from the side edge, with u and v swapped.
     */
    private static void corner(android.graphics.Path out, float x0, float y0, float sx,
                               float sy, boolean swap) {
        float[] c = CORNER;
        int u = swap ? 1 : 0;
        int v = swap ? 0 : 1;
        out.lineTo(x0 + sx * c[u], y0 + sy * c[v]);
        for (int i = 2; i < 20; i += 6) {
            out.cubicTo(x0 + sx * c[i + u], y0 + sy * c[i + v],
                    x0 + sx * c[i + 2 + u], y0 + sy * c[i + 2 + v],
                    x0 + sx * c[i + 4 + u], y0 + sy * c[i + 4 + v]);
        }
    }

    /** The card's milkiness in its squircle. */
    private static void drawShade(Canvas canvas, View v, int left, int top, int right,
                                  int bottom, float r) {
        // A light milkiness, set in the settings: white over the blur, frosting the glass a
        // little (0 for none).
        int[] s = LockGlass.settings;
        int milk = Math.max(0, Math.min(100, s[SHADE]));
        if (milk <= 0) return;
        squircle(PATH, left, top, right, bottom, r);
        SHADE_PAINT.setShader(null);
        SHADE_PAINT.setColor(0xFFFFFF | (Math.round(255f * milk / 100f * alphaOf(v)) << 24));
        canvas.drawPath(PATH, SHADE_PAINT);
    }

    private static final android.graphics.Paint RING_PAINT =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Paint SHEEN_FILL =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Path OUTSIDE = new android.graphics.Path();
    private static final Matrix ROT = new Matrix();

    /**
     * react-glass-rim's glass card edge (royroki/react-glass-rim, rimGradient.ts flat() and the
     * playground's .glass): a conic gradient round the centre, white everywhere but at the top
     * right and bottom left corners, where it fades to nothing (8 dp of full shadow each side
     * of the corner, 20 dp of ramp beyond, at most 60 % of a half edge), drawn as two rings of
     * 0.4 dp, the outer at the full intensity (0.3 at rim 40) and the inner at half; and its
     * inset sheen, inset 2 2 14 white 6 % (at glow 20), a soft light inside the top left edge.
     */
    private static int at(int[] s, int i, int fallback) {
        return s.length > i ? s[i] : fallback;
    }

    private static int rgbOf(int index) {
        return RIM_RGB[Math.max(0, Math.min(RIM_RGB.length - 1, index))];
    }

    private static void drawGlassRim(Canvas canvas, int[] s, int left, int w, int h, float r,
                                     float density, int rim, int glow, int alpha) {
        float cx = left + w / 2f;
        float cy = h / 2f;
        if (glow > 0) {
            // The inset shadow: what lies outside the card, moved 2 dp right and down,
            // blurred by 14 dp, seen through the card.
            canvas.save();
            squircle(PATH, left, 0, left + w, h, r);
            canvas.clipPath(PATH);
            OUTSIDE.reset();
            OUTSIDE.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            float pad = density * 40f;
            OUTSIDE.addRect(left - pad, -pad, left + w + pad, h + pad,
                    android.graphics.Path.Direction.CW);
            OUTSIDE.addPath(PATH);
            OUTSIDE.offset(density * 2f, density * 2f);
            SHEEN_FILL.setMaskFilter(new android.graphics.BlurMaskFilter(density * 7f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
            SHEEN_FILL.setColor(white(Math.min(1f, 0.06f * glow / 20f) * alpha / 255f));
            canvas.drawPath(OUTSIDE, SHEEN_FILL);
            canvas.restore();
        }
        // Its coloured border (border: Npx solid colour), under the rim, on the outline.
        float border = at(s, BORDER, 0) / 10f * density;
        if (border > 0f) {
            RING_PAINT.setShader(null);
            RING_PAINT.setStyle(android.graphics.Paint.Style.STROKE);
            RING_PAINT.setStrokeWidth(border);
            RING_PAINT.setColor(0xFF000000 | rgbOf(at(s, BORDER_COLOR, 3)));
            RING_PAINT.setAlpha(alpha);
            float bi = border / 2f;
            squircle(PATH, left + bi, bi, left + w - bi, h - bi, Math.max(0f, r - bi));
            canvas.drawPath(PATH, RING_PAINT);
        }
        if (rim <= 0) return;
        // The rim as react-glass-rim's project picture draws its glass card (og.svg): a
        // stroke with a diagonal gradient across the card's box, 0.9 white at the top left
        // corner, 0.12 along the diagonal through the other two corners, 0.9 again at the
        // bottom right. Intensity is the corners' opacity (90 = 0.9), the corner shadow scale
        // darkens the middle (1.0 = 0.12 / 0.9 of it), rings x band its width.
        float peak = Math.min(1f, rim / 100f);
        float shadowScale = Math.max(0.5f, at(s, CORNER_SHADOW, 10) / 10f);
        float mid = Math.min(peak, peak * (0.12f / 0.9f) / shadowScale);
        int accent = rgbOf(at(s, RIM_COLOR, 0));
        int steps = Math.max(1, at(s, RINGS, 1));
        float width = Math.max(0.5f, density * at(s, BAND, 12) / 10f * steps);
        float fa = alpha / 255f;
        int[] colors = {
                (Math.round(255f * peak * fa) << 24) | accent,
                (Math.round(255f * mid * fa) << 24) | accent,
                (Math.round(255f * peak * fa) << 24) | accent};
        // In the box's own units, as objectBoundingBox: the middle runs through the top right
        // and bottom left corners whatever the card's shape.
        android.graphics.LinearGradient lg = new android.graphics.LinearGradient(0f, 0f, 1f, 1f,
                colors, new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        ROT.setScale(w, h);
        ROT.postTranslate(left, 0f);
        lg.setLocalMatrix(ROT);
        RING_PAINT.setShader(lg);
        RING_PAINT.setStyle(android.graphics.Paint.Style.STROKE);
        RING_PAINT.setStrokeWidth(width);
        float inset = width / 2f;
        squircle(PATH, left + inset, inset, left + w - inset, h - inset, Math.max(0f, r - inset));
        canvas.drawPath(PATH, RING_PAINT);
    }


    private static int white(float a) {
        return (Math.round(255f * Math.max(0f, Math.min(1f, a))) << 24) | 0xFFFFFF;
    }

    /**
     * The blur as react-glass-rim's backdrop-filter: blur(Npx), N in CSS px (dp): its Gaussian
     * sigma in screen pixels, as a blur radius (sigma = 0.577 r + 0.5).
     */
    static int blurRadius(int cssPx, float density) {
        float sigma = Math.max(0, cssPx) * density;
        return sigma <= 0.5f ? 0 : Math.round((sigma - 0.5f) / 0.57735f);
    }

    private static Boolean blurEnabled;
    private static boolean blurListening;

    /** Whether the compositor blurs behind windows now (off in power saving). */
    private static boolean crossWindowBlur(Context c) {
        if (blurEnabled != null) return blurEnabled;
        try {
            android.view.WindowManager wm = c.getSystemService(android.view.WindowManager.class);
            blurEnabled = wm.isCrossWindowBlurEnabled();
            if (!blurListening) {
                blurListening = true;
                wm.addCrossWindowBlurEnabledListener(c.getMainExecutor(), enabled -> {
                    blurEnabled = enabled;
                    XLog.i("lock notifications: system blur " + (enabled ? "on" : "off,"
                            + " the cards blur the picture themselves"));
                    refreshAll();
                });
            }
            if (!blurEnabled) {
                XLog.i("lock notifications: system blur off (power saving?), the cards blur"
                        + " the picture themselves");
            }
        } catch (Throwable t) {
            blurEnabled = true;
        }
        return blurEnabled;
    }

    /**
     * Once: the notification's layout as One UI makes it (the row, its content, the icon and
     * the texts, their sizes and paddings, in dp), to bring the card to iOS's proportions.
     */
    private static void logRow(View row) {
        try {
            float d = row.getResources().getDisplayMetrics().density;
            StringBuilder b = new StringBuilder("lock notifications: layout, dp: row ")
                    .append(Math.round(row.getWidth() / d)).append('x')
                    .append(Math.round(row.getHeight() / d));
            describe(row, b, d, 0);
            XLog.debug(b.toString());
        } catch (Throwable t) {
            XLog.e("lock notifications layout", t);
        }
    }

    private static void logLayout(View background) {
        try {
            float d = background.getResources().getDisplayMetrics().density;
            View row = background;
            for (int i = 0; i < 6 && row != null; i++) {
                if (row.getClass().getName().contains("ExpandableNotificationRow")) break;
                ViewParent p = row.getParent();
                row = p instanceof View ? (View) p : null;
            }
            if (row == null) return;
            StringBuilder b = new StringBuilder("lock notifications: layout, dp: row ")
                    .append(Math.round(row.getWidth() / d)).append('x')
                    .append(Math.round(row.getHeight() / d));
            describe(row, b, d, 0);
            XLog.debug(b.toString());
        } catch (Throwable t) {
            XLog.e("lock notifications layout", t);
        }
    }

    private static void describe(View v, StringBuilder b, float d, int depth) {
        if (depth > 7 || b.length() > 3000) return;
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getHeight() == 0) continue;
            String id = "";
            try {
                if (c.getId() != View.NO_ID) id = c.getResources().getResourceEntryName(c.getId());
            } catch (Throwable ignored) {
                // No name.
            }
            b.append(" | ").append(depth).append(' ').append(c.getClass().getSimpleName());
            if (!id.isEmpty()) b.append('#').append(id);
            b.append(' ').append(Math.round(c.getWidth() / d)).append('x')
                    .append(Math.round(c.getHeight() / d)).append(" at ")
                    .append(Math.round(c.getLeft() / d)).append(',')
                    .append(Math.round(c.getTop() / d));
            if (c.getPaddingTop() + c.getPaddingBottom() + c.getPaddingLeft() > 0) {
                b.append(" pad ").append(Math.round(c.getPaddingLeft() / d)).append('/')
                        .append(Math.round(c.getPaddingTop() / d)).append('/')
                        .append(Math.round(c.getPaddingBottom() / d));
            }
            if (c instanceof android.widget.TextView) {
                b.append(" text ").append(Math.round(
                        ((android.widget.TextView) c).getTextSize() / d)).append("dp");
            }
            describe(c, b, d, depth + 1);
        }
    }

    /** The notification's content view, per background (found once). */
    private static final Map<View, java.lang.ref.WeakReference<View>> CONTENT =
            new WeakHashMap<>();

    /**
     * Where the notification's shown content ends, in the background's coordinates (the
     * background and the content sit in the same row at 0,0), or -1.
     */
    private static int contentBottom(View background) {
        java.lang.ref.WeakReference<View> ref = CONTENT.get(background);
        View content = ref != null ? ref.get() : null;
        View row = background.getParent() instanceof View ? (View) background.getParent() : null;
        if (row == null) return -1;
        if (content == null || !content.isAttachedToWindow() || !isUnder(content, row)) {
            content = findContent(row, 0);
            if (content == null) return -1;
            CONTENT.put(background, new java.lang.ref.WeakReference<>(content));
        }
        if (content.getVisibility() != View.VISIBLE || content.getHeight() <= 0) {
            // Another of the row's layouts is shown now (expanded): found again next time.
            CONTENT.remove(background);
            return -1;
        }
        int y = 0;
        for (View x = content; x != null && x != row; ) {
            y += x.getTop();
            ViewParent p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return y + content.getHeight();
    }

    private static boolean isUnder(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static int contentId = -1;

    /** The shown android:id/status_bar_latest_event_content under the row. */
    private static int headerId = -1;

    private static View findContent(View v, int depth) {
        if (depth > 4 || !(v instanceof android.view.ViewGroup)) return null;
        if (contentId == -1) {
            contentId = v.getResources().getIdentifier("status_bar_latest_event_content", "id",
                    "android");
            headerId = v.getResources().getIdentifier("notification_header", "id", "android");
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getHeight() == 0) continue;
            if (contentId != 0 && c.getId() == contentId) return c;
            // A hidden notification's public version (Secure Folder's on the lock screen):
            // One UI's header alone, with no content around it.
            if (headerId != 0 && c.getId() == headerId
                    && g.getClass().getName().endsWith("NotificationContentView")) {
                return c;
            }
            View found = findContent(c, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void release(View v) {
        Drawable b = BLURS.remove(v);
        if (b != null) {
            b.setVisible(false, false);
            b.setCallback(null);
        }
    }

    /** The view's alpha with its parents', 0 when any of them is not shown. */
    private static float alphaOf(View v) {
        float a = 1f;
        for (View x = v; x != null && a > 0f; ) {
            if (x.getVisibility() != View.VISIBLE) return 0f;
            a *= x.getAlpha();
            ViewParent p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return Math.max(0f, Math.min(1f, a));
    }

    /**
     * The compositor's blur does not see the views' alpha: before each frame each glass card
     * takes its notification's (the cards fade in and out with the lock screen), and goes when
     * the phone is unlocked.
     */
    private static void watch(View v) {
        ViewTreeObserver vto = v.getViewTreeObserver();
        if (OBSERVERS.containsKey(vto)) return;
        OBSERVERS.put(vto, Boolean.TRUE);
        vto.addOnPreDrawListener(() -> {
            try {
                sync();
            } catch (Throwable t) {
                XLog.e("lock notifications sync", t);
            }
            return true;
        });
    }

    private static void watchCorners(View v) {
        watch(v);
    }

    private static void sync() {
        if (!CORNERS.isEmpty()) {
            for (java.util.Map.Entry<View, LockCard> e : new ArrayList<>(CORNERS.entrySet())) {
                View v = e.getKey();
                if (v == null || !v.isAttachedToWindow()) continue;
                if (!on() || !locked(v.getContext())) {
                    e.getValue().release();
                    CORNERS.remove(v);
                    continue;
                }
                e.getValue().follow(v);
            }
        }
        if (!CARDS.isEmpty()) {
            for (java.util.Map.Entry<View, LockCard> e : new ArrayList<>(CARDS.entrySet())) {
                View v = e.getKey();
                if (v == null || !v.isAttachedToWindow()) continue;
                if (!on() || !locked(v.getContext())) {
                    e.getValue().release();
                    CARDS.remove(v);
                    v.invalidate();
                    continue;
                }
                e.getValue().follow(v);
            }
        }
        if (BLURS.isEmpty()) return;
        SYNC_LIST.clear();
        SYNC_LIST.addAll(BLURS.keySet());
        for (View v : SYNC_LIST) {
            if (v == null) continue;
            Drawable b = BLURS.get(v);
            if (b == null) continue;
            if (!on() || !v.isAttachedToWindow() || !locked(v.getContext())) {
                release(v);
                v.invalidate();
                continue;
            }
            int a = Math.round(255f * alphaOf(v));
            if (a != b.getAlpha()) {
                boolean back = b.getAlpha() == 0 && a > 0;
                b.setAlpha(a);
                if (back) v.invalidate();
            }
        }
        SYNC_LIST.clear();
    }

    private static boolean resolve() {
        if (createBlur != null) return true;
        if (reflectionFailed) return false;
        try {
            getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
            Class<?> vri = Class.forName("android.view.ViewRootImpl");
            Class<?> bbd = Class.forName(
                    "com.android.internal.graphics.drawable.BackgroundBlurDrawable");
            setBlurRadius = bbd.getDeclaredMethod("setBlurRadius", int.class);
            setCornerRadius = bbd.getDeclaredMethod("setCornerRadius", float.class);
            setBlurColor = bbd.getDeclaredMethod("setColor", int.class);
            setPorterDuffMode = bbd.getDeclaredMethod("setPorterDuffMode", PorterDuff.Mode.class);
            Method create = vri.getDeclaredMethod("createBackgroundBlurDrawable");
            create.setAccessible(true);
            createBlur = create;
            return true;
        } catch (Throwable t) {
            reflectionFailed = true;
            XLog.e("lock notifications: blur API unavailable", t);
            return false;
        }
    }
}
