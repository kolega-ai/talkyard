/// <reference path="../test-types.ts"/>

import * as _ from 'lodash';
import assert from '../utils/ty-assert';
import server from '../utils/server';
import * as make from '../utils/make';
import { buildSite } from '../utils/site-builder';
import { TyE2eTestBrowser } from '../utils/ty-e2e-test-browser';
import c from '../test-constants';


let richBrowserA;
let richBrowserB;
let owen: Member;
let owensBrowser: TyE2eTestBrowser;
let merche: Member;
let merchesBrowser: TyE2eTestBrowser;
let meilani: Member;
let meilanisBrowser: TyE2eTestBrowser;
let strangersBrowser: TyE2eTestBrowser;

let siteIdAddress: IdAddress;
let siteId;

let forum: TwoPagesTestForum;

const chatMessageOneStays = 'chatMessageOneStays';
const chatMessageTwoDeleted = 'chatMessageTwoDeleted';
const origPostReplyOneStays = 'origPostReplyOneStays';
const origPostReplyTwoDeleted = 'origPostReplyTwoDeleted';
const aReplyToApprove = 'aReplyToApprove';
const meilanianisReplyOne = 'meilanianisReplyOne';
const meilanianisReplyTwo = 'meilanianisReplyTwo';

let discussionPageUrl: string;
let chatPageUrl: string;


