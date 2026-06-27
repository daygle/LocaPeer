package com.locapeer.sharing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.data.entity.scheduleRules
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val prefs: AppPreferences,
    private val configDao: PeerSharingConfigDao
) : ViewModel() {

    val scope: String = savedStateHandle.get<String>("scope") ?: "global"
    val peerId: String = savedStateHandle.get<String>("peerId") ?: ""
    val peerName: String = savedStateHandle.get<String>("peerName") ?: ""

    private val _rules = MutableStateFlow<List<ScheduleRule>>(emptyList())
    val rules: StateFlow<List<ScheduleRule>> = _rules

    init {
        viewModelScope.launch {
            _rules.value = if (scope == "global") {
                prefs.settings.first().globalScheduleRules
            } else {
                configDao.getForPeer(peerId)?.scheduleRules() ?: emptyList()
            }
        }
    }

    fun saveRules(rules: List<ScheduleRule>) {
        _rules.value = rules
        viewModelScope.launch {
            if (scope == "global") {
                prefs.setGlobalScheduleRules(rules)
            } else {
                val rulesJson = Json.encodeToString(rules)
                val existing = configDao.getForPeer(peerId)
                if (existing != null) {
                    configDao.setScheduleRules(peerId, rulesJson)
                } else {
                    configDao.upsert(PeerSharingConfig(peerDeviceId = peerId, scheduleRulesJson = rulesJson))
                }
            }
        }
    }
}
