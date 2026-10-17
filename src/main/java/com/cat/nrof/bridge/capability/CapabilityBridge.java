package com.cat.nrof.bridge.capability;

import com.cat.nrof.runtime.NrofModContainer;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.lookup.v1.entity.EntityApiLookup;
import net.fabricmc.fabric.api.lookup.v1.item.ItemApiLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.IBlockCapabilityProvider;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges NeoForge's capability system to Fabric API's lookup system
 * ({@code BlockApiLookup} / {@code EntityApiLookup} / {@code ItemApiLookup}).
 *
 * <p>Two phases:</p>
 * <ol>
 *   <li>{@link #fireRegisterCapabilities(NrofModContainer)} — invoked between
 *       ConstructMod and CommonSetup for each converted mod, fires the real
 *       {@link RegisterCapabilitiesEvent} on the mod's event bus. NeoForge mods
 *       register providers via {@code event.registerBlock(...)} etc, which write
 *       into the capability instance's package-private {@code providers} map.</li>
 *   <li>{@link #sweepIntoFabricApi()} — invoked once after every mod has run its
 *       RegisterCapabilitiesEvent. Walks {@code BlockCapability.getAll()} /
 *       {@code EntityCapability.getAll()} / {@code ItemCapability.getAll()},
 *       reads each capability's {@code providers} map reflectively, and pushes
 *       every (block/entity/item, providers) pair into the matching Fabric
 *       lookup so Fabric mods see the registrations as ordinary API providers.</li>
 * </ol>
 *
 * <p>Registration direction is one-way (NeoForge → Fabric). For the reverse
 * direction (NeoForge consumers querying a Fabric/TR-registered block), see
 * the mixin on {@code BlockCapability#getCapability} that falls back to the
 * matching Fabric lookup when the NeoForge provider table has no entry.</p>
 */
public final class CapabilityBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("NROF/CapabilityBridge");

    private CapabilityBridge() {
    }

    /**
     * Instantiates the real {@link RegisterCapabilitiesEvent} (its ctor is
     * package-private in NeoForge; FML is the intended caller) and posts it to
     * the mod container's event bus. NeoForge's event bus dispatches to any
     * {@code @SubscribeEvent} listener the mod registered during ConstructMod.
     */
    public static void fireRegisterCapabilities(NrofModContainer container) {
        try {
            Constructor<RegisterCapabilitiesEvent> ctor =
                    RegisterCapabilitiesEvent.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            RegisterCapabilitiesEvent event = ctor.newInstance();
            container.acceptEvent(event);
        } catch (Throwable t) {
            LOGGER.error("[NROF] RegisterCapabilitiesEvent dispatch failed", t);
        }
    }

    /**
     * After all mods have run their RegisterCapabilitiesEvent, push every
     * recorded provider into the Fabric lookup with the same id as the
     * NeoForge capability name. Lookup instances are created on demand via
     * {@code BlockApiLookup.get(id, typeClass, contextClass)} which is
     * idempotent for a given id.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sweepIntoFabricApi() {
        int blockPairs = 0, entityPairs = 0, itemPairs = 0;

        // ---- BlockCapability → BlockApiLookup ----
        for (BlockCapability<?, ?> cap : BlockCapability.getAll()) {
            Map<Block, List<IBlockCapabilityProvider<?, ?>>> providers = readProviders(cap);
            if (providers.isEmpty()) continue;
            ResourceLocation id = cap.name();
            BlockApiLookup lookup = BlockApiLookup.get(id, (Class) cap.typeClass(), (Class) cap.contextClass());
            for (Map.Entry<Block, List<IBlockCapabilityProvider<?, ?>>> entry : providers.entrySet()) {
                List<IBlockCapabilityProvider<?, ?>> snapshot = List.copyOf(entry.getValue());
                lookup.registerForBlocks((world, pos, state, be, ctx) -> {
                    for (IBlockCapabilityProvider<?, ?> p : snapshot) {
                        Object result = invokeBlockProvider(p, world, pos, state, be, ctx);
                        if (result != null) return result;
                    }
                    return null;
                }, entry.getKey());
                blockPairs++;
            }
        }

        // ---- EntityCapability → EntityApiLookup ----
        for (EntityCapability<?, ?> cap : EntityCapability.getAll()) {
            Map<EntityType<?>, List<ICapabilityProvider<Entity, ?, ?>>> providers = readProviders(cap);
            if (providers.isEmpty()) continue;
            ResourceLocation id = cap.name();
            EntityApiLookup lookup = EntityApiLookup.get(id, (Class) cap.typeClass(), (Class) cap.contextClass());
            for (Map.Entry<EntityType<?>, List<ICapabilityProvider<Entity, ?, ?>>> entry : providers.entrySet()) {
                List<ICapabilityProvider<Entity, ?, ?>> snapshot = List.copyOf(entry.getValue());
                lookup.registerForTypes((entity, ctx) -> {
                    for (ICapabilityProvider<Entity, ?, ?> p : snapshot) {
                        Object result = invokeEntityProvider(p, entity, ctx);
                        if (result != null) return result;
                    }
                    return null;
                }, entry.getKey());
                entityPairs++;
            }
        }

        // ---- ItemCapability → ItemApiLookup ----
        for (ItemCapability<?, ?> cap : ItemCapability.getAll()) {
            Map<Item, List<ICapabilityProvider<ItemStack, ?, ?>>> providers = readProviders(cap);
            if (providers.isEmpty()) continue;
            ResourceLocation id = cap.name();
            ItemApiLookup lookup = ItemApiLookup.get(id, (Class) cap.typeClass(), (Class) cap.contextClass());
            for (Map.Entry<Item, List<ICapabilityProvider<ItemStack, ?, ?>>> entry : providers.entrySet()) {
                List<ICapabilityProvider<ItemStack, ?, ?>> snapshot = List.copyOf(entry.getValue());
                lookup.registerForItems((stack, ctx) -> {
                    for (ICapabilityProvider<ItemStack, ?, ?> p : snapshot) {
                        Object result = invokeItemProvider(p, stack, ctx);
                        if (result != null) return result;
                    }
                    return null;
                }, entry.getKey());
                itemPairs++;
            }
        }

        LOGGER.info("[NROF] capability bridge sweep complete: {} block + {} entity + {} item provider registrations pushed to Fabric API",
                blockPairs, entityPairs, itemPairs);
    }

    // ---- reflective helpers ------------------------------------------------

    /**
     * Reads the package-private {@code providers} field on the given capability
     * instance. Field is declared on each concrete subtype (BlockCapability /
     * EntityCapability / ItemCapability) as {@code final Map<...>}.
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> readProviders(Object cap) {
        try {
            Field f = cap.getClass().getDeclaredField("providers");
            f.setAccessible(true);
            Object v = f.get(cap);
            if (v instanceof Map) {
                return (Map<K, V>) v;
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.warn("[NROF] could not read providers field on {}: {}", cap.getClass().getName(), e.toString());
        }
        return Map.of();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object invokeBlockProvider(IBlockCapabilityProvider p,
                                              net.minecraft.world.level.Level world,
                                              net.minecraft.core.BlockPos pos,
                                              net.minecraft.world.level.block.state.BlockState state,
                                              net.minecraft.world.level.block.entity.BlockEntity be,
                                              Object ctx) {
        try {
            return p.getCapability(world, pos, state, be, ctx);
        } catch (Throwable t) {
            LOGGER.debug("[NROF] block capability provider threw: {}", t.toString());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object invokeEntityProvider(ICapabilityProvider p, Entity entity, Object ctx) {
        try {
            return p.getCapability(entity, ctx);
        } catch (Throwable t) {
            LOGGER.debug("[NROF] entity capability provider threw: {}", t.toString());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object invokeItemProvider(ICapabilityProvider p, ItemStack stack, Object ctx) {
        try {
            return p.getCapability(stack, ctx);
        } catch (Throwable t) {
            LOGGER.debug("[NROF] item capability provider threw: {}", t.toString());
            return null;
        }
    }
}
