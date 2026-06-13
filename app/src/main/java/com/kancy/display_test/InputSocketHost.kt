package com.kancy.display_test

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
 * listening sockets at well-known paths, accepts crosvm's connection, and writes events
 * to the accepted fd on the app's behalf via [write].
 *
 * MULTI-VM: every socket set is keyed by a `vmKey` (the crosvm display service name). Each VM
 * gets its own four socket files (`/data/local/tmp/<vmKey>_input_<kind>.sock`), so two VMs
 * running at once never connect to the same listener. Without this, the second VM's connection
 * would replace the first's peer fd and silently steal its input.
 *
 * ORDERING: the listeners must exist before a VM's crosvm starts, because crosvm connects at
 * startup. So the workflow per VM is: app binds root service -> [ensureListening] for that vmKey
 * -> (re)launch crosvm with the `--input ...[path=...]` flags from [launchArgsFor] -> crosvm
 * connects. Channels not yet connected report false from [readyChannels]; writes are retried.
 */
class InputSocketHost {

    companion object {
        private const val TAG = "InputSocketHost"

        // Channel indices — the order of the arrays keyed per vmKey.
        const val MULTITOUCH = 0
        const val KEYBOARD = 1
        const val MOUSE = 2
        const val SWITCHES = 3
        const val CHANNEL_COUNT = 4

        /** Filesystem-safe socket-name suffix per channel. */
        private val KINDS = arrayOf("multitouch", "keyboard", "mouse", "switches")

        private const val ACCEPT_WAIT_MS = 3000L

        /** The four socket paths crosvm connects to for [vmKey]. Must match [launchArgsFor]. */
        fun pathsFor(vmKey: String): Array<String> =
            Array(CHANNEL_COUNT) { ch -> "/data/local/tmp/${sanitize(vmKey)}_input_${KINDS[ch]}.sock" }

        /**
         * The crosvm flags that pair with [vmKey]'s sockets. width/height of the touch device must
         * match the guest resolution so view coords scale straight onto ABS_X/ABS_Y. Substitute the
         * real guest WxH for {W}x{H}. Shown to the user as the per-VM launch snippet.
         */
        fun launchArgsFor(vmKey: String): String {
            val p = pathsFor(vmKey)
            return buildString {
                append("--input multi-touch[path=${p[MULTITOUCH]},width={W},height={H}] ")
                append("--input keyboard[path=${p[KEYBOARD]}] ")
                append("--input mouse[path=${p[MOUSE]}] ")
                append("--input switches[path=${p[SWITCHES]}]")
            }
        }

        /** Keep socket filenames sane: a vmKey is a service name, so restrict to a safe charset. */
        private fun sanitize(vmKey: String): String =
            vmKey.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")

        /** Reflectively builds a filesystem AF_UNIX address (android.system.UnixSocketAddress). */
        private fun fileSystemAddr(path: String): SocketAddress {
            val cls = Class.forName("android.system.UnixSocketAddress")
            val m = cls.getMethod("createFileSystem", String::class.java)
            return m.invoke(null, path) as SocketAddress
        }
    }

    /** One VM's listening + accepted sockets. Guarded for peer access by [writeLocks]. */
    private class ChannelSet(val paths: Array<String>) {
        val serverFds = arrayOfNulls<FileDescriptor>(CHANNEL_COUNT)
        @Volatile var peerFds = arrayOfNulls<FileDescriptor>(CHANNEL_COUNT)
        val writeLocks = Array(CHANNEL_COUNT) { Any() }
        @Volatile var closed = false
    }

    /** vmKey -> its socket set. Guarded by `this`. */
    private val sets = HashMap<String, ChannelSet>()

