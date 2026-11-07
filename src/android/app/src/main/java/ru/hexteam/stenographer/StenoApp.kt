package ru.hexteam.stenographer

import android.app.Application
import ru.hexteam.stenographer.engine.ProcessingEngine

class StenoApp : Application() {
    val engine: ProcessingEngine by lazy { ProcessingEngine(this) }
}
