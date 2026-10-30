package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * ヴォラムネスに呑まれた相手の見え方(2026-10-01、実装係の判断)。
 *
 * <ul>
 *     <li><b>呑まれた相手は描かない</b>。胴の中(地上 3.3 ブロック)に乗せているが、身をよじる digest のアニメで胴から体がはみ出して見えるので。
 *     周りのプレイヤーには胴の膨らみ(digest のアニメ)で呑んでいることが分かる。バニラの描画(LivingEntityRenderer)の相手だけが対象 ——
 *     GeckoLib の Mob には {@link RenderLivingEvent} が届かないが、汚染側の Mob は呑まないので困らない(メモリ geo-entity-renderer-not-living)。</li>
 *     <li><b>呑まれたプレイヤー本人の画面は、暗い肉の色の濃い霧で覆う</b>(見えないと何が起きたか分からないため。周りの景色は 3 ブロック先で消える)。
 *     アクションバーの「中から殴れば吐き出す」はサーバーが出す({@link VoramnesEntity#SWALLOWED_MESSAGE})。
 *     胴の当たり判定の中に目があるので、どこを殴っても本体に当たる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class VoramnesClientEvents {

    /** 胃の中の霧の色(暗い肉の赤茶。仮)と見える距離。 */
    private static final float FOG_R = 0.20F;
    private static final float FOG_G = 0.07F;
    private static final float FOG_B = 0.05F;
    private static final float FOG_FAR = 3.0F;

    private VoramnesClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (event.getEntity().getVehicle() instanceof VoramnesEntity worm && worm.hidesPassengers()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (swallowed()) {
            event.setNearPlaneDistance(0.0F);
            event.setFarPlaneDistance(FOG_FAR);
            event.setFogShape(FogShape.SPHERE);
            event.setCanceled(true); // 取り消すと上の値が使われる(NeoForge の決まり)
        }
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (swallowed()) {
            event.setRed(FOG_R);
            event.setGreen(FOG_G);
            event.setBlue(FOG_B);
        }
    }

    /** 手元のプレイヤーがヴォラムネスの中に居るか。 */
    private static boolean swallowed() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof VoramnesEntity;
    }
}
