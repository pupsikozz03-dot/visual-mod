package com.visuals.mixin;

import com.visuals.client.VisualModClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.RenderTickCounter;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class ModMixins {

    @Mixin(net.minecraft.client.render.GameRenderer.class)
    public static class MixinGameRenderer {
        // В 1.21.x аргумент FOV имеет тип double!
        @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"), cancellable = true)
        private void onGetBasicProjectionMatrix(double fov, CallbackInfoReturnable<Matrix4f> cir) {
            if (VisualModClient.INSTANCE == null) return;
            VisualModClient.AspectRatioModule mod = (VisualModClient.AspectRatioModule) VisualModClient.INSTANCE.getModuleManager().getModule(VisualModClient.AspectRatioModule.class);
            if (mod != null && mod.isEnabled()) {
                float customAspect = mod.getRatio();
                Matrix4f matrix = new Matrix4f();
                matrix.perspective((float) Math.toRadians(fov), customAspect, 0.05f, 1000.0f);
                cir.setReturnValue(matrix);
            }
        }
    }

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
