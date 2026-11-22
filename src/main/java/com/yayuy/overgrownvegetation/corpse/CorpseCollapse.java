package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;

import com.yayuy.overgrownvegetation.network.CorpseCollapsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

/**
 * 胞子感染で死んだバニラの Mob が、死体ブロックと同じ姿勢へ崩れ落ちてから死体になる(2026-10-09、ユーザー指定)。
 *
 * <p>流れ: 死んだ瞬間({@link G1Infection})に {@link #begin} で印を付け、見ているプレイヤーへ
 * {@link CorpseCollapsePayload} を送る。クライアントはバニラの描画のまま死体の姿勢へ倒す
 * ({@code client.corpse.CollapsePoser})。サーバーは {@link #COLLAPSE_TICKS} 経ったら({@code LivingEntityCorpseDeathMixin})
 * {@link #tickDeath} で死体ブロックを置き、Mob は死体が描かれ始めるまで残してから煙を出さずに消す。
 *
 * <p>死体ブロックには死んだときの体の向き(yBodyRot)と、ブロックの真ん中からのずれを記録する。
 * 死体の描画と、起き上がる Mob の位置・向きはこれを使う(倒れたその場から起き上がる)。
 */
public final class CorpseCollapse {

    private CorpseCollapse() {
    }

    /**
     * 崩れ落ちの長さ(tick)。バニラは死んで 20 tick で Mob を消すので、その手前の 19 で死体に入れ替える。
     * クライアントの姿勢の進みは (deathTime + partialTick − 1) / (COLLAPSE_TICKS − 1)。
     */
    public static final int COLLAPSE_TICKS = 19;

    private static final String TAG = "ov_corpse_collapse";

    /** 崩れ落ちを始める(2026-11-08: 種は {@link G1Species})。羊の毛の色は死体へ引き継ぐ。 */
    public static void begin(LivingEntity entity, G1Species species) {
        // 死体の置き場が無ければ崩れ落ちを始めず、ふつうに死なせる(元のドロップも出る。2026-11-16 コードの見直し ③3 の続き。
        // 前は置く瞬間に萌芽体を生やしていたが、11-15 の「萌芽体が湧く道をなくす」方針と合わないので、全体管理の了承した「死体を作らない」にそろえた)
        if (entity.level() instanceof ServerLevel level && level.isLoaded(entity.blockPosition())
                && findCorpseSpot(level, entity.blockPosition()) == null) {
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putString("Block", BuiltInRegistries.BLOCK.getKey(species.corpseBlock()).toString());
        t.putFloat("Yaw", entity.yBodyRot);
        if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep) {
            t.putInt("Color", sheep.getColor().getId());
        }
        if (species == G1Species.SKELETON && entity instanceof net.minecraft.world.entity.Mob mob) {
            // 弓を持ったまま死体になり、起き上がった G1 も持つ(11-05 ユーザー「弓は持ったまま叩く」)。元の Mob のドロップは G1Infection#onLivingDrops で全部止める
            net.minecraft.world.item.ItemStack held = mob.getMainHandItem();
            if (!held.isEmpty()) {
                t.put("Held", held.save(entity.registryAccess()));
            }
        }
        entity.getPersistentData().put(TAG, t);
        PacketDistributor.sendToPlayersTrackingEntity(entity, new CorpseCollapsePayload(entity.getId(), species.assetName(), entity.yBodyRot));
    }

    /**
     * 死体を置いてから Mob を消すまでの猶予(tick)。新しく置いたブロックエンティティは、描画の区画が作り直されるまで
     * 描かれない(数フレーム〜数 tick)。その間に Mob が消えると何も無い瞬間ができる(10-09 に見た点滅)。
     * そのため Mob は残し、クライアントは死体が描かれ始めたら Mob を描くのをやめる({@code ClientCorpseCollapse#shouldHide})。
     */
    public static final int HANDOFF_TICKS = 20;

    /** 崩れ落ちの途中か(サーバー側。死にかけの Mob の tickDeath からだけ呼ばれる)。 */
    public static boolean isCollapsing(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        return data.contains(TAG);
    }

    /** サーバーの死の時計 1 tick 分({@code LivingEntityCorpseDeathMixin} から)。19 で死体を置き、さらに {@link #HANDOFF_TICKS} 後に Mob を消す。 */
    public static void tickDeath(LivingEntity entity) {
        entity.deathTime++;
        CompoundTag t = entity.getPersistentData().getCompound(TAG);
        if (entity.deathTime >= COLLAPSE_TICKS && !t.getBoolean("Placed")) {
            t.putBoolean("Placed", true);
            place(entity, t);
        }
        if (entity.deathTime >= COLLAPSE_TICKS + HANDOFF_TICKS && !entity.isRemoved()) {
            entity.getPersistentData().remove(TAG);
            entity.remove(Entity.RemovalReason.KILLED);
        }
    }

