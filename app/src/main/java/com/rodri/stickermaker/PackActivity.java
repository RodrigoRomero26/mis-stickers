package com.rodri.stickermaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

public class PackActivity extends Activity {
    public static final String EXTRA_ID = "pack_id";
    private static final int REQ_PICK = 1;
    private static final int REQ_WHATSAPP = 2;

    private String packId;
    private StickerStore.Pack pack;
    private TextView info;
    private LinearLayout grid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        packId = getIntent().getStringExtra(EXTRA_ID);
        LinearLayout col = Ui.column(this);
        info = Ui.text(this, "", 15, false);
        col.addView(info);
        col.addView(Ui.button(this, "➕  Agregar sticker", v -> pick()));
        col.addView(Ui.button(this, "📲  Agregar a WhatsApp", v -> addToWhatsApp()));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid, Ui.wide(this, 12));
        TextView hint = Ui.text(this, "Mantené apretado un sticker para borrarlo. "
                + "Si agregás o borrás stickers, volvé a tocar \"Agregar a WhatsApp\" para actualizarlo.", 13, false);
        hint.setAlpha(0.7f);
        col.addView(hint);
        setContentView(Ui.scroll(this, col));
    }

    @Override
    protected void onResume() {
        super.onResume();
        pack = StickerStore.load(this, packId);
        if (pack == null) {
            finish();
            return;
        }
        setTitle(pack.name);
        render();
    }

    private void render() {
        info.setText((pack.animated ? "Pack animado" : "Pack estático") + " · "
                + pack.stickers.size() + "/" + StickerStore.MAX_STICKERS + " stickers"
                + (pack.stickers.size() < StickerStore.MIN_STICKERS
                ? " (faltan " + (StickerStore.MIN_STICKERS - pack.stickers.size()) + " para poder agregarlo)" : ""));
        grid.removeAllViews();
        LinearLayout row = null;
        int size = Ui.dp(this, 104);
        for (int i = 0; i < pack.stickers.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.wide(this, 6));
            }
            StickerStore.Sticker s = pack.stickers.get(i);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(Ui.rounded(this, 0xFFEFF3F1));
            int pad = Ui.dp(this, 6);
            iv.setPadding(pad, pad, pad, pad);
            try {
                Drawable d = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(new File(StickerStore.dir(this, packId), s.file)));
                iv.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
            } catch (IOException ignored) {
            }
            iv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("¿Borrar este sticker?")
                        .setPositiveButton("Borrar", (dlg, w) -> {
                            try {
                                StickerStore.removeSticker(this, packId, s.file);
                            } catch (IOException e) {
                                Ui.toast(this, "Error: " + e.getMessage());
                            }
                            onResume();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, size, 1f);
            lp.setMargins(pad / 2, 0, pad / 2, 0);
            row.addView(iv, lp);
        }
        if (row != null) {
            for (int k = pack.stickers.size() % 3; k != 0 && k < 3; k++) {
                row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, size, 1f));
            }
        }
    }

    private void pick() {
        if (pack.stickers.size() >= StickerStore.MAX_STICKERS) {
            Ui.toast(this, "El pack ya tiene 30 stickers. Creá otro pack.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (pack.animated) {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/gif"});
        } else {
            i.setType("image/*");
        }
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Intent e = new Intent(this, EditorActivity.class)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EditorActivity.EXTRA_PACK, packId)
                    .putExtra(EditorActivity.EXTRA_MIME, getContentResolver().getType(uri));
            startActivity(e);
        } else if (requestCode == REQ_WHATSAPP && resultCode == RESULT_CANCELED && data != null) {
            String err = data.getStringExtra("validation_error");
            if (err != null) {
                new AlertDialog.Builder(this).setTitle("WhatsApp rechazó el pack")
                        .setMessage(err).setPositiveButton("OK", null).show();
            }
        }
    }

    private void addToWhatsApp() {
        if (pack.stickers.size() < StickerStore.MIN_STICKERS) {
            Ui.toast(this, "Necesitás al menos 3 stickers en el pack.");
            return;
        }
        String target = null;
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            if (installed(pkg)) {
                target = pkg;
                break;
            }
        }
        if (target == null) {
            Ui.toast(this, "No encontré WhatsApp instalado.");
            return;
        }
        Intent i = new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        i.putExtra("sticker_pack_id", pack.id);
        i.putExtra("sticker_pack_authority", StickerContentProvider.authority(this));
        i.putExtra("sticker_pack_name", pack.name);
        i.setPackage(target);
        try {
            startActivityForResult(i, REQ_WHATSAPP);
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, "Tu versión de WhatsApp no aceptó el pack. Actualizá WhatsApp.");
        }
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
