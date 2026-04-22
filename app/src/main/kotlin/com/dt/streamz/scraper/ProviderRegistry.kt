package com.dt.streamz.scraper

class ProviderRegistry(providers: List<Provider>) {
    private val byId: Map<String, Provider> = providers.associateBy { it.id }

    val all: List<Provider> = providers
    val anime: List<Provider> = providers.filter { it.supportsAnime }
    val movies: List<Provider> = providers.filter { it.supportsMovies }

    fun get(id: String): Provider =
        byId[id] ?: error("Unknown provider id=$id")
}
