package com.monotonnoe.oneglass.engine;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;

/**
 * Keeps the glass in step with a page fling.
 *
 * <p>While a finger drags the pages, the launcher scrolls them in the touch handler, before
 * the frame: the pre-draw pass sees the icons where they will be drawn and records the glass
 * there. A fling is different: its scroller moves the pages inside the draw, in the pager's
 * {@code computeScroll}, after the pre-draw pass has run. The glass was then recorded for the
 * previous frame's position and trailed the wallpaper by one frame, the faster the fling the
 * further. The pager's {@code computeScroll} is hooked here, and the glass is synced again
 * right after it, inside the same frame: the icons' display lists are still the UI thread's
 * until the frame ends, so their glass nodes can be recorded at this point as at any other.
 */
final class PagerScroll {
    private static final Set<Class<?>> HOOKED = new HashSet<>();
    /** Classes of the pages (cell layouts) whose chain was looked at, hooked or not. */
    private static final Set<Class<?>> CHECKED = new HashSet<>();
    private static boolean reported;

    /**
     * The scroll before each computeScroll on the stack, outermost first: a pager's
     * computeScroll may call its base class's, both hooked. Main thread only.
     */
    private static final int[] SX = new int[8];
    private static final int[] SY = new int[8];
    private static int depth;
    /** An inner call moved the scroll: the outermost call syncs once for all. */
    private static boolean movedInside;

    private PagerScroll() {
    }

    /**
     * Hooks every class at or above {@code page} (a cell layout; the Apps screen's and a
     * folder's anchor may be the pager itself) that declares computeScroll, up to a few
     * levels, each once. Which of them scrolls is not known here: the hook finds out.
     */
    static void watch(View page) {
        try {
            synchronized (CHECKED) {
                if (!CHECKED.add(page.getClass())) return;
            }
            StringBuilder chain = new StringBuilder();
            StringBuilder hooked = new StringBuilder();
            boolean covered = false;
            int levels = 0;
            ViewParent start = page instanceof ViewParent ? (ViewParent) page : page.getParent();
            for (ViewParent p = start; p instanceof View && levels < 5;
                 p = p.getParent(), levels++) {
                chain.append(' ').append(p.getClass().getSimpleName());
                Class<?> c = declaring(p.getClass());
                if (c == null) continue;
                boolean fresh;
                synchronized (HOOKED) {
                    fresh = HOOKED.add(c);
                }
                if (!fresh) {
                    // Hooked for another page already (the Apps screen's pager shares
                    // FastRecyclerView's with the Home screen's): followed all the same.
                    covered = true;
                    hooked.append(' ').append(c.getName()).append("(already)");
                    continue;
                }
                XposedBridge.hookAllMethods(c, "computeScroll", new SafeHook("computeScroll") {
                    @Override
                    protected void before(MethodHookParam param) {
                        // The no-argument method on a view only (an overload or a static
                        // helper of the same name is not the scroller's).
                        if (param.args.length != 0 || !(param.thisObject instanceof View)) {
                            return;
                        }
                        View v = (View) param.thisObject;
                        if (depth < SX.length) {
                            SX[depth] = v.getScrollX();
                            SY[depth] = v.getScrollY();
                        }
                        depth++;
                    }

                    @Override
                    protected void after(MethodHookParam param) {
                        if (param.args.length != 0 || !(param.thisObject instanceof View)) {
                            return;
                        }
                        View v = (View) param.thisObject;
                        depth = Math.max(0, depth - 1);
                        boolean moved = depth < SX.length
                                && (v.getScrollX() != SX[depth] || v.getScrollY() != SY[depth]);
                        if (depth > 0) {
                            if (moved) movedInside = true;
                            return;
                        }
                        // Only when computeScroll moved the pager (a fling): a drag scrolled
                        // in the touch handler already, and the pre-draw pass saw it.
                        if (moved || movedInside) {
                            movedInside = false;
                            IconBlur.onPagerScrolled();
                        }
                    }
                });
                hooked.append(' ').append(c.getName());
            }
            if (hooked.length() > 0) {
                XLog.i("glass follows flings through computeScroll of" + hooked);
            } else if (!covered && !reported) {
                reported = true;
                XLog.i("no computeScroll above the page, flings may trail:" + chain);
            }
        } catch (Throwable t) {
            XLog.e("pager scroll hook", t);
        }
    }

    /** The nearest class of {@code c}, below the framework's, that declares computeScroll. */
    private static Class<?> declaring(Class<?> c) {
        for (; c != null && c != View.class && c != ViewGroup.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("android.")) return null;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("computeScroll") && m.getParameterTypes().length == 0
                        && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    return c;
                }
            }
        }
        return null;
    }
}
