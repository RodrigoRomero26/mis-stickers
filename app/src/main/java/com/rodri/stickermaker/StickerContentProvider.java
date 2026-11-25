package com.rodri.stickermaker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.List;

/** Expone los packs con el formato que espera WhatsApp (mismo contrato que su app de ejemplo). */
public class StickerContentProvider extends ContentProvider {
    private static final int METADATA = 1;
    private static final int METADATA_SINGLE = 2;
    private static final int STICKERS = 3;
    private static final int STICKER_ASSET = 4;

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    private String authority;

    public static String authority(android.content.Context c) {
        return c.getPackageName() + ".stickercontentprovider";
    }

    @Override
    public boolean onCreate() {
        authority = authority(getContext());
        matcher.addURI(authority, "metadata", METADATA);
        matcher.addURI(authority, "metadata/*", METADATA_SINGLE);
        matcher.addURI(authority, "stickers/*", STICKERS);
        matcher.addURI(authority, "stickers_asset/*/*", STICKER_ASSET);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        switch (matcher.match(uri)) {
            case METADATA:
                return packsCursor(uri, StickerStore.list(getContext()));
            case METADATA_SINGLE: {
                StickerStore.Pack p = StickerStore.load(getContext(), uri.getLastPathSegment());
                return packsCursor(uri, p == null ? Collections.<StickerStore.Pack>emptyList() : Collections.singletonList(p));
            }
            case STICKERS:
                return stickersCursor(uri, StickerStore.load(getContext(), uri.getLastPathSegment()));
            default:
                throw new IllegalArgumentException("URI desconocida: " + uri);
        }
    }

    private Cursor packsCursor(Uri uri, List<StickerStore.Pack> packs) {
        MatrixCursor c = new MatrixCursor(new String[]{
                "sticker_pack_identifier",
                "sticker_pack_name",
                "sticker_pack_publisher",
                "sticker_pack_icon",
                "android_play_store_link",
                "ios_app_download_link",
                "sticker_pack_publisher_email",
                "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website",
                "image_data_version",
                "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack"});
        for (StickerStore.Pack p : packs) {
            c.addRow(new Object[]{p.id, p.name, p.publisher, StickerStore.TRAY_FILE,
                    "", "", "", "", "", "",
                    String.valueOf(p.version), 0, p.animated ? 1 : 0});
        }
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    private Cursor stickersCursor(Uri uri, StickerStore.Pack p) {
        MatrixCursor c = new MatrixCursor(new String[]{
                "sticker_file_name", "sticker_emoji", "sticker_accessibility_text"});
        if (p != null) {
            for (StickerStore.Sticker s : p.stickers) {
                String label = TextUtils.isEmpty(s.label) ? "Sticker" : s.label;
                c.addRow(new Object[]{s.file, TextUtils.join(",", s.emojis), label});
            }
        }
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (matcher.match(uri) != STICKER_ASSET) throw new FileNotFoundException(uri.toString());
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 3) throw new FileNotFoundException(uri.toString());
        String id = seg.get(1);
        String name = seg.get(2);
        if (!StickerStore.isSafeName(id) || !StickerStore.isSafeName(name)) throw new FileNotFoundException(uri.toString());
        File f = new File(StickerStore.dir(getContext(), id), name);
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public String getType(Uri uri) {
        switch (matcher.match(uri)) {
            case METADATA:
                return "vnd.android.cursor.dir/vnd." + authority + ".metadata";
            case METADATA_SINGLE:
                return "vnd.android.cursor.item/vnd." + authority + ".metadata";
            case STICKERS:
                return "vnd.android.cursor.dir/vnd." + authority + ".stickers";
            case STICKER_ASSET:
                return uri.getLastPathSegment().endsWith(".png") ? "image/png" : "image/webp";
            default:
                throw new IllegalArgumentException("URI desconocida: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
