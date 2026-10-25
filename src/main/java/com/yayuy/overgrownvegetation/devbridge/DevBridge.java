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
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.FileUtil;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Claude が自分で起動したクライアントを、外からファイルで操作する開発用の口。使い方は {@code ../tools/claude-client/README.md}。
 * 古い作りの {@code devbridge/DevBridge} から、どの段でも使う命令だけを移した(2026-10-25)。仕組みごとの命令は、その段で足す。
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
 *   <li>{@code key <キーの名前>} — 割り当てたキーを 1 回押す</li>
 *   <li>{@code hold|walk|sneak <tick>} — 右クリック・前へ歩く・スニークを押し続ける(次の命令は待たない)</li>
 *   <li>{@code look <yaw> <pitch>} — 視点の向き</li>
 *   <li>{@code camera first|back|front} — 一人称 / 三人称(後ろから)/ 三人称(前から)</li>
 *   <li>{@code respawn} — 死んでいたらリスポーン</li>
 *   <li>{@code status} — 画面・次元・座標・FPS・エンティティ数</li>
 *   <li>{@code echo <文字>} — out.log に目印を書く(バッチの終わりの合図に)</li>
 *   <li>{@code close} / {@code leave} / {@code quit} — 画面を閉じる / 保存してタイトルへ / ゲームを閉じる</li>
 * </ul>
 * 状態が変わると {@code TITLE}(タイトル画面)・{@code READY}(ワールドが見えている)・{@code SCREEN}(画面の切り替わり)を書く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
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
    /** hold / walk / sneak でキーを押している終わりの tick(0 = 押していない)。 */
    private static long holdUntil;
    private static long walkUntil;
    private static long sneakUntil;
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

    private DevBridge() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (!started && !start(mc)) return;
        // 始められなかった(別のクライアントが使っている等)ら、次の tick からも何もしない
        if (dir == null) return;
        // 押しっぱなしは毎 tick 押し直す(窓が裏に回ると全部のキーが離される)
        holdUntil = pressWhile(mc, mc.options.keyUse, holdUntil, "HOLD");
        walkUntil = pressWhile(mc, mc.options.keyUp, walkUntil, "WALK");
        sneakUntil = pressWhile(mc, mc.options.keyShift, sneakUntil, "SNEAK");
        watchState(mc);
        if (awaiting != null) {
            // ワールドに入ったかは「画面が閉じた」ではなく「読み込みが終わった」で見る(死んだまま開くと死亡画面が出て READY にならない)
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

    private static long pressWhile(Minecraft mc, KeyMapping key, long until, String name) {
        if (until <= 0L) return 0L;
        if (tick < until && mc.player != null && mc.screen == null) {
            key.setDown(true);
            return until;
        }
        key.setDown(false);
        log(name + " 終わり");
        return 0L;
    }

    /** 最初の tick に 1 回。前回の in.txt / out.log を *.prev に回し、空の in.txt を作る。 */
    private static boolean start(Minecraft mc) {
        started = true;
        try {
            dir = mc.gameDirectory.toPath().resolve("devbridge");
            Files.createDirectories(dir);
            Path pid = dir.resolve("running.pid");
            String me = Long.toString(ProcessHandle.current().pid());
            // 別のクライアントが同じフォルダで動いていたら、命令のファイルを回さずに止める(2 つのセッションのクライアントが同じ in.txt を読んでぶつかった)
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
                    // 自分の pid が書いてあるときだけ消す(後から別のクライアントが書いたものは残す)
                    if (Files.exists(pid) && me.equals(Files.readString(pid, StandardCharsets.UTF_8).trim())) {
                        Files.delete(pid);
                    }
                } catch (IOException ignored) {
                    // 次の起動で上書きされる
                }
            }, "devbridge-pid-cleanup"));
            log("START pid=" + me + " dir=" + dir.toAbsolutePath());
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

    /** 1 命令を実行する。前提がまだなら、その理由を返して待つ(行は進めない)。 */
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
                mc.createWorldOpenFlows().openWorld(arg, () -> {
                    log("ERR open に失敗: " + arg);
                    awaiting = null;
                    mc.setScreen(new TitleScreen());
                });
            }
            case "cmd" -> {
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || mc.player == null || server == null) return "ワールドの中";
                command(server, mc.player.getUUID(), arg);
            }
            case "shot" -> shot(mc, arg);
            case "wait" -> waitUntil = tick + Long.parseLong(arg);
            case "hold" -> {
                // 押し始めは 1 回のクリックとして数える(長押しで使う道具が始まる)
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                KeyMapping.click(mc.options.keyUse.getKey());
                mc.options.keyUse.setDown(true);
                holdUntil = tick + Math.max(1L, ticks);
                log("HOLD " + ticks + " tick");
            }
            case "walk" -> {
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                mc.options.keyUp.setDown(true);
                walkUntil = tick + Math.max(1L, ticks);
                log("WALK " + ticks + " tick");
            }
            case "sneak" -> {
                // クリエイティブで飛んでいれば下降
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
            case "key" -> {
                String name = arg.trim();
                for (KeyMapping mapping : mc.options.keyMappings) {
                    if (mapping.getName().equals(name)) {
                        KeyMapping.click(mapping.getKey());
                        log("KEY " + name + " → " + mapping.getKey().getName());
                        return null;
                    }
                }
                return "そのキーの名前は無い: " + name;
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
            case "respawn" -> {
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                if (player.isDeadOrDying()) {
                    player.respawn();
                    log("RESPAWN");
                } else {
                    log("RESPAWN 生きているので何もしない");
                }
            }
            case "status" -> status(mc);
            case "echo" -> log("ECHO " + arg);
            case "close" -> {
                if (mc.screen != null) mc.screen.onClose();
                log("CLOSE");
            }
            case "leave" -> {
                if (mc.level == null) return "ワールドの中";
                log("LEAVE");
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local) {
                    mc.disconnect(new GenericMessageScreen(Component.literal("Saving world")));
                } else {
                    mc.disconnect();
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
        mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(seed, true, false), dimensions, new TitleScreen());
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
