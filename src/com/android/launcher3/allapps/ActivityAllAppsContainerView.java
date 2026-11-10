/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.launcher3.allapps;

import static com.android.launcher3.Flags.enableExpandingPauseWorkButton;
import static com.android.launcher3.allapps.ActivityAllAppsContainerView.AdapterHolder.MAIN;
import static com.android.launcher3.allapps.ActivityAllAppsContainerView.AdapterHolder.SEARCH;
import static com.android.launcher3.allapps.ActivityAllAppsContainerView.AdapterHolder.WORK;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_PRIVATE_SPACE_HEADER;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_WORK_DISABLED_CARD;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_WORK_EDU_CARD;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_COUNT;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_TAP_ON_PERSONAL_TAB;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_TAP_ON_WORK_TAB;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.ScrollableLayoutManager.PREDICTIVE_BACK_MIN_SCALE;
import static com.android.launcher3.views.RecyclerViewFastScroller.FastScrollerLocation.ALL_APPS_SCROLLER;
import static com.android.window.flags2.Flags.predictiveBackThreeButtonNav;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.CrossWindowBlurListeners;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Flags;
import com.android.launcher3.Insettable;
import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.search.AllAppsSearchUiDelegate;
import com.android.launcher3.allapps.search.DefaultSearchAdapterProvider;
import com.android.launcher3.allapps.search.SearchAdapterProvider;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.keyboard.FocusedItemDecorator;
import com.android.launcher3.keyboard.ViewGroupFocusHelper;
import com.android.launcher3.model.StringCache;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.UserIconInfo;
import com.android.launcher3.recyclerview.AllAppsRecyclerViewPool;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;
import com.android.launcher3.views.RecyclerViewFastScroller;
import com.android.launcher3.views.ScrimView;
import com.android.launcher3.views.SpringRelativeLayout;
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip;
import com.android.systemui.plugins.AllAppsRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import static com.topjohnwu.superuser.internal.Utils.context;
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList;
import app.lawnchair.allapps.tabs.DrawerTab;
import app.lawnchair.allapps.tabs.DrawerTabsActions;
import app.lawnchair.allapps.tabs.DrawerTabsConfig;
import app.lawnchair.allapps.tabs.DrawerTabsWatcher;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.util.ComponentKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import app.lawnchair.font.FontManager;
import app.lawnchair.preferences.PreferenceManager;
import app.lawnchair.preferences2.PreferenceManager2;
import app.lawnchair.theme.color.tokens.ColorTokens;
import app.lawnchair.util.LawnchairUtilsKt;
import app.lawnchair.ui.StretchRecyclerViewContainer;

/**
 * All apps container view with search support for use in a dragging activity.
 *
 * @param <T> Type of context inflating all apps.
 */
