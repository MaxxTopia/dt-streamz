package com.dt.streamz.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout tokens. Tightens default Material spacing for a 1080p living-
 * room TV — Apple TV / Plex / shadcn-density bias rather than the wider
 * defaults Compose ships.
 */
object DtSpace {
    val PageHPad = 28.dp      // page horizontal gutter
    val PageVPad = 18.dp      // page vertical gutter
    val Section = 12.dp       // gap between page-level sections
    val Row = 10.dp           // gap between row items / row title -> row
    val Inline = 6.dp         // small inline gap
}

object DtPoster {
    /** Movie/anime portrait poster card. */
    val Width = 132.dp
    val Height = 198.dp

    /** Continue-watching landscape thumbnail. */
    val ContinueWidth = 168.dp
    val ContinueHeight = 100.dp

    /** Random-pick CTA card on Home. */
    val RandomWidth = 232.dp
    val RandomHeight = 120.dp
}
