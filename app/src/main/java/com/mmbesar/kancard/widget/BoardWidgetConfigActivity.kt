package com.mmbesar.kancard.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mmbesar.kancard.KanCardApplication
import com.mmbesar.kancard.R
import com.mmbesar.kancard.security.Mode
import com.mmbesar.kancard.ui.KanCardTheme
import kotlinx.coroutines.launch

/**
 * Shown when you add the widget: pick which project it should display.
 */
class BoardWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the user backs out, Android must cancel adding the widget.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = application as KanCardApplication
        val setUp = app.repository.mode != Mode.UNSET

        setContent {
            val settings by app.themePrefs.settings.collectAsStateWithLifecycle()
            KanCardTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val projects by app.repository.observeProjects()
                        .collectAsStateWithLifecycle(initialValue = emptyList())

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.widget_config_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        when {
                            !setUp -> Text(stringResource(R.string.widget_config_no_setup))
                            projects.isEmpty() -> Text(stringResource(R.string.widget_config_no_projects))
                            else -> LazyColumn {
                                items(projects, key = { it.id }) { project ->
                                    Text(
                                        text = project.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { choose(appWidgetId, project.id) }
                                            .padding(vertical = 16.dp),
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Saves the choice, draws the widget once, and tells Android it is done. */
    private fun choose(appWidgetId: Int, projectId: Long) {
        lifecycleScope.launch {
            WidgetPrefs.set(this@BoardWidgetConfigActivity, appWidgetId, projectId)
            val glanceId = GlanceAppWidgetManager(this@BoardWidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)
            BoardWidget().update(this@BoardWidgetConfigActivity, glanceId)
            // Download the chosen project in the background (the app may never have opened it).
            RefreshWorker.syncSoon(this@BoardWidgetConfigActivity)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}
