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

package com.android.documentsui.picker;

import static com.android.documentsui.base.State.ACTION_CREATE;
import static com.android.documentsui.base.State.ACTION_GET_CONTENT;
import static com.android.documentsui.base.State.ACTION_OPEN;
import static com.android.documentsui.base.State.ACTION_OPEN_TREE;
import static com.android.documentsui.base.State.ACTION_PICK_COPY_DESTINATION;

import com.android.documentsui.ActivityConfig;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.MimeTypes;
import com.android.documentsui.base.State;

/**
 * Provides support for Platform specific specializations of DirectoryFragment.
 */
final class Config extends ActivityConfig {

    @Override
    public boolean canSelectType(DocumentInfo doc, State state, boolean isOnline) {
        if (!isDocumentEnabled(doc, state, isOnline)) {
            return false;
        }

        if (doc.isDirectory()) {
            return false;
        }

        if (state.action == ACTION_OPEN_TREE || state.action == ACTION_PICK_COPY_DESTINATION) {
            // In this case nothing *ever* is selectable...the expected user behavior is
            // they navigate *into* a folder, then click a confirmation button indicating
            // that the current directory is the directory they are picking.
            return false;
        }

        return true;
    }

    @Override
    public boolean isDocumentEnabled(DocumentInfo doc, State state, boolean isOnline) {
        if (!super.isDocumentEnabled(doc, state, isOnline)) {
            return false;
        }

        // Directories are always enabled.
        if (doc.isDirectory()) {
            return true;
        }

        // Disable the item while tapjacking protection is in effect.
        if (state.itemsDisabled) {
            return false;
        }

        switch (state.action) {
            case ACTION_PICK_COPY_DESTINATION:
                return false;
            case ACTION_CREATE:
                // Read-only files are disabled when creating.
                if (!doc.isWriteSupported()) {
                    return false;
                }
            case ACTION_OPEN:
            case ACTION_GET_CONTENT:
                if (doc.isVirtual() && state.openableOnly) {
                    return false;
                }
        }

        return MimeTypes.mimeMatches(state.acceptMimes, doc.mimeType);
    }
}
