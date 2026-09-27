/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.octoshrimpy.quik.worker.ReceiveSmsWorker
import timber.log.Timber

/**
 * Receives explicit registration broadcasts from third-party apps that want to
 * be notified whenever QUIK receives a new SMS.
 *
 * ## How a third-party app registers
 *
 * Send an explicit broadcast to this receiver with action
 * [ReceiveSmsWorker.ACTION_REGISTER_SMS_RECEIVER], supplying:
 *
 * | Extra key                           | Value                                        |
 * |-------------------------------------|----------------------------------------------|
 * | [ReceiveSmsWorker.EXTRA_RECEIVER_PACKAGE] | Your application's package name        |
 * | [ReceiveSmsWorker.EXTRA_RECEIVER_CLASS]   | Fully-qualified BroadcastReceiver class|
 *
 * ```kotlin
 * // Example from a third-party app
 * val intent = Intent(ReceiveSmsWorker.ACTION_REGISTER_SMS_RECEIVER).apply {
 *     setClassName(
 *         "dev.octoshrimpy.quik",
 *         "dev.octoshrimpy.quik.receiver.SmsDeliveryRegistrationReceiver"
 *     )
 *     putExtra(ReceiveSmsWorker.EXTRA_RECEIVER_PACKAGE, packageName)
 *     putExtra(ReceiveSmsWorker.EXTRA_RECEIVER_CLASS,
 *              "com.example.myapp.MySmsReceiver")
 * }
 * context.sendBroadcast(intent)
 * ```
 *
 * ## What QUIK delivers back
 *
 * Once registered, your [BroadcastReceiver] will receive an explicit broadcast
 * with action [ReceiveSmsWorker.ACTION_SMS_RECEIVED] and the following extras
 * every time a new SMS passes all of QUIK's blocking / filtering rules:
 *
 * | Extra key                        | Type   | Description              |
 * |----------------------------------|--------|--------------------------|
 * | [ReceiveSmsWorker.EXTRA_MESSAGE_ID]  | Long   | QUIK message id          |
 * | [ReceiveSmsWorker.EXTRA_THREAD_ID]   | Long   | Thread id                |
 * | [ReceiveSmsWorker.EXTRA_ADDRESS]     | String | Sender address           |
 * | [ReceiveSmsWorker.EXTRA_BODY]        | String | Message body             |
 * | [ReceiveSmsWorker.EXTRA_DATE_SENT]   | Long   | Sent timestamp (ms)      |
 * | [ReceiveSmsWorker.EXTRA_SUB_ID]      | Int    | SIM subscription id      |
 *
 * ## Unregistering
 *
 * Send the same broadcast with action
 * [ReceiveSmsWorker.ACTION_UNREGISTER_SMS_RECEIVER] to stop receiving
 * notifications.
 */
class SmsDeliveryRegistrationReceiver : BroadcastReceiver() {

    companion object {
        /** SharedPreferences file used to persist registered receivers across process restarts. */
        const val PREFS_NAME = "quik_sms_delivery_registrations"

        /** Set<String> key — each entry is stored as "package/FullyQualifiedClassName". */
        const val PREF_KEY_RECEIVERS = "registered_receivers"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(ReceiveSmsWorker.EXTRA_RECEIVER_PACKAGE)
        val cls = intent.getStringExtra(ReceiveSmsWorker.EXTRA_RECEIVER_CLASS)

        if (pkg.isNullOrBlank() || cls.isNullOrBlank()) {
            Timber.w("SmsDeliveryRegistrationReceiver: missing package or class extra, ignoring")
            return
        }

        val entry = "$pkg/$cls"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_KEY_RECEIVERS, emptySet())!!.toMutableSet()

        when (intent.action) {
            ReceiveSmsWorker.ACTION_REGISTER_SMS_RECEIVER -> {
                if (current.add(entry)) {
                    prefs.edit().putStringSet(PREF_KEY_RECEIVERS, current).apply()
                    Timber.i("SmsDeliveryRegistrationReceiver: registered $entry")
                } else {
                    Timber.v("SmsDeliveryRegistrationReceiver: already registered $entry")
                }
            }

            ReceiveSmsWorker.ACTION_UNREGISTER_SMS_RECEIVER -> {
                if (current.remove(entry)) {
                    prefs.edit().putStringSet(PREF_KEY_RECEIVERS, current).apply()
                    Timber.i("SmsDeliveryRegistrationReceiver: unregistered $entry")
                } else {
                    Timber.v("SmsDeliveryRegistrationReceiver: not registered, nothing to remove: $entry")
                }
            }

            else -> Timber.w("SmsDeliveryRegistrationReceiver: unknown action ${intent.action}")
        }
    }
}
