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
    private int mUiMode;

    private boolean isFirstTime = true;
    private Subtitle currentSubtitle = null;

    private boolean mNavigationBarShowing, mSystemBarShowing, mActionBarShowing, mIsNavBarOnBottom, mIsGestureAreaShowing;
    private int mGestureAreaHeight, mControlBarHeight;

    Surface mUiSurface;
    private boolean mForbidWindow;
    DispSubtitleThread mDispSubtitleThread = null;
    private static boolean mFullScreenWithCutout = true;

    private static final int MSG_STOP_SUBTITLE = 0;
    private static final int MSG_DISPLAY_SUBTITLE = 1;
    private static final int MSG_REMOVE_SUBTITLE = 2;

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
     * Converts Nova's internal Subtitle object into a Media3/ExoPlayer Cue.
     */
    private Cue mapToExoCue(com.archos.medialib.Subtitle subtitle) {
        Cue.Builder builder = new Cue.Builder();

        if (subtitle.isText()) {
                    SpannableStringBuilder ssb = new SpannableStringBuilder(
                        HtmlCompat.fromHtml(cleanText(subtitle.getText()), HtmlCompat.FROM_HTML_MODE_LEGACY)
                    );
                    builder.setText(ssb);

                    // Parse embedded SSA/SRT alignment tags
                    SubtitleAlignment alignment = getAlignment(subtitle.getText());

                    float line = 0.95f; // Default near bottom
                    @Cue.AnchorType int lineAnchor = Cue.ANCHOR_TYPE_END;
                    float position = 0.5f; // Default center
                    @Cue.AnchorType int positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                    Layout.Alignment textAlignment = Layout.Alignment.ALIGN_CENTER;

                    // Map alignment to ExoPlayer anchors accurately
                    switch (alignment) {
                        case TOP_LEFT:
                            line = 0.05f; lineAnchor = Cue.ANCHOR_TYPE_START;
                            position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                            textAlignment = Layout.Alignment.ALIGN_NORMAL; // Left justify
                            break;
                        case TOP_MID:
                            line = 0.05f; lineAnchor = Cue.ANCHOR_TYPE_START;
                            position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            textAlignment = Layout.Alignment.ALIGN_CENTER;
                            break;
                        case TOP_RIGHT:
                            line = 0.05f; lineAnchor = Cue.ANCHOR_TYPE_START;
                            position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                            textAlignment = Layout.Alignment.ALIGN_OPPOSITE; // Right justify
                            break;
                        case MID_LEFT:
                            line = 0.5f; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                            textAlignment = Layout.Alignment.ALIGN_NORMAL;
                            break;
                        case MID_MID:
                            line = 0.5f; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            textAlignment = Layout.Alignment.ALIGN_CENTER;
                            break;
                        case MID_RIGHT:
                            line = 0.5f; lineAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                            textAlignment = Layout.Alignment.ALIGN_OPPOSITE;
                            break;
                        case BOTTOM_LEFT:
                            line = 0.95f; lineAnchor = Cue.ANCHOR_TYPE_END;
                            position = 0.05f; positionAnchor = Cue.ANCHOR_TYPE_START;
                            textAlignment = Layout.Alignment.ALIGN_NORMAL;
                            break;
                        case BOTTOM_RIGHT:
                            line = 0.95f; lineAnchor = Cue.ANCHOR_TYPE_END;
                            position = 0.95f; positionAnchor = Cue.ANCHOR_TYPE_END;
                            textAlignment = Layout.Alignment.ALIGN_OPPOSITE;
                            break;
                        case BOTTOM_MID:
                        default:
                            line = 0.95f; lineAnchor = Cue.ANCHOR_TYPE_END;
                            position = 0.5f; positionAnchor = Cue.ANCHOR_TYPE_MIDDLE;
                            textAlignment = Layout.Alignment.ALIGN_CENTER;
                            break;
                    }

                    builder.setLine(line, Cue.LINE_TYPE_FRACTION);
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

    private void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_STOP_SUBTITLE:
            case MSG_REMOVE_SUBTITLE:
                if (mExoSubtitleView != null) {
                    mExoSubtitleView.setCues(Collections.emptyList()); // Clear screen instantly
                }
                break;
            case MSG_DISPLAY_SUBTITLE:
                if (msg.obj == null) break;
                Cue cue = mapToExoCue((Subtitle) msg.obj);
                if (mExoSubtitleView != null) {
                    mExoSubtitleView.setCues(Collections.singletonList(cue)); // Render instantly
                }
                break;
        }
    }

    private void removeSubtitle(Subtitle subtitle) {
        mHandler.removeMessages(MSG_DISPLAY_SUBTITLE);
        mHandler.removeMessages(MSG_REMOVE_SUBTITLE);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_REMOVE_SUBTITLE, subtitle));
    }

    private void displaySubtitle(Subtitle subtitle) {
        mHandler.removeMessages(MSG_REMOVE_SUBTITLE);
        mHandler.removeMessages(MSG_DISPLAY_SUBTITLE);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPLAY_SUBTITLE, subtitle));
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
    public void setColor(int color){ mColor = color; updateExoPlayerStyle(); }

    public boolean getOutlineState() { return mOutline; }
    public void setOutlineState(boolean outline) { mOutline = outline; updateExoPlayerStyle(); }

    public boolean getBackgroundState() { return mBackground; }
    public void setBackgroundState(boolean background) { mBackground = background; updateExoPlayerStyle(); }

    public int getBackgroundOpacity() { return mBgOpacity; }
    public void setBackgroundOpacity(int opacity) { mBgOpacity = opacity; updateExoPlayerStyle(); }

    public void setSize(int size) { mSubtitleSize = size; updateExoPlayerStyle(); }
    public int getSize() { return mSubtitleSize; }

    public static float calcTextSize(int size) {
        int tmp = size > 100 ? 100 : Math.max(size, 0);
        return (tmp / 100f) * TXT_SIZE_RANGE + TXT_SIZE_MIN;
    }

    public void setUIMode(int uiMode) { mUiMode = uiMode; }

    final class DispSubtitleThread extends Thread {
        private boolean mSuspended = true;
        private boolean mRunning = true;
        private Subtitle mCurrentSubtitle = null;
        private Subtitle mNextSubtitle = null;
        private boolean interrupted = false;

        void quit() {
            mRunning = false;
            mDispSubtitleThread = null;
            interrupt();
            try { join(); } catch (InterruptedException e) {}
        }

        @Override
        public void run() {
            int mSubtitleDisplayLeft = 0;
            while (mRunning) {
                interrupted = false;
                synchronized (this) {
                    while (mSuspended) {
                        try { wait(); } catch (InterruptedException e) {
                            if (!mRunning) { clear(); return; }
                        }
                    }
                }
                synchronized (this) {
                    if ((mCurrentSubtitle == null && mNextSubtitle == null) || (mCurrentSubtitle == null && mNextSubtitle != null && mNextSubtitle.getDuration() == 0)) {
                        if (mNextSubtitle != null) mNextSubtitle = null;
                        mSuspended = true;
                        continue;
                    }

                    if (mCurrentSubtitle == null) {
                        mCurrentSubtitle = mNextSubtitle;
                        currentSubtitle = mCurrentSubtitle;
                        mNextSubtitle = null;
                        displaySubtitle(mCurrentSubtitle);
                        mSubtitleDisplayLeft = mCurrentSubtitle.getDuration();
                    }
                }

                if (mSubtitleDisplayLeft > 0) {
                    long sleepStart = System.currentTimeMillis();
                    try {
                        sleep(mSubtitleDisplayLeft);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        long elapsedTime = System.currentTimeMillis() - sleepStart;
                        if (mCurrentSubtitle != null && mNextSubtitle != null) {
                            int currentPosition = mCurrentSubtitle.getPosition() + (int) elapsedTime;
                            int realCurrentSubtitleDuration;
                            if (mCurrentSubtitle.getPosition() + mCurrentSubtitle.getDuration() > mNextSubtitle.getPosition()) {
                                realCurrentSubtitleDuration = mNextSubtitle.getPosition() - mCurrentSubtitle.getPosition();
                                mCurrentSubtitle.setDuration(realCurrentSubtitleDuration);
                                mSubtitleDisplayLeft = mNextSubtitle.getPosition() - currentPosition;
                            } else {
                                realCurrentSubtitleDuration = mCurrentSubtitle.getDuration();
                                mSubtitleDisplayLeft -= (int) (System.currentTimeMillis() - sleepStart);
                            }
                            if (mNextSubtitle.getDuration() == 0) mNextSubtitle = null;
                        } else {
                            mSubtitleDisplayLeft -= (int) (System.currentTimeMillis() - sleepStart);
                        }
                    }
                    if (! interrupted) mSubtitleDisplayLeft -= (int) (System.currentTimeMillis() - sleepStart);
                }

                if (mSubtitleDisplayLeft <= 0) {
                    synchronized (this) {
                        if (mCurrentSubtitle != null) {
                            // --- ZERO GAP LOGIC ---
                            boolean isZeroGap = false;
                            if (mNextSubtitle != null) {
                                long gap = mNextSubtitle.getPosition() - (mCurrentSubtitle.getPosition() + mCurrentSubtitle.getDuration());
                                if (gap <= 30) isZeroGap = true; // 30ms tolerance
                            }

                            // Only clear the screen if there's an actual pause in dialogue
                            if (!isZeroGap) {
                                removeSubtitle(mCurrentSubtitle);
                            }

                            mCurrentSubtitle = null;
                            currentSubtitle = null;
                            mSubtitleDisplayLeft = 0;
                        }
                    }
                }
            }
            clear();
        }

        synchronized void addSubtitle(Subtitle subtitle) {
            mSuspended = false;
            if (subtitle.isTimed()) {
                mNextSubtitle = subtitle;
                if (!isAlive()) super.start();
                else interrupt();
            } else {
                if (mCurrentSubtitle != null) {
                    removeSubtitle(mCurrentSubtitle);
                    mCurrentSubtitle = null;
                }
                if (subtitle.getText() != null) {
                    mCurrentSubtitle = subtitle;
                    displaySubtitle(mCurrentSubtitle);
                }
            }
        }

        synchronized void show() {}
        synchronized void clear() {
            mSuspended = true;
            if (mCurrentSubtitle != null) {
                removeSubtitle(mCurrentSubtitle);
                mCurrentSubtitle = null;
                mNextSubtitle = null;
            }
            mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_SUBTITLE));
        }
        synchronized void setSuspended(boolean suspended) {
            if (mSuspended == suspended) return;
            mSuspended = suspended;
            interrupt();
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
        if (currentSubtitle != null) displaySubtitle(currentSubtitle);
        setSize(mSubtitleSize);
        updateSubtitleLayout();
    }

    public void updateSubtitleLayout() {
        if (! isFirstTime) adjustView();
        if (currentSubtitle != null) displaySubtitle(currentSubtitle);
    }

    public void setUIExternalSurface(Surface uiSurface) {
        mUiSurface = uiSurface;
        if (mSubtitleSpacer != null) mSubtitleSpacer.setRenderingSurface(uiSurface);
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
            mDispSubtitleThread.clear();
            mDispSubtitleThread.interrupt();
        }
    }
}