public class ActivityAllAppsContainerView<T extends Context & ActivityContext>
        extends SpringRelativeLayout implements DragSource, Insettable,
        OnDeviceProfileChangeListener, PersonalWorkSlidingTabStrip.OnActivePageChangedListener,
        ScrimView.ScrimDrawingController {


    private static final String TAG = "ActivityAllAppsContainerView";
    public static final float PULL_MULTIPLIER = .02f;
    public static final float FLING_VELOCITY_MULTIPLIER = 1200f;
    protected static final String BUNDLE_KEY_CURRENT_PAGE = "launcher.allapps.current_page";
    private static final long DEFAULT_SEARCH_TRANSITION_DURATION_MS = 300;
    // Render the header protection at all times to debug clipping issues.
    private static final boolean DEBUG_HEADER_PROTECTION = false;
    /** Context of an activity or window that is inflating this container. */

    protected final T mActivityContext;
    protected final List<AdapterHolder> mAH;
    protected final Predicate<ItemInfo> mPersonalMatcher = info -> {
        if (info == null) {
            return false;
        }
        if (Process.myUserHandle().equals(info.user)) {
            return true;
        }
        UserIconInfo userIconInfo = UserCache.getInstance(getContext()).getUserInfo(info.user);
        return userIconInfo.isCloned();
    }; // Lawnchair: Show app from clone profile
    protected WorkProfileManager mWorkManager;
    protected final PrivateProfileManager mPrivateProfileManager;
    protected final Point mFastScrollerOffset = new Point();
    protected int mScrimColor;
    protected final float mHeaderThreshold;
    protected final AllAppsSearchUiDelegate mSearchUiDelegate;

    // Used to animate Search results out and A-Z apps in, or vice-versa.
    private final SearchTransitionController mSearchTransitionController;
    private final Paint mHeaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mInsets = new Rect();
    private final AllAppsStore<T> mAllAppsStore;
    private final RecyclerView.OnScrollListener mScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateHeaderScroll(recyclerView.computeVerticalScrollOffset());
                }
            };
    private final Paint mNavBarScrimPaint;
    private final int mHeaderProtectionColor;
    private final int mPrivateSpaceBottomExtraSpace;
    private final Path mTmpPath = new Path();
    private final RectF mTmpRectF = new RectF();
    protected AllAppsPagedView mViewPager;
    protected FloatingHeaderView mHeader;
    protected final List<AllAppsRow> mAdditionalHeaderRows = new ArrayList<>();
    protected View mBottomSheetBackground;
    protected RecyclerViewFastScroller mFastScroller;
    private ConstraintLayout mFastScrollLetterLayout;

    /**
     * View that defines the search box. Result is rendered inside {@link #mSearchRecyclerView}.
     */
    protected View mSearchContainer;
    protected SearchUiManager mSearchUiManager;
    protected boolean mUsingTabs;
    protected RecyclerViewFastScroller mTouchHandler;

    /** {@code true} when rendered view is in search state instead of the scroll state. */
    private boolean mIsSearching;
    private boolean mSearchExitInProgress;
    boolean showFastScroller;
    private boolean mRebindAdaptersAfterSearchAnimation;
    private int mNavBarScrimHeight = 0;
    public SearchRecyclerView mSearchRecyclerView;
    protected SearchAdapterProvider<?> mMainAdapterProvider;
    private View mBottomSheetHandleArea;
    private View mBottomSheetHandle;
    private boolean mHasWorkApps;
    private boolean mHasPrivateApps;
    private float[] mBottomSheetCornerRadii;
    private ScrimView mScrimView;
    private int mHeaderColor;
    private int mBottomSheetBackgroundColorBlurFallback;
    private int mBottomSheetBackgroundColorOverBlur;
    private int mBottomSheetBackgroundColorLegacy;
    private int mTabsProtectionAlpha;
    @Nullable private AllAppsTransitionController mAllAppsTransitionController;

    @Nullable private java.util.function.Consumer<Boolean> mCrossWindowBlurListener;

    private final PreferenceManager2 pref2;
    private final PreferenceManager pref;

    // Lawnchair: drawer tabs. Pages are [All (MAIN), custom tabs..., Work?].
    // A page index is not an adapter holder type: use holderForPage()/pageForHolder().
    private final DrawerTabsWatcher mTabsWatcher;
    private DrawerTabsConfig mTabsConfig = DrawerTabsConfig.EMPTY;
    private boolean mTabsConfigDirty;
    /**
     * Custom tabs are only shown once apps have been bound (like the stock Work tab). Showing
     * them during inflation crashes: the tabs path attaches work views that need the launcher's
     * apps view, which is not assigned yet.
     */
    private boolean mAppsBound;
    /** Custom tab holders keyed by tab id. They also sit in mAH from FIRST_CUSTOM on. */
    private final Map<Long, AdapterHolder> mCustomHolders = new HashMap<>();
    /** Component keys of each custom tab, keyed by tab id. */
    private final Map<Long, Set<String>> mTabKeys = new HashMap<>();
    /** Page index -> adapter holder type (index into mAH) for the bound layout. */
    private List<Integer> mPageHolders = new ArrayList<>(List.of(AdapterHolder.MAIN));
    private List<Long> mBoundTabIds = new ArrayList<>();
    private Set<String> mHiddenFromAll = Collections.emptySet();
    /** Matcher for the first ("All") page: personal apps minus apps hidden from All. */
    protected final Predicate<ItemInfo> mAllTabMatcher = info -> mPersonalMatcher.test(info)
            && (mHiddenFromAll.isEmpty() || !mHiddenFromAll.contains(drawerKeyOf(info)));

    // LC-Note: Allapps cache colour
    private int mCachedBottomSheetBgColor;

    public ActivityAllAppsContainerView(Context context) {
        this(context, null);
    }

    public ActivityAllAppsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActivityAllAppsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mActivityContext = ActivityContext.lookupContext(context);
        mAllAppsStore = new AllAppsStore<>(mActivityContext);
        
        pref2 = PreferenceManager2.getInstance(mActivityContext);
        pref = PreferenceManager.getInstance(mActivityContext);
        
        mScrimColor = ColorTokens.AllAppsScrimColor.resolveColor(context);
        mHeaderThreshold = getResources().getDimensionPixelSize(
                R.dimen.dynamic_grid_cell_border_spacing);
        mHeaderProtectionColor = ColorTokens.AllAppsHeaderProtectionColor.resolveColor(context);

        mWorkManager = new WorkProfileManager(
                mActivityContext.getSystemService(UserManager.class),
                this,
                mActivityContext.getStatsLogManager(),
                UserCache.INSTANCE.get(mActivityContext));
        mPrivateProfileManager = new PrivateProfileManager(
                mActivityContext.getSystemService(UserManager.class),
                this,
                mActivityContext.getStatsLogManager(),
                UserCache.INSTANCE.get(mActivityContext));
        mPrivateSpaceBottomExtraSpace = context.getResources().getDimensionPixelSize(
                R.dimen.ps_extra_bottom_padding);
        mAH = new ArrayList<>(Arrays.asList(null, null, null));
        mTabsWatcher = new DrawerTabsWatcher(context, this::onTabsConfigChanged);
        mTabsConfig = mTabsWatcher.current();
        applyTabsConfigData();
        mNavBarScrimPaint = new Paint();
        mNavBarScrimPaint.setColor(Themes.getNavBarScrimColor(mActivityContext));

        AllAppsStore.OnUpdateListener onAppsUpdated = this::onAppsUpdated;
        mAllAppsStore.addUpdateListener(onAppsUpdated);

        // This is a focus listener that proxies focus from a view into the list view.  This is to
        // work around the search box from getting first focus and showing the cursor.
        setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && getActiveRecyclerView() != null) {
                getActiveRecyclerView().requestFocus();
            }
        });
        mSearchUiDelegate = createSearchUiDelegate();
        initContent();

        mSearchTransitionController = new SearchTransitionController(this);
    }

    /** Creates the delegate for initializing search. */
    protected AllAppsSearchUiDelegate createSearchUiDelegate() {
        return new AllAppsSearchUiDelegate(this);
    }

    public AllAppsSearchUiDelegate getSearchUiDelegate() {
        return mSearchUiDelegate;
    }

    /**
     * Initializes the view hierarchy and internal variables. Any initialization which actually uses
     * these members should be done in {@link #onFinishInflate()}.
     * In terms of subclass initialization, the following would be parallel order for activity:
     *   initContent -> onPreCreate
     *   constructor/init -> onCreate
     *   onFinishInflate -> onPostCreate
     */
    protected void initContent() {
        showFastScroller = PreferenceCacheExtensionsKt.firstCached(pref2.getShowScrollbar());

        mMainAdapterProvider = mSearchUiDelegate.createMainAdapterProvider();

        mAH.set(AdapterHolder.MAIN, new AdapterHolder(AdapterHolder.MAIN,
                new LawnchairAlphabeticalAppsList<>(mActivityContext,
                        mAllAppsStore,
                        null,
                        mPrivateProfileManager)));
        mAH.set(AdapterHolder.WORK, new AdapterHolder(AdapterHolder.WORK,
                new LawnchairAlphabeticalAppsList<>(mActivityContext, mAllAppsStore, mWorkManager, null)));
        mAH.set(SEARCH, new AdapterHolder(SEARCH,
                new LawnchairAlphabeticalAppsList<>(mActivityContext, mAllAppsStore, null, null)));

        getLayoutInflater().inflate(R.layout.all_apps_content, this);
        mHeader = findViewById(R.id.all_apps_header);
        mAdditionalHeaderRows.clear();
        mAdditionalHeaderRows.addAll(getAdditionalHeaderRows());
        mBottomSheetBackground = findViewById(R.id.bottom_sheet_background);
        mBottomSheetHandleArea = findViewById(R.id.bottom_sheet_handle_area);
        mBottomSheetHandle = findViewById(R.id.bottom_sheet_handle);
        mSearchRecyclerView = findViewById(R.id.search_results_list_view);
        mFastScroller = findViewById(R.id.fast_scroller);
        mFastScroller.setPopupView(findViewById(R.id.fast_scroller_popup));
        mFastScroller.setVisibility(showFastScroller ? VISIBLE : INVISIBLE);
        mFastScrollLetterLayout = findViewById(R.id.scroll_letter_layout);
        setClipChildren(false);

        mSearchContainer = inflateSearchBar();
        if (!isSearchBarFloating()) {
            // Add the search box above everything else in this container (if the flag is enabled,
            // it's added to drag layer in onAttach instead).
            addView(mSearchContainer);
            // The search container is visually at the top of the all apps UI, and should thus be
            // focused by default. It's added to end of the children list, so it needs to be
            // explicitly marked as focused by default.
            mSearchContainer.setFocusedByDefault(true);
        }
        mSearchUiManager = (SearchUiManager) mSearchContainer;
    }

    public List<AllAppsRow> getAdditionalHeaderRows() {
        return List.of();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAH.get(SEARCH).setup(mSearchRecyclerView,
                /* Filter out A-Z apps */ itemInfo -> false);
        rebindAdapters(true /* force */);
        float cornerRadius = Themes.getDialogCornerRadius(getContext());
        mBottomSheetCornerRadii = new float[]{
                cornerRadius,
                cornerRadius, // Top left radius in px
                cornerRadius,
                cornerRadius, // Top right radius in px
                0,
                0, // Bottom right
                0,
                0 // Bottom left
        };

        if (Flags.allAppsBlur()) {
            int layerFg = ColorTokens.shade_panel_fg_color.resolveColor(getContext());
            int layerBg = ColorTokens.shade_panel_bg_color.resolveColor(getContext());
            mBottomSheetBackgroundColorOverBlur = ColorUtils.compositeColors(layerFg, layerBg);
            mBottomSheetBackgroundColorBlurFallback = ColorTokens.BottomSheetBackgroundColorBlurFallback.resolveColor(getContext());
        }

        mBottomSheetBackgroundColorLegacy = ColorTokens.SurfaceDimColor.resolveColor(getContext());

        // LC-Note: Update our allapps cached colour
        updateBottomSheetBackgroundColor();

        updateBackgroundVisibility(mActivityContext.getDeviceProfile());
        mSearchUiManager.initializeSearch(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isSearchBarFloating()) {
            mActivityContext.getDragLayer().addView(mSearchContainer);
            mSearchUiDelegate.onInitializeSearchBar();
        }
        mActivityContext.addOnDeviceProfileChangeListener(this);
        mTabsWatcher.start();
        if (Utilities.ATLEAST_S) {
            java.util.function.Consumer<Boolean> listener = enabled -> {
                if (updateBottomSheetBackgroundColor(enabled)) {
                    invalidate();
                }
            };
            mCrossWindowBlurListener = listener;
            UI_HELPER_EXECUTOR.execute(() -> {
                try {
                    CrossWindowBlurListeners.getInstance()
                            .addListener(MAIN_EXECUTOR, listener);
                } catch (Throwable t) {
                    // LC-Ignored
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mActivityContext.removeOnDeviceProfileChangeListener(this);
        mTabsWatcher.stop();
        if (mCrossWindowBlurListener != null) {
            java.util.function.Consumer<Boolean> listener = mCrossWindowBlurListener;
            UI_HELPER_EXECUTOR.execute(() -> {
                try {
                    CrossWindowBlurListeners.getInstance()
                            .removeListener(listener);
                } catch (Throwable t) {
                    // LC-Ignored
                }
            });
            mCrossWindowBlurListener = null;
        }
    }

    public SearchUiManager getSearchUiManager() {
        return mSearchUiManager;
    }

    public View getSearchView() {
        return mSearchContainer;
    }

    /** Clears displayed search results. */
    public void onClearSearchResult() {
        getMainAdapterProvider().clearHighlightedItem();
        ExtendedEditText editText = mSearchUiManager.getEditText();
        // An empty, focused query is still an active search session.
        boolean keepSearchFocus = editText != null && editText.isFocused()
                && editText.getText().length() == 0;
        animateToSearchState(false, DEFAULT_SEARCH_TRANSITION_DURATION_MS, keepSearchFocus);
        rebindAdapters();
    }

    /**
     * Sets results list for search
     */
    public void setSearchResults(ArrayList<AdapterItem> results) {
        getMainAdapterProvider().clearHighlightedItem();
        if (getSearchResultList().setSearchResults(results)) {
            getSearchRecyclerView().onSearchResultsChanged();
        }
        if (results != null) {
            animateToSearchState(true);
        }
    }

    /**
     * Sets results list for search.
     *
     * @param searchResultCode indicates if the result is final or intermediate for a given query
     *                         since we can get search results from multiple sources.
     */
    public void setSearchResults(ArrayList<AdapterItem> results, int searchResultCode) {
        setSearchResults(results);
        mSearchUiDelegate.onSearchResultsChanged(results, searchResultCode);
    }

    private void animateToSearchState(boolean goingToSearch) {
        animateToSearchState(goingToSearch, DEFAULT_SEARCH_TRANSITION_DURATION_MS);
    }

    public void setAllAppsTransitionController(
            AllAppsTransitionController allAppsTransitionController) {
        mAllAppsTransitionController = allAppsTransitionController;
    }

    void animateToSearchState(boolean goingToSearch, long durationMs) {
        animateToSearchState(goingToSearch, durationMs, false);
    }

    private void animateToSearchState(boolean goingToSearch, long durationMs,
            boolean keepSearchFocus) {
        if (!mSearchTransitionController.isRunning() && goingToSearch == isSearching()) {
            return;
        }
        mSearchExitInProgress = !goingToSearch;
        mFastScroller.setVisibility(goingToSearch ? INVISIBLE : VISIBLE);
        if (goingToSearch) {
            // Fade out the button to pause work apps.
            mWorkManager.onActivePageChanged(SEARCH);
        } else if (mAllAppsTransitionController != null) {
            // If exiting search, revert predictive back scale on all apps
            mAllAppsTransitionController.animateAllAppsToNoScale();
            mFastScroller.setVisibility(showFastScroller ? VISIBLE : INVISIBLE);
        }
        mSearchTransitionController.animateToState(goingToSearch, durationMs,
                /* onEndRunnable = */ () -> {
                    mIsSearching = goingToSearch;
                    updateSearchResultsVisibility();
                    int previousPage = getCurrentPage();
                    if (mRebindAdaptersAfterSearchAnimation) {
                        rebindAdapters(false);
                        mRebindAdaptersAfterSearchAnimation = false;
                    }

                    if (goingToSearch) {
                        mSearchUiDelegate.onAnimateToSearchStateCompleted();
                        mSearchExitInProgress = false;
                    } else {
                        setSearchResults(null);
                        if (mViewPager != null) {
                            int page = pageForHolder(previousPage);
                            if (page >= 0) {
                                mViewPager.setCurrentPage(page);
                            }
                        }
                        onActivePageChanged(previousPage, keepSearchFocus);
                        mSearchExitInProgress = false;
                    }
                });
    }

    public boolean shouldContainerScroll(MotionEvent ev) {
        BaseDragLayer dragLayer = mActivityContext.getDragLayer();
        // If the MotionEvent is inside the search box, and the container keeps on receiving touch
        // input, container should move down.
        if (dragLayer.isEventOverView(mSearchContainer, ev)) {
            // If the touch was on the edit text, container should move down ONLY when edit text is
            // already at the top.
            View editText = mSearchUiManager.getEditText();
            if (editText != null && dragLayer.isEventOverView(editText, ev)) {
                boolean canScrollUp = editText.canScrollVertically(-1);
                return !canScrollUp;
            }
            return true;
        }
        // If the MotionEvent is inside the handle area, and the container keeps on receiving touch
        // input, container should move down.
        if (dragLayer.isEventOverView(mBottomSheetHandleArea, ev)) {
            return true;
        }
        AllAppsRecyclerView rv = getActiveRecyclerView();
        if (rv == null) {
            return true;
        }
        if (rv.getScrollbar() != null
                && rv.getScrollbar().getThumbOffsetY() >= 0
                && dragLayer.isEventOverView(rv.getScrollbar(), ev)) {
            return false;
        }
        // Scroll if not within the container view (e.g. over large-screen scrim).
        if (!dragLayer.isEventOverView(getVisibleContainerView(), ev)) {
            return true;
        }
        return rv.shouldContainerScroll(ev, dragLayer);
    }

    /**
     * Resets the UI to be ready for fresh interactions in the future. Exits search and returns to
     * A-Z apps list.
     *
     * @param animate Whether to animate the header during the reset (e.g. switching profile tabs).
     */
    public void reset(boolean animate) {
        reset(animate, true);
    }

    /**
     * Resets the UI to be ready for fresh interactions in the future.
     *
     * @param animate Whether to animate the header during the reset (e.g. switching profile tabs).
     * @param exitSearch Whether to force exit the search state and return to A-Z apps list.
     */
    public void reset(boolean animate, boolean exitSearch) {
        // Scroll Main and Work RV to top. Search RV is done in `resetSearch`.
        if (!PreferenceCacheExtensionsKt.firstCached(pref2.getRememberPosition())) {
            for (int i = 0; i < mAH.size(); i++) {
                if (i != SEARCH && mAH.get(i).mRecyclerView != null) {
                    mAH.get(i).mRecyclerView.scrollToTop();
                }
            }
        }
        if (mTouchHandler != null) {
            mTouchHandler.endFastScrolling();
        }
        if (mHeader != null && mHeader.getVisibility() == VISIBLE) {
            mHeader.reset(animate);
        }
        updateBackgroundVisibility(mActivityContext.getDeviceProfile());
        // Reset the base recycler view after transitioning home.
        updateHeaderScroll(0);
        if (exitSearch) {
            // Reset the search bar and search RV after transitioning home.
            MAIN_EXECUTOR.getHandler().post(mSearchUiManager::resetSearch);
        }
        if (isSearching()) {
            mWorkManager.reset();
        }
    }

    /**
     * Exits search and returns to A-Z apps list. Scroll to the private space header.
     */
    public void resetAndScrollToPrivateSpaceHeader() {
        // Animate to A-Z with 0 time to reset the animation with proper state management.
        // We can't rely on `animateToSearchState` with delay inside `resetSearch` because that will
        // conflict with following scrolling to bottom, so we need it with 0 time here.
        animateToSearchState(false, 0);

        MAIN_EXECUTOR.getHandler().post(() -> {
            // Reset the search bar after transitioning home.
            // When `resetSearch` is called after `animateToSearchState` is finished, the inside
            // `animateToSearchState` with delay is a just no-op and return early.
            mSearchUiManager.resetSearch();
            // Switch to the main tab
            switchToTab(ActivityAllAppsContainerView.AdapterHolder.MAIN);
            // Scroll to bottom
            if (mPrivateProfileManager != null) {
                mPrivateProfileManager.scrollForHeaderToBeVisibleInContainer(
                        getActiveAppsRecyclerView(),
                        getPersonalAppList().getAdapterItems(),
                        mPrivateProfileManager.getPsHeaderHeight(),
                        mActivityContext.getDeviceProfile().getAllAppsProfile().getCellHeightPx());
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        mSearchUiManager.preDispatchKeyEvent(event);
        return super.dispatchKeyEvent(event);
    }

    public String getDescription() {
        if (!mUsingTabs && isSearching()) {
            return getContext().getString(R.string.all_apps_search_results);
        } else {
            StringCache cache = mActivityContext.getStringCache();
            if (mUsingTabs && mTabsConfig.isActive()) {
                CharSequence title = currentTabTitle();
                if (title != null) {
                    return title.toString();
                }
            }
            if (mUsingTabs) {
                if (cache != null) {
                    return isPersonalTab()
                            ? cache.allAppsPersonalTabAccessibility
                            : cache.allAppsWorkTabAccessibility;
                } else {
                    return isPersonalTab()
                            ? getContext().getString(R.string.all_apps_button_personal_label)
                            : getContext().getString(R.string.all_apps_button_work_label);
                }
            }
            return getContext().getString(R.string.all_apps_button_label);
        }
    }

    public boolean isSearching() {
        return mIsSearching;
    }

    /**
     * @return {@code true} if back gesture should exit search rather than change launcher state.
      */
    public boolean shouldBackExitSearch() {
        return isSearching();
    }

    /** Called by the tab strip with a page index. */
    @Override
    public void onActivePageChanged(int currentActivePage) {
        onActivePageChanged(holderForPage(currentActivePage), false);
    }

    private void onActivePageChanged(int currentActivePage, boolean keepSearchFocus) {
        if (mSearchTransitionController.isRunning()) {
            // Will be called at the end of the animation.
            return;
        }
        if (currentActivePage != SEARCH && !keepSearchFocus) {
            mActivityContext.hideKeyboard();
        }
        if (mAH.get(currentActivePage).mRecyclerView != null) {
            mAH.get(currentActivePage).mRecyclerView.bindFastScrollbar(mFastScroller,
                    ALL_APPS_SCROLLER);
        }
        // Header keeps track of active recycler view to properly render header protection.
        mHeader.setActiveRV(currentActivePage);
        reset(true /* animate */, !isSearching() && !keepSearchFocus /* exitSearch */);

        mWorkManager.onActivePageChanged(currentActivePage);
    }

    protected void rebindAdapters() {
        rebindAdapters(false /* force */);
    }

    protected void rebindAdapters(boolean force) {
        Log.d(TAG, "rebindAdapters: force: " + force);
        if (mSearchTransitionController.isRunning()) {
            mRebindAdaptersAfterSearchAnimation = true;
            return;
        }
        updateSearchResultsVisibility();

        // Lawnchair: drawer tabs can change the page layout without changing showTabs.
        syncCustomHolders();
        List<Integer> pages = computePageHolders();
        List<Long> tabIds = currentTabIds();
        boolean showTabs = shouldShowTabs();
        boolean layoutChanged = showTabs
                && (!pages.equals(mPageHolders) || !tabIds.equals(mBoundTabIds));
        if (showTabs == mUsingTabs && !layoutChanged && !force) {
            if (mTabsConfigDirty) {
                mTabsConfigDirty = false;
                refreshDrawerTabs();
            }
            Log.d(TAG, "rebindAdapters: Not needed.");
            return;
        }
        mTabsConfigDirty = false;

        forAllRecyclerViews(mAllAppsStore::unregisterIconContainer);

        // replaceAppsRVContainer() needs to use both mUsingTabs value to remove the old view AND
        // showTabs value to create new view. Hence the mUsingTabs new value assignment MUST happen
        // after this call.
        replaceAppsRVContainer(showTabs, showTabs ? pages.size() : 1);
        mUsingTabs = showTabs;
        mPageHolders = showTabs ? pages : new ArrayList<>(List.of(AdapterHolder.MAIN));
        mBoundTabIds = tabIds;

        for (int i = AdapterHolder.FIRST_CUSTOM; i < mAH.size(); i++) {
            mAH.get(i).mRecyclerView = null;
        }
        SparseArray<AllAppsRecyclerView> customRecyclerViews = new SparseArray<>();
        AllAppsRecyclerView mainRecyclerView = null;
        AllAppsRecyclerView workRecyclerView = null;
        if (mUsingTabs) {
            for (int page = 0; page < mPageHolders.size(); page++) {
                int type = mPageHolders.get(page);
                AllAppsRecyclerView rv = (AllAppsRecyclerView) mViewPager.getChildAt(page);
                AdapterHolder holder = mAH.get(type);
                holder.setup(rv, matcherForHolder(holder));
                if (type == AdapterHolder.MAIN) {
                    mainRecyclerView = rv;
                } else if (type == AdapterHolder.WORK) {
                    workRecyclerView = rv;
                } else {
                    customRecyclerViews.put(type, rv);
                }
            }
            if (workRecyclerView != null) {
                workRecyclerView.setId(R.id.apps_list_view_work);
                if (enableExpandingPauseWorkButton()
                        || FeatureFlags.ENABLE_EXPANDING_PAUSE_WORK_BUTTON.get()) {
                    mAH.get(AdapterHolder.WORK).mRecyclerView.addOnScrollListener(
                            mWorkManager.newScrollListener());
                }
            } else {
                mAH.get(AdapterHolder.WORK).mRecyclerView = null;
            }
            bindTabStrip(0);
            mViewPager.getPageIndicator().setActiveMarker(0);
            setDeviceManagementResources();
            if (mHeader.isSetUp()) {
                onActivePageChanged(holderForPage(mViewPager.getNextPage()), false);
            }
        } else {
            mainRecyclerView = findViewById(R.id.apps_list_view);
            mAH.get(AdapterHolder.MAIN).setup(mainRecyclerView, mAllTabMatcher);
            mAH.get(AdapterHolder.WORK).mRecyclerView = null;
        }
        mHeader.setCustomRecyclerViews(customRecyclerViews);
        setUpCustomRecyclerViewPool(
                mainRecyclerView,
                workRecyclerView,
                customRecyclerViews,
                mAllAppsStore.getRecyclerViewPool());
        setupHeader();

        if (isSearchBarFloating()) {
            // Keep the scroller above the search bar.
            RelativeLayout.LayoutParams scrollerLayoutParams =
                    (LayoutParams) mFastScroller.getLayoutParams();
            scrollerLayoutParams.bottomMargin = mSearchContainer.getHeight()
                    + getResources().getDimensionPixelSize(
                            R.dimen.fastscroll_bottom_margin_floating_search);
        }

        forAllRecyclerViews(mAllAppsStore::registerIconContainer);
    }

    // ---- Lawnchair: drawer tabs -------------------------------------------------------------

    /** Component key string used by drawer tab settings, or "" when unknown. */
    private static String drawerKeyOf(ItemInfo info) {
        if (info instanceof AppInfo) {
            return ((AppInfo) info).toComponentKey().toString();
        }
        if (info == null || info.getTargetComponent() == null || info.user == null) {
            return "";
        }
        return new ComponentKey(info.getTargetComponent(), info.user).toString();
    }

    private void applyTabsConfigData() {
        mHiddenFromAll = mTabsConfig.getHiddenFromAll();
        mTabKeys.clear();
        for (DrawerTab tab : mTabsConfig.getActiveTabs()) {
            mTabKeys.put(tab.getId(), new HashSet<>(tab.getApps()));
        }
    }

    private void onTabsConfigChanged(DrawerTabsConfig config) {
        if (config.equals(mTabsConfig)) {
            return;
        }
        Log.d(TAG, "onTabsConfigChanged: active=" + config.isActive()
                + " tabs=" + config.getTabs().size());
        mTabsConfig = config;
        applyTabsConfigData();
        mTabsConfigDirty = true;
        if (isSearching()) {
            mRebindAdaptersAfterSearchAnimation = true;
        } else {
            rebindAdapters();
        }
    }

    /** Makes mAH hold exactly one holder per active custom tab, in tab order. */
    private void syncCustomHolders() {
        List<DrawerTab> tabs = mTabsConfig.getActiveTabs();
        Set<Long> ids = new HashSet<>();
        for (DrawerTab tab : tabs) {
            ids.add(tab.getId());
        }
        Iterator<Map.Entry<Long, AdapterHolder>> it = mCustomHolders.entrySet().iterator();
        while (it.hasNext()) {
            AdapterHolder holder = it.next().getValue();
            if (!ids.contains(holder.mTabId)) {
                if (holder.mRecyclerView != null) {
                    mAllAppsStore.unregisterIconContainer(holder.mRecyclerView);
                    holder.mRecyclerView.setLayoutManager(null);
                    holder.mRecyclerView.setAdapter(null);
                    holder.mRecyclerView = null;
                }
                if (holder.mAppsList instanceof LawnchairAlphabeticalAppsList) {
                    ((LawnchairAlphabeticalAppsList<?>) holder.mAppsList).dispose();
                }
                it.remove();
            }
        }
        while (mAH.size() > AdapterHolder.FIRST_CUSTOM) {
            mAH.remove(mAH.size() - 1);
        }
        for (int i = 0; i < tabs.size(); i++) {
            long id = tabs.get(i).getId();
            int type = AdapterHolder.FIRST_CUSTOM + i;
            AdapterHolder holder = mCustomHolders.get(id);
            if (holder == null) {
                LawnchairAlphabeticalAppsList<T> list = new LawnchairAlphabeticalAppsList<>(
                        mActivityContext, mAllAppsStore, null, null);
                list.isCustomTab = true;
                holder = new AdapterHolder(type, list);
                holder.mTabId = id;
                holder.mPadding.set(mAH.get(AdapterHolder.MAIN).mPadding);
                mCustomHolders.put(id, holder);
            } else {
                holder.mType = type;
            }
            mAH.add(holder);
        }
    }

    private List<Integer> computePageHolders() {
        List<Integer> pages = new ArrayList<>();
        pages.add(AdapterHolder.MAIN);
        int count = mAppsBound ? mTabsConfig.getActiveTabs().size() : 0;
        for (int i = 0; i < count; i++) {
            pages.add(AdapterHolder.FIRST_CUSTOM + i);
        }
        if (mHasWorkApps) {
            pages.add(AdapterHolder.WORK);
        }
        return pages;
    }

    private List<Long> currentTabIds() {
        List<Long> ids = new ArrayList<>();
        for (DrawerTab tab : mTabsConfig.getActiveTabs()) {
            ids.add(tab.getId());
        }
        return ids;
    }

    private Predicate<ItemInfo> matcherForHolder(AdapterHolder holder) {
        if (holder.isMain()) {
            return mAllTabMatcher;
        }
        if (holder.isWork()) {
            return mWorkManager.getItemInfoMatcher();
        }
        final long tabId = holder.mTabId;
        final Predicate<ItemInfo> privateMatcher = mPrivateProfileManager.getItemInfoMatcher();
        return info -> {
            Set<String> keys = mTabKeys.get(tabId);
            return keys != null && !keys.isEmpty()
                    && !privateMatcher.test(info)
                    && keys.contains(drawerKeyOf(info));
        };
    }

    /** Re-applies filters and titles when tab contents change but the page layout does not. */
    private void refreshDrawerTabs() {
        mAH.get(AdapterHolder.MAIN).mAppsList.updateItemFilter(mAllTabMatcher);
        for (int i = AdapterHolder.FIRST_CUSTOM; i < mAH.size(); i++) {
            AdapterHolder holder = mAH.get(i);
            holder.mAppsList.updateItemFilter(matcherForHolder(holder));
        }
        if (mUsingTabs && mViewPager != null) {
            bindTabStrip(mViewPager.getNextPage());
            setDeviceManagementResources();
        }
    }

    /** Page index -> adapter holder type. */
    private int holderForPage(int page) {
        if (mViewPager == null || page < 0 || page >= mPageHolders.size()) {
            return AdapterHolder.MAIN;
        }
        return mPageHolders.get(page);
    }

    /** Adapter holder type -> page index, or -1 if that holder has no page. */
    private int pageForHolder(int holderType) {
        if (mViewPager == null) {
            return holderType == AdapterHolder.MAIN ? 0 : -1;
        }
        return mPageHolders.indexOf(holderType);
    }

    @Nullable
    private CharSequence currentTabTitle() {
        if (mViewPager == null) {
            return null;
        }
        View tab = mViewPager.getPageIndicator().getChildAt(mViewPager.getNextPage());
        return tab instanceof Button ? ((Button) tab).getText() : null;
    }

    /** Builds the tab buttons for the bound pages and wires clicks. */
    private void bindTabStrip(int activePage) {
        PersonalWorkSlidingTabStrip strip = mViewPager.getPageIndicator();
        boolean custom = mTabsConfig.isActive();
        if (custom || !strip.hasStockTabs()) {
            List<CharSequence> titles = new ArrayList<>();
            int[] ids = new int[mPageHolders.size()];
            for (int page = 0; page < mPageHolders.size(); page++) {
                int type = mPageHolders.get(page);
                if (type == AdapterHolder.MAIN) {
                    titles.add(custom ? mTabsConfig.allTabTitle(getContext())
                            : getContext().getString(R.string.all_apps_personal_tab));
                    ids[page] = R.id.tab_personal;
                } else if (type == AdapterHolder.WORK) {
                    titles.add(getContext().getString(R.string.all_apps_work_tab));
                    ids[page] = R.id.tab_work;
                } else {
                    titles.add(tabForHolder(type).getTitle());
                    ids[page] = View.NO_ID;
                }
            }
            strip.setTabs(titles, ids, custom, activePage);
        }
        for (int page = 0; page < strip.getChildCount() && page < mPageHolders.size(); page++) {
            final int target = page;
            final int type = mPageHolders.get(page);
            View tab = strip.getChildAt(page);
            tab.setOnClickListener(v -> {
                Log.d(TAG, "Clicked all apps tab " + target);
                if (mViewPager.snapToPage(target)) {
                    if (type == AdapterHolder.MAIN) {
                        mActivityContext.getStatsLogManager().logger()
                                .log(LAUNCHER_ALLAPPS_TAP_ON_PERSONAL_TAB);
                    } else if (type == AdapterHolder.WORK) {
                        mActivityContext.getStatsLogManager().logger()
                                .log(LAUNCHER_ALLAPPS_TAP_ON_WORK_TAB);
                    }
                }
            });
            Long tabId;
            if (type == AdapterHolder.MAIN) {
                tabId = -1L;
            } else if (type == AdapterHolder.WORK) {
                tabId = null;
            } else {
                tabId = tabForHolder(type).getId();
            }
            onBindDrawerTab(tab, tabId);
        }
    }

    private DrawerTab tabForHolder(int type) {
        return mTabsConfig.getActiveTabs().get(type - AdapterHolder.FIRST_CUSTOM);
    }

    /**
     * Wires long-press on a tab button: opens that tab's editor in settings ("All" opens the
     * tabs list). {@code tabId} is -1 for "All", null for the Work tab, else the custom tab id.
     */
    protected void onBindDrawerTab(View tab, @Nullable Long tabId) {
        if (tabId == null || !mTabsConfig.isActive()) {
            tab.setOnLongClickListener(null);
            tab.setLongClickable(false);
            return;
        }
        final long id = tabId;
        tab.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            DrawerTabsActions.openEditor(getContext(), id);
            return true;
        });
    }

    /**
     * If {@link ENABLE_ALL_APPS_RV_PREINFLATION} is enabled, wire custom
     * {@link RecyclerView.RecycledViewPool} to main and work {@link AllAppsRecyclerView}.
     *
     * Then if {@link ALL_APPS_GONE_VISIBILITY} is enabled, update max pool size. This is because
     * all apps rv's hidden visibility is changed to {@link View#GONE} from {@link View#INVISIBLE),
     * thus we cannot rely on layout pass to update pool size.
     */
    private static void setUpCustomRecyclerViewPool(
            @NonNull AllAppsRecyclerView mainRecyclerView,
            @Nullable AllAppsRecyclerView workRecyclerView,
            @NonNull SparseArray<AllAppsRecyclerView> customRecyclerViews,
            @NonNull AllAppsRecyclerViewPool recycledViewPool) {
        // Lawnchair: custom drawer tabs share the pool, sized as for two lists.
        final boolean hasWorkProfile = workRecyclerView != null || customRecyclerViews.size() > 0;
        recycledViewPool.setHasWorkProfile(hasWorkProfile);
        mainRecyclerView.setRecycledViewPool(recycledViewPool);
        if (workRecyclerView != null) {
            workRecyclerView.setRecycledViewPool(recycledViewPool);
        }
        for (int i = 0; i < customRecyclerViews.size(); i++) {
            customRecyclerViews.valueAt(i).setRecycledViewPool(recycledViewPool);
        }
        mainRecyclerView.updatePoolSize(hasWorkProfile);
    }

    private void replaceAppsRVContainer(boolean showTabs, int pageCount) {
        Log.d(TAG, "replaceAppsRVContainer: showTabs: " + showTabs + " pages: " + pageCount);
        for (int i = 0; i < mAH.size(); i++) {
            AdapterHolder adapterHolder = mAH.get(i);
            if (i != AdapterHolder.SEARCH && adapterHolder.mRecyclerView != null) {
                adapterHolder.mRecyclerView.setLayoutManager(null);
                adapterHolder.mRecyclerView.setAdapter(null);
            }
        }
        View oldView = getAppsRecyclerViewContainer();
        int index = indexOfChild(oldView);
        removeView(oldView);
        int layout = showTabs ? R.layout.all_apps_tabs : R.layout.all_apps_rv_layout;
        final View rvContainer = getLayoutInflater().inflate(layout, this, false);
        if (showTabs && ((ViewGroup) rvContainer).getChildCount() != pageCount) {
            // Lawnchair: one recycler view per drawer tab page.
            ViewGroup pager = (ViewGroup) rvContainer;
            pager.removeAllViews();
            for (int i = 0; i < pageCount; i++) {
                getLayoutInflater().inflate(R.layout.all_apps_rv_layout, pager, true);
            }
        }
        addView(rvContainer, index);
        if (showTabs) {
            mViewPager = (AllAppsPagedView) rvContainer;
            mViewPager.initParentViews(this);
            mViewPager.getPageIndicator().setOnActivePageChangedListener(this);
            mViewPager.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    @Px final int bottomOffsetPx =
                            (int) (ActivityAllAppsContainerView.this.getMeasuredHeight()
                                    * PREDICTIVE_BACK_MIN_SCALE);
                    outline.setRect(
                            0,
                            0,
                            view.getMeasuredWidth(),
                            view.getMeasuredHeight() + bottomOffsetPx);
                }
            });

            mWorkManager.reset();
            post(() -> mAH.get(AdapterHolder.WORK).applyPadding());
        } else {
            mWorkManager.detachWorkUtilityViews();
            mViewPager = null;
        }

        removeCustomRules(rvContainer);
        removeCustomRules(getSearchRecyclerView());
        if (isAppDrawerSearchBarHidden()) {
            layoutWithoutSearchContainer(rvContainer, showTabs);
            layoutWithoutSearchContainer(getSearchRecyclerView(), /* tabs= */ false);
        } else if (isSearchBarFloating()) {
            alignParentTop(rvContainer, showTabs);
            alignParentTop(getSearchRecyclerView(), /* tabs= */ false);
        } else {
            layoutBelowSearchContainer(rvContainer, showTabs);
            layoutBelowSearchContainer(getSearchRecyclerView(), /* tabs= */ false);
        }

        updateSearchResultsVisibility();
    }

    /**
     * Sets up header rows and hides the header shell when neither tabs nor visible row content
     * requires it, keeping adapter top padding consistent with that visibility.
     */
    void setupHeader() {
        mAdditionalHeaderRows.forEach(row -> mHeader.onPluginDisconnected(row));

        boolean hideSearchBar = isAppDrawerSearchBarHidden();
        boolean tabsHidden = !mUsingTabs;
        mHeader.setup(
                mAH.get(AdapterHolder.MAIN).mRecyclerView,
                mAH.get(AdapterHolder.WORK).mRecyclerView,
                (SearchRecyclerView) mAH.get(SEARCH).mRecyclerView,
                getCurrentPage(),
                tabsHidden);

        // Keep personal/work tabs or visible rows active; hide the empty header shell.
        boolean hasHeaderContent = mUsingTabs || (!hideSearchBar && mHeader.hasVisibleContent());
        mHeader.setVisibility(hasHeaderContent ? View.VISIBLE : View.GONE);

        int padding = hasHeaderContent ? mHeader.getMaxTranslation() : 0;
        mAH.forEach(adapterHolder -> {
            adapterHolder.mPadding.top = padding;
            adapterHolder.applyPadding();
            if (adapterHolder.mRecyclerView != null) {
                adapterHolder.mRecyclerView.scrollToTop();
            }
        });
        mAdditionalHeaderRows.forEach(row -> mHeader.onPluginConnected(row, mActivityContext));

        removeCustomRules(mHeader);
        if (hideSearchBar) {
            layoutWithoutSearchContainer(mHeader, false /* includeTabsMargin */);
        } else if (isSearchBarFloating()) {
            alignParentTop(mHeader, false /* includeTabsMargin */);
        } else {
            layoutBelowSearchContainer(mHeader, false /* includeTabsMargin */);
        }
    }

    /**
     * Force header height update with an offset. Used by {@link UniversalSearchInputView} to
     * request {@link FloatingHeaderView} to update its maxTranslation for multiline search bar.
     */
    public void forceUpdateHeaderHeight(int offset) {
        mHeader.updateSearchBarOffset(offset);
    }

    @Override
    public void addChildrenForAccessibility(ArrayList<View> arrayList) {
        super.addChildrenForAccessibility(arrayList);
        if (!Flags.floatingSearchBar()) {
            // Searchbox container is visually at the top of the all apps UI but it's present in
            // end of the children list.
            // We need to move the searchbox to the top in a11y tree for a11y services to read the
            // all apps screen in same as visual order.
            arrayList.stream().filter(v -> v.getId() == R.id.search_container_all_apps)
                    .findFirst().ifPresent(v -> {
                        arrayList.remove(v);
                        arrayList.add(0, v);
                    });
        }
    }

    protected void updateHeaderScroll(int scrolledOffset) {
        if (isAppDrawerSearchBarHidden() && !mUsingTabs)
            return;
        
        // Check if tab container background should be shown
        boolean showTabContainerBackground = PreferenceCacheExtensionsKt.firstCached(
                pref2.getWorkProfileTabContainerBackground(), pref2);
        
        float prog = Utilities.boundToRange((float) scrolledOffset / mHeaderThreshold, 0f, 1f);
        int headerColor = getHeaderColor(prog);
        int tabsAlpha = (!showTabContainerBackground || mHeader.getPeripheralProtectionHeight(/* expectedHeight */ false) == 0) ? 0
                : (int) (Utilities.boundToRange(
                        (scrolledOffset + mHeader.mSnappedScrolledY) / mHeaderThreshold, 0f, 1f)
                        * 255);
        if (headerColor != mHeaderColor || mTabsProtectionAlpha != tabsAlpha) {
            mHeaderColor = headerColor;
            mTabsProtectionAlpha = tabsAlpha;
            invalidateHeader();
        }
        if (mSearchUiManager.getEditText() == null) {
            return;
        }

        boolean bgVisible = mSearchUiManager.getBackgroundVisibility();
        if (scrolledOffset == 0) {
            if (!isSearching()) {
                bgVisible = true;
            }
            // LC-Note: Match Pixel Launcher behavior by focusing
            // and showing the keyboard on scroll to top
            if (PreferenceCacheExtensionsKt.firstCached(pref2.getAutoShowKeyboardInDrawer())) {
                boolean isControllerAnimating = mAllAppsTransitionController != null
                        && (mAllAppsTransitionController.getProgress() > 0f
                        || mAllAppsTransitionController.getAllAppScale().isAnimating());
                boolean isSearchTransitioning = mSearchTransitionController.isRunning()
                        || mSearchExitInProgress;
                if (!isControllerAnimating && !isSearchTransitioning) {
                    ExtendedEditText editText = mSearchUiManager.getEditText();
                    if (editText != null && !editText.isFocused()) {
                        editText.showKeyboard();
                    }
                }
            }
        } else if (scrolledOffset > mHeaderThreshold) {
            bgVisible = false;
        }
        mSearchUiManager.setBackgroundVisibility(bgVisible, 1 - prog);
    }

    protected int getHeaderColor(float blendRatio) {
        if (!mActivityContext.getDeviceProfile().shouldShowAllAppsOnSheet()) {
            float opacity = mSearchContainer.getAlpha();
            var showHeaderBackground = PreferenceCacheExtensionsKt.firstCached(
                pref2.getAppDrawerSearchBarBackground(), pref2);
            if (showHeaderBackground) {
                opacity = pref.getDrawerOpacity().get();
            }
            opacity = MathUtils.clamp(opacity, 0f, 1f);
            return ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(getBackgroundColor(), mHeaderProtectionColor, blendRatio),
                    Math.round(opacity * 255));
        }
        return isBackgroundBlurEnabled()
                ? ColorUtils.setAlphaComponent(mHeaderProtectionColor, (int) (blendRatio * 255))
                : ColorUtils.blendARGB(getBackgroundColor(), mHeaderProtectionColor, blendRatio);
    }

    private int getBackgroundColor() {
        return mActivityContext.getDeviceProfile().shouldShowAllAppsOnSheet()
                ? getBottomSheetBackgroundColor() : mScrimColor;
    }

    // LC-Note: Hey! We cache this! see updateBottomSheetBackgroundColor() for more details.
    int getBottomSheetBackgroundColor() {
        return mCachedBottomSheetBgColor;
    }

    // LC-Note: This is getBottomSheetBackgroundColor() in AOSP, but we refactor it to cache our prefs.
    private boolean updateBottomSheetBackgroundColor() {
        return updateBottomSheetBackgroundColor(mActivityContext.isAllAppsBackgroundBlurEnabled());
    }

    // LC-Note: For listener to avoid querying stale value.
    private boolean updateBottomSheetBackgroundColor(boolean blurEnabled) {
        int defaultColor;
        if (!Flags.allAppsBlur()) {
            defaultColor = mBottomSheetBackgroundColorLegacy;
        } else if (!blurEnabled) {
            defaultColor = mBottomSheetBackgroundColorBlurFallback;
        } else {
            defaultColor = mBottomSheetBackgroundColorOverBlur;
        }
        int newColor = LawnchairUtilsKt.getAllAppsBackgroundColor(mActivityContext, defaultColor);
        if (mCachedBottomSheetBgColor != newColor) {
            mCachedBottomSheetBgColor = newColor;
            return true;
        }
        return false;
    }

    boolean isBackgroundBlurEnabled() {
        return Flags.allAppsBlur() && mActivityContext.isAllAppsBackgroundBlurEnabled();
    }

    /**
     * @return true if the search bar is floating above this container (at the bottom of the screen)
     */
    protected boolean isSearchBarFloating() {
        return mSearchUiDelegate.isSearchBarFloating();
    }

    /**
     * Whether the <em>floating</em> search bar should appear as a small pill when not focused.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public boolean shouldFloatingSearchBarBePillWhenUnfocused() {
        return false;
    }

    /**
     * How far from the bottom of the screen the <em>floating</em> search bar should rest when the
     * IME is not present.
     * <p>
     * To hide offscreen, use a negative value.
     * <p>
     * Note: if the provided value is non-negative but less than the current bottom insets, the
     * insets will be applied. As such, you can use 0 to default to this.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginBottom() {
        return 0;
    }

    /**
     * How far from the start of the screen the <em>floating</em> search bar should rest.
     * <p>
     * To use original margin, return a negative value.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginStart() {
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin(mActivityContext);
    }

    /**
     * How far from the end of the screen the <em>floating</em> search bar should rest.
     * <p>
     * To use original margin, return a negative value.
     * <p>
     * Note: This method mirrors one in LauncherState. For subclasses that use Launcher, it likely
     * makes sense to use that method to derive an appropriate value for the current/target state.
     */
    public int getFloatingSearchBarRestingMarginEnd() {
        DeviceProfile dp = mActivityContext.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin(mActivityContext);
    }

    private boolean isAppDrawerSearchBarHidden() {
        return PreferenceCacheExtensionsKt.firstCached(pref2.getHideAppDrawerSearchBar());
    }

    private void layoutBelowSearchContainer(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_TOP, R.id.search_container_all_apps);

        int topMargin = getContext().getResources().getDimensionPixelSize(
                R.dimen.all_apps_header_top_margin);
        if (includeTabsMargin) {
            topMargin += getContext().getResources().getDimensionPixelSize(
                    R.dimen.all_apps_header_pill_height);
        }
        layoutParams.topMargin = topMargin;
    }

    private void alignParentTop(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        layoutParams.topMargin =
                includeTabsMargin
                        ? getContext().getResources().getDimensionPixelSize(
                        R.dimen.all_apps_header_pill_height)
                        : 0;
    }

    private void removeCustomRules(View v) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.removeRule(RelativeLayout.ABOVE);
        layoutParams.removeRule(RelativeLayout.ALIGN_TOP);
        layoutParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
    }

    private void layoutWithoutSearchContainer(View v, boolean includeTabsMargin) {
        if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        RelativeLayout.LayoutParams layoutParams = (LayoutParams) v.getLayoutParams();
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        int topMargin = 0;
        if (mActivityContext.getDeviceProfile().shouldShowAllAppsOnSheet()) {
            // Clear the bottom-sheet drag handle when search is not reserving that space.
            topMargin = getContext().getResources().getDimensionPixelSize(
                    R.dimen.bottom_sheet_handle_area_height);
        }
        if (includeTabsMargin) {
            topMargin += getContext().getResources().getDimensionPixelSize(
                    R.dimen.all_apps_header_pill_height);
        }
        layoutParams.topMargin = topMargin;
    }

    protected BaseAllAppsAdapter<T> createAdapter(AlphabeticalAppsList<T> appsList) {
        return new AllAppsGridAdapter<>(mActivityContext, getLayoutInflater(), appsList,
                mMainAdapterProvider);
    }

    public boolean isInAllApps() {
        // TODO: Make this abstract
        return true;
    }

    /** Creates the adapter provider for the main section. */
    protected SearchAdapterProvider<?> createMainAdapterProvider() {
        return new DefaultSearchAdapterProvider(mActivityContext);
    }

    /**
     * Inflates the search bar
     */
    protected View inflateSearchBar() {
        return mSearchUiDelegate.inflateSearchBar();
    }

    /** The adapter provider for the main section. */
    public final SearchAdapterProvider<?> getMainAdapterProvider() {
        return mMainAdapterProvider;
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> sparseArray) {
        try {
            // Many slice view id is not properly assigned, and hence throws null
            // pointer exception in the underneath method. Catching the exception
            // simply doesn't restore these slice views. This doesn't have any
            // user visible effect because because we query them again.
            super.dispatchRestoreInstanceState(sparseArray);
        } catch (Exception e) {
            Log.e("AllAppsContainerView", "restoreInstanceState viewId = 0", e);
        }

        Bundle state = (Bundle) sparseArray.get(R.id.work_tab_state_id, null);
        if (state != null) {
            int currentPage = state.getInt(BUNDLE_KEY_CURRENT_PAGE, 0);
            int page = pageForHolder(currentPage);
            if (currentPage != AdapterHolder.MAIN && page > 0 && mViewPager != null) {
                mViewPager.setCurrentPage(page);
                rebindAdapters();
            } else {
                reset(true);
            }
        }
    }

    @Override
    protected void dispatchSaveInstanceState(SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);
        Bundle state = new Bundle();
        state.putInt(BUNDLE_KEY_CURRENT_PAGE, getCurrentPage());
        container.put(R.id.work_tab_state_id, state);
    }

    public AllAppsStore<T> getAppsStore() {
        return mAllAppsStore;
    }

    public WorkProfileManager getWorkManager() {
        return mWorkManager;
    }

    /** Returns whether Private Profile has been setup. */
    public boolean hasPrivateProfile() {
        return mHasPrivateApps;
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        for (AdapterHolder holder : mAH) {
            holder.mAdapter.setAppsPerRow(dp.numShownAllAppsColumns);
            holder.mAppsList.setNumAppsPerRowAllApps(dp.numShownAllAppsColumns);
            if (holder.mRecyclerView != null) {
                // Remove all views and clear the pool, while keeping the data same. After this
                // call, all the viewHolders will be recreated.
                holder.mRecyclerView.swapAdapter(holder.mRecyclerView.getAdapter(), true);
                holder.mRecyclerView.getRecycledViewPool().clear();
            }
        }
        updateBackgroundVisibility(dp);

        boolean needsInvalidate = false;
        int navBarScrimColor = Themes.getNavBarScrimColor(mActivityContext);
        if (mNavBarScrimPaint.getColor() != navBarScrimColor) {
            mNavBarScrimPaint.setColor(navBarScrimColor);
            needsInvalidate = true;
        }
        // LC-Note: Update our allapps cached colour
        if (updateBottomSheetBackgroundColor()) {
            needsInvalidate = true;
        }

        if (needsInvalidate) {
            invalidate();
        }
    }

    protected void updateBackgroundVisibility(DeviceProfile deviceProfile) {
        mBottomSheetBackground.setVisibility(
                deviceProfile.shouldShowAllAppsOnSheet() ? View.VISIBLE : View.GONE);
        // Note: The opaque sheet background and header protection are added in drawOnScrim.
        // For the taskbar entrypoint, the scrim is drawn by its abstract slide in view container,
        // so its header protection is derived from this scrim instead.
    }

    @VisibleForTesting
    public void onAppsUpdated() {
        Log.d(TAG, "onAppsUpdated; number of apps: " + mAllAppsStore.getApps().length);
        mAppsBound = true;
        mHasWorkApps = Stream.of(mAllAppsStore.getApps())
                .anyMatch(mWorkManager.getItemInfoMatcher());
        mHasPrivateApps = Stream.of(mAllAppsStore.getApps())
                .anyMatch(mPrivateProfileManager.getItemInfoMatcher());
        if (!isSearching()) {
            rebindAdapters();
        }
        if (mHasWorkApps) {
            mWorkManager.reset();
        }
        if (mHasPrivateApps) {
            mPrivateProfileManager.reset();
        }

        mActivityContext.getStatsLogManager().logger()
                .withCardinality(mAllAppsStore.getApps().length)
                .log(LAUNCHER_ALLAPPS_COUNT);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The AllAppsContainerView houses the QSB and is hence visible from the Workspace
        // Overview states. We shouldn't intercept for the scrubber in these cases.
        if (!isInAllApps()) {
            mTouchHandler = null;
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            AllAppsRecyclerView rv = getActiveRecyclerView();
            if (rv != null && rv.getScrollbar() != null
                    && rv.getScrollbar().isHitInParent(ev.getX(), ev.getY(), mFastScrollerOffset)) {
                mTouchHandler = rv.getScrollbar();
            } else {
                mTouchHandler = null;
            }
        }
        if (mTouchHandler != null) {
            return mTouchHandler.handleTouchEvent(ev, mFastScrollerOffset);
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isInAllApps()) {
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            AllAppsRecyclerView rv = getActiveRecyclerView();
            if (rv != null && rv.getScrollbar() != null
                    && rv.getScrollbar().isHitInParent(ev.getX(), ev.getY(), mFastScrollerOffset)) {
                mTouchHandler = rv.getScrollbar();
            } else {
                mTouchHandler = null;

            }
        }
        if (mTouchHandler != null) {
            mTouchHandler.handleTouchEvent(ev, mFastScrollerOffset);
            return true;
        }
        if (isSearching()
                && mActivityContext.getDragLayer().isEventOverView(getVisibleContainerView(), ev)) {
            // if in search state, consume touch event.
            return true;
        }
        return false;
    }

    /** The current active recycler view (A-Z list from one of the profiles, or search results). */
    public AllAppsRecyclerView getActiveRecyclerView() {
        if (isSearching()) {
            return getSearchRecyclerView();
        }
        return getActiveAppsRecyclerView();
    }

    /** Run some code on all the recycler views. */
    protected void forAllRecyclerViews(Consumer<AllAppsRecyclerView> consumer) {
        for (AdapterHolder holder : mAH) {
            if (holder.mRecyclerView == null) {
                continue;
            }
            consumer.accept(holder.mRecyclerView);
        }
    }

    /** The current focus change listener in the search container. */
    public OnFocusChangeListener getSearchFocusChangeListener() {
        return mAH.get(AdapterHolder.SEARCH).mOnFocusChangeListener;
    }

    /** The current apps recycler view in the container. */
    private AllAppsRecyclerView getActiveAppsRecyclerView() {
        if (!mUsingTabs || mViewPager == null || isPersonalTab()) {
            return mAH.get(AdapterHolder.MAIN).mRecyclerView;
        }
        AllAppsRecyclerView rv =
                mAH.get(holderForPage(mViewPager.getNextPage())).mRecyclerView;
        return rv != null ? rv : mAH.get(AdapterHolder.MAIN).mRecyclerView;
    }

    /**
     * The container for A-Z apps (the ViewPager for main+work tabs, or main RV). This is currently
     * hidden while searching.
     */
    public ViewGroup getAppsRecyclerViewContainer() {
        return mViewPager != null ? mViewPager : findViewById(R.id.apps_list_view);
    }

    /** The RV for search results, which is hidden while A-Z apps are visible. */
    public SearchRecyclerView getSearchRecyclerView() {
        return mSearchRecyclerView;
    }

    protected boolean isPersonalTab() {
        return mViewPager == null
                || holderForPage(mViewPager.getNextPage()) == AdapterHolder.MAIN;
    }

    /**
     * Switches the current page to the provided {@code tab} if tabs are supported, otherwise does
     * nothing.
     */
    public void switchToTab(int tab) {
        int page = pageForHolder(tab);
        if (mUsingTabs && page >= 0) {
            mViewPager.setCurrentPage(page);
        }
    }

    public LayoutInflater getLayoutInflater() {
        return mSearchUiDelegate.getLayoutInflater();
    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean success) {}

    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);
        DeviceProfile grid = mActivityContext.getDeviceProfile();

        applyAdapterSideAndBottomPaddings(grid);

        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        // Ignore left/right insets on tablet because we are already centered in-screen.
        if (grid.getDeviceProperties().isTablet()) {
            mlp.leftMargin = mlp.rightMargin = 0;
        } else {
            mlp.leftMargin = insets.left;
            mlp.rightMargin = insets.right;
        }
        setLayoutParams(mlp);

        if (!grid.isVerticalBarLayout() || FeatureFlags.enableResponsiveWorkspace()) {
            int topPadding = grid.allAppsPadding.top;
            if (isSearchBarFloating() && !grid.shouldShowAllAppsOnSheet()) {
                topPadding += getResources().getDimensionPixelSize(
                        R.dimen.all_apps_additional_top_padding_floating_search);
            }
            setPadding(grid.allAppsLeftRightMargin, topPadding, grid.allAppsLeftRightMargin, 0);
        }
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    /**
     * Returns a padding in case a scrim is shown on the bottom of the view and a padding is needed.
     */
    protected int computeNavBarScrimHeight(WindowInsets insets) {
        return 0;
    }

    /**
     * Returns the current height of nav bar scrim
     */
    public int getNavBarScrimHeight() {
        return mNavBarScrimHeight;
    }

    @Override
    public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
        mNavBarScrimHeight = computeNavBarScrimHeight(insets);
        applyAdapterSideAndBottomPaddings(mActivityContext.getDeviceProfile());
        return super.dispatchApplyWindowInsets(insets);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (mNavBarScrimHeight > 0) {
            float left = (getWidth() - getWidth() / getScaleX()) / 2;
            float top = getHeight() / 2f + (getHeight() / 2f - mNavBarScrimHeight) / getScaleY();
            canvas.drawRect(left, top, getWidth() / getScaleX(),
                    top + mNavBarScrimHeight / getScaleY(), mNavBarScrimPaint);
        }
    }

    /**
     * Shows search results without the header, or restores the header only when tabs or visible
     * header rows provide content.
     */
    protected void updateSearchResultsVisibility() {
        if (isSearching()) {
            getSearchRecyclerView().setVisibility(VISIBLE);
            getAppsRecyclerViewContainer().setVisibility(GONE);
            mHeader.setVisibility(GONE);
        } else {
            getSearchRecyclerView().setVisibility(GONE);
            getAppsRecyclerViewContainer().setVisibility(VISIBLE);
            // Keep personal/work tabs or visible rows active; hide the empty header shell.
            boolean hasHeaderContent = mUsingTabs
                    || (!isAppDrawerSearchBarHidden() && mHeader.hasVisibleContent());
            mHeader.setVisibility(hasHeaderContent ? View.VISIBLE : View.GONE);
        }
        if (mHeader.isSetUp()) {
            mHeader.setActiveRV(getCurrentPage());
        }
    }

    private void applyAdapterSideAndBottomPaddings(DeviceProfile grid) {
        int bottomPadding = Math.max(mInsets.bottom, mNavBarScrimHeight);
        mAH.forEach(adapterHolder -> {
            adapterHolder.mPadding.bottom = bottomPadding;
            adapterHolder.mPadding.left = grid.allAppsPadding.left;
            adapterHolder.mPadding.right = grid.allAppsPadding.right;
            adapterHolder.applyPadding();
        });
    }

    private void setDeviceManagementResources() {
        if (mActivityContext.getStringCache() != null) {
            Button personalTab = findViewById(R.id.tab_personal);
            if (personalTab != null) {
                if (mTabsConfig.isActive()) {
                    personalTab.setText(mTabsConfig.allTabTitle(getContext()));
                } else {
                    personalTab.setText(R.string.all_apps_personal_tab);
                }
                personalTab.setAllCaps(false);
                FontManager.INSTANCE.get(getContext()).setCustomFont(personalTab, R.id.font_button);
            }

            Button workTab = findViewById(R.id.tab_work);
            if (workTab != null) {
                workTab.setText(R.string.all_apps_work_tab);
                workTab.setAllCaps(false);
                FontManager.INSTANCE.get(getContext()).setCustomFont(workTab, R.id.font_button);
            }
        }
    }

    /**
     * Returns true if the container has work apps.
     */
    public boolean shouldShowTabs() {
        return mHasWorkApps || (mAppsBound && mTabsConfig.isActive());
    }

    // Used by tests only
    private boolean isDescendantViewVisible(int viewId) {
        final View view = findViewById(viewId);
        if (view == null) return false;

        if (!view.isShown()) return false;

        return view.getGlobalVisibleRect(new Rect());
    }

    /** Called in Launcher#bindStringCache() to update the UI when cache is updated. */
    public void updateWorkUI() {
        setDeviceManagementResources();
        if (mWorkManager.getWorkUtilityView() != null) {
            mWorkManager.getWorkUtilityView().updateStringFromCache();
        }
        inflateWorkCardsIfNeeded();
    }

    private void inflateWorkCardsIfNeeded() {
        AllAppsRecyclerView workRV = mAH.get(AdapterHolder.WORK).mRecyclerView;
        if (workRV != null) {
            for (int i = 0; i < workRV.getChildCount(); i++) {
                View currentView  = workRV.getChildAt(i);
                int currentItemViewType = workRV.getChildViewHolder(currentView).getItemViewType();
                if (currentItemViewType == VIEW_TYPE_WORK_EDU_CARD) {
                    ((WorkEduCard) currentView).updateStringFromCache();
                } else if (currentItemViewType == VIEW_TYPE_WORK_DISABLED_CARD) {
                    ((WorkPausedCard) currentView).updateStringFromCache();
                }
            }
        }
    }

    @VisibleForTesting
    public void setWorkManager(WorkProfileManager workManager) {
        mWorkManager = workManager;
    }

    @VisibleForTesting
    public boolean isPersonalTabVisible() {
        return isDescendantViewVisible(R.id.tab_personal);
    }

    @VisibleForTesting
    public boolean isWorkTabVisible() {
        return isDescendantViewVisible(R.id.tab_work);
    }

    public AlphabeticalAppsList<T> getSearchResultList() {
        return mAH.get(SEARCH).mAppsList;
    }

    public AlphabeticalAppsList<T> getPersonalAppList() {
        return mAH.get(MAIN).mAppsList;
    }

    public AlphabeticalAppsList<T> getWorkAppList() {
        return mAH.get(WORK).mAppsList;
    }

    public FloatingHeaderView getFloatingHeaderView() {
        return mHeader;
    }

    @VisibleForTesting
    public View getContentView() {
        return isSearching() ? getSearchRecyclerView() : getAppsRecyclerViewContainer();
    }

    /** The current page visible in all apps. */
    public int getCurrentPage() {
        return isSearching()
                ? SEARCH
                : mViewPager == null ? AdapterHolder.MAIN : holderForPage(mViewPager.getNextPage());
    }

    public PrivateProfileManager getPrivateProfileManager() {
        return mPrivateProfileManager;
    }

    /**
     * Adds an update listener to animator that adds springs to the animation.
     */
    public void addSpringFromFlingUpdateListener(ValueAnimator animator,
            float velocity /* release velocity */,
            float progress /* portion of the distance to travel*/) {
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                float distance = (1 - progress) * getHeight(); // px
                float settleVelocity = Math.min(0, distance
                        / (AllAppsTransitionController.INTERP_COEFF * animator.getDuration())
                        + velocity);
                absorbSwipeUpVelocity(Math.max(1000, Math.abs(
                        Math.round(settleVelocity * FLING_VELOCITY_MULTIPLIER))));
            }
        });
    }

    /** Invoked when the container is pulled. */
    public void onPull(float deltaDistance, float displacement) {
        absorbPullDeltaDistance(PULL_MULTIPLIER * deltaDistance, PULL_MULTIPLIER * displacement);
        // Current motion spec is to actually push and not pull
        // on this surface. However, until EdgeEffect.onPush (b/190612804) is
        // implemented at view level, we will simply pull
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        outRect.offset(0, (int) getTranslationY());
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        invalidateHeader();
    }

    @Override
    public void setScaleY(float scaleY) {
        super.setScaleY(scaleY);
        try {
            if (predictiveBackThreeButtonNav() && mNavBarScrimHeight > 0) {
                // Call invalidate to prevent navbar scrim from scaling. The navbar scrim is drawn
                // directly onto the canvas. To prevent it from being scaled with the canvas, there's a
                // counter scale applied in dispatchDraw.
                invalidate(20, getHeight() - mNavBarScrimHeight, getWidth(), getHeight());
            }
        } catch (Throwable t) {
            // LC-Ignored
        }
    }

    /**
     * Set {@link Animator.AnimatorListener} on {@link mAllAppsTransitionController} to observe
     * animation of backing out of all apps search view to all apps view.
     */
    public void setAllAppsSearchBackAnimatorListener(Animator.AnimatorListener listener) {
        Preconditions.assertNotNull(mAllAppsTransitionController);
        if (mAllAppsTransitionController == null) {
            return;
        }
        mAllAppsTransitionController.setAllAppsSearchBackAnimationListener(listener);
    }

    public void setScrimView(ScrimView scrimView) {
        mScrimView = scrimView;
    }

    @Override
    public void drawOnScrimWithScaleAndBottomOffset(
            Canvas canvas, float scale, @Px int bottomOffsetPx) {
        final View panel = mBottomSheetBackground;
        final boolean hasBottomSheet = panel.getVisibility() == VISIBLE;
        final float translationY = ((View) panel.getParent()).getTranslationY();

        final float horizontalScaleOffset = (1 - scale) * panel.getWidth() / 2;
        final float verticalScaleOffset = (1 - scale) * (panel.getHeight() - getHeight() / 2);
        // Left and right insets can be applied to this container, as well as the panel.
        float left = getLeft() + panel.getLeft();
        float right = left + panel.getWidth();

        final float topNoScale = panel.getTop() + translationY;
        final float topWithScale = topNoScale + verticalScaleOffset;
        final float leftWithScale = left + horizontalScaleOffset;
        final float rightWithScale = right - horizontalScaleOffset;
        final float bottomWithOffset = panel.getBottom() + bottomOffsetPx;
        // Draw full background panel if presenting on a sheet.
        int bottomSheetBackgroundColor = getBottomSheetBackgroundColor();
        float bottomSheetBackgroundAlpha = Color.alpha(bottomSheetBackgroundColor) / 255.0f;
        if (hasBottomSheet) {
            mHeaderPaint.setColor(bottomSheetBackgroundColor);
            mHeaderPaint.setAlpha((int) (bottomSheetBackgroundAlpha * 255));

            mTmpRectF.set(
                    leftWithScale,
                    topWithScale,
                    rightWithScale,
                    bottomWithOffset);
            mTmpPath.reset();
            mTmpPath.addRoundRect(mTmpRectF, mBottomSheetCornerRadii, Direction.CW);
            canvas.drawPath(mTmpPath, mHeaderPaint);

            // When the background panel is blurred (or fallback), we don't add header protection.
            // TODO (b/414671116): Apply header protection whenever search bar is focused.
            if (Flags.allAppsBlur()) {
                return;
            }
        }

        if (DEBUG_HEADER_PROTECTION) {
            mHeaderPaint.setColor(Color.MAGENTA);
            mHeaderPaint.setAlpha(255);
        } else {
            mHeaderPaint.setColor(mHeaderColor);
            mHeaderPaint.setAlpha((int) (getAlpha() * Color.alpha(mHeaderColor)));
        }

        // If header is not visible or only differs from the background with alpha, don't draw it.
        int headerWithoutAlpha = ColorUtils.setAlphaComponent(mHeaderPaint.getColor(), 0);
        int backgroundWithoutAlpha = ColorUtils.setAlphaComponent(getBackgroundColor(), 0);
        if (headerWithoutAlpha == backgroundWithoutAlpha || mHeaderPaint.getColor() == 0) {
            return;
        }

        if (hasBottomSheet) {
            mHeaderPaint.setAlpha((int) (mHeaderPaint.getAlpha() * bottomSheetBackgroundAlpha));
        }

        // Draw header on background panel
        final float headerBottomNoScale =
                getHeaderBottom() + getVisibleContainerView().getPaddingTop();
        final float headerHeightNoScale = headerBottomNoScale - topNoScale;
        final float headerBottomWithScaleOnTablet = topWithScale + headerHeightNoScale * scale;
        final float headerBottomOffset = (getVisibleContainerView().getHeight() * (1 - scale) / 2);
        final float headerBottomWithScaleOnPhone = headerBottomNoScale * scale + headerBottomOffset;
        final FloatingHeaderView headerView = getFloatingHeaderView();
        if (hasBottomSheet) {
            // Start adding header protection if search bar or tabs will attach to the top.
            if (!isSearchBarFloating() || mUsingTabs) {
                mTmpRectF.set(
                        leftWithScale,
                        topWithScale,
                        rightWithScale,
                        headerBottomWithScaleOnTablet);
                mTmpPath.reset();
                mTmpPath.addRoundRect(mTmpRectF, mBottomSheetCornerRadii, Direction.CW);
                canvas.drawPath(mTmpPath, mHeaderPaint);
            }
        } else {
            canvas.drawRect(0, 0, canvas.getWidth(), headerBottomWithScaleOnPhone, mHeaderPaint);
        }

        // If tab exist (such as work profile), extend header with tab height
        final int tabsHeight = headerView.getPeripheralProtectionHeight(/* expectedHeight */ false);
        if (mTabsProtectionAlpha > 0 && tabsHeight != 0) {
            if (DEBUG_HEADER_PROTECTION) {
                mHeaderPaint.setColor(Color.BLUE);
                mHeaderPaint.setAlpha(255);
            } else {
                float tabAlpha = getAlpha() * mTabsProtectionAlpha;
                if (hasBottomSheet) {
                    tabAlpha *= bottomSheetBackgroundAlpha;
                }
                mHeaderPaint.setAlpha((int) tabAlpha);
            }
            left = 0f;
            right = canvas.getWidth();
            if (hasBottomSheet) {
                left = leftWithScale;
                right = rightWithScale;
            }

            final float tabTopWithScale = hasBottomSheet
                    ? headerBottomWithScaleOnTablet
                    : headerBottomWithScaleOnPhone;
            final float tabBottomWithScale = tabTopWithScale + tabsHeight * scale;

            canvas.drawRect(
                    left,
                    tabTopWithScale,
                    right,
                    tabBottomWithScale,
                    mHeaderPaint);
        }
    }

    /**
     * The height of the header protection as if the user scrolled down the app list.
     */
    float getHeaderProtectionHeight() {
        float headerBottom = getHeaderBottom() - getTranslationY();
        if (mUsingTabs) {
            return headerBottom + mHeader.getPeripheralProtectionHeight(/* expectedHeight */ true);
        } else {
            return headerBottom;
        }
    }

    ConstraintLayout getFastScrollerLetterList() {
        return mFastScrollLetterLayout;
    }

    /**
     * redraws header protection
     */
    public void invalidateHeader() {
        if (mScrimView != null) {
            mScrimView.invalidate();
        }
    }

    /** Returns the position of the bottom edge of the header */
    public int getHeaderBottom() {
        int bottom = (int) getTranslationY() + mHeader.getClipTop();
        if (isSearchBarFloating()) {
            if (mActivityContext.getDeviceProfile().shouldShowAllAppsOnSheet()) {
                return bottom + mBottomSheetBackground.getTop();
            }
            return bottom;
        }
        return bottom + mHeader.getTop();
    }

    boolean isUsingTabs() {
        return mUsingTabs;
    }

    /**
     * Returns a view that denotes the visible part of all apps container view.
     */
    public View getVisibleContainerView() {
        return mBottomSheetBackground.getVisibility() == VISIBLE ? mBottomSheetBackground : this;
    }

    protected void onInitializeRecyclerView(RecyclerView rv) {
        rv.addOnScrollListener(mScrollListener);
        mSearchUiDelegate.onInitializeRecyclerView(rv);
    }

    /** Returns the instance of @{code SearchTransitionController}. */
    public SearchTransitionController getSearchTransitionController() {
        return mSearchTransitionController;
    }

    /** Holds a {@link BaseAllAppsAdapter} and related fields. */
    public class AdapterHolder {
        public static final int MAIN = 0;
        public static final int WORK = 1;
        public static final int SEARCH = 2;
        /** Lawnchair: holder type of the first custom drawer tab; later tabs follow. */
        public static final int FIRST_CUSTOM = 3;

        private int mType;
        /** Lawnchair: drawer tab id for custom tab holders, -1 otherwise. */
        long mTabId = -1;
        public final BaseAllAppsAdapter<T> mAdapter;
        final RecyclerView.LayoutManager mLayoutManager;
        final AlphabeticalAppsList<T> mAppsList;
        final Rect mPadding = new Rect();
        AllAppsRecyclerView mRecyclerView;
        private OnFocusChangeListener mOnFocusChangeListener;

        AdapterHolder(int type, AlphabeticalAppsList<T> appsList) {
            mType = type;
            mAppsList = appsList;
            mAdapter = createAdapter(mAppsList);
            mAppsList.setAdapter(mAdapter);
            mLayoutManager = mAdapter.getLayoutManager();
        }

        void setup(@NonNull View rv, @Nullable Predicate<ItemInfo> matcher) {
            mAppsList.updateItemFilter(matcher);
            mRecyclerView = (AllAppsRecyclerView) rv;
            mRecyclerView.bindFastScrollbar(mFastScroller, ALL_APPS_SCROLLER);
            mRecyclerView.setEdgeEffectFactory(createEdgeEffectFactory());
            mRecyclerView.setApps(mAppsList);
            mRecyclerView.setLayoutManager(mLayoutManager);
            mRecyclerView.setAdapter(mAdapter);
            mRecyclerView.setHasFixedSize(true);
            // No animations will occur when changes occur to the items in this RecyclerView.
            mRecyclerView.setItemAnimator(null);
            onInitializeRecyclerView(mRecyclerView);
            // Use ViewGroupFocusHelper for SearchRecyclerView to draw focus outline for the
            // buttons in the view (e.g. query builder button and setting button)
            FocusedItemDecorator focusedItemDecorator = isSearch() ? new FocusedItemDecorator(
                    new ViewGroupFocusHelper(mRecyclerView)) : new FocusedItemDecorator(
                    mRecyclerView);
            mRecyclerView.addItemDecoration(focusedItemDecorator);
            // LC-Note: This is needed for highlight decoration
            if (isSearch()) {
                RecyclerView.ItemDecoration searchDecorator = getMainAdapterProvider().getDecorator();
                if (searchDecorator != null) {
                    mRecyclerView.addItemDecoration(searchDecorator);
                }
            }
            mOnFocusChangeListener = focusedItemDecorator.getFocusListener();
            mAdapter.setIconFocusListener(mOnFocusChangeListener);
            applyPadding();
        }

        void applyPadding() {
            if (mRecyclerView != null) {
                int bottomOffset = 0;
                if (isWork() && mWorkManager.getWorkUtilityView() != null) {
                    bottomOffset = mInsets.bottom + mWorkManager.getWorkUtilityView().getHeight();
                } else if (isMain() && mPrivateProfileManager != null) {
                    Optional<AdapterItem> privateSpaceHeaderItem = mAppsList.getAdapterItems()
                            .stream()
                            .filter(item -> item.viewType == VIEW_TYPE_PRIVATE_SPACE_HEADER)
                            .findFirst();
                    if (privateSpaceHeaderItem.isPresent()) {
                        bottomOffset = mPrivateSpaceBottomExtraSpace;
                    }
                }
                if (isSearchBarFloating()) {
                    bottomOffset += mSearchContainer.getHeight();
                }
                mRecyclerView.setPadding(mPadding.left, mPadding.top, mPadding.right,
                        mPadding.bottom + bottomOffset);
            }
        }

        boolean isWork() {
            return mType == WORK;
        }

        private boolean isSearch() {
            return mType == SEARCH;
        }

        boolean isMain() {
            return mType == MAIN;
        }
    }
}
