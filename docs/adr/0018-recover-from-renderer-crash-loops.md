# Recover from renderer crash loops

Module rendering uses best-effort error boundaries and supervised jobs, but in-process Compose code cannot promise perfect isolation. If restoring an instance repeatedly crashes startup, the next launch quarantines that instance, preserves its data, and substitutes the core safe layout. Users can inspect, reset, or remove the failed instance without clearing all launcher data.
