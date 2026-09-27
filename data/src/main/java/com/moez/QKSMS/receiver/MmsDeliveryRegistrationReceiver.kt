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
import dev.octoshrimpy.quik.worker.ReceiveMmsWorker
import timber.log.Timber

/**
 * Receives explicit registration broadcasts from third-party apps that want to
 * be notified whenever QUIK receives a new MMS.
 *
 * Mirrors [SmsDeliveryRegistrationReceiver] exactly, but for MMS.
 *
 * ## Registering
 *
 * Send an explicit broadcast targeted at this receiver with action
 * [ReceiveMmsWorker.ACTION_REGISTER_MMS_RECEIVER]:
 *
 * | Extra key                              | Value                                   |
 * |----------------------------------------|-----------------------------------------|
 * | [ReceiveMmsWorker.EXTRA_RECEIVER_PACKAGE] | Your application's package name      |
 * | [ReceiveMmsWorker.EXTRA_RECEIVER_CLASS]   | Fully-qualified BroadcastReceiver class|
 *
 * ## What QUIK delivers back
 *
 * Action [ReceiveMmsWorker.ACTION_MMS_RECEIVED] with extras:
 *
 * | Extra key                              | Type   | Description                     |
 * |----------------------------------------|--------|---------------------------------|
 * | [ReceiveMmsWorker.EXTRA_MESSAGE_ID]    | Long   | QUIK message id                 |
 * | [ReceiveMmsWorker.EXTRA_THREAD_ID]     | Long   | Thread id                       |
 * | [ReceiveMmsWorker.EXTRA_ADDRESS]       | String | Sender address                  |
 * | [ReceiveMmsWorker.EXTRA_SUBJECT]       | String | MMS subject (may be empty)      |
 * | [ReceiveMmsWorker.EXTRA_TEXT]          | String | Concatenated text parts         |
 * | [ReceiveMmsWorker.EXTRA_DATE_SENT]     | Long   | Sent timestamp (ms)             |
 * | [ReceiveMmsWorker.EXTRA_SUB_ID]        | Int    | SIM subscription id             |
 * | [ReceiveMmsWorker.EXTRA_MESSAGE_URI]   | String | content:// URI of the MMS row   |
 *
 * ## Unregistering
 *
 * Same broadcast with action [ReceiveMmsWorker.ACTION_UNREGISTER_MMS_RECEIVER].
 */
class MmsDeliveryRegistrationReceiver : BroadcastReceiver() {

    companion object {
        /** SharedPreferences file used to persist registered receivers across process restarts. */
        const val PREFS_NAME = "quik_mms_delivery_registrations"

        /** Set<String> key — each entry is stored as "package/FullyQualifiedClassName". */
        const val PREF_KEY_RECEIVERS = "registered_receivers"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(ReceiveMmsWorker.EXTRA_RECEIVER_PACKAGE)
        val cls = intent.getStringExtra(ReceiveMmsWorker.EXTRA_RECEIVER_CLASS)

        if (pkg.isNullOrBlank() || cls.isNullOrBlank()) {
            Timber.w("MmsDeliveryRegistrationReceiver: missing package or class extra, ignoring")
            return
        }

        val entry = "$pkg/$cls"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_KEY_RECEIVERS, emptySet())!!.toMutableSet()

        when (intent.action) {
            ReceiveMmsWorker.ACTION_REGISTER_MMS_RECEIVER -> {
                if (current.add(entry)) {
                    prefs.edit().putStringSet(PREF_KEY_RECEIVERS, current).apply()
                    Timber.i("MmsDeliveryRegistrationReceiver: registered $entry")
                } else {
                    Timber.v("MmsDeliveryRegistrationReceiver: already registered $entry")
                }
            }

            ReceiveMmsWorker.ACTION_UNREGISTER_MMS_RECEIVER -> {
                if (current.remove(entry)) {
                    prefs.edit().putStringSet(PREF_KEY_RECEIVERS, current).apply()
                    Timber.i("MmsDeliveryRegistrationReceiver: unregistered $entry")
                } else {
                    Timber.v("MmsDeliveryRegistrationReceiver: not registered, nothing to remove: $entry")
                }
            }

            else -> Timber.w("MmsDeliveryRegistrationReceiver: unknown action ${intent.action}")
        }
    }
}