    /** Idempotently binds + listens on all channels for [vmKey] and starts accept threads. */
    @Synchronized
    fun ensureListening(vmKey: String) {
        if (sets.containsKey(vmKey)) return
        val set = ChannelSet(pathsFor(vmKey))
        for (ch in 0 until CHANNEL_COUNT) {
            val path = set.paths[ch]
            try {
                File(path).delete() // stale socket file from a previous run
                val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
                Os.bind(fd, fileSystemAddr(path))
                Os.listen(fd, 1)
                Os.chmod(path, 0b111_111_111) // 0777: crosvm (root) connects
                set.serverFds[ch] = fd
                startAcceptThread(vmKey, set, ch)
                Log.i(TAG, "listening: $path")
            } catch (e: Exception) {
                Log.e(TAG, "failed to listen on $path", e)
            }
        }
        sets[vmKey] = set
    }

    private fun startAcceptThread(vmKey: String, set: ChannelSet, ch: Int) {
        val server = set.serverFds[ch] ?: return
        Thread({
            // Accept in a loop, not once: a VM's crosvm may stop and restart while this app keeps
            // running. Each new connection replaces the previous (now-dead) peer FOR THE SAME VM,
            // so write() always hands the live one. Different vmKeys have separate listeners, so a
            // second VM never lands here. The loop ends only when close() shuts the listener down.
            while (!set.closed) {
                val peer = try {
                    Os.accept(server, null /* peerAddress */)
                } catch (e: Exception) {
                    if (set.closed) break
                    Log.e(TAG, "accept failed on $vmKey channel $ch", e)
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    continue
                }
                val old = set.peerFds[ch]
                set.peerFds[ch] = peer
                old?.let { runCatching { Os.close(it) } } // release the stale connection's fd
                Log.i(TAG, "crosvm connected: $vmKey channel $ch (${set.paths[ch]})")
            }
        }, "InputAccept-$vmKey-$ch").apply { isDaemon = true }.start()
    }

    /**
     * Per-channel connection status for [vmKey]. Ensures listening first, then waits briefly so a
     * channel crosvm connects just after the display binder still reports ready.
     */
    fun readyChannels(vmKey: String): BooleanArray {
        ensureListening(vmKey)
        val set = synchronized(this) { sets[vmKey] } ?: return BooleanArray(CHANNEL_COUNT)
        val deadline = System.currentTimeMillis() + ACCEPT_WAIT_MS
        while (System.currentTimeMillis() < deadline && set.peerFds.any { it == null }) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        return BooleanArray(CHANNEL_COUNT) { ch -> set.peerFds[ch] != null }
    }

    /**
     * Writes pre-encoded evdev bytes to [vmKey]'s channel socket, here in the root process
     * (permissive domain) so the app never touches the socket. Returns false if the VM/channel
     * isn't connected or the write fails. Serialized per channel; consults the live peer fd each
     * call so a crosvm reconnect is picked up automatically.
     */
    fun write(vmKey: String, channel: Int, data: ByteArray): Boolean {
        if (channel !in 0 until CHANNEL_COUNT || data.isEmpty()) return false
        val set = synchronized(this) { sets[vmKey] } ?: return false
        return try {
            synchronized(set.writeLocks[channel]) {
                val fd = set.peerFds[channel] ?: return false
                var off = 0
                while (off < data.size) {
                    val n = Os.write(fd, data, off, data.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "write vm=$vmKey ch=$channel failed: ${e.message}")
            false
        }
    }

    /** Closes [vmKey]'s peer + server fds and removes its socket files. */
    @Synchronized
    fun close(vmKey: String) {
        val set = sets.remove(vmKey) ?: return
        closeSet(set)
    }

    /** Closes every VM's sockets (e.g. on root-service unbind). */
    @Synchronized
    fun closeAll() {
        sets.values.forEach { closeSet(it) }
        sets.clear()
    }

    private fun closeSet(set: ChannelSet) {
        set.closed = true // stop accept loops; closing the server fd below unblocks Os.accept()
        for (ch in 0 until CHANNEL_COUNT) {
            set.peerFds[ch]?.let { runCatching { Os.close(it) } }
            set.serverFds[ch]?.let { runCatching { Os.close(it) } }
            set.peerFds[ch] = null
            set.serverFds[ch] = null
            runCatching { File(set.paths[ch]).delete() }
        }
    }
}
