package com.kuronami.musicdiscmaker.item;

import java.util.Optional;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;

import net.minecraft.core.BlockPos;
//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
//?} else {
/*import net.minecraft.nbt.Tag;
*/
//?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Golden Jukebox を sneaking で選び、次に置くスピーカーへ同次元リンクを渡す BlockItem。
 */
public class SpeakerItem extends BlockItem {

    private static final String LINK_TAG = "music_disc_maker_speaker_link";
    private static final String DIMENSION = "dimension";
    private static final String POSITION = "position";
    private static final String SOURCE_ID = "source_id";

    public SpeakerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return linkOf(stack).isPresent() || super.isFoil(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Player player = context.getPlayer();
        final Level level = context.getLevel();
        final BlockPos clicked = context.getClickedPos();
        if (player == null || !player.isSecondaryUseActive()) {
            return super.useOn(context);
        }
        if (level.getBlockEntity(clicked) instanceof GoldenJukeboxBlockEntity golden) {
            //? if >=1.21.2 {
            if (!level.isClientSide()) {
            //?} else {
            /*if (!level.isClientSide) {
            *///?}
                //? if >=1.21.2 {
                writeLink(context.getItemInHand(), new SpeakerLink(level.dimension().identifier().toString(), clicked.asLong(), golden.sourceIdentity()));
                //?} else {
                /*writeLink(context.getItemInHand(), new SpeakerLink(level.dimension().location().toString(), clicked.asLong(), golden.sourceIdentity()));
                *///?}
            }
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(clicked) instanceof SpeakerBlockEntity speaker) {
            //? if >=1.21.2 {
            if (!level.isClientSide()) {
            //?} else {
            /*if (!level.isClientSide) {
            *///?}
                speaker.clearLink();
                notifyPlayer(player, "message.music_disc_maker.speaker.unlinked");
            }
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        final Optional<SpeakerLink> pendingLink = linkOf(context.getItemInHand());
        if (pendingLink.filter(link -> !isSameDimension(link, context.getLevel())).isPresent()) {
            //? if >=1.21.2 {
            if (!context.getLevel().isClientSide() && context.getPlayer() != null) {
            //?} else {
            /*if (!context.getLevel().isClientSide && context.getPlayer() != null) {
            *///?}
                notifyPlayer(context.getPlayer(), "message.music_disc_maker.speaker.wrong_dimension");
            }
            return InteractionResult.FAIL;
        }
        final InteractionResult result = super.place(context);
        //? if >=1.21.2 {
        if (!result.consumesAction() || context.getLevel().isClientSide()) {
        //?} else {
        /*if (!result.consumesAction() || context.getLevel().isClientSide) {
        *///?}
            return result;
        }
        final Level level = context.getLevel();
        if (level instanceof ServerLevel
                && level.getBlockEntity(context.getClickedPos()) instanceof SpeakerBlockEntity speaker) {
            pendingLink.ifPresent(speaker::replaceLinkedSource);
        }
        return result;
    }

    private static boolean isSameDimension(SpeakerLink link, Level level) {
        //? if >=1.21.2 {
        return link.dimensionId().equals(level.dimension().identifier().toString());
        //?} else {
        /*return link.dimensionId().equals(level.dimension().location().toString());
        *///?}
    }

    public static Optional<SpeakerLink> linkOf(ItemStack stack) {
        //? if >=1.21.2 {
        final CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        final CompoundTag link = root.getCompound(LINK_TAG).orElse(null);
        if (link == null) {
            return Optional.empty();
        }
        return SpeakerLink.restore(link.getStringOr(DIMENSION, ""), link.getLongOr(POSITION, 0L), link.getStringOr(SOURCE_ID, ""));
        //?} elif >=1.21 {
        /*final CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.contains(LINK_TAG, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        final CompoundTag link = root.getCompound(LINK_TAG);
        return SpeakerLink.restore(link.getString(DIMENSION), link.getLong(POSITION), link.getString(SOURCE_ID));
        */
        //?} else {
        /*final CompoundTag root = stack.getTag();
        if (root == null || !root.contains(LINK_TAG, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        final CompoundTag link = root.getCompound(LINK_TAG);
        return SpeakerLink.restore(link.getString(DIMENSION), link.getLong(POSITION), link.getString(SOURCE_ID));
        *///?}
    }

    public static void writeLink(ItemStack stack, SpeakerLink source) {
        //? if >=1.21 {
        final CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        root.put(LINK_TAG, encode(source));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        //?} else {
        /*stack.getOrCreateTag().put(LINK_TAG, encode(source));
        *///?}
    }

    private static void notifyPlayer(Player player, String key) {
        //? if >=1.21.2 {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable(key), true);
        }
        //?} else {
        /*player.displayClientMessage(Component.translatable(key), true);
        *///?}
    }

    private static CompoundTag encode(SpeakerLink source) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(DIMENSION, source.dimensionId());
        tag.putLong(POSITION, source.packedPos());
        if (source.sourceId() != null) tag.putString(SOURCE_ID, source.sourceId().toString());
        return tag;
    }
}
