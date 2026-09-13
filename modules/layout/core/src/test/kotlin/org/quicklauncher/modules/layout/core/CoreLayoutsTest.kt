package org.quicklauncher.modules.layout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.CodecResult
import org.quicklauncher.modules.layout.core.generated.CoreLayoutRegistry
import org.quicklauncher.testing.contracts.RegisteredContributionContractSuite

class CoreLayoutsTest {
    @Test
    fun `every layout satisfies the reusable public contribution contract suite`() {
        RegisteredContributionContractSuite.verify(CoreLayoutRegistry.entries)
    }

    @Test
    fun `grid configuration round trips and rejects out of range columns`() {
        val value = GridLayoutConfiguration(6)
        assertEquals(CodecResult.Decoded(value), GridLayoutCodec.decode(GridLayoutCodec.encode(value)))
        assertTrue(GridLayoutCodec.decode(org.quicklauncher.contracts.contribution.EncodedConfiguration.of("columns=0")) is CodecResult.Failed)
    }

    @Test
    fun `single block codec has one stable representation`() {
        assertEquals(
            CodecResult.Decoded(SingleBlockLayoutConfiguration),
            SingleBlockLayoutCodec.decode(SingleBlockLayoutCodec.encode(SingleBlockLayoutConfiguration)),
        )
    }
}
