package com.rodri.stickermaker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Recorta un video (sin recodificar) a un tramo de hasta 10 s y opcionalmente le cambia
 * el audio por otro archivo AAC/M4A. Lo guarda en la galería (Movies/MisStickers).
 */
public final class VideoTrimmer {
    private VideoTrimmer() {}

    public static Uri trim(Context c, Uri video, int startMs, int lenMs, Uri audio) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "sticker_sonido_" + System.currentTimeMillis() + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MisStickers");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri out = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (out == null) throw new IOException("No se pudo crear el archivo en la galería");

        boolean ok = false;
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = null;
        MediaMuxer mux = null;
        boolean started = false;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(out, "rw")) {
            if (pfd == null) throw new IOException("No se pudo escribir el archivo");
            mux = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            vEx.setDataSource(c, video, null);
            int vTrack = findTrack(vEx, "video/");
            if (vTrack < 0) throw new IOException("El archivo no tiene video");
            vEx.selectTrack(vTrack);
            MediaFormat vFormat = vEx.getTrackFormat(vTrack);
            int muxV = mux.addTrack(vFormat);

            int muxA = -1;
            if (audio != null) {
                aEx = new MediaExtractor();
                aEx.setDataSource(c, audio, null);
                int at = findTrack(aEx, "audio/");
                if (at < 0) throw new IOException("El archivo de audio no tiene sonido");
                MediaFormat af = aEx.getTrackFormat(at);
                if (!MediaFormat.MIMETYPE_AUDIO_AAC.equals(af.getString(MediaFormat.KEY_MIME))) {
                    throw new IOException("El audio tiene que ser AAC (.m4a). Si es MP3, elegilo directamente en el editor de WhatsApp.");
                }
                aEx.selectTrack(at);
                muxA = mux.addTrack(af);
            } else {
                aEx = new MediaExtractor();
                aEx.setDataSource(c, video, null);
                int at = findTrack(aEx, "audio/");
                if (at >= 0) {
                    aEx.selectTrack(at);
                    try {
                        muxA = mux.addTrack(aEx.getTrackFormat(at));
                    } catch (RuntimeException e) {
                        muxA = -1; // formato de audio no soportado: se exporta sin audio
                    }
                }
            }

            mux.setOrientationHint(rotation(c, video));
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            long lenUs = Math.min(lenMs, AnimatedBuilder.MAX_MS) * 1000L;
            vEx.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long base = Math.max(0, vEx.getSampleTime());
            copy(vEx, mux, muxV, buf, base, base + lenUs, base);

            if (muxA >= 0) {
                if (audio != null) {
                    aEx.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    copy(aEx, mux, muxA, buf, 0, lenUs, 0);
                } else {
                    aEx.seekTo(base, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    copy(aEx, mux, muxA, buf, base, base + lenUs, base);
                }
            }
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("No se pudo procesar el video: " + e.getMessage(), e);
        } finally {
            if (mux != null) {
                try {
                    if (started) mux.stop();
                } catch (RuntimeException e) {
                    ok = false;
                }
                mux.release();
            }
            vEx.release();
            if (aEx != null) aEx.release();
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(out, done, null, null);
            } else {
                cr.delete(out, null, null);
            }
        }
        if (!ok) throw new IOException("No se pudo guardar el video");
        return out;
    }

    private static void copy(MediaExtractor ex, MediaMuxer mux, int track, ByteBuffer buf,
                             long fromUs, long toUs, long baseUs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            long t = ex.getSampleTime();
            if (t >= toUs) break;
            if (t >= fromUs) {
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, t - baseUs, flags);
                mux.writeSampleData(track, buf, info);
            }
            if (!ex.advance()) break;
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int rotation(Context c, Uri video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, video);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return s == null ? 0 : Integer.parseInt(s);
        } catch (RuntimeException e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
