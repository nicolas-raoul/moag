package io.github.nicolasraoul.moag

import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.search.FlagTerm

object EmailFetcher {

    fun fetchRecentUnreadEmails(username: String, appPassword: String): List<String> {
        val props = Properties()
        props["mail.store.protocol"] = "imaps"
        props["mail.imaps.host"] = "imap.gmail.com"
        props["mail.imaps.port"] = "993"
        props["mail.imaps.timeout"] = "10000"

        val session = Session.getInstance(props)
        val emails = mutableListOf<String>()

        try {
            val store: Store = session.getStore("imaps")
            store.connect("imap.gmail.com", username, appPassword)

            val inbox: Folder = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            // Search for unread messages
            val unreadFlag = FlagTerm(Flags(Flags.Flag.SEEN), false)
            val messages: Array<Message> = inbox.search(unreadFlag)

            // Get up to 10 recent unread emails
            val limit = minOf(10, messages.size)
            for (i in 0 until limit) {
                val msg = messages[messages.size - 1 - i] // most recent first
                val subject = msg.subject ?: "No Subject"
                val from = msg.from?.joinToString(", ") ?: "Unknown Sender"
                emails.add("From: $from\nSubject: $subject")
            }

            inbox.close(false)
            store.close()

        } catch (e: Exception) {
            e.printStackTrace()
            emails.add("Error fetching emails: ${e.message}")
        }

        return emails
    }
}
