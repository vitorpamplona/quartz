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
                // The path is checked to be /YYYY-MM-DD.html before it gets here; the link drops the slash
                // so the archive works from any base the site is served under.
                val href = escape(edition.path.removePrefix("/"))
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
        return """
            |<!doctype html>
            |<html lang="en">
            |<head>
            |  <meta charset="utf-8">
            |  <meta name="viewport" content="width=device-width, initial-scale=1">
            |  <title>$name — Archive</title>
            |  <style>
            |    :root { color-scheme: light; }
            |    body { margin: 0; background: #faf8f3; color: #1a1a1a; font-family: Georgia, 'Times New Roman', serif; }
            |    main { max-width: 720px; margin: 0 auto; padding: 32px 22px 72px; }
            |    h1 { font-size: 40px; letter-spacing: -0.01em; margin: 0 0 4px; text-align: center; }
            |    p.sub { text-align: center; font: 13px/1.4 system-ui, sans-serif; letter-spacing: 0.12em; text-transform: uppercase; color: #555; margin: 0 0 24px; border-bottom: 3px double #1a1a1a; padding-bottom: 14px; }
            |    ol { list-style: none; padding: 0; margin: 0; }
            |    li { border-bottom: 1px solid #ddd8cc; padding: 14px 0; }
            |    a { color: inherit; text-decoration: none; display: block; }
            |    a:hover .h { text-decoration: underline; }
            |    time { display: block; font: 12px/1.4 system-ui, sans-serif; letter-spacing: 0.1em; text-transform: uppercase; color: #666; }
            |    .h { display: block; font-size: 22px; line-height: 1.25; margin-top: 2px; }
            |    .f { display: block; font: 13px/1.4 system-ui, sans-serif; color: #666; margin-top: 4px; }
            |    nav { text-align: center; margin-top: 28px; font: 14px system-ui, sans-serif; }
            |  </style>
            |</head>
            |<body>
            |  <main>
            |    <h1>$name</h1>
            |    <p class="sub">Archive</p>
            |    <ol>
            |$items
            |    </ol>
            |    <nav><a href="index.html">Today's edition</a></nav>
            |  </main>
            |</body>
            |</html>
            |
            """.trimMargin()
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
