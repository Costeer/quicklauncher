# Require Android 15 and isolate widget placements

Quicklauncher requires Android 15, API 35, so work profiles, pinned shortcuts, predictive Back, and Private Space are baseline concerns rather than optional compatibility layers. Every widget placement owns a separate Android widget ID because one ID cannot safely update several simultaneous host views or represent conflicting size options. Dormant layout configurations retain their widget placements and bindings until the user removes them.
