package com.kuronami.musicdiscmaker.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.kuronami.musicdiscmaker.compat.curios.CuriosEquipmentAccess;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.network.OpenEquippedBoomboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

//? if <26.3 {
import org.lwjgl.glfw.GLFW;
//?}

/** Curios 装備中のブームボックスを既存 GUI で開く client key。 */
public final class CuriosBoomboxKey {
    public static final String TRANSLATION_KEY = "key.music_disc_maker.open_equipped_boombox";

    // B は vanilla の既定 KeyMapping と重ならず、ブームボックスの頭文字でもある。
    //? if >=26.3 {
    private static final KeyMapping MAPPING = new KeyMapping(TRANSLATION_KEY,
            InputConstants.KEY_B, KeyMapping.Category.INVENTORY);
    //?} elif >=1.21.11 {
    private static final KeyMapping MAPPING = new KeyMapping(TRANSLATION_KEY,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyMapping.Category.INVENTORY);
    //?} else {
    /*private static final KeyMapping MAPPING = new KeyMapping(TRANSLATION_KEY,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.inventory");
    *///?}

    private CuriosBoomboxKey() {
    }

    public static KeyMapping mapping() {
        return MAPPING;
    }

    /** Curios API 非依存の導入判定。key 登録と tick の両方で使う。 */
    public static boolean isAvailable() {
        return Services.PLATFORM.isModLoaded("curios");
    }

    /** 現在の割り当て (再設定後を含む) を表示するための名前。 */
    public static Component displayName() {
        return MAPPING.getTranslatedKeyMessage();
    }

    /** client tick の終端で呼ぶ。押下 1 回につき要求 1 個だけを送る。 */
    public static void tick() {
        if (!isAvailable()) return;
        // 画面を開いている間 (URL 入力中の 'b' 含む) は発火させない。
        //? if >=26.2 {
        if (Minecraft.getInstance().gui.screen() != null) {
        //?} else {
        /*if (Minecraft.getInstance().screen != null) {
        *///?}
            while (MAPPING.consumeClick()) { /* discard */ }
            return;
        }
        while (MAPPING.consumeClick()) {
            final var player = Minecraft.getInstance().player;
            if (player == null) continue;
            final CuriosEquipmentAccess.Entry entry = CuriosEquipmentAccess.first(player);
            if (entry == null) continue;
            Services.NETWORK.sendToServer(new OpenEquippedBoomboxPayload(
                    entry.slotId(), entry.index(), BoomboxCarry.idOf(entry.stack())));
        }
    }
}
