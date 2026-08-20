# Limit first-release migration to Quicklauncher data

The first release handles its own Room and module-schema upgrades, Android backup restoration, and versioned Quicklauncher archive import. It does not promise live import from installed launchers or adapters for proprietary backup formats because Android exposes no standard workspace migration contract. The backup UI presents available Quicklauncher snapshots visually so users can inspect and restore their own configurations.
