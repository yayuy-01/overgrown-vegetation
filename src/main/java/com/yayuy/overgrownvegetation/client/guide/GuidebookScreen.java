package com.yayuy.overgrownvegetation.client.guide;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.OpenGuidebookPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ガイドブックの画面(2026-10-15、ユーザー指定)。古びた羊皮紙の本の見開き 320×200(GUI の単位)。今開けるのはガイドと Mob の章
 * ({@link #ready})。開くと前に閉じた章(最初はガイド)。
 *
 * <h2>付箋(目次)</h2>
 * <p>7 枚。開いている章より前の章は左の束、後ろの章は右の束から出る(B 案)。付箋は本を描いた後に上から描き、
 * <b>挟まっているページの線(紙の端から {@link #DEPTH} 画素内側)より奥は描かない</b>ので、ページの間から出て見える
 * (紙をレイヤーに分けなくてよい)。紙の端は {@code book_paper_mask.png}(紙 = 白)から行ごとに測る —— 本の絵の色からは測れない
 * (縫い目の明るさが紙と革の間にある)。付箋ごとに太さ(絵)・出る長さ {@link #OUT}・深さ・上下のずれ {@link #SHIFT} を変えてある。
 * 開いている章の付箋はいちばん上のページ(深さ 1)に挟まり、3 画素長く出る。カーソルを合わせると名前(開けない章は「準備中」も)。
 *
 * <h2>文の章(ガイド・アイテム・エフェクト・バイオーム。2026-10-16〜19)</h2>
 * 2026-10-24 ユーザー指定で書き直し: アイテムの説明を全部消し(items.json は空。項目が無い章は {@link #ready} で「準備中」)、
 * ガイドの章は「森の広がり方」(バイオームの章から移した)だけ。消した文と挿絵の控えは {@code overgrown/archive/guidebook_20261024/}。
 * アイテムの章(10-19、ユーザー指定「使い道のある物を種類ごとに・最初から全部読める」)は項目と区分けを
 * {@code assets/overgrown_vegetation/guidebook/items.json} から読む({@link #loadItemTopics})。目次は区分けの見出しを挟んで
 * 何見開きにも流れ(下の角の矢印でめくる)、名前の前と左上にアイテムの絵、最初の右のページの上の枠にその項目のアイテムを並べる。
 * <ul>
 *     <li>目次: 項目 {@link #topicsOf} の名前(左のページ → 右のページへ流す)。押すとその項目へ。エフェクトは名前の前に効果のアイコン。</li>
 *     <li>項目のページ: 本文は言語ファイルの {@code guidebook.overgrown_vegetation.<章>.<項目>.body}、名前は {@code .title}
 *     (エフェクトだけは効果そのものの名前 {@code effect.overgrown_vegetation.<id>} と、左上にアイコン)。{@code \n} で改行、
 *     空の行は半行あけ、「■」で始まる行は見出し、「・」「- 」で始まる行は箇条書き(折り返しは字下げ)。左のページの頭に名前、
 *     ガイドとバイオームは最初の右のページの上に挿絵({@code <章>_<項目>.png}、188×108 = 枠の中の 2 倍。無ければ枠だけ)。
 *     あふれた分は次の見開きへ流す。
 *     日本語は 1 字ずつ折り返し(英数字の続きは切らない・句読点と閉じ括弧は行頭に来ないよう前の行にぶら下げる)、
 *     日本語を含まない文はバニラの折り返し(空白で切る)。ページ割りは {@link #init()} で(字の倍率が窓で変わる)。</li>
 *     <li>左下の矢印: 最初の見開きなら目次へ、それ以外は前の見開きへ。</li>
 * </ul>
 *
 * <h2>Mob の章</h2>
 * <ul>
 *     <li>目次: グループごと(G1・G2…)。見出しを押すと畳める / 開く。見出しの右は「出会った数 / その組の数」。
 *     まだ出会っていない Mob は「？？？」。左のページ → 右のページ → 次の見開きへ流す。</li>
 *     <li>Mob のページ: 左に名前・グループ・説明(言語ファイルに書いてあれば。今は空)、右に回る模型・体力・攻撃力・防御力・
 *     ドロップ(アイテムの絵。カーソルで名前)。左下の矢印で目次へ戻る。</li>
 * </ul>
 *
 * <h2>動き(2026-10-15 ユーザー指定「付箋を押した・ページを進める・項目を押す・開く・閉じるときのアニメーション」)</h2>
 * <ul>
 *     <li>開く: 下から持ち上がり、少し小さいところから元の大きさへ。閉じる: 逆に沈んで閉じる(ESC の 2 度押しでもやり直さない)。</li>
 *     <li>ページをめくる(矢印・項目・目次へ戻る): 折り目を軸にページが 1 枚めくれる。前半は今のページが折り目へ縮み、
 *     後半は次のページが折り目から広がる。めくれる紙は {@code page_left/right.png}(そちら側の紙だけの絵)を横に縮めて描き、
 *     中身も同じ変形で描く(模型だけは描かない —— 切り抜きが画面の座標なので縮みに付いて来ない)。めくれる途中は紙を暗くし、
 *     下のページに影を落とす。</li>
 *     <li>付箋を押す: 付箋が少し引っ張られ、ページを数枚パラパラめくって章の頭へ。付箋にカーソルを合わせると 2 画素出てくる。</li>
 *     <li>音: ページ 1 枚ごとに本をめくる音、開く・閉じるときに本を置く音(バニラの音)。</li>
 *     <li>時間は壁の時計({@link Util#getMillis()})で測る(重いときに伸びないように)。めくっている間は中身を押せない(ESC は効く)。</li>
 * </ul>
 * 別の章の付箋を押すと、その向きへパラパラめくり、めくりの半ばで付箋が左右の束を移る(今の章の付箋は左の束のいちばん下)。
 *
 * <h2>字の大きさ</h2>
 * <p>GUI の拡大が 3 倍以上なら 0.75 倍、2 倍以下なら 1 倍(ユーザー指定。0.75 倍は GUI 2 倍で字がつぶれる)。
 * 当たり判定・行の高さ・字の幅はすべてこの倍率を掛ける。ツールチップは倍率を掛けない。
 *
 * <p>ゲームを止めない画面({@link #isPauseScreen()} = false)。
 */
public class GuidebookScreen extends Screen {

    static final int BOOK_W = 320, BOOK_H = 200;
    static final String[] TABS = {"guide", "mob", "item", "effect", "biome", "lore", "thanks"};
    static final int GUIDE = 0, MOB = 1, ITEM = 2, EFFECT = 3, BIOME = 4;
    /** 文の章の項目(言語ファイルのキーと挿絵の名前)。エフェクトは効果の id。 */
    static final String[] GUIDE_TOPICS = {"spread"};
    static final String[] EFFECT_TOPICS = {"spore_infection", "clinging_spores", "phyllosens_watch", "sporophyll_spores",
            "phyllosens_guidance", "water_rot", "well_fed", "savory_scent", "spore_ward", "alertness", "stasis", "stage_guard"};
    static final String[] BIOME_TOPICS = {"jungle", "swamp", "fungal_swamp", "water", "climax", "crystal", "nether", "end", "burnt"};
    /** 挿絵の枠(最初の右のページの上)と絵の大きさ(枠の中 94×54 の 2 倍)。 */
    static final int IMG_FW = 100, IMG_FH = 60, IMG_TW = 188, IMG_TH = 108;
    /** 行頭に来てはいけない字(前の行にぶら下げる)。 */
    static final String NO_LINE_START = "、。，．,.)）」』】〕！？!?ー…：:；;%％";
    /** 行末に来てはいけない字(次の行へ送る)。 */
    static final String NO_LINE_END = "(（「『【〔";
    /** 次に開くときの章(閉じたときの章を覚える。ゲームを閉じるまで)。 */
    static int lastChapter = GUIDE;
    static final int[] OUT = {20, 17, 22, 19, 23, 18, 21};
    static final int[] DEPTH = {3, 6, 4, 9, 5, 8, 6};
    static final int[] SHIFT = {0, 1, -1, 2, 0, -2, 1};

    static final int INK = 0xFF3B2A1A, INK_SOFT = 0xFF7A5F45, TITLE = 0xFF7A2E2A, LINK = 0xFF2F4E6E,
            RULE = 0xFFC8B49A, FRAME_BG = 0xFFE3D3BE;
    static final int LEFT_X = 47, RIGHT_X = 172, PAGE_W = 104, BOTTOM = 158;
    /** ガイドの本文の幅。右のページの右端は付箋が紙に食い込む(深さ最大 9)ので、両ページともそれより内側で折り返す。 */
    static final int BODY_W = 96;

    /** 動きの長さ(ミリ秒)。確かめるときだけ {@link #SLOW} を上げて遅くする。 */
    static final int SLOW = 1;
    static final int OPEN_MS = 220 * SLOW, CLOSE_MS = 160 * SLOW, FLIP_MS = 320 * SLOW, RIFFLE_MS = 110 * SLOW, TUG_MS = 220 * SLOW;

    static ResourceLocation tex(String name) {
        return OvergrownVegetation.id("textures/gui/guidebook/" + name + ".png");
    }

    private final List<OpenGuidebookPayload.Mob> mobs;
    private final Set<Integer> collapsed = new HashSet<>();
    private final Map<ResourceLocation, Entity> models = new HashMap<>();

    private int left, top;
    /** 折り目の列(本の座標)。めくるときの軸。 */
    private int gutter = BOOK_W / 2;
    private float textScale = 1.0F;
    private int lineH = 9;
    private final int[] paperL = new int[BOOK_H], paperR = new int[BOOK_H];
    private final NativeImage[] tabImages = new NativeImage[TABS.length * 2];

    // 目次
    private final List<Line> lines = new ArrayList<>();
    private int pageCount = 1;

    /**
     * 見開き 1 つぶんの中身。Mob の章: entry が null なら目次の spread 番目の見開き、あればその Mob のページ。
     * ガイドの章: topic が -1 なら目次、0 以上ならその項目の spread 番目の見開き。
     */
    record View(int chapter, @Nullable OpenGuidebookPayload.Mob entry, int topic, int spread) {
        static View index(int chapter) {
            return new View(chapter, null, -1, 0);
        }

        static View mobIndex(int spread) {
            return new View(MOB, null, -1, spread);
        }

        static View mob(OpenGuidebookPayload.Mob entry, int spread) {
            return new View(MOB, entry, -1, spread);
        }

        static View topic(int chapter, int topic, int spread) {
            return new View(chapter, null, topic, spread);
        }

        /** 目次でない(左下の矢印・Backspace で目次へ戻れる)。 */
        boolean inner() {
            return entry != null || topic >= 0;
        }
    }

    private View view = View.index(lastChapter);

    // 文の章のページ割り(init で作る)
    /** 1 行。rule なら点線(text は空)。x・y は本の座標。 */
    record TextLine(String text, int x, int y, int color, float scale, boolean rule) {
    }

    /** 文の章の目次の 1 行(折り返した名前)。page は目次の何ページ目か(0 = 最初の左)。topic が -1 なら区分けの見出し。 */
    record TopicRow(int topic, List<String> lines, int page, int y, int h) {
        boolean header() {
            return topic < 0;
        }
    }

    /** アイテムの章の 1 項目。 */
    record ItemTopic(String id, String category, List<net.minecraft.world.item.Item> items) {
    }

    private final List<ItemTopic> itemTopics = new ArrayList<>();
    /** 章 → 目次のページ数。 */
    private final Map<Integer, Integer> indexPages = new HashMap<>();

    /** 章 → 項目ごとのページ。 */
    private final Map<Integer, List<List<List<TextLine>>>> topicPages = new HashMap<>();
    /** 章 → 目次の行。 */
    private final Map<Integer, List<TopicRow>> topicRows = new HashMap<>();
    private final Map<ResourceLocation, Boolean> textureExists = new HashMap<>();

    /** めくっている最中。dir は +1 = 先へ(右のページが左へ)、-1 = 戻る。sheets 枚を続けてめくる。 */
    record Flip(View from, View to, int dir, long start, int sheetMs, int sheets) {
    }

    @Nullable
    private Flip flip;
    private int flipSoundSheet = -1;

    private long openStart = -1;
    private long closeStart = -1;
    private final float[] tabPull = new float[TABS.length];
    private final long[] tabTug = new long[TABS.length];
    private long lastFrame = -1;
    private ItemStack hoverStack = ItemStack.EMPTY;

    /** 目次の 1 行。group は見出しなら番号、mob は Mob の行なら中身。 */
    record Line(int group, @Nullable OpenGuidebookPayload.Mob mob, int found, int total, int page, int y) {
        boolean header() {
            return mob == null;
        }
    }

    public GuidebookScreen(List<OpenGuidebookPayload.Mob> mobs) {
        super(Component.translatable("item.overgrown_vegetation.guidebook"));
        this.mobs = mobs;
        // グループは最初は全部畳んでおく(2026-10-15 ユーザー指定)。
        for (OpenGuidebookPayload.Mob m : mobs) {
            collapsed.add(m.group());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - BOOK_W) / 2;
        top = (height - BOOK_H) / 2;
        textScale = minecraft.getWindow().getGuiScale() >= 3 ? 0.75F : 1.0F;
        lineH = Math.round(9 * textScale);
        loadImages();
        layoutIndex();
        if (itemTopics.isEmpty()) loadItemTopics();
        if (!ready(view.chapter())) view = View.index(GUIDE); // 前に閉じた章が中身を失っていたら
        layoutTextChapters();
        if (openStart < 0) {
            // init は窓の大きさを変えたときにも呼ばれるので、開く動きと音は最初の 1 回だけ。
            openStart = Util.getMillis();
            playSound(SoundEvents.BOOK_PUT, 1.0F);
        }
    }

    private void loadImages() {
        if (tabImages[0] != null) {
            return;
        }
        try (NativeImage mask = read(tex("book_paper_mask"))) {
            double sum = 0;
            int rows = 0;
            for (int y = 0; y < BOOK_H; y++) {
                paperL[y] = -1;
                paperR[y] = -1;
                if (mask == null || y >= mask.getHeight()) continue;
                for (int x = 0; x < mask.getWidth(); x++) {
                    int p = mask.getPixelRGBA(x, y);
                    boolean paper = (p >>> 24) > 0 && (p & 0xFF) > 128;
                    if (paper) {
                        if (paperL[y] < 0) paperL[y] = x;
                        paperR[y] = x;
                    }
                }
                if (paperL[y] >= 0 && paperR[y] - paperL[y] > BOOK_W / 2) {
                    sum += (paperL[y] + paperR[y]) / 2.0;
                    rows++;
                }
            }
            if (rows > 0) gutter = (int) Math.round(sum / rows);
        }
        for (int i = 0; i < TABS.length; i++) {
            tabImages[i * 2] = read(tex("tab_" + TABS[i]));
            tabImages[i * 2 + 1] = read(tex("tab_" + TABS[i] + "_r"));
        }
    }

    @Nullable
    private NativeImage read(ResourceLocation loc) {
        try (InputStream in = minecraft.getResourceManager().open(loc)) {
            return NativeImage.read(in);
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("ガイドブックの絵が読めない: {}", loc, e);
            return null;
        }
    }

    @Override
    public void removed() {
        for (int i = 0; i < tabImages.length; i++) {
            if (tabImages[i] != null) {
                tabImages[i].close();
                tabImages[i] = null;
            }
        }
        super.removed();
    }

    /** 開ける章(Mob と、項目が 1 つ以上ある文の章)。アイテムの章は 10-24 に中身を消したので、items.json に項目が戻るまで「準備中」。 */
    boolean ready(int chapter) {
        return chapter == MOB || (isText(chapter) && topicsOf(chapter).length > 0);
    }

    /** 文の章(目次 + 項目のページ)。 */
    static boolean isText(int chapter) {
        return chapter == GUIDE || chapter == ITEM || chapter == EFFECT || chapter == BIOME;
    }

    String[] topicsOf(int chapter) {
        return switch (chapter) {
            case GUIDE -> GUIDE_TOPICS;
            case ITEM -> itemTopics.stream().map(ItemTopic::id).toArray(String[]::new);
            case EFFECT -> EFFECT_TOPICS;
            case BIOME -> BIOME_TOPICS;
            default -> new String[0];
        };
    }

    /** 最初の右のページの上に挿絵(アイテムの章はそのアイテムの絵)があるか。 */
    static boolean illustrated(int chapter) {
        return chapter == GUIDE || chapter == ITEM || chapter == BIOME;
    }

    /** 名前の前にアイコンを置く章。 */
    static boolean iconed(int chapter) {
        return chapter == ITEM || chapter == EFFECT;
    }

    /**
     * 項目の名前のキー。エフェクトは効果そのものの名前。アイテムは {@code .title} が言語ファイルにあればそれ、
     * 無ければ最初のアイテムの名前。
     */
    String titleKey(int chapter, int topic) {
        if (chapter == EFFECT) return "effect.overgrown_vegetation." + EFFECT_TOPICS[topic];
        String key = "guidebook.overgrown_vegetation." + TABS[chapter] + "." + topicsOf(chapter)[topic] + ".title";
        if (chapter == ITEM && !I18n.exists(key)) {
            return itemTopics.get(topic).items().get(0).getDescriptionId();
        }
        return key;
    }

    String bodyKey(int chapter, int topic) {
        return "guidebook.overgrown_vegetation." + TABS[chapter] + "." + topicsOf(chapter)[topic] + ".body";
    }

    /**
     * アイテムの章の項目を {@code assets/overgrown_vegetation/guidebook/items.json} から読む。
     * 形: {@code {"categories":[{"id":"…","topics":[{"id":"…","items":["research_table", "minecraft:book", …]}]}]}}。
     * 名前空間の無い id はこの MOD。登録の無いアイテムは飛ばし、1 つも残らない項目は載せない。
     */
    private void loadItemTopics() {
        itemTopics.clear();
        ResourceLocation loc = OvergrownVegetation.id("guidebook/items.json");
        try (java.io.Reader reader = minecraft.getResourceManager().openAsReader(loc)) {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            for (com.google.gson.JsonElement c : root.getAsJsonArray("categories")) {
                String category = c.getAsJsonObject().get("id").getAsString();
                for (com.google.gson.JsonElement t : c.getAsJsonObject().getAsJsonArray("topics")) {
                    com.google.gson.JsonObject topic = t.getAsJsonObject();
                    List<net.minecraft.world.item.Item> items = new ArrayList<>();
                    for (com.google.gson.JsonElement i : topic.getAsJsonArray("items")) {
                        String s = i.getAsString();
                        ResourceLocation id = s.contains(":") ? ResourceLocation.parse(s) : OvergrownVegetation.id(s);
                        BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
                    }
                    if (!items.isEmpty()) itemTopics.add(new ItemTopic(topic.get("id").getAsString(), category, items));
                }
            }
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("ガイドブックのアイテムの章が読めない: {}", loc, e);
        }
    }

    /** 項目の名前の前のアイコン({@code size} 画素の正方形、本の座標)。 */
    private void drawTopicIcon(GuiGraphics g, int chapter, int topic, int x, int y, int size) {
        if (chapter == EFFECT) {
            var sprite = effectIcon(topic);
            if (sprite != null) g.blit(left + x, top + y, 0, size, size, sprite);
        } else if (chapter == ITEM) {
            g.pose().pushPose();
            g.pose().translate(left + x, top + y, 0);
            g.pose().scale(size / 16.0F, size / 16.0F, 1);
            g.renderItem(new ItemStack(itemTopics.get(topic).items().get(0)), 0, 0);
            g.pose().popPose();
        }
    }

    /** エフェクトのアイコン(無ければ null)。 */
    @Nullable
    private net.minecraft.client.renderer.texture.TextureAtlasSprite effectIcon(int topic) {
        return BuiltInRegistries.MOB_EFFECT.getHolder(OvergrownVegetation.id(EFFECT_TOPICS[topic]))
                .map(h -> minecraft.getMobEffectTextures().get(h)).orElse(null);
    }

    /** 左下の矢印が目次へ戻る見開き(Mob のページ・項目の最初の見開き)。 */
    private static boolean backGoesToIndex(View v) {
        return v.entry() != null || (v.topic() >= 0 && v.spread() == 0);
    }

    /** 付箋をどちらの束に置くかの章。めくりの半ばで行き先の章へ移る。 */
    private int tabChapter(long now) {
        Flip f = flip;
        if (f != null && now - f.start() < (long) f.sheets() * f.sheetMs() / 2) {
            return f.from().chapter();
        }
        return view.chapter();
    }

    private void playSound(net.minecraft.sounds.SoundEvent sound, float pitch) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    // ------------------------------------------------------------------ 目次のページ割り

    private void layoutIndex() {
        lines.clear();
        Map<Integer, List<OpenGuidebookPayload.Mob>> byGroup = new java.util.LinkedHashMap<>();
        for (OpenGuidebookPayload.Mob m : mobs) {
            byGroup.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(m);
        }
        int page = 0;
        int y = 37; // 1 ページ目は章の名前の下から
        int headerH = lineH + 4;
        for (Map.Entry<Integer, List<OpenGuidebookPayload.Mob>> e : byGroup.entrySet()) {
            int found = (int) e.getValue().stream().filter(OpenGuidebookPayload.Mob::discovered).count();
            if (y + headerH > BOTTOM) {
                page++;
                y = 20;
            }
            lines.add(new Line(e.getKey(), null, found, e.getValue().size(), page, y));
            y += headerH;
            if (collapsed.contains(e.getKey())) continue;
            for (OpenGuidebookPayload.Mob m : e.getValue()) {
                if (y + lineH > BOTTOM) {
                    page++;
                    y = 20;
                }
                lines.add(new Line(e.getKey(), m, 0, 0, page, y));
                y += lineH;
            }
        }
        pageCount = page + 1;
        int maxSpread = (pageCount - 1) / 2;
        if (view.chapter() == MOB && view.entry() == null && view.spread() > maxSpread) {
            view = View.mobIndex(maxSpread);
        }
    }

    // ------------------------------------------------------------------ 文の章のページ割り

    /** アイコンの幅(エフェクトの名前の前)。 */
    static final int ICON = 12;

    private void layoutTextChapters() {
        for (int chapter = 0; chapter < TABS.length; chapter++) {
            if (!isText(chapter)) continue;
            String[] topics = topicsOf(chapter);
            // 目次: 左のページの章の名前の下から、入りきらなければ次のページ・次の見開きへ。アイテムは区分けの見出しを挟む。
            List<TopicRow> rows = new ArrayList<>();
            int page = 0, y = 37;
            int lead = rowLead(chapter);
            int gap = chapter == ITEM ? 2 : 4;
            String category = null;
            for (int t = 0; t < topics.length; t++) {
                if (chapter == ITEM && !itemTopics.get(t).category().equals(category)) {
                    category = itemTopics.get(t).category();
                    int hh = lineH + 4;
                    // 見出しだけがページの最後に残らないように、続く 1 行ぶんの余裕も見る
                    if (y + hh + lineH > BOTTOM) {
                        page++;
                        y = 20;
                    } else if (y > 37 || page > 0) {
                        y += 2;
                    }
                    rows.add(new TopicRow(-1, List.of(I18n.get("guidebook.overgrown_vegetation.item.category." + category)), page, y, hh));
                    y += hh;
                }
                List<String> name = wrap(I18n.get(titleKey(chapter, t)), (PAGE_W - lead) / textScale);
                int h = Math.max(name.size() * lineH, iconed(chapter) ? Math.round(10 * textScale) : 0);
                if (y + h > BOTTOM) {
                    page++;
                    y = 20;
                }
                rows.add(new TopicRow(t, name, page, y, h));
                y += h + gap;
            }
            topicRows.put(chapter, rows);
            indexPages.put(chapter, page + 1);
            // 項目
            List<List<List<TextLine>>> all = new ArrayList<>();
            for (int t = 0; t < topics.length; t++) {
                all.add(paginate(chapter, t));
            }
            topicPages.put(chapter, all);
        }
        if (isText(view.chapter())) {
            if (view.topic() >= topicsOf(view.chapter()).length) {
                view = View.index(view.chapter());
            } else if (view.topic() >= 0) {
                int maxSpread = (pagesOf(view).size() - 1) / 2;
                if (view.spread() > maxSpread) view = View.topic(view.chapter(), view.topic(), maxSpread);
            } else {
                int maxSpread = (indexPages.getOrDefault(view.chapter(), 1) - 1) / 2;
                if (view.spread() > maxSpread) view = new View(view.chapter(), null, -1, maxSpread);
            }
        }
    }

    /** 項目 {@code topic} の行が載っている目次の見開き(項目から目次へ戻るとき)。 */
    private View indexOf(int chapter, int topic) {
        for (TopicRow r : topicRows.getOrDefault(chapter, List.of())) {
            if (r.topic() == topic) return new View(chapter, null, -1, r.page() / 2);
        }
        return View.index(chapter);
    }

    private List<List<TextLine>> pagesOf(View v) {
        return topicPages.get(v.chapter()).get(v.topic());
    }

    /** 目次の行の頭の幅(番号かアイコン)。 */
    private int rowLead(int chapter) {
        return iconed(chapter) ? Math.round(10 * textScale) + 3 : Math.round(font.width("00. ") * textScale);
    }

    /** 項目の名前と本文をページに割る。0 = 最初の左、1 = 最初の右(挿絵のある章は上に挿絵)、2 = 次の見開きの左 … */
    private List<List<TextLine>> paginate(int chapter, int topic) {
        boolean image = illustrated(chapter);
        List<List<TextLine>> pages = new ArrayList<>();
        pages.add(new ArrayList<>());
        pages.add(new ArrayList<>());
        int[] cursor = {0, 18}; // ページ, y
        // 名前は 1 行に収まるなら縮めて 1 行に(本文の字より小さくはしない)、収まらなければ折り返す。エフェクトは前にアイコン。
        int titleX = LEFT_X + (iconed(chapter) ? ICON + 3 : 0);
        int titleW = BODY_W - (titleX - LEFT_X);
        String title = I18n.get(titleKey(chapter, topic));
        float ts = Math.max(textScale, Math.min(titleScale(), titleW / (float) Math.max(1, font.width(title))));
        int titleH = Math.round(9 * ts) + 1;
        List<String> titleLines = font.width(title) * ts <= titleW + 0.01F ? List.of(title) : wrap(title, titleW / ts);
        for (String s : titleLines) {
            pages.get(0).add(new TextLine(s, titleX, cursor[1], TITLE, ts, false));
            cursor[1] += titleH;
        }
        if (iconed(chapter)) cursor[1] = Math.max(cursor[1], 18 + ICON);
        cursor[1] += 2;
        pages.get(0).add(new TextLine("", LEFT_X, cursor[1], RULE, 1.0F, true));
        cursor[1] += 5;

        String key = bodyKey(chapter, topic);
        String body = I18n.exists(key) ? I18n.get(key) : "";
        for (String para : body.split("\n", -1)) {
            if (para.isEmpty()) {
                if (cursor[1] > pageTop(cursor[0], image)) cursor[1] += lineH / 2;
                continue;
            }
            int color = INK;
            String prefix = "";
            String text = para;
            if (para.startsWith("■")) {
                text = para.substring(1).trim();
                color = TITLE;
                // 見出しだけがページの最後に残らないように、続く 1 行ぶんの余裕が無ければ次のページから。
                if (cursor[1] + lineH * 2 > BOTTOM) nextPage(pages, cursor, image);
                else if (cursor[1] > pageTop(cursor[0], image)) cursor[1] += 2;
            } else if (para.startsWith("・")) {
                prefix = "・";
                text = para.substring(1);
            } else if (para.startsWith("- ")) {
                prefix = "- ";
                text = para.substring(2);
            }
            int indent = prefix.isEmpty() ? 0 : Math.round(font.width(prefix) * textScale);
            List<String> wrapped = wrap(text, (BODY_W - indent) / textScale);
            // 箇条書きの 1 項目はページをまたがせない(数行なので、入りきらなければ次のページから)。
            if (!prefix.isEmpty() && cursor[1] + lineH * wrapped.size() > BOTTOM) nextPage(pages, cursor, image);
            for (int i = 0; i < wrapped.size(); i++) {
                if (cursor[1] + lineH > BOTTOM) nextPage(pages, cursor, image);
                int x = pageX(cursor[0]);
                List<TextLine> page = pages.get(cursor[0]);
                if (i == 0 && !prefix.isEmpty()) {
                    page.add(new TextLine(prefix, x, cursor[1], INK_SOFT, textScale, false));
                }
                page.add(new TextLine(wrapped.get(i), x + indent, cursor[1], color, textScale, false));
                cursor[1] += lineH;
            }
        }
        return pages;
    }

    private void nextPage(List<List<TextLine>> pages, int[] cursor, boolean image) {
        cursor[0]++;
        while (pages.size() <= cursor[0]) pages.add(new ArrayList<>());
        cursor[1] = pageTop(cursor[0], image);
    }

    /** 項目のページの書き始めの高さ(最初の右のページは挿絵の下から)。 */
    private static int pageTop(int page, boolean image) {
        return page == 1 && image ? 20 + IMG_FH + 6 : 20;
    }

    /**
     * {@code maxW}(字の単位)で折り返す。日本語を含む文は 1 字ずつ(英数字の続きは切らない・{@link #NO_LINE_START} は
     * 前の行にぶら下げる)、含まない文はバニラの折り返し(空白で切る)。
     */
    private List<String> wrap(String s, float maxW) {
        List<String> out = new ArrayList<>();
        if (s.codePoints().noneMatch(c -> c >= 0x3000)) {
            for (net.minecraft.network.chat.FormattedText t : font.getSplitter().splitLines(s, (int) maxW, net.minecraft.network.chat.Style.EMPTY)) {
                out.add(t.getString());
            }
            if (out.isEmpty()) out.add("");
            return out;
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80 && (Character.isLetterOrDigit(cp) || cp == '.' || cp == '%')) {
                run.appendCodePoint(cp);
                continue;
            }
            if (run.length() > 0) {
                tokens.add(run.toString());
                run.setLength(0);
            }
            tokens.add(new String(Character.toChars(cp)));
        }
        if (run.length() > 0) tokens.add(run.toString());

        StringBuilder line = new StringBuilder();
        int w = 0;
        for (String tok : tokens) {
            int tw = font.width(tok);
            if (w + tw > maxW && line.length() > 0 && NO_LINE_START.indexOf(tok.charAt(0)) < 0) {
                // 開き括弧は行末に残さず次の行へ送る。
                String carry = "";
                char last = line.charAt(line.length() - 1);
                if (line.length() > 1 && NO_LINE_END.indexOf(last) >= 0) {
                    carry = String.valueOf(last);
                    line.setLength(line.length() - 1);
                }
                out.add(line.toString());
                line.setLength(0);
                line.append(carry);
                w = font.width(carry);
                if (tok.equals(" ")) continue;
            }
            line.append(tok);
            w += tw;
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out;
    }

    private int pageX(int page) {
        return page % 2 == 0 ? LEFT_X : RIGHT_X;
    }

    // ------------------------------------------------------------------ 動きの進み

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private boolean opening(long now) {
        return now - openStart < OPEN_MS;
    }

    private boolean busy(long now) {
        return opening(now) || closeStart >= 0 || flip != null;
    }

    /** ページをめくり始める。 */
    private void startFlip(View to, int dir, int sheets) {
        long now = Util.getMillis();
        flip = new Flip(view, to, dir, now, sheets > 1 ? RIFFLE_MS : FLIP_MS, Math.max(1, sheets));
        flipSoundSheet = -1;
        view = to;
    }

    // ------------------------------------------------------------------ 描く

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        long now = Util.getMillis();
        float dt = lastFrame < 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        hoverStack = ItemStack.EMPTY;

        // 開く・閉じる: 下から持ち上がる / 沈む + 少し縮む。
        float lift, scale;
        if (closeStart >= 0) {
            float t = clamp01((now - closeStart) / (float) CLOSE_MS);
            if (t >= 1.0F) {
                super.onClose();
                return;
            }
            float e = t * t;
            lift = 24.0F * e;
            scale = 1.0F - 0.06F * e;
        } else {
            float t = clamp01((now - openStart) / (float) OPEN_MS);
            float e = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            lift = 24.0F * (1.0F - e);
            scale = 0.94F + 0.06F * e;
        }
        boolean animating = lift > 0.01F;
        boolean idle = !busy(now);
        g.pose().pushPose();
        g.pose().translate(left + BOOK_W / 2.0F, top + BOOK_H / 2.0F + lift, 0);
        g.pose().scale(scale, scale, 1);
        g.pose().translate(-(left + BOOK_W / 2.0F), -(top + BOOK_H / 2.0F), 0);

        g.blit(tex("book"), left, top, 0, 0, BOOK_W, BOOK_H, BOOK_W, BOOK_H);
        int hoverTab = drawTabs(g, mouseX, mouseY, idle, now, dt);
        if (flip != null) {
            drawFlip(g, now, !animating);
        } else {
            drawPage(g, view, 0, mouseX, mouseY, idle, !animating);
            drawPage(g, view, 1, mouseX, mouseY, idle, !animating);
        }
        g.pose().popPose();

        if (!idle) {
            return;
        }
        if (hoverTab >= 0) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.translatable("guidebook.overgrown_vegetation.tab." + TABS[hoverTab]));
            if (!ready(hoverTab)) {
                tip.add(Component.translatable("guidebook.overgrown_vegetation.not_ready").withStyle(ChatFormatting.GRAY));
            }
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        } else if (!hoverStack.isEmpty()) {
            g.renderTooltip(font, hoverStack, mouseX, mouseY);
        } else if (backGoesToIndex(view) && inArrow(mouseX, mouseY, LEFT_X, 165)) {
            g.renderTooltip(font, Component.translatable("guidebook.overgrown_vegetation.back"), mouseX, mouseY);
        }
    }

    /**
     * めくっている最中を描く。下のページ(動かない左右)を描いてから、めくれている 1 枚を折り目を軸に横へ縮めて重ねる。
     * 何枚も続けてめくる(付箋で章の頭へ飛ぶ)ときは、途中のページは中身の無い紙。
     */
    private void drawFlip(GuiGraphics g, long now, boolean models) {
        Flip f = flip;
        float k = (now - f.start()) / (float) f.sheetMs();
        int sheet = (int) Math.floor(k);
        if (sheet >= f.sheets()) {
            flip = null;
            drawPage(g, view, 0, -1, -1, false, models);
            drawPage(g, view, 1, -1, -1, false, models);
            return;
        }
        if (sheet != flipSoundSheet) {
            flipSoundSheet = sheet;
            playSound(SoundEvents.BOOK_PAGE_TURN, 0.9F + 0.2F * (float) Math.random());
        }
        float t = k - sheet;
        boolean first = sheet == 0, last = sheet == f.sheets() - 1;
        boolean forward = f.dir() > 0;
        // 下のページ: 先へめくるなら 左 = 今の左(最初の 1 枚だけ)・右 = 次の右(最後の 1 枚だけ)。戻るなら左右が逆。
        View under0 = forward ? (first ? f.from() : null) : (last ? f.to() : null);
        View under1 = forward ? (last ? f.to() : null) : (first ? f.from() : null);
        if (under0 != null) drawPage(g, under0, 0, -1, -1, false, models);
        if (under1 != null) drawPage(g, under1, 1, -1, -1, false, models);

        // めくれている 1 枚: 前半は出ていく側の面、後半は入ってくる側の面。
        boolean firstHalf = t < 0.5F;
        float sx = Math.abs((float) Math.cos(Math.PI * t));
        int side = forward ? (firstHalf ? 1 : 0) : (firstHalf ? 0 : 1);
        View face = firstHalf ? (first ? f.from() : null) : (last ? f.to() : null);
        float shade = 1.0F - 0.35F * (float) Math.sin(Math.PI * t);

        // 下のページへ落ちる影(めくれている紙の端の外側)。
        int pageEdge = side == 1 ? paperRight() : paperLeft();
        int edgeX = Math.round(gutter + (pageEdge - gutter) * sx);
        shadow(g, edgeX, side == 1 ? 1 : -1, sx);

        float gx = left + gutter;
        g.pose().pushPose();
        // 下のページの模型(奥行き +50 前後)とアイテムの絵(+150)より手前に描く。でないとめくれる紙を突き抜けて見える。
        g.pose().translate(gx, 0, 400);
        g.pose().scale(Math.max(0.001F, sx), 1, 1);
        g.pose().translate(-gx, 0, 0);
        RenderSystem.setShaderColor(shade, shade, shade, 1.0F);
        g.blit(tex(side == 1 ? "page_right" : "page_left"), left, top, 0, 0, BOOK_W, BOOK_H, BOOK_W, BOOK_H);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        if (face != null) {
            drawPage(g, face, side, -1, -1, false, false);
        }
        g.pose().popPose();
    }

    private int paperLeft() {
        int m = BOOK_W;
        for (int x : paperL) if (x >= 0) m = Math.min(m, x);
        return m;
    }

    private int paperRight() {
        int m = 0;
        for (int x : paperR) m = Math.max(m, x);
        return m;
    }

    /** めくれている紙の端 {@code edgeX}(本の座標)から外側({@code dir})へ、薄くなる影を数本。 */
    private void shadow(GuiGraphics g, int edgeX, int dir, float sx) {
        int strength = Math.round(0x50 * (1.0F - sx));
        if (strength <= 0) return;
        for (int i = 0; i < 6; i++) {
            int a = strength * (6 - i) / 6;
            int x = edgeX + dir * i;
            for (int y = 0; y < BOOK_H; y++) {
                int l = paperL[y], r = paperR[y];
                if (l < 0 || x < l || x > r) continue;
                if ((dir > 0 && x <= gutter) || (dir < 0 && x >= gutter)) continue;
                g.fill(left + x, top + y, left + x + 1, top + y + 1, a << 24);
            }
        }
    }

    /** 付箋を描き、カーソルの当たっている付箋の番号を返す(無ければ -1)。 */
    private int drawTabs(GuiGraphics g, int mouseX, int mouseY, boolean idle, long now, float dt) {
        int hover = idle ? tabAt(mouseX, mouseY) : -1;
        for (int i = 0; i < TABS.length; i++) {
            // カーソルで 2 画素出てくる(なめらかに)。押したときは少し引っ張られて戻る。
            float target = i == hover ? 2.0F : 0.0F;
            tabPull[i] += (target - tabPull[i]) * Math.min(1.0F, dt * 14.0F);
            float tug = 0.0F;
            if (tabTug[i] > 0 && now - tabTug[i] < TUG_MS) {
                tug = 3.0F * (float) Math.sin(Math.PI * (now - tabTug[i]) / TUG_MS);
            }
            int extra = Math.round(tabPull[i] + tug);
            TabPlace p = place(i, extra);
            if (p == null) continue;
            NativeImage img = tabImages[i * 2 + (p.right ? 1 : 0)];
            ResourceLocation t = tex("tab_" + TABS[i] + (p.right ? "_r" : ""));
            for (int r = 0; r < p.h; r++) {
                int dy = p.top + r;
                if (dy < 0 || dy >= BOOK_H || (p.right ? paperR[dy] : paperL[dy]) < 0) continue;
                int cut = p.right ? paperR[dy] - p.depth : paperL[dy] + p.depth;
                int c0 = p.right ? Math.max(0, cut - p.ox) : 0;
                int c1 = p.right ? p.w - 1 : Math.min(p.w - 1, cut - p.ox);
                if (c1 < c0) continue;
                g.blit(t, left + p.ox + c0, top + dy, c0, r, c1 - c0 + 1, 1, p.w, p.h);
                // 切り口: 上のページの影(付箋の絵がある所だけ)
                int cc = cut - p.ox;
                if (img != null && cc >= 0 && cc < p.w && (img.getPixelRGBA(cc, r) >>> 24) > 0) {
                    g.fill(left + cut, top + dy, left + cut + 1, top + dy + 1, 0x73000000);
                    int nb = p.right ? cc + 1 : cc - 1;
                    if (p.depth > 2 && nb >= 0 && nb < p.w && (img.getPixelRGBA(nb, r) >>> 24) > 0) {
                        int nx = p.right ? cut + 1 : cut - 1;
                        g.fill(left + nx, top + dy, left + nx + 1, top + dy + 1, 0x33000000);
                    }
                }
            }
        }
        return hover;
    }

    record TabPlace(boolean right, int ox, int top, int w, int h, int depth, int edge) {
    }

    /** 付箋 {@code i} の置き場所。{@code extra} だけ余分に外へ出す。 */
    @Nullable
    private TabPlace place(int i, int extra) {
        NativeImage img = tabImages[i * 2];
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        int current = tabChapter(Util.getMillis());
        boolean right = i > current;
        int depth = i == current ? 1 : DEPTH[i];
        int out = OUT[i] + (i == current ? 3 : 0) + extra;
        int center = 28 + i * 21 + SHIFT[i];
        int topY = center - (h - 1) / 2;
        double sum = 0;
        int n = 0;
        for (int y = topY; y < topY + h; y++) {
            if (y < 0 || y >= BOOK_H) continue;
            int e = right ? paperR[y] : paperL[y];
            if (e >= 0) {
                sum += e;
                n++;
            }
        }
        if (n == 0) return null;
        int edge = (int) Math.round(sum / n);
        int ox = right ? edge + out - (w - 1) : edge - out;
        return new TabPlace(right, ox, topY, w, h, depth, edge);
    }

    /** カーソルの当たっている付箋(当たり判定は出ていないときの形で。カーソルで出てきた分で揺れないように)。 */
    private int tabAt(double mouseX, double mouseY) {
        double bx = mouseX - left, by = mouseY - top;
        for (int i = 0; i < TABS.length; i++) {
            TabPlace p = place(i, 0);
            if (p == null) continue;
            int x0 = p.right ? p.edge - p.depth : p.ox - 2, x1 = p.right ? p.ox + p.w + 1 : p.edge + p.depth;
            if (by >= p.top && by < p.top + p.h && bx >= x0 && bx <= x1) return i;
        }
        return -1;
    }

    /**
     * 見開き {@code v} の片側({@code side} 0 = 左・1 = 右)の中身を描く(紙は本の絵か、めくれる紙が先に描いてある)。
     * {@code interactive} のときだけカーソルの下線・ドロップの名前を出す。{@code models} が false なら模型を描かない。
     */
    private void drawPage(GuiGraphics g, View v, int side, int mouseX, int mouseY, boolean interactive, boolean models) {
        if (isText(v.chapter())) {
            if (v.topic() < 0) {
                drawTopicIndex(g, v.chapter(), v.spread(), side, mouseX, mouseY, interactive);
            } else {
                drawTopicPage(g, v, side, mouseX, mouseY, interactive);
            }
        } else if (v.entry() == null) {
            drawIndexPage(g, v.spread() * 2 + side, side, mouseX, mouseY, interactive);
        } else if (side == 0) {
            drawEntryLeft(g, v.entry());
        } else {
            drawEntryRight(g, v.entry(), mouseX, mouseY, interactive, models);
        }
    }

    private void drawTopicIndex(GuiGraphics g, int chapter, int spread, int side, int mouseX, int mouseY, boolean interactive) {
        int page = spread * 2 + side;
        if (page == 0) {
            text(g, Component.translatable("guidebook.overgrown_vegetation.tab." + TABS[chapter]), LEFT_X, 18, TITLE, titleScale());
            dotted(g, LEFT_X, 32, PAGE_W);
        }
        TopicRow hover = interactive ? topicRowAt(mouseX, mouseY) : null;
        int lead = rowLead(chapter);
        for (TopicRow r : topicRows.getOrDefault(chapter, List.of())) {
            if (r.page() != page) continue;
            int x = pageX(r.page());
            if (r.header()) {
                text(g, Component.literal(r.lines().get(0)), x, r.y(), TITLE, textScale);
                g.fill(left + x, top + r.y() + lineH + 1, left + x + PAGE_W, top + r.y() + lineH + 2, RULE);
                continue;
            }
            if (iconed(chapter)) {
                drawTopicIcon(g, chapter, r.topic(), x, r.y() - 1, Math.round(10 * textScale));
            } else {
                text(g, Component.literal((r.topic() + 1) + "."), x, r.y(), INK_SOFT, textScale);
            }
            for (int i = 0; i < r.lines().size(); i++) {
                String s = r.lines().get(i);
                int ly = r.y() + i * lineH;
                text(g, Component.literal(s), x + lead, ly, LINK, textScale);
                if (r == hover) {
                    int w = Math.round(font.width(s) * textScale);
                    g.fill(left + x + lead, top + ly + lineH, left + x + lead + w, top + ly + lineH + 1, LINK);
                }
            }
        }
        if (side == 0 && spread > 0) arrow(g, LEFT_X, 165, false);
        if (side == 1 && (spread + 1) * 2 < indexPages.getOrDefault(chapter, 1)) arrow(g, 266, 165, true);
    }

    @Nullable
    private TopicRow topicRowAt(double mouseX, double mouseY) {
        if (!isText(view.chapter()) || view.topic() >= 0) return null;
        double bx = mouseX - left, by = mouseY - top;
        for (TopicRow r : topicRows.getOrDefault(view.chapter(), List.of())) {
            if (r.header() || r.page() / 2 != view.spread()) continue;
            int x = pageX(r.page());
            if (bx >= x && bx < x + PAGE_W && by >= r.y() - 1 && by < r.y() + r.h() + 1) return r;
        }
        return null;
    }

    private void drawTopicPage(GuiGraphics g, View v, int side, int mouseX, int mouseY, boolean interactive) {
        List<List<TextLine>> pages = pagesOf(v);
        int page = v.spread() * 2 + side;
        if (page == 1 && v.chapter() == ITEM) {
            drawItemFrame(g, v.topic(), mouseX, mouseY, interactive);
        } else if (page == 1 && illustrated(v.chapter())) {
            drawIllustration(g, v.chapter(), v.topic());
        }
        if (page == 0 && iconed(v.chapter())) drawTopicIcon(g, v.chapter(), v.topic(), LEFT_X, 17, ICON);
        if (pages != null && page < pages.size()) {
            for (TextLine l : pages.get(page)) {
                if (l.rule()) {
                    dotted(g, l.x(), l.y(), PAGE_W);
                } else {
                    text(g, Component.literal(l.text()), l.x(), l.y(), l.color(), l.scale());
                }
            }
        }
        if (side == 0) arrow(g, LEFT_X, 165, false);
        if (side == 1 && pages != null && (v.spread() + 1) * 2 < pages.size()) arrow(g, 266, 165, true);
    }

    /**
     * アイテムの章の挿絵の枠: その項目のアイテムを並べる(1 個なら 3 倍・4 個までなら 2 倍・それより多ければ等倍。
     * 入りきらない分は描かない)。カーソルを合わせると名前。
     */
    private void drawItemFrame(GuiGraphics g, int topic, int mouseX, int mouseY, boolean interactive) {
        int fx = RIGHT_X, fy = 20;
        g.fill(left + fx, top + fy, left + fx + IMG_FW, top + fy + IMG_FH, FRAME_BG);
        outline(g, fx, fy, IMG_FW, IMG_FH, INK);
        outline(g, fx + 2, fy + 2, IMG_FW - 4, IMG_FH - 4, INK_SOFT);
        List<net.minecraft.world.item.Item> items = itemTopics.get(topic).items();
        int n = items.size();
        int s = n == 1 ? 3 : n <= 4 ? 2 : 1;
        int cell = 16 * s + (s == 1 ? 1 : 2); // 等倍は 1 画素あけて 5 × 3 = 15 個まで
        int perRow = Math.max(1, (IMG_FW - 8) / cell);
        int rows = Math.min((IMG_FH - 8) / cell, (n + perRow - 1) / perRow);
        for (int row = 0; row < rows; row++) {
            int count = Math.min(perRow, n - row * perRow);
            int x0 = fx + (IMG_FW - count * cell) / 2 + 1;
            int y0 = fy + (IMG_FH - rows * cell) / 2 + 1 + row * cell;
            for (int i = 0; i < count; i++) {
                ItemStack stack = new ItemStack(items.get(row * perRow + i));
                int ix = x0 + i * cell, iy = y0;
                g.pose().pushPose();
                g.pose().translate(left + ix, top + iy, 0);
                g.pose().scale(s, s, 1);
                g.renderItem(stack, 0, 0);
                g.pose().popPose();
                if (interactive && mouseX >= left + ix && mouseX < left + ix + 16 * s && mouseY >= top + iy && mouseY < top + iy + 16 * s) {
                    hoverStack = stack;
                }
            }
        }
    }

    /** 項目の挿絵(最初の右のページの上)。絵が無ければ枠だけ。 */
    private void drawIllustration(GuiGraphics g, int chapter, int topic) {
        int fx = RIGHT_X, fy = 20;
        g.fill(left + fx, top + fy, left + fx + IMG_FW, top + fy + IMG_FH, FRAME_BG);
        ResourceLocation img = tex(TABS[chapter] + "_" + topicsOf(chapter)[topic]);
        boolean exists = textureExists.computeIfAbsent(img, loc -> minecraft.getResourceManager().getResource(loc).isPresent());
        if (exists) {
            g.blit(img, left + fx + 3, top + fy + 3, IMG_FW - 6, IMG_FH - 6, 0, 0, IMG_TW, IMG_TH, IMG_TW, IMG_TH);
        }
        outline(g, fx, fy, IMG_FW, IMG_FH, INK);
        outline(g, fx + 2, fy + 2, IMG_FW - 4, IMG_FH - 4, INK_SOFT);
    }

    private void drawIndexPage(GuiGraphics g, int page, int side, int mouseX, int mouseY, boolean interactive) {
        int spread = page / 2;
        if (page == 0) {
            text(g, Component.translatable("guidebook.overgrown_vegetation.mob.title"), LEFT_X, 18, TITLE, titleScale());
            dotted(g, LEFT_X, 32, PAGE_W);
        }
        Line hover = interactive ? lineAt(mouseX, mouseY) : null;
        for (Line l : lines) {
            if (l.page != page) continue;
            int x = pageX(l.page);
            if (l.header()) {
                boolean open = !collapsed.contains(l.group);
                triangle(g, x, l.y, open, TITLE);
                Component label = Component.translatable("guidebook.overgrown_vegetation.group", l.group);
                text(g, label, x + 10, l.y, TITLE, textScale);
                int lw = Math.round(font.width(label) * textScale);
                text(g, Component.literal(l.found + "/" + l.total), x + 10 + lw + 8, l.y, INK_SOFT, textScale);
                g.fill(left + x, top + l.y + lineH + 1, left + x + PAGE_W, top + l.y + lineH + 2, RULE);
            } else {
                Component name = rowName(l.mob);
                int color = l.mob.discovered() ? LINK : INK_SOFT;
                text(g, name, x + 8, l.y, color, textScale);
                if (l == hover && l.mob.discovered()) {
                    int w = Math.round(font.width(name) * textScale);
                    g.fill(left + x + 8, top + l.y + lineH, left + x + 8 + w, top + l.y + lineH + 1, LINK);
                }
            }
        }
        if (side == 0 && spread > 0) arrow(g, LEFT_X, 165, false);
        if (side == 1 && (spread + 1) * 2 < pageCount) arrow(g, 266, 165, true);
    }

    private Component rowName(OpenGuidebookPayload.Mob m) {
        if (!m.discovered()) {
            return Component.translatable("guidebook.overgrown_vegetation.unknown");
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(m.id());
        return type.getDescription();
    }

    @Nullable
    private Line lineAt(double mouseX, double mouseY) {
        double bx = mouseX - left, by = mouseY - top;
        for (Line l : lines) {
            if (l.page / 2 != view.spread()) continue;
            int x = pageX(l.page);
            int h = l.header() ? lineH + 3 : lineH;
            if (bx >= x && bx < x + PAGE_W && by >= l.y - 1 && by < l.y + h) {
                return l;
            }
        }
        return null;
    }

    private void drawEntryLeft(GuiGraphics g, OpenGuidebookPayload.Mob m) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(m.id());
        text(g, type.getDescription(), LEFT_X, 18, TITLE, titleScale());
        text(g, Component.translatable("guidebook.overgrown_vegetation.group", m.group()), LEFT_X, 33, INK_SOFT, textScale);
        dotted(g, LEFT_X, 42, PAGE_W);
        String descKey = "guidebook.overgrown_vegetation.mob." + m.id().getNamespace() + "." + m.id().getPath();
        if (I18n.exists(descKey)) {
            List<FormattedCharSequence> body = font.split(Component.translatable(descKey), Math.round(PAGE_W / textScale));
            int y = 47;
            for (FormattedCharSequence line : body) {
                if (y + lineH > BOTTOM + 4) break;
                g.pose().pushPose();
                g.pose().translate(left + LEFT_X, top + y, 0);
                g.pose().scale(textScale, textScale, 1);
                g.drawString(font, line, 0, 0, INK, false);
                g.pose().popPose();
                y += lineH;
            }
        }
        arrow(g, LEFT_X, 165, false);
    }

    private void drawEntryRight(GuiGraphics g, OpenGuidebookPayload.Mob m, int mouseX, int mouseY, boolean interactive,
                                boolean models) {
        int fx = RIGHT_X, fy = 20, fw = 100, fh = 72;
        g.fill(left + fx, top + fy, left + fx + fw, top + fy + fh, FRAME_BG);
        outline(g, fx, fy, fw, fh, INK);
        outline(g, fx + 2, fy + 2, fw - 4, fh - 4, INK_SOFT);
        if (models) {
            renderModel(g, m, left + fx + 3, top + fy + 3, left + fx + fw - 3, top + fy + fh - 3);
        }
        dotted(g, RIGHT_X, 98, 100);

        String[] keys = {"health", "attack", "armor"};
        float[] values = {m.health(), m.attack(), m.armor()};
        int y = 103;
        for (int i = 0; i < keys.length; i++) {
            text(g, Component.translatable("guidebook.overgrown_vegetation.stat." + keys[i]), RIGHT_X + 2, y, INK_SOFT, textScale);
            text(g, Component.literal(number(values[i])), RIGHT_X + 42, y, INK, textScale);
            y += lineH + 1;
        }
        y += 3;
        text(g, Component.translatable("guidebook.overgrown_vegetation.stat.drops"), RIGHT_X + 2, y + 4, INK_SOFT, textScale);
        for (int i = 0; i < m.drops().size(); i++) {
            int ix = RIGHT_X + 42 + (i % 3) * 18, iy = y + (i / 3) * 18;
            if (iy + 16 > 186) break;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(m.drops().get(i)));
            g.renderItem(stack, left + ix, top + iy);
            if (interactive && mouseX >= left + ix && mouseX < left + ix + 16 && mouseY >= top + iy && mouseY < top + iy + 16) {
                hoverStack = stack;
            }
        }
    }

    private void renderModel(GuiGraphics g, OpenGuidebookPayload.Mob m, int x0, int y0, int x1, int y1) {
        Entity e = models.computeIfAbsent(m.id(), id -> {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            return minecraft.level == null ? null : type.create(minecraft.level);
        });
        if (!(e instanceof LivingEntity living)) {
            return;
        }
        // 大きさはスポーンエッグの絵と同じく mobs.json の eggFit(見かけの差し渡し)で合わせる。尾や脚が当たり判定から
        // はみ出す Mob(アルボラなど)は、当たり判定で合わせると枠からあふれる。回るので奥行きの分も見て少し小さめに。
        net.minecraft.world.entity.EntityDimensions dims = living.getType().getDimensions();
        float nativeScale = living.getScale();
        float bh = dims.height() * nativeScale;
        float hitbox = Math.max(dims.width() * nativeScale, bh);
        float extent = Math.max(0.05F, com.yayuy.overgrownvegetation.client.SpawnEggEntityItemRenderer.fitSizeOf(living.getType(), hitbox));
        float scale = Math.min(y1 - y0, x1 - x0) * 0.85F / extent;
        float angle = (System.currentTimeMillis() % 9000L) / 9000.0F * 360.0F; // 9 秒で 1 回り
        float bodyRot = living.yBodyRot, yRot = living.getYRot(), xRot = living.getXRot(), head = living.yHeadRot, headO = living.yHeadRotO;
        living.yBodyRot = angle;
        living.setYRot(angle);
        living.setXRot(0);
        living.yHeadRot = angle;
        living.yHeadRotO = angle;
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI).mul(new Quaternionf().rotateX((float) Math.toRadians(-12)));
        g.enableScissor(x0, y0, x1, y1);
        try {
            // バニラの renderEntityInInventoryFollowsAngle と同じく、Mob 自身の大きさ(getScale)は描く側が掛けるので割っておく。
            InventoryScreen.renderEntityInInventory(g, (x0 + x1) / 2.0F, (y0 + y1) / 2.0F, scale / Math.max(0.01F, nativeScale),
                    new Vector3f(0, bh / 2.0F, 0), pose, null, living);
        } catch (Exception ex) {
            OvergrownVegetation.LOGGER.debug("ガイドブックの模型を描けない: {}", m.id(), ex);
        } finally {
            g.disableScissor();
            living.yBodyRot = bodyRot;
            living.setYRot(yRot);
            living.setXRot(xRot);
            living.yHeadRot = head;
            living.yHeadRotO = headO;
        }
    }

    private static String number(float v) {
        if (v <= 0) return "-";
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    private float titleScale() {
        return textScale < 1.0F ? 1.25F : 1.5F;
    }

    private void text(GuiGraphics g, Component c, int x, int y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(left + x, top + y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, c, 0, 0, color, false);
        g.pose().popPose();
    }

    private void dotted(GuiGraphics g, int x, int y, int w) {
        for (int i = 0; i < w; i += 2) {
            g.fill(left + x + i, top + y, left + x + i + 1, top + y + 1, INK_SOFT);
        }
    }

    private void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(left + x, top + y, left + x + w, top + y + 1, color);
        g.fill(left + x, top + y + h - 1, left + x + w, top + y + h, color);
        g.fill(left + x, top + y, left + x + 1, top + y + h, color);
        g.fill(left + x + w - 1, top + y, left + x + w, top + y + h, color);
    }

    /** ▼(開いている)/ ▶(畳んである)。 */
    private void triangle(GuiGraphics g, int x, int y, boolean open, int color) {
        for (int i = 0; i < 4; i++) {
            if (open) {
                g.fill(left + x + i, top + y + 1 + i, left + x + 7 - i, top + y + 2 + i, color);
            } else {
                g.fill(left + x + i, top + y + i, left + x + i + 1, top + y + 7 - i, color);
            }
        }
    }

    /** ページ送りの矢印(7x7)。 */
    private void arrow(GuiGraphics g, int x, int y, boolean right) {
        for (int i = 0; i < 4; i++) {
            int col = right ? x + i : x + 3 - i;
            g.fill(left + col, top + y + i, left + col + 1, top + y + 7 - i, INK);
        }
    }

    private boolean inArrow(double mouseX, double mouseY, int x, int y) {
        double bx = mouseX - left, by = mouseY - top;
        return bx >= x - 3 && bx < x + 7 && by >= y - 3 && by < y + 10;
    }

    // ------------------------------------------------------------------ 押す

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        long now = Util.getMillis();
        if (button != 0 || busy(now)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int tab = tabAt(mouseX, mouseY);
        if (tab >= 0) {
            tabTug[tab] = now;
            if (ready(tab)) {
                // 付箋で章の頭へ: その向きへ数枚パラパラめくる。
                View head = View.index(tab);
                if (tab != view.chapter()) {
                    startFlip(head, tab > view.chapter() ? 1 : -1, 3);
                } else if (!head.equals(view)) {
                    startFlip(head, -1, 3);
                }
            }
            return true;
        }
        if (isText(view.chapter())) {
            int ch = view.chapter();
            if (view.topic() >= 0) {
                if (inArrow(mouseX, mouseY, LEFT_X, 165)) {
                    startFlip(view.spread() > 0 ? View.topic(ch, view.topic(), view.spread() - 1) : indexOf(ch, view.topic()), -1, 1);
                    return true;
                }
                if ((view.spread() + 1) * 2 < pagesOf(view).size() && inArrow(mouseX, mouseY, 266, 165)) {
                    startFlip(View.topic(ch, view.topic(), view.spread() + 1), 1, 1);
                    return true;
                }
            } else {
                if (view.spread() > 0 && inArrow(mouseX, mouseY, LEFT_X, 165)) {
                    startFlip(new View(ch, null, -1, view.spread() - 1), -1, 1);
                    return true;
                }
                if ((view.spread() + 1) * 2 < indexPages.getOrDefault(ch, 1) && inArrow(mouseX, mouseY, 266, 165)) {
                    startFlip(new View(ch, null, -1, view.spread() + 1), 1, 1);
                    return true;
                }
                TopicRow r = topicRowAt(mouseX, mouseY);
                if (r != null) {
                    startFlip(View.topic(ch, r.topic(), 0), 1, 1);
                    return true;
                }
            }
        } else if (view.entry() != null) {
            if (inArrow(mouseX, mouseY, LEFT_X, 165)) {
                startFlip(View.mobIndex(view.spread()), -1, 1);
                return true;
            }
        } else {
            if (view.spread() > 0 && inArrow(mouseX, mouseY, LEFT_X, 165)) {
                startFlip(View.mobIndex(view.spread() - 1), -1, 1);
                return true;
            }
            if ((view.spread() + 1) * 2 < pageCount && inArrow(mouseX, mouseY, 266, 165)) {
                startFlip(View.mobIndex(view.spread() + 1), 1, 1);
                return true;
            }
            Line l = lineAt(mouseX, mouseY);
            if (l != null) {
                if (l.header()) {
                    if (!collapsed.remove(l.group)) collapsed.add(l.group);
                    layoutIndex();
                } else if (l.mob.discovered()) {
                    startFlip(View.mob(l.mob, view.spread()), 1, 1);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 目次へ戻る(Backspace)。
        if (keyCode == 259 && view.inner() && !busy(Util.getMillis())) {
            startFlip(view.chapter() == MOB ? View.mobIndex(view.spread()) : indexOf(view.chapter(), view.topic()), -1, 1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 閉じるときは沈む動きを見せてから閉じる(動いている間にもう一度押されてもやり直さない)。 */
    @Override
    public void onClose() {
        if (closeStart >= 0) {
            return;
        }
        closeStart = Util.getMillis();
        lastChapter = view.chapter();
        playSound(SoundEvents.BOOK_PUT, 0.8F);
    }
}
