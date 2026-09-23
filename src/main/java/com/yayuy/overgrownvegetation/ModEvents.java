package com.yayuy.overgrownvegetation;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.entity.HiveVineEntity;
import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.SporophyllEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import com.yayuy.overgrownvegetation.entity.XenacanthusEntity;
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
        // 汚染水草は汚染シダと同じ既定値だが、mobs.json では別 id("blight_water_grass")として編集できる。
        event.put(ModEntities.BLIGHT_WATER_GRASS.get(),
                BlightFernEntity.createAttributes("blight_water_grass").build());
        event.put(ModEntities.OVERGROWN_SHIELDER.get(), OvergrownShielderEntity.createAttributes().build());
        event.put(ModEntities.FALCIMNES.get(), FalcimnesEntity.createAttributes().build());
        event.put(ModEntities.PHALANGIOTARBUS.get(), PhalangiotarbusEntity.createAttributes().build());
        event.put(ModEntities.OVERGROWN_NEXUS.get(), OvergrownNexusEntity.createAttributes().build());
        event.put(ModEntities.DORGA_ARCHELAUS_TITANICA.get(), com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity.createAttributes().build());
        event.put(ModEntities.HIVE_NODE.get(), HiveNodeEntity.createAttributes().build());
        event.put(ModEntities.HIVE_VINE.get(), HiveVineEntity.createAttributes().build());

        // テスト Mob — グループ2(原生生産種)/ グループ3(原生消費種)。
        event.put(ModEntities.PROTOPHASMA.get(), ProtophasmaEntity.createAttributes().build());
        event.put(ModEntities.ARTHROPLEURA.get(), ArthropleuraEntity.createAttributes().build());
        event.put(ModEntities.SPELAEOPLEURA.get(), SpelaeopleuraEntity.createAttributes().build());
        event.put(ModEntities.COELUROSAURAVUS.get(), CoelurosauravusEntity.createAttributes().build());
        event.put(ModEntities.LITHOPLEURA.get(), LithopleuraEntity.createAttributes().build());
        event.put(ModEntities.STENODICTYA_LOBATA.get(), StenodictyaLobataEntity.createAttributes().build());
        event.put(ModEntities.MEGANEURA.get(), MeganeuraEntity.createAttributes().build());
        event.put(ModEntities.ARBORA.get(), ArboraEntity.createAttributes().build());
        event.put(ModEntities.ARBORA_QUEEN.get(),
                com.yayuy.overgrownvegetation.entity.ArboraQueenEntity.createAttributes().build());
        event.put(ModEntities.NEST_CORE.get(),
                com.yayuy.overgrownvegetation.entity.NestCoreEntity.createAttributes().build());
        event.put(ModEntities.PHYLLOSENS.get(), PhyllosensEntity.createAttributes().build());
        event.put(ModEntities.VELOPHYLL.get(), VelophyllEntity.createAttributes().build());
        event.put(ModEntities.SPOROPHYLL.get(), SporophyllEntity.createAttributes().build());
        event.put(ModEntities.PETROLACOSAURUS.get(), PetrolacosaurusEntity.createAttributes().build());
        event.put(ModEntities.IANTHASAURUS.get(), IanthasaurusEntity.createAttributes().build());
        event.put(ModEntities.HYLONOMUS.get(), HylonomusEntity.createAttributes().build());
        event.put(ModEntities.RHIZODUS.get(), RhizodusEntity.createAttributes().build());
        event.put(ModEntities.XENACANTHUS.get(), XenacanthusEntity.createAttributes().build());
        event.put(ModEntities.ORTHACANTHUS.get(), OrthacanthusEntity.createAttributes().build());
        event.put(ModEntities.MEGARACHNE.get(), MegarachneEntity.createAttributes().build());

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
        event.put(ModEntities.PARASITIZED_CREEPER.get(),
                MobStats.apply(Creeper.createAttributes(), "parasitized_creeper").build());
        event.put(ModEntities.PARASITIZED_SPIDER.get(),
                MobStats.apply(Spider.createAttributes(), "parasitized_spider").build());
    }

    private ModEvents() {
    }
}
