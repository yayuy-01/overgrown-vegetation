package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * ステノディクティアの幼虫 {@code stenodictya_nymph}(G2・原生生産種。翅芽のある 6 本脚の虫で、成虫と同じ顔)。Vault「Mob の能力/ステノディクティア」。
 *
 * <h2>段 1(2026-11-17): 梢(林冠の葉の上)を歩くまで</h2>
 * 「小さな地這いの虫」の型({@link CrawlerCritter})で歩く。うろつきの行き先は**林冠の葉の上だけ**(今の高さ ±2 の、てっぺんが葉の所)を選ぶ
 * ので、梢から地面へは下りていかない(行き先が無ければその場に留まる)。
 * 形は 11-16 に 0.7 倍の寸法で作り直した(全長 1.11。名前から「巨大」を外した)。正本 {@code デスクトップ\blockbench\mobs\stenodictya_nymph.bbmodel}。
 * アニメは idle・walk(と、後の段の eat・rest・flee)。走り(run)は無いので急ぐ間も walk を速く流す。
 * G2・G3 は生息しているだけの生き物(胞子を運ばない・亜種なし)。植物側(タグ group_2)。自然には湧かない。食べる・休む・逃げる、成虫への羽化は後の段。
 */
public class StenodictyaNymphEntity extends CrawlerCritter {

    public static final String ID = "stenodictya_nymph";
    /**
     * walk(0.6 秒 = 脚 2 巡)を 1 倍で流したとき足が滑らない地面の速さ(11-17 g2g3-bug-walk が脚の運びを入れて測った確定値)。
     * 急ぎ(約 0.10)が 3 倍の上限の中に収まるように 0.035。
     */
    public static final double WALK_REF = 0.035D;
    static final float TURN_PER_TICK = 12.0F;
    static final double STROLL_SPEED = 0.5D;
    /** 梢のうろつき: 行き先を探す水平の半径・今の高さからの上下の幅(ブロック)【仮】。 */
    static final int CANOPY_REACH = 6, CANOPY_DY = 2;

    public StenodictyaNymphEntity(EntityType<? extends StenodictyaNymphEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(6, new CanopyScurryGoal(this));
        com.yayuy.overgrownvegetation.ecosystem.life.EcoGoals.register(this, this.goalSelector);   // 行動の段(11-18)
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    /** walk 0.4〜3.0 倍【仮】(run が無いので、急ぐ間も walk を速く流す)。 */
    @Override
    protected double[] walkRange() {
        return new double[]{0.4D, 3.0D};
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 0.55D;
    }

    /** 幼虫はゆっくり這って長く止まる【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{30, 80};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{40, 140};
    }

    /** 葉か(梢の足場)。 */
    static boolean isLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.getBlock() instanceof LeavesBlock;
    }

    /**
     * 梢の上の行き先を 1 つ選ぶ(素の計算。GameTest からも呼ぶ): (x, z) のまわり {@link #CANOPY_REACH} の中を 10 回くじで引き、
     * てっぺん(高さ地図)のブロックが葉で、その上の高さが今の足元 ±{@link #CANOPY_DY} の所。見つからなければ null。
     */
    @Nullable
    public static Vec3 canopySpot(Level level, BlockPos feet, net.minecraft.util.RandomSource random) {
        for (int i = 0; i < 10; i++) {
            int x = feet.getX() + random.nextInt(CANOPY_REACH * 2 + 1) - CANOPY_REACH;
            int z = feet.getZ() + random.nextInt(CANOPY_REACH * 2 + 1) - CANOPY_REACH;
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunk(level, x >> 4, z >> 4)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (Math.abs(top - feet.getY()) > CANOPY_DY) continue;
            if (!isLeaves(level.getBlockState(new BlockPos(x, top - 1, z)))) continue;
            return new Vec3(x + 0.5D, top, z + 0.5D);
        }
        return null;
    }

    /** うろつき(小走りと一時停止)の行き先を梢の上だけにする。 */
    static final class CanopyScurryGoal extends SmallCrawler.ScurryGoal {
        CanopyScurryGoal(StenodictyaNymphEntity mob) {
            super(mob);
        }

        @Nullable
        @Override
        protected Vec3 pickSpot() {
            return canopySpot(this.mob.level(), this.mob.blockPosition(), this.mob.getRandom());
        }
    }
}
