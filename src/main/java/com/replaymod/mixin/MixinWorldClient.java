package com.replaymod.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.replaymod.recording.handler.RecordingEventHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;


@Mixin(ClientLevel.class)
public abstract class MixinWorldClient extends Level implements RecordingEventHandler.RecordingEventSender {
    @Shadow
    private Minecraft minecraft;

    protected MixinWorldClient(WritableLevelData mutableWorldProperties, ResourceKey<Level> registryKey, Holder<DimensionType> dimensionType, Supplier<ProfilerFiller> profiler, boolean bl, boolean bl2, long l) {
        super(mutableWorldProperties, registryKey,
                dimensionType, profiler, bl, bl2, l);
    }

    @Unique
    private RecordingEventHandler replayModRecording_getRecordingEventHandler() {
        return ((RecordingEventHandler.RecordingEventSender) this.minecraft.levelRenderer).getRecordingEventHandler();
    }

    // Sounds that are emitted by thePlayer no longer take the long way over the server
    // but are instead played directly by the client. The server only sends these sounds to
    // other clients so we have to record them manually.
    // E.g. Block place sounds
    @Inject(method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"))
    public void replayModRecording_recordClientSound(Player p_104645_, double p_104646_, double p_104647_, double p_104648_, SoundEvent p_104649_, SoundSource p_104650_, float p_104651_, float p_104652_, CallbackInfo ci) {
        if (p_104645_ == this.minecraft.player) {
            RecordingEventHandler handler = replayModRecording_getRecordingEventHandler();
            if (handler != null) {
                handler.onClientSound(p_104649_, p_104650_, p_104646_, p_104647_, p_104648_, p_104651_, p_104652_);
            }
        }
    }

    //TODO
    // Same goes for level events (also called effects). E.g. door open, block break, etc.
    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void playLevelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (player == this.minecraft.player) {
            // We caused this event, the server won't send it to us
            RecordingEventHandler handler = replayModRecording_getRecordingEventHandler();
            if (handler != null) {
                handler.onClientEffect(type, pos, data);
            }
        }
    }
}
