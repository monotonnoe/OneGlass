package com.monotonnoe.oneglass.engine;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * The lock screen's notifications at the bottom of the screen, as iOS has them: the stack is
 * moved down (by its translation, so the cards are touched where they show) until its last
 * card, or the shelf under it, ends a little above the shortcuts. A stack too tall for that
 * stays where One UI puts it. Before every frame, as the stack changes, and again for a moment
 * after a notification comes or goes; off when unlocked or when the setting is off. With
 * more than one card they are piled as iOS's stack, where One UI lays them out
 * ({@link #pileStates}), until a tap or a swipe up opens the pile; pulling the shade down
 * spreads them with the finger. One UI's own slide of the stack stays on top of the offset.
 * System UI.
 */
final class LockBottom {
    private static final String STACK =
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private static final String EXPANDABLE =
            "com.android.systemui.statusbar.notification.row.ExpandableView";
    /** Where the stack ends: this far above the screen's bottom, clear of the shortcuts. */
    private static final float GAP_DP = 132f;
    /** Index in {@link LockGlass#settings}: the setting, on by default. */
    static final int BOTTOM = 28;

    private static Class<?> expandable;
    private static Method actualHeight;
    /** The offset this class added to the stack's translation, to take off again. */
    private static float applied;
    private static float lastLogged = -1000f;
    /** The translation this class last set: another one means One UI set its own. */
    private static float setTo;
    private static float lastTop;
    private static float lastBottom;
    private static long loggedAt;

    private LockBottom() {
    }

    static void install(ClassLoader cl) {
        Class<?> stack;
        try {
            stack = Class.forName(STACK, false, cl);
            expandable = Class.forName(EXPANDABLE, false, cl);
            // Not public on One UI 8.5: looked up among the declared methods (else the view's
            // height is used).
            for (Class<?> k = expandable; k != null && actualHeight == null;
                    k = k.getSuperclass()) {
                try {
                    actualHeight = k.getDeclaredMethod("getActualHeight");
                    actualHeight.setAccessible(true);
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            XLog.i("lock bottom: no stack (" + t + "), off");
            return;
        }
        java.util.Set<?> h = XposedBridge.hookAllConstructors(stack,
                SafeHook.after("lock bottom", p -> watch((View) p.thisObject)));
        // What sets where One UI puts the stack: its own top padding or stack top is what
        // this should move rather than the view (which One UI answers by moving the cards
        // up inside it). Logged for the next step.
        StringBuilder m = new StringBuilder();
        for (Class<?> k = stack; k != null && k != View.class; k = k.getSuperclass()) {
            for (Method x : k.getDeclaredMethods()) {
                String n = x.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.contains("toppadding") || n.contains("stacktop") || n.contains("stacky")
                        || n.contains("cutoff") || n.contains("translation")
                        || n.contains("keyguard")) {
                    m.append(' ').append(x.getName()).append('/')
                            .append(x.getParameterCount());
                }
            }
        }
        XLog.i("lock bottom: stack methods:" + m);
        XLog.i("lock bottom: stack hooks " + (h == null ? 0 : h.size()));
        // One UI 8.5 computes the cards' places inside the stack's own pre-draw listener
        // (StackScrollAlgorithm.resetViewStates inlined there by R8), then applies them with
        // applyCurrentState() or startAnimationToState$1(), the one reached first after a
        // fresh computation piles them.
        try {
            Class<?> updater = Class.forName(STACK + "$1", false, cl);
            int n = count(XposedBridge.hookAllMethods(updater, "onPreDraw",
                    SafeHook.before("lock pile", p -> {
                        fresh = true;
                        statesApplied = false;
                    })));
            XposedBridge.hookAllMethods(updater, "onPreDraw",
                    SafeHook.after("lock pile", p -> {
                        fresh = false;
                        pileMoving = false;
                        // The places applied: the pile opening slides into them.
                        animateOpening();
                        // The stack placed for the places applied in this same frame (its own
                        // pre-draw runs before One UI's: 6.67 placed it a frame late, and the
                        // cards jumped as One UI moved them at once).
                        if (statesApplied) {
                            statesApplied = false;
                            View st = stackOfUpdater(p.thisObject);
                            if (st != null) update(st, "applied");
                        }
                    }));
            for (String name : new String[]{"applyCurrentState", "startAnimationToState$1",
                    "startAnimationToState"}) {
                boolean animated = name.startsWith("startAnimation");
                n += count(XposedBridge.hookAllMethods(stack, name,
                        SafeHook.before("lock pile", p -> {
                            statesApplied = true;
                            if (animated) inStackAnimation = true;
                            // For the trace: which of One UI's ways put the cards in place.
                            appliedBy = fresh ? name : name + " (again)";
                            if (!fresh) return;
                            fresh = false;
                            pileStates((View) p.thisObject);
                        })));
                XposedBridge.hookAllMethods(stack, name, SafeHook.after("lock pile", p -> {
                    if (animated) inStackAnimation = false;
                    // An opening pile's cards kept in their slide: One UI's pass just put them
                    // in the list's places at once (6.87 drew them there, 540 px up).
                    if (sliding()) applySlide();
                    if (tracing()) traceApplied((View) p.thisObject, appliedBy);
                }));
            }
            // Diagnostics only (Config.DEBUG): One UI taking a card out, and its animations
            // ending.
            if (Config.DEBUG) {
                XposedBridge.hookAllMethods(stack, "generateRemoveAnimation",
                        SafeHook.after("lock trace", p -> {
                            View st = (View) p.thisObject;
                            if (!LockNotifications.on()
                                    || !LockNotifications.locked(st.getContext())) {
                                return;
                            }
                            Object row = p.args.length > 0 ? p.args[0] : null;
                            traceStart("One UI takes #"
                                    + (row instanceof View ? id((View) row) : "?")
                                    + " out, animated " + p.getResult());
                        }));
                XposedBridge.hookAllMethods(stack, "onChildAnimationFinished",
                        SafeHook.after("lock trace", p -> trace("One UI's animations ended")));
            }
            XLog.i("lock bottom: pile hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no stack updater (" + t + "), no pile");
        }
        // One UI animates a new card's or a swiped one's neighbours in place and depth only
        // (its AnimationFilter): the pile's sizes and fading snapped (6.67, a card shrank or
        // grew at once). While the pile changes, they are animated along with them.
        try {
            Class<?> filter = Class.forName(
                    "com.android.systemui.statusbar.notification.stack.AnimationFilter", false,
                    cl);
            Field animateY = fieldOf(filter, "animateY");
            Field animateAlpha = fieldOf(filter, "animateAlpha");
            Field properties = fieldOf(filter, "mAnimatedProperties");
            Field hasDelays = fieldOf(filter, "hasDelays");
            Method add = properties != null
                    ? properties.getType().getMethod("add", Object.class) : null;
            if (animateY == null || animateAlpha == null || add == null) {
                throw new NoSuchFieldException("animateY/animateAlpha/mAnimatedProperties");
            }
            int n = count(XposedBridge.hookAllMethods(filter, "combineFilter",
                    SafeHook.after("pile filter", p -> {
                        Object f = p.thisObject;
                        boolean y = animateY.getBoolean(f);
                        boolean alphaWas = animateAlpha.getBoolean(f);
                        if (inStackAnimation && pileMoving && y) {
                            animateAlpha.setBoolean(f, true);
                            Object set = properties.get(f);
                            if (set != null) {
                                add.invoke(set, View.SCALE_X);
                                add.invoke(set, View.SCALE_Y);
                            }
                        }
                        // The cards on the lock screen held by their bottom move as one: One UI
                        // starts each 32 ms after the one above it, and with the stack placed
                        // by the last, the others rose 47 px and sank back as a card was swiped
                        // away from the list (6.87).
                        boolean delays = hasDelays != null && hasDelays.getBoolean(f);
                        // Only as a card goes: a new one keeps One UI's timing (the newest
                        // card's place in the pile waited empty for its 160 ms otherwise).
                        if (anchored && delays && android.os.SystemClock.uptimeMillis()
                                - removedAt < 700) {
                            hasDelays.setBoolean(f, false);
                        }
                        if (tracing()) {
                            trace("filter: in stack animation " + inStackAnimation
                                    + ", pile moving " + pileMoving + ", animateY " + y
                                    + ", animateAlpha " + alphaWas + " -> "
                                    + animateAlpha.getBoolean(f) + ", delays " + delays + " -> "
                                    + (hasDelays != null && hasDelays.getBoolean(f)));
                        }
                    })));
            XLog.i("lock bottom: pile filter hooks " + n + (hasDelays != null ? ""
                    : " (no hasDelays)"));
        } catch (Throwable t) {
            XLog.i("lock bottom: no animation filter (" + t + ")");
        }
        // A tap on the pile opens it, as iOS's stack, and opens nothing else: One UI would
        // also have opened the notification tapped (the bouncer, then its app). 6.67 opened
        // the pile on any finger resting on a card (a swipe aside, a pull down, a long press).
        try {
            Class<?> row = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    false, cl);
            int n = count(XposedBridge.hookAllMethods(row, "performClick",
                    SafeHook.before("pile tap", p -> {
                        View r = (View) p.thisObject;
                        View st = r.getParent() instanceof View ? (View) r.getParent() : null;
                        if (st == null || opened || BEHIND.isEmpty()
                                || !st.getClass().getName()
                                        .endsWith("NotificationStackScrollLayout")
                                || !pileWanted(st)) {
                            return;
                        }
                        open(st, "a tap");
                        p.setResult(true);
                    })));
            XLog.i("lock bottom: tap hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no row clicks (" + t + ")");
        }
        // On the lock screen the cards are glass with no shadow: One UI's fake shadow (in its
        // card's shape, cast by a card over the one under it) showed through the newest's
        // glass in the pile, and under it when it was swiped.
        try {
            Class<?> activatable = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ActivatableNotificationView",
                    false, cl);
            int n = count(XposedBridge.hookAllMethods(activatable, "setFakeShadowIntensity",
                    SafeHook.before("lock shadow", p -> {
                        if (p.args.length < 1 || !(p.args[0] instanceof Float)
                                || (Float) p.args[0] <= 0f) {
                            return;
                        }
                        View row = (View) p.thisObject;
                        if (LockNotifications.on() && LockNotifications.locked(row.getContext())
                                && LockNotifications.onKeyguard(row)) {
                            p.args[0] = 0f;
                        }
                    })));
            XLog.i("lock bottom: shadow hooks " + n);
            // A card under the newest stays smaller about its card's bottom: One UI puts the
            // pivot at the middle of its height each time it sets that height (as the screen
            // comes on, a notification comes), and the card under it rose 9 px, the pile with
            // it, then sank back as the next pass put the pivot back (6.86).
            n = count(XposedBridge.hookAllMethods(activatable, "setActualHeight",
                    SafeHook.after("pile pivot", p -> {
                        View row = (View) p.thisObject;
                        Float pv = PIVOT.get(row);
                        if (pv != null && row.getPivotY() != pv) row.setPivotY(pv);
                    })));
            XLog.i("lock bottom: pivot hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no fake shadow (" + t + ")");
        }
        try {
            try {
                deviceState = Class.forName("com.android.systemui.util.DeviceState", false, cl);
            } catch (Throwable ignored) {
            }
            Class<?> authClass = Class.forName("com.android.systemui.biometrics.AuthController",
                    false, cl);
            int n = count(XposedBridge.hookAllConstructors(authClass,
                    SafeHook.after("fingerprint sensor",
                            p -> auth = new java.lang.ref.WeakReference<>(p.thisObject))));
            XLog.i("lock bottom: sensor hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no AuthController (" + t + ")");
        }
        // Whether the sensor listens now (One UI's own DragDownHelper asks it the same way).
        try {
            Class<?> updateMonitor = Class.forName("com.android.keyguard.KeyguardUpdateMonitor",
                    false, cl);
            int n = count(XposedBridge.hookAllConstructors(updateMonitor,
                    SafeHook.after("fingerprint state",
                    p -> monitor = new java.lang.ref.WeakReference<>(p.thisObject))));
            XLog.i("lock bottom: fingerprint state hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no KeyguardUpdateMonitor (" + t + ")");
        }
        try {
            Class<?> window = Class.forName("com.android.systemui.shade.NotificationShadeWindowView",
                    false, cl);
            int n = count(XposedBridge.hookAllMethods(window, "dispatchTouchEvent",
                    SafeHook.before("pile swipe", LockBottom::onWindowTouch)));
            XLog.i("lock bottom: swipe hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no shade window (" + t + "), no swipe on the pile");
        }
        // The fingerprint sensor's hints ("cover the sensor fully") not shown on the lock
        // screen with the cards at the bottom, where they would be over them.
        try {
            Class<?> hint = Class.forName(
                    "com.android.systemui.statusbar.phone.KeyguardIndicationTextView", false, cl);
            int n = count(XposedBridge.hookAllConstructors(hint,
                    SafeHook.after("fingerprint hint", p -> watchHint((View) p.thisObject))));
            XLog.i("lock bottom: hint hooks " + n);
        } catch (Throwable t) {
            XLog.i("lock bottom: no indication texts (" + t + ")");
        }
    }

    /** The pile on the screen (its newest's top to its end), or -1 without a pile. */
    private static float pileTop = -1f;
    private static float pileBottom = -1f;
    private static boolean swipeTracking;
    private static boolean swipeTaken;
    private static float swipeX;
    private static float swipeY;

    /**
     * A swipe up on the pile opens it into the list, as on iOS. Seen first, in the shade
     * window's dispatchTouchEvent, before One UI takes a swipe up on the lock screen for the
     * bouncer: once it is the pile's, One UI gets a cancel and the rest of it is kept from it.
     * A swipe sideways stays One UI's (it dismisses the newest), as does a pull down (the
     * shade) and a second finger.
     */
    private static void onWindowTouch(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
        if (p.args.length < 1 || !(p.args[0] instanceof android.view.MotionEvent)) return;
        android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
        View root = (View) p.thisObject;
        int action = e.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            if (heldDown != null) {
                heldDown.recycle();
                heldDown = null;
            }
            fingerHeld = onSensor(e, root);
            if (fingerHeld) heldDown = android.view.MotionEvent.obtain(e);
        }
        if (fingerHeld) {
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN
                    || action == android.view.MotionEvent.ACTION_POINTER_UP) {
                // Another finger: the touch is the sensor's to its end, as in 6.67 (given
                // back, One UI would have the held finger's DOWN and never its end).
                if (heldDown != null) {
                    heldDown.recycle();
                    heldDown = null;
                }
            }
            android.graphics.Rect r = sensor(root);
            if (action == android.view.MotionEvent.ACTION_MOVE && heldDown != null && r != null
                    && e.getPointerCount() == 1
                    && e.getPointerId(0) == heldDown.getPointerId(0)
                    && !onRect(r, e.getRawX(), e.getRawY(), root)) {
                // Off the sensor: a swipe after all (aside, down, up), One UI's from where it
                // began, as One UI's own DragDownHelper tells a finger on the sensor from a
                // swipe. 6.67 kept the whole gesture from the cards.
                fingerHeld = false;
                android.view.MotionEvent down = heldDown;
                heldDown = null;
                try {
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject,
                            new Object[]{down});
                } catch (Throwable t) {
                    XLog.e("lock bottom: sensor touch", t);
                }
                track(down, root);
                down.recycle();
            } else {
                // A finger on the fingerprint sensor over the cards: for the sensor only, the
                // cards do not take it (a touch, a pile opened, a notification opened).
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    fingerHeld = false;
                    if (heldDown != null) {
                        heldDown.recycle();
                        heldDown = null;
                    }
                }
                swipeTracking = false;
                p.setResult(true);
                return;
            }
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            track(e, root);
            return;
        }
        if (swipeTaken) {
            // The rest of the swipe is the pile's.
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                swipeTaken = false;
            }
            p.setResult(true);
            return;
        }
        if (!swipeTracking) return;
        if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            // A second finger: no swipe on the pile (6.67 read the other finger's place).
            swipeTracking = false;
            return;
        }
        if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            swipeTracking = false;
            return;
        }
        if (action != android.view.MotionEvent.ACTION_MOVE) return;
        float dx = Math.abs(e.getRawX() - swipeX);
        float up = swipeY - e.getRawY();
        int slop = android.view.ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        if (dx > slop && dx > up) {
            // Sideways: One UI's, the newest dismissed.
            swipeTracking = false;
            return;
        }
        if (-up > slop && -up >= dx) {
            // Down: One UI's shade, pulled from the cards.
            swipeTracking = false;
            return;
        }
        if (up < 2f * slop || up < dx * 1.5f) return;
        swipeTracking = false;
        swipeTaken = true;
        View stack = stackRef != null ? stackRef.get() : null;
        if (stack != null) open(stack, "a swipe up");
        // One UI let go of what it started with this touch.
        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
        p.args[0] = cancel;
    }

    /** A touch going down: followed if it is on the pile, to see whether it swipes up. */
    private static void track(android.view.MotionEvent e, View root) {
        swipeTaken = false;
        swipeTracking = pileTop >= 0f && !BEHIND.isEmpty() && !opened
                && e.getRawY() >= pileTop - 16f * root.getResources()
                .getDisplayMetrics().density && e.getRawY() <= pileBottom;
        swipeX = e.getRawX();
        swipeY = e.getRawY();
    }

    /**
     * The pile opened into the list (a tap, a swipe up): laid out again by One UI once, which
     * the opening then slides from the pile into (6.66 asked twice for a swipe, and the
     * second pass put the cards at their places over the sliding).
     */
    private static void open(View stack, String by) {
        if (opened) return;
        opened = true;
        XLog.debug("lock bottom: the pile opened by " + by);
        relayout(stack);
        pileWas = Boolean.FALSE;
    }

    /** Where the cards are on the screen (the first's top to the last's end), or -1. */
    private static float cardsTop = -1f;
    private static float cardsBottom = -1f;
    private static boolean fingerHeld;
    /** The DOWN of a finger on the sensor, given to One UI if it slides off. */
    private static android.view.MotionEvent heldDown;
    /** System UI's KeyguardUpdateMonitor: whether the fingerprint sensor listens now. */
    private static java.lang.ref.WeakReference<Object> monitor;
    private static Method fingerprintRunning;
    private static boolean fingerprintLooked;
    /** System UI's AuthController: mUdfpsBounds is the sensor under the screen, on it. */
    private static java.lang.ref.WeakReference<Object> auth;
    private static Field udfpsBounds;
    private static boolean sensorLogged;

    private static Class<?> deviceState;

    private static android.graphics.Rect sensor(View root) {
        android.graphics.Rect r = udfps();
        if (r != null) return r;
        // As One UI's AuthController.updateUdfpsLocation places it (portrait): the image's
        // size wide in the middle, its top the sensor's height above the screen's bottom.
        try {
            if (deviceState == null) return null;
            Method h = deviceState.getDeclaredMethod("getInDisplayFingerprintHeight");
            Method size = deviceState.getDeclaredMethod("getInDisplayFingerprintImageSize");
            h.setAccessible(true);
            size.setAccessible(true);
            int height = (Integer) h.invoke(null);
            int image = (Integer) size.invoke(null);
            View top = root.getRootView();
            if (height <= 0 || image <= 0 || top.getHeight() <= 0) return null;
            int cx = top.getWidth() / 2;
            int y = top.getHeight() - height;
            r = new android.graphics.Rect(cx - image / 2, y, cx + image / 2, y + image);
            if (!sensorLogged) {
                sensorLogged = true;
                XLog.i("lock bottom: the fingerprint sensor at " + r
                        + " (One UI's in-display sizes), touches there not the cards'");
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static android.graphics.Rect udfps() {
        Object a = auth != null ? auth.get() : null;
        if (a == null) return null;
        try {
            if (udfpsBounds == null) udfpsBounds = fieldOf(a.getClass(), "mUdfpsBounds");
            Object r = udfpsBounds != null ? udfpsBounds.get(a) : null;
            if (r instanceof android.graphics.Rect && !((android.graphics.Rect) r).isEmpty()) {
                if (!sensorLogged) {
                    sensorLogged = true;
                    XLog.i("lock bottom: the fingerprint sensor at " + r
                            + ", touches there not the cards'");
                }
                return (android.graphics.Rect) r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** A touch going down on the fingerprint sensor where the cards are, on the lock screen. */
    private static boolean onSensor(android.view.MotionEvent e, View root) {
        if (cardsTop < 0f) return false;
        View stack = stackRef != null ? stackRef.get() : null;
        if (stack == null || !wanted(stack) || !onKeyguard(stack) || toShade(stack) > 0f
                || !fingerprintListening()) {
            return false;
        }
        android.graphics.Rect r = sensor(root);
        if (r == null) return false;
        float y = e.getRawY();
        return onRect(r, e.getRawX(), y, root) && y >= cardsTop && y <= cardsBottom;
    }

    /** On the sensor's rect, or 8 dp around it. */
    private static boolean onRect(android.graphics.Rect r, float x, float y, View root) {
        float pad = 8f * root.getResources().getDisplayMetrics().density;
        return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad
                && y <= r.bottom + pad;
    }

    /**
     * The fingerprint sensor listening now (KeyguardUpdateMonitor's
     * isFingerprintDetectionRunning): locked out or not in use, a touch there is the cards'.
     * Not known: as if it listened.
     */
    private static boolean fingerprintListening() {
        Object m = monitor != null ? monitor.get() : null;
        if (m == null) return true;
        if (!fingerprintLooked) {
            fingerprintLooked = true;
            fingerprintRunning = declared(m.getClass(), "isFingerprintDetectionRunning");
            XLog.i("lock bottom: fingerprint listening " + (fingerprintRunning != null
                    ? "from isFingerprintDetectionRunning()" : "unknown"));
        }
        if (fingerprintRunning == null) return true;
        try {
            return Boolean.TRUE.equals(fingerprintRunning.invoke(m));
        } catch (Throwable t) {
            return true;
        }
    }

    private static void watchHint(View v) {
        boolean[] hidden = new boolean[1];
        ViewTreeObserver.OnPreDrawListener pre = () -> {
            String id = idName(v);
            if (!id.contains("fingerprint") && !id.contains("biometric")) return true;
            int[] s = LockGlass.settings;
            boolean hide = s.length > BOTTOM && s[BOTTOM] != 0 && LockNotifications.on()
                    && LockNotifications.locked(v.getContext());
            if (hide) {
                if (v.getAlpha() != 0f) v.setAlpha(0f);
                hidden[0] = true;
            } else if (hidden[0]) {
                hidden[0] = false;
                v.setAlpha(1f);
            }
            return true;
        };
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View x) {
                x.getViewTreeObserver().addOnPreDrawListener(pre);
            }

            @Override
            public void onViewDetachedFromWindow(View x) {
                x.getViewTreeObserver().removeOnPreDrawListener(pre);
            }
        });
    }

    /** The cards of an opening pile: where each was drawn in the pile, to slide from. */
    private static final java.util.Map<View, float[]> OPENING = new java.util.HashMap<>();
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);

    /**
     * The pile opened (a tap, a swipe up), its places as a list just applied by One UI (at
     * once): each card put back where it was in the pile and slid from there into its place,
     * its scale and fading to the list's, as iOS's stack opens, from the top card down
     * (6.67 in no order); the texts of those that were under the newest fade in (pileStates).
     */
    private static void animateOpening() {
        if (OPENING.isEmpty()) return;
        java.util.List<View> order = new java.util.ArrayList<>(OPENING.keySet());
        order.sort((x, y) -> Float.compare(x.getTranslationY(), y.getTranslationY()));
        stopSlide();
        int i = 0;
        for (View c : order) {
            float[] from = OPENING.get(c);
            if (from == null) continue;
            float ty = c.getTranslationY();
            float sx = c.getScaleX();
            float sy = c.getScaleY();
            float a = c.getAlpha();
            // In One UI's shelf (hidden): not drawn, One UI's own.
            if (c.getVisibility() != View.VISIBLE) continue;
            // One UI animates them itself (its animators started from the pile's places).
            boolean moves = Math.abs(ty - from[0]) >= 1f || Math.abs(a - from[3]) >= 0.01f;
            // The list has every card at depth 0: the cards under the newest, drawn after it,
            // were drawn over it until they slid out from under it. The pile's kept as they go.
            boolean lifts = Math.abs(c.getTranslationZ() - from[4]) >= 0.001f;
            if (!moves && !lifts) continue;
            if (moves) {
                c.setPivotY(from[5]);
                PIVOT.put(c, from[5]);
            }
            SLIDE.put(c, new float[]{from[0], from[1], from[2], from[3], ty, sx, sy, a,
                    moves ? i++ * SLIDE_STAGGER : -1f, from[4],
                    from[0], from[1], from[2], from[3]});
        }
        // One UI's shelf (the overflow icons) is put at the list's end at once, 564 px lower:
        // off the screen while the stack is still down, it came up from the screen's bottom
        // with it. Faded in there with the last card instead.
        View owner = stackRef != null ? stackRef.get() : null;
        if (owner instanceof ViewGroup && i > 0) {
            ViewGroup g = (ViewGroup) owner;
            for (int n = 0; n < g.getChildCount(); n++) {
                View sh = g.getChildAt(n);
                if (!sh.getClass().getName().endsWith("NotificationShelf")
                        || sh.getVisibility() != View.VISIBLE) {
                    continue;
                }
                float y = sh.getTranslationY();
                SLIDE.put(sh, new float[]{y, 1f, 1f, 0f, y, 1f, 1f, sh.getAlpha(),
                        (i - 1) * SLIDE_STAGGER, sh.getTranslationZ(), y, 1f, 1f, 0f});
            }
        }
        OPENING.clear();
        if (SLIDE.isEmpty()) return;
        android.animation.ValueAnimator s = android.animation.ValueAnimator.ofFloat(0f, 1f);
        s.setDuration(SLIDE_MS + (long) (Math.max(0, i - 1) * SLIDE_STAGGER));
        s.setInterpolator(new android.view.animation.LinearInterpolator());
        s.addUpdateListener(x -> {
            try {
                applySlide();
            } catch (Throwable t) {
                XLog.e("lock bottom: opening", t);
            }
        });
        s.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (slide != x) return;
                // Into their places in the list as One UI has them now.
                slideEnd = true;
                try {
                    applySlide();
                } catch (Throwable t) {
                    XLog.e("lock bottom: opening", t);
                }
                SLIDE.clear();
                slide = null;
            }
        });
        slide = s;
        slideEnd = false;
        // This frame too: from the pile's places, not the list's One UI just applied.
        applySlide();
        s.start();
    }

    /**
     * The cards of an opening pile as they slide: from their places in the pile (translation,
     * scales, alpha), their places in the list when it opened (the same), and their delay.
     * 6.87 slid them with view animators: One UI applied the list's places again in the next
     * frames (twice as the pile opens), the cards were drawn there, the stack placed for
     * them, 540 px up, then back down as each card's animator started: the whole list flashed
     * open at the top for 4 frames at each opening.
     */
    private static final java.util.Map<View, float[]> SLIDE = new java.util.WeakHashMap<>();
    private static final long SLIDE_MS = 420;
    private static final float SLIDE_STAGGER = 25f;
    private static android.animation.ValueAnimator slide;
    private static boolean slideEnd;

    static boolean sliding() {
        return slide != null && !SLIDE.isEmpty();
    }

    private static void stopSlide() {
        android.animation.ValueAnimator s = slide;
        slide = null;
        PIVOT.keySet().removeAll(SLIDE.keySet());
        SLIDE.clear();
        if (s != null) s.cancel();
    }

    /**
     * Each sliding card where it is now in its slide (also right after One UI applied the
     * list's places again in a pass): to its place in the list as One UI has it now, as it
     * may change while it slides (a card swiped away from the list as it opens).
     */
    private static void applySlide() {
        android.animation.ValueAnimator s = slide;
        if (s == null || SLIDE.isEmpty()) return;
        View stack = stackRef != null ? stackRef.get() : null;
        if (!anchored || !(stack instanceof ViewGroup)) {
            // Off the lock screen as it slid (unlocked, the shade, off): One UI's places.
            stopSlide();
            if (stack != null) requestUpdate(stack);
            return;
        }
        ViewGroup g = (ViewGroup) stack;
        long t = slideEnd ? Long.MAX_VALUE / 4 : s.getCurrentPlayTime();
        // A copy: entries leave as they end, and One UI's listeners run inside (cancel).
        for (View c : new java.util.ArrayList<>(SLIDE.keySet())) {
            float[] f = SLIDE.get(c);
            if (c == null || f == null) continue;
            if (g.indexOfChild(c) < 0) {
                // Taken out of the list as it slid (swiped away, cancelled): One UI's.
                PIVOT.remove(c);
                SLIDE.remove(c);
                continue;
            }
            Object st = State.of(c);
            boolean placed = st != null && !State.flag(st, "hidden") && !State.flag(st, "gone");
            boolean depthOnly = f[8] < 0f;
            float toY = placed ? State.num(st, "yTranslation") : f[4];
            if (!depthOnly && Math.abs(toY - f[4]) >= 1f && !slideEnd) {
                // Its place in the list moved as it slid (a card came or went above it): a
                // whole slide again, from where it was last drawn to the new place. 6.88 moved
                // it by the change times its progress in one frame.
                f[0] = f[10];
                f[1] = f[11];
                f[2] = f[12];
                f[3] = f[13];
                f[4] = toY;
                f[8] = t;
                if (s.getDuration() < t + SLIDE_MS) s.setDuration(t + SLIDE_MS);
            }
            float p = Math.max(0f, Math.min(1f, (t - Math.max(0f, f[8])) / (float) SLIDE_MS));
            // One UI's animators of it stopped for all of its slide, its delay too: started by
            // a pass after the slide began, they ran after it in the frame and were drawn.
            if (p < 1f) stopOneUiAnimators(c, !depthOnly);
            c.setTranslationZ(p < 1f ? f[9]
                    : placed ? State.num(st, "zTranslation") : c.getTranslationZ());
            if (!depthOnly) {
                float k = EASE.getInterpolation(p);
                f[10] = f[0] + (toY - f[0]) * k;
                f[11] = f[1] + (f[5] - f[1]) * k;
                f[12] = f[2] + (f[6] - f[2]) * k;
                // Sent into One UI's shelf as it slid (a notification came above): faded out.
                f[13] = f[3] + ((placed ? f[7] : 0f) - f[3]) * k;
                c.setTranslationY(f[10]);
                c.setScaleX(f[11]);
                c.setScaleY(f[12]);
                c.setAlpha(f[13]);
            }
            if (p >= 1f) {
                // In its place: One UI's again, its own animations of it too (a card going
                // above it as the others still slide: 6.88 put it at the end for a frame).
                PIVOT.remove(c);
                SLIDE.remove(c);
            }
        }
    }

    private static int[] oneUiAnimatorIds;

    /** One UI's own animators on a sliding card (a pass animated as it slides) stopped. */
    private static void stopOneUiAnimators(View c, boolean all) {
        if (oneUiAnimatorIds == null) {
            // The depth's first: a card only kept above the others stops that one alone.
            String[] names = {"translation_z_animator_tag", "translation_y_animator_tag",
                    "alpha_animator_tag", "scale_x_animator_tag", "scale_y_animator_tag"};
            oneUiAnimatorIds = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                oneUiAnimatorIds[i] = c.getResources().getIdentifier(names[i], "id",
                        "com.android.systemui");
            }
        }
        for (int n = 0; n < (all ? oneUiAnimatorIds.length : 1); n++) {
            int id = oneUiAnimatorIds[n];
            Object a = id != 0 ? c.getTag(id) : null;
            if (a instanceof android.animation.Animator) {
                ((android.animation.Animator) a).cancel();
            }
        }
    }

    private static int count(java.util.Set<?> hooks) {
        return hooks == null ? 0 : hooks.size();
    }

    private static void watch(View stack) {
        stackRef = new java.lang.ref.WeakReference<>(stack);
        ViewTreeObserver.OnPreDrawListener pre = () -> {
            try {
                update(stack, "pre");
            } catch (Throwable t) {
                XLog.e("lock bottom", t);
            }
            return true;
        };
        stack.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(pre);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnPreDrawListener(pre);
            }
        });
        if (stack.isAttachedToWindow()) stack.getViewTreeObserver().addOnPreDrawListener(pre);
    }

    private static Method stackOnKeyguard;
    private static Method topPadding;
    private static boolean stackLooked;

    private static Method declared(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** Off: the setting off, unlocked, or the stack not showing. */
    private static boolean wanted(View stack) {
        int[] s = LockGlass.settings;
        return s.length > BOTTOM && s[BOTTOM] != 0 && LockNotifications.on()
                && LockNotifications.locked(stack.getContext()) && stack.isShown();
    }

    /**
     * The stack on the lock screen itself, not in the shade pulled down over it: the stack's
     * own onKeyguard() (One UI 8.5 has it), else any card on the lock screen (6.51 asked the
     * first card only, whose state may not be set yet, and the stack went back up).
     */
    static boolean onKeyguard(View stack) {
        if (!stackLooked) {
            stackLooked = true;
            stackOnKeyguard = declared(stack.getClass(), "onKeyguard");
            topPadding = declared(stack.getClass(), "getTopPadding");
            XLog.i("lock bottom: on keyguard from " + (stackOnKeyguard != null
                    ? "the stack's onKeyguard()" : "the cards") + ", top padding "
                    + (topPadding != null ? "from getTopPadding()" : "unknown"));
        }
        if (stackOnKeyguard != null) {
            try {
                return Boolean.TRUE.equals(stackOnKeyguard.invoke(stack));
            } catch (Throwable ignored) {
            }
        }
        if (stack instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) stack;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getClass().getName().endsWith("ExpandableNotificationRow")
                        && LockNotifications.onKeyguard(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Field ambient;
    private static Field fractionToShade;
    private static Field qsFraction;
    private static boolean ambientLooked;

    /**
     * How far the lock screen is going into the shade, 0 to 1: One UI's
     * AmbientState.mFractionToShade while the cards are pulled down
     * (LockscreenShadeTransitionController.setDragDownAmount), or its quick settings'
     * mQsExpansionFraction pulled from the top. The status bar's state stays the lock
     * screen's until the shade is open: 6.63 kept the cards low and piled meanwhile.
     */
    private static float toShade(View stack) {
        if (!ambientLooked) {
            ambientLooked = true;
            try {
                ambient = fieldOf(stack.getClass(), "mAmbientState");
                Class<?> a = ambient.getType();
                fractionToShade = fieldOf(a, "mFractionToShade");
                qsFraction = fieldOf(a, "mQsExpansionFraction");
            } catch (Throwable t) {
                ambient = null;
            }
            XLog.i("lock bottom: the shade's fraction " + (ambient != null && fractionToShade
                    != null ? "from AmbientState" : "unknown"));
        }
        if (ambient == null) return 0f;
        try {
            Object a = ambient.get(stack);
            if (a == null) return 0f;
            float t = fractionToShade != null ? fractionToShade.getFloat(a) : 0f;
            float q = qsFraction != null ? qsFraction.getFloat(a) : 0f;
            float f = Math.max(t, q);
            return f > 0f ? Math.min(1f, f) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static Field fieldOf(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static float topPadding(View stack) {
        try {
            Object v = topPadding != null ? topPadding.invoke(stack) : null;
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    /** Index in {@link LockGlass#settings}: the cards piled, on by default. */
    static final int PILE = 29;
    /** How far each card under the newest shows below it, in dp, and how many show. */
    private static final float LAYER_DP = 9f;
    private static final int LAYERS = 2;
    /** How far a card under the newest is drawn up under it, in dp (its corners filled). */
    private static final float OVERLAP_DP = 12f;
    /** The pile opened (a tap, a swipe up, the expand arrow), until the screen goes off. */
    private static boolean opened;
    private static boolean screenWatched;
    /** The cards under the newest: from where in each (row's coordinates) it is drawn. */
    private static final java.util.Map<View, Float> BEHIND = new java.util.WeakHashMap<>();
    /** The newest card of the pile, while there is one. */
    private static java.lang.ref.WeakReference<View> pileFront;
    private static java.lang.ref.WeakReference<View> stackRef;
    private static Boolean pileWas;
    /** How far the cards were piled last frame: 1 a pile, 0 a list (the shade pulled). */
    private static float amountWas = -1f;
    /** Whether the stack was the lock screen's last frame (not the shade over it). */
    private static Boolean keyguardWas;
    private static int countWas = -1;
    private static long checksUntil;
    /** One UI applied the cards' places this frame, in its own pre-draw. */
    private static boolean statesApplied;
    /** Inside One UI's animation to the places (where its filter is combined). */
    private static boolean inStackAnimation;
    /** The pile changed the places in this pass: its sizes and fading animated too. */
    private static boolean pileMoving;
    /** The cards are on the lock screen, the stack placed by where they end (this frame). */
    private static boolean anchored;
    /** When a notification last went from the lock screen's cards. */
    private static long removedAt = -100000;
    /** Since when the cards have been on the lock screen, placed (-1: not now). */
    private static long liveSince = -1;
    /** The rows hidden or not shown in the last measure, and those One UI brings up. */
    private static final java.util.Map<View, Boolean> SHELVED = new java.util.WeakHashMap<>();
    private static final java.util.Map<View, Boolean> ENTERING = new java.util.WeakHashMap<>();
    private static Field updaterStack;
    private static boolean updaterLooked;

    /**
     * Each row's place in the last pile: 0 its newest, 1 to LAYERS shown under it, LAYERS + 1
     * behind those (unseen), -1 a row One UI keeps in its shelf (past its lock screen limit).
     */
    private static final java.util.Map<View, Integer> LAYER = new java.util.WeakHashMap<>();
    /** The cards under the newest: where each is made smaller about (its card's bottom). */
    private static final java.util.Map<View, Float> PIVOT = new java.util.WeakHashMap<>();
    /** The last pass made a whole pile (not one spread by the shade). */
    private static boolean fullWas;
    /** The notification rows in the last pass's pile, -1 after a pass without one. */
    private static int pileRowsWas = -1;
    /** For a moment after a card left the pile: one coming up into it is put in place first. */
    private static long promoteUntil;

    /**
     * Diagnostics: the cards traced for 1.5 s after a notification went (240 lines), only with
     * {@link Config#DEBUG} (the traces were most of the LSPosed log).
     */
    private static long traceUntil;
    private static int traceLeft;
    private static String traceLast = "";
    /** Which of One UI's methods put the cards in place last (for the trace). */
    private static String appliedBy = "-";

    static boolean tracing() {
        return Config.DEBUG && traceLeft > 0 && android.os.SystemClock.uptimeMillis() < traceUntil;
    }

    private static void traceStart(String why) {
        if (!Config.DEBUG) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now >= traceUntil) {
            traceLeft = 240;
            traceLast = "";
        }
        traceUntil = now + 1500;
        trace("start: " + why);
    }

    /** One line of the trace; a line equal to the last one is not repeated. */
    static void trace(String line) {
        if (!tracing() || line.equals(traceLast)) return;
        traceLast = line;
        traceLeft--;
        XLog.debug("lock trace " + android.os.SystemClock.uptimeMillis() + ": " + line
                + (traceLeft == 0 ? " (trace full)" : ""));
    }

    static String id(View v) {
        return v == null ? "-" : Integer.toHexString(System.identityHashCode(v) & 0xffff);
    }

    private static String vis(View c) {
        return c.getVisibility() == View.VISIBLE ? " V"
                : c.getVisibility() == View.INVISIBLE ? " I" : " G";
    }

    private static String round3(float f) {
        return String.valueOf(Math.round(f * 1000) / 1000f);
    }

    /** Diagnostics: One UI's shelf (its overflow icons) as it is now. */
    private static void traceShelf(ViewGroup g, StringBuilder b) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.getClass().getName().endsWith("NotificationShelf")) continue;
            Object st = State.of(c);
            b.append(" [shelf").append(vis(c)).append(" a ")
                    .append(Math.round(c.getAlpha() * 100)).append(" y ")
                    .append(Math.round(c.getY())).append(" ty ")
                    .append(Math.round(c.getTranslationY()));
            if (st != null) {
                b.append(" | state y ").append(Math.round(State.num(st, "yTranslation")))
                        .append(" a ").append(Math.round(State.num(st, "alpha") * 100))
                        .append(State.flag(st, "hidden") ? " hidden" : "");
            }
            b.append(']');
        }
    }

    /** Diagnostics: every row as the stack is measured. */
    private static void traceRows(ViewGroup g, String by, float minY, View front, float top,
                                  float bottom) {
        StringBuilder b = new StringBuilder("rows by ").append(by).append(", applied by ")
                .append(appliedBy).append(", front #").append(id(front))
                .append(front != null && front.getParent() != g ? " (not in the stack)" : "")
                .append(", cards ").append(Float.isNaN(top) ? "none"
                        : Math.round(top) + ".." + Math.round(bottom))
                .append(", offset ").append(Math.round(applied)).append(", translation ")
                .append(Math.round(g.getTranslationY())).append(", from y ")
                .append(Math.round(minY)).append(':');
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!expandable.isInstance(c) || !c.getClass().getName().endsWith("Row")) continue;
            Object st = State.of(c);
            float alpha = front != null && (c == front || BEHIND.containsKey(c))
                    ? front.getAlpha() : c.getAlpha();
            String out = c.getVisibility() == View.GONE ? "gone"
                    : c.getVisibility() != View.VISIBLE ? "invisible"
                    : c.getHeight() <= 0 || heightOf(c) <= 0 ? "no height"
                    : alpha < 0.3f ? "alpha"
                    : c.getY() < minY ? "above"
                    : st != null && State.flag(st, "hidden") ? "hidden" : null;
            Integer layer = LAYER.get(c);
            Float pivot = PIVOT.get(c);
            Integer rest = LockNotifications.CARD_REST.get(c);
            Integer card = LockNotifications.CARD_BOTTOM.get(c);
            b.append(" [#").append(id(c)).append(" L")
                    .append(layer == null ? "-" : String.valueOf(layer))
                    .append(c == front ? " front" : BEHIND.containsKey(c) ? " behind" : "")
                    .append(vis(c))
                    .append(" a ").append(Math.round(c.getAlpha() * 100)).append('/')
                    .append(Math.round(alpha * 100))
                    .append(" y ").append(Math.round(c.getY()))
                    .append(" ty ").append(Math.round(c.getTranslationY()))
                    .append(" pv ").append(Math.round(c.getPivotY())).append('/')
                    .append(pivot == null ? "-" : String.valueOf(Math.round(pivot)))
                    .append(" s ").append(round3(c.getScaleY()))
                    .append(" z ").append(Math.round(c.getTranslationZ() * 10) / 10f)
                    .append(" h ").append(heightOf(c)).append('/').append(c.getHeight())
                    .append(' ').append(LockNotifications.cardTrace(c))
                    .append(" rest ").append(rest == null ? "-" : String.valueOf(rest))
                    .append(" drawn ").append(card == null ? "-" : String.valueOf(card));
            if (st != null) {
                b.append(" | state y ").append(Math.round(State.num(st, "yTranslation")))
                        .append(" h ").append(Math.round(State.num(st, "height")))
                        .append(" a ").append(Math.round(State.num(st, "alpha") * 100))
                        .append(" s ").append(round3(State.num(st, "scaleY")))
                        .append(State.flag(st, "hidden") ? " hidden" : "");
            }
            b.append(out == null ? " -> end " + Math.round(bottomOf(c)) : " (out: " + out + ")")
                    .append(']');
        }
        traceShelf(g, b);
        trace(b.toString());
    }

    private static int[] animatorIds;
    private static final String[] ANIMATORS = {"translation_y_animator_tag",
            "alpha_animator_tag", "scale_x_animator_tag", "translation_z_animator_tag",
            "height_animator_tag"};
    private static final String[] ANIMATOR_NAMES = {"y", "alpha", "scale", "z", "height"};

    /** Diagnostics: One UI's animators on a card (start delay + duration), from its tags. */
    private static String animators(View c) {
        if (animatorIds == null) {
            animatorIds = new int[ANIMATORS.length];
            for (int i = 0; i < ANIMATORS.length; i++) {
                animatorIds[i] = c.getResources().getIdentifier(ANIMATORS[i], "id",
                        "com.android.systemui");
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < animatorIds.length; i++) {
            Object a = animatorIds[i] != 0 ? c.getTag(animatorIds[i]) : null;
            if (!(a instanceof android.animation.Animator)) continue;
            android.animation.Animator x = (android.animation.Animator) a;
            b.append(' ').append(ANIMATOR_NAMES[i]).append(' ').append(x.getStartDelay())
                    .append('+').append(x.getDuration())
                    .append(x.isStarted() ? "" : " (not started)");
        }
        return b.length() == 0 ? " none" : b.toString();
    }

    /** Diagnostics: the cards right after One UI applied or started animating their places. */
    private static void traceApplied(View stack, String by) {
        if (!(stack instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) stack;
        StringBuilder b = new StringBuilder("after ").append(by).append(':');
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!expandable.isInstance(c) || !c.getClass().getName().endsWith("Row")) continue;
            b.append(" [#").append(id(c)).append(vis(c))
                    .append(" a ").append(Math.round(c.getAlpha() * 100))
                    .append(" ty ").append(Math.round(c.getTranslationY()))
                    .append(" s ").append(round3(c.getScaleY()))
                    .append(" pv ").append(Math.round(c.getPivotY()))
                    .append(" z ").append(Math.round(c.getTranslationZ() * 10) / 10f)
                    .append(" animators").append(animators(c)).append(']');
        }
        traceShelf(g, b);
        trace(b.toString());
    }

    /** From where the card of {@code row} is drawn, if it lies under the newest; else NaN. */
    static float behindFrom(View row) {
        Float f = BEHIND.get(row);
        return f != null ? f : Float.NaN;
    }

    private static boolean pileWanted(View stack) {
        int[] s = LockGlass.settings;
        return stack != null && !opened && s.length > PILE && s[PILE] != 0 && wanted(stack)
                && onKeyguard(stack) && pileAmount(stack) > 0f;
    }

    /**
     * How far the cards are piled, 1 to 0, as the shade is pulled down over them (halfway
     * there, a list): they spread with the finger, as One UI places them every step of the
     * drag (6.67 broke the pile at the first pixel, and the stack jumped).
     */
    private static float pileAmount(View stack) {
        return 1f - Math.min(1f, toShade(stack) * 2f);
    }

    /** The stack One UI's updater (NSSL$1) belongs to. */
    private static View stackOfUpdater(Object updater) {
        if (!updaterLooked) {
            updaterLooked = true;
            try {
                updaterStack = updater.getClass().getDeclaredField("this$0");
                updaterStack.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        try {
            Object st = updaterStack != null ? updaterStack.get(updater) : null;
            if (st instanceof View) return (View) st;
        } catch (Throwable ignored) {
        }
        return stackRef != null ? stackRef.get() : null;
    }

    /** One UI asked to lay the cards out again (their places computed, then piled). */
    private static void relayout(View stack) {
        // One UI's updater asked; a layout pass only without it (6.66's extra pass put the
        // cards at their places a frame into the opening).
        if (!requestUpdate(stack)) stack.requestLayout();
        stack.invalidate();
    }

    private static Method childrenUpdate;
    private static boolean childrenUpdateLooked;

    /** One UI's updater asked to place the cards again before the next frame. */
    private static boolean requestUpdate(View stack) {
        if (!childrenUpdateLooked) {
            childrenUpdateLooked = true;
            childrenUpdate = declared(stack.getClass(), "requestChildrenUpdate");
        }
        if (childrenUpdate == null) return false;
        try {
            childrenUpdate.invoke(stack);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * iOS's stack, made where One UI lays the cards out: after its algorithm computed each
     * card's place (its view state), the newest is left where it is and the next two are put
     * under it, a little smaller, their bottoms showing below it; the rest behind. One UI then
     * sets or animates the cards to these places itself, so nothing here fights its
     * animations (6.54-6.55 moved the cards after it, and it moved them back), and a new
     * notification comes onto the pile as One UI brings it in, on top.
     */
    /** The cards' places just computed by One UI, not yet applied. */
    private static boolean fresh;

    private static void pileStates(View stack) {
        boolean was = !BEHIND.isEmpty();
        if (opened && was && stack instanceof ViewGroup) {
            // Opening: where each card is drawn in the pile now, to slide from.
            ViewGroup g = (ViewGroup) stack;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (!c.getClass().getName().endsWith("Row")
                        || c.getVisibility() == View.GONE) {
                    continue;
                }
                OPENING.put(c, new float[]{c.getTranslationY(), c.getScaleX(), c.getScaleY(),
                        c.getVisibility() == View.VISIBLE ? c.getAlpha() : 0f,
                        c.getTranslationZ(), c.getPivotY()});
            }
        }
        java.util.Map<View, Float> behind = new java.util.HashMap<>();
        boolean piled = false;
        try {
            if (pileWanted(stack) && stack instanceof ViewGroup) {
                // Piled again (the screen went off as it opened): no slide into the list.
                stopSlide();
                piled = pile((ViewGroup) stack, behind, pileAmount(stack));
            } else {
                // No pile (opened, the shade, off): nothing comes up into one after this, and
                // One UI's pivots are its own again.
                LAYER.clear();
                // The cards still sliding out of the pile keep its pivot (their card's bottom)
                // until they are in the list: One UI's (half the row's height) moved a card
                // scaled 0.9 by up to (pivot - bottom) / 10 in one frame.
                if (sliding()) PIVOT.keySet().retainAll(SLIDE.keySet());
                else PIVOT.clear();
                fullWas = false;
                pileRowsWas = -1;
            }
        } finally {
            // One UI animates the pile's sizes and fading with its places in this pass.
            pileMoving = was || piled;
            if (behind.isEmpty()) pileFront = null;
            if (!behind.keySet().equals(BEHIND.keySet())) {
                java.util.Set<View> changed = new java.util.HashSet<>(BEHIND.keySet());
                changed.addAll(behind.keySet());
                java.util.Set<View> before = new java.util.HashSet<>(BEHIND.keySet());
                BEHIND.clear();
                BEHIND.putAll(behind);
                for (View r : changed) {
                    if (r == null) continue;
                    // Its texts faded out under the newest, or in out of the pile (6.67 hid
                    // and showed them at once when its card was next drawn).
                    if (BEHIND.containsKey(r) && !before.contains(r)) {
                        LockNotifications.hideContent(r);
                    } else if (!BEHIND.containsKey(r)) {
                        LockNotifications.revealContent(r);
                    }
                    // Its glass and texts drawn again, for the pile or out of it.
                    r.invalidate();
                    if (r instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) r;
                        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).invalidate();
                    }
                }
            } else {
                BEHIND.putAll(behind);
            }
        }
    }

    /**
     * Piles the cards' places just computed, {@code amount} of the way from One UI's list
     * (0) to the pile (1); true if it changed them. The cards under the newest are in
     * {@code behind} (their texts hidden) while they are mostly piled.
     */
    private static boolean pile(ViewGroup stack, java.util.Map<View, Float> behind,
                                float amount) {
        float d = stack.getResources().getDisplayMetrics().density;
        java.util.List<View> cards = new java.util.ArrayList<>();
        java.util.Map<View, Object> states = new java.util.HashMap<>();
        Object shelf = null;
        // The last pass's places: a card come up into the pile is told by them.
        java.util.Map<View, Integer> was = new java.util.HashMap<>(LAYER);
        LAYER.clear();
        PIVOT.clear();
        boolean fullBefore = fullWas;
        fullWas = false;
        int rows = 0;
        for (int i = 0; i < stack.getChildCount(); i++) {
            View c = stack.getChildAt(i);
            if (!expandable.isInstance(c) || c.getVisibility() == View.GONE) continue;
            Object st = State.of(c);
            if (st == null) continue;
            if (c.getClass().getName().endsWith("NotificationShelf")) {
                shelf = st;
                continue;
            }
            if (!c.getClass().getName().endsWith("Row")) continue;
            rows++;
            if (State.flag(st, "gone") || State.flag(st, "hidden")
                    || State.num(st, "height") <= 0f) {
                // One UI's own (past its lock screen limit, in its shelf): not in the pile.
                LAYER.put(c, -1);
                continue;
            }
            cards.add(c);
            states.put(c, st);
        }
        long now = android.os.SystemClock.uptimeMillis();
        View frontWas = null;
        for (java.util.Map.Entry<View, Integer> e : was.entrySet()) {
            if (e.getValue() == 0) frontWas = e.getKey();
        }
        if ((pileRowsWas >= 0 && rows < pileRowsWas)
                || (frontWas != null && !cards.contains(frontWas))) {
            // A card left (swiped away, or taken back by its app): the ones under it come up.
            promoteUntil = now + 600;
            traceStart("pile rows " + pileRowsWas + " -> " + rows + ", newest #" + id(frontWas)
                    + (frontWas != null && !cards.contains(frontWas) ? " gone" : ""));
        }
        pileRowsWas = rows;
        if (cards.size() < 2 || amount <= 0f) {
            if (tracing()) {
                trace("pile by " + appliedBy + ": " + cards.size() + " cards, amount " + amount
                        + ", not piled");
            }
            return false;
        }
        // In the order One UI just placed them, the newest on top: its places of this pass,
        // never ones kept from before (6.55 sorted by places kept, stale after a new card).
        cards.sort((x, y) -> Float.compare(State.num(states.get(x), "yTranslation"),
                State.num(states.get(y), "yTranslation")));
        View first = cards.get(0);
        Object fs = states.get(first);
        float frontBottom = State.num(fs, "yTranslation") + cardBottom(first, fs);
        int shown = Math.min(cards.size() - 1, LAYERS);
        float z = State.num(fs, "zTranslation");
        State.put(fs, "zTranslation", z + (LAYERS + 1) * 0.2f);
        LAYER.put(first, 0);
        // Under the newest (its texts hidden) while mostly piled; a little apart either way,
        // so a finger resting there does not hide and show them every frame.
        boolean hidden = amount >= (BEHIND.isEmpty() ? 0.55f : 0.45f);
        // For a moment after a card left the whole pile, one coming up into it unseen (from
        // behind the pile at alpha 0, or out of One UI's shelf far below it) is put first at
        // its place in the pile, a layer deeper still, and One UI brings it forward there:
        // it fades in and grows to its layer. 6.86 left it where it was: One UI slid it up
        // from its shelf, and the stack, placed by where its cards end, went up 540 px and
        // came back while the card itself stood still; or, never drawn on the lock screen
        // yet, it counted with its row's whole height for a frame, and the stack hopped up.
        boolean promoting = now < promoteUntil && amount >= 1f && fullBefore;
        StringBuilder tr = tracing() ? new StringBuilder() : null;
        for (int i = 1; i < cards.size(); i++) {
            View c = cards.get(i);
            Object st = states.get(c);
            int layer = Math.min(i, LAYERS);
            float cb = cardBottom(c, st);
            float scale = 1f - layer * 0.05f;
            float strip = layer * LAYER_DP * d;
            float fading = i <= LAYERS ? 1f - layer * 0.15f : 0f;
            float listY = State.num(st, "yTranslation");
            float listAlpha = State.num(st, "alpha");
            float y = listY + (frontBottom + strip - cb - listY) * amount;
            float s = 1f + (scale - 1f) * amount;
            float cz = z + (LAYERS + 1 - layer) * 0.2f;
            float tyWas = c.getTranslationY();
            // Smaller about its card's bottom middle: that bottom stays where it is put.
            c.setPivotX(c.getWidth() / 2f);
            c.setPivotY(cb);
            PIVOT.put(c, cb);
            State.put(st, "yTranslation", y);
            State.put(st, "scaleX", s);
            State.put(st, "scaleY", s);
            State.put(st, "zTranslation", cz);
            State.put(st, "alpha", listAlpha * (1f + (fading - 1f) * amount));
            int to = i <= LAYERS ? i : LAYERS + 1;
            LAYER.put(c, to);
            Integer from = was.get(c);
            boolean pre = promoting && from != null
                    && (from < 0 || (to <= LAYERS && from > LAYERS))
                    && (c.getVisibility() != View.VISIBLE || c.getAlpha() <= 0.01f);
            if (pre) {
                // Still unseen: One UI animates from the view as it is (ViewState.animateTo),
                // so only its fading and size (the pile's filter) move, after its delay.
                float sFrom = to <= LAYERS ? 1f - (layer + 1) * 0.05f : s;
                // Faded in from nothing (One UI shows it at once, at the alpha it has).
                if (c.getVisibility() != View.VISIBLE) c.setAlpha(0f);
                c.setTranslationY(y);
                c.setScaleX(sFrom);
                c.setScaleY(sFrom);
                c.setTranslationZ(cz);
                // Where its card ends there until it is drawn (it may never have been on the
                // lock screen, or last been drawn clipped going into the shelf).
                LockNotifications.CARD_BOTTOM.put(c, Math.round(cb));
            }
            // Drawn from a little above the newest's bottom (in the row, before its scale).
            if (hidden) behind.put(c, cb - (OVERLAP_DP * d + strip) / scale);
            if (tr != null) {
                tr.append(" [i").append(i).append(" #").append(id(c)).append(" L")
                        .append(from == null ? "new" : String.valueOf(from)).append("->")
                        .append(to).append(vis(c))
                        .append(" a ").append(Math.round(c.getAlpha() * 100))
                        .append(" ty ").append(Math.round(tyWas))
                        .append(pre ? " put at " + Math.round(c.getTranslationY()) + " s "
                                + round3(c.getScaleY()) : "")
                        .append(" -> state y ").append(Math.round(y))
                        .append(" s ").append(round3(s))
                        .append(" a ").append(Math.round(State.num(st, "alpha") * 100))
                        .append(" h ").append(Math.round(State.num(st, "height")))
                        .append(" cb ").append(Math.round(cb)).append(']');
            }
        }
        float end = frontBottom + shown * LAYER_DP * d;
        fullWas = amount >= 1f;
        // The overflow icons, if any, under the pile rather than where the list would end.
        if (shelf != null && !State.flag(shelf, "hidden")) {
            float shelfY = State.num(shelf, "yTranslation");
            if (shelfY > end) State.put(shelf, "yTranslation", shelfY + (end - shelfY) * amount);
        }
        if (hidden) pileFront = new java.lang.ref.WeakReference<>(first);
        if (!loggedPile) {
            loggedPile = true;
            XLog.i("lock bottom: piled " + cards.size() + " cards, the newest at "
                    + Math.round(State.num(fs, "yTranslation")) + ", ending at "
                    + Math.round(end) + " (state: " + State.describe(fs) + ")");
        }
        if (tr != null) {
            trace("pile by " + appliedBy + ", amount " + amount + ", rows " + rows
                    + (promoting ? ", a card left" : "") + ", end " + Math.round(end)
                    + ", front #" + id(first) + " L" + was.get(first) + " ty "
                    + Math.round(first.getTranslationY()) + " -> state y "
                    + Math.round(State.num(fs, "yTranslation")) + " cb "
                    + Math.round(frontBottom - State.num(fs, "yTranslation")) + ":" + tr);
        }
        return true;
    }

    private static boolean loggedPile;

    /**
     * Where the card of {@code row} ends in it at rest: as last drawn without One UI's clip
     * (6.67 took a clipped 2-4 dp), else the row's height.
     */
    private static float cardBottom(View row, Object state) {
        float h = State.num(state, "height");
        Integer drawn = LockNotifications.CARD_REST.get(row);
        if (drawn != null && drawn > 0 && drawn <= h) return drawn;
        if (drawn == null) {
            // Not drawn on the lock screen yet (a card kept behind the pile, or in One UI's
            // shelf): where its card ends by its layout, as it will be drawn. The row's height
            // put it 170 px too high in the pile, and it snapped down once drawn (6.86).
            int laid = LockNotifications.restBottom(row);
            if (laid > 0 && laid <= h) return laid;
        }
        return h;
    }

    /**
     * A card's view state (One UI's place for it), read and set by name: getters and setters
     * on Android 16, the fields on older ones.
     */
    private static final class State {
        private static final java.util.Map<String, java.lang.reflect.Member> GET =
                new java.util.HashMap<>();
        private static final java.util.Map<String, java.lang.reflect.Member> SET =
                new java.util.HashMap<>();
        private static Field viewState;
        private static boolean looked;

        /** ExpandableView.mViewState (One UI 8.5 has no getter left). */
        static Object of(View c) {
            if (!looked) {
                looked = true;
                for (Class<?> k = expandable; k != null && viewState == null;
                        k = k.getSuperclass()) {
                    try {
                        viewState = k.getDeclaredField("mViewState");
                        viewState.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                XLog.i("lock bottom: view states " + (viewState != null ? "found" : "none"));
            }
            try {
                return viewState != null ? viewState.get(c) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private static String cap(String n) {
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }

        private static java.lang.reflect.Member getter(Class<?> k, String n) {
            String key = k.getName() + "#" + n;
            if (GET.containsKey(key)) return GET.get(key);
            java.lang.reflect.Member m = null;
            for (Class<?> c = k; c != null && m == null; c = c.getSuperclass()) {
                for (String name : new String[]{"get" + cap(n), "is" + cap(n)}) {
                    try {
                        Method x = c.getDeclaredMethod(name);
                        x.setAccessible(true);
                        m = x;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                for (String name : new String[]{n, "m" + cap(n)}) {
                    if (m != null) break;
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        m = f;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            GET.put(key, m);
            return m;
        }

        private static java.lang.reflect.Member setter(Class<?> k, String n) {
            String key = k.getName() + "#" + n;
            if (SET.containsKey(key)) return SET.get(key);
            java.lang.reflect.Member m = null;
            for (Class<?> c = k; c != null && m == null; c = c.getSuperclass()) {
                for (Method x : c.getDeclaredMethods()) {
                    if (x.getName().equals("set" + cap(n)) && x.getParameterCount() == 1) {
                        x.setAccessible(true);
                        m = x;
                        break;
                    }
                }
                for (String name : new String[]{n, "m" + cap(n)}) {
                    if (m != null) break;
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        m = f;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            SET.put(key, m);
            return m;
        }

        private static Object read(Object st, String n) {
            java.lang.reflect.Member m = getter(st.getClass(), n);
            try {
                if (m instanceof Method) return ((Method) m).invoke(st);
                if (m instanceof Field) return ((Field) m).get(st);
            } catch (Throwable ignored) {
            }
            return null;
        }

        static float num(Object st, String n) {
            Object v = read(st, n);
            return v instanceof Number ? ((Number) v).floatValue() : 0f;
        }

        static boolean flag(Object st, String n) {
            return Boolean.TRUE.equals(read(st, n));
        }

        static void put(Object st, String n, float v) {
            java.lang.reflect.Member m = setter(st.getClass(), n);
            try {
                Class<?> t = m instanceof Method ? ((Method) m).getParameterTypes()[0]
                        : m instanceof Field ? ((Field) m).getType() : null;
                if (t == null) return;
                Object value = t == int.class ? (Object) Math.round(v) : (Object) v;
                if (m instanceof Method) ((Method) m).invoke(st, value);
                else ((Field) m).set(st, value);
            } catch (Throwable ignored) {
            }
        }

        static String describe(Object st) {
            StringBuilder b = new StringBuilder(st.getClass().getSimpleName());
            for (String n : new String[]{"yTranslation", "height", "alpha", "scaleX",
                    "zTranslation", "hidden", "gone"}) {
                java.lang.reflect.Member g = getter(st.getClass(), n);
                java.lang.reflect.Member s = setter(st.getClass(), n);
                b.append(' ').append(n).append(g == null ? " -" : " r").append(s == null
                        ? "-" : "w");
            }
            return b.toString();
        }
    }

    private static int heightOf(View c) {
        int h = c.getHeight();
        try {
            Object a = actualHeight != null ? actualHeight.invoke(c) : null;
            if (a instanceof Integer) h = (Integer) a;
        } catch (Throwable ignored) {
        }
        return h;
    }

    /** Where a card ends as it is drawn now (its scale about its pivot included). */
    private static float bottomOf(View c) {
        float h = heightOf(c);
        // Where its card ends, as drawn: a new notification comes in its full height first
        // (1235 px for a screenshot's, 6.60 then left the list at the top), its card is short.
        Integer card = LockNotifications.CARD_BOTTOM.get(c);
        if (card == null) {
            // Not drawn on the lock screen yet: where its card ends by its layout (6.86 took
            // the row's whole height for that frame, and the stack hopped up and back).
            int laid = LockNotifications.restBottom(c);
            if (laid > 0) card = laid;
        }
        if (card != null && card > 0 && card < h) h = card;
        return c.getY() + c.getPivotY() + (h - c.getPivotY()) * c.getScaleY();
    }

    /**
     * Checked again for a moment after the cards change (a notification comes or goes, One UI
     * moves the stack back): One UI animates them then, and the last frame before they settle
     * may leave nothing to check (6.57 left two cards at the top).
     */
    private static void checkSoon(View stack) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean running = now < checksUntil;
        checksUntil = now + 2500;
        if (running) return;
        Runnable[] again = new Runnable[1];
        again[0] = () -> {
            try {
                update(stack, "poll");
            } catch (Throwable t) {
                XLog.e("lock bottom", t);
            }
            if (android.os.SystemClock.uptimeMillis() < checksUntil) {
                stack.postDelayed(again[0], 120);
            }
        };
        stack.postDelayed(again[0], 120);
    }

    private static void update(View stack, String by) {
        boolean pile = pileWanted(stack);
        if (pileWas == null || pile != pileWas) {
            // On or off (locked, the setting, opened): One UI lays the cards out again.
            pileWas = pile;
            amountWas = pileAmount(stack);
            relayout(stack);
        } else if (pile) {
            // Spreading or piling up with the shade's fraction: placed again each step,
            // also when it is the quick settings pulled (One UI asks for that drag only).
            float amount = pileAmount(stack);
            if (Math.abs(amount - amountWas) > 0.002f) {
                amountWas = amount;
                requestUpdate(stack);
            }
        }
        boolean keyguard = onKeyguard(stack);
        if (stack instanceof ViewGroup && LockNotifications.on() && stack.isShown()) {
            if (LockNotifications.locked(stack.getContext())) {
                LockNotifications.stockForShade((ViewGroup) stack, !keyguard);
                if (keyguardWas == null || keyguard != keyguardWas) {
                    // The texts' lines as One UI's in the shade, one again on the lock screen.
                    keyguardWas = keyguard;
                    LockNotifications.oneLineFor(keyguard);
                }
            } else {
                LockNotifications.forgetShadeBlur();
                keyguardWas = null;
            }
        }
        anchored = wanted(stack) && keyguard && stack instanceof ViewGroup;
        if (!anchored) liveSince = -1;
        else if (liveSince < 0) liveSince = android.os.SystemClock.uptimeMillis();
        if (!anchored) {
            // The shade, unlocked, or off: where One UI puts it.
            targetShown = Float.NaN;
            offsetSlideAt = -1;
            ENTERING.clear();
            SHELVED.clear();
            move(stack, 0f, "off");
            return;
        }
        ViewGroup g = (ViewGroup) stack;
        float d = stack.getResources().getDisplayMetrics().density;
        // Cards above the stack's top padding are not at rest (coming in as the screen comes
        // on, going out): 6.52 counted them ("cards 0..1235") and moved the stack wrong.
        float minY = topPadding(stack) - 24f * d;
        java.util.List<View> cards = new java.util.ArrayList<>();
        int rows = 0;
        View front = pileFront != null && !BEHIND.isEmpty() ? pileFront.get() : null;
        java.util.List<View> shelved = new java.util.ArrayList<>();
        boolean leaving = false;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            // The cards only: not One UI's shelf, which sits low in the stack even empty
            // (6.49's stack read as already down, "1 px"), nor its footer.
            if (!expandable.isInstance(c) || !c.getClass().getName().endsWith("Row")
                    || c.getVisibility() == View.GONE) {
                continue;
            }
            rows++;
            // The pile's cards fade as one, as its newest does: each with its own fading
            // (the pile's 70 %, 55 %) dropped out at another moment, and the stack hopped.
            float alpha = front != null && (c == front || BEHIND.containsKey(c))
                    ? front.getAlpha() : c.getAlpha();
            Object st = State.of(c);
            // A card One UI brings up into the list as one above it goes (out of its shelf,
            // a card's height under the list's end, at full alpha at once: its swipe filter
            // does not animate alpha): it ends the list where it is going. Where it was, the
            // list's end dropped 198 px in one frame, the stack jumped up that far and the
            // cards above it snapped into their new places.
            boolean out = c.getVisibility() != View.VISIBLE
                    || (st != null && State.flag(st, "hidden"));
            if (out) shelved.add(c);
            // Going into One UI's shelf as a notification comes in above (hidden at once,
            // still sliding down under the list): the list ends where its places end.
            if (BEHIND.isEmpty() && st != null && c.getVisibility() == View.VISIBLE
                    && State.flag(st, "hidden")
                    && c.getTranslationY() < State.num(st, "yTranslation") - 0.5f) {
                leaving = true;
            }
            boolean entering = !out && BEHIND.isEmpty() && st != null && c.getHeight() > 0
                    && heightOf(c) > 0 && State.num(st, "alpha") >= 0.3f
                    && (ENTERING.containsKey(c) || SHELVED.containsKey(c))
                    && c.getTranslationY() > State.num(st, "yTranslation") + 0.5f;
            if (entering) {
                ENTERING.put(c, Boolean.TRUE);
                cards.add(c);
                continue;
            }
            ENTERING.remove(c);
            if (c.getVisibility() != View.VISIBLE || c.getHeight() <= 0
                    || alpha < 0.3f || c.getY() < minY || heightOf(c) <= 0) {
                continue;
            }
            // Not one One UI is sending into the shelf, or has hidden.
            if (st != null && State.flag(st, "hidden")) continue;
            cards.add(c);
        }
        SHELVED.clear();
        for (View c : shelved) SHELVED.put(c, Boolean.TRUE);
        if (rows != countWas) {
            // A notification came or went: logged, and checked again as One UI brings it in.
            logCards(g, minY, countWas, rows);
            if (countWas >= 0 && rows < countWas) {
                long now = android.os.SystemClock.uptimeMillis();
                // Gone before the eyes only: not one gone in the shade, unlocked or with the
                // screen off, seen as the lock screen comes back (its first placement).
                if (now - liveSince > 300) removedAt = now;
                traceStart("rows " + countWas + " -> " + rows);
            }
            countWas = rows;
            checkSoon(stack);
        }
        if (rows == 0) {
            // No notification: nothing over the fingerprint sensor, no pile to swipe (6.67
            // kept the last bounds, and touches on the sensor were kept from the lock screen).
            cardsTop = cardsBottom = -1f;
            pileTop = pileBottom = -1f;
        }
        // No card at rest this frame: the stack stays where it is (a single frame never sends
        // it back up); after a change it is checked again for a moment.
        if (cards.isEmpty()) {
            if (tracing()) traceRows(g, by, minY, front, Float.NaN, Float.NaN);
            return;
        }
        // The expand arrow touched on a card of the pile opens it into the list too, until
        // the screen goes off (a tap on a card: performClick).
        if (!BEHIND.isEmpty() && !opened) {
            for (View c : cards) {
                if (LockNotifications.userExpandedRow(c)) {
                    open(stack, "the expand arrow");
                    break;
                }
            }
        }
        float top = Float.MAX_VALUE;
        float bottom = -1f;
        for (View c : cards) {
            top = Math.min(top, c.getY());
            float b = bottomOf(c);
            Object st = ENTERING.containsKey(c) || leaving ? State.of(c) : null;
            // Coming up into the list: where it ends once there.
            if (st != null) {
                float there = b + State.num(st, "yTranslation") - c.getTranslationY();
                b = ENTERING.containsKey(c) ? there : Math.max(b, there);
            }
            bottom = Math.max(bottom, b);
        }
        lastTop = top;
        {
            // Where the cards are on the screen, for touches on the fingerprint sensor.
            int[] at = new int[2];
            stack.getLocationOnScreen(at);
            cardsTop = at[1] + top;
            cardsBottom = at[1] + bottom;
        }
        if (!BEHIND.isEmpty()) {
            // Where the pile is on the screen, for a swipe up on it to open it.
            int[] at = new int[2];
            stack.getLocationOnScreen(at);
            pileTop = at[1] + top;
            pileBottom = at[1] + bottom;
        } else {
            pileTop = pileBottom = -1f;
        }
        listBottom(stack, bottom, d);
        if (tracing()) traceRows(g, by, minY, front, top, bottom);
    }

    /** The stack moved down until its cards, ending at {@code bottom} in it, sit low. */
    private static void listBottom(View stack, float bottom, float d) {
        lastBottom = bottom;
        if (!screenWatched) {
            screenWatched = true;
            stack.getContext().getApplicationContext().registerReceiver(
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context c,
                                              android.content.Intent i) {
                            opened = false;
                            // The next lock screen placed at once, not eased from this one's.
                            targetShown = Float.NaN;
                            liveSince = -1;
                            View st = stackRef != null ? stackRef.get() : null;
                            if (st != null) {
                                st.post(() -> {
                                    try {
                                        update(st, "screen off");
                                    } catch (Throwable t) {
                                        XLog.e("lock bottom", t);
                                    }
                                });
                            }
                        }
                    }, new android.content.IntentFilter(
                            android.content.Intent.ACTION_SCREEN_OFF));
        }
        int[] at = new int[2];
        stack.getLocationOnScreen(at);
        // One UI sets the stack's translation itself at times: then this class's offset is
        // gone (6.53 added it again each time, 933, 1866, 2799 px, off the screen).
        boolean oneUi = oneUiMoved(stack);
        if (oneUi) checkSoon(stack);
        // Where the cards end on screen with no translation at all: One UI's own (its slide
        // as the lock screen is swiped up, or comes back over an app) stays on top of this
        // class's offset, the cards go with the rest (6.67 took it off again, they stayed).
        float laid = at[1] - stack.getTranslationY();
        float end = laid + bottom;
        float target = stack.getRootView().getHeight() - GAP_DP * d;
        // Above the text under the stack (the owner's text, the charging line), not over it.
        View text = indication(stack);
        if (text != null) {
            int[] t = new int[2];
            text.getLocationOnScreen(t);
            float textTop = t[1];
            if (textTop > laid + lastTop && textTop - 12f * d < target) {
                target = textTop - 12f * d;
            }
        }
        // The first placement of a lock screen (the screen on, back from the shade) at once,
        // also when a notification went while it was off.
        boolean first = Float.isNaN(targetShown);
        // Placed at once: a slide from before is from another base (One UI's own move).
        if (oneUi || first) offsetSlideAt = -1;
        // Going into the shade: back where One UI puts the cards as it goes.
        float offset = Math.max(0f, eased(stack, target, d) - end) * (1f - toShade(stack));
        move(stack, oneUi || first ? offset : afterRemoval(stack, offset, d), "cards");
    }

    /** The offset sliding to a far one after a card went (-1: none sliding). */
    private static long offsetSlideAt = -1;
    private static float offsetSlideFrom;
    private static float offsetSlideTo;

    /**
     * {@code offset} as the stack is moved now: a far move of it in one frame right after a
     * notification went from the cards (the list's last card swiped away: nothing of One
     * UI's moves, the cards above stood 198 px higher at once) slid over 360 ms instead, as
     * iOS's list closes up; any other at once, as the cards are placed by where they end.
     */
    private static float afterRemoval(View stack, float offset, float d) {
        long now = android.os.SystemClock.uptimeMillis();
        if (offsetSlideAt >= 0) {
            float f = (now - offsetSlideAt) / 360f;
            if (f < 1f && toShade(stack) <= 0f) {
                if (Math.abs(offset - offsetSlideTo) > 24f * d) {
                    // Its target jumped again as it slid: on from where it is shown (passed
                    // through, 3/4 of the jump showed in one frame).
                    offsetSlideAt = now;
                    offsetSlideFrom = applied;
                    f = 0f;
                }
                offsetSlideTo = offset;
                stack.postInvalidateOnAnimation();
                return offsetSlideFrom + (offset - offsetSlideFrom) * EASE.getInterpolation(f);
            }
            offsetSlideAt = -1;
        }
        // Only in the frames the card goes in (a frame slow by 48 ms in One UI's own close-up
        // also moves 72 px: that one followed as it is).
        if (now - removedAt < 120 && Math.abs(offset - applied) > 24f * d
                && toShade(stack) <= 0f && !sliding()) {
            offsetSlideAt = now;
            offsetSlideFrom = applied;
            offsetSlideTo = offset;
            if (tracing()) {
                trace("offset slides " + Math.round(applied) + " -> " + Math.round(offset));
            }
            stack.postInvalidateOnAnimation();
            return applied;
        }
        return offset;
    }

    /** The lower limit as shown, easing to a new one far from it (300 ms). */
    private static float targetShown = Float.NaN;
    private static float targetFrom;
    private static float targetTo;
    private static long targetAt;

    /**
     * {@code target} as the cards are placed now: a far move of the limit (the text under
     * the stack shown or gone, 462 px a few seconds after System UI starts) eased over
     * 300 ms, not in one frame; a small one, and the first of a lock screen, at once.
     */
    private static float eased(View stack, float target, float d) {
        long now = android.os.SystemClock.uptimeMillis();
        if (Float.isNaN(targetShown)) {
            targetShown = targetFrom = targetTo = target;
            targetAt = 0;
            return target;
        }
        if (Math.abs(target - targetTo) > 24f * d) {
            targetFrom = targetShown;
            targetAt = now;
        } else if (now - targetAt >= 300) {
            targetFrom = target;
        }
        targetTo = target;
        float f = Math.min(1f, (now - targetAt) / 300f);
        targetShown = f >= 1f ? targetTo
                : targetFrom + (targetTo - targetFrom) * EASE.getInterpolation(f);
        // The next frame's pre-draw while it eases, also with nothing else changing.
        if (f < 1f) stack.postInvalidateOnAnimation();
        return targetShown;
    }

    /** Diagnostics ({@link Config#DEBUG}): the cards as a notification came or went. */
    private static void logCards(ViewGroup g, float minY, int was, int now) {
        if (!Config.DEBUG) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.getClass().getName().endsWith("Row")) continue;
            Object st = State.of(c);
            b.append(" [y ").append(Math.round(c.getY())).append(" h ").append(heightOf(c))
                    .append(" a ").append(Math.round(c.getAlpha() * 100)).append("% ")
                    .append(c.getVisibility() == View.VISIBLE ? "shown" : "hidden");
            if (st != null) b.append(", state y ")
                    .append(Math.round(State.num(st, "yTranslation")));
            b.append(']');
        }
        XLog.debug("lock bottom: cards " + was + " -> " + now + " (from y " + Math.round(minY)
                + "):" + b);
    }

    private static java.util.List<java.lang.ref.WeakReference<View>> indications =
            new java.util.ArrayList<>();
    private static long indicationSearched;
    private static String indicationLogged = "";

    /**
     * The lock screen's text under the notifications that shows now (One UI has several
     * indication texts, most hidden: 6.56 took a hidden one): of the texts with an indication
     * or owner id, the shown one with text, highest on the screen below its middle.
     */
    private static View indication(View stack) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - indicationSearched > 1000) {
            indicationSearched = now;
            java.util.List<View> all = new java.util.ArrayList<>();
            findIndications(stack.getRootView(), 0, all);
            indications = new java.util.ArrayList<>();
            for (View v : all) indications.add(new java.lang.ref.WeakReference<>(v));
        }
        int half = stack.getRootView().getHeight() / 2;
        View best = null;
        int bestTop = Integer.MAX_VALUE;
        int[] at = new int[2];
        for (java.lang.ref.WeakReference<View> r : indications) {
            View v = r.get();
            if (!(v instanceof android.widget.TextView) || !v.isShown() || v.getHeight() <= 0
                    || v.getAlpha() < 0.1f) {
                continue;
            }
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t == null || t.toString().trim().isEmpty()) continue;
            v.getLocationOnScreen(at);
            if (at[1] > half && at[1] < bestTop) {
                bestTop = at[1];
                best = v;
            }
        }
        String name = best == null ? "" : idName(best);
        if (Config.DEBUG && !name.equals(indicationLogged)) {
            indicationLogged = name;
            XLog.debug("lock bottom: the list ends above " + (best == null ? "the screen's bottom"
                    : best.getClass().getSimpleName() + "#" + name + " \""
                    + ((android.widget.TextView) best).getText() + "\""));
        }
        return best;
    }

    private static void findIndications(View v, int depth, java.util.List<View> out) {
        if (depth > 16) return;
        String id = idName(v);
        // Not the fingerprint sensor's hints ("cover the sensor fully"): shown for a moment
        // on a touch, they moved the list up and back (6.61).
        if (v instanceof android.widget.TextView
                && (id.contains("indication") || id.contains("owner"))
                && !id.contains("fingerprint") && !id.contains("biometric")) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findIndications(g.getChildAt(i),
                    depth + 1, out);
        }
    }

    private static String idName(View v) {
        if (v.getId() == View.NO_ID) return "";
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * One UI set the stack's translation itself since this class last did: none of this
     * class's offset is in it any more, One UI's value is the base.
     */
    private static boolean oneUiMoved(View stack) {
        float ty = stack.getTranslationY();
        if (Math.abs(ty - setTo) <= 0.5f) return false;
        if (tracing()) {
            trace("One UI set the stack's translation " + Math.round(ty) + " (this class set "
                    + Math.round(setTo) + ", its offset " + Math.round(applied) + ")");
        }
        applied = 0f;
        setTo = ty;
        return true;
    }

    /** The stack moved {@code offset} down from where One UI puts it. */
    private static void move(View stack, float offset, String why) {
        // Also going off: 6.67 took its old offset off One UI's new translation there, and
        // the shade's list sat that far up.
        oneUiMoved(stack);
        if (Math.abs(offset - applied) <= 0.5f) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (Config.DEBUG && Math.abs(offset - lastLogged) > 60f && now - loggedAt > 2000) {
            loggedAt = now;
            lastLogged = offset;
            XLog.debug("lock bottom: " + Math.round(offset) + " px down (" + why + ": cards "
                    + Math.round(lastTop) + ".." + Math.round(lastBottom) + ", top padding "
                    + Math.round(topPadding(stack)) + ", translation "
                    + Math.round(stack.getTranslationY()) + ")");
        }
        float base = stack.getTranslationY() - applied;
        if (tracing()) {
            trace("move (" + why + "): offset " + Math.round(applied) + " -> "
                    + Math.round(offset) + ", translation " + Math.round(stack.getTranslationY())
                    + " -> " + Math.round(base + offset) + ", cards " + Math.round(lastTop) + ".."
                    + Math.round(lastBottom));
        }
        stack.setTranslationY(base + offset);
        setTo = stack.getTranslationY();
        applied = offset;
    }
}
