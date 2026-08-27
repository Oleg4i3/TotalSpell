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

import java.util.ArrayList;
import java.util.List;

/**
 * Реальный спелчекер-оверлей. Подчёркивает слова с ошибками в текстовом поле
 * любого приложения и по тапу на слово показывает варианты замены.
 */
public class ProbeAccessibilityService extends AccessibilityService
        implements SpellCheckerSession.SpellCheckerSessionListener {

    private static final long DEBOUNCE_MS = 500;
    private static final int MAX_CHECK_LENGTH = 400;
    private static final int MAX_SUGGESTIONS = 4;

    private WindowManager windowManager;
    private UnderlineView underlineView;
    private SpellCheckerSession spellCheckerSession;
    private Handler handler;

    private int overlayOffsetX;
    private int overlayOffsetY;

    private int requestGeneration = 0;
    private List<Word> pendingWords = new ArrayList<>();
    private final List<Word> misspelledWords = new ArrayList<>();
    private final List<View> touchTargets = new ArrayList<>();
    private View suggestionPopup;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextServicesManager tsm =
                (TextServicesManager) getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        spellCheckerSession = tsm.newSpellCheckerSession(null, null, this, true);

        underlineView = new UnderlineView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(underlineView, params);

        underlineView.post(() -> {
            int[] location = new int[2];
            underlineView.getLocationOnScreen(location);
            overlayOffsetX = location[0];
            overlayOffsetY = location[1];
            underlineView.setOffset(overlayOffsetX, overlayOffsetY);
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) {
            return;
        }
        CharSequence text = node.getText();
        node.recycle();

        if (handler == null) {
            return;
        }
        handler.removeCallbacksAndMessages(null);

        if (text == null || text.length() == 0) {
            clearAll();
            return;
        }

        final String snapshot = text.toString();
        handler.postDelayed(() -> checkText(snapshot), DEBOUNCE_MS);
    }

    private void checkText(String fullText) {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) {
            return;
        }
        CharSequence currentText = node.getText();
        node.recycle();
        if (currentText == null || !currentText.toString().equals(fullText)) {
            return;
        }

        requestGeneration++;
        pendingWords = tokenize(fullText);
        if (pendingWords.isEmpty()) {
            clearAll();
            return;
        }

        TextInfo[] infos = new TextInfo[pendingWords.size()];
        for (int i = 0; i < pendingWords.size(); i++) {
            infos[i] = new TextInfo(pendingWords.get(i).text, requestGeneration, i);
        }
        if (spellCheckerSession != null) {
            spellCheckerSession.getSuggestions(infos, MAX_SUGGESTIONS, false);
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        if (results == null || results.length == 0) {
            return;
        }
        if (results[0].getCookie() != requestGeneration) {
            return;
        }

        List<Word> flagged = new ArrayList<>();
        for (SuggestionsInfo info : results) {
            int index = info.getSequence();
            if (index < 0 || index >= pendingWords.size()) {
                continue;
            }
            boolean inDictionary = (info.getSuggestionsAttributes()
                    & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0;
            if (inDictionary || info.getSuggestionsCount() <= 0) {
                continue;
            }
            Word word = pendingWords.get(index);
            List<String> suggestions = new ArrayList<>();
            int count = Math.min(info.getSuggestionsCount(), MAX_SUGGESTIONS);
            for (int j = 0; j < count; j++) {
                suggestions.add(info.getSuggestionAt(j));
            }
            word.suggestions = suggestions;
            flagged.add(word);
        }

        applyFlaggedWords(flagged);
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        // Не используется — проверяем по отдельным словам через onGetSuggestions.
    }

    private void applyFlaggedWords(List<Word> flagged) {
        misspelledWords.clear();
        misspelledWords.addAll(flagged);

        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) {
            misspelledWords.clear();
            underlineView.setWords(new ArrayList<>());
            return;
        }

        for (Word word : misspelledWords) {
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
                    word.start);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                    word.end - word.start);
            node.refreshWithExtraData(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);
            Bundle extras = node.getExtras();
            Parcelable[] rects = extras.getParcelableArray(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
            word.rect = unionRect(rects);
        }
        node.recycle();

        removeSuggestionPopup();
        rebuildTouchTargets();
        underlineView.setWords(misspelledWords);
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
        if (word.suggestions == null || word.suggestions.isEmpty() || word.rect == null) {
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFFFFFFFF);
        layout.setPadding(12, 12, 12, 12);

        for (String suggestion : word.suggestions) {
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
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) {
            return;
        }
        CharSequence current = node.getText();
        boolean stillValid = current != null
                && word.end <= current.length()
                && current.subSequence(word.start, word.end).toString().equals(word.text);

        if (!stillValid) {
            node.recycle();
            clearAll();
            return;
        }

        String updated = current.subSequence(0, word.start).toString()
                + replacement
                + current.subSequence(word.end, current.length()).toString();

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();

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
            // Окно уже было удалено — ничего страшного.
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
        if (windowManager != null && underlineView != null) {
            safeRemoveView(underlineView);
        }
        if (spellCheckerSession != null) {
            spellCheckerSession.close();
        }
    }
}
