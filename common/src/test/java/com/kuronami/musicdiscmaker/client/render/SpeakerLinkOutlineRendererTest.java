package com.kuronami.musicdiscmaker.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeakerLinkOutlineRendererTest {
    @Test
    void missingRenderStateSkipsTheOutline() {
        final Object poseStack = new Object();
        final Object camera = new Object();

        assertFalse(RenderStateGuard.hasInputs(null, camera));
        assertFalse(RenderStateGuard.hasInputs(poseStack, null));
        assertFalse(RenderStateGuard.hasInputs(null, null));
        assertTrue(RenderStateGuard.hasInputs(poseStack, camera));
    }
}
