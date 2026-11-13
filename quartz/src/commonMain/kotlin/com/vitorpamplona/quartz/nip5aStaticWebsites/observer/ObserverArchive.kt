/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

/**
 * An Observer site's `/archive.html`: the kept editions, newest first, each linking to its
 * dated file with a relative link. A fixed template, never a model's: every value is
 * escaped, there are no scripts, and nothing loads from anywhere else.
 */
object ObserverArchive {
    fun html(
        title: String,
        editions: List<ObserverEdition>,
    ): String {
        val name = escape(title)
        val items =
            ObserverSite.newestFirst(editions).joinToString("\n") { edition ->
                val headline = edition.headline?.takeIf { it.isNotBlank() }?.let { escape(it) } ?: escape(edition.day)
                val facts =
                    listOfNotNull(
                        edition.posts?.let { "$it posts" },
                        edition.quotesVerified?.let { "$it quotes verified" },
                        edition.code?.let { "No. ${escape(it)}" },
                    ).joinToString(" · ")
                // Named from the day, never from the entry's path; relative, so the archive works
                // from any base the site is served under.
                val href = escape(ObserverSite.dayPath(edition.day).removePrefix("/"))
                buildString {
                    append("      <li><a href=\"")
                        .append(href)
                        .append("\"><time>")
                        .append(escape(edition.day))
                        .append("</time>")
                    append("<span class=\"h\">").append(headline).append("</span></a>")
                    if (facts.isNotEmpty()) append("<span class=\"f\">").append(facts).append("</span>")
                    append("</li>")
                }
            }
        // Not a trimMargin template: a headline with a line break followed by `|` would lose text.
        return buildString {
            append("<!doctype html>\n")
            append("<html lang=\"en\">\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\">\n")
            append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("  <title>").append(name).append(" — Archive</title>\n")
            append("  <style>\n")
            append("    :root { color-scheme: light; }\n")
            append("    body { margin: 0; background: #faf8f3; color: #1a1a1a; font-family: Georgia, 'Times New Roman', serif; }\n")
            append("    main { max-width: 720px; margin: 0 auto; padding: 32px 22px 72px; }\n")
            append("    h1 { font-size: 40px; letter-spacing: -0.01em; margin: 0 0 4px; text-align: center; }\n")
            append("    p.sub { text-align: center; font: 13px/1.4 system-ui, sans-serif; letter-spacing: 0.12em; text-transform: uppercase; color: #555; margin: 0 0 24px; border-bottom: 3px double #1a1a1a; padding-bottom: 14px; }\n")
            append("    ol { list-style: none; padding: 0; margin: 0; }\n")
            append("    li { border-bottom: 1px solid #ddd8cc; padding: 14px 0; }\n")
            append("    a { color: inherit; text-decoration: none; display: block; }\n")
            append("    a:hover .h { text-decoration: underline; }\n")
            append("    time { display: block; font: 12px/1.4 system-ui, sans-serif; letter-spacing: 0.1em; text-transform: uppercase; color: #666; }\n")
            append("    .h { display: block; font-size: 22px; line-height: 1.25; margin-top: 2px; }\n")
            append("    .f { display: block; font: 13px/1.4 system-ui, sans-serif; color: #666; margin-top: 4px; }\n")
            append("    nav { text-align: center; margin-top: 28px; font: 14px system-ui, sans-serif; }\n")
            append("  </style>\n")
            append("</head>\n")
            append("<body>\n")
            append("  <main>\n")
            append("    <h1>").append(name).append("</h1>\n")
            append("    <p class=\"sub\">Archive</p>\n")
            append("    <ol>\n")
            append(items)
            append("\n")
            append("    </ol>\n")
            append("    <nav><a href=\"index.html\">Today's edition</a></nav>\n")
            append("  </main>\n")
            append("</body>\n")
            append("</html>\n")
        }
    }

    fun escape(text: String): String =
        buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }
}
