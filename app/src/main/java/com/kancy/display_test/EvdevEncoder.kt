package com.kancy.display_test

import android.view.MotionEvent
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes input into the wire format crosvm's virtio-input devices read from a socket.
 *
 * Wire format (must match linux_input_sys::virtio_input_event in crosvm): a stream of
 * fixed 8-byte little-endian records `(type: u16, code: u16, value: i32)`. crosvm connects
 * to the socket given via `--input multi-touch[path=...]` / `keyboard[...]` / `mouse[...]` /
 * `switches[...]` and consumes these records directly.
 *
 * This is a faithful port of the event sequences in
 * packages/modules/Virtualization/.../VirtualMachine.java (sendMultiTouchEventInternal,
 * sendMouseEventInternal, sendKeyEvent, sendTabletModeEvent). It does NOT go through
 * ICrosvmAndroidDisplayService — that binder lives inside crosvm and cannot inject input.
 *
 * Each method takes the OutputStream of the per-device socket (the host end of the
 * connection crosvm made). The caller owns the socket lifecycle.
 */
object EvdevEncoder {

    // from include/uapi/linux/input-event-codes.h
    private const val EV_SYN: Short = 0x00
    private const val EV_KEY: Short = 0x01
    private const val EV_REL: Short = 0x02
    private const val EV_ABS: Short = 0x03
    private const val EV_SW: Short = 0x05

    private const val SYN_REPORT: Short = 0x00

    private const val REL_X: Short = 0x00
    private const val REL_Y: Short = 0x01
    private const val REL_HWHEEL: Short = 0x06
    private const val REL_WHEEL: Short = 0x08

    private const val ABS_X: Short = 0x00
    private const val ABS_Y: Short = 0x01
    private const val ABS_MT_SLOT: Short = 0x2f
    private const val ABS_MT_POSITION_X: Short = 0x35
    private const val ABS_MT_POSITION_Y: Short = 0x36
    private const val ABS_MT_TRACKING_ID: Short = 0x39

    private const val BTN_LEFT: Short = 0x110
    private const val BTN_RIGHT: Short = 0x111
    private const val BTN_MIDDLE: Short = 0x112
    private const val BTN_TOUCH: Short = 0x14a

    private const val SW_LID: Short = 0x00
    private const val SW_TABLET_MODE: Short = 0x01

    private data class Event(val type: Short, val code: Short, val value: Int)

    private fun write(sink: OutputStream, events: List<Event>) {
        val buf = ByteBuffer.allocate(8 * events.size).order(ByteOrder.LITTLE_ENDIAN)
        for (e in events) {
            buf.putShort(e.type)
            buf.putShort(e.code)
            buf.putInt(e.value)
        }
        // One write per event batch so a SYN_REPORT is never split across writes.
        sink.write(buf.array())
        sink.flush()
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    /** @param scanCode Linux evdev KEY_* code (from [KeyCodeMapper]). */
    fun sendKey(sink: OutputStream, scanCode: Short, down: Boolean) {
        write(
            sink,
            listOf(
                Event(EV_KEY, scanCode, if (down) 1 else 0),
                Event(EV_SYN, SYN_REPORT, 0),
            ),
        )
    }

    // ── Mouse (relative) ──────────────────────────────────────────────────────

    /** Forwards a mouse [MotionEvent] to a `--input mouse[...]` device. */
    fun sendMouse(sink: OutputStream, event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> write(
                sink,
                listOf(
                    Event(EV_REL, REL_X, event.x.toInt()),
                    Event(EV_REL, REL_Y, event.y.toInt()),
                    Event(EV_SYN, SYN_REPORT, 0),
                ),
            )

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val code = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> BTN_LEFT
                    MotionEvent.BUTTON_SECONDARY -> BTN_RIGHT
                    MotionEvent.BUTTON_TERTIARY -> BTN_MIDDLE
                    else -> return
                }
                val pressed = event.action == MotionEvent.ACTION_BUTTON_PRESS
                write(
                    sink,
                    listOf(
                        Event(EV_KEY, code, if (pressed) 1 else 0),
                        Event(EV_SYN, SYN_REPORT, 0),
                    ),
                )
            }

