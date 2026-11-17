package com.mylife.planner

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NotificationHelper {
    private const val EVENT_CHANNEL_ID = "event_reminders"
    private const val BUDGET_CHANNEL_ID = "budget_alerts"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL_ID, "Event Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(BUDGET_CHANNEL_ID, "Budget Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun showEventReminder(context: Context, eventId: Long, title: String, subtitle: String) {
        if (!hasPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context, eventId.toInt()))
            .build()
        NotificationManagerCompat.from(context).notify(eventId.toInt(), notification)
    }

    fun showBudgetExceeded(context: Context, notificationId: Int, title: String, message: String) {
        if (!hasPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, BUDGET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context, notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun mainIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Upcoming Event"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: "Reminder"
        NotificationHelper.showEventReminder(context, eventId, title, subtitle)
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
    }
}

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleReminder(event: EventEntity) {
        val mins = event.reminderMinutesBefore ?: run {
            cancelReminder(event.id)
            return
        }
        val triggerAt = event.startTime - (mins * 60_000L)
        if (triggerAt <= System.currentTimeMillis()) {
            cancelReminder(event.id)
            return
        }
        val timeText = Instant.ofEpochMilli(event.startTime)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("hh:mm a"))
        val subtitle = buildString {
            append("Starts at $timeText")
            if (!event.location.isNullOrBlank()) append(" • ${event.location}")
        }
        val pending = buildPendingIntent(event.id, event.title, subtitle)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancelReminder(eventId: Long) {
        alarmManager.cancel(buildPendingIntent(eventId, "", ""))
    }

    private fun buildPendingIntent(eventId: Long, title: String, subtitle: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_SUBTITLE, subtitle)
        }
        return PendingIntent.getBroadcast(
            context, eventId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
