package com.kancy.display_test

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.net.SocketAddress

/**
 * Root-side host for crosvm input sockets. Runs inside the libsu root process (uid=0).
 *
 * crosvm receives input by reading 8-byte evdev records from a unix socket given via
 * `--input <kind>[path=...]`. For a filesystem path, crosvm *connects* to it (see crosvm
 * device_helpers.rs IntoUnixStream), so we must be *listening* first. This object binds
 * listening sockets at well-known paths, accepts crosvm's connection, and hands the host
 * end (the accepted fd) back to the app via [getSockets] so the app can write events
 * directly with [EvdevEncoder] (no per-event binder round-trip).
 *
 * ORDERING: the listeners must exist before crosvm starts, because crosvm connects at
 * startup. So the workflow for input is: app binds root service -> [ensureListening] ->
 * (re)launch crosvm with the `--input ...[path=...]` flags from [LAUNCH_ARGS] -> crosvm
 * connects. Channels not yet connected return null from [getSockets]; the caller retries.
 */
class InputSocketHost {

    companion object {
        private const val TAG = "InputSocketHost"

        // Channel indices — the order of the array returned by getSockets().
        const val MULTITOUCH = 0
        const val KEYBOARD = 1
        const val MOUSE = 2
        const val SWITCHES = 3
        const val CHANNEL_COUNT = 4

        // Well-known filesystem socket paths crosvm connects to. Must match LAUNCH_ARGS.
        val PATHS = arrayOf(
            "/data/local/tmp/crosvm_input_multitouch.sock",
            "/data/local/tmp/crosvm_input_keyboard.sock",
            "/data/local/tmp/crosvm_input_mouse.sock",
            "/data/local/tmp/crosvm_input_switches.sock",
        )

        /**
         * The crosvm flags that pair with these sockets. width/height of the touch device must
         * match the guest resolution so view coords scale straight onto ABS_X/ABS_Y.
         * Substitute the real guest WxH for {W}x{H}.
         */
        val LAUNCH_ARGS: String
            get() = buildString {
                append("--input multi-touch[path=${PATHS[MULTITOUCH]},width={W},height={H}] ")
                append("--input keyboard[path=${PATHS[KEYBOARD]}] ")
                append("--input mouse[path=${PATHS[MOUSE]}] ")
                append("--input switches[path=${PATHS[SWITCHES]}]")
            }

        private const val ACCEPT_WAIT_MS = 3000L

        /** Reflectively builds a filesystem AF_UNIX address (android.system.UnixSocketAddress). */
        private fun fileSystemAddr(path: String): SocketAddress {
            val cls = Class.forName("android.system.UnixSocketAddress")
            val m = cls.getMethod("createFileSystem", String::class.java)
            return m.invoke(null, path) as SocketAddress
        }
    }

    private val serverFds = arrayOfNulls<FileDescriptor>(CHANNEL_COUNT)
    @Volatile private var peerFds = arrayOfNulls<FileDescriptor>(CHANNEL_COUNT)
    private var started = false
    @Volatile private var closed = false

    /** Idempotently binds + listens on all channels and starts background accept threads. */
    @Synchronized
    fun ensureListening() {
        if (started) return
        closed = false
        for (ch in 0 until CHANNEL_COUNT) {
            val path = PATHS[ch]
            try {
                File(path).delete() // stale socket file from a previous run
                val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
                Os.bind(fd, fileSystemAddr(path))
                Os.listen(fd, 1)
                Os.chmod(path, 0b111_111_111) // 0777: crosvm (root) connects
                serverFds[ch] = fd
                startAcceptThread(ch)
                Log.i(TAG, "listening: $path")
            } catch (e: Exception) {
                Log.e(TAG, "failed to listen on $path", e)
            }
        }
        started = true
    }

    private fun startAcceptThread(ch: Int) {
        val server = serverFds[ch] ?: return
        Thread({
            // Accept in a loop, not once: crosvm may stop and restart while this app keeps running.
            // Each new connection replaces the previous (now-dead) peer so getSockets() always
            // hands back the live one. The loop ends only when close() shuts the listener down.
            while (!closed) {
                val peer = try {
                    Os.accept(server, null /* peerAddress */)
                } catch (e: Exception) {
                    if (closed) break
                    Log.e(TAG, "accept failed on channel $ch", e)
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    continue
                }
                val old = peerFds[ch]
                peerFds[ch] = peer
                old?.let { runCatching { Os.close(it) } } // release the stale connection's fd
                Log.i(TAG, "crosvm connected on channel $ch (${PATHS[ch]})")
            }
        }, "InputAccept-$ch").apply { isDaemon = true }.start()
    }

    /**
     * Returns dup'd host-end FDs as [ParcelFileDescriptor]s, indexed by channel. Elements are
     * null for channels crosvm has not connected to yet. Waits briefly for connections.
     */
    fun getSockets(): Array<ParcelFileDescriptor?> {
        ensureListening()
        val deadline = System.currentTimeMillis() + ACCEPT_WAIT_MS
        while (System.currentTimeMillis() < deadline && peerFds.any { it == null }) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        return Array(CHANNEL_COUNT) { ch ->
            peerFds[ch]?.let { fd ->
                try {
                    ParcelFileDescriptor.dup(fd) // app gets its own fd to the same socket
                } catch (e: Exception) {
                    Log.e(TAG, "dup failed on channel $ch", e)
                    null
                }
            }
        }
    }

    /** Closes all peer + server fds and removes the socket files. */
    @Synchronized
    fun close() {
        closed = true // stop accept loops; closing the server fd below unblocks Os.accept()
        for (ch in 0 until CHANNEL_COUNT) {
            peerFds[ch]?.let { runCatching { Os.close(it) } }
            serverFds[ch]?.let { runCatching { Os.close(it) } }
            peerFds[ch] = null
            serverFds[ch] = null
            runCatching { File(PATHS[ch]).delete() }
        }
        started = false
    }
}
