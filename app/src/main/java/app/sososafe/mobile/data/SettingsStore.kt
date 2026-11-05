package app.sososafe.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.sososafe.core.ScanMode
import kotlinx.coroutines.flow.map

private val Context.settingsData by preferencesDataStore("settings")
data class UserSettings(val mode:ScanMode=ScanMode.AUTO,val theme:String="system",val dynamic:Boolean=true,val color:String="teal",val language:String="system",val deepScan:Boolean=true,val includeSystem:Boolean=false,val retentionDays:Int=7)
class SettingsStore(context:Context) {
    private val store=context.settingsData
    val flow=store.data.map { p -> UserSettings(runCatching { ScanMode.valueOf(p[stringPreferencesKey("mode")] ?: "AUTO") }.getOrDefault(ScanMode.AUTO),p[stringPreferencesKey("theme")] ?: "system",p[booleanPreferencesKey("dynamic")] ?: true,p[stringPreferencesKey("color")] ?: "teal",p[stringPreferencesKey("language")] ?: "system",p[booleanPreferencesKey("deep")] ?: true,p[booleanPreferencesKey("system")] ?: false,p[intPreferencesKey("retention")] ?: 7) }
    suspend fun set(key:String,value:String) { store.edit { it[stringPreferencesKey(key)]=value } }
    suspend fun set(key:String,value:Boolean) { store.edit { it[booleanPreferencesKey(key)]=value } }
    suspend fun retention(days:Int) { require(days in setOf(1,7,30)); store.edit { it[intPreferencesKey("retention")]=days } }
}
