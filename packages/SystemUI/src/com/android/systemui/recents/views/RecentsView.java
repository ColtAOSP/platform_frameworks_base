/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityOptions.OnAnimationStartedListener;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.os.Handler;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.AppTransitionAnimationSpec;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewDebug;
import android.view.ViewPropertyAnimator;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ImageButton;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideStackActionButtonEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.MultiWindowStateChangedEvent;
import com.android.systemui.recents.events.activity.ShowEmptyViewEvent;
import com.android.systemui.recents.events.activity.ShowStackActionButtonEvent;
import com.android.systemui.recents.events.component.ExpandPipEvent;
import com.android.systemui.recents.events.activity.ToggleRecentsEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndCancelledEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsTransitionHelper.AnimationSpecComposer;
import com.android.systemui.recents.views.RecentsTransitionHelper.AppTransitionAnimationSpecsFuture;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.phone.ScrimController;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout {

    private static final String TAG = "RecentsView";

    private static final int DEFAULT_UPDATE_SCRIM_DURATION = 200;

    private static final int SHOW_STACK_ACTION_BUTTON_DURATION = 134;
    private static final int HIDE_STACK_ACTION_BUTTON_DURATION = 100;

    private static final int BUSY_RECENTS_TASK_COUNT = 3;

    private TaskStackView mTaskStackView;
    private TextView mStackActionButton;
    private TextView mEmptyView;
    private final float mStackButtonShadowRadius;
    private final PointF mStackButtonShadowDistance;
    private final int mStackButtonShadowColor;
    private SettingsObserver mSettingsObserver;
    private boolean showClearAllRecents;
    View mFloatingButton;
    View mClearRecents;
    private int clearRecentsLocation;

    private boolean mAwaitingFirstLayout = true;
    private boolean mLastTaskLaunchedWasFreeform;

    @ViewDebug.ExportedProperty(category="recents")
    Rect mSystemInsets = new Rect();
    private int mDividerSize;

    private float mBusynessFactor;
    private GradientDrawable mBackgroundScrim;
    private ColorDrawable mMultiWindowBackgroundScrim;
    private ValueAnimator mBackgroundScrimAnimator;
    private Point mTmpDisplaySize = new Point();

    private final AnimatorUpdateListener mUpdateBackgroundScrimAlpha = (animation) -> {
        int alpha = (Integer) animation.getAnimatedValue();
        mBackgroundScrim.setAlpha(alpha);
        mMultiWindowBackgroundScrim.setAlpha(alpha);
    };

    private ScaleGestureDetector mRecentListGestureDetector;

    private RecentsTransitionHelper mTransitionHelper;
    @ViewDebug.ExportedProperty(deepExport=true, prefix="touch_")
    private RecentsViewTouchHandler mTouchHandler;
    private final FlingAnimationUtils mFlingAnimationUtils;

    TextView mMemText;
    ProgressBar mMemBar;
    private ActivityManager mAm;
    private int mTotalMem;

    private int mDisplayOrientation = Configuration.ORIENTATION_UNDEFINED;

    public RecentsView(Context context) {
        this(context, null);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);

        SystemServicesProxy ssp = Recents.getSystemServices();
        mTransitionHelper = new RecentsTransitionHelper(getContext());
        mDividerSize = ssp.getDockedDividerSize(context);
        mTouchHandler = new RecentsViewTouchHandler(this);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.3f);
        mBackgroundScrim = new GradientDrawable(context);
        mMultiWindowBackgroundScrim = new ColorDrawable();

        LayoutInflater inflater = LayoutInflater.from(context);
        mEmptyView = (TextView) inflater.inflate(R.layout.recents_empty, this, false);
        addView(mEmptyView);
        mSettingsObserver = new SettingsObserver(new Handler());

        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            if (mStackActionButton != null) {
                removeView(mStackActionButton);
            }
            mStackActionButton = (TextView) inflater.inflate(Recents.getConfiguration()
                            .isLowRamDevice
                        ? R.layout.recents_low_ram_stack_action_button
                        : R.layout.recents_stack_action_button,
                    this, false);

            mStackButtonShadowRadius = mStackActionButton.getShadowRadius();
            mStackButtonShadowDistance = new PointF(mStackActionButton.getShadowDx(),
                    mStackActionButton.getShadowDy());
            mStackButtonShadowColor = mStackActionButton.getShadowColor();
            addView(mStackActionButton);
        }
        mAm = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        reevaluateStyles();
    }

    public void reevaluateStyles() {
        int textColor = Utils.getColorAttr(mContext, R.attr.wallpaperTextColor);
        boolean usingDarkText = Color.luminance(textColor) < 0.5f;

        mEmptyView.setTextColor(textColor);
        mEmptyView.setCompoundDrawableTintList(new ColorStateList(new int[][]{
                {android.R.attr.state_enabled}}, new int[]{textColor}));

        if (mStackActionButton != null) {
            mStackActionButton.setTextColor(textColor);
            // Enable/disable shadow if text color is already dark.
            if (usingDarkText) {
                mStackActionButton.setShadowLayer(0, 0, 0, 0);
            } else {
                mStackActionButton.setShadowLayer(mStackButtonShadowRadius,
                        mStackButtonShadowDistance.x, mStackButtonShadowDistance.y,
                        mStackButtonShadowColor);
            }
        }

        // Let's also require dark status and nav bars if the text is dark
        int systemBarsStyle = usingDarkText ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;

        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                systemBarsStyle);
    }

    /**
     * Called from RecentsActivity when it is relaunched.
     */
    public void onReload(TaskStack stack, boolean isResumingFromVisible) {
        final RecentsConfiguration config = Recents.getConfiguration();
        final RecentsActivityLaunchState launchState = config.getLaunchState();
        final boolean isTaskStackEmpty = stack.getTaskCount() == 0;

        if (mTaskStackView == null) {
            isResumingFromVisible = false;
            mTaskStackView = new TaskStackView(getContext());
            mTaskStackView.setSystemInsets(mSystemInsets);
            addView(mTaskStackView);
        }

        // Reset the state
        mAwaitingFirstLayout = !isResumingFromVisible;
        mLastTaskLaunchedWasFreeform = false;

        // Update the stack
        mTaskStackView.onReload(isResumingFromVisible);
        updateStack(stack, true /* setStackViewTasks */);
        updateBusyness();

        if (isResumingFromVisible) {
            // If we are already visible, then restore the background scrim
            animateBackgroundScrim(getOpaqueScrimAlpha(), DEFAULT_UPDATE_SCRIM_DURATION);
        } else {
            // If we are already occluded by the app, then set the final background scrim alpha now.
            // Otherwise, defer until the enter animation completes to animate the scrim alpha with
            // the tasks for the home animation.
            if (launchState.launchedViaDockGesture || launchState.launchedFromApp
                    || isTaskStackEmpty) {
                mBackgroundScrim.setAlpha((int) (getOpaqueScrimAlpha() * 255));
            } else {
                mBackgroundScrim.setAlpha(0);
            }
            mMultiWindowBackgroundScrim.setAlpha(mBackgroundScrim.getAlpha());
        }
    }

    /**
     * Called from RecentsActivity when the task stack is updated.
     */
    public void updateStack(TaskStack stack, boolean setStackViewTasks) {
        if (setStackViewTasks) {
            mTaskStackView.setTasks(stack, true /* allowNotifyStackChanges */);
        }

        // Update the top level view's visibilities
        if (stack.getTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView(R.string.recents_empty_message);
        }
    }

    /**
     * Animates the scrim opacity based on how many tasks are visible.
     * Called from {@link RecentsActivity} when tasks are dismissed.
     */
    public void updateScrimOpacity() {
        if (updateBusyness()) {
            animateBackgroundScrim(getOpaqueScrimAlpha(), DEFAULT_UPDATE_SCRIM_DURATION);
        }
    }

    /**
     * Updates the busyness factor.
     *
     * @return True if it changed.
     */
    private boolean updateBusyness() {
        final int taskCount = mTaskStackView.getStack().getStackTaskCount();
        final float busyness = Math.min(taskCount, BUSY_RECENTS_TASK_COUNT)
                / (float) BUSY_RECENTS_TASK_COUNT;
        if (mBusynessFactor == busyness) {
            return false;
        } else {
            mBusynessFactor = busyness;
            return true;
        }
    }

    /**
     * Returns the current TaskStack.
     */
    public TaskStack getStack() {
        return mTaskStackView.getStack();
    }

    /**
     * Returns the window background scrim.
     */
    public void updateBackgroundScrim(Window window, boolean isInMultiWindow) {
        if (isInMultiWindow) {
            mBackgroundScrim.setCallback(null);
            window.setBackgroundDrawable(mMultiWindowBackgroundScrim);
        } else {
            mMultiWindowBackgroundScrim.setCallback(null);
            window.setBackgroundDrawable(mBackgroundScrim);
        }
    }

    /**
     * Returns whether the last task launched was in the freeform stack or not.
     */
    public boolean isLastTaskLaunchedFreeform() {
        return mLastTaskLaunchedWasFreeform;
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask(int logEvent) {
        if (mTaskStackView != null) {
            Task task = mTaskStackView.getFocusedTask();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));

                if (logEvent != 0) {
                    MetricsLogger.action(getContext(), logEvent,
                            task.key.getComponent().toString());
                }
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask() {
        if (Recents.getConfiguration().getLaunchState().launchedFromPipApp) {
            // If the app auto-entered PiP on the way to Recents, then just re-expand it
            EventBus.getDefault().send(new ExpandPipEvent());
            return true;
        }

        if (mTaskStackView != null) {
            Task task = getStack().getLaunchTarget();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /** Launches a given task. */
    public boolean launchTask(Task task, Rect taskBounds, int destinationStack) {
        if (mTaskStackView != null) {
            // Iterate the stack views and try and find the given task.
            List<TaskView> taskViews = mTaskStackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                if (tv.getTask() == task) {
                    EventBus.getDefault().send(new LaunchTaskEvent(tv, task, taskBounds,
                            destinationStack, false));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView(int msgResId) {
        mTaskStackView.setVisibility(View.INVISIBLE);
        mEmptyView.setText(msgResId);
        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.bringToFront();
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            mStackActionButton.bringToFront();
        }
        if (mFloatingButton != null) {
            mFloatingButton.setVisibility(View.GONE);
        }
        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                EventBus.getDefault().send(new ToggleRecentsEvent());
                updateMemoryStatus();
            }
        });
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.INVISIBLE);
        mTaskStackView.setVisibility(View.VISIBLE);
        mTaskStackView.bringToFront();
        // Prepare gesture detector.
        mTaskStackView.setOnTouchListener((View v, MotionEvent event) -> {
            mRecentListGestureDetector.onTouchEvent(event);
            return false;
        });
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            mStackActionButton.bringToFront();
        }
        if (mFloatingButton != null) {
            mFloatingButton.setVisibility(View.VISIBLE);
        }
        setOnClickListener(null);
    }

    /**
     * Set the color of the scrim.
     *
     * @param scrimColors Colors to use.
     * @param animated Interpolate colors if true.
     */
    public void setScrimColors(ColorExtractor.GradientColors scrimColors, boolean animated) {
        mBackgroundScrim.setColors(scrimColors, animated);
        int alpha = mMultiWindowBackgroundScrim.getAlpha();
        mMultiWindowBackgroundScrim.setColor(scrimColors.getMainColor());
        mMultiWindowBackgroundScrim.setAlpha(alpha);
    }

    public void startFABanimation() {
        RecentsConfiguration config = Recents.getConfiguration();
        // Animate the action button in
        mFloatingButton = ((View)getParent()).findViewById(R.id.floating_action_button);
        mFloatingButton.animate().alpha(1f)
                .setStartDelay(config.fabEnterAnimDelay)
                .setDuration(config.fabEnterAnimDuration)
                .setInterpolator(Interpolators.ALPHA_IN)
                .withLayer()
                .start();
    }

    public void endFABanimation() {
        RecentsConfiguration config = Recents.getConfiguration();
        // Animate the action button away
        mFloatingButton = ((View)getParent()).findViewById(R.id.floating_action_button);
        mFloatingButton.animate().alpha(0f)
                .setStartDelay(0)
                .setDuration(config.fabExitAnimDuration)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withLayer()
                .start();
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        EventBus.getDefault().register(mTouchHandler, RecentsActivity.EVENT_BUS_PRIORITY + 2);
        mSettingsObserver.observe();
        mClearRecents.setVisibility(View.VISIBLE);
        mClearRecents.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            EventBus.getDefault().send(new DismissAllTaskViewsEvent());
            updateMemoryStatus();
            }
        });
        mMemText = (TextView) ((View)getParent()).findViewById(R.id.recents_memory_text);
        mMemBar = (ProgressBar) ((View)getParent()).findViewById(R.id.recents_memory_bar);

        mRecentListGestureDetector =
                new ScaleGestureDetector(mContext,
                        new PinchInGesture(mTaskStackView));
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
        EventBus.getDefault().unregister(mTouchHandler);
        mSettingsObserver.unobserve();
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final ContentResolver resolver = mContext.getContentResolver();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.measure(widthMeasureSpec, heightMeasureSpec);
        showMemDisplay();
        }

        // Measure the empty view to the full size of the screen
        if (mEmptyView.getVisibility() != GONE) {
            measureChild(mEmptyView, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        }

        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Measure the stack action button within the constraints of the space above the stack
            Rect buttonBounds = mTaskStackView.mLayoutAlgorithm.getStackActionButtonRect();
            measureChild(mStackActionButton,
                    MeasureSpec.makeMeasureSpec(buttonBounds.width(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(buttonBounds.height(), MeasureSpec.AT_MOST));
        }

        setMeasuredDimension(width, height);

        if (mFloatingButton != null && showClearAllRecents) {
            clearRecentsLocation = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.RECENTS_CLEAR_ALL_LOCATION,
                3, UserHandle.USER_CURRENT);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                    mFloatingButton.getLayoutParams();
            boolean isLandscape = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
            if (isLandscape) {
                params.topMargin = mContext.getResources().
                      getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
            } else {
                params.topMargin = 2*(mContext.getResources().
                    getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height));
            }

            switch (clearRecentsLocation) {
                case 0:
                    params.gravity = Gravity.TOP | Gravity.RIGHT;
                    break;
                case 1:
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    break;
                case 2:
                    params.gravity = Gravity.TOP | Gravity.CENTER;
                    break;
                case 3:
                default:
                    params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                    break;
                case 4:
                    params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    break;
                case 5:
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                    break;
            }
            mFloatingButton.setLayoutParams(params);
        } else {
            mFloatingButton.setVisibility(View.GONE);
        }
        LayoutInflater inflater = LayoutInflater.from(mContext);
        float cornerRadius = mContext.getResources().getDimensionPixelSize(
                    R.dimen.recents_task_view_rounded_corners_radius);
    }

    private boolean showMemDisplay() {
        boolean enableMemDisplay = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SYSTEMUI_RECENTS_MEM_DISPLAY, 0) == 1;

        if (!enableMemDisplay) {
            mMemText.setVisibility(View.GONE);
            mMemBar.setVisibility(View.GONE);
            return false;
        }
        mMemText.setVisibility(View.VISIBLE);
        mMemBar.setVisibility(View.VISIBLE);

        updateMemoryStatus();
        return true;
    }

    private void updateMemoryStatus() {
        if (mMemText.getVisibility() == View.GONE
                || mMemBar.getVisibility() == View.GONE) return;

        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
            int available = (int)(memInfo.availMem / 1048576L);
            int max = (int)(getTotalMemory() / 1048576L);
            mMemText.setText(String.format(getResources().getString(R.string.recents_free_ram),available));
            mMemBar.setMax(max);
            mMemBar.setProgress(available);
    }

    public long getTotalMemory() {
        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
        long totalMem = memInfo.totalMem;
        return totalMem;
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.layout(left, top, left + getMeasuredWidth(), top + getMeasuredHeight());
        }

        // Layout the empty view
        if (mEmptyView.getVisibility() != GONE) {
            int leftRightInsets = mSystemInsets.left + mSystemInsets.right;
            int topBottomInsets = mSystemInsets.top + mSystemInsets.bottom;
            int childWidth = mEmptyView.getMeasuredWidth();
            int childHeight = mEmptyView.getMeasuredHeight();
            int childLeft = left + mSystemInsets.left +
                    Math.max(0, (right - left - leftRightInsets - childWidth)) / 2;
            int childTop = top + mSystemInsets.top +
                    Math.max(0, (bottom - top - topBottomInsets - childHeight)) / 2;
            mEmptyView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }

        // Needs to know the screen size since the gradient never scales up or down
        // even when bounds change.
        mContext.getDisplay().getRealSize(mTmpDisplaySize);
        mBackgroundScrim.setScreenSize(mTmpDisplaySize.x, mTmpDisplaySize.y);
        mBackgroundScrim.setBounds(left, top, right, bottom);
        mMultiWindowBackgroundScrim.setBounds(0, 0, mTmpDisplaySize.x, mTmpDisplaySize.y);

        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Layout the stack action button such that its drawable is start-aligned with the
            // stack, vertically centered in the available space above the stack
            Rect buttonBounds = getStackActionButtonBoundsFromStackLayout();
            mStackActionButton.layout(buttonBounds.left, buttonBounds.top, buttonBounds.right,
                    buttonBounds.bottom);
        }

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;
            // If launched via dragging from the nav bar, then we should translate the whole view
            // down offscreen
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            if (launchState.launchedViaDragGesture) {
                setTranslationY(getMeasuredHeight());
            } else {
                setTranslationY(0f);
            }

            if (Recents.getConfiguration().isLowRamDevice
                    && mEmptyView.getVisibility() == View.VISIBLE) {
                animateEmptyView(true /* show */, null /* postAnimationTrigger */);
            }
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        mTaskStackView.setSystemInsets(mSystemInsets);
        requestLayout();
        return insets;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mTouchHandler.onTouchEvent(ev)) {
            return true;
        } else {
            return super.onTouchEvent(ev);
        }
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);

        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            visDockStates.get(i).viewState.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d == who) {
                return true;
            }
        }
        return super.verifyDrawable(who);
    }

    /**** EventBus Events ****/

    public final void onBusEvent(ConfigurationChangedEvent event) {
        if (event.fromDeviceOrientationChange) {
            mDisplayOrientation = Utilities.getAppConfiguration(mContext).orientation;
        }
    }

    public final void onBusEvent(LaunchTaskEvent event) {
        mLastTaskLaunchedWasFreeform = event.task.isFreeformTask();
        mTransitionHelper.launchTaskFromRecents(getStack(), event.task, mTaskStackView,
                event.taskView, event.screenPinningRequested, event.targetTaskStack);
        if (Recents.getConfiguration().isLowRamDevice) {
            EventBus.getDefault().send(new HideStackActionButtonEvent(false /* translate */));
        }
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        int taskViewExitToHomeDuration = TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION;
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Hide the stack action button
            EventBus.getDefault().send(new HideStackActionButtonEvent());
        }
        animateBackgroundScrim(0f, taskViewExitToHomeDuration);

        if (Recents.getConfiguration().isLowRamDevice) {
            animateEmptyView(false /* show */, event.getAnimationTrigger());
        }
    }

    public final void onBusEvent(DragStartEvent event) {
        updateVisibleDockRegions(Recents.getConfiguration().getDockStatesForCurrentOrientation(),
                true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                TaskStack.DockState.NONE.viewState.hintTextAlpha,
                true /* animateAlpha */, false /* animateBounds */);

        // Temporarily hide the stack action button without changing visibility
        if (mStackActionButton != null) {
            mStackActionButton.animate()
                    .alpha(0f)
                    .setDuration(HIDE_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .start();
        }

        // Temporarily hide the memory bar without changing visibility
        if (mMemBar != null && showMemDisplay()) {
            mMemBar.animate()
                    .alpha(0f)
                    .setDuration(HIDE_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .start();
        }

        // Temporarily hide the memory text without changing visibility
        if (mMemText != null && showMemDisplay()) {
            mMemText.animate()
                    .alpha(0f)
                    .setDuration(HIDE_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .start();
        }

        // Temporarily hide the floating action button without changing visibility
        if (mFloatingButton != null && showClearAllRecents) {
            endFABanimation();
        }
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        if (event.dropTarget == null || !(event.dropTarget instanceof TaskStack.DockState)) {
            updateVisibleDockRegions(
                    Recents.getConfiguration().getDockStatesForCurrentOrientation(),
                    true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                    TaskStack.DockState.NONE.viewState.hintTextAlpha,
                    true /* animateAlpha */, true /* animateBounds */);
        } else {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;
            updateVisibleDockRegions(new TaskStack.DockState[] {dockState},
                    false /* isDefaultDockState */, -1, -1, true /* animateAlpha */,
                    true /* animateBounds */);
        }
        if (mStackActionButton != null) {
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    // Move the clear all button to its new position
                    Rect buttonBounds = getStackActionButtonBoundsFromStackLayout();
                    mStackActionButton.setLeftTopRightBottom(buttonBounds.left, buttonBounds.top,
                            buttonBounds.right, buttonBounds.bottom);
                }
            });
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        // Handle the case where we drop onto a dock region
        if (event.dropTarget instanceof TaskStack.DockState) {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;

            // Hide the dock region
            updateVisibleDockRegions(null, false /* isDefaultDockState */, -1, -1,
                    false /* animateAlpha */, false /* animateBounds */);

            // We translated the view but we need to animate it back from the current layout-space
            // rect to its final layout-space rect
            Utilities.setViewFrameFromTranslation(event.taskView);

            // Dock the task and launch it
            SystemServicesProxy ssp = Recents.getSystemServices();
            if (ssp.startTaskInDockedMode(event.task.key.id, dockState.createMode)) {
                final OnAnimationStartedListener startedListener =
                        new OnAnimationStartedListener() {
                    @Override
                    public void onAnimationStarted() {
                        EventBus.getDefault().send(new DockedFirstAnimationFrameEvent());
                        // Remove the task and don't bother relaying out, as all the tasks will be
                        // relaid out when the stack changes on the multiwindow change event
                        getStack().removeTask(event.task, null, true /* fromDockGesture */);
                    }
                };

                final Rect taskRect = getTaskRect(event.taskView);
                AppTransitionAnimationSpecsFuture future =
                        mTransitionHelper.getAppTransitionFuture(
                                new AnimationSpecComposer() {
                                    @Override
                                    public List<AppTransitionAnimationSpec> composeSpecs() {
                                        return mTransitionHelper.composeDockAnimationSpec(
                                                event.taskView, taskRect);
                                    }
                                });
                ssp.overridePendingAppTransitionMultiThumbFuture(future.getFuture(),
                        mTransitionHelper.wrapStartedListener(startedListener),
                        true /* scaleUp */);

                MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_DRAG_DROP,
                        event.task.getTopComponent().flattenToShortString());
            } else {
                EventBus.getDefault().send(new DragEndCancelledEvent(getStack(), event.task,
                        event.taskView));
            }
        } else {
            // Animate the overlay alpha back to 0
            updateVisibleDockRegions(null, true /* isDefaultDockState */, -1, -1,
                    true /* animateAlpha */, false /* animateBounds */);
        }

        // Show the stack action button again without changing visibility
        if (mStackActionButton != null) {
            mStackActionButton.animate()
                    .alpha(1f)
                    .setDuration(SHOW_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .start();
        }

        // Show the memory bar without changing visibility
        if (mMemBar != null && showMemDisplay()) {
            mMemBar.animate()
                    .alpha(1f)
                    .setDuration(SHOW_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .start();
        }

        // Show the memory text without changing visibility
        if (mMemText != null && showMemDisplay()) {
            mMemText.animate()
                    .alpha(1f)
                    .setDuration(SHOW_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .start();
        }

        // Show the floating action button without changing visibility
        if (mFloatingButton != null && showClearAllRecents) {
            startFABanimation();
        }
    }

    public final void onBusEvent(final DragEndCancelledEvent event) {
        // Animate the overlay alpha back to 0
        updateVisibleDockRegions(null, true /* isDefaultDockState */, -1, -1,
                true /* animateAlpha */, false /* animateBounds */);
    }

    private Rect getTaskRect(TaskView taskView) {
        int[] location = taskView.getLocationOnScreen();
        int viewX = location[0];
        int viewY = location[1];
        return new Rect(viewX, viewY,
                (int) (viewX + taskView.getWidth() * taskView.getScaleX()),
                (int) (viewY + taskView.getHeight() * taskView.getScaleY()));
    }

    public final void onBusEvent(DraggingInRecentsEvent event) {
        if (mTaskStackView.getTaskViews().size() > 0) {
            setTranslationY(event.distanceFromTop - mTaskStackView.getTaskViews().get(0).getY());
        }
    }

    public final void onBusEvent(DraggingInRecentsEndedEvent event) {
        ViewPropertyAnimator animator = animate();
        if (event.velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            animator.translationY(getHeight());
            animator.withEndAction(new Runnable() {
                @Override
                public void run() {
                    WindowManagerProxy.getInstance().maximizeDockedStack();
                }
            });
            mFlingAnimationUtils.apply(animator, getTranslationY(), getHeight(), event.velocity);
        } else {
            animator.translationY(0f);
            animator.setListener(null);
            mFlingAnimationUtils.apply(animator, getTranslationY(), 0, event.velocity);
        }
        animator.start();
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (!launchState.launchedViaDockGesture && !launchState.launchedFromApp
                && getStack().getTaskCount() > 0) {
            animateBackgroundScrim(getOpaqueScrimAlpha(),
                    TaskStackAnimationHelper.ENTER_FROM_HOME_TRANSLATION_DURATION);
        }
    }

    public final void onBusEvent(AllTaskViewsDismissedEvent event) {
        EventBus.getDefault().send(new HideStackActionButtonEvent());
    }

    public final void onBusEvent(DismissAllTaskViewsEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (!ssp.hasDockedTask()) {
            // Animate the background away only if we are dismissing Recents to home
            animateBackgroundScrim(0f, DEFAULT_UPDATE_SCRIM_DURATION);
        }
        updateMemoryStatus();
    }

    public final void onBusEvent(ShowStackActionButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        showStackActionButton(SHOW_STACK_ACTION_BUTTON_DURATION, event.translate);
    }

    public final void onBusEvent(HideStackActionButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        hideStackActionButton(HIDE_STACK_ACTION_BUTTON_DURATION, true /* translate */);
    }

    public final void onBusEvent(MultiWindowStateChangedEvent event) {
        updateStack(event.stack, false /* setStackViewTasks */);
    }

    public final void onBusEvent(ShowEmptyViewEvent event) {
        showEmptyView(R.string.recents_empty_message);
    }

    /**
     * Shows the stack action button.
     */
    private void showStackActionButton(final int duration, final boolean translate) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }
        if (showClearAllRecents) {
            return;
        }
        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        if (mStackActionButton.getVisibility() == View.INVISIBLE) {
            mStackActionButton.setVisibility(View.VISIBLE);
            mStackActionButton.setAlpha(0f);
            if (translate) {
                mStackActionButton.setTranslationY(mStackActionButton.getMeasuredHeight() *
                        (Recents.getConfiguration().isLowRamDevice ? 1 : -0.25f));
            } else {
                mStackActionButton.setTranslationY(0f);
            }
            postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    if (translate) {
                        mStackActionButton.animate()
                            .translationY(0f);
                    }
                    mStackActionButton.animate()
                            .alpha(1f)
                            .setDuration(duration)
                            .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                            .start();
                }
            });
        }
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        hideStackActionButton(duration, translate, postAnimationTrigger);
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate,
                                       final ReferenceCountedTrigger postAnimationTrigger) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        if (mStackActionButton.getVisibility() == View.VISIBLE) {
            if (translate) {
                mStackActionButton.animate().translationY(mStackActionButton.getMeasuredHeight()
                        * (Recents.getConfiguration().isLowRamDevice ? 1 : -0.25f));
            }
            mStackActionButton.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mStackActionButton.setVisibility(View.INVISIBLE);
                            postAnimationTrigger.decrement();
                        }
                    })
                    .start();
            postAnimationTrigger.increment();
        }
    }

    /**
     * Animates a translation in the Y direction and fades in/out for empty view to show or hide it.
     * @param show whether to translate up and fade in the empty view to the center of the screen
     * @param postAnimationTrigger to keep track of the animation
     */
    private void animateEmptyView(boolean show, ReferenceCountedTrigger postAnimationTrigger) {
        float start = mTaskStackView.getStackAlgorithm().getTaskRect().height() / 4;
        mEmptyView.setTranslationY(show ? start : 0);
        mEmptyView.setAlpha(show ? 0f : 1f);
        ViewPropertyAnimator animator = mEmptyView.animate()
                .setDuration(150)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(show ? 0 : start)
                .alpha(show ? 1f : 0f);

        if (postAnimationTrigger != null) {
            animator.setListener(postAnimationTrigger.decrementOnAnimationEnd());
            postAnimationTrigger.increment();
        }
        animator.start();
    }

    /**
     * Updates the dock region to match the specified dock state.
     */
    private void updateVisibleDockRegions(TaskStack.DockState[] newDockStates,
            boolean isDefaultDockState, int overrideAreaAlpha, int overrideHintAlpha,
            boolean animateAlpha, boolean animateBounds) {
        ArraySet<TaskStack.DockState> newDockStatesSet = Utilities.arrayToSet(newDockStates,
                new ArraySet<TaskStack.DockState>());
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            TaskStack.DockState dockState = visDockStates.get(i);
            TaskStack.DockState.ViewState viewState = dockState.viewState;
            if (newDockStates == null || !newDockStatesSet.contains(dockState)) {
                // This is no longer visible, so hide it
                viewState.startAnimation(null, 0, 0, TaskStackView.SLOW_SYNC_STACK_DURATION,
                        Interpolators.FAST_OUT_SLOW_IN, animateAlpha, animateBounds);
            } else {
                // This state is now visible, update the bounds and show it
                int areaAlpha = overrideAreaAlpha != -1
                        ? overrideAreaAlpha
                        : viewState.dockAreaAlpha;
                int hintAlpha = overrideHintAlpha != -1
                        ? overrideHintAlpha
                        : viewState.hintTextAlpha;
                Rect bounds = isDefaultDockState
                        ? dockState.getPreDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                                mSystemInsets)
                        : dockState.getDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                                mDividerSize, mSystemInsets, getResources());
                if (viewState.dockAreaOverlay.getCallback() != this) {
                    viewState.dockAreaOverlay.setCallback(this);
                    viewState.dockAreaOverlay.setBounds(bounds);
                }
                viewState.startAnimation(bounds, areaAlpha, hintAlpha,
                        TaskStackView.SLOW_SYNC_STACK_DURATION, Interpolators.FAST_OUT_SLOW_IN,
                        animateAlpha, animateBounds);
            }
        }
    }

    /**
     * Scrim alpha based on how busy recents is:
     * Scrim will be {@link ScrimController#GRADIENT_SCRIM_ALPHA} when the stack is empty,
     * and {@link ScrimController#GRADIENT_SCRIM_ALPHA_BUSY} when it's full.
     *
     * @return Alpha from 0 to 1.
     */
    private float getOpaqueScrimAlpha() {
        return MathUtils.map(0, 1, showWallpaperTint() ? ScrimController.GRADIENT_SCRIM_ALPHA : ScrimController.CUSTOM_GRADIENT_SCRIM_ALPHA,
                showWallpaperTint() ? ScrimController.GRADIENT_SCRIM_ALPHA_BUSY : ScrimController.CUSTOM_GRADIENT_SCRIM_ALPHA, mBusynessFactor);
    }

    /**
     * Animates the background scrim to the given {@param alpha}.
     */
    private void animateBackgroundScrim(float alpha, int duration) {
        Utilities.cancelAnimationWithoutCallbacks(mBackgroundScrimAnimator);
        // Calculate the absolute alpha to animate from
        final int fromAlpha = mBackgroundScrim.getAlpha();
        final int toAlpha = (int) (alpha * 255);
        mBackgroundScrimAnimator = ValueAnimator.ofInt(fromAlpha, toAlpha);
        mBackgroundScrimAnimator.setDuration(duration);
        mBackgroundScrimAnimator.setInterpolator(toAlpha > fromAlpha
                ? Interpolators.ALPHA_IN
                : Interpolators.ALPHA_OUT);
        mBackgroundScrimAnimator.addUpdateListener(mUpdateBackgroundScrimAlpha);
        mBackgroundScrimAnimator.start();
    }

    /**
     * @return the bounds of the stack action button.
     */
    Rect getStackActionButtonBoundsFromStackLayout() {
        Rect actionButtonRect = new Rect(mTaskStackView.mLayoutAlgorithm.getStackActionButtonRect());
        int left, top;
        if (Recents.getConfiguration().isLowRamDevice) {
            Rect windowRect = Recents.getSystemServices().getWindowRect();
            int spaceLeft = windowRect.width() - mSystemInsets.left - mSystemInsets.right;
            left = (spaceLeft - mStackActionButton.getMeasuredWidth()) / 2 + mSystemInsets.left;
            top = windowRect.height() - (mStackActionButton.getMeasuredHeight()
                    + mSystemInsets.bottom + mStackActionButton.getPaddingBottom() / 2);
        } else {
            left = isLayoutRtl()
                ? actionButtonRect.left - mStackActionButton.getPaddingLeft()
                : actionButtonRect.right + mStackActionButton.getPaddingRight()
                        - mStackActionButton.getMeasuredWidth();
            top = actionButtonRect.top +
                (actionButtonRect.height() - mStackActionButton.getMeasuredHeight()) / 2;
        }
        actionButtonRect.set(left, top, left + mStackActionButton.getMeasuredWidth(),
                top + mStackActionButton.getMeasuredHeight());
        return actionButtonRect;
    }

    View getStackActionButton() {
        return mStackActionButton;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        mTouchHandler.cancelStackActionButtonClick();
    }

    public void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        String id = Integer.toHexString(System.identityHashCode(this));

        writer.print(prefix); writer.print(TAG);
        writer.print(" awaitingFirstLayout="); writer.print(mAwaitingFirstLayout ? "Y" : "N");
        writer.print(" insets="); writer.print(Utilities.dumpRect(mSystemInsets));
        writer.print(" [0x"); writer.print(id); writer.print("]");
        writer.println();

        if (getStack() != null) {
            getStack().dump(innerPrefix, writer);
        }
        if (mTaskStackView != null) {
            mTaskStackView.dump(innerPrefix, writer);
        }
    }

    class SettingsObserver extends ContentObserver {
         SettingsObserver(Handler handler) {
             super(handler);
         }

         void observe() {
             ContentResolver resolver = mContext.getContentResolver();
             resolver.registerContentObserver(Settings.System.getUriFor(
                     Settings.System.SHOW_CLEAR_ALL_RECENTS), false, this, UserHandle.USER_ALL);
             update();
         }

         void unobserve() {
             ContentResolver resolver = mContext.getContentResolver();
             resolver.unregisterContentObserver(this);
         }

         @Override
         public void onChange(boolean selfChange, Uri uri) {
             update();
         }

   public void update() {
        mFloatingButton = ((View)getParent()).findViewById(R.id.floating_action_button);
        mClearRecents = (ImageButton) ((View)getParent()).findViewById(R.id.clear_recents);
        showClearAllRecents = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SHOW_CLEAR_ALL_RECENTS, 1, UserHandle.USER_CURRENT) != 0;
         }
     }
	private boolean showWallpaperTint() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.WALLPAPER_RECENTS_TINT, 1, UserHandle.USER_CURRENT) == 1;
         }

    /**
    * Extended SimpleOnScaleGestureListener to take
    * care of a pinch to zoom out gesture. This class
    * takes as well care on a bunch of animations which are needed
    * to control the final action.
    */
    private class PinchInGesture extends SimpleOnScaleGestureListener {

        // Constants for scaling max/min values
        private final static float MAX_SCALING_FACTOR       = 1.0f;
        private final static float MIN_SCALING_FACTOR       = 0.5f;
        private final static float MIN_ALPHA_SCALING_FACTOR = 0.55f;
        private final static float MIN_ALPHA_SCALING_FACTOR_LANDSCAPE = 0.75f;

        private final static int ANIMATION_FADE_IN_DURATION  = 400;
        private final static int ANIMATION_FADE_OUT_DURATION = 300;

        private float mScalingFactor = MAX_SCALING_FACTOR;
        private boolean mActionDetected;

        // View we need and is passed trough the constructor.
        private View mRecentTasksView;

        public PinchInGesture(View taskStackView) {
            mRecentTasksView = taskStackView;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Get gesture scaling factor and calculate the values we need
            mScalingFactor *= detector.getScaleFactor();
            mScalingFactor = Math.max(MIN_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));
            final float alphaValue = Math.max(MIN_ALPHA_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));

            // Reset detection value.
            mActionDetected = false;

            // Set alpha value for content.
            mRecentTasksView.setAlpha(alphaValue);

            // Check if we are under MIN_ALPHA_SCALING_FACTOR
            boolean isLandscape =
                    mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE;
            // Make the gesture easier to trigger on landscape where we have smaller space
            if (mScalingFactor < (!isLandscape ? MIN_ALPHA_SCALING_FACTOR
                    : MIN_ALPHA_SCALING_FACTOR_LANDSCAPE)) {
                mActionDetected = true;
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            // Reset to default scaling factor to prepare for next gesture.
            mScalingFactor = MAX_SCALING_FACTOR;

            final float currentAlpha = mRecentTasksView.getAlpha();

            // Gesture was detected and activated. Prepare and play the animations
            if (mActionDetected) {

                // Setup additional fade out final animation for tasks view.
                // Quickly restore alpha then go to 0 to create a flash effect
                ValueAnimator animation1 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation1.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation1.addUpdateListener((ValueAnimator animation) -> {
                    mRecentTasksView.setAlpha((Float) animation.getAnimatedValue());
                });

                // Start all ValueAnimator animations
                // and listen onAnimationEnd to prepare the views for the next call
                AnimatorSet animationSet = new AnimatorSet();
                animationSet.play(animation1);
                animationSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Animation is finished. Prepare tasks view for next recent call
                        mRecentTasksView.setVisibility(View.GONE);
                        mRecentTasksView.setAlpha(1.0f);
                        // Remove all tasks now
                        EventBus.getDefault().send(new DismissAllTaskViewsEvent());
                    }
                });
                animationSet.start();

            } else if (currentAlpha < 1.0f) {
                // No gesture action was detected but we may have a lower alpha
                // value for the tasks view. Animate back to full opacitiy
                ValueAnimator restoreAlpha = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                restoreAlpha.setDuration(100);
                restoreAlpha.addUpdateListener((ValueAnimator animation) -> {
                    mRecentTasksView.setAlpha((Float) animation.getAnimatedValue());
                });
                restoreAlpha.start();
            }
        }
    }
}
