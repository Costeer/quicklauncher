# Use a connected spatial destination map

Destinations occupy unique cells on an unbounded integer grid and connect through cardinal edges. The editor prevents operations that would disconnect any destination from the start destination. Layout content receives a gesture until it reaches its scroll boundary, then hands the drag to spatial navigation; users may choose edge-only navigation instead. The runtime composes the current destination and its gesture-preview neighbors rather than keeping the whole map active.
