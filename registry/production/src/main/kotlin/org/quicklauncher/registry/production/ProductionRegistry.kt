package org.quicklauncher.registry.production

import org.quicklauncher.modules.block.core.generated.CoreBlockRegistry
import org.quicklauncher.modules.command.core.generated.CoreCommandRegistry
import org.quicklauncher.modules.layout.core.generated.CoreLayoutRegistry
import org.quicklauncher.modules.search.core.generated.CoreSearchRegistry
import org.quicklauncher.modules.templates.core.generated.CoreTemplateRegistry
import org.quicklauncher.registry.annotations.AggregateContributionRegistry
import org.quicklauncher.contracts.contribution.ContributionRegistry
import org.quicklauncher.registry.production.generated.ProductionContributionRegistry

@AggregateContributionRegistry(
    fragments = [
        CoreLayoutRegistry::class,
        CoreBlockRegistry::class,
        CoreCommandRegistry::class,
        CoreSearchRegistry::class,
        CoreTemplateRegistry::class,
    ],
    packageName = "org.quicklauncher.registry.production.generated",
    registryName = "ProductionContributionRegistry",
)
object ProductionRegistry

/** Stable host-facing reference. Concrete contribution modules remain aggregation details. */
val productionRegistry: ContributionRegistry = ProductionContributionRegistry
