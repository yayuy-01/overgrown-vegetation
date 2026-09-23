package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 「寄生植物装備」の毎 tick の処理と、周りの Mob の反応。仕様は {@link PlantArmor}。
 *
 * <ul>
 *   <li>{@link #onPlayerTick}: 経過時間 → 段階の昇格、食料の消費と過繫茂バイオームでの減り止め、耐久値の回復、
 *       2 段階目の定期的な空腹、3 段階目の歩いた場所の汚染・光合成・バイオームでの体力 +10(吸収)。</li>
 *   <li>{@link #onDeath}: Mob を倒すと食料 +4。</li>
 *   <li>{@link #onBreakSpeed}: 光合成中は採掘速度 +10%。</li>
 *   <li>{@link #onEntityJoin}: ゴーレムと中立 Mob(狼・ホッキョクグマ・ハチ…、飼われていないもの)が
 *       ホストを狙うようになる。敵対 Mob は元からプレイヤーを狙う。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlantArmorEvents {

    private static final ResourceLocation SPEED_ID = OvergrownVegetation.id("plant_armor_photosynthesis_speed");
    private static final ResourceLocation ATTACK_ID = OvergrownVegetation.id("plant_armor_photosynthesis_attack");
    /** 3 段階目の過繫茂バイオームでの体力上乗せ(吸収、5 ハート)。 */
    private static final float BIOME_ABSORPTION = 10.0F;

    private PlantArmorEvents() {
    }

    /** 装着時間と段階をクライアントへ({@link com.yayuy.overgrownvegetation.network.PlantArmorGrowthPayload})。 */
    private static void sendGrowth(ServerPlayer player, int ticks, PlantArmor.Stage stage) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.yayuy.overgrownvegetation.network.PlantArmorGrowthPayload(ticks, stage.number));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ItemStack stack = PlantArmor.worn(player);
        PlantArmorState state = player.getData(ModAttachments.PLANT_ARMOR_STATE);
        if (stack.isEmpty()) {
            if (state.active) {
                deactivate(player, state);
            }
            return;
        }
        var cfg = OvergrownVegetationConfig.COMMON;
        ServerLevel level = player.serverLevel();
        FoodData food = player.getFoodData();
        state.active = true;
        // フィロセンスの視線は過繫茂側には付かない。着けた瞬間に残っていたら消す(残っているとグループ 4 が襲いに来る)。
        if (player.hasEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.PHYLLOSENS_WATCH)) {
            player.removeEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.PHYLLOSENS_WATCH);
        }

        // 段階。
        // 成長: 通常は 1 tick ぶん。スポロフィルの胞子バフ中は 1.5 倍(端数は持ち越す、ユーザー指定 2026-09-18)。
        double rate = player.hasEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.SPOROPHYLL_SPORES)
                ? cfg.plantArmorSporophyllGrowthMultiplier.get() : 1.0;
        state.growthCarry += (float) rate;
        int grow = (int) state.growthCarry;
        state.growthCarry -= grow;
        int ticks = player.getData(ModAttachments.PLANT_ARMOR_TICKS) + grow;
        player.setData(ModAttachments.PLANT_ARMOR_TICKS, ticks);
        PlantArmor.Stage stage = PlantArmor.stageOf(stack);
        PlantArmor.Stage due = PlantArmor.stageForTicks(ticks);
        if (due.number > stage.number) {
            stack.set(ModDataComponents.PLANT_ARMOR_STAGE.get(), due.number);
            stage = due;
            sendGrowth(player, ticks, due);
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.plant_armor.stage." + due.number), false);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SCULK_CATALYST_BLOOM,
                    SoundSource.PLAYERS, 1.5F, 0.6F);
        }

        // 成長の進みはクライアントに同期されない添付データなので、HUD/ツールチップ用に毎秒送る。
        if (player.tickCount % 20 == 0) {
            sendGrowth(player, ticks, stage);
        }

        boolean inBiome = ModBiomes.isOvergrownLike(level.getBiome(player.blockPosition()));

        // 食料: バニラの減り(移動・回復など)を過繫茂バイオームでは段階ごとの倍率に抑え、装備の維持ぶんを足す。
        float exhaustion = food.getExhaustionLevel();
        if (inBiome && exhaustion > state.lastExhaustion && state.lastExhaustion >= 0.0F) {
            float grown = exhaustion - state.lastExhaustion;
            exhaustion = state.lastExhaustion + grown * (float) stage.biomeHungerFactor;
            food.setExhaustion(exhaustion);
        }
        float upkeep = (float) (cfg.plantArmorExhaustionPerSecond.get() / 20.0) * (float) (inBiome ? stage.biomeHungerFactor : 1.0);
        if (upkeep > 0.0F) {
            food.addExhaustion(upkeep);
        }
        state.lastExhaustion = food.getExhaustionLevel();

        // 耐久値の回復: バイオームの中では無償、外では食料と引き換え(食料が空なら回復しない)。
        int interval = Math.max(1, cfg.plantArmorRepairIntervalTicks.get());
        if (player.tickCount % interval == 0 && stack.getDamageValue() > 0) {
            if (inBiome) {
                stack.setDamageValue(stack.getDamageValue() - 1);
            } else if (food.getFoodLevel() > 0) {
                stack.setDamageValue(stack.getDamageValue() - 1);
                food.addExhaustion((float) stage.repairExhaustion);
            }
        }

        // 2 段階目以降: 定期的に空腹 II を 14 秒。
        if (stage.number >= 2) {
            int hungerInterval = Math.max(20, cfg.plantArmorHungerEffectIntervalSeconds.get() * 20);
            if (player.tickCount % hungerInterval == 0) {
                player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 14 * 20, 1, false, true));
            }
        }

        // 3 段階目: 歩いた場所の汚染、光合成、バイオームでの体力上乗せ。
        if (stage.number >= 3) {
            BlockPos pos = player.blockPosition();
            if (player.onGround() && !pos.equals(state.lastGroundPos)) {
                state.lastGroundPos = pos.immutable();
                if (level.random.nextDouble() < cfg.plantArmorWalkCorruptionChance.get()) {
                    CorruptionSpreader.corruptSingle(level, pos.below());
                }
            }
            boolean sun = level.isDay() && !level.isRaining() && level.canSeeSky(pos.above());
            setPhotosynthesis(player, state, sun);
            if (inBiome && player.tickCount % 20 == 0 && player.getAbsorptionAmount() < BIOME_ABSORPTION) {
                player.setAbsorptionAmount(Math.min(BIOME_ABSORPTION, player.getAbsorptionAmount() + 1.0F));
            }
        } else if (state.photosynthesis) {
            setPhotosynthesis(player, state, false);
        }
    }

    /** 光合成(移動速度・攻撃速度 +10%。採掘速度は {@link #onBreakSpeed}、汚染シダは {@link PlantArmor#fernSpawnMultiplier})。 */
    private static void setPhotosynthesis(ServerPlayer player, PlantArmorState state, boolean on) {
        if (state.photosynthesis == on) {
            return;
        }
        state.photosynthesis = on;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance attack = player.getAttribute(Attributes.ATTACK_SPEED);
        if (on) {
            if (speed != null) {
                speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, 0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            if (attack != null) {
                attack.addOrUpdateTransientModifier(new AttributeModifier(ATTACK_ID, 0.10, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.plant_armor.photosynthesis"), true);
        } else {
            if (speed != null) {
                speed.removeModifier(SPEED_ID);
            }
            if (attack != null) {
                attack.removeModifier(ATTACK_ID);
            }
        }
    }

    private static void deactivate(ServerPlayer player, PlantArmorState state) {
        setPhotosynthesis(player, state, false);
        state.active = false;
        state.lastExhaustion = -1.0F;
        state.lastGroundPos = null;
    }

    /** 装備が外れた(死亡で落ちた、コマンド等)ときに光合成の属性を外す。 */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() == EquipmentSlot.CHEST && event.getEntity() instanceof ServerPlayer player
                && event.getFrom().getItem() instanceof PlantArmorItem && !(event.getTo().getItem() instanceof PlantArmorItem)) {
            deactivate(player, player.getData(ModAttachments.PLANT_ARMOR_STATE));
        }
    }

    /** Mob を倒すと食料 +4(config {@code plantArmorKillFood})。 */
    /**
     * 死んでも「寄生植物装備」はアイテムとして落ちない(ユーザー指定 2026-09-18)。寄生しているので体から離れず、
     * ドロップから抜いて {@link ModAttachments#PLANT_ARMOR_KEPT} に預け、リスポーン後 {@link #onClone} で胸へ戻す。
     * keepInventory のときはインベントリごと引き継がれるので何もしない。
     */
    @SubscribeEvent
    public static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var it = event.getDrops().iterator();
        while (it.hasNext()) {
            net.minecraft.world.entity.item.ItemEntity drop = it.next();
            if (drop.getItem().getItem() instanceof PlantArmorItem) {
                player.setData(ModAttachments.PLANT_ARMOR_KEPT, drop.getItem().copy());
                it.remove();
                return;
            }
        }
    }

    /**
     * 「大きな未発達な果実」を食べると成長時間が 1 分進む(ユーザー指定 2026-09-18、config {@code plantArmorFruitGrowthSeconds})。
     * 果実そのものの効果は {@link com.yayuy.overgrownvegetation.effect.FruitEffects}。
     */
    @SubscribeEvent
    public static void onFinishEating(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItem().is(com.yayuy.overgrownvegetation.registry.ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM)) {
            return;
        }
        ItemStack stack = PlantArmor.worn(player);
        if (stack.isEmpty()) {
            return;
        }
        int ticks = player.getData(ModAttachments.PLANT_ARMOR_TICKS) + OvergrownVegetationConfig.COMMON.plantArmorFruitGrowthSeconds.get() * 20;
        player.setData(ModAttachments.PLANT_ARMOR_TICKS, ticks);
        sendGrowth(player, ticks, PlantArmor.stageOf(stack));
    }

    /** リスポーンしたプレイヤーの胸へ、{@link #onDrops} で預かった装備を戻す。 */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack kept = player.getData(ModAttachments.PLANT_ARMOR_KEPT);
        if (kept.isEmpty()) {
            kept = event.getOriginal().getData(ModAttachments.PLANT_ARMOR_KEPT);
        }
        if (kept.isEmpty()) {
            return;
        }
        player.removeData(ModAttachments.PLANT_ARMOR_KEPT);
        event.getOriginal().removeData(ModAttachments.PLANT_ARMOR_KEPT);
        if (player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            player.setItemSlot(EquipmentSlot.CHEST, kept);
        } else if (!player.getInventory().add(kept)) {
            player.drop(kept, false);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || event.getEntity() instanceof Player) {
            return;
        }
        if (PlantArmor.worn(player).isEmpty()) {
            return;
        }
        int food = OvergrownVegetationConfig.COMMON.plantArmorKillFood.get();
        if (food > 0) {
            player.getFoodData().eat(food, 0.4F);
        }
        // グループ 1〜3 か、過繫茂 Mob 以外(バニラなど)を倒すと、装備の成長時間が 10 秒ぶん進む(ユーザー指定 2026-09-18)。
        com.yayuy.overgrownvegetation.phase.MobGroup group = com.yayuy.overgrownvegetation.phase.MobGroup.of(event.getEntity());
        if (group == null || group.number() <= 3) {
            int bonus = OvergrownVegetationConfig.COMMON.plantArmorKillGrowthSeconds.get() * 20;
            player.setData(ModAttachments.PLANT_ARMOR_TICKS, player.getData(ModAttachments.PLANT_ARMOR_TICKS) + bonus);
        }
    }

    /**
     * 殴られた過繫茂 Mob(グループ1〜10)はホストに反撃しない(ユーザー指定 2026-09-18)。
     * 反撃・索敵のどの Goal も最後は {@code Mob#setTarget} を通るので、ここで 1 か所だけ止める。
     */
    @SubscribeEvent
    public static void onChangeTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Player || !PlantArmor.isHost(event.getNewAboutToBeSetTarget())) {
            return;
        }
        if (com.yayuy.overgrownvegetation.phase.MobGroup.of(event.getEntity()) != null) {
            event.setCanceled(true);
        }
    }

    /** 光合成中は採掘速度 +10%。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity().getData(ModAttachments.PLANT_ARMOR_STATE).photosynthesis) {
            event.setNewSpeed(event.getNewSpeed() * 1.10F);
        }
    }

    /**
     * ゴーレムと中立 Mob(飼われていないもの)にホストを狙う索敵を足す。敵対 Mob({@link Enemy})は元から
     * プレイヤーを狙うので何もしない。グループ1〜10 の過繫茂 Mob は {@code Predation} 側でホストを味方扱いする。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || mob instanceof Enemy) {
            return;
        }
        boolean golem = mob instanceof IronGolem || mob instanceof SnowGolem;
        boolean neutral = mob instanceof NeutralMob && !(mob instanceof TamableAnimal tamable && tamable.isTame());
        if (!golem && !neutral) {
            return;
        }
        mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, 10, true, false,
                candidate -> candidate instanceof Player p && !p.isCreative() && !p.isSpectator() && PlantArmor.isHost(p)
                        && !(mob instanceof TamableAnimal t && t.isTame())));
    }

    /** ホスト 1 人ぶんの一時状態(セーブしない)。 */
    public static final class PlantArmorState {
        boolean active;
        float lastExhaustion = -1.0F;
        /** 成長倍率の端数の持ち越し(1.5 倍なら 2 tick に 3 tick 進む)。 */
        float growthCarry;
        BlockPos lastGroundPos;
        public boolean photosynthesis;
    }

    /** 光合成中か(クライアント表示など向け。サーバー側の状態)。 */
    public static boolean isPhotosynthesizing(LivingEntity entity) {
        return entity instanceof Player player && player.getData(ModAttachments.PLANT_ARMOR_STATE).photosynthesis;
    }
}
