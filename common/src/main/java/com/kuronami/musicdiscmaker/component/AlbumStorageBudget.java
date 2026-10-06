package com.kuronami.musicdiscmaker.component;

import java.util.List;

import io.netty.buffer.Unpooled;
//? if >=1.21 {
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.world.item.ItemStack;

/** Albumへ新たに収納する値の、実同期ItemStackに対する製品上の予算。 */
public final class AlbumStorageBudget {

    /**
     * 挿入を受け入れる ItemStack 同期値の上限。Album component 内部とForge NBT decoderの2 MiBより
     * 十分小さくし、container packetの外側と将来追加されるcomponentのための余白を残す。
     */
    public static final int INSERT_WIRE_BYTE_BUDGET = 1024 * 1024;

    /** 初回menu同期は既存データを開く経路なので、2 MiB packet枠のうち512 KiBをheader等に残す。 */
    public static final int INITIAL_MENU_WIRE_BYTE_BUDGET = 1536 * 1024;

    private AlbumStorageBudget() {
    }

    /** 拒否動作を変えず、容量と同期形式の失敗を分ける内部診断。 */
    public enum Reason { ACCEPTED, BYTE_BUDGET, ENTRY_LIMIT, ENCODE, DECODE, ROUNDTRIP_MISMATCH, TRAILING, INVALID_SOURCE }
    public enum Stage { ITEM, ALBUM, BOOMBOX_OUTER, BOOMBOX, CLICK_MENU, INITIAL_MENU }

    public record Result(Reason reason, Stage stage, int bytes, int budget) {
        public boolean fits() { return reason == Reason.ACCEPTED; }
        public boolean capacityExceeded() { return reason == Reason.BYTE_BUDGET || reason == Reason.ENTRY_LIMIT; }
        public Result at(Stage value) { return new Result(reason, value, bytes, budget); }
        /** 表示する拒否だけを記録する。曲・URL・component値・例外本文は記録しない。 */
        public String rejectionKey(String capacityKey) {
            com.kuronami.musicdiscmaker.MusicDiscMaker.LOGGER.warn(
                    "Disc storage rejected: reason={} stage={} bytes={} budget={}", reason, stage, bytes, budget);
            return capacityExceeded() ? capacityKey : "message.music_disc_maker.storage_sync_failed";
        }
    }

    public static Result entryLimit(Stage stage) { return new Result(Reason.ENTRY_LIMIT, stage, 0, INSERT_WIRE_BYTE_BUDGET); }
    public static Result invalidSource() { return new Result(Reason.INVALID_SOURCE, Stage.INITIAL_MENU, 0, INITIAL_MENU_WIRE_BYTE_BUDGET); }

    // Nettyの書込み容量検査と数値が一致した場合だけ容量超過とする。
    // codec自身の添字例外・読取り不足は容量超過と断定しない。
    private static final java.util.regex.Pattern CAPACITY_ERROR = java.util.regex.Pattern.compile(
            "^writerIndex\\((\\d+)\\) \\+ minWritableBytes\\((\\d+)\\) exceeds maxCapacity\\((\\d+)\\)");

