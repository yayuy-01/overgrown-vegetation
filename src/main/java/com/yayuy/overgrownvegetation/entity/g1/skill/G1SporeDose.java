package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * G1 の 1 段目が噴く胞子(煙・円錐・取り付きの噛み)を、浴びた相手の暴露量(%秒)へ直に足す(2026-11-12、能力の第 1 回)。
 *
 * <p>{@link SporeExposureTracker#addDose} は G1 の 1 撃のためにプレイヤーを外しているが、噴く胞子は仕様で「吸っている間は暴露量が増える・
 * 防護マスクで防げる」(チビゾンビ・村人・豚の (c))なので、ここではプレイヤーにも足す。満ちたときの影響(プレイヤーの感染 1・Mob の 20 ダメージ)は
 * 次の暴露量の判定({@code SporeExposureTracker#tickWith} の「減る前に満ちていた分も拾う」)が起こす。
 *
 * <ul>
 *   <li>植物側({@link SporeExposureTracker#isPlantSide})・測らない Mob・創造/観戦のプレイヤーには足さない。</li>
 *   <li>防護マスク(残りあり)を被っていれば足さず、量ぶんフィルターをすり減らす。</li>
 *   <li>感染中のプレイヤーは、暴露量の代わりに段の進みへ足す(11-15「混ぜた形」。前は積まない決まりだった)。</li>
 * </ul>
 */
public final class G1SporeDose {

    private G1SporeDose() {
    }

    /** 浴びせられる相手か。 */
    public static boolean canDose(LivingEntity e) {
        if (!e.isAlive()) return false;
        if (e instanceof Player p) {
            return !p.isCreative() && !p.isSpectator();
        }
        return SporeExposureTracker.isMeasuredMob(e);
    }

    /**
     * 暴露量を {@code amount}(%秒)足す。
     *
     * @return 実際に足したか(マスク・相手の種類で防がれたら false)
     */
    public static boolean add(LivingEntity e, double amount) {
        if (amount <= 0.0D || !canDose(e)) return false;
        if (ProtectiveMaskItem.isProtecting(e)) {
            ItemStack head = e.getItemBySlot(EquipmentSlot.HEAD);
            ProtectiveMaskItem.absorb(head, (int) Math.ceil(amount));
            return false;
        }
        if (e instanceof Player p && SporeInfectionEffects.stageOf(p) > 0) {
            // 感染中も積む(11-15 ユーザー決定「混ぜた形」。前の「感染中は積まない」は取り消し): 段の進みへ直に
            SporeInfectionEffects.advance(p, amount);
            return true;
        }
        SporeExposure ex = e.getData(ModAttachments.SPORE_EXPOSURE);
        ex.dose = Math.min(DesignData.sporeField().infectDose(), ex.dose + amount);
        // 11-15 ユーザー決定「胞子の技も必ず感染」: 傷の無い胞子の技(根糸・胞子の雲・噴射など。ここを通るのは G1 の技だけ)を浴びた Mob は
        // 必ず感染する。プレイヤーは今のまま(暴露量だけ)。
        if (!(e instanceof Player)) {
            com.yayuy.overgrownvegetation.spore.MobInfection.infect(e, e.level().getGameTime());
        }
        return true;
    }
}
