package com.kuronami.musicdiscmaker.compat.aeronautics;

//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;
import com.kuronami.musicdiscmaker.client.audio.LiveConfigAnchor;
import com.kuronami.musicdiscmaker.client.audio.StaticAnchor;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Resolves tracking changes each frame, including a source first heard through a distant speaker.
public final class SableSourceAnchor implements DiscAnchor, LiveConfigAnchor {
    private final BlockPos pos;
    private final Vec3 center;
    private final StaticAnchor fallback;
    public SableSourceAnchor(BlockPos pos) {
        this.pos = pos.immutable();
        this.center = Vec3.atCenterOf(pos);
        this.fallback = new StaticAnchor(pos);
    }
    @Override
    public boolean isValid() {
        final var sub = Sable.HELPER.getContainingClient(center);
        return sub == null ? fallback.isValid() : !sub.isRemoved();
    }
    @Override
    public Vec3 worldPos(float partialTicks) {
        final var sub = Sable.HELPER.getContainingClient(center);
        return sub == null ? center : sub.renderPose(partialTicks).transformPosition(center);
    }
    @Override
    public BlockPos configPos() { return pos; }
}
*///?}
