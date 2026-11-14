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

package com.android.documentsui.sidebar;

import static com.android.documentsui.base.Shared.compareToIgnoreCaseNullable;
import static com.android.documentsui.base.SharedMinimal.DEBUG;
import static com.android.documentsui.base.SharedMinimal.VERBOSE;
import static com.android.documentsui.util.FlagUtils.isHomeScreenFilesFlagEnabled;
import static com.android.documentsui.util.FlagUtils.isTrashFlowEnabled;
import static com.android.documentsui.util.FlagUtils.isUseMaterial3FlagEnabled;
import static com.android.documentsui.util.Material3Config.getRes;

import static java.util.Objects.requireNonNull;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.os.ext.SdkExtensions;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnDragListener;
import android.view.View.OnGenericMotionListener;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.RecyclerView;

import com.android.documentsui.ActionHandler;
import com.android.documentsui.BaseActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.DragHoverListener;
import com.android.documentsui.Injector;
import com.android.documentsui.Injector.Injected;
import com.android.documentsui.ItemDragListener;
import com.android.documentsui.R;
import com.android.documentsui.TimeoutTask;
import com.android.documentsui.UserManagerState;
import com.android.documentsui.UserPackage;
import com.android.documentsui.base.BooleanConsumer;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.DocumentStack;
import com.android.documentsui.base.Events;
import com.android.documentsui.base.Features;
import com.android.documentsui.base.Providers;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.base.ShortcutInfo;
import com.android.documentsui.base.SidebarEntryItemInfo;
import com.android.documentsui.base.State;
import com.android.documentsui.base.UserId;
import com.android.documentsui.dirlist.AnimationView;
import com.android.documentsui.loaders.LoaderIds;
import com.android.documentsui.roots.ProvidersAccess;
import com.android.documentsui.roots.ProvidersCache;
import com.android.documentsui.roots.RootsLoader;
import com.android.documentsui.roots.ShortcutsLoader;
import com.android.documentsui.util.CrossProfileUtils;
import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Display list of known storage backend roots.
 * This fragment will be used in:
 * * fixed_layout: as navigation tree (sidebar)
 * * drawer_layout: as navigation drawer
 * * nav_rail_layout: as navigation drawer and navigation rail.
 */
public class RootsFragment extends Fragment {

    private static final String TAG = "RootsFragment";
    private static final String EXTRA_INCLUDE_APPS = "includeApps";
    private static final String EXTRA_INCLUDE_APPS_INTENT = "includeAppsIntent";
    /**
     * A key used to store the container id in the RootFragment.
     * RootFragment is used in both navigation drawer and navigation rail, there are 2 instances
     * of the fragment rendered on the page, we need to know which one is which to render different
     * nav items inside.
     */
    private static final String EXTRA_CONTAINER_ID = "containerId";
    private static final int CONTEXT_MENU_ITEM_TIMEOUT = 500;
    private static final String LOADER_REFRESH_ROOT_AND_DIRECTORY_ID = "refreshRootAndDirectory";

    private RootsListHandler mListHandler;
    private LoaderCallbacks<Collection<RootInfo>> mRootsCallbacks;
    private LoaderCallbacks<Collection<ShortcutInfo>> mShortcutsCallbacks;
    private Collection<RootInfo> mLoadedRoots;
    private @Nullable OnDragListener mDragListener;

    @Injected
    private Injector<?> mInjector;

    @Injected
    private ActionHandler mActionHandler;

    private List<SortableItem> mApplicationItemList;

    // Weather the fragment is using nav_rail_container_roots as its container (in nav_rail_layout).
    // This will always be false if isUseMaterial3FlagEnabled() flag is off.
    private boolean mUseRailAsContainer = false;

    // Maintain state of whether a root and directory refresh is pending.
    private boolean mRefreshPending = false;

    /**
     * Show the RootsFragment inside the navigation drawer container.
     */
    public static RootsFragment show(FragmentManager fm, boolean includeApps, Intent intent) {
        return showWithLayout(getRes(R.id.container_roots), fm, includeApps, intent);
    }

    /**
     * Show the RootsFragment inside the navigation rail container.
     */
    public static RootsFragment showNavRail(FragmentManager fm, boolean includeApps,
            Intent intent) {
        return showWithLayout(getRes(R.id.nav_rail_container_roots), fm, includeApps, intent);
    }

    /**
     * Shows the {@link RootsFragment}.
     *
     * @param containerId the container id where the {@link RootsFragment} will be rendered into
     * @param fm          the FragmentManager for interacting with fragments associated with this
     *                    fragment's activity
     * @param includeApps if {@code true}, query the intent from the system and include apps in
     *                    the {@RootsFragment}.
     * @param intent      the intent to query for package manager
     */
    private static RootsFragment showWithLayout(
            @IdRes int containerId, FragmentManager fm, boolean includeApps, Intent intent) {
        final Bundle args = new Bundle();
        args.putBoolean(EXTRA_INCLUDE_APPS, includeApps);
        args.putParcelable(EXTRA_INCLUDE_APPS_INTENT, intent);
        if (isUseMaterial3FlagEnabled()) {
            args.putInt(EXTRA_CONTAINER_ID, containerId);
        }

        final RootsFragment fragment = new RootsFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(containerId, fragment);
        ft.commitAllowingStateLoss();

        return fragment;
    }

    /**
     * Get the RootsFragment instance for the navigation drawer.
     */
    public static RootsFragment get(FragmentManager fm) {
        return (RootsFragment) fm.findFragmentById(getRes(R.id.container_roots));
    }

