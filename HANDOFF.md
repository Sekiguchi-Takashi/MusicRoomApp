# MusicRoomApp / 音楽室アプリ HANDOFF

## 現在地
- v1.7 (versionCode 8)
- 設計書『音楽室アプリ｜スマホ版設計書』の **Phase 1〜4 完了** (Phase 5 は保留) を実装済み
- パッケージ: `com.appathy.musicroom` / アプリ名: 音楽室
- minSdk 26 / compileSdk 34 / AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7
- ビルドは GitHub Actions (`.github/workflows/build.yml`)。Gradle Wrapper は使わず `setup-gradle` で直接実行。push すると Releases に APK が出る。

## 実装済み
| 設計書 | 実装 |
|---|---|
| §4 MIDIオントロジー | `midi/MusicEvent.kt`, `midi/MidiHub.kt` |
| §17 MIDI接続画面 | `ui/MidiActivity.kt` (USB MIDI / 自動再接続 / 最後の機器を記憶) |
| §18 MIDI入力テスト | 同上 (Note名・Note番号・Velocity・Status・Channel・イベントログ) |
| §7.1 自由演奏 | `ui/PlayActivity.kt` + `ui/KeyboardView.kt` (マルチタッチ鍵盤) |
| Phase1 #9 MIDI録音・再生 | `ui/RecordActivity.kt` (録音／再生／クリア／保存／読込。`filesDir/take.csv`) |
| §7.2 メトロノーム | `ui/MetronomeActivity.kt` (BPM/拍子/分割/アクセント) |
| §8, §39 連打機能 | `ui/RepetitionActivity.kt` (回数・平均間隔・最短間隔・分散→安定度) |
| §9.1, §20, §41, §42 ピアノリズム | `ui/RhythmGameActivity.kt`, `ui/RhythmView.kt`, `game/ChartGenerator.kt`, `game/Judge.kt` |
| §9.2, §48 音当て | `ui/EarGameActivity.kt` (単音／2音／3音／音程) |
| §9.3 コードゲーム | `ui/ChordGameActivity.kt` (三和音・四和音、押鍵セット一致で判定) |
| Phase2 楽曲練習 | `song/Song.kt`, `song/SongChart.kt`, `ui/SongPracticeActivity.kt`, `ui/SongRollView.kt` |
| §36/§37/§44 記録の永続化・弱点検出・練習推薦 | `data/PracticeDb.kt` (SQLite), `data/Coach.kt`, `ui/HistoryActivity.kt`, `ui/TrendView.kt` |
| §43 小節単位評価・苦手小節抽出 | `song/SongEvaluator.kt` 相当 (`song/SongChart.kt` 内)。結果行タップで該当小節を4回ループ練習 |
| 音源 | `audio/SynthEngine.kt` (AudioTrack 低レイテンシ・16音ポリ) |
| ゲーム音 | `audio/SeRenderer.kt`, `ui/SoundLabActivity.kt`, WAV書き出し |
| §10, §49, §50 マイク音程検出・うた練習 | `audio/YinDetector.kt` (YIN), `audio/MicEngine.kt`, `ui/SingActivity.kt`, `ui/PitchTrackView.kt`, `ui/TunerActivity.kt`, `ui/PitchMeterView.kt` |
| §11 作曲 (弾いて入力・グリッド編集) | `song/Quantizer.kt`, `ui/ComposeActivity.kt`, `ui/ComposeGridView.kt`, `data/UserSongStore.kt` |
| §12〜§14, §55〜§60 歌詞のモーラ制約 | `song/Mora.kt`, `ui/LyricsActivity.kt` |
| 音楽理論 | `audio/MusicTheory.kt` (スケール／コード／音程名) |

## 未実装 (次フェーズ)
- 収録曲は4曲 (かえるのうた / きらきら星 / メリーさんのひつじ / 歓喜の歌)。すべて単旋律・Cメジャー・4/4。
  和音つきの曲や調号のある曲を足す場合は `song/SongLibrary` に追加するだけでよい。
- **Phase 5 (AI音楽先生) は長期保留**。Bonsai 側の受け入れができていないため、着手しない。
  再開する場合は `data/Coach.kt` のヒューリスティックを差し替える形になる。BONSAI_API.md の契約に従うこと。
