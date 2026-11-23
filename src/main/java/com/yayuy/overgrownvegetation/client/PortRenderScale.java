package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import software.bernie.geckolib.event.GeoRenderEvent;

/**
 * 【1.20.1 だけの物・G5 の助け手】体格(SCALE、決定 6)の描画の拡大。
 *
 * <p>本流の GeckoLib 4.7.6 は {@code GeoEntityRenderer#actuallyRender} で {@code LivingEntity#getScale()} を掛ける(足もとを原点に一様に拡大)。
 * 1.20.1 の 4.8.4 は掛けないので、GeckoLib の描画前の知らせ({@code GeoRenderEvent.Entity.Pre}。{@code preRender} の後・{@code actuallyRender} の前に
 * 1 回だけ来て、層の {@code reRender} では来ない)で同じだけ掛ける。当たり判定は互換の層の mixin(getScale に SCALE 属性を掛ける)と G3 の
 * {@code getDimensions} が合わせてある。
 *
 * <p>この MOD の Entity だけに掛ける(ほかの MOD の GeckoLib の Mob は 4.8.4 の前提で作られているので触らない)。1.20.1 の {@code getScale()} は
 * 子どもで 0.5 を返す = 本流(1.21 の getScale = 年齢の倍率 × SCALE)と同じ値。
 *
 * <p>本流で描き手が {@code applyRotations(…, nativeScale)} の nativeScale で割っていた所は、{@link #of(Entity)} で同じ値を引く
 * (ここで先に拡大してあるので、割る計算はそのまま合う)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PortRenderScale {

    private PortRenderScale() {
    }

    /** 本流の GeckoLib が掛ける体格(この MOD の生き物だけ。ほかは 1)。 */
    public static float of(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 1.0F;
        }
        if (!OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace())) {
            return 1.0F;
        }
        return living.getScale();
    }

    @SubscribeEvent
    public static void onGeoEntityPre(GeoRenderEvent.Entity.Pre event) {
        float s = of(event.getEntity());
        if (s != 1.0F) {
            event.getPoseStack().scale(s, s, s);
        }
    }
}
