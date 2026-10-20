@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.adham1223990.serverapplicationfix

import kotlin.contracts.contract

/*
 * Replacements for Kotlin's isBlank / isNotBlank / isNullOrBlank / ifBlank.
 *
 * Why these exist: on a non-empty string the stdlib versions run `indices.all { ... }`, which walks
 * an IntRange iterator. In this Aliucord/Discord runtime that iterator is the app's own minified
 * class (d0.d0.b), not the kotlin.collections.IntIterator the plugin was compiled against, so the
 * call throws "d0.d0.b cannot be cast to kotlin.collections.IntIterator". That is the exact crash
 * behind "Could not load the application form". These versions use a plain while loop and never
 * touch a Kotlin range/iterator, and keep the same smart-cast behaviour through contracts.
 */

fun CharSequence?.isNullOrBlankSafe(): Boolean {
    contract { returns(false) implies (this@isNullOrBlankSafe != null) }
    if (this == null) return true
    var i = 0
    while (i < length) {
        if (!Character.isWhitespace(this[i])) return false
        i++
    }
    return true
}

fun CharSequence.isBlankSafe(): Boolean {
    var i = 0
    while (i < length) {
        if (!Character.isWhitespace(this[i])) return false
        i++
    }
    return true
}

fun CharSequence.isNotBlankSafe(): Boolean = !isBlankSafe()

/** Same as `ifBlank { null }`. */
fun String.orNullIfBlank(): String? = if (isBlankSafe()) null else this

/** Same as `ifBlank { default }`. */
fun String.ifBlankSafe(default: String): String = if (isBlankSafe()) default else this
