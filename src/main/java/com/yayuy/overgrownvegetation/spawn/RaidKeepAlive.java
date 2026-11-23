package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;

/**
 * 予算を払って出した攻め手を、遠くで消さない(見直し ②3、11-16)。地上の G2・G3 はバニラの Monster のまま {@code removeWhenFarAway} が true で、
 * 送り込み・継ぎ足し・大きな襲撃・苗床で出した個体が、プレイヤーから 128 ブロックより遠いと消えていた(継ぎ足しは消えた分を払い直していた)。
 * 種のクラスには手を入れず、消えるかを聞くイベントで断る:
 * <ul>
 *   <li>拠点へ歩いている間(G2・G3 = {@link PlantMarch}、G1 = 拠点へ向かう使い)。</li>
 *   <li>大きな襲撃の印があり、大きな襲撃が続いている間(気分 LARGE)。</li>
 * </ul>
 * 襲撃が終わった後の個体は、ふだんどおり遠くで消える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RaidKeepAlive {

    private RaidKeepAlive() {
    }

    @SubscribeEvent
    public static void onDespawn(MobDespawnEvent event) {
        if (keep(event.getEntity())) event.setResult(MobDespawnEvent.Result.DENY);
    }

    /** 消さないでおく個体か。 */
    public static boolean keep(Mob m) {
        // ピースフルでは敵対の個体を守らない(バニラのとおり消える。11-18 見直し ①9・ユーザー決定 2「G1 を全部消す」。
        // 前は歩いている・襲撃の印の G1 だけ残った)
        if (m.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && m instanceof net.minecraft.world.entity.monster.Monster) return false;
        if (PlantMarch.active(m)) return true;
        if (m instanceof AbstractParasitizedMonster g1 && g1.march().active()) return true;
        if (!(m.level() instanceof ServerLevel level)) return false;
        return m.getTags().contains(LargeRaids.ATTACKER_TAG) && ForestDirector.get(level).mood() == ForestDirector.Mood.LARGE;
    }
}
