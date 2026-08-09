package com.tencent.watch.aio_impl.coreImpl.vb;

import androidx.recyclerview.widget.RecyclerView;
import org.jetbrains.annotations.NotNull;

/**
 * Compile-only stub for the anonymous RecyclerView.OnScrollListener installed in
 * WatchAIOListVB.onCreateView (the 7th one). Its onScrolled() shows the sliver input bar when the
 * list is at the bottom and collapses it to the up-arrow otherwise — i.e. the input-bar auto-hide.
 *
 * Field {@code a} is the synthetic captured outer WatchAIOListVB (its {@code J} field is the
 * InputBarController). Hooked by KeepInputBarOnScroll to optionally keep the bar shown while scrolling.
 */
public class WatchAIOListVB$onCreateView$7 extends RecyclerView.OnScrollListener {
    public final WatchAIOListVB a = null;

    @Override
    public void onScrolled(@NotNull RecyclerView recyclerView, int dx, int dy) {
    }
}
