package com.tencent.watch.aio_impl.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Compile-only stub for QQ's per-message "sending" indicator (an ImageView). The real class shows an
 * APNG spinner (R.drawable.common_loading6) while a message is sending. {@code a()} starts that
 * spinner; QQPro's {@code AIOItemSendViewM3} @Mixin overrides it to draw an M3 arc instead.
 *
 * Created via {@code new AIOItemSendView(ctx, null, 0, 6)} at runtime, so the 4-arg constructor must
 * exist. {@code a()} is declared non-final so the Kotlin @Mixin subclass can override it; the bytecode
 * body is replaced by the patcher regardless of this stub's body.
 */
public class AIOItemSendView extends ImageView {
    public AIOItemSendView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Show the sending spinner (called for send status == 1). */
    public void a() {
    }
}
