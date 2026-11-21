package com.yayuy.overgrownvegetation.gear.attach;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 防具の付け物の設計値({@code armor_attachments.json}、2026-11-21 夜)。<b>値はどれも【仮】</b>(働きはユーザーがまだ決めていない)。
 * 正本は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の末尾「防具の付け物」の節。
 *
 * <p>どの付け物がどの部位に付くか・1 つの防具にいくつまでかもここで決める(付け物を足すときは、アイテムを登録してここに 1 節足す)。
 * Gson で直に読む(鍵が無ければこの既定値)。毎回読むので、run/config の写しを直して再起動で効く。
 */
public final class AttachTable {

    public static final AttachTable DEFAULT = new AttachTable();

    /** 防具 1 つの枠の数(ユーザー決定 11-21 夜: 3)。 */
    public int slots = 3;
    /** 収納付きベルトの小物入れの枠(1〜9)。 */
    public int beltSlots = 4;

    /** 付け物ごとの決まり。鍵 = この MOD のアイテムの id(名前空間を省いた形)。 */
    public Map<String, Entry> attachments = defaults();

    /** 付け物 1 種の決まり。 */
    public static final class Entry {
        /** 付けられる部位(head / chest / legs / feet)。 */
        public List<String> parts = List.of();
        /** 1 つの防具に同じ物をいくつまで付けられるか。 */
        public int max = 1;
        /** 付けた部位の防御の上乗せ(1 枚ごと)。 */
        public double armor = 0.0D;
        /** 着けている人の地脈の力の減りを減らす割合(0.1 = 1 割減)。 */
        public double leyDrainReduction = 0.0D;
        /** 口覆いの布のフィルターの減りの倍率(1 = 防護マスクと同じ)。 */
        public double filterWear = 1.0D;

        Entry() {
        }

        Entry(List<String> parts, int max) {
            this.parts = parts;
            this.max = max;
        }
    }

    private static Map<String, Entry> defaults() {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("mouth_cloth", new Entry(List.of("head"), 1));
        m.put("storage_belt", new Entry(List.of("legs"), 1));
        Entry bark = new Entry(List.of("head", "chest", "legs", "feet"), 3);
        bark.armor = 1.0D;
        m.put("bark_plate", bark);
        Entry sash = new Entry(List.of("legs"), 1);
        sash.leyDrainReduction = 0.1D;
        m.put("conduit_sash", sash);
        return m;
    }
}
