package com.rodri.stickermaker;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera el sticker animado: saca cuadros a 12 fps, los compone a 512x512 y prueba
 * combinaciones de fps/calidad hasta que el archivo entre en el límite de WhatsApp (500 KB).
 */
public final class AnimatedBuilder {
    public static final int LIMIT = 495 * 1000;
    public static final int MAX_MS = 10_000;
    private static final int BASE_FPS = 12;
    // {saltear cada N cuadros, calidad}
    private static final int[][] ATTEMPTS = {
            {1, 80}, {1, 65}, {1, 50}, {2, 65}, {2, 50}, {2, 35}, {3, 40}, {3, 28}, {4, 28}, {4, 18}, {6, 18}};

    public interface Progress {
        void onProgress(String msg);
    }

    private AnimatedBuilder() {}

    public static byte[] build(FrameSource src, int startMs, int lenMs, StickerRenderer.Options o,
                               File cacheDir, Progress p) throws IOException {
        lenMs = Math.max(500, Math.min(MAX_MS, lenMs));
        int n = Math.max(2, Math.min(lenMs * BASE_FPS / 1000, MAX_MS * BASE_FPS / 1000));
        File dir = new File(cacheDir, "frames");
        clean(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Sin espacio temporal");

        int bytes = StickerRenderer.SIZE * StickerRenderer.SIZE * 4;
        ByteBuffer buf = ByteBuffer.allocate(bytes);
        try {
            for (int i = 0; i < n; i++) {
                p.onProgress("Sacando cuadros " + (i + 1) + "/" + n);
                Bitmap f = src.frameAt(startMs + i * 1000 / BASE_FPS);
                Bitmap c = StickerRenderer.compose(f, o);
                f.recycle();
                buf.clear();
                c.copyPixelsToBuffer(buf);
                c.recycle();
                try (FileOutputStream out = new FileOutputStream(new File(dir, i + ".raw"))) {
                    out.write(buf.array(), 0, bytes);
                }
            }

            Bitmap work = Bitmap.createBitmap(StickerRenderer.SIZE, StickerRenderer.SIZE, Bitmap.Config.ARGB_8888);
            for (int a = 0; a < ATTEMPTS.length; a++) {
                int step = ATTEMPTS[a][0], q = ATTEMPTS[a][1];
                int frameMs = step * 1000 / BASE_FPS;
                List<byte[]> frames = new ArrayList<>();
                List<Integer> durs = new ArrayList<>();
                int total = 0;
                boolean tooBig = false;
                for (int i = 0; i < n; i += step) {
                    p.onProgress("Comprimiendo (intento " + (a + 1) + ") " + (i + 1) + "/" + n);
                    buf.clear();
                    try (FileInputStream in = new FileInputStream(new File(dir, i + ".raw"))) {
                        int off = 0, r;
                        while (off < bytes && (r = in.read(buf.array(), off, bytes - off)) > 0) off += r;
                    }
                    buf.rewind();
                    work.copyPixelsFromBuffer(buf);
                    byte[] w = StickerRenderer.webp(work, q);
                    total += w.length;
                    if (total > LIMIT) {
                        tooBig = true;
                        break;
                    }
                    frames.add(w);
                    durs.add(frameMs);
                }
                if (tooBig) continue;
                if (frames.size() == 1) { // WhatsApp necesita al menos 2 cuadros
                    frames.add(frames.get(0));
                    durs.set(0, Math.max(8, frameMs / 2));
                    durs.add(Math.max(8, frameMs / 2));
                }
                byte[] result = WebpAnim.encode(frames, durs, StickerRenderer.SIZE, StickerRenderer.SIZE);
                if (result.length <= LIMIT) return result;
            }
            throw new IOException("No entra en 500 KB. Probá con un clip más corto.");
        } finally {
            clean(dir);
        }
    }

    private static void clean(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
