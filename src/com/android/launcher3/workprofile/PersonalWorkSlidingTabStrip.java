/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.workprofile;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.pageindicators.PageIndicator;
import com.android.launcher3.views.ActivityContext;

import app.lawnchair.font.FontManager;
import app.lawnchair.theme.color.tokens.ColorStateListTokens;
import app.lawnchair.theme.drawable.DrawableTokens;

/**
 * Supports two indicator colors, dedicated for personal and work tabs.
 */
public class PersonalWorkSlidingTabStrip extends LinearLayout implements PageIndicator {
    private final boolean mIsAlignOnIcon;
    private OnActivePageChangedListener mOnActivePageChangedListener;
    private int mLastActivePage = 0;
    // Lawnchair: drawer tabs. When true, tabs keep their natural width and the strip may grow
    // wider than its parent HorizontalScrollView, which then scrolls.
    private boolean mScrollable = false;

    public PersonalWorkSlidingTabStrip(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.PersonalWorkSlidingTabStrip);
        mIsAlignOnIcon = typedArray.getBoolean(
                R.styleable.PersonalWorkSlidingTabStrip_alignOnIcon, false);
        typedArray.recycle();
    }

    // Lawnchair: This function theme the work mode tab and toggle
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        for (int i = 0; i < getChildCount(); i++) {
            styleTab((Button) getChildAt(i));
        }
    }

    private void styleTab(Button tab) {
        tab.setAllCaps(false);
        // Lawnchair-TODO: StateListDrawable
//        RippleDrawable background = (RippleDrawable) tab.getBackground();
//        background.setDrawableByLayerId(android.R.id.mask, DrawableTokens.AllAppsTabsMaskDrawable.resolve(getContext()));
        tab.setBackground(DrawableTokens.AllAppsTabsBackground.resolve(getContext()));
        tab.setTextColor(ColorStateListTokens.AllAppsTabText.resolve(getContext()));
        FontManager.INSTANCE.get(getContext()).setCustomFont(tab, R.id.font_body_medium);
    }

    /** Whether the strip still holds the stock Personal/Work buttons. */
    public boolean hasStockTabs() {
        return getChildCount() == 2
                && getChildAt(0).getId() == R.id.tab_personal
                && getChildAt(1).getId() == R.id.tab_work;
    }

    /**
     * Lawnchair: replaces the tab buttons with one button per title. {@code ids[i]} is used as
     * the view id of button i ({@link View#NO_ID} to generate one). {@code activePage} is marked
     * selected without notifying the listener.
     *
     * @param scrollable true to keep natural tab widths and allow horizontal scrolling.
     */
    public void setTabs(List<CharSequence> titles, int[] ids, boolean scrollable,
            int activePage) {
        mScrollable = scrollable;
        removeAllViews();
        int gap = getResources().getDimensionPixelSize(
                R.dimen.all_apps_tabs_button_horizontal_padding);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int count = titles.size();
        for (int i = 0; i < count; i++) {
            Button tab = (Button) inflater.inflate(R.layout.all_apps_tab_button, this, false);
            tab.setText(titles.get(i));
            tab.setId(ids[i] != View.NO_ID ? ids[i] : View.generateViewId());
            LayoutParams lp = (LayoutParams) tab.getLayoutParams();
            lp.width = scrollable ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            lp.weight = 1;
            lp.setMarginStart(i == 0 ? 0 : gap);
            lp.setMarginEnd(i == count - 1 ? 0 : gap);
            if (scrollable) {
                tab.setMinWidth(0);
                tab.setMinimumWidth(0);
                int pad = getResources().getDimensionPixelSize(
                        R.dimen.all_apps_tabs_button_horizontal_padding) * 2;
                tab.setPadding(pad, tab.getPaddingTop(), pad, tab.getPaddingBottom());
            }
            styleTab(tab);
            addView(tab, lp);
        }
        mLastActivePage = activePage;
        updateTabTextColor(activePage);
        if (scrollable) {
            ensureTabVisible(activePage);
        } else if (getParent() instanceof HorizontalScrollView) {
            ((HorizontalScrollView) getParent()).scrollTo(0, 0);
        }
        requestLayout();
    }

    /** Scrolls the parent {@link HorizontalScrollView}, if any, so tab {@code pos} is visible. */
    private void ensureTabVisible(int pos) {
        if (!(getParent() instanceof HorizontalScrollView) || pos < 0 || pos >= getChildCount()) {
            return;
        }
        HorizontalScrollView scroller = (HorizontalScrollView) getParent();
        post(() -> {
            View tab = getChildAt(pos);
            if (tab == null) return;
            int target = tab.getLeft() - (scroller.getWidth() - tab.getWidth()) / 2;
            scroller.smoothScrollTo(Math.max(0, target), 0);
        });
    }

    /**
     * Highlights tab with index pos
     */
    public void updateTabTextColor(int pos) {
        for (int i = 0; i < getChildCount(); i++) {
            Button tab = (Button) getChildAt(i);
            tab.setSelected(i == pos);
        }
    }

    @Override
    public void setScroll(int currentScroll, int totalScroll) {}

    @Override
    public void setActiveMarker(int activePage) {
        updateTabTextColor(activePage);
        if (mScrollable) {
            ensureTabVisible(activePage);
        }
        if (mOnActivePageChangedListener != null && mLastActivePage != activePage) {
            mOnActivePageChangedListener.onActivePageChanged(activePage);
        }
        mLastActivePage = activePage;
    }

    public void setOnActivePageChangedListener(OnActivePageChangedListener listener) {
        mOnActivePageChangedListener = listener;
    }

    @Override
    public void setMarkersCount(int numMarkers) {
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getParent() instanceof HorizontalScrollView) {
            // Lawnchair: inside a HorizontalScrollView the width spec is UNSPECIFIED. Fill the
            // viewport, keep the icon-aligned inset as padding, and grow wider when scrollable
            // tabs do not fit.
            int size = MeasureSpec.getSize(widthMeasureSpec);
            if (size == 0) {
                size = ((View) getParent()).getMeasuredWidth();
            }
            if (size == 0) {
                size = getResources().getDisplayMetrics().widthPixels;
            }
            int side = mIsAlignOnIcon ? Math.max(0, (size - getTabWidth(getContext(), size)) / 2) : 0;
            if (getPaddingLeft() != side || getPaddingRight() != side) {
                setPadding(side, getPaddingTop(), side, getPaddingBottom());
            }
            int width = size;
            if (mScrollable) {
                int natural = side * 2;
                int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    child.measure(unspecified, heightMeasureSpec);
                    MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                    natural += child.getMeasuredWidth() + lp.getMarginStart() + lp.getMarginEnd();
                }
                width = Math.max(size, natural);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    heightMeasureSpec);
            return;
        }
        if (mIsAlignOnIcon) {
            // If any padding is not specified, restrict the width to emulate padding
            int size = MeasureSpec.getSize(widthMeasureSpec);
            size = getTabWidth(getContext(), size);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Returns distance between left and right app icons
     */
    public static int getTabWidth(Context context, int totalWidth) {
        DeviceProfile grid = ActivityContext.lookupContext(context).getDeviceProfile();
        int iconPadding = totalWidth / grid.numShownAllAppsColumns
                - grid.getAllAppsProfile().getIconSizePx();
        return totalWidth - iconPadding;
    }

    /**
     * Interface definition for a callback to be invoked when an active page has been changed.
     */
    public interface OnActivePageChangedListener {
        /** Called when the active page has been changed. */
        void onActivePageChanged(int currentActivePage);
    }
}
