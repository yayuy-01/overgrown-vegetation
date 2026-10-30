package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.Peaceful;
import com.yayuy.overgrownvegetation.item.ArmorAccessory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * 装飾品のうち<b>見た目だけで効くもの</b>の判定(順 1 の着手順 9)。クライアントの mixin から呼ぶ。
 *
 * <ul>
 *   <li>暗視の装飾品 —— {@code LightTextureAccessoryMixin} がライトマップの暗視の強さ(0〜1)を {@link #nightVisionScale} まで上げる。
 *   暗視の効果は付けないので、アイコンも点滅も出ない</li>
 *   <li>索敵の装飾品 —— {@code ArmorAccessoryGlowMixin} が半径内の敵を<b>自分の画面でだけ</b>発光の輪郭で描く
 *   (発光の効果を付けると他の人にも光って見えるため。寄生植物装備の {@code PlantArmorGlowMixin} と同じ手)</li>
 * </ul>
 * 「敵」= ピースフルで消える種({@link Peaceful#isEnemy})+ バニラの敵対 Mob({@link Enemy})。
 */
public final class ArmorAccessoryClient {

    /** 暗視の装飾品を着けていれば、その明るさ(暗視の効果の何割か)。着けていなければ 0。 */
    public static float nightVisionScale(LivingEntity entity) {
        if (!ArmorAccessory.NIGHT_VISION.wornBy(entity)) {
            return 0.0F;
        }
        return (float) DesignData.gear().accessories().nightVision();
    }

    /** 自分(このクライアントのプレイヤー)が索敵の装飾品で {@code entity} を光らせて見るか。 */
    public static boolean sensesEnemy(Entity entity) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null || entity == self || !(entity instanceof LivingEntity) || !entity.isAlive()) {
            return false;
        }
        if (!ArmorAccessory.ENEMY_SENSE.wornBy(self)) {
            return false;
        }
        double r = DesignData.gear().accessories().enemySenseRadius();
        if (entity.distanceToSqr(self) > r * r) {
            return false;
        }
        return entity instanceof Enemy || Peaceful.isEnemy(entity.getType());
    }

    private ArmorAccessoryClient() {
    }
}
