package com.rodri.stickermaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity {
    private LinearLayout listBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout col = Ui.column(this);

        col.addView(Ui.button(this, "➕  Nuevo pack de stickers estáticos", v -> newPack(false)));
        col.addView(Ui.button(this, "🎞️  Nuevo pack de stickers animados", v -> newPack(true)));
        col.addView(Ui.button(this, "🔊  Preparar sticker con sonido",
                v -> startActivity(new Intent(this, SoundPrepActivity.class))));

        col.addView(Ui.text(this, "Tus packs", 20, true));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(listBox, Ui.wide(this, 4));

        TextView hint = Ui.text(this,
                "Cada pack necesita entre 3 y 30 stickers. Un pack es todo estático o todo animado (regla de WhatsApp). "
                        + "Mantené apretado un pack para borrarlo.", 13, false);
        hint.setAlpha(0.7f);
        col.addView(hint);

        setContentView(Ui.scroll(this, col));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        listBox.removeAllViews();
        List<StickerStore.Pack> packs = StickerStore.list(this);
        if (packs.isEmpty()) {
            listBox.addView(Ui.text(this, "Todavía no creaste ningún pack.", 15, false));
            return;
        }
        for (StickerStore.Pack p : packs) {
            TextView row = Ui.text(this, p.name + "\n" + (p.animated ? "Animado" : "Estático")
                    + " · " + p.stickers.size() + " stickers", 16, false);
            int pad = Ui.dp(this, 14);
            row.setPadding(pad, pad, pad, pad);
            row.setBackground(Ui.rounded(this, 0xFFEFF3F1));
            row.setOnClickListener(v -> openPack(p.id));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("¿Borrar \"" + p.name + "\"?")
                        .setMessage("Si ya lo agregaste a WhatsApp, ahí va a dejar de actualizarse.")
                        .setPositiveButton("Borrar", (d, w) -> {
                            StickerStore.delete(this, p.id);
                            refresh();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            });
            listBox.addView(row);
        }
    }

    private void newPack(boolean animated) {
        EditText name = new EditText(this);
        name.setHint("Nombre del pack");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(name, Ui.wide(this, 0));
        new AlertDialog.Builder(this)
                .setTitle(animated ? "Pack animado" : "Pack estático")
                .setView(box)
                .setPositiveButton("Crear", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = animated ? "Mis animados" : "Mis stickers";
                    if (n.length() > 128) n = n.substring(0, 128);
                    try {
                        openPack(StickerStore.create(this, n, animated).id);
                    } catch (IOException e) {
                        Ui.toast(this, "Error: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openPack(String id) {
        startActivity(new Intent(this, PackActivity.class).putExtra(PackActivity.EXTRA_ID, id));
    }
}
