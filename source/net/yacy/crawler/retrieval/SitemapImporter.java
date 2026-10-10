//SitemapImporter.java
//------------------------
//part of YaCy
// SPDX-FileCopyrightText: 2007 Michael Peter Christen <mc@yacy.net)> 
// SPDX-License-Identifier: GPL-2.0-or-later
//first published on http://www.anomic.de
//Frankfurt, Germany, 2007
//
//this file was contributed by Martin Thelian
//last major change: $LastChangedDate$ by $LastChangedBy$
//Revision: $LastChangedRevision$
//
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.crawler.retrieval;

import java.net.MalformedURLException;
import java.util.Date;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.crawler.HarvestProcess;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.document.parser.sitemapParser;
import net.yacy.document.parser.sitemapParser.URLEntry;
import net.yacy.kelondro.data.meta.URIMetadataNode;
import net.yacy.search.Switchboard;

public class SitemapImporter extends Thread {

    private CrawlProfile crawlingProfile = null;
    private static final ConcurrentLog logger = new ConcurrentLog("SITEMAP");
    private DigestURL siteMapURL = null;
    private final Switchboard sb;

    public SitemapImporter(final Switchboard sb, final DigestURL sitemapURL, final CrawlProfile profileEntry) {
    	super("SitemapImporter(" + sitemapURL != null ? sitemapURL.toNormalform(false) : "" + ")");
        assert sitemapURL != null;
        this.sb = sb;
        this.siteMapURL = sitemapURL;
        assert profileEntry != null;
        this.crawlingProfile = profileEntry;
    }

    @Override
    public void run() {
        try {
            logger.info("Start parsing sitemap file " + this.siteMapURL.toNormalform(true));
            sitemapParser.SitemapReader parser = sitemapParser.parse(this.siteMapURL, this.crawlingProfile.getAgent());
            parser.start();
            URLEntry item;
            while ((item = parser.take()) != sitemapParser.POISON_URLEntry) {
                process(item);
            }
        } catch (final Exception e) {
            logger.warn("Unable to parse sitemap file " + this.siteMapURL, e);
        }
    }

    public void process(sitemapParser.URLEntry entry) {

        // get the url hash
        byte[] nexturlhash = null;
        DigestURL url = null;
        try {
            url = new DigestURL(entry.url());
            nexturlhash = url.hash();
        } catch (final MalformedURLException e1) {
        }

        if (url == null) return;

        // the URL filters of the crawl: the crawl stacker applies them only to links (depth > 0), and a sitemap of a
        // whole site (all languages of MDN, all releases) would otherwise fill the queue until the loader rejects them
        if (!inFilters(url.toNormalform(true), this.crawlingProfile.urlMustMatchPattern(), this.crawlingProfile.urlMustNotMatchPattern())) return;

        // check if the url is known and needs to be recrawled
        final Date lastMod = entry.lastmod(null);
        if (lastMod != null) {
            final HarvestProcess dbocc = this.sb.getHarvestProcess(ASCII.String(nexturlhash));
            if (dbocc != null && dbocc == HarvestProcess.LOADED) {
                // the url was already loaded: load it again only if the sitemap says it changed after it was loaded
                final URIMetadataNode oldEntry = this.sb.index.fulltext().getMetadata(nexturlhash);
                if (oldEntry != null && !changedSince(lastMod, oldEntry.loaddate(), oldEntry.moddate())) return;
            }
        }

        // URL needs to crawled
        this.sb.crawlStacker.enqueueEntry(new Request(
                ASCII.getBytes(this.sb.peers.mySeed().hash),
                url,
                null, // this.siteMapURL.toNormalform(false),
                entry.url(),
                entry.lastmod(new Date()),
                this.crawlingProfile.handle(),
                0,
                this.crawlingProfile.timezoneOffset()
                ));
        logger.info("New URL '" + entry.url() + "' added for loading.");
    }

    /** @return true if the url matches the must-match filter and not the must-not-match filter */
    static boolean inFilters(final String url, final java.util.regex.Pattern mustMatch, final java.util.regex.Pattern mustNotMatch) {
        return (mustMatch == null || mustMatch.matcher(url).matches()) && (mustNotMatch == null || !mustNotMatch.matcher(url).matches());
    }

    /**
     * @return true if a page that the sitemap says was modified at lastMod changed after the indexed copy: after it
     *         was loaded and after its own modification date; unknown dates do not count
     */
    static boolean changedSince(final Date lastMod, final Date loaded, final Date modified) {
        if (lastMod == null) return true;
        Date known = loaded;
        if (modified != null && (known == null || modified.after(known))) known = modified;
        return known == null || lastMod.after(known);
    }
}
