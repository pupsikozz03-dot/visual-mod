package com.visuals.mixin;

import com.visuals.client.VisualModClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class ModMixins {

    @Mixin(net.minecraft.client.render.GameRenderer.class)
    public static class MixinGameRenderer {
        @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"), cancellable = true)
        private void onGetBasicProjectionMatrix(float fov, CallbackInfoReturnable<Matrix4f> cir) {
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
        private void onRenderCrosshair(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter, CallbackInfo ci) {
            if (VisualModClient.INSTANCE == null) return;
            VisualModClient.CrosshairModule cross = (VisualModClient.CrosshairModule) VisualModClient.INSTANCE.getModuleManager().getModule(VisualModClient.CrosshairModule.class);
            if (cross != null && cross.isEnabled()) {
                ci.cancel();
            }
        }
    }

    @Mixin(LivingEntityRenderer.class)
    public static class MixinLivingEntityRenderer {
        @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("TAIL"))
        private void onRenderLiving(LivingEntityRenderState state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
            if (VisualModClient.INSTANCE == null) return;
            if (state instanceof PlayerEntityRenderState) {
                VisualModClient.CosmeticsModule cosm = (VisualModClient.CosmeticsModule) VisualModClient.INSTANCE.getModuleManager().getModule(VisualModClient.CosmeticsModule.class);
                if (cosm != null && cosm.isEnabled()) {
                    cosm.renderCosmeticsFromState(matrices, vertexConsumers);
                }
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
