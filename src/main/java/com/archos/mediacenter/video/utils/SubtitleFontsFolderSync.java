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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Custom subtitle fonts folder (MX Player / mpv-android style third-party fonts dir),
 * implemented via the Storage Access Framework instead of a raw filesystem path.
 *
 * WHY SAF AND NOT A PLAIN FOLDER PATH: this app only requests/holds the granular
 * "Photos and videos" media permissions (READ_MEDIA_IMAGES/READ_MEDIA_VIDEO), not
 * MANAGE_EXTERNAL_STORAGE. Under Android scoped storage, a shared folder like
 * Download/Subtitles is only visible to File.listFiles() for (a) media files the OS
 * classifies as photo/video/audio, or (b) files the app itself created -- confirmed by
 * direct logcat testing: a .mkv placed in a folder shows up in the RAW unfiltered
 * File.listFiles() result, a .ttf placed in the SAME folder does not appear AT ALL, not
 * merely filtered by app logic. SAF's ACTION_OPEN_DOCUMENT_TREE grant is a completely
 * separate access path from that media permission model: once the user taps "USE THIS
 * FOLDER" for a directory, the app gets a persistable content:// URI grant to that
 * specific tree, independent of and unaffected by the media-only permission scope.
 *
 * WHY COPY INTO A CACHE DIR RATHER THAN READ SAF DIRECTLY FROM NATIVE CODE: the native
 * side (sub_format_ssa.c's load_fonts_dir()) does plain opendir()/fopen()/fread() on a
 * filesystem path -- there is no such thing as a filesystem path for a content:// URI,
 * so native code cannot open one directly, and there's no clean way to hand a
 * ParcelFileDescriptor across the JNI boundary into that existing code without a much
 * larger rewrite of the SSA backend. Copying matched font files into
 * getCacheDir()/subtitle_fonts/ instead means: (1) the existing native pipeline needs
 * ZERO changes -- it keeps reading a plain directory path exactly as before, just
 * pointed at this cache dir instead of a user-visible one; (2) the copy only needs to
 * happen when the SAF folder changes, not on every video (see needsResync() below).
 */
public class SubtitleFontsFolderSync {

    private static final Logger log = LoggerFactory.getLogger(SubtitleFontsFolderSync.class);

    private static final String CACHE_SUBDIR = "subtitle_fonts";

    // Extensions libass can actually use (see has_font_ext() in sub_format_ssa.c --
    // keep in sync with that native-side check).
    private static final Set<String> FONT_EXTENSIONS = new HashSet<>();
    static {
        FONT_EXTENSIONS.add("ttf");
        FONT_EXTENSIONS.add("otf");
        FONT_EXTENSIONS.add("ttc");
    }

    private SubtitleFontsFolderSync() {} // static utility, not instantiated

    /** Returns the app-private cache directory native code should be pointed at (via
     * SubtitleEngine.setFontsFolder()) -- this is always a plain filesystem path,
     * regardless of what SAF tree URI it was last synced from. Safe to call even if
     * nothing has ever been synced yet (returns the directory whether or not it has
     * been created/populated). */
    public static File getCacheDir(Context context) {
        return new File(context.getCacheDir(), CACHE_SUBDIR);
    }

    /**
     * Copies every .ttf/.otf/.ttc DocumentFile directly under `treeUri` into the app's
     * private fonts cache directory, replacing whatever was there before. Deliberately
     * NOT incremental/diffed against the previous contents -- SAF trees are typically
     * small (a folder of fonts, not a media library), and a full wipe-and-recopy avoids
     * an entire class of bugs around stale cached fonts outliving a file the user
     * removed from the SAF folder. Returns the number of fonts copied, or -1 on a
     * hard failure (SAF permission revoked, tree no longer exists, etc.) -- distinct
     * from a legitimate 0 (folder access fine, just no font files in it).
     *
     * Runs synchronous file I/O -- callers on the UI thread (e.g. a preference's
     * onFontsFolderPickerResult) should dispatch this to a background thread rather
     * than call it directly from onActivityResult.
     */
    public static int syncFromTree(Context context, Uri treeUri) {
        if (treeUri == null) return -1;

        DocumentFile treeDoc = DocumentFile.fromTreeUri(context, treeUri);
        if (treeDoc == null || !treeDoc.isDirectory() || !treeDoc.canRead()) {
            log.warn("syncFromTree: tree URI '{}' is not a readable directory (permission revoked / folder deleted?)", treeUri);
            return -1;
        }

        File cacheDir = getCacheDir(context);
        // Wipe first (see method doc: full recopy, not incremental) -- tolerate a
        // missing dir on first-ever sync, but log if an existing dir couldn't be
        // cleared, since leftover stale fonts silently mixing with fresh ones would be
        // a confusing, hard-to-diagnose state.
        if (cacheDir.exists()) {
            File[] stale = cacheDir.listFiles();
            if (stale != null) {
                for (File f : stale) {
                    if (!f.delete()) {
                        log.warn("syncFromTree: failed to delete stale cached font '{}'", f.getAbsolutePath());
                    }
                }
            }
        } else if (!cacheDir.mkdirs()) {
            log.warn("syncFromTree: failed to create cache dir '{}'", cacheDir.getAbsolutePath());
            return -1;
        }

        ContentResolver resolver = context.getContentResolver();
        int copied = 0;
        for (DocumentFile child : treeDoc.listFiles()) {
            if (!child.isFile()) continue; // SAF trees can be nested; only direct children matter here, matching the flat-folder contract sub_format_ssa.c's load_fonts_dir() already assumes
            String name = child.getName();
            if (name == null) continue;

            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) continue;
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!FONT_EXTENSIONS.contains(ext)) continue;

            File destFile = new File(cacheDir, name);
            try (InputStream in = resolver.openInputStream(child.getUri());
                 OutputStream out = new FileOutputStream(destFile)) {
                if (in == null) {
                    log.warn("syncFromTree: could not open '{}' for reading", name);
                    continue;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                copied++;
            } catch (IOException e) {
                log.warn("syncFromTree: failed to copy '{}': {}", name, e.getMessage());
            }
        }

        log.debug("syncFromTree: copied {} font file(s) from '{}' into '{}'", copied, treeUri, cacheDir.getAbsolutePath());
        return copied;
    }

    /**
     * Cheap "has the SAF folder's contents possibly changed since we last synced"
     * check, used to decide whether a resync is worth doing (e.g. when the Settings
     * screen is reopened) without unconditionally re-copying every font on every visit.
     * Compares (child count, summed lastModified) against what's already in the cache
     * dir -- not a perfect signature, but SAF doesn't expose a directory-level mtime the
     * way a plain File does, and this is cheap (one listFiles() pass, no file reads) and
     * good enough to catch the common cases (font added/removed/replaced).
     */
    public static boolean needsResync(Context context, Uri treeUri) {
        if (treeUri == null) return false;
        DocumentFile treeDoc = DocumentFile.fromTreeUri(context, treeUri);
        if (treeDoc == null || !treeDoc.isDirectory()) return false;

        int liveCount = 0;
        long liveSignature = 0;
        for (DocumentFile child : treeDoc.listFiles()) {
            if (!child.isFile()) continue;
            String name = child.getName();
            if (name == null) continue;
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) continue;
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!FONT_EXTENSIONS.contains(ext)) continue;
            liveCount++;
            liveSignature += child.lastModified() + child.length();
        }

        File cacheDir = getCacheDir(context);
        File[] cached = cacheDir.listFiles();
        int cachedCount = cached == null ? 0 : cached.length;
        long cachedSignature = 0;
        if (cached != null) {
            for (File f : cached) cachedSignature += f.lastModified() + f.length();
        }

        return liveCount != cachedCount || liveSignature != cachedSignature;
    }
}
