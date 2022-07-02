/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.base;

import static com.android.documentsui.util.FlagUtils.isHomeScreenFilesFlagEnabled;

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;

import com.android.documentsui.ConfigStore;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.sorting.SortModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class State implements android.os.Parcelable {

    private static final String TAG = "State";

    private boolean mIsShowHiddenFiles;

    @IntDef(flag = true, value = {
            ACTION_BROWSE,
            ACTION_PICK_COPY_DESTINATION,
            ACTION_OPEN,
            ACTION_CREATE,
            ACTION_GET_CONTENT,
            ACTION_OPEN_TREE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActionType {
    }

    // File manager and related private picking activity.
    public static final int ACTION_BROWSE = 1;
    public static final int ACTION_PICK_COPY_DESTINATION = 2;
    // All public picking activities
    public static final int ACTION_OPEN = 3;
    public static final int ACTION_CREATE = 4;
    public static final int ACTION_GET_CONTENT = 5;
    public static final int ACTION_OPEN_TREE = 6;

    @IntDef(flag = true, value = {
            MODE_UNKNOWN,
            MODE_LIST,
            MODE_GRID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ViewMode {
    }

    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_LIST = 1;
    public static final int MODE_GRID = 2;

    public @ActionType int action;
    public String[] acceptMimes;

    /** Derived from local preferences */
    public @ViewMode int derivedMode = MODE_GRID;

    public boolean debugMode = false;

    /** Current sort state */
    public SortModel sortModel;

    public boolean allowMultiple;

    /** Represents the set of user ids that should be excluded from the picker. */
    public Set<Integer> excludedUserIds = Collections.emptySet();

    public boolean localOnly;
    public ArrayList<String> allowedAuthorities;

    public boolean openableOnly;
    public boolean restrictScopeStorage;
    public ConfigStore configStore = new ConfigStore.ConfigStoreImpl();

    /**
     * Represents whether the state supports cross-profile file picking.
     */
    public boolean supportsCrossProfile = false;

    /**
     * Represents whether the intent is a cross-profile intent
     */
    public boolean canShareAcrossProfile = false;

    /**
     * Returns true if we are allowed to interact with the user.
     */
    public boolean canInteractWith(UserId userId) {
        if (configStore.isPrivateSpaceInDocsUIEnabled()) {
            if (canForwardToProfileIdMap.isEmpty() && UserId.CURRENT_USER.equals(userId)) {
                return true;
            }
            return canForwardToProfileIdMap.getOrDefault(userId, false);
        }
        return canShareAcrossProfile || UserId.CURRENT_USER.equals(userId);
    }

    /**
     * Represents whether the intent can be forwarded to the {@link UserId} in the map
     */
    public Map<UserId, Boolean> canForwardToProfileIdMap = new HashMap<>();


    /**
     * This is basically a sub-type for the copy operation. It can be either COPY,
     * COMPRESS, EXTRACT or MOVE.
     * The only legal values, if set, are: OPERATION_COPY, OPERATION_COMPRESS,
     * OPERATION_EXTRACT and OPERATION_MOVE. Other pick
     * operations don't use this. In those cases OPERATION_UNKNOWN is also legal.
     */
    public @OpType int copyOperationSubType = FileOperationService.OPERATION_UNKNOWN;

    /** Current user navigation stack; empty implies recents. */
    public final DocumentStack stack = new DocumentStack();

    /**
     * Stores a ShortcutInfo reference of the currently selected shortcut. If a root is selected
     * instead, this value will be null.
     */
    @Nullable
    public ShortcutInfo shortcut;

    /** Instance configs for every shown directory */
    public HashMap<String, SparseArray<Parcelable>> dirConfigs = new HashMap<>();

    /** Name of the package that started DocsUI */
    public List<String> excludedAuthorities = new ArrayList<>();

    public void initAcceptMimes(Intent intent, String defaultAcceptMimeType) {
        if (intent.hasExtra(Intent.EXTRA_MIME_TYPES)) {
            acceptMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        } else {
            acceptMimes = new String[]{defaultAcceptMimeType};
        }
    }

    /**
     * Check current action should have preview function or not.
     *
     * @param showPreviewIconConfigValue the show_preview_icon resource boolean.
     * @return True, if the action should have preview.
     */
    public boolean shouldShowPreview(boolean showPreviewIconConfigValue) {
        return showPreviewIconConfigValue
                && (action == ACTION_GET_CONTENT
                        || action == ACTION_OPEN_TREE
                        || action == ACTION_OPEN);
    }

    /**
     * Check the action is file picking and acceptMimes are all images type or not.
     *
     * @return True, if acceptMimes are all image type and action is file picking.
     */
    public boolean isPhotoPicking() {
        if (action != ACTION_GET_CONTENT && action != ACTION_OPEN || acceptMimes == null) {
            return false;
        }

        for (String mimeType : acceptMimes) {
            if (!MimeTypes.mimeMatches(MimeTypes.IMAGE_MIME, mimeType)) {
                return false;
            }
        }
        return true;
    }

    public String getTitleAtPosition(int pos) {
        if (pos == 0 && shortcut != null) {
            return shortcut.getTitle();
        } else if ((pos == 0 || stack.isEmpty()) && stack.getRoot() != null) {
            return stack.getRoot().title;
        } else if (!stack.isEmpty() && pos < stack.size()) {
            return stack.get(pos).displayName;
        }
        return null;
    }

    /**
     * Returns true if DocsUI supports cross-profile for this {@link State}.
     */
    public boolean supportsCrossProfile() {
        return supportsCrossProfile;
    }

    /**
     * Sets whether hidden files should be shown.
     *
     * @param showHiddenFiles True to show hidden files, false otherwise.
     */
    public void setIsShowHiddenFiles(boolean showHiddenFiles) {
        this.mIsShowHiddenFiles = showHiddenFiles;
    }

    /**
     * Returns true if hidden files should be shown.
     *
     * @return True if hidden files should be shown, false otherwise.
     */
    public boolean shouldShowHiddenFiles() {
        // Hidden files are always shown in trash root.
        if (stack.isTrashRoot()) {
            return true;
        }

        return mIsShowHiddenFiles;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(action);
        out.writeStringArray(acceptMimes);
        out.writeInt(allowMultiple ? 1 : 0);
        out.writeInt(localOnly ? 1 : 0);
        out.writeStringList(allowedAuthorities);
        DurableUtils.writeToParcel(out, stack);
        if (isHomeScreenFilesFlagEnabled()) {
            out.writeBoolean(/*has shortcut*/ shortcut != null);
            if (shortcut != null) {
                DurableUtils.writeToParcel(out, shortcut);
            }
        }
        out.writeMap(dirConfigs);
        out.writeList(excludedAuthorities);
        out.writeInt(openableOnly ? 1 : 0);
        out.writeInt(restrictScopeStorage ? 1 : 0);
        out.writeParcelable(sortModel, 0);
        out.writeInt(/*excluded users count*/ excludedUserIds.size());
        if (!excludedUserIds.isEmpty()) {
            out.writeIntArray(excludedUserIds.stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
        }
    }

    @Override
    public String toString() {
        return "State{"
                + "action=" + action
                + ", acceptMimes=" + Arrays.toString(acceptMimes)
                + ", allowMultiple=" + allowMultiple
                + ", localOnly=" + localOnly
                + ", stack=" + stack
                + ", shortcut=" + shortcut
                + ", dirConfigs=" + dirConfigs
                + ", excludedAuthorities=" + excludedAuthorities
                + ", openableOnly=" + openableOnly
                + ", restrictScopeStorage=" + restrictScopeStorage
                + ", sortModel=" + sortModel
                + ", excludedUserIds=" + excludedUserIds
                + "}";
    }

    public static final ClassLoaderCreator<State> CREATOR = new ClassLoaderCreator<State>() {
        @Override
        public State createFromParcel(Parcel in) {
            return createFromParcel(in, null);
        }

        @Override
        public State createFromParcel(Parcel in, ClassLoader loader) {
            final State state = new State();
            state.action = in.readInt();
            state.acceptMimes = in.createStringArray();
            state.allowMultiple = in.readInt() != 0;
            state.localOnly = in.readInt() != 0;
            state.allowedAuthorities = in.createStringArrayList();
            DurableUtils.readFromParcel(in, state.stack);
            if (isHomeScreenFilesFlagEnabled()) {
                boolean hasShortcut = in.readBoolean();
                if (hasShortcut) {
                    state.shortcut = new ShortcutInfo();
                    DurableUtils.readFromParcel(in, state.shortcut);
                }
            }
            in.readMap(state.dirConfigs, loader);
            in.readList(state.excludedAuthorities, loader);
            state.openableOnly = in.readInt() != 0;
            state.restrictScopeStorage = in.readInt() != 0;
            state.sortModel = in.readParcelable(loader);
            int excludedUsersCount = in.readInt();
            if (excludedUsersCount > 0) {
                int[] excludedUsers = new int[excludedUsersCount];
                in.readIntArray(excludedUsers);
                state.excludedUserIds = Arrays.stream(excludedUsers)
                        .boxed()
                        .collect(Collectors.toSet());
            }
            return state;
        }

        @Override
        public State[] newArray(int size) {
            return new State[size];
        }
    };
}
