package com.launcher3mod.stacking;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.OverScroller;
import android.widget.Button;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Empilement des recents pour Launcher3 / Pixel Launcher (Android 17).
 *
 * Principe (post-layout, comme AOSPStackingRecent) : Launcher3 fait son layout paged normal,
 * puis, a chaque frame (RecentsView.updateCurveProperties), on calcule un offset virtuel par
 * TaskView et on l'applique. Aucun algorithme de layout n'est reimplemente, ce qui rend le
 * module tolerant aux versions.
 *
 * Signatures verifiees sur NexusLauncherRelease v17 (dexdump) :
 *   RecentsView.updatePageOffsets()V, updateCurveProperties()V, dispatchDraw(Canvas)V,
 *                onLayout(ZIIII)V, getPagedOrientationHandler(), getRunningTaskView(),
 *                getPageSpacing()I (herite de PagedView), mPageSpacing/mPageScrolls (PagedView)
 *   TaskView.getOffsetAdjustment(Z)F, applyTranslationX()V, applyTranslationY()V,
 *           resetViewTransforms()V, nonGridTranslationX:F, dismissTranslationX:F
 */
public class Stacker {

    private static final String TAG = RecentsStack.TAG;

    // Reglages (exposes pour ajustement).
    private static final float PAGE_SPACING_RATIO = 0.50f; // resserre l'espacement des pages
    private static final float BASE_SCALE = 1.00f;       // taille de la carte centree (= stock)
    private static final float SCALE_DROP = 0.20f;       // reduction supplementaire vers les bords
    private static final float MAX_ROTATION = 50f;       // inclinaison 3D maximale (degres)
    private static final float ALPHA_DROP = 0.35f;       // attenuation vers les bords
    private static final float Z_DROP = 200f;            // eloignement en Z vers les bords
    private static final float CAMERA_FACTOR = 3.5f;     // distance camera (perspective)
    private static final float NORM_RANGE = 0.5f;        // demi-largeur de normalisation

    // Mode "cartes empilees" (inspire AOSPStackingRecent).
    private static final float STACK_HILL_SCALE = 0.25f;
    private static final float STACK_HILL_EXP = 1.8f;
    private static final float STACK_PEEK_RATIO = 0.06f;
    private static final float STACK_SCALE_DROP = 0.15f;
    private static final float STACK_ALPHA_DROP = 0.20f;
    private static final float LERP = 0.65f;

    private static int mode = 0; // 0 = stock, 1 = flip, 2 = pile, 3 = grille2
    private static final int MODE_STOCK = 0;
    private static final int MODE_FLIP = 1;
    private static final int MODE_PILE = 2;
    private static final int MODE_GRID = 3;

    private static final float GRID_GAP_RATIO = 0.02f;  // espace entre cartes (ratio largeur)
    private static final float GRID_MARGIN_RATIO = 0.04f; // marge laterale (ratio largeur)
    private static final float GRID_MAX_SCALE = 0.60f;  // echelle maximale en grille
    // Cadence de reprise de la grille quand le launcher a reecrit ses canaux (layout,
    // retour sur Recents) : plus rapide que le filet de securite ordinaire.
    private static final long GRID_HEAL_FAST_MS = 100L;

    private static boolean snapNextFrame = false;
    private static View currentRecentsView;
    private Method updateCurvePropertiesMethod;

    private static float gridScrollY = 0f;
    private static float gridMaxScroll = 0f;
    private static OverScroller gridScroller;
    private static View gridHealView;
    private static int gridHealChildCount = -1;
    private static long gridHealUptime = 0L;
    private static final int TOUCH_SLOP = 24;
    private static final float KILL_DISTANCE_RATIO = 0.22f;
    private static final float KILL_FLING_VELOCITY = 900f;
    private static final String GRID_TOUCH_AXIS = "launcher3mod_grid_touch_axis";
    private static final String GRID_TOUCH_START_X = "launcher3mod_grid_touch_start_x";
    private static final String GRID_TOUCH_START_Y = "launcher3mod_grid_touch_start_y";
    private static final String GRID_TOUCH_LAST_X = "launcher3mod_grid_touch_last_x";
    private static final String GRID_TOUCH_LAST_Y = "launcher3mod_grid_touch_last_y";
    private static final String GRID_TOUCH_VELOCITY = "launcher3mod_grid_touch_velocity";
    private static final String GRID_TOUCH_TARGET = "launcher3mod_grid_touch_target";
    private static final int GRID_AXIS_UNDECIDED = 0;
    private static final int GRID_AXIS_VERTICAL = 1;
    private static final int GRID_AXIS_HORIZONTAL = 2;

    private final ClassLoader classLoader;

    private Class<?> recentsViewClass;
    private Class<?> taskViewClass;
    private Class<?> launcherRecentsViewClass;
    private Class<?> orientationHandlerClass;

    private Method getPagedOrientationHandlerMethod;
    private Method getPrimaryScrollMethod;
    private Method getMeasuredSizeMethod;
    private Method getChildStartMethod;
    private Method dismissTaskMethod;
    private Method getGridTranslationYMethod;

    private Field pageSpacingField;
    private Field pageScrollsField;
    private Field enableLiveTileField;
    private Field gridProgressField;

    private boolean loggedOnce = false;
    private long lastLogUptime = 0L;
    private long lastGridLoadUptime = 0L;
    private long lastGridRelayoutLog = 0L;
    private boolean loggedMatrixError = false;

    public Stacker(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void install() {
        recentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", classLoader);
        taskViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.TaskView", classLoader);
        launcherRecentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.LauncherRecentsView", classLoader);
        orientationHandlerClass = XposedHelpers.findClassIfExists("com.android.launcher3.touch.PagedOrientationHandler", classLoader);

        if (recentsViewClass == null || taskViewClass == null || orientationHandlerClass == null) {
            XposedBridge.log(TAG + ": classes introuvables recents=" + recentsViewClass
                    + " task=" + taskViewClass + " orient=" + orientationHandlerClass);
            return;
        }
        XposedBridge.log(TAG + ": classes OK");

        getPagedOrientationHandlerMethod = findMethod(recentsViewClass, "getPagedOrientationHandler");
        getPrimaryScrollMethod = findMethod(orientationHandlerClass, "getPrimaryScroll", View.class);
        getMeasuredSizeMethod = findMethod(orientationHandlerClass, "getMeasuredSize", View.class);
        getChildStartMethod = findMethod(orientationHandlerClass, "getChildStart", View.class);
        dismissTaskMethod = findMethod(recentsViewClass, "dismissTask", int.class, boolean.class);
        getGridTranslationYMethod = findMethod(taskViewClass, "getGridTranslationY");
        gridProgressField = findField(taskViewClass, "gridProgress");

        pageSpacingField = findField(recentsViewClass, "mPageSpacing");
        pageScrollsField = findField(recentsViewClass, "mPageScrolls");
        enableLiveTileField = findField(recentsViewClass, "mEnableDrawingLiveTile");

        hookApplyTranslationX();
        hookApplyTranslationY();
        hookResetViewTransforms();
        hookUpdateCurveProperties();
        hookPageSpacingSync();
        hookOverviewActionsButton();
        hookDismissController();
        hookPagedViewIntercept();
        hookGridChildRemoval();
        hookGridChildAddition();
        hookGridVisibleTaskIds();
        hookGridDrawRefresh();
        hookGridAttachReset();
        hookGridLayoutReset();

        XposedBridge.log(TAG + ": hooks installes (handler=" + (getPagedOrientationHandlerMethod != null)
                + " curve=" + (getChildStartMethod != null) + ")");
    }

    // ---- Hooks -----------------------------------------------------------------

    private void hookApplyTranslationX() {
        Method m = findMethod(taskViewClass, "applyTranslationX");
        if (m == null) {
            XposedBridge.log(TAG + ": applyTranslationX introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View child = (View) param.thisObject;
                Float target = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                float extra = target != null ? target : 0f;
                if (extra != 0f) {
                    child.setTranslationX(child.getTranslationX() + extra);
                }
            }
        });
        XposedBridge.log(TAG + ": hook applyTranslationX");
    }

