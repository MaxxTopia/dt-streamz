package com.dt.streamz.ui

sealed interface Route {
    data object Tabs : Route
    data class Details(val providerId: String, val titleId: String) : Route
    data class Player(val hlsUrl: String, val title: String = "") : Route
}
