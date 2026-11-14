package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 羊の 1 段目の (c)「綿毛を風に飛ばす」(2026-11-13、能力の第 2 回。仕様 Vault「G1 羊(1 段目)」3 節【決定 11-09・数は仮】)。
 * 綿毛の房を 1 段ちぎって空へ放つ。綿毛は世界の風に乗って流され、落ちた所に降り積もりを足す({@link G1FluffDrift})。<b>人に当てる技ではない</b>(運ぶ役)。
 *
 * <ul>
 *   <li>使う条件: 綿毛が 1 段以上・燃えていない(仕様【決定】: 燃えている間は飛ばせない)・空の下・{@value #CALM_RADIUS} ブロック以内に獲物がいない
 *   (仕様「敵が近いときは使わない」。G1 は 35 ブロック以内の生き物を必ず標的にするので、「標的がいない」ではなく「近くにいない」で見る)。</li>
 *   <li>段: loose_windup 30(綿毛の房が白く膨らみ、体を震わせて房を立てる = 放つ前に倒す・刈る好機)→ loose 10(1 tick 目に「ぼふっ」と放つ)。
 *   途中で火が付いたら止める(綿毛は減らない)。</li>
 *   <li>放つと綿毛が 1 段減る(草を食べると戻る、{@link G1GrazeGoal}。戻るのは放ってから {@link DevelopedParasitized#FLUFF_REGROW_TICKS} tick 後から)。着地点の升の降り積もり +{@value #DEPOSIT_PERCENT}%(発芽のしきい値の)。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(2 分【決定 11-13 ユーザー「クールダウンを20秒から2分に伸ばそう」】。前は 400 tick = 20 秒で、
 *   1 頭が 2〜3 分で風下の同じ升を発芽のしきい値まで持っていく計算だった)。冷却は残りの tick で保存する({@code LooseIn}、11-13 ユーザー「羊のやつは一応直しておこう。」。前は保存せず、読み込み直すたびに 1 回すぐ放っていた)。
 *   湧いた直後・1 段目に育った直後の 1 回はすぐ放てる【仮】。</li>
 * </ul>
 */
public class G1FluffLooseGoal extends G1TechniqueGoal {

    static final double CALM_RADIUS = 8.0D;
    public static final double DEPOSIT_PERCENT = 15.0D;
    public static final int COOLDOWN = 2400;

    public G1FluffLooseGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.LOOSE_WINDUP, G1Move.LOOSE}, new int[]{30, 10});
    }

    @Override
    protected boolean ready() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        return mob.fluff() > 0 && !mob.isOnFire() && mob.calm(CALM_RADIUS) && SporeExposureTracker.underSky(level, mob);
    }

    /** 向きは追わない(標的に向かう技ではない)。 */
    @Override
    protected int aimUntil() {
        return 0;
    }

    @Override
    protected boolean interrupted() {
        return mob.isOnFire();
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage % 8 == 4) {
            // 体を震わせる(房が擦れる乾いた音)
            level.playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.WOOL_STEP, SoundSource.HOSTILE, 0.6F, 1.4F);
        }
        if (stageIndex == 1 && tickInStage == 1 && mob.fluff() > 0) {
            mob.setFluff(mob.fluff() - 1);
            mob.delayFluffRegrow(); // 放った分をすぐ食べ直させない
            Vec3 from = mob.position().add(0.0D, mob.getBbHeight(), 0.0D);
            mob.setLastFluffLanding(G1FluffDrift.loose(level, from, DEPOSIT_PERCENT, mob.getRandom()));
            level.playSound(null, from.x, from.y, from.z, SoundEvents.WOOL_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.HOSTILE, 0.6F, 0.6F);
        }
    }
}
