package com.yayuy.overgrownvegetation.devbridge;

import com.mojang.logging.LogUtils;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.FileUtil;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import org.slf4j.Logger;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * Claude が自分で起動したクライアントを、外からファイルで操作する開発用の口(2026-10-02。ユーザーが
 * 「runClient を Claude が勝手に起動してよい」と了承)。使い方は {@code tools/claude-client/README.md}。
 *
 * <p>{@code gradlew.bat runClaudeClient}(ゲームフォルダ {@code runs/claude}、{@code -Dov.devbridge=true})のときだけ動く。
 * ユーザーの {@code runClient} では何もしない。配布 jar にはパッケージごと入らない(build.gradle の jar で除外)。
 *
 * <p>{@code runs/claude/devbridge/in.txt} に 1 行 1 命令で<b>追記</b>すると、上から順に 1 tick に 1 つずつ実行し、
 * 結果を {@code devbridge/out.log} に追記する。前提が満たされない命令(ワールドに入る前の {@code cmd} など)は
 * 満たされるまで待つ。動いている間に in.txt を消したり書き換えたりしない(どこまで読んだかが行番号なので)。
 * 起動したときに前回の in.txt / out.log は {@code *.prev} に回す。
 *
 * <ul>
 *   <li>{@code newworld [名前] [normal|flat] [シード]} — タイトル画面から新しいワールドを作って入る(クリエイティブ・コマンド可・ノーマル)</li>
 *   <li>{@code open <フォルダ名>} — 既存のワールドに入る</li>
 *   <li>{@code cmd <コマンド>} — 統合サーバーでプレイヤーとして権限 4 で実行。返ってきた文言を out.log へ</li>
 *   <li>{@code shot [名前]} — スクリーンショット({@code runs/claude/screenshots/<名前>.png})。書き終えたら SHOT を書く</li>
 *   <li>{@code wait <tick>} — 次の命令まで待つ(20 tick = 1 秒)</li>
 *   <li>{@code hud on|off} — 手・ホットバーなどの表示</li>
 *   <li>{@code look <yaw> <pitch>} — 視点の向き</li>
 *   <li>{@code camera first|back|front} — 一人称 / 三人称(後ろから)/ 三人称(前から)。自分の装備を撮るときに</li>
 *   <li>{@code interact <エンティティ id>} — 6 ブロック以内で一番近いその種類を右クリック(サーバーの届く距離は約 3)</li>
 *   <li>{@code attack <エンティティ id>} — 同じく殴る</li>
 *   <li>{@code respawn} — 死んでいたらリスポーン(死亡画面のボタンと同じ)。先に {@code cmd gamerule doImmediateRespawn true} にしておくと画面自体が出ない</li>
 *   <li>{@code status} — 画面・次元・座標・FPS・エンティティ数</li>
 *   <li>{@code echo <文字>} — out.log に目印を書く(バッチの終わりの合図に)</li>
 *   <li>{@code close} — 開いている画面を閉じる</li>
 *   <li>{@code leave} — 保存してタイトル画面へ戻る</li>
 *   <li>{@code quit} — ゲームを閉じる</li>
 * </ul>
 * 状態が変わると {@code TITLE}(タイトル画面)・{@code READY}(ワールドが見えている)・{@code SCREEN}(画面の切り替わり)を書く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DevBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("ov.devbridge");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** newworld / open / leave のあと、次の状態になるまで待つ上限(5 分)。 */
    private static final long AWAIT_LIMIT_TICKS = 20L * 60 * 5;

    /** コマンドの返事を out.log へ流す。プレイヤーのチャットには出さない。 */
    private static final CommandSource OUTPUT = new CommandSource() {
        @Override
        public void sendSystemMessage(Component component) {
            log("  | " + component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };

    private static Path dir;
    private static boolean started;
    private static long tick;
    private static long waitUntil;
    /** {@code hold} で右クリック(使う)を押し続ける tick の終わり(0 = 押していない。2026-10-08、火炎放射器の確かめ用)。 */
    private static long holdUntil;
    /** dig(2026-11-08): 壊している所・面・始めの状態・始めた tick・打ち切る tick・狙いの点。 */
    private static net.minecraft.core.BlockPos digPos;
    private static net.minecraft.core.Direction digFace;
    private static net.minecraft.world.level.block.state.BlockState digState;
    private static long digStart;
    private static long digUntil;
    private static Vec3 digAim;

    /** マウスを掴んでいることにする / 戻す(dig 用。MouseHandler#mouseGrabbed を直に。カーソルの形は変えない)。 */
    private static void setMouseGrabbed(Minecraft mc, boolean grabbed) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.MouseHandler.class.getDeclaredField("mouseGrabbed");
            f.setAccessible(true);
            f.setBoolean(mc.mouseHandler, grabbed);
        } catch (ReflectiveOperationException e) {
            log("ERR mouseGrabbed: " + e);
        }
    }
    /** sneak <tick>(2026-10-22、クリエイティブで飛びながら下りる確かめ): スニークを押している終わりの tick。 */
    private static long sneakUntil;
    /** walk <tick>(2026-10-23、シダの胞子の確かめ): 前へ歩くキーを押している終わりの tick。 */
    private static long walkUntil;
    private static List<String> lines = List.of();
    private static long lastSize = -1;
    private static long lastModified = -1;
    /** in.txt の次に実行する行(0 始まり)。 */
    private static int next;
    private static boolean onTitle;
    private static boolean inWorld;
    private static String lastScreen = "";
    /** "world" か "title" を待っている間は命令を進めない。 */
    private static String awaiting;
    private static long awaitingSince;
    /** 前提待ちの理由(同じ理由を毎 tick 書かない)。 */
    private static String waitingFor;

    /** navto の続き(着くまで引き直す。サーバーの Mob をクライアントの tick から見る。開発用)。 */
    private static volatile net.minecraft.world.entity.Mob navMob;
    private static double[] navTarget;
    private static long navUntil;

    /** detourbench の続き(サーバーのスレッドで読み書きする)。 */
    private static java.util.List<com.yayuy.overgrownvegetation.entity.FalcimnesEntity> benchMobs;
    private static long[] benchArrive;
    private static double[] benchX;
    private static double benchY, benchZ;
    private static long benchStart, benchCalls0;
    private static String benchMode;

    /** detourbench の 1 tick: 着いていない個体は経路が終わっていれば引き直す。全員着いたか 1200 tick で結果を書いて片付ける。 */
    private static void benchStep(net.minecraft.server.level.ServerLevel level) {
        var mobs = benchMobs;
        if (mobs == null) return;
        long t = level.getGameTime() - benchStart;
        int arrived = 0;
        for (int i = 0; i < mobs.size(); i++) {
            var f = mobs.get(i);
            if (benchArrive[i] >= 0) {
                arrived++;
                continue;
            }
            if (!f.isAlive()) continue;
            if (f.getZ() >= benchZ - 1.5D && Math.abs(f.getX() - benchX[i]) < 3.0D) {
                benchArrive[i] = t;
                arrived++;
            } else if (f.getNavigation().isDone()) {
                f.getNavigation().moveTo(benchX[i], benchY, benchZ, 1.0D);
            }
        }
        if (arrived == mobs.size() || t >= 1200L) {
            long total = com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.totalNanos;
            StringBuilder arr = new StringBuilder();
            for (long a : benchArrive) arr.append(' ').append(a);
            log(String.format(Locale.ROOT, "DETOURBENCH %s 体 %d 着いた %d tick %d 1tick最大 %.2f ms 平均 %.3f ms 合計 %.1f ms 回り道 %d 下調べ %d (%.2f ms/回) 使い回し %d 予算待ち %d 着いた tick%s",
                    benchMode, mobs.size(), arrived, t, com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.maxTickNanos / 1e6,
                    total / 1e6 / Math.max(1L, t), total / 1e6, com.yayuy.overgrownvegetation.entity.FalcimnesEntity.widePathCalls - benchCalls0,
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.calls,
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.nanos / 1e6 / Math.max(1L, com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.calls),
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.shared, com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.deferred, arr));
            for (var f : mobs) f.discard();
            benchMobs = null;
        }
    }

    private DevBridge() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (!started && !start(mc)) return;
        // 始められなかった(別のクライアントが使っている等)ら、次の tick からも何もしない(2026-10-20 まで 2 tick 目で dir の null を踏んで落ちていた)
        if (dir == null) return;
        GazeDev.tick();
        com.yayuy.overgrownvegetation.client.EggFitProbe.tick();
        if (navMob != null && tick % 5 == 0) {
            // navto の続き: 標的のいない Mob は待機の仕草を始めると経路を止めるので、着くまで引き直す
            net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
            net.minecraft.world.entity.Mob m = navMob;
            double[] t = navTarget;
            if (server == null || tick > navUntil || !m.isAlive()) {
                navMob = null;
            } else {
                server.execute(() -> {
                    if (m.distanceToSqr(t[0], t[1], t[2]) < 4.0D) {
                        navMob = null;
                        log(String.format(java.util.Locale.ROOT, "NAVTO 着いた %.1f %.1f %.1f", m.getX(), m.getY(), m.getZ()));
                    } else if (m.getNavigation().isDone()) {
                        m.getNavigation().moveTo(t[0], t[1], t[2], t[3]);
                    }
                });
            }
        }
        if (mc.player != null && mc.getSingleplayerServer() != null) {
            // 竹馬型の navto の続き・stiltbench(11-10)
            net.minecraft.client.server.IntegratedServer ss = mc.getSingleplayerServer();
            var sdim = mc.player.level().dimension();
            ss.execute(() -> {
                var lv = ss.getLevel(sdim);
                if (lv != null) StiltDev.tick(lv, lv.getGameTime(), DevBridge::log);
                if (lv != null) TowerDev.tick(lv, DevBridge::log);
            });
        }
        if (benchMobs != null && mc.player != null) {
            net.minecraft.client.server.IntegratedServer bs = mc.getSingleplayerServer();
            var dim = mc.player.level().dimension();
            if (bs != null) bs.execute(() -> {
                var lv = bs.getLevel(dim);
                if (lv != null) benchStep(lv);
            });
        }
        if (sneakUntil > 0L) {
            // スニーク(クリエイティブで飛んでいるときは下降)の押しっぱなし。hold と同じく毎 tick 押し直す。
            if (tick < sneakUntil && mc.player != null && mc.screen == null) {
                mc.options.keyShift.setDown(true);
            } else {
                mc.options.keyShift.setDown(false);
                sneakUntil = 0L;
                log("SNEAK 終わり");
            }
        }
        if (walkUntil > 0L) {
            if (tick < walkUntil && mc.player != null && mc.screen == null) {
                mc.options.keyUp.setDown(true);
            } else {
                mc.options.keyUp.setDown(false);
                walkUntil = 0L;
                log("WALK 終わり");
            }
        }
        if (holdUntil > 0L) {
            // 押しっぱなしは毎 tick 押し直す(窓が裏に回ると全部のキーが離される)。
            if (tick < holdUntil && mc.player != null && mc.screen == null) {
                mc.options.keyUse.setDown(true);
            } else {
                mc.options.keyUse.setDown(false);
                holdUntil = 0L;
                log("HOLD 終わり");
            }
        }
        if (digUntil > 0L && mc.player != null && mc.level != null && mc.gameMode != null) {
            // dig: 毎 tick 採掘を続ける(左クリック長押しと同じ)。壊れたら(ブロックが替わったら)かかった tick を書く
            if (mc.level.getBlockState(digPos) != digState) {
                long t = tick - digStart;
                log(String.format(Locale.ROOT, "DIG %s %s %d tick (%.2f 秒) → 今 %s", digState.getBlock().builtInRegistryHolder().key().location(),
                        digPos.toShortString(), t, t / 20.0D, mc.level.getBlockState(digPos).getBlock().builtInRegistryHolder().key().location()));
                mc.gameMode.stopDestroyBlock();
                digUntil = 0L;
            } else if (tick >= digUntil) {
                log("DIG 壊れず(" + (tick - digStart) + " tick)");
                mc.gameMode.stopDestroyBlock();
                digUntil = 0L;
            } else {
                // 左クリックの押しっぱなし。バニラの Minecraft#continueAttack は「マウスを掴んでいる」ときしか続けず、
                // 掴んでいなければ毎 tick stopDestroyBlock で進みを捨てるので、掴んでいることにする(窓が裏でも測れるように)
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, digAim);
                mc.options.keyAttack.setDown(true);
                setMouseGrabbed(mc, true);
            }
            if (digUntil == 0L) {
                mc.options.keyAttack.setDown(false);
                setMouseGrabbed(mc, false);
            }
        }
        watchState(mc);
        if (awaiting != null) {
            // ワールドに入ったかは「画面が閉じた」ではなく「読み込みが終わった」で見る。死んだまま開くと死亡画面が出て
            // READY(画面なし)にならず、5 分待ってしまった(2026-10-02)。死亡画面なら次の respawn で抜けられる。
            boolean loaded = mc.level != null && mc.player != null && mc.getOverlay() == null
                    && !(mc.screen instanceof ReceivingLevelScreen) && !(mc.screen instanceof LevelLoadingScreen);
            boolean done = awaiting.equals("world") ? loaded : onTitle;
            if (done && awaiting.equals("world") && mc.screen != null) log("LOADED 画面 " + mc.screen.getClass().getSimpleName() + " が出ている");
            if (done) {
                awaiting = null;
            } else if (tick - awaitingSince > AWAIT_LIMIT_TICKS) {
                log("ERR " + awaiting + " を 5 分待っても来ない。待つのをやめて次の命令へ");
                awaiting = null;
            } else {
                return;
            }
        }
        if (tick % 5 == 0) reload();
        if (tick < waitUntil || next >= lines.size()) return;
        String line = lines.get(next).strip();
        if (line.isEmpty() || line.startsWith("#")) {
            next++;
            return;
        }
        try {
            String blocked = run(mc, line);
            if (blocked == null) {
                next++;
                waitingFor = null;
            } else if (!blocked.equals(waitingFor)) {
                waitingFor = blocked;
                log("WAIT-FOR " + blocked + " : " + line);
            }
        } catch (Exception e) {
            log("ERR " + line + " → " + e);
            next++;
            waitingFor = null;
        }
    }

    /** 最初の tick に 1 回。前回の in.txt / out.log を *.prev に回し、空の in.txt を作る。 */
    private static boolean start(Minecraft mc) {
        started = true;
        try {
            dir = mc.gameDirectory.toPath().resolve("devbridge");
            Files.createDirectories(dir);
            Path pid = dir.resolve("running.pid");
            String me = Long.toString(ProcessHandle.current().pid());
            // 別のクライアントが同じフォルダで動いていたら、命令のファイルを回さずに止める(2026-10-16、2 つのセッションのクライアントが
            // 同じ in.txt を読んでぶつかった。後から起動した方が前の in.txt を回し、先に終わった方が running.pid を消していた)。
            if (Files.exists(pid)) {
                try {
                    long other = Long.parseLong(Files.readString(pid, StandardCharsets.UTF_8).trim());
                    if (other != ProcessHandle.current().pid() && ProcessHandle.of(other).map(ProcessHandle::isAlive).orElse(false)) {
                        LOGGER.error("devbridge: 別のクライアント(pid {})が {} を使っているので、このクライアントでは命令を受けない", other, dir);
                        dir = null;
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                    // 壊れた pid は上書きする
                }
            }
            rotate("in.txt", "in.prev.txt");
            rotate("out.log", "out.prev.log");
            Files.writeString(dir.resolve("in.txt"), "", StandardCharsets.UTF_8);
            Files.writeString(pid, me, StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 自分の pid が書いてあるときだけ消す(後から別のクライアントが書いたものは残す)。
                    if (Files.exists(pid) && me.equals(Files.readString(pid, StandardCharsets.UTF_8).trim())) {
                        Files.delete(pid);
                    }
                } catch (IOException ignored) {
                    // 次の起動で上書きされる
                }
            }, "devbridge-pid-cleanup"));
            log("START pid=" + ProcessHandle.current().pid() + " dir=" + dir.toAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.error("devbridge: {} を用意できないので止める", dir, e);
            dir = null;
            return false;
        }
    }

    private static void rotate(String from, String to) throws IOException {
        Path src = dir.resolve(from);
        if (Files.exists(src)) Files.move(src, dir.resolve(to), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void watchState(Minecraft mc) {
        boolean title = mc.level == null && mc.getOverlay() == null && mc.screen instanceof TitleScreen;
        if (title && !onTitle) log("TITLE");
        onTitle = title;

        IntegratedServer server = mc.getSingleplayerServer();
        boolean world = mc.level != null && mc.player != null && server != null
                && mc.getOverlay() == null && mc.screen == null;
        if (world && !inWorld) log("READY " + server.getWorldData().getLevelName() + " " + mc.level.dimension().location());
        inWorld = world;

        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            log("SCREEN " + screen);
            lastScreen = screen;
        }
    }

    /** in.txt が変わっていれば読み直す(追記だけの約束)。 */
    private static void reload() {
        Path in = dir.resolve("in.txt");
        try {
            if (!Files.exists(in)) return;
            long size = Files.size(in);
            long modified = Files.getLastModifiedTime(in).toMillis();
            if (size == lastSize && modified == lastModified) return;
            lastSize = size;
            lastModified = modified;
            lines = Files.readAllLines(in, StandardCharsets.UTF_8);
            if (next > lines.size()) {
                log("ERR in.txt が短くなった(" + lines.size() + " 行 < 読んだ " + next + " 行)。追記だけにすること。続きは末尾から");
                next = lines.size();
            }
        } catch (IOException e) {
            // 書いている途中に当たることがある。次の回に読み直す
            lastSize = -1;
        }
    }

    private static String run(Minecraft mc, String line) throws IOException {
        String[] parts = line.split("\\s+", 2);
        String op = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (op) {
            case "newworld" -> {
                if (!onTitle) return "タイトル画面";
                newWorld(mc, arg);
                await("world");
            }
            case "open" -> {
                if (!onTitle) return "タイトル画面";
                log("OPEN " + arg);
                await("world");
                // 1.20.1: openWorld(名前, 失敗のとき) は無い。loadLevel は失敗すると画面で知らせる(戻り先はタイトル画面)
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), arg);
            }
            case "cmd" -> {
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || mc.player == null || server == null) return "ワールドの中";
                command(server, mc.player.getUUID(), arg);
            }
            case "shot" -> shot(mc, arg);
            case "wait" -> waitUntil = tick + Long.parseLong(arg);
            case "ticklog" -> { // サーバーの tick の時間を次の N tick ぶん(11-23 サーバーの tick の軽量化)。ticklog <名前> <tick 数>
                IntegratedServer server = mc.getSingleplayerServer();
                if (server == null) return "ワールドの中";
                log(TickLog.start(server, dir, arg, DevBridge::log));
            }
            case "hold" -> {
                // 右クリック(使う)を <tick> だけ押し続ける。押し始めは 1 回のクリックとして数える(長押しで使う道具が始まる)。
                // 次の命令は待たない(押している間に look・shot ができる。待つなら続けて wait)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                net.minecraft.client.KeyMapping.click(mc.options.keyUse.getKey());
                mc.options.keyUse.setDown(true);
                holdUntil = tick + Math.max(1L, ticks);
                log("HOLD " + ticks + " tick");
            }
            case "walk" -> {
                // 前へ歩くキーを <tick> だけ押し続ける(本当に歩くので、動いた分が xo との差に出る。次の命令は待たない)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                mc.options.keyUp.setDown(true);
                walkUntil = tick + Math.max(1L, ticks);
                log("WALK " + ticks + " tick");
            }
            case "sneak" -> {
                // スニークを <tick> だけ押し続ける(クリエイティブで飛んでいれば下降。次の命令は待たない)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                if (mc.player.getAbilities().mayfly && !mc.player.getAbilities().flying) {
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                }
                mc.options.keyShift.setDown(true);
                sneakUntil = tick + Math.max(1L, ticks);
                log("SNEAK " + ticks + " tick");
            }
            case "hud" -> {
                mc.options.hideGui = arg.equalsIgnoreCase("off");
                log("HUD " + (mc.options.hideGui ? "off" : "on"));
            }
            case "findsea" -> {
                // findsea [半径]: ネザーで、読み込まれたチャンクのうち、溶岩の海(海面の高さの溶岩の上に 16 段以上の空き)と、
                // 低い岸(海面 + 1〜4 の床の上に 16 段以上の空き)の両方がある所を多い順に 8 つ(2026-11-12、先駆けの植物の写真の場所探し)
                String[] p = arg.trim().isEmpty() ? new String[0] : arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                int r = p.length > 0 ? Integer.parseInt(p[0]) : 128;
                net.minecraft.core.BlockPos me = mc.player.blockPosition();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    int s = com.yayuy.overgrownvegetation.corruption.NetherLava.surfaceY(level);
                    java.util.List<int[]> found = new java.util.ArrayList<>();
                    net.minecraft.core.BlockPos.MutableBlockPos q = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int cx = (me.getX() - r) >> 4; cx <= (me.getX() + r) >> 4; cx++) {
                        for (int cz = (me.getZ() - r) >> 4; cz <= (me.getZ() + r) >> 4; cz++) {
                            if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                            int sea = 0, shore = 0;
                            for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                                for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                                    int open = 0;
                                    if (level.getFluidState(q.set(x, s, z)).is(net.minecraft.world.level.material.Fluids.LAVA)) {
                                        while (open < 16 && level.getBlockState(q.set(x, s + 1 + open, z)).isAir()) open++;
                                        if (open >= 16) sea++;
                                        continue;
                                    }
                                    for (int f = s + 1; f <= s + 4; f++) {
                                        if (!level.getBlockState(q.set(x, f, z)).blocksMotion()) continue;
                                        open = 0;
                                        while (open < 16 && level.getBlockState(q.set(x, f + 1 + open, z)).isAir()) open++;
                                        if (open >= 16) {
                                            shore++;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (sea >= 16 && shore >= 8) found.add(new int[]{cx, cz, sea, shore, Math.min(sea, shore * 3)});
                        }
                    }
                    found.sort((a, b) -> b[4] - a[4]);
                    log("FINDSEA " + found.size() + " チャンク(海面 " + s + ")");
                    for (int i = 0; i < Math.min(8, found.size()); i++) {
                        int[] f = found.get(i);
                        log(String.format("FINDSEA チャンク %d %d(真ん中 %d %d)開けた海 %d 列・低い岸 %d 列", f[0], f[1], (f[0] << 4) + 8, (f[1] << 4) + 8, f[2], f[3]));
                    }
                });
            }
            case "findblock" -> {
                // findblock <ブロックの id> <半径>: プレイヤーのまわりで、そのブロックのうち地面から 1〜3 段の高さにあるものを近い順に 8 つ
                // (2026-11-08 夜、本物の森で地面近くの葉を探す)。返事は FINDBLOCK x y z 地面からの高さ
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                net.minecraft.world.level.block.Block want = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(p[0]));
                int r = p.length > 1 ? Integer.parseInt(p[1]) : 24;
                net.minecraft.core.BlockPos me = mc.player.blockPosition();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    java.util.List<int[]> found = new java.util.ArrayList<>();
                    net.minecraft.core.BlockPos.MutableBlockPos q = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -12; dy <= 12; dy++) {
                        q.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                        if (!level.getBlockState(q).is(want)) continue;
                        int h = 0;
                        for (int k = 1; k <= 4; k++) {
                            var below = level.getBlockState(q.below(k));
                            if (!below.isAir() && !below.is(want) && !below.getCollisionShape(level, q.below(k)).isEmpty()) { h = k; break; }
                        }
                        if (h >= 1 && h <= 3) found.add(new int[]{q.getX(), q.getY(), q.getZ(), h, Math.abs(dy) * 10000 + dx * dx + dz * dz});   // 高さの近いものから
                    }
                    found.sort(java.util.Comparator.comparingInt(a -> a[4]));
                    log("FINDBLOCK " + found.size() + " 個");
                    for (int i = 0; i < Math.min(16, found.size()); i++) {
                        int[] f = found.get(i);
                        log(String.format("FINDBLOCK %d %d %d 地面から %d", f[0], f[1], f[2], f[3]));
                    }
                });
            }
            case "navstat" -> {
                // 一番近い Mob の位置・経路の進み・動いている Goal(2026-11-08、navto の確かめ)。返事は NAVSTAT …
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                net.minecraft.world.phys.Vec3 me = mc.player.position();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    net.minecraft.world.entity.Mob best = null;
                    for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                            new net.minecraft.world.phys.AABB(me, me).inflate(64.0D))) {
                        if (m.isAlive() && (best == null || m.distanceToSqr(me) < best.distanceToSqr(me))) best = m;   // 死ぬ途中の個体は選ばない
                    }
                    if (best == null) {
                        log("NAVSTAT なし");
                        return;
                    }
                    net.minecraft.world.level.pathfinder.Path path = best.getNavigation().getPath();
                    StringBuilder goals = new StringBuilder();
                    best.goalSelector.getAvailableGoals().forEach(g -> {
                        if (g.isRunning()) goals.append(' ').append(g.getGoal().getClass().getSimpleName());
                    });
                    log(String.format(java.util.Locale.ROOT, "NAVSTAT pos %.2f %.2f %.2f yaw %.1f done %s next %s/%s noai %s ticking %s goals%s",
                            best.getX(), best.getY(), best.getZ(), best.getYRot(), best.getNavigation().isDone(),
                            path == null ? "-" : path.getNextNodeIndex(), path == null ? "-" : path.getNodeCount(), best.isNoAi(),
                            level.isPositionEntityTicking(best.blockPosition()), goals));
                });
            }
            case "detourplan" -> {
                // detourplan fx fy fz tx tz: 回り道の下調べを 1 回(使い回しなし)。返事は DETOURPLAN …、列の高さ 5 本も
                String[] p = arg.trim().split("\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                int fx = Integer.parseInt(p[0]), fy = Integer.parseInt(p[1]), fz = Integer.parseInt(p[2]), tx = Integer.parseInt(p[3]), tz = Integer.parseInt(p[4]);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.resetStats();
                    var plan = com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.plan(level, new net.minecraft.core.BlockPos(fx, fy, fz), new net.minecraft.core.BlockPos(tx, fy, tz), 2, true, 24.0D);
                    StringBuilder cols = new StringBuilder();
                    for (int z = fz; z <= tz; z++) cols.append(' ').append(z).append(':').append(com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.debugColumn(level, fx, z, fy, true));
                    log("DETOURPLAN " + (plan == null ? "なし" : plan.waypoint() + " 残り " + plan.gap()) + " 列" + cols);
                });
            }
            case "stiltbrainbench" -> {
                // stiltbrainbench watch|peer <n> <tick>: 竹馬型の見張り・覗きの重さ(11-14)。返事は STILTBRAINBENCH …
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null || p.length < 3) break;
                java.util.UUID id = mc.player.getUUID();
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                    if (sp != null) StiltDev.lookBench(sp.serverLevel(), sp, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), DevBridge::log);
                });
            }
            case "wallstyle" -> WallDev.run(arg, DevBridge::log); // 蠢く壁の見た目の案の切り替え(11-16)。返事は WALLSTYLE …
            case "scary" -> log("SCARY " + com.yayuy.overgrownvegetation.client.atmosphere.ScaryDev.run(arg)); // 怖い現象 A・D・E の確かめ(11-16)
            case "gazedev" -> GazeDev.run(arg.trim().split("\\s+"), DevBridge::log); // 見られているときの画面の縁(11-14)。返事は GAZEDEV …
            case "towerdev" -> {
                // towerdev …: 監視塔フィロセンスの姿勢・覗き・計測(11-14)。返事は TOWERDEV / TOWERBENCH …
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                java.util.UUID id = mc.player.getUUID();
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                    if (sp != null) TowerDev.run(sp.serverLevel(), sp, p, DevBridge::log);
                });
            }
            case "stiltlook" -> {
                // stiltlook x y z [倒す角] [光る 0/1] / stiltlook off: 竹馬型の首の確かめ(11-14)。返事は STILTLOOK …
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) break;
                net.minecraft.world.phys.Vec3 me = mc.player.position();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level != null) StiltDev.look(level, me, p, DevBridge::log);
                });
            }
            case "stiltbench" -> {
                // stiltbench <n> <cx> <y> <z0> <z1>: 竹馬型(フィロセンス)を n 体並べて z1 へ歩かせ、1 tick の最大・平均を測る(11-10)。返事は STILTBENCH …
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null || p.length < 5) {
                    log("STILTBENCH 引数か統合サーバーが無い");
                    break;
                }
                int n = Integer.parseInt(p[0]), cx = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                int z0 = Integer.parseInt(p[3]), z1 = Integer.parseInt(p[4]);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    int fixed = p.length > 5 ? Integer.parseInt(p[5]) : 0;   // 6 つ目 = 着いても測り続ける tick(11-11)
                    if (level != null) StiltDev.bench(level, n, cx, y, z0, z1, fixed, p.length > 6 && p[6].equals("same"), level.getGameTime(), DevBridge::log);
                });
            }
            case "detourbench" -> {
                // detourbench <coarse|wide> <n> <cx> <y> <z0> <z1>: 慎重なファルキムネスを n 体、x = cx を中心に 2 ブロックおきに z0 へ並べ、
                // 全員を z1 へ歩かせる(間の生け垣を回り込ませる)。回り道に使った時間の 1 tick の最大・平均と、全員が着くまでの tick を返す。
                // 返事は DETOURBENCH …(2026-11-09、広い探し直しの重さの比べ)
                String[] p = arg.trim().split("\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null || p.length < 6) {
                    log("DETOURBENCH 引数か統合サーバーが無い");
                    break;
                }
                boolean coarse = !p[0].equals("wide");
                int n = Integer.parseInt(p[1]), cx = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
                int z0 = Integer.parseInt(p[4]), z1 = Integer.parseInt(p[5]);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    com.yayuy.overgrownvegetation.entity.FalcimnesEntity.detourCoarse = coarse;
                    com.yayuy.overgrownvegetation.entity.forest.CoarseDetour.resetStats();
                    benchCalls0 = com.yayuy.overgrownvegetation.entity.FalcimnesEntity.widePathCalls;
                    benchMobs = new java.util.ArrayList<>();
                    benchArrive = new long[n];
                    java.util.Arrays.fill(benchArrive, -1L);
                    benchX = new double[n];
                    for (int i = 0; i < n; i++) {
                        var f = com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get().create(level);
                        if (f == null) continue;
                        double x = cx - (n - 1) + 2 * i + 0.5;
                        f.moveTo(x, y, z0 + 0.5, 0.0F, 0.0F);
                        f.setCustomName(net.minecraft.network.chat.Component.literal("nostroll" + i));
                        f.setPersistenceRequired();
                        level.addFreshEntity(f);
                        f.setPersonality(1.0F, 0.0F, 0.5F);
                        benchMobs.add(f);
                        benchX[benchMobs.size() - 1] = x;
                    }
                    benchY = y;
                    benchZ = z1 + 0.5;
                    benchStart = level.getGameTime();
                    benchMode = coarse ? "coarse" : "wide";
                    log("DETOURBENCH 始め " + benchMode + " " + benchMobs.size() + " 体");
                });
            }
            case "navto" -> {
                // プレイヤーから 64 ブロック以内で一番近い Mob を (x y z) へ歩かせる(経路探索の本来の道。2026-11-08、ファルキムネスの森の中の移動)。
                // 返事は NAVTO 種類 経路のノード数 届くか / NAVTO なし
                String[] p = arg.trim().split("\\s+");
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) {
                    log("NAVTO 統合サーバーが無い");
                    break;
                }
                double tx = Double.parseDouble(p[0]), ty = Double.parseDouble(p[1]), tz = Double.parseDouble(p[2]);
                double speed = p.length > 3 ? Double.parseDouble(p[3]) : 1.0D;
                net.minecraft.world.phys.Vec3 me = mc.player.position();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(mc.player.level().dimension());
                    if (level == null) return;
                    net.minecraft.world.entity.Mob best = null;
                    for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                            new net.minecraft.world.phys.AABB(me, me).inflate(64.0D))) {
                        if (m.isAlive() && (best == null || m.distanceToSqr(me) < best.distanceToSqr(me))) best = m;   // 死ぬ途中の個体は選ばない
                    }
                    if (best == null) {
                        log("NAVTO なし");
                        return;
                    }
                    if (StiltDev.navto(best, tx, ty, tz, speed, level.getGameTime(), DevBridge::log)) return;   // 竹馬型(11-10)
                    net.minecraft.world.level.pathfinder.Path path = best.getNavigation().createPath(tx, ty, tz, 0);
                    best.getNavigation().moveTo(path, speed);
                    navTarget = new double[]{tx, ty, tz, speed};
                    navUntil = tick + 1200L;
                    navMob = best;
                    StringBuilder nodes = new StringBuilder();
                    if (path != null) {
                        for (int i = 0; i < path.getNodeCount(); i++) {
                            net.minecraft.world.phys.Vec3 c = path.getEntityPosAtNode(best, i);
                            nodes.append(String.format(java.util.Locale.ROOT, " (%.1f,%.1f)", c.x, c.z));
                        }
                    }
                    log("NAVTO " + best.getType().getDescriptionId() + " ノード " + (path == null ? -1 : path.getNodeCount())
                            + " 届く " + (path != null && path.canReach()) + nodes);
                });
            }
            case "hitboxes" -> {
                // 当たり判定の箱(F3 + B と同じ。叩ける部位の箱も出る)を出す / 消す(2026-11-08、ファルキムネスの細い芯と部位)
                mc.getEntityRenderDispatcher().setRenderHitBoxes(!arg.equalsIgnoreCase("off"));
                log("HITBOXES " + mc.getEntityRenderDispatcher().shouldRenderHitBoxes());
            }
            case "key" -> {
                // 割り当てたキーを 1 回押したことにする(名前は KeyMapping の名前。例 key key.overgrown_vegetation.sunlit_lantern。2026-10-16)
                String name = arg.trim();
                for (net.minecraft.client.KeyMapping mapping : mc.options.keyMappings) {
                    if (mapping.getName().equals(name)) {
                        net.minecraft.client.KeyMapping.click(mapping.getKey());
                        log("KEY " + name + " → " + mapping.getKey().getName());
                        return null;
                    }
                }
                return "そのキーの名前は無い: " + name;
            }
            case "design" -> {
                // 設計データ(config/overgrown_vegetation/*.json)を読み直す。見え方の色味の見本を起動し直さずに切り替える(2026-10-31)
                if (!arg.trim().equals("reload")) return "design reload だけ";
                com.yayuy.overgrownvegetation.data.DesignData.reload();
                log("DESIGN 読み直した");
                return null;
            }
            case "resreload" -> {
                // リソースの再読み込み(F3+T と同じ)。汚した声のバッファーが捨てられて作り直されるかの確かめ(2026-11-14)
                mc.reloadResourcePacks().thenRun(() -> log("RESRELOAD 終わり"));
                return null;
            }
            case "ripple" -> {
                // 波紋の部品(client/atmosphere/Ripples)を決めた所に出す。ripple drop <x y z> / ripple rain <x y z> [直径] [秒](2026-10-31 見本撮り)
                String[] p = arg.trim().split("\\s+");
                if (p.length < 4 || mc.level == null) return "ripple drop|rain <x y z> [直径] [秒]";
                double x = Double.parseDouble(p[1]), y = Double.parseDouble(p[2]), z = Double.parseDouble(p[3]);
                if (p[0].equals("drop")) {
                    com.yayuy.overgrownvegetation.client.atmosphere.Ripples.drop(mc.level, x, y, z, mc.level.getGameTime(), 1.0F);
                } else {
                    float size = p.length > 4 ? Float.parseFloat(p[4]) : 0.3F;
                    float sec = p.length > 5 ? Float.parseFloat(p[5]) : 0.4F;
                    com.yayuy.overgrownvegetation.client.atmosphere.Ripples.rain(mc.level, x, y, z, 0.0F, 1.0F, 0.0F, size, sec);
                }
                log("RIPPLE " + p[0]);
                return null;
            }
            case "cavelife" -> {
                // 洞窟の小ネタ(2026-11-07): cavelife glow = 真上の天井のまわりに土ボタルを必ず置く / cavelife cricket = 見ている先にカマドウマの群れを置く
                if (mc.level == null || mc.player == null) return "ワールドが無い";
                String what = arg.trim();
                if (what.equals("glow")) {
                    log("CAVELIFE glow " + com.yayuy.overgrownvegetation.client.atmosphere.CaveGlowworms.devPlace(mc.level, mc.player));
                } else if (what.startsWith("hold")) {
                    // cavelife hold on|off = カマドウマを近づいても跳ばせない(11-08 形の写真用)
                    com.yayuy.overgrownvegetation.client.atmosphere.CaveCrickets.devHold(what.endsWith("on"));
                    log("CAVELIFE hold " + what);
                } else if (what.equals("cricket")) {
                    log("CAVELIFE cricket " + com.yayuy.overgrownvegetation.client.atmosphere.CaveCrickets.devPlace(mc.level, mc.player));
                } else {
                    return "cavelife glow|cricket|hold on|hold off";
                }
                return null;
            }
            case "geninfect" -> {
                // 汎用の感染体の見た目の確かめ(2026-11-15): まわり <半径> の生き物を感染体として描かせる。geninfect 0 で戻す
                if (mc.level == null || mc.player == null) return "ワールドが無い";
                if (arg.trim().startsWith("set ")) {
                    // geninfect set <番号> <値>: 塗りの数をその場で変える(InfectPaint.TUNE)。次の geninfect <半径> で塗り直す
                    String[] sp = arg.trim().split("\\s+");
                    log("GENINFECT set " + com.yayuy.overgrownvegetation.client.infect.InfectClientEvents.devTune(Integer.parseInt(sp[1]), Double.parseDouble(sp[2])));
                    break;
                }
                if (arg.trim().equals("dump")) {
                    // geninfect dump: この後に作る塗り替えた絵と位置の絵を devbridge/infect_dump/ へ書き出す
                    com.yayuy.overgrownvegetation.client.infect.InfectClientEvents.devDump(dir.resolve("infect_dump"));
                    log("GENINFECT dump");
                    break;
                }
                if (arg.trim().startsWith("parts")) {
                    // geninfect parts on|off: 体から生える部品(毎フレーム頂点を覗く)の切り替え。重さの比べ用
                    log("GENINFECT parts " + com.yayuy.overgrownvegetation.client.infect.InfectClientEvents.devParts(arg.trim().endsWith("on")));
                    break;
                }
                double r = arg.isBlank() ? 16.0D : Double.parseDouble(arg.trim());
                log("GENINFECT " + com.yayuy.overgrownvegetation.client.infect.InfectClientEvents.devMark(mc.level, mc.player.position(), r));
            }
            case "geninfectstate" -> {
                // 汎用の感染体のクライアントの印(サーバーから届いた分)の数と、いちばん近い印の中身(2026-11-15 土台の同期の確かめ)
                if (mc.level == null || mc.player == null) return "ワールドが無い";
                StringBuilder sb = new StringBuilder("GENINFECTSTATE 数 " + com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.count());
                net.minecraft.world.entity.Entity best = null;
                for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                    if (com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.of(e) != null
                            && (best == null || e.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) best = e;
                }
                if (best != null) {
                    var s = com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.of(best);
                    sb.append(" 近い ").append(best.getType().getDescriptionId()).append(" #").append(best.getId())
                            .append(" 種 ").append(Long.toHexString(s.seed())).append(" 進み ")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", s.progress(mc.level.getGameTime(), 0.0F)))
                            .append(" ボス ").append(s.boss());
                }
                log(sb.toString());
            }
            case "critters" -> {
                // 見た目だけの小さな生き物(client/atmosphere/SmallCritters、11-17)。critters status / near <種|all> / only <種|all> /
                // force biome|time|still on|off / live <0〜1|off>(升の賑わいを差し替え)。返事は CRITTERS …
                String[] p = arg.trim().split("\\s+");
                if (mc.level == null || mc.player == null) return "ワールドが無い";
                String res;
                switch (p[0]) {
                    case "near" -> res = com.yayuy.overgrownvegetation.client.atmosphere.SmallCritters.devNearest(p.length > 1 ? p[1] : "all",
                            mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
                    case "only" -> res = "only " + com.yayuy.overgrownvegetation.client.atmosphere.SmallCritters.devOnly(p.length > 1 ? p[1] : "all");
                    case "force" -> res = p.length < 3 ? "critters force biome|time|still on|off"
                            : com.yayuy.overgrownvegetation.client.atmosphere.SmallCritters.devForce(p[1], p[2].equals("on"));
                    case "live" -> {
                        if (p.length > 1 && !p[1].equals("off")) {
                            com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.debugOverride(Float.parseFloat(p[1]));
                        } else {
                            com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.clearDebugOverride();
                        }
                        res = "live " + com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.liveliness(mc.player.getX(), mc.player.getZ());
                    }
                    default -> res = com.yayuy.overgrownvegetation.client.atmosphere.SmallCritters.devDescribe() + " 賑わい "
                            + com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.liveliness(mc.player.getX(), mc.player.getZ())
                            + " 届いた " + com.yayuy.overgrownvegetation.client.atmosphere.EcoCellsClient.received();
                }
                log("CRITTERS " + res);
                return null;
            }
            case "lizard" -> {
                // 幹のトカゲ(client/atmosphere/TrunkLizards)を決めた木部のブロックの面に 1 匹置く(逃げ・湧きを止める)。lizard <x y z> <nx> <nz> [向き°] / lizard off(2026-11-07 見え方の前後)
                String[] p = arg.trim().split("\\s+");
                if (mc.level == null) return "ワールドが無い";
                if (p[0].equals("off")) {
                    com.yayuy.overgrownvegetation.client.atmosphere.TrunkLizards.devRelease();
                    log("LIZARD off");
                    return null;
                }
                if (p.length < 5) return "lizard <x y z> <nx> <nz> [向き°] / lizard off";
                double heading = p.length > 5 ? Math.toRadians(Double.parseDouble(p[5])) : 0.35;
                log("LIZARD " + com.yayuy.overgrownvegetation.client.atmosphere.TrunkLizards.devPlace(mc.level, Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), heading));
                return null;
            }
            case "clouds" -> {
                // 雲(2026-11-08 雲の段階 1、client/sky/VoxelClouds): status = 有効・量・面の数・遠景・段・雲の速さ。返事は CLOUDS …
                log("CLOUDS " + com.yayuy.overgrownvegetation.client.sky.VoxelClouds.describe() + " / " + com.yayuy.overgrownvegetation.client.atmosphere.CloudShadows.describe()
                        + " / " + com.yayuy.overgrownvegetation.client.atmosphere.RainFeel.describe());
                return null;
            }
            case "fog" -> {
                // 霧(2026-10-30 夜 第 3 段、client/atmosphere/MistDev): status / march on|off / time on|off / stat。返事は FOG …
                log(com.yayuy.overgrownvegetation.client.atmosphere.MistDev.command(arg));
                return null;
            }
            case "wx" -> {
                // 天気の見た目の残り(2026-11-16、client/atmosphere/WeatherLookDev): status / w … / off|on / set / far / sprite / values。返事は WX …
                log(com.yayuy.overgrownvegetation.client.atmosphere.WeatherLookDev.command(arg));
                return null;
            }
            case "wind" -> {
                // 汚染地域の風(CanopyWind)の gust / calm / hide / leafhist / lookshaft と状態の表示は、林冠の光と風と一緒に 2026-10-30 に parked/ へ退避した
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                String what = arg.trim().toLowerCase(java.util.Locale.ROOT);
                // 林の風の場(2026-10-30 夜 第 2 段で client/atmosphere/GustField として戻した): gust = カメラの風上に強い突風の前線
                // (約 10 秒後に届く)/ calm = 突風を消して 1 分凪 / status = 今の風。返事は WIND …
                com.yayuy.overgrownvegetation.client.atmosphere.GustField field = com.yayuy.overgrownvegetation.client.atmosphere.GustField.INSTANCE;
                if (what.equals("gust")) {
                    field.forceGust(player.getX(), player.getZ());
                    log("WIND gust " + field.describe(player.getX(), player.getZ()));
                    return null;
                } else if (what.equals("calm")) {
                    field.calm();
                    log("WIND calm");
                    return null;
                } else if (what.startsWith("spores ")) {
                    // 環境の胞子の梢より上の出し方を切り替えて撮り比べる(0 = 梢の高さで切る / 1 = 森の度合いで薄く / 2 = 両方 / 3 = なし)
                    com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.sporeCeilingMode(Integer.parseInt(what.substring(7).trim()));
                    log("WIND spores " + what.substring(7).trim());
                    return null;
                } else if (what.startsWith("sporelit ")) {
                    // 環境の胞子が日向で明るくなる割合をこのセッションだけ替える(負で設計値)
                    com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.sporeLit(Float.parseFloat(what.substring(9).trim()));
                    log("WIND sporelit " + what.substring(9).trim());
                    return null;
                } else if (what.startsWith("cave ")) {
                    // 地下モードの点光源の数の上限をこのセッションだけ替える(負で設計値。F3 の ms を 0 個と 24 個で比べる、11-01)
                    com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.caveMaxLights(Integer.parseInt(what.substring(5).trim()));
                    log("WIND cave " + com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.caveStatus());
                    return null;
                } else if (what.startsWith("cavedev ")) {
                    // 地下の地図の確かめ(11-02): cavedev thin on|off(前の数え方)/ cavedev tint on|off(地下の係数を赤で)。返事は WIND cavedev …
                    log("WIND cavedev " + com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.caveDev(what.substring(8)));
                    return null;
                } else if (what.equals("caveprobe")) {
                    // 地下の地図の点検(11-02 霧の剥がれ): 宙に浮いた「岩」の列をブロックごとに数える。返事は WIND caveprobe …
                    log("WIND caveprobe " + com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.caveProbe(mc.level, player.getEyePosition()));
                    return null;
                } else if (what.startsWith("afterglow ")) {
                    // 前線が過ぎたあとの粒の余韻の長さ(秒)をこのセッションだけ替える(負で設計値に戻す)
                    com.yayuy.overgrownvegetation.client.atmosphere.AtmosphereDev.afterglow(Double.parseDouble(what.substring(10).trim()));
                    log("WIND afterglow " + what.substring(10).trim());
                    return null;
                } else if (what.equals("status")) {
                    log("WIND " + field.describe(player.getX(), player.getZ()) + " 揺れ "
                            + String.format("%.2f", com.yayuy.overgrownvegetation.client.atmosphere.WindGate.value(0.0F)));
                    return null;
                }
                if (what.startsWith("omen")) {
                    return null;
                } else if (what.startsWith("column") || what.startsWith("bulge") || what.startsWith("dive")) {
                    // 雲の面(2026-10-19、sky.CloudSky#columnAt・sky.CloudSurface): column <x> <z> = 雲の柱を書く / bulge <x> <z> <半径> <盛り上がる tick> <保つ tick>
                    // / dive <x> <z> <半径> <埋まり始め tick> <埋まりきり tick> = 雲の柱を貫く丸い穴。返事は WIND column|bulge|dive …
                    String[] p = what.split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    double px = Double.parseDouble(p[1]);
                    double pz = Double.parseDouble(p[2]);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        if (p[0].equals("column")) {
                        } else if (p[0].equals("bulge")) {
                        } else {
                        }
                    });
                    return null;
                } else if (what.startsWith("puff")) {
                    // 吐いた雲(2026-10-19、sky.CloudPuffs#puff): puff <x> <y> <z> <半径> <高さ> <長さ tick>。返事は雲の番号。
                    String[] p = what.substring(4).trim().split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    double px = Double.parseDouble(p[0]);
                    double py = Double.parseDouble(p[1]);
                    double pz = Double.parseDouble(p[2]);
                    float pr = Float.parseFloat(p[3]);
                    float ph = Float.parseFloat(p[4]);
                    int pl = Integer.parseInt(p[5]);
                    return null;
                } else if (what.startsWith("bolt")) {
                    // 決めた 2 点の間の雷(2026-10-19、CloudLightning#strikeBetween): bolt <x1> <y1> <z1> <x2> <y2> <z2> [1 = 即死]。返事は前兆の tick。
                    String[] p = what.substring(4).trim().split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    Vec3 from = new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                    Vec3 to = new Vec3(Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]));
                    boolean harmful = p.length > 6 && p[6].equals("1");
                    return null;
                } else if (what.startsWith("holeshot")) {
                    // 雲の穴(2026-10-18): プレイヤーの所から本物の砲弾(攻城砲)をほぼ真上へ撃つ(着弾しても地形は壊れない)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    Vec3 at = player.position();
                    float yaw = player.getYRot();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        double h = 1.2;
                        Vec3 v = new Vec3(-Math.sin(Math.toRadians(yaw)) * h, 9.0, Math.cos(Math.toRadians(yaw)) * h);
                    });
                    log("WIND holeshot " + Math.round(at.x) + " " + Math.round(at.y) + " " + Math.round(at.z));
                    return null;
                } else if (what.startsWith("hole")) {
                    // 雲の穴(2026-10-18): hole <x> <y> <z> [半径] でその点を縦に貫く穴を開ける。
                    String[] p = what.substring(4).trim().split("\\s+");
                    double hx = Double.parseDouble(p[0]);
                    double hy = Double.parseDouble(p[1]);
                    double hz = Double.parseDouble(p[2]);
                    float hr = p.length > 3 ? Float.parseFloat(p[3]) : 24.0F;
                    return null;
                } else if (what.equals("leyflow")) {
                    return null;
                } else if (what.equals("gaps")) {
                    // 近くの林冠の穴(木を植えない輪、TreeSpacing#canopyGap)の中心と半径を、近い順に 6 つまで書く(2026-10-16)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND gaps: 統合サーバーが無い");
                        return null;
                    }
                    long seed = server.overworld().getSeed();
                    double chance = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.treeCanopyGapChance.get();
                    int grid = com.yayuy.overgrownvegetation.corruption.TreeSpacing.GAP_GRID;
                    int gx = Math.floorDiv((int) Math.floor(player.getX()), grid);
                    int gz = Math.floorDiv((int) Math.floor(player.getZ()), grid);
                    java.util.List<double[]> gaps = new java.util.ArrayList<>();
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            double[] g = com.yayuy.overgrownvegetation.corruption.TreeSpacing.canopyGap(seed, gx + dx, gz + dz, chance);
                            if (g != null) {
                                gaps.add(g);
                            }
                        }
                    }
                    gaps.sort(java.util.Comparator.comparingDouble(g -> (g[0] - player.getX()) * (g[0] - player.getX()) + (g[1] - player.getZ()) * (g[1] - player.getZ())));
                    StringBuilder sb = new StringBuilder("WIND gaps");
                    for (int i = 0; i < Math.min(6, gaps.size()); i++) {
                        double[] g = gaps.get(i);
                        sb.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f r%.1f)", g[0], g[1], g[2]));
                    }
                    log(sb.toString());
                    return null;
                } else if (what.startsWith("layley")) {
                    // 周り r ブロック(既定 24)の本物の地脈の線(強さ 0.5 以上)の上の地面を地脈ブロックにする(2026-10-16、極相の密林の確かめ用)。
                    String rs = what.substring(6).trim();
                    int radius = rs.isEmpty() ? 24 : Integer.parseInt(rs);
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND layley: 統合サーバーが無い");
                        return null;
                    }
                    int px = net.minecraft.util.Mth.floor(player.getX());
                    int pz = net.minecraft.util.Mth.floor(player.getZ());
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        double[] flow = new double[4];
                        int placed = 0;
                        for (int x = px - radius; x <= px + radius; x++) {
                            for (int z = pz - radius; z <= pz + radius; z++) {
                                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                                placed++;
                            }
                        }
                        log("WIND layley " + placed);
                    });
                    return null;
                } else if (what.equals("crystal")) {
                    // 足元から 3 ブロック先にエネルギー結晶の柱(高さ 5)を立てる(2026-10-16、結晶の共鳴の確かめ用)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return null;
                    }
                    net.minecraft.core.BlockPos base = player.blockPosition().offset(3, 0, 0);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        for (int dy = 0; dy < 5; dy++) {
                        }
                        log("WIND crystal " + base.getX() + " " + base.getY() + " " + base.getZ());
                    });
                    return null;
                } else if (what.equals("leynear")) {
                    // いちばん近い地脈の線の点と、地脈探しの針の先を出す(汚染の有無に関係なく。2026-10-22、地脈の花の確かめ)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND leynear: 統合サーバーが無い");
                        return null;
                    }
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                    });
                    return null;
                } else if (what.startsWith("findblock ")) {
                    // 読み込み済みの周り(半径 r、地表の上下 8)で、そのブロックのいちばん近い 1 つ(2026-10-22、天然の地脈の結晶を撮る)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    String[] a = what.substring("findblock ".length()).trim().split("\\s+");
                    if (server == null || a.length < 2) {
                        log("WIND findblock: 使い方 findblock <id> <半径>");
                        return null;
                    }
                    net.minecraft.world.level.block.Block target = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(a[0]));
                    int r = Integer.parseInt(a[1]);
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.core.BlockPos best = null;
                        double bestD = Double.MAX_VALUE;
                        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                int x = from.getX() + dx;
                                int z = from.getZ() + dz;
                                if (!level.hasChunk(x >> 4, z >> 4)) {
                                    continue;
                                }
                                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                                for (int y = top - 8; y <= top + 2; y++) {
                                    if (level.getBlockState(m.set(x, y, z)).is(target)) {
                                        double d = (double) dx * dx + (double) dz * dz;
                                        if (d < bestD) {
                                            bestD = d;
                                            best = m.immutable();
                                        }
                                    }
                                }
                            }
                        }
                        log("WIND findblock " + (best == null ? "なし" : best.getX() + " " + best.getY() + " " + best.getZ()));
                    });
                    return null;
                } else if (what.startsWith("leybiome ")) {
                    // そのバイオームの上を通る地脈の線の点を探す(半径 1024、8 おき。読み込まずにバイオームの源を引く。2026-10-22)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND leybiome: 統合サーバーが無い");
                        return null;
                    }
                    String biomeId = what.substring("leybiome ".length()).trim();
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        var gen = level.getChunkSource().getGenerator();
                        var sampler = level.getChunkSource().randomState().sampler();
                        for (int r = 0; r <= 1024; r += 8) {
                            for (int dx = -r; dx <= r; dx += 8) {
                                for (int dz = -r; dz <= r; dz += 8) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                        continue;
                                    }
                                    int x = from.getX() + dx;
                                    int z = from.getZ() + dz;
                                }
                            }
                        }
                        log("WIND leybiome なし");
                    });
                    return null;
                } else if (what.equals("gotoley")) {
                    // いちばん近い「過繁茂の地域を通る地脈の線」の上へ動く(統合サーバーで線の場を引く。半径 24 チャンク)。2026-10-16
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND gotoley: 統合サーバーが無い");
                        return null;
                    }
                    java.util.UUID id = player.getUUID();
                    int pcx = player.chunkPosition().x;
                    int pcz = player.chunkPosition().z;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        double[] flow = new double[4];
                        for (int r = 0; r <= 24; r++) {
                            for (int dz = -r; dz <= r; dz++) {
                                for (int dx = -r; dx <= r; dx++) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                        continue;
                                    }
                                    int cx = pcx + dx;
                                    int cz = pcz + dz;
                                    for (int i = 0; i < 256; i++) {
                                        int x = (cx << 4) + (i & 15);
                                        int z = (cz << 4) + (i >> 4);
                                        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                                        // 巨大樹の枝の上などへ行かないよう、地面の高さはプレイヤーの高さの近くから下へ探す。
                                        int startY = Math.min(y, (int) player.getY() + 8);
                                        for (y = startY; y > level.getMinBuildHeight() + 1; y--) {
                                            if (level.getBlockState(new net.minecraft.core.BlockPos(x, y - 1, z)).canOcclude()) {
                                                break;
                                            }
                                        }
                                        if (!com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(
                                                level.getBiome(new net.minecraft.core.BlockPos(x, y, z)))) {
                                            break;
                                        }
                                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                                        if (sp != null) {
                                            sp.teleportTo(level, x + 0.5, y + 1.0, z + 0.5, sp.getYRot(), sp.getXRot());
                                        }
                                        log("WIND gotoley " + x + " " + y + " " + z + " 向き " + String.format("%.2f %.2f", flow[1], flow[2]));
                                        return;
                                    }
                                }
                            }
                        }
                        log("WIND gotoley: 24 チャンク以内に過繁茂を通る線が無い");
                    });
                    return null;
                }
            }
            case "look" -> {
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                float yaw = Float.parseFloat(a[0]);
                float pitch = a.length > 1 ? Float.parseFloat(a[1]) : player.getXRot();
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.yRotO = yaw;
                player.xRotO = pitch;
                player.setYHeadRot(yaw);
                log(String.format(Locale.ROOT, "LOOK yaw=%.1f pitch=%.1f", yaw, pitch));
            }
            case "camera" -> {
                CameraType type = switch (arg.toLowerCase(Locale.ROOT)) {
                    case "back" -> CameraType.THIRD_PERSON_BACK;
                    case "front" -> CameraType.THIRD_PERSON_FRONT;
                    default -> CameraType.FIRST_PERSON;
                };
                mc.options.setCameraType(type);
                log("CAMERA " + type);
            }
            case "interact", "attack" -> {
                LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null || mc.level == null) return "ワールドの中";
                Entity target = nearest(mc, player, arg);
                if (target == null) {
                    log("ERR " + op + ": 6 ブロック以内に " + arg + " が見えない");
                    return null;
                }
                player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
                String what = String.format(Locale.ROOT, "%s (距離 %.1f)", target.getType().builtInRegistryHolder().key().location(),
                        Math.sqrt(boxDistanceSqr(target.getBoundingBox(), player.getEyePosition())));
                if (op.equals("attack")) {
                    mc.gameMode.attack(player, target);
                    player.swing(InteractionHand.MAIN_HAND);
                    log("ATTACK " + what);
                } else {
                    // バニラの右クリック(Minecraft#startUseItem)と同じ順: interactAt → だめなら interact
                    EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
                    InteractionResult result = mc.gameMode.interactAt(player, target, hit, InteractionHand.MAIN_HAND);
                    if (!result.consumesAction()) result = mc.gameMode.interact(player, target, InteractionHand.MAIN_HAND);
                    if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
                    log("INTERACT " + what + " → " + result);
                }
            }
            case "sproutscan" -> {
                // sproutscan <cx0> <cz0> <cx1> <cz1> [回数] [spawn](2026-11-08、萌芽体が水面に出る不具合): チャンクの箱で萌芽体の湧き場所
                // (SporeSprouts#surfacePoint)を回数ずつ引き、「水面」(自分は水でなく、足元のブロックの横が水 = 水面の高さの何かの上)を数えて例を書く。
                // spawn を付けると、引いた水の中・水面の点に本来の経路(BlightFernPlacement#spawn)で萌芽体を出す(後で fernscan で見る)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] a = arg.trim().split("\\s+");
                int cx0 = Integer.parseInt(a[0]), cz0 = Integer.parseInt(a[1]), cx1 = Integer.parseInt(a[2]), cz1 = Integer.parseInt(a[3]);
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 20;
                boolean spawn = a.length > 5 && a[5].equals("spawn");
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(cx0 * 31L + cz0);
                    int total = 0, water = 0, surface = 0, nul = 0;
                    java.util.Map<String, Integer> supports = new java.util.TreeMap<>();
                    for (int cx = Math.min(cx0, cx1); cx <= Math.max(cx0, cx1); cx++) {
                        for (int cz = Math.min(cz0, cz1); cz <= Math.max(cz0, cz1); cz++) {
                            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                            if (chunk == null) continue;
                            for (int i = 0; i < n; i++) {
                                net.minecraft.core.BlockPos p = com.yayuy.overgrownvegetation.spore.field.SporeSprouts.surfacePointForTest(level, chunk, random);
                                total++;
                                if (p == null) { nul++; continue; }
                                boolean inWater = level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER);
                                boolean nearWater = false;
                                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                                    // 足元のブロックの横が水 = 水面と同じ高さの何かの上に乗っている
                                    nearWater |= level.getFluidState(p.below().relative(d)).is(net.minecraft.tags.FluidTags.WATER);
                                }
                                if (inWater) water++;
                                // 足元が水面の膜・水そのもの(11-08 追記: 膜の上を選んでいないか)
                                net.minecraft.world.level.block.state.BlockState under = level.getBlockState(p.below());
                                if (!inWater && (under.getBlock() instanceof com.yayuy.overgrownvegetation.block.FloatingFilmBlock
                                        || !under.getFluidState().isEmpty())) {
                                    log("SPROUTSCAN 膜・水の上 " + p.toShortString() + " 足元 " + under);
                                }
                                if (!inWater && nearWater) {
                                    surface++;
                                    String sup = level.getBlockState(p.below()).getBlock().builtInRegistryHolder().key().location().toString();
                                    supports.merge(sup, 1, Integer::sum);
                                    if (surface <= 12) log("SPROUTSCAN 水面 " + p.toShortString() + " 足元 " + sup + " 自分のマス "
                                            + level.getBlockState(p).getBlock().builtInRegistryHolder().key().location());
                                }
                                if (spawn && (inWater || nearWater)) {
                                    var e = com.yayuy.overgrownvegetation.entity.BlightFernPlacement.spawn(level, p, net.minecraft.world.entity.MobSpawnType.EVENT);
                                    if (e != null) log("SPROUTSCAN 出した " + p.toShortString() + (inWater ? " 水の中" : " 水面"));
                                }
                            }
                        }
                    }
                    log("SPROUTSCAN 引いた " + total + " 水の中 " + water + " 水面 " + surface + " 無し " + nul + " 水面の足元 " + supports);
                });
                return null;
            }
            case "segnear" -> {
                // segnear [半径] [canopy](2026-11-08): プレイヤーに近い蔦の狙いのマス(LianaSegmentBlock)を 12 個まで、持ち主の蔓の種類
                // (本体・林冠・垂れる)と狙いの箱で書く。canopy を付けると林冠の細い蔓のものだけ
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || mc.player == null || server == null) return "ワールドの中";
                String[] a = arg.trim().isEmpty() ? new String[0] : arg.trim().split("\\s+");
                int r = a.length > 0 ? Integer.parseInt(a[0]) : 16;
                boolean canopyOnly = a.length > 1 && a[1].equals("canopy");
                var dimension = mc.level.dimension();
                net.minecraft.core.BlockPos center = mc.player.blockPosition();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    java.util.List<net.minecraft.core.BlockPos> found = new java.util.ArrayList<>();
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
                        if (level.getBlockState(p).getBlock() instanceof com.yayuy.overgrownvegetation.block.LianaSegmentBlock) found.add(p.immutable());
                    }
                    found.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(center)));
                    int n = 0;
                    for (net.minecraft.core.BlockPos p : found) {
                        net.minecraft.core.BlockPos prim = com.yayuy.overgrownvegetation.block.CorruptedLianaBlock.primaryOf(level, p);
                        String kind = "持ち主なし";
                        if (prim != null && level.getBlockEntity(prim) instanceof com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity l) {
                            kind = l.isClimbCanopy() ? "林冠" : l.isClimbMain() ? "本体" : l.isClimbHanging() ? "垂れる綱" : "添う細い蔓";
                        }
                        if (canopyOnly && !kind.equals("林冠")) continue;
                        log("SEG " + p.toShortString() + " " + kind + " 箱 " + level.getBlockState(p).getShape(level, p).bounds());
                        if (++n >= 12) break;
                    }
                    log("SEGNEAR " + found.size() + " 個");
                });
                return null;
            }
            case "fernscan" -> {
                // fernscan [半径]: まわりの萌芽体(歩く姿)と根付いた姿の位置・水の中か・足元を書く(2026-11-08)
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || mc.player == null || server == null) return "ワールドの中";
                int r = arg.isBlank() ? 64 : Integer.parseInt(arg.trim());
                var dimension = mc.level.dimension();
                net.minecraft.core.BlockPos center = mc.player.blockPosition();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    for (var e : level.getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.BlightFernEntity.class,
                            new net.minecraft.world.phys.AABB(center).inflate(r))) {
                        net.minecraft.core.BlockPos p = e.blockPosition();
                        log(String.format(Locale.ROOT, "FERN 歩く %.2f %.2f %.2f 水の中 %s 頭の上 %s 足元 %s 接地 %s", e.getX(), e.getY(), e.getZ(),
                                e.isInWater(), level.getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER),
                                level.getBlockState(p.below()).getBlock().builtInRegistryHolder().key().location(), e.onGround()));
                    }
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(center.offset(-r, -24, -r), center.offset(r, 24, r))) {
                        if (level.getBlockState(p).is(com.yayuy.overgrownvegetation.registry.ModBlocks.BLIGHT_FERN_ROOTED.get())) {
                            log("FERN 根付いた " + p.toShortString() + " 水の中 " + level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)
                                    + " 上が水 " + level.getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER)
                                    + " 足元 " + level.getBlockState(p.below()).getBlock().builtInRegistryHolder().key().location());
                        }
                    }
                    log("FERNSCAN 終わり");
                });
                return null;
            }
            case "dig" -> {
                // dig <x> <y> <z> [最大 tick](2026-11-08、蔦を壊す秒数を測る): 世界の点 (x, y, z) を狙い、照準の先のブロックを本来の
                // 採掘(MultiPlayerGameMode の start/continueDestroyBlock = 左クリック長押しと同じ。窓が裏でも動く)で壊し続ける。
                // 壊れたら `DIG <id> <座標> <tick> tick (<秒>)`。次の命令は待たない(続けて wait)。
                LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null || mc.level == null) return "ワールドの中";
                String[] a = arg.trim().split("\\s+");
                Vec3 aim = new Vec3(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]));
                long max = a.length > 3 ? Long.parseLong(a[3]) : 200L;
                player.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
                player.yRotO = player.getYRot();
                player.xRotO = player.getXRot();
                net.minecraft.world.phys.HitResult hit = player.pick(player.getBlockReach(), 0.0F, false);
                if (!(hit instanceof net.minecraft.world.phys.BlockHitResult bhr) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    log("DIG 狙いの先にブロックが無い");
                    return null;
                }
                digAim = aim;
                digPos = bhr.getBlockPos();
                digFace = bhr.getDirection();
                digState = mc.level.getBlockState(digPos);
                digStart = tick;
                digUntil = tick + max;
                setMouseGrabbed(mc, true);
                net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getKey());
                mc.options.keyAttack.setDown(true);
                log(String.format(Locale.ROOT, "DIG 狙い %s %s 手 %s 形 %s", digState.getBlock().builtInRegistryHolder().key().location(),
                        digPos.toShortString(), player.getMainHandItem().getItem().builtInRegistryHolder().key().location(),
                        digState.getShape(mc.level, digPos).bounds()));
            }
            case "useblock" -> {
                // ブロックの右クリック(上の面の真ん中を押す)。サーバーの届く距離は約 4.5 なので先に近くへ tp
                LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null || mc.level == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                Vec3 hitAt = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                        hitAt, net.minecraft.core.Direction.UP, pos, false);
                InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
                log(String.format(Locale.ROOT, "USEBLOCK %s %s (距離 %.1f) → %s", pos.toShortString(),
                        mc.level.getBlockState(pos).getBlock().builtInRegistryHolder().key().location(),
                        Math.sqrt(player.getEyePosition().distanceToSqr(hitAt)), result));
            }
            case "click" -> {
                // 開いている画面をクリック(GUI の拡大後の座標。押して離す)。画面の大きさも書く
                if (mc.screen == null) {
                    log("ERR click: 画面が開いていない");
                    return null;
                }
                String[] a = arg.split("\\s+");
                double x = Double.parseDouble(a[0]);
                double y = Double.parseDouble(a[1]);
                int button = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                boolean down = mc.screen.mouseClicked(x, y, button);
                boolean up = mc.screen.mouseReleased(x, y, button);
                log(String.format(Locale.ROOT, "CLICK %.1f %.1f (画面 %dx%d) → %s/%s", x, y,
                        mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), down, up));
            }
            case "scroll" -> {
                // 開いている画面をホイールで回す(GUI の拡大後の座標・回す量。負で下へ)。一覧の続きを撮るため(2026-11-19、設定のページ)
                if (mc.screen == null) {
                    log("ERR scroll: 画面が開いていない");
                    return null;
                }
                String[] a = arg.split("\\s+");
                double x = Double.parseDouble(a[0]);
                double y = Double.parseDouble(a[1]);
                double amount = Double.parseDouble(a[2]);
                boolean done = mc.screen.mouseScrolled(x, y, amount);
                log(String.format(Locale.ROOT, "SCROLL %.1f %.1f %.1f → %s", x, y, amount, done));
            }
            case "type" -> {
                // 開いている画面の入力欄(先に click で選んでおく)へ文字を打つ。"type \b3" のように \b は 1 文字消す(2026-11-14、設定画面の確かめ)
                if (mc.screen == null) {
                    log("ERR type: 画面が開いていない");
                    return null;
                }
                String text = arg.replace("\\b", "\b");
                for (char c : text.toCharArray()) {
                    if (c == '\b') {
                        mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                    } else {
                        mc.screen.charTyped(c, 0);
                    }
                }
                log("TYPE " + arg);
            }
            case "vineclimb" -> {
                // 登る蔓(2026-11-03)。vineclimb x y z [本数]: その幹(原木のどの段でもよい)に登る蔓を置き、林冠の細い蔓も付ける(世界生成と同じ。確率の関門は通さない)。
                // vineclimb clear x y z r: 箱の中の蔦の印(今の蔦も)を消す(写真に前の蔦が写らないように)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] a = arg.trim().split("\\s+");
                boolean clear = a[0].equals("clear");
                boolean find = a[0].equals("find"); // vineclimb find x y z r: 箱の中の幹を書き出す
                // vineclimb grow x y z r: 箱の中(高さ y の上下 12)の幹すべてに、世界生成と同じ道(TreeVineConnector#tryConnect、
                // 今の設定どおり)で蔦を付ける。乱数は幹の根元の座標を種にする(条件を変えて何度やっても同じ木に同じ抽選。FPS の比べ用、2026-11-03)
                boolean grow = a[0].equals("grow");
                int o = clear || find || grow ? 1 : 0;
                int x0 = Integer.parseInt(a[o]);
                int y0 = Integer.parseInt(a[o + 1]);
                int z0 = Integer.parseInt(a[o + 2]);
                int extra = a.length > o + 3 ? Integer.parseInt(a[o + 3]) : (clear ? 16 : 1);
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    if (grow) {
                        java.util.Map<Long, com.yayuy.overgrownvegetation.worldgen.ClimbingVine.Trunk> trunks = new java.util.LinkedHashMap<>();
                        for (int yy = y0 - 12; yy <= y0 + 12; yy += 3) {
                            for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(x0 - extra, yy, z0 - extra, x0 + extra, yy, z0 + extra)) {
                                var t = com.yayuy.overgrownvegetation.worldgen.ClimbingVine.findTrunk(level, p);
                                if (t != null && t.height() >= 4) {
                                    trunks.putIfAbsent(net.minecraft.core.BlockPos.asLong(t.minX(), t.baseY(), t.minZ()), t);
                                }
                            }
                        }
                        for (var e : trunks.entrySet()) {
                            var t = e.getValue();
                            it.unimi.dsi.fastutil.longs.LongOpenHashSet logs = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                            for (int dy = 0; dy < t.height(); dy++) {
                                for (int dx = 0; dx < t.width(); dx++) {
                                    for (int dz = 0; dz < t.width(); dz++) {
                                        logs.add(net.minecraft.core.BlockPos.asLong(t.minX() + dx, t.baseY() + dy, t.minZ() + dz));
                                    }
                                }
                            }
                            com.yayuy.overgrownvegetation.worldgen.TreeVineConnector.tryConnect(level,
                                    net.minecraft.util.RandomSource.create(e.getKey() ^ 0x5EEDL), net.minecraft.core.BlockPos.of(e.getKey()), logs);
                        }
                        log("VINECLIMB grow 幹 " + trunks.size());
                        return;
                    }
                    if (find) {
                        StringBuilder sb = new StringBuilder("VINECLIMB find");
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(x0 - extra, y0, z0 - extra, x0 + extra, y0, z0 + extra)) {
                            var t = com.yayuy.overgrownvegetation.worldgen.ClimbingVine.findTrunk(level, p);
                            if (t != null && t.height() >= 4 && seen.add(t.minX() + "," + t.minZ())) {
                                sb.append(" [").append(t.minX()).append(' ').append(t.baseY()).append(' ').append(t.minZ())
                                        .append(" w").append(t.width()).append(" h").append(t.height()).append(']');
                            }
                        }
                        log(sb.toString());
                        return;
                    }
                    if (clear) {
                        int removed = 0;
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(x0 - extra, y0 - 4, z0 - extra, x0 + extra, y0 + 48, z0 + extra)) {
                            if (com.yayuy.overgrownvegetation.block.CorruptedLianaBlock.isLianaPart(level.getBlockState(p)) // 狙いのマス(LianaSegmentBlock)も(11-08)
                                    || level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                                removed++;
                            }
                        }
                        log("VINECLIMB clear " + removed);
                        return;
                    }
                    net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(x0 * 31L + z0 * 17L + y0);
                    int placed = com.yayuy.overgrownvegetation.worldgen.ClimbingVine.placeOnTree(level,
                            new net.minecraft.core.BlockPos(x0, y0, z0), extra, random);
                    int[] canopy = com.yayuy.overgrownvegetation.worldgen.ClimbingVine.placeCanopyVines(level,
                            new net.minecraft.core.BlockPos(x0, y0, z0), random);
                    var trunk = com.yayuy.overgrownvegetation.worldgen.ClimbingVine.findTrunk(level, new net.minecraft.core.BlockPos(x0, y0, z0));
                    log("VINECLIMB " + placed + "/" + extra + " 林冠 a=" + canopy[0] + " b=" + canopy[1] + " trunk=" + trunk);
                    // 置いた蔓の根元・渡りの始め・終わり・先(撮る位置を決めるため)
                    if (trunk != null) {
                        net.minecraft.core.BlockPos src = new net.minecraft.core.BlockPos(trunk.minX(), trunk.baseY(), trunk.minZ());
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(src.offset(-6, -1, -6), src.offset(6 + trunk.width(), trunk.height() + 4, 6 + trunk.width()))) {
                            if (level.getBlockEntity(p) instanceof com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity be
                                    && be.hasClimb() && src.equals(be.getSourcePos())) {
                                float[] f = be.getClimbPath();
                                int st = com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity.CLIMB_STRIDE;
                                java.util.function.IntFunction<String> at = i -> i < 0 ? "-" : String.format(Locale.ROOT, "(%.1f %.1f %.1f)",
                                        p.getX() + (double) f[i * st], p.getY() + (double) f[i * st + 1], p.getZ() + (double) f[i * st + 2]);
                                log("VINECLIMB vine marker=" + p.toShortString() + " root=" + at.apply(0) + " bridge=" + at.apply(be.getClimbBridgeStart())
                                        + "→" + at.apply(be.getClimbBridgeEnd()) + " tip=" + at.apply(f.length / st - 1) + " 点=" + f.length / st
                                        + String.format(Locale.ROOT, " 太さ=%.2f", be.getClimbScale()) + (be.isClimbHanging() ? " 綱" : "") + (be.isClimbCanopy() ? " 林冠" : ""));
                            }
                        }
                    }
                });
            }
            case "roots" -> {
                // 根の先端・斜めのアーチを試しに生やす(2026-10-09)。例: roots ground 0 -61 0 8(地面の高さ y・本数)/ roots hang 0 -50 0 8(天井の石の高さ y)/ roots arch 0 -61 0 6
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                String kind = a[0];
                int x0 = Integer.parseInt(a[1]);
                int y0 = Integer.parseInt(a[2]);
                int z0 = Integer.parseInt(a[3]);
                int count = a.length > 4 ? Integer.parseInt(a[4]) : 6;
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(x0 * 31L + z0);
                    net.minecraft.world.level.block.state.BlockState log =
                            com.yayuy.overgrownvegetation.registry.ModBlocks.ROOT_ARCH_LOG.get().defaultBlockState();
                    it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    int built = 0;
                    for (int i = 0; i < count; i++) {
                        // 横に並べる(14 ブロックおき)
                        int x = x0 + (i % 4) * 14;
                        int z = z0 + (i / 4) * 14;
                        boolean ok;
                        if (kind.equals("hang")) {
                            ok = com.yayuy.overgrownvegetation.corruption.CaveGrowth.buildHangingRootTip(level,
                                    new net.minecraft.core.BlockPos(x, y0, z), random,
                                    com.yayuy.overgrownvegetation.corruption.CaveGrowth.Mode.JUNGLE);
                        } else if (kind.equals("arch")) {
                            double angle = random.nextDouble() * Math.PI * 2.0D;
                            int span = 6 + random.nextInt(8);
                            ok = com.yayuy.overgrownvegetation.corruption.CorruptionArch.build(level,
                                    new net.minecraft.core.BlockPos(x, y0, z),
                                    new net.minecraft.core.BlockPos(x + (int) Math.round(Math.cos(angle) * span), y0,
                                            z + (int) Math.round(Math.sin(angle) * span)),
                                    2 + random.nextInt(3), random.nextInt(2), log, touched, null, null);
                        } else {
                            var shape = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.rollGround(random);
                            var cells = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.plan(x + 0.5D, y0 - 0.5D, z + 0.5D, shape);
                            ok = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.place(level, cells, log, touched, new java.util.ArrayList<>());
                        }
                        if (ok) built++;
                    }
                    log("ROOTS " + kind + " " + built + "/" + count);
                });
            }
            case "vinespan" -> {
                // 壁と壁の隙間に渡る太い蔦(2026-11-07、corruption/VineSpan)。
                // vinespan x1 y1 z1 x2 y2 z2 [種]: 2 つの壁のブロックの間に置く。
                // vinespan find x y z r [最小 最大]: 箱(水平 r・上下 r/2)の壁から隙間を探して書き出す(汚染は問わない。汚染なら「汚染」と付く)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] a = arg.trim().split("\\s+");
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    if (a[0].equals("stats")) {
                        // vinespan stats [reset]: 自然に出る太い蔦の数え(tryGrow の呼ばれた回数・抽選・隙間・近く・置けない・天井・置けた・時間・記録の組の数)。11-07 自然に出る量の測り
                        int[] st = com.yayuy.overgrownvegetation.corruption.VineSpan.STATS;
                        long[] ns = com.yayuy.overgrownvegetation.corruption.VineSpan.NANOS;
                        log(String.format("VINESPAN STATS 呼ばれた %d 抽選 %d 隙間 %d 近くに有り %d 置けない %d(天井 %d) 置けた %d 時間 合計 %.2f ms 最大 %.3f ms 1 回の平均 %.3f ms 記録の組 %d 日 %d 時刻 %d 洞窟の壁から 呼ばれた %d 置けた %d 置く仕事 1 tick の最大 %.2f ms 合計 %.2f ms 置き終えた %d 捨てた %d 置きかけ %d",
                                com.yayuy.overgrownvegetation.corruption.VineSpan.CALLS, st[0], st[1], st[2], st[3], st[4], st[5],
                                ns[0] / 1.0e6, ns[1] / 1.0e6, st[0] == 0 ? 0.0 : ns[0] / 1.0e6 / st[0],
                                com.yayuy.overgrownvegetation.corruption.VineSpanRecord.get(level).size(),
                                level.getDayTime() / 24000L, level.getGameTime(), com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_CALLS, com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_GROWN, com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.NANOS[1] / 1.0e6, com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.NANOS[0] / 1.0e6, com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.DONE, com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.DROPPED, com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.pending(level)));
                        if (a.length > 1 && a[1].equals("list")) {
                            long[] e = com.yayuy.overgrownvegetation.corruption.VineSpanRecord.get(level).ends();
                            for (int i = 0; i + 1 < e.length; i += 2) {
                                log("VINESPAN REC " + net.minecraft.core.BlockPos.of(e[i]).toShortString().replace(",", "") + " -> " + net.minecraft.core.BlockPos.of(e[i + 1]).toShortString().replace(",", ""));
                            }
                        }
                        if (a.length > 1 && a[1].equals("reset")) {
                            java.util.Arrays.fill(st, 0);
                            java.util.Arrays.fill(ns, 0L);
                            com.yayuy.overgrownvegetation.corruption.VineSpan.CALLS = 0;
                            com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_CALLS = 0;
                            com.yayuy.overgrownvegetation.corruption.VineSpan.CAVE_GROWN = 0;
                            java.util.Arrays.fill(com.yayuy.overgrownvegetation.corruption.VineSpanPlacer.NANOS, 0L);
                        }
                        return;
                    }
                    if (a[0].equals("find")) {
                        int x0 = Integer.parseInt(a[1]);
                        int y0 = Integer.parseInt(a[2]);
                        int z0 = Integer.parseInt(a[3]);
                        int r = Integer.parseInt(a[4]);
                        double lo = a.length > 5 ? Double.parseDouble(a[5]) : 0.0D;
                        double hi = a.length > 6 ? Double.parseDouble(a[6]) : 99.0D;
                        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(1L);
                        java.util.List<com.yayuy.overgrownvegetation.corruption.VineSpan.Gap> found = new java.util.ArrayList<>();
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(x0 - r, y0 - r / 2, z0 - r, x0 + r, y0 + r / 2, z0 + r)) {
                            if (((p.getX() + p.getY() + p.getZ()) & 1) != 0) continue;
                            var g = com.yayuy.overgrownvegetation.corruption.VineSpan.findGap(level, p.immutable(), random, false);
                            if (g == null || g.span() < lo || g.span() > hi) continue;
                            boolean near = false;
                            for (var o : found) {
                                if (o.a().distSqr(g.a()) < 100 || o.b().distSqr(g.a()) < 100) { near = true; break; }
                            }
                            if (near) continue;
                            boolean cave = com.yayuy.overgrownvegetation.corruption.CaveGrowth.isCave(level, g.a(), 6);
                            // [最小 最大 sky|cave]: 空に開けた所(崖・渓谷)だけ / 洞窟だけ
                            if (a.length > 7 && a[7].equals("sky") == cave) continue;
                            found.add(g);
                            log("VINESPAN GAP " + g.a().toShortString().replace(",", "") + " -> " + g.b().toShortString().replace(",", "")
                                    + String.format(" 幅 %.1f", g.span()) + (cave ? " 洞窟" : " 空")
                                    + (com.yayuy.overgrownvegetation.corruption.VineSpan.isCorruptedPublic(level, g.a()) ? " 汚染" : ""));
                            if (found.size() >= 30) break;
                        }
                        log("VINESPAN FIND " + found.size());
                        return;
                    }
                    if (a[0].equals("grow")) {
                        // vinespan grow x1 y1 z1 x2 y2 z2: 箱の中の汚染済みのブロックごとに、汚染の直後と同じ育ち(CorruptionSpreader#growSurfacePlants)を 1 回走らせる。
                        // 太い蔦は自然と同じ確率・間隔で出る。出た所を書き出す。
                        int[] c = java.util.Arrays.stream(a).skip(1).limit(6).mapToInt(Integer::parseInt).toArray();
                        int before = com.yayuy.overgrownvegetation.corruption.VineSpan.GROWN.size();
                        int hosts = 0;
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(c[0], c[1], c[2], c[3], c[4], c[5])) {
                            net.minecraft.core.BlockPos at = p.immutable();
                            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(at);
                            if (st.isAir() || !com.yayuy.overgrownvegetation.corruption.VineSpan.isCorruptedPublic(level, at)) continue;
                            hosts++;
                            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, st,
                                    com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.surfaceGrowthChance(level, at));
                        }
                        var grown = com.yayuy.overgrownvegetation.corruption.VineSpan.GROWN;
                        for (int i = before; i < grown.size(); i++) {
                            var g = grown.get(i);
                            log("VINESPAN GROWN " + g.a().toShortString().replace(",", "") + " -> " + g.b().toShortString().replace(",", "")
                                    + String.format(" 幅 %.1f", g.span()));
                        }
                        log("VINESPAN GROW 汚染済み " + hosts + " マス・太い蔦 " + (grown.size() - before));
                        var st0 = com.yayuy.overgrownvegetation.corruption.VineSpan.STATS;
                        log("VINESPAN STATS 抽選 " + st0[0] + " 隙間 " + st0[1] + " 近くに有り " + st0[2] + " 置けない " + st0[3] + " " + com.yayuy.overgrownvegetation.corruption.VineSpan.LAST_FAIL);
                        return;
                    }
                    if (a[0].equals("cam")) {
                        // vinespan cam x1 y1 z1 x2 y2 z2 side|under|end: 真ん中を横・下・端の外から見る空いた所へ移る(写真の定点を探す)
                        int[] c = java.util.Arrays.stream(a).skip(1).limit(6).mapToInt(Integer::parseInt).toArray();
                        String how = a.length > 7 ? a[7] : "side";
                        double mx = (c[0] + c[3]) / 2.0D + 0.5D, my = (c[1] + c[4]) / 2.0D - 1.0D, mz = (c[2] + c[5]) / 2.0D + 0.5D;
                        double ex = c[3] - c[0], ez = c[5] - c[2];
                        double el = Math.sqrt(ex * ex + ez * ez);
                        ex /= el; ez /= el;
                        double hx = -ez, hz = ex;
                        java.util.function.BiFunction<double[], double[], Double> free = (from, dir) -> {
                            double d = 0;
                            for (d = 1; d < 30; d += 0.5) {
                                net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(from[0] + dir[0] * d, from[1] + dir[1] * d, from[2] + dir[2] * d);
                                if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) break;
                            }
                            return d;
                        };
                        double px, py, pz;
                        float yaw, pitch;
                        if (how.equals("under")) {
                            double down = free.apply(new double[]{mx + hx * 2, my - 4, mz + hz * 2}, new double[]{0, -1, 0});
                            px = mx + hx * 2; pz = mz + hz * 2; py = my - 4 - Math.max(0, Math.min(down - 2.0, 6.0));
                            yaw = (float) Math.toDegrees(Math.atan2(-ex, ez)); pitch = -70;
                        } else if (how.equals("end")) {
                            double back = free.apply(new double[]{c[0] + 0.5 + hx * 3, my + 3, c[2] + 0.5 + hz * 3}, new double[]{-ex, 0, -ez});
                            double k = Math.min(back - 1.5, 6.0);
                            px = c[0] + 0.5 + hx * 3 - ex * k; py = my + 3; pz = c[2] + 0.5 + hz * 3 - ez * k;
                            yaw = (float) Math.toDegrees(Math.atan2(-(mx - px), mz - pz)); pitch = 12;
                        } else {
                            double best = -1, sgn = 1;
                            for (double sg : new double[]{1, -1}) {
                                double d = free.apply(new double[]{mx, my + 2, mz}, new double[]{hx * sg, 0, hz * sg});
                                if (d > best) { best = d; sgn = sg; }
                            }
                            double k = Math.min(best - 1.5, 22.0);
                            px = mx + hx * sgn * k; py = my + 2 + k * 0.25; pz = mz + hz * sgn * k;
                            yaw = (float) Math.toDegrees(Math.atan2(-(mx - px), mz - pz));
                            pitch = (float) Math.toDegrees(Math.atan2(py - my, k));
                        }
                        var player = level.players().isEmpty() ? null : level.players().get(0);
                        if (player != null) {
                            player.teleportTo(level, px, py, pz, yaw, pitch);
                        }
                        log(String.format("VINESPAN CAM %s %.1f %.1f %.1f yaw %.0f pitch %.0f", how, px, py, pz, yaw, pitch));
                        return;
                    }
                    int[] v = java.util.Arrays.stream(a).limit(6).mapToInt(Integer::parseInt).toArray();
                    long seed = a.length > 6 ? Long.parseLong(a[6]) : v[0] * 31L + v[2];
                    long tb = System.nanoTime();
                    var res = com.yayuy.overgrownvegetation.corruption.VineSpan.build(level,
                            new net.minecraft.core.BlockPos(v[0], v[1], v[2]), new net.minecraft.core.BlockPos(v[3], v[4], v[5]),
                            net.minecraft.util.RandomSource.create(seed));
                    log(String.format("VINESPAN 形を決める時間 %.2f ms(葉と植物は待ち行列で置く)", (System.nanoTime() - tb) / 1.0e6));
                    log("VINESPAN " + res);
                });
            }
            case "corrupt" -> {
                // 箱の中のブロックを、汚染の本来の経路(CorruptionRegistry)で 1 回ずつ汚染する。例: corrupt 0 -60 0 15 -60 15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int changed = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2],
                            v.length > 3 ? v[3] : v[0], v.length > 4 ? v[4] : v[1], v.length > 5 ? v[5] : v[2])) {
                        net.minecraft.core.BlockPos at = p.immutable();
                        net.minecraft.world.level.block.state.BlockState before = level.getBlockState(at);
                        if (before.isAir() || !com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, at, before)) continue;
                        net.minecraft.world.level.block.state.BlockState after =
                                com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, at, before, level.random);
                        if (com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.noChange(before, after)) {
                            // 置き換えずに覆った(座標の記録)
                            if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at)) changed++;
                            continue;
                        }
                        com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.placeCorrupted(level, at, before, after,
                                net.minecraft.world.level.block.Block.UPDATE_ALL);
                        changed++;
                    }
                    log("CORRUPT " + changed + " マス");
                });
            }
            case "lightat" -> {
                // クライアントの光と地下の組の判定(11-03)。例: lightat 0 -40 0(覆われたブロック)
                if (mc.level == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(v[0], v[1], v[2]);
                StringBuilder sb = new StringBuilder("LIGHTAT " + at.toShortString());
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    sb.append(' ').append(d.getName().charAt(0)).append('=')
                            .append(mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at.relative(d)));
                }
                sb.append(" 岩 ").append(com.yayuy.overgrownvegetation.corruption.CaveCover.topRockY(mc.level, v[0], v[2]))
                        .append(" 面 ").append(Integer.toBinaryString(com.yayuy.overgrownvegetation.corruption.CaveCover.caveFaces(mc.level, at)));
                log(sb.toString());
            }
            case "caveflora" -> {
                // 地下の植生 1(11-03)の置き方を座標で呼ぶ(撮影用)。例: caveflora light 0 10 0(天井のブロック)/ caveflora ley 0 0 0 east near(壁のブロックと面)
                // / caveflora foxfire 0 0 0(床のブロック)
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] w = arg.trim().split("\\s+");
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                    String r = switch (w[0]) {
                        case "light" -> "light " + com.yayuy.overgrownvegetation.corruption.CaveFlora.placeCeilingLight(level, at, level.random)
                                + "・高さ " + com.yayuy.overgrownvegetation.corruption.CaveFlora.caveHeightBelow(level, at);
                        case "ley" -> "ley " + com.yayuy.overgrownvegetation.corruption.CaveFlora.placeLeyRoots(level, at,
                                net.minecraft.core.Direction.byName(w[4]),
                                com.yayuy.overgrownvegetation.block.LeyRootBlock.Glow.valueOf(w[5].toUpperCase(java.util.Locale.ROOT)), level.random);
                        case "foxfire" -> "foxfire " + com.yayuy.overgrownvegetation.corruption.CaveFlora.placeFoxfire(level, at, level.random);
                        case "mycelium" -> { // caveflora mycelium x y z <半径>: 箱の中の固いブロックごとに decorate と同じ確率で菌糸を貼る(11-03 直し)
                            int rad = w.length > 4 ? Integer.parseInt(w[4]) : 8, n = 0;
                            for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(at.offset(-rad, -rad, -rad), at.offset(rad, rad, rad))) {
                                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
                                if (!s.isAir() && s.isSolidRender(level, p) && level.random.nextDouble() < com.yayuy.overgrownvegetation.corruption.CaveFlora.MYCELIUM_CHANCE
                                        && com.yayuy.overgrownvegetation.corruption.CaveFlora.placeMycelium(level, p.immutable(), s, level.random)) {
                                    n++;
                                }
                            }
                            yield "mycelium " + n;
                        }
                        // 地下の植生 2(11-03): curtain x y z(天井のブロック)/ pool x y z(床のブロック)/ pillar x y z(床のブロック)
                        case "curtain" -> "curtain " + com.yayuy.overgrownvegetation.corruption.CaveFlora.curtainSite(level, at) + " "
                                + com.yayuy.overgrownvegetation.corruption.CaveFlora.placeRootCurtain(level, at, level.random);
                        case "pool" -> "pool pit " + com.yayuy.overgrownvegetation.corruption.CaveFlora.isPit(level, at.above()) + " "
                                + com.yayuy.overgrownvegetation.corruption.CaveFlora.placeSporePool(level, at);
                        case "pillar" -> "pillar 高さ " + com.yayuy.overgrownvegetation.corruption.CaveFlora.caveHeightAbove(level, at) + " " // pillar x y z [wide 0|1] [cap 0|1]
                                + com.yayuy.overgrownvegetation.corruption.CaveFlora.placePrototaxites(level, at, level.random,
                                w.length > 4 ? Integer.parseInt(w[4]) : -1, w.length > 5 ? Integer.parseInt(w[5]) : -1);
                        default -> "? " + w[0];
                    };
                    log("CAVEFLORA " + r);
                });
            }
            case "biomeconv" -> {
                // そのブロックを含むチャンクのバイオームを本来の判定(地表の汚染割合で過繁茂か = maybeConvert)で書き換える。
                // 例: biomeconv 100 -200。2026-10-23 湿地の封印の確かめ(極相の climax は 2026-10-30 に消した)
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] b = arg.trim().split("\\s+");
                var chunk = new net.minecraft.world.level.ChunkPos(Integer.parseInt(b[0]) >> 4, Integer.parseInt(b[1]) >> 4);
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter.maybeConvert(level, chunk);
                    log("BIOMECONV " + chunk + " → " + level.getBiome(chunk.getMiddleBlockPosition(64)).unwrapKey()
                            .map(k -> k.location().toString()).orElse("?"));
                });
            }
            case "count" -> {
                // 箱の中のそのブロックの数を数える(壊さない)。例: count overgrown_vegetation:overgrown_sapling 0 -64 0 63 0 63。2026-10-15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] countArgs = arg.trim().split("\\s+");
                var id = net.minecraft.resources.ResourceLocation.tryParse(countArgs[0]);
                int[] v = java.util.Arrays.stream(countArgs).skip(1).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null || id == null) return;
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                    int n = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2], v[3], v[4], v[5])) {
                        if (level.isLoaded(p) && level.getBlockState(p).is(block)) n++;
                    }
                    log("COUNT " + id + " " + n);
                });
            }
            case "aimat" -> {
                // 照準の先のブロック(2026-11-07 洞窟の壁の覆いの見え方の切り分け): クライアントの状態・クライアントに届いた覆いの記録・サーバーの状態と覆い。
                if (mc.level == null || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh)) return "照準の先にブロックが無い";
                net.minecraft.core.BlockPos at = bh.getBlockPos();
                var cchunk = mc.level.getChunkAt(at);
                boolean clientCovered = OvAttach.getExistingData(cchunk, com.yayuy.overgrownvegetation.registry.ModAttachments.OVERGROWN_LAYER.get())
                        .map(s -> s.contains(at.asLong())).orElse(false);
                long[] cells = com.yayuy.overgrownvegetation.client.ClientOvergrownLayer.coveredCells(cchunk, at.getY() >> 4);
                int cell = ((at.getY() & 15) << 8) | ((at.getZ() & 15) << 4) | (at.getX() & 15);
                boolean inSnapshot = cells != null && (cells[cell >> 6] & 1L << (cell & 63)) != 0L;
                String clientLine = "AIMAT " + at.toShortString() + " 面 " + bh.getDirection() + " クライアント " + mc.level.getBlockState(at)
                        + " 記録 " + clientCovered + " 写し " + inSnapshot;
                IntegratedServer server = mc.getSingleplayerServer();
                var dimension = mc.level.dimension();
                if (server != null) server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    log(clientLine + " | サーバー " + level.getBlockState(at) + " 覆い " + com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at)
                            + " canCover " + com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.canCover(level.getBlockState(at)));
                });
            }
            case "seepmark" -> {
                // 菌糸の染み下りの確かめ(2026-11-07): seepmark <cx0> <cz0> <cx1> <cz1> のチャンクを過繁茂にして台帳に載せる(地割れと同じ forceOvergrownBatch)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    java.util.List<net.minecraft.world.level.ChunkPos> list = new java.util.ArrayList<>();
                    for (int cx = v[0]; cx <= v[2]; cx++) for (int cz = v[1]; cz <= v[3]; cz++) list.add(new net.minecraft.world.level.ChunkPos(cx, cz));
                    int n = com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter.forceOvergrownBatch(level, list);
                    var phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
                    int inLedger = 0;
                    for (var p : list) if (phase.isOvergrownChunk(p)) inLedger++;
                    log("SEEPMARK converted " + n + " ledger " + inLedger + "/" + list.size() + " clock " + phase.clock(server));
                });
            }
            case "seepstat" -> {
                // 菌糸の染み下りの測り(2026-11-07): seepstat <cx0> <cz0> <cx1> <cz1>(チャンク座標の箱)。地下(列の地表のすぐ下の段以下)の
                // 空気に面したブロック(液体を除く)のうち汚染されたものの割合を 16 段ごとに。チャンクの進みと台帳の有無、処理の時間も出す(時間は出したら 0 に戻す)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int minY = level.getMinBuildHeight(), bands = (level.getMaxBuildHeight() - minY) / 16;
                    long[] total = new long[bands], bad = new long[bands], wetTotal = new long[2], plants = new long[bands];
                    int inLedger = 0, chunks = 0, pMin = Integer.MAX_VALUE, pMax = 0;
                    var phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
                    net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos(), n = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int cx = v[0]; cx <= v[2]; cx++)
                        for (int cz = v[1]; cz <= v[3]; cz++) {
                            var chunk = level.getChunkSource().getChunkNow(cx, cz);
                            if (chunk == null) continue;
                            chunks++;
                            if (phase.isOvergrownChunk(chunk.getPos())) inLedger++;
                            int p = com.yayuy.overgrownvegetation.spore.field.MyceliumSeep.progressPpm(chunk);
                            pMin = Math.min(pMin, p);
                            pMax = Math.max(pMax, p);
                            for (int lx = 0; lx < 16; lx++)
                                for (int lz = 0; lz < 16; lz++) {
                                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                                    int top = com.yayuy.overgrownvegetation.spore.field.MyceliumSeep.columnTopForStats(chunk, x, z, minY);
                                    for (int y = minY; y <= top; y++) {
                                        m.set(x, y, z);
                                        var s = chunk.getBlockState(m);
                                        if (!s.isAir() && !s.blocksMotion()) plants[(y - minY) >> 4]++; // 地下の植物(動きを止めないブロック。水中の根なども)
                                        if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                                        boolean air = false, wet = false;
                                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                                            n.setWithOffset(m, d);
                                            if (!level.isLoaded(n)) continue;
                                            var ns = level.getBlockState(n);
                                            if (ns.isAir()) air = true;
                                            else if (ns.is(net.minecraft.world.level.block.Blocks.WATER)) wet = true;
                                        }
                                        if (!air && wet) { // 水にだけ面したブロック(地底湖の底。2026-11-08)は別に数える
                                            wetTotal[0]++;
                                            if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(chunk, m, s)) wetTotal[1]++;
                                            continue;
                                        }
                                        if (!air) continue;
                                        int b = (y - minY) >> 4;
                                        total[b]++;
                                        if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(chunk, m, s)) bad[b]++;
                                    }
                                }
                        }
                    StringBuilder sb = new StringBuilder("SEEPSTAT chunks " + chunks + " ledger " + inLedger + " progress " + (chunks == 0 ? "-" : pMin + ".." + pMax)
                            + " clock " + phase.clock(server) + " | " + com.yayuy.overgrownvegetation.spore.field.MyceliumSeep.takeStats()
                            + " | " + com.yayuy.overgrownvegetation.spore.field.MyceliumSeep.debugState());
                    long tt = 0, tb = 0;
                    for (int b = bands - 1; b >= 0; b--) {
                        if (total[b] == 0) continue;
                        tt += total[b];
                        tb += bad[b];
                        sb.append(String.format(java.util.Locale.ROOT, "\n  y %4d..%4d  %6d / %6d  %5.1f%%  植物 %d", minY + b * 16, minY + b * 16 + 15, bad[b], total[b], 100.0 * bad[b] / total[b], plants[b]));
                    }
                    sb.append(String.format(java.util.Locale.ROOT, "\n  all  %d / %d  %.1f%%", tb, tt, tt == 0 ? 0.0 : 100.0 * tb / tt));
                    long tp = 0; for (long pl : plants) tp += pl;
                    sb.append(String.format(java.util.Locale.ROOT, "\n  植物 %d(汚染 100 マスあたり %.1f)", tp, tb == 0 ? 0.0 : 100.0 * tp / tb));
                    sb.append(String.format(java.util.Locale.ROOT, "\n  水にだけ面した  %d / %d  %.1f%%", wetTotal[1], wetTotal[0], wetTotal[0] == 0 ? 0.0 : 100.0 * wetTotal[1] / wetTotal[0]));
                    StringBuilder lag = new StringBuilder();
                    int lagN = 0;
                    for (int cx = v[0]; cx <= v[2]; cx++)
                        for (int cz = v[1]; cz <= v[3]; cz++) {
                            var chunk = level.getChunkSource().getChunkNow(cx, cz);
                            if (chunk == null) continue;
                            int p = com.yayuy.overgrownvegetation.spore.field.MyceliumSeep.progressPpm(chunk);
                            if (p < pMax - 100_000 && lagN++ < 8) lag.append(" [").append(cx).append(",").append(cz).append("]=").append(p);
                        }
                    if (lagN > 0) sb.append("\n  遅れているチャンク ").append(lagN).append(":").append(lag);
                    log(sb.toString());
                });
            }
            case "holes" -> {
                // 地表の汚染の穴と縁を数える(2026-11-04 胞子の地下の層と縁のセッション)。例: holes -64 -64 64 64(ブロックの範囲)。
                // 列ごとに頂(葉を除く)から木の一部・汚染済みの薄いものをくぐって 3 段に汚染があるかを見て、無い列を
                // 「チャンクが繁茂系か」「4 方の汚染の数」「汚染できない理由」で分けて HOLES 行に書く。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int w = v[2] - v[0] + 1, h = v[3] - v[1] + 1;
                    boolean[] dirty = new boolean[w * h];
                    boolean[] known = new boolean[w * h];
                    net.minecraft.core.BlockPos[] ground = new net.minecraft.core.BlockPos[w * h];
                    for (int i = 0; i < w; i++)
                        for (int j = 0; j < h; j++) {
                            int x = v[0] + i, z = v[1] + j;
                            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                            known[i * h + j] = true;
                            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                            net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos(x, y, z);
                            // 梢・幹・空気・当たり判定の無い植物をくぐって、最初の動きを止める木でないブロック = 地面(水は水底まで)
                            boolean c = false;
                            for (int d = 0; d < 200 && m.getY() > level.getMinBuildHeight(); d++) {
                                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(m);
                                boolean tree = com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s);
                                if (!tree && s.blocksMotion() && s.getFluidState().isEmpty()) break;
                                if (!tree && !s.isAir() && com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(level, m, s)) c = true; // 地面の上の汚染した苔・下草
                                else if (tree || s.isAir()) c = false; // 梢の汚染は地面の汚染に数えない
                                m.move(0, -1, 0);
                            }
                            c |= com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(level, m, level.getBlockState(m));
                            dirty[i * h + j] = c;
                            ground[i * h + j] = m.immutable();
                        }
                    java.util.Map<String, Integer> cats = new java.util.TreeMap<>();
                    java.util.Map<String, Integer> blocks = new java.util.TreeMap<>();
                    int inside = 0, insideDirty = 0, nearEdge = 0, nearEdgeDirty = 0;
                    for (int i = 1; i < w - 1; i++)
                        for (int j = 1; j < h - 1; j++) {
                            int k = i * h + j;
                            if (!known[k]) continue;
                            int x = v[0] + i, z = v[1] + j;
                            boolean like = com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(new net.minecraft.core.BlockPos(x, level.getSeaLevel(), z)));
                            int sides = (dirty[k - h] ? 1 : 0) + (dirty[k + h] ? 1 : 0) + (dirty[k - 1] ? 1 : 0) + (dirty[k + 1] ? 1 : 0);
                            if (like) { inside++; if (dirty[k]) insideDirty++; }
                            else if (sides > 0 || dirty[k]) { nearEdge++; if (dirty[k]) nearEdgeDirty++; }
                            if (dirty[k] || sides == 0) continue;
                            net.minecraft.core.BlockPos g = ground[k];
                            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(g);
                            String why;
                            if (!com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, g, st)) {
                                why = st.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTION_IMMUNE) ? "免疫タグ"
                                        : st.is(net.minecraft.tags.BlockTags.LEAVES) ? "自然の葉"
                                        : st.hasBlockEntity() ? "ブロックエンティティ"
                                        : st.getBlock().defaultDestroyTime() < 0 ? "壊せない"
                                        : !st.getFluidState().isEmpty() ? "水・水中" : st.isAir() ? "空気" : "その他";
                            } else {
                                why = "汚染できる";
                            }
                            String key = (like ? "繁茂系の中" : "外の縁") + "/" + why + "/4方" + sides;
                            cats.merge(key, 1, Integer::sum);
                            blocks.merge((like ? "中:" : "外:") + why + ":" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), 1, Integer::sum);
                        }
                    log("HOLES 繁茂系の列 " + inside + "・うち汚染 " + insideDirty + " / 外で汚染に接した列 " + nearEdge + "・うち汚染 " + nearEdgeDirty);
                    cats.forEach((key, n) -> log("HOLES " + key + " " + n));
                    log("HOLES 穴の地面 " + blocks);
                });
            }
            case "coverscan" -> {
                // 箱の中の砂・砂利(#minecraft:sand・砂利)を、覆われた / 素のままに分けて数え、素のものを 12 個まで上下と隣の様子つきで書く(2026-11-01 夜、海底の砂の抜けの切り分け)。
                // 例: coverscan -40 30 -240 40 62 -170
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int covered = 0;
                    int bare = 0;
                    int bareWet = 0;
                    int hanging = 0;
                    int plantTop = 0;
                    int front = 0;
                    StringBuilder list = new StringBuilder();
                    int listed = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2], v[3], v[4], v[5])) {
                        if (!level.isLoaded(p)) continue;
                        var s = level.getBlockState(p);
                        if (!s.is(net.minecraft.tags.BlockTags.SAND) && !s.is(net.minecraft.world.level.block.Blocks.GRAVEL)) continue;
                        boolean exposed = false;
                        boolean wet = false;
                        int corruptedNear = 0;
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                            var n = level.getBlockState(p.relative(d));
                            if (!n.canOcclude()) exposed = true;
                            if (n.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) wet = true;
                            if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCorruptedCell(level, p.relative(d), n)) corruptedNear++;
                        }
                        if (!exposed) continue;
                        if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, p)) {
                            covered++;
                            continue;
                        }
                        bare++;
                        if (wet) bareWet++;
                        var below = level.getBlockState(p.below());
                        if (below.isAir() || below.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && !below.blocksMotion()) hanging++;
                        else if (!level.getBlockState(p.above()).isAir() && !level.getBlockState(p.above()).blocksMotion()
                                && !level.getBlockState(p.above()).is(net.minecraft.world.level.block.Blocks.WATER)) plantTop++;
                        if (corruptedNear == 0) front++;
                        if (listed < 12) {
                            listed++;
                            list.append("\n   ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append(' ')
                                    .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath())
                                    .append(" 上=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.above()).getBlock()).getPath())
                                    .append(" 下=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.below()).getBlock()).getPath())
                                    .append(" 汚染済みの隣=").append(corruptedNear)
                                    .append(" ").append(level.getBiome(p).unwrapKey().map(k -> k.location().getPath()).orElse("?"));
                        }
                    }
                    log("COVERSCAN 覆われた " + covered + " 素のまま " + bare + "(うち水に接する " + bareWet + "・下が水か空気 " + hanging
                            + "・上が植物 " + plantTop + "・汚染済みの隣なし " + front + ")" + list);
                });
            }
            case "anchors" -> {
                // 木の間隔(TreeSpacing)で木を立てるはずの列に、今なにがあるかを集計する。例: anchors -310 13 -230 93(x1 z1 x2 z2)。2026-10-15
                // 返事は ANCHOR 分類 数 の行。木が立たない理由(上の草・水・覆い・未汚染など)を数で見るため。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
                    int spacing = cfg.treeSpacing.get();
                    double clearing = cfg.treeClearingChance.get();
                    java.util.Map<String, Integer> tally = new java.util.TreeMap<>();
                    var log = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LOG.get();
                    var sapling = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get();
                    net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int x = Math.min(v[0], v[2]); x <= Math.max(v[0], v[2]); x++) {
                        for (int z = Math.min(v[1], v[3]); z <= Math.max(v[1], v[3]); z++) {
                            if (!com.yayuy.overgrownvegetation.corruption.TreeSpacing.allowsTree(level.getSeed(), x, z, spacing, clearing)) continue;
                            // 林冠の穴の輪の中の列は木を立てない(2026-10-16)。木が無い理由として別に数える。
                            if (com.yayuy.overgrownvegetation.corruption.TreeSpacing.inCanopyGap(level.getSeed(), x, z, cfg.treeCanopyGapChance.get())) {
                                tally.merge("林冠の穴", 1, Integer::sum);
                                continue;
                            }
                            if (!level.hasChunk(x >> 4, z >> 4)) { tally.merge("未読み込み", 1, Integer::sum); continue; }
                            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                            String kind = null;
                            for (int y = top; y > top - 48 && kind == null; y--) {
                                p.set(x, y, z);
                                var s = level.getBlockState(p);
                                if (s.is(sapling)) kind = "苗木";
                                else if (s.is(log) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_WOOD.get())) {
                                    // 幹の根元(下が土)なら木、そうでなければ枝・ほかの木の幹
                                    var below = level.getBlockState(p.below());
                                    if (!below.is(log) && !below.isAir() && below.isSolidRender(level, p.below())) kind = "木";
                                } else if (!s.getFluidState().isEmpty()) kind = "水";
                                else if (s.isSolidRender(level, p) && !s.is(log)) {
                                    var above = level.getBlockState(p.above());
                                    kind = "なし: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()
                                            + " の上 " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(above.getBlock()).getPath();
                                }
                            }
                            tally.merge(kind == null ? "不明" : kind, 1, Integer::sum);
                        }
                    }
                    tally.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                            .forEach(e -> log("ANCHOR " + e.getKey() + " " + e.getValue()));
                    log("ANCHOR 終わり");
                });
            }
            case "grow" -> {
                // 汚染した地面(高さ y のブロック)の上に、汚染の本来の経路(CorruptionSpreader#growSurfacePlants)で下草・苗木を生やす。
                // 苗木の間隔(treeSpacing)もそのまま効く。例: grow 0 -61 0 63 63(x1 y z1 x2 z2)。2026-10-15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int saplings = 0;
                    for (int x = Math.min(v[0], v[3]); x <= Math.max(v[0], v[3]); x++) {
                        for (int z = Math.min(v[2], v[4]); z <= Math.max(v[2], v[4]); z++) {
                            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(x, v[1], z);
                            // 林床の地面(泥炭・倒木。2026-11-01)も汚染の本来の経路(growPlants)と同じく先に
                            net.minecraft.world.level.block.state.BlockState host =
                                    com.yayuy.overgrownvegetation.corruption.FloorGrowth.beforePlants(level, at, level.getBlockState(at));
                            if (host.is(net.minecraft.world.level.block.Blocks.WATER)) continue;
                            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, host,
                                    com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.surfaceGrowthChance(level, at));
                            if (level.getBlockState(at.above()).is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())) {
                                saplings++;
                            }
                        }
                    }
                    log("GROW 苗木 " + saplings + " 本");
                });
            }
            case "vegstat" -> {
                // 森の具合を数える(壊さない)。例: vegstat 0 0 63 63(x1 z1 x2 z2)。2026-11-01 夜、苗木と下草の確率の見直し。
                // 林冠: 地面(木の一部でない動きを止めるブロック)の 3 段より上に木の一部(葉・原木・木生シダ)がある列の割合。
                // 林床: 地面の真上のマスに何かある(下草・苔・苗木・幹)列の割合と、3×3 がどれも空の窓の割合。植物 = 地面の真上の木の一部でないもの(1 チャンクあたり)。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int x0 = Math.min(v[0], v[2]), z0 = Math.min(v[1], v[3]);
                    int w = Math.abs(v[2] - v[0]) + 1, d = Math.abs(v[3] - v[1]) + 1;
                    boolean[][] filled = new boolean[w][d];
                    int canopy = 0, upper = 0, floor = 0, moss = 0, plants = 0, saplings = 0, columns = 0;
                    long skyLight = 0;
                    int spacing = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.treeSpacing.get();
                    double clearing = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.treeClearingChance.get();
                    double gapChance = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.treeCanopyGapChance.get();
                    java.util.TreeMap<String, Integer> anchors = new java.util.TreeMap<>();
                    java.util.HashMap<String, Integer> bareGround = new java.util.HashMap<>();
                    var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int i = 0; i < w; i++) {
                        for (int j = 0; j < d; j++) {
                            int x = x0 + i, z = z0 + j;
                            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                            int ground = Integer.MIN_VALUE;
                            boolean tree = false;
                            for (int y = top; y > level.getMinBuildHeight(); y--) {
                                var s = level.getBlockState(cursor.set(x, y, z));
                                if (com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s)) {
                                    tree = true;
                                    continue;
                                }
                                if (s.blocksMotion() && s.getFluidState().isEmpty()) {
                                    ground = y;
                                    break;
                                }
                                if (!s.getFluidState().isEmpty()) {
                                    break; // 水の列は数えない
                                }
                            }
                            if (ground == Integer.MIN_VALUE) continue;
                            columns++;
                            boolean high = false;
                            for (int y = top; y > ground + 3 && !high; y--) {
                                high = com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(level.getBlockState(cursor.set(x, y, z)));
                            }
                            if (tree && high) canopy++;
                            if (tree && top >= ground + 16 && com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(level.getBlockState(cursor.set(x, top, z)))) upper++; // 上層: 列の頂が地面の 16 段以上上の葉・木
                            skyLight += level.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor.set(x, ground + 1, z));
                            // 苗木の列(TreeSpacing の格子・林冠の穴の外)に木が立ったか。立っていなければ地面の種類で理由を分ける。
                            if (com.yayuy.overgrownvegetation.corruption.TreeSpacing.allowsTree(level.getSeed(), x, z, spacing, clearing)
                                    && !com.yayuy.overgrownvegetation.corruption.TreeSpacing.inCanopyGap(level.getSeed(), x, z, gapChance)) {
                                var groundState = level.getBlockState(cursor.set(x, ground, z));
                                var standState = level.getBlockState(cursor.set(x, ground + 1, z));
                                String key;
                                if (standState.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock) key = "木生シダ";
                                else if (com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(standState)) key = "木";
                                else if (standState.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())) key = "苗木";
                                else if (groundState.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                                        || groundState.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get())) key = "無し:汚染した土(" + (standState.isAir() ? "上は空気" : "上に" + standState.getBlock().getDescriptionId().replace("block.", "")) + ")";
                                else if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, cursor.set(x, ground, z))) key = "無し:覆った " + groundState.getBlock().getDescriptionId().replace("block.", "");
                                else if (groundState.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED)) key = "無し:汚染した " + groundState.getBlock().getDescriptionId().replace("block.", "");
                                else key = "無し:未汚染 " + groundState.getBlock().getDescriptionId().replace("block.", "");
                                anchors.merge(key, 1, Integer::sum);
                            }
                            var above = level.getBlockState(cursor.set(x, ground + 1, z));
                            if (above.isAir() || above.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_MOSS_LAYER.get())) {
                                var g = level.getBlockState(cursor.set(x, ground, z));
                                String gk = g.getBlock().getDescriptionId().replace("block.", "") + (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, cursor) ? "(覆い)" : "");
                                bareGround.merge(gk, 1, Integer::sum);
                            }
                            if (above.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_MOSS_LAYER.get())) {
                                moss++; // 薄い苔だけのマスは目線では地面と変わらないので「空」に数える
                            } else if (!above.isAir()) {
                                floor++;
                                filled[i][j] = true;
                                if (above.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())) saplings++;
                                if (!com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(above)) plants++;
                            }
                        }
                    }
                    int windows = 0, empty = 0;
                    for (int i = 1; i + 1 < w; i++) {
                        for (int j = 1; j + 1 < d; j++) {
                            windows++;
                            boolean any = false;
                            for (int a = -1; a <= 1 && !any; a++) for (int b = -1; b <= 1 && !any; b++) any = filled[i + a][j + b];
                            if (!any) empty++;
                        }
                    }
                    log(String.format(java.util.Locale.ROOT,
                            "VEGSTAT 列 %d 林冠 %.1f%% 上層 %.1f%% 林床の空の光 %.2f 林床に植物 %.1f%% 苔だけ %.1f%% 3x3 が空 %.1f%% 植物 %.0f/チャンク 苗木 %d",
                            columns, 100.0 * canopy / Math.max(1, columns), 100.0 * upper / Math.max(1, columns), (double) skyLight / Math.max(1, columns), 100.0 * floor / Math.max(1, columns), 100.0 * moss / Math.max(1, columns),
                            100.0 * empty / Math.max(1, windows), plants * 256.0 / Math.max(1, w * d), saplings));
                    log("VEGSTAT 苗木の列 " + anchors);
                    log("VEGSTAT 植物の無い列の地面 " + bareGround.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8).toList());
                });
            }
            case "wgrow" -> {
                // 箱の中の水に接したブロックから、汚染の本来の経路(UnderwaterPlants#grow)で水中の植物を生やす。例: wgrow 0 40 0 63 62 63(2026-11-01)
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int hosts = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2], v[3], v[4], v[5])) {
                        net.minecraft.core.BlockPos at = p.immutable();
                        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(at);
                        if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                        boolean wet = false;
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                            wet |= level.getBlockState(at.relative(d)).is(net.minecraft.world.level.block.Blocks.WATER);
                        }
                        if (!wet) continue;
                        com.yayuy.overgrownvegetation.corruption.UnderwaterPlants.grow(level, at, s);
                        hosts++;
                    }
                    log("WGROW 水に接したブロック " + hosts);
                });
            }
            case "block" -> {
                // そのマスのブロックの状態をサーバーから読んで書く(汚染の置き換え先・向き・開閉の確かめ)。例: block 3 -60 2
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(v[0], v[1], v[2]);
                    boolean recorded = com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at);
                    log("BLOCK " + v[0] + " " + v[1] + " " + v[2] + " " + level.getBlockState(at) + (recorded ? " (苔の記録で覆われている)" : ""));
                });
            }
            case "deepwhy" -> {
                // そのブロックを含むチャンクの深部浸食の様子(カーソル・入口で済んでいない地表の列)。例: deepwhy 8 8。2026-11-03 村の覆いの切り分け
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] b = arg.trim().split("\\s+");
                var chunk = new net.minecraft.world.level.ChunkPos(Integer.parseInt(b[0]) >> 4, Integer.parseInt(b[1]) >> 4);
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    log("DEEPWHY " + chunk + " " + com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep.deepenStatusForDev(level, chunk));
                });
            }
            case "peel", "creepat", "coverat" -> {
                // peel x y z = 覆いを剥がす(OvergrownLayerData.uncover。工兵・ハサミと同じ入口)/ creepat x y z = 這い上がる苔の記録と剥がしの猶予を書く。
                // 2026-11-03 剥がしたマスの猶予の確かめ
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(v[0], v[1], v[2]);
                    if (op.equals("coverat")) {
                        log("COVERAT " + at.toShortString() + " " + com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.cover(level, at));
                        return;
                    }
                    if (op.equals("peel")) {
                        log("PEEL " + at.toShortString() + " " + com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.uncover(level, at));
                        return;
                    }
                    long rec = com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, at);
                    boolean grace = com.yayuy.overgrownvegetation.corruption.CoverCreepData.inPeelGrace(level, level.getChunkAt(at), at);
                    log("CREEPAT " + at.toShortString() + " 覆い " + com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at)
                            + " 記録 " + (rec == Long.MIN_VALUE ? "無し" : "時刻 " + com.yayuy.overgrownvegetation.corruption.CoverCreepData.startTick(rec)
                            + " 向き " + com.yayuy.overgrownvegetation.corruption.CoverCreepData.sides(rec)) + " 猶予中 " + grace + " 今 " + level.getGameTime());
                });
            }
            case "coverbox" -> {
                // 箱の中の空気でないブロックを「汚染ブロック(#corrupted)/ 苔の記録で覆われた / 素のまま」に分け、素のままをブロックごとに数える。
                // 例: coverbox 0 -60 0 15 -50 15。2026-11-03 村の覆いの切り分け
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int corrupted = 0, covered = 0, plain = 0, creeping = 0;
                    java.util.Map<String, int[]> plainBy = new java.util.TreeMap<>();
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(
                            Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
                            Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]))) {
                        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
                        if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
                        if (s.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED)) {
                            corrupted++;
                        } else if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, p)) {
                            covered++;
                        } else {
                            plain++;
                            if (com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, p) != Long.MIN_VALUE) creeping++;
                            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()
                                    + (s.getFluidState().isEmpty() ? "" : "(水)");
                            plainBy.computeIfAbsent(id, k -> new int[1])[0]++;
                        }
                    }
                    log("COVERBOX 汚染ブロック " + corrupted + " 覆い " + covered + " 素のまま " + plain + "(うち這い上がる苔 " + creeping + ") " + plainBy.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue()[0]).collect(java.util.stream.Collectors.joining(" ")));
                });
            }
            case "research" -> {
                // 研究のノードを開く(2026-10-24、封印されたレシピを撮る用)。例: research ley_conversion
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.player == null || server == null) return "ワールドの中";
                java.util.UUID id = mc.player.getUUID();
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                    if (sp == null) return;
                    log("RESEARCH " + arg.trim());
                });
            }
            case "creative" -> {
                // クリエイティブのインベントリを開いてタブを選ぶ。2 つ目は下へのスクロール(0〜1)。例: creative overgrown_vegetation:plants 0.5
                LocalPlayer player = mc.player;
                if (player == null || mc.level == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                ResourceLocation key = ResourceLocation.tryParse(a[0]);
                net.minecraft.world.item.CreativeModeTab tab = key == null ? null : BuiltInRegistries.CREATIVE_MODE_TAB.get(key);
                if (tab == null) {
                    log("ERR creative: タブが無い " + a[0]);
                    return null;
                }
                var screen = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                        player, mc.level.enabledFeatures(), mc.options.operatorItemsTab().get());
                mc.setScreen(screen);
                try {
                    // selectTab は private。setScreen の init が前回のタブを選び直すので、そのあとで選ぶ
                    var select = net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class
                            .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                    select.setAccessible(true);
                    select.invoke(screen, tab);
                    if (a.length > 1) screen.getMenu().scrollTo(Float.parseFloat(a[1]));
                    log("CREATIVE " + key + " (" + tab.getDisplayItems().size() + " 個)");
                } catch (ReflectiveOperationException e) {
                    log("ERR creative: " + e);
                }
            }
            case "respawn" -> {
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                if (player.isDeadOrDying()) {
                    player.respawn(); // 死亡画面の「リスポーン」と同じ。画面はリスポーンが届くと閉じる
                    log("RESPAWN");
                } else {
                    log("RESPAWN 生きているので何もしない");
                }
            }
            case "status" -> status(mc);
            // チャンクの読み込み解除の中身(2026-10-18、ワールドを閉じると固まる件)。chunkwatch は leave の前に流す
            case "chunkdump" -> log(ChunkDump.dump(mc, dir, arg.isEmpty() ? "chunkdump" : arg));
            case "chunkwatch" -> log(ChunkDump.watch(mc, dir));
            case "echo" -> log("ECHO " + arg);
            // スポーンエッグの eggFit を実際の描画から測る(2026-11-16)。eggfit [id の一部] [tick 数]。返事は EGGFIT …
            case "eggfit" -> {
                String[] ef = arg.trim().split("\\s+");
                boolean num = ef.length > 0 && ef[ef.length - 1].matches("\\d+");
                String filter = ef.length > 0 && !ef[0].matches("\\d+") ? ef[0] : "";
                log(com.yayuy.overgrownvegetation.client.EggFitProbe.start(filter, num ? Integer.parseInt(ef[ef.length - 1]) : 120, DevBridge::log));
            }
            // 重さを測る(2026-10-18、FxProfiler)。bench は終わるまで次の命令へ進まない。
            case "fxprof" -> log(FxProfiler.command(arg));
            // 雰囲気の演出 1 つずつの GPU の時間・雰囲気の重さの段だけの切り替え・光のにじみの止め(2026-11-23 軽量化 GPU、GpuProbe)
            case "gpuprobe" -> log(GpuProbe.command(arg));
            // 予定の雨と種類(2026-11-12 雨の種類、sky.RainIntensity)。今から 12 日分の雨の始まり・終わり(dayTime)・種類・雷雨。返事は RAINS …
            case "rains" -> {
                long t0 = mc.level == null ? 0L : mc.level.getDayTime();
                StringBuilder sb = new StringBuilder("RAINS");
                for (long d = Math.floorDiv(t0 + 6000L, 24000L); d <= Math.floorDiv(t0 + 6000L, 24000L) + 12; d++) {
                    com.yayuy.overgrownvegetation.sky.CloudWeather.Rain r = com.yayuy.overgrownvegetation.sky.CloudWeather.rainOfDay(d);
                    if (r != null) {
                        sb.append(String.format(" [%d〜%d 強さ %.1f%s]", r.from(), r.to(), com.yayuy.overgrownvegetation.sky.RainIntensity.kind(r),
                                r.thunderTo() > r.thunderFrom() ? " 雷 " + r.thunderFrom() + "〜" + r.thunderTo() : ""));
                    }
                }
                log(sb.toString());
            }
            // 雲の流れの跳びの計測(2026-11-12、CloudProbe)。cloudprobe start / stop。
            case "cloudprobe" -> log(CloudProbe.command(arg, dir));
            case "winsize" -> log(FxProfiler.winsize(mc, arg));
            // 資源パックの入り切り(2026-10-23、バニラの地形のシェーダーと比べる)。rpack <フォルダ名> on|off。読み直しが終わると RPACK 終わり
            case "rpack" -> {
                String[] rp = arg.trim().split("\\s+");
                var repo = mc.getResourcePackRepository();
                repo.reload();
                java.util.List<String> sel = new java.util.ArrayList<>(repo.getSelectedIds());
                String id = "file/" + rp[0];
                sel.remove(id);
                if (rp.length < 2 || !rp[1].equalsIgnoreCase("off")) {
                    sel.add(id);
                }
                repo.setSelected(sel);
                log("RPACK 選んだパック " + repo.getSelectedIds());
                mc.reloadResourcePacks().thenRun(() -> mc.execute(() -> log("RPACK 終わり")));
            }
            case "rd" -> {
                // 描画距離を変える(区画の数で重さが変わるかの撮り比べ用、2026-10-19)。options.txt には書かない
                mc.options.renderDistance().set(Integer.parseInt(arg.trim()));
                log("RD " + mc.options.renderDistance().get());
            }
            case "gldebug" -> {
                // GL のデバッグ出力を入り切りする(バニラは glDebugVerbosity 1 で入れている。ドライバの確かめが重いかの撮り比べ用、2026-10-19)
                if (arg.equalsIgnoreCase("off")) {
                    org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
                } else {
                    org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
                }
                log("GLDEBUG " + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT));
            }
            case "fastcull" -> {
                // 見える区画の選び直しの近道(client.SectionFrustumCache)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "shortlists" -> {
                // 見える区画の短い一覧(mixin.client.LevelRendererSectionListsMixin)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "depthat" -> {
                return FxProfiler.depthAt(arg, DevBridge::log);
            }
            case "uploadbudget" -> {
                // 区画の送り込みを 1 フレーム 2 ms までに(client.SectionUploadBudget)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "fogbacklog" -> {
                // 霧で描かずにいる区画を裏で組み立てておく(client.FogCullBacklog)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "lazycopy" -> {
                // 区画を組み立てる前の写しを上下 1 区画だけに(client.LazySectionCopy)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "fastdraw" -> {
                // 地形の区画を決まった器で描く(client.FastChunkDraw)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                } else if (arg.equalsIgnoreCase("on")) {
                }
            }
            case "visualsettings" -> {
                // 見た目の設定の画面を開く(2026-11-18。大群の項目を撮る)
                mc.setScreen(new com.yayuy.overgrownvegetation.client.difficulty.VisualSettingsScreen(mc.screen));
                if (arg.trim().equalsIgnoreCase("end") && mc.screen != null) {
                    // 一覧をいちばん下まで(新しい項目は下に足すことが多い)
                    for (var child : mc.screen.children()) {
                        if (child instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) list.setScrollAmount(list.getMaxScroll());
                    }
                }
                log("VISUALSETTINGS 開いた");
            }
            case "rendertier" -> {
                // この MOD の演出の段(2026-11-23)。rendertier none|low|medium|high で書き込み、引数なしは今の段。videosettings はビデオ設定を開く
                String a = arg.trim().toUpperCase(java.util.Locale.ROOT);
                if (a.equals("VIDEO")) {
                    mc.setScreen(new net.minecraft.client.gui.screens.VideoSettingsScreen(mc.screen, mc.options));
                } else if (!a.isEmpty()) {
                    com.yayuy.overgrownvegetation.config.QualityPreset.valueOf(a).writeToConfig();
                    com.yayuy.overgrownvegetation.client.GeoStaticGeometry.applyDetailChange();
                }
                var now = com.yayuy.overgrownvegetation.config.QualityPreset.current();
                log("RENDERTIER " + (now == null ? "CUSTOM" : now.name()));
            }
            case "impostor" -> {
                // 遠景の身代わりの重さ比べ(devbridge/impostor/ImpostorBench、2026-11-18)
                log(com.yayuy.overgrownvegetation.devbridge.impostor.ImpostorBench.command(arg, DevBridge::log));
            }
            case "crowd" -> {
                // 大群の描画の重さの切り分け(client.crowd.CrowdRenderDev、2026-11-18)。crowd anim off|on・crowd draw off|on(off = 飛ばす)
                String[] p = arg.trim().split("\\s+");
                if (p.length == 2) {
                    boolean skip = p[1].equalsIgnoreCase("off");
                    if (p[0].equalsIgnoreCase("anim")) com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipAnim = skip;
                    if (p[0].equalsIgnoreCase("draw")) com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipDraw = skip;
                    if (p[0].equalsIgnoreCase("sink")) com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.sink = skip;
                    if (p[0].equalsIgnoreCase("gpu")) com.yayuy.overgrownvegetation.client.crowd.CrowdDraw.enabled = !skip;
                    if (p[0].equalsIgnoreCase("tickrate")) com.yayuy.overgrownvegetation.client.crowd.TickRatePose.enabled = !skip;
                    if (p[0].equalsIgnoreCase("ticktrim")) com.yayuy.overgrownvegetation.entity.ClientTickTrim.enabled = !skip;
                    if (p[0].equalsIgnoreCase("virtual")) com.yayuy.overgrownvegetation.client.crowd.VirtualHordeDraw.enabled = !skip;
                    if (p[0].equalsIgnoreCase("look")) {
                        com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.hordeLook.set(
                                com.yayuy.overgrownvegetation.client.crowd.HordeLook.valueOf(p[1].toUpperCase(java.util.Locale.ROOT)));
                    }
                }
                log("CROWD " + com.yayuy.overgrownvegetation.client.crowd.CrowdDraw.status());
                log("CROWD " + com.yayuy.overgrownvegetation.client.crowd.HordeImpostors.status());
                log("CROWD アニメを飛ばす " + com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipAnim
                        + "・描かない " + com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipDraw
                        + "・頂点を捨てる " + com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.sink);
            }
            case "bench" -> {
                return FxProfiler.bench(mc, arg, DevBridge::log, dir);
            }
            case "close" -> {
                if (mc.screen != null) mc.screen.onClose();
                log("CLOSE");
            }
            case "inventory" -> {
                // サバイバルの持ち物の画面を開く(追加防具の枠を撮るため。2026-10-15)
                if (mc.player == null) return "ワールドの中";
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                log("INVENTORY");
            }
            case "pause" -> {
                // ポーズ画面を開く(葉っぱのボタン = Overgrown Vegetation の設定 などを押すため。2026-10-09)
                if (mc.level == null) return "ワールドの中";
                mc.pauseGame(false);
                log("PAUSE");
            }
            case "mouse" -> {
                // カーソルを動かす(GUI の拡大後の座標)。撮るときにツールチップ・枠の強調を出したく / 出したくないとき(2026-10-16)
                String[] p = arg.trim().split("\\s+");
                double s = mc.getWindow().getGuiScale();
                double mx = Double.parseDouble(p[0]) * s, my = Double.parseDouble(p[1]) * s;
                long window = mc.getWindow().getWindow();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(window, mx, my);
                try {
                    // glfwSetCursorPos は動いた知らせを出さないことがあるので、ゲームのマウスにも直に伝える。
                    java.lang.reflect.Method onMove = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    onMove.setAccessible(true);
                    onMove.invoke(mc.mouseHandler, window, mx, my);
                } catch (ReflectiveOperationException e) {
                    log("ERR mouse: " + e);
                }
                log("MOUSE " + p[0] + " " + p[1] + " → " + mc.mouseHandler.xpos() + "," + mc.mouseHandler.ypos());
            }
            case "guiscale" -> {
                // GUI の拡大を変える(0 = 自動)。字の大きさで割り付けの変わる画面を、起動し直さずに見比べる(2026-10-16)
                mc.options.guiScale().set(Integer.parseInt(arg.trim()));
                mc.resizeDisplay();
                log("GUISCALE " + mc.getWindow().getGuiScale() + " " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
            }
            case "widgets" -> {
                // 開いている画面のボタン・入力欄の位置(GUI の拡大後)と文字。click の座標を決めるのに(2026-10-09)
                if (mc.screen == null) {
                    log("ERR widgets: 画面が開いていない");
                    return null;
                }
                for (var child : mc.screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                        log(String.format(Locale.ROOT, "WIDGET %d %d %dx%d %s %s", w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                                w.getClass().getSimpleName(), w.getMessage().getString()));
                    }
                }
            }
            case "tooltip" -> {
                // 手に持った物のツールチップ(F3+H なしのふつうの表示)を 1 行ずつ。ホバーは撮れないので文字で確かめる(2026-10-09)
                if (mc.player == null || mc.level == null) return "ワールドの中";
                net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
                for (Component tip : held.getTooltipLines(mc.player,
                        net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
                    log("TIP " + tip.getString());
                }
            }
            case "skyboss" -> {
                // 空の巨大生物の当たり判定の確かめ用(2026-10-18 夜): skyboss <体の骨> <横> <上>
                // = その骨の真ん中の輪切りの中心から、体の横の向きへ <横>・世界の上へ <上> ブロックの所へ移って、輪切りの中心を向く。返事は SKYBOSS …(体力も)
                // 10-18 夜: skyboss rod|budNN_l <横> <上>(避雷針・蕾の所から)、skyboss break rod|<蕾の名前か番号>|buds <数>(弱点を今すぐ壊す。ボスの体力は減らさない)
                String[] a0 = arg.split("\\s+");
                // 10-19: skyboss w <骨> <横> <上> = <横> を体の横(転がると傾く)でなく、体の軸に直角な世界の水平の向きに取る(転がる途中を真横から撮る)
                boolean worldSide = a0.length >= 4 && a0[0].equals("w");
                String[] a = worldSide ? new String[]{a0[1], a0[2], a0[3]} : a0;
                // 10-19: skyboss hang(背の垂れ草の板が世界の真下から何度か。描いた最後のフレームで)・skyboss lines on|off(確かめ用の線)
                if (a[0].equals("hang")) {
                    return null;
                }
                if (a[0].equals("arcs")) {
                    return null;
                }
                if (a[0].equals("lines")) {
                    boolean on = !(a.length >= 2 && a[1].equals("off"));
                    log("SKYBOSS LINES " + (on ? "on" : "off"));
                    return null;
                }
                if (a[0].equals("eyes")) {
                    log("SKYBOSS EYES " + (a.length >= 3 ? a[1] + " " + a[2] : "off"));
                    return null;
                }
                boolean brk = a.length >= 2 && a[0].equals("break");
                // 10-19: skyboss status(雷雲に寄る・留まるの状態)・skyboss seek [範囲](いちばん近い巨大な積乱雲へ今すぐ寄らせる)
                // 10-19: skyboss roll <度>(体の軸のまわりの向きへ今すぐ転がる。180 = 腹が上・0 = 背が上。背の垂れ草を見る)
                // 10-19: skyboss charge <0〜1>(溜め)・skyboss discharge(今すぐ放電を始める)。status に放電の状態も
                // 10-19 の技: skyboss attack volley|sap|downdraft|spit|lash|grab|seed|beam(次の tick にその技を出す。戦いの中にする)・skyboss light <0〜1>(日の光の溜まり)
                boolean info = a[0].equals("status") || a[0].equals("seek") || a[0].equals("roll") || a[0].equals("charge") || a[0].equals("discharge")
                        || a[0].equals("attack") || a[0].equals("light") || a[0].equals("tentgrab") || a[0].equals("shiver") || a[0].equals("sea");
                if (a.length < 3 && !brk && !info) return "skyboss <骨|rod|budNN_l> <横> <上> / skyboss break rod|<蕾>|buds <数>";
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) return "ワールドの中";
                java.util.UUID me = mc.player.getUUID();
                double side = brk || info ? 0.0 : Double.parseDouble(a[1]);
                double up = brk || info ? 0.0 : Double.parseDouble(a[2]);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(me);
                    if (brk) {
                        return;
                    }
                    if (info) {
                        if (a[0].equals("seek")) {
                            double range = a.length >= 2 ? Double.parseDouble(a[1]) : 20000.0;
                        }
                        if (a[0].equals("roll") && a.length >= 2) {
                            log("SKYBOSS ROLL " + a[1] + " へ転がり始めた");
                        }
                        if (a[0].equals("charge") && a.length >= 2) {
                            log("SKYBOSS CHARGE " + a[1]);
                        }
                        if (a[0].equals("attack") && a.length >= 2) {
                            long gtA = level.getGameTime();
                        }
                        if (a[0].equals("tentgrab") && p != null) {
                            // skyboss tentgrab [触手 0〜7]: 次の tick に絡め取るを始め、捕まえる間(56 tick 後)に触手の先の方が来る所へ移って待つ
                            int tn = a.length >= 2 ? Integer.parseInt(a[1]) : 0;
                        }
                        if (a[0].equals("sea") && a.length >= 2) {
                        }
                        if (a[0].equals("shiver")) {
                            log("SKYBOSS SHIVER 次の tick に");
                        }
                        if (a[0].equals("light") && a.length >= 2) {
                            log("SKYBOSS LIGHT " + a[1]);
                        }
                        if (a[0].equals("discharge")) {
                        }
                        long gt = level.getGameTime();
                        return;
                    }
                    boolean rodT = a[0].equals("rod");
                    int idx = 0;
                    int o = idx * 3;
                    if (rodT) {
                    }                     if (worldSide) {
                    }
                });
            }
            case "reloadres" -> {
                // 資源(モデル・テクスチャ)を読み直す = F3+T。起動中のゲームが読む写し runs/_snap/<キー>/resources を直してから流すと、
                // 起動し直さずに見た目を詰められる(2026-10-09。11-14 から build/resources/main ではなく写しを読む。build.gradle の OV_SNAP)
                mc.reloadResourcePacks().thenRun(() -> log("RELOADRES 終わり"));
            }
            case "leave" -> {
                if (mc.level == null) return "ワールドの中";
                log("LEAVE");
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local) {
                    mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Saving world")));
                } else {
                    mc.clearLevel();
                }
                mc.setScreen(new TitleScreen());
                await("title");
            }
            case "quit" -> {
                log("QUIT");
                mc.stop();
            }
            default -> log("ERR 知らない命令: " + line);
        }
        return null;
    }

    /** 目(視点)から箱までの距離が 6 以内で一番近い、その種類のエンティティ。大きい体でも箱の端で測る。 */
    private static Entity nearest(Minecraft mc, LocalPlayer player, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        Optional<EntityType<?>> type = key == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(key);
        if (type.isEmpty()) return null;
        Vec3 eye = player.getEyePosition();
        Entity best = null;
        double bestSqr = 6.0 * 6.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == player || entity.getType() != type.get()) continue;
            double sqr = boxDistanceSqr(entity.getBoundingBox(), eye);
            if (sqr <= bestSqr) {
                best = entity;
                bestSqr = sqr;
            }
        }
        return best;
    }

    private static double boxDistanceSqr(AABB box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0.0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0.0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0.0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void await(String state) {
        awaiting = state;
        awaitingSince = tick;
    }

    private static void newWorld(Minecraft mc, String arg) throws IOException {
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        String name = a.length > 0 ? a[0] : "claude_test";
        boolean flat = a.length > 1 && a[1].equalsIgnoreCase("flat");
        long seed = 0L;
        if (a.length > 2) {
            try {
                seed = Long.parseLong(a[2]);
            } catch (NumberFormatException e) {
                seed = a[2].hashCode(); // バニラと同じく文字のシードは hashCode
            }
        }
        // 同じ名前のフォルダがあれば作成画面と同じく別の名前にする(上書きしない)
        String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), name, "");
        LevelSettings settings = new LevelSettings(folder, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        Function<RegistryAccess, WorldDimensions> dimensions = flat
                ? registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                : WorldPresets::createNormalWorldDimensions;
        log("NEWWORLD " + folder + (flat ? " flat" : " normal") + " seed=" + seed);
        mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(seed, true, false), dimensions);
    }

    /** 統合サーバーのスレッドで、プレイヤーとして実行する(~ や @s がプレイヤーを指す)。 */
    private static void command(IntegratedServer server, UUID playerId, String raw) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        log("CMD> " + command);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            CommandSourceStack source = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
                    .withSource(OUTPUT)
                    .withPermission(4);
            server.getCommands().performPrefixedCommand(source, command);
        });
    }

    private static void shot(Minecraft mc, String arg) {
        String name = arg.isEmpty() ? "shot_" + tick : arg.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (!name.endsWith(".png")) name += ".png";
        String file = name;
        Path path = mc.gameDirectory.toPath().resolve("screenshots").resolve(file).toAbsolutePath();
        // 書き込みは別スレッド。書き終えたら(または失敗したら)返事が来る
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(),
                message -> log("SHOT " + path + " : " + message.getString()));
    }

    private static void status(Minecraft mc) {
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        String world = mc.level == null ? "none" : mc.level.dimension().location().toString();
        String pos = mc.player == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f yaw=%.0f pitch=%.0f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
        int entities = mc.level == null ? 0 : mc.level.getEntityCount();
        log("STATUS screen=" + screen + " world=" + world + " pos=" + pos + " fps=" + mc.getFps() + " entities=" + entities
                + " queue=" + next + "/" + lines.size());
    }

    /** out.log に 1 行。描画のスレッドとサーバーのスレッドの両方から呼ばれる。 */
    private static synchronized void log(String message) {
        if (dir == null) return;
        String line = "[" + LocalTime.now().format(TIME) + " t=" + tick + "] " + message + System.lineSeparator();
        try {
            Files.writeString(dir.resolve("out.log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("devbridge: out.log に書けない: {}", message, e);
        }
    }
}