describe("admin-review-cascade-approval  TyT0SKDE24", () => {

  it("import a site", async () => {
    const builder = buildSite();
    forum = builder.addTwoPagesForum({
      title: "Cascade Approvals E2E Test",
      members: undefined, // default = everyone
    });
    const chatPage = builder.addPage({
      id: 'chat_page_id',
      folder: '/',
      showId: false,
      slug: 'chat-page',
      role: c.TestPageRole.OpenChat,
      title: "Chat Page",
      body: "Chat here, on this chat page.",
      categoryId: forum.categories.categoryA.id,
      authorId: forum.members.mallory.id,
    });
    assert.refEq(builder.getSite(), forum.siteData);

    const siteData = forum.siteData;
    siteData.settings.requireVerifiedEmail = false;
    siteData.settings.maxPostsPendApprBefore = 9;
    siteData.settings.numFirstPostsToApprove = 1;

    siteIdAddress = await server.importSiteData(forum.siteData);
    siteId = siteIdAddress.id;
    server.skipRateLimits(siteId); // 0await
    discussionPageUrl = siteIdAddress.origin + '/' + forum.topics.byMichaelCategoryA.slug;
    chatPageUrl = siteIdAddress.origin + '/' + chatPage.slug;
  });

  it("initialize people", async () => {
    richBrowserA = new TyE2eTestBrowser(wdioBrowserA, 'brA');
    richBrowserB = new TyE2eTestBrowser(wdioBrowserB, 'brB');

    owen = forum.members.owen;
    owensBrowser = richBrowserA;

    merche = make.memberMerche();
    merchesBrowser = richBrowserB;
    meilani = make.memberMeilani();
    meilanisBrowser = richBrowserB;
    strangersBrowser = richBrowserB;
  });

  it("Merche goes to the chat page", async () => {
    await merchesBrowser.go2(chatPageUrl);
  });

  it("... clicks Join Chat", async () => {
    await merchesBrowser.chat.joinChat();
  });

  it("... signs up", async () => {
    await merchesBrowser.loginDialog.createPasswordAccount(merche);
  });

  it("... verifies her email", async () => {
    const url = await server.waitAndGetLastVerifyEmailAddressLinkEmailedTo(
      siteIdAddress.id, merche.emailAddress);
    await merchesBrowser.go2(url);
    await merchesBrowser.hasVerifiedSignupEmailPage.clickContinue();
  });

  it("... clicks Jon Chat again", async () => {
    await merchesBrowser.chat.joinChat();
  });

  it("... posts a chat message", async () => {
    await merchesBrowser.chat.addChatMessage(chatMessageOneStays);
    await merchesBrowser.chat.waitForNumMessages(1);
  });

  it("... some time elapses, so next message won't get merged", async () => {
    await server.playTimeMinutes(30);
  });

  it("... she posts another message", async () => {
    await merchesBrowser.chat.addChatMessage(chatMessageTwoDeleted);
    await merchesBrowser.chat.waitForNumMessages(2);
  });

  it("... but deletes this one  TyT052SKDGJ37", async () => {
    await merchesBrowser.chat.deleteChatMessageNr(c.FirstReplyNr + 1);
  });

  it("Merche goes to the discussion page", async () => {
    await merchesBrowser.go2(discussionPageUrl);
  });

  it("... posts a reply", async () => {
    await merchesBrowser.complex.replyToOrigPost(origPostReplyOneStays);
  });

  it("... and another reply", async () => {
    await merchesBrowser.complex.replyToOrigPost(origPostReplyTwoDeleted);
  });

  it("... but deletes this one", async () => {
    await merchesBrowser.topic.deletePost(c.FirstReplyNr + 1);
  });

  it("... and one last reply, which Owen will approve", async () => {
    await merchesBrowser.complex.replyToOrigPost(aReplyToApprove);
  });

  it("Merche leaves, Meilani arrives and signs up", async () => {
    await merchesBrowser.topbar.clickLogout();
    await meilanisBrowser.complex.signUpAsMemberViaTopbar(meilani);
  });

  it("... verifies her email address", async () => {
    const url = await server.waitAndGetLastVerifyEmailAddressLinkEmailedTo(
      siteIdAddress.id, meilani.emailAddress);
    await meilanisBrowser.go2(url);
    await meilanisBrowser.hasVerifiedSignupEmailPage.clickContinue();
  });

  it("Meilani posts two replies", async () => {
    await meilanisBrowser.complex.replyToOrigPost(meilanianisReplyOne);
    await meilanisBrowser.complex.replyToOrigPost(meilanianisReplyTwo);
    await meilanisBrowser.topic.assertNumRepliesVisible(2 + 2);  // Merche's + Meilani's
  });

  it("... then leaves", async () => {
    await meilanisBrowser.topbar.clickLogout();
  });

  it("The replies appear as unapproved", async () => {
    const counts = await strangersBrowser.topic.countReplies();
    assert.deepEq(counts, {
          numNormal: 0, numDrafts: 0, numPreviews: 0, numUnapproved: 4, numDeleted: 0 });
  });


  // TESTS_MISSING:
  //   posts a new topic,
  //   and another topic but deletes that one.


  it("Owen logs in to the admin area, the review tab", async () => {
    await owensBrowser.adminArea.review.goHere(siteIdAddress.origin, { loginAs: owen });
  });

  it("There are 2 + 3 + 2 posts waiting for review", async () => {
    assert.eq(await owensBrowser.adminArea.review.countThingsToReview(), 7);
  });

  it("Owen approves Merche's most recent post", async () => {
    await owensBrowser.adminArea.review.approvePostForTaskIndex(3);
  });

  it("... the server carries out the review task", async () => {
    await owensBrowser.adminArea.review.playTimePastUndo();
    await owensBrowser.adminArea.review.waitForServerToCarryOutDecisions();
  });

  it("... this cascade-approves Merche's other posts", async () => {
    let counts: NumReplies;
    await strangersBrowser.refreshUntil(async () => {
      counts = await strangersBrowser.topic.countReplies();
      return counts.numNormal === 2;
    });
    assert.deepEq(counts, {
          numNormal: 2, numDrafts: 0, numPreviews: 0, numUnapproved: 2, numDeleted: 0 });
    // Apparently can take a short while before React has shown the .dw-p (post body),
    // this error happened:
    //     "Text match failure, selector:  #post-2 .dw-p-bd,  No elems match the selector."
    await strangersBrowser.topic.waitForPostNrVisible(c.FirstReplyNr);
  });

  // Without running into any errors because some of those posts have:
  //  - Been auto approved already by the System user,
  //    namely a chat message.
  //  - Been deleted already, by Merche.
  //

  it("... Merche's posts, not Meilaniani's", async () => {
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr, origPostReplyOneStays);
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr + 2, aReplyToApprove);
  });


  it("Owen approves Meilaniani's most recent post", async () => {
    await owensBrowser.adminArea.review.approvePostForMostRecentTask();
  });

  it("... the server obeys", async () => {
    await owensBrowser.adminArea.review.playTimePastUndo();
    await owensBrowser.adminArea.review.waitForServerToCarryOutDecisions();
  });

  it("... namely cascade-approves Meilani's posts too", async () => {
    let counts: NumReplies;
    await strangersBrowser.refreshUntil(async () => {
      counts = await strangersBrowser.topic.countReplies();
      return counts.numNormal === 4;
    });
    assert.deepEq(counts, {
            numNormal: 4, numDrafts: 0, numPreviews: 0, numUnapproved: 0, numDeleted: 0 });
  });

  it("... with the correct text contents", async () => {
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr, origPostReplyOneStays);
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr + 2, aReplyToApprove);
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr + 3, meilanianisReplyOne);
    await strangersBrowser.topic.assertPostTextMatches(c.FirstReplyNr + 4, meilanianisReplyTwo);
  });

});

