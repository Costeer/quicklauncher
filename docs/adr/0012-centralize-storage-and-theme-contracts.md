# Centralize storage and theme contracts

Room stores the destination map, content, placements, widget bindings, and versioned module records; Proto DataStore stores global preferences. Module configuration uses a namespaced type ID, schema version, and Kotlin-serialized JSON payload. The host also supplies fonts, colors, shapes, corner radii, spacing, icon rendering, and accessibility values through a shared theme contract, while modules expose only bounded overrides.
