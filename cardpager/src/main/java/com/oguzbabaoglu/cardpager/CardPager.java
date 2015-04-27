/*
 * Copyright (C) 2015 Oguz Babaoglu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oguzbabaoglu.cardpager;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewConfigurationCompat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * A modified ViewPager that behaves like a card stack.
 * Pages can be swiped left or right, moving onto the next page in the stack.
 * A common use case is to like or dislike content based on swipe direction.
 * Original class: {@link android.support.v4.view.ViewPager}.
 *
 * @author Oguz Babaoglu
 */
public class CardPager extends ViewGroup {

    // If the page is at most this far from its idle position,
    // allow the user to "catch" the page.
    private static final int CATCH_ALLOWANCE = 75; // dp

    private static final int DEFAULT_OFFSCREEN_PAGES = 1;
    private static final int MAX_SETTLE_DURATION = 600; // ms
    private static final int MIN_DISTANCE_FOR_FLING = 25; // dp
    private static final int SMOOTH_SCROLL_FACTOR = 4;
    private static final float SNAP_FACTOR = .3f;

    // Minimum fling velocity is larger than of ViewPager.
    // We want to minimize accidental swipes, there is no way of going back.
    private static final int MIN_FLING_VELOCITY = 800; // dp

    private static final float MIN_SCALE = 0.75f;

    // Offsets of the first and last items, if known.
    // Set during population, used to determine if we are at the beginning
    // or end of the pager data set during touch scrolling.
    private float firstOffset = -Float.MAX_VALUE;
    private float lastOffset = Float.MAX_VALUE;

    private boolean inLayout;
    private boolean firstLayout = true;
    private boolean populatePending;
    private boolean dragInProgress;
    private boolean unableToDrag;

    private int currentItem;   // Index of currently displayed page.
    private float virtualPos;  // Relative position of displayed page.
    private int lastScroll;
    private boolean reversePos; // True if we are currently inverting touches.

    private float density;
    private int restoredCurItem = -1;
    private Parcelable restoredAdapterState = null;
    private ClassLoader restoredClassLoader = null;

    private Scroller scroller;
    private PagerAdapter pagerAdapter;
    private PagerObserver pagerObserver;
    private OnCardChangeListener onCardChangeListener;

    private ArrayList<View> drawingOrderedChildren;

    private static final Comparator<ItemInfo> ITEM_COMPARATOR = new Comparator<ItemInfo>() {
        @Override
        public int compare(ItemInfo lhs, ItemInfo rhs) {
            return lhs.position - rhs.position;
        }
    };

    private static final Comparator<View> VIEW_COMPARATOR = new Comparator<View>() {
        @Override
        public int compare(View lhs, View rhs) {
            final LayoutParams llp = (LayoutParams) lhs.getLayoutParams();
            final LayoutParams rlp = (LayoutParams) rhs.getLayoutParams();
            return llp.position - rlp.position;
        }
    };

    private static final Interpolator INTERPOLATOR = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private int offscreenPageLimit = DEFAULT_OFFSCREEN_PAGES;

    /**
     * Allow sloppy touch work from the user.
     */
    private int touchSlop;

    /**
     * ID of the active pointer. This is used to retain consistency during
     * drags/flings if multiple pointers are used.
     */
    private int activePointerId = INVALID_POINTER;

    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #activePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * Position of the last motion event.
     */
    private float lastMotionX;
    private float lastMotionY;
    private float initialMotionX;
    private float initialMotionY;

    /**
     * Determines speed during touch scrolling.
     */
    private VelocityTracker velocityTracker;
    private int minimumVelocity;
    private int maximumVelocity;
    private int flingDistance;
    private int catchAllowance;

    /**
     * Indicates that the pager is in an idle, settled state. The current page
     * is fully in view and no animation is in progress.
     */
    public static final int SCROLL_STATE_IDLE = 0;

    /**
     * Indicates that the pager is currently being dragged by the user.
     */
    public static final int SCROLL_STATE_DRAGGING = 1;

    /**
     * Indicates that the pager is in the process of settling to a final position.
     */
    public static final int SCROLL_STATE_SETTLING = 2;

    private final Runnable endScrollRunnable = new Runnable() {
        public void run() {
            setScrollState(SCROLL_STATE_IDLE);
            populate();
        }
    };

    private int scrollState = SCROLL_STATE_IDLE;

    /**
     * Callback interface for responding to changing state of the selected page.
     */
    public interface OnCardChangeListener {

        /**
         * This method will be invoked when the current page is scrolled, either as part
         * of a programmatically initiated smooth scroll or a user initiated touch scroll.
         *
         * @param position             Position index of the page currently being displayed.
         *                             Page position+1 will be visible if positionOffset is nonzero.
         * @param positionOffset       Value from [-1, 1) indicating the offset from the page at position.
         * @param positionOffsetPixels Value in pixels indicating the offset from position.
         */
        void onCardScrolled(int position, float positionOffset, int positionOffsetPixels);

        /**
         * This method will be invoked when the current page is dismissed. Animation is not
         * necessarily complete.
         *
         * @param position Position index of the dismissed page.
         * @param right    True if dismissed from the right side, false otherwise.
         */
        void onCardDismissed(int position, boolean right);

        /**
         * Called when the scroll state changes. Useful for discovering when the user
         * begins dragging, when the pager is automatically settling to the current page,
         * or when it is fully stopped/idle.
         *
         * @param state The new scroll state.
         * @see CardPager#SCROLL_STATE_IDLE
         * @see CardPager#SCROLL_STATE_DRAGGING
         * @see CardPager#SCROLL_STATE_SETTLING
         */
        void onCardScrollStateChanged(int state);
    }

