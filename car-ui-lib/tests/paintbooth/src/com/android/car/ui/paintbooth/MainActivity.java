/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.ui.paintbooth;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.pagedrecyclerview.PagedRecyclerView;

import java.util.Arrays;
import java.util.List;

/**
 * Paint booth app
 */
public class MainActivity extends Activity {
    /**
     * List of all sample activities.
     */
    private List<Pair<String, Class<? extends Activity>>> mActivities = Arrays.asList(
            Pair.create("Dialogs sample page", DialogsActivity.class),
            Pair.create("PRV sample page", PagedRecyclerViewSamples.class)
    );

    private class ViewHolder extends RecyclerView.ViewHolder {
        private Button mButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            mButton = itemView.findViewById(R.id.button);
        }


        void update(String title, Class<? extends Activity> activityClass) {
            mButton.setText(title);
            mButton.setOnClickListener(e -> {
                Intent intent = new Intent(mButton.getContext(), activityClass);
                startActivity(intent);
            });
        }
    }

    private RecyclerView.Adapter mAdapter = new RecyclerView.Adapter() {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View item = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item, parent,
                    false);
            return new ViewHolder(item);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Pair<String, Class<? extends Activity>> item = mActivities.get(position);
            ((ViewHolder) holder).update(item.first, item.second);
        }

        @Override
        public int getItemCount() {
            return mActivities.size();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        PagedRecyclerView prv = findViewById(R.id.activities);
        prv.setAdapter(mAdapter);
    }
}