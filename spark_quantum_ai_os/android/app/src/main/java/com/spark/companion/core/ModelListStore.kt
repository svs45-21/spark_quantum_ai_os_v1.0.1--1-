package com.spark.companion.core

import android.content.Context
import com.spark.companion.brain.ModelList
import com.spark.companion.brain.ModelListing

/** Saved model drop-down lists, one per provider and purpose. */
class ModelListStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("spark_models", Context.MODE_PRIVATE)

    fun get(key: String): ModelList? = ModelListing.fromJson(prefs.getString(key, null))

    fun put(key: String, list: ModelList) = prefs.edit().putString(key, ModelListing.toJson(list)).apply()

    var lastRefresh: Long
        get() = prefs.getLong("last_refresh", 0L)
        set(value) = prefs.edit().putLong("last_refresh", value).apply()

    companion object {
        const val CLAUDE = "claude"
        const val OPENAI_IMAGES = "openai_images"
    }
}
