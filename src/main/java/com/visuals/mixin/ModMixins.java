package com.visuals.mixin;

import com.visuals.client.VisualModClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class ModMixins {

    @Mixin(net.minecraft.client.gui.hud.InGameHud.class)
    public static class MixinInGameHud {
        @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
        private void onRenderCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
            if (VisualModClient.INSTANCE == null) return;
            VisualModClient.CrosshairModule cross = (VisualModClient.CrosshairModule) VisualModClient.INSTANCE.getModuleManager().getModule(VisualModClient.CrosshairModule.class);
            if (cross != null && cross.isEnabled()) {
                ci.cancel();
            }
        }
    }

    @Mixin(TitleScreen.class)
    public static class MixinTitleScreen {
        @Inject(method = "init", at = @At("HEAD"), cancellable = true)
        private void onInit(CallbackInfo ci) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                mc.setScreen(new VisualModClient.CustomTitleScreen());
                ci.cancel();
            }
        }
    }
}
