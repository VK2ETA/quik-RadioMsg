package com.moez.QKSMS.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.mms.MMSPart
import com.moez.QKSMS.manager.QkTransaction
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.receiver.MessageSentReceiver
import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject

class RadioMmsReceiver : BroadcastReceiver() {

    private val TAG = "RadioMmsReceiver"

    @Inject lateinit var messageRepo: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)

        Log.d(TAG, "onReceive: ${intent.action}")
        if ("com.quik.sms.action.SEND_BACKGROUND_MMS" != intent.action) return

        val address       = intent.getStringExtra("EXTRA_PHONE_NUMBER")
        val body          = intent.getStringExtra("EXTRA_MESSAGE_BODY") ?: ""
        val imageBytes    = intent.getByteArrayExtra("EXTRA_IMAGE_BYTES")
        val imageMimeType = intent.getStringExtra("EXTRA_IMAGE_MIME_TYPE") ?: "image/jpeg"

        if (address.isNullOrEmpty()) { Log.e(TAG, "No address"); return }

        // Extend receiver lifetime past onReceive() return so background thread can finish
        val pendingResult = goAsync()

        Thread {
            try {
                val parts = mutableListOf<MMSPart>()
                if (imageBytes != null) {
                    parts += MMSPart().apply {
                        MimeType = imageMimeType
                        Name     = "image_0"
                        Data     = imageBytes
                    }
                }

                // Stage 1: create OS provider record
                val messageUri = QkTransaction.createMessage(
                    context,
                    subscriptionId = -1,
                    body           = body,
                    signature      = "",
                    toAddresses    = arrayOf(address),
                    parts          = parts,
                    asGroup        = false,
                    longAsMms      = false,
                    stripUnicode   = false
                )

                if (messageUri == Uri.EMPTY) {
                    Log.e(TAG, "createMessage failed")
                    return@Thread
                }

                Log.d(TAG, "MMS record created at $messageUri")

                // Stage 2: sync to Realm (write — must be off the main thread)
                val message = messageRepo.syncProviderMessage(messageUri, false)
                if (message == null) {
                    Log.e(TAG, "syncProviderMessage failed")
                    return@Thread
                }

                Log.d(TAG, "Synced to Realm message id=${message.id}")

                // Stage 3: send via QkTransaction
                val sentIntent = Intent(context, MessageSentReceiver::class.java)
                    .putExtra(MessageSentReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)

                if (!QkTransaction.sendMessage(context, messageUri, sentIntent, null)) {
                    Log.e(TAG, "QkTransaction.sendMessage failed for $address")
                } else {
                    Log.d(TAG, "MMS dispatched for $address uri=$messageUri")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MMS", e)
            } finally {
                pendingResult.finish() // always release the receiver
            }
        }.start()
    }
}