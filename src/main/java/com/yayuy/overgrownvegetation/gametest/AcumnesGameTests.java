package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * アクムネス(グループ5の空の狙撃役、2026-09-28)のテスト。守りたいのはユーザーが決めたところ:
 * <ul>
 *     <li><b>防具を貫通する棘</b>(ネザライトを着ても 12 がそのまま入る)。</li>
 *     <li><b>狙いの予兆を見せてから撃つ</b>(狙い始めてすぐには撃たない。照準線は 2026-09-28 に消した)と、対処の<b>遮蔽物で射線を切れば撃たない</b>。</li>
 *     <li>G5(適応種)である。体格 1.8(ファルキムネスと同じサイズ感)。</li>
 * </ul>
 * テスト場は 7×7 しかないので、狙撃の通しは体格を 1.0 に縮める(針先から弾が出るので、体格 1.8 だと針先が標的より先に出る)。
 * 的はハスク(日光で燃えない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AcumnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AcumnesGameTests.class);
        }

        private Registration() {
        }
    }

    private static Husk armoredHusk(GameTestHelper helper, BlockPos pos) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, pos);
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        husk.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        husk.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        return husk;
    }

    /** 棘はネザライトの防具を貫いて 12 入る(普通のダメージならネザライトで半分以下になる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void spineIgnoresArmor(GameTestHelper helper) {
        Husk husk = armoredHusk(helper, new BlockPos(3, 2, 5));
        AcumnesEntity owner = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(1, 2, 1));
        Vec3 from = helper.absoluteVec(new Vec3(3.5D, 3.0D, 1.5D));
        AcumnesSpine spine = new AcumnesSpine(helper.getLevel(), owner, from, new Vec3(0.0D, 0.0D, 1.0D), 12.0F);
        helper.getLevel().addFreshEntity(spine);
        helper.runAfterDelay(10L, () -> {
            float lost = husk.getMaxHealth() - husk.getHealth();
            if (lost < 11.5F) {
                helper.fail("防具を貫いていない(減った体力 " + lost + "、12 のはず)");
                return;
            }
            if (spine.isAlive()) {
                helper.fail("当たった棘が消えていない");
                return;
            }
            helper.succeed();
        });
    }

    /** 味方(この MOD の Mob)には当たらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void spineIgnoresAllies(GameTestHelper helper) {
        AcumnesEntity ally = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(3, 2, 5));
        ally.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        Vec3 from = helper.absoluteVec(new Vec3(3.5D, 3.0D, 1.5D));
        AcumnesSpine spine = new AcumnesSpine(helper.getLevel(), null, from, new Vec3(0.0D, 0.0D, 1.0D), 12.0F);
        helper.getLevel().addFreshEntity(spine);
        helper.runAfterDelay(10L, () -> {
            if (ally.getHealth() < ally.getMaxHealth()) {
                helper.fail("味方のアクムネスに当たった");
                return;
            }
            helper.succeed();
        });
    }

    /** G5(適応種)で、体格 1.8・重力なしで湧く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnsAsFloatingG5(GameTestHelper helper) {
        AcumnesEntity a = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(3, 2, 3));
        if (!Adaptation.isAdaptive(a.getType())) {
            helper.fail("group_5 に入っていない(適応が効かない)");
            return;
        }
        if (Math.abs(a.getScale() - 1.8F) > 1.0E-3F) {
            helper.fail("体格が 1.8 でない: " + a.getScale());
            return;
        }
        if (!a.isNoGravity()) {
            helper.fail("重力がある(空を飛ぶ種)");
            return;
        }
        helper.succeed();
    }

    /**
     * 狙撃の通し: 狙い始めて 2 秒(狙いの予兆の間)は撃たず、そのあと撃って防具ごと貫く。
     * 体格 1.0(針の長さ 2.8 ブロック)で、標的は 5 ブロック先。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void aimsThenFires(GameTestHelper helper) {
        AcumnesEntity a = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(3, 2, 1));
        a.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        Husk husk = armoredHusk(helper, new BlockPos(3, 2, 6));
        a.forceAim(husk);
        helper.runAfterDelay(30L, () -> {
            if (husk.getHealth() < husk.getMaxHealth()) {
                helper.fail("狙いの予兆の途中(狙い始めて 1.5 秒)で当たっている");
            }
            if (!a.isAiming()) {
                helper.fail("狙いが続いていない(動作 " + a.getAction() + ")");
            }
        });
        helper.runAfterDelay(70L, () -> {
            float lost = husk.getMaxHealth() - husk.getHealth();
            if (lost < 11.5F) {
                helper.fail("撃っていない・防具を貫いていない(減った体力 " + lost + ")");
                return;
            }
            helper.succeed();
        });
    }

    /** 対処「遮蔽物で射線を切る」: 間に壁があれば撃たずに取りやめる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void wallCancelsTheShot(GameTestHelper helper) {
        AcumnesEntity a = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(3, 2, 1));
        a.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        Husk husk = armoredHusk(helper, new BlockPos(3, 2, 6));
        a.forceAim(husk);
        // 狙い始めてから壁を立てる(射線を切る)
        helper.runAfterDelay(10L, () -> {
            for (int x = 0; x < 7; x++) {
                for (int y = 1; y < 7; y++) {
                    helper.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
                }
            }
        });
        helper.runAfterDelay(80L, () -> {
            if (husk.getHealth() < husk.getMaxHealth()) {
                helper.fail("壁の向こうの標的に当たった");
                return;
            }
            if (a.isAiming() || a.getAction() == AcumnesEntity.ACT_FIRE) {
                helper.fail("射線を切っても狙い続けている(動作 " + a.getAction() + ")");
                return;
            }
            helper.succeed();
        });
    }
}
