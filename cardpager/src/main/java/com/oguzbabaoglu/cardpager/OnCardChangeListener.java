package com.oguzbabaoglu.cardpager;

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
