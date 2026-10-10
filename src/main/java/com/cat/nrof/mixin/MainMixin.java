package com.cat.nrof.mixin;

import com.cat.nrof.loader.NrofLoaderHook;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NROF's loader integration point for the client.
 *
 * <p>{@code Main#main} runs before {@code MinecraftClient}'s constructor (which
 * invokes the main entrypoint stage), and — unlike any {@code net.fabricmc.loader.*}
 * class — it lives in the knot realm where mod mixins are actually applied. Running
 * the conversion pipeline + index registration here means every fabric entrypoint
 * already sees the converted NeoForge mods in the loader's mod index.</p>
 */
@Mixin(Main.class)
public class MainMixin {

    @Inject(method = "main", at = @At("HEAD"))
    private static void nrof$earlyRegister(String[] args, CallbackInfo ci) {
        NrofLoaderHook.registerConvertedMods();
    }
}
