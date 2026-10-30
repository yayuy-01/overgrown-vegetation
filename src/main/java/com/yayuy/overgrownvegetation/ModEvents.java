package com.yayuy.overgrownvegetation;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.SporophyllEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import com.yayuy.overgrownvegetation.entity.XenacanthusEntity;
import com.yayuy.overgrownvegetation.entity.DiplocaulusEntity;
import com.yayuy.overgrownvegetation.entity.LungfishEntity;
import com.yayuy.overgrownvegetation.entity.HibbertopterusEntity;
import com.yayuy.overgrownvegetation.entity.GiantSnailEntity;
import com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity;
import com.yayuy.overgrownvegetation.entity.EryopsEntity;
import com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity;
import com.yayuy.overgrownvegetation.entity.CrassigyrinusEntity;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import com.yayuy.overgrownvegetation.entity.ProtophasmaEntity;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
public final class ModEvents {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.BLIGHT_FERN.get(), BlightFernEntity.createAttributes().build());
        event.put(ModEntities.OVERGROWN_SHIELDER.get(), OvergrownShielderEntity.createAttributes().build());
        event.put(ModEntities.FALCIMNES.get(), FalcimnesEntity.createAttributes().build());
        event.put(ModEntities.SCUTIMNES.get(), ScutimnesEntity.createAttributes().build());
        event.put(ModEntities.ACUMNES.get(), com.yayuy.overgrownvegetation.entity.AcumnesEntity.createAttributes().build());
        // G5 の低キューブ 4 種(2026-10-01)。
        event.put(ModEntities.BATHYMNES.get(), com.yayuy.overgrownvegetation.entity.BathymnesEntity.createAttributes().build());
        event.put(ModEntities.EREBOMNES.get(), com.yayuy.overgrownvegetation.entity.ErebomnesEntity.createAttributes().build());
        event.put(ModEntities.VORAMNES.get(), com.yayuy.overgrownvegetation.entity.VoramnesEntity.createAttributes().build());
        event.put(ModEntities.FODIMNES.get(), com.yayuy.overgrownvegetation.entity.FodimnesEntity.createAttributes().build());
        event.put(ModEntities.PHALANGIOTARBUS.get(), PhalangiotarbusEntity.createAttributes().build());
        event.put(ModEntities.OVERGROWN_NEXUS.get(), OvergrownNexusEntity.createAttributes().build());
        event.put(ModEntities.DORGA_ARCHELAUS_TITANICA.get(), com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity.createAttributes().build());
        event.put(ModEntities.HIVE_NODE.get(), HiveNodeEntity.createAttributes().build());

        // テスト Mob — グループ2(原生生産種)/ グループ3(原生消費種)。
        event.put(ModEntities.PROTOPHASMA.get(), ProtophasmaEntity.createAttributes().build());
        event.put(ModEntities.ARTHROPLEURA.get(), ArthropleuraEntity.createAttributes().build());
        event.put(ModEntities.SPELAEOPLEURA.get(), SpelaeopleuraEntity.createAttributes().build());
        event.put(ModEntities.COELUROSAURAVUS.get(), CoelurosauravusEntity.createAttributes().build());
        event.put(ModEntities.LITHOPLEURA.get(), LithopleuraEntity.createAttributes().build());
        event.put(ModEntities.STENODICTYA_LOBATA.get(), StenodictyaLobataEntity.createAttributes().build());
        event.put(ModEntities.MEGANEURA.get(), MeganeuraEntity.createAttributes().build());
        event.put(ModEntities.ARBORA.get(), ArboraEntity.createAttributes().build());
        event.put(ModEntities.PHYLLOSENS.get(), PhyllosensEntity.createAttributes().build());
        event.put(ModEntities.VELOPHYLL.get(), VelophyllEntity.createAttributes().build());
        event.put(ModEntities.PEDICULA.get(), PediculaEntity.createAttributes().build());
        event.put(ModEntities.SPOROPHYLL.get(), SporophyllEntity.createAttributes().build());
        event.put(ModEntities.PETROLACOSAURUS.get(), PetrolacosaurusEntity.createAttributes().build());
        event.put(ModEntities.IANTHASAURUS.get(), IanthasaurusEntity.createAttributes().build());
        event.put(ModEntities.HYLONOMUS.get(), HylonomusEntity.createAttributes().build());
        event.put(ModEntities.RHIZODUS.get(), RhizodusEntity.createAttributes().build());
        event.put(ModEntities.XENACANTHUS.get(), XenacanthusEntity.createAttributes().build());
        event.put(ModEntities.ORTHACANTHUS.get(), OrthacanthusEntity.createAttributes().build());
        event.put(ModEntities.MEGARACHNE.get(), MegarachneEntity.createAttributes().build());
        // G2・G3 の新しい 8 種(2026-10-22)
        event.put(ModEntities.DIPLOCAULUS.get(), DiplocaulusEntity.createAttributes().build());
        event.put(ModEntities.LUNGFISH.get(), LungfishEntity.createAttributes().build());
        event.put(ModEntities.HIBBERTOPTERUS.get(), HibbertopterusEntity.createAttributes().build());
        event.put(ModEntities.GIANT_SNAIL.get(), GiantSnailEntity.createAttributes().build());
        event.put(ModEntities.STENODICTYA_NYMPH.get(), StenodictyaNymphEntity.createAttributes().build());
        event.put(ModEntities.ERYOPS.get(), EryopsEntity.createAttributes().build());
        event.put(ModEntities.PULMONOSCORPIUS.get(), PulmonoscorpiusEntity.createAttributes().build());
        event.put(ModEntities.CRASSIGYRINUS.get(), CrassigyrinusEntity.createAttributes().build());

        // 「過繁茂した(MOB名)」— 仮実装なので、対応するバニラ Mob とほぼ同じステータスを使う。
        // ただし牛・羊・豚・ニワトリはバニラでは非戦闘的で ATTACK_DAMAGE 属性を一切持たないため、
        // ParasitizedTargetGoal.install で追加した MeleeAttackGoal が実際に攻撃しようとした瞬間
        // (Mob#doHurtTarget が getAttributeValue(ATTACK_DAMAGE) を呼ぶ)IllegalArgumentException で
        // サーバーがクラッシュする。敵対化した以上この4種にも明示的に追加する必要がある(仮の値、
        // Zombie の基本値 3.0 に合わせた)。ゾンビ/クリーパー/クモは Monster.createMonsterAttributes()
        // 経由で元々 ATTACK_DAMAGE を持っているので変更不要。
        // 牛・羊・豚・ニワトリはバニラでは ATTACK_DAMAGE 属性を持たないため mobs.json 既定で 3.0 を追加する
        // (敵対化した以上これが無いと Mob#doHurtTarget でクラッシュする)。ゾンビ/クリーパー/クモは元々持つ。
        event.put(ModEntities.PARASITIZED_COW.get(),
                MobStats.apply(Cow.createAttributes(), "parasitized_cow").build());
        event.put(ModEntities.PARASITIZED_SHEEP.get(),
                MobStats.apply(Sheep.createAttributes(), "parasitized_sheep").build());
        event.put(ModEntities.PARASITIZED_PIG.get(),
                MobStats.apply(Pig.createAttributes(), "parasitized_pig").build());
        event.put(ModEntities.PARASITIZED_ZOMBIE.get(),
                MobStats.apply(Zombie.createAttributes(), "parasitized_zombie").build());
        event.put(ModEntities.PARASITIZED_VILLAGER.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedVillager.createAttributes(), "parasitized_villager").build());
        event.put(ModEntities.PARASITIZED_CHICKEN.get(),
                MobStats.apply(net.minecraft.world.entity.animal.Chicken.createAttributes(), "parasitized_chicken").build());
        event.put(ModEntities.PARASITIZED_SKELETON.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedSkeleton.createAttributes(), "parasitized_skeleton").build());
        event.put(ModEntities.PARASITIZED_ENDERMAN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedEnderman.createAttributes(), "parasitized_enderman").build());
        event.put(ModEntities.PARASITIZED_BABY_ZOMBIE.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedBabyZombie.createAttributes(), "parasitized_baby_zombie").build());
        // ネザーの媒介種(2026-10-09)
        event.put(ModEntities.PARASITIZED_PIGLIN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedPiglin.createAttributes(), "parasitized_piglin").build());
        event.put(ModEntities.PARASITIZED_PIGLIN_BRUTE.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedPiglinBrute.createAttributes(), "parasitized_piglin_brute").build());
        event.put(ModEntities.PARASITIZED_ZOMBIFIED_PIGLIN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedZombifiedPiglin.createAttributes(), "parasitized_zombified_piglin").build());
        event.put(ModEntities.PARASITIZED_WITHER_SKELETON.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedWitherSkeleton.createAttributes(), "parasitized_wither_skeleton").build());
        event.put(ModEntities.PARASITIZED_HOGLIN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedHoglin.createAttributes(), "parasitized_hoglin").build());
        event.put(ModEntities.PARASITIZED_ZOGLIN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedZoglin.createAttributes(), "parasitized_zoglin").build());
        event.put(ModEntities.PARASITIZED_STRIDER.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedStrider.createAttributes(), "parasitized_strider").build());
        event.put(ModEntities.PARASITIZED_GHAST.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedGhast.createAttributes(), "parasitized_ghast").build());
        event.put(ModEntities.PARASITIZED_BLAZE.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedBlaze.createAttributes(), "parasitized_blaze").build());
        // 2026-10-10: 死体からだけ起き上がる 2 種
        event.put(ModEntities.PARASITIZED_IRON_GOLEM.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedIronGolem.createAttributes(), "parasitized_iron_golem").build());
        event.put(ModEntities.PARASITIZED_WARDEN.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.ParasitizedWarden.createAttributes(), "parasitized_warden").build());
        // G1 の作り直し 第 1 段(2026-10-22): 進化体 8 種
        event.put(ModEntities.VINEARM_ZOMBIE.get(), MobStats.apply(Zombie.createAttributes(), "vinearm_zombie").build());
        event.put(ModEntities.HOLLOW_VILLAGER.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.HollowVillager.createAttributes(), "hollow_villager").build());
        event.put(ModEntities.SPORESAC_COW.get(), MobStats.apply(Cow.createAttributes(), "sporesac_cow").build());
        event.put(ModEntities.PODDED_CREEPER.get(), MobStats.apply(Creeper.createAttributes(), "podded_creeper").build());
        event.put(ModEntities.VINE_EXECUTIONER.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.VineExecutioner.createAttributes(), "vine_executioner").build());
        event.put(ModEntities.ROOT_STRIKER.get(),
                MobStats.apply(com.yayuy.overgrownvegetation.entity.VineExecutioner.createAttributes(), "root_striker").build());
        event.put(ModEntities.WALKING_SPORANGIUM.get(), com.yayuy.overgrownvegetation.entity.WalkingSporangium.createAttributes().build());
        event.put(ModEntities.BURST_POD.get(), com.yayuy.overgrownvegetation.entity.BurstPod.createAttributes().build());
        event.put(ModEntities.PARASITIZED_CREEPER.get(),
                MobStats.apply(Creeper.createAttributes(), "parasitized_creeper").build());
        event.put(ModEntities.PARASITIZED_SPIDER.get(),
                MobStats.apply(Spider.createAttributes(), "parasitized_spider").build());
    }

    private ModEvents() {
    }
}
