package co.magicpages.socialweb.ui.moderation

import android.content.Intent
import android.content.res.Resources
import androidx.core.net.toUri
import co.magicpages.socialweb.R

/** Where every report goes: Ghost's service has no report API, so Magic Pages reads them by mail. */
const val ReportAddress = "reports@magicpages.co"

/** Everything a finished report says, before it is written out as an email. */
data class ReportDraft(
    /** The reported account as `@user@host`, and the page it can be looked at on. */
    val handle: String,
    val profileUrl: String,
    /** The report started from one of the account's posts. */
    val aboutPost: Boolean,
    val reason: ReportReason,
    /** The texts of the server rules named as broken. */
    val rules: List<String>,
    val postUrls: List<String>,
    val comment: String,
    /** The reporter's own fediverse handle, so the reply has someone to go to. */
    val reporter: String?,
    /** The moderators' address when the report is also copied to them, and the server they run. */
    val forwardTo: String?,
    val forwardHost: String,
)

/** A report as a message: [to] and [cc] addresses, [subject] and plain-text [body]. */
class ReportEmail(
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
) {
    /** Everyone the message goes to, for telling the reader where a copied report has to be sent. */
    val recipients: List<String> get() = to + cc

    /** The whole message as text, for the clipboard when no mail app is there to take it. */
    val asText: String get() = "$subject\n\n$body"

    /** Opens the mail app's composer with everything filled in; only mail apps answer a bare `mailto:`. */
    fun intent(): Intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
        if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
}

/**
 * Writes [draft] as an email from string resources, so the labels follow the reader's language
 * like the rest of the app. Sections the report has nothing for are left out.
 */
fun buildReportEmail(resources: Resources, draft: ReportDraft): ReportEmail {
    val subject = resources.getString(
        if (draft.aboutPost) R.string.report_email_subject_post else R.string.report_email_subject_account,
        draft.handle,
    )
    val blocks = mutableListOf<String>()
    blocks += listOf(
        resources.getString(R.string.report_email_account, draft.handle),
        resources.getString(R.string.report_email_profile, draft.profileUrl),
        resources.getString(R.string.report_email_reason, resources.getString(draft.reason.title)),
    ).joinToString("\n")
    if (draft.rules.isNotEmpty()) {
        blocks += (listOf(resources.getString(R.string.report_email_rules)) + draft.rules.map { "- $it" }).joinToString("\n")
    }
    if (draft.postUrls.isNotEmpty()) {
        blocks += (listOf(resources.getString(R.string.report_email_posts)) + draft.postUrls.map { "- $it" }).joinToString("\n")
    }
    if (draft.comment.isNotBlank()) {
        blocks += resources.getString(R.string.report_email_comment) + "\n" + draft.comment.trim()
    }
    val closing = listOfNotNull(
        draft.reporter?.let { resources.getString(R.string.report_email_reporter, it) },
        draft.forwardTo?.let { resources.getString(R.string.report_email_forwarded, draft.forwardHost) },
        resources.getString(R.string.report_email_footer, resources.getString(R.string.app_name)),
    )
    blocks += closing.joinToString("\n")
    return ReportEmail(
        to = listOf(ReportAddress),
        cc = listOfNotNull(draft.forwardTo),
        subject = subject,
        body = blocks.joinToString("\n\n"),
    )
}
