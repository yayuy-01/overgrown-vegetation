package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 湧く場所の空き判定が<b>体格(SCALE)込みの大きさ</b>で見ているかのテスト(2026-09-23)。
 *
 * <p>SCALE 属性が当たり判定に掛かるのは湧いた後の最初の tick の終わりなので、湧かせる前の箱
 * ({@code EntityType#getSpawnAABB})と、世界に入った瞬間の当たり判定は素の大きさのまま。
 * 放っておくと素の大きさの隙間に湧いてから育って、丸太や壁に埋まる。直したのは 2 か所:
 * <ul>
 *     <li>{@code ModEntities} の {@code spawnDimensionsScale}({@code MobStats#spawnScale} = scale × scaleMax)</li>
 *     <li>{@link SpawnFit#unbury} が見る箱(SCALE 込みの {@code getDimensions})</li>
 * </ul>
 * ファルキムネス(体格 1.8、個体差なし)を使うのは、個体差のある種だと {@code MobSizeVariance} が
 * 世界に入った時点で当たり判定を作り直してしまい、直す前でも埋まらないため。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SpawnFitGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SpawnFitGameTests.class);
        }

        private Registration() {
        }
    }

    /** 立たせる足元(床は相対 y = 1 なので、その上)。 */
    private static final BlockPos FEET = new BlockPos(3, 2, 3);

    /**
     * mobs.json に載っている全種で、湧き判定の箱が<b>育ちうるいちばん大きな体</b>
     * (SCALE = 体格 × 個体差の上限)を包んでいる。新しい Mob を足して {@code spawnDimensionsScale} を
     * 付け忘れたら、体格か個体差のある種ならここで落ちる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnBoxCoversGrownBody(GameTestHelper helper) {
        List<String> bad = new ArrayList<>();
        for (String path : DesignData.mobs().knownIds()) {
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(OvergrownVegetation.id(path));
            if (type.isEmpty()) {
                continue;
            }
            Entity entity = type.get().create(helper.getLevel());
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            AttributeInstance scale = living.getAttribute(Attributes.SCALE);
            if (scale == null) {
                continue;
            }
            MobStat stat = DesignData.mobs().forId(path);
            scale.setBaseValue(scale.getBaseValue() * stat.scaleMax());
            EntityDimensions grown = living.getDimensions(living.getPose());
            AABB spawnBox = type.get().getSpawnAABB(0.0D, 0.0D, 0.0D);
            if (spawnBox.getXsize() + 1.0E-3D < grown.width() || spawnBox.getYsize() + 1.0E-3D < grown.height()) {
                bad.add(String.format("%s(湧き判定 %.2f x %.2f < 育った体 %.2f x %.2f)",
                        path, spawnBox.getXsize(), spawnBox.getYsize(), grown.width(), grown.height()));
            }
            entity.discard();
        }
        if (!bad.isEmpty()) {
            helper.fail("湧き判定の箱が育った体より小さい: " + String.join(", ", bad));
            return;
        }
        helper.succeed();
    }

    /** 素の大きさなら入るが、体格込みでは入らない隙間には湧かせない({@link SpawnFit#fits})。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void scaledMobDoesNotFitUnscaledGap(GameTestHelper helper) {
        EntityType<FalcimnesEntity> type = ModEntities.FALCIMNES.get();
        BlockPos feet = helper.absolutePos(FEET);
        if (!SpawnFit.fits(helper.getLevel(), type, feet)) {
            helper.fail("何も無いテスト場に湧けない(体格込みの箱がテスト場より大きい?)");
            return;
        }
        int ceiling = lowCeiling(helper, type);
        if (ceiling < 0) {
            return;
        }
        helper.setBlock(new BlockPos(FEET.getX(), ceiling, FEET.getZ()), Blocks.STONE);
        if (SpawnFit.fits(helper.getLevel(), type, feet)) {
            helper.fail("頭上 " + (ceiling - FEET.getY()) + " ブロックの天井の下に湧かせようとした(素の大きさで見ている)");
            return;
        }
        helper.succeed();
    }

    /**
     * 素の大きさなら入るが体格込みでは入らない所へ直に置いても({@code /summon} やスポーンエッグ相当)、
     * 世界に入った時点で {@link SpawnFit#unbury} が押し出し、育った後も埋まっていない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void unburyUsesGrownBody(GameTestHelper helper) {
        EntityType<FalcimnesEntity> type = ModEntities.FALCIMNES.get();
        int ceiling = lowCeiling(helper, type);
        if (ceiling < 0) {
            return;
        }
        // 真上の 1 ブロックだけ。横へ 2 ブロックずらせば体格込みの体(幅 2.97)でも外れる。
        helper.setBlock(new BlockPos(FEET.getX(), ceiling, FEET.getZ()), Blocks.STONE);
        FalcimnesEntity f = helper.spawnWithNoFreeWill(type, FEET);
        Vec3 spawned = Vec3.atBottomCenterOf(helper.absolutePos(FEET));
        if (f.position().distanceToSqr(spawned) < 1.0E-4D) {
            helper.fail("天井に頭が埋まる位置から動かしていない(素の大きさで見ている)");
            return;
        }
        // 2 tick 待って SCALE が当たり判定に掛かってから確かめる。
        helper.runAfterDelay(2L, () -> {
            if (f.getBbHeight() < type.getHeight() + 1.0E-3F) {
                helper.fail("2 tick 経っても当たり判定が素の大きさのまま(" + f.getBbHeight() + ")");
                return;
            }
            if (!helper.getLevel().noCollision(f, f.getBoundingBox())) {
                helper.fail("育った体がブロックに埋まっている " + f.getBoundingBox());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 素の体の頭より上で、体格込みの体の頭より下にある高さ(相対 y)。そういう高さが無い
     * (= 体格が小さくて、このテストで違いが出ない)なら失敗にして -1。
     * 体格は mobs.json から直に引く(確かめたい側の {@code getSpawnAABB} / {@code spawnScale} を使うと、
     * それが壊れたときに前提のほうで落ちて、本当に見たいところまで届かない)。
     */
    private static int lowCeiling(GameTestHelper helper, EntityType<?> type) {
        MobStat stat = DesignData.mobs().forId(EntityType.getKey(type).getPath());
        double grownScale = (stat.scale != null ? stat.scale : 1.0D) * stat.scaleMax();
        int ceiling = (int) Math.ceil(FEET.getY() + type.getHeight());
        double grownTop = FEET.getY() + type.getHeight() * grownScale;
        if (grownTop <= ceiling) {
            helper.fail("体格込みの頭(" + grownTop + ")が天井(" + ceiling + ")に届かず、このテストでは違いが出ない");
            return -1;
        }
        return ceiling;
    }

    private SpawnFitGameTests() {
    }
}
