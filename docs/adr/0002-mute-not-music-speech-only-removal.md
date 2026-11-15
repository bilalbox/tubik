# Music Removal is realtime, speech-only, and mutes on failure

Music Removal runs as audio plays, using a small streaming speech-enhancement model (FastEnhancer, MIT: the 16 kHz Small model trained on DNS-Challenge noise, 813 KB, with the 122 KB Tiny model as the fallback when the device can't keep up). It keeps **speech only**: sound effects are lost along with the music. If the model fails to load or processing falls behind (below roughly 1.5× the playback rate), the audio is **muted**, never played unprocessed.

## Considered options

- **Keep sound effects (DnR models such as HTDemucs or TIGER).** Rejected for v1: the music-remover bakeoff (`music-remover/bakeoff/RESULTS.md`, 2026-10-26) found that the separation models that keep sound effects are far from realtime on CPU, and low-end phones like the Galaxy A56 are a target.
- **Pre-process downloads into a cleaned file.** Deferred: it doesn't cover browsing.
- **Run the 48 kHz FastEnhancer models.** Replaced (2026-11-16): the pipeline only keeps the speech band, and on speech-plus-music test mixes the 16 kHz models matched the 48 kHz ones on speech, rejected instrumental music far better (about -38 dB against -23 dB for Small), and cost under half the CPU on a desktop core.
- **Fall back to the original audio when overloaded.** Rejected: the purpose of the feature is that music is never heard, so silence is the safe failure.

## Consequences

- Playback speed is capped at 1.5× while Music Removal is on.
- The model sits behind one seam, so it can be swapped (FastEnhancer-S, DPDFNet 16 kHz/48 kHz) once it has been measured on real low-end hardware; this hasn't been tested on an A56 yet.
- Output is 16 kHz mono (speech band up to about 7 kHz); Android resamples it for the device.
- A VAD gate after the model mutes everything that isn't speech. It decides one hop ahead of the audio so word onsets aren't clipped, which leaves audio about 64 ms behind video.
- Toggling Music Removal while something is playing keeps that 16 kHz mono format until the next video starts.
