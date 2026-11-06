package com.villamediana.sound.smart

import android.content.res.Resources
import com.villamediana.sound.ui.home.homeMixLabels

/**
 * The one set of localized mix titles. Home cards, the Mix screen and the player's
 * "Tocando de …" all build mixes with it, so a mix has the same name everywhere.
 */
fun soundMixLabels(res: Resources): MixLabels = homeMixLabels(res)
