package com.tencent.qqnt.watch.gallery;

import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.tencent.mobileqq.activity.photo.LocalMediaInfo;

import kotlin.jvm.functions.Function1;

public abstract class GalleryGridAdapter extends ListAdapter<LocalMediaInfo, RecyclerView.ViewHolder> {
    /** grid cell size in pixels */
    public int a;
    /** LifecycleOwner */
    public LifecycleOwner b;
    /** onItemClick callback: (LocalMediaInfo) -> Unit */
    public Function1<LocalMediaInfo, ?> c;

    protected GalleryGridAdapter() {
        super((DiffUtil.ItemCallback<LocalMediaInfo>) null);
    }


}
