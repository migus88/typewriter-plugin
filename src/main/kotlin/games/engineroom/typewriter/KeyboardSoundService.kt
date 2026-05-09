package games.engineroom.typewriter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.APP
import com.intellij.openapi.diagnostic.thisLogger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.pow
import kotlin.random.Random

/**
 * Plays keyboard click sounds.
 *
 * Architecture: **single mixing thread + single SourceDataLine**, both kept alive for the
 * service's lifetime. Each [playKey]/[playSpace]/[playEnter] call:
 * 1. Picks a random sample.
 * 2. Pre-renders a "voice" — pitch-shifted (linear-resampled) and gain-adjusted ShortArray.
 * 3. Appends the voice to the mix list and signals the mix thread.
 *
 * The mix thread loops:
 * - Waits on the voice list (no CPU when idle).
 * - When voices arrive, walks each active voice forward by [MIX_BUFFER_FRAMES] and sums
 *   into an int mix buffer (overflow-safe).
 * - Clips the int sum to 16-bit and writes one buffer to the line. Repeats until all voices
 *   finish, then sleeps again.
 *
 * Why not per-playback Clip / SourceDataLine: opening a line per click on macOS Java Sound
 * eventually exhausts mixer resources (the "weird sound + no audio" failure mode), and
 * `drain()` blocks playback threads for the full sound duration so the executor backs up
 * under fast typing. One always-open line + software mixing avoids both problems.
 *
 * Pitch + gain are applied at voice-render time, not via line controls — `FloatControl.SAMPLE_RATE`
 * isn't reliably exposed on macOS, and per-line MASTER_GAIN sets are racy when many voices
 * share a line. Software is deterministic.
 */