    private void hookApplyTranslationY() {
        Method m = findMethod(taskViewClass, "applyTranslationY");
        if (m == null) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View child = (View) param.thisObject;
                Float target = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset_y");
                float extra = target != null ? target : 0f;
                if (extra != 0f) {
                    child.setTranslationY(child.getTranslationY() + extra);
                }
            }
        });
    }

    private void hookResetViewTransforms() {
        Method m = findMethod(taskViewClass, "resetViewTransforms");
        if (m == null) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View child = (View) param.thisObject;
                Float rot = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_rot_y");
                Float scale = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_scale");
                Float alpha = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_alpha");
                Float z = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_z");
                if (rot != null) child.setRotationY(rot);
                if (scale != null) {
                    child.setScaleX(scale);
                    child.setScaleY(scale);
                }
                if (alpha != null) child.setAlpha(alpha);
                if (z != null) child.setTranslationZ(z);
                // Launcher3 remet a zero les canaux de grille quand il recycle ou
                // rattache une TaskView. Le decalage memorise devient alors faux et
                // decalait toutes les cartes d'autant : repartir d'un etat propre.
                forgetGridCardState(child);
                try {
                    XposedHelpers.callMethod(child, "applyTranslationX");
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * RecentsViewUtils.getVisibleTaskIds() only reports the carousel's nearby pages, and the
     * view model unbinds every other card (no thumbnail, icon or title). The grid shows all
     * cards, so report every task as visible while it is active.
     */
    private void hookGridVisibleTaskIds() {
        Class<?> utils = XposedHelpers.findClassIfExists(
                "com.android.quickstep.views.RecentsViewUtils", classLoader);
        Method m = utils != null ? findMethod(utils, "getVisibleTaskIds") : null;
        if (m == null) {
            XposedBridge.log(TAG + ": getVisibleTaskIds introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (mode != MODE_GRID || currentRecentsView == null) return;
                try {
                    java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
                    ViewGroup group = (ViewGroup) currentRecentsView;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child == null || !taskViewClass.isInstance(child)) continue;
                        int[] taskIds = (int[]) XposedHelpers.callMethod(child, "getTaskIds");
                        if (taskIds == null) continue;
                        for (int id : taskIds) {
                            if (!ids.contains(id)) ids.add(id);
                        }
                    }
                    if (!ids.isEmpty()) param.setResult(ids);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": ids visibles grille impossibles " + t);
                }
            }
        });
    }

    /**
     * The running app's live surface cannot follow the card transforms of custom layouts (it
     * stays fullscreen and shows through the card). Hide its leash; the snapshot is shown instead.
     */
    private void hideLiveSurface(ViewGroup rv) {
        XposedBridge.log(TAG + ": DBG hideLiveSurface");
        try {
            XposedHelpers.callMethod(rv, "runActionOnRemoteHandles",
                    (java.util.function.Consumer<Object>) handle -> {
                        try {
                            Object params = XposedHelpers.callMethod(handle, "getTransformParams");
                            Object targets = XposedHelpers.callMethod(params, "getTargetSet");
                            XposedBridge.log(TAG + ": DBG handle targets=" + targets);
                            if (targets == null) return;
                            Object[] apps = (Object[]) XposedHelpers.getObjectField(targets, "unfilteredApps");
                            android.view.SurfaceControl.Transaction tx =
                                    new android.view.SurfaceControl.Transaction();
                            for (Object app : apps) {
                                android.view.SurfaceControl leash = (android.view.SurfaceControl)
                                        XposedHelpers.getObjectField(app, "leash");
                                if (leash != null && leash.isValid()) tx.setAlpha(leash, 0f);
                            }
                            tx.apply();
                            tx.close();
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": masquage live tile impossible " + t);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": acces aux handles live tile impossible " + t);
        }
    }
    private void hookUpdateCurveProperties() {
        Method m = findMethod(recentsViewClass, "updateCurveProperties");
        if (m == null) {
            XposedBridge.log(TAG + ": updateCurveProperties introuvable");
            return;
        }
        updateCurvePropertiesMethod = m;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handleUpdateCurveProperties(param);
            }
        });
        XposedBridge.log(TAG + ": hook updateCurveProperties");
    }

    private void hookPageSpacingSync() {
        XC_MethodHook sync = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                syncPageSpacing(param.thisObject);
            }
        };
        if (launcherRecentsViewClass != null) {
            try {
                XposedBridge.hookAllMethods(launcherRecentsViewClass, "onStateTransitionStart", sync);
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedBridge.hookAllMethods(recentsViewClass, "onMeasure", sync);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(recentsViewClass, "onLayout", sync);
        } catch (Throwable ignored) {
        }
    }

    private void hookPagedViewIntercept() {
        Class<?> paged = XposedHelpers.findClassIfExists("com.android.launcher3.PagedView", classLoader);
        if (paged == null) return;
        Method m = findMethod(paged, "onInterceptTouchEvent", MotionEvent.class);
        if (m != null) {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mode == MODE_GRID && recentsViewClass.isInstance(param.thisObject)) {
                        param.setResult(interceptGridScroll((ViewGroup) param.thisObject,
                                (MotionEvent) param.args[0]));
                    }
                }
            });
            XposedBridge.log(TAG + ": hook PagedView.onInterceptTouchEvent grille");
        }

        Method touch = findMethod(paged, "onTouchEvent", MotionEvent.class);
        if (touch != null) {
            XposedBridge.hookMethod(touch, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mode == MODE_GRID && recentsViewClass.isInstance(param.thisObject)
                            && handleGridScrollTouch((ViewGroup) param.thisObject,
                            (MotionEvent) param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            XposedBridge.log(TAG + ": hook PagedView.onTouchEvent grille");
        }
    }

    /** Intercepte scroll et swipe fermeture au niveau du conteneur, meme sur les previews internes. */
    private boolean interceptGridScroll(ViewGroup rv, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            VelocityTracker old = (VelocityTracker) XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY);
            if (old != null) old.recycle();
            VelocityTracker tracker = VelocityTracker.obtain();
            tracker.addMovement(event);
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY, tracker);
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_START_X, event.getRawX());
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_START_Y, event.getRawY());
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_LAST_X, event.getRawX());
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_LAST_Y, event.getRawY());
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_AXIS, GRID_AXIS_UNDECIDED);
            XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_TARGET,
                    findGridCardAt(rv, event.getRawX(), event.getRawY()));
            return false;
        }

        Object axisObject = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_AXIS);
        int axis = axisObject instanceof Number ? ((Number) axisObject).intValue() : GRID_AXIS_UNDECIDED;
        VelocityTracker tracker = (VelocityTracker) XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY);
        if (tracker != null) tracker.addMovement(event);

        if (action == MotionEvent.ACTION_MOVE && axis == GRID_AXIS_UNDECIDED) {
            Object sx = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_START_X);
            Object sy = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_START_Y);
            if (sx instanceof Number && sy instanceof Number) {
                float dx = event.getRawX() - ((Number) sx).floatValue();
                float dy = event.getRawY() - ((Number) sy).floatValue();
                float ax = Math.abs(dx);
                float ay = Math.abs(dy);
                int slop = Math.max(TOUCH_SLOP,
                        ViewConfiguration.get(rv.getContext()).getScaledTouchSlop());
                float travel = (float) Math.hypot(ax, ay);
                if (travel >= slop) {
                    if (ay >= slop * 0.75f && ay >= ax * 1.10f) {
                        axis = GRID_AXIS_VERTICAL;
                    } else if (ax >= ay * 1.45f) {
                        axis = GRID_AXIS_HORIZONTAL;
                    } else if (travel >= slop * 2f) {
                        axis = ay >= ax ? GRID_AXIS_VERTICAL : GRID_AXIS_HORIZONTAL;
                    }
                    XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_AXIS, axis);
                    if (axis != GRID_AXIS_UNDECIDED && gridScroller != null) {
                        gridScroller.forceFinished(true);
                    }
                }
            }
        }

        if (axis == GRID_AXIS_VERTICAL || axis == GRID_AXIS_HORIZONTAL) return true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            clearGridTouchState(rv);
        }
        return false;
    }

    private boolean handleGridScrollTouch(ViewGroup rv, MotionEvent event) {
        Object axisObject = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_AXIS);
        int axis = axisObject instanceof Number ? ((Number) axisObject).intValue() : GRID_AXIS_UNDECIDED;
        if (axis != GRID_AXIS_VERTICAL && axis != GRID_AXIS_HORIZONTAL) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            if (axis == GRID_AXIS_VERTICAL) {
                Object last = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_LAST_Y);
                if (last instanceof Number) {
                    gridScrollY = clamp(gridScrollY + event.getRawY() - ((Number) last).floatValue(),
                            -gridMaxScroll, gridMaxScroll);
                }
                XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_LAST_Y, event.getRawY());
                refreshGrid((View) rv);
            } else {
                Object target = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_TARGET);
                Object last = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_LAST_X);
                if (target instanceof View && last instanceof Number) {
                    View card = (View) target;
                    card.setTranslationX(card.getTranslationX()
                            + event.getRawX() - ((Number) last).floatValue());
                }
                XposedHelpers.setAdditionalInstanceField(rv, GRID_TOUCH_LAST_X, event.getRawX());
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float velocityY = 0f;
            VelocityTracker tracker = (VelocityTracker) XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY);
            if (action == MotionEvent.ACTION_UP && tracker != null) {
                tracker.computeCurrentVelocity(1000, 8000f);
                velocityY = axis == GRID_AXIS_VERTICAL ? tracker.getYVelocity() : tracker.getXVelocity();
            }
            Object target = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_TARGET);
            Object startX = XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_START_X);
            boolean wasHorizontal = axis == GRID_AXIS_HORIZONTAL;
            float distanceX = startX instanceof Number
                    ? event.getRawX() - ((Number) startX).floatValue() : 0f;
            if (wasHorizontal && action == MotionEvent.ACTION_UP && target instanceof View) {
                View card = (View) target;
                int slop = Math.max(TOUCH_SLOP, ViewConfiguration.get(rv.getContext()).getScaledTouchSlop());
                float threshold = Math.max(slop * 3f,
                        card.getWidth() * KILL_DISTANCE_RATIO * Math.max(0.5f, card.getScaleX()));
                boolean dismiss = Math.abs(distanceX) >= threshold
                        || (Math.abs(velocityY) >= KILL_FLING_VELOCITY
                        && Math.signum(velocityY) == Math.signum(distanceX));
                if (dismiss) dismissGridTask(card);
                else refreshGrid((View) rv);
            }
            clearGridTouchState(rv);
            if (axis == GRID_AXIS_VERTICAL) refreshGrid((View) rv);
            if (action == MotionEvent.ACTION_UP && axis == GRID_AXIS_VERTICAL && Math.abs(velocityY) > 250f) {
                startGridFling(rv, velocityY);
            }
            return true;
        }
        return true;
    }

    private void clearGridTouchState(ViewGroup rv) {
        VelocityTracker tracker = (VelocityTracker) XposedHelpers.getAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY);
        if (tracker != null) tracker.recycle();
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_VELOCITY);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_AXIS);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_START_X);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_START_Y);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_LAST_X);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_LAST_Y);
        XposedHelpers.removeAdditionalInstanceField(rv, GRID_TOUCH_TARGET);
    }

    private View findGridCardAt(ViewGroup rv, float rawX, float rawY) {
        View hit = null;
        float highestZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child == null || !taskViewClass.isInstance(child) || child.getVisibility() != View.VISIBLE) continue;
            // GlobalVisibleRect applique la matrice complete (translation, scale,
            // clip parent et insets), contrairement aux bornes layout brutes.
            Rect bounds = new Rect();
            if (child.getGlobalVisibleRect(bounds)
                    && rawX >= bounds.left && rawX <= bounds.right
                    && rawY >= bounds.top && rawY <= bounds.bottom
                    && child.getTranslationZ() >= highestZ) {
                hit = child;
                highestZ = child.getTranslationZ();
            }
        }
        return hit;
    }

    private void hookDismissController() {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.launcher3.uioverrides.touchcontrollers.TaskViewDismissTouchController",
                classLoader);
        if (c == null) return;
        Method m = findMethod(c, "onControllerInterceptTouchEvent", MotionEvent.class);
        if (m == null) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (mode == MODE_GRID) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });
        XposedBridge.log(TAG + ": hook TaskViewDismissTouchController");
    }

    private void hookGridChildRemoval() {
        Method m = findMethod(ViewGroup.class, "onViewRemoved", View.class);
        if (m == null) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (mode != MODE_GRID || !(param.thisObject instanceof View)) return;
                if (recentsViewClass != null && !recentsViewClass.isInstance(param.thisObject)) return;
                final View rv = (View) param.thisObject;
                rv.postOnAnimation(() -> refreshGrid(rv));
            }
        });
        XposedBridge.log(TAG + ": hook RecentsView.onViewRemoved pour reflow grille");
    }

    /**
     * Le launcher ne rattache pas toutes les TaskView d'un coup : les cartes hors axe sont
     * ajoutees apres la derniere passe de updateCurveProperties (retour sur Recents ou vue
     * recyclee). Sans ce hook, les cartes ajoutees ensuite gardent leur position de
     * carousel et la grille ne montre qu'une carte. On replace la grille des qu'un enfant
     * arrive, puis une seconde fois apres le layout.
     */
    private void hookGridChildAddition() {
        Method m = findMethod(ViewGroup.class, "onViewAdded", View.class);
        if (m == null) {
            XposedBridge.log(TAG + ": onViewAdded introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (mode != MODE_GRID || !(param.thisObject instanceof View)) return;
                if (recentsViewClass != null && !recentsViewClass.isInstance(param.thisObject)) return;
                final ViewGroup rv = (ViewGroup) param.thisObject;
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                    forgetGridCardState((View) param.args[0]);
                }
                rv.postOnAnimation(() -> refreshGrid(rv));
                rv.postDelayed(() -> refreshGrid(rv), 400L);
            }
        });
        XposedBridge.log(TAG + ": hook RecentsView.onViewAdded pour les cartes tardives");
    }

    /**
     * Filet de securite : la grille est posee dans updateCurveProperties, appele par le
     * launcher pendant les transitions. Quand la vue se stabilise sans nouvelle passe, les
     * cartes ajoutees ou rattachees depuis la derniere passe restent a leur position de
     * carousel. On verifie donc au dessin, a faible cadence, que la grille est a jour.
     *
     * Le launcher reecrit ses propres canaux de grille a chaque passe de layout (retour sur
     * Recents depuis l'accueil, changement d'orientation) : gridProgress repasse a 0 et les
     * translations redeviennent celles du carousel. Toutes les cartes se superposent alors
     * au centre et une seule reste visible. La verification porte donc aussi sur ces canaux,
     * et la reprise est cadencee plus court quand ils sont pales.
     */
    private void hookGridDrawRefresh() {
        Method m = findMethod(recentsViewClass, "dispatchDraw", Canvas.class);
        if (m == null) {
            XposedBridge.log(TAG + ": dispatchDraw introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (mode != MODE_GRID || !(param.thisObject instanceof ViewGroup)) return;
                ViewGroup rv = (ViewGroup) param.thisObject;
                long now = android.os.SystemClock.uptimeMillis();
                int count = rv.getChildCount();
                boolean stale = gridChannelsOverwritten(rv);
                long interval = stale ? GRID_HEAL_FAST_MS : 500L;
                if (rv == gridHealView && count == gridHealChildCount && now - gridHealUptime < interval) {
                    return;
                }
                gridHealView = rv;
                gridHealChildCount = count;
                gridHealUptime = now;
                refreshGrid((View) rv);
            }
        });
        XposedBridge.log(TAG + ": hook RecentsView.dispatchDraw (grille auto-corrigee)");
    }

    /**
     * Vrai des qu'une TaskView rachetee par le launcher n'a plus les canaux de grille du
     * module. C'est le signal « le layout du launcher a ecrase notre pose ».
     */
    private boolean gridChannelsOverwritten(ViewGroup rv) {
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child == null || !taskViewClass.isInstance(child)) continue;
            Float progress = gridProgressOf(child);
            if (progress == null || progress.floatValue() < 0.5f) return true;
            Object placed = XposedHelpers.getAdditionalInstanceField(child,
                    "launcher3mod_grid_place_y");
            if (!(placed instanceof Float)) return true;
            if (getGridTranslationYMethod == null) continue;
            try {
                float current = ((Number) getGridTranslationYMethod.invoke(child)).floatValue();
                if (Math.abs(current - ((Float) placed).floatValue()) > 1f) return true;
            } catch (Throwable ignored) {
                return true;
            }
        }
        return false;
    }

    /**
     * Progression de grille reellement portee par la TaskView. Le champ du launcher fait
     * autorite ; la valeur memorisee par le module ne sert que de repli si le champ manque.
     */
    private Float gridProgressOf(View taskView) {
        if (gridProgressField != null) {
            try {
                return gridProgressField.getFloat(taskView);
            } catch (Throwable ignored) {
            }
        }
        Object stored = XposedHelpers.getAdditionalInstanceField(
                taskView, "launcher3mod_grid_progress");
        return stored instanceof Float ? (Float) stored : null;
    }

    /**
     * Retour sur Recents : le RecentsView est rattache, ses TaskViews sont recyclees et
     * rattachees. Les memoires du module (offset, miniature, canaux) decrivent alors les
     * anciennes cartes. On repart d'un etat propre et on replace la grille apres le layout.
     */
    private void hookGridAttachReset() {
        Method m = findMethod(recentsViewClass, "onAttachedToWindow");
        if (m == null) {
            XposedBridge.log(TAG + ": onAttachedToWindow introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                View rv = (View) param.thisObject;
                currentRecentsView = rv;
                clearGridCardState(rv);
                lastGridLoadUptime = 0L;
                if (mode != MODE_GRID) return;
                gridScrollY = Float.NaN;
                snapNextFrame = true;
                final ViewGroup group = (ViewGroup) rv;
                group.postOnAnimation(() -> refreshGrid(group));
                group.postDelayed(() -> refreshGrid(group), 400L);
            }
        });
        XposedBridge.log(TAG + ": hook RecentsView.onAttachedToWindow (grille re-posee)");
    }

    /**
     * Le layout du launcher (onLayout) recalcule gridProgress et les translations de grille
     * de chaque TaskView. Il passe donc apres notre pose et l'annule : une seule carte
     * reste visible. On replace la grille a chaque passe de layout.
     */
    private void hookGridLayoutReset() {
        Method m = findMethod(recentsViewClass, "onLayout", boolean.class, int.class, int.class,
                int.class, int.class);
        if (m == null) {
            XposedBridge.log(TAG + ": onLayout introuvable");
            return;
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (mode != MODE_GRID || !(param.thisObject instanceof ViewGroup)) return;
                ViewGroup rv = (ViewGroup) param.thisObject;
                // Ne replacer que si le launcher vient d'ecraser nos canaux : une pose
                // inutile declencherait un layout de plus, et une boucle de layouts.
                if (!gridChannelsOverwritten(rv)) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastGridRelayoutLog > 2000L) {
                    lastGridRelayoutLog = now;
                    XposedBridge.log(TAG + ": grille re-posee apres layout du launcher");
                }
                refreshGrid(rv);
            }
        });
        XposedBridge.log(TAG + ": hook RecentsView.onLayout (grille reposee chaque layout)");
    }

    // ---- Bouton de selection de vue (dans OverviewActionsView) -----------------

    private void hookOverviewActionsButton() {
        Class<?> overviewActionsClass = XposedHelpers.findClassIfExists(
                "com.android.quickstep.views.OverviewActionsView", classLoader);
        if (overviewActionsClass == null) {
            XposedBridge.log(TAG + ": OverviewActionsView introuvable");
            return;
        }
        Method onFinish = findMethod(overviewActionsClass, "onFinishInflate");
        if (onFinish == null) return;
        XposedBridge.hookMethod(onFinish, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    addModeButton(param.thisObject);
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.log(TAG + ": hook OverviewActionsView");
    }

    private void addModeButton(Object overviewActions) {
        Field f = XposedHelpers.findFieldIfExists(overviewActions.getClass(), "mActionButtons");
        if (f == null) return;
        Object o;
        try {
            f.setAccessible(true);
            o = f.get(overviewActions);
        } catch (Throwable t) {
            return;
        }
        if (!(o instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) o;
        if (bar.findViewWithTag("launcher3mod_mode") != null) return;

        Context context = bar.getContext();
        List<View> actionButtons = new ArrayList<>();
        List<View> hiddenActionButtons = new ArrayList<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child != null && !"launcher3mod_home".equals(child.getTag())) {
                if (child.getVisibility() == View.VISIBLE) actionButtons.add(child);
                else hiddenActionButtons.add(child);
            }
        }
        if (actionButtons.isEmpty()) return;

        Button modeButton = createControlButton(context, "launcher3mod_mode",
                iconForMode(mode), modeName(mode));
        modeButton.setOnClickListener(v -> toggleMode(modeButton));
        Button homeButton = createControlButton(context, "launcher3mod_home", "⌂", "Accueil");
        homeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        homeButton.setOnClickListener(v -> openHome(context));

        // Chaque bouton d'action garde sa colonne : son petit contrôle dédié est placé dessous.
        bar.removeAllViews();
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setTranslationY(0f);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        ViewGroup.LayoutParams barParams = bar.getLayoutParams();
        if (barParams != null) {
            // Le XML du launcher réserve une seule ligne. Etendre le conteneur
            // permet d'afficher la seconde rangée sans la clipper.
            barParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            barParams.height = dp(context, 120);
            bar.setLayoutParams(barParams);
        }
        for (int i = 0; i < actionButtons.size(); i++) {
            final int columnIndex = i;
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(column, columnParams);

            View action = actionButtons.get(i);
            action.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(action);

            if (columnIndex == 0) {
                addControlBelow(column, modeButton, context);
            } else if (columnIndex == 1) {
                addControlBelow(column, homeButton, context);
            }
        }
        // Conserver les actions actuellement masquées par le launcher sans
        // leur laisser une colonne vide dans la rangée visible.
        for (View hidden : hiddenActionButtons) {
            bar.addView(hidden, new LinearLayout.LayoutParams(0, 0));
        }
        // Ancrer les commandes secondaires a 32 dp du bord physique de l'ecran.
        // Le positionnement post-layout utilise la vraie hauteur, y compris la zone
        // systeme en bas, au lieu d'un decalage fixe qui varie selon les appareils.
        modeButton.post(() -> {
            try {
                int[] location = new int[2];
                modeButton.getLocationOnScreen(location);
                DisplayMetrics realMetrics = new DisplayMetrics();
                WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager == null) return;
                windowManager.getDefaultDisplay().getRealMetrics(realMetrics);
                float targetBottom = realMetrics.heightPixels - dp(context, 32);
                float delta = targetBottom - (location[1] + modeButton.getHeight());
                bar.setTranslationY(bar.getTranslationY() + delta);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": positionnement des commandes en bas impossible " + t);
            }
        });
    }

    private Button createControlButton(Context context, String tag, String text, String description) {
        Button button = new Button(context);
        button.setTag(tag);
        button.setText(text);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private void addControlBelow(LinearLayout column, Button button, Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 72), dp(context, 48));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(context, 16);
        column.addView(button, params);
    }

    private void openHome(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": retour accueil impossible " + t);
        }
    }

    private static String iconForMode(int m) {
        switch (m) {
            case MODE_FLIP:
                return "◧";
            case MODE_PILE:
                return "≡";
            case MODE_GRID:
                return "⊞";
            default:
                return "↺";
        }
    }

    private static String modeName(int m) {
        switch (m) {
            case MODE_FLIP:
                return "flip";
            case MODE_PILE:
                return "pile";
            case MODE_GRID:
                return "grille2";
            default:
                return "stock";
        }
    }

    private void toggleMode(Button b) {
        int previousMode = mode;
        mode = (mode + 1) % 4;
        snapNextFrame = true;
        gridScrollY = mode == MODE_GRID ? Float.NaN : 0f;
        b.setText(iconForMode(mode));
        b.setContentDescription(modeName(mode));
        XposedBridge.log(TAG + ": mode = " + modeName(mode));
        if (currentRecentsView != null) {
            if (previousMode == MODE_GRID && mode != MODE_GRID) {
                resetNativeGridTransforms(currentRecentsView);
            }
            if (mode == MODE_GRID) {
                loadGridTaskData(currentRecentsView);
            }
            currentRecentsView.requestLayout();
            if (updateCurvePropertiesMethod != null) {
                try {
                    updateCurvePropertiesMethod.invoke(currentRecentsView);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void resetNativeGridTransforms(View recentsView) {
        if (!(recentsView instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) recentsView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || !taskViewClass.isInstance(child)) continue;
            XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
            XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset_y", 0f);
            XposedHelpers.setAdditionalInstanceField(child, "launcher3mod_grid_offset_y", 0f);
            try {
                XposedHelpers.callMethod(child, "setGridTranslationX", 0f);
                XposedHelpers.callMethod(child, "setGridTranslationY", 0f);
                XposedHelpers.callMethod(child, "setGridProgress", 0f);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": reset des transforms grille impossible " + t);
            }
            forgetGridCardState(child);
        }
    }

    /**
     * Nouvelle instance de RecentsView (retour sur Recents apres l'accueil, relance du
     * launcher) : l'etat de grille memorise ne decrit plus les cartes affichees. On repart
     * d'un etat propre, en bas de grille (taches les plus recentes), et on replace la
     * grille apres le layout.
     */
    private void onRecentsViewChanged(View recentsView) {
        lastGridLoadUptime = 0L;
        clearGridCardState(recentsView);
        if (mode != MODE_GRID) return;
        gridScrollY = Float.NaN;
        snapNextFrame = true;
        if (!(recentsView instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) recentsView;
        group.postOnAnimation(() -> refreshGrid(group));
        group.postDelayed(() -> refreshGrid(group), 400L);
    }

    /** Oublie les decalages et l'etat de miniature d'une TaskView recyclee. */
    private void clearGridCardState(View recentsView) {
        if (!(recentsView instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) recentsView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            forgetGridCardState(child);
        }
    }

    /**
     * Oublie ce que le module a memorise sur une TaskView : decalage, miniature, canaux de
     * grille. Appele quand le launcher recycle ou rattache une carte, sinon la grille
     * repart d'un etat qui decrit l'ancienne carte.
     */
    private void forgetGridCardState(View child) {
        XposedHelpers.setAdditionalInstanceField(child, "launcher3mod_grid_offset_y", 0f);
        XposedHelpers.setAdditionalInstanceField(child, "launcher3mod_grid_thumbnail_reset", null);
        XposedHelpers.setAdditionalInstanceField(child, "launcher3mod_grid_progress", null);
        XposedHelpers.removeAdditionalInstanceField(child, "launcher3mod_grid_place_y");
    }

    /**
     * The launcher normally loads task icons and thumbnails for the carousel's nearby pages.
     * Our custom grid also exposes cards outside that range, so explicitly request their data
     * once when entering the grid. TaskView ignores already-loaded data and completes the loads
     * asynchronously through the launcher's normal caches.
     */
    private void loadGridTaskData(View recentsView) {
        if (!(recentsView instanceof ViewGroup)) return;
        try {
            XposedHelpers.callMethod(recentsView, "loadVisibleTaskData");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": chargement des donnees de cartes impossible " + t);
        }
    }

    /**
     * The running task's thumbnail keeps the matrix computed while it was fullscreen, so its
     * content is drawn at fullscreen scale inside the small grid card. Recompute it from the
     * snapshot view's current size.
     */
    private void refreshGridThumbnailMatrix(View taskView) {
        try {
            java.util.List<?> containers = (java.util.List<?>) XposedHelpers.callMethod(
                    taskView, "getTaskContainers");
            for (Object container : containers) {
                View snapshot = (View) XposedHelpers.callMethod(container, "getSnapshotView");
                if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) continue;
                XposedHelpers.callMethod(taskView, "updateThumbnailMatrix", container,
                        snapshot.getWidth(), snapshot.getHeight());
            }
        } catch (Throwable t) {
            if (!loggedMatrixError) {
                loggedMatrixError = true;
                XposedBridge.log(TAG + ": recalcul matrice miniature impossible " + t);
            }
        }
    }


    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---- Coeur : calcul de l'empilement ---------------------------------------

    private void handleUpdateCurveProperties(XC_MethodHook.MethodHookParam param) {
        if (!(param.thisObject instanceof ViewGroup)) return;
        ViewGroup rv = (ViewGroup) param.thisObject;
        if (getPagedOrientationHandlerMethod == null) return;

        try {
            Object handler = getPagedOrientationHandlerMethod.invoke(rv);
            if (handler == null) return;

            int scroll = ((Number) getPrimaryScrollMethod.invoke(handler, rv)).intValue();
            int measuredSize = ((Number) getMeasuredSizeMethod.invoke(handler, rv)).intValue();
            if (measuredSize <= 0) return;
            float center = scroll + measuredSize / 2f;

            View previousRecentsView = currentRecentsView;
            currentRecentsView = rv;
            if (rv != previousRecentsView) {
                onRecentsViewChanged(rv);
            }
            if (enableLiveTileField != null) {
                try {
                    enableLiveTileField.setBoolean(rv, mode == MODE_STOCK);
                    // A running task has no regular thumbnail while the live tile is active.
                    // In custom layouts the live SurfaceView cannot follow card transforms,
                    // so ask RecentsView to render the running task snapshot instead.
                    try {
                        boolean showRunningSnapshot = mode != MODE_STOCK;
                        Object previous = XposedHelpers.getAdditionalInstanceField(
                                rv, "launcher3mod_running_snapshot");
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        Object lastApply = XposedHelpers.getAdditionalInstanceField(
                                rv, "launcher3mod_running_snapshot_time");
                        boolean stale = lastApply instanceof Long
                                && nowMs - ((Long) lastApply).longValue() > 300L;
                        if (!(previous instanceof Boolean)
                                || ((Boolean) previous).booleanValue() != showRunningSnapshot
                                || (showRunningSnapshot && stale)) {
                            // Launcher3 resets this flag when the gesture ends; keep re-applying it.
                            try {
                                XposedHelpers.callMethod(rv, "setRunningTaskViewShowScreenshot",
                                        showRunningSnapshot);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": DBG showScreenshot " + t);
                            }
                            XposedHelpers.setAdditionalInstanceField(
                                    rv, "launcher3mod_running_snapshot", showRunningSnapshot);
                            XposedHelpers.setAdditionalInstanceField(
                                    rv, "launcher3mod_running_snapshot_time", nowMs);
                            if (showRunningSnapshot) hideLiveSurface(rv);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": DBG outer " + t);
                    }
                } catch (Throwable ignored) {
                }
            }

            int childCount = rv.getChildCount();
            float[] dist = new float[childCount];
            boolean[] visible = new boolean[childCount];

            for (int i = 0; i < childCount; i++) {
                View c = rv.getChildAt(i);
                if (c == null || !taskViewClass.isInstance(c)) continue;
                int start = ((Number) getChildStartMethod.invoke(handler, c)).intValue();
                int size = ((Number) getMeasuredSizeMethod.invoke(handler, c)).intValue();
                dist[i] = start + size / 2f - center;
                // L'alpha du carousel stock masque ses cartes hors axe. En grille,
                // elles doivent toutes participer au placement pour eviter les trous.
                visible[i] = c.getVisibility() == View.VISIBLE
                        && (mode == MODE_GRID || c.getAlpha() > 0.01f);
            }

            long now = android.os.SystemClock.uptimeMillis();
            boolean doLog = now - lastLogUptime > 1200L;
            if (doLog) lastLogUptime = now;
            StringBuilder diag = new StringBuilder();

            if (mode == MODE_STOCK) {
                // Vue d'origine du launcher : on annule seulement nos propres transforms
                // (le framework a deja applique ses scale/alpha de carousel).
                for (int i = 0; i < childCount; i++) {
                    View c = rv.getChildAt(i);
                    if (c == null || !visible[i]) continue;
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset_y", 0f);
                    c.setRotationY(0f);
                    c.setTranslationZ(0f);
                    c.setScaleX(1f);
                    c.setScaleY(1f);
                    c.setAlpha(1f);
                    try {
                        XposedHelpers.callMethod(c, "setContentAlpha", 1f);
                    } catch (Throwable ignored) {
                    }
                    try {
                        XposedHelpers.callMethod(c, "applyTranslationX");
                        XposedHelpers.callMethod(c, "applyTranslationY");
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (mode == MODE_PILE) {
                // Mode "pile" (AOSPStackingRecent) : traction vers le centre.
                float peek = clamp(measuredSize * STACK_PEEK_RATIO, 20f, 80f);
                for (int i = 0; i < childCount; i++) {
                    View c = rv.getChildAt(i);
                    if (c == null || !visible[i]) continue;
                    float d = dist[i];
                    float absD = Math.abs(d);

                    float rank = 0f;
                    for (int j = 0; j < childCount; j++) {
                        if (j == i || !visible[j]) continue;
                        // Rang global = nombre de cartes plus pres du centre.
                        if (Math.abs(dist[j]) < absD - 1f) rank += 1f;
                    }

                    float hill = 1f / (1f + (float) Math.pow(absD / (measuredSize * STACK_HILL_SCALE), STACK_HILL_EXP));
                    float stackTarget = (d < 0f) ? (-rank * peek) : (rank * peek);
                    float target = d * hill + stackTarget * (1f - hill);
                    float extra = target - d;

                    Float curObj = (Float) XposedHelpers.getAdditionalInstanceField(c, "apple_stack_offset");
                    float cur = curObj != null ? curObj : 0f;
                    float smoothed = snapNextFrame ? extra : cur + (extra - cur) * LERP;
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset", smoothed);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset_y", 0f);

                    float progress = Math.min(1f, absD / (measuredSize * 1.2f));
                    float scale = 1f - STACK_SCALE_DROP * progress;
                    float alpha = 1f - STACK_ALPHA_DROP * progress;
                    c.setRotationY(0f);
                    c.setScaleX(scale);
                    c.setScaleY(scale);
                    c.setAlpha(alpha);
                    float z = 100f - rank;
                    c.setTranslationZ(z);

                    XposedHelpers.setAdditionalInstanceField(c, "apple_rot_y", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_scale", scale);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_alpha", alpha);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_z", z);

                    try {
                        XposedHelpers.callMethod(c, "applyTranslationX");
                    } catch (Throwable ignored) {
                    }

                    if (doLog) {
                        diag.append(" [i=").append(i)
                                .append(" d=").append(Math.round(d))
                                .append(" r=").append(Math.round(rank))
                                .append(" ex=").append(Math.round(smoothed))
                                .append(" s=").append(scale)
                                .append(" a=").append(alpha)
                                .append("]");
                    }
                }
            snapNextFrame = false;
            } else if (mode == MODE_GRID) {
                // Mode grille 2 colonnes : cartes classees de gauche a droite, placees
                // sur 2 colonnes et N lignes, echelle reduite.
                // Sans hauteur, le centre de la grille serait calcule hors ecran et
                // toutes les cartes partiraient au-dessus de la vue.
                if (rv.getHeight() <= 0) {
                    snapNextFrame = false;
                    return;
                }
                int[] order = new int[childCount];
                int n = 0;
                for (int i = 0; i < childCount; i++) {
                    if (visible[i]) order[n++] = i;
                }
                for (int a = 0; a < n - 1; a++) {
                    for (int b = a + 1; b < n; b++) {
                        if (dist[order[b]] < dist[order[a]]) {
                            int tmp = order[a];
                            order[a] = order[b];
                            order[b] = tmp;
                        }
                    }
                }
                int columns = 2;
                if (now - lastGridLoadUptime > 400L) {
                    lastGridLoadUptime = now;
                    loadGridTaskData(rv);
                    for (int i = 0; i < childCount; i++) {
                        View c = rv.getChildAt(i);
                        if (c != null && taskViewClass.isInstance(c)) refreshGridThumbnailMatrix(c);
                    }
                }
                int rows = (n + columns - 1) / columns;
                float gap = measuredSize * GRID_GAP_RATIO;

                float refW = measuredSize;
                float refH = measuredSize;
                if (n > 0) {
                    View ref = rv.getChildAt(order[0]);
                    if (ref != null) {
                        int w = ref.getWidth();
                        int h = ref.getHeight();
                        if (w > 0) refW = w;
                        if (h > 0) refH = h;
                    }
                }
                float availW = measuredSize * (1f - GRID_MARGIN_RATIO * 2f);
                // Utilise la vraie hauteur visible du conteneur. Le ratio base sur la
                // largeur coupait le scroll sur les grands ecrans et en paysage.
                float bottomControls = 220f * rv.getResources().getDisplayMetrics().density;
                float viewportTop = rv.getPaddingTop();
                float viewportBottom = Math.max(viewportTop + 1f,
                        rv.getHeight() - rv.getPaddingBottom() - bottomControls);
                float availH = viewportBottom - viewportTop;
                float scaleW = (availW - (columns - 1) * gap) / (columns * refW);
                float gridScale = Math.min(scaleW, GRID_MAX_SCALE);
                float cardW = refW * gridScale;
                float cardH = refH * gridScale;
                float contentHeight = rows * (cardH + gap) - gap;
                float maxScroll = Math.max(0f, (contentHeight - availH) / 2f);
                gridMaxScroll = maxScroll;
                float gridCenterY = viewportTop + availH / 2f;
                if (Float.isNaN(gridScrollY)) gridScrollY = maxScroll;
                else gridScrollY = clamp(gridScrollY, -maxScroll, maxScroll);


                for (int k = 0; k < n; k++) {
                    int i = order[k];
                    View c = rv.getChildAt(i);
                    if (c == null) continue;
                    // Pas encore mesure : getChildStart et getTranslationY sont faux, et
                    // un placement base sur ces valeurs envoie la carte hors de la vue.
                    if (c.getWidth() <= 0 || c.getHeight() <= 0) continue;
                    // The grid changes the TaskView's outer transform without going through
                    // Launcher3's grid transition. Recompute the thumbnail's own preview/inset
                    // matrix once so it does not retain fullscreen/carousel positioning from
                    // the previous layout.
                    //
                    // Ces canaux sont notre seule pose : le launcher les reecrit a chaque
                    // layout (gridProgress = 0, translations du carousel). Les reaffirmer a
                    // chaque passe est ce qui rend la grille stable apres un aller-retour
                    // sur l'accueil.
                    XposedHelpers.setAdditionalInstanceField(c, "launcher3mod_grid_progress", 1f);
                    try {
                        // Use TaskView's own grid transform channels. Launcher3 combines
                        // these with dismiss, split-select and thumbnail state; adding a
                        // raw View translation after applyTranslationX/Y can separate the
                        // task preview from its card on edge/recycled tasks.
                        XposedHelpers.callMethod(c, "setGridProgress", 1f);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": canal de grille impossible " + t);
                    }
                    if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                            c, "launcher3mod_grid_thumbnail_reset"))) {
                        try {
                            XposedHelpers.callMethod(c, "setFullscreenProgress", 0f);
                            XposedHelpers.setAdditionalInstanceField(
                                    c, "launcher3mod_grid_thumbnail_reset", Boolean.TRUE);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": recalcul de la miniature grille impossible " + t);
                        }
                    }
                    int col = k % columns;
                    int row = k / columns;
                    float targetX = (col - (columns - 1) / 2f) * (cardW + gap);
                    float rowOffsetY = (row - (rows - 1) / 2f) * (cardH + gap) + gridScrollY;
                    // Le centre visuel est la position de layout + la translation deja
                    // appliquee par Launcher3, canal de grille du module exclu. On relit ce
                    // canal au lieu de memoriser le notre : le launcher le remet a zero quand
                    // il recycle ou rattache une carte.
                    float appliedGridY = 0f;
                    if (getGridTranslationYMethod != null) {
                        try {
                            appliedGridY = ((Number) getGridTranslationYMethod.invoke(c)).floatValue();
                        } catch (Throwable ignored) {
                        }
                    }
                    float baseCenterY = c.getTop() + c.getHeight() / 2f
                            + (c.getTranslationY() - appliedGridY);
                    float targetY = gridCenterY + rowOffsetY - baseCenterY;

                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset_y", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "launcher3mod_grid_offset_y", targetY);

                    c.setRotationY(0f);
                    c.setScaleX(gridScale);
                    c.setScaleY(gridScale);
                    c.setAlpha(1f);
                    try {
                        XposedHelpers.callMethod(c, "setContentAlpha", 1f);
                    } catch (Throwable ignored) {
                    }
                    float z = 100f - k;
                    c.setTranslationZ(z);

                    XposedHelpers.setAdditionalInstanceField(c, "apple_rot_y", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_scale", gridScale);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_alpha", 1f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_z", z);

                    try {
                        XposedHelpers.callMethod(c, "setGridTranslationX", targetX - dist[i]);
                        XposedHelpers.callMethod(c, "setGridTranslationY", targetY);
                        XposedHelpers.callMethod(c, "applyTranslationX");
                        XposedHelpers.callMethod(c, "applyTranslationY");
                        XposedHelpers.setAdditionalInstanceField(c, "launcher3mod_grid_place_y", targetY);
                    } catch (Throwable ignored) {
                    }

                    installGridTouchListener(c, rv);

                    if (doLog) {
                        diag.append(" [i=").append(i)
                                .append(" k=").append(k)
                                .append(" col=").append(col)
                                .append(" row=").append(row)
                                .append(" tx=").append(Math.round(targetX))
                                .append(" ty=").append(Math.round(targetY))
                                .append(" sc=").append(gridScale)
                                .append("]");
                    }
                }
                snapNextFrame = false;
            } else {
                // Mode "flip" : cover-flow 3D (rotationY + perspective).
                float normRange = measuredSize * NORM_RANGE;
                for (int i = 0; i < childCount; i++) {
                    View c = rv.getChildAt(i);
                    if (c == null || !visible[i]) continue;
                    float d = dist[i];

                    float norm = clamp(d / normRange, -1f, 1f);
                    float absN = Math.abs(norm);

                    float rotationY = -norm * MAX_ROTATION;
                    float scale = BASE_SCALE - SCALE_DROP * absN;
                    float alpha = 1f - ALPHA_DROP * absN;
                    float z = -absN * Z_DROP;

                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset", 0f);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_stack_offset_y", 0f);

                    c.setRotationY(rotationY);
                    c.setCameraDistance(normRange * 2f * CAMERA_FACTOR);
                    c.setScaleX(scale);
                    c.setScaleY(scale);
                    c.setAlpha(alpha);
                    c.setTranslationZ(z);

                    XposedHelpers.setAdditionalInstanceField(c, "apple_rot_y", rotationY);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_scale", scale);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_alpha", alpha);
                    XposedHelpers.setAdditionalInstanceField(c, "apple_z", z);

                    if (doLog) {
                        diag.append(" [i=").append(i)
                                .append(" d=").append(Math.round(d))
                                .append(" n=").append(norm)
                                .append(" ry=").append(Math.round(rotationY))
                                .append(" s=").append(scale)
                                .append(" a=").append(alpha)
                                .append("]");
                    }
                }
            }

            if (doLog && diag.length() > 0) {
                XposedBridge.log(TAG + ": " + diag);
            }
        } catch (Throwable t) {
            if (!loggedOnce) {
                loggedOnce = true;
                XposedBridge.log(TAG + ": erreur frame " + t);
            }
        }
    }

    private void syncPageSpacing(Object recentsView) {
        if (pageSpacingField == null || pageSpacingField.getType() != int.class) return;
        try {
            pageSpacingField.setAccessible(true);
            Object baseObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_base_spacing");
            int base;
            if (baseObj instanceof Number) {
                base = ((Number) baseObj).intValue();
            } else {
                base = pageSpacingField.getInt(recentsView);
                XposedHelpers.setAdditionalInstanceField(recentsView, "apple_base_spacing", base);
            }
            int target = Math.max(1, Math.round(base * PAGE_SPACING_RATIO));
            if (pageSpacingField.getInt(recentsView) != target) {
                pageSpacingField.setInt(recentsView, target);
                if (pageScrollsField != null) {
                    pageScrollsField.setAccessible(true);
                    pageScrollsField.set(recentsView, null);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Tactile grille : scroll vertical + swipe horizontal pour fermer --------

    private void installGridTouchListener(View card, final ViewGroup rv) {
        if (XposedHelpers.getAdditionalInstanceField(card, "launcher3mod_grid_touch") != null) return;
        XposedHelpers.setAdditionalInstanceField(card, "launcher3mod_grid_touch", Boolean.TRUE);
        // Ne pas laisser le long press du TaskView activer la selection du launcher.
        disableLongPress(card);
        final float[] startX = {0f};
        final float[] startY = {0f};
        final float[] lastX = {0f};
        final float[] lastY = {0f};
        final boolean[] dragging = {false};
        final boolean[] scrolling = {false};
        final VelocityTracker[] velocity = {null};
        final long[] downTime = {0L};
        final long longPressTimeout = ViewConfiguration.getLongPressTimeout();
        final int touchSlop = Math.max(TOUCH_SLOP,
                ViewConfiguration.get(card.getContext()).getScaledTouchSlop());
        card.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (gridScroller != null) gridScroller.forceFinished(true);
                    velocity[0] = VelocityTracker.obtain();
                    velocity[0].addMovement(e);
                    startX[0] = e.getRawX();
                    startY[0] = e.getRawY();
                    lastX[0] = e.getRawX();
                    lastY[0] = e.getRawY();
                    downTime[0] = android.os.SystemClock.uptimeMillis();
                    dragging[0] = false;
                    scrolling[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (velocity[0] != null) velocity[0].addMovement(e);
                    float dx = e.getRawX() - startX[0];
                    float dy = e.getRawY() - startY[0];
                    if (!dragging[0] && !scrolling[0]) {
                        float absX = Math.abs(dx);
                        float absY = Math.abs(dy);
                        float travel = (float) Math.hypot(absX, absY);
                        if (travel >= touchSlop) {
                            // Attendre que l'axe soit clair. Le scroll vertical
                            // tolere un peu de derive horizontale; un swipe de
                            // fermeture doit etre franchement horizontal.
                            if (absY >= touchSlop * 0.75f && absY >= absX * 1.10f) {
                                scrolling[0] = true;
                            } else if (absX >= absY * 1.45f) {
                                dragging[0] = true;
                            } else if (travel >= touchSlop * 2f) {
                                if (absY >= absX) scrolling[0] = true;
                                else dragging[0] = true;
                            }
                        }
                    }
                    if (scrolling[0]) {
                        float delta = e.getRawY() - lastY[0];
                        lastY[0] = e.getRawY();
                        float before = gridScrollY;
                        gridScrollY = clamp(before + delta, -gridMaxScroll, gridMaxScroll);

                        if (updateCurvePropertiesMethod != null && currentRecentsView != null) {
                            try {
                                updateCurvePropertiesMethod.invoke(currentRecentsView);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": invoke updateCurveProperties failed " + t);
                            }
                        }
                        return true;
                    }
                    if (dragging[0]) {
                        v.setTranslationX(v.getTranslationX() + (e.getRawX() - lastX[0]));
                        lastX[0] = e.getRawX();
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (velocity[0] != null) velocity[0].addMovement(e);
                    if (dragging[0] || scrolling[0]) {
                        boolean wasScrolling = scrolling[0];
                        boolean wasDragging = dragging[0];
                        float scrollVelocity = 0f;
                        float swipeVelocity = 0f;
                        if (velocity[0] != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            velocity[0].computeCurrentVelocity(1000, 8000f);
                            if (wasScrolling) scrollVelocity = velocity[0].getYVelocity();
                            if (wasDragging) swipeVelocity = velocity[0].getXVelocity();
                        }
                        if (velocity[0] != null) {
                            velocity[0].recycle();
                            velocity[0] = null;
                        }
                        if (currentRecentsView != null && updateCurvePropertiesMethod != null) {
                            try {
                                updateCurvePropertiesMethod.invoke(currentRecentsView);
                            } catch (Throwable ignored) {
                            }
                        }
                        dragging[0] = false;
                        scrolling[0] = false;
                        if (wasDragging && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            float distance = e.getRawX() - startX[0];
                            float minDistance = Math.max(touchSlop * 3f,
                                    v.getWidth() * KILL_DISTANCE_RATIO * Math.max(0.5f, v.getScaleX()));
                            boolean dismiss = Math.abs(distance) >= minDistance
                                    || (Math.abs(swipeVelocity) >= KILL_FLING_VELOCITY
                                    && Math.signum(swipeVelocity) == Math.signum(distance));
                            if (dismiss) dismissGridTask(v);
                            else refreshGrid(rv);
                        }
                        if (wasScrolling && Math.abs(scrollVelocity) > 250f) {
                            startGridFling(rv, scrollVelocity);
                        }
                        return true;
                    }
                    if (velocity[0] != null) {
                        velocity[0].recycle();
                        velocity[0] = null;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        float tapDx = e.getRawX() - startX[0];
                        float tapDy = e.getRawY() - startY[0];
                        long pressDuration = android.os.SystemClock.uptimeMillis() - downTime[0];
                        if (Math.hypot(tapDx, tapDy) < touchSlop
                                && pressDuration < longPressTimeout) {
                            try {
                                v.performClick();
                            } catch (Throwable ignored) {
                            }
                        } else {
                            // Geste diagonal trop ambigu : ne pas lancer l'app.
                            refreshGrid(rv);
                        }
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void dismissGridTask(View card) {
        try {
            int taskId = taskIdOf(card);
            if (taskId >= 0 && dismissTaskMethod != null && currentRecentsView != null) {
                dismissTaskMethod.invoke(currentRecentsView, taskId, true);
            } else {
                XposedBridge.log(TAG + ": dismiss grid skipped, invalid task or method");
                refreshGrid((View) currentRecentsView);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dismiss grid task failed " + t);
            refreshGrid((View) currentRecentsView);
        }
    }

    private int taskIdOf(View card) {
        try {
            int[] ids = (int[]) XposedHelpers.callMethod(card, "getTaskIds");
            return ids != null && ids.length > 0 ? ids[0] : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void refreshGrid(View rv) {
        if (rv == null || updateCurvePropertiesMethod == null) return;
        try {
            updateCurvePropertiesMethod.invoke(rv);
        } catch (Throwable ignored) {
        }
    }

    /** Continue le scroll vertical avec une deceleration native, comme une grille MIUI. */
    private void startGridFling(final ViewGroup rv, float velocityY) {
        if (gridMaxScroll <= 0f || rv == null) return;
        if (gridScroller == null) gridScroller = new OverScroller(rv.getContext());
        gridScroller.forceFinished(true);
        int start = Math.round(gridScrollY);
        gridScroller.fling(0, start, 0, Math.round(velocityY), 0, 0,
                Math.round(-gridMaxScroll), Math.round(gridMaxScroll));
        rv.removeCallbacks(gridFlingFrame);
        rv.postOnAnimation(gridFlingFrame);
    }

    private final Runnable gridFlingFrame = new Runnable() {
        @Override
        public void run() {
            View rv = currentRecentsView;
            if (rv == null || gridScroller == null || mode != MODE_GRID) return;
            if (gridScroller.computeScrollOffset()) {
                gridScrollY = clamp(gridScroller.getCurrY(), -gridMaxScroll, gridMaxScroll);
                if (updateCurvePropertiesMethod != null) {
                    try {
                        updateCurvePropertiesMethod.invoke(rv);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": grille scroll frame failed " + t);
                    }
                }
                rv.postOnAnimation(this);
            }
        }
    };

    private void disableLongPress(View v) {
        try {
            v.setLongClickable(false);
            if (v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextIsSelectable(false);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    if (child != null) disableLongPress(child);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Utilitaires -----------------------------------------------------------

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            Field f = XposedHelpers.findFieldIfExists(clazz, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Method m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        if (m != null) m.setAccessible(true);
        return m;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
