/**
 * Nextcloud Android client application
 *
 * @author Andy Scherzinger
 * Copyright (C) 2016 Andy Scherzinger
 * Copyright (C) 2016 Nextcloud
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU AFFERO GENERAL PUBLIC LICENSE for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.MediaFolder;
import com.owncloud.android.datamodel.MediaProvider;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.SyncedFolder;
import com.owncloud.android.datamodel.SyncedFolderItem;
import com.owncloud.android.datamodel.SyncedFolderProvider;
import com.owncloud.android.ui.adapter.FolderSyncAdapter;
import com.owncloud.android.ui.decoration.MediaGridItemDecoration;
import com.owncloud.android.ui.dialog.SyncedFolderPreferencesDialogFragment;
import com.owncloud.android.ui.dialog.parcel.SyncedFolderParcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;

import static com.owncloud.android.datamodel.SyncedFolderItem.UNPERSISTED_ID;

/**
 * Activity displaying all auto-synced folders and/or instant upload media folders.
 */
public class FolderSyncActivity extends FileActivity implements FolderSyncAdapter.ClickListener,
        SyncedFolderPreferencesDialogFragment.OnSyncedFolderPreferenceListener {
    private static final String TAG = FolderSyncActivity.class.getSimpleName();

    private static final String SYNCED_FOLDER_PREFERENCES_DIALOG_TAG = "SYNCED_FOLDER_PREFERENCES_DIALOG";
    public static final String PRIORITIZED_FOLDER = "Camera";

    private RecyclerView mRecyclerView;
    private FolderSyncAdapter mAdapter;
    private LinearLayout mProgress;
    private TextView mEmpty;
    private SyncedFolderProvider mSyncedFolderProvider;
    private List<SyncedFolderItem> syncFolderItems;
    private SyncedFolderPreferencesDialogFragment mSyncedFolderPreferencesDialogFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.folder_sync_layout);

        // setup toolbar
        setupToolbar();

        // setup drawer
        setupDrawer(R.id.nav_folder_sync);
        getSupportActionBar().setTitle(getString(R.string.drawer_folder_sync));

        setupContent();
    }

    /**
     * sets up the UI elements and loads all media/synced folders.
     */
    private void setupContent() {
        mRecyclerView = (RecyclerView) findViewById(android.R.id.list);

        mProgress = (LinearLayout) findViewById(android.R.id.progress);
        mEmpty = (TextView) findViewById(android.R.id.empty);

        // TODO implement "dynamic" grid count via xml-value for tablet vs. phone
        final int gridWidth = 4;
        mAdapter = new FolderSyncAdapter(this, gridWidth, this, mRecyclerView);
        mSyncedFolderProvider = new SyncedFolderProvider(getContentResolver());

        final GridLayoutManager lm = new GridLayoutManager(this, gridWidth);
        mAdapter.setLayoutManager(lm);
        int spacing = getResources().getDimensionPixelSize(R.dimen.mediaGridSpacing);
        mRecyclerView.addItemDecoration(new MediaGridItemDecoration(spacing));
        mRecyclerView.setLayoutManager(lm);
        mRecyclerView.setAdapter(mAdapter);

        load();
    }

    /**
     * loads all media/synced folders, adds them to the recycler view adapter and shows the list.
     */
    private void load() {
        if (mAdapter.getItemCount() > 0) return;
        setListShown(false);
        final Handler mHandler = new Handler();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaFolder> mediaFolders = MediaProvider.getAllShownImagesPath(getContentResolver());
                syncFolderItems = sortSyncedFolderItem(mergeFolderData(mSyncedFolderProvider.getSyncedFolders(),
                        mediaFolders));

                // TODO remove before merging to master, keeping it for debugging atm
                /**
                 for (MediaFolder mediaFolder : mediaFolders) {
                 Log.d(TAG, mediaFolder.absolutePath);
                 }
                 */

                mHandler.post(new TimerTask() {
                    @Override
                    public void run() {
                        mAdapter.setSyncFolderItems(syncFolderItems);
                        setListShown(true);
                    }
                });
            }
        }).start();
    }

    /**
     * merges two lists of SyncedFolder and MediaFolder items into one of SyncedFolderItems.
     *
     * @param syncedFolders the synced folders
     * @param mediaFolders  the media folders
     * @return the merged list of SyncedFolderItems
     */
    @NonNull
    private List<SyncedFolderItem> mergeFolderData(List<SyncedFolder> syncedFolders,
                                                   @NonNull List<MediaFolder> mediaFolders) {
        Map<String, SyncedFolder> syncedFoldersMap = createSyncedFoldersMap(syncedFolders);
        List<SyncedFolderItem> result = new ArrayList<>();

        for (MediaFolder mediaFolder : mediaFolders) {
            if (syncedFoldersMap.containsKey(mediaFolder.absolutePath)) {
                SyncedFolder syncedFolder = syncedFoldersMap.get(mediaFolder.absolutePath);
                result.add(createSyncedFolder(syncedFolder, mediaFolder));
            } else {
                result.add(createSyncedFolderFromMediaFolder(mediaFolder));
            }
        }

        return result;
    }

    /**
     * Sorts list by SyncedFolderItems.
     *
     * @param syncFolderItemList SyncedFolderItems to sort
     */
    public static List<SyncedFolderItem> sortSyncedFolderItem(List<SyncedFolderItem> syncFolderItemList) {
        Collections.sort(syncFolderItemList, new Comparator<SyncedFolderItem>() {
            public int compare(SyncedFolderItem f1, SyncedFolderItem f2) {
                if (f1 == null && f2 == null) {
                    return 0;
                } else if (f1 == null) {
                    return -1;
                } else if (f2 == null) {
                    return 1;
                } else if (f1.isEnabled() && f2.isEnabled()) {
                    return f1.getFolderName().compareTo(f2.getFolderName());
                } else if (f1.isEnabled()) {
                    return -1;
                } else if (f2.isEnabled()) {
                    return 1;
                } else if (f1.getFolderName() == null && f2.getFolderName() == null) {
                    return 0;
                } else if (f1.getFolderName() == null) {
                    return -1;
                } else if (f2.getFolderName() == null) {
                    return 1;
                } else if (PRIORITIZED_FOLDER.equals(f1.getFolderName())) {
                    return -1;
                } else if (PRIORITIZED_FOLDER.equals(f2.getFolderName())) {
                    return 1;
                } else {
                    return f1.getFolderName().compareTo(f2.getFolderName());
                }
            }
        });

        return syncFolderItemList;
    }

    /**
     * creates a SyncedFolderItem merging a SyncedFolder and MediaFolder object instance.
     *
     * @param syncedFolder the synced folder object
     * @param mediaFolder  the media folder object
     * @return the created SyncedFolderItem
     */
    @NonNull
    private SyncedFolderItem createSyncedFolder(@NonNull SyncedFolder syncedFolder, @NonNull MediaFolder mediaFolder) {
        return new SyncedFolderItem(
                syncedFolder.getId(),
                syncedFolder.getLocalPath(),
                syncedFolder.getRemotePath(),
                syncedFolder.getWifiOnly(),
                syncedFolder.getChargingOnly(),
                syncedFolder.getSubfolderByDate(),
                syncedFolder.getAccount(),
                syncedFolder.getUploadAction(),
                syncedFolder.isEnabled(),
                mediaFolder.filePaths,
                mediaFolder.folderName,
                mediaFolder.numberOfFiles);
    }

    /**
     * creates a SyncedFolderItem based on a MediaFolder object instance.
     *
     * @param mediaFolder the media folder object
     * @return the created SyncedFolderItem
     */
    @NonNull
    private SyncedFolderItem createSyncedFolderFromMediaFolder(@NonNull MediaFolder mediaFolder) {
        return new SyncedFolderItem(
                UNPERSISTED_ID,
                mediaFolder.absolutePath,
                getString(R.string.instant_upload_path) + "/" + mediaFolder.folderName,
                true,
                false,
                false,
                AccountUtils.getCurrentOwnCloudAccount(this).name,
                0,
                false,
                mediaFolder.filePaths,
                mediaFolder.folderName,
                mediaFolder.numberOfFiles);
    }

    /**
     * creates a lookup map for a list of given synced folders with their local path as the key.
     *
     * @param syncFolders list of synced folders
     * @return the lookup map for synced folders
     */
    @NonNull
    private Map<String, SyncedFolder> createSyncedFoldersMap(List<SyncedFolder> syncFolders) {
        Map<String, SyncedFolder> result = new HashMap<>();
        if (syncFolders != null) {
            for (SyncedFolder syncFolder : syncFolders) {
                result.put(syncFolder.getLocalPath(), syncFolder);
            }
        }
        return result;
    }

    /**
     * show/hide recycler view list or the empty message / progress info.
     *
     * @param shown flag if list should be shown
     */
    private void setListShown(boolean shown) {
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(shown ? View.VISIBLE : View.GONE);
            mProgress.setVisibility(shown ? View.GONE : View.VISIBLE);
            mEmpty.setVisibility(shown && mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean retval;
        switch (item.getItemId()) {
            case android.R.id.home: {
                if (isDrawerOpen()) {
                    closeDrawer();
                } else {
                    openDrawer();
                }
            }

            default:
                retval = super.onOptionsItemSelected(item);
        }
        return retval;
    }

    @Override
    public void restart() {
        Intent i = new Intent(this, FileDisplayActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    @Override
    public void showFiles(boolean onDeviceOnly) {
        MainApp.showOnlyFilesOnDevice(onDeviceOnly);
        Intent fileDisplayActivity = new Intent(getApplicationContext(), FileDisplayActivity.class);
        fileDisplayActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(fileDisplayActivity);
    }

    @Override
    public void onSyncStatusToggleClick(int section, SyncedFolderItem syncedFolderItem) {
        if (syncedFolderItem.getId() > UNPERSISTED_ID) {
            mSyncedFolderProvider.updateFolderSyncEnabled(syncedFolderItem.getId(), syncedFolderItem.isEnabled());
        } else {
            mSyncedFolderProvider.storeFolderSync(syncedFolderItem);
        }
    }

    @Override
    public void onSyncFolderSettingsClick(int section, SyncedFolderItem syncedFolderItem) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.addToBackStack(null);

        mSyncedFolderPreferencesDialogFragment = SyncedFolderPreferencesDialogFragment.newInstance(syncedFolderItem,
                section);
        mSyncedFolderPreferencesDialogFragment.show(ft, SYNCED_FOLDER_PREFERENCES_DIALOG_TAG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SyncedFolderPreferencesDialogFragment.REQUEST_CODE__SELECT_REMOTE_FOLDER
                && resultCode == RESULT_OK && mSyncedFolderPreferencesDialogFragment != null) {
            OCFile chosenFolder = data.getParcelableExtra(FolderPickerActivity.EXTRA_FOLDER);
            mSyncedFolderPreferencesDialogFragment.setRemoteFolderSummary(chosenFolder.getRemotePath());

        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onSaveSyncedFolderPreference(SyncedFolderParcelable syncedFolder) {
        SyncedFolderItem item = syncFolderItems.get(syncedFolder.getSection());
        item = updateSyncedFolderItem(item, syncedFolder.getLocalPath(), syncedFolder.getRemotePath(), syncedFolder
                .getWifiOnly(), syncedFolder.getChargingOnly(), syncedFolder.getSubfolderByDate(), syncedFolder
                .getUploadAction());

        if (syncedFolder.getId() == UNPERSISTED_ID) {
            // newly set up folder sync config
            mSyncedFolderProvider.storeFolderSync(item);
        } else {
            // existing synced folder setup to be updated
            mSyncedFolderProvider.updateSyncFolder(item);
        }
        mSyncedFolderPreferencesDialogFragment = null;
    }

    @Override
    public void onCancelSyncedFolderPreference() {
        mSyncedFolderPreferencesDialogFragment = null;
    }

    /**
     * update given synced folder with the given values.
     *
     * @param item            the synced folder to be updated
     * @param localPath       the local path
     * @param remotePath      the remote path
     * @param wifiOnly        upload on wifi only
     * @param chargingOnly    upload on charging only
     * @param subfolderByDate created sub folders
     * @param uploadAction    upload action
     * @return the updated item
     */
    private SyncedFolderItem updateSyncedFolderItem(SyncedFolderItem item,
                                                    String localPath,
                                                    String remotePath,
                                                    Boolean wifiOnly,
                                                    Boolean chargingOnly,
                                                    Boolean subfolderByDate,
                                                    Integer uploadAction) {
        item.setLocalPath(localPath);
        item.setRemotePath(remotePath);
        item.setWifiOnly(wifiOnly);
        item.setChargingOnly(chargingOnly);
        item.setSubfolderByDate(subfolderByDate);
        item.setUploadAction(uploadAction);
        return item;
    }
}