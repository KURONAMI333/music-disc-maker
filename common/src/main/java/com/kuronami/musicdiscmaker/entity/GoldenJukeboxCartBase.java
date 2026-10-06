package com.kuronami.musicdiscmaker.entity;

import com.kuronami.musicdiscmaker.register.ModItems;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
//?}
//? if <1.21.11 {
/*import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
*///?}

// VehicleEntity#getDropItem is protected in NeoForge 21.1.227 and on newer
// Minecraft bands, so the concrete cart subclass can implement it from this package.
public abstract class GoldenJukeboxCartBase extends AbstractMinecartContainer {

    protected GoldenJukeboxCartBase(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    // Same layout as vanilla MinecartChest(Level, double, double, double).
    protected GoldenJukeboxCartBase(EntityType<?> entityType, double x, double y, double z, Level level) {
        super(entityType, level);
        this.setPos(x, y, z);
    }

    @Override
    public Item getDropItem() {
        return ModItems.GOLDEN_JUKEBOX_MINECART.get();
    }
}
