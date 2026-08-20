# Use build-time modules and typed slots

Layout and block authors extend Quicklauncher inside the codebase. A generated compile-time registry validates module IDs, metadata, configuration schemas, factories, and capabilities. Layouts expose typed slots, and blocks may occupy only compatible slots. We reject runtime third-party plugins and unrestricted recursive composition because either choice would freeze a public compatibility contract before the launcher model has matured.
