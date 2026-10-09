package com.kuronami.musicdiscmaker.item;

import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.register.ModEntities;
import net.minecraft.core.BlockPos;
//? if <1.21.11 {
/*import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
*///?}
//? if >=1.21 && <1.21.11 {
/*import net.minecraft.core.dispenser.BlockSource;
*///?}
//? if <1.21 {
/*import net.minecraft.core.BlockSource;
*///?}
//? if >=1.21.11 {
import net.minecraft.core.dispenser.MinecartDispenseItemBehavior;
import net.minecraft.world.entity.EntitySpawnReason;
//?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//?} else {
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;
*///?}
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
//? if <1.21 {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
*///?}

/**
 * 金のジュークボックス付きトロッコをレールへ置く item。
 * バニラの {@code MinecartItem} と同じ挙動 (レール上のみ・dispenser 対応) で、
 * item に entity data が付いていれば (バニラの spawn egg 等と同じく) 置いた entity へ読み込む。
 */
public class GoldenJukeboxMinecartItem extends Item {

    public GoldenJukeboxMinecartItem(Item.Properties properties) {
        super(properties);
        //? if >=1.21.11 {
        DispenserBlock.registerBehavior(this,
                new MinecartDispenseItemBehavior(ModEntities.GOLDEN_JUKEBOX_MINECART.get()));
        //?} else {
        /*DispenserBlock.registerBehavior(this, DISPENSE_BEHAVIOR);
        *///?}
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        final BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.RAILS)) {
            return InteractionResult.FAIL;
        }
        final ItemStack stack = context.getItemInHand();
        final RailShape rail = railShape(state);
        //? if >=1.21.11 {
        final double yOffset = rail.isSlope() ? 0.5 : 0.0;
        if (level instanceof ServerLevel serverLevel) {
            final Vec3 spawn = new Vec3(pos.getX() + 0.5, pos.getY() + 0.0625 + yOffset, pos.getZ() + 0.5);
            final GoldenJukeboxMinecartEntity cart = AbstractMinecart.createMinecart(serverLevel,
                    spawn.x, spawn.y, spawn.z, ModEntities.GOLDEN_JUKEBOX_MINECART.get(),
                    EntitySpawnReason.DISPENSER, stack, context.getPlayer());
            if (cart == null) {
                return InteractionResult.FAIL;
            }
            if (AbstractMinecart.useExperimentalMovement(level)) {
                for (var entity : level.getEntities(null, cart.getBoundingBox())) {
                    if (entity instanceof AbstractMinecart) {
                        return InteractionResult.FAIL;
                    }
                }
            }
            if (!serverLevel.addFreshEntity(cart)) {
                return InteractionResult.FAIL;
            }
            serverLevel.gameEvent(GameEvent.ENTITY_PLACE, pos,
                    GameEvent.Context.of(context.getPlayer(), serverLevel.getBlockState(pos.below())));
            final Player player = context.getPlayer();
            if (player == null || !player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return InteractionResult.SUCCESS;
        //?} else {
        /*final double yOffset = isRailAscending(rail) ? 0.5 : 0.0;
        if (level instanceof ServerLevel serverLevel) {
            final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                    level, pos.getX() + 0.5, pos.getY() + 0.0625 + yOffset, pos.getZ() + 0.5);
            //? if >=1.21 {
            EntityType.<GoldenJukeboxMinecartEntity>createDefaultStackConfig(serverLevel, stack,
                    context.getPlayer()).accept(cart);
            //?} else {
            /^if (stack.hasCustomHoverName()) {
                cart.setCustomName(stack.getHoverName());
            }
            final CompoundTag itemTag = stack.getTag();
            if (itemTag != null && itemTag.contains("EntityTag", Tag.TAG_COMPOUND)) {
                // 1.20.1 の updateCustomEntityTag は item の root tag を受け、中の EntityTag を自分で読む。
                // EntityTag の中身を渡すと何も読まれず、収納していた盤と設定が消える。
                EntityType.updateCustomEntityTag(level, context.getPlayer(), cart, itemTag);
            }
            ^///?}
            if (!serverLevel.addFreshEntity(cart)) {
                return InteractionResult.FAIL;
            }
            serverLevel.gameEvent(GameEvent.ENTITY_PLACE, pos,
                    GameEvent.Context.of(context.getPlayer(), serverLevel.getBlockState(pos.below())));
            final Player player = context.getPlayer();
            if (player == null || !player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return placementResult(level);
        *///?}
    }

    private static boolean isRailAscending(RailShape shape) {
        //? if >=1.21.2 {
        return shape.isSlope();
        //?} else {
        /*return shape.isAscending();
        *///?}
    }

    private static InteractionResult placementResult(Level level) {
        //? if >=1.21.2 {
        return InteractionResult.SUCCESS;
        //?} else {
        /*return InteractionResult.sidedSuccess(level.isClientSide);
        *///?}
    }

    /** レール状態から線路形状を取る (傾斜判定用)。どの帯でも同じ実装。 */
    private static RailShape railShape(BlockState state) {
        return state.getBlock() instanceof BaseRailBlock railBlock
                ? state.getValue(railBlock.getShapeProperty()) : RailShape.NORTH_SOUTH;
    }

    //? if <1.21.11 {
    /*// ≤1.21.1 帯の dispenser 挙動。vanilla MinecartItem.DISPENSE_ITEM_BEHAVIOR と同じ
    // 配置則で、生成先だけを当 MOD の entity へ向ける
    // (>=1.21.11 帯は MinecartDispenseItemBehavior をそのまま使う)。
    private static final DispenseItemBehavior DISPENSE_BEHAVIOR = new DefaultDispenseItemBehavior() {
        private final DefaultDispenseItemBehavior fallback = new DefaultDispenseItemBehavior();

        @Override
        public ItemStack execute(BlockSource source, ItemStack stack) {
            //? if >=1.21 {
            final Direction direction = source.state().getValue(DispenserBlock.FACING);
            final ServerLevel level = source.level();
            final Vec3 center = source.center();
            final double x = center.x() + direction.getStepX() * 1.125;
            final double y = Math.floor(center.y()) + direction.getStepY();
            final double z = center.z() + direction.getStepZ() * 1.125;
            final BlockPos target = source.pos().relative(direction);
            //?} else {
            /^final Direction direction = source.getBlockState().getValue(DispenserBlock.FACING);
            final Level level = source.getLevel();
            final double x = source.x() + direction.getStepX() * 1.125;
            final double y = Math.floor(source.y()) + direction.getStepY();
            final double z = source.z() + direction.getStepZ() * 1.125;
            final BlockPos target = source.getPos().relative(direction);
            ^///?}
            final BlockState targetState = level.getBlockState(target);
            final double yOffset;
            if (targetState.is(BlockTags.RAILS)) {
                yOffset = isRailAscending(railShape(targetState)) ? 0.6 : 0.1;
            } else {
                if (!targetState.isAir()) {
                    return this.fallback.dispense(source, stack);
                }
                final BlockState below = level.getBlockState(target.below());
                if (!below.is(BlockTags.RAILS)) {
                    return this.fallback.dispense(source, stack);
                }
                yOffset = direction != Direction.DOWN && isRailAscending(railShape(below)) ? -0.4 : -0.9;
            }
            final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                    level, x, y + yOffset, z);
            //? if >=1.21 {
            EntityType.<GoldenJukeboxMinecartEntity>createDefaultStackConfig(level, stack, null)
                    .accept(cart);
            //?} else {
            /^if (stack.hasCustomHoverName()) {
                cart.setCustomName(stack.getHoverName());
            }
            final CompoundTag itemTag = stack.getTag();
            if (itemTag != null && itemTag.contains("EntityTag", Tag.TAG_COMPOUND)) {
                EntityType.updateCustomEntityTag(level, null, cart, itemTag);
            }
            ^///?}
            if (!level.addFreshEntity(cart)) {
                return this.fallback.dispense(source, stack);
            }
            stack.shrink(1);
            return stack;
        }

        @Override
        protected void playSound(BlockSource source) {
            //? if >=1.21 {
            source.level().levelEvent(1000, source.pos(), 0);
            //?} else {
            /^source.getLevel().levelEvent(1000, source.getPos(), 0);
            ^///?}
        }
    };
    *///?}
}
