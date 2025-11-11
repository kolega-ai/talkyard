/// <reference path="../test-types.ts"/>

import * as _ from 'lodash';
import assert from '../utils/ty-assert';
import * as fs from 'fs';
import server from '../utils/server';
import * as utils from '../utils/utils';
import { buildSite } from '../utils/site-builder';
import { TyE2eTestBrowser, TyAllE2eTestBrowsers } from '../utils/ty-e2e-test-browser';
import { IsWhere } from '../test-types';
import c from '../test-constants';

let allBrowsers: TyAllE2eTestBrowsers;
let brA: TyE2eTestBrowser;
let brB: TyE2eTestBrowser;
let owen: Member;
let owen_brA: TyE2eTestBrowser;
let maria: Member;
let maria_brB: TyE2eTestBrowser;
let memah: Member;
let memah_brB: TyE2eTestBrowser;
let guest_brB: TyE2eTestBrowser;

const localHostname = 'comments-for-e2e-test-embcfeed';
const embeddingOrigin = 'http://e2e-test-embcfeed.localhost:8080';

const comment_feeds = 'comment_feeds';
const Owen_Staff_Only_Title = 'Owen_Staff_Only_Title';
const Owen_staff_only_body = 'Owen_staff_only_body';
const Owen_staff_reply = 'Owen_staff_reply';

let site: IdAddress;
let forum: TwoCatsTestForum;

const staffCatTilte = 'About category Staff Only';

// (Change if needed, e.g. if more stuff incl in feeds, would need to increase.)
const guestEmbComtsSourceMaxLen = 1485;  // with Owen's comment included
const guestForumSourceMaxLen = 2330;

let source = '';
let guestEmbComtsSourceLen = 0;
let guestForumSourceLen = 0;
let adminForumSourceLen = 0;


