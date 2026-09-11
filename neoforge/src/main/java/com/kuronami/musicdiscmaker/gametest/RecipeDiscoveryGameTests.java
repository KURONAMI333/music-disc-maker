package com.kuronami.musicdiscmaker.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
//? if <1.21.2 {
/*@net.neoforged.neoforge.gametest.GameTestHolder("music_disc_maker")
*///?}
public final class RecipeDiscoveryGameTests {
    //? if <1.21.2 {
    /*@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
    @net.minecraft.gametest.framework.GameTest(template = "empty8x3x8")
    *///?}
    public static void newBlocksHaveLoadedRecipeUnlocks(GameTestHelper helper) {
        for (String name : java.util.List.of("boombox", "disc_dyeing_table", "disc_pedestal", "speaker", "album")) {
            //? if >=1.21.2 {
            final var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("music_disc_maker", "recipes/misc/" + name);
            //?} else {
            /*final var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("music_disc_maker", "recipes/misc/" + name);
            *///?}
            final var holder = helper.getLevel().getServer().getAdvancements().get(id);
            helper.assertTrue(holder != null, "Recipe unlock failed to load: " + name);
            final var advancement = holder.value();
            helper.assertTrue(advancement.criteria().keySet().containsAll(java.util.List.of("has_material", "has_the_recipe")),
                    "Missing material/recipe criterion: " + name);
            //? if >=26.2 {
            final var trigger = (net.minecraft.advancements.triggers.InventoryChangeTrigger.TriggerInstance)
                    advancement.criteria().get("has_material").triggerInstance();
            //?} elif >=1.21.2 {
            /*final var trigger = (net.minecraft.advancements.criterion.InventoryChangeTrigger.TriggerInstance)
                    advancement.criteria().get("has_material").triggerInstance();
            *///?} else {
            /*final var trigger = (net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance)
                    advancement.criteria().get("has_material").triggerInstance();
            *///?}
            final var material = switch (name) {
                case "boombox" -> net.minecraft.world.item.Items.JUKEBOX;
                case "speaker" -> net.minecraft.world.item.Items.NOTE_BLOCK;
                case "album" -> net.minecraft.world.item.Items.LEATHER;
                case "disc_pedestal" -> net.minecraft.world.item.Items.GOLD_INGOT;
                default -> net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                        .filter(item -> item instanceof net.minecraft.world.item.DyeItem).findFirst().orElseThrow();
            };
            helper.assertTrue(trigger.items().size() == 1 && trigger.items().get(0).test(new net.minecraft.world.item.ItemStack(material)),
                    "Material does not unlock recipe: " + name);
            helper.assertTrue(!trigger.items().get(0).test(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)),
                    "Unrelated stone unlocks recipe: " + name);
            if (name.equals("disc_dyeing_table")) {
                for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                    if (item instanceof net.minecraft.world.item.DyeItem) {
                        helper.assertTrue(trigger.items().get(0).test(new net.minecraft.world.item.ItemStack(item)),
                                "Dye variant cannot unlock table: " + item);
                    }
                }
            }
            helper.assertTrue(advancement.rewards().recipes().size() == 1
                            && advancement.rewards().recipes().get(0).toString().contains("music_disc_maker:" + name),
                    "Wrong recipe unlock reward: " + name);
        }
        helper.succeed();
    }
}