    /**
     * Simple implementation of the {@link OnCardChangeListener} interface with stub
     * implementations of each method. Extend this if you do not intend to override
     * every method of {@link OnCardChangeListener}.
     */
    public static class SimpleOnCardChangeListener implements OnCardChangeListener {
        @Override
        public void onCardScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // This space for rent
        }

        @Override
        public void onCardDismissed(int position, boolean right) {
            // This space for rent
        }

        @Override
        public void onCardScrollStateChanged(int state) {
            // This space for rent
        }
    }

    private final ArrayList<ItemInfo> items = new ArrayList<>();
    private final ItemInfo tempItem = new ItemInfo();

    /**
     * Holds info about page.
     */
    static class ItemInfo {
        Object object;
        int position;
        boolean scrolling;
        float widthFactor;
        float offset;
    }

    public CardPager(Context context) {
        super(context);
        initCardPager();
    }

    public CardPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        initCardPager();
    }

    void initCardPager() {

        setWillNotDraw(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setFocusable(true);
        setChildrenDrawingOrderEnabled(true);

        final Context context = getContext();
        final ViewConfiguration configuration = ViewConfiguration.get(context);

        density = context.getResources().getDisplayMetrics().density;
        scroller = new Scroller(context, INTERPOLATOR);

        touchSlop = ViewConfigurationCompat.getScaledPagingTouchSlop(configuration);
        maximumVelocity = configuration.getScaledMaximumFlingVelocity();

        minimumVelocity = (int) (MIN_FLING_VELOCITY * density);
        flingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);
        catchAllowance = (int) (CATCH_ALLOWANCE * density);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(endScrollRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        final int index = childCount - 1 - i;
        return ((LayoutParams) drawingOrderedChildren.get(index).getLayoutParams()).childIndex;
    }

    /**
     * Set scroll state. Will enable hardware layers in children if there is motion.
     *
     * @param newState new state
     */
    private void setScrollState(int newState) {
        if (scrollState == newState) {
            return;
        }

        scrollState = newState;

        if (onCardChangeListener != null) {
            onCardChangeListener.onCardScrollStateChanged(newState);
        }

        enableLayers(newState != SCROLL_STATE_IDLE);
    }

    /**
     * Set a PagerAdapter that will supply views for this pager as needed.
     *
     * @param adapter Adapter to use
     */
    public void setAdapter(PagerAdapter adapter) {
        if (pagerAdapter != null) {
            pagerAdapter.unregisterDataSetObserver(pagerObserver);
            pagerAdapter.startUpdate(this);
            for (int i = 0; i < items.size(); i++) {
                final ItemInfo ii = items.get(i);
                pagerAdapter.destroyItem(this, ii.position, ii.object);
            }
            pagerAdapter.finishUpdate(this);
            items.clear();
            removeCardViews();
            currentItem = 0;
            scrollTo(0, 0);
        }

        pagerAdapter = adapter;

        if (pagerAdapter != null) {
            if (pagerObserver == null) {
                pagerObserver = new PagerObserver();
            }
            pagerAdapter.registerDataSetObserver(pagerObserver);
            populatePending = false;
            final boolean wasFirstLayout = firstLayout;
            firstLayout = true;

            if (restoredCurItem >= 0) {
                pagerAdapter.restoreState(restoredAdapterState, restoredClassLoader);
                setCurrentItemInternal(restoredCurItem, false, true);
                restoredCurItem = -1;
                restoredAdapterState = null;
                restoredClassLoader = null;
            } else if (!wasFirstLayout) {
                populate();
            } else {
                requestLayout();
            }
        }
    }

    /**
     * Remove all child views.
     */
    private void removeCardViews() {
        for (int i = 0; i < getChildCount(); i++) {
            removeViewAt(i);
            i--;
        }
    }

    /**
     * Retrieve the current adapter supplying pages.
     *
     * @return The currently registered PagerAdapter
     */
    public PagerAdapter getAdapter() {
        return pagerAdapter;
    }

    /**
     * @return content width
     */
    private int getClientWidth() {
        return getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always) {
        setCurrentItemInternal(item, smoothScroll, always, 0);
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always, int velocity) {
        if (pagerAdapter == null || pagerAdapter.getCount() <= 0) {
            return;
        }
        if (!always && currentItem == item && items.size() != 0) {
            return;
        }

        if (item < 0) {
            item = 0;
        } else if (item >= pagerAdapter.getCount()) {
            item = pagerAdapter.getCount() - 1;
        }
        final int pageLimit = offscreenPageLimit;
        if (item > (currentItem + pageLimit) || item < (currentItem - pageLimit)) {
            // We are doing a jump by more than one page.  To avoid
            // glitches, we want to keep all current pages in the view
            // until the scroll ends.
            for (int i = 0; i < items.size(); i++) {
                items.get(i).scrolling = true;
            }
        }

        if (firstLayout) {
            // We don't have any idea how big we are yet and shouldn't have any pages either.
            // Just set things up and let the pending layout handle things.
            currentItem = item;
            requestLayout();

        } else {
            populate(item);
            scrollToItem(item, smoothScroll, velocity);
        }
    }

    private void scrollToItem(int item, boolean smoothScroll, int velocity) {
        final ItemInfo curInfo = infoForPosition(item);
        int destX = 0;
        if (curInfo != null) {
            destX = (int) (getClientWidth() * Math.max(firstOffset, Math.min(curInfo.offset, lastOffset)));
        }
        if (smoothScroll) {
            smoothScrollTo(destX, 0, velocity);

        } else {
            completeScroll(false);
            scrollTo(destX, 0);
            pageScrolled(destX);
        }
    }

    public void setOnCardChangeListener(OnCardChangeListener onCardChangeListener) {
        this.onCardChangeListener = onCardChangeListener;
    }

    /**
     * We want the duration of the page snap animation to be influenced by the distance that
     * the screen has to travel, however, we don't want this duration to be effected in a
     * purely linear fashion. Instead, we use this method to moderate the effect that the distance
     * of travel has on the overall snap duration.
     *
     * @param f unmodified distance factor
     * @return modified distance factor
     */
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= SNAP_FACTOR * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param x        the number of pixels to scroll by on the X axis
     * @param y        the number of pixels to scroll by on the Y axis
     * @param velocity the velocity associated with a fling, if applicable. (0 otherwise)
     */
    void smoothScrollTo(int x, int y, int velocity) {
        if (getChildCount() == 0) {
            // Nothing to do.
            return;
        }
        final int sx = getScrollX();
        final int sy = getScrollY();
        final int dx = x - sx;
        final int dy = y - sy;
        if (dx == 0 && dy == 0) {
            completeScroll(false);
            populate();
            setScrollState(SCROLL_STATE_IDLE);
            return;
        }

        setScrollState(SCROLL_STATE_SETTLING);

        final int width = getClientWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dx) / width);
        final float distance = halfWidth + halfWidth * distanceInfluenceForSnapDuration(distanceRatio);

        int duration;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = SMOOTH_SCROLL_FACTOR * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageWidth = width * pagerAdapter.getPageWidth(currentItem);
            final float pageDelta = (float) Math.abs(dx) / pageWidth;
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);

        scroller.startScroll(sx, sy, dx, dy, duration);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    ItemInfo addNewItem(int position, int index) {
        final ItemInfo ii = new ItemInfo();
        ii.position = position;
        ii.object = pagerAdapter.instantiateItem(this, position);
        ii.widthFactor = pagerAdapter.getPageWidth(position);
        if (index < 0 || index >= items.size()) {
            items.add(ii);
        } else {
            items.add(index, ii);
        }
        return ii;
    }

    void dataSetChanged() {
        // This method only gets called if our observer is attached, so pagerAdapter is non-null.

        final int adapterCount = pagerAdapter.getCount();
        boolean needPopulate = items.size() < offscreenPageLimit * 2 + 1 && items.size() < adapterCount;
        int newCurrItem = currentItem;

        boolean isUpdating = false;
        for (int i = 0; i < items.size(); i++) {
            final ItemInfo ii = items.get(i);
            final int newPos = pagerAdapter.getItemPosition(ii.object);

            if (newPos == PagerAdapter.POSITION_UNCHANGED) {
                continue;
            }

            if (newPos == PagerAdapter.POSITION_NONE) {
                items.remove(i);
                i--;

                if (!isUpdating) {
                    pagerAdapter.startUpdate(this);
                    isUpdating = true;
                }

                pagerAdapter.destroyItem(this, ii.position, ii.object);
                needPopulate = true;

                if (currentItem == ii.position) {
                    // Keep the current item in the valid range
                    newCurrItem = Math.max(0, Math.min(currentItem, adapterCount - 1));
                    needPopulate = true;
                }
                continue;
            }

            if (ii.position != newPos) {
                if (ii.position == currentItem) {
                    // Our current item changed position. Follow it.
                    newCurrItem = newPos;
                }

                ii.position = newPos;
                needPopulate = true;
            }
        }

        if (isUpdating) {
            pagerAdapter.finishUpdate(this);
        }

        Collections.sort(items, ITEM_COMPARATOR);

        if (needPopulate) {
            // Reset our known page widths; populate will recompute them.
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.widthFactor = 0.f;
            }

            setCurrentItemInternal(newCurrItem, false, true);
            requestLayout();
        }
    }

    void populate() {
        populate(currentItem);
    }

    void populate(int newCurrentItem) {
        ItemInfo oldCurInfo = null;
        int focusDirection = View.FOCUS_FORWARD;
        if (currentItem != newCurrentItem) {
            focusDirection = currentItem < newCurrentItem ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
            oldCurInfo = infoForPosition(currentItem);
            currentItem = newCurrentItem;
        }

        if (pagerAdapter == null) {
            sortChildDrawingOrder();
            return;
        }

        // Bail now if we are waiting to populate.  This is to hold off
        // on creating views from the time the user releases their finger to
        // fling to a new position until we have finished the scroll to
        // that position, avoiding glitches from happening at that point.
        if (populatePending) {
            sortChildDrawingOrder();
            return;
        }

        // Also, don't populate until we are attached to a window.  This is to
        // avoid trying to populate before we have restored our view hierarchy
        // state and conflicting with what is restored.
        if (getWindowToken() == null) {
            return;
        }

        pagerAdapter.startUpdate(this);

        final int pageLimit = offscreenPageLimit;
        final int startPos = Math.max(0, currentItem - pageLimit);
        final int adapterCount = pagerAdapter.getCount();
        final int endPos = Math.min(adapterCount - 1, currentItem + pageLimit);

        // Locate the currently focused item or add it if needed.
        int curIndex;
        ItemInfo curItem = null;
        for (curIndex = 0; curIndex < items.size(); curIndex++) {
            final ItemInfo ii = items.get(curIndex);
            if (ii.position == currentItem) {
                curItem = ii;
                break;
            }
        }

        if (curItem == null && adapterCount > 0) {
            curItem = addNewItem(currentItem, curIndex);
        }

        // Fill 3x the available width or up to the number of offscreen
        // pages requested to either side, whichever is larger.
        // If we have no current item we have no work to do.
        if (curItem != null) {
            float extraWidthLeft = 0.f;
            int itemIndex = curIndex - 1;
            ItemInfo ii = itemIndex >= 0 ? items.get(itemIndex) : null;
            final int clientWidth = getClientWidth();
            final float leftWidthNeeded = clientWidth <= 0 ? 0
                    : 2.f - curItem.widthFactor + (float) getPaddingLeft() / (float) clientWidth;
            for (int pos = currentItem - 1; pos >= 0; pos--) {
                if (extraWidthLeft >= leftWidthNeeded && pos < startPos) {
                    if (ii == null) {
                        break;
                    }
                    if (pos == ii.position && !ii.scrolling) {
                        items.remove(itemIndex);
                        pagerAdapter.destroyItem(this, pos, ii.object);

                        itemIndex--;
                        curIndex--;
                        ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                    }
                } else if (ii != null && pos == ii.position) {
                    extraWidthLeft += ii.widthFactor;
                    itemIndex--;
                    ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                } else {
                    ii = addNewItem(pos, itemIndex + 1);
                    extraWidthLeft += ii.widthFactor;
                    curIndex++;
                    ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                }
            }

            float extraWidthRight = curItem.widthFactor;
            itemIndex = curIndex + 1;
            if (extraWidthRight < 2.f) {
                ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                final float rightWidthNeeded = clientWidth <= 0 ? 0
                        : (float) getPaddingRight() / (float) clientWidth + 2.f;
                for (int pos = currentItem + 1; pos < adapterCount; pos++) {
                    if (extraWidthRight >= rightWidthNeeded && pos > endPos) {
                        if (ii == null) {
                            break;
                        }
                        if (pos == ii.position && !ii.scrolling) {
                            items.remove(itemIndex);
                            pagerAdapter.destroyItem(this, pos, ii.object);

                            ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                        }
                    } else if (ii != null && pos == ii.position) {
                        extraWidthRight += ii.widthFactor;
                        itemIndex++;
                        ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                    } else {
                        ii = addNewItem(pos, itemIndex);
                        itemIndex++;
                        extraWidthRight += ii.widthFactor;
                        ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                    }
                }
            }

            calculatePageOffsets(curItem, curIndex, oldCurInfo);
        }

        pagerAdapter.setPrimaryItem(this, currentItem, curItem != null ? curItem.object : null);

        pagerAdapter.finishUpdate(this);

        // Check width measurement of current pages and drawing sort order.
        // Update LayoutParams as needed.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.childIndex = i;
            if (lp.widthFactor == 0.f) {
                // 0 means requery the adapter for this, it doesn't have a valid width.
                final ItemInfo ii = infoForChild(child);
                if (ii != null) {
                    lp.widthFactor = ii.widthFactor;
                    lp.position = ii.position;
                }
            }
        }

        sortChildDrawingOrder();
        checkFocus(focusDirection);
    }

    /**
     * Check if child needs focus.
     *
     * @param focusDirection focusDirection
     */
    private void checkFocus(int focusDirection) {

        if (hasFocus()) {
            View currentFocused = findFocus();
            ItemInfo ii = currentFocused != null ? infoForAnyChild(currentFocused) : null;
            if (ii == null || ii.position != currentItem) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    ii = infoForChild(child);
                    if (ii != null && ii.position == currentItem) {
                        if (child.requestFocus(focusDirection)) {
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Sorts children in reverse order for drawing.
     */
    private void sortChildDrawingOrder() {

        if (drawingOrderedChildren == null) {
            drawingOrderedChildren = new ArrayList<>();
        } else {
            drawingOrderedChildren.clear();
        }
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            drawingOrderedChildren.add(child);
        }
        Collections.sort(drawingOrderedChildren, VIEW_COMPARATOR);
    }

    private void calculatePageOffsets(ItemInfo curItem, int curIndex, ItemInfo oldCurInfo) {

        if (pagerAdapter == null) {
            return;
        }

        final int adapterCount = pagerAdapter.getCount();
        final float marginOffset = 0;
        // Fix up offsets for later layout.
        if (oldCurInfo != null) {
            final int oldCurPosition = oldCurInfo.position;
            // Base offsets off of oldCurInfo.
            if (oldCurPosition < curItem.position) {
                int itemIndex = 0;
                ItemInfo ii;
                float offset = oldCurInfo.offset + oldCurInfo.widthFactor + marginOffset;
                for (int pos = oldCurPosition + 1;
                     pos <= curItem.position && itemIndex < items.size(); pos++) {
                    ii = items.get(itemIndex);
                    while (pos > ii.position && itemIndex < items.size() - 1) {
                        itemIndex++;
                        ii = items.get(itemIndex);
                    }
                    while (pos < ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset += pagerAdapter.getPageWidth(pos) + marginOffset;
                        pos++;
                    }
                    ii.offset = offset;
                    offset += ii.widthFactor + marginOffset;
                }
            } else if (oldCurPosition > curItem.position) {
                int itemIndex = items.size() - 1;
                ItemInfo ii;
                float offset = oldCurInfo.offset;
                for (int pos = oldCurPosition - 1;
                     pos >= curItem.position && itemIndex >= 0; pos--) {
                    ii = items.get(itemIndex);
                    while (pos < ii.position && itemIndex > 0) {
                        itemIndex--;
                        ii = items.get(itemIndex);
                    }
                    while (pos > ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset -= pagerAdapter.getPageWidth(pos) + marginOffset;
                        pos--;
                    }
                    offset -= ii.widthFactor + marginOffset;
                    ii.offset = offset;
                }
            }
        }

        // Base all offsets off of curItem.
        final int itemCount = items.size();
        float offset = curItem.offset;
        int pos = curItem.position - 1;
        firstOffset = curItem.position == 0 ? curItem.offset : -Float.MAX_VALUE;
        lastOffset = curItem.position == adapterCount - 1
                ? curItem.offset + curItem.widthFactor - 1
                : Float.MAX_VALUE;
        // Previous pages
        for (int i = curIndex - 1; i >= 0; i--, pos--) {
            final ItemInfo ii = items.get(i);
            while (pos > ii.position) {
                offset -= pagerAdapter.getPageWidth(pos--) + marginOffset;
            }
            offset -= ii.widthFactor + marginOffset;
            ii.offset = offset;
            if (ii.position == 0) {
                firstOffset = offset;
            }
        }
        offset = curItem.offset + curItem.widthFactor + marginOffset;
        pos = curItem.position + 1;
        // Next pages
        for (int i = curIndex + 1; i < itemCount; i++, pos++) {
            final ItemInfo ii = items.get(i);
            while (pos < ii.position) {
                offset += pagerAdapter.getPageWidth(pos++) + marginOffset;
            }
            if (ii.position == adapterCount - 1) {
                lastOffset = offset + ii.widthFactor - 1;
            }
            ii.offset = offset;
            offset += ii.widthFactor + marginOffset;
        }
    }

    @Override
    public void addView(@NonNull View child, int index, ViewGroup.LayoutParams params) {
        if (!checkLayoutParams(params)) {
            params = generateLayoutParams(params);
        }
        final LayoutParams lp = (LayoutParams) params;
        if (inLayout) {
            lp.needsMeasure = true;
            addViewInLayout(child, index, params);
        } else {
            super.addView(child, index, params);
        }
    }

    @Override
    public void removeView(@NonNull View view) {
        if (inLayout) {
            removeViewInLayout(view);
        } else {
            super.removeView(view);
        }
    }

    ItemInfo infoForChild(View child) {
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (pagerAdapter.isViewFromObject(child, ii.object)) {
                return ii;
            }
        }
        return null;
    }

    ItemInfo infoForAnyChild(View child) {
        ViewParent parent;
        while ((parent = child.getParent()) != this) {
            if (parent == null || !(parent instanceof View)) {
                return null;
            }
            child = (View) parent;
        }
        return infoForChild(child);
    }

    ItemInfo infoForPosition(int position) {
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (ii.position == position) {
                return ii;
            }
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        firstLayout = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // For simple implementation, our internal size is always 0.
        // We depend on the container to specify the layout size of
        // our view.  We can't really know what it is since we will be
        // adding and removing different arbitrary views and do not
        // want the layout to change as this happens.
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), getDefaultSize(0, heightMeasureSpec));

        final int measuredWidth = getMeasuredWidth();

        // Children are just made to fill our space.
        int childWidthSize = measuredWidth - getPaddingLeft() - getPaddingRight();
        int childHeightSize = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();

        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(childWidthSize, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(childHeightSize, MeasureSpec.EXACTLY);

        // Make sure we have created all fragments that we need to have shown.
        inLayout = true;
        populate();
        inLayout = false;

        // Page views.
        final int size = getChildCount();
        for (int i = 0; i < size; ++i) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Make sure scroll position is set correctly.
        if (w != oldw) {
            recomputeScrollPosition(w, oldw, 0, 0);
        }
    }

    private void recomputeScrollPosition(int width, int oldWidth, int margin, int oldMargin) {
        if (oldWidth > 0 && !items.isEmpty()) {
            final int widthWithMargin = width - getPaddingLeft() - getPaddingRight() + margin;
            final int oldWidthWithMargin = oldWidth - getPaddingLeft() - getPaddingRight()
                    + oldMargin;
            final int xpos = getScrollX();
            final float pageOffset = (float) xpos / oldWidthWithMargin;
            final int newOffsetPixels = (int) (pageOffset * widthWithMargin);

            scrollTo(newOffsetPixels, getScrollY());
            if (!scroller.isFinished()) {
                // We now return to your regularly scheduled scroll, already in progress.
                final int newDuration = scroller.getDuration() - scroller.timePassed();
                ItemInfo targetInfo = infoForPosition(currentItem);
                if (targetInfo != null) {
                    scroller.startScroll(newOffsetPixels, 0, (int) (targetInfo.offset * width), 0, newDuration);
                }
            }
        } else {
            final ItemInfo ii = infoForPosition(currentItem);
            final float scrollOffset = ii != null ? Math.min(ii.offset, lastOffset) : 0;
            final int scrollPos = (int) (scrollOffset * (width - getPaddingLeft() - getPaddingRight()));
            if (scrollPos != getScrollX()) {
                completeScroll(false);
                scrollTo(scrollPos, getScrollY());
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int count = getChildCount();
        int width = r - l;
        int height = b - t;
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        final int childWidth = width - paddingLeft - paddingRight;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            ItemInfo ii;
            if ((ii = infoForChild(child)) != null) {
                final int loff = (int) (childWidth * ii.offset);
                final int childLeft = paddingLeft + loff;
                if (lp.needsMeasure) {
                    // This was added during layout and needs measurement.
                    // Do it now that we know what we're working with.
                    lp.needsMeasure = false;
                    final int widthSpec = MeasureSpec.makeMeasureSpec(
                            (int) (childWidth * lp.widthFactor),
                            MeasureSpec.EXACTLY
                    );
                    final int heightSpec = MeasureSpec.makeMeasureSpec(
                            height - paddingTop - paddingBottom,
                            MeasureSpec.EXACTLY
                    );
                    child.measure(widthSpec, heightSpec);
                }

                child.layout(
                        childLeft,
                        paddingTop,
                        childLeft + child.getMeasuredWidth(),
                        paddingTop + child.getMeasuredHeight()
                );
            }

        }

        if (firstLayout) {
            scrollToItem(currentItem, false, 0);
        }
        firstLayout = false;
    }

    @Override
    public void computeScroll() {
        if (!scroller.isFinished() && scroller.computeScrollOffset()) {
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = scroller.getCurrX();
            int y = scroller.getCurrY();

            if (oldX != x || oldY != y) {
                scrollTo(x, y);
                if (!pageScrolled(x)) {
                    scroller.abortAnimation();
                    scrollTo(0, y);
                }
            }

            // Keep on drawing until the animation has finished.
            ViewCompat.postInvalidateOnAnimation(this);
            return;
        }

        // Done with scroll, clean up state.
        completeScroll(true);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);

        if (l == getClientWidth() * currentItem) {
            virtualPos = 0;
        }
    }

    /**
     * Called when page is scrolled. Updates virtualPos of page according to scroll position.
     *
     * @param xPos xPos
     * @return true if scrolled
     */
    private boolean pageScrolled(int xPos) {

        if (items.size() == 0) {
            return false;
        }

        final int deltaScroll = xPos - lastScroll;
        virtualPos = reversePos
                ? virtualPos + deltaScroll
                : virtualPos - deltaScroll;
        lastScroll = xPos;

        final int width = getClientWidth();
        final float pageOffset = virtualPos / width;

        if (onCardChangeListener != null) {
            onCardChangeListener.onCardScrolled(currentItem, pageOffset, (int) virtualPos);
        }

        onPageScrolled();

        return true;
    }

    /**
     * This method will be invoked when the current page is scrolled, either as part
     * of a programmatically initiated smooth scroll or a user initiated touch scroll.
     */
    protected void onPageScrolled() {

        final int scrollX = getScrollX();
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final float transformPos = (float) (child.getLeft() - scrollX) / getClientWidth();
            transformPage(child, transformPos);
        }
    }

    /**
     * Transforms the top and bottom pages with changing scroll.
     * If top page is moving to the right side (virtualPos > 0)
     * we need to counteract the scroll by twice as much.
     *
     * @param view     child view
     * @param position position with offset
     */
    protected void transformPage(View view, float position) {

        int pageWidth = view.getWidth();

        if (position < -1) {
            // This page is off-screen
            view.setAlpha(0);

        } else if (position <= 0) {

            view.setAlpha(1);

            // Counteract the default slide transition
            if (virtualPos > 0) {
                view.setTranslationX(pageWidth * -position * 2);
            } else {
                view.setTranslationX(0);
            }

            // Scale the page down (between MIN_SCALE and 1)
            float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
            view.setScaleX(scaleFactor);
            view.setScaleY(scaleFactor);

        } else if (position <= 1) {

            // Fade the page out.
            view.setAlpha(1 - position);

            // Counteract the default slide transition
            view.setTranslationX(pageWidth * -position);

            // Scale the page down (between MIN_SCALE and 1)
            float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
            view.setScaleX(scaleFactor);
            view.setScaleY(scaleFactor);

        } else {
            // This page is off-screen
            view.setAlpha(0);
        }
    }

    /**
     * Complete a scroll in progress.
     *
     * @param postEvents whether endScroll runnable should wait for animation to complete
     */
    private void completeScroll(boolean postEvents) {
        boolean needPopulate = scrollState == SCROLL_STATE_SETTLING;
        if (needPopulate) {
            // Done with scroll, no longer want to cache view drawing.
            scroller.abortAnimation();
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = scroller.getCurrX();
            int y = scroller.getCurrY();
            if (oldX != x || oldY != y) {
                scrollTo(x, y);
                if (x != oldX) {
                    pageScrolled(x);
                }
            }
        }
        populatePending = false;
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (ii.scrolling) {
                needPopulate = true;
                ii.scrolling = false;
            }
        }
        if (needPopulate) {
            if (postEvents) {
                ViewCompat.postOnAnimation(this, endScrollRunnable);
            } else {
                endScrollRunnable.run();
            }
        }
    }

    /**
     * Enable or disable hardware layers for drawing in children.
     *
     * @param enable enable
     */
    private void enableLayers(boolean enable) {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final int layerType = enable
                    ? ViewCompat.LAYER_TYPE_HARDWARE
                    : ViewCompat.LAYER_TYPE_NONE;
            ViewCompat.setLayerType(getChildAt(i), layerType, null);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onTouchEvent will be called and we do the actual
         * scrolling there.
         */

        final int action = ev.getAction() & MotionEventCompat.ACTION_MASK;

        // Reset virtual pos.
        if (scrollState == SCROLL_STATE_IDLE) {
            virtualPos = 0;
        }

        // Always take care of the touch gesture being complete.
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            // Release the drag.
            dragInProgress = false;
            unableToDrag = false;
            activePointerId = INVALID_POINTER;
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            return false;
        }

        // Nothing more to do here if we have decided whether or not we
        // are dragging.
        if (action != MotionEvent.ACTION_DOWN) {
            if (dragInProgress) {
                return true;
            }
            if (unableToDrag) {
                return false;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                /*
                 * dragInProgress == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */

                /*
                * Locally do absolute value. lastMotionY is set to the y value
                * of the down event.
                */
                final int activePointerId = this.activePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    break;
                }

                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                final float x = MotionEventCompat.getX(ev, pointerIndex);
                final float dx = x - lastMotionX;
                final float xDiff = Math.abs(dx);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float yDiff = Math.abs(y - initialMotionY);

                if (xDiff > touchSlop && xDiff * 0.5f > yDiff) {
                    dragInProgress = true;
                    requestParentDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                    lastMotionX = dx > 0
                            ? initialMotionX + touchSlop
                            : initialMotionX - touchSlop;
                    lastMotionY = y;

                } else if (yDiff > touchSlop) {
                    // The finger has moved enough in the vertical
                    // direction to be counted as a drag...  abort
                    // any attempt to drag horizontally, to work correctly
                    // with children that have scrolling containers.
                    unableToDrag = true;
                }
                if (dragInProgress) {
                    // Scroll to follow the motion event
                    performDrag(x);
                }
                break;

            case MotionEvent.ACTION_DOWN:
                /*
                 * Remember location of down touch.
                 * ACTION_DOWN always refers to pointer index 0.
                 */
                lastMotionX = initialMotionX = ev.getX();
                lastMotionY = initialMotionY = ev.getY();
                this.activePointerId = MotionEventCompat.getPointerId(ev, 0);
                unableToDrag = false;

                double scroll = getScrollX() / getClientWidth();

                if (scrollState == SCROLL_STATE_SETTLING && scroll * density < catchAllowance) {
                    // Let the user 'catch' the pager as it animates.
                    populatePending = false;
                    populate();
                    dragInProgress = true;
                    requestParentDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                } else {
                    dragInProgress = false;
                }
                break;

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return dragInProgress;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent ev) {

        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0) {
            // Don't handle edge touches immediately -- they may actually belong to one of our descendants.
            return false;
        }

        if (pagerAdapter == null || pagerAdapter.getCount() == 0) {
            // Nothing to present or scroll; nothing to touch.
            return false;
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);

        final int action = ev.getAction();

        switch (action & MotionEventCompat.ACTION_MASK) {

            case MotionEvent.ACTION_DOWN:

                // Do not interfere with the settling action.
                if (scrollState != SCROLL_STATE_SETTLING) {
                    scroller.abortAnimation();
                    populatePending = false;
                    populate();
                }

                // Remember where the motion event started
                lastMotionX = initialMotionX = ev.getX();
                lastMotionY = initialMotionY = ev.getY();
                activePointerId = MotionEventCompat.getPointerId(ev, 0);
                break;

            case MotionEvent.ACTION_MOVE:
                if (!dragInProgress) {
                    final int pointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                    final float x = MotionEventCompat.getX(ev, pointerIndex);
                    final float xDiff = Math.abs(x - lastMotionX);
                    final float y = MotionEventCompat.getY(ev, pointerIndex);
                    final float yDiff = Math.abs(y - lastMotionY);

                    if (xDiff > touchSlop && xDiff > yDiff) {

                        dragInProgress = true;
                        lastMotionX = x - initialMotionX > 0
                                ? initialMotionX + touchSlop
                                : initialMotionX - touchSlop;
                        lastMotionY = y;
                        setScrollState(SCROLL_STATE_DRAGGING);

                        // Disallow Parent Intercept, just in case
                        requestParentDisallowInterceptTouchEvent(true);
                    }
                }
                // Not else! Note that dragInProgress can be set above.
                if (dragInProgress) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                    final float x = MotionEventCompat.getX(ev, activePointerIndex);
                    performDrag(x);
                }
                break;

            case MotionEvent.ACTION_UP:

                if (!dragInProgress) {
                    break;
                }

                final VelocityTracker velocityTracker = this.velocityTracker;
                velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
                final int initialVelocity = (int) VelocityTrackerCompat.getXVelocity(velocityTracker, activePointerId);

                populatePending = true;

                final int width = getClientWidth();
                final int scrollX = getScrollX();
                final ItemInfo ii = infoForCurrentScrollPosition();
                final int currentPage = ii.position;
                final float pageOffset = ((float) scrollX / width) - ii.offset;
                final int activePointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                final float x = MotionEventCompat.getX(ev, activePointerIndex);
                final int totalDelta = (int) (x - initialMotionX);
                final int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity, totalDelta);

                setCurrentItemInternal(nextPage, true, true, initialVelocity);
                activePointerId = INVALID_POINTER;
                endDrag();
                break;

            case MotionEvent.ACTION_CANCEL:

                if (!dragInProgress) {
                    break;
                }

                scrollToItem(currentItem, true, 0);
                activePointerId = INVALID_POINTER;
                endDrag();
                break;

            case MotionEventCompat.ACTION_POINTER_DOWN:
                final int index = MotionEventCompat.getActionIndex(ev);
                lastMotionX = MotionEventCompat.getX(ev, index);
                activePointerId = MotionEventCompat.getPointerId(ev, index);
                break;

            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                lastMotionX = MotionEventCompat.getX(ev, MotionEventCompat.findPointerIndex(ev, activePointerId));
                break;
        }

        return true;
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    /**
     * Perform a user initiated drag motion. Does not allow scrolling backward.
     *
     * @param x scroll x
     */
    private void performDrag(float x) {

        float deltaX = lastMotionX - x;
        lastMotionX = x;

        if (virtualPos == 0) {

            if (deltaX < 0) {
                deltaX = -deltaX;
                reversePos = true;
            } else {
                reversePos = false;
            }

        } else if (virtualPos > 0) {

            deltaX = -deltaX;
            reversePos = true;

        } else {
            reversePos = false;
        }

        float oldScrollX = getScrollX();
        float scrollX = oldScrollX + deltaX;
        final int width = getClientWidth();

        float leftBound = 0;
        float rightBound = width * lastOffset;

        final ItemInfo currentItem = infoForPosition(this.currentItem);
        final ItemInfo lastItem = items.get(items.size() - 1);
        if (currentItem != null) {
            leftBound = currentItem.offset * width;
        }
        if (lastItem.position != pagerAdapter.getCount() - 1) {
            rightBound = lastItem.offset * width;
        }

        if (scrollX < leftBound) {
            scrollX = leftBound;

        } else if (scrollX > rightBound) {
            scrollX = rightBound;
        }
        // Don't lose the rounded component
        lastMotionX += scrollX - (int) scrollX;
        scrollTo((int) scrollX, getScrollY());
        pageScrolled((int) scrollX);
    }

    /**
     * @return Info about the page at the current scroll position.
     * This can be synthetic for a missing middle page; the 'object' field can be null.
     */
    private ItemInfo infoForCurrentScrollPosition() {
        final int width = getClientWidth();
        final float scrollOffset = width > 0 ? (float) getScrollX() / width : 0;
        final float marginOffset = 0;
        int lastPos = -1;
        float lastOffset = 0.f;
        float lastWidth = 0.f;
        boolean first = true;

        ItemInfo lastItem = null;
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            float offset;
            if (!first && ii.position != lastPos + 1) {
                // Create a synthetic item for a missing page.
                ii = tempItem;
                ii.offset = lastOffset + lastWidth + marginOffset;
                ii.position = lastPos + 1;
                ii.widthFactor = pagerAdapter.getPageWidth(ii.position);
                i--;
            }
            offset = ii.offset;

            final float leftBound = offset;
            final float rightBound = offset + ii.widthFactor + marginOffset;
            if (first || scrollOffset >= leftBound) {
                if (scrollOffset < rightBound || i == items.size() - 1) {
                    return ii;
                }
            } else {
                return lastItem;
            }
            first = false;
            lastPos = ii.position;
            lastOffset = offset;
            lastWidth = ii.widthFactor;
            lastItem = ii;
        }

        return lastItem;
    }

    /**
     * Figure out what the target page would be given current scroll and velocity.
     *
     * @return target page
     */
    private int determineTargetPage(int currentPage, float pageOffset, int velocity, int deltaX) {
        int targetPage;
        if (Math.abs(deltaX) > flingDistance && Math.abs(velocity) > minimumVelocity) {

            if (virtualPos < 0) {
                targetPage = velocity > 0 ? currentPage : currentPage + 1;
            } else {
                targetPage = velocity > 0 ? currentPage + 1 : currentPage;
            }

        } else {
            final float truncator = currentPage >= currentItem ? 0.4f : 0.6f;
            targetPage = (int) (currentPage + pageOffset + truncator);
        }

        if (items.size() > 0) {
            final ItemInfo firstItem = items.get(0);
            final ItemInfo lastItem = items.get(items.size() - 1);

            // Only let the user target pages we have items for
            targetPage = Math.max(firstItem.position, Math.min(targetPage, lastItem.position));
        }

        if (targetPage > currentPage && onCardChangeListener != null) {
            onCardChangeListener.onCardDismissed(currentPage, virtualPos > 0);
        }

        return targetPage;
    }

    /**
     * Check whether active pointer is up and re assign accordingly.
     *
     * @param ev motion event
     */
    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == activePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            lastMotionX = MotionEventCompat.getX(ev, newPointerIndex);
            activePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
            if (velocityTracker != null) {
                velocityTracker.clear();
            }
        }
    }

    private void endDrag() {
        dragInProgress = false;
        unableToDrag = false;

        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    public void addFocusables(@NonNull ArrayList<View> views, int direction, int focusableMode) {
        final int focusableCount = views.size();

        final int descendantFocusability = getDescendantFocusability();

        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == VISIBLE) {
                    ItemInfo ii = infoForChild(child);
                    if (ii != null && ii.position == currentItem) {
                        child.addFocusables(views, direction, focusableMode);
                    }
                }
            }
        }

        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.  this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.

        if (descendantFocusability != FOCUS_AFTER_DESCENDANTS || (focusableCount == views.size())) {
            // Note that we can't call the superclass here, because it will
            // add all views in.  So we need to do the same thing View does.
            if (!isFocusable()) {
                return;
            }
            if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE
                    && isInTouchMode() && !isFocusableInTouchMode()) {
                return;
            }
            views.add(this);
        }
    }

    /**
     * We only want the current page that is being shown to be touchable.
     */
    @Override
    public void addTouchables(@NonNull ArrayList<View> views) {
        // Note that we don't call super.addTouchables().
        // This is okay because a Pager is itself not touchable.
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == currentItem) {
                    child.addTouchables(views);
                }
            }
        }
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    protected boolean onRequestFocusInDescendants(int direction,
                                                  Rect previouslyFocusedRect) {
        int index;
        int increment;
        int end;
        int count = getChildCount();
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0;
            increment = 1;
            end = count;
        } else {
            index = count - 1;
            increment = -1;
            end = -1;
        }
        for (int i = index; i != end; i += increment) {
            View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == currentItem) {
                    if (child.requestFocus(direction, previouslyFocusedRect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return generateDefaultLayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams && super.checkLayoutParams(p);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    private class PagerObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            dataSetChanged();
        }

        @Override
        public void onInvalidated() {
            dataSetChanged();
        }
    }

    /**
     * Layout parameters that should be supplied for views added.
     */
    public static class LayoutParams extends ViewGroup.LayoutParams {

        /**
         * Width as a 0-1 multiplier of the measured pager width.
         */
        float widthFactor = 0.f;

        /**
         * true if this view was added during layout and needs to be measured
         * before being positioned.
         */
        boolean needsMeasure;

        /**
         * Adapter position this view.
         */
        int position;

        /**
         * Current child index within the CardPager that this view occupies.
         */
        int childIndex;

        public LayoutParams() {
            super(MATCH_PARENT, MATCH_PARENT);
        }

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }

}
