package com.kuronami.musicdiscmaker.client.render;

/** Render callbacks may run while a world transition has not supplied every render state yet. */
final class RenderStateGuard {
    private RenderStateGuard() {}

    static boolean hasInputs(Object poseStack, Object camera) {
        return poseStack != null && camera != null;
    }
}
