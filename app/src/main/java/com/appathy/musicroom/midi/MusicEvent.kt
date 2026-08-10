package com.appathy.musicroom.midi

enum class EventType { NOTE_ON, NOTE_OFF, CONTROL_CHANGE, PITCH_BEND, PROGRAM_CHANGE }

enum class EventSource { MIDI, TOUCH }

data class MusicEvent(
    val type: EventType,
    val note: Int = -1,
    val velocity: Int = 0,
    val channel: Int = 0,
    val controller: Int = -1,
    val value: Int = 0,
    val timestampNanos: Long = System.nanoTime(),
    val source: EventSource = EventSource.MIDI
) {
    val pitchName: String get() = noteName(note)

    companion object {
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(n: Int): String = if (n < 0) "-" else NAMES[n % 12] + (n / 12 - 1)

        fun frequency(n: Int): Double = 440.0 * Math.pow(2.0, (n - 69) / 12.0)
    }
}
