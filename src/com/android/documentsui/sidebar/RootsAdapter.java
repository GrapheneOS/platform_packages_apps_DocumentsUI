/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.sidebar;

import static com.android.documentsui.util.FlagUtils.isUseMaterial3FlagEnabled;
import static com.android.documentsui.util.Material3Config.getRes;

import android.os.Looper;
import android.view.View;
import android.view.View.OnDragListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.documentsui.BaseActivity;
import com.android.documentsui.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The adapter for the {@link android.widget.ListView} in sidebar. It contains the list of
 * {@link Item}s and provides sub-views to {@link android.widget.ListView}.
 */
class RootsAdapter extends ArrayAdapter<Item> {
    private static final int TYPE_ROOT = 0;
    private static final int TYPE_APP = 1;
    private static final int TYPE_ROOT_AND_APP = 2;
    private static final int TYPE_SPACER = 3;

    private static final Map<String, Long> sIdMap = new HashMap<>();
    // the next available id to associate with a new string id
    private static long sNextAvailableId;

    private final OnDragListener mDragListener;
    private final BaseActivity mActivity;

    public RootsAdapter(
            BaseActivity activity,
            List<Item> items,
            OnDragListener dragListener) {
        super(activity, 0, items);

        mDragListener = dragListener;
        mActivity = activity;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public long getItemId(int position) {
        // Ensure this method is only called in main thread because we don't have any
        // concurrency protection.
        assert(Looper.myLooper() == Looper.getMainLooper());

        String stringId = getItem(position).stringId;

        long id;
        if (sIdMap.containsKey(stringId)) {
            id = sIdMap.get(stringId);
        } else {
            id = sNextAvailableId++;
            sIdMap.put(stringId, id);
        }

        return id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Item item = getItem(position);
        final View view = item.getView(convertView, parent);

        final boolean enabled = isEnabled(position);
        final boolean fullyVisible = isFullyVisible(position);
        view.setEnabled(enabled);
        view.setAlpha(fullyVisible ? 1.0f : 0.5f);
        final View actionIcon = view.findViewById(getRes(R.id.action_icon));
        if (actionIcon != null) {
            actionIcon.setEnabled(enabled);
        }
        final View actionIconArea = view.findViewById(getRes(R.id.action_icon_area));
        if (actionIconArea != null) {
            actionIconArea.setEnabled(enabled);
        }

        if (isUseMaterial3FlagEnabled()) {
            // In order to have hover showing on the list item, we need to have
            // "android:clickable=true" on the list item level, which will break the click handler
            // because it's set at the list level, so here we "bubble up" the item level click
            // event to the list level by explicitly calling the "performItemClick" on the list
            // level.
            view.setOnClickListener(
                    v -> ((ListView) parent).performItemClick(v, position, getItemId(position)));
        }

        if (item.isRoot() || item.isShortcut()) {
            view.setTag(getRes(R.id.item_position_tag), position);
            view.setOnDragListener(mDragListener);
        } else {
            view.setTag(getRes(R.id.item_position_tag), null);
            view.setOnDragListener(null);
        }
        return view;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        if (getItemViewType(position) == TYPE_SPACER) {
            return false;
        }
        return isFullyVisible(position);
    }

    private boolean isFullyVisible(int position) {
        if (getItemViewType(position) == TYPE_ROOT_AND_APP ||
                getItemViewType(position) == TYPE_APP) {
            if (mActivity.getDisplayState().itemsDisabled) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        final Item item = getItem(position);
        if (item instanceof RootAndAppItem) {
            return TYPE_ROOT_AND_APP;
        } else if (item instanceof BaseSidebarEntryItem) {
            return TYPE_ROOT;
        } else if (item instanceof AppItem) {
            return TYPE_APP;
        } else {
            return TYPE_SPACER;
        }
    }

    @Override
    public int getViewTypeCount() {
        return 4;
    }
}