@Service(APP)
class KeyboardSoundService : Disposable {
    /** Single output format used by the mixer + line. All samples are normalized to this on load. */
    private val outputFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, OUTPUT_RATE, 16, 1, 2, OUTPUT_RATE, false,
    )

    private class Sample(val frames: ShortArray)
    private class Voice(val frames: ShortArray, var position: Int = 0)

    private val voicesLock = Object()
    private val voices = ArrayList<Voice>(MAX_VOICES)

    @Volatile private var alive = true

    private val keys: List<Sample> by lazy { loadAll((1..5).map { "key-%02d".format(it) }) }
    private val enters: List<Sample> by lazy { loadAll((1..2).map { "enter-%02d".format(it) }) }
    private val spaces: List<Sample> by lazy { loadAll((1..2).map { "spacebar-%02d".format(it) }) }

    /**
     * Single line, opened lazily on first access. Kept open for the service's lifetime so we
     * never pay the open/close cost (which is what drove the per-playback approaches into
     * resource exhaustion on macOS).
     */
    private val outputLine: SourceDataLine? by lazy {
        try {
            val info = DataLine.Info(SourceDataLine::class.java, outputFormat)
            if (!AudioSystem.isLineSupported(info)) {
                thisLogger().warn("No SourceDataLine supports $outputFormat")
                return@lazy null
            }
            val l = AudioSystem.getLine(info) as SourceDataLine
            l.open(outputFormat, LINE_BUFFER_BYTES)
            l.start()
            l
        } catch (t: Throwable) {
            thisLogger().warn("Failed to open audio line", t)
            null
        }
    }

    private val mixThread = Thread(::mixLoop, "Typewriter-AudioMixer").apply {
        isDaemon = true
        start()
    }

    /**
     * Force sample loading + audio line opening on a background thread. Call when the dialog
     * opens so the first keystroke doesn't pay file IO + first-time mixer init latency
     * (~tens to a few hundred ms on macOS).
     */
    fun prewarm() {
        Thread({
            try {
                keys.size
                enters.size
                spaces.size
                outputLine // forces lazy open
            } catch (t: Throwable) {
                thisLogger().debug("Prewarm failed", t)
            }
        }, "Typewriter-AudioPrewarm").apply { isDaemon = true; start() }
    }

    fun playKey() = play(keys, GAIN_KEY)
    fun playSpace() = play(spaces, GAIN_LOUD)
    fun playEnter() = play(enters, GAIN_LOUD)

    private fun play(samples: List<Sample>, gainLin: Float) {
        val sample = samples.randomOrNull() ?: return
        val pitch = MIN_PITCH + Random.nextFloat() * (MAX_PITCH - MIN_PITCH)
        val voice = renderVoice(sample, pitch, gainLin)
        synchronized(voicesLock) {
            if (voices.size >= MAX_VOICES) return  // drop rather than back up
            voices += voice
            (voicesLock as Object).notifyAll()
        }
    }

    /**
     * Linear-resample [sample] by [pitch] (>1 = higher + faster) and apply [gainLin] in one
     * pass. Output is 16-bit signed mono at [OUTPUT_RATE], ready to be summed straight into
     * the mix buffer.
     */
    private fun renderVoice(sample: Sample, pitch: Float, gainLin: Float): Voice {
        val src = sample.frames
        val outLen = (src.size / pitch).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val srcLast = src.size - 1
        for (i in 0 until outLen) {
            val srcIdx = i * pitch
            val lo = srcIdx.toInt().coerceAtMost(srcLast)
            val hi = (lo + 1).coerceAtMost(srcLast)
            val frac = srcIdx - lo
            val v = (src[lo] * (1 - frac) + src[hi] * frac) * gainLin
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return Voice(out)
    }

    private fun mixLoop() {
        val line = outputLine ?: return
        val frames = MIX_BUFFER_FRAMES
        val mix = IntArray(frames)
        val bytes = ByteArray(frames * 2)
        try {
            while (alive) {
                synchronized(voicesLock) {
                    while (alive && voices.isEmpty()) {
                        try {
                            (voicesLock as Object).wait()
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                }
                if (!alive) return

                // Pump audio until all voices have finished playing.
                while (alive) {
                    java.util.Arrays.fill(mix, 0)
                    val hasMore = synchronized(voicesLock) { mixOnce(mix, frames) }
                    encodeLE16(mix, bytes, frames)
                    try {
                        line.write(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        thisLogger().warn("Audio line write failed; mixer exiting", e)
                        return
                    }
                    if (!hasMore) break
                }
            }
        } catch (t: Throwable) {
            thisLogger().warn("Mix loop crashed", t)
        }
    }

    /** Caller must hold [voicesLock]. Returns true if any voice still has frames remaining. */
    private fun mixOnce(mix: IntArray, frames: Int): Boolean {
        val iter = voices.iterator()
        while (iter.hasNext()) {
            val v = iter.next()
            val remaining = v.frames.size - v.position
            val n = if (remaining < frames) remaining else frames
            var i = 0
            while (i < n) {
                mix[i] += v.frames[v.position + i].toInt()
                i++
            }
            v.position += n
            if (v.position >= v.frames.size) iter.remove()
        }
        return voices.isNotEmpty()
    }

    private fun encodeLE16(mix: IntArray, out: ByteArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val s = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            i++
        }
    }

    private fun loadAll(names: List<String>): List<Sample> = names.mapNotNull(::load)

    /**
     * Decode a wav resource and normalize it to mono 16-bit PCM at [OUTPUT_RATE]. Java Sound's
     * built-in PCM converter handles bit-depth + sample-rate + channel changes; if it fails,
     * we fall back to manual conversion (PCM_SIGNED at source rate, then software downmix +
     * resample).
     */
    private fun load(name: String): Sample? {
        return try {
            val url = javaClass.getResource("/sounds/keyboard/$name.wav")
            if (url == null) {
                thisLogger().warn("Missing sound resource: /sounds/keyboard/$name.wav")
                return null
            }
            AudioSystem.getAudioInputStream(url).use { src ->
                val direct = runCatching {
                    val s = AudioSystem.getAudioInputStream(outputFormat, src)
                    val bytes = s.readAllBytes()
                    bytesToShortsLE(bytes)
                }.getOrNull()
                val frames = direct ?: convertManually(src)
                if (frames == null || frames.isEmpty()) {
                    thisLogger().warn("Empty audio data for $name")
                    return null
                }
                Sample(frames)
            }
        } catch (t: Throwable) {
            thisLogger().warn("Failed to load sound $name", t)
            null
        }
    }

    private fun convertManually(src: javax.sound.sampled.AudioInputStream): ShortArray? {
        val sf = src.format
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, sf.sampleRate, 16, sf.channels,
            sf.channels * 2, sf.sampleRate, false,
        )
        val pcmStream = AudioSystem.getAudioInputStream(pcmFormat, src)
        val raw = bytesToShortsLE(pcmStream.readAllBytes())
        val mono = if (sf.channels == 1) raw else ShortArray(raw.size / sf.channels) { i ->
            var sum = 0
            for (c in 0 until sf.channels) sum += raw[i * sf.channels + c].toInt()
            (sum / sf.channels).toShort()
        }
        return if (sf.sampleRate == OUTPUT_RATE) mono
        else resampleLinear(mono, sf.sampleRate / OUTPUT_RATE)
    }

    private fun bytesToShortsLE(bytes: ByteArray): ShortArray =
        ShortArray(bytes.size / 2) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }

    private fun resampleLinear(input: ShortArray, ratio: Float): ShortArray {
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val last = input.size - 1
        for (i in 0 until outLen) {
            val srcIdx = i * ratio
            val lo = srcIdx.toInt().coerceAtMost(last)
            val hi = (lo + 1).coerceAtMost(last)
            val frac = srcIdx - lo
            out[i] = (input[lo] * (1 - frac) + input[hi] * frac).toInt().toShort()
        }
        return out
    }

    override fun dispose() {
        alive = false
        synchronized(voicesLock) { (voicesLock as Object).notifyAll() }
        mixThread.interrupt()
        outputLine?.let { l ->
            try { l.stop() } catch (_: Throwable) {}
            try { l.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val OUTPUT_RATE = 44100f

        private const val MIN_PITCH = 0.95f
        private const val MAX_PITCH = 1.05f

        /** Linear gain for unboosted keystrokes (1.0 = unity). */
        private const val GAIN_KEY = 1.0f

        /** Linear gain for space and enter. ~+6 dB ≈ 2× amplitude. */
        private val GAIN_LOUD: Float = 10.0.pow(6.0 / 20.0).toFloat()

        /** Frames per mix tick. 512 frames = ~11.6 ms at 44.1 kHz — small enough for low onset latency. */
        private const val MIX_BUFFER_FRAMES = 512

        /**
         * SourceDataLine internal buffer. Sets the upper bound on how late a newly-added voice
         * can start sounding (line buffer drain time). 4096 bytes = 2048 frames = ~46 ms.
         */
        private const val LINE_BUFFER_BYTES = 4096

        /**
         * Cap on simultaneous voices. Beyond this, new playback requests are silently dropped
         * — better to lose a click than introduce mix overhead under runaway typing.
         */
        private const val MAX_VOICES = 32

        fun get(): KeyboardSoundService =
            ApplicationManager.getApplication().getService(KeyboardSoundService::class.java)
    }
}
