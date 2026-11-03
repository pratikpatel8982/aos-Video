// Copyright 2026 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import android.util.AttributeSet;
import android.util.Log;

import com.archos.mediacenter.video.R;

/**
 * "Custom fonts folder" preference. Opens Nova's embedded {@link FolderPicker} (the same one
 * used for the torrent download location) on every device.
 *
 * It deliberately does NOT use the system Storage Access Framework picker
 * (ACTION_OPEN_DOCUMENT_TREE): Android TV / Fire TV devices ship no activity that handles it,
 * so launching it throws ActivityNotFoundException and crashes the app, and it is hard to
 * drive with a remote anyway.
 *
 * The value stored under {@link VideoPreferencesCommon#KEY_SUBTITLE_FONTS_FOLDER_URI} is a
 * plain filesystem path. For backward compatibility the key name is unchanged, and a
 * content:// tree URI saved by an earlier version is still understood (see
 * {@link SubtitleFontsFolderSync#isSafTreeUri(String)}) until the user picks a new folder.
 */
public class FontsFolderPreference extends Preference {

    private static final String TAG = "FontsFolderPreference";
    private static final boolean DBG = false;

    private ActivityResultLauncher<Intent> mFolderPickerLauncher;

    public void setFolderPickerLauncher(ActivityResultLauncher<Intent> launcher) {
        mFolderPickerLauncher = launcher;
    }

    public FontsFolderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DBG) Log.d(TAG, "FontsFolderPreference");
    }

    public FontsFolderPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (DBG) Log.d(TAG, "FontsFolderPreference");
    }

    public void notifyChanged() {
        super.notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder v) {
        super.onBindViewHolder(v);
    }

    @Override
    public void onClick() {
        if (getOnPreferenceClickListener() != null) return;
        if (mFolderPickerLauncher == null) {
            // Preference bound before onCreatePreferences() finished wiring the launcher up:
            // ignore the click rather than crash.
            Log.w(TAG, "onClick: no folder picker launcher registered, ignoring");
            return;
        }
        Intent i = new Intent(getContext(), FolderPicker.class);
        i.putExtra(FolderPicker.EXTRA_DIALOG_TITLE, getContext().getString(R.string.subtitle_fonts_folder_title));
        // Start at the last picked folder when there is one. A legacy content:// tree URI is
        // not a filesystem path FolderPicker can open, so in that case it starts at its default.
        String saved = getSharedPreferences().getString(
                VideoPreferencesCommon.KEY_SUBTITLE_FONTS_FOLDER_URI, null);
        if (saved != null && !SubtitleFontsFolderSync.isSafTreeUri(saved)) {
            i.putExtra(FolderPicker.EXTRA_CURRENT_SELECTION, saved);
        }
        mFolderPickerLauncher.launch(i);
    }

    @Override
    public CharSequence getSummary() {
        String saved = getSharedPreferences().getString(
                VideoPreferencesCommon.KEY_SUBTITLE_FONTS_FOLDER_URI, null);
        if (saved == null) {
            return getContext().getString(R.string.subtitle_fonts_folder_summary);
        }
        if (!SubtitleFontsFolderSync.isSafTreeUri(saved)) {
            return saved; // plain path picked with FolderPicker
        }
        // Legacy SAF tree URI saved by a previous version
        try {
            Uri uri = Uri.parse(saved);
            String docId = uri.getLastPathSegment();
            if (docId == null) return saved;
            int colon = docId.indexOf(':');
            if (colon < 0) return docId; // not the expected "<volume>:<path>" shape -- show as-is
            String relativePath = docId.substring(colon + 1);
            return relativePath.isEmpty() ? docId.substring(0, colon) : relativePath;
        } catch (Exception e) {
            return saved;
        }
    }
}
