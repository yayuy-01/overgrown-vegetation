# Overgrown Vegetation

Minecraft **1.21.1** / **NeoForge** 用の MOD。「異常繁殖を繰り返す寄生植物」が世界を侵食していく、
サバイバルの進行そのものを変えるコンテンツを追加します。

> 開発中（v0.1.9）です。仕様は今後変わる可能性があります。

## 特徴

- **広がる侵食** — 寄生植物を起点に、地形が球状に「汚染ブロック」へ置き換わっていきます。
  放置すると拠点の周囲まで飲み込まれます。
- **過繁茂した Mob** — バニラの Mob が寄生され、見た目も挙動も変わった姿で襲ってきます。
  硬化・毒性・耐火などの亜種もいます。
- **フェーズ進行** — 侵食の規模やプレイヤーの進捗に応じて世界の段階が進み、
  湧く敵や襲撃の激しさが変わっていきます。
- **研究と装備** — 侵食から得た素材を研究して、対抗手段となる道具・防具・設備をアンロックします。
- **防衛設備** — 忌避灯、有刺鉄線、自動砲台などで拠点を守れます。

## 導入方法

1. [NeoForge](https://neoforged.net/) **21.1.0 以降**（Minecraft 1.21.1 向け）を導入する
2. 以下の前提 MOD を `mods` フォルダに入れる
   - [GeckoLib](https://www.curseforge.com/minecraft/mc-mods/geckolib)
   - [Player Animation Library](https://docs.zigythebird.com/pal/)
3. 本 MOD の jar を `mods` フォルダに入れる
4. **[Sodium](https://modrinth.com/mod/sodium) の導入を強く勧めます**（必須ではありません）。光・霧・雰囲気の調整と確認は Sodium を入れた環境で行っています。Sodium 無しでも動きますが、FPS が大きく下がり、見え方の保証もしません

## コマンド

| コマンド | 説明 |
| --- | --- |
| `/ov phase` | 現在のフェーズの確認・変更（OP 専用） |

## ソースからビルドする

> **このリポジトリには素材（テクスチャ・モデル・アニメーション・効果音）が含まれていません。**
> コードはビルドできますが、できた jar はブロックやアイテムの表示が欠け、mob は正常に描画されません。
> 遊ぶ場合は作者が配布する jar を使ってください。

JDK 21 が必要です。

```
gradlew.bat build        # build/libs/ に jar を出力
```

初回の Gradle 同期は Minecraft の展開とマッピング適用のため 5〜15 分かかります。
対応バージョンは `gradle.properties` で変更できます。

## ライセンス

- **ソースコード**（このリポジトリの内容）: [MIT License](LICENSE)。改造・再配布・改変版の公開はご自由にどうぞ。
  `LICENSE` の著作権表示は残してください。
- **素材**（テクスチャ・モデル・アニメーション・効果音）: © yayuy, All Rights Reserved。
  このリポジトリには含まれておらず、配布版 jar に同梱されているものも含め、無断での再配布・流用はご遠慮ください。

| 依存ライブラリ | ライセンス |
| --- | --- |
| [NeoForge](https://neoforged.net/) | LGPL-2.1 |
| [GeckoLib](https://github.com/bernie-g/geckolib) | MIT |
| [Player Animation Library](https://docs.zigythebird.com/pal/) | MIT |

Minecraft 本体および Mojang の著作物の権利は Mojang Studios に帰属します。
本 MOD は Mojang Studios / Microsoft の公式製品ではなく、承認も受けていません。

## 不具合の報告

Issue へどうぞ。再現手順、Minecraft / NeoForge / 本 MOD のバージョン、
同時に入れている MOD、`logs/latest.log` を添えていただけると助かります。
