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

package com.archos.mediacenter.video.player;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.MiscUtils;
import com.archos.medialib.Subtitle;
import com.archos.medialib.Subtitle.SubtitleAlignment;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.ViewGroup.LayoutParams;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;

import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SubtitleManager {

    private static final Logger log = LoggerFactory.getLogger(SubtitleManager.class);

    private Context             mContext;
    private ViewGroup           mPlayerView;
    private View                mRootView;
    private WindowManager       mWindow;
    private Resources           mRes;

    private View                mSubtitleLayout = null;
    private SubtitleView        mExoSubtitleView; // The new ExoPlayer engine
    private SubtitleGfxView     mSubtitleGfxView = null; // Legacy Bitmap
    private Subtitle3DTextView  mSubtitleTxtView = null; // Legacy Text
    private TextShadowSpan      mTextShadowSpan = null;  // Legacy Shadow
    private SubtitleSpacerView  mSubtitleSpacer = null;
    private LayoutParams        mSubtitleSpacerParams = null;

    private Drawable            mSubtitlePosHintDrawable;
    private int                 mScreenWidth;
    private int                 mScreenHeight;
    private int                 mSubtitleSize = 50;
    private int                 mSubtitleVPos = 10;
    private int                 mSubtitleVPosPixel;
    private int                 mSubtitleEvadedVPos;

    // Styling State
    private int mColor = Color.WHITE;
    private boolean mOutline = false;
    private boolean mBackground = false;
    private int mBgOpacity = 128;
    // NOTE: not read anywhere in this class after the Media3 migration. Kept (rather than
    // deleted) because setUIMode() is a public method some external caller may still invoke;
    // removing the field would either break that call or require touching call sites outside
    // this file. If nothing reads this after a full search of the call graph, both the field
    // and setUIMode() are safe to delete.
    private int mUiMode;
    private boolean mForceLegacyEngine = false;

    private boolean isFirstTime = true;
    private Subtitle currentSubtitle = null;

    private boolean mNavigationBarShowing, mSystemBarShowing, mActionBarShowing, mIsNavBarOnBottom, mIsGestureAreaShowing;
    private int mGestureAreaHeight, mControlBarHeight;

    Surface mUiSurface;
    private boolean mForbidWindow;
    DispSubtitleThread mDispSubtitleThread = null;
    private static boolean mFullScreenWithCutout = true;

    private static final int MSG_STOP_SUBTITLE = 0;
    private static final int MSG_UPDATE_SUBTITLES = 1;

    private static final int TXT_SIZE_MIN = 16;
    private static final int TXT_SIZE_MAX = 64;
    private static final float TXT_SIZE_RANGE = TXT_SIZE_MAX - TXT_SIZE_MIN;

    // --- Regex Patterns (Kept exactly as you had them) ---
    private static final Pattern SSA_ANY_TAG = Pattern.compile("(?:\\\\)?\\{(?:\\{\\})?\\\\.*?\\}");
    private static final Pattern SSA_COLOR_TAG = Pattern.compile("\\{\\\\?[1-4\\*]?\\\\c&[h,H]([0-9A-Fa-f]+)&\\}(.*?)(?=\\{\\\\c|$)");
    private static final String HTML_COLOR_TAG = "<font color=\"#$1\">$2</font>";
    private static final Pattern SSA_BOLD_TAG = Pattern.compile("\\{\\\\b1\\}(.*?)(?=\\{\\\\b0|$)");
    private static final String HTML_BOLD_TAG = "<b>$1</b>";
    private static final Pattern SSA_ITALIC_TAG = Pattern.compile("\\{\\\\i1\\}(.*?)(?=\\{\\\\i0|$)");
    private static final String HTML_ITALIC_TAG = "<i>$1</i>";
    private static final Pattern SSA_UNDERLINE_TAG = Pattern.compile("\\{\\\\u1\\}(.*?)(?=\\{\\\\u0|$)");
    private static final String HTML_UNDERLINE_TAG = "<u>$1</u>";
    private static final Pattern SSA_STRIKETHROUGH_TAG = Pattern.compile("\\{\\\\s1\\}(.*?)(?=\\{\\\\s0|$)");
    private static final String HTML_STRIKETHROUGH_TAG = "<s>$1</s>";
    private static final Pattern VTT_VOICE_TAG_OPEN = Pattern.compile("<v\\s+([^>]+)>");
    private static final String HTML_VTT_VOICE_TAG_OPEN = "<b>$1:</b> ";
    private static final Pattern VTT_VOICE_TAG_CLOSE = Pattern.compile("</v>");
    private static final Pattern SUBRIP_ALIGNMENT_TAG = Pattern.compile("\\\\?\\{(?:\\{\\})?\\\\?\\\\(?:\\u2060)?an([1-9])\\\\?\\}");

    public SubtitleManager(Context context, ViewGroup playerView, WindowManager window, boolean forbidWindow) {
        mContext = context;
        mPlayerView = playerView;
        mWindow = window;
        mRes = context.getResources();
        mForbidWindow = forbidWindow;
        mSubtitlePosHintDrawable = ContextCompat.getDrawable(context, com.archos.mediacenter.video.R.drawable.subtitle_baseline);
    }

    /**
    * Determines whether subtitle rendering should bypass the Media3 engine
    * and fall back to Nova's legacy dual-view rendering pipeline.
    * * Currently triggered by the presence of an external GL surface (used for 3D SBS/TB).
    * This method serves as the centralized routing check and provides an easy
    * integration point for future user preferences forcing the legacy engine.
    *
    * @return true if cues should be routed to the legacy pipeline, false for Media3.
    */
    private boolean shouldDivertToLegacyPipeline() {
        // Condition updates automatically if the GL surface is active OR if forced via state
        return (mUiSurface != null) || mForceLegacyEngine;
    }

    /**
    * Converts Nova's internal Subtitle object into a Media3/ExoPlayer Cue,
    * using Row Stacking (LINE_TYPE_NUMBER) to prevent overlap.
    */
    private Cue mapToExoCue(com.archos.medialib.Subtitle subtitle, int stackOffset) {
        Cue.Builder builder = new Cue.Builder();

        if (subtitle.isText()) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(
                HtmlCompat.fromHtml(cleanText(subtitle.getText()), HtmlCompat.FROM_HTML_MODE_LEGACY)
            );
            builder.setText(ssb);

            SubtitleAlignment alignment = getAlignment(subtitle.getText());

            float line;
            @Cue.LineType int lineType = Cue.LINE_TYPE_NUMBER; // Row Stacking Mode!
            @Cue.AnchorType int lineAnchor;
            float position = 0.5f;
            @Cue.AnchorType int positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
            Layout.Alignment textAlignment = Layout.Alignment.ALIGN_CENTER;

            // Map alignment to ExoPlayer rows
            switch (alignment) {
                case TOP_LEFT:
                    line = 0f + stackOffset; lineAnchor = Cue.ANCHOR_TYPE_START;
                    position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                    textAlignment = Layout.Alignment.ALIGN_NORMAL;
                    break;
                case TOP_MID:
                    line = 0f + stackOffset; lineAnchor = Cue.ANCHOR_TYPE_START;
                    position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    textAlignment = Layout.Alignment.ALIGN_CENTER;
                    break;
                case TOP_RIGHT:
                    line = 0f + stackOffset; lineAnchor = Cue.ANCHOR_TYPE_START;
                    position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                    textAlignment = Layout.Alignment.ALIGN_OPPOSITE;
                    break;
                case MID_LEFT:
                    line = 0.5f; lineType = Cue.LINE_TYPE_FRACTION; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                    textAlignment = Layout.Alignment.ALIGN_NORMAL;
                    break;
                case MID_MID:
                    line = 0.5f; lineType = Cue.LINE_TYPE_FRACTION; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    textAlignment = Layout.Alignment.ALIGN_CENTER;
                    break;
                case MID_RIGHT:
                    line = 0.5f; lineType = Cue.LINE_TYPE_FRACTION; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                    textAlignment = Layout.Alignment.ALIGN_OPPOSITE;
                    break;
                case BOTTOM_LEFT:
                    line = -1f - stackOffset; lineAnchor = Cue.ANCHOR_TYPE_END;
                    position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                    textAlignment = Layout.Alignment.ALIGN_NORMAL;
                    break;
                case BOTTOM_RIGHT:
                    line = -1f - stackOffset; lineAnchor = Cue.ANCHOR_TYPE_END;
                    position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                    textAlignment = Layout.Alignment.ALIGN_OPPOSITE;
                    break;
                case BOTTOM_MID:
                default:
                    line = -1f - stackOffset; lineAnchor = Cue.ANCHOR_TYPE_END;
                    position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    textAlignment = Layout.Alignment.ALIGN_CENTER;
                    break;
            }

            builder.setLine(line, lineType);
            builder.setLineAnchor(lineAnchor);
            builder.setPosition(position);
            builder.setPositionAnchor(positionAnchor);
            builder.setTextAlignment(textAlignment);

        } else if (subtitle.isBitmap()) {
            builder.setBitmap(subtitle.getBitmap());
            if (subtitle.getFrameWidth() > 0 && subtitle.getFrameHeight() > 0) {
                Rect bounds = subtitle.getBounds();
                builder.setPosition((float) bounds.left / subtitle.getFrameWidth());
                builder.setPositionAnchor(Cue.ANCHOR_TYPE_START);
                builder.setLine((float) bounds.top / subtitle.getFrameHeight(), Cue.LINE_TYPE_FRACTION);
                builder.setLineAnchor(Cue.ANCHOR_TYPE_START);
                builder.setSize((float) bounds.width() / subtitle.getFrameWidth());
                builder.setBitmapHeight((float) bounds.height() / subtitle.getFrameHeight());
            }
        }
        return builder.build();
    }
    public static class SubtitleWithOffset {
        public final Subtitle subtitle;
        public final int stackOffset;
        public SubtitleWithOffset(Subtitle subtitle, int stackOffset) {
            this.subtitle = subtitle;
            this.stackOffset = stackOffset;
        }
    }

    // -------------------------------------------------------------
    // DELEGATION: Mapping old styling methods to ExoPlayer
    // -------------------------------------------------------------
    private void updateExoPlayerStyle() {
        if (mExoSubtitleView == null) return;

        int edgeType = mOutline ? CaptionStyleCompat.EDGE_TYPE_OUTLINE : CaptionStyleCompat.EDGE_TYPE_NONE;
        int bgColor = mBackground ? Color.argb(mBgOpacity, 0, 0, 0) : Color.TRANSPARENT;

        CaptionStyleCompat style = new CaptionStyleCompat(
            mColor, bgColor, Color.TRANSPARENT, edgeType, Color.BLACK, null
        );

        mExoSubtitleView.setStyle(style);
        mExoSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, calcTextSize(mSubtitleSize));
    }

    // --- Handler perfectly wired to ExoPlayer ---
    private static class SubtitleHandler extends Handler {
        private final WeakReference<SubtitleManager> mSubtitleManager;
        SubtitleHandler(SubtitleManager subtitleManager) {
            super(Looper.getMainLooper());
            mSubtitleManager = new WeakReference<>(subtitleManager);
        }
        @Override
        public void handleMessage(Message msg) {
            SubtitleManager subtitleManager = mSubtitleManager.get();
            if (subtitleManager != null) {
                subtitleManager.handleMessage(msg);
            }
        }
    }

    private final Handler mHandler = new SubtitleHandler(this);

    @SuppressWarnings("unchecked")
    private void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_STOP_SUBTITLE:
                if (mExoSubtitleView != null) mExoSubtitleView.setCues(Collections.emptyList());
                if (mSubtitleTxtView != null) { mSubtitleTxtView.setText(""); mSubtitleTxtView.setVisibility(View.GONE); }
                if (mSubtitleGfxView != null) { mSubtitleGfxView.remove(); }
                break;

            case MSG_UPDATE_SUBTITLES:
                ArrayList<SubtitleWithOffset> activeSubs = (ArrayList<SubtitleWithOffset>) msg.obj;
                boolean useLegacyGLPath = shouldDivertToLegacyPipeline();

                if (!useLegacyGLPath) {
                    // ====== MEDIA3 2D PATH ======
                    if (mSubtitleTxtView != null) mSubtitleTxtView.setVisibility(View.GONE);
                    if (mSubtitleGfxView != null) mSubtitleGfxView.setVisibility(View.GONE);

                    if (activeSubs == null || activeSubs.isEmpty()) {
                        mExoSubtitleView.setCues(Collections.emptyList());
                    } else {
                        ArrayList<Cue> exoCues = new ArrayList<>();

                        for (SubtitleWithOffset subWithOffset : activeSubs) {
                            Subtitle sub = subWithOffset.subtitle;
                            exoCues.add(mapToExoCue(sub, sub.isText() ? subWithOffset.stackOffset : 0));
                        }
                        mExoSubtitleView.setCues(exoCues);
                    }
                } else {
                    // ====== LEGACY 3D/GL PATH ======
                    mExoSubtitleView.setCues(Collections.emptyList());

                    if (activeSubs == null || activeSubs.isEmpty()) {
                        if (mSubtitleTxtView != null) { mSubtitleTxtView.setText(""); mSubtitleTxtView.setVisibility(View.GONE); mSubtitleTxtView.postInvalidate(); }
                        if (mSubtitleGfxView != null) { mSubtitleGfxView.remove(); }
                        return;
                    }

                    SpannableStringBuilder combinedText = new SpannableStringBuilder();
                    Subtitle bitmapSub = null;
                    SubtitleAlignment lastAlignment = SubtitleAlignment.BOTTOM_MID;

                    for (SubtitleWithOffset subWithOffset : activeSubs) {
                        Subtitle sub = subWithOffset.subtitle;
                        if (sub.isText()) {
                            if (combinedText.length() > 0) combinedText.append("\n"); // Concatenate overlaps for legacy
                            combinedText.append(HtmlCompat.fromHtml(cleanText(sub.getText()), HtmlCompat.FROM_HTML_MODE_LEGACY));
                            lastAlignment = getAlignment(sub.getText());
                        } else if (sub.isBitmap() && bitmapSub == null) {
                            bitmapSub = sub; // Grab the first bitmap
                        }
                    }

                    // Render Legacy Text
                    if (combinedText.length() > 0 && mSubtitleTxtView != null) {
                        mSubtitleTxtView.setVisibility(View.VISIBLE);
                        adjustSubtitlePosition(lastAlignment);

                        if (mTextShadowSpan == null) {
                            float sRadius = mRes.getDimension(R.dimen.subtitles_shadow_radius);
                            float sDx = mRes.getDimension(R.dimen.subtitles_shadow_dx);
                            float sDy = mRes.getDimension(R.dimen.subtitles_shadow_dy);
                            int sColor = ContextCompat.getColor(mContext, R.color.subtitles_shadow_color);
                            mTextShadowSpan = new TextShadowSpan(sRadius, sDx, sDy, sColor);
                        }
                        combinedText.setSpan(mTextShadowSpan, 0, combinedText.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                        mSubtitleTxtView.setText(combinedText);
                        mSubtitleTxtView.postInvalidate();
                    } else if (mSubtitleTxtView != null) {
                        mSubtitleTxtView.setText("");
                        mSubtitleTxtView.setVisibility(View.GONE);
                        mSubtitleTxtView.postInvalidate();
                    }

                    // Render Legacy Bitmaps
                    if (bitmapSub != null && mSubtitleGfxView != null) {
                        mSubtitleGfxView.setSubtitle(bitmapSub.getBitmap(), bitmapSub.getBounds(), bitmapSub.getFrameWidth(), bitmapSub.getFrameHeight());
                    } else if (mSubtitleGfxView != null) {
                        mSubtitleGfxView.remove();
                    }
                }
                break;
        }
    }

    // ... (Keep cleanText, replaceAll, getAlignment exactly as they were in your code) ...
    private static SubtitleAlignment getAlignment(final String input) {
        SubtitleAlignment alignment = SubtitleAlignment.BOTTOM_MID;
        Matcher subripAlignmentMatch = SUBRIP_ALIGNMENT_TAG.matcher(input);
        if (subripAlignmentMatch.find()) {
            int alignmentInt = Integer.parseInt(subripAlignmentMatch.group(1));
            alignment = switch (alignmentInt) {
                case 1 -> SubtitleAlignment.BOTTOM_LEFT;
                case 2 -> SubtitleAlignment.BOTTOM_MID;
                case 3 -> SubtitleAlignment.BOTTOM_RIGHT;
                case 4 -> SubtitleAlignment.MID_LEFT;
                case 5 -> SubtitleAlignment.MID_MID;
                case 6 -> SubtitleAlignment.MID_RIGHT;
                case 7 -> SubtitleAlignment.TOP_LEFT;
                case 8 -> SubtitleAlignment.TOP_MID;
                case 9 -> SubtitleAlignment.TOP_RIGHT;
                default -> alignment;
            };
        }
        return alignment;
    }

    private static String cleanText(final String input) {
        String displayText = input.trim();
        displayText = displayText.replaceAll("(?i)\\n|\\\\n", "<br />");
        displayText = displayText.replaceAll("([^\\n\\r][.!?])([A-Z])", "$1<br />$2");
        displayText = displayText.replaceAll("<br\\s*/>", "§NEWLINE§");
        displayText = displayText.replaceAll("\\s+", " ");
        displayText = displayText.replaceAll("§NEWLINE§", "<br />");

        StringBuffer sb = new StringBuffer(displayText.length());
        displayText = replaceAll(displayText, VTT_VOICE_TAG_OPEN, HTML_VTT_VOICE_TAG_OPEN, sb);
        displayText = replaceAll(displayText, VTT_VOICE_TAG_CLOSE, "", sb);
        Matcher ssaTagMatch = SSA_ANY_TAG.matcher(displayText);
        if (ssaTagMatch.find()) {
            sb.setLength(0);
            displayText = replaceAll(displayText, SSA_COLOR_TAG, HTML_COLOR_TAG, sb);
            displayText = replaceAll(displayText, SSA_ITALIC_TAG, HTML_ITALIC_TAG, sb);
            displayText = replaceAll(displayText, SSA_BOLD_TAG, HTML_BOLD_TAG, sb);
            displayText = replaceAll(displayText, SSA_UNDERLINE_TAG, HTML_UNDERLINE_TAG, sb);
            displayText = replaceAll(displayText, SSA_STRIKETHROUGH_TAG, HTML_STRIKETHROUGH_TAG, sb);
            displayText = replaceAll(displayText, SSA_ANY_TAG, "", sb);
        }
        return displayText;
    }

    private static String replaceAll(String input, Pattern pattern, String replacement, StringBuffer buffer) {
        buffer.setLength(0);
        Matcher match = pattern.matcher(input);
        while (match.find()) {
            match.appendReplacement(buffer, replacement);
        }
        match.appendTail(buffer);
        return buffer.toString();
    }

    // --- Public Facade API ---
    public int getColor() { return mColor; }
    public boolean getOutlineState() { return mOutline; }
    public boolean getBackgroundState() { return mBackground; }
    public int getBackgroundOpacity() { return mBgOpacity; }
    public int getSize() { return mSubtitleSize; }

    public static float calcTextSize(int size) {
        int tmp = size > 100 ? 100 : Math.max(size, 0);
        return (tmp / 100f) * TXT_SIZE_RANGE + TXT_SIZE_MIN;
    }

    public void setUIMode(int uiMode) {
        mUiMode = uiMode;
        if (mSubtitleTxtView != null) mSubtitleTxtView.setUIMode(uiMode);
    }

    public void setColor(int color) {
        mColor = color;
        updateExoPlayerStyle();
        if (mSubtitleTxtView != null) mSubtitleTxtView.setTextColor(color);
    }

    public void setOutlineState(boolean outline) {
        mOutline = outline;
        updateExoPlayerStyle();
        if (mSubtitleTxtView != null) mSubtitleTxtView.setOutlineState(outline);
    }

    public void setBackgroundState(boolean background) {
        mBackground = background;
        updateExoPlayerStyle();
        if (mSubtitleTxtView != null) mSubtitleTxtView.setBackgroundState(background);
    }

    public void setBackgroundOpacity(int opacity) {
        mBgOpacity = opacity;
        updateExoPlayerStyle();
        if (mSubtitleTxtView != null) mSubtitleTxtView.setBackgroundOpacity(opacity);
    }

    public void setSize(int size) {
        mSubtitleSize = size;
        updateExoPlayerStyle();
        if (mSubtitleGfxView != null) mSubtitleGfxView.setSize(size, mScreenWidth, mScreenHeight);
        if (mSubtitleTxtView != null) mSubtitleTxtView.setTextSize(calcTextSize(size));
    }
    /**
    * Force the subtitle manager to completely bypass the Media3 engine
    * and fall back entirely to the legacy TextView/GfxView pipeline.
    */
    public void setForceLegacyEngine(boolean forceLegacy) {
        if (this.mForceLegacyEngine != forceLegacy) {
            this.mForceLegacyEngine = forceLegacy;
            // Trigger a redraw if a video is actively running so the engine swaps instantly
            show();
        }
    }

    final class DispSubtitleThread extends Thread {
        private boolean mSuspended = true;
        private boolean mRunning = true;

        class ActiveCue {
            Subtitle subtitle;
            long expiresAt;
            int stackOffset; // <-- NEW: Persistently stores the slot

            ActiveCue(Subtitle s, int offset) {
                subtitle = s;
                expiresAt = s.isTimed() ? android.os.SystemClock.elapsedRealtime() + s.getDuration() : Long.MAX_VALUE;
                stackOffset = offset;
            }
        }

        private final ArrayList<ActiveCue> mActiveCues = new ArrayList<>();

        void quit() {
            mRunning = false;
            mDispSubtitleThread = null;
            interrupt();
            try { join(); } catch (InterruptedException e) {}
        }

        private void updateUI() {
            ArrayList<SubtitleWithOffset> subsToDisplay = new ArrayList<>();
            synchronized (this) {
                for (ActiveCue ac : mActiveCues) {
                    subsToDisplay.add(new SubtitleWithOffset(ac.subtitle, ac.stackOffset));
                }
            }
            mHandler.removeMessages(MSG_UPDATE_SUBTITLES);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_SUBTITLES, subsToDisplay));
        }

        synchronized void addSubtitle(Subtitle subtitle) {
            mSuspended = false;

            boolean isEmptyClear = (subtitle.getText() == null || subtitle.getText().trim().isEmpty()) && !subtitle.isBitmap();

            if (isEmptyClear) {
                mActiveCues.clear();
            } else {
                if (!subtitle.isTimed()) {
                    for (int i = mActiveCues.size() - 1; i >= 0; i--) {
                        if (mActiveCues.get(i).expiresAt == Long.MAX_VALUE) {
                            mActiveCues.remove(i);
                        }
                    }
                    if (subtitle.getText() != null || subtitle.isBitmap()) {
                        mActiveCues.add(new ActiveCue(subtitle, 0)); // Non-timed get base slot
                    }
                } else {
                    // Find the lowest unoccupied slot for this exact alignment
                    int assignedOffset = 0;
                    if (subtitle.isText()) {
                        SubtitleAlignment alignment = getAlignment(subtitle.getText());
                        boolean[] takenSlots = new boolean[16]; // Safely tracks up to 16 overlapping cues

                        for (ActiveCue ac : mActiveCues) {
                            if (ac.subtitle.isText() && getAlignment(ac.subtitle.getText()) == alignment) {
                                if (ac.stackOffset >= 0 && ac.stackOffset < takenSlots.length) {
                                    takenSlots[ac.stackOffset] = true; // Mark slot as taken
                                }
                            }
                        }

                        // Grab the first available empty slot
                        for (int i = 0; i < takenSlots.length; i++) {
                            if (!takenSlots[i]) {
                                assignedOffset = i;
                                break;
                            }
                        }
                    }
                    // Lock it in!
                    mActiveCues.add(new ActiveCue(subtitle, assignedOffset));
                }
            }

            updateUI();

            if (!isAlive()) super.start();
            else interrupt();
        }

        synchronized void clear() {
            mSuspended = true;
            mActiveCues.clear();
            updateUI();
            interrupt();
        }

        synchronized void setSuspended(boolean suspended) {
            if (mSuspended == suspended) return;
            mSuspended = suspended;
            long now = android.os.SystemClock.elapsedRealtime();

            if (suspended) {
                // We are pausing. Convert absolute expiration time into remaining duration!
                for (ActiveCue ac : mActiveCues) {
                    if (ac.expiresAt != Long.MAX_VALUE) {
                        ac.expiresAt = Math.max(0, ac.expiresAt - now);
                    }
                }
            } else {
                // We are resuming. Convert remaining duration back into a future absolute expiration time!
                for (ActiveCue ac : mActiveCues) {
                    if (ac.expiresAt != Long.MAX_VALUE) {
                        ac.expiresAt = now + ac.expiresAt;
                    }
                }
            }
            interrupt();
        }

        synchronized void show() { updateUI(); }

        @Override
        public void run() {
            while (mRunning) {
                long sleepTime;

                synchronized (this) {
                    while (true) {
                        if (mSuspended) {
                            try { wait(); } catch (InterruptedException e) { if (!mRunning) return; }
                            continue;
                        }

                        long now = android.os.SystemClock.elapsedRealtime();
                        boolean expiredAny = false;

                        // 1. Expire cues whose absolute time has passed
                        for (int i = mActiveCues.size() - 1; i >= 0; i--) {
                            ActiveCue ac = mActiveCues.get(i);
                            if (ac.expiresAt != Long.MAX_VALUE && ac.expiresAt <= now) {
                                mActiveCues.remove(i);
                                expiredAny = true;
                            }
                        }

                        if (expiredAny) updateUI();

                        if (mActiveCues.isEmpty()) {
                            try { wait(); } catch (InterruptedException e) { if (!mRunning) return; }
                            continue;
                        }

                        // 2. Find the closest upcoming absolute expiration
                        long minExpiresAt = Long.MAX_VALUE;
                        for (ActiveCue ac : mActiveCues) {
                            if (ac.expiresAt < minExpiresAt) {
                                minExpiresAt = ac.expiresAt;
                            }
                        }

                        if (minExpiresAt == Long.MAX_VALUE) {
                            try { wait(); } catch (InterruptedException e) { if (!mRunning) return; }
                            continue;
                        }

                        // 3. Calculate exactly how long to sleep from right now
                        sleepTime = minExpiresAt - now;
                        if (sleepTime <= 0) continue; // Safety catch: if it expired while we were doing math, loop again
                        break;
                    }
                }

                // 4. Sleep until the next absolute timestamp
                try {
                    sleep(sleepTime);
                } catch (InterruptedException e) {
                    // Woken up by a new subtitle arriving or pause state changing.
                    // The loop will seamlessly recalculate `now` and sleep for the correct remaining time!
                }
            }
        }
    }

    public void setScreenSize(int displayWidth, int displayHeight) {
        mScreenWidth = displayWidth;
        mScreenHeight = displayHeight;
        if (mSubtitleLayout != null) {
            ViewGroup.LayoutParams lp = mSubtitleLayout.getLayoutParams();
            lp.width = mScreenWidth;
            lp.height = mScreenHeight;
            mPlayerView.updateViewLayout(mSubtitleLayout, lp);
        }
        if (mSubtitleTxtView != null) mSubtitleTxtView.setScreenSize(displayWidth, displayHeight);
        show(); // Let the thread redraw the active cues
        setSize(mSubtitleSize);
        updateSubtitleLayout();
    }

    public void updateSubtitleLayout() {
        if (! isFirstTime) adjustView();
        show(); // Let the thread redraw the active cues
    }

    public void setUIExternalSurface(Surface uiSurface) {
        mUiSurface = uiSurface;
        if (mSubtitleSpacer != null) mSubtitleSpacer.setRenderingSurface(uiSurface);
        if (mSubtitleGfxView != null) mSubtitleGfxView.setRenderingSurface(uiSurface);
        if (mSubtitleTxtView != null) mSubtitleTxtView.setRenderingSurface(uiSurface);
    }

    @SuppressWarnings("deprecation")
    private void attachWindow() {
        SharedPreferences mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        if (mPreferences != null) mFullScreenWithCutout = mPreferences.getBoolean("enable_cutout_mode_short_edges", true);
        if (mSubtitleLayout != null) return;
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mSubtitleLayout = inflater.inflate(R.layout.subtitle_layout, mPlayerView, false);
        if (mSubtitleLayout == null) return;

        mSubtitleSpacer = (SubtitleSpacerView) mSubtitleLayout.findViewById(R.id.subtitle_spacer);

        // WIRE UP EXOPLAYER VIEW HERE
        mExoSubtitleView = mSubtitleLayout.findViewById(R.id.exo_subtitle_view);
        updateExoPlayerStyle(); // Apply initial styles

        // LEGACY CODE
        mSubtitleGfxView = mSubtitleLayout.findViewById(R.id.subtitle_gfx_view);
            mSubtitleTxtView = mSubtitleLayout.findViewById(R.id.subtitle_txt_view);

            if (mSubtitleTxtView != null) {
                mSubtitleTxtView.setScreenSize(mScreenWidth, mScreenHeight);
                mSubtitleTxtView.setUIMode(mUiMode);
                mSubtitleTxtView.setBackgroundState(mBackground);
                mSubtitleTxtView.setBackgroundOpacity(mBgOpacity);
                mSubtitleTxtView.setTextColor(mColor);
                mSubtitleTxtView.setOutlineState(mOutline);
                mSubtitleTxtView.setTextSize(calcTextSize(mSubtitleSize));
            }
        // ----------

        if (mSubtitleSpacer == null || mExoSubtitleView == null) return;

        mSubtitleSpacerParams = mSubtitleSpacer.getLayoutParams();
        mSubtitleSpacerParams.height = mSubtitleEvadedVPos;
        setUIExternalSurface(mUiSurface);

        if (mSubtitleLayout != null) {
            mRootView = mSubtitleLayout.getRootView();
            mSubtitleLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                if (! isFirstTime) adjustView();
                return insets;
            });
            mRootView.setOnSystemUiVisibilityChangeListener(visibility -> {
                mNavigationBarShowing = (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
                mSystemBarShowing = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
                mActionBarShowing = PlayerController.isActionBarShowing();
                mIsNavBarOnBottom = MiscUtils.isNavigationBarOnBottom(mRootView, mContext);
                mIsGestureAreaShowing = MiscUtils.isGestureAreaDisplayed(mContext);
                mGestureAreaHeight = MiscUtils.getGestureAreaHeight(mContext);
                if (! isFirstTime) adjustView();
            });
        }
        mPlayerView.addView(mSubtitleLayout, mScreenWidth, mScreenHeight);
    }

    private void adjustView() {
        boolean avoidCutout = ! mFullScreenWithCutout;
        boolean isFloatingPlayer = Player.sPlayer != null && Player.sPlayer.isFloatingPlayer();
        mActionBarShowing = PlayerController.isActionBarShowing();
        MiscUtils.adjustViewLayoutForInsets(mContext, mRootView, mSubtitleLayout, "mSubtitleLayout",
                mNavigationBarShowing, mSystemBarShowing, mActionBarShowing, PlayerController.isControlBarShowing(), mIsNavBarOnBottom, mIsGestureAreaShowing,
                (PlayerController.isControlBarShowing() ? PlayerController.getControlBarCurrentHeight() : 0), mSubtitleEvadedVPos,
                false, true, false, true,
                avoidCutout, avoidCutout, avoidCutout, avoidCutout, true, ! isFloatingPlayer);
    }

    private void detachWindow() {
        if (mSubtitleLayout == null) return;
        mPlayerView.removeView(mSubtitleLayout);
        mSubtitleLayout = null;
    }

    public void start() {
        attachWindow();
        if (mDispSubtitleThread == null) {
            mDispSubtitleThread = new DispSubtitleThread();
            try { mDispSubtitleThread.start(); } catch (IllegalThreadStateException e) {}
        }
        show();
    }

    public void stop() {
        if (mDispSubtitleThread != null) mDispSubtitleThread.quit();
        detachWindow();
    }

    public void show() { if (mDispSubtitleThread != null) mDispSubtitleThread.show(); }
    public void clear() { if (mDispSubtitleThread != null) mDispSubtitleThread.clear(); }

    public void fadeSubtitlePositionHint (boolean fadeIn) {
        if (mSubtitleSpacer == null) return;
        if (fadeIn) mSubtitleSpacer.animate().alpha(1).setDuration(100);
        else mSubtitleSpacer.animate().alpha(0).setDuration(500);
    }

    public void setShowSubtitlePositionHint (boolean show) {
        if (mSubtitleSpacer == null) return;
        mSubtitleSpacer.setAlpha(0);
        if (show) mSubtitleSpacer.setBackground(mSubtitlePosHintDrawable);
        else mSubtitleSpacer.setBackground(null);
    }

    public int getVerticalPosition() { return mSubtitleVPos; }

    public void setVerticalPosition(int pos) {
        mSubtitleVPos = pos;
        mSubtitleVPosPixel = (mScreenHeight * pos / 765) + 1;
        setVerticalPositionInternal(mSubtitleVPosPixel);
    }

    private void setVerticalPositionInternal (int pos) {
        mSubtitleEvadedVPos = pos;
        if (mSubtitleSpacer == null) return;
        mSubtitleSpacerParams.height = mSubtitleEvadedVPos;
        mSubtitleSpacer.setLayoutParams(mSubtitleSpacerParams);
        mSubtitleSpacer.requestLayout();
        mSubtitleSpacer.postInvalidate();
    }

    private void adjustSubtitlePosition(SubtitleAlignment alignment) {
        if (mSubtitleTxtView == null) return;
        int gravity = switch (alignment) {
            case BOTTOM_LEFT -> Gravity.BOTTOM | Gravity.START;
            case BOTTOM_MID -> Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            case BOTTOM_RIGHT -> Gravity.BOTTOM | Gravity.END;
            case MID_LEFT -> Gravity.CENTER_VERTICAL | Gravity.START;
            case MID_MID -> Gravity.CENTER;
            case MID_RIGHT -> Gravity.CENTER_VERTICAL | Gravity.END;
            case TOP_LEFT -> Gravity.TOP | Gravity.START;
            case TOP_MID -> Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case TOP_RIGHT -> Gravity.TOP | Gravity.END;
        };
        int textJustification = getTextJustification(alignment);
        mSubtitleTxtView.setGravity3D(gravity, textJustification);
    }

    private int getTextJustification(SubtitleAlignment alignment) {
        return switch (alignment) {
            case BOTTOM_LEFT, MID_LEFT, TOP_LEFT -> Gravity.START;
            case BOTTOM_RIGHT, MID_RIGHT, TOP_RIGHT -> Gravity.END;
            case BOTTOM_MID, MID_MID, TOP_MID -> Gravity.CENTER_HORIZONTAL;
        };
    }

    public void addSubtitle(Subtitle subtitle) {
        if (mDispSubtitleThread != null) mDispSubtitleThread.addSubtitle(subtitle);
    }

    public void onPlay() {
        if (mDispSubtitleThread != null) mDispSubtitleThread.setSuspended(false);
    }

    public void onPause() {
        if (mDispSubtitleThread != null) mDispSubtitleThread.setSuspended(true);
    }

    public void onSeekStart(int pos) {
        if (mDispSubtitleThread != null) {
            // interrupt() is now performed atomically inside clear() itself — see
            // DispSubtitleThread.clear() — so it no longer needs to be called separately
            // here, which removed a race window against a concurrent addSubtitle().
            mDispSubtitleThread.clear();
        }
    }
}
