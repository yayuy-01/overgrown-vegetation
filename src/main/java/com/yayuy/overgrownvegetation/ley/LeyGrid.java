package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.level.LevelEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * <b>線でつながった柱の網</b>(2026-11-13 サバイバルの背骨 段 2b、決定 8・13)。毎 tick、読み込まれた柱から網をたどり、FE を出す側から受け取る側へ運ぶ。
 *
 * <h2>網の中の役(FE の口の向きで決める)</h2>
 * <ul>
 *     <li><b>出す側</b>: 取り出せて受け取らない口(汲み上げ器・ほかの MOD の発電機)。</li>
 *     <li><b>受け取る側</b>: 受け取って取り出せない口(2c の換気の機械・ほかの MOD の機械)。</li>
 *     <li><b>ためる側</b>: どちらもできる口(柱に掛けた電池・ほかの MOD の蓄電器)。足りない時に出し、余った時に受け取る。</li>
 * </ul>
 * 1 tick の流れ: 受け取る側が欲しい量(試しに聞く)を、出す側 → 足りなければためる側から集めて配る。残った出す側の余りをためる側へ。
 * 合わせて網 1 つで {@code wireNetworkPerTick} FE まで【仮】。配るのは均等に分けてから余りを順に。線の長さで目減りはしない【仮】。
 *
 * <p>網をたどるとき、相手の柱が線を持っていない(片方だけ残った)線・先のブロックが FE の口を失った線は外し、機械の側の線は線の巻きを柱の足元に落とす。
 * 読み込まれていないチャンクの先はその tick だけ飛ばす(外さない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class LeyGrid {

    private static final Map<Level, Set<BlockPos>> LOADED = new WeakHashMap<>();

    private LeyGrid() {
    }

    static void track(Level level, BlockPos pos) {
        LOADED.computeIfAbsent(level, l -> new LinkedHashSet<>()).add(pos.immutable());
    }

    static void untrack(Level level, BlockPos pos) {
        Set<BlockPos> set = LOADED.get(level);
        if (set != null) {
            set.remove(pos);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            LOADED.remove(level);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<BlockPos> set = LOADED.get(level);
        if (set == null || set.isEmpty()) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos pos : new ArrayList<>(set)) {
            if (!visited.contains(pos)) {
                Network net = collect(level, pos, visited);
                if (net != null) {
                    net.transfer();
                }
            }
        }
    }

    /** 1 つの網(GameTest・表示から使う)。 */
    public static final class Network {
        public final List<BlockPos> pylons = new ArrayList<>();
        public final List<IEnergyStorage> producers = new ArrayList<>();
        public final List<IEnergyStorage> consumers = new ArrayList<>();
        public final List<IEnergyStorage> buffers = new ArrayList<>();
        private final List<LeyPylonBlockEntity> batteryHolders = new ArrayList<>();

        /** 1 tick ぶん運ぶ。運んだ量(受け取る側とためる側が受け取った合計)を返す。 */
        public int transfer() {
            int budget = DesignData.sporeField().ley().wireNetworkPerTick();
            int demand = 0;
            for (IEnergyStorage c : consumers) {
                demand = sat(demand + c.receiveEnergy(Integer.MAX_VALUE, true));
            }
            int want = Math.min(budget, demand);
            int moved = 0;
            if (want > 0) {
                int got = pull(producers, want);
                if (got < want) {
                    got += pull(buffers, want - got);
                }
                int left = push(consumers, got);
                left = push(buffers, left);
                moved += got - left;
                if (left > 0) {
                    OvergrownVegetation.LOGGER.debug("LeyGrid: {} FE をどこにも渡せなかった", left);
                }
                budget -= got;
            }
            if (budget > 0 && !buffers.isEmpty() && !producers.isEmpty()) {
                int room = 0;
                for (IEnergyStorage b : buffers) {
                    room = sat(room + b.receiveEnergy(Integer.MAX_VALUE, true));
                }
                int extra = pull(producers, Math.min(budget, room));
                int left = push(buffers, extra);
                moved += extra - left;
            }
            if (moved > 0) {
                for (LeyPylonBlockEntity be : batteryHolders) {
                    be.setChanged();
                }
            }
            return moved;
        }
    }

    /** {@code start} の柱からつながる網を集める(柱でなければ null)。たどった柱は {@code visited} に足す。 */
    public static Network collect(ServerLevel level, BlockPos start, Set<BlockPos> visited) {
        if (!(level.getBlockEntity(start) instanceof LeyPylonBlockEntity)) {
            return null;
        }
        Network net = new Network();
        Set<BlockPos> endpoints = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (!(level.getBlockEntity(p) instanceof LeyPylonBlockEntity pylon)) {
                continue;
            }
            net.pylons.add(p);
            IEnergyStorage battery = pylon.batteryStorage();
            if (battery != null) {
                net.buffers.add(battery);
                net.batteryHolders.add(pylon);
            }
            List<BlockPos> stale = new ArrayList<>();
            for (BlockPos q : pylon.links()) {
                if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, q)) {
                    continue;
                }
                BlockEntity other = level.getBlockEntity(q);
                if (other instanceof LeyPylonBlockEntity otherPylon) {
                    if (!otherPylon.hasLink(p)) {
                        stale.add(q);
                    } else if (visited.add(q)) {
                        queue.add(q);
                    }
                    continue;
                }
                IEnergyStorage s = LeyWires.energyAt(level, q, Direction.getNearest(p.getX() - q.getX(), p.getY() - q.getY(), p.getZ() - q.getZ()));
                if (s == null) {
                    stale.add(q);
                    Containers.dropItemStack(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, new ItemStack(ModLey.WIRE.get()));
                    continue;
                }
                if (!endpoints.add(q)) {
                    continue;
                }
                boolean out = s.canExtract();
                boolean in = s.canReceive();
                if (out && in) {
                    net.buffers.add(s);
                } else if (out) {
                    net.producers.add(s);
                } else if (in) {
                    net.consumers.add(s);
                }
            }
            for (BlockPos q : stale) {
                pylon.removeLink(q);
            }
        }
        return net;
    }

    /** 出す側から {@code amount} まで集める(均等に分けてから、足りない分を順に)。 */
    private static int pull(List<IEnergyStorage> sources, int amount) {
        if (amount <= 0 || sources.isEmpty()) {
            return 0;
        }
        int got = 0;
        int share = Math.max(1, amount / sources.size());
        for (IEnergyStorage s : sources) {
            got += s.extractEnergy(Math.min(share, amount - got), false);
            if (got >= amount) {
                return got;
            }
        }
        for (IEnergyStorage s : sources) {
            got += s.extractEnergy(amount - got, false);
            if (got >= amount) {
                break;
            }
        }
        return got;
    }

    /** 受け取る側へ {@code amount} を配り、渡しきれなかった量を返す。 */
    private static int push(List<IEnergyStorage> sinks, int amount) {
        if (amount <= 0 || sinks.isEmpty()) {
            return amount;
        }
        int left = amount;
        int share = Math.max(1, amount / sinks.size());
        for (IEnergyStorage s : sinks) {
            left -= s.receiveEnergy(Math.min(share, left), false);
            if (left <= 0) {
                return 0;
            }
        }
        for (IEnergyStorage s : sinks) {
            left -= s.receiveEnergy(left, false);
            if (left <= 0) {
                return 0;
            }
        }
        return left;
    }

    private static int sat(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, v));
    }
}
