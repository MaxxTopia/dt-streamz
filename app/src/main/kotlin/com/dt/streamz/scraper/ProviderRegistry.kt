package com.dt.streamz.scraper

/**
 * The local provider list is fixed by the APK, while availability is allowed
 * to change remotely through ScraperConfigLoader. Keeping the filter here
 * means every screen observes the same kill switch without duplicating config
 * logic in individual tabs.
 */
class ProviderRegistry(
    providers: List<Provider>,
    private val isProviderEnabled: (String) -> Boolean = { true },
) {
    private val byId: Map<String, Provider> = providers.associateBy { it.id }
    private val localProviders: List<Provider> = providers

    val all: List<Provider> get() = localProviders.filter { isProviderEnabled(it.id) }
    val anime: List<Provider> get() = all.filter { it.supportsAnime }
    val movies: List<Provider> get() = all.filter { it.supportsMovies }

    fun isEnabled(id: String): Boolean = isProviderEnabled(id)

    fun get(id: String): Provider =
        byId[id] ?: error("Unknown provider id=$id")
}
