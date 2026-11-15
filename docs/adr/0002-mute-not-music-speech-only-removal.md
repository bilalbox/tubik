# Music Removal is realtime, speech-only, and mutes on failure

Music Removal runs as audio plays, using a small streaming speech-enhancement model (FastEnhancer-T first: MIT, 148 KB, 48 kHz mono). It keeps **speech only**: sound effects are lost along with the music. If the model fails to load or processing falls behind (below roughly 1.5× the playback rate), the audio is **muted**, never played unprocessed.

## Considered options

- **Keep sound effects (DnR models such as HTDemucs or TIGER).** Rejected for v1: the music-remover bakeoff (`music-remover/bakeoff/RESULTS.md`, 2026-10-26) found that the separation models that keep sound effects are far from realtime on CPU, and low-end phones like the Galaxy A56 are a target.
- **Pre-process downloads into a cleaned file.** Deferred: it doesn't cover browsing.
- **Fall back to the original audio when overloaded.** Rejected: the purpose of the feature is that music is never heard, so silence is the safe failure.

## Consequences

- Playback speed is capped at 1.5× while Music Removal is on.
- The model sits behind one seam, so it can be swapped (FastEnhancer-S, DPDFNet 16 kHz/48 kHz) once it has been measured on real low-end hardware; this hasn't been tested on an A56 yet.
- Output is mono.
