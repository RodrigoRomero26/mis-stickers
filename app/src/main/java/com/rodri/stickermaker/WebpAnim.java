package com.rodri.stickermaker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Arma un WebP animado (contenedor VP8X + ANIM + ANMF) a partir de cuadros
 * que ya vienen codificados como WebP individuales (por ejemplo, con Bitmap.compress).
 * Android no trae un codificador de WebP animado, así que se ensambla a mano.
 * Clase en Java puro (sin APIs de Android) para poder probarla fuera del teléfono.
 */
public final class WebpAnim {
    private WebpAnim() {}

    public static byte[] encode(List<byte[]> frames, List<Integer> durationsMs, int width, int height) {
        if (frames.isEmpty()) throw new IllegalArgumentException("Sin cuadros");
        if (frames.size() != durationsMs.size()) throw new IllegalArgumentException("Duraciones inválidas");

        boolean anyAlpha = false;
        ByteArrayOutputStream anmfChunks = new ByteArrayOutputStream();

        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (f.length < 12 || !tag(f, 0).equals("RIFF") || !tag(f, 8).equals("WEBP")) {
                throw new IllegalArgumentException("El cuadro " + i + " no es WebP");
            }
            byte[] alph = null;
            byte[] img = null;
            String imgTag = null;
            int p = 12;
            while (p + 8 <= f.length) {
                String t = tag(f, p);
                int size = le32(f, p + 4);
                int start = p + 8;
                if (size < 0 || start + size > f.length) throw new IllegalArgumentException("WebP corrupto");
                if (t.equals("ALPH")) {
                    alph = Arrays.copyOfRange(f, start, start + size);
                } else if (t.equals("VP8 ") || t.equals("VP8L")) {
                    img = Arrays.copyOfRange(f, start, start + size);
                    imgTag = t;
                }
                p = start + size + (size & 1);
            }
            if (img == null) throw new IllegalArgumentException("El cuadro " + i + " no tiene imagen");

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            le24(payload, 0);              // X / 2
            le24(payload, 0);              // Y / 2
            le24(payload, width - 1);
            le24(payload, height - 1);
            le24(payload, Math.max(8, durationsMs.get(i)));
            payload.write(0x02);           // sin mezcla (cada cuadro reemplaza al anterior), sin descarte
            if (alph != null && !"VP8L".equals(imgTag)) {
                anyAlpha = true;
                writeChunk(payload, "ALPH", alph);
            }
            if ("VP8L".equals(imgTag)) anyAlpha = true;
            writeChunk(payload, imgTag, img);
            writeChunk(anmfChunks, "ANMF", payload.toByteArray());
        }

        ByteArrayOutputStream vp8x = new ByteArrayOutputStream();
        vp8x.write(0x02 | (anyAlpha ? 0x10 : 0)); // animación (+ alfa)
        vp8x.write(0);
        vp8x.write(0);
        vp8x.write(0);
        le24(vp8x, width - 1);
        le24(vp8x, height - 1);

        ByteArrayOutputStream anim = new ByteArrayOutputStream();
        le32(anim, 0x00000000); // color de fondo: transparente
        anim.write(0);          // repetir infinito
        anim.write(0);

        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write('W');
        content.write('E');
        content.write('B');
        content.write('P');
        writeChunk(content, "VP8X", vp8x.toByteArray());
        writeChunk(content, "ANIM", anim.toByteArray());
        byte[] a = anmfChunks.toByteArray();
        content.write(a, 0, a.length);

        byte[] c = content.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(c.length + 8);
        out.write('R');
        out.write('I');
        out.write('F');
        out.write('F');
        le32(out, c.length);
        out.write(c, 0, c.length);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String tag, byte[] data) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        le32(out, data.length);
        out.write(data, 0, data.length);
        if ((data.length & 1) == 1) out.write(0);
    }

    private static String tag(byte[] b, int off) {
        return new String(b, off, 4, StandardCharsets.US_ASCII);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    private static void le24(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        le24(out, v);
        out.write((v >> 24) & 0xff);
    }
}
