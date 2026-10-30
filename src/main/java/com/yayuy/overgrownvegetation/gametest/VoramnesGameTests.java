package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import com.yayuy.overgrownvegetation.entity.VoramnesHeadPart;
import com.yayuy.overgrownvegetation.entity.VoramnesSenses;
import com.yayuy.overgrownvegetation.entity.VoramnesTerrain;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * ヴォラムネス(グループ5の地中の種、2026-10-01)のテスト。守りたいのは採用の案(ユーザー指定):
 * <ul>
 *     <li><b>走る・跳ぶ振動で気付き、スニークには気付かない</b>({@link #noiseRangeRules}・{@link #sneakingIsNotHeardRunningIs})。</li>
 *     <li><b>真下から丸呑み</b>(振動の点へ地中を泳ぎ、花を開いて突き上げて呑む: {@link #huntsTheNoiseAndSwallowsFromBelow})。
 *     地上に出ている間は正面から呑む({@link #surfacedWormSwallowsFromTheFront})。</li>
 *     <li><b>中で継続ダメージ(防具貫通)、中から一定量殴ると吐き出す</b>({@link #digestIgnoresArmorAndSpitsWhenHitFromInside})。</li>
 *     <li>地中をすり抜けるが<b>ブロックは壊さない・窒息しない</b>({@link #neverBreaksBlocksNorSuffocates})。</li>
 * </ul>
 * ほかに、死のアニメの選び方・状態ごとの当たり判定・呑んでいる途中の保存と読み込み・湧きの関門・頭の部位。
 *
 * <p>テスト場(gametest_empty7)は床が y=1 の石 1 層だけなので、潜れる地面({@code VoramnesTerrain.SOLID_DEPTH} = 3 マス)を作るため
 * y=2・3 を土で埋めて、地表を y=4 にする({@link #layGround})。上の空きは y=4〜7 の 4 マス(地上に出る・花を開くのに要る空き 4 と同じ)。
 * 体格は本番の 2 のまま(呑まれたハスクの頭は天井のバリアに入るが、呑まれている間は壁の中のダメージを受けない —— それも確かめている)。
 * 的はハスク(日光で燃えない)。振動は全体へ配らず、個体の {@link VoramnesEntity#onVibration} を直接呼ぶ(並列のほかのテストの個体に聞かせない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VoramnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(VoramnesGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SURFACE_Y = 4;
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    /** y=2・3 を土で埋める(床の石 y=1 と合わせて 3 マスの潜れる地面、地表は y=4)。 */
    private static void layGround(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.DIRT);
            }
        }
    }

    /**
     * テストの個体。Mob の振動は聞かない(2026-10-09 に Mob の足音も聞くようにしたが、テスト場は並んで同時に走るので、隣のテスト場の Mob の足音・
     * 着地(跳んだ着地は 24 ブロック届く)で頼んでいない相手を追い始める)。Mob の振動を見るテストだけ有効にする。
     */
    private static VoramnesEntity worm(GameTestHelper helper, int x, int z) {
        VoramnesEntity w = helper.spawn(ModEntities.VORAMNES.get(), new BlockPos(x, SURFACE_Y, z));
        w.setHearMobsForTests(false);
        return w;
    }

    private static Husk stillHusk(GameTestHelper helper, int x, int z) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(x, SURFACE_Y, z));
        husk.setNoAi(true);
        return husk;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new GameTestAssertException(message);
        }
    }

    // ------------------------------------------------------------------ 振動

    /** 振動の届く距離の決まり: 走る・跳ぶは遠く、歩くは近く、スニーク・羊毛の上・クリエイティブは聞こえない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void noiseRangeRules(GameTestHelper helper) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        double loud = VoramnesSenses.LOUD_RANGE;
        double quiet = VoramnesSenses.QUIET_RANGE;
        check(VoramnesSenses.noiseRange(p, false, DIRT) == quiet, "歩く足音が近距離の振動になっていない");
        p.setSprinting(true);
        check(VoramnesSenses.noiseRange(p, false, DIRT) == loud, "走る足音が遠くまで届く振動になっていない");
        p.setShiftKeyDown(true);
        check(VoramnesSenses.noiseRange(p, false, DIRT) == 0.0D, "スニークの足音が聞こえている");
        p.fallDistance = 1.2F;
        check(VoramnesSenses.noiseRange(p, true, DIRT) == 0.0D, "スニークの着地が聞こえている");
        p.setShiftKeyDown(false);
        check(VoramnesSenses.noiseRange(p, false, Blocks.WHITE_WOOL.defaultBlockState()) == 0.0D, "羊毛の上の足音が聞こえている");
        p.setSprinting(false);
        check(VoramnesSenses.noiseRange(p, true, DIRT) == loud, "跳んで着地した振動が遠くまで届かない");
        p.fallDistance = 0.3F;
        check(VoramnesSenses.noiseRange(p, true, DIRT) == quiet, "小さな段差の着地が近距離の振動になっていない");
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        creative.setSprinting(true);
        check(VoramnesSenses.noiseRange(creative, false, DIRT) == 0.0D, "クリエイティブの足音が聞こえている");
        helper.succeed();
    }

    /** 個体の耳: スニークは近くでも聞こえず、歩く足音は近くだけ、走る足音は遠くから聞こえて、その点を追い始める。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void sneakingIsNotHeardRunningIs(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 near = worm.position().add(3.0D, 0.0D, 0.0D);
        p.setShiftKeyDown(true);
        p.setSprinting(true);
        check(!worm.onVibration(near, p, false, DIRT) && !worm.isHunting(), "3 ブロック先のスニークに気付いた");
        p.setShiftKeyDown(false);
        p.setSprinting(false);
        Vec3 walkFar = worm.position().add(VoramnesSenses.QUIET_RANGE + 4.0D, 0.0D, 0.0D);
        check(!worm.onVibration(walkFar, p, false, DIRT) && !worm.isHunting(), "遠くの歩く足音に気付いた");
        p.setSprinting(true);
        Vec3 runFar = worm.position().add(0.0D, 0.0D, VoramnesSenses.LOUD_RANGE - 4.0D);
        check(worm.onVibration(runFar, p, false, DIRT), "走る足音に気付かない(" + (VoramnesSenses.LOUD_RANGE - 4.0D) + " ブロック)");
        check(worm.isHunting() && worm.getNoisePos() != null && worm.getNoisePos().distanceTo(runFar) < 1.0E-3D,
                "聞いた点を追っていない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 呑む

    /** 振動の点へ地中を泳いで行き、真下から花を開いて突き上げ、そこに立っている相手を呑む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void huntsTheNoiseAndSwallowsFromBelow(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 1, 1);
        Husk husk = stillHusk(helper, 5, 5);
        worm.hearVibration(husk.position(), husk);
        helper.succeedWhen(() -> {
            check(husk.getVehicle() == worm, "呑んでいない(行動 " + VoramnesEntity.animationName(worm.getAction())
                    + "、水平の距離 " + String.format("%.2f", Math.hypot(worm.getX() - husk.getX(), worm.getZ() - husk.getZ())) + ")");
            int act = worm.getAction();
            check(act == VoramnesEntity.ACT_ERUPT || act == VoramnesEntity.ACT_DIGEST,
                    "呑んだのに突き上げ・消化の行動でない: " + VoramnesEntity.animationName(act));
        });
    }

    /** 地上に出ている間は、正面の相手へ向いて口を開き(open → gape)、呑みに行く(swallow)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void surfacedWormSwallowsFromTheFront(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 0);
        worm.forceAction(VoramnesEntity.ACT_IDLE);
        Husk husk = stillHusk(helper, 3, 3);
        Set<Integer> seen = new HashSet<>();
        helper.onEachTick(() -> seen.add(worm.getAction()));
        helper.succeedWhen(() -> {
            check(husk.getVehicle() == worm, "正面の相手を呑んでいない(行動 " + VoramnesEntity.animationName(worm.getAction()) + ")");
            check(seen.contains(VoramnesEntity.ACT_OPEN) && seen.contains(VoramnesEntity.ACT_SWALLOW),
                    "口を開く予兆(open)を見せずに呑んだ");
        });
    }

    /** 胃の中の継続ダメージは防具を貫く。中から一定量殴ると吐き出す(乗客から外れ、生きている)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void digestIgnoresArmorAndSpitsWhenHitFromInside(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true); // 行動(消化・吐き出し)は aiStep で進む
        Husk husk = stillHusk(helper, 3, 5);
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        husk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        husk.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        husk.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        if (!worm.forceSwallow(husk)) {
            helper.fail("ハスクを呑めない");
            return;
        }
        float start = husk.getHealth();
        helper.runAfterDelay(22L, () -> {
            float lost = start - husk.getHealth();
            float expected = worm.digestDamage();
            check(husk.getVehicle() == worm, "消化の途中で外に出ている");
            check(Math.abs(lost - expected) < 0.05F, "胃の中のダメージが防具を貫いていない(減った " + lost + "、" + expected + " のはず)");
            // 中から殴る(ハスクが本体を殴った体。吐き出す量を十分に超える)
            DamageSource punch = worm.damageSources().mobAttack(husk);
            worm.hurt(punch, 30.0F);
            check(worm.getAction() == VoramnesEntity.ACT_SPIT,
                    "中から殴っても吐き出しに入らない(行動 " + VoramnesEntity.animationName(worm.getAction()) + ")");
        });
        helper.runAfterDelay(60L, () -> {
            check(!husk.isPassenger(), "吐き出していない");
            check(husk.isAlive(), "吐き出された相手が死んでいる");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 地形

    /** 地中を泳いでもブロックを壊さず、土の中に埋まっていても窒息しない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void neverBreaksBlocksNorSuffocates(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity swimmer = worm(helper, 1, 1);
        Vec3 start = swimmer.position();
        swimmer.hearVibration(helper.absoluteVec(new Vec3(5.5D, SURFACE_Y, 5.5D)), null);
        VoramnesEntity buried = helper.spawn(ModEntities.VORAMNES.get(), new BlockPos(5, 2, 1));
        buried.setNoAi(true); // 土の中に置いたまま動かさない
        // 世界に入った後で土の中へ置き直す(湧いた Mob を空きへ押し出す SpawnFit#unbury の有無によらず、確かに土の中で確かめる)
        Vec3 inDirt = helper.absoluteVec(new Vec3(5.5D, 2.0D, 1.5D));
        buried.moveTo(inDirt.x, inDirt.y, inDirt.z, 0.0F, 0.0F);
        int dirtY = helper.absolutePos(new BlockPos(5, 2, 1)).getY();
        helper.runAfterDelay(60L, () -> {
            check(buried.getBlockY() == dirtY, "前提: 埋めた個体が土の中に居ない(y " + buried.getBlockY() + ")");
            for (int x = 0; x < 7; x++) {
                for (int z = 0; z < 7; z++) {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 1, z));
                    helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, 2, z));
                    helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, 3, z));
                }
            }
            check(swimmer.position().distanceTo(start) > 2.0D, "地中を進んでいない(" + swimmer.position().distanceTo(start) + ")");
            check(swimmer.getHealth() >= swimmer.getMaxHealth(), "泳いだ個体が傷ついた: " + swimmer.getHealth());
            check(buried.getHealth() >= buried.getMaxHealth(), "土の中の個体が窒息した: " + buried.getHealth());
            check(!buried.isInWall(), "土の中で壁の中と判定されている");
            check(!buried.hurt(buried.damageSources().inWall(), 5.0F), "壁の中のダメージを受けた");
            helper.succeed();
        });
    }

    /** 湧きの関門: 下に潜れる地面が 3 マス続く乾いた地表だけ。薄い床の上・水の中は断る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckNeedsDeepDryGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A: 土 2 段(+ 床の石)の上 = 地表 y=4
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.DIRT);
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.DIRT);
        // B: 宙に浮いた土 1 つ(下は空気)の上 = 地表 y=5 だが下が薄い
        helper.setBlock(new BlockPos(3, 4, 3), Blocks.DIRT);
        // C: 土 2 段の上が水
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.DIRT);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.DIRT);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.WATER);
        VoramnesEntity w = ModEntities.VORAMNES.get().create(level);
        check(w != null, "作れない");
        BlockPos a = helper.absolutePos(new BlockPos(1, SURFACE_Y, 1));
        w.moveTo(a.getX() + 0.5D, a.getY(), a.getZ() + 0.5D, 0.0F, 0.0F);
        check(w.acceptOvergrownSpawn(level), "深い地面の上に湧けない");
        check(w.getBlockY() == a.getY() && w.getAction() == VoramnesEntity.ACT_STALK, "地中(stalk)のまま地表に置かれていない");
        BlockPos b = helper.absolutePos(new BlockPos(3, 5, 3));
        w.moveTo(b.getX() + 0.5D, b.getY(), b.getZ() + 0.5D, 0.0F, 0.0F);
        check(!w.acceptOvergrownSpawn(level), "薄い床の上に湧いた");
        BlockPos c = helper.absolutePos(new BlockPos(5, SURFACE_Y, 5));
        w.moveTo(c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, 0.0F, 0.0F);
        check(!w.acceptOvergrownSpawn(level), "水の中に湧いた");
        w.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ 状態・描画の約束

    /** 死のアニメは今の状態で選ぶ(NOTES の表)。行動番号とアニメ 20 本は 1 対 1。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void deathAnimationFollowsState(GameTestHelper helper) {
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_IDLE, 1.0F) == VoramnesEntity.ACT_DEATH, "地上 → death");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_FLINCH, 0.2F) == VoramnesEntity.ACT_DEATH, "被弾 → death");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_GAPE, 0.5F) == VoramnesEntity.ACT_DEATH_OPEN, "口を開いている → death_open");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_SWALLOW, 0.2F) == VoramnesEntity.ACT_DEATH_OPEN, "呑みに行く途中 → death_open");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_DIGEST, 1.0F) == VoramnesEntity.ACT_DEATH_DIGEST, "呑んでいる → death_digest");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_DIGEST_KICK, 0.1F) == VoramnesEntity.ACT_DEATH_DIGEST, "中から殴られた → death_digest");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_LURK, 0.5F) == VoramnesEntity.ACT_DEATH_BURIED, "地面の花 → death_buried");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_LURK_FLINCH, 0.1F) == VoramnesEntity.ACT_DEATH_BURIED, "花の被弾 → death_buried");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_AMBUSH, 0.6F) == VoramnesEntity.ACT_DEATH_BURIED, "花を開いた → death_buried");
        check(VoramnesEntity.deathActionFor(VoramnesEntity.ACT_STALK, 0.0F) == VoramnesEntity.ACT_DEATH_STALK, "地中 → death_stalk");
        String[] expected = {"stalk", "emerge", "idle", "submerge", "open", "gape", "swallow", "digest", "digest_kick", "spit",
                "ambush", "lurk", "lurk_flinch", "erupt", "flinch", "death", "death_open", "death_digest", "death_buried", "death_stalk"};
        check(VoramnesEntity.actionCount() == expected.length, "行動の数がアニメの数と違う");
        for (int i = 0; i < expected.length; i++) {
            check(expected[i].equals(VoramnesEntity.animationName(i)), "行動 " + i + " のアニメが " + VoramnesEntity.animationName(i));
        }
        // 地上に出ている間だけ地面の下の胴(tail1)を隠す
        check(VoramnesEntity.hidesTail(VoramnesEntity.ACT_IDLE) && VoramnesEntity.hidesTail(VoramnesEntity.ACT_DIGEST)
                && !VoramnesEntity.hidesTail(VoramnesEntity.ACT_STALK) && !VoramnesEntity.hidesTail(VoramnesEntity.ACT_EMERGE)
                && !VoramnesEntity.hidesTail(VoramnesEntity.ACT_LURK), "tail1 を隠す行動が NOTES と違う");
        // 地中・地面の花の間は、地面の下に埋まった胴の箱を隠す(出入り・突き上げ・地上の間は描く)
        check(VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_STALK) && VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_LURK)
                && VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_AMBUSH) && VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_DEATH_STALK)
                && !VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_EMERGE) && !VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_ERUPT)
                && !VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_SUBMERGE) && !VoramnesEntity.hidesBuriedTrunk(VoramnesEntity.ACT_IDLE),
                "地面の下の胴を隠す行動が違う");
        helper.succeed();
    }

    /**
     * 外からの被弾のアニメは今の状態で選ぶ(NOTES の表): 地上に立っている(idle・emerge の終わり際・submerge の出だし)= flinch、
     * 地面の花(lurk・開き切った ambush)= lurk_flinch、呑んでいる = digest_kick、口を開いている・地中・突き上げ = 止めない。
     * 実体でも、地上の個体を殴ると flinch に入る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hurtReactionFollowsState(GameTestHelper helper) {
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_IDLE, 1.0F) == VoramnesEntity.ACT_FLINCH, "地上 → flinch");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_EMERGE, 1.4F) == VoramnesEntity.ACT_FLINCH, "出切る間際 → flinch");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_EMERGE, 0.4F) == -1, "地面を割っている途中で flinch に飛んだ");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_SUBMERGE, 0.1F) == VoramnesEntity.ACT_FLINCH, "潜り始め → flinch");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_SUBMERGE, 1.0F) == -1, "沈みかけで flinch に飛んだ");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_LURK, 0.5F) == VoramnesEntity.ACT_LURK_FLINCH, "地面の花 → lurk_flinch");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_AMBUSH, 0.7F) == VoramnesEntity.ACT_LURK_FLINCH, "開き切った花 → lurk_flinch");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_AMBUSH, 0.2F) == -1, "開きかけの花で lurk_flinch に飛んだ");
        check(VoramnesEntity.hurtReactionFor(VoramnesEntity.ACT_DIGEST, 1.0F) == VoramnesEntity.ACT_DIGEST_KICK, "呑んでいる → digest_kick");
        int[] keep = {VoramnesEntity.ACT_STALK, VoramnesEntity.ACT_OPEN, VoramnesEntity.ACT_GAPE, VoramnesEntity.ACT_SWALLOW,
                VoramnesEntity.ACT_SPIT, VoramnesEntity.ACT_ERUPT, VoramnesEntity.ACT_FLINCH, VoramnesEntity.ACT_LURK_FLINCH};
        for (int act : keep) {
            check(VoramnesEntity.hurtReactionFor(act, 0.3F) == -1, VoramnesEntity.animationName(act) + " を被弾で止めた");
        }
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        // 2026-10-09 から地上で口を閉じて待つ間は傷を受けない(flinch は被弾では流れない)。呑んでいる間は受けて digest_kick
        worm.forceAction(VoramnesEntity.ACT_IDLE);
        check(!worm.hurt(worm.damageSources().magic(), 1.0F), "地上で口を閉じて待つ個体に当たった");
        check(worm.getAction() == VoramnesEntity.ACT_IDLE, "当たらない被弾で行動が変わった: " + VoramnesEntity.animationName(worm.getAction()));
        worm.forceAction(VoramnesEntity.ACT_DIGEST);
        // 同じ tick の同じダメージ源は 1 回にまとめる(本体と頭の部位)ので、別のダメージ源で殴る
        check(worm.hurt(worm.damageSources().generic(), 1.0F), "呑んでいる個体に当たらない");
        check(worm.getAction() == VoramnesEntity.ACT_DIGEST_KICK, "呑んでいる間に殴られても digest_kick に入らない: "
                + VoramnesEntity.animationName(worm.getAction()));
        helper.succeed();
    }

    /**
     * 傷を受けるのは口を開けた時と、呑んで体を出している時だけ(2026-10-09 ユーザー指定)。地中・地面の花(潜伏)・地上で口を閉じている・出入りは受けない。
     * open は溜め(蕾を締めて反る)の後から、swallow は花弁が閉じるまで(獲物を抱えていれば閉じた後も)、spit は口が閉じるまで。
     * {@code /kill}(奈落と同じ BYPASSES_INVULNERABILITY)は通す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void takesDamageOnlyWithMouthOpenOrPreyInside(GameTestHelper helper) {
        int[] closed = {VoramnesEntity.ACT_STALK, VoramnesEntity.ACT_AMBUSH, VoramnesEntity.ACT_LURK, VoramnesEntity.ACT_IDLE,
                VoramnesEntity.ACT_EMERGE, VoramnesEntity.ACT_SUBMERGE, VoramnesEntity.ACT_FLINCH, VoramnesEntity.ACT_ERUPT};
        for (int act : closed) {
            check(!VoramnesEntity.exposedAction(act, 0.5F, false), VoramnesEntity.animationName(act) + " で傷を受ける");
        }
        int[] open = {VoramnesEntity.ACT_GAPE, VoramnesEntity.ACT_DIGEST, VoramnesEntity.ACT_DIGEST_KICK};
        for (int act : open) {
            check(VoramnesEntity.exposedAction(act, 0.5F, false), VoramnesEntity.animationName(act) + " で傷を受けない");
        }
        check(!VoramnesEntity.exposedAction(VoramnesEntity.ACT_OPEN, 0.2F, false), "open の溜め(口を閉じている)で傷を受ける");
        check(VoramnesEntity.exposedAction(VoramnesEntity.ACT_OPEN, 0.5F, false), "open で口を開いた後に傷を受けない");
        check(VoramnesEntity.exposedAction(VoramnesEntity.ACT_SWALLOW, 0.1F, false), "swallow の口が開いている間に傷を受けない");
        check(!VoramnesEntity.exposedAction(VoramnesEntity.ACT_SWALLOW, 0.8F, false), "空振りの swallow の閉じた後に傷を受ける");
        check(VoramnesEntity.exposedAction(VoramnesEntity.ACT_SWALLOW, 0.8F, true), "呑んだ swallow の後に傷を受けない");
        check(VoramnesEntity.exposedAction(VoramnesEntity.ACT_ERUPT, 0.5F, true), "突き上げて呑んだ姿で傷を受けない");
        check(VoramnesEntity.exposedAction(VoramnesEntity.ACT_SPIT, 0.7F, false), "吐く口が開いている間に傷を受けない");
        check(!VoramnesEntity.exposedAction(VoramnesEntity.ACT_SPIT, 1.2F, false), "吐き終えて口を閉じた後に傷を受ける");

        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        for (int act : new int[]{VoramnesEntity.ACT_STALK, VoramnesEntity.ACT_LURK, VoramnesEntity.ACT_IDLE}) {
            worm.forceAction(act);
            float before = worm.getHealth();
            worm.hurt(worm.damageSources().magic(), 5.0F);
            worm.hurt(worm.damageSources().playerAttack(creative), 5.0F);
            check(worm.getHealth() == before, VoramnesEntity.animationName(act) + " の個体の体力が減った");
        }
        worm.forceAction(VoramnesEntity.ACT_GAPE);
        float before = worm.getHealth();
        check(worm.hurt(worm.damageSources().magic(), 5.0F) && worm.getHealth() < before, "口を開けた個体に当たらない");
        worm.forceAction(VoramnesEntity.ACT_STALK);
        worm.hurt(worm.damageSources().genericKill(), Float.MAX_VALUE);
        check(!worm.isAlive(), "地中の個体を /kill で倒せない");
        helper.succeed();
    }

    /**
     * 地面に潜伏している間(地中・地面の花)は Mob から見つからない(2026-10-09 ユーザー指定): 敵対 Mob・アイアンゴーレムの標的選び
     * ({@code canAttack})に通らず、直接 setTarget しても止まる。機関砲台も撃たない。地上に出ている間は見つかる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hiddenWormIsNotFoundByMobs(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        Husk husk = stillHusk(helper, 1, 1);
        net.minecraft.world.entity.animal.IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(5, SURFACE_Y, 5));
        golem.setNoAi(true);
        for (int act : new int[]{VoramnesEntity.ACT_STALK, VoramnesEntity.ACT_LURK}) {
            worm.forceAction(act);
            check(worm.isHiddenInGround(), VoramnesEntity.animationName(act) + " が潜伏の姿になっていない");
            check(!husk.canAttack(worm) && !golem.canAttack(worm), VoramnesEntity.animationName(act) + " の個体を Mob が狙える");
            husk.setTarget(worm);
            check(husk.getTarget() == null, VoramnesEntity.animationName(act) + " の個体を直接 setTarget できた");
            check(!com.yayuy.overgrownvegetation.defense.GunTurretTargeting.isEnemy(worm), "潜伏している個体を機関砲台が狙う");
        }
        worm.forceAction(VoramnesEntity.ACT_IDLE);
        check(!worm.isHiddenInGround() && husk.canAttack(worm) && golem.canAttack(worm), "地上に出ている個体を Mob が狙えない");
        husk.setTarget(worm);
        check(husk.getTarget() == worm, "地上に出ている個体を setTarget できない");
        check(com.yayuy.overgrownvegetation.defense.GunTurretTargeting.isEnemy(worm), "地上に出ている個体を機関砲台が狙わない");
        helper.succeed();
    }

    /**
     * 過繁茂側でない Mob の足音も聞く(2026-10-09、ユーザー「過繁茂 mob 以外に敵対していない場合がある」。前はプレイヤーの振動だけ)。
     * 今の標的の音が新しいうちは、ほかの Mob の足音では標的を替えない(群れに振り回されない)。プレイヤーの振動は Mob の標的より先。
     * 汚染側の仲間の足音は聞かない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hearsMobFootstepsButKeepsItsTarget(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setHearMobsForTests(true);
        Husk first = stillHusk(helper, 1, 3);
        Husk second = stillHusk(helper, 5, 3);
        Vec3 near1 = worm.position().add(2.0D, 0.0D, 0.0D);
        check(worm.onVibration(near1, first, false, DIRT), "近くを歩く Mob の足音を聞かない");
        check(worm.getTarget() == first, "足音を立てた Mob を標的にしていない");
        Vec3 near2 = worm.position().add(-2.0D, 0.0D, 0.0D);
        check(!worm.onVibration(near2, second, false, DIRT) && worm.getTarget() == first, "ほかの Mob の足音で標的を替えた");
        // プレイヤーの振動は Mob の標的より先(聞いて、その点へ向かう。偽のプレイヤーは世界に居ないので標的にはならない)
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        check(worm.onVibration(near2, p, false, DIRT) && worm.getNoisePos() != null && worm.getNoisePos().distanceTo(near2) < 1.0E-3D,
                "プレイヤーの足音が Mob の標的より先にならない");
        var ally = ModEntities.ARBORA.get().create(helper.getLevel());
        if (ally != null) {
            ally.moveTo(helper.absoluteVec(new Vec3(3.5D, SURFACE_Y, 1.5D)));
            check(!worm.onVibration(near1, ally, false, DIRT), "汚染側の仲間の足音を聞いた");
            ally.discard();
        }
        helper.succeed();
    }

    /**
     * 地上に出た姿のまま世界に入っても(読み込み直し・{@code /summon})、湧いた Mob を空きへ押し出す {@code SpawnFit#unbury} に動かされない
     * (地中をすり抜ける種。葉・天井に当たっても自分で地表へ合わせる)。テスト場の天井(y=8)は体格 2 の地上の体(8.5)より低く、
     * どこへも押し出せず違いが出ないので、体格を 0.5 に落として(個体差の記録で。{@code MobSizeVariance} が世界に入るときに体格を種の値へ戻すため)
     * 頭の上に石を 1 つ置く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void surfacedWormIsNotPushedOutOnJoin(GameTestHelper helper) {
        layGround(helper);
        helper.setBlock(new BlockPos(3, SURFACE_Y + 1, 3), Blocks.STONE);
        ServerLevel level = helper.getLevel();
        VoramnesEntity w = ModEntities.VORAMNES.get().create(level);
        check(w != null, "作れない");
        w.setData(ModAttachments.SIZE_VARIANCE, 0.5F);
        AttributeInstance scale = w.getAttribute(Attributes.SCALE);
        check(scale != null, "SCALE 属性が無い");
        scale.setBaseValue(0.5D);
        w.forceAction(VoramnesEntity.ACT_IDLE);
        Vec3 feet = helper.absoluteVec(new Vec3(3.5D, SURFACE_Y, 3.5D));
        w.moveTo(feet.x, feet.y, feet.z, 0.0F, 0.0F);
        check(!level.noCollision(w, w.getDimensions(w.getPose()).makeBoundingBox(feet)),
                "前提: 地上の体が頭上の石に当たっていない(押し出しが起きる場面になっていない)");
        check(level.addFreshEntity(w), "世界に入れられない");
        check(w.position().distanceTo(feet) < 1.0E-6D, "世界に入ったときに押し出された: " + w.position().subtract(feet));
        check(w.getAction() == VoramnesEntity.ACT_IDLE, "地上の姿でなくなった: " + VoramnesEntity.animationName(w.getAction()));
        helper.succeed();
    }

    /** 当たり判定は体の場所で変わる(地中 = 蕾の先、地上 = 胴の柱、地面の花 = 花の真ん中)。湧いた直後は地中。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void hitboxFollowsPosture(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        float s = worm.getScale();
        EntityType<?> type = ModEntities.VORAMNES.get();
        helper.runAfterDelay(1L, () -> {
            check(worm.getAction() == VoramnesEntity.ACT_STALK, "湧いた直後が地中でない");
            check(Math.abs(worm.getBbHeight() - 0.45F * s) < 1.0E-3F && Math.abs(worm.getBbWidth() - 0.5F * s) < 1.0E-3F,
                    "地中の当たり判定が蕾の先でない: " + worm.getBbWidth() + " x " + worm.getBbHeight());
            worm.forceAction(VoramnesEntity.ACT_IDLE);
            check(Math.abs(worm.getBbHeight() - type.getHeight() * s) < 1.0E-3F && Math.abs(worm.getBbWidth() - type.getWidth() * s) < 1.0E-3F,
                    "地上の当たり判定が胴の柱でない: " + worm.getBbWidth() + " x " + worm.getBbHeight());
            worm.forceAction(VoramnesEntity.ACT_LURK);
            check(Math.abs(worm.getBbHeight() - 0.5F * s) < 1.0E-3F && Math.abs(worm.getBbWidth() - type.getWidth() * s) < 1.0E-3F,
                    "地面の花の当たり判定が違う: " + worm.getBbWidth() + " x " + worm.getBbHeight());
            check(Adaptation.isAdaptive(worm.getType()), "group_5 に入っていない(適応が効かない)");
            helper.succeed();
        });
    }

    /** 頭の部位を殴っても本体の体力が減る(体力は共通)。同じ tick の同じダメージ源が本体と頭の両方に当たっても 1 回分だけ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void headPartSharesHealthOnce(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        helper.runAfterDelay(2L, () -> {
            check(worm.getParts().length == 1 && worm.getParts()[0] instanceof VoramnesHeadPart, "頭の部位が無い");
            VoramnesHeadPart head = (VoramnesHeadPart) worm.getParts()[0];
            check(head.is(worm) && head.getParent() == worm, "頭の部位が本体と同一視されない");
            check(head.getId() == worm.getId() + 1, "頭の部位の id が本体の続き番号でない");
            DamageSource magic = worm.damageSources().magic(); // 防具を貫く(数えやすい)
            worm.forceAction(VoramnesEntity.ACT_GAPE); // 傷を受けるのは口を開けた時など(10-09)
            float before = worm.getHealth();
            check(head.hurt(magic, 10.0F), "頭の部位に当たらない");
            worm.hurt(magic, 20.0F); // 同じ tick の同じダメージ源 → 捨てる(捨てないと差分の 10 がもう一度入る)
            float lost = before - worm.getHealth();
            check(lost > 5.0F && lost <= 10.5F, "頭と本体の二重当たり、または体力が減っていない: " + lost);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 呑んでいる途中に保存して読み込み直しても、獲物は中に居たまま消化が続く。獲物が戻らなかったとき(プレイヤーが抜けた後の保存など)は
     * 次の tick に地上の待機へ戻る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void saveAndLoadWhileDigesting(GameTestHelper helper) {
        layGround(helper);
        ServerLevel level = helper.getLevel();
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        Husk husk = stillHusk(helper, 3, 5);
        check(worm.forceSwallow(husk), "ハスクを呑めない");
        VoramnesEntity[] loaded = new VoramnesEntity[2];
        float[] hp = new float[1];
        helper.runAfterDelay(5L, () -> {
            CompoundTag tag = new CompoundTag();
            check(worm.save(tag), "保存できない");
            CompoundTag empty = tag.copy();
            empty.remove("Passengers");
            empty.remove("UUID");
            worm.discard();
            husk.discard();
            Entity back = EntityType.loadEntityRecursive(tag, level, e -> level.addWithUUID(e) ? e : null);
            check(back instanceof VoramnesEntity, "読み込めない");
            loaded[0] = (VoramnesEntity) back;
            check(loaded[0].getAction() == VoramnesEntity.ACT_DIGEST, "読み込み直すと消化でない: "
                    + VoramnesEntity.animationName(loaded[0].getAction()));
            check(loaded[0].getFirstPassenger() instanceof Husk, "獲物が中に戻っていない");
            hp[0] = ((Husk) loaded[0].getFirstPassenger()).getHealth();
            Entity alone = EntityType.loadEntityRecursive(empty, level, e -> level.addWithUUID(e) ? e : null);
            check(alone instanceof VoramnesEntity, "獲物の無い写しを読み込めない");
            loaded[1] = (VoramnesEntity) alone;
        });
        helper.runAfterDelay(8L, () -> check(loaded[1].getAction() == VoramnesEntity.ACT_IDLE,
                "獲物の居ない消化のまま: " + VoramnesEntity.animationName(loaded[1].getAction())));
        helper.runAfterDelay(35L, () -> {
            VoramnesEntity w = loaded[0];
            check(w.getAction() == VoramnesEntity.ACT_DIGEST || w.getAction() == VoramnesEntity.ACT_DIGEST_KICK,
                    "読み込み直した後に消化が止まった: " + VoramnesEntity.animationName(w.getAction()));
            check(w.getFirstPassenger() instanceof Husk h && h.getHealth() < hp[0], "読み込み直した後に消化のダメージが入らない");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 点検係の追加(2026-10-01)

    /**
     * 呑まれた相手は自分から降りられない(降車は {@code Events#onMount} が止める)。本体が死んだら外へ出る(生きたまま、地表に)。
     * 消化で倒れて降りるのと区別するため、ハスクは不死身にする。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void swallowedPreyStaysInsideUntilTheWormDies(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        Husk husk = stillHusk(helper, 3, 5);
        husk.setInvulnerable(true);
        check(worm.forceSwallow(husk), "ハスクを呑めない");
        husk.stopRiding();
        check(husk.getVehicle() == worm, "呑まれた相手が自分から降りられた");
        int surfaceY = helper.absolutePos(new BlockPos(3, SURFACE_Y, 3)).getY();
        helper.runAfterDelay(10L, () -> {
            check(husk.getVehicle() == worm, "消化の途中で外に出ている(行動 " + VoramnesEntity.animationName(worm.getAction()) + ")");
            worm.kill();
            check(!worm.isAlive(), "前提: 本体が死んでいない");
            check(worm.getAction() == VoramnesEntity.ACT_DEATH_DIGEST,
                    "呑んでいる間の死が death_digest でない: " + VoramnesEntity.animationName(worm.getAction()));
            check(!husk.isPassenger(), "本体が死んでも獲物が中に残っている");
            check(husk.isAlive() && husk.getY() >= surfaceY - 0.01D, "降ろされた獲物が生きて地表に居ない(y " + husk.getY() + ")");
            helper.succeed();
        });
    }

    /**
     * 呑み込みの途中(erupt の残り。まだ digest に入っていない)に中から殴られた量も数え、足りていれば digest に入らずそのまま吐き出す
     * (以前は digest に入るまでの約 1 秒の被弾を捨てていた)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void hitsFromInsideCountBeforeDigestStarts(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        worm.setNoAi(true);
        Husk husk = stillHusk(helper, 3, 5);
        husk.setInvulnerable(true);
        check(worm.forceSwallow(husk), "ハスクを呑めない");
        worm.forceAction(VoramnesEntity.ACT_ERUPT); // 突き上げて呑んだ直後の体
        helper.runAfterDelay(8L, () -> {
            check(worm.getAction() == VoramnesEntity.ACT_ERUPT && husk.getVehicle() == worm, "前提: 突き上げの途中で呑んでいない");
            check(worm.hurt(worm.damageSources().mobAttack(husk), 30.0F), "中から殴っても当たらない");
            check(worm.getAction() == VoramnesEntity.ACT_ERUPT, "突き上げの途中で止まった: " + VoramnesEntity.animationName(worm.getAction()));
        });
        helper.runAfterDelay(26L, () -> check(worm.getAction() == VoramnesEntity.ACT_SPIT,
                "突き上げの後、digest に入らずに吐き出していない: " + VoramnesEntity.animationName(worm.getAction())));
        helper.runAfterDelay(60L, () -> {
            check(!husk.isPassenger(), "吐き出していない");
            helper.succeed();
        });
    }

    /**
     * 口が届く範囲(前 2.6〜6.2・横 1.8、体格 2。相手の幅の半分だけ広げる)。呑みに行く範囲も同じ形で、手前(前 1.5〜2.3)へ空振りしない。
     * 地上の個体は、その内側(根元)に張り付いた相手へは口を開かず、潜って真下から呑む。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void tooCloseForTheMouthDivesAndTakesItFromBelow(GameTestHelper helper) {
        double k = 1.0D; // 体格 2 / 描画倍率 2
        check(!VoramnesEntity.mouthReach(2.0D, 0.0D, 0.3D, k), "根元(前 2.0)に口が届くことになっている");
        check(VoramnesEntity.mouthReach(2.5D, 0.0D, 0.3D, k) && VoramnesEntity.mouthReach(4.4D, 2.0D, 0.3D, k)
                && VoramnesEntity.mouthReach(6.4D, -1.9D, 0.3D, k), "口の届く範囲の中に届かない");
        check(!VoramnesEntity.mouthReach(4.4D, 2.3D, 0.3D, k) && !VoramnesEntity.mouthReach(6.7D, 0.0D, 0.3D, k),
                "口の届く範囲の外(横 2.3・前 6.7)に届く");
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 1);
        worm.forceAction(VoramnesEntity.ACT_IDLE);
        Husk husk = stillHusk(helper, 3, 3); // 2 ブロック先 = 口の届くいちばん手前(2.3)より内側
        check(Math.hypot(husk.getX() - worm.getX(), husk.getZ() - worm.getZ()) < VoramnesEntity.mouthNear(husk.getBbWidth() * 0.5D, worm.getScale() / 2.0D),
                "前提: ハスクが口の届く範囲より内側に居ない");
        Set<Integer> seen = new HashSet<>();
        helper.onEachTick(() -> seen.add(worm.getAction()));
        helper.succeedWhen(() -> {
            check(husk.getVehicle() == worm, "根元の相手を呑んでいない(行動 " + VoramnesEntity.animationName(worm.getAction()) + ")");
            check(seen.contains(VoramnesEntity.ACT_SUBMERGE) && seen.contains(VoramnesEntity.ACT_AMBUSH),
                    "潜って真下から(submerge → ambush)呑んでいない");
            check(!seen.contains(VoramnesEntity.ACT_OPEN) && !seen.contains(VoramnesEntity.ACT_SWALLOW), "届かない口を開いて空振りした");
        });
    }

    /**
     * 地中の個体の真上(蕾の先)に水を流しても、その場に留まらず隣の乾いた列へ泳いで出る(以前は動かず、溶岩なら焼け死ぬまで留まった)。
     * 水は四方をガラスで囲って広がらないようにする(斜めの列は乾いたまま)。
     * <p>
     * 床の下(相対 y=0)は GameTest が空けた空気の層で、その下に石 2 段 + 平らな世界の土があるので、ヴォラムネスには「地表」に見える。
     * そのままだと、足元の地表を探す処理(下へ 12)がこの列の床下を見つけて<b>同じ列のまま床下へ移り</b>、乾いた列へ泳ぐ処理を通らない
     * (2026-10-01 まとめ係。3 回に 1 回ほど「列に留まっている」で落ちた。通った回も、たまたまのぶらつきで床下を動いただけだった)。
     * 床下のこのマスを土で塞ぎ、移った先が地表の高さにあることも確かめる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void floodedWormSwimsToDryGround(GameTestHelper helper) {
        layGround(helper);
        helper.setBlock(new BlockPos(3, 0, 3), Blocks.DIRT);
        VoramnesEntity worm = worm(helper, 3, 3);
        helper.setBlock(new BlockPos(3, SURFACE_Y, 2), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, SURFACE_Y, 4), Blocks.GLASS);
        helper.setBlock(new BlockPos(2, SURFACE_Y, 3), Blocks.GLASS);
        helper.setBlock(new BlockPos(4, SURFACE_Y, 3), Blocks.GLASS);
        helper.setBlock(new BlockPos(3, SURFACE_Y, 3), Blocks.WATER);
        BlockPos flooded = helper.absolutePos(new BlockPos(3, SURFACE_Y, 3));
        ServerLevel level = helper.getLevel();
        helper.succeedWhen(() -> {
            BlockPos at = worm.blockPosition();
            check(worm.getAction() == VoramnesEntity.ACT_STALK, "地中でない: " + VoramnesEntity.animationName(worm.getAction()));
            BlockPos rel = at.subtract(helper.absolutePos(BlockPos.ZERO));
            check(!(at.getX() == flooded.getX() && at.getZ() == flooded.getZ()), "水を掛けられた列に留まっている(相対 " + rel.toShortString() + ")");
            check(at.getY() >= flooded.getY(), "地表より下(床下)へ移った: 相対 " + rel.toShortString());
            check(VoramnesTerrain.isValidSurface(level, at), "乾いた地表に出ていない: 相対 " + rel.toShortString());
            helper.assertBlockPresent(Blocks.WATER, new BlockPos(3, SURFACE_Y, 3));
        });
    }

    /**
     * 足音はゲームの振動({@code VanillaGameEvent})を通って個体に届く(ほかのテストは {@code onVibration} を直接呼んでいる)。
     * スニーク・羊毛の上は届かず、ふつうに歩く足音は近く(2.5 ブロック)なら届く。歩く足音の距離(6)はほかのテスト場(24 以上離れている)の個体に届かない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void footstepsReachTheWormThroughTheGameEvent(GameTestHelper helper) {
        layGround(helper);
        VoramnesEntity worm = worm(helper, 3, 3);
        ServerLevel level = helper.getLevel();
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 near = worm.position().add(2.0D, 0.0D, 0.0D);
        p.setShiftKeyDown(true);
        level.gameEvent(GameEvent.STEP, near, GameEvent.Context.of(p, DIRT));
        check(!worm.isHunting(), "スニークの足音がゲームの振動を通って届いた");
        p.setShiftKeyDown(false);
        level.gameEvent(GameEvent.STEP, near, GameEvent.Context.of(p, Blocks.WHITE_WOOL.defaultBlockState()));
        check(!worm.isHunting(), "羊毛の上の足音がゲームの振動を通って届いた");
        Vec3 walk = worm.position().add(0.0D, 0.0D, 2.5D);
        level.gameEvent(GameEvent.STEP, walk, GameEvent.Context.of(p, DIRT));
        check(worm.isHunting() && worm.getNoisePos() != null && worm.getNoisePos().distanceTo(walk) < 1.0E-3D,
                "近くを歩く足音がゲームの振動(VanillaGameEvent)を通って届かない");
        helper.succeed();
    }
}
