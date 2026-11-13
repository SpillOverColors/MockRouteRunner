# Pedometer adapter module (MIT)

LSPatch-embedded Xposed legacy module. Hooks `SensorManager.registerListener`
inside the patched app process and wraps step-counter/step-detector listeners.
It injects `SensorEvent` values at a configurable 1–300 spm cadence
(default 100–130), with finite min <= max validation.

- Scope: only the patched app process; no system hooks.
- Control: loopback only on a package-derived port in `39000–39999`
  (FNV-1a of the target package), commands `AUTH`, `HELLO <package>`,
  `START <package> <min> <max> <seed>`, `PAUSE`, `RESUME`, `STOP`, `STATUS`.
- Bounded: at most 2 live connections, socket timeouts, 192-byte line limit,
  12 commands/connection. The static session key is coordination, not security.
- Target isolation: `START`/`HELLO` must match the current process package;
  only the main process starts the server. The 1000-port hash cannot guarantee
  uniqueness: two packages may collide, and multiple processes of the same
  package share the port. Only the expected sensor-consumer (main) process is
  supported; a secondary-process consumer is not controllable.
- `HELLO` is a readiness/target check, not authentication. Any local process
  that learns the package and key can attempt the handshake.
- PAUSE/STOP invalidate queued Handler deliveries by engine generation; each
  proxy also has a registration gate so `unregisterListener` invalidates its
  already-queued deliveries. Motion phase advances by actual active elapsed
  time, not planned interval.
- No remote listener; no route UI modification.
- The Xposed API is provided by LSPatch at runtime; only our classes are packaged.
- Offline tests: `tools\pedometer-module\test.ps1` (generation cancellation,
  actual active time, counter floor, multi-sensor/manager mapping).
