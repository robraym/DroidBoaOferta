package br.com.droidboaoferta;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

final class BottomNavigationController {
    static final int ITEM_NONE = 0;
    static final int ITEM_HOME = 1;
    static final int ITEM_SOURCES = 2;
    static final int ITEM_ALERTS = 3;
    static final int ITEM_ARCHIVED = 4;
    static final int ITEM_TRASH = 5;

    private BottomNavigationController() {
    }

    static void setup(Activity activity, int currentItem, int... animatedContentViewIds) {
        setupItem(activity, currentItem, ITEM_HOME, R.id.nav_home,
                R.id.nav_home_indicator, R.id.nav_home_icon, R.id.nav_home_label,
                MainActivity.class);
        setupItem(activity, currentItem, ITEM_SOURCES, R.id.nav_sources,
                R.id.nav_sources_indicator, R.id.nav_sources_icon, R.id.nav_sources_label,
                TelegramSetupActivity.class);
        setupItem(activity, currentItem, ITEM_ALERTS, R.id.nav_alerts,
                R.id.nav_alerts_indicator, R.id.nav_alerts_icon, R.id.nav_alerts_label,
                AlertsActivity.class);
        setupItem(activity, currentItem, ITEM_ARCHIVED, R.id.nav_archived,
                R.id.nav_archived_indicator, R.id.nav_archived_icon, R.id.nav_archived_label,
                ArchivedOffersActivity.class);
        setupItem(activity, currentItem, ITEM_TRASH, R.id.nav_trash,
                R.id.nav_trash_indicator, R.id.nav_trash_icon, R.id.nav_trash_label,
                TrashedOffersActivity.class);
        hideNavigationWhileKeyboardIsOpen(activity);
        resetInitialFocus(activity);
        NavigationAnimationController.bind(activity, animatedContentViewIds);
    }

    static void resetInitialFocus(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        WindowCompat.getInsetsController(activity.getWindow(), content)
                .hide(WindowInsetsCompat.Type.ime());
    }

    private static void hideNavigationWhileKeyboardIsOpen(Activity activity) {
        View bottomNavigation = activity.findViewById(R.id.bottom_navigation);
        View content = activity.findViewById(android.R.id.content);
        if (bottomNavigation == null || content == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect visibleWindow = new Rect();
                decorView.getWindowVisibleDisplayFrame(visibleWindow);
                int windowHeight = decorView.getRootView().getHeight();
                int coveredHeight = windowHeight - visibleWindow.height();
                boolean keyboardVisible = coveredHeight > windowHeight * 0.15f;
                setNavigationVisible(bottomNavigation, !keyboardVisible);
            });
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            setNavigationVisible(bottomNavigation, !keyboardVisible);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private static void setNavigationVisible(View bottomNavigation, boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (bottomNavigation.getVisibility() != visibility) {
            bottomNavigation.setVisibility(visibility);
        }
    }

    private static void setupItem(Activity activity, int currentItem, int item,
                                  int rootId, int indicatorId, int iconId, int labelId,
                                  Class<? extends Activity> targetActivity) {
        View root = activity.findViewById(rootId);
        if (root == null) {
            return;
        }
        boolean selected = currentItem == item;
        activity.findViewById(indicatorId).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);

        int color = activity.getColor(selected ? R.color.action : R.color.text_secondary);
        ImageView icon = activity.findViewById(iconId);
        icon.setColorFilter(color);

        TextView label = activity.findViewById(labelId);
        label.setTextColor(color);
        label.setSelected(selected);

        root.setSelected(selected);
        root.setOnClickListener(view -> {
            if (selected) {
                return;
            }
            Intent intent = new Intent(activity, targetActivity);
            if (targetActivity == MainActivity.class) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            }
            NavigationAnimationController.prepare(targetActivity, item > currentItem);
            activity.startActivity(intent);
            NavigationAnimationController.suppressWindowTransition(activity);
        });
    }
}
