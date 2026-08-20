# Separate content from placement and system access

The launcher host owns destination identity, semantic content, persistence, and access to Android launcher APIs. Modules receive typed host services and own only their visual placement records and versioned settings. This keeps folders, favorites, and shortcuts intact across layout changes while preventing each layout from inventing its own package, profile, widget, and storage behavior. Widget placements are the exception to shared content identity because each one needs a distinct Android widget binding.
