package com.spellswitch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервис нужен здесь не ради его "специальных возможностей" в обычном смысле,
 * а как единственный легальный способ рисовать системный оверлей
 * (TYPE_ACCESSIBILITY_OVERLAY) без разрешения SYSTEM_ALERT_WINDOW —
 * пользователь один раз включает его в Настройки → Специальные возможности.
 *
 * Делает две независимые вещи:
 * 1) Плавающий ярлык-переключатель языка проверки орфографии (тап — цикл,
 *    долгий тап — меню, драг — перемещение). Работает всегда.
 * 2) Опционально (по чекбоксу в настройках) — автоматическая проверка текста
 *    в любом редактируемом поле: подчёркивание слов с ошибками и подсказки
 *    по тапу. Язык для проверки определяется по самому тексту и выставляется
 *    через тот же SpellCheckerSwitcher, которым управляет ярлык — поэтому
 *    они не могут конфликтовать: это одно и то же состояние.
 */
public class OverlayAccessibilityService extends AccessibilityService
        implements SpellCheckerSession.SpellCheckerSessionListener {

    static final String PREFS_NAME = "spellswitch_prefs";
    static final String KEY_TAP_THROUGH = "tap_through_enabled";
    static final String KEY_ALPHA_PERCENT = "overlay_alpha_percent";
    static final String KEY_HEIGHT_DP = "overlay_height_dp";
    static final String KEY_OVERLAY_X = "overlay_x";
    static final String KEY_OVERLAY_Y = "overlay_y";
    static final String KEY_SHOW_FLAG = "show_flag";
    static final String KEY_AUTO_SPELLCHECK_ENABLED = "auto_spellcheck_enabled";

    private static final int LONG_PRESS_MS = 500;
    private static final int DRAG_SLOP_PX = 12;
    private static final int DEFAULT_ALPHA_PERCENT = 80;
    private static final int DEFAULT_HEIGHT_DP = 48;

    private static final long DEBOUNCE_MS = 900;
    private static final int MAX_CHECK_LENGTH = 400;
    private static final int MAX_SUGGESTIONS = 4;

    // --- переключатель языка (ярлык) ---

    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams layoutParams;
    private boolean overlayAdded;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    private float touchStartX;
    private float touchStartY;
    private int paramStartX;
    private int paramStartY;
    private boolean isDragging;
    private long touchDownTime;

    // --- автопроверка орфографии ---

    private TextView statusView;
    private UnderlineView underlineView;
    private SpellCheckerSession spellCheckerSession;
    private Handler debounceHandler;
    private int spellOffsetX;
    private int spellOffsetY;

    private AccessibilityNodeInfo trackedNode;
    private String trackedPackage = "";
    private int requestGeneration = 0;
    private String lastCheckedText = "";
    private final List<Word> misspelledWords = new ArrayList<>();
    private final List<View> touchTargets = new ArrayList<>();
    private View suggestionPopup;

    private String statusLangCode = "\u2014";
    private int statusMisspelledCount = 0;
    private String statusMisspelledList = "";
    private String statusError = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prepareOverlay();
        prepareSpellCheckOverlays();
        registerPrefsListener();
        updateOverlayVisibility();
    }

    // ================= переключатель языка (без изменений по сути) =================

    @SuppressLint("ClickableViewAccessibility")
    private void prepareOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new TextView(this);
        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
        overlayView.setTextColor(Color.WHITE);
        overlayView.setBackgroundColor(Color.parseColor("#1565C0"));
        overlayView.setGravity(Gravity.CENTER);
        overlayView.setPadding(28, 0, 28, 0);
        overlayView.setTextSize(14);
        applyAlpha();

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                dpToPx(getHeightDp()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        loadSavedPosition();

        overlayView.setOnTouchListener(this::onOverlayTouch);
    }

    private void registerPrefsListener() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefsListener = (sp, key) -> {
            if (KEY_ALPHA_PERCENT.equals(key)) {
                applyAlpha();
            } else if (KEY_HEIGHT_DP.equals(key)) {
                applyHeight();
            } else if (KEY_SHOW_FLAG.equals(key)) {
                overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
            } else if (KEY_AUTO_SPELLCHECK_ENABLED.equals(key)) {
                applySpellCheckVisibility();
                if (!isAutoSpellCheckEnabled()) {
                    clearSpellCheckOverlays();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void applyAlpha() {
        int percent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_ALPHA_PERCENT, DEFAULT_ALPHA_PERCENT);
        overlayView.setAlpha(percent / 100f);
    }

    private void applyHeight() {
        layoutParams.height = dpToPx(getHeightDp());
        if (overlayAdded) {
            windowManager.updateViewLayout(overlayView, layoutParams);
        }
    }

    private int getHeightDp() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Показывает/прячет ярлык в зависимости от того, есть ли сейчас среди
     * окон экрана окно с типом TYPE_INPUT_METHOD (то есть открыта клавиатура).
     */
    private void updateOverlayVisibility() {
        boolean keyboardVisible = isKeyboardVisible();

        if (keyboardVisible && !overlayAdded) {
            windowManager.addView(overlayView, layoutParams);
            overlayAdded = true;
        } else if (!keyboardVisible && overlayAdded) {
            windowManager.removeView(overlayView);
            overlayAdded = false;
        }
    }

    private boolean isKeyboardVisible() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true;
            }
        }
        return false;
    }

    private void loadSavedPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        layoutParams.x = prefs.getInt(KEY_OVERLAY_X, 40);
        layoutParams.y = prefs.getInt(KEY_OVERLAY_Y, 200);
    }

    private void savePosition() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_OVERLAY_X, layoutParams.x)
                .putInt(KEY_OVERLAY_Y, layoutParams.y)
                .apply();
    }

    private boolean onOverlayTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                paramStartX = layoutParams.x;
                paramStartY = layoutParams.y;
                isDragging = false;
                touchDownTime = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (!isDragging && (Math.abs(dx) > DRAG_SLOP_PX || Math.abs(dy) > DRAG_SLOP_PX)) {
                    isDragging = true;
                }
                if (isDragging) {
                    layoutParams.x = paramStartX + (int) dx;
                    layoutParams.y = paramStartY + (int) dy;
                    windowManager.updateViewLayout(overlayView, layoutParams);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                long heldMs = System.currentTimeMillis() - touchDownTime;
                if (isDragging) {
                    savePosition();
                } else if (heldMs >= LONG_PRESS_MS) {
                    showLanguageMenu();
                } else {
                    cycleLanguage();
                }
                return true;
            }
        }
        return false;
    }

    private void cycleLanguage() {
        SpellCheckerSwitcher.cycleNext(this);
        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
        restartSpellCheckerSessionForNewLanguage();

        if (isTapThroughEnabled()) {
            dispatchTapThrough();
        }
    }

    /**
     * Синтетический тап ровно в то место экрана, где сейчас лежит оверлей —
     * если он откалиброван на кнопку переключения раскладки клавиатуры, этот
     * тап попадёт на неё. Требует android:canPerformGestures="true" в
     * конфиге сервиса. dispatchGesture() — публичный API AccessibilityService
     * с API 24.
     */
    private void dispatchTapThrough() {
        int[] loc = new int[2];
        overlayView.getLocationOnScreen(loc);
        float x = loc[0] + overlayView.getWidth() / 2f;
        float y = loc[1] + overlayView.getHeight() / 2f;

        float savedAlpha = overlayView.getAlpha();
        overlayView.setVisibility(View.INVISIBLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    overlayView.setAlpha(savedAlpha);
                    overlayView.setVisibility(View.VISIBLE);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    overlayView.setAlpha(savedAlpha);
                    overlayView.setVisibility(View.VISIBLE);
                }
            }, null);
        }, 60);
    }

    private boolean isTapThroughEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_TAP_THROUGH, true);
    }

    private void setTapThroughEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_TAP_THROUGH, enabled).apply();
    }

    private void showLanguageMenu() {
        boolean tapThroughOn = isTapThroughEnabled();
        String[] order = SpellCheckerSwitcher.getOrder(this);

        String[] items = new String[order.length + 1];
        for (int i = 0; i < order.length; i++) {
            items[i] = SpellCheckerSwitcher.menuNameFor(order[i]);
        }
        items[order.length] = "Синхро-тап по раскладке: "
                + (tapThroughOn ? "ВКЛ (нажмите, чтобы выключить)" : "ВЫКЛ (нажмите, чтобы включить)");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(items, (d, which) -> {
                    if (which < order.length) {
                        SpellCheckerSwitcher.setLanguage(this, order[which]);
                        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
                        restartSpellCheckerSessionForNewLanguage();
                    } else {
                        setTapThroughEnabled(!tapThroughOn);
                    }
                })
                .create();

        showAsOverlay(dialog);
    }

    private void showAsOverlay(AlertDialog dialog) {
        // Диалог из Service/AccessibilityService не имеет своего Activity-окна,
        // поэтому тип окна нужно выставить вручную — тем же типом, что и оверлей.
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    /** Ручное переключение (ярлык/меню) тоже должно перезапустить сессию проверки. */
    private void restartSpellCheckerSessionForNewLanguage() {
        if (spellCheckerSession != null) {
            spellCheckerSession.close();
            spellCheckerSession = null;
        }
    }

    // ================= автопроверка орфографии =================

    private boolean isAutoSpellCheckEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SPELLCHECK_ENABLED, false);
    }

    private void prepareSpellCheckOverlays() {
        debounceHandler = new Handler(Looper.getMainLooper());

        statusView = new TextView(this);
        statusView.setBackgroundColor(0xCC000000);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(11);
        statusView.setPadding(16, 16, 16, 16);
        WindowManager.LayoutParams statusParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        statusParams.gravity = Gravity.TOP;
        windowManager.addView(statusView, statusParams);

        underlineView = new UnderlineView(this);
        WindowManager.LayoutParams underlineParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        underlineParams.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(underlineView, underlineParams);

        underlineView.post(() -> {
            int[] location = new int[2];
            underlineView.getLocationOnScreen(location);
            spellOffsetX = location[0];
            spellOffsetY = location[1];
            underlineView.setOffset(spellOffsetX, spellOffsetY);
        });

        applySpellCheckVisibility();
    }

    private void applySpellCheckVisibility() {
        boolean enabled = isAutoSpellCheckEnabled();
        if (statusView != null) {
            statusView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshStatus() {
        if (statusView == null || !isAutoSpellCheckEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Автопроверка \u2014 язык: ").append(statusLangCode)
                .append(", ошибок: ").append(statusMisspelledCount);
        if (statusMisspelledCount > 0) {
            sb.append(" (").append(statusMisspelledList).append(')');
        }
        if (statusError != null) {
            sb.append("\n\u26A0 ").append(statusError);
        }
        statusView.setText(sb.toString());
    }

    private String getCurrentImePackage() {
        try {
            String ime = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                int slash = ime.indexOf('/');
                return slash > 0 ? ime.substring(0, slash) : ime;
            }
        } catch (Exception e) {
            // игнорируем
        }
        return null;
    }

    private String detectLangCode(String text) {
        boolean hasUkrainianLetter = false;
        boolean hasRussianOnlyLetter = false;
        int cyrillicCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
                cyrillicCount++;
            }
            if ("\u0456\u0457\u0454\u0491\u0406\u0407\u0404\u0490".indexOf(c) >= 0) {
                hasUkrainianLetter = true;
            }
            if ("\u044B\u044D\u044A\u042B\u042D\u042A".indexOf(c) >= 0) {
                hasRussianOnlyLetter = true;
            }
        }

        if (hasUkrainianLetter) {
            return "uk";
        }
        if (hasRussianOnlyLetter) {
            return "ru";
        }

        boolean mostlyCyrillic = text.length() > 0 && cyrillicCount * 2 > text.length();
        if (mostlyCyrillic) {
            String current = SpellCheckerSwitcher.currentCode(this);
            if ("ru".equals(current) || "uk".equals(current)) {
                return current;
            }
            return "ru";
        }

        return "en";
    }

    private void ensureSpellCheckerSession() {
        try {
            TextServicesManager tsm = (TextServicesManager)
                    getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            spellCheckerSession = tsm.newSpellCheckerSession(null, null, this, true);
            if (spellCheckerSession != null) {
                statusError = null;
            }
        } catch (Exception e) {
            spellCheckerSession = null;
            statusError = "Ошибка создания спелчекера: " + e;
        }
        if (spellCheckerSession == null && statusError == null) {
            statusError = "newSpellCheckerSession() вернул null.";
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        updateOverlayVisibility();

        if (!isAutoSpellCheckEnabled()) {
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCs = event.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : "";
            String imePackage = getCurrentImePackage();
            boolean isKeyboard = imePackage != null && imePackage.equals(pkg);
            boolean isSelf = pkg.equals(getPackageName());
            if (!pkg.isEmpty() && !isKeyboard && !isSelf && !pkg.equals(trackedPackage)) {
                if (debounceHandler != null) {
                    debounceHandler.removeCallbacksAndMessages(null);
                }
                statusMisspelledCount = 0;
                statusMisspelledList = "";
                refreshStatus();
                clearSpellCheckOverlays();
            }
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence scrollPkgCs = event.getPackageName();
            String scrollPkg = scrollPkgCs != null ? scrollPkgCs.toString() : "";
            if (scrollPkg.equals(trackedPackage)) {
                clearSpellCheckOverlays();
            }
            return;
        }

        AccessibilityNodeInfo node = event.getSource();
        if (node == null) {
            return;
        }
        if (!node.isEditable()) {
            node.recycle();
            return;
        }

        CharSequence text = node.getText();
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "\u2014";

        if (text == null || text.length() == 0) {
            node.recycle();
            if (pkg.equals(trackedPackage) && debounceHandler != null) {
                debounceHandler.removeCallbacksAndMessages(null);
                debounceHandler.postDelayed(() -> {
                    statusMisspelledCount = 0;
                    statusMisspelledList = "";
                    refreshStatus();
                    clearSpellCheckOverlays();
                }, 300);
            }
            return;
        }

        if (trackedNode != null) {
            trackedNode.recycle();
        }
        trackedNode = node;
        trackedPackage = pkg;

        if (debounceHandler == null) {
            return;
        }
        debounceHandler.removeCallbacksAndMessages(null);

        final String snapshot = text.toString();
        debounceHandler.postDelayed(() -> checkText(snapshot), DEBOUNCE_MS);
    }

    private void checkText(String fullText) {
        try {
            if (trackedNode == null) {
                return;
            }
            if (!trackedNode.refresh()) {
                return;
            }
            CharSequence currentText = trackedNode.getText();
            if (currentText == null || !currentText.toString().equals(fullText)) {
                return;
            }

            requestGeneration++;

            String detectedLangCode = detectLangCode(fullText);
            String actualCode = SpellCheckerSwitcher.currentCode(this);
            if (!detectedLangCode.equals(actualCode)) {
                SpellCheckerSwitcher.setLanguage(this, detectedLangCode);
                overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
                if (spellCheckerSession != null) {
                    spellCheckerSession.close();
                    spellCheckerSession = null;
                }
            }
            statusLangCode = detectedLangCode;

            if (spellCheckerSession == null) {
                ensureSpellCheckerSession();
            }
            if (spellCheckerSession == null) {
                refreshStatus();
                return;
            }

            String textToCheck = fullText.length() > MAX_CHECK_LENGTH
                    ? fullText.substring(0, MAX_CHECK_LENGTH) : fullText;
            lastCheckedText = textToCheck;

            TextInfo info = new TextInfo(textToCheck, requestGeneration, 0);
            spellCheckerSession.getSentenceSuggestions(new TextInfo[]{info}, MAX_SUGGESTIONS);
        } catch (Exception e) {
            statusError = "Ошибка проверки: " + e;
            refreshStatus();
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        // Не используется — проверяем целым предложением через onGetSentenceSuggestions.
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        try {
            if (results == null || results.length == 0) {
                return;
            }
            SentenceSuggestionsInfo ssi = results[0];
            int spanCount = ssi.getSuggestionsCount();

            if (spanCount > 0) {
                SuggestionsInfo firstInfo = ssi.getSuggestionsInfoAt(0);
                if (firstInfo != null && firstInfo.getCookie() != requestGeneration) {
                    return;
                }
            }

            List<Word> flagged = new ArrayList<>();
            List<String> names = new ArrayList<>();

            for (int i = 0; i < spanCount; i++) {
                SuggestionsInfo info = ssi.getSuggestionsInfoAt(i);
                if (info == null) {
                    continue;
                }
                int offset = ssi.getOffsetAt(i);
                int length = ssi.getLengthAt(i);
                if (offset < 0 || length <= 0 || offset + length > lastCheckedText.length()) {
                    continue;
                }
                String wordText = lastCheckedText.substring(offset, offset + length);
                int attrs = info.getSuggestionsAttributes();
                int count = info.getSuggestionsCount();

                boolean looksLikeTypo =
                        (attrs & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0;
                if (!looksLikeTypo) {
                    continue;
                }

                Word word = new Word();
                word.start = offset;
                word.end = offset + length;
                word.text = wordText;
                List<String> suggestions = new ArrayList<>();
                int limit = Math.min(Math.max(count, 0), MAX_SUGGESTIONS);
                for (int j = 0; j < limit; j++) {
                    suggestions.add(info.getSuggestionAt(j));
                }
                word.suggestions = suggestions;
                flagged.add(word);
                names.add(word.text);
            }

            statusMisspelledCount = flagged.size();
            statusMisspelledList = String.join(", ", names);
            statusError = null;
            refreshStatus();

            applyFlaggedWords(flagged);
        } catch (Exception e) {
            statusError = "Ошибка обработки результата: " + e;
            refreshStatus();
        }
    }

    private void applyFlaggedWords(List<Word> flagged) {
        try {
            misspelledWords.clear();
            misspelledWords.addAll(flagged);

            if (trackedNode == null || !trackedNode.refresh()) {
                misspelledWords.clear();
                underlineView.setWords(new ArrayList<>());
                return;
            }

            for (Word word : misspelledWords) {
                Bundle args = new Bundle();
                args.putInt(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
                        word.start);
                args.putInt(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                        word.end - word.start);
                trackedNode.refreshWithExtraData(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);
                Bundle extras = trackedNode.getExtras();
                Parcelable[] rects = extras.getParcelableArray(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
                word.rect = unionRect(rects);
            }

            removeSuggestionPopup();
            rebuildTouchTargets();
            underlineView.setWords(misspelledWords);
        } catch (Exception e) {
            statusError = "Ошибка позиционирования: " + e;
            refreshStatus();
        }
    }

    private RectF unionRect(Parcelable[] rects) {
        if (rects == null) {
            return null;
        }
        RectF union = null;
        for (Parcelable p : rects) {
            if (p instanceof RectF) {
                RectF r = (RectF) p;
                if (union == null) {
                    union = new RectF(r);
                } else {
                    union.union(r);
                }
            }
        }
        return union;
    }

    private void rebuildTouchTargets() {
        for (View v : touchTargets) {
            safeRemoveView(v);
        }
        touchTargets.clear();

        for (Word word : misspelledWords) {
            if (word.rect == null) {
                continue;
            }
            View hitArea = new View(this);
            hitArea.setOnClickListener(v -> showSuggestionPopup(word));

            int left = (int) word.rect.left - spellOffsetX;
            int top = (int) word.rect.top - spellOffsetY;
            int width = Math.max(1, (int) (word.rect.right - word.rect.left));
            int height = Math.max(1, (int) (word.rect.bottom - word.rect.top));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = left;
            params.y = top;

            windowManager.addView(hitArea, params);
            touchTargets.add(hitArea);
        }
    }

    private void showSuggestionPopup(Word word) {
        removeSuggestionPopup();
        if (word.rect == null) {
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFFFFFFFF);
        layout.setPadding(12, 12, 12, 12);

        if (word.suggestions == null || word.suggestions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Помечено как ошибка, вариантов замены нет");
            empty.setPadding(8, 8, 8, 8);
            layout.addView(empty);
        }

        for (String suggestion : word.suggestions == null ? new ArrayList<String>() : word.suggestions) {
            Button button = new Button(this);
            button.setText(suggestion);
            button.setAllCaps(false);
            button.setOnClickListener(v -> {
                applySuggestion(word, suggestion);
                removeSuggestionPopup();
            });
            layout.addView(button);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (int) word.rect.left - spellOffsetX;
        params.y = (int) word.rect.bottom - spellOffsetY + 8;

        windowManager.addView(layout, params);
        suggestionPopup = layout;
    }

    private void removeSuggestionPopup() {
        if (suggestionPopup != null) {
            safeRemoveView(suggestionPopup);
            suggestionPopup = null;
        }
    }

    private void applySuggestion(Word word, String replacement) {
        if (trackedNode == null || !trackedNode.refresh()) {
            return;
        }
        CharSequence current = trackedNode.getText();
        boolean stillValid = current != null
                && word.end <= current.length()
                && current.subSequence(word.start, word.end).toString().equals(word.text);

        if (!stillValid) {
            clearSpellCheckOverlays();
            return;
        }

        String updated = current.subSequence(0, word.start).toString()
                + replacement
                + current.subSequence(word.end, current.length()).toString();

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
        trackedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        clearSpellCheckOverlays();
    }

    private void clearSpellCheckOverlays() {
        misspelledWords.clear();
        if (underlineView != null) {
            underlineView.setWords(new ArrayList<>());
        }
        removeSuggestionPopup();
        for (View v : touchTargets) {
            safeRemoveView(v);
        }
        touchTargets.clear();
    }

    private void safeRemoveView(View v) {
        try {
            windowManager.removeView(v);
        } catch (IllegalArgumentException e) {
        }
    }

    private static class Word {
        int start;
        int end;
        String text;
        List<String> suggestions;
        RectF rect;
    }

    private static class UnderlineView extends View {
        private List<Word> words = new ArrayList<>();
        private final Paint paint = new Paint();
        private int offsetX;
        private int offsetY;

        UnderlineView(Context context) {
            super(context);
            paint.setColor(0xFFAA00FF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6f);
        }

        void setOffset(int x, int y) {
            offsetX = x;
            offsetY = y;
        }

        void setWords(List<Word> newWords) {
            this.words = newWords;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (Word w : words) {
                if (w.rect == null) {
                    continue;
                }
                float left = w.rect.left - offsetX;
                float right = w.rect.right - offsetX;
                float y = w.rect.bottom - offsetY;
                drawWavyLine(canvas, left, right, y);
            }
        }

        private void drawWavyLine(Canvas canvas, float left, float right, float y) {
            Path path = new Path();
            float waveWidth = 10f;
            float waveHeight = 4f;
            path.moveTo(left, y);
            float x = left;
            boolean up = true;
            while (x < right) {
                float nextX = Math.min(x + waveWidth, right);
                float controlX = (x + nextX) / 2;
                float controlY = up ? y - waveHeight : y + waveHeight;
                path.quadTo(controlX, controlY, nextX, y);
                x = nextX;
                up = !up;
            }
            canvas.drawPath(path, paint);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (prefsListener != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        clearSpellCheckOverlays();
        if (trackedNode != null) {
            trackedNode.recycle();
            trackedNode = null;
        }
        if (spellCheckerSession != null) {
            spellCheckerSession.close();
            spellCheckerSession = null;
        }
        return super.onUnbind(intent);
    }
}
