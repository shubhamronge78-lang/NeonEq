# Changelog

## v2.0.2 (Build #58) — volume + visualizer fixes

### Fixed: EQ toggle ON was quieter than OFF

**Root cause:** `EqualizerEngine.computePreampDb()` subtracted preamp gain for
*every* active effect — BassBoost (~1 dB per 100 strength), Virtualizer
(~0.5 dB per 100) and LoudnessEnhancer (~1 dB per 100 mB). With the default
150/150/150 settings that applied a constant **−2 dB to all band levels**
whenever the EQ was enabled. Since LoudnessEnhancer at 150 only adds +1.5 dB,
the net effect of turning the EQ on was *quieter* audio — and compensating
loudness defeated the point of a loudness slider entirely.

**Fix:**
- **LoudnessEnhancer and Virtualizer are no longer compensated.** Loudness is
  the user explicitly requesting gain; the virtualizer is a stereo-width
  effect with negligible level gain.
- **BassBoost** (the actual distortion source on small speakers) is still
  compensated but at half the old rate: ~0.5 dB per 100 strength. Default
  150 costs nothing; maximum 300 trims ~1.5 dB.
- **Band boosts** now count toward compensation — but only above +6 dB, at
  half the excess, computed from the maximum positive band level.
- Total compensation cap lowered from −6 dB to **−4 dB**.

**Result:** default settings are now transparent (0 dB preamp) — the EQ toggle
no longer changes perceived volume, and loudness actually adds the gain it
promises. Distortion protection still engages for extreme settings
(bass 300, bands pushed past +6 dB).

### Fixed: Visualizer stalling and freezing

**Root cause 1 — MIUI silently kills the capture:** the `Visualizer(0)`
waveform callbacks stop without any error after screen-off, track changes or
output switches. The object stays "enabled" but delivers nothing, and nothing
ever re-attached it.

**Fix:** a **self-heal watchdog** in the session poller (`scanForActiveSessions()`,
1.5 s cadence). If the EQ is enabled, audio is playing (active playback configs
present) and no waveform has arrived for **> 4 s**, the capture is re-attached
on the audio executor. Repeat failures back off progressively (4 s × retry
count, capped ≈ 60 s) so devices that never deliver data aren't hammered.
`lastWaveformAt` and `visRetryCount` are `@Volatile` — they are written by the
audio capture thread and the session-poll thread.

**Root cause 2 — frozen frame when the toggle is OFF:** `setEnabled(false)`
disables the capture, so callbacks stop — but the UI kept rendering the *last
stale buffer* forever. The `active` parameter passed to `VisualizerBars` was
ignored in the render path.

**Fix:** `VisualizerBars` now gates on `live = active && waveform.isNotEmpty()`.
When the EQ is off (or no capture data exists), the idle breathing pulse
renders instead of a frozen spectrum. Applies to all three styles (Bars, Wave,
Circle) via the shared `ampFor()` amplitude extractor.

### Prior release — v2.0.1 (Build #57)
- New band canvas: smooth neon bezier curve through band tops, dashed 0 dB
  reference line, active-band glow + floating value bubble while dragging.
- Zero steady-state allocations in all redraw paths: Paths, Paints, Brushes
  and buffers hoisted with `remember()`; unpositioned brushes size to the
  drawn geometry (v56 removed per-band Paint churn, v57 removed 32–41
  gradient-brush allocations per frame in the visualizer).
