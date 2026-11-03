package com.jot.app
import android.app.Application

class JotApplication : Application() {
    lateinit var controller: JotController
        private set
    override fun onCreate() { super.onCreate(); controller = JotController(this) }
}
