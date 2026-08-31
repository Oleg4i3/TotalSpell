package com.example.spellprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
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
import java.util.Locale;

public class ProbeAccessibilityService extends AccessibilityService
        implements SpellCheckerSession.SpellCheckerSessionListener {

    private static final long DEBOUNCE_MS = 900;
    private static final int MAX_CHECK_LENGTH = 400;
    private static final int MAX_SUGGESTIONS = 4;

    private WindowManager windowManager;
    private TextView statusView;
    private UnderlineView underlineView;
    private SpellCheckerSession spellCheckerSession;
    private Locale currentSessionLocale;
    private Handler handler;

    private int overlayOffsetX;
    private int overlayOffsetY;

    private AccessibilityNodeInfo trackedNode;
    private String trackedPackage = "";

    private int requestGeneration = 0;
    private String lastCheckedText = "";
    private final List<Word> misspelledWords = new ArrayList<>();
    private final List<View> touchTargets = new ArrayList<>();
    private View suggestionPopup;

    private String statusApp = "\u2014";
    private int statusTextLength = 0;
    private int statusWordsFound = 0;
    private int statusMisspelledCount = 0;
    private String statusMisspelledList = "";
    private String statusDebug = "";
    private String statusError = null;
    private int checkTextCount = 0;
    private int getSuggestionsCallCount = 0;
    private int onGetSuggestionsCount = 0;
    private String lastCallbackInfo = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

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
            overlayOffsetX = location[0];
            overlayOffsetY = location[1];
            underlineView.setOffset(overlayOffsetX, overlayOffsetY);
        });

        ensureSpellCheckerSession(Locale.getDefault());

        refreshStatus();
    }

    private String getCurrentImePackage() {
        try {
            String ime = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                int slash = ime.indexOf('/');
                return slash > 0 ? ime.substring(0, slash) : ime;
            }
        } catch (Exception e) {
            // игнорируем — просто не будем знать пакет клавиатуры
        }
        return null;
    }

    private Locale detectLocale() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                if (subtype != null) {
                    String tag = subtype.getLanguageTag();
                    if (tag != null && !tag.isEmpty()) {
                        return Locale.forLanguageTag(tag);
                    }
                    String localeStr = subtype.getLocale();
                    if (localeStr != null && !localeStr.isEmpty()) {
                        String lang = localeStr.split("_")[0];
                        return new Locale(lang);
                    }
                }
            }
        } catch (Exception e) {
            // игнорируем, используем запасной вариант ниже
        }
        return Locale.getDefault();
    }

    private void ensureSpellCheckerSession(Locale locale) {
        try {
            TextServicesManager tsm = (TextServicesManager)
                    getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            spellCheckerSession = tsm.newSpellCheckerSession(null, locale, this, true);
            if (spellCheckerSession == null) {
                // На некоторых устройствах конкретный Locale не проходит — пробуем без него.
                spellCheckerSession = tsm.newSpellCheckerSession(null, null, this, true);
            }
            currentSessionLocale = locale;
            if (spellCheckerSession != null) {
                statusError = null;
            }
        } catch (Exception e) {
            spellCheckerSession = null;
            statusError = "Ошибка создания спелчекера: " + e;
        }

        if (spellCheckerSession == null && statusError == null) {
            statusError = "newSpellCheckerSession() вернул null. Проверьте Настройки "
                    + "\u2192 Система \u2192 Язык и ввод \u2192 Проверка правописания.";
        }
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TotalSpell \u2014 приложение: ").append(statusApp).append('\n');
        sb.append("символов: ").append(statusTextLength)
                .append(", язык: ").append(currentSessionLocale == null ? "\u2014" : currentSessionLocale)
                .append(", слов найдено: ").append(statusWordsFound)
                .append(", с ошибками: ").append(statusMisspelledCount);
        if (statusMisspelledCount > 0) {
            sb.append(" (").append(statusMisspelledList).append(')');
        }
        if (statusDebug != null && statusDebug.length() > 0) {
            sb.append('\n').append(statusDebug);
        }
        if (statusError != null) {
            sb.append("\n\u26A0 ").append(statusError);
        }
        sb.append("\ncheckText:").append(checkTextCount)
                .append(" getSuggestions вызван:").append(getSuggestionsCallCount)
                .append(" onGetSuggestions сработал:").append(onGetSuggestionsCount);
        if (lastCallbackInfo.length() > 0) {
            sb.append('\n').append(lastCallbackInfo);
        }
        statusView.setText(sb.toString());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCs = event.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : "";
            String imePackage = getCurrentImePackage();
            boolean isKeyboard = imePackage != null && imePackage.equals(pkg);
            boolean isSelf = pkg.equals(getPackageName());
            if (!pkg.isEmpty() && !isKeyboard && !isSelf && !pkg.equals(trackedPackage)) {
                if (handler != null) {
                    handler.removeCallbacksAndMessages(null);
                }
                statusApp = pkg;
                statusTextLength = 0;
                statusWordsFound = 0;
                statusMisspelledCount = 0;
                statusMisspelledList = "";
                statusDebug = "";
                refreshStatus();
                clearAll();
            }
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence scrollPkgCs = event.getPackageName();
            String scrollPkg = scrollPkgCs != null ? scrollPkgCs.toString() : "";
            if (scrollPkg.equals(trackedPackage)) {
                clearAll();
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
            if (pkg.equals(trackedPackage) && handler != null) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> {
                    statusApp = pkg;
                    statusTextLength = 0;
                    statusWordsFound = 0;
                    statusMisspelledCount = 0;
                    statusMisspelledList = "";
                    statusDebug = "";
                    refreshStatus();
                    clearAll();
                }, 300);
            }
            return;
        }

        if (trackedNode != null) {
            trackedNode.recycle();
        }
        trackedNode = node;
        trackedPackage = pkg;

        if (handler == null) {
            return;
        }
        handler.removeCallbacksAndMessages(null);

        statusApp = pkg;
        statusTextLength = text.length();
        refreshStatus();

        final String snapshot = text.toString();
        handler.postDelayed(() -> checkText(snapshot), DEBOUNCE_MS);
    }

    private void checkText(String fullText) {
        try {
            if (trackedNode == null) {
                statusError = "checkText: нет отслеживаемого поля";
                refreshStatus();
                return;
            }
            boolean refreshed = trackedNode.refresh();
            if (!refreshed) {
                statusError = "checkText: refresh() не удался, поле недоступно";
                refreshStatus();
                return;
            }
            CharSequence currentText = trackedNode.getText();
            if (currentText == null || !currentText.toString().equals(fullText)) {
                return;
            }

            checkTextCount++;
            requestGeneration++;
            statusWordsFound = tokenize(fullText).size();
            statusError = null;
            refreshStatus();

            Locale detectedLocale = detectLocale();
            if (spellCheckerSession == null || !detectedLocale.equals(currentSessionLocale)) {
                if (spellCheckerSession != null) {
                    spellCheckerSession.close();
                    spellCheckerSession = null;
                }
                ensureSpellCheckerSession(detectedLocale);
            }
            if (spellCheckerSession == null) {
                statusError = "Спелчекер-сессия недоступна (null)";
                refreshStatus();
                return;
            }

            String textToCheck = fullText.length() > MAX_CHECK_LENGTH
                    ? fullText.substring(0, MAX_CHECK_LENGTH) : fullText;
            lastCheckedText = textToCheck;

            TextInfo info = new TextInfo(textToCheck, requestGeneration, 0);
            getSuggestionsCallCount++;
            refreshStatus();
            spellCheckerSession.getSentenceSuggestions(new TextInfo[]{info}, MAX_SUGGESTIONS);
        } catch (Exception e) {
            statusError = "Ошибка checkText: " + e;
            refreshStatus();
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        // Больше не используется — проверяем целым предложением через onGetSentenceSuggestions.
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        onGetSuggestionsCount++;
        lastCallbackInfo = "results=" + (results == null ? "null" : ("length=" + results.length));
        refreshStatus();
        try {
            if (results == null || results.length == 0) {
                return;
            }
            SentenceSuggestionsInfo ssi = results[0];
            int spanCount = ssi.getSuggestionsCount();

            if (spanCount > 0) {
                SuggestionsInfo firstInfo = ssi.getSuggestionsInfoAt(0);
                if (firstInfo != null && firstInfo.getCookie() != requestGeneration) {
                    lastCallbackInfo += ", устарело";
                    refreshStatus();
                    return;
                }
            }

            List<Word> flagged = new ArrayList<>();
            List<String> names = new ArrayList<>();
            StringBuilder debug = new StringBuilder();

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
                debug.append(wordText).append("[a=").append(attrs)
                        .append(",n=").append(count).append("] ");

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
            statusDebug = debug.toString();
            statusError = null;
            refreshStatus();

            applyFlaggedWords(flagged);
        } catch (Exception e) {
            statusError = "Ошибка onGetSentenceSuggestions: " + e;
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
            statusError = "Ошибка applyFlaggedWords: " + e;
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

            int left = (int) word.rect.left - overlayOffsetX;
            int top = (int) word.rect.top - overlayOffsetY;
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
        params.x = (int) word.rect.left - overlayOffsetX;
        params.y = (int) word.rect.bottom - overlayOffsetY + 8;

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
            clearAll();
            return;
        }

        String updated = current.subSequence(0, word.start).toString()
                + replacement
                + current.subSequence(word.end, current.length()).toString();

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
        trackedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        clearAll();
    }

    private void clearAll() {
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

    private List<Word> tokenize(String text) {
        List<Word> words = new ArrayList<>();
        int len = Math.min(text.length(), MAX_CHECK_LENGTH);
        int i = 0;
        while (i < len) {
            if (Character.isLetter(text.charAt(i))) {
                int start = i;
                while (i < len && Character.isLetter(text.charAt(i))) {
                    i++;
                }
                Word w = new Word();
                w.start = start;
                w.end = i;
                w.text = text.substring(start, i);
                words.add(w);
            } else {
                i++;
            }
        }
        return words;
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
    public void onDestroy() {
        super.onDestroy();
        clearAll();
        if (trackedNode != null) {
            trackedNode.recycle();
            trackedNode = null;
        }
        if (windowManager != null) {
            if (underlineView != null) {
                safeRemoveView(underlineView);
            }
            if (statusView != null) {
                safeRemoveView(statusView);
            }
        }
        if (spellCheckerSession != null) {
            spellCheckerSession.close();
        }
    }
}
