package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.client.infect.InfectRender;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 汎用の感染体(2026-11-15)を描いている間、エンティティ用の RenderType に入る絵を、塗り替えた絵へ差し替える({@link InfectRender#swap})。
 * 体の絵も、重ね着(羊の毛・村人の服)も、GeckoLib やほかの MOD の描き方も、絵はこの工場を通る。
 * 光る目({@code eyes}・emissive)・防具({@code armorCutoutNoCull})・渦は差し替えない。
 */
@Mixin(RenderType.class)
public abstract class RenderTypeInfectMixin {

    // 感染体を描いていなければ、何もせずそのまま返す
    @ModifyVariable(method = {"entitySolid", "entityCutout", "entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;",
            "entityCutoutNoCullZOffset(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;", "entityTranslucentCull",
            "entityTranslucent(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;", "entitySmoothCutout", "entityDecal", "entityNoOutline"},
            at = @At("HEAD"), argsOnly = true, require = 0)
    private static ResourceLocation overgrown_vegetation$infectSkin(ResourceLocation location) {
        return InfectRender.swap(location);
    }

    @ModifyReturnValue(method = {"entitySolid", "entityCutout", "entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;",
            "entityCutoutNoCullZOffset(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;", "entityTranslucentCull",
            "entityTranslucent(Lnet/minecraft/resources/ResourceLocation;Z)Lnet/minecraft/client/renderer/RenderType;", "entitySmoothCutout", "entityDecal", "entityNoOutline"},
            at = @At("RETURN"), require = 0)
    private static RenderType overgrown_vegetation$infectRemember(RenderType type, @Local(argsOnly = true) ResourceLocation location) {
        InfectRender.remember(type, location);
        return type;
    }
}
