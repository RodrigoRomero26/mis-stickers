package com.rodri.stickermaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Entrega cuadros de un video o un GIF en un momento dado. No es thread-safe. */
public abstract class FrameSource implements Closeable {
    public abstract int durationMs();

    public abstract Bitmap frameAt(int ms) throws IOException;

    public static FrameSource open(Context c, Uri uri, String mime) throws IOException {
        if (mime != null && mime.startsWith("video/")) return new VideoSource(c, uri);
        byte[] data = readAll(c, uri, 60 * 1024 * 1024);
        boolean isGif = data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
        if (isGif) return new GifSource(data);
        return new VideoSource(c, uri);
    }

    static byte[] readAll(Context c, Uri uri, int max) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No se pudo abrir el archivo");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("Archivo demasiado grande");
            }
            return out.toByteArray();
        }
    }

    static final class VideoSource extends FrameSource {
        private final MediaMetadataRetriever r = new MediaMetadataRetriever();
        private final int duration;

        VideoSource(Context c, Uri uri) throws IOException {
            try {
                r.setDataSource(c, uri);
            } catch (RuntimeException e) {
                throw new IOException("No se pudo leer el video");
            }
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration = d == null ? 0 : (int) Long.parseLong(d);
            if (duration <= 0) throw new IOException("El archivo no parece un video válido");
        }

        @Override
        public int durationMs() {
            return duration;
        }

        @Override
        public Bitmap frameAt(int ms) throws IOException {
            long us = Math.max(0, Math.min(ms, duration - 1)) * 1000L;
            Bitmap b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 1024, 1024);
            if (b == null) b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024);
            if (b == null) throw new IOException("No se pudo sacar el cuadro del video");
            return b;
        }

        @Override
        public void close() {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    static final class GifSource extends FrameSource {
        private final Movie movie;
        private final int duration;
        private final float scale;

        GifSource(byte[] data) throws IOException {
            movie = Movie.decodeByteArray(data, 0, data.length);
            if (movie == null || movie.width() <= 0) throw new IOException("GIF inválido");
            duration = movie.duration() > 0 ? movie.duration() : 1000;
            int max = Math.max(movie.width(), movie.height());
            scale = max > 1024 ? 1024f / max : 1f;
        }

        @Override
        public int durationMs() {
            return duration;
        }

        @Override
        public Bitmap frameAt(int ms) {
            int w = Math.max(1, Math.round(movie.width() * scale));
            int h = Math.max(1, Math.round(movie.height() * scale));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(scale, scale);
            movie.setTime(movie.duration() > 0 ? ms % movie.duration() : 0);
            movie.draw(c, 0, 0);
            return b;
        }

        @Override
        public void close() {
        }
    }
}
