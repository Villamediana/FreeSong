package com.villamediana.surco.ui.home

import android.content.res.Resources
import com.villamediana.surco.R
import com.villamediana.surco.smart.MixLabels

/** Localized mix titles for [com.villamediana.surco.smart.SmartMixes.build]. */
fun homeMixLabels(res: Resources): MixLabels = MixLabels(
    genreMix = { genre -> res.getString(R.string.home_mix_genre, genre) },
    genreMixSubtitle = { artists -> res.getString(R.string.home_mix_genre_subtitle, artists) },
    liked = res.getString(R.string.home_mix_liked),
    likedSubtitle = res.getString(R.string.home_mix_liked_subtitle),
    onRepeat = res.getString(R.string.home_mix_on_repeat),
    onRepeatSubtitle = res.getString(R.string.home_mix_on_repeat_subtitle),
    rediscover = res.getString(R.string.home_mix_rediscover),
    rediscoverSubtitle = res.getString(R.string.home_mix_rediscover_subtitle),
    fresh = res.getString(R.string.home_mix_fresh),
    freshSubtitle = res.getString(R.string.home_mix_fresh_subtitle),
    decade = { decade ->
        val short = decadeShort(decade)
        if (short != null) res.getString(R.string.home_mix_decade_short, short) else res.getString(R.string.home_mix_decade, decade)
    },
    decadeSubtitle = res.getString(R.string.home_mix_decade_subtitle),
    artistMix = { artist -> res.getString(R.string.home_mix_artist, artist) },
    daily = res.getString(R.string.home_mix_daily),
    dailySubtitle = res.getString(R.string.home_mix_daily_subtitle),
)
