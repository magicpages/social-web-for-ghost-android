package co.magicpages.socialweb.data.settings

import co.magicpages.socialweb.data.translation.siteOf

/**
 * Whether the reader view is hidden for the site [articleUrl] is on, going by [AppSettings.readerHiddenSites]. A
 * site is its host without "www.", so example.com and www.example.com are one.
 */
fun Set<String>.hidesReaderFor(articleUrl: String?): Boolean = siteOf(articleUrl)?.let { it in this } == true
