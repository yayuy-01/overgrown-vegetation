package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.ClientErosion;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 浸食度で最大体力が下がったときに、<b>ダメージの演出を出さない</b>(2026-09-22)。
 *
 * <h2>なぜ演出が出るのか</h2>
 * <p>最大体力が下がると {@code LivingEntity#onAttributeUpdated} が現在体力を新しい上限まで切り詰める。
 * サーバーはそれを {@code ClientboundSetHealthPacket} で送り、クライアントは
 * {@link LocalPlayer#hurtTo} で受ける。{@code hurtTo} は「体力が減った = 殴られた」とみなして
 * {@code hurtTime} を立てるので、<b>赤い明滅と画面の傾き</b>({@code GameRenderer#bobHurt})が出る。
 * ダメージ源は無いのに、殴られたように見える。
 *
 * <h2>どう見分けるか</h2>
 * <p>次の 3 つが揃ったときだけ「上限の切り詰め」とみなして静かに反映する。
 * <ul>
 *     <li><b>浸食度を持っている</b> —— この MOD が原因のときだけに限る</li>
 *     <li><b>減り幅が {@value #QUIET_DROP} 以下</b> —— 1% ぶんの切り詰めは体力 20・最大 -50% でも 0.1。
 *     普通のダメージがこの幅に収まることはまず無い</li>
 *     <li><b>減った先が新しい上限のすぐ近く</b> —— 切り詰めは必ず「上限ちょうど」になる。
 *     属性の同期が 1 tick ずれても拾えるよう {@value #MAX_HEALTH_SLACK} の余裕を持たせてある</li>
 * </ul>
 * どれか 1 つでも外れたら素通しするので、<b>本物のダメージの演出は消えない</b>。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerQuietHealthCapMixin {

    /** 静かに反映してよい減り幅の上限(体力)。 */
    private static final float QUIET_DROP = 0.5F;

    /** 「新しい上限ちょうど」とみなす余裕(体力)。属性の同期が遅れたぶんを吸収する。 */
    private static final float MAX_HEALTH_SLACK = 1.0F;

    @Shadow
    private boolean flashOnSetHealth;

    @Inject(method = "hurtTo", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$quietMaxHealthCap(float health, CallbackInfo ci) {
        if (!this.flashOnSetHealth) {
            return; // 入室直後の初回同期。バニラに任せる(ここでフラグを立てる役目がある)。
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        float drop = self.getHealth() - health;
        if (drop <= 0.0F || drop > QUIET_DROP) {
            return;
        }
        if (ClientErosion.percentOf(self) <= 0.0F) {
            return;
        }
        if (health < self.getMaxHealth() - MAX_HEALTH_SLACK) {
            return;
        }
        self.setHealth(health);
        ci.cancel();
    }
}
