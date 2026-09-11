package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.1 && <1.21.2 {
/*import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.menu.DiscDyeingTableMenu;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}
public final class DiscDyeingSyncGameTests {
    //? if >=1.21.1 && <1.21.2 {
    /*@GameTest(template = "empty8x3x8", batch = "discDyeingSync")
    public static void dyeChangesReachSynchronizerOnNextBroadcast(GameTestHelper helper) {
        final BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, ModBlocks.DISC_DYEING_TABLE.get());
        final var table = (DiscDyeingTableBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        final var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "dye-sync"));
        try {
            final CustomTrackData track = new CustomTrackData("https://example.invalid/dye-sync",
                    "Dye sync", "tester", 120_000L, "", false);
            final ItemStack input = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
            input.set(ModDataComponents.CUSTOM_TRACK.get(), track);
            input.set(ModDataComponents.DISC_DYE.get(), new DiscDyeData(DiscDye.BLUE, DiscDye.PINK));
            table.setItem(DiscDyeingTableBlockEntity.SLOT_INPUT, input);
            final var menu = new DiscDyeingTableMenu(63, player.getInventory(), table);
            final ItemStack[] delivered = { ItemStack.EMPTY };
            final int[] updates = { 0 };
            menu.setSynchronizer(new ContainerSynchronizer() {
                @Override
                public void sendInitialData(AbstractContainerMenu source, NonNullList<ItemStack> slots,
                        ItemStack carried, int[] data) { }
                @Override
                public void sendSlotChange(AbstractContainerMenu source, int slot, ItemStack stack) {
                    if (slot == DiscDyeingTableBlockEntity.SLOT_OUTPUT) {
                        delivered[0] = stack.copy();
                        updates[0]++;
                    }
                }
                @Override
                public void sendCarriedChange(AbstractContainerMenu source, ItemStack stack) { }
                @Override
                public void sendDataChange(AbstractContainerMenu source, int slot, int value) { }
            });
            // No ticks or scheduled waits between edits and their first broadcast.
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, new ItemStack(Items.MAGENTA_DYE));
            menu.broadcastChanges();
            assertDelivery(helper, delivered[0], track, new DiscDyeData(DiscDye.MAGENTA, DiscDye.PINK));
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, new ItemStack(Items.ORANGE_DYE));
            menu.broadcastChanges();
            assertDelivery(helper, delivered[0], track, new DiscDyeData(DiscDye.MAGENTA, DiscDye.ORANGE));
            table.removeItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, 1);
            menu.broadcastChanges();
            assertDelivery(helper, delivered[0], track, new DiscDyeData(DiscDye.BLUE, DiscDye.ORANGE));
            table.removeItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, 1);
            menu.broadcastChanges();
            assertDelivery(helper, delivered[0], track, new DiscDyeData(DiscDye.BLUE, DiscDye.PINK));
            helper.assertTrue(updates[0] == 4, "染料変更ごとの出力同期が4回ではない");
            helper.succeed();
        } finally {
            player.discard();
            helper.setBlock(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void assertDelivery(GameTestHelper helper, ItemStack result, CustomTrackData track, DiscDyeData dye) {
        helper.assertTrue(dye.equals(result.get(ModDataComponents.DISC_DYE.get())), "最初のbroadcastで最新色が通知されない");
        helper.assertTrue(track.equals(result.get(ModDataComponents.CUSTOM_TRACK.get())), "出力同期が曲情報を失った");
    }
    *///?}
}
