package com.appathy.musicroom

import android.app.Application
import com.appathy.musicroom.midi.MidiHub

class MusicRoomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MidiHub.init(this)
    }
}
