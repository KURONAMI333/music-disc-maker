package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.compat.curios.CuriosEquipmentAccess;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Forge 1.20.1 での Curios 専用 slot の境界を検証する。実 Curios jar は GameTest 実行時に無い
 * (compileOnly) ので、{@link CuriosEquipmentAccess#install} へ fake provider を入れ、
 * common 側の menu 解決・交換無効化・走査列挙が legacy 帯でも同じ契約を持つことを見る。
 */
@net.minecraftforge.gametest.GameTestHolder("music_disc_maker")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class CuriosBoomboxLegacyGameTests {
    private CuriosBoomboxLegacyGameTests() { }

    /** 専用 slot の実物が menu の機体として解決され、参照交換で無効化される。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void equippedSourceResolvesAndInvalidatesOnExchange(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "curios-fixture"));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        CuriosEquipmentAccess.install(p -> slot.get());
        try {
            final BoomboxSource source = BoomboxSource.equipped("boombox", 0, BoomboxCarry.idOf(box));
            final BoomboxMenu menu = new BoomboxMenu(301, player.getInventory(), source);
            helper.assertTrue(menu.currentStack() == box && menu.stillValid(player),
                    "legacy で装備 source の実機体を解決できない");

            final ItemStack other = new ItemStack(ModItems.BOOMBOX.get());
            slot.set(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, other)));
            helper.assertTrue(!menu.stillValid(player) && menu.currentStack().isEmpty(),
                    "legacy で slot 交換後も menu が有効のままだった");

            slot.set(List.of());
            helper.assertTrue(!BoomboxCarry.carried(player).contains(box),
                    "slot が空になった後も機体が carried に残った");
        } finally {
            CuriosEquipmentAccess.install(null);
            player.discard();
        }
        helper.succeed();
    }

    /** 未採番機体の expectedId=UNASSIGNED は現在の id と一致して解決される。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void unassignedEquippedIdResolves(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "curios-fixture"));
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get()); // 未採番 (UNASSIGNED)
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        CuriosEquipmentAccess.install(p -> slot.get());
        try {
            final BoomboxSource source = BoomboxSource.equipped(
                    "boombox", 0, BoomboxContents.UNASSIGNED);
            final BoomboxMenu menu = new BoomboxMenu(303, player.getInventory(), source);
            helper.assertTrue(menu.currentStack() == box && menu.stillValid(player),
                    "legacy で未採番機体の装備 source が解決されない");
        } finally {
            CuriosEquipmentAccess.install(null);
            player.discard();
        }
        helper.succeed();
    }
}
