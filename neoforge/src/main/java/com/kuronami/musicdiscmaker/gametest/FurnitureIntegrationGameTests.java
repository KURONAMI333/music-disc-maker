package com.kuronami.musicdiscmaker.gametest;

//? if <1.21.2 {
/*import java.lang.reflect.Field;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/^*
 * Runs only in {@code runFurnitureGameTestServer}, with a real [Let's Do] Furniture jar in the mods folder.
 *
 * <p>Furniture is not a compile dependency, so its gramophone is reached through the block registry and its
 * fields through reflection. The test checks that {@code FurnitureGramophoneMixin} hooks the gramophone's tick
 * (an MDM disc that the gramophone plays is announced to clients) and that {@code FurnitureGramophoneBlockMixin}
 * stops it when the block is broken. Furniture 1.1.7 renamed its package, which once left both hooks unapplied
 * without any error, so the test is meant to be run against both an old and a new Furniture build.
 ^/
@GameTestHolder("mdm_furniture_integration")
public final class FurnitureIntegrationGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final long DURATION_MS = 120_000L;
    private static final String URL = "https://example.invalid/furniture-gramophone";

    private FurnitureIntegrationGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void gramophoneStreamsAndStopsAnMdmDisc(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("furniture"), "Furniture is not loaded");
        final Block gramophone = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("furniture", "gramophone"));
        helper.assertTrue(gramophone != Blocks.AIR, "Furniture has no furniture:gramophone block");
        helper.setBlock(POS, gramophone.defaultBlockState());
        final BlockPos abs = helper.absolutePos(POS);
        final BlockEntity be = helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(be != null && be.getClass().getSimpleName().equals("GramophoneBlockEntity"),
                "the gramophone has no GramophoneBlockEntity: " + be);
        MusicDiscMaker.LOGGER.info("Furniture gramophone under test: {}", be.getClass().getName());

        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(URL, "Gramophone", "tester", DURATION_MS, "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(DURATION_MS)), false));
        try {
            set(be, "recordItem", disc);
            set(be, "isPlaying", Boolean.TRUE);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Furniture changed the gramophone fields MDM reads", e);
        }
        final var dim = helper.getLevel().dimension();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    final ActiveDiscRegistry.Playing playing = ActiveDiscRegistry.current(dim, abs);
                    helper.assertTrue(playing != null && URL.equals(playing.track().url()),
                            "the gramophone tick hook did not stream the MDM disc (" + be.getClass().getName() + ")");
                })
                .thenExecute(() -> helper.setBlock(POS, Blocks.AIR))
                .thenExecute(() -> helper.assertTrue(ActiveDiscRegistry.current(dim, abs) == null,
                        "breaking the gramophone did not stop the MDM stream (" + gramophone.getClass().getName() + ")"))
                .thenSucceed();
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
*///?}
