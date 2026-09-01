package com.spellswitch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView alphaLabel;
    private TextView heightLabel;
    private LinearLayout orderContainer;
    private int density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 24 * density;
        root.setPadding(pad, pad * 2, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, pad);
        root.addView(statusView);

        Button openAccessibilityBtn = new Button(this);
        openAccessibilityBtn.setText("Открыть Специальные возможности");
        openAccessibilityBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(openAccessibilityBtn);

        addSpacer(root, pad);
        addAdbInstructionsSection(root, pad);

        addSpacer(root, pad);
        addAlphaSection(root, pad);

        addSpacer(root, pad);
        addHeightSection(root, pad);

        addSpacer(root, pad);
        addFlagCheckbox(root, pad);

        addSpacer(root, pad);
        addAutoSpellCheckSection(root, pad);

        addSpacer(root, pad);
        addOrderSection(root, pad);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void addSpacer(LinearLayout parent, int height) {
        View spacer = new View(this);
        parent.addView(spacer, LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private void addAdbInstructionsSection(LinearLayout parent, int pad) {
        TextView title = new TextView(this);
        title.setText("Как выдать разрешение (один раз, через ADB на компьютере)");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, pad / 2);
        parent.addView(title);

        String instructions =
                "1. На телефоне: Настройки → О телефоне → 7 раз тапнуть по номеру сборки "
                        + "(появится \"Вы стали разработчиком\") → Настройки → Для разработчиков "
                        + "→ включить \"Отладка по USB\".\n\n"
                        + "2. На компьютере: скачать SDK Platform-Tools "
                        + "(developer.android.com/tools/releases/platform-tools) и распаковать "
                        + "архив в любую папку — устанавливать ничего не нужно, там просто adb.exe.\n\n"
                        + "3. Подключить телефон к компьютеру USB-кабелем с передачей данных. "
                        + "На экране телефона появится запрос \"Разрешить отладку по USB с этого "
                        + "компьютера?\" — подтвердить.\n\n"
                        + "4. Открыть командную строку в папке с adb и проверить связь:\n"
                        + "    adb devices\n"
                        + "Должно появиться устройство со статусом \"device\" "
                        + "(если \"unauthorized\" — попап на телефоне ещё не подтверждён).\n\n"
                        + "5. Выполнить команду ниже — кнопка скопирует её в буфер обмена:";

        TextView body = new TextView(this);
        body.setText(instructions);
        body.setTextIsSelectable(true);
        body.setPadding(0, 0, 0, pad / 2);
        parent.addView(body);

        String command = "adb shell pm grant com.spellswitch"
                + " android.permission.WRITE_SECURE_SETTINGS";

        TextView commandView = new TextView(this);
        commandView.setText(command);
        commandView.setTypeface(Typeface.MONOSPACE);
        commandView.setTextIsSelectable(true);
        commandView.setBackgroundColor(Color.parseColor("#EEEEEE"));
        commandView.setPadding(16 * density, 16 * density, 16 * density, 16 * density);
        parent.addView(commandView);

        Button copyBtn = new Button(this);
        copyBtn.setText("Скопировать команду");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("adb command", command));
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
            }
        });
        parent.addView(copyBtn);

        TextView note = new TextView(this);
        note.setText("После выполнения команды вернитесь на этот экран — статус выше "
                + "должен смениться на \"выдано\" (может понадобиться заново открыть приложение).");
        note.setPadding(0, pad / 2, 0, 0);
        parent.addView(note);
    }

    private void addAlphaSection(LinearLayout parent, int pad) {
        SharedPreferences prefs = getSharedPreferences(
                OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE);
        int currentAlpha = prefs.getInt(OverlayAccessibilityService.KEY_ALPHA_PERCENT, 80);

        alphaLabel = new TextView(this);
        alphaLabel.setText("Прозрачность ярлыка: " + currentAlpha + "%");
        parent.addView(alphaLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(currentAlpha);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int clamped = Math.max(15, progress); // ниже — ярлык станет не видно и не потрогать
                alphaLabel.setText("Прозрачность ярлыка: " + clamped + "%");
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(OverlayAccessibilityService.KEY_ALPHA_PERCENT, clamped)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        parent.addView(seekBar);
    }

    private void addHeightSection(LinearLayout parent, int pad) {
        SharedPreferences prefs = getSharedPreferences(
                OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE);
        int currentHeight = prefs.getInt(OverlayAccessibilityService.KEY_HEIGHT_DP, 48);

        heightLabel = new TextView(this);
        heightLabel.setText("Высота ярлыка: " + currentHeight + " dp");
        parent.addView(heightLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(150);
        seekBar.setProgress(currentHeight);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int clamped = Math.max(24, progress); // меньше — по ярлыку не попасть пальцем
                heightLabel.setText("Высота ярлыка: " + clamped + " dp");
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(OverlayAccessibilityService.KEY_HEIGHT_DP, clamped)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        parent.addView(seekBar);
    }

    private void addFlagCheckbox(LinearLayout parent, int pad) {
        SharedPreferences prefs = getSharedPreferences(
                OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE);
        boolean showFlag = prefs.getBoolean(OverlayAccessibilityService.KEY_SHOW_FLAG, false);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("Показывать флаг вместо букв");
        checkBox.setChecked(showFlag);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(OverlayAccessibilityService.KEY_SHOW_FLAG, isChecked)
                        .apply());
        parent.addView(checkBox);
    }

    private void addAutoSpellCheckSection(LinearLayout parent, int pad) {
        TextView title = new TextView(this);
        title.setText("Автоматическая проверка орфографии");
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, pad / 2);
        parent.addView(title);

        SharedPreferences prefs = getSharedPreferences(
                OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(
                OverlayAccessibilityService.KEY_AUTO_SPELLCHECK_ENABLED, false);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("Проверять орфографию везде (подчёркивание и подсказки)");
        checkBox.setChecked(enabled);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(OverlayAccessibilityService.KEY_AUTO_SPELLCHECK_ENABLED, isChecked)
                        .apply());
        parent.addView(checkBox);

        boolean autoDetect = prefs.getBoolean(
                OverlayAccessibilityService.KEY_AUTO_DETECT_LANGUAGE, true);
        CheckBox autoDetectBox = new CheckBox(this);
        autoDetectBox.setText("Автоматически определять язык по тексту");
        autoDetectBox.setChecked(autoDetect);
        autoDetectBox.setPadding(24 * density, 0, 0, 0);
        autoDetectBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(OverlayAccessibilityService.KEY_AUTO_DETECT_LANGUAGE, isChecked)
                        .apply());
        parent.addView(autoDetectBox);

        TextView autoDetectNote = new TextView(this);
        autoDetectNote.setText("Выключено — язык проверки не переключается автоматически, "
                + "используется только тот, что выбран ярлыком/меню вручную.");
        autoDetectNote.setPadding(24 * density, 0, 0, pad / 2);
        parent.addView(autoDetectNote);

        boolean debugStatus = prefs.getBoolean(
                OverlayAccessibilityService.KEY_DEBUG_STATUS_ENABLED, false);
        CheckBox debugBox = new CheckBox(this);
        debugBox.setText("Показывать отладочную строку сверху экрана");
        debugBox.setChecked(debugStatus);
        debugBox.setPadding(24 * density, 0, 0, 0);
        debugBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(OverlayAccessibilityService.KEY_DEBUG_STATUS_ENABLED, isChecked)
                        .apply());
        parent.addView(debugBox);

        TextView note = new TextView(this);
        note.setText("Определяет язык по печатаемому тексту и сама переключает язык "
                + "проверки — тот же самый, что и ярлык выше, поэтому они не конфликтуют. "
                + "Слово с ошибкой подчёркивается фиолетовой волнистой линией, тап по нему "
                + "показывает варианты замены.");
        note.setPadding(0, pad / 2, 0, 0);
        parent.addView(note);
    }

    private void addOrderSection(LinearLayout parent, int pad) {
        TextView title = new TextView(this);
        title.setText("Порядок переключения (перетащите, чтобы изменить):");
        title.setPadding(0, 0, 0, pad / 2);
        parent.addView(title);

        orderContainer = new LinearLayout(this);
        orderContainer.setOrientation(LinearLayout.VERTICAL);
        parent.addView(orderContainer);

        refreshOrderRows();
    }

    private void refreshOrderRows() {
        orderContainer.removeAllViews();
        String[] order = SpellCheckerSwitcher.getOrder(this);

        for (String code : order) {
            TextView row = new TextView(this);
            row.setText("\u2630  " + SpellCheckerSwitcher.menuNameFor(code)
                    + "  (" + SpellCheckerSwitcher.labelFor(code) + ")");
            row.setTag(code);
            row.setTextSize(16);
            row.setPadding(24 * density, 20 * density, 24 * density, 20 * density);
            row.setBackgroundColor(Color.parseColor("#EEEEEE"));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4 * density, 0, 4 * density);
            row.setLayoutParams(lp);

            row.setOnLongClickListener(v -> {
                ClipData dragData = ClipData.newPlainText("", "");
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                v.startDragAndDrop(dragData, shadow, v, 0);
                v.setVisibility(View.INVISIBLE);
                return true;
            });

            row.setOnDragListener((targetView, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;

                    case DragEvent.ACTION_DRAG_ENTERED:
                        targetView.setBackgroundColor(Color.parseColor("#BBDEFB"));
                        return true;

                    case DragEvent.ACTION_DRAG_EXITED:
                        targetView.setBackgroundColor(Color.parseColor("#EEEEEE"));
                        return true;

                    case DragEvent.ACTION_DROP: {
                        View sourceView = (View) event.getLocalState();
                        String sourceCode = (String) sourceView.getTag();
                        String targetCode = (String) targetView.getTag();
                        swapInOrder(sourceCode, targetCode);
                        refreshOrderRows();
                        return true;
                    }

                    case DragEvent.ACTION_DRAG_ENDED:
                        targetView.setVisibility(View.VISIBLE);
                        targetView.setBackgroundColor(Color.parseColor("#EEEEEE"));
                        return true;

                    default:
                        return false;
                }
            });

            orderContainer.addView(row);
        }
    }

    private void swapInOrder(String sourceCode, String targetCode) {
        String[] order = SpellCheckerSwitcher.getOrder(this);
        int srcIdx = indexOf(order, sourceCode);
        int tgtIdx = indexOf(order, targetCode);
        if (srcIdx < 0 || tgtIdx < 0 || srcIdx == tgtIdx) return;

        String tmp = order[srcIdx];
        order[srcIdx] = order[tgtIdx];
        order[tgtIdx] = tmp;
        SpellCheckerSwitcher.setOrder(this, order);
    }

    private int indexOf(String[] arr, String val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(val)) return i;
        }
        return -1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean accessibilityOn = isAccessibilityServiceEnabled();
        boolean permissionGranted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;

        StringBuilder sb = new StringBuilder();
        sb.append("Оверлей (Спец. возможности): ")
                .append(accessibilityOn ? "включён" : "ВЫКЛЮЧЕН — включите вручную")
                .append("\n\n");
        sb.append("Разрешение WRITE_SECURE_SETTINGS: ")
                .append(permissionGranted ? "выдано" : "НЕ выдано — нужен adb grant");
        statusView.setText(sb.toString());
    }

    private boolean isAccessibilityServiceEnabled() {
        String expected = getPackageName() + "/" + OverlayAccessibilityService.class.getName();
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        for (String s : enabledServices.split(":")) {
            if (s.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
