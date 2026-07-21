package br.com.droidboaoferta;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

final class NavigationAnimationController {
    static final String MODE_FADE = "smooth";
    static final String MODE_SLIDE = "slide";
    static final String MODE_ZOOM = "zoom";
    static final String MODE_RISE = "rise";
    static final String MODE_DROP = "drop";
    static final String MODE_ZOOM_OUT = "zoom_out";
    static final String MODE_TURN = "turn";
    static final String MODE_BOUNCE = "bounce";
    static final String MODE_GLIDE_ZOOM = "glide_zoom";
    static final String MODE_NONE = "none";

    private static final String PREFS = "app_preferences";
    private static final String NAVIGATION_ANIMATION = "navigation_animation";
    private static Class<? extends Activity> pendingTarget;
    private static boolean pendingMovingRight;

    private NavigationAnimationController() {
    }

    static String getSavedMode(Context context) {
        return prefs(context).getString(NAVIGATION_ANIMATION, MODE_FADE);
    }

    static void saveMode(Context context, String mode) {
        prefs(context).edit().putString(NAVIGATION_ANIMATION, mode).apply();
    }

    static int getSummaryResource(String mode) {
        switch (mode) {
            case MODE_SLIDE:
                return R.string.navigation_animation_slide;
            case MODE_ZOOM:
                return R.string.navigation_animation_zoom;
            case MODE_RISE:
                return R.string.navigation_animation_rise;
            case MODE_DROP:
                return R.string.navigation_animation_drop;
            case MODE_ZOOM_OUT:
                return R.string.navigation_animation_zoom_out;
            case MODE_TURN:
                return R.string.navigation_animation_turn;
            case MODE_BOUNCE:
                return R.string.navigation_animation_bounce;
            case MODE_GLIDE_ZOOM:
                return R.string.navigation_animation_glide_zoom;
            case MODE_NONE:
                return R.string.navigation_animation_none;
            case MODE_FADE:
            default:
                return R.string.navigation_animation_fade;
        }
    }

    static void bind(Activity activity, int... contentViewIds) {
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver.OnWindowFocusChangeListener listener = hasFocus -> {
            if (!hasFocus) {
                return;
            }
            Boolean movingRight = consumePendingTransition(activity);
            if (movingRight == null) {
                return;
            }
            decor.post(() -> animateContent(activity, contentViewIds, movingRight));
        };
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(listener);
    }

    static void prepare(Class<? extends Activity> targetActivity, boolean movingRight) {
        pendingTarget = targetActivity;
        pendingMovingRight = movingRight;
    }

    @SuppressWarnings("deprecation")
    static void suppressWindowTransition(Activity activity) {
        activity.overridePendingTransition(0, 0);
    }

    private static Boolean consumePendingTransition(Activity activity) {
        if (pendingTarget == null || pendingTarget != activity.getClass()) {
            return null;
        }
        pendingTarget = null;
        return pendingMovingRight;
    }

    private static void animateContent(Activity activity, int[] contentViewIds,
                                       boolean movingRight) {
        List<View> contentViews = new ArrayList<>();
        for (int contentViewId : contentViewIds) {
            View content = activity.findViewById(contentViewId);
            if (content != null && content.getVisibility() == View.VISIBLE) {
                contentViews.add(content);
            }
        }
        if (contentViews.isEmpty()) {
            return;
        }
        String mode = getSavedMode(activity);
        for (View content : contentViews) {
            reset(content);
        }
        switch (mode) {
            case MODE_SLIDE:
                float distance = activity.getResources().getDisplayMetrics().widthPixels * 0.18f;
                for (View content : contentViews) {
                    content.setAlpha(0.72f);
                    content.setTranslationX(movingRight ? distance : -distance);
                    content.animate().alpha(1f).translationX(0f).setDuration(210L).start();
                }
                break;
            case MODE_ZOOM:
                for (View content : contentViews) {
                    content.setAlpha(0.55f);
                    content.setScaleX(0.94f);
                    content.setScaleY(0.94f);
                    content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L).start();
                }
                break;
            case MODE_RISE:
                float riseDistance = 56f * activity.getResources().getDisplayMetrics().density;
                for (View content : contentViews) {
                    content.setAlpha(0.65f);
                    content.setTranslationY(riseDistance);
                    content.animate().alpha(1f).translationY(0f).setDuration(220L).start();
                }
                break;
            case MODE_DROP:
                float dropDistance = 56f * activity.getResources().getDisplayMetrics().density;
                for (View content : contentViews) {
                    content.setAlpha(0.65f);
                    content.setTranslationY(-dropDistance);
                    content.animate().alpha(1f).translationY(0f).setDuration(220L).start();
                }
                break;
            case MODE_ZOOM_OUT:
                for (View content : contentViews) {
                    content.setAlpha(0.5f);
                    content.setScaleX(1.09f);
                    content.setScaleY(1.09f);
                    content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L).start();
                }
                break;
            case MODE_TURN:
                for (View content : contentViews) {
                    content.setCameraDistance(8000f * activity.getResources().getDisplayMetrics().density);
                    content.setPivotX(content.getWidth() / 2f);
                    content.setPivotY(content.getHeight() / 2f);
                    content.setAlpha(0.45f);
                    content.setRotationY(movingRight ? 18f : -18f);
                    content.animate().alpha(1f).rotationY(0f).setDuration(240L).start();
                }
                break;
            case MODE_BOUNCE:
                for (View content : contentViews) {
                    content.setAlpha(0.7f);
                    content.setScaleX(0.84f);
                    content.setScaleY(0.84f);
                    content.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setInterpolator(new OvershootInterpolator(1.35f))
                            .setDuration(320L)
                            .start();
                }
                break;
            case MODE_GLIDE_ZOOM:
                float glideDistance = activity.getResources().getDisplayMetrics().widthPixels * 0.12f;
                for (View content : contentViews) {
                    content.setAlpha(0.55f);
                    content.setTranslationX(movingRight ? glideDistance : -glideDistance);
                    content.setScaleX(0.94f);
                    content.setScaleY(0.94f);
                    content.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(240L)
                            .start();
                }
                break;
            case MODE_NONE:
                break;
            case MODE_FADE:
            default:
                for (View content : contentViews) {
                    content.setAlpha(0f);
                    content.animate().alpha(1f).setDuration(180L).start();
                }
                break;
        }
    }

    private static void reset(View view) {
        view.animate().cancel();
        view.animate().setInterpolator(new DecelerateInterpolator());
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotationY(0f);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
