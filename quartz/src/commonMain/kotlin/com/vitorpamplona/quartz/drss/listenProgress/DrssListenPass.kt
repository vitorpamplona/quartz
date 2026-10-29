/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.drss.listenProgress

/**
 * drss.io's rule for counting a play of one episode, fed the playhead as it moves: a play counts
 * when playback, not a seek, carries the playhead across [PLAY_FRACTION] of the episode, and the
 * next one counts only after the listener has gone back below [REARM_FRACTION]. Start a new pass
 * when a new source loads.
 */
class DrssListenPass {
    private var last = -1.0
    private var armed = true
    private var seeked = false

    /** The listener moved the playhead by hand: the next update cannot count. */
    fun seeked() {
        seeked = true
    }

    /**
     * Folds one playhead update in; true when this one counts as a play. The first update only
     * records where the pass starts, so loading an episode near its end counts nothing.
     */
    fun update(
        positionInSeconds: Double,
        durationInSeconds: Double,
        isPlaying: Boolean,
    ): Boolean {
        if (!(durationInSeconds > 0) || positionInSeconds.isNaN()) return false
        if (last < 0) {
            last = positionInSeconds
            return false
        }
        val previous = last
        last = positionInSeconds
        if (positionInSeconds < REARM_FRACTION * durationInSeconds) armed = true
        if (seeked) {
            seeked = false
            return false
        }
        val mark = PLAY_FRACTION * durationInSeconds
        if (!isPlaying || !armed || previous >= mark || positionInSeconds < mark) return false
        armed = false
        return true
    }

    companion object {
        const val PLAY_FRACTION = 0.9
        const val REARM_FRACTION = 0.1

        /** How often drss saves the playhead to relays while an episode plays, besides pause and end. */
        const val RELAY_SAVE_INTERVAL_SECONDS = 60L
    }
}
