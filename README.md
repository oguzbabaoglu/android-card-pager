CardPager
=========
A modified ViewPager that behaves like a card stack.

Pages can be swiped left or right, moving onto the next page in the stack.
A common use case is to like or dislike content based on swipe direction.

![ ](/images/cardpager.gif)

Including In Your Project
-------------------------
```groovy
dependencies {
    compile 'com.oguzbabaoglu:cardpager:0.1'
}
```

Usage
-----
*For a working implementation see the `example/` folder.*

Usage is very similar to a normal `ViewPager`.

   1. Include a `CardPager` in your layout.
  
   ```xml
   <com.oguzbabaoglu.cardpager.CardPager
      xmlns:android="http://schemas.android.com/apk/res/android"
      android:id="@+id/view_cardpager"
      android:layout_width="match_parent"
      android:layout_height="match_parent"
   />
   ```
   2. Attach a `PagerAdapter`. 
   
   ```java
   CardPager cardPager = (CardPager) findViewById(R.id.view_cardpager);
   cardPager.setAdapter(new ColorAdapter(this));
   ```
  
   3. Implement a `OnCardChangeListener` to react to swipes.
   
   ```java
   cardPager.setOnCardChangeListener(new CardPager.SimpleOnCardChangeListener() {
      @Override
      public void onCardDismissed(int position, boolean right) {
          if (right) {
              onLike(position);
          } else {
              onDisLike(position);
          }
      }
  });
  ```

Todo
----

- Allow margins on pages.
- Allow transformers for different swipe effects.
- Add back Accessibility options.

License
=======

```
Copyright 2015 Oguz Babaoglu

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
