package com.example.spellprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

    private static final long DEBOUNCE_MS = 500;
    private static final int MAX_CHECK_LENGTH = 400;
    private static final int MAX_SUGGESTIONS = 4;

    private WindowManager windowManager;
    private TextView statusView;
    private UnderlineView underlineView;
    private SpellCheckerSession spellCheckerSession;
    private Handler handler;

    private int overlayOffsetX;
    private int overlayOffsetY;

    private AccessibilityNodeInfo trackedNode;
    private String trackedPackage = "";

    private int requestGeneration = 0;
    private List<Word> pendingWords = new ArrayList<>();
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

        try {
            TextServicesManager tsm = (TextServicesManager)
                    getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            spellCheckerSession = tsm.newSpellCheckerSession(null, new Locale("ru"), this, true);
        } catch (Exception e) {
            spellCheckerSession = null;
            statusError = "Ошибка создания спелчекера: " + e;
        }

        if (spellCheckerSession == null && statusError == null) {
            statusError = "newSpellCheckerSession() вернул null. Проверьте Настройки "
                    + "\u2192 Система \u2192 Язык и ввод \u2192 Проверка правописания.";
        }

        refreshStatus();
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TotalSpell \u2014 приложение: ").append(statusApp).append('\n');
        sb.append("символов: ").append(statusTextLength)
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
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) {
            return;
        }

        CharSequence text = node.getText();
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "\u2014";

        if (text == null || text.length() == 0) {
            node.recycle();
            if (pkg.equals(trackedPackage)) {
                statusApp = pkg;
                statusTextLength = 0;
                statusWordsFound = 0;
                statusMisspelledCount = 0;
                statusMisspelledList = "";
                refreshStatus();
                clearAll();
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
            pendingWords = tokenize(fullText);
            statusWordsFound = pendingWords.size();
            statusError = null;
            refreshStatus();

            if (pendingWords.isEmpty()) {
                clearAll();
                return;
            }

            if (spellCheckerSession == null) {
                statusError = "Спелчекер-сессия недоступна (null)";
                refreshStatus();
                return;
            }

            TextInfo[] infos = new TextInfo[pendingWords.size()];
            for (int i = 0; i < pendingWords.size(); i++) {
                infos[i] = new TextInfo(pendingWords.get(i).text, requestGeneration, i);
            }
            getSuggestionsCallCount++;
            refreshStatus();
            spellCheckerSession.getSuggestions(infos, MAX_SUGGESTIONS, false);
        } catch (Exception e) {
            statusError = "Ошибка checkText: " + e;
            refreshStatus();
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        onGetSuggestionsCount++;
        lastCallbackInfo = "results=" + (results == null ? "null" : ("length=" + results.length));
        if (results != null && results.length > 0) {
            lastCallbackInfo += ", cookie=" + results[0].getCookie()
                    + " (ждали " + requestGeneration + ")";
        }
        refreshStatus();
        try {
            if (results == null || results.length == 0) {
                return;
            }
            if (results[0].getCookie() != requestGeneration) {
                return;
            }

            List<Word> flagged = new ArrayList<>();
            List<String> names = new ArrayList<>();
            StringBuilder debug = new StringBuilder();
            for (SuggestionsInfo info : results) {
                int index = info.getSequence();
                String label = (index >= 0 && index < pendingWords.size())
                        ? pendingWords.get(index).text : ("#" + index);
                int attrs = info.getSuggestionsAttributes();
                int count = info.getSuggestionsCount();
                debug.append(label).append("[a=").append(attrs)
                        .append(",n=").append(count).append("] ");

                if (index < 0 || index >= pendingWords.size()) {
                    continue;
                }
                boolean inDictionary = (attrs & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0;
                if (inDictionary) {
                    continue;
                }
                Word word = pendingWords.get(index);
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
            statusError = "Ошибка onGetSuggestions: " + e;
            refreshStatus();
        }
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
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
            paint.setColor(Color.RED);
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
                canvas.drawLine(left, y, right, y, paint);
            }
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
