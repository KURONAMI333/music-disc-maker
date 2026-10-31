package com.kuronami.musicdiscmaker.entity;

import com.kuronami.musicdiscmaker.register.ModItems;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
//?} else {
/*import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
*///?}

// VehicleEntity#getDropItem is package-private in vanilla 1.21.1, so every platform build
// widens it: NeoForge 21.1.227 ships it as protected, Fabric uses an access widener, and
// the 1.20.1 build uses an access transformer.
public abstract class GoldenJukeboxCartBase extends AbstractMinecartContainer {

    protected GoldenJukeboxCartBase(EntityType<? extends GoldenJukeboxCartBase> entityType, Level level) {
        super(entityType, level);
    }

    /** Places the cart at (x, y, z). */
    protected GoldenJukeboxCartBase(EntityType<? extends GoldenJukeboxCartBase> entityType,
            double x, double y, double z, Level level) {
        super(entityType, level);
        this.setPos(x, y, z);
    }

    @Override
    public Item getDropItem() {
        return ModItems.GOLDEN_JUKEBOX_MINECART.get();
    }
}
