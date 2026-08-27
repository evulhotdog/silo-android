# Host-stop session lifecycle (Android TV power-off)

When an Android TV device is powered off by remote (or otherwise put to sleep),
the framework delivers `onPause`/`onStop` to the player Activity while the
process keeps living behind the dark panel. The screen-out composition survives,
and before this contract existed the backgrounded player kept advertising a
live session forever: the shared progress reporter POSTed every 10 seconds with
`isPaused = true`, and each progress write refreshes server-side liveness
(`SessionManager.UpdateProgress` sets `LastActivityAt`). The reaper grants
paused sessions a 30-minute grace (`DefaultPausedSessionGrace`,
`internal/playback/session.go`; mirrored by `internal/worker/cleanup.go`), so a
parked-but-pinging entry never aged out of the admin "now playing" surface.

## Contract

Solo video playback treats host `onStop` as end-of-viewing for **server**
purposes while keeping the local screen intact:

1. The final position is sampled exactly like the Back path
   (`resolveTvPlaybackExitSnapshot`) and written through
   `FinalPlaybackPositionWriter`.
2. `PlaybackSessionLifecycle.suspendSessionForHostStop` flushes the durable
   progress snapshot, cancels reporter/recovery jobs, issues the explicit
   stop request, records a `ParkedPlayback`, and publishes
   `SessionState.Suspended`. Ownership context (`lastAdoptedSessionId`,
   `lastStartParams`) is deliberately preserved.
3. The admin surface drops the entry immediately because the park sends a real
   user-stop instead of waiting out the paused-grace reap.
4. While parked, every adoption path is refused (guard inside
   `adoptActiveSessionIfCurrent`) so a start still in flight cannot resurrect
   capacity behind a dark screen.
5. Transport intent against the parked session (`onPlayPause` un-pausing,
   `setPaused(false)`, `seekImmediate`, `onSkipBy`) wakes it:
   `renewSuspendedSession` emits the captured renewal, the existing mid-play
   404-recovery plumbing re-plans via `loadContent(recoveryStartParams…)`, and
   `loadContent`'s ready state publishes `isPaused = false` — i.e. pressing
   Play resumes at the parked position under a fresh server session.

## Exemptions

- **Picture-in-picture**: playback must continue reporting, unchanged.
- **Watch Together rooms**: room liveness is the room's own contract; the
  screen skips parking entirely while a room controller is bound.

## Where things live

- Shared primitives: `android-shared/.../player/PlaybackSessionLifecycle.kt`
  (`suspendSessionForHostStop`, `renewSuspendedSession`, `suspendedPlayback`,
  `ParkedPlayback`, `SessionState.Suspended`).
- TV wiring: `TvPlayerScreen.kt` lifecycle observer (ON_STOP branch) and
  `TvPlayerViewModel.kt` (`onHostActivityStopped`, `beginWakeFromHostStop`,
  wake guards on transport entries).
- Behavioral proof: `PlaybackSessionLifecycleTest`
  (park/renew/refusal/consumption cases).
- Wiring proof: `TvPlayerHostStopSourceTest`.

## Known edge

Waking from scrub/skip input re-plans at the requested target rather than the
pre-scrub frame; a viewer who scrubs against a parked screen gets that target.
The old suspended-session snapshot (`params`) survives a failed wake reload
only until the next adoption clears it — see `ParkedPlayback.startParams`.
