# MusicRoomApp / 音楽室アプリ HANDOFF

## 現在地
- v1.14 (versionCode 15)
- 設計書『音楽室アプリ｜スマホ版設計書』の **Phase 1〜4 完了** (Phase 5 は保留) を実装済み
- パッケージ: `com.appathy.musicroom` / アプリ名: 音楽室
- minSdk 26 / compileSdk 34 / AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7
- **配布は `deploy.sh` 1コマンド** (恒久仕様)。push → `git pull --rebase origin main` → タグ発行までを行う。
  タグを打つと `.github/workflows/release.yml` (カタログ管理システムが API 経由でコミット) がビルドして Release を作り、
  自作アプリストアに更新として現れる。
- `git pull --rebase origin main` は必須。カタログ管理システムが release.yml と `ci/appathy.keystore` を
  直接コミットするため、無いと push が rejected になる。
- **`ci/` と `.github/workflows/release.yml` は削除しない** (配布ビルドに必要)。
- **`build.yml` は作らない・同梱しない** (納品規約)。CI は `release.yml` のタグ起動のみに一本化する。
  `actions/upload-artifact` も使わない (Artifacts 無料枠 0.5GB が枯渇し全ビルドが落ちる)。APK は Release から配布する。
- **次タグはローカルのタグ一覧から算出する。** `git fetch --tags --force` → `git tag --list 'v*' | sort -V | tail -1` →
  パッチ +1 → `git tag` → `git push origin タグ名`。GitHub API の `git/ref/heads/main` 参照は反映遅延で
  一つ前のコミットにタグが付くため使わない。deploy.sh の第2引数に `notag` を渡すと push のみで終わる。
- **署名は固定鍵。`ci/appathy.keystore` があり、かつパスワードが env / gradle プロパティで渡されていればそれを使い、
  無ければ同梱の `keystore/musicroom.jks` を使う** (v1.10〜)。
  これがないと Actions のランナーが毎回新しい debug キーを自動生成するため署名が変わり、
  上書きインストールできず「アンインストールしてから」になる = SQLite の練習記録と自作曲が毎回消える。
  パスワードは musicroom / alias musicroom。private リポジトリ前提の割り切り。Play 配布するなら Secrets へ移すこと。
  v1.9 以前の APK からは署名が変わるので、一度だけアンインストールが必要 (自作曲は先に書き出しておく)。

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
| 曲の共有 | `data/UserSongStore.exportJson/importJson` + `ui/ComposeActivity` の [📤 書き出し/取り込み] (共有・コピー・クリップボード・ファイル) |
| 伴奏づけ | `song/Harmonizer.kt` (小節ごとにダイアトニック三和音を推定 → ブロック/アルペジオ/ベースで生成) |
| つまみ操作 | `midi/CcLearn.kt` — CC番号を決め打ちせず「最初に動かしたつまみから順に役割を割当」て保存 |
| BLE MIDI | `midi/BleMidiScanner.kt` + `MidiHub.connectBluetooth` + MIDI画面のスキャンUI |
| 音楽理論 | `audio/MusicTheory.kt` (スケール／コード／音程名) |

## 未実装 (次フェーズ)
- 収録曲は4曲 (かえるのうた / きらきら星 / メリーさんのひつじ / 歓喜の歌)。すべて単旋律・Cメジャー・4/4。
  和音つきの曲や調号のある曲を足す場合は `song/SongLibrary` に追加するだけでよい。
- **Phase 5 (AI音楽先生) は長期保留**。Bonsai 側の受け入れができていないため、着手しない。
  再開する場合は `data/Coach.kt` のヒューリスティックを差し替える形になる。BONSAI_API.md の契約に従うこと。
- 練習カレンダー/連続日数 (意図的に未着手)
- 歌詞のタイミング再生の作り込み (現状は音に合わせてモーラを1つずつ表示するのみ)
- 伴奏トラックは再生のみで判定対象にしていない。両手練習をやるなら PlayNote に track を持たせる必要がある。
- BLE MIDI (現状は USB MIDI のみ。`MidiHub` は transport 非依存なので、スキャンUIを足すだけで載る)
- 能力モデルは現状ヒューリスティック (`data/Coach.kt`)。統計が貯まったら推定へ置き換える。
- 記録の書き出し (CSV/JSON) と、セッション詳細画面はまだない。

## 精度まわりの約束 (v1.13)
- **自動で音を切るときは `SynthEngine.noteOn` が返すトークンを `releaseToken` に渡す。**
  `noteOff(pitch)` はその音高の声部を全部離すため、同じ音を速く連打すると前の音の自動オフが次の音を切ってしまう。
  鍵盤・MIDI の離鍵は従来どおり `noteOff(pitch)` でよい。
- サステインペダルを離したときは、まだ指が乗っている音 (`heldNotes`) を残す。以前は全部消していた。
- YIN はオクターブ上に取り違えやすいので、2倍・3倍の周期にも十分深い谷があればそちらを採用する (`correctOctave`)。
  さらに直近3フレームの中央値で単発の誤検出を落とす。
- 歌唱判定の時刻は `MicEngine` が渡す録音時刻 (窓の中心ぶん遡らせた値) を使う。UI へ届くまでの遅れを含めないため。
- 歌唱の音程評価は音の前後 18% を除いた中央区間だけを見る (`SingActivity.ONSET_MARGIN`)。
  しゃくり上げや次の音への移り変わりが混ざるのを避けるため。表示用の軌跡は全区間そのまま描く。
- BPM 推定は中央値だけで決めず、候補 BPM ごとに全打点の吸着誤差を計算して最小のものを選ぶ (`Quantizer.estimateBpm`)。
- 自己ベストは MAX(score) と MAX(accuracy) を別々に取らず、最高スコアを出した回の行ごと取る。

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
- 曲は2トラック (`Song.notes` = メロディ / `Song.accompaniment` = 伴奏)。伴奏は判定せず `SongChart.backing` として再生のみ。
  作曲画面の [♪ メロディ / 🎵 伴奏] で編集対象を切り替える。伴奏トラックでの「弾いて入力」は和音を保持する
  (`Quantizer.quantize(polyphonic = true)`)。メロディは従来どおり単旋律化する。
- MIDI の CC は機種・プリセットで番号が変わるため決め打ちしない。`CcLearn` が役割を順に学習し SharedPreferences に保存する。
  割当のリセットは作曲画面の [⚙ 設定] から。
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
