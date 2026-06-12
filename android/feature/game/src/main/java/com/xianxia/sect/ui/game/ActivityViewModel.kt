package com.xianxia.sect.ui.game

import com.xianxia.sect.core.config.BuiltinActivityConfig
import com.xianxia.sect.core.model.ActivityDef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor() : BaseViewModel() {
    private val _activities = MutableStateFlow<List<ActivityDef>>(emptyList())
    val activities: StateFlow<List<ActivityDef>> = _activities.asStateFlow()

    private val _selectedActivityId = MutableStateFlow<String?>(null)
    val selectedActivityId: StateFlow<String?> = _selectedActivityId.asStateFlow()

    init {
        _activities.value = BuiltinActivityConfig.getAllActivities()
        _selectedActivityId.value = _activities.value.firstOrNull()?.id
    }

    fun selectActivity(id: String) {
        _selectedActivityId.value = id
    }
}
