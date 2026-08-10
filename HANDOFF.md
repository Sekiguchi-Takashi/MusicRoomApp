# MusicRoomApp / 音楽室アプリ HANDOFF

## 現在地
- v1.1 (versionCode 2)
- 設計書『音楽室アプリ｜スマホ版設計書』の **Phase 1 全体 + Phase 2 の一部** を実装済み
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
| §7.2 メトロノーム | `ui/MetronomeActivity.kt` (BPM/拍子/分割/アクセント) |
| §8, §39 連打機能 | `ui/RepetitionActivity.kt` (回数・平均間隔・最短間隔・分散→安定度) |
| 音源 | `audio/SynthEngine.kt` (AudioTrack 低レイテンシ・16音ポリ) |
| ゲーム音 | `audio/SeRenderer.kt`, `ui/SoundLabActivity.kt`, WAV書き出し |

## 未実装 (次フェーズ)
- Phase 1 残: MIDI録音・再生
- Phase 2 残: 楽曲練習、タイミング判定、リズムゲーム、音当てゲーム、コードゲーム
- Phase 3: マイク入力・音程検出・うた練習
- Phase 4: 作曲・歌詞制約・歌詞タイミング
- Phase 5: 能力モデル・弱点検出・練習推薦・AI音楽先生
- BLE MIDI (現状は USB MIDI のみ。`MidiHub` は transport 非依存なので、スキャンUIを足すだけで載る)
- データ永続化 (現状 SharedPreferences に最終接続デバイス名のみ)

## 設計上の約束
- **すべての入力は `MusicEvent` に正規化してから使う。** MIDI もタッチ鍵盤も `MidiHub` を通る (`MidiHub.inject`)。
  新しいゲーム／練習画面は `MidiHub.Listener` を実装するだけでよく、MIDI の有無を意識しない。
- `SynthEngine` は Activity の onResume/onPause で start/stop する。
- 効果音はリアルタイム合成ではなく `SeRenderer` でオフライン生成 → 同じ PCM をそのまま再生と WAV 保存に使う。

## Arturia MiniLab 3 メモ
- USB-C クラスコンプライアント。専用ドライバ不要、OTG 直結で `MidiHub.devices()` に出る。
- バスパワー駆動。スマホの給電が不足する場合は給電付き USB-C ハブを挟む。
- 鍵盤 = ch1 の NoteOn/NoteOff (velocity 有効)。
- パッド = 既定で note 36〜43。ゲーム音ラボでは 8 プリセットに直結している。
- エンコーダ／フェーダー = CC。現状は MIDI入力テスト画面でログ表示のみ。
- サステインペダル端子の CC64 は `PlayActivity` で反映済み。
