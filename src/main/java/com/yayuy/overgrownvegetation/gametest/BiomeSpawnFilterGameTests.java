package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * <b>環境ごとの湧き分け</b>のテスト(ユーザー指定 2026-09-21)。
 *
 * <p>この規則は<b>ゲーム内で確かめにくい</b> —— 「湧かない」ことの確認には、湿地を歩き回って
 * 何も出ないのを長時間眺めるしかなく、たまたま出なかっただけなのか規則が効いているのか区別できない。
 * 表引き自体は純粋な関数なので、ここで確かめる。
 *
 * <p>どの種がどの環境に湧くかは {@code mobs.json} の {@code spawns}(マネージャーで編集する値)なので、
 * <b>中身そのものはテストしない</b> —— 調整するたびにテストが落ちては意味がない。見るのは仕組みの方:
 * <ul>
 *     <li>バイオームの環境 → 枠の対応({@link OvergrownSpawnProfile#placeOf})。
 *     湿地と菌湿地は同じ枠、海(環境なし)は専用の枠。ここが崩れると設定が別の枠に効く。</li>
 *     <li>{@code spawns} に書いていない場所では弾かれる —— 「空 = 制限なし」と読むと、
 *     分けたつもりの環境に全種が湧く。</li>
 *     <li>候補プールと 1 体ごとの判定({@link MobStats#spawnsIn})が<b>同じ答え</b>を返す ——
 *     食い違うと「候補には入るのに永久に湧かない種」ができ、その枠の目標個体数を食い潰す。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BiomeSpawnFilterGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BiomeSpawnFilterGameTests.class);
        }

        private Registration() {
        }
    }

    /** 環境 → 枠の対応。湿地と菌湿地はひとまとめ、環境の無い海は専用の枠。 */
    @GameTest(template = "gametest_empty7")
    public static void placeOfMapsEnvironments(GameTestHelper helper) {
        if (OvergrownSpawnProfile.placeOf(OvergrowthVariant.JUNGLE) != MobStat.SpawnPlace.JUNGLE) {
            helper.fail("密林が密林の枠になっていない");
            return;
        }
        for (OvergrowthVariant variant : List.of(OvergrowthVariant.SWAMP, OvergrowthVariant.FUNGAL_SWAMP)) {
            if (OvergrownSpawnProfile.placeOf(variant) != MobStat.SpawnPlace.SWAMP) {
                helper.fail(variant + " が湿地の枠になっていない");
                return;
            }
        }
        if (OvergrownSpawnProfile.placeOf((OvergrowthVariant) null) != MobStat.SpawnPlace.OCEAN) {
            helper.fail("環境の無いバイオーム(海)が海の枠になっていない");
            return;
        }
        helper.succeed();
    }

    /**
     * {@code spawns} に書いた場所でだけ湧ける。設定を持たない種(ハイヴノードのように
     * 専用の出現経路しか持たないもの)は、どの枠にも入らない。
     */
    @GameTest(template = "gametest_empty7")
    public static void spawnsDecideWherePerPlace(GameTestHelper helper) {
        for (int phase = 1; phase <= 10; phase++) {
            for (MobStat.SpawnPlace place : MobStat.SpawnPlace.values()) {
                for (EntityType<?> type : MobStats.overgrownSpawnPool(phase)) {
                    String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
                    boolean inData = DesignData.mobs().forId(path).spawns().of(place).contains(phase);
                    if (inData != MobStats.spawnsIn(type, phase, place)) {
                        helper.fail("F" + phase + " / " + place + " で " + path + " の判定が mobs.json と食い違う");
                        return;
                    }
                }
            }
        }
        EntityType<?> noSpawns = ModEntities.HIVE_NODE.get();
        for (int phase = 1; phase <= 10; phase++) {
            for (MobStat.SpawnPlace place : MobStat.SpawnPlace.values()) {
                if (MobStats.spawnsIn(noSpawns, phase, place)) {
                    helper.fail("spawns を持たない種が " + place + " に湧ける(空を「制限なし」と読んでいる)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** 候補プール(まとめて引く方)と 1 体ごとの判定が同じ答えを返すか。 */
    @GameTest(template = "gametest_empty7")
    public static void poolAndPerTypeCheckAgree(GameTestHelper helper) {
        for (int phase = 1; phase <= 10; phase++) {
            List<EntityType<?>> union = MobStats.overgrownSpawnPool(phase);
            for (MobStat.SpawnPlace place : List.of(MobStat.SpawnPlace.JUNGLE, MobStat.SpawnPlace.SWAMP,
                    MobStat.SpawnPlace.OCEAN)) {
                for (EntityType<?> type : DesignData.mobs().pool(phase, place)) {
                    if (!union.contains(type)) {
                        helper.fail("F" + phase + ": " + place + " に湧ける種が過繁茂バイオームの候補に入っていない");
                        return;
                    }
                    if (!MobStats.spawnsIn(type, phase, place)) {
                        helper.fail("F" + phase + ": プールと 1 体ごとの判定が食い違う");
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * 地上 / 地下({@code spawns.depth})が<b>仕組みとして</b>効いているか(2026-09-26)。
     * 中身(どの種が地下か)は {@code mobs.json} で変わるので値は固定しない。見るのは 3 つ:
     * <ul>
     *     <li>書いていない種は地上(既定に落ちる)。</li>
     *     <li>{@code isCave} =「地下にしか湧かない」—— 両方に湧く種を地表の処理から外さない
     *     (外すと、地上にも湧くはずの種が地表に出なくなる)。</li>
     *     <li>どちらか一方は必ず true(両方 false = どこにも湧けない種を作らない)。</li>
     * </ul>
     */
    @GameTest(template = "gametest_empty7")
    public static void depthDecidesSurfaceOrCave(GameTestHelper helper) {
        for (EntityType<?> type : MobStats.overgrownSpawnPool(10)) {
            boolean surface = MobStats.spawnsOnSurface(type);
            boolean cave = MobStats.spawnsInCaves(type);
            String id = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type));
            if (!surface && !cave) {
                helper.fail(id + ": 地上にも地下にも湧けない(depth の既定が効いていない)");
                return;
            }
            if (OvergrownSpawnProfile.isCave(type) != (cave && !surface)) {
                helper.fail(id + ": isCave が「地下にしか湧かない」になっていない");
                return;
            }
        }
        // 書いていない種は地上(spawns ごと無い種でも落ちないこと)。
        if (!MobStats.spawnsOnSurface(ModEntities.HIVE_NODE.get())
                || MobStats.spawnsInCaves(ModEntities.HIVE_NODE.get())) {
            helper.fail("depth を書いていない種の既定が「地上だけ」になっていない");
            return;
        }
        helper.succeed();
    }

    private BiomeSpawnFilterGameTests() {
    }
}
