package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 感染の口(2026-11-08): 胞子に感染して死んだバニラの Mob を、崩れ落ち → 死体ブロックへつなぐ(Vault「G1 段階 0 の共通」5 節
 * 「G1 を戻すと、この死が死体になる」)。
 *
 * <ul>
 *   <li>感染の死 = ダメージの種類 {@code spore_infection} で死んだ(暴露量 1500 %秒で 20 ダメージ。{@code SporeExposureTracker})。</li>
 *   <li>【決定 11-08 ユーザー「死体にする」】G1 の攻撃で死んだ Mob も感染の死に数える(噛まれて死んだ = 噛み傷から感染した。G1 のタグだけ。G4 以降に殺されたものは入れない)。
 *   1 撃 +300 %秒の暴露は 5 撃で 20 ダメージだが、牛(10)・鶏(4)・豚(10)は 5 撃より前に殴られて死ぬので、これが無いと
 *   「噛まれた家畜・村人も G1 になる鎖」(11-05 ユーザー「入れる」)がつながらない。
 *   【決定 11-15 ユーザー】G4〜G10・汎用の感染体({@code PlantSideRules#isInfector})に倒された Mob も同じに数える(G2・G3 は入れない)。</li>
 *   <li>表({@link G1Species})にある種の大人 → 死体の姿勢へ崩れ落ちてから死体ブロック({@link CorpseCollapse})。</li>
 *   <li>表にある種の子ども(子どもの村人・牛・豚・羊・鶏)→ 死体を作らず萌芽体がその場から生える(11-05 共通「子どもの動物は汚染シダ」= 萌芽体)。</li>
 *   <li>表に無い種(オオカミ・ウマ・他 mod の Mob・人類側など)→ 萌芽体が生える(2026-11-09 ユーザー決定。前は普通に死ぬだけ)。
 *   植物側({@code SporeExposureTracker#isPlantSide})は何も生やさない。萌芽体は近くに十分いれば生えない(密集の判定)。</li>
 * </ul>
 * 死体は Mob を消さずに置く(バニラの死の続き = ドロップ・経験値・倒した記録はそのまま。記憶 death-event-discard-skips-loot)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class G1Infection {

    private G1Infection() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel level) || !com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, entity.blockPosition())) {
            return;
        }
        if (!isInfectionDeath(entity, event.getSource())) {
            return;
        }
        G1Species species = G1Species.ofVictim(entity);
        if (species != null) {
            CorpseCollapse.begin(entity, species);
        } else if (entity instanceof net.minecraft.world.entity.Mob && !com.yayuy.overgrownvegetation.spore.SporeExposureTracker.isPlantSide(entity)) {
            // 表の種の子ども、と表に無い種(11-09 ユーザー決定。他 mod の Mob・人類側も)は萌芽体。植物側は何も生やさない。密集なら生えない
            BlightFernPlacement.spawnFromDeath(level, entity.blockPosition());
        }
    }

    /**
     * 死体になる Mob は元のドロップを出さない(11-08 ユーザー決定。死体を壊せば G1 のドロップが出る)。経験値はそのまま。
     * 崩れ落ちの印は死の知らせ(上)で付くので、ドロップの知らせの時には付いている。
     */
    @SubscribeEvent
    public static void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!event.getEntity().level().isClientSide() && CorpseCollapse.isCollapsing(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** 胞子に感染して死んだか(上の javadoc)。 */
    public static boolean isInfectionDeath(LivingEntity entity, DamageSource source) {
        // MOD の設定「動物や村人の感染」(11-19): オフならプレイヤー以外は感染の死にしない(寄生された姿・萌芽体にならない)
        if (!(entity instanceof net.minecraft.world.entity.player.Player) && entity.getServer() != null
                && !com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(entity.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.MOB_INFECTION)) {
            return false;
        }
        if (source.is(ModDamageTypes.SPORE_INFECTION)) {
            return true;
        }
        // 11-15 ユーザー決定: G1 だけでなく G4〜G10・汎用の感染体に倒された Mob も感染の死(傷口から胞子が入る)
        return source.getEntity() instanceof LivingEntity attacker && com.yayuy.overgrownvegetation.spore.PlantSideRules.isInfector(attacker);
    }
}
