/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import scala.collection.Seq
import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.core.PageParts.{BodyNr, TitleNr}
import debiki.EdHttp.ResultException
import debiki.dao.ErrCodes._
import talkyard.server.authz.{ReqrAndTgt, StaffReqrAndTgt}
import java.{util => ju}


class MovePostsAppSpec extends DaoAppSuite(disableScripts = true, disableBackgroundJobs = true) {
  var dao: SiteDao = _
  var theModerator: Pat = _
  var theMember: Pat = _
  var theMemb2: Pat = _
  var theMemb3: Pat = _

  // We _need_4_voters.
  var theVoter: Pat = _
  var theVoter2: Pat = _
  var theVoter3: Pat = _
  var theVoter4: Pat = _

  var theReqrMember: ReqrAndTgt = _
  var theReqrMemb2: ReqrAndTgt = _
  var theReqrMemb3: ReqrAndTgt = _

  var theReqrVoter: ReqrAndTgt = _
  var theReqrVoter2: ReqrAndTgt = _
  var theReqrVoter3: ReqrAndTgt = _
  var theReqrVoter4: ReqrAndTgt = _

  var theReqrStaff: StaffReqrAndTgt = _

  var catA: Cat = _
  var staffCat: Cat = _


  "The Dao can move posts  TyTMOPO" - {
    val now = new ju.Date()

    "prepare" in {
      globals.systemDao.getOrCreateFirstSite()
      dao = globals.siteDao(Site.FirstSiteId)

      val createForumResult = dao.createForum(
            title = "Move Pages Forum", folder = "/movepagesforum/", isForEmbCmts = false,
            Who(SystemUserId, browserIdData)).get
      val forumPageId = createForumResult.pagePath.pageId

      catA = createCategory(
            slug = "cat-a",
            forumPageId = forumPageId,
            parentCategoryId = createForumResult.rootCategoryId,
            authorId = SystemUserId,
            browserIdData,
            dao).category

      staffCat = createCategory(
            slug = "staff-cat",
            forumPageId = forumPageId,
            parentCategoryId = createForumResult.rootCategoryId,
            authorId = SystemUserId,
            browserIdData,
            dao,
            staffOnly = true).category


      createPasswordOwner("mv_ownr", dao)
      theModerator = createPasswordModerator("move_mod", dao)
      theMember = createPasswordUser("move_mbr", dao)
      theMemb2 = createPasswordUser("move_mb2", dao)
      theMemb3 = createPasswordUser("move_mb3", dao)
      // Is mod, so can Bury and Unwanted vote.
      theVoter = createPasswordModerator("move_vot", dao)
      theVoter2 = createPasswordModerator("move_vo2", dao)
      theVoter3 = createPasswordModerator("move_vo3", dao)
      theVoter4 = createPasswordModerator("move_vo4", dao)
      theReqrMember = ReqrAndTgt.self(theMember, BrowserIdData.Test)
      theReqrMemb2 = ReqrAndTgt.self(theMemb2, BrowserIdData.Test)
      theReqrMemb3 = ReqrAndTgt.self(theMemb3, BrowserIdData.Test)
      theReqrVoter = ReqrAndTgt.self(theVoter, BrowserIdData.Test)
      theReqrVoter2 = ReqrAndTgt.self(theVoter2, BrowserIdData.Test)
      theReqrVoter3 = ReqrAndTgt.self(theVoter3, BrowserIdData.Test)
      theReqrVoter4 = ReqrAndTgt.self(theVoter4, BrowserIdData.Test)
      theReqrStaff = ReqrAndTgt.self(theModerator, BrowserIdData.Test).denyUnlessStaff()

      // Just so we know the ids, if need to debug this spec.
      // owner = 100
      theModerator.id mustBe 101
      theMember.id mustBe 102
      theMemb2.id mustBe 103
      theMemb3.id mustBe 104
      theVoter.id mustBe 105
      theVoter2.id mustBe 106
      theVoter3.id mustBe 107
      theVoter4.id mustBe 108

      // No, let's test category access perms combined with moving posts, instead of:
      //letEveryoneTalkAndStaffModerate(dao)
    }

    "move one posts to eleswhere on the same page" in {
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Title"),
            textAndHtmlMaker.testBody("body"), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val firstParent = reply(theModerator.id, thePageId, "1st parent")(dao)
      val secondParent = reply(theModerator.id, thePageId, "2nd parent")(dao)
      val postToMove = reply(theMember.id, thePageId, "to move", Some(firstParent.nr))(dao)
      val otherToMove = reply(theMember.id, thePageId, "other2move", Some(firstParent.nr))(dao)
      val postByStaff = reply(theModerator.id, thePageId, "by staff", Some(firstParent.nr))(dao)
      val metaBefore = dao.readOnlyTransaction(_.loadThePageMeta(thePageId))

      info("non-staff may not move someone else's post")
      intercept[ResultException] {
        dao.movePostIfAuth(
              postByStaff.pagePostId, MovePostWhere.UnderOldPost(secondParent.pagePostNr),
              // Not staff, and not hans post:
              theReqrMember)
      }.getMessage must include(TyE_MayNotMovePost_MayNotEdit)

      info("non-staff can move their own post")
      var postAfter = dao.movePostIfAuth(
            postToMove.pagePostId, MovePostWhere.UnderOldPost(secondParent.pagePostNr),
            theReqrMember)._1
      postAfter.parentNr mustBe Some(secondParent.nr)
      var reloadedPost = dao.readTx(_.loadThePost(postToMove.id))
      reloadedPost.parentNr mustBe Some(secondParent.nr)

      info("staff may move other people's posts though")
      postAfter = dao.movePostIfAuth(
            // Not the mod's post, but han can move it anyway.
            otherToMove.pagePostId, MovePostWhere.UnderOldPost(secondParent.pagePostNr),
            theReqrStaff)._1
      postAfter.parentNr mustBe Some(secondParent.nr)
      reloadedPost = dao.readTx(_.loadThePost(otherToMove.id))
      reloadedPost.parentNr mustBe Some(secondParent.nr)

      info("staff can move their own posts too of course")
      postAfter = dao.movePostIfAuth(
            postByStaff.pagePostId, MovePostWhere.UnderOldPost(secondParent.pagePostNr),
            theReqrStaff)._1
      postAfter.parentNr mustBe Some(secondParent.nr)
      reloadedPost = dao.readTx(_.loadThePost(postByStaff.id))
      reloadedPost.parentNr mustBe Some(secondParent.nr)

      info("page meta unchanged  (posts moved within the same page)")
      val metaAfter = dao.readTx(_.loadThePageMeta(thePageId))
      metaBefore mustBe metaAfter
    }

    "won't do bad things" in {
      val thePageId: PageId = createPage2(
            PageType.Discussion, textAndHtmlMaker.testTitle("Title"),
            textAndHtmlMaker.testBody("body"), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id)).id
      val staffPageResult: CreatePageResult = createPage2(
            PageType.Discussion, textAndHtmlMaker.testTitle("Staff_Pg"),
            textAndHtmlMaker.testBody("Staff_body."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(staffCat.id))
      val staffPageId = staffPageResult.id
      val staffPageBody = staffPageResult.bodyPost
      val firstReply = reply(theMember.id, thePageId, "1st reply")(dao)
      val secondReply = reply(theMember.id, thePageId, "2nd reply")(dao)
      val staffPageReply = reply(theModerator.id, staffPageId, "Staff_only_reply")(dao)
      val (titleId, bodyId) = dao.readOnlyTransaction { transaction =>
        (transaction.loadThePost(thePageId, TitleNr).id,
         transaction.loadThePost(thePageId, BodyNr).id)
      }

      info("refuses to move orig post title")
      intercept[ResultException] {
        dao.movePostIfAuth(
              PagePostId(thePageId, titleId), MovePostWhere.UnderOldPost(secondReply.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("EsE7YKG25_")

      info("refuses to move orig post body")
      intercept[ResultException] {
        dao.movePostIfAuth(
              PagePostId(thePageId, bodyId), MovePostWhere.UnderOldPost(secondReply.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("EsE7YKG25_")

      info("refuses to place reply below title")
      intercept[ResultException] {
        dao.movePostIfAuth(
              PagePostId(thePageId, secondReply.id),
              MovePostWhere.UnderOldPost(PagePostNr(thePageId, TitleNr)),
              theReqrStaff)._1
      }.getMessage must include("EsE4YKJ8_")

      info("won't try to move a post that doesn't exist")
      intercept[ResultException] {
        dao.movePostIfAuth(
              PagePostId(thePageId, 9999), MovePostWhere.UnderOldPost(secondReply.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("TyEMOPO_PONF_")

      info("refuses to place reply below non-existing post")
      intercept[ResultException] {
        dao.movePostIfAuth(
              secondReply.pagePostId,
              MovePostWhere.UnderOldPost(PagePostNr(thePageId, 9999)),
              theReqrStaff)._1
      }.getMessage must include("EsE7YKG42_")

      info("checks permissions:  won't move a post *on* a page you can't see")
      var ex = intercept[ResultException] {
        dao.movePostIfAuth(
              staffPageReply.pagePostId,  // can't access (can't see)
              MovePostWhere.UnderOldPost(secondReply.pagePostNr),
              theReqrMember)._1  // not staff
      }
      ex.getMessage must include(TyE_MayNotMovePost_MayNotEdit)
      ex.getMessage must include(TyE_MayNotEdit_CantSeePage)

      info("checks permissions:  won't move a post *to* a page you can't see")
      ex = intercept[ResultException] {
        dao.movePostIfAuth(
              secondReply.pagePostId,
              MovePostWhere.UnderOldPost(staffPageReply.pagePostNr),  // can't access
              theReqrMember)._1  // not staff
      }
      ex.getMessage must include(TyE_MayNotMovePost_MayNotReply)
      ex.getMessage must include(TyE_MayNotReply_CantSeePage)
      // This would be the wrong reason. The Edit permission is needed only on the
      // post being moved — not on the destination page. On the dest page, we need
      // the Reply permission only (and the See permission of course).
      ex.getMessage must not include TyE_MayNotEdit_CantSeePage

      info("checks permissions:  staff can move comments on staff-only pages, though")
      dao.movePostIfAuth(
            staffPageReply.pagePostId, // staff can access
            MovePostWhere.UnderOldPost(secondReply.pagePostNr),
            theReqrStaff)._1  // is staff

      TESTS_MISSING; COULD // check page meta: one less reply, one more reply. TyTMOPO_META

      // This moves the reply back to the staff-only page.
      info("checks permissions:  staff can move to staff-only pages, too")
      dao.movePostIfAuth(
            PagePostId(thePageId, staffPageReply.id),
            MovePostWhere.UnderOldPost(staffPageBody.pagePostNr), // staff can access
            theReqrStaff)._1  // is staff
    }

    "won't create cycles" in {   // [TyTMOVEPOST692]
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Title"),
            textAndHtmlMaker.testBody("body"), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postA = reply(theModerator.id, thePageId, "A")(dao)
      val postB = reply(theModerator.id, thePageId, "B", parentNr = Some(postA.nr))(dao)
      val postC = reply(theModerator.id, thePageId, "C", parentNr = Some(postB.nr))(dao)
      val postC2 = reply(theModerator.id, thePageId, "C2", parentNr = Some(postB.nr))(dao)
      val postD = reply(theModerator.id, thePageId, "D", parentNr = Some(postC.nr))(dao)

      info("won't create A —> A")
      intercept[ResultException] {
        dao.movePostIfAuth(postA.pagePostId, MovePostWhere.UnderOldPost(postA.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("TyE7SRJ2MG_")

      info("won't create A —> B —> A")
      intercept[ResultException] {
        dao.movePostIfAuth(postA.pagePostId, MovePostWhere.UnderOldPost(postB.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("EsE7KCCL_")

      info("won't create A —> B –> C —> A")
      intercept[ResultException] {
        dao.movePostIfAuth(postA.pagePostId, MovePostWhere.UnderOldPost(postC.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("EsE7KCCL_")

      info("agrees to move D from C to C2, fine")
      dao.movePostIfAuth(postD.pagePostId, MovePostWhere.UnderOldPost(postC2.pagePostNr),
            theReqrStaff)._1
      val reloadedD = dao.readOnlyTransaction(_.loadThePost(postD.id))
      reloadedD.parentNr mustBe Some(postC2.nr)

      info("won't create C2 —> D —> C2")
      intercept[ResultException] {
        dao.movePostIfAuth(postC2.pagePostId, MovePostWhere.UnderOldPost(postD.pagePostNr),
              theReqrStaff)._1
      }.getMessage must include("EsE7KCCL_")

      info("but agrees to move C from to D, fine")
      dao.movePostIfAuth(postC.pagePostId, MovePostWhere.UnderOldPost(postD.pagePostNr),
            theReqrStaff)._1
      val reloadedC = dao.readOnlyTransaction(_.loadThePost(postC.id))
      reloadedC.parentNr mustBe Some(postD.nr)
    }

    "move a post A... with many descendants to X –> Y —> A..." in {
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Title"),
            textAndHtmlMaker.testBody("body"), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postA = reply(theModerator.id, thePageId, "A")(dao)
      val postB = reply(theModerator.id, thePageId, "B", parentNr = Some(postA.nr))(dao)
      val postC = reply(theModerator.id, thePageId, "C", parentNr = Some(postB.nr))(dao)
      val postC2 = reply(theModerator.id, thePageId, "C2", parentNr = Some(postB.nr))(dao)
      val postX = reply(theModerator.id, thePageId, "X")(dao)
      val postY = reply(theModerator.id, thePageId, "Y", parentNr = Some(postX.nr))(dao)

      dao.movePostIfAuth(
            postA.pagePostId, MovePostWhere.UnderOldPost(postY.pagePostNr),
            theReqrStaff)._1

      dao.readOnlyTransaction { transaction =>
        val pageParts = dao.newPageDao(thePageId, transaction).parts
        pageParts.thePostByNr(postY.nr).parentNr mustBe Some(postX.nr)
        pageParts.ancestorsParentFirstOf(postY.nr).map(_.nr) mustBe Seq(postX.nr, BodyNr)
        pageParts.ancestorsParentFirstOf(postA.nr).map(_.nr) mustBe Seq(postY.nr, postX.nr, BodyNr)
        pageParts.ancestorsParentFirstOf(postC.nr).map(_.nr) mustBe Seq(
          postB.nr, postA.nr, postY.nr, postX.nr, BodyNr)
      }
    }

    "move one post to another page" in {
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page One"),
            textAndHtmlMaker.testBody("Body one."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))

      val pageTwoId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page Two"),
            textAndHtmlMaker.testBody("Body two."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postOnPageTwo = dao.insertReplySkipAuZ(textAndHtmlMaker.testBody("Post on page 2."), pageTwoId,
        replyToPostNrs = Set(PageParts.BodyNr), PostType.Normal, deleteDraftNr = None,
        Who(SystemUserId, browserIdData = browserIdData), dummySpamRelReqStuff).post

      // Create after page 2 so becomes the most recent one.
      playTimeMillis(1000)
      val post = reply(theModerator.id, thePageId, "A post.")(dao)

      val fromPageMetaBefore = dao.readOnlyTransaction(_.loadThePageMeta(thePageId))
      val toPageMetaBefore = dao.readOnlyTransaction(_.loadThePageMeta(pageTwoId))

      info("move it")
      val postAfter = dao.movePostIfAuth(
            post.pagePostId, MovePostWhere.UnderOldPost(postOnPageTwo.pagePostNr),
            theReqrStaff)._1

      postAfter.pageId mustBe pageTwoId
      postAfter.parentNr mustBe Some(postOnPageTwo.nr)

      val reloadedPost = dao.readOnlyTransaction(_.loadThePost(post.id))
      reloadedPost.pageId mustBe pageTwoId
      reloadedPost.parentNr mustBe Some(postOnPageTwo.nr)

      info("from page meta properly updated")
      val fromPageMetaAfter = dao.readOnlyTransaction(_.loadThePageMeta(thePageId))
      fromPageMetaAfter mustBe fromPageMetaBefore.copy(
        updatedAt = fromPageMetaAfter.updatedAt,
        frequentPosterIds = Nil,
        lastApprovedReplyAt = None,
        lastApprovedReplyById = None,
        numOrigPostRepliesVisible = fromPageMetaBefore.numRepliesVisible - 1,
        numRepliesVisible = fromPageMetaBefore.numRepliesVisible - 1,
        numRepliesTotal = fromPageMetaBefore.numRepliesTotal - 1,
        numPostsTotal = fromPageMetaBefore.numPostsTotal - 1,
        version = toPageMetaBefore.version + 1)

      info("to page meta properly updated")
      val toPageMetaAfter = dao.readOnlyTransaction(_.loadThePageMeta(pageTwoId))
      toPageMetaAfter mustBe toPageMetaBefore.copy(
        updatedAt = toPageMetaAfter.updatedAt,
        // Should the target page get bumped? Sometimes, one wants that, other cases not.
        // Maybe better bump it then, since sometimes one wants it bumped?
        // For now, this will make the test pass: (maybe later, could check the actual value)
        bumpedAt = fromPageMetaAfter.bumpedAt,
        // The System user = OP author, so skipped. The moved post = skipped since is most recent.
        frequentPosterIds = Nil,
        lastApprovedReplyAt = Some(postAfter.createdAt),
        lastApprovedReplyById = Some(postAfter.createdById),
        numOrigPostRepliesVisible = toPageMetaBefore.numRepliesVisible + 0, // not an OP reply
        numRepliesVisible = toPageMetaBefore.numRepliesVisible + 1,
        numRepliesTotal = toPageMetaBefore.numRepliesTotal + 1,
        numPostsTotal = toPageMetaBefore.numPostsTotal + 1,
        version = toPageMetaBefore.version + 1)

      info("post read stats moved to new page")
    }

    "move a tree to another page" in {
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page One"),
            textAndHtmlMaker.testBody("Body one."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postA = reply(theModerator.id, thePageId, "A")(dao)
      val postB = reply(theModerator.id, thePageId, "B", parentNr = Some(postA.nr))(dao)
      val postC = reply(theModerator.id, thePageId, "C", parentNr = Some(postB.nr))(dao)
      val postD = reply(theModerator.id, thePageId, "D", parentNr = Some(postC.nr))(dao)
      val postD2 = reply(theModerator.id, thePageId, "D2", parentNr = Some(postC.nr))(dao)
      val otherPost = reply(theModerator.id, thePageId, "Other")(dao)

      val pageTwoId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page Two"),
            textAndHtmlMaker.testBody("Body two."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postOnPageTwo = dao.insertReplySkipAuZ(textAndHtmlMaker.testBody("Post on page 2."), pageTwoId,
        replyToPostNrs = Set(PageParts.BodyNr), PostType.Normal, deleteDraftNr = None,
        Who(SystemUserId, browserIdData = browserIdData), dummySpamRelReqStuff).post

      info("can move the tree")
      val postAfterMove = dao.movePostIfAuth(postA.pagePostId,
            MovePostWhere.UnderOldPost(postOnPageTwo.pagePostNr),
            theReqrStaff)._1
      postAfterMove.pageId mustBe pageTwoId
      postAfterMove.parentNr mustBe Some(postOnPageTwo.nr)

      info("tree gone one first page, present on second instead")
      val maxNewNr = dao.readOnlyTransaction { transaction =>
        val firstParts = dao.newPageDao(thePageId, transaction).parts
        firstParts.postByNr(postA.nr) mustBe None
        firstParts.postByNr(postB.nr) mustBe None
        firstParts.postByNr(postC.nr) mustBe None
        firstParts.postByNr(postD.nr) mustBe None
        firstParts.postByNr(postD2.nr) mustBe None

        val secondPage = dao.newPageDao(pageTwoId, transaction)
        val postAAfter = secondPage.parts.thePostById(postA.id)
        val postBAfter = secondPage.parts.thePostById(postB.id)
        val postCAfter = secondPage.parts.thePostById(postC.id)
        val postDAfter = secondPage.parts.thePostById(postD.id)
        val postD2After = secondPage.parts.thePostById(postD2.id)

        postAAfter.parentNr mustBe Some(postOnPageTwo.nr)
        postBAfter.parentNr mustBe Some(postAAfter.nr)
        postCAfter.parentNr mustBe Some(postBAfter.nr)
        postDAfter.parentNr mustBe Some(postCAfter.nr)
        postD2After.parentNr mustBe Some(postCAfter.nr)

        secondPage.parts.ancestorsParentFirstOf(postD2After.nr).map(_.nr) mustBe Seq(
          postCAfter.nr, postBAfter.nr, postAAfter.nr, postOnPageTwo.nr, BodyNr)

        secondPage.parts.highestReplyNr getOrDie "EsE6Y8WQ0"
      }

      info("can add replies to the original page")
      val lastReplyOrigPage = reply(theModerator.id, thePageId, "Last reply.")(dao)
      lastReplyOrigPage.nr mustBe (otherPost.nr + 1)

      info("can add replies to the new page")
      val lastPostPageTwo = dao.insertReplySkipAuZ(textAndHtmlMaker.testBody("Last post, page 2."), pageTwoId,
        replyToPostNrs = Set(maxNewNr), PostType.Normal, deleteDraftNr = None,
        Who(SystemUserId, browserIdData),
        dummySpamRelReqStuff).post
      lastPostPageTwo.nr mustBe (maxNewNr + 1)
    }

    "moves post read stats to new page  TyTMOPO_META" in {
      val ip = "1.2.3.4"
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page One"),
            textAndHtmlMaker.testBody("Body one."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postUnread = reply(theModerator.id, thePageId, "Not read, won't move")(dao)
      val postRead = reply(theModerator.id, thePageId, "Won't move this.")(dao)
      val postToMove = reply(theModerator.id, thePageId, "Will move this.")(dao)

      val pageTwoId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page Two"),
            textAndHtmlMaker.testBody("Body two."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postOnPageTwo = dao.insertReplySkipAuZ(textAndHtmlMaker.testBody("Post on page 2."), pageTwoId,
        replyToPostNrs = Set(PageParts.BodyNr), PostType.Normal, deleteDraftNr = None,
        Who(SystemUserId, browserIdData = browserIdData), dummySpamRelReqStuff).post

      val fromPageMetaBefore = dao.readOnlyTransaction(_.loadThePageMeta(thePageId))
      val toPageMetaBefore = dao.readOnlyTransaction(_.loadThePageMeta(pageTwoId))

      info("create post read stats, find on first page")
      dao.readWriteTransaction(_.updatePostsReadStats(
        thePageId, Set(postRead.nr, postToMove.nr), theModerator.id, Some(ip)))

      val fromPageReadStatsBefore = dao.readOnlyTransaction(_.loadPostsReadStats(thePageId))
      fromPageReadStatsBefore.guestIpsByPostNr.get(postUnread.nr) mustBe None
      fromPageReadStatsBefore.guestIpsByPostNr.get(postRead.nr) mustBe None
      fromPageReadStatsBefore.guestIpsByPostNr.get(postToMove.nr) mustBe None
      fromPageReadStatsBefore.roleIdsByPostNr.get(postUnread.nr) mustBe None
      fromPageReadStatsBefore.roleIdsByPostNr.get(postRead.nr) mustBe Some(Set(theModerator.id))
      fromPageReadStatsBefore.roleIdsByPostNr.get(postToMove.nr) mustBe Some(Set(theModerator.id))

      info("move a post")
      val postAfter = dao.movePostIfAuth(postToMove.pagePostId,
            MovePostWhere.UnderOldPost(postOnPageTwo.pagePostNr),
            theReqrStaff)._1
      postAfter.pageId mustBe pageTwoId
      postAfter.parentNr mustBe Some(postOnPageTwo.nr)

      info("post read stats moved to new page")
      val fromPageReadStatsAfter = dao.readOnlyTransaction(_.loadPostsReadStats(thePageId))
      val toPageReadStatsAfter = dao.readOnlyTransaction(_.loadPostsReadStats(pageTwoId))

      fromPageReadStatsAfter.guestIpsByPostNr.get(postUnread.nr) mustBe None
      fromPageReadStatsAfter.guestIpsByPostNr.get(postRead.nr) mustBe None
      fromPageReadStatsAfter.guestIpsByPostNr.get(postToMove.nr) mustBe None
      fromPageReadStatsAfter.roleIdsByPostNr.get(postUnread.nr) mustBe None
      fromPageReadStatsAfter.roleIdsByPostNr.get(postRead.nr) mustBe Some(Set(theModerator.id))
      fromPageReadStatsAfter.roleIdsByPostNr.get(postToMove.nr) mustBe None

      toPageReadStatsAfter.guestIpsByPostNr.get(postOnPageTwo.nr) mustBe None
      toPageReadStatsAfter.guestIpsByPostNr.get(postAfter.nr) mustBe None
      toPageReadStatsAfter.roleIdsByPostNr.get(postOnPageTwo.nr) mustBe None
      toPageReadStatsAfter.roleIdsByPostNr.get(postAfter.nr) mustBe Some(Set(theModerator.id))
    }

    "move a post (& replies) to new page, becomes orig post  TyTMOPO_NEWPG  TyTMOPO_META" in {
      val thePageId = createPage(PageType.Discussion, textAndHtmlMaker.testTitle("Page_One"),
            textAndHtmlMaker.testBody("Body_one."), SystemUserId, browserIdData, dao,
            anyCategoryId = Some(catA.id))
      val postX_0votes = reply(theMember.id, thePageId, "X")(dao)
      val postA = reply(theMember.id, thePageId, "A")(dao)
      val postAA = reply(theMemb2.id, thePageId, "AA", parentNr = Some(postA.nr))(dao)
      val postAAA = reply(theMemb2.id, thePageId, "AAA", parentNr = Some(postAA.nr))(dao)
      val postAAAA1 = reply(theMemb2.id, thePageId, "AAAA1", parentNr = Some(postAAA.nr))(dao)
      val postAAAA2_0votes = reply(theMemb2.id, thePageId, "AAAA2", parentNr = Some(postAAA.nr))(dao)
      val postB = reply(theMember.id, thePageId, "B")(dao)
      val postBB = reply(theMemb2.id, thePageId, "BB", parentNr = Some(postB.nr))(dao)
      val postC = reply(theMember.id, thePageId, "C")(dao)
      val postD = reply(theMember.id, thePageId, "D")(dao)
      val postDD = reply(theMemb3.id, thePageId, "DD", parentNr = Some(postD.nr))(dao)
      val postDDD = reply(theMemb3.id, thePageId, "DDD", parentNr = Some(postDD.nr))(dao)
      val postE_0votes = reply(theMember.id, thePageId, "E")(dao)  // top level comt, w/o replies
      val postF = reply(theMember.id, thePageId, "F")(dao)
       // A reply to a comment, w/o replies:
      val postFF = reply(theMember.id, thePageId, "FF", parentNr = Some(postF.nr))(dao)
      val postG = reply(theMember.id, thePageId, "G")(dao)

      val numRepliesBefore = 16
      val numOrigPostRepliesBefore = 8  // X A B C D E F G

      for {
        // Each of these 4 posts:
        postNr <- Seq(PageParts.BodyNr, postX_0votes.nr, postAAAA2_0votes.nr, postE_0votes.nr)
        // gets _4_likes, 3 wrongs, 2 burys, 1 unwanted:
        typeNum <- Seq((PostVoteType.Like, 4), (PostVoteType.Wrong, 3),
                       (PostVoteType.Bury, 2), (PostVoteType.Unwanted, 1))
      } {
        val (voteType, num) = typeNum
        // Can't vote on own post. Can vote just once per post. So we _need_4_voters.
        vote(theReqrVoter, thePageId, postNr = postNr, voteType)(dao)
        if (num >= 2) vote(theReqrVoter2, thePageId, postNr = postNr, voteType)(dao)
        if (num >= 3) vote(theReqrVoter3, thePageId, postNr = postNr, voteType)(dao)
        if (num >= 4) vote(theReqrVoter4, thePageId, postNr = postNr, voteType)(dao)
      }

      // Reload posts with proper vote counts.
      val (postX, postAAAA2, postE) =
            dao readTx { tx =>
              (tx.loadThePost(postX_0votes.id),
                  tx.loadThePost(postAAAA2_0votes.id),
                  tx.loadThePost(postE_0votes.id))
            }

      val metaBefore = dao.getThePageMeta(thePageId)
      metaBefore.numPostsTotal mustBe (2 + numRepliesBefore)  // 2 = title and orig post
      metaBefore.numRepliesTotal mustBe numRepliesBefore
      metaBefore.numRepliesVisible mustBe numRepliesBefore
      metaBefore.numOrigPostRepliesVisible mustBe numOrigPostRepliesBefore
      // Most replies are by theMember, the AA... are by theMemb2, just one by theMemb3.
      // But theMember is the last replyer, and therefore not incl.
      metaBefore.frequentPosterIds mustBe Seq(theMemb2.id, theMemb3.id)

      metaBefore.numLikes      mustBe (4 * 4)  // _4_likes
      metaBefore.numWrongs     mustBe (4 * 3)  // 3 wrongs
      metaBefore.numBurys      mustBe (4 * 2)  // 2 burys
      metaBefore.numUnwanteds  mustBe (4 * 1)  // 1 unwanted
      metaBefore.numOrigPostDoItVotes      mustBe (0) // unimpl, right
      metaBefore.numOrigPostDoNotVotes     mustBe (0) //
      metaBefore.numOrigPostLikeVotes      mustBe (4) // BodyNr has _4_likes
      metaBefore.numOrigPostWrongVotes     mustBe (3) //             3 wrongs
      metaBefore.numOrigPostBuryVotes      mustBe (2) //             2 burys
      metaBefore.numOrigPostUnwantedVotes  mustBe (1) //             1 unwanted

      info("can move a single top level comment: E to a new page")
      val postE_after = dao.movePostIfAuth(postE.pagePostId,
            MovePostWhere.ToNewDiscussion,
            theReqrStaff)._1
      postE_after.pageId must not be thePageId  // should be a new page
      postE_after.parentNr mustBe None
      postE_after.nr mustBe PageParts.BodyNr

      info("can move a nested comment: BB to a new page")
      val postBB_after = dao.movePostIfAuth(postBB.pagePostId,
            MovePostWhere.ToNewDiscussion,
            theReqrStaff)._1
      postBB_after.pageId must not be thePageId  // should be a new page
      postBB_after.parentNr mustBe None
      postBB_after.nr mustBe PageParts.BodyNr

      info("can move a comments tree: AA... to a new page")
      val postAA_after = dao.movePostIfAuth(postAA.pagePostId,
            MovePostWhere.ToNewDiscussion,
            theReqrStaff)._1
      postAA_after.pageId must not be thePageId  // should be a new page
      postAA_after.parentNr mustBe None
      postAA_after.nr mustBe PageParts.BodyNr

      // Inspect the result (original page, and new pages):

      val (pageAA_id) = dao.readTx { tx =>
        val firstParts = dao.newPageDao(thePageId, tx).parts
        info("moved the specified comments: AA, BB, E")
        firstParts.postByNr(postAA.nr)  mustBe None
        firstParts.postByNr(postBB.nr)  mustBe None
        firstParts.postByNr(postE.nr)   mustBe None

        info("didn't move too many comments")
        firstParts.postByNr(postX.nr)   mustBe  Some(postX)  // not moved, still here
        firstParts.postByNr(postA.nr)   mustBe  Some(postA)  // not moved
        // AA... moved.
        firstParts.postByNr(postB.nr)   mustBe  Some(postB)
        // BB moved.
        firstParts.postByNr(postC.nr)   mustBe  Some(postC)
        firstParts.postByNr(postD.nr)   mustBe  Some(postD)
        firstParts.postByNr(postDD.nr)  mustBe  Some(postDD)
        firstParts.postByNr(postDDD.nr) mustBe  Some(postDDD)
        // E moved.
        firstParts.postByNr(postF.nr)   mustBe  Some(postF)
        firstParts.postByNr(postFF.nr)  mustBe  Some(postFF)
        firstParts.postByNr(postG.nr)   mustBe  Some(postG)

        val numMoved = 6  // AA AAA AAAA1 AAAA2,  BB,  E

        info("Page meta updated: fewer replies")
        val metaAfter = firstParts.pageMeta
        metaAfter.numPostsTotal mustBe (2 + numRepliesBefore - numMoved)
        metaAfter.numRepliesTotal mustBe (numRepliesBefore - numMoved)
        metaAfter.numRepliesVisible mustBe (numRepliesBefore - numMoved)
        // Only one orig post reply was moved:  E
        metaAfter.numOrigPostRepliesVisible mustBe (numOrigPostRepliesBefore - 1)
        // All theMemb2's posts got moved, gone.
        // theMember is the last replyer, and therefore not incl. So, only theMemb3:
        metaAfter.frequentPosterIds mustBe Seq(theMemb3.id)

        // There used to be `4 * ...` (4 = posts BodyNr, X, AAAA2, EE)
        // but now there's `2 * ...` since AAAA2 and EE got moved.
        metaAfter.numLikes     mustBe (2 * 4)  // still _4_likes
        metaAfter.numWrongs    mustBe (2 * 3)  // and 3 wrongs, etc
        metaAfter.numBurys     mustBe (2 * 2)
        metaAfter.numUnwanteds mustBe (2 * 1)
        metaAfter.numOrigPostDoItVotes     mustBe (0)
        metaAfter.numOrigPostDoNotVotes    mustBe (0)
        metaAfter.numOrigPostLikeVotes     mustBe (4) // orig post still there, w _4_likes
        metaAfter.numOrigPostWrongVotes    mustBe (3)
        metaAfter.numOrigPostBuryVotes     mustBe (2)
        metaAfter.numOrigPostUnwantedVotes mustBe (1)

        TESTS_MISSING // lastApprovedReplyById, lastApprovedReplyAt  TyTMOPO_META_LATEST
        TESTS_MISSING // bumpedAt, updatedAt

        info("E is the orig post of a new page, w/o replies")
        val pageE = dao.newPageDao(postE_after.pageId, tx)
        pageE.parts.thePostById(postE.id) mustBe postE_after
        postE_after.parentNr mustBe None
        postE_after.nr mustBe PageParts.BodyNr
        pageE.parts.ancestorsParentFirstOf(postE_after.nr).map(_.nr) mustBe Nil
        pageE.parts.highestReplyNr mustBe None // no replies

        info("Page E meta looks ok")
        pageE.meta.numPostsTotal mustBe 2  // title + orig post
        pageE.meta.numRepliesTotal mustBe 0
        pageE.meta.numRepliesVisible mustBe 0
        pageE.meta.numOrigPostRepliesVisible mustBe 0
        pageE.meta.frequentPosterIds mustBe Nil // author not incl in list
        pageE.meta.numLikes      mustBe (4)  // Post E has _4_likes
        pageE.meta.numWrongs     mustBe (3)  // 3 wrongs
        pageE.meta.numBurys      mustBe (2)  // ...
        pageE.meta.numUnwanteds  mustBe (1)  //
        pageE.meta.numOrigPostDoItVotes      mustBe (0)
        pageE.meta.numOrigPostDoNotVotes     mustBe (0)
        pageE.meta.numOrigPostLikeVotes      mustBe (4) // Post E = BodyNr has _4_likes,
        pageE.meta.numOrigPostWrongVotes     mustBe (3) //                      3 wrongs
        pageE.meta.numOrigPostBuryVotes      mustBe (2) //                      ...
        pageE.meta.numOrigPostUnwantedVotes  mustBe (1) //

        TESTS_MISSING // these:  TyTMOPO_META_LATEST  (& at more places below)
        // lastApprovedReplyAt, lastApprovedReplyById, bumpedAt, updatedAt

        info("BB is the orig post of a new page, w/o replies")
        val pageBB = dao.newPageDao(postBB_after.pageId, tx)
        pageBB.parts.thePostById(postBB.id) mustBe postBB_after
        postBB_after.parentNr mustBe None
        postBB_after.nr mustBe PageParts.BodyNr
        pageBB.parts.ancestorsParentFirstOf(postBB_after.nr).map(_.nr) mustBe Nil
        pageBB.parts.highestReplyNr mustBe None // no replies
        pageBB.meta.numRepliesTotal mustBe 0
        pageBB.meta.numPostsTotal mustBe 2  // title + orig post
        // COULD do same tests as for PageE above.

        info("AA is the orig post of a new page")
        val pageAA = dao.newPageDao(postAA_after.pageId, tx)
        pageAA.parts.thePostById(postAA.id) mustBe postAA_after
        postAA_after.parentNr mustBe None
        postAA_after.nr mustBe PageParts.BodyNr
        pageAA.parts.ancestorsParentFirstOf(postAA_after.nr).map(_.nr) mustBe Nil

        info("the descendants (AAA, AAAA1 AAAA2) got moved too")
        val postAAA_after = pageAA.parts.thePostById(postAAA.id)
        val postAAAA1_after = pageAA.parts.thePostById(postAAAA1.id)
        val postAAAA2_after = pageAA.parts.thePostById(postAAAA2.id)

        info("page AA meta looks ok")
        pageAA.parts.highestReplyNr mustBe Some(1 + 3)
        pageAA.meta.numPostsTotal mustBe 5  // title + orig post + 3 replies
        pageAA.meta.numRepliesTotal mustBe 3
        pageAA.meta.numRepliesVisible mustBe 3
        pageAA.meta.numOrigPostRepliesVisible mustBe 1 // AAA
        TESTS_MISSING // another author, so won't be empty?
        pageAA.meta.frequentPosterIds mustBe Nil // theMember2 = author, not incl in list
        pageAA.meta.numLikes      mustBe (4)  // Comment AAAA2 has _4_likes
        pageAA.meta.numWrongs     mustBe (3)  //                  3 wrongs
        pageAA.meta.numBurys      mustBe (2)  //                  ...
        pageAA.meta.numUnwanteds  mustBe (1)  //
        pageAA.meta.numOrigPostDoItVotes      mustBe (0)
        pageAA.meta.numOrigPostDoNotVotes     mustBe (0)
        pageAA.meta.numOrigPostLikeVotes      mustBe (0) // BodyNr has 0 likes
        pageAA.meta.numOrigPostWrongVotes     mustBe (0) // — only AAAA2 has any votes.
        pageAA.meta.numOrigPostBuryVotes      mustBe (0) //
        pageAA.meta.numOrigPostUnwantedVotes  mustBe (0) //

        info("all the AAA... comments have their relative structure intact")
        postAAA_after.nr mustBe PageParts.FirstReplyNr  // FirstReplyNr = 2
        postAAAA1_after.nr must (be(3) or be(4)) // either PageParts.FirstReplyNr + 1 or + 2
        postAAAA2_after.nr must (be(3) or be(4)) //
        postAAAA1_after.nr must not be postAAAA2_after.nr

        postAAA_after.parentNr   mustBe Some(postAA_after.nr)
        postAAAA1_after.parentNr mustBe Some(postAAA_after.nr)
        postAAAA2_after.parentNr mustBe Some(postAAA_after.nr)

        pageAA.parts.ancestorsParentFirstOf(postAAA_after.nr).map(_.nr) mustBe Seq(BodyNr)
        pageAA.parts.ancestorsParentFirstOf(postAAAA1_after.nr).map(_.nr) mustBe Seq(
              postAAA_after.nr, BodyNr)
        pageAA.parts.ancestorsParentFirstOf(postAAAA2_after.nr).map(_.nr) mustBe Seq(
              postAAA_after.nr, BodyNr)

        pageAA.id
      }

      info("can add replies to the original page")
      val lastReplyOrigPage = reply(theModerator.id, thePageId, "One more reply.")(dao)
      lastReplyOrigPage.nr mustBe (postG.nr + 1)

      info("can add replies to the new pages")
      val lastReplyPageAA = reply(theModerator.id, pageAA_id, "AAB")(dao)
      lastReplyPageAA.nr mustBe (5)  // AAAA1 and AAAA2 are nr 3 an 4 (unkn order)
    }
  }

}
