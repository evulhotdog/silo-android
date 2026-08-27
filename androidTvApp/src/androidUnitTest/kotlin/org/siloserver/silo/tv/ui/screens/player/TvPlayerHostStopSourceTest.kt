package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source contract for host-stop parking across [TvPlayerScreen] and
 * [TvPlayerViewModel].
 *
 * Android TV delivers `onStop` when the device is powered off by remote while
 * the process keeps living behind the dark panel. Solo playback must park its
 * server session there (`suspendSessionForHostStop`) so the admin "now
 * playing" surface drops the entry immediately instead of our 10s progress
 * heartbeat advertising a paused session forever. The screen hook stays
 * Watch-Together-exempt (room liveness is the room's own contract) and inside
 * the existing PiP guard (PiP keeps playing and reporting).
 *
 * Mirrors TvPictureInPictureSourceTest's read-the-source style: this pins the
 * wiring, while PlaybackSessionLifecycleTest pins the lifecycle behavior.
 */
class TvPlayerHostStopSourceTest {
    private val player = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()
    private val viewModel = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()

    @Test
    fun onStopParksTheServerSessionForSoloPlayback() {
        val lifecycleBranch = player
            .substringAfter("Lifecycle.Event.ON_PAUSE,")
            .substringBefore("Lifecycle.Event.ON_RESUME")
        assertTrue(lifecycleBranch.contains("event == Lifecycle.Event.ON_STOP"))
        assertTrue(lifecycleBranch.contains("roomController == null"))
        assertTrue(lifecycleBranch.contains("viewModel.onHostActivityStopped("))
        assertTrue(viewModel.contains("suspendSessionForHostStop("))
    }

    @Test
    fun transportEntriesWakeAParkedSessionInsteadOfDrivingIt() {
        for (entry in listOf("fun onPlayPause(", "fun setPaused(", "fun seekImmediate(", "fun onSkipBy(")) {
            val body = viewModel.substringAfter(entry).substringBefore("\n    fun ")
            assertTrue(
                body.contains("beginWakeFromHostStop("),
                "$entry must route through beginWakeFromHostStop",
            )
        }
    }
}