            MotionEvent.ACTION_SCROLL -> {
                val sx = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt()
                val sy = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
                when {
                    sx != 0 -> write(
                        sink,
                        listOf(Event(EV_REL, REL_HWHEEL, sx), Event(EV_SYN, SYN_REPORT, 0)),
                    )
                    sy != 0 -> write(
                        sink,
                        listOf(Event(EV_REL, REL_WHEEL, sy), Event(EV_SYN, SYN_REPORT, 0)),
                    )
                }
            }
            // ACTION_UP / ACTION_DOWN are handled via ACTION_BUTTON_PRESS/RELEASE.
        }
    }

    // ── Multi-touch ─────────────────────────────────────────────────────────

    /**
     * Forwards a touch [MotionEvent] to a `--input multi-touch[...]` device.
     * @param scaleX guestWidth / viewWidth  (mirror of TerminalApp's touchScale, per-axis)
     * @param scaleY guestHeight / viewHeight
     *
     * The multi-touch device's ABS_X/ABS_Y range must equal the guest resolution passed via
     * `--input multi-touch[width=guestW,height=guestH]`, so view coords scale straight to it.
     */
    fun sendMultiTouch(sink: OutputStream, event: MotionEvent, scaleX: Float, scaleY: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val events = ArrayList<Event>(event.pointerCount * 6 + 1)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = (event.getX(i) * scaleX).toInt()
                    val y = (event.getY(i) * scaleY).toInt()
                    events.add(Event(EV_ABS, ABS_MT_SLOT, id))
                    events.add(Event(EV_ABS, ABS_MT_TRACKING_ID, id))
                    events.add(Event(EV_ABS, ABS_MT_POSITION_X, x))
                    events.add(Event(EV_ABS, ABS_MT_POSITION_Y, y))
                    events.add(Event(EV_ABS, ABS_X, x))
                    events.add(Event(EV_ABS, ABS_Y, y))
                }
                events.add(Event(EV_SYN, SYN_REPORT, 0))
                write(sink, events)
            }

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val down = event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = (event.getX(idx) * scaleX).toInt()
                val y = (event.getY(idx) * scaleY).toInt()
                write(
                    sink,
                    listOf(
                        Event(EV_KEY, BTN_TOUCH, if (down) 1 else 0),
                        Event(EV_ABS, ABS_MT_SLOT, id),
                        Event(EV_ABS, ABS_MT_TRACKING_ID, if (down) id else -1),
                        Event(EV_ABS, ABS_MT_POSITION_X, x),
                        Event(EV_ABS, ABS_MT_POSITION_Y, y),
                        Event(EV_ABS, ABS_X, x),
                        Event(EV_ABS, ABS_Y, y),
                        Event(EV_SYN, SYN_REPORT, 0),
                    ),
                )
            }
        }
    }

    // ── Switches ──────────────────────────────────────────────────────────────

    /** Forwards tablet/desktop mode to a `--input switches[...]` device. */
    fun sendTabletMode(sink: OutputStream, tabletMode: Boolean) {
        write(
            sink,
            listOf(
                Event(EV_SW, SW_TABLET_MODE, if (tabletMode) 1 else 0),
                Event(EV_SYN, SYN_REPORT, 0),
            ),
        )
    }

    /** Forwards lid open/close to a `--input switches[...]` device. */
    fun sendLid(sink: OutputStream, close: Boolean) {
        write(
            sink,
            listOf(
                Event(EV_SW, SW_LID, if (close) 1 else 0),
                Event(EV_SYN, SYN_REPORT, 0),
            ),
        )
    }
}
