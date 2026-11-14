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

package com.android.documentsui.picker;

import static com.android.documentsui.base.Shared.getCallingAppName;
import static com.android.documentsui.util.Material3Config.getRes;

import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.documentsui.BaseActivity;
import com.android.documentsui.DocumentsUIDialogFragment;
import com.android.documentsui.R;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.Shared;
import com.android.modules.utils.build.SdkLevel;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Used to confirm with user that it's OK to overwrite an existing file. */
public class ConfirmFragment extends DocumentsUIDialogFragment {

    private static final String TAG = "ConfirmFragment";

    public static final String CONFIRM_TYPE = "type";
    public static final int TYPE_OVERWRITE = 1;
    public static final int TYPE_OEPN_TREE = 2;

    private ActionHandler<PickActivity> mActions;
    private DocumentInfo mTarget;
    private int mType;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mActions = ((PickActivity) getActivity()).getInjector().actions;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle arg = (getArguments() != null) ? getArguments() : savedInstanceState;

        mTarget = arg.getParcelable(Shared.EXTRA_DOC);
        mType = arg.getInt(CONFIRM_TYPE);
        final PickResult pickResult = ((PickActivity) getActivity()).getInjector().pickResult;
        final BaseActivity activity = (BaseActivity) getActivity();

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
        switch (mType) {
            case TYPE_OVERWRITE:
                String title = getString(getRes(R.string.overwrite_file_confirmation_title));
                String message =
                        String.format(
                                getString(getRes(R.string.overwrite_file_confirmation_message)),
                                mTarget.displayName);
                builder.setTitle(title);
                builder.setMessage(message);
                builder.setPositiveButton(
                        getRes(R.string.overwrite_file_confirmation_positive_button),
                        (DialogInterface dialog, int id) -> {
                            pickResult.increaseActionCount();
                            mActions.finishPicking(mTarget.getDocumentUri());
                        });
                break;
            case TYPE_OEPN_TREE:
                final Uri treeUri = mTarget.getTreeDocumentUri();
                final String target = activity.getCurrentTitle();
                // TODO(b/456014591): Remove the empty string once the translation is done.
                //  We remove one argument during the string update but still pass the empty string
                //  below, otherwise it will crash before the translation is done for other
                //  languages.
                final String text =
                        getString(
                                getRes(R.string.open_tree_dialog_title),
                                getCallingAppName(getActivity()),
                                "");
                message = getString(getRes(R.string.open_tree_dialog_message), target, "");

                builder.setTitle(text);
                builder.setMessage(message);
                builder.setPositiveButton(
                        getRes(R.string.allow),
                        (DialogInterface dialog, int id) -> {
                            pickResult.increaseActionCount();
                            mActions.finishPicking(treeUri);
                        });
                break;

        }
        builder.setNegativeButton(android.R.string.cancel,
                (DialogInterface dialog, int id) -> pickResult.increaseActionCount());

        Dialog dialog = builder.create();

        // Disable confirm button while tapjacking protection
        // is in effect to protect against actions that would
        // give access to whole directories or overwrite files.
        dialog.setOnShowListener(d -> {
            final Button button = ((AlertDialog) d).getButton(DialogInterface.BUTTON_POSITIVE);
            activity.setConfirmButtonUpdater(() -> {
                final boolean enabled = !activity.getDisplayState().itemsDisabled;
                button.setEnabled(enabled);
                button.setAlpha(enabled ? 1.0f : 0.5f);
            });
        });
        dialog.setOnDismissListener(d -> activity.setConfirmButtonUpdater(null));

        if (SdkLevel.isAtLeastS()) {
            dialog.getWindow().setHideOverlayWindows(true);
        }
        return dialog;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(Shared.EXTRA_DOC, mTarget);
        outState.putInt(CONFIRM_TYPE, mType);
    }

    public static void show(FragmentManager fm, DocumentInfo overwriteTarget, int type) {
        Bundle arg = new Bundle();
        arg.putParcelable(Shared.EXTRA_DOC, overwriteTarget);
        arg.putInt(CONFIRM_TYPE, type);

        FragmentTransaction ft = fm.beginTransaction();
        Fragment f = new ConfirmFragment();
        f.setArguments(arg);
        ft.add(f, TAG);
        ft.commitAllowingStateLoss();
    }
}
