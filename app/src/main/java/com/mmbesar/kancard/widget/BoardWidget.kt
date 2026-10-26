package com.mmbesar.kancard.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.material3.ColorProviders
import com.mmbesar.kancard.KanCardApplication
import com.mmbesar.kancard.MainActivity
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.WidgetData
import com.mmbesar.kancard.ui.ThemePrefs
import com.mmbesar.kancard.ui.schemeFor

/**
 * The home-screen widget: shows the open tasks of one project, grouped by column.
 * It only reads the on-phone cache (fast, works offline); [RefreshWorker]
 * keeps that cache fresh. Tapping the widget opens KanCard.
 */
class BoardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as KanCardApplication
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val projectId = WidgetPrefs.projectFor(context, appWidgetId)
        val data = projectId?.let { app.repository.widgetSnapshot(it) }
        val emptyText = context.getString(R.string.widget_not_configured)
        // Same look as the app: the chosen theme/accent, one scheme for light and one for dark phones.
        val settings = ThemePrefs.load(context)
        val light = schemeFor(settings, systemDark = false, context = context)
        val dark = schemeFor(settings, systemDark = true, context = context)

        provideContent {
            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) {
                WidgetContent(data, emptyText)
            }
        }
    }
}

@Composable
private fun WidgetContent(data: WidgetData?, emptyText: String) {
    // Tapping the widget opens the app (this Glance version needs an explicit Intent).
    val openApp = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(10.dp)
            .clickable(openApp),
    ) {
        if (data == null) {
            Text(
                text = emptyText,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
        } else {
            Text(
                text = data.projectName,
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                items(data.rows) { row ->
                    Text(
                        text = if (row.isHeader) row.text else "• ${row.text}",
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        style = TextStyle(
                            color = if (row.isHeader) GlanceTheme.colors.secondary
                            else GlanceTheme.colors.onSurface,
                            fontSize = if (row.isHeader) 13.sp else 14.sp,
                            fontWeight = if (row.isHeader) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}
