package com.tencent.qqnt.watch.gallery;

import android.database.Cursor;
import android.net.Uri;

import com.tencent.mobileqq.activity.photo.LocalMediaInfo;

import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/**
 * Compile-time stub for QQ's gallery repository. The real implementation lives
 * in the target APK; this only exposes the members the {@code GallerySortOrder}
 * Mixin hook needs to override {@link #b}.
 */
public class GalleryRepository {
    /** BASE_COLUMNS: _id, _data, mime_type, date_added, date_modified, _size, width, height */
    public static final String[] a = new String[0];
    /** offsetImage */
    public int e;
    /** offsetVideo */
    public int f;

    /** Loads one page of media for {@code uri}; returns the LocalMediaInfo list. */
    public List<LocalMediaInfo> b(
        Uri uri,
        String[] extraColumns,
        int offset,
        Function2<LocalMediaInfo, Cursor, Unit> mediaInfoHandler
    ) {
        return null;
    }
}