    /**
     * Get the RootsFragment instance for the navigation drawer.
     */
    public static RootsFragment getNavRail(FragmentManager fm) {
        return (RootsFragment) fm.findFragmentById(getRes(R.id.nav_rail_container_roots));
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        if (isUseMaterial3FlagEnabled()) {
            mUseRailAsContainer =
                    getArguments() != null
                            && getArguments().getInt(EXTRA_CONTAINER_ID)
                                    == getRes(R.id.nav_rail_container_roots);
        }

        mInjector = getBaseActivity().getInjector();

        final View view =
                inflater.inflate(
                        mUseRailAsContainer
                                ? getRes(R.layout.fragment_nav_rail_roots)
                                : getRes(R.layout.fragment_roots),
                        container,
                        false);
        if (isUseMaterial3FlagEnabled()) {
            final RecyclerView recyclerView = view.findViewById(getRes(R.id.roots_list));
            mListHandler = new RootsRecyclerViewHandler(requireNonNull(recyclerView));
        } else {
            final ListView listView = view.findViewById(getRes(R.id.roots_list));
            mListHandler = new RootsListViewHandler(requireNonNull(listView));
        }
        mListHandler.setup(getBaseActivity());
        // ListView does not have right-click specific listeners, so we will have a
        // GenericMotionListener to listen for it.
        // Currently, right click is viewed the same as long press, so we will have to quickly
        // register for context menu when we receive a right click event, and quickly unregister
        // it afterwards to prevent context menus popping up upon long presses.
        // All other motion events will then get passed to OnItemClickListener.
        mListHandler.setOnGenericMotionListener(new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (Events.isMousyEvent(event)
                        && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                    int x = (int) event.getX();
                    int y = (int) event.getY();
                    return onRightClick(v, x, y, () -> {
                        mInjector.menuManager.showContextMenu(
                                RootsFragment.this, v, x, y);
                    });
                }
                return false;
            }
        });
        return view;
    }

    private boolean onRightClick(View v, int x, int y, Runnable callback) {
        Item item = mListHandler.getItemFromViewUnder(x, y);

        // If a read-only root, no need to see if top level is writable (it's not)
        if (!(item instanceof BaseSidebarEntryItem)
                || !((BaseSidebarEntryItem) item).getItemInfo().supportsCreate()) {
            return false;
        }

        final BaseSidebarEntryItem sidebarItem = (BaseSidebarEntryItem) item;
        getSidebarItemDocument(sidebarItem, (DocumentInfo doc) -> {
                sidebarItem.setDocInfo(doc);
                callback.run();
        });
        return true;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final BaseActivity activity = getBaseActivity();
        final ProvidersCache providers = DocumentsApplication.getProvidersCache(activity);
        final State state = activity.getDisplayState();

        mActionHandler = mInjector.actions;

        if (mInjector.config.dragAndDropEnabled()) {
            final DragHost host = new DragHost(
                    activity,
                    DocumentsApplication.getDragAndDropManager(activity),
                    this::getItem,
                    mActionHandler);
            final ItemDragListener<DragHost> listener =
                    new ItemDragListener<DragHost>(host) {
                        @Override
                        public boolean handleDropEventChecked(View v, DragEvent event) {
                            final Item item = getItem(v);

                            assert (item.isRoot() || item.isShortcut());

                            return item.dropOn(event);
                        }
                    };
            mDragListener = mListHandler.createDragListener(listener);
        }

        mShortcutsCallbacks =
                new LoaderCallbacks<>() {
                    private Bundle mArgs;

                    @NonNull
                    @Override
                    public Loader<Collection<ShortcutInfo>> onCreateLoader(
                            int id, @Nullable Bundle args) {
                        mArgs = args;
                        return new ShortcutsLoader(
                                getContext(), providers, activity.getSelectedUser());
                    }

                    @Override
                    public void onLoadFinished(
                            @NonNull Loader<Collection<ShortcutInfo>> loader,
                            Collection<ShortcutInfo> shortcuts) {
                        if (!isHomeScreenFilesFlagEnabled()) {
                            shortcuts = new ArrayList<>();
                        }
                        loadFinished(mLoadedRoots, shortcuts, activity, state);
                        if (isHomeScreenFilesFlagEnabled()
                                && mArgs != null
                                && mArgs.getBoolean(LOADER_REFRESH_ROOT_AND_DIRECTORY_ID)) {
                            // Only refresh the current window - we don't want to cancel current
                            // search results.
                            getBaseActivity()
                                    .refreshCurrentRootAndDirectoryWithoutSearch(
                                            AnimationView.ANIM_NONE);
                        }
                    }

                    @Override
                    public void onLoaderReset(@NonNull Loader<Collection<ShortcutInfo>> loader) {
                        mListHandler.resetAdapter();
                    }
                };

        mRootsCallbacks =
                new LoaderCallbacks<>() {
                    private Bundle mArgs;

                    @Override
                    public Loader<Collection<RootInfo>> onCreateLoader(int id, Bundle args) {
                        mArgs = args;
                        return new RootsLoader(activity, providers, state);
                    }

                    @Override
                    public void onLoadFinished(
                            Loader<Collection<RootInfo>> loader, Collection<RootInfo> roots) {
                        if (!isAdded()) {
                            return;
                        }

                        if (isHomeScreenFilesFlagEnabled()) {
                            mLoadedRoots = roots;
                            // Load the shortcut roots next
                            LoaderManager.getInstance(RootsFragment.this)
                                    .restartLoader(LoaderIds.SHORTCUTS, mArgs, mShortcutsCallbacks);
                            mArgs = null;
                            return;
                        }
                        loadFinished(roots, new ArrayList<>(), activity, state);
                        mArgs = null;
                    }

                    @Override
                    public void onLoaderReset(Loader<Collection<RootInfo>> loader) {
                        mListHandler.resetAdapter();
                    }
                };
    }

    @VisibleForTesting
    public void setDragSpringTimeoutForTest(int testDragSpringTimeout) {
        if (mDragListener instanceof DragHoverListener) {
            ((DragHoverListener) mDragListener).setDragSpringTimeoutForTest(testDragSpringTimeout);
        }
    }

    public void reloadRootsAndShortcuts(boolean refreshRootAndDirectory) {
        // Prevent refresh from being overwritten by repetitive calls during config changes.
        mRefreshPending |= refreshRootAndDirectory;
        Bundle args = new Bundle();
        args.putBoolean(LOADER_REFRESH_ROOT_AND_DIRECTORY_ID, mRefreshPending);
        LoaderManager.getInstance(this).restartLoader(LoaderIds.ROOTS, args, mRootsCallbacks);
    }

    @VisibleForTesting
    public void loadFinished(Collection<RootInfo> roots, Collection<ShortcutInfo> shortcuts,
            BaseActivity activity, State state) {
        boolean shouldIncludeHandlerApp = getArguments().getBoolean(EXTRA_INCLUDE_APPS,
            /* defaultValue= */ false);
        Intent handlerAppIntent = getArguments().getParcelable(EXTRA_INCLUDE_APPS_INTENT);

        final Intent intent = activity.getIntent();
        final boolean excludeSelf =
            intent.getBooleanExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, false);
        final String excludePackage = excludeSelf ? activity.getCallingPackage() : null;
        final boolean maybeShowBadge =
            getBaseActivity().getDisplayState().supportsCrossProfile();

        // For action which supports cross profile, update the policy value in state if
        // necessary.
        ResolveInfo crossProfileResolveInfo = null;
        UserManagerState userManagerState = null;
        if (state.supportsCrossProfile() && handlerAppIntent != null) {
            if (state.configStore.isPrivateSpaceInDocsUIEnabled()
                && SdkLevel.isAtLeastS()) {
                userManagerState = DocumentsApplication.getUserManagerState(getContext());
                Map<UserId, Boolean> canForwardToProfileIdMap =
                    userManagerState.getCanForwardToProfileIdMapForAllowedUsers(intent, state);
                updateCrossProfileMapStateAndMaybeRefresh(canForwardToProfileIdMap);
            } else {
                crossProfileResolveInfo = CrossProfileUtils.getCrossProfileResolveInfo(
                    UserId.CURRENT_USER, getContext().getPackageManager(),
                    handlerAppIntent, getContext(),
                    state.configStore.isPrivateSpaceInDocsUIEnabled());
                updateCrossProfileStateAndMaybeRefresh(
                    /* canShareAcrossProfile= */ crossProfileResolveInfo != null);
            }
        }

        if (state.configStore.isPrivateSpaceInDocsUIEnabled() && userManagerState == null) {
            userManagerState = DocumentsApplication.getUserManagerState(getContext());
        }

        List<UserId> userIds;
        if (state.configStore.isPrivateSpaceInDocsUIEnabled() && SdkLevel.isAtLeastS()) {
            userIds = DocumentsApplication.getUserManagerState(getContext()).getUserIds();
        } else {
            userIds = DocumentsApplication.getUserIdManager(getContext()).getUserIds();
        }

        List<Item> sortedItems = sortLoadResult(
            getContext(),
            state,
            roots,
            shortcuts,
            excludePackage,
            shouldIncludeHandlerApp ? handlerAppIntent : null,
            DocumentsApplication.getProvidersCache(getContext()),
            getBaseActivity().getSelectedUser(),
            userIds,
            maybeShowBadge,
            userManagerState);

        // This will be removed when feature flag is removed.
        if (crossProfileResolveInfo != null && !Features.CROSS_PROFILE_TABS) {
            // Add profile item if we don't support cross-profile tab.
            sortedItems.add(new SpacerItem());
            if (mUseRailAsContainer) {
                sortedItems.add(new NavRailProfileItem(crossProfileResolveInfo,
                    crossProfileResolveInfo.loadLabel(
                        getContext().getPackageManager()).toString(), mActionHandler));
            } else {
                sortedItems.add(new ProfileItem(crossProfileResolveInfo,
                    crossProfileResolveInfo.loadLabel(
                        getContext().getPackageManager()).toString(), mActionHandler));
            }
        }

        // Disable drawer if only one root
        activity.setRootsDrawerLocked(sortedItems.size() <= 1);

        mListHandler.scrollToFirstVisiblePosition(sortedItems, mDragListener);

        mInjector.shortcutsUpdater.accept(roots);
        mInjector.appsRowManager.updateList(mApplicationItemList);
        mInjector.appsRowManager.updateView(activity);
        onCurrentRootChanged();
        mRefreshPending = false;
    }


    /**
     * Updates the state values of whether we can share across profiles, if necessary. Also reload
     * documents stack if the selected user is not the current user.
     */
    private void updateCrossProfileStateAndMaybeRefresh(boolean canShareAcrossProfile) {
        final State state = getBaseActivity().getDisplayState();
        if (state.canShareAcrossProfile != canShareAcrossProfile) {
            state.canShareAcrossProfile = canShareAcrossProfile;
            if (!UserId.CURRENT_USER.equals(getBaseActivity().getSelectedUser())) {
                mActionHandler.loadDocumentsForCurrentStack();
            }
        }
    }

    private void updateCrossProfileMapStateAndMaybeRefresh(
            Map<UserId, Boolean> canForwardToProfileIdMap) {
        final State state = getBaseActivity().getDisplayState();
        if (!state.canForwardToProfileIdMap.equals(canForwardToProfileIdMap)) {
            state.canForwardToProfileIdMap = canForwardToProfileIdMap;
            if (!UserId.CURRENT_USER.equals(getBaseActivity().getSelectedUser())) {
                mActionHandler.loadDocumentsForCurrentStack();
            }
        }
    }

    /**
     * If the package name of other providers or apps capable of handling the original intent
     * include the preferred root source, it will have higher order than others.
     *
     * @param excludePackage   Exclude activities from this given package
     * @param handlerAppIntent When not null, apps capable of handling the original intent will
     *                         be included in list of roots (in special section at bottom).
     */
    @VisibleForTesting
    List<Item> sortLoadResult(
            Context context,
            State state,
            Collection<RootInfo> roots,
            Collection<ShortcutInfo> shortcuts,
            @Nullable String excludePackage,
            @Nullable Intent handlerAppIntent,
            ProvidersAccess providersAccess,
            UserId selectedUser,
            List<UserId> userIds,
            boolean maybeShowBadge,
            UserManagerState userManagerState) {
        final List<Item> result = new ArrayList<>();

        final RootItemListBuilder librariesBuilder = new RootItemListBuilder(selectedUser, userIds);
        final RootItemListBuilder storageProvidersBuilder = new RootItemListBuilder(selectedUser,
                userIds);
        final List<RootItem> otherProviders = new ArrayList<>();
        final List<Item> trashItems = new ArrayList<>();
        final boolean hideMediaRoots =
                isUseMaterial3FlagEnabled()
                        && !context.getResources().getBoolean(R.bool.show_media_roots);

        boolean hasDownloadsOverlay = false;

        final List<BaseSidebarEntryItem> librariesAndShortcuts = new ArrayList<>();
        if (isHomeScreenFilesFlagEnabled()) {
            // Handle the shortcuts next. The shortcuts passed in are specific to the user. So we
            // can just create and add the shortcut items normally as it should already account for
            // cross profile behaviour.
            for (final ShortcutInfo shortcut : shortcuts) {
                if (shortcut.getDerivedType() == SidebarEntryItemInfo.TYPE_DOWNLOADS) {
                    hasDownloadsOverlay = true;
                }
                final ShortcutItem item =
                        mUseRailAsContainer
                                ? new NavRailShortcutItem(
                                        shortcut,
                                        mActionHandler,
                                        /* packageName= */ "",
                                        maybeShowBadge)
                                : new ShortcutItem(
                                        shortcut,
                                        mActionHandler,
                                        /* packageName= */ "",
                                        maybeShowBadge);
                librariesAndShortcuts.add(item);
            }
        }

        for (final RootInfo root : roots) {
            final RootItem item;

            if (root.isExternalStorageHome()) {
                // No-op.
            } else if (root.isFiles()) {
                // Never show this if MediaDocumentsProvider is serving it, it's for Recents only.
            } else if (root.isLocalSearch(context)) {
                // Local search provider is integrated with other providers, not to browse the
                // files.
            } else if (hideMediaRoots
                    && (root.isImages()
                            || root.isVideos()
                            || root.isDocuments()
                            || root.isAudio())) {
                Log.d(TAG, "Hiding " + root);
            } else if (isHomeScreenFilesFlagEnabled()
                    && root.isDownloads()
                    && hasDownloadsOverlay) {
                // Hide the DownloadStorageProvider root if we have a shortcut to the Downloads
                // folder via ExternalStorageProvider.
                Log.d(TAG, "Hiding DownloadStorageProvider root: " + root);
            } else if (root.isLibrary() || root.isDownloads()) {
                item =
                        mUseRailAsContainer
                                ? new NavRailRootItem(root, mActionHandler, maybeShowBadge)
                                : new RootItem(root, mActionHandler, maybeShowBadge);
                librariesBuilder.add(item);
            } else if (root.isStorage()) {
                item =
                        mUseRailAsContainer
                                ? new NavRailRootItem(root, mActionHandler, maybeShowBadge)
                                : new RootItem(root, mActionHandler, maybeShowBadge);
                storageProvidersBuilder.add(item);
            } else if (isTrashFlowEnabled() && root.isTrash()) {
                item =
                        mUseRailAsContainer
                                ? new NavRailRootItem(root, mActionHandler, maybeShowBadge)
                                : new RootItem(root, mActionHandler, maybeShowBadge);
                trashItems.add(item);
            } else if (root.authority != null) {
                item =
                        mUseRailAsContainer
                                ? new NavRailRootItem(
                                        root,
                                        mActionHandler,
                                        providersAccess.getPackageName(root.userId, root.authority),
                                        maybeShowBadge)
                                : new RootItem(
                                        root,
                                        mActionHandler,
                                        providersAccess.getPackageName(root.userId, root.authority),
                                        maybeShowBadge);
                otherProviders.add(item);
            }
        }

        final RootComparator comp = new RootComparator();
        final List<RootItem> libraries = librariesBuilder.getList();
        final List<RootItem> storageProviders = storageProvidersBuilder.getList();

        if (isHomeScreenFilesFlagEnabled()) {
            final SidebarEntryItemComparator sidebarItemComp = new SidebarEntryItemComparator();
            librariesAndShortcuts.addAll(libraries);
            Collections.sort(librariesAndShortcuts, sidebarItemComp);
            Collections.sort(storageProviders, comp);

            if (VERBOSE) {
                Log.v(TAG, "Adding library roots and system defined shortcuts: "
                        + librariesAndShortcuts);
            }
            result.addAll(librariesAndShortcuts);
        } else {
            Collections.sort(libraries, comp);
            Collections.sort(storageProviders, comp);

            if (VERBOSE) Log.v(TAG, "Adding library roots: " + libraries);
            result.addAll(libraries);
        }

        // Only add the spacer if it is actually separating something.
        if (!result.isEmpty() && !storageProviders.isEmpty()) {
            result.add(new SpacerItem());
        }
        if (VERBOSE) Log.v(TAG, "Adding storage roots: " + storageProviders);
        result.addAll(storageProviders);

        final List<SortableItem> rootList = new ArrayList<>();
        final List<SortableItem> rootListOtherUser = new ArrayList<>();
        final List<List<SortableItem>> rootListAllUsers = new ArrayList<>();
        for (int i = 0; i < userIds.size(); ++i) {
            rootListAllUsers.add(new ArrayList<>());
        }

        mApplicationItemList = new ArrayList<>();
        if (handlerAppIntent != null) {
            includeHandlerApps(state, handlerAppIntent, excludePackage, rootList, rootListOtherUser,
                    rootListAllUsers, otherProviders, userIds, maybeShowBadge);
        } else {
            // Only add providers
            otherProviders.sort(comp);
            for (RootItem item : otherProviders) {
                if (state.configStore.isPrivateSpaceInDocsUIEnabled()) {
                    createRootListsPrivateSpaceEnabled(item, userIds, rootListAllUsers);
                } else {
                    createRootListsPrivateSpaceDisabled(item, rootList, rootListOtherUser);
                }
                mApplicationItemList.add(item);
            }
        }

        List<Item> presentableList =
                state.configStore.isPrivateSpaceInDocsUIEnabled() && SdkLevel.isAtLeastS()
                        ? getPresentableListPrivateSpaceEnabled(
                        context, state, rootListAllUsers, userIds, userManagerState) :
                        getPresentableListPrivateSpaceDisabled(context, state, rootList,
                                rootListOtherUser);

        if (isHomeScreenFilesFlagEnabled()) {
            List<Item> presentableListWithDivider = new ArrayList<>();
            boolean hasBaseSidebarItems = false;
            for (Item item : presentableList) {
                if (item instanceof BaseSidebarEntryItem) {
                    hasBaseSidebarItems = true;
                }
                if (hasBaseSidebarItems && item instanceof AppItem) {
                    presentableListWithDivider.add(new SpacerItem());
                    hasBaseSidebarItems = false;
                }
                presentableListWithDivider.add(item);
            }
            result.addAll(presentableListWithDivider);
        } else {
            addListToResult(result, presentableList);
        }
        addListToResult(result, trashItems);
        return result;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private List<Item> getPresentableListPrivateSpaceEnabled(
            Context context,
            State state,
            List<List<SortableItem>> rootListAllUsers,
            List<UserId> userIds,
            UserManagerState userManagerState) {
        return new UserItemsCombiner(
                        context.getResources(),
                        context.getSystemService(UserManager.class),
                        context.getSystemService(DevicePolicyManager.class),
                        state)
                .setRootListForAllUsers(rootListAllUsers)
                .createPresentableListForAllUsers(userIds, userManagerState.getUserIdToLabelMap());
    }

    private List<Item> getPresentableListPrivateSpaceDisabled(
            Context context,
            State state,
            List<SortableItem> rootList,
            List<SortableItem> rootListOtherUser) {
        return new UserItemsCombiner(
                        context.getResources(),
                        context.getSystemService(UserManager.class),
                        context.getSystemService(DevicePolicyManager.class),
                        state)
                .setRootListForCurrentUser(rootList)
                .setRootListForOtherUser(rootListOtherUser)
                .createPresentableList();
    }

    private void addListToResult(List<Item> result, List<Item> rootList) {
        if (!result.isEmpty() && !rootList.isEmpty()) {
            result.add(new SpacerItem());
        }
        result.addAll(rootList);
    }

    /**
     * Adds apps capable of handling the original intent will be included in list of roots. If the
     * providers and apps are the same package name, combine them as RootAndAppItems.
     */
    private void includeHandlerApps(
            State state,
            Intent handlerAppIntent,
            @Nullable String excludePackage,
            List<SortableItem> rootList,
            List<SortableItem> rootListOtherUser,
            List<List<SortableItem>> rootListAllUsers,
            List<RootItem> otherProviders,
            List<UserId> userIds,
            boolean maybeShowBadge) {
        if (VERBOSE) Log.v(TAG, "Adding handler apps for intent: " + handlerAppIntent);

        Context context = getContext();
        final Map<UserPackage, ResolveInfo> appsMapping = new HashMap<>();
        final Map<UserPackage, SortableItem> appItems = new HashMap<>();

        final String myPackageName = context.getPackageName();
        for (UserId userId : userIds) {
            final PackageManager pm = userId.getPackageManager(context);
            final List<ResolveInfo> infos = pm.queryIntentActivities(
                    handlerAppIntent, PackageManager.MATCH_DEFAULT_ONLY);

            // In addition to hiding DocumentsUI from possible handler apps, the Android
            // Photopicker should also be hidden. ACTION_PICK_IMAGES is used to identify
            // the Photopicker package since that is the primary API.
            List<ResolveInfo> photopickerActivities;
            List<String> photopickerPackages;

            if (SdkLevel.isAtLeastR()
                    && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
                photopickerActivities = pm.queryIntentActivities(
                        new Intent(MediaStore.ACTION_PICK_IMAGES),
                        PackageManager.MATCH_DEFAULT_ONLY);
                photopickerPackages = photopickerActivities.stream()
                        .map(info -> info.activityInfo.packageName)
                .collect(Collectors.toList());
            } else {
                photopickerActivities = Collections.emptyList();
                photopickerPackages = Collections.emptyList();
            }

            // Omit ourselves and maybe calling package from the list
            for (ResolveInfo info : infos) {
                if (!info.activityInfo.exported) {
                    if (VERBOSE) {
                        Log.v(TAG, "Non exported activity: " + info.activityInfo);
                    }
                    continue;
                }

                final String packageName = info.activityInfo.packageName;

                // If the package name for the activity is in the list of Photopicker
                // activities, exclude it.
                if (photopickerPackages.contains(packageName)) {
                    continue;
                }

                if (!myPackageName.equals(packageName)
                        && !TextUtils.equals(excludePackage, packageName)) {
                    UserPackage userPackage = new UserPackage(userId, packageName);
                    appsMapping.put(userPackage, info);

                    if (!CrossProfileUtils.isCrossProfileIntentForwarderActivity(info)) {
                        final SortableItem item =
                                mUseRailAsContainer
                                        ? new NavRailAppItem(
                                                info,
                                                info.loadLabel(pm).toString(),
                                                userId,
                                                mActionHandler)
                                        : new AppItem(
                                                info,
                                                info.loadLabel(pm).toString(),
                                                userId,
                                                mActionHandler);
                        appItems.put(userPackage, item);
                        if (VERBOSE) Log.v(TAG, "Adding handler app: " + item);
                    }
                }
            }
        }

        // If there are some providers and apps has the same package name, combine them as one item.
        for (RootItem rootItem : otherProviders) {
            final UserPackage userPackage = new UserPackage(rootItem.userId,
                    rootItem.getPackageName());
            final ResolveInfo resolveInfo = appsMapping.get(userPackage);

            final SortableItem item;
            if (resolveInfo != null) {
                item =
                        mUseRailAsContainer
                                ? new NavRailRootAndAppItem(
                                        rootItem.root, resolveInfo, mActionHandler, maybeShowBadge)
                                : new RootAndAppItem(
                                        rootItem.root, resolveInfo, mActionHandler, maybeShowBadge);
                appItems.remove(userPackage);
            } else {
                item = rootItem;
            }

            if (state.configStore.isPrivateSpaceInDocsUIEnabled()) {
                createRootListsPrivateSpaceEnabled(item, userIds, rootListAllUsers);
            } else {
                createRootListsPrivateSpaceDisabled(item, rootList, rootListOtherUser);
            }
        }

        for (SortableItem item : appItems.values()) {
            if (state.configStore.isPrivateSpaceInDocsUIEnabled()) {
                createRootListsPrivateSpaceEnabled(item, userIds, rootListAllUsers);
            } else {
                createRootListsPrivateSpaceDisabled(item, rootList, rootListOtherUser);
            }
        }

        final String preferredRootPackage =
                getResources().getString(getRes(R.string.preferred_root_package), "");
        Comparator<SortableItem> comp;
        if (isHomeScreenFilesFlagEnabled()) {
            comp = new SortableItemComparator();
        } else {
            comp = new ItemComparator(preferredRootPackage);
        }

        if (state.configStore.isPrivateSpaceInDocsUIEnabled()) {
            addToApplicationItemListPrivateSpaceEnabled(userIds, rootListAllUsers, comp, state);
        } else {
            addToApplicationItemListPrivateSpaceDisabled(rootList, rootListOtherUser, comp, state);
        }
    }

    private void addToApplicationItemListPrivateSpaceEnabled(
            List<UserId> userIds,
            List<List<SortableItem>> rootListAllUsers,
            Comparator<SortableItem> comp,
            State state) {
        for (int i = 0; i < userIds.size(); ++i) {
            rootListAllUsers.get(i).sort(comp);
            if (UserId.CURRENT_USER.equals(userIds.get(i))) {
                mApplicationItemList.addAll(rootListAllUsers.get(i));
            } else if (state.supportsCrossProfile && state.canInteractWith(userIds.get(i))) {
                mApplicationItemList.addAll(rootListAllUsers.get(i));
            }
        }
    }

    private void addToApplicationItemListPrivateSpaceDisabled(
            List<SortableItem> rootList,
            List<SortableItem> rootListOtherUser,
            Comparator<SortableItem> comp,
            State state) {
        rootList.sort(comp);
        rootListOtherUser.sort(comp);
        if (state.supportsCrossProfile() && state.canShareAcrossProfile) {
            mApplicationItemList.addAll(rootList);
            mApplicationItemList.addAll(rootListOtherUser);
        } else {
            mApplicationItemList.addAll(rootList);
        }
    }

    private void createRootListsPrivateSpaceEnabled(
            SortableItem item, List<UserId> userIds, List<List<SortableItem>> rootListAllUsers) {
        for (int i = 0; i < userIds.size(); ++i) {
            if (userIds.get(i).equals(item.userId)) {
                rootListAllUsers.get(i).add(item);
                break;
            }
        }
    }

    private void createRootListsPrivateSpaceDisabled(
            SortableItem item, List<SortableItem> rootList, List<SortableItem> rootListOtherUser) {
        if (UserId.CURRENT_USER.equals(item.userId)) {
            rootList.add(item);
        } else {
            rootListOtherUser.add(item);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        final Context context = getActivity();
        // Update the information for Storage's root
        if (context != null) {
            DocumentsApplication.getProvidersCache(context).updateAuthorityAsync(
                    ((BaseActivity) context).getSelectedUser(), Providers.AUTHORITY_STORAGE);
        }
        onDisplayStateChanged();
    }

    public void onDisplayStateChanged() {
        mListHandler.onDisplayStateChange();

        reloadRootsAndShortcuts(/* refreshRootAndDirectory= */ false);
    }

    public void refreshItems() {
        mListHandler.refreshItems();
    }

    public void onCurrentRootChanged() {
        if (!mListHandler.isAdapterInitialized()) {
            return;
        }

        if (isHomeScreenFilesFlagEnabled()) {
            SidebarEntryItemInfo itemInfo = getBaseActivity().getCurrentShortcut();
            if (itemInfo == null) {
                itemInfo = getBaseActivity().getCurrentRoot();
            }
            for (int i = 0; i < mListHandler.getItemCount(); i++) {
                final Object item = mListHandler.getItem(i);
                if (item instanceof BaseSidebarEntryItem) {
                    final SidebarEntryItemInfo testInfo =
                            ((BaseSidebarEntryItem) item).getItemInfo();
                    if (Objects.equals(testInfo.getUri(), itemInfo.getUri())) {
                        // TODO: (b/465888139) - Remove the line below after finding a way to
                        //  update stale shortcut after a language change.
                        itemInfo.setTitle(testInfo.getTitle());
                        mListHandler.selectItem(i);
                        return;
                    }
                }
            }
        } else {
            final RootInfo root = ((BaseActivity) getActivity()).getCurrentRoot();
            for (int i = 0; i < mListHandler.getItemCount(); i++) {
                final Object item = mListHandler.getItem(i);
                if (item instanceof RootItem) {
                    final RootInfo testRoot = ((RootItem) item).root;
                    if (Objects.equals(testRoot, root)) {
                        root.title = testRoot.title;
                        mListHandler.selectItem(i);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Called when the selected user is changed. It reloads roots with the current user.
     */
    public void onSelectedUserChanged() {
        reloadRootsAndShortcuts(/* refreshRootAndDirectory= */ false);
    }

    /**
     * Attempts to shift focus back to the navigation drawer.
     */
    public boolean requestFocus() {
        return mListHandler.requestListFocus();
    }

    private BaseActivity getBaseActivity() {
        return (BaseActivity) getActivity();
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        final Item item = mListHandler.getItemForContextMenu(menuInfo);
        if (item == null) {
            return;
        }

        BaseActivity activity = getBaseActivity();
        item.createContextMenu(menu, activity.getMenuInflater(), mInjector.menuManager);
    }

    @Override
    public boolean onContextItemSelected(MenuItem menuItem) {
        final Item item = mListHandler.getItemForContextMenu(menuItem.getMenuInfo());
        if (item == null) {
            return false;
        }
        final BaseSidebarEntryItem sidebarItem = (BaseSidebarEntryItem) item;
        final int id = menuItem.getItemId();
        if (id == getRes(R.id.root_menu_eject_root)) {
            // This option should be hidden for shortcuts.
            View itemView = mListHandler.getItemViewForContextMenu(menuItem.getMenuInfo());
            if (itemView == null) {
                return false;
            }
            final View ejectIcon = itemView.findViewById(getRes(R.id.action_icon));
            ejectClicked(ejectIcon, sidebarItem.getItemInfo().getRoot(), mActionHandler);
            return true;
        } else if (id == getRes(R.id.root_menu_open_in_new_window)) {
            Runnable openInNewWindowRunnable =
                    () -> {
                        if (sidebarItem instanceof RootItem) {
                            mActionHandler.openInNewWindow(
                                    new DocumentStack(
                                            sidebarItem.getItemInfo().getRoot(),
                                            sidebarItem.getDocInfo()),
                                    null);
                        } else if (isHomeScreenFilesFlagEnabled()
                                && sidebarItem instanceof ShortcutItem) {
                            ShortcutInfo shortcut = (ShortcutInfo) sidebarItem.getItemInfo();
                            mActionHandler.openInNewWindow(
                                    new DocumentStack(shortcut.getRoot(), sidebarItem.getDocInfo()),
                                    shortcut);
                        }
                    };
            if (sidebarItem.getDocInfo() == null) {
                mActionHandler.getDocument(
                        sidebarItem.getItemInfo().getRoot().authority,
                        sidebarItem.getItemInfo().getDocumentId(),
                        sidebarItem.getItemInfo().getRoot().userId,
                        TimeoutTask.DEFAULT_TIMEOUT,
                        (docInfo) -> {
                            if (docInfo != null) {
                                sidebarItem.setDocInfo(docInfo);
                                openInNewWindowRunnable.run();
                            }
                        });
            } else {
                openInNewWindowRunnable.run();
            }
            return true;
        } else if (id == getRes(R.id.root_menu_paste_into_folder)) {
            mActionHandler.pasteIntoFolder(sidebarItem.getItemInfo());
            return true;
        } else if (id == getRes(R.id.root_menu_settings)
                || (id == getRes(R.id.root_menu_manage_device))) {
            mActionHandler.openSettings(sidebarItem.getItemInfo().getRoot());
            return true;
        } else if (id == getRes(R.id.root_menu_inspect)) {
            if (!isHomeScreenFilesFlagEnabled()
                    || !(sidebarItem instanceof ShortcutItem)) {
                return false;
            }
            ShortcutInfo shortcut = (ShortcutInfo) sidebarItem.getItemInfo();
            mActionHandler.getDocument(
                    shortcut.getRoot().authority,
                    shortcut.getDocumentId(),
                    shortcut.getRoot().userId,
                    TimeoutTask.DEFAULT_TIMEOUT,
                    mActionHandler::showPreview
            );
            return true;
        }
        if (DEBUG) {
            Log.d(TAG, "Unhandled menu item selected: " + item);
        }
        return false;
    }

    private void getSidebarItemDocument(BaseSidebarEntryItem sidebarItem, RootUpdater updater) {
        // We need to start a GetDocumentTask so we can know whether items can be directly
        // pasted into root
        mActionHandler.getDocument(
                sidebarItem.getItemInfo().getRoot().authority,
                sidebarItem.getItemInfo().getDocumentId(),
                sidebarItem.getItemInfo().getRoot().userId,
                CONTEXT_MENU_ITEM_TIMEOUT,
                updater::updateDocInfoForRoot);
    }

    private Item getItem(View v) {
        final int pos = (Integer) v.getTag(getRes(R.id.item_position_tag));
        return mListHandler.getItem(pos);
    }

    static void ejectClicked(View ejectIcon, RootInfo root, ActionHandler actionHandler) {
        assert (ejectIcon != null);
        assert (!root.ejecting);
        ejectIcon.setEnabled(false);
        root.ejecting = true;
        actionHandler.ejectRoot(
                root,
                new BooleanConsumer() {
                    @Override
                    public void accept(boolean ejected) {
                        // Event if ejected is false, we should reset, since the op failed.
                        // Either way, we are no longer attempting to eject the device.
                        root.ejecting = false;

                        // If the view is still visible, we update its state.
                        if (ejectIcon.getVisibility() == View.VISIBLE) {
                            ejectIcon.setEnabled(!ejected);
                        }
                    }
                });
    }

    private static class RootComparator implements Comparator<RootItem> {
        @Override
        public int compare(RootItem lhs, RootItem rhs) {
            return lhs.root.compareTo(rhs.root);
        }
    }

    private static class SidebarEntryItemComparator implements Comparator<BaseSidebarEntryItem> {
        @Override
        public int compare(BaseSidebarEntryItem lhs, BaseSidebarEntryItem rhs) {
            return lhs.getItemInfo().compareTo(rhs.getItemInfo());
        }
    }

    /**
     * The comparator of {@link SortableItem} which compares the list of other document providers
     * and apps (e.g. cloud providers and apps like the photopicker). {@link BaseSidebarEntryItem}
     * has priority over {@link AppItem}. If the two items are of the same type, compare by title.
     */
    @VisibleForTesting
    static class SortableItemComparator implements Comparator<SortableItem> {
        @Override
        public int compare(SortableItem lhs, SortableItem rhs) {
            // AppItems have less priority over BaseSidebarEntryItems.
            int score = lhs.getItemType() - rhs.getItemType();
            if (score != 0) {
                return score;
            }

            // Sort by title.
            return compareToIgnoreCaseNullable(lhs.getTitle(), rhs.getTitle());
        }
    }

    /**
     * The comparator of {@link AppItem}, {@link RootItem} and {@link RootAndAppItem}. Sort by if
     * the item's package name starts with the preferred package name, then title, then summary.
     * Because the {@link AppItem} doesn't have summary, it will have lower order than other same
     * title items.
     */
    @VisibleForTesting
    static class ItemComparator implements Comparator<SortableItem> {
        private final String mPreferredPackageName;

        ItemComparator(String preferredPackageName) {
            mPreferredPackageName = preferredPackageName;
        }

        @Override
        public int compare(SortableItem lhs, SortableItem rhs) {
            // Sort by whether the item starts with preferred package name
            if (!mPreferredPackageName.isEmpty()) {
                if (lhs.getPackageName().startsWith(mPreferredPackageName)) {
                    if (!rhs.getPackageName().startsWith(mPreferredPackageName)) {
                        // lhs starts with it, but rhs doesn't start with it
                        return -1;
                    }
                } else {
                    if (rhs.getPackageName().startsWith(mPreferredPackageName)) {
                        // lhs doesn't start with it, but rhs starts with it
                        return 1;
                    }
                }
            }

            // Sort by title
            int score = compareToIgnoreCaseNullable(lhs.getTitle(), rhs.getTitle());
            if (score != 0) {
                return score;
            }

            // Sort by summary. If the item is AppItem, it doesn't have summary.
            // So, the RootItem or RootAndAppItem will have higher order than AppItem.
            if (lhs instanceof RootItem) {
                return rhs instanceof RootItem ? compareToIgnoreCaseNullable(
                        ((RootItem) lhs).root.summary, ((RootItem) rhs).root.summary) : 1;
            }
            return rhs instanceof RootItem ? -1 : 0;
        }
    }

    @FunctionalInterface
    interface RootUpdater {
        void updateDocInfoForRoot(DocumentInfo doc);
    }
}
