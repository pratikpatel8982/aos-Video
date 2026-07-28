// Copyright 2017 Archos SA
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

// Replaces the earlier FolderPicker-based FontsFolderDialogPreference. That approach
// (this app's own torrent-style raw-filesystem folder browser) cannot see font files at
// all under this app's actual storage permission scope: confirmed by direct logcat
// testing that a .ttf placed in a folder never appears in even the RAW, completely
// unfiltered java.io.File.listFiles() result for that directory when the app holds only
// the granular "Photos and videos" media permissions (no MANAGE_EXTERNAL_STORAGE) --
// under scoped storage, a shared folder only exposes files the OS classifies as
// photo/video/audio, or files the app created itself, to plain filesystem listing.
//
// ACTION_OPEN_DOCUMENT_TREE (this class) is Android's actual sanctioned mechanism for
// "let the user grant access to one arbitrary folder": it opens the system Files app,
// the user taps a folder and confirms "USE THIS FOLDER" / "Allow access to <folder>",
// and the app receives a content:// tree URI it can persist indefinitely via
// takePersistableUriPermission() -- completely independent of, and unaffected by, the
// media-only permission scope above. No extra runtime permission prompt beyond that
// one-time SAF grant dialog is needed.
public class FontsFolderSafPreference extends Preference {

    private static final String TAG = "FontsFolderSafPreference";
    private static final boolean DBG = false;

    private ActivityResultLauncher<Intent> mTreePickerLauncher;

    public void setTreePickerLauncher(ActivityResultLauncher<Intent> launcher) {
        mTreePickerLauncher = launcher;
    }

    public FontsFolderSafPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DBG) Log.d(TAG, "FontsFolderSafPreference");
    }

    public FontsFolderSafPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        if (DBG) Log.d(TAG, "FontsFolderSafPreference");
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
        if (mTreePickerLauncher == null) {
            // No registered launcher (e.g. preference bound before onCreatePreferences()
            // finished wiring it up) -- fail silently rather than crash; there's no
            // legacy startActivityForResult fallback here the way FolderPicker's callers
            // had, since ACTION_OPEN_DOCUMENT_TREE's result (the tree URI + persisted
            // permission) MUST be handled via a proper activity-result callback to call
            // takePersistableUriPermission() correctly -- there isn't a meaningful
            // degraded path for this one.
            Log.w(TAG, "onClick: no tree picker launcher registered, ignoring");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // Best-effort hint to start the system picker at the last-picked folder, if any.
        // The Files app is free to ignore this; it's a hint, not a guarantee, unlike
        // FolderPicker's own EXTRA_CURRENT_SELECTION which that app fully honored.
        String savedUriString = getSharedPreferences().getString(
                VideoPreferencesCommon.KEY_SUBTITLE_FONTS_FOLDER_URI, null);
        if (savedUriString != null) {
            try {
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(savedUriString));
            } catch (Exception ignored) {
                // Malformed/stale saved URI -- not worth failing the whole picker launch over.
            }
        }
        mTreePickerLauncher.launch(intent);
    }

    public CharSequence getSummary() {
        String savedUriString = getSharedPreferences().getString(
                VideoPreferencesCommon.KEY_SUBTITLE_FONTS_FOLDER_URI, null);
        if (savedUriString == null) {
            return getContext().getString(R.string.subtitle_fonts_folder_summary);
        }
        // A content:// tree URI's toString() is a legible-enough path hint for a
        // preference summary (e.g. ".../tree/primary:Download/MyFonts") without needing
        // a full DocumentFile round-trip just to render the summary line.
        try {
            Uri uri = Uri.parse(savedUriString);
            String path = uri.getLastPathSegment();
            return path != null ? path : savedUriString;
        } catch (Exception e) {
            return savedUriString;
        }
    }
}
