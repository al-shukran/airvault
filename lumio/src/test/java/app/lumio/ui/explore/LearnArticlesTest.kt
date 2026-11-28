package app.lumio.ui.explore

import app.lumio.data.provider.ProviderCatalog
import app.lumio.ui.settings.providerCoverage
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class LearnArticlesTest {
    @Test
    fun theReadTimeIsCountedFromTheWordsAndIsNeverBelowOneMinute() {
        assertEquals("1 min read", LearnArticle("a", "A", listOf("one two three")).readTime)
        assertEquals(3, LearnArticle("a", "A", listOf(List(401) { "w" }.joinToString(" "))).minutes)
    }

    @Test
    fun everyArticleHasCopyAndAUniqueId() {
        assertEquals(LearnArticles.ALL.size, LearnArticles.ALL.map { it.id }.toSet().size)
        LearnArticles.ALL.forEach { assertTrue(it.paragraphs.isNotEmpty()); assertNotNull(LearnArticles.find(it.id)) }
    }

    @Test
    fun everyDataAndPriceProviderStatesWhatItSupplies() {
        (ProviderCatalog.dataProviders() + ProviderCatalog.priceProviders()).forEach { assertNotNull(it.id, providerCoverage(it.id)) }
    }
}
