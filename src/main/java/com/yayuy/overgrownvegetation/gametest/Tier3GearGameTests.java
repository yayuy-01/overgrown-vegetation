package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.gear.GearTierTable;
import com.yayuy.overgrownvegetation.gear.HeatBladeItem;
import com.yayuy.overgrownvegetation.gear.LeyArmorPowers;
import com.yayuy.overgrownvegetation.gear.LeyBoltEntity;
import com.yayuy.overgrownvegetation.gear.LeyCrystal;
import com.yayuy.overgrownvegetation.gear.ModGear;
import com.yayuy.overgrownvegetation.gear.SporeBurnerItem;
import com.yayuy.overgrownvegetation.gear.ThunderBladeItem;
import com.yayuy.overgrownvegetation.ley.LeyBatteryItem;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 段 3(2026-11-18): 結晶を電池から満たす・雷の飛び移り・熱の刃の焼き消し・杖の弾の爆ぜ・筒の扇・装甲の落下と押されない・作り方が研究の節につながる。
 * 期待値は {@code gear_tiers.json} から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class Tier3GearGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(Tier3GearGameTests.class);
        }

        private Registration() {
        }
    }

    private Tier3GearGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "tier3_gear";

    private static GearTierTable t() {
        return DesignData.gearTiers();
    }

    private static FakePlayer player(GameTestHelper helper, String name, BlockPos at) {
        FakePlayer p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        for (EquipmentSlot s : EquipmentSlot.values()) p.setItemSlot(s, ItemStack.EMPTY);
        Vec3 v = Vec3.atBottomCenterOf(helper.absolutePos(at));
        p.moveTo(v.x, v.y, v.z, 0.0F, 0.0F);
        return p;
    }

    private static Husk husk(GameTestHelper helper, BlockPos p) {
        Husk h = helper.spawnWithNoFreeWill(EntityType.HUSK, p);
        h.setNoAi(true);
        return h;
    }

    /** 電池から満たす(上限・電池が減る)・払う(足りなければ減らさない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void crystalPoursFromBatteryAndSpends(GameTestHelper helper) {
        ItemStack blade = new ItemStack(ModGear.THUNDER_BLADE.get());
        ItemStack battery = new ItemStack(ModLey.BATTERY.get());
        battery.set(ModLey.ENERGY.get(), LeyBatteryItem.capacity());
        int moved = LeyCrystal.pour(blade, battery);
        int want = Math.min(t().crystal.weaponCapacity, Math.min(LeyBatteryItem.capacity(), t().crystal.pourPerClick));
        if (moved != want || LeyCrystal.stored(blade) != want || LeyBatteryItem.stored(battery) != LeyBatteryItem.capacity() - want) {
            helper.fail("満たし: 移った " + moved + "(" + want + " のはず)・結晶 " + LeyCrystal.stored(blade) + "・電池 " + LeyBatteryItem.stored(battery));
            return;
        }
        LeyCrystal.set(blade, 10);
        if (LeyCrystal.spend(blade, 11) || LeyCrystal.stored(blade) != 10 || !LeyCrystal.spend(blade, 10) || LeyCrystal.stored(blade) != 0) {
            helper.fail("払い方がおかしい: " + LeyCrystal.stored(blade));
            return;
        }
        helper.succeed();
    }

    /** 雷: 狙った相手から並んだ敵へ jumps 回まで飛び移る。プレイヤーには飛ばない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void thunderChainsToNearbyFoes(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_t3_thunder", new BlockPos(1, 2, 1));
        Husk first = husk(helper, new BlockPos(1, 2, 5));
        Husk[] rest = {husk(helper, new BlockPos(3, 2, 5)), husk(helper, new BlockPos(5, 2, 5)), husk(helper, new BlockPos(5, 2, 3)), husk(helper, new BlockPos(5, 2, 1))};
        int n = ThunderBladeItem.chain(player, first);
        int want = Math.min(t().thunderBlade.jumps, rest.length);
        long hurt = java.util.Arrays.stream(rest).filter(h -> h.getHealth() < h.getMaxHealth()).count();
        if (n != want || hurt != want || first.getHealth() < first.getMaxHealth() || player.getHealth() < player.getMaxHealth()) {
            helper.fail("雷: 当てた " + n + "・傷ついた " + hurt + "(" + want + " のはず)・狙った相手 " + first.getHealth() + "・本人 " + player.getHealth());
            return;
        }
        helper.succeed();
    }

    /** 熱の刃: 刈った所とつながった刈れる植物を burnMax まで焼き消す(石は残す)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void heatBladeBurnsConnectedPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 3);
        for (int x = 2; x <= 5; x++) helper.setBlock(new BlockPos(x, 2, 3), Blocks.COBWEB);
        helper.setBlock(new BlockPos(5, 3, 3), Blocks.COBWEB);
        helper.setBlock(new BlockPos(1, 3, 3), Blocks.STONE);
        int n = HeatBladeItem.burnConnected(level, helper.absolutePos(origin), null);
        int want = Math.min(5, t().heatBlade.burnMax);
        if (n != want || !helper.getBlockState(new BlockPos(1, 3, 3)).is(Blocks.STONE)) {
            helper.fail("焼き消し: " + n + "(" + want + " のはず)");
            return;
        }
        helper.succeed();
    }

    /** 杖の弾: 爆ぜると半径の中の相手を傷つけて押し、撃った本人は傷つけない。結晶を払う。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void staffBoltBurstsAndPushes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = player(helper, "ov_t3_staff", new BlockPos(3, 2, 1));
        Husk near = husk(helper, new BlockPos(3, 2, 4));
        Husk far = husk(helper, new BlockPos(3, 2, 6));
        ItemStack staff = new ItemStack(ModGear.LEY_STAFF.get());
        LeyCrystal.set(staff, t().leyStaff.cost);
        LeyBoltEntity bolt = com.yayuy.overgrownvegetation.gear.LeyStaffItem.fire(level, player, staff);
        if (bolt == null || LeyCrystal.stored(staff) != 0) {
            helper.fail("撃てない・払っていない");
            return;
        }
        int n = bolt.burst(level, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3))).add(0, 0.5D, 0));
        boolean farHurt = t().leyStaff.radius < 2.9D && far.getHealth() < far.getMaxHealth();
        if (n < 1 || near.getHealth() >= near.getMaxHealth() || near.getDeltaMovement().z <= 0.0D || farHurt || player.getHealth() < player.getMaxHealth()) {
            helper.fail("爆ぜ: 当てた " + n + "・近い " + near.getHealth() + " 押し " + near.getDeltaMovement() + "・遠いのも当たった " + farHurt);
            return;
        }
        if (LeyCrystal.stored(staff) == 0 && com.yayuy.overgrownvegetation.gear.LeyStaffItem.fire(level, player, staff) != null) {
            helper.fail("結晶が空なのに撃てた");
            return;
        }
        helper.succeed();
    }

    /** 筒: 前の扇の相手を燃やし、扇の中の刈れる植物を焼き消す。後ろは焼かない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void burnerScorchesConeAhead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = player(helper, "ov_t3_burner", new BlockPos(3, 2, 2));
        player.setXRot(20.0F); // 少し下を向く(目の高さから床の植物へ)
        Husk front = husk(helper, new BlockPos(3, 2, 5));
        Husk back = husk(helper, new BlockPos(3, 2, 0));
        helper.setBlock(new BlockPos(3, 2, 4), Blocks.COBWEB);
        helper.setBlock(new BlockPos(2, 2, 0), Blocks.COBWEB);
        int[] r = SporeBurnerItem.pulse(level, player);
        if (r[1] < 1 || front.getRemainingFireTicks() <= 0 || back.getRemainingFireTicks() > 0) {
            helper.fail("扇の相手: 燃やした " + r[1] + "・前の火 " + front.getRemainingFireTicks() + "・後ろの火 " + back.getRemainingFireTicks());
            return;
        }
        if (helper.getBlockState(new BlockPos(3, 2, 4)).is(Blocks.COBWEB) || !helper.getBlockState(new BlockPos(2, 2, 0)).is(Blocks.COBWEB)) {
            helper.fail("扇の植物: 前 " + helper.getBlockState(new BlockPos(3, 2, 4)) + "・後ろ " + helper.getBlockState(new BlockPos(2, 2, 0)));
            return;
        }
        helper.succeed();
    }

    /** 装甲: 足は落下ダメージを結晶で消す(払える分だけ)・脚は押されない・切れたら止まる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void leyArmorAbsorbsFallAndKnockback(GameTestHelper helper) {
        Husk h = husk(helper, new BlockPos(3, 2, 3));
        ItemStack chest = new ItemStack(ModGear.LEY_CHESTPLATE.get());
        int per = t().leyArmorPower.fallCostPerDamage;
        LeyCrystal.set(chest, per * 5);
        h.setItemSlot(EquipmentSlot.CHEST, chest);
        h.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModGear.LEY_BOOTS.get()));
        h.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModGear.LEY_LEGGINGS.get()));
        ItemStack worn = h.getItemBySlot(EquipmentSlot.CHEST);
        float left = LeyArmorPowers.absorbFall(h, worn, 8.0F);
        if (Math.abs(left - 3.0F) > 1.0E-4F || LeyCrystal.stored(worn) != 0) {
            helper.fail("落下: 残り " + left + "(3 のはず)・結晶 " + LeyCrystal.stored(worn));
            return;
        }
        LeyCrystal.set(worn, t().leyArmorPower.knockbackCost);
        h.setDeltaMovement(Vec3.ZERO);
        h.knockback(1.0D, 1.0D, 0.0D);
        if (h.getDeltaMovement().lengthSqr() > 1.0E-6D || LeyCrystal.stored(worn) != 0) {
            helper.fail("押されない: 動き " + h.getDeltaMovement() + "・結晶 " + LeyCrystal.stored(worn));
            return;
        }
        h.knockback(1.0D, 1.0D, 0.0D);
        if (h.getDeltaMovement().lengthSqr() < 1.0E-4D) {
            helper.fail("結晶が空なのに押されない");
            return;
        }
        helper.succeed();
    }

    /** 胴のバフ(案 B): 1 秒ごとに払って再生 I と満腹の減りを遅く。結晶が切れたらバフだけ止まる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void leyChestBuffRegeneratesUntilEmpty(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_t3_buff", new BlockPos(3, 2, 3));
        GearTierTable.LeyArmorPower pw = t().leyArmorPower;
        if (!pw.regenEnabled) {
            helper.succeed(); // 設計値で切ってある
            return;
        }
        player.setHealth(player.getMaxHealth() * (float) Math.min(1.0D, pw.regenBelowHealth) - 1.0F);
        ItemStack chest = new ItemStack(ModGear.LEY_CHESTPLATE.get());
        LeyCrystal.set(chest, pw.regenCostPerSecond * 2);
        player.setItemSlot(EquipmentSlot.CHEST, chest);
        ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
        player.getFoodData().setExhaustion(1.0F);
        boolean a = LeyArmorPowers.tickBuff(player);
        float ex = player.getFoodData().getExhaustionLevel();
        boolean b = LeyArmorPowers.tickBuff(player);
        boolean c = LeyArmorPowers.tickBuff(player);
        boolean regen = player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
        player.removeAllEffects();
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        if (!a || !b || c || !regen || LeyCrystal.stored(worn) != 0 || Math.abs(ex - (1.0F - pw.hungerReliefPerSecond)) > 1.0E-4F) {
            helper.fail("バフ: 1 秒目 " + a + "・2 秒目 " + b + "・空で " + c + "・再生 " + regen + "・結晶 " + LeyCrystal.stored(worn) + "・消耗 " + ex);
            return;
        }
        helper.succeed();
    }

    /** 段 3 の作り方が読み込めて、研究の節(t3_armor など)が開く作り方に入っている。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void tier3RecipesLoadAndAreResearchGated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String[] ids = {"ley_helmet", "ley_chestplate", "ley_leggings", "ley_boots", "thunder_blade", "heat_blade", "ley_staff", "spore_burner"};
        StringBuilder bad = new StringBuilder();
        String tree;
        try (var in = Tier3GearGameTests.class.getResourceAsStream("/overgrown_vegetation/design/research_tree.json")) {
            tree = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            tree = "";
        }
        for (String id : ids) {
            if (level.getRecipeManager().byKey(OvergrownVegetation.id(id)).isEmpty()) bad.append(id).append(" の作り方が無い / ");
            if (!tree.contains("\"overgrown_vegetation:" + id + "\"")) bad.append(id).append(" が研究の節に無い / ");
        }
        if (bad.length() > 0) {
            helper.fail(bad.toString());
            return;
        }
        helper.succeed();
    }
}
