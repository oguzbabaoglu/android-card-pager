package com.oguzbabaoglu.cardpager;

/**
 * Simple implementation of the {@link OnCardChangeListener} interface with stub
 * implementations of each method. Extend this if you do not intend to override
 * every method of {@link OnCardChangeListener}.
 */
public class SimpleOnCardChangeListener implements OnCardChangeListener {
  @Override public void onCardScrolled(int position, float positionOffset, int positionOffsetPixels) {
    // override
  }

  @Override public void onCardDismissed(int position, boolean right) {
    // override
  }

  @Override public void onCardScrollStateChanged(int state) {
    // override
  }
}
