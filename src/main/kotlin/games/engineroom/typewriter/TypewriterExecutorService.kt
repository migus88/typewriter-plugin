package games.engineroom.typewriter

import games.engineroom.typewriter.commands.Command
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.APP
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-threaded scheduler for typing sessions.
 *
 * A session is a list of commands enqueued together. Only one session runs at a time —
 * [start] checks for that. [stop] cancels the running session (interrupts the worker,
 * drains the queue) and fires the session's `onDone` callback on the EDT, so the UI can
 * unfreeze regardless of whether the run completed naturally or was stopped.
 */
@Service(APP)
class TypewriterExecutorService : Disposable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var currentSession: Session? = null

    val isRunning: Boolean
        @Synchronized get() = currentSession != null

    @Synchronized
    fun start(commands: List<Command>, onDone: () -> Unit) {
        check(currentSession == null) { "A typing session is already running" }
        val session = Session(onDone)
        currentSession = session
        for (cmd in commands) {
            session.futures += scheduler.schedule(cmd, 0, SECONDS)
        }
        session.futures += scheduler.schedule({ session.complete() }, 0, SECONDS)
    }

    @Synchronized
    fun stop() {
        currentSession?.cancel()
    }

    override fun dispose() {
        synchronized(this) { currentSession?.cancel() }
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, SECONDS)) scheduler.shutdownNow()
    }

    private inner class Session(private val onDone: () -> Unit) {
        val futures = mutableListOf<Future<*>>()
        private val completed = AtomicBoolean(false)

        fun cancel() {
            futures.forEach { it.cancel(true) }
            complete()
        }

        fun complete() {
            if (!completed.compareAndSet(false, true)) return
            synchronized(this@TypewriterExecutorService) {
                if (currentSession === this) currentSession = null
            }
            ApplicationManager.getApplication().invokeLater(onDone)
        }
    }
}