describe(`embcom.feeds.2br.ec  TyTEC_FEEDS`, () => {

  it(`Construct site`, async () => {
    const builder = buildSite();
    forum = builder.addTwoCatsForum({
      title: "Emb Comments Feeds E2e Test",
      members: ['memah', 'maria', 'michael', 'mallory']
    });

    builder.getSite().meta.localHostname = localHostname;
    builder.getSite().settings.allowEmbeddingFrom = embeddingOrigin;

    allBrowsers = new TyE2eTestBrowser(allWdioBrowsers, 'brAll');
    brA = new TyE2eTestBrowser(wdioBrowserA, 'brA');
    brB = new TyE2eTestBrowser(wdioBrowserB, 'brB');

    owen = forum.members.owen;
    owen_brA = brA;

    maria = forum.members.maria;
    maria_brB = brB;
    memah = forum.members.memah;
    memah_brB = brB;
    guest_brB = brB;

    assert.refEq(builder.getSite(), forum.siteData);
  });

  it(`Import site`, async () => {
    site = await server.importSiteData(forum.siteData);
    server.skipRateLimits(site.id); // 0await
  });


  it(`Create embedding pages`, () => {
    const dir = 'target';
    fs.writeFileSync(`${dir}/page-a-slug.html`, makeHtml('aaa', '#500'));
    fs.writeFileSync(`${dir}/page-b-slug.html`, makeHtml('bbb', '#040'));
    function makeHtml(pageName: St, bgColor: St): St {
      return utils.makeEmbeddedCommentsHtml({
              pageName, discussionId: '', localHostname, bgColor});
    }
  });

  it(`A guest goes to the embedded comments feed`, async () => {
    await guest_brB.go2(site.origin + '/-/v0/embedded-comments-feed',
            { willBeWhere: IsWhere.Feed });
  });
  it(`... sees the feed`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1('Atom', {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/embedded-comments-feed');
  });
  it(`... it's empty — just ${c.EmptyAtomDocLen} chars long`, async () => {
    assert.eq(source.length, c.EmptyAtomDocLen);
  });
  it(`... does not incl the staff-only category, ${staffCatTilte}`, async () => {
    assert.excludes(source, staffCatTilte);
  });

  it(`The guest looks at the whole site feed`, async () => {
    await guest_brB.go2('/-/v0/feed', { willBeWhere: IsWhere.Feed });
  });
  it(`... it's not empty — includes Category A's about page`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1('CategoryA', {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/feed');
  });
  it(`... length > ${c.EmptyAtomDocLen}`, async () => {
    assert.that(source.length > c.EmptyAtomDocLen);
  });
  it(`... does not incl the staff-only category, ${staffCatTilte}`, async () => {
    assert.excludes(source, staffCatTilte);
  });


  it(`Owen goes to emb page a`, async () => {
    await owen_brA.go2(embeddingOrigin + '/page-a-slug.html');
  });
  it(`... logs in`, async () => {
    await owen_brA.complex.loginIfNeededViaMetabar(owen);
  });
  it(`... posts a comment`, async () => {
    await owen_brA.complex.replyToEmbeddingBlogPost(
          `I fed my cat with ${comment_feeds} but it got more and more hungry`);
  });


  it(`The guest looks at the embedded comments feed again`, async () => {
    await guest_brB.go2('/-/v0/embedded-comments-feed', { willBeWhere: IsWhere.Feed });
  });
  it(`... sees Owen's comment`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1(comment_feeds, {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/embedded-comments-feed');
  });
  it(`... is longer now, with the comment included  ttt`, async () => {
    assert.that(source.length > c.EmptyAtomDocLen);
  });
  it(`... but <= guestEmbComtsSourceMaxLen = ${guestEmbComtsSourceMaxLen} chars`, async () => {
    guestEmbComtsSourceLen = source.length
    console.log('guestEmbComtsSourceLen: ' + guestEmbComtsSourceLen);
    assert.that(guestEmbComtsSourceLen <= guestEmbComtsSourceMaxLen);
  });


  it(`The guest looks at the whole site feed`, async () => {
    await guest_brB.go2('/-/v0/feed', { willBeWhere: IsWhere.Feed });
  });
  it(`... sees Owen's comment there too`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1(comment_feeds, {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/feed');
  });
  it(`... does not see the staff-only category: ${staffCatTilte}`, async () => {
    assert.excludes(source, staffCatTilte);
  });
  it(`... is at most ${guestForumSourceMaxLen} chars long`, async () => {
    guestForumSourceLen = source.length
    console.log(`${guestForumSourceLen} <= ${guestForumSourceMaxLen}`);
    assert.that(guestForumSourceLen <= guestForumSourceMaxLen);
  });


  it(`Owen looks at the whole site feed`, async () => {
    await owen_brA.go2(site.origin + '/-/v0/feed', { willBeWhere: IsWhere.Feed });
  });
  it(`... sees his comment in the feed`, async () => {
    await owen_brA.waitUntilPageHtmlSourceMatches_1(comment_feeds, {
            refreshBetween: true });
    source = await owen_brA.getPageSource();
    assert.includes(source, '/-/v0/feed');
  });
  it(`... DOES see the staff-only category: ${staffCatTilte}`, async () => {
    assert.includes(source, staffCatTilte);
  });
  it(`... feed length is > ${guestForumSourceMaxLen} chars`, async () => {
    adminForumSourceLen = source.length
    assert.that(adminForumSourceLen > guestForumSourceMaxLen); 
  });

  it(`Owen posts a staff-only topic and comment`, async () => {
    await owen_brA.go2('/latest/staff-only');
    await owen_brA.complex.createAndSaveTopic({
            title: Owen_Staff_Only_Title, body: Owen_staff_only_body });
    await owen_brA.complex.replyToOrigPost(Owen_staff_reply);
  });
  it(`... looks at the whole site feed`, async () => {
    await owen_brA.go2('/-/v0/feed', { willBeWhere: IsWhere.Feed });
  });
  it(`... sees the new staff-only page & comment`, async () => {
    await owen_brA.waitUntilPageHtmlSourceMatches_1(Owen_staff_reply, {
            refreshBetween: true });
  });
  it(`... and title & orig post`, async () => {
    source = await owen_brA.getPageSource();
    assert.includes(source, Owen_Staff_Only_Title);
    assert.includes(source, Owen_staff_only_body);
  });


  it(`The guest, though ...`, async () => {
    await guest_brB.refresh2({ isWhere: IsWhere.Feed });
  });
  it(`... does NOT see the new staff-only stuff  TyTFEED_0PRIV`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1(comment_feeds, {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/feed');
    assert.excludes(source, staffCatTilte);
    assert.excludes(source, Owen_Staff_Only_Title);
    assert.excludes(source, Owen_staff_only_body);
    assert.excludes(source, Owen_staff_reply);
  });
  it(`... is still at most ${guestForumSourceMaxLen} chars`, async () => {
    guestForumSourceLen = source.length
    console.log(`${guestForumSourceLen} <= ${guestForumSourceMaxLen}`);
    assert.that(guestForumSourceLen <= guestForumSourceMaxLen);
  });


  it(`Owen goes to CategoryA`, async () => {
    await owen_brA.go2('/latest/category-a');
  });
  it(`... makes it accessible to members only  TyTFEED_0PRIV`, async () => {
    await owen_brA.forumButtons.clickEditCategory();
    await owen_brA.categoryDialog.openSecurityTab();
    await owen_brA.categoryDialog.securityTab.switchGroupFromTo(
          c.EveryoneFullName, c.AllMembersFullName);
    await owen_brA.categoryDialog.submit();
  });


  it(`The guest now ...`, async () => {
    await guest_brB.go2(site.origin);  // so waitUntilPageHtmlSourceMatches_1() below will work
    await guest_brB.go2(site.origin + '/-/v0/embedded-comments-feed',
            { willBeWhere: IsWhere.Feed });
  });
  it(`... sees nothing at all in the feed  TyTFEED_0PRIV`, async () => {
    await guest_brB.waitUntilPageHtmlSourceMatches_1('Atom', {
            refreshBetween: true });
    source = await guest_brB.getPageSource();
    assert.includes(source, '/-/v0/embedded-comments-feed');
    assert.excludes(source, staffCatTilte);
    assert.excludes(source, Owen_Staff_Only_Title);
    assert.excludes(source, Owen_staff_only_body);
    assert.excludes(source, Owen_staff_reply);
  });
  it(`... also the "${comment_feeds}" text is gone`, async () => {
    assert.excludes(source, comment_feeds);
  });
  it(`... it's empty, <= ${c.EmptyAtomDocLen} chars long`, async () => {
    guestForumSourceLen = source.length
    console.log(`${guestForumSourceLen} <= ${c.EmptyAtomDocLen}`);
  });

  /* But  /-/v0/feed  includes an entry for the forum title & intro post,
     so it's not empty, even if all categories are inaccessible:

        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>comments-for-e2e-test-embcfeed.localhost</title>
          <id>http://comments-for-e2e-test-embcfeed.localhost/-/v0/feed</id>
          <link href="http://comments-for-e2e-test-embcfeed.localhost/-/v0/feed"
                rel="self" type="application/atom+xml"/>
          <updated>2015-12-04T03:13:44Z</updated>
          <author>
            <name>comments-for-e2e-test-embcfeed.localhost</name>
          </author>
          <generator uri="https://www.talkyard.io">Talkyard</generator>
          <entry>
            <id>http://comments-for-e2e-test-embcfeed.localhost/-1#post-1</id>
     ——>    <title>Emb Comments Feeds E2e Test</title>
            <updated>2015-12-04T03:13:44Z</updated>
            <content type="xhtml">
              <div xmlns="http://www.w3.org/1999/xhtml">
     ——>        <p>Forum intro text.</p>
              </div>
            </content>
          </entry>
        </feed> */


  it(`Maria logs in`, async () => {
    await maria_brB.go2(site.origin);
    await maria_brB.complex.loginWithPasswordViaTopbar(maria);
  });

  it(`... goes to the feed`, async () => {
    await maria_brB.go2(site.origin + '/-/v0/feed',
            { willBeWhere: IsWhere.Feed });
  });
  it(`... sees the stuff in CategoryA — she's a member, can see Cat A`, async () => {
    await maria_brB.waitUntilPageHtmlSourceMatches_1('Atom', {
            refreshBetween: true });
    source = await maria_brB.getPageSource();
    assert.includes(source, '/-/v0/feed');
    assert.includes(source, 'CategoryA');
    assert.includes(source, comment_feeds);
  });
  it(`... not the staff cat  TyTFEED_0PRIV`, async () => {
    assert.excludes(source, staffCatTilte);
    assert.excludes(source, Owen_Staff_Only_Title);
    assert.excludes(source, Owen_staff_only_body);
    assert.excludes(source, Owen_staff_reply);
  });



  // TESTS_MISSING TyTFEED_ONLYEMBCOM: Make sure not-embedded-comments posts
  // won't appear in the embedded-comments-feed.

});

