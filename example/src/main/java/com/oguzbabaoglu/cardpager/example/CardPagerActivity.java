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

package com.oguzbabaoglu.cardpager.example;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;

import com.oguzbabaoglu.cardpager.CardPager;

/**
 * @author Oguz Babaoglu
 */
public class CardPagerActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cardpager);

        CardPager cardPager = (CardPager) findViewById(R.id.view_cardpager);
        cardPager.setAdapter(new ColorAdapter(this));
    }
}
