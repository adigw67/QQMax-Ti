package com.tencent.qqnt.watch.chat.list;

import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem;
import org.jetbrains.annotations.NotNull;

/**
 * Compile-only stub for the anonymous {@link WatchRecentItemBuilder.Companion.OnItemClickListener}
 * created in {@link WatchRecentItemBuilder}'s {@code listener} field. {@code a()} is the long-press
 * handler: it pops the native SelectDialogFragment whose rows are 删除 / 置顶 / 免打扰. Field {@code a}
 * is the synthetic captured outer builder (its {@code d} field is the current chat WatchFragment).
 *
 * Given a no-arg constructor (the real class takes the outer builder) so a @Mixin can extend it.
 * Hooked by {@code ChatListLongClickMenu} to append a 清空消息 row to that menu.
 */
public class WatchRecentItemBuilder$listener$1
        implements WatchRecentItemBuilder.Companion.OnItemClickListener {
    public WatchRecentItemBuilder a = null;

    @Override
    public void a(@NotNull RecentContactChatItem recentContactChatItem) {
    }

    @Override
    public void b(@NotNull RecentContactChatItem recentContactChatItem) {
    }
}
