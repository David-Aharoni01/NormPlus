package com.norm2hacked.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.data.db.dao.NotificationRuleDao
import com.norm2hacked.data.db.entities.NotificationRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.norm2hacked.ui.theme.Background
import com.norm2hacked.ui.theme.OnSurfaceMuted
import com.norm2hacked.ui.theme.Surface
import com.norm2hacked.ui.theme.Teal

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class NotificationRulesViewModel @Inject constructor(
    private val ruleDao: NotificationRuleDao,
) : ViewModel() {
    val rules = ruleDao.queryAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(rule: NotificationRuleEntity, enabled: Boolean) {
        viewModelScope.launch { ruleDao.upsert(rule.copy(enabled = enabled)) }
    }
    fun setVibrateOnFirst(rule: NotificationRuleEntity, vibrate: Boolean) {
        viewModelScope.launch { ruleDao.upsert(rule.copy(vibrateOnFirst = vibrate)) }
    }
    fun setMuteGroupChats(rule: NotificationRuleEntity, mute: Boolean) {
        viewModelScope.launch { ruleDao.upsert(rule.copy(muteGroupChats = mute)) }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun NotificationRulesScreen(viewModel: NotificationRulesViewModel, onBack: () -> Unit) {
    val rules by viewModel.rules.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Notification Rules", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }

        if (rules.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                "No apps yet — notifications from apps will appear here once received.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                items(rules, key = { it.packageName }) { rule ->
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(rule.appLabel, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(rule.packageName, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                        Spacer(Modifier.height(8.dp))
                        RuleToggle("Forward to watch", rule.enabled) { viewModel.setEnabled(rule, it) }
                        if (rule.enabled) {
                            RuleToggle("Vibrate on first", rule.vibrateOnFirst) { viewModel.setVibrateOnFirst(rule, it) }
                            RuleToggle("Mute group chat repeats (30s)", rule.muteGroupChats) { viewModel.setMuteGroupChats(rule, it) }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun RuleToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Switch(
            checked = checked, onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Teal),
        )
    }
}
