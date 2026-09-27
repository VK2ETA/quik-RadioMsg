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
package dev.octoshrimpy.quik.worker

import android.content.Context
import android.content.Intent
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.interactor.UpdateBadge
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.receiver.SmsDeliveryRegistrationReceiver
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class ReceiveSmsWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        const val INPUT_DATA_KEY_MESSAGE_ID = "messageId"

        // ── Public API consumed by third-party apps ────────────────────────────

        /** Delivered to every registered receiver when a new SMS arrives. */
        const val ACTION_SMS_RECEIVED = "dev.octoshrimpy.quik.action.SMS_RECEIVED"

        /**
         * Send an explicit broadcast carrying this action (targeted at
         * [SmsDeliveryRegistrationReceiver]) to subscribe for SMS deliveries.
         *
         * Required extras: [EXTRA_RECEIVER_PACKAGE], [EXTRA_RECEIVER_CLASS]
         */
        const val ACTION_REGISTER_SMS_RECEIVER   = "dev.octoshrimpy.quik.action.REGISTER_SMS_RECEIVER"

        /** Same as [ACTION_REGISTER_SMS_RECEIVER] but removes the subscription. */
        const val ACTION_UNREGISTER_SMS_RECEIVER = "dev.octoshrimpy.quik.action.UNREGISTER_SMS_RECEIVER"

        // Extras carried by ACTION_SMS_RECEIVED
        const val EXTRA_MESSAGE_ID = "extra_message_id"   // Long
        const val EXTRA_THREAD_ID  = "extra_thread_id"    // Long
        const val EXTRA_ADDRESS    = "extra_address"      // String
        const val EXTRA_BODY       = "extra_body"         // String
        const val EXTRA_DATE_SENT  = "extra_date_sent"    // Long  (ms)
        const val EXTRA_SUB_ID     = "extra_sub_id"       // Int

        // Extras required in the registration broadcast
        const val EXTRA_RECEIVER_PACKAGE = "extra_receiver_package"  // String
        const val EXTRA_RECEIVER_CLASS   = "extra_receiver_class"    // String
    }

    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var blockingClient: BlockingClient
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var updateBadge: UpdateBadge
    @Inject lateinit var shortcutManager: ShortcutManager
    @Inject lateinit var filterRepo: MessageContentFilterRepository
    @Inject lateinit var contactsRepo: ContactRepository

    override fun doWork(): Result {
        Timber.v("started")

        val messageId = inputData.getLong(INPUT_DATA_KEY_MESSAGE_ID, -1)
        if (messageId < 0) {
            Timber.v("failed. message id was {messageId}")
            return Result.failure(inputData)
        }

        val message = messageRepo.getMessage(messageId) ?: return Result.failure(inputData)

        val action = blockingClient.shouldBlock(message.address).blockingGet()

        when {
            ((action is BlockingClient.Action.Block) && prefs.drop.get()) -> {
                // blocked and 'drop blocked' remove from db and don't continue
                Timber.v("address is blocked and drop blocked is on. dropped")
                messageRepo.deleteMessages(listOf(message.id))
                return Result.failure(inputData)
            }

            action is BlockingClient.Action.Block -> {
                // blocked
                Timber.v("address is blocked")
                messageRepo.markRead(listOf(message.threadId))
                conversationRepo.markBlocked(
                    listOf(message.threadId),
                    prefs.blockingManager.get(),
                    action.reason
                )
            }

            action is BlockingClient.Action.Unblock -> {
                // unblock
                Timber.v("unblock conversation if blocked")
                conversationRepo.markUnblocked(message.threadId)
            }
        }

        val messageFilterAction = filterRepo.isBlocked(message.getText(), message.address, contactsRepo)
        if (messageFilterAction) {
            Timber.v("message dropped based on content filters")
            messageRepo.deleteMessages(listOf(message.id))
            return Result.failure(inputData)
        }

        // update and fetch conversation
        conversationRepo.updateConversations(listOf(message.threadId))
        val conversation = conversationRepo.getOrCreateConversation(message.threadId)
            ?: return Result.failure(inputData)

        // don't notify (continue) for blocked conversations
        if (conversation.blocked) {
            Timber.v("no notifications for blocked")
            return Result.failure(inputData)
        }

        // unarchive conversation if necessary
        if (conversation.archived) {
            Timber.v("conversation unarchived")
            conversationRepo.markUnarchived(listOf(conversation.id))
        }

        // update/create notification
        Timber.v("update/create notification")
        notificationManager.update(conversation.id)

        // update shortcuts
        Timber.v("update shortcuts")
        shortcutManager.updateShortcuts()
        shortcutManager.getOrCreateShortcut(conversation.id)

        // update the badge and widget
        Timber.v("update badge and widget")
        updateBadge.execute(Unit)

        // forward to any registered third-party apps
        deliverSmsToRegisteredApps(message)

        Timber.v("finished")

        return Result.success()
    }

    /**
     * Sends a package-restricted broadcast to every app that registered via
     * [SmsDeliveryRegistrationReceiver].
     *
     * Using [Intent.setPackage] (not setClassName) is deliberate:
     *   - It restricts delivery to the target package only, so no other app
     *     can intercept it.
     *   - Because no explicit component is named, Android does NOT start the
     *     target process if it is not already running. The broadcast is
     *     silently dropped when the app is dead, which is exactly what we want.
     *   - The client app must register its receiver dynamically (in code) so
     *     it is only live while the process is alive.
     */
    private fun deliverSmsToRegisteredApps(message: Message) {
        val prefs = applicationContext.getSharedPreferences(
            SmsDeliveryRegistrationReceiver.PREFS_NAME, Context.MODE_PRIVATE
        )
        val registered = prefs
            .getStringSet(SmsDeliveryRegistrationReceiver.PREF_KEY_RECEIVERS, emptySet())
            .orEmpty()

        if (registered.isEmpty()) {
            Timber.v("no third-party sms receivers registered")
            return
        }

        for (entry in registered) {
            val slash = entry.indexOf('/')
            if (slash < 0) { Timber.w("malformed sms receiver entry: $entry"); continue }

            // entry is stored as "package/class" — we only need the package for setPackage()
            val pkg = entry.substring(0, slash)

            applicationContext.sendBroadcast(
                Intent(ACTION_SMS_RECEIVED).apply {
                    setPackage(pkg)            // restricts to this package; no process start
                    putExtra(EXTRA_MESSAGE_ID, message.id)
                    putExtra(EXTRA_THREAD_ID,  message.threadId)
                    putExtra(EXTRA_ADDRESS,    message.address)
                    putExtra(EXTRA_BODY,       message.body)
                    putExtra(EXTRA_DATE_SENT,  message.dateSent)
                    putExtra(EXTRA_SUB_ID,     message.subId)
                }
            )
            Timber.v("sms broadcast sent to package: $pkg")
        }
    }

    override fun getForegroundInfo() = ForegroundInfo(
        0,
        notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
    )

}
