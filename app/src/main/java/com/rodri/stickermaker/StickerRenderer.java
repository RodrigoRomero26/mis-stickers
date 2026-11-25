package com.rodri.stickermaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Compone el sticker 512x512 (encuadre + texto) y lo codifica. */
public final class StickerRenderer {
    public static final int SIZE = 512;
    public static final int STATIC_LIMIT = 100 * 1000;   // WhatsApp: estáticos hasta 100 KB
    public static final int TRAY_LIMIT = 50 * 1000;     // ícono hasta 50 KB

    private StickerRenderer() {}

    public static final class Options {
        public boolean fill;      // true = recortar para llenar, false = ajustar con bordes transparentes
        public String text = "";
        public boolean textTop;
    }

    public static Bitmap decode(Context c, Uri uri, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int max = Math.max(w, h);
                    if (max > maxSide) {
                        float s = maxSide / (float) max;
                        decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                    }
                });
    }

    public static Bitmap compose(Bitmap src, Options o) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float w = src.getWidth(), h = src.getHeight();
        float s = o.fill ? Math.max(SIZE / w, SIZE / h) : Math.min(SIZE / w, SIZE / h);
        float dw = w * s, dh = h * s;
        RectF dst = new RectF((SIZE - dw) / 2f, (SIZE - dh) / 2f, (SIZE + dw) / 2f, (SIZE + dh) / 2f);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        if (o.text != null && !o.text.trim().isEmpty()) drawCaption(c, o.text.trim(), o.textTop);
        return out;
    }

    private static void drawCaption(Canvas c, String text, boolean top) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setColor(0xFFFFFFFF);
        fill.setTextAlign(Paint.Align.CENTER);
        float maxW = SIZE - 32;
        float size = 72;
        List<String> lines;
        while (true) {
            fill.setTextSize(size);
            lines = wrap(text, fill, maxW);
            boolean fits = lines.size() <= 3;
            for (String l : lines) if (fill.measureText(l) > maxW) fits = false;
            if (fits || size <= 22) break;
            size -= 4;
        }
        Paint stroke = new Paint(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(Math.max(4, size / 7f));
        stroke.setColor(0xFF000000);

        Paint.FontMetrics fm = fill.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float blockH = lineH * lines.size();
        float y = top ? 16 - fm.ascent : SIZE - 16 - blockH - fm.ascent;
        for (String l : lines) {
            c.drawText(l, SIZE / 2f, y, stroke);
            c.drawText(l, SIZE / 2f, y, fill);
            y += lineH;
        }
    }

    private static List<String> wrap(String text, Paint p, float maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (p.measureText(candidate) <= maxW || cur.length() == 0) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    @SuppressWarnings("deprecation")
    public static byte[] webp(Bitmap b, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        b.compress(fmt, Math.min(quality, 95), out);
        return out.toByteArray();
    }

    public static byte[] encodeStatic(Bitmap b) throws IOException {
        for (int q = 90; q >= 10; q -= 10) {
            byte[] data = webp(b, q);
            if (data.length <= STATIC_LIMIT) return data;
        }
        throw new IOException("No se pudo dejar el sticker por debajo de 100 KB");
    }

    public static byte[] trayPng(Bitmap b) throws IOException {
        Bitmap small = Bitmap.createScaledBitmap(b, 96, 96, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.PNG, 100, out);
        if (out.size() > TRAY_LIMIT) throw new IOException("Ícono demasiado pesado");
        return out.toByteArray();
    }
}
