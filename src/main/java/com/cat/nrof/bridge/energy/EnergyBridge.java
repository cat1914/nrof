package com.cat.nrof.bridge.energy;

import net.minecraft.util.math.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.reborn.energy.api.EnergyStorage;

/**
 * Wires NeoForge's Forge-Energy capability ({@link Capabilities.EnergyStorage#BLOCK})
 * and Tech Reborn's {@link EnergyStorage#SIDED} lookup together, in both directions:
 *
 * <ul>
 *   <li><b>Forward (NeoForge → TR):</b> registers a {@code registerFallback} on
 *       {@code EnergyStorage.SIDED}. Any block that has a NeoForge FE provider
 *       (registered via {@code RegisterCapabilitiesEvent}) but no explicit TR
 *       registration is wrapped in a {@link NeoToTrEnergy} adapter on demand.
 *       This makes TR-mods see Create's FE capacity directly.</li>
 *   <li><b>Backward (TR → NeoForge):</b> handled by
 *       {@code com.cat.nrof.mixin.BlockCapabilityMixin} which intercepts
 *       {@code BlockCapability#getCapability} calls on the Energy capability and
 *       falls back to {@code EnergyStorage.SIDED.find(...)} when the NeoForge
 *       provider table has no entry for the queried block. NeoForge mods thus see
 *       TR-registered energy as an {@link IEnergyStorage} via
 *       {@link TrToNeoEnergy}.</li>
 * </ul>
 *
 * <p>{@link #install()} must run after every converted mod's
 * RegisterCapabilitiesEvent has fired, i.e. at the end of
 * {@code NrofModRuntime#boot}, so the NeoForge provider tables are fully
 * populated before the fallback handler starts answering queries.</p>
 */
public final class EnergyBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("NROF/EnergyBridge");

    private EnergyBridge() {
    }

    public static void install() {
        // Forward: expose NeoForge FE to TR consumers via fallback.
        EnergyStorage.SIDED.registerFallback((world, pos, state, blockEntity, direction) -> {
            IEnergyStorage neo = Capabilities.EnergyStorage.BLOCK.getCapability(
                    world, pos, state, blockEntity, direction);
            return neo == null ? null : new NeoToTrEnergy(neo);
        });
        LOGGER.info("[NROF] energy bridge installed: Capabilities.EnergyStorage.BLOCK ⇆ EnergyStorage.SIDED (fallback + mixin fallback)");
    }

    /**
     * Reverse-direction helper used by {@code BlockCapabilityMixin}: when a
     * NeoForge mod queries {@code Capabilities.EnergyStorage.BLOCK} for a block
     * that has no NeoForge-registered provider, fall back to the TR lookup and
     * wrap the result as an {@link IEnergyStorage}.
     *
     * <p>Returns {@code null} when the block has no TR energy either, in which
     * case the caller lets the default NeoForge lookup proceed (which will also
     * return {@code null}).</p>
     */
    public static IEnergyStorage queryTrEnergy(
            net.minecraft.world.World world,
            net.minecraft.util.math.BlockPos pos,
            net.minecraft.block.BlockState state,
            net.minecraft.block.entity.BlockEntity blockEntity,
            Direction direction) {
        EnergyStorage tr = EnergyStorage.SIDED.find(world, pos, state, blockEntity, direction);
        return tr == null ? null : new TrToNeoEnergy(tr);
    }
}