- 作曲の残り: 和音入力 (現状は単旋律のみ)、曲のエクスポート/インポート UI (`UserSongStore.exportJson` は実装済みだが画面がない)、
  歌詞のタイミング再生 (現状は音に合わせてモーラを1つずつ表示するのみ)
- BLE MIDI (現状は USB MIDI のみ。`MidiHub` は transport 非依存なので、スキャンUIを足すだけで載る)
- 能力モデルは現状ヒューリスティック (`data/Coach.kt`)。統計が貯まったら推定へ置き換える。
- 記録の書き出し (CSV/JSON) と、セッション詳細画面はまだない。

## 設計上の約束
- **SynthEngine.timbre (グローバル音色) は自由演奏と録音・再生だけが使う。**
  ゲーム・出題・お手本は必ず `Wave.PIANO` を明示して渡す (v1.6 で修正済みのバグ。NOISE 音色だと音当てが成立しなくなる)。
- **すべての入力は `MusicEvent` に正規化してから使う。** MIDI もタッチ鍵盤も `MidiHub` を通る (`MidiHub.inject`)。
  新しいゲーム／練習画面は `MidiHub.Listener` を実装するだけでよく、MIDI の有無を意識しない。
- MIDI由来かタッチ由来かは `MusicEvent.source` で区別する。二重発音を避けるため、画面鍵盤を持つ Activity は
  `EventSource.MIDI` のみを onMusicEvent で処理し、タッチは KeyboardView.Callback で受ける。
- `SynthEngine` は Activity の onResume/onPause で start/stop する。
- タイミング判定の閾値は `game/Judge.kt` に集約。テンポ・難易度で変えられるよう可変にしてある。
  楽曲練習は Judge.windowMs の 1.6 倍を許容窓にしている (ゲームより緩め)。
- マイク処理は 22050Hz / 窓1024 / ホップ512 の YIN。22050 が使えない端末は 44100 で読んで 1/2 に間引く。
  うた練習の判定はノート区間の中央値セント差で、有声率 35% 未満は MISS。DB には ms ではなくセントを入れているため、
  ラベルを「うた・曲名」と接頭辞つきにして楽曲練習の小節統計と混ざらないようにしている。
- 自作曲は 1曲1ファイルの JSON (`filesDir/songs/*.json`)。SQLite ではないのは、構造が入れ子で曲数が少なく、
  そのままエクスポートに使えるため。`SongLibrary.all(context)` が内蔵曲と自作曲を合流させ、自作曲は頭に「★」がつく。
  楽曲練習・うた練習の曲リストは `reloadCatalog()` で onResume ごとに読み直す (作曲画面で保存した曲がすぐ出る)。
- 量子化は `Quantizer`。単旋律化のため同一拍位置は最も高い音だけを残し、次の音に食い込む長さは切り詰める。
- モーラ数えは拗音 (ゃゅょ等) を直前に吸収し、撥音・促音・長音は1モーラとして数える。漢字が残っていると警告を出す。
- 曲は「拍位置と長さ (拍)」で持ち、`ChartBuilder.build()` が BPM を掛けて実時間チャートへ展開する。
  部分練習は同じビルダに measures と repeats を渡すだけで作れる。能力モデル (Phase 5) もこの MeasureStat を入力にする想定。
- 効果音はリアルタイム合成ではなく `SeRenderer` でオフライン生成 → 同じ PCM をそのまま再生と WAV 保存に使う。

## Arturia MiniLab 3 メモ
- USB-C クラスコンプライアント。専用ドライバ不要、OTG 直結で `MidiHub.devices()` に出る。
- バスパワー駆動。スマホの給電が不足する場合は給電付き USB-C ハブを挟む。
- 鍵盤 = ch1 の NoteOn/NoteOff (velocity 有効)。
- パッド = 既定で note 36〜43。ゲーム音ラボでは 8 プリセットに直結している。
- エンコーダ／フェーダー = CC。現状は MIDI入力テスト画面でログ表示のみ。
- サステインペダル端子の CC64 は `PlayActivity` で反映済み。
- リズムゲームのレーンは C4〜B4 の白鍵 (Cメジャースケール)。25鍵の MiniLab 3 でそのまま届く範囲にしてある。
