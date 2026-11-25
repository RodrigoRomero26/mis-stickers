package com.rodri.stickermaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WhatsApp todavía no deja que apps externas creen stickers con sonido.
 * Esta pantalla deja listo un video corto (hasta 10 s, con el audio que quieras)
 * para cargarlo en "Crear sticker" de WhatsApp y dejarle el audio original.
 */
public class SoundPrepActivity extends Activity {
    private static final int REQ_VIDEO = 1;
    private static final int REQ_AUDIO = 2;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Uri video, audio;
    private int durationMs;
    private TextView videoTv, audioTv, startTv, lenTv, status;
    private SeekBar startSb, lenSb;
    private LinearLayout controls;
    private Button saveBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sticker con sonido");
        LinearLayout col = Ui.column(this);

        TextView intro = Ui.text(this,
                "WhatsApp creó los stickers con sonido, pero por ahora solo se hacen desde su propio editor "
                        + "(no desde apps externas). Acá preparás el video recortado y con el audio que quieras, "
                        + "y después lo cargás en WhatsApp en dos toques.", 14, false);
        col.addView(intro);

        col.addView(Ui.button(this, "🎬  Elegir video", v -> pickVideo()));
        videoTv = Ui.text(this, "Ningún video elegido", 14, false);
        col.addView(videoTv);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setVisibility(android.view.View.GONE);
        startTv = Ui.text(this, "", 14, false);
        startSb = new SeekBar(this);
        lenTv = Ui.text(this, "", 14, false);
        lenSb = new SeekBar(this);
        controls.addView(startTv);
        controls.addView(startSb);
        controls.addView(lenTv);
        controls.addView(lenSb);
        controls.addView(Ui.button(this, "🎵  Cambiar audio (opcional, .m4a)", v -> pickAudio()));
        audioTv = Ui.text(this, "Audio: el original del video", 14, false);
        audioTv.setOnClickListener(v -> {
            audio = null;
            audioTv.setText("Audio: el original del video");
        });
        controls.addView(audioTv);
        saveBtn = Ui.button(this, "💾  Guardar en la galería", v -> save());
        controls.addView(saveBtn);
        col.addView(controls, Ui.wide(this, 4));

        status = Ui.text(this, "", 14, false);
        col.addView(status);

        setContentView(Ui.scroll(this, col));
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
        startActivityForResult(i, REQ_VIDEO);
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        startActivityForResult(i, REQ_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_VIDEO) {
            video = data.getData();
            durationMs = readDuration(video);
            if (durationMs <= 0) {
                videoTv.setText("No pude leer ese video.");
                controls.setVisibility(android.view.View.GONE);
                return;
            }
            videoTv.setText("Video de " + Ui.secs(durationMs));
            int maxLen = Math.max(5, Math.min(AnimatedBuilder.MAX_MS, durationMs) / 100);
            lenSb.setMax(maxLen - 5);
            lenSb.setProgress(maxLen - 5);
            lenSb.setOnSeekBarChangeListener(Ui.onSeek(this::updateStartMax, null));
            startSb.setOnSeekBarChangeListener(Ui.onSeek(this::updateLabels, null));
            startSb.setProgress(0);
            updateStartMax();
            controls.setVisibility(android.view.View.VISIBLE);
            status.setText("");
        } else if (requestCode == REQ_AUDIO) {
            audio = data.getData();
            audioTv.setText("Audio: archivo elegido (tocá acá para volver al original)");
        }
    }

    private int lenMs() {
        return (lenSb.getProgress() + 5) * 100;
    }

    private void updateStartMax() {
        startSb.setMax(Math.max(0, (durationMs - lenMs()) / 100));
        updateLabels();
    }

    private void updateLabels() {
        startTv.setText("Empieza en: " + Ui.secs(startSb.getProgress() * 100));
        lenTv.setText("Duración: " + Ui.secs(lenMs()) + " (máx. 10 s)");
    }

    private int readDuration(Uri u) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, u);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : (int) Long.parseLong(d);
        } catch (RuntimeException e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void save() {
        saveBtn.setEnabled(false);
        status.setText("Guardando…");
        final Uri v = video, a = audio;
        final int start = startSb.getProgress() * 100, len = lenMs();
        exec.submit(() -> {
            try {
                VideoTrimmer.trim(this, v, start, len, a);
                main.post(this::showDone);
            } catch (Exception e) {
                main.post(() -> status.setText("Error: " + e.getMessage()));
            } finally {
                main.post(() -> saveBtn.setEnabled(true));
            }
        });
    }

    private void showDone() {
        status.setText("Listo: guardado en Películas/MisStickers.");
        new AlertDialog.Builder(this)
                .setTitle("Video listo")
                .setMessage("Ahora en WhatsApp:\n\n"
                        + "1. Abrí un chat y tocá el ícono de stickers.\n"
                        + "2. Tocá \"Crear\" y elegí este video (está en Películas/MisStickers).\n"
                        + "3. Tocá el botón de sonido y dejá el audio original.\n"
                        + "4. Enviá. Después lo podés guardar en favoritos.\n\n"
                        + "Si no ves el botón de sonido, tu WhatsApp todavía no tiene la función (se está habilitando de a poco).")
                .setPositiveButton("Abrir WhatsApp", (d, w) -> {
                    Intent i = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
                    if (i != null) startActivity(i);
                    else Ui.toast(this, "No encontré WhatsApp instalado.");
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }
}
