package com.cat.nrof.mixin;

import com.cat.nrof.bridge.energy.EnergyBridge;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reverse-direction fallback for the FE bridge: when a NeoForge mod calls
 * {@code Capabilities.EnergyStorage.BLOCK.getCapability(world, pos, state, be, dir)}
 * on a block that has no NeoForge-registered provider, look the block up in
 * Tech Reborn's {@code EnergyStorage.SIDED} instead and wrap the result as
 * {@link IEnergyStorage}.
 *
 * <p>The bundled {@code neoforge-api} jar is remapped to intermediary at
 * cache-build time, so its method descriptors use {@code class_xxxx} names at
 * runtime. The mixin descriptor below is written with those intermediary
 * names directly and {@code remap = false} is set so the refmap does not
 * attempt to re-translate them.</p>
 *
 * <p>Only the energy capability is bridged in the reverse direction. Other
 * capabilities are served by the forward sweep in
 * {@code CapabilityBridge#sweepIntoFabricApi} — adding a generic reverse
 * fallback for every capability would require evaluating every Fabric lookup
 * on every NeoForge query, which is expensive and rarely useful.</p>
 */
@Mixin(value = BlockCapability.class, remap = false)
public abstract class BlockCapabilityMixin {

    @Inject(
            method = "getCapability(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/BlockEntity;Ljava/lang/Object;)Ljava/lang/Object;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void nrof$trEnergyFallback(World world, BlockPos pos, BlockState state,
                                       BlockEntity blockEntity, Object context,
                                       CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        Object self = this;
        if (self != Capabilities.EnergyStorage.BLOCK) {
            return;
        }
        // Context type for EnergyStorage.BLOCK is Direction (sided). Anything
        // else isn't a valid energy query and shouldn't be bridged.
        Direction direction = context instanceof Direction d ? d : null;
        IEnergyStorage wrapped = EnergyBridge.queryTrEnergy(world, pos, state, blockEntity, direction);
        if (wrapped != null) {
            cir.setReturnValue(wrapped);
        }
    }
}