    /** 足元に置けないときに探す広さ(水平の半径・上下)。【仮】 */
    static final int SPOT_RADIUS = 2, SPOT_UP = 1, SPOT_DOWN = 1;

    /**
     * 死体ブロックを置いてよいマスを探す(2026-11-16 コードの見直し ③3)。まず足元、駄目なら近い順に水平 {@link #SPOT_RADIUS}・上下 1 の範囲で、
     * 下の面がしっかりした空きを探す。見つからなければ null(崩れ落ちを始めず、ふつうに死なせる = {@link #begin})。
     * <ul>
     *   <li>置いてよい = 置き換えられるブロック(空気・草・雪の層・水など {@code canBeReplaced})で、ブロックエンティティを持たない
     *   (チェスト・先の死体を消さない)。ドア・ベッド・ハーフは置き換えられないので外れる。</li>
     *   <li>足元以外は、下が上の面のしっかりしたブロックのときだけ(宙に浮いた死体を作らない)。</li>
     * </ul>
     */
    public static BlockPos findCorpseSpot(ServerLevel level, BlockPos origin) {
        if (canHoldCorpse(level, origin)) return origin;
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dy : new int[] {0, SPOT_UP, -SPOT_DOWN}) {
            for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx++) {
                for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    int d = dx * dx + dz * dz + dy * dy * 2;
                    if (d >= bestD) continue;
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(p) || !canHoldCorpse(level, p)) continue;
                    BlockPos below = p.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
                    best = p;
                    bestD = d;
                }
            }
            if (best != null) return best; // 同じ高さで見つかれば上下は見ない
        }
        return null;
    }

    private static boolean canHoldCorpse(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos);
        return s.canBeReplaced() && !s.hasBlockEntity() && s.getBlock().defaultDestroyTime() >= 0.0F;
    }

    /** 崩れ落ちの終わり: 死体ブロックを置く(向き・ずれを記録)。Mob はまだ消さない。 */
    private static void place(LivingEntity entity, CompoundTag t) {
        if (entity.level() instanceof ServerLevel level) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(t.getString("Block")));
            BlockPos origin = entity.blockPosition();
            // 置いてよい所だけに置く(2026-11-16 コードの見直し ③3。前は足元を無条件に置き換え、チェストの中身・ドア・ベッド・ハーフ・先の死体を消していた)
            BlockPos pos = level.isLoaded(origin) ? findCorpseSpot(level, origin) : null;
            if (pos != null) {
                level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
                com.yayuy.overgrownvegetation.director.ForestEconomy.onInfected(level, entity); // 11-20: 死体になった = 感染体への変化。森にまとまって入る
                com.yayuy.overgrownvegetation.ecosystem.map.MapSync.onRealInfected(level, entity); // 11-21: 地図の上の生態系の台帳へ返す
                if (level.getBlockEntity(pos) instanceof CorruptedCorpseBlockEntity be) {
                    if (t.contains("Color")) {
                        be.setWoolColor(t.getInt("Color"));
                    }
                    if (t.contains("Held")) {
                        be.setHeldItem(net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(), t.getCompound("Held")));
                    }
                    // ずれは足元に置けたときだけ(よそのマスへ移したら、そのマスの真ん中に置く。ずれたまま描くと隣の物にめり込むので)
                    boolean here = pos.equals(origin);
                    be.setPlacement(t.getFloat("Yaw"),
                            here ? entity.getX() - (pos.getX() + 0.5) : 0.0D, here ? entity.getY() - pos.getY() : 0.0D,
                            here ? entity.getZ() - (pos.getZ() + 0.5) : 0.0D);
                    if (!here) {
                        // 倒れた Mob も死体のマスへ移す。クライアントは「描いた死体のそば(0.6 ブロック)の Mob」を消して引き継ぐ
                        // ({@code ClientCorpseCollapse#shouldHide})ので、移さないと猶予の 1 秒、死体と倒れた Mob が並んで見える
                        entity.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, entity.getYRot(), entity.getXRot());
                    }
                }
                // 2026-11-08: 前はここで粒(コンポスター)と苔の音を出して絵の入れ替わりを隠していた。今の死体は崩れ落ちと同じ
                // 無傷の絵で始まり、突き破り(corpse_burst)で傷のある絵に替わるので、隠す物が無くなり外した。
            }
            // 崩れ落ちの 19 tick の間に置き場がふさがった(まれ)ときは死体を作らない(崩れ落ちの初めに置き場があることは確かめてある = begin)
        }
    }
}
