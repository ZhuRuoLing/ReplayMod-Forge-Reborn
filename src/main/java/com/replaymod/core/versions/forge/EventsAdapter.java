package com.replaymod.core.versions.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.replaymod.core.events.*;
import com.replaymod.gui.utils.EventRegistrations;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class EventsAdapter extends EventRegistrations {
    @SubscribeEvent
    public void onKeyEvent(InputEvent.KeyInputEvent event) {
        KeyBindingEventCallback.EVENT.invoker().onKeybindingEvent();
    }

    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
    	KeyBindingEventCallback.EVENT.invoker().onKeybindingEvent();
    }

    @SubscribeEvent
    public void preRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        PreRenderCallback.EVENT.invoker().preRender();
    }

    @SubscribeEvent
    public void postRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PostRenderCallback.EVENT.invoker().postRender();
    }

    @SubscribeEvent
    public void renderCameraPath(RenderLevelStageEvent event) {
        PostRenderWorldCallback.EVENT.invoker().postRenderWorld(new PoseStack());
    }

    @SubscribeEvent
    public void oRenderHand(RenderHandEvent event) {
        if (PreRenderHandCallback.EVENT.invoker().preRenderHand()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void preRenderGameOverlay(RenderGameOverlayEvent.Pre event) {
        Boolean result = null;
        //TODO
        /*switch (event.getType()) {
            case CROSSHAIRS:
                result = RenderSpectatorCrosshairCallback.EVENT.invoker().shouldRenderSpectatorCrosshair();
                break;
            case HOTBAR:
                result = RenderHotbarCallback.EVENT.invoker().shouldRenderHotbar();
                break;
        }Q*/
        if (result == Boolean.FALSE) {
            event.setCanceled(true);
        }
    }
}
