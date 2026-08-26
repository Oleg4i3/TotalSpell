package com.example.spellprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Диагностический сервис. Не подчёркивает ошибки и не проверяет орфографию.
 * Единственная задача: для текущего сфокусированного текстового поля в ЛЮБОМ
 * приложении попытаться получить координаты отдельных символов через
 * refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, ...) и показать
 * результат в виде текста на постоянной полупрозрачной полосе сверху экрана.
 */
public class ProbeAccessibilityService extends AccessibilityService {

    private WindowManager windowManager;
    private TextView overlayView;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new TextView(this);
        overlayView.setBackgroundColor(0xCC000000);
        overlayView.setTextColor(0xFFFFFFFF);
        overlayView.setTextSize(12);
        overlayView.setPadding(20, 20, 20, 20);
        overlayView.setText("Spellcheck Probe: нажмите в текстовое поле в любом приложении…");

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;

        windowManager.addView(overlayView, params);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (overlayView == null) {
            return;
        }

        AccessibilityNodeInfo node = event.getSource();
        if (node == null) {
            return;
        }

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            node.recycle();
            return;
        }

        int length = Math.min(text.length(), 60);

        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
        node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);

        Bundle extras = node.getExtras();
        Parcelable[] rects = extras.getParcelableArray(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);

        int withCoords = 0;
        RectF firstRect = null;
        if (rects != null) {
            for (Parcelable p : rects) {
                if (p instanceof RectF) {
                    withCoords++;
                    if (firstRect == null) {
                        firstRect = (RectF) p;
                    }
                }
            }
        }

        String appName = event.getPackageName() != null
                ? event.getPackageName().toString() : "неизвестно";

        StringBuilder message = new StringBuilder();
        message.append("Приложение: ").append(appName).append('\n');
        message.append("Длина текста в поле: ").append(text.length()).append('\n');

        if (rects == null) {
            message.append("Координаты символов: НЕ ПОДДЕРЖИВАЮТСЯ (null)");
        } else {
            message.append("Координаты символов: ").append(withCoords)
                    .append(" из ").append(length).append(" запрошенных");
            if (firstRect != null) {
                message.append("\nПервый символ на экране: (")
                        .append((int) firstRect.left).append(", ")
                        .append((int) firstRect.top).append(')');
            }
        }

        overlayView.setText(message.toString());
        node.recycle();
    }

    @Override
    public void onInterrupt() {
        // Не требуется для диагностики.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }
}
