package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LeySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 線を張る決まり(2026-11-13 サバイバルの背骨 段 2b、決定 13。数は {@code spore_field.json} の {@code ley.wire*}【仮】)。
 * <ul>
 *     <li>線の端になれるのは、柱と、FE の口を持つブロック(汲み上げ器・2c の換気の機械・ほかの MOD の機械)。<b>片方は必ず柱</b>(機械どうしを直につながない)。</li>
 *     <li>長さは端のブロックの真ん中どうしで {@code wireMaxLength} まで・1 本の柱に {@code wireMaxPerPylon} 本まで。</li>
 *     <li>線の通り道(端の点どうしを結ぶまっすぐな線)に当たり判定のあるブロックがあれば張れない({@code wireNeedsClearPath})。</li>
 * </ul>
 * <b>張った後にふさがれたとき</b>【仮・まだ作らない】: 今は何も起きない(線はそのまま流れる)。籠城の手入れにするなら、
 * {@link LeyGrid} の網を集める所で {@link #clearPath} を時々測り、ふさがれた線を「たるむ → 流れが細る → 切れる(線の巻きを落とす)」にできる
 * (植物に覆われる・Mob に切られる、もここから)。
 */
public final class LeyWires {

    private LeyWires() {
    }

    /** 張れない理由(null = 張れる)。言語の鍵 {@code message.overgrown_vegetation.ley_wire.<小文字>}。 */
    public enum Fail {
        SAME, NOT_NODE, NO_PYLON, TOO_LONG, FULL, ALREADY, BLOCKED
    }

    private static LeySpec spec() {
        return DesignData.sporeField().ley();
    }

    public static boolean isPylon(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof LeyPylonBlockEntity;
    }

    /** そのブロックの FE の口(向きを問わず、最初に見つかったもの)。柱には無い。 */
    public static IEnergyStorage energyAt(Level level, BlockPos pos, Direction preferred) {
        IEnergyStorage s = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, preferred);
        if (s != null) {
            return s;
        }
        s = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (s != null) {
            return s;
        }
        for (Direction d : Direction.values()) {
            s = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, d);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    public static boolean isNode(Level level, BlockPos pos) {
        return isPylon(level, pos) || energyAt(level, pos, null) != null;
    }

    public static double length(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    public static Fail check(Level level, BlockPos a, BlockPos b) {
        if (a.equals(b)) {
            return Fail.SAME;
        }
        if (!isNode(level, a) || !isNode(level, b)) {
            return Fail.NOT_NODE;
        }
        boolean pa = isPylon(level, a);
        boolean pb = isPylon(level, b);
        if (!pa && !pb) {
            return Fail.NO_PYLON;
        }
        LeySpec spec = spec();
        if (length(a, b) > spec.wireMaxLength() + 1.0e-6) {
            return Fail.TOO_LONG;
        }
        LeyPylonBlockEntity ea = pa ? (LeyPylonBlockEntity) level.getBlockEntity(a) : null;
        LeyPylonBlockEntity eb = pb ? (LeyPylonBlockEntity) level.getBlockEntity(b) : null;
        if ((ea != null && ea.hasLink(b)) || (eb != null && eb.hasLink(a))) {
            return Fail.ALREADY;
        }
        if ((ea != null && ea.links().size() >= spec.wireMaxPerPylon()) || (eb != null && eb.links().size() >= spec.wireMaxPerPylon())) {
            return Fail.FULL;
        }
        if (spec.wireNeedsClearPath() && !clearPath(level, a, b)) {
            return Fail.BLOCKED;
        }
        return null;
    }

    /** 決まりを確かめずに張る(呼ぶ前に {@link #check})。 */
    public static void connect(Level level, BlockPos a, BlockPos b) {
        if (level.getBlockEntity(a) instanceof LeyPylonBlockEntity ea) {
            ea.addLink(b);
        }
        if (level.getBlockEntity(b) instanceof LeyPylonBlockEntity eb) {
            eb.addLink(a);
        }
    }

    /**
     * 線が端のブロックに付く点(ワールド座標)。柱は頭の碍子(真ん中の上)、ほかのブロックは相手の方を向いた箱の面の上の点。
     * 描画と通り道の判定の両方で使う。
     */
    public static Vec3 anchor(Level level, BlockPos pos, BlockPos toward) {
        if (isPylon(level, pos)) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 15.5 / 16.0, pos.getZ() + 0.5);
        }
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 d = Vec3.atCenterOf(toward).subtract(c);
        double m = Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z)));
        if (m < 1.0e-6) {
            return c;
        }
        return c.add(d.scale(0.5 / m));
    }

    /** 端の点どうしを結ぶまっすぐな線が、端のブロック以外の当たり判定に触れないか(4 分の 1 ブロックごとに見る)。 */
    public static boolean clearPath(Level level, BlockPos a, BlockPos b) {
        Vec3 pa = anchor(level, a, b);
        Vec3 pb = anchor(level, b, a);
        double len = pa.distanceTo(pb);
        int steps = Math.max(2, (int) Math.ceil(len * 4.0));
        for (int i = 1; i < steps; i++) {
            Vec3 p = pa.lerp(pb, i / (double) steps);
            BlockPos bp = BlockPos.containing(p);
            if (bp.equals(a) || bp.equals(b)) {
                continue;
            }
            BlockState state = level.getBlockState(bp);
            VoxelShape shape = state.getCollisionShape(level, bp);
            if (shape.isEmpty()) {
                continue;
            }
            Vec3 local = p.subtract(bp.getX(), bp.getY(), bp.getZ());
            for (AABB box : shape.toAabbs()) {
                if (box.inflate(0.01).contains(local)) {
                    return false;
                }
            }
        }
        return true;
    }
}