    private static boolean exceededWireBudget(RuntimeException failure, io.netty.buffer.ByteBuf wire) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (!(current instanceof IndexOutOfBoundsException) || current.getMessage() == null) continue;
            final java.util.regex.Matcher match = CAPACITY_ERROR.matcher(current.getMessage());
            if (!match.find()) continue;
            try {
                final long writer = Long.parseLong(match.group(1));
                final long wanted = Long.parseLong(match.group(2));
                final long maximum = Long.parseLong(match.group(3));
                if (writer == wire.writerIndex() && maximum == wire.maxCapacity()
                        && wanted > maximum - writer) return true;
            } catch (NumberFormatException ignored) { /* 判定不能はcodec失敗として扱う。 */ }
        }
        return false;
    }

    //? if >=1.21 {
    /** 既存boolean APIを保持する。 */
    public static boolean fits(ItemStack stack, RegistryAccess registryAccess) { return inspect(stack, registryAccess).fits(); }
    public static Result inspect(ItemStack stack, RegistryAccess registryAccess) {
        return inspectValues(List.of(stack), ItemStack.EMPTY, registryAccess, INSERT_WIRE_BYTE_BUDGET, Stage.ITEM, false);
    }

    /** changedSlotsとcursorのみ。無関係な所持品を予算へ追加しない。 */
    public static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return inspectMenuSync(slots, carried, registryAccess).fits();
    }
    public static Result inspectMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return inspectValues(slots, carried, registryAccess, INSERT_WIRE_BYTE_BUDGET, Stage.CLICK_MENU, true);
    }
    public static boolean fitsInitialMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return inspectInitialMenuSync(slots, carried, registryAccess).fits();
    }
    public static Result inspectInitialMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return inspectValues(slots, carried, registryAccess, INITIAL_MENU_WIRE_BYTE_BUDGET, Stage.INITIAL_MENU, true);
    }

    private static Result inspectValues(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess,
            int budget, Stage stage, boolean menu) {
        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(256, budget), registryAccess);
        try {
            try {
                for (final ItemStack stack : slots) ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, stack);
                if (menu) {
                    final ItemStack stack = carried;
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, stack);
                }
            } catch (RuntimeException rejected) {
                return new Result(exceededWireBudget(rejected, wire) ? Reason.BYTE_BUDGET : Reason.ENCODE,
                        stage, wire.writerIndex(), budget);
            }
            try {
                for (final ItemStack stack : slots) {
                    final ItemStack restored = ItemStack.OPTIONAL_STREAM_CODEC.decode(wire);
                    // 単体の既存判定は残余bytesを先に確認する。
                    if (!menu && wire.readableBytes() != 0) return new Result(Reason.TRAILING, stage, wire.writerIndex(), budget);
                    if (!ItemStack.matches(stack, restored))
                        return new Result(Reason.ROUNDTRIP_MISMATCH, stage, wire.writerIndex(), budget);
                }
                if (menu) {
                    final ItemStack restored = ItemStack.OPTIONAL_STREAM_CODEC.decode(wire);
                    if (!ItemStack.matches(carried, restored)) return new Result(Reason.ROUNDTRIP_MISMATCH, stage, wire.writerIndex(), budget);
                }
                return new Result(wire.readableBytes() == 0 ? Reason.ACCEPTED : Reason.TRAILING,
                        stage, wire.writerIndex(), budget);
            } catch (RuntimeException rejected) {
                return new Result(Reason.DECODE, stage, wire.writerIndex(), budget);
            }
        } finally { wire.release(); }
    }
    //?} else {
    /*/^** 既存boolean APIを保持する。 ^/
    public static boolean fits(ItemStack stack) { return inspect(stack).fits(); }
    public static Result inspect(ItemStack stack) {
        return inspectValues(List.of(stack), ItemStack.EMPTY, INSERT_WIRE_BYTE_BUDGET, Stage.ITEM, false);
    }

    /^** changedSlotsとcursorのみ。無関係な所持品を予算へ追加しない。 ^/
    public static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried) {
        return inspectMenuSync(slots, carried).fits();
    }
    public static Result inspectMenuSync(List<ItemStack> slots, ItemStack carried) {
        return inspectValues(slots, carried, INSERT_WIRE_BYTE_BUDGET, Stage.CLICK_MENU, true);
    }
    public static boolean fitsInitialMenuSync(List<ItemStack> slots, ItemStack carried) {
        return inspectInitialMenuSync(slots, carried).fits();
    }
    public static Result inspectInitialMenuSync(List<ItemStack> slots, ItemStack carried) {
        return inspectValues(slots, carried, INITIAL_MENU_WIRE_BYTE_BUDGET, Stage.INITIAL_MENU, true);
    }

    private static Result inspectValues(List<ItemStack> slots, ItemStack carried,
            int budget, Stage stage, boolean menu) {
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer(256, budget));
        try {
            try {
                for (final ItemStack stack : slots) wire.writeItem(stack);
                if (menu) {
                    final ItemStack stack = carried;
                    wire.writeItem(stack);
                }
            } catch (RuntimeException rejected) {
                return new Result(exceededWireBudget(rejected, wire) ? Reason.BYTE_BUDGET : Reason.ENCODE,
                        stage, wire.writerIndex(), budget);
            }
            try {
                for (final ItemStack stack : slots) {
                    final ItemStack restored = wire.readItem();
                    // Forgeの旧menu判定はdecode成功のみ。新しい拒否を増やさない。
                    
                    if (!menu && !ItemStack.matches(stack, restored))
                        return new Result(Reason.ROUNDTRIP_MISMATCH, stage, wire.writerIndex(), budget);
                }
                if (menu) {
                    final ItemStack restored = wire.readItem();
                    // 従来どおり復号可能性だけを検査する。
                }
                return new Result(wire.readableBytes() == 0 ? Reason.ACCEPTED : Reason.TRAILING,
                        stage, wire.writerIndex(), budget);
            } catch (RuntimeException rejected) {
                return new Result(Reason.DECODE, stage, wire.writerIndex(), budget);
            }
        } finally { wire.release(); }
    }
    *///?}
}
