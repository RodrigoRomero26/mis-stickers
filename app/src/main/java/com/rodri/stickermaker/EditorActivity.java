package com.rodri.stickermaker;

import android.app.Activity;
import android.graphics.Bitmap;
import android.icu.text.BreakIterator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Edita un sticker: encuadre, texto, emoji y (si es animado) tramo del video/GIF. */
public class EditorActivity extends Activity {
    public static final String EXTRA_PACK = "pack";
    public static final String EXTRA_MIME = "mime";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable previewRunnable = this::requestPreview;

    private StickerStore.Pack pack;
    private Uri uri;
    private String mime;
    private boolean animated;

    // Solo se usan desde el hilo de fondo
    private FrameSource source;
    private Bitmap staticSource;

    private int durationMs;
    private volatile int previewToken;
    private boolean busy;

    private ImageView preview;
    private RadioButton fillRb;
    private EditText textEt, emojiEt;
    private CheckBox topCb;
    private SeekBar startSb, lenSb;
    private TextView startTv, lenTv, status;
    private LinearLayout timeBox;
    private Button saveBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pack = StickerStore.load(this, getIntent().getStringExtra(EXTRA_PACK));
        uri = getIntent().getData();
        mime = getIntent().getStringExtra(EXTRA_MIME);
        if (pack == null || uri == null) {
            finish();
            return;
        }
        animated = pack.animated;
        setTitle(animated ? "Sticker animado" : "Sticker");
        buildUi();
        status.setText("Cargando…");
        saveBtn.setEnabled(false);
        exec.submit(this::load);
    }

    private void buildUi() {
        LinearLayout col = Ui.column(this);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.rounded(this, 0xFFDDE3E0));
        int side = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32), Ui.dp(this, 320));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(side, side);
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        col.addView(preview, plp);

        status = Ui.text(this, "", 14, false);
        col.addView(status);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton fitRb = new RadioButton(this);
        fitRb.setText("Ajustar entero");
        fitRb.setId(1);
        fillRb = new RadioButton(this);
        fillRb.setText("Recortar al cuadrado");
        fillRb.setId(2);
        rg.addView(fitRb);
        rg.addView(fillRb);
        rg.check(1);
        rg.setOnCheckedChangeListener((g, id) -> schedulePreview());
        col.addView(rg, Ui.wide(this, 8));

        timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        startTv = Ui.text(this, "", 14, false);
        startSb = new SeekBar(this);
        lenTv = Ui.text(this, "", 14, false);
        lenSb = new SeekBar(this);
        timeBox.addView(startTv);
        timeBox.addView(startSb);
        timeBox.addView(lenTv);
        timeBox.addView(lenSb);
        timeBox.setVisibility(animated ? android.view.View.VISIBLE : android.view.View.GONE);
        col.addView(timeBox, Ui.wide(this, 4));

        textEt = new EditText(this);
        textEt.setHint("Texto (opcional)");
        textEt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                schedulePreview();
            }
        });
        col.addView(textEt, Ui.wide(this, 8));

        topCb = new CheckBox(this);
        topCb.setText("Texto arriba");
        topCb.setOnCheckedChangeListener((b, c) -> schedulePreview());
        col.addView(topCb);

        emojiEt = new EditText(this);
        emojiEt.setHint("Emoji(s) para buscarlo en WhatsApp, ej: 😂🔥");
        emojiEt.setText("😀");
        col.addView(emojiEt, Ui.wide(this, 8));

        saveBtn = Ui.button(this, "💾  Guardar sticker", v -> save());
        col.addView(saveBtn);

        setContentView(Ui.scroll(this, col));
    }

    private void load() {
        try {
            if (animated) {
                source = FrameSource.open(this, uri, mime);
                durationMs = source.durationMs();
                main.post(this::setupTime);
            } else {
                staticSource = StickerRenderer.decode(this, uri, 1024);
                main.post(() -> {
                    status.setText("");
                    saveBtn.setEnabled(true);
                    requestPreview();
                });
            }
        } catch (Exception e) {
            main.post(() -> status.setText("No se pudo abrir el archivo: " + e.getMessage()));
        }
    }

    private void setupTime() {
        int maxLenTenths = Math.max(5, Math.min(AnimatedBuilder.MAX_MS, durationMs) / 100);
        lenSb.setMax(maxLenTenths - 5);
        lenSb.setProgress(Math.min(maxLenTenths, 40) - 5); // 4 s por defecto
        updateStartMax();
        startSb.setOnSeekBarChangeListener(Ui.onSeek(this::updateLabels, this::requestPreview));
        lenSb.setOnSeekBarChangeListener(Ui.onSeek(this::updateStartMax, null));
        status.setText("Duración del archivo: " + Ui.secs(durationMs));
        saveBtn.setEnabled(true);
        requestPreview();
    }

    private int lenMs() {
        return (lenSb.getProgress() + 5) * 100;
    }

    private int startMs() {
        return startSb.getProgress() * 100;
    }

    private void updateStartMax() {
        startSb.setMax(Math.max(0, (durationMs - lenMs()) / 100));
        updateLabels();
    }

    private void updateLabels() {
        startTv.setText("Empieza en: " + Ui.secs(startMs()));
        lenTv.setText("Duración del sticker: " + Ui.secs(lenMs()) + " (máx. 10 s)");
    }

    private StickerRenderer.Options options() {
        StickerRenderer.Options o = new StickerRenderer.Options();
        o.fill = fillRb.isChecked();
        o.text = textEt.getText().toString();
        o.textTop = topCb.isChecked();
        return o;
    }

    private void schedulePreview() {
        main.removeCallbacks(previewRunnable);
        main.postDelayed(previewRunnable, 350);
    }

    private void requestPreview() {
        if (busy || (animated ? durationMs == 0 : false)) return;
        final int token = ++previewToken;
        final StickerRenderer.Options o = options();
        final int start = animated ? startMs() : 0;
        exec.submit(() -> {
            try {
                Bitmap base = animated ? source.frameAt(start) : staticSource;
                if (base == null) return;
                Bitmap c = StickerRenderer.compose(base, o);
                if (animated) base.recycle();
                if (token == previewToken) main.post(() -> preview.setImageBitmap(c));
            } catch (Exception e) {
                main.post(() -> status.setText("Error en la vista previa: " + e.getMessage()));
            }
        });
    }

    private void save() {
        if (busy) return;
        busy = true;
        saveBtn.setEnabled(false);
        final StickerRenderer.Options o = options();
        final List<String> emojis = parseEmojis(emojiEt.getText().toString());
        final int start = animated ? startMs() : 0;
        final int len = animated ? lenMs() : 0;
        status.setText("Generando…");
        exec.submit(() -> {
            try {
                byte[] data;
                if (animated) {
                    data = AnimatedBuilder.build(source, start, len, o, getCacheDir(),
                            msg -> main.post(() -> status.setText(msg)));
                } else {
                    data = StickerRenderer.encodeStatic(StickerRenderer.compose(staticSource, o));
                }
                StickerStore.addSticker(this, pack.id, data, emojis, o.text.trim());
                main.post(() -> {
                    Ui.toast(this, "Sticker guardado (" + (data.length / 1000) + " KB)");
                    finish();
                });
            } catch (Exception e) {
                main.post(() -> {
                    busy = false;
                    saveBtn.setEnabled(true);
                    status.setText("No se pudo guardar: " + e.getMessage());
                });
            }
        });
    }

    static List<String> parseEmojis(String s) {
        List<String> out = new ArrayList<>();
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String g = s.substring(start, end).trim();
            if (g.isEmpty() || g.equals(",")) continue;
            out.add(g);
            if (out.size() == 3) break;
        }
        if (out.isEmpty()) out.add("😀");
        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        exec.submit(() -> {
            if (source != null) {
                try {
                    source.close();
                } catch (Exception ignored) {
                }
            }
        });
        exec.shutdown();
    }
}
