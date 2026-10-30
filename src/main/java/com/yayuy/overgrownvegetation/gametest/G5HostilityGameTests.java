package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * G5 は過繁茂側以外の全部に敵対する —— 2026-10-09 のユーザー報告「いくつかの mob が過繁茂 mob 以外に敵対していない場合がある」を調べて
 * 直した所の柵(種ごとの動きはそれぞれのテスト: ヴォラムネスの Mob の足音は {@code VoramnesGameTests}、フォディムネスの地上の個体は
 * {@code FodimnesGameTests})。ここは共通の標的選び({@link ParasitizedTargetGoal})の分。
 * 世界に足さない実体で、判定の式そのものを見る(並んで走る隣のテスト場の Mob に左右されない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G5HostilityGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G5HostilityGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new GameTestAssertException(message);
        }
    }

    private static <T extends LivingEntity> T make(EntityType<T> type, Level level, List<LivingEntity> made) {
        T e = type.create(level);
        check(e != null, "作れない: " + EntityType.getKey(type));
        made.add(e);
        return e;
    }

    /**
     * 標的選びは、殴っても傷つかない防具立てと、ParasitizedMob を名乗らない過繁茂側(ペディキュラなど。どれかのグループに属する)を外す
     * (外さないと同士討ちの打ち消し・防具立ての無傷でいつまでも殴り続け、近くの村人・動物・プレイヤーへ向かわなかった)。
     * 村人・牛・アイアンゴーレム・同盟軍の兵士は狙う(対照: 全部外れていたら索敵そのものが壊れている)。
     */
    @GameTest(template = TEMPLATE)
    public static void targetGoalSkipsUnhurtableAndOvergrownSide(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<LivingEntity> made = new ArrayList<>();
        try {
            check(ParasitizedTargetGoal.isAlwaysExcluded(make(EntityType.ARMOR_STAND, level, made)), "防具立てを狙う");
            check(ParasitizedTargetGoal.isAlwaysExcluded(make(ModEntities.PEDICULA.get(), level, made)), "ペディキュラ(G4)を狙う");
            check(!ParasitizedTargetGoal.isAlwaysExcluded(make(EntityType.VILLAGER, level, made)), "村人を狙わない");
            check(!ParasitizedTargetGoal.isAlwaysExcluded(make(EntityType.COW, level, made)), "牛を狙わない");
            check(!ParasitizedTargetGoal.isAlwaysExcluded(make(EntityType.IRON_GOLEM, level, made)), "アイアンゴーレムを狙わない");
            Mob soldier = make(AllyEntities.type(SoldierKind.RIFLEMAN), level, made);
            check(!ParasitizedTargetGoal.isAlwaysExcluded(soldier), "同盟軍の兵士を狙わない");
        } finally {
            made.forEach(LivingEntity::discard);
        }
        helper.succeed();
    }

    /**
     * 宙に浮く G5(アクムネス = 地面の 3〜8 上を漂う、エレボムネス = 大きな空洞の空中を泳ぐ)は索敵箱を上下 12 広げる
     * (バニラの ±4 だと下の Mob・プレイヤーを見つけられないことがあった)。地上を歩く G5(ファルキムネス)は広げない。
     */
    @GameTest(template = TEMPLATE)
    public static void airborneG5SearchFarBelow(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<LivingEntity> made = new ArrayList<>();
        try {
            check(ParasitizedTargetGoal.extraSearchHeight(make(ModEntities.ACUMNES.get(), level, made)) >= 12.0D,
                    "アクムネスの索敵箱が上下に広がっていない");
            check(ParasitizedTargetGoal.extraSearchHeight(make(ModEntities.EREBOMNES.get(), level, made)) >= 12.0D,
                    "エレボムネスの索敵箱が上下に広がっていない");
            check(ParasitizedTargetGoal.extraSearchHeight(make(ModEntities.FALCIMNES.get(), level, made)) == 0.0D,
                    "地上を歩くファルキムネスの索敵箱まで広がった");
        } finally {
            made.forEach(LivingEntity::discard);
        }
        helper.succeed();
    }

    /**
     * エレボムネスは狩れない相手を標的に選ばない(10-09、索敵箱を上下に広げたときの手当て): 洞窟の中に居るとき、空の見える所の相手と、
     * 見えないまま諦めたばかりの相手は選ばない(選んでは 5〜10 秒で外す、を繰り返して見える相手へ向かえなくなる)。洞窟の中の相手は選ぶ(対照)。
     * 東半分(x 4〜6)に石の屋根(y 5)を掛けて洞窟にし、空の光が落ち着くのを待つ(20 tick)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void erebomnesSkipsPreyItCannotHunt(GameTestHelper helper) {
        for (int x = 4; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new net.minecraft.core.BlockPos(x, 5, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        Level level = helper.getLevel();
        List<LivingEntity> made = new ArrayList<>();
        var ereb = make(ModEntities.EREBOMNES.get(), level, made);
        var inCave = make(EntityType.VILLAGER, level, made);
        var outside = make(EntityType.VILLAGER, level, made);
        place(helper, ereb, 5.5D, 2.0D, 3.5D);
        place(helper, inCave, 5.5D, 2.0D, 1.5D);
        place(helper, outside, 1.5D, 2.0D, 3.5D);
        helper.runAfterDelay(20L, () -> {
            try {
                check(ereb.canAttack(inCave), "洞窟の中の村人を選ばない");
                check(!ereb.canAttack(outside), "洞窟の中から、空の見える所の村人を選ぶ");
                ereb.giveUpUnseenForTests(inCave);
                check(!ereb.canAttack(inCave), "見えないまま諦めたばかりの相手をすぐ選び直す");
            } finally {
                made.forEach(LivingEntity::discard);
            }
            helper.succeed();
        });
    }

    private static void place(GameTestHelper helper, LivingEntity e, double x, double y, double z) {
        net.minecraft.world.phys.Vec3 p = helper.absoluteVec(new net.minecraft.world.phys.Vec3(x, y, z));
        e.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
    }

    private G5HostilityGameTests() {
    }
}
