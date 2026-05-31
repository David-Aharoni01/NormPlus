package com.norm2hacked.ui.screens.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.data.db.dao.HeartRateDao
import com.norm2hacked.data.db.dao.WorkoutDao
import com.norm2hacked.domain.model.GpsPoint
import com.norm2hacked.domain.model.WorkoutSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class WorkoutDetailUiState(
    val workout: WorkoutSummary? = null,
    val hrPoints: List<Pair<Long, Float>> = emptyList(),
    val gpsPoints: List<GpsPoint> = emptyList(),
)

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val heartRateDao: HeartRateDao,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutDetailUiState())
    val state: StateFlow<WorkoutDetailUiState> = _state.asStateFlow()

    fun load(workoutId: Long) {
        viewModelScope.launch {
            val entity = workoutDao.queryById(workoutId) ?: return@launch
            val summary = WorkoutSummary(
                id = entity.id,
                startEpochMs = entity.startEpoch,
                durationSeconds = entity.durationSeconds,
                sportType = entity.sportType,
                steps = entity.steps,
                calories = entity.calories,
                distanceMeters = entity.distanceMeters,
                avgHeartRate = entity.avgHeartRate,
                hasGps = entity.gpsPointsJson != null,
            )

            // Load HR samples within workout time window (snapshot — detail screen doesn't live-update)
            val endEpoch = entity.startEpoch + entity.durationSeconds * 1000L
            val hrList = heartRateDao.queryByRange(entity.startEpoch, endEpoch).first()
            val pts = hrList.map { it.timestampEpoch to it.bpm.toFloat() }

            val gps = entity.gpsPointsJson?.let { json ->
                runCatching {
                    Json.parseToJsonElement(json).jsonArray.map { el ->
                        val obj = el.jsonObject
                        GpsPoint(
                            lat = obj["lat"]!!.jsonPrimitive.content.toDouble(),
                            lon = obj["lon"]!!.jsonPrimitive.content.toDouble(),
                            speed = obj["speed"]?.jsonPrimitive?.content?.toFloat() ?: 0f,
                            epochMs = obj["t"]!!.jsonPrimitive.content.toLong(),
                        )
                    }
                }.getOrDefault(emptyList())
            } ?: emptyList()

            _state.update { it.copy(workout = summary, hrPoints = pts, gpsPoints = gps) }
        }
    }
}
