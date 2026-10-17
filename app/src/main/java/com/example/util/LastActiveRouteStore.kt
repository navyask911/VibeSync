package com.example.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.vibeSyncRouteDataStore by preferencesDataStore(name = "vibesync_navigation")

object LastActiveRouteStore {
    private val lastRoute = stringPreferencesKey("last_active_route")
    suspend fun saveChat(context: Context, matchId: String) {
        LastActiveRoutePolicy.chatRoute(matchId)?.let { route ->
            context.applicationContext.vibeSyncRouteDataStore.edit { it[lastRoute] = route }
        }
    }
    suspend fun readRoute(context: Context): String? =
        context.applicationContext.vibeSyncRouteDataStore.data.first()[lastRoute]
    suspend fun clear(context: Context) {
        context.applicationContext.vibeSyncRouteDataStore.edit { it.remove(lastRoute) }
    }
}

object LastActiveRoutePolicy {
    fun chatRoute(matchId: String): String? {
        val id = matchId.trim()
        if (!safe(id)) return null
        return "chat:$id"
    }
    fun parseChatMatchId(route: String?): String? {
        val value = route?.trim().orEmpty()
        if (!value.startsWith("chat:")) return null
        return value.removePrefix("chat:").takeIf(::safe)
    }
    private fun safe(id: String): Boolean =
        id.isNotBlank() && id.length <= 200 &&
            id.none { it.isWhitespace() || it == '/' || it == '\\' || it.isISOControl() }
}
