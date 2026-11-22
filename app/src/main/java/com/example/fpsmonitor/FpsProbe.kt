package com.example.fpsmonitor

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Best-effort SurfaceFlinger frame pacing diagnostics through Shizuku's shell identity.
 * A layer's presentation rate is not necessarily the game's internal rendering FPS.
 */
object FpsProbe {
    data class Result(val fps: Int?, val status: String, val layer: String? = null)
    private var lastLayer: String? = null

    private fun shell(vararg args: String): String {
        @Suppress("DEPRECATION")
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null, arrayOf("sh", "-c", args.joinToString(" ")), null, null
        ) as Process
        return try {
            val output = StringBuilder()
            val reader = Thread {
                try { process.inputStream.bufferedReader().use { output.append(it.readText().take(300000)) } }
                catch (_: Exception) {}
            }
            reader.isDaemon = true
            reader.start()
            // Shizuku remote Process.exitValue() can throw "process hasn't exited".
            // Wait for termination on a separate daemon thread with a deadline.
            val done = CountDownLatch(1)
            val exitCode = AtomicInteger(-1)
            val waiter = Thread {
                try { exitCode.set(process.waitFor()) }
                catch (_: Exception) { exitCode.set(-1) }
                finally { done.countDown() }
            }
            waiter.isDaemon = true
            waiter.start()
            if (!done.await(3, TimeUnit.SECONDS)) {
                process.destroy()
                throw IllegalStateException("Тайм-аут диагностики FPS")
            }
            reader.join(500)
            if (exitCode.get() != 0) throw IllegalStateException("SurfaceFlinger отказал в доступе")
            output.toString()
        } finally { process.destroy() }
    }

    fun measure(): Result {
        try {
            if (!Shizuku.pingBinder()) return Result(null, "Shizuku не запущен")
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                return Result(null, "Разреши доступ Shizuku")
            val layers = shell("dumpsys SurfaceFlinger --list")
                .lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("Display") && !it.startsWith("Layer") }
                .filterNot { it.contains("StatusBar", true) || it.contains("NavigationBar", true) ||
                    it.contains("fpsmonitor", true) || it.contains("Screenshot", true) }
                .toList()
            if (layers.isEmpty()) return Result(null, "Список слоёв недоступен")
            // Prefer a previously working layer. Other candidates are heuristic and may be UI surfaces.
            val candidates = (listOfNotNull(lastLayer) + layers.asReversed()).distinct()
            for (layer in candidates.take(35)) {
                // Single-quote shell escape prevents layer names from being interpreted as commands.
                val quoted = "'" + layer.replace("'", "'\\''") + "'"
                val data = try { shell("dumpsys SurfaceFlinger --latency $quoted") } catch (_: Exception) { continue }
                val lines = data.lineSequence().map { it.trim() }.toList()
                val timestamps = lines.drop(1).mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 3) null else parts[1].toLongOrNull()?.takeIf { it > 0L && it != Long.MAX_VALUE }
                }.distinct().sorted()
                if (timestamps.size < 8) continue
                val intervals = timestamps.zipWithNext { a, b -> b - a }
                    .filter { it in 2_000_000L..1_000_000_000L }
                if (intervals.size < 6) continue
                val median = intervals.sorted()[intervals.size / 2]
                val fps = (1_000_000_000.0 / median).toInt().coerceIn(1, 240)
                lastLayer = layer
                return Result(fps, "FPS поверхности (оценка)", layer)
            }
            return Result(null, "Нет доступных кадров; открой игру")
        } catch (e: SecurityException) {
            return Result(null, "Нет доступа к SurfaceFlinger")
        } catch (e: Exception) {
            return Result(null, e.message?.take(65) ?: "Ошибка диагностики")
        }
    }
}
