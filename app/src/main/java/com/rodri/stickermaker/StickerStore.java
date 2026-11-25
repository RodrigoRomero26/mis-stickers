package com.rodri.stickermaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Guarda los packs en la memoria interna: files/packs/<id>/pack.json + archivos de stickers. */
public final class StickerStore {
    public static final int MIN_STICKERS = 3;
    public static final int MAX_STICKERS = 30;
    public static final String TRAY_FILE = "tray.png";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]+");

    private StickerStore() {}

    public static final class Sticker {
        public String file;
        public List<String> emojis = new ArrayList<>();
        public String label = "";
    }

    public static final class Pack {
        public String id;
        public String name;
        public String publisher = "Mis Stickers";
        public boolean animated;
        public int version = 1;
        public List<Sticker> stickers = new ArrayList<>();
    }

    public static boolean isSafeName(String s) {
        return s != null && SAFE.matcher(s).matches() && !s.equals(".") && !s.equals("..");
    }

    private static File root(Context c) {
        return new File(c.getFilesDir(), "packs");
    }

    public static File dir(Context c, String id) {
        if (!isSafeName(id)) throw new IllegalArgumentException("id inválido");
        return new File(root(c), id);
    }

    public static synchronized List<Pack> list(Context c) {
        List<Pack> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            Pack p = load(c, d.getName());
            if (p != null) out.add(p);
        }
        Collections.sort(out, (a, b) -> b.id.compareTo(a.id));
        return out;
    }

    public static synchronized Pack load(Context c, String id) {
        if (!isSafeName(id)) return null;
        File f = new File(dir(c, id), "pack.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Pack p = new Pack();
            p.id = id;
            p.name = o.getString("name");
            p.publisher = o.optString("publisher", p.publisher);
            p.animated = o.getBoolean("animated");
            p.version = o.optInt("version", 1);
            JSONArray arr = o.getJSONArray("stickers");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject so = arr.getJSONObject(i);
                Sticker s = new Sticker();
                s.file = so.getString("file");
                s.label = so.optString("label", "");
                JSONArray em = so.getJSONArray("emojis");
                for (int j = 0; j < em.length(); j++) s.emojis.add(em.getString(j));
                p.stickers.add(s);
            }
            return p;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public static synchronized void save(Context c, Pack p) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("name", p.name);
            o.put("publisher", p.publisher);
            o.put("animated", p.animated);
            o.put("version", p.version);
            JSONArray arr = new JSONArray();
            for (Sticker s : p.stickers) {
                JSONObject so = new JSONObject();
                so.put("file", s.file);
                so.put("label", s.label);
                JSONArray em = new JSONArray();
                for (String e : s.emojis) em.put(e);
                so.put("emojis", em);
                arr.put(so);
            }
            o.put("stickers", arr);
            File d = dir(c, p.id);
            if (!d.exists() && !d.mkdirs()) throw new IOException("No se pudo crear la carpeta");
            writeFile(new File(d, "pack.json"), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public static synchronized Pack create(Context c, String name, boolean animated) throws IOException {
        Pack p = new Pack();
        p.id = "pack_" + System.currentTimeMillis();
        p.name = name;
        p.animated = animated;
        save(c, p);
        return p;
    }

    public static synchronized void delete(Context c, String id) {
        File d = dir(c, id);
        File[] files = d.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    public static synchronized void addSticker(Context c, String packId, byte[] webp, List<String> emojis, String label)
            throws IOException {
        Pack p = load(c, packId);
        if (p == null) throw new IOException("El pack no existe");
        if (p.stickers.size() >= MAX_STICKERS) throw new IOException("El pack ya tiene " + MAX_STICKERS + " stickers");
        Sticker s = new Sticker();
        s.file = "s" + System.currentTimeMillis() + ".webp";
        s.emojis.addAll(emojis);
        s.label = label == null ? "" : label;
        writeFile(new File(dir(c, packId), s.file), webp);
        p.stickers.add(s);
        p.version++;
        save(c, p);
        updateTray(c, p);
    }

    public static synchronized void removeSticker(Context c, String packId, String file) throws IOException {
        Pack p = load(c, packId);
        if (p == null) return;
        for (int i = 0; i < p.stickers.size(); i++) {
            if (p.stickers.get(i).file.equals(file)) {
                p.stickers.remove(i);
                //noinspection ResultOfMethodCallIgnored
                new File(dir(c, packId), file).delete();
                break;
            }
        }
        p.version++;
        save(c, p);
        updateTray(c, p);
    }

    /** El ícono del pack (96x96 PNG) sale del primer sticker. */
    private static void updateTray(Context c, Pack p) throws IOException {
        File tray = new File(dir(c, p.id), TRAY_FILE);
        if (p.stickers.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            tray.delete();
            return;
        }
        File first = new File(dir(c, p.id), p.stickers.get(0).file);
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(first),
                (decoder, info, src) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        writeFile(tray, StickerRenderer.trayPng(b));
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar " + f.getName());
    }
}
